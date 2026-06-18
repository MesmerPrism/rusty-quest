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
- remote camera session plans, device-kind declarations, media-lane safety
  policy, low-rate runtime endpoint bindings, peer transport routes, and
  platform validation gates for Quest and Android phone endpoints.
- native OpenXR/Vulkan renderer plan contracts, pure-HWB import evidence,
  public/private layer ABI boundaries, and timing scorecards for Quest-native
  rendering examples.

## Non-Ownership

- Makepad widget or shell implementation;
- Matter mesh, SDF/ADF, collision, or particle truth;
- Optics view/projection/appearance truth;
- Manifold command/session authority;
- Lattice reference-space or tracked-pose authority.
- Makepad-side media projection/adoption, app widgets, or H.264 texture
  import.
- high-rate frame payload transport through Rusty Quest core contracts.
- Matter SDF truth, Optics projection semantics, or private downstream layer
  implementation payloads for native renderer extension slots.

ADB writes are generated operations from validated profiles. They are not
hand-authored settings authority.

## Native Quest Renderer Contracts

`crates/rusty-quest-native-renderer` owns
`rusty.quest.native_renderer_plan.v1` and
`rusty.quest.native_renderer_timing_scorecard.v1`. These contracts describe the
clean native path for Camera2 AHardwareBuffer import, Vulkan external-image
descriptor shape, low-resolution guide blur, optional Matter SDF inputs,
private layer ABI slots, and per-stage timing evidence.

The crate does not link Android, OpenXR, Vulkan, Makepad, Matter, Optics, or
Lattice runtime crates. Runtime adapters must consume the public plan and
report scorecard evidence instead of becoming hidden authority.

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
camera permissions, launches through Android framework `NativeActivity`, and
keeps app logic in the Rust native library. The Rust code opens outside camera
ids `50` and `51` through NDK `ACameraManager`, acquires `PRIVATE` GPU-sampled
`AHardwareBuffer` frames, initializes the Android OpenXR loader, probes the
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
lower-latency `AImage_deleteAsync`/sync-fd path is still future work. The Vulkan
import path logs external-format feature bits and selects YCbCr chroma/sampler
filters from the advertised features. Camera import and stereo-descriptor cache
eviction is allowed only for resources that are not protected by the frame being
prepared and not referenced by submitted frame slots; if all cached imports are
in flight, eviction is deferred and logged instead of destroying live Vulkan
resources.

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

Runtime property parsing for replay visual proof, compact hand input source,
hand mesh diagnostic settings, graft-copy enablement, and SDF cadence belongs to the
`native_renderer_options` module. The OpenXR/Vulkan frame loop consumes typed
options from that module so Android property transport, replay/live fallback
semantics, and visual proof defaults remain testable without a headset.

Only the blur guide path, public recorded-hand replay visual, resident
compact-joint GPU-skinned triangle overlay, native GPU mesh boundary, and
opt-in recorded compact-joint GPU skinned-mesh SDF path are public in this
package.
Private visual layer implementations remain downstream extension-slot payloads
and are not part of the public source, fixture, or APK build manifest.

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
safety requirements. Manifold owns live command/session authority, Quest
Makepad owns the Quest-specific Makepad app adapter and projection surface, and
settings JSON remains a low-rate control plane.
