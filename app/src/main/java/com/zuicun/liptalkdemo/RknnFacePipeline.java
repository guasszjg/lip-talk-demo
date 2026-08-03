package com.zuicun.liptalkdemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** RK3576 NPU implementation of BlazeFace detection plus 478-point face landmarks. */
final class RknnFacePipeline implements AutoCloseable {
    private static final int DETECTOR_SIZE = 128;
    private static final int LANDMARK_SIZE = 256;
    private static final float DETECTION_THRESHOLD = 0.55f;
    private static final float NMS_THRESHOLD = 0.30f;
    private static final float LANDMARK_THRESHOLD = 0.50f;
    private static final float ROI_SCALE = 1.50f;
    private static final List<Anchor> ANCHORS = createAnchors();

    private final RknnSession detector;
    private final RknnSession landmarker;
    private final int maximumFaces;
    private final float viewportAspect;

    static RknnFacePipeline tryCreate(Context context, int maximumFaces) {
        RknnSession detector = RknnSession.tryCreate(
                context, "face_detector_rk3576_fp16.rknn", 896 * 16, 896);
        if (detector == null) return null;
        RknnSession landmarker = RknnSession.tryCreate(
                context, "face_landmarks_rk3576_fp16.rknn", 1434, 1, 1);
        if (landmarker == null) {
            detector.close();
            return null;
        }
        return new RknnFacePipeline(context, detector, landmarker, maximumFaces);
    }

    private RknnFacePipeline(
            Context context, RknnSession detector, RknnSession landmarker, int maximumFaces
    ) {
        viewportAspect = context.getResources().getDisplayMetrics().widthPixels
                / (float) context.getResources().getDisplayMetrics().heightPixels;
        this.detector = detector;
        this.landmarker = landmarker;
        this.maximumFaces = maximumFaces;
    }

    List<List<NormalizedLandmark>> detect(Bitmap frame) {
        List<Detection> detections = new ArrayList<>();
        for (DetectorInput input : prepareDetectorInputs(frame, viewportAspect)) {
            float[][] detectorOutputs;
            try {
                detectorOutputs = detector.runAll(toRgb(input.bitmap));
            } finally {
                input.bitmap.recycle();
            }
            float[] regressors = findOutput(detectorOutputs, 896 * 16);
            float[] scores = findOutput(detectorOutputs, 896);
            detections.addAll(decodeDetections(
                    regressors, scores, input, frame.getWidth(), frame.getHeight()));
        }
        detections = suppressOverlaps(detections);

        List<List<NormalizedLandmark>> faces = new ArrayList<>();
        for (Detection detection : detections) {
            List<NormalizedLandmark> landmarks = runLandmarks(frame, detection);
            if (landmarks != null) faces.add(landmarks);
            if (faces.size() >= maximumFaces) break;
        }
        return faces;
    }

    private List<NormalizedLandmark> runLandmarks(Bitmap frame, Detection detection) {
        float centerX = detection.centerX * frame.getWidth();
        float centerY = detection.centerY * frame.getHeight();
        float roiSize = Math.max(
                detection.width * frame.getWidth(),
                detection.height * frame.getHeight()) * ROI_SCALE;
        if (roiSize < 20f) return null;

        float eyeDx = (detection.keypoints[2] - detection.keypoints[0]) * frame.getWidth();
        float eyeDy = (detection.keypoints[3] - detection.keypoints[1]) * frame.getHeight();
        float rotation = normalizeHalfTurn((float) Math.atan2(eyeDy, eyeDx));
        float half = roiSize * 0.5f;
        float cosine = (float) Math.cos(rotation);
        float sine = (float) Math.sin(rotation);
        float[] source = {
                centerX + rotateX(-half, -half, cosine, sine),
                centerY + rotateY(-half, -half, cosine, sine),
                centerX + rotateX(half, -half, cosine, sine),
                centerY + rotateY(half, -half, cosine, sine),
                centerX + rotateX(-half, half, cosine, sine),
                centerY + rotateY(-half, half, cosine, sine)
        };
        float[] destination = {0f, 0f, LANDMARK_SIZE, 0f, 0f, LANDMARK_SIZE};
        Matrix frameToRoi = new Matrix();
        if (!frameToRoi.setPolyToPoly(source, 0, destination, 0, 3)) return null;

        Bitmap crop = Bitmap.createBitmap(
                LANDMARK_SIZE, LANDMARK_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(crop);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(frame, frameToRoi,
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        float[][] outputs;
        try {
            outputs = landmarker.runAll(toRgb(crop));
        } finally {
            crop.recycle();
        }
        float[] rawLandmarks = findOutput(outputs, 1434);
        if (rawLandmarks == null) return null;
        float presence = findPresence(outputs);
        if (presence < LANDMARK_THRESHOLD) return null;

        Matrix roiToFrame = new Matrix();
        if (!frameToRoi.invert(roiToFrame)) return null;
        List<NormalizedLandmark> result = new ArrayList<>(478);
        float[] point = new float[2];
        for (int index = 0; index < 478; index++) {
            point[0] = rawLandmarks[index * 3];
            point[1] = rawLandmarks[index * 3 + 1];
            roiToFrame.mapPoints(point);
            float x = point[0] / frame.getWidth();
            float y = point[1] / frame.getHeight();
            float z = rawLandmarks[index * 3 + 2]
                    / LANDMARK_SIZE * roiSize / frame.getWidth();
            result.add(NormalizedLandmark.create(x, y, z));
        }
        return result;
    }

    private static List<DetectorInput> prepareDetectorInputs(
            Bitmap frame, float viewportAspect
    ) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        List<DetectorInput> inputs = new ArrayList<>(3);
        inputs.add(prepareDetectorInput(frame, 0, 0, width, height));
        float frameAspect = width / (float) height;
        if (Math.abs(frameAspect - viewportAspect) < 0.15f) return inputs;

        int tileSize = Math.round(Math.min(width, height) * 2f / 3f);
        if (frameAspect > viewportAspect) {
            int left = (width - tileSize) / 2;
            inputs.add(prepareDetectorInput(frame, left, 0, tileSize, tileSize));
            inputs.add(prepareDetectorInput(
                    frame, left, height - tileSize, tileSize, tileSize));
        } else {
            int top = (height - tileSize) / 2;
            inputs.add(prepareDetectorInput(frame, 0, top, tileSize, tileSize));
            inputs.add(prepareDetectorInput(
                    frame, width - tileSize, top, tileSize, tileSize));
        }
        return inputs;
    }

    private static DetectorInput prepareDetectorInput(
            Bitmap frame, int cropLeft, int cropTop, int cropWidth, int cropHeight
    ) {
        float scale = Math.min(
                DETECTOR_SIZE / (float) cropWidth,
                DETECTOR_SIZE / (float) cropHeight);
        float padX = (DETECTOR_SIZE - cropWidth * scale) * 0.5f;
        float padY = (DETECTOR_SIZE - cropHeight * scale) * 0.5f;
        Bitmap bitmap = Bitmap.createBitmap(
                DETECTOR_SIZE, DETECTOR_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        canvas.translate(padX, padY);
        canvas.scale(scale, scale);
        canvas.drawBitmap(frame, -cropLeft, -cropTop,
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return new DetectorInput(
                bitmap, scale, padX, padY, cropLeft, cropTop);
    }

    private static List<Detection> decodeDetections(
            float[] boxes,
            float[] rawScores,
            DetectorInput input,
            int frameWidth,
            int frameHeight
    ) {
        List<Detection> candidates = new ArrayList<>();
        if (boxes == null || rawScores == null || ANCHORS.size() != 896) return candidates;
        for (int index = 0; index < ANCHORS.size(); index++) {
            float score = sigmoid(Math.max(-100f, Math.min(100f, rawScores[index])));
            if (score < DETECTION_THRESHOLD) continue;
            Anchor anchor = ANCHORS.get(index);
            int offset = index * 16;
            float centerX = boxes[offset] / DETECTOR_SIZE + anchor.x;
            float centerY = boxes[offset + 1] / DETECTOR_SIZE + anchor.y;
            float width = boxes[offset + 2] / DETECTOR_SIZE;
            float height = boxes[offset + 3] / DETECTOR_SIZE;
            float[] keypoints = new float[12];
            for (int keypoint = 0; keypoint < 6; keypoint++) {
                keypoints[keypoint * 2] =
                        boxes[offset + 4 + keypoint * 2] / DETECTOR_SIZE + anchor.x;
                keypoints[keypoint * 2 + 1] =
                        boxes[offset + 5 + keypoint * 2] / DETECTOR_SIZE + anchor.y;
            }
            Detection detection = mapToFrame(
                    centerX, centerY, width, height, keypoints, score,
                    input, frameWidth, frameHeight);
            if (detection.width > 0f && detection.height > 0f) candidates.add(detection);
        }
        return suppressOverlaps(candidates);
    }

    private static List<Detection> suppressOverlaps(List<Detection> candidates) {
        candidates.sort(Comparator.comparingDouble((Detection item) -> item.score).reversed());
        List<Detection> selected = new ArrayList<>();
        for (Detection candidate : candidates) {
            boolean overlaps = false;
            for (Detection accepted : selected) {
                if (iou(candidate, accepted) > NMS_THRESHOLD) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) selected.add(candidate);
        }
        return selected;
    }

    private static Detection mapToFrame(
            float centerX, float centerY, float width, float height,
            float[] keypoints, float score, DetectorInput input,
            int frameWidth, int frameHeight
    ) {
        float pixelCenterX = (centerX * DETECTOR_SIZE - input.padX) / input.scale
                + input.cropLeft;
        float pixelCenterY = (centerY * DETECTOR_SIZE - input.padY) / input.scale
                + input.cropTop;
        float mappedWidth = width * DETECTOR_SIZE / input.scale / frameWidth;
        float mappedHeight = height * DETECTOR_SIZE / input.scale / frameHeight;
        float[] mappedKeypoints = new float[keypoints.length];
        for (int index = 0; index < keypoints.length; index += 2) {
            mappedKeypoints[index] =
                    (keypoints[index] * DETECTOR_SIZE - input.padX)
                            / input.scale / frameWidth
                            + input.cropLeft / (float) frameWidth;
            mappedKeypoints[index + 1] =
                    (keypoints[index + 1] * DETECTOR_SIZE - input.padY)
                            / input.scale / frameHeight
                            + input.cropTop / (float) frameHeight;
        }
        return new Detection(
                pixelCenterX / frameWidth, pixelCenterY / frameHeight,
                mappedWidth, mappedHeight, mappedKeypoints, score);
    }

    private static float iou(Detection first, Detection second) {
        float firstLeft = first.centerX - first.width * 0.5f;
        float firstTop = first.centerY - first.height * 0.5f;
        float secondLeft = second.centerX - second.width * 0.5f;
        float secondTop = second.centerY - second.height * 0.5f;
        float intersectionWidth = Math.max(0f,
                Math.min(firstLeft + first.width, secondLeft + second.width)
                        - Math.max(firstLeft, secondLeft));
        float intersectionHeight = Math.max(0f,
                Math.min(firstTop + first.height, secondTop + second.height)
                        - Math.max(firstTop, secondTop));
        float intersection = intersectionWidth * intersectionHeight;
        return intersection / Math.max(1e-6f,
                first.width * first.height + second.width * second.height - intersection);
    }

    private static List<Anchor> createAnchors() {
        int[] strides = {8, 16, 16, 16};
        List<Anchor> anchors = new ArrayList<>(896);
        int layer = 0;
        while (layer < strides.length) {
            int lastSameStride = layer;
            while (lastSameStride < strides.length
                    && strides[lastSameStride] == strides[layer]) {
                lastSameStride++;
            }
            int anchorsPerCell = (lastSameStride - layer) * 2;
            int featureMap = (int) Math.ceil(DETECTOR_SIZE / (float) strides[layer]);
            for (int y = 0; y < featureMap; y++) {
                for (int x = 0; x < featureMap; x++) {
                    for (int ignored = 0; ignored < anchorsPerCell; ignored++) {
                        anchors.add(new Anchor(
                                (x + 0.5f) / featureMap,
                                (y + 0.5f) / featureMap));
                    }
                }
            }
            layer = lastSameStride;
        }
        return anchors;
    }

    private static byte[] toRgb(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        byte[] rgb = new byte[pixels.length * 3];
        int destination = 0;
        for (int pixel : pixels) {
            rgb[destination++] = (byte) ((pixel >> 16) & 0xff);
            rgb[destination++] = (byte) ((pixel >> 8) & 0xff);
            rgb[destination++] = (byte) (pixel & 0xff);
        }
        return rgb;
    }

    private static float[] findOutput(float[][] outputs, int size) {
        for (float[] output : outputs) if (output.length == size) return output;
        return null;
    }

    private static float findPresence(float[][] outputs) {
        // The first scalar output is the face-presence logit. The following scalar
        // is an auxiliary model output and must not be treated as a probability.
        for (float[] output : outputs) {
            if (output.length == 1) return sigmoid(output[0]);
        }
        return 0f;
    }

    private static float sigmoid(float value) {
        return (float) (1d / (1d + Math.exp(-value)));
    }

    private static float rotateX(
            float x, float y, float cosine, float sine
    ) {
        return x * cosine - y * sine;
    }

    private static float rotateY(
            float x, float y, float cosine, float sine
    ) {
        return x * sine + y * cosine;
    }

    private static float normalizeHalfTurn(float angle) {
        while (angle > Math.PI * 0.5f) angle -= (float) Math.PI;
        while (angle < -Math.PI * 0.5f) angle += (float) Math.PI;
        return angle;
    }

    @Override
    public void close() {
        detector.close();
        landmarker.close();
    }

    private static final class Anchor {
        final float x;
        final float y;
        Anchor(float x, float y) { this.x = x; this.y = y; }
    }

    private static final class DetectorInput {
        final Bitmap bitmap;
        final float scale;
        final float padX;
        final float padY;
        final int cropLeft;
        final int cropTop;
        DetectorInput(
                Bitmap bitmap, float scale, float padX, float padY,
                int cropLeft, int cropTop
        ) {
            this.bitmap = bitmap;
            this.scale = scale;
            this.padX = padX;
            this.padY = padY;
            this.cropLeft = cropLeft;
            this.cropTop = cropTop;
        }
    }

    private static final class Detection {
        final float centerX;
        final float centerY;
        final float width;
        final float height;
        final float[] keypoints;
        final float score;
        Detection(
                float centerX, float centerY, float width, float height,
                float[] keypoints, float score
        ) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
            this.keypoints = keypoints;
            this.score = score;
        }
    }
}
