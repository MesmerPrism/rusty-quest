param([string]$RepoRoot = "")

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
} else {
    $RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
}

function Read-Required([string]$RelativePath) {
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing $RelativePath" }
    return [System.IO.File]::ReadAllText($path)
}

function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

$cliPath = Join-Path $RepoRoot "tools\Invoke-ConnectionHubQuest.ps1"
$cli = Read-Required "tools\Invoke-ConnectionHubQuest.ps1"
$build = Read-Required "tools\Build-ManifoldBrokerAndroid.ps1"
$provider = Read-Required "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubDebugControlProvider.java"
$activity = Read-Required "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubStartActivity.java"
$browserHarness = Read-Required "tools\browser\connection-hub-browser-e2e.js"
$releaseManifest = Read-Required "fixtures\broker-products\connection-hub-standalone.AndroidManifest.xml"

Require ($cli.Contains('[Parameter(Mandatory=$true)]') -and $cli.Contains('[string]$Serial')) "Operator CLI must require an explicit serial."
Require ($cli.Contains('Lock-ExactProvider $FileManagerCli $FileManagerSha256')) "QFM must be held under one exact content pin."
Require ($cli.Contains('Lock-ExactExecutable $Adb $AdbSha256') -and
    $cli.Contains('Lock-ExactExecutable $Python $PythonSha256') -and
    $cli.Contains('Lock-ExactExecutable $Node $NodeSha256') -and
    $cli.Contains('Lock-ExactExecutable $Gradle $GradleSha256')) "ADB/Python/Node/Gradle run locks are incomplete."
Require ($cli.Contains('adb_version = $script:AdbVersion') -and
    $cli.Contains('python_version = $script:PythonVersion') -and
    $cli.Contains('node_version = $script:NodeVersion') -and
    $cli.Contains('gradle_version = $script:GradleVersion')) "Checkpoint does not bind executable versions."
Require ($cli.Contains('Installed-byte readback') -and $cli.Contains('Stage-Apk')) "QFM transaction must stage and confirm exact APK bytes."
Require ($cli.Contains('qfm-69b02f1.launch-export-parser')) "Known QFM launcher parser fallback is not labelled."
Require ($cli.Contains('qfm-missing-typed-connection-hub-service-action-v1')) "Hub service provider gap is not labelled."
Require ($cli.Contains('qfm-missing-typed-connection-hub-activity-action-v1')) "Real foreground-service lifecycle gap is not labelled."
Require ($cli.Contains('qfm-missing-bounded-logcat-v1')) "Logcat provider gap is not labelled."
Require ($cli.Contains('qfm-missing-typed-package-stop-v1')) "Package-stop provider gap is not labelled."
Require ($cli.Contains('qfm-readonly-device-state-v1')) "Read-only device-state gap is not labelled."
Require ($cli.Contains('qfm-readonly-package-state-v1')) "Read-only package-state gap is not labelled."
Require ($cli.Contains('qfm-missing-typed-target-uninstall-v1')) "Target-uninstall provider gap is not labelled."
Require ($cli.Contains('qfm-missing-typed-wifi-rebind-v1')) "Wi-Fi rebind provider gap is not labelled."
Require (-not $cli.Contains('PairingCode =')) "Pairing secrets must not be accepted as ordinary PowerShell values."
Require ($cli.Contains('--pairing-code-stdin') -and $cli.Contains('--pairing-code-fd')) "Hostess secret-safe input routes are missing."
Require ($cli.Contains('DPAPI-CurrentUser-session')) "Hostess DPAPI session ownership must be explicit."
Require ($cli.Contains('function Get-DebugPairingSecret') -and $cli.Contains('function Invoke-HostessPairWithSecret')) "Autonomous secret transport is missing."
Require ($cli.Contains('[Array]::Clear($Secret') -and $cli.Contains('[Array]::Clear($stdout')) "Pairing secret buffers are not explicitly cleared."
Require ($cli.Contains('$pairingSecret = Get-DebugPairingSecret') -and -not $cli.Contains('Save-Receipt "pair-code"')) "E2E must use the dedicated non-recording secret route."
Require ($cli.Contains('-Keystore $resolvedKeystore') -and $cli.Contains('RUSTY_CONNECTION_HUB_KEYSTORE')) "All APK builds must use one explicit keystore."
Require ($cli.Contains('the three APK signers differ') -and $cli.Contains('ExpectedSignerSha256')) "Pre-install signer equality gate is missing."
Require ($cli.Contains('surface.spatial_video_control.media') -and $cli.Contains('surface.connection_hub_sample.toggle')) "Both fixed provider surfaces must be in the command registry."
Require ($cli.Contains('Capture-PreState') -and $cli.Contains('Restore-PreState') -and $cli.Contains('apk", "export"')) "Target pre-state preservation/restore is incomplete."
Require ($cli.Contains('preexisting_hub_private_trust_state_has_no_exact_restore_contract') -and
    $cli.Contains('path=[string]$prior.retained_apk') -and
    $cli.Contains('$script:TargetMutationStarted')) "PreserveAndRestore does not fail closed for an unknown running Hub or launch restored provider bytes."
Require ($cli.Contains('function Start-RunLogCapture') -and
    $cli.Contains('function Record-TargetProcessEpoch') -and
    $cli.Contains('function Stop-RunLogCapture') -and
    $cli.Contains('Run-owned log capture coverage was incomplete') -and
    $cli.Contains('target_fatal_count') -and $cli.Contains('target_anr_count')) "Run-bounded target fatal/ANR evidence is incomplete."
Require ($cli.Contains('"Cleanup", "E2E"') -and
    $cli.Contains('pre-existing Hub install because restoring APK bytes cannot restore its pairing/trust state')) "Cleanup QFM dependency or private-state fail-closed policy is missing."
Require ($cli.Contains('Initialize-Checkpoint') -and $cli.Contains('Resume checkpoint artifact digest mismatch')) "Resume checkpoint identity binding is incomplete."
Require ($cli.Contains('ProviderLifetimeSeconds') -and $cli.Contains('Wait-Surface "surface.spatial_video_control.media" $false')) "Sequential provider/lifetime oracle is incomplete."
Require ($cli.Contains('authenticated_socket_open_before_revoke') -and
    $cli.Contains('authenticated_socket_closed_within_deadline') -and
    $cli.Contains('stale_bearer_auth_rejected') -and
    $cli.Contains('credentials_deleted_after_negative_proof') -and
    -not $cli.Contains('function Assert-RevokedSocketClosed')) "Same-process revoke/closed-socket/stale-bearer proof is missing."
Require ($cli.Contains('$effect.request_binding_exact -ne $true') -and
    $cli.Contains('$effect.authority_accepted -ne $true') -and
    $cli.Contains('$effect.provider_applied -ne $true') -and
    $cli.Contains('provider_effect_observed')) "Device command oracle does not require an exact observed provider effect."
Require ($cli.Contains('Restart-HubProcess') -and $cli.Contains('START_STICKY did not produce a new Hub process')) "Process restart/FGS recovery oracle is missing."
Require ($cli.Contains('not_run_safety_reason') -and $cli.Contains('RequireWifiRebindE2E')) "Wi-Fi rebind safety gate is missing."
Require (-not $cli.Contains('AdbArguments')) "A generic caller-supplied ADB argument surface is forbidden."

Require ($build.Contains('[switch]$EnableConnectionHubDebugOperator')) "Debug operator build gate is missing."
Require ($build.Contains('if ($EnableConnectionHubDebugOperator)')) "Debug operator manifest must be conditional."
Require ($build.Contains('return [bool]$EnableConnectionHubDebugOperator')) "Debug provider source must be excluded by default."
Require ($build.Contains('connection_hub_debug_operator = [bool]$EnableConnectionHubDebugOperator')) "Build receipt must disclose debug operator inclusion."
Require ($provider.Contains('android.permission.DUMP') -eq $false) "Permission authority belongs in the generated manifest, not provider logic."
Require ($provider.Contains('Binder.getCallingUid() != Process.SHELL_UID')) "Debug operator must reject non-shell callers."
Require ($provider.Contains('pairing_secret_in_receipt') -and -not $provider.Contains('receipt.put("pairing_code"')) "Debug receipt must prove secret exclusion."
Require ($provider.Contains('"pair-code".equals(method)') -and $provider.Contains('secret_b64')) "Debug-only one-use secret projection is missing."
Require (-not $releaseManifest.Contains('ConnectionHubDebugControlProvider')) "Release product fixture must exclude the debug operator."
Require ($activity.Contains('ACTION_DEBUG_START_HUB') -and $activity.Contains('startForegroundService')) "Typed real Activity-to-FGS lifecycle route is missing."
Require ($browserHarness.Contains('process.stdin') -and $browserHarness.Contains('consoleErrors') -and $browserHarness.Contains('spatial-removed-sample-present-command-applied')) "Real browser sequential-surface E2E harness is incomplete."
Require (-not $browserHarness.Contains('--pairing-code') -and -not $browserHarness.Contains('pairing_code:')) "Browser harness must not accept or emit a pairing-code argument/field."

$planText = & pwsh -NoProfile -File $cliPath -Action E2E -Serial SIMULATED123 -DryRun
if ($LASTEXITCODE -ne 0) { throw "Operator dry-run failed." }
$plan = $planText | ConvertFrom-Json
Require ([string]$plan.'$schema' -eq 'rusty.quest.connection_hub.operator_plan.v1') "Dry-run schema mismatch."
Require ($plan.qfm_first -eq $true -and $plan.qfm_exact_sha256_required -eq $true) "Dry-run lost QFM-first exact-pin policy."
Require (@($plan.e2e_sequence).Count -eq 20 -and $plan.e2e_sequence[-1] -eq 'target-only-pre-state-restore') "Dry-run E2E sequence is incomplete."
Require ($plan.provider_lifetime_seconds -gt 120 -and $plan.checkpoint_resume -match 'artifacts') "Dry-run omitted lifetime/checkpoint gates."
Require ($plan.secrets_in_plan -eq $false) "Dry-run plan secret posture mismatch."

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("connection-hub-operator-test-" + [Guid]::NewGuid().ToString("N"))
try {
    $runText = & pwsh -NoProfile -File $cliPath -Action SimulateE2E -Serial SIMULATED123 -EvidenceRoot $tempRoot
    if ($LASTEXITCODE -ne 0) { throw "Operator simulation failed." }
    $run = $runText | ConvertFrom-Json
    Require ($run.result -eq 'passed' -and $run.secrets_in_output -eq $false) "Simulation run posture mismatch."
    $manifest = Get-Content -Raw -LiteralPath $run.evidence_manifest | ConvertFrom-Json
    Require ([string]$manifest.'$schema' -eq 'rusty.quest.connection_hub.operator_evidence_manifest.v1') "Simulation evidence schema mismatch."
    Require (@($manifest.receipts).Count -eq 20 -and $manifest.secrets_in_manifest -eq $false) "Simulation evidence closure mismatch."
    foreach ($receipt in @($manifest.receipts)) {
        $path = Join-Path (Split-Path -Parent $run.evidence_manifest) ([string]$receipt.name)
        Require ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -eq [string]$receipt.sha256) "Simulation receipt digest mismatch."
    }
    $checkpointPath = Join-Path (Split-Path -Parent $run.evidence_manifest) "checkpoint.json"
    $checkpoint = Get-Content -Raw -LiteralPath $checkpointPath | ConvertFrom-Json
    Require (@($checkpoint.completed_stages).Count -eq 20 -and $checkpoint.secrets_in_checkpoint -eq $false) "Simulation checkpoint closure mismatch."
    $resumedText = & pwsh -NoProfile -File $cliPath -Action SimulateE2E -Serial SIMULATED123 -EvidenceRoot $tempRoot -ResumeCheckpoint $checkpointPath
    if ($LASTEXITCODE -ne 0) { throw "Operator resume simulation failed." }
    $resumed = $resumedText | ConvertFrom-Json
    $resumedManifest = Get-Content -Raw -LiteralPath $resumed.evidence_manifest | ConvertFrom-Json
    Require (@($resumedManifest.receipts).Count -eq 20 -and $resumed.result -eq 'passed') "Resumed evidence closure mismatch."
    $mismatchOutput = & pwsh -NoProfile -File $cliPath -Action SimulateE2E -Serial DIFFERENT123 -EvidenceRoot $tempRoot -ResumeCheckpoint $checkpointPath 2>&1
    Require ($LASTEXITCODE -ne 0 -and ($mismatchOutput -join "`n") -match 'checkpoint identity') "Resume accepted a mismatched serial."
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        $resolved = (Resolve-Path -LiteralPath $tempRoot).Path
        $systemTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\')
        Require ($resolved.StartsWith($systemTemp + '\connection-hub-operator-test-', [StringComparison]::OrdinalIgnoreCase)) "Refusing unexpected test cleanup target."
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

Write-Output "Rusty Connection Hub operator CLI source, dry-run, and simulation validation passed"
