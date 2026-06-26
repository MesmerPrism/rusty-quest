# Rusty Quest Kuramoto Spatial SDK Android

This app is a separate Meta Spatial SDK lane for the Kuramoto experiment panel
workflow. It does not replace the native renderer APK and does not package the
Rust NativeActivity renderer. It now packages a small Rust/Vulkan surface
visual layer for the Spatial SDK shell, including the compute-written storage buffer
proof plus a native Kuramoto study hand-anchor particle draw path that can use
live OpenXR hand joints to drive the recorded-compatible resident hand-rig
skinning path, with a forced replay fallback. It is still not the full native
OpenXR/Kuramoto renderer or private Kuramoto compute payload path.

Package:

```text
io.github.mesmerprism.rustyquest.kuramoto_spatial/.KuramotoSpatialActivity
```

Purpose:

- prove a real Spatial SDK `AppSystemActivity` can own a world-space panel;
- experiment with panel pose, scale, meter size, and dp-per-meter display
  settings through Spatial SDK mechanisms;
- observe Spatial SDK-owned OpenXR handle availability and a tiny
  `PanelSurface` create/destroy capability without rendering through it;
- package a tiny Rust JNI receipt library in the same `AppSystemActivity` APK
  and pass the SDK OpenXR handle values plus panel-surface validity to native
  code without starting a renderer;
- create a second Spatial SDK surface panel as a native render target and let
  Rust/Vulkan draw the native study-style hand-anchor particle layer into its
  Android `Surface`;
- open, close, foreground, and resize the Spatial SDK workflow panel without
  stopping the native Vulkan surface particle layer. Closing the workflow panel
  leaves a compact launcher panel visible so the user can bring the
  questionnaire and parameter UI back in front of the view;
- submit bounded low-rate particle parameters from the world-space Compose
  panel to the native Vulkan particle layer through JNI;
- map randomized experiment block conditions to the same bounded native
  parameter bridge, then close the workflow panel back to particle view while
  the native surface particle layer keeps running;
- host a direct BLE Polar H10 panel inside the Spatial SDK workflow panel and
  mirror Polar stream events into the participant `polar_events.jsonl` and
  ECG rows into `ecg_events.jsonl`;
- preserve the low-rate participant, surface, block, questionnaire, and JSONL
  logging shape from the native Kuramoto workflow.

Non-scope for the first lane:

- no full private Kuramoto compute payload inside this app yet; the current
  live path reuses the native renderer's compact hand-rig skinning shape but
  still uses the Spatial SDK surface-panel renderer and a visible LCHE
  movement/noise shader slice;
- no GPU particle or phase-field data through panel JSON;
- no Polar samples or ECG payloads are routed to the native renderer or shader
  path. The Polar panel is a low-rate experiment-record adapter only.

Native interop probe:

- logs `channel=native-interop-probe` at scene-ready and VR-ready;
- records `Scene` runtime name plus nonzero status for the Spatial SDK-owned
  OpenXR instance/session/getInstanceProcAddr handles;
- creates and immediately destroys a 64px `PanelSurface` at VR-ready as a
  no-render surface-capability probe.
- loads `libkuramoto_spatial_native_receipt.so`;
- calls `KuramotoSpatialActivity.nativeRecordNoRenderInteropReceipt(...)` with
  the SDK handle values and `PanelSurface` validity bit;
- resolves and calls `xrGetInstanceProperties` through the SDK-provided
  `getInstanceProcAddr` handle as a no-render OpenXR handle-usability probe;
- resolves `xrGetSystem` and the Vulkan-enable entrypoints needed for a later
  no-present renderer adapter, and calls `xrGetVulkanGraphicsRequirements2KHR`
  when those entrypoints are exposed by the SDK-owned OpenXR instance;
- creates a no-present Vulkan instance through `xrCreateVulkanInstanceKHR`,
  obtains the OpenXR-selected Vulkan physical device through
  `xrGetVulkanGraphicsDevice2KHR`, finds a graphics+compute queue family,
  creates a logical Vulkan device through `xrCreateVulkanDeviceKHR`, obtains
  queue 0, then immediately destroys the device and instance;
- logs `channel=native-interop-receipt` from Kotlin and
  `RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE` from Rust with a no-render receipt
  bitmask, plus a concise Rust `channel=native-vulkan-object-probe` marker for
  Vulkan object creation details.

Native surface particle/hand layer:

- registers `kuramoto_particle_surface_panel` through
  `VideoSurfacePanelRegistration` beside the questionnaire panel;
- calls `KuramotoSpatialActivity.nativeStartSurfaceParticleLayer(...)` with the
  panel-provided Android `Surface`;
- calls `KuramotoSpatialActivity.nativeUpdateSurfaceParticleParameters(...)`
  from the Compose panel for bounded `driver0`, `driver1`, and point-scale
  updates;
- creates a native Vulkan Android surface and WSI swapchain, compiles GLSL
  compute/vertex/fragment shaders to SPIR-V through `native-receipt/build.rs`,
  dispatches a compute shader that writes a GPU storage buffer, and draws
  native study-style hand-anchor particle billboards from resident recorded-rig
  mesh anchors. Live `XR_EXT_hand_tracking` rows drive the same compact
  bind-pose/weight skinning shape used by the native renderer, with a resident
  forced replay validation-mesh coordinate source as the no-blank fallback.
  This uses no per-particle Spatial SDK entities;
- passes the Spatial SDK-owned OpenXR instance/session/getInstanceProcAddr
  handles into `nativeStartSurfaceParticleLayer(...)`, resolves
  `xrCreateHandTrackerEXT`, `xrLocateHandJointsEXT`, `xrCreateReferenceSpace`,
  and `xrConvertTimespecTimeToTimeKHR`, converts `CLOCK_MONOTONIC` to `XrTime`,
  and uploads 52 live joint rows into a host-visible Vulkan storage buffer;
- maps live joints through the recorded-compatible compact pose shape into the
  resident bind mesh, then projects barycentric mesh-surface particle anchors
  onto the Spatial SDK panel plane. This preserves the native study's
  surface-normal movement model instead of clustering particles around joints;
- applies the `lche` study condition as a visible shader dynamics slice:
  `kuramoto.private.native.profile.high-energy-low-coherence.movement-only.v1`,
  `movementBaseHz=0.88`, `movementCoupling=0.0`, frequency-spread/noise
  modulation, and explicit `studyProfileDynamicsActive=true` markers. The full
  private Kuramoto compute payload is still separate;
- accepts `RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR` at build time, using
  the same forced replay capture convention as the native renderer. The
  committed fallback remains the public shape fixture, but local headset runs
  should pass a full `left/right.rig.json` plus
  `left/right.validation_mesh.jsonl` capture directory;
- uses a packed side-by-side stereo proof surface:
  `StereoMode.LeftRight`, packed extent `2048x1024`, per-eye extent
  `1024x1024`, and a physical panel aspect that matches the target per-eye
  projection footprint rather than the packed image;
- draws both hand particle fields into each packed half with a per-eye virtual
  IPD ray intersection against the Spatial SDK panel plane
  (`properStereoStudyParticles=true`,
  `replayStereoProjection=per-eye-spatial-sdk-panel-plane-ray-intersection`);
- places the particle surface as a viewer-pose projection plane using
  `Scene.getViewerPose()` on scene ticks, with
  `cameraFacingParticleSurface=true`,
  `projectionLockedParticleSurface=true`,
  `placementMode=viewer-pose-projection-locked-quad`, target distance `0.72m`,
  target tangents `-1.0;1.0;-1.0;1.0`, and a `1.44m x 1.44m` physical
  footprint;
- records `Scene.getEyeOffsets()` in projection-plane update markers and sends
  the panel center/right/up/size basis to native code so the shader can map
  Spatial SDK scene-space hand coordinates onto the panel plane;
- transforms live `XR_EXT_hand_tracking` joint rows by locating the OpenXR
  view pose with `xrLocateViews`, expressing each live joint relative to that
  view pose, and rebuilding the joint position in the Spatial SDK panel basis
  from `Scene.getViewerPose()`. This is the primary path for keeping live
  hands in front of the camera while the Spatial SDK surface follows the
  viewer. The earlier raw OpenXR `LOCAL_FLOOR` to scene transform remains a
  fallback/diagnostic path: offset `0.0;0.0;2.0m`, yaw `180deg`, and the
  hotloadable properties
  `debug.rustyquest.kuramoto_spatial.live_hand_scene.offset_x_m`,
  `debug.rustyquest.kuramoto_spatial.live_hand_scene.offset_y_m`,
  `debug.rustyquest.kuramoto_spatial.live_hand_scene.offset_z_m`, and
  `debug.rustyquest.kuramoto_spatial.live_hand_scene.yaw_degrees`. A separate
  `debug.rustyquest.kuramoto_spatial.live_hand_scene.horizontal_sign` default
  of `-1` is also kept only for that fallback path. Headset feedback on the
  fallback showed that sign/offset tuning could fix left/right and near/far
  axes but still leave the live hands offset at an angle from the head-locked
  panel basis;
- treats live joint `status.y` as the compact-frame pose-valid gate and keeps
  live mesh skinning in the vertex shader. Once a hand has the native-equivalent
  21 runtime joint rows plus 5 tip-length rows, the shader skins every weighted
  vertex against the resident bind mesh without CPU-side skinned vertices;
- retries up to six alternate live mesh triangles before hiding a live particle
  whose initial sampled triangle has an invalid skinned vertex or degenerate
  normal. This keeps live hand particle density closer to the fallback mesh
  while preserving surface-triangle anchors instead of reverting to joint
  clusters;
- polls the low-rate Android property
  `debug.rustyquest.kuramoto_spatial.live_hand_depth_offset_meters` every
  frame. This is now a post-skinning diagnostic nudge with default `0.0m`; use
  it only after the raw-to-scene transform is accepted;
- polls the low-rate Android property
  `debug.rustyquest.kuramoto_spatial.particle_layer.target_distance_meters` on
  scene ticks. This is the actual Spatial SDK particle-surface distance from
  the viewer and is the distance control to use when the whole particle hand
  field appears too far away. The surface width/height scale with that distance
  to preserve the current panel-plane FOV footprint, and native receives the
  same `panelTargetDistanceMeters` for stereo projection;
- polls the low-rate Android property
  `debug.rustyquest.kuramoto_spatial.particle_layer.surface_overscan_scale` on
  scene ticks. This grows the Spatial SDK particle carrier quad independently
  of the viewer-pose projection plane. Kotlin sends the enlarged
  `surfaceWidthMeters`/`surfaceHeightMeters` to native as the physical panel
  mapping size so particle positions and point radii stay in the same world
  locations while the visible quad covers more of the field of view. Markers
  report `projectionWidthMeters`, `surfaceWidthMeters`,
  `surfaceOverscanScale`, `projectionPlanePoseInvariantWithOverscan=true`, and
  `particleWorldScaleInvariantWithOverscan=true`;
- disables the Spatial Toolkit player hand visual through
  `AvatarSystem.setShowHands(false)`. This mirrors the native renderer policy:
  the built-in Meta/SDK hand mesh is hidden unless explicitly requested, while
  the custom Vulkan particle hands remain visible;
- polls the low-rate Android property
  `debug.rustyquest.kuramoto_spatial.particle_layer.diagnostic_mode` every
  frame. This is a GPU shader visualization switch, not a CPU skinning path:
  `normal`, `triangle-bands`, `projection-clamp`, `no-dynamics`, and
  `degenerate` isolate topology coverage, panel projection clipping, dynamics
  visibility, and collapsed live triangles while keeping production skinning on
  the GPU;
- marks the current projection contract as full panel-plane mapping:
  `projectionContentMappingMode=world-to-spatial-sdk-panel-plane-left-right`,
  `targetProjectionSpace=spatial-sdk-panel-plane-perspective-projection`,
  `targetCoordinateSpace=spatial-sdk-surface-panel-eye-uv`, and full
  `0.0;0.0;1.0;1.0` left/right target surface UV rects;
- logs `channel=native-surface-particle-layer` markers including
  `surface-panel-ready`, native `started`, `render-loop-ready`, and
  `first-frame-presented`, plus `computeParticleStateBuffer=true`,
  `computeShaderDispatchReady=true`, `computeParameterBridge=true`,
  `native-surface-compute-stereo-proof=true`, `stereoMode=LeftRight`,
  `perEyeExtent=1024x1024`, `packedExtent=2048x1024`,
  `surfaceLayerMode=native-kuramoto-study-hand-anchor-particles`,
  `forcedReplayHands=true`, `forcedReplayMeshVisible=false`,
  `diagnosticParticlesVisible=false`, `nativeStudyParticlesVisible=true`,
  `handAnchorParticlesVisible=true`, `gpuReplayHandsResident=true`,
  `handAnchorParticlePath=resident-recorded-rig-gpu-skinned-mesh-coordinate-anchor-billboards`,
  `handAnchorParticleCoordinateSource=live-openxr-world-joints-gpu-skinned-resident-mesh-with-forced-replay-fallback`,
  `liveHandJointFrameSource=XR_EXT_hand_tracking`,
  `liveHandJointGpuInputPath=recorded-compatible-compact-joint-pose-gpu-skinning`,
  `liveHandJointPlacementMode=viewer-relative-openxr-to-spatial-sdk-panel-plane`,
  `liveHandCoordinateTransform=viewer-relative-openxr-to-spatial-sdk-panel-basis`,
  `liveHandViewPoseSource=xrLocateViews`,
  `liveHandPanelBasisSource=Scene.getViewerPose-panel-plane`,
  `liveHandSceneTransformSource=runtime-hotload-android-property`,
  `liveHandSceneOffsetDefaultM=0.000;0.000;2.000`,
  `liveHandSceneYawDefaultDegrees=180.000`,
  `liveHandSceneHorizontalSignDefault=-1.000`,
  `liveHandCompactUploadEquivalent=true`,
  `liveHandCompactFrameGate=native-equivalent-21-runtime-5-tip`,
  `liveHandRuntimeJointPoseCount`, `liveHandTipLengthCount`,
  `liveMeshSkinningPolicy=native-compact-frame-gated-full-weight-skinning`,
  `liveMeshTriangleRetryPolicy=bounded-alternate-triangle-sampling`,
  `liveMeshTriangleValidationAttempts=6`,
  `liveHandCorrectPositionSizeProof=spatial-sdk-panel-plane-projection`,
  `liveHandJointStatusY=pose-valid`,
  `liveHandSkinningValidityPolicy=native-compact-frame-gate-trust-all-weights`,
  `liveHandDepthOffsetParameterSource=runtime-hotload-android-property`,
  `liveHandDepthOffsetProperty=debug.rustyquest.kuramoto_spatial.live_hand_depth_offset_meters`,
  `particleLayerTargetDistanceParameterSource=runtime-hotload-android-property`,
  `particleLayerTargetDistanceProperty=debug.rustyquest.kuramoto_spatial.particle_layer.target_distance_meters`,
  `particleDiagnosticModeProperty=debug.rustyquest.kuramoto_spatial.particle_layer.diagnostic_mode`,
  `particleDiagnosticModeName`,
  `privateKuramotoPayloadActive=false`, `studyProfileDynamicsActive=true`,
  `kuramotoConditionId=lche`,
  `kuramotoStudyProfileId=kuramoto.private.native.profile.high-energy-low-coherence.movement-only.v1`,
  `kuramotoMovementBaseHz=0.88`, `kuramotoMovementCoupling=0.0`,
  `properStereoStudyParticles=true`,
  `replayStereoProjection=per-eye-spatial-sdk-panel-plane-ray-intersection`,
  `cameraFacingParticleSurface=true`,
  `projectionLockedParticleSurface=true`,
  `placementMode=viewer-pose-projection-locked-quad`,
  `targetProjectionSpace=spatial-sdk-panel-plane-perspective-projection`, and
  `projectionContentMappingMode=world-to-spatial-sdk-panel-plane-left-right`.

Current boundary: this is a visible native Vulkan WSI surface-layer proof, not
yet the final OpenXR Kuramoto particle renderer. The compute/storage-buffer
path proves the right resource class inside the Spatial SDK shell, and the
current live source now reuses the native study's resident hand-rig skinning
shape for mesh-surface particle anchors. The remaining gap is linking the full
private Kuramoto compute payload and private-particle shader stack rather than
the current LCHE movement/noise shader slice. The current placement is driven
by the Spatial SDK viewer pose,
but the SDK still owns the final quad composition rather than exposing the
native OpenXR projection swapchain directly.
The old skybox/backboard diagnostic scene is no longer part of this lane: the
Vulkan carrier surface is the user-facing XR visual surface, and the Spatial
SDK panels are low-rate workflow controls.

External OpenXR swapchain wrapper probe:

- gated by `debug.rustyquest.spatial.external_swapchain_probe`, off by
  default, with optional
  `debug.rustyquest.spatial.external_swapchain_probe.cycles` and
  `debug.rustyquest.spatial.external_swapchain_probe.cycle_ms` lifecycle
  controls;
- creates a known-good SDK `SceneSwapchain`, records `handle`,
  `nativeHandle()`, `platformHandle()`, and `getSurface()` validity, then tries
  `SceneSwapchain(...)` wrapping for each handle class;
- creates a tiny native mono `XrSwapchain` against the SDK-owned OpenXR
  instance/session without calling `xrWaitFrame`, `xrBeginFrame`, or
  `xrEndFrame`, enumerates/acquires/waits/releases one image, and returns the
  raw handle for a guarded Kotlin wrapper/layer attempt;
- 2026-06-26 Quest 3S result: the SDK-created `handle` and `nativeHandle`
  rewrap, `platformHandle()` is `0`, native `xrCreateSwapchain` succeeds and
  enumerates three images, and the raw external wrapper exposes matching
  `handle`/`nativeHandle`; however `getSurface()` on the raw wrapper crashes
  inside the SDK, `SceneQuadLayer` rejects the wrapper with a native assert,
  and `SceneSwapchain.destroy()` is skipped for raw wrappers while native
  `xrDestroySwapchain` owns cleanup. Treat raw external `XrSwapchain` display
  through `SceneQuadLayer` as blocked unless Meta documents a supported
  external-swapchain contract.

SDK-owned manual `SceneQuadLayer` probes:

- Canvas-only probe is gated by
  `debug.rustyquest.spatial.sdk_quad_surface_probe`, with optional
  `debug.rustyquest.spatial.sdk_quad_surface_probe.hold_ms`. It creates
  `SceneSwapchain.createAsAndroid(512, 512, false)`, draws a red/green
  checkerboard into `getSurface()` with Android `Canvas`, and attaches it to a
  manual `SceneQuadLayer`.
- 2026-06-26 Quest 3S result: a plain `SceneObject(scene, Entity(...))`
  anchor fails with the SDK native assert `SceneObjectInstance handle is null`,
  but a generated mesh-backed anchor created with
  `SceneMesh.singleSidedQuad(...)`, `SceneMaterial.passthrough()`, and
  `SceneObject(scene, mesh, ..., entity)` succeeds. Treat manual
  `SceneQuadLayer` as viable for SDK-owned swapchains only when the scene
  object has a real SDK mesh/object handle.
- Native Vulkan WSI probe is gated by
  `debug.rustyquest.spatial.sdk_quad_vulkan_probe`, with optional
  `debug.rustyquest.spatial.sdk_quad_vulkan_probe.hold_ms` and
  `debug.rustyquest.spatial.sdk_quad_vulkan_probe.frame_count`. It reuses the
  SDK-created Android `Surface`, creates
  `ANativeWindow -> VkSurfaceKHR -> Vulkan WSI swapchain` in the native receipt
  library, and renders an animated clear-color pattern without the private
  particle shader stack.
- 2026-06-26 Quest 3S result:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260626-092257-sdk-owned-quad-vulkan-probe`
  logged `surfaceValid=true`, `sceneQuadLayerCreated=true`,
  `manualSceneQuadLayerViable=true`, native `startMask=15`,
  `swapchainImages=3`, `extent=512x512`, `surfaceFormat=R8G8B8A8_UNORM`,
  `presentMode=FIFO`, `compositeAlpha=INHERIT`, `first-frame-presented`, and
  `render-complete framesPresented=360 requestedFrames=360`. The narrowed
  fatal check found no `FATAL EXCEPTION`, `Fatal signal`, `SIGSEGV`,
  `SIGABRT`, `AndroidRuntime`, or `ANR in` lines.
- Stereo/alpha probe is gated by
  `debug.rustyquest.spatial.sdk_quad_stereo_alpha_probe`. It uses
  `SceneSwapchain.createAsAndroid(2048, 1024, false)`,
  `StereoMode.LeftRight`, per-eye red/blue grids, alpha fade variants, clip
  variants, overscan, and z-index changes.
- 2026-06-26 Quest 3S result:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260626-093745-sdk-owned-quad-stereo-alpha-probe-zindex-fix`
  completed the programmatic contract: the layer was created from the
  SDK-owned Android surface, the native Vulkan WSI producer presented the
  packed 2048x1024 output, z-index ordering was corrected, and no fatal/ANR
  lines were captured. Visual eye-leakage, UV orientation, and final alpha
  convention should still be operator-checked on headset before using this for
  stereo camera projection.
- `PanelSurface` matrix probe is gated by
  `debug.rustyquest.spatial.panel_surface_matrix_probe`. It checks
  `PanelSurface(useSwapchain=true/useTexture=false)` and
  `PanelSurface(useSwapchain=false/useTexture=true)` for valid `surface`,
  non-null `swapchain`, `SceneQuadLayer` backing, and native Vulkan WSI
  presentation.
- 2026-06-26 Quest 3S result:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260626-094428-panel-surface-matrix-probe`
  showed both `PanelSurface` modes expose a valid Android `Surface` that can
  be used by native Vulkan WSI. Only `useSwapchain=true/useTexture=false`
  exposes a non-null SDK swapchain that can back a `SceneQuadLayer`; texture
  mode has no `panelSurface.swapchain`, but its surface still works as a native
  Vulkan producer target.
- Camera2/HWB probe is gated by
  `debug.rustyquest.spatial.camera_hwb_probe`. It starts Camera2 ID `50`
  first with fallback to `51`, creates an `AImageReader` with
  `AIMAGE_FORMAT_PRIVATE` and `AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE`,
  imports one acquired `AHardwareBuffer` as a Vulkan sampled image, and
  presents a luma/checker diagnostic into the passing SDK-owned quad carrier.
- 2026-06-26 Quest 3S result:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260626-100813-camera-hwb-spatial-probe`
  logged `selectedCameraId=50`, `selectedPrivateSize=1280x1280`,
  `vkGetAhbPropertiesResult=success`, `externalFormat=647`,
  `samplerMode=external-format-ycbcr`, `sampledCameraTexture=true`,
  `first-camera-frame-presented`, and `complete framesPresented=300`. Manual
  `pm grant` for `horizonos.permission.SPATIAL_CAMERA` was role-managed and
  failed, but the probe still opened camera `50` and presented camera-derived
  pixels through `scenequadlayer-createAsAndroid-vulkan-wsi`.
- Decision: the high-control manual layer route is alive when the swapchain is
  SDK-owned and exposed as an Android `Surface`. It now covers Canvas,
  native Vulkan WSI, programmatic stereo/alpha checks, PanelSurface surface
  variants, and the first Camera2/HWB-to-Vulkan sampled diagnostic. Raw
  external `XrSwapchain` wrapping should remain blocked unless Meta documents
  a different contract.

Build:

```powershell
& 'S:\Work\tools\Quest\Use-QuestTooling.ps1'
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-KuramotoSpatialSdkAndroid.ps1 -RepoRoot .
```

Forced replay hand build:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-KuramotoSpatialSdkAndroid.ps1 -RepoRoot . -RecordedHandCaptureDir <capture-dir-with-left-right-rig-and-validation-mesh> -RecordedHandFrameLimit 24
```

Expected APK:

```text
target/kuramoto-spatial-sdk-android/rusty-quest-kuramoto-spatial-sdk.apk
```

Static validation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-KuramotoSpatialSdkAndroid.ps1 -RepoRoot .
```

Headset workflow smoke, after taking Agent Board leases and choosing an
explicit Quest serial:

```powershell
$serial = "3487C10H3M017Q"
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-KuramotoSpatialSdkAndroidSelfTest.ps1 `
  -Serial $serial `
  -ParticipantId codex-spatial-sdk-visible-20260625 `
  -SurfaceTargetId real-hands
```

The wrapper installs the built APK unless `-SkipInstall` is passed, launches the
`RUN_WORKFLOW_SELF_TEST` action, captures PID-scoped logcat and app-private
session JSONL files, writes `evidence-summary.json`, and fails if panel mode,
Polar panel creation, condition handoff, native particle startup, first frame,
questionnaire, or failure-marker checks do not pass.

Live Polar H10 validation, with the sensor nearby and wearing/wet electrodes
active:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-KuramotoSpatialSdkAndroidPolarLive.ps1 `
  -Serial $serial `
  -ParticipantId codex-spatial-polar-live-20260625 `
  -SurfaceTargetId real-hands
```

The live wrapper launches `RUN_POLAR_LIVE_VALIDATION`, pregrants declared BLE
runtime permissions when possible, drives panel-owned scan, best-device connect,
and ECG start commands, captures PID-scoped logcat plus
`polar_sensor_status.json`, `polar_stream_events.jsonl`, `polar_events.jsonl`,
and `ecg_events.jsonl`, and fails unless a real ECG frame is decoded and
mirrored into the participant files. Use `-AllowMissingLivePolar` only for an
exploratory artifact bundle when the H10 is unavailable.

Headlocked workflow panel tuning while the APK remains active:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialPanelHeadlock.ps1 `
  -Serial $serial `
  -Enabled true `
  -OffsetX 0 `
  -OffsetY 0 `
  -Distance 1.40 `
  -Width 1.20 `
  -Scale 0.65 `
  -JoystickEnabled true
```

The workflow panel is headlocked by default in this lane. The panel pose is
recomputed from `Scene.getViewerPose()` while the panel is open, using
viewer-right `offset_x_m`, viewer-up `offset_y_m`, and viewer-forward
`distance_meters`. Runtime hotload properties are:
`debug.rustyquest.kuramoto_spatial.panel.headlocked.enabled`,
`debug.rustyquest.kuramoto_spatial.panel.headlocked.offset_x_m`,
`debug.rustyquest.kuramoto_spatial.panel.headlocked.offset_y_m`,
`debug.rustyquest.kuramoto_spatial.panel.headlocked.distance_meters`,
`debug.rustyquest.kuramoto_spatial.panel.headlocked.width_meters`,
`debug.rustyquest.kuramoto_spatial.panel.headlocked.height_meters`,
`debug.rustyquest.kuramoto_spatial.panel.headlocked.scale`,
`debug.rustyquest.kuramoto_spatial.panel.headlocked.joystick.enabled`,
`debug.rustyquest.kuramoto_spatial.panel.headlocked.joystick.translate_rate_mps`,
`debug.rustyquest.kuramoto_spatial.panel.headlocked.joystick.distance_rate_mps`,
and
`debug.rustyquest.kuramoto_spatial.panel.headlocked.joystick.scale_rate_per_second`.
Android generic-motion controller input maps left stick to panel x/y offset,
right-stick y to distance, and right-stick x to scale. The app writes the last
runtime-adjusted values to
`files/kuramoto_spatial_panel_headlock_tuning.json`; the helper reports that
file so tuned headset values can become future defaults. If Quest controller
axes do not arrive through Android generic motion in a later runtime, add a
native OpenXR action polling fallback rather than moving panel authority into
the Vulkan particle renderer.

Activate a surface target and leave the app in particle view:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-KuramotoSpatialSdkAndroidUiAction.ps1 `
  -Serial $serial `
  -Action surface-target-activate `
  -ParticipantId codex-spatial-icosphere-live `
  -SurfaceTargetId icosphere `
  -ReadMarkers
```

`RUN_UI_COMMAND` is wrapped by
`tools/Invoke-KuramotoSpatialSdkAndroidUiAction.ps1` and can remotely exercise:
`panel-open`, `panel-close`, `panel-reset`, `panel-headlock-on`,
`panel-headlock-off`, `panel-headlock-toggle`, `panel-adjust`, `panel-resize`,
`particle-controls`, `participant-reset`, `participant-begin`,
`polar-setup-save`, `surface-select`, `start-block`,
`surface-target-activate`, and `questionnaire-submit`. `RUN_SURFACE_TARGET` is
the direct activation action underneath the `surface-target-activate` command:
it follows the panel-first block-start path but does not schedule the self-test
panel reopen or questionnaire submit. It resets a fresh participant session,
selects `real-hands`, `gpu-replay-hands`, or `icosphere`, closes the Spatial
SDK workflow panel, starts the validation condition block, and leaves the
native Vulkan surface visible. For debugging, the right controller primary
button route polls the Spatial SDK `Controller` component for
`ButtonBits.ButtonA`, handles Android `KEYCODE_BUTTON_A`/`KEYCODE_BUTTON_1` on
the down edge, and keeps an Android generic-motion button fallback. Any route
that fires reopens the workflow panel when it is closed and emits
`controller-primary-opened-panel` with its `inputSource`.

Live raw-to-scene hand transform tuning while the APK remains active:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialLiveHandSceneTransform.ps1 -Serial $serial -OffsetX 0 -OffsetY 0 -OffsetZ 2 -YawDegrees 180 -HorizontalSign -1
```

Candidate depth-axis correction while the APK remains active:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialLiveHandSceneTransform.ps1 -Serial $serial -OffsetX 0 -OffsetY 0 -OffsetZ 2 -YawDegrees 0 -HorizontalSign 1
```

Live post-skinning diagnostic depth-offset tuning while the APK remains active:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialLiveHandDepthOffset.ps1 -Serial $serial -Meters 0
```

Live particle-surface distance tuning while the APK remains active:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialParticleLayerTargetDistance.ps1 -Serial $serial -Meters 0.35
```

Live particle-surface overscan tuning while the APK remains active:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialParticleLayerOverscan.ps1 -Serial $serial -Scale 1.35
```

Live GPU particle diagnostic mode while the APK remains active:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialParticleDiagnosticMode.ps1 -Serial $serial -Mode triangle-bands
```

The validation action drives the same low-rate store path as the panel:
participant setup, Polar setup metadata, surface selection, block timing,
condition-to-parameter handoff, particle-view transition during the running
block, automatic questionnaire due state, and questionnaire submission. Direct
BLE Polar scan/connect/ECG streaming is panel-owned and requires a headset run
with a nearby Polar device. ADB
`screencap` currently captures the VR compositor/performance overlay but not
the Spatial SDK panel layer, so headset evidence should include logcat,
SurfaceFlinger, activity dumpsys, and app-private JSONL artifacts.
