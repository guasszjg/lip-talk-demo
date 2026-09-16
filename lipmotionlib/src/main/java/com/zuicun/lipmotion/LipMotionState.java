package com.zuicun.lipmotion;

/** Stable state produced by the geometry-based lip motion tracker. */
public enum LipMotionState {
    NO_FACE,
    SAMPLING,
    STILL,
    MOVING,
    /** Face is temporarily lost while the ASR motion gate remains open. */
    FACE_LOST_HOLD
}
