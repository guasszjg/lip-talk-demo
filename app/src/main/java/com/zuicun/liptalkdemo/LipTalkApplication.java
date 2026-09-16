package com.zuicun.liptalkdemo;

import android.app.Application;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.camera2.Camera2Config;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraXConfig;
import androidx.camera.core.ExperimentalLensFacing;

/**
 * Applies a CameraX limiter on Rockchip boards whose Android feature declarations
 * do not match the cameras actually exposed by Camera2.
 */
@OptIn(markerClass = ExperimentalLensFacing.class)
public final class LipTalkApplication extends Application
        implements CameraXConfig.Provider {
    @NonNull
    @Override
    public CameraXConfig getCameraXConfig() {
        CameraXConfig defaultConfig = Camera2Config.defaultConfig();
        if (!DevicePlatform.isRockchip()) return defaultConfig;

        Integer availableFacing = findPreferredLensFacing();
        if (availableFacing == null) return defaultConfig;
        CameraSelector limiter = new CameraSelector.Builder()
                .requireLensFacing(availableFacing)
                .build();
        return CameraXConfig.Builder.fromConfig(defaultConfig)
                .setAvailableCamerasLimiter(limiter)
                .build();
    }

    private Integer findPreferredLensFacing() {
        try {
            CameraManager manager =
                    (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            Integer fallback = null;
            for (String cameraId : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(cameraId)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return CameraSelector.LENS_FACING_FRONT;
                }
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    fallback = CameraSelector.LENS_FACING_BACK;
                } else if (fallback == null
                        && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    fallback = CameraSelector.LENS_FACING_EXTERNAL;
                }
            }
            return fallback;
        } catch (Exception ignored) {
            return null;
        }
    }
}
