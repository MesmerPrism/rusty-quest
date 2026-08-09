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
$publishedProvider = Read-Required "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubOperatorProvider.java"
$operatorController = Read-Required "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubOperatorController.java"
$activity = Read-Required "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubStartActivity.java"
$browserHarness = Read-Required "tools\browser\connection-hub-browser-e2e.js"
$releaseManifest = Read-Required "fixtures\broker-products\connection-hub-standalone.AndroidManifest.xml"
$nativeAdapter = Read-Required "apps\manifold-broker-android\connection-hub-native\src\connection_hub_jni.rs"
$operatorGuide = Read-Required "docs\CONNECTION_HUB_OPERATOR.md"
$releaseGuide = Read-Required "docs\CONNECTION_HUB_LABS_RELEASE.md"
$releaseBuild = Read-Required "tools\Build-ConnectionHubLabsRelease.ps1"
$processCapture = Read-Required "tools\ConnectionHubBoundedProcessCapture.cs"

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
Require ($cli.Contains('function Invoke-CapturedBounded') -and
    $cli.Contains('function Invoke-CapturedTimed') -and
    $cli.Contains('function Invoke-AdbBounded') -and
    $cli.Contains('@("shell", "am", "start", "-n", $Component)') -and
    $cli.Contains('"dispatch one fixed reviewed component" 10000') -and
    -not $cli.Contains('@("shell", "am", "start", "-W", "-n", $Component)')) "Reviewed Activity fallback must be bounded and must not wait behind Horizon Sensor Lock."
Require ($cli.Contains('qfm-missing-typed-connection-hub-service-action-v1')) "Hub service provider gap is not labelled."
Require ($cli.Contains('qfm-missing-typed-connection-hub-activity-action-v1')) "Real foreground-service lifecycle gap is not labelled."
Require ($cli.Contains('"shell", "am", "start-foreground-service", "-n", "$HubPackage/.ConnectionHubStartService"') -and
    $cli.Contains('debug-shell-foreground-service') -and
    -not $cli.Contains('"shell", "am", "start", "-W", "-n", $HubActivity')) "Off-head Hub lifecycle must use the exact DUMP-gated debug service rather than a Sensor-Lock-blocked Activity."
Require ($cli.Contains('qfm-missing-bounded-logcat-v1')) "Logcat provider gap is not labelled."
Require ($cli.Contains('qfm-missing-typed-package-stop-v1')) "Package-stop provider gap is not labelled."
Require ($cli.Contains('qfm-readonly-device-state-v1')) "Read-only device-state gap is not labelled."
Require ($cli.Contains('qfm-readonly-package-state-v1')) "Read-only package-state gap is not labelled."
Require ($cli.Contains('qfm-missing-typed-target-uninstall-v1')) "Target-uninstall provider gap is not labelled."
Require ($cli.Contains('qfm-missing-typed-wifi-rebind-v1')) "Wi-Fi rebind provider gap is not labelled."
Require ($cli.Contains('[switch]$UseBoundedVirtualProximity') -and
    $cli.Contains('com.oculus.vrpowermanager.prox_close') -and
    $cli.Contains('"--ei", "duration", "600000"') -and
    $cli.Contains('com.oculus.vrpowermanager.automation_disable') -and
    $cli.Contains('virtual_proximity_restore_failed')) "Bounded off-head Quest validation does not restore normal proximity fail-closed."
Require ($cli.Contains('[switch]$UseOffHeadDebugProviders') -and
    $cli.Contains('qfm-missing-typed-debug-provider-service-action-v1') -and
    $cli.Contains('$package.action.START_CONNECTION_HUB_DEBUG_SURFACE') -and
    $cli.Contains('$SpatialDebugSurfaceService') -and
    $cli.Contains('$SampleDebugSurfaceService') -and
    $cli.Contains('isForeground=true')) "Off-head E2E does not use the exact DUMP-gated debug provider FGS with independent readback."
Require ($cli.Contains('function Test-ExactAndroidComponentEcho') -and
    $cli.Contains('Test-ExactAndroidComponentEcho $dispatch.output $component') -and
    $cli.Contains('$matches.Count -ne 1') -and
    $cli.Contains("StartsWith('.', [StringComparison]::Ordinal)")) `
    "Debug provider dispatch does not compare Android's exact full and same-package shorthand component forms canonically."
Require ($cli.Contains('pre_dispatch_proof_rejected') -and
    $cli.Contains('dispatch_attempted') -and
    $cli.Contains('cmd", "package", "query-activities", "--brief", "--components"') -and
    $cli.Contains('$resolvedComponents.Count -ne 1 -or $resolvedComponents[0] -cne $Component')) "QFM launcher fallback is not independently bound to one exact fixed exported component."
Require (-not $cli.Contains('PairingCode =')) "Pairing secrets must not be accepted as ordinary PowerShell values."
Require ($cli.Contains('--pairing-code-stdin') -and $cli.Contains('--pairing-code-fd')) "Hostess secret-safe input routes are missing."
Require ($cli.Contains('DPAPI-CurrentUser-session')) "Hostess DPAPI session ownership must be explicit."
Require ($cli.Contains('function Get-DebugPairingSecret') -and $cli.Contains('function Invoke-HostessPairWithSecret')) "Autonomous secret transport is missing."
Require ($cli.Contains('[Array]::Clear($Secret') -and $cli.Contains('[Array]::Clear($stdout')) "Pairing secret buffers are not explicitly cleared."
Require ($cli.Contains('$pairingSecret = Get-DebugPairingSecret') -and -not $cli.Contains('Save-Receipt "pair-code"')) "E2E must use the dedicated non-recording secret route."
Require ($cli.Contains('[void]$process.WaitForExit(12000)')) "Pairing secret transport leaks the WaitForExit Boolean into the char-buffer result."
Require ($cli.Contains('function Test-JsonContainsUnredactedHostessSecret') -and
    $cli.Contains("'^(?i:pairing_code|bearer_token|session_bearer)$'") -and
    $cli.Contains("'^(?i:pairing_code|bearer_token|session_bearer)_in_receipt$'") -and
    $cli.Contains('Hostess JSON exceeded the maximum inspection depth') -and
    $cli.Contains('Hostess output contained a non-JSON runtime value') -and
    $cli.Contains('Synthetic Hostess secret inspection accepted a cyclic runtime object') -and
    -not $cli.Contains('$result.output -match')) "Hostess secret inspection must be parsed-field aware rather than raw-text based."
Require ($cli.Contains('function Invoke-HostessCheckedRaw') -and
    $cli.Contains('Invoke-CapturedTimed $script:Python') -and
    -not $cli.Contains('Invoke-Captured $script:Python @($script:Hostess, "list-surfaces"') -and
    -not $cli.Contains('.ReadToEndAsync()') -and
    $cli.Contains('ConnectionHubBoundedProcessCapture.cs') -and
    $processCapture.Contains('stdoutThread.Start()') -and
    $processCapture.Contains('stderrThread.Start()') -and
    $processCapture.Contains('process.Kill(entireProcessTree: true)') -and
    $processCapture.Contains('IsBackground = true') -and
    $processCapture.Contains('DrainStream') -and
    $cli.Contains('exceeded the one MiB captured-output bound')) "Every ordinary Hostess route must use the shared deadline-controlled wrapper without async stream deadlock."
Require ($cli.Contains('$wait.authentication_retry_count') -and
    $cli.Contains('[int]$wait.authentication_retry_count -gt 1')) "The bounded wait-surface authentication retry is not validated exactly."
Require ($cli.Contains('-Keystore $resolvedKeystore') -and $cli.Contains('RUSTY_CONNECTION_HUB_KEYSTORE')) "All APK builds must use one explicit keystore."
Require ($cli.Contains('the three APK signers differ') -and $cli.Contains('ExpectedSignerSha256')) "Pre-install signer equality gate is missing."
Require ($cli.Contains('surface.spatial_video_control.media') -and $cli.Contains('surface.connection_hub_sample.toggle')) "Both fixed provider surfaces must be in the command registry."
Require ($cli.Contains('Capture-PreState') -and $cli.Contains('Restore-PreState') -and $cli.Contains('apk", "export"')) "Target pre-state preservation/restore is incomplete."
Require ($cli.Contains('preexisting_target_private_state_has_no_exact_restore_contract') -and
    $cli.Contains('path=[string]$prior.retained_apk') -and
    $cli.Contains('$script:TargetMutationStarted')) "PreserveAndRestore does not fail closed for an unknown running Hub or launch restored provider bytes."
Require ($cli.Contains('function Start-RunLogCapture') -and
    $cli.Contains('function Record-TargetProcessEpoch') -and
    $cli.Contains('function Stop-RunLogCapture') -and
    $cli.Contains('Run-owned log capture coverage was incomplete') -and
    $cli.Contains('"-v", "epoch", "-v", "uid"') -and
    $cli.Contains('capture_process_alive_at_end') -and
    $cli.Contains('end_marker_retained') -and
    $cli.Contains('backlog_before_start_excluded') -and
    $cli.Contains('target_fatal_count') -and $cli.Contains('target_anr_count')) "Run-bounded target fatal/ANR evidence is incomplete."
Require ($cli.Contains('"Cleanup", "E2E"') -and
    $cli.Contains('pre-existing target install because restoring APK bytes cannot restore app-private state') -and
    $cli.Contains('function Invoke-StandaloneCleanup')) "Cleanup QFM dependency or private-state fail-closed policy is missing."
Require ($cli.Contains('$packageName -ne $expectedPackages[$index]') -and
    $cli.Contains('three distinct packages and APK byte digests')) "Exact-three artifact identity/distinctness gate is missing."
Require ($cli.Contains('Assert-CheckpointStageClosure') -and
    $cli.Contains('artifact_build_manifest_path') -and
    $cli.Contains('Restore-ProcessEpochsFromReceipts')) "Resume stage/build/process evidence closure is incomplete."
Require ($cli.Contains('function Measure-RunLogWindow') -and
    $cli.Contains('Synthetic UID/PID/native fatal classifier')) "Behavioral run-log classifier simulation is missing."
Require ($cli.Contains('capture_process_exit_observed') -and
    $cli.Contains('$captureProcessExitObserved = $process.WaitForExit(5000)') -and
    $cli.Contains('Assert-RunLogProcessExitObserved $captureProcessExitObserved') -and
    $cli.Contains('Synthetic failure cleanup accepted an uncontained log capture process')) "Run-owned capture termination is not positively observed or failure-cleanup enforced."
Require ($cli.Contains('Get-TargetAbsenceCleanupDecision') -and
    $cli.Contains('Synthetic target cleanup did not preserve idempotent already-absent behavior')) "Idempotent partial-install cleanup simulation is missing."
Require ($cli.Contains('Assert-StageReceiptSemantics') -and
    $cli.Contains('checkpoint_stage =') -and
    $cli.Contains('operation=[string]$_.receipt.operation') -and
    $cli.Contains('require_wifi_rebind_e2e')) "Stage semantic closure or Wi-Fi checkpoint policy binding is incomplete."
Require ($cli.Contains('receipt operation/provider/status multiset is not exact') -and
    $cli.Contains('"hub-real-service|android-foreground-service|passed"') -and
    ([regex]::Matches($cli, '"wait-surface\|rusty-hostess\|passed", "wait-surface\|rusty-hostess\|passed"').Count -ge 3)) "Stage closure does not bind exact service and dual surface-oracle multiplicity."
Require ($cli.Contains('Restore stage must contain exactly one target restoration receipt.') -and
    $cli.Contains('Restore stage target ordering or Hub identity is not exact.') -and
    $cli.Contains('Test-ExactBoolean $restoredPackages[0].prior_running $true') -and
    $cli.Contains('"hub-start|debug-shell-foreground-service|passed"')) "Restore-stage closure does not bind the conditional prior-running Hub restart receipts."
Require ($cli.Contains('Revoke-RunOwnedSessionIfPresent') -and
    $cli.Contains('session_file = [System.IO.Path]::GetFullPath($SessionFile)') -and
    $cli.Contains('expired-session-negative-proof') -and
    $cli.Contains('failure_reclassified_only_after_credential_closure') -and
    $cli.Contains('Hostess authentication was rejected before locally recorded session expiry') -and
    $cli.Contains('capture_process_absence_observed=$true')) "Interrupted pairing/log cleanup does not bind and close the run-owned resources."
Require ($cli.Contains('$closedHistoricalFailureSha256') -and
    $cli.Contains('historical_failure_closed=[bool]$historicalFailureClosed') -and
    $cli.Contains('historical_failed_receipts_closed=$closedHistoricalFailureSha256.Count')) "Cleanup manifests do not preserve and explicitly close historical failed receipts."
Require ($cli.Contains('Initialize-Checkpoint') -and $cli.Contains('Resume checkpoint artifact digest mismatch')) "Resume checkpoint identity binding is incomplete."
Require ($cli.Contains('ProviderLifetimeSeconds') -and $cli.Contains('Wait-Surface "surface.spatial_video_control.media" $false')) "Sequential provider/lifetime oracle is incomplete."
Require ($cli.Contains('Invoke-HostessCheckedRaw "wait-surface" @(') -and
    $cli.Contains('"--session-file", $SessionFile') -and
    $cli.Contains('"--max-events", "128"') -and
    $cli.Contains('"--keepalive-interval-seconds", "5"') -and
    $cli.Contains('$providerLifetimeMaxEvents = 256') -and
    $cli.Contains('"--max-events", [string]$providerLifetimeMaxEvents') -and
    $cli.Contains('[int]$watchDetails.event_count -le $providerLifetimeMaxEvents') -and
    $cli.Contains('provider-lifetime-oracle-failure') -and
    $cli.Contains('keepalive_count_satisfied = $keepaliveCountSatisfied') -and
    $cli.Contains('rusty.hostess.connection_hub.wait_surface_receipt.v1') -and
    $cli.Contains('single-transport provider lifetime watch')) "Surface/lifetime observation must use bounded single-transport Hostess routes."
Require ($cli.Contains('authenticated_socket_open_before_revoke') -and
    $cli.Contains('authenticated_socket_closed_within_deadline') -and
    $cli.Contains('stale_bearer_auth_rejected') -and
    $cli.Contains('credentials_deleted_after_negative_proof') -and
    -not $cli.Contains('function Assert-RevokedSocketClosed')) "Same-process revoke/closed-socket/stale-bearer proof is missing."
Require ($cli.Contains('Test-ExactBoolean $effect.request_binding_exact $true') -and
    $cli.Contains('Test-ExactBoolean $effect.authority_accepted $true') -and
    $cli.Contains('Test-ExactBoolean $effect.provider_applied $true') -and
    $cli.Contains('provider_effect_observed')) "Device command oracle does not require an exact observed provider effect."
Require ($cli.Contains('Restart-HubProcess') -and $cli.Contains('START_STICKY did not produce a new Hub process')) "Process restart/FGS recovery oracle is missing."
Require ($provider.Contains('active_controller_sessions') -and
    $cli.Contains('authenticated_controller_session_restored') -and
    $cli.Contains('Test-ExactJsonInteger $status.owner_receipt.active_controller_sessions 1')) "Process restart does not prove authenticated session restoration."
Require ($cli.Contains('function Get-HostessTimeoutMilliseconds') -and
    $cli.Contains('function Get-SafeHostessFailureReason') -and
    $cli.Contains("'rusty.hostess.connection_hub.cli_error.v1'") -and
    $cli.Contains("'^command_rejected:[a-z0-9_().:-]{1,192}$'") -and
    $cli.Contains('output_retained=$false') -and
    $cli.Contains('pairing_secret_in_receipt=$false') -and
    $cli.Contains('bearer_token_in_receipt=$false') -and
    $cli.Contains('Hostess $Verb failed closed')) "Hostess subprocesses are not bounded with secret-free failure receipts."
Require ($cli.Contains('not_run_safety_reason') -and $cli.Contains('RequireWifiRebindE2E')) "Wi-Fi rebind safety gate is missing."
Require ($cli.Contains('connection-hub-protocol-v2.json') -and
    $cli.Contains('[switch]$LegacyV1') -and $cli.Contains('"--legacy-v1"') -and
    $cli.Contains('rusty.hostess.connection_hub.pair_receipt.v2') -and
    $cli.Contains('rollover_safe')) "Default v2 and explicit non-rollover-safe legacy selection are incomplete."
Require ($cli.Contains('Hostess keepalive renewal did not prove exact next-sequence advancement') -and
    $cli.Contains('V2 command receipt did not prove exact accepted next-sequence advancement') -and
    $cli.Contains('Reconnect did not prove a newer transport with unchanged v2 external sequence')) "V2 keepalive/command/reconnect sequence oracles are incomplete."
Require ($cli.Contains('function Assert-HostessStatusReceipt') -and
    $cli.Contains('Hostess surface snapshot did not satisfy the exact bounded list receipt contract') -and
    $cli.Contains('$Details.rollover_safe -isnot [bool]')) "Hostess status/list protocol receipts are not validated without Boolean coercion."
$operatorAst = [System.Management.Automation.Language.Parser]::ParseFile($cliPath, [ref]$null, [ref]$null)
$exactBooleanFunction = $operatorAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Test-ExactBoolean'
}, $true)
Require ($null -ne $exactBooleanFunction) "Exact Boolean validator is missing."
. ([scriptblock]::Create($exactBooleanFunction.Extent.Text))
Require ((Test-ExactBoolean $true $true) -and (Test-ExactBoolean $false $false) -and
    -not (Test-ExactBoolean 'true' $true) -and -not (Test-ExactBoolean 'false' $false) -and
    -not (Test-ExactBoolean 1 $true) -and -not (Test-ExactBoolean 0 $false)) `
    "Exact Boolean validator accepted a string or numeric impostor."
$exactIntegerFunction = $operatorAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Test-ExactJsonInteger'
}, $true)
Require ($null -ne $exactIntegerFunction) "Exact JSON integer validator is missing."
. ([scriptblock]::Create($exactIntegerFunction.Extent.Text))
Require ((Test-ExactJsonInteger 1 1) -and (Test-ExactJsonInteger ([long]::MaxValue) 1) -and
    -not (Test-ExactJsonInteger '1' 1) -and -not (Test-ExactJsonInteger 1.0 1) -and
    -not (Test-ExactJsonInteger ([decimal]1) 1) -and -not (Test-ExactJsonInteger 0 1)) `
    "Exact JSON integer validator accepted a string, fraction, decimal, or out-of-range value."
$safeFailureFunction = $operatorAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-SafeHostessFailureReason'
}, $true)
Require ($null -ne $safeFailureFunction) "Safe Hostess failure reason projection is missing."
. ([scriptblock]::Create($safeFailureFunction.Extent.Text))
$typedCommandRejection = [pscustomobject]@{
    completed_within_timeout=$true
    output=''
    stderr='{"$schema":"rusty.hostess.connection_hub.cli_error.v1","status":"failed","reason":"command_rejected:rejected_some(replay)","secrets_in_receipt":false}'
}
Require ((Get-SafeHostessFailureReason $typedCommandRejection) -eq
    'command_rejected:rejected_some(replay)') `
    "Safe Hostess failure projection discarded a bounded typed command rejection."
$componentFunction = $operatorAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Test-ExactAndroidComponentEcho'
}, $true)
Require ($null -ne $componentFunction) "Exact Android component echo validator is missing."
. ([scriptblock]::Create($componentFunction.Extent.Text))
$expectedComponent = 'io.github.example/io.github.example.ConnectionHubDebugSurfaceService'
Require ((Test-ExactAndroidComponentEcho `
        'Starting service: Intent { cmp=io.github.example/io.github.example.ConnectionHubDebugSurfaceService }' `
        $expectedComponent) -and
    (Test-ExactAndroidComponentEcho `
        'Starting service: Intent { cmp=io.github.example/.ConnectionHubDebugSurfaceService }' `
        $expectedComponent) -and
    -not (Test-ExactAndroidComponentEcho `
        'Starting service: Intent { cmp=io.github.other/.ConnectionHubDebugSurfaceService }' `
        $expectedComponent) -and
    -not (Test-ExactAndroidComponentEcho `
        'Starting service: Intent { cmp=io.github.example/.DifferentService }' `
        $expectedComponent) -and
    -not (Test-ExactAndroidComponentEcho `
        'cmp=io.github.example/.ConnectionHubDebugSurfaceService cmp=io.github.example/.ConnectionHubDebugSurfaceService' `
        $expectedComponent)) `
    "Exact Android component echo validator accepted substitution or duplicate evidence."
Require ($cli.Contains('function Force-HistoryRollover') -and
    $cli.Contains('Invoke-DebugOperator "force-rollover"') -and
    $cli.Contains('Hostess v2 lost-receipt/rollover-replay simulation failed') -and
    $cli.Contains('pre_rollover_next_external_request_sequence') -and
    $cli.Contains('fresh_command_advanced_exactly_once')) "Quest-owned rollover continuity proof is incomplete."
Require ($cli.Contains('"rollover_replay_failed_closed"') -and
    $cli.Contains('"rollover_replay_not_redispatched"') -and
    $cli.Contains('$missingChecks.Count -ne 0')) "Named lost-receipt/replay simulation checks are not required."
Require (-not $cli.Contains('AdbArguments')) "A generic caller-supplied ADB argument surface is forbidden."

Require ($build.Contains('[switch]$EnableConnectionHubDebugOperator')) "Debug operator build gate is missing."
Require ($build.Contains('[int]$VersionCode = 1') -and
    $build.Contains('[string]$VersionName = "0.1.0"') -and
    $build.Contains('"--version-code", [string]$VersionCode') -and
    $build.Contains('"--version-name", $VersionName') -and
    $build.Contains('apk_size = (Get-Item -LiteralPath $apkSigned).Length')) "Versioned Connection Hub artifact metadata is incomplete."
Require ($build.Contains('if ($EnableConnectionHubDebugOperator)')) "Debug operator manifest must be conditional."
Require ($build.Contains('return [bool]$EnableConnectionHubDebugOperator')) "Debug provider source must be excluded by default."
Require ($build.Contains('connection_hub_debug_operator = [bool]$EnableConnectionHubDebugOperator')) "Build receipt must disclose debug operator inclusion."
Require ($provider.Contains('android.permission.DUMP') -eq $false) "Permission authority belongs in the generated manifest, not provider logic."
Require ($provider.Contains('Binder.getCallingUid() != Process.SHELL_UID')) "Debug operator must reject non-shell callers."
Require ($provider.Contains('pairing_secret_in_receipt') -and -not $provider.Contains('receipt.put("pairing_code"')) "Debug receipt must prove secret exclusion."
Require ($provider.Contains('"pair-code".equals(method)') -and $provider.Contains('secret_b64')) "Debug-only one-use secret projection is missing."
Require (-not $releaseManifest.Contains('ConnectionHubDebugControlProvider')) "Release product fixture must exclude the debug operator."
Require ($build.Contains('android:name=".ConnectionHubOperatorProvider"') -and
    $build.Contains('android:permission="android.permission.DUMP"') -and
    $build.Contains('if ($connectionHubSelected)')) "Selected normal Hub products must package the fixed DUMP-gated operator provider."
Require ($publishedProvider.Contains('Binder.getCallingUid() != Process.SHELL_UID') -and
    $publishedProvider.Contains('ConnectionHubOperatorController.ACTION_PAIR') -and
    $publishedProvider.Contains('ConnectionHubOperatorController.ACTION_REVOKE') -and
    -not $publishedProvider.Contains('client_id') -and
    -not $publishedProvider.Contains('caller_selected_capability')) "Published operator identity or closed method boundary is incomplete."
Require ($operatorController.Contains('transition("sent")') -and
    $operatorController.Contains('transition("pending")') -and
    $operatorController.Contains('"outcome_unknown"') -and
    $operatorController.Contains('"secrets_in_receipt", false')) "Published operator receipt/effect confirmation state machine is incomplete."
Require ($operatorGuide.Contains('## Published ADB operator') -and
    $operatorGuide.Contains('`start`, `stop`,') -and
    $operatorGuide.Contains('`pair`, `revoke`, and `forget`') -and
    $operatorGuide.Contains('shell history')) "Published operator guide is incomplete or loses secret handling guidance."
Require ($activity.Contains('ACTION_DEBUG_START_HUB') -and $activity.Contains('startForegroundService')) "Typed real Activity-to-FGS lifecycle route is missing."
Require ($browserHarness.Contains('process.stdin') -and $browserHarness.Contains('consoleErrors') -and $browserHarness.Contains('spatial-removed-sample-present-command-applied')) "Real browser sequential-surface E2E harness is incomplete."
Require (-not $browserHarness.Contains('--pairing-code') -and -not $browserHarness.Contains('pairing_code:')) "Browser harness must not accept or emit a pairing-code argument/field."
Require ($releaseBuild.Contains('rusty.quest.connection_hub_labs_release.v1') -and
    $releaseBuild.Contains('Connection Hub Labs release requires a clean exact Rusty Quest worktree') -and
    $releaseBuild.Contains('$buildManifest.connection_hub_debug_operator -ne $false') -and
    $releaseBuild.Contains('transport_classification = "trusted_lan_experimental"') -and
    $releaseBuild.Contains('confidentiality = "none"') -and
    $releaseBuild.Contains('insecure_trusted_lan_requires_explicit_opt_in = $true')) "Connection Hub Labs release builder lost its clean-tree, release-only, or plaintext opt-in boundary."
Require ($releaseGuide.Contains('`transport_classification=trusted_lan_experimental`') -and
    $releaseGuide.Contains('`confidentiality=none`') -and
    $releaseGuide.Contains('`production_eligible=false`') -and
    $releaseGuide.Contains('--allow-insecure-trusted-lan') -and
    $releaseGuide.Contains('release build excludes the shell-only')) "Connection Hub Labs release guide lost its security or operator boundary."
foreach ($adapterStatus in @(
    'adapter_session_not_active',
    'adapter_missing_required_field',
    'adapter_epoch_field_invalid',
    'adapter_digest_invalid',
    'adapter_session_id_invalid',
    'adapter_lease_id_invalid',
    'adapter_command_id_invalid',
    'adapter_request_id_invalid',
    'adapter_schema_id_invalid',
    'adapter_identifier_invalid',
    'adapter_command_binding_invalid'
)) {
    Require ($nativeAdapter.Contains('"' + $adapterStatus + '"')) "Native adapter is missing typed rejection $adapterStatus."
    Require ($operatorGuide.Contains('`' + $adapterStatus + '`')) "Operator guide is missing typed rejection $adapterStatus."
}
Require ($nativeAdapter.Contains('"applied": false') -and $nativeAdapter.Contains('"authority_receipt": {}')) "Typed native adapter rejections must remain fail closed and carry no authority acceptance receipt."

$planText = & pwsh -NoProfile -File $cliPath -Action E2E -Serial SIMULATED123 -DryRun
if ($LASTEXITCODE -ne 0) { throw "Operator dry-run failed." }
$plan = $planText | ConvertFrom-Json
Require ([string]$plan.'$schema' -eq 'rusty.quest.connection_hub.operator_plan.v1') "Dry-run schema mismatch."
Require ($plan.qfm_first -eq $true -and $plan.qfm_exact_sha256_required -eq $true) "Dry-run lost QFM-first exact-pin policy."
Require (@($plan.e2e_sequence).Count -eq 23 -and $plan.e2e_sequence[-1] -eq 'target-only-pre-state-restore') "Dry-run E2E sequence is incomplete."
Require ($plan.socket_protocol -eq 'rusty.quest.connection_hub.v2' -and $plan.rollover_safe -eq $true) "Dry-run did not default to rollover-safe v2."
Require ($plan.provider_lifetime_seconds -gt 120 -and $plan.checkpoint_resume -match 'artifacts') "Dry-run omitted lifetime/checkpoint gates."
Require ($plan.secrets_in_plan -eq $false) "Dry-run plan secret posture mismatch."
$legacyPlanText = & pwsh -NoProfile -File $cliPath -Action E2E -Serial SIMULATED123 -DryRun -LegacyV1
if ($LASTEXITCODE -ne 0) { throw "Legacy operator dry-run failed." }
$legacyPlan = $legacyPlanText | ConvertFrom-Json
Require ($legacyPlan.socket_protocol -eq 'rusty.quest.connection_hub.v1' -and $legacyPlan.rollover_safe -eq $false) "Legacy v1 was not explicit and non-rollover-safe."
$legacyBrowserText = & pwsh -NoProfile -File $cliPath -Action BrowserE2E -Serial SIMULATED123 -DryRun -LegacyV1 2>$null
Require ($LASTEXITCODE -ne 0 -and [string]::IsNullOrWhiteSpace(($legacyBrowserText -join ""))) "Standalone browser E2E must reject a legacy-v1 label."

if ($null -eq ('RustyQuest.ConnectionHub.Tools.BoundedProcessCapture' -as [type])) {
    Add-Type -Path (Join-Path $RepoRoot "tools\ConnectionHubBoundedProcessCapture.cs")
}
$largeOutputCommand = @'
$out = 'o' * 262144
$err = 'e' * 131072
[Console]::Out.Write($out)
[Console]::Error.Write($err)
'@
$largeCapture = [RustyQuest.ConnectionHub.Tools.BoundedProcessCapture]::Run(
    (Join-Path $PSHOME "pwsh.exe"),
    @("-NoProfile", "-Command", $largeOutputCommand),
    10000,
    1048576,
    5000)
Require ($largeCapture.CompletedWithinTimeout -and $largeCapture.DrainCompleted -and
    $largeCapture.ExitCode -eq 0 -and
    $largeCapture.StandardOutput.Length -eq 262144 -and
    $largeCapture.StandardError.Length -eq 131072 -and
    -not $largeCapture.StandardOutputExceededLimit -and
    -not $largeCapture.StandardErrorExceededLimit) `
    "Bounded process capture did not drain simultaneous output larger than Windows pipe capacity."
$timeoutCommand = @'
[Console]::Out.Write('started')
[Console]::Error.Write('waiting')
Start-Sleep -Seconds 30
'@
$timeoutCapture = [RustyQuest.ConnectionHub.Tools.BoundedProcessCapture]::Run(
    (Join-Path $PSHOME "pwsh.exe"),
    @("-NoProfile", "-Command", $timeoutCommand),
    1000,
    1048576,
    5000)
Require (-not $timeoutCapture.CompletedWithinTimeout -and $timeoutCapture.DrainCompleted -and
    $null -eq $timeoutCapture.ExitCode -and
    [System.Text.Encoding]::UTF8.GetString($timeoutCapture.StandardOutput) -eq 'started' -and
    [System.Text.Encoding]::UTF8.GetString($timeoutCapture.StandardError) -eq 'waiting') `
    "Bounded process capture did not terminate and drain a timed-out process tree."

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("connection-hub-operator-test-" + [Guid]::NewGuid().ToString("N"))
try {
    $runText = & pwsh -NoProfile -File $cliPath -Action SimulateE2E -Serial SIMULATED123 -EvidenceRoot $tempRoot
    if ($LASTEXITCODE -ne 0) { throw "Operator simulation failed." }
    $run = $runText | ConvertFrom-Json
    Require ($run.result -eq 'passed' -and $run.secrets_in_output -eq $false) "Simulation run posture mismatch."
    $manifest = Get-Content -Raw -LiteralPath $run.evidence_manifest | ConvertFrom-Json
    Require ([string]$manifest.'$schema' -eq 'rusty.quest.connection_hub.operator_evidence_manifest.v1') "Simulation evidence schema mismatch."
    Require (@($manifest.receipts).Count -eq 23 -and $manifest.secrets_in_manifest -eq $false) "Simulation evidence closure mismatch."
    foreach ($receipt in @($manifest.receipts)) {
        $path = Join-Path (Split-Path -Parent $run.evidence_manifest) ([string]$receipt.name)
        Require ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -eq [string]$receipt.sha256) "Simulation receipt digest mismatch."
    }
    $checkpointPath = Join-Path (Split-Path -Parent $run.evidence_manifest) "checkpoint.json"
    $checkpoint = Get-Content -Raw -LiteralPath $checkpointPath | ConvertFrom-Json
    Require (@($checkpoint.completed_stages).Count -eq 23 -and $checkpoint.secrets_in_checkpoint -eq $false) "Simulation checkpoint closure mismatch."
    $resumedText = & pwsh -NoProfile -File $cliPath -Action SimulateE2E -Serial SIMULATED123 -EvidenceRoot $tempRoot -ResumeCheckpoint $checkpointPath
    if ($LASTEXITCODE -ne 0) { throw "Operator resume simulation failed." }
    $resumed = $resumedText | ConvertFrom-Json
    $resumedManifest = Get-Content -Raw -LiteralPath $resumed.evidence_manifest | ConvertFrom-Json
    Require (@($resumedManifest.receipts).Count -eq 23 -and $resumed.result -eq 'passed') "Resumed evidence closure mismatch."
    $checkpointJson = Get-Content -Raw -LiteralPath $checkpointPath
    $relabelled = $checkpointJson | ConvertFrom-Json
    $firstStage = [string]$relabelled.completed_stages[0]
    $secondStage = [string]$relabelled.completed_stages[1]
    $firstEntries = $relabelled.stage_receipts.$firstStage
    $relabelled.stage_receipts.$firstStage = $relabelled.stage_receipts.$secondStage
    $relabelled.stage_receipts.$secondStage = $firstEntries
    [System.IO.File]::WriteAllText($checkpointPath, ($relabelled | ConvertTo-Json -Depth 30), (New-Object System.Text.UTF8Encoding($false)))
    $relabelOutput = & pwsh -NoProfile -File $cliPath -Action SimulateE2E -Serial SIMULATED123 -EvidenceRoot $tempRoot -ResumeCheckpoint $checkpointPath 2>&1
    Require ($LASTEXITCODE -ne 0 -and ($relabelOutput -join "`n") -match 'relabeled or substituted') "Resume accepted a valid receipt relabeled to another completed stage."
    [System.IO.File]::WriteAllText($checkpointPath, $checkpointJson, (New-Object System.Text.UTF8Encoding($false)))
    $duplicated = $checkpointJson | ConvertFrom-Json
    $duplicateStage = [string]$duplicated.completed_stages[0]
    $originalStageEntry = $duplicated.stage_receipts.$duplicateStage
    $duplicated.stage_receipts.$duplicateStage = @($originalStageEntry, $originalStageEntry)
    [System.IO.File]::WriteAllText($checkpointPath, ($duplicated | ConvertTo-Json -Depth 30), (New-Object System.Text.UTF8Encoding($false)))
    $duplicateOutput = & pwsh -NoProfile -File $cliPath -Action SimulateE2E -Serial SIMULATED123 -EvidenceRoot $tempRoot -ResumeCheckpoint $checkpointPath 2>&1
    Require ($LASTEXITCODE -ne 0 -and ($duplicateOutput -join "`n") -match 'multiset is not exact') "Resume accepted duplicated stage receipt multiplicity."
    [System.IO.File]::WriteAllText($checkpointPath, $checkpointJson, (New-Object System.Text.UTF8Encoding($false)))
    $wifiMismatchOutput = & pwsh -NoProfile -File $cliPath -Action SimulateE2E -Serial SIMULATED123 -EvidenceRoot $tempRoot -ResumeCheckpoint $checkpointPath -RequireWifiRebindE2E 2>&1
    Require ($LASTEXITCODE -ne 0 -and ($wifiMismatchOutput -join "`n") -match 'checkpoint identity') "Resume accepted a changed Wi-Fi-required policy."
    $sessionMismatchOutput = & pwsh -NoProfile -File $cliPath -Action SimulateE2E -Serial SIMULATED123 -EvidenceRoot $tempRoot -ResumeCheckpoint $checkpointPath -SessionFile (Join-Path $tempRoot 'substituted-session.json') 2>&1
    Require ($LASTEXITCODE -ne 0 -and ($sessionMismatchOutput -join "`n") -match 'session path mismatch') "Resume accepted a substituted Hostess session path."
    $tamperedReceiptPath = Join-Path (Split-Path -Parent $run.evidence_manifest) ([string]$manifest.receipts[0].name)
    [System.IO.File]::AppendAllText($tamperedReceiptPath, " ")
    $tamperedOutput = & pwsh -NoProfile -File $cliPath -Action SimulateE2E -Serial SIMULATED123 -EvidenceRoot $tempRoot -ResumeCheckpoint $checkpointPath 2>&1
    Require ($LASTEXITCODE -ne 0 -and ($tamperedOutput -join "`n") -match 'receipt path or digest') "Resume accepted a tampered completed-stage receipt."
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
