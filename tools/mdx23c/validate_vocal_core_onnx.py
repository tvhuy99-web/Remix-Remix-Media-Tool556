#!/usr/bin/env python3
"""Reject MDX23C ONNX artifacts that cannot fit the Android phase-1 contract."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import onnx

EXPECTED_INPUT = "spectrogram"
EXPECTED_OUTPUT = "vocals_spectrogram"
EXPECTED_SHAPE = [1, 4, 4096, 256]
DISALLOWED_OPS = {"DFT", "STFT", "Loop", "Scan", "If"}


def tensor_shape(value: onnx.ValueInfoProto) -> list[int | str | None]:
    result: list[int | str | None] = []
    for dimension in value.type.tensor_type.shape.dim:
        if dimension.HasField("dim_value"):
            result.append(dimension.dim_value)
        elif dimension.HasField("dim_param"):
            result.append(dimension.dim_param)
        else:
            result.append(None)
    return result


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    args = parser.parse_args()
    model_path = args.model.resolve()
    graph = onnx.load(str(model_path), load_external_data=False)
    onnx.checker.check_model(graph)

    if len(graph.graph.input) != 1 or len(graph.graph.output) != 1:
        raise RuntimeError("Graph must expose exactly one input and one output")
    input_info = graph.graph.input[0]
    output_info = graph.graph.output[0]
    if input_info.name != EXPECTED_INPUT or output_info.name != EXPECTED_OUTPUT:
        raise RuntimeError(
            f"Names must be {EXPECTED_INPUT!r}/{EXPECTED_OUTPUT!r}, "
            f"got {input_info.name!r}/{output_info.name!r}",
        )
    if tensor_shape(input_info) != EXPECTED_SHAPE or tensor_shape(output_info) != EXPECTED_SHAPE:
        raise RuntimeError(
            f"Static shape must be {EXPECTED_SHAPE}, "
            f"got {tensor_shape(input_info)}/{tensor_shape(output_info)}",
        )

    operators = sorted({node.op_type for node in graph.graph.node})
    forbidden = sorted(DISALLOWED_OPS.intersection(operators))
    if forbidden:
        raise RuntimeError(f"Graph still contains host-DSP/control-flow operators: {forbidden}")
    if any(tensor.data_location == onnx.TensorProto.EXTERNAL for tensor in graph.graph.initializer):
        raise RuntimeError("External-data ONNX is not supported")

    report = {
        "bytes": model_path.stat().st_size,
        "sha256": sha256(model_path),
        "opset_imports": {item.domain or "ai.onnx": item.version for item in graph.opset_import},
        "operators": operators,
        "input": {"name": input_info.name, "shape": tensor_shape(input_info)},
        "output": {"name": output_info.name, "shape": tensor_shape(output_info)},
    }
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
