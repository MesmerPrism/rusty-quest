param(
    [string]$Serial = "340YC10G7T0JBW",
    [string]$LeaseId = "",
    [ValidateSet("synthetic", "camera2")]
    [string]$SourceMode = "synthetic",
    [string]$RunId = "",
    [string]$OutDir = "",
    [int]$RunSeconds = 20,
    [int]$BrokerLocalPort = 18765,
    [int]$PackedSourcePort = 8879,
    [int]$MinimumPairs = 5,
    [int]$PerEyeWidth = 1280,
    [int]$PerEyeHeight = 1280,
    [int]$Fps = 15,
    [int]$Bitrate = 12000000,
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$Python = "python",
    [string]$HostessCtl = "S:\Work\repos\active\rusty-hostess\tools\hostessctl\hostessctl.py",
    [string]$BrokerApk = "S:\Work\repos\active\rusty-quest\target\manifold-broker-android\rusty-manifold-broker.apk",
    [string]$NativeRendererApk = "S:\Work\repos\active\rusty-quest\target\native-renderer-android\rusty-quest-native-renderer.apk",
    [string]$NativeRendererProfile = "S:\Work\repos\active\rusty-quest\fixtures\runtime-profiles\quest-native-renderer-broker-rmanvid1-stereo-camera.profile.json",
    [switch]$SkipInstall,
    [switch]$SkipCleanup
)

$ErrorActionPreference = "Stop"
$BrokerPackage = "io.github.mesmerprism.rustymanifold.broker"
$NativeRendererPackage = "io.github.mesmerprism.rustyquest.native_renderer"
$NativeRendererActivity = "$NativeRendererPackage/android.app.NativeActivity"
$MediaLayout = "side-by-side-left-right"
$PackedWidth = $PerEyeWidth * 2
$PackedHeight = $PerEyeHeight
$SenderFrameLayout = "sbs-lr|${PackedWidth}x${PackedHeight}|${PerEyeWidth}x${PerEyeHeight}|c2sensor|nearest|20000000|gpu|nostale"

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl100-packed-sbs-local-$SourceMode-" + (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path "S:\Work\repos\active\rusty-quest\target" $RunId
}
$MediaDir = Join-Path $OutDir "media"
New-Item -ItemType Directory -Force -Path $MediaDir | Out-Null

$helperRoot = Join-Path $PSScriptRoot "qcl100_native_projection"
. (Join-Path $helperRoot "Common.ps1")
. (Join-Path $helperRoot "BridgeCommands.ps1")
. (Join-Path $helperRoot "DirectP2pMediaAuthority.ps1")

function Get-RemoteCameraRuntimeFromExecution {
    param([string]$Path)
    $execution = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    $messages = @($execution.command_execution.broker_messages)
    for ($index = $messages.Count - 1; $index -ge 0; $index--) {
        if ($null -ne $messages[$index].remote_camera_runtime) {
            return $messages[$index].remote_camera_runtime
        }
    }
    return $null
}

function Set-DeviceProperty {
    param([string]$Name, [string]$Value)
    Invoke-AdbChecked -Serial $Serial -Arguments @("shell", "setprop", $Name, $Value) -Name "setprop $Name"
}

foreach ($path in @($Adb, $HostessCtl, $BrokerApk, $NativeRendererApk, $NativeRendererProfile)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required packed loopback artifact not found: $path"
    }
}
if ($RunSeconds -lt 8) {
    throw "RunSeconds must be at least 8 so decoder and pair freshness are observable."
}
if ($PerEyeWidth -lt 64 -or $PerEyeHeight -lt 64 -or $Fps -lt 1 -or $Bitrate -lt 100000) {
    throw "Packed loopback dimensions/rate are invalid."
}

$logPath = Join-Path $OutDir "native-renderer.logcat.txt"
$logCapture = $null
$summaryPath = Join-Path $OutDir "packed-stereo-local-summary.json"
$startResult = $null
$statusResult = $null
$stopResult = $null
$summary = $null

try {
    Invoke-AdbChecked -Serial $Serial -Arguments @("get-state") -Name "device readiness"
    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "am", "force-stop", $BrokerPackage)
    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "am", "force-stop", $NativeRendererPackage)
    Invoke-AdbBestEffort -Serial $Serial -Arguments @("forward", "--remove", "tcp:$BrokerLocalPort")

    if (-not $SkipInstall) {
        Invoke-AdbChecked -Serial $Serial -Arguments @("install", "-r", $BrokerApk) -Name "install broker"
        Invoke-AdbChecked -Serial $Serial -Arguments @("install", "-r", $NativeRendererApk) -Name "install native renderer"
    }
    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "pm", "grant", $BrokerPackage, "android.permission.CAMERA")
    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "pm", "grant", $BrokerPackage, "horizonos.permission.HEADSET_CAMERA")

    $profileOut = Join-Path $MediaDir "native-renderer-property-write-plan.json"
    Invoke-External `
        -Name "apply native renderer profile" `
        -File "powershell" `
        -Arguments @(
            "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", (Join-Path $PSScriptRoot "Apply-RuntimeProfile.ps1"),
            "-ProfilePath", (Resolve-Path $NativeRendererProfile).Path,
            "-Execute", "-Out", $profileOut, "-Adb", $Adb, "-Serial", $Serial
        ) `
        -LogPath (Join-Path $MediaDir "native-renderer-profile-apply.stdout.txt") | Out-Null

    Set-DeviceProperty "debug.rustyquest.native_renderer.video_projection.broker.media_layout" $MediaLayout
    Set-DeviceProperty "debug.rustyquest.native_renderer.video_projection.broker.host" "127.0.0.1"
    Set-DeviceProperty "debug.rustyquest.native_renderer.video_projection.broker.left_port" $PackedSourcePort.ToString()
    Set-DeviceProperty "debug.rustyquest.native_renderer.video_projection.broker.right_port" "0"
    Set-DeviceProperty "debug.rustyquest.native_renderer.video_projection.width" $PackedWidth.ToString()
    Set-DeviceProperty "debug.rustyquest.native_renderer.video_projection.height" $PackedHeight.ToString()

    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "logcat", "-c")
    $logCapture = Start-Qcl100NativeRendererLogcatCapture -Serial $Serial -Label "local" -Path $logPath

    $sourceKind = if ($SourceMode -eq "synthetic") {
        "diagnostic_synthetic_mediacodec_surface"
    } else {
        "camera2_mediacodec_surface"
    }
    $permissionPolicy = if ($SourceMode -eq "synthetic") {
        "no_camera_permission_required"
    } else {
        "camera_permission_required"
    }
    $senderParams = New-RustyQuestRemoteCameraSenderParams `
        -SessionId $RunId `
        -SenderSourcePorts "stereo:$PackedSourcePort" `
        -MediaProfiles "stereo:${PackedWidth}x${PackedHeight}@${Fps}:${Bitrate}" `
        -CameraIds "left:50,right:51" `
        -QualityProfile "qcl100-packed-sbs-local-$SourceMode" `
        -SourceKind $sourceKind `
        -CameraPermissionPolicy $permissionPolicy `
        -MediaLayout $MediaLayout `
        -SenderFrameLayout $SenderFrameLayout
    $startRequest = New-BridgeRequest `
        "start-packed-source" `
        "command.remote_camera.start_sender" `
        $senderParams `
        "request.qcl100.$RunId.start" `
        "evidence.qcl100.$RunId.start"
    $startResult = Invoke-LiveBridgeCommand `
        "start-packed-source" `
        $Serial `
        $BrokerLocalPort `
        $startRequest `
        -TimeoutSeconds 60

    Invoke-AdbChecked -Serial $Serial -Arguments @(
        "shell", "am", "start", "-S", "-n", $NativeRendererActivity
    ) -Name "launch native renderer"
    Start-Sleep -Seconds $RunSeconds

    $statusParams = [ordered]@{ session_id = $RunId }
    $statusRequest = New-BridgeRequest `
        "packed-final-status" `
        "command.remote_camera.get_status" `
        $statusParams `
        "request.qcl100.$RunId.status" `
        "evidence.qcl100.$RunId.status"
    $statusResult = Invoke-LiveBridgeCommand `
        "packed-final-status" `
        $Serial `
        $BrokerLocalPort `
        $statusRequest `
        -NoLaunchBroker `
        -TimeoutSeconds 60
    $logStop = Stop-Qcl100NativeRendererLogcatCapture -Capture $logCapture
    $logCapture = $null

    $runtime = Get-RemoteCameraRuntimeFromExecution -Path $statusResult.execution_path
    $sourceRuntime = if ($null -ne $runtime) { $runtime.sender_source_runtime } else { $null }
    $sources = if ($null -ne $sourceRuntime) { @($sourceRuntime.sources) } else { @() }
    $source = if ($sources.Count -eq 1) { $sources[0] } else { $null }
    $log = if (Test-Path -LiteralPath $logPath) { Get-Content -Raw -LiteralPath $logPath } else { "" }
    $checks = [ordered]@{
        one_packed_source = [bool]($sources.Count -eq 1 -and [bool]$source.packed_stereo_enabled)
        source_mode_matches = [bool]($null -ne $source -and [string]$source.source_kind -eq $sourceKind)
        gpu_compositor_active = [bool]($null -ne $source -and [bool]$source.gpu_compositor_active)
        cpu_pixel_copy_absent = [bool]($null -ne $source -and -not [bool]$source.cpu_pixel_copy)
        one_encoder = [bool]($null -ne $source -and [int]$source.encoder_instance_count -eq 1)
        packed_dimensions_exact = [bool]($null -ne $source -and
            [int]$source.packed_width -eq $PackedWidth -and
            [int]$source.packed_height -eq $PackedHeight -and
            [int]$source.per_eye_width -eq $PerEyeWidth -and
            [int]$source.per_eye_height -eq $PerEyeHeight)
        camera2_output_size_exact_supported = [bool]($SourceMode -ne "camera2" -or (
            $null -ne $source.left_camera_capture -and
            $null -ne $source.right_camera_capture -and
            [bool]$source.left_camera_capture.camera_output_size_exact_supported -and
            [bool]$source.right_camera_capture.camera_output_size_exact_supported -and
            [int]$source.left_camera_capture.camera_output_width -eq $PerEyeWidth -and
            [int]$source.left_camera_capture.camera_output_height -eq $PerEyeHeight -and
            [int]$source.right_camera_capture.camera_output_width -eq $PerEyeWidth -and
            [int]$source.right_camera_capture.camera_output_height -eq $PerEyeHeight))
        pairs_fresh = [bool]($null -ne $source -and [long]$source.pairs_accepted -ge $MinimumPairs)
        pairs_bounded = [bool]($null -ne $source -and [long]$source.pair_delta_max_ns -le 20000000L)
        stale_eye_reuse_absent = [bool]($null -ne $source -and [long]$source.stale_eye_reuse_count -eq 0L)
        encoded_frames_fresh = [bool]($null -ne $source -and [long]$source.encoded_frames -ge $MinimumPairs)
        synthetic_pattern_fresh = [bool]($SourceMode -ne "synthetic" -or ($null -ne $source -and [long]$source.synthetic_frames -ge $MinimumPairs))
        packed_thread_one_socket_decoder_reader = [bool]($log -match 'status=start-packed-thread.*packedSocketCount=1.*decoderInstanceCount=1.*nativeImageReaderCount=1')
        packed_rmanvid_v4_header = [bool]($log -match "status=stream-header.*schema=4.*width=$PackedWidth height=$PackedHeight.*packedStereo=true.*brokerMediaLayout=side-by-side-left-right")
        hardware_decoder = [bool]($log -match 'status=decoder-created.*decoderSoftware=false.*requireHardware=true')
        packed_decoder_frames = [bool]($log -match 'status=frame side=stereo.*stereoPairId=[1-9][0-9]*.*packedStereo=true')
        packed_ahardwarebuffer_frames = [bool]($log -match "status=frame side=stereo.*packedStereo=true.*descriptorWidth=$PackedWidth descriptorHeight=$PackedHeight.*nativeImageReaderCount=1.*cpuPixelCopy=false")
        no_packed_or_android_fatal = [bool]($log -notmatch 'channel=remote-camera-broker-inlet[^\r\n]*status=error|channel=remote-camera-broker-pair[^\r\n]*status=rejected|FATAL EXCEPTION|Fatal signal')
    }
    $failedChecks = @($checks.GetEnumerator() | Where-Object { -not [bool]$_.Value } | ForEach-Object { $_.Key })
    $summary = [ordered]@{
        schema = "rusty.quest.qcl100_packed_stereo_local_loopback.v1"
        run_id = $RunId
        generated_at_utc = (Get-Date).ToUniversalTime().ToString("o")
        serial = $Serial
        lease_id = $LeaseId
        source_mode = $SourceMode
        media_layout = $MediaLayout
        packed_source_port = $PackedSourcePort
        run_seconds = $RunSeconds
        minimum_pairs = $MinimumPairs
        status = if ($failedChecks.Count -eq 0) { "pass" } else { "fail" }
        promotion_scope = "local_packed_sbs_sender_receiver_only"
        promotion_claimed = $false
        checks = $checks
        failed_checks = $failedChecks
        source_runtime = $source
        broker_runtime = $runtime
        native_log_path = $logPath
        native_log_capture = $logStop
        start_command = $startResult
        status_command = $statusResult
        cleanup_skipped = [bool]$SkipCleanup
    }
    Write-JsonFile -Value $summary -Path $summaryPath
    if ($failedChecks.Count -gt 0) {
        throw "Packed stereo local $SourceMode validation failed: $($failedChecks -join ', ')"
    }
} finally {
    if ($null -ne $logCapture) {
        Stop-Qcl100NativeRendererLogcatCapture -Capture $logCapture | Out-Null
    }
    if (-not $SkipCleanup) {
        try {
            $stopParams = [ordered]@{ session_id = $RunId }
            $stopRequest = New-BridgeRequest `
                "stop-packed-session" `
                "command.remote_camera.stop" `
                $stopParams `
                "request.qcl100.$RunId.stop" `
                "evidence.qcl100.$RunId.stop"
            $stopResult = Invoke-LiveBridgeCommand `
                "stop-packed-session" `
                $Serial `
                $BrokerLocalPort `
                $stopRequest `
                -NoLaunchBroker `
                -AllowFailure `
                -TimeoutSeconds 30
        } catch {
        }
        Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "am", "force-stop", $NativeRendererPackage)
        Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "am", "force-stop", $BrokerPackage)
        Invoke-AdbBestEffort -Serial $Serial -Arguments @("forward", "--remove", "tcp:$BrokerLocalPort")
    }
}

Get-Content -Raw -LiteralPath $summaryPath
