# Spatial SDK Kuramoto Port Implementation Plan

## Initial Hypothesis

The smallest useful Spatial SDK version should be a separate Quest app lane
under `apps/`, not a mutation of `apps/native-renderer-android`.

Expected requirements:

- Add a standalone Gradle/Kotlin Android project for a minimal Spatial SDK
  `AppSystemActivity`.
- Use a distinct package and label so the existing native renderer APK remains
  buildable, installable, and launchable as-is.
- Register and spawn a Spatial SDK panel entity from the immersive activity.
- Use panel shape, transform, scale, and display options as the experiment
  surface for panel placement/size/DPI testing.
- Reuse or mirror only the low-rate Kuramoto experiment workflow:
  participant setup, direct BLE Polar setup/status, ECG event mirroring,
  surface choice, randomized condition order, short timed blocks,
  questionnaire capture, and JSONL files joinable by
  participant/session/block/condition/profile/surface IDs.
- Keep native OpenXR/Vulkan hand mesh, particle buffers, and high-rate frames
  out of the Spatial SDK panel command/data path.
- Treat hand rendering in this first Spatial SDK lane as not expected unless a
  later slice embeds or coordinates with the native renderer.

## Resources Consulted

Checked on 2026-06-25 unless otherwise noted.

Local:

- `AGENTS.md`
- `README.md`
- `docs/ARCHITECTURE.md`
- `docs/VALIDATION.md`
- `docs/NATIVE_APP_BUILD_WORKFLOW.md`
- `fixtures/README.md`
- `tools/check_all.ps1`
- `tools/checks/Test-NativeRendererAndroidScaffoldStatic.ps1`
- `apps/native-renderer-android/AndroidManifest.xml`
- `apps/native-renderer-android/README.md`
- `apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/ControlPanelActivity.java`
- `apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/KuramotoExperimentSession.java`
- `apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/PolarSensorPanel.java`
- `apps/native-renderer-android/native/src/native_renderer_panel_bridge.rs`
- `apps/native-renderer-android/native/src/native_renderer_stimulus_panel.rs`
- `rusty-morphospace-context` skill and its Quest/Android routing reference
- `meta-quest-workflow` skill and its Agent Board/tooling routing reference
- `system-engineering` skill
- `C:\Users\tillh\.codex\attachments\a8b2cfdf-8736-4be2-8c58-e3aea6ac0bfe\pasted-text.txt`,
  external research handoff on Spatial SDK swapchains, Android surfaces,
  OpenXR frame ownership, and Vulkan producer constraints
- `C:\Users\tillh\.codex\attachments\b175e15d-2388-4d07-a4d1-4022bbabb752\pasted-text.txt`,
  external research handoff on Vulkan-rendered Spatial SDK surface panels,
  `VideoSurfacePanelRegistration`, stereo media modes, and the packed-stereo
  particle proof path
- Spatial SDK 0.13.1 local Gradle artifacts under
  `local-artifacts/gradle-user-home/caches/9.4.1/transforms`, inspected with
  `javap` for `AppSystemActivity`, `VrActivity`, `Scene`,
  `SceneSwapchain`, `PanelSurface`, `ActivityPanelRegistration`,
  `IntentPanelRegistration`, `VideoSurfacePanelRegistration`,
  `ReadableVideoSurfacePanelRegistration`, `StereoCanvasPanelRegistration`,
  and `SceneMesh`.

Official Meta:

- Spatial SDK overview:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-explainer/
- Add Spatial SDK to an existing 2D app:
  https://developers.meta.com/horizon/documentation/spatial-sdk/add-spatial-sdk-to-app/
- Spatial SDK activity lifecycle:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-activity-lifecycle/
- Spatial SDK architecture:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-architecture/
- Hybrid apps overview:
  https://developers.meta.com/horizon/documentation/spatial-sdk/hybrid-apps-overview/
- Hybrid sample:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-sample-hybrid/
- 2D panels in Spatial SDK:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel/
- Register 2D panels:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-registration/
- Build and position your first panel:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-panel-tutorial/
- Jetpack Compose in panels:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-compose/
- Panel resolution and display options:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-resolution/
- Meta Spatial SDK packages:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-packages/
- Connecting Spatial Editor to your project:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-editor/
- Custom shaders:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-custom-shaders/
- 3D objects and mesh entities, used here only as a boundary/reference for
  what not to do per particle:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-3dobjects/
- Premium Media sample overview, for a programmatic Spatial SDK scene with
  custom shader usage:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-sample-premiummedia/
- Spatial SDK media playback, including direct-to-surface media panel and
  stereo media concepts:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-media-playback/
- Spatial SDK Spatial Video sample:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-sample-video/
- Spatial SDK runtime guidelines:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-runtime-guidelines/
- Spatial SDK CPU/GPU levels:
  https://developers.meta.com/horizon/documentation/spatial-sdk/os-cpu-gpu-levels/
- Spatial SDK `SceneSwapchain` API reference v0.13.1:
  https://developers.meta.com/horizon/reference/spatial-sdk/v0.13.1/com_meta_spatial_runtime_sceneswapchain/
- Spatial SDK `Scene` API reference v0.13.1:
  https://developers.meta.com/horizon/reference/spatial-sdk/v0.13.1/com_meta_spatial_runtime_scene/
- Spatial SDK `SceneQuadLayer` API reference v0.13.1:
  https://developers.meta.com/horizon/reference/spatial-sdk/v0.13.1/com_meta_spatial_runtime_scenequadlayer/
- Spatial SDK `StereoMode` API reference v0.13.1:
  https://developers.meta.com/horizon/reference/spatial-sdk/v0.13.1/com_meta_spatial_runtime_stereomode/
- Spatial SDK `MediaPanelRenderOptions` API reference v0.13.1:
  https://developers.meta.com/horizon/reference/spatial-sdk/v0.13.1/com_meta_spatial_toolkit_mediapanelrenderoptions/
- Meta native OpenXR frame-loop documentation, used as the ownership boundary
  for why the embedded surface path should not run a second native
  `xrWaitFrame`/`xrBeginFrame`/`xrEndFrame` loop inside `AppSystemActivity`:
  https://developers.meta.com/horizon/documentation/native/android/mobile-openxr-frames/
- Meta native OpenXR SDK samples:
  https://developers.meta.com/horizon/documentation/native/native-openxr-sdk-sample/

Official Android:

- Android NDK `ANativeWindow`, used as the Java/Kotlin `Surface` to native
  Vulkan WSI bridge:
  https://developer.android.com/ndk/reference/group/a-native-window
- Support large screen resizability:
  https://developer.android.com/games/develop/multiplatform/support-large-screen-resizability
- ChromeOS window management, for Android manifest launch-size semantics:
  https://developer.android.com/develop/devices/chromeos/learn/window-management

Community/context, not treated as API contract:

- Spatial SDK community forum note on per-frame work versus `onSceneTick()`:
  https://communityforums.atmeta.com/discussions/SpatialSDK/run-every-frame-vs-every-tick/1304829
- Spatial SDK community category for follow-up questions:
  https://communityforums.atmeta.com/category/developer/discussions/SpatialSDK

## Architecture Decisions

- Lane: create `apps/kuramoto-spatial-sdk-android` as a separate app lane. Do
  not add Spatial SDK, AndroidX, Compose, `AppSystemActivity`, `VrActivity`, or
  GLXF tokens to the existing native renderer app source/build path.
- Package/activity: use a new package,
  `io.github.mesmerprism.rustyquest.kuramoto_spatial`, with an immersive
  Spatial SDK activity as the launcher. Keep
  `io.github.mesmerprism.rustyquest.native_renderer/android.app.NativeActivity`
  unchanged.
- Panel ownership: the Spatial SDK activity owns panel registration, entity
  spawn, transform, scale, meter size, and display options. The panel is a
  low-rate experiment workflow UI and logger, not render authority.
- UI implementation: prefer a single view-based Compose panel for the first
  lane because it avoids embedding the old NativeActivity-coupled Java panel
  and gives direct access to `QuadShapeOptions`, `DpPerMeterDisplayOptions`,
  `Transform`, `Pose`, and `Scale`.
- Experiment state: store app-private session JSON/JSONL under this package's
  files directory. Preserve the join keys used by the native experiment rows:
  `participant_id`, `session_id`, `block_index`, `block_number`,
  `condition_id`, `profile_id`, and `surface_target_id`.
- Native interop: no native renderer control in the first Spatial SDK lane.
  This keeps the native GPU hand/particle path preserved and avoids claiming
  that Spatial SDK panel placement validates the native app-owned renderer.
- Particle visuals: do not represent Kuramoto particles as one Spatial SDK
  mesh entity per particle. The study target remains GPU shader/buffer driven.
  Any Spatial SDK particle slice must use a shader-backed draw path, a batched
  procedural surface, or explicit low-rate control of the existing native
  Vulkan renderer; per-particle `mesh://sphere`/entity spawning is only a
  rejected diagnostic shortcut.
- Renderer authority refinement: the target architecture is now Spatial SDK as
  the world-space UI/control shell and the native OpenXR/Vulkan stack as the
  particle/rendering authority. The existing native same-APK 2D panel already
  proves the useful low-rate contract shape: Android UI requests values,
  Rust/JNI queues and validates them, and the renderer applies accepted values
  on its own frame loop. The Spatial SDK path should reuse that pattern instead
  of porting the particle renderer into Spatial SDK entities.
- Coexistence boundary: local SDK API inspection shows Spatial SDK owns its own
  `VrActivity`/`AppSystemActivity` immersive runtime. `Scene` exposes
  `getOpenXrInstanceHandle()`, `getOpenXrSessionHandle()`,
  `getOpenXrGetInstanceProcAddrHandle()`, `registerRequiredOpenXRExtensions()`,
  `SceneSwapchain.getSurface()`, panel/media `Surface` registrations, and
  direct mesh geometry update hooks. These are possible native interop probes,
  but they are not evidence that the current `android.app.NativeActivity`
  OpenXR/Vulkan frame loop can be embedded unchanged inside a Spatial SDK
  activity.
- Renderer-adapter proof: the no-render Rust/JNI and OpenXR function-resolution
  probes must remain separate from the Vulkan frame loop until the next slice
  can prove one renderer-backend entry point at a time. Rendering into a
  Spatial SDK-owned `Surface` or reusing Spatial SDK's OpenXR session handles
  would be a new backend/adapter for the native renderer, not a mutation of the
  existing native APK route.
- Current no-render probe boundary: the Spatial SDK lane now observes
  SDK-owned OpenXR handle availability and creates/destroys a tiny
  `PanelSurface` at VR-ready, but it does not hand those objects to the native
  renderer yet. This remains an observe-only guardrail around panel/surface
  capability before any Vulkan backend work is attempted.
- Native receipt probe boundary: the Spatial SDK APK now packages a separate
  Rust cdylib, `libkuramoto_spatial_native_receipt.so`, and loads it from
  `KuramotoSpatialActivity`. Kotlin passes the Spatial SDK-owned OpenXR
  instance/session/getInstanceProcAddr handle values plus the `PanelSurface`
  validity bit to a no-render JNI function, which returns/logs a bitmask
  receipt. This proves the same-activity bridge shape needed for a
  single-immersive-owner architecture without touching Vulkan renderer
  ownership yet.
- OpenXR handle-usability boundary: the same Rust cdylib now uses the
  SDK-provided `getInstanceProcAddr` handle to resolve and call
  `xrGetInstanceProperties` against the SDK-owned OpenXR instance. This proves
  a native backend under `AppSystemActivity` can use the SDK OpenXR function
  table for no-render queries. It still does not create Vulkan objects, submit
  frames, or take ownership of the Spatial SDK frame loop.
- OpenXR Vulkan capability boundary: the same no-render bridge now resolves
  `xrGetSystem`, calls `xrGetVulkanGraphicsRequirements2KHR`, and resolves the
  Vulkan-enable2 entrypoints `xrCreateVulkanInstanceKHR`,
  `xrGetVulkanGraphicsDevice2KHR`, and `xrCreateVulkanDeviceKHR` from the
  SDK-owned OpenXR instance. This proves the Spatial SDK instance was created
  with the OpenXR Vulkan capability path exposed. It still does not create a
  Vulkan instance/device or submit frames.
- No-present Vulkan object boundary: the Spatial SDK lane now attempts the
  next renderer-adapter slice inside the no-render Rust/JNI receipt library:
  create a Vulkan instance with `xrCreateVulkanInstanceKHR`, obtain the
  OpenXR-selected physical device with `xrGetVulkanGraphicsDevice2KHR`, select
  a graphics+compute queue family, create a logical Vulkan device with
  `xrCreateVulkanDeviceKHR`, obtain queue 0, and immediately destroy the
  device and instance. This still does not create an OpenXR session, command
  pool, swapchain, shader module, storage buffer, frame loop, or particle
  submission. Its only purpose is to prove whether SDK-owned OpenXR handles can
  host the native Vulkan object layer required by the later Kuramoto compute
  path.
- Visible native surface boundary: the Spatial SDK lane now also registers a
  second Spatial SDK `VideoSurfacePanelRegistration` as a native render target
  beside the questionnaire panel. Kotlin passes the panel-provided Android
  `Surface` to Rust through
  `nativeStartSurfaceParticleLayer(...)`; Rust creates a Vulkan Android surface
  and WSI swapchain, records compute and graphics pipelines, dispatches a
  compute shader that writes a storage buffer, then draws a batched 2048-point
  particle field by consuming that buffer from the vertex stage. This proves a
  visible native GPU storage-buffer particle route can live inside the same
  `AppSystemActivity` shell as the Spatial SDK UI panel without per-particle
  Spatial SDK entities. It is not yet the final Kuramoto/private-particle
  payload path and does not prove reuse of the existing native OpenXR
  projection swapchain.
- Surface-backed visual-plane decision: the correct label for the currently
  supported route is native Vulkan particle layer embedded in Spatial SDK world
  space. The Spatial SDK shell owns the app/session, world placement, panel
  registration, size, and visibility; the native Vulkan layer owns particle
  simulation and rendering into an SDK-provided Android-compatible surface.
  This is not "particles on the questionnaire UI panel", not one Spatial SDK
  entity per particle, and not a native OpenXR projection layer owned by the
  existing `NativeActivity` renderer.
- Surface primitive decision: the headset-proven route remains
  `VideoSurfacePanelRegistration` plus its `Surface` callback because it gives
  a supported direct-to-surface media-panel path with SDK world placement and
  stereo render options. `SceneSwapchain.createAsAndroid(...)` paired with
  `Scene.createQuadLayer(...)`/`SceneQuadLayer` remains a useful documented
  manual-layer reference and future proof target, but the earlier runtime
  `SceneQuadLayer`/`SceneMesh("mesh://box")` attempt was unstable enough that
  it should not replace the working media/surface panel lane yet.
- Embedded Vulkan producer boundary: the native surface renderer behaves like
  an Android Vulkan WSI producer. It receives one Java/Kotlin `Surface`,
  obtains an `ANativeWindow`, creates one `VkSurfaceKHR`/swapchain for that
  window, and presents into that surface. In this embedded lane it must not
  call `xrWaitFrame`, `xrBeginFrame`, or `xrEndFrame`; Spatial SDK remains the
  immersive frame/session owner. The native producer should also not depend on
  `onSceneTick()` for render cadence because scene ticks can be throttled or
  policy-driven; the render loop is paced by its own swapchain/acquire/present
  path and app lifecycle state.
- Stereo visual target: the current validated stereo surface lane is
  `native-surface-compute-stereo-proof`, but the active visible source slice is
  now `surfaceLayerMode=native-kuramoto-study-hand-anchor-particles` instead
  of diagnostic point particles or visible replay mesh triangles. Keep the mono
  surface mode as a fallback, and use a packed side-by-side surface through
  `StereoMode.LeftRight`: current projection-plane proof per-eye extent
  `1024x1024`, packed surface extent `2048x1024`, left eye in x `0..1023`,
  right eye in x `1024..2047`, and a physical panel aspect matching the per-eye
  image rather than the packed image. The native receipt library still
  dispatches the compute particle buffer for resource-continuity markers, but
  the rendered layer is now native study-style hand-anchor particle billboards
  over a resident recorded-rig mesh source with
  `properStereoStudyParticles=true` and
  `replayStereoProjection=per-eye-spatial-sdk-panel-plane-ray-intersection`.
  Live OpenXR joints use the same recorded-compatible compact skinning shape as
  the native renderer, while forced replay remains the no-blank fallback. This
  is not yet the final private Kuramoto compute payload path.
- Particle surface placement and projection metadata: the current projection
  proof treats the Spatial SDK media panel as a viewer-pose projection plane,
  not an arbitrary world panel. The Kotlin activity updates the particle panel
  from `Scene.getViewerPose()` on scene ticks, records `Scene.getEyeOffsets()`
  for evidence, and emits `projectionLockedParticleSurface=true`,
  `placementMode=viewer-pose-projection-locked-quad`,
  `placementAuthority=spatial-sdk-viewer-pose-scene-tick`, target distance
  `0.72m`, a panel-plane-derived projection basis, and a `1.44m x 1.44m`
  physical footprint. The packed surface extent `2048x1024`, with
  `1024x1024` per eye. The particle surface also emits the projection-space
  vocabulary used by the current Spatial SDK surface route:
  `projectionContentMappingMode=world-to-spatial-sdk-panel-plane-left-right`,
  `targetProjectionSpace=spatial-sdk-panel-plane-perspective-projection`,
  `targetCoordinateSpace=spatial-sdk-surface-panel-eye-uv`, and full
  left/right `0.0;0.0;1.0;1.0` target surface UV rects. The reusable lesson
  from the private stereo-alignment reference is the target-footprint and
  render-view discipline, not its private visual effect formulas.
- Forced replay hand-mesh source boundary: the native receipt build now accepts
  `RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR` and embeds the same recorded
  hand capture shape used by the native renderer path: rig topology plus the
  first N `validation_mesh_jsonl` frames. Rust expands those validation frames
  into resident storage-buffer triangle vertices and draws them per packed eye
  using a virtual-IPD projection. If no external capture is supplied, the build
  falls back to the public replay-shape fixture. This proves a forced replay
  GPU mesh-hand visual source can live on the Spatial SDK Vulkan surface; it
  does not yet prove live OpenXR hand mesh ingestion or the private Kuramoto
  shader payloads.
- Foreground visual mode target: the study UI and particle visual surface
  should remain separate planes. UI mode can keep the questionnaire/settings
  panel visible and the particle surface smaller/lower priority. Visual mode
  should hide, move, or lower the questionnaire panel and make the particle
  surface larger, opaque, high `zIndex`, and foregrounded. Start opaque:
  direct-to-surface transparency and SDK material effects are separate risk
  items and should not block the stereo proof.
- Panel-to-native parameter boundary: the Spatial SDK Compose panel now has a
  small low-rate parameter bridge for the native surface particle layer.
  `SurfaceParticleControls` submits bounded `driver0`, `driver1`, and point
  scale values through `nativeUpdateSurfaceParticleParameters(...)`; Rust stores
  those values atomically and the compute push constants consume them on the
  render thread. This mirrors the existing same-APK native panel pattern:
  Android UI requests bounded values, native code owns validation/application,
  and no high-rate particle buffers move through panel JSON.
- Experiment block condition handoff: block start now maps the randomized
  condition metadata to the same bounded parameter bridge
  (`movement_base_frequency_hz` to energy/`driver0`, `movement_coupling` to
  coherence/`driver1`) and returns the workflow panel to particle view. This
  preserves native renderer authority: the Spatial panel submits low-rate
  condition intent and scalar controls only.
- Foreground coexistence boundary: headset testing shows a separate Spatial
  SDK `AppSystemActivity` can foreground over the native renderer without
  killing the native process, and the native `NativeActivity` can be brought
  back with the same process id. It does not keep the native Vulkan/OpenXR
  frame loop visible or submitting while Spatial SDK is foreground: the native
  activity is paused, its window is terminated, and frame-like renderer markers
  stop. This makes activity switching viable for questionnaire breaks, but not
  for a continuous particle view with an overlaid world-space Spatial SDK panel.
- Feasible route split: if continuous particles plus a Spatial SDK panel is
  required, the next proof should rehost or adapt the native renderer under the
  Spatial SDK immersive owner, using the already-proven SDK OpenXR handles or
  SDK-owned `PanelSurface`. If temporary backgrounding is acceptable during
  questionnaire or setup, the native renderer can remain `NativeActivity` and
  use activity switching as a low-rate break workflow.

## Iteration Log

- 2026-06-25 initial read: repository starts clean on
  `codex/kuramoto-experiment-panel-workflow` at
  `252f753afb8168bc59cefed4e711484b130a2083`.
- 2026-06-25 branch: created `codex/spatial-sdk-kuramoto-lane` for the
  Spatial SDK lane so the pushed experiment-panel branch remains intact.
- 2026-06-25 official doc check: Meta docs support an `AppSystemActivity`
  activity, `registerPanels()`, `PanelRegistration`/`ComposeViewPanelRegistration`,
  `Entity.createPanelEntity(...)`, `Transform(Pose(...))`, `QuadShapeOptions`,
  and `DpPerMeterDisplayOptions` for real Spatial SDK panel placement/resolution.
- 2026-06-25 local architecture check: existing native renderer static gates
  deliberately reject Spatial SDK tokens in the same-APK 2D panel and report
  `spatial_sdk_packaged = $false`. Therefore the first implementation must be a
  separate lane and separate validation slot.
- 2026-06-25 implementation: added
  `apps/kuramoto-spatial-sdk-android` with Gradle 9.4.1, AGP 8.11.1, Kotlin
  2.1.0, Spatial SDK 0.13.1, and a Compose-backed `AppSystemActivity` panel.
- 2026-06-25 build iteration: the first Gradle bootstrap failed because
  PowerShell `Invoke-WebRequest` threw a null reference while fetching the
  Gradle `.sha256`. The build wrapper now uses a small .NET download helper
  and still verifies SHA-256 before extracting Gradle.
- 2026-06-25 build iteration: Kotlin compilation failed when the root build
  file put the Spatial SDK and Compose plugins on the top-level plugin
  classpath. Aligning with the official sample fixed the issue: root declares
  Android/Kotlin only, app module applies Meta Spatial and Compose.
- 2026-06-25 build iteration: enabled `org.gradle.configuration-cache=true`
  because the official Meta Spatial SDK sample uses it. The final rebuild
  reused the configuration cache.
- 2026-06-25 panel iteration: initial screenshots from `adb screencap` showed
  the VR compositor/performance overlay but not the Spatial SDK panel layer.
  The panel now uses the official sample view-origin convention
  `scene.setViewOrigin(0, 0, 2, 180)` with default panel pose
  `y=1.1, z=-1.7`, and an explicit high-contrast Compose surface. The saved
  ADB screenshot still does not include the Spatial SDK panel layer; logcat,
  foreground activity state, and SurfaceFlinger layer evidence are the headset
  proof for this run.
- 2026-06-25 headset validation: installed and launched the APK on Quest 3S
  serial `3487C10H3M017Q` with serial-scoped ADB under Agent Board leases. The
  validation action drove participant setup, Polar placeholder setup, surface
  selection, block start, automatic elapsed-block transition, questionnaire
  submission, and completion markers.
- 2026-06-25 official sample control: the local Meta Spatial SDK
  `StarterSample` checkout did not compile until Meta Spatial Editor v16.1 was
  downloaded and administratively extracted under ignored `local-artifacts`.
  After pointing the sample's local Gradle `spatial.scenes.cliPath` at the
  extracted `CLI.exe`, the official sample built and launched on Quest. The
  headset operator confirmed that the official sample panel is visible inside
  its 3D environment.
- 2026-06-25 visibility iteration: because the official sample is visible and
  the Rusty lane previously spawned only a programmatic panel in an otherwise
  empty black immersive scene, the app now starts with explicit diagnostic
  spatial reference geometry: an unlit skybox, a colored backboard behind the
  panel, and a light floor slab. The panel also now uses sample-sized meter
  dimensions (`2.048m x 1.254m`), an explicit `PanelDimensions(Vector2)`,
  `Visible(true)`, an opaque Compose view background, and the sample-aligned
  180-degree quaternion component instead of relying on the panel defaults.
- 2026-06-25 panel content probe: headset inspection showed the diagnostic
  environment and panel plane were visible, but the panel content appeared
  black. The panel now keeps the diagnostic environment and switches to an
  opaque yellow Android window/Compose background with a teal
  `PANEL COLOR PROBE` banner and an orange `Visible Button`; the build manifest
  records this as `panel_content_probe =
  opaque-yellow-background-teal-banner-orange-button`.
- 2026-06-25 sample diff follow-up: diffing against Meta's visible
  `StarterSample` showed two material differences: the sample panel is authored
  into exported GLXF scene data, and its panel node uses the scene quaternion
  `[6.12323426e-17, 6.12323426e-17, 1, -3.74939976e-33]` rather than an
  identity runtime pose. The first follow-up run keeps the runtime-created
  panel but applies that sample quaternion to test whether the black plane is
  the panel back face before moving to a scene-authored GLXF panel.
- 2026-06-25 confirmed panel visibility fix: headset inspection confirmed the
  sample quaternion fixed the fully black panel. A runtime-created
  `Entity.createPanelEntity(...)` can show Compose content in this lane when
  its `Transform(Pose(..., Quaternion(...)))` uses the same facing convention
  as the official sample scene panel. The GLXF scene-authored panel remains a
  useful reference/control path, but it is not required for the current
  runtime-created panel route.
- 2026-06-25 particle-port correction: a proposed `mesh://sphere` particle
  visual would not represent the real Rusty Kuramoto particle workflow. The
  port target is shader-based GPU particle rendering or native Vulkan renderer
  coordination, not per-particle Spatial SDK mesh entities. Spatial SDK custom
  shader support is relevant, but its GLSL material pipeline is not the same
  as the current native Vulkan compute/storage-buffer/private-particle stack;
  the next design step is to choose a shader-port lane that preserves batching
  and low-rate study controls without routing high-rate particles through the
  panel.
- 2026-06-25 native renderer coexistence investigation: the working native
  panel is a plain same-package `com.oculus.intent.category.2D` Activity
  launched by the Rust `NativeActivity` through JNI. It communicates with the
  renderer through bounded JSON candidates and JNI live queues such as
  `nativeSubmitLivePrivateParticleDynamics(...)` and
  `nativeStartKuramotoExperimentBlock(...)`; Rust validates and applies the
  effective values. This is the contract to preserve for Spatial SDK.
- 2026-06-25 Spatial SDK API inspection: `AppSystemActivity` extends Spatial
  SDK `VrActivity`, not Android `NativeActivity`. The SDK includes
  activity/intent panel registrations for hybrid 2D panels, media panel
  registrations that supply an Android `Surface`, stereo canvas panels, panel
  input/interactivity components, `SceneMesh.updateGeometryDirect(...)`, and
  OpenXR handle getters on `Scene`. No local class or official doc found in
  this pass documents embedding an existing `android.app.NativeActivity`
  OpenXR/Vulkan loop unchanged inside an `AppSystemActivity`.
- 2026-06-25 official hybrid-app doc check: Meta's hybrid docs support
  jumping between 2D panel activities and immersive activities inside one app,
  embedded panels, and cooperative interaction modes. The 2D panel
  communication docs also state the built-in panel-to-scene helper is limited
  to UI within a single Activity, while activity-based panels need a different
  communication pattern. This reinforces a low-rate command/receipt bridge
  rather than high-rate particle data in panel JSON.
- 2026-06-25 local validation after investigation:
  `tools/Test-KuramotoSpatialSdkAndroid.ps1 -RepoRoot .` passed,
  `tools/Build-KuramotoSpatialSdkAndroid.ps1 -RepoRoot .` passed with the
  Gradle 9.4.1 configuration cache reused, and
  `tools/checks/Test-NativeRendererAndroidScaffoldStatic.ps1 -RepoRoot .`
  passed. The APK hash remained
  `faac5631d5a20ae5d4432d2d8f3858124655447e63519639e02e194cbc7076c4`.
  No headset launch was run for this investigation because no runtime
  coexistence prototype exists yet.
- 2026-06-25 no-render native interop probe implementation: added
  `SpatialNativeInteropProbe` to the Spatial SDK activity. It logs
  `channel=native-interop-probe` at scene-ready and VR-ready, records the
  Spatial SDK `Scene` runtime name plus nonzero status for OpenXR
  instance/session/getInstanceProcAddr handles, and at VR-ready creates then
  destroys a 64px `PanelSurface`. This is intentionally observe-only: no
  particle renderer state, high-rate buffers, or native Vulkan frame loop is
  touched.
- 2026-06-25 no-render native interop headset validation: rebuilt the APK with
  the probe and launched it on Quest 3S serial `3487C10H3M017Q`. The marker
  stream reported `runtimeName=MHE`, all three Spatial SDK OpenXR handles
  nonzero at scene-ready and VR-ready, and
  `surfaceProbeStatus=created-destroyed-no-render surfaceValid=true` at
  VR-ready. No `AndroidRuntime` crash lines were present in the captured tag
  log.
- 2026-06-25 native-vs-Spatial foreground coexistence probe: installed the
  current native renderer APK and Spatial SDK APK on Quest 3S serial
  `3487C10H3M017Q`, launched native first, then foregrounded
  `KuramotoSpatialActivity`. Streaming logcat captured native lifecycle
  markers: `event=pause` followed by `event=terminate-window` as the Spatial
  SDK activity became foreground. The native process stayed alive
  (`pid=2913` before and after), but `native_frame_like_after_spatial_count=0`.
  Spatial SDK still reported nonzero OpenXR handles and
  `PanelSurface` `surfaceValid=true`. This rejects the "separate Spatial SDK
  activity as live overlay over NativeActivity" route.
- 2026-06-25 return-to-native foreground probe: from the Spatial SDK foreground
  state, launching `android.app.NativeActivity` brought the native task back to
  front with the same native process id (`pid=2913`), emitted native
  `event=resume` and `event=init-window`, and produced 119 frame-like native
  markers in the capture window with zero crash lines. This keeps an
  activity-switch questionnaire/setup workflow viable when pausing particles is
  acceptable.
- 2026-06-25 native receipt probe implementation: added
  `apps/kuramoto-spatial-sdk-android/native-receipt`, a minimal Rust cdylib
  packaged as `libkuramoto_spatial_native_receipt.so` through Gradle generated
  `jniLibs`. The Spatial activity loads it, calls
  `nativeRecordNoRenderInteropReceipt(...)` after each SDK handle/surface
  probe, and logs `channel=native-interop-receipt` with the native receipt
  bitmask. The native side also emits
  `RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-interop-receipt`.
- 2026-06-25 native receipt probe headset validation: rebuilt and launched on
  Quest 3S serial `3487C10H3M017Q`. The APK packaged
  `lib/arm64-v8a/libkuramoto_spatial_native_receipt.so`; `System.loadLibrary`
  succeeded; scene-ready native receipt mask was `15` (OpenXR instance,
  session, getInstanceProcAddr, no surface yet); VR-ready receipt mask was
  `31` (same handle bits plus `PanelSurface` valid). Rust and Kotlin both
  emitted `channel=native-interop-receipt` markers and no `AndroidRuntime`
  crash lines were captured.
- 2026-06-25 OpenXR handle-usability probe implementation and validation:
  added `openxr-sys` ABI types to the native receipt crate and used the
  Spatial SDK `getInstanceProcAddr` handle to resolve/call
  `xrGetInstanceProperties`. Headset validation returned scene-ready receipt
  mask `239` and VR-ready receipt mask `255`; all OpenXR handle bits,
  `getInstanceProcAddr` callable, `xrGetInstanceProperties` resolved,
  `xrGetInstanceProperties` succeeded, and VR-ready `PanelSurface` valid bits
  were true. The native OpenXR runtime name/version came back
  `Oculus 204.201.0`, while the Spatial SDK `Scene.getRuntimeName()` marker
  still reported `MHE`. No crash lines were captured.
- 2026-06-25 OpenXR Vulkan capability probe implementation and validation:
  extended the no-render Rust receipt library to resolve `xrGetSystem`, query
  HMD system id, call `xrGetVulkanGraphicsRequirements2KHR`, and resolve
  `xrCreateVulkanInstanceKHR`, `xrGetVulkanGraphicsDevice2KHR`, and
  `xrCreateVulkanDeviceKHR`. Headset validation returned scene-ready receipt
  mask `32751` and VR-ready receipt mask `32767`; Vulkan requirements
  succeeded with min API `1.0.0` and max API `1.2.0`; all three Vulkan-enable2
  entrypoints resolved; the SDK `PanelSurface` was valid at VR-ready; and no
  crash lines were captured.
- 2026-06-25 no-present Vulkan object probe implementation: added `ash` to the
  no-render native receipt crate and mirrored the native renderer's
  OpenXR-backed Vulkan creation path only up to object creation. The probe now
  loads the Vulkan loader, calls `xrCreateVulkanInstanceKHR`, calls
  `xrGetVulkanGraphicsDevice2KHR`, checks for a graphics+compute queue family,
  calls `xrCreateVulkanDeviceKHR`, obtains queue 0, and immediately destroys
  the Vulkan device and instance. Kotlin and Rust receipt markers now expose
  `vkInstanceCreated`, `vkGraphicsDeviceObtained`,
  `vkGraphicsComputeQueueFound`, `vkDeviceCreated`, `vkQueueObtained`, and
  `vkObjectsDestroyed`; Rust also emits a concise
  `channel=native-vulkan-object-probe` marker for object-creation result
  details because the full receipt line can exceed logcat's useful line length.
  Android target `cargo check` passed before headset validation.
- 2026-06-25 no-present Vulkan object probe headset validation: rebuilt and
  launched on Quest 3S serial `3487C10H3M017Q`. VR-ready native receipt mask
  was `2097151`, with `PanelSurface` valid and all OpenXR/Vulkan capability
  and object bits true. The concise native Vulkan marker reported
  `vkCreateInstanceResult=xr_success_vk_success`,
  `vkGraphicsDeviceResult=success`, `vkQueueFamilyIndex=0`,
  `vkCreateDeviceResult=xr_success_vk_success`,
  `vkQueueObtained=true`, and `vkObjectsDestroyed=true`; the selected Vulkan
  device was `Adreno__TM__740` with API `1.3.295`. No `AndroidRuntime` crash
  lines were captured.
- 2026-06-25 manual `SceneQuadLayer` surface attempt: creating a
  `SceneSwapchain.createAsAndroid(...)` surface was viable, but the manual
  `SceneQuadLayer` anchor path was not stable from runtime-created toolkit
  entities. A plain `SceneObject(scene, entity)` produced a generic SDK
  exception, and attempting a runtime `SceneMesh("mesh://box", "", 0)` failed
  with a native GLTF loader assertion because `mesh://box` is a toolkit URI,
  not an APK mesh file for `SceneMesh`. The route was abandoned in favor of the
  SDK-supported media/surface panel registration callback.
- 2026-06-25 visible native surface-particle implementation: added a second
  panel id, `kuramoto_particle_surface_panel`, registered through
  `VideoSurfacePanelRegistration`. Its `surfaceConsumer` starts the Rust JNI
  `nativeStartSurfaceParticleLayer(...)` path. The native receipt crate now has
  a `surface_particle_layer` module, a shader build script, and GLSL
  `surface_particles.comp.glsl`, `surface_particles.vert.glsl`, and
  `surface_particles.frag.glsl` sources. The native path converts the Java
  `Surface` through `ANativeWindow_fromSurface`, creates a Vulkan Android
  surface and swapchain, allocates a storage buffer, descriptor set, compute
  pipeline, and point-list graphics pipeline, dispatches compute to fill
  particle rows, and reports `render-loop-ready` and `first-frame-presented`.
- 2026-06-25 compute-resource parity source slice: replaced the visible
  surface layer's procedural vertex-only point generation with a native Vulkan
  compute/storage-buffer path. The command buffer dispatches
  `surface_particles.comp.glsl`, inserts a compute-to-vertex shader barrier,
  then draws the same 2048 points from the GPU-written buffer. The Spatial SDK
  panel now includes `SurfaceParticleControls` and submits bounded low-rate
  `driver0`, `driver1`, and point-scale values through
  `nativeUpdateSurfaceParticleParameters(...)`; native markers expose
  `computeParticleStateBuffer=true`, `computeShaderDispatchReady=true`, and
  `computeParameterBridge=true`. Android-target `cargo check -p
  kuramoto-spatial-native-receipt --target aarch64-linux-android` passed after
  this source slice.
- 2026-06-25 compute-resource parity build validation:
  `tools/Test-KuramotoSpatialSdkAndroid.ps1 -RepoRoot .` passed with static
  tokens for the compute shader, storage buffer, descriptor set, dispatch,
  compute-to-vertex barrier, and JNI parameter bridge. The focused APK build
  then passed with Gradle configuration cache reuse. The rebuilt APK SHA-256 is
  `e3cbf0a58bbdd98f1ff4a717436f873d3332d277e0c808c94ceb46b2c6641515`,
  and its build manifest records
  `native_surface_particle_layer_rendering =
  native-vulkan-wsi-surface-panel-compute-storage-buffer`.
- 2026-06-25 compute-resource parity headset validation: installed and
  launched the rebuilt APK on Quest 3S serial `3487C10H3M017Q` with live
  tag-filtered logcat already running. Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-142213-compute-storage-particles-live-log`.
  APK SHA-256:
  `e3cbf0a58bbdd98f1ff4a717436f873d3332d277e0c808c94ceb46b2c6641515`.
  The run reported `panel-entity-spawned`, `surface-panel-ready
  surfaceValid=true`, native `parameters-updated`, Kotlin
  `parameters-submitted`, `render-loop-ready
  computeParticleStateBuffer=true computeShaderDispatchReady=true
  particleStorageBufferBytes=65536 particleCount=2048 extent=1024x768
  swapchainImages=3`, and `first-frame-presented
  computeParticleStateBuffer=true`. The Spatial SDK OpenXR/Vulkan object
  receipt remained green at VR-ready with mask `2097151`; the workflow
  self-test completed; the captured live tag log had zero `AndroidRuntime`
  crash lines and zero `status=render-failed` lines. The app was left
  foregrounded after the run as process id `17809`.
- 2026-06-25 external research handoff fold-in: reviewed two web-research
  handoff notes and incorporated the durable API/resource links plus the
  architecture implication into this working plan. The current validated path
  remains Spatial SDK `AppSystemActivity` plus
  `VideoSurfacePanelRegistration` for the world-positioned visual surface and
  native Vulkan WSI/compute/storage-buffer rendering for particles. The next
  source slice is now explicitly named `native-surface-compute-stereo-proof`,
  using `StereoMode.LeftRight`, packed extent `2048x768`, per-eye extent
  `1024x768`, and a first proof with duplicated mono particles plus L/R debug
  markers before IPD/off-axis projection work.
- 2026-06-25 packed-stereo source slice: started
  `native-surface-compute-stereo-proof` by changing the Spatial SDK media
  surface to `StereoMode.LeftRight`, requesting packed extent `2048x768` while
  keeping per-eye extent `1024x768`, preserving the per-eye physical panel
  aspect, switching the native Vulkan graphics pipeline to dynamic
  viewport/scissor state, and drawing the compute-written storage-buffer
  particle field into both packed halves with L/R debug marker tinting. This
  is still a duplicated-mono stereo proof, not yet IPD-separated or off-axis
  projection.
- 2026-06-25 packed-stereo headset validation: rebuilt and launched on Quest
  3S serial `3487C10H3M017Q`. Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-143715-packed-stereo-surface-particles`.
  APK SHA-256:
  `ca64eefaf0d069f8142c836a55ce7ec0851a7d995e0b6f2d495a4179cc4085e7`.
  The run reported `surface-panel-ready surfaceValid=true
  stereoMode=LeftRight perEyeExtent=1024x768 packedExtent=2048x768`, native
  `render-loop-ready native-surface-compute-stereo-proof=true
  sideBySideStereoProof=true stereoDebugMarkers=true
  computeParticleStateBuffer=true computeShaderDispatchReady=true
  particleStorageBufferBytes=65536 particleCount=2048 extent=2048x768
  stereoMode=LeftRight perEyeExtent=1024x768 packedExtent=2048x768`, and
  `first-frame-presented` with the same packed-stereo markers. Kotlin and Rust
  parameter bridge markers both reported `computeParameterBridge=true`; the
  workflow self-test completed; the captured tag log had zero
  `AndroidRuntime` crash lines and zero `status=render-failed` lines. The app
  was left foregrounded after the run as process id `19908`.
- 2026-06-25 camera-facing particle-surface source slice: moved the Spatial SDK
  Vulkan particle media surface from the side proof pose to a centered,
  foreground view-origin proof pose. The source now emits
  `cameraFacingParticleSurface=true`,
  `placementMode=view-origin-camera-facing-quad`,
  `placementAuthority=spatial-sdk-local-floor-view-origin-static`,
  `projectionContentMappingMode=per-eye-surface-raster-left-right`,
  `targetCoordinateSpace=spatial-sdk-surface-panel-eye-uv`, and full left/right
  target surface UV rects in both Kotlin and native markers. This is the
  immediate baseline for headset visual inspection; dynamic camera-following
  remains a follow-up until the SDK viewer/head entity route is identified.
- 2026-06-25 camera-facing particle-surface headset validation: rebuilt and
  launched on Quest 3S serial `3487C10H3M017Q`. Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-145334-camera-facing-spatial-particle-quad`.
  APK SHA-256:
  `fe67f51763280c5a96ceacca0e0cba48025388e55eaf67167dffe034973d9a00`.
  The fresh app process reported `panel-entity-spawned`,
  `start-requested`, and `surface-panel-ready` with
  `cameraFacingParticleSurface=true`,
  `placementMode=view-origin-camera-facing-quad`,
  `placementAuthority=spatial-sdk-local-floor-view-origin-static`,
  `x=0.0 y=1.22 z=-1.1`, `widthMeters=1.28 heightMeters=0.96`,
  `projectionContentMappingMode=per-eye-surface-raster-left-right`,
  `targetCoordinateSpace=spatial-sdk-surface-panel-eye-uv`, full left/right
  target surface UV rects, `stereoMode=LeftRight`, `perEyeExtent=1024x768`,
  and `packedExtent=2048x768`. Native Vulkan reported `render-loop-ready` and
  `first-frame-presented` with the same per-eye surface raster mapping markers,
  `computeParticleStateBuffer=true`, and `computeShaderDispatchReady=true`.
  The self-test completed, the PID-filtered log had zero `AndroidRuntime`
  lines and zero `status=render-failed` lines, and the app was left running as
  process id `22071`.
- 2026-06-25 projection-plane source slice: replaced the fixed near
  camera-facing particle quad with a viewer-pose driven projection plane. The
  activity now updates the particle panel transform from `Scene.getViewerPose()`
  in `onSceneTick()`, records `Scene.getEyeOffsets()` in bounded
  `projection-plane-updated` markers, switches the proof surface to
  `StereoMode.LeftRight` with per-eye extent `1024x1024` and packed extent
  `2048x1024`, and maps the physical panel footprint to
  `targetProjectionSpace=openxr-eye-fov-tangent-space` using target tangents
  `-1.0;1.0;-1.0;1.0` at `0.72m`. This is the first Spatial SDK equivalent of
  the native renderer's per-eye projection target discipline; the SDK still
  owns final quad composition.
- 2026-06-25 projection-plane front-face correction: headset visual inspection
  showed the projection plane was placed and sized correctly, but particles were
  not visible. The native Vulkan markers were green, so the likely issue was
  the media panel front/back orientation after the viewer-pose quaternion
  change. The plane orientation now uses `Quaternion.fromDirection(forward, up)`
  and logs `projectionPlaneFacingMode=viewer-forward-front-face`; the native
  diagnostic point size floor was also raised for this proof. Rebuilt and
  launched on Quest 3S serial `3487C10H3M017Q`. Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-151641-projection-plane-front-face-particles`.
  APK SHA-256:
  `cd7d8d73301ad3162d738c8730fd90bb3edd7bdc67b949415b8489072647b4ab`.
  The app was left running as process id `18295`; the PID-filtered log reported
  `projection-plane-updated`, `projectionPlaneFacingMode=viewer-forward-front-face`,
  `render-loop-ready`, `first-frame-presented`, `perEyeExtent=1024x1024`,
  `packedExtent=2048x1024`, zero `AndroidRuntime` lines, and zero
  `status=render-failed` lines.
- 2026-06-25 headset visual confirmation after front-face correction:
  particles were visible again. The previous opposite-facing projection-plane
  orientation made the questionnaire panel appear in front of the particle
  quad; after the front-face correction, the questionnaire panel appeared
  behind the particle quad. Treat UI/particle visual ordering as an explicit
  mode decision from here: visual mode can keep the projection-plane particle
  quad foregrounded, while questionnaire/settings mode should move, hide, or
  explicitly foreground the UI panel instead of relying on incidental quad
  orientation or depth order.
- 2026-06-25 forced replay hand-mesh source slice: switched the visible native
  surface content from diagnostic point particles to forced replay
  validation-mesh hand triangles. The build wrapper now accepts
  `-RecordedHandCaptureDir` and `-RecordedHandFrameLimit`, passes
  `RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR` into the native receipt build,
  and embeds rig topology plus `validation_mesh_jsonl` frames into
  `recorded_hand_replay_source.json`. The native receipt library uploads those
  frames into a resident storage buffer and draws them into each packed eye
  with markers `surfaceLayerMode=forced-replay-gpu-mesh-hands`,
  `forcedReplayHands=true`, `gpuReplayHandsResident=true`,
  `properStereoReplayHands=true`, and
  `replayStereoProjection=per-eye-spatial-sdk-panel-plane-ray-intersection`.
  This preserves the
  Spatial SDK projection-plane surface and avoids per-hand or per-particle
  Spatial SDK mesh entities.
- 2026-06-25 forced replay hand-mesh headset visual confirmation: rebuilt,
  installed, and launched the forced replay hand-mesh APK on Quest 3S serial
  `3487C10H3M017Q`. Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-154717-forced-replay-gpu-hands`.
  APK SHA-256:
  `aeb6198b153841d1533ea1282cfe53460f52a515a1592d219e982b73012c5f2b`.
  The app install and `am start` succeeded and the headset operator confirmed
  that the forced replay mesh was visible on the Spatial SDK projection
  surface. The scripted logcat marker pull used an invalid timestamp filter and
  did not capture the expected marker lines, so that evidence JSON records the
  visual confirmation separately. The next source slice should replace the
  visible replay mesh with the native Kuramoto mesh study particle visual while
  keeping the same Spatial SDK projection-plane surface route.
- 2026-06-25 native Kuramoto study particle source slice: replaced the visible
  forced replay mesh draw with native study-style hand-anchor particle
  billboards. The active shader now uses the forced replay validation mesh as a
  resident coordinate source, chooses triangle anchors and barycentric
  positions in the vertex stage, and draws 1024 soft billboard particles per
  hand into each packed stereo eye. The markers now identify the layer as
  `surfaceLayerMode=native-kuramoto-study-hand-anchor-particles`,
  `nativeStudyParticlesVisible=true`, `handAnchorParticlesVisible=true`,
  `forcedReplayMeshVisible=false`,
  `handAnchorParticlePath=resident-recorded-rig-gpu-skinned-mesh-coordinate-anchor-billboards`,
  `handAnchorParticleCoordinateSource=live-openxr-world-joints-gpu-skinned-resident-mesh-with-forced-replay-fallback`,
  `privateKuramotoPayloadActive=false`, and
  `properStereoStudyParticles=true`. This is the intended bridge from the
  confirmed replay mesh proof toward the native Kuramoto mesh study mode; the
  next step after headset visibility is linking the private Kuramoto compute
  payload instead of the deterministic anchor-color particle output.
- 2026-06-25 live OpenXR joint source slice: added a live hand-joint input path
  inside the Spatial SDK surface renderer without introducing mesh objects for
  particles. Kotlin now passes the SDK-owned OpenXR
  instance/session/getInstanceProcAddr handles into
  `nativeStartSurfaceParticleLayer(...)`; Rust resolves
  `xrCreateHandTrackerEXT`, `xrLocateHandJointsEXT`,
  `xrCreateReferenceSpace`, and `xrConvertTimespecTimeToTimeKHR`, converts
  `CLOCK_MONOTONIC` into `XrTime`, creates left/right trackers, and uploads 52
  joint rows into a second Vulkan storage buffer. This joint-cloud slice proved
  live tracking and correct panel visibility, but it was not the correct study
  source because particles clustered around joints instead of remaining
  mesh-surface anchors with surface-normal dynamics. It was superseded by the
  resident rig skinning slice below.
- 2026-06-25 resident rig live-skinning correction: replaced the joint-cloud
  shader branch with the native renderer's compact skinning shape. The Spatial
  SDK lane now parses `bind_vertices`, `vertex_blend_indices`,
  `vertex_blend_weights`, `triangle_indices`, bind poses, and bind-joint
  source rows from the same recorded rig payload used by the native renderer;
  uploads those arrays as resident Vulkan storage buffers; maps live OpenXR's
  26 default joints into the recorded 21 compact runtime-joint order; derives
  five tip lengths from distal/tip pairs; and skins the recorded bind mesh in
  the particle vertex shader before choosing barycentric triangle anchors and
  normals. The live path now reports
  `liveHandJointGpuInputPath=recorded-compatible-compact-joint-pose-gpu-skinning`,
  `handAnchorParticlePath=resident-recorded-rig-gpu-skinned-mesh-coordinate-anchor-billboards`,
  `handAnchorParticleCoordinateSource=live-openxr-world-joints-gpu-skinned-resident-mesh-with-forced-replay-fallback`,
  `jointClusterMode=false`, and
  `liveHandCorrectPositionSizeProof=spatial-sdk-panel-plane-projection`.
  Forced replay remains the fallback when live hand rows or real capture rig
  metadata are unavailable. This is still an on-surface Spatial SDK panel-plane
  projection, not a second OpenXR frame loop and not a per-particle Spatial SDK
  entity path.
- 2026-06-25 live skinning robustness and diagnostic depth hotload: headset feedback
  showed the fallback recording had a full particle surface, while live hands
  could drop most particles and appeared at an approximately panel-distance
  offset. The accepted live joint row contract now keeps `status.x` as
  position-valid, treats `status.y` as the compact-frame pose-valid gate, and
  moves the stricter position-tracked flag to `status.w`. The production live
  particle path stays GPU-skinned like the native app: once the CPU adapter can
  upload the native-equivalent compact frame (21 runtime joint rows plus 5
  tip-length rows), the vertex shader skins every weighted bind-mesh vertex
  against resident bind poses and does not substitute CPU-side skinned mesh
  vertices. Hands with too few pose-valid joints fall back to the resident
  recording mesh. The live visual path also polls
  `debug.rustyquest.kuramoto_spatial.live_hand_depth_offset_meters` as a
  low-rate Android-property hotload. This is now only a post-skinning
  diagnostic nudge with default `0.0m`; the raw live joint coordinate-frame
  conversion below is the actual distance/alignment fix. The helper
  `tools/Set-KuramotoSpatialLiveHandDepthOffset.ps1` wraps the serial-scoped
  `adb setprop` and `getprop` readback. The current follow-up supersedes the
  earlier weighted-joint filtering with the native compact-frame contract:
  markers report `liveHandJointStatusY=pose-valid`,
  `liveHandCompactFrameGate=native-equivalent-21-runtime-5-tip`,
  `liveMeshSkinningPolicy=native-compact-frame-gated-full-weight-skinning`,
  `liveHandSkinningValidityPolicy=native-compact-frame-gate-trust-all-weights`,
  `liveHandDepthOffsetParameterSource=runtime-hotload-android-property`, and
  `liveHandDepthOffsetProperty=debug.rustyquest.kuramoto_spatial.live_hand_depth_offset_meters`.
  The follow-up diagnostic slice adds only GPU shader visualization, not CPU
  skinning: `debug.rustyquest.kuramoto_spatial.particle_layer.diagnostic_mode`
  accepts `normal`, `triangle-bands`, `projection-clamp`, `no-dynamics`, and
  `degenerate`, with `tools/Set-KuramotoSpatialParticleDiagnosticMode.ps1`
  wrapping the serial-scoped `adb setprop`/`getprop` readback.
- 2026-06-25 live skinning/depth hotload headset run: rebuilt with the full
  two-hand recorded capture at
  `S:\Work\tmp\quest-handmesh-matter-full-20260601-123844\pulled\hand-recordings\quest-handmesh-1780310333778406776`
  and `RecordedHandFrameLimit=24`, installed on Quest 3S serial
  `3487C10H3M017Q`, set
  `debug.rustyquest.kuramoto_spatial.live_hand_depth_offset_meters=0.72`, and
  launched the workflow self-test with `surface_target_id=real-hands`. Evidence
  directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-174820-live-skinning-depth-hotload-fallback`.
  APK SHA-256:
  `4f5f84d5b7c690f815489f076ff7f4acb332dbd43e04f31a018c3a1756735c44`.
  Native receipt library SHA-256:
  `cd221c0ba6f67f98d7f7c0902c428f47730b7b6ed3c5c36f357373fadf9bd951`.
  Fresh app-private markers showed `render-loop-ready`, `first-frame-presented`,
  `liveHandDepthOffsetMeters=0.720` and a live frame with both hands at
  `active-position-joints-26-pose-joints-26-skinning-ready-true`. This older
  run proved live-row transport, but its weighted-joint filtering policy is
  superseded by the native compact-frame skinning policy below.
  `dumpsys activity` showed `KuramotoSpatialActivity` resumed/focused as process
  `11173`, and the captured tag log had zero `AndroidRuntime`/fatal crash lines.
  This proves the corrected live input path and hotload transport are active;
  visual acceptance of the offset remains a headset-operator check.
- 2026-06-25 distance hotload correction: headset feedback showed changing
  `debug.rustyquest.kuramoto_spatial.live_hand_depth_offset_meters` from
  `0.72` to `0` was consumed by native markers but did not change perceived
  particle-hand distance. The first follow-up added
  `debug.rustyquest.kuramoto_spatial.particle_layer.target_distance_meters`,
  polled in Kotlin on scene ticks. It moves the Spatial SDK particle surface
  center along `Scene.getViewerPose().forward`, scales panel width/height with
  distance to preserve the existing FOV footprint, and passes the same
  `panelTargetDistanceMeters` to native so the stereo ray-intersection shader
  uses the matching virtual eye-to-panel distance. The helper
  `tools/Set-KuramotoSpatialParticleLayerTargetDistance.ps1` wraps the
  serial-scoped `adb setprop`/`getprop` path; start visual checks around
  `0.35m` after installing this slice. Kotlin now emits
  `status=surface-geometry-hotload-updated` whenever target distance or
  surface overscan changes.
  Follow-up headset feedback showed this control moved the panel/quad contract
  but still did not change the apparent live-hand offset, so it is not the
  raw live-hand alignment fix.
- 2026-06-25 live hand raw-to-scene transform correction: the remaining offset
  is now treated as a coordinate-space mismatch. The Spatial SDK scene uses
  `scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)` and
  `scene.setViewOrigin(0.0, 0.0, 2.0, 180.0)`, while the native
  `xrLocateHandJointsEXT` path was uploading raw OpenXR `LOCAL_FLOOR` joint
  poses directly into the recorded-compatible GPU skinning rows. The live hand
  adapter now transforms joint positions and orientations into the Spatial SDK
  scene frame before upload, with defaults matching the view-origin setup:
  offset `0.0;0.0;2.0m` and yaw `180deg`. These values are hotloadable through
  `debug.rustyquest.kuramoto_spatial.live_hand_scene.offset_x_m`,
  `debug.rustyquest.kuramoto_spatial.live_hand_scene.offset_y_m`,
  `debug.rustyquest.kuramoto_spatial.live_hand_scene.offset_z_m`, and
  `debug.rustyquest.kuramoto_spatial.live_hand_scene.yaw_degrees`, wrapped by
  `tools/Set-KuramotoSpatialLiveHandSceneTransform.ps1`. Markers report
  `liveHandJointPlacementMode=raw-openxr-local-floor-to-spatial-sdk-scene-to-panel-plane`,
  `liveHandCoordinateTransform=raw-openxr-local-floor-to-spatial-sdk-scene`,
  `liveHandSceneTransformSource=runtime-hotload-android-property`,
  `liveHandSceneOffsetDefaultM=0.000;0.000;2.000`, and
  `liveHandSceneYawDefaultDegrees=180.000`. If headset inspection shows the
  SDK sign convention is inverted, tune `yaw_degrees` and `offset_z_m` live
  before rebuilding.
- 2026-06-25 live hand horizontal sign and density correction: headset
  inspection showed the raw-to-scene transform fixed distance, while horizontal
  handedness was mirrored: moving the right physical hand right moved the
  spatial particle hand left. The transform now has a separate
  `debug.rustyquest.kuramoto_spatial.live_hand_scene.horizontal_sign`
  property, default `-1`, so the accepted `yaw=180`/`offset_z=2` depth
  correction can be kept while X is unmirrored. The live particle shader also
  now reports
  `liveMeshTriangleRetryPolicy=bounded-alternate-triangle-sampling` and retries
  six alternate skinned mesh triangles
  (`liveMeshTriangleValidationAttempts=6`) before hiding a live particle whose
  first sampled triangle has an invalid vertex or degenerate normal. This is a
  density fix for the real live mesh path, not a return to joint-cluster
  particles or a per-particle Spatial SDK entity route.
- 2026-06-25 horizontal sign and live-density retry headset run: rebuilt with
  the same full two-hand recorded capture and installed on Quest 3S serial
  `3487C10H3M017Q`. Runtime properties were
  `live_hand_scene.offset_z_m=2`, `live_hand_scene.yaw_degrees=180`,
  `live_hand_scene.horizontal_sign=-1`,
  `live_hand_depth_offset_meters=0`, and
  `particle_layer.target_distance_meters=0.35`. Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-182430-horizontal-sign-density-retry`.
  APK SHA-256:
  `9CDF4F87290D805ED1FBDAE26C51217B1085CC36A3DEB8580A47D5EA71BF1276`.
  App-private native markers reported `render-loop-ready`,
  `first-frame-presented`, `liveHandSceneHorizontalSignDefault=-1.000`,
  `liveMeshTriangleRetryPolicy=bounded-alternate-triangle-sampling`,
  `liveMeshTriangleValidationAttempts=6`, and a live two-hand frame with
  `liveHandFallbackToReplay=false`. Activity dumpsys showed the Spatial SDK
  activity resumed as process `16239`; app-private markers had zero
  `AndroidRuntime`/fatal crash lines. The app was left foregrounded for headset
  visual inspection.
- 2026-06-25 depth-axis hotload check: headset feedback after the horizontal
  sign fix showed left/right hand identity was correct, but near/far motion was
  still mirrored: bringing physical hands closer moved particle hands farther
  away. Without rebuilding, the running APK was hotloaded to
  `live_hand_scene.offset_z_m=2`, `live_hand_scene.yaw_degrees=0`, and
  `live_hand_scene.horizontal_sign=1`. Android property readback matched those
  values, and app-private markers in
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-183353-depth-axis-hotload-yaw0`
  reported `status=live-hand-scene-transform-hotload-updated`,
  `liveHandSceneYawDegrees=0.000`, and `liveHandSceneHorizontalSign=1.000`.
  Treat this as the candidate raw `LOCAL_FLOOR` to Spatial scene mapping until
  headset visual inspection confirms that depth motion, left/right identity,
  and static distance all match.
- 2026-06-25 live hand viewer-relative panel-basis correction: headset
  feedback confirmed the `yaw=0`/`horizontal_sign=1` hotload fixed near/far
  motion, but the live particle hands remained offset to the side at an angle
  from the camera. The fallback replay hands were correctly oriented and
  straight ahead, but still far along the forward axis, which separated the
  fallback replay depth issue from the live-coordinate basis issue. The live
  hand adapter now resolves `xrLocateViews`, locates the OpenXR view pose in
  the same `LOCAL_FLOOR` reference space as `xrLocateHandJointsEXT`, expresses
  live joint positions relative to the OpenXR view right/up/forward basis, and
  rebuilds those positions in the current Spatial SDK panel basis sent by
  `Scene.getViewerPose()`. Markers should report
  `liveHandJointPlacementMode=viewer-relative-openxr-to-spatial-sdk-panel-plane`,
  `liveHandCoordinateTransform=viewer-relative-openxr-to-spatial-sdk-panel-basis`,
  `liveHandViewPoseSource=xrLocateViews`, and
  `liveHandPanelBasisSource=Scene.getViewerPose-panel-plane`. The older raw
  `LOCAL_FLOOR` to scene transform and hotload properties remain only as the
  fallback/diagnostic path when `xrLocateViews` or the panel basis is
  unavailable.
- 2026-06-25 viewer-relative live-hand mapping headset run: rebuilt with the
  same full two-hand recorded capture and installed on Quest 3S serial
  `3487C10H3M017Q`. APK SHA-256:
  `A1C2BD47588CA6AD4165851DCCAAE9CEFE22A8D93D57AFB0020755CC0E4EE617`.
  Runtime kept `particle_layer.target_distance_meters=0.35` and
  `live_hand_depth_offset_meters=0` for comparison with the previous run.
  Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-185154-viewer-relative-live-hand-mapping`.
  App-private native markers reported `panel-pose-updated`,
  `render-loop-ready`, `first-frame-presented`,
  `liveHandJointPlacementMode=viewer-relative-openxr-to-spatial-sdk-panel-plane`,
  `liveHandCoordinateTransform=viewer-relative-openxr-to-spatial-sdk-panel-basis`,
  `liveHandViewPoseReady=true`, and
  `liveHandViewLocateStatus=ready-view-count-2-panel-basis-ready`. The marker
  pull happened while no active hands were visible, so it reported fallback
  replay for that instant; the app was left foregrounded for live headset
  visual inspection.
- 2026-06-25 viewer-relative alignment accepted benchmark: headset operator
  inspection confirmed the alignment and world-space behavior are now correct.
  Treat `viewer-relative-openxr-to-spatial-sdk-panel-basis` as the accepted
  baseline for the Spatial SDK live hand route. The next active issue is no
  longer world placement; it is live particle-to-mesh coverage. Fallback replay
  particles remain spread as expected, and the native real-time app spreads
  particles across live hands as expected, while the Spatial SDK real-time path
  still shows particles only in a few regions. The investigation should compare
  Spatial live skinning against the native compact 21 runtime joint plus 5 tip
  length path, including bind-joint source rows, tip reconstruction, valid
  weight thresholds, and live triangle rejection counters.
- 2026-06-25 native-equivalent live mesh coverage patch: comparison against the
  native real-time app showed that the native GPU skinning path does not filter
  weighted joints in shader. It submits a full compact frame only after the CPU
  adapter can build 21 runtime joint poses plus 5 tip-length rows, then the GPU
  vertex shader skins every weighted vertex against the resident bind mesh. The
  Spatial SDK path now
  mirrors that contract: per-hand live activation requires
  `liveHandCompactFrameGate=native-equivalent-21-runtime-5-tip`, markers expose
  `liveHandRuntimeJointPoseCount` and `liveHandTipLengthCount`, and the vertex
  shader reports
  `liveMeshSkinningPolicy=native-compact-frame-gated-full-weight-skinning` plus
  `liveHandSkinningValidityPolicy=native-compact-frame-gate-trust-all-weights`.
  This keeps the accepted viewer-relative world alignment fixed while testing
  whether live particle coverage now matches fallback replay and the native
  real-time path.
- 2026-06-25 native compact live-skinning headset run: rebuilt with the full
  two-hand recorded capture and installed on Quest 3S serial `3487C10H3M017Q`.
  APK SHA-256:
  `D0013FB1D51BF38C794C235F01BBF4498B24591750810D42BC83AD003FE08EDD`.
  Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-190957-native-compact-live-skinning`.
  Runtime kept `particle_layer.target_distance_meters=0.35` and
  `live_hand_depth_offset_meters=0`. Markers confirmed
  `native-kuramoto-study-particles-ready`,
  `liveMeshSkinningPolicy=native-compact-frame-gated-full-weight-skinning`,
  `liveHandCompactFrameGate=native-equivalent-21-runtime-5-tip`,
  `first-frame-presented`, process `21069`, and a resumed/focused
  `KuramotoSpatialActivity`. The captured marker window had no active hands in
  view (`liveHandRuntimeJointPoseCount=0`, `liveHandTipLengthCount=0`), so this
  run proves launch/presentation and the patched policy markers, while live
  density remains a headset-operator visual check with hands visible.
- 2026-06-25 GPU-only live coverage diagnostics: following the headset report
  that live hands are world-aligned but still sparse while fallback replay is
  dense, the next slice keeps the Spatial path GPU-skinned and adds
  hotloadable shader diagnostics through
  `debug.rustyquest.kuramoto_spatial.particle_layer.diagnostic_mode`. Use
  `triangle-bands` first: broad color coverage means triangle sampling and GPU
  skinning are reaching the full resident mesh; color appearing only in a few
  areas points at bind topology, compact joint source rows, or degenerate
  triangle collapse. `projection-clamp` clamps off-panel particles to the panel
  border and colors projection failures, so orange/magenta bands indicate
  projection clipping rather than missing particles. `no-dynamics` disables the
  LCHE motion/alpha slice and makes live/fallback particles bright, isolating
  dynamics or facing attenuation. `degenerate` accepts collapsed live triangles
  and colors them red, which tests whether normal-area rejection is hiding most
  live particles. These modes do not introduce CPU-skinned particles; they are
  temporary vertex-shader visualizations over the same GPU skinning path.
- 2026-06-25 GPU live-coverage diagnostic headset run: rebuilt with the full
  two-hand recorded capture and installed on Quest 3S serial `3487C10H3M017Q`.
  APK SHA-256:
  `74E75EDD2E08793DE207BB9032927A4A65F06968D3FF4EB2C5E317CF049CBFCC`.
  Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-193417-gpu-live-coverage-diagnostics`.
  Runtime properties were
  `debug.rustyquest.kuramoto_spatial.particle_layer.target_distance_meters=0.35`,
  `debug.rustyquest.kuramoto_spatial.live_hand_depth_offset_meters=0`, and
  `debug.rustyquest.kuramoto_spatial.particle_layer.diagnostic_mode=triangle-bands`.
  Markers confirmed `particleDiagnosticMode=1`,
  `particleDiagnosticModeName=triangle-bands`, `render-loop-ready`,
  `first-frame-presented`, process `23700`, and a resumed/focused
  `KuramotoSpatialActivity`. The captured marker window again had no active
  hands in view (`liveHandRuntimeJointPoseCount=0`,
  `liveHandTipLengthCount=0`), so the run proves the GPU diagnostic launch and
  shader-mode transport; live density interpretation remains a headset-operator
  visual check with hands visible. The app was left foregrounded in
  `triangle-bands` mode.
- 2026-06-25 hands-in-view diagnostic capture: a non-disruptive 15-second
  logcat window from the already-running app emitted no fresh native status
  lines, so the app was cold-relaunched with the headset operator's hands in
  view and the same runtime properties. Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-193927-hands-in-view-diagnostics`.
  Relaunch process `24589` reported `particleDiagnosticModeName=triangle-bands`,
  `render-loop-ready`, `first-frame-presented`, and then
  `status=live-hand-joints-frame-ready` with
  `liveHandRuntimeJointPoseCount=42` and `liveHandTipLengthCount=10`, plus zero
  fatal/`AndroidRuntime` markers in the captured window. This proves both hands
  were present in the native-equivalent compact live frame while the GPU
  diagnostic shader mode was active. The remaining sparse-live-particle issue
  is therefore not explained by missing live joint rows or missing tip-length
  rows; next diagnostics should inspect the GPU-skinned triangle coverage,
  collapsed/degenerate triangles, projection clipping, or dynamics/facing
  visibility using the shader modes.
- 2026-06-25 native real-hands diff after headset feedback: the visible wrist
  particles were the critical clue. The native real-hands path computes
  connected-component ranks for the recorded hand topology, reports rank 0 as
  `hand-inside`, rank 1 as `hand-back`, and rank 2 as `wrist-cap`, while the
  private Kuramoto sample surface uses
  `keep_two_largest_components_drop_wrist_bridge_boundaries_v1`. Spatial was
  still uploading every rig triangle as `[a,b,c,0]`, so the shader could not
  distinguish the wrist cap from the two hand surfaces. The Spatial receipt now
  mirrors the native union-find component ranking, filters particle source
  triangles to ranks 0 and 1 for both forced replay fallback and live GPU
  skinning, preserves the rank in the `uvec4` triangle rows, and also skips
  rank 2 in the vertex shader as a defensive guard. Runtime markers include
  `liveMeshSurfacePolicy=keep_two_largest_components_drop_wrist_bridge_boundaries_v1`,
  `liveMeshWristCapPolicy=drop-component-rank-2`, and per-hand source/sampling
  triangle counts; the expected full capture counts are 2314 source triangles,
  2296 sampling triangles, and 18 dropped wrist-cap triangles per hand.
- 2026-06-25 wrist-component filter headset validation: rebuilt with the full
  two-hand recorded capture at
  `S:\Work\tmp\quest-handmesh-matter-full-20260601-123844\pulled\hand-recordings\quest-handmesh-1780310333778406776`
  and `RecordedHandFrameLimit=24`, installed on Quest 3S serial
  `3487C10H3M017Q`, and left foregrounded in `triangle-bands` diagnostic mode
  with target distance `0.35m` and live hand depth offset `0.0m`. Evidence
  directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-200242-wrist-filter-per-hand-marker-launch`.
  App-private markers reported `render-loop-ready`, `first-frame-presented`,
  and split per-hand component receipts. Both hands reported
  `HandMeshComponentTriangleCounts=1220;1076;18`,
  `HandMeshSourceTriangleCount=2314`, `HandMeshSamplingTriangleCount=2296`,
  and `HandMeshDroppedTriangleCount=18`, with kept ranks `0;1` and dropped rank
  `2`. This confirms the Spatial live-skinning triangle table now uses the same
  wrist-cap exclusion policy as the native real-hands study path.
- 2026-06-25 hands-visible shader diagnostic sweep: headset feedback after the
  wrist-cap fix still showed sparse live-hand particles, but the wrist surface
  was gone. A live hotload sweep changed
  `debug.rustyquest.kuramoto_spatial.particle_layer.diagnostic_mode` through
  `projection-clamp`, `degenerate`, and `no-dynamics` while the same APK stayed
  foregrounded. Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-200718-live-hands-diagnostic-sweep`.
  Operator observation: green/projection-clamp and yellow/no-dynamics remained
  patchy, while red/degenerate showed the full hands. A follow-up cold relaunch
  in `degenerate` mode produced evidence at
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-200855-hands-visible-degenerate-relaunch`
  with `particleDiagnosticModeName=degenerate-triangle-accept` and a live
  compact frame (`liveHandRuntimeJointPoseCount=21`,
  `liveHandTipLengthCount=5`). Interpretation: the live source table and wrist
  filter are now present, projection and dynamics are not the primary cause of
  sparsity, and the next defect is likely GPU-skinned triangle collapse or a
  bind/runtime joint mapping mismatch that makes many live triangle normals
  near-zero before the normal path rejects them.
- 2026-06-25 live normal fallback patch: headset observation during the
  diagnostic sweep showed that red/degenerate mode tracked the entire live hand
  mesh well. This means the live GPU-skinned positions were usable, but the
  Spatial shader was incorrectly treating small triangle-area normals as a hard
  particle-visibility failure. The native hand-anchor fallback path keeps
  particles visible even when the triangle normal cannot be reconstructed, and
  the private Kuramoto path carries explicit normal data. The Spatial receipt
  now includes `bind_normals` in the resident skinning vertex buffer, skins
  those normals with the same weighted joint poses, and uses a
  `liveMeshNormalFallbackPolicy=skinned-bind-normal-for-small-triangle-area`
  fallback whenever a live triangle cross product is too small. Normal mode
  should therefore keep the full coverage proven by red mode while preserving a
  surface-normal direction for the current LCHE movement slice.
- 2026-06-25 live normal fallback headset launch: rebuilt with the full
  two-hand recorded capture and launched on Quest 3S serial `3487C10H3M017Q`
  in `normal` diagnostic mode. Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-201601-live-normal-bind-normal-fallback-launch`.
  Runtime markers reported
  `liveMeshNormalFallbackPolicy=skinned-bind-normal-for-small-triangle-area`,
  `particleDiagnosticModeName=normal`, `render-loop-ready`,
  `first-frame-presented`, and a live compact frame with
  `liveHandRuntimeJointPoseCount=21` and `liveHandTipLengthCount=5`. Process
  `29602` was foregrounded for headset-operator inspection.
- 2026-06-25 particle-surface overscan and built-in hand visual suppression:
  the next Spatial SDK slice separates viewer-pose projection distance from the
  physical carrier quad. Kotlin now polls
  `debug.rustyquest.kuramoto_spatial.particle_layer.surface_overscan_scale`
  and computes both `projectionWidthMeters`/`projectionHeightMeters` and
  `surfaceWidthMeters`/`surfaceHeightMeters`. The enlarged surface dimensions
  are applied to `PanelDimensions`, `QuadShapeOptions`, and the native
  panel-pose update so the shader maps NDC back onto the larger physical quad;
  this preserves particle world positions and point radii while increasing
  the visible field-of-view coverage. Markers include
  `projectionPlanePoseInvariantWithOverscan=true` and
  `particleWorldScaleInvariantWithOverscan=true`. The live helper
  `tools/Set-KuramotoSpatialParticleLayerOverscan.ps1` wraps the serial-scoped
  property. The same slice disables the Spatial Toolkit player hand mesh via
  `AvatarSystem.setShowHands(false)`, matching the native renderer's
  explicit-only base hand mesh policy while leaving the custom Vulkan particle
  hands active.
- 2026-06-25 headset operator confirmation: the first
  `AvatarSystem.setShowHands(false)` approach did keep the built-in Meta/SDK
  hand visual off. The follow-up patch moves that same setting into a small
  Spatial SDK system (`SpatialAvatarHandVisualSuppressionSystem`) so it is
  applied from the SDK system lifecycle rather than relying on Activity
  callback timing. This is intended as a robustness improvement, not a change
  to the accepted visual policy.
- 2026-06-25 overscan/suppression headset run:
  `local-artifacts\kuramoto-spatial-sdk-headset\20260625-204606-overscan-system-handvisual-launch`
  installed APK SHA-256
  `8F82E19A6473A439C9156C813734F17838569427D946F18D6D097A92A2876ADC` on
  Quest serial `3487C10H3M017Q` and left process `1399` running for headset
  inspection. Runtime properties used the validated live-hand alignment
  transform (`offset=0,0,2`, `yaw=180`, `horizontal_sign=-1`), target distance
  `0.35m`, surface overscan `1.35`, and particle diagnostic mode `normal`.
  PID-filtered logcat for this process contains one
  `channel=spatial-sdk-avatar-visual status=disabled` marker from
  `SpatialAvatarHandVisualSuppressionSystem`, one
  `status=surface-geometry-hotload-updated` marker with
  `projectionWidthMeters=0.7000`, `projectionHeightMeters=0.7000`,
  `surfaceWidthMeters=0.9450`, `surfaceHeightMeters=0.9450`, and
  `surfaceOverscanScale=1.3500`, plus `render-loop-ready` and
  `first-frame-presented`. The collected evidence has zero `AndroidRuntime`,
  `FATAL`, or `render-failed` matches, and native markers include repeated
  `live-hand-joints-frame-ready` entries.
- 2026-06-25 workflow panel mode slice: the active path no longer relies on
  the older skybox/backboard/floor diagnostic scene. `KuramotoSpatialActivity`
  now treats the native Vulkan surface as the user-facing XR visual surface and
  adds an explicit Spatial SDK panel mode: the workflow panel can be opened,
  focused to `0.0;1.1;0.475m`, closed into particle-view mode, and resized by
  updating `PanelDimensions(Vector2(...))`. Closing the workflow panel toggles
  `Visible(false)` on `kuramoto_experiment_panel`, leaves
  `kuramoto_particle_surface_panel` visible/running, and shows the compact
  `kuramoto_panel_launcher` panel so the user can reopen the questionnaire and
  parameter UI in-app. Markers report `panelMode`,
  `workflowPanelVisible`, `launcherPanelVisible`, and
  `particleLayerRenderContinuity=kept-running`; the validation self-test now
  exercises the close/reopen transition while the block is running.
- 2026-06-25 workflow panel mode headset validation:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-212715-panel-mode-native-selftest`
  installed APK SHA-256
  `AAF24BAEC90547F8ED47160EA52B8B470E3FBD5793D53BB2CD9273190C50A918`
  on Quest serial `3487C10H3M017Q`. The self-test closed the workflow panel
  into particle-view mode (`workflowPanelVisible=false`,
  `launcherPanelVisible=true`), reopened/focused it
  (`workflowPanelVisible=true`, `launcherPanelVisible=false`), and kept the
  particle layer running (`particleLayerRenderContinuity=kept-running`).
  The same cold launch reported `panelRegistrationCount=3`,
  `surface-panel-ready`, `particleLayerStarted=true`, native
  `render-loop-ready`, native `first-frame-presented`, live-hand markers,
  self-test completion, and zero `AndroidRuntime`, `FATAL`, or
  `render-failed` matches.
- 2026-06-25 closer workflow panel plus recorded hand fallback run: after
  headset feedback that the panel still appeared too far away, the workflow
  panel distance was reduced by 50% relative to the current Spatial SDK view
  origin (`z=2.0`). The default visible workflow panel now starts at
  `z=0.15m` instead of `z=-1.7m`; the focused/open pose uses
  `0.0;1.1;0.475m` instead of `0.0;1.1;-1.05m`; and the compact launcher uses
  `z=0.525m` instead of `z=-0.95m`. The APK was rebuilt with the previously
  validated recorded Quest hand capture at
  `S:\Work\tmp\quest-handmesh-matter-full-20260601-123844\pulled\hand-recordings\quest-handmesh-1780310333778406776`
  and `RecordedHandFrameLimit=24`, then installed and launched normally on
  Quest serial `3487C10H3M017Q`. Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-224134-closer-panel-recorded-hands-launch`.
  APK SHA-256:
  `C5D9E0E29760B8B6EA7487B3BE4955489BE7D540079023F07BC31F558DA03851`.
  Build manifest reports
  `forced_replay_hand_source_mode=external-recorded-capture-build-env`,
  `forced_replay_hand_frame_limit=24`, and
  `spatial_panel_focus_pose_meters=0.0;1.1;0.475`. Runtime markers report
  `panelZ=0.15`, `panelRegistrationCount=3`, `surfaceOverscanScale=1.3500`,
  `render-loop-ready`, `first-frame-presented`, and native
  `gpuReplayHandSourceKind=external-recorded-capture-build-env` with
  `leftReplayHandFrameCount=24`, `rightReplayHandFrameCount=24`,
  `leftReplayHandVerticesPerFrame=6888`, and
  `rightReplayHandVerticesPerFrame=6888`. Failure marker counts were zero for
  `AndroidRuntime`, `FATAL`, and `render-failed`. Process `18454` was left
  running for headset inspection.
- 2026-06-25 Spatial Polar panel slice: the Spatial SDK workflow panel now
  embeds a direct BLE Polar H10 panel adapted from the native 2D control-panel
  route. `KuramotoSpatialActivity` owns the `PolarSensorPanel` lifetime,
  forwards BLE permission results, and mirrors decoded Polar stream events
  into `KuramotoExperimentStore.appendPolarEvent(...)`. The store records the
  `spatial-sdk-direct-ble-panel` lane in run metadata, appends all Polar stream
  rows to `polar_events.jsonl`, mirrors `stream.polar_h10.ecg` rows to
  `ecg_events.jsonl`, and attaches the current Kuramoto experiment envelope to
  every mirrored event. The Android manifest now declares Bluetooth scan/connect
  and legacy location/Bluetooth permissions. This is still a low-rate
  experiment-record adapter: Polar HR/RR, ACC, ECG, and device-status rows do
  not feed the native Vulkan particle renderer or shader parameter queue.
- 2026-06-25 live Polar validation route: added
  `RUN_POLAR_LIVE_VALIDATION` and
  `tools/Invoke-KuramotoSpatialSdkAndroidPolarLive.ps1` as the real H10
  scan/connect/start-ECG evidence lane. The action creates a participant
  session, keeps the Spatial workflow panel as UI authority, selects ECG mode,
  scans, auto-connects the best discovered Polar device, requests ECG, and
  logs `polar-live-validation` completion with `ecgReceiving=true` only after
  decoded frames arrive. The wrapper installs/pregrants, launches the action,
  captures PID-scoped logcat, root `polar_sensor_status.json` and
  `polar_stream_events.jsonl`, participant `polar_events.jsonl` and
  `ecg_events.jsonl`, and fails unless real ECG frame markers and mirrored ECG
  rows are present. First strict live H10 headset evidence is recorded below.
- 2026-06-25 strict live Polar H10 validation:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-221738-polar-live`
  installed APK SHA
  `BC3529DBBAE3EF40508C8F68831F82C5B06E80B1C5C3EDDCC01E500ADE107D1D`
  on Quest 3S serial `3487C10H3M017Q` with
  `allow_missing_live_polar=false`. The wrapper summary passed after clearing
  stale app-private live Polar marker files (`exit=0` for
  `polar_sensor_status.json`, `polar_stream_events.jsonl`, and
  `kuramoto_spatial_activity_markers.log`). The run scanned one Polar device,
  auto-selected Polar H10 `A0:9E:1A:C7:74:56`, connected, reached
  `pmd-ready`, started ECG, and captured durable `ecg-frame` markers. The
  validation completion marker reported `ecgReceiving=true`,
  `discoveredDeviceCount=1`, and
  `ECG_logging_active_38_frames_2774_samples_mirrored_to_participant_files`;
  PID/tag logcat continued to 52 frames and 3796 samples before stop. The
  wrapper also captured root Polar stream rows, participant `polar_events.jsonl`
  and `ecg_events.jsonl` ECG rows, a non-empty app-private ECG event file, and
  zero `AndroidRuntime`, `FATAL`, or `render-failed` matches.
- 2026-06-25 panel-first experiment flow alignment: the Spatial SDK lane now
  mirrors the native app's operator flow. Cold launch keeps the workflow panel
  visible, resets normal launcher starts to participant setup, and hides the
  Vulkan particle surface entity; validation intents still create their own
  sessions. The explicit panel `Start Block` action selects one of the
  available surface modes (`real-hands`, `gpu-replay-hands`, or `icosphere`),
  closes the panel, starts the next randomized block, and then hands bounded
  condition scalars to the native Vulkan particle queue. When a block duration
  elapses, the panel is focused again for the questionnaire. Questionnaire
  submission writes the row and advances to `ready_next_block` or `complete`;
  it no longer starts the next block automatically. The native surface can
  remain registered for renderer continuity, but visual ownership now switches
  like the native panel-first workflow.
- 2026-06-26 headlocked workflow panel mode: the workflow panel is now a
  viewer-relative Spatial SDK UI surface by default while the native Vulkan
  surface remains the particle/projection authority. `KuramotoSpatialActivity`
  recomputes the panel pose from `Scene.getViewerPose()` on scene ticks when
  the panel is open, using viewer-right `offset_x_m`, viewer-up `offset_y_m`,
  and viewer-forward `distance_meters`. The default app pose is
  `0.0;0.0;1.40m`, with default panel width `1.20m` and headlocked scale
  `0.65`, plus a panel-side toggle to return to the older world-locked pose
  when needed. Runtime tuning uses
  `tools/Set-KuramotoSpatialPanelHeadlock.ps1` and the properties
  `debug.rustyquest.kuramoto_spatial.panel.headlocked.enabled`,
  `.offset_x_m`, `.offset_y_m`, `.distance_meters`, `.width_meters`,
  `.height_meters`, `.scale`, `.joystick.enabled`,
  `.joystick.translate_rate_mps`, `.joystick.distance_rate_mps`, and
  `.joystick.scale_rate_per_second`. Android generic-motion controller input
  maps left stick to panel x/y offset, right-stick y to distance, and
  right-stick x to scale. Every hotload or joystick adjustment persists
  `files/kuramoto_spatial_panel_headlock_tuning.json`, so a headset-tuned
  offset can be read back and promoted to defaults. If Quest controller axes do
  not route through Android generic motion on a target runtime, the next
  fallback should be a native OpenXR action-polling bridge, not moving workflow
  panel authority into the Vulkan renderer.
- 2026-06-26 remote UI action surface and debug controller reopen: added
  `io.github.mesmerprism.rustyquest.kuramoto_spatial.action.RUN_UI_COMMAND`
  plus `tools/Invoke-KuramotoSpatialSdkAndroidUiAction.ps1` so CLI validation
  can exercise panel-open, panel-close, panel-reset, headlock toggles, placement
  adjustment, panel resize, particle-control sliders, participant reset/begin,
  Polar setup save, surface select, start-block, surface-target activation, and
  questionnaire submit through the same handlers as the Compose panel. A
  dedicated
  `io.github.mesmerprism.rustyquest.kuramoto_spatial.action.RUN_SURFACE_TARGET`
  path starts a selected surface target and leaves the app in particle view
  instead of running the self-test panel reopen. For debugging, right-controller
  primary input now has three reopen routes: Spatial SDK `Controller` component
  polling with `ButtonBits.ButtonA`, Android `KEYCODE_BUTTON_A`/`KEYCODE_BUTTON_1`
  on the down edge, and an Android generic-motion button fallback. ADB keyevent
  tests can prove only the Android key route; physical Quest controller proof
  should be checked with the `inputSource=spatial-sdk-controller-component`
  marker while the headset is running.
- 2026-06-26 external OpenXR swapchain wrapper probe: added a debug-only
  `debug.rustyquest.spatial.external_swapchain_probe` path to
  `KuramotoSpatialActivity` and `native-receipt/src/surface_particle_layer.rs`
  to test whether `SceneSwapchain(handle: Long)` can wrap a raw native
  `XrSwapchain` created against the Spatial SDK-owned OpenXR session. The
  native side resolves only `xrEnumerateSwapchainFormats`,
  `xrCreateSwapchain`, `xrEnumerateSwapchainImages`,
  `xrAcquireSwapchainImage`, `xrWaitSwapchainImage`,
  `xrReleaseSwapchainImage`, and `xrDestroySwapchain`; it deliberately does
  not call `xrWaitFrame`, `xrBeginFrame`, or `xrEndFrame`. Headset evidence in
  `local-artifacts/kuramoto-spatial-sdk-headset/20260626-014223-external-swapchain-probe-guarded`
  showed the SDK-created `handle` and `nativeHandle()` rewrap, while
  `platformHandle()` is `0`. Native `xrCreateSwapchain` succeeded for a
  256x256 mono color swapchain, enumerated three images, and
  acquire/wait/release completed. A raw external `SceneSwapchain` wrapper could
  report matching `handle`/`nativeHandle`, but `getSurface()` on that wrapper
  crashes inside the SDK, `SceneQuadLayer` rejects it with a native assert, and
  `SceneSwapchain.destroy()` on the raw wrapper is avoided so native
  `xrDestroySwapchain` owns cleanup. This fails the probe decision rule for a
  visible/renderable external projection-swapchain path. The current viable
  route remains the Android surface/WSI panel carrier unless Meta exposes a
  supported external-swapchain or shared Vulkan device/queue/sync contract.
- 2026-06-25 Spatial experiment condition handoff slice: the panel-controlled
  block start now returns an active block snapshot with
  `movement_base_frequency_hz` and `movement_coupling`, applies those values to
  native surface-particle `driver0`/`driver1` through
  `nativeUpdateSurfaceParticleParameters(...)` after the workflow panel has
  closed to particle view with source `experiment-block-start`. The validation
  self-test also emits
  `self-test-experiment-block-start` handoff markers.
- 2026-06-25 condition handoff headset validation:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-214448-condition-handoff-selftest`
  installed APK SHA
  `22ED9E2A10D07279B9983129EB4EAAA61187FAC8DA2C3DC2672D38543DE51E3F`
  on Quest 3S serial `3487C10H3M017Q`. The self-test logged
  `status=experiment-condition-parameter-handoff`,
  `source=self-test-experiment-block-start`, `conditionId=lche`,
  `driver0Value01=0.8500`, `driver1Value01=0.1500`,
  `panelMustNotBeAuthority=true`, and
  `rendererAuthority=native-vulkan-wsi-surface-panel`. The summary also reports
  panel close/reopen, `panelRegistrationCount=3`, surface start/ready,
  `render-loop-ready`, `first-frame-presented`, live-hand markers,
  `self-test-complete`, and zero `AndroidRuntime`, `FATAL`, or `render-failed`
  matches.
- 2026-06-25 Spatial SDK headset self-test wrapper: added
  `tools/Invoke-KuramotoSpatialSdkAndroidSelfTest.ps1` as the durable
  serial-scoped validation route for this lane. It installs the built APK unless
  `-SkipInstall` is set, launches `RUN_WORKFLOW_SELF_TEST`, captures
  PID-scoped logcat, app-private `kuramoto_experiment_session.json` and session
  JSONL files, writes `evidence-summary.json`, and rejects missing
  panel/particle/condition/Polar-panel evidence markers.
- 2026-06-25 wrapper headset validation:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-215438-wrapper-selftest`
  installed APK SHA
  `22ED9E2A10D07279B9983129EB4EAAA61187FAC8DA2C3DC2672D38543DE51E3F`
  on Quest 3S serial `3487C10H3M017Q`. The wrapper summary passed with all
  required panel, condition handoff, Polar setup/panel creation, particle
  startup, `render-loop-ready`, `first-frame-presented`, live-hand,
  questionnaire, and app-private session JSON/JSONL evidence present, including
  the empty ECG event file captured as a file-presence artifact. Failure marker
  counts were zero for `AndroidRuntime`, `FATAL`, and `render-failed`.
- 2026-06-25 live hand raw-to-scene transform headset run: rebuilt with the
  full two-hand recorded capture at
  `S:\Work\tmp\quest-handmesh-matter-full-20260601-123844\pulled\hand-recordings\quest-handmesh-1780310333778406776`
  and `RecordedHandFrameLimit=24`, installed on Quest 3S serial
  `3487C10H3M017Q`, set
  `debug.rustyquest.kuramoto_spatial.live_hand_scene.offset_z_m=2`,
  `debug.rustyquest.kuramoto_spatial.live_hand_scene.yaw_degrees=180`,
  `debug.rustyquest.kuramoto_spatial.live_hand_depth_offset_meters=0`, and
  `debug.rustyquest.kuramoto_spatial.particle_layer.target_distance_meters=0.35`.
  Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-181200-live-hand-scene-transform`.
  APK SHA-256:
  `EF28F7A5D77DB5692CB25953DFDD36A7036C7F8AAAA1E5517439942ABF4ED170`.
  App-private native markers reported `render-loop-ready`,
  `first-frame-presented`,
  `liveHandCoordinateTransform=raw-openxr-local-floor-to-spatial-sdk-scene`,
  `liveHandSceneOffsetDefaultM=0.000;0.000;2.000`,
  `liveHandSceneYawDefaultDegrees=180.000`, `liveHandDepthOffsetMeters=0.000`,
  and a live `live-hand-joints-frame-ready` update with both hands active,
  `liveHandVisualizableJointCount=52`, and `liveHandFallbackToReplay=false`.
  Activity dumpsys showed `KuramotoSpatialActivity` resumed as process `14739`,
  and the app-private marker logs had zero `AndroidRuntime`/fatal crash lines.
  The app was left foregrounded for headset visual inspection.
- 2026-06-25 study-profile dynamics slice: the Spatial surface hand-particle
  shader now applies the concrete `lche` study condition,
  `kuramoto.private.native.profile.high-energy-low-coherence.movement-only.v1`,
  with `kuramotoMovementBaseHz=0.88`, `kuramotoMovementCoupling=0.0`, high
  frequency spread, and noise/size/phase modulation. Markers report
  `studyProfileDynamicsActive=true` while still keeping
  `privateKuramotoPayloadActive=false` so the notes do not overclaim the full
  private Kuramoto compute stack.
- 2026-06-25 visible native surface-particle headset validation: rebuilt and
  launched on Quest 3S serial `3487C10H3M017Q`. Evidence directory:
  `local-artifacts/kuramoto-spatial-sdk-headset/20260625-140350-native-surface-panel-first-frame`.
  APK SHA-256:
  `dc9d645643b19308088ba54e503580b52b272eb72b18159fba9e42465379f83d`.
  The run reported `surface-panel-ready surfaceValid=true`, native start mask
  `15`, `render-loop-ready particleCount=2048 extent=1024x768
  swapchainImages=3`, and `first-frame-presented particleCount=2048
  extent=1024x768`. The Spatial SDK OpenXR/Vulkan object receipt remained
  green at VR-ready with mask `2097151`; the workflow self-test completed; the
  app process remained alive as `pid=15652`; and the captured tag log had zero
  `AndroidRuntime` crash lines. The app was left foregrounded after the run for
  headset visual inspection.

## Final Build/Run Recipe

Local static gate:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-KuramotoSpatialSdkAndroid.ps1 -RepoRoot .
```

Build gate:

```powershell
# Activate the repo-family Quest/Android tooling for this machine first.
& 'S:\Work\tools\Quest\Use-QuestTooling.ps1'
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-KuramotoSpatialSdkAndroid.ps1 -RepoRoot .
```

APK output:

```text
target/kuramoto-spatial-sdk-android/rusty-quest-kuramoto-spatial-sdk.apk
```

Current validated APK SHA-256:

```text
4f5f84d5b7c690f815489f076ff7f4acb332dbd43e04f31a018c3a1756735c44
```

Previous projection-plane proof APK SHA-256:

```text
6f6396b812f281c43f85e4f7e68e25826d12e31e7dc31d86e0ec3ab613d8c19b
```

Previous camera-facing proof APK SHA-256:

```text
fe67f51763280c5a96ceacca0e0cba48025388e55eaf67167dffe034973d9a00
```

Previous packed-stereo proof APK SHA-256:

```text
ca64eefaf0d069f8142c836a55ce7ec0851a7d995e0b6f2d495a4179cc4085e7
```

Previous mono compute-storage surface APK SHA-256:

```text
e3cbf0a58bbdd98f1ff4a717436f873d3332d277e0c808c94ceb46b2c6641515
```

Previous visible procedural-surface APK SHA-256:

```text
dc9d645643b19308088ba54e503580b52b272eb72b18159fba9e42465379f83d
```

Headset validation command shape:

```powershell
$serial = "3487C10H3M017Q"
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-KuramotoSpatialSdkAndroidSelfTest.ps1 `
  -Serial $serial `
  -ParticipantId codex-spatial-native-probe-20260625 `
  -SurfaceTargetId real-hands
```

Live Polar H10 validation command shape:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-KuramotoSpatialSdkAndroidPolarLive.ps1 `
  -Serial $serial `
  -ParticipantId codex-spatial-polar-live-20260625 `
  -SurfaceTargetId real-hands
```

Live raw-to-scene hand transform tuning while the APK remains foregrounded:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialLiveHandSceneTransform.ps1 -Serial $serial -OffsetX 0 -OffsetY 0 -OffsetZ 2 -YawDegrees 180 -HorizontalSign -1
```

Live post-skinning diagnostic depth-offset tuning while the APK remains foregrounded:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialLiveHandDepthOffset.ps1 -Serial $serial -Meters 0
```

Live particle-surface distance tuning while the APK remains foregrounded:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialParticleLayerTargetDistance.ps1 -Serial $serial -Meters 0.35
```

Live GPU particle diagnostic mode while the APK remains foregrounded:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Set-KuramotoSpatialParticleDiagnosticMode.ps1 -Serial $serial -Mode triangle-bands
```

Evidence directory:

```text
local-artifacts/kuramoto-spatial-sdk-headset/20260625-151641-projection-plane-front-face-particles
```

Important files in that directory:

- `live-tag-logcat.txt`: activity creation, `channel=native-interop-probe`, Kotlin
  `channel=native-interop-receipt`, Rust
  `RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE`, Rust
  `channel=native-vulkan-object-probe`, Rust
  `channel=native-surface-particle-layer`, validation workflow markers, and
  zero captured crash lines.
- `evidence-summary.json`: Quest serial, APK hash, participant id, particle
  layer markers, receipt markers, Vulkan object detail lines, scene-ready
  receipt mask `2097135`, VR-ready receipt mask `2097151`, `PanelSurface`
  valid at VR-ready, all no-present Vulkan object bits true, native surface
  panel `render-loop-ready`, `first-frame-presented`,
  `native-surface-compute-stereo-proof=true`,
  `sideBySideStereoProof=true`, `stereoMode=LeftRight`,
  `perEyeExtent=1024x1024`, `packedExtent=2048x1024`,
  `cameraFacingParticleSurface=true`,
  `projectionLockedParticleSurface=true`,
  `placementMode=viewer-pose-projection-locked-quad`,
  `projectionPlaneFacingMode=viewer-forward-front-face`,
  `targetDistanceMeters=0.72`, `widthMeters=1.44 heightMeters=1.44`,
  `projectionContentMappingMode=world-to-spatial-sdk-panel-plane-left-right`,
  `targetProjectionSpace=spatial-sdk-panel-plane-perspective-projection`,
  `targetCoordinateSpace=spatial-sdk-surface-panel-eye-uv`,
  `computeParticleStateBuffer=true`, `computeShaderDispatchReady=true`,
  `computeParameterBridge=true`, `parameters-submitted`, `parameters-updated`,
  and zero crash/render-failed lines.
- `dumpsys-activity.txt`: activity foreground/visible state for
  `KuramotoSpatialActivity`.

Coexistence probe evidence directory:

```text
local-artifacts/spatial-native-coexistence/20260625-102918-streaming-foreground-probe
```

Important files in that directory:

- `coexistence-summary.json`: native and Spatial APK hashes, native/spatial
  process ids, native lifecycle markers, Spatial probe markers, and zero
  native frame-like markers after Spatial foreground.
- `streaming-coexistence-logcat.txt`: streaming tag capture started before
  native launch and stopped after Spatial foreground.
- `dumpsys-activity-native-spatial-filter.txt`: Spatial activity resumed and
  visible; native `NativeActivity` task paused and not visible.
- `return-native-summary.json`: return-to-native proof with same native
  process id, native resume/window markers, 119 frame-like native markers, and
  zero crash lines.

## Remaining Risks And Follow-Ups

- Spatial SDK artifacts and Gradle may need to be downloaded during the first
  build. Keep downloads under Gradle/user caches or ignored local artifacts,
  not committed binaries.
- Spatial SDK 0.13.1 docs expect Android Gradle Plugin 8.11 and Gradle 9.x.
  The build wrapper bootstraps Gradle 9.4.1 under ignored `local-artifacts`
  and writes the APK/build manifest under ignored `target`.
- The first lane is expected to validate Spatial SDK panel placement/options
  and workflow logging. The confirmed sample-quaternion fix means the next
  slice can keep the runtime-created panel while proving native-renderer
  coexistence/interop, not Spatial SDK-owned particle rendering.
- The no-render interop probe proves Spatial SDK can expose nonzero OpenXR
  handles and a valid SDK-owned `PanelSurface` on the headset. The native
  receipt, OpenXR handle-usability, Vulkan capability, and no-present Vulkan
  object probes now prove Rust/JNI can receive the SDK-owned handles, query the
  SDK OpenXR function table, create the OpenXR-selected Vulkan instance/device,
  obtain a graphics+compute queue, and clean those Vulkan objects up inside
  `AppSystemActivity`. The visible surface route now has the source-side
  compute-resource parity slice: command pool/command buffer allocation,
  storage buffer, descriptor/pipeline layout, shader module creation, a compute
  dispatch that writes particle state, and a draw pass that consumes the buffer.
  Headset validation now covers this compute/storage-buffer surface path, the
  packed-stereo `StereoMode.LeftRight` duplicated-mono proof, and the
  projection-plane/front-face diagnostic-particle proof, and the visually
  confirmed forced replay validation-mesh hand triangle proof. The active
  source slice now replaces the visible replay mesh with native study-style
  hand-anchor particle billboards, can switch to live OpenXR joint rows when
  `liveHandJointFrameReady=true`, keeps forced replay as fallback, and emits
  `surfaceLayerMode=native-kuramoto-study-hand-anchor-particles`. The latest
  live source uses resident recorded-rig skinning for mesh-surface anchors. The
  corrected live skinning path now has headset marker validation with both
  hands pose-valid and a hotloaded depth offset; visual acceptance of the
  offset remains an explicit headset-operator check. This is still not yet the
  full private Kuramoto payload or full private-particle shader stack.
- The visible native surface-particle layer proves that a Spatial SDK surface
  panel can host a Rust/Vulkan WSI swapchain and present a 2048-particle GPU
  point field in the same `AppSystemActivity` as the questionnaire UI panel.
  This is the first visible native-render target under the Spatial SDK shell.
  The current source keeps compute-written storage-buffer particles for
  continuity markers and now draws hand-anchor particle billboards from live
  OpenXR joint rows when available by skinning the resident recorded rig, with
  a resident forced replay validation-mesh coordinate buffer as fallback. It
  still does not yet carry the private Kuramoto payloads or full
  private-particle shader stack. It also still renders into an SDK
  media/surface panel rather than the existing native OpenXR projection layer.
- The foreground coexistence probe rejects a separate Spatial SDK activity as a
  simultaneous overlay over the current NativeActivity renderer. It is only an
  activity-switch path: native can background and resume, but particles are not
  visible or submitting while the Spatial SDK activity is foreground.
- Continuous particles plus a world-space Spatial SDK panel requires a new
  single-immersive-owner architecture. The two candidates are: run a native
  renderer adapter inside `AppSystemActivity` using Spatial SDK-owned OpenXR
  handles, or render into a Spatial SDK-owned surface/swapchain that Spatial
  SDK composites beside its panel. Both are new backends/adapters, not a direct
  reuse of the existing `android.app.NativeActivity` frame loop.
- The surface-panel route is world-anchored and GPU-rendered, but it is still a
  planar visual layer unless a later slice uses a cylinder/equirect layer or a
  different SDK-supported surface shape. It does not provide true free 3D
  particles in the Spatial SDK scene graph, SDK depth/occlusion/lighting, or
  room-volume particle interaction by itself.
- Packed side-by-side stereo doubles the rendered pixel width and bandwidth for
  the same per-eye resolution. Validate small first, then sweep per-eye
  `1024x768`, `1280x720`, and `1600x900` with packed extents `2048x768`,
  `2560x720`, and `3200x900`. Keep mono fallback and explicit first-frame
  markers for `stereoMode=LeftRight`, `perEyeExtent=1024x768`,
  `packedExtent=2048x768`, and `particleCount`.
- Direct-to-surface alpha/transparency remains an explicit risk. Start visual
  mode opaque with alpha 1.0, high `zIndex`, and a clear foreground/background
  policy. Test premultiplied versus straight alpha only after the opaque
  stereo proof is stable.
- Lifecycle torture is still required for the surface renderer: surface
  creation/destruction, pause/resume, sleep/wake, focus changes, panel
  close/open, swapchain recreation, dynamic parameter changes, and failure
  markers that do not crash `KuramotoSpatialActivity`.
- Current ADB screenshots do not show the Spatial SDK panel layer even though
  the foreground activity, panel spawn marker, and SurfaceFlinger app layer are
  present. Treat screenshots as compositor-only evidence until a headset-side
  capture method that includes Spatial SDK panels is selected. The official
  `StarterSample` visibility check shows that headset-operator inspection is
  still the best acceptance signal for this Spatial SDK panel lane.
- If questionnaire semantics change, bump the questionnaire schema and update
  UI plus persisted row writer together. Current plan is to preserve the
  existing minimal comfort/intensity/engagement/notes semantics.
- If a later slice coordinates this Spatial SDK app with the native renderer,
  define an explicit low-rate command/receipt boundary first. Do not route
  high-rate hands, meshes, particles, phase fields, or buffers through panel
  JSON.
- A Spatial SDK-native particle port is a fallback path only. It must first
  prove a shader/batched draw path and a data ownership model compatible with
  the current GPU shader/private-particle architecture. Per-particle ECS
  entities are rejected for this study.

## Open Questions For Meta Or SDK Reference Checks

- 2026-06-26 probe answer: `SceneSwapchain.platformHandle()` was `0` for both
  SDK-created and raw-wrapped swapchains on Quest 3S in Spatial SDK 0.13.1.
  `SceneSwapchain(handle: Long)` can rewrap SDK `handle`/`nativeHandle` values
  and can hold a raw native `XrSwapchain` handle far enough to report matching
  `handle`/`nativeHandle`, but the raw wrapper is not usable as a
  `SceneQuadLayer` source: `getSurface()` crashes inside the SDK and
  `SceneQuadLayer` creation throws a native assert. Treat externally-created
  raw `XrSwapchain` wrapping as blocked unless Meta documents a different
  supported contract.
- Is native Vulkan rendering directly into `SceneSwapchain.create(...)` or
  other SDK-owned swapchain images supported, and if so how are the `VkDevice`,
  `VkQueue`, image handles, image formats, and synchronization objects obtained
  or shared?
- Is `SceneSwapchain.createAsAndroid(...).getSurface()` officially supported
  as a Vulkan producer surface through `VK_KHR_android_surface`, or should the
  supported route remain media/surface panel registration callbacks?
- What alpha format and blend convention should be used for
  `SceneQuadLayer`/surface-backed layers, especially for direct-to-surface
  paths?
- Are there cadence or presentation restrictions for a native producer render
  loop that presents into an SDK surface-backed panel while Spatial SDK owns
  the immersive session?
