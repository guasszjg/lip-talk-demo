package com.zuicun.lipmotion;

import java.util.ArrayDeque;
import java.util.Deque;

/** Package-private state machine shared by the detector and unit tests. */
final class MouthMotionTracker {
    private static final int RECENT_SAMPLE_COUNT = 5;
    private static final float SMOOTHING_ALPHA = 0.55f;
    private static final long DEFAULT_FRAME_INTERVAL_MS = 33L;
    private static final long MAX_FRAME_INTERVAL_MS = 200L;
    private static final long ENTER_CONFIRM_MS = 120L;
    private static final long EXIT_CONFIRM_MS = 500L;
    private static final float HOLD_GATE_RATIO = 0.68f;

    private final int windowSize;
    private final int minimumSamples;
    private final float motionThreshold;
    private final float rangeThreshold;
    private final Deque<Float> values = new ArrayDeque<>();
    private long enterEvidenceMs;
    private long quietEvidenceMs;
    private long lastTimestampMs = -1L;
    private boolean moving;

    MouthMotionTracker(float motionThreshold, float rangeThreshold) {
        this(12, 6, motionThreshold, rangeThreshold);
    }

    MouthMotionTracker(
            int windowSize,
            int minimumSamples,
            float motionThreshold,
            float rangeThreshold
    ) {
        this.windowSize = windowSize;
        this.minimumSamples = minimumSamples;
        this.motionThreshold = motionThreshold;
        this.rangeThreshold = rangeThreshold;
    }

    Reading update(float openness, long timestampMs) {
        long frameIntervalMs = frameInterval(timestampMs);
        float clamped = Math.max(0f, Math.min(2f, openness));
        float smoothingAlpha = timeAdjustedSmoothingAlpha(frameIntervalMs);
        float filtered = values.isEmpty()
                ? clamped
                : values.peekLast() * (1f - smoothingAlpha) + clamped * smoothingAlpha;
        values.addLast(filtered);
        if (values.size() > windowSize) values.removeFirst();

        int skip = Math.max(0, values.size() - RECENT_SAMPLE_COUNT);
        int index = 0;
        int recentCount = 0;
        int deltaCount = 0;
        float previous = 0f;
        float absoluteDeltaSum = 0f;
        float recentMinimum = Float.MAX_VALUE;
        float recentMaximum = -Float.MAX_VALUE;
        for (float value : values) {
            if (index++ < skip) continue;
            recentMinimum = Math.min(recentMinimum, value);
            recentMaximum = Math.max(recentMaximum, value);
            if (recentCount > 0) {
                absoluteDeltaSum += Math.abs(value - previous);
                deltaCount++;
            }
            previous = value;
            recentCount++;
        }

        float movement = absoluteDeltaSum / Math.max(1, deltaCount);
        float range = recentMaximum - recentMinimum;
        boolean ready = values.size() >= minimumSamples;
        float velocityGate = motionThreshold * 0.70f;
        float rangeGate = rangeThreshold * 0.45f;
        boolean startCandidate = ready
                && movement >= velocityGate
                && range >= rangeGate;
        boolean holdCandidate = ready
                && movement >= velocityGate * HOLD_GATE_RATIO
                && range >= rangeGate * HOLD_GATE_RATIO;
        float activityScore = Math.min(1f, Math.min(
                movement / velocityGate,
                range / rangeGate));

        if (!moving) {
            quietEvidenceMs = 0L;
            enterEvidenceMs = startCandidate
                    ? Math.min(ENTER_CONFIRM_MS, enterEvidenceMs + frameIntervalMs)
                    : Math.max(0L, enterEvidenceMs - frameIntervalMs);
            if (enterEvidenceMs >= ENTER_CONFIRM_MS) moving = true;
        } else {
            enterEvidenceMs = ENTER_CONFIRM_MS;
            quietEvidenceMs = holdCandidate
                    ? Math.max(0L, quietEvidenceMs - frameIntervalMs * 2L)
                    : Math.min(EXIT_CONFIRM_MS, quietEvidenceMs + frameIntervalMs);
            if (quietEvidenceMs >= EXIT_CONFIRM_MS) {
                moving = false;
                enterEvidenceMs = 0L;
            }
        }

        return new Reading(
                openness,
                movement,
                range,
                moving,
                ready,
                values.size(),
                minimumSamples,
                activityScore);
    }

    void reset() {
        values.clear();
        enterEvidenceMs = 0L;
        quietEvidenceMs = 0L;
        lastTimestampMs = -1L;
        moving = false;
    }

    private long frameInterval(long timestampMs) {
        long interval = lastTimestampMs < 0L
                ? DEFAULT_FRAME_INTERVAL_MS
                : timestampMs - lastTimestampMs;
        lastTimestampMs = timestampMs;
        if (interval <= 0L) return DEFAULT_FRAME_INTERVAL_MS;
        return Math.min(MAX_FRAME_INTERVAL_MS, interval);
    }

    private static float timeAdjustedSmoothingAlpha(long frameIntervalMs) {
        double frameRatio = frameIntervalMs / (double) DEFAULT_FRAME_INTERVAL_MS;
        return (float) (1.0 - Math.pow(1.0 - SMOOTHING_ALPHA, frameRatio));
    }

    static final class Reading {
        final float openness;
        final float movement;
        final float range;
        final boolean moving;
        final boolean ready;
        final int sampleCount;
        final int minimumSamples;
        final float activityScore;

        Reading(
                float openness,
                float movement,
                float range,
                boolean moving,
                boolean ready,
                int sampleCount,
                int minimumSamples,
                float activityScore
        ) {
            this.openness = openness;
            this.movement = movement;
            this.range = range;
            this.moving = moving;
            this.ready = ready;
            this.sampleCount = sampleCount;
            this.minimumSamples = minimumSamples;
            this.activityScore = activityScore;
        }
    }
}
