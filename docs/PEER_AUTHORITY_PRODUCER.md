# Retained peer authority producer

`produce_peer_authority` projects retained observations through existing Manifold
peer, enrollment, reciprocal signed rendezvous and signed session APIs. It accepts
typed requests, never accepted peer state, enrollment state or authority booleans.
Completed evaluations retain the actual owner decisions and receipts, including
rejected attempts. Adapter guard failures abort without a review output. The legacy
decision-matrix adapter remains conformance evidence only.

The operator reviews and separately pins the policy (operators, public key
fingerprints and adapters). The facts file binds the session identity, exact raw
BLE pair receipt, two on-device public identity receipts and 1–32 exact reviewed
source/provenance files. Each file has a raw SHA-256 pin. Pair serials, public key
hashes, peer and key IDs must join. The raw facts-file SHA-256 is the reciprocal
nonce. Both signatures consequently cover the same source, identity, BLE and
session closure through the existing owner domain bytes. Fact files are evidence
inputs; a matching file hash does not by itself accept its provenance claims.

Each identity fact has a canonical hardware `serial`, exact BLE carrier `endpoint`,
public identity `receipt` pin and raw `inventory` pin. Inventory is an array of
closed native call records (`arguments`, `exit_code`, `stdout`, `stderr`). Exactly
one record must be `-s ENDPOINT shell getprop ro.serialno`, exit zero, empty stderr,
and stdout equal to the canonical serial apart from trailing CR/LF. Pair primary
and secondary serial fields join the endpoints exactly; no endpoint normalization
or historical receipt rewriting is permitted. Device key identity uses the canonical
serial. The unchanged legacy identity helper accepts that safe canonical serial.

Prepare each `ManifoldSignedRendezvousEvidence` with this nonce, reciprocal peers,
roles, current key IDs and explicit Unix-millisecond issue/expiry times. The owner
requires a lifetime at most 60 seconds. Run:

```text
produce_peer_authority prepare-signing-bytes evidence.json RAW_SHA256 new-owner.bin
peer_authority_device_helper sign-owner-bytes SESSION_ID identity.json device-key owner.bin RAW_SHA256 new-signature.json
```

The device helper reads its retained device key, joins the public identity receipt,
and signs the exact binary owner preimage. It exports no private key. Its receipt
retains session/serial/peer/key IDs, the signed-byte hash and signature. Transfer the
signature into the corresponding evidence request; Manifold verifies that signature
against current enrollment. The old `sign` command signs context JSON and cannot
satisfy this new signed-owner route.

The helper receipt's session ID is metadata: the helper does not parse it from the
binary preimage. The cryptographic session join comes from the pinned facts-file
nonce, the signed owner preimage and the host's exact session projection check.
Receipt metadata alone cannot establish that join.

Retain a request journal in chronological order: actual peer status proposals,
operator enrollment requests, reciprocal signed rendezvous request and session
projection. A request step has `now_ms` and a typed `request` tagged `operation`
(`peer`, `enrollment`, `rendezvous`, `session` or `revoke`). Enrollment/rotation/
revocation are real owner decisions. A session review uses only an accepted retained
rendezvous receipt and the current recomputed enrollment and peer states. The full
signed topology envelope is retained; it is not a new signature over its inner
topology projection and does not grant a direct-media lease.

```text
produce_peer_authority review policy.json POLICY_SHA journal.json JOURNAL_SHA facts.json FACTS_SHA NOW_MS new-review.json
```

The producer recomputes the complete bounded journal (1–256 requests) from empty
owner states and rereads every pinned input before creating its immutable output.
The final request time must equal the explicit current review time. Historical
requests retain their original review times. A retained rejected request is evidence,
not a successful command exit interpreted as authority: consumers inspect the
actual owner decision and current authorization.

Each file read currently has no per-file size limit. Counts are bounded, but a large
policy, journal, identity or source-fact file can consume unbounded read memory and
hashing work. Select bounded reviewed inputs; this producer makes no bounded-cost
claim for arbitrary files.

This target-free CLI performs no enrollment device mutation, network operation,
ADB operation, launch or stream. Durable continuation must CAS the operator-retained
journal raw digest and append without truncating prior requests. The CLI does not
own a machine-wide latest-journal pointer or hide a concurrent writer. Resetting a
journal cannot be credited as durable replay protection. Effect owners must recheck
the current journal, file pins, receipt expiry and accepted owner decision at their
effect boundary. Different fact closures require separately retained journals; no
historical receipt may be relabeled as current authority.
