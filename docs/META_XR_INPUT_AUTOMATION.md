# Meta XR input automation adapter

This opt-in developer component extracts the selected debug API-layer packaging
checks from the existing native/Spatial experiment adapters. It does not bundle
Meta binaries, enable a product, create an OpenXR session, start a proxy, inject
input through ADB, or supply command/session authority. The original application
keeps its sole XR owner and original widget/action handlers.

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
