package com.zuicun.liptalkdemo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LatencyTrackerTest {
    @Test
    public void keepsOnlyConfiguredWindow() {
        LatencyTracker tracker = new LatencyTracker(3);
        tracker.add(10);
        tracker.add(20);
        tracker.add(30);
        assertEquals(30, tracker.add(40));
    }

    @Test
    public void resetClearsOldSamples() {
        LatencyTracker tracker = new LatencyTracker(30);
        tracker.add(100);
        tracker.reset();
        assertEquals(20, tracker.add(20));
    }
}
