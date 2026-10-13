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
