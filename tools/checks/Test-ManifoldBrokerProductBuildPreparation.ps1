param([string]$RepoRoot = ".")

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$build = Join-Path $repo "tools\Build-ManifoldBrokerAndroid.ps1"
$manifoldFixtures = Join-Path $repo "..\rusty-manifold\fixtures\broker-product"
$testRoot = Join-Path $repo "target\broker-product-build-preparation-test"
$buildSource = Get-Content -Raw -LiteralPath $build
if ($buildSource -notmatch '\$schema''\s*=\s*"rusty\.manifold\.broker\.adapter_config\.v2"' -or
    $buildSource -notmatch 'product_lock_sha256\s*=\s*"sha256:\$\(\[string\]\$productInputs\.manifold_lock_sha256\)"') {
    throw "Runtime adapter config is not bound to the exact prepared product-lock bytes."
}
if ($buildSource -notmatch 'client_lock_id\s*=\s*\[string\]\$clientLock\.feature_lock_id' -or
    $buildSource -notmatch 'client_lock_fingerprint\s*=\s*"sha256:\$\(\[string\]\$binding\.input\.sha256\)"') {
    throw "Generated admission grants are not bound to the exact packaged client-lock identity and bytes."
}
if ($buildSource -notmatch '\$schema''\s*=\s*"rusty\.manifold\.admission\.snapshot\.v2"' -or
    $buildSource -notmatch 'reviewed_sweep_ids\s*=\s*@\(\)') {
    throw "Generated admission state is not a complete current v2 snapshot."
}
if ($buildSource -notmatch 'if\s*\(\$ValidateRuntimeConfigOnly\)\s*\{' -or
    $buildSource -notmatch 'Write-Output\s+\$runtimeConfigPath') {
    throw "The package build has no fail-fast runtime-config authority preflight."
}
foreach ($receiptToken in @(
    'manifold_product_modules',
    'manifold_product_permissions',
    'android_permissions',
    'android_components',
    'product_spec_lock_validated',
    'runtime_config_validated',
    'apk_signature_verified',
    'packaged_assets_validated',
    'apksigner verification')) {
    if ($buildSource -notmatch [regex]::Escape($receiptToken)) {
        throw "Build receipt semantic closure is missing '$receiptToken'."
    }
}

function Assert-Rejected {
    param(
        [Parameter(Mandatory=$true)][scriptblock]$Action,
        [Parameter(Mandatory=$true)][string]$Label
    )

    $rejected = $false
    try {
        & $Action | Out-Null
    } catch {
        $rejected = $true
    }
    if (-not $rejected) {
        throw "$Label was not rejected."
    }
}

$mediaOut = Join-Path $testRoot "media"
& $build `
    -ProductSpecPath (Join-Path $manifoldFixtures "media-session-standalone.json") `
    -ProductLockPath (Join-Path $manifoldFixtures "media-session-standalone.lock.json") `
    -OutDir $mediaOut `
    -PrepareOnly | Out-Null
$mediaReceiptPath = Join-Path $mediaOut "product-inputs\product-package-inputs.json"
$mediaReceipt = Get-Content -Raw -LiteralPath $mediaReceiptPath | ConvertFrom-Json
$mediaManifest = Get-Content -Raw -LiteralPath (Join-Path $mediaOut "product-inputs\AndroidManifest.xml")
$mediaRegistry = Get-Content -Raw -LiteralPath (Join-Path $mediaOut "product-inputs\command-registry.json") | ConvertFrom-Json
if ($mediaReceipt.product_id -ne "broker.media_session.standalone" -or
    @($mediaReceipt.features) -contains "camera_media" -or
    @($mediaReceipt.features) -notcontains "media_session" -or
    ([string]$mediaReceipt.manifold_lock_sha256).Length -ne 64) {
    throw "Camera-free media package receipt drifted."
}
if ($mediaManifest -match 'permission\.CAMERA|HEADSET_CAMERA|SPATIAL_CAMERA|NEARBY_WIFI_DEVICES|BLUETOOTH_' -or
    $mediaManifest -notmatch 'foregroundServiceType="dataSync"') {
    throw "Camera-free media package manifest drifted."
}
if ($mediaRegistry.manifold_lock_id -ne $mediaReceipt.manifold_lock_id -or
    @($mediaRegistry.command_ids) -notcontains "command.media.session.start") {
    throw "Camera-free media command registry is not bound to the accepted lock."
}

Assert-Rejected -Label "missing explicit product inputs" -Action {
    & $build -OutDir (Join-Path $testRoot "missing") -PrepareOnly
}
Assert-Rejected -Label "sensitive product without compatibility switch" -Action {
    & $build `
        -ProductSpecPath (Join-Path $manifoldFixtures "direct-p2p-standalone.json") `
        -ProductLockPath (Join-Path $manifoldFixtures "direct-p2p-standalone.lock.json") `
        -OutDir (Join-Path $testRoot "sensitive") `
        -PrepareOnly
}

$legacyOut = Join-Path $testRoot "legacy"
& $build -LegacyCameraP2pCompatibility -OutDir $legacyOut -PrepareOnly | Out-Null
$legacyReceipt = Get-Content -Raw -LiteralPath (Join-Path $legacyOut "product-inputs\product-package-inputs.json") | ConvertFrom-Json
if ($legacyReceipt.product_id -ne "broker.legacy_camera_p2p.standalone" -or
    @($legacyReceipt.features) -notcontains "camera_media" -or
    @($legacyReceipt.features) -notcontains "direct_p2p") {
    throw "Explicit legacy compatibility package receipt drifted."
}

$staleLockPath = Join-Path $testRoot "stale-media.lock.json"
$stale = Get-Content -Raw -LiteralPath (Join-Path $manifoldFixtures "media-session-standalone.lock.json") | ConvertFrom-Json
$stale.permissions = @($stale.permissions) + @("camera")
$staleJson = $stale | ConvertTo-Json -Depth 12
[System.IO.File]::WriteAllText($staleLockPath, $staleJson, (New-Object System.Text.UTF8Encoding($false)))
Assert-Rejected -Label "expanded product lock" -Action {
    & $build `
        -ProductSpecPath (Join-Path $manifoldFixtures "media-session-standalone.json") `
        -ProductLockPath $staleLockPath `
        -OutDir (Join-Path $testRoot "stale") `
        -PrepareOnly
}

Write-Host "Manifold broker product build preparation gate passed"
