package com.zuicun.liptalkdemo;

import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalLensFacing;
import androidx.camera.lifecycle.ProcessCameraProvider;

import android.view.Surface;

@OptIn(markerClass = ExperimentalLensFacing.class)
final class CompatibleCamera {
    private CompatibleCamera() {}

    static Selection select(ProcessCameraProvider provider) throws Exception {
        if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            return new Selection(CameraSelector.DEFAULT_FRONT_CAMERA, true);
        }
        if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            return new Selection(CameraSelector.DEFAULT_BACK_CAMERA, false);
        }
        CameraSelector external = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
                .build();
        if (provider.hasCamera(external)) {
            return new Selection(external, false);
        }
        throw new IllegalStateException("设备没有可用的 Camera2 摄像头");
    }

    static final class Selection {
        final CameraSelector selector;
        final boolean mirrored;
        final boolean compensateRockchipUsbRotation;

        Selection(CameraSelector selector, boolean mirrored) {
            this.selector = selector;
            this.mirrored = mirrored;
            this.compensateRockchipUsbRotation =
                    DevicePlatform.isRockchip() && !mirrored;
        }

        int targetRotation() {
            return compensateRockchipUsbRotation
                    ? Surface.ROTATION_90 : Surface.ROTATION_0;
        }
    }
}
