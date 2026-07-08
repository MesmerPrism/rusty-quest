param(
    [string]$OwnerSerial = "340YC10G7T0JBW",
    [string]$ClientSerial = "3487C10H3M017Q",
    [string]$OwnerLeaseId = "",
    [string]$ClientLeaseId = "",
    [string]$RunId = "",
    [string]$OutDir = "",
    [string]$MonitorPath = "",
    [string]$RunnerPath = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$Qcl041Apk = "S:\Work\repos\active\rusty-quest\target\qcl041-wifi-direct-harness-android\rusty-quest-qcl041-wifi-direct-harness.apk",
    [int]$PollSeconds = 15,
    [int]$OverallTimeoutSeconds = 240,
    [int]$PhaseStallTimeoutSeconds = 90,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl100-route-clear-recovery-" + (Get-Date -Format "yyyyMMddTHHmmssZ").ToString()
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path "S:\Work\repos\active\rusty-quest\target" $RunId
}
if ([string]::IsNullOrWhiteSpace($MonitorPath)) {
    $MonitorPath = Join-Path $PSScriptRoot "Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirectMonitored.ps1"
}
if ([string]::IsNullOrWhiteSpace($RunnerPath)) {
    $RunnerPath = Join-Path $PSScriptRoot "Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1"
}

$MonitorPath = (Resolve-Path -LiteralPath $MonitorPath).Path
$RunnerPath = (Resolve-Path -LiteralPath $RunnerPath).Path
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Write-JsonFile {
    param([object]$Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 16) + "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function Read-Qcl100RouteClearRecoveryAdbText {
    param(
        [string]$Serial,
        [string[]]$Arguments,
        [string]$Path = ""
    )
    $output = & $Adb -s $Serial @Arguments 2>&1 | Out-String
    if (-not [string]::IsNullOrWhiteSpace($Path)) {
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($Path, $output, $utf8NoBom)
    }
    return $output
}

function Get-Qcl100RouteClearRecoverySensorLockState {
    param([string]$Serial, [string]$Label)

    $windowPath = Join-Path $OutDir "$Label-sensorlock-window.txt"
    $powerPath = Join-Path $OutDir "$Label-sensorlock-power.txt"
    $window = Read-Qcl100RouteClearRecoveryAdbText -Serial $Serial -Arguments @("shell", "dumpsys", "window") -Path $windowPath
    $power = Read-Qcl100RouteClearRecoveryAdbText -Serial $Serial -Arguments @("shell", "dumpsys", "power") -Path $powerPath
    $mounted = (Read-Qcl100RouteClearRecoveryAdbText -Serial $Serial -Arguments @("shell", "getprop", "sys.hmt.mounted")).Trim()

    $currentFocus = ""
    $focusMatch = [regex]::Match($window, "mCurrentFocus=([^\r\n]+)")
    if ($focusMatch.Success) {
        $currentFocus = $focusMatch.Groups[1].Value.Trim()
    }

    $sensorLockActive = [bool]($window -match "com\.oculus\.os\.vrlockscreen/.SensorLockActivity")
    return [ordered]@{
        schema = "rusty.quest.qcl100_route_clear_sensorlock_preflight_device.v1"
        serial = $Serial
        label = $Label
        sensor_lock_active = $sensorLockActive
        current_focus = $currentFocus
        sys_hmt_mounted = $mounted
        wakefulness_awake = [bool]($power -match "mWakefulness=Awake")
        window_artifact = $windowPath
        power_artifact = $powerPath
    }
}

$startedAt = Get-Date
$policyPath = Join-Path $OutDir "qcl100-route-clear-recovery-wrapper.json"
$monitorSummaryPath = Join-Path $OutDir "qcl100-monitor-summary.json"
$finalSummaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"

$policy = [ordered]@{
    schema = "rusty.quest.qcl100_route_clear_recovery.v1"
    run_id = $RunId
    status = if ($DryRun) { "dry_run_planned" } else { "running" }
    started_at = $startedAt.ToString("o")
    out_dir = $OutDir
    owner_serial = $OwnerSerial
    client_serial = $ClientSerial
    owner_lease_id = $OwnerLeaseId
    client_lease_id = $ClientLeaseId
    lease_ids_supplied = [bool](-not [string]::IsNullOrWhiteSpace($OwnerLeaseId) -and -not [string]::IsNullOrWhiteSpace($ClientLeaseId))
    purpose = "route_cleanup_preflight_only_after_qcl100_crash_relaunch_watch"
    crash_watch_policy = [ordered]@{
        live_qcl100_qcl099_media_paused = $true
        non_media_broker_hello_allowed = $false
        promotion_allowed = $false
        allowed_next_slice = "route_cleanup_preflight_only"
        requires_human_review_before_media = $true
    }
    requested_runner_flags = [ordered]@{
        PreflightOnly = $true
        RunQcl041PreclearBeforeAirgapPreflight = $true
        RequireInfrastructureWifiDisconnected = $true
        RequireP2p0Ipv4Cleared = $true
        RequireCandidateWifiDirectRoutesClear = $true
        SkipWakePrep = $true
    }
    forbidden_live_actions = @(
        "command.remote_camera.start_receiver",
        "command.remote_camera.start_sender",
        "non_media_broker_hello",
        "native_renderer_launch",
        "media_projection_run",
        "qcl099_makepad_projection_run"
    )
    monitor_path = $MonitorPath
    runner_path = $RunnerPath
    monitor_summary_path = $monitorSummaryPath
    final_summary_path = $finalSummaryPath
    sensorlock_preflight_required = $true
    sensorlock_preflight_artifact = Join-Path $OutDir "qcl100-route-clear-sensorlock-preflight.json"
    dry_run = [bool]$DryRun
}
Write-JsonFile -Value $policy -Path $policyPath

if (-not $DryRun -and -not [bool]$policy.lease_ids_supplied) {
    $endedAt = Get-Date
    $policy["status"] = "blocked_missing_lease_ids"
    $policy["blocked_reason"] = "route_clear_recovery_requires_owner_and_client_lease_ids"
    $policy["ended_at"] = $endedAt.ToString("o")
    $policy["elapsed_seconds"] = [int][Math]::Ceiling(($endedAt - $startedAt).TotalSeconds)
    $policy["monitor_summary_present"] = $false
    $policy["final_summary_present"] = $false
    Write-JsonFile -Value $policy -Path $policyPath
    throw "QCL100 route-clear recovery requires OwnerLeaseId and ClientLeaseId for non-dry-run use."
}

if (-not $DryRun) {
    $sensorLockPreflightPath = [string]$policy.sensorlock_preflight_artifact
    $sensorLockPreflight = [ordered]@{
        schema = "rusty.quest.qcl100_route_clear_sensorlock_preflight.v1"
        run_id = $RunId
        owner = Get-Qcl100RouteClearRecoverySensorLockState -Serial $OwnerSerial -Label "owner"
        client = Get-Qcl100RouteClearRecoverySensorLockState -Serial $ClientSerial -Label "client"
    }
    $sensorLockPreflight["sensor_lock_active"] = [bool](
        [bool]$sensorLockPreflight.owner.sensor_lock_active -or
        [bool]$sensorLockPreflight.client.sensor_lock_active
    )
    $sensorLockPreflight["passed"] = [bool](-not [bool]$sensorLockPreflight.sensor_lock_active)
    Write-JsonFile -Value $sensorLockPreflight -Path $sensorLockPreflightPath
    $policy["sensorlock_preflight"] = $sensorLockPreflight
    if ([bool]$sensorLockPreflight.sensor_lock_active) {
        $endedAt = Get-Date
        $policy["status"] = "blocked_sensorlock"
        $policy["blocked_reason"] = "sensorlock_active_before_route_clear_recovery"
        $policy["ended_at"] = $endedAt.ToString("o")
        $policy["elapsed_seconds"] = [int][Math]::Ceiling(($endedAt - $startedAt).TotalSeconds)
        $policy["monitor_summary_present"] = $false
        $policy["final_summary_present"] = $false
        Write-JsonFile -Value $policy -Path $policyPath
        throw "QCL100 route-clear recovery blocked by SensorLock; clear protected headset UI before route-cleanup/preflight-only work."
    }
    Write-JsonFile -Value $policy -Path $policyPath
}

$monitorParams = @{
    OwnerSerial = $OwnerSerial
    ClientSerial = $ClientSerial
    OwnerLeaseId = $OwnerLeaseId
    ClientLeaseId = $ClientLeaseId
    RunId = $RunId
    OutDir = $OutDir
    RunnerPath = $RunnerPath
    Adb = $Adb
    Qcl041Apk = $Qcl041Apk
    PollSeconds = $PollSeconds
    OverallTimeoutSeconds = $OverallTimeoutSeconds
    PhaseStallTimeoutSeconds = $PhaseStallTimeoutSeconds
    PreflightOnly = $true
    RunQcl041PreclearBeforeAirgapPreflight = $true
    RequireInfrastructureWifiDisconnected = $true
    RequireP2p0Ipv4Cleared = $true
    RequireCandidateWifiDirectRoutesClear = $true
    SkipWakePrep = $true
}
if ($DryRun) {
    $monitorParams.DryRun = $true
}

try {
    & $MonitorPath @monitorParams
    $endedAt = Get-Date
    $policy["status"] = if ($DryRun) { "dry_run_planned" } else { "monitor_completed" }
    $policy["ended_at"] = $endedAt.ToString("o")
    $policy["elapsed_seconds"] = [int][Math]::Ceiling(($endedAt - $startedAt).TotalSeconds)
    $policy["monitor_summary_present"] = Test-Path -LiteralPath $monitorSummaryPath
    $policy["final_summary_present"] = Test-Path -LiteralPath $finalSummaryPath
    Write-JsonFile -Value $policy -Path $policyPath
} catch {
    $endedAt = Get-Date
    $policy["status"] = "monitor_failed"
    $policy["ended_at"] = $endedAt.ToString("o")
    $policy["elapsed_seconds"] = [int][Math]::Ceiling(($endedAt - $startedAt).TotalSeconds)
    $policy["error"] = $_.Exception.Message
    $policy["monitor_summary_present"] = Test-Path -LiteralPath $monitorSummaryPath
    $policy["final_summary_present"] = Test-Path -LiteralPath $finalSummaryPath
    Write-JsonFile -Value $policy -Path $policyPath
    throw
}

Get-Content -Raw -LiteralPath $policyPath
