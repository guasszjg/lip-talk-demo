#!/usr/bin/env python3
"""Convert LipTalkDemo ONNX models to FP16 RKNN models for RK3576."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

from rknn.api import RKNN


@dataclass(frozen=True)
class ModelSpec:
    source: str
    output: str
    input_name: str
    input_shape: list[int]
    mean: list[float]
    std: list[float]


MODEL_SPECS = (
    ModelSpec(
        source="w600k_mbf.onnx",
        output="w600k_mbf_rk3576_fp16.rknn",
        input_name="input.1",
        input_shape=[1, 3, 112, 112],
        mean=[127.5, 127.5, 127.5],
        std=[127.5, 127.5, 127.5],
    ),
    ModelSpec(
        source="genderage.onnx",
        output="genderage_rk3576_fp16.rknn",
        input_name="data",
        input_shape=[1, 3, 96, 96],
        # genderage.onnx contains its own Sub/Mul normalization.
        mean=[0.0, 0.0, 0.0],
        std=[1.0, 1.0, 1.0],
    ),
)


def convert(spec: ModelSpec, models_dir: Path, output_dir: Path) -> None:
    source = models_dir / spec.source
    destination = output_dir / spec.output
    if not source.is_file():
        raise FileNotFoundError(source)

    print(f"\n=== Converting {source.name} -> {destination.name} ===")
    rknn = RKNN(verbose=True)
    try:
        result = rknn.config(
            target_platform="rk3576",
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
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    for spec in MODEL_SPECS:
        convert(spec, args.models_dir.resolve(), args.output_dir.resolve())
    print("\nAll RK3576 FP16 models converted successfully.")


if __name__ == "__main__":
    main()
