# Lossless completed-renewal transport projection

The native owner retains the complete provider renewal receipt. A completed
checkpoint can repeat its `media_authority` in `continuation_checkpoint.baseline`
and `continuation_checkpoint.original`. Serializing those identical objects in
every signed reply and status can exceed the existing frame or Java lifecycle
bounds after a second media lifetime.

`embedded_duplex/renewal_projection.rs` encodes only those repeated values. It
does not change accepted authority, clocks, deadlines, signatures, current
validation, provider state, or the full private archive and custody policy.
The existing signing, frame, Java response and complete-provider bounds remain.

## Closed wire contract

When at least one checkpoint copy is exactly equal to the same receipt's
`media_authority`, the exported object is:

```json
{
  "$schema": "rusty.quest.concurrent.authority_renewal_projection.v1",
  "receipt_sha256": "sha256:<digest of the complete original receipt>",
  "receipt": {"$schema": "rusty.quest.concurrent.authority_renewal_receipt.v3"}
}
```

The `receipt` member contains the complete receipt fields except that either
of the two named checkpoint slots, **only when exactly equal**, contains:

```json
{"$schema":"rusty.quest.concurrent.media_authority_reference.v1"}
```

This tag means the `media_authority` of that same encoded receipt. It accepts
no path, caller-selected target, extra field, external object or recursive
lookup. Unequal historical `original` authority remains inline. Finite v1/v2
receipts and uncheckpointed v3 receipts keep their existing representation.

`expand` restores the two fixed slots and verifies both the complete canonical
receipt SHA-256 and exact deterministic re-encoding. Unknown envelope/reference
fields, altered authority, hash drift and noncanonical reference choices refuse.
Canonical JSON uses the existing native/provider `serde_json::Value` object
ordering, preserving array order. The complete receipt remains below the
provider's unchanged one-MiB bound. A projection is an encoding, not an authority
receipt to pass directly to existing semantic validators.

## Consumers and custody

The signed `renew_finished_b` payload exports this encoding as
`provider_renewal`. The initiator expands it before retaining its renewal result.
The responder keeps the exact encoded final reply for retry; completed-lifetime
archive eligibility compares it against a projection of the actual full
provider receipt. Exact reply, terminal Stop and archive/ledger guards remain.

Lifecycle `renewal_receipts` uses the same encoding. If its latest receipt is
projected, `continuation.$schema` is
`rusty.quest.embedded_duplex.ongoing_media_projection.v3`, and its
`continuation_checkpoint` is exactly:

```json
{
  "$schema":"rusty.quest.concurrent.checkpoint_reference.v1",
  "receipt_sha256":"sha256:<complete latest receipt digest>"
}
```

The consumer expands the receipt window, checks that this reference names the
latest complete receipt, restores that receipt's actual checkpoint, and then
uses the existing v2 continuation/full-v3 receipt validators. Raw export bytes,
producer and capture custody remain distinct from this reconstructed semantic
view. `current_owner_validation.owner_receipt_sha256` still names the complete
original receipt; it never changes to the wire projection's digest. Policy v2
and fresh-lifecycle v4 identities remain unchanged.

An unavailable or malformed projection does not authorize replay, another
renewal, ACK, cleanup credit or a new lifetime. Existing Pending/UNKNOWN
ownership and compensation rules apply.

## Focused validation

The production codec's host controls cover exact round trips, unequal original
authority, unchanged finite exports, closed references, changed hashes and
authority, and latest-checkpoint digest binding. Optional external fixture
inputs supply a complete measured signing body and lifecycle status without
committing private evidence. The connected two-owner/two-JVM lifecycle fixture
must additionally exercise genuine renewed second and repeat lifetimes through
actual Java status/response bounds, ordinary Stop and deferred ACKs. Those
checks prove their modeled host boundary; they do not prove device media,
camera pixels, network performance or repair an already uncertain operation.
