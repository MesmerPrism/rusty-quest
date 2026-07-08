# Dot-sourced helper functions for QCL100 lower-gate planning artifacts.

function Format-Qcl100LowerGateCommandToken {
    param([string]$Token)
    if ($null -eq $Token) {
        return '""'
    }
    $escaped = $Token.Replace('"', '\"')
    if ($escaped -match '\s' -or $escaped -match '[`"]') {
        return '"' + $escaped + '"'
    }
    return $escaped
}

function New-Qcl100LowerGateCommand {
    param(
        [Parameter(Mandatory=$true)]
        [string]$ScriptPath,
        [string[]]$Arguments = @()
    )
    $tokens = @(
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        $ScriptPath
    ) + @($Arguments)
    [ordered]@{
        shell = "powershell"
        script = $ScriptPath
        arguments = @($Arguments)
        command_line = (@($tokens) | ForEach-Object { Format-Qcl100LowerGateCommandToken -Token $_ }) -join " "
    }
}

function New-Qcl100LowerGateStep {
    param(
        [int]$Ordinal,
        [string]$Id,
        [string]$Status,
        [string]$Objective,
        [bool]$LaunchesQcl100Media,
        [bool]$LaunchesNativeRenderer,
        [string]$DeviceMutation,
        [object]$Command = $null,
        [string[]]$ExpectedArtifacts = @(),
        [string[]]$RequiredPassFields = @(),
        [string]$PromotionEffect = "does_not_promote_qcl100"
    )
    [ordered]@{
        ordinal = $Ordinal
        id = $Id
        status = $Status
        objective = $Objective
        launches_qcl100_media = $LaunchesQcl100Media
        launches_native_renderer = $LaunchesNativeRenderer
        device_mutation = $DeviceMutation
        command = $Command
        expected_artifacts = @($ExpectedArtifacts)
        required_pass_fields = @($RequiredPassFields)
        promotion_effect = $PromotionEffect
    }
}

function Get-Qcl100LowerGateAuthority {
    param([string]$Authority)
    $normalized = ([string]$Authority).Trim().ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        return "android_connectivitymanager_network"
    }
    if ($normalized -eq "rusty-direct-p2p-socket-authority" -or
            $normalized -eq "rusty_direct_network_authority" -or
            $normalized -eq "rusty-direct-network-authority") {
        return "rusty_direct_p2p_socket_authority"
    }
    if ($normalized -ne "android_connectivitymanager_network" -and
            $normalized -ne "rusty_direct_p2p_socket_authority") {
        throw "Unknown QCL100 lower-gate authority: $Authority"
    }
    return $normalized
}

function New-Qcl100LowerGatePlan {
    param(
        [string]$RunId,
        [string]$OutDir,
        [string]$OwnerSerial,
        [string]$ClientSerial,
        [string]$OwnerWifiDirectAddress,
        [string]$ClientWifiDirectAddress,
        [string]$Qcl041Q2qNetworkName,
        [string]$Qcl041Q2qPassphrase,
        [string]$Direction,
        [string]$LaneMode,
        [string]$Qcl082TransportProtocol,
        [string]$TransportOwner = "qcl041",
        [int]$ProjectionSeconds,
        [int]$NoMediaLaunchSeconds,
        [int]$Qcl082ControlTcpMediaStreamBytesPerDirection,
        [string]$RequiredQcl041MatrixSummaryPath,
        [string]$RequiredQcl041MatrixRunId,
        [int]$MaxQcl041MatrixGateAgeSeconds,
        [string]$Qcl100LowerGateAuthority = "android_connectivitymanager_network",
        [string]$Qcl100ScriptPath,
        [string]$Qcl041MatrixScriptPath
    )
    $acceptedLowerGateAuthority = Get-Qcl100LowerGateAuthority -Authority $Qcl100LowerGateAuthority
    $acceptedTransportOwner = ([string]$TransportOwner).Trim().ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($acceptedTransportOwner)) {
        $acceptedTransportOwner = "qcl041"
    }
    if ($acceptedTransportOwner -ne "qcl041" -and $acceptedTransportOwner -ne "broker") {
        throw "Unknown QCL100 transport owner: $TransportOwner"
    }
    $rustyDirectP2pAuthority = [bool]($acceptedLowerGateAuthority -eq "rusty_direct_p2p_socket_authority")
    $diagnosticShortMediaDirection = if ($Direction -eq "client-to-owner") { "client-to-owner" } else { "owner-to-client" }
    $qcl041GroupOwnerLabel = if ($diagnosticShortMediaDirection -eq "client-to-owner") { "client" } else { "owner" }
    $targetRoot = Split-Path -Parent $OutDir
    if ([string]::IsNullOrWhiteSpace($targetRoot)) {
        $targetRoot = "S:\Work\repos\active\rusty-quest\target"
    }
    $strictPreflightRunId = "$RunId-route-clear-preflight"
    $controlTcpGateRunId = "$RunId-qcl041-control-tcp-gate"
    $xrReadinessRunId = "$RunId-xr-readiness"
    $noMediaRunId = "$RunId-no-media-launch"
    $lowerGateEvidenceRunId = "$RunId-lower-gate-evidence"
    $shortMediaRunId = "$RunId-control-tcp-short-media"
    $controlTcpGateOutDir = Join-Path $targetRoot $controlTcpGateRunId
    $controlTcpGateSummaryPath = Join-Path $controlTcpGateOutDir "summary.json"
    $planSummaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
    $routeClearOutDir = Join-Path $targetRoot $strictPreflightRunId
    $routeClearSummaryPath = Join-Path $routeClearOutDir "native-stereo-projection-summary.json"
    $xrReadinessOutDir = Join-Path $targetRoot $xrReadinessRunId
    $xrReadinessSummaryPath = Join-Path $xrReadinessOutDir "native-stereo-projection-summary.json"
    $noMediaOutDir = Join-Path $targetRoot $noMediaRunId
    $noMediaSummaryPath = Join-Path $noMediaOutDir "native-stereo-projection-summary.json"
    $lowerGateEvidenceOutDir = Join-Path $targetRoot $lowerGateEvidenceRunId
    $lowerGateEvidencePath = Join-Path $lowerGateEvidenceOutDir "qcl100-lower-gate-evidence.json"
    $effectiveMatrixSummaryPath = if ([string]::IsNullOrWhiteSpace($RequiredQcl041MatrixSummaryPath)) {
        $controlTcpGateSummaryPath
    } else {
        $RequiredQcl041MatrixSummaryPath
    }
    $effectiveMatrixRunId = if ([string]::IsNullOrWhiteSpace($RequiredQcl041MatrixRunId)) {
        $controlTcpGateRunId
    } else {
        $RequiredQcl041MatrixRunId
    }
    $strictPreflightArgs = @(
        "-RunId", $strictPreflightRunId,
        "-OutDir", $routeClearOutDir,
        "-OwnerSerial", $OwnerSerial,
        "-ClientSerial", $ClientSerial,
        "-OwnerWifiDirectAddress", $OwnerWifiDirectAddress,
        "-ClientWifiDirectAddress", $ClientWifiDirectAddress,
        "-Qcl041Q2qNetworkName", $Qcl041Q2qNetworkName,
        "-Qcl041Q2qPassphrase", $Qcl041Q2qPassphrase,
        "-TransportOwner", $acceptedTransportOwner,
        "-Qcl082TransportProtocol", "control-tcp",
        "-PreflightOnly",
        "-RequireInfrastructureWifiDisconnected",
        "-RequireP2p0Ipv4Cleared",
        "-RequireCandidateWifiDirectRoutesClear"
    )
    $controlTcpBindingVariants = if ($rustyDirectP2pAuthority) {
        "udp_local_p2p_bind_echo,tcp_local_p2p_bind_socket,tcp_local_p2p_bind_stream_socket"
    } else {
        "tcp_socket_factory,tcp_network_bind_socket,tcp_process_bound,tcp_native_fd_network_bound"
    }
    $controlTcpGateArgs = @(
        "-RunId", $controlTcpGateRunId,
        "-OutDir", $controlTcpGateOutDir,
        "-OwnerSerial", $OwnerSerial,
        "-ClientSerial", $ClientSerial,
        "-OwnerWifiDirectAddress", $OwnerWifiDirectAddress,
        "-ClientWifiDirectAddress", $ClientWifiDirectAddress,
        "-Qcl041Q2qNetworkName", $Qcl041Q2qNetworkName,
        "-Qcl041Q2qPassphrase", $Qcl041Q2qPassphrase,
        "-Qcl041GroupOwnerLabel", $qcl041GroupOwnerLabel,
        "-Qcl100ControlTcpGate",
        "-RequireInfrastructureWifiDisconnected",
        "-RequireP2p0Ipv4Cleared",
        "-RequireCandidateWifiDirectRoutesClear",
        "-RequireTcpTunnelStreamPass",
        "-AppNetworkTrace",
        "-Qcl100LowerGateAuthority", $acceptedLowerGateAuthority,
        "-TcpBindingVariants", $controlTcpBindingVariants
    )
    if ($rustyDirectP2pAuthority) {
        $controlTcpGateArgs += @(
            "-AppNetworkTraceOnly",
            "-TcpTunnelStreamBytesPerDirection", "1048576",
            "-TcpTunnelStreamSeconds", "5"
        )
    }
    $xrReadinessArgs = @(
        "-RunId", $xrReadinessRunId,
        "-OutDir", $xrReadinessOutDir,
        "-OwnerSerial", $OwnerSerial,
        "-ClientSerial", $ClientSerial,
        "-TransportOwner", $acceptedTransportOwner,
        "-Qcl082TransportProtocol", "control-tcp",
        "-XrLaunchReadinessOnly",
        "-RequireQcl041MatrixGatePass",
        "-RequiredQcl041MatrixSummaryPath", $effectiveMatrixSummaryPath,
        "-RequiredQcl041MatrixRunId", $effectiveMatrixRunId,
        "-Qcl100LowerGateAuthority", $acceptedLowerGateAuthority,
        "-MaxQcl041MatrixGateAgeSeconds", ([Math]::Max(0, $MaxQcl041MatrixGateAgeSeconds)).ToString()
    )
    $noMediaArgs = @(
        "-RunId", $noMediaRunId,
        "-OutDir", $noMediaOutDir,
        "-OwnerSerial", $OwnerSerial,
        "-ClientSerial", $ClientSerial,
        "-TransportOwner", $acceptedTransportOwner,
        "-Qcl082TransportProtocol", "control-tcp",
        "-NoMediaLaunchOnly",
        "-NoMediaLaunchSeconds", ([Math]::Max(1, $NoMediaLaunchSeconds)).ToString(),
        "-RequireQcl041MatrixGatePass",
        "-RequiredQcl041MatrixSummaryPath", $effectiveMatrixSummaryPath,
        "-RequiredQcl041MatrixRunId", $effectiveMatrixRunId,
        "-Qcl100LowerGateAuthority", $acceptedLowerGateAuthority,
        "-MaxQcl041MatrixGateAgeSeconds", ([Math]::Max(0, $MaxQcl041MatrixGateAgeSeconds)).ToString()
    )
    $lowerGateEvidenceArgs = @(
        "-RunId", $lowerGateEvidenceRunId,
        "-OutDir", $lowerGateEvidenceOutDir,
        "-ValidateLowerGateEvidenceOnly",
        "-Qcl100LowerGateAuthority", $acceptedLowerGateAuthority,
        "-LowerGatePlanSummaryPath", $planSummaryPath,
        "-RouteClearSummaryPath", $routeClearSummaryPath,
        "-Qcl041ControlTcpSummaryPath", $controlTcpGateSummaryPath,
        "-XrReadinessSummaryPath", $xrReadinessSummaryPath,
        "-NoMediaLaunchSummaryPath", $noMediaSummaryPath
    )
    if ($rustyDirectP2pAuthority) {
        $lowerGateEvidenceArgs += @("-RequireQcl041TcpTunnelStreamPass")
    } else {
        $lowerGateEvidenceArgs += @(
            "-RequireQcl041ClientP2pNetworkCallbackSeen",
            "-RequireQcl041ClientP2pNetworkSocketAuthority",
            "-RequireQcl041StrictUdpDatagramEchoPass",
            "-RequireQcl041TcpTunnelStreamPass"
        )
    }
    $shortMediaBytes = if ($Qcl082ControlTcpMediaStreamBytesPerDirection -gt 0) {
        [Math]::Min($Qcl082ControlTcpMediaStreamBytesPerDirection, 262144)
    } else {
        65536
    }
    $shortMediaSeconds = [Math]::Min([Math]::Max(5, $ProjectionSeconds), 12)
    $shortMediaMinFreshFrameSpanSeconds = [Math]::Min(8, [Math]::Max(3, $shortMediaSeconds - 4))
    $shortMediaArgs = @(
        "-RunId", $shortMediaRunId,
        "-OutDir", (Join-Path $targetRoot $shortMediaRunId),
        "-OwnerSerial", $OwnerSerial,
        "-ClientSerial", $ClientSerial,
        "-OwnerWifiDirectAddress", $OwnerWifiDirectAddress,
        "-ClientWifiDirectAddress", $ClientWifiDirectAddress,
        "-Qcl041Q2qNetworkName", $Qcl041Q2qNetworkName,
        "-Qcl041Q2qPassphrase", $Qcl041Q2qPassphrase,
        "-Direction", $diagnosticShortMediaDirection,
        "-LaneMode", "left-only",
        "-TransportOwner", $acceptedTransportOwner,
        "-Qcl082TransportProtocol", "control-tcp",
        "-ProjectionSeconds", $shortMediaSeconds.ToString(),
        "-MinFreshFrameSpanSeconds", $shortMediaMinFreshFrameSpanSeconds.ToString(),
        "-Qcl082ControlTcpMediaStreamBytesPerDirection", $shortMediaBytes.ToString(),
        "-RequireQcl041MatrixGatePass",
        "-RequiredQcl041MatrixSummaryPath", $effectiveMatrixSummaryPath,
        "-RequiredQcl041MatrixRunId", $effectiveMatrixRunId,
        "-Qcl100LowerGateAuthority", $acceptedLowerGateAuthority,
        "-MaxQcl041MatrixGateAgeSeconds", ([Math]::Max(0, $MaxQcl041MatrixGateAgeSeconds)).ToString()
    )
    $controlTcpRequiredPassFields = if ($rustyDirectP2pAuthority) {
        @(
            "status=pass",
            "qcl100_control_tcp_gate=true",
            "matrix_focus=qcl100_control_tcp_gate",
            "app_network_trace_enabled=true",
            "require_tcp_tunnel_stream_pass=true",
            "matrix.qcl041_local_p2p_bind_stream_authority=diagnostic_pass",
            "matrix.group_client_artifact_label",
            "matrix.client_p2p_interface_local_bind_socket_authority=network_interface_local_p2p_address_bind",
            "matrix.client_p2p_interface_local_bind_udp_pass=true",
            "matrix.client_p2p_interface_local_bind_tcp_pass=true",
            "matrix.local_p2p_bind_tcp_stream_pass=true",
            "matrix.local_p2p_bind_tcp_stream_sender_to_receiver_rx_bytes>=1048576",
            "matrix.local_p2p_bind_tcp_stream_receiver_to_sender_rx_bytes>=1048576")
    } else {
        @(
            "status=pass",
            "qcl100_control_tcp_gate=true",
            "matrix_focus=qcl100_control_tcp_gate",
            "app_network_trace_enabled=true",
            "require_tcp_tunnel_stream_pass=true",
            "matrix.client_p2p_network_callback_seen=true",
            "matrix.client_p2p_network_visible_app=true",
            "matrix.client_p2p_network_link_properties_present=true",
            "matrix.client_p2p_network_route_matches_group_owner=true",
            "matrix.client_p2p_network_socket_authority_pass=true",
            "matrix.udp_network_bound_receiver_observed_packets>0",
            "matrix.tcp_tunnel_stream_bidirectional_bytes_pass=true")
    }
    $controlTcpStepStatus = if ($rustyDirectP2pAuthority) {
        "required_before_qcl100_no_media"
    } else {
        "required_before_qcl100_media"
    }
    $controlTcpStepObjective = if ($rustyDirectP2pAuthority) {
        "Run the QCL041 same-group Rusty direct p2p0 socket matrix gate and require receiver-observed local UDP, TCP, and bidirectional TCP stream bytes."
    } else {
        "Run the QCL041 same-group control-TCP matrix gate and require sustained receiver-observed bidirectional TCP tunnel stream bytes."
    }
    $shortMediaStatus = if ($rustyDirectP2pAuthority) {
        "allowed_after_lower_gates_pass_diagnostic_not_promoting"
    } else {
        "allowed_after_lower_gates_pass"
    }
    $shortMediaObjective = if ($rustyDirectP2pAuthority) {
        "Run a left-only, short-duration $diagnosticShortMediaDirection broker-direct Rusty p2p socket diagnostic after route, QCL041, XR readiness, and no-media lower gates are clean."
    } else {
        "Run a left-only, short-duration $diagnosticShortMediaDirection control-TCP diagnostic media path after route, QCL041, and XR readiness gates are clean."
    }
    $directP2pSenderAuthorityRequiredField = if ($diagnosticShortMediaDirection -eq "client-to-owner") {
        "freshness_acceptance.client_direct_p2p_sender_authority_accepted=true"
    } else {
        "freshness_acceptance.owner_direct_p2p_sender_authority_accepted=true"
    }
    $directP2pReceiverObservedRequiredField = if ($diagnosticShortMediaDirection -eq "client-to-owner") {
        "freshness_acceptance.owner_broker_receiver_observed_bytes_fresh=true"
    } else {
        "freshness_acceptance.client_broker_receiver_observed_bytes_fresh=true"
    }
    $shortMediaRequiredPassFields = if ($rustyDirectP2pAuthority) {
        @(
            "freshness_acceptance.passed=true",
            "freshness_acceptance.qcl082_media_topology_required=false",
            "direct_p2p_media_ready=true",
            "direct_p2p_native_projection_ready=true",
            $directP2pSenderAuthorityRequiredField,
            $directP2pReceiverObservedRequiredField,
            "same_group_duplex_claimed=false",
            "native_log_summary.system_fatal_count=0")
    } else {
        @(
            "freshness_acceptance.passed=true",
            "qcl082_media_topology_accepted=true",
            "native_log_summary.system_fatal_count=0")
    }
    [ordered]@{
        schema = "rusty.quest.qcl100_lower_gate_plan.v1"
        run_id = $RunId
        status = "planned_not_promoted"
        mode = "lower_gate_plan_only"
        generated_at_utc = (Get-Date).ToUniversalTime().ToString("o")
        non_live_artifact = $true
        launched = $false
        device_mutation_performed = $false
        promotion_allowed = $false
        same_group_duplex_claimed = $false
        requested_direction = $Direction
        diagnostic_short_media_direction = $diagnosticShortMediaDirection
        qcl041_group_owner_label = $qcl041GroupOwnerLabel
        requested_lane_mode = $LaneMode
        requested_qcl082_transport_protocol = $Qcl082TransportProtocol
        requested_transport_owner = $acceptedTransportOwner
        diagnostic_transport_protocol = "control-tcp"
        accepted_lower_gate_authority = $acceptedLowerGateAuthority
        authority_tracks = [ordered]@{
            qcl041_local_p2p_bind_stream_authority = $(if ($rustyDirectP2pAuthority) { "accepted_for_rusty_owned_socket_lower_gate_not_promoting" } else { "diagnostic_only_not_promoting" })
            rusty_direct_p2p_socket_authority = $(if ($rustyDirectP2pAuthority) { "accepted_for_no_media_lower_gate_not_promoting" } else { "available_as_explicit_alternate_only" })
            qcl100_android_network_authority = $(if ($rustyDirectP2pAuthority) { "not_required_for_rusty_direct_socket_lower_gate" } else { "required_for_current_lower_gate" })
            qcl100_same_group_simultaneous_native_render = "not_promoted"
        }
        alternate_authority_candidates = @(
            [ordered]@{
                id = "qcl041_local_p2p_bind_stream_authority"
                status = $(if ($rustyDirectP2pAuthority) { "accepted_under_rusty_direct_p2p_socket_authority_not_promoting" } else { "candidate_not_promoting" })
                observed_capability = "strict local p2p0 app-bound UDP/TCP/TCP-stream sockets can move bytes without a ConnectivityManager Network handle"
                required_redesign_before_promotion = @(
                    "new lower-gate contract distinct from android_connectivitymanager_network",
                    "strict route-clear and stale-p2p0-clear preflight",
                    "WifiP2pInfo.groupFormed and WifiP2pGroup interface p2p evidence",
                    "receiver-observed local p2p0 UDP/TCP stream bytes and peer-source checks",
                    "cleanup/readback classification",
                    "promotion_allowed=false and same_group_duplex_claimed=false until QCL100 media/render gates are rerun under the redesigned authority"
                )
                accepted_scope = $(if ($rustyDirectP2pAuthority) { "Rusty-owned sockets and bridge transports that can explicitly bind a source address on p2p0" } else { "none in default Android authority mode" })
            }
        )
        owner_serial = $OwnerSerial
        client_serial = $ClientSerial
        owner_wifi_direct_address = $OwnerWifiDirectAddress
        client_wifi_direct_address = $ClientWifiDirectAddress
        qcl041_q2q_network_name = $Qcl041Q2qNetworkName
        required_qcl041_matrix_summary_path = $effectiveMatrixSummaryPath
        required_qcl041_matrix_run_id = $effectiveMatrixRunId
        max_qcl041_matrix_gate_age_seconds = [Math]::Max(0, $MaxQcl041MatrixGateAgeSeconds)
        lower_gate_sequence = @(
            (New-Qcl100LowerGateStep `
                -Ordinal 1 `
                -Id "route_clear_passive_preflight" `
                -Status "required_before_group_launch" `
                -Objective "Prove infrastructure Wi-Fi is disconnected, stale p2p0 IPv4 is absent, and candidate Wi-Fi Direct routes are clear before forming a same-group epoch." `
                -LaunchesQcl100Media $false `
                -LaunchesNativeRenderer $false `
                -DeviceMutation "none: serial-scoped passive shell reads only" `
                -Command (New-Qcl100LowerGateCommand -ScriptPath $Qcl100ScriptPath -Arguments $strictPreflightArgs) `
                -ExpectedArtifacts @($routeClearSummaryPath, (Join-Path $routeClearOutDir "airgap-preflight.json")) `
                -RequiredPassFields @("status=preflight_only", "preflight.infrastructure_wifi_disconnected=true", "preflight.p2p0_ipv4_cleared=true", "preflight.candidate_wifi_direct_prelaunch_routes_clear=true"))
            (New-Qcl100LowerGateStep `
                -Ordinal 2 `
                -Id "qcl041_strict_control_tcp_gate" `
                -Status $controlTcpStepStatus `
                -Objective $controlTcpStepObjective `
                -LaunchesQcl100Media $false `
                -LaunchesNativeRenderer $false `
                -DeviceMutation "forms and tears down a QCL041 Wi-Fi Direct group on the two serial-scoped devices" `
                -Command (New-Qcl100LowerGateCommand -ScriptPath $Qcl041MatrixScriptPath -Arguments $controlTcpGateArgs) `
                -ExpectedArtifacts @($controlTcpGateSummaryPath) `
                -RequiredPassFields $controlTcpRequiredPassFields)
            (New-Qcl100LowerGateStep `
                -Ordinal 3 `
                -Id "qcl100_xr_readiness_gate" `
                -Status "required_before_native_launch" `
                -Objective "Verify both headsets remain XR-launch ready while pinned to the fresh QCL041 control-TCP gate artifact." `
                -LaunchesQcl100Media $false `
                -LaunchesNativeRenderer $false `
                -DeviceMutation "serial-scoped readiness probes only" `
                -Command (New-Qcl100LowerGateCommand -ScriptPath $Qcl100ScriptPath -Arguments $xrReadinessArgs) `
                -ExpectedArtifacts @($xrReadinessSummaryPath) `
                -RequiredPassFields @("status=pass", "mode=xr_launch_readiness_only", "qcl041_matrix_gate_passes_requirement=true"))
            (New-Qcl100LowerGateStep `
                -Ordinal 4 `
                -Id "qcl100_no_media_launch_gate" `
                -Status "available_after_wake_policy_and_lower_gates_pass" `
                -Objective "Launch broker and native renderer without QCL041 relays or QCL082 media before interpreting any media/render failure as topology evidence." `
                -LaunchesQcl100Media $false `
                -LaunchesNativeRenderer $true `
                -DeviceMutation "launches broker/native-renderer apps and requires an explicit wake policy when executed" `
                -Command (New-Qcl100LowerGateCommand -ScriptPath $Qcl100ScriptPath -Arguments $noMediaArgs) `
                -ExpectedArtifacts @($noMediaSummaryPath) `
                -RequiredPassFields @("status=pass", "mode=no_media_launch_only", "qcl041_started=false", "qcl082_media_started=false", "native_log_summary.system_fatal_count=0", "cleanup_policy.final_force_stop_cleanup_skipped=false"))
            (New-Qcl100LowerGateStep `
                -Ordinal 5 `
                -Id "qcl100_lower_gate_evidence_validation" `
                -Status "required_before_short_media_gate" `
                -Objective "Validate the route-clear, QCL041 control-TCP, XR readiness, and no-media artifacts as a complete non-promoting lower-gate evidence bundle." `
                -LaunchesQcl100Media $false `
                -LaunchesNativeRenderer $false `
                -DeviceMutation "none: reads lower-gate JSON artifacts only" `
                -Command (New-Qcl100LowerGateCommand -ScriptPath $Qcl100ScriptPath -Arguments $lowerGateEvidenceArgs) `
                -ExpectedArtifacts @((Join-Path $lowerGateEvidenceOutDir "native-stereo-projection-summary.json"), $lowerGateEvidencePath) `
                -RequiredPassFields @("status=lower_gate_evidence_validated", "lower_gate_evidence.passed=true", "promotion_allowed=false", "same_group_duplex_claimed=false"))
            (New-Qcl100LowerGateStep `
                -Ordinal 6 `
                -Id "qcl100_short_control_tcp_media_gate" `
                -Status $shortMediaStatus `
                -Objective $shortMediaObjective `
                -LaunchesQcl100Media $true `
                -LaunchesNativeRenderer $true `
                -DeviceMutation $(if ($rustyDirectP2pAuthority) { "launches QCL041, broker, native renderer, and short broker-direct Rusty p2p socket media; remains non-promoting" } else { "launches QCL041, broker, native renderer, and short QCL082 control-TCP media" }) `
                -Command (New-Qcl100LowerGateCommand -ScriptPath $Qcl100ScriptPath -Arguments $shortMediaArgs) `
                -ExpectedArtifacts @((Join-Path (Join-Path $targetRoot $shortMediaRunId) "native-stereo-projection-summary.json")) `
                -RequiredPassFields $shortMediaRequiredPassFields)
            (New-Qcl100LowerGateStep `
                -Ordinal 7 `
                -Id "qcl100_full_parity_promotion_attempt" `
                -Status "blocked_until_lower_gates_pass" `
                -Objective "Promote same-group simultaneous native render parity only after accepted topology, receiver-observed media bytes, final-window scorecards, cleanup, and zero native/system fatal lines." `
                -LaunchesQcl100Media $true `
                -LaunchesNativeRenderer $true `
                -DeviceMutation "not permitted by this plan-only artifact" `
                -ExpectedArtifacts @("native-stereo-projection-summary.json", "live-filtered-logcat.txt", "owner-qcl041.json", "client-qcl041.json") `
                -RequiredPassFields @("same_group_duplex_claimed=true", "transport_claims.same_group_duplex_claimed=true", "freshness_acceptance.final_window_passed=true", "native_log_summary.system_fatal_count=0", "cleanup_policy.final_force_stop_cleanup_skipped=false") `
                -PromotionEffect "may_promote_qcl100_only_if_all_lower_gates_and_final_requirements_pass")
        )
        promotion_requirements = @(
            "accepted same-group topology",
            "receiver-observed broker/media bytes on both required directions",
            "final-window native renderer scorecards on required receivers",
            "same_group_duplex_claimed=true only for a valid same-group simultaneous topology",
            "cleanup evidence",
            "zero native/system fatal lines"
        )
        deferred_full_promotion_reason = "Lower route, QCL041 control-TCP, XR/no-media, and short media crash-isolation gates must pass before a full QCL100 promotion attempt."
        evidence_dir = $OutDir
    }
}

function Invoke-Qcl100LowerGatePlanSelfTest {
    param([string]$OutputDirectory = $OutDir)
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $OutputDirectory = Join-Path $env:TEMP "qcl100-lower-gate-plan-selftest"
    }
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $plan = New-Qcl100LowerGatePlan `
        -RunId "qcl100-lower-gate-plan-selftest" `
        -OutDir $OutputDirectory `
        -OwnerSerial "OWNER" `
        -ClientSerial "CLIENT" `
        -OwnerWifiDirectAddress "192.168.49.1" `
        -ClientWifiDirectAddress "192.168.49.46" `
        -Qcl041Q2qNetworkName "DIRECT-rq-QCL100" `
        -Qcl041Q2qPassphrase "RustyQcl100Pass" `
        -Direction "duplex" `
        -LaneMode "stereo" `
        -Qcl082TransportProtocol "udp" `
        -ProjectionSeconds 30 `
        -NoMediaLaunchSeconds 8 `
        -Qcl082ControlTcpMediaStreamBytesPerDirection 0 `
        -RequiredQcl041MatrixSummaryPath "" `
        -RequiredQcl041MatrixRunId "" `
        -MaxQcl041MatrixGateAgeSeconds 1800 `
        -Qcl100ScriptPath "tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1" `
        -Qcl041MatrixScriptPath "tools\Invoke-Qcl041QuestToQuestAppBoundSocketMatrix.ps1"
    if ($plan.schema -ne "rusty.quest.qcl100_lower_gate_plan.v1") {
        throw "QCL100 lower-gate plan self-test expected schema rusty.quest.qcl100_lower_gate_plan.v1."
    }
    if ([bool]$plan.promotion_allowed -or [bool]$plan.same_group_duplex_claimed) {
        throw "QCL100 lower-gate plan self-test expected plan-only artifact not to allow promotion or claim duplex."
    }
    if ($plan.accepted_lower_gate_authority -ne "android_connectivitymanager_network" -or
            $plan.authority_tracks.qcl041_local_p2p_bind_stream_authority -ne "diagnostic_only_not_promoting" -or
            $plan.authority_tracks.qcl100_android_network_authority -ne "required_for_current_lower_gate" -or
            $plan.authority_tracks.qcl100_same_group_simultaneous_native_render -ne "not_promoted") {
        throw "QCL100 lower-gate plan self-test expected explicit non-promoting local-p2p0 authority labels."
    }
    $candidate = @($plan.alternate_authority_candidates | Where-Object { $_.id -eq "qcl041_local_p2p_bind_stream_authority" } | Select-Object -First 1)
    if ($candidate.Count -eq 0 -or $candidate[0].status -ne "candidate_not_promoting") {
        throw "QCL100 lower-gate plan self-test expected local p2p0 stream authority to remain candidate_not_promoting."
    }
    if (@($plan.lower_gate_sequence).Count -ne 7) {
        throw "QCL100 lower-gate plan self-test expected seven ordered lower-gate steps."
    }
    $routeStep = $plan.lower_gate_sequence[0]
    if ($routeStep.id -ne "route_clear_passive_preflight" -or @($routeStep.command.arguments) -notcontains "-PreflightOnly" -or @($routeStep.command.arguments) -notcontains "-RequireCandidateWifiDirectRoutesClear") {
        throw "QCL100 lower-gate plan self-test expected route-clear passive preflight command."
    }
    $controlTcpStep = $plan.lower_gate_sequence[1]
    if ($controlTcpStep.id -ne "qcl041_strict_control_tcp_gate" -or @($controlTcpStep.command.arguments) -notcontains "-Qcl100ControlTcpGate" -or @($controlTcpStep.command.arguments) -notcontains "-RequireTcpTunnelStreamPass" -or @($controlTcpStep.command.arguments) -notcontains "-AppNetworkTrace") {
        throw "QCL100 lower-gate plan self-test expected strict QCL041 control-TCP gate command."
    }
    $controlTcpArgs = @($controlTcpStep.command.arguments)
    $qcl041GroupOwnerArgIndex = [array]::IndexOf($controlTcpArgs, "-Qcl041GroupOwnerLabel")
    if ($qcl041GroupOwnerArgIndex -lt 0 -or ($qcl041GroupOwnerArgIndex + 1) -ge $controlTcpArgs.Count -or [string]$controlTcpArgs[$qcl041GroupOwnerArgIndex + 1] -ne "owner") {
        throw "QCL100 lower-gate plan self-test expected default control-TCP gate to preserve owner as the QCL041 group owner."
    }
    $tcpVariantArgIndex = [array]::IndexOf($controlTcpArgs, "-TcpBindingVariants")
    $expectedTcpVariants = "tcp_socket_factory,tcp_network_bind_socket,tcp_process_bound,tcp_native_fd_network_bound"
    if ($tcpVariantArgIndex -lt 0 -or ($tcpVariantArgIndex + 1) -ge $controlTcpArgs.Count -or [string]$controlTcpArgs[$tcpVariantArgIndex + 1] -ne $expectedTcpVariants) {
        throw "QCL100 lower-gate plan self-test expected the full ordered QCL041 TCP binding variant ladder: $expectedTcpVariants."
    }
    foreach ($requiredField in @("matrix.client_p2p_network_callback_seen=true", "matrix.client_p2p_network_socket_authority_pass=true", "matrix.udp_network_bound_receiver_observed_packets>0")) {
        if (@($controlTcpStep.required_pass_fields) -notcontains $requiredField) {
            throw "QCL100 lower-gate plan self-test expected strict QCL041 control-TCP field: $requiredField."
        }
    }
    $noMediaStep = $plan.lower_gate_sequence[3]
    if ($noMediaStep.status -ne "available_after_wake_policy_and_lower_gates_pass" -or -not [bool]$noMediaStep.launches_native_renderer -or @($noMediaStep.command.arguments) -notcontains "-NoMediaLaunchOnly") {
        throw "QCL100 lower-gate plan self-test expected executable no-media launch gate to remain explicit."
    }
    $noMediaArgs = @($noMediaStep.command.arguments)
    $transportOwnerArgIndex = [array]::IndexOf($noMediaArgs, "-TransportOwner")
    if ($transportOwnerArgIndex -lt 0 -or ($transportOwnerArgIndex + 1) -ge $noMediaArgs.Count -or [string]$noMediaArgs[$transportOwnerArgIndex + 1] -ne "qcl041") {
        throw "QCL100 lower-gate plan self-test expected no-media command to preserve the default qcl041 transport owner."
    }
    $evidenceStep = $plan.lower_gate_sequence[4]
    if ($evidenceStep.id -ne "qcl100_lower_gate_evidence_validation" -or $evidenceStep.status -ne "required_before_short_media_gate" -or @($evidenceStep.command.arguments) -notcontains "-ValidateLowerGateEvidenceOnly" -or @($evidenceStep.command.arguments) -notcontains "-RequireQcl041StrictUdpDatagramEchoPass") {
        throw "QCL100 lower-gate plan self-test expected lower-gate evidence validation command before short media."
    }
    $fullParityStep = $plan.lower_gate_sequence[6]
    if ($fullParityStep.status -ne "blocked_until_lower_gates_pass" -or $fullParityStep.promotion_effect -ne "may_promote_qcl100_only_if_all_lower_gates_and_final_requirements_pass") {
        throw "QCL100 lower-gate plan self-test expected full parity promotion to remain blocked by lower gates."
    }
    $selfTest = [ordered]@{
        schema = "rusty.quest.qcl100_lower_gate_plan_self_test.v1"
        plan = $plan
        passed = $true
    }
    $rustyDirectPlan = New-Qcl100LowerGatePlan `
        -RunId "qcl100-lower-gate-plan-rusty-direct-selftest" `
        -OutDir (Join-Path $OutputDirectory "rusty-direct") `
        -OwnerSerial "OWNER" `
        -ClientSerial "CLIENT" `
        -OwnerWifiDirectAddress "192.168.49.1" `
        -ClientWifiDirectAddress "192.168.49.46" `
        -Qcl041Q2qNetworkName "DIRECT-rq-QCL100" `
        -Qcl041Q2qPassphrase "RustyQcl100Pass" `
        -Direction "duplex" `
        -LaneMode "stereo" `
        -Qcl082TransportProtocol "control-tcp" `
        -ProjectionSeconds 30 `
        -NoMediaLaunchSeconds 8 `
        -Qcl082ControlTcpMediaStreamBytesPerDirection 0 `
        -RequiredQcl041MatrixSummaryPath "" `
        -RequiredQcl041MatrixRunId "" `
        -MaxQcl041MatrixGateAgeSeconds 1800 `
        -TransportOwner "broker" `
        -Qcl100LowerGateAuthority "rusty_direct_p2p_socket_authority" `
        -Qcl100ScriptPath "tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1" `
        -Qcl041MatrixScriptPath "tools\Invoke-Qcl041QuestToQuestAppBoundSocketMatrix.ps1"
    if ($rustyDirectPlan.accepted_lower_gate_authority -ne "rusty_direct_p2p_socket_authority") {
        throw "QCL100 lower-gate plan self-test expected Rusty direct authority mode to name rusty_direct_p2p_socket_authority."
    }
    if ([bool]$rustyDirectPlan.promotion_allowed -or [bool]$rustyDirectPlan.same_group_duplex_claimed) {
        throw "QCL100 lower-gate plan self-test expected Rusty direct authority mode to remain non-promoting."
    }
    if ($rustyDirectPlan.authority_tracks.rusty_direct_p2p_socket_authority -ne "accepted_for_no_media_lower_gate_not_promoting" -or
            $rustyDirectPlan.authority_tracks.qcl100_android_network_authority -ne "not_required_for_rusty_direct_socket_lower_gate") {
        throw "QCL100 lower-gate plan self-test expected Rusty direct authority labels."
    }
    $rustyControlTcpStep = $rustyDirectPlan.lower_gate_sequence[1]
    $rustyArgs = @($rustyControlTcpStep.command.arguments)
    $rustyVariantArgIndex = [array]::IndexOf($rustyArgs, "-TcpBindingVariants")
    $expectedRustyVariants = "udp_local_p2p_bind_echo,tcp_local_p2p_bind_socket,tcp_local_p2p_bind_stream_socket"
    if ($rustyVariantArgIndex -lt 0 -or ($rustyVariantArgIndex + 1) -ge $rustyArgs.Count -or [string]$rustyArgs[$rustyVariantArgIndex + 1] -ne $expectedRustyVariants) {
        throw "QCL100 lower-gate plan self-test expected Rusty direct QCL041 TCP binding variants: $expectedRustyVariants."
    }
    if (@($rustyArgs) -notcontains "-AppNetworkTraceOnly") {
        throw "QCL100 lower-gate plan self-test expected Rusty direct QCL041 gate to run local-bind trace-only variants."
    }
    foreach ($requiredField in @("matrix.client_p2p_interface_local_bind_socket_authority=network_interface_local_p2p_address_bind", "matrix.local_p2p_bind_tcp_stream_sender_to_receiver_rx_bytes>=1048576")) {
        if (@($rustyControlTcpStep.required_pass_fields) -notcontains $requiredField) {
            throw "QCL100 lower-gate plan self-test expected Rusty direct QCL041 field: $requiredField."
        }
    }
    $rustyTcpBytesArgIndex = [array]::IndexOf($rustyArgs, "-TcpTunnelStreamBytesPerDirection")
    if ($rustyTcpBytesArgIndex -lt 0 -or ($rustyTcpBytesArgIndex + 1) -ge $rustyArgs.Count -or [string]$rustyArgs[$rustyTcpBytesArgIndex + 1] -ne "1048576") {
        throw "QCL100 lower-gate plan self-test expected Rusty direct QCL041 TCP stream bytes to use the 1 MiB lower-gate contract."
    }
    $rustyShortMediaStep = $rustyDirectPlan.lower_gate_sequence[5]
    if ($rustyShortMediaStep.status -ne "allowed_after_lower_gates_pass_diagnostic_not_promoting" -or
            -not [bool]$rustyShortMediaStep.launches_qcl100_media -or
            -not [bool]$rustyShortMediaStep.launches_native_renderer) {
        throw "QCL100 lower-gate plan self-test expected Rusty direct short media to be an allowed non-promoting diagnostic after lower gates."
    }
    if (@($rustyShortMediaStep.required_pass_fields) -notcontains "direct_p2p_media_ready=true" -or
            @($rustyShortMediaStep.required_pass_fields) -notcontains "freshness_acceptance.qcl082_media_topology_required=false") {
        throw "QCL100 lower-gate plan self-test expected Rusty direct short media to require direct-p2p media evidence, not QCL041 media topology."
    }
    $rustyShortMediaArgs = @($rustyShortMediaStep.command.arguments)
    $rustyShortMediaDirectionArgIndex = [array]::IndexOf($rustyShortMediaArgs, "-Direction")
    if ($rustyShortMediaDirectionArgIndex -lt 0 -or ($rustyShortMediaDirectionArgIndex + 1) -ge $rustyShortMediaArgs.Count -or [string]$rustyShortMediaArgs[$rustyShortMediaDirectionArgIndex + 1] -ne "owner-to-client") {
        throw "QCL100 lower-gate plan self-test expected duplex/default Rusty direct short media to stay scoped to owner-to-client."
    }
    $minFreshArgIndex = [array]::IndexOf($rustyShortMediaArgs, "-MinFreshFrameSpanSeconds")
    if ($minFreshArgIndex -lt 0 -or ($minFreshArgIndex + 1) -ge $rustyShortMediaArgs.Count -or [double]$rustyShortMediaArgs[$minFreshArgIndex + 1] -gt 8.0) {
        throw "QCL100 lower-gate plan self-test expected Rusty direct short media to set a feasible MinFreshFrameSpanSeconds value."
    }
    if ($rustyDirectPlan.requested_transport_owner -ne "broker") {
        throw "QCL100 lower-gate plan self-test expected Rusty direct broker transport owner to be recorded."
    }
    foreach ($stepIndex in @(0, 2, 3, 5)) {
        $stepArgs = @($rustyDirectPlan.lower_gate_sequence[$stepIndex].command.arguments)
        $brokerTransportOwnerArgIndex = [array]::IndexOf($stepArgs, "-TransportOwner")
        if ($brokerTransportOwnerArgIndex -lt 0 -or ($brokerTransportOwnerArgIndex + 1) -ge $stepArgs.Count -or [string]$stepArgs[$brokerTransportOwnerArgIndex + 1] -ne "broker") {
            throw "QCL100 lower-gate plan self-test expected Rusty direct step $stepIndex command to preserve broker transport owner."
        }
    }
    $rustyReversePlan = New-Qcl100LowerGatePlan `
        -RunId "qcl100-lower-gate-plan-rusty-direct-reverse-selftest" `
        -OutDir (Join-Path $OutputDirectory "rusty-direct-reverse") `
        -OwnerSerial "OWNER" `
        -ClientSerial "CLIENT" `
        -OwnerWifiDirectAddress "192.168.49.1" `
        -ClientWifiDirectAddress "192.168.49.46" `
        -Qcl041Q2qNetworkName "DIRECT-rq-QCL100" `
        -Qcl041Q2qPassphrase "RustyQcl100Pass" `
        -Direction "client-to-owner" `
        -LaneMode "left-only" `
        -Qcl082TransportProtocol "control-tcp" `
        -ProjectionSeconds 12 `
        -NoMediaLaunchSeconds 8 `
        -Qcl082ControlTcpMediaStreamBytesPerDirection 65536 `
        -RequiredQcl041MatrixSummaryPath "" `
        -RequiredQcl041MatrixRunId "" `
        -MaxQcl041MatrixGateAgeSeconds 1800 `
        -TransportOwner "broker" `
        -Qcl100LowerGateAuthority "rusty_direct_p2p_socket_authority" `
        -Qcl100ScriptPath "tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1" `
        -Qcl041MatrixScriptPath "tools\Invoke-Qcl041QuestToQuestAppBoundSocketMatrix.ps1"
    if ($rustyReversePlan.diagnostic_short_media_direction -ne "client-to-owner" -or $rustyReversePlan.qcl041_group_owner_label -ne "client") {
        throw "QCL100 lower-gate plan self-test expected reverse Rusty direct diagnostics to use client-to-owner media and client QCL041 group owner."
    }
    $rustyReverseControlArgs = @($rustyReversePlan.lower_gate_sequence[1].command.arguments)
    $rustyReverseGroupOwnerArgIndex = [array]::IndexOf($rustyReverseControlArgs, "-Qcl041GroupOwnerLabel")
    if ($rustyReverseGroupOwnerArgIndex -lt 0 -or ($rustyReverseGroupOwnerArgIndex + 1) -ge $rustyReverseControlArgs.Count -or [string]$rustyReverseControlArgs[$rustyReverseGroupOwnerArgIndex + 1] -ne "client") {
        throw "QCL100 lower-gate plan self-test expected reverse Rusty direct QCL041 gate to launch client as group owner."
    }
    $rustyReverseShortStep = $rustyReversePlan.lower_gate_sequence[5]
    $rustyReverseShortArgs = @($rustyReverseShortStep.command.arguments)
    $rustyReverseDirectionArgIndex = [array]::IndexOf($rustyReverseShortArgs, "-Direction")
    if ($rustyReverseDirectionArgIndex -lt 0 -or ($rustyReverseDirectionArgIndex + 1) -ge $rustyReverseShortArgs.Count -or [string]$rustyReverseShortArgs[$rustyReverseDirectionArgIndex + 1] -ne "client-to-owner") {
        throw "QCL100 lower-gate plan self-test expected reverse Rusty direct short media to use client-to-owner."
    }
    foreach ($requiredField in @("freshness_acceptance.client_direct_p2p_sender_authority_accepted=true", "freshness_acceptance.owner_broker_receiver_observed_bytes_fresh=true")) {
        if (@($rustyReverseShortStep.required_pass_fields) -notcontains $requiredField) {
            throw "QCL100 lower-gate plan self-test expected reverse Rusty direct short media field: $requiredField."
        }
    }
    $selfTest["rusty_direct_reverse_plan"] = $rustyReversePlan
    $selfTest["rusty_direct_plan"] = $rustyDirectPlan
    $selfTestPath = Join-Path $OutputDirectory "qcl100-lower-gate-plan-self-test.json"
    $selfTestParent = Split-Path -Parent $selfTestPath
    if (-not [string]::IsNullOrWhiteSpace($selfTestParent)) {
        New-Item -ItemType Directory -Force -Path $selfTestParent | Out-Null
    }
    $selfTest | ConvertTo-Json -Depth 24 | Set-Content -Path $selfTestPath -Encoding UTF8
    return $selfTest
}
