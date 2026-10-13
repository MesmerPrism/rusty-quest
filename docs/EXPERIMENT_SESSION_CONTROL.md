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
