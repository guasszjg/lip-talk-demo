package com.zuicun.liptalkdemo;

public enum DetectionMode {
    SINGLE(1, "单人高性能"),
    MULTI(4, "多人检测");

    public final int maximumFaces;
    public final String displayName;

    DetectionMode(int maximumFaces, String displayName) {
        this.maximumFaces = maximumFaces;
        this.displayName = displayName;
    }
}
