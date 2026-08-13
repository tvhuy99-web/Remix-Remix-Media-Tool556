# Studio multivocal rollout plan

## Goal

Upgrade Studio full-take recording from replacement takes on one vocal track to independent vocal layers that can play, mix, edit and export at the same time, while keeping punch recording, latency compensation, recovery and existing projects compatible.

## Phase 1 — independent overdub layers ✅

Implemented:

- Normal **Thu giọng** requests a fresh recording layer.
- The first layer is **Giọng chính**; later layers are named **Giọng 2**, **Giọng 3**, and so on.
- Additional layers are immediately materialized as editable timeline clips using the existing latency-compensated placement.
- Punch recording targets the selected clip's track when possible.
- Failed recording setup removes an empty auto-created layer so projects do not accumulate ghost tracks.
- Playback, mixer and export reuse the existing all-track planners/renderers, so independent layers overlap without a second audio engine.
- Existing callers that do not send a recording-target request keep the old first-VOCAL behavior for compatibility.

## Phase 2 — layer controls ✅ core complete

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

Still useful before the advanced DSP phases:

- Choose the intended role for the *next* recording before REC, instead of classifying it afterward.
- Add an explicit selected-track state shared by Timeline, punch recording and Mixer.
- Consider per-layer monitor/input preferences only on devices where Android audio routing makes them reliable.

## Phase 3 — rhythm and vocal production

Planned in this order:

1. Beat-grid analysis plus BPM/key metadata, keeping manual BPM override.
2. Semi-automatic vocal alignment with visible suggested offsets before any destructive-looking automatic timing correction.
3. Pitch correction with key/scale controls and formant-aware pitch shifting.
4. Harmony generation built on the now-safe duplicate/layer system.
5. Extend the existing EQ/compressor/reverb/Spatial chain only where the current Studio effects do not already cover the requested sound.

## Phase 4 — final production workflow

- Region drag/drop editing with snapping and fine movement.
- Loudness normalization and de-pop/crossfade defaults across edits.
- A/B preview for derived vocal processing.
- Final mix and stem export validation across long projects and many simultaneous vocal layers.

## Validation completed

Phase 1 feature verification:

- GitHub Actions `31662633869`: project verification, unit tests, lint, debug APK, native Studio packaging and signature verification all passed.
- Standard PR #40 workflow `31663157244`: build, APK inspection, signature, lint and unit tests all passed on the integrated Studio head.

Phase 2 verification:

- GitHub Actions `31663560484`: first full track-management implementation passed project verification, unit tests, lint, build, native packaging and APK signature verification.
- GitHub Actions `31664012208`: edge-case hardening passed the same full verification after fixing punch-without-main-vocal and safe duplicate source identity.

Temporary patch/verification files were kept out of the clean Phase 2 commit. Only the verified Studio source and tests were merged into the Studio foundation branch.
