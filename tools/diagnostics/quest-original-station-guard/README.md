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
