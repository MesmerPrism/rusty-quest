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

function Get-Qcl100MatrixGateInt {
    param($Value)
    try {
        if ($null -eq $Value) {
            return 0
        }
        return [int]$Value
    } catch {
        return 0
    }
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

function Get-Qcl100Qcl041MatrixLowerGateIssueCodes {
    @(
        "qcl041_client_p2p_network_callback_not_seen",
        "qcl041_client_p2p_network_not_visible_app",
        "qcl041_client_p2p_network_link_properties_missing",
        "qcl041_client_p2p_network_route_not_matching_group_owner",
        "qcl041_client_p2p_udp_network_bound_not_receiver_observed",
        "qcl041_client_p2p_tcp_stream_not_bidirectional"
    )
}

function Get-Qcl100Qcl041MatrixIssueCode {
    param($Issue)
    if ($null -eq $Issue) {
        return ""
    }
    if ($Issue -is [System.Collections.IDictionary] -and $Issue.Contains("code")) {
        return [string]$Issue["code"]
    }
    return [string](Get-Qcl100MatrixGateProperty -Object $Issue -Name "code")
}

function Get-Qcl100Qcl041MatrixLowerGateIssues {
    param($Issues)
    $acceptedCodes = @{}
    foreach ($code in @(Get-Qcl100Qcl041MatrixLowerGateIssueCodes)) {
        $acceptedCodes[$code] = $true
    }

    $lowerGateIssues = @()
    foreach ($issue in @($Issues)) {
        $code = Get-Qcl100Qcl041MatrixIssueCode -Issue $issue
        if (-not [string]::IsNullOrWhiteSpace($code) -and $acceptedCodes.ContainsKey($code)) {
            $lowerGateIssues += $issue
        }
    }
    return $lowerGateIssues
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
    $networkVisibilityDeepTrace =
        Get-Qcl100MatrixGateProperty -Object $summary -Name "network_visibility_deep_trace"
    $networkVisibilityDeepTraceRows =
        Get-Qcl100MatrixGateProperty -Object $networkVisibilityDeepTrace -Name "rows"
    $networkVisibilityDeepTraceRowCount = if ($null -eq $networkVisibilityDeepTraceRows) {
        0
    } else {
        @($networkVisibilityDeepTraceRows).Count
    }
    $networkVisibilityDeepTraceExpectedRowIds = @(
        "callback_wifi_p2p_default",
        "callback_wifi_p2p_clear_capabilities",
        "callback_local_network_reflection",
        "callback_wifi_transport_clear_capabilities",
        "callback_include_other_uid_wifi_p2p",
        "callback_include_other_uid_local_network",
        "get_all_networks_standard",
        "get_all_networks_include_other_uid_request_observed",
        "wifi_p2p_request_network_info",
        "wifi_p2p_request_connection_info",
        "wifi_p2p_request_group_info",
        "local_p2p_bind_tcp_stream_control"
    )
    $networkVisibilityDeepTraceRowIds = @()
    foreach ($row in @($networkVisibilityDeepTraceRows)) {
        $rowId = [string](Get-Qcl100MatrixGateProperty -Object $row -Name "id")
        if (-not [string]::IsNullOrWhiteSpace($rowId)) {
            $networkVisibilityDeepTraceRowIds += $rowId
        }
    }
    $networkVisibilityDeepTraceMissingRowIds = @()
    foreach ($expectedRowId in $networkVisibilityDeepTraceExpectedRowIds) {
        if ($networkVisibilityDeepTraceRowIds -notcontains $expectedRowId) {
            $networkVisibilityDeepTraceMissingRowIds += $expectedRowId
        }
    }
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
    $clientP2pNetworkCallbackSeen = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_network_callback_seen"
    $clientP2pNetworkVisibleApp = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_network_visible_app"
    $clientP2pNetworkLinkPropertiesPresent = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_network_link_properties_present"
    $clientP2pNetworkRouteMatchesGroupOwner = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_network_route_matches_group_owner"
    $clientP2pNetworkSocketAuthorityPass = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_network_socket_authority_pass"
    $clientAppNetworkPermissionsAllGranted = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_app_network_permissions_all_granted"
    $clientAppNetworkPermissionsAllDeclaredGranted = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_app_network_permissions_all_declared_granted"
    $clientSdkInt = Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_sdk_int"
    $clientTargetSdkInt = Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_target_sdk_int"
    $clientNearbyWifiDevicesPermissionApplicable = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_permission_nearby_wifi_devices_applicable"
    $clientAccessFineLocationPermissionApplicable = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_permission_access_fine_location_applicable"
    $clientAccessFineLocationPermissionManifestMaxSdk = Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_permission_access_fine_location_manifest_max_sdk"
    $clientAppNetworkAuthorityRestrictionHint = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_app_network_authority_restriction_hint")
    $clientRequestWifiP2pRestrictedNetworkSecurityException = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_request_wifi_p2p_restricted_network_security_exception"
    $clientAppOpNearbyWifiDevicesMode = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_appop_nearby_wifi_devices_mode")
    $clientAppOpFineLocationMode = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_appop_fine_location_mode")
    $clientAppOpWifiScanMode = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_appop_wifi_scan_mode")
    $clientAfterGroupAllNetworkCount = Get-Qcl100MatrixGateInt (Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_after_group_formation_all_network_count")
    $clientAfterGroupP2pCandidateCount = Get-Qcl100MatrixGateInt (Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_after_group_formation_p2p_candidate_count")
    $clientAfterGroupNetworkInterfaceP2pCount = Get-Qcl100MatrixGateInt (Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_after_group_formation_network_interface_p2p_count")
    $clientIncludeOtherUidCandidateSeen = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_include_other_uid_candidate_seen"
    $clientIncludeOtherUidOnAvailableCount = Get-Qcl100MatrixGateInt (Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_include_other_uid_on_available_count")
    $clientIncludeOtherUidCachedNetworkCount = Get-Qcl100MatrixGateInt (Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_include_other_uid_cached_network_count")
    $clientIncludeOtherUidBindSocketResult = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_include_other_uid_bind_socket_result")
    $clientWifiP2pNetworkInfoAvailable = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_wifi_p2p_network_info_available"
    $clientWifiP2pNetworkInfoConnected = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_wifi_p2p_network_info_connected"
    $clientWifiP2pNetworkInfoState = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_wifi_p2p_network_info_state")
    $clientWifiP2pNetworkInfoDetailedState = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_wifi_p2p_network_info_detailed_state")
    $clientWifiP2pGroupInterface = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_wifi_p2p_group_interface")
    $clientWifiP2pGroupClientCount = Get-Qcl100MatrixGateInt (Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_wifi_p2p_group_client_count")
    $clientStrictLocalP2pAppTransportPass = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_strict_local_p2p_app_transport_pass"
    $qcl041LocalP2pBindStreamAuthority = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "qcl041_local_p2p_bind_stream_authority")
    $qcl100AndroidNetworkAuthority = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "qcl100_android_network_authority")
    $qcl100SameGroupSimultaneousNativeRender = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "qcl100_same_group_simultaneous_native_render")
    $udpNetworkBoundReceiverObservedPackets = Get-Qcl100MatrixGateInt (Get-Qcl100MatrixGateProperty -Object $matrix -Name "udp_network_bound_receiver_observed_packets")
    $localP2pBindNonPromoting = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_interface_local_bind_non_promoting"
    $localP2pBindSocketAuthority = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_p2p_interface_local_bind_socket_authority")
    $localP2pBindUdpAttempted = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_interface_local_bind_udp_attempted"
    $localP2pBindUdpPass = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_interface_local_bind_udp_pass"
    $localP2pBindUdpReceiverObservedPackets = Get-Qcl100MatrixGateInt (Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_p2p_interface_local_bind_udp_receiver_observed_packets")
    $localP2pBindUdpReceiverObservedSourceAddress = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_p2p_interface_local_bind_udp_receiver_observed_source_address")
    $localP2pBindTcpAttempted = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_interface_local_bind_tcp_attempted"
    $localP2pBindTcpPass = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_interface_local_bind_tcp_pass"
    $localP2pBindTcpReceiverAccepts = Get-Qcl100MatrixGateInt (Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_p2p_interface_local_bind_tcp_receiver_accepts")
    $localP2pBindTcpReceiverAcceptedSource = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_p2p_interface_local_bind_tcp_receiver_accepted_source")
    $localP2pBindTcpStreamAttempted = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_interface_local_bind_tcp_stream_attempted"
    $localP2pBindTcpStreamPass = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_interface_local_bind_tcp_stream_pass"
    $localP2pBindTcpStreamReceiverAccepts = Get-Qcl100MatrixGateInt (Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_p2p_interface_local_bind_tcp_stream_receiver_accepts")
    $localP2pBindTcpStreamReceiverAcceptedSource = [string](Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_p2p_interface_local_bind_tcp_stream_receiver_accepted_source")
    $localP2pBindTcpStreamClientToOwnerRxBytes = Get-Qcl100MatrixGateInt (Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_p2p_interface_local_bind_tcp_stream_client_to_owner_rx_bytes")
    $localP2pBindTcpStreamOwnerToClientRxBytes = Get-Qcl100MatrixGateInt (Get-Qcl100MatrixGateProperty -Object $matrix -Name "client_p2p_interface_local_bind_tcp_stream_owner_to_client_rx_bytes")
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
        if (-not $clientP2pNetworkCallbackSeen) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_client_p2p_network_callback_not_seen" `
                -Message "QCL041 client app did not observe a same-epoch Wi-Fi Direct Network from ConnectivityManager callbacks."
        }
        if (-not $clientP2pNetworkVisibleApp) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_client_p2p_network_not_visible_app" `
                -Message "QCL041 client app did not have an app-visible Wi-Fi Direct Network candidate."
        }
        if (-not $clientP2pNetworkLinkPropertiesPresent) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_client_p2p_network_link_properties_missing" `
                -Message "QCL041 client selected P2P Network did not expose LinkProperties."
        }
        if (-not $clientP2pNetworkRouteMatchesGroupOwner) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_client_p2p_network_route_not_matching_group_owner" `
                -Message "QCL041 client selected P2P Network did not report a route matching the group owner address."
        }
        if ($udpNetworkBoundReceiverObservedPackets -le 0 -or -not $clientP2pNetworkSocketAuthorityPass) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_client_p2p_udp_network_bound_not_receiver_observed" `
                -Message "QCL041 client app-bound DatagramSocket echo was not receiver-observed on the group owner."
        }
        if (-not $tcpTunnelStreamBidirectionalBytesPass) {
            $issues += New-Qcl100Qcl041MatrixGateIssue `
                -Code "qcl041_client_p2p_tcp_stream_not_bidirectional" `
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

    $lowerGateIssues = @(Get-Qcl100Qcl041MatrixLowerGateIssues -Issues $issues)
    $lowerGateIssueCodes = @($lowerGateIssues | ForEach-Object { Get-Qcl100Qcl041MatrixIssueCode -Issue $_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $firstIssue = if ($issues.Count -gt 0) { Get-Qcl100Qcl041MatrixIssueCode -Issue $issues[0] } else { "" }
    $firstLowerGateIssue = if ($lowerGateIssueCodes.Count -gt 0) { [string]$lowerGateIssueCodes[0] } else { "" }
    $blockedReasonForQcl100 = if (-not [string]::IsNullOrWhiteSpace($firstLowerGateIssue)) {
        $firstLowerGateIssue
    } else {
        $firstIssue
    }
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
        network_visibility_deep_trace_classification =
            [string](Get-Qcl100MatrixGateProperty -Object $networkVisibilityDeepTrace -Name "classification")
        network_visibility_deep_trace_diagnostic_id =
            [string](Get-Qcl100MatrixGateProperty -Object $networkVisibilityDeepTrace -Name "diagnostic_id")
        network_visibility_deep_trace_row_count = $networkVisibilityDeepTraceRowCount
        network_visibility_deep_trace_expected_row_ids = $networkVisibilityDeepTraceExpectedRowIds
        network_visibility_deep_trace_row_ids = $networkVisibilityDeepTraceRowIds
        network_visibility_deep_trace_missing_row_ids = $networkVisibilityDeepTraceMissingRowIds
        network_visibility_deep_trace_expected_rows_present =
            [bool]($networkVisibilityDeepTraceMissingRowIds.Count -eq 0)
        network_visibility_deep_trace_local_p2p_promotes_qcl100 =
            Get-Qcl100MatrixGateBool -Object $networkVisibilityDeepTrace -Name "local_p2p_bind_stream_promotes_qcl100"
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
        client_p2p_network_callback_seen = $clientP2pNetworkCallbackSeen
        client_p2p_network_visible_app = $clientP2pNetworkVisibleApp
        client_p2p_network_link_properties_present = $clientP2pNetworkLinkPropertiesPresent
        client_p2p_network_route_matches_group_owner = $clientP2pNetworkRouteMatchesGroupOwner
        client_p2p_network_socket_authority_pass = $clientP2pNetworkSocketAuthorityPass
        client_app_network_permissions_all_granted = $clientAppNetworkPermissionsAllGranted
        client_app_network_permissions_all_declared_granted = $clientAppNetworkPermissionsAllDeclaredGranted
        client_sdk_int = $clientSdkInt
        client_target_sdk_int = $clientTargetSdkInt
        client_permission_nearby_wifi_devices_applicable = $clientNearbyWifiDevicesPermissionApplicable
        client_permission_access_fine_location_applicable = $clientAccessFineLocationPermissionApplicable
        client_permission_access_fine_location_manifest_max_sdk = $clientAccessFineLocationPermissionManifestMaxSdk
        client_app_network_authority_restriction_hint = $clientAppNetworkAuthorityRestrictionHint
        client_request_wifi_p2p_restricted_network_security_exception = $clientRequestWifiP2pRestrictedNetworkSecurityException
        client_appop_nearby_wifi_devices_mode = $clientAppOpNearbyWifiDevicesMode
        client_appop_fine_location_mode = $clientAppOpFineLocationMode
        client_appop_wifi_scan_mode = $clientAppOpWifiScanMode
        client_after_group_formation_all_network_count = $clientAfterGroupAllNetworkCount
        client_after_group_formation_p2p_candidate_count = $clientAfterGroupP2pCandidateCount
        client_after_group_formation_network_interface_p2p_count = $clientAfterGroupNetworkInterfaceP2pCount
        client_include_other_uid_candidate_seen = $clientIncludeOtherUidCandidateSeen
        client_include_other_uid_on_available_count = $clientIncludeOtherUidOnAvailableCount
        client_include_other_uid_cached_network_count = $clientIncludeOtherUidCachedNetworkCount
        client_include_other_uid_bind_socket_result = $clientIncludeOtherUidBindSocketResult
        client_wifi_p2p_network_info_available = $clientWifiP2pNetworkInfoAvailable
        client_wifi_p2p_network_info_connected = $clientWifiP2pNetworkInfoConnected
        client_wifi_p2p_network_info_state = $clientWifiP2pNetworkInfoState
        client_wifi_p2p_network_info_detailed_state = $clientWifiP2pNetworkInfoDetailedState
        client_wifi_p2p_group_interface = $clientWifiP2pGroupInterface
        client_wifi_p2p_group_client_count = $clientWifiP2pGroupClientCount
        client_strict_local_p2p_app_transport_pass = $clientStrictLocalP2pAppTransportPass
        qcl041_local_p2p_bind_stream_authority = $qcl041LocalP2pBindStreamAuthority
        qcl100_android_network_authority = $qcl100AndroidNetworkAuthority
        qcl100_same_group_simultaneous_native_render = $qcl100SameGroupSimultaneousNativeRender
        udp_network_bound_receiver_observed_packets = $udpNetworkBoundReceiverObservedPackets
        local_p2p_bind_diagnostic_non_promoting = $localP2pBindNonPromoting
        local_p2p_bind_socket_authority = $localP2pBindSocketAuthority
        local_p2p_bind_udp_attempted = $localP2pBindUdpAttempted
        local_p2p_bind_udp_pass = $localP2pBindUdpPass
        local_p2p_bind_udp_receiver_observed_packets = $localP2pBindUdpReceiverObservedPackets
        local_p2p_bind_udp_receiver_observed_source_address = $localP2pBindUdpReceiverObservedSourceAddress
        local_p2p_bind_tcp_attempted = $localP2pBindTcpAttempted
        local_p2p_bind_tcp_pass = $localP2pBindTcpPass
        local_p2p_bind_tcp_receiver_accepts = $localP2pBindTcpReceiverAccepts
        local_p2p_bind_tcp_receiver_accepted_source = $localP2pBindTcpReceiverAcceptedSource
        local_p2p_bind_tcp_stream_attempted = $localP2pBindTcpStreamAttempted
        local_p2p_bind_tcp_stream_pass = $localP2pBindTcpStreamPass
        local_p2p_bind_tcp_stream_receiver_accepts = $localP2pBindTcpStreamReceiverAccepts
        local_p2p_bind_tcp_stream_receiver_accepted_source = $localP2pBindTcpStreamReceiverAcceptedSource
        local_p2p_bind_tcp_stream_client_to_owner_rx_bytes = $localP2pBindTcpStreamClientToOwnerRxBytes
        local_p2p_bind_tcp_stream_owner_to_client_rx_bytes = $localP2pBindTcpStreamOwnerToClientRxBytes
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
        first_issue = $firstIssue
        lower_gate_issue_codes = $lowerGateIssueCodes
        lower_gate_issue_count = [int]$lowerGateIssueCodes.Count
        first_lower_gate_issue = $firstLowerGateIssue
        blocked_reason_for_qcl100 = $blockedReasonForQcl100
        passed = [bool]($issues.Count -eq 0)
    }
}

function Read-Qcl100Qcl041MatrixComparisonSummary {
    param([string]$Path)
    $present = $false
    $parsed = $false
    $summary = $null
    $parseError = ""
    $resolvedPath = ""
    if (-not [string]::IsNullOrWhiteSpace($Path) -and (Test-Path -LiteralPath $Path)) {
        $present = $true
        $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
        try {
            $summary = Get-Content -Raw -LiteralPath $resolvedPath | ConvertFrom-Json
            $parsed = $true
        } catch {
            $parseError = $_.Exception.Message
        }
    }
    [pscustomobject]@{
        path = $Path
        resolved_path = $resolvedPath
        present = $present
        parsed = $parsed
        parse_error = $parseError
        object = $summary
    }
}

function Get-Qcl100Qcl041MatrixComparisonFields {
    param($Summary)
    $preflight = Get-Qcl100MatrixGateProperty -Object $Summary -Name "preflight"
    $matrix = Get-Qcl100MatrixGateProperty -Object $Summary -Name "matrix"
    $appNetworkVisibility = Get-Qcl100MatrixGateProperty -Object $Summary -Name "app_network_visibility"
    [ordered]@{
        run_id = [string](Get-Qcl100MatrixGateProperty -Object $Summary -Name "run_id")
        status = [string](Get-Qcl100MatrixGateProperty -Object $Summary -Name "status")
        blocked_reason = [string](Get-Qcl100MatrixGateProperty -Object $Summary -Name "blocked_reason")
        matrix_focus = [string](Get-Qcl100MatrixGateProperty -Object $Summary -Name "matrix_focus")
        require_infrastructure_wifi_disconnected = Get-Qcl100MatrixGateBool -Object $Summary -Name "require_infrastructure_wifi_disconnected"
        require_p2p0_ipv4_cleared = Get-Qcl100MatrixGateBool -Object $Summary -Name "require_p2p0_ipv4_cleared"
        require_candidate_wifi_direct_routes_clear = Get-Qcl100MatrixGateBool -Object $Summary -Name "require_candidate_wifi_direct_routes_clear"
        require_tcp_tunnel_stream_pass = Get-Qcl100MatrixGateBool -Object $Summary -Name "require_tcp_tunnel_stream_pass"
        preflight_infrastructure_wifi_disconnected = Get-Qcl100MatrixGateBool -Object $preflight -Name "infrastructure_wifi_disconnected"
        preflight_p2p0_ipv4_cleared = Get-Qcl100MatrixGateBool -Object $preflight -Name "p2p0_ipv4_cleared"
        preflight_candidate_wifi_direct_routes_clear = Get-Qcl100MatrixGateBool -Object $preflight -Name "candidate_wifi_direct_prelaunch_routes_clear"
        matrix_receiver_observed_bytes = Get-Qcl100MatrixGateBool -Object $matrix -Name "receiver_observed_bytes"
        matrix_tcp_tunnel_stream_bidirectional_bytes_pass = Get-Qcl100MatrixGateBool -Object $matrix -Name "tcp_tunnel_stream_bidirectional_bytes_pass"
        matrix_client_p2p_network_callback_seen = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_network_callback_seen"
        matrix_client_p2p_network_visible_app = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_network_visible_app"
        matrix_client_p2p_network_link_properties_present = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_network_link_properties_present"
        matrix_client_p2p_network_route_matches_group_owner = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_network_route_matches_group_owner"
        matrix_client_p2p_network_socket_authority_pass = Get-Qcl100MatrixGateBool -Object $matrix -Name "client_p2p_network_socket_authority_pass"
        matrix_udp_network_bound_receiver_observed_packets = Get-Qcl100MatrixGateInt (Get-Qcl100MatrixGateProperty -Object $matrix -Name "udp_network_bound_receiver_observed_packets")
        app_network_visibility_decision = [string](Get-Qcl100MatrixGateProperty -Object $appNetworkVisibility -Name "decision")
        app_network_client_visible = [bool](
            (Get-Qcl100MatrixGateBool -Object $appNetworkVisibility -Name "client_qcl041_p2p_network_visible") -or
            (Get-Qcl100MatrixGateBool -Object $appNetworkVisibility -Name "client_wifi_p2p_network_request_visible") -or
            (Get-Qcl100MatrixGateBool -Object $appNetworkVisibility -Name "client_p2p_network_callback_seen")
        )
        shell_client_route_get_from_p2p_source_uses_p2p0 = Get-Qcl100MatrixGateBool -Object $appNetworkVisibility -Name "shell_client_route_get_from_p2p_source_uses_p2p0"
    }
}

function Get-Qcl100Qcl041MatrixArtifactComparison {
    param(
        [string]$OlderCurrentWlanSummaryPath,
        [string]$StrictApDisconnectedSummaryPath
    )

    $older = Read-Qcl100Qcl041MatrixComparisonSummary -Path $OlderCurrentWlanSummaryPath
    $strict = Read-Qcl100Qcl041MatrixComparisonSummary -Path $StrictApDisconnectedSummaryPath
    $olderFields = if ($older.parsed) { Get-Qcl100Qcl041MatrixComparisonFields -Summary $older.object } else { [ordered]@{} }
    $strictFields = if ($strict.parsed) { Get-Qcl100Qcl041MatrixComparisonFields -Summary $strict.object } else { [ordered]@{} }

    $normalizedReason = ""
    $message = ""
    if (-not [bool]$older.present) {
        $normalizedReason = "older_current_wlan_artifact_missing"
        $message = "Older current-WLAN artifact is missing."
    } elseif (-not [bool]$older.parsed) {
        $normalizedReason = "older_current_wlan_artifact_parse_failed"
        $message = "Older current-WLAN artifact could not be parsed."
    } elseif (-not [bool]$strict.present) {
        $normalizedReason = "strict_ap_disconnected_artifact_missing"
        $message = "Strict AP-disconnected artifact is missing."
    } elseif (-not [bool]$strict.parsed) {
        $normalizedReason = "strict_ap_disconnected_artifact_parse_failed"
        $message = "Strict AP-disconnected artifact could not be parsed."
    } elseif (-not [bool]$olderFields.require_infrastructure_wifi_disconnected -or
            -not [bool]$olderFields.require_p2p0_ipv4_cleared -or
            -not [bool]$olderFields.require_candidate_wifi_direct_routes_clear) {
        $normalizedReason = "older_current_wlan_pass_missing_strict_preflight_requirements"
        $message = "Older artifact was not run with the strict AP-disconnected, stale-p2p0-clear, and candidate-route-clear preflight requirements."
    } elseif (-not [bool]$olderFields.preflight_infrastructure_wifi_disconnected) {
        $normalizedReason = "older_current_wlan_pass_used_infrastructure_wifi"
        $message = "Older artifact cannot satisfy QCL100 lower gate because ordinary infrastructure Wi-Fi was still connected."
    } elseif (-not [bool]$olderFields.preflight_p2p0_ipv4_cleared) {
        $normalizedReason = "older_current_wlan_pass_started_with_stale_p2p0"
        $message = "Older artifact cannot satisfy QCL100 lower gate because stale p2p0 IPv4 was present before launch."
    } elseif (-not [bool]$olderFields.preflight_candidate_wifi_direct_routes_clear) {
        $normalizedReason = "older_current_wlan_pass_started_with_candidate_wifi_direct_routes"
        $message = "Older artifact cannot satisfy QCL100 lower gate because candidate Wi-Fi Direct routes were present before launch."
    } elseif (-not [bool]$olderFields.require_tcp_tunnel_stream_pass) {
        $normalizedReason = "older_current_wlan_pass_missing_strict_stream_requirement"
        $message = "Older artifact did not require the strict bidirectional TCP tunnel stream bit."
    } elseif (-not [bool]$olderFields.matrix_tcp_tunnel_stream_bidirectional_bytes_pass) {
        $normalizedReason = "older_current_wlan_pass_strict_stream_bit_false"
        $message = "Older artifact has the strict bidirectional TCP tunnel stream bit false."
    } elseif ([string]$strictFields.app_network_visibility_decision -eq "qcl041_client_p2p_network_not_visible" -or
            [string]$strictFields.app_network_visibility_decision -eq "qcl041_client_p2p_network_not_visible_app" -or
            [string]$strictFields.app_network_visibility_decision -eq "qcl041_strict_local_p2p_app_transport_pass_connectivitymanager_network_absent" -or
            [string]$strictFields.app_network_visibility_decision -eq "qcl041_connectivitymanager_other_uid_p2p_visible_client_uid_hidden" -or
            [string]$strictFields.blocked_reason -eq "qcl041_client_p2p_network_not_visible_app" -or
            [string]$strictFields.blocked_reason -eq "qcl041_strict_local_p2p_app_transport_pass_connectivitymanager_network_absent" -or
            [string]$strictFields.blocked_reason -eq "qcl041_connectivitymanager_other_uid_p2p_visible_client_uid_hidden" -or
            -not [bool]$strictFields.matrix_client_p2p_network_callback_seen -or
            -not [bool]$strictFields.matrix_client_p2p_network_visible_app) {
        $normalizedReason = "strict_ap_disconnected_blocked_by_app_invisible_p2p"
        $message = "Strict AP-disconnected artifact shows shell-visible Wi-Fi Direct is not visible to the QCL041 app network APIs."
    } elseif (-not [bool]$strictFields.matrix_client_p2p_network_link_properties_present) {
        $normalizedReason = "strict_ap_disconnected_blocked_by_missing_p2p_link_properties"
        $message = "Strict AP-disconnected artifact selected a client Wi-Fi Direct Network without LinkProperties."
    } elseif (-not [bool]$strictFields.matrix_client_p2p_network_route_matches_group_owner) {
        $normalizedReason = "strict_ap_disconnected_blocked_by_p2p_route_mismatch"
        $message = "Strict AP-disconnected artifact selected a client Wi-Fi Direct Network that did not route to the group owner."
    } elseif (-not [bool]$strictFields.matrix_client_p2p_network_socket_authority_pass -or
            [int]$strictFields.matrix_udp_network_bound_receiver_observed_packets -le 0) {
        $normalizedReason = "strict_ap_disconnected_blocked_by_udp_socket_authority"
        $message = "Strict AP-disconnected artifact did not prove receiver-observed network-bound UDP socket authority before TCP."
    } elseif ([string]$strictFields.blocked_reason -eq "tcp_tunnel_stream_not_bidirectional" -or
            [string]$strictFields.blocked_reason -eq "qcl041_client_p2p_tcp_stream_not_bidirectional" -or
            -not [bool]$strictFields.matrix_tcp_tunnel_stream_bidirectional_bytes_pass) {
        $normalizedReason = "strict_ap_disconnected_blocked_by_strict_stream_bit_false"
        $message = "Strict AP-disconnected artifact has the strict bidirectional TCP tunnel stream bit false."
    } elseif ([string]$strictFields.blocked_reason -eq "p2p0_ipv4_present" -or
            -not [bool]$strictFields.preflight_p2p0_ipv4_cleared) {
        $normalizedReason = "strict_ap_disconnected_blocked_by_stale_p2p0"
        $message = "Strict artifact did not begin from a stale-p2p0-clear state."
    } else {
        $normalizedReason = "artifact_pair_does_not_satisfy_qcl100_lower_gate"
        $message = "The artifact pair does not prove the strict QCL100 lower gate; use the normalized fields to inspect the first unmet condition."
    }

    [ordered]@{
        schema = "rusty.quest.qcl100_qcl041_matrix_artifact_comparison.v1"
        generated_at_utc = (Get-Date).ToUniversalTime().ToString("o")
        older_current_wlan_artifact = [ordered]@{
            path = $older.path
            resolved_path = $older.resolved_path
            present = [bool]$older.present
            parsed = [bool]$older.parsed
            parse_error = $older.parse_error
            fields = $olderFields
        }
        strict_ap_disconnected_artifact = [ordered]@{
            path = $strict.path
            resolved_path = $strict.resolved_path
            present = [bool]$strict.present
            parsed = [bool]$strict.parsed
            parse_error = $strict.parse_error
            fields = $strictFields
        }
        qcl100_lower_gate_satisfied_by_old_artifact = $false
        normalized_reason = $normalizedReason
        why_old_artifact_does_not_satisfy_qcl100_lower_gate = $message
        comparison_completed = $true
    }
}

function New-Qcl100Qcl041MatrixSelfTestNetworkVisibilityDeepTrace {
    param(
        [bool]$Pass,
        [bool]$LocalP2pBindTcpStreamPass = $false
    )
    $rowIds = @(
        "callback_wifi_p2p_default",
        "callback_wifi_p2p_clear_capabilities",
        "callback_local_network_reflection",
        "callback_wifi_transport_clear_capabilities",
        "callback_include_other_uid_wifi_p2p",
        "callback_include_other_uid_local_network",
        "get_all_networks_standard",
        "get_all_networks_include_other_uid_request_observed",
        "wifi_p2p_request_network_info",
        "wifi_p2p_request_connection_info",
        "wifi_p2p_request_group_info",
        "local_p2p_bind_tcp_stream_control"
    )
    $rows = @()
    foreach ($rowId in $rowIds) {
        $observed = if ($Pass) {
            $rowId -ne "local_p2p_bind_tcp_stream_control"
        } elseif ($LocalP2pBindTcpStreamPass) {
            @(
                "wifi_p2p_request_network_info",
                "wifi_p2p_request_connection_info",
                "wifi_p2p_request_group_info",
                "local_p2p_bind_tcp_stream_control"
            ) -contains $rowId
        } else {
            $false
        }
        $rows += [ordered]@{
            id = $rowId
            status = $(if ($observed) { "observed" } else { "not_observed" })
        }
    }
    [ordered]@{
        schema = "rusty.quest.qcl041_network_visibility_deep_trace.v1"
        diagnostic_id = "qcl041_network_visibility_deep_trace"
        classification = $(if ($Pass) {
            "android_connectivitymanager_network_authority_pass"
        } elseif ($LocalP2pBindTcpStreamPass) {
            "p2p_framework_connected_local_bind_transport_only"
        } else {
            "connectivitymanager_network_absent_or_not_observed"
        })
        app_network_visibility_decision = $(if ($Pass) {
            "qcl041_and_shell_source_route_use_p2p0"
        } elseif ($LocalP2pBindTcpStreamPass) {
            "qcl041_local_p2p_bind_transport_only"
        } else {
            "qcl041_client_p2p_network_not_visible"
        })
        rows = @($rows)
        qcl100_android_network_authority = $(if ($Pass) { "pass" } else { "blocked" })
        qcl041_local_p2p_bind_stream_authority = $(if ($LocalP2pBindTcpStreamPass) { "diagnostic_pass" } else { "not_proven" })
        local_p2p_bind_stream_promotes_qcl100 = $false
        promotion_allowed = $false
        same_group_duplex_claimed = $false
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
        [bool]$ClientToOwnerAppBoundUdpSocketPass = $Pass,
        [bool]$ClientP2pNetworkCallbackSeen = $Pass,
        [bool]$ClientP2pNetworkVisibleApp = $Pass,
        [bool]$ClientP2pNetworkLinkPropertiesPresent = $Pass,
        [bool]$ClientP2pNetworkRouteMatchesGroupOwner = $Pass,
        [bool]$ClientP2pNetworkSocketAuthorityPass = $Pass,
        [int]$UdpNetworkBoundReceiverObservedPackets = $(if ($Pass) { 1 } else { 0 }),
        [bool]$LocalP2pBindNonPromoting = $false,
        [bool]$LocalP2pBindUdpPass = $false,
        [int]$LocalP2pBindUdpReceiverObservedPackets = 0,
        [bool]$LocalP2pBindTcpPass = $false,
        [int]$LocalP2pBindTcpReceiverAccepts = 0,
        [bool]$LocalP2pBindTcpStreamPass = $false,
        [int]$LocalP2pBindTcpStreamReceiverAccepts = 0,
        [int]$LocalP2pBindTcpStreamBytesPerDirection = 0
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
            client_p2p_network_callback_seen = $ClientP2pNetworkCallbackSeen
            client_p2p_network_visible_app = $ClientP2pNetworkVisibleApp
            client_p2p_network_selected_handle = $(if ($ClientP2pNetworkVisibleApp) { 123456 } else { $null })
            client_p2p_network_selected_interface = $(if ($ClientP2pNetworkVisibleApp) { "p2p0" } else { "" })
            client_p2p_network_link_properties_present = $ClientP2pNetworkLinkPropertiesPresent
            client_p2p_network_route_matches_group_owner = $ClientP2pNetworkRouteMatchesGroupOwner
            client_p2p_network_capability_wifi_p2p = $ClientP2pNetworkVisibleApp
            client_p2p_network_capability_local_network = $false
            client_p2p_network_socket_authority_attempted = $ClientP2pNetworkSocketAuthorityPass
            client_p2p_network_socket_authority_pass = $ClientP2pNetworkSocketAuthorityPass
            client_app_network_permissions_all_granted = $true
            client_app_network_permissions_all_declared_granted = $false
            client_sdk_int = 33
            client_target_sdk_int = 35
            client_permission_nearby_wifi_devices_applicable = $true
            client_permission_access_fine_location_applicable = $false
            client_permission_access_fine_location_manifest_max_sdk = 32
            udp_network_bound_receiver_observed_packets = $UdpNetworkBoundReceiverObservedPackets
            udp_network_bound_receiver_observed_source_address = $(if ($UdpNetworkBoundReceiverObservedPackets -gt 0) { "192.168.49.46" } else { "" })
            udp_network_bound_receiver_observed_source_matches_client_p2p = [bool]($UdpNetworkBoundReceiverObservedPackets -gt 0)
            udp_network_bound_network_handle = $(if ($UdpNetworkBoundReceiverObservedPackets -gt 0) { 123456 } else { $null })
            client_p2p_interface_local_bind_non_promoting = $LocalP2pBindNonPromoting
            client_p2p_interface_local_bind_socket_authority = $(if ($LocalP2pBindNonPromoting) { "network_interface_local_p2p_address_bind" } else { "" })
            client_p2p_interface_local_bind_udp_attempted = $LocalP2pBindNonPromoting
            client_p2p_interface_local_bind_udp_pass = $LocalP2pBindUdpPass
            client_p2p_interface_local_bind_udp_receiver_observed_packets = $LocalP2pBindUdpReceiverObservedPackets
            client_p2p_interface_local_bind_udp_receiver_observed_source_address = $(if ($LocalP2pBindUdpReceiverObservedPackets -gt 0) { "192.168.49.46" } else { "" })
            client_p2p_interface_local_bind_udp_receiver_observed_source_matches_client_p2p = [bool]($LocalP2pBindUdpReceiverObservedPackets -gt 0)
            client_p2p_interface_local_bind_tcp_attempted = $LocalP2pBindNonPromoting
            client_p2p_interface_local_bind_tcp_pass = $LocalP2pBindTcpPass
            client_p2p_interface_local_bind_tcp_receiver_accepts = $LocalP2pBindTcpReceiverAccepts
            client_p2p_interface_local_bind_tcp_receiver_accepted_source = $(if ($LocalP2pBindTcpReceiverAccepts -gt 0) { "192.168.49.46" } else { "" })
            client_p2p_interface_local_bind_tcp_stream_attempted = $LocalP2pBindNonPromoting
            client_p2p_interface_local_bind_tcp_stream_pass = $LocalP2pBindTcpStreamPass
            client_p2p_interface_local_bind_tcp_stream_receiver_accepts = $LocalP2pBindTcpStreamReceiverAccepts
            client_p2p_interface_local_bind_tcp_stream_receiver_accepted_source = $(if ($LocalP2pBindTcpStreamReceiverAccepts -gt 0) { "192.168.49.46" } else { "" })
            client_p2p_interface_local_bind_tcp_stream_client_to_owner_rx_bytes = $(if ($LocalP2pBindTcpStreamPass) { $LocalP2pBindTcpStreamBytesPerDirection } else { 0 })
            client_p2p_interface_local_bind_tcp_stream_owner_to_client_rx_bytes = $(if ($LocalP2pBindTcpStreamPass) { $LocalP2pBindTcpStreamBytesPerDirection } else { 0 })
            qcl041_local_p2p_bind_stream_authority = $(if ($LocalP2pBindTcpStreamPass) { "diagnostic_pass" } else { "not_proven" })
            qcl100_android_network_authority = $(if ($Pass) { "pass" } else { "blocked" })
            qcl100_same_group_simultaneous_native_render = "not_promoted"
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
        network_visibility_deep_trace = New-Qcl100Qcl041MatrixSelfTestNetworkVisibilityDeepTrace `
            -Pass $Pass `
            -LocalP2pBindTcpStreamPass $LocalP2pBindTcpStreamPass
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
    $localP2pBindOnlyPath = Join-Path $OutputDirectory "qcl041-matrix-gate-selftest-local-p2p-bind-only-summary.json"
    $olderCurrentWlanPath = Join-Path $OutputDirectory "qcl041-matrix-gate-selftest-older-current-wlan-summary.json"
    $strictApDisconnectedPath = Join-Path $OutputDirectory "qcl041-matrix-gate-selftest-strict-ap-disconnected-summary.json"
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
    Write-JsonFile -Value (New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $true -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId $expectedMatrixRunId -ReceiverObservedUdpModes @() -ClientToOwnerWifiDirectUdpMatrixModePass $false -ClientToOwnerAppBoundUdpSocketPass $false -ClientP2pNetworkSocketAuthorityPass $false -UdpNetworkBoundReceiverObservedPackets 0) -Path $udpMissingPath
    Write-JsonFile -Value (New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $true -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId $expectedMatrixRunId) -Path $controlTcpPath
    Write-JsonFile -Value (New-Qcl100Qcl041MatrixGateSelfTestSummary `
            -Pass $false `
            -OwnerSerial $expectedOwnerSerial `
            -ClientSerial $expectedClientSerial `
            -RunId $expectedMatrixRunId `
            -ReceiverObservedBytes $true `
            -ReceiverObservedUdpModes @("udp_local_p2p_bind_echo") `
            -ReceiverObservedTcpModes @("tcp_local_p2p_bind_socket", "tcp_local_p2p_bind_stream_socket") `
            -ClientToOwnerWifiDirectUdpMatrixModePass $false `
            -ClientToOwnerAppBoundUdpSocketPass $false `
            -LocalP2pBindNonPromoting $true `
            -LocalP2pBindUdpPass $true `
            -LocalP2pBindUdpReceiverObservedPackets 4 `
            -LocalP2pBindTcpPass $true `
            -LocalP2pBindTcpReceiverAccepts 1 `
            -LocalP2pBindTcpStreamPass $true `
            -LocalP2pBindTcpStreamReceiverAccepts 1 `
            -LocalP2pBindTcpStreamBytesPerDirection 4194304) -Path $localP2pBindOnlyPath
    $controlTcpGateIncompleteMatrixSummary = New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $true -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId $expectedMatrixRunId
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
    $olderCurrentWlanSummary = New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $true -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId "qcl041-matrix-gate-selftest-older-current-wlan"
    $olderCurrentWlanSummary.require_infrastructure_wifi_disconnected = $false
    $olderCurrentWlanSummary.require_p2p0_ipv4_cleared = $false
    $olderCurrentWlanSummary.require_candidate_wifi_direct_routes_clear = $false
    $olderCurrentWlanSummary.preflight.infrastructure_wifi_disconnected = $false
    $olderCurrentWlanSummary.preflight.p2p0_ipv4_cleared = $true
    $olderCurrentWlanSummary.preflight.candidate_wifi_direct_prelaunch_routes_clear = $true
    Write-JsonFile -Value $olderCurrentWlanSummary -Path $olderCurrentWlanPath
    $strictApDisconnectedSummary = New-Qcl100Qcl041MatrixGateSelfTestSummary -Pass $false -OwnerSerial $expectedOwnerSerial -ClientSerial $expectedClientSerial -RunId "qcl041-matrix-gate-selftest-strict-ap-disconnected"
    $strictApDisconnectedSummary.status = "blocked"
    $strictApDisconnectedSummary.blocked_reason = "qcl041_client_p2p_network_not_visible"
    $strictApDisconnectedSummary.preflight.infrastructure_wifi_disconnected = $true
    $strictApDisconnectedSummary.preflight.p2p0_ipv4_cleared = $true
    $strictApDisconnectedSummary.preflight.candidate_wifi_direct_prelaunch_routes_clear = $true
    $strictApDisconnectedSummary.app_network_visibility = [ordered]@{
        decision = "qcl041_client_p2p_network_not_visible"
        client_qcl041_p2p_network_visible = $false
        client_wifi_p2p_network_request_visible = $false
        shell_client_route_get_from_p2p_source_uses_p2p0 = $true
    }
    Write-JsonFile -Value $strictApDisconnectedSummary -Path $strictApDisconnectedPath
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
    if (-not [bool]$passEvidence.network_visibility_deep_trace_expected_rows_present -or
            [int]$passEvidence.network_visibility_deep_trace_row_count -ne 12 -or
            $passEvidence.network_visibility_deep_trace_classification -ne "android_connectivitymanager_network_authority_pass") {
        throw "QCL100 QCL041 matrix gate self-test expected pass summary to preserve all network visibility deep-trace rows."
    }
    if ([bool]$passEvidence.network_visibility_deep_trace_local_p2p_promotes_qcl100) {
        throw "QCL100 QCL041 matrix gate self-test expected local p2p bind evidence not to promote QCL100."
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
    if ([bool]$udpMissingEvidence.passed -or $udpMissingEvidence.blocked_reason_for_qcl100 -ne "qcl041_client_p2p_udp_network_bound_not_receiver_observed") {
        throw "QCL100 QCL041 matrix gate self-test expected UDP summary without strict network-bound echo evidence to fail on missing UDP socket authority."
    }
    $localP2pBindOnlyEvidence = Get-Qcl100Qcl041MatrixGateEvidence `
        -Path $localP2pBindOnlyPath `
        -ExpectedOwnerSerial $expectedOwnerSerial `
        -ExpectedClientSerial $expectedClientSerial `
        -ExpectedRunId $expectedMatrixRunId `
        -Qcl082TransportProtocol "udp" `
        -MaxAgeSeconds $maxAgeSeconds `
        -RequireFresh
    if ([bool]$localP2pBindOnlyEvidence.passed -or $localP2pBindOnlyEvidence.blocked_reason_for_qcl100 -ne "qcl041_client_p2p_network_callback_not_seen") {
        throw "QCL100 QCL041 matrix gate self-test expected local p2p bind-only diagnostic evidence to fail on missing callback-visible Network."
    }
    if (-not [bool]$localP2pBindOnlyEvidence.local_p2p_bind_diagnostic_non_promoting -or -not [bool]$localP2pBindOnlyEvidence.local_p2p_bind_udp_pass -or -not [bool]$localP2pBindOnlyEvidence.local_p2p_bind_tcp_pass -or -not [bool]$localP2pBindOnlyEvidence.local_p2p_bind_tcp_stream_pass) {
        throw "QCL100 QCL041 matrix gate self-test expected local p2p bind-only diagnostic fields to be preserved as non-promoting evidence."
    }
    if ($localP2pBindOnlyEvidence.qcl041_local_p2p_bind_stream_authority -ne "diagnostic_pass" -or
            $localP2pBindOnlyEvidence.qcl100_android_network_authority -ne "blocked" -or
            $localP2pBindOnlyEvidence.qcl100_same_group_simultaneous_native_render -ne "not_promoted") {
        throw "QCL100 QCL041 matrix gate self-test expected local p2p bind-only authority labels to stay non-promoting."
    }
    if (-not [bool]$localP2pBindOnlyEvidence.network_visibility_deep_trace_expected_rows_present -or
            $localP2pBindOnlyEvidence.network_visibility_deep_trace_classification -ne "p2p_framework_connected_local_bind_transport_only" -or
            [bool]$localP2pBindOnlyEvidence.network_visibility_deep_trace_local_p2p_promotes_qcl100) {
        throw "QCL100 QCL041 matrix gate self-test expected local p2p bind-only deep-trace classification to stay diagnostic-only."
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
    $comparisonEvidence = Get-Qcl100Qcl041MatrixArtifactComparison `
        -OlderCurrentWlanSummaryPath $olderCurrentWlanPath `
        -StrictApDisconnectedSummaryPath $strictApDisconnectedPath
    if ($comparisonEvidence.normalized_reason -ne "older_current_wlan_pass_missing_strict_preflight_requirements") {
        throw "QCL100 QCL041 matrix gate self-test expected artifact comparison to reject older current-WLAN evidence on strict preflight requirements."
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
        local_p2p_bind_only_summary_path = $localP2pBindOnlyPath
        older_current_wlan_summary_path = $olderCurrentWlanPath
        strict_ap_disconnected_summary_path = $strictApDisconnectedPath
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
        local_p2p_bind_only_case = $localP2pBindOnlyEvidence
        artifact_comparison_case = $comparisonEvidence
        passed = $true
    }
    Write-JsonFile -Value $selfTest -Path (Join-Path $OutputDirectory "qcl100-qcl041-matrix-gate-self-test.json")
    return $selfTest
}
