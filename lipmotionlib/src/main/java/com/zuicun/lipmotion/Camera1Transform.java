package com.zuicun.lipmotion;

import android.hardware.Camera;
import android.view.Surface;

/** Orientation helpers for the deprecated but still widely used Android Camera1 API. */
@SuppressWarnings("deprecation")
public final class Camera1Transform {
    private Camera1Transform() {}

    /** Value suitable for {@link Camera#setDisplayOrientation(int)}. */
    public static int previewDisplayOrientation(int cameraId, int displayRotation) {
        Camera.CameraInfo info = cameraInfo(cameraId);
        int deviceDegrees = displayDegrees(displayRotation);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            int result = (info.orientation + deviceDegrees) % 360;
            return (360 - result) % 360;
        }
        return (info.orientation - deviceDegrees + 360) % 360;
    }

    /**
     * Clockwise rotation to pass to {@link LipMotionDetector#submitNv21} so raw NV21 pixels become
     * upright before RKNN inference.
     */
    public static int frameRotationDegrees(int cameraId, int displayRotation) {
        Camera.CameraInfo info = cameraInfo(cameraId);
        int deviceDegrees = displayDegrees(displayRotation);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (info.orientation + deviceDegrees) % 360;
        }
        return (info.orientation - deviceDegrees + 360) % 360;
    }

    /** Normal Camera1 front previews are horizontally mirrored. */
    public static boolean isMirrored(int cameraId) {
        return cameraInfo(cameraId).facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
    }

    private static Camera.CameraInfo cameraInfo(int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        return info;
    }

    private static int displayDegrees(int displayRotation) {
        switch (displayRotation) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            case Surface.ROTATION_0:
            default: return 0;
        }
    }
}
