package com.zuicun.liptalkdemo;

import java.util.ArrayDeque;
import java.util.Deque;

/** Converts a normalized mouth-opening time series into a stable motion decision. */
public final class MouthMotionTracker {
    private final int windowSize;
    private final int minimumSamples;
    private final float motionThreshold;
    private final float rangeThreshold;
    private final Deque<Float> values = new ArrayDeque<>();
    private int positiveFrames;
    private int negativeFrames;
    private boolean moving;

    public MouthMotionTracker() {
        this(12, 6, 0.018f, 0.055f);
    }

    public MouthMotionTracker(
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

    public Reading update(float openness) {
        values.addLast(Math.max(0f, Math.min(2f, openness)));
        if (values.size() > windowSize) {
            values.removeFirst();
        }

        float sum = 0f;
        float minimum = Float.MAX_VALUE;
        float maximum = -Float.MAX_VALUE;
        for (float value : values) {
            sum += value;
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        float mean = sum / Math.max(1, values.size());
        float squaredDeltaSum = 0f;
        for (float value : values) {
            float delta = value - mean;
            squaredDeltaSum += delta * delta;
        }

        float movement = (float) Math.sqrt(squaredDeltaSum / Math.max(1, values.size()));
        float range = maximum - minimum;
        boolean rawMoving = values.size() >= minimumSamples
                && (movement >= motionThreshold || range >= rangeThreshold);

        if (rawMoving) {
            positiveFrames++;
            negativeFrames = 0;
        } else {
            negativeFrames++;
            positiveFrames = 0;
        }
        if (!moving && positiveFrames >= 2) moving = true;
        if (moving && negativeFrames >= 5) moving = false;

        return new Reading(openness, movement, range, moving);
    }

    public void reset() {
        values.clear();
        positiveFrames = 0;
        negativeFrames = 0;
        moving = false;
    }

    public static final class Reading {
        public final float openness;
        public final float movement;
        public final float range;
        public final boolean isMoving;

        public Reading(float openness, float movement, float range, boolean isMoving) {
            this.openness = openness;
            this.movement = movement;
            this.range = range;
            this.isMoving = isMoving;
        }
    }
}
