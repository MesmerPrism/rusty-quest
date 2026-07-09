# Spatial Camera Panel Implementation Notes

`apps/spatial-camera-panel-android` is a public Quest platform adapter for Meta
Spatial SDK panel behavior. It is intentionally separate from the native
OpenXR/Vulkan renderer and from downstream private effect stacks.

The room/world-space projection iteration history from the pre-room baseline
through the current video-surface panel carrier and private UI ordering work is
tracked in `docs/SPATIAL_ROOM_WORLDSPACE_ITERATION_LOG.md`.
The current targeted carrier matrix and Spatial SDK sample inventory are
tracked in `docs/SPATIAL_LAYERING_CARRIER_PROBE_PLAN.md`.

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
  distance after the default stereo horizontal offset was locked in; when the
  layer-control panel is open it controls that panel's stored distance and
  persists across close/open. Right-stick X is intentionally
  ignored/swallowed so it no longer drives panel scale, distance, or private
  panel side-flick movement.
- The right primary button opens a generic `spatial_private_layer_panel` while
  the camera/video stack is active. The panel renders as a Spatial SDK layer-backed
  world-space object with a compositor z-index above the
  camera/video projection layer, exposes the seven generic layer choices,
  projection area scale, depth source policy (`mono-layer0`, `mono-layer1`,
  `eye-index`, or `compare`), and depth-alignment X/Y/scale controls, and
  updates native state through `nativeUpdatePrivateLayerOverride`,
  `nativeUpdatePrivateLayerDepthLayerPolicy`, and
  `nativeUpdatePrivateLayerDepthAlignment`. Movement is owned by the Spatial
  SDK entity `Grabbable(type = PIVOT_Y)` component while actively grabbed;
  otherwise the app reapplies stored placement so default controller side-flick
  movement cannot reposition it. Compose drag deltas remain disabled; the
  header handle is a visual affordance only, so pointer deltas cannot feed back
  into panel
  transforms. The panel is seeded once in front of the viewer and then left to
  the Spatial SDK as a free world-space grabbable; forced radial placement
  writes remain disabled. While this panel is open, thumbstick-driven projection
  scale and panel-distance writes are suppressed so controller motion cannot
  move the UI out from under the pointer. A/trigger select is explicitly
  enabled for the Compose layer buttons, while controller squeeze/palm remains
  the grab path.
- The private icosphere surface has an explicit recenter request, not a
  remapping request. Right trigger in particle view, and the controller-free
  `particle-recenter` UI command, call the same native JNI path. Native consumes
  the latest `Scene.getViewerPose` world position as the sphere center while
  keeping canonical Spatial-world axes, fixed meter scale, and
  `sim-space-fixed-in-spatial-sdk-world-space` registration unchanged.
- The accepted default starts without the packaged room or skybox, uses the
  manual custom-mesh projection carrier at 2.0m, opens the generic
  layer-control UI panel at 1.0m, keeps right secondary/B disabled as a
  consumed no-op, and preserves left-stick Y as the panel-distance control
  while the layer-control UI is open.
- In packaged-room full-FOV mode, projection visibility and controller
  hit-testing are intentionally separated: the projection remains a higher
  compositor layer than the room and keeps its foreground full-FOV size, but
  the projection panel carrier is marked input-transparent with
  `projectionPanelInputPassThrough=true` and
  `projectionPanelHittable=NoCollision`. Controller rays/grab skip the
  full-FOV render panel and resolve to the normal-distance UI panel first.
- Strict headset smoke support for public multi-stack projection activation:
  `-RequirePublicMultiStackProjection` requires guide targets, public blur,
  opaque guide/projection pipelines, fallback depth, projection-applied, and
  layer-cycle elapsed markers, plus the packed target-rect markers and camera
  stack particle-suppression markers.
- Generic driver-profile handoff records using `profile-a` through `profile-d`
  and scalar `driver0_value01` / `driver1_value01` values.
- Public deterministic native hand-anchor particle smoke tests over resident
  hand meshes.
- An opt-in ECS world-space hand billboard flock named
  `spatial-sdk-world-hand-billboard-flock`. It creates persistent Spatial SDK
  carriers, samples Spatial SDK hand anchors, keeps public per-agent drift
  state in system arrays, and can render either through the original
  `ecs-entities` comparison path or the default `batched-scene-mesh` path with
  two dynamic `TriangleMesh` scene objects.

## ECS World-Space Hand Billboard Flock

The public Spatial SDK path now has an opt-in ECS flock module named
`spatial-sdk-world-hand-billboard-flock`. It is disabled by default through
`debug.rustyquest.spatial.hand_billboard_flock.enabled=false`.
The carrier is selected through
`debug.rustyquest.spatial.hand_billboard_flock.carrier`; the default
`batched-scene-mesh` mode preserves the visible particle count while removing
per-particle ECS component writes, and `ecs-entities` remains available for A/B
baseline runs.
The batched carrier also supports
`debug.rustyquest.spatial.hand_billboard_flock.visual_mode=wireframe-edges`,
which emits four thin app-owned edge quads for each billboard mesh item. This
is the Spatial SDK wireframe comparison path; the built-in `AvatarSystem` hand
mesh remains SDK-owned and is marker-reported as not publicly wireframeable.

Implementation order:

1. Register a reusable `SpatialFeature` with a late `SystemBase`.
2. Create persistent billboard carriers once when enabled.
3. Keep per-agent phase and offset state in arrays owned by the system.
4. Query Spatial SDK local hand anchor entities each frame.
5. In `batched-scene-mesh` mode, pack camera-facing quads into two
   `TriangleMesh` carriers and report zero per-particle `Transform` writes.
6. In `wireframe-edges` visual mode, replace each filled billboard face with
   four edge quads in the same `TriangleMesh` carrier.
7. In `ecs-entities` mode, write final `Transform` and `Visible` components
   back to the retained entity pool for comparison.
8. Use one shared viewer-facing orientation basis for all billboard cards.
9. Report public markers for visible particle count, carrier count, source,
   visibility, and boundary policy.

Non-scope:

- no projection-surface panel mapping;
- no native custom skinning route;
- no private effect formulas or tuned private profiles;
- no high-rate JSON payloads.

Private extension point:

- `RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_SRC_DIR` may add downstream Kotlin
  source to the app build.
- `RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_ASSET_DIR` may add downstream private
  APK assets for that optional source.
- `SpatialPrivateFeatureLoader` reflects a private registry class only when
  that source is present.
- Public source remains a carrier and does not name or implement downstream
  formulas, profiles, or kernels.

Validation starts with the static Spatial Camera Panel gate, then headset runs
can enable the module with the property above and require
`channel=spatial-hand-billboard-flock status=pool-created` plus
`status=world-space-updated` markers.

## Spatial Feature Modularity

Use the official `FeatureDevSample` pattern as the default shape for new
Spatial lane capabilities and refactors. That sample keeps reusable Spatial SDK
behavior in feature modules (`:nativefeature`, `:kotlinfeature`) and registers
those features beside `VRFeature` and `ComposeFeature`; the Activity orchestrates
features rather than owning all component/system behavior directly.

Apply that model here when a lane grows beyond a narrow facade method:

- keep `SpatialCameraPanelActivity.kt` as the lifecycle/JNI/panel-registration
  facade, with pure parsing/formatting/math helpers in
  `SpatialCameraPanelRuntimeHelpers.kt` and default-disabled video projection
  route policy in `SpatialVideoProjectionSettings.kt`;
- package raw Camera2/HWB and diagnostic probe route defaults, dimensions,
  durations, Android property opt-ins, and opt-in marker fields in
  `SpatialDiagnosticProbeRouteModule.kt`;
- package virtual room and skybox behavior in `SpatialVirtualRoomModule.kt`;
- package staged GLB/GLTF asset behavior as a feature/module;
- package projection carrier selection, target-rect math, and markers in
  `CameraHwbProjectionModule.kt`;
- package private layer panel placement/input policy in
  `SpatialPanelPlacementModule.kt`;
- package controller shortcut routing policy in
  `SpatialControllerRoutingModule.kt`;
- package OpenXR extension policy, explicit opt-in multimodal input defaults,
  native interop probe/receipt markers, multimodal opt-in marker fields, and
  native receipt bit decoding in `SpatialOpenXrRouteModule.kt`;
- package surface-particle route policy, carrier parsing, dimensions, media
  settings, and marker fields in `SpatialSurfaceParticleRouteModule.kt`.

All Spatial feature modules must stay explicit opt-in. Individual modules can be compiled,
registered, or available in source, but they should not create scene
objects, start native routes, alter input handling, request package/permission
behavior, or emit active capability markers unless a documented property,
profile, app-build spec, or intent extra enables that feature for the current
run. Keep the opt-in default and marker evidence in the owning route module so
static gates can prove a feature is present in source without being active by
default.

This is especially relevant before adding new carrier experiments. Prefer a
small feature-shaped slice over more growth in `SpatialCameraPanelActivity.kt`.

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
`projectionCarrier=video-surface-panel-scene-object`,
`projectionRoomRenderOrder=video-surface-panel-over-virtual-room`, and
`legacyLauncherPanelSuppressed=true`. A headset retry with
`scenequadlayer-room-object` proved that the old `SceneQuadLayer` path can keep
input/control evidence alive but still renders behind authored room geometry,
visible outside/through the room window. A restored first-room-style direct
anchor plus original sample `mesh://skybox` also failed to show the projection;
skybox-only evidence showed the sample skybox path can hide the direct
SceneQuadLayer even without the room. The current replay patch restores the
old sample skybox `Entity.create(Mesh, Material, Transform)` call shape and
emits `skyboxEntityCreateApi=toolkit-varargs-first-room-replay`,
`projectionStartGate=virtual-room-loaded`, and the old first-room
`projectionRoomRenderOrder=projection-layer-over-virtual-room` token. Headset
evidence with all three markers still did not show the custom projection, so
the carrier remains a rejected runtime comparison path through
`debug.rustyquest.spatial.camera_hwb_projection_probe.carrier`. The accepted
default is the no-room/no-skybox ordering path: manual custom-mesh projection
at 2.0m, generic layer-control UI at 1.0m, right secondary/B disabled as a
consumed no-op, left-stick Y panel-distance persistence, and high-z
layer-control UI rendering. The gate also checks right-stick projection target
scale markers, placement-independent layer override markers, and the generic
depth alignment JNI bridge without allowing private effect vocabulary into
this public lane.

## Build

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1 -Build
```

For a build where the generic layer-control panel buttons visibly change the
active camera projection layer, pass the downstream opaque shader profile into
the build wrapper:

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
