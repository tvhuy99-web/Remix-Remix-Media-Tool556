# Studio multivocal rollout plan

## Goal

Upgrade Studio into an independent multi-vocal production workspace while preserving punch recording, latency compensation, recovery, non-destructive editing and backward-compatible projects.

Accessibility is a product constraint, not a later polish pass. Core workflows must remain fully operable without drag gestures, precise waveform tapping or visual-only state.

## Phase 1 — independent overdub layers ✅

Implemented:

- Normal **Thu giọng** creates a fresh independent voice layer instead of replacing one main-vocal take.
- The first layer is **Giọng chính**; later layers can overlap for backing vocals, duet and ad-lib work.
- New layers are immediately materialized as latency-compensated editable clips.
- Punch recording targets an existing voice layer and does not create an accidental overdub layer.
- Playback, Mixer and export reuse the existing all-track planner/renderers.
- Failed recording setup removes empty auto-created layers.

## Phase 2 — layer controls and recording intent ✅

Implemented:

- **Quản lý lớp**: role, rename, reorder, duplicate and delete.
- Roles: **Chính**, **Bè**, **Phụ**, **Song ca / khác**.
- Duplicate is non-destructive and reuses source assets with new clip identities.
- Delete preserves source assets and participates in Undo/Redo.
- **Lớp sắp thu** is selected before REC and the created layer carries that role immediately.
- **Lớp đang thao tác** is shared by clip selection, pre-record controls, Mixer and punch targeting.
- TalkBack announces recording role and working layer state.
- Beat remains locked out of voice-layer management.

Optional, device-dependent follow-up:

- Per-layer monitoring/input preferences only where Android routing can make the behavior reliable.

## Phase 2.5 — accessible clip positioning ✅

- Playhead and clip movement use selectable **5 s / 1 s / 100 ms / 10 ms** steps.
- TalkBack receives playhead and selected-clip position updates through live regions/state descriptions.
- **Tới đầu đoạn**, **Tới cuối đoạn** and **Đưa đầu đoạn tới vị trí đang nghe** provide exact non-drag editing paths.
- Waveform tap/drag remains optional for sighted users.
- Movement stays non-destructive through `StudioEditEngine.move` and Undo/Redo.

## Beat import hardening ✅

- Beat input accepts MP3, WAV, M4A and FLAC.
- Extension validation uses known MIME types as fallback when document providers omit extensions.
- Unsupported formats fail early with a Vietnamese actionable message.
- Studio keeps SAF/content URI + persisted read permission; broad storage permission and absolute source paths are not required.

## Phase 3 — rhythm and vocal production

### Foundation ✅

- Persisted optional root note + Major/Minor metadata with safe defaults for older project JSON.
- Persisted `gridOriginFrame`, the timeline frame treated as beat 1 of bar 1.
- Deterministic `StudioBeatGrid` for beat frames, nearest beat, bar/beat index and bounded marker ranges.

### 3.1 Accessible musical controls ✅

- **Nhịp & tông** exposes manual BPM, all 12 pitch classes and Major/Minor.
- Beat can be previewed and **Đặt phách 1 tại vị trí đang nghe** stores an exact timeline grid origin.
- Suggestions and save state are announced through TalkBack semantics/live regions.
- Existing Pro voice saves preserve musical key and grid-origin metadata.

### 3.2 Automatic BPM + key suggestions ✅ first production version

- BPM analysis runs offline on the prepared 48 kHz beat PCM using onset-envelope autocorrelation.
- Key analysis uses Goertzel chroma energy and Major/Minor tonal profiles.
- Results contain confidence values and remain suggestions until the user explicitly chooses them and saves.
- Analyzer never silently overwrites project metadata.

### 3.3 Semi-automatic vocal alignment ✅ first production version

- The selected vocal clip is analyzed for multiple significant onset points.
- Candidate timing offsets are scored against the persisted half-beat grid.
- Review announces suggested offset, confidence and average timing error before/after.
- User can fine-tune with **5 s / 1 s / 100 ms / 10 ms** steps before Apply.
- Apply uses the existing non-destructive move edit path and can be undone immediately.
- This phase intentionally shifts clips only; it does not time-warp or stretch vocal audio.

### Next dependency order

1. Pitch correction / Auto-Tune with explicit key/scale, strength controls and formant-aware shifting. Keep it reversible before adding a one-tap mode.
2. Harmony generation on top of independent layers, with each generated harmony retaining its own pan/volume/mute/solo controls.
3. Extend existing EQ/compressor/reverb/Spatial processing only where current Studio effects do not cover the requested sound.

## Phase 4 — final production workflow

- Named region selection, boundary controls and snapping presets with full TalkBack operation.
- Loudness normalization plus de-pop/crossfade defaults across edits.
- Accessible A/B preview for derived vocal processing.
- Long-project and many-layer export/stem validation.
- Device-level validation for TalkBack, headphone routing, latency, overdub and Punch.

## Validation completed

- `31662633869`: Phase 1 multi-vocal foundation.
- `31663560484`, `31664012208`: Phase 2 track-management and edge-case hardening.
- `31684859962`: accessible non-drag positioning.
- `31687971661`: role before REC.
- `31689266238`: shared working-track/Punch.
- `31689906367`: integrated Phase 2 PR #40 head.
- `31690303731`: key/grid foundation.
- `31691074221`: beat-format validation.
- `31698189515`: Phase 3 musical UI, BPM/key suggestions, semi-auto vocal alignment, metadata preservation, full build/native/signature/lint/unit-test validation.

Validation-only pull requests targeting `main` were closed without merging. Studio feature commits are merged only into `agent/studio-foundation-native-audio-core`; PR #40 remains Draft until device-level validation is complete.
