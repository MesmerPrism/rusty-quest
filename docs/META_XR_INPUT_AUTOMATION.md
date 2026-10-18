# Meta XR input automation adapter

This opt-in developer component extracts the selected debug API-layer packaging
checks from the existing native/Spatial experiment adapters. It does not bundle
Meta binaries, enable a product, create an OpenXR session, start a proxy, inject
input through ADB, or supply command/session authority. The original application
keeps its sole XR owner and original widget/action handlers.

## Finite real Operator backend

`tools/lib/meta-xr-operator-backend.mjs` implements the official standalone
proxy's newline-delimited stdio MCP protocol, using Node 22 or newer and only
standard modules. It launches the exact hash-pinned executable with one bare
loopback origin argument, never a shell, ADB command, automatic forward or
headset selection. `metavr xroperator detect` creates a forward; discovery must
therefore be coordinated before using that separate command. Installed CLI
metadata can disagree with actual bundle presence; inspect the selected files.

The finite surface reads instance/session/frame information, places one hand or
controller, performs one pinch or `Trigger` edge, then explicitly releases and
reads back both hands/controllers. Calls are closed to these vendor tools:

| Purpose | Exact tool |
| --- | --- |
| Readiness | `openxr_get_instance_info`, `openxr_get_session_info`, `openxr_get_frame_info` |
| Controller | `openxr_set_controller_pose`, `openxr_get_controller_pose`, `openxr_set_controller_input` |
| Hand | `openxr_hand_gesture`, `openxr_get_hand_state` |
| Restore physical devices | `openxr_release_input_devices` |

Only `hand_pinch` and `controller_select` plans are accepted, with an explicit
hand, reference space, finite position, unit quaternion and 100–500 ms hold.
Controller components use exact `Trigger` spelling. Selecting Trigger/pinch
uses the vendor's bounded `auto_release`/`hold_duration`, so slow owner readback
does not extend the held input. Cleanup still explicitly sends up/open and
full device release after unknown outcomes. The hand plan uses
open/pinch/open edges with a fixed 0.1-second transition; it supplies no target
solver or unsupported aim `auto_release` argument. The app owner computes and
checks fresh widget geometry and the correct reference space before binding the
plan. The backend does not expose arbitrary tool names, semantic app actions,
head override, recording, capture or session exit.

Prepare the source closure without starting anything:

1. Run `tools/Prepare-MetaXrInputCapsule.ps1` with the existing selection
   arguments JSON, exact APK/hash, external proxy/hash and reviewed online
   registry/hash. It reuses the packaging checks and requires a
   `rusty.quest.aapt2_manifest_dump.v1` receipt containing `apk_sha256`, integer
   `exit_code` and `lines` from successful actual APK manifest inspection.
   The existing build/run owner still validates the full app/feature/signing
   closure. The result is explicitly unbound and proves no installed device.
2. Use `bindOperatorCapsule(prepared, binding)` from
   `tools/Bind-MetaXrInputCapsule.mjs`, or its inert three-file CLI. Binding
   contains exactly `identity`, `origin`, `plan`, and `owner_route`.
   It rehashes the local proxy/registry and pins all selected tool schemas.
   Binding is data validation, never a lease or input authorization.
3. The selected source-owned consumer calls `runOperatorInput` with that
   capsule, a fresh evidence directory and its existing read-only owner observer.
   The caller coordinates the exact headset, boot, selected process, port/forward
   and exclusive input window. The backend itself creates none of these.

The runtime identity binds package, APK, layer selection, canonical serial,
boot, PID, process birth, app-owned instance identity and numeric XR session.
The vendor instance-info tool reports application/engine metadata and **does
not supply an instance handle**. A consumer must obtain that binding from its
actual app owner. Three successful focused session observations must show
non-regressing XR time and strict first-to-last advancement. Repeated latest
frames can occur inside that finite budget. Timeout, offline tool, schema drift,
session change or unfocused state stops input without a blind read retry.
After each owner guard and immediately before each new input mutation, the
backend reads the live proxy session again and requires that same focused
session. A newly timestamped dump containing an old native focus marker does
not prove current focus. Such diagnostics can corroborate the owner identity
and frame history but cannot replace this live layer check. Original guarded
compensation still runs after focus loss.
Before taking input ownership, both hands must report no synthetic override,
conformance mode must be physical and both controller poses must be
non-simulated. Existing automation input is preserved; this operation cannot
release another owner's baseline overrides.

The owner observer has one bounded `observe(context)` method and an exact
`route`. Each returned receipt joins `identity`, host `correlation_id`,
`owner_route`, fresh host `observed_at_utc` (at most eight seconds old),
`evidence_ref` and `evidence_sha256`. The observer must retain the actual owner
evidence behind the reference; these fields are not self-issued authority.
It is called at `before_proxy`, `guard`, `release_guard`, `original_effect`, `release`, and
`terminal`. Guards require `status=ready`, `installed_bytes_verified=true`,
`layer_loaded=true`, and `exclusive_input_grant=true` from the selected current
owner route. Cached owner focus is diagnostic; current XR focus belongs to
the live layer session, checked during readiness and immediately before every
new input. Starting the read-only proxy does not require an owner focus claim.
Identity is checked before every mutation,
including cleanup. A changed process/session remains unresolved; cleanup never
follows a replacement app. The observer receives the exact plan for fresh
geometry checks and receives real vendor hand/controller readback for release.
Compensation retains the original acquired input authorization. It rechecks
exact original actor/session and selected installed app/layer facts, but does
not demand renewed new-input focus or a fresh selection grant. Losing focus
must not leave persistent overrides stuck on the same original target.

`original_effect` reports `confirmed`, `pending`, or `rejected` from the
original app handler's state. Controller acknowledgment remains
`dispatch_state=acknowledged`; an actual delivered pressed hand generation can
establish `delivered`. A completed auto-release can already be open by readback;
that remains acknowledged. Neither confirms a widget, rendered hands, capture or presentation.
`release` requires `status=released`, `buttons_down=false` and
`overrides_active=false`, referring to the operation's synthetic input. An open
hand, release acknowledgment or process absence alone cannot establish this.
`terminal` uses `scope=input_operation` and `confirmed`, `pending` or `unknown`.
For an ordinary page action the app/session stays running after effective
release. Writer/producer/XR teardown belongs only to an original action that
actually requests Save/exit; this backend does not request it.

Every new-input RPC intent is written with create-new and fsync before its stdin
write; responses, owner evidence joins, release attempts and final state are
retained when reporting storage remains available. After a possible input effect,
reporting failures cannot block original-target compensation. Those calls still
require the actual identity guard, but use best-effort journaling and retain
their original request IDs and acknowledgments in memory. `reporting_errors`
and `evidence_persisted=false` stay separate from known effect/release state.
A fresh attempt directory prevents accidental reuse. Uncertain placement or
press is never replayed. The `finally` path attempts original-target explicit
up/open and full device release, and retains unknown release separately even if
terminal evidence succeeds. Unknown dispatch still attempts independent original-effect readback
after cleanup; a confirmed original effect does not promote or replay that
uncertain dispatch. RPC, owner observation and proxy close waits are finite.
A timeout retains its original RPC ID; late responses are preserved for
read-only reconciliation and never cause input replay. Proxy retirement closes
stdin and awaits actual stdout/stderr/child close for a bounded wait. A slow
healthy child is not killed after an arbitrary second. If it remains alive,
`proxy_exit.pending=true` and the exact owned PID remain explicit; the returned
non-enumerable `waitForProxyRetirement(waitMs)` allows a further bounded wait and
persists late replies/retirement. The caller retains custody of that child and
must reconcile or explicitly retire it. Pending retirement prevents completion.
An exited proxy does not retire the app, forward or lease; the caller
keeps their actual resource ownership and cleanup contract. Journaling is
durable request evidence, not a crash-recovery service or restart watchdog.

Run `pwsh -NoProfile -File tools/Test-MetaXrInputBackend.ps1` for source
preparation and independent stdio peer tests through the actual Node/PowerShell
runtime. `testOnlyLaunch` is an explicit harness seam and always labels the
result `evidence_tier=host-protocol`; ordinary execution uses the selected
executable and `operator-protocol`. Neither tier substitutes for the retained
app owner's evidence or physical validation. No test launches the vendor proxy,
opens a port, builds an APK or contacts a headset.

## Packaging boundary

Import `tools/lib/MetaXrOperatorPackaging.psm1` in PowerShell 7.6 or newer.
`Get-QuestMetaXrOperatorSelection` accepts the actual resolver-produced
`rusty.quest.native_app_feature_lock.v1` file and its exact SHA-256, the actual
Android manifest, exact package, `NativeOpenXr` or `SpatialSdk` consumer,
`-Enable`, and the external layer library/manifest paths and SHA-256 values.
No arguments returns disabled. Inputs without explicit opt-in reject.
The lock must select `debug.meta_xr_operator`; the existing composition owner
still validates the complete feature closure. This adapter does not replace
that resolver or create a parallel feature authority. This source extraction
does not add the feature to any current product or builder's default closure.

The adapter binds package, consumer, lock, Android manifest and exact external
files in its fingerprint. It checks the fixed Android layer manifest, ARM64
ELF LOAD/dynamic/dependency closure and debuggable app's optional experimental
and Internet declarations. Spatial SDK additionally requires 16 KiB alignment
and explicit application `extractNativeLibs=true`, since the selected official
manifest uses a relative physical library path. Native packaging remains a
separate consumer. Arbitrary paths, ABI variants and layer names are not accepted.

Use `Copy-QuestMetaXrOperatorLayer` with fresh `-OutputRoot` and
`-NativeStageRoot` owned by the build. It revalidates the mutable selection and
snapshots both hash-checked inputs before creating destinations. Existing files
are never overwritten. Disk errors can still leave partial own staging; the
caller must preserve/classify that failure and retire only its owned staging.

The build must include only these staged debug assets/JNI libraries and reject
release selection. It must retain its actual build lock and signer/APK evidence.
Then run `Assert-QuestMetaXrOperatorArchive` and
`Assert-QuestMetaXrOperatorManifestDump -Selection ... -Lines ... -ExitCode ...`
against the actual ZIP and successful aapt2 xmltree output. The latter binds
package, application debuggable and Spatial extraction; existing app-owner
manifest/permission parity checks remain required. The source merged-manifest
helper does not substitute for inspecting the actual binary manifest.
An unselected ZIP containing Operator entries rejects. No library, class or
manifest should leak into ordinary/release output.

The fixed layer shape is `XR_APILAYER_METAX_operator`, its exact ARM64 library,
and `XR_METAX1_agentic_external_tool`. An updated official bundle needs a
reviewed adapter update, not relaxed unknown-field/dependency acceptance.
Packaging success remains `runtime_verified=false`.

## Finite exact-runtime rehearsal

Import `tools/lib/MetaXrInputAutomation.psm1` in the actual persistent runtime
that will host the consumer adapter. It provides
`Test-QuestMetaXrInputReadiness` and `Invoke-QuestMetaXrInputRehearsal`.
The rehearsal is functional but source-only: all observations/effects are
injected by a neutral harness or the consuming host adapter; the result always
says `evidence_tier=host-rehearsal` and `device_verified=false`.
No callback's successful return is a device acceptance certificate.

The immutable-by-copy context binds exact package, APK hash, process birth,
layer fingerprint, instance and session. The selected proxy must be a bare
loopback HTTP origin, without `/sse`, query or credentials. That endpoint shape
matches the inspected local bundle; this is not a universal MCP endpoint rule.
Selection, identity and origin validate before **any** callback. Each callback
gets a fresh context copy, so its mutations cannot retarget later release.

Readiness consumes two to five successful observations with these fields:

- `identity`: the same six identity fields as the selected context;
- `backend_ready`, `layer_loaded`, `focused`: effective Boolean observations;
- `frame_index`, `source_timestamp_ns`: nonnegative integer values;
- `clock_domain`: `xr`, kept separate from host/device time.

A latest-frame snapshot may repeat within that finite successful-read budget,
but first-to-last frame and source timestamp must advance without regression.
An exception/unknown read stops; it is never retried as if successful. The
adapter performing real observations must derive these fields from its actual
backend/layer/session, rather than tool enumeration or selected settings.

The rehearsal accepts only `hand_pinch` or `controller_select` plus five explicit
scriptblock adapters, each called with that original context:

| Callback | Result boundary |
| --- | --- |
| `ReadFrame` | The same-session readiness observation above |
| `DispatchInput` | Identity, matching adapter `correlation_id`, status `delivered` |
| `ObserveOriginalEffect` | Same identity/correlation, `confirmed`, `pending` or `rejected`; confirmation needs `evidence_ref` |
| `ReleaseInput` | Same identity/correlation, status `released`, effective `overrides_active=false`, `buttons_down=false`, and `evidence_ref` |
| `ObserveTerminal` | Same identity/correlation, `confirmed`, `pending` or `unknown`; confirmation needs owner `evidence_ref` |

The correlation ID belongs to this host attempt, not an invented remote widget
command ID. Original app-local effects need their own evidence; controller or
hand delivery alone does not prove SDK widget adoption, rendered hand meshes or
pixels. Terminal evidence belongs to the selected action owner: a local Save/
exit action needs durable output, writer close, producer acknowledgement and XR
teardown; a nonterminal action may leave terminal state pending. Process absence
alone is insufficient. Optional capture/recording, Java/AAR/DEX closure and
MediaProjection consent remain independent selections, never input prerequisites.

Before dispatch, failure means `not_sent`. Before invoking dispatch, state is
recorded in the in-memory attempt as `outcome_unknown`. A lost
or throwing dispatch is never replayed. `finally` invokes release against the
original identity and then the original terminal observer. Unknown release is
retained even when terminal observation succeeds. Open hand alone is not proof
that all overrides were disabled. `completed` requires separately confirmed
delivery, original effect, release and terminal state; inspect the receipts and
errors for incomplete attempts. Rehearsal itself is not durable crash recovery:
the consuming service must journal the original attempt before a real effect.

## Host conformance

Run the source-owned neutral harness in the exact PowerShell host:

```powershell
pwsh -NoProfile -File tools/Test-MetaXrInputAutomation.ps1
python tools/check_quest_boundaries.py
```

It generates only synthetic external ELF/ZIP/manifests in a fresh local-artifacts
directory. Two independent app state machines exercise native original-page
progress and SDK delivery without widget progress. Damaged package/layer/
manifest/extraction/hash/identity/session/frame/endpoint cases refuse; source
and path tampering cause no staging writes. Lost dispatch, failed release,
callback retargeting, unknown reads and missing terminal evidence retain their
original uncertainty. This proves the reusable host seam, not a live device,
actual tool bundle load, SDK widget pixels or distribution eligibility.

A new live consumer must still compose the exact official layer into its own
APK, bind its actual installed app/layer/source and proxy/session, rehearse its
changed adapters in that same runtime, then correlate one original input/effect
and release/terminal evidence. Use the Meta workflow's focused input-automation
playbook for device procedure. Hostess owns its CLI/API/WPF projection; Quest
owns this layer/platform composition and app owners retain action semantics.
