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
$promotionAcceptance = Read-RepoText "tools\qcl100_native_projection\PromotionAcceptance.ps1"
$settingsFence = Read-RepoText "tools\qcl100_native_projection\SettingsFence.ps1"
$infrastructureWifiDisconnect = Read-RepoText "tools\qcl100_native_projection\InfrastructureWifiDisconnect.ps1"
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
    "`"route_cleanup_preflight_only`"",
    "requires_human_review_before_media",
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
    "PreflightOnly = [bool]`$PreflightOnly",
    "[switch]`$FenceSettingsBeforeRun",
    "SettingsFenceTarget",
    "ClearLogcatAfterSettingsFence",
    "AllowSettingsForegroundAfterFence",
    "DisconnectInfrastructureWifiBeforeRun",
    "InfrastructureWifiDisconnectTarget",
    "InfrastructureWifiSsid",
    "InfrastructureWifiDisconnectPostClickWaitMs",
    "infrastructure_wifi_disconnect_receipts",
    "blocked_infrastructure_wifi_disconnect",
    "settings_fence_receipts",
    "blocked_settings_fence",
    "Invoke-Qcl100InfrastructureWifiDisconnect",
    "Invoke-Qcl100SettingsFence",
    "Read-Qcl100CrashVitals",
    "Compare-Qcl100CrashVitals",
    "qcl100-pre-settings-crash-vitals.json",
    "qcl100-pre-run-crash-vitals.json",
    "qcl100-post-run-crash-vitals.json",
    "rusty.quest.qcl100_settings_lifecycle_acceptance.v1",
    "rusty.quest.qcl100_crash_lifecycle_acceptance.v1",
    "boot_id_stable",
    "system_server_pid_stable",
    "uptime_monotonic",
    "blocked_pre_settings_vitals_unreadable",
    "blocked_settings_lifecycle_changed",
    "blocked_system_lifecycle_changed",
    "Test-Qcl100CleanupRowsClean",
    "cleanup_readback_clean",
    "blocked_cleanup_readback_not_clean"
)) {
    Assert-Contains -Text $monitor -Needle $token -Label "monitored preflight-only/settings-fence forwarding"
}

foreach ($token in @(
    "[switch]`$RunFinalRouteClear",
    "[switch]`$PromotionSelfTest",
    "Invoke-Qcl100FinalRouteClearRecovery",
    "qcl100-final-route-clear-acceptance.json",
    "rusty.quest.qcl100_final_route_clear_acceptance.v1",
    "blocked_final_route_clear_not_accepted",
    "blocked_promotion_acceptance",
    "promotion_candidate_requested",
    "promotion_acceptance = `$promotionAcceptance",
    "same_group_duplex_claimed = [bool]`$promotionAcceptance.same_group_duplex_claimed",
    "promotion_claimed = [bool]`$promotionAcceptance.promotion_claimed",
    "final_route_clear_acceptance = `$finalRouteClearAcceptance",
    "cleanup_readback_source"
)) {
    Assert-Contains -Text $monitor -Needle $token -Label "monitored final promotion acceptance"
}

foreach ($token in @(
    "rusty.quest.qcl100_monitored_promotion_acceptance.v1",
    "promoted_same_group_full_stereo_duplex_with_rusty_direct_p2p_socket_authority",
    "all_monitored_full_stereo_direct_p2p_promotion_gates_passed",
    "direction_not_duplex",
    "lane_mode_not_stereo",
    "direct_p2p_required_direction_path_count_not_two",
    "direct_p2p_two_direction_paths_not_accepted",
    "client_direct_p2p_receiver_authority_not_accepted",
    "settings_lifecycle_acceptance_not_passed",
    "crash_lifecycle_acceptance_not_passed",
    "final_route_clear_not_accepted",
    "settings_fence_target_not_both",
    "child_runner_claimed_promotion",
    "final_promotion_authority = `"qcl100_monitored_promotion_acceptance`"",
    "Invoke-Qcl100PromotionAcceptanceSelfTest"
)) {
    Assert-Contains -Text $promotionAcceptance -Needle $token -Label "QCL100 monitored promotion reducer"
}

foreach ($token in @(
    "FinalPromotionRouteClear",
    "final_route_clear_before_qcl100_monitored_promotion_acceptance",
    "OwnerWifiDirectAddress",
    "ClientWifiDirectAddress"
)) {
    Assert-Contains -Text $routeRecovery -Needle $token -Label "QCL100 final route-clear forwarding"
}

foreach ($token in @(
    "rusty.quest.qcl100_settings_fence.v1",
    "rusty.quest.qcl100_settings_fence_plan.v1",
    "com.oculus.panelapp.settings",
    "com.android.settings",
    '"input", "keyevent", "HOME"',
    '"logcat", "-c"',
    "foreground_not_settings",
    "ready_for_qcl041_group_formation",
    "no_wifi_mutation",
    "media_started = `$false",
    "qcl041_started = `$false"
)) {
    Assert-Contains -Text ($monitor + "`n" + $settingsFence) -Needle $token -Label "QCL100 Settings fence helper/monitor"
}

foreach ($token in @(
    "rusty.quest.qcl100_infrastructure_wifi_disconnect.v1",
    "rusty.quest.qcl100_infrastructure_wifi_disconnect_plan.v1",
    "settingsWifiDisconnectProbe",
    "allowDisconnect",
    "networkClickMode",
    "disconnectClickMode",
    "uiObject2",
    "cmd",
    "wifi",
    "status",
    "blocked_unexpected_ssid",
    "failed_still_connected",
    "pass_already_disconnected",
    "dry_probe_before_mutation",
    "no_forget_targeted",
    "wifi_radio_mutated = `$false",
    "requires_settings_fence_after_disconnect"
)) {
    Assert-Contains -Text ($monitor + "`n" + $infrastructureWifiDisconnect) -Needle $token -Label "QCL100 guarded infrastructure Wi-Fi disconnect"
}

foreach ($token in @(
    "FenceSettingsBeforeRun = [bool](-not `$SkipSettingsFence)",
    "SettingsFenceTarget = `$SettingsFenceTarget",
    "DisconnectInfrastructureWifiBeforeRun = [bool]`$DisconnectInfrastructureWifiBeforeRun",
    "InfrastructureWifiDisconnectTarget = `$InfrastructureWifiDisconnectTarget",
    "InfrastructureWifiSsid = `$InfrastructureWifiSsid",
    "infrastructure_wifi_disconnect_requested",
    "guarded_settings_disconnect_before_route_clear_preflight",
    "settings_fence_required",
    "force_stop_settings_surfaces_before_qcl041_preclear"
)) {
    Assert-Contains -Text $routeRecovery -Needle $token -Label "route-clear recovery Settings fence forwarding"
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
Assert-Contains -Text $readme -Needle "ConnectionProbe.updateMatchedScanResults" -Label "remote camera streaming W crash diagnosis docs"
Assert-Contains -Text $readme -Needle "-FenceSettingsBeforeRun" -Label "remote camera streaming Settings fence docs"
Assert-Contains -Text $readme -Needle "crash mitigation and preflight evidence only" -Label "remote camera streaming Settings fence non-promotion docs"
Assert-Contains -Text $readme -Needle "therefore a same-epoch left-lane diagnostic pass" -Label "remote camera streaming simultaneous left-lane scope"
Assert-Contains -Text $readme -Needle "Windows Kernel-PnP event 1010" -Label "remote camera streaming Q USB removal evidence"
Assert-Contains -Text $readme -Needle "not distinguish cable/port/power loss from a headset USB-stack or OS reset" -Label "remote camera streaming Q USB root-cause caveat"
Assert-Contains -Text $readme -Needle "-Direction duplex -LaneMode stereo -RunFinalRouteClear" -Label "remote camera streaming monitored promotion invocation"
Assert-Contains -Text $readme -Needle "rusty.quest.qcl100_monitored_promotion_acceptance.v1" -Label "remote camera streaming monitored promotion contract"
Assert-Contains -Text $readme -Needle "Only that reducer may emit" -Label "remote camera streaming final promotion authority"

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
$settingsFencePath = Join-Path $RepoRoot "tools\qcl100_native_projection\SettingsFence.ps1"
$tokensToParse = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($settingsFencePath, [ref]$tokensToParse, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -ne 0) {
    throw "QCL100 Settings fence helper has PowerShell parse errors: $($parseErrors | Out-String)"
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
    $routeDryRunOutput = & $routeRecoveryPath `
        -DryRun `
        -OutDir $routeRecoveryDryRunDir `
        -RunId "qcl100-route-clear-recovery-static-dryrun" `
        -DisconnectInfrastructureWifiBeforeRun `
        -InfrastructureWifiDisconnectTarget owner `
        -InfrastructureWifiSsid "MagentaWLAN-R5V4" 2>&1 | Out-String
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
            -not [bool]$routePolicy.requested_runner_flags.RequireCandidateWifiDirectRoutesClear -or
            -not [bool]$routePolicy.requested_runner_flags.DisconnectInfrastructureWifiBeforeRun) {
        throw "QCL100 route-clear recovery dry-run policy did not preserve strict preflight flags."
    }
    if (-not [bool]$routePolicy.crash_watch_policy.requires_human_review_before_media -or
        [bool]$routePolicy.crash_watch_policy.promotion_candidate_parent_authorized) {
        throw "Standalone QCL100 route-clear recovery must retain human review before media."
    }
    if ([string]$monitorDryRun.status -ne "dry_run") {
        throw "QCL100 route-clear recovery dry-run monitor status drifted: $($monitorDryRun.status)"
    }
    if (-not [bool]$monitorDryRun.infrastructure_wifi_disconnect.requested -or
            [string]$monitorDryRun.infrastructure_wifi_disconnect.target -ne "owner" -or
            [string]$monitorDryRun.infrastructure_wifi_disconnect.target_ssid -ne "MagentaWLAN-R5V4") {
        throw "QCL100 route-clear recovery monitor dry-run did not preserve guarded infrastructure Wi-Fi disconnect plan."
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

$monitorFenceDryRunDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-monitor-settings-fence-static-" + [guid]::NewGuid().ToString("N"))
try {
    $monitorPath = Join-Path $RepoRoot "tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirectMonitored.ps1"
    $monitorFenceDryRunOutput = & $monitorPath `
        -DryRun `
        -OutDir $monitorFenceDryRunDir `
        -RunId "qcl100-monitor-settings-fence-static-dryrun" `
        -DisconnectInfrastructureWifiBeforeRun `
        -InfrastructureWifiDisconnectTarget owner `
        -InfrastructureWifiSsid "MagentaWLAN-R5V4" `
        -FenceSettingsBeforeRun `
        -SettingsFenceTarget owner 2>&1 | Out-String
    $monitorFenceDryRunExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    if ($monitorFenceDryRunExitCode -ne 0) {
        throw "QCL100 monitor Settings fence dry-run exited with $monitorFenceDryRunExitCode. $monitorFenceDryRunOutput"
    }
    $monitorFenceSummaryPath = Join-Path $monitorFenceDryRunDir "qcl100-monitor-summary.json"
    if (-not (Test-Path -LiteralPath $monitorFenceSummaryPath)) {
        throw "QCL100 monitor Settings fence dry-run did not write monitor summary. Output: $monitorFenceDryRunOutput"
    }
    $monitorFenceSummary = Get-Content -Raw -LiteralPath $monitorFenceSummaryPath | ConvertFrom-Json
    if ([string]$monitorFenceSummary.status -ne "dry_run") {
        throw "QCL100 monitor Settings fence dry-run status drifted: $($monitorFenceSummary.status)"
    }
    if (-not [bool]$monitorFenceSummary.settings_fence_requested) {
        throw "QCL100 monitor Settings fence dry-run did not preserve settings_fence_requested."
    }
    if ([bool]$monitorFenceSummary.settings_fence.hardware_touched) {
        throw "QCL100 monitor Settings fence dry-run must not touch hardware."
    }
    if ([string]$monitorFenceSummary.settings_fence.target -ne "owner") {
        throw "QCL100 monitor Settings fence dry-run target drifted: $($monitorFenceSummary.settings_fence.target)"
    }
    if (-not [bool]$monitorFenceSummary.infrastructure_wifi_disconnect_requested) {
        throw "QCL100 monitor guarded infrastructure Wi-Fi disconnect dry-run did not preserve infrastructure_wifi_disconnect_requested."
    }
    if ([bool]$monitorFenceSummary.infrastructure_wifi_disconnect.hardware_touched) {
        throw "QCL100 monitor guarded infrastructure Wi-Fi disconnect dry-run claimed hardware was touched."
    }
    if ([string]$monitorFenceSummary.infrastructure_wifi_disconnect.target -ne "owner") {
        throw "QCL100 monitor guarded infrastructure Wi-Fi disconnect dry-run target drifted: $($monitorFenceSummary.infrastructure_wifi_disconnect.target)"
    }
    if ([string]$monitorFenceSummary.infrastructure_wifi_disconnect.target_ssid -ne "MagentaWLAN-R5V4") {
        throw "QCL100 monitor guarded infrastructure Wi-Fi disconnect dry-run SSID drifted: $($monitorFenceSummary.infrastructure_wifi_disconnect.target_ssid)"
    }
    if (@($monitorFenceSummary.infrastructure_wifi_disconnect_receipts).Count -ne 0) {
        throw "QCL100 monitor guarded infrastructure Wi-Fi disconnect dry-run should not produce live receipts."
    }
} finally {
    if (Test-Path -LiteralPath $monitorFenceDryRunDir) {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        $resolvedMonitorFenceDryRunDir = [System.IO.Path]::GetFullPath($monitorFenceDryRunDir)
        if ($resolvedMonitorFenceDryRunDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $monitorFenceDryRunDir -Recurse -Force
        }
    }
}

$promotionSelfTestDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-promotion-self-test-static-" + [guid]::NewGuid().ToString("N"))
try {
    $promotionSelfTestOutput = & $monitorPath `
        -PromotionSelfTest `
        -OutDir $promotionSelfTestDir 2>&1 | Out-String
    $promotionSelfTestExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    if ($promotionSelfTestExitCode -ne 0) {
        throw "QCL100 promotion acceptance self-test exited with $promotionSelfTestExitCode. $promotionSelfTestOutput"
    }
    $promotionSelfTestPath = Join-Path $promotionSelfTestDir "qcl100-promotion-acceptance-self-test.json"
    $promotionSelfTestResult = Get-Content -Raw -LiteralPath $promotionSelfTestPath | ConvertFrom-Json
    if ([string]$promotionSelfTestResult.status -ne "pass" -or [int]$promotionSelfTestResult.fixture_count -ne 9) {
        throw "QCL100 promotion acceptance self-test result drifted. $promotionSelfTestOutput"
    }
} finally {
    if (Test-Path -LiteralPath $promotionSelfTestDir) {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        $resolvedPromotionSelfTestDir = [System.IO.Path]::GetFullPath($promotionSelfTestDir)
        if ($resolvedPromotionSelfTestDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $promotionSelfTestDir -Recurse -Force
        }
    }
}

$promotionDryRunDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-promotion-dry-run-static-" + [guid]::NewGuid().ToString("N"))
try {
    $promotionDryRunOutput = & $monitorPath `
        -DryRun `
        -OutDir $promotionDryRunDir `
        -RunId "qcl100-promotion-static-dryrun" `
        -Direction duplex `
        -LaneMode stereo `
        -FenceSettingsBeforeRun `
        -SettingsFenceTarget both `
        -RunFinalRouteClear 2>&1 | Out-String
    $promotionDryRunExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    if ($promotionDryRunExitCode -ne 0) {
        throw "QCL100 promotion dry-run exited with $promotionDryRunExitCode. $promotionDryRunOutput"
    }
    $promotionDryRunSummary = Get-Content -Raw -LiteralPath (Join-Path $promotionDryRunDir "qcl100-monitor-summary.json") | ConvertFrom-Json
    if (-not [bool]$promotionDryRunSummary.promotion_candidate_requested -or
        -not [bool]$promotionDryRunSummary.final_route_clear_requested -or
        [string]$promotionDryRunSummary.final_route_clear.settings_fence_target -ne "both" -or
        [bool]$promotionDryRunSummary.promotion_claimed) {
        throw "QCL100 promotion dry-run did not preserve fail-closed full-stereo planning. $promotionDryRunOutput"
    }
} finally {
    if (Test-Path -LiteralPath $promotionDryRunDir) {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        $resolvedPromotionDryRunDir = [System.IO.Path]::GetFullPath($promotionDryRunDir)
        if ($resolvedPromotionDryRunDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $promotionDryRunDir -Recurse -Force
        }
    }
}

$settingsLifecycleDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-settings-lifecycle-static-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $settingsLifecycleDir | Out-Null
    $fakeLifecycleAdbPath = Join-Path $settingsLifecycleDir "fake-lifecycle-adb.ps1"
    @'
$joined = $args -join " "
if ($joined -match "settings get global boot_count") {
    Write-Output "90"
    exit 0
}
if ($joined -match "cat /proc/sys/kernel/random/boot_id") {
    Write-Output "11111111-2222-3333-4444-555555555555"
    exit 0
}
if ($joined -match "cat /proc/uptime") {
    Write-Output "1000.00 100.00"
    exit 0
}
if ($joined -match "pidof system_server") {
    $counterPath = Join-Path $PSScriptRoot "pid-call-count.txt"
    $count = if (Test-Path -LiteralPath $counterPath) { [int](Get-Content -Raw -LiteralPath $counterPath) } else { 0 }
    $count += 1
    Set-Content -LiteralPath $counterPath -Value $count -Encoding ASCII
    Write-Output $(if ($count -le 2) { "30291" } else { "30999" })
    exit 0
}
if ($joined -match "getprop ro.boot.bootreason") {
    Write-Output "reboot"
    exit 0
}
Write-Error "Unexpected fake ADB command: $joined"
exit 1
'@ | Set-Content -LiteralPath $fakeLifecycleAdbPath -Encoding UTF8

    $settingsLifecycleRunDir = Join-Path $settingsLifecycleDir "run"
    $settingsLifecycleFailed = $false
    $settingsLifecycleOutput = ""
    try {
        $settingsLifecycleOutput = & $monitorPath `
            -OutDir $settingsLifecycleRunDir `
            -RunId "qcl100-settings-lifecycle-static" `
            -Adb $fakeLifecycleAdbPath 2>&1 | Out-String
    } catch {
        $settingsLifecycleFailed = $true
        $settingsLifecycleOutput = $_.Exception.Message + "`n" + ($_ | Out-String)
    }
    if (-not $settingsLifecycleFailed) {
        throw "QCL100 fake Settings-window system_server replacement unexpectedly reached the runner. $settingsLifecycleOutput"
    }
    $settingsLifecycleSummaryPath = Join-Path $settingsLifecycleRunDir "qcl100-monitor-summary.json"
    if (-not (Test-Path -LiteralPath $settingsLifecycleSummaryPath)) {
        throw "QCL100 Settings lifecycle guard did not write a monitor summary. $settingsLifecycleOutput"
    }
    $settingsLifecycleSummary = Get-Content -Raw -LiteralPath $settingsLifecycleSummaryPath | ConvertFrom-Json
    if ([string]$settingsLifecycleSummary.status -ne "blocked_settings_lifecycle_changed" -or
        [bool]$settingsLifecycleSummary.settings_lifecycle_acceptance.passed -or
        [bool]$settingsLifecycleSummary.settings_lifecycle_acceptance.owner.system_server_pid_stable) {
        throw "QCL100 Settings lifecycle guard did not fail closed on the fake system_server replacement. $settingsLifecycleOutput"
    }
    if (Test-Path -LiteralPath (Join-Path $settingsLifecycleRunDir "native-stereo-projection-summary.json")) {
        throw "QCL100 Settings lifecycle guard started the native runner after a lifecycle change."
    }
} finally {
    if (Test-Path -LiteralPath $settingsLifecycleDir) {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        $resolvedSettingsLifecycleDir = [System.IO.Path]::GetFullPath($settingsLifecycleDir)
        if ($resolvedSettingsLifecycleDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $settingsLifecycleDir -Recurse -Force
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
