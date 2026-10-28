# Embedded duplex finite authority and native fencing

This is the source contract for the renewable-authority candidate. The current
operator route remains pre-Start. Native fencing, compilation and host checks
do not establish camera, codec, reciprocal rendering, renewal or teardown.
See [the retained cleanup contract](EMBEDDED_DUPLEX_R4_RECOVERY.md) for the
remaining app transaction and restoration obligations.

Explicit current debug mutations can use [Original custody](EMBEDDED_DUPLEX_ORIGINAL_CUSTODY.md)
for durable begin/query/ACK observations and a process-incarnation companion on
the existing command lane. The default no-ID finite/direct route remains
available. Original completion/ACK never replaces the physical cleanup or
runtime authority proofs below. The separately selected
[fresh credential setup provider](EMBEDDED_DUPLEX_FRESH_SETUP.md) adds typed
fresh phases and lifecycle-v4 joins; owner succession remains separate.

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

### Observation of a cold-restored Pending fence

Operational-run inspection uses `nativeInspectionDirectory` and the existing
`nativeInspectionRecord` callbacks. Both verify the actual live held writer and
exact persisted record, including when acquisition retained Pending recovery
state. Admission keeps its separate `nativeFenceDirectory`,
`nativeAdmissionRecord` and `requireFresh` gates. Readback never clears Pending,
grants a capability or changes a run selection. Native registry, resource-owner,
stale-handoff and changed-record refusals remain actual inspection results.

The successor operator declares `run_status.v2`: its closed fields are `$schema`,
`run_id`, `selection_generation`, `native`, `app_process_generation`,
`app_display_generation` and `app_fence_disposition`. The last field is exactly
`clear`, `effects_pending` or `recovery_required`; recovery-only disposition takes
precedence over the pending bit. Native inspection keeps its v1 schema. A Native
eligible result alone grants no app admission. A consumer must version-dispatch
the exact v1 and v2 DTOs; v1 has no app disposition and must not acquire an
inferred `clear`. Existing installed v1 producers retain their six-field schema.

Run `tools/checks/Test-EmbeddedDuplexProcessFenceHost.ps1` for actual Java cold
acquisition/readback and unchanged effect-gate controls. Run
`python tools/checks/Test-ColdPendingRunInspectionHost.py` for the complete
source-extracted JNI inspection caller/projection against explicit JNI, registry
and platform models. These host controls prove neither an installed JNI binding
nor physical cleanup/recovery. A dead old process does not need to join an old
in-memory callback, but any new local recovery must establish its own actual
retirement facts and preserve persistent counter lineage and remote UNKNOWNs.

Finite no-media Whole completion publishes its joined receipt and latest-Whole
pointer only after the actual Original callback, command stages and outbound
exchanges have returned. File-only restart readers version-dispatch closed
no-media v1/v2/v3 proofs, preserving exact physical bytes, actor joins and Native
counter floors. The production Native handoff parser independently checks the
same versions and terminal barriers. A retained source-run companion may support
cold recovery only when it matches the exact unretired structured Native owner;
the independently held current run remains the recovery output subject.

`Test-EmbeddedDuplexOriginalsHost.py --whole-publication` exercises completion,
join publication, ACK and reload with modeled physical owners. Its optional
`--captured-whole-row` reads captured evidence without rewriting it and runs
that exact response through a new modeled lifecycle. `Test-OperationalHandoffHost.py`
uses an explicit existing host dependency directory to compile the production
JSON/owner/handoff validators with real serde JSON and SHA-256, including an
optional Java-exported handoff. Kernel identity and physical commit remain
modeled leaves; these controls confer no historical ACK or device credit.

## Validation boundaries

### Prepared media readiness and retirement estimates

The incoming activation's readiness estimate is diagnostic. The same claimed
arm and revision continue waiting for actual acquired and GPU-retired frames;
the exact receiver/route/decoder/reader identities, 500 ms frame freshness,
current authority expiry and cleanup fence still apply. Stop, supersession,
interruption and malformed or foreign evidence remain real failures.

The selected prepared receiver similarly keeps its same decoder and delivery
latch past its startup estimate. Each wait checks the actual current-authority
supplier and desired Stop. A readiness estimate cannot invoke cleanup or
recreate a decoder. Prepared worker and callback retirement joins retain the
same threads until actual death, keeping resource and connection barriers.
Caller interruption during those joins is restored after the real join; it
does not manufacture terminal cleanup. The nonprepared compatibility receiver
retains its explicit startup and shutdown bounds. Transport read and codec
polling bounds remain separate from readiness.

A dispatched display lifecycle action has one retained future. Its estimate
warns without abandoning queued effects. An interrupted waiting caller drains
that same future and restores its interrupt status after receiving the actual
success or failure. It cannot free caller-owned SDK resources while its queued
action remains live. Main-thread blocking and a fenced lifecycle dispatcher
still reject before dispatch.

`tools/checks/Test-OwnedMediaWaitsHost.py` compiles the complete production
callers using an explicit SDK compile-command inventory plus JSON/JUnit host
dependencies. It checks delayed readiness and dispatch beyond the production
estimates, exact claim/freshness fences, actual desired Stop, interrupted
caller custody, real thread retirement and the legacy bounded path. Android
and SDK resources are host models in these controls; separate Android/SDK
typechecking and device evidence retain their own limits.

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

Ordinary remote owner ticket issuance consumes the current actor's privately held
prepared decoder receipt, whose sole writer authenticates the signed remote
view and validates the exact decoder request before preparation completes.
The lookup joins local Native generation, peers, immutable route configuration
and held preparation actor. It selects recipient placement identity only; current
media authority and the receiver's Native/Java registry checks remain separate.
Retained cleanup keeps its local source carrier and independently target-authored
Stop, including after prepared metadata retirement.

At the existing Native rejection log, `TARGET_EXECUTOR_GENERATION` identifies the
exact authenticated-recipient generation refusal. Each rejection also records
`error_sha256` and `error_bytes` from the original UTF-8 error before generic
mapping. These fixed-size observations permit offline comparison with source
literals even for a residual stage; arbitrary error, ticket, key or payload text
is not logged. The first-failure latch and generic caller rejection remain.
Ordinary JNI exchange failures have the finite `ORDINARY_JAVA_EXCHANGE_REJECTED`
code, separate from owner callback rejection. None of these observations supplies
completion, cleanup or admission evidence.

The first-error latch also emits `status=owner-effect-failed` with that finite
stage and original error fingerprint on Android, after releasing its diagnostic
mutex. This preserves local pre-dispatch proof failures for offline comparison
when no incoming dispatch boundary runs. Later compensation failures cannot
replace or relog that first cause. Host builds retain the same return/latch
behavior without requiring an Android logger.

The broker's `recipient_generation` controls join actual ticket issuance,
Composite placement, authenticated prepared metadata and signed remote dispatch
against asymmetric registry generations. The existing
`Test-RetainedOwnerDiagnosticsHost.py` additionally accepts `--host-deps` naming
provisioned host dependencies to exercise exact held-generation, retained-carrier
and rejection-log bodies. These controls model enclosing Native/resource owners;
they do not typecheck the Android native application or qualify device effects.

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

This host gate requires Cargo, a JDK (`javac` and `java`), and a provisioned
`org.json` JAR. Select the JAR with `--java-json-jar` or `RUSTY_QUEST_JSON_JAR`;
the existing owner Gradle cache is a fallback. A missing JAR fails before the
controls run and does not earn a skipped pass. Default aggregate registration
is deferred until dependency provisioning has an owner; invoke this focused
gate explicitly with its required inputs. Cargo can acquire its ordinary test dependencies;
use `--offline` only after provisioning the registry. The result records the
selected JAR digest. No Android SDK, Gradle build, APK, or device is invoked by
this focused gate.

## Opt-in ongoing source composition

The explicit artifact policy and its six build/inspection projections are
documented in [media policy selection](EMBEDDED_DUPLEX_MEDIA_POLICY_SELECTION.md).
The native adapter retains actual coupled owner checkpoints and independent
request epochs; [ongoing continuation](ONGOING_MEDIA_CONTINUATION.md) separates
finite, partial v1 and complete v2 histories. Camera source reuse now also
requires actual graph readiness, the same live claim and fresh original source
epoch. A thread alone supplies no readiness.

This source package adds no fresh credential/pair-setup feature, independent
Fresh development data session, remote-recovery campaign, APK or device result.
Those remain separate composition and product checkpoints.

## App-owned cold process recovery

`run_status.v2` separates the app fence from Native eligibility with the closed
`app_fence_disposition` enum `clear`, `effects_pending`, `recovery_required`.
Current-process Pending effects remain ordinary owner work. Cold-restored Pending
requires cleanup recovery. A restored clear phase whose exact Native handoff
never published also requires recovery after fresh verification that its old
process is dead. Read-only inspection uses the held-record/live fence check;
ordinary effect admission still uses `requireFresh` and every existing Native
and physical-owner gate. The installed `run_status.v1` contract stays separate.

The debug `recover_cold_pending` operation takes current `run_id`, new
`original_id`, and optional paired `prior_run_id`/`prior_original_id`. Durable
Native-owner mode omits both prior extras. Legacy migration uses both exact IDs
to read one retained Native-bound Original and the two named app companions.
There is no caller proof JSON, counter, process identity, cleanup flag or path.

New Native allocation stores counter, exact app admission record and the actual
kernel process birth identity together in `native_process_fence.v2`, in the
existing fixed lock inode and checksum-bound write before capability publication.
App initialization also stamps its current companion before allocation, including
non-Original UI entry. A crash before publishing the bootstrap Original therefore
does not lose the counter owner. A v1 counter supports only the bounded legacy
link; a missing or conflicting link is an explicit unsupported case.

Recovery keeps the app fence and Native counter fixed while it checks the exact
last writable companion, fresh boot/PID/birth retirement and actual current local
owners. It stops and joins current Own, display, resources, renderer, camera,
control, callbacks and outbound exchanges. Java callbacks run outside authority
locks. Native then reacquires its original lock and rechecks counter, companion,
owner and current kernel identity before retaining the new local retirement.
Only that verified receipt clears the app Pending phase and stamps the current
incarnation. Neither counter reset nor historical callback replay is involved.
Old Original rows, transaction UNKNOWN and remote debt remain unchanged.

Populated Start/evidence journals do not prevent local retirement. Recovery
captures their exact current and prior digests from the source-owned companions,
checks the bytes again before commit and handoff, and preserves every old row
and journal byte. The internal owner link v2 carries exactly those four digest
strings; callers cannot supply them. Same-run nonterminal Start still blocks
ordinary bootstrap. A new run uses its own journal scope and the newly earned
local handoff without pretending that the old media transaction completed.
Before overwriting a Pending global phase, acquisition durably retains its exact
predecessor in a checksum-named private file. Recovery reads only that named
predecessor when the companion checksum requires it, without scanning history.
The predecessor is fully written and synced under a unique staging name before
atomic publication; incomplete orphan staging bytes never become the named proof.

Dispatch/query/consumer/join/ACK retain the existing outer
`operator_transport.v3` Original flow. Its actual Future response is a nested
`operator_transport.v1` observed receipt of
`cold_pending_local_retirement.v2`, including the closed eleven-field
`cold_pending_native_retirement.v2`. The paired v1 codecs remain explicit
compatibility readers; mixed local/Native versions are rejected. The local
receipt keeps its seventeen closed fields. The current actor has a null Native
generation. Its app hash is the operation's Pending hash; the receipt separately
records the resulting clear hash. The actual new worker/callback/local joins use
`original_join.v2` with a null Native executor and the entire local receipt hash.
A restored row never gains an invented live Future or ACK join.

The app retains fixed current-run pointers to completed recovery and joined
Whole receipts. It chooses a validated closure with the latest Native generation
for the next selector/admission; a newer normal Whole supersedes an older
recovery receipt. Restart reads these exact owners without a history scan or an
ACK census. Interrupted recovery publication remains recovery-required, and a
new cleanup decision preserves the earlier UNKNOWN row. Staged pointer paths are
owned by their Original IDs so a dead writer cannot monopolize the next attempt.

The focused `Test-EmbeddedDuplexNewRunHost.py --cold-recovery` control executes
the actual Java recovery, Original persistence/query/join/ACK, selector, journal
guards and Native admission prefix. Its Native ABI bridge invokes the actual
Rust DTO projector and handoff validator. Separate Unix controls exercise the
real counter inode, owner write, process death, retirement and successor claim;
the full JNI module compiles against the real JNI crate. Android/platform and
physical-owner leaves in the JVM control remain models. The optional retained
witness manifest accepts exact source captures only as temporary test inputs.
This is source evidence, not APK, target-JNI or hardware validation.


## Owning local cleanup after partial Start

A carried `whole_app_close` Original first validates its actual current Java
actor and held attachment/resources. It requests both local media directions
once and retains their actual workers while decoder, reader, encoder, camera,
renderer and callback joins remain Pending. Physical joins run outside the
command lane so the callbacks needed to finish them can run. Observation
intervals emit warnings; they cannot free a live owner or certify a join.
Before local drain, the current registry fences new forward effects and joins
only callbacks actually executing in this process. Historical UNKNOWN records
remain unchanged. Late registry installation inherits the same fence. The codec
source-owner check and local leaf retirement share the existing lifecycle lock.
Local resource retirement grants no remote Stop readback or transaction result.
A failed or ambiguous logical Whole preserves the same Original and UNKNOWN.

Retained cleanup registries acquire their existing transaction admission before
checking durable uncertainty, carrying forward the ordering from `2c984675`.
Another thread's live persistence is joined; a failed commit remains refused
after admission. Same-thread callback reentry still returns `runtime busy`.
Construction snapshots and fresh-setup observations retain nonwaiting
`try_enter()` admission and check uncertainty only after acquisition, so a failed
writer cannot release stale state to an observer. The admission primitive,
durable-before-effect extent and existing authority checks remain unchanged.
`Test-RetainedOriginalEnrollmentHost.ps1` exercises the production registry,
persistence/state gates and actual admission primitive with deterministic
interleavings; JSON, durable Java callbacks, authority and providers are modeled.
These controls establish source ordering, not an installed storage-failure cause.

Native current-root lookup is separate from ordinary operation admission.
Whole validates the current carried Whole Original. Incoming owner dispatch
verifies the signed exact target before classifying Stop or retained
compensation for cleanup admission. Start and activation still require the
ordinary gate. Lookup alone, a stop latch, an expired Pair or caller data cannot
authorize an SDK effect.

The maintained two-principal cleanup projection, ticket, request and response
use component v3. Entitlement comes from the independently retained original
Start, exact target/counter, accepted original enrollment and current signer
status. Historical requester, Pair and topology expiry remain recorded target
evidence rather than renewable cleanup rights. Revocation, key changes,
foreign target/root and replay collisions still reject. The explicit v2 reader
preserves its finite legacy rules.

Remote authentication retains signed issuance, future skew and a finite horizon
selected by the accepted source policy. An omitted development policy keeps the
finite compatibility horizon. Cached Pending and terminal queries retain their
exact request/response identity beyond that interval. After transport
uncertainty the sender keeps the same commit bytes. Only an authenticated signed
`owned_cleanup_authentication_expired_no_effect` refusal, produced before replay
reservation or registry entry, permits a fresh envelope for that same cleanup
identity. It cannot refresh or remutate an uncertain effect.

New exceptional Original completion emits `original_failure.v2` at the existing
completion seam. Its seven closed fields bind operation, Original, fixed
`actual_future_completion` stage, direct throwable family and bounded cause
category. No exception message, stack, endpoint or credential is exported.
This retains safe failure classification, not a complete state-guard diagnosis.
The outcome stays UNKNOWN/uncertain. Existing terminal failure rows, outcome
hashes and ACK custody are read byte-exact and are never upgraded in place.
