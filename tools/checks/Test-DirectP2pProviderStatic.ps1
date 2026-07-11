$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$app = Join-Path $repo "apps\direct-p2p-provider-android"
$decisionGateTool = Join-Path $repo "tools\Invoke-PeerSessionDecisionGateTwoQuest.ps1"
$required = @(
    "AndroidManifest.xml",
    "native\Cargo.toml",
    "native\src\lib.rs",
    "src\main\java\io\github\mesmerprism\rustyquest\directp2p\DirectP2pProviderActivity.java",
    "src\main\java\io\github\mesmerprism\rustyquest\directp2p\AndroidNetworkBindingProvider.java",
    "src\main\java\io\github\mesmerprism\rustyquest\directp2p\RustDirectSocketProvider.java"
)
foreach ($relative in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $app $relative))) { throw "Missing product provider file: $relative" }
}
if (-not (Test-Path -LiteralPath $decisionGateTool)) { throw "Missing peer-session decision gate integration tool" }
$source = (Get-ChildItem -LiteralPath $app -Recurse -File |
    Where-Object { $_.Extension -in '.rs','.java','.toml','.xml' } |
    ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
foreach ($forbidden in @('qcl041','qcl-041','qcl041-wifi-direct-harness-android','io.github.mesmerprism.rustyquest.qcl041')) {
    if ($source -match [regex]::Escape($forbidden)) { throw "Product provider contains forbidden harness dependency token: $forbidden" }
}
foreach ($token in @('android_wifi_direct_topology_provider','android_network_binding_provider','rust_direct_socket_provider','bounded_control_exchange','p2p0','require_peer_session_authorization','phase=topology_gate','PEER_TOPOLOGY_AUTHORIZATION_SCHEMA','stale_authority_revision','decision_not_authorized')) {
    if ($source -notmatch [regex]::Escape($token)) { throw "Product provider is missing authority token: $token" }
}
$decisionGate = Get-Content -Raw -LiteralPath $decisionGateTool
foreach ($token in @('unauthenticated_authorization','stale-after-revocation','revoked_authorization','phase=topology_gate status=accepted','validate_product_wifi_direct_run','user_authorized_serial_scoped')) {
    if ($decisionGate -notmatch [regex]::Escape($token)) { throw "Peer-session decision gate tool is missing token: $token" }
}
Write-Output "direct P2P provider static checks passed"
