param(
    [string]$OwnerSerial = "340YC10G7T0JBW",
    [string]$ClientSerial = "3487C10H3M017Q",
    [string]$OwnerLeaseId = "",
    [string]$ClientLeaseId = "",
    [string]$RunId = "",
    [string]$OutDir = "",
    [string]$RunnerPath = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$Qcl041Apk = "S:\Work\repos\active\rusty-quest\target\qcl041-wifi-direct-harness-android\rusty-quest-qcl041-wifi-direct-harness.apk",
    [string]$BrokerApk = "S:\Work\repos\active\rusty-quest\target\manifold-broker-android\rusty-manifold-broker.apk",
    [string]$NativeRendererApk = "S:\Work\repos\active\rusty-quest\target\native-renderer-android\rusty-quest-native-renderer.apk",
    [string]$NativeRendererProfile = "S:\Work\repos\active\rusty-quest\fixtures\runtime-profiles\quest-native-renderer-broker-rmanvid1-stereo-camera.profile.json",
    [ValidateSet("duplex", "owner-to-client", "client-to-owner")]
    [string]$Direction = "duplex",
    [ValidateSet("stereo", "left-only", "right-only")]
    [string]$LaneMode = "stereo",
    [ValidateSet("qcl041", "broker")]
    [string]$TransportOwner = "broker",
    [ValidateSet("android_connectivitymanager_network", "rusty_direct_p2p_socket_authority")]
    [string]$Qcl100LowerGateAuthority = "rusty_direct_p2p_socket_authority",
    [int]$ProjectionSeconds = 20,
    [int]$LiveBridgeCommandTimeoutSeconds = 60,
    [double]$MinFreshFrameSpanSeconds = 25.0,
    [int]$MinFreshFrameLines = 4,
    [int]$OwnerBrokerLocalPort = 18765,
    [int]$ClientBrokerLocalPort = 18766,
    [string]$OwnerWifiDirectAddress = "192.168.49.1",
    [string]$ClientWifiDirectAddress = "192.168.49.46",
    [string]$Qcl041Q2qNetworkName = "DIRECT-rq-QCL100",
    [string]$Qcl041Q2qPassphrase = "RustyQcl100Pass",
    [string]$MediaProfiles = "left:320x240@15:500000;right:320x240@15:500000",
    [ValidateSet("udp", "tcp", "reverse-tcp", "control-tcp", "mixed", "mixed-client-tcp")]
    [string]$Qcl082TransportProtocol = "udp",
    [int]$Qcl082ControlTcpMediaStreamBytesPerDirection = 0,
    [int]$Qcl082ControlTcpMediaStreamChunkBytes = 16384,
    [int]$PollSeconds = 30,
    [int]$OverallTimeoutSeconds = 900,
    [int]$PhaseStallTimeoutSeconds = 300,
    [switch]$RequireInfrastructureWifiDisconnected,
    [switch]$RequireP2p0Ipv4Cleared,
    [switch]$RequireCandidateWifiDirectRoutesClear,
    [switch]$RunQcl041PreclearBeforeAirgapPreflight,
    [switch]$RequireQcl041MatrixGatePass,
    [string]$RequiredQcl041MatrixSummaryPath = "",
    [string]$RequiredQcl041MatrixRunId = "",
    [int]$MaxQcl041MatrixGateAgeSeconds = 1800,
    [switch]$PreflightOnly,
    [switch]$SkipInstall,
    [switch]$SkipWakePrep,
    [switch]$AllowWakePrepMutation,
    [switch]$SkipRunnerCleanup,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

if ($PollSeconds -lt 5) {
    throw "PollSeconds must be at least 5 so the monitor has a meaningful status cadence."
}
if ($OverallTimeoutSeconds -lt 60) {
    throw "OverallTimeoutSeconds must be at least 60 for a QCL100 live run."
}
if ($PhaseStallTimeoutSeconds -lt 60) {
    throw "PhaseStallTimeoutSeconds must be at least 60 for a QCL100 live run."
}
if ($LiveBridgeCommandTimeoutSeconds -lt 15) {
    throw "LiveBridgeCommandTimeoutSeconds must be at least 15 seconds so bridge commands have a bounded but useful live window."
}
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl100-q2q-native-stereo-projection-monitored-" + (Get-Date -Format "yyyyMMddTHHmmssZ").ToString()
}
if ([string]::IsNullOrWhiteSpace($RunnerPath)) {
    $RunnerPath = Join-Path $PSScriptRoot "Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1"
}
$RunnerPath = (Resolve-Path -LiteralPath $RunnerPath).Path
$runnerWorkingDirectory = Split-Path -Parent (Split-Path -Parent $RunnerPath)
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path "S:\Work\repos\active\rusty-quest\target" $RunId
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$mediaDir = Join-Path $OutDir "media"
$progressPath = Join-Path $OutDir "qcl100-monitor-progress.jsonl"
$monitorSummaryPath = Join-Path $OutDir "qcl100-monitor-summary.json"
$runnerParamsPath = Join-Path $OutDir "qcl100-runner-params.json"
$runnerChildScriptPath = Join-Path $OutDir "qcl100-runner-child.ps1"
$stdoutPath = Join-Path $OutDir "qcl100-runner.stdout.txt"
$stderrPath = Join-Path $OutDir "qcl100-runner.stderr.txt"
$postRunCleanupReadbackPath = Join-Path $OutDir "qcl100-post-run-cleanup-readback.json"

function Write-JsonFile {
    param([object]$Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 32) + "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function Add-JsonLine {
    param([object]$Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 16 -Compress) + "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::AppendAllText($Path, $json, $utf8NoBom)
}

function Read-JsonIfPresent {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    }
    return $null
}

function Get-Qcl100LatestEvidenceFileSummary {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    $ignoredMonitorFiles = @(
        "qcl100-monitor-progress.jsonl",
        "qcl100-monitor-summary.json",
        "qcl100-monitor-wrapper.stdout.txt",
        "qcl100-monitor-wrapper.stderr.txt",
        "qcl100-post-run-cleanup-readback.json"
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

function Get-NestedValue {
    param($Object, [string[]]$Path)
    $value = $Object
    foreach ($part in $Path) {
        if ($null -eq $value) {
            return $null
        }
        $property = $value.PSObject.Properties[$part]
        if ($null -eq $property) {
            return $null
        }
        $value = $property.Value
    }
    return $value
}

function ConvertTo-PowerShellSingleQuotedLiteral {
    param([string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function ConvertTo-EncodedCommand {
    param([string]$ScriptText)
    return [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($ScriptText))
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

function Invoke-AdbBestEffort {
    param([string]$Serial, [string[]]$Arguments)
    & $Adb -s $Serial @Arguments 2>&1 | Out-Null
}

function Invoke-AdbTextBestEffort {
    param([string]$Serial, [string[]]$Arguments)
    $output = & $Adb -s $Serial @Arguments 2>&1 | Out-String
    [ordered]@{
        exit_code = $LASTEXITCODE
        output = $output
    }
}

function Get-Qcl100WifiStatusFields {
    param([string]$RawStatus)
    $infrastructureConnected = [bool]($RawStatus -match 'Wifi is connected to "([^"]+)"')
    $ssid = if ($infrastructureConnected) { $Matches[1] } else { "" }
    [ordered]@{
        infrastructure_connected = $infrastructureConnected
        infrastructure_ssid = $ssid
        raw_status = $RawStatus.TrimEnd()
    }
}

function Get-Qcl100P2p0StatusFields {
    param([string]$RawStatus)
    $ipv4Present = [bool]($RawStatus -match 'inet\s+([0-9]+(?:\.[0-9]+){3})/')
    $ipv4Address = if ($ipv4Present) { $Matches[1] } else { "" }
    [ordered]@{
        ipv4_present = $ipv4Present
        ipv4_address = $ipv4Address
        raw_status = $RawStatus.TrimEnd()
    }
}

function Read-Qcl100DeviceState {
    param([string]$Serial)
    $wifi = Invoke-AdbTextBestEffort -Serial $Serial -Arguments @("shell", "cmd", "wifi", "status")
    $p2p0 = Invoke-AdbTextBestEffort -Serial $Serial -Arguments @("shell", "ip", "-4", "addr", "show", "p2p0")
    $wifiFields = Get-Qcl100WifiStatusFields -RawStatus ([string]$wifi.output)
    $p2p0Fields = Get-Qcl100P2p0StatusFields -RawStatus ([string]$p2p0.output)
    [ordered]@{
        serial = $Serial
        wifi_exit_code = $wifi.exit_code
        wifi = $wifiFields
        p2p0_exit_code = $p2p0.exit_code
        p2p0 = $p2p0Fields
    }
}

function Save-Qcl041DeviceArtifact {
    param(
        [string]$Serial,
        [string]$Label,
        [string]$RunId,
        [string]$OutPath
    )
    $alreadyPresent = Test-Path -LiteralPath $OutPath
    $read = Invoke-AdbTextBestEffort -Serial $Serial -Arguments @(
        "exec-out",
        "run-as",
        "io.github.mesmerprism.rustyquest.qcl041",
        "cat",
        "files/qcl041/$RunId.json"
    )
    $artifact = $null
    $saved = $false
    $parseStatus = "not_read"
    if ($read.exit_code -eq 0 -and -not [string]::IsNullOrWhiteSpace([string]$read.output)) {
        try {
            $artifact = [string]$read.output | ConvertFrom-Json
            $parseStatus = "pass"
            if (-not $alreadyPresent) {
                [string]$read.output | Set-Content -Encoding UTF8 -Path $OutPath
                $saved = $true
            }
        } catch {
            $parseStatus = "json_parse_failed"
        }
    } elseif ($read.exit_code -eq 0) {
        $parseStatus = "empty"
    } else {
        $parseStatus = "read_failed"
    }
    [ordered]@{
        label = $Label
        serial = $Serial
        out_path = $OutPath
        already_present = $alreadyPresent
        saved = $saved
        read_exit_code = $read.exit_code
        parse_status = $parseStatus
        artifact_present = [bool](Test-Path -LiteralPath $OutPath)
        observed_at_utc = Get-NestedValue -Object $artifact -Path @("observed_at_utc")
        q2q_role = Get-NestedValue -Object $artifact -Path @("topology", "q2q_role")
        peer_discovery_status = Get-NestedValue -Object $artifact -Path @("lifecycle", "peer_discovery", "status")
        group_formation_status = Get-NestedValue -Object $artifact -Path @("lifecycle", "group_formation", "status")
        socket_exchange_status = Get-NestedValue -Object $artifact -Path @("lifecycle", "socket_exchange", "status")
        cleanup_status = Get-NestedValue -Object $artifact -Path @("lifecycle", "cleanup", "status")
        cleanup_completed = Get-NestedValue -Object $artifact -Path @("lifecycle", "cleanup", "completed")
        wifi_direct_local_address = Get-NestedValue -Object $artifact -Path @("diagnostics", "lifecycle", "wifi_direct_local_address")
        wifi_direct_network_found = Get-NestedValue -Object $artifact -Path @("diagnostics", "control_tcp", "wifi_direct_network_found")
    }
}

function Invoke-Qcl100Cleanup {
    param([string[]]$Serials)
    $rows = @()
    foreach ($serial in $Serials) {
        foreach ($package in @(
            "io.github.mesmerprism.rustyquest.qcl041",
            "io.github.mesmerprism.rustymanifold.broker",
            "io.github.mesmerprism.rustyquest.native_renderer"
        )) {
            Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", $package)
        }
        Invoke-AdbBestEffort -Serial $serial -Arguments @("forward", "--remove-all")
        $deviceState = Read-Qcl100DeviceState -Serial $serial
        $rows += [ordered]@{
            serial = $serial
            cleanup_attempted = $true
            post_cleanup_device_state = $deviceState
        }
    }
    return $rows
}

function Get-Qcl100LatestLiveBridgeCommandAttempt {
    param([string]$MediaDir)
    if (-not (Test-Path -LiteralPath $MediaDir)) {
        return $null
    }
    $file = @(Get-ChildItem -LiteralPath $MediaDir -Filter "*-live-command-attempt.json" -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1)
    if ($file.Count -eq 0) {
        return $null
    }
    try {
        $receipt = Get-Content -Raw -LiteralPath $file[0].FullName | ConvertFrom-Json
        return [ordered]@{
            path = $file[0].FullName
            last_write_utc = $file[0].LastWriteTimeUtc.ToString("o")
            name = [string](Get-NestedValue -Object $receipt -Path @("name"))
            serial = [string](Get-NestedValue -Object $receipt -Path @("serial"))
            status = [string](Get-NestedValue -Object $receipt -Path @("status"))
            attempt = Get-NestedValue -Object $receipt -Path @("attempt")
            timeout_seconds = Get-NestedValue -Object $receipt -Path @("timeout_seconds")
            elapsed_ms = Get-NestedValue -Object $receipt -Path @("elapsed_ms")
            error = [string](Get-NestedValue -Object $receipt -Path @("error"))
            receipt = $receipt
        }
    } catch {
        return [ordered]@{
            path = $file[0].FullName
            last_write_utc = $file[0].LastWriteTimeUtc.ToString("o")
            read_error = $_.Exception.Message
        }
    }
}

function Get-Qcl100MonitorProgress {
    param([string]$OutDir, [string]$MediaDir, [datetime]$StartedAt, [int]$TimeoutSeconds)
    $now = Get-Date
    $summaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
    $failurePath = Join-Path $OutDir "qcl100-orchestration-failure.json"
    $ownerQcl041Path = Join-Path $OutDir "owner-qcl041.json"
    $clientQcl041Path = Join-Path $OutDir "client-qcl041.json"
    $qcl041RelayLaunchPath = Join-Path $OutDir "qcl100-qcl041-relays-launched.json"
    $addressRefreshAttemptPath = Join-Path $OutDir "qcl100-direct-p2p-address-refresh-attempt.json"
    $summary = Read-JsonIfPresent -Path $summaryPath
    $addressRefreshAttempt = Read-JsonIfPresent -Path $addressRefreshAttemptPath
    $latestLiveBridgeCommandAttempt = Get-Qcl100LatestLiveBridgeCommandAttempt -MediaDir $MediaDir
    $phase = "starting"
    if (Test-Path -LiteralPath $failurePath) {
        $phase = "failure_summary_present"
    } elseif ($null -ne $summary) {
        $phase = "final_summary_present"
    } elseif ((Test-Path -LiteralPath $ownerQcl041Path) -or (Test-Path -LiteralPath $clientQcl041Path)) {
        $phase = "final_qcl041_artifacts_present"
    } elseif ($null -ne $addressRefreshAttempt -and [string]$addressRefreshAttempt.status -eq "running") {
        $phase = "direct_p2p_address_refresh_running"
    } elseif ($null -ne $addressRefreshAttempt -and [string]$addressRefreshAttempt.status -eq "blocked") {
        $phase = "direct_p2p_address_refresh_blocked"
    } elseif ($null -ne $addressRefreshAttempt -and [string]$addressRefreshAttempt.status -eq "pass") {
        $phase = "direct_p2p_address_refresh_passed"
    } elseif (Test-Path -LiteralPath $qcl041RelayLaunchPath) {
        $phase = "qcl041_relays_launched"
    } elseif (Test-Path -LiteralPath $MediaDir) {
        $mediaNames = @(Get-ChildItem -LiteralPath $MediaDir -File -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
        if ($null -ne $latestLiveBridgeCommandAttempt -and [string]$latestLiveBridgeCommandAttempt.status -eq "running") {
            $phase = "live_bridge_command_running"
        } elseif ($null -ne $latestLiveBridgeCommandAttempt -and [string]$latestLiveBridgeCommandAttempt.status -eq "fail") {
            $phase = "live_bridge_command_failed"
        } elseif ($mediaNames -contains "owner-final-status-execution.json" -or $mediaNames -contains "client-final-status-execution.json") {
            $phase = "final_broker_status_present"
        } elseif ($mediaNames -contains "owner-start-source-only-live-command-attempt.json" -or $mediaNames -contains "client-start-source-only-live-command-attempt.json") {
            $phase = "broker_sender_start_attempted"
        } elseif ($mediaNames -contains "owner-start-receiver-execution.json" -or $mediaNames -contains "client-start-receiver-execution.json") {
            $phase = "broker_receiver_started"
        } elseif ($mediaNames.Count -gt 0) {
            $phase = "media_artifacts_present"
        }
    }
    $files = @()
    if (Test-Path -LiteralPath $OutDir) {
        $files = @(Get-ChildItem -LiteralPath $OutDir -Recurse -File -ErrorAction SilentlyContinue)
    }
    $latestAnyWriteUtc = if ($files.Count -gt 0) {
        (@($files | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1)[0]).LastWriteTimeUtc.ToString("o")
    } else {
        ""
    }
    $latestEvidenceFile = Get-Qcl100LatestEvidenceFileSummary -Path $OutDir
    $latestEvidenceWriteUtc = if ($null -eq $latestEvidenceFile) { "" } else { $latestEvidenceFile.last_write_utc }
    [ordered]@{
        schema = "rusty.quest.qcl100_monitor_progress.v1"
        timestamp = $now.ToString("o")
        run_id = $RunId
        out_dir = $OutDir
        phase = $phase
        elapsed_seconds = [int][Math]::Floor(($now - $StartedAt).TotalSeconds)
        remaining_seconds = [int][Math]::Max(0, [Math]::Floor(($StartedAt.AddSeconds($TimeoutSeconds) - $now).TotalSeconds))
        artifacts = [ordered]@{
            total_file_count = $files.Count
            media_file_count = if (Test-Path -LiteralPath $MediaDir) { @(Get-ChildItem -LiteralPath $MediaDir -File -ErrorAction SilentlyContinue).Count } else { 0 }
            latest_write_utc = $latestEvidenceWriteUtc
            latest_evidence_file = $latestEvidenceFile
            latest_any_write_utc = $latestAnyWriteUtc
            final_summary_present = [bool]($null -ne $summary)
            failure_summary_present = Test-Path -LiteralPath $failurePath
            owner_qcl041_json = Test-Path -LiteralPath $ownerQcl041Path
            client_qcl041_json = Test-Path -LiteralPath $clientQcl041Path
            qcl041_relays_launched = Test-Path -LiteralPath $qcl041RelayLaunchPath
            direct_p2p_address_refresh_attempt = $addressRefreshAttempt
            latest_live_bridge_command_attempt = $latestLiveBridgeCommandAttempt
        }
        final_summary_status = Get-NestedValue -Object $summary -Path @("status")
        active_receiver_projection_ready = Get-NestedValue -Object $summary -Path @("active_receiver_projection_ready")
        same_group_duplex_claimed = Get-NestedValue -Object $summary -Path @("same_group_duplex_claimed")
    }
}

function Get-Qcl100ProgressSignature {
    param($Progress)
    return @(
        $Progress.phase,
        $Progress.artifacts.total_file_count,
        $Progress.artifacts.media_file_count,
        $Progress.artifacts.latest_write_utc,
        $(if ($null -eq $Progress.artifacts.latest_evidence_file) { "" } else { $Progress.artifacts.latest_evidence_file.path }),
        $(if ($null -eq $Progress.artifacts.latest_evidence_file) { "" } else { $Progress.artifacts.latest_evidence_file.bytes }),
        $Progress.artifacts.final_summary_present,
        $Progress.artifacts.failure_summary_present,
        $Progress.artifacts.owner_qcl041_json,
        $Progress.artifacts.client_qcl041_json,
        $Progress.artifacts.qcl041_relays_launched,
        $(if ($null -eq $Progress.artifacts.direct_p2p_address_refresh_attempt) { "" } else { $Progress.artifacts.direct_p2p_address_refresh_attempt.status }),
        $(if ($null -eq $Progress.artifacts.direct_p2p_address_refresh_attempt) { "" } else { $Progress.artifacts.direct_p2p_address_refresh_attempt.attempt_count }),
        $(if ($null -eq $Progress.artifacts.latest_live_bridge_command_attempt) { "" } else { $Progress.artifacts.latest_live_bridge_command_attempt.name }),
        $(if ($null -eq $Progress.artifacts.latest_live_bridge_command_attempt) { "" } else { $Progress.artifacts.latest_live_bridge_command_attempt.status }),
        $(if ($null -eq $Progress.artifacts.latest_live_bridge_command_attempt) { "" } else { $Progress.artifacts.latest_live_bridge_command_attempt.attempt }),
        $Progress.final_summary_status
    ) -join "|"
}

$runnerParams = [ordered]@{
    OwnerSerial = $OwnerSerial
    ClientSerial = $ClientSerial
    OwnerLeaseId = $OwnerLeaseId
    ClientLeaseId = $ClientLeaseId
    RunId = $RunId
    OutDir = $OutDir
    Adb = $Adb
    Qcl041Apk = $Qcl041Apk
    BrokerApk = $BrokerApk
    NativeRendererApk = $NativeRendererApk
    NativeRendererProfile = $NativeRendererProfile
    Direction = $Direction
    LaneMode = $LaneMode
    TransportOwner = $TransportOwner
    Qcl100LowerGateAuthority = $Qcl100LowerGateAuthority
    ProjectionSeconds = $ProjectionSeconds
    LiveBridgeCommandTimeoutSeconds = $LiveBridgeCommandTimeoutSeconds
    MinFreshFrameSpanSeconds = $MinFreshFrameSpanSeconds
    MinFreshFrameLines = $MinFreshFrameLines
    OwnerBrokerLocalPort = $OwnerBrokerLocalPort
    ClientBrokerLocalPort = $ClientBrokerLocalPort
    OwnerWifiDirectAddress = $OwnerWifiDirectAddress
    ClientWifiDirectAddress = $ClientWifiDirectAddress
    Qcl041Q2qNetworkName = $Qcl041Q2qNetworkName
    Qcl041Q2qPassphrase = $Qcl041Q2qPassphrase
    MediaProfiles = $MediaProfiles
    Qcl082TransportProtocol = $Qcl082TransportProtocol
    Qcl082ControlTcpMediaStreamBytesPerDirection = $Qcl082ControlTcpMediaStreamBytesPerDirection
    Qcl082ControlTcpMediaStreamChunkBytes = $Qcl082ControlTcpMediaStreamChunkBytes
    RequireInfrastructureWifiDisconnected = [bool]$RequireInfrastructureWifiDisconnected
    RequireP2p0Ipv4Cleared = [bool]$RequireP2p0Ipv4Cleared
    RequireCandidateWifiDirectRoutesClear = [bool]$RequireCandidateWifiDirectRoutesClear
    RunQcl041PreclearBeforeAirgapPreflight = [bool]$RunQcl041PreclearBeforeAirgapPreflight
    RequireQcl041MatrixGatePass = [bool]$RequireQcl041MatrixGatePass
    RequiredQcl041MatrixSummaryPath = $RequiredQcl041MatrixSummaryPath
    RequiredQcl041MatrixRunId = $RequiredQcl041MatrixRunId
    MaxQcl041MatrixGateAgeSeconds = $MaxQcl041MatrixGateAgeSeconds
    PreflightOnly = [bool]$PreflightOnly
    SkipInstall = [bool]$SkipInstall
    SkipWakePrep = [bool]$SkipWakePrep
    AllowWakePrepMutation = [bool]$AllowWakePrepMutation
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
        schema = "rusty.quest.qcl100_monitor_summary.v1"
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

Write-Host "[qcl100-monitor] starting run_id=$RunId timeout=${OverallTimeoutSeconds}s phase_stall=${PhaseStallTimeoutSeconds}s poll=${PollSeconds}s out_dir=$OutDir"
try {
    $process = Start-Process `
        -FilePath "powershell" `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", $encodedCommand) `
        -WorkingDirectory $runnerWorkingDirectory `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -WindowStyle Hidden `
        -PassThru

    while ($true) {
        $process.Refresh()
        $progress = Get-Qcl100MonitorProgress -OutDir $OutDir -MediaDir $mediaDir -StartedAt $startedAt -TimeoutSeconds $OverallTimeoutSeconds
        $progress["runner_pid"] = $process.Id
        $progress["runner_has_exited"] = [bool]$process.HasExited
        $progressSignature = Get-Qcl100ProgressSignature -Progress $progress
        if ($progressSignature -ne $lastProgressSignature) {
            $lastProgressSignature = $progressSignature
            $lastProgressAt = Get-Date
        }
        $phaseStallElapsedSeconds = [int][Math]::Floor(((Get-Date) - $lastProgressAt).TotalSeconds)
        $progress["phase_stall_elapsed_seconds"] = $phaseStallElapsedSeconds
        $progress["phase_stall_timeout_seconds"] = $PhaseStallTimeoutSeconds
        Add-JsonLine -Value $progress -Path $progressPath
        $latestBridgeCommand = Get-NestedValue -Object $progress -Path @("artifacts", "latest_live_bridge_command_attempt", "name")
        $latestBridgeCommandStatus = Get-NestedValue -Object $progress -Path @("artifacts", "latest_live_bridge_command_attempt", "status")
        if ([string]::IsNullOrWhiteSpace([string]$latestBridgeCommand)) {
            $latestBridgeCommand = "none"
            $latestBridgeCommandStatus = ""
        }
        $addressRefreshStatus = Get-NestedValue -Object $progress -Path @("artifacts", "direct_p2p_address_refresh_attempt", "status")
        $addressRefreshAttemptCount = Get-NestedValue -Object $progress -Path @("artifacts", "direct_p2p_address_refresh_attempt", "attempt_count")
        if ([string]::IsNullOrWhiteSpace([string]$addressRefreshStatus)) {
            $addressRefreshStatus = "none"
            $addressRefreshAttemptCount = ""
        }
        Write-Host ("[qcl100-monitor] elapsed={0}s remaining={1}s phase={2} files={3} media={4} stall={5}s summary={6} bridge={7}/{8} addr={9}/{10}" -f `
            $progress.elapsed_seconds,
            $progress.remaining_seconds,
            $progress.phase,
            $progress.artifacts.total_file_count,
            $progress.artifacts.media_file_count,
            $phaseStallElapsedSeconds,
            $progress.artifacts.final_summary_present,
            $latestBridgeCommand,
            $latestBridgeCommandStatus,
            $addressRefreshStatus,
            $addressRefreshAttemptCount)

        if ($process.HasExited) {
            try {
                $process.WaitForExit()
            } catch {
            }
            $exitCode = $process.ExitCode
            if ($null -eq $exitCode -and (Test-Path -LiteralPath (Join-Path $OutDir "native-stereo-projection-summary.json"))) {
                $exitCode = 0
                $exitCodeInferred = $true
            }
            break
        }
        if ($phaseStallElapsedSeconds -ge $PhaseStallTimeoutSeconds) {
            $phaseStalled = $true
            Write-Host "[qcl100-monitor] phase-stall timeout reached; stopping runner process tree and cleaning devices"
            Stop-ProcessTree -ProcessId $process.Id
            break
        }
        if ((Get-Date) -ge $deadline) {
            $timedOut = $true
            Write-Host "[qcl100-monitor] timeout reached; stopping runner process tree and cleaning devices"
            Stop-ProcessTree -ProcessId $process.Id
            break
        }
        $remainingSleep = [int][Math]::Max(1, [Math]::Min($PollSeconds, ($deadline - (Get-Date)).TotalSeconds))
        Start-Sleep -Seconds $remainingSleep
    }
} finally {
    if (-not $SkipRunnerCleanup) {
        $cleanup = Invoke-Qcl100Cleanup -Serials @($OwnerSerial, $ClientSerial)
    }
}

$endedAt = Get-Date
$finalSummaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
$finalSummary = Read-JsonIfPresent -Path $finalSummaryPath
$finalSummaryStatus = Get-NestedValue -Object $finalSummary -Path @("status")
$ownerQcl041DeviceArtifact = Save-Qcl041DeviceArtifact `
    -Serial $OwnerSerial `
    -Label "owner" `
    -RunId $RunId `
    -OutPath (Join-Path $OutDir "owner-qcl041.json")
$clientQcl041DeviceArtifact = Save-Qcl041DeviceArtifact `
    -Serial $ClientSerial `
    -Label "client" `
    -RunId $RunId `
    -OutPath (Join-Path $OutDir "client-qcl041.json")
$qcl041DeviceArtifacts = @($ownerQcl041DeviceArtifact, $clientQcl041DeviceArtifact)
$postRunCleanupReadback = [ordered]@{
    schema = "rusty.quest.qcl100_post_run_cleanup_readback.v1"
    run_id = $RunId
    timestamp = $endedAt.ToString("o")
    cleanup_attempted = -not $SkipRunnerCleanup
    cleanup = $cleanup
}
Write-JsonFile -Value $postRunCleanupReadback -Path $postRunCleanupReadbackPath

$status = "failed"
if ($timedOut) {
    $status = "timeout"
} elseif ($phaseStalled) {
    $status = "phase_stall_timeout"
} elseif ($exitCode -eq 0 -and $null -ne $finalSummary) {
    if (-not [string]::IsNullOrWhiteSpace([string]$finalSummaryStatus) -and
        ([string]$finalSummaryStatus -match '^(blocked|failed|failure)')) {
        $status = [string]$finalSummaryStatus
    } else {
        $status = "completed"
    }
} elseif ($exitCode -eq 0) {
    $status = "completed_without_summary"
}

$finalTransportOwner = Get-NestedValue -Object $finalSummary -Path @("transport_owner")
if ([string]::IsNullOrWhiteSpace([string]$finalTransportOwner)) {
    $finalTransportOwner = Get-NestedValue -Object $finalSummary -Path @("topology", "transport_owner")
}
$finalLowerGateAuthority = Get-NestedValue -Object $finalSummary -Path @("qcl100_lower_gate_authority")
if ([string]::IsNullOrWhiteSpace([string]$finalLowerGateAuthority)) {
    $finalLowerGateAuthority = Get-NestedValue -Object $finalSummary -Path @("topology", "qcl100_lower_gate_authority")
}

$monitorSummary = [ordered]@{
    schema = "rusty.quest.qcl100_monitor_summary.v1"
    run_id = $RunId
    status = $status
    started_at = $startedAt.ToString("o")
    ended_at = $endedAt.ToString("o")
    elapsed_seconds = [int][Math]::Ceiling(($endedAt - $startedAt).TotalSeconds)
    timeout_seconds = $OverallTimeoutSeconds
    phase_stall_timeout_seconds = $PhaseStallTimeoutSeconds
    live_bridge_command_timeout_seconds = $LiveBridgeCommandTimeoutSeconds
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
    final_summary_status = $finalSummaryStatus
    transport_owner = $finalTransportOwner
    qcl100_lower_gate_authority = $finalLowerGateAuthority
    active_receiver_projection_ready = Get-NestedValue -Object $finalSummary -Path @("active_receiver_projection_ready")
    same_group_duplex_claimed = Get-NestedValue -Object $finalSummary -Path @("same_group_duplex_claimed")
    qcl041_device_artifacts = $qcl041DeviceArtifacts
    cleanup = $cleanup
    post_run_cleanup_readback_path = $postRunCleanupReadbackPath
    post_run_cleanup_readback = $postRunCleanupReadback
    final_progress = Get-Qcl100MonitorProgress -OutDir $OutDir -MediaDir $mediaDir -StartedAt $startedAt -TimeoutSeconds $OverallTimeoutSeconds
}
Write-JsonFile -Value $monitorSummary -Path $monitorSummaryPath

Get-Content -Raw -LiteralPath $monitorSummaryPath

if ($status -ne "completed") {
    throw "QCL100 monitored run ended with status=$status; see $monitorSummaryPath"
}
