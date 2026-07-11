param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..") }
$root = (Resolve-Path -LiteralPath $RepoRoot).Path
$fixtureRoot = Join-Path $root "fixtures\broker-authority"

function Read-Json([string]$Path) {
    Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

foreach ($suffix in @("applied", "unknown-rejected", "unleased-rejected")) {
    $standalone = Read-Json (Join-Path $fixtureRoot "standalone-$suffix.response.json")
    $embedded = Read-Json (Join-Path $fixtureRoot "embedded-$suffix.response.json")
    foreach ($response in @($standalone, $embedded)) {
        if ($response.'$schema' -ne "rusty.quest.broker.authority_response.v1") { throw "Authority response schema drifted for '$suffix'." }
        if ($response.local_acceptance_rules -ne $false) { throw "Quest bridge gained local acceptance rules for '$suffix'." }
        if ($response.decision_owner_id -ne "module.runtime.host") { throw "Quest bridge authority owner drifted for '$suffix'." }
        if ($response.adapter_receipt.authority_owner_id -ne "module.runtime.host") { throw "Adapter receipt authority owner drifted for '$suffix'." }
    }
    $standaloneDispatch = $standalone.adapter_receipt.dispatch | ConvertTo-Json -Depth 20 -Compress
    $embeddedDispatch = $embedded.adapter_receipt.dispatch | ConvertTo-Json -Depth 20 -Compress
    $standaloneApplication = $standalone.adapter_receipt.application | ConvertTo-Json -Depth 20 -Compress
    $embeddedApplication = $embedded.adapter_receipt.application | ConvertTo-Json -Depth 20 -Compress
    if ($standaloneDispatch -ne $embeddedDispatch -or $standaloneApplication -ne $embeddedApplication) {
        throw "Standalone/embedded Runtime Host decision parity drifted for '$suffix'."
    }
}

$standaloneJavaPath = Join-Path $root "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\ManifoldRuntimeAuthorityBridge.java"
$embeddedJavaPath = Join-Path $root "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\EmbeddedManifoldRuntimeAuthorityBridge.java"
$standaloneRustPath = Join-Path $root "apps\manifold-broker-android\native\src\lib.rs"
$embeddedRustPath = Join-Path $root "apps\native-renderer-android\native\src\embedded_manifold_runtime_authority_jni.rs"
foreach ($path in @($standaloneJavaPath, $embeddedJavaPath, $standaloneRustPath, $embeddedRustPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing broker authority bridge: $path" }
}

$javaSources = @(
    (Get-Content -Raw -LiteralPath $standaloneJavaPath)
    (Get-Content -Raw -LiteralPath $embeddedJavaPath)
)
foreach ($java in $javaSources) {
    foreach ($token in @("nativeEvaluate", "rusty.quest.broker.authority_invocation.v1", "rusty.quest.broker.authority_response.v1", "module.runtime.host", "local_acceptance_rules")) {
        if ($java -notmatch [regex]::Escape($token)) { throw "Java authority bridge is missing '$token'." }
    }
    if ($java -match 'command\.[a-z]' -or $java -match 'rejection_reason') {
        throw "Java authority bridge contains command or rejection policy."
    }
}

$standaloneRust = Get-Content -Raw -LiteralPath $standaloneRustPath
$embeddedRust = Get-Content -Raw -LiteralPath $embeddedRustPath
if ($standaloneRust -notmatch 'evaluate_authority_json' -or $embeddedRust -notmatch 'evaluate_authority_json') {
    throw "Both JNI boundaries must delegate to the shared Quest/Manifold evaluator."
}

Write-Host "Quest broker authority static gate passed"
