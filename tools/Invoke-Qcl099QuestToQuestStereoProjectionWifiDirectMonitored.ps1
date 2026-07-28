param(
    [string]$OwnerSerial = "340YC10G7T0JBW",
    [string]$ClientSerial = "3487C10H3M017Q",
    [string]$OwnerLeaseId = "",
    [string]$ClientLeaseId = "",
    [string]$RunId = "",
    [string]$OutDir = "",
    [string]$RunnerPath = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$Python = "python",
    [string]$HostessCtl = "S:\Work\repos\active\rusty-hostess\tools\hostessctl\hostessctl.py",
    [string]$Qcl041Apk = "S:\Work\repos\active\rusty-quest\target\qcl041-wifi-direct-harness-android\rusty-quest-qcl041-wifi-direct-harness.apk",
    [string]$BrokerApk = "S:\Work\repos\active\rusty-quest\target\manifold-broker-android\rusty-manifold-broker.apk",
    [string]$MakepadApk = "S:\Work\repos\active\rusty-quest-makepad\local-artifacts\quest-makepad-camera-apk\rustyquestmakepadcamera.apk",
    [int]$ProjectionSeconds = 30,
    [int]$OwnerBrokerLocalPort = 18765,
    [int]$ClientBrokerLocalPort = 18766,
    [string]$OwnerWifiDirectAddress = "192.168.49.1",
    [string]$ClientWifiDirectAddress = "192.168.49.46",
    [string]$Qcl041Q2qNetworkName = "DIRECT-rq-QCL100",
    [string]$Qcl041Q2qPassphrase = "RustyQcl100Pass",
    [int]$LeftReceiverPort = 8979,
    [int]$RightReceiverPort = 8980,
    [int]$LeftTransportPort = 9079,
    [int]$RightTransportPort = 9080,
    [int]$LeftSourcePort = 8879,
    [int]$RightSourcePort = 8880,
    [string]$CameraIds = "left:50,right:51",
    [string]$MediaProfiles = "left:320x240@15:500000;right:320x240@15:500000",
    [ValidateSet("qcl041", "broker")]
    [string]$TransportOwner = "qcl041",
    [ValidateSet("android_connectivitymanager_network", "rusty_direct_p2p_socket_authority")]
    [string]$Qcl100LowerGateAuthority = "rusty_direct_p2p_socket_authority",
    [int]$MakepadPreferredWidth = 320,
    [int]$MakepadPreferredHeight = 240,
    [int]$MakepadFrameRateHz = 15,
    [ValidateSet("cpu-yuv", "hardware-buffer", "surface-texture")]
    [string]$MakepadDecodeOutputMode = "cpu-yuv",
    [int]$RelayTimeoutSeconds = 95,
    [int]$RelayMaxBytes = 128000000,
    [int]$HoldAfterSocketMs = 90000,
    [int]$XrFocusWaitSeconds = 24,
    [int]$XrFocusLaunchAttempts = 3,
    [int]$PollSeconds = 30,
    [int]$OverallTimeoutSeconds = 900,
    [int]$PhaseStallTimeoutSeconds = 300,
    [switch]$SkipInstall,
    [switch]$SkipWakePrep,
    [switch]$SkipRunnerCleanup,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

if ($PollSeconds -lt 5) {
    throw "PollSeconds must be at least 5 so the monitor has a meaningful status cadence."
}
if ($OverallTimeoutSeconds -lt 60) {
    throw "OverallTimeoutSeconds must be at least 60."
}
if ($PhaseStallTimeoutSeconds -lt 60) {
    throw "PhaseStallTimeoutSeconds must be at least 60."
}
if ($Qcl041Q2qNetworkName -notmatch '^DIRECT-[A-Za-z0-9]{2}.*$' -or $Qcl041Q2qNetworkName.Length -gt 32) {
    throw "Qcl041Q2qNetworkName must follow the Wi-Fi Direct DIRECT-xy naming rule and fit in 32 characters."
}
if ($Qcl041Q2qPassphrase -notmatch '^[\x20-\x7e]{8,63}$') {
    throw "Qcl041Q2qPassphrase must be 8-63 printable ASCII characters."
}

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl099-q2q-stereo-projection-" + (Get-Date -Format "yyyyMMddTHHmmssZ").ToString()
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path "S:\Work\repos\active\rusty-quest\target" $RunId
}
if ([string]::IsNullOrWhiteSpace($RunnerPath)) {
    $RunnerPath = Join-Path $PSScriptRoot "Invoke-Qcl099QuestToQuestStereoProjectionWifiDirect.ps1"
}
$RunnerPath = (Resolve-Path -LiteralPath $RunnerPath).Path
$runnerWorkingDirectory = Split-Path -Parent (Split-Path -Parent $RunnerPath)

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$mediaDir = Join-Path $OutDir "media"
$stdoutPath = Join-Path $OutDir "qcl099-runner.stdout.txt"
$stderrPath = Join-Path $OutDir "qcl099-runner.stderr.txt"
$progressPath = Join-Path $OutDir "qcl099-monitor-progress.jsonl"
$monitorSummaryPath = Join-Path $OutDir "qcl099-monitor-summary.json"
$runnerParamsPath = Join-Path $OutDir "qcl099-runner-params.json"
$runnerChildScriptPath = Join-Path $OutDir "qcl099-runner-child.ps1"
$postRunCleanupReadbackPath = Join-Path $OutDir "qcl099-post-run-cleanup-readback.json"

$Qcl041Package = "io.github.mesmerprism.rustyquest.qcl041"
$BrokerPackage = "io.github.mesmerprism.rustymanifold.broker"
$MakepadPackage = "io.github.mesmerprism.rustyquest.makepad.camera"

function Write-JsonFile {
    param([object]$Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 32) + "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function Add-JsonLine {
    param([object]$Value, [string]$Path)
    $json = $Value | ConvertTo-Json -Depth 32 -Compress
    Add-Content -Encoding UTF8 -Path $Path -Value $json
}

function ConvertTo-PowerShellSingleQuotedLiteral {
    param([string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function ConvertTo-EncodedCommand {
    param([string]$ScriptText)
    $bytes = [System.Text.Encoding]::Unicode.GetBytes($ScriptText)
    return [Convert]::ToBase64String($bytes)
}

function Read-JsonIfPresent {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    try {
        return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    } catch {
        return [ordered]@{
            parse_error = $_.Exception.Message
            path = $Path
        }
    }
}

function Get-NestedValue {
    param($Object, [string[]]$Path)
    $cursor = $Object
    foreach ($segment in $Path) {
        if ($null -eq $cursor) {
            return $null
        }
        $property = $cursor.PSObject.Properties[$segment]
        if ($null -eq $property) {
            return $null
        }
        $cursor = $property.Value
    }
    return $cursor
}

function Summarize-Qcl041Artifact {
    param([string]$Path)
    $json = Read-JsonIfPresent -Path $Path
    if ($null -eq $json) {
        return $null
    }
    return [ordered]@{
        path = $Path
        run_id = Get-NestedValue -Object $json -Path @("run_id")
        role = Get-NestedValue -Object $json -Path @("device", "wifi_direct_role")
        peer_count = Get-NestedValue -Object $json -Path @("diagnostics", "lifecycle", "peer_count")
        group_formed = Get-NestedValue -Object $json -Path @("diagnostics", "lifecycle", "connection_info_group_formed")
        local_interface = Get-NestedValue -Object $json -Path @("diagnostics", "lifecycle", "wifi_direct_local_interface")
        local_address = Get-NestedValue -Object $json -Path @("diagnostics", "lifecycle", "wifi_direct_local_address")
        socket_status = Get-NestedValue -Object $json -Path @("lifecycle", "socket_exchange", "status")
        relay_started_before_socket_probe = Get-NestedValue -Object $json -Path @("diagnostics", "qcl082_relay", "started_before_socket_probe")
        relay_status = Get-NestedValue -Object $json -Path @("diagnostics", "qcl082_relay", "status")
        relay_bytes_copied = Get-NestedValue -Object $json -Path @("diagnostics", "qcl082_relay", "bytes_copied")
        relay_left_status = Get-NestedValue -Object $json -Path @("diagnostics", "qcl082_relay_left", "status")
        relay_right_status = Get-NestedValue -Object $json -Path @("diagnostics", "qcl082_relay_right", "status")
        cleanup_status = Get-NestedValue -Object $json -Path @("lifecycle", "cleanup", "status")
    }
}

function Get-LatestFileSummary {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    $ignoredMonitorFiles = @(
        "qcl099-monitor-progress.jsonl",
        "qcl099-monitor-summary.json",
        "qcl099-wrapper.stdout.txt",
        "qcl099-wrapper.stderr.txt"
    )
    $latest = Get-ChildItem -LiteralPath $Path -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $ignoredMonitorFiles -notcontains $_.Name } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $latest) {
        return $null
    }
    return [ordered]@{
        path = $latest.FullName
        name = $latest.Name
        last_write_utc = $latest.LastWriteTimeUtc.ToString("o")
        bytes = $latest.Length
    }
}

function Get-Qcl099MonitorProgress {
    param(
        [string]$OutDir,
        [string]$MediaDir,
        [datetime]$StartedAt,
        [int]$TimeoutSeconds
    )
    $now = Get-Date
    $elapsedSeconds = [int][Math]::Floor(($now - $StartedAt).TotalSeconds)
    $remainingSeconds = [int][Math]::Ceiling([Math]::Max(0, $TimeoutSeconds - $elapsedSeconds))
    $summaryPath = Join-Path $OutDir "stereo-projection-summary.json"
    $ownerQcl041Path = Join-Path $OutDir "owner-qcl041.json"
    $clientQcl041Path = Join-Path $OutDir "client-qcl041.json"
    $ownerLogPath = Join-Path $OutDir "owner-makepad.logcat.txt"
    $clientLogPath = Join-Path $OutDir "client-makepad.logcat.txt"
    $runnerProgressPath = Join-Path $OutDir "qcl099-runner-progress.jsonl"

    $mediaFiles = @()
    if (Test-Path -LiteralPath $MediaDir) {
        $mediaFiles = @(Get-ChildItem -LiteralPath $MediaDir -File -ErrorAction SilentlyContinue)
    }
    $readinessLogs = @($mediaFiles | Where-Object { $_.Name -like "*makepad-readiness-*.logcat.txt" })
    $bridgeExecutions = @($mediaFiles | Where-Object { $_.Name -like "*-execution.json" })
    $runnerProgressLineCount = 0
    if (Test-Path -LiteralPath $runnerProgressPath) {
        $runnerProgressLineCount = @(Get-Content -LiteralPath $runnerProgressPath -ErrorAction SilentlyContinue).Count
    }

    $artifacts = [ordered]@{
        media_dir = Test-Path -LiteralPath $MediaDir
        runner_stdout = Test-Path -LiteralPath $stdoutPath
        runner_stderr = Test-Path -LiteralPath $stderrPath
        runner_progress = Test-Path -LiteralPath $runnerProgressPath
        runner_progress_line_count = $runnerProgressLineCount
        owner_qcl041_launch = Test-Path -LiteralPath (Join-Path $MediaDir "owner-qcl041-launch.txt")
        client_qcl041_launch = Test-Path -LiteralPath (Join-Path $MediaDir "client-qcl041-launch.txt")
        owner_qcl041_json = Test-Path -LiteralPath $ownerQcl041Path
        client_qcl041_json = Test-Path -LiteralPath $clientQcl041Path
        owner_makepad_log = Test-Path -LiteralPath $ownerLogPath
        client_makepad_log = Test-Path -LiteralPath $clientLogPath
        final_summary = Test-Path -LiteralPath $summaryPath
        media_file_count = $mediaFiles.Count
        bridge_execution_count = $bridgeExecutions.Count
        readiness_log_count = $readinessLogs.Count
    }

    $phase = "starting"
    if ($artifacts.final_summary) {
        $phase = "summary_emitted"
    } elseif ($artifacts.owner_makepad_log -or $artifacts.client_makepad_log) {
        $phase = "final_log_collection"
    } elseif ($artifacts.owner_qcl041_json -or $artifacts.client_qcl041_json) {
        $phase = "qcl041_artifact_collection"
    } elseif ($artifacts.readiness_log_count -gt 0) {
        $phase = "xr_readiness_poll"
    } elseif ($artifacts.owner_qcl041_launch -or $artifacts.client_qcl041_launch) {
        $phase = "qcl041_relay_running"
    } elseif ($artifacts.bridge_execution_count -gt 0) {
        $phase = "bridge_commands_running"
    } elseif ($artifacts.media_file_count -gt 0) {
        $phase = "setup_or_request_emission"
    }

    $summary = Read-JsonIfPresent -Path $summaryPath

    return [ordered]@{
        schema = "rusty.quest.qcl099_monitor_progress.v1"
        timestamp = $now.ToString("o")
        run_id = $RunId
        out_dir = $OutDir
        elapsed_seconds = $elapsedSeconds
        remaining_seconds = $remainingSeconds
        timeout_seconds = $TimeoutSeconds
        phase = $phase
        artifacts = $artifacts
        latest_file = Get-LatestFileSummary -Path $OutDir
        owner_qcl041 = Summarize-Qcl041Artifact -Path $ownerQcl041Path
        client_qcl041 = Summarize-Qcl041Artifact -Path $clientQcl041Path
        final_summary_present = [bool]($null -ne $summary)
        projection_ready_both_headsets = Get-NestedValue -Object $summary -Path @("projection_ready_both_headsets")
        owner_projection_ready = Get-NestedValue -Object $summary -Path @("owner_projection_ready")
        client_projection_ready = Get-NestedValue -Object $summary -Path @("client_projection_ready")
    }
}

function Get-Qcl099ProgressSignature {
    param($Progress)
    $latest = $Progress.latest_file
    $owner = $Progress.owner_qcl041
    $client = $Progress.client_qcl041
    $parts = @(
        $Progress.phase,
        $Progress.final_summary_present,
        $Progress.artifacts.media_file_count,
        $Progress.artifacts.bridge_execution_count,
        $Progress.artifacts.readiness_log_count,
        $Progress.artifacts.runner_progress_line_count,
        $Progress.artifacts.owner_qcl041_json,
        $Progress.artifacts.client_qcl041_json,
        $Progress.artifacts.owner_makepad_log,
        $Progress.artifacts.client_makepad_log,
        $(if ($null -eq $latest) { "" } else { $latest.path }),
        $(if ($null -eq $latest) { "" } else { $latest.last_write_utc }),
        $(if ($null -eq $latest) { "" } else { $latest.bytes }),
        $(if ($null -eq $owner) { "" } else { $owner.relay_status }),
        $(if ($null -eq $owner) { "" } else { $owner.relay_bytes_copied }),
        $(if ($null -eq $client) { "" } else { $client.relay_status }),
        $(if ($null -eq $client) { "" } else { $client.relay_bytes_copied })
    )
    return ($parts -join "|")
}

function Invoke-AdbBestEffort {
    param([string]$Serial, [string[]]$Arguments)
    try {
        & $Adb -s $Serial @Arguments 2>&1 | Out-Null
    } catch {
    }
}

function Test-P2p0HasIpv4 {
    param([string]$Readback)
    if ([string]::IsNullOrWhiteSpace($Readback)) {
        return $false
    }
    return [bool]($Readback -match "\binet\s")
}

function Test-CleanupRowsClean {
    param([object[]]$Rows)
    foreach ($row in @($Rows)) {
        if (Test-P2p0HasIpv4 -Readback ([string]$row.p2p0_after_cleanup)) {
            return $false
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$row.rusty_processes_after_cleanup)) {
            return $false
        }
    }
    return $Rows.Count -gt 0
}

function Invoke-Qcl041PostCleanupPreclear {
    param(
        [string]$Serial,
        [string]$LeaseId,
        [string]$Label
    )
    $effectiveLeaseId = if ([string]::IsNullOrWhiteSpace($LeaseId)) { "unleased" } else { $LeaseId }
    $leaseReserved = if ([string]::IsNullOrWhiteSpace($LeaseId)) { "false" } else { "true" }
    $preclearRunId = "$RunId-$Label-qcl041-post-cleanup-preclear"
    $launchLogPath = Join-Path $mediaDir "$Label-post-cleanup-preclear-launch.txt"
    $artifactPath = Join-Path $mediaDir "$Label-post-cleanup-preclear.json"
    $intentArgs = @(
        "shell", "am", "start-foreground-service",
        "-n", "$Qcl041Package/.Qcl041WifiDirectHarnessService",
        "--es", "qcl041.run_id", $preclearRunId,
        "--es", "qcl041.device_serial", $Serial,
        "--es", "qcl041.device_model", "Quest_3S",
        "--es", "qcl041.lease_id", $effectiveLeaseId,
        "--es", "qcl041.lease_resource", "quest:$Serial",
        "--ez", "qcl041.lease_reserved_before_live_steps", $leaseReserved,
        "--es", "qcl041.peer_class", "quest",
        "--es", "qcl041.q2q_role", "group_owner",
        "--ez", "qcl041.q2q_preclear_only", "true",
        "--ez", "qcl041.q2q_preclear_stale_group", "true",
        "--es", "qcl041.q2q_network_name", $Qcl041Q2qNetworkName,
        "--es", "qcl041.q2q_passphrase", $Qcl041Q2qPassphrase,
        "--es", "qcl041.peer_name_contains", "Quest",
        "--es", "qcl041.host_toolchain_profile", "qcl099_quest_to_quest_stereo_projection_wifi_direct_post_cleanup_preclear",
        "--ei", "qcl041.timeout_seconds", "12",
        "--ei", "qcl041.socket_timeout_seconds", "5",
        "--ei", "qcl041.hold_after_socket_ms", "0",
        "--ez", "qcl041.qcl082_relay_enabled", "false",
        "--ez", "qcl041.qcl082_receive_proxy_enabled", "false",
        "--ez", "qcl041.qcl082_ack_pacing_enabled", "false"
    )
    $launchOutput = (& $Adb -s $Serial @intentArgs 2>&1 | Out-String).Trim()
    $launchExitCode = $LASTEXITCODE
    $launchOutput | Set-Content -Encoding UTF8 -Path $launchLogPath
    Start-Sleep -Seconds 8
    $content = (& $Adb -s $Serial exec-out run-as $Qcl041Package cat "files/qcl041/$preclearRunId.json" 2>&1 | Out-String).Trim()
    $artifactReadExitCode = $LASTEXITCODE
    if ($artifactReadExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($content)) {
        $content | Set-Content -Encoding UTF8 -Path $artifactPath
    }
    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "am", "force-stop", $Qcl041Package)
    return [ordered]@{
        schema = "rusty.quest.qcl099_qcl041_post_cleanup_preclear_receipt.v1"
        serial = $Serial
        label = $Label
        run_id = $preclearRunId
        launch_exit_code = $launchExitCode
        artifact_read_exit_code = $artifactReadExitCode
        qcl041_preclear_only = $true
        qcl041_preclear_stale_group = $true
        launch_log_path = $launchLogPath
        artifact_path = $artifactPath
        artifact_present = [bool](Test-Path -LiteralPath $artifactPath)
        parsed_artifact = Read-JsonIfPresent -Path $artifactPath
        final_force_stop_attempted = $true
    }
}

function Invoke-Qcl099Cleanup {
    param([string[]]$Serials)
    $cleanup = @()
    foreach ($serial in $Serials) {
        $label = if ($serial -eq $OwnerSerial) { "owner" } elseif ($serial -eq $ClientSerial) { "client" } else { $serial }
        $leaseId = if ($serial -eq $OwnerSerial) { $OwnerLeaseId } elseif ($serial -eq $ClientSerial) { $ClientLeaseId } else { "" }
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", $Qcl041Package)
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", $BrokerPackage)
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", $MakepadPackage)
        Invoke-AdbBestEffort -Serial $serial -Arguments @("forward", "--remove-all")
        $p2p0BeforePreclear = ""
        $p2p0 = ""
        $rustyProcesses = ""
        try {
            $p2p0BeforePreclear = (& $Adb -s $serial shell ip -o addr show p2p0 2>&1 | Out-String).Trim()
        } catch {
            $p2p0BeforePreclear = $_.Exception.Message
        }
        $postCleanupPreclear = Invoke-Qcl041PostCleanupPreclear -Serial $serial -LeaseId $leaseId -Label $label
        try {
            $p2p0 = (& $Adb -s $serial shell ip -o addr show p2p0 2>&1 | Out-String).Trim()
        } catch {
            $p2p0 = $_.Exception.Message
        }
        try {
            $rustyProcesses = (& $Adb -s $serial shell ps -A 2>&1 | Select-String -Pattern "rustyquest|rustymanifold" | Out-String).Trim()
        } catch {
            $rustyProcesses = $_.Exception.Message
        }
        $cleanup += [ordered]@{
            serial = $serial
            force_stop_attempted = $true
            forward_remove_all_attempted = $true
            qcl041_post_cleanup_preclear = $postCleanupPreclear
            p2p0_before_preclear = $p2p0BeforePreclear
            p2p0_before_preclear_had_ipv4 = Test-P2p0HasIpv4 -Readback $p2p0BeforePreclear
            p2p0_after_cleanup = $p2p0
            p2p0_after_cleanup_has_ipv4 = Test-P2p0HasIpv4 -Readback $p2p0
            rusty_processes_after_cleanup = $rustyProcesses
        }
    }
    return $cleanup
}

function Stop-ProcessTree {
    param([int]$ProcessId)
    try {
        $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue)
        foreach ($child in $children) {
            Stop-ProcessTree -ProcessId ([int]$child.ProcessId)
        }
    } catch {
    }
    try {
        Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    } catch {
    }
}

$runnerParams = [ordered]@{
    OwnerSerial = $OwnerSerial
    ClientSerial = $ClientSerial
    OwnerLeaseId = $OwnerLeaseId
    ClientLeaseId = $ClientLeaseId
    RunId = $RunId
    OutDir = $OutDir
    Adb = $Adb
    Python = $Python
    HostessCtl = $HostessCtl
    Qcl041Apk = $Qcl041Apk
    BrokerApk = $BrokerApk
    MakepadApk = $MakepadApk
    ProjectionSeconds = $ProjectionSeconds
    OwnerBrokerLocalPort = $OwnerBrokerLocalPort
    ClientBrokerLocalPort = $ClientBrokerLocalPort
    OwnerWifiDirectAddress = $OwnerWifiDirectAddress
    ClientWifiDirectAddress = $ClientWifiDirectAddress
    LeftReceiverPort = $LeftReceiverPort
    RightReceiverPort = $RightReceiverPort
    LeftTransportPort = $LeftTransportPort
    RightTransportPort = $RightTransportPort
    LeftSourcePort = $LeftSourcePort
    RightSourcePort = $RightSourcePort
    CameraIds = $CameraIds
    MediaProfiles = $MediaProfiles
    TransportOwner = $TransportOwner
    Qcl100LowerGateAuthority = $Qcl100LowerGateAuthority
    MakepadPreferredWidth = $MakepadPreferredWidth
    MakepadPreferredHeight = $MakepadPreferredHeight
    MakepadFrameRateHz = $MakepadFrameRateHz
    MakepadDecodeOutputMode = $MakepadDecodeOutputMode
    RelayTimeoutSeconds = $RelayTimeoutSeconds
    RelayMaxBytes = $RelayMaxBytes
    HoldAfterSocketMs = $HoldAfterSocketMs
    XrFocusWaitSeconds = $XrFocusWaitSeconds
    XrFocusLaunchAttempts = $XrFocusLaunchAttempts
    SkipInstall = [bool]$SkipInstall
    SkipWakePrep = [bool]$SkipWakePrep
    SkipCleanup = [bool]$SkipRunnerCleanup
}
Write-JsonFile -Value $runnerParams -Path $runnerParamsPath

$paramsLiteral = ConvertTo-PowerShellSingleQuotedLiteral -Value $runnerParamsPath
$runnerLiteral = ConvertTo-PowerShellSingleQuotedLiteral -Value $RunnerPath
$childScript = @"
`$ErrorActionPreference = 'Stop'
`$params = Get-Content -Raw -LiteralPath $paramsLiteral | ConvertFrom-Json
`$splat = @{}
foreach (`$property in `$params.PSObject.Properties) {
    if (`$property.Value -is [bool]) {
        if (`$property.Value) {
            `$splat[`$property.Name] = `$true
        }
    } elseif (`$null -ne `$property.Value -and -not [string]::IsNullOrWhiteSpace([string]`$property.Value)) {
        `$splat[`$property.Name] = `$property.Value
    }
}
& $runnerLiteral @splat
if (`$LASTEXITCODE -is [int]) {
    exit `$LASTEXITCODE
}
exit 0
"@
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($runnerChildScriptPath, $childScript, $utf8NoBom)
$encodedCommand = ConvertTo-EncodedCommand -ScriptText $childScript

if ($DryRun) {
    $summary = [ordered]@{
        schema = "rusty.quest.qcl099_monitor_summary.v1"
        run_id = $RunId
        status = "dry_run"
        out_dir = $OutDir
        runner_path = $RunnerPath
        runner_params_path = $runnerParamsPath
        runner_child_script_path = $runnerChildScriptPath
        progress_path = $progressPath
        stdout_path = $stdoutPath
        stderr_path = $stderrPath
        poll_seconds = $PollSeconds
        overall_timeout_seconds = $OverallTimeoutSeconds
        phase_stall_timeout_seconds = $PhaseStallTimeoutSeconds
    }
    Write-JsonFile -Value $summary -Path $monitorSummaryPath
    Get-Content -Raw -LiteralPath $monitorSummaryPath
    return
}

$startedAt = Get-Date
$deadline = $startedAt.AddSeconds($OverallTimeoutSeconds)
$timedOut = $false
$phaseStalled = $false
$lastProgressAt = $startedAt
$lastProgressSignature = $null
$cleanup = @()
$exitCode = $null
$exitCodeInferred = $false
$process = $null

Write-Host "[qcl099-monitor] starting run_id=$RunId timeout=${OverallTimeoutSeconds}s phase_stall=${PhaseStallTimeoutSeconds}s poll=${PollSeconds}s out_dir=$OutDir"

try {
    $process = Start-Process `
        -FilePath "pwsh" `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", $encodedCommand) `
        -WorkingDirectory $runnerWorkingDirectory `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -WindowStyle Hidden `
        -PassThru

    while ($true) {
        $process.Refresh()
        $progress = Get-Qcl099MonitorProgress -OutDir $OutDir -MediaDir $mediaDir -StartedAt $startedAt -TimeoutSeconds $OverallTimeoutSeconds
        $progress["runner_pid"] = $process.Id
        $progress["runner_has_exited"] = [bool]$process.HasExited
        $progressSignature = Get-Qcl099ProgressSignature -Progress $progress
        if ($progressSignature -ne $lastProgressSignature) {
            $lastProgressSignature = $progressSignature
            $lastProgressAt = Get-Date
        }
        $phaseStallElapsedSeconds = [int][Math]::Floor(((Get-Date) - $lastProgressAt).TotalSeconds)
        $progress["phase_stall_elapsed_seconds"] = $phaseStallElapsedSeconds
        $progress["phase_stall_timeout_seconds"] = $PhaseStallTimeoutSeconds
        Add-JsonLine -Value $progress -Path $progressPath
        Write-Host ("[qcl099-monitor] elapsed={0}s remaining={1}s phase={2} files={3} stall={4}s summary={5}" -f `
            $progress.elapsed_seconds,
            $progress.remaining_seconds,
            $progress.phase,
            $progress.artifacts.media_file_count,
            $phaseStallElapsedSeconds,
            $progress.final_summary_present)

        if ($process.HasExited) {
            try {
                $process.WaitForExit()
            } catch {
            }
            $exitCode = $process.ExitCode
            if ($null -eq $exitCode -and (Test-Path -LiteralPath (Join-Path $OutDir "stereo-projection-summary.json"))) {
                $exitCode = 0
                $exitCodeInferred = $true
            }
            break
        }
        if ($phaseStallElapsedSeconds -ge $PhaseStallTimeoutSeconds) {
            $phaseStalled = $true
            Write-Host "[qcl099-monitor] phase-stall timeout reached; stopping runner process tree and cleaning devices"
            Stop-ProcessTree -ProcessId $process.Id
            break
        }
        if ((Get-Date) -ge $deadline) {
            $timedOut = $true
            Write-Host "[qcl099-monitor] timeout reached; stopping runner process tree and cleaning devices"
            Stop-ProcessTree -ProcessId $process.Id
            break
        }

        $remainingSleep = [int][Math]::Max(1, [Math]::Min($PollSeconds, ($deadline - (Get-Date)).TotalSeconds))
        Start-Sleep -Seconds $remainingSleep
    }
} finally {
    if (-not $SkipRunnerCleanup) {
        $cleanup = Invoke-Qcl099Cleanup -Serials @($OwnerSerial, $ClientSerial)
    }
}

$endedAt = Get-Date
$finalSummaryPath = Join-Path $OutDir "stereo-projection-summary.json"
$finalSummary = Read-JsonIfPresent -Path $finalSummaryPath
$postRunCleanupReadback = [ordered]@{
    schema = "rusty.quest.qcl099_post_run_cleanup_readback.v1"
    run_id = $RunId
    timestamp = $endedAt.ToString("o")
    cleanup_attempted = -not $SkipRunnerCleanup
    cleanup_readback_clean = if ($SkipRunnerCleanup) { $false } else { Test-CleanupRowsClean -Rows $cleanup }
    cleanup = $cleanup
}
Write-JsonFile -Value $postRunCleanupReadback -Path $postRunCleanupReadbackPath
$status = "failed"
if ($timedOut) {
    $status = "timeout"
} elseif ($phaseStalled) {
    $status = "phase_stall_timeout"
} elseif ($exitCode -eq 0 -and $null -ne $finalSummary) {
    $status = "completed"
} elseif ($exitCode -eq 0) {
    $status = "completed_without_summary"
}

$monitorSummary = [ordered]@{
    schema = "rusty.quest.qcl099_monitor_summary.v1"
    run_id = $RunId
    status = $status
    started_at = $startedAt.ToString("o")
    ended_at = $endedAt.ToString("o")
    elapsed_seconds = [int][Math]::Ceiling(($endedAt - $startedAt).TotalSeconds)
    timeout_seconds = $OverallTimeoutSeconds
    phase_stall_timeout_seconds = $PhaseStallTimeoutSeconds
    timed_out = $timedOut
    phase_stalled = $phaseStalled
    last_progress_at = $lastProgressAt.ToString("o")
    runner_exit_code = $exitCode
    runner_exit_code_inferred = $exitCodeInferred
    out_dir = $OutDir
    runner_path = $RunnerPath
    stdout_path = $stdoutPath
    stderr_path = $stderrPath
    progress_path = $progressPath
    runner_params_path = $runnerParamsPath
    final_summary_path = $finalSummaryPath
    final_summary_present = [bool]($null -ne $finalSummary)
    transport_owner = Get-NestedValue -Object $finalSummary -Path @("transport_owner")
    qcl100_lower_gate_authority = Get-NestedValue -Object $finalSummary -Path @("qcl100_lower_gate_authority")
    projection_ready_both_headsets = Get-NestedValue -Object $finalSummary -Path @("projection_ready_both_headsets")
    owner_projection_ready = Get-NestedValue -Object $finalSummary -Path @("owner_projection_ready")
    client_projection_ready = Get-NestedValue -Object $finalSummary -Path @("client_projection_ready")
    direct_p2p_media_ready = Get-NestedValue -Object $finalSummary -Path @("direct_p2p_media_ready")
    direct_p2p_makepad_projection_ready_both_headsets = Get-NestedValue -Object $finalSummary -Path @("direct_p2p_makepad_projection_ready_both_headsets")
    cleanup = $cleanup
    post_run_cleanup_readback_path = $postRunCleanupReadbackPath
    post_run_cleanup_readback = $postRunCleanupReadback
    final_progress = Get-Qcl099MonitorProgress -OutDir $OutDir -MediaDir $mediaDir -StartedAt $startedAt -TimeoutSeconds $OverallTimeoutSeconds
}
Write-JsonFile -Value $monitorSummary -Path $monitorSummaryPath

Get-Content -Raw -LiteralPath $monitorSummaryPath

if ($status -ne "completed") {
    throw "QCL099 monitored run ended with status=$status; see $monitorSummaryPath"
}
