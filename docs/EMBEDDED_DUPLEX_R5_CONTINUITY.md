# Embedded duplex finite authority and native fencing

This is the source contract for the renewable-authority candidate. The current
operator route remains pre-Start. Native fencing, compilation and host checks
do not establish camera, codec, reciprocal rendering, renewal or teardown.
See [the retained cleanup contract](EMBEDDED_DUPLEX_R4_RECOVERY.md) for the
remaining app transaction and restoration obligations.

For an already Peer-terminal started route, the selected whole-close request
retains its own completion future while actual Own, renderer and resource
barriers retire. The existing Own lifetime observer only schedules readiness
checks on the process command lane. Native host completion rechecks the exact
configuration and app/native incarnation, the selected whole-close and retained
Peer-terminal history, then every existing physical barrier. It does not repeat
Stop, route termination, projection stop or an owner dispatch. Duplicate calls
on that same challenge observe the held completion instead of issuing effects.

Elapsed time and callback arrival never imply cleanup. A changed identity or
failed continuation stops observation and retains the failure/objects; missing
owners and incomplete barriers remain Pending. The operator's wait may expire
without canceling the held cleanup. The existing same-challenge whole-close
receipt remains available after completion. No-media-only/remote-owner closure
keeps its existing route; this completion seam selects a started local client
only. Host controls are not Android physical teardown evidence.

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

### Verified terminal Stop projection

The incoming Sink gate accepts the existing C1 `retained_cleanup` projection
only at the registry-verified terminal Stop callback. It retains the exact
armed provider epoch, client, lease, runtime spec, live expiry, handle and newer
provider revision checks before recording a reopening fence. A subsequent arm
and activation still require a separately authenticated `current_route`;
cleanup or compensation cannot authorize either. The older retained cleanup
v2 projection remains supported by its existing callback.

The closed native diagnostic parser also admits Sink terminal Stop failures
with `INCOMING_ARM_PROJECTION` or `INCOMING_ARM_EVIDENCE` at the incoming
verification stage. These bounded diagnostic fields confer no completion or
authority. The first-failure latch remains unchanged: an old Stop diagnostic
does not identify a later compensation failure. A rejected gate callback may
follow actual provider execution and registry evidence consumption, so neither
that rejection nor a source fix proves physical cleanup or permits replay.

Production Java gate/registry controls and the focused
`Test-OwnerDiagnosticReadHost.ps1` parser controls exercise these joins on the
host. They do not prove Android/JNI execution, a remote compensation outcome,
an installed APK change or terminal cleanup on a headset.

### Retained prepare failure observations

The generic app identity signer admits the four closed retained preparation
domains used by the native request and target response constructors: cleanup
v1/v3 and abort v2/v4. Domain matching includes the NUL separator and requires
a nonempty payload. The callback capability, current cleanup requester and
lease, enrolled target signature, and physical owner barriers still apply;
signing a preparation never proves cleanup. Common-LAN context and pair
ceremony bytes retain their separate validated signing routes.
The native seed signer admits these same retained domains and the existing
cleanup dispatch request/response domains; it preserves its closed allowlist
and byte bounds.

`tools/checks/Test-RetainedCleanupSigningHost.py` executes the production Java
domain guard and signing methods against domain bytes from both Rust
constructors. It checks real JCA Ed25519 signatures, payload tampering, missing
payloads, adjacent versions/domains, and existing bounds. Its identity key is a
host fixture. The same check executes the complete production Rust seed signer
and real Ed25519 signature controls; it does not exercise the Android/JNI
callback or remote effects.

The native first-failure latch distinguishes outgoing peer binding, pending
capacity, sequence, entropy, preparation encoding, signing and exchange using
fixed `RETAINED_REMOTE_*` codes. Incoming preparation separately identifies
requester-authority lookup and original-owner tuple joins with fixed
`RETAINED_INCOMING_*` codes. The existing generic stages remain available for
other failures. These codes contain no exception text, identities or payloads;
they preserve the original operation result and never establish completion.
`peer_status.native_owner_dispatch_failure` carries the code through its
existing field. The Java diagnostic parser and cleanup authority remain unchanged.

`tools/checks/Test-RetainedPrepareDiagnosticHost.ps1` executes the exact outgoing
production preparation block and latch with explicitly modeled platform seams.
The focused broker-authority test
`retained_prepare_sender_grant_and_requester_are_not_receiver_local_authority`
uses distinct production authority stores, signed pair/session/route and revoker
APIs to check local and foreign grant/requester lookup. Its platform executors
are modeled. Neither test observes an actual incoming headset packet, JNI
execution, or completed remote cleanup.

### Cleanup after coupled session renewal

Retained remote cleanup keeps the exact original forward Start target. A native
sender validates its complete canonical owner snapshot before projecting compact
same-session transition certificates into the completely signed Prepare. The
last 64 accepted linked transitions may be carried, at most 64 KiB, within
the existing 128 KiB frame bound. The receiver anchors the ordered, noncyclic
transitions to its retained original topology and the exact current cleanup
topology; a missing original anchor fails closed. Each signed certificate retains
the accepted receipt digest, session/decision/peer/transport/configuration joins,
request, revision and observed/expiry bounds. It does not import the sender's
Broker or infer foreign ancestry from
its own authority history. Signer, original grant/client/lease/provider, current
requester expiry, native capability and physical owner guards remain mandatory.

Empty ancestry preserves the original serialized Prepare shape. An old receiver
rejects the new ancestry field; there is no downgrade fallback. Updated sender
and receiver are needed before selecting renewed cleanup. The focused
`retained_prepare_authenticates_original_after_coupled_renewal_and_expiry` test
uses two distinct production authority stores. Focused controls also exercise 22
continuous coupled renewals before expired-holder cleanup and a later Start
after 16 renewals without further renewal. Coupled owner history remains bounded
at 32 completed renewals; a 64-certificate serialization specimen tests wire
size only. The focused `retained_prepare_renewal_after_supported_media_restart`
regression exercises 16 renewals, completed Stop/release and a separately admitted
new Start, then six further renewals and expired-holder cleanup in both directions.
The Manifold owner validates continuous inner-lease history within each admitted
derivative lifetime, authenticates the terminal release/new-admission boundary,
and checks every retained old and new media/route graph. Altered historical
release, terminal, cleanup, lease-chain and graph records fail closed.
`tools/checks/Test-RetainedCleanupAncestryHost.py` tests
the exact native Prepare serde and signature verification with real Ed25519.
These host tests do not prove JNI, exchange or physical cleanup.

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

Forward Start retention includes only ArmCleanup, ArmReceiver and Start owners.
A rollback carrier keeps operation Start but has Stop/Cleanup action kinds;
executing it must preserve the original forward ticket, projection and any
verified forward effect. An unprepared receiver's terminal Stop reports and
retains `stopped`; Cleanup reports and retains `cleaned`, after the same reader
release and projection retirement barriers. The packaged registry still joins
the readback to its fresh physical snapshot. These source contracts cannot
reconstruct an original already overwritten by an older installed candidate.
No owner is absent merely because Start never reached its ticket: local cleanup
must enter the exact packaged registry, stop any independent resources and
verify its current terminal snapshot. Unknown or stale snapshots remain Pending.

`tools/checks/Test-RetainedStartAbortCleanup.py` accepts an explicit pinned,
modeled supplier graph and checks the real owner continuation, recovery damage,
registry resources, neutral consumer and Android native type boundary. It
neither admits that graph nor builds an APK or proves physical terminal cleanup.

The signed cross-peer cleanup request now has a separate v3 Stop / v4 retained
abort domain. It carries the issuing peer's cleanup projection, binds it to the
authenticated remote Start projection retained at the executor, and verifies
the currently enrolled peer signer. The executor does not import foreign grants
or requester leases into its own Broker store. Source-side live requester checks
remain required before preparation and commit exchange; receiver-side replay and
commit revalidate the retained source, signed request and finite expiry.

Old retained records without the authenticated Start projection cannot acquire
this authority by inference. Legacy v1/v2 requests retain their original strict
local-store validation. Signed field changes, stale enrollment, missing original
provenance, replay collisions and expiry reject cleanup; rejection leaves the
physical retirement barrier unresolved. The separate-store host controls execute
the exact native prepare/current-source code with real signatures and production
Broker validators; Java persistence, clock and platform completion are modeled.
No host result identifies the incoming packet or cause of a prior device failure.

### Current-process remote receiver closure

A peer that only received an authenticated receiver Arm and Stop does not own
a local seven-owner media transaction. Its `whole_app_close` can authenticate
the current, unrestored dispatch replay and verify that every receiver handle's
latest signed registry readback is stopped after its Arm. Pending, uncertain,
rejected, malformed, foreign-generation or restored histories remain unresolved.
Local media mutations and retained cleanup-server history use their existing
typed cleanup routes instead. `close_no_media` still refuses prior owner effects.

This branch reports `rusty.quest.embedded_duplex.remote_owner_cleanup.v1` with
`peer_physical_cleanup=not_locally_started`. Its authenticated receiver history
explicitly makes no physical cleanup claim. Native resource, Own capture and
renderer retirement are still required before removing the native host; Java
then checks the same process/config/executor, resources, control callbacks,
journals and process fence before reporting whole-app terminal. Polling or
repeating closure cannot promote a pending physical barrier to terminal.

`tools/checks/test_remote_owner_close_host.py` takes explicit repository,
supplier Quest root, tool and JSON jar paths and an absent output directory.
It runs the real signed replay predicate and the actual extracted Java
completion method with modeled platform barriers. These controls do not build
an APK or attest Android resource retirement, device state or media acceptance.

### Unused incoming receiver during whole-product closure

A successfully installed duplex host retains both directional resource sets.
Outgoing-only Start/Stop leaves the local incoming receiver unprepared. Explicit
`whole_app_close` retires that unused receiver with the existing synchronized
`closeUnstartedAndVerify` guard before observing the product resource barrier.
Staged, armed or used receivers require their typed owner cleanup; this step
neither stops them nor manufactures a local media Stop. Pipeline, outgoing
registry, Own capture, renderer, native host and process-fence barriers remain
mandatory. `close_no_media` is unchanged.

The focused unused-incoming host control extracts the actual receiver and
resource methods with modeled remaining platform members. It proves source
behavior only, not Android JNI integration, physical cleanup or acceptance.

### Explicit nonaccepting abandonment after process absence

#### Historical birth unavailable, with no Start journals

The same fixed operator call also accepts the closed
`rusty.quest.embedded_duplex.abandonment_authorization.v2` branch. This branch
is limited to an original pending app fence whose checkpoint and recovery
evidence journals were both absent. Nonempty journals reject; v1 remains the
existing complete-identity route. V2 never invents a Start lineage.

`historical_peers` contains exactly `peer_a` and `peer_b`. Each retains the exact
original concurrent-qualification ARM receipt and its digest, original package,
APK, signer, physical device, boot and installation UID scope. Original Linux
birth is explicitly null with `historical_birth_not_recorded`. Package UID is
the independently attested installation scope, not a claimed old `/proc` read.
Complete package/UID process absence on both devices must be independently
authenticated immediately before dispatch; Boolean attestations and digests
are not self-authenticating process inventories. Preserve failed cleanup and
all original receipt capsules alongside that evidence.

The verifier checks ARM/native snapshot identity and challenge, reconstructs
the original pending/no-journal fence from the producer's app epoch, and binds
its digest to the authentic immediate recovery predecessor receipt. The exact
recovery fence bytes then bind that receipt to the current successor's actual
predecessor. Reconstruction is labeled as reconstruction; it is not a recorded
OS birth. A swapped historical actor cannot match this chain.

`current_recovery_actor` is separate: current PID, `/proc/self/stat` start ticks
and app generation are checked against the actual effect-free process. The
current installed APK digest is distinct from the preserved original APK;
package, component, signer and installation UID must still match. A same-package
signed update must preserve private data and acquire a new exact source/feature
closure before adoption. Changing the namespace or erasing journals is not a
recovery route. No build or install is authorized by the disposition itself.

The existing archive-first/fence-commit transaction retains both fixed fences,
authorization and immutable copies of the selected owner's dispatch, product
activation, retained cleanup and native fence records. Their exact hashes are
checked before and after copying. Those active records are never erased by v2.
After the app fence has durably cleared, an exact clear-fence witness is synced
inside that archive. V2 restoration requires that witness; a marker alone never
projects replay or permits freshness. A clear fence without its witness fails
closed. The reader projects only the three exact archived dispatch, activation
and cleanup-preparation maps as canonical empty maps for the independent scope.
Sidecars deny before AtomicFile recovery. New record hashes remain visible;
native generation state is never projected. The other peer's record digests are external evidence; the local
archive does not claim to copy remote files. Interrupted archive commits remain
recovery-only. The unchanged `old_scope_abandoned_cleanup_unknown` receipt keeps
both cleanup and authority-transfer claims false. Fresh enrollment and Start
remain independent, with no automatic admission, callback or command replay.

The debug-only DUMP/shell operator has two separate fixed calls:
`recovery_status` reads the retained fence/journal digests; `abandon_retained_scope`
accepts one bounded, closed `authorization_json` of schema
`rusty.quest.embedded_duplex.abandonment_authorization.v1`. This is deliberate
operator authorization, not a cleanup, Start, grant, or native-operation input.

The operator must first authenticate genuine complete process-absence evidence
for the selected old package UID/PID/start-birth on the exact physical device,
boot and user, including the other affected peer when applicable. It must join
that evidence's exact SHA to the old installed actor and fresh signed candidate,
and guard these facts immediately before dispatch. The app records this
external attestation honestly; it cannot recover the old PID/boot from the
process fence, and a digest alone is not self-authenticating absence evidence.
The existing debug shell/DUMP boundary supplies the explicit operator authority.
The app independently checks its current package, packaged signer, installed
APK digest, component, UID/user, a different current PID, and exact current and
predecessor fence digests. No generic paths, keys, grants or operation strings
are accepted. Operator attestation must never be populated from `pidof` absence alone.

Invoke before attaching a new Activity. Only a live exclusive recovery-only
fence in a NEW process with no native Host, platform/resources, display,
review, or live Own capture can accept it. The archive retains exact predecessor
and recovery fence bytes, checkpoint and evidence bytes, plus a synced receipt
`old_scope_abandoned_cleanup_unknown`. It states `old_cleanup_confirmed=false`
and `old_authority_transferred=false`. Neither peer nor whole-app terminal is
published and the old checkpoint's phase is never relabeled terminal.

The marker is flushed before clearing only the successor app fence. A crash
before that fence commit remains recovery-only; a torn fence fails closed.
Journal readers hide only exact archived abandoned records from the fresh
active scope. After archive and fence commit, the owner retires only the
verified duplicate active journal files; the exact archive bytes remain
preserved and rechecked. Unarchived AtomicFile sidecars or drift deny the
operation. A partial retirement is reconciled solely against that exact archive.
Fresh bootstrap still constructs new Manifold authority and must pass ordinary
enrollment, process, display, pair, finite expiry and Start checks. No retained
snapshot, lease, replay, callback or grant is restored or transferred. A later
fresh-effect crash is a new recovery obligation. Successful abandonment means
permission to attempt an independent scope, not accepted media or old cleanup.

This extends the older cleanup-only recovery contract with an explicit
nonaccepting disposition. Actual recovery-only restoration continues to require
its original exact authority/owner joins. Host controls model external absence
and Android chmod/platform observations; they prove neither Android execution,
actual process absence, physical cleanup, APK qualification nor device recovery.
