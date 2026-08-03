package com.zuicun.liptalkdemo;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;

public final class MouthAnalyzer {
    private final MouthMotionTracker tracker = new MouthMotionTracker();

    public Result analyze(List<NormalizedLandmark> landmarks) {
        if (landmarks.size() <= 308) return null;

        NormalizedLandmark upperLip = landmarks.get(13);
        NormalizedLandmark lowerLip = landmarks.get(14);
        NormalizedLandmark upperLeftLip = landmarks.get(82);
        NormalizedLandmark lowerLeftLip = landmarks.get(87);
        NormalizedLandmark upperRightLip = landmarks.get(312);
        NormalizedLandmark lowerRightLip = landmarks.get(317);
        NormalizedLandmark leftCorner = landmarks.get(78);
        NormalizedLandmark rightCorner = landmarks.get(308);
        float mouthHeight = distance(upperLip, lowerLip) * 0.50f
                + distance(upperLeftLip, lowerLeftLip) * 0.25f
                + distance(upperRightLip, lowerRightLip) * 0.25f;
        float mouthWidth = distance(leftCorner, rightCorner);
        if (mouthWidth < 0.001f) return null;

        MouthMotionTracker.Reading reading = tracker.update(mouthHeight / mouthWidth);
        return new Result(reading.openness, reading.movement, reading.isMoving);
    }

    public void reset() {
        tracker.reset();
    }

    private float distance(NormalizedLandmark a, NormalizedLandmark b) {
        return (float) Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    public static final class Result {
        public final float openness;
        public final float movement;
        public final boolean isMoving;

        public Result(float openness, float movement, boolean isMoving) {
            this.openness = openness;
            this.movement = movement;
            this.isMoving = isMoving;
        }
    }
}
