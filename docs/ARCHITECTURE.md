# Rusty Quest Architecture

Rusty Quest owns platform profile and validation contracts for Quest-hosted
apps.

## Ownership

- runtime profile contracts;
- Android property hygiene and write/readback plans;
- Quest device profile catalogs;
- launch and validation receipts;
- platform tooling wrappers.
- Quest-owned Android package adapters for platform-hosted broker surfaces.
- host-to-Quest device-link report contracts for device identity, ADB
  forward/tunnel state, broker endpoint readiness, runtime subscriber health,
  command-result stages, and stream capability descriptors.
- QCL-040/QCL-041 Wi-Fi Direct lifecycle source artifacts, including live
  evidence tier, Agent Board quest lease identity, peer discovery, group
  formation, bounded TCP socket exchange, and cleanup evidence.
- remote camera session plans, device-kind declarations, media-lane safety
  policy, low-rate runtime endpoint bindings, peer transport routes, and
  platform validation gates for Quest and Android phone endpoints.
- native OpenXR/Vulkan renderer plan contracts, pure-HWB import evidence,
  public/private layer ABI boundaries, and timing scorecards for Quest-native
  rendering examples.
- profile-owned fullscreen stereo video input settings, stream metadata, and
  validation guards for app-private video projected through the native
  OpenXR/Vulkan path.
- narrow Meta Spatial SDK Android experiment lanes when they are Quest platform
  adapters for panel placement, sizing, launch, and headset validation rather
  than Morphospace geometry, rendering, or session authority.

## Non-Ownership

- Makepad widget or shell implementation;
- Matter mesh, SDF/ADF, collision, or particle truth;
- Optics view/projection/appearance truth;
- Manifold command/session authority;
- Lattice reference-space or tracked-pose authority.
- Makepad-side media projection/adoption, app widgets, or H.264 texture
  import.
- high-rate frame payload transport through Rusty Quest core contracts.
- host-side UI/session orchestration, WPF page state, or app-private fallback
  execution.
- Matter SDF truth, Optics projection semantics, or private downstream layer
  implementation payloads for native renderer extension slots.
- private downstream effect kernels, tuned profiles, study semantics, live
  hand-mesh dynamics, or coupling parameters beyond the low-rate generic
  driver-profile panel records needed for headset validation.

ADB writes are generated operations from validated profiles. They are not
hand-authored settings authority.

## Device Link Contracts

`crates/rusty-quest-device-link` owns `rusty.quest.device_link.v1`, a
source-only report contract for host-to-Quest connectivity. The report is the
shared boundary that WPF, Makepad tooling, Hostess CLI routes, and later
frontends can inspect without becoming device or command authority.

The contract separates:

- device identity observed through serial-scoped ADB;
- ADB forward/tunnel state, including host and device ports;
- Manifold broker endpoint readiness over `/manifold/v1/events`;
- runtime subscriber health for broker-dispatched request/receipt streams;
- command-result reports with required stages such as `sent`,
  `transport_ok`, `authority_accepted`, `runtime_accepted`, and `applied`;
- stream capability descriptors that distinguish command WebSocket events,
  LSL sample streams, UDP low-latency telemetry, binary media, and app-private
  JSON fallback paths.

The crate does not open sockets, launch APKs, parse logcat, or run ADB.
Adapters such as Hostess may execute those routes and then emit the report.
Validation rejects high-rate JSON stream claims and rejects applied command
results that lack runtime receipt stages, keeping transport readiness separate
from effective runtime state.

The same crate owns `rusty.quest.connectivity_wifi_direct_lifecycle.v1` for
QCL-040/QCL-041 source evidence. Hostess may normalize this artifact into its
connectivity topology report, but the artifact itself must identify the live
source run, harness owner, matching Agent Board quest lease, peer class, and
the full Wi-Fi Direct lifecycle from feature/permission checks through peer
discovery, group formation, bounded TCP probe, and cleanup. Template artifacts
or raw feature checks are not promotion evidence.

It also owns the protocol-neutral direct-P2P socket route and BLE rendezvous
contracts. `rusty.quest.direct_p2p_socket_route.v1` is the reusable lower
boundary for route kind, scoped socket authority, interface, local bind, peer
endpoint, supported subnet, and Android-`Network` non-requirement. It is
data-only and opens no sockets. The compact BLE/GATT `rqrv` contract may carry
authenticated low-rate proposals and already-observed endpoint hints, but the
BLE adapter may not mutate Wi-Fi Direct state, execute Manifold commands, or
carry media. Manifold or a platform lifecycle owner decides whether to act on
an accepted proposal.

The peer-session adapter is the explicit bridge: it validates and digests the
BLE pair artifact, then submits an authenticated expiring proposal to
Manifold. Manifold owns accept/reject, replay, peer-change, and revocation
decisions. The product Wi-Fi Direct provider consumes only a fresh
`rusty.manifold.peer.topology_authorization.v1` at the current revision and
checks its assigned local role before Android topology initialization.

## Reusable Media Stream Contracts

`crates/rusty-quest-media-stream` owns
`rusty.quest.media_stream_session.v1`, a source-only contract for reusable
H.264 media streaming. It generalizes low-rate source descriptors, capture
authority, binary media lanes, bounded queues, receiver-first startup, local
endpoint bindings, peer routes, packet-size expectations, and promotion
counters without owning Manifold authority, sockets, encoders, decoders, ADB,
MediaProjection consent, or hidden platform APIs.

Its `MediaStreamSessionRuntime` is the deterministic platform-adoption layer,
not Manifold accepted-state authority. Runtime specs preserve the accepted
Manifold decision/revision, select sources/processors/routes/sinks explicitly,
and advance receiver-first lifecycle only from adapter evidence. Direct-P2P
bindings consume the existing scoped Rust socket route contract; processors
do not own codecs, and packed-SBS does not permit CPU pixel copies.

Display-derived sources identify their route explicitly. App-consent
MediaProjection display composite is the production candidate; shell
hidden-display mirror remains `lab_developer_only` and must declare its shell
authority and developer-only requirement.

## Native Quest Renderer Contracts

### Closed feature activation

`rusty-quest-feature-activation` owns bounded parsing of the portable v1 lock,
exact application-accepted digest binding, module-ID dependency and feature-ID
conflict closure, runtime-input comparison, and common rejection markers. Hand
and particle crates wrap its private decision in different nominal facade types,
so authority cannot cross module effect gates. App shells own their project,
feature, module, profile, digest, and all platform effects. See
[FEATURE_ACTIVATION.md](FEATURE_ACTIVATION.md) for the full anti-drift contract.

`crates/rusty-quest-native-renderer` owns
`rusty.quest.native_renderer_plan.v1` and
`rusty.quest.native_renderer_timing_scorecard.v1`. These contracts describe the
clean native path for Camera2 AHardwareBuffer import, Vulkan external-image
descriptor shape, low-resolution guide blur, optional Matter SDF inputs,
private layer ABI slots, and per-stage timing evidence.

The crate does not link Android, OpenXR, Vulkan, Makepad, Matter, Optics, or
Lattice runtime crates. Runtime adapters must consume the public plan and
report scorecard evidence instead of becoming hidden authority.

## Spatial Camera Panel Android Package

`apps/spatial-camera-panel-android` owns the separate Meta Spatial SDK package
lane for public Quest panel and camera-stack validation:

```text
io.github.mesmerprism.rustyquest.spatial_camera_panel/.SpatialCameraPanelActivity
```

This package is a Quest platform adapter for Spatial SDK panel behavior. It
uses `AppSystemActivity`, `VRFeature`, and `ComposeFeature` to register and
spawn one Compose-backed 2D panel, then exposes low-rate controls for
participant setup, direct BLE Polar H10 intake, ECG event mirroring, surface
target selection, block timing, questionnaire submission, raw Camera2/HWB
projection probes, and public blur/projection receipts. The panel placement
controls are there to test Spatial SDK position, scale, and resolution options
on headset; they are not a renderer contract and are not the native Quest XR
path.
The Spatial SDK dependency is treated as a carrier substrate, not as camera,
particle, or experiment authority. `SpatialSdkLaneBoundary.kt` records that
layer/panel placement, camera projection, surface particles, experiment panel,
and debug probes are separate route owners, while static checks reject direct
camera/particle cross-ownership in the split native modules.
`SpatialCameraLatencyDiagnosticModule` is an app-local diagnostic control-plane
owner. It polls only the revision property during normal placement updates,
parses the complete property transaction after a revision change, routes the
current-viewer/frozen-world placement comparison, and submits a bounded JNI
settings packet. When the opt-in raw-layer rotation diagnostic is active, it
also supplies a bounded timestamped viewer-basis history; native camera
callbacks select an exact, interpolated bracket, or explicitly labeled
fallback basis using either callback time minus the operator's assumed capture
age or a directly observed sensor timestamp whose callback delta is plausible
and bounded. The raw shader applies a rotation-only ray warp and can transpose
it for a direction-control A/B. Direct use of an `UNKNOWN` Camera2 timebase
remains an empirical headset diagnostic rather than a portable clock contract.
For presentation-time experiments, the Spatial SDK remains the sole OpenXR
frame-loop owner. The sidecar can use latest Scene pose, bounded Scene
extrapolation, or `xrLocateViews` at an explicitly estimated target lead through
borrowed SDK OpenXR handles; it never calls wait/begin/end frame and never calls
the target compositor-predicted time. Rust remains the
data-plane owner for camera acquisition, AHardwareBuffer import, strict/mono
stereo A/B policies, cadence aggregation, Vulkan WSI, and shader reprojection.
The best-current calibrated mode keeps the relative headset rotation in the
viewer/gyroscope basis, then conjugates it by the Camera2 static lens-pose
rotation before sampling camera-space rays. Static intrinsic focal lengths and
principal point replace the diagnostic symmetric-FOV assumption. Calibration
is stored and applied independently for the left and right cameras. Two
eye-specific draw calls keep each push block at 96 bytes, below the portable
128-byte Vulkan push-constant floor. The output target rectangle remains fixed
within an unchanged full-surface scissor. A live-safe central source crop can reserve 0-20 percent of real
camera pixels at each edge for rotation reprojection; invalid warped UVs are
discarded to the underlying carrier only after that real margin is exhausted,
rather than clamped or replaced with an unwarped stale sample.
The environment-depth adapter keeps depth acquisition and texture ownership in
native code. A coherent frame snapshot carries both D16 array layers, near/far,
capture/display times, and two depth plus two render FOV/pose records into the
public projection stack. The renderer is the effective-state authority: it
selects the source view from the live stereo/mono policy, derives a bounded
FOV/orientation affine, composes low-rate panel residuals, and reports whether
metadata was applied or fell back to identity. Pose translation is observable
but not applied without a depth-aware reprojection contract. Spatial SDK still
owns the OpenXR frame loop, so the exported-depth sidecar is explicitly marked
call-order nonconformant until an SDK frame hook or texture export closes that
lifecycle boundary.
Present mode, swapchain image count, and Camera2 AE FPS request are captured at
route creation and are reported as pending restart when changed live.
The world-space hand billboard flock uses that substrate as a public carrier
example. Its high-density `batched-scene-mesh` mode keeps public drift state in
Kotlin arrays but renders particles through two dynamic `TriangleMesh` scene
objects, avoiding per-particle ECS component writes. The retained
`ecs-entities` carrier remains a comparison path. Neither carrier contains
downstream coupling kernels, tuned profiles, private replay payloads, or study
semantics.

OpenXR joint diagnostics in the Spatial lane keep coordinate mappings
profiled and reversible. `mirror-x-origin-registration` is the headset-accepted
Spatial hand-lab mapping; `viewer-world-basis-registration` remains the clean
rollback profile. A registration captured from an all-zero startup viewer pose
is provisional and is recaptured once a live Spatial viewer origin arrives.
Every profile emits its mapping token and determinant and retains fixture plus
live-headset acceptance evidence.

The lane deliberately stays outside `apps/native-renderer-android`. It does
not link the Rust native renderer, does not request camera or hand-tracking
features, and does not move hand mesh frames, particle arrays, field buffers,
or replay sequences through Java/Kotlin JSON. Questionnaire output remains a
low-rate app-private JSONL artifact keyed by `participant_id`, `session_id`,
`block_index`, `block_number`, `condition_id`, `profile_id`, and
`surface_target_id` so downstream analysis can join it back to private study
state without making Rusty Quest the effect authority. Polar stream rows remain
low-rate panel records: the Spatial app may scan/connect to Polar H10, decode
HR/RR, ACC, ECG, and device-status events, and mirror ECG rows to
`ecg_events.jsonl`, but those samples do not enter the native Vulkan renderer,
particle buffers, or shader parameter path.

When a Spatial block starts, the panel maps condition metadata to bounded
generic driver values (`driver0` and `driver1`) and submits them through the
existing native surface-particle JNI parameter bridge. That is the only
block-start runtime handoff: native Vulkan/OpenXR still owns validation,
resident public particle buffers, compute dispatch, and presentation, and the
workflow panel returns to particle view after the request. Private downstream
visual semantics, coupling kernels, and tuned parameters stay out of Rusty
Quest.

## Native Renderer Android Package

`apps/native-renderer-android` owns the first Android package scaffold for the
native renderer lane:

```text
io.github.mesmerprism.rustyquest.native_renderer/android.app.NativeActivity
```

This package is the primary public native Quest XR stack. It is the place to
add low-level Quest examples that need direct Rust/OpenXR/Vulkan control,
custom Camera2/HWB projection, native Meta passthrough composition, solid
background projection, resident GPU hand meshes, public blur processing, or
public SDF/hand-resource hooks. New examples should stay inside this route
unless they specifically need a Makepad UI shell or a Manifold-controlled
operator app.

The package is a Quest platform adapter. It consumes the validated public
native-renderer plan fixture as an APK asset, requests Android/headset/spatial
camera permissions, launches the immersive renderer through Android framework
`NativeActivity`, and keeps immersive render logic in the Rust native library.
The package also exposes a same-APK 2D `ControlPanelActivity` as a plain
Android panel. That panel is not a renderer, not a Spatial SDK host, and not a
Makepad shell; it is a low-rate requester that stages
`rusty.quest.stimulus_volume.profile.v1` candidates in app-private storage. On
startup, the Rust `NativeActivity` reads `stimulus_volume_candidate.json`,
validates safety and range constraints, applies accepted candidates as
effective startup settings for the stimulus-volume route, and writes
`rusty.quest.stimulus_volume.apply_status.v1` status. Live editor work should
reuse that candidate/status contract through a same-process command queue
rather than polling files from the GPU hot path.
The same panel owns the hidden display-composite capture request action for
MediaProjection diagnostics. Android remains the authority for
`createScreenCaptureIntent` result data, while the non-exported foreground
`mediaProjection` service only adapts approved tokens into a `VirtualDisplay`
that writes into a Rust-created `Surface`. Rust owns the NDK `AImageReader`,
native `AImage` acquisition, and `AHardwareBuffer` descriptor evidence. This
route is explicitly `display_composite` media evidence: it must not be treated
as raw camera, passthrough texture, environment-depth, or geometry truth, and
high-rate JSON frame payloads are out of contract.
`ahardware_buffer_vulkan.rs` owns the reusable Vulkan import mechanics for
Android `AHardwareBuffer` images: property query, external-memory image
creation, memory binding, image-view creation, layout transition, and retained
buffer lifetime. Camera2 keeps YCbCr conversion and descriptor policy above
that helper; display-composite RGBA sampling should adopt the same helper
without making Camera2 the owner of MediaProjection buffers.
Fullscreen stereo video projection is a separate input family. The Android
adapter owns only low-rate `MediaExtractor`/`MediaCodec` control and writes
decoded frames into a Rust-created `AImageReader` `Surface`. Rust owns native
`AImage` acquisition, `AHardwareBuffer` descriptors, side-by-side left/right
source UV metadata, and Vulkan import/sampling through the reusable AHB helper.
The source video path, stereo layout, decoder dimensions, queue depth, frame
cap, looping flag, target, and opacity are profile-owned low-rate settings.
`tools/Stage-NativeRendererVideo.ps1` is the device-facing adapter that stages
user-provided MP4 files into the package-scoped external files tree with
serial-scoped ADB. It emits the compact absolute `video_projection_path` for
the runtime property override, avoids `run-as` so release-style APKs work, and
does not add broad shared-storage authority to the app.
Decoded frames must not become high-rate JSON, Java `HardwareBuffer` bridge
payloads, or CPU pixel-copy surfaces. The fullscreen video route is a
background video input stream for the custom projection path; future camera,
private, or diagnostic overlays should compose above it instead of coupling the
video decoder to Camera2 or display-composite ownership.
The `video-border-blend` camera processing layer follows that boundary: the
video projection renderer draws the prepared stereo frame first, while the
guide/camera projection pass owns the target-edge transition. `alpha-over`
keeps the fixed-function guide alpha baseline. Other public modes use a
guide/video shader composite that samples the Camera2 guide texture and the
prepared stereo video texture together inside the inner band. Camera2 remains
the guide source; MediaCodec remains the video source; the final OpenXR/Vulkan
frame loop is the only composition authority between them.
The Rust code opens outside camera ids `50` and `51` through NDK
`ACameraManager`, acquires `PRIVATE` GPU-sampled `AHardwareBuffer` frames,
initializes the Android OpenXR loader, probes the
OpenXR-selected Vulkan instance/device prerequisites, creates a native
OpenXR/Vulkan session and stereo swapchain, submits a diagnostic projection
layer with the public recorded-hand replay overlay visible, stages an optional
local full recorded bind mesh into a native Vulkan storage-buffer boundary, and
emits native timing counters plus Vulkan external-HWB import boundary metadata
under `RUSTY_QUEST_NATIVE_RENDERER`. Direct-HWB camera diagnostics expose
runtime-selectable YCbCr conversion and swapchain-format preferences:
`camera.ycbcr.mode=android-suggested` uses Android/Vulkan's suggested
model/range, while `camera.ycbcr.mode=forced-bt601-narrow` forces the
effective Vulkan sampler conversion to limited BT.601 for comparison against
the Makepad pure-HWB reference lane. The current raw-quality baseline combines
Android-suggested YCbCr with `swapchain.color_format=unorm`; BT.601/UNORM
remains a comparison route, not the default. The public camera-quality profile
knob is support-gated through Camera2 metadata: `direct-baseline` applies no
request overrides, while `direct-low-noise-30` requests 30 FPS AE, high-quality
noise reduction with a fast fallback, and edge enhancement off where supported.
`direct-low-latency-60` requests 60 FPS AE with fast noise reduction,
`camera.resolution=1280x960` selects a support-gated alternate reader size, and
`camera.sync_mode=hold-image-until-gpu-fence` retains sampled `AImage` objects
until the submitted Vulkan frame-slot fence retires. Runtime markers distinguish
`cameraSyncRequested` from `cameraSyncActive`; `early-delete-ahb-retained`
remains the default baseline, hold-sync is an active diagnostic, and the
lower-latency `AImage_deleteAsync`/sync-fd mode is an active async ImageReader
diagnostic with Vulkan external-semaphore ownership still marked pending. The Vulkan
import path logs external-format feature bits and selects YCbCr chroma/sampler
filters from the advertised features. Camera import and stereo-descriptor cache
eviction is allowed only for resources that are not protected by the frame being
prepared and not referenced by submitted frame slots; if all cached imports are
in flight, eviction is deferred and logged instead of destroying live Vulkan
resources. Capture result metadata is retained only as a bounded per-eye recent
snapshot ring so acquired HWB frames and timing scorecards can report nearest
Camera2 result correlation without introducing a high-rate telemetry store.

This package is not Manifold command authority, not an Optics visual truth
source, and not a Matter SDF owner. Headset smokes now prove
`openxrSubmitReady=true` for the native diagnostic projection layer. Recorded
replay scorecards now report `recordedHandReplayVisible=true`,
`animatedHandMeshVisualReady=true` for local full-capture builds,
`cpuSdfPerFrame=false`, and
`sdfComputePath=native-vulkan-compute-recorded-skinned-mesh-sdf-field` for the
opt-in target SDF field. That path parses recorded rig blend indices/weights,
compact joint frames, tip lengths, and component-ranked triangle indices; keeps
source mesh, bind-pose, and bind-joint-source buffers resident; uploads only
runtime joint poses plus packed tip-length rows per frame; runs a GPU skinning
pass into a resident skinned-position buffer; then draws the animated hand
overlay and optionally builds the SDF field from that GPU-owned mesh. The same
compact input ABI is now available for live OpenXR hand tracking: when
`XR_EXT_hand_tracking` is enabled and a hand is active, the runtime packs live
Meta hand joints into the recorded-compatible 21-pose plus tip-length frame
shape and feeds the same resident GPU skinning/SDF buffers. The bind mesh
storage buffer, resident skinned-position visual overlay, compact input source,
and skinned-mesh SDF field remain separate source/visual/input/field
boundaries. The resident skinned-position buffer is now OpenXR reference-space
meter data; live hand visuals project that buffer through each eye's OpenXR
pose/FOV, while the current SDF visual still projects the same world-space mesh
into a metadata-target grid. The paired live visual route treats right-hand
topology as a separate source, matching the browser preview that used `mesh1`
for the right hand. The primary path consumes the left replay summary; the
secondary path consumes the right replay summary for GPU skinning, SDF resource
allocation, and triangle drawing when a full local capture is embedded. Runtime
markers expose source handedness separately from visual hand labels. The overlay
keeps the same connected-component ranking as the browser preview, rank `0`
hand-inside, rank `1` hand-back, and rank `2` wrist cap, but the visible hand
material is now a continuous single surface color rather than component-colored
chunks. A separate opt-in graft-copy setting instances the already-skinned
source mesh onto the opposite hand's reconstructed fingertip anchors when both
live hands are visible; it does not rerun skinning or upload expanded mesh
vertices. The native passthrough graft-only runtime profile is a distinct
route: it uses `XR_FB_passthrough` plus an alpha-blended projection layer for
only those graft instances, skips Camera2/custom stereo projection, and keeps
the SDF visual disabled. A second native passthrough profile enables
`debug.rustyquest.native_renderer.hand_mesh.real_hands.visible=true`, so the
same GPU-skinned resident live hand meshes draw under the graft instances while
Camera2/custom stereo projection and SDF remain disabled. The solid black
hands-and-grafts profile uses no `XR_FB_passthrough` layer at all: it clears the
submitted projection layer to opaque black and draws only the live base hand
meshes plus graft instances. The solid black OpenXR-hands anchor-particles
profile keeps the same opaque black background but disables the app's custom
hand mesh and graft visuals; the resident skinned mesh remains GPU-owned as the
anchor source, and only particle billboards are drawn while the runtime/default
OpenXR hand visual is requested for topology comparison.
`targetSpaceMeshToSdfKernelAvailable=true` means the
opt-in target-space SDF visual route is compiled into the renderer;
`meshToSdfKernel=true` appears only for frames where that opt-in GPU kernel
actually ran. Cached field reuse is
reported separately through `sdfFieldReused=true`, `sdfFieldCacheHits`, and
`sdfUpdateCadenceFrames`. `fullSkinnedMeshSdfReady=true` is scoped to a valid
resident field in this native renderer; live headset visual acceptance and
Matter/Lattice SDF parity remain later gates. The public blur guide path now
has a native low-resolution renderer: imported camera descriptors feed per-eye
384x384 guide downsample images, horizontal and vertical 5-tap blur passes
produce the guide texture, and the final projection path samples that guide
texture when `guideGraphReady=true`. This does not make camera projection parity or
Vulkan external-HWB import true by construction. A green prerequisite probe may make
`openxrInstanceReady` and `vulkanExternalImportPrereqsReady` true, while
`vulkanExternalImportReady=false` continues to separate native camera frame
acquisition from Vulkan image/cache ownership and color-correct projection
acceptance. Camera projection resource markers report suggested and effective
YCbCr model/range, component mapping, chroma offsets, conversion mode, and the
selected OpenXR swapchain color-format preference so color acceptance can be
reviewed from device evidence rather than inferred from route readiness.

Native renderer Android property names belong to `native_renderer_properties`;
shared string, boolean, integer, and float parsing belongs to
`native_renderer_property_values`; camera/output option parsing belongs to
`native_renderer_camera_options`; environment-depth settings parsing belongs to
`native_renderer_environment_depth_options`; hand-anchor particle settings
parsing belongs to `native_renderer_hand_anchor_particle_options`;
projection-border and peripheral-stretch settings parsing belongs to
`native_renderer_projection_border_stretch_options`;
native Meta passthrough compositor style parsing belongs to
`native_renderer_passthrough_style_options`, with the raw
`xrPassthroughLayerSetStyleFB` bridge kept in `openxr_passthrough_style`.
The first audio-reactive parity slice also belongs to that style owner: it is
profile-configured, oscillator-backed, and updates the effective mono-to-RGBA
color-map phase plus edge tint at a bounded rate while leaving future
microphone capture as a source adapter;
stimulus-volume settings parsing belongs to
`native_renderer_stimulus_volume_options`; and fullscreen stereo video input
settings parsing belongs to `native_renderer_video_projection_options`.
Same-APK 2D panel candidate parsing,
status writing, and startup-effective stimulus override logic belongs to
`native_renderer_stimulus_panel`; it adapts the panel schema into the existing
stimulus-volume settings owner without making Java UI code runtime authority.
Render-route, compact hand source,
hand-visual diagnostic, and private-layer settings parsing belongs to
`native_renderer_visual_options`. The `native_renderer_options` module
remains the aggregate facade consumed by the OpenXR/Vulkan frame loop so
Android property transport, replay/live fallback semantics, environment-depth
defaults, camera/output defaults, hand-anchor particle defaults,
projection-border/peripheral-stretch defaults, visual proof defaults, and
stimulus safety defaults remain testable without a headset. The broad aggregate
parser regression suite lives in `native_renderer_options_tests` so the facade
does not also own the test-family source. The typed low-rate property manifest
at `fixtures/native-renderer/native-renderer-property-manifest.json` is the
inspection surface for Android property names, value kinds, accepted profile
tokens/ranges, parser owners, startup-effective lifecycle, profile-owned
explicit-set clear behavior, runtime-owner default behavior, and expected
low-rate validators; the parity checker fails when runtime constants, profile
fixtures, manifest authority metadata, manifest validator metadata, or
specialized cross-field validator surfaces drift away from it. The manifest
records where defaults are owned rather than copying default values out of the
runtime parser modules. The runtime profile apply tool also consumes the
manifest for all native renderer set operations before any ADB write, and the
`rusty-quest-profile` Rust validator consumes it before dry-run write-plan
generation. Every manifest entry names those low-rate validators;
family-specific validators remain responsible for cross-field rules such as
near/far ordering and stimulus safety acknowledgement. The Android scaffold
validation delegates manifest schema and
parity-tool wiring assertions to
`tools/checks/Test-NativeRendererPropertyManifestStatic.ps1`, keeping that
settings-authority gate out of the broader source-token ledger. The
Android manifest, Rust NativeActivity, input pump, Cargo manifest, build
script, and app README static checks live in
`tools/checks/Test-NativeRendererAndroidScaffoldStatic.ps1`, so package/app
scaffold assertions are not mixed with executable runtime-evidence checks. The
native-renderer source/build public boundary scan lives in
`tools/checks/Test-NativeRendererPublicBoundaryStatic.ps1`, so legacy route and
private visual-token checks are not mixed into renderer-family feature checks.
Environment-depth source, profile, fixture, and smoke-wrapper static checks
live in `tools/checks/Test-NativeRendererEnvironmentDepthStatic.ps1`. General
runtime-evidence checker, replay-smoke wrapper, and permission-pregrant static
checks live in `tools/checks/Test-NativeRendererRuntimeEvidenceStatic.ps1`.
Runtime-profile apply-tool serial scoping and Rust validator manifest-hook
assertions live in
`tools/checks/Test-NativeRendererRuntimeProfileStatic.ps1`. Stimulus-volume
renderer, shader, OpenXR action, timing, and route-marker static checks live in
`tools/checks/Test-NativeRendererStimulusVolumeStatic.ps1`. Breathing Room
projection-target route static checks, including Manifold breath/pose
transport and right-hand OpenXR input/haptic markers, live in
`tools/checks/Test-NativeRendererProjectionTargetStatic.ps1`. Recorded-hand
replay, live compact hand input, GPU-skinned hand mesh visual, graft-copy, and
GPU mesh replay boundary static checks live in
`tools/checks/Test-NativeRendererHandVisualStatic.ps1`. Target-space GPU SDF
field, tile-bin, overlay shader, compact-joint upload, cadence/cache, and SDF
marker static checks live in `tools/checks/Test-NativeRendererGpuSdfStatic.ps1`.
Camera projection metadata, guide blur/projection, direct-HWB camera quality
diagnostic, peripheral-stretch, source-route profile snippet, and native camera
scaffold static checks live in
`tools/checks/Test-NativeRendererCameraGuideStatic.ps1`. Fullscreen stereo
video projection settings, Java `MediaCodec` control, Rust-owned
`AImageReader` stream creation, video metadata, Vulkan import/sampling,
profile fixture, staging wrapper, shader compilation, and no-CPU-copy guard
checks live in
`tools/checks/Test-NativeRendererVideoProjectionStatic.ps1`. OpenXR/Vulkan
prerequisite, timing marker, private-slot, render-mode, scorecard, and native
timing counter static checks live in
`tools/checks/Test-NativeRendererOpenXrVulkanStatic.ps1`, leaving the main
Android harness to run executable runtime-evidence logcat gates. The full
native-renderer profile and damaged-profile inventories are owned by
`tools/Test-NativeRendererProfileMatrix.ps1`, which dry-runs every valid
profile and rejects every damaged fixture through the manifest-backed runtime
profile tool.

The OpenXR/Vulkan integration file keeps session setup, frame submission, and
projection composition authority in `xr_vulkan.rs`. Marker scorecard emission
is split into the child module `xr_vulkan/scorecard.rs`, keeping timing and
visual-acceptance evidence formatting out of the frame-loop authority while
still allowing the child module to use the frame-loop's private runtime stats.
Replay/live visual evidence rectangle math lives in
`xr_vulkan/replay_visual_stats.rs`, so marker-field projection and UV evidence
helpers are not another responsibility of the frame-loop integration file.
The environment-depth particle Vulkan resource and command recording facade
remains `gpu_environment_depth_particles.rs`; readback statistics, marker
policy strings, surface-support depth flags, normal-source/counter markers,
and depth-grid sizing live in
`gpu_environment_depth_particle_stats.rs` so resource lifetime code does not
also own the low-rate evidence policy. The
`environment_depth_geometry.rs` helper owns reference-space
projection/reprojection math for coordinate-semantics tests and low-rate
provider pose-delta evidence. The source-only
`environment_depth_scene_map.rs` mirror owns host-testable spatial hash,
probe, merge, stale-replace, free-space retire, source-layer agreement, and
layer-offset separation policy. The source-only
`environment_depth_surface_support.rs` mirror owns host-testable
depth-neighborhood and retained-cell normal/coherence policy for synthetic
planes, holes, and depth steps, pose-shifted retained scene-cell samples, plus
compact surface descriptor fixtures for future GPU support buffers; the
Android runtime remains GPU-owned.
The scene-map and surface-support CPU mirrors are compiled only for host/test
builds. In the headset app, the CPU role is limited to profile/property
loading, permission and provider setup, Vulkan/OpenXR resource orchestration,
command submission, low-rate pose/timing calculations, and aggregate
marker/readback evidence; it must not expand Meta depth images into particle
rows or become the scene-map authority. The live depth-to-reference-space
projection, retained-cell hashing, support counters, normal counters, particle
buffers, and draw path stay in the native GPU stack.

Only the blur guide path, public recorded-hand replay visual, resident
compact-joint GPU-skinned triangle overlay, native GPU mesh boundary, and
opt-in recorded compact-joint GPU skinned-mesh SDF path are public in this
package.
The generic private particle slot is public substrate only: Rusty Quest owns
build-time discovery, placeholder behavior, static payload staging, the
four-vec4 billboard row ABI, sampled R8 texture-array mask upload/sampling,
resident GPU index-remap sorting, parameterized transparency/coverage controls,
generic tracer budget/draw-capacity plumbing, generic anchor/echo row
budget plumbing, generic draw/compute
orchestration, captured world-anchor center/scale and forward-axis state, a
24-word host-visible diagnostic storage buffer at descriptor binding `9`, and
public slot markers. The private-particle compute push keeps the 128-byte ABI:
draw passes receive real FOV tangents, while compute passes receive the
captured anchor forward axis in the same vector for downstream shaders that
need startup/recenter-stable orientation. The diagnostic buffer is generic: private
compute shaders may write compact integer counters or fixed-point reductions,
while Rusty Quest only clears it, reads it after the frame-slot fence, and emits
`privateParticleDiagnostic*` markers, including optional tracer active,
spawned, discarded, anchor/echo active/spawned/discarded, saturation,
active-edge, and pass-health fields when the private shader writes them. Public
markers distinguish main particle count, tracer budget, anchor/echo budget,
merged draw count, and compact diagnostic status so downstream shaders can
append effect-owned tracer or anchor/echo rows without introducing CPU-expanded
particle lists or full particle-buffer readback.
Main particles keep two generic state rows for phase-like ping-pong use;
tracer slots allocate four state rows so downstream shaders can preserve a
frozen billboard snapshot separately from age/fade updates. Anchor/echo slots
also allocate four state rows for downstream effect-owned lifetime bookkeeping.
The slot owns only generic runtime-polled scalar adoption for
`debug.rustyquest.native_renderer.private_particles.*`: world-anchor scale,
visual scale, tracer draw slots/lifetime/cadence, transparency
opacity/alpha/depth/RGB coupling, and the generic color facing-attenuation
strength, plus bounded generic driver scalars in the `driver0.value01` through
`driver7.value01` bank.
The opt-in `particles.private.manifold_scalar_driver` feature adds a public
Manifold stream-to-driver adapter. It subscribes to configured Manifold scalar
stream ids, parses bounded `value01` samples, clamps them to `0..=1`, and
overlays them onto selected generic driver slots. Routes use
`stream_id:driverN.value01` entries separated by semicolons. This adapter owns
no downstream effect semantics, coupling kernels, tuned parameters, or private
payload content; it only bridges honest Manifold scalar streams into the
generic driver bank.
The runtime reports accepted values through `privateParticleSettingsHotload`
markers. This does not make Rusty Quest the authority for downstream phase
dynamics, payload constants, or effect-specific visual interpretation.
Downstream repos own effect-specific compute shader semantics, payload
contents, marker prefix, opaque effect marker fields, screenshots, and proof
profile bodies.
Private visual layer implementations remain downstream extension-slot or
private-particle payloads and are not part of the public source, fixture, or
APK build manifest.

## Manifold Broker Android Package

The Quest lane owns the Android package identity for the on-device Manifold
broker adapter:

```text
io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity
```

Manifold remains the command/session/stream authority. The Android app is a
platform adapter that exposes `/manifold/v1/events` and acknowledges
`rusty.manifold.command.envelope.v1` requests. It deliberately avoids
synthesizing live stream events; live Polar, controller, and Makepad streams
must come from their own providers.

The package has no hand-maintained manifest authority. Its build requires an
exact Manifold product spec and accepted lock, then generates the actual
manifest, command registry, feature constants, and lock-stamped receipt through
`rusty-quest-broker-product`. Generic media-session selection is camera-free;
camera permission requests and the camera foreground-service type are enabled
only by generated product constants. Direct-P2P and BLE remain dedicated
provider lanes. The former broad camera/P2P validation package is retained only
as an explicitly selected legacy compatibility product.

The build also generates one exact `runtime_config.v1` containing the accepted
lock, adapter binding, initial leases, and signature-derived admission grants.
Media products additionally require exact canonical Manifold-descriptor and
Quest-runtime bindings. Runtime Host acceptance prepares a seven-owner media
action with `platform_effect_completed=false`; only an exact receiver-first or
cleanup-last completion applied by Rust advances the Quest media lifecycle.
The generic Java path never enters `RemoteCameraSessionRuntime`. See
`MEDIA_SESSION_RUNTIME.md`.
That config includes exact packaged product-spec, accepted-lock, and client-lock
JSON plus their hashes; grants are the product/client capability intersection,
not a union. A separately generated canonical config digest is checked by Rust
at initialization. Base and camera-free products therefore cannot inherit
camera, peer, or unselected media/sink authority.
`QuestBrokerRuntimeProvider` retains that config and one
`ManifoldBrokerRuntime` for the life of the process. Binder `authorize_use`
creates a one-use permit bound to its opaque token, the caller's
package/signature-derived client, exact command capability, resulting admission
revision for that use, expiry, and provider
epoch. The standalone WebSocket server and embedded server pass the full
mutation into JNI; Rust consumes the permit and calls the single Runtime Host
review/apply path before Java may execute a named platform effect.
Typed platform parameters are canonicalized and digest-bound through both host
receipts; Java consumes the exact Rust response payload, never the request body.

The embedded Native Renderer does not accept authority config from runtime
settings. Its build generates the camera-product/native-client closure and
config digest, while its Java lifecycle derives the installed package and sole
APK signer from Android before asking Rust to issue and authorize each bounded
command use. Caller-supplied epoch, token, revision, and requester fields are
replaced by that authenticated lifecycle.

Same-process service/activity rebinds must present the same config fingerprint
and preserve both authority revisions. Process restart uses fresh
`SecureRandom` entropy, derives a new epoch in Rust, and starts from the
product-owned initial state. Old-epoch, stale, replayed, expired, cross-client,
capability-substituted, product-unselected, and unleased work fail closed.
`BrokerStartService` is non-exported; the launcher remains exported and the
admission service remains exported only behind the signature permission.

The package also owns the Quest-side broker dispatch for the benign Hostess
Makepad safe probe command `hostess.makepad.bridge_probe.set_marker`. Accepted
commands are published as `rusty.hostess.bridge_command.request.v1` payloads on
`stream.hostess.makepad.bridge_command`, and the command ACK reports
`runtime_receipt_required=true` plus the expected
`stream.hostess.makepad.bridge_command.receipt` receipt stream. The broker ACK
is command authority only; Hostess Makepad must still publish a runtime receipt
before a Windows companion or other frontend can claim `runtime_accepted` or
`applied`.

The same package contains the first remote-camera runtime adapter slices. It is
still an adapter, not Manifold authority: Manifold accepts/rejects commands and
leases, while the package executes local Quest behavior requested by accepted
commands. The current code can arm local receiver sockets for
`command.remote_camera.start_receiver`, bind peer transport ingress sockets,
report remote-camera status, bridge a local sender source socket to a modeled
peer route for `command.remote_camera.start_sender`, and stop those local
sockets. It reads low-rate endpoint properties generated from validated Rusty
Quest profiles, such as receiver ports, transport receive ports, sender source
kind, sender media profiles, sender source ports, camera hints, permission
policy, and outgoing transport routes. The sender-source adapter can leave an
external H.264 socket as the source, bind a diagnostic synthetic MediaCodec
surface source, or open a Camera2 capture session into a MediaCodec encoder
when Android camera permission is available. Quest stereo Camera2 publishing is
bound by `sender_camera_ids`: outside left eye camera `50` and outside right eye
camera `51`. It does not implement Android phone adapter execution, relay/TLS
handshakes, Makepad texture adoption, or Manifold routing authority.

## Remote Camera Session Contracts

`crates/rusty-quest-remote-camera` defines
`rusty.quest.remote_camera_session.v1` plans for the first remote camera
streaming topologies:

- Quest-to-Quest two-way stereo H.264 streaming;
- Quest-to-Android phone duplex streaming, with Quest stereo lanes and an
  Android phone mono lane.

The crate validates endpoint roles, receiver-first startup, H.264 lane shape,
binary high-rate payload planes, bounded queues, local runtime endpoint
bindings, sender source kind and camera-permission policy, the Quest stereo
outside eye camera map, peer transport routes, privacy tiers, and operator
safety requirements. Manifold owns live command/session authority, native
OpenXR/Vulkan or Meta Spatial SDK adapters own render adoption for their
explicitly enabled app, and settings JSON remains a low-rate control plane.
Legacy Makepad projection remains an explicit compatibility lane only.

The optional packed stereo layout adds a stricter contract without changing
the default two-lane layout. Both Camera2 surfaces are correlated to capture
results and paired by bounded nearest `SENSOR_TIMESTAMP`; a unique accepted
pair is GPU-composited left-then-right into one MediaCodec input surface. The
wire path carries one H.264 lane with an RMANVID v4 pair extension, and the
receiver requires one hardware decoder plus one native `AImageReader`. Native
OpenXR imports the packed `AHardwareBuffer` once and projects its two UV halves
as logical eye views with the same source-pair identity. Stale-eye reuse,
unpaired encoded packets, CPU pixel copies, extra packed lanes, and RMANVID
layout/dimension mismatches fail closed. The Meta Spatial SDK adapter consumes
the same explicit layout and wire contract, but remains a separate opt-in app
surface and was compile/static qualified rather than included in the native
OpenXR hardware promotion.

Direct Wi-Fi does not introduce a second media stack. The remote-camera
contract separates six concerns that every transport adapter must preserve:

1. route/topology kind;
2. socket authority;
3. explicit local bind and peer endpoint;
4. low-rate control versus binary media payload plane;
5. platform execution adapter;
6. runtime and promotion evidence.

The canonical direct-Wi-Fi route is `direct_p2p_tcp` with
`socket_authority=rusty_direct_p2p_socket_authority`. Its local and peer IPv4
addresses must be concrete, supported P2P addresses on one `/24`, the local
address must match the source endpoint's transport listener, and the runtime
must report the actual P2P interface. Legacy compact route strings remain
parser compatibility only; they are not a second authority model.
The generic route/address/authority invariants are implemented once in
`rusty-quest-device-link`; the remote-camera crate adapts its unchanged route
fields into that contract and adds only lane, endpoint, and profile checks.

`apps/direct-p2p-provider-android` is the product implementation of this
authority split. `WifiP2pManager` owns temporary credentialed topology,
`AndroidNetworkBindingProvider` observes whether Android exposes a matching
public `Network`, and the Rust `cdylib` owns socket creation, explicit local
bind, bounded control exchange, and close. On current Quest builds `p2p0` may
be valid while no matching Android `Network` is public; the receipt records
`network_available=false` and handle `0`. This is not an Android binding and
does not weaken Rust socket authority. Product receipts exclude media and all
connectivity-lab identities.

Remote-camera remains the camera-specific compatibility contract. Generic
display/camera streaming uses `rusty.quest.media_stream_session.v1` as the
source-neutral language, and `build_media_stream_session_plan` maps existing
remote-camera fixtures into that contract without changing their source shape.
