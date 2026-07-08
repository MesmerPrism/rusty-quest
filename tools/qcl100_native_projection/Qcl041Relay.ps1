# Dot-sourced helper functions for Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1.
# Keep these functions side-effect free until called by the runner facade.

function Read-Qcl041AppFile {
    param(
        [string]$Serial,
        [string]$DevicePath,
        [int]$TimeoutSeconds = 5
    )
    return Read-RustyQuestAdbAppFile `
        -AdbPath $Adb `
        -Serial $Serial `
        -Package $Qcl041Package `
        -DevicePath $DevicePath `
        -TimeoutSeconds $TimeoutSeconds
}

function Wait-Qcl041AppFile {
    param(
        [string]$Serial,
        [string]$DevicePath,
        [string]$OutPath,
        [int]$TimeoutSeconds = 15,
        [string]$Label = ""
    )
    $started = Get-Date
    $attempt = 0
    $lastError = ""
    $effectiveTimeoutSeconds = [Math]::Max(1, $TimeoutSeconds)
    while (((Get-Date) - $started).TotalSeconds -lt $effectiveTimeoutSeconds) {
        $attempt++
        $read = Read-Qcl041AppFile -Serial $Serial -DevicePath $DevicePath -TimeoutSeconds ([Math]::Min(5, $effectiveTimeoutSeconds))
        if ([bool]$read.timed_out) {
            $lastError = "qcl041_app_file_read_timeout after $($read.timeout_seconds)s"
        } elseif (-not [string]::IsNullOrWhiteSpace([string]$read.stdout)) {
            $saved = Save-RustyQuestJsonArtifactFromStdout `
                -Content ([string]$read.stdout) `
                -OutPath $OutPath `
                -Label $Label `
                -ExitCode $read.exit_code
            if ([bool]$saved.saved) {
                return [ordered]@{
                    status = if ([bool]$saved.accepted_with_nonzero_exit_code) { "pass_with_nonzero_exit_code" } else { "pass" }
                    label = $Label
                    attempts = $attempt
                    elapsed_ms = [int][Math]::Ceiling(((Get-Date) - $started).TotalMilliseconds)
                    artifact_path = $OutPath
                    artifact_present = $true
                    adb_exit_code = $read.exit_code
                    accepted_with_nonzero_exit_code = [bool]$saved.accepted_with_nonzero_exit_code
                    warning = [string]$saved.warning
                    last_error = ""
                }
            }
            $lastError = "qcl041_app_file_stdout_json_parse_failed exit_code=$($read.exit_code): $($saved.error)"
        } else {
            $lastError = if (-not [string]::IsNullOrWhiteSpace([string]$read.stderr)) { [string]$read.stderr } else { [string]$read.stdout }
        }
        Start-Sleep -Milliseconds 500
    }
    return [ordered]@{
        status = "timeout"
        label = $Label
        attempts = $attempt
        elapsed_ms = [int][Math]::Ceiling(((Get-Date) - $started).TotalMilliseconds)
        artifact_path = $OutPath
        artifact_present = $false
        last_error = $lastError
    }
}

function Start-Qcl041Relay {
    param(
        [string]$Serial,
        [string]$Role,
        [string]$ReceiverHost,
        [string]$LeaseId,
        [string]$LogName,
        [bool]$RelayEnabled = $true,
        [bool]$ReceiveProxyEnabled = $false,
        [string]$DeferredReceiverTargetFile = "",
        [int]$DeferredReceiverTargetWaitMs = 0,
        [bool]$RequireDeferredReceiverTarget = $false,
        [string]$TopologyEpoch = $RunId,
        [bool]$RequireUdpReceiveProxyNetworkBinding = $false
    )
    $effectiveLeaseId = if ([string]::IsNullOrWhiteSpace($LeaseId)) { "unleased" } else { $LeaseId }
    $leaseReserved = if ([string]::IsNullOrWhiteSpace($LeaseId)) { "false" } else { "true" }
    $laneSpecs = @()
    $proxyLaneSpecs = @()
    if ($leftLaneActive) {
        $laneSpecs += "left:127.0.0.1:${LeftSourcePort}:${ReceiverHost}:${LeftTransportPort}"
        $proxyLaneSpecs += "left:${LeftTransportPort}:127.0.0.1:${LeftTransportProxyTargetPort}"
    }
    if ($rightLaneActive) {
        $laneSpecs += "right:127.0.0.1:${RightSourcePort}:${ReceiverHost}:${RightTransportPort}"
        $proxyLaneSpecs += "right:${RightTransportPort}:127.0.0.1:${RightTransportProxyTargetPort}"
    }
    $laneSpec = $laneSpecs -join ";"
    $laneSpecForShell = $laneSpec.Replace(";", "\;")
    $proxyLaneSpec = $proxyLaneSpecs -join ";"
    $proxyLaneSpecForShell = $proxyLaneSpec.Replace(";", "\;")
    $primarySourcePort = if ($leftLaneActive) { $LeftSourcePort } else { $RightSourcePort }
    $primaryTransportPort = if ($leftLaneActive) { $LeftTransportPort } else { $RightTransportPort }
    $primaryProxyTargetPort = if ($leftLaneActive) { $LeftTransportProxyTargetPort } else { $RightTransportProxyTargetPort }
    $relayTransportProtocol = $Qcl082TransportProtocol
    $receiveProxyTransportProtocol = $Qcl082TransportProtocol
    if ($Qcl082TransportProtocol -eq "mixed") {
        $relayTransportProtocol = if ($Role -eq "group_owner") { "udp" } else { "reverse-tcp" }
        $receiveProxyTransportProtocol = if ($Role -eq "group_owner") { "reverse-tcp" } else { "udp" }
    } elseif ($Qcl082TransportProtocol -eq "mixed-client-tcp") {
        $relayTransportProtocol = if ($Role -eq "group_owner") { "udp" } else { "tcp" }
        $receiveProxyTransportProtocol = if ($Role -eq "group_owner") { "tcp" } else { "udp" }
    }
    $qcl041TimeoutSeconds = [Math]::Max(85, $ProjectionSeconds + 60)
    $effectiveHoldAfterSocketMs = [Math]::Max($HoldAfterSocketMs, ($ProjectionSeconds + 45) * 1000)
    $intentArgs = @(
        "-s", $Serial,
        "shell", "am", "start-foreground-service",
        "-n", "$Qcl041Package/.Qcl041WifiDirectHarnessService",
        "--es", "qcl041.run_id", $RunId,
        "--es", "qcl041.device_serial", $Serial,
        "--es", "qcl041.device_model", "Quest_3S",
        "--es", "qcl041.lease_id", $effectiveLeaseId,
        "--es", "qcl041.lease_resource", "quest:$Serial",
        "--ez", "qcl041.lease_reserved_before_live_steps", $leaseReserved,
        "--es", "qcl041.peer_class", "quest",
        "--es", "qcl041.q2q_role", $Role,
        "--ez", "qcl041.q2q_preclear_stale_group", "false",
        "--es", "qcl041.q2q_network_name", $Qcl041Q2qNetworkName,
        "--es", "qcl041.q2q_passphrase", $Qcl041Q2qPassphrase,
        "--es", "qcl041.peer_name_contains", "Quest",
        "--es", "qcl041.host_toolchain_profile", "qcl100_quest_to_quest_native_stereo_projection_wifi_direct",
        "--ei", "qcl041.timeout_seconds", $qcl041TimeoutSeconds.ToString(),
        "--ei", "qcl041.socket_timeout_seconds", "30",
        "--ei", "qcl041.hold_after_socket_ms", $effectiveHoldAfterSocketMs.ToString(),
        "--es", "qcl041.qcl082_topology_epoch", $TopologyEpoch,
        "--es", "qcl041.qcl082_relay_receiver_host", $ReceiverHost
    )
    if ($RelayEnabled) {
        $intentArgs += @(
            "--ez", "qcl041.qcl082_relay_enabled", "true",
            "--es", "qcl041.qcl082_relay_source_host", "127.0.0.1",
            "--ei", "qcl041.qcl082_relay_source_port", $primarySourcePort.ToString(),
            "--ei", "qcl041.qcl082_relay_receiver_port", $primaryTransportPort.ToString(),
            "--es", "qcl041.qcl082_relay_lanes", $laneSpecForShell,
            "--es", "qcl041.qcl082_transport_protocol", $relayTransportProtocol,
            "--es", "qcl041.qcl082_relay_transport_protocol", $relayTransportProtocol,
            "--ei", "qcl041.qcl082_relay_timeout_seconds", $RelayTimeoutSeconds.ToString(),
            "--ei", "qcl041.qcl082_relay_max_bytes", $RelayMaxBytes.ToString(),
            "--ei", "qcl041.qcl082_relay_write_stall_timeout_ms", $Qcl082RelayWriteStallTimeoutMs.ToString(),
            "--ei", "qcl041.qcl082_relay_receiver_progress_timeout_ms", $Qcl082RelayReceiverProgressTimeoutMs.ToString(),
            "--ei", "qcl041.qcl082_relay_port_rotation_count", $Qcl082RelayPortRotationCount.ToString(),
            "--ei", "qcl041.qcl082_relay_start_delay_ms", $Qcl082RelayStartDelayMs.ToString()
        )
        if (-not [string]::IsNullOrWhiteSpace($DeferredReceiverTargetFile)) {
            $intentArgs += @(
                "--es", "qcl041.qcl082_relay_receiver_target_file", $DeferredReceiverTargetFile,
                "--ei", "qcl041.qcl082_relay_receiver_target_wait_ms", $DeferredReceiverTargetWaitMs.ToString(),
                "--ez", "qcl041.qcl082_relay_require_deferred_receiver_target", $RequireDeferredReceiverTarget.ToString().ToLowerInvariant()
            )
        }
    } else {
        $intentArgs += @("--ez", "qcl041.qcl082_relay_enabled", "false")
    }
    if ($RelayEnabled -or $ReceiveProxyEnabled) {
        $intentArgs += @(
            "--ez", "qcl041.qcl082_ack_pacing_enabled", $effectiveQcl082AckPacingEnabled.ToString().ToLowerInvariant(),
            "--ei", "qcl041.qcl082_ack_chunk_bytes", $Qcl082AckChunkBytes.ToString(),
            "--ei", "qcl041.qcl082_ack_timeout_ms", $Qcl082AckTimeoutMs.ToString(),
            "--ei", "qcl041.qcl082_ack_soft_timeout_limit", $Qcl082AckSoftTimeoutLimit.ToString(),
            "--ei", "qcl041.qcl082_control_tcp_media_stream_bytes_per_direction", ([Math]::Max(0, $Qcl082ControlTcpMediaStreamBytesPerDirection)).ToString(),
            "--ei", "qcl041.qcl082_control_tcp_media_stream_chunk_bytes", ([Math]::Max(1, $Qcl082ControlTcpMediaStreamChunkBytes)).ToString()
        )
    } else {
        $intentArgs += @("--ez", "qcl041.qcl082_ack_pacing_enabled", "false")
    }
    if ($ReceiveProxyEnabled) {
        $intentArgs += @(
            "--ez", "qcl041.qcl082_receive_proxy_enabled", "true",
            "--ei", "qcl041.qcl082_receive_proxy_listen_port", $primaryTransportPort.ToString(),
            "--es", "qcl041.qcl082_receive_proxy_target_host", "127.0.0.1",
            "--ei", "qcl041.qcl082_receive_proxy_target_port", $primaryProxyTargetPort.ToString(),
            "--es", "qcl041.qcl082_receive_proxy_lanes", $proxyLaneSpecForShell,
            "--es", "qcl041.qcl082_transport_protocol", $receiveProxyTransportProtocol,
            "--es", "qcl041.qcl082_receive_proxy_transport_protocol", $receiveProxyTransportProtocol,
            "--ei", "qcl041.qcl082_receive_proxy_timeout_seconds", $RelayTimeoutSeconds.ToString(),
            "--ei", "qcl041.qcl082_receive_proxy_max_bytes", $RelayMaxBytes.ToString(),
            "--ei", "qcl041.qcl082_relay_receiver_progress_timeout_ms", $Qcl082RelayReceiverProgressTimeoutMs.ToString(),
            "--ei", "qcl041.qcl082_relay_port_rotation_count", $Qcl082RelayPortRotationCount.ToString(),
            "--ei", "qcl041.qcl082_receive_proxy_peer_idle_timeout_ms", $Qcl082ReceiveProxyPeerIdleTimeoutMs.ToString(),
            "--ez", "qcl041.qcl082_udp_receive_proxy_require_wifi_direct_network_binding", $RequireUdpReceiveProxyNetworkBinding.ToString().ToLowerInvariant()
        )
    } else {
        $intentArgs += @("--ez", "qcl041.qcl082_receive_proxy_enabled", "false")
    }
    Invoke-External `
        -Name "QCL041 stereo relay launch $Serial" `
        -File $Adb `
        -Arguments $intentArgs `
        -LogPath (Join-Path $MediaDir $LogName) | Out-Null
}

function Invoke-Qcl041PreclearOnly {
    param(
        [string]$Serial,
        [string]$LeaseId,
        [string]$Label
    )
    $effectiveLeaseId = if ([string]::IsNullOrWhiteSpace($LeaseId)) { "unleased" } else { $LeaseId }
    $leaseReserved = if ([string]::IsNullOrWhiteSpace($LeaseId)) { "false" } else { "true" }
    $preclearRunId = "$RunId-$Label-qcl041-preclear"
    $intentArgs = @(
        "-s", $Serial,
        "shell", "am", "start-foreground-service",
        "-n", "$Qcl041Package/.Qcl041WifiDirectHarnessService",
        "--es", "qcl041.run_id", $preclearRunId,
        "--es", "qcl041.device_serial", $Serial,
        "--es", "qcl041.device_model", "Quest_3S",
        "--es", "qcl041.lease_id", $effectiveLeaseId,
        "--es", "qcl041.lease_resource", "quest:$Serial",
        "--ez", "qcl041.lease_reserved_before_live_steps", $leaseReserved,
        "--es", "qcl041.peer_class", "quest",
        "--es", "qcl041.q2q_role", "group_owner",
        "--ez", "qcl041.q2q_preclear_only", "true",
        "--ez", "qcl041.q2q_preclear_stale_group", "true",
        "--es", "qcl041.q2q_network_name", $Qcl041Q2qNetworkName,
        "--es", "qcl041.q2q_passphrase", $Qcl041Q2qPassphrase,
        "--es", "qcl041.peer_name_contains", "Quest",
        "--es", "qcl041.host_toolchain_profile", "qcl100_quest_to_quest_native_stereo_projection_wifi_direct_preclear",
        "--ei", "qcl041.timeout_seconds", "12",
        "--ei", "qcl041.socket_timeout_seconds", "5",
        "--ei", "qcl041.hold_after_socket_ms", "0",
        "--ez", "qcl041.qcl082_relay_enabled", "false",
        "--ez", "qcl041.qcl082_receive_proxy_enabled", "false",
        "--ez", "qcl041.qcl082_ack_pacing_enabled", "false"
    )
    $launchPath = Join-Path $MediaDir "$Label-qcl041-preclear-launch.txt"
    Invoke-External `
        -Name "QCL041 preclear-only launch $Serial" `
        -File $Adb `
        -Arguments $intentArgs `
        -LogPath $launchPath | Out-Null
    $artifactPath = Join-Path $MediaDir "$Label-qcl041-preclear.json"
    $artifactRead = Wait-Qcl041AppFile `
        -Serial $Serial `
        -DevicePath "files/qcl041/$preclearRunId.json" `
        -OutPath $artifactPath `
        -TimeoutSeconds 15 `
        -Label "$Label-qcl041-preclear"
    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "am", "force-stop", $Qcl041Package)
    return [ordered]@{
        schema = "rusty.quest.qcl100_qcl041_preclear_receipt.v1"
        serial = $Serial
        label = $Label
        run_id = $preclearRunId
        qcl041_preclear_only = $true
        qcl041_preclear_stale_group = $true
        launch_log_path = $launchPath
        artifact_path = $artifactPath
        artifact_present = [bool](Test-Path -LiteralPath $artifactPath)
        artifact_read = $artifactRead
        final_force_stop_attempted = $true
    }
}

function Get-Qcl041ArtifactFreshnessWaitState {
    param(
        $Artifact,
        [bool]$RequireRelayFreshness = $false,
        [bool]$RequireReceiveProxyFreshness = $false,
        [string]$Label = ""
    )
    $referenceUnixMs = Get-Qcl041ArtifactReferenceUnixMs -Artifact $Artifact
    $relayFreshness = $null
    $receiveProxyFreshness = $null
    if ($RequireRelayFreshness) {
        $relayFreshness = Get-Qcl082RelayFreshness -Diagnostics $Artifact.diagnostics -ReferenceUnixMs $referenceUnixMs
    }
    if ($RequireReceiveProxyFreshness) {
        $receiveProxyFreshness = Get-Qcl082ReceiveProxyFreshness -Diagnostics $Artifact.diagnostics -ReferenceUnixMs $referenceUnixMs
    }

    $relayFresh = [bool]((-not $RequireRelayFreshness) -or ($null -ne $relayFreshness -and [bool]$relayFreshness.fresh))
    $receiveProxyFresh = [bool]((-not $RequireReceiveProxyFreshness) -or ($null -ne $receiveProxyFreshness -and [bool]$receiveProxyFreshness.fresh))
    $issues = @()
    if (-not $relayFresh) {
        $issues += "qcl082_relay_not_fresh"
    }
    if (-not $receiveProxyFresh) {
        $issues += "qcl082_receive_proxy_not_fresh"
    }

    [ordered]@{
        schema = "rusty.quest.qcl100_qcl041_artifact_freshness_wait_state.v1"
        label = $Label
        reference_unix_ms = $referenceUnixMs
        relay_required = [bool]$RequireRelayFreshness
        relay_fresh = $relayFresh
        relay_lane_count = if ($null -eq $relayFreshness) { 0 } else { ConvertTo-LongSafe $relayFreshness.lane_count }
        relay_fresh_lane_count = if ($null -eq $relayFreshness) { 0 } else { ConvertTo-LongSafe $relayFreshness.fresh_lane_count }
        relay_expected_lane_count = if ($null -eq $relayFreshness) { 0 } else { ConvertTo-LongSafe $relayFreshness.expected_lane_count }
        receive_proxy_required = [bool]$RequireReceiveProxyFreshness
        receive_proxy_fresh = $receiveProxyFresh
        receive_proxy_lane_count = if ($null -eq $receiveProxyFreshness) { 0 } else { ConvertTo-LongSafe $receiveProxyFreshness.lane_count }
        receive_proxy_fresh_lane_count = if ($null -eq $receiveProxyFreshness) { 0 } else { ConvertTo-LongSafe $receiveProxyFreshness.fresh_lane_count }
        receive_proxy_expected_lane_count = if ($null -eq $receiveProxyFreshness) { 0 } else { ConvertTo-LongSafe $receiveProxyFreshness.expected_lane_count }
        ready = [bool]($issues.Count -eq 0)
        issue_count = $issues.Count
        issues = $issues
    }
}

function Read-Qcl041Artifact {
    param(
        [string]$Serial,
        [string]$OutPath,
        [int]$TimeoutSeconds = $Qcl041ArtifactWaitSeconds,
        [bool]$RequireRelayFreshness = $false,
        [bool]$RequireReceiveProxyFreshness = $false,
        [string]$Label = ""
    )
    $started = Get-Date
    $attempt = 0
    $lastError = ""
    if ($TimeoutSeconds -le 0) {
        $TimeoutSeconds = 1
    }
    while (((Get-Date) - $started).TotalSeconds -lt $TimeoutSeconds) {
        $attempt++
        $readTimeoutSeconds = [Math]::Min(5, [Math]::Max(1, [int][Math]::Ceiling($TimeoutSeconds)))
        $read = Read-Qcl041AppFile -Serial $Serial -DevicePath "files/qcl041/$RunId.json" -TimeoutSeconds $readTimeoutSeconds
        if ([bool]$read.timed_out) {
            $lastError = "qcl041_artifact_adb_read_timeout after $($read.timeout_seconds)s"
        } elseif (-not [string]::IsNullOrWhiteSpace([string]$read.stdout)) {
            $saved = Save-RustyQuestJsonArtifactFromStdout `
                -Content ([string]$read.stdout) `
                -OutPath $OutPath `
                -Label $Label `
                -ExitCode $read.exit_code
            if ([bool]$saved.saved) {
                $artifact = $saved.artifact
                if (-not $RequireRelayFreshness -and -not $RequireReceiveProxyFreshness) {
                    return
                }
                $waitState = Get-Qcl041ArtifactFreshnessWaitState `
                    -Artifact $artifact `
                    -RequireRelayFreshness:$RequireRelayFreshness `
                    -RequireReceiveProxyFreshness:$RequireReceiveProxyFreshness `
                    -Label $Label
                if ([bool]$waitState.ready) {
                    return
                }
                $lastError = "qcl041_artifact_qcl082_freshness_wait: " + ($waitState | ConvertTo-Json -Compress -Depth 8)
            } else {
                $lastError = "artifact_mid_hold_or_final_wait: qcl041_artifact_stdout_json_parse_failed exit_code=$($read.exit_code): $($saved.error)"
            }
        } else {
            $lastError = if (-not [string]::IsNullOrWhiteSpace([string]$read.stderr)) { [string]$read.stderr } else { [string]$read.stdout }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Could not read QCL041 artifact from $Serial after $attempt attempts and ${TimeoutSeconds}s (qcl041_artifact_wait_timeout). $lastError"
}

function Invoke-Qcl100Qcl041ArtifactFreshnessWaitSelfTest {
    param([string]$OutputDirectory = $OutDir)
    $leftLaneActive = $true
    $rightLaneActive = $true
    $activeLaneCount = 2
    $LaneMode = "stereo"
    $referenceUnixMs = 2000000L
    $observedAtUtc = [DateTimeOffset]::FromUnixTimeMilliseconds($referenceUnixMs).UtcDateTime.ToString("o")
    $freshUnixMs = $referenceUnixMs - 1000L
    $staleUnixMs = $referenceUnixMs - 20000L

    $freshArtifact = [pscustomobject]@{
        observed_at_utc = $observedAtUtc
        diagnostics = [pscustomobject]@{
            qcl082_relay_left = [pscustomobject]@{ status = "streaming"; transport_protocol = "control-tcp"; bytes_copied = 4096; copy_last_byte_unix_ms = $freshUnixMs }
            qcl082_relay_right = [pscustomobject]@{ status = "streaming"; transport_protocol = "control-tcp"; bytes_copied = 4096; copy_last_byte_unix_ms = $freshUnixMs }
            qcl082_receive_proxy_left = [pscustomobject]@{ status = "streaming"; transport_protocol = "control-tcp"; bytes_copied = 4096; copy_last_byte_unix_ms = $freshUnixMs }
            qcl082_receive_proxy_right = [pscustomobject]@{ status = "streaming"; transport_protocol = "control-tcp"; bytes_copied = 4096; copy_last_byte_unix_ms = $freshUnixMs }
        }
    }
    $staleArtifact = [pscustomobject]@{
        observed_at_utc = $observedAtUtc
        diagnostics = [pscustomobject]@{
            qcl082_relay_left = [pscustomobject]@{ status = "streaming"; transport_protocol = "control-tcp"; bytes_copied = 4096; copy_last_byte_unix_ms = $freshUnixMs }
            qcl082_relay_right = [pscustomobject]@{ status = "streaming"; transport_protocol = "control-tcp"; bytes_copied = 4096; copy_last_byte_unix_ms = $staleUnixMs }
            qcl082_receive_proxy_left = [pscustomobject]@{ status = "streaming"; transport_protocol = "control-tcp"; bytes_copied = 4096; copy_last_byte_unix_ms = $freshUnixMs }
            qcl082_receive_proxy_right = [pscustomobject]@{ status = "streaming"; transport_protocol = "control-tcp"; bytes_copied = 4096; copy_last_byte_unix_ms = $freshUnixMs }
        }
    }

    $freshState = Get-Qcl041ArtifactFreshnessWaitState `
        -Artifact $freshArtifact `
        -RequireRelayFreshness:$true `
        -RequireReceiveProxyFreshness:$true `
        -Label "qcl041-artifact-freshness-selftest-fresh"
    if (-not [bool]$freshState.ready) {
        throw "QCL041 artifact freshness wait self-test expected fresh fixture to be ready."
    }
    $staleState = Get-Qcl041ArtifactFreshnessWaitState `
        -Artifact $staleArtifact `
        -RequireRelayFreshness:$true `
        -RequireReceiveProxyFreshness:$true `
        -Label "qcl041-artifact-freshness-selftest-stale-relay"
    if ([bool]$staleState.ready -or -not ($staleState.issues -contains "qcl082_relay_not_fresh")) {
        throw "QCL041 artifact freshness wait self-test expected stale relay fixture to wait."
    }

    if (-not [string]::IsNullOrWhiteSpace($OutputDirectory)) {
        New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
        Write-JsonFile -Value ([ordered]@{
            schema = "rusty.quest.qcl100_qcl041_artifact_freshness_wait_self_test.v1"
            required = "qcl041_artifact_qcl082_freshness_wait_requires_final_required_relay_and_receive_proxy_freshness"
            cases = @(
                [ordered]@{ name = "fresh-relay-and-receive-proxy-ready"; passed = [bool]$freshState.ready; state = $freshState },
                [ordered]@{ name = "stale-relay-keeps-waiting"; passed = [bool](-not $staleState.ready); state = $staleState }
            )
        }) -Path (Join-Path $OutputDirectory "qcl100-qcl041-artifact-freshness-wait-self-test.json")
    }
    Write-Output "QCL100 QCL041 artifact freshness wait self-test passed."
}

function Wait-Qcl041AdvertisedReceiveTarget {
    param(
        [string]$Serial,
        [string]$Label,
        [int]$TimeoutSeconds = 30
    )
    $started = Get-Date
    $attempt = 0
    while (((Get-Date) - $started).TotalSeconds -lt $TimeoutSeconds) {
        $attempt++
        $read = Read-Qcl041AppFile -Serial $Serial -DevicePath "files/qcl041/$RunId.json" -TimeoutSeconds 5
        if ($read.exit_code -eq 0 -and -not [string]::IsNullOrWhiteSpace([string]$read.stdout)) {
            try {
                $artifact = [string]$read.stdout | ConvertFrom-Json
                $diag = $artifact.diagnostics
                $receive = $diag.qcl082_receive_proxy
                $host = [string]$receive.advertised_receive_address
                if ([string]::IsNullOrWhiteSpace($host) -or $host -eq "0.0.0.0") {
                    $host = [string]$receive.effective_socket_bind_address
                }
                if ([string]::IsNullOrWhiteSpace($host) -or $host -eq "0.0.0.0") {
                    $host = [string]$receive.cached_local_bind_address
                }
                if ([string]::IsNullOrWhiteSpace($host) -or $host -eq "0.0.0.0") {
                    $host = [string]$diag.lifecycle.wifi_direct_local_address
                }
                if (-not [string]::IsNullOrWhiteSpace($host) -and $host -ne "0.0.0.0") {
                    return [ordered]@{
                        found = $true
                        label = $Label
                        serial = $Serial
                        attempts = $attempt
                        advertised_receive_address = $host
                        effective_socket_bind_address = [string]$receive.effective_socket_bind_address
                        cached_local_bind_address = [string]$receive.cached_local_bind_address
                        lifecycle_wifi_direct_local_address = [string]$diag.lifecycle.wifi_direct_local_address
                        source = "qcl041_receiver_artifact"
                    }
                }
            } catch {
                # Keep polling while the artifact is still being written.
            }
        }
        $p2pHost = Get-Qcl041CurrentP2pAddressFromAdb -Serial $Serial
        if (-not [string]::IsNullOrWhiteSpace($p2pHost) -and $p2pHost -ne "0.0.0.0") {
            return [ordered]@{
                found = $true
                label = $Label
                serial = $Serial
                attempts = $attempt
                advertised_receive_address = $p2pHost
                effective_socket_bind_address = ""
                cached_local_bind_address = ""
                lifecycle_wifi_direct_local_address = $p2pHost
                source = "adb_current_p2p0_ipv4_fallback"
            }
        }
        Start-Sleep -Milliseconds 500
    }
    return [ordered]@{
        found = $false
        label = $Label
        serial = $Serial
        attempts = $attempt
        advertised_receive_address = ""
        source = "qcl041_receiver_artifact_timeout"
    }
}

function Get-Qcl041CurrentP2pAddressFromAdb {
    param([string]$Serial)
    $output = & $Adb -s $Serial shell ip -4 addr show p2p0 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($output)) {
        return ""
    }
    $match = [regex]::Match($output, 'inet\s+([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)\/')
    if (-not $match.Success) {
        return ""
    }
    return $match.Groups[1].Value
}

function Publish-Qcl082DeferredReceiverTarget {
    param(
        [string]$SenderSerial,
        [string]$SenderLabel,
        [string]$ReceiverSerial,
        [string]$ReceiverLabel,
        [string]$DeviceTargetFile
    )
    $target = Wait-Qcl041AdvertisedReceiveTarget -Serial $ReceiverSerial -Label $ReceiverLabel -TimeoutSeconds 85
    if (-not [bool]$target.found) {
        throw "QCL082 deferred receiver target was not published by $ReceiverLabel after $($target.attempts) attempts."
    }
    $lanes = [ordered]@{}
    if ($leftLaneActive) {
        $lanes.left = [ordered]@{ host = $target.advertised_receive_address; port = $LeftTransportPort }
    }
    if ($rightLaneActive) {
        $lanes.right = [ordered]@{ host = $target.advertised_receive_address; port = $RightTransportPort }
    }
    $targetPayload = [ordered]@{
        schema = "rusty.quest.qcl082_deferred_receiver_target.v1"
        run_id = $RunId
        topology_epoch = $RunId
        source = "host_receiver_artifact_deferred_target"
        receiver_label = $ReceiverLabel
        receiver_serial = $ReceiverSerial
        sender_label = $SenderLabel
        sender_serial = $SenderSerial
        bind_address = $target.effective_socket_bind_address
        advertised_receive_address = $target.advertised_receive_address
        advertised_receive_port = if ($leftLaneActive) { $LeftTransportPort } else { $RightTransportPort }
        lanes = $lanes
    }
    $deviceRelativePath = if ([string]::IsNullOrWhiteSpace($DeviceTargetFile)) {
        "qcl041/qcl082-deferred-receiver-target.json"
    } else {
        $DeviceTargetFile
    }
    $externalTargetPath = "/sdcard/Android/data/$Qcl041Package/files/$deviceRelativePath"
    $externalTargetDir = $externalTargetPath -replace '/[^/]+$', ''
    $targetPayload.external_target_path = $externalTargetPath
    $localPath = Join-Path $MediaDir "$SenderLabel-deferred-receiver-target.json"
    Write-JsonFile -Value $targetPayload -Path $localPath
    Invoke-AdbChecked -Serial $SenderSerial -Arguments @("shell", "mkdir", "-p", $externalTargetDir) -Name "mkdir qcl082 deferred external target"
    $pushStdout = Join-Path $MediaDir "$SenderLabel-deferred-receiver-target-push.stdout.txt"
    $pushStderr = Join-Path $MediaDir "$SenderLabel-deferred-receiver-target-push.stderr.txt"
    $pushProcess = Start-Process `
        -FilePath $Adb `
        -ArgumentList @("-s", $SenderSerial, "push", $localPath, $externalTargetPath) `
        -RedirectStandardOutput $pushStdout `
        -RedirectStandardError $pushStderr `
        -WindowStyle Hidden `
        -Wait `
        -PassThru
    if ($pushProcess.ExitCode -ne 0) {
        $pushOutput = ""
        if (Test-Path -LiteralPath $pushStdout) {
            $pushOutput += Get-Content -Raw $pushStdout
        }
        if (Test-Path -LiteralPath $pushStderr) {
            $pushOutput += Get-Content -Raw $pushStderr
        }
        throw "push QCL082 deferred external target $SenderSerial failed with exit code $($pushProcess.ExitCode). $pushOutput"
    }
    return $targetPayload
}

function Get-TransportRouteSpec {
    param([string]$PeerHost)
    return Get-RustyQuestDirectP2pTransportRouteSpec `
        -PeerHost $PeerHost `
        -LeftLaneActive:$leftLaneActive `
        -RightLaneActive:$rightLaneActive `
        -LeftTransportPort $LeftTransportPort `
        -RightTransportPort $RightTransportPort `
        -LanePrefix "qcl100-native"
}

function New-SenderParams {
    param(
        [string]$TransportRoutes,
        [string]$TransportBindLocalAddress
    )
    return New-RustyQuestRemoteCameraSenderParams `
        -SessionId $RunId `
        -SenderSourcePorts $effectiveSenderSourcePorts `
        -MediaProfiles $effectiveMediaProfiles `
        -CameraIds $effectiveCameraIds `
        -QualityProfile "qcl100-native-stereo-direct-wifi" `
        -TransportRoutes $TransportRoutes `
        -TransportBindLocalAddress $TransportBindLocalAddress
}
