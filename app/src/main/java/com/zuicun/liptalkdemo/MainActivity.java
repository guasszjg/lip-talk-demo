package com.zuicun.liptalkdemo;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity
        implements FaceLandmarkerEngine.Listener, DetectionModeController {
    private static final int COLOR_BACKGROUND = Color.rgb(7, 17, 31);
    private static final int COLOR_PRIMARY = Color.rgb(94, 234, 212);
    private static final int COLOR_SUCCESS = Color.rgb(74, 222, 128);
    private static final int COLOR_ERROR = Color.rgb(248, 113, 113);
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(203, 213, 225);
    private static final int COLOR_TEXT_MUTED = Color.rgb(148, 163, 184);

    private PreviewView previewView;
    private FaceOverlayView overlayView;
    private TextView brandBadge;
    private TextView modeButton;
    private TextView statusDot;
    private TextView statusText;
    private TextView metricsText;
    private TextView helperText;
    private FrameLayout.LayoutParams brandParams;
    private FrameLayout.LayoutParams modeParams;
    private FrameLayout.LayoutParams cardParams;
    private ExecutorService cameraExecutor;
    private volatile FaceLandmarkerEngine engine;
    private volatile DetectionMode detectionMode = DetectionMode.SINGLE;
    private volatile MultiFaceAnalyzer multiFaceAnalyzer =
            new MultiFaceAnalyzer(DetectionMode.SINGLE);

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) prepareDetector();
                else showError("需要摄像头权限才能运行");
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureEdgeToEdge();
        detectionMode = loadDetectionMode();
        multiFaceAnalyzer = new MultiFaceAnalyzer(detectionMode);
        buildInterface();
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            prepareDetector();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
    }

    private void buildInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BACKGROUND);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        overlayView = new FaceOverlayView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        View bottomScrim = new View(this);
        GradientDrawable scrim = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.argb(235, 7, 17, 31), Color.TRANSPARENT});
        bottomScrim.setBackground(scrim);
        root.addView(bottomScrim, new FrameLayout.LayoutParams(
                -1, dp(380), Gravity.BOTTOM));
        root.addView(overlayView, new FrameLayout.LayoutParams(-1, -1));

        brandBadge = new TextView(this);
        brandBadge.setText("●  ON-DEVICE VISION");
        brandBadge.setTextColor(COLOR_PRIMARY);
        brandBadge.setTextSize(11f);
        brandBadge.setLetterSpacing(0.08f);
        brandBadge.setGravity(Gravity.CENTER);
        brandBadge.setPadding(dp(12), dp(8), dp(12), dp(8));
        brandBadge.setBackground(roundedBackground(
                Color.argb(205, 7, 17, 31), dp(18), Color.argb(90, 148, 163, 184), dp(1)));
        brandParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        brandParams.setMargins(dp(14), dp(12), dp(12), dp(12));
        root.addView(brandBadge, brandParams);

        modeButton = new TextView(this);
        modeButton.setTextColor(Color.WHITE);
        modeButton.setTextSize(14f);
        modeButton.setGravity(Gravity.CENTER);
        modeButton.setPadding(dp(15), dp(9), dp(15), dp(9));
        modeButton.setElevation(dp(6));
        modeButton.setOnClickListener(view -> setDetectionMode(
                detectionMode == DetectionMode.SINGLE
                        ? DetectionMode.MULTI
                        : DetectionMode.SINGLE));
        updateModeButton();
        modeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        modeParams.setMargins(dp(12), dp(12), dp(14), dp(12));
        root.addView(modeButton, modeParams);

        LinearLayout card = createStatusCard();
        cardParams = new FrameLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        cardParams.setMargins(dp(14), dp(14), dp(14), dp(14));
        root.addView(card, cardParams);
        setContentView(root);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            brandParams.topMargin = systemBars.top + dp(12);
            modeParams.topMargin = systemBars.top + dp(12);
            cardParams.bottomMargin = systemBars.bottom + dp(14);
            brandBadge.setLayoutParams(brandParams);
            modeButton.setLayoutParams(modeParams);
            card.setLayoutParams(cardParams);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private LinearLayout createStatusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(17), dp(20), dp(17));
        card.setElevation(dp(12));
        card.setBackground(roundedBackground(
                Color.argb(235, 10, 24, 42),
                dp(24),
                Color.argb(100, 148, 163, 184),
                dp(1)));

        TextView sectionLabel = new TextView(this);
        sectionLabel.setText("实时视觉状态");
        sectionLabel.setTextColor(COLOR_TEXT_MUTED);
        sectionLabel.setTextSize(11f);
        sectionLabel.setLetterSpacing(0.10f);
        card.addView(sectionLabel, new LinearLayout.LayoutParams(
                -1, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                -1, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(5);
        card.addView(statusRow, rowParams);

        statusDot = new TextView(this);
        statusDot.setText("●");
        statusDot.setTextColor(COLOR_PRIMARY);
        statusDot.setTextSize(18f);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dotParams.rightMargin = dp(10);
        statusRow.addView(statusDot, dotParams);

        statusText = new TextView(this);
        statusText.setText("正在准备视觉模型…");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(22f);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setMaxLines(2);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        metricsText = new TextView(this);
        metricsText.setText("等待摄像头画面");
        metricsText.setTextColor(COLOR_TEXT_SECONDARY);
        metricsText.setTextSize(14f);
        metricsText.setGravity(Gravity.START);
        LinearLayout.LayoutParams metricsParams = new LinearLayout.LayoutParams(
                -1, ViewGroup.LayoutParams.WRAP_CONTENT);
        metricsParams.topMargin = dp(8);
        card.addView(metricsText, metricsParams);

        helperText = new TextView(this);
        helperText.setText("纯视觉检测 · 图像仅在设备端处理");
        helperText.setTextColor(COLOR_TEXT_MUTED);
        helperText.setTextSize(12f);
        helperText.setGravity(Gravity.START);
        LinearLayout.LayoutParams helperParams = new LinearLayout.LayoutParams(
                -1, ViewGroup.LayoutParams.WRAP_CONTENT);
        helperParams.topMargin = dp(5);
        card.addView(helperText, helperParams);
        return card;
    }

    private void prepareDetector() {
        cameraExecutor.execute(() -> {
            try {
                engine = new FaceLandmarkerEngine(
                        getApplicationContext(), detectionMode, this);
                runOnUiThread(this::startCamera);
            } catch (Exception error) {
                showError("模型初始化失败：" + error.getMessage());
            }
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(cameraExecutor, image -> {
                    try {
                        FaceLandmarkerEngine currentEngine = engine;
                        if (currentEngine == null) image.close();
                        else currentEngine.detect(image, true);
                    } catch (Exception error) {
                        image.close();
                        onError(error.getMessage() == null ? "图像处理失败" : error.getMessage());
                    }
                });

                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis);
            } catch (Exception error) {
                showError("摄像头启动失败：" + error.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onResult(
            @NonNull FaceLandmarkerResult result,
            int width,
            int height,
            long latencyMs,
            @NonNull DetectionMode resultMode
    ) {
        if (resultMode != detectionMode) return;
        MultiFaceAnalyzer.Analysis analysis =
                multiFaceAnalyzer.analyze(result.faceLandmarks(), result.timestampMs());
        MultiFaceAnalyzer.FaceReading target = analysis.selected;
        if (target == null) return;
        runOnUiThread(() -> {
            overlayView.setResult(analysis, width, height);
            if (target.isMoving) {
                applyStatus(
                        "已锁定嘴部运动目标 #" + target.trackId,
                        COLOR_SUCCESS,
                        formatMetrics(analysis.faces.size(), target, latencyMs),
                        "嘴部持续运动 · 纯视觉候选结果");
            } else {
                applyStatus(
                        "当前主目标 #" + target.trackId,
                        COLOR_PRIMARY,
                        formatMetrics(analysis.faces.size(), target, latencyMs),
                        resultMode == DetectionMode.SINGLE
                                ? "单人高性能 · 青框和黄唇为当前目标"
                                : "多人检测 · 绿框表示嘴部运动");
            }
        });
    }

    private String formatMetrics(
            int faceCount,
            MultiFaceAnalyzer.FaceReading target,
            long latencyMs
    ) {
        return String.format(
                Locale.US,
                "%d 人   开合 %.3f   运动 %.3f   %d ms",
                faceCount,
                target.openness,
                target.movement,
                latencyMs);
    }

    @Override
    public void onNoFace(@NonNull DetectionMode resultMode) {
        if (resultMode != detectionMode) return;
        multiFaceAnalyzer.reset();
        runOnUiThread(() -> {
            overlayView.clear();
            applyStatus(
                    "等待用户进入画面",
                    COLOR_TEXT_MUTED,
                    "未检测到人脸",
                    "请保持正脸，并让脸部占画面约 1/3");
        });
    }

    @Override
    public void onError(@NonNull String message) {
        showError(message);
    }

    @Override
    public void setDetectionMode(@NonNull DetectionMode mode) {
        if (mode == detectionMode) return;
        detectionMode = mode;
        multiFaceAnalyzer = new MultiFaceAnalyzer(mode);
        getSharedPreferences("lip_demo", MODE_PRIVATE)
                .edit()
                .putString("detection_mode", mode.name())
                .apply();
        runOnUiThread(() -> {
            updateModeButton();
            overlayView.clear();
            applyStatus(
                    "正在切换为" + mode.displayName,
                    mode == DetectionMode.SINGLE ? COLOR_PRIMARY : Color.rgb(96, 165, 250),
                    "正在重新初始化视觉模型…",
                    mode == DetectionMode.SINGLE
                            ? "更低延迟，适合广告机单人交互区"
                            : "最多检测 4 人，并锁定嘴部运动目标");
        });

        if (cameraExecutor == null || cameraExecutor.isShutdown()) return;
        cameraExecutor.execute(() -> {
            FaceLandmarkerEngine previous = engine;
            engine = null;
            if (previous != null) previous.close();
            try {
                engine = new FaceLandmarkerEngine(getApplicationContext(), mode, this);
            } catch (Exception error) {
                showError("模式切换失败：" + error.getMessage());
            }
        });
    }

    @Override
    @NonNull
    public DetectionMode getDetectionMode() {
        return detectionMode;
    }

    private DetectionMode loadDetectionMode() {
        String saved = getSharedPreferences("lip_demo", MODE_PRIVATE)
                .getString("detection_mode", DetectionMode.SINGLE.name());
        try {
            return DetectionMode.valueOf(saved);
        } catch (IllegalArgumentException ignored) {
            return DetectionMode.SINGLE;
        }
    }

    private void updateModeButton() {
        if (modeButton == null) return;
        boolean single = detectionMode == DetectionMode.SINGLE;
        modeButton.setText(single ? "⚡  单人模式" : "◆  多人模式");
        modeButton.setBackground(roundedBackground(
                single ? Color.argb(235, 13, 148, 136) : Color.argb(235, 37, 99, 235),
                dp(22),
                Color.argb(150, 255, 255, 255),
                dp(1)));
    }

    private void applyStatus(String title, int accent, String metrics, String helper) {
        statusDot.setTextColor(accent);
        statusText.setText(title);
        statusText.setTextColor(Color.WHITE);
        metricsText.setText(metrics);
        helperText.setText(helper);
    }

    private void showError(String message) {
        runOnUiThread(() -> applyStatus(
                "视觉服务暂不可用",
                COLOR_ERROR,
                message,
                "请重新打开应用或检查摄像头权限"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.execute(() -> {
            if (engine != null) engine.close();
        });
        cameraExecutor.shutdown();
    }

    private GradientDrawable roundedBackground(
            int fillColor,
            int radius,
            int strokeColor,
            int strokeWidth
    ) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(radius);
        background.setStroke(strokeWidth, strokeColor);
        return background;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
