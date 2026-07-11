param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..") }
$root = (Resolve-Path -LiteralPath $RepoRoot).Path
$fixtureRoot = Join-Path $root "fixtures\broker-products"
$androidNamespace = "http://schemas.android.com/apk/res/android"
$expectedProfiles = @(
    "base-standalone",
    "camera-embedded",
    "direct-p2p-standalone",
    "ble-embedded"
)

function Get-ProjectionPermissionLines([object]$Projection) {
    @($Projection.permissions | ForEach-Object {
        $flags = @($_.uses_permission_flags | Sort-Object)
        "$($_.name)|$($flags -join ',')"
    } | Sort-Object)
}

function Get-ManifestPermissionLines([xml]$Manifest) {
    @($Manifest.manifest.'uses-permission' | ForEach-Object {
        $name = $_.GetAttribute("name", $androidNamespace)
        $flags = $_.GetAttribute("usesPermissionFlags", $androidNamespace)
        "$name|$flags"
    } | Sort-Object)
}

foreach ($profile in $expectedProfiles) {
    $projectionPath = Join-Path $fixtureRoot "$profile.manifest.json"
    $manifestPath = Join-Path $fixtureRoot "$profile.AndroidManifest.xml"
    if (-not (Test-Path -LiteralPath $projectionPath -PathType Leaf)) { throw "Missing broker projection: $projectionPath" }
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Missing broker manifest: $manifestPath" }

    $projection = Get-Content -Raw -LiteralPath $projectionPath | ConvertFrom-Json
    [xml]$manifest = Get-Content -Raw -LiteralPath $manifestPath
    if ($projection.'$schema' -ne "rusty.quest.broker.android_manifest_projection.v1") {
        throw "Unexpected broker projection schema for '$profile'."
    }

    $projected = Get-ProjectionPermissionLines $projection
    $declared = Get-ManifestPermissionLines $manifest
    if (($projected -join "|") -ne ($declared -join "|")) {
        throw "Android permission projection drifted for '$profile': projected=[$($projected -join '; ')] declared=[$($declared -join '; ')]"
    }
}

$base = Get-Content -Raw -LiteralPath (Join-Path $fixtureRoot "base-standalone.manifest.json") | ConvertFrom-Json
$baseNames = @($base.permissions.name | Sort-Object)
if (($baseNames -join "|") -ne "android.permission.INTERNET") {
    throw "Base broker product must remain camera/P2P/BLE-free."
}

$neverForLocationPermissions = @(
    "android.permission.NEARBY_WIFI_DEVICES",
    "android.permission.BLUETOOTH_SCAN"
)
foreach ($profile in $expectedProfiles) {
    $projection = Get-Content -Raw -LiteralPath (Join-Path $fixtureRoot "$profile.manifest.json") | ConvertFrom-Json
    foreach ($permission in @($projection.permissions | Where-Object { $neverForLocationPermissions -contains $_.name })) {
        if (@($permission.uses_permission_flags) -notcontains "neverForLocation") {
            throw "Sensitive permission '$($permission.name)' lacks neverForLocation in '$profile'."
        }
    }
}

Write-Host "Quest broker product static gate passed"
