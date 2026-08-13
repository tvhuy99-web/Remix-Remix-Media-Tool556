# Studio multivocal rollout plan

## Goal

Upgrade Studio full-take recording from replacement takes on one vocal track to independent vocal layers that can play, mix, edit and export at the same time, while keeping punch recording, latency compensation, recovery and existing projects compatible.

Accessibility is a product constraint, not a later polish pass. Core editing must remain fully operable without drag gestures, precise waveform tapping or visual-only state.

## Phase 1 — independent overdub layers ✅

Implemented:

- Normal **Thu giọng** requests a fresh recording layer.
- The first layer is **Giọng chính**; later recordings create independent voice layers instead of replacing one main vocal take.
- Additional layers are immediately materialized as editable timeline clips using the existing latency-compensated placement.
- Punch recording targets an existing voice layer instead of creating an overdub layer.
- Failed recording setup removes an empty auto-created layer so projects do not accumulate ghost tracks.
- Playback, mixer and export reuse the existing all-track planners/renderers, so independent layers overlap without a second audio engine.
- Existing direct callers can still use the legacy `NewLayer` request for compatibility.

## Phase 2 — layer controls and recording intent ✅

Implemented:

- Per-layer management is collapsed behind **Quản lý lớp** so the normal Studio surface stays simple.
- Voice roles: **Chính**, **Bè**, **Phụ**, **Song ca / khác**.
- Rename, reorder, duplicate and delete controls.
- Custom names are shown consistently in Mixer and Timeline.
- Duplicate is non-destructive: it reuses source assets with new clip identities instead of copying audio files.
- Delete keeps source assets and participates in the existing Undo/Redo history.
- Beat remains locked out of voice-layer edits and cannot be reordered through vocal layers.
- Track-management edits are blocked while recording/busy, without disabling the existing realtime mixer controls.
- Punch recording remains available even if the original main-vocal track was renamed, reclassified or deleted, as long as an editable recorded layer remains.
- **Lớp sắp thu** is chosen before REC: **Giọng chính**, **Giọng bè**, **Giọng phụ** or **Song ca / khác**. Normal REC keeps its existing permission flow and creates the new independent layer with the selected role and an accessible generated name.
- **Lớp đang thao tác** is a project-scoped shared state used by clip selection, the pre-record controls, Mixer and punch targeting.
- Selecting a clip synchronizes the working layer to that clip's track; explicitly selecting a working layer clears the old clip selection so the UI cannot silently carry two conflicting targets.
- TalkBack announces both the next-recording role and the active working layer through state descriptions/live regions.

Still optional and device-dependent rather than a blocking Phase 2 requirement:

- Per-layer monitor/input preferences should only be added where Android routing can make the behavior reliable and understandable on the target device.

## Phase 2.5 — accessible clip positioning ✅

Implemented instead of drag/drop as a required editing path:

- Timeline navigation uses selectable **5 s / 1 s / 100 ms / 10 ms** steps with large Back/Forward buttons.
- The current playhead time is exposed through a polite accessibility live region so TalkBack can announce position changes.
- Clip selection announces track name, clip number, start time and duration.
- The selected clip announces its start, end and duration, and updates that announcement after movement.
- **Tới đầu đoạn** and **Tới cuối đoạn** place the playhead at exact clip boundaries for preview, split, trim and punch workflows.
- Clip movement uses the same **5 s / 1 s / 100 ms / 10 ms** step model instead of requiring a drag gesture.
- **Đưa đầu đoạn tới vị trí đang nghe** aligns the clip start to the current playhead without dragging.
- Waveform tap remains available as a convenience for sighted users, but the UI explicitly states that tapping/dragging is not required.
- Movement stays non-destructive, uses the existing `StudioEditEngine.move`, preserves source audio and participates in Undo/Redo.

## Phase 3 — rhythm and vocal production

Current dependency order:

1. Add persisted musical-key/scale metadata beside the existing BPM and beats-per-bar settings, with backward-compatible project codec defaults.
2. Build a deterministic beat-grid utility from timeline sample rate, BPM, time signature and an explicit grid origin. Keep manual BPM override even after analysis is added.
3. Add beat/key analysis as suggestions rather than silently overwriting project metadata. The review UI must announce the detected BPM/key, confidence and the value that will be applied.
4. Add semi-automatic vocal alignment as a suggested offset. Review must support the same accessible **5 s / 1 s / 100 ms / 10 ms** fine-tuning model before Apply/Undo.
5. Add pitch correction with explicit key/scale controls and formant-aware shifting. The first version should be controllable and reversible before any “one tap” automatic mode.
6. Add harmony generation on top of the safe duplicate/layer system, with each generated harmony remaining an independent layer with its own pan/volume.
7. Extend the existing EQ/compressor/reverb/Spatial chain only where the current Studio effects do not already cover the requested sound.

## Phase 4 — final production workflow

- Region selection through named clip/region lists, boundary controls, snapping presets and fine time-step movement. Dragging may remain optional for sighted users but must never be required.
- Accessible start/end markers whose time values and state changes are announced to TalkBack.
- Loudness normalization and de-pop/crossfade defaults across edits.
- A/B preview for derived vocal processing with clearly announced active version.
- Final mix and stem export validation across long projects and many simultaneous vocal layers.
- Revisit system-picker format validation so Studio can give a clear unsupported-format message while keeping modern SAF/content-URI handling rather than requiring broad storage access or absolute source paths.

## Validation completed

Phase 1 feature verification:

- GitHub Actions `31662633869`: project verification, unit tests, lint, debug APK, native Studio packaging and signature verification all passed.
- Standard PR #40 workflow `31663157244`: build, APK inspection, signature, lint and unit tests all passed on the integrated Studio head.

Phase 2 track management verification:

- GitHub Actions `31663560484`: first full track-management implementation passed project verification, unit tests, lint, build, native packaging and APK signature verification.
- GitHub Actions `31664012208`: edge-case hardening passed the same full verification after fixing punch-without-main-vocal and safe duplicate source identity.

Accessible positioning verification:

- GitHub Actions `31684859962`: project verification, accessible editing unit tests, full unit suite, lint, debug APK, native Studio/Spatial packaging and APK signature verification all passed.

Recording-role and shared-working-track verification:

- Standard workflow `31687971661` passed verify, debug APK, native inspection, signature, lint and unit tests for **Lớp sắp thu**.
- Standard workflow `31689266238` passed the same full suite for **Lớp đang thao tác** after an earlier validation run correctly caught and led to a fix for an incomplete unit-test clip fixture.

Validation-only pull requests were closed without merging to `main`. The Studio feature commits were merged only into `agent/studio-foundation-native-audio-core`, keeping PR #40 Draft until device-level validation is complete.
