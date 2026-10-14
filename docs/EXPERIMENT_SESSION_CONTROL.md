# Closed experiment session controls

Controller, panel, debug CLI and Web Bluetooth controls address the same serialized
native session and official active clock. They do not fabricate a phase sample,
immersive presentation, frame, wearer result or acoustic observation.

| Operation | Controller / panel | CLI | Web |
| --- | --- | --- | --- |
| Arm selected condition and locked bias | Experimenter condition controls | arm with requested_condition/requested_bias | Existing v1 arm carries condition/bias |
| Official Start | Right Grip+A / Start official run | official-start | v2 start |
| Pause | Right Grip+B / Pause official run | pause | v2 pause |
| Resume | Right Grip+A / Resume official run | resume | v2 resume |
| Save and prepare next | Panel Save session & prepare next | save-next | Existing v1 save-next |
| Save and exit | Panel Save and exit | save-exit | Existing v1 save-exit |
| Return to immersive | Panel Return / existing B shortcut | return-vr | Existing v1 return-vr |
| Open Polar setup | Panel Polar | open-polar | Existing v1 open-polar |
| Status | Panel refresh | status | Status characteristic |

Pending bias is configuration for the next Arm, not an independent mutation of
an active session. Developer tuning, Polar scan/connect/stream controls and
private settings keep their separate existing typed contracts; this table does
not turn them into unrestricted session operations.

## Packaged remote VR preparation

`debug.rustyquest.native_renderer.experiment.preparation_mode` selects
`local-panel` (default) or `remote-vr` in the packaged native app settings.
The native owner installs this startup policy once; mutable Android property
readback cannot replace it. Unknown or absent values preserve the local panel.

In `remote-vr`, an authenticated cold launcher epoch is consumed after a
submitted immersive frame without opening the settings Activity. Recording
recovery still completes before readiness. Save Next retains its original
durable finalization, recorded reason, generation and operation receipt, then
returns to idle preparation with official active time zero and no panel route.
Java save acknowledgement admits both the local `show-experimenter` route and
remote preparation's `none` route. The remote route requires the explicit
`remote-vr` mode; both require a nonempty exact finalized operation, a positive
exact finalized generation, accepted operation status and the writer's saved
result. The coordinator retains its pending-operation, generation and newer
route-revision checks. A saved recording does not require continued XR focus.
The native worker and app owner remain alive for browser control. Arm and
official Start keep their existing audio, recording and frame prerequisites.

Status exposes `readback.preparation_mode` and `readback.vr_preparation`.
The latter requires completed recovery, initialized native state, idle phase
and an actual focused submitted XR presentation. Focus loss clears that
presentation; neither wake nor preparation observations start or resume a run.
These fields do not prove configured cold-boot kiosk enforcement or BLE radio
availability. Explicit maintenance panels and Save and exit remain available.

Focused validation includes the `experiment_session` and
`native_renderer_panel_bridge` native host tests, the runtime property parity
gate, and the Android native target check. Device qualification must separately
cover cold launch, two consecutive durable sessions, wake, BLE control
availability and deliberate maintenance return.

## Debug operator

EnableExperimentSessionDebugOperator is an explicit build-resolution opt-in.
It requires the selected breath CLI/panel closure and an explicitly declared
ExperimentSessionDebugControlProvider manifest component. Only those builds
include its two src/debug classes and package-scoped debug-experiment-control
authority. Builds without the opt-in omit both classes and authority.

The provider requires FLAG_DEBUGGABLE, DUMP permission and synchronous Binder
calling UID2000 before decoding. Its sole call is experiment-control with a
bounded base64 JSON argument and no extras. The request schema is
rusty.quest.experiment_session.operator_command.v1. Status has only schema and
operation. Mutations additionally bind unique16hex operation_id, expected PID,
UID, process start ticks, APK SHA, source binding SHA, runtime epoch, session
generation/key, condition/profile and operator_origin shell-experiment-operator-v1.
Arm alone adds requested_condition (condition-a/b) and requested_bias (0..100).
No current recording is represented by expected_session_key '-' and empty
condition/profile with generation0; native runtime epoch stays positive.

Invoke-ExperimentSessionControl.ps1 sends exactly one provider call after exact
ADB byte/canonical/boot checks. The caller retains its immutable request and
owns the exact headset lease. Queued is not applied. Status exposes persistent
native operator correlation and the existing owner receipt for legacy commands.
Replay IDs are denied for the application lifetime; there is no automatic replay.

### Privileged local trusted-LAN enrollment

The debug-only `enroll-trusted-lan` operation additionally requires Boolean
`trusted_lan_consent: true`, positive `expected_native_owner_identity`, positive
`expected_gateway_lifecycle_revision`, and `expected_enrollment_reference` from
that process's most recent privileged `status.local_enrollment` observation.
The offer expires after five seconds and is consumed once. A new status replaces
it; rotation, revocation and native-owner retirement invalidate it. Public
status/discovery, BLE and authenticated remote gateway requests never receive
this offer and cannot invoke this operation. Browser enrollment is a separate
grant and remains unchanged.

The existing actor PID/UID/birth, installed APK, activation binding, runtime
epoch and recording generation/key/condition/profile fences apply. The main
owner re-reads native state and pending work before rotation and before returning
credentials: it requires idle, no current session and no pending command. Native
owner identity and lifecycle revision must still match. A post-rotation owner,
session or expiry mismatch revokes the new keys and returns no credentials.
Replay IDs remain consumed even on denial; no retry is automatic.

The headset UI and local debug grant call the same random controller/key
rotation, private persistence, route retirement and reconciliation method.
This does not change selected permissions, origins or network features, create a
hotspot, or enroll at launch. The normal release closure still omits both debug
provider classes and their authority. A local grant is available only in an
explicitly selected debuggable build behind shell UID 2000 and DUMP permission.

For enrollment, `Invoke-ExperimentSessionControl.ps1` requires
`-SecretOutputDirectory` naming a new directory below an existing local parent.
On Windows it creates and verifies a protected current-user-only ACL before any
provider call. It captures both provider streams privately and writes the
credential-bearing receipt only to create-new `enrollment.json` inside that
directory. Normal output contains only the result and file path. Do not print,
log, publish or copy the receipt into ordinary evidence. Provider/transport
failure may leave the enrollment outcome unknown; retain the private output
location and reconcile explicitly, never replay the original request. The
response proves local enrollment/rotation only, not browser reachability or LAN
security beyond the explicitly consented trusted network.

## Web session capability

Optional q={v:2,s:32lowerhex,g:positive JS-safe native generation,o:[closed op]}
provides one state-appropriate start/pause/resume operation. The opaque reference
rotates with server/native owner/epoch, session/runtime epoch, generation,
condition, profile or source binding. Failed readback, pending work, invalid
shape or expiry withdraws admission. q.g must equal the status native generation.

The native readback represents recording by `phase=active`; it has no separate
`recording` member. Its active `condition_id` must be the actual string
`condition-a` or `condition-b`. An explicit null on idle clears the condition;
legacy idle status may omit it. Missing, null or mistyped active identity fails
closed instead of inheriting the previous Java view.

Arm confirmation joins the original browser/native mapping to its durable Arm
checkpoint, generation, runtime epoch and actual condition. A later presentation
or audio operation may become the last native operation without replacing that
checkpoint. The same proof remains valid if participant controls have already
advanced the current session to running or paused; it never confirms another
generation or owner. Observation after the ordinary shell deadline continues
only for an exact retained Arm/Save Next mapping on the live carrier/native epoch
and launch. Carrier retirement, replacement, cleanup or mapping settlement ends
that exception. Other commands keep their existing observation deadline. No
observer timeout authorizes replay or changes native writer durability.
The native `active` phase with `arming` control state is intermediate: the
process command observer continues polling through asynchronous audio preparation
until the checkpoint and armed/running/paused state agree, even without a panel.
An exact command rejection still terminates that command's observation.

The closed v2 envelope is {v,id,op,s,g,n,mac?}, at most240 UTF8 bytes. Gated mode
requires the first16 HMAC-SHA256 bytes of
RQEC2|challenge|id|op|s|g|nonce. Open mode retains its explicit existing policy.
The owner rechecks the full stored tuple and current native worker fences.
Confirmation requires the exact original native operation, generation/epoch,
identity, explicit origin and applied control state; queue/write acceptance alone
is not confirmation. Official Start retains the real immersive-presentation
requirement, and the official Tick/threshold alone determines completion.

UI Start/Resume retains one immutable tuple and operation ID through the existing
ReturnVR handoff. It polls genuine presentation for at most5 seconds; only the
same current epoch/session/condition/profile/source in ImmersiveActive permits
one native call. Drift, timeout or an unknown call outcome cannot retry. Pause
uses the actual running owner directly. The native reducer remains authoritative
for frame/presentation/clock admission; the UI handoff never synthesizes them.

## Historical recording validation and save timings

The recording actor owns an in-memory inventory of successfully validated
sessions. Startup always reads and validates historical manifests, terminal
records/checkpoints, identities, and stream contents. Subsequent Arm and save
operations may reuse historical results on Android/Linux only while complete
session-directory metadata snapshots agree before and after recovery. These
snapshots include every file name, type, device/inode identity, length, link
count, and nanosecond modification/change times. Same-length writes with
restored modification times still invalidate through kernel change time.
Recent files additionally carry SHA-256 content fences: a fixed cutoff two
seconds before recovery covers Linux/Android timestamp tick coarseness through
post-index acknowledgment. Hashing uses bounded buffers and verifies file and
path metadata around each read. Old histories remain metadata-only; this assumes
app-private custody and filesystem change timestamps with subsecond granularity.
A backward wall-clock observation during recovery or publication rejects that
operation. A digest aging out of the recent window forces full semantic revalidation
once, even with unchanged metadata and forward clock progress. Otherwise a
same-tick write after the previous observation could hide behind the transition.
Old histories then remain metadata-only; newly modified large histories may
incur one additional full scan when their recent digest ages out. Privileged clock/filesystem manipulation outside app-private
custody is not an immutability guarantee.
Added/deleted sessions and root replacement invalidate the corresponding
inventory. Symlinks and aliased files reject reuse; unsupported platforms or
nonregular entries fall back to complete recovery. Zero inode/change-time
fields and zero subsecond change times also force full recovery; the intended
Android app-private filesystem must supply meaningful nanosecond change time. No persisted index earns
trust, and failed validation never becomes a reusable entry. Kernel-privileged
mutation that can forge filesystem metadata is outside app-private custody.

Save always validates the current recording again, after draining the exact
accepted cutoff and synchronizing its streams and terminal record. It publishes
the index and checks the metadata fence once more before returning the saved
acknowledgment. An unstable observation clears the inventory and fails that
operation; it does not silently publish stale counts or automatically retry.
The existing corruption and interrupted-checkpoint recovery rules are unchanged.
The cache lasts only as long as the recording actor.

Native readback adds `recovery_work` with `elapsed_us`, `validated_sessions`,
`reused_sessions`, `metadata_entries`, and `recent_bytes_hashed` (both recovery
fences combined). It also exposes `finalization_timings`: `drain_us`, `durable_finalize_us`,
`recovery_us`, `index_us`, `fence_us`, and `total_us`, plus
`fence_bytes_hashed` for the post-index fence. All duration fields are measured
with the owner's monotonic clock, in microseconds; zero also represents no
completed measurement yet. These diagnostics do not alter durable-recording
schemas or certify end-to-end browser latency. After a successful save, the
readback describes the finalization recovery, including current validation.
Later recovery may update `recovery_work`. Finalization timings reset to zero
when a new finalization begins; a failed finalization leaves them zero. Full startup recovery still scales with history;
warm recovery scales with metadata entries and changed/current stream bytes.

Focused host validation runs the native crate tests filtered by
`session_recording` and `experiment_session_runtime`. The inventory race and
metadata tests require an actual Unix filesystem, such as Linux ext4 `/tmp`;
Windows fallback tests do not prove Android metadata behavior. The retained
12,655-checkpoint fixture reports metadata overhead without a wall-clock pass
threshold. Android target compilation and live app-private filesystem/readback
qualification remain necessary before claiming device latency improvement.
