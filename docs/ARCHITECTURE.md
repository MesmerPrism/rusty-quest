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
under `RUSTY_QUEST_NATIVE_RENDERER`.

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
boundaries. The overlay uses the same connected-component ranking as the
browser preview: rank `0` hand-inside, rank `1` hand-back, and rank `2` wrist
cap. `targetSpaceMeshToSdfKernelAvailable=true` means the opt-in target-space
route is compiled into the renderer; `meshToSdfKernel=true` appears only for
frames where that opt-in GPU kernel actually ran. Cached field reuse is
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
acceptance.

Runtime property parsing for replay visual proof, compact hand input source,
hand mesh diagnostic settings, and SDF cadence belongs to the
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
