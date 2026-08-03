package com.zuicun.liptalkdemo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Stabilizes low-frequency attribute estimates for one lightweight face track. */
public final class AgeGenderSmoother {
    public static final int WINDOW_SIZE = 7;
    private static final float MIN_SAMPLE_GENDER_CONFIDENCE = 0.58f;
    private static final float MIN_COMBINED_GENDER_EVIDENCE = 0.20f;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private int trackId = -1;

    public synchronized Result add(int newTrackId, int gender, int age, float confidence, long latencyMs) {
        if (newTrackId != trackId) {
            samples.clear();
            trackId = newTrackId;
        }
        samples.addLast(new Sample(gender, Math.max(0, Math.min(100, age)), confidence));
        while (samples.size() > WINDOW_SIZE) samples.removeFirst();

        List<Integer> ages = new ArrayList<>();
        float genderScore = 0f;
        for (Sample sample : samples) {
            ages.add(sample.age);
            if (sample.confidence >= MIN_SAMPLE_GENDER_CONFIDENCE) {
                float weight = Math.max(0f, sample.confidence * 2f - 1f);
                genderScore += sample.gender == 1 ? weight : -weight;
            }
        }
        Collections.sort(ages);
        int stableAge = robustAge(ages);
        float genderEvidence = Math.abs(genderScore) / samples.size();
        int stableGender = genderEvidence < MIN_COMBINED_GENDER_EVIDENCE
                ? -1
                : (genderScore > 0f ? 1 : 0);
        float stableConfidence = Math.min(1f, 0.5f + 0.5f * genderEvidence);
        return new Result(
                trackId,
                stableGender,
                stableAge,
                stableConfidence,
                samples.size(),
                latencyMs);
    }

    private static int robustAge(List<Integer> sortedAges) {
        if (sortedAges.size() < 5) return sortedAges.get(sortedAges.size() / 2);
        int sum = 0;
        for (int index = 1; index < sortedAges.size() - 1; index++) {
            sum += sortedAges.get(index);
        }
        return Math.round((float) sum / (sortedAges.size() - 2));
    }

    public synchronized void reset() {
        samples.clear();
        trackId = -1;
    }

    private static final class Sample {
        final int gender;
        final int age;
        final float confidence;

        Sample(int gender, int age, float confidence) {
            this.gender = gender;
            this.age = age;
            this.confidence = confidence;
        }
    }

    public static final class Result {
        public final int trackId;
        public final int gender;
        public final int age;
        public final float confidence;
        public final int sampleCount;
        public final long latencyMs;

        Result(int trackId, int gender, int age, float confidence, int sampleCount, long latencyMs) {
            this.trackId = trackId;
            this.gender = gender;
            this.age = age;
            this.confidence = confidence;
            this.sampleCount = sampleCount;
            this.latencyMs = latencyMs;
        }

        public String genderLabel() {
            if (confidence < 0.60f || gender < 0) return "不确定";
            return gender == 1 ? "男" : "女";
        }

        public int genderConfidencePercent() {
            return Math.round(confidence * 100f);
        }

        public String ageBand() {
            if (age < 18) return "<18 岁";
            if (age >= 70) return "70+ 岁";
            int lower = age / 10 * 10;
            return lower + "–" + (lower + 9) + " 岁";
        }
    }
}
