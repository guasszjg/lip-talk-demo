package com.zuicun.liptalkdemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import androidx.camera.core.ImageProxy;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

public final class FaceLandmarkerEngine implements AutoCloseable {
    private static final String MODEL_FILE = "face_landmarker.task";
    private final Listener listener;
    private final DetectionMode mode;
    private final InferenceBackend backend;
    private final FaceLandmarker landmarker;
    private final RknnFacePipeline rknnPipeline;
    private final Map<Long, Bitmap> pendingFrames = new LinkedHashMap<>();

    public FaceLandmarkerEngine(
            Context context,
            DetectionMode mode,
            InferenceBackend backend,
            Listener listener
    ) {
        this.listener = listener;
        this.mode = mode;
        this.backend = backend;
        rknnPipeline = RknnFacePipeline.tryCreate(context, mode.maximumFaces);
        if (rknnPipeline != null) {
            landmarker = null;
            return;
        }
        BaseOptions baseOptions = BaseOptions.builder()
                .setDelegate(backend == InferenceBackend.GPU ? Delegate.GPU : Delegate.CPU)
                .setModelAssetPath(MODEL_FILE)
                .build();
        FaceLandmarker.FaceLandmarkerOptions options =
                FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumFaces(mode.maximumFaces)
                        .setMinFaceDetectionConfidence(0.5f)
                        .setMinFacePresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setResultListener(this::handleResult)
                        .setErrorListener(error -> listener.onError(
                                error.getMessage() == null ? "人脸模型运行失败" : error.getMessage()))
                        .build();
        landmarker = FaceLandmarker.createFromOptions(context, options);
    }

    public boolean usesRknn() {
        return rknnPipeline != null;
    }

    public String backendName() {
        return usesRknn() ? "RKNN NPU" : backend.displayName;
    }

    public void detect(ImageProxy imageProxy, boolean frontCamera) {
        long timestamp = SystemClock.uptimeMillis();
        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        Bitmap bitmap;
        try {
            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
            int[] pixels = planes.length == 3
                    ? yuv420ToArgb(planes, width, height)
                    : rgbaToArgb(planes[0], width, height);
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        } finally {
            imageProxy.close();
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        if (frontCamera) matrix.postScale(-1f, 1f);
        Bitmap transformed = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (transformed != bitmap && !bitmap.isRecycled()) bitmap.recycle();
        if (rknnPipeline != null) {
            try {
                List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>>
                        faces = rknnPipeline.detect(transformed);
                if (faces.isEmpty()) {
                    listener.onNoFace(mode, backend);
                } else {
                    listener.onResult(
                            faces,
                            timestamp,
                            transformed,
                            transformed.getWidth(),
                            transformed.getHeight(),
                            SystemClock.uptimeMillis() - timestamp,
                            mode,
                            backend);
                }
            } finally {
                if (!transformed.isRecycled()) transformed.recycle();
            }
            return;
        }
        synchronized (pendingFrames) {
            pendingFrames.put(timestamp, transformed);
            while (pendingFrames.size() > 4) {
                Long oldest = pendingFrames.keySet().iterator().next();
                Bitmap dropped = pendingFrames.remove(oldest);
                if (dropped != null && !dropped.isRecycled()) dropped.recycle();
            }
        }
        MPImage image = new BitmapImageBuilder(transformed).build();
        landmarker.detectAsync(image, timestamp);
    }

    private static int[] yuv420ToArgb(
            ImageProxy.PlaneProxy[] planes, int width, int height
    ) {
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int yBase = yBuffer.position(), uBase = uBuffer.position(), vBase = vBuffer.position();
        int yRow = planes[0].getRowStride(), uRow = planes[1].getRowStride();
        int vRow = planes[2].getRowStride();
        int yPixel = planes[0].getPixelStride(), uPixel = planes[1].getPixelStride();
        int vPixel = planes[2].getPixelStride();
        int[] output = new int[width * height];
        for (int row = 0; row < height; row++) {
            int uvRow = row >> 1;
            for (int column = 0; column < width; column++) {
                int uvColumn = column >> 1;
                int y = yBuffer.get(yBase + row * yRow + column * yPixel) & 0xff;
                int u = uBuffer.get(uBase + uvRow * uRow + uvColumn * uPixel) & 0xff;
                int v = vBuffer.get(vBase + uvRow * vRow + uvColumn * vPixel) & 0xff;
                int c = Math.max(0, y - 16), d = u - 128, e = v - 128;
                int red = clamp((298 * c + 409 * e + 128) >> 8);
                int green = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                int blue = clamp((298 * c + 516 * d + 128) >> 8);
                output[row * width + column] =
                        0xff000000 | (red << 16) | (green << 8) | blue;
            }
        }
        return output;
    }

    private static int[] rgbaToArgb(ImageProxy.PlaneProxy plane, int width, int height) {
        ByteBuffer buffer = plane.getBuffer();
        int base = buffer.position();
        int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
        int[] output = new int[width * height];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int offset = base + row * rowStride + column * pixelStride;
                int red = buffer.get(offset) & 0xff;
                int green = buffer.get(offset + 1) & 0xff;
                int blue = buffer.get(offset + 2) & 0xff;
                output[row * width + column] =
                        0xff000000 | (red << 16) | (green << 8) | blue;
            }
        }
        return output;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void handleResult(FaceLandmarkerResult result, MPImage input) {
        Bitmap frame;
        synchronized (pendingFrames) {
            frame = pendingFrames.remove(result.timestampMs());
        }
        if (frame == null) return;
        try {
            if (result.faceLandmarks().isEmpty()) {
                listener.onNoFace(mode, backend);
                return;
            }
            listener.onResult(
                    result.faceLandmarks(),
                    result.timestampMs(),
                    frame,
                    frame.getWidth(),
                    frame.getHeight(),
                    SystemClock.uptimeMillis() - result.timestampMs(),
                    mode,
                    backend);
        } finally {
            if (!frame.isRecycled()) frame.recycle();
        }
    }

    @Override
    public void close() {
        if (landmarker != null) landmarker.close();
        if (rknnPipeline != null) rknnPipeline.close();
        synchronized (pendingFrames) {
            for (Bitmap frame : pendingFrames.values()) {
                if (!frame.isRecycled()) frame.recycle();
            }
            pendingFrames.clear();
        }
    }

    public interface Listener {
        void onResult(
                List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>> faces,
                long timestampMs,
                Bitmap frame,
                int width,
                int height,
                long latencyMs,
                DetectionMode mode,
                InferenceBackend backend);
        void onNoFace(DetectionMode mode, InferenceBackend backend);
        void onError(String message);
    }
}
