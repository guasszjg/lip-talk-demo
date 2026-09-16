package com.zuicun.lipmotion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable result for one accepted NV21 frame. */
public final class LipMotionResult {
    private final long timestampMs;
    private final LipMotionState state;
    private final float openness;
    private final float movement;
    private final float range;
    private final float activityScore;
    private final int sampleCount;
    private final int minimumSamples;
    private final int imageWidth;
    private final int imageHeight;
    private final long inferenceLatencyMs;
    private final List<LipPoint> outerLip;
    private final List<LipPoint> innerLip;

    LipMotionResult(
            long timestampMs,
            LipMotionState state,
            float openness,
            float movement,
            float range,
            float activityScore,
            int sampleCount,
            int minimumSamples,
            int imageWidth,
            int imageHeight,
            long inferenceLatencyMs,
            List<LipPoint> outerLip,
            List<LipPoint> innerLip
    ) {
        this.timestampMs = timestampMs;
        this.state = state;
        this.openness = openness;
        this.movement = movement;
        this.range = range;
        this.activityScore = activityScore;
        this.sampleCount = sampleCount;
        this.minimumSamples = minimumSamples;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.inferenceLatencyMs = inferenceLatencyMs;
        this.outerLip = Collections.unmodifiableList(new ArrayList<>(outerLip));
        this.innerLip = Collections.unmodifiableList(new ArrayList<>(innerLip));
    }

    static LipMotionResult noFace(
            long timestampMs,
            int imageWidth,
            int imageHeight,
            long latencyMs
    ) {
        return new LipMotionResult(
                timestampMs,
                LipMotionState.NO_FACE,
                0f,
                0f,
                0f,
                0f,
                0,
                0,
                imageWidth,
                imageHeight,
                latencyMs,
                Collections.<LipPoint>emptyList(),
                Collections.<LipPoint>emptyList());
    }

    static LipMotionResult faceLostHold(
            long timestampMs,
            int imageWidth,
            int imageHeight,
            long latencyMs
    ) {
        return new LipMotionResult(
                timestampMs,
                LipMotionState.FACE_LOST_HOLD,
                0f,
                0f,
                0f,
                0f,
                0,
                0,
                imageWidth,
                imageHeight,
                latencyMs,
                Collections.<LipPoint>emptyList(),
                Collections.<LipPoint>emptyList());
    }

    public long getTimestampMs() { return timestampMs; }
    public LipMotionState getState() { return state; }
    public boolean isFaceDetected() {
        return state != LipMotionState.NO_FACE
                && state != LipMotionState.FACE_LOST_HOLD;
    }
    public boolean isMoving() {
        return state == LipMotionState.MOVING
                || state == LipMotionState.FACE_LOST_HOLD;
    }
    public float getOpenness() { return openness; }
    public float getMovement() { return movement; }
    public float getRange() { return range; }
    public float getActivityScore() { return activityScore; }
    public int getSampleCount() { return sampleCount; }
    public int getMinimumSamples() { return minimumSamples; }
    public int getImageWidth() { return imageWidth; }
    public int getImageHeight() { return imageHeight; }
    public long getInferenceLatencyMs() { return inferenceLatencyMs; }
    public List<LipPoint> getOuterLip() { return outerLip; }
    public List<LipPoint> getInnerLip() { return innerLip; }
}
