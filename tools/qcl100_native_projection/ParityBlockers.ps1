# Dot-sourced helper functions for QCL100 parity blocker diagnostics.

function Add-Qcl100ParityBlocker {
    param(
        [System.Collections.ArrayList]$Blockers,
        [string]$Gate,
        [bool]$Required,
        [bool]$Passed,
        [string]$Reason,
        $Details = $null
    )
    if ($Required -and -not $Passed) {
        $entry = [ordered]@{
            gate = $Gate
            required = [bool]$Required
            passed = [bool]$Passed
            reason = $Reason
        }
        if ($null -ne $Details) {
            $entry.details = $Details
        }
        [void]$Blockers.Add($entry)
    }
}

function Set-Qcl100ParityBlockers {
    param(
        $FreshnessAcceptance,
        [System.Collections.ArrayList]$Blockers
    )
    if ($null -eq $FreshnessAcceptance) {
        return
    }
    if ($null -eq $Blockers) {
        $Blockers = [System.Collections.ArrayList]::new()
    }
    $FreshnessAcceptance["parity_blocker_count"] = $Blockers.Count
    $FreshnessAcceptance["first_parity_blocker"] = if ($Blockers.Count -gt 0) { $Blockers[0].gate } else { "" }
    $FreshnessAcceptance["parity_blockers"] = @($Blockers)
}

function New-Qcl100PreflightParityBlockers {
    param(
        $AirgapPreflight,
        [bool]$RequireInfrastructureWifiDisconnected,
        [bool]$RequireP2p0Ipv4Cleared,
        [bool]$RequireCandidateWifiDirectRoutesClear
    )
    $blockers = [System.Collections.ArrayList]::new()
    if ($null -eq $AirgapPreflight) {
        Add-Qcl100ParityBlocker `
            -Blockers $blockers `
            -Gate "qcl100_airgap_preflight" `
            -Required ([bool]($RequireInfrastructureWifiDisconnected -or $RequireP2p0Ipv4Cleared -or $RequireCandidateWifiDirectRoutesClear)) `
            -Passed $false `
            -Reason "airgap_preflight_not_run"
        return ,$blockers
    }
    Add-Qcl100ParityBlocker `
        -Blockers $blockers `
        -Gate "infrastructure_wifi_airgap" `
        -Required $RequireInfrastructureWifiDisconnected `
        -Passed ([bool]$AirgapPreflight.infrastructure_wifi_disconnected) `
        -Reason "infrastructure_wifi_connected" `
        -Details ([ordered]@{
            owner_infrastructure_connected = [bool]$AirgapPreflight.owner_wifi.infrastructure_connected
            client_infrastructure_connected = [bool]$AirgapPreflight.client_wifi.infrastructure_connected
        })
    Add-Qcl100ParityBlocker `
        -Blockers $blockers `
        -Gate "wifi_direct_p2p0_ipv4_preflight" `
        -Required $RequireP2p0Ipv4Cleared `
        -Passed ([bool]$AirgapPreflight.p2p0_ipv4_cleared) `
        -Reason "p2p0_ipv4_present" `
        -Details ([ordered]@{
            owner_p2p0_ipv4_present = [bool]$AirgapPreflight.owner_p2p0.ipv4_present
            owner_p2p0_ipv4_address = $AirgapPreflight.owner_p2p0.ipv4_address
            client_p2p0_ipv4_present = [bool]$AirgapPreflight.client_p2p0.ipv4_present
            client_p2p0_ipv4_address = $AirgapPreflight.client_p2p0.ipv4_address
        })
    Add-Qcl100ParityBlocker `
        -Blockers $blockers `
        -Gate "wifi_direct_candidate_route_preflight" `
        -Required $RequireCandidateWifiDirectRoutesClear `
        -Passed ([bool]$AirgapPreflight.candidate_wifi_direct_prelaunch_routes_clear) `
        -Reason "candidate_wifi_direct_routes_not_clear" `
        -Details ([ordered]@{
            candidate_wifi_direct_route_count = $AirgapPreflight.candidate_wifi_direct_route_count
            routes_using_wlan0 = $AirgapPreflight.candidate_wifi_direct_routes_using_wlan0
            routes_using_p2p0 = $AirgapPreflight.candidate_wifi_direct_routes_using_p2p0
            routes_using_loopback = $AirgapPreflight.candidate_wifi_direct_routes_using_loopback
            local_self_routes = $AirgapPreflight.candidate_wifi_direct_local_self_routes
            unreachable_routes = $AirgapPreflight.candidate_wifi_direct_routes_unreachable
            reachable_routes = $AirgapPreflight.candidate_wifi_direct_routes_reachable
        })
    return ,$blockers
}

function New-Qcl100SyntheticAirgapPreflight {
    param(
        [bool]$InfrastructureWifiDisconnected = $true,
        [bool]$P2p0Ipv4Cleared = $true,
        [bool]$CandidateRoutesClear = $true,
        [bool]$OwnerInfrastructureConnected = $false,
        [bool]$ClientInfrastructureConnected = $false,
        [bool]$OwnerP2p0Ipv4Present = $false,
        [string]$OwnerP2p0Ipv4Address = "",
        [bool]$ClientP2p0Ipv4Present = $false,
        [string]$ClientP2p0Ipv4Address = "",
        [int]$RoutesUsingWlan0 = 0,
        [int]$RoutesUsingP2p0 = 0,
        [int]$RoutesUsingLoopback = 0,
        [int]$LocalSelfRoutes = 0,
        [int]$UnreachableRoutes = 0,
        [int]$ReachableRoutes = 0
    )
    [ordered]@{
        schema = "rusty.quest.qcl100_infrastructure_wifi_airgap_preflight.v1"
        owner_wifi = [ordered]@{
            infrastructure_connected = $OwnerInfrastructureConnected
        }
        client_wifi = [ordered]@{
            infrastructure_connected = $ClientInfrastructureConnected
        }
        owner_p2p0 = [ordered]@{
            ipv4_present = $OwnerP2p0Ipv4Present
            ipv4_address = $OwnerP2p0Ipv4Address
        }
        client_p2p0 = [ordered]@{
            ipv4_present = $ClientP2p0Ipv4Present
            ipv4_address = $ClientP2p0Ipv4Address
        }
        infrastructure_wifi_disconnected = $InfrastructureWifiDisconnected
        p2p0_ipv4_cleared = $P2p0Ipv4Cleared
        candidate_wifi_direct_prelaunch_routes_clear = $CandidateRoutesClear
        candidate_wifi_direct_route_count = $RoutesUsingWlan0 + $RoutesUsingP2p0 + $RoutesUsingLoopback + $UnreachableRoutes + $ReachableRoutes
        candidate_wifi_direct_routes_using_wlan0 = $RoutesUsingWlan0
        candidate_wifi_direct_routes_using_p2p0 = $RoutesUsingP2p0
        candidate_wifi_direct_routes_using_loopback = $RoutesUsingLoopback
        candidate_wifi_direct_local_self_routes = $LocalSelfRoutes
        candidate_wifi_direct_routes_unreachable = $UnreachableRoutes
        candidate_wifi_direct_routes_reachable = $ReachableRoutes
    }
}

function Assert-Qcl100ParityBlockerCase {
    param(
        [string]$Name,
        $AirgapPreflight,
        [bool]$RequireInfrastructureWifiDisconnected,
        [bool]$RequireP2p0Ipv4Cleared,
        [bool]$RequireCandidateWifiDirectRoutesClear,
        [int]$ExpectedBlockerCount,
        [string]$ExpectedFirstBlocker
    )
    $blockers = New-Qcl100PreflightParityBlockers `
        -AirgapPreflight $AirgapPreflight `
        -RequireInfrastructureWifiDisconnected $RequireInfrastructureWifiDisconnected `
        -RequireP2p0Ipv4Cleared $RequireP2p0Ipv4Cleared `
        -RequireCandidateWifiDirectRoutesClear $RequireCandidateWifiDirectRoutesClear
    $freshnessAcceptance = [ordered]@{
        passed = $false
    }
    Set-Qcl100ParityBlockers -FreshnessAcceptance $freshnessAcceptance -Blockers $blockers
    if ([int]$freshnessAcceptance.parity_blocker_count -ne $ExpectedBlockerCount) {
        throw "QCL100 parity blocker self-test '$Name' expected blocker count $ExpectedBlockerCount but got $($freshnessAcceptance.parity_blocker_count)."
    }
    if ([string]$freshnessAcceptance.first_parity_blocker -ne $ExpectedFirstBlocker) {
        throw "QCL100 parity blocker self-test '$Name' expected first blocker '$ExpectedFirstBlocker' but got '$($freshnessAcceptance.first_parity_blocker)'."
    }
    [ordered]@{
        name = $Name
        expected_blocker_count = $ExpectedBlockerCount
        expected_first_blocker = $ExpectedFirstBlocker
        parity_blocker_count = [int]$freshnessAcceptance.parity_blocker_count
        first_parity_blocker = [string]$freshnessAcceptance.first_parity_blocker
        parity_blockers = @($freshnessAcceptance.parity_blockers)
        passed = $true
    }
}

function Invoke-Qcl100ParityBlockerSelfTest {
    param([string]$OutputDirectory = $OutDir)
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $OutputDirectory = Join-Path $env:TEMP "qcl100-parity-blocker-selftest"
    }
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $results = @(
        Assert-Qcl100ParityBlockerCase `
            -Name "missing-airgap-preflight" `
            -AirgapPreflight $null `
            -RequireInfrastructureWifiDisconnected $true `
            -RequireP2p0Ipv4Cleared $true `
            -RequireCandidateWifiDirectRoutesClear $true `
            -ExpectedBlockerCount 1 `
            -ExpectedFirstBlocker "qcl100_airgap_preflight"
        Assert-Qcl100ParityBlockerCase `
            -Name "infrastructure-wifi-connected" `
            -AirgapPreflight (New-Qcl100SyntheticAirgapPreflight -InfrastructureWifiDisconnected $false -OwnerInfrastructureConnected $true) `
            -RequireInfrastructureWifiDisconnected $true `
            -RequireP2p0Ipv4Cleared $true `
            -RequireCandidateWifiDirectRoutesClear $true `
            -ExpectedBlockerCount 1 `
            -ExpectedFirstBlocker "infrastructure_wifi_airgap"
        Assert-Qcl100ParityBlockerCase `
            -Name "client-stale-p2p0" `
            -AirgapPreflight (New-Qcl100SyntheticAirgapPreflight -P2p0Ipv4Cleared $false -CandidateRoutesClear $false -ClientP2p0Ipv4Present $true -ClientP2p0Ipv4Address "192.168.49.46" -RoutesUsingLoopback 1 -LocalSelfRoutes 1 -UnreachableRoutes 3) `
            -RequireInfrastructureWifiDisconnected $true `
            -RequireP2p0Ipv4Cleared $true `
            -RequireCandidateWifiDirectRoutesClear $true `
            -ExpectedBlockerCount 2 `
            -ExpectedFirstBlocker "wifi_direct_p2p0_ipv4_preflight"
        Assert-Qcl100ParityBlockerCase `
            -Name "candidate-routes-not-clear" `
            -AirgapPreflight (New-Qcl100SyntheticAirgapPreflight -CandidateRoutesClear $false -RoutesUsingP2p0 1 -ReachableRoutes 1) `
            -RequireInfrastructureWifiDisconnected $true `
            -RequireP2p0Ipv4Cleared $true `
            -RequireCandidateWifiDirectRoutesClear $true `
            -ExpectedBlockerCount 1 `
            -ExpectedFirstBlocker "wifi_direct_candidate_route_preflight"
        Assert-Qcl100ParityBlockerCase `
            -Name "strict-preflight-clear" `
            -AirgapPreflight (New-Qcl100SyntheticAirgapPreflight) `
            -RequireInfrastructureWifiDisconnected $true `
            -RequireP2p0Ipv4Cleared $true `
            -RequireCandidateWifiDirectRoutesClear $true `
            -ExpectedBlockerCount 0 `
            -ExpectedFirstBlocker ""
    )
    $selfTest = [ordered]@{
        schema = "rusty.quest.qcl100_parity_blocker_self_test.v1"
        required = "blocked-preflight and final QCL100 summaries expose compact ordered parity blockers in freshness_acceptance"
        cases = $results
        passed = $true
    }
    Write-JsonFile -Value $selfTest -Path (Join-Path $OutputDirectory "qcl100-parity-blocker-self-test.json")
    Write-Output "QCL100 parity blocker self-test passed."
    return $selfTest
}
