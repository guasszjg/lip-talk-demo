package com.zuicun.lipmotion;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Camera1-friendly lip motion detector.
 *
 * <p>{@link #submitNv21(byte[], int, int, int, boolean, long)} copies the supplied Camera1
 * callback buffer before it returns. The host may therefore immediately return that buffer via
 * {@code Camera.addCallbackBuffer(data)}. Inference runs on one private worker and drops stale
 * pending frames.</p>
 */
public final class LipMotionDetector implements AutoCloseable {
    private static final String TAG = "LipMotionRKNN";
    private static final long DIAGNOSTIC_INTERVAL_MS = 2000L;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "lip-motion-detector");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private final Handler callbackHandler = new Handler(Looper.getMainLooper());
    private final AtomicReference<Frame> latestFrame = new AtomicReference<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LipMotionListener listener;
    private final LipViewOverlayRenderer viewOverlayRenderer;
    private final Nv21FrameConverter frameConverter = new Nv21FrameConverter();
    private final MouthGeometry mouthGeometry;
    private final FaceLossGraceTracker faceLossGraceTracker;
    private final RknnFacePipeline rknnPipeline;
    private final boolean debugLogging;
    private long lastDiagnosticLogMs = Long.MIN_VALUE;
    private boolean motionGateActive;
    private float selectedCenterX = Float.NaN;
    private float selectedCenterY = Float.NaN;

    public LipMotionDetector(
            Context context,
            LipMotionConfig config,
            LipMotionListener listener
    ) {
        this.listener = listener;
        debugLogging = config.debugLogging;
        viewOverlayRenderer = new LipViewOverlayRenderer(config);
        mouthGeometry = new MouthGeometry(config.motionThreshold, config.rangeThreshold);
        faceLossGraceTracker = new FaceLossGraceTracker(config.faceLostGraceMs);
        final Context applicationContext = context.getApplicationContext();
        final int maximumFaces = config.maximumFaces;
        Future<RknnFacePipeline> initialization = worker.submit(
                new Callable<RknnFacePipeline>() {
                    @Override
                    public RknnFacePipeline call() {
                        return RknnFacePipeline.tryCreate(applicationContext, maximumFaces);
                    }
                });
        try {
            rknnPipeline = initialization.get();
            if (rknnPipeline == null) {
                throw new IllegalStateException(
                        "Unable to initialize " + RknnPlatform.displayName()
                                + " RKNN face pipeline");
            }
            Log.i(TAG, RknnPlatform.displayName() + " RKNN face pipeline initialized");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
            throw new IllegalStateException("Lip detector initialization interrupted", error);
        } catch (ExecutionException error) {
            worker.shutdownNow();
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalStateException(
                    "Unable to initialize " + RknnPlatform.displayName()
                            + " RKNN face pipeline", cause);
        }
    }

    /**
     * Reuses an existing normal View (for example xxFaceView) by drawing lips through ViewOverlay.
     * The host View does not need to extend a library class or change its onDraw implementation.
     */
    public void setOverlayView(final View overlayView) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            viewOverlayRenderer.setView(overlayView);
        } else {
            callbackHandler.post(new Runnable() {
                @Override
                public void run() {
                    viewOverlayRenderer.setView(overlayView);
                }
            });
        }
    }

    /** Uses {@link SystemClock#uptimeMillis()} as the frame timestamp. */
    public boolean submitNv21(
            byte[] nv21,
            int width,
            int height,
            int rotationDegrees,
            boolean mirrored
    ) {
        return submitNv21(
                nv21,
                width,
                height,
                rotationDegrees,
                mirrored,
                SystemClock.uptimeMillis());
    }

    /**
     * Submits a Camera1 NV21 frame and returns immediately after copying it.
     *
     * @param rotationDegrees clockwise rotation needed to make the NV21 image upright; one of
     *                        0, 90, 180 or 270
     * @param mirrored true when the displayed preview is horizontally mirrored, normally for a
     *                 front-facing Camera1 preview
     * @param timestampMs monotonic timestamp, normally {@link SystemClock#uptimeMillis()}
     * @return false only after the detector has been closed
     */
    public boolean submitNv21(
            byte[] nv21,
            int width,
            int height,
            int rotationDegrees,
            boolean mirrored,
            long timestampMs
    ) {
        validateFrame(nv21, width, height, rotationDegrees);
        if (closed.get()) return false;
        int expectedSize = width * height * 3 / 2;
        latestFrame.set(new Frame(
                Arrays.copyOf(nv21, expectedSize),
                width,
                height,
                rotationDegrees,
                mirrored,
                timestampMs));
        scheduleDrain();
        return true;
    }

    /** Clears temporal state and removes any lip drawing. */
    public void reset() {
        latestFrame.set(null);
        callbackHandler.post(new Runnable() {
            @Override
            public void run() {
                viewOverlayRenderer.clear();
            }
        });
        if (!closed.get()) {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    mouthGeometry.reset();
                    faceLossGraceTracker.reset();
                    motionGateActive = false;
                    selectedCenterX = Float.NaN;
                    selectedCenterY = Float.NaN;
                }
            });
        }
    }

    private void scheduleDrain() {
        if (draining.compareAndSet(false, true)) {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    drainFrames();
                }
            });
        }
    }

    private void drainFrames() {
        try {
            while (!closed.get()) {
                Frame frame = latestFrame.getAndSet(null);
                if (frame == null) break;
                try {
                    process(frame);
                } catch (Throwable error) {
                    mouthGeometry.reset();
                    faceLossGraceTracker.reset();
                    motionGateActive = false;
                    dispatchError(error);
                }
            }
        } finally {
            draining.set(false);
            if (!closed.get() && latestFrame.get() != null) scheduleDrain();
        }
    }

    private void process(Frame frame) {
        long startMs = SystemClock.uptimeMillis();
        Bitmap bitmap = frameConverter.convert(
                frame.data,
                frame.width,
                frame.height,
                frame.rotationDegrees,
                frame.mirrored);
        try {
            List<List<FaceLandmark>> faces = rknnPipeline.detect(bitmap);
            long latencyMs = SystemClock.uptimeMillis() - startMs;
            logDiagnostics(frame, bitmap, faces.size(), latencyMs);
            List<FaceLandmark> selected = largestFace(faces);
            if (selected == null) {
                deliverFaceLostResult(frame, bitmap, latencyMs);
                return;
            }

            faceLossGraceTracker.onFaceDetected();
            float[] center = faceCenter(selected);
            if (!Float.isNaN(selectedCenterX)
                    && Math.hypot(center[0] - selectedCenterX, center[1] - selectedCenterY) > 0.25) {
                mouthGeometry.reset();
                faceLossGraceTracker.reset();
                motionGateActive = false;
            }
            selectedCenterX = center[0];
            selectedCenterY = center[1];

            MouthGeometry.Analysis mouth = mouthGeometry.analyze(selected, frame.timestampMs);
            if (mouth == null) {
                deliverFaceLostResult(frame, bitmap, latencyMs);
                return;
            }
            MouthMotionTracker.Reading reading = mouth.reading;
            motionGateActive = reading.moving;
            LipMotionState state = !reading.ready
                    ? LipMotionState.SAMPLING
                    : (reading.moving ? LipMotionState.MOVING : LipMotionState.STILL);
            deliver(new LipMotionResult(
                    frame.timestampMs,
                    state,
                    reading.openness,
                    reading.movement,
                    reading.range,
                    reading.activityScore,
                    reading.sampleCount,
                    reading.minimumSamples,
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    latencyMs,
                    mouth.outerLip,
                    mouth.innerLip));
        } finally {
            bitmap.recycle();
        }
    }

    private void deliverFaceLostResult(Frame frame, Bitmap bitmap, long latencyMs) {
        boolean hold = faceLossGraceTracker.shouldHold(
                frame.timestampMs, motionGateActive);
        if (hold) {
            deliver(LipMotionResult.faceLostHold(
                    frame.timestampMs,
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    latencyMs));
            return;
        }
        mouthGeometry.reset();
        faceLossGraceTracker.reset();
        motionGateActive = false;
        selectedCenterX = Float.NaN;
        selectedCenterY = Float.NaN;
        deliver(LipMotionResult.noFace(
                frame.timestampMs,
                bitmap.getWidth(),
                bitmap.getHeight(),
                latencyMs));
    }

    private void deliver(final LipMotionResult result) {
        callbackHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!closed.get()) {
                    viewOverlayRenderer.render(result);
                    listener.onLipMotionResult(result);
                }
            }
        });
    }

    private void dispatchError(final Throwable error) {
        Log.e(TAG, "Lip motion processing failed", error);
        callbackHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!closed.get()) {
                    viewOverlayRenderer.clear();
                    listener.onLipMotionError(error);
                }
            }
        });
    }

    private void logDiagnostics(
            Frame frame,
            Bitmap transformed,
            int faceCount,
            long latencyMs
    ) {
        if (!debugLogging) return;
        long now = SystemClock.uptimeMillis();
        if (lastDiagnosticLogMs != Long.MIN_VALUE
                && now - lastDiagnosticLogMs < DIAGNOSTIC_INTERVAL_MS) return;
        lastDiagnosticLogMs = now;
        Log.i(TAG, "NV21=" + frame.width + "x" + frame.height
                + ", bytes=" + frame.data.length
                + ", rotation=" + frame.rotationDegrees
                + ", mirrored=" + frame.mirrored
                + ", transformed=" + transformed.getWidth() + "x" + transformed.getHeight()
                + ", faces=" + faceCount
                + ", " + rknnPipeline.diagnostics()
                + ", latencyMs=" + latencyMs);
    }

    private static List<FaceLandmark> largestFace(
            List<List<FaceLandmark>> faces
    ) {
        List<FaceLandmark> best = null;
        float bestArea = -1f;
        for (List<FaceLandmark> face : faces) {
            float[] bounds = bounds(face);
            float area = (bounds[2] - bounds[0]) * (bounds[3] - bounds[1]);
            if (area > bestArea) {
                bestArea = area;
                best = face;
            }
        }
        return best;
    }

    private static float[] faceCenter(List<FaceLandmark> face) {
        float[] bounds = bounds(face);
        return new float[]{(bounds[0] + bounds[2]) / 2f, (bounds[1] + bounds[3]) / 2f};
    }

    private static float[] bounds(List<FaceLandmark> face) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (FaceLandmark point : face) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    private static void validateFrame(
            byte[] nv21,
            int width,
            int height,
            int rotationDegrees
    ) {
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("NV21 width and height must be positive even values");
        }
        if (rotationDegrees != 0
                && rotationDegrees != 90
                && rotationDegrees != 180
                && rotationDegrees != 270) {
            throw new IllegalArgumentException("rotationDegrees must be 0, 90, 180 or 270");
        }
        long expected = (long) width * height * 3L / 2L;
        if (nv21.length < expected) {
            throw new IllegalArgumentException(
                    "NV21 buffer too small: expected at least " + expected
                            + " bytes but was " + nv21.length);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        latestFrame.set(null);
        callbackHandler.post(new Runnable() {
            @Override
            public void run() {
                viewOverlayRenderer.setView(null);
            }
        });
        Future<?> closeFuture = worker.submit(new Runnable() {
            @Override
            public void run() {
                rknnPipeline.close();
            }
        });
        try {
            closeFuture.get(3L, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            closeFuture.cancel(true);
        } finally {
            worker.shutdownNow();
        }
    }

    private static final class Frame {
        final byte[] data;
        final int width;
        final int height;
        final int rotationDegrees;
        final boolean mirrored;
        final long timestampMs;

        Frame(
                byte[] data,
                int width,
                int height,
                int rotationDegrees,
                boolean mirrored,
                long timestampMs
        ) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.rotationDegrees = rotationDegrees;
            this.mirrored = mirrored;
            this.timestampMs = timestampMs;
        }
    }
}
