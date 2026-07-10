param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
} else {
    $RepoRoot = Resolve-Path $RepoRoot
}

function Assert-Qcl100PromotionCandidateStatic {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        throw "QCL100 native stereo promotion candidate static check failed: $Message"
    }
}

function Remove-Qcl100PromotionCandidateTempDirectory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $resolvedPath = [System.IO.Path]::GetFullPath($Path)
    if (-not $resolvedPath.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove non-temp promotion candidate test directory: $resolvedPath"
    }
    Remove-Item -LiteralPath $resolvedPath -Recurse -Force
}

$candidatePath = Join-Path $RepoRoot "tools\Invoke-Qcl100NativeStereoPromotionCandidate.ps1"
Assert-Qcl100PromotionCandidateStatic -Condition (Test-Path -LiteralPath $candidatePath) -Message "candidate wrapper is missing"

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($candidatePath, [ref]$tokens, [ref]$parseErrors)
Assert-Qcl100PromotionCandidateStatic -Condition ($parseErrors.Count -eq 0) -Message "candidate wrapper has parse errors: $($parseErrors | Out-String)"

$candidateText = Get-Content -Raw -LiteralPath $candidatePath
foreach ($token in @(
        "rusty.quest.qcl100_native_stereo_promotion_candidate_plan.v1",
        "rusty_direct_p2p_socket_authority",
        "qcl099_makepad_used = `$false",
        "udp_local_p2p_bind_echo",
        "tcp_local_p2p_bind_socket",
        "tcp_local_p2p_bind_stream_socket",
        "PromotionCandidatePreRouteClear = `$true",
        "Qcl041GroupOwnerLabel = `"owner`"",
        "RequireTcpTunnelStreamPass = `$true",
        "Direction = `"duplex`"",
        "LaneMode = `"stereo`"",
        "SettingsFenceTarget = `"both`"",
        "SkipWakePrep = `$true",
        "RunFinalRouteClear = `$true",
        "promoted_same_group_full_stereo_duplex_with_rusty_direct_p2p_socket_authority"
    )) {
    Assert-Qcl100PromotionCandidateStatic -Condition $candidateText.Contains($token) -Message "candidate wrapper is missing token: $token"
}

$dryRunDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-promotion-candidate-static-" + [guid]::NewGuid().ToString("N"))
try {
    $runId = "qcl100-native-stereo-promotion-candidate-static-dryrun"
    $output = & $candidatePath `
        -DryRun `
        -RunId $runId `
        -OutDir $dryRunDir `
        -InfrastructureWifiSsid "Static-Test-SSID" 2>&1 | Out-String
    $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    Assert-Qcl100PromotionCandidateStatic -Condition ($exitCode -eq 0) -Message "dry run exited with ${exitCode}: $output"

    $plan = Get-Content -Raw -LiteralPath (Join-Path $dryRunDir "qcl100-promotion-candidate-plan.json") | ConvertFrom-Json
    Assert-Qcl100PromotionCandidateStatic -Condition ([string]$plan.status -eq "dry_run") -Message "dry-run plan status drifted"
    Assert-Qcl100PromotionCandidateStatic -Condition (-not [bool]$plan.hardware_touched) -Message "dry-run plan claimed hardware access"
    Assert-Qcl100PromotionCandidateStatic -Condition ([string]$plan.qcl100_lower_gate_authority -eq "rusty_direct_p2p_socket_authority") -Message "custom lower-gate authority drifted"
    Assert-Qcl100PromotionCandidateStatic -Condition ([string]$plan.direction -eq "duplex" -and [string]$plan.lane_mode -eq "stereo") -Message "full-stereo duplex topology drifted"
    Assert-Qcl100PromotionCandidateStatic -Condition (-not [bool]$plan.qcl099_makepad_used) -Message "default candidate reintroduced QCL099/Makepad"
    Assert-Qcl100PromotionCandidateStatic -Condition (-not [bool]$plan.promotion_claimed -and -not [bool]$plan.same_group_duplex_claimed) -Message "dry run claimed promotion"
    $variants = @($plan.qcl041_gate.variants)
    Assert-Qcl100PromotionCandidateStatic -Condition (
        $variants.Count -eq 3 -and
        $variants -contains "udp_local_p2p_bind_echo" -and
        $variants -contains "tcp_local_p2p_bind_socket" -and
        $variants -contains "tcp_local_p2p_bind_stream_socket"
    ) -Message "QCL041 local-bind variant set drifted"

    $preRouteDir = [string]$plan.pre_route_clear.out_dir
    $preRouteMonitor = Get-Content -Raw -LiteralPath (Join-Path $preRouteDir "qcl100-monitor-summary.json") | ConvertFrom-Json
    $preRoutePolicy = Get-Content -Raw -LiteralPath (Join-Path $preRouteDir "qcl100-route-clear-recovery-wrapper.json") | ConvertFrom-Json
    Assert-Qcl100PromotionCandidateStatic -Condition ([string]$preRouteMonitor.status -eq "dry_run") -Message "pre-route monitor dry run missing"
    Assert-Qcl100PromotionCandidateStatic -Condition ([bool]$preRoutePolicy.promotion_candidate_pre_route_clear -and -not [bool]$preRoutePolicy.final_promotion_route_clear) -Message "pre-route promotion-candidate scope drifted"
    Assert-Qcl100PromotionCandidateStatic -Condition ([bool]$preRoutePolicy.crash_watch_policy.promotion_candidate_parent_authorized -and -not [bool]$preRoutePolicy.crash_watch_policy.requires_human_review_before_media) -Message "pre-route parent authorization is not explicit"
    Assert-Qcl100PromotionCandidateStatic -Condition ([bool]$preRouteMonitor.infrastructure_wifi_disconnect_requested) -Message "pre-route guarded disconnect not requested"
    Assert-Qcl100PromotionCandidateStatic -Condition ([string]$preRouteMonitor.infrastructure_wifi_disconnect.target -eq "both") -Message "pre-route disconnect does not cover both headsets"
    Assert-Qcl100PromotionCandidateStatic -Condition ([string]$preRouteMonitor.settings_fence.target -eq "both") -Message "pre-route Settings fence does not cover both headsets"
    Assert-Qcl100PromotionCandidateStatic -Condition (-not [bool]$preRouteMonitor.infrastructure_wifi_disconnect.hardware_touched -and -not [bool]$preRouteMonitor.settings_fence.hardware_touched) -Message "pre-route dry run claimed hardware access"

    $fullStereoDir = [string]$plan.full_stereo.out_dir
    $fullStereoMonitor = Get-Content -Raw -LiteralPath (Join-Path $fullStereoDir "qcl100-monitor-summary.json") | ConvertFrom-Json
    $fullStereoParams = Get-Content -Raw -LiteralPath (Join-Path $fullStereoDir "qcl100-runner-params.json") | ConvertFrom-Json
    Assert-Qcl100PromotionCandidateStatic -Condition ([bool]$fullStereoMonitor.promotion_candidate_requested) -Message "full-stereo dry run is not marked as a promotion candidate"
    Assert-Qcl100PromotionCandidateStatic -Condition ([bool]$fullStereoMonitor.final_route_clear_requested) -Message "final route clear is not requested"
    Assert-Qcl100PromotionCandidateStatic -Condition (-not [bool]$fullStereoMonitor.promotion_claimed) -Message "full-stereo dry run claimed promotion"
    Assert-Qcl100PromotionCandidateStatic -Condition ([string]$fullStereoParams.Direction -eq "duplex" -and [string]$fullStereoParams.LaneMode -eq "stereo") -Message "runner params are not full-stereo duplex"
    Assert-Qcl100PromotionCandidateStatic -Condition ([string]$fullStereoParams.TransportOwner -eq "broker") -Message "broker transport ownership drifted"
    Assert-Qcl100PromotionCandidateStatic -Condition ([string]$fullStereoParams.Qcl100LowerGateAuthority -eq "rusty_direct_p2p_socket_authority") -Message "runner params do not use Rusty direct P2P authority"
    Assert-Qcl100PromotionCandidateStatic -Condition ([bool]$fullStereoParams.RequireQcl041MatrixGatePass) -Message "fresh QCL041 matrix gate is not required"
    Assert-Qcl100PromotionCandidateStatic -Condition ([string]$fullStereoParams.RequiredQcl041MatrixRunId -eq "$runId-qcl041-local-bind-gate") -Message "QCL041 run-id pin drifted"
    Assert-Qcl100PromotionCandidateStatic -Condition ([bool]$fullStereoParams.RunQcl041PreclearBeforeAirgapPreflight) -Message "strict QCL041 preclear is not requested"
    Assert-Qcl100PromotionCandidateStatic -Condition ([bool]$fullStereoParams.SkipWakePrep) -Message "promotion candidate must defer wake ownership to the external watchdog"
} finally {
    Remove-Qcl100PromotionCandidateTempDirectory -Path $dryRunDir
}

$stubIntegrationDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-promotion-candidate-stubs-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $stubIntegrationDir | Out-Null
    $routeStubPath = Join-Path $stubIntegrationDir "route-stub.ps1"
    $qcl041StubPath = Join-Path $stubIntegrationDir "qcl041-stub.ps1"
    $monitorStubPath = Join-Path $stubIntegrationDir "monitor-stub.ps1"

    @'
param([Parameter(ValueFromRemainingArguments=$true)][object[]]$Remaining)
$values = @{}
for ($index = 0; $index -lt $Remaining.Count; $index += 2) {
    $key = ([string]$Remaining[$index]).TrimStart("-").TrimEnd(":")
    $values[$key] = $Remaining[$index + 1]
}
$outDir = [string]$values.OutDir
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$policy = [ordered]@{
    schema = "rusty.quest.qcl100_route_clear_recovery.v1"
    run_id = [string]$values.RunId
    status = "monitor_completed"
    promotion_candidate_pre_route_clear = [bool]$values.PromotionCandidatePreRouteClear
    final_promotion_route_clear = $false
    crash_watch_policy = [ordered]@{
        promotion_candidate_parent_authorized = $true
        requires_human_review_before_media = $false
    }
}
$monitor = [ordered]@{ schema = "rusty.quest.qcl100_monitor_summary.v1"; status = "completed" }
$summary = [ordered]@{
    schema = "rusty.quest.qcl100_native_stereo_projection_run.v1"
    status = "preflight_only"
    preflight = [ordered]@{
        infrastructure_wifi_disconnected = $true
        p2p0_ipv4_cleared = $true
        candidate_wifi_direct_prelaunch_routes_clear = $true
    }
}
$policy | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outDir "qcl100-route-clear-recovery-wrapper.json") -Encoding UTF8
$monitor | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outDir "qcl100-monitor-summary.json") -Encoding UTF8
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outDir "native-stereo-projection-summary.json") -Encoding UTF8
'@ | Set-Content -LiteralPath $routeStubPath -Encoding UTF8

    @'
param([Parameter(ValueFromRemainingArguments=$true)][object[]]$Remaining)
$values = @{}
for ($index = 0; $index -lt $Remaining.Count; $index += 2) {
    $key = ([string]$Remaining[$index]).TrimStart("-").TrimEnd(":")
    $values[$key] = $Remaining[$index + 1]
}
$outDir = [string]$values.OutDir
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$streamBytes = [int64]$values.TcpTunnelStreamBytesPerDirection
$summary = [ordered]@{
    schema = "rusty.quest.qcl041_q2q_app_bound_socket_matrix_run.v1"
    run_id = [string]$values.RunId
    owner_serial = [string]$values.OwnerSerial
    client_serial = [string]$values.ClientSerial
    status = "pass"
    blocked_reason = ""
    qcl100_lower_gate_authority = "rusty_direct_p2p_socket_authority"
    qcl041_group_owner_label = "owner"
    owner_qcl041_role = "group_owner"
    client_qcl041_role = "client"
    promotion_allowed = $false
    same_group_duplex_claimed = $false
    preflight = [ordered]@{
        infrastructure_wifi_disconnected = $true
        p2p0_ipv4_cleared = $true
        candidate_wifi_direct_prelaunch_routes_clear = $true
    }
    matrix = [ordered]@{
        client_strict_local_p2p_app_transport_pass = $true
        client_p2p_interface_local_bind_udp_pass = $true
        client_p2p_interface_local_bind_tcp_pass = $true
        client_p2p_interface_local_bind_tcp_stream_pass = $true
        client_p2p_interface_local_bind_udp_receiver_observed_source_matches_client_p2p = $true
        client_p2p_interface_local_bind_socket_authority = "network_interface_local_p2p_address_bind"
        local_p2p_bind_tcp_stream_pass = $true
        local_p2p_bind_tcp_stream_sender_to_receiver_rx_bytes = $streamBytes
        local_p2p_bind_tcp_stream_receiver_to_sender_rx_bytes = $streamBytes
        local_p2p_bind_tcp_stream_sender_to_receiver_crc32_match = $true
        local_p2p_bind_tcp_stream_receiver_to_sender_crc32_match = $true
    }
}
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $outDir "summary.json") -Encoding UTF8
'@ | Set-Content -LiteralPath $qcl041StubPath -Encoding UTF8

    @'
param([Parameter(ValueFromRemainingArguments=$true)][object[]]$Remaining)
$values = @{}
for ($index = 0; $index -lt $Remaining.Count; $index += 2) {
    $key = ([string]$Remaining[$index]).TrimStart("-").TrimEnd(":")
    $values[$key] = $Remaining[$index + 1]
}
$outDir = [string]$values.OutDir
$runId = [string]$values.RunId
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$accepted = [bool]($runId -notlike "*blocked*")
$monitor = [ordered]@{
    schema = "rusty.quest.qcl100_monitor_summary.v1"
    run_id = $runId
    status = if ($accepted) { "completed" } else { "blocked_promotion_acceptance" }
    promotion_acceptance = [ordered]@{
        schema = "rusty.quest.qcl100_monitored_promotion_acceptance.v1"
        status = if ($accepted) {
            "promoted_same_group_full_stereo_duplex_with_rusty_direct_p2p_socket_authority"
        } else {
            "blocked_not_promoting"
        }
        accepted = $accepted
    }
    same_group_duplex_claimed = $accepted
    promotion_claimed = $accepted
}
$native = [ordered]@{
    schema = "rusty.quest.qcl100_native_stereo_projection_run.v1"
    status = "same_group_duplex_proven_with_rusty_direct_p2p_socket_authority"
}
$monitor | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $outDir "qcl100-monitor-summary.json") -Encoding UTF8
$native | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outDir "native-stereo-projection-summary.json") -Encoding UTF8
'@ | Set-Content -LiteralPath $monitorStubPath -Encoding UTF8

    $acceptedDir = Join-Path $stubIntegrationDir "accepted"
    $acceptedParams = @{
        RunId = "qcl100-promotion-candidate-stub-accepted"
        OutDir = $acceptedDir
        OwnerLeaseId = "owner-static-lease"
        ClientLeaseId = "client-static-lease"
        InfrastructureWifiSsid = "Static-Test-SSID"
        RouteClearRecoveryPath = $routeStubPath
        Qcl041MatrixPath = $qcl041StubPath
        MonitoredRunnerPath = $monitorStubPath
    }
    $acceptedOutput = & $candidatePath @acceptedParams 2>&1 | Out-String
    $acceptedExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    Assert-Qcl100PromotionCandidateStatic -Condition ($acceptedExitCode -eq 0) -Message "accepted stub sequence failed: $acceptedOutput"
    $acceptedPlan = Get-Content -Raw -LiteralPath (Join-Path $acceptedDir "qcl100-promotion-candidate-plan.json") | ConvertFrom-Json
    Assert-Qcl100PromotionCandidateStatic -Condition ([string]$acceptedPlan.status -eq "promoted") -Message "accepted stub sequence did not promote"
    Assert-Qcl100PromotionCandidateStatic -Condition ([bool]$acceptedPlan.pre_route_clear_acceptance.accepted) -Message "accepted stub pre-route phase failed"
    Assert-Qcl100PromotionCandidateStatic -Condition ([bool]$acceptedPlan.qcl041_gate_acceptance.accepted) -Message "accepted stub QCL041 phase failed"
    Assert-Qcl100PromotionCandidateStatic -Condition ([bool]$acceptedPlan.promotion_acceptance.accepted -and [bool]$acceptedPlan.promotion_claimed -and [bool]$acceptedPlan.same_group_duplex_claimed) -Message "accepted stub final monitor claims failed"

    $blockedDir = Join-Path $stubIntegrationDir "blocked"
    $blockedParams = $acceptedParams.Clone()
    $blockedParams.RunId = "qcl100-promotion-candidate-stub-blocked"
    $blockedParams.OutDir = $blockedDir
    $blockedFailed = $false
    try {
        & $candidatePath @blockedParams 2>&1 | Out-Null
    } catch {
        $blockedFailed = $true
    }
    Assert-Qcl100PromotionCandidateStatic -Condition $blockedFailed -Message "damaged final monitor result did not fail the orchestrator"
    $blockedPlan = Get-Content -Raw -LiteralPath (Join-Path $blockedDir "qcl100-promotion-candidate-plan.json") | ConvertFrom-Json
    Assert-Qcl100PromotionCandidateStatic -Condition ([string]$blockedPlan.status -eq "blocked") -Message "damaged final monitor plan status drifted"
    Assert-Qcl100PromotionCandidateStatic -Condition (-not [bool]$blockedPlan.promotion_claimed -and -not [bool]$blockedPlan.same_group_duplex_claimed) -Message "damaged final monitor claimed promotion"
} finally {
    Remove-Qcl100PromotionCandidateTempDirectory -Path $stubIntegrationDir
}

$missingSsidDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-promotion-candidate-no-ssid-" + [guid]::NewGuid().ToString("N"))
try {
    $missingSsidFailed = $false
    try {
        & $candidatePath -DryRun -OutDir $missingSsidDir 2>&1 | Out-Null
    } catch {
        $missingSsidFailed = $true
    }
    Assert-Qcl100PromotionCandidateStatic -Condition $missingSsidFailed -Message "candidate accepted a missing explicit infrastructure SSID"
    Assert-Qcl100PromotionCandidateStatic -Condition (-not (Test-Path -LiteralPath $missingSsidDir)) -Message "missing-SSID guard touched the output tree"
} finally {
    Remove-Qcl100PromotionCandidateTempDirectory -Path $missingSsidDir
}

$missingLeaseDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-qcl100-promotion-candidate-no-leases-" + [guid]::NewGuid().ToString("N"))
try {
    $missingLeaseFailed = $false
    try {
        & $candidatePath -InfrastructureWifiSsid "Static-Test-SSID" -OutDir $missingLeaseDir 2>&1 | Out-Null
    } catch {
        $missingLeaseFailed = $true
    }
    Assert-Qcl100PromotionCandidateStatic -Condition $missingLeaseFailed -Message "live candidate accepted missing device lease IDs"
    Assert-Qcl100PromotionCandidateStatic -Condition (-not (Test-Path -LiteralPath $missingLeaseDir)) -Message "missing-lease guard touched hardware/output setup"
} finally {
    Remove-Qcl100PromotionCandidateTempDirectory -Path $missingLeaseDir
}

Write-Output "QCL100 native stereo promotion candidate static checks passed."
