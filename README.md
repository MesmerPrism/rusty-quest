# Rusty Quest

Rusty Quest is the Morphospace lane for Quest platform behavior: runtime
profiles, Android property hygiene, permissions, launch planning, and platform
validation evidence.

This repo treats ADB and Android properties as transports. They are generated
from validated profiles and produce dry-run/readback evidence rather than
becoming hand-written launch authority.

## Native Quest Rendering

`crates/rusty-quest-native-renderer` defines the first clean contract for a
pure Quest-native OpenXR/Vulkan camera renderer. It models the public AGPL
HWB import, offscreen guide blur, SDF input hook, private extension ABI slot,
and detailed timing scorecard required before building a native app scaffold.

See `docs/NATIVE_QUEST_RENDERING.md`.

`apps/native-renderer-android` is the first Quest-native Android package
scaffold for that contract. It stages the public renderer plan as an APK asset
and uses a Rust NativeActivity library, with no app Java packaged, to open NDK
`ACameraManager` camera ids `50` and `51` into GPU-sampled `AImageReader`
hardware buffers and emit `RUSTY_QUEST_NATIVE_RENDERER` timing markers. It is
not a Makepad route and it is not a legacy compatibility route. The current
scaffold has headset evidence for a real submitted OpenXR diagnostic projection
layer with the public recorded-hand replay overlay visible. The current native
camera proof imports retained Camera2 HWB frames
into Vulkan external images and renders them only inside metadata-owned per-eye
target rectangles, with source raster Y flip controlled by metadata rather than
a hard-coded shader flip. Local builds can embed the real recorded
Meta/OpenXR hand capture and stage its bind mesh into a native Vulkan storage
buffer while reporting `cpuSdfPerFrame=false`. The current render loop also
draws the real recorded hand mesh from the resident GPU-skinned position buffer
inside the metadata target rectangle, preserving the browser-discovered
component split: hand-inside, hand-back, and wrist cap. A compact-joint GPU
path now parses the real rig blend indices/weights, bind-joint sources, compact
runtime joint frames, and tip lengths; keeps source mesh and bind metadata
buffers resident; uploads only runtime poses plus tip-length rows per frame;
dispatches GPU skinning into a resident skinned-position buffer; and optionally
builds the target SDF field from that GPU-owned mesh. The same compact input
shape can now be fed by live OpenXR hand tracking when `XR_EXT_hand_tracking`
is available: live frames upload only the 21 runtime joint poses plus packed tip
lengths and then reuse the resident topology/bind/SDF graph. The SDF visual
path now also separates kernel dispatch from cached field reuse through
cadence/cache scorecard markers. The native camera path now includes a public
low-resolution guide blur graph: imported Camera2 HWB frames are downsampled to
384x384 per-eye guide textures, blurred with split horizontal/vertical 5-tap
passes, and final projection samples the guide texture when the graph is ready
instead of sampling external HWB again. Live headset visual acceptance,
Matter/Lattice SDF parity, color conformance, projection parity,
and higher-order SDF acceleration remain separate validation gates. The
2026-06-17 no-real-hands recorded replay smoke visually verified the
target-local mesh/SDF overlay in headset screenshots and passed the stage
performance budget at 90.1 FPS with zero stale frames. During the separate
2026-06-17 live-hand check the user had real hands in view, but did not see a
mesh or SDF representation in the headset, so live mesh/SDF visual acceptance
still needs an explicit hand-mesh diagnostic offset/tint retest. The replay
profile and the future live-hand diagnostic profile are separate
runtime-profile fixtures so recorded replay acceptance cannot be confused with
live visual acceptance. Replay-proof, compact hand input source, hand mesh
diagnostic, and SDF cadence properties are parsed in
`native_renderer_options` rather than inside the Vulkan frame loop.

## Remote Camera Streaming

`crates/rusty-quest-remote-camera` validates the first Morphospace remote
camera session plans for Quest-to-Quest two-way streaming and Quest-to-Android
phone duplex streaming. It is a contract crate only: high-rate camera frames
stay on a binary media plane, while session plans, safety requirements, queue
policy, local runtime endpoint bindings, peer transport routes, and
observability gates remain low-rate data.

See `docs/REMOTE_CAMERA_STREAMING.md`.

## Android Broker Package

`apps/manifold-broker-android` is the Quest-owned Android package scaffold for
the Morphospace Manifold broker identity used by Hostess:

```text
io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity
```

It exposes `/manifold/v1/events` on local TCP port `8765`, accepts
`rusty.manifold.command.envelope.v1` WebSocket command envelopes, and returns
acknowledgements. It does not synthesize live provider stream events, so Polar,
controller, and Makepad evidence still requires real providers.

For remote-camera commands, the broker package now has the first Quest-owned
runtime adapter slice. It recognizes `command.remote_camera.start_receiver`,
`command.remote_camera.start_sender`, `command.remote_camera.get_status`, and
`command.remote_camera.stop`; receiver start arms local TCP receiver sockets
from the validated `debug.rustyquest.remote_camera.receiver_ports` and
`debug.rustyquest.remote_camera.transport_receive_ports` properties, then
reports `remote_camera_runtime` status in the command ack. Sender start can now
use validated `debug.rustyquest.remote_camera.sender_source_kind`,
`debug.rustyquest.remote_camera.sender_media_profiles`, and
`debug.rustyquest.remote_camera.transport_routes` properties to arm a local
H.264 sender source and bridge it to a peer transport ingress. The broker
supports an external H.264 socket source, a diagnostic synthetic MediaCodec
surface source, and a Camera2-to-MediaCodec source gated by camera permission
evidence. Android-phone adapter execution and paired headset/phone live-stream
evidence remain later validation work.

## Validation

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
powershell -NoProfile -ExecutionPolicy Bypass -Command "cargo test -p rusty-quest-native-renderer"
powershell -NoProfile -ExecutionPolicy Bypass -Command "cargo test -p rusty-quest-remote-camera"
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeRendererAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-NativeRendererAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererReplaySmoke.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -RunSeconds 12
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1
```
