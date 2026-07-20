package com.zuicun.liptalkdemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;

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
    private final FaceLandmarker landmarker;

    public FaceLandmarkerEngine(Context context, DetectionMode mode, Listener listener) {
        this.listener = listener;
        this.mode = mode;
        BaseOptions baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
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

    public void detect(ImageProxy imageProxy, boolean frontCamera) {
        long timestamp = SystemClock.uptimeMillis();
        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        Bitmap bitmap = Bitmap.createBitmap(
                imageProxy.getWidth(), imageProxy.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            imageProxy.getPlanes()[0].getBuffer().rewind();
            bitmap.copyPixelsFromBuffer(imageProxy.getPlanes()[0].getBuffer());
        } finally {
            imageProxy.close();
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        if (frontCamera) matrix.postScale(-1f, 1f);
        Bitmap transformed = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        MPImage image = new BitmapImageBuilder(transformed).build();
        landmarker.detectAsync(image, timestamp);
    }

    private void handleResult(FaceLandmarkerResult result, MPImage input) {
        if (result.faceLandmarks().isEmpty()) {
            listener.onNoFace(mode);
            return;
        }
        listener.onResult(
                result,
                input.getWidth(),
                input.getHeight(),
                SystemClock.uptimeMillis() - result.timestampMs(),
                mode);
    }

    @Override
    public void close() {
        landmarker.close();
    }

    public interface Listener {
        void onResult(
                FaceLandmarkerResult result,
                int width,
                int height,
                long latencyMs,
                DetectionMode mode);
        void onNoFace(DetectionMode mode);
        void onError(String message);
    }
}
