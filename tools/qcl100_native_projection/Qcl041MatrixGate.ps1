# Dot-sourced helper functions for QCL100's required QCL041 matrix evidence gate.

function Get-Qcl100MatrixGateProperty {
    param(
        $Object,
        [string]$Name
    )
    if ($null -eq $Object -or [string]::IsNullOrWhiteSpace($Name)) {
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Get-Qcl100MatrixGateBool {
    param(
        $Object,
        [string]$Name
    )
    $value = Get-Qcl100MatrixGateProperty -Object $Object -Name $Name
    if ($null -eq $value) {
        return $false
    }
    if ($value -is [bool]) {
        return [bool]$value
    }
    $text = [string]$value
    if ($text.Equals("true", [System.StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    if ($text.Equals("false", [System.StringComparison]::OrdinalIgnoreCase)) {
        return $false
    }
    return [bool]$value
}

function New-Qcl100Qcl041MatrixGateIssue {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Code,
        [Parameter(Mandatory=$true)]
        [string]$Message
    )
    [ordered]@{
        code = $Code
        message = $Message
    }
}

function Get-Qcl100Qcl041MatrixGateTransportRequirement {
    param([string]$Qcl082TransportProtocol)
    $normalized = ([string]$Qcl082TransportProtocol).Trim().ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        return [ordered]@{
            protocol = ""
            topology = "unspecified"
            supported = $true
            require_app_bound_udp = $false
            require_control_tcp_stream = $false
        }
    }
    if ($normalized -eq "udp") {
        return [ordered]@{
            protocol = $normalized
            topology = "app_bound_udp"
            supported = $true
            require_app_bound_udp = $true
            require_control_tcp_stream = $false
        }
    }
    if ($normalized -eq "control-tcp") {
        return [ordered]@{
            protocol = $normalized
            topology = "control_tcp_tunnel_stream"
            supported = $true
            require_app_bound_udp = $false
            require_control_tcp_stream = $true
        }
    }
    return [ordered]@{
        protocol = $normalized
        topology = "unsupported_qcl100_media_topology"
        supported = $false
        require_app_bound_udp = $false
        require_control_tcp_stream = $false
    }
}

function ConvertTo-Qcl100MatrixGateModeList {
    param($Modes)
    $modeList = @()
    foreach ($mode in @($Modes)) {
        $text = ([string]$mode).Trim()
        if (-not [string]::IsNullOrWhiteSpace($text)) {
            $modeList += $text
        }
    }
    return $modeList
}

function Test-Qcl100MatrixGateModePresent {
    param(
        $Modes,
        [string[]]$AcceptedModes
    )
    foreach ($mode in @(ConvertTo-Qcl100MatrixGateModeList -Modes $Modes)) {
        if ($AcceptedModes -contains $mode) {
            return $true
        }
    }
    return $false
}

function Get-Qcl100Qcl041MatrixGateEvidence {
    param(
        [string]$Path,
        [string]$ExpectedOwnerSerial = "",
        [string]$ExpectedClientSerial = "",
        [string]$ExpectedRunId = "",
        [string]$Qcl082TransportProtocol = "",
        [int]$MaxAgeSeconds = 0,
        [switch]$RequireFresh
    )

    $issues = @()
    $resolvedPath = ""
    $summary = $null
    $present = $false
    $parsed = $false
    $artifactLastWriteUtc = ""
    $artifactAgeSeconds = $null
    $artifactAgeSource = ""
    $artifactFreshEnough = $true
    $artifactAgeLimitSeconds = [Math]::Max(0, $MaxAgeSeconds)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        $issues += New-Qcl100Qcl041MatrixGateIssue `
            -Code "qcl041_matrix_summary_path_missing" `
            -Message "A strict QCL041 matrix summary path is required before QCL100 media/render launch."
    } elseif (-not (Test-Path -LiteralPath $Path)) {
        $issues += New-Qcl100Qcl041MatrixGateIssue `
            -Code "qcl041_matrix_summary_missing" `
            -Message "The required QCL041 matrix summary does not exist: $Path"
    } else {
        $present = $true
        $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
        $item = Get-Item -LiteralPath $resolvedPath
        $lastWriteUtc = $item.LastWriteTimeUtc
        $artifactLastWriteUtc = $lastWriteUtc.ToString("o")
        $artifactAgeSource = "file_last_write_time_utc"
        $artifactAgeSeconds = [int][Math]::Floor([Math]::Max(0.0, ((Get-Date).ToUniversalTime() - $lastWriteUtc).TotalSeconds))
        if ($artifactAgeLimitSeconds -gt 0) {
            $artifactFreshEnough = [bool]($artifactAgeSeconds -le $artifactAgeLimitSeconds)
        }
        try {
            $summary = Get-Content -Raw -LiteralPath $resolvedPath | ConvertFrom-Json
            $parsed = $true
        } catch {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_summary_parse_failed" `
                -Message $_.Exception.Message
        }
    }

    $preflight = Get-Qcl100MatrixGateProperty -Object $summary -Name "preflight"
    $matrix = Get-Qcl100MatrixGateProperty -Object $summary -Name "matrix"
    $status = [string](Get-Qcl100MatrixGateProperty -Object $summary -Name "status")
    $blockedReason = [string](Get-Qcl100MatrixGateProperty -Object $summary -Name "blocked_reason")
    $matrixRunId = [string](Get-Qcl100MatrixGateProperty -Object $summary -Name "run_id")
    $ownerSerial = [string](Get-Qcl100MatrixGateProperty -Object $summary -Name "owner_serial")
    $clientSerial = [string](Get-Qcl100MatrixGateProperty -Object $summary -Name "client_serial")
    $launched = Get-Qcl100MatrixGateBool -Object $summary -Name "launched"
    $requireInfrastructureWifiDisconnected = Get-Qcl100MatrixGateBool -Object $summary -Name "require_infrastructure_wifi_disconnected"
    $requireP2p0Ipv4Cleared = Get-Qcl100MatrixGateBool -Object $summary -Name "require_p2p0_ipv4_cleared"
    $requireCandidateWifiDirectRoutesClear = Get-Qcl100MatrixGateBool -Object $summary -Name "require_candidate_wifi_direct_routes_clear"
    $requireTcpTunnelStreamPass = Get-Qcl100MatrixGateBool -Object $summary -Name "require_tcp_tunnel_stream_pass"
    $matrixFocus = [string](Get-Qcl100MatrixGateProperty -Object $summary -Name "matrix_focus")
    $qcl100ControlTcpGate = Get-Qcl100MatrixGateBool -Object $summary -Name "qcl100_control_tcp_gate"
    $delayedUdpRequired = Get-Qcl100MatrixGateBool -Object $summary -Name "delayed_udp_required"
    $wholeMatrixCompletionRequired = Get-Qcl100MatrixGateBool -Object $summary -Name "whole_matrix_completion_required"
    $requestedDelayedUdpDelaySeconds = Get-Qcl100MatrixGateProperty -Object $summary -Name "requested_delayed_udp_delay_seconds"
    $delayedUdpDelaySeconds = Get-Qcl100MatrixGateProperty -Object $summary -Name "delayed_udp_delay_seconds"
    $tcpTunnelStreamBytesPerDirection = Get-Qcl100MatrixGateProperty -Object $summary -Name "tcp_tunnel_stream_bytes_per_direction"
    $matrixTcpTunnelStreamConfiguredBytesPerDirection = Get-Qcl100MatrixGateProperty -Object $matrix -Name "tcp_tunnel_stream_configured_bytes_per_direction"
    $matrixTcpTunnelStreamRequiredBytesPerDirection = Get-Qcl100MatrixGateProperty -Object $matrix -Name "tcp_tunnel_stream_required_bytes_per_direction"
    $infrastructureWifiDisconnected = Get-Qcl100MatrixGateBool -Object $preflight -Name "infrastructure_wifi_disconnected"
    $p2p0Ipv4Cleared = Get-Qcl100MatrixGateBool -Object $preflight -Name "p2p0_ipv4_cleared"
    $candidateRoutesClear = Get-Qcl100MatrixGateBool -Object $preflight -Name "candidate_wifi_direct_prelaunch_routes_clear"
    $ownerMatrixComplete = Get-Qcl100MatrixGateBool -Object $matrix -Name "owner_matrix_complete"
    $clientMatrixComplete = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_matrix_complete"
    $ownerMatrixLastCheckpoint = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "owner_matrix_last_checkpoint")
    $clientMatrixLastCheckpoint = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_matrix_last_checkpoint")
    $receiverObservedBytes = Get-Qcl100MatrixGateBool -Object $matrix -Name "receiver_observed_bytes"
    $tcpTunnelStreamBidirectionalBytesPass = Get-Qcl100MatrixGateBool -Object $matrix -Name "tcp_tunnel_stream_bidirectional_bytes_pass"
    $receiverObservedUdpModes = ConvertTo-Qcl100MatrixGateModeList -Modes (Get-Qcl100MatrixGateProperty -Object $matrix -Name "receiver_observed_udp_modes")
    $receiverObservedTcpModes = ConvertTo-Qcl100MatrixGateModeList -Modes (Get-Qcl100MatrixGateProperty -Object $matrix -Name "receiver_observed_tcp_modes")
    $clientToOwnerUdpEvidenceScope = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_to_owner_udp_evidence_scope")
    $matrixClientToOwnerWifiDirectUdpModePresent = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_to_owner_wifi_direct_udp_matrix_mode_pass"
    $clientToOwnerWifiDirectUdpReceiverObservedModes = ConvertTo-Qcl100MatrixGateModeList -Modes (Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_to_owner_wifi_direct_udp_receiver_observed_modes")
    $matrixClientToOwnerAppBoundUdpSocketPass = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_to_owner_app_bound_udp_socket_pass"
    $clientToOwnerAppBoundUdpReceiverObservedModes = ConvertTo-Qcl100MatrixGateModeList -Modes (Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_to_owner_app_bound_udp_receiver_observed_modes")
    $matrixSameGroupUdpDuplexMediaProvenByMatrix = Get-Qcl100MatrixGateBool -Object $matrix -Name "same_group_udp_duplex_media_proven_by_matrix"
    $sameGroupUdpDuplexMediaProofRequired = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "same_group_udp_duplex_media_proof_required")
    $transportRequirement = Get-Qcl100Qcl041MatrixGateTransportRequirement -Qcl082TransportProtocol $Qcl082TransportProtocol
    $acceptedUdpMatrixModes = @(
        "udp_network_bound",
        "udp_source_and_network_bound",
        "udp_native_fd_network_bound",
        "udp_process_bound",
        "early_bound_delayed_udp_network_bound",
        "early_bound_delayed_udp_source_and_network_bound",
        "delayed_udp_network_bound",
        "delayed_udp_source_and_network_bound",
        "delayed_udp_native_fd_network_bound",
        "delayed_udp_process_bound"
    )
    $acceptedTcpMatrixModes = @("tcp_tunnel_stream_socket")
    $matrixAppBoundUdpModePresent = Test-Qcl100MatrixGateModePresent -Modes $receiverObservedUdpModes -AcceptedModes $acceptedUdpMatrixModes
    $matrixControlTcpStreamModePresent = Test-Qcl100MatrixGateModePresent -Modes $receiverObservedTcpModes -AcceptedModes $acceptedTcpMatrixModes

    if ($parsed) {
        if (-not [string]::IsNullOrWhiteSpace($ExpectedRunId)) {
            if ([string]::IsNullOrWhiteSpace($matrixRunId)) {
                $issues += New-Qcl100Qcl041MatrixGateIssue `
                    -Code "qcl041_matrix_run_id_missing" `
                    -Message "QCL041 matrix summary did not report run_id."
            } elseif (-not $matrixRunId.Equals($ExpectedRunId, [System.StringComparison]::Ordinal)) {
                $issues += New-Qcl100Qcl041MatrixGateIssue `
                    -Code "qcl041_matrix_run_id_mismatch" `
                    -Message "QCL041 matrix run_id '$matrixRunId' does not match expected run_id '$ExpectedRunId'."
            }
        }
        if (-not [string]::IsNullOrWhiteSpace($ExpectedOwnerSerial)) {
            if ([string]::IsNullOrWhiteSpace($ownerSerial)) {
                $issues += New-Qcl100Qcl041MatrixGateIssue `
                    -Code "qcl041_matrix_owner_serial_missing" `
                    -Message "QCL041 matrix summary did not report owner_serial."
            } elseif (-not $ownerSerial.Equals($ExpectedOwnerSerial, [System.StringComparison]::OrdinalIgnoreCase)) {
                $issues += New-Qcl100Qcl041MatrixGateIssue `
                    -Code "qcl041_matrix_owner_serial_mismatch" `
                    -Message "QCL041 matrix owner_serial '$ownerSerial' does not match expected owner '$ExpectedOwnerSerial'."
            }
        }
        if (-not [string]::IsNullOrWhiteSpace($ExpectedClientSerial)) {
            if ([string]::IsNullOrWhiteSpace($clientSerial)) {
                $issues += New-Qcl100Qcl041MatrixGateIssue `
                    -Code "qcl041_matrix_client_serial_missing" `
                    -Message "QCL041 matrix summary did not report client_serial."
            } elseif (-not $clientSerial.Equals($ExpectedClientSerial, [System.StringComparison]::OrdinalIgnoreCase)) {
                $issues += New-Qcl100Qcl041MatrixGateIssue `
                    -Code "qcl041_matrix_client_serial_mismatch" `
                    -Message "QCL041 matrix client_serial '$clientSerial' does not match expected client '$ExpectedClientSerial'."
            }
        }
        if ($RequireFresh -and $artifactAgeLimitSeconds -gt 0 -and -not $artifactFreshEnough) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_summary_stale" `
                -Message "QCL041 matrix summary is $artifactAgeSeconds seconds old, which exceeds the $artifactAgeLimitSeconds second freshness limit."
        }
        if ($status -ne "pass") {
            $detail = if ([string]::IsNullOrWhiteSpace($blockedReason)) { $status } else { $blockedReason }
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_status_not_pass" `
                -Message "QCL041 matrix summary did not pass: $detail"
        }
        if (-not $launched) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_not_launched" `
                -Message "QCL041 matrix summary did not launch the synthetic socket matrix."
        }
        if (-not $requireInfrastructureWifiDisconnected) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_missing_infrastructure_wifi_gate" `
                -Message "QCL041 matrix summary was not run with RequireInfrastructureWifiDisconnected."
        }
        if (-not $requireP2p0Ipv4Cleared) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_missing_p2p0_clear_gate" `
                -Message "QCL041 matrix summary was not run with RequireP2p0Ipv4Cleared."
        }
        if (-not $requireCandidateWifiDirectRoutesClear) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_missing_candidate_route_clear_gate" `
                -Message "QCL041 matrix summary was not run with RequireCandidateWifiDirectRoutesClear."
        }
        if (-not $requireTcpTunnelStreamPass) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_missing_tcp_tunnel_stream_gate" `
                -Message "QCL041 matrix summary was not run with RequireTcpTunnelStreamPass."
        }
        if (-not $infrastructureWifiDisconnected) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_infrastructure_wifi_not_clear" `
                -Message "QCL041 matrix preflight did not prove ordinary Wi-Fi disconnected."
        }
        if (-not $p2p0Ipv4Cleared) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_p2p0_not_clear_before_launch" `
                -Message "QCL041 matrix preflight did not prove stale p2p0 IPv4 was cleared before launch."
        }
        if (-not $candidateRoutesClear) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_candidate_routes_not_clear" `
                -Message "QCL041 matrix preflight did not prove candidate Wi-Fi Direct routes were clear before launch."
        }
        if (-not $receiverObservedBytes) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_receiver_observed_bytes_absent" `
                -Message "QCL041 matrix did not report receiver-observed UDP or TCP bytes."
        }
        if (-not $tcpTunnelStreamBidirectionalBytesPass) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_tcp_tunnel_stream_not_bidirectional" `
                -Message "QCL041 matrix did not report bidirectional sustained TCP tunnel stream bytes."
        }
        if (-not [bool]$transportRequirement.supported) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_qcl100_transport_not_supported" `
                -Message "QCL041 matrix gate only supports QCL100 UDP app-bound media or control-tcp tunnel media; '$($transportRequirement.protocol)' is not an accepted QCL100 media topology."
        }
        if ([bool]$transportRequirement.require_app_bound_udp -and -not $matrixClientToOwnerWifiDirectUdpModePresent) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_client_to_owner_udp_mode_absent" `
                -Message "QCL100 UDP media requires explicit QCL041 client-to-owner receiver-observed Wi-Fi Direct UDP matrix evidence."
        }
        if ([bool]$transportRequirement.require_control_tcp_stream -and -not $matrixControlTcpStreamModePresent) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_matrix_control_tcp_stream_mode_absent" `
                -Message "QCL100 control-tcp media requires the QCL041 receiver-observed tcp_tunnel_stream_socket matrix mode."
        }
    }

    $firstIssue = if ($issues.Count -gt 0) { $issues[0].code } else { "" }
    [ordered]@{
        schema = "rusty.quest.qcl100_qcl041_matrix_gate.v1"
        artifact_path = $Path
        resolved_artifact_path = $resolvedPath
        artifact_present = $present
        artifact_last_write_utc = $artifactLastWriteUtc
        artifact_age_seconds = $artifactAgeSeconds
        artifact_age_source = $artifactAgeSource
        artifact_age_limit_seconds = $artifactAgeLimitSeconds
        artifact_fresh_enough = $artifactFreshEnough
        require_fresh_artifact = [bool]$RequireFresh
        parsed = $parsed
        qcl082_transport_protocol = $transportRequirement.protocol
        required_qcl100_media_topology = $transportRequirement.topology
        qcl100_media_topology_supported_by_gate = [bool]$transportRequirement.supported
        accepted_udp_matrix_modes = $acceptedUdpMatrixModes
        accepted_tcp_matrix_modes = $acceptedTcpMatrixModes
        expected_run_id = $ExpectedRunId
        run_id = $matrixRunId
        status = $status
        blocked_reason = $blockedReason
        expected_owner_serial = $ExpectedOwnerSerial
        expected_client_serial = $ExpectedClientSerial
        owner_serial = $ownerSerial
        client_serial = $clientSerial
        launched = $launched
        require_infrastructure_wifi_disconnected = $requireInfrastructureWifiDisconnected
        require_p2p0_ipv4_cleared = $requireP2p0Ipv4Cleared
        require_candidate_wifi_direct_routes_clear = $requireCandidateWifiDirectRoutesClear
        require_tcp_tunnel_stream_pass = $requireTcpTunnelStreamPass
        matrix_focus = $matrixFocus
        qcl100_control_tcp_gate = $qcl100ControlTcpGate
        delayed_udp_required = $delayedUdpRequired
        whole_matrix_completion_required = $wholeMatrixCompletionRequired
        requested_delayed_udp_delay_seconds = $requestedDelayedUdpDelaySeconds
        delayed_udp_delay_seconds = $delayedUdpDelaySeconds
        tcp_tunnel_stream_bytes_per_direction = $tcpTunnelStreamBytesPerDirection
        matrix_tcp_tunnel_stream_configured_bytes_per_direction = $matrixTcpTunnelStreamConfiguredBytesPerDirection
        matrix_tcp_tunnel_stream_required_bytes_per_direction = $matrixTcpTunnelStreamRequiredBytesPerDirection
        preflight_infrastructure_wifi_disconnected = $infrastructureWifiDisconnected
        preflight_p2p0_ipv4_cleared = $p2p0Ipv4Cleared
        preflight_candidate_wifi_direct_routes_clear = $candidateRoutesClear
        owner_matrix_complete = $ownerMatrixComplete
        client_matrix_complete = $clientMatrixComplete
        owner_matrix_last_checkpoint = $ownerMatrixLastCheckpoint
        client_matrix_last_checkpoint = $clientMatrixLastCheckpoint
        receiver_observed_bytes = $receiverObservedBytes
        tcp_tunnel_stream_bidirectional_bytes_pass = $tcpTunnelStreamBidirectionalBytesPass
        receiver_observed_udp_modes = $receiverObservedUdpModes
        receiver_observed_tcp_modes = $receiverObservedTcpModes
        matrix_app_bound_udp_mode_present = $matrixAppBoundUdpModePresent
        matrix_client_to_owner_udp_evidence_scope = $clientToOwnerUdpEvidenceScope
        matrix_client_to_owner_wifi_direct_udp_matrix_mode_present = $matrixClientToOwnerWifiDirectUdpModePresent
        matrix_client_to_owner_wifi_direct_udp_receiver_observed_modes = $clientToOwnerWifiDirectUdpReceiverObservedModes
        matrix_client_to_owner_app_bound_udp_socket_pass = $matrixClientToOwnerAppBoundUdpSocketPass
        matrix_client_to_owner_app_bound_udp_receiver_observed_modes = $clientToOwnerAppBoundUdpReceiverObservedModes
        matrix_same_group_udp_duplex_media_proven_by_matrix = $matrixSameGroupUdpDuplexMediaProvenByMatrix
        matrix_same_group_udp_duplex_media_proof_required = $sameGroupUdpDuplexMediaProofRequired
        matrix_control_tcp_stream_mode_present = $matrixControlTcpStreamModePresent
        issues = $issues
        blocked_reason_for_qcl100 = $firstIssue
        passed = [bool]($issues.Count -eq 0)
    }
}

function New-Qcl100Qcl041MatrixGateSelfTestSummary {
    param(
        [bool]$Pass,
        [string]$OwnerSerial = "340YC10G7T0JBW",
        [string]$ClientSerial = "3487C10H3M017Q",
        [string]$RunId = "",
        [bool]$ReceiverObservedBytes = $Pass,
        [bool]$TcpTunnelStreamBidirectionalBytesPass = $Pass,
        [string[]]$ReceiverObservedUdpModes = @("udp_network_bound"),
        [string[]]$ReceiverObservedTcpModes = @("tcp_tunnel_stream_socket"),
        [bool]$ClientToOwnerWifiDirectUdpMatrixModePass = $Pass,
        [bool]$ClientToOwnerAppBoundUdpSocketPass = $Pass
    )
    [ordered]@{
        schema = "rusty.quest.qcl041_q2q_app_bound_socket_matrix_run.v1"
        run_id = if (-not [string]::IsNullOrWhiteSpace($RunId)) { $RunId } elseif ($Pass) { "qcl041-matrix-gate-selftest-pass" } else { "qcl041-matrix-gate-selftest-fail" }
        status = "pass"
        launched = $true
        owner_serial = $OwnerSerial
        client_serial = $ClientSerial
        require_infrastructure_wifi_disconnected = $true
        require_p2p0_ipv4_cleared = $true
        require_candidate_wifi_direct_routes_clear = $true
        require_tcp_tunnel_stream_pass = $true
        preflight = [ordered]@{
            infrastructure_wifi_disconnected = $true
            p2p0_ipv4_cleared = $true
            candidate_wifi_direct_prelaunch_routes_clear = $true
        }
        matrix = [ordered]@{
            receiver_observed_bytes = $ReceiverObservedBytes
            tcp_tunnel_stream_bidirectional_bytes_pass = $TcpTunnelStreamBidirectionalBytesPass
            receiver_observed_udp_modes = @($ReceiverObservedUdpModes)
            receiver_observed_tcp_modes = @($ReceiverObservedTcpModes)
            client_to_owner_udp_evidence_scope = "client_sender_to_group_owner_receiver"
            client_to_owner_wifi_direct_udp_matrix_mode_pass = $ClientToOwnerWifiDirectUdpMatrixModePass
            client_to_owner_wifi_direct_udp_receiver_observed_modes = @($ReceiverObservedUdpModes)
            client_to_owner_app_bound_udp_socket_pass = $ClientToOwnerAppBoundUdpSocketPass
            client_to_owner_app_bound_udp_receiver_observed_modes = @($ReceiverObservedUdpModes)
            same_group_udp_duplex_media_proven_by_matrix = $false
            same_group_udp_duplex_media_proof_required = "qcl100_same_epoch_final_window_media_and_renderer_freshness"
        }
    }
}

function Invoke-Qcl100Qcl041MatrixGateSelfTest {
    param([string]$OutputDirectory = $OutDir)
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $OutputDirectory = Join-Path $env:TEMP "qcl100-qcl041-matrix-gate-selftest"
    }
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $passPath = Join-Path $OutputDirectory "qcl041-matrix-gate-selftest-pass-summary.json"
    $failPath = Join-Path $OutputDirectory "qcl041-matrix-gate-selftest-fail-summary.json"
    $missingRunIdPath = Join-Path $OutputDirectory "qcl041-matrix-gate-selftest-missing-run-id-summary.json"
    $wrongRunIdPath = Join-Path $OutputDirectory "qcl041-matrix-gate-selftest-wrong-run-id-summary.json"
    $wrongClientPath = Join-Path $OutputDirectory "qcl041-matrix-gate-selftest-wrong-client-summary.json"
    $stalePath = Join-Path $OutputDirectory "qcl041-matrix-gate-selftest-stale-summary.json"
    $udpMissingPath = Join-Path $OutputDirectory "qcl041-matrix-gate-selftest-udp-mode-missing-summary.json"
    $controlTcpPath = Join-Path $OutputDirectory "qcl041-matrix-gate-selftest-control-tcp-summary.json"
    $controlTcpGateIncompleteMatrixPath = Join-Path $OutputDirectory "qcl041-matrix-gate-selftest-control-tcp-gate-incomplete-matrix-summary.json"
    $unsupportedTransportPath = Join-Path $OutputDirectory "qcl041-matrix-gate-selftest-unsupported-transport-summary.json"
    $expectedOwnerSerial = "340YC10G7T0JBW"
    $expectedClientSerial = "3487C10H3M017Q"
    $expectedMatrixRunId = "qcl041-matrix-gate-selftest-pass"
    $maxAgeSeconds = 1800
    Write-JsonFile -Value (New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $true -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId $expectedMatrixRunId) -Path $passPath
    Write-JsonFile -Value (New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $false -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId $expectedMatrixRunId -ClientToOwnerWifiDirectUdpMatrixModePass $false -ClientToOwnerAppBoundUdpSocketPass $false) -Path $failPath
    $missingRunIdSummary = New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $true -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId $expectedMatrixRunId
    $missingRunIdSummary.run_id = ""
    Write-JsonFile -Value $missingRunIdSummary -Path $missingRunIdPath
    Write-JsonFile -Value (New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $true -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId "qcl041-matrix-gate-selftest-other-run") -Path $wrongRunIdPath
    Write-JsonFile -Value (New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $true -OwnerSerial $expectedOwnerSerial -ClientSerial "WRONG-CLIENT" -RunId $expectedMatrixRunId) -Path $wrongClientPath
    Write-JsonFile -Value (New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $true -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId $expectedMatrixRunId) -Path $stalePath
    Write-JsonFile -Value (New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $true -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId $expectedMatrixRunId -ReceiverObservedUdpModes @() -ClientToOwnerWifiDirectUdpMatrixModePass $false -ClientToOwnerAppBoundUdpSocketPass $false) -Path $udpMissingPath
    Write-JsonFile -Value (New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $true -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId $expectedMatrixRunId -ReceiverObservedUdpModes @() -ClientToOwnerWifiDirectUdpMatrixModePass $false -ClientToOwnerAppBoundUdpSocketPass $false) -Path $controlTcpPath
    $controlTcpGateIncompleteMatrixSummary = New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $true -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId $expectedMatrixRunId -ReceiverObservedUdpModes @() -ClientToOwnerWifiDirectUdpMatrixModePass $false -ClientToOwnerAppBoundUdpSocketPass $false
    $controlTcpGateIncompleteMatrixSummary.matrix_focus = "qcl100_control_tcp_gate"
    $controlTcpGateIncompleteMatrixSummary.qcl100_control_tcp_gate = $true
    $controlTcpGateIncompleteMatrixSummary.delayed_udp_required = $false
    $controlTcpGateIncompleteMatrixSummary.whole_matrix_completion_required = $false
    $controlTcpGateIncompleteMatrixSummary.requested_delayed_udp_delay_seconds = 45
    $controlTcpGateIncompleteMatrixSummary.delayed_udp_delay_seconds = 0
    $controlTcpGateIncompleteMatrixSummary.matrix.owner_matrix_complete = $false
    $controlTcpGateIncompleteMatrixSummary.matrix.client_matrix_complete = $false
    $controlTcpGateIncompleteMatrixSummary.matrix.owner_matrix_last_checkpoint = "group_owner_receiver_final"
    $controlTcpGateIncompleteMatrixSummary.matrix.client_matrix_last_checkpoint = "client_after_tcp_tunnel_stream"
    Write-JsonFile -Value $controlTcpGateIncompleteMatrixSummary -Path $controlTcpGateIncompleteMatrixPath
    Write-JsonFile -Value (New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $true -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId $expectedMatrixRunId) -Path $unsupportedTransportPath
    [System.IO.File]::SetLastWriteTimeUtc($stalePath, (Get-Date).ToUniversalTime().AddSeconds(-($maxAgeSeconds + 60)))

    $passEvidence = Get-Qcl100Qcl041MatrixGateEvidence `
        -Path $passPath `
        -ExpectedOwnerSerial $expectedOwnerSerial `
        -ExpectedClientSerial $expectedClientSerial `
        -ExpectedRunId $expectedMatrixRunId `
        -Qcl082TransportProtocol "udp" `
        -MaxAgeSeconds $maxAgeSeconds `
        -RequireFresh
    if (-not [bool]$passEvidence.passed) {
        throw "QCL100 QCL041 matrix gate self-test expected pass summary to pass."
    }
    if (-not [bool]$passEvidence.matrix_client_to_owner_wifi_direct_udp_matrix_mode_present) {
        throw "QCL100 QCL041 matrix gate self-test expected pass summary to preserve directed client-to-owner UDP matrix evidence."
    }
    if ([string]$passEvidence.matrix_client_to_owner_udp_evidence_scope -ne "client_sender_to_group_owner_receiver") {
        throw "QCL100 QCL041 matrix gate self-test expected pass summary to preserve directed UDP evidence scope."
    }
    if ([bool]$passEvidence.matrix_same_group_udp_duplex_media_proven_by_matrix) {
        throw "QCL100 QCL041 matrix gate self-test expected matrix UDP evidence not to claim same-group duplex media proof."
    }
    $failEvidence = Get-Qcl100Qcl041MatrixGateEvidence `
        -Path $failPath `
        -ExpectedOwnerSerial $expectedOwnerSerial `
        -ExpectedClientSerial $expectedClientSerial `
        -ExpectedRunId $expectedMatrixRunId `
        -Qcl082TransportProtocol "udp" `
        -MaxAgeSeconds $maxAgeSeconds `
        -RequireFresh
    if ([bool]$failEvidence.passed) {
        throw "QCL100 QCL041 matrix gate self-test expected fail summary to fail."
    }
    $missingRunIdEvidence = Get-Qcl100Qcl041MatrixGateEvidence `
        -Path $missingRunIdPath `
        -ExpectedOwnerSerial $expectedOwnerSerial `
        -ExpectedClientSerial $expectedClientSerial `
        -ExpectedRunId $expectedMatrixRunId `
        -Qcl082TransportProtocol "udp" `
        -MaxAgeSeconds $maxAgeSeconds `
        -RequireFresh
    if ([bool]$missingRunIdEvidence.passed -or $missingRunIdEvidence.blocked_reason_for_qcl100 -ne "qcl041_matrix_run_id_missing") {
        throw "QCL100 QCL041 matrix gate self-test expected missing-run-id summary to fail on missing run_id."
    }
    $wrongRunIdEvidence = Get-Qcl100Qcl041MatrixGateEvidence `
        -Path $wrongRunIdPath `
        -ExpectedOwnerSerial $expectedOwnerSerial `
        -ExpectedClientSerial $expectedClientSerial `
        -ExpectedRunId $expectedMatrixRunId `
        -Qcl082TransportProtocol "udp" `
        -MaxAgeSeconds $maxAgeSeconds `
        -RequireFresh
    if ([bool]$wrongRunIdEvidence.passed -or $wrongRunIdEvidence.blocked_reason_for_qcl100 -ne "qcl041_matrix_run_id_mismatch") {
        throw "QCL100 QCL041 matrix gate self-test expected wrong-run-id summary to fail on run_id mismatch."
    }
    $wrongClientEvidence = Get-Qcl100Qcl041MatrixGateEvidence `
        -Path $wrongClientPath `
        -ExpectedOwnerSerial $expectedOwnerSerial `
        -ExpectedClientSerial $expectedClientSerial `
        -ExpectedRunId $expectedMatrixRunId `
        -Qcl082TransportProtocol "udp" `
        -MaxAgeSeconds $maxAgeSeconds `
        -RequireFresh
    if ([bool]$wrongClientEvidence.passed -or $wrongClientEvidence.blocked_reason_for_qcl100 -ne "qcl041_matrix_client_serial_mismatch") {
        throw "QCL100 QCL041 matrix gate self-test expected wrong-client summary to fail on client serial mismatch."
    }
    $staleEvidence = Get-Qcl100Qcl041MatrixGateEvidence `
        -Path $stalePath `
        -ExpectedOwnerSerial $expectedOwnerSerial `
        -ExpectedClientSerial $expectedClientSerial `
        -ExpectedRunId $expectedMatrixRunId `
        -Qcl082TransportProtocol "udp" `
        -MaxAgeSeconds $maxAgeSeconds `
        -RequireFresh
    if ([bool]$staleEvidence.passed -or $staleEvidence.blocked_reason_for_qcl100 -ne "qcl041_matrix_summary_stale") {
        throw "QCL100 QCL041 matrix gate self-test expected stale summary to fail on freshness."
    }
    $udpMissingEvidence = Get-Qcl100Qcl041MatrixGateEvidence `
        -Path $udpMissingPath `
        -ExpectedOwnerSerial $expectedOwnerSerial `
        -ExpectedClientSerial $expectedClientSerial `
        -ExpectedRunId $expectedMatrixRunId `
        -Qcl082TransportProtocol "udp" `
        -MaxAgeSeconds $maxAgeSeconds `
        -RequireFresh
    if ([bool]$udpMissingEvidence.passed -or $udpMissingEvidence.blocked_reason_for_qcl100 -ne "qcl041_matrix_client_to_owner_udp_mode_absent") {
        throw "QCL100 QCL041 matrix gate self-test expected UDP summary without directed client-to-owner UDP evidence to fail on missing UDP mode."
    }
    $controlTcpEvidence = Get-Qcl100Qcl041MatrixGateEvidence `
        -Path $controlTcpPath `
        -ExpectedOwnerSerial $expectedOwnerSerial `
        -ExpectedClientSerial $expectedClientSerial `
        -ExpectedRunId $expectedMatrixRunId `
        -Qcl082TransportProtocol "control-tcp" `
        -MaxAgeSeconds $maxAgeSeconds `
        -RequireFresh
    if (-not [bool]$controlTcpEvidence.passed) {
        throw "QCL100 QCL041 matrix gate self-test expected control-tcp summary to pass with tcp_tunnel_stream_socket."
    }
    $controlTcpGateIncompleteMatrixEvidence = Get-Qcl100Qcl041MatrixGateEvidence `
        -Path $controlTcpGateIncompleteMatrixPath `
        -ExpectedOwnerSerial $expectedOwnerSerial `
        -ExpectedClientSerial $expectedClientSerial `
        -ExpectedRunId $expectedMatrixRunId `
        -Qcl082TransportProtocol "control-tcp" `
        -MaxAgeSeconds $maxAgeSeconds `
        -RequireFresh
    if (-not [bool]$controlTcpGateIncompleteMatrixEvidence.passed) {
        throw "QCL100 QCL041 matrix gate self-test expected control-tcp gate summary to pass when the whole UDP matrix is intentionally incomplete."
    }
    if (-not [bool]$controlTcpGateIncompleteMatrixEvidence.qcl100_control_tcp_gate -or [bool]$controlTcpGateIncompleteMatrixEvidence.whole_matrix_completion_required) {
        throw "QCL100 QCL041 matrix gate self-test expected control-tcp gate evidence to preserve gate metadata."
    }
    if ($controlTcpGateIncompleteMatrixEvidence.matrix_focus -ne "qcl100_control_tcp_gate") {
        throw "QCL100 QCL041 matrix gate self-test expected control-tcp gate evidence to preserve matrix_focus."
    }
    $unsupportedTransportEvidence = Get-Qcl100Qcl041MatrixGateEvidence `
        -Path $unsupportedTransportPath `
        -ExpectedOwnerSerial $expectedOwnerSerial `
        -ExpectedClientSerial $expectedClientSerial `
        -ExpectedRunId $expectedMatrixRunId `
        -Qcl082TransportProtocol "reverse-tcp" `
        -MaxAgeSeconds $maxAgeSeconds `
        -RequireFresh
    if ([bool]$unsupportedTransportEvidence.passed -or $unsupportedTransportEvidence.blocked_reason_for_qcl100 -ne "qcl041_matrix_qcl100_transport_not_supported") {
        throw "QCL100 QCL041 matrix gate self-test expected unsupported transport summary to fail on unsupported transport."
    }

    $selfTest = [ordered]@{
        schema = "rusty.quest.qcl100_qcl041_matrix_gate_self_test.v1"
        pass_summary_path = $passPath
        fail_summary_path = $failPath
        missing_run_id_summary_path = $missingRunIdPath
        wrong_run_id_summary_path = $wrongRunIdPath
        wrong_client_summary_path = $wrongClientPath
        stale_summary_path = $stalePath
        udp_missing_summary_path = $udpMissingPath
        control_tcp_summary_path = $controlTcpPath
        control_tcp_gate_incomplete_matrix_summary_path = $controlTcpGateIncompleteMatrixPath
        unsupported_transport_summary_path = $unsupportedTransportPath
        pass_case = $passEvidence
        fail_case = $failEvidence
        missing_run_id_case = $missingRunIdEvidence
        wrong_run_id_case = $wrongRunIdEvidence
        wrong_client_case = $wrongClientEvidence
        stale_case = $staleEvidence
        udp_missing_case = $udpMissingEvidence
        control_tcp_case = $controlTcpEvidence
        control_tcp_gate_incomplete_matrix_case = $controlTcpGateIncompleteMatrixEvidence
        unsupported_transport_case = $unsupportedTransportEvidence
        passed = $true
    }
    Write-JsonFile -Value $selfTest -Path (Join-Path $OutputDirectory "qcl100-qcl041-matrix-gate-self-test.json")
    return $selfTest
}
