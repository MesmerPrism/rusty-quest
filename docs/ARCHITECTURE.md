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

## Non-Ownership

- Makepad widget or shell implementation;
- Matter mesh, SDF/ADF, collision, or particle truth;
- Optics view/projection/appearance truth;
- Manifold command/session authority;
- Lattice reference-space or tracked-pose authority.
- Makepad-side media projection/adoption, app widgets, or H.264 texture
  import.
- high-rate frame payload transport through Rusty Quest core contracts.

ADB writes are generated operations from validated profiles. They are not
hand-authored settings authority.

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
