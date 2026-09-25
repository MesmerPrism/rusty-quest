# Embedded Duplex r4 retained cleanup and recovery contract

Status: Quest owner design contract. No Start, Stop, or Revoke operator verb is
enabled by this document. The current preflight and signed-pair path do not
establish media acceptance.

## Authority and identities

Manifold remains the only Broker, media-session, lease, peer-session, and route
decision authority. Quest owns the platform transaction, owner callbacks,
write-ahead journal, and terminal readback. A debug shell adapter transports
only fixed calls to the same app handler used by the panel. It supplies no
client, lease, ticket, route, JSON, key, or revoker identity.

Every transaction retains the exact configuration SHA-256, confirmed enrollment
SHA-256, APK/source binding, display generation, provider epoch, local peer,
signed session ID and expiry, original media client and lease, accepted media
decision, route grant, platform runtime specification, and each authority
revision. Every mutation has a unique request ID, exact input digest, owner
receipt digest, and a persisted `attempted` marker before its call. An
ambiguous response is reconciled against live or restored authority; it is
never interpreted as rejection.

## Two-principal retained cleanup

A retained cleanup ticket needs two separate identities:

| Principal | Required fields | Meaning |
| --- | --- | --- |
| Immutable target | `target_client_id`, `target_runtime_lease_id`, `target_provider_epoch_id`, `target_platform_runtime_spec_id`, `route_grant_id`, `peer_session_id`, `terminal_route_sha256` | The original accepted media and route that must be stopped. These fields never change to the revoker. |
| Current requester | `requester_id`, `requester_runtime_lease_id`, `requester_provider_epoch_id`, `requester_expires_at_ms`, `trusted_revoker` | The live original holder or separately trusted revoker allowed to request cleanup. The requester lease must be current and non-derivative for revocation. |

The signed cleanup projection also binds the exact owner selection, action
kind, ticket ID, execution nonce/generation, `cleanup_target_sha256`, signed
topology digest, authority/route revisions, and exclusive expiry. The executor
checks the target and requester independently against
`retained_cleanup_target` and the restored live Broker/peer. It never edits an
old Start ticket to carry a revoker ID. The current single-client/lease
`AndroidMediaExecutionTicket` cannot express this proof: Quest must add a
distinct cleanup ticket/projection and verifier. Manifold's existing retained
target and route cleanup APIs remain the decision source.

The product registry may execute only idempotent Stop or compensation under
this projection. Each owner must return its own terminal readback, including
resource handle and monotonically newer state revision, or prove absence from
its own registry. Seven owners remain ordered, with terminal cleanup last.
An uncertain owner retains a retry cursor; no prior verified effect is
re-executed. `complete_route_cleanup` receives only the native-derived effect
receipt ID and SHA-256 after all owner and application receipts verify.

## Process-death restoration

The first r4 checkpoint is a private `AtomicFile` phase marker. Before media
mutation, the app must extend it to a complete, bounded, versioned evidence
set with exact digests for the Broker adapter Host snapshot, Broker runtime
evidence and control-lease authority, peer Runtime Host snapshot, media product
state and owner progress, dispatch/activation replay, and request/receipt
ledger. The app holds one exclusive writer per provider epoch. Every
write-ahead marker and authority snapshot is synced before the next effect.
Malformed, missing, stale, or cross-epoch evidence blocks Start and fresh
bootstrap while retaining the journal for operator-visible recovery.

Recovery reconstructs the same provider epoch through Manifold's existing
adapter `restart_from_json`, Broker
`restore_from_caller_attested_exclusive_evidence`, and peer
`restart_from_json_with_live_broker_runtime`, checking their exact joins. The
Quest provider must expose a validated restore path rather than calling its
current `from_config`, which creates a fresh epoch and empty media sessions.
Quest media product state needs a separate recovery-only restore that retains
pending owner receipts, uncertain attempt, abort progress, active handle
identity, applied action IDs, and original client/lease. It must not accept
untrusted aggregate completion JSON as authority.

**No old Start action or callback may run after process death.** The restored
state is cleanup-only. If no owner callback began, abort the pending Start and
terminalize any route with exact authority receipts. If an owner may have
started, use the two-principal retained cleanup protocol and provider-verified
readback. If a live provider/handle cannot be rejoined or absence positively
proved, retain an unresolved cleanup state. A fresh epoch may request cleanup
only through an independently admitted trusted revoker and the retained
original target; it cannot inherit the original media action.

## Terminal condition

Clear the transaction only when the local and remote route legs are terminal
and cleanup complete, all owner resources prove stopped/absent, the media
runtime is terminal, the Broker and peer snapshots retain exact final receipts,
dispatch and activation replay have no unresolved entry, and the app has
read back the identical target, requester, provider epoch, route grant, and
effect digest. Expiry, process death, a rejected command, or an empty registry
alone is not terminal evidence.

## Required host failure gates

- Rejected IssueToken, AuthorizeUse, and Start mutation, including a mutation
  that consumes admission or adopts a lease before returning an error.
- Crash before and after every write-ahead marker and authority/owner receipt;
  a restarted process chooses cleanup/reconciliation and never Start replay.
- Wrong provider epoch, session, route, target client/lease, runtime spec,
  requester lease, derivative revoker, stale ticket generation, copied owner
  readback, or swapped effect digest rejects without a platform callback.
- Route issue reject/ambiguous reply/expiry, every one of the seven Start and
  Stop owner steps, uncertain callback, abort retry, activation reply loss,
  Stop/Revoke race, and cleanup receipt mismatch retain a retryable exact
  state or produce full terminal proof.
- UI and fixed CLI verbs call one service/host handler and return the same
  typed state; no caller-supplied native operation or JSON escape exists.

The current `rusty-quest-media-stream` test
`stale_and_restarted_platform_completions_fail_closed` already rejects old
epoch readbacks. The current embedded authority test
`retained_cleanup_ticket_never_relabels_original_target_as_revoker` already
rejects relabeling. Both invariants remain required in the new cleanup path.
