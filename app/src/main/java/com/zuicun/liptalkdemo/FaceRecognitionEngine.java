package com.zuicun.liptalkdemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.SystemClock;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** Multi-administrator face embedding and 1:N matching engine for local research testing. */
public final class FaceRecognitionEngine implements AutoCloseable {
    private static final int INPUT_SIZE = 112;
    private static final String RKNN_MODEL_FILE = "w600k_mbf_rk3576_fp16.rknn";
    private static final long REFRESH_MS = 650L;
    private static final float MATCH_THRESHOLD = 0.42f;
    private static final float AMBIGUITY_MARGIN = 0.04f;
    private static final int CONFIRMATIONS = 2;
    private static final float[] TEMPLATE = {
            38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f,
            41.5493f, 92.3655f, 70.7299f, 92.2041f
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final RknnSession rknnSession;
    private final AdminFaceStore store;
    private final Map<Integer, TrackEvidence> evidence = new ConcurrentHashMap<>();
    private volatile long lastSubmittedAt;

    public FaceRecognitionEngine(Context context) throws Exception {
        rknnSession = RknnSession.tryCreate(context, RKNN_MODEL_FILE, 512);
        if (rknnSession != null) {
            environment = null;
            session = null;
            inputName = null;
        } else {
            environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            session = environment.createSession(readAsset(context, "w600k_mbf.onnx"), options);
            inputName = session.getInputNames().iterator().next();
        }
        store = new AdminFaceStore(context);
    }

    public int adminCount() {
        return store.list().size();
    }

    public List<AdminFaceStore.Record> admins() {
        return store.list();
    }

    public String backendName() {
        return rknnSession == null ? "ONNX CPU" : "RKNN NPU";
    }

    public void enroll(
            Bitmap frame,
            List<NormalizedLandmark> landmarks,
            String name,
            EnrollmentListener listener
    ) {
        Bitmap face = align(frame, landmarks);
        if (face == null) {
            listener.onEnrollmentError("人脸太小或姿态偏转过大，请正对摄像头");
            return;
        }
        executor.execute(() -> {
            try {
                float[] embedding = infer(face, false, false);
                ByteArrayOutputStream avatar = new ByteArrayOutputStream();
                face.compress(Bitmap.CompressFormat.JPEG, 90, avatar);
                AdminFaceStore.Record record = store.add(
                        name.trim(), embedding, avatar.toByteArray(), System.currentTimeMillis());
                evidence.clear();
                listener.onEnrolled(record, store.list().size());
            } catch (Exception error) {
                listener.onEnrollmentError(message(error, "管理员注册失败"));
            } finally {
                face.recycle();
            }
        });
    }

    public boolean submit(
            Bitmap frame,
            List<MultiFaceAnalyzer.FaceReading> faces,
            RecognitionListener listener
    ) {
        if (store.list().isEmpty() || faces.isEmpty()) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastSubmittedAt < REFRESH_MS || !inFlight.compareAndSet(false, true)) return false;
        lastSubmittedAt = now;
        List<FaceInput> inputs = new ArrayList<>();
        for (MultiFaceAnalyzer.FaceReading reading : faces) {
            Bitmap aligned = align(frame, reading.landmarks);
            if (aligned != null) inputs.add(new FaceInput(reading.trackId, aligned));
        }
        if (inputs.isEmpty()) {
            inFlight.set(false);
            return false;
        }
        executor.execute(() -> {
            long started = SystemClock.uptimeMillis();
            Map<Integer, Match> matches = new HashMap<>();
            try {
                List<AdminFaceStore.Record> admins = store.list();
                for (FaceInput input : inputs) {
                    float[] embedding = infer(input.bitmap, false, false);
                    float[] flippedEmbedding = infer(input.bitmap, true, false);
                    matches.put(input.trackId, match(
                            input.trackId,
                            embedding,
                            flippedEmbedding,
                            admins));
                }
                listener.onRecognition(matches, SystemClock.uptimeMillis() - started);
            } catch (Exception error) {
                listener.onRecognitionError(message(error, "主人识别失败"));
            } finally {
                for (FaceInput input : inputs) input.bitmap.recycle();
                inFlight.set(false);
            }
        });
        return true;
    }

    public void delete(String id) throws Exception {
        store.delete(id);
        evidence.clear();
    }

    public void clearAdmins() throws Exception {
        store.clear();
        evidence.clear();
    }

    public void reloadAdmins() throws Exception {
        store.reload();
        evidence.clear();
    }

    public void resetTracking() {
        evidence.clear();
        lastSubmittedAt = 0L;
    }

    private Match match(
            int trackId,
            float[] embedding,
            float[] flippedEmbedding,
            List<AdminFaceStore.Record> admins
    ) {
        AdminFaceStore.Record best = null;
        float bestScore = -1f;
        float secondScore = -1f;
        for (AdminFaceStore.Record admin : admins) {
            float score = Math.max(
                    cosine(embedding, admin.embedding),
                    cosine(flippedEmbedding, admin.embedding));
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = admin;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        boolean candidate = best != null
                && bestScore >= MATCH_THRESHOLD
                && (admins.size() == 1 || bestScore - secondScore >= AMBIGUITY_MARGIN);
        TrackEvidence track = evidence.computeIfAbsent(trackId, ignored -> new TrackEvidence());
        String candidateId = candidate ? best.id : null;
        if (candidateId != null && candidateId.equals(track.adminId)) {
            track.confirmations++;
        } else {
            track.adminId = candidateId;
            track.confirmations = candidate ? 1 : 0;
        }
        boolean confirmed = candidate && track.confirmations >= CONFIRMATIONS;
        return new Match(best == null ? null : best.id, best == null ? null : best.name,
                bestScore, confirmed);
    }

    private float[] infer(
            Bitmap bitmap, boolean horizontalFlip, boolean swapRedBlue
    ) throws Exception {
        int plane = INPUT_SIZE * INPUT_SIZE;
        int[] pixels = new int[plane];
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        if (rknnSession != null) {
            float[] embedding = rknnSession.run(
                    toRgbBytes(pixels, horizontalFlip, swapRedBlue));
            normalize(embedding);
            return embedding;
        }
        float[] input = new float[plane * 3];
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int destination = y * INPUT_SIZE + x;
                int sourceX = horizontalFlip ? INPUT_SIZE - 1 - x : x;
                int pixel = pixels[y * INPUT_SIZE + sourceX];
                int red = (pixel >> 16) & 0xff;
                int blue = pixel & 0xff;
                input[destination] = ((swapRedBlue ? blue : red) - 127.5f) / 127.5f;
                input[plane + destination] = (((pixel >> 8) & 0xff) - 127.5f) / 127.5f;
                input[plane * 2 + destination] =
                        ((swapRedBlue ? red : blue) - 127.5f) / 127.5f;
            }
        }
        try (OnnxTensor tensor = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(input), new long[]{1, 3, INPUT_SIZE, INPUT_SIZE});
             OrtSession.Result result =
                     session.run(Collections.singletonMap(inputName, tensor))) {
            float[] embedding = ((float[][]) result.get(0).getValue())[0].clone();
            normalize(embedding);
            return embedding;
        }
    }

    private static byte[] toRgbBytes(
            int[] pixels, boolean horizontalFlip, boolean swapRedBlue
    ) {
        byte[] rgb = new byte[INPUT_SIZE * INPUT_SIZE * 3];
        int destination = 0;
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int sourceX = horizontalFlip ? INPUT_SIZE - 1 - x : x;
                int pixel = pixels[y * INPUT_SIZE + sourceX];
                int red = (pixel >> 16) & 0xff;
                int green = (pixel >> 8) & 0xff;
                int blue = pixel & 0xff;
                rgb[destination++] = (byte) (swapRedBlue ? blue : red);
                rgb[destination++] = (byte) green;
                rgb[destination++] = (byte) (swapRedBlue ? red : blue);
            }
        }
        return rgb;
    }

    private static Bitmap align(Bitmap frame, List<NormalizedLandmark> landmarks) {
        if (landmarks.size() <= 473) return null;
        float[] source = {
                landmarks.get(468).x() * frame.getWidth(), landmarks.get(468).y() * frame.getHeight(),
                landmarks.get(473).x() * frame.getWidth(), landmarks.get(473).y() * frame.getHeight(),
                landmarks.get(1).x() * frame.getWidth(), landmarks.get(1).y() * frame.getHeight(),
                landmarks.get(61).x() * frame.getWidth(), landmarks.get(61).y() * frame.getHeight(),
                landmarks.get(291).x() * frame.getWidth(), landmarks.get(291).y() * frame.getHeight()
        };
        swapByX(source, 0, 2);
        swapByX(source, 6, 8);
        if (Math.hypot(source[0] - source[2], source[1] - source[3]) < 30d) return null;
        float noseRatio = projection(
                source[4], source[5], source[0], source[1], source[2], source[3]);
        if (noseRatio < 0.16f || noseRatio > 0.84f) return null;
        Matrix matrix = similarity(source, TEMPLATE);
        Bitmap output = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(frame, matrix,
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return output;
    }

    private static Matrix similarity(float[] source, float[] destination) {
        int count = source.length / 2;
        float sx = 0f, sy = 0f, dx = 0f, dy = 0f;
        for (int i = 0; i < source.length; i += 2) {
            sx += source[i]; sy += source[i + 1];
            dx += destination[i]; dy += destination[i + 1];
        }
        sx /= count; sy /= count; dx /= count; dy /= count;
        double denominator = 0d, cosine = 0d, sine = 0d;
        for (int i = 0; i < source.length; i += 2) {
            double x = source[i] - sx, y = source[i + 1] - sy;
            double u = destination[i] - dx, v = destination[i + 1] - dy;
            denominator += x * x + y * y;
            cosine += x * u + y * v;
            sine += x * v - y * u;
        }
        float a = (float) (cosine / denominator);
        float b = (float) (sine / denominator);
        Matrix matrix = new Matrix();
        matrix.setValues(new float[]{
                a, -b, dx - a * sx + b * sy,
                b, a, dy - b * sx - a * sy,
                0f, 0f, 1f
        });
        return matrix;
    }

    private static void swapByX(float[] points, int first, int second) {
        if (points[first] <= points[second]) return;
        float x = points[first], y = points[first + 1];
        points[first] = points[second]; points[first + 1] = points[second + 1];
        points[second] = x; points[second + 1] = y;
    }

    private static float projection(
            float x, float y, float x1, float y1, float x2, float y2
    ) {
        float dx = x2 - x1, dy = y2 - y1;
        return ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy);
    }

    private static void normalize(float[] values) {
        double sum = 0d;
        for (float value : values) sum += value * value;
        float norm = (float) Math.sqrt(sum);
        if (norm == 0f) throw new IllegalStateException("无效人脸特征");
        for (int index = 0; index < values.length; index++) values[index] /= norm;
    }

    private static float cosine(float[] first, float[] second) {
        if (first.length != second.length) return -1f;
        float score = 0f;
        for (int index = 0; index < first.length; index++) score += first[index] * second[index];
        return score;
    }

    private static byte[] readAsset(Context context, String name) throws Exception {
        try (InputStream input = context.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String message(Exception error, String fallback) {
        return error.getMessage() == null ? fallback : error.getMessage();
    }

    @Override
    public void close() {
        executor.shutdown();
        if (rknnSession != null) rknnSession.close();
        if (session == null) return;
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }

    private static final class FaceInput {
        final int trackId;
        final Bitmap bitmap;
        FaceInput(int trackId, Bitmap bitmap) { this.trackId = trackId; this.bitmap = bitmap; }
    }

    private static final class TrackEvidence {
        String adminId;
        int confirmations;
    }

    public static final class Match {
        public final String adminId;
        public final String name;
        public final float similarity;
        public final boolean isAdmin;

        Match(String adminId, String name, float similarity, boolean isAdmin) {
            this.adminId = adminId;
            this.name = name;
            this.similarity = similarity;
            this.isAdmin = isAdmin;
        }
    }

    public interface RecognitionListener {
        void onRecognition(Map<Integer, Match> matches, long latencyMs);
        void onRecognitionError(String message);
    }

    public interface EnrollmentListener {
        void onEnrolled(AdminFaceStore.Record record, int total);
        void onEnrollmentError(String message);
    }
}
