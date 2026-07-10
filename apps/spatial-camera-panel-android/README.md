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
  a persistent pool of non-hittable billboard entities, updates only final
  transforms and visibility each frame, keeps flock state in system arrays, and
  avoids the projection-surface particle route.
- Generic private surface-particle build hook metadata for downstream
  GPU-resident payload experiments. The public app records profile, shader,
  payload-directory, and marker-prefix build inputs, then routes configured
  inputs through a metadata-only private renderer variant until a complete
  staged payload is present. When the payload directory is supplied, the native receipt build
  script copies the conventional private positions, normals, aux0, and mask
  files into generated Rust payload metadata and reports shader/payload byte
  counts as staged readiness markers. A configured render loop can allocate
  startup Vulkan storage buffers for those staged bytes and report resident
  staged-buffer markers. It can also create the private compute and graphics
  descriptor/pipeline ABI from the staged shader and generic draw shaders.
  It now allocates two private descriptor sets plus main-only output, phase,
  driver-bank, and diagnostic storage buffers for the descriptor plan. The
  native frame loop records a main-only private compute dispatch over the staged
  payload, then draws the main private particle rows as a visible projection
  surface overlay. Profile-derived tracer state and draw rows are allocated in
  the same GPU-resident buffers and included in the merged source-order draw.
  In staged private main-draw mode the public hand-anchor proof is not drawn as
  a background fallback; markers report
  `privateSurfaceParticlePublicFallbackActive=false` and
  `privatePayloadVisibility=private-main-draw-only`. The private compute pass
  captures the first eligible floor-space Spatial panel pose as a scene-fixed
  world anchor, then uses the live panel pose only as the projection surface.
  OpenXR local-floor mapping markers remain as diagnostics for comparison with
  the hand-particle projection. Markers report
  `privateSurfaceParticleWorldAnchorMode=spatial-sdk-scene-fixed-anchor`,
  `privateSurfaceParticleWorldAnchorComputeSource=spatial-sdk-scene-fixed-anchor`,
  and `privateSurfaceParticleWorldAnchorMapped=true` when the diagnostic OpenXR
  mapper is also active.
  The Compose panel and remote UI command path also expose a bounded generic
  surface-particle parameter packet over the existing JNI live queue: driver
  slots `0..7`, point scale, tracer draw slots, tracer lifetime, tracer copy
  cadence, opacity, and projection world scale. The public app reports adopted
  packet revisions and keeps high-rate rows out of Kotlin/Java.
- Surface-particle renderer selection and frame-target carrier resources are
  split inside the native receipt: generated private build metadata can be
  reported in lifecycle markers, while the metadata-only private placeholder
  holds public build-input completeness markers. The swapchain carrier owns
  native surface-particle WSI creation, image acquisition, present, and
  swapchain destruction; the frame-loop wrapper owns command buffers,
  semaphores, and the frame fence; the pipeline/descriptor wrappers own shader
  pipeline state and storage-buffer descriptors; the frame-target wrapper owns
  only image views, render pass, framebuffers, and extent.

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
The private surface-particle hook follows the same boundary: profile/shader
payload inputs are build metadata and validation inputs, not a Java/Kotlin JSON
data plane, and no particle, phase, graph, texture, or tracer-state rows may
flow through it. The private staged icosphere path also must not draw the
public hand-particle proof behind the private rows; that proof remains the
public default only when the private payload is absent.
The world-space hand billboard flock follows the same public boundary. It owns
only generic Spatial SDK carrier objects, hand-anchor sampling, public drift
state, visibility, and status markers. Its default `batched-scene-mesh`
carrier renders a fixed-count particle cloud through two dynamic
`TriangleMesh` scene objects so high-density tests avoid per-particle ECS
`Transform` writes; `ecs-entities` remains an explicit comparison carrier. It
does not own private effect formulas, tuned profiles, native surface-particle
buffers, or camera-projection target math.
The optional private SpatialFeature hook follows the same boundary. Public
source only discovers an env-provided source directory and reflects a registry
class when present; downstream source owns any private systems, formulas, and
profile semantics.

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

A 2026-06-30 Quest 3S no-controller private surface-particle alias smoke passed
for the generic `particle-alias-control` path:
`local-artifacts\spatial-camera-panel-headset\20260630-164329-particle-alias-smoke\evidence-summary.json`;
APK SHA-256 `87229743C77AC66FA971DD4DFFEFA915C7E330375C09A4B5BB1CA42EC9EAA027`.
It validated active alias accept, inactive visual-driver alias reject,
activated alias accept, forbidden high-rate reject, and profile-derived
sphere-radius write using ADB intents, compact native alias markers, and JNI
return masks. `controller_input_required=false`.

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
- `app/src/main/.../SpatialCameraPanelRuntimeHelpers.kt` owns shared marker
  token formatting, Android system-property and intent-extra parsing, and
  small Spatial vector math helpers used by the facade. It must stay free of
  lifecycle, panel, camera, particle, and JNI start/stop authority.
- `app/src/main/.../SpatialVideoProjectionSettings.kt` owns the Kotlin-side
  video projection route policy: default-disabled opt-in controls,
  intent/property parsing, the low-rate settings value object, route marker
  fields, and video-only probe lifecycle marker fields used by the Spatial
  camera projection route. It does not decode media or own native AImageReader
  / AHardwareBuffer handoff.
- `app/src/main/.../SpatialDiagnosticProbeRouteModule.kt` owns the
  default-disabled route policy for diagnostic probes: raw Camera2/HWB,
  external OpenXR swapchain wrapping, SDK-owned quad surface/Vulkan probes,
  stereo-alpha probe dimensions/timing, panel-surface matrix variants, and
  explicit opt-in marker fields. It also owns raw Camera2/HWB diagnostic probe
  marker fields, raw Camera2/HWB diagnostic probe layer marker fields,
  SDK-owned quad surface layer/canvas/cleanup marker fields,
  SDK-owned quad surface/Vulkan/stereo-alpha probe lifecycle marker fields,
  external OpenXR swapchain wrapping lifecycle marker fields, and panel-surface
  matrix probe lifecycle marker fields.
  It must not create scene objects, draw surfaces, call JNI, or mutate Activity
  probe state.
- `app/src/main/.../SpatialExternalSwapchainProbeCoordinator.kt` owns the
  default-disabled external OpenXR swapchain probe lifecycle: seven dedicated
  state/retainer fields, SDK-handle wrapping checks, native-handle wrapping,
  Spatial scene object and quad-layer lifetime, cycle scheduling, destroy
  ownership classification, and cleanup markers. It checks the existing
  explicit property opt-in before touching bindings. The Activity supplies the
  Spatial `Scene`, native-library state, marker sink, and JNI create/destroy
  callbacks; the coordinator does not declare JNI methods or read unrelated
  feature properties.
- `app/src/main/.../SpatialSdkQuadResourceCoordinator.kt` owns the six shared
  SDK-quad resource handles used by explicitly enabled surface, Vulkan,
  stereo-alpha, matrix, camera-HWB, camera-projection, and video-projection
  routes: swapchain, Android surface, layer, scene object, anchor mesh, and
  anchor material. It also owns viewer-relative probe pose calculation,
  scoped layer access, and ordered scene/swapchain cleanup. The coordinator is
  inert until an already opted-in route adopts resources; it reads no runtime
  properties, starts no JNI route, and suppresses cleanup markers when no
  resources were adopted. The Activity remains the facade for feature gates,
  native stop callbacks, and route-specific marker composition.
- `app/src/main/.../SpatialSdkQuadSurfaceProbeCoordinator.kt` owns the
  default-disabled SDK canvas surface probe: its exact property gate, start
  state, main-thread scheduling, Android swapchain/surface acquisition,
  checkerboard draw, plain-entity to generated-mesh fallback, visible-window
  receipt, and delayed cleanup/completion markers. Its layer factory is also
  called by Vulkan and panel-matrix routes only after those routes pass their
  own explicit opt-ins. The Activity supplies the shared resource owner,
  cleanup callback, `Scene`, and marker sink; the coordinator declares no JNI
  methods and cannot enable another route.
- `app/src/main/.../SpatialSdkQuadVulkanProbeCoordinator.kt` owns the
  default-disabled SDK-quad Vulkan diagnostic lifecycle: its exact property
  gate, start state, native-library availability branch, Android swapchain and
  surface acquisition, generated-anchor layer request, native producer start,
  hold timer, stop, cleanup, and completion receipts. The Activity supplies
  JNI start/stop callbacks and dynamic native-library state; the panel-matrix
  route retains its independent opt-in and direct JNI orchestration.
- `app/src/main/.../SpatialSdkQuadStereoAlphaProbeCoordinator.kt` owns the
  default-disabled stereo-alpha diagnostic lifecycle: its exact property gate,
  two state fields, stereo pattern drawing, clip/blend/color setup, generated
  anchor and layer creation, delayed z-index and alpha mutations, cleanup, and
  operator-check completion receipt. It uses only the shared resource owner,
  Activity-supplied `Scene`, cleanup callback, and marker sink; it has no JNI
  or unrelated feature-property authority.
- `app/src/main/.../SpatialPanelSurfaceMatrixProbeCoordinator.kt` owns the
  default-disabled two-variant `PanelSurface` matrix diagnostic: its exact
  property gate, start state, swapchain/texture variant construction, shared
  layer-factory request, native producer attempt, timed cleanup, variant gap,
  and final completion receipt. The Activity supplies `Scene`, native state,
  Vulkan callbacks, cleanup, and markers; no other diagnostic is enabled by
  this coordinator.
- `app/src/main/.../SpatialCameraHwbProbeCoordinator.kt` owns the
  default-disabled raw camera-HWB diagnostic: its exact property gate,
  projection-route exclusion, start state, native-library branch, Android
  swapchain/surface and generated layer lifecycle, native camera producer
  start/stop, hold timer, cleanup, and receipts. The Activity supplies the
  projection-property adapter, dynamic native state, JNI callbacks, shared
  resources, and marker sink; camera projection retains separate authority.
- `app/src/main/.../SpatialVideoProjectionProbeCoordinator.kt` owns the
  explicit video-projection probe opt-in, scene/virtual-room deferral, start
  state, settings resolution, Android swapchain/surface lifecycle, shared
  projection-layer request, native probe start, and route receipts. Activity
  bindings retain effective settings, camera-panel suppression, native video
  configuration, shared projection startup, viewer updates, and JNI authority;
  the coordinator does not create a second settings source of truth.
- `app/src/main/.../SpatialVideoProjectionRuntimeCoordinator.kt` is the single
  effective-settings and playback-state owner shared by video-only, raw, and
  panel projection routes. It resolves route settings, adopts the effective
  snapshot, sequences native configuration and playback start/stop, and fails
  closed when inactive settings reach `start`. Activity bindings retain the
  Android playback context and JNI declarations; carrier coordinators cannot
  create parallel settings or started-state authorities.
- `app/src/main/.../SpatialCameraHwbProjectionLaunchCoordinator.kt` owns the
  exact camera-HWB projection property opt-in, scene/virtual-room deferral,
  one-shot launch state, start receipt, and main-thread dispatch. Activity
  bindings retain property reads, reader limits, effective video settings,
  carrier selection, marker-field composition, and projection execution; the
  coordinator cannot activate raw or panel carriers without that explicit
  launch gate.
- `app/src/main/.../SpatialCameraHwbProjectionDepthPrerequisiteCoordinator.kt`
  owns request-driven native passthrough and environment-depth startup/stop
  plus the retained depth-start mask. Both start routes fail closed unless the
  camera projection launch is explicitly active. Activity bindings retain
  Scene/OpenXR capture, extension reporting, native-library state, projection
  entity observation, and JNI declarations.
- `app/src/main/.../SpatialCameraHwbProjectionRawCarrierCoordinator.kt` owns
  the raw SceneQuadLayer projection execution path: Android swapchain/surface
  acquisition, generated stereo layer construction, synthetic-preview branch,
  native prerequisite ordering, producer start, cleanup, and receipts. Its run
  route fails closed unless the launch coordinator is active and raw carrier
  mode is selected. Activity bindings retain effective settings, entity state,
  placement inputs, private-layer policy, video startup, and JNI authority.
- `app/src/main/.../SpatialCameraHwbProjectionPanelCarrierCoordinator.kt`
  owns the panel-carrier lifecycle state, video-panel callback adoption, SDK
  and manual carrier construction, readiness/start sequencing, layer updates,
  synthetic-preview branch, native producer start, and ordered cleanup. It
  fails closed unless the launch coordinator is active and panel carrier mode
  is selected. Activity bindings retain effective settings, placement and
  private-layer policy, shared projection entity state, and JNI authority.
- `app/src/main/.../SpatialCameraHwbProjectionPlacementUpdateCoordinator.kt`
  owns the active projection update loop and its two marker-throttle fields:
  entity pose/dimensions, raw layer resize/z-order, panel-carrier layer update,
  native panel-pose projection, and plane-update receipts. Updates fail closed
  unless an explicit camera launch or video runtime is active. Activity
  bindings retain placement-plane calculation and the JNI primitive adapter.
- `app/src/main/.../SpatialCameraHwbProjectionTuningCoordinator.kt` is the
  single target-scale/stereo-offset authority. It owns four tuning and joystick
  timing fields, launch reset, effective target-rect reporting, guarded scale
  input, panel scale adjustment, native parameter submission, and receipts.
  Input mutations fail closed unless the explicitly launched projection entity
  exists. Activity bindings retain property reads, MotionEvent axis extraction,
  placement refresh, and JNI declarations.
- `app/src/main/.../SpatialCameraHwbProjectionSyntheticRenderer.kt` owns the
  Android Canvas checkerboard/text visual and its draw/skip/failure receipts.
  It has no property, route, or JNI authority and cannot activate itself; only
  the already opted-in raw and panel carrier coordinators receive its draw
  binding after their synthetic-visual gate passes.
- `app/src/main/.../SpatialCameraHwbProjectionCarrierStateCoordinator.kt` is
  the single carrier mode, placement mode, secondary-toggle arming, and debounce
  state owner. It resolves carrier mode through an Activity property/intent
  binding, exposes carrier policy tokens, and sequences guarded placement
  toggles plus private-layer reapply receipts. Toggle mutation returns inert
  unless the camera projection launch is explicitly active; JNI remains an
  Activity callback.
- `app/src/main/.../SpatialCameraHwbProjectionGeometryCoordinator.kt` owns the
  read-only projection-plane construction, target-distance and input-clearance
  policy, projection marker composition, and panel media settings. It observes
  Scene viewer/eye geometry through an Activity binding but has no activation,
  property, JNI, or entity-mutation authority.
- `app/src/main/.../CameraHwbProjectionModule.kt` owns the Kotlin-side
  camera-HWB projection carrier/config marker surface: carrier token parsing,
  panel z-index/display-role policy, viewer-locked and virtual-wall projection
  plane construction from Activity-observed scene inputs, target-rect math,
  raw-projection startup/swapchain/completion/native-start marker fields,
  raw projection layer-create marker fields, synthetic visual draw marker
  fields, projection plane/update marker fields,
  panel-carrier start lifecycle marker fields, target scale, stereo offset, placement-toggle marker fields,
  stereo marker fields, and receipt constants.
  It must not query the Spatial scene, create
  Spatial scene objects, start JNI native routes, consume controller input, or
  own camera frames.
- `app/src/main/.../CameraHwbProjectionPanelCarrierModule.kt` owns camera-HWB
  projection panel carrier construction: the Spatial SDK video-surface panel
  registration and callback sequencing, the Spatial SDK video-surface panel
  entity, the manual custom-mesh `PanelSceneObject`, carrier create/surface/add
  failure markers, entity-spawn markers, video-surface panel consumer/ready
  markers, and manual-carrier readiness markers. The Activity supplies explicit
  state-adoption, settings, marker-sink, placement, layer-update, and JNI-start
  bindings.
  It must not start JNI native routes, update native projection parameters,
  poll controllers, or decide whether a carrier is enabled for the current run.
- `app/src/main/.../SpatialPanelPlacementModule.kt` owns workflow/private-layer
  panel placement policy: default distances and sizes, headlock property
  parsing, placement clamping, pose/dimension/settings factories, private
  layer `Grabbable(type = PIVOT_Y)` setup, headlock marker fields,
  placement/headlock marker envelopes, panel shell/mode marker envelopes,
  panel-state persistence failure marker envelope,
  private-layer panel layer readiness/failure marker envelopes, and
  private-layer grabbable/sync evidence.
  It must
  not mutate Spatial scene entities, consume controller input, or call JNI.
- `app/src/main/.../PrivateLayerPanelControlModule.kt` owns private-layer
  control model and evidence policy: layer choices, depth-source choices,
  depth-alignment clamping, panel-control marker fields, and JNI submission
  result markers. It must not render Compose UI, mutate Activity state, call
  JNI, or decide feature opt-in.
- `app/src/main/.../PrivateLayerControlPanel.kt` owns only the Compose
  projection of those controls and forwards requests to Activity-owned routes.
- `app/src/main/.../SpatialControllerSnapshotAdapter.kt` owns read-only Spatial
  SDK ECS observation for controller components, local right-controller
  preference, player-avatar hand-controller fallback, button/thumb bit
  normalization, and `SpatialControllerPrimarySnapshot` construction. It must
  not choose polling cadence, enable input, pin Android controllers, dispatch
  actions, read feature properties, emit markers, or call JNI.
- `app/src/main/.../SpatialNativeInputBootstrapCoordinator.kt` owns the four
  one-shot multimodal/controller bootstrap fields and deferred/error/result
  sequencing. It starts neither route unless the Activity-supplied explicit
  opt-in callback is true. Activity bindings retain property reads,
  native-library state, OpenXR probe capture, JNI declarations, and panel/input
  action authority.
- `app/src/main/.../SpatialControllerPollingCoordinator.kt` owns native and
  Spatial SDK controller poll sequencing, ten edge/route-telemetry state
  fields, route-marker throttling, and ordered scale, distance, trigger,
  secondary, and primary callback dispatch. Native callbacks remain inert
  until the Activity-supplied state reports the feature enabled, receipt
  library loaded, and native actions started. The Activity retains frame
  cadence, feature/property selection, input enablement and controller pinning,
  Spatial scene capture, action implementations, and JNI calls.
- `app/src/main/.../SpatialControllerInputRouteCoordinator.kt` owns the typed
  controller-route app-spec gate, idempotent Android game-controller pin
  registry, pinned-event fallback ordering, and input-route marker throttling.
  The Spatial Camera Panel Activity explicitly opts in with
  `SpatialControllerInputRouteSpec(enabled=true,
  source=spatial-camera-panel-app-spec)` and supplies callbacks for Spatial
  input enablement, controller enumeration/pinning, Android event routing, and
  marker emission. The module remains inert for a disabled or unnamed spec and
  must not query the Spatial scene, read runtime properties, mutate app/store
  state, or call JNI.
- `app/src/main/.../SpatialControllerAndroidEventRouter.kt` owns Android
  key/gamepad button recognition, key-versus-motion edge state, trigger-axis
  thresholding, source/detail normalization, and ordered secondary/trigger/
  primary callback dispatch. The Activity supplies the placement-toggle,
  particle-recenter, panel-toggle, and secondary-arm callbacks. The router must
  not enable or pin input, choose feature opt-in, mutate scene/store state
  directly, emit markers, or call JNI.
- `app/src/main/.../SpatialControllerRoutingModule.kt` owns controller input
  policy helpers: Spatial VR input-system property parsing, controller route
  timing constants, trigger thresholds, joystick axis normalization,
  left-stick panel-distance mapping, right-primary panel toggle decisions, and
  controller route/joystick marker envelopes.
  It must not query Spatial ECS entities, mutate panel state, pin Android game
  controllers, emit markers directly, or call JNI.
- `app/src/main/.../SpatialOpenXrRouteModule.kt` owns OpenXR route policy:
  required extension lists, the explicit opt-in multimodal input default,
  native receipt library-load and interop probe/receipt marker fields,
  native passthrough and environment-depth start marker fields,
  native controller-action start marker fields, multimodal opt-in marker fields,
  native receipt bit decoding, and marker-ready native route status helpers.
  It must not load native libraries, call JNI, query the Spatial runtime, or
  mutate Activity state.
- `app/src/main/.../SpatialNativeInteropCoordinator.kt` owns native receipt
  library load state, Scene/OpenXR probe capture, the temporary no-render
  `PanelSurface`, receipt-call sequencing, and probe/receipt marker dispatch.
  It runs only when invoked by Activity lifecycle callbacks. The Activity
  retains the JNI declaration and forwards explicit multimodal/controller
  bootstrap callbacks; the coordinator has no feature-property or panel-action
  authority.
- `app/src/main/.../SpatialValidationCommandModule.kt` owns validation and
  remote UI command route marker policy: self-test, remote UI command, surface
  target activation, remote participant creation, and Polar live-validation
  marker envelopes plus default validation identifiers. It must not mutate the
  store, operate panels, schedule handlers, call Polar APIs, or start native
  routes.
- `app/src/main/.../SpatialValidationWorkflowCoordinator.kt` owns the four
  exact-action intent opt-ins, validation command parsing, store/session
  sequencing, remote UI command dispatch, and delayed self-test/Polar
  automation. Ordinary launches are inert: no store provider, panel callback,
  Polar callback, or diagnostic callback is invoked unless the intent action
  matches a declared validation route. The Activity supplies typed panel,
  particle, Polar, diagnostics, marker, and error-reporting bindings; the
  coordinator must not register features, mutate Spatial scene entities, read
  runtime properties, or call JNI.
- `app/src/main/.../SpatialSurfaceParticleRouteModule.kt` owns the
  surface-particle route policy: native-layer opt-in/suppression defaults,
  carrier token parsing, panel dimensions, projection-surface math, media
  settings, route lifecycle marker fields, parameter/alias marker fields,
  projection update marker fields, panel-layer marker fields, recenter marker fields,
  panel registration marker fields, panel entity marker fields,
  lifecycle-check marker fields, and camera-stack particle suppression marker fields. It must
  not create scene objects, read runtime properties, call JNI, or mutate
  Activity state.
- `app/src/main/.../SpatialSurfaceParticleParameterCoordinator.kt` is the
  single bounded control-state and parameter/alias submission owner. It clamps
  the low-rate control packet, sequences driver-profile handoff receipts, and
  calls Activity-supplied JNI adapters. Intent parsing, native-library state,
  JNI declarations, panel visibility observation, and feature activation stay
  in Activity bindings; the coordinator cannot start the particle runtime.
- `app/src/main/.../SpatialSurfaceParticleRuntimeCoordinator.kt` is the
  explicit-opt-in native particle lifecycle owner. It owns started,
  camera-stack-suppressed, start-requested, and last-start-mask state and
  sequences guarded start, camera-stack suppression, and stop receipts.
  Android `Surface` access, OpenXR capture, scene visibility, runtime-property
  reads, and JNI declarations remain Activity-supplied adapters.
- `app/src/main/.../SpatialSurfaceParticleProjectionGeometryCoordinator.kt`
  owns effective particle target distance/view yaw, remote overrides,
  projection/surface dimensions, placement marker fields, and command
  receipts. Runtime-property reads and Android `Intent` parsing remain
  Activity bindings; geometry updates cannot activate or start the feature.
- `app/src/main/.../SpatialSurfaceParticleProjectionUpdateCoordinator.kt`
  owns roll-stable viewer/eye projection math, geometry-change state,
  panel-layer/native-pose update cadence, and projection receipts. Scene
  capture, entity mutation, Android clock access, and JNI declarations remain
  Activity adapters; projection updates cannot activate or start the feature.
- `app/src/main/.../SpatialSurfaceParticlePanelLayerCoordinator.kt` owns
  panel-layer configured/opacity state, change detection, result status, and
  update/failure receipts. The concrete Spatial SDK layer handle and z-index,
  blend, and color mutation remain an Activity callback; the coordinator cannot
  create a panel or activate the particle feature.
- `app/src/main/.../SpatialSurfaceParticlePresentationStateCoordinator.kt`
  owns panel registration count, adopted panel state, surface-consumer validity,
  and the lifecycle-diagnostic presentation snapshot across video-surface and
  manual-carrier paths. The scene entity and retained manual Android `Surface`
  remain Activity resources; this state owner cannot create or activate a panel.
- `app/src/main/.../SpatialSurfaceParticlePanelCarrierModule.kt` owns native
  surface-particle panel carrier construction: registered video-surface callback
  sequencing, the manual custom-mesh `PanelSceneObject`, create/surface/add
  failure markers, and readiness marker fields. The Activity retains the
  explicit opt-in/manual-carrier decision and supplies state-adoption,
  settings, marker-sink, layer-update, and JNI-start bindings. The module must
  not decide feature opt-in, own particle parameter state, or mutate Activity
  lifecycle state directly.
- `app/src/main/.../SpatialVirtualRoomModule.kt` owns the explicit opt-in
  packaged virtual room and skybox route: GLXF load, lighting, IBL/skydome
  setup, skybox resources, property parsing, markers, and cleanup. It remains
  inert unless `debug.rustyquest.spatial.virtual_room.enabled` or the skybox
  properties opt it in, and it must not own camera frames, panel UI, native
  particle buffers, or JNI start/stop authority.
- `app/src/main/.../SpatialSdkLaneBoundary.kt` records the explicit route
  boundaries. Spatial SDK layer/panel primitives are the carrier substrate;
  experiment panel, camera projection, surface particles, and debug probes are
  separate consumers of that carrier.
- `app/src/main/.../SpatialStagedAssetModule.kt` owns the generic Spatial SDK
  staged 3D asset path. It creates a runtime `Mesh` entity from an explicit
  GLB/GLTF URI, marks raw FBX URIs as conversion-required, and owns
  deferred-start marker fields when the packaged virtual room has not loaded.
- The Activity remains the facade for the generic packaged virtual room path.
  It delegates room and skybox behavior to `SpatialVirtualRoomModule.kt`, then
  starts dependent staged-asset, video, and camera probes only after the module
  reports the room loaded.
- `app/src/main/.../SpatialPublicMultiStack.kt` mirrors the public seven-slot
  camera guide multi-stack receipt fields for Kotlin-side start, carrier, and
  placement markers. It marks opaque downstream slots inactive in this public
  app.
- `app/src/main/.../ExperimentPanelController.kt` owns the Compose experiment
  panel UI and launcher UI plus experiment lifecycle and auto-panel marker
  envelopes. It may request panel visibility changes and low-rate
  particle-driver scalar updates, but it must not emit markers directly or
  own camera frames, Vulkan WSI, SDK quad layers, or particle buffers.
- `app/src/main/.../SpatialComposePanelRegistrationModule.kt` owns construction
  of the workflow, private-layer control, and launcher Compose panel
  registrations. The Activity supplies explicit state and requester callbacks,
  and retains panel lifecycle, scene-object adoption, marker emission, JNI,
  persistence, and video-surface carrier authority limited to feature selection
  and adapter binding.
- `app/src/main/.../SpatialCameraPanelModels.kt` owns shared panel placement,
  native-interop receipt, and low-rate control state models used by the
  Activity facade and panel UI.
- `app/src/main/.../SpatialAvatarHandVisualFeature.kt` owns the built-in Meta
  avatar hand visual policy. The default keeps hands hidden so native/public
  hand visuals remain explicit; set
  `debug.rustyquest.spatial.avatar_hands.visible=true` on a headset to enable
  the Spatial SDK `AvatarSystem` hand visual for comparison runs.
- `app/src/main/.../SpatialAvatarHandInvestigationFeature.kt` owns the
  read-only Spatial SDK hand investigation probe. Enable it with
  `debug.rustyquest.spatial.avatar_hand_probe.enabled=true`; it samples
  `AvatarBody`, `AvatarAttachment`, `Controller`, `Mesh`, `Material`,
  `MeshMaterialOverrides`, and `MeshCreationSystem` through public APIs and
  reports whether any hand candidate exposes a public mesh/material entity. Its
  markers deliberately keep `sceneMeshVertexReadbackPublic=false` and
  `spatialAvatarHandMeshWireframeSupported=false` unless a supported public
  topology path exists.
- `app/src/main/.../SpatialHandBillboardFlockFeature.kt` owns the opt-in
  public ECS world-space hand billboard flock. Enable it with
  `debug.rustyquest.spatial.hand_billboard_flock.enabled=true`; the default is
  disabled so existing projection and panel validations do not change. Its
  app-owned `TriangleMesh` carrier supports
  `debug.rustyquest.spatial.hand_billboard_flock.visual_mode=wireframe-edges`
  for edge-quad wire inspection. Select
  `debug.rustyquest.spatial.hand_billboard_flock.wireframe.source=spatial-sdk-joint-proxy`
  for the supported app-owned proxy. Requests for `openxr-fb-mesh` or
  `custom-mesh`, and the Spatial-specific
  `avatar-system-public-mesh-probe`, are runtime-polled and reported in
  markers, but they resolve back to the Spatial proxy unless the read-only
  probe observes a supported public topology path for the SDK-owned
  `AvatarSystem` hand mesh.
- `app/src/main/.../SpatialPrivateFeatureLoader.kt` owns the optional private
  SpatialFeature extension point. Build with
  `RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_SRC_DIR=<kotlin-source-dir>` to include
  downstream private source and optionally
  `RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_ASSET_DIR=<asset-dir>` for downstream
  private APK assets plus
  `RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_RES_DIR=<res-dir>` for private
  drawables/material resources; when absent, the loader returns no features.
- Headless private stimulus runs can set
  `debug.rustyquest.spatial.panel_shell.visible=false` to hide the workflow,
  private-layer, launcher, and native surface-particle panels while leaving
  Spatial SDK world entities/features active.
- Future Spatial lane growth should follow the official `FeatureDevSample`
  modularity pattern: move reusable Spatial SDK behavior into feature/module
  owners with their own component/system registration, and keep this Activity
  as the registration/orchestration facade instead of adding every room,
  carrier, panel-placement, controller, and marker behavior directly here.
- Feature modules must be explicit opt-in. Individual modules may be compiled,
  registered, or present in source, but they must not create scene objects,
  start native routes, change input behavior, alter package/permission
  expectations, or emit active markers unless a documented property, profile,
  app spec, or intent extra enables that feature for the current run.
  Route-policy modules should own default-disabled controls and marker fields
  that identify the opt-in route so static gates can check the feature does not
  bleed into unrelated app runs.
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
  The private surface-particle hook records downstream input hashes and marker
  env names, reports executable-input completeness, staged payload byte counts,
  and generated private-shader compilation status. With complete staged inputs,
  the surface route stages those bytes into startup storage buffers, creates
  private compute plus graphics pipeline ABI at bindings `0,1,2,3,4,5,8,9`,
  allocates private descriptor sets with main-only output, phase, driver-bank,
  and diagnostic buffers, records main-only private compute dispatch, and draws
  the private main particle rows with a Spatial panel-plane projection shader.
  Profile-derived tracer state/draw rows are included in the same merged
  billboard output in this slice. The native
  surface-particle WSI path is split into swapchain, frame-loop, pipeline,
  descriptor, and frame-target wrappers around the private staged-payload draw
  path.
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

The generic private surface-particle alias command is available through the UI
action wrapper without controller input:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialCameraPanelAndroidUiAction.ps1 `
  -Action particle-alias-control `
  -ParticleAliasParameterId tracer_draw_slots_per_oscillator `
  -ParticleAliasValue 3 `
  -VisualDriverActivationProfile default `
  -Serial <quest-serial> `
  -ReadMarkers
```

When a private Spatial surface profile is configured at build time, native code
resolves the alias from generated profile metadata, applies accepted requests to
generic scalar fields, and emits `privateSurfaceParticleUiParameter*` accept or
reject markers. Public source owns only generic fields and marker transport;
private alias meanings stay in the configured profile.

Use the focused no-controller headset smoke to validate the full alias sequence:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialCameraPanelAndroidParticleAliasSmoke.ps1 `
  -Serial <quest-serial> `
  -ClearLogcat `
  -StopAfterRun
```

The smoke sends active, inactive, activated, forbidden high-rate, and
profile-derived alias requests through `am start` intents. It captures
pid-scoped logcat and app-private activity markers, then requires the matching
`privateSurfaceParticleUiParameter*` accept/reject fields. It does not require physical controller input.

The icosphere surface can be explicitly recentered without changing the
simulation-to-Spatial coordinate mapping. Right trigger in particle view, or
the no-controller `particle-recenter` UI command, asks native code to move only
the particle sphere center to the latest `Scene.getViewerPose` world position.
Native markers report `private-world-anchor-recentered`,
`privateSurfaceParticleWorldAnchorCenterSource=current-spatial-sdk-viewer-world-coordinate`,
and `privateSurfaceParticleRecenterChangesCoordinateMapping=false`.
The same native world-anchor store also runs a startup distance guard for the
first plausible tracked viewer pose: when that viewer pose is more than `0.5m`
from the current sphere center, native logs `private-world-anchor-auto-recentered`
and recenters only the sphere center to the viewer position while keeping
canonical Spatial world axes and fixed meter scale. The guard locks after the
first accepted tracked correction; normal head motion does not continuously
drag the sphere. Use the explicit trigger or no-controller `particle-recenter`
UI command for later intentional recentering.

Use the focused particle visual smoke for the private icosphere projection
surface after building with `RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_*`
inputs, or with the matching `Build-SpatialCameraPanelAndroid.ps1`
`-PrivateSurfaceParticleProfilePath`, `-PrivateSurfaceParticleShader`,
`-PrivateSurfaceParticlePayloadDir`, and `-PrivateSurfaceParticleMarkerPrefix`
arguments:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialCameraPanelAndroidParticleVisualSmoke.ps1 `
  -Serial <quest-serial> `
  -SurfaceTargetId icosphere `
  -ClearLogcat `
  -StopAfterRun
```

The smoke installs the APK, activates the `icosphere` surface target, forces the
separate Spatial hand-billboard flock property on, then requires the selected
icosphere path to suppress that flock. It also pulls app-private native markers
and requires the private main-draw no-fallback renderer, a floor-space world
anchor capture after startup provisional-anchor skips, OpenXR local-floor
world-anchor capture/mapping, nonzero 2562-particle / 17934-tracer draw counts,
and screenshot dimensions. It does not require
physical controller input.

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
