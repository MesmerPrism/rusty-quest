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
  route policy plus video-only probe lifecycle marker fields in
  `SpatialVideoProjectionSettings.kt`;
- package raw Camera2/HWB and diagnostic probe route defaults, dimensions,
  durations, Android property opt-ins, opt-in marker fields, raw Camera2/HWB diagnostic probe marker fields,
  raw Camera2/HWB diagnostic probe layer marker fields,
  SDK-owned quad surface layer/canvas/cleanup marker fields,
  SDK-owned quad surface/Vulkan/stereo-alpha probe lifecycle marker fields,
  external OpenXR swapchain wrapping lifecycle marker fields,
  and panel-surface matrix probe lifecycle marker fields in `SpatialDiagnosticProbeRouteModule.kt`;
- package the default-disabled external OpenXR swapchain execution lifecycle,
  dedicated wrapper/scene state, cycle scheduling, SDK-handle wrapping checks,
  native-handle wrapper retention, destroy-ownership classification, and
  cleanup in `SpatialExternalSwapchainProbeCoordinator.kt`; keep JNI
  declarations and native-library state in Activity-supplied bindings;
- package the six shared SDK-owned quad resource handles, viewer-relative pose
  calculation, scoped layer access, and ordered scene/swapchain cleanup in
  `SpatialSdkQuadResourceCoordinator.kt`; keep each route's exact opt-in gate,
  JNI start/stop authority, and feature-specific marker composition outside
  this inert resource owner;
- package the default-disabled SDK canvas surface probe gate, start state,
  scheduling, Android swapchain/surface acquisition, checkerboard draw,
  plain-entity/generated-mesh layer fallback, and completion lifecycle in
  `SpatialSdkQuadSurfaceProbeCoordinator.kt`; allow explicitly enabled Vulkan
  and panel-matrix callers to reuse its layer factory without enabling the
  surface probe itself;
- package the default-disabled SDK-quad Vulkan probe gate, start state,
  native-library availability branch, Android swapchain/surface acquisition,
  generated-anchor layer request, native producer start/stop, hold timer, and
  completion receipts in `SpatialSdkQuadVulkanProbeCoordinator.kt`; retain JNI
  declarations and panel-matrix orchestration in Activity-supplied bindings;
- package the default-disabled stereo-alpha probe gate, two state fields,
  stereo pattern drawing, clip/blend/color setup, generated anchor/layer
  creation, delayed z-index and alpha mutations, cleanup, and operator-check
  completion receipt in `SpatialSdkQuadStereoAlphaProbeCoordinator.kt`; keep
  JNI and unrelated property authority out of this coordinator;
- package the default-disabled two-variant `PanelSurface` matrix gate, start
  state, swapchain/texture construction, shared layer request, native producer
  attempt, timed cleanup, variant gap, and final receipt in
  `SpatialPanelSurfaceMatrixProbeCoordinator.kt`; retain JNI declarations and
  dynamic native-library state in Activity-supplied bindings;
- package the default-disabled raw camera-HWB probe gate, projection-route
  exclusion, start state, native-library branch, Android swapchain/surface and
  generated layer lifecycle, native producer start/stop, hold timer, cleanup,
  and receipts in `SpatialCameraHwbProbeCoordinator.kt`; keep property reads,
  JNI declarations, and camera-projection authority in Activity bindings;
- package video-projection probe opt-in, scene/virtual-room deferral, start
  state, settings resolution, Android swapchain/surface lifecycle, shared
  projection-layer request, native probe start, and route receipts in
  `SpatialVideoProjectionProbeCoordinator.kt`; retain effective settings,
  native configuration, projection startup, viewer updates, and JNI authority
  in explicit Activity callbacks;
- package the one effective video-projection settings snapshot, playback
  started state, native configuration sequence, playback start/stop, and
  inactive-start fail-closed guard in
  `SpatialVideoProjectionRuntimeCoordinator.kt`; route video-only, raw, and
  panel carriers through that authority while retaining Android playback
  context and JNI declarations in Activity bindings;
- package the exact camera-HWB projection property opt-in,
  scene/virtual-room deferral, one-shot launch state, launch receipt, and
  main-thread dispatch in `SpatialCameraHwbProjectionLaunchCoordinator.kt`;
  retain property reads, reader-limit resolution, effective video settings,
  carrier selection, marker composition, and raw/panel execution in explicit
  Activity bindings;
- package request-driven native passthrough and environment-depth startup/stop
  plus the retained depth-start mask in
  `SpatialCameraHwbProjectionDepthPrerequisiteCoordinator.kt`; fail both start
  routes closed unless the camera projection launch is explicitly active and
  retain Scene/OpenXR capture, extension reporting, native-library state,
  projection entity observation, and JNI declarations in Activity bindings;
- package raw SceneQuadLayer projection execution, swapchain/surface ownership,
  generated stereo layer construction, synthetic-preview branching, native
  prerequisite ordering, producer startup, cleanup, and receipts in
  `SpatialCameraHwbProjectionRawCarrierCoordinator.kt`; fail closed unless the
  launch coordinator is active and raw carrier mode is selected, while keeping
  effective settings, placement inputs, private-layer policy, video startup,
  and JNI authority in Activity bindings;
- package the panel-carrier lifecycle fields, video-panel callback adoption,
  SDK/manual carrier construction, readiness/start sequencing, layer updates,
  synthetic-preview branch, native producer startup, and ordered cleanup in
  `SpatialCameraHwbProjectionPanelCarrierCoordinator.kt`; fail closed unless
  the launch coordinator is active and panel carrier mode is selected, while
  keeping effective settings, placement/private-layer policy, shared entity
  state, and JNI authority in Activity bindings;
- package the active projection entity/layer update loop, raw and panel layer
  projection, native panel-pose update sequencing, two marker-throttle fields,
  and plane-update receipts in
  `SpatialCameraHwbProjectionPlacementUpdateCoordinator.kt`; fail closed unless
  an explicit camera launch or video runtime is active, while retaining plane
  calculation and the JNI primitive adapter in Activity bindings;
- package target-scale/stereo-offset state, joystick timing, launch reset,
  effective target-rect reporting, guarded scale input, panel scale adjustment,
  native parameter submission, and receipts in
  `SpatialCameraHwbProjectionTuningCoordinator.kt`; fail input mutations closed
  unless the explicitly launched projection entity exists, while retaining
  property reads, MotionEvent axis extraction, placement refresh, and JNI
  declarations in Activity bindings;
- package the Android Canvas synthetic checkerboard/text draw path and its
  draw/skip/failure receipts in
  `SpatialCameraHwbProjectionSyntheticRenderer.kt`; give it no property, route,
  or JNI authority and expose it only as a draw binding to already opted-in raw
  and panel carriers after their synthetic-visual gate passes;
- package carrier mode, placement mode, secondary-toggle arming/debounce state,
  carrier policy tokens, guarded placement toggling, private-layer reapply, and
  receipts in `SpatialCameraHwbProjectionCarrierStateCoordinator.kt`; keep
  property/intent reads and JNI in Activity bindings and return toggle mutation
  inert unless the camera projection launch is explicitly active;
- package read-only projection-plane construction, target-distance and
  input-clearance policy, projection marker composition, and panel media
  settings in `SpatialCameraHwbProjectionGeometryCoordinator.kt`; retain Scene
  observation as an Activity binding and give the coordinator no activation,
  property, JNI, or entity-mutation authority;
- package virtual room and skybox behavior in `SpatialVirtualRoomModule.kt`;
- package staged GLB/GLTF asset behavior as a feature/module;
- package projection carrier selection, placement-plane construction from
  Activity-observed scene inputs, target-rect math, target scale, stereo offset,
  raw-projection startup/swapchain/completion/native-start marker fields,
  raw projection layer-create marker fields, synthetic visual draw marker
  fields, projection plane/update marker fields,
  panel-carrier start lifecycle marker fields, placement-toggle marker fields, and markers in
  `CameraHwbProjectionModule.kt`;
- package camera-HWB projection panel carrier construction, the Spatial SDK
  video-surface panel registration and callback sequencing, registered panel
  entity, manual custom-mesh `PanelSceneObject`, video-surface panel consumer/ready
  markers, and create/surface/add/readiness marker fields in
  `CameraHwbProjectionPanelCarrierModule.kt`;
- package private layer panel placement/input policy, placement/headlock marker
  envelopes, panel shell/mode marker envelopes, the panel-state persistence
  failure marker envelope, private-layer panel layer
  readiness/failure marker envelopes, and private-layer grabbable/sync evidence in
  `SpatialPanelPlacementModule.kt`;
- package private-layer control choices, depth alignment clamping, panel-control
  marker fields, and JNI submission result marker fields in
  `PrivateLayerPanelControlModule.kt`;
- package layer-override, depth-source, and depth-alignment mutable state plus
  guarded native submission sequencing in
  `SpatialPrivateLayerControlCoordinator.kt`; fail closed before mutation or
  native submission unless the Activity-supplied camera/video projection route
  is active, and retain property reads, route state, placement refresh, and JNI
  declarations in typed Activity bindings;
- keep `PrivateLayerControlPanel.kt` as the Compose-only projection of those
  controls;
- package read-only Spatial SDK controller/avatar ECS observation, local-right
  controller preference, avatar-controller fallback, button/thumb
  normalization, and `SpatialControllerPrimarySnapshot` construction in
  `SpatialControllerSnapshotAdapter.kt`;
- package the four one-shot multimodal/controller bootstrap fields plus
  deferred/error/result sequencing in
  `SpatialNativeInputBootstrapCoordinator.kt`; require Activity-supplied
  explicit opt-in callbacks for both routes and retain property reads,
  native-library state, OpenXR probe capture, JNI declarations, and panel/input
  action authority in the Activity facade;
- package native and Spatial SDK controller poll sequencing, edge and
  route-telemetry state, marker throttling, and ordered action callback dispatch
  in `SpatialControllerPollingCoordinator.kt`; keep frame cadence, route
  enablement, controller pinning, feature/property selection, scene capture,
  action implementations, and JNI in the Activity facade;
- package the typed controller-route app-spec gate, idempotent Android
  controller pin registry, pinned-event fallback ordering, and route-marker
  throttling in `SpatialControllerInputRouteCoordinator.kt`; explicitly enable
  it from the Spatial Camera Panel app spec while retaining Spatial input
  enablement, controller enumeration/pinning, event handlers, and marker sink as
  Activity-supplied platform callbacks;
- package Android key/gamepad button recognition, key-versus-motion edge state,
  trigger-axis thresholding, source/detail normalization, and ordered callback
  dispatch in `SpatialControllerAndroidEventRouter.kt`; keep input enablement,
  controller pinning, action implementation, scene/store mutation, marker
  emission, feature opt-in, and JNI in the Activity facade;
- package controller shortcut routing policy and controller marker envelopes in
  `SpatialControllerRoutingModule.kt`;
- package OpenXR extension policy, explicit opt-in multimodal input defaults,
  native receipt library-load and interop probe/receipt markers,
  native passthrough/environment-depth start markers,
  native controller-action start markers, multimodal opt-in marker fields, and
  native receipt bit decoding in
  `SpatialOpenXrRouteModule.kt`;
- package native receipt library load state, Scene/OpenXR probe capture, the
  temporary no-render `PanelSurface`, receipt-call sequencing, and
  probe/receipt marker dispatch in `SpatialNativeInteropCoordinator.kt`; invoke
  it only from Activity lifecycle callbacks, retain the JNI declaration in the
  Activity, and forward explicit multimodal/controller bootstrap callbacks
  without giving the coordinator feature-property or panel-action authority;
- package validation and remote UI command marker policy, including self-test,
  UI-command, surface-target activation, remote participant, and Polar
  live-validation marker envelopes plus default validation identifiers, in
  `SpatialValidationCommandModule.kt`;
- package the four exact-action validation intent opt-ins, command parsing,
  store/session sequencing, remote UI dispatch, and delayed self-test/Polar
  automation in `SpatialValidationWorkflowCoordinator.kt`; keep ordinary
  launches inert and retain scene mutation, feature registration, runtime
  properties, and JNI behind Activity-supplied callbacks or outside this
  coordinator;
- keep the Compose experiment UI plus experiment lifecycle and auto-panel
  marker envelopes in `ExperimentPanelController.kt`, while the Activity owns
  store mutation, panel visibility, and marker emission;
- package workflow, private-layer control, and launcher Compose panel
  construction in `SpatialComposePanelRegistrationModule.kt`; pass state and
  requester callbacks explicitly while the Activity retains panel lifecycle,
  scene-object adoption, marker emission, JNI, persistence, and video-surface
  carrier authority limited to feature selection and adapter binding;
- package surface-particle route policy, carrier parsing, dimensions, media
  settings, route lifecycle marker fields, parameter/alias marker fields,
  projection update marker fields, panel-layer marker fields, recenter marker fields,
  panel registration marker fields, panel entity marker fields,
  lifecycle-check marker fields, and camera-stack particle suppression marker fields in
  `SpatialSurfaceParticleRouteModule.kt`.
- package the bounded surface-particle control state, clamping, driver-profile
  handoff receipts, and parameter/alias submission sequencing in
  `SpatialSurfaceParticleParameterCoordinator.kt`; retain intent parsing,
  native-library state, JNI declarations, panel visibility observation, and
  feature activation in Activity bindings so the coordinator cannot start the
  particle runtime.
- package explicit-opt-in particle lifecycle state and guarded native
  start/camera-stack suppression/stop sequencing in
  `SpatialSurfaceParticleRuntimeCoordinator.kt`; retain Android `Surface`
  access, OpenXR probe capture, scene visibility, runtime-property reads, and
  JNI declarations in Activity-supplied adapters.
- package effective particle target distance/view yaw, remote overrides,
  projection/surface dimensions, placement marker fields, and command receipts
  in `SpatialSurfaceParticleProjectionGeometryCoordinator.kt`; retain
  runtime-property reads and Android `Intent` parsing in Activity bindings so
  geometry updates cannot activate or start the feature.
- package roll-stable viewer/eye projection math, geometry-change state,
  panel-layer/native-pose update cadence, and projection receipts in
  `SpatialSurfaceParticleProjectionUpdateCoordinator.kt`; retain Scene capture,
  entity mutation, Android clock access, and JNI declarations in Activity
  adapters so projection updates cannot activate or start the feature.
- package panel-layer configured/opacity state, change detection, result status,
  and update/failure receipts in
  `SpatialSurfaceParticlePanelLayerCoordinator.kt`; retain the concrete Spatial
  SDK layer handle and z-index, blend, and color mutation in an Activity callback
  so the coordinator cannot create a panel or activate the particle feature.
- package panel registration count, adopted panel state, surface-consumer
  validity, and lifecycle-diagnostic presentation snapshots in
  `SpatialSurfaceParticlePresentationStateCoordinator.kt`; retain the scene
  entity and manual Android `Surface` lifetime in Activity so the state owner
  cannot create or activate a panel.
- package recenter eligibility, native availability, acceptance-mask handling,
  and command receipts in `SpatialSurfaceParticleRecenterCoordinator.kt`; fail closed before JNI
  unless the Activity-supplied particle feature opt-in is enabled, and retain property reads plus
  JNI declarations in Activity bindings.
- package delayed lifecycle-check scheduling, explicit validation overrides,
  and read-only marker projection in
  `SpatialSurfaceParticleLifecycleDiagnosticsCoordinator.kt`; fail closed for
  ordinary lifecycle callbacks when the particle feature is disabled, retain
  scene/store/panel/runtime/receipt/presentation capture in Activity snapshot
  adapters, and prohibit entity creation or particle-runtime activation.
- package headlock pose-marker cadence, hotload-token change state, joystick
  delta/cadence, and private-layer grabbable cadence in
  `SpatialPanelInteractionStateCoordinator.kt`; retain panel placement,
  runtime-property reads, entity access, and marker construction in Activity,
  and prohibit feature activation or Spatial state mutation in the coordinator.
- package workflow placement, private-layer placement, private-layer
  visibility, and their pure adjust/resize/reset/headlock/visibility transitions
  in `SpatialPanelPlacementStateCoordinator.kt`; expose read-only Activity
  facade views, retain pose capture/entity/marker/persistence/property/SDK
  mutation adapters in Activity, and prohibit feature registration or activation.
- package workflow headlock, private-layer viewer-pose, and
  entity-pose-to-placement geometry in `SpatialPanelPoseCoordinator.kt`; retain
  `Scene.getViewerPose`, entity transform capture/mutation, and corrected-state
  adoption in Activity bindings, and prohibit property reads, marker emission,
  JNI, or feature activation in pose geometry.
- package the exact headlock-tuning JSON schema/key order/output filename and
  panel-state persistence failure receipts in
  `SpatialPanelPersistenceCoordinator.kt`; retain typed placement snapshots,
  output-directory and store adapters, and marker routing in Activity, and
  prohibit property reads, scene mutation, or feature activation in persistence.
- package private-layer panel layer eligibility, missing-resource outcomes,
  z-index update sequencing, and failure receipts in
  `SpatialPrivateLayerPanelLayerCoordinator.kt`; retain the concrete
  `PanelSceneObject`, SDK layer mutation, and app-spec enablement binding in
  Activity, and prohibit panel registration, visibility, or activation.
- package native surface-particle registered video-surface callback sequencing,
  manual panel carrier construction, custom-mesh `PanelSceneObject` creation,
  create/surface/add failure markers, and readiness marker fields in
  `SpatialSurfaceParticlePanelCarrierModule.kt`; keep the explicit opt-in versus
  manual-carrier decision in the Activity facade and pass state/JNI adapters
  through typed bindings.

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
the app receives an SDK-loadable mesh URI. The staged asset module also owns the
`start-deferred` marker emitted while a requested packaged virtual room is still
loading. Raw FBX paths are conversion-required source markers and are not
SDK-loadable runtime mesh URIs.
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
