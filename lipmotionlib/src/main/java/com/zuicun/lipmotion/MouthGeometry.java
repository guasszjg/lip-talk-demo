package com.zuicun.lipmotion;

import java.util.ArrayList;
import java.util.List;

final class MouthGeometry {
    static final int[] OUTER_LIPS = {
            61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291,
            375, 321, 405, 314, 17, 84, 181, 91, 146
    };
    static final int[] INNER_LIPS = {
            78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308,
            324, 318, 402, 317, 14, 87, 178, 88, 95
    };

    private final MouthMotionTracker tracker;

    MouthGeometry(float motionThreshold, float rangeThreshold) {
        tracker = new MouthMotionTracker(motionThreshold, rangeThreshold);
    }

    Analysis analyze(List<FaceLandmark> landmarks, long timestampMs) {
        if (landmarks.size() <= 317) return null;
        float mouthHeight = distance(landmarks.get(13), landmarks.get(14)) * 0.50f
                + distance(landmarks.get(82), landmarks.get(87)) * 0.25f
                + distance(landmarks.get(312), landmarks.get(317)) * 0.25f;
        float mouthWidth = distance(landmarks.get(78), landmarks.get(308));
        if (mouthWidth < 0.001f) return null;

        MouthMotionTracker.Reading reading = tracker.update(
                mouthHeight / mouthWidth,
                timestampMs);
        return new Analysis(
                reading,
                points(landmarks, OUTER_LIPS),
                points(landmarks, INNER_LIPS));
    }

    void reset() {
        tracker.reset();
    }

    private static float distance(FaceLandmark a, FaceLandmark b) {
        return (float) Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static List<LipPoint> points(
            List<FaceLandmark> landmarks,
            int[] indexes
    ) {
        List<LipPoint> output = new ArrayList<>(indexes.length);
        for (int index : indexes) {
            FaceLandmark point = landmarks.get(index);
            output.add(new LipPoint(point.x, point.y));
        }
        return output;
    }

    static final class Analysis {
        final MouthMotionTracker.Reading reading;
        final List<LipPoint> outerLip;
        final List<LipPoint> innerLip;

        Analysis(
                MouthMotionTracker.Reading reading,
                List<LipPoint> outerLip,
                List<LipPoint> innerLip
        ) {
            this.reading = reading;
            this.outerLip = outerLip;
            this.innerLip = innerLip;
        }
    }
}
