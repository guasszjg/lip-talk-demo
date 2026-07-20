package com.zuicun.liptalkdemo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MouthMotionTrackerTest {
    @Test
    public void stableMouthDoesNotTriggerMotion() {
        MouthMotionTracker tracker = new MouthMotionTracker();
        MouthMotionTracker.Reading reading = tracker.update(0.08f);
        for (int i = 0; i < 15; i++) reading = tracker.update(0.08f);
        assertFalse(reading.isMoving);
    }

    @Test
    public void changingMouthTriggersMotion() {
        MouthMotionTracker tracker = new MouthMotionTracker();
        float[] samples = {0.06f, 0.08f, 0.16f, 0.09f, 0.23f, 0.07f, 0.18f, 0.06f};
        MouthMotionTracker.Reading reading = null;
        for (float sample : samples) reading = tracker.update(sample);
        assertTrue(reading != null && reading.isMoving);
    }
}
