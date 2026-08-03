package com.zuicun.liptalkdemo;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.List;

/** One-shot detector used for administrator photos selected from the gallery. */
public final class EnrollmentFaceDetector implements AutoCloseable {
    private final FaceLandmarker landmarker;

    public EnrollmentFaceDetector(Context context) {
        BaseOptions base = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build();
        FaceLandmarker.FaceLandmarkerOptions options =
                FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(base)
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumFaces(2)
                        .setMinFaceDetectionConfidence(0.65f)
                        .setMinFacePresenceConfidence(0.65f)
                        .build();
        landmarker = FaceLandmarker.createFromOptions(context, options);
    }

    public List<NormalizedLandmark> detectSingle(Bitmap bitmap) {
        FaceLandmarkerResult result =
                landmarker.detect(new BitmapImageBuilder(bitmap).build());
        if (result.faceLandmarks().isEmpty()) {
            throw new IllegalArgumentException("照片中没有检测到清晰人脸");
        }
        if (result.faceLandmarks().size() != 1) {
            throw new IllegalArgumentException("注册照片中只能有一张人脸");
        }
        return result.faceLandmarks().get(0);
    }

    @Override
    public void close() {
        landmarker.close();
    }
}
