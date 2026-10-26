# Embedded duplex Original custody

The debug-only operator provider accepts an optional `original_id` on the eight
current mutators: `start`, `renew_authority`, `peer_stop`, `peer_revoke`,
`whole_app_close`, `concurrent_arm`, `policy_update`, and `blend_update`.
Without that field, the existing finite/direct dispatch route is unchanged.
The provider retains DUMP permission, shell-UID checks and its closed typed
parser. Callers cannot supply JSON, a native operation, an actor, a path or a
process identity. Release source sets contain no operator provider.

An Original is a durable observation of one actual invocation. It is not a
new runtime authority or permission to retry an uncertain effect. The first
call binds a lowercase 32-hex ID to the operation, original challenge and SHA-256
of canonical transport-v2 request JSON. Object keys are sorted, scalar types
remain exact, and typed Float extras bind their raw Float32 bits. The
`original_id` extra is excluded from the request digest. Reusing an ID with
different input is refused; exact repetition observes the same Original and
never invokes its supplier again.

## Actual invocation and outcome

The production store syncs its registered and entered records before invoking
the supplier. That supplier returns the existing RuntimeService Future. The
existing sole ProcessHost command lane carries the source-owned record, binds
the actual held app generation and native actor before the action, and retains
its command until the body returns. No timer, wrapper Future, second executor
or synthetic cleanup substitutes for that operation.

The Future's actual response is bound by a canonical response digest and an
outcome digest. Current lifecycle responses are checked through the actual
`concurrent_qualification_receipt.v1` wrapper and `peer_lifecycle` v1-v3 payload.
Native `pending`, pending renewal, failed Futures and incomplete whole-product
cleanup retain uncertainty. A completed Java call alone cannot settle them.
The configuration/arm routes retain their own current receipt schemas and
operation/challenge checks; their observations grant no physical-cleanup claim.

`operation_query` accepts only the challenge and Original ID. It returns the
transport-v2 envelope and canonical `observation_json`; its challenge is the
query challenge and the row preserves the original challenge. Outcomes are
`pending`, `unknown`, `observed` or `compacted`. Query never dispatches an effect.
A restored registered/entered row is observed as unknown without rewriting the
retained evidence. Restored operations are never replayed and cannot acquire
new acknowledgement credit in another process.

## Process and acknowledgement joins

Explicit Original commands bind the held app FileChannel fence to the actual
kernel boot ID, PID, Android app UID and `/proc/<pid>/stat` birth tick. The
synced companion binds the exact app record and its subsequent phase records.
Missing, conflicting, torn or changed companion data denies bound custody.
Companions and Original checkpoints use exact canonical UTF-8 bytes; duplicate
keys are denied even when the Android JSON parser would overwrite a prior key.
The legacy unbound finite fence remains supported. Binding never resets a
native generation, increments app generation, clears pending recovery or
infers that a previous process's effects have ended.

`operation_ack` additionally requires the exact request digest, outcome digest
and a lowercase 64-hex consumer receipt digest. ACK requires the same actual
completed non-cancelled Future, its joined source callback, no remaining
carried command, successful persistence, and the same held process/native
actor. The actor must still be owned by the actual ready host, or must match
the outgoing actor captured after the existing whole-product native, Java,
display and app-fence cleanup barriers. No-media/fresh/retired-owner shortcuts
are added. The existing no-media whole-close receipt remains queryable as
unknown even when its own scope is terminal; this slice grants it no ACK join. If that join is unavailable, the Original stays retained.

A successful ACK writes an immutable logical `compacted` tombstone. It retains
the full record on disk and drops its live in-memory custody. This label does
not attest file deletion, media cleanup, authority revocation or a new session.
Repeating the same ACK observes the tombstone; changing its consumer digest is
refused. A temporary publication file or unavailable sync is retained and
refused rather than overwritten or treated as success. All records live in
the app-private no-backup `operator-originals-v1` directory. An existing
owner-succession archive marker blocks new mutations in this limited provider.

## Source validation and limits

Run `python tools/checks/Test-EmbeddedDuplexOriginalsHost.py --root .` with a JDK
and the pinned org.json 20240303 host dependency. The host controls use real
production store/Future/file code and source-extracted production caller joins;
Android OS, native/resource observations remain declared model leaves. Run
`pwsh -NoProfile -File tools/checks/Test-EmbeddedDuplexProcessFenceHost.ps1`
with `-JavaHome`, `-HostJsonJar` and optional `-AndroidJar` for actual process
parser, companion and API source checks. Compile the actual app composition
with `:app:compileDebugKotlin :app:testDebugUnitTest` under both default finite
and explicitly selected ongoing-v2 policy when those inputs change.

These are source/host checks. They establish no APK, device, physical camera,
codec, LAN, performance, owner succession or fresh-session acceptance.
Fresh-only credential/lifecycle-v4, continuation, archive and owner-succession
calls remain unavailable. Independent development DATA sessions and the
independently pinned example are separate consumers and contracts.
