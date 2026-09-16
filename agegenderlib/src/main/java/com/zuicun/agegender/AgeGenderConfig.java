package com.zuicun.agegender;

public final class AgeGenderConfig {
    final int rotationDegrees;
    final boolean mirrored;
    final long intervalMs;
    final boolean debugLogging;
    private AgeGenderConfig(Builder b) {
        rotationDegrees=b.rotationDegrees; mirrored=b.mirrored;
        intervalMs=b.intervalMs; debugLogging=b.debugLogging;
    }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private int rotationDegrees;
        private boolean mirrored;
        private long intervalMs=700L;
        private boolean debugLogging;
        public Builder setRotationDegrees(int value) {
            if (value!=0 && value!=90 && value!=180 && value!=270)
                throw new IllegalArgumentException("rotationDegrees must be 0/90/180/270");
            rotationDegrees=value; return this;
        }
        public Builder setMirrored(boolean value) { mirrored=value; return this; }
        public Builder setIntervalMs(long value) {
            if (value<200L) throw new IllegalArgumentException("intervalMs must be >= 200");
            intervalMs=value; return this;
        }
        public Builder setDebugLogging(boolean value) { debugLogging=value; return this; }
        public AgeGenderConfig build() { return new AgeGenderConfig(this); }
    }
}
