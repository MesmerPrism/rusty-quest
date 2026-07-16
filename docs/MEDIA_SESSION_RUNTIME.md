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

Platform code returns an exact
`rusty.quest.media_stream_platform_completion.v1`. Rust applies it only when:

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

`GenericMediaSessionPlatformAdapter` validates the Rust action and exposes the
exact completion application route. The broker does not own application source
or sink policy and therefore reports `awaiting_product_owner_completions`
until a selected product adapter supplies real receipts. It never synthesizes
owner completion.

Generic `media_session` effects do not call `RemoteCameraSessionRuntime`.
Remote-camera properties, defaults, permissions, command aliases, and runtime
state remain behind the explicit `remote_camera_compatibility` branch.

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
