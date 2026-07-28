# Rusty Quest Native Renderer Android

This package is the Quest-native Android scaffold for the public
`rusty.quest.native_renderer_plan.v1` route:

```text
io.github.mesmerprism.rustyquest.native_renderer/android.app.NativeActivity
```

It is the main public native Quest XR stack in Rusty Quest. Use it for
Rust-first OpenXR/Vulkan examples that need custom projection layers, Meta
passthrough composition, a solid-color XR background, resident GPU-skinned hand
meshes, public blur processing, SDF hooks, or detailed timing markers without a
Makepad runtime in the app.

Runtime routes are selected by profile/property, not by separate APKs:

The reusable particle-contract adapter is independently selectable with
`quest-native-renderer-particle-adapter-conformance.profile.json`. Its property
defaults false. When selected, startup emits a `channel=particle-adapter`
effective receipt proving the native renderer consumes the shared
Matter/Lattice/Optics handoff without enabling a particle renderer, importing
backend resources into the contract, or changing other runtime routes.

The `native_openxr_hand_lab` app build explicitly enables
`debug.rustyquest.native_renderer.hand_adapter.enabled`. The shared adapter
validates provider/basis/hand/rig identity and CPU-prepared parity; OpenXR
acquisition, GPU buffers, shaders, material choice, and hand visibility remain
native-renderer policy. Setting the property to `false` restores the prior path.

| Profile | Background | Visible hand content | Camera2/HWB projection |
| --- | --- | --- | --- |
| `quest-native-renderer-direct-hwb-camera-quality.profile.json` | Custom direct camera projection | Hand/SDF overlays disabled for raw camera inspection | Enabled, forced direct `AHardwareBuffer` sample with Android-suggested YCbCr, UNORM swapchain preference, and clean border |
| `quest-native-renderer-direct-hwb-camera-quality-bt601-unorm.profile.json` | Custom direct camera projection | Hand/SDF overlays disabled for raw camera inspection | Enabled, forced direct `AHardwareBuffer` sample with limited BT.601 YCbCr and UNORM swapchain preference |
| `quest-native-renderer-direct-hwb-low-noise-30.profile.json` | Custom direct camera projection | Hand/SDF overlays disabled for low-noise A/B inspection | Enabled, Android-suggested YCbCr plus support-gated 30 FPS AE, noise reduction, and edge-off request controls |
| `quest-native-renderer-direct-hwb-low-noise-record-30.profile.json` | Custom direct camera projection | Hand/SDF overlays disabled for record-template A/B inspection | Enabled, same low-noise controls as the preview profile but using Camera2 `TEMPLATE_RECORD` |
| `quest-native-renderer-direct-hwb-low-latency-60.profile.json` | Custom direct camera projection | Hand/SDF overlays disabled for low-latency A/B inspection | Enabled, Android-suggested YCbCr plus support-gated 60 FPS AE, fast noise reduction, and edge-off request controls |
| `quest-native-renderer-direct-hwb-hold-sync.profile.json` | Custom direct camera projection | Hand/SDF overlays disabled for sync A/B inspection | Enabled, Android-suggested YCbCr with `AImage` retained until the submitted Vulkan frame fence retires |
| `quest-native-renderer-direct-hwb-hold-sync-reader6.profile.json` | Custom direct camera projection | Hand/SDF overlays disabled for queue-depth A/B inspection | Enabled, hold-sync with `readerMaxImages=6` |
| `quest-native-renderer-direct-hwb-hold-sync-reader8.profile.json` | Custom direct camera projection | Hand/SDF overlays disabled for queue-depth A/B inspection | Enabled, hold-sync with `readerMaxImages=8` |
| `quest-native-renderer-direct-hwb-1280x960.profile.json` | Custom direct camera projection | Hand/SDF overlays disabled for resolution A/B inspection | Enabled, Android-suggested YCbCr with requested 1280x960 reader size and support-gated fallback |
| `quest-native-renderer-display-composite-feedback.profile.json` | Native Meta passthrough with Android MediaProjection feedback plane | Recursive MediaProjection feedback target only; hand/SDF/graft visuals disabled | Camera2 disabled; guide blur disabled; Rust/NDK `AImageReader`/`AHardwareBuffer` sampled through Vulkan, then folded through an app-owned ping-pong feedback texture without CPU pixel copy |
| `quest-native-renderer-replay-visual-proof.profile.json` | Custom camera projection | Recorded GPU-skinned mesh and SDF visual | Enabled |
| `quest-native-renderer-hwb-peripheral-stretch.profile.json` | Custom camera projection with full-eye target-edge stretch/blend border | Optional recorded/live mesh controls remain profile-selectable | Enabled |
| `quest-native-renderer-live-hand-visual-diagnostic.profile.json` | Custom camera projection | Live diagnostic mesh/SDF, pending screenshot acceptance | Enabled |
| `quest-native-renderer-live-hand-anchor-particles.profile.json` | Custom camera projection | Live base hand meshes plus resident GPU anchor particles | Enabled |
| `quest-native-renderer-native-passthrough-graft-only.profile.json` | Native Meta passthrough | Fingertip graft copies only | Disabled |
| `quest-native-renderer-native-passthrough-hands-and-grafts.profile.json` | Native Meta passthrough | Live base hand meshes plus graft copies | Disabled |
| `quest-native-renderer-solid-black-hands-and-grafts.profile.json` | Opaque black projection layer | Live base hand meshes plus graft copies | Disabled |
| `quest-native-renderer-solid-black-openxr-hands-anchor-particles.profile.json` | Opaque black projection layer | Runtime/default OpenXR hands requested plus resident GPU anchor particles; app custom mesh hidden | Disabled |

It consumes the public native renderer fixture at build time. Immersive runtime
ownership remains a Rust NativeActivity in
`librusty_quest_native_renderer.so`. The APK also packages a small same-APK 2D
control panel Activity as `classes.dex`; that panel stages
`stimulus_volume_candidate.json` and reads/writes
`stimulus_volume_status.json` in app-private storage. The Java panel is a
low-rate requester only: it uses native Android controls, does not package
Spatial SDK, WebView, Compose, or Makepad, and does not mutate renderer state
directly. On startup, the Rust NativeActivity reads the staged candidate,
validates `rusty.quest.stimulus_volume.profile.v1`, and applies the effective
stimulus-volume settings before the OpenXR/Vulkan loop starts.
The Rust code opens NDK `ACameraManager` camera ids `50` and `51`, creates
`PRIVATE` GPU-sampled `AImageReader` hardware buffers, acquires
`AHardwareBuffer` frames in Rust callbacks, and emits
`RUSTY_QUEST_NATIVE_RENDERER` timing and counter markers.
Runtime permissions are requested by a tiny Rust/JNI call into Android's
framework `Activity.requestPermissions`; the panel Activity does not own runtime
permission policy.
Quest live hand tracking also requires the selected APK manifest to declare
`com.oculus.permission.HAND_TRACKING` and optional
`oculus.software.handtracking`; in source-only native app builds those
declarations come from the hand-specific feature, not the OpenXR/Vulkan base.
OS-level hand tracking must be enabled before OpenXR reports active joints.
Display-composite feedback uses the same APK identity and a non-exported
foreground `mediaProjection` service. The panel action
`io.github.mesmerprism.rustyquest.native_renderer.action.REQUEST_DISPLAY_COMPOSITE_CAPTURE`
calls Android `createScreenCaptureIntent` on every launch, so a lab
`PROJECT_MEDIA` app-op pregrant only suppresses the prompt; it does not replace
the single-use result-data token. The service passes a Rust-created `Surface`
to `VirtualDisplay`; frame objects stay in Rust through NDK `AImageReader`.
Generic Vulkan `AHardwareBuffer` property query, image import, memory binding,
image-view creation, layout transition, and retained-handle ownership live in
`ahardware_buffer_vulkan.rs`; Camera2 keeps its YCbCr policy above that module,
and display-composite sampling reuses it rather than coupling to Camera2. The
recursive feedback effect is an app-owned device-local ping-pong texture inside
the display-composite renderer; it does not assume MediaProjection can recapture
native passthrough or later compositor layers.
Use
`tools/Grant-NativeRendererPermissions.ps1 -GrantMediaProjectionAppOp` for the
lab pregrant and `-ResetMediaProjectionAppOp` after validation.
The NativeActivity event pump drains `MainEvent::InputAvailable` through
`AndroidApp::input_events_iter()` so controller/menu key events are
acknowledged by Android's input queue while remaining unhandled by the renderer
unless a later layer explicitly consumes them.

## Same-APK 2D Control Panel

`io.github.mesmerprism.rustyquest.native_renderer/.ControlPanelActivity` is a
plain Android 2D panel with `com.oculus.intent.category.2D`. It is intended as
the first low-overhead in-headset setup surface for the stimulus-volume route.
The panel writes a candidate profile:

```text
files/stimulus_volume_candidate.json
```

The candidate schema is `rusty.quest.stimulus_volume.profile.v1`. It can select
the solid-black or native-passthrough stimulus-volume render route, safety
acknowledgement, active/inactive request state, render target tier, raymarch
sample count, central-FOV fraction, gradient smoothing, pattern family,
Trevor-style mirror/warp dynamics, oscillator frequencies, phase offsets, and
randomization Hz bounds. The native panel exposes those fields through pattern
tiles, mirror tiles, and bounded Android `SeekBar` controls; it keeps a visible
Close button inside the panel as well as the platform panel close affordance.
The Rust NativeActivity is the authority: it rejects missing safety
acknowledgement for active requests, rejects randomization and oscillator
frequencies outside `3.0` to `40.0` Hz, rejects unsupported pattern families,
mirror modes, or render targets, disables stale Breathing Room projection-target
controls for volume-only routes, and emits `stimulus-panel` markers when a
candidate is missing, rejected, or applied.

The panel also writes an app-private status witness:

```text
files/stimulus_volume_status.json
```

The native runtime may replace that file with
`rusty.quest.stimulus_volume.apply_status.v1` after startup validation. For
running immersive sessions, the same panel also supports two live paths:
`Apply Live` queues one candidate immediately, while `Live auto update`
debounces control edits and keeps only the newest pending candidate. Both live
paths use a same-process JNI queue that the Rust frame loop drains at a frame
boundary; they do not poll panel files in the GPU command-recording hot path.
When opened, the panel seeds safety, active/randomize, render-target, and
quality controls from the current `debug.rustyquest.native_renderer.*`
properties so `Apply Live` starts from the active runtime profile instead of a
hard-coded render target. Live changes update scalar stimulus settings,
dynamics, and the right-primary randomize gate. Render-mode or render-target
changes are still rejected as restart-required so the live path never
reallocates Vulkan storage images. The hidden
`io.github.mesmerprism.rustyquest.native_renderer.action.APPLY_LIVE_SELF_TEST`
intent is a device-validation hook that calls the same Java submit path as the
visible `Apply Live` button when Quest 2D panel pointer automation is not
reliable. It is handled from both `onNewIntent` and `onResume`; pass a fresh
`diagnostic_token` extra when repeating the self-test against an already-open
single-task panel.

While the immersive native renderer is running, the OpenXR action set binds the
right controller trigger value to a panel toggle. Pressing the right trigger
starts `ControlPanelActivity` with the panel toggle action; if the panel is
already alive, its `onNewIntent` handler closes the panel. This is best-effort
cooperative panel behavior and includes an on-panel Close button as a fallback
for platform modes that move input focus fully to the 2D panel. The A/right
primary button remains reserved for stimulus randomization in the default
stimulus path. In `private-layer-selector` and `driver-profile-session` panel
modes, A/right-primary opens the panel instead and is not also bound to the
projection reset action, so right-thumbstick projection scale control remains
independent.

The stimulus route starts from the saved headset dynamics
`headset-randomize-count-28-2026-06-20` before any new randomize press: a spiral
family at 3.084 Hz with spatial oscillators 6.041, 35.362, and 37.531 Hz.

Set `debug.rustyquest.native_renderer.control_panel.mode=polar-sensor` to open
the same activity as a direct Polar BLE panel. A Polar-enabled native app-build
must request `sensor.polar_h10_ble`; the generated manifest then declares
Bluetooth scan/connect, legacy Bluetooth, fine-location, and BLE feature
surface. The panel scans for Polar devices, connects to the selected device,
subscribes to HR/RR plus Polar PMD control/data characteristics, and writes
bounded app-private stream events to `files/polar_stream_events.jsonl`. The
current direct PMD route keeps one PMD stream active at a time, so the page
defaults to ACC and lets the operator switch the active PMD stream to ECG while
HR/RR notifications remain subscribed.

The Rust core proves Android package, NativeActivity entry, NDK camera/HWB
acquisition shape, native timing counters, OpenXR loader packaging, an
OpenXR/Vulkan prerequisite probe, Vulkan external-HWB import boundary strings,
cached Vulkan external-HWB import for Camera2 frames, and a real submitted OpenXR
stereo projection layer with metadata-targeted camera projection, a public
384x384 per-eye guide blur graph, the recorded hand replay overlay, resident
compact-joint GPU-skinned triangle visual, native GPU mesh boundary, and
opt-in native compact-joint skinned-mesh GPU SDF path in scorecards. The same
final guide projection can optionally enable the Makepad-reference peripheral
stretch/blend border natively through
`debug.rustyquest.native_renderer.processing.layer=peripheral-stretch`,
expanding coverage to the full eye while keeping the metadata-owned camera
target as the source core. When
`XR_EXT_hand_tracking` is available, the same
resident path can consume live OpenXR hand joints as the compact input source:
21 runtime poses plus packed tip lengths, not expanded mesh vertices.
This follows the original capture/export contract: per-hand rig files own the
Meta/OpenXR bind topology, triangle components, blend weights, and bind poses;
clip JSONL frames carry compact runtime joint poses plus tip lengths; validation
mesh frames and GLB export are witnesses built from that rig plus clip data.
The native runtime therefore treats live Meta joints as the dynamic input for
the same resident Meta-derived topology instead of importing per-frame expanded
mesh vertices.
The live adapter preserves left and right compact frames separately. The
primary hand feeds the current single SDF field, while a second resident
GPU-skinned visual path can draw the other live hand in the same metadata-owned
target area. Scorecards report `liveMetaHandUsingBoth`,
`liveMetaHandVisualizableHandCount`, `liveHandMeshVisualLeftVisible`,
`liveHandMeshVisualRightVisible`, and
`liveHandMeshVisualBothHandsVisible` so two-hand tracking is not collapsed into
the old one-hand marker.
The live mesh visual is deliberately not considered accepted by markers alone:
during the 2026-06-17 live-hand check the user had real hands in view, but did
not see a mesh or SDF representation in the headset. Future headset retests
should enable the high-contrast diagnostic overlay with
`debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled`, optional
`debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.offset_uv`, and
`debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.alpha`, then
capture visual evidence that the mesh and SDF effects are visible over the
camera projection.
For exact triangle inspection, enable
`debug.rustyquest.native_renderer.hand_mesh.visual.wireframe.enabled=true`.
The wire overlay is shader-barycentric on the resident selected triangle list,
so it works for live compact joint skinning, recorded joint replay, and
post-skinning graft copies without CPU-expanded edge buffers. Select the
resident visual source with
`debug.rustyquest.native_renderer.hand_mesh.visual.mesh_source`: `auto` accepts
the packaged topology, `openxr-fb-mesh` requires the Meta/OpenXR FB mesh
topology, and `custom-mesh` requires a non-FB custom topology. The visual source
and wireframe enabled/width properties are runtime-polled, so low-rate
`adb setprop` changes can switch the inspection mode without relaunching.
The optional field overlay now uses the clearer property
`debug.rustyquest.native_renderer.sdf.field_visual.enabled`; the older
`debug.rustyquest.native_renderer.sdf.visual.enabled` remains a compatibility
alias.
The staged property bundle for that later retest is
`fixtures/runtime-profiles/quest-native-renderer-live-hand-visual-diagnostic.profile.json`;
it forces `live-meta-openxr-hand-tracking`, keeps recorded fallback available
until live joint frames arrive, enables the high-contrast mesh diagnostic plus SDF visual, and keeps live mesh/SDF
acceptance pending until screenshot evidence shows visible overlay color.
The optional graft-copy experiment is controlled separately by
`debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled`; the shared
profiles set it to `false` so replay and live diagnostic runs do not inherit a
stale experimental copy mode.
`fixtures/runtime-profiles/quest-native-renderer-live-hand-anchor-particles.profile.json`
keeps that graft path disabled, selects live OpenXR hand tracking, makes the
live base hand meshes visible, and enables the resident Vulkan particle layer
through `debug.rustyquest.native_renderer.hand_anchor_particles.enabled=true`.
The particle layer evaluates deterministic barycentric coordinate anchors in
the vertex shader over the resident skinned-position and triangle buffers for
each hand, draws camera-facing billboards in OpenXR reference-space meters, and
uses a static feather-dot luminance alpha mask with no animation. Its markers
report `handAnchorParticleCpuExpandedUploadPerFrame=false` and
`handAnchorParticleMeshUploadPerFrame=false`; the only per-frame hand input
remains the compact live joint/tip-length upload used by the existing GPU
skinning path.
The same renderer also exposes standard particle transparency and ordering
controls. Per-particle back-to-front ordering is implemented as a resident GPU
index-remap pass over the GPU particle output buffer; the CPU path is limited
to property/profile selection and never uploads expanded sorted particle rows
in steady state.
`fixtures/runtime-profiles/quest-native-renderer-solid-black-openxr-hands-anchor-particles.profile.json`
is the topology-matching comparison route: it skips Camera2/custom projection,
clears the OpenXR projection layer to solid black, disables the app's custom
hand mesh and graft visuals, requests the runtime/default OpenXR hand visual as
the comparison hand, and keeps the same resident-mesh anchor particles visible
in world space.
`fixtures/runtime-profiles/quest-native-renderer-environment-depth-status.profile.json`
is the first source-only environment-depth status profile. It owns only the
environment-depth low-rate properties, keeps depth images and particle/map
buffers out of JSON, reports the disabled/status skeleton through
`RUSTY_QUEST_NATIVE_RENDERER channel=environment-depth`, and leaves actual
OpenXR environment-depth provider binding for a later GPU slice.
`fixtures/runtime-profiles/quest-native-renderer-native-passthrough-environment-depth-particles.profile.json`
is the pure native GPU proof route. It disables the hand/SDF overlay paths,
uses native passthrough, fills a resident Vulkan storage buffer from a compute
shader with synthetic depth-view samples mapped into OpenXR reference-space
meters, and draws reference-space billboards through each current eye pose/FOV.
Markers on `RUSTY_QUEST_NATIVE_RENDERER channel=environment-depth-particles`
report `environmentDepthParticleCpuUploadBytes=0`,
`environmentDepthGpuBuffersResident=true`, and
`environmentDepthParticleBufferMemory=device-local`, and
`environmentDepthParticleCoordinateSpace=openxr-reference-space`. This proves
the native passthrough particle mapping stack, not a bound
`XR_META_environment_depth` provider.
`fixtures/runtime-profiles/quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json`
is the real provider scene-map route, matching the later legacy
`SceneParticleMap` behavior rather than the earlier view-grid overlay. It
requests `XR_META_environment_depth`, requires `horizonos.permission.USE_SCENE`,
sets `environment_depth.layer_policy=mono-layer0`, samples layer 0 as an
explicit mono source from the D16 two-layer depth swapchain in native Vulkan
compute (`environmentDepthSourceViewCount=1`,
`environmentDepthSampledLayerMask=0x1`,
`environmentDepthShaderLayerPolicy=mono-layer0`),
reconstructs depth samples into OpenXR local reference space, hashes
`0.06m` reference-space cells into the bounded particle buffer, preserves
existing cells on invalid samples, applies confidence-gated visible-free-space
correction with the `near-plus-cell-step-cap` range policy, and draws those
retained cells over `XR_FB_passthrough`. Run it through
`tools/Invoke-NativeRendererReplaySmoke.ps1 -EvidenceMode EnvironmentDepthParticles`;
the wrapper serial-scopes ADB, pregrants the declared permissions with
`tools/Grant-NativeRendererPermissions.ps1`, and accepts only runtime markers
showing acquired Meta depth frames, `environmentDepthMode=scene-particle-map`,
nonzero source depth samples, `spatial-hash-reference-space-cells`, zero
expanded CPU particle upload, resident GPU buffers, and device-local particle
memory. The same evidence marker now carries the Iteration 5 scorecard fields:
render view-state flags, capture-to-display/frame-age timing, acquire-to-render
timing on the particle path, repeated-capture and unavailable-streak counters,
explicit texture-transform/ray-UV/sample-UV policy labels, the
edge-aware four-neighbor confidence filter label, and the free-space
confidence-skip counter.
For the deliberate world-space motion proof, use
`tools/Invoke-NativeRendererEnvironmentDepthMotionProof.ps1`; it runs the same
Meta environment-depth particle path and requires render-view pose-delta
evidence, defaulting to at least 120 head-motion samples and 25 degrees of yaw.
Pass `-MinimumTranslationM` when a lateral-translation gate is needed in the
same run.
For the known-distance raw-D16 proof, use
`tools/Invoke-NativeRendererEnvironmentDepthKnownDistanceProof.ps1`; it runs
the same Meta environment-depth particle path but requires the raw-D16 debug
view and checks `environmentDepthCenterReconstructedMeters`,
`environmentDepthCenterConfidence`, and
`environmentDepthRawCenterWindowValidCount` against the supplied
`-TargetDistanceMeters`, tolerance, and minimum evidence thresholds. Run it once
per measured target distance; then run
`tools/Test-NativeRendererEnvironmentDepthKnownDistanceSeries.ps1` over the
resulting summaries to require reconstructed meters and raw D16 to be monotonic
across the measured target set.
After the deliberate motion proof and the four known-distance runs exist, run
`tools/Test-NativeRendererEnvironmentDepthEvidenceBundle.ps1` over the motion
`run-summary.json`, known-distance `run-summary.json` files, and known-distance
series result. That checker verifies the wrapper route and runtime summaries as
one bundle while keeping the headset visual acceptance requirement explicit.
For the final device session, prefer
`tools/Invoke-NativeRendererEnvironmentDepthAcceptanceSuite.ps1`; it runs the
motion proof, the 0.5 m, 1 m, 2 m, and 4 m known-distance proofs, the
known-distance series checker, and the evidence-bundle checker in that order.
`fixtures/runtime-profiles/quest-native-renderer-native-passthrough-meta-environment-depth-particles-layer1.profile.json`
is the A/B comparison profile for `environment_depth.layer_policy=mono-layer1`.
It samples texture-array layer 1 and depth view 1 with
`environmentDepthSampledLayerMask=0x2` and
`environmentDepthShaderLayerPolicy=mono-layer1`; it is still mono-source
evidence, not stereo fusion.
`fixtures/runtime-profiles/quest-native-renderer-native-passthrough-meta-environment-depth-particles-low-capacity.profile.json`
is the stress profile for the same real Meta provider scene-map path. It keeps
the layer-0 OpenXR-local world-space map policy fixed, lowers the particle
capacity to 64, and samples every 4 pixels so headset evidence can require
`environmentDepthParticleCount=64` plus nonzero exhausted hash probes. Use it
only as a bounded-map stress route; the normal 32768-capacity profile remains
the default quality proof.
`fixtures/runtime-profiles/quest-native-renderer-native-passthrough-meta-environment-depth-particles-debug-colors.profile.json`
keeps the same real Meta provider scene-map route but switches
`environment_depth.debug_view=free-space-state`. The particle marker reports
`environmentDepthParticleDebugColorMode=free-space-state`, while the normal,
layer-1, and low-capacity profiles report `depth-gradient`. The same
`debug_view` setting can also select confidence, age, source-layer, and
hash-probe diagnostic colors without adding a second Android property.
The Iteration 8 acceptance matrix is captured as dedicated dry-run profiles:
`quest-native-renderer-envdepth-layer0.profile.json`,
`quest-native-renderer-envdepth-layer1.profile.json`,
`quest-native-renderer-envdepth-raw-depth-debug.profile.json`,
`quest-native-renderer-envdepth-local-space.profile.json`,
`quest-native-renderer-envdepth-stage-space.profile.json`,
`quest-native-renderer-envdepth-capacity-65536.profile.json`,
`quest-native-renderer-envdepth-stride-8.profile.json`, and
`quest-native-renderer-envdepth-hand-removal.profile.json`. They keep the same
real Meta scene-particle-map path while varying exactly one source-view,
debug-view, reference-space, capacity, stride, or
`xrSetEnvironmentDepthHandRemovalMETA` policy.
The source-side surface-support profiles are
`quest-native-renderer-envdepth-local-surfels.profile.json`,
`quest-native-renderer-envdepth-global-surfaces.profile.json`,
`quest-native-renderer-envdepth-hybrid-surfaces.profile.json`, and
`quest-native-renderer-envdepth-source-layer-agreement.profile.json`. They add
validated `environment_depth.surface_model` and
`environment_depth.surface_support.*` properties plus
`environmentDepthSurfaceSupport*` markers for local candidates, connected
global surfaces, hybrid mode, and non-default two-source-layer agreement. The
dry-run profile markers remain non-enforcing, but the real Meta
environment-depth scene-map compute path now uses those settings to apply a
bounded GPU local-depth-neighborhood support gate before writing retained scene
cells. Runtime particle markers report
`environmentDepthSurfaceSupportEnforced=true`,
`environmentDepthSurfaceSupportStatus=enforced-local-depth-neighborhood-component-local-hint`,
`environmentDepthSurfaceSupportedCells`, and
`environmentDepthSurfaceRejectedIsolatedCells` when that gate is active. They
also report aggregate local-patch component hints through
`environmentDepthSurfaceLargestComponentCells`,
`environmentDepthSurfaceComponentCandidateCells`, and
`environmentDepthSurfaceConfirmedComponentCells`, plus
`environmentDepthSurfaceLifecycleStatus` with candidate, confirmed, promoted,
and candidate-retired cell counters. Source-layer
agreement uses `environmentDepthSourceLayerAgreementRequired`,
`environmentDepthSourceLayerAgreementCells`, and
`environmentDepthSingleLayerOnlyCells`; the connected-component/global-surface
connected-label pass is still pending and requires later headset evidence.
`fixtures/runtime-profiles/quest-native-renderer-native-passthrough-graft-only.profile.json`
keeps native passthrough focused on graft instances only, while
`fixtures/runtime-profiles/quest-native-renderer-native-passthrough-hands-and-grafts.profile.json`
also sets `debug.rustyquest.native_renderer.hand_mesh.real_hands.visible=true`
so the base live hand meshes draw over native passthrough before the graft
instances.
`fixtures/runtime-profiles/quest-native-renderer-solid-black-hands-and-grafts.profile.json`
uses the same live hand mesh and graft settings without native passthrough or
Camera2 projection; the submitted projection layer clears to opaque black.
Live diagnostic builds now keep the resident GPU-skinned position buffer in
OpenXR reference-space meters. Live compact-hand frames draw through each
eye's OpenXR pose/FOV (`handMeshVisualProjectionSpace=openxr-eye-fov-world-space`)
instead of fixed target-local UVs. The shader explicitly converts OpenXR
eye-space `+Y` up into the current positive-height Vulkan viewport convention
(`handMeshVisualClipY=openxr-y-up-to-vulkan-positive-viewport`) so vertical hand
motion stays aligned in headset space. Recorded replay can still use a
metadata-target diagnostic mapper for no-real-hands screenshots. When the live
path is active, `live-hand-mesh-target-proof` reports
`liveHandMeshTargetProofPath=gpu-skinned-resident-triangle-fill`,
`liveHandMeshJointOverlaySuppressed=true`, and the joint skeleton fallback is
not drawn. This separates "the live joints are visible" from "the real
GPU-skinned mesh is visible."
In the first headset inspection of the live mesh route, the left live mesh
looked good while the right mesh was visibly deformed. The browser-side "hand
job" preview showed the important correction: the right hand is not a mirrored
left route; it uses the distinct `mesh1` / `right.rig.json` /
`right.clip.jsonl` source. The native runtime now loads a paired replay set,
keeps the primary path on the left source, and creates the secondary
GPU-skinned visual/SDF resources from the right-hand source when that local
capture is embedded. Markers report `recordedHandReplayRightHandDistinct`,
`recordedHandReplayRightHandedness`, `handMeshVisualSourceHandedness`, and
`handMeshVisualSecondarySourceHandedness` so headset logs can prove the right
draw came from right-hand bind topology. This still needs a live headset retest
before calling the right-hand visual accepted.

For no-real-hands isolation, set
`debug.rustyquest.native_renderer.replay.visual_proof.enabled=true`. That preset
selects the recorded replay input by default, enables the high-contrast mesh
diagnostic plus SDF visual, and reports
`recordedReplayVisualProofEnabled=true`,
`compactHandInputSourceMode=recorded-replay`, and
`recordedReplayVisualAcceptance=pending-headset-screenshot`. Override
`debug.rustyquest.native_renderer.hand_mesh.input.source` only when a test needs
`auto`, `recorded-replay`, or `live-meta-openxr-hand-tracking` explicitly.
The reproducible property bundle is
`fixtures/runtime-profiles/quest-native-renderer-replay-visual-proof.profile.json`.

The OpenXR/Vulkan probe initializes the Android OpenXR loader, creates the
OpenXR instance with `XR_KHR_android_create_instance` and
`XR_KHR_vulkan_enable2`, asks the runtime for Vulkan graphics requirements,
creates an OpenXR-selected Vulkan instance, and records whether the selected
device exposes Android hardware-buffer external memory plus sampler YCbCr
conversion support. These probe markers report prerequisite readiness only.
Runtime scorecards may report `openxrSubmitReady=true` after a submitted
diagnostic projection frame. `vulkanExternalImportReady=true` is reserved for
frames where Camera2 `AHardwareBuffer` objects were imported into Vulkan
external images and bound through the immutable YCbCr descriptor path.

For raw camera-quality inspection, the
`quest-native-renderer-direct-hwb-camera-quality.profile.json` route sets
`debug.rustyquest.native_renderer.camera.output=direct-hwb`, bypasses the guide
and private projection outputs, disables hand/SDF overlays, and reports
`cameraProjectionPath=metadata-target-direct-hwb-forced` with
`directHwbProjectionDiagnostic=true`. It also sets
`debug.rustyquest.native_renderer.camera.direct_border.opacity=0.0` so the
projection target contains only sampled camera color. It uses Android-suggested
YCbCr plus `debug.rustyquest.native_renderer.swapchain.color_format=unorm`,
with `debug.rustyquest.native_renderer.camera.quality_profile=direct-baseline`
and `debug.rustyquest.native_renderer.camera.sync_mode=early-delete-ahb-retained`
logged as the active public baseline. The
`quest-native-renderer-direct-hwb-camera-quality-bt601-unorm.profile.json`
route keeps the same visual baseline but sets
`debug.rustyquest.native_renderer.camera.ycbcr.mode=forced-bt601-narrow` and
`debug.rustyquest.native_renderer.swapchain.color_format=unorm`; device logs
then report suggested/effective YCbCr model/range plus selected swapchain
format for range/matrix/gamma A/B review. The
`quest-native-renderer-direct-hwb-low-noise-30.profile.json` route keeps the
Android-suggested/UNORM baseline and requests support-gated Camera2 controls
for 30 FPS AE, high-quality noise reduction with fast fallback, and edge
enhancement off. `quest-native-renderer-direct-hwb-low-noise-record-30.profile.json`
keeps those controls but creates the repeating request from Camera2
`TEMPLATE_RECORD` for preview-vs-record A/B checks. AE FPS markers report the
requested, selected, and applied range, using exact support first and the
nearest exposed range when needed. `quest-native-renderer-direct-hwb-low-latency-60.profile.json`
requests the matching low-latency 60 FPS profile, while
`quest-native-renderer-direct-hwb-1280x960.profile.json` requests the historical
1280x960 reader size for resolution-path A/B checks. PRIVATE reader-size
fallbacks are ranked by tested preferred sizes, aspect fit, target-FPS
feasibility, and exposed min-frame duration when Camera2 reports it. Device logs include
`camera-capabilities`, `camera-request-profile`, `camera-capture-result`,
buffer-removed listener, selected reader size, image dataspace when the runtime
exports `AImage_getDataSpace`, and YCbCr format-feature markers. For objective
range checks, set
`debug.rustyquest.native_renderer.camera.luma_diagnostic.enabled=true`; the
renderer then runs an opt-in Vulkan compute diagnostic over the resident
direct-HWB image views and reports per-eye luma mean/min/max plus a
high-frequency ratio in `timing-scorecard`.
`quest-native-renderer-direct-hwb-hold-sync.profile.json` activates the
conservative synchronization diagnostic: the camera callback retains the
`AImage` for the sampled frame and the render loop releases it only after the
Vulkan fence for that submitted frame slot has completed. The reader6 and
reader8 hold-sync variants keep the same visual route but raise
`debug.rustyquest.native_renderer.camera.reader_max_images` for ImageReader
queue-headroom A/B checks. The lower-latency
`AImage_deleteAsync`/sync-fd release path is available as
`debug.rustyquest.native_renderer.camera.sync_mode=delete-async-release-fence`.
It uses the async ImageReader acquire/release APIs and reports acquire-fence fd
presence, while markers still call out that Vulkan external-semaphore ownership
transfer is pending. Keep the hold-sync profile as the fence-backed safety
comparison. 

Stereo pairing defaults to latest left plus latest right frame. Set
`debug.rustyquest.native_renderer.camera.stereo_pairing=nearest-timestamp` to
enable the bounded recent-frame pairing diagnostic; timing scorecards then
report `stereoPairingPolicy=nearest-timestamp` with `stereoPairDeltaNs` for
fixed-scene comparisons.

Acceptance caveat: the current visual acceptance covers the native diagnostic
projection, recorded hand replay overlay, metadata-owned camera target area,
guide-texture final projection route, and no-real-hands mesh/SDF overlay
screenshot evidence. Color conformance and projection alignment remain pending
even when runtime markers report `guideGraphReady=true`,
`cameraProjectionPath=metadata-target-guide-texture-final` or
`cameraProjectionPath=metadata-target-guide-texture-peripheral-stretch-final`,
`actualFinalExternalHwbSamples=0`, and `actualGuideTextureSamples=1`, with the
direct-HWB projection path acting only as fallback. The real recorded capture can be embedded
for local builds with `-RecordedHandCaptureDir`; committed fixtures keep only a
public topology/shape summary. The opt-in SDF visual is a low-resolution target
field built from the recorded rig blend indices/weights and compact joint
frames. It keeps source mesh, bind-pose, and bind-joint-source buffers
resident, uploads only runtime joint poses plus packed tip-length rows per
frame, dispatches GPU skinning into a resident skinned-position buffer, and
then computes or reuses the SDF field when the visual SDF property is enabled.
The update cadence is controlled by
`debug.rustyquest.native_renderer.sdf.update_period_frames` and scorecards
distinguish `sdfFieldUpdateDispatched=true` from `sdfFieldReused=true`. It reports
`targetSpaceMeshToSdfKernelAvailable=true` for the route and
`meshToSdfKernel=true` only on frames where the opt-in field is computed.
Recorded replay `fullSkinnedMeshSdfReady=true` is scoped to this native
full-capture build path; the 2026-06-17 no-real-hands replay smoke visually
validated the recorded mesh/SDF overlay in headset screenshots. Live compact
input is build-validated but still needs headset visual acceptance, and direct
Meta hand-mesh topology import, Matter/Lattice-backed SDF parity, and live
headset visual SDF acceptance remain pending. The active mesh visual draws the
shared resident skinned-position buffer through a descriptor based Vulkan
triangle pipeline, while component ranks for hand-inside, hand-back, and wrist
cap remain metadata rather than visible color bands. The normal hand material
is a continuous single surface color with depth/normal shading; diagnostics can
brighten that same continuous surface. The resident buffer is now
`skinnedPositionBufferCoordinateSpace=openxr-reference-space`; the SDF visual
still projects that world-space mesh into a low-resolution metadata-target
field for this slice. When graft copies are enabled and both live hands are
visible, the already-skinned left mesh can be instanced onto the right
fingertips and the already-skinned right mesh onto the left fingertips using
palm anchors and a wrist-radius to target-finger-radius scale. Guide blur
headset/color acceptance, color conformance, and projection alignment also
remain pending.

The public effect surface includes the blur guide path, the target-edge
peripheral stretch/blend border, the recorded hand replay visual, the resident
compact-joint GPU-skinned triangle overlay, the native GPU mesh boundary, the
opt-in live hand graft-copy visual, and the opt-in recorded compact-joint
skinned-mesh GPU SDF path. Private downstream visual layers remain extension
slots and are not packaged here.

Build:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Resolve-NativeAppBuild.ps1 -AppSpec .\fixtures\native-app-builds\native-openxr-hand-lab.app.json -DryRun
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-NativeRendererAndroid.ps1 -AppBuildLock .\local-artifacts\native-app-builds\native_openxr_hand_lab\<resolution-fingerprint>\feature-lock.json
```

Locked builds require a clean exact source commit/tree, use the app-specific
package/client identity, and write an immutable content-addressed output with
`build-manifest.json` and `run-capsule.json`. See
`docs/APK_RUN_ISOLATION.md`. `-AllowUnlockedDevelopmentBuild` is an explicit
compatibility escape and is not acceptance evidence.

Explicit unlocked recorded-replay compatibility build (not acceptance):

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-NativeRendererAndroid.ps1 -AllowUnlockedDevelopmentBuild -RecordedHandCaptureDir <recorded-hand-capture-dir> -RecordedHandFrameLimit 8 -RequireRecordedHandCapture
```

Static validation:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeRendererAndroid.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-replay-visual-proof.profile.json -DryRun -Out .\local-artifacts\native-renderer-replay-visual-proof-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-direct-hwb-camera-quality.profile.json -DryRun -Out .\local-artifacts\native-renderer-direct-hwb-camera-quality-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-direct-hwb-camera-quality-bt601-unorm.profile.json -DryRun -Out .\local-artifacts\native-renderer-direct-hwb-camera-quality-bt601-unorm-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-direct-hwb-low-noise-30.profile.json -DryRun -Out .\local-artifacts\native-renderer-direct-hwb-low-noise-30-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-direct-hwb-low-noise-record-30.profile.json -DryRun -Out .\local-artifacts\native-renderer-direct-hwb-low-noise-record-30-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-direct-hwb-low-latency-60.profile.json -DryRun -Out .\local-artifacts\native-renderer-direct-hwb-low-latency-60-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-direct-hwb-hold-sync.profile.json -DryRun -Out .\local-artifacts\native-renderer-direct-hwb-hold-sync-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-direct-hwb-hold-sync-reader6.profile.json -DryRun -Out .\local-artifacts\native-renderer-direct-hwb-hold-sync-reader6-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-direct-hwb-hold-sync-reader8.profile.json -DryRun -Out .\local-artifacts\native-renderer-direct-hwb-hold-sync-reader8-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-direct-hwb-1280x960.profile.json -DryRun -Out .\local-artifacts\native-renderer-direct-hwb-1280x960-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-hwb-peripheral-stretch.profile.json -DryRun -Out .\local-artifacts\native-renderer-hwb-peripheral-stretch-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-live-hand-visual-diagnostic.profile.json -DryRun -Out .\local-artifacts\native-renderer-live-hand-visual-diagnostic-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-live-hand-anchor-particles.profile.json -DryRun -Out .\local-artifacts\native-renderer-live-hand-anchor-particles-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-solid-black-openxr-hands-anchor-particles.profile.json -DryRun -Out .\local-artifacts\native-renderer-solid-black-openxr-hands-anchor-particles-property-write-plan.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath .\fixtures\runtime-profiles\quest-native-renderer-native-passthrough-hands-and-grafts.profile.json -DryRun -Out .\local-artifacts\native-renderer-native-passthrough-hands-and-grafts-property-write-plan.json
```

Runtime evidence validation for a no-real-hands replay smoke:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-ApkRunCapsule.ps1 -Path <content-addressed-output>\run-capsule.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererReplaySmoke.ps1 -RunCapsule <content-addressed-output>\run-capsule.json -Serial <quest-serial> -RunSeconds 12
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeRendererRuntimeEvidence.ps1 -LogcatPath <filtered-logcat.txt> -ScreenshotPath <screenshot.png> -RequireScreenshot -RequireNonFlatScreenshot -RequireTargetNonFlatScreenshot -RequireHandMeshVisualScreenshot -RequireSdfVisualScreenshot -RequireCameraProjection -RequireReplayVisualProof -RequireGuideGraph -RequireSdfVisual -RequireGpuTimestampReady -RequirePerformanceBudget -RequirePrivateSlotNoPayload
```

The wrapper validates the run capsule, installs its APK unless `-SkipInstall`
is passed, applies the capsule profile with complete property closure, captures
filtered logcat plus a
screenshot, and then calls the checker with non-flat screenshot analysis unless
`-AllowFlatScreenshot` is passed. The checker treats logcat markers and
screenshots as acceptance evidence only when the latest dedicated markers prove
submitted camera projection, recorded replay hand visual visibility,
guide-texture final projection, SDF visual visibility, Vulkan timestamp
readiness when required, screenshot dimensions plus sampled luminance/color
variation in the full screenshot, runtime-emitted target UV rectangles, and
separate runtime-emitted hand-mesh/SDF overlay evidence rectangles. This keeps
camera target variation separate from mesh/SDF visual proof, and the overlay
regions are also checked for expected high-chroma diagnostic color families so
grayscale camera detail alone is rejected. The wrapper writes target,
hand-mesh, and SDF crop PNGs under `screenshot-crops/` beside the runtime
summary for direct inspection. It also requires stage-level performance budgets
by default; pass
`-AllowPerformanceBudgetMiss` only when collecting failed-run artifacts is the
priority.

Display-composite MediaProjection smoke:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererDisplayCompositeSmoke.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -Serial <quest-serial> -RunSeconds 12
```

The display-composite wrapper installs the APK unless `-SkipInstall` is passed,
applies the display-composite runtime profile, grants and later resets the lab
`PROJECT_MEDIA` app-op, launches the panel capture action with a fresh
`display_composite_request_token`, and requires PID-scoped markers for the
Rust-created `Surface`, native `AImageReader`, and `AHardwareBuffer` frame
handoff without Java per-frame buffers or CPU pixel copies.

Device-facing smoke runs require `-Serial <quest-serial>` or
`RUSTY_QUEST_SERIAL`. Use `-AdbServerPort` or `RUSTY_QUEST_ADB_SERVER_PORT`
only when deliberately routing through a non-default ADB server. Logcat capture
is PID-scoped by default; pass `-ClearLogcat` only for an exclusive headset run
where clearing that device's log buffer is acceptable.
For the later live-hand retest, pass
`-EvidenceMode LiveVisualDiagnosticCaveat` without overriding `-ProfilePath`;
the wrapper will apply
`quest-native-renderer-live-hand-visual-diagnostic.profile.json` and require
the live-marker caveat plus screenshot overlay-color gates instead of recorded
replay proof.
