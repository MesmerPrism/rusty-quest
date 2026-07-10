# Shared direct p2p media topology builders for Quest-to-Quest RMANVID1 flows.

function Read-RustyQuestAdbAppFile {
    param(
        [Parameter(Mandatory=$true)][string]$AdbPath,
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$Package,
        [Parameter(Mandatory=$true)][string]$DevicePath,
        [int]$TimeoutSeconds = 5
    )
    $effectiveTimeoutSeconds = [Math]::Max(1, $TimeoutSeconds)
    $tempPrefix = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "rusty-quest-adb-read-" + [Guid]::NewGuid().ToString("N"))
    $stdoutPath = "$tempPrefix.stdout.txt"
    $stderrPath = "$tempPrefix.stderr.txt"
    $process = $null
    try {
        $process = Start-Process `
            -FilePath $AdbPath `
            -ArgumentList @("-s", $Serial, "exec-out", "run-as", $Package, "cat", $DevicePath) `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -WindowStyle Hidden `
            -PassThru
        if (-not $process.WaitForExit($effectiveTimeoutSeconds * 1000)) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            return [ordered]@{
                status = "timeout"
                timed_out = $true
                exit_code = $null
                stdout = ""
                stderr = ""
                timeout_seconds = $effectiveTimeoutSeconds
            }
        }
        $process.Refresh()
        $stdout = if (Test-Path -LiteralPath $stdoutPath) { Get-Content -Raw -LiteralPath $stdoutPath } else { "" }
        $stderr = if (Test-Path -LiteralPath $stderrPath) { Get-Content -Raw -LiteralPath $stderrPath } else { "" }
        return [ordered]@{
            status = if ($process.ExitCode -eq 0) { "pass" } else { "fail" }
            timed_out = $false
            exit_code = $process.ExitCode
            stdout = $stdout
            stderr = $stderr
            timeout_seconds = $effectiveTimeoutSeconds
        }
    } finally {
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
}

function Save-RustyQuestJsonArtifactFromStdout {
    param(
        [string]$Content,
        [string]$OutPath,
        [string]$Label = "",
        $ExitCode = $null
    )
    if ([string]::IsNullOrWhiteSpace($Content)) {
        return [ordered]@{
            saved = $false
            artifact = $null
            error = "empty_stdout"
            exit_code = $ExitCode
            accepted_with_nonzero_exit_code = $false
        }
    }
    try {
        $artifact = [string]$Content | ConvertFrom-Json
        [string]$Content | Set-Content -Encoding UTF8 -Path $OutPath
        $acceptedWithNonzeroExit = [bool]($null -ne $ExitCode -and [int]$ExitCode -ne 0)
        return [ordered]@{
            saved = $true
            artifact = $artifact
            out_path = $OutPath
            label = $Label
            exit_code = $ExitCode
            accepted_with_nonzero_exit_code = $acceptedWithNonzeroExit
            warning = if ($acceptedWithNonzeroExit) { "qcl041_json_stdout_accepted_with_nonzero_exit_code" } else { "" }
            error = ""
        }
    } catch {
        return [ordered]@{
            saved = $false
            artifact = $null
            out_path = $OutPath
            label = $Label
            exit_code = $ExitCode
            accepted_with_nonzero_exit_code = $false
            warning = ""
            error = "json_parse_failed: $($_.Exception.Message)"
        }
    }
}

function Get-RustyQuestDirectP2pTransportRouteSpec {
    param(
        [Parameter(Mandatory=$true)][string]$PeerHost,
        [bool]$LeftLaneActive = $true,
        [bool]$RightLaneActive = $true,
        [int]$LeftTransportPort = 9079,
        [int]$RightTransportPort = 9080,
        [string]$RouteKind = "direct_p2p_tcp",
        [string]$LanePrefix = "remote-camera",
        [switch]$PackedStereo
    )
    if ($PackedStereo) {
        return "${LanePrefix}-stereo|stereo|${RouteKind}|${PeerHost}|${LeftTransportPort}"
    }
    $routes = @()
    if ($LeftLaneActive) {
        $routes += "${LanePrefix}-left|left|${RouteKind}|${PeerHost}|${LeftTransportPort}"
    }
    if ($RightLaneActive) {
        $routes += "${LanePrefix}-right|right|${RouteKind}|${PeerHost}|${RightTransportPort}"
    }
    return ($routes -join ";")
}

function New-RustyQuestRemoteCameraReceiverParams {
    param(
        [Parameter(Mandatory=$true)][string]$SessionId,
        [Parameter(Mandatory=$true)][string]$ReceiverPorts,
        [Parameter(Mandatory=$true)][string]$TransportReceivePorts,
        [string]$ReceiverBindHost = "127.0.0.1",
        [string]$TransportBindHost = "0.0.0.0"
    )
    [ordered]@{
        session_id = $SessionId
        receiver_bind_host = $ReceiverBindHost
        receiver_ports = $ReceiverPorts
        transport_bind_host = $TransportBindHost
        transport_receive_ports = $TransportReceivePorts
    }
}

function New-RustyQuestRemoteCameraSenderParams {
    param(
        [Parameter(Mandatory=$true)][string]$SessionId,
        [Parameter(Mandatory=$true)][string]$SenderSourcePorts,
        [Parameter(Mandatory=$true)][string]$MediaProfiles,
        [Parameter(Mandatory=$true)][string]$CameraIds,
        [Parameter(Mandatory=$true)][string]$QualityProfile,
        [string]$TransportRoutes = "none",
        [string]$TransportBindLocalAddress = "",
        [string]$TransportSocketAuthority = "rusty_direct_p2p_socket_authority",
        [string]$SourceHost = "127.0.0.1",
        [string]$SourceKind = "camera2_mediacodec_surface",
        [string]$CameraPermissionPolicy = "camera_permission_required",
        [string]$MediaLayout = "separate-eye-streams",
        [string]$SenderFrameLayout = ""
    )
    $params = [ordered]@{
        session_id = $SessionId
        sender_source_host = $SourceHost
        sender_source_ports = $SenderSourcePorts
        sender_source_kind = $SourceKind
        sender_media_profiles = $MediaProfiles
        sender_camera_ids = $CameraIds
        sender_camera_id = "none"
        sender_camera_facing = "none"
        sender_quality_profile = $QualityProfile
        camera_permission_policy = $CameraPermissionPolicy
        media_layout = $MediaLayout
        transport_routes = $TransportRoutes
    }
    if (-not [string]::IsNullOrWhiteSpace($SenderFrameLayout)) {
        $params.sender_frame_layout = $SenderFrameLayout
    }
    if (-not [string]::IsNullOrWhiteSpace($TransportBindLocalAddress)) {
        $params.transport_bind_local_address = $TransportBindLocalAddress
    }
    if ($TransportRoutes -ne "none" -and -not [string]::IsNullOrWhiteSpace($TransportSocketAuthority)) {
        $params.transport_socket_authority = $TransportSocketAuthority
    }
    return $params
}

function New-RustyQuestDirectP2pAuthoritySummary {
    param(
        [string]$OwnerWifiDirectAddress,
        [string]$ClientWifiDirectAddress,
        [string]$OwnerTransportRoutes,
        [string]$ClientTransportRoutes,
        [string]$OwnerTransportBindLocalAddress,
        [string]$ClientTransportBindLocalAddress,
        [string]$OwnerReceiverTransportBindHost = "",
        [string]$ClientReceiverTransportBindHost = "",
        [string]$Authority = "rusty_direct_p2p_socket_authority"
    )
    [ordered]@{
        authority = $Authority
        media_payload_plane = "binary-media"
        high_rate_json_payload = $false
        outbound_socket_binding = "sender peer sockets bind explicit QCL041-proven local p2p0 address before connecting to peer p2p0 address"
        receiver_bind_policy = "direct-p2p broker receiver transport sockets bind explicit QCL041-proven local p2p0 address after address refresh; 0.0.0.0 is only for pre-address relay/proxy flows"
        owner_wifi_direct_address = $OwnerWifiDirectAddress
        client_wifi_direct_address = $ClientWifiDirectAddress
        owner_transport_routes = $OwnerTransportRoutes
        client_transport_routes = $ClientTransportRoutes
        owner_transport_bind_local_address = $OwnerTransportBindLocalAddress
        client_transport_bind_local_address = $ClientTransportBindLocalAddress
        owner_receiver_transport_bind_host = $OwnerReceiverTransportBindHost
        client_receiver_transport_bind_host = $ClientReceiverTransportBindHost
    }
}

function Get-RustyQuestQcl041WifiDirectLocalAddress {
    param($Artifact)
    if ($null -eq $Artifact) {
        return ""
    }
    $address = [string]$Artifact.diagnostics.lifecycle.wifi_direct_local_address
    if (-not [string]::IsNullOrWhiteSpace($address)) {
        return $address.Trim()
    }
    return ""
}

function Get-RustyQuestQcl041WifiDirectAcceptedPeerAddress {
    param($Artifact)
    if ($null -eq $Artifact) {
        return ""
    }
    $address = [string]$Artifact.diagnostics.q2q_server.accepted_peer_address
    if (-not [string]::IsNullOrWhiteSpace($address)) {
        return $address.Trim()
    }
    return ""
}

function Get-RustyQuestQcl041WifiDirectSocketLocalAddress {
    param($Artifact)
    if ($null -eq $Artifact) {
        return ""
    }
    $address = [string]$Artifact.diagnostics.control_tcp.socket_bound_to_wifi_direct_local_address
    if (-not [string]::IsNullOrWhiteSpace($address)) {
        return $address.Trim()
    }
    return ""
}

function Get-RustyQuestQcl041WifiDirectGroupOwnerAddress {
    param($Artifact)
    if ($null -eq $Artifact) {
        return ""
    }
    $candidates = @(
        [string]$Artifact.diagnostics.control_tcp.wifi_p2p_info_group_owner_address,
        [string]$Artifact.diagnostics.qcl082_receive_proxy.wifi_p2p_info_group_owner_address,
        [string]$Artifact.diagnostics.qcl082_relay.wifi_p2p_info_group_owner_address,
        [string]$Artifact.diagnostics.lifecycle.group_owner_address,
        [string]$Artifact.diagnostics.lifecycle.connection_info_group_owner_address
    )
    foreach ($address in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace($address)) {
            return $address.Trim()
        }
    }
    return ""
}

function Test-RustyQuestDirectP2pAddress {
    param([string]$Address)
    return [bool]($Address -match '^192\.168\.49\.(?:[1-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-4])$')
}

function Get-RustyQuestDirectP2pAddressRefreshSummary {
    param(
        [string]$OwnerRequestedAddress,
        [string]$ClientRequestedAddress,
        [string]$OwnerObservedAddress,
        [string]$ClientObservedAddress
    )
    $ownerEffective = if ([string]::IsNullOrWhiteSpace($OwnerObservedAddress)) { $OwnerRequestedAddress } else { $OwnerObservedAddress }
    $clientEffective = if ([string]::IsNullOrWhiteSpace($ClientObservedAddress)) { $ClientRequestedAddress } else { $ClientObservedAddress }
    $issues = @()
    if ([string]::IsNullOrWhiteSpace($OwnerObservedAddress)) {
        $issues += "owner_observed_p2p_address_missing"
    }
    if ([string]::IsNullOrWhiteSpace($ClientObservedAddress)) {
        $issues += "client_observed_p2p_address_missing"
    }
    if (-not [string]::IsNullOrWhiteSpace($OwnerObservedAddress) -and -not (Test-RustyQuestDirectP2pAddress -Address $OwnerObservedAddress)) {
        $issues += "owner_observed_p2p_address_out_of_range"
    }
    if (-not [string]::IsNullOrWhiteSpace($ClientObservedAddress) -and -not (Test-RustyQuestDirectP2pAddress -Address $ClientObservedAddress)) {
        $issues += "client_observed_p2p_address_out_of_range"
    }
    if (-not [string]::IsNullOrWhiteSpace($OwnerObservedAddress) -and
        -not [string]::IsNullOrWhiteSpace($ClientObservedAddress) -and
        $OwnerObservedAddress -eq $ClientObservedAddress) {
        $issues += "owner_client_observed_p2p_address_same"
    }
    [ordered]@{
        address_refresh_mode = "strict_observed_qcl041_direct_p2p"
        owner_requested_wifi_direct_address = $OwnerRequestedAddress
        client_requested_wifi_direct_address = $ClientRequestedAddress
        owner_observed_wifi_direct_address = $OwnerObservedAddress
        client_observed_wifi_direct_address = $ClientObservedAddress
        owner_effective_wifi_direct_address = $ownerEffective
        client_effective_wifi_direct_address = $clientEffective
        owner_address_refreshed = [bool]($ownerEffective -ne $OwnerRequestedAddress)
        client_address_refreshed = [bool]($clientEffective -ne $ClientRequestedAddress)
        ready = [bool]($issues.Count -eq 0)
        blocked_reason = if ($issues.Count -eq 0) { "" } else { "direct_p2p_address_refresh_not_ready" }
        issues = @($issues)
    }
}

function Assert-RustyQuestDirectP2pAddressRefreshReady {
    param(
        [Parameter(Mandatory=$true)]$Summary,
        [string]$Label = "direct-p2p-address-refresh"
    )
    if (-not [bool]$Summary.ready) {
        $issues = @($Summary.issues) -join ","
        if ([string]::IsNullOrWhiteSpace($issues)) {
            $issues = [string]$Summary.blocked_reason
        }
        throw "$Label blocked: direct p2p address refresh is not ready ($issues)."
    }
}
