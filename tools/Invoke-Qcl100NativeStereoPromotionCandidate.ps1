param(
    [string]$OwnerSerial = "340YC10G7T0JBW",
    [string]$ClientSerial = "3487C10H3M017Q",
    [string]$OwnerLeaseId = "",
    [string]$ClientLeaseId = "",
    [string]$RunId = "",
    [string]$OutDir = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$Qcl041Apk = "S:\Work\repos\active\rusty-quest\target\qcl041-wifi-direct-harness-android\rusty-quest-qcl041-wifi-direct-harness.apk",
    [string]$BrokerApk = "S:\Work\repos\active\rusty-quest\target\manifold-broker-android\rusty-manifold-broker.apk",
    [string]$NativeRendererApk = "S:\Work\repos\active\rusty-quest\target\native-renderer-android\rusty-quest-native-renderer.apk",
    [string]$NativeRendererProfile = "S:\Work\repos\active\rusty-quest\fixtures\runtime-profiles\quest-native-renderer-broker-rmanvid1-stereo-camera.profile.json",
    [string]$InfrastructureWifiSsid = "",
    [string]$OwnerWifiDirectAddress = "192.168.49.1",
    [string]$ClientWifiDirectAddress = "192.168.49.46",
    [int]$ProjectionSeconds = 45,
    [double]$MinFreshFrameSpanSeconds = 25.0,
    [int]$MinFreshFrameLines = 4,
    [int]$Qcl041StreamBytesPerDirection = 1048576,
    [int]$Qcl041StreamSeconds = 10,
    [int]$PollSeconds = 15,
    [int]$OverallTimeoutSeconds = 1200,
    [int]$PhaseStallTimeoutSeconds = 360,
    [int]$MaxQcl041MatrixGateAgeSeconds = 600,
    [string]$RouteClearRecoveryPath = "",
    [string]$Qcl041MatrixPath = "",
    [string]$MonitoredRunnerPath = "",
    [switch]$SkipInstall,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl100-native-stereo-promotion-candidate-" + (Get-Date -Format "yyyyMMddTHHmmssZ").ToString()
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path "S:\Work\repos\active\rusty-quest\target" $RunId
}
if ([string]::IsNullOrWhiteSpace($InfrastructureWifiSsid)) {
    throw "InfrastructureWifiSsid is required for guarded Disconnect without Forget."
}
if ($OwnerSerial -eq $ClientSerial) {
    throw "OwnerSerial and ClientSerial must be distinct."
}
if (-not $DryRun -and
    ([string]::IsNullOrWhiteSpace($OwnerLeaseId) -or [string]::IsNullOrWhiteSpace($ClientLeaseId))) {
    throw "Live promotion candidate requires OwnerLeaseId and ClientLeaseId."
}
if ($ProjectionSeconds -lt 30) {
    throw "ProjectionSeconds must be at least 30 for a full-stereo promotion candidate."
}
if ($MinFreshFrameSpanSeconds -lt 20.0) {
    throw "MinFreshFrameSpanSeconds must be at least 20 for a full-stereo promotion candidate."
}
if ($Qcl041StreamBytesPerDirection -lt 1048576) {
    throw "Qcl041StreamBytesPerDirection must be at least 1048576."
}

if ([string]::IsNullOrWhiteSpace($RouteClearRecoveryPath)) {
    $RouteClearRecoveryPath = Join-Path $PSScriptRoot "Invoke-Qcl100RouteClearRecovery.ps1"
}
if ([string]::IsNullOrWhiteSpace($Qcl041MatrixPath)) {
    $Qcl041MatrixPath = Join-Path $PSScriptRoot "Invoke-Qcl041QuestToQuestAppBoundSocketMatrix.ps1"
}
if ([string]::IsNullOrWhiteSpace($MonitoredRunnerPath)) {
    $MonitoredRunnerPath = Join-Path $PSScriptRoot "Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirectMonitored.ps1"
}
$RouteClearRecoveryPath = (Resolve-Path -LiteralPath $RouteClearRecoveryPath).Path
$Qcl041MatrixPath = (Resolve-Path -LiteralPath $Qcl041MatrixPath).Path
$MonitoredRunnerPath = (Resolve-Path -LiteralPath $MonitoredRunnerPath).Path

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$preRouteRunId = "$RunId-pre-route-clear"
$preRouteOutDir = Join-Path $OutDir "pre-route-clear"
$qcl041RunId = "$RunId-qcl041-local-bind-gate"
$qcl041OutDir = Join-Path $OutDir "qcl041-local-bind-gate"
$qcl041SummaryPath = Join-Path $qcl041OutDir "summary.json"
$mediaRunId = "$RunId-full-stereo"
$mediaOutDir = Join-Path $OutDir "full-stereo"
$mediaMonitorSummaryPath = Join-Path $mediaOutDir "qcl100-monitor-summary.json"
$mediaNativeSummaryPath = Join-Path $mediaOutDir "native-stereo-projection-summary.json"
$planPath = Join-Path $OutDir "qcl100-promotion-candidate-plan.json"

function Write-Qcl100PromotionCandidateJson {
    param(
        [Parameter(Mandatory=$true)]$Value,
        [Parameter(Mandatory=$true)][string]$Path
    )
    $json = ($Value | ConvertTo-Json -Depth 32) + "`n"
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function Read-Qcl100PromotionCandidateJson {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Get-Qcl100PromotionCandidateNestedValue {
    param($Object, [string[]]$Path)
    $value = $Object
    foreach ($part in $Path) {
        if ($null -eq $value) {
            return $null
        }
        $property = $value.PSObject.Properties[$part]
        if ($null -eq $property) {
            return $null
        }
        $value = $property.Value
    }
    return $value
}

function Get-Qcl100PreRouteClearAcceptance {
    param([string]$Path)
    $policy = Read-Qcl100PromotionCandidateJson -Path (Join-Path $Path "qcl100-route-clear-recovery-wrapper.json")
    $monitor = Read-Qcl100PromotionCandidateJson -Path (Join-Path $Path "qcl100-monitor-summary.json")
    $summary = Read-Qcl100PromotionCandidateJson -Path (Join-Path $Path "native-stereo-projection-summary.json")
    $issues = @()
    if ([string](Get-Qcl100PromotionCandidateNestedValue -Object $policy -Path @("status")) -ne "monitor_completed") {
        $issues += "pre_route_clear_wrapper_not_completed"
    }
    if (-not [bool](Get-Qcl100PromotionCandidateNestedValue -Object $policy -Path @("promotion_candidate_pre_route_clear")) -or
        [bool](Get-Qcl100PromotionCandidateNestedValue -Object $policy -Path @("final_promotion_route_clear"))) {
        $issues += "pre_route_clear_scope_mismatch"
    }
    if (-not [bool](Get-Qcl100PromotionCandidateNestedValue -Object $policy -Path @("crash_watch_policy", "promotion_candidate_parent_authorized")) -or
        [bool](Get-Qcl100PromotionCandidateNestedValue -Object $policy -Path @("crash_watch_policy", "requires_human_review_before_media"))) {
        $issues += "pre_route_clear_parent_authorization_missing"
    }
    if ([string](Get-Qcl100PromotionCandidateNestedValue -Object $monitor -Path @("status")) -ne "completed") {
        $issues += "pre_route_clear_monitor_not_completed"
    }
    if ([string](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("status")) -ne "preflight_only") {
        $issues += "pre_route_clear_summary_not_preflight_only"
    }
    foreach ($requirement in @(
            @{ path = @("preflight", "infrastructure_wifi_disconnected"); issue = "pre_route_clear_infrastructure_wifi_connected" },
            @{ path = @("preflight", "p2p0_ipv4_cleared"); issue = "pre_route_clear_p2p0_not_clear" },
            @{ path = @("preflight", "candidate_wifi_direct_prelaunch_routes_clear"); issue = "pre_route_clear_candidate_routes_not_clear" }
        )) {
        if (-not [bool](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path $requirement.path)) {
            $issues += $requirement.issue
        }
    }
    $issues = @($issues | Select-Object -Unique)
    return [ordered]@{
        accepted = [bool]($issues.Count -eq 0)
        issues = $issues
        policy_status = Get-Qcl100PromotionCandidateNestedValue -Object $policy -Path @("status")
        monitor_status = Get-Qcl100PromotionCandidateNestedValue -Object $monitor -Path @("status")
        final_summary_status = Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("status")
    }
}

function Get-Qcl100LocalBindGateAcceptance {
    param(
        [string]$Path,
        [string]$ExpectedRunId
    )
    $summary = Read-Qcl100PromotionCandidateJson -Path $Path
    $issues = @()
    if ([string](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("schema")) -ne "rusty.quest.qcl041_q2q_app_bound_socket_matrix_run.v1") {
        $issues += "qcl041_gate_schema_mismatch"
    }
    if ([string](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("run_id")) -ne $ExpectedRunId) {
        $issues += "qcl041_gate_run_id_mismatch"
    }
    if ([string](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("owner_serial")) -ne $OwnerSerial -or
        [string](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("client_serial")) -ne $ClientSerial) {
        $issues += "qcl041_gate_device_identity_mismatch"
    }
    if ([string](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("status")) -ne "pass") {
        $issues += "qcl041_gate_status_not_pass"
    }
    if (-not [string]::IsNullOrWhiteSpace([string](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("blocked_reason")))) {
        $issues += "qcl041_gate_blocked_reason_present"
    }
    if ([string](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("qcl100_lower_gate_authority")) -ne "rusty_direct_p2p_socket_authority") {
        $issues += "qcl041_gate_wrong_authority"
    }
    if ([string](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("qcl041_group_owner_label")) -ne "owner" -or
        [string](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("owner_qcl041_role")) -ne "group_owner" -or
        [string](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("client_qcl041_role")) -ne "client") {
        $issues += "qcl041_gate_role_layout_mismatch"
    }
    foreach ($requirement in @(
            @{ path = @("preflight", "infrastructure_wifi_disconnected"); issue = "qcl041_gate_infrastructure_wifi_connected" },
            @{ path = @("preflight", "p2p0_ipv4_cleared"); issue = "qcl041_gate_p2p0_not_clear" },
            @{ path = @("preflight", "candidate_wifi_direct_prelaunch_routes_clear"); issue = "qcl041_gate_candidate_routes_not_clear" },
            @{ path = @("matrix", "client_strict_local_p2p_app_transport_pass"); issue = "qcl041_strict_local_p2p_transport_not_passed" },
            @{ path = @("matrix", "client_p2p_interface_local_bind_udp_pass"); issue = "qcl041_local_bind_udp_not_passed" },
            @{ path = @("matrix", "client_p2p_interface_local_bind_tcp_pass"); issue = "qcl041_local_bind_tcp_not_passed" },
            @{ path = @("matrix", "client_p2p_interface_local_bind_tcp_stream_pass"); issue = "qcl041_local_bind_tcp_stream_variant_not_passed" },
            @{ path = @("matrix", "client_p2p_interface_local_bind_udp_receiver_observed_source_matches_client_p2p"); issue = "qcl041_local_bind_udp_source_address_mismatch" }
        )) {
        if (-not [bool](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path $requirement.path)) {
            $issues += $requirement.issue
        }
    }
    if ([string](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("matrix", "client_p2p_interface_local_bind_socket_authority")) -ne "network_interface_local_p2p_address_bind") {
        $issues += "qcl041_local_bind_socket_authority_mismatch"
    }
    if (-not [bool](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("matrix", "local_p2p_bind_tcp_stream_pass"))) {
        $issues += "qcl041_local_bind_stream_not_passed"
    }
    if ([int64](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("matrix", "local_p2p_bind_tcp_stream_sender_to_receiver_rx_bytes")) -lt $Qcl041StreamBytesPerDirection) {
        $issues += "qcl041_sender_to_receiver_bytes_too_low"
    }
    if ([int64](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("matrix", "local_p2p_bind_tcp_stream_receiver_to_sender_rx_bytes")) -lt $Qcl041StreamBytesPerDirection) {
        $issues += "qcl041_receiver_to_sender_bytes_too_low"
    }
    if (-not [bool](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("matrix", "local_p2p_bind_tcp_stream_sender_to_receiver_crc32_match")) -or
        -not [bool](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("matrix", "local_p2p_bind_tcp_stream_receiver_to_sender_crc32_match"))) {
        $issues += "qcl041_stream_crc_mismatch"
    }
    if ([bool](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("promotion_allowed")) -or
        [bool](Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("same_group_duplex_claimed"))) {
        $issues += "qcl041_gate_claimed_promotion_or_duplex"
    }
    $issues = @($issues | Select-Object -Unique)
    return [ordered]@{
        accepted = [bool]($issues.Count -eq 0)
        issues = $issues
        status = Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("status")
        sender_to_receiver_rx_bytes = Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("matrix", "local_p2p_bind_tcp_stream_sender_to_receiver_rx_bytes")
        receiver_to_sender_rx_bytes = Get-Qcl100PromotionCandidateNestedValue -Object $summary -Path @("matrix", "local_p2p_bind_tcp_stream_receiver_to_sender_rx_bytes")
    }
}

$qcl041Variants = @(
    "udp_local_p2p_bind_echo",
    "tcp_local_p2p_bind_socket",
    "tcp_local_p2p_bind_stream_socket"
)
$plan = [ordered]@{
    schema = "rusty.quest.qcl100_native_stereo_promotion_candidate_plan.v1"
    run_id = $RunId
    status = if ($DryRun) { "dry_run_planned" } else { "running" }
    out_dir = $OutDir
    owner_serial = $OwnerSerial
    client_serial = $ClientSerial
    owner_lease_id = $OwnerLeaseId
    client_lease_id = $ClientLeaseId
    lease_ids_supplied = [bool](-not [string]::IsNullOrWhiteSpace($OwnerLeaseId) -and -not [string]::IsNullOrWhiteSpace($ClientLeaseId))
    qcl100_lower_gate_authority = "rusty_direct_p2p_socket_authority"
    transport_owner = "broker"
    direction = "duplex"
    lane_mode = "stereo"
    qcl099_makepad_used = $false
    infrastructure_wifi_ssid = $InfrastructureWifiSsid
    promotion_authority = "qcl100_monitored_promotion_acceptance"
    wake_prep_policy = "external_watchdog_owned_skip_runner_mutation"
    pre_route_clear = [ordered]@{
        run_id = $preRouteRunId
        out_dir = $preRouteOutDir
        script = $RouteClearRecoveryPath
    }
    qcl041_gate = [ordered]@{
        run_id = $qcl041RunId
        out_dir = $qcl041OutDir
        summary_path = $qcl041SummaryPath
        script = $Qcl041MatrixPath
        group_owner_label = "owner"
        variants = $qcl041Variants
        stream_bytes_per_direction = $Qcl041StreamBytesPerDirection
        stream_seconds = $Qcl041StreamSeconds
    }
    full_stereo = [ordered]@{
        run_id = $mediaRunId
        out_dir = $mediaOutDir
        monitor_summary_path = $mediaMonitorSummaryPath
        native_summary_path = $mediaNativeSummaryPath
        script = $MonitoredRunnerPath
        projection_seconds = $ProjectionSeconds
        minimum_fresh_frame_span_seconds = $MinFreshFrameSpanSeconds
        final_route_clear_requested = $true
    }
    same_group_duplex_claimed = $false
    promotion_claimed = $false
}
Write-Qcl100PromotionCandidateJson -Value $plan -Path $planPath

$routeParams = @{
    OwnerSerial = $OwnerSerial
    ClientSerial = $ClientSerial
    OwnerLeaseId = $OwnerLeaseId
    ClientLeaseId = $ClientLeaseId
    RunId = $preRouteRunId
    OutDir = $preRouteOutDir
    Adb = $Adb
    Qcl041Apk = $Qcl041Apk
    SettingsFenceTarget = "both"
    DisconnectInfrastructureWifiBeforeRun = $true
    InfrastructureWifiDisconnectTarget = "both"
    InfrastructureWifiSsid = $InfrastructureWifiSsid
    ClearLogcatAfterSettingsFence = $true
    PromotionCandidatePreRouteClear = $true
    OwnerWifiDirectAddress = $OwnerWifiDirectAddress
    ClientWifiDirectAddress = $ClientWifiDirectAddress
}

$monitorParams = @{
    OwnerSerial = $OwnerSerial
    ClientSerial = $ClientSerial
    OwnerLeaseId = $OwnerLeaseId
    ClientLeaseId = $ClientLeaseId
    RunId = $mediaRunId
    OutDir = $mediaOutDir
    Adb = $Adb
    Qcl041Apk = $Qcl041Apk
    BrokerApk = $BrokerApk
    NativeRendererApk = $NativeRendererApk
    NativeRendererProfile = $NativeRendererProfile
    Direction = "duplex"
    LaneMode = "stereo"
    TransportOwner = "broker"
    Qcl100LowerGateAuthority = "rusty_direct_p2p_socket_authority"
    ProjectionSeconds = $ProjectionSeconds
    MinFreshFrameSpanSeconds = $MinFreshFrameSpanSeconds
    MinFreshFrameLines = $MinFreshFrameLines
    Qcl082TransportProtocol = "control-tcp"
    Qcl082ControlTcpMediaStreamBytesPerDirection = 65536
    PollSeconds = $PollSeconds
    OverallTimeoutSeconds = $OverallTimeoutSeconds
    PhaseStallTimeoutSeconds = $PhaseStallTimeoutSeconds
    OwnerWifiDirectAddress = $OwnerWifiDirectAddress
    ClientWifiDirectAddress = $ClientWifiDirectAddress
    RequireInfrastructureWifiDisconnected = $true
    RequireP2p0Ipv4Cleared = $true
    RequireCandidateWifiDirectRoutesClear = $true
    RunQcl041PreclearBeforeAirgapPreflight = $true
    RequireQcl041MatrixGatePass = $true
    RequiredQcl041MatrixSummaryPath = $qcl041SummaryPath
    RequiredQcl041MatrixRunId = $qcl041RunId
    MaxQcl041MatrixGateAgeSeconds = $MaxQcl041MatrixGateAgeSeconds
    DisconnectInfrastructureWifiBeforeRun = $true
    InfrastructureWifiDisconnectTarget = "both"
    InfrastructureWifiSsid = $InfrastructureWifiSsid
    FenceSettingsBeforeRun = $true
    SettingsFenceTarget = "both"
    ClearLogcatAfterSettingsFence = $true
    RunFinalRouteClear = $true
    SkipWakePrep = $true
}
if ($SkipInstall) {
    $monitorParams["SkipInstall"] = $true
}

if ($DryRun) {
    $routeDryParams = $routeParams.Clone()
    $routeDryParams["DryRun"] = $true
    & $RouteClearRecoveryPath @routeDryParams | Out-Null
    $monitorDryParams = $monitorParams.Clone()
    $monitorDryParams["DryRun"] = $true
    & $MonitoredRunnerPath @monitorDryParams | Out-Null
    $plan["status"] = "dry_run"
    $plan["hardware_touched"] = $false
    $plan["pre_route_clear_dry_run_summary"] = Join-Path $preRouteOutDir "qcl100-monitor-summary.json"
    $plan["full_stereo_dry_run_summary"] = $mediaMonitorSummaryPath
    Write-Qcl100PromotionCandidateJson -Value $plan -Path $planPath
    Get-Content -Raw -LiteralPath $planPath
    return
}

$failure = $null
try {
    & $RouteClearRecoveryPath @routeParams | Out-Null
    $preRouteAcceptance = Get-Qcl100PreRouteClearAcceptance -Path $preRouteOutDir
    $plan["pre_route_clear_acceptance"] = $preRouteAcceptance
    Write-Qcl100PromotionCandidateJson -Value $plan -Path $planPath
    if (-not [bool]$preRouteAcceptance.accepted) {
        throw "Pre-route-clear acceptance failed: $($preRouteAcceptance.issues -join ', ')"
    }

    $qcl041Params = @{
        OwnerSerial = $OwnerSerial
        ClientSerial = $ClientSerial
        RunId = $qcl041RunId
        OutDir = $qcl041OutDir
        Adb = $Adb
        Qcl041Apk = $Qcl041Apk
        Qcl041GroupOwnerLabel = "owner"
        Qcl100ControlTcpGate = $true
        Qcl100LowerGateAuthority = "rusty_direct_p2p_socket_authority"
        AppNetworkTraceOnly = $true
        TcpBindingVariants = $qcl041Variants
        TcpTunnelStreamSeconds = $Qcl041StreamSeconds
        TcpTunnelStreamBytesPerDirection = $Qcl041StreamBytesPerDirection
        RequireInfrastructureWifiDisconnected = $true
        RequireP2p0Ipv4Cleared = $true
        RequireCandidateWifiDirectRoutesClear = $true
        RequireTcpTunnelStreamPass = $true
        FenceQuestSettingsBeforeLaunch = $true
        ClearLogcatAfterSettingsFence = $true
    }
    if ($SkipInstall) {
        $qcl041Params["SkipInstall"] = $true
    }
    & $Qcl041MatrixPath @qcl041Params | Out-Null
    $qcl041Acceptance = Get-Qcl100LocalBindGateAcceptance -Path $qcl041SummaryPath -ExpectedRunId $qcl041RunId
    $plan["qcl041_gate_acceptance"] = $qcl041Acceptance
    Write-Qcl100PromotionCandidateJson -Value $plan -Path $planPath
    if (-not [bool]$qcl041Acceptance.accepted) {
        throw "QCL041 local-bind gate acceptance failed: $($qcl041Acceptance.issues -join ', ')"
    }

    & $MonitoredRunnerPath @monitorParams | Out-Null
    $monitorSummary = Read-Qcl100PromotionCandidateJson -Path $mediaMonitorSummaryPath
    $nativeSummary = Read-Qcl100PromotionCandidateJson -Path $mediaNativeSummaryPath
    $promotionAccepted = [bool](Get-Qcl100PromotionCandidateNestedValue -Object $monitorSummary -Path @("promotion_acceptance", "accepted"))
    $plan["monitor_status"] = Get-Qcl100PromotionCandidateNestedValue -Object $monitorSummary -Path @("status")
    $plan["promotion_acceptance"] = Get-Qcl100PromotionCandidateNestedValue -Object $monitorSummary -Path @("promotion_acceptance")
    $plan["native_summary_present"] = [bool]($null -ne $nativeSummary)
    $plan["same_group_duplex_claimed"] = [bool](Get-Qcl100PromotionCandidateNestedValue -Object $monitorSummary -Path @("same_group_duplex_claimed"))
    $plan["promotion_claimed"] = [bool](Get-Qcl100PromotionCandidateNestedValue -Object $monitorSummary -Path @("promotion_claimed"))
    $promotionIssues = @()
    if ([string](Get-Qcl100PromotionCandidateNestedValue -Object $monitorSummary -Path @("schema")) -ne "rusty.quest.qcl100_monitor_summary.v1") {
        $promotionIssues += "monitor_schema_mismatch"
    }
    if ([string](Get-Qcl100PromotionCandidateNestedValue -Object $monitorSummary -Path @("status")) -ne "completed") {
        $promotionIssues += "monitor_status_not_completed"
    }
    if ($null -eq $nativeSummary) {
        $promotionIssues += "native_summary_absent"
    }
    if (-not $promotionAccepted) {
        $promotionIssues += "promotion_acceptance_not_passed"
    }
    if ([string](Get-Qcl100PromotionCandidateNestedValue -Object $monitorSummary -Path @("promotion_acceptance", "status")) -ne "promoted_same_group_full_stereo_duplex_with_rusty_direct_p2p_socket_authority") {
        $promotionIssues += "promotion_acceptance_status_mismatch"
    }
    if (-not [bool]$plan.promotion_claimed -or -not [bool]$plan.same_group_duplex_claimed) {
        $promotionIssues += "monitor_promotion_claims_missing"
    }
    $promotionIssues = @($promotionIssues | Select-Object -Unique)
    if ($promotionIssues.Count -gt 0) {
        throw "Monitored full-stereo promotion acceptance did not pass: $($promotionIssues -join ', ')"
    }
    $plan["status"] = "promoted"
    Write-Qcl100PromotionCandidateJson -Value $plan -Path $planPath
} catch {
    $failure = $_.Exception.Message
    $plan["status"] = "blocked"
    $plan["error"] = $failure
    $monitorSummary = Read-Qcl100PromotionCandidateJson -Path $mediaMonitorSummaryPath
    if ($null -ne $monitorSummary) {
        $plan["monitor_status"] = Get-Qcl100PromotionCandidateNestedValue -Object $monitorSummary -Path @("status")
        $plan["promotion_acceptance"] = Get-Qcl100PromotionCandidateNestedValue -Object $monitorSummary -Path @("promotion_acceptance")
        $plan["same_group_duplex_claimed"] = [bool](Get-Qcl100PromotionCandidateNestedValue -Object $monitorSummary -Path @("same_group_duplex_claimed"))
        $plan["promotion_claimed"] = [bool](Get-Qcl100PromotionCandidateNestedValue -Object $monitorSummary -Path @("promotion_claimed"))
    }
    Write-Qcl100PromotionCandidateJson -Value $plan -Path $planPath
}

Get-Content -Raw -LiteralPath $planPath
if ($null -ne $failure) {
    throw "QCL100 native stereo promotion candidate blocked: $failure"
}
