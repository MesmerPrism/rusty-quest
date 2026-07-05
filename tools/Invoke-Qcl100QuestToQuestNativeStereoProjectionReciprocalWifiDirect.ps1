param(
    [string]$OwnerSerial = "340YC10G7T0JBW",
    [string]$ClientSerial = "3487C10H3M017Q",
    [string]$OwnerLeaseId = "",
    [string]$ClientLeaseId = "",
    [string]$RunId = "",
    [string]$OutDir = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$Python = "python",
    [string]$HostessCtl = "S:\Work\repos\active\rusty-hostess\tools\hostessctl\hostessctl.py",
    [string]$Qcl041Apk = "S:\Work\repos\active\rusty-quest\target\qcl041-wifi-direct-harness-android\rusty-quest-qcl041-wifi-direct-harness.apk",
    [string]$BrokerApk = "S:\Work\repos\active\rusty-quest\target\manifold-broker-android\rusty-manifold-broker.apk",
    [string]$NativeRendererApk = "S:\Work\repos\active\rusty-quest\target\native-renderer-android\rusty-quest-native-renderer.apk",
    [string]$NativeRendererProfile = "S:\Work\repos\active\rusty-quest\fixtures\runtime-profiles\quest-native-renderer-broker-rmanvid1-stereo-camera.profile.json",
    [ValidateSet("stereo", "left-only", "right-only")]
    [string]$LaneMode = "stereo",
    [int]$ProjectionSeconds = 60,
    [string]$OwnerWifiDirectAddress = "192.168.49.1",
    [string]$ClientWifiDirectAddress = "192.168.49.46",
    [string]$Qcl041Q2qNetworkName = "DIRECT-rq-QCL100",
    [string]$Qcl041Q2qPassphrase = "RustyQcl100Pass",
    [string]$CameraIds = "left:50,right:51",
    [string]$MediaProfiles = "left:320x240@15:500000;right:320x240@15:500000",
    [int]$NativeRendererBrokerConnectTimeoutMs = 60000,
    [int]$LegTimeoutSeconds = 900,
    [switch]$SkipInstall,
    [switch]$SkipWakePrep,
    [switch]$SkipCleanup
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl100-q2q-native-stereo-projection-reciprocal-" + (Get-Date -Format "yyyyMMddTHHmmssZ").ToString()
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path "S:\Work\repos\active\rusty-quest\target" $RunId
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$singleRunScript = Join-Path $scriptRoot "Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1"
foreach ($path in @($singleRunScript, $HostessCtl, $Qcl041Apk, $BrokerApk, $NativeRendererApk, $NativeRendererProfile)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required artifact not found: $path"
    }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Write-Qcl100JsonFile {
    param(
        [Parameter(Mandatory=$true)]
        $Value,
        [Parameter(Mandatory=$true)]
        [string]$Path
    )
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $Value | ConvertTo-Json -Depth 100 | Set-Content -Encoding UTF8 -Path $Path
}

function Read-Qcl100JsonIfPresent {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        return Get-Content -Raw $Path | ConvertFrom-Json
    }
    return $null
}

function Invoke-Qcl100ReciprocalLeg {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Direction,
        [Parameter(Mandatory=$true)]
        [string]$Label,
        [Parameter(Mandatory=$true)]
        [bool]$InstallForLeg
    )
    $legRunId = "$RunId-$Label"
    $legOutDir = Join-Path $OutDir $Label
    New-Item -ItemType Directory -Force -Path $legOutDir | Out-Null
    $stdout = Join-Path $legOutDir "wrapper.stdout.txt"
    $stderr = Join-Path $legOutDir "wrapper.stderr.txt"
    $arguments = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $singleRunScript,
        "-OwnerSerial", $OwnerSerial,
        "-ClientSerial", $ClientSerial,
        "-RunId", $legRunId,
        "-OutDir", $legOutDir,
        "-Adb", $Adb,
        "-Python", $Python,
        "-HostessCtl", $HostessCtl,
        "-Qcl041Apk", $Qcl041Apk,
        "-BrokerApk", $BrokerApk,
        "-NativeRendererApk", $NativeRendererApk,
        "-NativeRendererProfile", $NativeRendererProfile,
        "-Direction", $Direction,
        "-LaneMode", $LaneMode,
        "-ProjectionSeconds", $ProjectionSeconds.ToString(),
        "-TransportOwner", "qcl041",
        "-Qcl082TransportProtocol", "udp",
        "-DisableQcl082AckPacing",
        "-OwnerWifiDirectAddress", $OwnerWifiDirectAddress,
        "-ClientWifiDirectAddress", $ClientWifiDirectAddress,
        "-Qcl041Q2qNetworkName", $Qcl041Q2qNetworkName,
        "-Qcl041Q2qPassphrase", $Qcl041Q2qPassphrase,
        "-CameraIds", $CameraIds,
        "-MediaProfiles", $MediaProfiles,
        "-NativeRendererBrokerConnectTimeoutMs", $NativeRendererBrokerConnectTimeoutMs.ToString()
    )
    if (-not [string]::IsNullOrWhiteSpace($OwnerLeaseId)) {
        $arguments += @("-OwnerLeaseId", $OwnerLeaseId)
    }
    if (-not [string]::IsNullOrWhiteSpace($ClientLeaseId)) {
        $arguments += @("-ClientLeaseId", $ClientLeaseId)
    }
    if (-not $InstallForLeg) {
        $arguments += "-SkipInstall"
    }
    if ($SkipWakePrep) {
        $arguments += "-SkipWakePrep"
    }
    if ($SkipCleanup) {
        $arguments += "-SkipCleanup"
    }

    $process = Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList $arguments `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru
    $legTimedOut = $false
    $legTimeoutMs = [Math]::Max(60, $LegTimeoutSeconds) * 1000
    if (-not $process.WaitForExit($legTimeoutMs)) {
        $legTimedOut = $true
        try {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            $process.WaitForExit(5000) | Out-Null
        } catch {
        }
    }

    $summaryPath = Join-Path $legOutDir "native-stereo-projection-summary.json"
    $summary = Read-Qcl100JsonIfPresent -Path $summaryPath
    return [ordered]@{
        label = $Label
        direction = $Direction
        run_id = $legRunId
        out_dir = $legOutDir
        summary_path = $summaryPath
        stdout_path = $stdout
        stderr_path = $stderr
        exit_code = if ($legTimedOut) { -2 } else { $process.ExitCode }
        timed_out = [bool]$legTimedOut
        timeout_seconds = $LegTimeoutSeconds
        summary_present = [bool]($null -ne $summary)
        passed = [bool]($null -ne $summary -and $summary.freshness_acceptance.passed)
        qcl041_group_owner_label = if ($null -ne $summary) { [string]$summary.qcl041_group_owner_label } else { "" }
        active_receiver_projection_ready = if ($null -ne $summary) { [bool]$summary.active_receiver_projection_ready } else { $false }
        freshness_acceptance = if ($null -ne $summary) { $summary.freshness_acceptance } else { $null }
        qcl082_deferred_receiver_targets = if ($null -ne $summary) { $summary.qcl082_deferred_receiver_targets } else { $null }
        evidence_dir = $legOutDir
    }
}

$ownerToClient = Invoke-Qcl100ReciprocalLeg `
    -Direction "owner-to-client" `
    -Label "owner-to-client" `
    -InstallForLeg ([bool](-not $SkipInstall))

$clientToOwner = Invoke-Qcl100ReciprocalLeg `
    -Direction "client-to-owner" `
    -Label "client-to-owner" `
    -InstallForLeg $false

$transportClaims = [ordered]@{
    same_group_duplex_claimed = $false
    reciprocal_role_recycled_go_to_client_udp = $true
    same_group_duplex_status = "blocked_until_same_epoch_bidirectional_media_and_renderer_freshness_pass"
    acceptance_scope = "two_independent_role_recycled_go_to_client_udp_legs"
}

$transportCapabilityMatrix = [ordered]@{
    same_group_go_to_client_udp = [ordered]@{
        status = "pass"
        evidence = "current_receiver_target_go_to_client_udp_one_way_runs"
    }
    same_group_client_to_go_udp = [ordered]@{
        status = "socket_level_app_bound_pass_media_not_yet_proven"
        evidence = "qcl041_app_bound_socket_matrix_receiver_observed_client_to_group_owner_udp_but_qcl100_same_epoch_media_stream_render_not_yet_passing"
    }
    same_group_client_to_go_udp_unbound_or_default = [ordered]@{
        status = "fail"
        evidence = "client_to_group_owner_udp_peer_proof_zero_receiver_packets"
    }
    same_group_client_to_go_udp_app_bound_socket = [ordered]@{
        status = "pass_socket_level"
        evidence = "qcl041_app_bound_socket_matrix_network_bound_datagram_socket_receiver_observed_packets"
    }
    same_group_go_to_client_tcp = [ordered]@{
        status = "fail_or_not_clean"
        evidence = "mixed_reverse_tcp_attempts_timed_out_connecting_to_group_client_listener"
    }
    same_group_client_to_go_tcp = [ordered]@{
        status = "mixed_not_media_proven"
        evidence = "qcl041_control_tcp_passed_but_qcl100_media_tcp_timed_out_and_network_bound_tcp_socket_failed_eperm"
    }
    same_group_simultaneous_duplex = [ordered]@{
        status = "blocked"
        evidence = "no_same_epoch_run_has_both_headsets_receiving_advancing_media"
    }
    reciprocal_role_recycled_go_to_client_udp_both_roles = [ordered]@{
        status = "pass"
        evidence = "owner_to_client_and_client_to_owner_role_recycled_subruns_passed"
    }
}

$passed = [bool]($ownerToClient.passed -and $clientToOwner.passed)
$summary = [ordered]@{
    schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_reciprocal_run.v1"
    run_id = $RunId
    mode = "reciprocal_role_recycled_one_way"
    same_group_duplex_claimed = [bool]$transportClaims.same_group_duplex_claimed
    same_group_duplex_status = $transportClaims.same_group_duplex_status
    acceptance_model = "both_role_orders_must_pass_as_independent_go_to_client_udp_native_stereo_projection_runs"
    transport_claims = $transportClaims
    transport_capability_matrix = $transportCapabilityMatrix
    owner_serial = $OwnerSerial
    client_serial = $ClientSerial
    lane_mode = $LaneMode
    projection_seconds_per_leg = $ProjectionSeconds
    camera_ids = $CameraIds
    media_profiles = $MediaProfiles
    qcl082_transport_protocol = "udp"
    qcl082_ack_pacing_enabled = $false
    skip_install_requested = [bool]$SkipInstall
    skip_wake_prep = [bool]$SkipWakePrep
    skip_cleanup = [bool]$SkipCleanup
    owner_to_client = $ownerToClient
    client_to_owner = $clientToOwner
    freshness_acceptance = [ordered]@{
        required = "owner_to_client_and_client_to_owner_role_recycled_one_way_qcl100_native_stereo_projection_pass"
        owner_to_client_passed = [bool]$ownerToClient.passed
        client_to_owner_passed = [bool]$clientToOwner.passed
        passed = $passed
    }
    cleanup_policy = [ordered]@{
        final_force_stop_cleanup_skipped = [bool]$SkipCleanup
        reason = if ($SkipCleanup) { "skip_cleanup_preserve_final_xr_focus" } else { "single_leg_runner_cleanup_policy_applied_per_leg" }
    }
    evidence_dir = $OutDir
}

$summaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
Write-Qcl100JsonFile -Value $summary -Path $summaryPath
Get-Content -Raw $summaryPath

if (-not $passed) {
    exit 2
}
