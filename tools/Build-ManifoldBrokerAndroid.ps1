param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutDir = "",
    [string]$Keystore = "",
    [string]$ProductSpecPath = "",
    [string]$ProductLockPath = "",
    [string[]]$MediaSessionBindingPath = @(),
    [switch]$LegacyCameraP2pCompatibility,
    [switch]$PrepareOnly,
    [switch]$ValidateRuntimeConfigOnly
)

$ErrorActionPreference = "Stop"

function Get-LatestDirectory {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Parent,
        [Parameter(Mandatory=$true)]
        [string]$Pattern
    )

    $directory = Get-ChildItem -LiteralPath $Parent -Directory -Filter $Pattern |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $directory) {
        throw "No directory matching $Pattern under $Parent"
    }
    return $directory.FullName
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string]$File,
        [string[]]$Arguments = @()
    )

    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Get-FileSha256Hex {
    param([Parameter(Mandatory=$true)][string]$Path)

    $cmd = Get-Command Get-FileHash -ErrorAction SilentlyContinue
    if ($null -ne $cmd) {
        return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    }

    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $hash = $sha.ComputeHash($stream)
            return -join ($hash | ForEach-Object { $_.ToString("x2") })
        } finally {
            $sha.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Resolve-ProductInputPath {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$RepoRoot
    )

    $candidate = if ([System.IO.Path]::IsPathRooted($Path)) {
        $Path
    } else {
        Join-Path $RepoRoot $Path
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "$Label does not exist: $candidate"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Get-ExactClientGrantCapabilities {
    param(
        [Parameter(Mandatory=$true)]$ClientLock,
        [Parameter(Mandatory=$true)]$ProductLock
    )
    $allowed = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($commandId in @($ProductLock.command_ids)) {
        $suffix = ([string]$commandId) -replace '^command\.', ''
        [void]$allowed.Add("capability.command.$suffix")
    }
    $features = @($ProductLock.features | ForEach-Object { [string]$_ })
    $commands = @($ProductLock.command_ids | ForEach-Object { [string]$_ })
    $streams = @($ProductLock.stream_ids | ForEach-Object { [string]$_ })
    $mediaSelected = $features -contains "media_session"
    $peerSelected = ($features -contains "direct_p2p") -or
        ($features -contains "ble_rendezvous") -or
        ($commands -contains "command.peer.status.get") -or
        ($streams -contains "stream.peer.status")
    $result = foreach ($capability in @($ClientLock.capabilities | ForEach-Object { [string]$_ })) {
        if ($allowed.Contains($capability) -or
            ($mediaSelected -and ($capability -eq "capability.media.session.observe" -or $capability.StartsWith("capability.sink.", [System.StringComparison]::Ordinal))) -or
            ($peerSelected -and $capability -eq "capability.peer.session.observe")) {
            $capability
        }
    }
    return @($result | Sort-Object -Unique)
}

function Read-ValidatedClientLock {
    param([Parameter(Mandatory=$true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Broker client lock does not exist: $Path"
    }
    $json = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path))
    $lock = $json | ConvertFrom-Json
    if ([string]$lock.schema -ne "rusty.quest.broker_client_spec.v1" -or
        [string]::IsNullOrWhiteSpace([string]$lock.client_id) -or
        [string]::IsNullOrWhiteSpace([string]$lock.package_name) -or
        @($lock.adapter_permissions).Count -ne 1 -or
        [string]$lock.adapter_permissions[0] -ne "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION" -or
        @($lock.runtime_properties).Count -ne 0 -or
        @($lock.application_defaults).Count -ne 0) {
        throw "Broker client lock is not a closed signature-scoped client spec: $Path"
    }
    $capabilities = @($lock.capabilities | ForEach-Object { [string]$_ })
    $sortedCapabilities = @($capabilities | Sort-Object -Unique)
    if ($capabilities.Count -ne $sortedCapabilities.Count -or
        (@(Compare-Object $capabilities $sortedCapabilities -SyncWindow 0).Count -ne 0)) {
        throw "Broker client lock capabilities must be unique and ordinally sorted: $Path"
    }
    return [pscustomobject]@{
        path = (Resolve-Path -LiteralPath $Path).Path
        json = $json
        lock = $lock
        sha256 = Get-FileSha256Hex -Path $Path
    }
}

function Get-RuntimeConfigDigest {
    param(
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [Parameter(Mandatory=$true)][string]$RuntimeConfigPath
    )

    Push-Location $RepoRoot
    try {
        $output = @(& cargo run --quiet -p rusty-quest-broker-authority --bin runtime_config_digest -- $RuntimeConfigPath 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "runtime config digest failed: $($output -join [Environment]::NewLine)"
        }
    } finally {
        Pop-Location
    }
    $digest = @($output | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -match '^[0-9a-f]{64}$' }) | Select-Object -Last 1
    if ([string]::IsNullOrWhiteSpace($digest)) {
        throw "runtime config digest did not emit one lowercase SHA-256"
    }
    return $digest
}

function Test-HasInputPath {
    param([string[]]$Path)
    return @(Expand-InputPaths -Path $Path).Count -gt 0
}

function Expand-InputPaths {
    param([string[]]$Path)
    return @($Path | ForEach-Object {
        ([string]$_).Split(",", [System.StringSplitOptions]::RemoveEmptyEntries) |
            ForEach-Object { $_.Trim().Trim("'").Trim('"') } |
            Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
    })
}

function Read-MediaLifecycleAuthority {
    param(
        [Parameter(Mandatory=$true)]$ClientLock,
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [Parameter(Mandatory=$true)]$MediaBindingByRelativePath
    )

    $clientId = [string]$ClientLock.client_id
    $mapping = @{
        "client.quest.native-renderer" = @{
            lifecycle = "fixtures\broker-clients\native-renderer.media-lifecycle.json"
            feature = "apps\native-renderer-android\morphospace\conformance-locks\broker-media-client.feature.lock.json"
        }
        "client.quest.spatial-camera-panel" = @{
            lifecycle = "fixtures\broker-clients\spatial-camera-panel.media-lifecycle.json"
            feature = "apps\spatial-camera-panel-android\morphospace\conformance-locks\broker-media-client.feature.lock.json"
        }
    }
    if (-not $mapping.ContainsKey($clientId)) {
        return $null
    }

    $lifecyclePath = Join-Path $RepoRoot $mapping[$clientId].lifecycle
    $featurePath = Join-Path $RepoRoot $mapping[$clientId].feature
    $lifecycle = Get-Content -Raw -LiteralPath $lifecyclePath | ConvertFrom-Json
    $relativeMediaPath = ([string]$lifecycle.media_binding_path).Replace("/", "\")
    if (-not $MediaBindingByRelativePath.ContainsKey($relativeMediaPath)) {
        return $null
    }
    $mediaPath = [string]$MediaBindingByRelativePath[$relativeMediaPath]
    return [ordered]@{
        media_lifecycle_lock_json = [System.IO.File]::ReadAllText($lifecyclePath)
        media_lifecycle_lock_sha256 = Get-FileSha256Hex -Path $lifecyclePath
        app_feature_lock_json = [System.IO.File]::ReadAllText($featurePath)
        app_feature_lock_sha256 = Get-FileSha256Hex -Path $featurePath
        media_binding_json = [System.IO.File]::ReadAllText($mediaPath)
        media_binding_sha256 = Get-FileSha256Hex -Path $mediaPath
    }
}

$appRoot = Resolve-Path (Join-Path $PSScriptRoot "..\apps\manifold-broker-android")
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$targetRoot = Join-Path $repoRoot "target"
if ($PrepareOnly -and $ValidateRuntimeConfigOnly) {
    throw "-PrepareOnly and -ValidateRuntimeConfigOnly are mutually exclusive."
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $targetRoot "manifold-broker-android"
}

$resolvedOutParent = Split-Path -Parent $OutDir
New-Item -ItemType Directory -Force -Path $targetRoot, $resolvedOutParent | Out-Null
$resolvedTargetRoot = (Resolve-Path $targetRoot).Path.TrimEnd("\")
$resolvedOutFull = [System.IO.Path]::GetFullPath($OutDir).TrimEnd("\")
if (-not $resolvedOutFull.StartsWith($resolvedTargetRoot + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must be under the repo target directory: $resolvedOutFull"
}
if (Test-Path $OutDir) {
    $resolvedOutDir = (Resolve-Path $OutDir).Path
    Remove-Item -LiteralPath $resolvedOutDir -Recurse -Force
}

$legacyProductId = "broker.legacy_camera_p2p.standalone"
if ($LegacyCameraP2pCompatibility) {
    if (-not [string]::IsNullOrWhiteSpace($ProductSpecPath) -or
        -not [string]::IsNullOrWhiteSpace($ProductLockPath)) {
        throw "-LegacyCameraP2pCompatibility cannot be combined with explicit product input paths."
    }
    $manifoldFixtures = Join-Path $repoRoot "..\rusty-manifold\fixtures\broker-product"
    $ProductSpecPath = Join-Path $manifoldFixtures "legacy-camera-p2p-standalone.json"
    $ProductLockPath = Join-Path $manifoldFixtures "legacy-camera-p2p-standalone.lock.json"
    if (-not (Test-HasInputPath -Path $MediaSessionBindingPath)) {
        $MediaSessionBindingPath = @(Join-Path $repoRoot "fixtures\media-runtime-products\camera2-surface.binding.json")
    }
} elseif ([string]::IsNullOrWhiteSpace($ProductSpecPath) -or
          [string]::IsNullOrWhiteSpace($ProductLockPath)) {
    throw "Explicit -ProductSpecPath and -ProductLockPath are required. Use -LegacyCameraP2pCompatibility only for the broad camera/P2P validation package."
}

$resolvedProductSpecPath = Resolve-ProductInputPath -Path $ProductSpecPath -Label "Product spec" -RepoRoot $repoRoot
$resolvedProductLockPath = Resolve-ProductInputPath -Path $ProductLockPath -Label "Product lock" -RepoRoot $repoRoot
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$productInputsDir = Join-Path $OutDir "product-inputs"
Push-Location $repoRoot
try {
    Invoke-Checked "broker product preparation" "cargo" @(
        "run", "--quiet",
        "-p", "rusty-quest-broker-product",
        "--bin", "prepare_android_broker_product",
        "--",
        $resolvedProductSpecPath,
        $resolvedProductLockPath,
        $productInputsDir
    )
} finally {
    Pop-Location
}

$productInputsReceiptPath = Join-Path $productInputsDir "product-package-inputs.json"
$generatedManifestPath = Join-Path $productInputsDir "AndroidManifest.xml"
$generatedProductConfigPath = Join-Path $productInputsDir "generated\io\github\mesmerprism\rustymanifold\broker\GeneratedBrokerProductConfig.java"
$canonicalProductSpecPath = Join-Path $productInputsDir "product-spec.json"
$acceptedProductLockPath = Join-Path $productInputsDir "accepted-product-lock.json"
$commandRegistryPath = Join-Path $productInputsDir "command-registry.json"
$manifestProjectionPath = Join-Path $productInputsDir "manifest-projection.json"
foreach ($path in @($productInputsReceiptPath, $generatedManifestPath, $generatedProductConfigPath, $canonicalProductSpecPath, $acceptedProductLockPath, $commandRegistryPath, $manifestProjectionPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Broker product preparation omitted required output: $path"
    }
}
$productInputs = Get-Content -Raw -LiteralPath $productInputsReceiptPath | ConvertFrom-Json
if ($productInputs.'$schema' -ne "rusty.quest.broker.android_package_inputs.v1" -or
    $productInputs.runtime_mode -ne "standalone") {
    throw "Broker product preparation did not return a standalone accepted package receipt."
}
$preparedHashes = [ordered]@{
    product_spec_sha256 = Get-FileSha256Hex -Path $canonicalProductSpecPath
    manifold_lock_sha256 = Get-FileSha256Hex -Path $acceptedProductLockPath
    manifest_projection_sha256 = Get-FileSha256Hex -Path $manifestProjectionPath
    android_manifest_sha256 = Get-FileSha256Hex -Path $generatedManifestPath
    command_registry_sha256 = Get-FileSha256Hex -Path $commandRegistryPath
}
foreach ($field in $preparedHashes.Keys) {
    if ([string]$productInputs.$field -ne [string]$preparedHashes[$field]) {
        throw "Prepared broker product hash mismatch for $field."
    }
}
$sensitiveFeatures = @($productInputs.features | Where-Object { $_ -in @("camera_media", "direct_p2p", "ble_rendezvous") })
if ($LegacyCameraP2pCompatibility) {
    if ($productInputs.product_id -ne $legacyProductId) {
        throw "Legacy compatibility selection resolved the wrong product: $($productInputs.product_id)"
    }
} elseif ($productInputs.product_id -eq $legacyProductId -or $sensitiveFeatures.Count -gt 0) {
    throw "Camera, direct-P2P, and BLE broker packaging is restricted to an explicit compatibility product or a dedicated provider package."
}

if ($PrepareOnly) {
    Write-Output $productInputsReceiptPath
    return
}

if ([string]::IsNullOrWhiteSpace($AndroidHome)) {
    throw "ANDROID_HOME or -AndroidHome is required."
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "JAVA_HOME or -JavaHome is required."
}

$buildTools = Get-LatestDirectory -Parent (Join-Path $AndroidHome "build-tools") -Pattern "*"
$platformRoot = Get-LatestDirectory -Parent (Join-Path $AndroidHome "platforms") -Pattern "android-*"
$platformJar = Join-Path $platformRoot "android.jar"
$aapt2 = Join-Path $buildTools "aapt2.exe"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$javac = Join-Path $JavaHome "bin\javac.exe"
$jar = Join-Path $JavaHome "bin\jar.exe"
$keytool = Join-Path $JavaHome "bin\keytool.exe"
$ndkRoot = Get-LatestDirectory -Parent (Join-Path $AndroidHome "ndk") -Pattern "*"
$ndkBin = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"
$androidClang = Join-Path $ndkBin "aarch64-linux-android29-clang.cmd"
$androidAr = Join-Path $ndkBin "llvm-ar.exe"

foreach ($tool in @($platformJar, $aapt2, $d8, $zipalign, $apksigner, $javac, $jar, $keytool, $androidClang, $androidAr)) {
    if (-not (Test-Path $tool)) {
        throw "Required tool not found: $tool"
    }
}

$classesDir = Join-Path $OutDir "classes"
$dexDir = Join-Path $OutDir "dex"
$classesJar = Join-Path $OutDir "classes.jar"
$apkUnsigned = Join-Path $OutDir "rusty-manifold-broker-unsigned.apk"
$apkUnaligned = Join-Path $OutDir "rusty-manifold-broker-unaligned.apk"
$apkAligned = Join-Path $OutDir "rusty-manifold-broker-aligned.apk"
$apkSigned = Join-Path $OutDir "rusty-manifold-broker.apk"
if ([string]::IsNullOrWhiteSpace($Keystore)) {
    $Keystore = Join-Path $targetRoot "rusty-manifold-broker-debug.keystore"
}

New-Item -ItemType Directory -Force -Path $classesDir, $dexDir | Out-Null

if (-not (Test-Path $Keystore)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Keystore) | Out-Null
    Invoke-Checked "keytool" $keytool @(
        "-genkeypair",
        "-v",
        "-keystore", $Keystore,
        "-storepass", "android",
        "-keypass", "android",
        "-alias", "androiddebugkey",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=Rusty Manifold Broker,O=Rusty Quest,C=US"
    )
}

$certificatePath = Join-Path $OutDir "broker-signing-certificate.der"
Invoke-Checked "keytool certificate export" $keytool @(
    "-exportcert",
    "-keystore", $Keystore,
    "-storepass", "android",
    "-alias", "androiddebugkey",
    "-file", $certificatePath
)
$certificateSha256 = Get-FileSha256Hex -Path $certificatePath
$acceptedProductLockJson = [System.IO.File]::ReadAllText($acceptedProductLockPath)
$acceptedProductSpecJson = [System.IO.File]::ReadAllText($canonicalProductSpecPath)
$acceptedProductLock = $acceptedProductLockJson | ConvertFrom-Json
$mediaSelected = @($acceptedProductLock.features | ForEach-Object { [string]$_ }) -contains "media_session"
$mediaSessionBindings = @()
$mediaBindingByRelativePath = @{}
if ($mediaSelected) {
    if (-not (Test-HasInputPath -Path $MediaSessionBindingPath)) {
        throw "Media-session products require -MediaSessionBindingPath with exact Manifold and Quest canonical bindings."
    }
    foreach ($bindingPath in @(Expand-InputPaths -Path $MediaSessionBindingPath)) {
        $resolvedMediaSessionBindingPath = Resolve-ProductInputPath `
            -Path $bindingPath `
            -Label "Media session binding" `
            -RepoRoot $repoRoot
        $mediaSessionBinding = Get-Content -Raw -LiteralPath $resolvedMediaSessionBindingPath | ConvertFrom-Json
        if ($null -eq $mediaSessionBinding.manifold -or
            $null -eq $mediaSessionBinding.quest -or
            [string]$mediaSessionBinding.manifold.'$schema' -ne "rusty.manifold.media.session_product_binding.v1" -or
            [string]$mediaSessionBinding.quest.'$schema' -ne "rusty.quest.media_stream_runtime_product_binding.v1") {
            throw "Media session binding is not an exact Manifold/Quest product binding: $resolvedMediaSessionBindingPath"
        }
        $mediaSessionBindings += $mediaSessionBinding
        $repoRootPrefix = ([string]$repoRoot).TrimEnd("\") + "\"
        if (-not $resolvedMediaSessionBindingPath.StartsWith($repoRootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Media session binding must be under the repo root: $resolvedMediaSessionBindingPath"
        }
        $relative = $resolvedMediaSessionBindingPath.Substring($repoRootPrefix.Length).Replace("/", "\")
        $mediaBindingByRelativePath[$relative] = $resolvedMediaSessionBindingPath
    }
} elseif (Test-HasInputPath -Path $MediaSessionBindingPath) {
    throw "-MediaSessionBindingPath cannot widen a product that did not select media_session."
}
$initialLeases = @()
$clientLockInputs = @(
    [ordered]@{
        grant_id = "grant.quest.authorized"
        input = Read-ValidatedClientLock -Path (Join-Path $repoRoot "fixtures\broker-clients\admission-probe.client.json")
    },
    [ordered]@{
        grant_id = "grant.quest.native-renderer"
        input = Read-ValidatedClientLock -Path (Join-Path $repoRoot "fixtures\broker-clients\native-renderer.client.json")
    },
    [ordered]@{
        grant_id = "grant.quest.spatial-camera-panel"
        input = Read-ValidatedClientLock -Path (Join-Path $repoRoot "fixtures\broker-clients\spatial-camera-panel.client.json")
    }
)
$generatedGrants = @()
$packagedClientLocks = @()
foreach ($binding in $clientLockInputs) {
    $clientLock = $binding.input.lock
    $generatedCapabilities = @(Get-ExactClientGrantCapabilities -ClientLock $clientLock -ProductLock $acceptedProductLock)
    $generatedGrants += [ordered]@{
        grant_id = [string]$binding.grant_id
        client_lock_id = [string]$clientLock.feature_lock_id
        client_lock_fingerprint = "sha256:$([string]$binding.input.sha256)"
        identity = [ordered]@{
            client_id = [string]$clientLock.client_id
            platform_subject = [string]$clientLock.package_name
            signing_fingerprint = "sha256:$certificateSha256"
        }
        capabilities = $generatedCapabilities
        expires_at_ms = 4102444800000
        revoked = $false
    }
    $packagedClientLock = [ordered]@{
        grant_id = [string]$binding.grant_id
        client_lock_json = [string]$binding.input.json
        client_lock_sha256 = [string]$binding.input.sha256
    }
    if ($mediaSelected) {
        $mediaLifecycleAuthority = Read-MediaLifecycleAuthority `
            -ClientLock $clientLock `
            -RepoRoot $repoRoot `
            -MediaBindingByRelativePath $mediaBindingByRelativePath
        if ($null -ne $mediaLifecycleAuthority) {
            $packagedClientLock["media_lifecycle_authority"] = $mediaLifecycleAuthority
            $mediaLifecycleLock = $mediaLifecycleAuthority.media_lifecycle_lock_json | ConvertFrom-Json
            $initialLeases += [ordered]@{
                lease_id = [string]$mediaLifecycleLock.broker_runtime_lease_id
                scope = "lease.media.session"
                holder_id = [string]$mediaLifecycleLock.client_id
                expires_at_ms = 4102444800000
            }
        }
    }
    $packagedClientLocks += $packagedClientLock
}
$admissionConfig = [ordered]@{
    '$schema' = "rusty.quest.broker.admission_config.v1"
    snapshot = [ordered]@{
        '$schema' = "rusty.manifold.admission.snapshot.v2"
        authority_id = "authority.admission.quest"
        authority_revision = 1
        grants = $generatedGrants
        active_tokens = @()
        revoked_token_ids = @()
        consumed_request_ids = @()
        consumed_use_request_ids = @()
        reviewed_sweep_ids = @()
        audit_events = @()
        max_token_ttl_ms = 60000
    }
}
$runtimeConfig = [ordered]@{
    '$schema' = "rusty.quest.broker.runtime_config.v1"
    bridge_kind = "standalone_process_jni"
    adapter_config = [ordered]@{
        '$schema' = "rusty.manifold.broker.adapter_config.v2"
        adapter_id = "adapter.quest.manifold_broker.standalone"
        mode = "standalone"
        product_lock_id = [string]$productInputs.manifold_lock_id
        product_lock_fingerprint = [string]$productInputs.manifold_lock_fingerprint
        product_lock_sha256 = "sha256:$([string]$productInputs.manifold_lock_sha256)"
        authority_host_id = "host.quest.manifold_broker"
        authority_owner_id = "module.runtime.host"
    }
    product_lock = $acceptedProductLock
    packaged_authority = [ordered]@{
        product_spec_json = $acceptedProductSpecJson
        product_spec_sha256 = Get-FileSha256Hex -Path $canonicalProductSpecPath
        product_lock_json = $acceptedProductLockJson
        product_lock_sha256 = Get-FileSha256Hex -Path $acceptedProductLockPath
        client_locks = $packagedClientLocks
    }
    initial_leases = $initialLeases
    admission = $admissionConfig
}
if ($mediaSessionBindings.Count -eq 1) {
    $runtimeConfig["media_session"] = $mediaSessionBindings[0]
} elseif ($mediaSessionBindings.Count -gt 1) {
    $runtimeConfig["media_sessions"] = $mediaSessionBindings
}
$runtimeConfigPath = Join-Path $OutDir "broker-runtime-config.json"
[System.IO.File]::WriteAllText(
    $runtimeConfigPath,
    ($runtimeConfig | ConvertTo-Json -Depth 30),
    (New-Object System.Text.UTF8Encoding($false)))
$runtimeConfigSha256 = Get-RuntimeConfigDigest -RepoRoot $repoRoot -RuntimeConfigPath $runtimeConfigPath
if ($ValidateRuntimeConfigOnly) {
    Write-Output $runtimeConfigPath
    return
}
$runtimeConfigJson = $runtimeConfig | ConvertTo-Json -Depth 30 -Compress
$runtimeConfigJava = $runtimeConfigJson.Replace('\', '\\').Replace('"', '\"')
$generatedPackageDir = Join-Path $OutDir "generated\io\github\mesmerprism\rustymanifold\broker"
New-Item -ItemType Directory -Force -Path $generatedPackageDir | Out-Null
$generatedRuntimeConfigPath = Join-Path $generatedPackageDir "GeneratedBrokerRuntimeConfig.java"
$generatedRuntimeConfigSource = @"
package io.github.mesmerprism.rustymanifold.broker;

final class GeneratedBrokerRuntimeConfig {
    static final String JSON = "$runtimeConfigJava";
    static final String SHA256 = "$runtimeConfigSha256";
    private GeneratedBrokerRuntimeConfig() {}
}
"@
[System.IO.File]::WriteAllText(
    $generatedRuntimeConfigPath,
    $generatedRuntimeConfigSource,
    (New-Object System.Text.UTF8Encoding($false)))
$sourceFiles = Get-ChildItem -Path (Join-Path $appRoot "src\main\java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
$sourceFiles = @($sourceFiles) + @($generatedProductConfigPath, $generatedRuntimeConfigPath)
if ($sourceFiles.Count -eq 0) {
    throw "No Java sources found under $appRoot"
}
$sourceList = Join-Path $OutDir "sources.rsp"
$sourceFiles | Set-Content -Encoding ASCII -Path $sourceList

Invoke-Checked "javac" $javac @("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-bootclasspath", $platformJar, "-d", $classesDir, "@$sourceList")
Invoke-Checked "jar class pack" $jar @("cf", $classesJar, "-C", $classesDir, ".")
Invoke-Checked "d8" $d8 @("--lib", $platformJar, "--output", $dexDir, $classesJar)

$previousLinker = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
$previousCc = $env:CC_aarch64_linux_android
$previousAr = $env:AR_aarch64_linux_android
try {
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $androidClang
    $env:CC_aarch64_linux_android = $androidClang
    $env:AR_aarch64_linux_android = $androidAr
    Push-Location $repoRoot
    try {
        Invoke-Checked "standalone broker admission native" "cargo" @(
            "build",
            "--target", "aarch64-linux-android",
            "-p", "rusty-quest-manifold-broker-authority-native"
        )
    } finally {
        Pop-Location
    }
} finally {
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $previousLinker
    $env:CC_aarch64_linux_android = $previousCc
    $env:AR_aarch64_linux_android = $previousAr
}
$nativeSoSource = Join-Path $repoRoot "target\aarch64-linux-android\debug\librusty_quest_manifold_broker_authority.so"
if (-not (Test-Path -LiteralPath $nativeSoSource -PathType Leaf)) {
    throw "Standalone broker authority native library not found: $nativeSoSource"
}
$nativeLibRoot = Join-Path $OutDir "native-package"
$nativeAbiDir = Join-Path $nativeLibRoot "lib\arm64-v8a"
New-Item -ItemType Directory -Force -Path $nativeAbiDir | Out-Null
$nativeSoPackaged = Join-Path $nativeAbiDir "librusty_quest_manifold_broker_authority.so"
Copy-Item -LiteralPath $nativeSoSource -Destination $nativeSoPackaged
Invoke-Checked "aapt2 link" $aapt2 @(
    "link",
    "-o", $apkUnsigned,
    "--manifest", $generatedManifestPath,
    "-I", $platformJar,
    "--min-sdk-version", "29",
    "--target-sdk-version", "34",
    "--version-code", "1",
    "--version-name", "0.1.0"
)

Copy-Item $apkUnsigned $apkUnaligned
Invoke-Checked "jar dex update" $jar @("uf", $apkUnaligned, "-C", $dexDir, "classes.dex")
Invoke-Checked "jar native library update" $jar @("uf", $apkUnaligned, "-C", $nativeLibRoot, "lib")
$productPackageRoot = Join-Path $OutDir "product-package"
$productAssetDir = Join-Path $productPackageRoot "assets\manifold"
New-Item -ItemType Directory -Force -Path $productAssetDir | Out-Null
Copy-Item -LiteralPath $acceptedProductLockPath -Destination (Join-Path $productAssetDir "accepted-product-lock.json")
Copy-Item -LiteralPath $commandRegistryPath -Destination (Join-Path $productAssetDir "command-registry.json")
Copy-Item -LiteralPath $manifestProjectionPath -Destination (Join-Path $productAssetDir "manifest-projection.json")
Copy-Item -LiteralPath $runtimeConfigPath -Destination (Join-Path $productAssetDir "runtime-config.json")
Invoke-Checked "jar product assets update" $jar @("uf", $apkUnaligned, "-C", $productPackageRoot, "assets")
Invoke-Checked "zipalign" $zipalign @("-f", "4", $apkUnaligned, $apkAligned)

Invoke-Checked "apksigner" $apksigner @(
    "sign",
    "--ks", $Keystore,
    "--ks-pass", "pass:android",
    "--key-pass", "pass:android",
    "--out", $apkSigned,
    $apkAligned
)
Invoke-Checked "apksigner verification" $apksigner @("verify", "--verbose", $apkSigned)

$sha256 = Get-FileSha256Hex -Path $apkSigned
$manifestProjection = Get-Content -Raw -LiteralPath $manifestProjectionPath | ConvertFrom-Json
$androidPermissions = @($manifestProjection.permissions | ForEach-Object { [string]$_.name } | Sort-Object -Unique)
[xml]$generatedAndroidManifest = [System.IO.File]::ReadAllText($generatedManifestPath)
$androidNamespace = "http://schemas.android.com/apk/res/android"
$androidComponents = @()
foreach ($kind in @("activity", "service")) {
    foreach ($node in @($generatedAndroidManifest.manifest.application.$kind)) {
        $component = [ordered]@{
            kind = $kind
            name = [string]$node.GetAttribute("name", $androidNamespace)
            exported = [System.Convert]::ToBoolean($node.GetAttribute("exported", $androidNamespace))
        }
        $permission = [string]$node.GetAttribute("permission", $androidNamespace)
        if (-not [string]::IsNullOrWhiteSpace($permission)) {
            $component["permission"] = $permission
        }
        $foregroundServiceType = [string]$node.GetAttribute("foregroundServiceType", $androidNamespace)
        if (-not [string]::IsNullOrWhiteSpace($foregroundServiceType)) {
            $component["foreground_service_type"] = $foregroundServiceType
        }
        $androidComponents += $component
    }
}
$manifest = [ordered]@{
    '$schema' = "rusty.quest.manifold_broker_android.build_manifest.v2"
    package_name = "io.github.mesmerprism.rustymanifold.broker"
    activity = "io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity"
    authority = "rusty.manifold"
    endpoint_path = "/manifold/v1/events"
    broker_port = 8765
    admission_permission = "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION"
    admission_service = "io.github.mesmerprism.rustymanifold.broker/.ManifoldAdmissionService"
    admission_decision_owner = "rusty.manifold.admission"
    admission_client_signing_certificate_sha256 = $certificateSha256
    admission_native_library_sha256 = Get-FileSha256Hex -Path $nativeSoPackaged
    manifold_product_id = [string]$productInputs.product_id
    manifold_product_lock_id = [string]$productInputs.manifold_lock_id
    manifold_product_lock_fingerprint = [string]$productInputs.manifold_lock_fingerprint
    manifold_product_lock_sha256 = [string]$productInputs.manifold_lock_sha256
    manifold_product_spec_sha256 = [string]$productInputs.product_spec_sha256
    manifold_product_features = @($productInputs.features)
    manifold_product_modules = @($acceptedProductLock.module_ids)
    manifold_product_permissions = @($acceptedProductLock.permissions)
    android_permissions = $androidPermissions
    android_components = $androidComponents
    generated_android_manifest_sha256 = [string]$productInputs.android_manifest_sha256
    generated_manifest_projection_sha256 = [string]$productInputs.manifest_projection_sha256
    generated_command_registry_sha256 = [string]$productInputs.command_registry_sha256
    broker_runtime_config_sha256 = Get-FileSha256Hex -Path $runtimeConfigPath
    broker_runtime_config_canonical_sha256 = $runtimeConfigSha256
    packaged_client_lock_sha256 = @($clientLockInputs | ForEach-Object { [string]$_.input.sha256 })
    broker_runtime_provider_epoch_policy = "fresh-process-entropy_same-process-rebind-continuity"
    product_inputs_receipt_sha256 = Get-FileSha256Hex -Path $productInputsReceiptPath
    generated_android_manifest = $generatedManifestPath
    packaged_product_lock_asset = "assets/manifold/accepted-product-lock.json"
    packaged_command_registry_asset = "assets/manifold/command-registry.json"
    packaged_manifest_projection_asset = "assets/manifold/manifest-projection.json"
    packaged_runtime_config_asset = "assets/manifold/runtime-config.json"
    legacy_camera_p2p_compatibility = [bool]$LegacyCameraP2pCompatibility
    apk_path = $apkSigned
    apk_sha256 = $sha256
    validation = [ordered]@{
        product_spec_lock_validated = $true
        generated_manifest_validated = $true
        runtime_config_validated = $true
        apk_signature_verified = $true
        packaged_assets_validated = $true
    }
    live_stream_events_synthesized = $false
}
$manifestPath = Join-Path $OutDir "build-manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $manifestPath

Write-Output $apkSigned
