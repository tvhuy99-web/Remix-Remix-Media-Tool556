#!/usr/bin/env python3
"""Regenerate the MossFormer2 Kaldi-fbank values used by MossFormer2DspTest.

Requires torch and torchaudio. Dither is disabled here so the parity fixture is deterministic;
production separately enables and A/B-measures the upstream 1-LSB dither setting.
"""

from __future__ import annotations

import math

import torch
import torchaudio

SAMPLE_RATE = 48_000
SAMPLES = 4 * SAMPLE_RATE
SELECTED_FRAMES = (0, 1, 127, 495)
SELECTED_MELS = 10


def main() -> None:
    waveform = torch.empty(SAMPLES, dtype=torch.float32)
    for index in range(SAMPLES):
        position = float(index)
        waveform[index] = 32_768.0 * (
            0.31 * math.sin(2.0 * math.pi * 440.0 * position / SAMPLE_RATE)
            + 0.17 * math.sin(2.0 * math.pi * 1_234.0 * position / SAMPLE_RATE)
            + 0.03 * math.cos(2.0 * math.pi * 73.0 * position / SAMPLE_RATE)
        )

    features = torchaudio.compliance.kaldi.fbank(
        waveform.unsqueeze(0),
        dither=0.0,
        frame_length=40.0,
        frame_shift=8.0,
        num_mel_bins=60,
        sample_frequency=float(SAMPLE_RATE),
        window_type="hamming",
    )

    for frame in SELECTED_FRAMES:
        values = ", ".join(f"{value.item():.6f}f" for value in features[frame, :SELECTED_MELS])
        print(f"{frame} to floatArrayOf({values}),")


if __name__ == "__main__":
    main()
