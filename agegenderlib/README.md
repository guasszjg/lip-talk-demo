# AgeGenderLib（RK3588）v1.1.0 接入文档

## v1.1.0 优化内容

- 增加人脸连续性判断，换人、位置突变或外观明显变化时自动清空历史结果；
- 增加人脸尺寸、正脸程度、亮度、对比度和清晰度综合质量评分；
- 模糊、过暗、过曝、距离过远或侧脸帧不再进入年龄性别平滑；
- 年龄改为质量加权中位数，并使用中位绝对偏差剔除偶发异常年龄；
- 性别改为质量及置信度加权，并加入切换迟滞，减少男女性别来回跳变；
- 对原图和水平翻转图的年龄结果进行一致性检查；
- 保持 v1.0.0 的初始化、NV21 提交和回调接口兼容。

## 1. 功能说明

AgeGenderLib 接收 Camera1 或 `CaeLib-release.aar` 回调的 NV21 摄像头数据，在 RK3588 NPU 上完成：

- 人脸检测；
- 478 点人脸关键点检测；
- 人脸五点对齐；
- 年龄、性别推理；
- 最多 7 次识别结果的内部平滑。

AAR 已内置三个 RK3588 RKNN 模型，不需要另外复制模型文件。AgeGenderLib 不重复携带 `librknnrt.so`，宿主项目必须保留原来的 `CaeLib-release.aar`，由它提供 RKNN Runtime 2.3.2。

## 2. 添加 AAR

把以下两个文件放入宿主项目的 `app/libs`：

```text
app/libs/CaeLib-release.aar
app/libs/AgeGenderLib-RK3588-v1.0.0.aar
```

带版本号和不带版本号的 AgeGenderLib 内容相同，只能放一个，不能同时集成，否则会出现重复类错误。

在 `app/build.gradle` 中添加：

```gradle
dependencies {
    implementation files('libs/CaeLib-release.aar')
    implementation files('libs/AgeGenderLib-RK3588-v1.0.0.aar')
}
```

当前 CaeLib 只提供完整的 `armeabi-v7a` 原生库，因此宿主项目保持：

```gradle
android {
    defaultConfig {
        ndk {
            abiFilters 'armeabi-v7a'
        }
    }
}
```

## 3. 初始化

在 Activity 或管理类中声明：

```java
import com.zuicun.agegender.AgeGenderConfig;
import com.zuicun.agegender.AgeGenderDetector;
import com.zuicun.agegender.AgeGenderListener;
import com.zuicun.agegender.AgeGenderResult;

private AgeGenderDetector ageGenderDetector;
```

在 `Activity.onCreate()` 中初始化：

```java
ageGenderDetector = new AgeGenderDetector(
        this,
        AgeGenderConfig.builder()
                // 192.168.46.136 当前 CaeLib 摄像头的实测参数
                .setRotationDegrees(0)
                .setMirrored(false)
                .setIntervalMs(700)
                .setDebugLogging(true)
                .build(),
        new AgeGenderListener() {
            @Override
            public void onAgeGenderResult(AgeGenderResult result) {
                if (!result.isFaceDetected()) {
                    ageGenderTextView.setText("年龄性别：等待人脸");
                    return;
                }

                String gender = result.getGenderLabel();
                int age = result.getAge();
                String ageBand = result.getAgeBand();
                float confidence = result.getGenderConfidence();

                ageGenderTextView.setText(
                        "性别：" + gender
                                + "  年龄：" + age
                                + "（" + ageBand + "）"
                );
            }

            @Override
            public void onAgeGenderError(String message) {
                Log.e("AgeGenderDemo", message);
            }
        }
);
```

`onAgeGenderResult()` 和 `onAgeGenderError()` 已回到 Android 主线程，可以直接更新 TextView。

## 4. 提交 CaeLib 的 NV21 数据

在原来的 `RealtimeDualModalEngine.Listener.onVideoStream()` 中添加：

```java
@Override
public void onVideoStream(
        byte[] nv21,
        int width,
        int height,
        long timestampMs
) {
    if (ageGenderDetector != null) {
        ageGenderDetector.submitNv21(
                nv21,
                width,
                height,
                timestampMs
        );
    }

    // 原来的 NV21 预览或其他处理代码可以继续保留。
}
```

注意：

- 不要修改 NV21 数组；
- 不要在回调结束后继续保存并使用这个 NV21 数组；
- CaeLib 可能会重复使用原始缓冲区；
- AgeGenderLib 会在内部复制真正需要处理的帧，不会阻塞 CaeLib 的摄像头回调；
- 达到限频时间或者上一帧仍在推理时，`submitNv21()` 会返回 `false`，属于正常跳帧。

## 5. 释放资源

```java
@Override
protected void onDestroy() {
    if (ageGenderDetector != null) {
        ageGenderDetector.close();
        ageGenderDetector = null;
    }

    super.onDestroy();
}
```

## 6. AgeGenderResult 字段

| 接口 | 含义 |
|---|---|
| `isFaceDetected()` | 当前是否检测到人脸 |
| `isResultReliable()` | 当前年龄性别结果是否达到可用质量 |
| `getStatus()` | `STATUS_OK`、`STATUS_NO_FACE` 或 `STATUS_QUALITY_INSUFFICIENT` |
| `getStatusLabel()` | `正常`、`无人脸`或`人脸质量不足` |
| `getQualityScore()` | 当前有效人脸的综合质量分数，范围为 `0～1` |
| `getGenderLabel()` | `男`、`女`或者`待稳定` |
| `getGender()` | 男为 `1`，女为 `0`，未知为 `-1` |
| `getAge()` | 内部平滑后的具体年龄；无人脸时为 `-1` |
| `getAgeBand()` | 年龄段，例如 `20-29`、`70+` |
| `getGenderConfidence()` | 性别稳定置信度，范围为 `0～1` |
| `getSampleCount()` | 当前参与平滑的有效样本数量 |
| `getTimestampMs()` | 对应输入帧的时间戳 |
| `getInferenceLatencyMs()` | 本次完整处理耗时，单位毫秒 |

兼容旧代码时可以继续先检查 `isFaceDetected()`。新代码建议检查 `isResultReliable()`，只有返回 `true` 时才展示年龄和性别；检测到人脸但质量不足时，可提示用户靠近摄像头或正对摄像头。

## 7. 配置参数

| 参数 | 说明 |
|---|---|
| `setRotationDegrees()` | NV21 顺时针旋转角度，只能传 `0/90/180/270` |
| `setMirrored()` | 摄像头图像是否需要水平镜像 |
| `setIntervalMs()` | 两次年龄性别推理的最短间隔，最低 `200ms`，建议 `700ms` |
| `setDebugLogging()` | 是否输出 `AgeGenderLib` 调试日志 |

在 `192.168.46.136` 当前设备上已经验证：

```text
NV21尺寸：640x480
rotationDegrees：0
mirrored：false
```

实测三个模型均成功运行在 RK3588 NPU，单次完整处理约为 24～45ms。

## 8. 常见问题

### 出现 `Duplicate librknnrt.so`

确认使用最新的 AgeGenderLib AAR。最新版不携带 `librknnrt.so`，只复用 CaeLib 中的 RKNN Runtime。

### 一直没有人脸

首先检查日志中的 NV21 宽高，然后检查 `rotationDegrees`。如果画面实际倒置或横竖方向不正确，依次测试 `0/90/180/270`。

### 性别显示“待稳定”

这是低置信度或者刚检测到人脸时的正常状态。连续获得几个有效样本后，库会自动稳定为男或女。

### 正式系统 APK 无法安装

如果宿主 APK 使用 `android.uid.system`，必须使用目标 RK3588 固件的平台证书签名。AgeGenderLib 本身不要求系统 UID。
