package com.zuicun.lipmotion;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.List;

/** Draws only the two yellow lip contours onto an existing View Canvas. */
public final class LipMotionCanvasRenderer {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LipMotionConfig.OverlayScaleType scaleType;
    private final int lipColor;

    public LipMotionCanvasRenderer(LipMotionConfig config) {
        scaleType = config.overlayScaleType;
        lipColor = config.lipColor;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** This method does not clear the Canvas and therefore can be called from xxFaceView.onDraw(). */
    public void draw(
            Canvas canvas,
            LipMotionResult result,
            int targetWidth,
            int targetHeight,
            float density
    ) {
        if (result == null || !result.isFaceDetected() || targetWidth <= 0 || targetHeight <= 0) {
            return;
        }
        boolean moving = result.getState() == LipMotionState.MOVING;
        paint.setStrokeWidth((moving ? 4.5f : 3f) * density);
        paint.setColor(Color.argb(
                moving ? 255 : 205,
                Color.red(lipColor),
                Color.green(lipColor),
                Color.blue(lipColor)));

        Transform transform = transform(result, targetWidth, targetHeight);
        drawPath(canvas, result.getOuterLip(), transform);
        drawPath(canvas, result.getInnerLip(), transform);
    }

    private Transform transform(LipMotionResult result, int targetWidth, int targetHeight) {
        float sourceWidth = result.getImageWidth();
        float sourceHeight = result.getImageHeight();
        float scaleX = targetWidth / sourceWidth;
        float scaleY = targetHeight / sourceHeight;
        if (scaleType == LipMotionConfig.OverlayScaleType.STRETCH) {
            return new Transform(scaleX, scaleY, 0f, 0f, sourceWidth, sourceHeight);
        }
        float uniformScale = scaleType == LipMotionConfig.OverlayScaleType.CENTER_CROP
                ? Math.max(scaleX, scaleY)
                : Math.min(scaleX, scaleY);
        return new Transform(
                uniformScale,
                uniformScale,
                (targetWidth - sourceWidth * uniformScale) / 2f,
                (targetHeight - sourceHeight * uniformScale) / 2f,
                sourceWidth,
                sourceHeight);
    }

    private void drawPath(Canvas canvas, List<LipPoint> points, Transform transform) {
        if (points.isEmpty()) return;
        Path path = new Path();
        for (int index = 0; index < points.size(); index++) {
            LipPoint point = points.get(index);
            float x = point.getX() * transform.sourceWidth * transform.scaleX
                    + transform.offsetX;
            float y = point.getY() * transform.sourceHeight * transform.scaleY
                    + transform.offsetY;
            if (index == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        canvas.drawPath(path, paint);
    }

    private static final class Transform {
        final float scaleX;
        final float scaleY;
        final float offsetX;
        final float offsetY;
        final float sourceWidth;
        final float sourceHeight;

        Transform(
                float scaleX,
                float scaleY,
                float offsetX,
                float offsetY,
                float sourceWidth,
                float sourceHeight
        ) {
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
        }
    }
}
