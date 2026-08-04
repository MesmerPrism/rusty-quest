# Rusty Connection Hub Quest operator

`tools/Invoke-ConnectionHubQuest.ps1` is the closed Quest-side build,
deployment, lifecycle, provider, diagnostic, Hostess, cleanup, and evidence
wrapper for the standalone Connection Hub development product. It requires an
explicit Quest serial for every action and never changes the ADB daemon,
wireless-debugging state, power policy, proximity, or unrelated packages.

The wrapper is for local debug validation. It does not make the current
plaintext trusted-LAN transport production eligible. The Hub continues to
advertise `confidentiality=none` and `production_eligible=false` until a
separate TLS/WSS product slice is accepted.

## Closed actions

| Action | Effect and evidence |
| --- | --- |
| `Build` | Builds the Hub, Spatial Video provider, and distinct sample provider with one explicit keystore. No device action. |
| `Inspect` | Stages read-only content-addressed APK copies and inspects all three with one hash-locked File Manager executable. Signers must be identical. |
| `Install` | Inspect, exact-serial install, and installed-base byte readback for all three APKs. It stops before launch on any mismatch. |
| `Prerequisites` | Binds exact serial/provider/protocol hashes and validates the optional browser provider. |
| `Start`, `Status`, `Stop`, `Forget` | Dispatches a fixed debug Activity action through the real foreground service, then uses the shell provider only for observation. Start proves the ongoing notification and service. |
| `DebugProtocolProof` | Separately proves the debug shell protocol start/status/stop route without claiming foreground-service coverage. |
| `RestartProcess` | Schedules a receipt-first debug process death, then proves a new PID, START_STICKY service/notification/listener recovery, encrypted desired-state restore, reconnect, provider re-registration, and post-restart command in E2E. |
| `WifiRebindE2E` | Opt-in only on a non-TCP ADB transport with Wi-Fi initially enabled; otherwise emits an explicit safety-skip receipt and makes no rebind claim. |
| `LaunchSpatial`, `LaunchSample`, `StopSpatial`, `StopSample`, `WaitSurface`, `WaitSurfaceAbsent`, `StopProviders` | Drives and observes the fixed foreground-app surface lifecycle. It never expects both Activity-owned surfaces concurrently. |
| `HostessStatus`, `HostessPair`, `HostessList`, `HostessWatch`, `HostessCommand`, `HostessReconnect`, `HostessRevoke` | Projects Hostess's existing closed Connection Hub controller CLI. Only the two checked-in surfaces and their registered commands are accepted. |
| `Logs` | Captures at most 5,000 serial-scoped logcat lines and rejects `FATAL EXCEPTION`, `AndroidRuntime E`, or `UnsatisfiedLinkError`. |
| `BrowserE2E` | Uses an exact Playwright package/browser pin and stdin-only pairing secret to exercise the packaged page, sequential Spatial→Sample→Spatial surfaces, commands, receipts, revoke, and zero console/page errors. |
| `Cleanup` | Applies the recorded `RetainCandidate` or `PreserveAndRestore` policy, restores target running/listener state, and verifies power/proximity/autosleep/forward invariants. |
| `E2E` | Resumable checkpointed transaction covering pre-state, exact install, real FGS, pairing, Spatial command, >2-minute provider lifetime, process restart, sequential Spatial→Sample→Spatial surfaces, reconnect epoch, optional safe Wi-Fi rebind, diagnostics, revoke/closed-socket negative, optional required real-browser leg, and target-only restoration. |
| `SimulateE2E` | Writes deterministic no-device synthetic receipts and their hash-bound evidence manifest. |

Every non-dry run writes private evidence outside the source checkout. The
manifest binds each receipt by SHA-256 and records cleanup scope. APKs, serials,
paths, logs, session metadata, and device receipts must not be committed.
Before install it exports and inspects every pre-existing target APK and records
its identity/version/digest and running state. `RetainCandidate` is an explicit
receipt-bound policy; `PreserveAndRestore` reinstalls and re-observes exact prior
bytes. Checkpoints bind serial, protocol, QFM/Hostess pins, artifact hashes,
build-manifest hash, and policy, and refuse mismatched resume attempts.

## Exact providers and signing

Supply the resolved QFM and Hostess source-file paths together with their
expected SHA-256 values. The wrapper holds each provider under a read lock and
rechecks its hash around invocation. It stages each APK under its SHA-256 and
makes that copy read-only for the complete local transaction.

`Build` and `E2E` require one explicit `-Keystore`. The Broker build and both
Gradle provider modules use that same file. QFM inspection then proves that all
three APK signer digests are identical before any install. Optionally bind the
expected certificate digest with `-ExpectedSignerSha256`.

The Spatial Video native adapter still has its own older, exact Manifold source
lock. Therefore build calls name both `-HubManifoldSourceRoot` and
`-SpatialManifoldSourceRoot`; neither source root is inferred from an ambient
checkout. Gradle must be the validated 8.13 distribution.

## Pairing-secret boundary

Manual `HostessPair` supports Hostess's hidden prompt, `-PairingCodeStdin`, or
`-PairingCodeFd`. There is no ordinary pairing-code argument.

`E2E` is autonomous after ADB authorization. Its explicitly debug-only
`pair-code` call is protected by `android.permission.DUMP` and an exact shell
UID check. The returned one-use code bypasses generic receipt handling: it is
decoded into bounded buffers, written character-by-character to Hostess stdin,
and zeroed. It never enters argv, a temporary file, stdout, a receipt, logcat,
or the evidence manifest. Hostess stores only the established session through
Windows DPAPI `CurrentUser` and emits secret-redacted evidence.

`ConnectionHubDebugControlProvider` is packaged only when
`Build-ManifoldBrokerAndroid.ps1 -EnableConnectionHubDebugOperator` is
explicit. The normal product manifest and default build exclude it. The build
receipt says whether it was included, and the static operator gate checks this
negative boundary. Do not submit or publish the debug-operator APK.

## Reviewed ADB fallbacks

The wrapper is QFM-first. Every raw ADB use is selected from this fixed,
receipt-visible gap registry, always with `-s <serial>` and no caller-supplied
component, package, method, or arguments:

- `qfm-69b02f1.launch-export-parser` — fixed launcher fallback only after the exact known parser error;
- `qfm-missing-typed-connection-hub-service-action-v1` — fixed DUMP-protected Hub debug methods;
- `qfm-missing-typed-connection-hub-activity-action-v1` — fixed debug Activity actions that enter the real foreground-service lifecycle;
- `qfm-missing-typed-package-stop-v1` — force-stop only the two fixed sample providers;
- `qfm-missing-bounded-logcat-v1` — bounded diagnostic log read and fatal scan;
- `qfm-readonly-device-state-v1` — read-only stay-awake, timeout, and power/proximity facts for invariant checking;
- `qfm-readonly-package-state-v1` — read-only installed/running state for the three fixed test targets;
- `qfm-missing-typed-target-uninstall-v1` — uninstall only a fixed target that this run installed and must restore as absent;
- `qfm-missing-typed-wifi-rebind-v1` — opt-in USB-only Wi-Fi disable/restore and origin-rebind validation.

Each fallback result carries its stable gap id. These receipts claim transport
fallback only, never File Manager, Hostess, Manifold, or application
acceptance.

## Sequential surface oracle

Spatial Video registers only while its Activity is started. Launching the
sample Activity must remove Spatial and add Sample; relaunching Spatial must
remove Sample and add Spatial again. The Hub logical session and real
foreground service persist across all three app switches. Concurrent presence
is not required unless a future provider explicitly declares a background
lifetime.

Each device command gate requires the exact requested surface, command, and
request binding plus `accepted=true`, `provider_applied=true`, and
`status=provider_effect_observed`. An accepted-but-queued protocol receipt is
valid transport output but is not application-effect acceptance. Revocation is
proved inside one Hostess process: open authenticated socket, applied HTTP
revoke, bounded close of that socket, fresh stale-bearer authentication
rejection, then local credential deletion. Failure caused only by a previously
deleted session file is not revocation evidence.

Native command-adapter precondition failures are returned as structured,
fail-closed receipts with `applied=false` and no authority acceptance receipt.
Stable status families distinguish an inactive logical session, a missing
required field, an invalid epoch or digest, field-specific invalid identifiers,
and an otherwise invalid command binding:
`adapter_session_not_active`, `adapter_missing_required_field`,
`adapter_epoch_field_invalid`, `adapter_digest_invalid`,
`adapter_session_id_invalid`, `adapter_lease_id_invalid`,
`adapter_command_id_invalid`, `adapter_request_id_invalid`,
`adapter_schema_id_invalid`, `adapter_identifier_invalid`, and
`adapter_command_binding_invalid`. These
statuses describe rejection before Manifold command admission; they never claim
that Manifold or the provider accepted the request.

## Real browser provider

`tools/browser/connection-hub-browser-e2e.js` is separate from Hostess protocol
testing. It accepts the one-use code only on stdin, never argv or a file. The
operator binds the exact Playwright `package.json`, browser executable, and
harness digests. `-RequireBrowserE2E` turns this optional provider into a
required acceptance gate; otherwise the final evidence says `not_required`
and makes no browser-device claim.

## Dry run and source validation

```powershell
pwsh -NoProfile -File tools\Invoke-ConnectionHubQuest.ps1 `
  -Action E2E -Serial SIMULATED123 -DryRun

pwsh -NoProfile -File tools\Invoke-ConnectionHubQuest.ps1 `
  -Action SimulateE2E -Serial SIMULATED123 `
  -EvidenceRoot C:\private\rusty-connection-hub-evidence

pwsh -NoProfile -File tools\checks\Test-ConnectionHubOperator.ps1 -RepoRoot .
```

The normal E2E invocation additionally supplies exact QFM/Hostess hashes,
Gradle 8.13, the shared keystore, both clean Manifold source roots, a private
evidence root, and the public controller identity SHA-256. Use `-DryRun` first;
it performs no file, build, network, or device mutation.
