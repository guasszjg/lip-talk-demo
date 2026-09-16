package com.zuicun.liptalkdemo;

import android.os.Build;

final class DevicePlatform {
    private DevicePlatform() {}

    static boolean isRockchip() {
        String identity = (Build.HARDWARE + " " + Build.BOARD + " "
                + Build.MANUFACTURER + " " + Build.PRODUCT).toLowerCase();
        return identity.contains("rockchip") || identity.contains("rk35")
                || identity.contains("rk30");
    }
}
