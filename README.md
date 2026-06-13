# Rusty Quest

Rusty Quest is the Morphospace lane for Quest platform behavior: runtime
profiles, Android property hygiene, permissions, launch planning, and platform
validation evidence.

This repo treats ADB and Android properties as transports. They are generated
from validated profiles and produce dry-run/readback evidence rather than
becoming hand-written launch authority.

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
powershell -NoProfile -ExecutionPolicy Bypass -Command "cargo test -p rusty-quest-remote-camera"
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1
```
