# LipMotion RK3576 / RK3588 Android Library

`lipmotionlib` 是面向 RK3576、RK3588（arm64-v8a）的嘴唇运动检测 AAR。它直接接收 Android Camera1 的 NV21 数据，使用 RKNN Runtime 在 NPU 上执行人脸检测和 478 点人脸关键点推理，再通过几何时序算法输出嘴唇运动状态。库会读取 `ro.board.platform`，自动选择与芯片匹配的 RKNN 模型。

## AAR 内置内容

| 文件 | 用途 |
|---|---|
| `face_detector_rk3576_fp16.rknn` | 在完整摄像头画面中检测人脸 |
| `face_landmarks_rk3576_fp16.rknn` | 对人脸区域输出 478 个关键点，包括嘴唇关键点 |
| `face_detector_rk3588_fp16.rknn` | RK3588 人脸检测模型 |
| `face_landmarks_rk3588_fp16.rknn` | RK3588 478 点人脸关键点模型 |
| `liblipmotion_rknn.so` | 本库的 JNI 推理桥接 |
| `librknnrt.so` | Rockchip RKNN Runtime |

库不再包含或依赖 MediaPipe `face_landmarker.task`，也不提供 CPU/GPU 回退。如果 RKNN Runtime 或模型初始化失败，构造 `LipMotionDetector` 时会直接报告错误，避免误以为正在使用 NPU。

## 能力

- 直接接收 Camera1 `NV21 byte[]`。
- 在内部单工作线程执行 RK3576/RK3588 NPU 推理。
- 提交时立即复制 NV21；返回后可安全调用 `Camera.addCallbackBuffer(data)`。
- 只保留最新待处理帧，推理慢于摄像头帧率时不会无限排队。
- 输出 `NO_FACE`、`SAMPLING`、`STILL`、`MOVING`。
- 可复用已有的 `xxFaceView extends View`，只追加黄色内外嘴唇轮廓。
- 不绘制人脸框；原有人脸框是否显示仍由业务 View 控制。

## 集成 AAR

把 AAR 放到同事项目的 `app/libs/`：

```gradle
dependencies {
implementation files('libs/LipMotionLib-RK3576-RK3588-v1.1.0.aar')
}
```

应用必须包含 `arm64-v8a`：

```gradle
android {
    defaultConfig {
        ndk {
            abiFilters 'arm64-v8a'
        }
    }
}
```

不需要再添加 MediaPipe `tasks-vision` 依赖，也不需要单独复制 RKNN 模型或 `.so` 文件。

## 初始化并复用 xxFaceView

```java
LipMotionConfig config = LipMotionConfig.builder()
        .setMaximumFaces(1)
        .setOverlayScaleType(LipMotionConfig.OverlayScaleType.CENTER_CROP)
        .setFaceLostGraceMs(400) // 默认值；短暂丢脸时保持ASR门控
        .setDebugLogging(true) // 联调时开启，正式发布可删除
        .build();

LipMotionDetector detector = new LipMotionDetector(
        getApplicationContext(),
        config,
        new LipMotionListener() {
            @Override
            public void onLipMotionResult(LipMotionResult result) {
                // 回调位于主线程。
                boolean moving = result.isMoving();
                LipMotionState state = result.getState();
            }

            @Override
            public void onLipMotionError(Throwable error) {
                Log.e("LipMotion", "检测失败", error);
            }
        });

View xxFaceView = findViewById(R.id.xxFaceView);
detector.setOverlayView(xxFaceView);
```

业务侧可直接用 `result.isMoving()` 控制 ASR。库内部已经做了多帧嘴唇运动平滑，并在运动过程中偶发丢脸时默认保持最多 `400ms`：

```java
boolean allowAsr = result.isMoving();
```

短暂丢脸保持期间，`state` 为 `FACE_LOST_HOLD`、`isMoving()` 为 `true`、`isFaceDetected()` 为 `false`，黄色嘴唇轮廓会被清除，不会停留在上一帧。持续丢脸超过保护时间后，状态转为 `NO_FACE` 且 `isMoving()` 返回 `false`。设置 `.setFaceLostGraceMs(0)` 可以关闭这一保护；允许范围为 `0～2000ms`。

库使用 `ViewOverlay` 在 `xxFaceView` 最上层绘制嘴唇，不清空 Canvas，也不要求修改 `xxFaceView.onDraw()`。

### RKNN 诊断日志

联调时设置 `.setDebugLogging(true)` 后，可过滤：

```bash
adb logcat -s LipMotionRKNN:I
```

库在初始化时记录一条日志，之后最多每两秒记录一条帧诊断，例如：

```text
NV21=1920x1080, bytes=3110400, rotation=90, mirrored=false,
transformed=1080x1920, faces=1, detectorMax=0.812,
candidates=3, selected=1, landmarks=1, latencyMs=86
```

| 字段 | 含义 |
|---|---|
| `NV21/bytes` | 业务传入的原始宽高和缓冲区长度 |
| `rotation/mirrored` | 当前实际使用的旋转与镜像参数 |
| `transformed` | 旋转、镜像后的推理图像尺寸 |
| `detectorMax` | 当前各检测区域中的最高人脸置信度 |
| `candidates` | 超过检测阈值的原始候选数量 |
| `selected` | 去重后的候选人脸数量 |
| `landmarks` | 成功生成 478 点关键点的人脸数量 |
| `faces` | 最终返回给嘴唇算法的人脸数量 |
| `latencyMs` | 当前帧转换和 NPU 推理总耗时 |

如果 `detectorMax` 长期低于约 `0.55`，通常是人脸太小、模糊、逆光或方向仍不正确；如果 `selected>0` 但 `landmarks=0`，说明检测到了人脸框，但关键点阶段没有通过人脸存在度判断。

如果业务希望自己控制绘制顺序，也可保存最新的 `LipMotionResult`，并在 `xxFaceView.onDraw()` 中调用：

```java
lipRenderer.draw(canvas, latestLipResult, getWidth(), getHeight(),
        getResources().getDisplayMetrics().density);
```

## Camera1 NV21 接入

```java
Camera.Size size = camera.getParameters().getPreviewSize();
int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
int frameRotation = Camera1Transform.frameRotationDegrees(cameraId, displayRotation);
boolean mirrored = Camera1Transform.isMirrored(cameraId);

int bufferSize = size.width * size.height * 3 / 2;
camera.addCallbackBuffer(new byte[bufferSize]);
camera.addCallbackBuffer(new byte[bufferSize]);
camera.setPreviewCallbackWithBuffer((data, sourceCamera) -> {
    try {
        detector.submitNv21(
                data,
                size.width,
                size.height,
                frameRotation,
                mirrored,
                SystemClock.uptimeMillis());
    } finally {
        sourceCamera.addCallbackBuffer(data);
    }
});
```

### submitNv21 参数说明

库提供两个重载接口：

```java
boolean submitNv21(
        byte[] nv21,
        int width,
        int height,
        int rotationDegrees,
        boolean mirrored);

boolean submitNv21(
        byte[] nv21,
        int width,
        int height,
        int rotationDegrees,
        boolean mirrored,
        long timestampMs);
```

| 参数 | 含义 | 传值要求 |
|---|---|---|
| `nv21` | Camera1 当前预览帧的原始 NV21 数据 | 直接传入 `PreviewCallback` 返回的 `data`；最小长度必须是 `width × height × 3 ÷ 2` |
| `width` | NV21 原始图像宽度 | 使用 `camera.getParameters().getPreviewSize().width`；必须是正偶数，不是屏幕宽度或 View 宽度 |
| `height` | NV21 原始图像高度 | 使用 `camera.getParameters().getPreviewSize().height`；必须是正偶数，不是屏幕高度或 View 高度 |
| `rotationDegrees` | 把原始 NV21 画面顺时针旋转多少度后才是正向人脸 | 只能传 `0`、`90`、`180` 或 `270`；建议通过 `Camera1Transform.frameRotationDegrees(cameraId, displayRotation)` 计算 |
| `mirrored` | 推理画面和嘴唇坐标是否需要水平镜像 | 前置摄像头预览通常传 `true`，后置通常传 `false`；建议通过 `Camera1Transform.isMirrored(cameraId)` 计算 |
| `timestampMs` | 当前帧的单调递增时间戳，单位毫秒 | 建议传 `SystemClock.uptimeMillis()`；不能使用会因系统校时而跳变的普通日期时间 |

不传 `timestampMs` 的五参数版本，会由库内部自动使用 `SystemClock.uptimeMillis()`：

```java
detector.submitNv21(data, width, height, frameRotation, mirrored);
```

返回值含义：

- 返回 `true`：帧已被库接收。库会立即复制 NV21 数据，因此方法返回后可马上把 `data` 归还给 Camera1。
- 返回 `false`：检测器已经执行过 `close()`，不会再接收数据。
- 参数不合法时会抛出 `IllegalArgumentException`，例如宽高不是正偶数、数据长度不足或旋转角度不是四个允许值之一。

为了防止摄像头回调线程被阻塞，库只保留最新的待处理帧。返回 `true` 表示数据已接收，不保证每一帧都会执行 NPU 推理；当摄像头帧率高于推理速度时，中间旧帧会被自动丢弃。

### RK3576 测试设备 192.168.46.134

该设备实测信息为：屏幕当前方向 `ROTATION_0`、摄像头 Facing 为 Back、Sensor Orientation 为 `90°`，现有 Demo 预览使用与 `FILL_CENTER` 等价的全屏填充裁剪。因此在设备保持当前竖屏方向、继续使用同一个摄像头时推荐：

```java
LipMotionConfig config = LipMotionConfig.builder()
        .setOverlayScaleType(LipMotionConfig.OverlayScaleType.CENTER_CROP)
        .build();

detector.submitNv21(
        data,
        previewSize.width,
        previewSize.height,
        90,       // rotationDegrees
        false);   // mirrored：后置/外接摄像头不镜像
```

更稳妥的正式写法是不硬编码 `90`，而是使用实际传给 `Camera.open(cameraId)` 的 Camera1 ID 动态计算：

```java
int displayRotation = activity.getWindowManager()
        .getDefaultDisplay()
        .getRotation();
int frameRotation = Camera1Transform.frameRotationDegrees(
        cameraId, displayRotation);
boolean mirrored = Camera1Transform.isMirrored(cameraId);

detector.submitNv21(
        data,
        previewSize.width,
        previewSize.height,
        frameRotation,
        mirrored);
```

如果设备旋转为横屏、改用另一个摄像头或摄像头 HAL 信息变化，应以动态计算结果为准，不能继续固定传 `90`。

### RK3576 测试设备 192.168.46.236

该设备实测有两块显示屏，但 `com.neldtv.mips` 当前运行在内置 Display 0：物理分辨率 `1080×1920`、当前方向 `ROTATION_0`。正在使用的摄像头 Facing 为 Back、Sensor Orientation 为 `270°`，因此当前配置推荐：

```java
LipMotionConfig config = LipMotionConfig.builder()
        .setOverlayScaleType(LipMotionConfig.OverlayScaleType.CENTER_CROP)
        .build();

detector.submitNv21(
        data,
        previewSize.width,
        previewSize.height,
        270,      // rotationDegrees
        false);  // mirrored
```

摄像头支持 `1920×1080`，当前服务中还能看到实际 `1280×720` 流。调用库时不能根据屏幕尺寸或这里记录的数值猜测 NV21 宽高，必须使用同一次 Camera1 会话的：

```java
Camera.Size previewSize = camera.getParameters().getPreviewSize();
```

这两个预览尺寸都是 16:9，旋转后为 9:16，与内屏 `1080×1920` 比例一致。若摄像头预览和 `xxFaceView` 都是全屏同区域，`CENTER_CROP` 不会产生明显额外裁剪。如果 `xxFaceView` 只是页面左上角的小窗口，则仍要看该小窗口如何显示摄像头：等比例铺满用 `CENTER_CROP`，强制拉伸用 `STRETCH`。

该设备还连接了 HDMI Display 2。若以后把 Activity 移到 HDMI 屏，必须从 Activity 当前所属 Display 获取旋转值并动态调用 `Camera1Transform`，不要继续硬编码 `270`。

### OverlayScaleType 说明

`OverlayScaleType` 只控制“归一化嘴唇坐标如何映射到 xxFaceView”，不会改变 RKNN 模型推理结果。它有三个值：

| 值 | 映射方式 | 适用预览效果 |
|---|---|---|
| `CENTER_CROP` | 等比例放大到完全铺满 View，超出 View 的部分从四周居中裁掉 | Camera 画面铺满屏幕、没有黑边；对应 CameraX `FILL_CENTER`，也是 192.168.46.134 当前推荐值 |
| `FIT_CENTER` | 等比例缩小，让完整画面都显示在 View 内，剩余区域会留黑边或空白 | Camera 画面完整可见，允许上下或左右留边 |
| `STRETCH` | 宽和高分别缩放，强制拉满 View，不保持原始宽高比 | 业务预览本身被横向或纵向拉伸；人物可能看起来变宽或变高 |

选择原则是必须与 Camera 预览的实际显示方式相同，否则 NPU 判断仍然正常，但黄色嘴唇轮廓会与画面中的嘴巴错位。

如果黄色嘴唇和预览画面位置不一致，检查 `rotationDegrees`、`mirrored` 以及 `CENTER_CROP/FIT_CENTER/STRETCH` 是否与业务预览一致。

## 生命周期

```java
@Override
protected void onDestroy() {
    if (detector != null) {
        detector.setOverlayView(null);
        detector.close();
        detector = null;
    }
    super.onDestroy();
}
```

切换摄像头、切换识别对象或长时间停止送帧时调用：

```java
detector.reset();
```

## 构建

```bash
./gradlew :lipmotionlib:assembleRelease
```

产物：`lipmotionlib/build/outputs/aar/lipmotionlib-release.aar`

## 注意

- 当前内置模型支持 RK3576 和 RK3588；其他 RK 芯片仍需转换对应模型并扩展平台选择逻辑。
- AAR 使用 JDK 8 编译为 Java 7 字节码，不包含 Lambda、方法引用或接口默认方法，可供旧版 Android Gradle Plugin/D8 项目接入。
- 后续支持其他 RK 芯片时，需要为目标芯片转换对应的两个 RKNN 模型，并按芯片选择模型资源。
- 这是嘴唇运动判断，不是语音识别，也不能单独证明画面中的人在发声。
- `LipMotionDetector` 应长期复用，不能每帧创建。
