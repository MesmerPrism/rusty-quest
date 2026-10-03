# Original station guard diagnostic

This is a separate original-only compensation helper. It reuses the accepted
shell UID bootstrap and saved-profile projection; it never creates or deletes
a Wi-Fi profile and accepts no credentials or arbitrary shell command.

The closed config has exactly `run_token` (fresh 32 lowercase hex), `serial`,
`boot_id`, `original_network_id`, `owner_address`, `local_owner` (true/false),
and `deadline_elapsed_realtime_ms`. It lives at
`/data/local/tmp/rqosg-<run_token>.properties`, staged by the caller with mode0600.
The caller must bind exact DEX/source/config hashes and both canonical devices.
The diagnostic group name is `DIRECT-rp-<first20hex of run_token>`;
a static shared group name is never authorized for compensation. The app requires the matching guard_run_token intent extra; both sides derive exactly the same closed name.

`snapshot` reads hashes only. `arm` requires effective original station A,
absent group and idle discovery, and framework NETWORK_SELECTION_ENABLED / DISABLED_NONE. It creates a new baseline state recording the arm monotonic time and a raw-byte configuration SHA256. `guard`
rechecks those facts, runs independent of the diagnostic app and creates a
new readiness marker containing PID, kernel birth ticks, boot, serial and
monotonic deadline. The caller must observe BOTH fresh readiness markers before
any topology effects. Callback work uses a separate HandlerThread Looper;
the guardian wait does not block callbacks. At deadline it restores, unless an
exact same-run completed marker already exists. `restore` performs an early
explicit compensation using the same checks and a nonblocking process lock.

Restoration removes only the fresh nonce-derived network + exact owner MAC +
local role. It requires successful remove acknowledgment and a subsequent
effective absence readback. Discovery stopping is allowed only for the client
when that owned group was observed; unexplained discovery remains unknown.
Before every restoration effect, the original profile's exact framework
projection excludes only selection status/disable reason; all other projected
fields and every unrelated profile remain locked. This allows its own P2P
interruption to disable original selection without blocking compensation.
Then the helper enables only the existing original station ID, requires actual
Wi-Fi IPv4 association, NETWORK_SELECTION_ENABLED / DISABLED_NONE, and joins
the COMPLETE captured profile hashes again before marking restored.
Fresh arm/guard starts require a future deadline bounded to180s; explicit
restore may run later on the same boot with the exact armed state, whose
original deadline-minus-arm duration is still checked. Expiry never admits a
new formation or arm, and completed restoration cannot be replayed. Foreign groups, rejected/missing callbacks or baseline drift never
produce success. An unknown outcome requires read-only reconciliation; do not
retry topology effects or broadly clear groups.

The old temporary-B helper and all accepted binary bytes remain unchanged.
This source has host compilation/contract evidence only. No Android device,
DEX runtime, callback permissions or original-station restoration is yet
qualified. The app integration supplying this exact fresh group name is a
separate required slice. Existing immutable fixed-IP watchdogs must be
suspended via their owned stop/PID-birth contract before topology change;
keep-awake intent remains indefinite. Restore serial-verified endpoint mappings
and resume 300-second/no-end watchdogs only after both stations are restored.

## Explicit signed short-echo mode

The Pair app's `require_peer_session_authorization=true` mode consumes the actual
owner topology receipt, local peer, complementary role and exact revision. It
rechecks that tuple and both wall/elapsed expiry at formation/discovery/connect
admission, callback continuation and native dispatch. `guarded_echo_timeout_ms`
is closed to1..20000; omitted means20000. Startup requires strictly more than
20seconds formation + selected echo timeout +10seconds cleanup in the actual
remaining signed window. These are conservative budgets, not measured proof
that device formation will finish. Owner receipts over60seconds are rejected in
this narrower diagnostic mode; no caller extends their expiry.

Guarded native sockets are nonblocking, revalidate the exact role/revision and
signed expiry before every accept/connect/read/write attempt, and cap the whole
native exchange at the smaller echo/remaining wall-and-monotonic budget while
reserving10seconds for cleanup. Partial failure byte counts are retained. This
is one no-media echo, not a DirectLaneLease or continuous90second authorization.
Legacy diagnostic entrypoints retain their ABI and make no signed-owner claim.

Owned cleanup and original-only station compensation are independent of expired
authorization: expiry denies new topology/socket traffic, not exact-owned group
removal/restoration. Platform callback uncertainty, foreign groups and dropped
transport still require retained read-only reconciliation; no automatic retry or
generic group clearing is permitted. Actual hardware MAC/readiness, guardian
markers, source/binary provenance and genuine operator enrollment/signatures
must be obtained before a physical trial. This source change alone proves none
of those device prerequisites.

## Closed shell-UID device inventory

`device-info` is a separate inventory entrypoint, not guardian arm or formation.
Its exact four config keys are `run_token`, `serial`, `boot_id`, and
`deadline_elapsed_realtime_ms`, with a future deadline at most30seconds away.
The path is exactly `/data/local/tmp/rqpi-<run_token>.properties`; no configured
owner MAC, network or role is accepted. UID2000 and the actual canonical serial,
boot and unchanged raw config are required.

It reuses the existing shell attribution/channel bootstrap, reads P2P state,
absent group/idle discovery and actual device info twice, and rejects redacted,
invalid or changed addresses. It requests no group, discovery, connect, Wi-Fi
radio change or profile mutation. The owned channel is closed and callback
thread stopped in finally; Wi-Fi state before initialization/after close is
retained and must match. Null observation fields remain unavailable.

Opening a framework channel can itself enable/initialize P2P service state.
Do not label that as a proven effect-free read. The parent must retain actual
raw service/station snapshots before initialization and after owned channel
cleanup, classify any state transition faithfully and reconcile it without
generic radio/group clearing. This inventory proves only an observed MAC;
owner authorization, group readiness and restoration qualification are separate.

## Opt-in actual formation cleanup admission

The unchanged armed configuration still records its inventory owner address.
Absent a formation receipt, restoration retains that exact original MAC guard.
A caller may use `admit-formation` to create a separate, immutable
`<config>.formation` cleanup receipt for a newly randomized actual GO address.
It does not rewrite the configuration or authorize formation, sockets, media,
or a DirectLaneLease. Unknown, malformed or mixed receipts deny restoration.

The closed `<config>.formation-input` keys are `run_id`, `run_token`, `serial`,
`boot_id`, `pair_apk_sha256`, `guardian_dex_sha256`, `source_revision`,
`source_tree`, `pid`, `pid_start_ticks`, `go_serial`, `go_boot_id`, and
`go_receipt_sha256`. All these temporary files require shell ownership, mode0600,
fixed run-derived paths and create-new staging. Source revision/tree are
caller-bound publication provenance, not an on-device Git verification claim.
The caller must authenticate their exact source/APK/DEX build closure before
staging; the helper checks actual installed APK and its own DEX bytes.

The Pair `formation-observation` provider accepts only shell UID2000 with DUMP,
`current-formation`, no argument, and exactly `run_id`/`run_token` extras.
It invokes the current in-process activity callback, rereads the Android group,
and reports its admitted lifecycle group, actual boot, PID/kernel birth and
monotonic observation. No last receipt is cached or restored after process
restart. Failure, cleanup, destruction or lost/mismatched group clears or denies
observation. Guarded owner authorization must still have remaining validity.
Provider reads perform no formation/discovery/profile/radio writes.

Guardian joins the exact provider package/APK, live source run/token/role,
current process cmdline/birth, same boot, actual current group and locked
baseline. It rereads the provider and group after profile checks before writing
the receipt. A local GO can admit its cleanup immediately without waiting for
a client, allowing partial-start compensation. A client additionally requires
`<config>.formation-go`, the exact GO receipt carried and authenticated by the
caller from the canonical GO device. Its raw SHA must equal the declared
`go_receipt_sha256`; run, boot/serial, source/APK/DEX and actual complementary
owner/network must join. This is a caller-authenticated device carrier, not a
new cross-device cryptographic signature or a topology authorization boolean.

Restoration can consume that receipt after authority expiry solely to compensate
the exact same group; config/state/DEX raw hashes, serial/boot/run/network/role
and observed owner MAC remain locked. No receipt allows arbitrary first-group
learning. All original profile, prefix and callback acknowledgement/absence
checks remain in force. The caller must archive and remove these extra exact
owned members only after actual restoration; missing or changed members remain
unknown. Host tests and JVM compilation against Android35 API classes do not
prove Android DEX/desugaring or device runtime qualification.

### Opt-in autonomous formation prestaging

`guard-with-formation` preserves the original armed baseline and 180-second deadline. Before READY, stage a closed live-derived input with `process_binding=current-source-provider` and no expected PID placeholders; all run/token, serial/boot, source, APK and DEX pins remain mandatory. The guard observes an actual running exact-package actor, UID and `/proc` birth, then the live source provider and current group. It seals one create-new cleanup receipt only after both provider reads, actual actor/group, original profile and final input pins agree. This is cleanup attribution, not a topology or media grant. Default `guard` and v1 one-shot `admit-formation` retain their existing behavior.

Client prestaging may declare `go_receipt_sha256=pending-authenticated-carrier`. It does not admit a future group or invent a digest. The parent must authenticate the actual local-GO receipt, stage its complete canonical bytes in `.formation-go`, then publish a create-new `.formation-go-binding` containing only its actual SHA256. Both use the existing owned-file UID/mode/symlink/size guards. Payload alone is explicitly incomplete; descriptor without payload, malformed bytes, changed input, unexpected existing output or changed sealed receipt deny. Host staging must never expose a partial file as complete. Remote carrier authenticity remains the explicit authenticated parent boundary, not a new cross-device cryptographic signature.

Before the Pair actor exists, observation is unavailable without a provider call. A process may exit between the process read and Binder call; this is not an atomic guarantee that Android never creates a provider process. Missing live formation is retried only through the closed source unavailable result; malformed or foreign state aborts. No cached observation becomes accepted. Already sealed receipts retain exact compensation identity after expiry; no new admission begins at the original deadline. Source-only host cases and JVM/Android API compilation do not establish DEX/runtime timing. Prestaging removes synchronous host admission calls but does not prove the remaining GO capture and client launch fit the unchanged 45/60-second guards.
