$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$runnerPath = Join-Path $repoRoot "tools\Invoke-Qcl099QuestToQuestStereoProjectionWifiDirect.ps1"
$monitorPath = Join-Path $repoRoot "tools\Invoke-Qcl099QuestToQuestStereoProjectionWifiDirectMonitored.ps1"
$directAuthorityPath = Join-Path $repoRoot "tools\qcl100_native_projection\DirectP2pMediaAuthority.ps1"

foreach ($path in @($runnerPath, $monitorPath, $directAuthorityPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing QCL099 stereo projection runner file: $path"
    }
}

$runner = Get-Content -Raw -LiteralPath $runnerPath
$monitor = Get-Content -Raw -LiteralPath $monitorPath
$directAuthority = Get-Content -Raw -LiteralPath $directAuthorityPath

foreach ($token in @(
    'ValidateSet\("qcl041", "broker"\)',
    "TransportOwner",
    "Qcl100LowerGateAuthority",
    "rusty_direct_p2p_socket_authority",
    "DirectP2pMediaAuthority.ps1",
    "Get-RustyQuestDirectP2pTransportRouteSpec",
    "New-RustyQuestRemoteCameraReceiverParams",
    "New-RustyQuestRemoteCameraSenderParams",
    "New-RustyQuestDirectP2pAuthoritySummary",
    "Get-RustyQuestQcl041WifiDirectLocalAddress",
    "Get-RustyQuestQcl041WifiDirectAcceptedPeerAddress",
    "Get-RustyQuestQcl041WifiDirectSocketLocalAddress",
    "Get-RustyQuestDirectP2pAddressRefreshSummary",
    "Assert-RustyQuestDirectP2pAddressRefreshReady",
    "Get-Qcl099DirectP2pAddressRefresh",
    "New-Qcl099ReceiverParams",
    "Assert-Qcl099ReceiverReady",
    "receiver_ready",
    "broker_direct_senders_started",
    "direct_p2p_receiver_observed_bytes_ready",
    "direct_p2p_receiver_final_status_fresh",
    "direct_p2p_sender_authority_ready",
    "direct_p2p_address_refresh",
    "direct_p2p_media_ready",
    "direct_p2p_makepad_projection_ready_both_headsets",
    "Get-Qcl099BrokerReceiverObservedFreshness",
    "Get-Qcl099DirectP2pSenderAuthority"
)) {
    if ($runner -notmatch $token) {
        throw "QCL099 runner is missing required direct p2p broker-mode token: $token"
    }
}

if ($runner -notmatch 'if \(\$TransportOwner -eq "qcl041"\)[\s\S]*Invoke-LiveBridgeCommand "owner-start-source-only"') {
    throw "QCL099 runner must start broker senders before QCL041 only in qcl041 relay mode."
}
if ($runner -notmatch 'if \(\$TransportOwner -eq "broker"\)[\s\S]*Invoke-LiveBridgeCommand "owner-start-source-only"') {
    throw "QCL099 runner must start broker senders after QCL041 group hold in broker mode."
}
if ($runner -notmatch 'New-Qcl099ReceiverParams -TransportBindHost \$ownerReceiverTransportBindHost[\s\S]*Assert-Qcl099ReceiverReady') {
    throw "QCL099 broker mode must bind receivers to refreshed direct-p2p local addresses and assert readiness before sender start."
}
if ($runner -notmatch 'qcl041\.qcl082_relay_enabled", \$RelayEnabled\.ToString\(\)\.ToLowerInvariant\(\)') {
    throw "QCL099 runner must disable QCL041 media relay when broker owns direct p2p media."
}

foreach ($token in @(
    "PollSeconds",
    "OverallTimeoutSeconds",
    "PhaseStallTimeoutSeconds",
    "runnerWorkingDirectory",
    "Write-Host",
    "TransportOwner",
    "Qcl100LowerGateAuthority",
    "MakepadDecodeOutputMode",
    "direct_p2p_media_ready",
    "direct_p2p_makepad_projection_ready_both_headsets"
)) {
    if ($monitor -notmatch $token) {
        throw "QCL099 monitored runner is missing required bounded/progress token: $token"
    }
}

foreach ($token in @(
    "rusty_direct_p2p_socket_authority",
    "binary-media",
    "high_rate_json_payload",
    "sender peer sockets bind explicit QCL041-proven local p2p0 address",
    "direct-p2p broker receiver transport sockets bind explicit QCL041-proven local p2p0 address after address refresh",
    "owner_receiver_transport_bind_host",
    "client_receiver_transport_bind_host"
)) {
    if ($directAuthority -notmatch $token) {
        throw "Direct p2p media authority helper is missing required contract token: $token"
    }
}

. $directAuthorityPath
$freshSummary = Get-RustyQuestDirectP2pAddressRefreshSummary `
    -OwnerRequestedAddress "192.168.49.1" `
    -ClientRequestedAddress "192.168.49.46" `
    -OwnerObservedAddress "192.168.49.1" `
    -ClientObservedAddress "192.168.49.102"
if (-not [bool]$freshSummary.ready) {
    throw "Direct p2p address refresh helper should accept two observed p2p0 addresses."
}
Assert-RustyQuestDirectP2pAddressRefreshReady -Summary $freshSummary -Label "fresh-helper-test"

$staleSummary = Get-RustyQuestDirectP2pAddressRefreshSummary `
    -OwnerRequestedAddress "192.168.49.1" `
    -ClientRequestedAddress "192.168.49.46" `
    -OwnerObservedAddress "192.168.49.1" `
    -ClientObservedAddress ""
if ([bool]$staleSummary.ready -or -not (@($staleSummary.issues) -contains "client_observed_p2p_address_missing")) {
    throw "Direct p2p address refresh helper must reject default-only client routes."
}
$threw = $false
try {
    Assert-RustyQuestDirectP2pAddressRefreshReady -Summary $staleSummary -Label "stale-helper-test"
} catch {
    $threw = $true
}
if (-not $threw) {
    throw "Direct p2p address refresh assertion must fail stale/default routes before broker media starts."
}

Write-Host "QCL099 stereo projection runner static checks passed."
