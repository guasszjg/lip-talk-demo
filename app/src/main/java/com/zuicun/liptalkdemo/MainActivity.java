package com.zuicun.liptalkdemo;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
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
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity
        implements FaceLandmarkerEngine.Listener,
        DetectionModeController,
        InferenceBackendController,
        AttributeDetectionController,
        AgeGenderEngine.Listener,
        FaceRecognitionEngine.RecognitionListener,
        FaceRecognitionEngine.EnrollmentListener {
    private static final int COLOR_BACKGROUND = Color.rgb(7, 17, 31);
    private static final int COLOR_PRIMARY = Color.rgb(94, 234, 212);
    private static final int COLOR_SUCCESS = Color.rgb(74, 222, 128);
    private static final int COLOR_ERROR = Color.rgb(248, 113, 113);
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(203, 213, 225);
    private static final int COLOR_TEXT_MUTED = Color.rgb(148, 163, 184);

    private PreviewView previewView;
    private FaceOverlayView overlayView;
    private TextView brandBadge;
    private TextView backendButton;
    private TextView modeButton;
    private TextView statusDot;
    private TextView statusText;
    private TextView metricsText;
    private TextView helperText;
    private TextView buildInfoText;
    private TextView mouthStateBadge;
    private TextView attributeBadge;
    private TextView adminBadge;
    private LinearLayout statusCard;
    private TextView statusSectionLabel;
    private FrameLayout.LayoutParams brandParams;
    private FrameLayout.LayoutParams controlsParams;
    private FrameLayout.LayoutParams cardParams;
    private boolean statusDetailsExpanded;
    private ExecutorService cameraExecutor;
    private volatile FaceLandmarkerEngine engine;
    private volatile AgeGenderEngine ageGenderEngine;
    private volatile FaceRecognitionEngine faceRecognitionEngine;
    private volatile DetectionMode detectionMode = DetectionMode.SINGLE;
    private volatile InferenceBackend inferenceBackend = InferenceBackend.CPU;
    private volatile MultiFaceAnalyzer multiFaceAnalyzer =
            new MultiFaceAnalyzer(DetectionMode.SINGLE);
    private final LatencyTracker latencyTracker = new LatencyTracker(30);
    private final AgeGenderSmoother ageGenderSmoother = new AgeGenderSmoother();
    private volatile boolean attributeDetectionEnabled = true;
    private volatile int selectedTrackId = -1;
    private volatile boolean captureAdminRequested;
    private volatile String pendingAdminName;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) prepareDetector();
                else showError("需要摄像头权限才能运行");
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> photoPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null && pendingAdminName != null) enrollAdminFromPhoto(uri);
                else pendingAdminName = null;
            });

    private final ActivityResultLauncher<Intent> adminSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                FaceRecognitionEngine recognitionEngine = faceRecognitionEngine;
                if (recognitionEngine == null || cameraExecutor == null) return;
                cameraExecutor.execute(() -> {
                    try {
                        recognitionEngine.reloadAdmins();
                        runOnUiThread(() -> {
                            overlayView.setIdentities(java.util.Collections.emptyMap());
                            updateAdminBadge("管理员资料已更新");
                            startCamera();
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> updateAdminBadge(
                                "管理员资料刷新失败：" + error.getMessage()));
                    }
                });
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureEdgeToEdge();
        detectionMode = loadDetectionMode();
        inferenceBackend = loadInferenceBackend();
        attributeDetectionEnabled = loadAttributeDetectionEnabled();
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
        boolean statusAtTop = shouldPlaceStatusAtTop();

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        overlayView = new FaceOverlayView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        View bottomScrim = new View(this);
        GradientDrawable scrim = new GradientDrawable(
                statusAtTop
                        ? GradientDrawable.Orientation.TOP_BOTTOM
                        : GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.argb(72, 7, 17, 31), Color.TRANSPARENT});
        bottomScrim.setBackground(scrim);
        root.addView(bottomScrim, new FrameLayout.LayoutParams(
                -1, dp(230), statusAtTop ? Gravity.TOP : Gravity.BOTTOM));
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

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        backendButton = new TextView(this);
        backendButton.setTextColor(Color.WHITE);
        backendButton.setTextSize(13f);
        backendButton.setGravity(Gravity.CENTER);
        backendButton.setPadding(dp(13), dp(9), dp(13), dp(9));
        backendButton.setElevation(dp(6));
        backendButton.setOnClickListener(view -> {
            FaceLandmarkerEngine current = engine;
            if (current != null && current.usesRknn()) {
                Toast.makeText(this, "RK3576 当前固定使用 RKNN NPU", Toast.LENGTH_SHORT).show();
                return;
            }
            setInferenceBackend(
                    inferenceBackend == InferenceBackend.CPU
                            ? InferenceBackend.GPU
                            : InferenceBackend.CPU);
        });
        updateBackendButton();
        controls.addView(backendButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        modeButton = new TextView(this);
        modeButton.setTextColor(Color.WHITE);
        modeButton.setTextSize(13f);
        modeButton.setGravity(Gravity.CENTER);
        modeButton.setPadding(dp(13), dp(9), dp(13), dp(9));
        modeButton.setElevation(dp(6));
        modeButton.setOnClickListener(view -> setDetectionMode(
                detectionMode == DetectionMode.SINGLE
                        ? DetectionMode.MULTI
                        : DetectionMode.SINGLE));
        updateModeButton();
        LinearLayout.LayoutParams modeButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        modeButtonParams.leftMargin = dp(8);
        controls.addView(modeButton, modeButtonParams);

        controlsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        controlsParams.setMargins(dp(12), dp(12), dp(14), dp(12));
        root.addView(controls, controlsParams);

        LinearLayout card = createStatusCard();
        cardParams = new FrameLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                statusAtTop ? Gravity.TOP : Gravity.BOTTOM);
        cardParams.setMargins(dp(10), dp(10), dp(10), dp(10));
        root.addView(card, cardParams);
        setContentView(root);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            brandParams.topMargin = systemBars.top + dp(12);
            controlsParams.topMargin = systemBars.top + dp(12);
            if (statusAtTop) {
                cardParams.topMargin = systemBars.top + dp(64);
                cardParams.bottomMargin = dp(10);
            } else {
                cardParams.topMargin = dp(10);
                cardParams.bottomMargin = systemBars.bottom + dp(10);
            }
            brandBadge.setLayoutParams(brandParams);
            controls.setLayoutParams(controlsParams);
            card.setLayoutParams(cardParams);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private boolean shouldPlaceStatusAtTop() {
        if (!DevicePlatform.isRockchip()) return false;
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        return height > width * 2;
    }

    private LinearLayout createStatusCard() {
        LinearLayout card = new LinearLayout(this);
        statusCard = card;
        card.setOrientation(LinearLayout.VERTICAL);
        card.setElevation(dp(4));
        card.setOnClickListener(view -> setStatusDetailsExpanded(!statusDetailsExpanded));

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(headerRow, new LinearLayout.LayoutParams(
                -1, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusSectionLabel = new TextView(this);
        statusSectionLabel.setTextColor(COLOR_TEXT_SECONDARY);
        statusSectionLabel.setTextSize(10f);
        statusSectionLabel.setLetterSpacing(0.06f);
        headerRow.addView(statusSectionLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mouthStateBadge = new TextView(this);
        mouthStateBadge.setTextSize(11f);
        mouthStateBadge.setGravity(Gravity.CENTER);
        mouthStateBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        setMouthStateBadge(
                "嘴巴：等待",
                COLOR_TEXT_SECONDARY,
                Color.argb(150, 51, 65, 85));
        headerRow.addView(mouthStateBadge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        attributeBadge = new TextView(this);
        attributeBadge.setTextSize(10.5f);
        attributeBadge.setGravity(Gravity.CENTER_VERTICAL);
        attributeBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        attributeBadge.setEllipsize(android.text.TextUtils.TruncateAt.END);
        attributeBadge.setOnClickListener(view -> setAttributeDetectionEnabled(
                !attributeDetectionEnabled));
        updateAttributeBadgeWaiting();
        LinearLayout.LayoutParams attributeParams = new LinearLayout.LayoutParams(
                -1, ViewGroup.LayoutParams.WRAP_CONTENT);
        attributeParams.topMargin = dp(8);
        card.addView(attributeBadge, attributeParams);

        adminBadge = new TextView(this);
        adminBadge.setTextSize(10.5f);
        adminBadge.setGravity(Gravity.CENTER_VERTICAL);
        adminBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        adminBadge.setEllipsize(android.text.TextUtils.TruncateAt.END);
        adminBadge.setOnClickListener(view -> showAdminMenu());
        updateAdminBadge("点击设置或管理主人");
        LinearLayout.LayoutParams adminParams = new LinearLayout.LayoutParams(
                -1, ViewGroup.LayoutParams.WRAP_CONTENT);
        adminParams.topMargin = dp(6);
        card.addView(adminBadge, adminParams);

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
        statusDot.setTextSize(15f);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dotParams.rightMargin = dp(10);
        statusRow.addView(statusDot, dotParams);

        statusText = new TextView(this);
        statusText.setText("正在准备视觉模型…");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(17f);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        metricsText = new TextView(this);
        metricsText.setText("等待摄像头画面");
        metricsText.setTextColor(COLOR_TEXT_SECONDARY);
        metricsText.setTextSize(12f);
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

        buildInfoText = new TextView(this);
        buildInfoText.setText(
                "版本 v" + BuildConfig.VERSION_NAME
                        + " (" + BuildConfig.VERSION_CODE + ")"
                        + " · 编译 " + BuildConfig.BUILD_TIME);
        buildInfoText.setTextColor(Color.rgb(100, 116, 139));
        buildInfoText.setTextSize(10f);
        buildInfoText.setGravity(Gravity.START);
        LinearLayout.LayoutParams buildInfoParams = new LinearLayout.LayoutParams(
                -1, ViewGroup.LayoutParams.WRAP_CONTENT);
        buildInfoParams.topMargin = dp(5);
        card.addView(buildInfoText, buildInfoParams);
        setStatusDetailsExpanded(false);
        return card;
    }

    private void setStatusDetailsExpanded(boolean expanded) {
        statusDetailsExpanded = expanded;
        if (statusCard == null) return;
        statusSectionLabel.setText(expanded
                ? "实时视觉状态 · 点击收起"
                : "实时状态 · 点击展开详情");
        statusCard.setPadding(
                expanded ? dp(16) : dp(12),
                expanded ? dp(14) : dp(10),
                expanded ? dp(16) : dp(12),
                expanded ? dp(14) : dp(10));
        statusCard.setBackground(roundedBackground(
                expanded
                        ? Color.argb(205, 10, 24, 42)
                        : Color.argb(105, 10, 24, 42),
                dp(20),
                expanded
                        ? Color.argb(100, 148, 163, 184)
                        : Color.argb(75, 203, 213, 225),
                dp(1)));
        statusText.setSingleLine(!expanded);
        attributeBadge.setSingleLine(!expanded);
        adminBadge.setSingleLine(!expanded);
        metricsText.setVisibility(expanded ? View.VISIBLE : View.GONE);
        helperText.setVisibility(expanded ? View.VISIBLE : View.GONE);
        buildInfoText.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private void prepareDetector() {
        cameraExecutor.execute(() -> {
            try {
                prepareAttributeEngineIfNeeded();
                prepareFaceRecognitionEngineIfNeeded();
                try {
                    engine = new FaceLandmarkerEngine(
                            getApplicationContext(), detectionMode, inferenceBackend, this);
                } catch (Exception gpuError) {
                    if (inferenceBackend != InferenceBackend.GPU) throw gpuError;
                    inferenceBackend = InferenceBackend.CPU;
                    saveInferenceBackend(InferenceBackend.CPU);
                    engine = new FaceLandmarkerEngine(
                            getApplicationContext(), detectionMode, InferenceBackend.CPU, this);
                    runOnUiThread(() -> {
                        updateBackendButton();
                        applyStatus(
                                "GPU 不可用，已回退 CPU",
                                COLOR_ERROR,
                                "设备未能初始化 MediaPipe GPU Delegate",
                                "应用仍可继续使用 CPU 推理");
                    });
                }
                runOnUiThread(() -> {
                    updateBackendButton();
                    startCamera();
                });
            } catch (Exception error) {
                showError("模型初始化失败：" + error.getMessage());
            }
        });
    }

    private void prepareFaceRecognitionEngineIfNeeded() {
        if (faceRecognitionEngine != null) return;
        try {
            faceRecognitionEngine = new FaceRecognitionEngine(getApplicationContext());
            runOnUiThread(() -> updateAdminBadge("点击设置或管理主人"));
        } catch (Exception error) {
            runOnUiThread(() -> updateAdminBadge(
                    "主人识别不可用：" + (error.getMessage() == null ? "模型初始化失败" : error.getMessage())));
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                CompatibleCamera.Selection camera = CompatibleCamera.select(provider);
                Preview.Builder previewBuilder = new Preview.Builder();
                ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);
                if (camera.compensateRockchipUsbRotation) {
                    previewBuilder.setTargetRotation(camera.targetRotation());
                    analysisBuilder.setTargetRotation(camera.targetRotation());
                }
                Preview preview = previewBuilder.build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = analysisBuilder.build();
                analysis.setAnalyzer(cameraExecutor, image -> {
                    try {
                        FaceLandmarkerEngine currentEngine = engine;
                        if (currentEngine == null) image.close();
                        else currentEngine.detect(image, camera.mirrored);
                    } catch (Exception error) {
                        image.close();
                        onError(error.getMessage() == null ? "图像处理失败" : error.getMessage());
                    }
                });

                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        camera.selector,
                        preview,
                        analysis);
            } catch (Exception error) {
                showError("摄像头启动失败：" + error.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onResult(
            @NonNull List<List<NormalizedLandmark>> faces,
            long timestampMs,
            @NonNull Bitmap frame,
            int width,
            int height,
            long latencyMs,
            @NonNull DetectionMode resultMode,
            @NonNull InferenceBackend resultBackend
    ) {
        if (resultMode != detectionMode || resultBackend != inferenceBackend) return;
        MultiFaceAnalyzer.Analysis analysis =
                multiFaceAnalyzer.analyze(faces, timestampMs);
        MultiFaceAnalyzer.FaceReading target = analysis.selected;
        if (target == null) return;
        selectedTrackId = target.trackId;
        FaceRecognitionEngine recognitionEngine = faceRecognitionEngine;
        if (captureAdminRequested && recognitionEngine != null && pendingAdminName != null) {
            captureAdminRequested = false;
            recognitionEngine.enroll(frame, target.landmarks, pendingAdminName, this);
        }
        if (recognitionEngine != null) {
            recognitionEngine.submit(frame, analysis.faces, this);
        }
        AgeGenderEngine attributeEngine = ageGenderEngine;
        if (attributeDetectionEnabled && attributeEngine != null) {
            attributeEngine.submit(frame, target, this);
        }
        long averageLatencyMs = latencyTracker.add(latencyMs);
        runOnUiThread(() -> {
            overlayView.setResult(analysis, width, height);
            updateMouthState(target);
            if (target.isMoving) {
                applyStatus(
                        "已锁定嘴部运动目标 #" + target.trackId,
                        COLOR_SUCCESS,
                        formatMetrics(analysis.faces.size(), target, latencyMs, resultBackend),
                        "近 30 帧均值 " + averageLatencyMs + " ms · 纯视觉候选结果");
            } else {
                applyStatus(
                        "当前主目标 #" + target.trackId,
                        COLOR_PRIMARY,
                        formatMetrics(analysis.faces.size(), target, latencyMs, resultBackend),
                        resultMode == DetectionMode.SINGLE
                                ? "近 30 帧均值 " + averageLatencyMs + " ms · 单人高性能"
                                : "近 30 帧均值 " + averageLatencyMs + " ms · 多人检测");
            }
        });
    }

    private String formatMetrics(
            int faceCount,
            MultiFaceAnalyzer.FaceReading target,
            long latencyMs,
            InferenceBackend backend
    ) {
        return String.format(
                Locale.US,
                "%s   %d 人   开合 %.3f   运动 %.3f   %d ms",
                effectiveFaceBackendName(backend),
                faceCount,
                target.openness,
                target.movement,
                latencyMs);
    }

    @Override
    public void onNoFace(
            @NonNull DetectionMode resultMode,
            @NonNull InferenceBackend resultBackend
    ) {
        if (resultMode != detectionMode || resultBackend != inferenceBackend) return;
        multiFaceAnalyzer.reset();
        selectedTrackId = -1;
        ageGenderSmoother.reset();
        AgeGenderEngine attributeEngine = ageGenderEngine;
        if (attributeEngine != null) attributeEngine.resetTracking();
        FaceRecognitionEngine recognitionEngine = faceRecognitionEngine;
        if (recognitionEngine != null) recognitionEngine.resetTracking();
        runOnUiThread(() -> {
            overlayView.clear();
            updateAttributeBadgeWaiting();
            updateAdminBadge("等待人脸");
            setMouthStateBadge(
                    "嘴巴：等待",
                    COLOR_TEXT_SECONDARY,
                    Color.argb(150, 51, 65, 85));
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
        latencyTracker.reset();
        selectedTrackId = -1;
        ageGenderSmoother.reset();
        AgeGenderEngine attributeEngine = ageGenderEngine;
        if (attributeEngine != null) attributeEngine.resetTracking();
        FaceRecognitionEngine recognitionEngine = faceRecognitionEngine;
        if (recognitionEngine != null) recognitionEngine.resetTracking();
        getSharedPreferences("lip_demo", MODE_PRIVATE)
                .edit()
                .putString("detection_mode", mode.name())
                .apply();
        runOnUiThread(() -> {
            updateModeButton();
            overlayView.clear();
            setMouthStateBadge(
                    "嘴巴：采样中",
                    Color.rgb(253, 224, 71),
                    Color.argb(155, 113, 63, 18));
            applyStatus(
                    "正在切换为" + mode.displayName,
                    mode == DetectionMode.SINGLE ? COLOR_PRIMARY : Color.rgb(96, 165, 250),
                    "正在重新初始化视觉模型…",
                    mode == DetectionMode.SINGLE
                            ? "更低延迟，适合广告机单人交互区"
                            : "最多检测 4 人，并锁定嘴部运动目标");
        });

        if (cameraExecutor == null || cameraExecutor.isShutdown()) return;
        rebuildEngine(mode, inferenceBackend, inferenceBackend == InferenceBackend.GPU);
    }

    @Override
    public void setInferenceBackend(@NonNull InferenceBackend backend) {
        if (backend == inferenceBackend) return;
        inferenceBackend = backend;
        latencyTracker.reset();
        saveInferenceBackend(backend);
        runOnUiThread(() -> {
            updateBackendButton();
            overlayView.clear();
            setMouthStateBadge(
                    "嘴巴：采样中",
                    Color.rgb(253, 224, 71),
                    Color.argb(155, 113, 63, 18));
            applyStatus(
                    "正在切换到 " + backend.displayName,
                    backend == InferenceBackend.GPU
                            ? Color.rgb(192, 132, 252)
                            : COLOR_PRIMARY,
                    "正在重新初始化 MediaPipe 推理后端…",
                    backend == InferenceBackend.GPU
                            ? "GPU 首次初始化可能需要稍候"
                            : "CPU 兼容性更好，适合作为稳定基线");
        });
        rebuildEngine(detectionMode, backend, true);
    }

    @Override
    @NonNull
    public InferenceBackend getInferenceBackend() {
        return inferenceBackend;
    }

    private void rebuildEngine(
            DetectionMode requestedMode,
            InferenceBackend requestedBackend,
            boolean allowGpuFallback
    ) {
        if (cameraExecutor == null || cameraExecutor.isShutdown()) return;
        cameraExecutor.execute(() -> {
            FaceLandmarkerEngine previous = engine;
            engine = null;
            if (previous != null) previous.close();
            try {
                engine = new FaceLandmarkerEngine(
                        getApplicationContext(), requestedMode, requestedBackend, this);
                runOnUiThread(this::updateBackendButton);
            } catch (Exception error) {
                if (allowGpuFallback && requestedBackend == InferenceBackend.GPU) {
                    try {
                        inferenceBackend = InferenceBackend.CPU;
                        saveInferenceBackend(InferenceBackend.CPU);
                        engine = new FaceLandmarkerEngine(
                                getApplicationContext(), requestedMode, InferenceBackend.CPU, this);
                        runOnUiThread(() -> {
                            updateBackendButton();
                            applyStatus(
                                    "GPU 不可用，已回退 CPU",
                                    COLOR_ERROR,
                                    "GPU Delegate 初始化失败",
                                    "应用仍可继续使用 CPU 推理");
                        });
                        return;
                    } catch (Exception cpuError) {
                        showError("CPU 回退失败：" + cpuError.getMessage());
                        return;
                    }
                }
                showError("推理后端切换失败：" + error.getMessage());
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

    private InferenceBackend loadInferenceBackend() {
        String saved = getSharedPreferences("lip_demo", MODE_PRIVATE)
                .getString("inference_backend", InferenceBackend.CPU.name());
        try {
            return InferenceBackend.valueOf(saved);
        } catch (IllegalArgumentException ignored) {
            return InferenceBackend.CPU;
        }
    }

    private void saveInferenceBackend(InferenceBackend backend) {
        getSharedPreferences("lip_demo", MODE_PRIVATE)
                .edit()
                .putString("inference_backend", backend.name())
                .apply();
    }

    @Override
    public void setAttributeDetectionEnabled(boolean enabled) {
        if (enabled == attributeDetectionEnabled) return;
        attributeDetectionEnabled = enabled;
        ageGenderSmoother.reset();
        getSharedPreferences("lip_demo", MODE_PRIVATE)
                .edit()
                .putBoolean("attribute_detection", enabled)
                .apply();
        if (!enabled) {
            updateAttributeBadgeWaiting();
            return;
        }
        updateAttributeBadgeWaiting();
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
            cameraExecutor.execute(this::prepareAttributeEngineIfNeeded);
        }
    }

    @Override
    public boolean isAttributeDetectionEnabled() {
        return attributeDetectionEnabled;
    }

    private boolean loadAttributeDetectionEnabled() {
        return getSharedPreferences("lip_demo", MODE_PRIVATE)
                .getBoolean("attribute_detection", true);
    }

    private void prepareAttributeEngineIfNeeded() {
        if (!attributeDetectionEnabled || ageGenderEngine != null) return;
        try {
            ageGenderEngine = new AgeGenderEngine(getApplicationContext());
            runOnUiThread(this::updateAttributeBadgeWaiting);
        } catch (Exception error) {
            runOnUiThread(() -> setAttributeBadge(
                    "属性估计：模型未安装 · 点击关闭",
                    COLOR_ERROR,
                    Color.argb(155, 127, 29, 29)));
        }
    }

    @Override
    public void onAttributeResult(
            int trackId,
            int gender,
            int age,
            float confidence,
            long latencyMs
    ) {
        if (!attributeDetectionEnabled || trackId != selectedTrackId) return;
        AgeGenderSmoother.Result result = ageGenderSmoother.add(
                trackId, gender, age, confidence, latencyMs);
        runOnUiThread(() -> {
            if (!attributeDetectionEnabled || result.trackId != selectedTrackId) return;
            setAttributeBadge(
                    "模型分类[" + (ageGenderEngine == null
                            ? "不可用" : ageGenderEngine.backendName()) + "]：" + result.genderLabel()
                            + " " + result.genderConfidencePercent() + "%"
                            + " · 外观 " + result.ageBand()
                            + " · " + result.latencyMs + " ms"
                            + " · 样本 " + result.sampleCount + "/"
                            + AgeGenderSmoother.WINDOW_SIZE,
                    Color.rgb(216, 180, 254),
                    Color.argb(165, 88, 28, 135));
        });
    }

    @Override
    public void onAttributeError(String message) {
        if (!attributeDetectionEnabled) return;
        runOnUiThread(() -> setAttributeBadge(
                "属性估计失败：" + message,
                COLOR_ERROR,
                Color.argb(155, 127, 29, 29)));
    }

    @Override
    public void onRecognition(
            Map<Integer, FaceRecognitionEngine.Match> matches,
            long latencyMs
    ) {
        runOnUiThread(() -> {
            overlayView.setIdentities(matches);
            FaceRecognitionEngine.Match selected = matches.get(selectedTrackId);
            if (selected != null && selected.isAdmin) {
                updateAdminBadge(
                        "✓ 主人：" + selected.name
                                + " · 相似度 " + Math.round(selected.similarity * 100f) + "%"
                                + " · " + latencyMs + " ms",
                        true);
            } else {
                if (selected != null && selected.name != null) {
                    updateAdminBadge(
                            "候选：" + selected.name
                                    + " · 相似度 " + Math.round(selected.similarity * 100f)
                                    + "% · 未确认");
                } else {
                    updateAdminBadge("未确认主人 · 点击管理");
                }
            }
        });
    }

    @Override
    public void onRecognitionError(String message) {
        runOnUiThread(() -> updateAdminBadge("主人识别失败：" + message));
    }

    @Override
    public void onEnrolled(AdminFaceStore.Record record, int total) {
        pendingAdminName = null;
        runOnUiThread(() -> {
            updateAdminBadge("已添加主人：" + record.name + " · 共 " + total + " 位");
            Toast.makeText(this, "主人“" + record.name + "”注册成功", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onEnrollmentError(String message) {
        pendingAdminName = null;
        captureAdminRequested = false;
        runOnUiThread(() -> {
            updateAdminBadge("注册失败：" + message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void showAdminMenu() {
        adminSettingsLauncher.launch(new Intent(this, AdminSettingsActivity.class));
    }

    private void promptAdminName() {
        EditText input = new EditText(this);
        input.setHint("例如：张三");
        input.setSingleLine(true);
        int padding = dp(20);
        input.setPadding(padding, dp(8), padding, dp(8));
        new AlertDialog.Builder(this)
                .setTitle("输入主人名称")
                .setView(input)
                .setPositiveButton("下一步", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pendingAdminName = name;
                    chooseEnrollmentSource();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseEnrollmentSource() {
        new AlertDialog.Builder(this)
                .setTitle("注册“" + pendingAdminName + "”")
                .setItems(new String[]{"使用当前摄像头拍照", "从相册选择照片"}, (dialog, which) -> {
                    if (which == 0) {
                        captureAdminRequested = true;
                        updateAdminBadge("请正对摄像头，正在采集“" + pendingAdminName + "”…");
                    } else {
                        photoPickerLauncher.launch(new PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                                .build());
                    }
                })
                .setOnCancelListener(dialog -> pendingAdminName = null)
                .show();
    }

    private void enrollAdminFromPhoto(Uri uri) {
        String name = pendingAdminName;
        if (cameraExecutor == null || cameraExecutor.isShutdown()) return;
        updateAdminBadge("正在分析“" + name + "”的注册照片…");
        cameraExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    bitmap = ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(getContentResolver(), uri),
                            (decoder, info, source) -> decoder.setAllocator(
                                    ImageDecoder.ALLOCATOR_SOFTWARE));
                } else {
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        bitmap = BitmapFactory.decodeStream(input);
                    }
                }
                if (bitmap == null) throw new IllegalArgumentException("无法读取所选照片");
                List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> landmarks;
                try (EnrollmentFaceDetector detector =
                             new EnrollmentFaceDetector(getApplicationContext())) {
                    landmarks = detector.detectSingle(bitmap);
                }
                FaceRecognitionEngine recognitionEngine = faceRecognitionEngine;
                if (recognitionEngine == null) throw new IllegalStateException("主人识别模型不可用");
                recognitionEngine.enroll(bitmap, landmarks, name, this);
            } catch (Exception error) {
                onEnrollmentError(error.getMessage() == null ? "注册照片处理失败" : error.getMessage());
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
        });
    }

    private void showAdminManagement() {
        FaceRecognitionEngine recognitionEngine = faceRecognitionEngine;
        if (recognitionEngine == null) return;
        List<AdminFaceStore.Record> admins = recognitionEngine.admins();
        if (admins.isEmpty()) {
            Toast.makeText(this, "还没有设置主人", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] options = new String[admins.size() + 1];
        for (int index = 0; index < admins.size(); index++) {
            options[index] = "删除：" + admins.get(index).name;
        }
        options[admins.size()] = "清除全部主人";
        new AlertDialog.Builder(this)
                .setTitle("管理主人")
                .setItems(options, (dialog, which) -> {
                    if (which == admins.size()) confirmClearAdmins();
                    else confirmDeleteAdmin(admins.get(which));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteAdmin(AdminFaceStore.Record admin) {
        new AlertDialog.Builder(this)
                .setTitle("删除主人")
                .setMessage("确定删除“" + admin.name + "”吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    try {
                        faceRecognitionEngine.delete(admin.id);
                        overlayView.setIdentities(java.util.Collections.emptyMap());
                        updateAdminBadge("已删除“" + admin.name + "”");
                    } catch (Exception error) {
                        updateAdminBadge("删除失败：" + error.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearAdmins() {
        new AlertDialog.Builder(this)
                .setTitle("清除全部主人")
                .setMessage("此操作会删除本机保存的全部主人特征，且无法恢复。")
                .setPositiveButton("全部清除", (dialog, which) -> {
                    try {
                        faceRecognitionEngine.clearAdmins();
                        overlayView.setIdentities(java.util.Collections.emptyMap());
                        updateAdminBadge("未设置主人 · 点击添加");
                    } catch (Exception error) {
                        updateAdminBadge("清除失败：" + error.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateAdminBadge(String message) {
        updateAdminBadge(message, false);
    }

    private void updateAdminBadge(String message, boolean recognizedAdmin) {
        if (adminBadge == null) return;
        int count = faceRecognitionEngine == null ? 0 : faceRecognitionEngine.adminCount();
        String backend = faceRecognitionEngine == null
                ? "不可用" : faceRecognitionEngine.backendName();
        adminBadge.setText("主人识别[" + backend + "]（" + count + "）：" + message);
        adminBadge.setTextColor(recognizedAdmin
                ? Color.rgb(254, 243, 199)
                : Color.rgb(191, 219, 254));
        adminBadge.setBackground(roundedBackground(
                recognizedAdmin
                        ? Color.argb(145, 146, 64, 14)
                        : Color.argb(105, 30, 58, 138),
                dp(14),
                recognizedAdmin
                        ? Color.argb(180, 251, 191, 36)
                        : Color.argb(85, 147, 197, 253),
                dp(1)));
    }

    private void updateModeButton() {
        if (modeButton == null) return;
        boolean single = detectionMode == DetectionMode.SINGLE;
        modeButton.setText(single ? "⚡  单人" : "◆  多人");
        modeButton.setBackground(roundedBackground(
                single ? Color.argb(235, 13, 148, 136) : Color.argb(235, 37, 99, 235),
                dp(22),
                Color.argb(150, 255, 255, 255),
                dp(1)));
    }

    private void updateBackendButton() {
        if (backendButton == null) return;
        FaceLandmarkerEngine current = engine;
        if (current != null && current.usesRknn()) {
            backendButton.setText("NPU");
            backendButton.setBackground(roundedBackground(
                    Color.argb(235, 180, 83, 9),
                    dp(22),
                    Color.rgb(253, 224, 71),
                    dp(1)));
            return;
        }
        boolean gpu = inferenceBackend == InferenceBackend.GPU;
        backendButton.setText(gpu ? "GPU" : "CPU");
        backendButton.setBackground(roundedBackground(
                gpu ? Color.argb(235, 126, 34, 206) : Color.argb(225, 30, 41, 59),
                dp(22),
                gpu ? Color.rgb(216, 180, 254) : Color.argb(130, 148, 163, 184),
                dp(1)));
    }

    private String effectiveFaceBackendName(InferenceBackend fallback) {
        FaceLandmarkerEngine current = engine;
        return current == null ? fallback.displayName : current.backendName();
    }

    private void applyStatus(String title, int accent, String metrics, String helper) {
        statusDot.setTextColor(accent);
        statusText.setText(title);
        statusText.setTextColor(Color.WHITE);
        metricsText.setText(metrics);
        helperText.setText(helper);
    }

    private void updateMouthState(MultiFaceAnalyzer.FaceReading target) {
        if (!target.isReady) {
            setMouthStateBadge(
                    "采样 " + target.sampleCount + "/" + target.minimumSamples,
                    Color.rgb(253, 224, 71),
                    Color.argb(155, 113, 63, 18));
            return;
        }
        if (target.isMoving) {
            setMouthStateBadge(
                    "嘴巴：运动中",
                    COLOR_SUCCESS,
                    Color.argb(165, 20, 83, 45));
            return;
        }
        if (target.activityScore >= 1f) {
            setMouthStateBadge(
                    "嘴巴：确认中",
                    Color.rgb(253, 224, 71),
                    Color.argb(155, 113, 63, 18));
            return;
        }
        int activityPercent = Math.round(target.activityScore * 100f);
        setMouthStateBadge(
                "嘴巴：静止 " + activityPercent + "%",
                COLOR_PRIMARY,
                Color.argb(155, 17, 94, 89));
    }

    private void setMouthStateBadge(String text, int textColor, int fillColor) {
        if (mouthStateBadge == null) return;
        mouthStateBadge.setText(text);
        mouthStateBadge.setTextColor(textColor);
        mouthStateBadge.setBackground(roundedBackground(
                withAlpha(fillColor, 105),
                dp(14),
                Color.argb(75, 255, 255, 255),
                dp(1)));
    }

    private void updateAttributeBadgeWaiting() {
        if (attributeBadge == null) return;
        if (!attributeDetectionEnabled) {
            setAttributeBadge(
                    "外观年龄/性别：已关闭 · 点击开启",
                    COLOR_TEXT_MUTED,
                    Color.argb(150, 30, 41, 59));
        } else if (ageGenderEngine == null) {
            setAttributeBadge(
                    "外观年龄/性别：正在准备研究模型…",
                    Color.rgb(253, 224, 71),
                    Color.argb(155, 113, 63, 18));
        } else {
            setAttributeBadge(
                    "外观年龄/性别：等待人脸 · 点击关闭",
                    Color.rgb(216, 180, 254),
                    Color.argb(155, 88, 28, 135));
        }
    }

    private void setAttributeBadge(String text, int textColor, int fillColor) {
        if (attributeBadge == null) return;
        attributeBadge.setText(text);
        attributeBadge.setTextColor(textColor);
        attributeBadge.setBackground(roundedBackground(
                withAlpha(fillColor, 105),
                dp(14),
                Color.argb(75, 255, 255, 255),
                dp(1)));
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(
                Math.max(0, Math.min(255, alpha)),
                Color.red(color),
                Color.green(color),
                Color.blue(color));
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            setMouthStateBadge(
                    "嘴巴：不可用",
                    COLOR_ERROR,
                    Color.argb(155, 127, 29, 29));
            applyStatus(
                    "视觉服务暂不可用",
                    COLOR_ERROR,
                    message,
                    "请重新打开应用或检查摄像头权限");
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.execute(() -> {
            if (engine != null) engine.close();
            if (ageGenderEngine != null) ageGenderEngine.close();
            if (faceRecognitionEngine != null) faceRecognitionEngine.close();
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
