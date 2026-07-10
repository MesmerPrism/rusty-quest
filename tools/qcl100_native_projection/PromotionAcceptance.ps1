function Get-Qcl100PromotionNestedValue {
    param(
        $Object,
        [string[]]$Path
    )

    $value = $Object
    foreach ($part in $Path) {
        if ($null -eq $value) {
            return $null
        }
        if ($value -is [System.Collections.IDictionary]) {
            if (-not $value.Contains($part)) {
                return $null
            }
            $value = $value[$part]
            continue
        }
        $property = $value.PSObject.Properties[$part]
        if ($null -eq $property) {
            return $null
        }
        $value = $property.Value
    }
    return $value
}

function New-Qcl100PromotionAcceptance {
    param(
        $FinalSummary,
        [string]$Direction,
        [string]$LaneMode,
        [bool]$RunnerCompleted,
        $SettingsLifecycleAcceptance,
        $CrashLifecycleAcceptance,
        [bool]$CleanupReadbackClean,
        $FinalRouteClearAcceptance,
        [object[]]$Qcl041DeviceArtifacts,
        [bool]$SettingsFenceRequested,
        [string]$SettingsFenceTarget,
        [object[]]$SettingsFenceReceipts
    )

    $issues = @()
    $mediaLayout = [string](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("media_layout"))
    $expectedReceiverLaneCount = if ($mediaLayout -eq "side-by-side-left-right") { 1 } else { 2 }
    if (-not $RunnerCompleted) {
        $issues += "runner_not_completed"
    }
    if ($null -eq $FinalSummary) {
        $issues += "native_summary_absent"
    }

    $summaryDirection = [string](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("direction"))
    $summaryLaneMode = [string](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("lane_mode"))
    $transportOwner = [string](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("transport_owner"))
    if ([string]::IsNullOrWhiteSpace($transportOwner)) {
        $transportOwner = [string](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("topology", "transport_owner"))
    }
    $authority = [string](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("qcl100_lower_gate_authority"))
    if ([string]::IsNullOrWhiteSpace($authority)) {
        $authority = [string](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("topology", "qcl100_lower_gate_authority"))
    }

    if ($Direction -ne "duplex" -or $summaryDirection -ne "duplex") {
        $issues += "direction_not_duplex"
    }
    if ($LaneMode -ne "stereo" -or $summaryLaneMode -ne "stereo") {
        $issues += "lane_mode_not_stereo"
    }
    if ($transportOwner -ne "broker") {
        $issues += "transport_owner_not_broker"
    }
    if ($authority -ne "rusty_direct_p2p_socket_authority") {
        $issues += "lower_gate_authority_not_rusty_direct_p2p_socket_authority"
    }

    foreach ($requirement in @(
            @{ path = @("preflight", "infrastructure_wifi_disconnected"); issue = "preflight_infrastructure_wifi_not_disconnected" },
            @{ path = @("preflight", "p2p0_ipv4_cleared"); issue = "preflight_p2p0_not_clear" },
            @{ path = @("preflight", "candidate_wifi_direct_prelaunch_routes_clear"); issue = "preflight_candidate_routes_not_clear" },
            @{ path = @("qcl041_matrix_gate", "passed"); issue = "qcl041_matrix_gate_not_passed" },
            @{ path = @("direct_p2p_address_refresh", "ready"); issue = "direct_p2p_address_refresh_not_ready" },
            @{ path = @("direct_p2p_media_topology_acceptance", "accepted"); issue = "direct_p2p_media_topology_not_accepted" },
            @{ path = @("owner_direct_p2p_sender_authority", "accepted"); issue = "owner_direct_p2p_sender_authority_not_accepted" },
            @{ path = @("client_direct_p2p_sender_authority", "accepted"); issue = "client_direct_p2p_sender_authority_not_accepted" },
            @{ path = @("owner_direct_p2p_receiver_authority", "accepted"); issue = "owner_direct_p2p_receiver_authority_not_accepted" },
            @{ path = @("client_direct_p2p_receiver_authority", "accepted"); issue = "client_direct_p2p_receiver_authority_not_accepted" },
            @{ path = @("freshness_acceptance", "passed"); issue = "freshness_acceptance_not_passed" },
            @{ path = @("projection_ready_both_headsets"); issue = "projection_not_ready_both_headsets" },
            @{ path = @("active_receiver_projection_ready"); issue = "active_receiver_projection_not_ready" },
            @{ path = @("transport_claims", "same_group_duplex_claimed"); issue = "child_same_group_duplex_not_claimed" },
            @{ path = @("transport_claims", "same_group_rusty_direct_p2p_socket_authority_duplex_claimed"); issue = "child_rusty_direct_p2p_duplex_not_claimed" }
        )) {
        if (-not [bool](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path $requirement.path)) {
            $issues += $requirement.issue
        }
    }

    $transportStatus = [string](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("transport_claims", "status"))
    if ($transportStatus -ne "same_group_duplex_proven_with_rusty_direct_p2p_socket_authority") {
        $issues += "transport_claim_status_not_rusty_direct_p2p_duplex"
    }
    $requiredPathCount = [int](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("direct_p2p_media_topology_acceptance", "required_path_count"))
    $acceptedPathCount = [int](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("direct_p2p_media_topology_acceptance", "accepted_path_count"))
    $rejectedPathCount = [int](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("direct_p2p_media_topology_acceptance", "rejected_path_count"))
    if ($requiredPathCount -ne 2) {
        $issues += "direct_p2p_required_direction_path_count_not_two"
    }
    if ($acceptedPathCount -ne $requiredPathCount -or $acceptedPathCount -ne 2 -or $rejectedPathCount -ne 0) {
        $issues += "direct_p2p_two_direction_paths_not_accepted"
    }

    $parityBlockerCount = [int](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("freshness_acceptance", "parity_blocker_count"))
    if ($parityBlockerCount -ne 0) {
        $issues += "freshness_parity_blockers_present"
    }
    $nativeFatalCount = [int](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("freshness_acceptance", "native_log_fatal_count"))
    $systemFatalCount = [int](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("freshness_acceptance", "native_log_system_fatal_count"))
    if ($nativeFatalCount -ne 0 -or $systemFatalCount -ne 0) {
        $issues += "native_or_system_fatal_present"
    }
    foreach ($side in @("owner", "client")) {
        $freshLaneCount = [int](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("freshness_acceptance", "${side}_broker_receiver_observed_fresh_lane_count"))
        $byteCount = [int64](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("freshness_acceptance", "${side}_broker_receiver_observed_byte_count"))
        $streamFresh = [bool](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("freshness_acceptance", "${side}_stream_fresh_frames"))
        $scorecardFresh = [bool](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("freshness_acceptance", "${side}_scorecard_fresh_frames"))
        if ($freshLaneCount -ne $expectedReceiverLaneCount) {
            $issues += "${side}_receiver_fresh_lane_count_not_expected_layout_count"
        }
        if ($byteCount -le 0) {
            $issues += "${side}_receiver_observed_bytes_not_positive"
        }
        if (-not $streamFresh) {
            $issues += "${side}_renderer_stream_not_fresh"
        }
        if (-not $scorecardFresh) {
            $issues += "${side}_renderer_scorecard_not_fresh"
        }
    }

    if ([bool](Get-Qcl100PromotionNestedValue -Object $FinalSummary -Path @("promotion_claimed"))) {
        $issues += "child_runner_claimed_promotion"
    }
    if (-not [bool](Get-Qcl100PromotionNestedValue -Object $SettingsLifecycleAcceptance -Path @("passed"))) {
        $issues += "settings_lifecycle_acceptance_not_passed"
    }
    if (-not [bool](Get-Qcl100PromotionNestedValue -Object $CrashLifecycleAcceptance -Path @("passed"))) {
        $issues += "crash_lifecycle_acceptance_not_passed"
    }
    if (-not $CleanupReadbackClean) {
        $issues += "cleanup_readback_not_clean"
    }
    if (-not [bool](Get-Qcl100PromotionNestedValue -Object $FinalRouteClearAcceptance -Path @("accepted"))) {
        $issues += "final_route_clear_not_accepted"
    }

    if (-not $SettingsFenceRequested) {
        $issues += "settings_fence_not_requested"
    }
    if ($SettingsFenceTarget -ne "both") {
        $issues += "settings_fence_target_not_both"
    }
    $settingsReceiptLabels = @()
    foreach ($receipt in @($SettingsFenceReceipts)) {
        if ([bool](Get-Qcl100PromotionNestedValue -Object $receipt -Path @("passed")) -and
            [bool](Get-Qcl100PromotionNestedValue -Object $receipt -Path @("foreground_not_settings")) -and
            [bool](Get-Qcl100PromotionNestedValue -Object $receipt -Path @("ready_for_qcl041_group_formation"))) {
            $settingsReceiptLabels += [string](Get-Qcl100PromotionNestedValue -Object $receipt -Path @("label"))
        }
    }
    if (@($settingsReceiptLabels | Sort-Object -Unique).Count -ne 2 -or
        $settingsReceiptLabels -notcontains "owner" -or
        $settingsReceiptLabels -notcontains "client") {
        $issues += "settings_fence_receipts_not_passed_for_both"
    }

    $qcl041Labels = @()
    foreach ($artifact in @($Qcl041DeviceArtifacts)) {
        if ([string](Get-Qcl100PromotionNestedValue -Object $artifact -Path @("parse_status")) -eq "pass" -and
            [bool](Get-Qcl100PromotionNestedValue -Object $artifact -Path @("artifact_present")) -and
            [string](Get-Qcl100PromotionNestedValue -Object $artifact -Path @("group_formation_status")) -eq "pass") {
            $qcl041Labels += [string](Get-Qcl100PromotionNestedValue -Object $artifact -Path @("label"))
        }
    }
    if (@($qcl041Labels | Sort-Object -Unique).Count -ne 2 -or
        $qcl041Labels -notcontains "owner" -or
        $qcl041Labels -notcontains "client") {
        $issues += "qcl041_post_run_artifacts_not_read_for_both"
    }

    $issues = @($issues | Select-Object -Unique)
    $accepted = [bool]($issues.Count -eq 0)
    [ordered]@{
        schema = "rusty.quest.qcl100_monitored_promotion_acceptance.v1"
        status = if ($accepted) {
            "promoted_same_group_full_stereo_duplex_with_rusty_direct_p2p_socket_authority"
        } else {
            "blocked_not_promoting"
        }
        accepted = $accepted
        same_group_duplex_claimed = $accepted
        promotion_claimed = $accepted
        normalized_reason = if ($accepted) {
            "all_monitored_full_stereo_direct_p2p_promotion_gates_passed"
        } else {
            [string]$issues[0]
        }
        issue_count = $issues.Count
        issues = $issues
        direction = $summaryDirection
        lane_mode = $summaryLaneMode
        media_layout = $mediaLayout
        expected_receiver_lane_count = $expectedReceiverLaneCount
        transport_owner = $transportOwner
        qcl100_lower_gate_authority = $authority
        required_direct_p2p_path_count = $requiredPathCount
        accepted_direct_p2p_path_count = $acceptedPathCount
        rejected_direct_p2p_path_count = $rejectedPathCount
        native_log_fatal_count = $nativeFatalCount
        native_log_system_fatal_count = $systemFatalCount
        settings_lifecycle_accepted = [bool](Get-Qcl100PromotionNestedValue -Object $SettingsLifecycleAcceptance -Path @("passed"))
        crash_lifecycle_accepted = [bool](Get-Qcl100PromotionNestedValue -Object $CrashLifecycleAcceptance -Path @("passed"))
        cleanup_readback_clean = $CleanupReadbackClean
        final_route_clear_accepted = [bool](Get-Qcl100PromotionNestedValue -Object $FinalRouteClearAcceptance -Path @("accepted"))
        settings_fence_passed_both = [bool]($settingsReceiptLabels -contains "owner" -and $settingsReceiptLabels -contains "client")
        qcl041_artifacts_read_both = [bool]($qcl041Labels -contains "owner" -and $qcl041Labels -contains "client")
        final_promotion_authority = "qcl100_monitored_promotion_acceptance"
    }
}

function Assert-Qcl100PromotionSelfTest {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        throw "QCL100 promotion self-test failed: $Message"
    }
}

function Copy-Qcl100PromotionFixture {
    param($Value)
    return ($Value | ConvertTo-Json -Depth 32 | ConvertFrom-Json)
}

function Invoke-Qcl100PromotionAcceptanceSelfTest {
    $summary = [ordered]@{
        direction = "duplex"
        lane_mode = "stereo"
        transport_owner = "broker"
        qcl100_lower_gate_authority = "rusty_direct_p2p_socket_authority"
        promotion_claimed = $false
        preflight = [ordered]@{
            infrastructure_wifi_disconnected = $true
            p2p0_ipv4_cleared = $true
            candidate_wifi_direct_prelaunch_routes_clear = $true
        }
        qcl041_matrix_gate = [ordered]@{ passed = $true }
        direct_p2p_address_refresh = [ordered]@{ ready = $true }
        direct_p2p_media_topology_acceptance = [ordered]@{
            accepted = $true
            required_path_count = 2
            accepted_path_count = 2
            rejected_path_count = 0
        }
        owner_direct_p2p_sender_authority = [ordered]@{ accepted = $true }
        client_direct_p2p_sender_authority = [ordered]@{ accepted = $true }
        owner_direct_p2p_receiver_authority = [ordered]@{ accepted = $true }
        client_direct_p2p_receiver_authority = [ordered]@{ accepted = $true }
        projection_ready_both_headsets = $true
        active_receiver_projection_ready = $true
        transport_claims = [ordered]@{
            status = "same_group_duplex_proven_with_rusty_direct_p2p_socket_authority"
            same_group_duplex_claimed = $true
            same_group_rusty_direct_p2p_socket_authority_duplex_claimed = $true
        }
        freshness_acceptance = [ordered]@{
            passed = $true
            parity_blocker_count = 0
            native_log_fatal_count = 0
            native_log_system_fatal_count = 0
            owner_broker_receiver_observed_fresh_lane_count = 2
            client_broker_receiver_observed_fresh_lane_count = 2
            owner_broker_receiver_observed_byte_count = 4194304
            client_broker_receiver_observed_byte_count = 4194304
            owner_stream_fresh_frames = $true
            client_stream_fresh_frames = $true
            owner_scorecard_fresh_frames = $true
            client_scorecard_fresh_frames = $true
        }
    }
    $settingsLifecycle = [ordered]@{ passed = $true }
    $crash = [ordered]@{ passed = $true }
    $route = [ordered]@{ accepted = $true }
    $qcl041 = @(
        [ordered]@{ label = "owner"; parse_status = "pass"; artifact_present = $true; group_formation_status = "pass" },
        [ordered]@{ label = "client"; parse_status = "pass"; artifact_present = $true; group_formation_status = "pass" }
    )
    $settings = @(
        [ordered]@{ label = "owner"; passed = $true; foreground_not_settings = $true; ready_for_qcl041_group_formation = $true },
        [ordered]@{ label = "client"; passed = $true; foreground_not_settings = $true; ready_for_qcl041_group_formation = $true }
    )
    $baseParams = @{
        Direction = "duplex"
        LaneMode = "stereo"
        RunnerCompleted = $true
        SettingsLifecycleAcceptance = $settingsLifecycle
        CrashLifecycleAcceptance = $crash
        CleanupReadbackClean = $true
        FinalRouteClearAcceptance = $route
        Qcl041DeviceArtifacts = $qcl041
        SettingsFenceRequested = $true
        SettingsFenceTarget = "both"
        SettingsFenceReceipts = $settings
    }

    $accepted = New-Qcl100PromotionAcceptance -FinalSummary $summary @baseParams
    Assert-Qcl100PromotionSelfTest -Condition ([bool]$accepted.accepted) -Message "complete full-stereo direct-P2P fixture must promote"
    Assert-Qcl100PromotionSelfTest -Condition ([bool]$accepted.promotion_claimed) -Message "accepted fixture must claim promotion"

    $leftOnlySummary = Copy-Qcl100PromotionFixture -Value $summary
    $leftOnlySummary.lane_mode = "left-only"
    $leftOnlyParams = $baseParams.Clone()
    $leftOnlyParams["LaneMode"] = "left-only"
    $leftOnly = New-Qcl100PromotionAcceptance -FinalSummary $leftOnlySummary @leftOnlyParams
    Assert-Qcl100PromotionSelfTest -Condition (-not [bool]$leftOnly.accepted) -Message "left-only fixture must not promote"
    Assert-Qcl100PromotionSelfTest -Condition (@($leftOnly.issues) -contains "lane_mode_not_stereo") -Message "left-only fixture must report lane blocker"

    $routeBlockedParams = $baseParams.Clone()
    $routeBlockedParams["FinalRouteClearAcceptance"] = [ordered]@{ accepted = $false }
    $routeBlocked = New-Qcl100PromotionAcceptance -FinalSummary $summary @routeBlockedParams
    Assert-Qcl100PromotionSelfTest -Condition (@($routeBlocked.issues) -contains "final_route_clear_not_accepted") -Message "route-clear damage must block promotion"

    $lifecycleBlockedParams = $baseParams.Clone()
    $lifecycleBlockedParams["CrashLifecycleAcceptance"] = [ordered]@{ passed = $false }
    $lifecycleBlocked = New-Qcl100PromotionAcceptance -FinalSummary $summary @lifecycleBlockedParams
    Assert-Qcl100PromotionSelfTest -Condition (@($lifecycleBlocked.issues) -contains "crash_lifecycle_acceptance_not_passed") -Message "lifecycle damage must block promotion"

    $settingsLifecycleBlockedParams = $baseParams.Clone()
    $settingsLifecycleBlockedParams["SettingsLifecycleAcceptance"] = [ordered]@{ passed = $false }
    $settingsLifecycleBlocked = New-Qcl100PromotionAcceptance -FinalSummary $summary @settingsLifecycleBlockedParams
    Assert-Qcl100PromotionSelfTest -Condition (@($settingsLifecycleBlocked.issues) -contains "settings_lifecycle_acceptance_not_passed") -Message "Settings-window lifecycle damage must block promotion"

    $receiverBlockedSummary = Copy-Qcl100PromotionFixture -Value $summary
    $receiverBlockedSummary.client_direct_p2p_receiver_authority.accepted = $false
    $receiverBlocked = New-Qcl100PromotionAcceptance -FinalSummary $receiverBlockedSummary @baseParams
    Assert-Qcl100PromotionSelfTest -Condition (@($receiverBlocked.issues) -contains "client_direct_p2p_receiver_authority_not_accepted") -Message "receiver authority damage must block promotion"

    $directionPathBlockedSummary = Copy-Qcl100PromotionFixture -Value $summary
    $directionPathBlockedSummary.direct_p2p_media_topology_acceptance.accepted_path_count = 1
    $directionPathBlockedSummary.direct_p2p_media_topology_acceptance.rejected_path_count = 1
    $directionPathBlocked = New-Qcl100PromotionAcceptance -FinalSummary $directionPathBlockedSummary @baseParams
    Assert-Qcl100PromotionSelfTest -Condition (@($directionPathBlocked.issues) -contains "direct_p2p_two_direction_paths_not_accepted") -Message "missing direct-P2P direction path must block promotion"

    $settingsBlockedParams = $baseParams.Clone()
    $settingsBlockedParams["SettingsFenceTarget"] = "owner"
    $settingsBlocked = New-Qcl100PromotionAcceptance -FinalSummary $summary @settingsBlockedParams
    Assert-Qcl100PromotionSelfTest -Condition (@($settingsBlocked.issues) -contains "settings_fence_target_not_both") -Message "single-device Settings fence must block promotion"

    $childClaimSummary = Copy-Qcl100PromotionFixture -Value $summary
    $childClaimSummary.promotion_claimed = $true
    $childClaimBlocked = New-Qcl100PromotionAcceptance -FinalSummary $childClaimSummary @baseParams
    Assert-Qcl100PromotionSelfTest -Condition (@($childClaimBlocked.issues) -contains "child_runner_claimed_promotion") -Message "child promotion claim must fail closed"

    [ordered]@{
        schema = "rusty.quest.qcl100_monitored_promotion_acceptance_self_test.v1"
        status = "pass"
        fixture_count = 9
        accepted_status = $accepted.status
        damaged_cases = @(
            "left_only",
            "final_route_clear_not_accepted",
            "system_lifecycle_changed",
            "settings_window_system_lifecycle_changed",
            "receiver_authority_not_accepted",
            "direct_p2p_direction_path_not_accepted",
            "settings_fence_not_both",
            "child_promotion_claimed"
        )
    }
}
