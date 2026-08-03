#!/usr/bin/env python3
"""Compare original TFLite outputs with converted RKNN simulator outputs."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import tensorflow as tf
from rknn.api import RKNN


ROOT = Path.home() / "workspace"
EXTRACTED = ROOT / "rknn-output" / "face_landmarker_extracted"
OUTPUT = ROOT / "rknn-output"


def tflite_inference(model_path: Path, input_data: np.ndarray) -> list[np.ndarray]:
    interpreter = tf.lite.Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    interpreter.set_tensor(input_detail["index"], input_data.astype(input_detail["dtype"]))
    interpreter.invoke()
    return [
        interpreter.get_tensor(detail["index"]).reshape(-1)
        for detail in interpreter.get_output_details()
    ]


def rknn_inference(
        model_path: Path,
        input_data: np.ndarray,
        mean: list[float],
        std: list[float],
) -> list[np.ndarray]:
    rknn = RKNN(verbose=False)
    try:
        assert rknn.config(
            target_platform="rk3576", mean_values=mean, std_values=std) == 0
        assert rknn.load_tflite(model=str(model_path)) == 0
        assert rknn.build(do_quantization=False) == 0
        assert rknn.init_runtime() == 0
        return [
            np.asarray(value).reshape(-1)
            for value in rknn.inference(inputs=[input_data], data_format=["nhwc"])
        ]
    finally:
        rknn.release()


def by_size(outputs: list[np.ndarray], size: int, occurrence: int = 0) -> np.ndarray:
    matches = [value for value in outputs if value.size == size]
    return matches[occurrence]


def report(label: str, reference: np.ndarray, converted: np.ndarray) -> None:
    reference = reference.astype(np.float32)
    converted = converted.astype(np.float32)
    difference = np.abs(reference - converted)
    cosine = float(np.dot(reference, converted) / (
        np.linalg.norm(reference) * np.linalg.norm(converted) + 1e-12))
    print(
        f"{label}: cosine={cosine:.8f}, "
        f"mean_abs={difference.mean():.8f}, max_abs={difference.max():.8f}"
    )


def main() -> None:
    random = np.random.default_rng(3576)

    detector_rgb = random.integers(0, 256, (1, 128, 128, 3), dtype=np.uint8)
    detector_tflite = tflite_inference(
        EXTRACTED / "face_detector.tflite",
        (detector_rgb.astype(np.float32) - 127.5) / 127.5,
    )
    detector_rknn = rknn_inference(
        EXTRACTED / "face_detector.tflite",
        detector_rgb,
        [127.5, 127.5, 127.5],
        [127.5, 127.5, 127.5],
    )
    report(
        "detector regressors",
        by_size(detector_tflite, 896 * 16),
        by_size(detector_rknn, 896 * 16),
    )
    report(
        "detector scores",
        by_size(detector_tflite, 896),
        by_size(detector_rknn, 896),
    )

    landmark_rgb = random.integers(0, 256, (1, 256, 256, 3), dtype=np.uint8)
    landmark_tflite = tflite_inference(
        EXTRACTED / "face_landmarks_detector.tflite",
        landmark_rgb.astype(np.float32) / 255.0,
    )
    landmark_rknn = rknn_inference(
        EXTRACTED / "face_landmarks_detector.tflite",
        landmark_rgb,
        [0.0, 0.0, 0.0],
        [255.0, 255.0, 255.0],
    )
    report(
        "face landmarks",
        by_size(landmark_tflite, 1434),
        by_size(landmark_rknn, 1434),
    )
    print("Model conversion comparison completed.")


if __name__ == "__main__":
    main()
