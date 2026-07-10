# Dot-sourced helper functions for Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1.
# Keep these functions side-effect free until called by the runner facade.

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
                copy_read_operations = $_.copy_read_operations
                copy_write_operations = $_.copy_write_operations
                copy_last_read_size = $_.copy_last_read_size
                        copy_last_write_size = $_.copy_last_write_size
                        copy_last_read_age_ms = $_.copy_last_read_age_ms
                        copy_last_write_age_ms = $_.copy_last_write_age_ms
                        transport_accept_count = $_.transport_accept_count
                        local_client_accept_count = $_.local_client_accept_count
                        stream_recycle_count = $_.stream_recycle_count
                        close_reason = $_.close_reason
                        error = $_.error
                        peer_route = $_.peer_route
                        peer_socket_authority = $_.peer_socket_authority
                        peer_socket_created_from_wifi_direct_network = $_.peer_socket_created_from_wifi_direct_network
                        peer_socket_bound_to_wifi_direct_network = $_.peer_socket_bound_to_wifi_direct_network
                        peer_socket_network_route_matches_peer = $_.peer_socket_network_route_matches_peer
                        peer_socket_network_wifi_transport = $_.peer_socket_network_wifi_transport
                        peer_socket_network_direct_p2p_ready = $_.peer_socket_network_direct_p2p_ready
                        peer_socket_wifi_direct_bind_required = $_.peer_socket_wifi_direct_bind_required
                        peer_socket_wifi_direct_bind_attempts = $_.peer_socket_wifi_direct_bind_attempts
                        peer_socket_network_interface = $_.peer_socket_network_interface
                        peer_socket_network_selection = $_.peer_socket_network_selection
                        peer_socket_local_interface = $_.peer_socket_local_interface
                        peer_socket_local_interface_is_p2p = $_.peer_socket_local_interface_is_p2p
                        peer_socket_bound_local_address = $_.peer_socket_bound_local_address
                        peer_socket_local_address_same_subnet = $_.peer_socket_local_address_same_subnet
                        peer_socket_local_address_direct_p2p_ready = $_.peer_socket_local_address_direct_p2p_ready
                        peer_socket_direct_p2p_ready = $_.peer_socket_direct_p2p_ready
                        peer_socket_bind_local_address_error = $_.peer_socket_bind_local_address_error
                        peer_socket_local_address_error = $_.peer_socket_local_address_error
                        transport_host = $_.transport_host
                        transport_server_socket_open = $_.transport_server_socket_open
                        transport_server_bind_attempt_unix_ms = $_.transport_server_bind_attempt_unix_ms
                        transport_server_bound_unix_ms = $_.transport_server_bound_unix_ms
                        transport_server_bound_address = $_.transport_server_bound_address
                        transport_server_bound_port = $_.transport_server_bound_port
                        transport_server_bound_interface = $_.transport_server_bound_interface
                        transport_server_bind_error = $_.transport_server_bind_error
                        receiver_ready = $_.receiver_ready
                        receiver_transport_ready = $_.receiver_transport_ready
                        receiver_local_ready = $_.receiver_local_ready
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
                        first_packet_elapsed_ms = if ($_.source_lanes -and $_.source_lanes.Count -gt 0) { $_.source_lanes[0].first_packet_elapsed_ms } else { $null }
                        last_packet_elapsed_ms = if ($_.source_lanes -and $_.source_lanes.Count -gt 0) { $_.source_lanes[0].last_packet_elapsed_ms } else { $null }
                        last_packet_unix_ms = if ($_.source_lanes -and $_.source_lanes.Count -gt 0) { $_.source_lanes[0].last_packet_unix_ms } else { $null }
                        last_packet_age_ms = if ($_.source_lanes -and $_.source_lanes.Count -gt 0) { $_.source_lanes[0].last_packet_age_ms } else { $null }
                        camera2_capture_freshness = $_.camera2_capture_freshness
                    }
                })
            }
        }
    }
}

function Get-Qcl100ActiveMediaEyes {
    if ($packedMediaLayout) {
        return "stereo"
    }
    if ($leftLaneActive) {
        Write-Output "left"
    }
    if ($rightLaneActive) {
        Write-Output "right"
    }
}

function Get-BrokerReceiverObservedFreshness {
    param($BrokerStatus)
    $receiverLanes = @()
    if ($null -ne $BrokerStatus -and $null -ne $BrokerStatus.lanes) {
        $receiverLanes = @($BrokerStatus.lanes | Where-Object { $_.role -eq "receiver" })
    }
    $records = @()
    $eyes = @(Get-Qcl100ActiveMediaEyes)
    foreach ($eye in $eyes) {
        $lane = @($receiverLanes | Where-Object { $_.eye -eq $eye } | Select-Object -First 1)[0]
        $bytesReceived = ConvertTo-LongSafe $lane.bytes_received
        $copyReadBytes = ConvertTo-LongSafe $lane.copy_bytes_read
        $copyWriteBytes = ConvertTo-LongSafe $lane.copy_bytes_written
        $copyReadAgeValue = Get-Qcl100SummaryProperty -Object $lane -Name "copy_last_read_age_ms"
        $copyWriteAgeValue = Get-Qcl100SummaryProperty -Object $lane -Name "copy_last_write_age_ms"
        $copyReadAgeMs = ConvertTo-LongSafe $copyReadAgeValue
        $copyWriteAgeMs = ConvertTo-LongSafe $copyWriteAgeValue
        $readFresh = [bool]($null -ne $copyReadAgeValue -and $copyReadAgeMs -ge 0L -and $copyReadAgeMs -le 5000L)
        $writeFresh = [bool]($null -ne $copyWriteAgeValue -and $copyWriteAgeMs -ge 0L -and $copyWriteAgeMs -le 5000L)
        $fresh = [bool]($bytesReceived -gt 0L -and $copyReadBytes -gt 0L -and $copyWriteBytes -gt 0L -and $readFresh -and $writeFresh)
        $records += [ordered]@{
            eye = $eye
            status = $lane.state
            bytes_received = $bytesReceived
            copy_bytes_read = $copyReadBytes
            copy_bytes_written = $copyWriteBytes
            copy_last_read_age_ms = if ($null -ne $copyReadAgeValue) { $copyReadAgeMs } else { $null }
            copy_last_write_age_ms = if ($null -ne $copyWriteAgeValue) { $copyWriteAgeMs } else { $null }
            copy_read_operations = ConvertTo-LongSafe $lane.copy_read_operations
            copy_write_operations = ConvertTo-LongSafe $lane.copy_write_operations
            transport_accept_count = ConvertTo-LongSafe $lane.transport_accept_count
            local_client_accept_count = ConvertTo-LongSafe $lane.local_client_accept_count
            stream_recycle_count = ConvertTo-LongSafe $lane.stream_recycle_count
            close_reason = $lane.close_reason
            error = $lane.error
            fresh_receiver_observed_bytes = $fresh
        }
    }
    $freshRecords = @($records | Where-Object { $_.fresh_receiver_observed_bytes })
    $observedRecords = @($records | Where-Object { (ConvertTo-LongSafe $_.bytes_received) -gt 0L })
    [ordered]@{
        required = "active broker receiver lanes report positive receiver-observed bytes and read/write copy ages within 5s of final status"
        max_final_age_ms = 5000
        lane_mode = $LaneMode
        expected_lane_count = $activeLaneCount
        lane_count = $records.Count
        receiver_observed_lane_count = $observedRecords.Count
        fresh_receiver_observed_lane_count = $freshRecords.Count
        receiver_observed_byte_count = ($records | ForEach-Object { ConvertTo-LongSafe $_.bytes_received } | Measure-Object -Sum).Sum
        lanes = $records
        fresh = [bool]($records.Count -eq $activeLaneCount -and $freshRecords.Count -eq $records.Count)
    }
}

function Get-Qcl100DirectP2pSenderAuthority {
    param(
        $BrokerStatus,
        [bool]$Required = $true
    )
    $senderLanes = @()
    if ($null -ne $BrokerStatus -and $null -ne $BrokerStatus.lanes) {
        $senderLanes = @($BrokerStatus.lanes | Where-Object { $_.role -eq "sender" })
    }
    $records = @()
    $eyes = @(Get-Qcl100ActiveMediaEyes)
    foreach ($eye in $eyes) {
        $lane = @($senderLanes | Where-Object { $_.eye -eq $eye } | Select-Object -First 1)[0]
        $bytesSent = ConvertTo-LongSafe $lane.bytes_sent
        $records += [ordered]@{
            eye = $eye
            state = $lane.state
            bytes_sent = $bytesSent
            peer_route = $lane.peer_route
            peer_socket_authority = $lane.peer_socket_authority
            peer_socket_bound_local_address = $lane.peer_socket_bound_local_address
            peer_socket_local_interface = $lane.peer_socket_local_interface
            peer_socket_network_interface = $lane.peer_socket_network_interface
            peer_socket_network_selection = $lane.peer_socket_network_selection
            peer_socket_bound_to_wifi_direct_network = $lane.peer_socket_bound_to_wifi_direct_network
            peer_socket_network_route_matches_peer = $lane.peer_socket_network_route_matches_peer
            peer_socket_network_direct_p2p_ready = $lane.peer_socket_network_direct_p2p_ready
            peer_socket_local_interface_is_p2p = $lane.peer_socket_local_interface_is_p2p
            peer_socket_local_address_direct_p2p_ready = $lane.peer_socket_local_address_direct_p2p_ready
            peer_socket_direct_p2p_ready = $lane.peer_socket_direct_p2p_ready
            peer_socket_wifi_direct_bind_required = $lane.peer_socket_wifi_direct_bind_required
            peer_socket_wifi_direct_bind_attempts = ConvertTo-LongSafe $lane.peer_socket_wifi_direct_bind_attempts
            peer_socket_local_address_same_subnet = $lane.peer_socket_local_address_same_subnet
            direct_p2p_sender_authority_accepted = [bool](
                $lane.peer_socket_authority -eq "rusty_direct_p2p_socket_authority" -and
                -not [string]::IsNullOrWhiteSpace([string]$lane.peer_socket_bound_local_address) -and
                [string]$lane.peer_socket_local_interface -eq "p2p0" -and
                (Test-Qcl100Truthy $lane.peer_socket_wifi_direct_bind_required) -and
                (Test-Qcl100Truthy $lane.peer_socket_local_address_same_subnet) -and
                (Test-Qcl100Truthy $lane.peer_socket_local_interface_is_p2p) -and
                (Test-Qcl100Truthy $lane.peer_socket_direct_p2p_ready) -and
                $bytesSent -gt 0L)
        }
    }
    $acceptedRecords = @($records | Where-Object { $_.direct_p2p_sender_authority_accepted })
    [ordered]@{
        required = "broker sender lanes must use rusty_direct_p2p_socket_authority, bind a local p2p0 source address on the peer subnet, and report positive media bytes"
        required_by_direction = [bool]$Required
        expected_lane_count = if ($Required) { $activeLaneCount } else { 0 }
        lane_count = $records.Count
        accepted_lane_count = $acceptedRecords.Count
        lanes = $records
        accepted = [bool]((-not $Required) -or ($records.Count -eq $activeLaneCount -and $acceptedRecords.Count -eq $records.Count))
    }
}

function Get-Qcl100DirectP2pReceiverAuthority {
    param(
        $BrokerStatus,
        [string]$ExpectedLocalAddress,
        [bool]$Required = $true
    )
    $receiverLanes = @()
    if ($null -ne $BrokerStatus -and $null -ne $BrokerStatus.lanes) {
        $receiverLanes = @($BrokerStatus.lanes | Where-Object { $_.role -eq "receiver" })
    }
    $records = @()
    $eyes = @(Get-Qcl100ActiveMediaEyes)
    foreach ($eye in $eyes) {
        $lane = @($receiverLanes | Where-Object { $_.eye -eq $eye } | Select-Object -First 1)[0]
        $transportPort = ConvertTo-LongSafe (Get-Qcl100SummaryProperty -Object $lane -Name "transport_port")
        $boundPort = ConvertTo-LongSafe (Get-Qcl100SummaryProperty -Object $lane -Name "transport_server_bound_port")
        $transportHost = [string](Get-Qcl100SummaryProperty -Object $lane -Name "transport_host")
        $boundAddress = [string](Get-Qcl100SummaryProperty -Object $lane -Name "transport_server_bound_address")
        $boundInterface = [string](Get-Qcl100SummaryProperty -Object $lane -Name "transport_server_bound_interface")
        $socketOpen = Test-Qcl100Truthy (Get-Qcl100SummaryProperty -Object $lane -Name "transport_server_socket_open")
        $receiverReady = Test-Qcl100Truthy (Get-Qcl100SummaryProperty -Object $lane -Name "receiver_ready")
        $receiverTransportReady = Test-Qcl100Truthy (Get-Qcl100SummaryProperty -Object $lane -Name "receiver_transport_ready")
        $bindAccepted = [bool](
            -not [string]::IsNullOrWhiteSpace($ExpectedLocalAddress) -and
            $transportHost -eq $ExpectedLocalAddress -and
            $socketOpen -and
            $receiverReady -and
            $receiverTransportReady -and
            $boundAddress -eq $ExpectedLocalAddress -and
            $boundInterface -eq "p2p0" -and
            $transportPort -gt 0L -and
            $boundPort -eq $transportPort
        )
        $records += [ordered]@{
            eye = $eye
            state = Get-Qcl100SummaryProperty -Object $lane -Name "state"
            expected_local_address = $ExpectedLocalAddress
            transport_host = $transportHost
            transport_port = $transportPort
            transport_server_socket_open = $socketOpen
            transport_server_bound_address = $boundAddress
            transport_server_bound_port = $boundPort
            transport_server_bound_interface = $boundInterface
            transport_server_bind_error = Get-Qcl100SummaryProperty -Object $lane -Name "transport_server_bind_error"
            receiver_ready = $receiverReady
            receiver_transport_ready = $receiverTransportReady
            direct_p2p_receiver_authority_accepted = $bindAccepted
        }
    }
    $acceptedRecords = @($records | Where-Object { $_.direct_p2p_receiver_authority_accepted })
    [ordered]@{
        required = "broker receiver transport listeners must be runtime-bound to the QCL041-observed local p2p0 address and interface"
        required_by_direction = [bool]$Required
        expected_local_address = $ExpectedLocalAddress
        expected_lane_count = if ($Required) { $activeLaneCount } else { 0 }
        lane_count = $records.Count
        accepted_lane_count = $acceptedRecords.Count
        lanes = $records
        accepted = [bool]((-not $Required) -or ($records.Count -eq $activeLaneCount -and $acceptedRecords.Count -eq $records.Count))
    }
}

function New-Qcl100DirectP2pTopologyPath {
    param(
        [string]$Name,
        $SenderAuthority,
        $ReceiverAuthority,
        $ReceiverFreshness,
        [bool]$Required
    )
    if (-not $Required) {
        return $null
    }
    $issues = @()
    if (-not (Test-Qcl100Truthy (Get-Qcl100SummaryProperty -Object $SenderAuthority -Name "accepted"))) {
        $issues += "sender_authority_not_accepted"
    }
    if (-not (Test-Qcl100Truthy (Get-Qcl100SummaryProperty -Object $ReceiverAuthority -Name "accepted"))) {
        $issues += "receiver_authority_not_accepted"
    }
    if (-not (Test-Qcl100Truthy (Get-Qcl100SummaryProperty -Object $ReceiverFreshness -Name "fresh"))) {
        $issues += "receiver_observed_bytes_not_fresh"
    }
    [ordered]@{
        name = $Name
        mode = "rusty_direct_p2p_socket_authority"
        sender_authority_accepted = [bool]($issues -notcontains "sender_authority_not_accepted")
        receiver_authority_accepted = [bool]($issues -notcontains "receiver_authority_not_accepted")
        receiver_observed_bytes_fresh = [bool]($issues -notcontains "receiver_observed_bytes_not_fresh")
        issue = if ($issues.Count -gt 0) { $issues[0] } else { "" }
        issues = $issues
        accepted = [bool]($issues.Count -eq 0)
    }
}

function Get-Qcl100DirectP2pMediaTopologyAcceptance {
    param(
        [bool]$Required,
        [string]$LowerGateAuthority,
        [string]$MediaAuthority,
        $AddressRefresh,
        $OwnerSenderAuthority,
        $ClientSenderAuthority,
        $OwnerReceiverAuthority,
        $ClientReceiverAuthority,
        $OwnerReceiverFreshness,
        $ClientReceiverFreshness,
        [bool]$OwnerSends,
        [bool]$ClientSends,
        [bool]$OwnerReceives,
        [bool]$ClientReceives
    )
    $authorityAccepted = [bool](
        $LowerGateAuthority -eq "rusty_direct_p2p_socket_authority" -and
        $MediaAuthority -eq "rusty_direct_p2p_socket_authority"
    )
    $addressRefreshAccepted = Test-Qcl100Truthy (Get-Qcl100SummaryProperty -Object $AddressRefresh -Name "ready")
    $paths = @(@(
        New-Qcl100DirectP2pTopologyPath `
            -Name "owner_to_client" `
            -SenderAuthority $OwnerSenderAuthority `
            -ReceiverAuthority $ClientReceiverAuthority `
            -ReceiverFreshness $ClientReceiverFreshness `
            -Required:([bool]($OwnerSends -and $ClientReceives))
        New-Qcl100DirectP2pTopologyPath `
            -Name "client_to_owner" `
            -SenderAuthority $ClientSenderAuthority `
            -ReceiverAuthority $OwnerReceiverAuthority `
            -ReceiverFreshness $OwnerReceiverFreshness `
            -Required:([bool]($ClientSends -and $OwnerReceives))
    ) | Where-Object { $null -ne $_ })
    $acceptedPaths = @($paths | Where-Object { $_.accepted })
    $issues = @()
    if ($Required -and -not $authorityAccepted) {
        $issues += "rusty_direct_p2p_socket_authority_not_selected"
    }
    if ($Required -and -not $addressRefreshAccepted) {
        $issues += "direct_p2p_address_refresh_not_ready"
    }
    $issues += @($paths | Where-Object { -not $_.accepted } | ForEach-Object { $_.name + ":" + $_.issue })
    if ($Required -and $paths.Count -eq 0) {
        $issues += "direct_p2p_active_direction_path_missing"
    }
    [ordered]@{
        required = "active broker media directions must use Rusty direct p2p sender sockets, p2p0-bound receiver listeners, fresh receiver-observed bytes, and current QCL041 addresses"
        required_by_transport_owner = [bool]$Required
        required_transport_topology = "rusty_direct_p2p_socket_authority"
        lower_gate_authority = $LowerGateAuthority
        media_authority = $MediaAuthority
        authority_accepted = $authorityAccepted
        address_refresh_accepted = $addressRefreshAccepted
        required_path_count = $paths.Count
        accepted_path_count = $acceptedPaths.Count
        rejected_path_count = $paths.Count - $acceptedPaths.Count
        direct_p2p_socket_authority_path_count = $paths.Count
        accepted_modes = if ($acceptedPaths.Count -gt 0) { @("rusty_direct_p2p_socket_authority") } else { @() }
        first_issue = if ($issues.Count -gt 0) { $issues[0] } else { "" }
        issues = $issues
        paths = $paths
        accepted = [bool]((-not $Required) -or ($authorityAccepted -and $addressRefreshAccepted -and $paths.Count -gt 0 -and $acceptedPaths.Count -eq $paths.Count))
    }
}

function Get-BrokerCameraSourceFreshness {
    param($BrokerStatus)
    $sources = @()
    if ($null -ne $BrokerStatus -and $null -ne $BrokerStatus.sender_source_runtime) {
        $sources = @($BrokerStatus.sender_source_runtime.sources)
    }
    $records = @($sources | ForEach-Object {
        $capture = $_.camera2_capture_freshness
        $captureCount = ConvertTo-LongSafe $capture.capture_callback_count
        $frameDelta = ConvertTo-LongSafe $capture.frame_number_delta
        $captureAgeMs = ConvertTo-LongSafe $capture.last_capture_age_ms
        $packetCount = ConvertTo-LongSafe $_.video_packet_count
        $packetAgeMs = ConvertTo-LongSafe $_.last_packet_age_ms
        $captureFresh = [bool](
            $capture.fresh_camera2_frames_observed -eq $true -and
            $captureCount -ge $MinFreshFrameLines -and
            $frameDelta -gt 0 -and
            $captureAgeMs -ge 0L -and
            $captureAgeMs -le 5000L
        )
        $packetFresh = [bool](
            $packetCount -ge $MinFreshFrameLines -and
            $packetAgeMs -ge 0L -and
            $packetAgeMs -le 5000L
        )
        [ordered]@{
            eye = $_.eye
            camera_id = $_.camera_id
            state = $_.state
            lane_state = $_.lane_state
            connected = $_.connected
            video_packet_count = $packetCount
            last_packet_age_ms = $packetAgeMs
            packet_fresh = $packetFresh
            capture_callback_count = $captureCount
            camera2_frame_number_delta = $frameDelta
            camera2_last_capture_age_ms = $captureAgeMs
            camera2_capture_fresh = $captureFresh
            fresh_camera_frames = [bool]($captureFresh -and $packetFresh)
            camera2_capture_freshness = $capture
        }
    })
    $freshRecords = @($records | Where-Object { $_.fresh_camera_frames })
    [ordered]@{
        required = "active local Camera2 source lanes have advancing capture callback frame numbers and encoded packets written within 5s of final status"
        minimum_frame_lines = $MinFreshFrameLines
        max_final_age_ms = 5000
        lane_mode = $LaneMode
        expected_source_count = $activeLaneCount
        source_count = $records.Count
        fresh_source_count = $freshRecords.Count
        sources = $records
        fresh = [bool]($records.Count -eq $activeLaneCount -and $freshRecords.Count -eq $records.Count)
    }
}

function Get-Qcl041ArtifactReferenceUnixMs {
    param($Artifact)
    if ($null -eq $Artifact -or $null -eq $Artifact.observed_at_utc) {
        return [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    }
    try {
        return [DateTimeOffset]::Parse(
            [string]$Artifact.observed_at_utc,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::AssumeUniversal).ToUnixTimeMilliseconds()
    } catch {
        return [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    }
}

function Get-Qcl082LastByteAgeMs {
    param(
        $Section,
        [long]$ReferenceUnixMs
    )
    $reportedAgeMs = ConvertTo-LongSafe $Section.copy_last_byte_age_ms
    if ($reportedAgeMs -eq 0L -and $null -eq $Section.copy_last_byte_age_ms) {
        $reportedAgeMs = ConvertTo-LongSafe $Section.last_byte_age_ms
    }
    $lastByteUnixMs = ConvertTo-LongSafe $Section.copy_last_byte_unix_ms
    if ($lastByteUnixMs -eq 0L -and $null -eq $Section.copy_last_byte_unix_ms) {
        $lastByteUnixMs = ConvertTo-LongSafe $Section.last_byte_unix_ms
    }
    if ($ReferenceUnixMs -gt 0L -and $lastByteUnixMs -gt 0L) {
        return [ordered]@{
            age_ms = [Math]::Max(0L, $ReferenceUnixMs - $lastByteUnixMs)
            source = "last_byte_unix_ms"
            last_byte_unix_ms = $lastByteUnixMs
            reported_age_ms = $reportedAgeMs
        }
    }
    [ordered]@{
        age_ms = $reportedAgeMs
        source = "reported_age_ms"
        last_byte_unix_ms = $lastByteUnixMs
        reported_age_ms = $reportedAgeMs
    }
}

function Get-Qcl100SummaryProperty {
    param(
        $Object,
        [string]$Name
    )
    if ($null -eq $Object -or [string]::IsNullOrWhiteSpace($Name)) {
        return $null
    }
    if ($Object -is [System.Collections.IDictionary]) {
        if ($Object.Contains($Name)) {
            return $Object[$Name]
        }
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Test-Qcl100Truthy {
    param($Value)
    if ($null -eq $Value) {
        return $false
    }
    if ($Value -is [bool]) {
        return [bool]$Value
    }
    $text = [string]$Value
    return [bool]($text.Equals("true", [StringComparison]::OrdinalIgnoreCase) -or $text -eq "1")
}

function Test-Qcl100ConcreteUdpBindAddress {
    param($Value)
    if ($null -eq $Value) {
        return $false
    }
    $text = ([string]$Value).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $false
    }
    return [bool]($text -ne "0.0.0.0" -and $text -ne "auto" -and $text -ne "::" -and $text -ne "0:0:0:0:0:0:0:0")
}

function Get-Qcl082CopyFreshness {
    param(
        $Diagnostics,
        [string]$BaseSection,
        [string]$FreshField,
        [string]$RequiredText,
        [long]$ReferenceUnixMs = 0L
    )
    $records = @()
    $eyes = @()
    if ($leftLaneActive) {
        $eyes += "left"
    }
    if ($rightLaneActive) {
        $eyes += "right"
    }
    foreach ($eye in $eyes) {
        $sectionName = "${BaseSection}_$eye"
        $section = if ($null -ne $Diagnostics) { $Diagnostics.$sectionName } else { $null }
        if ($null -eq $section -and $activeLaneCount -eq 1 -and $null -ne $Diagnostics) {
            $singleLaneSection = $Diagnostics.$BaseSection
            if ($null -ne $singleLaneSection -and $singleLaneSection.label -eq $eye) {
                $section = $singleLaneSection
            }
        }
        $bytesCopied = ConvertTo-LongSafe $section.bytes_copied
        $lastByteAge = Get-Qcl082LastByteAgeMs -Section $section -ReferenceUnixMs $ReferenceUnixMs
        $lastByteAgeMs = ConvertTo-LongSafe $lastByteAge.age_ms
        $currentElapsedMs = ConvertTo-LongSafe $section.copy_current_elapsed_ms
        $lastByteElapsedMs = ConvertTo-LongSafe $section.copy_last_byte_elapsed_ms
        $socketPrefix = if ($BaseSection -eq "qcl082_receive_proxy") { "receive_proxy_udp" } else { "relay_udp" }
        $requiredNetworkPrefix = if ($BaseSection -eq "qcl082_relay") { "relay_udp_required_usable_network_" } else { "" }
        $transportProtocol = $section.transport_protocol
        if ([string]::IsNullOrWhiteSpace([string]$transportProtocol) -and $null -ne $Diagnostics) {
            $base = $Diagnostics.$BaseSection
            if ($null -ne $base) {
                $transportProtocol = $base.transport_protocol
            }
        }
        $usesControlTcpCarrier = [bool]($transportProtocol -eq "control-tcp")
        $isUdpMediaLane = [bool]($transportProtocol -eq "udp")
        $udpSocketBindingAllowed = Get-Qcl100SummaryProperty -Object $section -Name "${socketPrefix}_wifi_direct_network_binding_allowed"
        $udpSocketBindingRequired = Get-Qcl100SummaryProperty -Object $section -Name "${socketPrefix}_wifi_direct_network_binding_required"
        $udpSocketNetworkFound = Get-Qcl100SummaryProperty -Object $section -Name "${socketPrefix}_wifi_direct_network_found"
        $udpSocketNetwork = Get-Qcl100SummaryProperty -Object $section -Name "${socketPrefix}_wifi_direct_network"
        $udpSocketNetworkHandle = Get-Qcl100SummaryProperty -Object $section -Name "${socketPrefix}_wifi_direct_network_handle"
        $udpSocketBoundToWifiDirectNetwork = Get-Qcl100SummaryProperty -Object $section -Name "${socketPrefix}_bound_to_wifi_direct_network"
        $udpSocketBindWifiDirectNetworkError = Get-Qcl100SummaryProperty -Object $section -Name "${socketPrefix}_bind_wifi_direct_network_error"
        $udpSocketAutoBindOnFirstSend = Get-Qcl100SummaryProperty -Object $section -Name "${socketPrefix}_auto_bind_on_first_send"
        $udpSocketBoundLocalAddress = Get-Qcl100SummaryProperty -Object $section -Name "${socketPrefix}_bound_local_address"
        $udpSocketBoundPort = Get-Qcl100SummaryProperty -Object $section -Name "${socketPrefix}_bound_port"
        $relayUdpProcessBoundToWifiDirectNetwork = if ($BaseSection -eq "qcl082_relay") {
            Get-Qcl100SummaryProperty -Object $section -Name "relay_udp_process_bound_to_wifi_direct_network"
        } else {
            $null
        }
        $relayUdpProcessNetworkBindReason = if ($BaseSection -eq "qcl082_relay") {
            Get-Qcl100SummaryProperty -Object $section -Name "relay_udp_process_network_bind_reason"
        } else {
            $null
        }
        $relayUdpProcessNetworkBindError = if ($BaseSection -eq "qcl082_relay") {
            Get-Qcl100SummaryProperty -Object $section -Name "relay_udp_process_network_bind_error"
        } else {
            $null
        }
        $usesAppBoundUdpMediaSocket = [bool](
            $isUdpMediaLane -and
            (Test-Qcl100Truthy $udpSocketBoundToWifiDirectNetwork)
        )
        $usesAppBoundUdpMediaProcess = [bool](
            $isUdpMediaLane -and
            (Test-Qcl100Truthy $relayUdpProcessBoundToWifiDirectNetwork)
        )
        $usesAppBoundUdpMediaLane = [bool]($usesAppBoundUdpMediaSocket -or $usesAppBoundUdpMediaProcess)
        $usesLocalP2pUdpMediaBind = [bool](
            $isUdpMediaLane -and
            -not $usesAppBoundUdpMediaSocket -and
            (Test-Qcl100ConcreteUdpBindAddress $udpSocketBoundLocalAddress)
        )
        $fresh = [bool]($bytesCopied -gt 0L -and $lastByteAgeMs -ge 0L -and $lastByteAgeMs -le 5000L)
        $records += [ordered]@{
            eye = $eye
            status = $section.status
            transport_protocol = $transportProtocol
            uses_control_tcp_media_carrier = $usesControlTcpCarrier
            uses_app_bound_udp_media_socket = $usesAppBoundUdpMediaSocket
            uses_app_bound_udp_media_process = $usesAppBoundUdpMediaProcess
            uses_app_bound_udp_media_lane = $usesAppBoundUdpMediaLane
            uses_local_p2p_udp_media_bind = $usesLocalP2pUdpMediaBind
            bytes_copied = $bytesCopied
            frames_sent = ConvertTo-LongSafe $section.frames_sent
            frames_received = ConvertTo-LongSafe $section.frames_received
            copy_current_elapsed_ms = $currentElapsedMs
            copy_last_byte_elapsed_ms = $lastByteElapsedMs
            copy_last_byte_age_ms = $lastByteAgeMs
            copy_last_byte_age_source = $lastByteAge.source
            copy_last_byte_unix_ms = $lastByteAge.last_byte_unix_ms
            reported_copy_last_byte_age_ms = $lastByteAge.reported_age_ms
            copy_completed_reason = $section.copy_completed_reason
            configured_receiver_host = $section.configured_receiver_host
            configured_receiver_port = $section.configured_receiver_port
            advertised_receive_address = $section.advertised_receive_address
            advertised_receive_port = $section.advertised_receive_port
            final_target_host = $section.final_target_host
            final_target_port = $section.final_target_port
            final_target_source = $section.final_target_source
            target_matches_udp_peer_proof = $section.target_matches_udp_peer_proof
            target_matches_advertised_receive_address = $section.target_matches_advertised_receive_address
            effective_receiver_bind_address = $section.effective_receiver_bind_address
            effective_receiver_bind_port = $section.effective_receiver_bind_port
            udp_peer_proof_source_address = $section.udp_peer_proof_source_address
            udp_peer_proof_source_port = $section.udp_peer_proof_source_port
            udp_peer_proof_tx_enabled = $section.udp_peer_proof_tx_enabled
            udp_peer_proof_tx_sent = $section.udp_peer_proof_tx_sent
            udp_peer_proof_rx_count = $section.udp_peer_proof_rx_count
            udp_peer_proof_last_seq = $section.udp_peer_proof_last_seq
            udp_peer_proof_observed_source = $section.udp_peer_proof_observed_source
            udp_peer_proof_ack_sent = $section.udp_peer_proof_ack_sent
            udp_peer_proof_hello_target_host = $section.udp_peer_proof_hello_target_host
            udp_peer_proof_hello_target_port = $section.udp_peer_proof_hello_target_port
            udp_peer_proof_ack_received = $section.udp_peer_proof_ack_received
            udp_peer_proof_ack_source_address = $section.udp_peer_proof_ack_source_address
            udp_peer_proof_ack_source_port = $section.udp_peer_proof_ack_source_port
            udp_last_sender_source_address = $section.udp_last_sender_source_address
            udp_last_sender_source_port = $section.udp_last_sender_source_port
            udp_sender_wifi_direct_network_binding_required = $section.udp_sender_wifi_direct_network_binding_required
            udp_receive_proxy_wifi_direct_network_binding_skipped = $section.udp_receive_proxy_wifi_direct_network_binding_skipped
            udp_socket_label = $socketPrefix
            udp_socket_wifi_direct_network_binding_allowed = $udpSocketBindingAllowed
            udp_socket_wifi_direct_network_binding_required = $udpSocketBindingRequired
            udp_socket_wifi_direct_network_found = $udpSocketNetworkFound
            udp_socket_wifi_direct_network = $udpSocketNetwork
            udp_socket_wifi_direct_network_handle = $udpSocketNetworkHandle
            udp_socket_bound_to_wifi_direct_network = $udpSocketBoundToWifiDirectNetwork
            udp_socket_bind_wifi_direct_network_error = $udpSocketBindWifiDirectNetworkError
            udp_socket_auto_bind_on_first_send = $udpSocketAutoBindOnFirstSend
            udp_socket_bound_local_address = $udpSocketBoundLocalAddress
            udp_socket_bound_port = $udpSocketBoundPort
            relay_udp_process_bound_to_wifi_direct_network = $relayUdpProcessBoundToWifiDirectNetwork
            relay_udp_process_network_bind_reason = $relayUdpProcessNetworkBindReason
            relay_udp_process_network_bind_error = $relayUdpProcessNetworkBindError
            relay_udp_required_usable_network_required = Get-Qcl100SummaryProperty -Object $section -Name "${requiredNetworkPrefix}required"
            relay_udp_required_usable_network_found = Get-Qcl100SummaryProperty -Object $section -Name "${requiredNetworkPrefix}found"
            relay_udp_required_usable_network_selected_network = Get-Qcl100SummaryProperty -Object $section -Name "${requiredNetworkPrefix}selected_network"
            relay_udp_required_usable_network_selected_network_handle = Get-Qcl100SummaryProperty -Object $section -Name "${requiredNetworkPrefix}selected_network_handle"
            relay_udp_required_usable_network_selected_interface = Get-Qcl100SummaryProperty -Object $section -Name "${requiredNetworkPrefix}selected_interface"
            relay_udp_required_usable_network_selected_validated = Get-Qcl100SummaryProperty -Object $section -Name "${requiredNetworkPrefix}selected_validated"
            relay_udp_required_usable_network_selected_nonvalidated_fallback = Get-Qcl100SummaryProperty -Object $section -Name "${requiredNetworkPrefix}selected_nonvalidated_fallback"
            relay_udp_required_usable_network_reject_reason = Get-Qcl100SummaryProperty -Object $section -Name "${requiredNetworkPrefix}reject_reason"
            relay_udp_required_usable_network_wait_attempts = Get-Qcl100SummaryProperty -Object $section -Name "${requiredNetworkPrefix}wait_attempts"
            relay_udp_required_usable_network_wait_elapsed_ms = Get-Qcl100SummaryProperty -Object $section -Name "${requiredNetworkPrefix}wait_elapsed_ms"
            $FreshField = $fresh
        }
    }
    $freshRecords = @($records | Where-Object { $_.$FreshField })
    $carrierRecords = @($records | Where-Object { $_.uses_control_tcp_media_carrier })
    $udpRecords = @($records | Where-Object { $_.transport_protocol -eq "udp" })
    $appBoundUdpSocketRecords = @($udpRecords | Where-Object { $_.uses_app_bound_udp_media_socket })
    $appBoundUdpProcessRecords = @($udpRecords | Where-Object { $_.uses_app_bound_udp_media_process })
    $appBoundUdpLaneRecords = @($udpRecords | Where-Object { $_.uses_app_bound_udp_media_lane })
    $localP2pUdpBindRecords = @($udpRecords | Where-Object { $_.uses_local_p2p_udp_media_bind })
    $udpLanesMissingSocketBinding = @($udpRecords | Where-Object {
        -not $_.uses_app_bound_udp_media_socket
    } | ForEach-Object { $_.eye })
    $udpLanesMissingAppBoundLane = @($udpRecords | Where-Object {
        -not $_.uses_app_bound_udp_media_lane
    } | ForEach-Object { $_.eye })
    $protocols = @($records | ForEach-Object { $_.transport_protocol } | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    } | Sort-Object -Unique)
    [ordered]@{
        required = $RequiredText
        max_final_age_ms = 5000
        reference_unix_ms = $ReferenceUnixMs
        lane_mode = $LaneMode
        expected_lane_count = $activeLaneCount
        lane_count = $records.Count
        fresh_lane_count = $freshRecords.Count
        transport_protocols = $protocols
        control_tcp_media_carrier_lane_count = $carrierRecords.Count
        all_lanes_use_control_tcp_media_carrier = [bool]($records.Count -gt 0 -and $carrierRecords.Count -eq $records.Count)
        udp_media_lane_count = $udpRecords.Count
        app_bound_udp_media_socket_lane_count = $appBoundUdpSocketRecords.Count
        app_bound_udp_media_process_lane_count = $appBoundUdpProcessRecords.Count
        app_bound_udp_media_lane_count = $appBoundUdpLaneRecords.Count
        local_p2p_bound_udp_media_lane_count = $localP2pUdpBindRecords.Count
        all_udp_lanes_use_app_bound_udp_media_socket = [bool]($udpRecords.Count -gt 0 -and $appBoundUdpSocketRecords.Count -eq $udpRecords.Count)
        all_udp_lanes_use_app_bound_udp_media_lane = [bool]($udpRecords.Count -gt 0 -and $appBoundUdpLaneRecords.Count -eq $udpRecords.Count)
        udp_lanes_missing_app_bound_udp_media_socket = $udpLanesMissingSocketBinding
        udp_lanes_missing_app_bound_udp_media_lane = $udpLanesMissingAppBoundLane
        lanes = $records
        fresh = [bool]($records.Count -eq $activeLaneCount -and $freshRecords.Count -eq $records.Count)
    }
}

function Get-Qcl082RelayFreshness {
    param(
        $Diagnostics,
        [long]$ReferenceUnixMs = 0L
    )
    Get-Qcl082CopyFreshness `
        -Diagnostics $Diagnostics `
        -BaseSection "qcl082_relay" `
        -FreshField "fresh_relay_bytes" `
        -RequiredText "active QCL-082 relay lanes have copied bytes and report a last byte within 5s of the live artifact read" `
        -ReferenceUnixMs $ReferenceUnixMs
}

function Get-Qcl082ReceiveProxyFreshness {
    param(
        $Diagnostics,
        [long]$ReferenceUnixMs = 0L
    )
    Get-Qcl082CopyFreshness `
        -Diagnostics $Diagnostics `
        -BaseSection "qcl082_receive_proxy" `
        -FreshField "fresh_receive_proxy_bytes" `
        -RequiredText "active QCL-082 receive-proxy lanes have copied bytes into the broker receiver and report a last byte within 5s of the live artifact read" `
        -ReferenceUnixMs $ReferenceUnixMs
}

function New-Qcl100Qcl082TopologyPath {
    param(
        [string]$Name,
        $Freshness,
        [bool]$Required
    )
    if (-not $Required) {
        return $null
    }
    $protocols = @($Freshness.transport_protocols | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    })
    $laneCount = ConvertTo-LongSafe $Freshness.lane_count
    $udpLaneCount = ConvertTo-LongSafe $Freshness.udp_media_lane_count
    $appBoundUdpLaneCount = ConvertTo-LongSafe $Freshness.app_bound_udp_media_lane_count
    $localP2pUdpLaneCount = ConvertTo-LongSafe $Freshness.local_p2p_bound_udp_media_lane_count
    $controlTcpLaneCount = ConvertTo-LongSafe $Freshness.control_tcp_media_carrier_lane_count
    $accepted = $false
    $mode = "none"
    $issue = ""
    if ($laneCount -le 0L) {
        $issue = "qcl082_path_has_no_lanes"
    } elseif ($protocols.Count -eq 1 -and [string]$protocols[0] -eq "control-tcp") {
        $accepted = [bool]($controlTcpLaneCount -eq $laneCount -and [bool]$Freshness.all_lanes_use_control_tcp_media_carrier)
        $mode = "justified_control_tcp_media_carrier"
        if (-not $accepted) {
            $issue = "control_tcp_media_carrier_not_all_lanes"
        }
    } elseif ($udpLaneCount -gt 0L -and $udpLaneCount -eq $laneCount) {
        $accepted = [bool]($appBoundUdpLaneCount -eq $udpLaneCount -and [bool]$Freshness.all_udp_lanes_use_app_bound_udp_media_lane)
        if (-not $accepted) {
            $issue = "udp_media_lanes_not_all_app_bound"
        }
        $mode = if ($accepted) {
            "app_bound_udp_media_lanes"
        } elseif ($localP2pUdpLaneCount -gt 0L) {
            "local_p2p_udp_media_bind_rejected"
        } else {
            "udp_media_lanes_missing_app_bound_binding"
        }
    } else {
        $issue = "qcl082_media_topology_not_app_bound_udp_or_control_tcp"
        if ($protocols.Count -gt 0) {
            $mode = "unsupported_" + ($protocols -join "_")
        }
    }
    [ordered]@{
        name = $Name
        required = $true
        accepted = $accepted
        mode = $mode
        issue = $issue
        lane_count = $laneCount
        transport_protocols = $protocols
        control_tcp_media_carrier_lane_count = $controlTcpLaneCount
        all_lanes_use_control_tcp_media_carrier = [bool]$Freshness.all_lanes_use_control_tcp_media_carrier
        udp_media_lane_count = $udpLaneCount
        app_bound_udp_media_lane_count = $appBoundUdpLaneCount
        app_bound_udp_media_socket_lane_count = ConvertTo-LongSafe $Freshness.app_bound_udp_media_socket_lane_count
        local_p2p_bound_udp_media_lane_count = $localP2pUdpLaneCount
        all_udp_lanes_use_app_bound_udp_media_lane = [bool]$Freshness.all_udp_lanes_use_app_bound_udp_media_lane
        udp_lanes_missing_app_bound_udp_media_lane = $Freshness.udp_lanes_missing_app_bound_udp_media_lane
    }
}

function Get-Qcl100Qcl082MediaTopologyAcceptance {
    param(
        $OwnerRelayFreshness,
        $ClientRelayFreshness,
        $OwnerReceiveProxyFreshness,
        $ClientReceiveProxyFreshness,
        [bool]$OwnerRelayRequired,
        [bool]$ClientRelayRequired,
        [bool]$OwnerReceiveProxyRequired,
        [bool]$ClientReceiveProxyRequired
    )
    $paths = @(
        New-Qcl100Qcl082TopologyPath -Name "owner_relay" -Freshness $OwnerRelayFreshness -Required:$OwnerRelayRequired
        New-Qcl100Qcl082TopologyPath -Name "client_relay" -Freshness $ClientRelayFreshness -Required:$ClientRelayRequired
        New-Qcl100Qcl082TopologyPath -Name "owner_receive_proxy" -Freshness $OwnerReceiveProxyFreshness -Required:$OwnerReceiveProxyRequired
        New-Qcl100Qcl082TopologyPath -Name "client_receive_proxy" -Freshness $ClientReceiveProxyFreshness -Required:$ClientReceiveProxyRequired
    ) | Where-Object { $null -ne $_ }
    $acceptedPaths = @($paths | Where-Object { $_.accepted })
    $issues = @($paths | Where-Object { -not $_.accepted } | ForEach-Object { $_.name + ":" + $_.issue })
    $modes = @($paths | ForEach-Object { $_.mode } | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    } | Sort-Object -Unique)
    $acceptedModes = @($acceptedPaths | ForEach-Object { $_.mode } | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    } | Sort-Object -Unique)
    $controlTcpCarrierPaths = @($paths | Where-Object { $_.mode -eq "justified_control_tcp_media_carrier" })
    $appBoundUdpPaths = @($paths | Where-Object { $_.mode -eq "app_bound_udp_media_lanes" })
    $localP2pRejectedPaths = @($paths | Where-Object { $_.mode -eq "local_p2p_udp_media_bind_rejected" })
    $unsupportedPaths = @($paths | Where-Object { $_.issue -eq "qcl082_media_topology_not_app_bound_udp_or_control_tcp" })
    [ordered]@{
        required = "active QCL082 media topology paths must be app-bound UDP media lanes or the justified control-tcp media carrier"
        required_transport_topology = "app_bound_udp_media_lanes_or_justified_control_tcp_media_carrier"
        required_path_count = $paths.Count
        accepted_path_count = $acceptedPaths.Count
        rejected_path_count = $paths.Count - $acceptedPaths.Count
        mode_count = $modes.Count
        modes = $modes
        accepted_modes = $acceptedModes
        control_tcp_media_carrier_path_count = $controlTcpCarrierPaths.Count
        app_bound_udp_media_path_count = $appBoundUdpPaths.Count
        local_p2p_udp_media_rejected_path_count = $localP2pRejectedPaths.Count
        unsupported_media_topology_path_count = $unsupportedPaths.Count
        first_issue = if ($issues.Count -gt 0) { $issues[0] } else { "" }
        issues = $issues
        paths = $paths
        accepted = [bool]($paths.Count -gt 0 -and $acceptedPaths.Count -eq $paths.Count)
    }
}

function New-Qcl100TransportClaims {
    param(
        [string]$Direction,
        [string]$LaneMode = "stereo",
        $FreshnessAcceptance,
        $MediaTopologyAcceptance,
        $DirectP2pMediaTopologyAcceptance = $null,
        [bool]$MediaTopologyRequired = $true
    )
    $passed = Test-Qcl100Truthy (Get-Qcl100SummaryProperty -Object $FreshnessAcceptance -Name "passed")
    $duplexDirection = [bool]($Direction -eq "duplex")
    $stereoLaneMode = [bool]($LaneMode -eq "stereo")
    $activeDirectionStreamRenderClaimed = [bool]$passed
    $requiredPathCount = ConvertTo-LongSafe (Get-Qcl100SummaryProperty -Object $MediaTopologyAcceptance -Name "required_path_count")
    $acceptedPathCount = ConvertTo-LongSafe (Get-Qcl100SummaryProperty -Object $MediaTopologyAcceptance -Name "accepted_path_count")
    $appBoundUdpPathCount = ConvertTo-LongSafe (Get-Qcl100SummaryProperty -Object $MediaTopologyAcceptance -Name "app_bound_udp_media_path_count")
    $controlTcpPathCount = ConvertTo-LongSafe (Get-Qcl100SummaryProperty -Object $MediaTopologyAcceptance -Name "control_tcp_media_carrier_path_count")
    $mediaTopologyAccepted = Test-Qcl100Truthy (Get-Qcl100SummaryProperty -Object $MediaTopologyAcceptance -Name "accepted")
    $acceptedModes = @(Get-Qcl100SummaryProperty -Object $MediaTopologyAcceptance -Name "accepted_modes" | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    })
    $directP2pRequired = Test-Qcl100Truthy (Get-Qcl100SummaryProperty -Object $DirectP2pMediaTopologyAcceptance -Name "required_by_transport_owner")
    $directP2pAccepted = Test-Qcl100Truthy (Get-Qcl100SummaryProperty -Object $DirectP2pMediaTopologyAcceptance -Name "accepted")
    $directP2pRequiredPathCount = ConvertTo-LongSafe (Get-Qcl100SummaryProperty -Object $DirectP2pMediaTopologyAcceptance -Name "required_path_count")
    $directP2pAcceptedPathCount = ConvertTo-LongSafe (Get-Qcl100SummaryProperty -Object $DirectP2pMediaTopologyAcceptance -Name "accepted_path_count")
    $directP2pPathCount = ConvertTo-LongSafe (Get-Qcl100SummaryProperty -Object $DirectP2pMediaTopologyAcceptance -Name "direct_p2p_socket_authority_path_count")
    $directP2pAcceptedModes = @(Get-Qcl100SummaryProperty -Object $DirectP2pMediaTopologyAcceptance -Name "accepted_modes" | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    })
    $pureAppBoundUdpDuplex = [bool](
        $duplexDirection -and
        $stereoLaneMode -and
        $passed -and
        $mediaTopologyAccepted -and
        $requiredPathCount -gt 0L -and
        $appBoundUdpPathCount -eq $requiredPathCount
    )
    $pureControlTcpAlternate = [bool](
        $duplexDirection -and
        $stereoLaneMode -and
        $passed -and
        $mediaTopologyAccepted -and
        $requiredPathCount -gt 0L -and
        $controlTcpPathCount -eq $requiredPathCount
    )
    $mixedAcceptedAlternate = [bool](
        $duplexDirection -and
        $stereoLaneMode -and
        $passed -and
        $mediaTopologyAccepted -and
        $requiredPathCount -gt 0L -and
        $acceptedPathCount -eq $requiredPathCount -and
        -not $pureAppBoundUdpDuplex -and
        -not $pureControlTcpAlternate -and
        $controlTcpPathCount -gt 0L
    )
    $pureRustyDirectP2pSocketAuthority = [bool](
        $duplexDirection -and
        $stereoLaneMode -and
        $passed -and
        $directP2pRequired -and
        $directP2pAccepted -and
        $directP2pRequiredPathCount -eq 2L -and
        $directP2pAcceptedPathCount -eq 2L -and
        $directP2pPathCount -eq 2L
    )
    $recognizedSameGroupDuplexTopology = [bool](
        $pureRustyDirectP2pSocketAuthority -or
        $pureAppBoundUdpDuplex -or
        $pureControlTcpAlternate -or
        $mixedAcceptedAlternate
    )
    $status = if (-not $passed) {
        "blocked_until_same_epoch_bidirectional_media_and_renderer_freshness_pass"
    } elseif (-not $duplexDirection) {
        "active_direction_stream_render_proven"
    } elseif (-not $stereoLaneMode) {
        "same_group_duplex_diagnostic_lane_only_not_full_stereo"
    } elseif ($pureRustyDirectP2pSocketAuthority) {
        "same_group_duplex_proven_with_rusty_direct_p2p_socket_authority"
    } elseif ($pureAppBoundUdpDuplex) {
        "same_group_app_bound_udp_duplex_proven"
    } elseif ($pureControlTcpAlternate) {
        "same_group_duplex_proven_with_justified_control_tcp_alternate"
    } elseif ($mixedAcceptedAlternate) {
        "same_group_duplex_proven_with_mixed_accepted_topology"
    } elseif ($directP2pRequired -and -not $directP2pAccepted) {
        "same_group_direct_p2p_topology_not_accepted"
    } elseif (-not $MediaTopologyRequired) {
        "same_group_duplex_topology_not_required_for_broker_direct_media_no_duplex_claim"
    } elseif (-not $mediaTopologyAccepted) {
        "same_group_duplex_topology_not_accepted"
    } else {
        "same_group_duplex_topology_unclassified"
    }
    [ordered]@{
        required = "QCL100 may claim same-group duplex only after both active directions prove accepted media topology, receiver-observed bytes, p2p0 socket authority where selected, and final-window native renderer scorecards"
        direction = $Direction
        lane_mode = $LaneMode
        full_stereo_required_for_qcl100_promotion = $true
        active_direction_stream_render_claimed = $activeDirectionStreamRenderClaimed
        same_group_duplex_claimed = $recognizedSameGroupDuplexTopology
        same_group_simultaneous_duplex = $recognizedSameGroupDuplexTopology
        same_group_rusty_direct_p2p_socket_authority_duplex_claimed = $pureRustyDirectP2pSocketAuthority
        same_group_app_bound_udp_duplex_claimed = $pureAppBoundUdpDuplex
        justified_alternate_topology_claimed = [bool]($pureControlTcpAlternate -or $mixedAcceptedAlternate)
        justified_alternate_topology = if ($pureControlTcpAlternate) { "control_tcp_media_carrier" } elseif ($mixedAcceptedAlternate) { "mixed_control_tcp_and_app_bound_udp_media" } else { "" }
        blocked_until_same_epoch_bidirectional_media_and_renderer_freshness_pass = [bool]($duplexDirection -and -not $recognizedSameGroupDuplexTopology)
        status = $status
        required_transport_topology = if ($directP2pRequired) {
            Get-Qcl100SummaryProperty -Object $DirectP2pMediaTopologyAcceptance -Name "required_transport_topology"
        } else {
            Get-Qcl100SummaryProperty -Object $MediaTopologyAcceptance -Name "required_transport_topology"
        }
        accepted_modes = @($acceptedModes + $directP2pAcceptedModes | Sort-Object -Unique)
        direct_p2p_media_topology_required = $directP2pRequired
        direct_p2p_media_topology_accepted = $directP2pAccepted
        direct_p2p_media_topology_required_path_count = $directP2pRequiredPathCount
        direct_p2p_media_topology_accepted_path_count = $directP2pAcceptedPathCount
        direct_p2p_media_topology_rejected_path_count = ConvertTo-LongSafe (Get-Qcl100SummaryProperty -Object $DirectP2pMediaTopologyAcceptance -Name "rejected_path_count")
        direct_p2p_media_topology_first_issue = Get-Qcl100SummaryProperty -Object $DirectP2pMediaTopologyAcceptance -Name "first_issue"
        qcl082_media_topology_required = [bool]$MediaTopologyRequired
        qcl082_media_topology_accepted = $mediaTopologyAccepted
        qcl082_media_topology_required_path_count = $requiredPathCount
        qcl082_media_topology_accepted_path_count = $acceptedPathCount
        qcl082_media_topology_rejected_path_count = ConvertTo-LongSafe (Get-Qcl100SummaryProperty -Object $MediaTopologyAcceptance -Name "rejected_path_count")
        qcl082_media_topology_control_tcp_media_carrier_path_count = $controlTcpPathCount
        qcl082_media_topology_app_bound_udp_media_path_count = $appBoundUdpPathCount
        qcl082_media_topology_first_issue = Get-Qcl100SummaryProperty -Object $MediaTopologyAcceptance -Name "first_issue"
    }
}

function Assert-Qcl100Qcl082CarrierFreshness {
    param(
        [string]$Name,
        $Freshness,
        [bool]$ExpectedFresh,
        [int]$ExpectedCarrierLaneCount,
        [string]$ExpectedProtocol,
        [string]$FrameField,
        [long]$ExpectedLeftFrames,
        [long]$ExpectedRightFrames
    )
    if ([bool]$Freshness.fresh -ne $ExpectedFresh) {
        throw "QCL100 QCL082 carrier self-test '$Name' expected fresh=$ExpectedFresh but got $($Freshness.fresh)."
    }
    if ((ConvertTo-LongSafe $Freshness.control_tcp_media_carrier_lane_count) -ne $ExpectedCarrierLaneCount) {
        throw "QCL100 QCL082 carrier self-test '$Name' expected carrier lanes=$ExpectedCarrierLaneCount but got $($Freshness.control_tcp_media_carrier_lane_count)."
    }
    $protocols = @($Freshness.transport_protocols)
    if ($protocols.Count -ne 1 -or [string]$protocols[0] -ne $ExpectedProtocol) {
        throw "QCL100 QCL082 carrier self-test '$Name' expected protocol '$ExpectedProtocol' but got '$($protocols -join ',')'."
    }
    if ($ExpectedCarrierLaneCount -gt 0 -and -not [bool]$Freshness.all_lanes_use_control_tcp_media_carrier) {
        throw "QCL100 QCL082 carrier self-test '$Name' expected all lanes to use control-tcp media carrier."
    }
    $left = @($Freshness.lanes | Where-Object { $_.eye -eq "left" })[0]
    $right = @($Freshness.lanes | Where-Object { $_.eye -eq "right" })[0]
    if ((ConvertTo-LongSafe $left.$FrameField) -ne $ExpectedLeftFrames) {
        throw "QCL100 QCL082 carrier self-test '$Name' expected left $FrameField=$ExpectedLeftFrames but got $($left.$FrameField)."
    }
    if ((ConvertTo-LongSafe $right.$FrameField) -ne $ExpectedRightFrames) {
        throw "QCL100 QCL082 carrier self-test '$Name' expected right $FrameField=$ExpectedRightFrames but got $($right.$FrameField)."
    }
    [ordered]@{
        name = $Name
        expected_fresh = $ExpectedFresh
        fresh = [bool]$Freshness.fresh
        transport_protocols = $Freshness.transport_protocols
        control_tcp_media_carrier_lane_count = $Freshness.control_tcp_media_carrier_lane_count
        all_lanes_use_control_tcp_media_carrier = [bool]$Freshness.all_lanes_use_control_tcp_media_carrier
        left = $left
        right = $right
    }
}

function Assert-Qcl100Qcl082UdpBindingFreshness {
    param(
        [string]$Name,
        $Freshness,
        [bool]$ExpectedFresh,
        [int]$ExpectedUdpLaneCount,
        [int]$ExpectedAppBoundSocketLaneCount,
        [int]$ExpectedAppBoundLaneCount,
        [int]$ExpectedLocalP2pLaneCount
    )
    if ([bool]$Freshness.fresh -ne $ExpectedFresh) {
        throw "QCL100 QCL082 UDP binding self-test '$Name' expected fresh=$ExpectedFresh but got $($Freshness.fresh)."
    }
    if ((ConvertTo-LongSafe $Freshness.udp_media_lane_count) -ne $ExpectedUdpLaneCount) {
        throw "QCL100 QCL082 UDP binding self-test '$Name' expected UDP lanes=$ExpectedUdpLaneCount but got $($Freshness.udp_media_lane_count)."
    }
    if ((ConvertTo-LongSafe $Freshness.app_bound_udp_media_socket_lane_count) -ne $ExpectedAppBoundSocketLaneCount) {
        throw "QCL100 QCL082 UDP binding self-test '$Name' expected app-bound UDP socket lanes=$ExpectedAppBoundSocketLaneCount but got $($Freshness.app_bound_udp_media_socket_lane_count)."
    }
    if ((ConvertTo-LongSafe $Freshness.app_bound_udp_media_lane_count) -ne $ExpectedAppBoundLaneCount) {
        throw "QCL100 QCL082 UDP binding self-test '$Name' expected app-bound UDP lanes=$ExpectedAppBoundLaneCount but got $($Freshness.app_bound_udp_media_lane_count)."
    }
    if ((ConvertTo-LongSafe $Freshness.local_p2p_bound_udp_media_lane_count) -ne $ExpectedLocalP2pLaneCount) {
        throw "QCL100 QCL082 UDP binding self-test '$Name' expected local-P2P UDP lanes=$ExpectedLocalP2pLaneCount but got $($Freshness.local_p2p_bound_udp_media_lane_count)."
    }
    [ordered]@{
        name = $Name
        expected_fresh = $ExpectedFresh
        fresh = [bool]$Freshness.fresh
        udp_media_lane_count = $Freshness.udp_media_lane_count
        app_bound_udp_media_socket_lane_count = $Freshness.app_bound_udp_media_socket_lane_count
        app_bound_udp_media_process_lane_count = $Freshness.app_bound_udp_media_process_lane_count
        app_bound_udp_media_lane_count = $Freshness.app_bound_udp_media_lane_count
        local_p2p_bound_udp_media_lane_count = $Freshness.local_p2p_bound_udp_media_lane_count
        all_udp_lanes_use_app_bound_udp_media_socket = [bool]$Freshness.all_udp_lanes_use_app_bound_udp_media_socket
        all_udp_lanes_use_app_bound_udp_media_lane = [bool]$Freshness.all_udp_lanes_use_app_bound_udp_media_lane
        udp_lanes_missing_app_bound_udp_media_socket = $Freshness.udp_lanes_missing_app_bound_udp_media_socket
        udp_lanes_missing_app_bound_udp_media_lane = $Freshness.udp_lanes_missing_app_bound_udp_media_lane
    }
}

function Assert-Qcl100Qcl082MediaTopologyAcceptance {
    param(
        [string]$Name,
        $Topology,
        [bool]$ExpectedAccepted,
        [int]$ExpectedRequiredPathCount,
        [int]$ExpectedAcceptedPathCount,
        [int]$ExpectedRejectedPathCount,
        [int]$ExpectedControlTcpPathCount = -1,
        [int]$ExpectedAppBoundUdpPathCount = -1,
        [int]$ExpectedLocalP2pRejectedPathCount = -1,
        [string[]]$ExpectedAcceptedModes = @(),
        [string]$ExpectedFirstIssue = $null
    )
    if ([bool]$Topology.accepted -ne $ExpectedAccepted) {
        throw "QCL100 QCL082 media topology self-test '$Name' expected accepted=$ExpectedAccepted but got $($Topology.accepted)."
    }
    if ((ConvertTo-LongSafe $Topology.required_path_count) -ne $ExpectedRequiredPathCount) {
        throw "QCL100 QCL082 media topology self-test '$Name' expected required paths=$ExpectedRequiredPathCount but got $($Topology.required_path_count)."
    }
    if ((ConvertTo-LongSafe $Topology.accepted_path_count) -ne $ExpectedAcceptedPathCount) {
        throw "QCL100 QCL082 media topology self-test '$Name' expected accepted paths=$ExpectedAcceptedPathCount but got $($Topology.accepted_path_count)."
    }
    if ((ConvertTo-LongSafe $Topology.rejected_path_count) -ne $ExpectedRejectedPathCount) {
        throw "QCL100 QCL082 media topology self-test '$Name' expected rejected paths=$ExpectedRejectedPathCount but got $($Topology.rejected_path_count)."
    }
    if ($ExpectedControlTcpPathCount -ge 0 -and (ConvertTo-LongSafe $Topology.control_tcp_media_carrier_path_count) -ne $ExpectedControlTcpPathCount) {
        throw "QCL100 QCL082 media topology self-test '$Name' expected control-TCP carrier paths=$ExpectedControlTcpPathCount but got $($Topology.control_tcp_media_carrier_path_count)."
    }
    if ($ExpectedAppBoundUdpPathCount -ge 0 -and (ConvertTo-LongSafe $Topology.app_bound_udp_media_path_count) -ne $ExpectedAppBoundUdpPathCount) {
        throw "QCL100 QCL082 media topology self-test '$Name' expected app-bound UDP paths=$ExpectedAppBoundUdpPathCount but got $($Topology.app_bound_udp_media_path_count)."
    }
    if ($ExpectedLocalP2pRejectedPathCount -ge 0 -and (ConvertTo-LongSafe $Topology.local_p2p_udp_media_rejected_path_count) -ne $ExpectedLocalP2pRejectedPathCount) {
        throw "QCL100 QCL082 media topology self-test '$Name' expected rejected local-P2P UDP paths=$ExpectedLocalP2pRejectedPathCount but got $($Topology.local_p2p_udp_media_rejected_path_count)."
    }
    if ($null -ne $ExpectedFirstIssue -and [string]$Topology.first_issue -ne $ExpectedFirstIssue) {
        throw "QCL100 QCL082 media topology self-test '$Name' expected first issue '$ExpectedFirstIssue' but got '$($Topology.first_issue)'."
    }
    if ($ExpectedAcceptedModes.Count -gt 0) {
        $actualModes = @($Topology.accepted_modes | Sort-Object)
        $expectedModes = @($ExpectedAcceptedModes | Sort-Object)
        if (($actualModes -join ",") -ne ($expectedModes -join ",")) {
            throw "QCL100 QCL082 media topology self-test '$Name' expected accepted modes '$($expectedModes -join ',')' but got '$($actualModes -join ',')'."
        }
    }
    [ordered]@{
        name = $Name
        expected_accepted = $ExpectedAccepted
        accepted = [bool]$Topology.accepted
        required_path_count = $Topology.required_path_count
        accepted_path_count = $Topology.accepted_path_count
        rejected_path_count = $Topology.rejected_path_count
        modes = $Topology.modes
        accepted_modes = $Topology.accepted_modes
        control_tcp_media_carrier_path_count = $Topology.control_tcp_media_carrier_path_count
        app_bound_udp_media_path_count = $Topology.app_bound_udp_media_path_count
        local_p2p_udp_media_rejected_path_count = $Topology.local_p2p_udp_media_rejected_path_count
        unsupported_media_topology_path_count = $Topology.unsupported_media_topology_path_count
        first_issue = $Topology.first_issue
        issues = $Topology.issues
        paths = $Topology.paths
    }
}

function Assert-Qcl100TransportClaims {
    param(
        [string]$Name,
        $Claims,
        [bool]$ExpectedSameGroupDuplexClaimed,
        [bool]$ExpectedRustyDirectP2pDuplexClaimed = $false,
        [bool]$ExpectedAppBoundUdpDuplexClaimed,
        [bool]$ExpectedJustifiedAlternateClaimed,
        [string]$ExpectedStatus
    )
    if ([bool]$Claims.same_group_duplex_claimed -ne $ExpectedSameGroupDuplexClaimed) {
        throw "QCL100 transport-claims self-test '$Name' expected same_group_duplex_claimed=$ExpectedSameGroupDuplexClaimed but got $($Claims.same_group_duplex_claimed)."
    }
    if ([bool]$Claims.same_group_app_bound_udp_duplex_claimed -ne $ExpectedAppBoundUdpDuplexClaimed) {
        throw "QCL100 transport-claims self-test '$Name' expected same_group_app_bound_udp_duplex_claimed=$ExpectedAppBoundUdpDuplexClaimed but got $($Claims.same_group_app_bound_udp_duplex_claimed)."
    }
    if ([bool]$Claims.same_group_rusty_direct_p2p_socket_authority_duplex_claimed -ne $ExpectedRustyDirectP2pDuplexClaimed) {
        throw "QCL100 transport-claims self-test '$Name' expected same_group_rusty_direct_p2p_socket_authority_duplex_claimed=$ExpectedRustyDirectP2pDuplexClaimed but got $($Claims.same_group_rusty_direct_p2p_socket_authority_duplex_claimed)."
    }
    if ([bool]$Claims.justified_alternate_topology_claimed -ne $ExpectedJustifiedAlternateClaimed) {
        throw "QCL100 transport-claims self-test '$Name' expected justified_alternate_topology_claimed=$ExpectedJustifiedAlternateClaimed but got $($Claims.justified_alternate_topology_claimed)."
    }
    if ([string]$Claims.status -ne $ExpectedStatus) {
        throw "QCL100 transport-claims self-test '$Name' expected status '$ExpectedStatus' but got '$($Claims.status)'."
    }
    [ordered]@{
        name = $Name
        same_group_duplex_claimed = [bool]$Claims.same_group_duplex_claimed
        same_group_simultaneous_duplex = [bool]$Claims.same_group_simultaneous_duplex
        same_group_rusty_direct_p2p_socket_authority_duplex_claimed = [bool]$Claims.same_group_rusty_direct_p2p_socket_authority_duplex_claimed
        same_group_app_bound_udp_duplex_claimed = [bool]$Claims.same_group_app_bound_udp_duplex_claimed
        justified_alternate_topology_claimed = [bool]$Claims.justified_alternate_topology_claimed
        justified_alternate_topology = $Claims.justified_alternate_topology
        blocked_until_same_epoch_bidirectional_media_and_renderer_freshness_pass = [bool]$Claims.blocked_until_same_epoch_bidirectional_media_and_renderer_freshness_pass
        status = $Claims.status
        accepted_modes = $Claims.accepted_modes
    }
}

function Assert-Qcl100BrokerReceiverObservedFreshness {
    param(
        [string]$Name,
        $Freshness,
        [bool]$ExpectedFresh,
        [int]$ExpectedLaneCount,
        [int]$ExpectedFreshLaneCount,
        [long]$ExpectedByteCount
    )
    if ([bool]$Freshness.fresh -ne $ExpectedFresh) {
        throw "QCL100 broker receiver self-test '$Name' expected fresh=$ExpectedFresh but got $($Freshness.fresh)."
    }
    if ((ConvertTo-LongSafe $Freshness.lane_count) -ne $ExpectedLaneCount) {
        throw "QCL100 broker receiver self-test '$Name' expected lane count=$ExpectedLaneCount but got $($Freshness.lane_count)."
    }
    if ((ConvertTo-LongSafe $Freshness.fresh_receiver_observed_lane_count) -ne $ExpectedFreshLaneCount) {
        throw "QCL100 broker receiver self-test '$Name' expected fresh receiver lanes=$ExpectedFreshLaneCount but got $($Freshness.fresh_receiver_observed_lane_count)."
    }
    if ((ConvertTo-LongSafe $Freshness.receiver_observed_byte_count) -ne $ExpectedByteCount) {
        throw "QCL100 broker receiver self-test '$Name' expected receiver bytes=$ExpectedByteCount but got $($Freshness.receiver_observed_byte_count)."
    }
    [ordered]@{
        name = $Name
        expected_fresh = $ExpectedFresh
        fresh = [bool]$Freshness.fresh
        lane_count = $Freshness.lane_count
        receiver_observed_lane_count = $Freshness.receiver_observed_lane_count
        fresh_receiver_observed_lane_count = $Freshness.fresh_receiver_observed_lane_count
        receiver_observed_byte_count = $Freshness.receiver_observed_byte_count
        lanes = $Freshness.lanes
    }
}

function Assert-Qcl100DirectP2pSenderAuthority {
    param(
        [string]$Name,
        $Authority,
        [bool]$ExpectedAccepted,
        [int]$ExpectedLaneCount,
        [int]$ExpectedAcceptedLaneCount
    )
    if ([bool]$Authority.accepted -ne $ExpectedAccepted) {
        throw "QCL100 direct-p2p sender authority self-test '$Name' expected accepted=$ExpectedAccepted but got $($Authority.accepted)."
    }
    if ((ConvertTo-LongSafe $Authority.lane_count) -ne $ExpectedLaneCount) {
        throw "QCL100 direct-p2p sender authority self-test '$Name' expected lane count=$ExpectedLaneCount but got $($Authority.lane_count)."
    }
    if ((ConvertTo-LongSafe $Authority.accepted_lane_count) -ne $ExpectedAcceptedLaneCount) {
        throw "QCL100 direct-p2p sender authority self-test '$Name' expected accepted lanes=$ExpectedAcceptedLaneCount but got $($Authority.accepted_lane_count)."
    }
    [ordered]@{
        name = $Name
        expected_accepted = $ExpectedAccepted
        accepted = [bool]$Authority.accepted
        lane_count = $Authority.lane_count
        accepted_lane_count = $Authority.accepted_lane_count
        lanes = $Authority.lanes
    }
}

function Assert-Qcl100DirectP2pReceiverAuthority {
    param(
        [string]$Name,
        $Authority,
        [bool]$ExpectedAccepted,
        [int]$ExpectedLaneCount,
        [int]$ExpectedAcceptedLaneCount
    )
    if ([bool]$Authority.accepted -ne $ExpectedAccepted) {
        throw "QCL100 direct-p2p receiver authority self-test '$Name' expected accepted=$ExpectedAccepted but got $($Authority.accepted)."
    }
    if ((ConvertTo-LongSafe $Authority.lane_count) -ne $ExpectedLaneCount) {
        throw "QCL100 direct-p2p receiver authority self-test '$Name' expected lane count=$ExpectedLaneCount but got $($Authority.lane_count)."
    }
    if ((ConvertTo-LongSafe $Authority.accepted_lane_count) -ne $ExpectedAcceptedLaneCount) {
        throw "QCL100 direct-p2p receiver authority self-test '$Name' expected accepted lanes=$ExpectedAcceptedLaneCount but got $($Authority.accepted_lane_count)."
    }
    [ordered]@{
        name = $Name
        expected_accepted = $ExpectedAccepted
        accepted = [bool]$Authority.accepted
        lane_count = $Authority.lane_count
        accepted_lane_count = $Authority.accepted_lane_count
        lanes = $Authority.lanes
    }
}

function Assert-Qcl100DirectP2pMediaTopologyAcceptance {
    param(
        [string]$Name,
        $Topology,
        [bool]$ExpectedAccepted,
        [int]$ExpectedRequiredPathCount,
        [int]$ExpectedAcceptedPathCount,
        [string]$ExpectedFirstIssue = ""
    )
    if ([bool]$Topology.accepted -ne $ExpectedAccepted) {
        throw "QCL100 direct-p2p media topology self-test '$Name' expected accepted=$ExpectedAccepted but got $($Topology.accepted)."
    }
    if ((ConvertTo-LongSafe $Topology.required_path_count) -ne $ExpectedRequiredPathCount) {
        throw "QCL100 direct-p2p media topology self-test '$Name' expected required paths=$ExpectedRequiredPathCount but got $($Topology.required_path_count)."
    }
    if ((ConvertTo-LongSafe $Topology.accepted_path_count) -ne $ExpectedAcceptedPathCount) {
        throw "QCL100 direct-p2p media topology self-test '$Name' expected accepted paths=$ExpectedAcceptedPathCount but got $($Topology.accepted_path_count)."
    }
    if ([string]$Topology.first_issue -ne $ExpectedFirstIssue) {
        throw "QCL100 direct-p2p media topology self-test '$Name' expected first issue '$ExpectedFirstIssue' but got '$($Topology.first_issue)'."
    }
    [ordered]@{
        name = $Name
        expected_accepted = $ExpectedAccepted
        accepted = [bool]$Topology.accepted
        required_path_count = $Topology.required_path_count
        accepted_path_count = $Topology.accepted_path_count
        first_issue = $Topology.first_issue
        paths = $Topology.paths
    }
}

function Invoke-Qcl100RuntimeSummarySelfTest {
    $leftLaneActive = $true
    $rightLaneActive = $true
    $activeLaneCount = 2
    $LaneMode = "stereo"
    $referenceUnixMs = 2000000L
    $relayDiagnostics = [pscustomobject]@{
        qcl082_relay_left = [pscustomobject]@{
            label = "left"
            status = "pass"
            transport_protocol = "control-tcp"
            bytes_copied = 4096
            frames_sent = 4
            copy_last_byte_unix_ms = 1999000L
            copy_current_elapsed_ms = 1200
            copy_last_byte_elapsed_ms = 1100
            copy_completed_reason = "max_bytes"
        }
        qcl082_relay_right = [pscustomobject]@{
            label = "right"
            status = "pass"
            transport_protocol = "control-tcp"
            bytes_copied = 8192
            frames_sent = 8
            copy_last_byte_unix_ms = 1999500L
            copy_current_elapsed_ms = 1300
            copy_last_byte_elapsed_ms = 1250
            copy_completed_reason = "max_bytes"
        }
    }
    $receiveProxyDiagnostics = [pscustomobject]@{
        qcl082_receive_proxy_left = [pscustomobject]@{
            label = "left"
            status = "pass"
            transport_protocol = "control-tcp"
            bytes_copied = 4096
            frames_received = 4
            copy_last_byte_unix_ms = 1999000L
            copy_current_elapsed_ms = 1200
            copy_last_byte_elapsed_ms = 1100
            copy_completed_reason = "control_tcp_peer_end"
        }
        qcl082_receive_proxy_right = [pscustomobject]@{
            label = "right"
            status = "pass"
            transport_protocol = "control-tcp"
            bytes_copied = 8192
            frames_received = 8
            copy_last_byte_unix_ms = 1999500L
            copy_current_elapsed_ms = 1300
            copy_last_byte_elapsed_ms = 1250
            copy_completed_reason = "control_tcp_peer_end"
        }
    }
    $staleRelayDiagnostics = [pscustomobject]@{
        qcl082_relay_left = [pscustomobject]@{
            label = "left"
            status = "pass"
            transport_protocol = "control-tcp"
            bytes_copied = 4096
            frames_sent = 4
            copy_last_byte_unix_ms = 1980000L
            copy_current_elapsed_ms = 1200
            copy_last_byte_elapsed_ms = 1100
            copy_completed_reason = "deadline_elapsed"
        }
        qcl082_relay_right = [pscustomobject]@{
            label = "right"
            status = "pass"
            transport_protocol = "control-tcp"
            bytes_copied = 8192
            frames_sent = 8
            copy_last_byte_unix_ms = 1999500L
            copy_current_elapsed_ms = 1300
            copy_last_byte_elapsed_ms = 1250
            copy_completed_reason = "max_bytes"
        }
    }
    $udpRelayDiagnostics = [pscustomobject]@{
        qcl082_relay_left = [pscustomobject]@{
            label = "left"
            status = "pass"
            transport_protocol = "udp"
            bytes_copied = 4096
            frames_sent = 4
            copy_last_byte_unix_ms = 1999000L
            relay_udp_wifi_direct_network_binding_allowed = $true
            relay_udp_wifi_direct_network_binding_required = $true
            relay_udp_wifi_direct_network_found = $true
            relay_udp_bound_to_wifi_direct_network = $true
            relay_udp_bound_local_address = "auto"
            relay_udp_bound_port = 0
        }
        qcl082_relay_right = [pscustomobject]@{
            label = "right"
            status = "pass"
            transport_protocol = "udp"
            bytes_copied = 8192
            frames_sent = 8
            copy_last_byte_unix_ms = 1999500L
            relay_udp_wifi_direct_network_binding_allowed = $true
            relay_udp_wifi_direct_network_binding_required = $true
            relay_udp_wifi_direct_network_found = $true
            relay_udp_bound_to_wifi_direct_network = $true
            relay_udp_bound_local_address = "auto"
            relay_udp_bound_port = 0
        }
    }
    $udpReceiveProxyLocalBindDiagnostics = [pscustomobject]@{
        qcl082_receive_proxy_left = [pscustomobject]@{
            label = "left"
            status = "pass"
            transport_protocol = "udp"
            bytes_copied = 4096
            frames_received = 4
            copy_last_byte_unix_ms = 1999000L
            receive_proxy_udp_wifi_direct_network_binding_allowed = $true
            receive_proxy_udp_wifi_direct_network_binding_required = $false
            receive_proxy_udp_wifi_direct_network_found = $false
            receive_proxy_udp_bound_to_wifi_direct_network = $false
            receive_proxy_udp_bound_local_address = "192.168.49.46"
            receive_proxy_udp_bound_port = 9079
        }
        qcl082_receive_proxy_right = [pscustomobject]@{
            label = "right"
            status = "pass"
            transport_protocol = "udp"
            bytes_copied = 8192
            frames_received = 8
            copy_last_byte_unix_ms = 1999500L
            receive_proxy_udp_wifi_direct_network_binding_allowed = $true
            receive_proxy_udp_wifi_direct_network_binding_required = $false
            receive_proxy_udp_wifi_direct_network_found = $false
            receive_proxy_udp_bound_to_wifi_direct_network = $false
            receive_proxy_udp_bound_local_address = "192.168.49.46"
            receive_proxy_udp_bound_port = 9080
        }
    }
    $receiverBrokerStatus = [pscustomobject]@{
        lanes = @(
            [pscustomobject]@{
                role = "receiver"
                eye = "left"
                state = "transport_peer_connected_streaming_to_local_client"
                bytes_received = 4096
                copy_bytes_read = 4096
                copy_bytes_written = 4096
                copy_read_operations = 4
                copy_write_operations = 4
                copy_last_read_age_ms = 900
                copy_last_write_age_ms = 800
                transport_accept_count = 1
                local_client_accept_count = 1
                transport_host = "192.168.49.102"
                transport_port = 9079
                transport_server_socket_open = $true
                transport_server_bound_address = "192.168.49.102"
                transport_server_bound_port = 9079
                transport_server_bound_interface = "p2p0"
                receiver_ready = $true
                receiver_transport_ready = $true
            },
            [pscustomobject]@{
                role = "receiver"
                eye = "right"
                state = "transport_peer_connected_streaming_to_local_client"
                bytes_received = 8192
                copy_bytes_read = 8192
                copy_bytes_written = 8192
                copy_read_operations = 8
                copy_write_operations = 8
                copy_last_read_age_ms = 700
                copy_last_write_age_ms = 650
                transport_accept_count = 1
                local_client_accept_count = 1
                transport_host = "192.168.49.102"
                transport_port = 9080
                transport_server_socket_open = $true
                transport_server_bound_address = "192.168.49.102"
                transport_server_bound_port = 9080
                transport_server_bound_interface = "p2p0"
                receiver_ready = $true
                receiver_transport_ready = $true
            }
        )
    }
    $staleReceiverBrokerStatus = [pscustomobject]@{
        lanes = @(
            [pscustomobject]@{
                role = "receiver"
                eye = "left"
                state = "transport_peer_connected_streaming_to_local_client"
                bytes_received = 4096
                copy_bytes_read = 4096
                copy_bytes_written = 4096
                copy_read_operations = 4
                copy_write_operations = 4
                copy_last_read_age_ms = 9000
                copy_last_write_age_ms = 800
                transport_accept_count = 1
                local_client_accept_count = 1
            },
            [pscustomobject]@{
                role = "receiver"
                eye = "right"
                state = "transport_peer_connected_streaming_to_local_client"
                bytes_received = 8192
                copy_bytes_read = 8192
                copy_bytes_written = 8192
                copy_read_operations = 8
                copy_write_operations = 8
                copy_last_read_age_ms = 700
                copy_last_write_age_ms = 650
                transport_accept_count = 1
                local_client_accept_count = 1
            }
        )
    }
    $directSenderBrokerStatus = [pscustomobject]@{
        lanes = @(
            [pscustomobject]@{
                role = "sender"
                eye = "left"
                state = "transport_peer_connected_streaming_from_local_source"
                bytes_sent = 4096
                peer_route = [pscustomobject]@{
                    route_kind = "rusty_direct_p2p_socket_authority"
                    connect_host = "192.168.49.102"
                    connect_port = 9079
                    local_bind_host = "192.168.49.1"
                }
                peer_socket_authority = "rusty_direct_p2p_socket_authority"
                peer_socket_bound_local_address = "192.168.49.1"
                peer_socket_local_interface = "p2p0"
                peer_socket_network_selection = "rusty_direct_p2p_explicit_local_bind_address"
                peer_socket_bound_to_wifi_direct_network = $false
                peer_socket_network_route_matches_peer = $false
                peer_socket_network_direct_p2p_ready = $false
                peer_socket_local_interface_is_p2p = $true
                peer_socket_local_address_direct_p2p_ready = $true
                peer_socket_direct_p2p_ready = $true
                peer_socket_wifi_direct_bind_required = $true
                peer_socket_wifi_direct_bind_attempts = 1
                peer_socket_local_address_same_subnet = $true
            },
            [pscustomobject]@{
                role = "sender"
                eye = "right"
                state = "transport_peer_connected_streaming_from_local_source"
                bytes_sent = 8192
                peer_route = [pscustomobject]@{
                    route_kind = "rusty_direct_p2p_socket_authority"
                    connect_host = "192.168.49.102"
                    connect_port = 9080
                    local_bind_host = "192.168.49.1"
                }
                peer_socket_authority = "rusty_direct_p2p_socket_authority"
                peer_socket_bound_local_address = "192.168.49.1"
                peer_socket_local_interface = "p2p0"
                peer_socket_network_selection = "rusty_direct_p2p_explicit_local_bind_address"
                peer_socket_bound_to_wifi_direct_network = $false
                peer_socket_network_route_matches_peer = $false
                peer_socket_network_direct_p2p_ready = $false
                peer_socket_local_interface_is_p2p = $true
                peer_socket_local_address_direct_p2p_ready = $true
                peer_socket_direct_p2p_ready = $true
                peer_socket_wifi_direct_bind_required = $true
                peer_socket_wifi_direct_bind_attempts = 1
                peer_socket_local_address_same_subnet = $true
            }
        )
    }
    $wrongInterfaceSenderBrokerStatus = $directSenderBrokerStatus |
        ConvertTo-Json -Depth 16 |
        ConvertFrom-Json
    $wrongInterfaceSenderBrokerStatus.lanes[0].peer_socket_local_interface = "wlan0"
    $wrongInterfaceSenderBrokerStatus.lanes[0].peer_socket_local_interface_is_p2p = $false
    $wrongInterfaceSenderBrokerStatus.lanes[0].peer_socket_local_address_direct_p2p_ready = $false
    $wrongInterfaceSenderBrokerStatus.lanes[0].peer_socket_direct_p2p_ready = $false
    $summarizedDirectSenderBrokerStatus = Summarize-BrokerRuntime ([pscustomobject]@{
        schema = "rusty.quest.remote_camera.android_runtime_status.v1"
        session_id = "runtime-summary-self-test"
        active_count = 2
        created_count = 2
        failed_count = 0
        matched_count = 2
        lanes = $directSenderBrokerStatus.lanes
        sender_source_runtime = $null
    })
    $wrongAuthoritySenderBrokerStatus = [pscustomobject]@{
        lanes = @(
            [pscustomobject]@{
                role = "sender"
                eye = "left"
                state = "transport_peer_connected_streaming_from_local_source"
                bytes_sent = 4096
                peer_socket_authority = "android_connectivitymanager_network_or_rusty_direct_p2p_fallback"
                peer_socket_bound_local_address = ""
                peer_socket_wifi_direct_bind_required = $true
                peer_socket_wifi_direct_bind_attempts = 1
                peer_socket_local_address_same_subnet = $false
            },
            [pscustomobject]@{
                role = "sender"
                eye = "right"
                state = "transport_peer_connected_streaming_from_local_source"
                bytes_sent = 8192
                peer_socket_authority = "rusty_direct_p2p_socket_authority"
                peer_socket_bound_local_address = "192.168.49.1"
                peer_socket_local_interface = "p2p0"
                peer_socket_local_interface_is_p2p = $true
                peer_socket_local_address_direct_p2p_ready = $true
                peer_socket_direct_p2p_ready = $true
                peer_socket_wifi_direct_bind_required = $true
                peer_socket_wifi_direct_bind_attempts = 1
                peer_socket_local_address_same_subnet = $true
            }
        )
    }
    $relayFreshness = Get-Qcl082RelayFreshness -Diagnostics $relayDiagnostics -ReferenceUnixMs $referenceUnixMs
    $receiveProxyFreshness = Get-Qcl082ReceiveProxyFreshness -Diagnostics $receiveProxyDiagnostics -ReferenceUnixMs $referenceUnixMs
    $staleRelayFreshness = Get-Qcl082RelayFreshness -Diagnostics $staleRelayDiagnostics -ReferenceUnixMs $referenceUnixMs
    $udpRelayFreshness = Get-Qcl082RelayFreshness -Diagnostics $udpRelayDiagnostics -ReferenceUnixMs $referenceUnixMs
    $udpReceiveProxyAppBoundDiagnostics = [pscustomobject]@{
        qcl082_receive_proxy_left = [pscustomobject]@{
            label = "left"
            status = "pass"
            transport_protocol = "udp"
            bytes_copied = 4096
            frames_received = 4
            copy_last_byte_unix_ms = 1999000L
            receive_proxy_udp_wifi_direct_network_binding_allowed = $true
            receive_proxy_udp_wifi_direct_network_binding_required = $true
            receive_proxy_udp_wifi_direct_network_found = $true
            receive_proxy_udp_bound_to_wifi_direct_network = $true
            receive_proxy_udp_bound_local_address = "192.168.49.1"
            receive_proxy_udp_bound_port = 9079
        }
        qcl082_receive_proxy_right = [pscustomobject]@{
            label = "right"
            status = "pass"
            transport_protocol = "udp"
            bytes_copied = 8192
            frames_received = 8
            copy_last_byte_unix_ms = 1999500L
            receive_proxy_udp_wifi_direct_network_binding_allowed = $true
            receive_proxy_udp_wifi_direct_network_binding_required = $true
            receive_proxy_udp_wifi_direct_network_found = $true
            receive_proxy_udp_bound_to_wifi_direct_network = $true
            receive_proxy_udp_bound_local_address = "192.168.49.1"
            receive_proxy_udp_bound_port = 9080
        }
    }
    $udpReceiveProxyAppBoundFreshness = Get-Qcl082ReceiveProxyFreshness `
        -Diagnostics $udpReceiveProxyAppBoundDiagnostics `
        -ReferenceUnixMs $referenceUnixMs
    $udpReceiveProxyLocalBindFreshness = Get-Qcl082ReceiveProxyFreshness `
        -Diagnostics $udpReceiveProxyLocalBindDiagnostics `
        -ReferenceUnixMs $referenceUnixMs
    $receiverObservedFreshness = Get-BrokerReceiverObservedFreshness -BrokerStatus $receiverBrokerStatus
    $staleReceiverObservedFreshness = Get-BrokerReceiverObservedFreshness -BrokerStatus $staleReceiverBrokerStatus
    $directSenderAuthority = Get-Qcl100DirectP2pSenderAuthority -BrokerStatus $directSenderBrokerStatus -Required:$true
    $summarizedDirectSenderAuthority = Get-Qcl100DirectP2pSenderAuthority -BrokerStatus $summarizedDirectSenderBrokerStatus -Required:$true
    $wrongInterfaceSenderAuthority = Get-Qcl100DirectP2pSenderAuthority -BrokerStatus $wrongInterfaceSenderBrokerStatus -Required:$true
    $wrongAuthoritySenderAuthority = Get-Qcl100DirectP2pSenderAuthority -BrokerStatus $wrongAuthoritySenderBrokerStatus -Required:$true
    $notRequiredSenderAuthority = Get-Qcl100DirectP2pSenderAuthority -BrokerStatus $wrongAuthoritySenderBrokerStatus -Required:$false
    $directReceiverAuthority = Get-Qcl100DirectP2pReceiverAuthority `
        -BrokerStatus $receiverBrokerStatus `
        -ExpectedLocalAddress "192.168.49.102" `
        -Required:$true
    $wrongAddressReceiverAuthority = Get-Qcl100DirectP2pReceiverAuthority `
        -BrokerStatus $receiverBrokerStatus `
        -ExpectedLocalAddress "192.168.49.1" `
        -Required:$true
    $directP2pAddressRefresh = [pscustomobject]@{ ready = $true }
    $directP2pDuplexTopology = Get-Qcl100DirectP2pMediaTopologyAcceptance `
        -Required:$true `
        -LowerGateAuthority "rusty_direct_p2p_socket_authority" `
        -MediaAuthority "rusty_direct_p2p_socket_authority" `
        -AddressRefresh $directP2pAddressRefresh `
        -OwnerSenderAuthority $directSenderAuthority `
        -ClientSenderAuthority $directSenderAuthority `
        -OwnerReceiverAuthority $directReceiverAuthority `
        -ClientReceiverAuthority $directReceiverAuthority `
        -OwnerReceiverFreshness $receiverObservedFreshness `
        -ClientReceiverFreshness $receiverObservedFreshness `
        -OwnerSends:$true `
        -ClientSends:$true `
        -OwnerReceives:$true `
        -ClientReceives:$true
    $directP2pWrongReceiverTopology = Get-Qcl100DirectP2pMediaTopologyAcceptance `
        -Required:$true `
        -LowerGateAuthority "rusty_direct_p2p_socket_authority" `
        -MediaAuthority "rusty_direct_p2p_socket_authority" `
        -AddressRefresh $directP2pAddressRefresh `
        -OwnerSenderAuthority $directSenderAuthority `
        -ClientSenderAuthority $directSenderAuthority `
        -OwnerReceiverAuthority $wrongAddressReceiverAuthority `
        -ClientReceiverAuthority $wrongAddressReceiverAuthority `
        -OwnerReceiverFreshness $receiverObservedFreshness `
        -ClientReceiverFreshness $receiverObservedFreshness `
        -OwnerSends:$true `
        -ClientSends:$true `
        -OwnerReceives:$true `
        -ClientReceives:$true
    $controlTcpTopology = Get-Qcl100Qcl082MediaTopologyAcceptance `
        -OwnerRelayFreshness $relayFreshness `
        -ClientRelayFreshness $relayFreshness `
        -OwnerReceiveProxyFreshness $receiveProxyFreshness `
        -ClientReceiveProxyFreshness $receiveProxyFreshness `
        -OwnerRelayRequired:$true `
        -ClientRelayRequired:$true `
        -OwnerReceiveProxyRequired:$true `
        -ClientReceiveProxyRequired:$true
    $appBoundUdpTopology = Get-Qcl100Qcl082MediaTopologyAcceptance `
        -OwnerRelayFreshness $udpRelayFreshness `
        -ClientRelayFreshness $udpRelayFreshness `
        -OwnerReceiveProxyFreshness $udpReceiveProxyAppBoundFreshness `
        -ClientReceiveProxyFreshness $udpReceiveProxyAppBoundFreshness `
        -OwnerRelayRequired:$true `
        -ClientRelayRequired:$true `
        -OwnerReceiveProxyRequired:$true `
        -ClientReceiveProxyRequired:$true
    $localP2pUdpTopology = Get-Qcl100Qcl082MediaTopologyAcceptance `
        -OwnerRelayFreshness $udpReceiveProxyLocalBindFreshness `
        -ClientRelayFreshness $udpReceiveProxyLocalBindFreshness `
        -OwnerReceiveProxyFreshness $udpReceiveProxyLocalBindFreshness `
        -ClientReceiveProxyFreshness $udpReceiveProxyLocalBindFreshness `
        -OwnerRelayRequired:$true `
        -ClientRelayRequired:$true `
        -OwnerReceiveProxyRequired:$true `
        -ClientReceiveProxyRequired:$true
    $mixedUdpTopology = Get-Qcl100Qcl082MediaTopologyAcceptance `
        -OwnerRelayFreshness $udpRelayFreshness `
        -ClientRelayFreshness $udpRelayFreshness `
        -OwnerReceiveProxyFreshness $udpReceiveProxyLocalBindFreshness `
        -ClientReceiveProxyFreshness $udpReceiveProxyLocalBindFreshness `
        -OwnerRelayRequired:$true `
        -ClientRelayRequired:$true `
        -OwnerReceiveProxyRequired:$true `
        -ClientReceiveProxyRequired:$true
    $unacceptedTopologyWithAppBoundCounts = [pscustomobject]@{
        accepted = $false
        required_transport_topology = "app_bound_udp_media_lanes_or_justified_control_tcp_media_carrier"
        required_path_count = 4
        accepted_path_count = 0
        rejected_path_count = 4
        accepted_modes = @("app_bound_udp_media_lanes")
        control_tcp_media_carrier_path_count = 0
        app_bound_udp_media_path_count = 4
        first_issue = "damaged_topology_not_accepted"
    }
    $passedAcceptance = [pscustomobject]@{ passed = $true }
    $blockedAcceptance = [pscustomobject]@{ passed = $false }
    $results = @(
        Assert-Qcl100Qcl082CarrierFreshness `
            -Name "relay-control-tcp-carrier-fresh" `
            -Freshness $relayFreshness `
            -ExpectedFresh $true `
            -ExpectedCarrierLaneCount 2 `
            -ExpectedProtocol "control-tcp" `
            -FrameField "frames_sent" `
            -ExpectedLeftFrames 4 `
            -ExpectedRightFrames 8
        Assert-Qcl100Qcl082CarrierFreshness `
            -Name "receive-proxy-control-tcp-carrier-fresh" `
            -Freshness $receiveProxyFreshness `
            -ExpectedFresh $true `
            -ExpectedCarrierLaneCount 2 `
            -ExpectedProtocol "control-tcp" `
            -FrameField "frames_received" `
            -ExpectedLeftFrames 4 `
            -ExpectedRightFrames 8
        Assert-Qcl100Qcl082CarrierFreshness `
            -Name "relay-control-tcp-carrier-stale-left-byte" `
            -Freshness $staleRelayFreshness `
            -ExpectedFresh $false `
            -ExpectedCarrierLaneCount 2 `
            -ExpectedProtocol "control-tcp" `
            -FrameField "frames_sent" `
            -ExpectedLeftFrames 4 `
            -ExpectedRightFrames 8
        Assert-Qcl100Qcl082UdpBindingFreshness `
            -Name "relay-udp-app-bound-socket-fresh" `
            -Freshness $udpRelayFreshness `
            -ExpectedFresh $true `
            -ExpectedUdpLaneCount 2 `
            -ExpectedAppBoundSocketLaneCount 2 `
            -ExpectedAppBoundLaneCount 2 `
            -ExpectedLocalP2pLaneCount 0
        Assert-Qcl100Qcl082UdpBindingFreshness `
            -Name "receive-proxy-udp-app-bound-socket-fresh" `
            -Freshness $udpReceiveProxyAppBoundFreshness `
            -ExpectedFresh $true `
            -ExpectedUdpLaneCount 2 `
            -ExpectedAppBoundSocketLaneCount 2 `
            -ExpectedAppBoundLaneCount 2 `
            -ExpectedLocalP2pLaneCount 0
        Assert-Qcl100Qcl082UdpBindingFreshness `
            -Name "receive-proxy-udp-local-p2p-fallback-fresh" `
            -Freshness $udpReceiveProxyLocalBindFreshness `
            -ExpectedFresh $true `
            -ExpectedUdpLaneCount 2 `
            -ExpectedAppBoundSocketLaneCount 0 `
            -ExpectedAppBoundLaneCount 0 `
            -ExpectedLocalP2pLaneCount 2
        Assert-Qcl100Qcl082MediaTopologyAcceptance `
            -Name "topology-control-tcp-carrier-accepted" `
            -Topology $controlTcpTopology `
            -ExpectedAccepted $true `
            -ExpectedRequiredPathCount 4 `
            -ExpectedAcceptedPathCount 4 `
            -ExpectedRejectedPathCount 0 `
            -ExpectedControlTcpPathCount 4 `
            -ExpectedAppBoundUdpPathCount 0 `
            -ExpectedLocalP2pRejectedPathCount 0 `
            -ExpectedAcceptedModes @("justified_control_tcp_media_carrier") `
            -ExpectedFirstIssue ""
        Assert-Qcl100Qcl082MediaTopologyAcceptance `
            -Name "topology-app-bound-udp-accepted" `
            -Topology $appBoundUdpTopology `
            -ExpectedAccepted $true `
            -ExpectedRequiredPathCount 4 `
            -ExpectedAcceptedPathCount 4 `
            -ExpectedRejectedPathCount 0 `
            -ExpectedControlTcpPathCount 0 `
            -ExpectedAppBoundUdpPathCount 4 `
            -ExpectedLocalP2pRejectedPathCount 0 `
            -ExpectedAcceptedModes @("app_bound_udp_media_lanes") `
            -ExpectedFirstIssue ""
        Assert-Qcl100Qcl082MediaTopologyAcceptance `
            -Name "topology-local-p2p-udp-rejected" `
            -Topology $localP2pUdpTopology `
            -ExpectedAccepted $false `
            -ExpectedRequiredPathCount 4 `
            -ExpectedAcceptedPathCount 0 `
            -ExpectedRejectedPathCount 4 `
            -ExpectedControlTcpPathCount 0 `
            -ExpectedAppBoundUdpPathCount 0 `
            -ExpectedLocalP2pRejectedPathCount 4 `
            -ExpectedFirstIssue "owner_relay:udp_media_lanes_not_all_app_bound"
        Assert-Qcl100Qcl082MediaTopologyAcceptance `
            -Name "topology-mixed-app-bound-relay-local-p2p-receive-proxy-rejected" `
            -Topology $mixedUdpTopology `
            -ExpectedAccepted $false `
            -ExpectedRequiredPathCount 4 `
            -ExpectedAcceptedPathCount 2 `
            -ExpectedRejectedPathCount 2 `
            -ExpectedControlTcpPathCount 0 `
            -ExpectedAppBoundUdpPathCount 2 `
            -ExpectedLocalP2pRejectedPathCount 2 `
            -ExpectedAcceptedModes @("app_bound_udp_media_lanes") `
            -ExpectedFirstIssue "owner_receive_proxy:udp_media_lanes_not_all_app_bound"
        Assert-Qcl100BrokerReceiverObservedFreshness `
            -Name "receiver-observed-broker-bytes-fresh" `
            -Freshness $receiverObservedFreshness `
            -ExpectedFresh $true `
            -ExpectedLaneCount 2 `
            -ExpectedFreshLaneCount 2 `
            -ExpectedByteCount 12288
        Assert-Qcl100BrokerReceiverObservedFreshness `
            -Name "receiver-observed-broker-bytes-stale-left-read" `
            -Freshness $staleReceiverObservedFreshness `
            -ExpectedFresh $false `
            -ExpectedLaneCount 2 `
            -ExpectedFreshLaneCount 1 `
            -ExpectedByteCount 12288
        Assert-Qcl100DirectP2pSenderAuthority `
            -Name "direct-p2p-sender-authority-accepted" `
            -Authority $directSenderAuthority `
            -ExpectedAccepted $true `
            -ExpectedLaneCount 2 `
            -ExpectedAcceptedLaneCount 2
        Assert-Qcl100DirectP2pSenderAuthority `
            -Name "direct-p2p-sender-authority-survives-runtime-summary-projection" `
            -Authority $summarizedDirectSenderAuthority `
            -ExpectedAccepted $true `
            -ExpectedLaneCount 2 `
            -ExpectedAcceptedLaneCount 2
        Assert-Qcl100DirectP2pSenderAuthority `
            -Name "direct-p2p-sender-authority-rejects-wlan-interface" `
            -Authority $wrongInterfaceSenderAuthority `
            -ExpectedAccepted $false `
            -ExpectedLaneCount 2 `
            -ExpectedAcceptedLaneCount 1
        Assert-Qcl100DirectP2pSenderAuthority `
            -Name "direct-p2p-sender-authority-rejects-wrong-authority" `
            -Authority $wrongAuthoritySenderAuthority `
            -ExpectedAccepted $false `
            -ExpectedLaneCount 2 `
            -ExpectedAcceptedLaneCount 1
        Assert-Qcl100DirectP2pSenderAuthority `
            -Name "direct-p2p-sender-authority-neutral-when-not-required" `
            -Authority $notRequiredSenderAuthority `
            -ExpectedAccepted $true `
            -ExpectedLaneCount 2 `
            -ExpectedAcceptedLaneCount 1
        Assert-Qcl100DirectP2pReceiverAuthority `
            -Name "direct-p2p-receiver-authority-accepted" `
            -Authority $directReceiverAuthority `
            -ExpectedAccepted $true `
            -ExpectedLaneCount 2 `
            -ExpectedAcceptedLaneCount 2
        Assert-Qcl100DirectP2pReceiverAuthority `
            -Name "direct-p2p-receiver-authority-rejects-wrong-bind-address" `
            -Authority $wrongAddressReceiverAuthority `
            -ExpectedAccepted $false `
            -ExpectedLaneCount 2 `
            -ExpectedAcceptedLaneCount 0
        Assert-Qcl100DirectP2pMediaTopologyAcceptance `
            -Name "direct-p2p-duplex-topology-accepted" `
            -Topology $directP2pDuplexTopology `
            -ExpectedAccepted $true `
            -ExpectedRequiredPathCount 2 `
            -ExpectedAcceptedPathCount 2
        Assert-Qcl100DirectP2pMediaTopologyAcceptance `
            -Name "direct-p2p-duplex-topology-rejects-wrong-receiver-bind" `
            -Topology $directP2pWrongReceiverTopology `
            -ExpectedAccepted $false `
            -ExpectedRequiredPathCount 2 `
            -ExpectedAcceptedPathCount 0 `
            -ExpectedFirstIssue "owner_to_client:receiver_authority_not_accepted"
        Assert-Qcl100TransportClaims `
            -Name "transport-claims-rusty-direct-p2p-duplex" `
            -Claims (New-Qcl100TransportClaims -Direction "duplex" -FreshnessAcceptance $passedAcceptance -MediaTopologyAcceptance $unacceptedTopologyWithAppBoundCounts -DirectP2pMediaTopologyAcceptance $directP2pDuplexTopology -MediaTopologyRequired:$false) `
            -ExpectedSameGroupDuplexClaimed $true `
            -ExpectedRustyDirectP2pDuplexClaimed $true `
            -ExpectedAppBoundUdpDuplexClaimed $false `
            -ExpectedJustifiedAlternateClaimed $false `
            -ExpectedStatus "same_group_duplex_proven_with_rusty_direct_p2p_socket_authority"
        Assert-Qcl100TransportClaims `
            -Name "transport-claims-rusty-direct-p2p-left-only-diagnostic-does-not-claim-full-duplex" `
            -Claims (New-Qcl100TransportClaims -Direction "duplex" -LaneMode "left-only" -FreshnessAcceptance $passedAcceptance -MediaTopologyAcceptance $unacceptedTopologyWithAppBoundCounts -DirectP2pMediaTopologyAcceptance $directP2pDuplexTopology -MediaTopologyRequired:$false) `
            -ExpectedSameGroupDuplexClaimed $false `
            -ExpectedAppBoundUdpDuplexClaimed $false `
            -ExpectedJustifiedAlternateClaimed $false `
            -ExpectedStatus "same_group_duplex_diagnostic_lane_only_not_full_stereo"
        Assert-Qcl100TransportClaims `
            -Name "transport-claims-rusty-direct-p2p-wrong-receiver-bind-does-not-claim-duplex" `
            -Claims (New-Qcl100TransportClaims -Direction "duplex" -FreshnessAcceptance $passedAcceptance -MediaTopologyAcceptance $unacceptedTopologyWithAppBoundCounts -DirectP2pMediaTopologyAcceptance $directP2pWrongReceiverTopology -MediaTopologyRequired:$false) `
            -ExpectedSameGroupDuplexClaimed $false `
            -ExpectedAppBoundUdpDuplexClaimed $false `
            -ExpectedJustifiedAlternateClaimed $false `
            -ExpectedStatus "same_group_direct_p2p_topology_not_accepted"
        Assert-Qcl100TransportClaims `
            -Name "transport-claims-control-tcp-duplex-justified-alternate" `
            -Claims (New-Qcl100TransportClaims -Direction "duplex" -FreshnessAcceptance $passedAcceptance -MediaTopologyAcceptance $controlTcpTopology) `
            -ExpectedSameGroupDuplexClaimed $true `
            -ExpectedAppBoundUdpDuplexClaimed $false `
            -ExpectedJustifiedAlternateClaimed $true `
            -ExpectedStatus "same_group_duplex_proven_with_justified_control_tcp_alternate"
        Assert-Qcl100TransportClaims `
            -Name "transport-claims-app-bound-udp-duplex" `
            -Claims (New-Qcl100TransportClaims -Direction "duplex" -FreshnessAcceptance $passedAcceptance -MediaTopologyAcceptance $appBoundUdpTopology) `
            -ExpectedSameGroupDuplexClaimed $true `
            -ExpectedAppBoundUdpDuplexClaimed $true `
            -ExpectedJustifiedAlternateClaimed $false `
            -ExpectedStatus "same_group_app_bound_udp_duplex_proven"
        Assert-Qcl100TransportClaims `
            -Name "transport-claims-unaccepted-topology-does-not-claim-duplex" `
            -Claims (New-Qcl100TransportClaims -Direction "duplex" -FreshnessAcceptance $passedAcceptance -MediaTopologyAcceptance $unacceptedTopologyWithAppBoundCounts) `
            -ExpectedSameGroupDuplexClaimed $false `
            -ExpectedAppBoundUdpDuplexClaimed $false `
            -ExpectedJustifiedAlternateClaimed $false `
            -ExpectedStatus "same_group_duplex_topology_not_accepted"
        Assert-Qcl100TransportClaims `
            -Name "transport-claims-one-way-does-not-claim-duplex" `
            -Claims (New-Qcl100TransportClaims -Direction "owner-to-client" -FreshnessAcceptance $passedAcceptance -MediaTopologyAcceptance $appBoundUdpTopology) `
            -ExpectedSameGroupDuplexClaimed $false `
            -ExpectedAppBoundUdpDuplexClaimed $false `
            -ExpectedJustifiedAlternateClaimed $false `
            -ExpectedStatus "active_direction_stream_render_proven"
        Assert-Qcl100TransportClaims `
            -Name "transport-claims-broker-direct-no-qcl041-topology-no-duplex-claim" `
            -Claims (New-Qcl100TransportClaims -Direction "duplex" -FreshnessAcceptance $passedAcceptance -MediaTopologyAcceptance $unacceptedTopologyWithAppBoundCounts -MediaTopologyRequired:$false) `
            -ExpectedSameGroupDuplexClaimed $false `
            -ExpectedAppBoundUdpDuplexClaimed $false `
            -ExpectedJustifiedAlternateClaimed $false `
            -ExpectedStatus "same_group_duplex_topology_not_required_for_broker_direct_media_no_duplex_claim"
        Assert-Qcl100TransportClaims `
            -Name "transport-claims-blocked-does-not-claim-duplex" `
            -Claims (New-Qcl100TransportClaims -Direction "duplex" -FreshnessAcceptance $blockedAcceptance -MediaTopologyAcceptance $controlTcpTopology) `
            -ExpectedSameGroupDuplexClaimed $false `
            -ExpectedAppBoundUdpDuplexClaimed $false `
            -ExpectedJustifiedAlternateClaimed $false `
            -ExpectedStatus "blocked_until_same_epoch_bidirectional_media_and_renderer_freshness_pass"
    )
    Write-JsonFile -Value ([ordered]@{
        schema = "rusty.quest.qcl100_runtime_summary_self_test.v1"
        required = "qcl082_freshness_preserves_control_tcp_carrier_counts_reports_udp_binding_requires_app_bound_udp_or_control_tcp_topology_and_rusty_direct_p2p_duplex_requires_runtime_p2p0_receiver_bind_sender_authority_receiver_bytes_and_same_epoch_native_freshness"
        cases = $results
    }) -Path (Join-Path $OutDir "qcl100-runtime-summary-self-test.json")
    Write-Output "QCL100 runtime summary self-test passed."
}

function Resolve-Qcl100CameraSourceFreshness {
    param(
        $CurrentFreshness,
        $RelayFreshness,
        $BrokerStatus,
        [string]$SenderLabel
    )
    if ($null -ne $CurrentFreshness -and [bool]$CurrentFreshness.fresh) {
        return $CurrentFreshness
    }
    if ($null -ne $BrokerStatus -or $null -eq $RelayFreshness -or -not [bool]$RelayFreshness.fresh) {
        return $CurrentFreshness
    }
    [ordered]@{
        required = "active local Camera2 source freshness inferred from fresh QCL-082 relay bytes because final broker status was unavailable"
        fallback_source = "qcl082_relay_freshness_after_final_status_unavailable"
        sender_label = $SenderLabel
        minimum_frame_lines = $MinFreshFrameLines
        max_final_age_ms = 5000
        lane_mode = $LaneMode
        expected_source_count = $activeLaneCount
        source_count = $RelayFreshness.lane_count
        fresh_source_count = $RelayFreshness.fresh_lane_count
        sources = @($RelayFreshness.lanes | ForEach-Object {
            [ordered]@{
                eye = $_.eye
                state = $_.status
                connected = $true
                bytes_copied = $_.bytes_copied
                last_byte_age_ms = $_.copy_last_byte_age_ms
                packet_fresh = [bool]$_.fresh_relay_bytes
                camera2_capture_fresh = $null
                fresh_camera_frames = [bool]$_.fresh_relay_bytes
                evidence = "qcl082_relay_bytes_fresh"
            }
        })
        broker_status_unavailable = $true
        fresh = [bool]($RelayFreshness.lane_count -eq $activeLaneCount -and $RelayFreshness.fresh_lane_count -eq $RelayFreshness.lane_count)
    }
}
