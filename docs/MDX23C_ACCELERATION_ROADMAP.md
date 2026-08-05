# MDX23C acceleration roadmap

## Runtime modes exposed now

- CPU stable
- XNNPACK experimental with automatic CPU fallback
- 25%, 50% and 75% host overlap modes

Every separation report must record requested acceleration, effective backend, overlap mode, stride,
chunk count, per-chunk inference time, peak PSS and native allocation.

## LiteRT GPU prototype

1. Convert only the learned `[1,4,4096,256]` spectrogram core; keep STFT/iSTFT and residual music in Kotlin.
2. Prefer FP16 weights and FP16 GPU execution while preserving float host I/O if required.
3. Rewrite or fuse unsupported normalization/operator patterns instead of silently partitioning most of
   the graph back to CPU.
4. Require PyTorch/ONNX/LiteRT parity on fixed spectrogram fixtures.
5. Gate the option by a real device probe and fall back to XNNPACK/CPU after recoverable open errors.

## Qualcomm QNN prototype

1. Build a separate ONNX Runtime Android AAR with QNN enabled; do not replace the stable AAR first.
2. Start with QNN GPU for the float graph, then test HTP only with a calibrated quantized artifact.
3. Package QNN libraries only in an experimental build flavor and gate by supported Snapdragon SOC.
4. Use QNN context caching after correctness is proven to reduce session startup cost.
5. Do not expose QNN in the UI until the complete graph, or a clearly dominant subgraph, is offloaded.

## Acceptance thresholds on the OnePlus reference device

For the 28.54-second hard vocal/instrumental sample:

- balanced mode target: under 180 seconds
- fast mode target: under 120 seconds
- no OOM, NaN/Inf or clipping
- reconstruction correlation at least 0.999
- no clearly audible seam clicks at configured boundaries
- quality must remain competitive with UVR MDX-Net on the internal hard-case set

A backend that is faster only on one chunk but loses the advantage through graph partitioning, thermal
throttling or session setup is not accepted.
