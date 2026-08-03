package com.zuicun.liptalkdemo;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;

/** Dedicated administrator portrait capture screen. */
public final class AdminCameraActivity extends ComponentActivity {
    public static final String EXTRA_PHOTO_PATH = "admin_photo_path";
    private ImageCapture imageCapture;
    private TextView captureButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildInterface();
        startCamera();
    }

    private void buildInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        PreviewView preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        // The RK3576 external-camera HAL reports a sensor orientation that only
        // PreviewView's compatible transform path applies correctly.
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        preview.setTag("preview");

        root.addView(new GuideView(this), new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("拍摄管理员头像");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setGravity(Gravity.CENTER);
        title.setBackgroundColor(Color.argb(145, 0, 0, 0));
        title.setPadding(dp(18), dp(15), dp(18), dp(15));
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                -1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        titleParams.topMargin = dp(25);
        root.addView(title, titleParams);

        TextView cancel = button("取消", Color.argb(190, 30, 41, 59));
        cancel.setOnClickListener(view -> finish());
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(
                dp(82), dp(52), Gravity.BOTTOM | Gravity.START);
        cancelParams.setMargins(dp(25), 0, 0, dp(38));
        root.addView(cancel, cancelParams);

        captureButton = button("拍照", Color.rgb(13, 148, 136));
        captureButton.setTextSize(18f);
        captureButton.setOnClickListener(view -> takePhoto());
        FrameLayout.LayoutParams captureParams = new FrameLayout.LayoutParams(
                dp(112), dp(60), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        captureParams.bottomMargin = dp(34);
        root.addView(captureButton, captureParams);

        TextView tip = new TextView(this);
        tip.setText("请正对镜头，让完整脸部位于取景框内");
        tip.setTextColor(Color.WHITE);
        tip.setTextSize(14f);
        tip.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tipParams = new FrameLayout.LayoutParams(
                -1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        tipParams.setMargins(dp(20), 0, dp(20), dp(112));
        root.addView(tip, tipParams);

        setContentView(root);
    }

    private void startCamera() {
        PreviewView preview = findViewById(android.R.id.content)
                .findViewWithTag("preview");
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                CompatibleCamera.Selection camera = CompatibleCamera.select(provider);
                Preview.Builder previewBuilder = new Preview.Builder();
                ImageCapture.Builder captureBuilder = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY);
                if (camera.compensateRockchipUsbRotation) {
                    previewBuilder.setTargetRotation(camera.targetRotation());
                    captureBuilder.setTargetRotation(camera.targetRotation());
                }
                Preview cameraPreview = previewBuilder.build();
                cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                imageCapture = captureBuilder.build();
                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        camera.selector,
                        cameraPreview,
                        imageCapture);
            } catch (Exception error) {
                Toast.makeText(this, "摄像头启动失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        ImageCapture capture = imageCapture;
        if (capture == null) return;
        captureButton.setEnabled(false);
        captureButton.setText("处理中…");
        File output = new File(getCacheDir(), "admin_capture_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(output).build();
        capture.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(
                            @NonNull ImageCapture.OutputFileResults outputFileResults
                    ) {
                        Intent result = new Intent();
                        result.putExtra(EXTRA_PHOTO_PATH, output.getAbsolutePath());
                        setResult(RESULT_OK, result);
                        finish();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        captureButton.setEnabled(true);
                        captureButton.setText("拍照");
                        Toast.makeText(
                                AdminCameraActivity.this,
                                "拍照失败：" + exception.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private TextView button(String text, int color) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16f);
        button.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(28));
        button.setBackground(background);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class GuideView extends View {
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);

        GuideView(AdminCameraActivity context) {
            super(context);
            shade.setColor(Color.argb(105, 0, 0, 0));
            border.setColor(Color.rgb(94, 234, 212));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(context.dp(3));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float ovalWidth = width * 0.66f;
            float ovalHeight = ovalWidth * 1.28f;
            float left = (width - ovalWidth) / 2f;
            float top = getHeight() * 0.20f;
            RectF oval = new RectF(left, top, left + ovalWidth, top + ovalHeight);
            int checkpoint = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            canvas.drawRect(0, 0, getWidth(), getHeight(), shade);
            Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
            clear.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.CLEAR));
            canvas.drawOval(oval, clear);
            canvas.restoreToCount(checkpoint);
            canvas.drawOval(oval, border);
        }
    }
}
