param(
    [string]$OwnerSerial = "340YC10G7T0JBW",
    [string]$ClientSerial = "3487C10H3M017Q",
    [string]$OwnerLeaseId = "",
    [string]$ClientLeaseId = "",
    [string]$RunId = "",
    [string]$OutDir = "",
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
    [switch]$SkipInstall,
    [switch]$SkipWakePrep,
    [switch]$SkipCleanup
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl099-q2q-stereo-projection-" + (Get-Date -Format "yyyyMMddTHHmmssZ").ToString()
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path "S:\Work\repos\active\rusty-quest\target" $RunId
}

$MediaDir = Join-Path $OutDir "media"
$RunnerProgressPath = Join-Path $OutDir "qcl099-runner-progress.jsonl"
New-Item -ItemType Directory -Force -Path $MediaDir | Out-Null

$helperRoot = Join-Path $PSScriptRoot "qcl100_native_projection"
. (Join-Path $helperRoot "DirectP2pMediaAuthority.ps1")

function Invoke-External {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$File,
        [string[]]$Arguments = @(),
        [string]$LogPath = ""
    )
    $output = & $File @Arguments 2>&1 | Out-String
    if (-not [string]::IsNullOrWhiteSpace($LogPath)) {
        $output | Set-Content -Encoding UTF8 -Path $LogPath
    }
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE. $output"
    }
    return $output
}

function Invoke-AdbBestEffort {
    param([string]$Serial, [string[]]$Arguments)
    & $Adb -s $Serial @Arguments 2>&1 | Out-Null
}

function Invoke-AdbChecked {
    param([string]$Serial, [string[]]$Arguments, [string]$Name = "adb")
    Invoke-External -Name "$Name $Serial" -File $Adb -Arguments (@("-s", $Serial) + $Arguments) | Out-Null
}

function Read-AdbText {
    param([string]$Serial, [string[]]$Arguments, [string]$Path = "")
    $output = & $Adb -s $Serial @Arguments 2>&1 | Out-String
    if (-not [string]::IsNullOrWhiteSpace($Path)) {
        $output | Set-Content -Encoding UTF8 -Path $Path
    }
    return $output
}

function Write-JsonFile {
    param([object]$Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 32) + "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function Add-RunnerProgress {
    param([string]$Phase, [object]$Data = $null)
    try {
        $row = [ordered]@{
            schema = "rusty.quest.qcl099_runner_progress.v1"
            timestamp = (Get-Date).ToString("o")
            run_id = $RunId
            out_dir = $OutDir
            phase = $Phase
        }
        if ($null -ne $Data) {
            $row["data"] = $Data
        }
        $json = ($row | ConvertTo-Json -Depth 16 -Compress) + "`n"
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::AppendAllText($RunnerProgressPath, $json, $utf8NoBom)
    } catch {
        Write-Warning "Failed to write QCL099 runner progress: $($_.Exception.Message)"
    }
}

function New-BridgeRequest {
    param(
        [string]$Name,
        [string]$Command,
        [object]$Params,
        [string]$RequestId,
        [string]$EvidenceId
    )
    $paramsPath = Join-Path $MediaDir "$Name-params.json"
    $requestPath = Join-Path $MediaDir "$Name-request.json"
    Write-JsonFile -Value $Params -Path $paramsPath
    Invoke-External `
        -Name "emit $Name" `
        -File $Python `
        -Arguments @(
            $HostessCtl,
            "emit-bridge-command-request",
            "--bridge-command", $Command,
            "--out", $requestPath,
            "--request-id", $RequestId,
            "--evidence-id", $EvidenceId,
            "--required-stage", "sent",
            "--required-stage", "authority_accepted",
            "--params-json-file", $paramsPath
        ) | Out-Null
    return $requestPath
}

function Invoke-LiveBridgeCommand {
    param(
        [string]$Name,
        [string]$Serial,
        [int]$BrokerLocalPort,
        [string]$RequestPath,
        [switch]$NoLaunchBroker
    )
    $args = @(
        $HostessCtl,
        "run-bridge-command-live-android",
        "--input", $RequestPath,
        "--out", (Join-Path $MediaDir "$Name-route.json"),
        "--execution-out", (Join-Path $MediaDir "$Name-execution.json"),
        "--validation-out", (Join-Path $MediaDir "$Name-validation.json"),
        "--logcat-out", (Join-Path $MediaDir "$Name.logcat.txt"),
        "--adb", $Adb,
        "--serial", $Serial,
        "--broker-local-port", $BrokerLocalPort.ToString(),
        "--broker-package", "io.github.mesmerprism.rustymanifold.broker",
        "--no-launch-makepad",
        "--no-wait-makepad-process",
        "--socket-wait-seconds", "10",
        "--wait-seconds", "10"
    )
    if ($NoLaunchBroker) {
        $args += @("--no-launch-broker", "--no-wait-broker-process")
    }
    Invoke-External -Name "live bridge command $Name" -File $Python -Arguments $args | Out-Null
    return [ordered]@{
        name = $Name
        execution_path = Join-Path $MediaDir "$Name-execution.json"
    }
}

function Get-Qcl099RemoteCameraRuntimeFromBridgeResult {
    param($BridgeResult)
    if ($null -eq $BridgeResult -or [string]::IsNullOrWhiteSpace([string]$BridgeResult.execution_path)) {
        return $null
    }
    if (-not (Test-Path -LiteralPath ([string]$BridgeResult.execution_path))) {
        return $null
    }
    $execution = Get-Content -Raw -LiteralPath ([string]$BridgeResult.execution_path) | ConvertFrom-Json
    $messages = @($execution.command_execution.broker_messages)
    if ($messages.Count -eq 0) {
        return $null
    }
    return $messages[0].remote_camera_runtime
}

function Assert-Qcl099ReceiverReady {
    param(
        [string]$Label,
        $BridgeResult
    )
    $runtime = Get-Qcl099RemoteCameraRuntimeFromBridgeResult $BridgeResult
    if ($null -eq $runtime) {
        throw "QCL099 $Label receiver start did not produce remote_camera_runtime readiness evidence."
    }
    if ([bool]$runtime.receiver_ready) {
        return
    }
    $status = [string]$runtime.receiver_ready_status
    if ([string]::IsNullOrWhiteSpace($status)) {
        $status = "unknown"
    }
    throw "QCL099 $Label receiver did not reach receiver_ready before sender start; status=$status execution=$($BridgeResult.execution_path)"
}

function Set-DeviceProperty {
    param([string]$Serial, [string]$Name, [string]$Value)
    Invoke-AdbChecked -Serial $Serial -Arguments @("shell", "setprop", $Name, $Value) -Name "setprop"
    $observed = (& $Adb -s $Serial shell getprop $Name 2>&1 | Select-Object -First 1).Trim()
    [ordered]@{
        name = $Name
        expected = $Value
        observed = $observed
        matched = [bool]($observed -eq $Value)
    }
}

function Get-MakepadDecodeRouteMetadata {
    param([string]$Mode)
    switch ($Mode) {
        "cpu-yuv" {
            return [ordered]@{
                decode_target = "MediaCodec CPU-YUV"
                texture_path = "broker-h264-mediacodec-cpu-yuv"
                makepad_vulkan_import = "false"
                requires_hardware_buffer = $false
            }
        }
        "hardware-buffer" {
            return [ordered]@{
                decode_target = "MediaCodec hardware-buffer"
                texture_path = "broker-h264-mediacodec-hardware-buffer"
                makepad_vulkan_import = "true"
                requires_hardware_buffer = $true
            }
        }
        "surface-texture" {
            return [ordered]@{
                decode_target = "MediaCodec surface-texture"
                texture_path = "broker-h264-surface-texture"
                makepad_vulkan_import = "false"
                requires_hardware_buffer = $false
            }
        }
    }
    throw "Unsupported Makepad decode output mode: $Mode"
}

function Set-MakepadBrokerProjectionProperties {
    param([string]$Serial)
    $properties = @(
        [ordered]@{ name = "debug.rustyquest.makepad.camera.streaming.enabled"; value = "true" },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.enabled"; value = "true" },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.host"; value = "127.0.0.1" },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.broker.port"; value = "8765" },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.stream.port"; value = $LeftReceiverPort.ToString() },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.right.stream.port"; value = $RightReceiverPort.ToString() },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.source.mode"; value = "existing-stream" },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.decode.output.mode"; value = $MakepadDecodeOutputMode },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.projection.geometry.profile"; value = "camera-projection" },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.source.sampling.mode"; value = "target-local-raster" },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.width"; value = $MakepadPreferredWidth.ToString() },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.height"; value = $MakepadPreferredHeight.ToString() },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.capture.ms"; value = "0" },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.max.packets"; value = "0" },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.frame.rate.hz"; value = $MakepadFrameRateHz.ToString() },
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.live.stream"; value = "true" },
        [ordered]@{ name = "debug.rustyquest.makepad.camera.projection.mode"; value = "display-screen-homography" },
        [ordered]@{ name = "debug.rustyquest.makepad.camera.projection.geometry.profile"; value = "camera-projection" },
        [ordered]@{ name = "debug.rustyquest.makepad.camera.source.sampling.mode"; value = "target-local-raster" },
        [ordered]@{ name = "debug.rustyquest.makepad.projection.border.policy"; value = "solid-red" },
        [ordered]@{ name = "debug.rustyquest.makepad.direct.camera.hardware.buffer.external"; value = "false" },
        [ordered]@{ name = "debug.rustyquest.makepad.native.passthrough.enabled"; value = "false" }
    )
    $readbacks = @()
    foreach ($property in $properties) {
        $readbacks += Set-DeviceProperty -Serial $Serial -Name $property.name -Value $property.value
    }
    return $readbacks
}

function Start-Qcl041Relay {
    param(
        [string]$Serial,
        [string]$Role,
        [string]$ReceiverHost,
        [string]$LeaseId,
        [string]$LogName,
        [bool]$RelayEnabled = $true
    )
    $laneSpec = "left:127.0.0.1:${LeftSourcePort}:${ReceiverHost}:${LeftTransportPort};right:127.0.0.1:${RightSourcePort}:${ReceiverHost}:${RightTransportPort}"
    $laneSpecForShell = $laneSpec.Replace(";", "\;")
    $intentArgs = @(
        "-s", $Serial,
        "shell", "am", "start-foreground-service",
        "-n", "io.github.mesmerprism.rustyquest.qcl041/.Qcl041WifiDirectHarnessService",
        "--es", "qcl041.run_id", $RunId,
        "--es", "qcl041.device_serial", $Serial,
        "--es", "qcl041.device_model", "Quest_3S",
        "--es", "qcl041.lease_id", $LeaseId,
        "--es", "qcl041.lease_resource", "quest:$Serial",
        "--ez", "qcl041.lease_reserved_before_live_steps", "true",
        "--es", "qcl041.peer_class", "quest",
        "--es", "qcl041.q2q_role", $Role,
        "--es", "qcl041.peer_name_contains", "Quest",
        "--es", "qcl041.host_toolchain_profile", "qcl099_quest_to_quest_stereo_projection_wifi_direct",
        "--ei", "qcl041.timeout_seconds", "85",
        "--ei", "qcl041.socket_timeout_seconds", "30",
        "--ei", "qcl041.hold_after_socket_ms", $HoldAfterSocketMs.ToString(),
        "--ez", "qcl041.qcl082_relay_enabled", $RelayEnabled.ToString().ToLowerInvariant(),
        "--ez", "qcl041.qcl082_media_path_before_socket_probe", $RelayEnabled.ToString().ToLowerInvariant(),
        "--es", "qcl041.qcl082_relay_source_host", "127.0.0.1",
        "--ei", "qcl041.qcl082_relay_source_port", $LeftSourcePort.ToString(),
        "--es", "qcl041.qcl082_relay_receiver_host", $ReceiverHost,
        "--ei", "qcl041.qcl082_relay_receiver_port", $LeftTransportPort.ToString(),
        "--es", "qcl041.qcl082_relay_lanes", $laneSpecForShell,
        "--ei", "qcl041.qcl082_relay_timeout_seconds", $RelayTimeoutSeconds.ToString(),
        "--ei", "qcl041.qcl082_relay_max_bytes", $RelayMaxBytes.ToString(),
        "--ei", "qcl041.qcl082_relay_start_delay_ms", "1000"
    )
    Invoke-External `
        -Name "QCL041 stereo relay launch $Serial" `
        -File $Adb `
        -Arguments $intentArgs `
        -LogPath (Join-Path $MediaDir $LogName) | Out-Null
}

function Read-Qcl041Artifact {
    param([string]$Serial, [string]$OutPath, [int]$TimeoutSeconds = 30)
    $started = Get-Date
    $attempt = 0
    $lastError = ""
    if ($TimeoutSeconds -le 0) {
        $TimeoutSeconds = 1
    }
    while (((Get-Date) - $started).TotalSeconds -lt $TimeoutSeconds) {
        $attempt++
        $readTimeoutSeconds = [Math]::Min(5, [Math]::Max(1, [int][Math]::Ceiling($TimeoutSeconds)))
        $read = Read-RustyQuestAdbAppFile `
            -AdbPath $Adb `
            -Serial $Serial `
            -Package "io.github.mesmerprism.rustyquest.qcl041" `
            -DevicePath "files/qcl041/$RunId.json" `
            -TimeoutSeconds $readTimeoutSeconds
        if ([bool]$read.timed_out) {
            $lastError = "qcl041_artifact_adb_read_timeout after $($read.timeout_seconds)s"
        } elseif (-not [string]::IsNullOrWhiteSpace([string]$read.stdout)) {
            $saved = Save-RustyQuestJsonArtifactFromStdout `
                -Content ([string]$read.stdout) `
                -OutPath $OutPath `
                -Label "qcl099-qcl041-artifact" `
                -ExitCode $read.exit_code
            if ([bool]$saved.saved) {
                return
            }
            $lastError = "qcl041_artifact_stdout_json_parse_failed exit_code=$($read.exit_code): $($saved.error)"
        } else {
            $lastError = if (-not [string]::IsNullOrWhiteSpace([string]$read.stderr)) { [string]$read.stderr } else { [string]$read.stdout }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Could not read QCL041 artifact from $Serial after $attempt attempts and ${TimeoutSeconds}s (qcl041_artifact_wait_timeout). $lastError"
}

function Get-Qcl099DirectP2pAddressRefresh {
    param(
        [string]$OwnerOutPath,
        [string]$ClientOutPath,
        [int]$TimeoutSeconds = 45
    )
    $started = Get-Date
    $lastError = ""
    $ownerObserved = ""
    $clientObserved = ""
    $clientAcceptedByOwner = ""
    $clientSocketLocal = ""
    $ownerGroupOwnerAddressFromClient = ""
    $clientGroupOwnerAddressFromOwner = ""
    $attempt = 0
    while (((Get-Date) - $started).TotalSeconds -lt $TimeoutSeconds) {
        $attempt++
        try {
            Read-Qcl041Artifact -Serial $OwnerSerial -OutPath $OwnerOutPath -TimeoutSeconds 2
            Read-Qcl041Artifact -Serial $ClientSerial -OutPath $ClientOutPath -TimeoutSeconds 2
            $ownerArtifact = Get-Content -Raw -LiteralPath $OwnerOutPath | ConvertFrom-Json
            $clientArtifact = Get-Content -Raw -LiteralPath $ClientOutPath | ConvertFrom-Json
            $ownerObserved = Get-RustyQuestQcl041WifiDirectLocalAddress -Artifact $ownerArtifact
            $clientObserved = Get-RustyQuestQcl041WifiDirectLocalAddress -Artifact $clientArtifact
            $clientAcceptedByOwner = Get-RustyQuestQcl041WifiDirectAcceptedPeerAddress -Artifact $ownerArtifact
            $clientSocketLocal = Get-RustyQuestQcl041WifiDirectSocketLocalAddress -Artifact $clientArtifact
            $ownerGroupOwnerAddressFromClient = Get-RustyQuestQcl041WifiDirectGroupOwnerAddress -Artifact $clientArtifact
            $clientGroupOwnerAddressFromOwner = Get-RustyQuestQcl041WifiDirectGroupOwnerAddress -Artifact $ownerArtifact
            if ([string]::IsNullOrWhiteSpace($ownerObserved) -and
                -not [string]::IsNullOrWhiteSpace($ownerGroupOwnerAddressFromClient) -and
                $ownerGroupOwnerAddressFromClient -ne $clientObserved) {
                $ownerObserved = $ownerGroupOwnerAddressFromClient
            }
            if ([string]::IsNullOrWhiteSpace($clientObserved)) {
                if (-not [string]::IsNullOrWhiteSpace($clientSocketLocal)) {
                    $clientObserved = $clientSocketLocal
                } elseif (-not [string]::IsNullOrWhiteSpace($clientAcceptedByOwner)) {
                    $clientObserved = $clientAcceptedByOwner
                } elseif (-not [string]::IsNullOrWhiteSpace($clientGroupOwnerAddressFromOwner) -and
                    $clientGroupOwnerAddressFromOwner -ne $ownerObserved) {
                    $clientObserved = $clientGroupOwnerAddressFromOwner
                }
            }
            if (-not [string]::IsNullOrWhiteSpace($ownerObserved) -and
                -not [string]::IsNullOrWhiteSpace($clientObserved)) {
                break
            }
            $lastError = "owner='$ownerObserved' client='$clientObserved' acceptedByOwner='$clientAcceptedByOwner' clientSocket='$clientSocketLocal' ownerGroupOwnerFromClient='$ownerGroupOwnerAddressFromClient' clientGroupOwnerFromOwner='$clientGroupOwnerAddressFromOwner'"
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 500
    }
    $summary = Get-RustyQuestDirectP2pAddressRefreshSummary `
        -OwnerRequestedAddress $OwnerWifiDirectAddress `
        -ClientRequestedAddress $ClientWifiDirectAddress `
        -OwnerObservedAddress $ownerObserved `
        -ClientObservedAddress $clientObserved
    $summary["attempt_count"] = $attempt
    $summary["timeout_seconds"] = $TimeoutSeconds
    $summary["client_accepted_by_owner"] = $clientAcceptedByOwner
    $summary["client_socket_local_address"] = $clientSocketLocal
    $summary["owner_group_owner_address_from_client"] = $ownerGroupOwnerAddressFromClient
    $summary["client_group_owner_address_from_owner"] = $clientGroupOwnerAddressFromOwner
    $summary["last_error"] = $lastError
    return $summary
}

function Start-MakepadProjection {
    param([string]$Serial, [string]$LogName)
    Invoke-External `
        -Name "Makepad XR launch $Serial" `
        -File $Adb `
        -Arguments @(
            "-s", $Serial,
            "shell", "am", "start", "-W",
            "-a", "android.intent.action.MAIN",
            "-c", "com.oculus.intent.category.VR",
            "-n", "io.github.mesmerprism.rustyquest.makepad.camera/.MakepadAppXr"
        ) `
        -LogPath (Join-Path $MediaDir $LogName) | Out-Null
}

function Prepare-QuestForXrFocus {
    param([string]$Serial, [string]$Label)
    if ($SkipWakePrep) {
        return [ordered]@{
            skipped = $true
            serial = $Serial
            label = $Label
        }
    }

    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "input", "keyevent", "224")
    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "svc", "power", "stayon", "true")
    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "settings", "put", "system", "screen_off_timeout", "2147483647")
    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "settings", "put", "secure", "sleep_timeout", "-1")

    Start-Sleep -Milliseconds 500
    $power = Read-AdbText -Serial $Serial -Arguments @("shell", "dumpsys", "power") -Path (Join-Path $MediaDir "$Label-power-after-wake-prep.txt")
    $display = Read-AdbText -Serial $Serial -Arguments @("shell", "dumpsys", "display") -Path (Join-Path $MediaDir "$Label-display-after-wake-prep.txt")
    $mounted = (Read-AdbText -Serial $Serial -Arguments @("shell", "getprop", "sys.hmt.mounted")).Trim()
    [ordered]@{
        skipped = $false
        serial = $Serial
        label = $Label
        sys_hmt_mounted = $mounted
        wakefulness_awake = [bool]($power -match "mWakefulness=Awake")
        display_on = [bool]($display -match "mScreenState=ON|state=ON|Display Power: state=ON")
        stay_on_applied = $true
    }
}

function Get-MakepadFocusSnapshot {
    param([string]$Serial, [string]$Label, [string]$Suffix)
    $activityPath = Join-Path $MediaDir "$Label-focus-$Suffix-activity.txt"
    $windowPath = Join-Path $MediaDir "$Label-focus-$Suffix-window.txt"
    $activity = Read-AdbText -Serial $Serial -Arguments @("shell", "dumpsys", "activity", "activities") -Path $activityPath
    $window = Read-AdbText -Serial $Serial -Arguments @("shell", "dumpsys", "window", "windows") -Path $windowPath
    $focusActive = ($activity -like "*io.github.mesmerprism.rustyquest.makepad.camera*" -and $activity -like "*MakepadAppXr*") -or
        ($window -like "*io.github.mesmerprism.rustyquest.makepad.camera*" -and $window -like "*MakepadAppXr*")
    [ordered]@{
        label = $Label
        suffix = $Suffix
        focus_active = [bool]$focusActive
        activity_path = $activityPath
        window_path = $windowPath
    }
}

function Wait-MakepadXrCadence {
    param([string]$Serial, [string]$Label)
    $attempts = @()
    for ($attempt = 1; $attempt -le $XrFocusLaunchAttempts; $attempt++) {
        Start-MakepadProjection -Serial $Serial -LogName "$Label-makepad-launch-attempt-$attempt.txt"
        $deadline = (Get-Date).AddSeconds($XrFocusWaitSeconds)
        do {
            Start-Sleep -Seconds 2
            $suffix = "attempt-$attempt-" + (Get-Date -Format "HHmmss")
            $logPath = Join-Path $MediaDir "$Label-makepad-readiness-$suffix.logcat.txt"
            Invoke-External -Name "$Label makepad readiness logcat" -File $Adb -Arguments @("-s", $Serial, "logcat", "-d", "-v", "threadtime") -LogPath $logPath | Out-Null
            $makepad = Summarize-MakepadLog -LogPath $logPath -ExpectedDecodeOutputMode $MakepadDecodeOutputMode
            $focus = Get-MakepadFocusSnapshot -Serial $Serial -Label $Label -Suffix $suffix
            $ready = [bool]($focus.focus_active -and $makepad.frame_flow_ready)
            $attempts += [ordered]@{
                attempt = $attempt
                suffix = $suffix
                ready = $ready
                focus_active = $focus.focus_active
                frame_flow_ready = $makepad.frame_flow_ready
                visible_camera_projection_ready = $makepad.visible_camera_projection_ready
                left_stream_header_ok = $makepad.left_stream_header_ok
                right_stream_header_ok = $makepad.right_stream_header_ok
                left_hardware_buffer_frames = $makepad.left_hardware_buffer_frames
                right_hardware_buffer_frames = $makepad.right_hardware_buffer_frames
                log_path = $logPath
                activity_path = $focus.activity_path
                window_path = $focus.window_path
                last_cadence = $makepad.last_cadence
            }
            if ($ready) {
                return [ordered]@{
                    serial = $Serial
                    label = $Label
                    ready = $true
                    attempts = $attempts
                }
            }
        } while ((Get-Date) -lt $deadline)

        if ($attempt -lt $XrFocusLaunchAttempts) {
            Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "input", "keyevent", "224")
            Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustyquest.makepad.camera")
            Start-Sleep -Seconds 1
        }
    }

    return [ordered]@{
        serial = $Serial
        label = $Label
        ready = $false
        attempts = $attempts
    }
}

function Count-LinesContaining {
    param([string[]]$Lines, [string]$Needle)
    return @($Lines | Where-Object { $_ -like "*$Needle*" }).Count
}

function Count-LinesContainingAll {
    param([string[]]$Lines, [string[]]$Needles)
    return @($Lines | Where-Object {
        $line = $_
        foreach ($needle in $Needles) {
            if ($line -notlike "*$needle*") {
                return $false
            }
        }
        return $true
    }).Count
}

function Select-LastLineContaining {
    param([string[]]$Lines, [string]$Needle)
    $matches = @($Lines | Where-Object { $_ -like "*$Needle*" })
    if ($matches.Count -eq 0) {
        return $null
    }
    return [string]$matches[-1]
}

function Select-LastLineContainingAll {
    param([string[]]$Lines, [string[]]$Needles)
    $matches = @($Lines | Where-Object {
        $line = $_
        foreach ($needle in $Needles) {
            if ($line -notlike "*$needle*") {
                return $false
            }
        }
        return $true
    })
    if ($matches.Count -eq 0) {
        return $null
    }
    return [string]$matches[-1]
}

function Get-MarkerValue {
    param([string]$Line, [string]$Key)
    if ([string]::IsNullOrWhiteSpace($Line)) {
        return $null
    }
    $match = [regex]::Match($Line, "(^|\s)$([regex]::Escape($Key))=([^\s]+)")
    if (-not $match.Success) {
        return $null
    }
    return $match.Groups[2].Value
}

function ConvertTo-IntSafe {
    param($Value)
    try {
        return [int]$Value
    } catch {
        return 0
    }
}

function Summarize-MakepadLog {
    param(
        [string]$LogPath,
        [string]$ExpectedDecodeOutputMode = $MakepadDecodeOutputMode
    )
    $route = Get-MakepadDecodeRouteMetadata -Mode $ExpectedDecodeOutputMode
    $lines = @(Get-Content -LiteralPath $LogPath -ErrorAction SilentlyContinue)
    $lastCadence = Select-LastLineContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_CADENCE schema=rusty.quest.makepad-cadence.v1 phase=sample"
    $lastTextureMetadata = Select-LastLineContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_TEXTURE_METADATA schema=rusty.quest.makepad-texture-metadata.v1"
    $lastHardwareBufferStart = Select-LastLineContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_HARDWARE_BUFFER_IMPORT schema=rusty.quest.makepad-hardware-buffer-import.v1 phase=start"
    $lastVideoTextureGate = Select-LastLineContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_VIDEO_TEXTURE_GATE schema=rusty.quest.makepad-video-texture-gate.v1"
    $lastFrameFlow = Select-LastLineContaining -Lines $lines -Needle "phase=xr-end-frame status=submitted"
    $leftHeader = Select-LastLineContaining -Lines $lines -Needle "phase=stream-header-metadata status=ok side=left"
    $rightHeader = Select-LastLineContaining -Lines $lines -Needle "phase=stream-header-metadata status=ok side=right"
    $streamHeaderDerivedSampling = Select-LastLineContainingAll -Lines $lines -Needles @(
        "phase=source-sampling status=ok",
        "orientationMetadataSource=stream-header",
        "leftContentGeometryMetadataSource=stream-header",
        "rightContentGeometryMetadataSource=stream-header",
        "leftContentMappingIntent=map-full-frame-content-to-projection-area",
        "rightContentMappingIntent=map-full-frame-content-to-projection-area"
    )
    $streamHeaderDerivedProjection = Select-LastLineContainingAll -Lines $lines -Needles @(
        "phase=visible-panel-bound status=ok",
        "sourceBindingMode=broker-h264-stream-header-full-frame-diagnostic",
        "projectionGeometryProfile=full-frame-diagnostic",
        "sourceRasterOriginPolicy=explicit-broker-raster-or-camera2-import"
    )
    $leftProgress = Select-LastLineContainingAll -Lines $lines -Needles @("External H.264 playback progress", "streamPort=$LeftReceiverPort")
    $rightProgress = Select-LastLineContainingAll -Lines $lines -Needles @("External H.264 playback progress", "streamPort=$RightReceiverPort")
    $fatal = @($lines | Where-Object { $_ -match "FATAL EXCEPTION|Fatal signal|\bGPU page fault\b|\bANR\b|\s[FE]\s+.*\bSIG(SEGV|ABRT)\b" })
    $leftDelta = ConvertTo-IntSafe (Get-MarkerValue -Line $lastCadence -Key "leftTextureUpdateDelta")
    $rightDelta = ConvertTo-IntSafe (Get-MarkerValue -Line $lastCadence -Key "rightTextureUpdateDelta")
    $pairedDelta = ConvertTo-IntSafe (Get-MarkerValue -Line $lastCadence -Key "pairedTextureUpdateDelta")
    $texturePath = Get-MarkerValue -Line $lastCadence -Key "cameraTexturePath"
    if ([string]::IsNullOrWhiteSpace($texturePath)) {
        $texturePath = Get-MarkerValue -Line $lastTextureMetadata -Key "cameraTexturePath"
    }
    $leftHwb = ConvertTo-IntSafe (Get-MarkerValue -Line $leftProgress -Key "hardwareBufferFrameEmitCount")
    $rightHwb = ConvertTo-IntSafe (Get-MarkerValue -Line $rightProgress -Key "hardwareBufferFrameEmitCount")
    $leftDecoded = ConvertTo-IntSafe (Get-MarkerValue -Line $leftProgress -Key "decodedFrameCount")
    $rightDecoded = ConvertTo-IntSafe (Get-MarkerValue -Line $rightProgress -Key "decodedFrameCount")
    $leftYuv = ConvertTo-IntSafe (Get-MarkerValue -Line $leftProgress -Key "yuvFrameEmitCount")
    $rightYuv = ConvertTo-IntSafe (Get-MarkerValue -Line $rightProgress -Key "yuvFrameEmitCount")
    $visibleReady = Get-MarkerValue -Line $lastCadence -Key "visibleCameraProjectionReady"
    $mappingReady = Get-MarkerValue -Line $lastCadence -Key "projectionMappingReady"
    $vulkanImport = Get-MarkerValue -Line $lastCadence -Key "makepadVulkanImport"
    $pairedFrames = Get-MarkerValue -Line $lastCadence -Key "pairedLeftRightCameraFrames"
    $streamHeaderDerivedGateReady = [bool]($streamHeaderDerivedSampling -and $streamHeaderDerivedProjection)
    $streamHeaderGateReady = [bool](($leftHeader -and $rightHeader) -or $streamHeaderDerivedGateReady)
    $projectionMappingGateReady = [bool]($mappingReady -eq "true")
    $frameFlowReady = [bool]($lastFrameFlow -like "*shouldRender=true*" -and $lastFrameFlow -like "*resultCode=0*")
    $expectedTexturePath = [string]$route.texture_path
    $expectedVulkanImport = [string]$route.makepad_vulkan_import
    $leftRouteFrameReady = if ($route.requires_hardware_buffer) { $leftHwb -gt 0 -or $leftDelta -gt 0 } else { $leftDelta -gt 0 }
    $rightRouteFrameReady = if ($route.requires_hardware_buffer) { $rightHwb -gt 0 -or $rightDelta -gt 0 } else { $rightDelta -gt 0 }
    $pairedRouteFrameReady = if ($route.requires_hardware_buffer) {
        $pairedDelta -gt 0 -or ($leftHwb -gt 0 -and $rightHwb -gt 0)
    } else {
        $pairedDelta -gt 0
    }
    $expectedRouteReady = [bool](
        $texturePath -eq $expectedTexturePath -and
        $vulkanImport -eq $expectedVulkanImport
    )
    $decodeRouteReady = [bool]($expectedRouteReady -and $leftRouteFrameReady -and $rightRouteFrameReady -and $pairedRouteFrameReady)
    $routeFailureHint = "none"
    if (-not $frameFlowReady) {
        $routeFailureHint = "no_active_xr_frame_flow"
    } elseif ($texturePath -ne $expectedTexturePath) {
        $routeFailureHint = "unexpected_texture_path"
    } elseif ($vulkanImport -ne $expectedVulkanImport) {
        $routeFailureHint = "unexpected_makepad_vulkan_import"
    } elseif (-not $leftRouteFrameReady -or -not $rightRouteFrameReady -or -not $pairedRouteFrameReady) {
        $routeFailureHint = "decode_texture_counters_not_advancing"
    } elseif (-not $streamHeaderGateReady) {
        $routeFailureHint = "stream_headers_missing"
    } elseif ($mappingReady -ne "true" -or $visibleReady -ne "true") {
        $routeFailureHint = "projection_mapping_or_visible_panel_not_ready"
    } elseif ($fatal.Count -ne 0) {
        $routeFailureHint = "native_or_system_fatal_present"
    }
    $mediaProjectionReady = [bool](
        $decodeRouteReady -and
        $visibleReady -eq "true" -and
        $pairedFrames -eq "true" -and
        $frameFlowReady -and
        $fatal.Count -eq 0
    )
    [ordered]@{
        log_path = $LogPath
        marker_counts = [ordered]@{
            hardware_buffer_import = Count-LinesContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_HARDWARE_BUFFER_IMPORT"
            stream_header_ok = Count-LinesContaining -Lines $lines -Needle "phase=stream-header-metadata status=ok"
            stream_header_derived_sampling = Count-LinesContainingAll -Lines $lines -Needles @(
                "phase=source-sampling status=ok",
                "leftContentGeometryMetadataSource=stream-header",
                "rightContentGeometryMetadataSource=stream-header"
            )
            stream_header_derived_projection = Count-LinesContainingAll -Lines $lines -Needles @(
                "phase=visible-panel-bound status=ok",
                "sourceBindingMode=broker-h264-stream-header-full-frame-diagnostic"
            )
            texture_updated = Count-LinesContaining -Lines $lines -Needle "phase=texture-updated status=ok"
            cadence = Count-LinesContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_CADENCE"
            frame_adoption = Count-LinesContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_FRAME_ADOPTION"
            stereo_projection = Count-LinesContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_STEREO_PROJECTION"
            video_texture_gate = Count-LinesContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_VIDEO_TEXTURE_GATE"
            h264_progress = Count-LinesContaining -Lines $lines -Needle "External H.264 playback progress"
            makepad_frame_flow = Count-LinesContaining -Lines $lines -Needle "phase=xr-end-frame status=submitted"
        }
        expected_decode_output_mode = $ExpectedDecodeOutputMode
        expected_texture_path = $expectedTexturePath
        expected_makepad_vulkan_import = $expectedVulkanImport
        broker_h264_hardware_buffer_requested = [bool]($lastHardwareBufferStart -like "*broker-h264-stereo-mediacodec-hardware-buffer*")
        texture_path = $texturePath
        makepad_vulkan_import = $vulkanImport
        left_stream_header_ok = [bool]($null -ne $leftHeader)
        right_stream_header_ok = [bool]($null -ne $rightHeader)
        left_hardware_buffer_frames = $leftHwb
        right_hardware_buffer_frames = $rightHwb
        left_decoded_frames = $leftDecoded
        right_decoded_frames = $rightDecoded
        left_yuv_frames = $leftYuv
        right_yuv_frames = $rightYuv
        left_texture_update_delta = $leftDelta
        right_texture_update_delta = $rightDelta
        paired_texture_update_delta = $pairedDelta
        projection_mapping_ready = $mappingReady
        aligned_projection = Get-MarkerValue -Line $lastCadence -Key "alignedProjection"
        paired_left_right_camera_frames = $pairedFrames
        visible_camera_projection_ready = $visibleReady
        video_texture_gate_ready = (Get-MarkerValue -Line $lastVideoTextureGate -Key "textureGateReady") -eq "true"
        frame_flow_ready = $frameFlowReady
        decode_output_route_ready = $decodeRouteReady
        media_projection_ready = $mediaProjectionReady
        stream_header_gate_ready = $streamHeaderGateReady
        stream_header_gate_source = if ($leftHeader -and $rightHeader) { "stream-header-metadata-side-lines" } elseif ($streamHeaderDerivedGateReady) { "source-sampling-visible-panel-bound" } else { "none" }
        stream_header_derived_sampling_ok = [bool]($null -ne $streamHeaderDerivedSampling)
        stream_header_derived_projection_ok = [bool]($null -ne $streamHeaderDerivedProjection)
        projection_mapping_gate_ready = $projectionMappingGateReady
        route_failure_hint = $routeFailureHint
        fatal_count = $fatal.Count
        fatal_lines = @($fatal | Select-Object -First 20)
        last_hardware_buffer_start = $lastHardwareBufferStart
        last_frame_flow = $lastFrameFlow
        last_stream_header_derived_sampling = $streamHeaderDerivedSampling
        last_stream_header_derived_projection = $streamHeaderDerivedProjection
        last_cadence = $lastCadence
        last_texture_metadata = $lastTextureMetadata
        left_progress = $leftProgress
        right_progress = $rightProgress
        projection_ready = [bool](
            $streamHeaderGateReady -and
            $projectionMappingGateReady -and
            $mediaProjectionReady
        )
    }
}

function Summarize-BrokerRuntime {
    param($Runtime)
    if ($null -eq $Runtime) {
        return $null
    }
    $sourceRuntime = $Runtime.sender_source_runtime
    [ordered]@{
        schema = $Runtime.schema
        session_id = $Runtime.session_id
        active_count = $Runtime.active_count
        created_count = $Runtime.created_count
        failed_count = $Runtime.failed_count
        matched_count = $Runtime.matched_count
        lanes = @($Runtime.lanes | ForEach-Object {
            [ordered]@{
                role = $_.role
                eye = $_.eye
                port = $_.port
                transport_port = $_.transport_port
                state = $_.state
                bytes_received = $_.bytes_received
                bytes_sent = $_.bytes_sent
                copy_bytes_read = $_.copy_bytes_read
                copy_bytes_written = $_.copy_bytes_written
                copy_last_read_age_ms = $_.copy_last_read_age_ms
                copy_last_write_age_ms = $_.copy_last_write_age_ms
                transport_accept_count = $_.transport_accept_count
                local_client_accept_count = $_.local_client_accept_count
                peer_route = $_.peer_route
                peer_socket_authority = $_.peer_socket_authority
                peer_socket_bound_local_address = $_.peer_socket_bound_local_address
                peer_socket_local_interface = $_.peer_socket_local_interface
                peer_socket_network_selection = $_.peer_socket_network_selection
                peer_socket_wifi_direct_bind_required = $_.peer_socket_wifi_direct_bind_required
                peer_socket_wifi_direct_bind_attempts = $_.peer_socket_wifi_direct_bind_attempts
                peer_socket_local_address_same_subnet = $_.peer_socket_local_address_same_subnet
            }
        })
        sender_source_runtime = if ($null -eq $sourceRuntime) {
            $null
        } else {
            [ordered]@{
                source_count = $sourceRuntime.source_count
                sources = @($sourceRuntime.sources | ForEach-Object {
                    [ordered]@{
                        source_kind = $_.source_kind
                        state = $_.state
                        connected_output_count = $_.connected_output_count
                        camera_id = $_.camera_selection.camera_id
                        eye = if ($_.source_lanes -and $_.source_lanes.Count -gt 0) { $_.source_lanes[0].eye } else { $null }
                        port = if ($_.source_lanes -and $_.source_lanes.Count -gt 0) { $_.source_lanes[0].port } else { $null }
                        lane_state = if ($_.source_lanes -and $_.source_lanes.Count -gt 0) { $_.source_lanes[0].state } else { $null }
                        connected = if ($_.source_lanes -and $_.source_lanes.Count -gt 0) { $_.source_lanes[0].connected } else { $null }
                        bytes_written = if ($_.source_lanes -and $_.source_lanes.Count -gt 0) { $_.source_lanes[0].bytes_written } else { $null }
                        packet_count = if ($_.source_lanes -and $_.source_lanes.Count -gt 0) { $_.source_lanes[0].packet_count } else { $null }
                        video_packet_count = if ($_.source_lanes -and $_.source_lanes.Count -gt 0) { $_.source_lanes[0].video_packet_count } else { $null }
                        codec_config_packet_count = if ($_.source_lanes -and $_.source_lanes.Count -gt 0) { $_.source_lanes[0].codec_config_packet_count } else { $null }
                    }
                })
            }
        }
    }
}

function ConvertTo-LongSafe {
    param($Value)
    if ($null -eq $Value) {
        return 0L
    }
    try {
        return [long]$Value
    } catch {
        return 0L
    }
}

function Get-Qcl099BrokerReceiverObservedFreshness {
    param($BrokerStatus)
    $receiverLanes = @()
    if ($null -ne $BrokerStatus -and $null -ne $BrokerStatus.lanes) {
        $receiverLanes = @($BrokerStatus.lanes | Where-Object { $_.role -eq "receiver" })
    }
    $records = @()
    foreach ($eye in @("left", "right")) {
        $lane = @($receiverLanes | Where-Object { $_.eye -eq $eye } | Select-Object -First 1)[0]
        $bytesReceived = ConvertTo-LongSafe $lane.bytes_received
        $copyBytesRead = ConvertTo-LongSafe $lane.copy_bytes_read
        $copyBytesWritten = ConvertTo-LongSafe $lane.copy_bytes_written
        $copyLastReadAgeMs = ConvertTo-LongSafe $lane.copy_last_read_age_ms
        $copyLastWriteAgeMs = ConvertTo-LongSafe $lane.copy_last_write_age_ms
        $readFresh = [bool]($null -ne $lane.copy_last_read_age_ms -and $copyLastReadAgeMs -ge 0L -and $copyLastReadAgeMs -le 5000L)
        $writeFresh = [bool]($null -ne $lane.copy_last_write_age_ms -and $copyLastWriteAgeMs -ge 0L -and $copyLastWriteAgeMs -le 5000L)
        $fresh = [bool]($bytesReceived -gt 0L -and $copyBytesRead -gt 0L -and $copyBytesWritten -gt 0L -and $readFresh -and $writeFresh)
        $records += [ordered]@{
            eye = $eye
            status = $lane.state
            bytes_received = $bytesReceived
            copy_bytes_read = $copyBytesRead
            copy_bytes_written = $copyBytesWritten
            copy_last_read_age_ms = if ($null -ne $lane.copy_last_read_age_ms) { $copyLastReadAgeMs } else { $null }
            copy_last_write_age_ms = if ($null -ne $lane.copy_last_write_age_ms) { $copyLastWriteAgeMs } else { $null }
            transport_accept_count = ConvertTo-LongSafe $lane.transport_accept_count
            local_client_accept_count = ConvertTo-LongSafe $lane.local_client_accept_count
            fresh_receiver_observed_bytes = $fresh
        }
    }
    $freshRecords = @($records | Where-Object { $_.fresh_receiver_observed_bytes })
    $observedRecords = @($records | Where-Object { (ConvertTo-LongSafe $_.bytes_received) -gt 0L })
    [ordered]@{
        required = "Makepad direct broker mode requires fresh receiver-observed RMANVID1 bytes on both stereo lanes"
        max_final_age_ms = 5000
        lane_count = $records.Count
        receiver_observed_lane_count = $observedRecords.Count
        fresh_receiver_observed_lane_count = $freshRecords.Count
        receiver_observed_byte_count = ($records | ForEach-Object { ConvertTo-LongSafe $_.bytes_received } | Measure-Object -Sum).Sum
        lanes = $records
        fresh = [bool]($records.Count -eq 2 -and $freshRecords.Count -eq 2)
    }
}

function Get-Qcl099DirectP2pSenderAuthority {
    param($BrokerStatus)
    $senderLanes = @()
    if ($null -ne $BrokerStatus -and $null -ne $BrokerStatus.lanes) {
        $senderLanes = @($BrokerStatus.lanes | Where-Object { $_.role -eq "sender" })
    }
    $records = @($senderLanes | ForEach-Object {
        [ordered]@{
            eye = $_.eye
            state = $_.state
            bytes_sent = ConvertTo-LongSafe $_.bytes_sent
            peer_route = $_.peer_route
            peer_socket_authority = $_.peer_socket_authority
            peer_socket_bound_local_address = $_.peer_socket_bound_local_address
            peer_socket_local_interface = $_.peer_socket_local_interface
            peer_socket_network_selection = $_.peer_socket_network_selection
            peer_socket_wifi_direct_bind_required = $_.peer_socket_wifi_direct_bind_required
            peer_socket_wifi_direct_bind_attempts = $_.peer_socket_wifi_direct_bind_attempts
            peer_socket_local_address_same_subnet = $_.peer_socket_local_address_same_subnet
        }
    })
    $acceptedRecords = @($records | Where-Object {
        $_.peer_socket_authority -eq "rusty_direct_p2p_socket_authority" -and
        -not [string]::IsNullOrWhiteSpace([string]$_.peer_socket_bound_local_address) -and
        (ConvertTo-LongSafe $_.bytes_sent) -gt 0L
    })
    [ordered]@{
        required = "sender lanes use rusty_direct_p2p_socket_authority with an explicit local p2p0 bind and positive media bytes"
        lane_count = $records.Count
        accepted_lane_count = $acceptedRecords.Count
        lanes = $records
        accepted = [bool]($records.Count -eq 2 -and $acceptedRecords.Count -eq 2)
    }
}

foreach ($path in @($HostessCtl, $Qcl041Apk, $BrokerApk, $MakepadApk)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required artifact not found: $path"
    }
}
Add-RunnerProgress -Phase "preflight_artifacts_passed" -Data ([ordered]@{
    hostess_ctl = $HostessCtl
    qcl041_apk = $Qcl041Apk
    broker_apk = $BrokerApk
    makepad_apk = $MakepadApk
})

foreach ($serial in @($OwnerSerial, $ClientSerial)) {
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustyquest.qcl041")
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustymanifold.broker")
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustyquest.makepad.camera")
    Invoke-AdbBestEffort -Serial $serial -Arguments @("forward", "--remove-all")
    if (-not $SkipInstall) {
        Invoke-AdbChecked -Serial $serial -Arguments @("install", "-r", $Qcl041Apk) -Name "install qcl041"
        Invoke-AdbChecked -Serial $serial -Arguments @("install", "-r", $BrokerApk) -Name "install broker"
        Invoke-AdbChecked -Serial $serial -Arguments @("install", "-r", $MakepadApk) -Name "install makepad"
    }
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "pm", "grant", "io.github.mesmerprism.rustyquest.qcl041", "android.permission.NEARBY_WIFI_DEVICES")
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "pm", "grant", "io.github.mesmerprism.rustymanifold.broker", "android.permission.CAMERA")
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "pm", "grant", "io.github.mesmerprism.rustymanifold.broker", "horizonos.permission.HEADSET_CAMERA")
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "pm", "grant", "io.github.mesmerprism.rustyquest.makepad.camera", "horizonos.permission.HEADSET_CAMERA")
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "logcat", "-c")
}
Add-RunnerProgress -Phase "device_preflight_done" -Data ([ordered]@{
    owner_serial = $OwnerSerial
    client_serial = $ClientSerial
    skip_install = [bool]$SkipInstall
})

$ownerWakePrep = Prepare-QuestForXrFocus -Serial $OwnerSerial -Label "owner"
$clientWakePrep = Prepare-QuestForXrFocus -Serial $ClientSerial -Label "client"
Add-RunnerProgress -Phase "wake_prep_done"

$ownerMakepadProps = Set-MakepadBrokerProjectionProperties -Serial $OwnerSerial
$clientMakepadProps = Set-MakepadBrokerProjectionProperties -Serial $ClientSerial
Add-RunnerProgress -Phase "makepad_properties_done"

function New-Qcl099ReceiverParams {
    param([string]$TransportBindHost = "0.0.0.0")
    return New-RustyQuestRemoteCameraReceiverParams `
        -SessionId $RunId `
        -ReceiverPorts "left:$LeftReceiverPort,right:$RightReceiverPort" `
        -TransportReceivePorts "left:$LeftTransportPort,right:$RightTransportPort" `
        -TransportBindHost $TransportBindHost
}

$receiverParams = New-Qcl099ReceiverParams
$directP2pAddressRefresh = $null
$ownerTransportRoutes = if ($TransportOwner -eq "broker") {
    Get-RustyQuestDirectP2pTransportRouteSpec `
        -PeerHost $ClientWifiDirectAddress `
        -LeftTransportPort $LeftTransportPort `
        -RightTransportPort $RightTransportPort `
        -LanePrefix "qcl099-makepad"
} else {
    "none"
}
$clientTransportRoutes = if ($TransportOwner -eq "broker") {
    Get-RustyQuestDirectP2pTransportRouteSpec `
        -PeerHost $OwnerWifiDirectAddress `
        -LeftTransportPort $LeftTransportPort `
        -RightTransportPort $RightTransportPort `
        -LanePrefix "qcl099-makepad"
} else {
    "none"
}
$ownerTransportBindLocalAddress = if ($TransportOwner -eq "broker") { $OwnerWifiDirectAddress } else { "" }
$clientTransportBindLocalAddress = if ($TransportOwner -eq "broker") { $ClientWifiDirectAddress } else { "" }
$ownerReceiverTransportBindHost = if ($TransportOwner -eq "broker") { $ownerTransportBindLocalAddress } else { "0.0.0.0" }
$clientReceiverTransportBindHost = if ($TransportOwner -eq "broker") { $clientTransportBindLocalAddress } else { "0.0.0.0" }
$directP2pAuthoritySummary = if ($TransportOwner -eq "broker") {
    New-RustyQuestDirectP2pAuthoritySummary `
        -OwnerWifiDirectAddress $OwnerWifiDirectAddress `
        -ClientWifiDirectAddress $ClientWifiDirectAddress `
        -OwnerTransportRoutes $ownerTransportRoutes `
        -ClientTransportRoutes $clientTransportRoutes `
        -OwnerTransportBindLocalAddress $ownerTransportBindLocalAddress `
        -ClientTransportBindLocalAddress $clientTransportBindLocalAddress `
        -OwnerReceiverTransportBindHost $ownerReceiverTransportBindHost `
        -ClientReceiverTransportBindHost $clientReceiverTransportBindHost
} else {
    $null
}
$ownerSenderParams = New-RustyQuestRemoteCameraSenderParams `
    -SessionId $RunId `
    -SenderSourcePorts "left:$LeftSourcePort,right:$RightSourcePort" `
    -MediaProfiles $MediaProfiles `
    -CameraIds $CameraIds `
    -QualityProfile "qcl099-makepad-stereo-direct-wifi" `
    -TransportRoutes $ownerTransportRoutes `
    -TransportBindLocalAddress $ownerTransportBindLocalAddress
$clientSenderParams = New-RustyQuestRemoteCameraSenderParams `
    -SessionId $RunId `
    -SenderSourcePorts "left:$LeftSourcePort,right:$RightSourcePort" `
    -MediaProfiles $MediaProfiles `
    -CameraIds $CameraIds `
    -QualityProfile "qcl099-makepad-stereo-direct-wifi" `
    -TransportRoutes $clientTransportRoutes `
    -TransportBindLocalAddress $clientTransportBindLocalAddress

$ownerRecv = New-BridgeRequest "owner-start-receiver" "command.remote_camera.start_receiver" $receiverParams "request.qcl099.$RunId.owner.receiver" "evidence.qcl099.$RunId.owner.receiver"
$clientRecv = New-BridgeRequest "client-start-receiver" "command.remote_camera.start_receiver" $receiverParams "request.qcl099.$RunId.client.receiver" "evidence.qcl099.$RunId.client.receiver"
$ownerSender = New-BridgeRequest "owner-start-source-only" "command.remote_camera.start_sender" $ownerSenderParams "request.qcl099.$RunId.owner.source_only" "evidence.qcl099.$RunId.owner.source_only"
$clientSender = New-BridgeRequest "client-start-source-only" "command.remote_camera.start_sender" $clientSenderParams "request.qcl099.$RunId.client.source_only" "evidence.qcl099.$RunId.client.source_only"
Add-RunnerProgress -Phase "bridge_requests_emitted"

if ($TransportOwner -ne "broker") {
    $ownerReceiverStartProbe = Invoke-LiveBridgeCommand "owner-start-receiver" $OwnerSerial $OwnerBrokerLocalPort $ownerRecv
    Assert-Qcl099ReceiverReady -Label "owner" -BridgeResult $ownerReceiverStartProbe
    $clientReceiverStartProbe = Invoke-LiveBridgeCommand "client-start-receiver" $ClientSerial $ClientBrokerLocalPort $clientRecv
    Assert-Qcl099ReceiverReady -Label "client" -BridgeResult $clientReceiverStartProbe
}
if ($TransportOwner -eq "qcl041") {
    Invoke-LiveBridgeCommand "owner-start-source-only" $OwnerSerial $OwnerBrokerLocalPort $ownerSender
    Invoke-LiveBridgeCommand "client-start-source-only" $ClientSerial $ClientBrokerLocalPort $clientSender
}
Add-RunnerProgress -Phase "bridge_commands_started"

$qcl041RelayEnabled = [bool]($TransportOwner -eq "qcl041")
Start-Qcl041Relay $OwnerSerial "group_owner" $ClientWifiDirectAddress $OwnerLeaseId "owner-qcl041-launch.txt" -RelayEnabled:$qcl041RelayEnabled
Start-Sleep -Seconds 2
Start-Qcl041Relay $ClientSerial "client" $OwnerWifiDirectAddress $ClientLeaseId "client-qcl041-launch.txt" -RelayEnabled:$qcl041RelayEnabled
Add-RunnerProgress -Phase "qcl041_relays_started"

Start-Sleep -Seconds 8
if ($TransportOwner -eq "broker") {
    $ownerAddressRefreshPath = Join-Path $OutDir "owner-qcl041-address-refresh.json"
    $clientAddressRefreshPath = Join-Path $OutDir "client-qcl041-address-refresh.json"
    $directP2pAddressRefresh = Get-Qcl099DirectP2pAddressRefresh `
        -OwnerOutPath $ownerAddressRefreshPath `
        -ClientOutPath $clientAddressRefreshPath `
        -TimeoutSeconds 45
    Assert-RustyQuestDirectP2pAddressRefreshReady -Summary $directP2pAddressRefresh -Label "QCL099 broker media"
    $effectiveOwnerWifiDirectAddress = [string]$directP2pAddressRefresh.owner_effective_wifi_direct_address
    $effectiveClientWifiDirectAddress = [string]$directP2pAddressRefresh.client_effective_wifi_direct_address
    $ownerTransportRoutes = Get-RustyQuestDirectP2pTransportRouteSpec `
        -PeerHost $effectiveClientWifiDirectAddress `
        -LeftTransportPort $LeftTransportPort `
        -RightTransportPort $RightTransportPort `
        -LanePrefix "qcl099-makepad"
    $clientTransportRoutes = Get-RustyQuestDirectP2pTransportRouteSpec `
        -PeerHost $effectiveOwnerWifiDirectAddress `
        -LeftTransportPort $LeftTransportPort `
        -RightTransportPort $RightTransportPort `
        -LanePrefix "qcl099-makepad"
    $ownerTransportBindLocalAddress = $effectiveOwnerWifiDirectAddress
    $clientTransportBindLocalAddress = $effectiveClientWifiDirectAddress
    $ownerReceiverTransportBindHost = $effectiveOwnerWifiDirectAddress
    $clientReceiverTransportBindHost = $effectiveClientWifiDirectAddress
    $directP2pAuthoritySummary = New-RustyQuestDirectP2pAuthoritySummary `
        -OwnerWifiDirectAddress $effectiveOwnerWifiDirectAddress `
        -ClientWifiDirectAddress $effectiveClientWifiDirectAddress `
        -OwnerTransportRoutes $ownerTransportRoutes `
        -ClientTransportRoutes $clientTransportRoutes `
        -OwnerTransportBindLocalAddress $ownerTransportBindLocalAddress `
        -ClientTransportBindLocalAddress $clientTransportBindLocalAddress `
        -OwnerReceiverTransportBindHost $ownerReceiverTransportBindHost `
        -ClientReceiverTransportBindHost $clientReceiverTransportBindHost
    $ownerReceiverParams = New-Qcl099ReceiverParams -TransportBindHost $ownerReceiverTransportBindHost
    $clientReceiverParams = New-Qcl099ReceiverParams -TransportBindHost $clientReceiverTransportBindHost
    $ownerRecv = New-BridgeRequest "owner-start-receiver" "command.remote_camera.start_receiver" $ownerReceiverParams "request.qcl099.$RunId.owner.receiver" "evidence.qcl099.$RunId.owner.receiver"
    $clientRecv = New-BridgeRequest "client-start-receiver" "command.remote_camera.start_receiver" $clientReceiverParams "request.qcl099.$RunId.client.receiver" "evidence.qcl099.$RunId.client.receiver"
    $ownerReceiverStartProbe = Invoke-LiveBridgeCommand "owner-start-receiver" $OwnerSerial $OwnerBrokerLocalPort $ownerRecv
    Assert-Qcl099ReceiverReady -Label "owner" -BridgeResult $ownerReceiverStartProbe
    $clientReceiverStartProbe = Invoke-LiveBridgeCommand "client-start-receiver" $ClientSerial $ClientBrokerLocalPort $clientRecv
    Assert-Qcl099ReceiverReady -Label "client" -BridgeResult $clientReceiverStartProbe
    $ownerSenderParams = New-RustyQuestRemoteCameraSenderParams `
        -SessionId $RunId `
        -SenderSourcePorts "left:$LeftSourcePort,right:$RightSourcePort" `
        -MediaProfiles $MediaProfiles `
        -CameraIds $CameraIds `
        -QualityProfile "qcl099-makepad-stereo-direct-wifi" `
        -TransportRoutes $ownerTransportRoutes `
        -TransportBindLocalAddress $ownerTransportBindLocalAddress
    $clientSenderParams = New-RustyQuestRemoteCameraSenderParams `
        -SessionId $RunId `
        -SenderSourcePorts "left:$LeftSourcePort,right:$RightSourcePort" `
        -MediaProfiles $MediaProfiles `
        -CameraIds $CameraIds `
        -QualityProfile "qcl099-makepad-stereo-direct-wifi" `
        -TransportRoutes $clientTransportRoutes `
        -TransportBindLocalAddress $clientTransportBindLocalAddress
    $ownerSender = New-BridgeRequest "owner-start-source-only" "command.remote_camera.start_sender" $ownerSenderParams "request.qcl099.$RunId.owner.source_only" "evidence.qcl099.$RunId.owner.source_only"
    $clientSender = New-BridgeRequest "client-start-source-only" "command.remote_camera.start_sender" $clientSenderParams "request.qcl099.$RunId.client.source_only" "evidence.qcl099.$RunId.client.source_only"
    Invoke-LiveBridgeCommand "owner-start-source-only" $OwnerSerial $OwnerBrokerLocalPort $ownerSender
    Invoke-LiveBridgeCommand "client-start-source-only" $ClientSerial $ClientBrokerLocalPort $clientSender
    Add-RunnerProgress -Phase "broker_direct_senders_started"
    Start-Sleep -Seconds 2
}
$ownerXrStart = Wait-MakepadXrCadence -Serial $OwnerSerial -Label "owner"
Add-RunnerProgress -Phase "owner_xr_readiness_done" -Data ([ordered]@{ ready = [bool]$ownerXrStart.ready })
$clientXrStart = Wait-MakepadXrCadence -Serial $ClientSerial -Label "client"
Add-RunnerProgress -Phase "client_xr_readiness_done" -Data ([ordered]@{ ready = [bool]$clientXrStart.ready })

Add-RunnerProgress -Phase "projection_window_start" -Data ([ordered]@{ projection_seconds = $ProjectionSeconds })
Start-Sleep -Seconds $ProjectionSeconds
Add-RunnerProgress -Phase "projection_window_done"

$statusParams = [ordered]@{ session_id = $RunId }
$ownerStatus = New-BridgeRequest "owner-final-status" "command.remote_camera.get_status" $statusParams "request.qcl099.$RunId.owner.final_status" "evidence.qcl099.$RunId.owner.final_status"
$clientStatus = New-BridgeRequest "client-final-status" "command.remote_camera.get_status" $statusParams "request.qcl099.$RunId.client.final_status" "evidence.qcl099.$RunId.client.final_status"
Invoke-LiveBridgeCommand "owner-final-status" $OwnerSerial $OwnerBrokerLocalPort $ownerStatus -NoLaunchBroker
Invoke-LiveBridgeCommand "client-final-status" $ClientSerial $ClientBrokerLocalPort $clientStatus -NoLaunchBroker
Add-RunnerProgress -Phase "bridge_status_done"

$ownerLog = Join-Path $OutDir "owner-makepad.logcat.txt"
$clientLog = Join-Path $OutDir "client-makepad.logcat.txt"
Invoke-External -Name "owner logcat" -File $Adb -Arguments @("-s", $OwnerSerial, "logcat", "-d", "-v", "threadtime") -LogPath $ownerLog | Out-Null
Invoke-External -Name "client logcat" -File $Adb -Arguments @("-s", $ClientSerial, "logcat", "-d", "-v", "threadtime") -LogPath $clientLog | Out-Null
$ownerFinalFocus = Get-MakepadFocusSnapshot -Serial $OwnerSerial -Label "owner" -Suffix "final"
$clientFinalFocus = Get-MakepadFocusSnapshot -Serial $ClientSerial -Label "client" -Suffix "final"

Read-Qcl041Artifact -Serial $OwnerSerial -OutPath (Join-Path $OutDir "owner-qcl041.json")
Read-Qcl041Artifact -Serial $ClientSerial -OutPath (Join-Path $OutDir "client-qcl041.json")
Add-RunnerProgress -Phase "final_artifacts_collected"

$ownerStop = New-BridgeRequest "owner-stop" "command.remote_camera.stop" $statusParams "request.qcl099.$RunId.owner.stop" "evidence.qcl099.$RunId.owner.stop"
$clientStop = New-BridgeRequest "client-stop" "command.remote_camera.stop" $statusParams "request.qcl099.$RunId.client.stop" "evidence.qcl099.$RunId.client.stop"
Invoke-LiveBridgeCommand "owner-stop" $OwnerSerial $OwnerBrokerLocalPort $ownerStop -NoLaunchBroker
Invoke-LiveBridgeCommand "client-stop" $ClientSerial $ClientBrokerLocalPort $clientStop -NoLaunchBroker
Add-RunnerProgress -Phase "bridge_stop_done"

Add-RunnerProgress -Phase "post_stop_summary_start"
$ownerQcl041 = Get-Content -Raw (Join-Path $OutDir "owner-qcl041.json") | ConvertFrom-Json
$clientQcl041 = Get-Content -Raw (Join-Path $OutDir "client-qcl041.json") | ConvertFrom-Json
$ownerStatusExecution = Get-Content -Raw (Join-Path $MediaDir "owner-final-status-execution.json") | ConvertFrom-Json
$clientStatusExecution = Get-Content -Raw (Join-Path $MediaDir "client-final-status-execution.json") | ConvertFrom-Json
$ownerMakepad = Summarize-MakepadLog -LogPath $ownerLog -ExpectedDecodeOutputMode $MakepadDecodeOutputMode
$clientMakepad = Summarize-MakepadLog -LogPath $clientLog -ExpectedDecodeOutputMode $MakepadDecodeOutputMode
$ownerBrokerStatus = Summarize-BrokerRuntime $ownerStatusExecution.command_execution.broker_messages[0].remote_camera_runtime
$clientBrokerStatus = Summarize-BrokerRuntime $clientStatusExecution.command_execution.broker_messages[0].remote_camera_runtime
$ownerBrokerReceiverObservedFreshness = Get-Qcl099BrokerReceiverObservedFreshness -BrokerStatus $ownerBrokerStatus
$clientBrokerReceiverObservedFreshness = Get-Qcl099BrokerReceiverObservedFreshness -BrokerStatus $clientBrokerStatus
$ownerDirectP2pSenderAuthority = Get-Qcl099DirectP2pSenderAuthority -BrokerStatus $ownerBrokerStatus
$clientDirectP2pSenderAuthority = Get-Qcl099DirectP2pSenderAuthority -BrokerStatus $clientBrokerStatus
Add-RunnerProgress -Phase "post_stop_summary_inputs_loaded" -Data ([ordered]@{
    owner_fatal_count = $ownerMakepad.fatal_count
    client_fatal_count = $clientMakepad.fatal_count
})

$summary = [ordered]@{
    schema = "rusty.quest.qcl099_quest_to_quest_stereo_projection_wifi_direct_run.v1"
    run_id = $RunId
    owner_serial = $OwnerSerial
    client_serial = $ClientSerial
    projection_seconds = $ProjectionSeconds
    transport_owner = $TransportOwner
    qcl100_lower_gate_authority = $Qcl100LowerGateAuthority
    media_profiles = $MediaProfiles
    camera_ids = $CameraIds
    topology = [ordered]@{
        transport = "quest_to_quest_wifi_direct"
        transport_owner = $TransportOwner
        relay = if ($TransportOwner -eq "qcl041") { "qcl041_multi_lane_rmanvid1" } else { "manifold_broker_direct_tcp_sender_bridge_after_qcl041_group_hold" }
        source_ports = "left:$LeftSourcePort,right:$RightSourcePort"
        receiver_ports = "left:$LeftReceiverPort,right:$RightReceiverPort"
        transport_receive_ports = "left:$LeftTransportPort,right:$RightTransportPort"
        makepad_source_mode = "existing-stream"
        makepad_decode_output_mode = $MakepadDecodeOutputMode
    }
    direct_p2p_address_refresh = $directP2pAddressRefresh
    direct_p2p_media_authority = $directP2pAuthoritySummary
    owner_relay = $ownerQcl041.diagnostics.qcl082_relay
    owner_relay_left = $ownerQcl041.diagnostics.qcl082_relay_left
    owner_relay_right = $ownerQcl041.diagnostics.qcl082_relay_right
    client_relay = $clientQcl041.diagnostics.qcl082_relay
    client_relay_left = $clientQcl041.diagnostics.qcl082_relay_left
    client_relay_right = $clientQcl041.diagnostics.qcl082_relay_right
    owner_broker_status = $ownerBrokerStatus
    client_broker_status = $clientBrokerStatus
    owner_broker_receiver_observed_freshness = $ownerBrokerReceiverObservedFreshness
    client_broker_receiver_observed_freshness = $clientBrokerReceiverObservedFreshness
    owner_direct_p2p_sender_authority = $ownerDirectP2pSenderAuthority
    client_direct_p2p_sender_authority = $clientDirectP2pSenderAuthority
    owner_makepad_projection = $ownerMakepad
    client_makepad_projection = $clientMakepad
    owner_wake_prep = $ownerWakePrep
    client_wake_prep = $clientWakePrep
    owner_xr_start_readiness = $ownerXrStart
    client_xr_start_readiness = $clientXrStart
    owner_final_focus = $ownerFinalFocus
    client_final_focus = $clientFinalFocus
    owner_makepad_property_readbacks = $ownerMakepadProps
    client_makepad_property_readbacks = $clientMakepadProps
    replicated_old_topology = [ordered]@{
        manifold_broker_camera2_stereo_sources = $true
        binary_media_magic = "RMANVID1"
        direct_wifi_instead_of_online_relay = $true
        makepad_existing_stream_consumer = $true
        decode_target = (Get-MakepadDecodeRouteMetadata -Mode $MakepadDecodeOutputMode).decode_target
        projection_target = "Makepad native XR custom stereo projection"
    }
    evidence_dir = $OutDir
}

$summary["owner_projection_ready"] = [bool]($ownerMakepad.projection_ready -and $ownerFinalFocus.focus_active)
$summary["client_projection_ready"] = [bool]($clientMakepad.projection_ready -and $clientFinalFocus.focus_active)
$summary["projection_ready_both_headsets"] = [bool]($summary["owner_projection_ready"] -and $summary["client_projection_ready"])
$summary["direct_p2p_receiver_observed_bytes_ready"] = [bool](
    $TransportOwner -eq "broker" -and
    $ownerBrokerReceiverObservedFreshness.receiver_observed_lane_count -eq 2 -and
    $clientBrokerReceiverObservedFreshness.receiver_observed_lane_count -eq 2 -and
    (ConvertTo-LongSafe $ownerBrokerReceiverObservedFreshness.receiver_observed_byte_count) -gt 0L -and
    (ConvertTo-LongSafe $clientBrokerReceiverObservedFreshness.receiver_observed_byte_count) -gt 0L
)
$summary["direct_p2p_receiver_final_status_fresh"] = [bool](
    $TransportOwner -eq "broker" -and
    $ownerBrokerReceiverObservedFreshness.fresh -and
    $clientBrokerReceiverObservedFreshness.fresh
)
$summary["direct_p2p_sender_authority_ready"] = [bool](
    $TransportOwner -eq "broker" -and
    $ownerDirectP2pSenderAuthority.accepted -and
    $clientDirectP2pSenderAuthority.accepted
)
$summary["direct_p2p_media_ready"] = [bool](
    $TransportOwner -eq "broker" -and
    $summary["direct_p2p_receiver_observed_bytes_ready"] -and
    $summary["direct_p2p_sender_authority_ready"]
)
$summary["direct_p2p_makepad_projection_ready_both_headsets"] = [bool](
    $summary["direct_p2p_media_ready"] -and
    $summary["projection_ready_both_headsets"]
)
Write-JsonFile -Value $summary -Path (Join-Path $OutDir "stereo-projection-summary.json")
Add-RunnerProgress -Phase "stereo_summary_written" -Data ([ordered]@{
    owner_projection_ready = [bool]$summary["owner_projection_ready"]
    client_projection_ready = [bool]$summary["client_projection_ready"]
    projection_ready_both_headsets = [bool]$summary["projection_ready_both_headsets"]
    direct_p2p_media_ready = [bool]$summary["direct_p2p_media_ready"]
    direct_p2p_makepad_projection_ready_both_headsets = [bool]$summary["direct_p2p_makepad_projection_ready_both_headsets"]
})

if (-not $SkipCleanup) {
    foreach ($serial in @($OwnerSerial, $ClientSerial)) {
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustyquest.qcl041")
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustymanifold.broker")
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustyquest.makepad.camera")
        Invoke-AdbBestEffort -Serial $serial -Arguments @("forward", "--remove-all")
    }
}
Add-RunnerProgress -Phase "cleanup_done" -Data ([ordered]@{ skip_cleanup = [bool]$SkipCleanup })

Add-RunnerProgress -Phase "completed"
Get-Content -Raw (Join-Path $OutDir "stereo-projection-summary.json")
