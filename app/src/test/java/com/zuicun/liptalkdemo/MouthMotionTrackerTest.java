package com.zuicun.liptalkdemo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MouthMotionTrackerTest {
    @Test
    public void stableMouthDoesNotTriggerMotion() {
        MouthMotionTracker tracker = new MouthMotionTracker();
        MouthMotionTracker.Reading reading = tracker.update(0.08f);
        assertFalse(reading.isReady);
        assertEquals(1, reading.sampleCount);
        for (int i = 0; i < 15; i++) reading = tracker.update(0.08f);
        assertTrue(reading.isReady);
        assertFalse(reading.isMoving);
    }

    @Test
    public void changingMouthTriggersMotion() {
        MouthMotionTracker tracker = new MouthMotionTracker();
        float[] samples = {
                0.06f, 0.08f, 0.16f, 0.09f, 0.23f,
                0.07f, 0.18f, 0.06f, 0.17f, 0.07f
        };
        MouthMotionTracker.Reading reading = null;
        for (float sample : samples) reading = tracker.update(sample);
        assertTrue(reading != null && reading.isMoving);
    }

    @Test
    public void heldOpenMouthSettlesBackToStill() {
        MouthMotionTracker tracker = new MouthMotionTracker();
        float[] opening = {
                0.07f, 0.08f, 0.10f, 0.20f, 0.31f,
                0.34f, 0.35f, 0.32f, 0.35f, 0.34f
        };
        MouthMotionTracker.Reading reading = null;
        for (float sample : opening) reading = tracker.update(sample);
        assertTrue(reading != null && reading.isMoving);

        for (int i = 0; i < 24; i++) reading = tracker.update(0.35f);
        assertFalse(reading.isMoving);
    }

    @Test
    public void openMouthLandmarkJitterDoesNotTriggerMotion() {
        MouthMotionTracker tracker = new MouthMotionTracker();
        float[] samples = {
                0.350f, 0.356f, 0.346f, 0.353f, 0.348f, 0.355f,
                0.347f, 0.352f, 0.349f, 0.354f, 0.348f, 0.351f
        };
        MouthMotionTracker.Reading reading = null;
        for (float sample : samples) reading = tracker.update(sample);
        assertTrue(reading != null && reading.isReady);
        assertFalse(reading.isMoving);
    }

    @Test
    public void shortPauseDoesNotToggleMovingState() {
        MouthMotionTracker tracker = new MouthMotionTracker();
        long timestampMs = 0L;
        float[] speaking = {
                0.06f, 0.09f, 0.18f, 0.07f, 0.21f,
                0.08f, 0.19f, 0.07f, 0.18f, 0.08f
        };
        MouthMotionTracker.Reading reading = null;
        for (float sample : speaking) {
            timestampMs += 33L;
            reading = tracker.update(sample, timestampMs);
        }
        assertTrue(reading != null && reading.isMoving);

        for (int i = 0; i < 9; i++) {
            timestampMs += 33L;
            reading = tracker.update(0.08f, timestampMs);
        }
        assertTrue(reading.isMoving);
    }

    @Test
    public void sustainedPauseEventuallyBecomesStill() {
        MouthMotionTracker tracker = new MouthMotionTracker();
        long timestampMs = 0L;
        float[] speaking = {
                0.06f, 0.09f, 0.18f, 0.07f, 0.21f,
                0.08f, 0.19f, 0.07f, 0.18f, 0.08f
        };
        MouthMotionTracker.Reading reading = null;
        for (float sample : speaking) {
            timestampMs += 33L;
            reading = tracker.update(sample, timestampMs);
        }
        assertTrue(reading != null && reading.isMoving);

        for (int i = 0; i < 24; i++) {
            timestampMs += 33L;
            reading = tracker.update(0.08f, timestampMs);
        }
        assertFalse(reading.isMoving);
    }

    @Test
    public void timingIsStableAtLowerFrameRate() {
        MouthMotionTracker tracker = new MouthMotionTracker();
        long timestampMs = 0L;
        float[] speaking = {
                0.06f, 0.16f, 0.07f, 0.20f,
                0.08f, 0.19f, 0.07f, 0.18f
        };
        MouthMotionTracker.Reading reading = null;
        for (float sample : speaking) {
            timestampMs += 66L;
            reading = tracker.update(sample, timestampMs);
        }
        assertTrue(reading != null && reading.isMoving);

        for (int i = 0; i < 5; i++) {
            timestampMs += 66L;
            reading = tracker.update(0.08f, timestampMs);
        }
        assertTrue(reading.isMoving);

        for (int i = 0; i < 8; i++) {
            timestampMs += 66L;
            reading = tracker.update(0.08f, timestampMs);
        }
        assertFalse(reading.isMoving);
    }
}
