# Polar connection and stream recovery

The optional BLE carrier can project an independent Polar stop capability `z`.
Its compact fields are `{r,a,e,s?}`: action revision and ACC/ECG requested,
PMD-ACK-active and recent-data bits (1/2/4). When `s` is absent, the existing
session `q.s` is a shared offering identifier salted by the full Polar owner,
selected/connected sensor and stream revision tuple. Session and Polar grants
remain separately admitted records with distinct RQEC2/RQPC1 HMAC domains.
An old session capability without `z` never authorizes a Polar stop.

The v3 wire is closed to `{v,id,op,s,r,n,mac?}`, only stop-acc/stop-ecg,
and retains the240-byte command limit. Maximum signed bytes measure171.
The app resolves the reference to its full original owner/action tuple, consumes
the offering before queued dispatch, and rechecks native epoch/profile/binding
before the originating Polar adapter's main-thread revision admission. No
owner factory, automatic retry, connection, recording or native clock change
is introduced. Carrier/owner/sensor/revision replacement retires old grants.

The full representative armed/audio-ready/Polar-connected status with both
independent grants measures452 bytes within the existing480-byte status cap.
Mandatory fields are preserved; the existing optional session-q overflow
fallback remains, and a Polar grant that cannot fit is cleared and omitted.
Full numeric maximum status representations still become unavailable under
the original cap. This is bounded parity in representable states, not universal
control availability. A BLE dispatched stop is not sensor ACK, physical
retirement, data cessation or recording finalization; the independent current
stream bits retain those distinctions. Frozen APKs remain unchanged and need
separate compiled package and physical qualification before adoption.

A successful app-owned connection enables continued connection intent for that
exact sensor instance. Transport failure retains this intent. Discovery during
recovery may not substitute another sensor, even when it is the only candidate.
Each connection attempt times out after 15 seconds; retries wait 2 seconds within
a two-attempt cycle and 10 seconds between cycles. Cycles continue while the owner
and connection intent remain active. Permission or radio unavailability does not
authorize changing those settings.

Intentional Disconnect and owner shutdown cancel connection and stream recovery.
Panel recreation cannot rearm an intentional disconnect; an explicit successful
Connect or a genuinely new process owner can begin a new connection intent.
Closing the panel only detaches presentation. Stop PMD cancels the selected
stream's resume intent; Stop all PMD cancels both PMD streams while keeping the
HR/RR connection intent. A stop before the first connection also overrides the
automatic PMD default. A new explicit sensor selection starts a new stream intent.

The retained stream intent expresses requested acquisition, not evidence of a
running stream. A failed START remains inactive unless actual incoming data proves
otherwise. Its requested intent remains available for a later connection recovery;
an explicit stop cancels it. ACC and ECG requests can both be restored after the
same sensor reconnects. Subscription and command acknowledgements remain distinct
from fresh samples.

If Stop arrives after START dispatch, its actual START acknowledgement is retained.
The owner then dispatches one STOP when the preceding write completes. The delayed
operation checks the current GATT owner and the selected stream's intent revision.
An ineffective or rejected STOP is unconfirmed and is not automatically replayed.
A successful matching STOP acknowledgement, rather than write completion alone,
marks the stream stopped.

The app-owned v2 status adds `connection_recovery_requested`,
`acc_resume_requested`, and `ecg_resume_requested`. These are intent diagnostics;
the existing running/data fields retain their effective meaning. The Polar
operator and status sampler consume named fields and allow additional fields.
The production compact phone-control status encoding remains unchanged.

Run `tools/checks/Test-NativeRendererExperimentPanel.ps1` for Android compilation
and production policy tests. Run `python tools/checks/test_polar_recovery_callbacks.py`
for extracted production callback, timeout, discovery, stop and restore decisions
with inert Android transport boundaries. These host controls do not qualify range,
physical reconnection, sample recovery, or simultaneous Bluetooth links; those need
separate device observations.

The selected Polar composition requests automatic acquisition from the exact admitted
NativeActivity MAIN/LAUNCHER cold launch, on the Android main thread. It does not
wait for a 2D experimenter panel or the selected breath-input source. Application
preparation, provider reads, internal routes and recreation do not create this
request. Missing permissions remain an explicit prerequisite; the visible panel
continues to own permission prompts and manual Connect remains a fallback. The
existing process-owner intentional Disconnect suppression also applies to this
request. Host controls qualify routing, not discovery or sensor timing.

## Projected individual stop controls

The panel's explicit Stop ACC and Stop ECG controls and
`tools/Invoke-NativeRendererPolarProjectedStop.ps1` use the same main-thread
Polar owner adapter. Existing spinner-based Stop PMD remains a compatibility
control. An explicit stop cancels only that stream's requested resume intent;
it neither disconnects HR/RR nor changes the automatic connection policy.
Before the first connection, an explicit stop of the other stream preserves
the latent selected default intent. It does not directly start a stream; the
ordinary successful connection/subscription path remains the starter. Legacy
selected Stop PMD still cancels its default before the first connection.

The CLI uses the existing DUMP-protected `PolarSensorCommandReceiver` and its
new bounded `polar_sensor_projected_request_b64` extra. It does not create a
provider, Activity, permission prompt or BLE capability. A request cannot mix
this extra with the legacy command extra. The closed request schema is
`rusty.quest.native_renderer.polar_projected_stop.v1`. Status contains only
`schema` and `operation=status`. Stop requests additionally require a unique
32hex offered operation ID, action revision, PID, runtime generation and 32hex owner lifetime epoch,
exact selected and connected sensor instance IDs, and both stream intent
revisions from the current projection. The owner epoch changes even if a PID
is reused or the Polar runtime is explicitly reopened in one process.

The main-thread projection offers operation IDs bound to the owner lifetime,
action revision and explicit stream operation. This is an idempotency namespace,
not an additional authorization grant. Admission consumes and advances the
action revision before touching the stream. The app retains one immutable latest
dispatch receipt. Its identical duplicate returns that receipt without another
effect. Older action revisions are cumulatively stale, including unknown
effects; an old ID cannot be moved to a new revision or another operation.
A throwing effect is not rolled back or automatically replayed, even if no
stream-intent change was observed. This constant-space highwater has no ordinary
action-count limit. Only exhaustion of the protocol's actual safe integer
representation prevents further admission. The scope is process-owner local;
a replacement gets a new owner epoch. It is not a cross-process command journal
or saved-session certificate. Retain original request/receipt bytes externally
when historical or unknown outcomes matter; the app stores only the latest body.

Run the CLI with `-Operation status` first. For a stop, supply the exact returned
owner/sensor/revision fields and its offered `-OperationId`; a changed field denies
before dispatch. `files/polar_sensor_projected_action_status.json` is a correlated
latest invocation projection, not a historical receipt archive. Its action
receipt says dispatched/not-confirmed separately from the current main-thread
projection. Requested intent, matching PMD acknowledgement and fresh sample
readback remain distinct. A status read neither scans nor connects, starts a
stream, changes the active experiment recording, or replays a stop. Unknown or
missing receipts must be preserved rather than retried for confirmation.
The panel shows rejected, unavailable, unknown or requested/pending feedback in
its existing status area. A dispatch never labels the stream stopped.
Each button retains its originating panel/runtime instance. A retired, closed
or mismatched panel callback cannot create a runtime or adopt a replacement's
fresh tuple. Its denial updates only the old view, never the replacement's
persisted owner status. The current owner's button follows the common adapter.

The CLI first sends exactly one existing read-only legacy `status` request.
Its exact token-correlated owner receipt declares the optional
`projected_stop_schema` and owner identity. A genuine correlated old status
receipt without the declaration is unsupported, so no new projected request is
sent. Missing, malformed, permission-denied or uncorrelated output stays unknown;
silence is not unsupported proof. A fresh correlated owner identity differing
from a mutation's expected scope denies it before dispatch. No extra status
request is sent to infer a later actor replacement.

Observation defaults to waiting until receipt or cooperative host cancellation.
`-CancelFile` stops only observation and never cancels the app operation; when
already set before dispatch, the action stays unissued. Optional explicit
`-ObservationSeconds` selects a finite total receipt-observation window. Its
expiry retains the original request and unknown outcome without replay or a
native kill. A window ending during support preflight leaves the projected
action unissued. A real available correlated receipt takes precedence over the
observer cutoff, and dispatch/STOP acknowledgement remain separate.

The existing ordinary panel gate registers production adapter controls and
Android API compilation; extracted production Polar callback controls cover
the explicit stream selection and retained ACK/intent behavior. These are
source/host qualifications only. This tranche adds no web acquisition command:
the existing session `qcap` and RQEC2 envelope cannot authorize Polar actions.
Any future web adapter needs its own reviewed operation/sensor/revision
capability and the existing server/HMAC/command-budget fences.

An idle or otherwise profile-unbound native session omits the optional Polar BLE
stop grant before parsing nullable profile fields. This retires an earlier grant
and keeps ordinary status available; it does not bind a profile, authorize a
sensor operation, or weaken validation of an offered bound-session grant.


## Native session acquisition hold

Sessions configured for Polar ACC require an accepted raw ACC sample within
one second, measured with native monotonic receipt time. The selected composition
owner publishes this requirement; read-only breath history, phase ambiguity,
calibration confidence, GATT connection state and browser telemetry cannot prove
fresh acquisition. A composition replacement or source change clears prior sample
proof. Once a recording uses Polar, changing or disabling its source cannot remove
the recording's Polar requirement.

The session worker checks input before official Start/Resume and on its own timed
loop, including after commands and writer work. Loss applies the ordinary native
Pause and durable recording control event once, stopping official active time at
the acquisition deadline. Acquisition retains interruption evidence if data returns
while the worker is busy, so that recovery cannot hide a missed hold. Neither render frames nor a visible panel/browser are
needed. Arm remains preparation and may run while input is unavailable. Other
input sources and default compositions retain their existing behavior.

Fresh input after reconnection only updates readiness. The session remains paused
until a fresh explicit Direct or authenticated Remote Resume passes the original
session, presentation and owner fences. Readiness is checked at request arrival
and again at execution; a request made without input cannot activate later just
because recovery occurred while it waited in the command queue. No retry or sensor
callback issues Resume.

Native readback exposes `polar_input` with `required`, `fresh`, `pause_latched`,
`reason` and `timeout_ms` (1000). Reasons include `awaiting-acc`, `acc-stale`,
`source-unavailable`, `fresh`, `recovered-awaiting-resume` and `not-required`.
The pause latch survives recovery until accepted explicit Resume or session end.
The native panel details distinguish waiting from recovered-but-paused.
BLE and LAN status optionally project `pi:{r,f,h}` for requirement, freshness and
pause latch. Old native owners omit it. This field is observation only and may be
omitted to retain the original480-byte BLE status cap; native command admission
remains authoritative. The connection/stream retry policy above is unchanged.

Focused host coverage includes the native `experiment_session_runtime`,
`polar_input_health`, `breath_composition_runtime` and `polar_composition_adapters`
tests, the production callback harness `tools/checks/test_polar_recovery_callbacks.py`,
the original capability/status codec tests, and the actual BLE journal callback
harness. These controls do not prove physical sensor radio loss or reconnection;
that requires a later attended test with a real sensor.
