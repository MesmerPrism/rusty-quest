# Rusty Quest Validation

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
```

The runtime profile validation path is dry-run only. It validates runtime profile
fixtures and generates a deterministic property write plan without touching a
headset or ADB server.

Remote camera session plans are also source-only validation:

```powershell
cargo test -p rusty-quest-remote-camera
```

The tests validate Quest-to-Quest and Quest-to-Android phone duplex fixtures and
reject a damaged fixture that tries to carry high-rate camera payloads through
control JSON. They also validate the low-rate runtime endpoint bindings that
name adapter kind, sender source kind, sender source ports, sender media
profiles, Quest stereo sender camera ids, camera permission policy, receiver
listen ports, peer transport ingress ports, and outgoing transport routes for
each media endpoint. Quest stereo endpoints are expected to bind outside left
eye camera id `50` and outside right eye camera id `51`.

The remote-camera profile fixture also runs through the existing dry-run
property planner:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath fixtures\runtime-profiles\quest-remote-camera-q2q-diagnostic.profile.json -DryRun -Out local-artifacts\remote-camera-property-write-plan.json
```

Runtime profile validation and `Apply-RuntimeProfile.ps1` both reject Android
property values above the on-device `setprop` byte limit. Remote-camera media
profile and direct-route properties therefore use compact strings instead of
full lane ids.

The Manifold broker Android scaffold has two validation levels:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-ManifoldBrokerAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1
```

The static test verifies package naming, `/manifold/v1/events`, Manifold
command-envelope acknowledgement support, remote-camera command lifecycle
hooks, receiver port and transport route property consumption, sender bridge
markers, sender-source runtime support for Camera2/MediaCodec and diagnostic
synthetic MediaCodec sources, the high-rate JSON payload ban, and absence of
legacy Rusty-XR tokens. The build command requires an Android SDK and JDK in
the current process and writes a debug APK plus build manifest under `target/`.
The camera-source broker APK is expected to declare `android.permission.CAMERA`,
`horizonos.permission.HEADSET_CAMERA`, and
`horizonos.permission.SPATIAL_CAMERA`; that expectation is specific to this
broker adapter and does not change the camera-free Makepad app validation lane.

## Live Quest Remote Camera Smoke

The first 2026-06-12 Quest smoke evidence is recorded in the local
developer evidence archive as `remote-camera-broker-20260612-stereo-ids`.

The run installed a locally built `rusty-manifold-broker.apk` with SHA-256
`4C5ED7DDEC5738A70DFB9B76DB5AD8609B60311B56A492B424D3F2AF1B5C2024`, granted
camera permissions, applied the diagnostic Q2Q runtime profile, and drove the
broker through `/manifold/v1/events`. The clean smoke summary reports
`receiver_armed`, `sender_transport_bridge_started`, a live status snapshot
with four active lanes and zero failed lanes, and stopped cleanly.

The captured receiver stream stats prove binary H.264 media on both local
receiver sockets for that build:

- left lane: `camera_id=50`, 1280x1280, `RMQVID01`, 1,517,104 bytes;
- right lane: `camera_id=51`, 1280x1280, `RMQVID01`, 1,512,879 bytes.

`RMQVID01` was an interim Quest-broker magic in the captured APK. Current source
emits `RMANVID1`, the repo-family Manifold stream magic consumed by the Makepad
H.264 reader. Rebuild and rerun live peer validation before treating the
Manifold-magic path as headset evidence.

The current Manifold-framing smoke evidence is recorded in the local developer
evidence archive as `remote-camera-broker-20260612-rmanvid1-smoke`.

That run installed rebuilt APK SHA-256
`44E9E907F4FC68ADD0912613760275460D2FC10D2C2798A0D8B7EC53C4A3C474`, applied
the Q2Q diagnostic runtime profile, used a command-level loopback route
override (`left:127.0.0.1:9079;right:127.0.0.1:9080`), and drove
`/manifold/v1/events` through hello, start receiver, start sender, live status,
and stop.

The compact status check reports:

- `active_count=4`, `failed_count=0`;
- two `source_streaming_camera2` source states;
- camera ids `50` and `51`;
- `high_rate_json_payload=false`;
- left receiver stream: `RMANVID1`, `camera_id=50`, 1,812,763 bytes;
- right receiver stream: `RMANVID1`, `camera_id=51`, 1,815,524 bytes.

This is direct TCP self-loop broker evidence on one Quest. It does not replace
future two-headset LAN validation, Quest-to-Android-phone validation, TLS relay
validation, or Quest Makepad projection validation.
