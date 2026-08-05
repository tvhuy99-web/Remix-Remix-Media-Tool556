#!/usr/bin/env python3
"""Export the official MSST MDX23C vocal checkpoint as a fixed spectrogram-core ONNX graph."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort
import torch
from torch import nn

INPUT_NAME = "spectrogram"
OUTPUT_NAME = "vocals_spectrogram"
INPUT_SHAPE = (1, 4, 4096, 256)
OPSET = 18


class VocalSpectrogramCore(nn.Module):
    """The learned MDX23C core without torch.stft/torch.istft."""

    def __init__(self, model: nn.Module, vocal_index: int) -> None:
        super().__init__()
        self.model = model
        self.vocal_index = vocal_index

    def forward(self, spectrogram: torch.Tensor) -> torch.Tensor:
        x = self.model.cac2cws(spectrogram)
        mix = x
        x = self.model.first_conv(x)
        first_conv_out = x
        x = x.transpose(-1, -2)

        encoder_outputs = []
        for block in self.model.encoder_blocks:
            x = block.tfc_tdf(x)
            encoder_outputs.append(x)
            x = block.downscale(x)

        x = self.model.bottleneck_block(x)
        for block in self.model.decoder_blocks:
            x = block.upscale(x)
            x = torch.cat([x, encoder_outputs.pop()], dim=1)
            x = block.tfc_tdf(x)

        x = x.transpose(-1, -2)
        x = x * first_conv_out
        x = self.model.final_conv(torch.cat([mix, x], dim=1))
        x = self.model.cws2cac(x)
        batch, _, frequencies, frames = x.shape
        x = x.reshape(batch, self.model.num_target_instruments, -1, frequencies, frames)
        return x[:, self.vocal_index]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--msst-root", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--vocal-index", type=int, default=0)
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--skip-ort-parity", action="store_true")
    return parser.parse_args()


def extract_state_dict(checkpoint: Any) -> dict[str, torch.Tensor]:
    if isinstance(checkpoint, dict):
        for key in ("state_dict", "model_state_dict", "model"):
            candidate = checkpoint.get(key)
            if isinstance(candidate, dict) and candidate:
                checkpoint = candidate
                break
    if not isinstance(checkpoint, dict) or not checkpoint:
        raise TypeError("Unsupported checkpoint structure")

    result: dict[str, torch.Tensor] = {}
    for key, value in checkpoint.items():
        if not isinstance(value, torch.Tensor):
            continue
        clean = key.removeprefix("module.").removeprefix("model.")
        result[clean] = value
    if not result:
        raise ValueError("Checkpoint contains no tensor weights")
    return result


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    args = parse_args()
    msst_root = args.msst_root.resolve()
    config_path = args.config.resolve()
    checkpoint_path = args.checkpoint.resolve()
    output_path = args.output.resolve()
    if not (msst_root / "utils" / "settings.py").is_file():
        raise FileNotFoundError(f"Not an MSST checkout: {msst_root}")
    if not config_path.is_file() or not checkpoint_path.is_file():
        raise FileNotFoundError("Config or checkpoint is missing")

    sys.path.insert(0, str(msst_root))
    from utils.settings import get_model_from_config  # pylint: disable=import-error

    torch.set_grad_enabled(False)
    model, config = get_model_from_config("mdx23c", str(config_path))
    state = extract_state_dict(torch.load(checkpoint_path, map_location="cpu", weights_only=False))
    missing, unexpected = model.load_state_dict(state, strict=False)
    if missing or unexpected:
        raise RuntimeError(f"Checkpoint mismatch: missing={missing}, unexpected={unexpected}")
    model.eval()

    instruments = list(config.training.instruments)
    if args.vocal_index < 0 or args.vocal_index >= len(instruments):
        raise ValueError(f"vocal-index {args.vocal_index} outside {instruments}")
    if instruments[args.vocal_index] != "vocals":
        raise ValueError(f"Selected source is {instruments[args.vocal_index]!r}, not 'vocals'")

    core = VocalSpectrogramCore(model, args.vocal_index).eval()
    sample = torch.randn(INPUT_SHAPE, dtype=torch.float32)
    with torch.inference_mode():
        reference = core(sample).cpu().numpy()
    if tuple(reference.shape) != INPUT_SHAPE:
        raise RuntimeError(f"Unexpected core output shape: {reference.shape}")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        core,
        sample,
        output_path,
        input_names=[INPUT_NAME],
        output_names=[OUTPUT_NAME],
        opset_version=OPSET,
        do_constant_folding=True,
        dynamic_axes=None,
    )
    graph = onnx.load(str(output_path), load_external_data=False)
    onnx.checker.check_model(graph)
    if any(tensor.data_location == onnx.TensorProto.EXTERNAL for tensor in graph.graph.initializer):
        raise RuntimeError("External-data ONNX is not supported by the Android downloader")

    parity = None
    if not args.skip_ort_parity:
        session = ort.InferenceSession(str(output_path), providers=["CPUExecutionProvider"])
        actual = session.run([OUTPUT_NAME], {INPUT_NAME: sample.numpy()})[0]
        difference = np.abs(reference - actual)
        parity = {
            "max_abs_error": float(difference.max()),
            "mean_abs_error": float(difference.mean()),
        }
        if parity["max_abs_error"] > 2e-4:
            raise RuntimeError(f"ONNX parity failed: {parity}")

    metadata_path = (args.metadata or output_path.with_suffix(".json")).resolve()
    metadata = {
        "format": "mdx23c-vocal-spectrogram-core-v1",
        "source_repository": "ZFTurbo/Music-Source-Separation-Training",
        "config": str(config_path),
        "checkpoint_file": checkpoint_path.name,
        "checkpoint_sha256": sha256(checkpoint_path),
        "onnx_file": output_path.name,
        "onnx_bytes": output_path.stat().st_size,
        "onnx_sha256": sha256(output_path),
        "opset": OPSET,
        "input_name": INPUT_NAME,
        "output_name": OUTPUT_NAME,
        "input_shape": list(INPUT_SHAPE),
        "output_shape": list(INPUT_SHAPE),
        "parity": parity,
    }
    metadata_path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(metadata, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
