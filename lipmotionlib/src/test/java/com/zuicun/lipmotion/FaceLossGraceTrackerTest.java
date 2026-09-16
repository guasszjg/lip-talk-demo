package com.zuicun.lipmotion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FaceLossGraceTrackerTest {
    @Test
    public void activeMotionIsHeldOnlyWithinGracePeriod() {
        FaceLossGraceTracker tracker = new FaceLossGraceTracker(400L);

        assertTrue(tracker.shouldHold(1000L, true));
        assertTrue(tracker.shouldHold(1400L, true));
        assertFalse(tracker.shouldHold(1401L, true));
    }

    @Test
    public void inactiveMotionIsNeverHeld() {
        FaceLossGraceTracker tracker = new FaceLossGraceTracker(400L);
        assertFalse(tracker.shouldHold(1000L, false));
    }

    @Test
    public void detectedFaceStartsANewGraceWindow() {
        FaceLossGraceTracker tracker = new FaceLossGraceTracker(400L);
        assertTrue(tracker.shouldHold(1000L, true));
        tracker.onFaceDetected();
        assertTrue(tracker.shouldHold(2000L, true));
        assertTrue(tracker.shouldHold(2400L, true));
    }
}
