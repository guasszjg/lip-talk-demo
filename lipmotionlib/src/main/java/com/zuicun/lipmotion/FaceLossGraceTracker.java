package com.zuicun.lipmotion;

/** Keeps an active motion gate open across a short face-detection dropout. */
final class FaceLossGraceTracker {
    private final long graceMs;
    private long lostSinceMs = -1L;

    FaceLossGraceTracker(long graceMs) {
        this.graceMs = graceMs;
    }

    boolean shouldHold(long timestampMs, boolean motionWasActive) {
        if (!motionWasActive || graceMs <= 0L) return false;
        if (lostSinceMs < 0L) lostSinceMs = timestampMs;
        return timestampMs - lostSinceMs <= graceMs;
    }

    void onFaceDetected() {
        lostSinceMs = -1L;
    }

    void reset() {
        lostSinceMs = -1L;
    }
}
