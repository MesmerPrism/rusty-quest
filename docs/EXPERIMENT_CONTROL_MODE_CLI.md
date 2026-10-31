# Running experiment control mode

The opt-in debug operator can switch an already running selected native owner
between Direct and Remote. It uses the panel buttons' selection and presentation
handler. It sends no experiment Start, Pause, Resume or Save, and does not launch
or restart the app. The existing session, recording, elapsed clock and Polar
acquisition stay with their owners. The older APK without mode operator v1
(including installed59) reports unsupported; an APK update is required first.

Use PowerShell 7 with explicit current transport, canonical hardware serial,
boot ID, package and selected ADB bytes. Mutations also require the exact
installed APK SHA-256. The caller owns any required headset lease. Each invocation
needs a new output directory under an existing parent without reparse points.

```powershell
$target = @{
 Device = '<current explicit ADB transport>'
 CanonicalSerial = '<verified hardware serial>'
 BootId = '<current boot UUID>'
 Package = '<selected application package>'
 Adb = '<absolute path to adb.exe>'
 AdbSha256 = '<64 lowercase hex characters>'
}
pwsh -NoProfile -File tools/Invoke-ExperimentControlMode.ps1 @target `
 -Mode status -OutputDirectory '<new receipt directory>'
pwsh -NoProfile -File tools/Invoke-ExperimentControlMode.ps1 @target `
 -Mode remote -ExpectedApkSha256 '<64 lowercase hex characters>' `
 -OutputDirectory '<another new receipt directory>'
pwsh -NoProfile -File tools/Invoke-ExperimentControlMode.ps1 @target `
 -Mode direct -ExpectedApkSha256 '<same qualified APK SHA-256>' `
 -OutputDirectory '<another new receipt directory>'
```

The wrapper verifies `pidof <package>` before every provider call so an absent
process is refused. It reads fresh actor and owner fences, sends at most one
mutation, then reads that original request once. If the requested mode is already
selected it returns the inert status without selecting again. The directory
retains `target.json`, exact read/set/original request and receipt JSON, and
`result.json` or `failure.json`. A missing reply does not authorize a retry.

To reconcile the original ID from `set-request.json` without another mutation:

```powershell
pwsh -NoProfile -File tools/Invoke-ExperimentControlMode.ps1 @target `
 -Mode status -OperationId '<original 16 lowercase hex ID>' `
 -OutputDirectory '<new reconciliation receipt directory>'
```

The raw typed [operator](EXPERIMENT_SESSION_CONTROL.md#debug-operator) remains
available through `Invoke-ExperimentSessionControl.ps1`. New closed operations
use the existing `rusty.quest.experiment_session.operator_command.v1` schema:

```json
{"schema":"rusty.quest.experiment_session.operator_command.v1","operation":"control-mode-status"}
```

Status returns the existing operator receipt schema, `mode_operator_version:1`,
`operation`, `pid`, `uid`, `process_start_ticks`, `apk_sha256`, and `mode_result`:
`host_id`, `carrier_generation`, `native_owner_epoch`, `binding_sha256`, `mode`,
`mode_packed`, `mode_revision`, the native `session` readback and raw
`panel_visibility`. Only the selected live native controller can supply status.

`set-control-mode` has exactly these fields:

| Field | Value |
| --- | --- |
| schema, operation | Same schema; `set-control-mode` |
| operation_id | New 16 lowercase hex ID |
| requested_mode | `direct` or `remote` |
| operator_origin | `shell-experiment-operator-v1` |
| expected_pid, expected_uid, expected_process_start_ticks | Positive integers from the read |
| expected_apk_sha256 | Exact qualified APK digest |
| expected_binding_sha256 | Read binding digest |
| expected_runtime_epoch, expected_generation | Native session integers; generation may be zero |
| expected_session_key | Native key, or `-` when absent |
| expected_condition, expected_profile_sha256 | Native condition/profile, or empty when absent |
| expected_host_id | Actual controller instance from the read |
| expected_carrier_generation, expected_native_owner_epoch | Positive current owner integers |
| expected_mode_packed, expected_mode_revision | Positive native mode CAS integers |

The main-thread owner rechecks actor/APK, binding, actual host, carrier, native
session and mode fences at execution. Remote cancels pending local handoff and
old local actions remain revision fenced. Mode changes preserve the existing
guest revocation/discovery and Polar startup suppression/idempotence paths.

The set receipt's `mode_result` contains `operation_id`, immutable
`original_request`, and `state`: `pending`, `accepted`, `rejected` or
`outcome_unknown`. Acceptance retains `native_acceptance` with the execution
read `before`, selected mode/packed/revision and the exact new visibility request.
The in-memory journal is bounded to 128 original IDs; entries are never evicted
to permit replay. Repeated IDs return their original disposition even if the
caller changes the request. Process/receipt loss remains unknown.

```json
{"schema":"rusty.quest.experiment_session.operator_command.v1","operation":"control-mode-receipt","operation_id":"0123456789abcdef"}
```

This read adds `presentation.status`: `pending`, `unavailable` or
`acknowledged`. Native mode acceptance does not prove rendered visibility.
`effective_visible` appears only when the same host/mode and exact new native
visibility generation/request revision report an applied submitted frame.
Missing focus/renderer evidence remains pending or unavailable. A submitted
frame acknowledgement retains `visible_confirmation:false`; attended visual
confirmation is separate. No intent alone is reported as panel hidden/visible.

These operations remain debug-only behind DUMP and shell UID. They add no LAN
or BLE mode opcode and accept no arbitrary native bits, shell text or command.
Release builds omit the existing debug source closure and provider authority.

Host fixtures (explicit dependency paths required):

```powershell
python tools/checks/test_experiment_mode_operator.py --json-jar <json.jar> --android-jar <android.jar> --jdk-bin <jdk-bin>
python tools/checks/test_experiment_mode_runtime.py --json-jar <json.jar> --jdk-bin <jdk-bin>
python tools/checks/test_experiment_mode_cli.py --pwsh <pwsh.exe>
```

These run actual production selection/journal code and a child PowerShell CLI
with inert platform I/O. They do not establish physical input, rendering or
running-session device qualification.
