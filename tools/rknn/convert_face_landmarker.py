#!/usr/bin/env python3
"""Extract and convert MediaPipe Face Landmarker models for a Rockchip NPU."""

from __future__ import annotations

import argparse
import zipfile
from dataclasses import dataclass
from pathlib import Path

from rknn.api import RKNN


@dataclass(frozen=True)
class ModelSpec:
    source: str
    output_stem: str
    mean: list[float]
    std: list[float]


MODEL_SPECS = (
    ModelSpec(
        source="face_detector.tflite",
        output_stem="face_detector",
        # MediaPipe face detector ImageToTensor output range is [-1, 1].
        mean=[127.5, 127.5, 127.5],
        std=[127.5, 127.5, 127.5],
    ),
    ModelSpec(
        source="face_landmarks_detector.tflite",
        output_stem="face_landmarks",
        # MediaPipe face landmark ImageToTensor output range is [0, 1].
        mean=[0.0, 0.0, 0.0],
        std=[255.0, 255.0, 255.0],
    ),
)


def convert(
        spec: ModelSpec, extracted_dir: Path, output_dir: Path,
        target_platform: str,
) -> None:
    source = extracted_dir / spec.source
    destination = output_dir / f"{spec.output_stem}_{target_platform}_fp16.rknn"
    print(f"\n=== Converting {source.name} -> {destination.name} ===")
    rknn = RKNN(verbose=True)
    try:
        result = rknn.config(
            target_platform=target_platform,
            mean_values=spec.mean,
            std_values=spec.std,
            optimization_level=3,
        )
        if result != 0:
            raise RuntimeError(f"rknn.config failed: {result}")
        result = rknn.load_tflite(model=str(source))
        if result != 0:
            raise RuntimeError(f"rknn.load_tflite failed: {result}")
        result = rknn.build(do_quantization=False)
        if result != 0:
            raise RuntimeError(f"rknn.build failed: {result}")
        result = rknn.export_rknn(str(destination))
        if result != 0:
            raise RuntimeError(f"rknn.export_rknn failed: {result}")
        print(f"Created: {destination} ({destination.stat().st_size / 1024 / 1024:.2f} MiB)")
    finally:
        rknn.release()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--task", type=Path, default=Path.home() / "workspace" / "models" / "face_landmarker.task"
    )
    parser.add_argument(
        "--output-dir", type=Path, default=Path.home() / "workspace" / "rknn-output"
    )
    parser.add_argument(
        "--target-platform", choices=("rk3576", "rk3588"), default="rk3576"
    )
    args = parser.parse_args()
    task = args.task.resolve()
    output_dir = args.output_dir.resolve()
    extracted_dir = output_dir / "face_landmarker_extracted"
    output_dir.mkdir(parents=True, exist_ok=True)
    extracted_dir.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(task) as archive:
        for spec in MODEL_SPECS:
            archive.extract(spec.source, extracted_dir)

    for spec in MODEL_SPECS:
        convert(spec, extracted_dir, output_dir, args.target_platform)
    print(
        f"\n{args.target_platform.upper()} Face Landmarker neural models "
        "converted successfully."
    )


if __name__ == "__main__":
    main()
