#!/usr/bin/env python3
"""Convert LipTalkDemo ONNX models to FP16 RKNN models for a Rockchip NPU."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

from rknn.api import RKNN


@dataclass(frozen=True)
class ModelSpec:
    source: str
    output_stem: str
    input_name: str
    input_shape: list[int]
    mean: list[float]
    std: list[float]


MODEL_SPECS = (
    ModelSpec(
        source="w600k_mbf.onnx",
        output_stem="w600k_mbf",
        input_name="input.1",
        input_shape=[1, 3, 112, 112],
        mean=[127.5, 127.5, 127.5],
        std=[127.5, 127.5, 127.5],
    ),
    ModelSpec(
        source="genderage.onnx",
        output_stem="genderage",
        input_name="data",
        input_shape=[1, 3, 96, 96],
        # genderage.onnx contains its own Sub/Mul normalization.
        mean=[0.0, 0.0, 0.0],
        std=[1.0, 1.0, 1.0],
    ),
)


def convert(
        spec: ModelSpec, models_dir: Path, output_dir: Path, target_platform: str
) -> None:
    source = models_dir / spec.source
    destination = output_dir / f"{spec.output_stem}_{target_platform}_fp16.rknn"
    if not source.is_file():
        raise FileNotFoundError(source)

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

        result = rknn.load_onnx(
            model=str(source),
            inputs=[spec.input_name],
            input_size_list=[spec.input_shape],
        )
        if result != 0:
            raise RuntimeError(f"rknn.load_onnx failed: {result}")

        # Start with FP16. INT8 requires a representative face dataset and
        # accuracy validation before it is safe for identity recognition.
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
        "--models-dir", type=Path, default=Path.home() / "workspace" / "models"
    )
    parser.add_argument(
        "--output-dir", type=Path, default=Path.home() / "workspace" / "rknn-output"
    )
    parser.add_argument(
        "--target-platform", choices=("rk3576", "rk3588"), default="rk3576"
    )
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    for spec in MODEL_SPECS:
        convert(
            spec, args.models_dir.resolve(), args.output_dir.resolve(),
            args.target_platform,
        )
    print(f"\nAll {args.target_platform.upper()} FP16 models converted successfully.")


if __name__ == "__main__":
    main()
