# Studio multivocal rollout plan

## Goal

Upgrade Studio full-take recording from replacement takes on one vocal track to independent vocal layers that can play, mix, edit and export at the same time, while keeping punch recording, latency compensation, recovery and existing projects compatible.

## Phase 1 — independent overdub layers (implemented in this branch)

- Normal **Thu giọng** requests a fresh recording layer.
- The first layer is **Giọng chính**; later layers are named **Giọng 2**, **Giọng 3**, and so on.
- Additional layers are immediately materialized as editable timeline clips using the existing latency-compensated placement.
- Punch recording targets the selected clip's track when possible and falls back to the main vocal track.
- Failed recording setup removes an empty auto-created layer so projects do not accumulate ghost tracks.
- Playback, mixer and export reuse the existing all-track planners/renderers, so independent layers overlap without a second audio engine.

## Phase 2 — layer controls

- Explicit add-layer controls for main vocal, backing vocal, ad-lib and duet roles.
- Rename, reorder, duplicate and delete track controls.
- Clear selected-track state shared by timeline, punch recording and mixer.
- Optional per-layer input/monitor preferences where the device supports them.

## Phase 3 — rhythm and vocal production

- Beat-grid analysis and BPM/key metadata.
- Semi-automatic vocal alignment before fully automatic timing correction.
- Pitch correction with key/scale controls and formant-aware pitch shifting.
- Harmony generation and duplicate/pan workflows.
- Preset chains for EQ, compression, echo, reverb, spatial audio and vocal character.

## Phase 4 — final production workflow

- Region drag/drop editing with snapping and fine movement.
- Loudness normalization and de-pop/crossfade defaults across edits.
- A/B preview for derived vocal processing.
- Final mix and stem export validation across long projects and many vocal layers.

## Validation completed for Phase 1

GitHub Actions run `31662633869` completed successfully with:

- `scripts/verify_project.py`
- `testDebugUnitTest`
- `lintDebug`
- `assembleDebug`
- APK native-library inspection including `libmediatool_studio.so`
- APK signature verification

The temporary patch/verification workflow used for this implementation was removed after the source patch passed, leaving the feature diff clean.
