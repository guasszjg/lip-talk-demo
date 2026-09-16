package com.zuicun.lipmotion;

/** A normalized point in the upright, display-oriented input image. */
public final class LipPoint {
    private final float x;
    private final float y;

    public LipPoint(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }
}
