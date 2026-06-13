# Remote Camera Streaming

Rusty Quest owns the platform contract for remote camera session plans and the
Quest-owned Android broker package adapter that can execute accepted platform
commands. The first Morphospace slices describe how Quest and Android phone
camera endpoints are allowed to pair, send H.264 video lanes, apply
backpressure, bind local runtime ports, and report evidence.

This document records the public, architecture-aligned shape of the feature. It
does not import legacy runtime code or take Manifold command/session authority.
The current broker adapter can arm local receiver sockets, bind peer transport
ingress sockets, arm a sender source socket, bridge that source socket to a
peer route, and expose status. The Quest broker now compiles a Camera2 to
MediaCodec sender-source adapter and a diagnostic synthetic MediaCodec source.
The Quest-side direct TCP broker lane has live headset evidence for the outside
left/right eye camera map. Android-phone runtime adapters, TLS relay handshakes,
and paired live-device streaming evidence remain pending.

## Lineage

This feature was derived from the public Rusty-XR Quest streaming work, not by
copying the old runtime authority model. When comparing behavior, start with
the legacy Rusty-XR docs `QUEST_TO_QUEST_ONLINE_STREAMING_ROADMAP.md`,
`QUEST_TO_QUEST_INTERNET_RELAY_MVP.md`,
`QUEST_TO_QUEST_NATIVE_RELAY_SESSION_2026_05_19.md`, and
`QUEST_Q2Q_AGENT_ONBOARDING.md`. The concrete legacy source paths were the
Quest broker H.264 sender/proxy classes, the composite-layer H.264 consumer and
Camera2 services, the `tools/video/q2q_relay.py` relay, and the companion
`Q2QRelayTransport.kt` phone adapter.

The lessons carried forward are receiver-first startup, explicit left/right
camera ids `50` and `51`, Android platform Camera2/MediaCodec H.264 before
larger media stacks, binary media-plane payloads, compact low-rate runtime
properties, and separate packet/decode/hardware-buffer/projection evidence.
The overreach rejected was making Makepad, the phone companion, or this Quest
adapter the session authority; Manifold remains the accepted command/session
authority.

For chronology, inspect the public Rusty-XR git history around commits
`ed0db1b` through `4514764`, then the Android companion Q2Q commits
`73844b4`, `ea201e9`, and `5f6f137`. The Morphospace port is tracked in the
private refactor iteration docs named
`remote-camera-streaming-morphospace-plan-2026-06-12.md`,
`remote-camera-streaming-morphospace-iterations-2026-06-12.md`, and
`remote-camera-streaming-legacy-lineage-2026-06-13.md`.

## Scope

The first supported topologies are:

- `quest_to_quest_two_way`: two Quest devices each publish stereo H.264 lanes
  to the other device.
- `quest_android_phone_duplex`: a Quest publishes stereo H.264 lanes to an
  Android phone while the phone publishes a mono H.264 lane back to the Quest.

The session schema is `rusty.quest.remote_camera_session.v1`. Plans live under
`fixtures/remote-camera-sessions/` and are validated by the
`rusty-quest-remote-camera` crate.

For a specific Quest endpoint, the crate can derive a
`rusty.quest.runtime_profile.v1` profile that contains only low-rate launch
state: enabled/session/topology ids, endpoint id/kind/role, lane counts,
privacy tier, transport kind, adapter kind, sender source kind, local sender
source ports, per-lane sender media profiles, camera hints, camera permission
policy, local receiver ports, peer transport ingress ports, and outgoing
transport routes.
The profile fixture
`fixtures/runtime-profiles/quest-remote-camera-q2q-diagnostic.profile.json`
proves that the remote-camera plan maps into the existing dry-run property
write path without carrying media payloads.

Quest stereo Camera2 endpoints must use the dedicated outside eye camera map:
left eye camera id `50`, right eye camera id `51`. The low-rate runtime
property for this map is
`debug.rustyquest.remote_camera.sender_camera_ids=left:50,right:51`.
`debug.rustyquest.remote_camera.sender_camera_id` stays `none` for stereo Quest
endpoints so the runtime cannot silently collapse both eyes onto one fallback
camera.

The Quest-owned `apps/manifold-broker-android` package recognizes
`command.remote_camera.start_receiver`, `command.remote_camera.start_sender`,
`command.remote_camera.get_status`, and `command.remote_camera.stop` command
envelopes. Receiver start binds local receiver sockets from the generated
`debug.rustyquest.remote_camera.receiver_ports` property so a Makepad external
H.264 player can connect through the existing binary media path, and also binds
peer transport ingress sockets from
`debug.rustyquest.remote_camera.transport_receive_ports`. Sender start reads
`debug.rustyquest.remote_camera.transport_routes` or a raw command-message
`transport_routes` override, then reads
`debug.rustyquest.remote_camera.sender_source_kind` and
`debug.rustyquest.remote_camera.sender_media_profiles` plus the optional
`debug.rustyquest.remote_camera.sender_camera_ids` map to decide whether the
local source is an external H.264 socket, a diagnostic synthetic MediaCodec
surface, or one Camera2 capture feeding a MediaCodec surface per Quest eye. If
the source is available and a route exists, sender start opens a binary TCP
bridge from the local H.264 source socket to the modeled peer ingress. If no
route is present it reports `sender_transport_pending`; if Camera2 permission
or source startup is unavailable it reports `sender_source_unavailable` without
starting a transport bridge thread.

Android `setprop` values are capped on-device, so generated direct-LAN launch
properties use compact low-rate strings. For example, sender media profiles use
`left:720x720@30:2500000;right:720x720@30:2500000`, and direct routes use
`left:quest-b.local:9079;right:quest-b.local:9080`. The broker runtime also
accepts the earlier verbose parser shape for compatibility with command-message
overrides.

## Boundaries

Rusty Quest owns:

- device-kind declarations for Quest, Android phone, and relay endpoints;
- platform media lane requirements for Camera2-style H.264 sources;
- local diagnostic versus encrypted relay privacy tiers;
- receiver-first startup, slow-peer closure, and queue bounds;
- local runtime adapter kind, sender source kind, camera permission policy, app
  receiver port bindings, and peer transport route bindings;
- operator-visible safety requirements;
- validation evidence required before runtime promotion.

Rusty Quest does not own:

- Manifold command/session authority or live stream routing;
- Makepad widgets, texture upload, projection draw, or app-shell state;
- Optics projection truth beyond declaring that stream metadata must exist;
- Matter geometry, PMD, mesh, SDF, or particle truth;
- high-rate frame payloads inside settings or control JSON.

## Media Plane

Every video lane must use the `binary-media` high-rate payload plane. JSON is
allowed for session plans and low-rate metadata only. The validator rejects
plans that try to put high-rate camera payloads in control JSON or inline JSON
frame payloads.

The first codec is H.264 because the reference work used Android MediaCodec
paths on both Quest and phone. The contract keeps this as a narrow first slice
instead of turning codec support into a generic media framework.

The current diagnostic packet stream emitted by the broker uses a compact
binary header and packet records. Stream bytes remain on TCP sockets; JSON is
limited to session plans, runtime properties, command acknowledgements, and
status evidence.

## Security

All remote camera plans require:

- a visible streaming indicator;
- explicit pairing;
- an immediate stop command;
- receiver-first startup;
- bounded queues with slow-peer close behavior.

Local LAN diagnostics may use unencrypted transport. Relay-backed or non-local
topologies must require encrypted transport on every lane.

## Validation

Run:

```powershell
cargo test -p rusty-quest-remote-camera
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath fixtures\runtime-profiles\quest-remote-camera-q2q-diagnostic.profile.json -DryRun -Out local-artifacts\remote-camera-property-write-plan.json
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
```

The broker package also has static and compile validation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-ManifoldBrokerAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1
```

Live headset evidence for the Quest-side direct TCP broker lane was first
captured on 2026-06-12 in the local developer evidence archive as
`remote-camera-broker-20260612-stereo-ids`.

The installed broker APK SHA-256 was
`4C5ED7DDEC5738A70DFB9B76DB5AD8609B60311B56A492B424D3F2AF1B5C2024`. The
`websocket-smoke-summary-stereo-ids-clean.json` run used a Manifold hello,
`command.remote_camera.start_receiver`,
`command.remote_camera.start_sender`, `command.remote_camera.get_status`, and
`command.remote_camera.stop` against `ws://127.0.0.1:8765/manifold/v1/events`.
Its live status snapshot reported four active lanes, zero failed lanes, left
camera selection `50`, right camera selection `51`, and H.264 stream metadata
on both receiver sockets. That captured build used the interim `RMQVID01`
stream magic.

The current Manifold-framing build was then captured in the local developer
evidence archive as `remote-camera-broker-20260612-rmanvid1-smoke`.

That run installed APK SHA-256
`44E9E907F4FC68ADD0912613760275460D2FC10D2C2798A0D8B7EC53C4A3C474`, applied
the same Quest stereo camera map, used a per-command loopback route override
for a one-headset direct TCP smoke, and reported `RMANVID1` H.264 stream magic
on both receiver sockets. The compact status check reported four active lanes,
zero failures, two `source_streaming_camera2` sources, left camera id `50`,
right camera id `51`, and `high_rate_json_payload=false`. The current smoke
therefore proves the Quest broker can source and bridge both Quest outside eye
cameras on one headset with the repo-family Manifold H.264 stream framing.

That evidence does not yet prove two physical Quest devices, Android-phone
runtime execution, TLS relay handshakes, headset-to-phone evidence, or Makepad
projection validation. Those belong to later Quest, Manifold, Android-phone,
and Quest Makepad slices.
