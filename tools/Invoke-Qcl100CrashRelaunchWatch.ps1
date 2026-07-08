param(
    [string]$OwnerSerial = "340YC10G7T0JBW",
    [string]$ClientSerial = "3487C10H3M017Q",
    [ValidateSet("owner", "client")]
    [string]$SubjectRole = "owner",
    [string]$RunId = "",
    [string]$OutDir = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$OwnerLeaseId = "",
    [string]$ClientLeaseId = "",
    [string]$BaselineSummaryPath = "",
    [int]$MaxLogcatLines = 1200,
    [switch]$SelfTest
)

$ErrorActionPreference = "Stop"

$helperRoot = Join-Path $PSScriptRoot "qcl100_native_projection"
. (Join-Path $helperRoot "Common.ps1")
. (Join-Path $helperRoot "CrashRelaunchWatch.ps1")

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl100-crash-relaunch-watch-" + (Get-Date -Format "yyyyMMddTHHmmssZ")
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path "S:\Work\repos\active\rusty-quest\target" $RunId
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

if ($SelfTest) {
    $selfTestSummary = Invoke-Qcl100CrashWatchSelfTest -OutDir $OutDir
    Get-Content -Raw -LiteralPath (Join-Path $OutDir "qcl100-crash-relaunch-watch-self-test.json")
    if ([string]$selfTestSummary.status -ne "pass") {
        throw "QCL100 crash/relaunch watch self-test failed."
    }
    return
}

if (-not (Test-Path -LiteralPath $Adb)) {
    throw "ADB not found: $Adb"
}
if ($MaxLogcatLines -lt 100 -or $MaxLogcatLines -gt 5000) {
    throw "MaxLogcatLines must be between 100 and 5000 so log evidence stays bounded."
}

function Get-Qcl100CrashWatchDeviceSnapshot {
    param(
        [string]$Serial,
        [string]$Role
    )

    $deviceDir = Join-Path $OutDir $Role
    New-Item -ItemType Directory -Force -Path $deviceDir | Out-Null

    $state = Invoke-Qcl100CrashWatchAdbText -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("get-state") -Path (Join-Path $deviceDir "adb-state.txt")
    $model = Invoke-Qcl100CrashWatchAdbText -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", "getprop", "ro.product.model") -Path (Join-Path $deviceDir "ro-product-model.txt")
    $build = Invoke-Qcl100CrashWatchAdbText -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", "getprop", "ro.build.version.incremental") -Path (Join-Path $deviceDir "ro-build-version-incremental.txt")
    $sysBootCompleted = Invoke-Qcl100CrashWatchAdbText -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", "getprop", "sys.boot_completed") -Path (Join-Path $deviceDir "sys-boot-completed.txt")
    $roBootReason = Invoke-Qcl100CrashWatchAdbText -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", "getprop", "ro.boot.bootreason") -Path (Join-Path $deviceDir "ro-boot-bootreason.txt")
    $sysBootReason = Invoke-Qcl100CrashWatchAdbText -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", "getprop", "sys.boot.reason") -Path (Join-Path $deviceDir "sys-boot-reason.txt")
    $bootCount = Invoke-Qcl100CrashWatchAdbText -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", "settings", "get", "global", "boot_count") -Path (Join-Path $deviceDir "global-boot-count.txt")
    $uptime = Invoke-Qcl100CrashWatchAdbText -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", "cat", "/proc/uptime") -Path (Join-Path $deviceDir "proc-uptime.txt")
    $date = Invoke-Qcl100CrashWatchAdbText -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", "date") -Path (Join-Path $deviceDir "date.txt")
    $wifi = Invoke-Qcl100CrashWatchAdbText -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", "cmd", "wifi", "status") -Path (Join-Path $deviceDir "wifi-status.txt")
    $p2p0 = Invoke-Qcl100CrashWatchAdbText -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", "ip", "-4", "addr", "show", "p2p0") -Path (Join-Path $deviceDir "p2p0-ipv4.txt")
    $logcatPath = Join-Path $deviceDir "bounded-logcat-tail.txt"
    $logcat = Invoke-Qcl100CrashWatchAdbText -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("logcat", "-d", "-t", "$MaxLogcatLines", "-v", "threadtime") -Path $logcatPath
    $focusedLines = Get-Qcl100CrashWatchRelevantLogLines -LogcatText ([string]$logcat.output) -MaxLines 160
    $focusedLogcatPath = Join-Path $deviceDir "focused-crash-relaunch-logcat.txt"
    ($focusedLines -join "`n") | Set-Content -Encoding UTF8 -Path $focusedLogcatPath
    $tokenCounts = Get-Qcl100CrashWatchLogTokenCounts -LogcatText ([string]$logcat.output)

    [ordered]@{
        role = $Role
        serial = $Serial
        adb_state = ([string]$state.output).Trim()
        adb_state_exit_code = $state.exit_code
        model = ([string]$model.output).Trim()
        build = ([string]$build.output).Trim()
        sys_boot_completed = ([string]$sysBootCompleted.output).Trim()
        ro_boot_bootreason = ([string]$roBootReason.output).Trim()
        sys_boot_reason = ([string]$sysBootReason.output).Trim()
        boot_count = ([string]$bootCount.output).Trim()
        uptime_raw = ([string]$uptime.output).Trim()
        uptime_seconds = Convert-Qcl100CrashWatchUptimeSeconds -UptimeText ([string]$uptime.output)
        device_date = ([string]$date.output).Trim()
        wifi_status_path = $wifi.path
        p2p0_status_path = $p2p0.path
        raw_logcat_path = $logcatPath
        focused_logcat_path = $focusedLogcatPath
        max_logcat_lines = $MaxLogcatLines
        logcat_exit_code = $logcat.exit_code
        focused_logcat_line_count = $focusedLines.Count
        log_token_counts = $tokenCounts
        no_logcat_clear = $true
        no_package_launch = $true
        no_force_stop = $true
        no_wifi_mutation = $true
        no_media_command = $true
    }
}

function Get-Qcl100CrashWatchBaselineSubject {
    param(
        [string]$Path,
        [string]$Role
    )
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    $baseline = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    if ($null -ne $baseline.subject_device_snapshot) {
        return $baseline.subject_device_snapshot
    }
    if ($Role -eq "owner" -and $null -ne $baseline.devices.owner) {
        return $baseline.devices.owner
    }
    if ($Role -eq "client" -and $null -ne $baseline.devices.client) {
        return $baseline.devices.client
    }
    if ($Role -eq "owner" -and $null -ne $baseline.evidence) {
        $evidence = $baseline.evidence
        $uptimeSeconds = $evidence.owner_uptime_seconds_after_second_report
        if ($null -eq $uptimeSeconds) {
            $uptimeSeconds = $evidence.owner_uptime_seconds_after_report
        }
        if ($null -ne $evidence.owner_boot_count -or $null -ne $uptimeSeconds) {
            return [pscustomobject]@{
                serial = [string]$baseline.subject_serial
                sys_boot_completed = [string]$evidence.owner_sys_boot_completed
                uptime_seconds = $uptimeSeconds
                boot_count = $evidence.owner_boot_count
                ro_boot_bootreason = [string]$evidence.owner_boot_reason
                sys_boot_reason = [string]$evidence.owner_boot_reason
                device_date = [string]$baseline.updated_at
                baseline_source = "planning_watch_artifact"
            }
        }
    }
    return $null
}

$startedAt = Get-Date
$owner = Get-Qcl100CrashWatchDeviceSnapshot -Serial $OwnerSerial -Role "owner"
$client = Get-Qcl100CrashWatchDeviceSnapshot -Serial $ClientSerial -Role "client"
$subject = if ($SubjectRole -eq "owner") { $owner } else { $client }
$baselineSubject = Get-Qcl100CrashWatchBaselineSubject -Path $BaselineSummaryPath -Role $SubjectRole
$subjectLogcat = ""
if (Test-Path -LiteralPath $subject.raw_logcat_path) {
    $subjectLogcat = Get-Content -Raw -LiteralPath $subject.raw_logcat_path
}
$classification = New-Qcl100CrashWatchClassification `
    -SubjectDevice ([pscustomobject]$subject) `
    -BaselineSubjectDevice $baselineSubject `
    -LogcatText $subjectLogcat
$endedAt = Get-Date

$summary = [ordered]@{
    schema = "rusty.quest.qcl100_crash_relaunch_watch_run.v1"
    run_id = $RunId
    status = "passive_diagnostic_collected"
    started_at = $startedAt.ToString("o")
    ended_at = $endedAt.ToString("o")
    elapsed_seconds = [int][Math]::Ceiling(($endedAt - $startedAt).TotalSeconds)
    out_dir = $OutDir
    adb_path = $Adb
    adb_server_port = $AdbServerPort
    owner_serial = $OwnerSerial
    client_serial = $ClientSerial
    subject_role = $SubjectRole
    subject_serial = [string]$subject.serial
    owner_lease_id = $OwnerLeaseId
    client_lease_id = $ClientLeaseId
    lease_ids_supplied = [bool](-not [string]::IsNullOrWhiteSpace($OwnerLeaseId) -and -not [string]::IsNullOrWhiteSpace($ClientLeaseId))
    baseline_summary_path = $BaselineSummaryPath
    passive_only = $true
    no_logcat_clear = $true
    no_package_launch = $true
    no_force_stop = $true
    no_wifi_mutation = $true
    no_media_command = $true
    max_logcat_lines = $MaxLogcatLines
    devices = [ordered]@{
        owner = $owner
        client = $client
    }
    subject_device_snapshot = $subject
    classification = $classification
    next_policy = [ordered]@{
        live_qcl100_qcl099_media_paused = $true
        promotion_allowed = $false
        allowed_next_slice = "diagnosis_only"
        non_media_broker_hello_allowed = $false
        requires_human_review_before_media = $true
        watch_clearance_status = "not_cleared_by_wrapper"
    }
}

$summaryPath = Join-Path $OutDir "qcl100-crash-relaunch-watch-summary.json"
Write-JsonFile -Value $summary -Path $summaryPath
Get-Content -Raw -LiteralPath $summaryPath
