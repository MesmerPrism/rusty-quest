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
    if (-not (Test-Path $path)) {
        throw "Missing video-projection static input: $path"
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
        throw "Video-projection static check missing $Label token: $Needle"
    }
}

$nativeLib = Read-RepoText "apps\native-renderer-android\native\src\lib.rs"
$nativeOptions = Read-RepoText "apps\native-renderer-android\native\src\native_renderer_video_projection_options.rs"
$nativeProperties = Read-RepoText "apps\native-renderer-android\native\src\native_renderer_properties.rs"
$nativeStream = Read-RepoText "apps\native-renderer-android\native\src\video_projection_native_stream.rs"
$remoteCameraStream = Read-RepoText "apps\native-renderer-android\native\src\remote_camera_projection_native_stream.rs"
$javaPlayback = Read-RepoText "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\StereoVideoPlayback.java"
$brokerSource = Read-RepoText "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\RemoteCameraSourceRuntime.java"
$brokerSession = Read-RepoText "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\RemoteCameraSessionRuntime.java"
$playerBridge = Read-RepoText "apps\native-renderer-android\native\src\video_projection_player_bridge.rs"
$projectionMetadata = Read-RepoText "apps\native-renderer-android\native\src\video_projection_metadata.rs"
$videoRenderer = Read-RepoText "apps\native-renderer-android\native\src\video_projection.rs"
$xrVulkan = Read-RepoText "apps\native-renderer-android\native\src\xr_vulkan.rs"
$scorecard = Read-RepoText "apps\native-renderer-android\native\src\xr_vulkan\scorecard.rs"
$guideProjectionShader = Read-RepoText "apps\native-renderer-android\native\shaders\guide_projection.frag.glsl"
$guideVideoProjectionShader = Read-RepoText "apps\native-renderer-android\native\shaders\guide_video_projection.frag.glsl"
$projectionBorderStretchOptions = Read-RepoText "apps\native-renderer-android\native\src\native_renderer_projection_border_stretch_options.rs"
$nativeBuild = Read-RepoText "apps\native-renderer-android\native\build.rs"
$vertexShader = Read-RepoText "apps\native-renderer-android\native\shaders\video_projection.vert.glsl"
$fragmentShader = Read-RepoText "apps\native-renderer-android\native\shaders\video_projection.frag.glsl"
$stageVideo = Read-RepoText "tools\Stage-NativeRendererVideo.ps1"
$blendSweep = Read-RepoText "tools\Invoke-NativeRendererVideoBorderBlendSweep.ps1"
$qcl100Runner = @(
    Read-RepoText "tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1"
    Read-RepoText "tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionReciprocalWifiDirect.ps1"
    Get-ChildItem -LiteralPath (Join-Path $RepoRoot "tools\qcl100_native_projection") -Filter "*.ps1" |
        Sort-Object Name |
        ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName }
) -join "`n"
$videoProjectionDoc = Read-RepoText "docs\NATIVE_VIDEO_PROJECTION.md"
$propertyManifest = Read-RepoText "fixtures\native-renderer\native-renderer-property-manifest.json"
$validProfile = Read-RepoText "fixtures\runtime-profiles\quest-native-renderer-fullscreen-stereo-video.profile.json"
$brokerRmanvid1Profile = Read-RepoText "fixtures\runtime-profiles\quest-native-renderer-broker-rmanvid1-stereo-camera.profile.json"
$videoBorderBlendProfile = Read-RepoText "fixtures\runtime-profiles\quest-native-renderer-hwb-video-border-blend.profile.json"
$profileMatrix = Read-RepoText "tools\Test-NativeRendererProfileMatrix.ps1"
$parityTool = Read-RepoText "tools\check_native_renderer_property_parity.py"

foreach ($token in @(
    "mod video_projection",
    "mod video_projection_metadata",
    "mod video_projection_native_stream",
    "mod video_projection_player_bridge",
    "mod remote_camera_projection_native_stream",
    """video-projection""",
    "video_projection_settings.marker_fields()",
    "video_projection_player_bridge::start_if_enabled"
)) {
    Assert-Contains -Text $nativeLib -Needle $token -Label "native lib"
}

foreach ($token in @(
    "MediaCodec",
    "MediaExtractor",
    "Surface",
    "nativeCreateStereoVideoSurface",
    "nativeStopStereoVideoStream",
    "nativeStereoVideoLifecycleEvent",
    "decodeOnce",
    "EVENT_LOOP_RESTARTED",
    "extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)",
    "presentationOffsetUs",
    "video loop restart produced no sample",
    "releaseOutputBuffer",
    "resolvePath",
    "video/noodletest-sbs.mp4",
    "broker-rmanvid1",
    "RMANVID1",
    "readBrokerHeader",
    "nativeCreateRemoteCameraSurface",
    "nativePumpRemoteCameraImage",
    "nativeRemoteCameraLifecycleEvent",
    "MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)",
    "remote-camera-broker-inlet",
    "video-projection-playback",
    "status=start-dispatch",
    "status=start-threads",
    "normalizeBrokerPort",
    "return 0;",
    "leftEnabled=%s",
    "rightEnabled=%s",
    "singleLaneDiagnostic=%s",
    "leftPort > 0",
    "rightPort > 0",
    "status=socket-connected",
    "STREAM_READ_TIMEOUT_MS",
    "STREAM_READ_TIMEOUT_MS = 60_000",
    "streamReadTimeoutMs",
    "streamReadTimeoutMs = connectTimeoutMs",
    "BROKER_OUTPUT_STALL_WARN_MS",
    "BROKER_OUTPUT_STALL_FLUSH_MS",
    "BROKER_INPUT_STALL_WARN_MS",
    "BROKER_INPUT_STALL_FLUSH_MS",
    "BROKER_INPUT_STALL_QUEUE_PACKETS",
    "BROKER_RECONNECT_DELAY_MS",
    "status=connect-attempt",
    "status=stream-ended-reconnect",
    "status=reconnect-scheduled",
    "status=progress",
    "status=output-stall",
    "status=input-stall",
    "status=decoder-flush",
    "status=decoder-restart",
    "status=packet-read",
    "status=packet-reader-error",
    "createBrokerDecoder",
    "releaseBrokerDecoder",
    "MediaCodecList",
    "MediaCodecInfo",
    "createByCodecName",
    "chooseBrokerDecoder",
    "isSoftwareOnly",
    "decoderSelection=%s",
    "software-preferred",
    "status=decoder-created",
    "BROKER_PACKET_QUEUE_CAPACITY",
    "BROKER_PACKET_POLL_TIMEOUT_MS",
    "ArrayBlockingQueue",
    "queuedPackets",
    "queuedMinusRendered",
    "decodeBacklogPackets",
    "inputUnavailableMs",
    "packetQueueDepth",
    "droppedPackets",
    "droppedQueuedPackets",
    "decoderFlushes",
    "decoderRestarts",
    "remotePumpAttempts",
    "remotePumpFrames",
    "remotePumpLastResult",
    "packetAvailable=%s",
    "decoderInputRequested=%s",
    "decoderInputAvailable=%s",
    "packetReadIdleMs",
    "readerDroppedPackets",
    "packetReaderAlive=%s",
    "lastReadPacketSize",
    "lastReadPacketPtsUs",
    "codec.start()",
    "BROKER_INPUT_STARVATION_RECREATE_MS",
    "input-starvation",
    "cachedCodecConfigPacket",
    "isCodecConfigPacket",
    "recreate-codec-cached-config"
)) {
    Assert-Contains -Text $javaPlayback -Needle $token -Label "java playback"
}

foreach ($token in @(
    "SYNC_FRAME_REQUEST_INTERVAL_MS",
    "MediaFormat.KEY_I_FRAME_INTERVAL",
    "MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES",
    "requestSyncFrame(encoder)",
    "nextSyncRequestMs = nowMs + SYNC_FRAME_REQUEST_INTERVAL_MS",
    "cachedCodecConfigPacket",
    "replayCachedCodecConfig",
    "codec_config_cache_ready",
    "codec_config_replay_count",
    "consumer_sync_frame_request_count",
    "streamReady"
)) {
    Assert-Contains -Text $brokerSource -Needle $token -Label "broker Camera2 H264 source"
}

foreach ($token in @(
    "copyBytesRead",
    "copyBytesWritten",
    "copyReadOperations",
    "copyWriteOperations",
    """copy_bytes_read""",
    """copy_bytes_written""",
    """copy_read_operations""",
    """copy_write_operations""",
    """copy_last_read_age_ms""",
    """copy_last_write_age_ms"""
)) {
    Assert-Contains -Text $brokerSession -Needle $token -Label "broker remote camera copy telemetry"
}

foreach ($forbidden in @("ImageReader.newInstance", "getHardwareBuffer", "copyPixelsFromBuffer", "getPlanes(", "nativeStereoVideoHardwareBufferFrame")) {
    if ($javaPlayback.Contains($forbidden)) {
        throw "Stereo video playback must not use Java per-frame hardware-buffer or CPU pixel-copy token: $forbidden"
    }
}

foreach ($token in @(
    "StereoVideoPlayback",
    "start_if_enabled",
    "app-private-file",
    "broker-rmanvid1",
    "mediacodec-surface-to-ndk-aimage-reader-ahardwarebuffer",
    "rmanvid1-tcp-to-mediacodec-surface-to-ndk-aimage-reader-ahardwarebuffer",
    "remote_camera_broker_stereo",
    "manifold-broker-rmanvid1-camera2-h264"
)) {
    Assert-Contains -Text ($playerBridge + $nativeOptions) -Needle $token -Label "player bridge/options"
}

foreach ($token in @(
    "AImageReader_newWithUsage",
    "AImageReader_getWindow",
    "ANativeWindow_toSurface",
    "AImageReader_acquireLatestImage",
    "AImage_getHardwareBuffer",
    "AIMAGE_FORMAT_PRIVATE",
    "AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT",
    "stream=stereo_video",
    "loop-restarted",
    "sourceAuthority=android-mediacodec-surface-decoder",
    "highRateJsonPayload=false",
    "nativeImageReader=true",
    "javaHardwareBufferBridge=false",
    "cpuPixelCopy=false",
    "latestFramePublished=true"
)) {
    Assert-Contains -Text $nativeStream -Needle $token -Label "native stream"
}

foreach ($token in @(
    "latest_remote_stereo_frame",
    "NativeStereoCameraFrame",
    "remote-broker-single-lane-left-mirrored-right",
    "remote-broker-single-lane-right-mirrored-left",
    "AImageReader_newWithUsage",
    "AImageReader_acquireLatestImage",
    "AImage_getHardwareBuffer",
    "remote_camera_broker_stereo",
    "remote-broker-left",
    "remote-broker-right",
    "acquire_remote_camera_image",
    "java-pump",
    "imageAcquireTrigger=",
    "remote-camera-broker-latest",
    "singleLaneMirrorReady=",
    "clear_latest_for_side",
    "EVENT_ERROR",
    "EVENT_CONNECTED",
    "EVENT_STREAM_HEADER",
    "latestFrameInvalidated=",
    "staleLatestFrameGuard=true",
    "Dropping decoded AImageReader frames here can starve MediaCodec output",
    "manifold-broker-rmanvid1-camera2-h264",
    "nativeImageReader=true",
    "javaHardwareBufferBridge=false",
    "cpuPixelCopy=false",
    "remoteCameraGpuAdoptionPath=rmanvid1-mediacodec-surface-aimage-reader-ahardwarebuffer-to-vulkan-camera-projection"
)) {
    Assert-Contains -Text $remoteCameraStream -Needle $token -Label "remote camera broker native stream"
}

foreach ($token in @(
    "rusty.quest.native_renderer.video_projection_metadata.v1",
    "leftSourceUvRect",
    "rightSourceUvRect",
    "sourcePositionMode=camera-target-center-position-only",
    "leftSourcePositionOffsetUv",
    "rightSourcePositionOffsetUv",
    "videoProjectionTarget",
    "dataInputMetadataAuthority=video-projection-stream",
    "downstreamProjectionScaleAuthority=fixed-video-target"
)) {
    Assert-Contains -Text $projectionMetadata -Needle $token -Label "projection metadata"
}

foreach ($token in @(
    "VideoProjectionRenderer",
    "latest_remote_stereo_frame",
    "latest_video_projection_frame",
    "query_ahb_vulkan_import_properties",
    "import_ahb_sampled_image",
    "transition_ahb_sampled_image_to_shader_read",
    "source_position_offset_for_eye",
    "video-projection-import",
    "video-projection-resources",
    "videoProjectionRendered",
    "videoProjectionGpuImportReady=true",
    "remote-broker-camera-projection-source",
    "metadata-target-remote-broker-camera2-h264",
    "camera-projection-scorecard",
    "remoteBrokerCameraProjectionActive",
    "previousProjectionCleared=true",
    "videoProjectionExternalFormatSampling",
    "videoProjectionSamplerYcbcrConversion",
    "combined-immutable-sampler-ycbcr-conversion",
    "videoProjectionGpuAdoptionPath=android-mediacodec-surface-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image"
)) {
    Assert-Contains -Text ($videoRenderer + $xrVulkan + $scorecard) -Needle $token -Label "video renderer"
}

foreach ($token in @(
    "Get-RemoteFrameFreshness",
    "Get-ScorecardFrameFreshness",
    "Test-Qcl100RemoteBrokerProjectionScorecardLine",
    "Get-BrokerCameraSourceFreshness",
    "Get-Qcl082RelayFreshness",
    "stream_fresh_frames",
    "scorecard_fresh_frames",
    "owner_camera_source_freshness",
    "owner_relay_freshness",
    "left_frame_freshness",
    "right_frame_freshness",
    "left_scorecard_freshness",
    "right_scorecard_freshness",
    "active_lane_ahb_frames_ready",
    "active_lane_projection_imports_ready",
    "cameraProjectionPath=*remote-broker",
    "system_fatal_count",
    "nativePackagePattern",
    "source_frame_delta",
    "advancing_source_frames",
    "advanced_in_final_window",
    "recent_at_final_scorecard",
    "frame_span_seconds",
    "minimum_frame_span_seconds",
    "minimum_frame_lines",
    "sustained_frame_span",
    "MinFreshFrameSpanSeconds",
    "MinFreshFrameLines",
    "final_frame_age_seconds",
    "Get-ArtifactEvidence",
    "Resolve-Aapt2FromAdb",
    "Assert-ApkUsesPermission",
    "android.permission.INTERNET",
    "native-renderer-apk-permissions.txt",
    "installed_artifacts",
    "native_renderer_apk",
    "native_renderer_apk_permission",
    'ValidateSet("duplex", "owner-to-client", "client-to-owner")',
    'ValidateSet("stereo", "left-only", "right-only")',
    'ValidateSet("qcl041", "broker")',
    'ValidateSet("udp", "tcp", "reverse-tcp", "control-tcp", "mixed", "mixed-client-tcp")',
    "Direction",
    "LaneMode",
    "TransportOwner",
    "qcl041GroupOwnerLabel",
    "ownerQcl041Role",
    "clientQcl041Role",
    "ownerQcl041RelayReceiverHost",
    "clientQcl041RelayReceiverHost",
    "qcl041_group_owner_label",
    "qcl041_roles",
    'if ($ownerQcl041Role -eq "group_owner")',
    "LeftTransportProxyTargetPort",
    "RightTransportProxyTargetPort",
    "Get-TransportRouteSpec",
    "transport_bind_local_address",
    "manifold_broker_direct_tcp_sender_bridge_after_qcl041_group_hold",
    "qcl041_outbound_relay_to_qcl041_receive_proxy",
    "qcl041_receive_proxy_target_ports",
    "active_paths",
    "owner-to-client",
    "client-to-owner",
    "left-only",
    "right-only",
    "Qcl041Q2qNetworkName",
    "Qcl041Q2qPassphrase",
    "DIRECT-rq-QCL100",
    "qcl041.q2q_network_name",
    "qcl041.q2q_passphrase",
    "effectiveHoldAfterSocketMs",
    '($ProjectionSeconds + 45) * 1000',
    '"qcl041.hold_after_socket_ms", $effectiveHoldAfterSocketMs.ToString()',
    "left_lane_active",
    "right_lane_active",
    "active_lane_count",
    "lane_mode_property_overrides",
    "qcl100_native_renderer_lane_mode_override",
    "qcl041.qcl082_relay_enabled",
    'qcl041.qcl082_relay_enabled", "false"',
    "qcl041.qcl082_ack_pacing_enabled",
    "qcl041.qcl082_ack_chunk_bytes",
    "qcl041.qcl082_ack_soft_timeout_limit",
    "qcl041.qcl082_relay_write_stall_timeout_ms",
    "Qcl082RelayWriteStallTimeoutMs = 3000",
    "qcl041.qcl082_relay_receiver_progress_timeout_ms",
    "Qcl082RelayReceiverProgressTimeoutMs = 3000",
    "qcl041.qcl082_relay_port_rotation_count",
    "Qcl082RelayPortRotationCount = 8",
    "qcl041.qcl082_transport_protocol",
    'Qcl082TransportProtocol = "udp"',
    'Qcl082TransportProtocol -eq "mixed-client-tcp"',
    '$relayTransportProtocol = if ($Role -eq "group_owner") { "udp" } else { "tcp" }',
    '$receiveProxyTransportProtocol = if ($Role -eq "group_owner") { "tcp" } else { "udp" }',
    "Qcl082RelayStartDelayMs = 5000",
    "start_delay_ms",
    "transport_protocol",
    "write_stall_timeout_ms",
    "receiver_progress_timeout_ms",
    "port_rotation_count",
    "qcl041.qcl082_receive_proxy_enabled",
    "qcl041.qcl082_receive_proxy_lanes",
    "Qcl082ReceiveProxyPeerIdleTimeoutMs = 3000",
    "NativeRendererBrokerConnectTimeoutMs",
    "native_renderer_broker_socket",
    "broker_stream_read_timeout_ms",
    "summary_first_force_stop_cleanup",
    "AllowFailure",
    "New-Qcl100TrapNativeLogSummary",
    "native_log_summary",
    "Invoke-Qcl100FinalQcl041ArtifactRead",
    "qcl041_artifact_reads",
    "qcl041_final_artifact_read_blocked",
    "Read-Qcl100JsonIfPresent",
    "Get-Qcl100RemoteCameraRuntimeFromExecution",
    "final_status_probes",
    'if (-not $summary["freshness_acceptance"].passed)',
    "exit 2",
    "owner_receive_proxy_required",
    "client_receive_proxy_required",
    "owner_camera_source_required",
    "client_camera_source_required",
    "owner_relay_required",
    "client_relay_required",
    "owner_stream_required",
    "client_stream_required",
    "active_receiver_projection_ready",
    "freshness_acceptance",
    "transport_claims",
    "transport_capability_matrix",
    "same_group_duplex_claimed",
    "reciprocal_role_recycled_go_to_client_udp",
    "same_group_go_to_client_udp",
    "same_group_client_to_go_udp",
    "same_group_client_to_go_udp_unbound_or_default",
    "same_group_client_to_go_udp_app_bound_socket",
    "same_group_go_to_client_tcp",
    "same_group_client_to_go_tcp",
    "same_group_simultaneous_duplex",
    "reciprocal_role_recycled_go_to_client_udp_both_roles",
    "blocked_until_same_epoch_bidirectional_media_and_renderer_freshness_pass",
    "socket_level_app_bound_pass_media_not_yet_proven",
    "camera2_capture_freshness",
    "fresh_camera2_frames_observed",
    "last_packet_age_ms",
    "copy_last_byte_age_ms",
    "Get-Qcl041ArtifactReferenceUnixMs",
    "Get-Qcl041ArtifactFreshnessWaitState",
    "Invoke-Qcl100Qcl041ArtifactFreshnessWaitSelfTest",
    "RequireRelayFreshness",
    "RequireReceiveProxyFreshness",
    "qcl041_artifact_qcl082_freshness_wait",
    "owner-final-qcl041",
    "client-final-qcl041",
    "Get-Qcl082LastByteAgeMs",
    "Get-Qcl100SummaryProperty",
    "udp_socket_bound_to_wifi_direct_network",
    "relay_udp_required_usable_network_selected_network_handle",
    "copy_last_byte_age_source",
    "copy_last_byte_unix_ms",
    "reported_copy_last_byte_age_ms",
    "qcl041_artifact_freshness_reference",
    "observed_at_utc",
    "copy_bytes_read",
    "copy_bytes_written",
    "peer_socket_bound_local_address",
    "Start-Qcl100NativeRendererLogcatCapture",
    "Stop-Qcl100NativeRendererLogcatCapture",
    "live_filtered_logcat",
    "RQNativeRenderer:I",
    "native_renderer_logcat_capture",
    "FreshnessSelfTest",
    "LowerGatePlanOnly",
    "NoMediaLaunchOnly",
    "NoMediaLaunchSeconds",
    "ValidateLowerGateEvidenceOnly",
    "LowerGatePlanSummaryPath",
    "RouteClearSummaryPath",
    "Qcl041ControlTcpSummaryPath",
    "XrReadinessSummaryPath",
    "NoMediaLaunchSummaryPath",
    "qcl100_lower_gate_plan",
    "qcl100_lower_gate_evidence",
    "qcl100-lower-gate-plan.json",
    "qcl100-lower-gate-evidence.json",
    "route_clear_passive_preflight",
    "qcl041_strict_control_tcp_gate",
    "qcl100_lower_gate_evidence_validation",
    "required_before_short_media_gate",
    "qcl100_no_media_launch_gate",
    "validate_lower_gate_evidence_only",
    "no_media_launch_only",
    "qcl041_started",
    "qcl082_media_started",
    "blocked_until_lower_gates_pass",
    "Invoke-Qcl100ParityBlockerSelfTest",
    "Invoke-Qcl100LowerGatePlanSelfTest",
    "Invoke-Qcl100LowerGateEvidenceSelfTest",
    "rusty.quest.qcl100_lower_gate_evidence.v1",
    "qcl100-lower-gate-evidence-self-test.json",
    "RequireInfrastructureWifiDisconnected",
    "RequireP2p0Ipv4Cleared",
    "RequiredQcl041MatrixRunId",
    "AllowWakePrepMutation",
    "external_keep_awake_managed",
    "blocked_wake_prep_mutation_without_explicit_allow",
    "wake_prep_policy",
    'Prepare-QuestForXrFocus -Serial $OwnerSerial -Label "owner" -SkipWakePrep:$SkipWakePrep -AllowWakePrepMutation:$AllowWakePrepMutation',
    'Prepare-QuestForXrFocus -Serial $ClientSerial -Label "client" -SkipWakePrep:$SkipWakePrep -AllowWakePrepMutation:$AllowWakePrepMutation',
    "effectiveRequireQcl041MatrixGatePass",
    "qcl041MatrixRunIdPinRequiresGate",
    "qcl041MatrixGatePassesRequirement",
    '$qcl041MatrixGatePassesRequirement -and',
    "PreflightOnly",
    "XrLaunchReadinessOnly",
    "Get-Qcl100WifiStatus",
    "Get-Qcl100P2pIpv4Status",
    "airgap-preflight.json",
    "owner-p2p0-ipv4.txt",
    "client-p2p0-ipv4.txt",
    "infrastructure_wifi_airgap_preflight",
    "infrastructure_wifi_connected",
    "wifi_direct_p2p0_ipv4_preflight",
    "p2p0_ipv4_present",
    "xr_launch_readiness_preflight",
    "p2p0_ipv4_cleared",
    "blocked_preflight",
    "require_infrastructure_wifi_disconnected",
    "require_p2p0_ipv4_cleared",
    "requested_require_qcl041_matrix_gate_pass",
    "qcl041_matrix_run_id_pin_requires_gate",
    "required_qcl041_matrix_run_id",
    "qcl041_matrix_gate_required",
    "qcl041_matrix_gate_evaluated",
    "qcl041_matrix_gate_passed",
    "qcl041_matrix_gate_passes_requirement",
    "qcl041_matrix_gate_run_id",
    "qcl041_matrix_gate_transport_protocol",
    "qcl041_matrix_gate_required_topology",
    "matrix_tcp_tunnel_stream_required_bytes_per_direction",
    "matrix_client_to_owner_wifi_direct_udp_matrix_mode_present",
    "matrix_same_group_udp_duplex_media_proven_by_matrix",
    "qcl041_matrix_client_to_owner_udp_mode_absent",
    "Add-Qcl100ParityBlocker",
    "Set-Qcl100ParityBlockers",
    "New-Qcl100PreflightParityBlockers",
    "rusty.quest.qcl100_parity_blocker_self_test.v1",
    "parity_blocker_count",
    "first_parity_blocker",
    "parity_blockers",
    "qcl100_airgap_preflight",
    "infrastructure_wifi_airgap",
    "wifi_direct_p2p0_ipv4_preflight",
    "wifi_direct_candidate_route_preflight",
    "qcl041_matrix_gate",
    "owner_xr_launch_readiness",
    "client_xr_launch_readiness",
    "owner_xr_launch_not_ready",
    "client_xr_launch_not_ready",
    "readiness_only_no_media_launched",
    "owner_camera_source",
    "client_camera_source",
    "owner_qcl082_relay",
    "client_qcl082_relay",
    "owner_qcl082_receive_proxy",
    "client_qcl082_receive_proxy",
    "owner_broker_receiver_observed",
    "client_broker_receiver_observed",
    "qcl082_media_topology",
    "qcl082_media_topology_required_transport_topology",
    "qcl082_media_topology_modes",
    "qcl082_media_topology_accepted_modes",
    "qcl082_media_topology_control_tcp_media_carrier_path_count",
    "qcl082_media_topology_app_bound_udp_media_path_count",
    "qcl082_media_topology_local_p2p_udp_media_rejected_path_count",
    "qcl082_media_topology_first_issue",
    "transport_claims",
    "New-Qcl100TransportClaims",
    "same_group_duplex_claimed",
    "same_group_simultaneous_duplex",
    "same_group_app_bound_udp_duplex_claimed",
    "justified_alternate_topology_claimed",
    "same_group_duplex_proven_with_justified_control_tcp_alternate",
    "same_group_app_bound_udp_duplex_proven",
    "same_group_duplex_topology_not_accepted",
    "app_bound_udp_media_lanes_or_justified_control_tcp_media_carrier",
    "qcl082_media_topology_accepted",
    "transport-claims-unaccepted-topology-does-not-claim-duplex",
    "local_p2p_udp_media_bind_rejected",
    "owner_native_renderer_stream",
    "client_native_renderer_stream",
    "owner_native_renderer_scorecard",
    "client_native_renderer_scorecard",
    'launched = $false',
    "frozen-source-frame",
    "one-frame-at-start",
    "short-frame-burst",
    "stale-before-scorecard",
    "fresh-frames-system-fatal-blocks-projection",
    '$streamFreshFrames -and'
)) {
    Assert-Contains -Text $qcl100Runner -Needle $token -Label "QCL100 native stereo freshness gate"
}

Assert-Contains `
    -Text $brokerRmanvid1Profile `
    -Needle '"value": "60000"' `
    -Label "broker RMANVID1 native renderer stream read timeout profile"

$qcl100Path = Join-Path $RepoRoot "tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1"
$freshnessSelfTestDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-freshness-selftest-" + [guid]::NewGuid().ToString("N"))
try {
    $freshnessSelfTestOutput = & $qcl100Path -FreshnessSelfTest -OutDir $freshnessSelfTestDir 2>&1 | Out-String
    if (-not $freshnessSelfTestOutput.Contains("QCL100 freshness self-test passed.")) {
        throw "QCL100 freshness self-test did not report success. Output: $freshnessSelfTestOutput"
    }
    $freshnessSelfTestJson = Join-Path $freshnessSelfTestDir "qcl100-freshness-self-test.json"
    if (-not (Test-Path $freshnessSelfTestJson)) {
        throw "QCL100 freshness self-test did not write evidence JSON: $freshnessSelfTestJson"
    }
    $freshnessSelfTest = Get-Content -Raw -LiteralPath $freshnessSelfTestJson | ConvertFrom-Json
    if ($freshnessSelfTest.cases.Count -ne 7) {
        throw "QCL100 freshness self-test expected 7 cases but found $($freshnessSelfTest.cases.Count)."
    }
    $scorecardAuthorityCase = @($freshnessSelfTest.cases | Where-Object {
        $_.name -eq "fresh-frames-missing-rmanvid1-scorecard-authority"
    })
    if ($scorecardAuthorityCase.Count -ne 1) {
        throw "QCL100 freshness self-test did not include the RMANVID1 scorecard-authority damaged case."
    }
} finally {
    if (Test-Path $freshnessSelfTestDir) {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        $resolvedSelfTestDir = [System.IO.Path]::GetFullPath($freshnessSelfTestDir)
        if ($resolvedSelfTestDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $freshnessSelfTestDir -Recurse -Force
        }
    }
}

foreach ($token in @(
    "diagnostic_edge_tint",
    "border * 0.72 * (1.0 - stretch_active) * diagnostic_edge_tint",
    "guideProjectionEdgeTint=diagnostic-debug-only",
    "guideProjectionEdgeTintActive",
    "NativeVideoBorderBlendMode",
    "PROP_VIDEO_BORDER_BLEND_MODE",
    "videoBorderBlendMode",
    "videoBorderBlendCompositor",
    "videoBorderBlendShaderCompositeActive",
    "videoBorderBlendFormula",
    "videoBorderBlendCostTier",
    "videoBorderBlendSamplePattern",
    "videoBorderBlendTemporalState"
)) {
    Assert-Contains -Text ($guideProjectionShader + $projectionBorderStretchOptions) -Needle $token -Label "video border diagnostic edge tint"
}

foreach ($token in @(
    "u_guide",
    "u_video_projection",
    "GuideVideoProjectionPush",
    "video_source_uv_rect",
    "video_target_rect",
    "video_sample_rgb",
    "linear_to_srgb",
    "luma_matched_camera_rgb",
    "chroma_luma_split_rgb",
    "soft_light_rgb",
    "overlay_rgb",
    "screen_rgb",
    "gradient_aware_rgb",
    "two_band_rgb",
    "transition_band_weight"
)) {
    Assert-Contains -Text $guideVideoProjectionShader -Needle $token -Label "guide/video composite shader"
}

foreach ($token in @(
    "video_projection.vert.glsl",
    "video_projection.frag.glsl",
    "guide_video_projection.frag.glsl",
    "video_projection.vert.spv",
    "video_projection.frag.spv",
    "guide_video_projection.frag.spv"
)) {
    Assert-Contains -Text $nativeBuild -Needle $token -Label "shader build"
}

foreach ($token in @(
    "u_video_projection",
    "source_uv_rect",
    "target_rect",
    "flip_y",
    "source_position_offset_uv",
    "positioned_local_uv"
)) {
    Assert-Contains -Text ($vertexShader + $fragmentShader) -Needle $token -Label "shader"
}

foreach ($token in @(
    "Per-Eye Positioning",
    'Left source position offset: `0.046875,0.046875`',
    'Right source position offset: `-0.046875,0.054688`',
    "cannot bleed the left and right source halves",
    "no cyan/orange debug rim",
    "same dequeued input buffer"
)) {
    Assert-Contains -Text $videoProjectionDoc -Needle $token -Label "video projection doc"
}

foreach ($token in @(
    "v.mp4",
    '/sdcard/Android/data/$PackageName/files',
    "app_scoped_external_destination",
    "video_projection_path",
    "package-scoped-external-files",
    "max_android_property_value_length",
    "adb_serial_required",
    "device-scoped-adb",
    "rusty.quest.native_renderer.video_stage_receipt.v1",
    "broad_shared_storage_required"
)) {
    Assert-Contains -Text $stageVideo -Needle $token -Label "video staging wrapper"
}
if ($stageVideo.Contains("run-as")) {
    throw "Video staging wrapper must not require run-as; release APKs are not debuggable."
}

foreach ($propertyName in @(
    "debug.rustyquest.native_renderer.video_border_blend.mode",
    "debug.rustyquest.native_renderer.video_projection.enabled",
    "debug.rustyquest.native_renderer.video_projection.broker.connect_timeout_ms",
    "debug.rustyquest.native_renderer.video_projection.broker.host",
    "debug.rustyquest.native_renderer.video_projection.broker.left_port",
    "debug.rustyquest.native_renderer.video_projection.broker.right_port",
    "debug.rustyquest.native_renderer.video_projection.source",
    "debug.rustyquest.native_renderer.video_projection.path",
    "debug.rustyquest.native_renderer.video_projection.stereo_layout",
    "debug.rustyquest.native_renderer.video_projection.width",
    "debug.rustyquest.native_renderer.video_projection.height",
    "debug.rustyquest.native_renderer.video_projection.max_images",
    "debug.rustyquest.native_renderer.video_projection.fps_cap",
    "debug.rustyquest.native_renderer.video_projection.looping",
    "debug.rustyquest.native_renderer.video_projection.target",
    "debug.rustyquest.native_renderer.video_projection.opacity",
    "debug.rustyquest.native_renderer.video_projection.high_rate_json_payload"
)) {
    Assert-Contains -Text $nativeProperties -Needle $propertyName -Label "native property registry"
    Assert-Contains -Text $propertyManifest -Needle $propertyName -Label "property manifest"
    Assert-Contains -Text ($validProfile + $brokerRmanvid1Profile + $videoBorderBlendProfile) -Needle $propertyName -Label "valid profile"
}

foreach ($token in @(
    "native_renderer_video_projection_options.rs",
    "profile.quest.native_renderer.broker_rmanvid1_stereo_camera",
    "profile.quest.native_renderer.fullscreen_stereo_video",
    "quest-native-renderer-broker-rmanvid1-stereo-camera.profile.json",
    "quest-native-renderer-fullscreen-stereo-video.profile.json",
    "broker-rmanvid1",
    "remoteBrokerCameraProjectionActive=true",
    "videoProjectionSourceAuthority=manifold-broker-rmanvid1-camera2-h264",
    "videoProjectionTransport=rmanvid1-tcp-to-mediacodec-surface-to-ndk-aimage-reader-ahardwarebuffer",
    "side-by-side-left-right",
    "videoProjectionLeftSourceUvRect=0.000000,0.000000,0.500000,1.000000",
    "videoProjectionRightSourceUvRect=0.500000,0.000000,0.500000,1.000000",
    "videoBorderBlendMode=crossfade",
    "videoBorderBlendCompositor=guide-video-shader-composite",
    "videoBorderBlendFormula",
    "videoBorderBlendCostTier",
    "high_rate_json_payload"
)) {
    Assert-Contains -Text ($propertyManifest + $validProfile + $brokerRmanvid1Profile + $videoBorderBlendProfile + $profileMatrix + $parityTool) -Needle $token -Label "profile contract"
}

foreach ($mode in @(
    "alpha-over",
    "crossfade",
    "linear-crossfade",
    "luma-match",
    "chroma-luma",
    "soft-light",
    "overlay",
    "screen",
    "multiply",
    "gradient-aware",
    "two-band",
    "temporal-stabilized"
)) {
    Assert-Contains -Text ($projectionBorderStretchOptions + $propertyManifest + $blendSweep + $videoProjectionDoc) -Needle $mode -Label "video-border blend mode token"
}

foreach ($token in @(
    "rusty.quest.native_renderer.video_border_blend_sweep.v1",
    "Invoke-NativeRendererVideoBorderBlendSweep.ps1",
    "mode-summary.json",
    "video-border-blend-sweep-report.md",
    "Test-NativeRendererRuntimeEvidence.ps1",
    "set_properties",
    "expected_markers",
    'videoBorderBlendMode=$Mode',
    "projectionCompositeCpuMs",
    "observedOpenXrFps",
    "screenshot.png"
)) {
    Assert-Contains -Text $blendSweep -Needle $token -Label "video-border blend sweep wrapper"
}

Write-Output "Native renderer video-projection static checks passed."
