# AI voice cleanup with DPDFNet-8

MediaTool implements an offline speech-cleanup pipeline using the official `dpdfnet8_48khz_hr` TFLite model from Ceva-IP, followed by deterministic voice shaping and loudness normalization.

## Pinned model

- Model: `dpdfnet8_48khz_hr`
- Revision: `dd6818d00f50c836fed43a6243ebe49116de5964`
- Size: `19,639,068` bytes
- SHA-256: `3a28291a00b359592eaf6e853f49344eb6aac23dc992739de28da0f9face44c3`
- License: Apache-2.0
- Model is downloaded on demand and is not bundled in the APK.

## Signal contract

- Input is decoded to mono float PCM at 48 kHz.
- STFT window: 960 samples.
- Hop: 480 samples.
- Vorbis window.
- Model tensor: `[1, 1, 481, 2]` represented as 962 floats.
- The LiteRT model instance is fresh for every file so recurrent state cannot leak between tasks.
- Processing is frame-streamed; memory usage does not grow with file duration.

## Pipeline

1. Decode source audio or the audio stream of a video to mono 48 kHz float PCM.
2. Run stateful DPDFNet-8 inference on each 10 ms hop.
3. Apply a four-frame aligned attenuation limit to reduce over-suppression artifacts.
4. Reconstruct PCM with overlap-add and remove the model's fixed 1,920-sample advance.
5. Apply high-pass filtering and light compression.
6. Measure loudness, then apply two-pass EBU R128 normalization when measurement succeeds.
7. Resample to 48 kHz, apply a -1 dBFS limiter and encode using the user's audio format settings.

## User presets

- Natural: 9 dB attenuation limit.
- Balanced: 15 dB attenuation limit.
- Strong: 24 dB attenuation limit.
- Loudness targets: -18, -16 or -14 LUFS.

## Operational safety

- Foreground media-processing service.
- Model size and SHA-256 validation before inference.
- Maximum source duration of three hours.
- Storage and available-RAM preflight.
- Cancellation propagates to FFmpeg and the frame loop.
- Task state and output file survive activity/process reconstruction.
- Temporary float PCM files are deleted on success, failure or cancellation.

## Known first-version limits

- Output is mono.
- CPU/XNNPACK is used for predictable recurrent-model support.
- Runtime speed depends on device CPU; the pipeline records inference RTF and frame latency in diagnostics.
- The model reduces noise but cannot restore speech that was never captured, severe clipping, or fully masked words.
