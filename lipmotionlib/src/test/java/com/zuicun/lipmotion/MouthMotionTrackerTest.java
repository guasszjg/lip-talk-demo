package com.zuicun.lipmotion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MouthMotionTrackerTest {
    @Test
    public void steadyLandmarkJitterRemainsStill() {
        MouthMotionTracker tracker = new MouthMotionTracker(0.018f, 0.055f);
        long timeMs = 0L;
        MouthMotionTracker.Reading reading = null;
        float[] samples = {
                0.350f, 0.356f, 0.346f, 0.353f, 0.348f, 0.355f,
                0.347f, 0.352f, 0.349f, 0.354f, 0.348f, 0.351f
        };
        for (float sample : samples) reading = tracker.update(sample, timeMs += 33L);
        assertTrue(reading != null && reading.ready);
        assertFalse(reading.moving);
    }

    @Test
    public void speechLikeMotionSurvivesShortPauseThenStops() {
        MouthMotionTracker tracker = new MouthMotionTracker(0.018f, 0.055f);
        long timeMs = 0L;
        MouthMotionTracker.Reading reading = null;
        float[] speaking = {
                0.06f, 0.09f, 0.18f, 0.07f, 0.21f,
                0.08f, 0.19f, 0.07f, 0.18f, 0.08f
        };
        for (float sample : speaking) reading = tracker.update(sample, timeMs += 33L);
        assertTrue(reading != null && reading.moving);

        for (int index = 0; index < 9; index++) {
            reading = tracker.update(0.08f, timeMs += 33L);
        }
        assertTrue(reading.moving);

        for (int index = 0; index < 20; index++) {
            reading = tracker.update(0.08f, timeMs += 33L);
        }
        assertFalse(reading.moving);
    }

    @Test
    public void lowerFrameRateUsesSameTimeHysteresis() {
        MouthMotionTracker tracker = new MouthMotionTracker(0.018f, 0.055f);
        long timeMs = 0L;
        MouthMotionTracker.Reading reading = null;
        float[] speaking = {0.06f, 0.16f, 0.07f, 0.20f, 0.08f, 0.19f, 0.07f, 0.18f};
        for (float sample : speaking) reading = tracker.update(sample, timeMs += 66L);
        assertTrue(reading != null && reading.moving);

        for (int index = 0; index < 5; index++) {
            reading = tracker.update(0.08f, timeMs += 66L);
        }
        assertTrue(reading.moving);

        for (int index = 0; index < 8; index++) {
            reading = tracker.update(0.08f, timeMs += 66L);
        }
        assertFalse(reading.moving);
    }
}
