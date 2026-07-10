param(
    [Parameter(Mandatory=$true)][string]$RunDir,
    [ValidateSet("owner-to-client", "client-to-owner", "duplex")]
    [string]$Direction = "duplex",
    [string]$SyntheticLocalSummaryPath = "",
    [string]$Camera2LocalSummaryPath = "",
    [string]$Qcl041SummaryPath = "",
    [string]$DualLaneFallbackPromotionPath = "",
    [int]$MinimumPairs = 5,
    [string]$Out = ""
)

$ErrorActionPreference = "Stop"
$RunDir = (Resolve-Path -LiteralPath $RunDir).Path
if ([string]::IsNullOrWhiteSpace($Out)) {
    $Out = Join-Path $RunDir "packed-stereo-acceptance.json"
}

function Read-Json {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Write-Json {
    param($Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 32) + "`n"
    [System.IO.File]::WriteAllText($Path, $json, [System.Text.UTF8Encoding]::new($false))
}

function Get-RemoteCameraRuntime {
    param([string]$ExecutionPath)
    $execution = Read-Json $ExecutionPath
    if ($null -eq $execution) { return $null }
    $messages = @($execution.command_execution.broker_messages)
    for ($index = $messages.Count - 1; $index -ge 0; $index--) {
        if ($null -ne $messages[$index].remote_camera_runtime) {
            return $messages[$index].remote_camera_runtime
        }
    }
    return $null
}

function Get-MarkerValue {
    param([string]$Line, [string]$Key)
    if ([string]::IsNullOrWhiteSpace($Line)) { return "" }
    $match = [regex]::Match($Line, "(?:^|\s)" + [regex]::Escape($Key) + "=([^\s]+)")
    if ($match.Success) { return $match.Groups[1].Value }
    return ""
}

function Get-LastMatchingLine {
    param([string[]]$Lines, [string]$Pattern)
    return @($Lines | Where-Object { $_ -match $Pattern } | Select-Object -Last 1)[0]
}

function Test-PackedEndpoint {
    param(
        [string]$Label,
        $Runtime,
        [string]$LogPath,
        [bool]$Sends,
        [bool]$Receives,
        [string]$ExpectedLocalAddress
    )
    $lanes = if ($null -ne $Runtime) { @($Runtime.lanes) } else { @() }
    $senderLanes = @($lanes | Where-Object { $_.role -eq "sender" })
    $receiverLanes = @($lanes | Where-Object { $_.role -eq "receiver" })
    $sender = if ($senderLanes.Count -eq 1) { $senderLanes[0] } else { $null }
    $receiver = if ($receiverLanes.Count -eq 1) { $receiverLanes[0] } else { $null }
    $sources = if ($null -ne $Runtime -and $null -ne $Runtime.sender_source_runtime) {
        @($Runtime.sender_source_runtime.sources)
    } else { @() }
    $source = if ($sources.Count -eq 1) { $sources[0] } else { $null }
    $lines = if (Test-Path -LiteralPath $LogPath) { @(Get-Content -LiteralPath $LogPath) } else { @() }
    $log = $lines -join "`n"
    $scorecard = Get-LastMatchingLine -Lines $lines -Pattern 'channel=camera-projection-scorecard.*remoteBrokerCameraProjectionActive=true'
    $decoderCreatedCount = @($lines | Where-Object { $_ -match 'channel=remote-camera-broker-inlet status=decoder-created' }).Count
    $packedFrameCount = @($lines | Where-Object { $_ -match 'channel=remote-camera-broker-ahardware-buffer status=frame side=stereo.*packedStereo=true' }).Count
    $leftHardwareBufferId = Get-MarkerValue -Line $scorecard -Key "leftHardwareBufferId"
    $rightHardwareBufferId = Get-MarkerValue -Line $scorecard -Key "rightHardwareBufferId"
    $checks = [ordered]@{
        sender_lane_count = [bool]((-not $Sends) -or $senderLanes.Count -eq 1)
        sender_lane_is_stereo = [bool]((-not $Sends) -or ($null -ne $sender -and [string]$sender.eye -eq "stereo"))
        sender_direct_p2p_authority = [bool]((-not $Sends) -or (
            $null -ne $sender -and
            [string]$sender.peer_socket_authority -eq "rusty_direct_p2p_socket_authority" -and
            [string]$sender.peer_socket_bound_local_address -eq $ExpectedLocalAddress -and
            [string]$sender.peer_socket_local_interface -eq "p2p0" -and
            [bool]$sender.peer_socket_direct_p2p_ready -and
            [long]$sender.bytes_sent -gt 0L))
        one_packed_source = [bool]((-not $Sends) -or ($sources.Count -eq 1 -and [bool]$source.packed_stereo_enabled))
        camera2_50_51 = [bool]((-not $Sends) -or (
            [string]$source.source_kind -eq "camera2_mediacodec_surface" -and
            [string]$source.left_camera_id -eq "50" -and
            [string]$source.right_camera_id -eq "51"))
        camera2_exact_1280_square_outputs = [bool]((-not $Sends) -or (
            [int]$source.per_eye_width -eq 1280 -and
            [int]$source.per_eye_height -eq 1280 -and
            [bool]$source.left_camera_capture.camera_output_size_exact_supported -and
            [bool]$source.right_camera_capture.camera_output_size_exact_supported -and
            [int]$source.left_camera_capture.camera_output_width -eq 1280 -and
            [int]$source.left_camera_capture.camera_output_height -eq 1280 -and
            [int]$source.right_camera_capture.camera_output_width -eq 1280 -and
            [int]$source.right_camera_capture.camera_output_height -eq 1280))
        packed_2560x1280_source = [bool]((-not $Sends) -or (
            [int]$source.packed_width -eq 2560 -and
            [int]$source.packed_height -eq 1280))
        one_gpu_encoder = [bool]((-not $Sends) -or (
            [bool]$source.gpu_compositor_active -and
            -not [bool]$source.cpu_pixel_copy -and
            [int]$source.encoder_instance_count -eq 1))
        bounded_unique_pairs = [bool]((-not $Sends) -or (
            [long]$source.pairs_accepted -ge $MinimumPairs -and
            [long]$source.encoded_frames -ge $MinimumPairs -and
            [long]$source.pair_delta_max_ns -le [long]$source.pair_delta_bound_ns -and
            [long]$source.stale_eye_reuse_count -eq 0L -and
            [long]$source.encoded_packets_without_pair -eq 0L))
        receiver_lane_count = [bool]((-not $Receives) -or $receiverLanes.Count -eq 1)
        receiver_lane_is_stereo = [bool]((-not $Receives) -or ($null -ne $receiver -and [string]$receiver.eye -eq "stereo"))
        receiver_p2p0_bound = [bool]((-not $Receives) -or (
            $null -ne $receiver -and
            [string]$receiver.transport_host -eq $ExpectedLocalAddress -and
            [string]$receiver.transport_server_bound_address -eq $ExpectedLocalAddress -and
            [string]$receiver.transport_server_bound_interface -eq "p2p0" -and
            [bool]$receiver.receiver_ready -and
            [bool]$receiver.receiver_transport_ready))
        receiver_bytes_fresh = [bool]((-not $Receives) -or (
            [long]$receiver.bytes_received -gt 0L -and
            [long]$receiver.copy_bytes_read -gt 0L -and
            [long]$receiver.copy_bytes_written -gt 0L -and
            [long]$receiver.copy_last_read_age_ms -ge 0L -and
            [long]$receiver.copy_last_read_age_ms -le 5000L))
        one_decoder_instance = [bool]((-not $Receives) -or (
            $decoderCreatedCount -eq 1 -and
            $log -match 'decoderSoftware=false.*decoderSelection=hardware-required.*requireHardware=true' -and
            $log -notmatch 'status=decoder-restart'))
        rmanvid_v4_packed = [bool]((-not $Receives) -or (
            $log -match 'status=stream-header side=stereo.*schema=4.*width=2560 height=1280.*packedStereo=true.*brokerMediaLayout=side-by-side-left-right'))
        packed_2560x1280_decoder_output = [bool]((-not $Receives) -or (
            $log -match 'status=frame side=stereo.*packedStereo=true.*descriptorWidth=2560 descriptorHeight=1280'))
        packed_ahardwarebuffer_fresh = [bool]((-not $Receives) -or $packedFrameCount -ge $MinimumPairs)
        one_native_image_reader_no_cpu_copy = [bool]((-not $Receives) -or (
            $log -match 'status=start-packed-thread.*packedSocketCount=1.*decoderInstanceCount=1.*nativeImageReaderCount=1.*cpuPixelCopy=false'))
        openxr_projection_ready = [bool]((-not $Receives) -or (
            $scorecard -match 'cameraProjectionReady=true.*openxrSubmitReady=true.*vulkanExternalImportReady=true.*projectionReady=true' -and
            $scorecard -match 'stereoPairingPolicy=remote-broker-packed-sbs-source-timestamp'))
        packed_same_ahb_sampled_per_eye = [bool]((-not $Receives) -or (
            -not [string]::IsNullOrWhiteSpace($leftHardwareBufferId) -and
            $leftHardwareBufferId -eq $rightHardwareBufferId))
        packed_eye_uv_split_equivalent = [bool]((-not $Receives) -or (
            $scorecard -match 'sourceLayout=packed-side-by-side-left-right' -and
            $scorecard -match 'leftSourceUvRect=0\.000000,0\.000000,0\.500000,1\.000000' -and
            $scorecard -match 'rightSourceUvRect=0\.500000,0\.000000,0\.500000,1\.000000' -and
            $scorecard -match 'packedSourceVisualEquivalenceReady=true'))
        no_packed_or_android_fatal = [bool]($log -notmatch 'channel=remote-camera-broker-inlet[^\r\n]*status=error|channel=remote-camera-broker-pair[^\r\n]*status=rejected|FATAL EXCEPTION|Fatal signal')
    }
    $failed = @($checks.GetEnumerator() | Where-Object { -not [bool]$_.Value } | ForEach-Object { $_.Key })
    return [ordered]@{
        label = $Label
        sends = $Sends
        receives = $Receives
        expected_local_address = $ExpectedLocalAddress
        accepted = [bool]($failed.Count -eq 0)
        checks = $checks
        failed_checks = $failed
        sender_lane = $sender
        receiver_lane = $receiver
        source_runtime = $source
        decoder_created_count = $decoderCreatedCount
        packed_ahardwarebuffer_frame_count = $packedFrameCount
        last_projection_scorecard = $scorecard
        log_path = $LogPath
    }
}

$childSummaryPath = Join-Path $RunDir "native-stereo-projection-summary.json"
$childSummary = Read-Json $childSummaryPath
if ($null -eq $childSummary) { throw "Missing child QCL100 summary: $childSummaryPath" }
$mediaDir = Join-Path $RunDir "media"
$ownerRuntime = Get-RemoteCameraRuntime (Join-Path $mediaDir "owner-final-status-execution.json")
$clientRuntime = Get-RemoteCameraRuntime (Join-Path $mediaDir "client-final-status-execution.json")
$ownerSends = [bool]($Direction -in @("owner-to-client", "duplex"))
$clientSends = [bool]($Direction -in @("client-to-owner", "duplex"))
$ownerReceives = [bool]($Direction -in @("client-to-owner", "duplex"))
$clientReceives = [bool]($Direction -in @("owner-to-client", "duplex"))
$ownerAddress = [string]$childSummary.direct_p2p_address_refresh.owner_effective_wifi_direct_address
$clientAddress = [string]$childSummary.direct_p2p_address_refresh.client_effective_wifi_direct_address
$owner = Test-PackedEndpoint `
    -Label "owner" `
    -Runtime $ownerRuntime `
    -LogPath (Join-Path $RunDir "owner-native-renderer.logcat.txt") `
    -Sends:$ownerSends `
    -Receives:$ownerReceives `
    -ExpectedLocalAddress $ownerAddress
$client = Test-PackedEndpoint `
    -Label "client" `
    -Runtime $clientRuntime `
    -LogPath (Join-Path $RunDir "client-native-renderer.logcat.txt") `
    -Sends:$clientSends `
    -Receives:$clientReceives `
    -ExpectedLocalAddress $clientAddress

$syntheticSummary = Read-Json $SyntheticLocalSummaryPath
$camera2Summary = Read-Json $Camera2LocalSummaryPath
$qcl041Summary = Read-Json $Qcl041SummaryPath
$dualFallback = Read-Json $DualLaneFallbackPromotionPath
$prerequisites = [ordered]@{
    synthetic_local_passed = [bool]($null -ne $syntheticSummary -and [string]$syntheticSummary.status -eq "pass")
    camera2_local_passed = [bool]($null -ne $camera2Summary -and [string]$camera2Summary.status -eq "pass")
    qcl041_lower_gate_passed = [bool](
        $null -ne $qcl041Summary -and
        [string]$qcl041Summary.status -eq "pass" -and
        [string]$qcl041Summary.qcl100_lower_gate_authority -eq "rusty_direct_p2p_socket_authority")
    dual_lane_fallback_preserved = [bool](
        $null -ne $dualFallback -and
        [string]$dualFallback.status -eq "promoted" -and
        [bool]$dualFallback.promotion_claimed)
}
$failedPrerequisites = @($prerequisites.GetEnumerator() | Where-Object { -not [bool]$_.Value } | ForEach-Object { $_.Key })
$duplex = [bool]($Direction -eq "duplex")
$accepted = [bool]($owner.accepted -and $client.accepted -and $failedPrerequisites.Count -eq 0)
$promotionClaimed = [bool]($accepted -and $duplex)
$result = [ordered]@{
    schema = "rusty.quest.qcl100_packed_stereo_acceptance.v1"
    generated_at_utc = (Get-Date).ToUniversalTime().ToString("o")
    run_id = [string]$childSummary.run_id
    direction = $Direction
    media_layout = "side-by-side-left-right"
    transport_topology = "one-rusty-direct-p2p-socket-per-direction"
    status = if ($promotionClaimed) { "promoted" } elseif ($accepted) { "pass_not_promoting_one_way" } else { "blocked" }
    accepted = $accepted
    promotion_claimed = $promotionClaimed
    promotion_scope = if ($duplex) { "packed_sbs_stereo_simultaneous_duplex" } else { "packed_sbs_stereo_one_way_validation" }
    owner = $owner
    client = $client
    active_direction_count = if ($duplex) { 2 } else { 1 }
    accepted_direction_count = if ($accepted) { if ($duplex) { 2 } else { 1 } } else { 0 }
    prerequisites = $prerequisites
    failed_prerequisites = $failedPrerequisites
    source_artifacts = [ordered]@{
        child_qcl100_summary = $childSummaryPath
        synthetic_local_summary = $SyntheticLocalSummaryPath
        camera2_local_summary = $Camera2LocalSummaryPath
        qcl041_summary = $Qcl041SummaryPath
        dual_lane_fallback_promotion = $DualLaneFallbackPromotionPath
    }
    legacy_dual_lane_summary_accepted = [bool]$childSummary.freshness_acceptance.passed
    legacy_dual_lane_summary_advisory_for_packed = $true
}
Write-Json -Value $result -Path $Out
Get-Content -Raw -LiteralPath $Out
if (-not $accepted) {
    throw "Packed stereo acceptance blocked; see $Out"
}
