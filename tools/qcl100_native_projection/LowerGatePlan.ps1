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
        [int]$ProjectionSeconds,
        [int]$NoMediaLaunchSeconds,
        [int]$Qcl082ControlTcpMediaStreamBytesPerDirection,
        [string]$RequiredQcl041MatrixSummaryPath,
        [string]$RequiredQcl041MatrixRunId,
        [int]$MaxQcl041MatrixGateAgeSeconds,
        [string]$Qcl100ScriptPath,
        [string]$Qcl041MatrixScriptPath
    )
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
        "-Qcl082TransportProtocol", "control-tcp",
        "-PreflightOnly",
        "-RequireInfrastructureWifiDisconnected",
        "-RequireP2p0Ipv4Cleared",
        "-RequireCandidateWifiDirectRoutesClear"
    )
    $controlTcpGateArgs = @(
        "-RunId", $controlTcpGateRunId,
        "-OutDir", $controlTcpGateOutDir,
        "-OwnerSerial", $OwnerSerial,
        "-ClientSerial", $ClientSerial,
        "-OwnerWifiDirectAddress", $OwnerWifiDirectAddress,
        "-ClientWifiDirectAddress", $ClientWifiDirectAddress,
        "-Qcl041Q2qNetworkName", $Qcl041Q2qNetworkName,
        "-Qcl041Q2qPassphrase", $Qcl041Q2qPassphrase,
        "-Qcl100ControlTcpGate",
        "-RequireInfrastructureWifiDisconnected",
        "-RequireP2p0Ipv4Cleared",
        "-RequireCandidateWifiDirectRoutesClear",
        "-RequireTcpTunnelStreamPass"
    )
    $xrReadinessArgs = @(
        "-RunId", $xrReadinessRunId,
        "-OutDir", $xrReadinessOutDir,
        "-OwnerSerial", $OwnerSerial,
        "-ClientSerial", $ClientSerial,
        "-Qcl082TransportProtocol", "control-tcp",
        "-XrLaunchReadinessOnly",
        "-RequireQcl041MatrixGatePass",
        "-RequiredQcl041MatrixSummaryPath", $effectiveMatrixSummaryPath,
        "-RequiredQcl041MatrixRunId", $effectiveMatrixRunId,
        "-MaxQcl041MatrixGateAgeSeconds", ([Math]::Max(0, $MaxQcl041MatrixGateAgeSeconds)).ToString()
    )
    $noMediaArgs = @(
        "-RunId", $noMediaRunId,
        "-OutDir", $noMediaOutDir,
        "-OwnerSerial", $OwnerSerial,
        "-ClientSerial", $ClientSerial,
        "-Qcl082TransportProtocol", "control-tcp",
        "-NoMediaLaunchOnly",
        "-NoMediaLaunchSeconds", ([Math]::Max(1, $NoMediaLaunchSeconds)).ToString(),
        "-RequireQcl041MatrixGatePass",
        "-RequiredQcl041MatrixSummaryPath", $effectiveMatrixSummaryPath,
        "-RequiredQcl041MatrixRunId", $effectiveMatrixRunId,
        "-MaxQcl041MatrixGateAgeSeconds", ([Math]::Max(0, $MaxQcl041MatrixGateAgeSeconds)).ToString()
    )
    $lowerGateEvidenceArgs = @(
        "-RunId", $lowerGateEvidenceRunId,
        "-OutDir", $lowerGateEvidenceOutDir,
        "-ValidateLowerGateEvidenceOnly",
        "-LowerGatePlanSummaryPath", $planSummaryPath,
        "-RouteClearSummaryPath", $routeClearSummaryPath,
        "-Qcl041ControlTcpSummaryPath", $controlTcpGateSummaryPath,
        "-XrReadinessSummaryPath", $xrReadinessSummaryPath,
        "-NoMediaLaunchSummaryPath", $noMediaSummaryPath
    )
    $shortMediaBytes = if ($Qcl082ControlTcpMediaStreamBytesPerDirection -gt 0) {
        [Math]::Min($Qcl082ControlTcpMediaStreamBytesPerDirection, 262144)
    } else {
        65536
    }
    $shortMediaSeconds = [Math]::Min([Math]::Max(5, $ProjectionSeconds), 12)
    $shortMediaArgs = @(
        "-RunId", $shortMediaRunId,
        "-OutDir", (Join-Path $targetRoot $shortMediaRunId),
        "-OwnerSerial", $OwnerSerial,
        "-ClientSerial", $ClientSerial,
        "-OwnerWifiDirectAddress", $OwnerWifiDirectAddress,
        "-ClientWifiDirectAddress", $ClientWifiDirectAddress,
        "-Qcl041Q2qNetworkName", $Qcl041Q2qNetworkName,
        "-Qcl041Q2qPassphrase", $Qcl041Q2qPassphrase,
        "-Direction", "owner-to-client",
        "-LaneMode", "left-only",
        "-Qcl082TransportProtocol", "control-tcp",
        "-ProjectionSeconds", $shortMediaSeconds.ToString(),
        "-Qcl082ControlTcpMediaStreamBytesPerDirection", $shortMediaBytes.ToString(),
        "-RequireQcl041MatrixGatePass",
        "-RequiredQcl041MatrixSummaryPath", $effectiveMatrixSummaryPath,
        "-RequiredQcl041MatrixRunId", $effectiveMatrixRunId,
        "-MaxQcl041MatrixGateAgeSeconds", ([Math]::Max(0, $MaxQcl041MatrixGateAgeSeconds)).ToString()
    )
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
        requested_lane_mode = $LaneMode
        requested_qcl082_transport_protocol = $Qcl082TransportProtocol
        diagnostic_transport_protocol = "control-tcp"
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
                -Status "required_before_qcl100_media" `
                -Objective "Run the QCL041 same-group control-TCP matrix gate and require sustained receiver-observed bidirectional TCP tunnel stream bytes." `
                -LaunchesQcl100Media $false `
                -LaunchesNativeRenderer $false `
                -DeviceMutation "forms and tears down a QCL041 Wi-Fi Direct group on the two serial-scoped devices" `
                -Command (New-Qcl100LowerGateCommand -ScriptPath $Qcl041MatrixScriptPath -Arguments $controlTcpGateArgs) `
                -ExpectedArtifacts @($controlTcpGateSummaryPath) `
                -RequiredPassFields @("status=pass", "qcl100_control_tcp_gate=true", "matrix_focus=qcl100_control_tcp_gate", "require_tcp_tunnel_stream_pass=true", "matrix.tcp_tunnel_stream_bidirectional_bytes_pass=true"))
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
                -Status "allowed_after_lower_gates_pass" `
                -Objective "Run a left-only, short-duration control-TCP diagnostic media path after route, QCL041, and XR readiness gates are clean." `
                -LaunchesQcl100Media $true `
                -LaunchesNativeRenderer $true `
                -DeviceMutation "launches QCL041, broker, native renderer, and short QCL082 control-TCP media" `
                -Command (New-Qcl100LowerGateCommand -ScriptPath $Qcl100ScriptPath -Arguments $shortMediaArgs) `
                -ExpectedArtifacts @((Join-Path (Join-Path $targetRoot $shortMediaRunId) "native-stereo-projection-summary.json")) `
                -RequiredPassFields @("freshness_acceptance.passed=true", "qcl082_media_topology_accepted=true", "native_log_summary.system_fatal_count=0"))
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
    if (@($plan.lower_gate_sequence).Count -ne 7) {
        throw "QCL100 lower-gate plan self-test expected seven ordered lower-gate steps."
    }
    $routeStep = $plan.lower_gate_sequence[0]
    if ($routeStep.id -ne "route_clear_passive_preflight" -or @($routeStep.command.arguments) -notcontains "-PreflightOnly" -or @($routeStep.command.arguments) -notcontains "-RequireCandidateWifiDirectRoutesClear") {
        throw "QCL100 lower-gate plan self-test expected route-clear passive preflight command."
    }
    $controlTcpStep = $plan.lower_gate_sequence[1]
    if ($controlTcpStep.id -ne "qcl041_strict_control_tcp_gate" -or @($controlTcpStep.command.arguments) -notcontains "-Qcl100ControlTcpGate" -or @($controlTcpStep.command.arguments) -notcontains "-RequireTcpTunnelStreamPass") {
        throw "QCL100 lower-gate plan self-test expected strict QCL041 control-TCP gate command."
    }
    $noMediaStep = $plan.lower_gate_sequence[3]
    if ($noMediaStep.status -ne "available_after_wake_policy_and_lower_gates_pass" -or -not [bool]$noMediaStep.launches_native_renderer -or @($noMediaStep.command.arguments) -notcontains "-NoMediaLaunchOnly") {
        throw "QCL100 lower-gate plan self-test expected executable no-media launch gate to remain explicit."
    }
    $evidenceStep = $plan.lower_gate_sequence[4]
    if ($evidenceStep.id -ne "qcl100_lower_gate_evidence_validation" -or $evidenceStep.status -ne "required_before_short_media_gate" -or @($evidenceStep.command.arguments) -notcontains "-ValidateLowerGateEvidenceOnly") {
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
    $selfTestPath = Join-Path $OutputDirectory "qcl100-lower-gate-plan-self-test.json"
    $selfTestParent = Split-Path -Parent $selfTestPath
    if (-not [string]::IsNullOrWhiteSpace($selfTestParent)) {
        New-Item -ItemType Directory -Force -Path $selfTestParent | Out-Null
    }
    $selfTest | ConvertTo-Json -Depth 24 | Set-Content -Path $selfTestPath -Encoding UTF8
    return $selfTest
}
