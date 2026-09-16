package com.zuicun.lipmotion;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;

final class LipViewOverlayRenderer implements View.OnLayoutChangeListener {
    private final LipOverlayDrawable drawable;
    private View target;

    LipViewOverlayRenderer(LipMotionConfig config) {
        drawable = new LipOverlayDrawable(new LipMotionCanvasRenderer(config));
    }

    void setView(View value) {
        if (target == value) return;
        if (target != null) {
            target.removeOnLayoutChangeListener(this);
            target.getOverlay().remove(drawable);
        }
        target = value;
        if (value == null) return;
        value.addOnLayoutChangeListener(this);
        drawable.setDensity(value.getResources().getDisplayMetrics().density);
        drawable.setBounds(0, 0, value.getWidth(), value.getHeight());
        value.getOverlay().add(drawable);
        value.invalidate();
    }

    void render(LipMotionResult result) {
        drawable.setResult(result);
        if (target != null) target.invalidate();
    }

    void clear() {
        render(null);
    }

    @Override
    public void onLayoutChange(
            View view,
            int left,
            int top,
            int right,
            int bottom,
            int oldLeft,
            int oldTop,
            int oldRight,
            int oldBottom
    ) {
        drawable.setBounds(0, 0, right - left, bottom - top);
        drawable.invalidateSelf();
    }

    private static final class LipOverlayDrawable extends Drawable {
        private final LipMotionCanvasRenderer renderer;
        private LipMotionResult result;
        private float density = 1f;

        LipOverlayDrawable(LipMotionCanvasRenderer renderer) {
            this.renderer = renderer;
        }

        void setResult(LipMotionResult value) {
            result = value;
            invalidateSelf();
        }

        void setDensity(float value) {
            density = value;
        }

        @Override
        public void draw(Canvas canvas) {
            renderer.draw(canvas, result, getBounds().width(), getBounds().height(), density);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
