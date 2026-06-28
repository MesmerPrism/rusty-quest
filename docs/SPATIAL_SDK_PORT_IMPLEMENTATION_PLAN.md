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
- Public seven-slot camera guide multi-stack contract with generic final,
  guide blur, post-blur guide, and depth diagnostic slots.
- Public guide-target/pass manifests and generic separable 5-tap guide blur
  shader compilation for future multi-pass Spatial camera routes.
- Generic public guide-target and guide-pass resource scaffold for the
  multi-pass route, including public blur pipeline creation and a generic blur
  record function kept outside camera stream and surface-particle proof
  modules.
- Optional opaque guide shader build hook that compiles six pass variants and
  reports pass byte counts without committing downstream shader source.
- Opaque guide descriptor shape for five guide targets at bindings 4-8,
  separate from the one-texture public blur descriptor path.
- Optional opaque guide pipeline creation when all six pass variants are
  present, with the raw camera command buffer scheduling the pass graph.
- Optional guide-pass scheduling from the raw camera command buffer: opaque
  analysis passes use downstream pipelines, while the four blur passes use the
  public blur pipeline over packed stereo guide targets.
- Optional opaque projection pipeline over camera, packed guide, and generic
  fallback depth descriptors. Downstream projection shader source and effect
  values are build environment inputs, not committed public payloads.
- Final opaque projection keeps the Spatial SDK `SceneQuadLayer` as the
  carrier and clips Vulkan output to the packed native effective target rects.
  It must not resize or reposition the Spatial quad into a half-eye full-scale
  projection.
- Strict headset smoke support for public multi-stack projection activation:
  `-RequirePublicMultiStackProjection` requires guide targets, public blur,
  opaque guide/projection pipelines, fallback depth, projection-applied, and
  layer-cycle elapsed markers, plus the packed target-rect markers and camera
  stack particle-suppression markers.
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
- Opaque camera-stack layers beyond public raw, guide blur, depth diagnostic,
  and projection-carrier probes.

## Static Contract

The public static gate for this lane is
`tools/checks/Test-SpatialCameraPanelAndroidStatic.ps1`. It checks the package
rename, JNI bridge names, raw/blur camera probe markers, public multi-stack
receipts, public guide-target allocation markers, guide-pass resource markers,
public blur pipeline/record-function markers, public blur shader compilation
hooks, shader availability byte-count markers, opaque guide pass variant
markers, opaque guide descriptor-shape markers, generic driver-profile schemas,
packed stereo guide schedule markers, and the absence of private effect
vocabulary in the public Spatial Camera Panel lane. The public multi-stack
receipts stay separate from camera stream and surface-particle proof ownership.
The static gate also checks the optional opaque projection pipeline and generic
depth fallback resources without requiring downstream shader source. A compact
projection-evidence native marker keeps target-rect, projection-applied, layer
cycle, and fallback-depth proof outside Android logcat line-length truncation.
Static drift checks treat public multi-stack receipts as their own contract.

## Build

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1 -Build
```

Build output goes to
`target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk`.

Latest strict Quest 3S evidence:

```text
local-artifacts\spatial-camera-panel-headset\20260628-161204-camera-hwb-projection-smoke\evidence-summary.json
APK_SHA256=66ED720405FA857A0355B91225A563B6FA9043069A8AB08BE67B43C4F7BE0954
```

That run passed the raw Camera2/AHardwareBuffer gate and the strict public
multi-stack projection gate. The public summary records
`public_multistack_projection_applied=true`,
`public_multistack_layer_cycle_enabled=true`, opaque guide/projection pipeline
readiness, fallback depth readiness, packed native target-rect clipping, and
camera-stack particle suppression without committing downstream shader source
or private effect formulae.
