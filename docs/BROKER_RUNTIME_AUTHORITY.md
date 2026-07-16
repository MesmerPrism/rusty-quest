# Broker Runtime Authority

## Decision

Standalone and embedded brokers use the same stateful Rust path:

1. the provider verifies a build-generated canonical config digest plus exact
   packaged product/client lock hashes, then initializes one closed config;
2. signature-scoped Binder evidence, or the embedded app's Android-derived
   package/single-signer evidence, issues a client-bound token;
3. `authorize_use` creates one capability-scoped bounded use;
4. the server mutation carries that use id, its opaque token id, use-creation
   admission revision, live provider epoch, exact client id, Runtime Host
   revision, command, lease, and bounded time window;
5. `ManifoldBrokerRuntime` consumes the use and invokes the single Runtime Host
   review/application path;
6. Java executes a named platform effect only when the Rust response reports
   the preserved host application as applied and returns the exact
   receipt-bound typed parameters.

`QuestBrokerRuntimeProvider` owns process lifetime. Reinitialization with the
same canonical config preserves the provider epoch and both accepted-state
revisions. A different config rejects. Process death discards the provider;
the next initialization uses fresh `SecureRandom` entropy and Rust derives a
new `epoch.provider.*` id. Old-epoch requests cannot enter review.

## Transport request

`rusty.quest.broker.server_mutation_request.v1` contains:

- `bridge_kind`: standalone or embedded placement;
- `provider_epoch_id`;
- `admission_use_request_id`;
- `token_id`, the opaque token that produced that one-use admission;
- `expected_admission_authority_revision`;
- one `rusty.manifold.runtime_host.command_request.v1`;
- typed `rusty.quest.broker.effect_params.v1` plus the matching
  `rusty.manifold.runtime_host.typed_params_digest.v1` on the command request.

The shared client SDK builds this shape through
`build_broker_mutation_request`. It derives the exact
`capability.command.<command suffix>` requirement and refuses commands absent
from the client's explicit capability set.
It canonicalizes nested JSON object keys, rejects canonical payloads over 4096
bytes, and binds the exact type/hash/size through review, dispatch, application,
and response. Java never re-reads request params after authority returns.

## Failure order

Before Runtime Host review, Manifold rejects schema drift, wrong provider
epoch, wrong use-creation revision, unknown/replayed/expired bounded use, token
substitution, cross-client requester, and capability substitution. Once
admission passes,
the use is consumed before review. Unknown or product-unselected commands,
stale host revisions, host request replay, and missing/expired/wrong-holder
leases therefore return the normal Runtime Host rejection but cannot reuse the
admission.

The latest global admission revision is not a blanket invalidation signal for
pending work. Two clients may retain independent bounded uses while unrelated
issue/use/revoke/expiry work advances global state. Exact-token revocation or
expiry removes only pending uses derived from that token.

Java and WebSocket code may inspect `accepted` to decide whether to project a
platform effect. They must never write that field, attach a Manifold authority
label, keep a parallel command allowlist, or treat localhost/session-token
transport as admission.

## Validation

```powershell
cargo test -p rusty-manifold-broker-adapter
cargo test -p rusty-quest-broker-authority
cargo test -p rusty-quest-manifold-broker-authority-native
cargo test -p rusty-quest-native-renderer-android-native embedded_manifold_runtime_authority_jni
cargo test -p rusty-quest-broker-client
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-QuestBrokerAuthorityStatic.ps1 -RepoRoot .
```

## Product-adoption boundary

The authority and Native Renderer embedded config/admission path are complete,
but generic media command adoption remains separately gated. Later product work
must route only accepted and leased `command.media.session.*` work into
`rusty-quest-media-stream`; keep
source, processor, route/socket, codec, sink, and cleanup receipts separate;
and retain remote-camera only as an explicit compatibility mapper. Until then,
missing capability or lease fails closed and no compatibility command regains
unauthenticated loopback authority.
