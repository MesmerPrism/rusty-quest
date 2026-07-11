$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$app = Join-Path $repo "apps\direct-p2p-provider-android"
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
$source = (Get-ChildItem -LiteralPath $app -Recurse -File |
    Where-Object { $_.Extension -in '.rs','.java','.toml','.xml' } |
    ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
foreach ($forbidden in @('qcl041','qcl-041','qcl041-wifi-direct-harness-android','io.github.mesmerprism.rustyquest.qcl041')) {
    if ($source -match [regex]::Escape($forbidden)) { throw "Product provider contains forbidden harness dependency token: $forbidden" }
}
foreach ($token in @('android_wifi_direct_topology_provider','android_network_binding_provider','rust_direct_socket_provider','bounded_control_exchange','p2p0')) {
    if ($source -notmatch [regex]::Escape($token)) { throw "Product provider is missing authority token: $token" }
}
Write-Output "direct P2P provider static checks passed"
