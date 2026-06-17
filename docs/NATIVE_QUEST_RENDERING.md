# Native Quest Rendering

This note defines the first clean Rusty Quest route for a native OpenXR/Vulkan
camera renderer. It is not a Makepad app route and it is not a Rusty-XR
compatibility route.

## Decision

Build the pure-HWB camera path as a Quest-native renderer adapter:

```text
Camera2 AHardwareBuffer
-> Vulkan external image import/cache
-> low-resolution guide blur graph
-> optional public Matter SDF/hand-mesh inputs
-> optional private extension ABI slots
-> Optics-owned custom projection composite
-> OpenXR projection layer
```

The public core contracts live in `rusty-quest-native-renderer` under
`AGPL-3.0-or-later`. The first Android package scaffold now lives in
`apps/native-renderer-android`; it consumes the public plan fixture through a
Rust NativeActivity and keeps runtime evidence separate from visual
acceptance.

## Authority

- Rusty Quest owns the native renderer plan, Quest runtime adapter boundary,
  Android/Quest platform lifecycle, HWB import evidence, timing scorecards, and
  validation gates.
- Rusty Optics owns renderer-neutral projection, blur/effect semantics, and
  visual acceptance scorecards.
- Rusty Matter owns mesh/SDF field truth and CPU/reference SDF fixtures.
- Rusty Lattice owns hand/reference-space transforms, tracked-pose snapshots,
  validity, confidence, and frame binding.
- Private downstream work may implement private layer slots, but public plans
  carry only ABI ids and capability/resource ids. Public fixtures must not carry
  private implementation paths, binaries, package names, or payload bodies.

## Public Core

The initial public renderer plan requires:

- `camera2-hardware-buffer` source with outside camera ids `50` and `51`;
- `camera2-ahardwarebuffer-vulkan-external` import path;
- `combined-immutable-sampler-ycbcr-conversion` descriptor shape;
- color conformance still marked required until the green/pink HWB guide output
  is fixed and visually accepted;
- offscreen guide blur with at most two passes per eye;
- final projection with zero external HWB samples per fragment and one guide
  texture sample per fragment;
- stage timing for camera acquire, HWB import/cache, guide graph, SDF update,
  projection composite, and OpenXR submit.

The valid public example is:

```text
fixtures/native-renderer/native-hwb-blur-sdf-public.plan.json
```

It includes one private extension slot as a public ABI hook:

```text
rusty.quest.native_renderer.private_layer_slot.v1
```

That slot is intentionally a boundary, not a plugin loader. The public plan may
name input/output resource ids and timing budgets, but not private binaries,
paths, source trees, APKs, shaders, or proprietary payloads.

The Android native renderer instantiates this as a public no-op ABI slot and
emits `private-extension-slot` markers with `privateLayerPublicAbiOnly=true`,
`privateLayerPayloadLinked=false`, and
`privateLayerOutput=identity-public-abi-resource`. Private downstream layers can
replace that no-op behind the same resource boundary later; the public route
must continue to prove that no private implementation path or payload is linked.

## Cost Tracking

Native renderer implementations must emit timing and cost markers under:

```text
RUSTY_QUEST_NATIVE_RENDERER
```

The first scorecard schema is:

```text
rusty.quest.native_renderer_timing_scorecard.v1
```

Required counters include:

- `camera_frames_acquired`
- `hardware_buffer_imports`
- `hardware_buffer_cache_hits`
- `hardware_buffer_cache_misses`
- `guide_graph_renders`
- `guide_graph_cache_hits`
- `sdf_field_updates`
- `private_layer_invocations`
- `xr_frames_submitted`
- `stale_frames`

Timing acceptance must distinguish source acquisition, import/cache lookup,
offscreen guide graph, SDF field update, final projection, and OpenXR submit.
Total frame timing is not enough because the main risk is hiding expensive HWB
sampling, SDF refresh, or extension work inside one opaque render number.
The Android native renderer now emits host CPU timing fields for that split:
`cameraAcquireImportCpuMs`, `guideGraphCpuMs`, `liveHandLocateCpuMs`,
`handSdfPrepareCpuMs`, `handMeshVisualCpuMs`, `projectionCompositeCpuMs`,
`commandRecordCpuMs`, `swapchainWaitCpuMs`, `queueSubmitCpuMs`, and
`openxrEndFrameCpuMs`, with `cpuTimingScope=host-recording-and-submit`. These
fields are command-recording and submit-side evidence. The renderer also owns a
source-validated Vulkan timestamp query scaffold under the
`gpu-timestamp-timing` marker. It reports `gpuTimestampQuerySupported`,
`gpuTimestampQueryReady`, `gpuTimestampValidBits`, `gpuTimestampPeriodNs`,
`cameraProjectionGpuMs`, `guideGraphGpuMs`, `handSdfGpuMs`,
`handMeshVisualGpuMs`, and `projectionCompositeGpuMs`, with
`gpuTimingScope=vulkan-timestamp-query`. Runtime acceptance of those GPU values
still requires a replay or live-headset run because timestamp support and
query readiness are device/runtime evidence, not static evidence.

## Implementation Differences From Rusty-Vision

Rusty-Vision remains reference evidence, not a source template. The clean
native route may differ where newer Vulkan evidence supports it:

- keep immutable YCbCr conversion and descriptor shape explicit in the plan;
- cache imported HWB resources and expose hit/miss/retire counters;
- cache guide graph outputs by camera update sequence and blur parameters;
- keep SDF/hand-mesh inputs as public Matter/Lattice resources rather than
  app-local globals;
- keep private layer hooks behind a public ABI descriptor and timing budget;
- use scorecards and damaged fixtures before adding a broad app scaffold.

## Validation

Run:

```powershell
cargo test -p rusty-quest-native-renderer
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeRendererAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererReplaySmoke.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -RunSeconds 12
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeRendererRuntimeEvidence.ps1 -LogcatPath <filtered-logcat.txt> -ScreenshotPath <screenshot.png> -RequireScreenshot -RequireNonFlatScreenshot -RequireTargetNonFlatScreenshot -RequireHandMeshVisualScreenshot -RequireSdfVisualScreenshot -RequireCameraProjection -RequireReplayVisualProof -RequireGuideGraph -RequireSdfVisual -RequireGpuTimestampReady -RequirePerformanceBudget -RequirePrivateSlotNoPayload
```

The tests validate the public plan, validate a sample timing scorecard, reject
plans that leak private extension implementation paths, and reject final
projection plans that return to multiplied external HWB samples.
`Invoke-NativeRendererReplaySmoke.ps1` is the no-real-hands device wrapper for
the recorded replay path: it applies the replay visual-proof profile, launches
the NativeActivity, captures logcat and a screenshot, and then calls
`Test-NativeRendererRuntimeEvidence.ps1`. That evidence checker uses the latest
dedicated logcat marker per channel, plus an optional screenshot file, so
startup fallback markers do not count as failure once later accepted frame
markers are present. For wrapper runs, screenshot content analysis is enabled:
the summary records dimensions, sampled unique colors, luminance range, and
per-target-rectangle stats derived from the runtime-emitted
`leftTargetScreenUvRect` and `rightTargetScreenUvRect` marker fields. The
summary also records separate hand-mesh and SDF overlay evidence rectangles
from `leftHandMeshVisualScreenUvRect`/`rightHandMeshVisualScreenUvRect` and
`leftSdfVisualScreenUvRect`/`rightSdfVisualScreenUvRect`, so camera/projection
content and mesh/SDF visual content are not conflated. Overlay rectangles also
record chroma and expected cyan/yellow/magenta-family pixel counts, so
grayscale camera detail inside the same region is not accepted as mesh/SDF
visual proof. Flat screenshots, flat target regions, flat or colorless
hand-mesh evidence regions, or flat or colorless SDF evidence regions are
rejected unless the wrapper is run with `-AllowFlatScreenshot`. The wrapper
also writes target, hand-mesh, and SDF crop PNGs under `screenshot-crops/`
beside `runtime-evidence-summary.json` for direct visual inspection. Replay
wrapper runs also require
the performance budget gate by default; the checker records the observed FPS,
stale-frame count, and CPU/GPU stage timing budget results, and fails if a
stage exceeds its configured threshold. `-AllowPerformanceBudgetMiss` turns
that into collection-only behavior for exploratory runs.
The source-only live-hand diagnostic fixture deliberately remains a caveat
fixture: `RequireLiveVisualDiagnosticCaveat` accepts live compact-input markers
only while live mesh/SDF visual acceptance stays
`pending-repeat-headset-visual-proof`, and rejects marker-only acceptance.
The smoke wrapper has an explicit evidence mode: default `ReplayVisualProof`
uses the recorded replay profile and replay/SDF marker gates, while
`LiveVisualDiagnosticCaveat` applies the live-hand diagnostic profile and asks
the evidence checker for the live-marker caveat plus the same screenshot
overlay-color gates.

For full repo checks:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
```

## Android Scaffold

`apps/native-renderer-android` builds:

```text
io.github.mesmerprism.rustyquest.native_renderer/android.app.NativeActivity
```

The build script stages
`fixtures/native-renderer/native-hwb-blur-sdf-public.plan.json` as
`assets/native-hwb-blur-sdf-public.plan.json`, builds
`librusty_quest_native_renderer.so` with Cargo for `aarch64-linux-android`,
packages the Quest OpenXR loader when present, and signs a debug APK under
`target/native-renderer-android/`. No app Java or JNI C++ shim is packaged for
this route; the only Java class used at launch is Android's framework
`android.app.NativeActivity`.
Runtime permissions are requested from Rust through the framework Activity's
`requestPermissions` method, keeping the app-owned code path native while still
supporting normal Android permission flow.
The native renderer manifest declares `com.oculus.permission.HAND_TRACKING`
and optional `oculus.software.handtracking` for the `XR_EXT_hand_tracking`
compact-input path. Those declarations only make the app eligible; the headset
still has to have OS-level hand tracking enabled before active joint frames are
reported.

The runtime scaffold:

- validates `rusty.quest.native_renderer_plan.v1` before opening cameras;
- pumps Android NativeActivity lifecycle events and drains input events when
  `MainEvent::InputAvailable` is delivered, preventing Android input-dispatch
  ANR dialogs while preserving platform fallback handling for unowned keys;
- opens outside Camera2 ids `50` and `51` through NDK `ACameraManager` with
  private GPU-sampled `AImageReader` hardware buffers;
- acquires and describes each native `AHardwareBuffer` in Rust callbacks;
- initializes the Android OpenXR loader and probes OpenXR-selected Vulkan
  instance/device prerequisites for Android HWB external memory and sampler
  YCbCr conversion;
- imports retained Camera2 `AHardwareBuffer` frames into cached Vulkan external
  images with immutable YCbCr sampler conversion;
- renders the direct-HWB camera diagnostic only inside metadata-owned per-eye
  target-screen rectangles, with source raster Y flip controlled by metadata;
- builds a public low-resolution guide graph from those imported camera
  descriptors: per-eye 384x384 downsample, horizontal 5-tap blur, vertical
  5-tap blur, and final guide-texture projection inside the same metadata
  target rectangles;
- creates an OpenXR/Vulkan session and stereo swapchain, records per-eye
  projection clears into array-layer image views, and submits a real
  `CompositionLayerProjection`;
- stages the public recorded-hand topology/shape fixture, or an optional local
  full recorded hand capture generated into Cargo `OUT_DIR`, and renders a
  metadata target-boundary replay diagnostic with compact joint dots hidden by
  default;
- creates a native Vulkan storage-buffer boundary for the recorded bind mesh
  when a full local capture is embedded; the committed public fixture is
  metadata-only and reports `sourceMeshBuffersResident=false`;
- embeds bounded recorded validation-mesh metadata for local full-capture
  builds, but draws the animated hand as a native Vulkan triangle overlay from
  the resident GPU-skinned position buffer inside the metadata target
  rectangle, with component ranks matching the browser preview: hand-inside,
  hand-back, and wrist cap;
- exposes a property-controlled hand mesh diagnostic overlay that brightens,
  enlarges, and target-local offsets the resident GPU-skinned triangle draw
  through
  `debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled`,
  `debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.offset_uv`, and
  `debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.alpha`;
- exposes a no-real-hands replay proof preset with
  `debug.rustyquest.native_renderer.replay.visual_proof.enabled=true`. Unless
  `debug.rustyquest.native_renderer.hand_mesh.input.source` is explicitly set,
  that preset selects `recorded-replay`, enables the mesh diagnostic and SDF
  visual, and emits `recordedReplayVisualProofEnabled=true`,
  `compactHandInputSourceMode=recorded-replay`, and
  `recordedReplayVisualAcceptance=pending-headset-screenshot`;
- keeps the later live-hand visual retest in a separate profile,
  `quest-native-renderer-live-hand-visual-diagnostic.profile.json`, which
  disables replay proof, forces `live-meta-openxr-hand-tracking`, disables
  recorded fallback, enables the high-contrast mesh diagnostic plus SDF visual,
  and still reports live mesh/SDF acceptance as pending until headset
  screenshots show visible target-local overlay color;
- keeps an opt-in native Vulkan skinned-mesh SDF path disabled by default
  behind `debug.rustyquest.native_renderer.sdf.visual.enabled`; local
  full-capture builds parse rig blend indices/weights and compact joint
  frames, keep source mesh, bind-pose, and bind-joint-source buffers resident,
  upload only runtime joint poses plus packed tip-length rows per frame,
  dispatch GPU skinning into a resident skinned-position buffer, and optionally
  build the target SDF field from that GPU-owned mesh;
- enables `XR_EXT_hand_tracking` when the runtime advertises it and packs live
  left/right hand joints into the same recorded-compatible compact input shape:
  21 runtime joint poses plus 5 tip lengths, with no live validation-mesh vertex
  upload path;
- separates SDF field updates from cached field reuse with
  `debug.rustyquest.native_renderer.sdf.update_period_frames`,
  `sdfFieldUpdateDispatched`, `sdfFieldReused`, and `sdfFieldCacheHits`;
- emits source frame, import sequence, descriptor shape, release/retire, and
  timing/counter markers under `RUSTY_QUEST_NATIVE_RENDERER`;
- emits projection scorecards with `openxrSubmitReady=true`,
  `recordedHandReplayVisible=true`, `animatedHandMeshVisualReady=true`,
  `cpuSdfPerFrame=false`,
  `sdfComputePath=native-vulkan-compute-recorded-skinned-mesh-sdf-field`,
  `targetSpaceMeshToSdfKernelAvailable=true`, default
  `dynamicSdfReady=false`, `sdfVisualEffectVisible=false`, and
  `meshToSdfKernel=false` while the property is disabled; frames still report
  `gpuSkinningReady=true`, `compactJointSkinningKernel=true`,
  `jointMatrixUploadPerFrame=false`, and
  `compactJointPoseUploadPerFrame=true` when the resident hand visual is
  active. Opt-in frames that run the SDF kernel report
  `sdfFieldUpdateDispatched=true` and `meshToSdfKernel=true`; cached reuse
  frames keep `dynamicSdfReady=true` while reporting `sdfFieldReused=true` and
  `meshToSdfKernel=false`. Live OpenXR hand frames report
  `liveMetaHandCompactFrameReady=true` and
  `handMeshCompactInputSource=live-meta-openxr-hand-tracking` when available.
  Recorded fallback frames report `handMeshCompactInputSource=recorded-replay`.
  The selection mode itself is reported separately as
  `compactHandInputSourceMode=auto`, `recorded-replay`, or
  `live-meta-openxr-hand-tracking`, so replay proof and live visual acceptance
  cannot be confused.
  The SDF field markers mirror that distinction with `sdfCompactInputSource`
  and keep `liveSdfVisualAcceptance` pending until headset visual proof exists.
  Submitted frames report `guideGraphReady=true`,
  `cameraProjectionPath=metadata-target-guide-texture-final`,
  `actualFinalExternalHwbSamples=0`, and `actualGuideTextureSamples=1` when
  the guide graph is ready. The direct HWB projection remains only as a
  fallback when the guide graph is unavailable.

The OpenXR/Vulkan probe still reports `openxrSubmitReady=false` because it is a
prerequisite check. Runtime frame scorecards report `openxrSubmitReady=true`
only after a real `xrEndFrame` submission. Runtime scorecards may now report
`vulkanExternalImportReady=true` only after the renderer imports retained
Camera2 HWB frames into Vulkan external images and binds the combined immutable
YCbCr sampler descriptor shape. This is still not final visual parity:
metadata target footprint, orientation, guide blur, color/reference behavior,
and projection alignment are reported as separate fields.

The 2026-06-17 headset smokes for this route visually verified the diagnostic
projection and the later no-real-hands recorded replay path: both stereo eye
layers rendered, the public hand replay and mesh/SDF overlay were visible in
the metadata target area, cameras `50` and `51` delivered `AHardwareBuffer`
frames, the final projection used the guide texture rather than external HWB
samples, screenshot target/hand/SDF overlay color gates passed, and the stage
budget passed at 90.1 FPS with `stale_frames=0`.
The later recorded replay slices moved the runtime marker from the synthetic
CPU screen-space SDF diagnostic to `recordedHandReplayVisible=true` and
`cpuSdfPerFrame=false`, then replaced the expanded validation-mesh SDF upload
with an opt-in recorded compact-joint skinned-mesh GPU SDF path. The current
resident route also moved the visible animated mesh off the validation-mesh
upload stream and onto the same GPU-skinned position buffer used by the SDF
path. The current slice adds an optional live OpenXR compact hand source and
SDF update cadence/cache markers; live hand-marker readiness is separate from
live mesh/SDF visual acceptance. During the 2026-06-17 live-hand check the user
had real hands in view, but did not see a mesh or SDF representation in the
headset. A later headset retest should apply
`fixtures/runtime-profiles/quest-native-renderer-live-hand-visual-diagnostic.profile.json`
and capture visual proof that the live mesh and any SDF effect are visible in
the metadata target area.
Local APK builds can embed the real recorded
`bind-mesh-plus-compact-joint-frame` capture through
`Build-NativeRendererAndroid.ps1 -RecordedHandCaptureDir <capture-dir>
-RequireRecordedHandCapture`.
This acceptance is intentionally scoped to native projection, recorded replay
ingestion, resident GPU-skinned hand drawing, the live compact input adapter,
the GPU source-mesh boundary, replay evidence for the public guide blur graph,
and replay evidence of the resident skinned-mesh SDF visual path. It does not
claim camera projection color parity, live hand visual acceptance, direct Meta
hand-mesh topology import, or Matter/Lattice-backed SDF parity.

The public package exposes only the blur guide path. Other downstream visual
layers remain private extension implementations behind ABI slots and must not
enter the public fixture, source package, or build manifest until explicitly
graduated.

Build:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-NativeRendererAndroid.ps1
```

## Next Runtime Slices

Next slices should:

- build/install/launch the native renderer with the guide graph and confirm
  `guideGraphReady=true`, `actualFinalExternalHwbSamples=0`,
  `actualGuideTextureSamples=1`, and acceptable color/framing in-headset;
- use the passed no-real-hands recorded replay smoke as the baseline for
  performance and screenshot evidence while tuning the next rendering slices;
- visually validate live OpenXR compact hand input on headset and compare it
  against the recorded replay source in the same resident GPU draw path, with
  the hand mesh diagnostic offset/tint enabled if the default overlay is hard
  to see;
- validate and tune the existing triangle-bounds/tile-bin/narrow-band SDF
  kernel on device, then independently tune visual/particle field resolutions
  when the live/replay visual proof is stable;
- tighten color/reference behavior for imported external HWB textures;
- bind the compact hand input and skinned field to the Matter/Lattice hand
  resource shape;
- use the public no-op private extension slot as the future downstream layer
  handoff point without adding plugin loading or private payloads to the public
  APK;
- keep Colorama, distortion, and other downstream effects behind private ABI
  slots until they are explicitly graduated.
