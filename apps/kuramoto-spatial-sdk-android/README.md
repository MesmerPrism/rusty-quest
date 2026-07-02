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
- submit bounded low-rate particle parameters from the world-space Compose
  panel to the native Vulkan particle layer through JNI;
- preserve the low-rate participant, surface, block, questionnaire, and JSONL
  logging shape from the native Kuramoto workflow.

Non-scope for the first lane:

- no full private Kuramoto compute payload inside this app yet; the current
  live path reuses the native renderer's compact hand-rig skinning shape but
  still uses the Spatial SDK surface-panel renderer and a visible LCHE
  movement/noise shader slice;
- no GPU particle or phase-field data through panel JSON;
- no BLE Polar stream intake inside this app. The app creates the same
  participant file skeleton so ECG/Polar files remain part of the session
  bundle, but live Polar intake stays in the native lane until a low-rate
  bridge is designed.

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
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$serial = "3487C10H3M017Q"
$pkg = "io.github.mesmerprism.rustyquest.kuramoto_spatial"
& $adb -s $serial install -r -d -g target\kuramoto-spatial-sdk-android\rusty-quest-kuramoto-spatial-sdk.apk
& $adb -s $serial shell am start -W -n "$pkg/.KuramotoSpatialActivity" `
  -a io.github.mesmerprism.rustyquest.kuramoto_spatial.action.RUN_WORKFLOW_SELF_TEST `
  --es participant_id codex-spatial-sdk-visible-20260625 `
  --es surface_target_id real-hands
```

Live raw-to-scene hand transform tuning while the APK remains active:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialLiveHandSceneTransform.ps1 -Serial $serial -OffsetX 0 -OffsetY 0 -OffsetZ 2 -YawDegrees 180 -HorizontalSign -1
```

Candidate depth-axis correction while the APK remains active:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialLiveHandSceneTransform.ps1 -Serial $serial -OffsetX 0 -OffsetY 0 -OffsetZ 2 -YawDegrees 0 -HorizontalSign 1
```

Live hand spatial viewer-world registration parity diagnostic while the APK
remains active:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialLiveHandRegistrationParity.ps1 -Serial $serial -Parity yaw-180
```

This writes
`debug.rustyquest.kuramoto_spatial.live_hand_spatial_viewer_world_registration.parity`
with one of `none`, `flip-x`, `flip-y`, `flip-z`, `yaw-180`, or `flip-xz`.
The default accepted ECS/OpenXR bridge mapping is `flip-x` with
`debug.rustyquest.kuramoto_spatial.live_hand_spatial_viewer_world_registration.reflection_orientation`
set to `local-y`; explicit `none` remains a diagnostic override.
The single-axis values are reflection diagnostics; `yaw-180` / `flip-xz`
rotates both the lateral and forward axes together and keeps the registration
determinant positive. Native markers include
`status=live-hand-spatial-viewer-world-registration-parity-updated`,
`status=live-hand-spatial-viewer-world-reflection-orientation-updated`,
`status=live-hand-spatial-viewer-world-registration-diagnostic`,
`liveHandSpatialWorldRegistrationParity`, and
`liveHandSpatialWorldRegistrationOrientationAdjusted`, and
`liveHandSpatialWorldRegistrationEffectivePositionDeterminant`.

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
participant setup, Polar placeholder, surface selection, block timing,
automatic questionnaire due state, and questionnaire submission. ADB
`screencap` currently captures the VR compositor/performance overlay but not
the Spatial SDK panel layer, so headset evidence should include logcat,
SurfaceFlinger, activity dumpsys, and app-private JSONL artifacts.
