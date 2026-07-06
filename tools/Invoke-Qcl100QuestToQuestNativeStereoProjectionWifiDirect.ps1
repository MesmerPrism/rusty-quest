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
    [ValidateSet("duplex", "owner-to-client", "client-to-owner")]
    [string]$Direction = "duplex",
    [ValidateSet("", "owner", "client")]
    [string]$Qcl041GroupOwnerLabelOverride = "",
    [ValidateSet("stereo", "left-only", "right-only")]
    [string]$LaneMode = "stereo",
    [ValidateSet("qcl041", "broker")]
    [string]$TransportOwner = "qcl041",
    [int]$ProjectionSeconds = 30,
    [int]$OwnerBrokerLocalPort = 18765,
    [int]$ClientBrokerLocalPort = 18766,
    [string]$OwnerWifiDirectAddress = "192.168.49.1",
    [string]$ClientWifiDirectAddress = "192.168.49.46",
    [string]$Qcl041Q2qNetworkName = "DIRECT-rq-QCL100",
    [string]$Qcl041Q2qPassphrase = "RustyQcl100Pass",
    [int]$LeftReceiverPort = 8979,
    [int]$RightReceiverPort = 8980,
    [int]$LeftTransportPort = 9079,
    [int]$RightTransportPort = 9080,
    [int]$LeftTransportProxyTargetPort = 9179,
    [int]$RightTransportProxyTargetPort = 9180,
    [int]$LeftSourcePort = 8879,
    [int]$RightSourcePort = 8880,
    [string]$CameraIds = "left:50,right:51",
    [string]$MediaProfiles = "left:320x240@15:500000;right:320x240@15:500000",
    [int]$RelayTimeoutSeconds = 95,
    [int]$RelayMaxBytes = 128000000,
    [int]$Qcl082RelayStartDelayMs = 5000,
    [int]$HoldAfterSocketMs = 90000,
    [bool]$Qcl082AckPacingEnabled = $true,
    [switch]$DisableQcl082AckPacing,
    [int]$Qcl082AckChunkBytes = 8192,
    [int]$Qcl082AckTimeoutMs = 1500,
    [int]$Qcl082AckSoftTimeoutLimit = 0,
    [int]$Qcl082ControlTcpMediaStreamBytesPerDirection = 0,
    [int]$Qcl082ControlTcpMediaStreamChunkBytes = 16384,
    [int]$Qcl082RelayWriteStallTimeoutMs = 3000,
    [int]$Qcl082RelayReceiverProgressTimeoutMs = 3000,
    [int]$Qcl082RelayPortRotationCount = 8,
    [ValidateSet("udp", "tcp", "reverse-tcp", "control-tcp", "mixed", "mixed-client-tcp")]
    [string]$Qcl082TransportProtocol = "udp",
    [bool]$RequireQcl082UdpReceiveProxyNetworkBinding = $true,
    [int]$Qcl082ReceiveProxyPeerIdleTimeoutMs = 3000,
    [int]$Qcl041ArtifactWaitSeconds = 180,
    [int]$NativeRendererBrokerConnectTimeoutMs = 60000,
    [int]$NoMediaLaunchSeconds = 8,
    [double]$MinFreshFrameSpanSeconds = 25.0,
    [int]$MinFreshFrameLines = 5,
    [switch]$RequireInfrastructureWifiDisconnected,
    [switch]$RequireP2p0Ipv4Cleared,
    [switch]$RequireCandidateWifiDirectRoutesClear,
    [switch]$RunQcl041PreclearBeforeAirgapPreflight,
    [switch]$RequireQcl041MatrixGatePass,
    [string]$RequiredQcl041MatrixSummaryPath = "",
    [string]$RequiredQcl041MatrixRunId = "",
    [int]$MaxQcl041MatrixGateAgeSeconds = 1800,
    [switch]$PreflightOnly,
    [switch]$SkipInstall,
    [switch]$SkipWakePrep,
    [switch]$AllowWakePrepMutation,
    [switch]$SkipCleanup,
    [switch]$XrLaunchReadinessOnly,
    [switch]$LowerGatePlanOnly,
    [switch]$NoMediaLaunchOnly,
    [switch]$ValidateLowerGateEvidenceOnly,
    [string]$LowerGatePlanSummaryPath = "",
    [string]$RouteClearSummaryPath = "",
    [string]$Qcl041ControlTcpSummaryPath = "",
    [string]$XrReadinessSummaryPath = "",
    [string]$NoMediaLaunchSummaryPath = "",
    [switch]$AllowLowerGateEvidenceSkippedCleanup,
    [switch]$FreshnessSelfTest
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl100-q2q-native-stereo-projection-" + (Get-Date -Format "yyyyMMddTHHmmssZ").ToString()
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path "S:\Work\repos\active\rusty-quest\target" $RunId
}
if ($Qcl041Q2qNetworkName -notmatch '^DIRECT-[A-Za-z0-9]{2}.*$' -or $Qcl041Q2qNetworkName.Length -gt 32) {
    throw "Qcl041Q2qNetworkName must follow the Wi-Fi Direct DIRECT-xy naming rule and fit in 32 characters."
}
if ($Qcl041Q2qPassphrase -notmatch '^[\x20-\x7e]{8,63}$') {
    throw "Qcl041Q2qPassphrase must be 8-63 printable ASCII characters."
}

$MediaDir = Join-Path $OutDir "media"
New-Item -ItemType Directory -Force -Path $MediaDir | Out-Null

$NativeRendererPackage = "io.github.mesmerprism.rustyquest.native_renderer"
$NativeRendererActivity = "io.github.mesmerprism.rustyquest.native_renderer/android.app.NativeActivity"
$BrokerPackage = "io.github.mesmerprism.rustymanifold.broker"
$Qcl041Package = "io.github.mesmerprism.rustyquest.qcl041"

$Qcl100ToolRoot = $PSScriptRoot
$helperRoot = Join-Path $Qcl100ToolRoot "qcl100_native_projection"
. (Join-Path $helperRoot "Common.ps1")
. (Join-Path $helperRoot "ParityBlockers.ps1")
. (Join-Path $helperRoot "BridgeCommands.ps1")
. (Join-Path $helperRoot "Qcl041Relay.ps1")
. (Join-Path $helperRoot "Readiness.ps1")
. (Join-Path $helperRoot "Freshness.ps1")
. (Join-Path $helperRoot "RuntimeSummary.ps1")
. (Join-Path $helperRoot "Qcl041MatrixGate.ps1")
. (Join-Path $helperRoot "LowerGatePlan.ps1")
. (Join-Path $helperRoot "LowerGateEvidence.ps1")


































if ($FreshnessSelfTest) {
    Invoke-Qcl100FreshnessSelfTest
    Invoke-Qcl100ParityBlockerSelfTest -OutputDirectory $OutDir | Out-Null
    Invoke-Qcl100RuntimeSummarySelfTest
    Invoke-Qcl100Qcl041ArtifactFreshnessWaitSelfTest -OutputDirectory $OutDir
    Invoke-Qcl100Qcl041MatrixGateSelfTest -OutputDirectory $OutDir
    Invoke-Qcl100LowerGatePlanSelfTest -OutputDirectory $OutDir | Out-Null
    Invoke-Qcl100LowerGateEvidenceSelfTest -OutputDirectory $OutDir | Out-Null
    return
}

$wakePrepPolicy = if ($SkipWakePrep) {
    "external_keep_awake_managed"
} elseif ($AllowWakePrepMutation) {
    "runner_wake_prep_mutation_explicitly_allowed"
} else {
    "blocked_wake_prep_mutation_without_explicit_allow"
}
if (-not $LowerGatePlanOnly -and -not $ValidateLowerGateEvidenceOnly -and -not $PreflightOnly -and -not $XrLaunchReadinessOnly -and -not $SkipWakePrep -and -not $AllowWakePrepMutation) {
    throw "QCL100 full media runs would mutate Quest wake state through Prepare-QuestForXrFocus. Pass -SkipWakePrep when an external keep-awake/watchdog thread owns headset state, or pass -AllowWakePrepMutation only when the operator explicitly wants this runner to apply wake prep."
}

$ownerSends = [bool]($Direction -eq "duplex" -or $Direction -eq "owner-to-client")
$clientSends = [bool]($Direction -eq "duplex" -or $Direction -eq "client-to-owner")
$ownerReceives = [bool]($Direction -eq "duplex" -or $Direction -eq "client-to-owner")
$clientReceives = [bool]($Direction -eq "duplex" -or $Direction -eq "owner-to-client")
$ownerRelayRequired = [bool]($ownerSends -and $TransportOwner -eq "qcl041")
$clientRelayRequired = [bool]($clientSends -and $TransportOwner -eq "qcl041")
$ownerReceiveProxyRequired = [bool]($ownerReceives -and $TransportOwner -eq "qcl041")
$clientReceiveProxyRequired = [bool]($clientReceives -and $TransportOwner -eq "qcl041")
$ownerRendererRequired = [bool]$ownerReceives
$clientRendererRequired = [bool]$clientReceives
$effectiveQcl082AckPacingEnabled = [bool]($Qcl082AckPacingEnabled -and -not $DisableQcl082AckPacing)
$qcl041MatrixRunIdPinRequiresGate = -not [string]::IsNullOrWhiteSpace($RequiredQcl041MatrixRunId)
$effectiveRequireQcl041MatrixGatePass = [bool]($RequireQcl041MatrixGatePass -or $qcl041MatrixRunIdPinRequiresGate)
$qcl041GroupOwnerLabel = if ([string]::IsNullOrWhiteSpace($Qcl041GroupOwnerLabelOverride)) {
    if ($Direction -eq "client-to-owner") { "client" } else { "owner" }
} else {
    $Qcl041GroupOwnerLabelOverride
}
$ownerQcl041Role = if ($qcl041GroupOwnerLabel -eq "owner") { "group_owner" } else { "client" }
$clientQcl041Role = if ($qcl041GroupOwnerLabel -eq "client") { "group_owner" } else { "client" }
$ownerQcl041RelayReceiverHost = if ($ownerQcl041Role -eq "group_owner") { $ClientWifiDirectAddress } else { $OwnerWifiDirectAddress }
$clientQcl041RelayReceiverHost = if ($clientQcl041Role -eq "group_owner") { $ClientWifiDirectAddress } else { $OwnerWifiDirectAddress }
$qcl082DeferredReceiverTargetFile = "qcl041/qcl082-deferred-receiver-target.json"
$qcl082DeferredReceiverTargets = [ordered]@{}
$ownerDeferredReceiverTargetRequired = [bool]($TransportOwner -eq "qcl041" -and $ownerRelayRequired -and $clientReceiveProxyRequired)
$clientDeferredReceiverTargetRequired = [bool]($TransportOwner -eq "qcl041" -and $clientRelayRequired -and $ownerReceiveProxyRequired)
$ownerDeferredReceiverTargetFile = if ($ownerDeferredReceiverTargetRequired) { $qcl082DeferredReceiverTargetFile } else { "" }
$clientDeferredReceiverTargetFile = if ($clientDeferredReceiverTargetRequired) { $qcl082DeferredReceiverTargetFile } else { "" }
$ownerDeferredReceiverTargetWaitMs = if ($ownerDeferredReceiverTargetRequired) { 90000 } else { 0 }
$clientDeferredReceiverTargetWaitMs = if ($clientDeferredReceiverTargetRequired) { 90000 } else { 0 }
$leftLaneActive = [bool]($LaneMode -ne "right-only")
$rightLaneActive = [bool]($LaneMode -ne "left-only")
$activeLaneCount = @(@($leftLaneActive, $rightLaneActive) | Where-Object { $_ }).Count
$qcl100AirgapPreflight = $null
$qcl041PreflightPreclear = [ordered]@{
    schema = "rusty.quest.qcl100_qcl041_preflight_preclear.v1"
    requested = [bool]$RunQcl041PreclearBeforeAirgapPreflight
    performed = $false
    operator_approval_required = $true
    device_state_mutation = "starts the installed QCL041 foreground service with q2q_preclear_only=true, calls WifiP2pManager.removeGroup, then force-stops the QCL041 package"
    before_preflight_artifact = ""
    after_preflight_artifact = ""
    before_p2p0_ipv4_cleared = $null
    after_p2p0_ipv4_cleared = $null
    before_candidate_wifi_direct_prelaunch_routes_clear = $null
    after_candidate_wifi_direct_prelaunch_routes_clear = $null
    owner_preclear_receipt = $null
    client_preclear_receipt = $null
}
$qcl041MatrixGate = $null
$qcl041MatrixGatePath = ""
$qcl041MatrixGateEvaluated = $false
$qcl041MatrixGatePassed = $false
$qcl041MatrixGatePassesRequirement = [bool](-not $effectiveRequireQcl041MatrixGatePass)
$qcl041MatrixGateBlockedReason = ""
$qcl041MatrixGateRunId = ""
$qcl041MatrixGateTransportProtocol = $Qcl082TransportProtocol
$qcl041MatrixGateRequiredTopology = ""

if ($LowerGatePlanOnly) {
    $planPath = Join-Path $OutDir "qcl100-lower-gate-plan.json"
    $summaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
    $lowerGatePlan = New-Qcl100LowerGatePlan `
        -RunId $RunId `
        -OutDir $OutDir `
        -OwnerSerial $OwnerSerial `
        -ClientSerial $ClientSerial `
        -OwnerWifiDirectAddress $OwnerWifiDirectAddress `
        -ClientWifiDirectAddress $ClientWifiDirectAddress `
        -Qcl041Q2qNetworkName $Qcl041Q2qNetworkName `
        -Qcl041Q2qPassphrase $Qcl041Q2qPassphrase `
        -Direction $Direction `
        -LaneMode $LaneMode `
        -Qcl082TransportProtocol $Qcl082TransportProtocol `
        -ProjectionSeconds $ProjectionSeconds `
        -NoMediaLaunchSeconds $NoMediaLaunchSeconds `
        -Qcl082ControlTcpMediaStreamBytesPerDirection $Qcl082ControlTcpMediaStreamBytesPerDirection `
        -RequiredQcl041MatrixSummaryPath $RequiredQcl041MatrixSummaryPath `
        -RequiredQcl041MatrixRunId $RequiredQcl041MatrixRunId `
        -MaxQcl041MatrixGateAgeSeconds $MaxQcl041MatrixGateAgeSeconds `
        -Qcl100ScriptPath (Join-Path $Qcl100ToolRoot "Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1") `
        -Qcl041MatrixScriptPath (Join-Path $Qcl100ToolRoot "Invoke-Qcl041QuestToQuestAppBoundSocketMatrix.ps1")
    Write-JsonFile -Value $lowerGatePlan -Path $planPath
    $planSummary = [ordered]@{
        schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
        run_id = $RunId
        status = "lower_gate_plan_only"
        mode = "lower_gate_plan_only"
        non_live_artifact = $true
        launched = $false
        device_mutation_performed = $false
        promotion_allowed = $false
        same_group_duplex_claimed = $false
        lower_gate_plan_artifact = $planPath
        lower_gate_plan = $lowerGatePlan
        freshness_acceptance = [ordered]@{
            required = "QCL100 lower-gate plan only; no device state, media, broker, or native renderer launch occurred"
            passed = $false
            blocked_reason = "lower_gate_plan_only"
        }
        evidence_dir = $OutDir
    }
    Write-JsonFile -Value $planSummary -Path $summaryPath
    Get-Content -Raw $summaryPath
    exit 0
}

if ($ValidateLowerGateEvidenceOnly) {
    $evidencePath = Join-Path $OutDir "qcl100-lower-gate-evidence.json"
    $summaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
    $lowerGateEvidence = Get-Qcl100LowerGateEvidence `
        -PlanSummaryPath $LowerGatePlanSummaryPath `
        -RouteClearSummaryPath $RouteClearSummaryPath `
        -Qcl041ControlTcpSummaryPath $Qcl041ControlTcpSummaryPath `
        -XrReadinessSummaryPath $XrReadinessSummaryPath `
        -NoMediaLaunchSummaryPath $NoMediaLaunchSummaryPath `
        -AllowSkippedCleanup:$AllowLowerGateEvidenceSkippedCleanup
    Write-JsonFile -Value $lowerGateEvidence -Path $evidencePath
    $evidenceSummary = [ordered]@{
        schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
        run_id = $RunId
        status = if ([bool]$lowerGateEvidence.passed) { "lower_gate_evidence_validated" } else { "blocked_lower_gate_evidence" }
        mode = "validate_lower_gate_evidence_only"
        non_live_artifact = $true
        launched = $false
        device_mutation_performed = $false
        promotion_allowed = $false
        same_group_duplex_claimed = $false
        lower_gate_evidence_artifact = $evidencePath
        lower_gate_evidence = $lowerGateEvidence
        freshness_acceptance = [ordered]@{
            required = "lower-gate evidence validation only; no media freshness or promotion claim is made"
            passed = $false
            blocked_reason = "lower_gate_evidence_only"
        }
        evidence_dir = $OutDir
    }
    Write-JsonFile -Value $evidenceSummary -Path $summaryPath
    Get-Content -Raw $summaryPath
    if (-not [bool]$lowerGateEvidence.passed) {
        exit 2
    }
    exit 0
}

if ($RequireInfrastructureWifiDisconnected -or $RequireP2p0Ipv4Cleared -or $RequireCandidateWifiDirectRoutesClear -or $PreflightOnly) {
    if ($RunQcl041PreclearBeforeAirgapPreflight) {
        $beforePreclearPath = Join-Path $OutDir "airgap-preflight-before-qcl041-preclear.json"
        $beforePreclear = New-Qcl100AirgapPreflight `
            -OwnerSerial $OwnerSerial `
            -ClientSerial $ClientSerial `
            -OwnerWifiDirectAddress $OwnerWifiDirectAddress `
            -ClientWifiDirectAddress $ClientWifiDirectAddress `
            -MediaDir $MediaDir `
            -PathPrefix "before-qcl041-preclear"
        Write-JsonFile -Value $beforePreclear -Path $beforePreclearPath
        $qcl041PreflightPreclear.before_preflight_artifact = $beforePreclearPath
        $qcl041PreflightPreclear.before_p2p0_ipv4_cleared = [bool]$beforePreclear.p2p0_ipv4_cleared
        $qcl041PreflightPreclear.before_candidate_wifi_direct_prelaunch_routes_clear = [bool]$beforePreclear.candidate_wifi_direct_prelaunch_routes_clear
        $qcl041PreflightPreclear.owner_preclear_receipt = Invoke-Qcl041PreclearOnly -Serial $OwnerSerial -LeaseId $OwnerLeaseId -Label "owner-preflight"
        $qcl041PreflightPreclear.client_preclear_receipt = Invoke-Qcl041PreclearOnly -Serial $ClientSerial -LeaseId $ClientLeaseId -Label "client-preflight"
        $qcl041PreflightPreclear.performed = $true
    }
    $qcl100AirgapPreflight = New-Qcl100AirgapPreflight `
        -OwnerSerial $OwnerSerial `
        -ClientSerial $ClientSerial `
        -OwnerWifiDirectAddress $OwnerWifiDirectAddress `
        -ClientWifiDirectAddress $ClientWifiDirectAddress `
        -MediaDir $MediaDir
    $airgapPreflightPath = Join-Path $OutDir "airgap-preflight.json"
    Write-JsonFile -Value $qcl100AirgapPreflight -Path $airgapPreflightPath
    if ($RunQcl041PreclearBeforeAirgapPreflight) {
        $qcl041PreflightPreclear.after_preflight_artifact = $airgapPreflightPath
        $qcl041PreflightPreclear.after_p2p0_ipv4_cleared = [bool]$qcl100AirgapPreflight.p2p0_ipv4_cleared
        $qcl041PreflightPreclear.after_candidate_wifi_direct_prelaunch_routes_clear = [bool]$qcl100AirgapPreflight.candidate_wifi_direct_prelaunch_routes_clear
    }

    if ($RequireInfrastructureWifiDisconnected -and -not [bool]$qcl100AirgapPreflight.infrastructure_wifi_disconnected) {
        $blockedSummaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
        $blockedSummary = [ordered]@{
            schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
            run_id = $RunId
            status = "blocked_preflight"
            blocked_stage = "infrastructure_wifi_airgap_preflight"
            blocked_reason = "infrastructure_wifi_connected"
            require_infrastructure_wifi_disconnected = $true
            require_p2p0_ipv4_cleared = [bool]$RequireP2p0Ipv4Cleared
            require_candidate_wifi_direct_routes_clear = [bool]$RequireCandidateWifiDirectRoutesClear
            preflight = $qcl100AirgapPreflight
            preflight_artifact = $airgapPreflightPath
            qcl041_preflight_preclear = $qcl041PreflightPreclear
            launched = $false
            owner_serial = $OwnerSerial
            client_serial = $ClientSerial
            direction = $Direction
            lane_mode = $LaneMode
            transport_owner = $TransportOwner
            owner_sends = $ownerSends
            client_sends = $clientSends
            owner_receives = $ownerReceives
            client_receives = $clientReceives
            topology = [ordered]@{
                transport = "quest_to_quest_wifi_direct"
                transport_owner = $TransportOwner
                qcl041_group_owner_label = $qcl041GroupOwnerLabel
                owner_qcl041_role = $ownerQcl041Role
                client_qcl041_role = $clientQcl041Role
            }
            transport_claims = [ordered]@{
                same_group_duplex_claimed = $false
                same_group_simultaneous_duplex = $false
                status = "blocked_preflight"
                blocked_until_same_epoch_bidirectional_media_and_renderer_freshness_pass = $true
            }
            same_group_duplex_claimed = $false
            freshness_acceptance = [ordered]@{
                required = "airgapped same-group QCL100 media attempt must start with both Quest headsets disconnected from infrastructure Wi-Fi, then prove receiver-observed QCL082 bytes and native renderer freshness"
                direction = $Direction
                lane_mode = $LaneMode
                owner_camera_source_required = [bool]$ownerSends
                client_camera_source_required = [bool]$clientSends
                owner_relay_required = [bool]$ownerRelayRequired
                client_relay_required = [bool]$clientRelayRequired
                owner_receive_proxy_required = [bool]$ownerReceiveProxyRequired
                client_receive_proxy_required = [bool]$clientReceiveProxyRequired
                owner_stream_required = [bool]$ownerRendererRequired
                client_stream_required = [bool]$clientRendererRequired
                blocked_reason = "infrastructure_wifi_connected"
                passed = $false
            }
            cleanup_policy = [ordered]@{
                final_force_stop_cleanup_skipped = $true
                reason = "blocked_preflight_no_launch"
                force_stop_packages = @()
            }
            evidence_dir = $OutDir
        }
        $preflightBlockers = New-Qcl100PreflightParityBlockers `
            -AirgapPreflight $qcl100AirgapPreflight `
            -RequireInfrastructureWifiDisconnected ([bool]$RequireInfrastructureWifiDisconnected) `
            -RequireP2p0Ipv4Cleared ([bool]$RequireP2p0Ipv4Cleared) `
            -RequireCandidateWifiDirectRoutesClear ([bool]$RequireCandidateWifiDirectRoutesClear)
        Set-Qcl100ParityBlockers -FreshnessAcceptance $blockedSummary.freshness_acceptance -Blockers $preflightBlockers
        Write-JsonFile -Value $blockedSummary -Path $blockedSummaryPath
        Get-Content -Raw $blockedSummaryPath
        exit 2
    }

    if ($RequireP2p0Ipv4Cleared -and -not [bool]$qcl100AirgapPreflight.p2p0_ipv4_cleared) {
        $blockedSummaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
        $blockedSummary = [ordered]@{
            schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
            run_id = $RunId
            status = "blocked_preflight"
            blocked_stage = "wifi_direct_p2p0_ipv4_preflight"
            blocked_reason = "p2p0_ipv4_present"
            require_infrastructure_wifi_disconnected = [bool]$RequireInfrastructureWifiDisconnected
            require_p2p0_ipv4_cleared = $true
            require_candidate_wifi_direct_routes_clear = [bool]$RequireCandidateWifiDirectRoutesClear
            preflight = $qcl100AirgapPreflight
            preflight_artifact = $airgapPreflightPath
            qcl041_preflight_preclear = $qcl041PreflightPreclear
            launched = $false
            owner_serial = $OwnerSerial
            client_serial = $ClientSerial
            direction = $Direction
            lane_mode = $LaneMode
            transport_owner = $TransportOwner
            owner_sends = $ownerSends
            client_sends = $clientSends
            owner_receives = $ownerReceives
            client_receives = $clientReceives
            topology = [ordered]@{
                transport = "quest_to_quest_wifi_direct"
                transport_owner = $TransportOwner
                qcl041_group_owner_label = $qcl041GroupOwnerLabel
                owner_qcl041_role = $ownerQcl041Role
                client_qcl041_role = $clientQcl041Role
            }
            transport_claims = [ordered]@{
                same_group_duplex_claimed = $false
                same_group_simultaneous_duplex = $false
                status = "blocked_preflight"
                blocked_until_same_epoch_bidirectional_media_and_renderer_freshness_pass = $true
            }
            same_group_duplex_claimed = $false
            freshness_acceptance = [ordered]@{
                required = "same-group QCL100 media attempt must start with no stale p2p0 IPv4 from an earlier Wi-Fi Direct epoch, then prove receiver-observed QCL082 bytes and native renderer freshness"
                direction = $Direction
                lane_mode = $LaneMode
                owner_camera_source_required = [bool]$ownerSends
                client_camera_source_required = [bool]$clientSends
                owner_relay_required = [bool]$ownerRelayRequired
                client_relay_required = [bool]$clientRelayRequired
                owner_receive_proxy_required = [bool]$ownerReceiveProxyRequired
                client_receive_proxy_required = [bool]$clientReceiveProxyRequired
                owner_stream_required = [bool]$ownerRendererRequired
                client_stream_required = [bool]$clientRendererRequired
                blocked_reason = "p2p0_ipv4_present"
                passed = $false
            }
            cleanup_policy = [ordered]@{
                final_force_stop_cleanup_skipped = $true
                reason = "blocked_preflight_no_launch"
                force_stop_packages = @()
            }
            evidence_dir = $OutDir
        }
        $preflightBlockers = New-Qcl100PreflightParityBlockers `
            -AirgapPreflight $qcl100AirgapPreflight `
            -RequireInfrastructureWifiDisconnected ([bool]$RequireInfrastructureWifiDisconnected) `
            -RequireP2p0Ipv4Cleared ([bool]$RequireP2p0Ipv4Cleared) `
            -RequireCandidateWifiDirectRoutesClear ([bool]$RequireCandidateWifiDirectRoutesClear)
        Set-Qcl100ParityBlockers -FreshnessAcceptance $blockedSummary.freshness_acceptance -Blockers $preflightBlockers
        Write-JsonFile -Value $blockedSummary -Path $blockedSummaryPath
        Get-Content -Raw $blockedSummaryPath
        exit 2
    }

    if ($RequireCandidateWifiDirectRoutesClear -and -not [bool]$qcl100AirgapPreflight.candidate_wifi_direct_prelaunch_routes_clear) {
        $blockedSummaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
        $blockedSummary = [ordered]@{
            schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
            run_id = $RunId
            status = "blocked_preflight"
            blocked_stage = "wifi_direct_candidate_route_preflight"
            blocked_reason = "candidate_wifi_direct_routes_not_clear"
            require_infrastructure_wifi_disconnected = [bool]$RequireInfrastructureWifiDisconnected
            require_p2p0_ipv4_cleared = [bool]$RequireP2p0Ipv4Cleared
            require_candidate_wifi_direct_routes_clear = $true
            preflight = $qcl100AirgapPreflight
            preflight_artifact = $airgapPreflightPath
            qcl041_preflight_preclear = $qcl041PreflightPreclear
            launched = $false
            owner_serial = $OwnerSerial
            client_serial = $ClientSerial
            direction = $Direction
            lane_mode = $LaneMode
            transport_owner = $TransportOwner
            owner_sends = $ownerSends
            client_sends = $clientSends
            owner_receives = $ownerReceives
            client_receives = $clientReceives
            topology = [ordered]@{
                transport = "quest_to_quest_wifi_direct"
                transport_owner = $TransportOwner
                qcl041_group_owner_label = $qcl041GroupOwnerLabel
                owner_qcl041_role = $ownerQcl041Role
                client_qcl041_role = $clientQcl041Role
            }
            transport_claims = [ordered]@{
                same_group_duplex_claimed = $false
                same_group_simultaneous_duplex = $false
                status = "blocked_preflight"
                blocked_until_same_epoch_bidirectional_media_and_renderer_freshness_pass = $true
            }
            same_group_duplex_claimed = $false
            freshness_acceptance = [ordered]@{
                required = "same-group QCL100 media attempt must start with no stale candidate Wi-Fi Direct routes from an earlier epoch, then prove receiver-observed QCL082 bytes and native renderer freshness"
                direction = $Direction
                lane_mode = $LaneMode
                owner_camera_source_required = [bool]$ownerSends
                client_camera_source_required = [bool]$clientSends
                owner_relay_required = [bool]$ownerRelayRequired
                client_relay_required = [bool]$clientRelayRequired
                owner_receive_proxy_required = [bool]$ownerReceiveProxyRequired
                client_receive_proxy_required = [bool]$clientReceiveProxyRequired
                owner_stream_required = [bool]$ownerRendererRequired
                client_stream_required = [bool]$clientRendererRequired
                blocked_reason = "candidate_wifi_direct_routes_not_clear"
                passed = $false
            }
            cleanup_policy = [ordered]@{
                final_force_stop_cleanup_skipped = $true
                reason = "blocked_preflight_no_launch"
                force_stop_packages = @()
            }
            evidence_dir = $OutDir
        }
        $preflightBlockers = New-Qcl100PreflightParityBlockers `
            -AirgapPreflight $qcl100AirgapPreflight `
            -RequireInfrastructureWifiDisconnected ([bool]$RequireInfrastructureWifiDisconnected) `
            -RequireP2p0Ipv4Cleared ([bool]$RequireP2p0Ipv4Cleared) `
            -RequireCandidateWifiDirectRoutesClear ([bool]$RequireCandidateWifiDirectRoutesClear)
        Set-Qcl100ParityBlockers -FreshnessAcceptance $blockedSummary.freshness_acceptance -Blockers $preflightBlockers
        Write-JsonFile -Value $blockedSummary -Path $blockedSummaryPath
        Get-Content -Raw $blockedSummaryPath
        exit 2
    }

}

if ($effectiveRequireQcl041MatrixGatePass -or -not [string]::IsNullOrWhiteSpace($RequiredQcl041MatrixSummaryPath)) {
    $qcl041MatrixGate = Get-Qcl100Qcl041MatrixGateEvidence `
        -Path $RequiredQcl041MatrixSummaryPath `
        -ExpectedOwnerSerial $OwnerSerial `
        -ExpectedClientSerial $ClientSerial `
        -ExpectedRunId $RequiredQcl041MatrixRunId `
        -Qcl082TransportProtocol $Qcl082TransportProtocol `
        -MaxAgeSeconds $MaxQcl041MatrixGateAgeSeconds `
        -RequireFresh:$effectiveRequireQcl041MatrixGatePass
    $qcl041MatrixGatePath = Join-Path $OutDir "qcl041-matrix-gate.json"
    Write-JsonFile -Value $qcl041MatrixGate -Path $qcl041MatrixGatePath
    $qcl041MatrixGateEvaluated = [bool]($null -ne $qcl041MatrixGate)
    $qcl041MatrixGatePassed = [bool]($qcl041MatrixGateEvaluated -and [bool]$qcl041MatrixGate.passed)
    $qcl041MatrixGatePassesRequirement = [bool]((-not $effectiveRequireQcl041MatrixGatePass) -or $qcl041MatrixGatePassed)
    $qcl041MatrixGateBlockedReason = if ($qcl041MatrixGateEvaluated) { $qcl041MatrixGate.blocked_reason_for_qcl100 } else { "" }
    $qcl041MatrixGateRunId = if ($qcl041MatrixGateEvaluated) { $qcl041MatrixGate.run_id } else { "" }
    $qcl041MatrixGateTransportProtocol = if ($qcl041MatrixGateEvaluated) { $qcl041MatrixGate.qcl082_transport_protocol } else { $Qcl082TransportProtocol }
    $qcl041MatrixGateRequiredTopology = if ($qcl041MatrixGateEvaluated) { $qcl041MatrixGate.required_qcl100_media_topology } else { "" }
    if ($effectiveRequireQcl041MatrixGatePass -and -not [bool]$qcl041MatrixGate.passed) {
        $blockedSummaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
        $blockedSummary = [ordered]@{
            schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
            run_id = $RunId
            status = "blocked_preflight"
            blocked_stage = "qcl041_matrix_gate_preflight"
            blocked_reason = $qcl041MatrixGate.blocked_reason_for_qcl100
            require_infrastructure_wifi_disconnected = [bool]$RequireInfrastructureWifiDisconnected
            require_p2p0_ipv4_cleared = [bool]$RequireP2p0Ipv4Cleared
            require_candidate_wifi_direct_routes_clear = [bool]$RequireCandidateWifiDirectRoutesClear
            require_qcl041_matrix_gate_pass = [bool]$effectiveRequireQcl041MatrixGatePass
            requested_require_qcl041_matrix_gate_pass = [bool]$RequireQcl041MatrixGatePass
            qcl041_matrix_run_id_pin_requires_gate = [bool]$qcl041MatrixRunIdPinRequiresGate
            required_qcl041_matrix_summary_path = $RequiredQcl041MatrixSummaryPath
            required_qcl041_matrix_run_id = $RequiredQcl041MatrixRunId
            max_qcl041_matrix_gate_age_seconds = [Math]::Max(0, $MaxQcl041MatrixGateAgeSeconds)
            qcl041_matrix_gate = $qcl041MatrixGate
            qcl041_matrix_gate_artifact = $qcl041MatrixGatePath
            preflight = $qcl100AirgapPreflight
            preflight_artifact = if ($null -ne $qcl100AirgapPreflight) { $airgapPreflightPath } else { "" }
            qcl041_preflight_preclear = $qcl041PreflightPreclear
            launched = $false
            owner_serial = $OwnerSerial
            client_serial = $ClientSerial
            direction = $Direction
            lane_mode = $LaneMode
            transport_owner = $TransportOwner
            owner_sends = $ownerSends
            client_sends = $clientSends
            owner_receives = $ownerReceives
            client_receives = $clientReceives
            topology = [ordered]@{
                transport = "quest_to_quest_wifi_direct"
                transport_owner = $TransportOwner
                qcl041_group_owner_label = $qcl041GroupOwnerLabel
                owner_qcl041_role = $ownerQcl041Role
                client_qcl041_role = $clientQcl041Role
            }
            transport_claims = [ordered]@{
                same_group_duplex_claimed = $false
                same_group_simultaneous_duplex = $false
                status = "blocked_preflight"
                blocked_until_same_epoch_bidirectional_media_and_renderer_freshness_pass = $true
            }
            same_group_duplex_claimed = $false
            freshness_acceptance = [ordered]@{
                required = "QCL100 media/render launch requires a strict QCL041 app-bound matrix pass with clean airgap preflight, receiver-observed bytes, and sustained bidirectional TCP tunnel stream bytes"
                direction = $Direction
                lane_mode = $LaneMode
                owner_camera_source_required = [bool]$ownerSends
                client_camera_source_required = [bool]$clientSends
                owner_relay_required = [bool]$ownerRelayRequired
                client_relay_required = [bool]$clientRelayRequired
                owner_receive_proxy_required = [bool]$ownerReceiveProxyRequired
                client_receive_proxy_required = [bool]$clientReceiveProxyRequired
                qcl041_matrix_gate_required = [bool]$effectiveRequireQcl041MatrixGatePass
                qcl041_matrix_gate_evaluated = [bool]$qcl041MatrixGateEvaluated
                qcl041_matrix_gate_artifact = $qcl041MatrixGatePath
                qcl041_matrix_gate_passed = [bool]$qcl041MatrixGatePassed
                qcl041_matrix_gate_passes_requirement = [bool]$qcl041MatrixGatePassesRequirement
                qcl041_matrix_gate_blocked_reason = $qcl041MatrixGateBlockedReason
                qcl041_matrix_run_id_pin_requires_gate = [bool]$qcl041MatrixRunIdPinRequiresGate
                required_qcl041_matrix_run_id = $RequiredQcl041MatrixRunId
                qcl041_matrix_gate_run_id = $qcl041MatrixGateRunId
                qcl041_matrix_gate_transport_protocol = $qcl041MatrixGateTransportProtocol
                qcl041_matrix_gate_required_topology = $qcl041MatrixGateRequiredTopology
                owner_stream_required = [bool]$ownerRendererRequired
                client_stream_required = [bool]$clientRendererRequired
                blocked_reason = $qcl041MatrixGate.blocked_reason_for_qcl100
                passed = $false
            }
            cleanup_policy = [ordered]@{
                final_force_stop_cleanup_skipped = $true
                reason = "blocked_preflight_no_launch"
                force_stop_packages = @()
            }
            evidence_dir = $OutDir
        }
        $matrixBlockers = New-Qcl100PreflightParityBlockers `
            -AirgapPreflight $qcl100AirgapPreflight `
            -RequireInfrastructureWifiDisconnected ([bool]$RequireInfrastructureWifiDisconnected) `
            -RequireP2p0Ipv4Cleared ([bool]$RequireP2p0Ipv4Cleared) `
            -RequireCandidateWifiDirectRoutesClear ([bool]$RequireCandidateWifiDirectRoutesClear)
        Add-Qcl100ParityBlocker `
            -Blockers $matrixBlockers `
            -Gate "qcl041_matrix_gate" `
            -Required ([bool]$effectiveRequireQcl041MatrixGatePass) `
            -Passed ([bool]$qcl041MatrixGatePassesRequirement) `
            -Reason "qcl041_matrix_gate_failed_or_missing" `
            -Details ([ordered]@{
                artifact = $qcl041MatrixGatePath
                blocked_reason = $qcl041MatrixGateBlockedReason
                run_id = $qcl041MatrixGateRunId
                required_run_id = $RequiredQcl041MatrixRunId
            })
        Set-Qcl100ParityBlockers -FreshnessAcceptance $blockedSummary.freshness_acceptance -Blockers $matrixBlockers
        Write-JsonFile -Value $blockedSummary -Path $blockedSummaryPath
        Get-Content -Raw $blockedSummaryPath
        exit 2
    }
}

if ($PreflightOnly) {
    $preflightSummaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
    $preflightSummary = [ordered]@{
        schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
        run_id = $RunId
        status = "preflight_only"
        require_infrastructure_wifi_disconnected = [bool]$RequireInfrastructureWifiDisconnected
        require_p2p0_ipv4_cleared = [bool]$RequireP2p0Ipv4Cleared
        require_candidate_wifi_direct_routes_clear = [bool]$RequireCandidateWifiDirectRoutesClear
        require_qcl041_matrix_gate_pass = [bool]$effectiveRequireQcl041MatrixGatePass
        requested_require_qcl041_matrix_gate_pass = [bool]$RequireQcl041MatrixGatePass
        qcl041_matrix_run_id_pin_requires_gate = [bool]$qcl041MatrixRunIdPinRequiresGate
        required_qcl041_matrix_summary_path = $RequiredQcl041MatrixSummaryPath
        required_qcl041_matrix_run_id = $RequiredQcl041MatrixRunId
        max_qcl041_matrix_gate_age_seconds = [Math]::Max(0, $MaxQcl041MatrixGateAgeSeconds)
        qcl041_matrix_gate = $qcl041MatrixGate
        qcl041_matrix_gate_artifact = $qcl041MatrixGatePath
        preflight = $qcl100AirgapPreflight
        preflight_artifact = $airgapPreflightPath
        qcl041_preflight_preclear = $qcl041PreflightPreclear
        wake_prep_policy = $wakePrepPolicy
        launched = $false
        owner_serial = $OwnerSerial
        client_serial = $ClientSerial
        direction = $Direction
        lane_mode = $LaneMode
        transport_owner = $TransportOwner
        same_group_duplex_claimed = $false
        freshness_acceptance = [ordered]@{
            required = "preflight only; no media, broker, or native renderer path launched"
            direction = $Direction
            lane_mode = $LaneMode
            passed = $false
        }
            evidence_dir = $OutDir
        }
    $preflightBlockers = New-Qcl100PreflightParityBlockers `
        -AirgapPreflight $qcl100AirgapPreflight `
        -RequireInfrastructureWifiDisconnected ([bool]$RequireInfrastructureWifiDisconnected) `
        -RequireP2p0Ipv4Cleared ([bool]$RequireP2p0Ipv4Cleared) `
        -RequireCandidateWifiDirectRoutesClear ([bool]$RequireCandidateWifiDirectRoutesClear)
    Set-Qcl100ParityBlockers -FreshnessAcceptance $preflightSummary.freshness_acceptance -Blockers $preflightBlockers
    Write-JsonFile -Value $preflightSummary -Path $preflightSummaryPath
    Get-Content -Raw $preflightSummaryPath
    exit 0
}

if ($XrLaunchReadinessOnly) {
    $ownerWakePrep = [ordered]@{
        skipped = $true
        reason = "xr_launch_readiness_only"
        serial = $OwnerSerial
        label = "owner"
    }
    $clientWakePrep = [ordered]@{
        skipped = $true
        reason = "xr_launch_readiness_only"
        serial = $ClientSerial
        label = "client"
    }
    $ownerXrReadiness = Get-QuestXrLaunchReadiness -Serial $OwnerSerial -Label "owner"
    $clientXrReadiness = Get-QuestXrLaunchReadiness -Serial $ClientSerial -Label "client"
    $xrReadinessBlocked = @()
    if (-not [bool]$ownerXrReadiness.xr_launch_ready) {
        $xrReadinessBlocked += [ordered]@{
            role = "owner"
            serial = $OwnerSerial
            issues = $ownerXrReadiness.issues
        }
    }
    if (-not [bool]$clientXrReadiness.xr_launch_ready) {
        $xrReadinessBlocked += [ordered]@{
            role = "client"
            serial = $ClientSerial
            issues = $clientXrReadiness.issues
        }
    }
    $readinessStatus = if ($xrReadinessBlocked.Count -gt 0) { "blocked" } else { "pass" }
    $readinessBlockedReason = if ($readinessStatus -eq "blocked") { "xr_launch_readiness_preflight" } else { "" }
    $readinessSummary = [ordered]@{
        schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
        run_id = $RunId
        status = $readinessStatus
        mode = "xr_launch_readiness_only"
        non_invasive = $true
        blocked_stage = $readinessBlockedReason
        blocked_reason = $readinessBlockedReason
        require_infrastructure_wifi_disconnected = [bool]$RequireInfrastructureWifiDisconnected
        require_p2p0_ipv4_cleared = [bool]$RequireP2p0Ipv4Cleared
        require_candidate_wifi_direct_routes_clear = [bool]$RequireCandidateWifiDirectRoutesClear
        require_qcl041_matrix_gate_pass = [bool]$effectiveRequireQcl041MatrixGatePass
        requested_require_qcl041_matrix_gate_pass = [bool]$RequireQcl041MatrixGatePass
        qcl041_matrix_run_id_pin_requires_gate = [bool]$qcl041MatrixRunIdPinRequiresGate
        required_qcl041_matrix_summary_path = $RequiredQcl041MatrixSummaryPath
        required_qcl041_matrix_run_id = $RequiredQcl041MatrixRunId
        max_qcl041_matrix_gate_age_seconds = [Math]::Max(0, $MaxQcl041MatrixGateAgeSeconds)
        qcl041_matrix_gate = $qcl041MatrixGate
        qcl041_matrix_gate_artifact = $qcl041MatrixGatePath
        preflight = $qcl100AirgapPreflight
        preflight_artifact = if ($null -ne $qcl100AirgapPreflight) { $airgapPreflightPath } else { "" }
        qcl041_preflight_preclear = $qcl041PreflightPreclear
        wake_prep_policy = $wakePrepPolicy
        launched = $false
        owner_serial = $OwnerSerial
        client_serial = $ClientSerial
        direction = $Direction
        lane_mode = $LaneMode
        transport_owner = $TransportOwner
        owner_sends = $ownerSends
        client_sends = $clientSends
        owner_receives = $ownerReceives
        client_receives = $clientReceives
        owner_wake_prep = $ownerWakePrep
        client_wake_prep = $clientWakePrep
        owner_xr_launch_readiness = $ownerXrReadiness
        client_xr_launch_readiness = $clientXrReadiness
        blocked_headsets = $xrReadinessBlocked
        topology = [ordered]@{
            transport = "quest_to_quest_wifi_direct"
            transport_owner = $TransportOwner
            qcl041_group_owner_label = $qcl041GroupOwnerLabel
            owner_qcl041_role = $ownerQcl041Role
            client_qcl041_role = $clientQcl041Role
        }
        transport_claims = [ordered]@{
            same_group_duplex_claimed = $false
            same_group_simultaneous_duplex = $false
            status = if ($readinessStatus -eq "blocked") { "blocked_preflight" } else { "readiness_only_no_media" }
            blocked_until_same_epoch_bidirectional_media_and_renderer_freshness_pass = $true
        }
        same_group_duplex_claimed = $false
        freshness_acceptance = [ordered]@{
            required = "xr launch readiness only; no media, broker, or native renderer path launched, but strict QCL041 matrix and airgap gates are preserved"
            direction = $Direction
            lane_mode = $LaneMode
            owner_camera_source_required = [bool]$ownerSends
            client_camera_source_required = [bool]$clientSends
            owner_relay_required = [bool]$ownerRelayRequired
            client_relay_required = [bool]$clientRelayRequired
            owner_receive_proxy_required = [bool]$ownerReceiveProxyRequired
            client_receive_proxy_required = [bool]$clientReceiveProxyRequired
            qcl041_matrix_gate_required = [bool]$effectiveRequireQcl041MatrixGatePass
            qcl041_matrix_gate_evaluated = [bool]$qcl041MatrixGateEvaluated
            qcl041_matrix_gate_artifact = $qcl041MatrixGatePath
            qcl041_matrix_gate_passed = [bool]$qcl041MatrixGatePassed
            qcl041_matrix_gate_passes_requirement = [bool]$qcl041MatrixGatePassesRequirement
            qcl041_matrix_gate_blocked_reason = $qcl041MatrixGateBlockedReason
            qcl041_matrix_run_id_pin_requires_gate = [bool]$qcl041MatrixRunIdPinRequiresGate
            required_qcl041_matrix_run_id = $RequiredQcl041MatrixRunId
            qcl041_matrix_gate_run_id = $qcl041MatrixGateRunId
            qcl041_matrix_gate_transport_protocol = $qcl041MatrixGateTransportProtocol
            qcl041_matrix_gate_required_topology = $qcl041MatrixGateRequiredTopology
            owner_stream_required = [bool]$ownerRendererRequired
            client_stream_required = [bool]$clientRendererRequired
            owner_xr_launch_ready = [bool]$ownerXrReadiness.xr_launch_ready
            client_xr_launch_ready = [bool]$clientXrReadiness.xr_launch_ready
            xr_launch_readiness_only = $true
            readiness_only_no_media_launched = $true
            blocked_reason = $readinessBlockedReason
            passed = $false
        }
        cleanup_policy = [ordered]@{
            final_force_stop_cleanup_skipped = $true
            reason = "xr_launch_readiness_only_no_launch"
            force_stop_packages = @()
        }
        evidence_dir = $OutDir
        issue = if ($readinessStatus -eq "blocked") { "One or more required Quest headsets are not XR launch-ready." } else { "" }
    }
    $readinessBlockers = New-Qcl100PreflightParityBlockers `
        -AirgapPreflight $qcl100AirgapPreflight `
        -RequireInfrastructureWifiDisconnected ([bool]$RequireInfrastructureWifiDisconnected) `
        -RequireP2p0Ipv4Cleared ([bool]$RequireP2p0Ipv4Cleared) `
        -RequireCandidateWifiDirectRoutesClear ([bool]$RequireCandidateWifiDirectRoutesClear)
    Add-Qcl100ParityBlocker `
        -Blockers $readinessBlockers `
        -Gate "qcl041_matrix_gate" `
        -Required ([bool]$effectiveRequireQcl041MatrixGatePass) `
        -Passed ([bool]$qcl041MatrixGatePassesRequirement) `
        -Reason "qcl041_matrix_gate_failed_or_missing" `
        -Details ([ordered]@{
            artifact = $qcl041MatrixGatePath
            blocked_reason = $qcl041MatrixGateBlockedReason
            run_id = $qcl041MatrixGateRunId
            required_run_id = $RequiredQcl041MatrixRunId
        })
    Add-Qcl100ParityBlocker `
        -Blockers $readinessBlockers `
        -Gate "owner_xr_launch_readiness" `
        -Required $true `
        -Passed ([bool]$ownerXrReadiness.xr_launch_ready) `
        -Reason "owner_xr_launch_not_ready" `
        -Details ([ordered]@{
            issues = @($ownerXrReadiness.issues)
            current_focus = $ownerXrReadiness.current_focus
            sensor_lock_active = [bool]$ownerXrReadiness.sensor_lock_active
            reprojected_os_dialog_seen = [bool]$ownerXrReadiness.reprojected_os_dialog_seen
            sys_hmt_mounted = $ownerXrReadiness.sys_hmt_mounted
        })
    Add-Qcl100ParityBlocker `
        -Blockers $readinessBlockers `
        -Gate "client_xr_launch_readiness" `
        -Required $true `
        -Passed ([bool]$clientXrReadiness.xr_launch_ready) `
        -Reason "client_xr_launch_not_ready" `
        -Details ([ordered]@{
            issues = @($clientXrReadiness.issues)
            current_focus = $clientXrReadiness.current_focus
            sensor_lock_active = [bool]$clientXrReadiness.sensor_lock_active
            reprojected_os_dialog_seen = [bool]$clientXrReadiness.reprojected_os_dialog_seen
            sys_hmt_mounted = $clientXrReadiness.sys_hmt_mounted
        })
    Set-Qcl100ParityBlockers -FreshnessAcceptance $readinessSummary.freshness_acceptance -Blockers $readinessBlockers
    $readinessSummaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
    Write-JsonFile -Value $readinessSummary -Path $readinessSummaryPath
    Get-Content -Raw $readinessSummaryPath
    return
}






foreach ($path in @($HostessCtl, $Qcl041Apk, $BrokerApk, $NativeRendererApk, $NativeRendererProfile)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required artifact not found: $path"
    }
}

$nativeRendererApkPermission = Assert-ApkUsesPermission `
    -ApkPath $NativeRendererApk `
    -Permission "android.permission.INTERNET" `
    -Label "native renderer" `
    -DumpPath (Join-Path $MediaDir "native-renderer-apk-permissions.txt")

$ownerQclPermission = $null
$clientQclPermission = $null
$ownerNativePermission = $null
$clientNativePermission = $null
$ownerProfilePlan = $null
$clientProfilePlan = $null
$ownerLaneModeOverride = $null
$clientLaneModeOverride = $null
$ownerXrReadiness = $null
$clientXrReadiness = $null
$ownerLog = Join-Path $OutDir "owner-native-renderer.logcat.txt"
$clientLog = Join-Path $OutDir "client-native-renderer.logcat.txt"
$ownerNativeLogcatCapture = $null
$clientNativeLogcatCapture = $null
$ownerNativeLogcatCaptureStop = $null
$clientNativeLogcatCaptureStop = $null
$script:qcl100TrapCleanupAttempted = $false
$script:qcl100Qcl041ArtifactReads = $null

function New-Qcl100TrapNativeLogSummary {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    try {
        $summary = Summarize-NativeRendererLog -LogPath $Path
        return [ordered]@{
            log_path = $Path
            projection_ready = [bool]$summary.projection_ready
            stream_fresh_frames = [bool]$summary.stream_fresh_frames
            scorecard_fresh_frames = [bool]$summary.scorecard_fresh_frames
            system_fatal_count = $summary.system_fatal_count
            system_fatal_lines = $summary.system_fatal_lines
            fatal_count = $summary.fatal_count
            fatal_lines = $summary.fatal_lines
            left_frame_fresh = [bool]$summary.left_frame_freshness.fresh
            right_frame_fresh = [bool]$summary.right_frame_freshness.fresh
            left_scorecard_fresh = [bool]$summary.left_scorecard_freshness.fresh
            right_scorecard_fresh = [bool]$summary.right_scorecard_freshness.fresh
        }
    } catch {
        return [ordered]@{
            log_path = $Path
            summary_error = $_.Exception.Message
        }
    }
}

function Write-Qcl100OrchestrationFailureSummary {
    param([string]$Message)

    $failure = [ordered]@{
        schema = "rusty.quest.qcl100_native_stereo_projection_orchestration_failure.v1"
        run_id = $RunId
        generated_at_utc = (Get-Date).ToUniversalTime().ToString("o")
        status = "blocked_orchestration"
        blocked_stage = "qcl100_orchestration_exception"
        blocked_reason = $Message
        qcl041_artifact_wait_seconds = $Qcl041ArtifactWaitSeconds
        artifacts = [ordered]@{
            owner_qcl041 = (Test-Path -LiteralPath (Join-Path $OutDir "owner-qcl041.json"))
            client_qcl041 = (Test-Path -LiteralPath (Join-Path $OutDir "client-qcl041.json"))
            owner_native_log = (Test-Path -LiteralPath $ownerLog)
            client_native_log = (Test-Path -LiteralPath $clientLog)
        }
        qcl041_artifact_reads = $script:qcl100Qcl041ArtifactReads
        native_log_summary = [ordered]@{
            owner = New-Qcl100TrapNativeLogSummary -Path $ownerLog
            client = New-Qcl100TrapNativeLogSummary -Path $clientLog
        }
        cleanup = [ordered]@{
            final_force_stop_cleanup_skipped = [bool]$SkipCleanup
            reason = if ($SkipCleanup) { "skip_cleanup_preserve_failure_state" } else { "trap_force_stop_cleanup" }
            force_stop_packages = if ($SkipCleanup) { @() } else { @($Qcl041Package, $BrokerPackage, $NativeRendererPackage) }
        }
    }
    $failurePath = Join-Path $OutDir "qcl100-orchestration-failure.json"
    Write-JsonFile -Value $failure -Path $failurePath
    $summaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
    if (-not (Test-Path -LiteralPath $summaryPath)) {
        Write-JsonFile -Value $failure -Path $summaryPath
    }
}

function Read-Qcl100JsonIfPresent {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        return Get-Content -Raw $Path | ConvertFrom-Json
    }
    return $null
}

function Get-Qcl100RemoteCameraRuntimeFromExecution {
    param($Execution)
    if ($null -eq $Execution) {
        return $null
    }
    $messages = @($Execution.command_execution.broker_messages)
    if ($messages.Count -eq 0) {
        return $null
    }
    return $messages[0].remote_camera_runtime
}

trap {
    $message = $_.Exception.Message
    try {
        if ($null -ne $ownerNativeLogcatCapture -and $null -eq $ownerNativeLogcatCaptureStop) {
            $ownerNativeLogcatCaptureStop = Stop-Qcl100NativeRendererLogcatCapture -Capture $ownerNativeLogcatCapture
        }
        if ($null -ne $clientNativeLogcatCapture -and $null -eq $clientNativeLogcatCaptureStop) {
            $clientNativeLogcatCaptureStop = Stop-Qcl100NativeRendererLogcatCapture -Capture $clientNativeLogcatCapture
        }
    } catch {
    }
    try {
        Write-Qcl100OrchestrationFailureSummary -Message $message
    } catch {
    }
    if (-not $SkipCleanup -and -not $script:qcl100TrapCleanupAttempted) {
        $script:qcl100TrapCleanupAttempted = $true
        try {
            Stop-Qcl100DeviceApps -Serials @($OwnerSerial, $ClientSerial)
        } catch {
        }
    }
    break
}

foreach ($device in @(
    [ordered]@{ serial = $OwnerSerial; label = "owner" },
    [ordered]@{ serial = $ClientSerial; label = "client" }
)) {
    $serial = $device.serial
    $label = $device.label
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", $Qcl041Package)
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", $BrokerPackage)
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", $NativeRendererPackage)
    Invoke-AdbBestEffort -Serial $serial -Arguments @("forward", "--remove-all")
    if (-not $SkipInstall) {
        Invoke-AdbChecked -Serial $serial -Arguments @("install", "-r", $Qcl041Apk) -Name "install qcl041"
        Invoke-AdbChecked -Serial $serial -Arguments @("install", "-r", $BrokerApk) -Name "install broker"
        Invoke-AdbChecked -Serial $serial -Arguments @("install", "-r", $NativeRendererApk) -Name "install native renderer"
    }
    if ($label -eq "owner") {
        $ownerQclPermission = Grant-QclRuntimePermissions -Serial $serial -Label $label
        $ownerNativePermission = Grant-NativeRendererPermissions -Serial $serial -Label $label
        $ownerProfilePlan = Apply-NativeRendererProfile -Serial $serial -Label $label
        $ownerLaneModeOverride = Apply-NativeRendererLaneModeOverride -Serial $serial -Label $label
    } else {
        $clientQclPermission = Grant-QclRuntimePermissions -Serial $serial -Label $label
        $clientNativePermission = Grant-NativeRendererPermissions -Serial $serial -Label $label
        $clientProfilePlan = Apply-NativeRendererProfile -Serial $serial -Label $label
        $clientLaneModeOverride = Apply-NativeRendererLaneModeOverride -Serial $serial -Label $label
    }
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "logcat", "-c")
}

$ownerNativeLogcatCapture = Start-Qcl100NativeRendererLogcatCapture -Serial $OwnerSerial -Label "owner" -Path $ownerLog
$clientNativeLogcatCapture = Start-Qcl100NativeRendererLogcatCapture -Serial $ClientSerial -Label "client" -Path $clientLog

if ($NoMediaLaunchOnly) {
    $ownerWakePrep = Prepare-QuestForXrFocus -Serial $OwnerSerial -Label "owner" -SkipWakePrep:$SkipWakePrep -AllowWakePrepMutation:$AllowWakePrepMutation
    $clientWakePrep = Prepare-QuestForXrFocus -Serial $ClientSerial -Label "client" -SkipWakePrep:$SkipWakePrep -AllowWakePrepMutation:$AllowWakePrepMutation
    $ownerXrReadiness = Get-QuestXrLaunchReadiness -Serial $OwnerSerial -Label "owner"
    $clientXrReadiness = Get-QuestXrLaunchReadiness -Serial $ClientSerial -Label "client"
    $xrReadinessBlocked = @()
    if (-not [bool]$ownerXrReadiness.xr_launch_ready) {
        $xrReadinessBlocked += [ordered]@{
            role = "owner"
            serial = $OwnerSerial
            issues = $ownerXrReadiness.issues
        }
    }
    if (-not [bool]$clientXrReadiness.xr_launch_ready) {
        $xrReadinessBlocked += [ordered]@{
            role = "client"
            serial = $ClientSerial
            issues = $clientXrReadiness.issues
        }
    }
    if ($xrReadinessBlocked.Count -gt 0) {
        $blockedSummaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
        $blockedSummary = [ordered]@{
            schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
            run_id = $RunId
            status = "blocked"
            mode = "no_media_launch_only"
            blocked_stage = "xr_launch_readiness_preflight"
            owner_serial = $OwnerSerial
            client_serial = $ClientSerial
            wake_prep_policy = $wakePrepPolicy
            owner_wake_prep = $ownerWakePrep
            client_wake_prep = $clientWakePrep
            owner_xr_launch_readiness = $ownerXrReadiness
            client_xr_launch_readiness = $clientXrReadiness
            blocked_headsets = $xrReadinessBlocked
            qcl041_started = $false
            qcl082_media_started = $false
            same_group_duplex_claimed = $false
            cleanup_policy = [ordered]@{
                final_force_stop_cleanup_skipped = [bool]$SkipCleanup
                reason = if ($SkipCleanup) { "skip_cleanup_preserve_no_media_failure_state" } else { "blocked_preflight_force_stop_cleanup" }
                force_stop_packages = if ($SkipCleanup) { @() } else { @($Qcl041Package, $BrokerPackage, $NativeRendererPackage) }
            }
            evidence_dir = $OutDir
            issue = "One or more required Quest headsets are not mounted or are blocked by SensorLock/reprojected OS dialog before no-media broker/native launch."
        }
        Write-JsonFile -Value $blockedSummary -Path $blockedSummaryPath
        if (-not $SkipCleanup) {
            Stop-Qcl100DeviceApps -Serials @($OwnerSerial, $ClientSerial)
        }
        Get-Content -Raw $blockedSummaryPath
        exit 2
    }

    $statusParams = [ordered]@{ session_id = $RunId }
    $ownerStatus = New-BridgeRequest "owner-no-media-status" "command.remote_camera.get_status" $statusParams "request.qcl100.$RunId.owner.no_media_status" "evidence.qcl100.$RunId.owner.no_media_status"
    $clientStatus = New-BridgeRequest "client-no-media-status" "command.remote_camera.get_status" $statusParams "request.qcl100.$RunId.client.no_media_status" "evidence.qcl100.$RunId.client.no_media_status"
    $ownerNoMediaStatusProbe = Invoke-LiveBridgeCommand "owner-no-media-status" $OwnerSerial $OwnerBrokerLocalPort $ownerStatus -TimeoutSeconds 25 -RetryCount 3 -RetryDelayMs 1500
    $clientNoMediaStatusProbe = Invoke-LiveBridgeCommand "client-no-media-status" $ClientSerial $ClientBrokerLocalPort $clientStatus -TimeoutSeconds 25 -RetryCount 3 -RetryDelayMs 1500

    Start-NativeRenderer -Serial $OwnerSerial -Label "owner"
    Start-NativeRenderer -Serial $ClientSerial -Label "client"
    Start-Sleep -Seconds ([Math]::Max(1, $NoMediaLaunchSeconds))

    $ownerNativeLogcatCaptureStop = Stop-Qcl100NativeRendererLogcatCapture -Capture $ownerNativeLogcatCapture
    $clientNativeLogcatCaptureStop = Stop-Qcl100NativeRendererLogcatCapture -Capture $clientNativeLogcatCapture
    if (-not (Test-Path -LiteralPath $ownerLog) -or (Get-Item -LiteralPath $ownerLog).Length -eq 0) {
        Invoke-External -Name "owner no-media native renderer filtered logcat fallback" -File $Adb -Arguments @("-s", $OwnerSerial, "logcat", "-d", "-v", "threadtime", "RQNativeRenderer:I", "AndroidRuntime:E", "*:S") -LogPath $ownerLog | Out-Null
    }
    if (-not (Test-Path -LiteralPath $clientLog) -or (Get-Item -LiteralPath $clientLog).Length -eq 0) {
        Invoke-External -Name "client no-media native renderer filtered logcat fallback" -File $Adb -Arguments @("-s", $ClientSerial, "logcat", "-d", "-v", "threadtime", "RQNativeRenderer:I", "AndroidRuntime:E", "*:S") -LogPath $clientLog | Out-Null
    }
    $ownerFinalFocus = Get-NativeRendererFocusSnapshot -Serial $OwnerSerial -Label "owner" -Suffix "no-media-final"
    $clientFinalFocus = Get-NativeRendererFocusSnapshot -Serial $ClientSerial -Label "client" -Suffix "no-media-final"
    $ownerStatusExecution = Read-Qcl100JsonIfPresent (Join-Path $MediaDir "owner-no-media-status-execution.json")
    $clientStatusExecution = Read-Qcl100JsonIfPresent (Join-Path $MediaDir "client-no-media-status-execution.json")
    $ownerNativeRenderer = Summarize-NativeRendererLog -LogPath $ownerLog
    $clientNativeRenderer = Summarize-NativeRendererLog -LogPath $clientLog
    $ownerBrokerStatus = Summarize-BrokerRuntime (Get-Qcl100RemoteCameraRuntimeFromExecution $ownerStatusExecution)
    $clientBrokerStatus = Summarize-BrokerRuntime (Get-Qcl100RemoteCameraRuntimeFromExecution $clientStatusExecution)
    $ownerNoMediaPass = [bool](
        $ownerNoMediaStatusProbe.status -eq "pass" -and
        $null -ne $ownerBrokerStatus -and
        [bool]$ownerFinalFocus.focus_active -and
        [int]$ownerNativeRenderer.system_fatal_count -eq 0 -and
        [int]$ownerNativeRenderer.fatal_count -eq 0
    )
    $clientNoMediaPass = [bool](
        $clientNoMediaStatusProbe.status -eq "pass" -and
        $null -ne $clientBrokerStatus -and
        [bool]$clientFinalFocus.focus_active -and
        [int]$clientNativeRenderer.system_fatal_count -eq 0 -and
        [int]$clientNativeRenderer.fatal_count -eq 0
    )
    $noMediaPassed = [bool]($ownerNoMediaPass -and $clientNoMediaPass)
    $noMediaSummary = [ordered]@{
        schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
        run_id = $RunId
        status = if ($noMediaPassed) { "pass" } else { "blocked" }
        mode = "no_media_launch_only"
        no_media_launch_seconds = [Math]::Max(1, $NoMediaLaunchSeconds)
        qcl041_started = $false
        qcl082_media_started = $false
        qcl041_relay_started = $false
        broker_launch_required = $true
        native_renderer_launch_required = $true
        promotion_allowed = $false
        same_group_duplex_claimed = $false
        owner_serial = $OwnerSerial
        client_serial = $ClientSerial
        wake_prep_policy = $wakePrepPolicy
        owner_wake_prep = $ownerWakePrep
        client_wake_prep = $clientWakePrep
        owner_xr_launch_readiness = $ownerXrReadiness
        client_xr_launch_readiness = $clientXrReadiness
        permission_pregrant_receipts = [ordered]@{
            owner_qcl_broker = $ownerQclPermission
            client_qcl_broker = $clientQclPermission
            owner_native_renderer = $ownerNativePermission
            client_native_renderer = $clientNativePermission
        }
        runtime_profile_plans = [ordered]@{
            owner_native_renderer = $ownerProfilePlan
            client_native_renderer = $clientProfilePlan
        }
        lane_mode_property_overrides = [ordered]@{
            owner_native_renderer = $ownerLaneModeOverride
            client_native_renderer = $clientLaneModeOverride
        }
        final_status_probes = [ordered]@{
            owner = $ownerNoMediaStatusProbe
            client = $clientNoMediaStatusProbe
        }
        owner_broker_status = $ownerBrokerStatus
        client_broker_status = $clientBrokerStatus
        owner_native_renderer_projection = $ownerNativeRenderer
        client_native_renderer_projection = $clientNativeRenderer
        native_log_summary = [ordered]@{
            owner = [ordered]@{
                log_path = $ownerLog
                fatal_count = $ownerNativeRenderer.fatal_count
                fatal_lines = $ownerNativeRenderer.fatal_lines
                system_fatal_count = $ownerNativeRenderer.system_fatal_count
                system_fatal_lines = $ownerNativeRenderer.system_fatal_lines
            }
            client = [ordered]@{
                log_path = $clientLog
                fatal_count = $clientNativeRenderer.fatal_count
                fatal_lines = $clientNativeRenderer.fatal_lines
                system_fatal_count = $clientNativeRenderer.system_fatal_count
                system_fatal_lines = $clientNativeRenderer.system_fatal_lines
            }
            fatal_count = ([int]$ownerNativeRenderer.fatal_count + [int]$clientNativeRenderer.fatal_count)
            system_fatal_count = ([int]$ownerNativeRenderer.system_fatal_count + [int]$clientNativeRenderer.system_fatal_count)
        }
        native_renderer_logcat_capture = [ordered]@{
            owner = $ownerNativeLogcatCaptureStop
            client = $clientNativeLogcatCaptureStop
        }
        owner_final_focus = $ownerFinalFocus
        client_final_focus = $clientFinalFocus
        owner_no_media_launch_pass = $ownerNoMediaPass
        client_no_media_launch_pass = $clientNoMediaPass
        freshness_acceptance = [ordered]@{
            required = "no-media launch only; broker and native renderer launch without QCL041 relays, QCL082 media, or same-group duplex promotion"
            passed = $noMediaPassed
            owner_broker_status_present = [bool]($null -ne $ownerBrokerStatus)
            client_broker_status_present = [bool]($null -ne $clientBrokerStatus)
            owner_native_focus_active = [bool]$ownerFinalFocus.focus_active
            client_native_focus_active = [bool]$clientFinalFocus.focus_active
            native_log_system_fatal_count = ([int]$ownerNativeRenderer.system_fatal_count + [int]$clientNativeRenderer.system_fatal_count)
            native_log_fatal_count = ([int]$ownerNativeRenderer.fatal_count + [int]$clientNativeRenderer.fatal_count)
        }
        cleanup_policy = [ordered]@{
            final_force_stop_cleanup_skipped = [bool]$SkipCleanup
            reason = if ($SkipCleanup) { "skip_cleanup_preserve_no_media_launch_state" } else { "no_media_launch_force_stop_cleanup" }
            force_stop_packages = if ($SkipCleanup) { @() } else { @($Qcl041Package, $BrokerPackage, $NativeRendererPackage) }
        }
        evidence_dir = $OutDir
    }
    $noMediaSummaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
    Write-JsonFile -Value $noMediaSummary -Path $noMediaSummaryPath
    if (-not $SkipCleanup) {
        Stop-Qcl100DeviceApps -Serials @($OwnerSerial, $ClientSerial)
    }
    Get-Content -Raw $noMediaSummaryPath
    if (-not $noMediaPassed) {
        exit 2
    }
    exit 0
}

Invoke-Qcl041PreclearOnly -Serial $OwnerSerial -LeaseId $OwnerLeaseId -Label "owner"
Invoke-Qcl041PreclearOnly -Serial $ClientSerial -LeaseId $ClientLeaseId -Label "client"

$ownerWakePrep = Prepare-QuestForXrFocus -Serial $OwnerSerial -Label "owner" -SkipWakePrep:$SkipWakePrep -AllowWakePrepMutation:$AllowWakePrepMutation
$clientWakePrep = Prepare-QuestForXrFocus -Serial $ClientSerial -Label "client" -SkipWakePrep:$SkipWakePrep -AllowWakePrepMutation:$AllowWakePrepMutation
$ownerXrReadiness = Get-QuestXrLaunchReadiness -Serial $OwnerSerial -Label "owner"
$clientXrReadiness = Get-QuestXrLaunchReadiness -Serial $ClientSerial -Label "client"

$xrReadinessBlocked = @()
if (-not [bool]$ownerXrReadiness.xr_launch_ready) {
    $xrReadinessBlocked += [ordered]@{
        role = "owner"
        serial = $OwnerSerial
        issues = $ownerXrReadiness.issues
    }
}
if (-not [bool]$clientXrReadiness.xr_launch_ready) {
    $xrReadinessBlocked += [ordered]@{
        role = "client"
        serial = $ClientSerial
        issues = $clientXrReadiness.issues
    }
}
if ($xrReadinessBlocked.Count -gt 0) {
    $blockedSummaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
    $blockedSummary = [ordered]@{
        schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
        run_id = $RunId
        status = "blocked"
        blocked_stage = "xr_launch_readiness_preflight"
        owner_serial = $OwnerSerial
        client_serial = $ClientSerial
        direction = $Direction
        lane_mode = $LaneMode
        transport_owner = $TransportOwner
        owner_sends = $ownerSends
        client_sends = $clientSends
        owner_receives = $ownerReceives
        client_receives = $clientReceives
        wake_prep_policy = $wakePrepPolicy
        owner_wake_prep = $ownerWakePrep
        client_wake_prep = $clientWakePrep
        owner_xr_launch_readiness = $ownerXrReadiness
        client_xr_launch_readiness = $clientXrReadiness
        blocked_headsets = $xrReadinessBlocked
        cleanup_policy = [ordered]@{
            final_force_stop_cleanup_skipped = [bool]$SkipCleanup
            reason = if ($SkipCleanup) { "skip_cleanup_preserve_final_xr_focus" } else { "blocked_preflight_force_stop_cleanup" }
        }
        issue = "One or more required Quest headsets are not mounted or are blocked by SensorLock/reprojected OS dialog before broker/native launch."
    }
    Write-JsonFile -Value $blockedSummary -Path $blockedSummaryPath
    if (-not $SkipCleanup) {
        Stop-Qcl100DeviceApps -Serials @($OwnerSerial, $ClientSerial)
    }
    throw "QCL100 XR launch readiness preflight failed; see $blockedSummaryPath"
}

$mediaProfileByEye = @{}
foreach ($part in @($MediaProfiles -split ";")) {
    if ([string]::IsNullOrWhiteSpace($part)) {
        continue
    }
    $pieces = $part.Split(":", 2)
    if ($pieces.Count -eq 2) {
        $mediaProfileByEye[$pieces[0]] = $part
    }
}
$cameraIdByEye = @{}
foreach ($part in @($CameraIds -split ",")) {
    if ([string]::IsNullOrWhiteSpace($part)) {
        continue
    }
    $pieces = $part.Split(":", 2)
    if ($pieces.Count -eq 2) {
        $cameraIdByEye[$pieces[0]] = $part
    }
}
$receiverPortSpecs = @()
$transportReceivePortSpecs = @()
$senderSourcePortSpecs = @()
$effectiveMediaProfileSpecs = @()
$effectiveCameraIdSpecs = @()
$leftBrokerTransportReceivePort = if ($TransportOwner -eq "qcl041") { $LeftTransportProxyTargetPort } else { $LeftTransportPort }
$rightBrokerTransportReceivePort = if ($TransportOwner -eq "qcl041") { $RightTransportProxyTargetPort } else { $RightTransportPort }
if ($leftLaneActive) {
    $receiverPortSpecs += "left:$LeftReceiverPort"
    $transportReceivePortSpecs += "left:$leftBrokerTransportReceivePort"
    $senderSourcePortSpecs += "left:$LeftSourcePort"
    $effectiveMediaProfileSpecs += if ($mediaProfileByEye.ContainsKey("left")) { $mediaProfileByEye["left"] } else { "left:320x240@15:500000" }
    $effectiveCameraIdSpecs += if ($cameraIdByEye.ContainsKey("left")) { $cameraIdByEye["left"] } else { "left:50" }
}
if ($rightLaneActive) {
    $receiverPortSpecs += "right:$RightReceiverPort"
    $transportReceivePortSpecs += "right:$rightBrokerTransportReceivePort"
    $senderSourcePortSpecs += "right:$RightSourcePort"
    $effectiveMediaProfileSpecs += if ($mediaProfileByEye.ContainsKey("right")) { $mediaProfileByEye["right"] } else { "right:320x240@15:500000" }
    $effectiveCameraIdSpecs += if ($cameraIdByEye.ContainsKey("right")) { $cameraIdByEye["right"] } else { "right:51" }
}
$effectiveReceiverPorts = $receiverPortSpecs -join ","
$effectiveTransportReceivePorts = $transportReceivePortSpecs -join ","
$effectiveSenderSourcePorts = $senderSourcePortSpecs -join ","
$effectiveMediaProfiles = $effectiveMediaProfileSpecs -join ";"
$effectiveCameraIds = $effectiveCameraIdSpecs -join ","



$receiverParams = [ordered]@{
    session_id = $RunId
    receiver_bind_host = "127.0.0.1"
    receiver_ports = $effectiveReceiverPorts
    transport_bind_host = "0.0.0.0"
    transport_receive_ports = $effectiveTransportReceivePorts
}
$ownerTransportRoutes = if ($TransportOwner -eq "broker") { Get-TransportRouteSpec $ClientWifiDirectAddress } else { "none" }
$clientTransportRoutes = if ($TransportOwner -eq "broker") { Get-TransportRouteSpec $OwnerWifiDirectAddress } else { "none" }
$ownerTransportBindLocalAddress = if ($TransportOwner -eq "broker") { $OwnerWifiDirectAddress } else { "" }
$clientTransportBindLocalAddress = if ($TransportOwner -eq "broker") { $ClientWifiDirectAddress } else { "" }
$ownerSenderParams = New-SenderParams `
    -TransportRoutes $ownerTransportRoutes `
    -TransportBindLocalAddress $ownerTransportBindLocalAddress
$clientSenderParams = New-SenderParams `
    -TransportRoutes $clientTransportRoutes `
    -TransportBindLocalAddress $clientTransportBindLocalAddress

$ownerRecv = New-BridgeRequest "owner-start-receiver" "command.remote_camera.start_receiver" $receiverParams "request.qcl100.$RunId.owner.receiver" "evidence.qcl100.$RunId.owner.receiver"
$clientRecv = New-BridgeRequest "client-start-receiver" "command.remote_camera.start_receiver" $receiverParams "request.qcl100.$RunId.client.receiver" "evidence.qcl100.$RunId.client.receiver"
$ownerSender = New-BridgeRequest "owner-start-source-only" "command.remote_camera.start_sender" $ownerSenderParams "request.qcl100.$RunId.owner.source_only" "evidence.qcl100.$RunId.owner.source_only"
$clientSender = New-BridgeRequest "client-start-source-only" "command.remote_camera.start_sender" $clientSenderParams "request.qcl100.$RunId.client.source_only" "evidence.qcl100.$RunId.client.source_only"

if ($ownerReceives) {
    Invoke-LiveBridgeCommand "owner-start-receiver" $OwnerSerial $OwnerBrokerLocalPort $ownerRecv
}
if ($clientReceives) {
    Invoke-LiveBridgeCommand "client-start-receiver" $ClientSerial $ClientBrokerLocalPort $clientRecv
}
if ($ownerSends -and $TransportOwner -eq "qcl041") {
    Invoke-LiveBridgeCommand "owner-start-source-only" $OwnerSerial $OwnerBrokerLocalPort $ownerSender
}
if ($clientSends -and $TransportOwner -eq "qcl041") {
    Invoke-LiveBridgeCommand "client-start-source-only" $ClientSerial $ClientBrokerLocalPort $clientSender
}

if ($ownerRendererRequired) {
    Start-NativeRenderer -Serial $OwnerSerial -Label "owner"
}
if ($clientRendererRequired) {
    Start-NativeRenderer -Serial $ClientSerial -Label "client"
}
Start-Sleep -Seconds 2

if ($ownerQcl041Role -eq "group_owner") {
    Start-Qcl041Relay `
        $OwnerSerial `
        $ownerQcl041Role `
        $ownerQcl041RelayReceiverHost `
        $OwnerLeaseId `
        "owner-qcl041-launch.txt" `
        $ownerRelayRequired `
        $ownerReceiveProxyRequired `
        $ownerDeferredReceiverTargetFile `
        $ownerDeferredReceiverTargetWaitMs `
        $ownerDeferredReceiverTargetRequired `
        $RunId `
        $RequireQcl082UdpReceiveProxyNetworkBinding
    Start-Sleep -Seconds 5
    Start-Qcl041Relay `
        $ClientSerial `
        $clientQcl041Role `
        $clientQcl041RelayReceiverHost `
        $ClientLeaseId `
        "client-qcl041-launch.txt" `
        $clientRelayRequired `
        $clientReceiveProxyRequired `
        $clientDeferredReceiverTargetFile `
        $clientDeferredReceiverTargetWaitMs `
        $clientDeferredReceiverTargetRequired `
        $RunId `
        $RequireQcl082UdpReceiveProxyNetworkBinding
} else {
    Start-Qcl041Relay `
        $ClientSerial `
        $clientQcl041Role `
        $clientQcl041RelayReceiverHost `
        $ClientLeaseId `
        "client-qcl041-launch.txt" `
        $clientRelayRequired `
        $clientReceiveProxyRequired `
        $clientDeferredReceiverTargetFile `
        $clientDeferredReceiverTargetWaitMs `
        $clientDeferredReceiverTargetRequired `
        $RunId `
        $RequireQcl082UdpReceiveProxyNetworkBinding
    Start-Sleep -Seconds 5
    Start-Qcl041Relay `
        $OwnerSerial `
        $ownerQcl041Role `
        $ownerQcl041RelayReceiverHost `
        $OwnerLeaseId `
        "owner-qcl041-launch.txt" `
        $ownerRelayRequired `
        $ownerReceiveProxyRequired `
        $ownerDeferredReceiverTargetFile `
        $ownerDeferredReceiverTargetWaitMs `
        $ownerDeferredReceiverTargetRequired `
        $RunId `
        $RequireQcl082UdpReceiveProxyNetworkBinding
}
if ($ownerDeferredReceiverTargetRequired) {
    $qcl082DeferredReceiverTargets.owner_to_client = Publish-Qcl082DeferredReceiverTarget `
        -SenderSerial $OwnerSerial `
        -SenderLabel "owner" `
        -ReceiverSerial $ClientSerial `
        -ReceiverLabel "client" `
        -DeviceTargetFile $qcl082DeferredReceiverTargetFile
}
if ($clientDeferredReceiverTargetRequired) {
    $qcl082DeferredReceiverTargets.client_to_owner = Publish-Qcl082DeferredReceiverTarget `
        -SenderSerial $ClientSerial `
        -SenderLabel "client" `
        -ReceiverSerial $OwnerSerial `
        -ReceiverLabel "owner" `
        -DeviceTargetFile $qcl082DeferredReceiverTargetFile
}

Start-Sleep -Seconds 8

if ($ownerSends -and $TransportOwner -eq "broker") {
    Invoke-LiveBridgeCommand "owner-start-source-only" $OwnerSerial $OwnerBrokerLocalPort $ownerSender
}
if ($clientSends -and $TransportOwner -eq "broker") {
    Invoke-LiveBridgeCommand "client-start-source-only" $ClientSerial $ClientBrokerLocalPort $clientSender
}
if ($TransportOwner -eq "broker") {
    Start-Sleep -Seconds 2
}

Start-Sleep -Seconds $ProjectionSeconds

$ownerNativeLogcatCaptureStop = Stop-Qcl100NativeRendererLogcatCapture -Capture $ownerNativeLogcatCapture
$clientNativeLogcatCaptureStop = Stop-Qcl100NativeRendererLogcatCapture -Capture $clientNativeLogcatCapture
if (-not (Test-Path -LiteralPath $ownerLog) -or (Get-Item -LiteralPath $ownerLog).Length -eq 0) {
    Invoke-External -Name "owner native renderer filtered logcat fallback" -File $Adb -Arguments @("-s", $OwnerSerial, "logcat", "-d", "-v", "threadtime", "RQNativeRenderer:I", "AndroidRuntime:E", "*:S") -LogPath $ownerLog | Out-Null
}
if (-not (Test-Path -LiteralPath $clientLog) -or (Get-Item -LiteralPath $clientLog).Length -eq 0) {
    Invoke-External -Name "client native renderer filtered logcat fallback" -File $Adb -Arguments @("-s", $ClientSerial, "logcat", "-d", "-v", "threadtime", "RQNativeRenderer:I", "AndroidRuntime:E", "*:S") -LogPath $clientLog | Out-Null
}
$ownerFinalFocus = Get-NativeRendererFocusSnapshot -Serial $OwnerSerial -Label "owner" -Suffix "final"
$clientFinalFocus = Get-NativeRendererFocusSnapshot -Serial $ClientSerial -Label "client" -Suffix "final"

$statusParams = [ordered]@{ session_id = $RunId }
$ownerStatus = New-BridgeRequest "owner-final-status" "command.remote_camera.get_status" $statusParams "request.qcl100.$RunId.owner.final_status" "evidence.qcl100.$RunId.owner.final_status"
$clientStatus = New-BridgeRequest "client-final-status" "command.remote_camera.get_status" $statusParams "request.qcl100.$RunId.client.final_status" "evidence.qcl100.$RunId.client.final_status"
# Capture broker age fields before the blocking QCL041 artifact wait can age out the final media window.
$ownerFinalStatusProbe = Invoke-LiveBridgeCommand "owner-final-status" $OwnerSerial $OwnerBrokerLocalPort $ownerStatus -NoLaunchBroker -AllowFailure -TimeoutSeconds 25 -RetryCount 3 -RetryDelayMs 1500
$clientFinalStatusProbe = Invoke-LiveBridgeCommand "client-final-status" $ClientSerial $ClientBrokerLocalPort $clientStatus -NoLaunchBroker -AllowFailure -TimeoutSeconds 25 -RetryCount 3 -RetryDelayMs 1500

function Invoke-Qcl100FinalQcl041ArtifactRead {
    param(
        [string]$Serial,
        [string]$OutPath,
        [bool]$RequireRelayFreshness,
        [bool]$RequireReceiveProxyFreshness,
        [string]$Label
    )
    $result = [ordered]@{
        label = $Label
        serial = $Serial
        out_path = $OutPath
        require_relay_freshness = [bool]$RequireRelayFreshness
        require_receive_proxy_freshness = [bool]$RequireReceiveProxyFreshness
        artifact_present_before = (Test-Path -LiteralPath $OutPath)
        artifact_present_after = $false
        status = "unknown"
        error = $null
    }
    try {
        Read-Qcl041Artifact `
            -Serial $Serial `
            -OutPath $OutPath `
            -RequireRelayFreshness:$RequireRelayFreshness `
            -RequireReceiveProxyFreshness:$RequireReceiveProxyFreshness `
            -Label $Label
        $result.status = "pass"
    } catch {
        $result.status = "fail"
        $result.error = $_.Exception.Message
    }
    $result.artifact_present_after = (Test-Path -LiteralPath $OutPath)
    return $result
}

$ownerQcl041Read = Invoke-Qcl100FinalQcl041ArtifactRead `
    -Serial $OwnerSerial `
    -OutPath (Join-Path $OutDir "owner-qcl041.json") `
    -RequireRelayFreshness:$ownerRelayRequired `
    -RequireReceiveProxyFreshness:$ownerReceiveProxyRequired `
    -Label "owner-final-qcl041"
$clientQcl041Read = Invoke-Qcl100FinalQcl041ArtifactRead `
    -Serial $ClientSerial `
    -OutPath (Join-Path $OutDir "client-qcl041.json") `
    -RequireRelayFreshness:$clientRelayRequired `
    -RequireReceiveProxyFreshness:$clientReceiveProxyRequired `
    -Label "client-final-qcl041"
$script:qcl100Qcl041ArtifactReads = [ordered]@{
    owner = $ownerQcl041Read
    client = $clientQcl041Read
}
$qcl041ReadFailures = @(@($ownerQcl041Read, $clientQcl041Read) | Where-Object { $_.status -ne "pass" })
if ($qcl041ReadFailures.Count -gt 0) {
    $failureLabels = @($qcl041ReadFailures | ForEach-Object {
        "$($_.label): $($_.error)"
    })
    throw "QCL100 final QCL041 artifact read blocked after attempting both owner and client final artifacts (qcl041_final_artifact_read_blocked): $($failureLabels -join ' | ')"
}

$ownerQcl041 = Get-Content -Raw (Join-Path $OutDir "owner-qcl041.json") | ConvertFrom-Json
$clientQcl041 = Get-Content -Raw (Join-Path $OutDir "client-qcl041.json") | ConvertFrom-Json
$ownerStatusExecution = Read-Qcl100JsonIfPresent (Join-Path $MediaDir "owner-final-status-execution.json")
$clientStatusExecution = Read-Qcl100JsonIfPresent (Join-Path $MediaDir "client-final-status-execution.json")
$ownerNativeRenderer = Summarize-NativeRendererLog -LogPath $ownerLog
$clientNativeRenderer = Summarize-NativeRendererLog -LogPath $clientLog
$ownerBrokerStatus = Summarize-BrokerRuntime (Get-Qcl100RemoteCameraRuntimeFromExecution $ownerStatusExecution)
$clientBrokerStatus = Summarize-BrokerRuntime (Get-Qcl100RemoteCameraRuntimeFromExecution $clientStatusExecution)
$ownerQcl041ReferenceUnixMs = Get-Qcl041ArtifactReferenceUnixMs -Artifact $ownerQcl041
$clientQcl041ReferenceUnixMs = Get-Qcl041ArtifactReferenceUnixMs -Artifact $clientQcl041
$ownerCameraSourceFreshness = Get-BrokerCameraSourceFreshness -BrokerStatus $ownerBrokerStatus
$clientCameraSourceFreshness = Get-BrokerCameraSourceFreshness -BrokerStatus $clientBrokerStatus
$ownerRelayFreshness = Get-Qcl082RelayFreshness -Diagnostics $ownerQcl041.diagnostics -ReferenceUnixMs $ownerQcl041ReferenceUnixMs
$clientRelayFreshness = Get-Qcl082RelayFreshness -Diagnostics $clientQcl041.diagnostics -ReferenceUnixMs $clientQcl041ReferenceUnixMs
$ownerReceiveProxyFreshness = Get-Qcl082ReceiveProxyFreshness -Diagnostics $ownerQcl041.diagnostics -ReferenceUnixMs $ownerQcl041ReferenceUnixMs
$clientReceiveProxyFreshness = Get-Qcl082ReceiveProxyFreshness -Diagnostics $clientQcl041.diagnostics -ReferenceUnixMs $clientQcl041ReferenceUnixMs
$ownerBrokerReceiverObservedFreshness = Get-BrokerReceiverObservedFreshness -BrokerStatus $ownerBrokerStatus
$clientBrokerReceiverObservedFreshness = Get-BrokerReceiverObservedFreshness -BrokerStatus $clientBrokerStatus
$qcl082MediaTopologyAcceptance = Get-Qcl100Qcl082MediaTopologyAcceptance `
    -OwnerRelayFreshness $ownerRelayFreshness `
    -ClientRelayFreshness $clientRelayFreshness `
    -OwnerReceiveProxyFreshness $ownerReceiveProxyFreshness `
    -ClientReceiveProxyFreshness $clientReceiveProxyFreshness `
    -OwnerRelayRequired:$ownerRelayRequired `
    -ClientRelayRequired:$clientRelayRequired `
    -OwnerReceiveProxyRequired:$ownerReceiveProxyRequired `
    -ClientReceiveProxyRequired:$clientReceiveProxyRequired
$ownerCameraSourceFreshness = Resolve-Qcl100CameraSourceFreshness `
    -CurrentFreshness $ownerCameraSourceFreshness `
    -RelayFreshness $ownerRelayFreshness `
    -BrokerStatus $ownerBrokerStatus `
    -SenderLabel "owner"
$clientCameraSourceFreshness = Resolve-Qcl100CameraSourceFreshness `
    -CurrentFreshness $clientCameraSourceFreshness `
    -RelayFreshness $clientRelayFreshness `
    -BrokerStatus $clientBrokerStatus `
    -SenderLabel "client"

$summary = [ordered]@{
    schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
    run_id = $RunId
    owner_serial = $OwnerSerial
    client_serial = $ClientSerial
    direction = $Direction
    lane_mode = $LaneMode
    qcl041_group_owner_label = $qcl041GroupOwnerLabel
    qcl041_roles = [ordered]@{
        owner = $ownerQcl041Role
        client = $clientQcl041Role
        owner_relay_receiver_host = $ownerQcl041RelayReceiverHost
        client_relay_receiver_host = $clientQcl041RelayReceiverHost
    }
    projection_seconds = $ProjectionSeconds
    media_profiles = $effectiveMediaProfiles
    camera_ids = $effectiveCameraIds
    require_infrastructure_wifi_disconnected = [bool]$RequireInfrastructureWifiDisconnected
    require_p2p0_ipv4_cleared = [bool]$RequireP2p0Ipv4Cleared
    require_candidate_wifi_direct_routes_clear = [bool]$RequireCandidateWifiDirectRoutesClear
    require_qcl041_matrix_gate_pass = [bool]$effectiveRequireQcl041MatrixGatePass
    requested_require_qcl041_matrix_gate_pass = [bool]$RequireQcl041MatrixGatePass
    qcl041_matrix_run_id_pin_requires_gate = [bool]$qcl041MatrixRunIdPinRequiresGate
    required_qcl041_matrix_summary_path = $RequiredQcl041MatrixSummaryPath
    required_qcl041_matrix_run_id = $RequiredQcl041MatrixRunId
    max_qcl041_matrix_gate_age_seconds = [Math]::Max(0, $MaxQcl041MatrixGateAgeSeconds)
    preflight = $qcl100AirgapPreflight
    qcl041_preflight_preclear = $qcl041PreflightPreclear
    qcl041_matrix_gate = $qcl041MatrixGate
    qcl041_matrix_gate_artifact = $qcl041MatrixGatePath
    qcl082_ack_pacing = [ordered]@{
        enabled = [bool]$effectiveQcl082AckPacingEnabled
        disabled_by_switch = [bool]$DisableQcl082AckPacing
        chunk_bytes = $Qcl082AckChunkBytes
        timeout_ms = $Qcl082AckTimeoutMs
        soft_timeout_limit = $Qcl082AckSoftTimeoutLimit
    }
    qcl082_receive_proxy = [ordered]@{
        peer_idle_timeout_ms = $Qcl082ReceiveProxyPeerIdleTimeoutMs
        udp_network_binding_required = [bool]$RequireQcl082UdpReceiveProxyNetworkBinding
    }
    qcl082_relay = [ordered]@{
        transport_protocol = $Qcl082TransportProtocol
        start_delay_ms = $Qcl082RelayStartDelayMs
        write_stall_timeout_ms = $Qcl082RelayWriteStallTimeoutMs
        receiver_progress_timeout_ms = $Qcl082RelayReceiverProgressTimeoutMs
        port_rotation_count = $Qcl082RelayPortRotationCount
    }
    qcl082_deferred_receiver_targets = $qcl082DeferredReceiverTargets
    qcl041_artifact_freshness_reference = [ordered]@{
        owner_observed_at_utc = $ownerQcl041.observed_at_utc
        owner_reference_unix_ms = $ownerQcl041ReferenceUnixMs
        client_observed_at_utc = $clientQcl041.observed_at_utc
        client_reference_unix_ms = $clientQcl041ReferenceUnixMs
    }
    native_renderer_broker_socket = [ordered]@{
        connect_timeout_ms = $NativeRendererBrokerConnectTimeoutMs
        stream_read_timeout_ms = $NativeRendererBrokerConnectTimeoutMs
        timeout_property = "debug.rustyquest.native_renderer.video_projection.broker.connect_timeout_ms"
    }
    installed_artifacts = [ordered]@{
        qcl041_apk = Get-ArtifactEvidence -Path $Qcl041Apk
        broker_apk = Get-ArtifactEvidence -Path $BrokerApk
        native_renderer_apk = Get-ArtifactEvidence -Path $NativeRendererApk
        native_renderer_profile = Get-ArtifactEvidence -Path $NativeRendererProfile
        native_renderer_apk_permission = $nativeRendererApkPermission
    }
    topology = [ordered]@{
        transport = "quest_to_quest_wifi_direct"
        transport_owner = $TransportOwner
        relay = if ($TransportOwner -eq "qcl041") { "qcl041_outbound_relay_to_qcl041_receive_proxy" } else { "manifold_broker_direct_tcp_sender_bridge_after_qcl041_group_hold" }
        receiver_consumer = "native-rusty-quest-renderer"
        renderer_profile = $NativeRendererProfile
        source_ports = "left:$LeftSourcePort,right:$RightSourcePort"
        receiver_ports = $effectiveReceiverPorts
        qcl041_receive_proxy_listen_ports = "left:$LeftTransportPort,right:$RightTransportPort"
        qcl041_receive_proxy_target_ports = "left:$LeftTransportProxyTargetPort,right:$RightTransportProxyTargetPort"
        transport_receive_ports = $effectiveTransportReceivePorts
        native_renderer_source = "broker-rmanvid1"
        decode_target = "MediaCodec-to-Rust-AImageReader-AHardwareBuffer"
        projection_target = "Rusty Quest native Vulkan custom stereo projection"
        active_paths = [ordered]@{
            owner_sends = $ownerSends
            client_sends = $clientSends
            owner_receives = $ownerReceives
            client_receives = $clientReceives
            owner_relay_required = $ownerRelayRequired
            client_relay_required = $clientRelayRequired
            owner_receive_proxy_required = $ownerReceiveProxyRequired
            client_receive_proxy_required = $clientReceiveProxyRequired
            owner_renderer_required = $ownerRendererRequired
            client_renderer_required = $clientRendererRequired
            lane_mode = $LaneMode
            left_lane_active = $leftLaneActive
            right_lane_active = $rightLaneActive
            active_lane_count = $activeLaneCount
        }
    }
    owner_relay = $ownerQcl041.diagnostics.qcl082_relay
    owner_relay_left = $ownerQcl041.diagnostics.qcl082_relay_left
    owner_relay_right = $ownerQcl041.diagnostics.qcl082_relay_right
    owner_receive_proxy = $ownerQcl041.diagnostics.qcl082_receive_proxy
    owner_receive_proxy_left = $ownerQcl041.diagnostics.qcl082_receive_proxy_left
    owner_receive_proxy_right = $ownerQcl041.diagnostics.qcl082_receive_proxy_right
    client_relay = $clientQcl041.diagnostics.qcl082_relay
    client_relay_left = $clientQcl041.diagnostics.qcl082_relay_left
    client_relay_right = $clientQcl041.diagnostics.qcl082_relay_right
    client_receive_proxy = $clientQcl041.diagnostics.qcl082_receive_proxy
    client_receive_proxy_left = $clientQcl041.diagnostics.qcl082_receive_proxy_left
    client_receive_proxy_right = $clientQcl041.diagnostics.qcl082_receive_proxy_right
    owner_broker_status = $ownerBrokerStatus
    client_broker_status = $clientBrokerStatus
    final_status_probes = [ordered]@{
        owner = $ownerFinalStatusProbe
        client = $clientFinalStatusProbe
    }
    owner_camera_source_freshness = $ownerCameraSourceFreshness
    client_camera_source_freshness = $clientCameraSourceFreshness
    owner_relay_freshness = $ownerRelayFreshness
    client_relay_freshness = $clientRelayFreshness
    owner_receive_proxy_freshness = $ownerReceiveProxyFreshness
    client_receive_proxy_freshness = $clientReceiveProxyFreshness
    owner_broker_receiver_observed_freshness = $ownerBrokerReceiverObservedFreshness
    client_broker_receiver_observed_freshness = $clientBrokerReceiverObservedFreshness
    qcl082_media_topology_acceptance = $qcl082MediaTopologyAcceptance
    owner_native_renderer_projection = $ownerNativeRenderer
    client_native_renderer_projection = $clientNativeRenderer
    native_renderer_logcat_capture = [ordered]@{
        owner = $ownerNativeLogcatCaptureStop
        client = $clientNativeLogcatCaptureStop
    }
    wake_prep_policy = $wakePrepPolicy
    owner_wake_prep = $ownerWakePrep
    client_wake_prep = $clientWakePrep
    owner_xr_launch_readiness = $ownerXrReadiness
    client_xr_launch_readiness = $clientXrReadiness
    owner_final_focus = $ownerFinalFocus
    client_final_focus = $clientFinalFocus
    permission_pregrant_receipts = [ordered]@{
        owner_qcl_broker = $ownerQclPermission
        client_qcl_broker = $clientQclPermission
        owner_native_renderer = $ownerNativePermission
        client_native_renderer = $clientNativePermission
    }
    runtime_profile_plans = [ordered]@{
        owner_native_renderer = $ownerProfilePlan
        client_native_renderer = $clientProfilePlan
    }
    lane_mode_property_overrides = [ordered]@{
        owner_native_renderer = $ownerLaneModeOverride
        client_native_renderer = $clientLaneModeOverride
    }
    cleanup_policy = [ordered]@{
        final_force_stop_cleanup_skipped = [bool]$SkipCleanup
        reason = if ($SkipCleanup) { "skip_cleanup_preserve_final_xr_focus" } else { "summary_first_force_stop_cleanup" }
        force_stop_packages = if ($SkipCleanup) { @() } else { @($Qcl041Package, $BrokerPackage, $NativeRendererPackage) }
    }
    graceful_stop = [ordered]@{
        skipped = [bool]$SkipCleanup
        reason = if ($SkipCleanup) { "skip_cleanup_preserve_final_xr_focus" } else { "summary_first_force_stop_cleanup" }
    }
    replicated_old_topology_with_native_renderer = [ordered]@{
        manifold_broker_camera2_stereo_sources = $true
        binary_media_magic = "RMANVID1"
        direct_wifi_instead_of_online_relay = $true
        makepad_consumer = $false
        native_renderer_broker_inlet = $true
        decode_target = "MediaCodec Surface -> NDK AImageReader -> AHardwareBuffer"
        projection_target = "Native Rusty Quest Vulkan custom stereo projection"
    }
    evidence_dir = $OutDir
}

$summary["owner_projection_ready"] = [bool]($ownerNativeRenderer.projection_ready -and $ownerFinalFocus.focus_active)
$summary["client_projection_ready"] = [bool]($clientNativeRenderer.projection_ready -and $clientFinalFocus.focus_active)
$summary["projection_ready_both_headsets"] = [bool]($summary["owner_projection_ready"] -and $summary["client_projection_ready"])
$summary["owner_projection_required"] = [bool]$ownerRendererRequired
$summary["client_projection_required"] = [bool]$clientRendererRequired
$summary["active_receiver_projection_ready"] = [bool](
    ((-not $ownerRendererRequired) -or $summary["owner_projection_ready"]) -and
    ((-not $clientRendererRequired) -or $summary["client_projection_ready"])
)
$summary["freshness_acceptance"] = [ordered]@{
    required = "active_direction_paths_have_fresh_camera2_source_frames_fresh_qcl082_relay_bytes_app_bound_udp_or_control_tcp_qcl082_media_topology_fresh_broker_receiver_observed_bytes_and_active_receiver_renderer_eyes_have_sustained_advancing_AHardwareBuffer_frames_plus_scorecard_progression"
    direction = $Direction
    lane_mode = $LaneMode
    minimum_frame_span_seconds = $MinFreshFrameSpanSeconds
    minimum_frame_lines = $MinFreshFrameLines
    owner_camera_source_required = [bool]$ownerSends
    client_camera_source_required = [bool]$clientSends
    owner_relay_required = [bool]$ownerRelayRequired
    client_relay_required = [bool]$clientRelayRequired
    owner_receive_proxy_required = [bool]$ownerReceiveProxyRequired
    client_receive_proxy_required = [bool]$clientReceiveProxyRequired
    qcl041_matrix_gate_required = [bool]$effectiveRequireQcl041MatrixGatePass
    qcl041_matrix_gate_evaluated = [bool]$qcl041MatrixGateEvaluated
    qcl041_matrix_gate_artifact = $qcl041MatrixGatePath
    qcl041_matrix_gate_passed = [bool]$qcl041MatrixGatePassed
    qcl041_matrix_gate_passes_requirement = [bool]$qcl041MatrixGatePassesRequirement
    qcl041_matrix_gate_blocked_reason = $qcl041MatrixGateBlockedReason
    qcl041_matrix_run_id_pin_requires_gate = [bool]$qcl041MatrixRunIdPinRequiresGate
    required_qcl041_matrix_run_id = $RequiredQcl041MatrixRunId
    qcl041_matrix_gate_run_id = $qcl041MatrixGateRunId
    qcl041_matrix_gate_transport_protocol = $qcl041MatrixGateTransportProtocol
    qcl041_matrix_gate_required_topology = $qcl041MatrixGateRequiredTopology
    owner_broker_receiver_observed_required = [bool]$ownerReceives
    client_broker_receiver_observed_required = [bool]$clientReceives
    owner_stream_required = [bool]$ownerRendererRequired
    client_stream_required = [bool]$clientRendererRequired
    owner_camera_source_fresh = [bool]$ownerCameraSourceFreshness.fresh
    client_camera_source_fresh = [bool]$clientCameraSourceFreshness.fresh
    owner_relay_bytes_fresh = [bool]$ownerRelayFreshness.fresh
    client_relay_bytes_fresh = [bool]$clientRelayFreshness.fresh
    owner_receive_proxy_bytes_fresh = [bool]$ownerReceiveProxyFreshness.fresh
    client_receive_proxy_bytes_fresh = [bool]$clientReceiveProxyFreshness.fresh
    owner_broker_receiver_observed_bytes_fresh = [bool]$ownerBrokerReceiverObservedFreshness.fresh
    client_broker_receiver_observed_bytes_fresh = [bool]$clientBrokerReceiverObservedFreshness.fresh
    owner_broker_receiver_observed_lane_count = $ownerBrokerReceiverObservedFreshness.receiver_observed_lane_count
    client_broker_receiver_observed_lane_count = $clientBrokerReceiverObservedFreshness.receiver_observed_lane_count
    owner_broker_receiver_observed_fresh_lane_count = $ownerBrokerReceiverObservedFreshness.fresh_receiver_observed_lane_count
    client_broker_receiver_observed_fresh_lane_count = $clientBrokerReceiverObservedFreshness.fresh_receiver_observed_lane_count
    owner_broker_receiver_observed_byte_count = $ownerBrokerReceiverObservedFreshness.receiver_observed_byte_count
    client_broker_receiver_observed_byte_count = $clientBrokerReceiverObservedFreshness.receiver_observed_byte_count
    owner_relay_transport_protocols = $ownerRelayFreshness.transport_protocols
    client_relay_transport_protocols = $clientRelayFreshness.transport_protocols
    owner_receive_proxy_transport_protocols = $ownerReceiveProxyFreshness.transport_protocols
    client_receive_proxy_transport_protocols = $clientReceiveProxyFreshness.transport_protocols
    owner_relay_control_tcp_media_carrier_lane_count = $ownerRelayFreshness.control_tcp_media_carrier_lane_count
    client_relay_control_tcp_media_carrier_lane_count = $clientRelayFreshness.control_tcp_media_carrier_lane_count
    owner_receive_proxy_control_tcp_media_carrier_lane_count = $ownerReceiveProxyFreshness.control_tcp_media_carrier_lane_count
    client_receive_proxy_control_tcp_media_carrier_lane_count = $clientReceiveProxyFreshness.control_tcp_media_carrier_lane_count
    owner_relay_all_lanes_use_control_tcp_media_carrier = [bool]$ownerRelayFreshness.all_lanes_use_control_tcp_media_carrier
    client_relay_all_lanes_use_control_tcp_media_carrier = [bool]$clientRelayFreshness.all_lanes_use_control_tcp_media_carrier
    owner_receive_proxy_all_lanes_use_control_tcp_media_carrier = [bool]$ownerReceiveProxyFreshness.all_lanes_use_control_tcp_media_carrier
    client_receive_proxy_all_lanes_use_control_tcp_media_carrier = [bool]$clientReceiveProxyFreshness.all_lanes_use_control_tcp_media_carrier
    owner_relay_udp_media_lane_count = $ownerRelayFreshness.udp_media_lane_count
    client_relay_udp_media_lane_count = $clientRelayFreshness.udp_media_lane_count
    owner_receive_proxy_udp_media_lane_count = $ownerReceiveProxyFreshness.udp_media_lane_count
    client_receive_proxy_udp_media_lane_count = $clientReceiveProxyFreshness.udp_media_lane_count
    owner_relay_app_bound_udp_media_socket_lane_count = $ownerRelayFreshness.app_bound_udp_media_socket_lane_count
    client_relay_app_bound_udp_media_socket_lane_count = $clientRelayFreshness.app_bound_udp_media_socket_lane_count
    owner_receive_proxy_app_bound_udp_media_socket_lane_count = $ownerReceiveProxyFreshness.app_bound_udp_media_socket_lane_count
    client_receive_proxy_app_bound_udp_media_socket_lane_count = $clientReceiveProxyFreshness.app_bound_udp_media_socket_lane_count
    owner_relay_app_bound_udp_media_lane_count = $ownerRelayFreshness.app_bound_udp_media_lane_count
    client_relay_app_bound_udp_media_lane_count = $clientRelayFreshness.app_bound_udp_media_lane_count
    owner_receive_proxy_app_bound_udp_media_lane_count = $ownerReceiveProxyFreshness.app_bound_udp_media_lane_count
    client_receive_proxy_app_bound_udp_media_lane_count = $clientReceiveProxyFreshness.app_bound_udp_media_lane_count
    owner_relay_local_p2p_bound_udp_media_lane_count = $ownerRelayFreshness.local_p2p_bound_udp_media_lane_count
    client_relay_local_p2p_bound_udp_media_lane_count = $clientRelayFreshness.local_p2p_bound_udp_media_lane_count
    owner_receive_proxy_local_p2p_bound_udp_media_lane_count = $ownerReceiveProxyFreshness.local_p2p_bound_udp_media_lane_count
    client_receive_proxy_local_p2p_bound_udp_media_lane_count = $clientReceiveProxyFreshness.local_p2p_bound_udp_media_lane_count
    owner_relay_all_udp_lanes_use_app_bound_udp_media_socket = [bool]$ownerRelayFreshness.all_udp_lanes_use_app_bound_udp_media_socket
    client_relay_all_udp_lanes_use_app_bound_udp_media_socket = [bool]$clientRelayFreshness.all_udp_lanes_use_app_bound_udp_media_socket
    owner_receive_proxy_all_udp_lanes_use_app_bound_udp_media_socket = [bool]$ownerReceiveProxyFreshness.all_udp_lanes_use_app_bound_udp_media_socket
    client_receive_proxy_all_udp_lanes_use_app_bound_udp_media_socket = [bool]$clientReceiveProxyFreshness.all_udp_lanes_use_app_bound_udp_media_socket
    owner_relay_all_udp_lanes_use_app_bound_udp_media_lane = [bool]$ownerRelayFreshness.all_udp_lanes_use_app_bound_udp_media_lane
    client_relay_all_udp_lanes_use_app_bound_udp_media_lane = [bool]$clientRelayFreshness.all_udp_lanes_use_app_bound_udp_media_lane
    owner_receive_proxy_all_udp_lanes_use_app_bound_udp_media_lane = [bool]$ownerReceiveProxyFreshness.all_udp_lanes_use_app_bound_udp_media_lane
    client_receive_proxy_all_udp_lanes_use_app_bound_udp_media_lane = [bool]$clientReceiveProxyFreshness.all_udp_lanes_use_app_bound_udp_media_lane
    owner_relay_udp_lanes_missing_app_bound_udp_media_socket = $ownerRelayFreshness.udp_lanes_missing_app_bound_udp_media_socket
    client_relay_udp_lanes_missing_app_bound_udp_media_socket = $clientRelayFreshness.udp_lanes_missing_app_bound_udp_media_socket
    owner_receive_proxy_udp_lanes_missing_app_bound_udp_media_socket = $ownerReceiveProxyFreshness.udp_lanes_missing_app_bound_udp_media_socket
    client_receive_proxy_udp_lanes_missing_app_bound_udp_media_socket = $clientReceiveProxyFreshness.udp_lanes_missing_app_bound_udp_media_socket
    owner_relay_udp_lanes_missing_app_bound_udp_media_lane = $ownerRelayFreshness.udp_lanes_missing_app_bound_udp_media_lane
    client_relay_udp_lanes_missing_app_bound_udp_media_lane = $clientRelayFreshness.udp_lanes_missing_app_bound_udp_media_lane
    owner_receive_proxy_udp_lanes_missing_app_bound_udp_media_lane = $ownerReceiveProxyFreshness.udp_lanes_missing_app_bound_udp_media_lane
    client_receive_proxy_udp_lanes_missing_app_bound_udp_media_lane = $clientReceiveProxyFreshness.udp_lanes_missing_app_bound_udp_media_lane
    qcl082_media_topology_accepted = [bool]$qcl082MediaTopologyAcceptance.accepted
    qcl082_media_topology_required_path_count = $qcl082MediaTopologyAcceptance.required_path_count
    qcl082_media_topology_accepted_path_count = $qcl082MediaTopologyAcceptance.accepted_path_count
    qcl082_media_topology_rejected_path_count = $qcl082MediaTopologyAcceptance.rejected_path_count
    qcl082_media_topology_required_transport_topology = $qcl082MediaTopologyAcceptance.required_transport_topology
    qcl082_media_topology_modes = $qcl082MediaTopologyAcceptance.modes
    qcl082_media_topology_accepted_modes = $qcl082MediaTopologyAcceptance.accepted_modes
    qcl082_media_topology_control_tcp_media_carrier_path_count = $qcl082MediaTopologyAcceptance.control_tcp_media_carrier_path_count
    qcl082_media_topology_app_bound_udp_media_path_count = $qcl082MediaTopologyAcceptance.app_bound_udp_media_path_count
    qcl082_media_topology_local_p2p_udp_media_rejected_path_count = $qcl082MediaTopologyAcceptance.local_p2p_udp_media_rejected_path_count
    qcl082_media_topology_unsupported_path_count = $qcl082MediaTopologyAcceptance.unsupported_media_topology_path_count
    qcl082_media_topology_first_issue = $qcl082MediaTopologyAcceptance.first_issue
    qcl082_media_topology_issues = $qcl082MediaTopologyAcceptance.issues
    owner_stream_fresh_frames = [bool]$ownerNativeRenderer.stream_fresh_frames
    client_stream_fresh_frames = [bool]$clientNativeRenderer.stream_fresh_frames
    owner_scorecard_fresh_frames = [bool]$ownerNativeRenderer.scorecard_fresh_frames
    client_scorecard_fresh_frames = [bool]$clientNativeRenderer.scorecard_fresh_frames
    owner_camera_source_passed = [bool]((-not $ownerSends) -or $ownerCameraSourceFreshness.fresh)
    client_camera_source_passed = [bool]((-not $clientSends) -or $clientCameraSourceFreshness.fresh)
    owner_relay_passed = [bool]((-not $ownerRelayRequired) -or $ownerRelayFreshness.fresh)
    client_relay_passed = [bool]((-not $clientRelayRequired) -or $clientRelayFreshness.fresh)
    owner_receive_proxy_passed = [bool]((-not $ownerReceiveProxyRequired) -or $ownerReceiveProxyFreshness.fresh)
    client_receive_proxy_passed = [bool]((-not $clientReceiveProxyRequired) -or $clientReceiveProxyFreshness.fresh)
    owner_broker_receiver_observed_passed = [bool]((-not $ownerReceives) -or $ownerBrokerReceiverObservedFreshness.fresh)
    client_broker_receiver_observed_passed = [bool]((-not $clientReceives) -or $clientBrokerReceiverObservedFreshness.fresh)
    qcl082_media_topology_passed = [bool]$qcl082MediaTopologyAcceptance.accepted
    owner_stream_passed = [bool]((-not $ownerRendererRequired) -or $ownerNativeRenderer.stream_fresh_frames)
    client_stream_passed = [bool]((-not $clientRendererRequired) -or $clientNativeRenderer.stream_fresh_frames)
    owner_scorecard_passed = [bool]((-not $ownerRendererRequired) -or $ownerNativeRenderer.scorecard_fresh_frames)
    client_scorecard_passed = [bool]((-not $clientRendererRequired) -or $clientNativeRenderer.scorecard_fresh_frames)
    owner_camera_source = $ownerCameraSourceFreshness
    client_camera_source = $clientCameraSourceFreshness
    owner_relay = $ownerRelayFreshness
    client_relay = $clientRelayFreshness
    owner_receive_proxy = $ownerReceiveProxyFreshness
    client_receive_proxy = $clientReceiveProxyFreshness
    owner_broker_receiver_observed = $ownerBrokerReceiverObservedFreshness
    client_broker_receiver_observed = $clientBrokerReceiverObservedFreshness
    qcl082_media_topology = $qcl082MediaTopologyAcceptance
    owner_left = $ownerNativeRenderer.left_frame_freshness
    owner_right = $ownerNativeRenderer.right_frame_freshness
    owner_left_scorecard = $ownerNativeRenderer.left_scorecard_freshness
    owner_right_scorecard = $ownerNativeRenderer.right_scorecard_freshness
    client_left = $clientNativeRenderer.left_frame_freshness
    client_right = $clientNativeRenderer.right_frame_freshness
    client_left_scorecard = $clientNativeRenderer.left_scorecard_freshness
    client_right_scorecard = $clientNativeRenderer.right_scorecard_freshness
    passed = [bool](
        $qcl041MatrixGatePassesRequirement -and
        ((-not $ownerSends) -or $ownerCameraSourceFreshness.fresh) -and
        ((-not $clientSends) -or $clientCameraSourceFreshness.fresh) -and
        ((-not $ownerRelayRequired) -or $ownerRelayFreshness.fresh) -and
        ((-not $clientRelayRequired) -or $clientRelayFreshness.fresh) -and
        ((-not $ownerReceiveProxyRequired) -or $ownerReceiveProxyFreshness.fresh) -and
        ((-not $clientReceiveProxyRequired) -or $clientReceiveProxyFreshness.fresh) -and
        ((-not $ownerReceives) -or $ownerBrokerReceiverObservedFreshness.fresh) -and
        ((-not $clientReceives) -or $clientBrokerReceiverObservedFreshness.fresh) -and
        $qcl082MediaTopologyAcceptance.accepted -and
        ((-not $ownerRendererRequired) -or $ownerNativeRenderer.stream_fresh_frames) -and
        ((-not $clientRendererRequired) -or $clientNativeRenderer.stream_fresh_frames) -and
        ((-not $ownerRendererRequired) -or $ownerNativeRenderer.scorecard_fresh_frames) -and
        ((-not $clientRendererRequired) -or $clientNativeRenderer.scorecard_fresh_frames)
    )
}
$summary["transport_claims"] = New-Qcl100TransportClaims `
    -Direction $Direction `
    -FreshnessAcceptance $summary["freshness_acceptance"] `
    -MediaTopologyAcceptance $qcl082MediaTopologyAcceptance
$summary["same_group_duplex_claimed"] = [bool]$summary["transport_claims"]["same_group_duplex_claimed"]
$qcl100ParityBlockers = [System.Collections.ArrayList]::new()
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "qcl041_matrix_gate" `
    -Required ([bool]$effectiveRequireQcl041MatrixGatePass) `
    -Passed ([bool]$qcl041MatrixGatePassesRequirement) `
    -Reason "qcl041_matrix_gate_failed_or_missing" `
    -Details ([ordered]@{
        artifact = $qcl041MatrixGatePath
        blocked_reason = $qcl041MatrixGateBlockedReason
        run_id = $qcl041MatrixGateRunId
        required_run_id = $RequiredQcl041MatrixRunId
    })
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "owner_camera_source" `
    -Required ([bool]$ownerSends) `
    -Passed ([bool]$ownerCameraSourceFreshness.fresh) `
    -Reason "owner_camera_source_not_fresh"
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "client_camera_source" `
    -Required ([bool]$clientSends) `
    -Passed ([bool]$clientCameraSourceFreshness.fresh) `
    -Reason "client_camera_source_not_fresh"
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "owner_qcl082_relay" `
    -Required ([bool]$ownerRelayRequired) `
    -Passed ([bool]$ownerRelayFreshness.fresh) `
    -Reason "owner_qcl082_relay_bytes_not_fresh"
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "client_qcl082_relay" `
    -Required ([bool]$clientRelayRequired) `
    -Passed ([bool]$clientRelayFreshness.fresh) `
    -Reason "client_qcl082_relay_bytes_not_fresh"
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "owner_qcl082_receive_proxy" `
    -Required ([bool]$ownerReceiveProxyRequired) `
    -Passed ([bool]$ownerReceiveProxyFreshness.fresh) `
    -Reason "owner_qcl082_receive_proxy_bytes_not_fresh"
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "client_qcl082_receive_proxy" `
    -Required ([bool]$clientReceiveProxyRequired) `
    -Passed ([bool]$clientReceiveProxyFreshness.fresh) `
    -Reason "client_qcl082_receive_proxy_bytes_not_fresh"
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "owner_broker_receiver_observed" `
    -Required ([bool]$ownerReceives) `
    -Passed ([bool]$ownerBrokerReceiverObservedFreshness.fresh) `
    -Reason "owner_broker_receiver_observed_bytes_not_fresh"
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "client_broker_receiver_observed" `
    -Required ([bool]$clientReceives) `
    -Passed ([bool]$clientBrokerReceiverObservedFreshness.fresh) `
    -Reason "client_broker_receiver_observed_bytes_not_fresh"
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "qcl082_media_topology" `
    -Required $true `
    -Passed ([bool]$qcl082MediaTopologyAcceptance.accepted) `
    -Reason "qcl082_media_topology_not_accepted" `
    -Details ([ordered]@{
        required_path_count = $qcl082MediaTopologyAcceptance.required_path_count
        accepted_path_count = $qcl082MediaTopologyAcceptance.accepted_path_count
        rejected_path_count = $qcl082MediaTopologyAcceptance.rejected_path_count
        issues = $qcl082MediaTopologyAcceptance.issues
    })
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "owner_native_renderer_stream" `
    -Required ([bool]$ownerRendererRequired) `
    -Passed ([bool]$ownerNativeRenderer.stream_fresh_frames) `
    -Reason "owner_native_renderer_stream_frames_not_fresh"
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "client_native_renderer_stream" `
    -Required ([bool]$clientRendererRequired) `
    -Passed ([bool]$clientNativeRenderer.stream_fresh_frames) `
    -Reason "client_native_renderer_stream_frames_not_fresh"
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "owner_native_renderer_scorecard" `
    -Required ([bool]$ownerRendererRequired) `
    -Passed ([bool]$ownerNativeRenderer.scorecard_fresh_frames) `
    -Reason "owner_native_renderer_scorecard_frames_not_fresh"
Add-Qcl100ParityBlocker `
    -Blockers $qcl100ParityBlockers `
    -Gate "client_native_renderer_scorecard" `
    -Required ([bool]$clientRendererRequired) `
    -Passed ([bool]$clientNativeRenderer.scorecard_fresh_frames) `
    -Reason "client_native_renderer_scorecard_frames_not_fresh"
Set-Qcl100ParityBlockers -FreshnessAcceptance $summary["freshness_acceptance"] -Blockers $qcl100ParityBlockers
$summaryPath = Join-Path $OutDir "native-stereo-projection-summary.json"
Write-JsonFile -Value $summary -Path $summaryPath

if (-not $SkipCleanup) {
    Stop-Qcl100DeviceApps -Serials @($OwnerSerial, $ClientSerial)
}

Get-Content -Raw $summaryPath
if (-not $summary["freshness_acceptance"].passed) {
    exit 2
}
