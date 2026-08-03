package com.zuicun.liptalkdemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.SystemClock;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Optional InsightFace apparent age/gender PoC.
 * Official pretrained weights are for non-commercial research only.
 */
public final class AgeGenderEngine implements AutoCloseable {
    private static final String MODEL_FILE = "genderage.onnx";
    private static final String RKNN_MODEL_FILE = "genderage_rk3576_fp16.rknn";
    private static final int INPUT_SIZE = 96;
    private static final int INFERENCE_VIEWS = 2;
    private static final long REFRESH_INTERVAL_MS = 1000L;
    private static final int RIGHT_EYE_IRIS = 468;
    private static final int LEFT_EYE_IRIS = 473;
    private static final int NOSE_TIP = 1;
    private static final int RIGHT_MOUTH_CORNER = 61;
    private static final int LEFT_MOUTH_CORNER = 291;
    // InsightFace's five-point ArcFace template, scaled from 112 to 96 pixels.
    private static final float[] ALIGNMENT_TEMPLATE = {
            32.824f, 44.311f,
            63.027f, 44.144f,
            48.022f, 61.488f,
            35.614f, 79.170f,
            60.626f, 79.032f
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final RknnSession rknnSession;
    private volatile long lastSubmittedAt;
    private volatile int lastTrackId = -1;

    public AgeGenderEngine(Context context) throws IOException, OrtException {
        rknnSession = RknnSession.tryCreate(context, RKNN_MODEL_FILE, 3);
        if (rknnSession != null) {
            environment = null;
            session = null;
            inputName = null;
        } else {
            environment = OrtEnvironment.getEnvironment();
            byte[] model = readAsset(context, MODEL_FILE);
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            session = environment.createSession(model, options);
            inputName = session.getInputNames().iterator().next();
        }
    }

    public boolean submit(
            Bitmap frame,
            MultiFaceAnalyzer.FaceReading face,
            Listener listener
    ) {
        long now = SystemClock.uptimeMillis();
        boolean trackChanged = face.trackId != lastTrackId;
        if (!trackChanged && now - lastSubmittedAt < REFRESH_INTERVAL_MS) return false;
        if (!inFlight.compareAndSet(false, true)) return false;

        lastSubmittedAt = now;
        lastTrackId = face.trackId;
        Bitmap crop;
        try {
            crop = alignFace(frame, face.landmarks);
        } catch (RuntimeException error) {
            inFlight.set(false);
            listener.onAttributeError(error.getMessage());
            return false;
        }
        if (crop == null) {
            inFlight.set(false);
            return false;
        }
        executor.execute(() -> {
            long startedAt = SystemClock.uptimeMillis();
            try {
                RawResult result = infer(crop);
                listener.onAttributeResult(
                        face.trackId,
                        result.gender,
                        result.age,
                        result.confidence,
                        SystemClock.uptimeMillis() - startedAt);
            } catch (Exception error) {
                listener.onAttributeError(
                        error.getMessage() == null ? "属性模型推理失败" : error.getMessage());
            } finally {
                crop.recycle();
                inFlight.set(false);
            }
        });
        return true;
    }

    public void resetTracking() {
        lastTrackId = -1;
        lastSubmittedAt = 0L;
    }

    public String backendName() {
        return rknnSession == null ? "ONNX CPU" : "RKNN NPU";
    }

    private RawResult infer(Bitmap bitmap) throws OrtException {
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        if (rknnSession != null) {
            float[] original = rknnSession.run(toRgbBytes(pixels, false));
            float[] flipped = rknnSession.run(toRgbBytes(pixels, true));
            return decode(original, flipped);
        }
        float[] input = new float[INFERENCE_VIEWS * 3 * INPUT_SIZE * INPUT_SIZE];
        int plane = INPUT_SIZE * INPUT_SIZE;
        int viewStride = 3 * plane;
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int destination = y * INPUT_SIZE + x;
                writeRgb(input, destination, plane, pixels[destination]);
                int flippedSource = y * INPUT_SIZE + (INPUT_SIZE - 1 - x);
                writeRgb(input, viewStride + destination, plane, pixels[flippedSource]);
            }
        }

        try (OnnxTensor tensor = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(input),
                new long[]{INFERENCE_VIEWS, 3, INPUT_SIZE, INPUT_SIZE});
             OrtSession.Result outputs = session.run(Collections.singletonMap(inputName, tensor))) {
            float[][] output = (float[][]) outputs.get(0).getValue();
            return decode(output[0], output[1]);
        }
    }

    private static RawResult decode(float[] original, float[] flipped) {
        float femaleLogit = (original[0] + flipped[0]) * 0.5f;
        float maleLogit = (original[1] + flipped[1]) * 0.5f;
        int gender = maleLogit > femaleLogit ? 1 : 0;
        float confidence = softmaxConfidence(femaleLogit, maleLogit);
        // The age head is not flip-invariant, so retain the aligned original view.
        int age = Math.round(original[2] * 100f);
        return new RawResult(gender, Math.max(0, Math.min(100, age)), confidence);
    }

    private static byte[] toRgbBytes(int[] pixels, boolean horizontalFlip) {
        byte[] rgb = new byte[INPUT_SIZE * INPUT_SIZE * 3];
        int destination = 0;
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int sourceX = horizontalFlip ? INPUT_SIZE - 1 - x : x;
                int pixel = pixels[y * INPUT_SIZE + sourceX];
                rgb[destination++] = (byte) ((pixel >> 16) & 0xff);
                rgb[destination++] = (byte) ((pixel >> 8) & 0xff);
                rgb[destination++] = (byte) (pixel & 0xff);
            }
        }
        return rgb;
    }

    private static void writeRgb(float[] input, int offset, int plane, int pixel) {
        // The model starts with Sub(127.5) and Mul(1/128), so pass raw RGB values.
        input[offset] = (pixel >> 16) & 0xff;
        input[offset + plane] = (pixel >> 8) & 0xff;
        input[offset + plane * 2] = pixel & 0xff;
    }

    private static Bitmap alignFace(Bitmap frame, List<NormalizedLandmark> landmarks) {
        if (landmarks.size() <= LEFT_EYE_IRIS) {
            throw new IllegalArgumentException("人脸关键点不完整");
        }
        float[] source = {
                pixelX(landmarks, RIGHT_EYE_IRIS, frame), pixelY(landmarks, RIGHT_EYE_IRIS, frame),
                pixelX(landmarks, LEFT_EYE_IRIS, frame), pixelY(landmarks, LEFT_EYE_IRIS, frame),
                pixelX(landmarks, NOSE_TIP, frame), pixelY(landmarks, NOSE_TIP, frame),
                pixelX(landmarks, RIGHT_MOUTH_CORNER, frame),
                pixelY(landmarks, RIGHT_MOUTH_CORNER, frame),
                pixelX(landmarks, LEFT_MOUTH_CORNER, frame),
                pixelY(landmarks, LEFT_MOUTH_CORNER, frame)
        };

        // Front-camera frames may be mirrored. Align image-left features to image-left template.
        swapPairsByHorizontalOrder(source, 0, 2);
        swapPairsByHorizontalOrder(source, 6, 8);
        float eyeDistance = distance(source[0], source[1], source[2], source[3]);
        if (eyeDistance < 28f) return null;
        float nosePosition = projectionRatio(
                source[4], source[5], source[0], source[1], source[2], source[3]);
        if (nosePosition < 0.18f || nosePosition > 0.82f) return null;

        Matrix transform = similarityTransform(source, ALIGNMENT_TEMPLATE);
        Bitmap output = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(android.graphics.Color.BLACK);
        canvas.drawBitmap(frame, transform, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        if (!hasUsableImageQuality(output)) {
            output.recycle();
            return null;
        }
        return output;
    }

    private static float pixelX(List<NormalizedLandmark> landmarks, int index, Bitmap frame) {
        return landmarks.get(index).x() * frame.getWidth();
    }

    private static float pixelY(List<NormalizedLandmark> landmarks, int index, Bitmap frame) {
        return landmarks.get(index).y() * frame.getHeight();
    }

    private static void swapPairsByHorizontalOrder(float[] points, int first, int second) {
        if (points[first] <= points[second]) return;
        float x = points[first];
        float y = points[first + 1];
        points[first] = points[second];
        points[first + 1] = points[second + 1];
        points[second] = x;
        points[second + 1] = y;
    }

    /** Least-squares similarity transform: scale + rotation + translation, without skew. */
    private static Matrix similarityTransform(float[] source, float[] destination) {
        int count = source.length / 2;
        float sourceMeanX = 0f;
        float sourceMeanY = 0f;
        float destinationMeanX = 0f;
        float destinationMeanY = 0f;
        for (int index = 0; index < source.length; index += 2) {
            sourceMeanX += source[index];
            sourceMeanY += source[index + 1];
            destinationMeanX += destination[index];
            destinationMeanY += destination[index + 1];
        }
        sourceMeanX /= count;
        sourceMeanY /= count;
        destinationMeanX /= count;
        destinationMeanY /= count;

        double denominator = 0d;
        double cosine = 0d;
        double sine = 0d;
        for (int index = 0; index < source.length; index += 2) {
            double x = source[index] - sourceMeanX;
            double y = source[index + 1] - sourceMeanY;
            double u = destination[index] - destinationMeanX;
            double v = destination[index + 1] - destinationMeanY;
            denominator += x * x + y * y;
            cosine += x * u + y * v;
            sine += x * v - y * u;
        }
        if (denominator < 1e-6d) throw new IllegalArgumentException("目标人脸过小");
        float a = (float) (cosine / denominator);
        float b = (float) (sine / denominator);
        float translateX = destinationMeanX - a * sourceMeanX + b * sourceMeanY;
        float translateY = destinationMeanY - b * sourceMeanX - a * sourceMeanY;
        Matrix matrix = new Matrix();
        matrix.setValues(new float[]{
                a, -b, translateX,
                b, a, translateY,
                0f, 0f, 1f
        });
        return matrix;
    }

    private static float projectionRatio(
            float x, float y, float startX, float startY, float endX, float endY
    ) {
        float dx = endX - startX;
        float dy = endY - startY;
        float denominator = dx * dx + dy * dy;
        return denominator <= 0f ? 0.5f
                : ((x - startX) * dx + (y - startY) * dy) / denominator;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private static boolean hasUsableImageQuality(Bitmap bitmap) {
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        double brightness = 0d;
        double brightnessSquared = 0d;
        double gradient = 0d;
        int gradientCount = 0;
        int[] previousRow = new int[INPUT_SIZE];
        for (int y = 0; y < INPUT_SIZE; y++) {
            int previous = -1;
            for (int x = 0; x < INPUT_SIZE; x++) {
                int pixel = pixels[y * INPUT_SIZE + x];
                int gray = Math.round(
                        0.299f * ((pixel >> 16) & 0xff)
                                + 0.587f * ((pixel >> 8) & 0xff)
                                + 0.114f * (pixel & 0xff));
                brightness += gray;
                brightnessSquared += gray * gray;
                if (previous >= 0) {
                    gradient += Math.abs(gray - previous);
                    gradientCount++;
                }
                if (y > 0) {
                    gradient += Math.abs(gray - previousRow[x]);
                    gradientCount++;
                }
                previous = gray;
                previousRow[x] = gray;
            }
        }
        double count = pixels.length;
        double mean = brightness / count;
        double standardDeviation = Math.sqrt(
                Math.max(0d, brightnessSquared / count - mean * mean));
        double meanGradient = gradientCount == 0 ? 0d : gradient / gradientCount;
        return mean >= 32d && mean <= 225d && standardDeviation >= 18d && meanGradient >= 4d;
    }

    private static float softmaxConfidence(float first, float second) {
        float maximum = Math.max(first, second);
        double firstExp = Math.exp(first - maximum);
        double secondExp = Math.exp(second - maximum);
        return (float) (Math.max(firstExp, secondExp) / (firstExp + secondExp));
    }

    private static byte[] readAsset(Context context, String name) throws IOException {
        try (InputStream input = context.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        if (rknnSession != null) rknnSession.close();
        if (session == null) return;
        try {
            session.close();
        } catch (OrtException ignored) {
            // Best-effort cleanup during Activity destruction.
        }
    }

    public interface Listener {
        void onAttributeResult(int trackId, int gender, int age, float confidence, long latencyMs);

        void onAttributeError(String message);
    }

    private static final class RawResult {
        final int gender;
        final int age;
        final float confidence;

        RawResult(int gender, int age, float confidence) {
            this.gender = gender;
            this.age = age;
            this.confidence = confidence;
        }
    }
}
