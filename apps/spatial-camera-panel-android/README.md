# Spatial Camera Panel Android

This app is a public Meta Spatial SDK lane for headset panel validation and
low-rate driver-profile control. It packages a Spatial SDK/Compose panel under
`io.github.mesmerprism.rustyquest.spatial_camera_panel`.

## Public Scope

- Spatial SDK panel registration, placement, scaling, and headlock controls.
- Low-rate participant/session files, Polar H10 intake records, ECG mirroring,
  block timing, and questionnaire JSONL artifacts.
- Raw Camera2/AHardwareBuffer projection probes and public blur/projection
  receipts.
- Generic driver profiles `profile-a` through `profile-d` with bounded
  `driver0_value01` and `driver1_value01` handoff markers.
- Native hand-anchor particle smoke tests that use public deterministic
  resident-mesh anchor billboards.

## Boundary

This app does not own high-rate renderer authority. It does not move hand mesh
frames, particle arrays, field buffers, private shader payloads, or replay
sequences through Kotlin/Java JSON. The public camera stack in this lane is raw
and blur/projection validation only. Private downstream visual semantics,
effect formulas, coupling kernels, and tuned parameter profiles belong outside
Rusty Quest.

## Native Receipt Source Map

- `native-receipt/src/camera_hwb_probe.rs` is the Android JNI facade and
  Camera2/AHardwareBuffer-to-Vulkan WSI render orchestration for the raw camera
  probes.
- `native-receipt/src/camera_hwb_marker.rs` owns the raw camera probe marker
  channel and native log formatting helper.
- `native-receipt/src/camera_hwb_projection_target.rs` owns the public
  camera-projection target-rect constants, effective-rect formula,
  side-by-side packed UV rects, raw-color projection push constants, and marker
  field construction. Its host unit tests protect the target-rect behavior
  without requiring Android system libraries.
- `native-receipt/src/surface_particle_layer.rs`, `replay_hands.rs`, and
  `live_hand_joints.rs` remain Android-only surface-particle proof modules.

## Validation

Run the static gate:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1
```

Build with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1 -Build
```

The build wrapper writes
`target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk`
and `target\spatial-camera-panel-android\build-manifest.json`.
