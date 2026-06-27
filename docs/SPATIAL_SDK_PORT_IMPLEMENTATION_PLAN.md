# Spatial Camera Panel Implementation Notes

`apps/spatial-camera-panel-android` is a public Quest platform adapter for Meta
Spatial SDK panel behavior. It is intentionally separate from the native
OpenXR/Vulkan renderer and from downstream private effect stacks.

## Owned Here

- Spatial SDK feature registration and one Compose-backed control panel.
- Panel placement, panel headlock, controller routing, and validation markers.
- Low-rate app-private JSONL records for participant/session setup, Polar H10
  intake, ECG mirroring, block events, foreground events, and questionnaires.
- Raw Camera2/AHardwareBuffer and public blur/projection validation probes.
- Generic driver-profile handoff records using `profile-a` through `profile-d`
  and scalar `driver0_value01` / `driver1_value01` values.
- Public deterministic native hand-anchor particle smoke tests over resident
  hand meshes.

## Not Owned Here

- Private effect formulas, tuned profiles, coupling kernels, or study-specific
  names.
- High-rate hand mesh, particle, field, or shader payload transport through
  Kotlin/Java JSON.
- Native renderer presentation authority. Vulkan/OpenXR presentation remains in
  `apps/native-renderer-android`.
- Private camera-stack layers beyond public raw and blur/projection probes.

## Static Contract

The public static gate for this lane is
`tools/checks/Test-SpatialCameraPanelAndroidStatic.ps1`. It checks the package
rename, JNI bridge names, raw/blur camera probe markers, generic driver-profile
schemas, and the absence of private effect vocabulary in the public Spatial
Camera Panel lane.

## Build

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1 -Build
```

Build output goes to
`target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk`.
