package com.zuicun.liptalkdemo;

import java.util.ArrayDeque;
import java.util.Deque;

/** Rolling latency average used for quick CPU/GPU comparison on device. */
public final class LatencyTracker {
    private final int windowSize;
    private final Deque<Long> samples = new ArrayDeque<>();
    private long total;

    public LatencyTracker(int windowSize) {
        this.windowSize = windowSize;
    }

    public synchronized long add(long latencyMs) {
        samples.addLast(latencyMs);
        total += latencyMs;
        if (samples.size() > windowSize) total -= samples.removeFirst();
        return Math.round(total / (double) samples.size());
    }

    public synchronized void reset() {
        samples.clear();
        total = 0L;
    }
}
