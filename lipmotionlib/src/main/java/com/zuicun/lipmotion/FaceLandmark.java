package com.zuicun.lipmotion;

/** Internal normalized 3D face landmark produced by the RKNN pipeline. */
final class FaceLandmark {
    final float x;
    final float y;
    final float z;

    FaceLandmark(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
