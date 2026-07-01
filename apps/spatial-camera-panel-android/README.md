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
- Spatial SDK staged 3D asset support for explicit GLB/GLTF mesh URIs. This is
  a generic runtime `Mesh` entity path with transform, scale, and optional
  `Grabbable` controls.
- Packaged virtual room support for explicit `assets/scenes/Composition.glxf`
  scene assets, usually exported from an official Spatial SDK panel sample or
  another Meta Spatial Editor room. The opt-in property is
  `debug.rustyquest.spatial.virtual_room.enabled`; the module is generic
  Spatial SDK room support, not MRUK real-room placement and not passthrough
  room capture.
- Public seven-slot camera guide multi-stack contract, including generic final,
  guide blur, post-blur guide, and depth diagnostic slots.
- Scene-depth permission diagnostics that mirror the native renderer surface:
  `horizonos.permission.USE_SCENE`, OpenXR permissions, and a smoke-wrapper
  `USE_SCENE_DATA` app-op receipt. The public multi-stack keeps a fallback
  depth descriptor for unbound runs, and strict camera/video smokes can now bind
  real `XR_META_environment_depth` descriptors when native passthrough is active.
- Generic driver profiles `profile-a` through `profile-d` with bounded
  `driver0_value01` and `driver1_value01` handoff markers.
- Native hand-anchor particle smoke tests that use public deterministic
  resident-mesh anchor billboards.
- An opt-in Spatial SDK ECS world-space hand billboard flock module. It creates
  generic persistent Spatial SDK carrier objects, samples public hand anchors,
  keeps drift state in system arrays, and avoids projection-surface particle
  routing. The default `batched-scene-mesh` carrier renders high-density
  billboards through two dynamic `TriangleMesh` scene objects; `ecs-entities`
  remains an explicit comparison carrier.

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

The world-space hand billboard flock follows the same public boundary. It owns
only generic Spatial SDK carrier objects, hand-anchor sampling, public drift
state, visibility, and status markers. Its default `batched-scene-mesh` carrier
renders a fixed-count particle cloud through two dynamic `TriangleMesh` scene
objects so high-density tests avoid per-particle ECS `Transform` writes;
`ecs-entities` remains an explicit comparison carrier. It does not own private
effect formulas, tuned profiles, native surface-particle buffers, or
camera-projection target math.

The staged 3D asset path follows the same boundary: raw source model files are
local inputs only and must not be packaged or committed. Runtime rendering uses
an explicit staged GLB/GLTF URI supplied by system property or intent extra.
Raw FBX is accepted only by host tooling as a source-format marker that requires
conversion before staging.

The packaged virtual room path follows the same public/private boundary.
Reusable source owns the `spatial-sdk-packaged-virtual-room` loader, lighting,
skybox/IBL setup, and markers. Local room exports, screenshots, media, and
private test models remain local launch inputs unless explicitly approved for
publication.

Depth and render ordering are still active Spatial lane work. The public lane
records depth source policy and alignment controls, but it does not yet claim a
final depth-stack organization for the virtual room, skybox, GLB/GLTF assets,
video layer, and custom camera projection surface. A previous room iteration
proved the custom projection quad can be visible in front of an explicitly
backgrounded runtime skydome, but the original sample `mesh://skybox` path is a
separate and currently negative ordering case for the direct `SceneQuadLayer`
carrier: skybox-only evidence can hide the direct projection even while native
video/camera frames are being produced. The repeatable foreground-room path is
the `video-surface-panel-scene-object` carrier; direct `SceneQuadLayer` remains
a comparison/diagnostic route while depth-stack organization is still active
work.

## Headset Evidence

The 2026-06-28 Quest 3S raw-color camera projection smoke passed the camera
stack gate: SDK-owned `SceneQuadLayer`, native Vulkan WSI, camera 50/51 streams,
target-rect clipping, and stereo output all rendered. A stricter private-shader
build of the public multi-stack route also passed on 2026-06-28 with
`-RequirePublicMultiStackProjection`: five guide targets allocated, public blur
runtime ready, opaque guide/projection pipelines ready, fallback depth ready,
`publicMultiStackProjectionApplied=true`, and
`publicMultiStackLayerCycleEnabled=true`. Later 2026-06-29 strict evidence binds
real `XR_META_environment_depth` descriptors with
`publicMultiStackDepthCurrentDescriptorSource=xr-meta-environment-depth`,
`publicMultiStackDepthRealDescriptorBound=true`,
`environmentDepthValidData=true`, and nonzero valid sample counters after the
native passthrough prerequisite is active. The fallback descriptor remains
available and continues to mark unbound/default runs, but it is no longer the
only Spatial depth source path.

The 2026-06-29 depth-layer compare run used `-DepthLayerPolicy compare`, which
drives the shader to sample depth layer 0 and layer 1 at the same UV and render
their difference. That visual proof showed structured per-eye differences, so
the two Meta-provided depth layers should not be treated as byte-identical by
default. This is shader visual evidence, not a literal GPU readback byte
comparison. General Spatial depth-stack alignment is deferred to manual panel
calibration and future alignment work.

The strict run preserves the native projection footprint by keeping the Spatial
SDK quad as the carrier and clipping Vulkan output to the packed native target
rects; it also suppresses the surface particle renderer while the camera stack
is active.

Latest strict evidence:
`local-artifacts\spatial-camera-panel-headset\20260629-152338-camera-hwb-projection-smoke\evidence-summary.json`;
APK SHA-256 `FA45845AE0B239C75D6B0777E73F5E614919C77320208BECFBD0E1EAF19874CC`.

A separate vergence/focus mismatch remains: when the camera projection is
brought into comfortable focus, Meta system menus can appear doubled or soft.
Treat that as a future Rusty Lattice / projection-space alignment
investigation, not as a camera acquisition, HWB import, WSI carrier, or public
multi-stack failure.

For that investigation, the raw Camera2/HWB projection probe now defaults to
the accepted no-room ordering path: room and skybox are disabled at launch, the
projection surface is fixed at a 2.0m default distance, the generic
layer-control UI panel opens at a 1.0m default distance, and the opposed
per-eye horizontal UV offset stays locked to the current default `0.046320`,
captured from a live Quest 3S headset readback on 2026-06-28 where the camera
projection and Meta performance HUD aligned simultaneously. Left-stick Y
controls workflow panel distance, and when the layer-control panel is open it
controls that panel's stored distance; it does not tune projection stereo
offset.
Runtime readback uses
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
longer drives panel scale or distance. While the private layer panel is open,
thumbstick-driven projection scale is suppressed so controller motion cannot
resize the camera projection while the UI is under the pointer. Right-stick
side flick is ignored for private-panel movement. The private layer panel is a
Spatial SDK
`Grabbable(type = PIVOT_Y)` entity, matching Meta's floating panel samples,
with a visual header grab handle but no Compose drag-driven movement.
When opened, it is seeded in front of the viewer at the last stored distance.
When it is not actively grabbed, the app reapplies the stored placement so
right-stick/default SDK nudges do not teleport it; while grabbed, the SDK
transform is accepted and synced back into the stored placement.

For the accepted no-room default, right secondary/B is deliberately disabled
and consumed as a no-op; markers use
`cameraProjectionWallToggleInput=disabled-right-secondary-noop` and
`cameraProjectionWallToggleEnabled=false`. Earlier room diagnostics used the
right secondary/B button to toggle the raw camera projection quad between a
fixed virtual wall pose inside the packaged room and the full-field
viewer-locked pose. With the room enabled, the accepted live surface carrier is
`video-surface-panel-scene-object`, because headset validation showed the
`scenequadlayer-room-object` retry still rendered the custom projection behind
authored room geometry while remaining visible outside/through the room window.
A later restored first-room-style direct anchor also failed against the
original sample `mesh://skybox`; skybox-only evidence showed the sample skybox
can hide the direct projection by itself. The current first-room replay marker
for that diagnostic is
`skyboxEntityCreateApi=toolkit-varargs-first-room-replay`, which restores the
old sample skybox `Entity.create(Mesh, Material, Transform)` call shape. The
same diagnostic now also reports
`projectionStartGate=virtual-room-loaded` and the old first-room
`projectionRoomRenderOrder=projection-layer-over-virtual-room` token. Headset
evidence with all three markers still did not show the custom projection, so
this direct SceneQuadLayer path remains a negative comparison route.
Set `debug.rustyquest.spatial.camera_hwb_projection_probe.carrier` to
`scenequadlayer-room-object` only to reproduce that rejected comparison path.
Earlier foreground-room runtime evidence used
`cameraProjectionWallToggleInput=right-controller-secondary-button`,
`virtualRoomWallPlacementMode=virtual-room-wall-fixed-quad`, and
`virtualRoomWallCenterM` markers plus
`projectionRoomRenderOrder=video-surface-panel-over-virtual-room` and
`legacyLauncherPanelSuppressed=true`.

When the camera/video stack is active, the right primary button opens a
front-of-camera private-layer control panel instead of the participant workflow
panel or the legacy launcher panel. That panel mirrors the native private
layer selector: seven generic
layer choices, live projection-area scale, live depth source policy
(`mono-layer0`, `mono-layer1`, `eye-index`, or `compare`), and live
depth-alignment X/Y/scale controls. It is registered as
`spatial_private_layer_panel`, currently renders through the targeted
`spatial-sdk-layer` UI ordering test path with layer z-index `99`, uses
Spatial SDK `Grabbable` as the movement authority so it sticks to the grabbed
pose, and
updates the public opaque
projection route through
`nativeUpdatePrivateLayerOverride` and
`nativeUpdatePrivateLayerDepthLayerPolicy` plus
`nativeUpdatePrivateLayerDepthAlignment`. Layer override markers include
`layerOverrideAppliesToWallAndFullFov=true`.
In the accepted no-room default, opening this control panel keeps
the camera/video projection at 2.0m and opens the UI at 1.0m so the UI is
physically in front of the projection without the previous `0.25m`/`0.22m`
foreground compensation path. The projection carrier is explicitly
input-transparent
(`projectionPanelInputPassThrough=true`, manual carrier
`projectionPanelHittable=none-manual-custom-mesh-noninteractive`), so
controller-ray and button behavior must be verified on headset rather than
inferred from visibility markers alone.
The panel explicitly accepts A/trigger select for its Compose controls; the
inner palm/squeeze action remains the SDK grab path.

The manual custom-mesh projection carrier
(`manual-panel-scene-object-custom-mesh`) remains the active room/skybox
input-test candidate, not a finalized carrier. It passed strict synthetic
visibility checks with the room and skybox, and actual private-profile launches proved
video, staged GLB, room, skybox, and non-hittable carrier markers. A high-z UI
layer alone did not foreground the panel while the UI remained behind the
`0.25m` projection plane; the successful visual ordering run used high-z UI,
foreground UI distance/scale compensation, and manual projection
`forceSceneTexture=true` with `layerConfig=null`. Do not treat this as complete
until a headset run proves controller-ray targeting, button clickability, and
layer-button effect changes in the actual app.

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
- `app/src/main/.../SpatialStagedAssetModule.kt` owns the generic Spatial SDK
  staged 3D asset path. It creates a runtime `Mesh` entity from an explicit
  GLB/GLTF URI and marks raw FBX URIs as conversion-required.
- The Activity owns the generic packaged virtual room path. It loads a packaged
  GLXF composition only when `debug.rustyquest.spatial.virtual_room.enabled`
  is true, applies sample-style lighting and skybox resources if present, and
  marks `mrukPlacement=false`.
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
- `app/src/main/.../SpatialHandBillboardFlockFeature.kt` owns the opt-in
  public ECS world-space hand billboard flock. Enable it with
  `debug.rustyquest.spatial.hand_billboard_flock.enabled=true`.
- Future Spatial lane growth should follow the official `FeatureDevSample`
  modularity pattern: move reusable Spatial SDK behavior into feature/module
  owners with their own component/system registration, and keep this Activity
  as the registration/orchestration facade instead of adding every room,
  carrier, panel-placement, controller, and marker behavior directly here.
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
- `tools/Stage-SpatialCameraPanelAsset.ps1` stages a local GLB/GLTF into the
  package-scoped external files directory and emits the runtime mesh URI. If
  the source is FBX, the script requires a converted GLB/GLTF path first.

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

Builds that need the generic layer selector to visibly change the active
camera projection layer must provide the downstream opaque shader inputs at
build time. Prefer passing a private profile that names those shader sources
and projection constants:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1 `
  -Build `
  -PrivateLayerProfilePath <path-to-private-layer-profile.json>
```

Without those inputs, the APK still builds and the panel buttons still submit
layer state, but the native renderer intentionally falls back to the public raw
camera projection path, so layer selection has no visible effect.

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

For local host media, prefer staging through the wrapper so spaces and scoped
storage do not break Android system-property transport:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1 `
  -Serial <quest-serial> `
  -VideoSourcePath <local-stereo-video.mp4> `
  -RequireSpatialVideoProjection
```

This stages the file to the package-scoped external path
`/sdcard/Android/data/io.github.mesmerprism.rustyquest.spatial_camera_panel/files/v.mp4`,
which is the path used by the successful native-loop Spatial proofs.

To include a generic Spatial SDK staged 3D asset, provide a staged mesh URI or
let the wrapper stage a local GLB/GLTF source. Raw FBX sources must be converted
to GLB/GLTF first; the source model remains local and is not packaged:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1 `
  -Serial <quest-serial> `
  -AssetSourcePath <local-source-model.fbx> `
  -AssetConvertedMeshPath <converted-model.glb> `
  -RequireSpatialAssetModel
```

The runtime module is controlled by
`debug.rustyquest.spatial.asset_model.enabled`,
`debug.rustyquest.spatial.asset_model.mesh_uri`,
`debug.rustyquest.spatial.asset_model.source_format`,
`debug.rustyquest.spatial.asset_model.position_m`,
`debug.rustyquest.spatial.asset_model.rotation_degrees`,
`debug.rustyquest.spatial.asset_model.scale`, and
`debug.rustyquest.spatial.asset_model.grabbable`.

To include a packaged virtual room in the same smoke, export a GLXF room into
the APK assets before building and add the room flags:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1 `
  -Serial <quest-serial> `
  -AssetSourcePath <local-source-model.fbx> `
  -AssetConvertedMeshPath <converted-model.glb> `
  -EnableVirtualRoom `
  -RequireSpatialAssetModel `
  -RequireSpatialVirtualRoom
```

The required room markers are `channel=spatial-virtual-room status=loaded`,
`status=scene-configured`, `roomAssetSource=packaged-glxf`,
`genericModuleSupport=true`, and `mrukPlacement=false`.

After building with downstream opaque shader inputs, require the public
multi-stack projection proof with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1 `
  -Serial <quest-serial> `
  -ClearLogcat `
  -StopAfterRun `
  -RequirePublicMultiStackProjection
```
