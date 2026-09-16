package com.zuicun.lipmotion;

import android.os.Build;

import java.lang.reflect.Method;
import java.util.Locale;

/** Selects the RKNN model compiled for the current Rockchip NPU generation. */
final class RknnPlatform {
    private static final String PLATFORM = detectPlatform();

    private RknnPlatform() {}

    static String model(String stem) {
        return stem + "_" + PLATFORM + "_fp16.rknn";
    }

    static String displayName() {
        return PLATFORM.toUpperCase(Locale.US);
    }

    private static String detectPlatform() {
        String boardPlatform = systemProperty("ro.board.platform");
        String identity = (boardPlatform + " " + Build.HARDWARE + " " + Build.BOARD)
                .toLowerCase(Locale.US);
        if (identity.contains("rk3588")) return "rk3588";
        return "rk3576";
    }

    private static String systemProperty(String key) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Method get = type.getMethod("get", String.class);
            Object value = get.invoke(null, key);
            return value == null ? "" : value.toString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
