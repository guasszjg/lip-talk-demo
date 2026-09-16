package com.zuicun.agegender;

import android.graphics.Bitmap;
import android.graphics.Matrix;

final class Nv21FrameConverter {
    Bitmap convert(byte[] nv21, int width, int height, int rotationDegrees, boolean mirrored) {
        int[] pixels = new int[width * height];
        nv21ToArgb(nv21, width, height, pixels);
        Bitmap source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        source.setPixels(pixels, 0, width, 0, 0, width, height);
        if (rotationDegrees == 0 && !mirrored) return source;

        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        if (mirrored) matrix.postScale(-1f, 1f);
        Bitmap transformed = Bitmap.createBitmap(
                source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        if (transformed != source) source.recycle();
        return transformed;
    }

    private static void nv21ToArgb(byte[] input, int width, int height, int[] output) {
        int frameSize = width * height;
        for (int row = 0; row < height; row++) {
            int yRow = row * width;
            int uvRow = frameSize + (row >> 1) * width;
            for (int column = 0; column < width; column++) {
                int y = input[yRow + column] & 0xff;
                int uvOffset = uvRow + (column & ~1);
                int v = (input[uvOffset] & 0xff) - 128;
                int u = (input[uvOffset + 1] & 0xff) - 128;
                int c = Math.max(0, y - 16);
                int red = clamp((298 * c + 409 * v + 128) >> 8);
                int green = clamp((298 * c - 100 * u - 208 * v + 128) >> 8);
                int blue = clamp((298 * c + 516 * u + 128) >> 8);
                output[yRow + column] =
                        0xff000000 | (red << 16) | (green << 8) | blue;
            }
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
