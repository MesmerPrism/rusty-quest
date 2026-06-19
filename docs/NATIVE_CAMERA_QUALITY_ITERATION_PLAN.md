# Native Camera Quality Iteration Plan

This plan turns the public native camera-stack audit into implementation slices
for `apps/native-renderer-android`. It stays public: private downstream visual
effects, private tuning, and Morphovision-specific behavior are out of scope.

## Current Baseline

- Direct-HWB camera profiles force Camera2/HWB projection while disabling hand,
  SDF, private-layer, and direct-border overlays for raw camera inspection.
- The default direct-HWB baseline is Android-suggested YCbCr with a UNORM
  swapchain preference.
- The Breathing Room PMB scale profile is profile-owned rather than default:
  it keeps `camera.output=guide-public` for the stretch border route, disables
  guide blur, requests `guide.resolution=camera-native`, and pins forced
  BT.601 narrow YCbCr plus UNORM swapchain markers to match the documented raw
  stack color behavior.
- Comparison profiles cover forced BT.601 limited conversion, low-noise 30 FPS
  Camera2 requests, low-latency 60 FPS Camera2 requests, 1280x960 reader size,
  and hold-image-until-GPU-fence synchronization.
- Camera frames are acquired from NDK `ACameraManager` streams, retained as
  `AHardwareBuffer` handles, imported into Vulkan external images, and rendered
  through cached descriptors.
- The conservative hold-sync path retains sampled `AImage` leases until the
  submitted Vulkan frame-slot fence completes. The lower-latency
  `AImage_deleteAsync`/sync-fd diagnostic path is active for ImageReader
  acquire/release API coverage, while full Vulkan external-semaphore ownership
  remains explicitly marked pending.

## Scope

This iteration improves public raw camera quality diagnostics and buffer
lifetime safety:

1. protect generic import-cache eviction from destroying in-flight imports;
2. expose queue-depth controls and queue-exhaustion markers;
3. correlate Camera2 result metadata with sampled frames;
4. add additional support-gated Camera2 profile selection;
5. rank reader-size fallback with timing/aspect evidence;
6. add dataspace and luma/range diagnostics;
7. design and later implement full async acquire/release sync-fd ownership;
8. add stereo frame pairing policy after metadata is rich enough to score it.

## Non-Scope

- Private Morphovision effect formulas, tuning, profiles, Colorama, or
  distortion behavior.
- Replacing the direct-HWB baseline with a private effect path.
- Importing Rusty Vision or Makepad runtime dependencies.
- Treating community observations as device truth without headset validation.

## Mitigation Map

| Risk | Borrowed lesson | Rejected overreach | Mitigation path | Validation |
| --- | --- | --- | --- | --- |
| `camera-import-cache-inflight-eviction` | Vulkan/AHB object lifetime and submitted-frame state must stay distinct. | Do not disable the import cache or rebuild descriptors every frame. | Make normal LRU eviction skip in-flight hardware-buffer ids and log skipped/applied eviction counts. | `cargo test -p rusty-quest-native-renderer-android-native`; `tools/check_all.ps1` |
| `imagereader-queue-starvation` | `acquireLatestImage` needs queue headroom, especially when holding images through GPU fences. | Do not hard-code a single larger max-image count as the only answer. | Add runtime `readerMaxImages` profile parsing, clamp it, and expose 4/6/8 A/B profiles and markers. | Runtime profile dry-runs plus headset log markers for acquire errors and lease backlog. |
| `camera-result-not-source-of-truth` | Camera2 request success is not proof that sampled buffers used the requested state. | Do not build a broad telemetry database before the runtime has markers. | Log result-side exposure, sensitivity, frame duration, AE/AWB state, NR, edge, and sync frame fields. | Static marker checks; headset profile runs under fixed scene/lighting. |
| `preview-template-quality-ceiling` | Android preview templates may prioritize preview cadence over quality. | Do not replace the baseline template globally. | Add a separate `TEMPLATE_RECORD` low-noise A/B profile. | Compare preview-low-noise-30 vs record-low-noise-30. |
| `fps-exact-match-blindness` | Supported AE ranges differ by device and OS. | Do not silently widen every profile. | Select exact preferred range first, then nearest support-gated range with markers. | Marker checks plus result-side frame duration/exposure correlation. |
| `reader-size-list-order` | Stream configuration list order is not a quality ranking. | Do not choose largest resolution by default. | Rank fallbacks by tested preferred sizes, aspect fit, target FPS feasibility, and min frame duration. | Profile A/B for 1280x1280, 1280x960, and closest-supported. |
| `range-brightness-ambiguity` | YCbCr model/range and swapchain color format can shift perceived brightness. | Do not make forced BT.601 the global default without proof. | Keep Android-suggested default, pin BT.601/UNORM only in profiles that have accepted visual evidence, and add dataspace/luma diagnostics. | Gray/black/white chart headset run plus GPU luma summaries. |
| `guide-downsample-artifacts` | A low-resolution guide texture is useful for blur diagnostics but can visibly damage a no-blur raw camera route. | Do not remove the low-resolution guide blur path; it remains a useful diagnostic and performance route. | Add a profile-owned guide resolution policy so no-blur Breathing Room can use camera-sized guide textures while blur profiles keep 384x384 by default. | `guideGraphResolutionPolicy`, `guideGraphDownsampleResolution`, and in-headset raw-camera inspection. |
| `stereo-latest-latest-shimmer` | Separate left/right streams may not arrive in lockstep. | Do not drop frames until metadata proves a useful threshold. | Add nearest-timestamp pairing after result metadata and frame-age markers land. | Compare latest/latest vs nearest-pairing with pair delta and temporal-diff evidence. |

## Implementation Slices

### Slice 1: Import Cache And Queue Headroom

- Raise the default camera import cache budget above the two-reader queue pool.
- Make generic LRU eviction skip imports referenced by submitted frame slots.
- Track and log cache eviction attempts, applied evictions, in-flight skips, and
  cache limit.
- Add `debug.rustyquest.native_renderer.camera.reader_max_images`, parsed and
  clamped to a safe diagnostic range.
- Add direct-HWB hold-sync profiles for `readerMaxImages=6` and
  `readerMaxImages=8`.

### Slice 2: Result Metadata Correlation

- Extend capture-result markers with exposure time, sensitivity, frame duration,
  AE state, AWB state, NR mode, edge mode, and sync frame number when exposed.
- Store recent result metadata per camera side so sampled frames can report the
  nearest result snapshot.
- Keep logging bounded and marker-first; do not introduce a telemetry database.

Implemented shape: each camera side keeps a bounded recent-result ring, capture
callbacks store sensor timestamp/exposure/sensitivity/frame duration/AE/AWB/NR/
edge/sync fields, acquired HWB frames report the nearest result correlation,
and the timing scorecard carries left/right result-correlation fields.

### Slice 3: Camera2 Profile Selection

- Add a `direct-low-noise-record-30` profile that uses `TEMPLATE_RECORD`.
- Change AE FPS selection from exact-only to exact-first nearest-supported,
  with explicit requested, selected, and applied markers.
- Keep preview/direct-baseline as the canonical default.

Implemented shape: the public quality profile parser accepts
`direct-low-noise-record-30`, the native Camera2 path chooses
`TEMPLATE_RECORD` only for that profile, and `camera-request-profile` logs
`template`, requested/selected/applied AE FPS range, and exact-vs-nearest
selection status. The existing preview/direct-baseline profile remains the
default.

### Slice 4: Reader Size Ranking

- Parse min-frame-duration stream configurations when available.
- Rank explicit and fallback PRIVATE reader sizes by preferred tested sizes,
  target aspect, and target frame duration feasibility.
- Log the ranking reason.

Implemented shape: Camera2 capabilities parse
`ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS`, expose PRIVATE min-frame
duration markers, and select reader-size fallbacks with a deterministic ranking
over target-FPS feasibility, preferred 1280x1280/1280x960 sizes, aspect error,
size distance, and min frame duration. `camera-start` reports the selected size,
ranking reason, min-frame duration, target FPS, and feasibility marker.

### Slice 5: Dataspace And Objective Range Diagnostics

- Log image dataspace when available.
- Add an opt-in GPU luma/range diagnostic pass after direct-HWB sampling, with
  per-eye luma mean/range/high-frequency counters reported only in diagnostics.

Implemented shape: acquired `AImage` frames dynamically query
`AImage_getDataSpace` when the symbol exists and report `imageDataspace` plus
status on HWB frame markers and left/right dataspace fields in the timing
scorecard. The opt-in
`debug.rustyquest.native_renderer.camera.luma_diagnostic.enabled` path records
a native Vulkan compute pass over the resident direct-HWB image views, samples a
64x64 grid per eye, and reports per-eye luma mean/min/max and high-frequency
ratio from a frame-slot readback buffer. The baseline leaves this disabled.

### Slice 6: Async Sync-FD Producer/Consumer Path

- Add a separate `camera.sync_mode=delete-async-release-fence` implementation
  only after Slice 1-5 markers make headset regressions measurable.
- Use async ImageReader acquire/release APIs, Vulkan external semaphores, and
  explicit ownership transfer.
- Keep hold-sync as the fallback diagnostic path.

Implemented shape: `delete-async-release-fence` is now an active diagnostic
mode that uses `AImageReader_acquireLatestImageAsync`, reports acquire fence fd
presence, closes observed acquire fds to avoid leaks, and releases images with
`AImage_deleteAsync`. Markers deliberately report
`active-diagnostic-sync-fd-observed-vulkan-semaphore-pending` because the public
Vulkan device setup does not yet enable/import external semaphore fds. The
hold-sync path remains the fence-backed safety path.

### Slice 7: Stereo Pairing

- Add a profile-controlled pairing policy after result metadata exists.
- Compare latest/latest with nearest-timestamp pairing under fixed-scene
  headset evidence.

Implemented shape: `debug.rustyquest.native_renderer.camera.stereo_pairing`
defaults to `latest-latest` and can be set to `nearest-timestamp`. The
nearest-timestamp policy keeps a bounded four-frame per-eye ring only when
requested, chooses the left/right pair with the smallest sensor timestamp
delta, and reports `stereoPairingPolicy` alongside `stereoPairDeltaNs` in the
timing scorecard.

## Validation

Minimum validation for each source slice:

```powershell
cargo test -p rusty-quest-native-renderer-android-native
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
```

APK/headset validation remains a separate gate and must use the repo-local
Quest workflow and Agent Board lease rules.
