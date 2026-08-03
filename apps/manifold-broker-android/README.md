# Rusty Manifold Broker Android

This app is the Rusty Quest-owned Android package adapter for the standalone
Manifold broker identity:

```text
io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity
```

The app source deliberately has no `AndroidManifest.xml`. Packaging requires an
explicit Manifold product spec and exact accepted lock. The Quest product
preparer validates that pair, renders the actual permission-minimal manifest,
generates a command registry and Java feature constants, and packages the
accepted lock/registry/projection as APK assets. The build receipt records the
lock id, closure fingerprint, canonical lock SHA-256, generated artifact hashes,
and selected feature set.

The camera-free generic media-session package can be prepared without an
Android toolchain:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1 `
  -ProductSpecPath ..\rusty-manifold\fixtures\broker-product\media-session-standalone.json `
  -ProductLockPath ..\rusty-manifold\fixtures\broker-product\media-session-standalone.lock.json `
  -PrepareOnly
```

Remove `-PrepareOnly`, add
`-MediaSessionBindingPath .\fixtures\media-runtime-products\display-composite.binding.json`,
and provide the documented SDK/JDK roots to build the APK. Generic
media-session selection contains no camera, P2P, or BLE
permission. Camera permission requests and the camera foreground-service type
are guarded by generated feature constants.

The old remote-camera/QCL validation surface is retained only as explicit
compatibility:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1 `
  -LegacyCameraP2pCompatibility
```

That switch selects the committed `broker.legacy_camera_p2p.standalone` spec
and lock. Direct-P2P and BLE product work should otherwise use their dedicated
provider apps instead of widening the background broker.

The package exposes `/manifold/v1/events` on local TCP port `8765` and retains
the historical remote-camera adapter source for compatibility. The build embeds
an exact runtime config over the accepted lock, adapter, initial leases, and
signature-derived grants. One process-local Rust provider preserves state
across activity/service/Binder rebinds; a process restart receives a fresh
epoch. Every WebSocket mutation must present its opaque token and consume a
current one-use admission before the Runtime Host can apply it, and Java
performs platform effects only after
that Rust-authored receipt. The start service is package-private; admission is
the only exported service and remains signature-protected.

The runtime config also contains exact product-spec, accepted-lock, and
per-client lock bytes with hashes. Generated grants are the exact product/client
intersection, and Rust verifies the canonical config digest before creating the
provider. Base builds grant no media/sink/peer capability; camera-free media
adds only its selected media/sink closure. Bound typed effect parameters are
returned by Rust and are the only values Java platform adapters consume.
Generic media command acceptance prepares an exact seven-owner action but
leaves `platform_effect_completed=false`; only an exact owner completion
applied back through Rust can report completion. Generic media never routes
through `RemoteCameraSessionRuntime`. See `docs/MEDIA_SESSION_RUNTIME.md`.

Generic media platform-effect adoption remains a separate product gate. The
Native Renderer now packages and verifies its exact embedded config, client
lock, signer-derived grant, and Android-authenticated local admission lifecycle.
Absent capabilities or leases reject rather than restoring former
unauthenticated compatibility behavior.

## Connection Hub wire authority

`contracts/connection-hub-protocol-v1.json` remains the byte-frozen legacy
compatibility vector (SHA-256
`fa00d34511b2ee5576eebdd815e58ae032e37b10c209e41289cfd876c78c9c78`).
It is still accepted, but because its frames do not carry an authority sequence
it is not claimed replay-safe across history rollover or a lost receipt.
`contracts/connection-hub-protocol-v2.json` is the current Rusty Quest-owned
canonical wire schema/vector set. It binds the legacy bytes and freezes the
ASCII canonical-JSON algorithm plus exact command and keepalive frame digests.
The HTTP/WebSocket server host tests validate emitted field sets against these
vectors, and the browser loads a checked-in v2 projection whose tests must match
the same owner bytes.
Consumers must reject missing required fields, unknown fields, schema changes,
and type changes. Downstream Hostess clients may copy or hash-bind this file,
but they do not independently redefine its wire shapes.

Provider instances are single-lifecycle identities. Explicit surface removal or
Binder death unregisters both the surface and its exact Manifold provider
instance; a later app launch consumes fresh admission and receives a fresh
instance. The Hub listener and controller session remain independent of that
provider churn.

Each accepted WebSocket is installed atomically with its Manifold transport
epoch. A late older handshake cannot displace a newer socket. Surface leases
retain their authority expiry locally, are reacquired after expiry, and receive
one bounded reacquire-and-authorize attempt when Manifold reports the cached
lease inactive. Leases are intentionally rebuilt after process restart.

Authenticated v2 commands and JSON keepalives present the exact logical
session, current transport epoch, positive next request sequence, and SHA-256 of
the exact canonical raw frame to Manifold. Accepted activity consumes one
sequence and slides controller/session deadlines; rejected activity returns the
unchanged authority-derived next sequence. Transport replacement slides the
deadlines without consuming a request sequence. The browser resynchronizes from
each authentication or activity receipt and sends a bounded five-second JSON
keepalive while connected.

The native adapter rolls audit history proactively before capacity while
retaining exact prior-epoch live controller/session/provider/surface/lease IDs.
The external request fence remains attached to the logical session, so an exact
old v2 frame is rejected after rollover and restart. A forced rollover hook and
short activity deadlines exist only in the shell-UID debug operator product;
release packaging omits the provider and disables the native hook.

Browser disconnect is fail closed: local bearer/socket state is cleared only
after an exact applied revoke receipt. Network, HTTP, schema, or authority
rejection retains the credential so the wearer can retry, while an accepted
revoke closes the current socket and makes the stale bearer unusable.

The sealed authority caps controller trust at 366 days, logical sessions at 30
days, and a surface lease at 24 hours. Manifold remains the sole authority for
renewal, transport replacement, replay fencing, expiry, revocation, and history
rollover; Android persists only its opaque authority envelope and the minimum
typed session projection required to reconnect.
