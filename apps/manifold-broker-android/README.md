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

`ConnectionHubHttpServer` is the standalone HTTP/socket placement adapter over
`crates/rusty-quest-broker-transport`. The shared core owns bounded RFC6455
upgrade/framing, per-client queues and writer isolation, liveness/close, and
cleanup; `ConnectionHubProtocol` retains JSON schema and command projection.
Binder identity, Manifold authorization, and owner-confirmed effects remain
outside transport. Validate the core and standalone/embedded parity on the host
before building or using a device.

The exported admission service derives UID/package/signer from Android and
requires one matching packaged grant. Its Messenger replies carry the client
correlation id, session generation, and broker epoch. Registration binds a
stable registration id and exact canonical-byte digest; only an identical
same-session replay returns the cached applied result. See
`../../docs/CONNECTION_HUB_BINDER_ADMISSION.md`.

Runtime-evidence replies on this Connection Hub Binder surface use
`rusty.quest.broker.runtime_evidence.transport_projection.v1`, a compatibility
projection capped at 32 KiB. It preserves the Rust authority owner, provider
epoch, Runtime Host revision, and admission revision, and binds the exact full
authority response by SHA-256 and UTF-8 byte length. It never relabels pruned
data as full `rusty.quest.broker.runtime_evidence.v1` evidence. The full audit
histories remain inside the Rust authority; Java adds no acceptance policy.

The selected normal Hub product packages
`ConnectionHubOperatorProvider` at the fixed
`io.github.mesmerprism.rustymanifold.broker.connection-hub-operator`
authority. Android restricts it with `android.permission.DUMP`, and the
provider additionally requires the shell UID. Its closed methods are `start`,
`stop`, `status`, `pair`, `revoke`, `forget`, and `pair-code`. The lifecycle and
session methods call the same `ConnectionHubOperatorController` used by wearer
actions and confirm effective state before returning. `pair-code` is the one
narrow exception: it accepts no argument or extras, requires a running listener
and an exact six-ASCII-digit process-memory wearer code, and returns only
`secret_b64` without constructing a receipt. Pairing credentials are returned
separately from secret-redacted receipts. The provider accepts no arbitrary
action, component, path, client identity, grant, or capability.

The published CLI starts the fixed exported `ConnectionHubStartService` with
the fixed START action through serial-scoped shell transport before invoking
the typed `start` method. Both components require `android.permission.DUMP`.
The provider never starts a service from its background application context;
without a ready foreground service its controller receipt terminates as
rejected and the listener remains stopped.

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
instance and a fresh authority-only surface subject. The stable surface name
on the browser protocol does not become an authority identity. The Hub listener
and controller session remain independent of that provider churn.

The packaged locked-playlist surface for Spatial Camera Panel is enrolled on
the canonical `client.quest.spatial-camera-panel` grant for the real
`io.github.mesmerprism.rustyquest.spatial_camera_panel` package. The package-
and-signer subject occurs exactly once; selecting the Hub product adds provider
registration to that grant's capability intersection. Duplicate same-subject
grants fail the build, and the app never supplies a client identity. The
surface contract permits only empty-argument Next, Previous, Pause, and Resume
commands and the scalar state keys `playlist_title`, `item_count`,
`active_index`, `active_label`, `item_elapsed_seconds`,
`item_duration_seconds`, `running`, `paused`, `phase`, `progress`, and
`revision`. The browser presents the zero-based active index as “item N of M”
and elapsed/total seconds as a clock instead of exposing raw normalized
progress. Availability is app-owned: the provider registers only while an
effective locked playlist is active and unregisters when it is not. The
compatibility contract exposes neither a typed item selector nor an ordered
item array; either addition requires a separate Hub-owner schema, descriptor,
browser, authorization, and negative-test change.

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

Connection Hub native authority is built from the standalone
`connection-hub-native` Cargo workspace and its own committed lockfile. It
shares the broker admission implementation as source inside the same resulting
native library, but its independently pinned Manifold dependency graph does not
change the repository-wide Cargo lock used by the package updater and other
Quest products. The build rejects a Manifold path that differs from the exact
clean source root validated by `native/manifold-source.lock.json`.

Browser disconnect is fail closed: local bearer/socket state is cleared only
after an exact applied revoke receipt. Network, HTTP, schema, or authority
rejection retains the credential so the wearer can retry, while an accepted
revoke closes the current socket and makes the stale bearer unusable.

The sealed authority caps controller trust at 366 days, logical sessions at 30
days, and a surface lease at 24 hours. Manifold remains the sole authority for
renewal, transport replacement, replay fencing, expiry, revocation, and history
rollover; Android persists only its opaque authority envelope and the minimum
typed session projection required to reconnect.
