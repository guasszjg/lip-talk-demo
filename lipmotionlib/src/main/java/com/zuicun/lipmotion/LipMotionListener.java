package com.zuicun.lipmotion;

/** Results and errors are delivered on the Android main thread. */
public interface LipMotionListener {
    void onLipMotionResult(LipMotionResult result);

    void onLipMotionError(Throwable error);
}
