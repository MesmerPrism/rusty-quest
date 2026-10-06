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

### Process-local installed APK digest

The process host owns one non-persisted installed-base-APK digest entry. Each
qualification request still reads current PackageManager package, version,
install timestamps, UID and source path, opens a readable APK descriptor, and
checks its identity against the current path. The key includes device/inode,
mode, owner, link count, size, and modification/change timestamps including
nanoseconds. Access time is excluded because a digest read can change it.
Identity is checked before and after a hit or full digest read. Changes and
observation/read/close failures discard the entry; a new process starts empty.
This relies on the ordinary Android installed-package filesystem and metadata
boundary, not on resistance to a privileged actor forging that boundary.
It does not cache qualification, process fences, native status, signed sessions,
Start intents, cleanup results or execution authority. These guards remain live.

`tools/checks/Test-InstalledApkDigestMemoHost.ps1` executes the production memo
and extracted identity comparison with modeled package/stat boundaries, checks
concurrent requests and failure invalidation, and compiles the Android adapter
against API34. It establishes neither Android execution nor an end-to-end
performance gain. SDK installed-byte-read counters do not count this app-local
digest calculation.

### Process-held Start intent consumption

The app consumes its exact preflight object before native Start dispatch. A
throwing or Pending Start retains a process cleanup obligation and cannot
replace or replay the consumed intent. An active native Start acknowledgement,
joined to the same configuration and validated qualification receipt, releases
only that intent slot. A later Start still requires a newly observed signed
pair, current expiry, enrollment, display and process guards. Pending preflight
lineage continues to bind the exact session expiry; renewal never rewrites an
unconsumed object. Verified whole-product or no-media cleanup clears the slot
through the existing cleanup owner.

`tools/checks/Test-EmbeddedDuplexStartIntentHost.ps1` executes the production
Java slot and preflight classes with a modeled native boundary and checks the
host's dispatch/receipt/cleanup integration. It provides no Android API, APK,
device, signed-pair or physical-cleanup qualification.

### Peer source retirement after decoder shutdown

Concurrent Peer teardown stops the decoder and releases its native reader before
removing the registry source. Native code retains the exact stopped route,
decoder and reader identity. Registry retirement consumes that positive stop
proof with no current decoder binding; binding absence alone is insufficient.
An exact source or a never-published source can then retire atomically, including
a failed Start that received no frames. The source slot retains its epoch
watermark so stale publication or cleanup cannot revive or remove a successor.

This removes only the Peer registry reference. Own capture and previously
submitted image leases remain under their existing owners; GPU fences and reader
lease retirement still determine physical cleanup. Render-policy changes select
Own or Peer through the existing stereo-bank control owner and do not perform a
media stop or the separate Local rollback operation.

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

### Retained failed-Start cleanup

A completed reverse Start rollback can retain a cleanup obligation without an
active Start holder. The explicit recovery v2 journal preserves the exact
original action, forward prefix, uncertain owner and verified reverse receipts.
Recovery v1 does not accept this record. Ordinary Start and Stop remain denied;
cleanup requires a separately authenticated current trusted revoker and the
original accepted client, lease, provider epoch, product spec and rollback cursor.
An ambiguous durable write blocks further effects. A terminal lifecycle label
does not restore terminal authority without independent current terminal proof.

Fresh requester transport can execute the original rollback's Stop/Cleanup
owners through distinct signed retained-abort v2 preparation/effect domains.
The actual target ticket remains Stop and its registry readback is verified
before projection onto the original Start rollback carrier. Existing retained
cleanup v1 remains Stop-only. Missing remote original state stays Pending.
No owner is absent merely because Start never reached its ticket: local cleanup
must enter the exact packaged registry, stop any independent resources and
verify its current terminal snapshot. Unknown or stale snapshots remain Pending.

`tools/checks/Test-RetainedStartAbortCleanup.py` accepts an explicit pinned,
modeled supplier graph and checks the real owner continuation, recovery damage,
registry resources, neutral consumer and Android native type boundary. It
neither admits that graph nor builds an APK or proves physical terminal cleanup.
