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
- Optional public stereo-video projection behind the raw camera probe, using an
  explicitly staged runtime path, Java `MediaCodec`, native
  `AImageReader`/`AHardwareBuffer`, and the existing Spatial SDK
  `SceneQuadLayer` carrier. No video asset is packaged in the app.
- Spatial SDK staged 3D asset support for explicit GLB/GLTF mesh URIs. The
  public app owns runtime `Mesh` entity creation, transform/scale placement,
  and optional `Grabbable` controls, not source asset provenance.
- Packaged virtual room support for explicit GLXF scenes under app assets. The
  public app owns optional scene loading, skybox/IBL setup, and a fixed
  virtual-wall camera-quad placement mode; local sample room assets are launch
  inputs, not required public source assets.
- Public seven-slot camera guide multi-stack contract with generic final,
  guide blur, post-blur guide, and depth diagnostic slots.
- Public guide-target/pass manifests and generic separable 5-tap guide blur
  shader compilation for future multi-pass Spatial camera routes.
- Spatial scene-depth diagnostics mirror the native renderer permission and
  evidence vocabulary. The APK declares `horizonos.permission.USE_SCENE` and
  OpenXR permissions; headset smokes pregrant package-declared permissions and
  record the `USE_SCENE_DATA` app-op. The public depth layer keeps a fallback
  descriptor for unbound runs and can now bind real `XR_META_environment_depth`
  descriptors after the native passthrough prerequisite is active. Current
  headset evidence must distinguish `publicMultiStackDepthCurrentDescriptorSource`
  from fallback readiness and must require `environmentDepthValidData=true`
  plus nonzero valid sample counters before accepting real depth.
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
- Right-controller Y-axis input scales the packed projection target around each
  eye center. The live control is reported with
  `projectionTargetScaleJoystickControlsEnabled=true` and
  `right-stick-y-projection-target-scale`. Left-stick Y controls workflow-panel
  distance after the default stereo horizontal offset was locked in, and nudges
  the private layer panel's current free-transform distance when that panel is
  not actively palm-grabbed. Right-stick X is intentionally ignored/swallowed
  so it no longer drives panel scale or distance.
- The right primary button opens a generic `spatial_private_layer_panel` while
  the camera/video stack is active. The panel renders as a Spatial SDK mesh
  world-space object in front of the camera/video projection instead of as a
  compositor layer, exposes the seven generic layer choices, projection area
  scale, depth source policy (`mono-layer0`, `mono-layer1`, `eye-index`, or
  `compare`), and depth-alignment X/Y/scale controls, and updates native state
  through `nativeUpdatePrivateLayerOverride`,
  `nativeUpdatePrivateLayerDepthLayerPolicy`, and
  `nativeUpdatePrivateLayerDepthAlignment`. Movement is owned by the Spatial
  SDK entity `Grabbable(type = PIVOT_Y)` component, matching Meta's floating
  panel samples. Compose drag deltas remain disabled; the header handle is a
  visual affordance only, so pointer deltas cannot feed back into panel
  transforms. The panel is seeded once in front of the viewer and then left to
  the Spatial SDK as a free world-space grabbable; forced radial placement
  writes remain disabled while left-stick Y applies a direct distance nudge to
  the current SDK transform. A/trigger select is explicitly enabled for the
  Compose layer buttons, while controller squeeze/palm remains the grab path.
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
  video-composition, and projection-carrier probes.
- Raw source model assets, including FBX files. FBX can be used as a local host
  test input only after conversion to a staged GLB/GLTF runtime mesh.
- Local sample room exports, screenshots, or media used to package a headset
  proof build. The reusable source contract is the generic
  `spatial-sdk-packaged-virtual-room` loader plus marker surface.

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
The same gate protects the optional Spatial video path: it requires explicit
runtime controls, MediaCodec-to-native Surface decode, native AImageReader/AHB
handoff, Vulkan AHB import markers, no CPU pixel copy, no Java HardwareBuffer
bridge, and no packaged or hardcoded video media path.
The staged 3D asset gate is similarly generic: it requires a declared
`spatial-sdk-staged-3d-asset` module, explicit runtime mesh URI transport,
GLB/GLTF SDK mesh formats, raw-FBX conversion markers, and no packaged raw
source model files.
The packaged virtual room gate requires the declared
`spatial-sdk-packaged-virtual-room` boundary, runtime opt-in property, packaged
GLXF scene URI markers, explicit non-MRUK/non-passthrough-room markers, and the
right secondary/B button camera-projection wall/full-FOV toggle. With the room
enabled, the projection surface starts in the full-FOV viewer-locked mode and
reports
`projectionDefaultPlacementMode=viewer-pose-projection-locked-quad`,
`projectionRoomRenderOrder=projection-layer-over-virtual-room`, and
`legacyLauncherPanelSuppressed=true`. It also checks the private-layer panel
registration, right-stick projection target scale markers, placement-independent
layer override markers, front-of-camera panel ordering, and generic depth
alignment JNI bridge without allowing private effect vocabulary into this
public lane.

## Build

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1 -Build
```

For a build where the private-layer panel buttons visibly change the active
camera projection layer, pass the downstream opaque shader profile into the
build wrapper:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1 `
  -Build `
  -PrivateLayerProfilePath <path-to-private-layer-profile.json>
```

The profile and shader sources remain outside this public repo. A build without
those inputs keeps the public raw-camera fallback and can still prove panel
input plumbing, but it cannot prove visible layer switching.

For generic 3D asset validation, stage a GLB/GLTF or pass a local FBX plus a
converted GLB/GLTF export into the headset smoke wrapper:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1 `
  -Serial <quest-serial> `
  -AssetSourcePath <local-source-model.fbx> `
  -AssetConvertedMeshPath <converted-model.glb> `
  -EnableVirtualRoom `
  -RequireSpatialAssetModel `
  -RequireSpatialVirtualRoom
```

This records `channel=spatial-sdk-asset-model status=entity-created` only after
the app receives an SDK-loadable mesh URI. Raw FBX paths are conversion-required
source markers and are not SDK-loadable runtime mesh URIs.
The virtual room flags require `channel=spatial-virtual-room status=loaded` and
`status=scene-configured`; the actual GLXF room files may come from a local
Meta Spatial Editor sample export and should be treated separately from the
generic public module support.

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

On 2026-06-29, a strict camera/video run with `-DepthLayerPolicy compare`
passed with real `XR_META_environment_depth` bound and native passthrough active:

```text
local-artifacts\spatial-camera-panel-headset\20260629-152338-camera-hwb-projection-smoke\evidence-summary.json
APK_SHA256=FA45845AE0B239C75D6B0777E73F5E614919C77320208BECFBD0E1EAF19874CC
```

The compare path samples depth layer 0 and layer 1 at the same shader UV and
renders their difference. The headset/screenshot evidence showed structured
per-eye differences, so the layers must not be assumed byte-identical. This is
visual shader evidence only; literal byte-for-byte confirmation would require a
future GPU readback/statistics pass. General Spatial depth-stack alignment is
deferred to manual panel calibration and later alignment work.
