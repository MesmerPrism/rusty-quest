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
- Optional public stereo-video projection for the raw camera probe: Java
  `MediaCodec` decodes an explicitly staged side-by-side or top-bottom source
  into a native `AImageReader`/`AHardwareBuffer`, and the same Vulkan WSI pass
  composites it behind the camera projection on the existing Spatial SDK
  `SceneQuadLayer`.
- Public seven-slot camera guide multi-stack contract, including generic final,
  guide blur, post-blur guide, and depth diagnostic slots.
- Generic driver profiles `profile-a` through `profile-d` with bounded
  `driver0_value01` and `driver1_value01` handoff markers.
- Native hand-anchor particle smoke tests that use public deterministic
  resident-mesh anchor billboards.

## Boundary

This app does not own high-rate renderer authority. It does not move hand mesh
frames, particle arrays, field buffers, private shader payloads, or replay
sequences through Kotlin/Java JSON. The public camera stack in this lane is raw
and blur/projection/video-composition validation only. The app does not package
video files and does not own private media sources; video projection is enabled
only through runtime properties or intent extras that point at an explicitly
staged app-private or device-local file. Opaque downstream
analysis/projection slots, visual semantics, effect formulas, coupling kernels,
and tuned parameter profiles belong outside Rusty Quest.

## Headset Evidence

The 2026-06-28 Quest 3S raw-color camera projection smoke passed the camera
stack gate: SDK-owned `SceneQuadLayer`, native Vulkan WSI, camera 50/51 streams,
target-rect clipping, and stereo output all rendered. A stricter private-shader
build of the public multi-stack route also passed on 2026-06-28 with
`-RequirePublicMultiStackProjection`: five guide targets allocated, public blur
runtime ready, opaque guide/projection pipelines ready, fallback depth ready,
`publicMultiStackProjectionApplied=true`, and
`publicMultiStackLayerCycleEnabled=true`. The strict run preserves the native
projection footprint by keeping the Spatial SDK quad as the carrier and clipping
Vulkan output to the packed native target rects; it also suppresses the surface
particle renderer while the camera stack is active.

Latest strict evidence:
`local-artifacts\spatial-camera-panel-headset\20260628-161204-camera-hwb-projection-smoke\evidence-summary.json`;
APK SHA-256 `66ED720405FA857A0355B91225A563B6FA9043069A8AB08BE67B43C4F7BE0954`.

A separate vergence/focus mismatch remains: when the camera projection is
brought into comfortable focus, Meta system menus can appear doubled or soft.
Treat that as a future Rusty Lattice / projection-space alignment
investigation, not as a camera acquisition, HWB import, WSI carrier, or public
multi-stack failure.

For that investigation, the raw Camera2/HWB projection probe keeps the Spatial
SDK quad carrier at a fixed 1.0m default distance and locks the opposed
per-eye horizontal UV offset to the current default `0.046320`, captured from
a live Quest 3S headset readback on 2026-06-28 where the camera projection and
Meta performance HUD aligned simultaneously. Left-stick Y is now reserved for
panel scrolling, not projection offset tuning. Runtime readback uses
`projectionTargetStereoHorizontalOffsetUv`, `projectionTargetLeftOffsetUv`,
`projectionTargetRightOffsetUv`, and the effective packed rect markers. While
this projection probe is active, the hidden surface-particle panel no longer
writes the shared native panel-basis state on each scene tick; the camera
projection plane is the native panel-pose authority.

The same projection probe also exposes right-controller Y-axis control over
the packed projection target scale. This adjusts the live target rect around
each eye center while keeping the Spatial SDK carrier and the packed stereo
mapping stable. Runtime readback uses `projectionTargetLiveScale`,
`projectionTargetScaleJoystickControlsEnabled=true`, and
`right-stick-y-projection-target-scale`. Right-stick X is intentionally
ignored by the activity and swallowed when it is the only active axis so it no
longer drives panel scale or distance. Left-stick X remains the panel
horizontal placement control while a panel is open; left-stick Y is left
unconsumed so the panel can scroll.

When the camera/video stack is active, the right primary button opens a
front-of-camera private-layer control panel instead of the participant workflow
panel. That panel mirrors the native private layer selector: seven generic
layer choices, live projection-area scale, and live depth-alignment X/Y/scale
controls. It is registered as `spatial_private_layer_panel`, renders at
`panelRenderOrder=front-of-camera-video` with the `spatial-sdk-layer` panel
render path, and updates the public opaque projection route through
`nativeUpdatePrivateLayerOverride` and
`nativeUpdatePrivateLayerDepthAlignment`.

For controller modality, this APK follows the official Spatial SDK panel sample
shape: optional hands-and-controllers declarations are present, controller
render models are requested, and the default VR input backend is Interaction
SDK pointer mode. The debug property
`debug.rustyquest.spatial_camera_panel.vr_input_system=simple_controller` can
still be used for controlled headset A/B tests, but the normal path is
`interaction_sdk`. If no local `AvatarBody` hand entity reports an active
`ControllerType.CONTROLLER`, the app should treat that as an app-owned
readiness issue rather than expecting Horizon to block launch.

The previous multimodal probe remains in source for controlled follow-up tests:
`debug.rustyquest.spatial.multimodal_input.enabled=true` can make
`registerRequiredOpenXRExtensions()` declare
`XR_META_simultaneous_hands_and_controllers` and
`XR_META_detached_controllers` before Spatial SDK starts OpenXR. The native
receipt then makes a best-effort resume request and logs support, function
resolution, and resume status under `channel=spatial-multimodal-input`. That
path is disabled by default because the normal panel path uses Spatial SDK
Interaction SDK pointer input without native multimodal extension forcing.

## Native Receipt Source Map

- `app/src/main/.../SpatialCameraPanelActivity.kt` remains the Spatial SDK
  Activity facade: lifecycle, panel registration, scene tick routing, JNI
  calls, and route orchestration.
- `app/src/main/.../SpatialSdkLaneBoundary.kt` records the explicit route
  boundaries. Spatial SDK layer/panel primitives are the carrier substrate;
  experiment panel, camera projection, surface particles, and debug probes are
  separate consumers of that carrier.
- `app/src/main/.../SpatialPublicMultiStack.kt` mirrors the public seven-slot
  camera guide multi-stack receipt fields for Kotlin-side start, carrier, and
  placement markers. It marks opaque downstream slots inactive in this public
  app.
- `app/src/main/.../ExperimentPanelController.kt` owns the Compose experiment
  panel UI and launcher UI. It may request panel visibility changes and
  low-rate particle-driver scalar updates, but it must not own camera frames,
  Vulkan WSI, SDK quad layers, or particle buffers.
- `app/src/main/.../SpatialCameraPanelModels.kt` owns shared panel placement,
  native-interop receipt, and low-rate control state models used by the
  Activity facade and panel UI.
- `app/src/main/.../SpatialAvatarHandVisualFeature.kt` owns suppression of the
  built-in Meta avatar hand visual so native/public hand visuals remain
  explicit.
- `native-receipt/src/camera_hwb_probe.rs` is the Android JNI facade and
  raw camera probe orchestration entry point.
- `native-receipt/src/camera_hwb_stream.rs` owns the Android Camera2 /
  `AImageReader` stream runtime, stereo camera 50/51 selection, private output
  size selection, and acquired `AHardwareBuffer` frame handoff.
- `native-receipt/src/camera_hwb_wsi.rs` owns the Vulkan WSI/resource helpers:
  surface-device selection, swapchain format/extent policy, sampled-HWB
  replacement import, descriptors, pipeline creation, command recording, and
  resource teardown.
- `native-receipt/src/camera_hwb_marker.rs` owns the raw camera probe marker
  channel and native log formatting helper.
- `native-receipt/src/camera_hwb_projection_target.rs` owns the public
  camera-projection target-rect constants, effective-rect formula,
  side-by-side packed UV rects, raw-color projection push constants, and marker
  field construction. Its host unit tests protect the target-rect behavior
  without requiring Android system libraries.
- `native-receipt/src/spatial_public_multistack.rs` owns the native receipt
  mirror for the public seven-slot camera guide multi-stack contract, including
  guide-target/pass manifests, public guide blur slots, and opaque downstream
  slot markers.
- `native-receipt/src/spatial_public_multistack_runtime.rs` owns the generic
  Vulkan guide-target and guide-pass resource scaffold for the public
  multi-stack contract: offscreen targets, render pass, framebuffers, sampler,
  descriptor layout/pool, sample descriptors, public blur pipeline creation,
  a generic public blur record function, and the opaque guide descriptor shape
  plus optional opaque guide pipeline creation used by downstream shader
  payloads. The guide scheduler packs both stereo eyes into the public five
  guide targets and keeps the four blur passes on the public blur pipeline. The
  optional opaque projection path owns only generic descriptor/pipeline plumbing
  plus a fallback depth descriptor; downstream shader source and effect values
  come from build environment inputs. Final opaque projection uses full
  packed-surface viewport state plus per-eye packed target-rect scissors, not a
  resized Spatial quad. It is intentionally separate from camera stream
  orchestration and surface-particle proof modules.
- `app/src/main/.../SpatialStereoVideoPlayback.java` is the optional Spatial
  video decode bridge. It resolves only an explicit runtime path, creates no
  default fixture path, and sends decoded frames to a native-created Surface.
- `native-receipt/src/spatial_video_projection_settings.rs`,
  `spatial_video_projection_native_stream.rs`, and
  `spatial_video_projection.rs` own the public stereo-video projection
  settings, AImageReader/AHardwareBuffer handoff, Vulkan import cache, and
  full-surface video draw that runs before the camera projection. Markers prove
  `nativeImageReader=true`, `javaHardwareBufferBridge=false`,
  `cpuPixelCopy=false`, same-surface composition, and preserved camera
  alignment.
- `native-receipt/shaders/public_guide_blur.frag.glsl` is the public generic
  separable 5-tap blur shader asset. Downstream opaque shader overrides are
  optional build inputs watched by the native receipt build script. Native
  receipts report compiled shader byte counts and whether opaque overrides were
  present; downstream shader contents remain outside this public app.
- `native-receipt/src/surface_particle_layer.rs`, `replay_hands.rs`, and
  `live_hand_joints.rs` remain Android-only surface-particle proof modules.

## Spatial SDK Lane Source Map

The Spatial SDK dependency is not a camera, particle, or experiment authority
by itself. Treat it as the Quest platform carrier for panels, layer placement,
surface creation, pose locking, sizing, and capability probes. Camera work
belongs in the Camera2/HWB projection modules, particle work belongs in the
surface-particle native modules, and panel/session work belongs in the Compose
panel controller plus store. Static validation checks that camera modules do
not reference particle internals, particle modules do not reference camera HWB
internals, and the panel controller does not directly own native start calls or
SDK quad/swapchain primitives.

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

Run the raw camera projection headset smoke with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1 `
  -Serial <quest-serial> `
  -ClearLogcat
```

The smoke enables `debug.rustyquest.spatial.camera_hwb_projection_probe`,
starts tag-filtered logcat before launch, captures the marker summary, window
state, and screenshot under `local-artifacts\spatial-camera-panel-headset`,
and leaves the projection running for visual inspection unless `-StopAfterRun`
is passed.

To include the optional public video background, stage the media on the device
or under the app-private files directory and pass the path at runtime:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1 `
  -Serial <quest-serial> `
  -ClearLogcat `
  -VideoPath <device-or-app-private-path> `
  -RequireSpatialVideoProjection
```

After building with downstream opaque shader env vars, require the public
multi-stack projection proof with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1 `
  -Serial <quest-serial> `
  -ClearLogcat `
  -StopAfterRun `
  -RequirePublicMultiStackProjection
```
