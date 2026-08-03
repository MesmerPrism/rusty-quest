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
| `Start`, `Status`, `Stop`, `Forget` | Uses the fixed debug Hub shell provider and records the missing QFM service-action capability. `Forget` remains a Manifold decision. |
| `LaunchProviders`, `StopProviders` | Launches the two fixed providers through QFM, with only the reviewed revision-69b02f1 launcher-export fallback; stopping uses one fixed-package provider-gap fallback. |
| `HostessStatus`, `HostessPair`, `HostessList`, `HostessWatch`, `HostessCommand`, `HostessReconnect`, `HostessRevoke` | Projects Hostess's existing closed Connection Hub controller CLI. Only the two checked-in surfaces and their registered commands are accepted. |
| `Logs` | Captures at most 5,000 serial-scoped logcat lines and rejects `FATAL EXCEPTION`, `AndroidRuntime E`, or `UnsatisfiedLinkError`. |
| `Cleanup` | Stops only the two provider packages and the Hub listener; it does not uninstall packages or alter device settings. |
| `E2E` | Builds, inspects, checks shared signer, installs, starts, launches both providers, pairs, lists, invokes one command per provider, reconnects, watches, scans logs, revokes, and cleans up. |
| `SimulateE2E` | Writes deterministic no-device synthetic receipts and their hash-bound evidence manifest. |

Every non-dry run writes private evidence outside the source checkout. The
manifest binds each receipt by SHA-256 and records cleanup scope. APKs, serials,
paths, logs, session metadata, and device receipts must not be committed.

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

The wrapper is QFM-first. Raw ADB is limited to four fixed gaps, always with
`-s <serial>` and no caller-supplied component, package, method, or arguments:

- `qfm-69b02f1.launch-export-parser` — fixed launcher fallback only after the exact known parser error;
- `qfm-missing-typed-connection-hub-service-action-v1` — fixed DUMP-protected Hub debug methods;
- `qfm-missing-typed-package-stop-v1` — force-stop only the two fixed sample providers;
- `qfm-missing-bounded-logcat-v1` — bounded diagnostic log read and fatal scan.

These receipts claim transport fallback only, never File Manager, Hostess,
Manifold, or application acceptance.

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
