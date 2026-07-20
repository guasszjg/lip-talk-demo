package com.zuicun.liptalkdemo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.ArrayList;
import java.util.List;

public final class FaceOverlayView extends View {
    private static final int[] FACE_OVAL = {
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288,
            397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136,
            172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109
    };
    private static final int[] OUTER_LIPS = {
            61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291,
            375, 321, 405, 314, 17, 84, 181, 91, 146
    };
    private static final int[] INNER_LIPS = {
            78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308,
            324, 318, 402, 317, 14, 87, 178, 88, 95
    };

    private final Paint selectedFacePaint = stroke(Color.rgb(94, 234, 212), 5f);
    private final Paint selectedLipPaint = stroke(Color.rgb(250, 204, 21), 7f);
    private final Paint otherFacePaint = stroke(Color.argb(150, 203, 213, 225), 3f);
    private final Paint movingFacePaint = stroke(Color.rgb(74, 222, 128), 4f);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<MultiFaceAnalyzer.FaceReading> faces = new ArrayList<>();
    private int imageWidth;
    private int imageHeight;

    public FaceOverlayView(Context context) {
        this(context, null);
    }

    public FaceOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        badgePaint.setColor(Color.argb(210, 7, 17, 31));
        badgePaint.setStyle(Paint.Style.FILL);
        badgeTextPaint.setColor(Color.WHITE);
        badgeTextPaint.setTextSize(dp(14));
        badgeTextPaint.setFakeBoldText(true);
    }

    public void setResult(MultiFaceAnalyzer.Analysis analysis, int width, int height) {
        faces = new ArrayList<>(analysis.faces);
        imageWidth = width;
        imageHeight = height;
        postInvalidate();
    }

    public void clear() {
        faces = new ArrayList<>();
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (faces.isEmpty() || imageWidth == 0 || imageHeight == 0) return;

        float scale = Math.max(
                getWidth() / (float) imageWidth,
                getHeight() / (float) imageHeight);
        float offsetX = (getWidth() - imageWidth * scale) / 2f;
        float offsetY = (getHeight() - imageHeight * scale) / 2f;

        for (MultiFaceAnalyzer.FaceReading face : faces) {
            Paint facePaint = face.isSelected
                    ? selectedFacePaint
                    : (face.isMoving ? movingFacePaint : otherFacePaint);
            drawPath(canvas, face.landmarks, FACE_OVAL, facePaint, scale, offsetX, offsetY);
            if (face.isSelected) {
                drawPath(canvas, face.landmarks, OUTER_LIPS, selectedLipPaint, scale, offsetX, offsetY);
                drawPath(canvas, face.landmarks, INNER_LIPS, selectedLipPaint, scale, offsetX, offsetY);
            }
            drawBadge(canvas, face, scale, offsetX, offsetY);
        }
    }

    private void drawPath(
            Canvas canvas,
            List<NormalizedLandmark> landmarks,
            int[] indexes,
            Paint paint,
            float scale,
            float offsetX,
            float offsetY
    ) {
        Path path = new Path();
        for (int position = 0; position < indexes.length; position++) {
            int index = indexes[position];
            if (index >= landmarks.size()) return;
            NormalizedLandmark point = landmarks.get(index);
            float x = point.x() * imageWidth * scale + offsetX;
            float y = point.y() * imageHeight * scale + offsetY;
            if (position == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawBadge(
            Canvas canvas,
            MultiFaceAnalyzer.FaceReading face,
            float scale,
            float offsetX,
            float offsetY
    ) {
        NormalizedLandmark top = face.landmarks.get(10);
        String label = face.isSelected
                ? "目标 #" + face.trackId
                : "#" + face.trackId;
        float x = top.x() * imageWidth * scale + offsetX;
        float y = top.y() * imageHeight * scale + offsetY - dp(12);
        float textWidth = badgeTextPaint.measureText(label);
        float left = x - textWidth / 2f - dp(7);
        float topY = y - dp(19);
        canvas.drawRoundRect(left, topY, left + textWidth + dp(14), y + dp(5),
                dp(8), dp(8), badgePaint);
        canvas.drawText(label, left + dp(7), y, badgeTextPaint);
    }

    private static Paint stroke(int color, float width) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        return paint;
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
