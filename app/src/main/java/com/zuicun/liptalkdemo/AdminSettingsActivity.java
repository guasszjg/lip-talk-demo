package com.zuicun.liptalkdemo;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen local administrator enrollment and management UI. */
public final class AdminSettingsActivity extends ComponentActivity
        implements FaceRecognitionEngine.EnrollmentListener {
    private static final int BACKGROUND = Color.rgb(7, 17, 31);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FaceRecognitionEngine engine;
    private LinearLayout listContainer;
    private TextView summary;
    private String pendingName;
    private boolean changed;

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String path = result.getData().getStringExtra(AdminCameraActivity.EXTRA_PHOTO_PATH);
                    if (path != null) processFile(new File(path));
                } else pendingName = null;
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> pickerLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) processUri(uri);
                else pendingName = null;
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildInterface();
        executor.execute(() -> {
            try {
                engine = new FaceRecognitionEngine(getApplicationContext());
                runOnUiThread(this::refreshList);
            } catch (Exception error) {
                runOnUiThread(() -> {
                    summary.setText("管理员模型初始化失败：" + error.getMessage());
                    Toast.makeText(this, "管理员模型不可用", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void buildInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(30), dp(18), dp(24));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(58)));

        TextView back = textButton("‹ 返回", Color.rgb(30, 41, 59));
        back.setOnClickListener(view -> finishWithResult());
        header.addView(back, new LinearLayout.LayoutParams(dp(88), dp(44)));

        TextView title = new TextView(this);
        title.setText("管理员设置");
        title.setTextColor(Color.WHITE);
        title.setTextSize(23f);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView add = textButton("+ 添加", Color.rgb(13, 148, 136));
        add.setOnClickListener(view -> promptName());
        header.addView(add, new LinearLayout.LayoutParams(dp(88), dp(44)));

        summary = new TextView(this);
        summary.setText("正在加载管理员信息…");
        summary.setTextColor(Color.rgb(148, 163, 184));
        summary.setTextSize(13f);
        summary.setPadding(dp(4), dp(10), dp(4), dp(12));
        page.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView privacy = new TextView(this);
        privacy.setText("头像和人脸特征仅保存在本机应用私有目录，并使用系统密钥加密。"
                + " 当前为研究测试功能，不包含活体检测。");
        privacy.setTextColor(Color.rgb(100, 116, 139));
        privacy.setTextSize(11f);
        privacy.setPadding(dp(4), dp(12), dp(4), 0);
        page.addView(privacy, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void refreshList() {
        if (engine == null) return;
        List<AdminFaceStore.Record> admins = engine.admins();
        summary.setText("当前共有 " + admins.size() + " 位管理员");
        listContainer.removeAllViews();
        if (admins.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("尚未设置管理员\n\n点击右上角“添加”，可使用专用拍照界面或相册照片注册。");
            empty.setTextColor(Color.rgb(148, 163, 184));
            empty.setTextSize(16f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(90), dp(24), dp(90));
            listContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (AdminFaceStore.Record admin : admins) addAdminCard(admin);
    }

    private void addAdminCard(AdminFaceStore.Record admin) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(14), dp(12), dp(14));
        card.setBackground(round(Color.rgb(15, 30, 50), dp(18)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(10);
        listContainer.addView(card, cardParams);

        ImageView avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(round(Color.rgb(30, 58, 90), dp(36)));
        if (admin.avatarJpeg.length > 0) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(
                    admin.avatarJpeg, 0, admin.avatarJpeg.length);
            avatar.setImageBitmap(bitmap);
        }
        card.addView(avatar, new LinearLayout.LayoutParams(dp(72), dp(72)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(14), 0, dp(8), 0);
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView name = new TextView(this);
        name.setText(admin.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(19f);
        info.addView(name);

        TextView detail = new TextView(this);
        String time = admin.createdAt == 0L
                ? "旧版本注册"
                : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                        .format(new Date(admin.createdAt));
        detail.setText("注册时间 " + time + "\n本机加密特征 · 512 维");
        detail.setTextColor(Color.rgb(148, 163, 184));
        detail.setTextSize(12f);
        detail.setPadding(0, dp(5), 0, 0);
        info.addView(detail);

        TextView delete = textButton("删除", Color.rgb(127, 29, 29));
        delete.setOnClickListener(view -> confirmDelete(admin));
        card.addView(delete, new LinearLayout.LayoutParams(dp(66), dp(42)));
    }

    private void promptName() {
        if (engine == null) {
            Toast.makeText(this, "模型仍在准备中", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = new EditText(this);
        input.setHint("例如：张三");
        input.setSingleLine(true);
        input.setPadding(dp(20), dp(8), dp(20), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("管理员名称")
                .setView(input)
                .setPositiveButton("下一步", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                    } else {
                        pendingName = name;
                        chooseSource();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseSource() {
        new AlertDialog.Builder(this)
                .setTitle("添加“" + pendingName + "”")
                .setItems(new String[]{"打开专用拍照界面", "从相册选择照片"}, (dialog, which) -> {
                    if (which == 0) {
                        cameraLauncher.launch(new Intent(this, AdminCameraActivity.class));
                    } else {
                        pickerLauncher.launch(new PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                                .build());
                    }
                })
                .setNegativeButton("取消", (dialog, which) -> pendingName = null)
                .show();
    }

    private void processFile(File file) {
        executor.execute(() -> {
            try {
                Bitmap bitmap;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    bitmap = ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(file),
                            (decoder, info, source) ->
                                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
                } else {
                    bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                }
                enrollBitmap(bitmap);
            } catch (Exception error) {
                onEnrollmentError(error.getMessage() == null ? "无法读取拍照结果" : error.getMessage());
            } finally {
                file.delete();
            }
        });
    }

    private void processUri(Uri uri) {
        executor.execute(() -> {
            try {
                Bitmap bitmap;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    bitmap = ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(getContentResolver(), uri),
                            (decoder, info, source) ->
                                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
                } else {
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        bitmap = BitmapFactory.decodeStream(input);
                    }
                }
                enrollBitmap(bitmap);
            } catch (Exception error) {
                onEnrollmentError(error.getMessage() == null ? "无法读取照片" : error.getMessage());
            }
        });
    }

    private void enrollBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            onEnrollmentError("无法读取照片");
            return;
        }
        try {
            runOnUiThread(() -> summary.setText("正在检测并注册“" + pendingName + "”…"));
            List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> landmarks;
            try (EnrollmentFaceDetector detector = new EnrollmentFaceDetector(this)) {
                landmarks = detector.detectSingle(bitmap);
            }
            engine.enroll(bitmap, landmarks, pendingName, this);
            bitmap.recycle();
        } catch (Exception error) {
            if (!bitmap.isRecycled()) bitmap.recycle();
            onEnrollmentError(error.getMessage() == null ? "照片处理失败" : error.getMessage());
        }
    }

    private void confirmDelete(AdminFaceStore.Record admin) {
        new AlertDialog.Builder(this)
                .setTitle("删除管理员")
                .setMessage("确定删除“" + admin.name + "”及其本机头像和人脸特征吗？")
                .setPositiveButton("删除", (dialog, which) -> executor.execute(() -> {
                    try {
                        engine.delete(admin.id);
                        changed = true;
                        runOnUiThread(this::refreshList);
                    } catch (Exception error) {
                        runOnUiThread(() -> Toast.makeText(
                                this, "删除失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }))
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onEnrolled(AdminFaceStore.Record record, int total) {
        pendingName = null;
        changed = true;
        runOnUiThread(() -> {
            Toast.makeText(this, "已添加管理员“" + record.name + "”", Toast.LENGTH_SHORT).show();
            refreshList();
        });
    }

    @Override
    public void onEnrollmentError(String message) {
        pendingName = null;
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            refreshList();
        });
    }

    private void finishWithResult() {
        setResult(changed ? RESULT_OK : RESULT_CANCELED);
        finish();
    }

    @Override
    public void onBackPressed() {
        setResult(changed ? RESULT_OK : RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.execute(() -> {
            if (engine != null) engine.close();
        });
        executor.shutdown();
    }

    private TextView textButton(String text, int color) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14f);
        button.setGravity(Gravity.CENTER);
        button.setBackground(round(color, dp(22)));
        return button;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
