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
    [int]$MakepadPreferredWidth = 320,
    [int]$MakepadPreferredHeight = 240,
    [int]$MakepadFrameRateHz = 15,
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
New-Item -ItemType Directory -Force -Path $MediaDir | Out-Null

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
        [ordered]@{ name = "debug.rustyquest.makepad.broker.h264.decode.output.mode"; value = "hardware-buffer" },
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
        [string]$LogName
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
        "--ez", "qcl041.qcl082_relay_enabled", "true",
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
    param([string]$Serial, [string]$OutPath)
    $content = & $Adb -s $Serial exec-out run-as io.github.mesmerprism.rustyquest.qcl041 cat "files/qcl041/$RunId.json" 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($content)) {
        throw "Could not read QCL041 artifact from $Serial. $content"
    }
    $content | Set-Content -Encoding UTF8 -Path $OutPath
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
            $makepad = Summarize-MakepadLog -LogPath $logPath
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

        Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "input", "keyevent", "224")
        Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustyquest.makepad.camera")
        Start-Sleep -Seconds 1
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

function Select-LastLineContaining {
    param([string[]]$Lines, [string]$Needle)
    $matches = @($Lines | Where-Object { $_ -like "*$Needle*" })
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
    param([string]$LogPath)
    $lines = @(Get-Content -LiteralPath $LogPath -ErrorAction SilentlyContinue)
    $lastCadence = Select-LastLineContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_CADENCE schema=rusty.quest.makepad-cadence.v1 phase=sample"
    $lastTextureMetadata = Select-LastLineContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_TEXTURE_METADATA schema=rusty.quest.makepad-texture-metadata.v1"
    $lastHardwareBufferStart = Select-LastLineContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_HARDWARE_BUFFER_IMPORT schema=rusty.quest.makepad-hardware-buffer-import.v1 phase=start"
    $lastVideoTextureGate = Select-LastLineContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_VIDEO_TEXTURE_GATE schema=rusty.quest.makepad-video-texture-gate.v1"
    $lastFrameFlow = Select-LastLineContaining -Lines $lines -Needle "phase=xr-end-frame status=submitted"
    $leftHeader = Select-LastLineContaining -Lines $lines -Needle "phase=stream-header-metadata status=ok side=left"
    $rightHeader = Select-LastLineContaining -Lines $lines -Needle "phase=stream-header-metadata status=ok side=right"
    $leftProgress = Select-LastLineContaining -Lines $lines -Needle "External H.264 playback progress videoId=100"
    $rightProgress = Select-LastLineContaining -Lines $lines -Needle "External H.264 playback progress videoId=101"
    $fatal = @($lines | Where-Object { $_ -match "FATAL EXCEPTION|Fatal signal|SIGSEGV|SIGABRT|GPU page fault|ANR" })
    $leftDelta = ConvertTo-IntSafe (Get-MarkerValue -Line $lastCadence -Key "leftTextureUpdateDelta")
    $rightDelta = ConvertTo-IntSafe (Get-MarkerValue -Line $lastCadence -Key "rightTextureUpdateDelta")
    $pairedDelta = ConvertTo-IntSafe (Get-MarkerValue -Line $lastCadence -Key "pairedTextureUpdateDelta")
    $texturePath = Get-MarkerValue -Line $lastCadence -Key "cameraTexturePath"
    if ([string]::IsNullOrWhiteSpace($texturePath)) {
        $texturePath = Get-MarkerValue -Line $lastTextureMetadata -Key "cameraTexturePath"
    }
    $leftHwb = ConvertTo-IntSafe (Get-MarkerValue -Line $leftProgress -Key "hardwareBufferFrameEmitCount")
    $rightHwb = ConvertTo-IntSafe (Get-MarkerValue -Line $rightProgress -Key "hardwareBufferFrameEmitCount")
    $visibleReady = Get-MarkerValue -Line $lastCadence -Key "visibleCameraProjectionReady"
    $mappingReady = Get-MarkerValue -Line $lastCadence -Key "projectionMappingReady"
    $vulkanImport = Get-MarkerValue -Line $lastCadence -Key "makepadVulkanImport"
    $frameFlowReady = [bool]($lastFrameFlow -like "*shouldRender=true*" -and $lastFrameFlow -like "*resultCode=0*")
    [ordered]@{
        log_path = $LogPath
        marker_counts = [ordered]@{
            hardware_buffer_import = Count-LinesContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_HARDWARE_BUFFER_IMPORT"
            stream_header_ok = Count-LinesContaining -Lines $lines -Needle "phase=stream-header-metadata status=ok"
            texture_updated = Count-LinesContaining -Lines $lines -Needle "phase=texture-updated status=ok"
            cadence = Count-LinesContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_CADENCE"
            frame_adoption = Count-LinesContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_FRAME_ADOPTION"
            stereo_projection = Count-LinesContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_STEREO_PROJECTION"
            video_texture_gate = Count-LinesContaining -Lines $lines -Needle "RUSTY_QUEST_MAKEPAD_VIDEO_TEXTURE_GATE"
            h264_progress = Count-LinesContaining -Lines $lines -Needle "External H.264 playback progress"
        }
        broker_h264_hardware_buffer_requested = [bool]($lastHardwareBufferStart -like "*broker-h264-stereo-mediacodec-hardware-buffer*")
        texture_path = $texturePath
        makepad_vulkan_import = $vulkanImport
        left_stream_header_ok = [bool]($null -ne $leftHeader)
        right_stream_header_ok = [bool]($null -ne $rightHeader)
        left_hardware_buffer_frames = $leftHwb
        right_hardware_buffer_frames = $rightHwb
        left_texture_update_delta = $leftDelta
        right_texture_update_delta = $rightDelta
        paired_texture_update_delta = $pairedDelta
        projection_mapping_ready = $mappingReady
        aligned_projection = Get-MarkerValue -Line $lastCadence -Key "alignedProjection"
        visible_camera_projection_ready = $visibleReady
        video_texture_gate_ready = (Get-MarkerValue -Line $lastVideoTextureGate -Key "textureGateReady") -eq "true"
        frame_flow_ready = $frameFlowReady
        fatal_count = $fatal.Count
        fatal_lines = @($fatal | Select-Object -First 20)
        last_hardware_buffer_start = $lastHardwareBufferStart
        last_cadence = $lastCadence
        last_texture_metadata = $lastTextureMetadata
        left_progress = $leftProgress
        right_progress = $rightProgress
        projection_ready = [bool](
            $leftHeader -and
            $rightHeader -and
            ($leftHwb -gt 0 -or $leftDelta -gt 0) -and
            ($rightHwb -gt 0 -or $rightDelta -gt 0) -and
            $texturePath -eq "broker-h264-mediacodec-hardware-buffer" -and
            $vulkanImport -eq "true" -and
            $mappingReady -eq "true" -and
            $visibleReady -eq "true" -and
            $frameFlowReady -and
            $fatal.Count -eq 0
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

foreach ($path in @($HostessCtl, $Qcl041Apk, $BrokerApk, $MakepadApk)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required artifact not found: $path"
    }
}

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

$ownerWakePrep = Prepare-QuestForXrFocus -Serial $OwnerSerial -Label "owner"
$clientWakePrep = Prepare-QuestForXrFocus -Serial $ClientSerial -Label "client"

$ownerMakepadProps = Set-MakepadBrokerProjectionProperties -Serial $OwnerSerial
$clientMakepadProps = Set-MakepadBrokerProjectionProperties -Serial $ClientSerial

$receiverParams = [ordered]@{
    session_id = $RunId
    receiver_bind_host = "127.0.0.1"
    receiver_ports = "left:$LeftReceiverPort,right:$RightReceiverPort"
    transport_bind_host = "0.0.0.0"
    transport_receive_ports = "left:$LeftTransportPort,right:$RightTransportPort"
}
$senderParams = [ordered]@{
    session_id = $RunId
    sender_source_host = "127.0.0.1"
    sender_source_ports = "left:$LeftSourcePort,right:$RightSourcePort"
    sender_source_kind = "camera2_mediacodec_surface"
    sender_media_profiles = $MediaProfiles
    sender_camera_ids = $CameraIds
    sender_camera_id = "none"
    sender_camera_facing = "none"
    sender_quality_profile = "qcl099-stereo-direct-wifi"
    camera_permission_policy = "camera_permission_required"
    transport_routes = "none"
}

$ownerRecv = New-BridgeRequest "owner-start-receiver" "command.remote_camera.start_receiver" $receiverParams "request.qcl099.$RunId.owner.receiver" "evidence.qcl099.$RunId.owner.receiver"
$clientRecv = New-BridgeRequest "client-start-receiver" "command.remote_camera.start_receiver" $receiverParams "request.qcl099.$RunId.client.receiver" "evidence.qcl099.$RunId.client.receiver"
$ownerSender = New-BridgeRequest "owner-start-source-only" "command.remote_camera.start_sender" $senderParams "request.qcl099.$RunId.owner.source_only" "evidence.qcl099.$RunId.owner.source_only"
$clientSender = New-BridgeRequest "client-start-source-only" "command.remote_camera.start_sender" $senderParams "request.qcl099.$RunId.client.source_only" "evidence.qcl099.$RunId.client.source_only"

Invoke-LiveBridgeCommand "owner-start-receiver" $OwnerSerial $OwnerBrokerLocalPort $ownerRecv
Invoke-LiveBridgeCommand "client-start-receiver" $ClientSerial $ClientBrokerLocalPort $clientRecv
Invoke-LiveBridgeCommand "owner-start-source-only" $OwnerSerial $OwnerBrokerLocalPort $ownerSender
Invoke-LiveBridgeCommand "client-start-source-only" $ClientSerial $ClientBrokerLocalPort $clientSender

Start-Qcl041Relay $OwnerSerial "group_owner" $ClientWifiDirectAddress $OwnerLeaseId "owner-qcl041-launch.txt"
Start-Sleep -Seconds 2
Start-Qcl041Relay $ClientSerial "client" $OwnerWifiDirectAddress $ClientLeaseId "client-qcl041-launch.txt"

Start-Sleep -Seconds 8
$ownerXrStart = Wait-MakepadXrCadence -Serial $OwnerSerial -Label "owner"
$clientXrStart = Wait-MakepadXrCadence -Serial $ClientSerial -Label "client"

Start-Sleep -Seconds $ProjectionSeconds

$ownerLog = Join-Path $OutDir "owner-makepad.logcat.txt"
$clientLog = Join-Path $OutDir "client-makepad.logcat.txt"
Invoke-External -Name "owner logcat" -File $Adb -Arguments @("-s", $OwnerSerial, "logcat", "-d", "-v", "threadtime") -LogPath $ownerLog | Out-Null
Invoke-External -Name "client logcat" -File $Adb -Arguments @("-s", $ClientSerial, "logcat", "-d", "-v", "threadtime") -LogPath $clientLog | Out-Null
$ownerFinalFocus = Get-MakepadFocusSnapshot -Serial $OwnerSerial -Label "owner" -Suffix "final"
$clientFinalFocus = Get-MakepadFocusSnapshot -Serial $ClientSerial -Label "client" -Suffix "final"

Read-Qcl041Artifact -Serial $OwnerSerial -OutPath (Join-Path $OutDir "owner-qcl041.json")
Read-Qcl041Artifact -Serial $ClientSerial -OutPath (Join-Path $OutDir "client-qcl041.json")

$statusParams = [ordered]@{ session_id = $RunId }
$ownerStatus = New-BridgeRequest "owner-final-status" "command.remote_camera.get_status" $statusParams "request.qcl099.$RunId.owner.final_status" "evidence.qcl099.$RunId.owner.final_status"
$clientStatus = New-BridgeRequest "client-final-status" "command.remote_camera.get_status" $statusParams "request.qcl099.$RunId.client.final_status" "evidence.qcl099.$RunId.client.final_status"
$ownerStop = New-BridgeRequest "owner-stop" "command.remote_camera.stop" $statusParams "request.qcl099.$RunId.owner.stop" "evidence.qcl099.$RunId.owner.stop"
$clientStop = New-BridgeRequest "client-stop" "command.remote_camera.stop" $statusParams "request.qcl099.$RunId.client.stop" "evidence.qcl099.$RunId.client.stop"
Invoke-LiveBridgeCommand "owner-final-status" $OwnerSerial $OwnerBrokerLocalPort $ownerStatus -NoLaunchBroker
Invoke-LiveBridgeCommand "client-final-status" $ClientSerial $ClientBrokerLocalPort $clientStatus -NoLaunchBroker
Invoke-LiveBridgeCommand "owner-stop" $OwnerSerial $OwnerBrokerLocalPort $ownerStop -NoLaunchBroker
Invoke-LiveBridgeCommand "client-stop" $ClientSerial $ClientBrokerLocalPort $clientStop -NoLaunchBroker

$ownerQcl041 = Get-Content -Raw (Join-Path $OutDir "owner-qcl041.json") | ConvertFrom-Json
$clientQcl041 = Get-Content -Raw (Join-Path $OutDir "client-qcl041.json") | ConvertFrom-Json
$ownerStatusExecution = Get-Content -Raw (Join-Path $MediaDir "owner-final-status-execution.json") | ConvertFrom-Json
$clientStatusExecution = Get-Content -Raw (Join-Path $MediaDir "client-final-status-execution.json") | ConvertFrom-Json
$ownerMakepad = Summarize-MakepadLog -LogPath $ownerLog
$clientMakepad = Summarize-MakepadLog -LogPath $clientLog

$summary = [ordered]@{
    schema = "rusty.quest.qcl099_quest_to_quest_stereo_projection_wifi_direct_run.v1"
    run_id = $RunId
    owner_serial = $OwnerSerial
    client_serial = $ClientSerial
    projection_seconds = $ProjectionSeconds
    media_profiles = $MediaProfiles
    camera_ids = $CameraIds
    topology = [ordered]@{
        transport = "quest_to_quest_wifi_direct"
        relay = "qcl041_multi_lane_rmanvid1"
        source_ports = "left:$LeftSourcePort,right:$RightSourcePort"
        receiver_ports = "left:$LeftReceiverPort,right:$RightReceiverPort"
        transport_receive_ports = "left:$LeftTransportPort,right:$RightTransportPort"
        makepad_source_mode = "existing-stream"
        makepad_decode_output_mode = "hardware-buffer"
    }
    owner_relay = $ownerQcl041.diagnostics.qcl082_relay
    owner_relay_left = $ownerQcl041.diagnostics.qcl082_relay_left
    owner_relay_right = $ownerQcl041.diagnostics.qcl082_relay_right
    client_relay = $clientQcl041.diagnostics.qcl082_relay
    client_relay_left = $clientQcl041.diagnostics.qcl082_relay_left
    client_relay_right = $clientQcl041.diagnostics.qcl082_relay_right
    owner_broker_status = Summarize-BrokerRuntime $ownerStatusExecution.command_execution.broker_messages[0].remote_camera_runtime
    client_broker_status = Summarize-BrokerRuntime $clientStatusExecution.command_execution.broker_messages[0].remote_camera_runtime
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
        decode_target = "MediaCodec hardware-buffer"
        projection_target = "Makepad native XR custom stereo projection"
    }
    evidence_dir = $OutDir
}

$summary["owner_projection_ready"] = [bool]($ownerMakepad.projection_ready -and $ownerFinalFocus.focus_active)
$summary["client_projection_ready"] = [bool]($clientMakepad.projection_ready -and $clientFinalFocus.focus_active)
$summary["projection_ready_both_headsets"] = [bool]($summary["owner_projection_ready"] -and $summary["client_projection_ready"])
Write-JsonFile -Value $summary -Path (Join-Path $OutDir "stereo-projection-summary.json")

if (-not $SkipCleanup) {
    foreach ($serial in @($OwnerSerial, $ClientSerial)) {
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustyquest.qcl041")
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustymanifold.broker")
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustyquest.makepad.camera")
        Invoke-AdbBestEffort -Serial $serial -Arguments @("forward", "--remove-all")
    }
}

Get-Content -Raw (Join-Path $OutDir "stereo-projection-summary.json")
