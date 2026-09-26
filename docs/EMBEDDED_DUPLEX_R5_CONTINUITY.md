# Embedded duplex finite authority and native fencing

This is the source contract for the renewable-authority candidate. The current
operator route remains pre-Start. Native fencing, compilation and host checks
do not establish camera, codec, reciprocal rendering, renewal or teardown.
See [the retained cleanup contract](EMBEDDED_DUPLEX_R4_RECOVERY.md) for the
remaining app transaction and restoration obligations.

## App and native execution ownership

The app holds its private FileChannel writer lock and a synced generation,
process nonce and pending journal binding. Native execution holds a distinct
private inode with a retained `flock` descriptor. These locks have separate
owners and must not share an inode: reopening Java's lock file in native code
would change the Java lock's process semantics.

Native admission reads the live app-held channel. Its opaque capability binds
the app generation and exact admission-record digest to a monotonically
allocated native executor generation. Runtime callbacks check that capability
before and after external work. Enrollment primitives remain available before
runtime admission; an enrollment signature is not runtime execution authority.

Initialization publishes a Host only after construction and the final live
capability check succeed. An unwind guard clears only the initialization gate
on errors or panic. It preserves the staged route and every retained cleanup
obligation; releasing the gate neither proves no effects nor permits a poisoned
capability to bootstrap a successor.

The permanent native record currently stores a schema, counter and checksum.
The app-record-to-native-incarnation mapping is held in memory and returned by
live admission readback. This is not a durable restore mapping or a cleanup
reconstruction protocol. Process death, lock reacquisition, an empty recreated
registry and stale callback suppression cannot prove old provider teardown.

## Validation boundaries

Initialization regression tests use explicit native capabilities and retain the
exact no-media close predicates. Counter exhaustion must start from a valid
persisted maximal counter and reject allocation without wrapping or rewriting
the journal; changing a live counter tests tamper rejection instead.

A focused host fixture and a full Android production-graph typecheck provide
different evidence. Java API compatibility requires Android API stubs or the
actual Android compile/lint route. Desktop JDK compilation alone cannot qualify
an Android API surface. A diagnostic composition lock remains separate from the
original locked build until its exact dependency change is deliberately adopted.

Physical owner cleanup, durable cleanup-only restoration, signed terminal
cross-owner completion, frame continuity and a renewable duplex soak require
their own integrated executor and device evidence. The typed operator provider
and panel retain the same app handlers and do not gain Start from this document.
