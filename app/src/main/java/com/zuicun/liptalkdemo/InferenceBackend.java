package com.zuicun.liptalkdemo;

public enum InferenceBackend {
    CPU("CPU"),
    GPU("GPU");

    public final String displayName;

    InferenceBackend(String displayName) {
        this.displayName = displayName;
    }
}
