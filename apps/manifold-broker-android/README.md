# Rusty Manifold Broker Android

This app is the Rusty Quest-owned Android package surface for the Morphospace
Manifold broker identity:

```text
io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity
```

It is a platform adapter scaffold, not Manifold core authority. The app starts
a local WebSocket endpoint at `/manifold/v1/events` on TCP port `8765`,
accepts `rusty.manifold.command.envelope.v1` command envelopes, and replies
with command acknowledgements. It intentionally does not synthesize live Polar,
controller, or Makepad stream events, so live recording cannot pass without
real providers.

For remote-camera work, the package can arm local receiver sockets, bind peer
transport ingress sockets, bridge sender source sockets to modeled peer routes,
and start broker-owned sender sources. Supported sender source kinds are
`external_h264_socket`, `diagnostic_synthetic_mediacodec_surface`, and
`camera2_mediacodec_surface`. Quest stereo Camera2 publishing uses the explicit
outside eye camera map `left:50,right:51` from
`debug.rustyquest.remote_camera.sender_camera_ids`; it does not use a single
fallback `sender_camera_id`. The Camera2 path is gated by runtime camera
permission evidence and this APK intentionally declares Android, headset, and
spatial camera permissions. That camera-enabled broker profile is separate from
camera-free Makepad APK validation.

The 2026-06-12 headset smoke recorded in the local developer evidence archive
as `remote-camera-broker-20260612-stereo-ids` validated the direct TCP broker
path on one Quest: command hello, receiver
start, sender start, live status, binary H.264 bytes on both receiver lanes,
left camera id `50`, and right camera id `51`. Physical two-Quest,
Quest-to-Android-phone, relay/TLS, and Makepad projection runs are still
separate validation gates.

Build:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1
```
