param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
} else {
    $RepoRoot = Resolve-Path $RepoRoot
}

function Read-RepoText {
    param([Parameter(Mandatory=$true)][string]$RelativePath)
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing QCL100 crash/relaunch watch static input: $path"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param(
        [Parameter(Mandatory=$true)][string]$Text,
        [Parameter(Mandatory=$true)][string]$Needle,
        [Parameter(Mandatory=$true)][string]$Label
    )
    if (-not $Text.Contains($Needle)) {
        throw "QCL100 crash/relaunch watch static check missing $Label token: $Needle"
    }
}

$wrapper = Read-RepoText "tools\Invoke-Qcl100CrashRelaunchWatch.ps1"
$helper = Read-RepoText "tools\qcl100_native_projection\CrashRelaunchWatch.ps1"
$routeRecovery = Read-RepoText "tools\Invoke-Qcl100RouteClearRecovery.ps1"
$brokerHello = Read-RepoText "tools\Invoke-Qcl100BrokerWebSocketHelloProbe.ps1"
$monitor = Read-RepoText "tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirectMonitored.ps1"
$readme = Read-RepoText "docs\REMOTE_CAMERA_STREAMING.md"
$repoReadme = Read-RepoText "README.md"

$tokens = @(
    "rusty.quest.qcl100_crash_relaunch_watch_run.v1",
    "rusty.quest.qcl100_crash_relaunch_watch_self_test.v1",
    "qcl100-crash-relaunch-watch-summary.json",
    "qcl100-crash-relaunch-watch-self-test.json",
    "SubjectRole",
    "BaselineSummaryPath",
    "owner_uptime_seconds_after_second_report",
    "planning_watch_artifact",
    "MaxLogcatLines",
    "boot_count",
    "/proc/uptime",
    "ro.boot.bootreason",
    "sys.boot.reason",
    "sys.boot_completed",
    "logcat",
    "-d",
    "-t",
    "bounded-logcat-tail.txt",
    "focused-crash-relaunch-logcat.txt",
    "SurfaceUtils",
    "onShutdown",
    "crash-uploader",
    "Crash uploaded",
    "AndroidRuntime",
    "tombstone",
    "system_server",
    "os_reboot_proven",
    "crash_relaunch_or_surface_shutdown_suspected",
    "surface_shutdown_seen_without_reboot_proof",
    "boot_count_changed",
    "uptime_decreased",
    "no_logcat_clear",
    "no_package_launch",
    "no_force_stop",
    "no_wifi_mutation",
    "no_media_command",
    "diagnosis_only",
    "live_qcl100_qcl099_media_paused = `$true",
    "non_media_broker_hello_allowed = `$false",
    "watch_clearance_status",
    "promotion_allowed = `$false"
)
foreach ($token in $tokens) {
    Assert-Contains -Text ($wrapper + "`n" + $helper) -Needle $token -Label "script/helper"
}

foreach ($token in @(
    "rusty.quest.qcl100_route_clear_recovery.v1",
    "route_cleanup_preflight_only_after_qcl100_crash_relaunch_watch",
    "Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirectMonitored.ps1",
    "PreflightOnly = `$true",
    "RunQcl041PreclearBeforeAirgapPreflight = `$true",
    "RequireInfrastructureWifiDisconnected = `$true",
    "RequireP2p0Ipv4Cleared = `$true",
    "RequireCandidateWifiDirectRoutesClear = `$true",
    "SkipWakePrep = `$true",
    "live_qcl100_qcl099_media_paused = `$true",
    "non_media_broker_hello_allowed = `$false",
    "promotion_allowed = `$false",
    "allowed_next_slice = `"route_cleanup_preflight_only`"",
    "requires_human_review_before_media = `$true",
    "forbidden_live_actions",
    "blocked_missing_lease_ids",
    "route_clear_recovery_requires_owner_and_client_lease_ids",
    "rusty.quest.qcl100_route_clear_sensorlock_preflight.v1",
    "sensorlock_preflight_required",
    "sensorlock_preflight_artifact",
    "blocked_sensorlock",
    "sensorlock_active_before_route_clear_recovery",
    "SensorLock",
    "qcl100-route-clear-recovery-wrapper.json"
)) {
    Assert-Contains -Text $routeRecovery -Needle $token -Label "route-clear recovery wrapper"
}

foreach ($token in @(
    "[switch]`$PreflightOnly",
    "PreflightOnly = [bool]`$PreflightOnly"
)) {
    Assert-Contains -Text $monitor -Needle $token -Label "monitored preflight-only forwarding"
}

foreach ($forbidden in @(
    '"logcat", "-c"',
    '"am", "start"',
    '"am", "force-stop"',
    '"svc", "wifi"',
    '"cmd", "wifi", "connect-network"',
    "command.remote_camera.start_receiver",
    "command.remote_camera.start_sender"
)) {
    if (($wrapper + "`n" + $helper).Contains($forbidden)) {
        throw "QCL100 crash/relaunch watch must remain passive and not include forbidden token: $forbidden"
    }
}

foreach ($forbidden in @(
    "New-BridgeRequest",
    "Invoke-LiveBridgeCommand",
    "owner-start-receiver",
    "client-start-receiver",
    "owner-start-source-only",
    "client-start-source-only",
    "NoMediaLaunchOnly",
    "XrLaunchReadinessOnly",
    "AllowWakePrepMutation = `$true"
)) {
    if ($routeRecovery.Contains($forbidden)) {
        throw "QCL100 route-clear recovery wrapper must not include forbidden broker/media token: $forbidden"
    }
}

foreach ($token in @(
    "rusty.quest.qcl100_broker_websocket_hello_probe.v1",
    "broker_websocket_hello_only",
    "ClientWebSocket",
    "wait-broker-websocket-ready",
    "hello_ack",
    "remote_camera_command_sent = `$false",
    "qcl041_started = `$false",
    "native_renderer_launched = `$false",
    "media_projection_started = `$false",
    "same_group_duplex_claimed = `$false",
    "promotion_claimed = `$false",
    "hardware_touched = `$false"
)) {
    Assert-Contains -Text $brokerHello -Needle $token -Label "broker WebSocket hello probe"
}

foreach ($forbidden in @(
    "New-BridgeRequest",
    "Invoke-LiveBridgeCommand",
    "Start-NativeRenderer",
    "NoMediaLaunchOnly",
    "NativeRendererActivity",
    "NativeRendererPackage"
)) {
    if ($brokerHello.Contains($forbidden)) {
        throw "QCL100 broker WebSocket hello probe must not include forbidden media/native token: $forbidden"
    }
}

Assert-Contains -Text $repoReadme -Needle "Remote Camera Streaming" -Label "repo README remote camera routing"
Assert-Contains -Text $readme -Needle "qcl100-crash-relaunch-watch" -Label "remote camera streaming crash watch docs"
Assert-Contains -Text $readme -Needle "not treated as an OS reboot by itself" -Label "remote camera streaming reboot caveat"
Assert-Contains -Text $readme -Needle "non-media broker hello" -Label "remote camera streaming broker-hello pause"
Assert-Contains -Text $readme -Needle "Invoke-Qcl100RouteClearRecovery.ps1" -Label "remote camera streaming route-clear recovery docs"

$wrapperPath = Join-Path $RepoRoot "tools\Invoke-Qcl100CrashRelaunchWatch.ps1"
$tokensToParse = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($wrapperPath, [ref]$tokensToParse, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -ne 0) {
    throw "QCL100 crash/relaunch watch wrapper has PowerShell parse errors: $($parseErrors | Out-String)"
}
$helperPath = Join-Path $RepoRoot "tools\qcl100_native_projection\CrashRelaunchWatch.ps1"
$tokensToParse = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($helperPath, [ref]$tokensToParse, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -ne 0) {
    throw "QCL100 crash/relaunch watch helper has PowerShell parse errors: $($parseErrors | Out-String)"
}
$routeRecoveryPath = Join-Path $RepoRoot "tools\Invoke-Qcl100RouteClearRecovery.ps1"
$tokensToParse = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($routeRecoveryPath, [ref]$tokensToParse, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -ne 0) {
    throw "QCL100 route-clear recovery wrapper has PowerShell parse errors: $($parseErrors | Out-String)"
}
$brokerHelloPath = Join-Path $RepoRoot "tools\Invoke-Qcl100BrokerWebSocketHelloProbe.ps1"
$tokensToParse = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($brokerHelloPath, [ref]$tokensToParse, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -ne 0) {
    throw "QCL100 broker WebSocket hello probe has PowerShell parse errors: $($parseErrors | Out-String)"
}

$selfTestDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-crash-watch-static-" + [guid]::NewGuid().ToString("N"))
try {
    $output = & $wrapperPath -SelfTest -OutDir $selfTestDir 2>&1 | Out-String
    $selfTestExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    if ($selfTestExitCode -ne 0) {
        throw "QCL100 crash/relaunch watch self-test exited with $selfTestExitCode. $output"
    }
    $selfTestPath = Join-Path $selfTestDir "qcl100-crash-relaunch-watch-self-test.json"
    if (-not (Test-Path -LiteralPath $selfTestPath)) {
        throw "QCL100 crash/relaunch watch self-test did not write $selfTestPath. Output: $output"
    }
    $selfTest = Get-Content -Raw -LiteralPath $selfTestPath | ConvertFrom-Json
    if ([string]$selfTest.status -ne "pass") {
        throw "QCL100 crash/relaunch watch self-test status was $($selfTest.status)."
    }
    if ([int]$selfTest.case_count -ne 3) {
        throw "QCL100 crash/relaunch watch self-test expected 3 cases but found $($selfTest.case_count)."
    }
} finally {
    if (Test-Path -LiteralPath $selfTestDir) {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        $resolvedSelfTestDir = [System.IO.Path]::GetFullPath($selfTestDir)
        if ($resolvedSelfTestDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $selfTestDir -Recurse -Force
        }
    }
}

$routeRecoveryDryRunDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-route-clear-recovery-static-" + [guid]::NewGuid().ToString("N"))
try {
    $routeDryRunOutput = & $routeRecoveryPath -DryRun -OutDir $routeRecoveryDryRunDir -RunId "qcl100-route-clear-recovery-static-dryrun" 2>&1 | Out-String
    $routeDryRunExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    if ($routeDryRunExitCode -ne 0) {
        throw "QCL100 route-clear recovery dry-run exited with $routeDryRunExitCode. $routeDryRunOutput"
    }
    $routePolicyPath = Join-Path $routeRecoveryDryRunDir "qcl100-route-clear-recovery-wrapper.json"
    $monitorDryRunPath = Join-Path $routeRecoveryDryRunDir "qcl100-monitor-summary.json"
    if (-not (Test-Path -LiteralPath $routePolicyPath)) {
        throw "QCL100 route-clear recovery dry-run did not write policy wrapper JSON. Output: $routeDryRunOutput"
    }
    if (-not (Test-Path -LiteralPath $monitorDryRunPath)) {
        throw "QCL100 route-clear recovery dry-run did not write monitor dry-run summary. Output: $routeDryRunOutput"
    }
    $routePolicy = Get-Content -Raw -LiteralPath $routePolicyPath | ConvertFrom-Json
    $monitorDryRun = Get-Content -Raw -LiteralPath $monitorDryRunPath | ConvertFrom-Json
    if ([string]$routePolicy.status -ne "dry_run_planned") {
        throw "QCL100 route-clear recovery dry-run policy status drifted: $($routePolicy.status)"
    }
    if (-not [bool]$routePolicy.requested_runner_flags.PreflightOnly -or
            -not [bool]$routePolicy.requested_runner_flags.RunQcl041PreclearBeforeAirgapPreflight -or
            -not [bool]$routePolicy.requested_runner_flags.RequireP2p0Ipv4Cleared -or
            -not [bool]$routePolicy.requested_runner_flags.RequireCandidateWifiDirectRoutesClear) {
        throw "QCL100 route-clear recovery dry-run policy did not preserve strict preflight flags."
    }
    if ([string]$monitorDryRun.status -ne "dry_run") {
        throw "QCL100 route-clear recovery dry-run monitor status drifted: $($monitorDryRun.status)"
    }
} finally {
    if (Test-Path -LiteralPath $routeRecoveryDryRunDir) {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        $resolvedDryRunDir = [System.IO.Path]::GetFullPath($routeRecoveryDryRunDir)
        if ($resolvedDryRunDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $routeRecoveryDryRunDir -Recurse -Force
        }
    }
}

$routeRecoveryNoLeaseDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-route-clear-recovery-no-lease-static-" + [guid]::NewGuid().ToString("N"))
try {
    $routeNoLeaseFailed = $false
    $routeNoLeaseOutput = ""
    try {
        $routeNoLeaseOutput = & $routeRecoveryPath -OutDir $routeRecoveryNoLeaseDir -RunId "qcl100-route-clear-recovery-static-no-lease" 2>&1 | Out-String
        $routeNoLeaseExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    } catch {
        $routeNoLeaseFailed = $true
        $routeNoLeaseExitCode = 1
        $routeNoLeaseOutput = $_.Exception.Message + "`n" + ($_ | Out-String)
    }
    if (-not $routeNoLeaseFailed -and $routeNoLeaseExitCode -eq 0) {
        throw "QCL100 route-clear recovery non-dry-run without leases unexpectedly succeeded. $routeNoLeaseOutput"
    }
    $routeNoLeasePolicyPath = Join-Path $routeRecoveryNoLeaseDir "qcl100-route-clear-recovery-wrapper.json"
    $routeNoLeaseMonitorPath = Join-Path $routeRecoveryNoLeaseDir "qcl100-monitor-summary.json"
    if (-not (Test-Path -LiteralPath $routeNoLeasePolicyPath)) {
        throw "QCL100 route-clear recovery no-lease guard did not write policy wrapper JSON. Output: $routeNoLeaseOutput"
    }
    if (Test-Path -LiteralPath $routeNoLeaseMonitorPath) {
        throw "QCL100 route-clear recovery no-lease guard should block before monitor execution."
    }
    $routeNoLeasePolicy = Get-Content -Raw -LiteralPath $routeNoLeasePolicyPath | ConvertFrom-Json
    if ([string]$routeNoLeasePolicy.status -ne "blocked_missing_lease_ids") {
        throw "QCL100 route-clear recovery no-lease policy status drifted: $($routeNoLeasePolicy.status)"
    }
    if ([bool]$routeNoLeasePolicy.lease_ids_supplied) {
        throw "QCL100 route-clear recovery no-lease policy incorrectly reports lease IDs supplied."
    }
} finally {
    if (Test-Path -LiteralPath $routeRecoveryNoLeaseDir) {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        $resolvedNoLeaseDir = [System.IO.Path]::GetFullPath($routeRecoveryNoLeaseDir)
        if ($resolvedNoLeaseDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $routeRecoveryNoLeaseDir -Recurse -Force
        }
    }
}

$routeRecoverySensorLockDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-route-clear-recovery-sensorlock-static-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $routeRecoverySensorLockDir | Out-Null
    $fakeAdbPath = Join-Path $routeRecoverySensorLockDir "fake-adb.ps1"
    @'
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Args)
$joined = $Args -join " "
if ($joined -like "*shell dumpsys window*") {
    "mCurrentFocus=Window{123 u0 com.oculus.os.vrlockscreen/.SensorLockActivity}"
    exit 0
}
if ($joined -like "*shell dumpsys power*") {
    "  mWakefulness=Awake"
    exit 0
}
if ($joined -like "*shell getprop sys.hmt.mounted*") {
    "1"
    exit 0
}
exit 0
'@ | Set-Content -LiteralPath $fakeAdbPath -Encoding UTF8
    $routeSensorLockFailed = $false
    $routeSensorLockOutput = ""
    try {
        $routeSensorLockOutput = & $routeRecoveryPath `
            -OutDir $routeRecoverySensorLockDir `
            -RunId "qcl100-route-clear-recovery-static-sensorlock" `
            -OwnerLeaseId "owner-lease" `
            -ClientLeaseId "client-lease" `
            -Adb $fakeAdbPath 2>&1 | Out-String
        $routeSensorLockExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    } catch {
        $routeSensorLockFailed = $true
        $routeSensorLockExitCode = 1
        $routeSensorLockOutput = $_.Exception.Message + "`n" + ($_ | Out-String)
    }
    if (-not $routeSensorLockFailed -and $routeSensorLockExitCode -eq 0) {
        throw "QCL100 route-clear recovery with fake SensorLock unexpectedly succeeded. $routeSensorLockOutput"
    }
    $routeSensorLockPolicyPath = Join-Path $routeRecoverySensorLockDir "qcl100-route-clear-recovery-wrapper.json"
    $routeSensorLockPreflightPath = Join-Path $routeRecoverySensorLockDir "qcl100-route-clear-sensorlock-preflight.json"
    $routeSensorLockMonitorPath = Join-Path $routeRecoverySensorLockDir "qcl100-monitor-summary.json"
    if (-not (Test-Path -LiteralPath $routeSensorLockPolicyPath)) {
        throw "QCL100 route-clear recovery SensorLock guard did not write policy wrapper JSON. Output: $routeSensorLockOutput"
    }
    if (-not (Test-Path -LiteralPath $routeSensorLockPreflightPath)) {
        throw "QCL100 route-clear recovery SensorLock guard did not write preflight JSON. Output: $routeSensorLockOutput"
    }
    if (Test-Path -LiteralPath $routeSensorLockMonitorPath) {
        throw "QCL100 route-clear recovery SensorLock guard should block before monitor execution."
    }
    $routeSensorLockPolicy = Get-Content -Raw -LiteralPath $routeSensorLockPolicyPath | ConvertFrom-Json
    $routeSensorLockPreflight = Get-Content -Raw -LiteralPath $routeSensorLockPreflightPath | ConvertFrom-Json
    if ([string]$routeSensorLockPolicy.status -ne "blocked_sensorlock") {
        throw "QCL100 route-clear recovery SensorLock policy status drifted: $($routeSensorLockPolicy.status)"
    }
    if (-not [bool]$routeSensorLockPreflight.sensor_lock_active) {
        throw "QCL100 route-clear recovery SensorLock preflight did not classify the fake SensorLock state."
    }
} finally {
    if (Test-Path -LiteralPath $routeRecoverySensorLockDir) {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        $resolvedSensorLockDir = [System.IO.Path]::GetFullPath($routeRecoverySensorLockDir)
        if ($resolvedSensorLockDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $routeRecoverySensorLockDir -Recurse -Force
        }
    }
}

$brokerHelloDryRunDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-broker-hello-static-" + [guid]::NewGuid().ToString("N"))
try {
    $brokerHelloDryRunOutput = & $brokerHelloPath -DryRun -OutDir $brokerHelloDryRunDir -RunId "qcl100-broker-hello-static-dryrun" 2>&1 | Out-String
    $brokerHelloDryRunExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    if ($brokerHelloDryRunExitCode -ne 0) {
        throw "QCL100 broker WebSocket hello dry-run exited with $brokerHelloDryRunExitCode. $brokerHelloDryRunOutput"
    }
    $brokerHelloSummaryPath = Join-Path $brokerHelloDryRunDir "qcl100-broker-websocket-hello-summary.json"
    if (-not (Test-Path -LiteralPath $brokerHelloSummaryPath)) {
        throw "QCL100 broker WebSocket hello dry-run did not write summary JSON. Output: $brokerHelloDryRunOutput"
    }
    $brokerHelloSummary = Get-Content -Raw -LiteralPath $brokerHelloSummaryPath | ConvertFrom-Json
    if ([string]$brokerHelloSummary.status -ne "dry_run_planned") {
        throw "QCL100 broker WebSocket hello dry-run status drifted: $($brokerHelloSummary.status)"
    }
    if ([bool]$brokerHelloSummary.hardware_touched) {
        throw "QCL100 broker WebSocket hello dry-run must not touch hardware."
    }
    foreach ($forbiddenAction in @("native renderer launch", "command.remote_camera.start_receiver", "command.remote_camera.start_sender", "media projection")) {
        if (@($brokerHelloSummary.forbidden_actions) -notcontains $forbiddenAction) {
            throw "QCL100 broker WebSocket hello dry-run missing forbidden action: $forbiddenAction"
        }
    }
} finally {
    if (Test-Path -LiteralPath $brokerHelloDryRunDir) {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        $resolvedBrokerHelloDryRunDir = [System.IO.Path]::GetFullPath($brokerHelloDryRunDir)
        if ($resolvedBrokerHelloDryRunDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $brokerHelloDryRunDir -Recurse -Force
        }
    }
}

Write-Output "QCL100 crash/relaunch watch static checks passed."
