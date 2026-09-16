package com.zuicun.lipmotion;

/** Configuration shared by NV21 inference and the optional View overlay. */
public final class LipMotionConfig {
    public enum OverlayScaleType { CENTER_CROP, FIT_CENTER, STRETCH }

    final int maximumFaces;
    final float motionThreshold;
    final float rangeThreshold;
    final OverlayScaleType overlayScaleType;
    final int lipColor;
    final boolean debugLogging;
    final long faceLostGraceMs;

    private LipMotionConfig(Builder builder) {
        maximumFaces = builder.maximumFaces;
        motionThreshold = builder.motionThreshold;
        rangeThreshold = builder.rangeThreshold;
        overlayScaleType = builder.overlayScaleType;
        lipColor = builder.lipColor;
        debugLogging = builder.debugLogging;
        faceLostGraceMs = builder.faceLostGraceMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maximumFaces = 1;
        private float motionThreshold = 0.018f;
        private float rangeThreshold = 0.055f;
        private OverlayScaleType overlayScaleType = OverlayScaleType.CENTER_CROP;
        private int lipColor = 0xFFFACC15;
        private boolean debugLogging;
        private long faceLostGraceMs = 400L;

        public Builder setMaximumFaces(int value) {
            maximumFaces = value;
            return this;
        }

        public Builder setMotionThreshold(float value) {
            motionThreshold = value;
            return this;
        }

        public Builder setRangeThreshold(float value) {
            rangeThreshold = value;
            return this;
        }

        public Builder setOverlayScaleType(OverlayScaleType value) {
            overlayScaleType = value;
            return this;
        }

        public Builder setLipColor(int value) {
            lipColor = value;
            return this;
        }

        public Builder setDebugLogging(boolean value) {
            debugLogging = value;
            return this;
        }

        /** ASR gate hold time for a brief NO_FACE dropout; zero disables the hold. */
        public Builder setFaceLostGraceMs(long value) {
            faceLostGraceMs = value;
            return this;
        }

        public LipMotionConfig build() {
            if (maximumFaces < 1 || maximumFaces > 4) {
                throw new IllegalArgumentException("maximumFaces must be between 1 and 4");
            }
            if (motionThreshold <= 0f || rangeThreshold <= 0f) {
                throw new IllegalArgumentException("motion thresholds must be positive");
            }
            if (faceLostGraceMs < 0L || faceLostGraceMs > 2000L) {
                throw new IllegalArgumentException(
                        "faceLostGraceMs must be between 0 and 2000");
            }
            return new LipMotionConfig(this);
        }
    }
}
