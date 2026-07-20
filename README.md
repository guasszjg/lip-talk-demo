# Note9 端侧嘴部运动与多人目标选择 Demo

这是一个使用 Java 编写的 Android 端侧视觉验证项目，运行在三星 Note9（Android 10）上。项目通过 CameraX 获取前置摄像头画面，使用 Google MediaPipe Face Landmarker 提取人脸关键点，再通过自研的嘴部几何特征、连续帧统计和多人目标选择逻辑，判断画面中的目标是否存在持续嘴部运动。

项目当前定位是：

> 验证“视觉嘴部运动检测”能否作为智能广告机语音交互入口的一部分。

它不是唇语识别模型，也不能单独证明一个人正在发声。要达到商用的“正在讲话”判断，需要继续加入音频 VAD、回声消除，必要时加入麦克风阵列声源定位。

## 1. 当前能力

- CameraX 前置摄像头实时预览。
- MediaPipe 478 点三维人脸关键点检测。
- 单人高性能、多人检测两种运行模式。
- 右上角按钮可实时切换模式，并记住上次选择。
- 使用系统 WindowInsets 处理状态栏和导航栏安全区，底部信息不会被系统导航栏遮挡。
- 使用边缘相机画面、渐变遮罩和半透明圆角状态卡呈现实时结果。
- 多人模式最多同时检测 4 张脸。
- 为每张脸建立短时跟踪编号，但不做人脸身份识别。
- 为每张脸独立计算嘴部开合度和连续运动量。
- 无明显嘴部运动时，优先选择面积较大、靠近画面中心的人。
- 出现持续嘴部运动时，临时锁定对应目标，减少多人场景中目标跳动。
- 在画面上绘制人脸轮廓、嘴唇轮廓、目标编号和实时延迟。
- 摄像头数据在设备本地处理，不需要把视频上传到业务服务器。
- APK 仅打包 `arm64-v8a`，适用于 Note9，也与 RK3576 的 ARM64 架构一致。

## 2. 这个项目不具备的能力

当前版本不包含以下能力：

- 不识别人的身份，不保存人脸特征向量。
- 不识别用户说了什么，不是唇语识别。
- 不根据嘴型还原语音内容。
- 不区分说话、打哈欠、吃东西、咀嚼和夸张微笑。
- 不使用麦克风，因此无法确认嘴动时是否真的有声音。
- 多人编号仅在短时间、相邻帧内有效，离开画面后重新进入可能获得新编号。
- 不适用于安防、医疗、生命安全或身份认证决策。

产品界面中应使用“嘴部正在运动”或“疑似讲话”，不要把纯视觉结果直接表述为“确认正在讲话”。

## 3. 整体架构

```text
CameraX 前置摄像头
        │
        │ RGBA 视频帧，保留最新帧
        ▼
MediaPipe Face Landmarker
        │
        ├── 人脸检测
        ├── 人脸跟踪
        └── 每张脸 478 个三维关键点
                 │
                 ▼
        MultiFaceAnalyzer
                 │
                 ├── 人脸短时编号
                 ├── 嘴部归一化开合度
                 ├── 12 帧运动统计
                 ├── 嘴部运动滞回判断
                 └── 主目标选择与锁定
                         │
                         ├── FaceOverlayView 可视化
                         └── 后续接入 VAD / ASR / Agent
```

摄像头分析使用 `STRATEGY_KEEP_ONLY_LATEST`。当模型处理速度低于摄像头帧率时，旧帧会被丢弃，避免队列持续堆积导致延迟越来越大。

## 4. 技术栈与版本

| 组件                     | 当前配置                | 用途                          |
| ---------------------- | -------------------:| --------------------------- |
| Java                   | 17                  | Android 业务与算法代码             |
| Android minSdk         | 26                  | 最低 Android 8.0              |
| Android targetSdk      | 35                  | 应用目标版本                      |
| CameraX                | 1.4.2               | 摄像头预览和逐帧分析                  |
| MediaPipe Tasks Vision | 0.10.29             | Face Landmarker Android API |
| Face Landmarker 模型     | float16 / version 1 | 人脸检测和 478 点关键点              |
| ABI                    | arm64-v8a           | Note9、RK3576 ARM64          |
| 推理 Delegate            | CPU                 | 当前 Note9 Demo 使用 CPU        |

项目中的 Android 业务代码全部为 Java。`build.gradle.kts` 和 `settings.gradle.kts` 是 Gradle 构建配置，不是 Kotlin 应用源码。

## 5. 模型来源

### 5.1 官方来源

模型文件位于：

```text
app/src/main/assets/face_landmarker.task
```

本项目使用的下载地址：

```text
https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
```

文件信息：

| 属性      | 值                                                                  |
| ------- | ------------------------------------------------------------------ |
| 文件大小    | 3,758,596 bytes                                                    |
| SHA-256 | `64184E229B263107BC2B804C6625DB1341FF2BB731874B0BCC2FE6544E0BC9FF` |

官方资料：

- [MediaPipe Face Landmarker 概览](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker)
- [Android 接入指南](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android)
- [MediaPipe 官方仓库](https://github.com/google-ai-edge/mediapipe)
- [MediaPipe Android 示例仓库](https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/face_landmarker/android)

### 5.2 模型包内部组成

根据 Google 官方说明，Face Landmarker 模型包由多个模型组成：

1. 人脸检测模型：先定位画面中的人脸和少量粗关键点。
2. FaceMesh-V2：对裁剪后的人脸预测 478 个三维关键点。
3. Blendshape 模型：根据关键点预测 52 个表情系数。

官方文档列出的模型输入规格为：

| 内部模型          | 输入规格        | 输出         |
| ------------- | -----------:| ---------- |
| Face Detector | 192 × 192   | 人脸位置和粗关键点  |
| FaceMesh-V2   | 256 × 256   | 478 个三维关键点 |
| Blendshape    | 1 × 146 × 2 | 52 个表情系数   |

官方说明中，人脸检测部分采用面向移动端的 BlazeFace short-range；FaceMesh-V2 是类似 MobileNetV2 的轻量卷积网络。参考：

- [BlazeFace Short Range 模型卡](https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20%28Short%20Range%29.pdf)
- [FaceMesh-V2 模型卡](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Face%20Mesh%20V2.pdf)
- [Blendshape-V2 模型卡](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Blendshape%20V2.pdf)

本项目没有启用 Blendshape 和人脸变换矩阵输出，只读取人脸关键点，以降低不必要的数据处理。模型包内仍包含这些子模型。

### 5.3 模型与代码许可

MediaPipe 官方代码仓库、官方示例和上述 BlazeFace、FaceMesh-V2 模型卡标注为 Apache License 2.0。模型文件、依赖库、示例代码和本项目自有代码属于不同交付物，正式商用前仍应由公司法务或开源合规人员核对当时版本的许可证、NOTICE、隐私声明和分发要求。

当前项目自身尚未添加公司级开源许可证，不应仅凭本 README 推断项目代码可以对外分发。

Google 当前的 MediaPipe 隐私说明表示，任务输入在设备端处理，不会把输入图像发送给 Google；同时也提示 Tasks API 可能发送性能和使用情况指标。产品发布前应针对实际依赖版本检查隐私声明、网络行为、用户告知和同意要求。参考：[MediaPipe 官方仓库 Privacy Notice](https://github.com/google-ai-edge/mediapipe#privacy-notice)。

当前依赖固定为 `tasks-vision:0.10.29` 以保证 Demo 可复现，并不代表这是长期产品应使用的最终版本。升级依赖或模型前需要重新做精度、性能、ABI、隐私和回归测试。官方目前仍把 Face Landmarker 标记为 Preview，接口与行为存在演进可能。

## 6. MediaPipe 推理配置

`FaceLandmarkerEngine.java` 当前使用：

```java
.setDelegate(Delegate.CPU)
.setRunningMode(RunningMode.LIVE_STREAM)
.setNumFaces(mode.maximumFaces)
.setMinFaceDetectionConfidence(0.5f)
.setMinFacePresenceConfidence(0.5f)
.setMinTrackingConfidence(0.5f)
```

`LIVE_STREAM` 模式异步返回结果，并利用视频跟踪减少每帧重复运行完整人脸检测的开销。

Google 官方文档指出，时间平滑只在 `numFaces = 1` 时启用。因此：

- 单人模式的关键点通常更稳定，延迟也更低。
- 多人模式需要更严格的嘴动阈值，避免关键点抖动造成误触发。

## 7. 嘴部运动算法

当前“嘴动算法”不是一个单独训练的神经网络，而是基于 FaceMesh 关键点的时序规则算法。

### 7.1 使用的关键点

| 关键点编号 | 含义    |
| -----:| ----- |
| 13    | 上内唇   |
| 14    | 下内唇   |
| 78    | 左嘴角附近 |
| 308   | 右嘴角附近 |

### 7.2 归一化嘴巴开合度

```text
mouthHeight = distance(point13, point14)
mouthWidth  = distance(point78, point308)

openness = mouthHeight / mouthWidth
```

除以嘴巴宽度的目的，是降低用户距离摄像头远近、画面分辨率和脸部大小变化的影响。这里使用二维 `x/y` 距离；目前没有使用 `z` 坐标。

### 7.3 连续帧运动量

每张脸维护最近 12 个开合度样本，并计算：

```text
movement = 最近 12 帧开合度的标准差
range    = 最大开合度 - 最小开合度
```

只判断某一帧嘴巴张开是不够的，因为张嘴静止、打哈欠和表情也会造成较大开合度。当前算法更关注连续帧是否发生变化。

### 7.4 当前阈值

| 模式  | 标准差阈值 | 极差阈值  | 原因                           |
| --- | -----:| -----:| ---------------------------- |
| 单人  | 0.018 | 0.055 | MediaPipe 单人模式有时间平滑，可使用较灵敏阈值 |
| 多人  | 0.024 | 0.070 | 多人关键点抖动更大，需要降低误触发            |

窗口至少积累 6 帧才开始判断。连续 2 帧满足运动条件后进入“嘴部运动”状态；连续 5 帧不满足后才退出。这种进入/退出使用不同持续时间的方式属于滞回，可以减少界面在临界阈值附近频繁闪烁。

所有阈值都是当前 Demo 的工程初值，不是通用真值。正式产品应使用你们真实广告机场景验证集重新标定。

## 8. 单人和多人模式

### 8.1 单人高性能模式

```java
DetectionMode.SINGLE
```

- `numFaces = 1`。
- MediaPipe 对关键点启用时间平滑。
- 适合广告机划定站位区、一次主要服务一名用户的场景。
- Note9 本次开发环境观察到的单帧延迟约 44 ms。

### 8.2 多人检测模式

```java
DetectionMode.MULTI
```

- `numFaces = 4`。
- 每张脸有独立的嘴动时间窗口。
- 支持短时编号、主目标选择和嘴动目标锁定。
- Note9 本次开发环境观察到的单帧延迟约 110 ms。

44 ms 和 110 ms 只是单台 Note9、当前画面和一次运行中的观察值，不是严格基准测试结果。正式性能报告应记录预热后至少数分钟的 P50、P95、FPS、CPU、内存、温度和功耗。

### 8.3 模式切换

用户可点击画面右上角按钮切换模式，选择会存入 `SharedPreferences`。首次安装默认使用单人模式。

`MainActivity` 实现了 `DetectionModeController`：

```java
controller.setDetectionMode(DetectionMode.SINGLE); // 单人、低延迟
controller.setDetectionMode(DetectionMode.MULTI);  // 最多 4 人
DetectionMode current = controller.getDetectionMode();
```

模式切换会重建 Face Landmarker，因为最大人脸数是模型初始化配置。摄像头预览不需要重启。

## 9. 多人跟踪和目标选择算法

当前多人跟踪不提取身份特征，只根据相邻帧的人脸中心位置进行匹配。

### 9.1 短时跟踪

- 新出现的人脸分配递增编号。
- 当前帧人脸与已有轨迹进行最近中心点匹配。
- 匹配半径会根据人脸面积动态调整。
- 连续 10 个处理帧未出现的轨迹会被删除。
- 快速交叉、严重遮挡或离开后重入时，编号可能交换。

### 9.2 无嘴部运动时的主目标

```text
presenceScore = 0.72 × 相对人脸面积 + 0.28 × 靠近画面中心程度
```

面积更大近似表示距离设备更近；靠近中心表示更可能站在广告机交互区域。为避免两张脸大小接近时频繁切换，旧目标在得分差小于 0.12 时继续保留。

### 9.3 出现嘴部运动时

```text
speakerScore = 0.45 × presenceScore + 0.55 × 嘴部运动分数
```

满足持续嘴动条件的轨迹会额外加分。选中嘴动目标后锁定约 1.2 秒，短暂停顿不会马上切换到其他人。

这里的 `speakerScore` 只是“视觉说话人候选分数”，不能代替声音证据。

## 10. 代码结构

```text
app/src/main/java/com/zuicun/liptalkdemo/
├── MainActivity.java
│   ├── 构建界面
│   ├── CameraX 生命周期
│   ├── 模式按钮
│   └── 显示检测结果
├── FaceLandmarkerEngine.java
│   ├── 加载 face_landmarker.task
│   ├── 配置单人/多人模式
│   └── 异步执行 MediaPipe
├── DetectionMode.java
│   └── SINGLE / MULTI 配置
├── DetectionModeController.java
│   └── 业务代码模式切换接口
├── MultiFaceAnalyzer.java
│   ├── 多人短时跟踪
│   ├── 每人嘴动统计
│   └── 主目标选择与锁定
├── MouthMotionTracker.java
│   ├── 12 帧滑动窗口
│   ├── 标准差和极差
│   └── 进入/退出滞回
├── MouthAnalyzer.java
│   └── 单张脸嘴部几何计算的基础封装
└── FaceOverlayView.java
    └── 人脸、嘴唇、编号和目标状态绘制
```

测试代码：

```text
app/src/test/java/com/zuicun/liptalkdemo/
├── MouthMotionTrackerTest.java
└── MultiFaceAnalyzerTest.java
```

自动测试覆盖稳定闭嘴不触发、连续嘴型变化触发、无人嘴动时选择大脸、较小人脸嘴动时抢占目标以及目标短时锁定。

## 11. 编译和安装

### 11.1 Android Studio

1. 使用 Android Studio 打开项目根目录。
2. 等待 Gradle 同步。
3. 在 Note9 中开启“开发者选项”和“USB 调试”。
4. USB 连接手机并允许调试授权。
5. 选择手机后运行 `app`。
6. 首次启动允许摄像头权限。

### 11.2 命令行

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.zuicun.liptalkdemo/.MainActivity
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 12. 是否需要微调或自己训练

### 12.1 当前阶段：不建议微调 Face Landmarker

第一阶段不建议直接微调或重训 Google 的人脸检测、FaceMesh 模型，原因是：

- 当前目标不是提高通用人脸关键点精度，而是判断广告机场景中的讲话活动。
- MediaPipe Face Landmarker 已经适合手机前置摄像头和实时人脸关键点任务。
- 官方 `.task` 模型包面向直接推理使用，不是本项目里可以简单继续训练的 PyTorch 检查点。
- 重训 478 点关键点模型需要大规模、精确的三维或伪三维人脸标注，投入很高。
- 即使关键点更准，也无法从根本上解决“打哈欠或咀嚼被认为说话”的语义问题。

因此更合理的做法是：

> 冻结人脸检测和关键点模型，把关键点当作通用特征，再训练你们自己的轻量级视觉语音活动检测模型。

### 12.2 第一优先级：先调规则，不训练

先采集真实广告机场景数据，建立离线回放和评估工具，然后调整：

- 单人和多人嘴动阈值。
- 滑动窗口长度。
- 进入与退出所需帧数。
- 目标锁定时间。
- 交互区域大小。
- 最小人脸尺寸和距离限制。

如果规则加音频 VAD 已经满足误唤醒和漏检指标，就没有必要为了“使用 AI 模型”而训练模型。

### 12.3 第二优先级：加入音频 VAD

建议在训练视觉模型之前先加入：

```text
视觉嘴动分数 + 麦克风 VAD + 设备播放状态
```

设备自己播放 TTS 或视频声音时，还需要 AEC（回声消除）或播放参考信号，否则麦克风 VAD 会把设备声音当作用户声音。

一个可落地的状态机示例：

```text
无人脸
  └── 不启动 ASR

有人脸，但没有声音和嘴动
  └── 保持待机

有 VAD，且目标嘴部持续运动
  └── 高置信度启动 ASR

只有 VAD，没有目标嘴动
  └── 可能是旁人或环境声，延迟确认

只有嘴动，没有 VAD
  └── 可能是表情、咀嚼或静音说话，不直接启动 ASR
```

### 12.4 什么时候值得训练自己的模型

出现以下情况时，建议训练自有下游模型：

- 规则阈值在不同人、距离、角度和灯光下无法同时兼顾误报与漏报。
- 笑、咀嚼、打哈欠等困难负样本频繁误触发。
- 需要更可靠地区分“嘴在动”和“正在说话”。
- 多人环境中需要选择真正的说话人。
- 需要形成公司可控的数据、模型和指标闭环。

### 12.5 推荐训练目标：下游时序分类器

第一版自有模型可以输入连续 0.5～1.0 秒的嘴部特征，例如 15～30 帧：

```text
每帧特征：
- 上下唇距离
- 左右嘴角距离
- 多组内外唇距离
- 嘴唇关键点坐标
- 一阶速度和二阶加速度
- 头部姿态
- 人脸尺寸

连续帧
   ↓
1D CNN / TCN / GRU / Tiny Transformer
   ↓
speaking / not-speaking
```

这类模型参数量可以控制得很小，便于导出 ONNX，再转换为 RKNN 部署到 RK3576。

如果关键点特征模型无法区分困难动作，再升级为嘴部图像序列模型：

```text
连续嘴部 ROI 图像
   ↓
MobileNetV3 / EfficientNet-Lite
   ↓
TCN / GRU / Tiny Transformer
   ↓
视觉讲话概率
```

不建议第一步就做完整唇语识别。唇语识别需要更大数据量、更复杂时序模型，而且远超“是否正在讲话”的产品需求。

## 13. 数据采集建议

### 13.1 标签

至少标注：

- `speaking`：本人真实发声且嘴部可见。
- `silent`：静止、听别人讲话、自然眨眼。
- `hard_negative`：微笑、咀嚼、喝水、打哈欠、咳嗽、舔嘴唇、无声动嘴。
- `invalid`：严重遮挡、脸太小、背对镜头、画面模糊。

标签最好利用单人近讲麦克风或人工校验的音频时间线生成，而不是仅凭画面主观猜测。

### 13.2 场景覆盖

广告机场景应覆盖：

- 距离：0.5 m、1 m、1.5 m、2 m，必要时更远。
- 姿态：正脸、左右 15°/30°/45°、抬头、低头。
- 光照：背光、暗光、顶光、屏幕反光。
- 人群：单人、两人并排、前后遮挡、路人经过。
- 外观：眼镜、口罩、胡须、帽子及不同年龄段。
- 噪声：商场、大厅、医院、展厅和设备自身播放声音。
- 设备：真实摄像头、镜头高度、安装角度和最终外壳。

### 13.3 数据拆分

必须按“人”拆分训练集、验证集和测试集，不要把同一个人的相邻视频片段随机分到不同集合，否则指标会虚高。最好额外保留一个从未参与调参的真实站点测试集。

工程试验可先从数十小时、数百名参与者开始建立基线；是否扩充到更大规模，应由错误分析和目标指标决定，而不是只追求数据数量。涉及人脸与声音采集时，应取得授权并遵守公司隐私、数据留存和删除制度。

## 14. 评估指标

不要只看逐帧准确率，建议同时评估：

| 指标                      | 说明                |
| ----------------------- | ----------------- |
| Precision / Recall / F1 | 视觉讲话帧分类效果         |
| 误唤醒次数/小时                | 广告机没有被讲话时错误启动 ASR |
| 漏唤醒率                    | 用户讲话但没有启动 ASR     |
| 首字延迟                    | 用户开始讲话到系统确认的时间    |
| 目标切换率                   | 多人场景中说话人编号错误切换频率  |
| P50 / P95 推理延迟          | 不只观察单次延迟          |
| 有效 FPS                  | 每秒真正完成推理的帧数       |
| CPU / 内存 / 温度 / 功耗      | 长时间运行稳定性          |
| 场景分桶指标                  | 不同距离、角度、光照和人群分别统计 |

最终产品指标应直接对应业务，例如“连续运行 8 小时的误唤醒次数”和“用户开口后 300 ms 内启动 ASR 的比例”。

## 15. 推荐技术路线

### 阶段 A：当前 Demo

- MediaPipe Face Landmarker。
- 关键点规则嘴动检测。
- 单人/多人切换。
- Note9 实机验证。

### 阶段 B：广告机试点

- 加入真实交互区域限制。
- 加入音频 VAD 和播放状态。
- 建立日志、视频回放和标注工具。
- 在真实站点统计误唤醒、漏唤醒和延迟。

### 阶段 C：自有视觉讲话模型

- 冻结 MediaPipe 关键点模型。
- 训练小型关键点时序分类器。
- 加入困难负样本挖掘。
- 实现按人、按站点的离线评测。

### 阶段 D：RK3576 产品化

- 评估 MediaPipe ARM CPU 性能是否满足需求。
- 若不满足，替换为可转 RKNN 的人脸检测与关键点模型。
- 人脸检测可评估 SCRFD、轻量 YOLO-Face 等方案。
- 关键点可评估 PFLD、轻量 106 点或自有嘴部关键点模型。
- 将 PyTorch 模型导出 ONNX，通过 RKNN Toolkit2 转换、量化和校准。
- 保留本项目的嘴动统计、目标选择、状态机和业务接口思想。

MediaPipe 的 `.task` 模型包不能直接等同于 RKNN 模型。迁移到 RK3576 NPU 时，通常需要替换推理模型或重新搭建预处理、后处理和跟踪链路，而不是简单修改文件后缀。

## 16. 什么时候需要重训人脸关键点模型

只有在以下问题经过数据证明长期无法解决时，才考虑替换或训练关键点模型：

- 最终摄像头中人脸长期小于现有模型的有效尺寸。
- 安装角度导致大量侧脸、俯视或仰视。
- 特殊行业人群、遮挡或成像方式与手机自拍域差异很大。
- 必须把关键点推理放到 RK NPU，但现有模型格式和算子无法转换。
- 只需要少量嘴部关键点，希望用更小模型换取更高 FPS。

即使需要训练，也建议先从已有可训练的轻量关键点网络迁移学习，而不是从零训练。训练数据需要稳定、统一的关键点标注规范；如果目标只是嘴部活动检测，可以只标注嘴部区域并训练更小的专用模型。

## 17. 已知风险与改进项

- 多人模式比单人模式慢，Note9 上长时间运行还需要测试温升和降频。
- 多人模式没有 MediaPipe 单人时间平滑，关键点抖动更明显。
- 中心点近邻跟踪在多人交叉和遮挡时可能交换编号。
- 当前阈值来自 Demo 调试，没有真实广告机验证集支持。
- 没有音频信息，无法排除无声嘴动和旁人声音。
- 设备播放 TTS 时可能影响未来的 VAD，需要 AEC 或播放参考信号。
- 当前 UI 和分析代码仍在一个 Demo 应用中，产品化时应提取为独立 SDK 或服务。

## 18. 推荐结论

对当前智能广告机需求，建议采用以下决策：

1. 默认使用单人模式，获得更低延迟和更稳定关键点。
2. 保留多人模式作为现场调试和特殊点位能力。
3. 下一步优先加入音频 VAD，而不是立即微调 Face Landmarker。
4. 用真实广告机场景数据标定规则并建立量化指标。
5. 规则加 VAD 仍不达标时，训练自有的轻量嘴部时序分类器。
6. 只有关键点本身成为明确瓶颈时，才替换或训练人脸关键点模型。

这条路线能够先快速验证业务价值，同时逐步积累真正形成技术壁垒的数据、评测体系和下游讲话检测模型。
