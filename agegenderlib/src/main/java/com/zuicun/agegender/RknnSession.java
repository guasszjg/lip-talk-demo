package com.zuicun.agegender;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Small JNI wrapper around Rockchip RKNN Runtime with safe failure on non-Rockchip devices. */
final class RknnSession implements AutoCloseable {
    private static final String TAG = "AgeGenderRKNN";
    private static final boolean LIBRARY_LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("agegender_rknn");
            loaded = true;
        } catch (Throwable error) {
            Log.e(TAG, "RKNN native runtime unavailable", error);
            loaded = false;
        }
        LIBRARY_LOADED = loaded;
    }

    private long handle;

    static RknnSession tryCreate(Context context, String assetName, int... expectedOutputSizes) {
        if (!LIBRARY_LOADED) return null;
        try {
            byte[] model = readAsset(context, assetName);
            long handle = nativeCreate(model, expectedOutputSizes);
            if (handle != 0L) Log.i(TAG, "RKNN NPU active: " + assetName);
            return handle == 0L ? null : new RknnSession(handle);
        } catch (Throwable error) {
            Log.e(TAG, "RKNN model unavailable: " + assetName, error);
            return null;
        }
    }

    private RknnSession(long handle) {
        this.handle = handle;
    }

    synchronized float[] run(byte[] rgb) {
        float[][] outputs = runAll(rgb);
        return outputs.length == 0 ? null : outputs[0];
    }

    synchronized float[][] runAll(byte[] rgb) {
        if (handle == 0L) throw new IllegalStateException("RKNN session is closed");
        float[][] outputs = nativeRun(handle, rgb);
        if (outputs == null) throw new IllegalStateException("RKNN inference failed");
        return outputs;
    }

    @Override
    public synchronized void close() {
        if (handle == 0L) return;
        nativeDestroy(handle);
        handle = 0L;
    }

    private static byte[] readAsset(Context context, String name) throws Exception {
        try (InputStream input = context.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static native long nativeCreate(byte[] model, int[] expectedOutputSizes);
    private static native float[][] nativeRun(long handle, byte[] rgb);
    private static native void nativeDestroy(long handle);
}
