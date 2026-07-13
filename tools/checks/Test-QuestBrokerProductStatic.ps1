param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..") }
$root = (Resolve-Path -LiteralPath $RepoRoot).Path
$fixtureRoot = Join-Path $root "fixtures\broker-products"
$androidNamespace = "http://schemas.android.com/apk/res/android"
$expectedProfiles = @(
    "base-standalone",
    "media-session-standalone",
    "camera-embedded",
    "direct-p2p-standalone",
    "ble-embedded",
    "legacy-camera-p2p-standalone"
)

function Get-ProjectionPermissionLines([object]$Projection) {
    @($Projection.permissions | ForEach-Object {
        $flags = @($_.uses_permission_flags | Sort-Object)
        $maxSdk = if ($null -eq $_.max_sdk_version) { "" } else { [string]$_.max_sdk_version }
        "$($_.name)|$($flags -join ',')|$maxSdk"
    } | Sort-Object)
}

function Get-ManifestPermissionLines([xml]$Manifest) {
    @($Manifest.manifest.'uses-permission' | ForEach-Object {
        $name = $_.GetAttribute("name", $androidNamespace)
        $flags = $_.GetAttribute("usesPermissionFlags", $androidNamespace)
        $maxSdk = $_.GetAttribute("maxSdkVersion", $androidNamespace)
        "$name|$flags|$maxSdk"
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
    if ($projection.runtime_mode -eq "standalone") {
        $startService = @($manifest.manifest.application.service | Where-Object {
            $_.GetAttribute("name", $androidNamespace) -eq ".BrokerStartService"
        }) | Select-Object -First 1
        $admissionService = @($manifest.manifest.application.service | Where-Object {
            $_.GetAttribute("name", $androidNamespace) -eq ".ManifoldAdmissionService"
        }) | Select-Object -First 1
        $launcher = @($manifest.manifest.application.activity | Where-Object {
            $_.GetAttribute("name", $androidNamespace) -eq ".BrokerStartActivity"
        }) | Select-Object -First 1
        if ($null -eq $startService -or $startService.GetAttribute("exported", $androidNamespace) -ne "false") {
            throw "BrokerStartService must be package-private for '$profile'."
        }
        if ($null -eq $launcher -or $launcher.GetAttribute("exported", $androidNamespace) -ne "true") {
            throw "Broker launcher activity must remain exported for '$profile'."
        }
        if ($null -eq $admissionService -or
            $admissionService.GetAttribute("exported", $androidNamespace) -ne "true" -or
            $admissionService.GetAttribute("permission", $androidNamespace) -ne "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION") {
            throw "Admission service must remain exported only behind its signature permission for '$profile'."
        }
    }
}

$base = Get-Content -Raw -LiteralPath (Join-Path $fixtureRoot "base-standalone.manifest.json") | ConvertFrom-Json
$baseNames = @($base.permissions.name | Sort-Object)
$expectedBaseNames = @(
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.INTERNET",
    "android.permission.POST_NOTIFICATIONS"
)
if (($baseNames -join "|") -ne ($expectedBaseNames -join "|")) {
    throw "Base broker product must contain only network and standalone lifecycle permissions."
}
$media = Get-Content -Raw -LiteralPath (Join-Path $fixtureRoot "media-session-standalone.manifest.json") | ConvertFrom-Json
if ((@($media.permissions.name | Sort-Object) -join "|") -ne ($expectedBaseNames -join "|")) {
    throw "Generic media-session product must not inherit camera, P2P, or BLE permissions."
}
$mediaManifest = Get-Content -Raw -LiteralPath (Join-Path $fixtureRoot "media-session-standalone.AndroidManifest.xml")
if ($mediaManifest -match 'permission\.CAMERA|HEADSET_CAMERA|SPATIAL_CAMERA|NEARBY_WIFI_DEVICES|BLUETOOTH_' -or
    $mediaManifest -notmatch 'foregroundServiceType="dataSync"') {
    throw "Generic media-session package manifest is not permission-minimal."
}
$legacyManifest = Get-Content -Raw -LiteralPath (Join-Path $fixtureRoot "legacy-camera-p2p-standalone.AndroidManifest.xml")
foreach ($token in @("permission.CAMERA", "HEADSET_CAMERA", "SPATIAL_CAMERA", "NEARBY_WIFI_DEVICES", 'foregroundServiceType="dataSync|camera"')) {
    if ($legacyManifest -notmatch [regex]::Escape($token)) {
        throw "Explicit legacy camera/P2P manifest is missing '$token'."
    }
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

$appManifest = Join-Path $root "apps\manifold-broker-android\AndroidManifest.xml"
if (Test-Path -LiteralPath $appManifest) {
    throw "Broker app must not retain an ambient hand-maintained AndroidManifest.xml."
}
$build = Get-Content -Raw -LiteralPath (Join-Path $root "tools\Build-ManifoldBrokerAndroid.ps1")
$preparer = Join-Path $root "crates\rusty-quest-broker-product\src\bin\prepare_android_broker_product.rs"
if (-not (Test-Path -LiteralPath $preparer -PathType Leaf)) {
    throw "Broker product preparation CLI is missing."
}
$artifactGate = Join-Path $root "tools\checks\Test-ManifoldBrokerBuildArtifact.ps1"
if (-not (Test-Path -LiteralPath $artifactGate -PathType Leaf)) {
    throw "Broker build artifact semantic gate is missing."
}
foreach ($token in @(
    "ProductSpecPath",
    "ProductLockPath",
    "prepare_android_broker_product",
    "product-package-inputs.json",
    "generatedManifestPath",
    "command-registry.json",
    "accepted-product-lock.json",
    "manifold_product_lock_sha256",
    "GeneratedBrokerRuntimeConfig.java",
    "static final String SHA256",
    "rusty.quest.broker.runtime_config.v1",
    "broker_runtime_config_sha256",
    "broker_runtime_config_canonical_sha256",
    "ValidateRuntimeConfigOnly",
    "manifold_product_modules",
    "android_components",
    "apk_signature_verified",
    "packaged_authority",
    "Get-ExactClientGrantCapabilities",
    "client_lock_sha256",
    "LegacyCameraP2pCompatibility",
    "rusty.quest.manifold_broker_android.build_manifest.v2")) {
    if ($build -notmatch [regex]::Escape($token)) {
        throw "Broker build is missing exact product packaging token '$token'."
    }
}
if ($build -match 'Join-Path \$appRoot "AndroidManifest\.xml"') {
    throw "Broker build still consumes the removed ambient app manifest."
}

Write-Host "Quest broker product static gate passed"
