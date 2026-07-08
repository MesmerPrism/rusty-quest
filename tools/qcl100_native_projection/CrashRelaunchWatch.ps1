# Dot-sourced helper functions for the QCL100 crash/relaunch watch.
# Keep this passive: no log clearing, launches, force-stops, Wi-Fi mutation, or media commands.

function New-Qcl100CrashWatchAdbBaseArgs {
    param(
        [string]$Serial,
        [string]$AdbServerPort = ""
    )
    $args = @()
    if (-not [string]::IsNullOrWhiteSpace($AdbServerPort)) {
        $args += @("-P", $AdbServerPort)
    }
    $args += @("-s", $Serial)
    return $args
}

function Invoke-Qcl100CrashWatchAdbText {
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string]$Serial,
        [string]$AdbServerPort = "",
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [string]$Path = ""
    )

    $adbArgs = New-Qcl100CrashWatchAdbBaseArgs -Serial $Serial -AdbServerPort $AdbServerPort
    $output = & $Adb @adbArgs @Arguments 2>&1 | Out-String
    $exitCode = $LASTEXITCODE
    if (-not [string]::IsNullOrWhiteSpace($Path)) {
        $output | Set-Content -Encoding UTF8 -Path $Path
    }
    return [ordered]@{
        exit_code = $exitCode
        output = $output
        path = $Path
    }
}

function Convert-Qcl100CrashWatchUptimeSeconds {
    param([string]$UptimeText)

    $trimmed = ([string]$UptimeText).Trim()
    if ($trimmed -match '^([0-9]+(?:\.[0-9]+)?)\s+') {
        return [double]$Matches[1]
    }
    return $null
}

function Get-Qcl100CrashWatchLogTokenCounts {
    param([string]$LogcatText)

    $text = [string]$LogcatText
    $tokens = [ordered]@{
        SurfaceUtils = 0
        onShutdown = 0
        "crash-uploader" = 0
        "Crash uploaded" = 0
        AndroidRuntime = 0
        FATAL_EXCEPTION = 0
        system_server = 0
        Watchdog = 0
        tombstone = 0
        reboot = 0
        shutdown = 0
        rustymanifold = 0
        rustyquest = 0
    }
    foreach ($key in @($tokens.Keys)) {
        $tokens[$key] = ([regex]::Matches($text, [regex]::Escape($key), "IgnoreCase")).Count
    }
    return $tokens
}

function Get-Qcl100CrashWatchRelevantLogLines {
    param(
        [string]$LogcatText,
        [int]$MaxLines = 80
    )

    $pattern = "SurfaceUtils|onShutdown|crash-uploader|Crash uploaded|AndroidRuntime|FATAL EXCEPTION|system_server|Watchdog|tombstone|reboot|shutdown|rustymanifold|rustyquest"
    $lines = @(([string]$LogcatText -split "`r?`n") | Where-Object { $_ -match $pattern })
    if ($lines.Count -gt $MaxLines) {
        $lines = @($lines | Select-Object -Last $MaxLines)
    }
    return $lines
}

function Get-Qcl100CrashWatchDeviceSnapshotFromObject {
    param($Device)

    if ($null -eq $Device) {
        return $null
    }
    [ordered]@{
        serial = [string]$Device.serial
        sys_boot_completed = [string]$Device.sys_boot_completed
        uptime_seconds = if ($null -ne $Device.uptime_seconds) { [double]$Device.uptime_seconds } else { $null }
        boot_count = if ($null -ne $Device.boot_count -and [string]$Device.boot_count -match '^[0-9]+$') { [int]$Device.boot_count } else { $null }
        ro_boot_bootreason = [string]$Device.ro_boot_bootreason
        sys_boot_reason = [string]$Device.sys_boot_reason
        device_date = [string]$Device.device_date
    }
}

function Compare-Qcl100CrashWatchDeviceSnapshot {
    param(
        $Before,
        $After
    )

    $beforeSnapshot = Get-Qcl100CrashWatchDeviceSnapshotFromObject -Device $Before
    $afterSnapshot = Get-Qcl100CrashWatchDeviceSnapshotFromObject -Device $After
    $bootCountChanged = $false
    if ($null -ne $beforeSnapshot -and $null -ne $afterSnapshot -and
        $null -ne $beforeSnapshot.boot_count -and $null -ne $afterSnapshot.boot_count) {
        $bootCountChanged = [int]$afterSnapshot.boot_count -ne [int]$beforeSnapshot.boot_count
    }
    $uptimeDecreased = $false
    if ($null -ne $beforeSnapshot -and $null -ne $afterSnapshot -and
        $null -ne $beforeSnapshot.uptime_seconds -and $null -ne $afterSnapshot.uptime_seconds) {
        $uptimeDecreased = [double]$afterSnapshot.uptime_seconds -lt ([double]$beforeSnapshot.uptime_seconds - 30.0)
    }
    [ordered]@{
        before = $beforeSnapshot
        after = $afterSnapshot
        boot_count_changed = $bootCountChanged
        uptime_decreased = $uptimeDecreased
        os_reboot_proven = [bool]($bootCountChanged -or $uptimeDecreased)
    }
}

function New-Qcl100CrashWatchClassification {
    param(
        $SubjectDevice,
        $BaselineSubjectDevice = $null,
        [string]$LogcatText = ""
    )

    $comparison = Compare-Qcl100CrashWatchDeviceSnapshot -Before $BaselineSubjectDevice -After $SubjectDevice
    $tokenCounts = Get-Qcl100CrashWatchLogTokenCounts -LogcatText $LogcatText
    $surfaceShutdownSeen = [bool]($tokenCounts.SurfaceUtils -gt 0 -and $tokenCounts.onShutdown -gt 0)
    $crashUploaderSeen = [bool]($tokenCounts["crash-uploader"] -gt 0 -or $tokenCounts["Crash uploaded"] -gt 0)
    $fatalSeen = [bool]($tokenCounts.AndroidRuntime -gt 0 -or $tokenCounts.FATAL_EXCEPTION -gt 0 -or $tokenCounts.tombstone -gt 0)
    $classification = "unknown"
    if ([bool]$comparison.os_reboot_proven) {
        $classification = "os_reboot_proven"
    } elseif ($surfaceShutdownSeen -and $crashUploaderSeen) {
        $classification = "crash_relaunch_or_surface_shutdown_suspected"
    } elseif ($fatalSeen) {
        $classification = "app_or_system_crash_suspected"
    } elseif ($surfaceShutdownSeen) {
        $classification = "surface_shutdown_seen_without_reboot_proof"
    }

    [ordered]@{
        classification = $classification
        os_reboot_proven = [bool]$comparison.os_reboot_proven
        boot_count_changed = [bool]$comparison.boot_count_changed
        uptime_decreased = [bool]$comparison.uptime_decreased
        surface_shutdown_seen = $surfaceShutdownSeen
        crash_uploader_seen = $crashUploaderSeen
        android_fatal_or_tombstone_seen = $fatalSeen
        token_counts = $tokenCounts
        comparison = $comparison
        relevant_log_lines = Get-Qcl100CrashWatchRelevantLogLines -LogcatText $LogcatText
    }
}

function Invoke-Qcl100CrashWatchSelfTest {
    param([string]$OutDir)

    if ([string]::IsNullOrWhiteSpace($OutDir)) {
        $OutDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-crash-watch-selftest-" + [guid]::NewGuid().ToString("N"))
    }
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $before = [pscustomobject]@{
        serial = "340YC10G7T0JBW"
        sys_boot_completed = "1"
        uptime_seconds = 40000.0
        boot_count = 73
        ro_boot_bootreason = "shutdown,userrequested"
        sys_boot_reason = "shutdown,userrequested"
        device_date = "Tue Jul 7 22:48:49 EEST 2026"
    }
    $sameBoot = [pscustomobject]@{
        serial = "340YC10G7T0JBW"
        sys_boot_completed = "1"
        uptime_seconds = 40222.0
        boot_count = 73
        ro_boot_bootreason = "shutdown,userrequested"
        sys_boot_reason = "shutdown,userrequested"
        device_date = "Tue Jul 7 22:48:49 EEST 2026"
    }
    $afterReboot = [pscustomobject]@{
        serial = "340YC10G7T0JBW"
        sys_boot_completed = "1"
        uptime_seconds = 42.0
        boot_count = 74
        ro_boot_bootreason = "reboot"
        sys_boot_reason = "reboot"
        device_date = "Tue Jul 7 22:51:01 EEST 2026"
    }
    $crashLog = @"
07-07 22:47:57.995  1000  2000 I SurfaceUtils: connecting to surface, reason onShutdown
07-07 22:48:17.587  1000  2001 I crash-uploader: Crash uploaded from /data/misc/crashes/example
"@
    $cleanLog = "07-07 22:48:17.000  1000  2001 I ActivityTaskManager: normal line"
    $cases = @(
        [ordered]@{
            name = "same-boot-surface-shutdown-crash-uploader"
            expected_classification = "crash_relaunch_or_surface_shutdown_suspected"
            actual = New-Qcl100CrashWatchClassification -SubjectDevice $sameBoot -BaselineSubjectDevice $before -LogcatText $crashLog
        },
        [ordered]@{
            name = "boot-count-and-uptime-reset"
            expected_classification = "os_reboot_proven"
            actual = New-Qcl100CrashWatchClassification -SubjectDevice $afterReboot -BaselineSubjectDevice $before -LogcatText $cleanLog
        },
        [ordered]@{
            name = "same-boot-clean-log"
            expected_classification = "unknown"
            actual = New-Qcl100CrashWatchClassification -SubjectDevice $sameBoot -BaselineSubjectDevice $before -LogcatText $cleanLog
        }
    )
    foreach ($case in $cases) {
        $case["ok"] = [bool]([string]$case.actual.classification -eq [string]$case.expected_classification)
    }
    $summary = [ordered]@{
        schema = "rusty.quest.qcl100_crash_relaunch_watch_self_test.v1"
        status = if (@($cases | Where-Object { -not [bool]$_.ok }).Count -eq 0) { "pass" } else { "fail" }
        case_count = $cases.Count
        failure_count = @($cases | Where-Object { -not [bool]$_.ok }).Count
        cases = $cases
    }
    $path = Join-Path $OutDir "qcl100-crash-relaunch-watch-self-test.json"
    Write-JsonFile -Value $summary -Path $path
    return $summary
}
