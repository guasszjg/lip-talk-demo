package com.zuicun.liptalkdemo;

/** Public switch interface for UI, Agent or other business modules. */
public interface DetectionModeController {
    void setDetectionMode(DetectionMode mode);
    DetectionMode getDetectionMode();
}
