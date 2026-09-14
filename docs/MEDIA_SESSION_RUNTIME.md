# Generic Media Session Runtime

Generic media adoption is a closed, source-neutral product path. Manifold owns
accepted session and stream references. `rusty-quest-media-stream` owns the
Quest platform composition and receiver-first lifecycle. Android source,
processor, route, socket, codec, sink, and cleanup providers remain separately
selected owners.

## Packaged binding

A media product packages one
`rusty.manifold.media.session_product_binding.v1` and one
`rusty.quest.media_stream_runtime_product_binding.v1`. The broker rejects the
package unless both canonical SHA-256 values validate and all of these
references agree exactly:

- session id and accepted Manifold revision;
- Quest runtime-spec id;
- source, processor, route, sink, and stream ids;
- generic versus explicit remote-camera compatibility state.

The Quest spec must contain a strict sorted owner set that exactly covers all
seven owner families: source, processor, route, socket, codec, sink, and
cleanup. A generic spec rejects any remote-camera owner/provider identity.
Camera2 and display-composite bindings are generated independently under
`fixtures/media-runtime-products/`; their permission and consent authorities
do not cross.

Regenerate the committed examples deterministically:

```powershell
cargo run -p rusty-quest-broker-authority --bin export_media_product_bindings -- fixtures\media-runtime-products
```

## Prepare and apply

`command.media.session.start` or `.stop` can be accepted only by Manifold
Runtime Host. That response carries a Rust-authored platform action, sets
`platform_effect_completed=false`, and binds the live provider epoch, both
canonical product hashes, accepted decision/revision, Quest lifecycle
revision, and every owner action.

The host injects a trusted executor that runs each selected owner action and
reads the resulting handle and revision from its own registry. A private Rust
adapter supplies that readback to the generic runtime. The provider trait and
its completion receipts are not a caller-writable JSON/JNI completion API.
Rust applies the recorded results only when:

- every owner tuple/action and unique receipt id matches;
- cleanup and receivers complete before any source starts;
- every non-cleanup owner stops before terminal cleanup;
- action, epoch, hashes, revision, and operation remain current;
- the action is neither replayed nor from a restarted provider.

Only the resulting
`rusty.quest.media_stream_platform_application.v1` receipt may set
`platform_effect_completed=true`. Start advances through receivers-armed to
sources-started. Stop is terminal; implicit restart is rejected.

## Android boundary

`GenericMediaSessionPlatformAdapter` reports the prepared Rust action as
`awaiting_product_owner_completions`. Public client completion requests ask the
retained authority to progress its pending action; they cannot supply provider
readback or an executor capability. An absent executor is an explicit failure,
not a successful provider selected from the product lock.

The reusable `rusty-quest-media-stream-android` module supplies a Rust `rlib`
and an Android AAR in `crates/rusty-quest-media-stream-android/android`. The AAR
has no Activity, service, permissions, or native `.so`. Its
`AndroidMediaOwnerRegistry` binds only packaged provider selections. Each host
links the Rust library into its own single authority library and installs the
internal registry bridge; it does not create a second Manifold authority.

Execution binds the exact action, epoch, revisions, client/lease, owner and
resource, ordered step, and executor generation. Internal readback must match
the executor's live registry. The process provider is temporarily owned outside
its mutex during platform callbacks; concurrent/re-entrant access must return
a bounded busy result. Do not wait for platform callbacks on the main Looper
or while holding a Java monitor or registry/authority lock.

Partial Start failure compensates the uncertain attempt and previously
completed owners in reverse order, including late callbacks that may have
created a resource. The generic runtime has no asynchronous uncertain-Stop
recovery API: the executor must resolve Stop cleanup before returning, or retain
an explicit unresolved failure. Receipt rejection alone is not cleanup proof.

The standalone broker is a module consumer. The native-renderer adapter in this
supplier scope retains compatibility and fails closed without an installed
executor; that does not qualify another application integration.

Generic `media_session` effects do not call `RemoteCameraSessionRuntime`.
Remote-camera properties, defaults, permissions, command aliases, and runtime
state remain behind the explicit `remote_camera_compatibility` branch.

The compatibility host may delegate media mechanics to the shared AAR while
keeping those policies local. It must not compile private copies of module
classes into its source list.

## Product build API

Full media broker builds require `-MediaSessionBindingPath`. The committed
camera-free example is:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1 `
  -ProductSpecPath ..\rusty-manifold\fixtures\broker-product\media-session-standalone.json `
  -ProductLockPath ..\rusty-manifold\fixtures\broker-product\media-session-standalone.lock.json `
  -MediaSessionBindingPath .\fixtures\media-runtime-products\display-composite.binding.json
```

The media package grants the dedicated admission probe only the command
capabilities selected by the product and binds its control lease as
`lease.media.session.client.quest.authorized`. NET-016 application adoption
must consume the Rust action/completion API with the application's own exact
client lock and lease; it must not add Java acceptance rules or reuse the
broker probe identity.

## Validation

```powershell
cargo test -p rusty-quest-media-stream
cargo test -p rusty-quest-broker-authority
cargo test -p rusty-quest-manifold-broker-authority-native
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-QuestBrokerAuthorityStatic.ps1 -RepoRoot .
```

The test matrix covers display-composite and Camera2 valid/damaged bindings,
receiver-first start, stop/release/cleanup, partial owner receipts, stale hash,
replay, provider restart, remote-camera bleed, and JNI application.
