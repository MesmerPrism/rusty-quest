# Embedded duplex finite authority and native fencing

## Retained preparation admission diagnostic

The fixed debug-only `prepared_peer_start_diagnostic` operator call accepts a
fresh challenge and an optional typed `original_id`. It reads the existing
process/native preparation journal without creating a host or Original,
rerunning eligibility, entering SDK/transport work, or requiring a preparation
Arena. A supplied Original must match an actually bound attempt. Failures before
that binding leave the Original explicitly unknown. Missing attempts are
`unobserved`; an unavailable current actor/journal refuses the read. Neither
case proves absence of effects or upgrades an older UNKNOWN.

The journal retains the actual first failed stage and closed reason while the
primary operation and its generic JNI error remain unchanged. Java eligibility
failures include the actual closed callback guard. The combined native physical
guard includes Boolean/nullable predicate results derived only from its already
computed values. No raw exception text, keys or caller-supplied native operation
is returned. The Java census contains phase/reference presence only and always
reports `cleanup_proven=false`; it does not call the no-media classifier or SDK.

There is no preparation retry quota. The diagnostic attempt counter never limits
the operation, including exhaustion. A live Arena, unresolved Original or stale
actor is an ownership/idempotency guard. Fresh preparation after actual terminal
cleanup uses a fresh Original and archives the prior preparation through its
existing owner; it does not replay an UNKNOWN. No automatic retry, clear, cleanup
or acceptance is added by this diagnostic.

`tools/checks/Test-PreparationDiagnosticHost.py --output <new-host-output>
--json-jar <host-org-json-jar> --android-jar <selected-compileSdk-android.jar>`
first compiles the complete diagnostic helper against the selected Android API,
then compiles the actual Rust journal and Java decoder,
and the actual Java eligibility bodies with modeled Android dependencies. It
checks each stage, callback failure location/order, predicate retention, stale
actor/Original rejection, counter exhaustion and repeated read purity. These
controls do not qualify an APK, JNI runtime, camera, pair, media or cleanup.

## Process incarnation and ordinary app Close

New local-resource obligations retain the existing write-ahead v1 fence and
native admission ABI. Before an effect can enter, a synced private companion
binds that exact fence to the kernel boot ID, PID, package UID and process birth
ticks. Status reacquisition preserves the original effect owner. It cannot
reassign that owner's effects to the status reader or infer death from a lock
release, generation increment, elapsed time or relaunch alone.

A distinct actual boot, an absent original PID, or a different birth at that PID
can retire the original process's local handles. A private retained receipt
contains the original fence bytes and incarnation, the actual observer and the
closed termination reason. Its disposition is termination by process or boot;
graceful cleanup remains unconfirmed and remote authority is never transferred.
Same-boot births are never compared across different boots. Unreadable kernel
observations, UID mismatch, legacy/missing/interrupted/corrupt companions and
missing retained termination evidence keep recovery pending. A primary write
without a committed companion earns no termination inference.

Automatic fresh admission additionally requires absent Start/evidence journals
and absent remote replay/cleanup/retained-setup records, including sidecars.
All remote records retain their original bytes, owner, revocation, expiry and
unknown outcome; the local termination receipt neither reconciles nor replays
them. Presence is deliberately conservative, including retained completed
records whose authority cannot be inferred by this local fence. Existing
explicit nonaccepting disposition remains the route for legacy ambiguity.
Enrollment, settings, archives and replay tombstones are untouched.

The ordinary app `closeProduct` entry selects the existing `WHOLE_APP_CLOSE`
owner with its retained or actual current ARM challenge. It creates no ARM or
session and never substitutes no-media closure for a started route. The
Activity exposes the explicit close future and uses the same owner as its
`onDestroy` fallback. The existing panel Close still dismisses the panel.
Android need not invoke `onDestroy`; process-death handling therefore depends
on the durable incarnation evidence. Close completion still requires the
actual Stop, Own, renderer, callback, control and whole-product joins. Pending
or failed owners retain their attachment; only real terminal completion permits
reopen. No new app quit button or OS lifecycle guarantee is introduced here.
An owner future returning normally is insufficient: ordinary Close validates
the direct or wrapped whole-app proof and its fence/Own/renderer/control joins.
A Pending, failed or malformed receipt leaves the Activity and its executor
owned, even when the transport future itself succeeded.

`tools/checks/Test-EmbeddedDuplexProcessFenceHost.ps1` exercises the real fence
and private filesystem, with modeled kernel observations, plus genuine Android
API34 compilation when an Android SDK is selected. It covers status/relaunch,
same-boot death, changed boot, PID reuse, uncertainty, interrupted persistence,
legacy records, retained remote bytes and verified close/reopen. The production
bootstrap prefix, pre-native claim failure, never-started teardown prefix and
ordinary Close selection run through `Test-OrdinaryProductCloseHost.py`.
These controls establish neither Android physical cleanup nor APK/device
qualification. Existing retained whole-close completion controls remain
applicable.

### Remaining independent Own admission

An unresolved remote transaction still blocks
`EmbeddedDuplexRecoveryCoordinator.requireFreshBootstrap()` in
`EmbeddedDuplexProcessHost.initializeOnCommandLane`, before native initialization
and `EmbeddedDuplexResources.installAsync` can start Own. Native
`runtime_host::authenticate_own_capture_lock` currently derives the Own lock
from `QuestBrokerRuntimeConfig.packaged_authority.client_locks`, and
`runtime_host::build_host` publishes `own_capture_bootstrap` alongside the Peer Host's
provider, transport servers, lifecycle state and native capability. Bypassing
that gate would reuse authority rather than separate local capture.

The next bounded design needs a distinct local Own admission owned by Quest:
authenticate the installed package/signing identity and selected
`quest-stereo-input-set` feature lock against an actual current app/native
incarnation, without loading or replaying Peer transactions. Hold its capture,
display and local stop owner separately from `EmbeddedDuplexResources`' Peer
pipeline, incoming receiver and registry. Keep Manifold's accepted Peer
session/dispatch/expiry/revocation owners unchanged. Compose observations so
local capture/cleanup can be current while remote reconciliation is pending;
local completion must never claim whole-product or remote transaction cleanup.
That separate admission and resource split is outside this source slice.

## Optional current receiver cleanup observation

Existing `peer_status` can include
`rusty.quest.embedded_duplex.remote_receiver_observation.v1` for a current,
unrestored remote-only receiver. This optional observation grants no authority
and does not change top-level Peer, native-host or whole-app cleanup meanings.
Local Start/pending cleanup continues to require its existing typed owners.

Native reuses authenticated current receiver history: exact raw readback and
signature, current key/generation, no pending dispatch/activation or retained
cleanup, and latest Stop after Start for every receiver group. A separate
history projection retains exact Stop receipt, handle and state revision; it
still claims no physical cleanup. Java takes coherent receiver snapshots around
two read-only native STATUS projections and requires stable history/resource
identity plus an exact matching terminal Stop. Actual decoder terminal, released
reader surface and retired Peer projection must all hold. Changes, unavailable
proof, pending work or a rearm remain nested Pending. Ordinary STATUS keeps its
existing result when this optional observation is unavailable.

Reads dispatch no owner action, consume no registry verification, release no
resource, and stop neither Own capture nor the app. No native lock is held
across Java callbacks. This is a bounded current observation, not permanent
quiescence or authority for a later Start; the operator's Stop fence remains
separate. The closed debug operator and fixed native action words are unchanged.

`tools/checks/test_remote_receiver_observation_host.py` compiles production
signed-history code and actual Java STATUS call-site/snapshot/composition
bodies against explicitly modeled platform facts. It checks stable exact joins,
pending/rearm/identity/resource faults and zero read effects. These host checks
are neither Android physical cleanup nor artifact/device qualification.

The prospective opt-in owner checkpoint and versioned native observations are
specified in [Ongoing media continuation](ONGOING_MEDIA_CONTINUATION.md).
Finite defaults and the fixed native ABI remain unchanged; the first source
candidate does not yet remove generic lifetime history capacities.

## Closed bootstrap resource failure observations

The existing debug `runtime_status` failure stage/code fields retain only closed
facts. Resource installation keeps stage `resource_install` and distinguishes
native initialized identity, owner construction, Own capture startup, Own display
activation, pipeline creation/route, outgoing owners, bindings and registry
installation. Own display activation additionally distinguishes unavailable
retained capture, fresh-frame Pending, carrier not ready, superseded owner,
interruption and the existing dispatch deadline. Codes contain no exception
message, input, endpoint, key or arbitrary class name.

The trace follows the actual failing call before cleanup; suppressed cleanup
errors do not replace it. The failure does not claim readiness, cleanup, media
Start or authority, and does not authorize retry. Own capture and exact partial
Peer owners remain with the existing cleanup handlers. Runtime/provider schemas,
operator calls, freshness checks and Java timeout bounds are unchanged.

`tools/checks/Test-BootstrapResourceFailureHost.py --root . --output <new-output>`
compiles the production trace and resource-install bodies against modeled media
owners. It checks per-step faults, typed activation reasons, wrapper deadlines,
cleanup failures and refusal of repeated installation. These host controls are
not Android/JNI or physical camera qualification.

## Renewal OS-clock sampling observation

The native renewal caller reads the actual OS wall clock inside a monotonic
bracket. The existing one-millisecond quantization allowance, ten-millisecond
maximum uncertainty and one-second wall/monotonic delta bound remain intact.
The Broker clock validator now returns closed rejection codes for unhealthy or
foreign accepted clocks, reversed clocks, discontinuity, revoker identity or
clock drift, exhausted sequence, and excessive read uncertainty. Its existing
public method preserves the previous error categories. Validation never
publishes a clock or grants authority.

Only excessive measured read uncertainty permits another fresh OS read, up to
three samples. All hard bounds are checked before that classification; a wide
read cannot hide another failure. No provider mutation, signing, network
exchange, authority extension or effect is repeated by this sampling loop.
The accepted fresh sample supplies the actual time for the unchanged provider
renewal. A failed/exhausted read preserves the pending renewal and old receipts.
Actual signed-expiry and current-route checks remain separate and unchanged.

The existing debug peer lifecycle receipt adds optional `authority_clock_read`:
null until a renewal clock read, otherwise a versioned bounded observation of
the latest read, its finite rejection codes, actual wall/monotonic bracket and
uncertainty, attempt count and unchanged numeric bounds. It contains no epoch
identifier, key, credential, configuration body or formatted exception. Reads
do not clear it or turn Pending into acceptance. Existing receipt schema,
required fields, operator calls and collapsed `last_failure` text remain
compatible; consumers that do not inspect this optional observation ignore it.

`python tools/checks/Test-ConcurrentAuthorityClockHost.py --root . --output
<new-private-output>` runs the actual production validator/sampler and extracted
native renewal/pending bodies with modeled OS reads and provider effects.
It covers delayed then narrow reads, hard-bound precedence, exhausted samples,
retained pending history, and no duplicated provider effect. These host controls
do not prove Android/JNI compatibility, deployment, the cause of a prior device
failure, or successful renewed media. Android typecheck and an exact qualified
artifact remain separate prerequisites for later device validation.

Known-birth nonaccepting disposition v4 supports a distinct current boot after
an externally attested complete package-UID absence. It preserves the original
known actor birth, boot, ARM receipt, pending fence and replay records. The
closed authorization adds `current_boot_id`; it must differ from the selected
old boot and match the actual kernel boot read in the fresh, effect-free
process. Current PID, UID, birth ticks and direct successor generation still
match exactly. Birth ticks are ordered only within v3's same-boot branch;
v4 never compares ticks across boots. Archive and committed-fence readback
prove disposition, never old physical cleanup or transferred authority.

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

The Java bootstrap reader accepts the closed two-map dispatch replay form and
its current three-map form with `target_bindings`. Each dispatch field must be
a JSON object. The full replay object is forwarded unchanged to Rust, which
owns validation of retained tickets, target bindings and signed responses.
Activation replay remains closed to its two existing maps. Reading a compatible
snapshot confers neither fresh execution authority nor old cleanup credit.
The production Java packaged-input tests cover both dispatch forms, unchanged
forwarding, malformed maps and unknown fields; these are host controls only.

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

Fresh outgoing cleanup arming after a verified Stop requires every previous
outgoing owner and the outgoing pipeline to be terminal. The injected incoming
Sink belongs to the independent opposite direction and may already be rearmed
by the peer. Its executor generation must still match, but outgoing recreation
does not require or claim incoming cleanup and does not stop or mutate it.
The owner lifecycle host controls retain unresolved outgoing, stale generation,
pending physical cleanup and duplicate-arm rejection alongside this ordering.
Host controls do not establish installed bilateral restart or physical cleanup.

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

### Local transport attempt observation

The existing read-only owner diagnostic and Peer lifecycle receipt expose a
separate bounded latest transport attempt. Its sequence, phase, outcome and
failure category are closed values. Connect, request write, response header,
response body and returned-frame phases remain distinct; refusal, unchanged
transport deadline, EOF, frame bounds and other I/O retain their observed phase.
The first media/native failure latch and its admission semantics are unchanged.
No exception message, peer identity, address, payload or signature is exported.

A returned frame is explicitly unauthenticated: native response decoding,
signature verification, preparation/commit and physical owner barriers still
follow it. A remote handler rejection closes the connection without a rejection
envelope; EOF therefore cannot identify an authentication failure or establish
that no effect happened. Missing/invalid callback data is unavailable/rejected,
never synthesized success. Counter exhaustion and observer failure cannot deny,
retry or reissue transport or Stop.

`tools/checks/Test-OwnerTransportDiagnosticHost.py` exercises the production Java
endpoint over real ephemeral loopback TCP, including refusal, EOF before/within
the header, truncated body, rejected handler, frame bounds, valid unverified
response, observer failure and the actual unchanged thirty-second read deadline.
`Test-OwnerDiagnosticReadHost.ps1` also executes the strict production transport
parser and damaged-field/counter controls. These are host observations, not
Android/JNI execution, an installed APK update or retained physical cleanup.

### Explicit paired cleanup requester selection

A fresh paired enrollment must select the same reviewed `media_revoker_id` on
both sides when it uses the current singleton requester trust policy. Local
revoker adoption grants no foreign receiver trust: the remote retained Prepare
still checks its requester against the receiving peer's explicit trust policy.
Distinct local requester IDs can therefore pass independent enrollment review
and Start, then be rejected on expired-session cleanup. Changing a future
selection does not authorize, repair or retire an existing Pending owner action.

`tools/Review-EmbeddedDuplexPairedEnrollment.py` compiles and invokes the production
inert `EmbeddedDuplexEnrollmentDraft.requirePairedCleanupIdentity` guard against
the two selected review capsules before dispatching either review/confirmation.
The tool never invokes those capsules. Its result grants no enrollment authority;
independent genuine reviews, confirmations, key/role bindings and current process
and device guards retain their existing owners. Unsupported local fixtures or
same-role pairs are rejected. A nonmatching pair is corrected explicitly in the
fresh reviewed selection, never by expanding the receiving policy implicitly.

The focused production broker control
`retained_prepare_32_renewals_requires_explicit_shared_revoker_trust` runs both
reviewed shared-identity and distinct local-identity cases through thirty-two
actual coupled renewals and expiry. Shared trust accepts the authenticated
cleanup projection; distinct receiver trust rejects and leaves the receiver
snapshot unchanged. This is host authority evidence, not physical Stop, whole
close or a claim that policy changes can recover a retained live transaction.

### Proof-bound warm Activity rollover

A successful typed whole-product close now hands the attached Activity an opaque
process-owned rollover token after the persisted full-cleanup fence and all
existing physical barriers. It binds the actor epoch, app/native/attachment
generations, runtime and enrollment digests, and final receipt digest. The
Activity reconciles its cached bootstrap future and attachment only on its UI
thread, after the completed future matches that token and the process host
still owns the issued token in the closed state. A successor attachment
invalidates the token. Pending cleanup, `NEW` alone, a replaced/failed future,
other actors or runtime inputs, and ordinary `onNewIntent` cannot clear the
cache. Rollover does not bootstrap, authorize media, replay Stop or retire a
process. The existing no-media close path keeps its separate behavior.

`tools/checks/Test-WholeCloseActivityRolloverHost.py` compiles the production
token and current-host predicate and checks incomplete/different proofs and
actor/attachment/runtime drift against a modeled process fence. Source joins
check the actual callback ordering and UI guards. Retained completion and
remote-owner completion controls remain applicable. Actual Android Java/Kotlin
entrypoint compilation is a separate source qualification; host checks do not
qualify a packaged or installed app, or physical device cleanup.

### Retired source refresh during control changes

Each common-graph iteration captures one immutable control snapshot for its
retired imports and subsequent recording. If controls change while refreshing
retired imports, the refresh clears both retired bindings and defers that frame
before resetting the signaled fence. The next iteration refreshes current
controls. Deferral yields and returns to the loop's cancellation check. This
exact normal control race does not terminate the renderer;
pending retirement, unavailable/invalid control, source binding and native
resource errors keep their fatal behavior. No source owner is restarted and
no command or media effect is replayed. Neutral mask participation remains
both complete six-stage banks on the concurrent source-set path.
The successfully refreshed full guide-processing policy also stays with the
imported bytes through WSI acquisition and recording. Recording checks its
stored snapshot/import consistency rather than comparing mutable current
controls after acquisition. Pending fence, nonzero request revision, exact
imported revision and source-key checks remain mandatory. The existing scoped
processing policy applies that snapshot to bank stages; the next frame adopts
later controls. There is no post-acquisition retry/continue.

The ordinary `Test-SpatialCameraPanelAndroid.ps1` route invokes
`tools/Test-StereoSourceRefreshHost.ps1`, also reached by `check_all.ps1`.
It uses a retained bounded pre-change fixture with no history/worktree lookup.
`tools/checks/Test-StereoSourceRefreshHost.py` compiles that baseline
and candidate refresh bodies with modeled retired imports, then executes the
actual candidate loop/control-policy seams. It reproduces baseline termination,
checks exact-race deferral versus physical failures, rejects unretired imports,
and verifies coherent mask/revision recording when a control changes after
refresh. The pure production classifier and mask tests also run. These checks
prove source behavior, not which error stopped an installed renderer or physical
Android/GPU recovery. The current worker only logs terminal errors; counters
and terminal cleanup remain observable after that bounded log history expires.


## Separate debug renderer and CPU configuration observations

`renderer_terminal_status` is a shell/DUMP-gated read-only operator method.
Its closed `rusty.quest.stereo.renderer_terminal_status.v1` receipt joins the
current app actor context to a native same-process source graph sequence. It
retains the first bounded failure phase/category and physical carrier cleanup
observation. This is neither media authority nor a full cleanup receipt. Late
callbacks cannot overwrite a finished graph or its successor. A new graph
sequence begins only after the existing physical renderer owner claim succeeds;
the observation never authorizes recovery or transfers authority.

`blend_config_read` uses the separate closed
`rusty.quest.stereo.neutral_mask_config_receipt.v1` receipt. Native actor,
challenge, arm, runtime, feature and installed APK inputs are checked before and
after one atomic mask/policy copy. Retired source banks may still expose their
CPU configuration with enabled header zero. The receipt has configuration-only
scope and carries no GPU frame, pixel readback, readback request, media authority
or cleanup claim. Existing blend read/update and fixed 160-word contracts retain
their separate adoption requirements.

The ordinary `Test-StereoSourceRefreshHost.ps1` route registers native terminal
latch and CPU source binding controls; its explicit Kotlin compiler/runtime
classpath options run the actual CPU producer/accessor controls. The separate
terminal host script selects explicit Java/JSON dependencies for its actual
projection controls. Target JNI/Java/provider compilation and artifact adoption
remain separate qualification steps.

### Known-birth no-journal disposition

The separately closed `rusty.quest.embedded_duplex.abandonment_authorization.v3`
selects an original actor whose PID and Linux start ticks were recorded. Both
peers attest complete package/UID absence. Each preserved original ARM receipt
joins its native vector, app epoch and exact Pending fence; a typed original
birth receipt joins PID/start/UID, epoch, package APK, device and boot. The
current effect-free recovery actor must be the direct next app generation and
must match the current installed APK and fresh fence. This branch rejects
Start journals and never relabels a known birth as historical unknown evidence.

Archive-first preservation and the existing clear-fence commit witness apply
to exactly the two replay maps, cleanup preparations and native counter. Only
byte-identical archived replay maps project as empty after a verified witness.
A newly written map remains visible; sidecars, drift or a missing witness fail
closed. The native counter is preserved and never projected, reset or deleted.
Other durable files remain unchanged. This disposition proves neither the old
physical cleanup nor new admission, renderer participation or media readiness.
Fresh bootstrap, signatures, resources and Start remain separate actions.

The ordinary process-fence host gate registers production transaction and
crash controls. Its optional `-KnownBirthFixture` input also exercises preserved
owner bytes supplied by an adopted external workspace. Fixture execution still
models current actor and external absence; it performs no headset cleanup.

## Failed initialization observation

The existing debug `owner_failure_diagnostic` read also supports an initialization
failure before native host publication. Its native v1 envelope contains a versioned
`initialization_failure.v1` observation with a process-local attempt number,
runtime digest, app-record digest, app/native generations, and a closed static
reason code. Provider initialization preserves only a fixed code for the typed
`QuestBrokerRuntimeError` category, without formatting its payload. Unknown
variants and error strings are redacted; configuration bodies, keys,
credentials and exception messages are never exported. This is observation only,
not authority, successful host construction, or cleanup certification.

The existing process mutex owns this record. Accepted initialization clears the
previous record before construction; successful route cleanup removes it. Reads
require the current live capability, matching staged digest and app record, and
no initialization/close in progress. The live app-source callback runs without
either native mutex held; the same attempt and route are revalidated afterwards.
Java additionally joins the current FAILED
host's held runtime and app-record digests. Published-host diagnostics keep their
existing callback path. Capability loss and panics do not fabricate a readable
failure. The existing pending fence and cleanup obligations are unchanged.

`tools/checks/Test-InitializationFailureDiagnosticHost.py` executes the production
state, initializer, diagnostic and cleanup-commit bodies, plus the Java owner
method, with modeled capability/host/JNI inputs. Typed provider payloads are
modeled in this harness; actual enum compatibility is checked by the native
Android compile. No cleanup prerequisite outside the commit block is simulated
as an actual platform observation.
The Android-target native typecheck is separate; neither proves installed behavior.

## Retained source graph readiness

A claimed source renderer now tracks Pending, Ready, Failed and Closed separately
from worker liveness. Only completed renderer setup publishes Ready. The initial
caller and same-carrier reuse both require that readiness, the current exact Own
epoch/window, a live uncancelled worker and the original claim. A late waiter
cannot cancel or release a successor that reused the same route generation:
cleanup additionally joins the exact shared startup identity.

The source graph readiness wait is fifteen seconds, independent of the existing
five-second seed wait. Together with Java's at-most-ten-second freshness stage,
it fits nominally within the existing thirty-second serialized projection stage.
These are waiting bounds, not proof that an effect failed or physically retired.
Cancellation still joins the original worker and verifies its existing physical
cleanup. A delayed ready signal cannot turn a caller's failed/closed startup into
success, even while that join is in progress. Setup failure, receiver closure and
startup deadline remain distinct internal outcomes; existing bounded renderer
and bootstrap observations remain observations rather than media authority.

Focused host controls exercise the production readiness state and the source
starter with modeled renderer/OS effects. Android compilation and actual camera,
GPU, display and bilateral media behavior require their own package/device proof.
## SourceBound root re-establishment

The opted-in SourceBound fresh policy selects an actual ongoing Peer owner and
registers its root issuer against the exact packaged media client grant and outer
lease during pristine construction. The fixed `reestablish_peer_roots` command
accepts `{}` only and executes as a new retained operator Original.
`peer_root_status` is read-only and returns actual independent grant/control
lease/Runtime Host lease facts. A status read supplies no media rights.

Physical ARM binds the current process, configuration and renderer lineage; it
is separate from current Broker or pair authority. A late physical ARM does not
make an expired root current. Establish a genuine fresh pair first, re-establish
any expired retained Active roots through their actual owners, then run the full
current media-admission checks. The operation supports actual NeverStarted and
genuine terminal normal Peer Stop lifetimes while independent Own preview
remains live. Pending Start, renewal, fresh phase, unresolved owner work,
revocation, source loss or a changed actor/config/ARM refuses.

Each root component that remains current is preserved exactly. Only expired
retained Active components change, with explicit new owner receipts; at least
one change is required. The old expiry, prior receipts, lifetime counters and
Original outcomes remain retained. No re-ARM, bootstrap, authority clone or
capability expansion occurs. Explicit DesiredStop latches only the exact entered
root Original, without a HostLease or second media writer. It is checked before
plan and immediately before commit admission; Stop after that boundary does not
rewrite an entered result or claim physical cleanup.

A successful constructor retains its exact runtime-configuration binding. Partial
construction is a separate retained-owner cleanup journal; an absent Host or
missing successful-constructor receipt is never proof that no resources/floor
were created. The distinct partial-construction owner protocol must account and
join those obligations before cleanup certification.

The inherited pair/session 235-second and credential/root 300-second windows are
actual strict validity policies, with no functional duration rationale
established by this change. Pre-Start recovery does not cover authority expiring
inside an already-entered SDK/resource wait. That path needs owned resource
preparation before final time-bound admission/activation, and cannot be claimed
complete from warning-only waits or a successful ARM.


## Resource preparation before final media admission

The selected SourceBound ongoing product uses the explicit retained
`prepare_peer_start` operation on both actual peers before final pair/root/media
admission. It owns one nonclone resource reservation before any fallible SDK
callback. The constructor-bound target records the actual product owner rows,
runtime specifications, source dimensions, actor and ARM. It is not an SDK-ready
receipt. Actual encoder output format/CSD, dormant decoder startup and dormant
renderer allocation are earned separately. All outgoing packet/header delivery
and incoming projection delivery remain gated. Independent Own preview may
remain live; a prepared Peer owner is not falsely reported as terminal absence.

RQPS1 signs the actual ready-stage metadata under a dedicated SourceBound
domain, current immutable selected keys/source, reciprocal target and explicit
Stop fences. Audit timestamps remain real and nonfuture; there is no estimated
age or setup-expiry window. The metadata grants no media authority. A verified
incoming frame waits for a genuine counterpart local reservation without
auto-creating SDK resources or holding a Native HostLease across that wait.
Every handler remains owned and joins on explicit Stop. An old consumed or
terminal preparation cannot answer a new cycle's view as a fresh reservation.

After both preparations finish, establish the actual current bilateral pair,
re-establish expired retained Active Broker roots where required, and perform
the complete current media admission. Ordinary signed expiry, revocation,
route/source, floor and exact actor checks remain strict. Start consumes the
same prepared local resource owner once; opposite-direction activation is a
separate owned effect. Ready resources do not authorize a second Start or
replay an UNKNOWN Original. The first real decoded-frame AHB import is inherent
later frame work, not a prewarm readiness claim. The inherited validity
durations remain explicit unresolved policy choices; this staging change does
not establish their functional rationale or waive later currentness checks.

SDK entry without a returned receipt remains UNKNOWN with the actual owner
retained. `abort_prepared_peer_start` closes and joins that exact SDK owner; it
does not infer absence or repeat the effect. Completed Stop and explicit abort
metadata transfer to immutable app-owned durable custody before a new resource
reservation replaces the old slot. The old Original outcome remains unchanged.
Whole-close can transfer a current cleanup abort without claiming the executing
cleanup Original has already succeeded. Normal media whole-close uses the
versioned full owner-closure custody before dropping its last actual gate.
Only the fixed retained whole-close continuation may resume an interrupted
closing handoff; ordinary operations remain fenced and Stop is not reenacted.

Host controls use modeled Android SDK leaves with the actual nonclone resource
owner and wire codec. Full bilateral Native/provider/Java Start overlap and
Android SDK behavior require their own coordinated qualification. Syntax and
apply checks provide no runtime, device or full-media validation credit.


## Retained operator originals and fresh-owner retirement

The selected newer debug operator transport requires an `original_id` before
each typed effect. Challenge remains a correlation field. The app records the
exact canonical request before invoking its existing effect handler; repeated
identity/input observes the same retained Future/result and changed input
refuses. Fixed `operation_query`, `operation_ack`, `operation_stop` and
`operation_join_status` calls accept only their closed typed fields under the
existing DUMP/shell guard. They accept no arbitrary JSON, component, path,
native operation, role, key or authority. Readonly status calls retain their
existing v1 projections. ACK transfers data custody; it proves no cleanup.

Ordinary observer/startup/frame/dispatch estimates publish warnings while the
same actual producer/Future remains owned. Actual signed expiry, frame identity,
Stop, supersession, structural failure and resource bounds remain strict.
An actual native lifecycle Pending result is retained UNKNOWN even when its
Java Future completed. Query and estimated delay never replay an effect or
erase its partial effects. `operation_stop` targets only that existing original
and its owned transport; it proves no physical cleanup or remote resolution.

Fresh-policy selected native lifecycle v4 and pair-status v2 are deliberate
closed projections. Existing finite/native v1/v2/v3 field meanings remain.
Current-operation failure causes come from the actual native producer; historical
pair status is not substituted for that cause. A new explicit
`fresh_pair_continue` wrapper original binds a durable joined UNKNOWN
predecessor and the same native setup request. It is a separate effect; readonly
query/duplicate input never continues a transaction or overwrites its outcome.

Whole-app close selects its no-media route from the actual readonly native
classifier. A no-media candidate is not final proof. Retained cleanup joins the
actual Own camera/session/GL producer, Peer resources, renderer, control tasks,
outbound exchanges, native retirement and app fence. Callback unregister/drain
is asynchronous and followed by the actual command-lane marker. Native runtime
absence does not manufacture Java ARM or physical camera absence. Per-original
join receipts require the actual Future and publication callback to finish and
bind the exact canonical physical retirement proof. Host/source/external capture
joins and process EOF remain separate owners.

Native completed lifetime/fresh-phase metadata moves only after fsynced,
identity-bound custody ACKs. Heavy Java originals additionally require actual
consumer delivery/custody and operation joins; unresolved or unconsumed originals
cannot disappear due to elapsed time/count. Native pending fresh-authority
closure and terminal fresh-owner retirement use distinct durable tagged stores.
Both fence later writable bootstrap until supported same-owner reconciliation;
neither reconstructs a pristine owner or clears replay/floor history.

Host/unit/socket controls model protocol and owner boundaries only. Android/JNI,
physical camera/GL cleanup, APK deployment, network streaming and source EOF
require their own selected artifact and owner evidence.

### Old process scopes and new local admission

An immutable UNKNOWN Original remains queryable and prevents same-ID replay.
Its historical process scope does not block a new local admission after the
existing fence owner has authentically disposed that exact scope. Admission
accounting uses only the exact Original actor's app-record digest, the current
package/UID, and either a verified committed nonaccepting abandonment archive
or an observed local process termination followed by a permitted clear-fence
commit. Current, foreign, live, uncommitted, corrupt or ambiguous scopes remain
blocking. Readonly calls with no current Original count unresolved disk rows;
two absent object references are not a current-operation exclusion.

Future v1 abandonment writes the same immutable `committed-fence.raw` witness
already used by newer abandonment routes. Existing archives are never rewritten
to add evidence. Automatic incarnation retirement additionally retains its clear
commit witness only after the existing no-remote-record predicate permits that
transition. Mere PID absence or the presence of a termination receipt alone is
insufficient. Existing conservative remote-record/sidecar guards remain; archive
and metadata history is not inferred terminal from its filename or age.

This changes local unresolved accounting only. Original rows, state, outcome,
request, consumer retention, signed authority, remote UNKNOWN, replay history,
native floors and writable-restore fences are unchanged. It does not manufacture
a graceful cleanup join, consumer ACK or authority transfer. A genuine remote
obligation still requires its existing owner reconciliation or explicitly
authorized nonaccepting disposition. Host controls execute the production
archive/fence classes and all three counter methods with modeled kernel facts;
Android API compilation and the actual app/package build remain separate gates.
