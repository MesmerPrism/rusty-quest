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

`contracts/connection-hub-protocol-v1.json` is the Rusty Quest-owned canonical
wire schema/vector set for the standalone Connection Hub. The HTTP/WebSocket
server host tests validate their emitted field sets against it, and the browser
loads a checked-in projection whose tests must match the same owner bytes.
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

Browser disconnect is fail closed: local bearer/socket state is cleared only
after an exact applied revoke receipt. Network, HTTP, schema, or authority
rejection retains the credential so the wearer can retry, while an accepted
revoke closes the current socket and makes the stale bearer unusable.

The sealed authority currently caps controller trust at 366 days and logical
sessions at 30 days. Persistent sliding renewal and automatic audit-history
rollover are not emulated in Android: they require new Manifold-owned,
replay-preserving authority operations before adoption. This keeps wire v1 and
the sole authority boundary intact.
