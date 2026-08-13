# Studio multivocal implementation

## Implemented behavior

A normal Studio recording now creates an independent vocal layer instead of replacing the active take on the first vocal track.

- First normal recording: `Giọng chính`.
- Later normal recordings: `Giọng 2`, `Giọng 3`, ...
- Each later layer receives its own track and a latency-compensated timeline clip as soon as the take is finalized.
- Existing playback, mixer and render code already iterate all non-muted project tracks, so these layers play and export together.
- Punch recording reuses an existing target track rather than creating a new layer. If the user selected a clip, its owning track is requested; otherwise Studio falls back to the main vocal track.
- If recording setup is cancelled or fails before any audio/take is committed, an empty automatically-created voice layer is removed.

## Compatibility

Projects created before this change remain valid. Repository callers that do not send an explicit recording-target request retain the previous behavior of using the first `VOCAL` track.

## Tests

`StudioRecordingTargetTest` covers:

- first vocal-layer creation;
- subsequent independent layer creation and naming;
- punch target reuse;
- one-shot request consumption.

## CI verification

GitHub Actions run `31662633869` completed successfully after applying the source patch and ran project verification, unit tests, lint, debug APK assembly, native Studio/Spatial library inspection and APK signature verification.
