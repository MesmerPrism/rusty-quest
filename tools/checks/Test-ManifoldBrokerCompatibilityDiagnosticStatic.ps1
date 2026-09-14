[CmdletBinding()]
param(
    [string]$RepoRoot = ".",
    [Parameter(Mandatory=$true)][string]$BuildManifest,
    [Parameter(Mandatory=$true)][string]$RollbackEvidence,
    [Parameter(Mandatory=$true)][string]$FeatureLockPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{64}$')][string]$FeatureLockSha256,
    [Parameter(Mandatory=$true)][ValidateRange(1, [int]::MaxValue)][int]$FeatureLockRevision,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{64}$')][string]$FeatureLockFingerprint,
    [Parameter(Mandatory=$true)][ValidateRange(1, 2100000000)][int]$ExpectedVersionCode,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedSignerCertificateSha256,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$')][string]$ExpectedPackageName,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$')][string]$SpatialCameraPanelPackageName,
    [Parameter(Mandatory=$true)][string]$FileManagerCli,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{64}$')][string]$FileManagerSha256,
    [Parameter(Mandatory=$true)][string]$AdbPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{64}$')][string]$AdbSha256,
    [string]$OutPath = ""
)

$ErrorActionPreference = "Stop"
$ExpectedVersionName = "0.1.0-unit020-lan-20260913"
$ExpectedRollbackVersionName = "0.1.10103"
$ExpectedProductId = "broker.legacy_camera_p2p.standalone"
$ExpectedFeatureId = "q2q-broker-compatibility-diagnostic-v1"
$ExpectedProviderAuthority =
    "io.github.mesmerprism.rustymanifold.broker.debug-remote-camera-control"
$ExpectedBuildManifoldRevision = "ae3effb502e5b3bf565dc628b3ac74235397145d"
$ExpectedBuildManifoldTree = "4a148035b8692be171833a7ba235a391403c8256"
$ExpectedProducerDescriptorSourceRevision = "6cb398ae06c5c7c47fdcbd47d17768bc31725f3c"
$ExpectedLegacySpecSha256 = "007cac98547be79ddfdade70cfeedbca1c154034e52a8d62b59946f3dea5b314"
$ExpectedLegacyLockSha256 = "f311d4fa9f5ddd37f6936b33f996885d12997edb9f89c36048945eb1f339268d"

function Get-Sha256([Parameter(Mandatory=$true)][string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-TextSha256([Parameter(Mandatory=$true)][string]$Text) {
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Text)
    try {
        return [Convert]::ToHexString(
            [Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    } finally { [Array]::Clear($bytes, 0, $bytes.Length) }
}

function Resolve-ExactFile(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Label,
        [string]$ExpectedSha256 = "") {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label does not exist: $Path"
    }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    if (-not [string]::IsNullOrWhiteSpace($ExpectedSha256) -and
        (Get-Sha256 $resolved) -cne $ExpectedSha256) {
        throw "$Label SHA-256 mismatch."
    }
    return $resolved
}

function Assert-ExactProperties($Value, [string[]]$Expected, [string]$Label) {
    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $wanted = @($Expected | Sort-Object)
    if (($actual -join "`n") -cne ($wanted -join "`n")) {
        throw "$Label field set is not exact."
    }
}

function Assert-ExactStrings($Actual, [string[]]$Expected, [string]$Label) {
    $left = @($Actual | ForEach-Object { [string]$_ })
    if (($left -join "`n") -cne (@($Expected) -join "`n")) {
        throw "$Label is not the exact ordered set."
    }
}

function Assert-UniqueStrings($Actual, [string]$Label) {
    $values = @($Actual | ForEach-Object { [string]$_ })
    $unique = @($values | Sort-Object -Unique)
    if ($values.Count -ne $unique.Count) {
        throw "$Label contains duplicate values."
    }
}

function Assert-CanonicalStringSet($Actual, $Expected, [string]$Label) {
    $left = @($Actual | ForEach-Object { [string]$_ } | Sort-Object -Unique)
    $right = @($Expected | ForEach-Object { [string]$_ } | Sort-Object -Unique)
    if (($left -join "`n") -cne ($right -join "`n")) {
        throw "$Label is not the exact canonical set."
    }
}

function Invoke-JsonTool([string]$File, [string[]]$Arguments, [string]$Label) {
    $stderrPath = Join-Path ([IO.Path]::GetTempPath()) (
        "broker-compat-static-" + [Guid]::NewGuid().ToString("N") + ".stderr")
    try {
        $stdout = @(& $File @Arguments 2> $stderrPath)
        $exitCode = $LASTEXITCODE
        $stderr = if (Test-Path -LiteralPath $stderrPath) {
            [IO.File]::ReadAllText($stderrPath)
        } else { "" }
        if ($exitCode -ne 0) {
            throw "$Label failed with exit code $exitCode. $stderr"
        }
        $text = $stdout -join "`n"
        try { return $text | ConvertFrom-Json }
        catch { throw "$Label did not return one JSON document." }
    } finally {
        if (Test-Path -LiteralPath $stderrPath) {
            [IO.File]::Delete($stderrPath)
        }
    }
}

function Invoke-CheckedChild([string]$ScriptPath, [string[]]$Arguments, [string]$Label) {
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with child exit code $LASTEXITCODE."
    }
}

function Assert-ApkInspection($Inspection, [string]$Sha256, [string]$PackageName,
        [int]$VersionCode, [string]$VersionName, [string]$Signer, [string]$Label) {
    if ([string]$Inspection.Sha256 -cne $Sha256 -or
        [string]$Inspection.Identity.PackageName -cne $PackageName -or
        $null -ne $Inspection.Identity.SplitName -or
        [int64]$Inspection.Identity.VersionCode -ne $VersionCode -or
        [string]$Inspection.Identity.VersionName -cne $VersionName -or
        ([string]$Inspection.Identity.SignerSha256).ToLowerInvariant() -cne $Signer) {
        throw "$Label APK inspection differs from its exact package/version/signer tuple."
    }
}

function Get-ZipEntrySha256([IO.Compression.ZipArchive]$Archive, [string]$Name) {
    $entry = $Archive.GetEntry($Name)
    if ($null -eq $entry) { throw "Candidate APK is missing required entry: $Name" }
    $stream = $entry.Open()
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            return -join ($sha.ComputeHash($stream) | ForEach-Object {
                $_.ToString("x2") })
        } finally { $sha.Dispose() }
    } finally { $stream.Dispose() }
}

$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$buildManifestPath = Resolve-ExactFile $BuildManifest "BuildManifest"
$rollbackEvidencePath = Resolve-ExactFile $RollbackEvidence "RollbackEvidence"
$featureLock = Resolve-ExactFile $FeatureLockPath "FeatureLockPath" $FeatureLockSha256
$qfm = Resolve-ExactFile $FileManagerCli "FileManagerCli" $FileManagerSha256
$adb = Resolve-ExactFile $AdbPath "AdbPath" $AdbSha256
$buildManifestSha256 = Get-Sha256 $buildManifestPath
$rollbackEvidenceSha256 = Get-Sha256 $rollbackEvidencePath
$build = Get-Content -Raw -LiteralPath $buildManifestPath | ConvertFrom-Json
$rollback = Get-Content -Raw -LiteralPath $rollbackEvidencePath | ConvertFrom-Json
$lock = Get-Content -Raw -LiteralPath $featureLock | ConvertFrom-Json

if ([string]$build.'$schema' -cne "rusty.quest.manifold_broker_android.build_manifest.v2" -or
    [string]$build.package_name -cne $ExpectedPackageName -or
    [int64]$build.version_code -ne $ExpectedVersionCode -or
    [string]$build.version_name -cne $ExpectedVersionName -or
    [string]$build.manifold_product_id -cne $ExpectedProductId -or
    $build.legacy_camera_p2p_compatibility -isnot [bool] -or
    -not $build.legacy_camera_p2p_compatibility -or
    $build.remote_camera_debug_operator -isnot [bool] -or
    -not $build.remote_camera_debug_operator -or
    $build.shared_morphovision_signer_required -isnot [bool] -or
    -not $build.shared_morphovision_signer_required -or
    [string]$build.expected_shared_morphovision_signer_sha256 -cne
        $ExpectedSignerCertificateSha256 -or
    [string]$build.artifact_signer_sha256 -cne $ExpectedSignerCertificateSha256 -or
    [string]$build.spatial_camera_panel_package_name -cne
        $SpatialCameraPanelPackageName -or
    $build.spatial_camera_panel_package_specialized -isnot [bool] -or
    -not $build.spatial_camera_panel_package_specialized -or
    $build.manifold_source_root_explicit -isnot [bool] -or
    -not $build.manifold_source_root_explicit -or
    $build.manifold_source_tracked_clean -isnot [bool] -or
    -not $build.manifold_source_tracked_clean -or
    [string]$build.manifold_source_revision -cne $ExpectedBuildManifoldRevision -or
    [string]$build.manifold_source_tree -cne $ExpectedBuildManifoldTree -or
    $build.manifold_source_approved -isnot [bool] -or
    -not $build.manifold_source_approved -or
    $build.manifold_cargo_resolution_verified -isnot [bool] -or
    -not $build.manifold_cargo_resolution_verified) {
    throw "BuildManifest is outside the admitted supplier package closure."
}
$expectedManifoldPackages = @(
    "rusty-manifold-admission", "rusty-manifold-broker-adapter",
    "rusty-manifold-broker-product", "rusty-manifold-media-session",
    "rusty-manifold-model", "rusty-manifold-peer",
    "rusty-manifold-peer-runtime-host", "rusty-manifold-runtime-host")
Assert-ExactStrings @($build.manifold_cargo_dependency_packages) `
    $expectedManifoldPackages "BuildManifest isolated Manifold dependency packages"

$expectedBindings = @(
    "fixtures/media-runtime-products/camera2-surface.binding.json",
    "fixtures/media-runtime-products/spatial-camera-panel-display.binding.json")
$actualBindings = @($build.media_session_bindings | ForEach-Object { [string]$_.path })
Assert-ExactStrings $actualBindings $expectedBindings "BuildManifest media bindings"
foreach ($binding in @($build.media_session_bindings)) {
    if ([string]$binding.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
        [string]::IsNullOrWhiteSpace([string]$binding.manifold_session_id) -or
        [string]::IsNullOrWhiteSpace([string]$binding.quest_runtime_spec_id)) {
        throw "BuildManifest media binding identity is incomplete."
    }
}

$diagnosticBuild = $build.compatibility_diagnostic
$missingRequiredDiagnosticFlags = @(
    "static_gate_required", "rollback_evidence_required") | Where-Object {
        $diagnosticBuild.$_ -isnot [bool] -or -not $diagnosticBuild.$_
    }
$enabledProhibitedDiagnosticFlags = @(
    "uninstall_permitted", "data_clear_permitted", "global_log_clear_permitted",
    "blanket_force_stop_permitted", "downgrade_permitted", "adb_lifecycle_permitted") |
    Where-Object { $diagnosticBuild.$_ -isnot [bool] -or $diagnosticBuild.$_ }
if ([string]$diagnosticBuild.schema -cne
        "rusty.quest.manifold_broker.compatibility_diagnostic_build.v1" -or
    [string]$diagnosticBuild.remote_camera_debug_provider_authority -cne
        $ExpectedProviderAuthority -or
    [string]$diagnosticBuild.install_policy -cne
        "inspected-same-version-replace-with-installed-byte-readback" -or
    [string]$diagnosticBuild.restore_policy -cne
        "same-version-same-signer-rollback-reinstall" -or
    $missingRequiredDiagnosticFlags.Count -ne 0 -or
    $enabledProhibitedDiagnosticFlags.Count -ne 0) {
    throw "BuildManifest diagnostic safety closure is incomplete."
}

$buildRoot = Split-Path -Parent $buildManifestPath
$artifactPaths = [ordered]@{
    apk = Join-Path $buildRoot "rusty-manifold-broker.apk"
    runtime = Join-Path $buildRoot "broker-runtime-config.json"
    product_receipt = Join-Path $buildRoot "product-inputs\product-package-inputs.json"
    spec = Join-Path $buildRoot "product-inputs\product-spec.json"
    lock = Join-Path $buildRoot "product-inputs\accepted-product-lock.json"
    projection = Join-Path $buildRoot "product-inputs\manifest-projection.json"
    android_manifest = Join-Path $buildRoot "product-inputs\AndroidManifest.xml"
    registry = Join-Path $buildRoot "product-inputs\command-registry.json"
}
foreach ($artifactPath in $artifactPaths.Values) {
    if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
        throw "Build artifact closure is missing: $artifactPath"
    }
}
if ((Resolve-Path -LiteralPath ([string]$build.apk_path)).Path -cne
        (Resolve-Path -LiteralPath $artifactPaths.apk).Path) {
    throw "BuildManifest APK path escapes its closed build directory."
}
$apkPath = Resolve-ExactFile ([string]$build.apk_path) "BuildManifest APK"
if ((Get-Sha256 $apkPath) -cne [string]$build.apk_sha256) {
    throw "BuildManifest APK bytes drifted."
}
$productReceipt = Get-Content -Raw -LiteralPath $artifactPaths.product_receipt |
    ConvertFrom-Json
$productLock = Get-Content -Raw -LiteralPath $artifactPaths.lock | ConvertFrom-Json
$projection = Get-Content -Raw -LiteralPath $artifactPaths.projection | ConvertFrom-Json
if ([string]$productReceipt.'$schema' -cne
        "rusty.quest.broker.android_package_inputs.v1" -or
    [string]$productReceipt.product_id -cne $ExpectedProductId -or
    [string]$build.manifold_product_lock_id -cne
        [string]$productReceipt.manifold_lock_id -or
    [string]$build.manifold_product_lock_fingerprint -cne
        [string]$productReceipt.manifold_lock_fingerprint -or
    [string]$build.manifold_product_spec_sha256 -cne (Get-Sha256 $artifactPaths.spec) -or
    [string]$build.manifold_product_lock_sha256 -cne (Get-Sha256 $artifactPaths.lock) -or
    [string]$build.generated_manifest_projection_sha256 -cne
        (Get-Sha256 $artifactPaths.projection) -or
    [string]$build.generated_android_manifest_sha256 -cne
        (Get-Sha256 $artifactPaths.android_manifest) -or
    [string]$build.generated_command_registry_sha256 -cne
        (Get-Sha256 $artifactPaths.registry) -or
    [string]$build.broker_runtime_config_sha256 -cne
        (Get-Sha256 $artifactPaths.runtime) -or
    [string]$build.product_inputs_receipt_sha256 -cne
        (Get-Sha256 $artifactPaths.product_receipt) -or
    (Get-Sha256 $artifactPaths.spec) -cne $ExpectedLegacySpecSha256 -or
    (Get-Sha256 $artifactPaths.lock) -cne $ExpectedLegacyLockSha256) {
    throw "Build manifest does not close over its exact prepared product artifacts."
}
Assert-ExactStrings @($build.manifold_product_features) @($productLock.features) `
    "BuildManifest product features"
Assert-ExactStrings @($build.manifold_product_modules) @($productLock.module_ids) `
    "BuildManifest product modules"
Assert-ExactStrings @($build.manifold_product_permissions) @($productLock.permissions) `
    "BuildManifest product permissions"
Assert-ExactStrings @($build.android_permissions) @(
    $projection.permissions | ForEach-Object { [string]$_.name } |
        Sort-Object -Unique) "BuildManifest Android permissions"

$runtime = Get-Content -Raw -LiteralPath $artifactPaths.runtime | ConvertFrom-Json
$spatialPackagedClient = @($runtime.packaged_authority.client_locks |
    Where-Object { [string]$_.grant_id -ceq "grant.quest.spatial-camera-panel" })
$spatialGrant = @($runtime.admission.snapshot.grants |
    Where-Object { [string]$_.grant_id -ceq "grant.quest.spatial-camera-panel" })
if ($spatialPackagedClient.Count -ne 1 -or $spatialGrant.Count -ne 1) {
    throw "Runtime config does not contain one exact Spatial client/grant pair."
}
$specializedClient = [string]$spatialPackagedClient[0].client_lock_json |
    ConvertFrom-Json
$lifecycleAuthority = $spatialPackagedClient[0].media_lifecycle_authority
$specializedLifecycle = [string]$lifecycleAuthority.media_lifecycle_lock_json |
    ConvertFrom-Json
$expectedSpatialCapabilities = @(
    "capability.command.media.session.start",
    "capability.command.media.session.stop",
    "capability.command.session.list",
    "capability.media.session.observe",
    "capability.peer.session.observe",
    "capability.sink.spatial-sdk")
if ([string]$specializedClient.package_name -cne $SpatialCameraPanelPackageName -or
    [string]$specializedLifecycle.package_name -cne $SpatialCameraPanelPackageName -or
    [string]$spatialGrant[0].identity.platform_subject -cne
        $SpatialCameraPanelPackageName -or
    [string]$spatialGrant[0].identity.signing_fingerprint -cne
        "sha256:$ExpectedSignerCertificateSha256" -or
    [string]$build.spatial_camera_panel_client_lock_sha256 -cne
        (Get-TextSha256 ([string]$spatialPackagedClient[0].client_lock_json)) -or
    [string]$spatialPackagedClient[0].client_lock_sha256 -cne
        [string]$build.spatial_camera_panel_client_lock_sha256 -or
    [string]$build.spatial_camera_panel_media_lifecycle_sha256 -cne
        (Get-TextSha256 ([string]$lifecycleAuthority.media_lifecycle_lock_json)) -or
    [string]$lifecycleAuthority.media_lifecycle_lock_sha256 -cne
        [string]$build.spatial_camera_panel_media_lifecycle_sha256 -or
    [string]$build.spatial_camera_panel_grant_sha256 -cne
        (Get-TextSha256 ($spatialGrant[0] | ConvertTo-Json -Depth 20 -Compress))) {
    throw "Specialized Spatial client/grant/lifecycle digest closure is invalid."
}
Assert-ExactStrings @($spatialGrant[0].capabilities) $expectedSpatialCapabilities `
    "specialized Spatial grant capabilities"

$runtimeValidator = Join-Path $buildRoot `
    "isolated-cargo\target\debug\runtime_config_digest.exe"
if (-not (Test-Path -LiteralPath $runtimeValidator -PathType Leaf) -or
    (Get-Sha256 $runtimeValidator) -cne
        [string]$build.broker_runtime_config_validator_sha256) {
    throw "Canonical runtime-config authority validator bytes drifted."
}
$digestOutput = @(& $runtimeValidator $artifactPaths.runtime 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Canonical runtime-config authority rejected the packaged runtime config."
}
$canonicalDigest = @($digestOutput | ForEach-Object { ([string]$_).Trim() } |
    Where-Object { $_ -cmatch '^[0-9a-f]{64}$' }) | Select-Object -Last 1
if ([string]$canonicalDigest -cne
    [string]$build.broker_runtime_config_canonical_sha256) {
    throw "Canonical runtime-config authority digest drifted."
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $duplicateEntries = @($archive.Entries | Group-Object FullName |
        Where-Object Count -gt 1)
    if ($duplicateEntries.Count -ne 0) {
        throw "Candidate APK contains duplicate ZIP entries."
    }
    foreach ($asset in @(
        @{name="assets/manifold/accepted-product-lock.json";path=$artifactPaths.lock},
        @{name="assets/manifold/command-registry.json";path=$artifactPaths.registry},
        @{name="assets/manifold/manifest-projection.json";path=$artifactPaths.projection},
        @{name="assets/manifold/runtime-config.json";path=$artifactPaths.runtime})) {
        if ((Get-ZipEntrySha256 $archive $asset.name) -cne (Get-Sha256 $asset.path)) {
            throw "Candidate APK packaged asset hash drifted: $($asset.name)"
        }
    }
    $nativeEntrySha256 = Get-ZipEntrySha256 $archive `
        "lib/arm64-v8a/librusty_quest_manifold_broker_authority.so"
    if ($nativeEntrySha256 -cne
        [string]$build.admission_native_library_sha256) {
        throw "Candidate APK native authority bytes drifted."
    }
} finally { $archive.Dispose() }
if ((Get-Item -LiteralPath $apkPath).Length -ne [int64]$build.apk_size) {
    throw "Candidate APK size differs from BuildManifest."
}

$buildTools = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME "build-tools") `
    -Directory | Sort-Object Name -Descending | Select-Object -First 1
if ($null -eq $buildTools) { throw "Android build-tools are unavailable." }
$aapt2 = Join-Path $buildTools.FullName "aapt2.exe"
$permissionOutput = @(& $aapt2 dump permissions $apkPath 2>&1)
if ($LASTEXITCODE -ne 0) { throw "aapt2 permission inspection failed." }
$apkPermissions = @($permissionOutput | ForEach-Object {
    if ([string]$_ -match "^uses-permission: name='([^']+)'(?: .*)?$") { $Matches[1] }
} | Where-Object { $_ } | Sort-Object -Unique)
Assert-ExactStrings $apkPermissions @($build.android_permissions) `
    "actual candidate APK permissions"
$xmlTree = @(& $aapt2 dump xmltree --file AndroidManifest.xml $apkPath 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { throw "aapt2 component inspection failed." }
$androidAttributeMatches = [regex]::Matches($xmlTree,
    '(?m)^\s*A:\s+http://schemas\.android\.com/apk/res/android:(?<attribute>[A-Za-z]+)(?:\(0x[0-9a-fA-F]+\))?="(?<value>[^"]*)"')
foreach ($component in @($build.android_components)) {
    $componentMatches = @($androidAttributeMatches | Where-Object {
        $_.Groups['attribute'].Value -ceq 'name' -and
        $_.Groups['value'].Value -ceq [string]$component.name
    })
    if ($componentMatches.Count -ne 1) {
        throw "Actual APK component is missing or duplicated: $($component.name)"
    }
}
$providerAttributes = [ordered]@{
    name = '.RemoteCameraDebugControlProvider'
    authorities = $ExpectedProviderAuthority
    permission = 'android.permission.DUMP'
}
foreach ($providerAttribute in $providerAttributes.GetEnumerator()) {
    $providerMatches = @($androidAttributeMatches | Where-Object {
        $_.Groups['attribute'].Value -ceq $providerAttribute.Key -and
        $_.Groups['value'].Value -ceq $providerAttribute.Value
    })
    if ($providerMatches.Count -ne 1) {
        throw "Actual APK remote-camera provider closure is invalid: $($providerAttribute.Value)"
    }
}

Assert-ExactProperties $rollback @(
    '$schema', 'candidate_apk_sha256', 'package_name', 'version_code',
    'candidate_version_name',
    'signer_certificate_sha256', 'rollback_apk', 'captured_from_installed_bytes',
    'same_version_restore', 'preservation') "RollbackEvidence"
Assert-ExactProperties $rollback.rollback_apk @('path', 'sha256', 'actual_version_name') `
    "RollbackEvidence.rollback_apk"
Assert-ExactProperties $rollback.preservation @(
    'uninstall_required', 'data_clear_required', 'global_log_clear_required',
    'blanket_force_stop_required', 'downgrade_required', 'adb_lifecycle_required') `
    "RollbackEvidence.preservation"
if ([string]$rollback.'$schema' -cne
        "rusty.quest.manifold_broker.compatibility_rollback_evidence.v1" -or
    [string]$rollback.candidate_apk_sha256 -cne [string]$build.apk_sha256 -or
    [string]$rollback.package_name -cne $ExpectedPackageName -or
    [int64]$rollback.version_code -ne $ExpectedVersionCode -or
    [string]$rollback.candidate_version_name -cne $ExpectedVersionName -or
    [string]$rollback.signer_certificate_sha256 -cne
        $ExpectedSignerCertificateSha256 -or
    $rollback.captured_from_installed_bytes -isnot [bool] -or
    -not $rollback.captured_from_installed_bytes -or
    $rollback.same_version_restore -isnot [bool] -or -not $rollback.same_version_restore -or
    [string]$rollback.rollback_apk.actual_version_name -cne
        $ExpectedRollbackVersionName -or
    @($rollback.preservation.PSObject.Properties | Where-Object {
        $_.Value -isnot [bool] -or $_.Value }).Count -ne 0) {
    throw "RollbackEvidence does not prove one non-destructive same-version restore tuple."
}
$rollbackApk = Resolve-ExactFile ([string]$rollback.rollback_apk.path) `
    "RollbackEvidence APK" ([string]$rollback.rollback_apk.sha256)
if ([string]$rollback.rollback_apk.sha256 -ceq [string]$build.apk_sha256) {
    throw "RollbackEvidence must preserve prior installed bytes distinct from the candidate."
}

$candidateInspection = Invoke-JsonTool $qfm @("apk", "inspect", "--file", $apkPath, "--json") `
    "candidate APK inspection"
$rollbackInspection = Invoke-JsonTool $qfm @("apk", "inspect", "--file", $rollbackApk, "--json") `
    "rollback APK inspection"
Assert-ApkInspection $candidateInspection ([string]$build.apk_sha256) $ExpectedPackageName `
    $ExpectedVersionCode $ExpectedVersionName $ExpectedSignerCertificateSha256 "candidate"
Assert-ApkInspection $rollbackInspection ([string]$rollback.rollback_apk.sha256) `
    $ExpectedPackageName $ExpectedVersionCode `
    ([string]$rollback.rollback_apk.actual_version_name) `
    $ExpectedSignerCertificateSha256 "rollback"

if ([int64]$lock.revision -ne $FeatureLockRevision -or
    [string]$lock.lock_fingerprint -cne $FeatureLockFingerprint -or
    [string]$lock.schema -cne "rusty.morphospace.workflow.feature_lock.v2" -or
    [string]$lock.project_id -cne "rusty-quest-to-quest-streaming" -or
    @($lock.denied_features).Count -ne 0) {
    throw "Feature lock does not select the exact compatibility diagnostic revision/fingerprint."
}
$expectedSelectedFeatures = @(
    "q2q-broker-producer-handoff-v1",
    "q2q-broker-compatibility-diagnostic-v1")
Assert-ExactStrings @($lock.selected_features) $expectedSelectedFeatures `
    "selected feature closure"
if (@($lock.features).Count -ne 2) {
    throw "Feature lock must contain exactly the producer and diagnostic features."
}
$producerFeature = @($lock.features | Where-Object {
    [string]$_.feature_id -ceq "q2q-broker-producer-handoff-v1" })
$feature = @($lock.features | Where-Object { [string]$_.feature_id -ceq $ExpectedFeatureId })
if ($producerFeature.Count -ne 1 -or $feature.Count -ne 1 -or
    $producerFeature[0].selected -isnot [bool] -or
    -not $producerFeature[0].selected -or
    $feature[0].selected -isnot [bool] -or
    -not $feature[0].selected -or
    [string]$producerFeature[0].module_id -cne "q2q-broker-producer-handoff" -or
    [string]$feature[0].module_id -cne "q2q-broker-compatibility-diagnostic" -or
    @($producerFeature[0].dependencies).Count -ne 0 -or
    @($feature[0].dependencies).Count -ne 1 -or
    [string]$feature[0].dependencies[0] -cne "q2q-broker-producer-handoff-v1" -or
    @($producerFeature[0].conflicts).Count -ne 0 -or
    @($feature[0].conflicts).Count -ne 0 -or
    [string]$producerFeature[0].descriptor.source_revision -cne
        $ExpectedProducerDescriptorSourceRevision -or
    [string]$producerFeature[0].descriptor.sha256 -cne $ExpectedLegacyLockSha256 -or
    [string]$producerFeature[0].descriptor.source_sha256 -cne
        $ExpectedLegacyLockSha256 -or
    [string]$feature[0].activation.rule -cne "selected-lock-and-runtime-input" -or
    [string]$feature[0].activation.effective_marker -cne
        "rusty.quest.broker.compatibility_diagnostic.effective") {
    throw "Selected diagnostic feature activation is not exact."
}
$expectedRuntimeInputs = @(
    "broker.compatibility-diagnostic.build-manifest",
    "broker.compatibility-diagnostic.rollback-evidence",
    "broker.compatibility-diagnostic.static-gate-receipt",
    "broker.compatibility-diagnostic.feature-lock-path",
    "broker.compatibility-diagnostic.feature-lock-sha256",
    "broker.compatibility-diagnostic.feature-lock-revision",
    "broker.compatibility-diagnostic.feature-lock-fingerprint",
    "broker.compatibility-diagnostic.expected-version-code",
    "broker.compatibility-diagnostic.signer-certificate-sha256",
    "broker.compatibility-diagnostic.broker-package-name",
    "broker.compatibility-diagnostic.spatial-camera-panel-package-name",
    "broker.compatibility-diagnostic.file-manager-cli",
    "broker.compatibility-diagnostic.file-manager-sha256",
    "broker.compatibility-diagnostic.adb-path",
    "broker.compatibility-diagnostic.adb-sha256",
    "broker.compatibility-diagnostic.serial")
Assert-ExactStrings @($feature[0].activation.runtime_inputs) $expectedRuntimeInputs `
    "diagnostic runtime inputs"
Assert-ExactStrings @($feature[0].effects.inputs) $expectedRuntimeInputs `
    "diagnostic effect inputs"
Assert-ExactStrings @($feature[0].effects.markers) @(
    "rusty.quest.broker.compatibility_diagnostic.effective") `
    "diagnostic effect markers"
Assert-ExactStrings @($feature[0].effects.tools) @(
    "tools/Invoke-ManifoldBrokerCompatibilityDiagnostic.ps1",
    "tools/checks/Test-ManifoldBrokerCompatibilityDiagnosticStatic.ps1") `
    "diagnostic effect tools"
foreach ($emptyEffect in @(
    "activities", "assets", "commands", "native_libraries", "permissions",
    "queries", "routes", "scenes", "services", "shaders", "streams")) {
    if (@($feature[0].effects.$emptyEffect).Count -ne 0) {
        throw "Diagnostic feature unexpectedly widens $emptyEffect."
    }
}
$effectKeys = @(
    "activities", "assets", "commands", "inputs", "markers",
    "native_libraries", "permissions", "queries", "routes", "scenes",
    "services", "shaders", "streams", "tools")
Assert-ExactStrings @($lock.effect_union.PSObject.Properties.Name) $effectKeys `
    "feature effect-union categories"
foreach ($effectKey in $effectKeys) {
    foreach ($declaredFeature in @($lock.features)) {
        Assert-UniqueStrings @($declaredFeature.effects.$effectKey) `
            "feature $($declaredFeature.feature_id) effect $effectKey"
    }
    Assert-UniqueStrings @($lock.effect_union.$effectKey) `
        "feature effect union $effectKey"
    $recomputed = @($lock.features | ForEach-Object {
        @($_.effects.$effectKey) } | ForEach-Object { [string]$_ })
    Assert-CanonicalStringSet @($lock.effect_union.$effectKey) $recomputed `
        "feature effect union $effectKey"
}
$expectedApkPermissionsFromFeature = @($lock.effect_union.permissions |
    Where-Object { [string]$_ -cne
        "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION" })
Assert-ExactStrings $apkPermissions $expectedApkPermissionsFromFeature `
    "feature-lock-to-APK permission closure"

$diagnosticScript = Join-Path $repo "tools\Invoke-ManifoldBrokerCompatibilityDiagnostic.ps1"
$clientGate = Join-Path $repo "tools\checks\Test-ManifoldBrokerClientSpecializationStatic.ps1"
foreach ($path in @($diagnosticScript, $clientGate)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required supplier diagnostic source is missing: $path"
    }
}
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    $diagnosticScript, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -ne 0) {
    throw "Compatibility diagnostic PowerShell syntax is invalid."
}
$forbiddenCommandPattern =
    '(?i)(\buninstall\b|\bpm\s+clear\b|\blogcat\b[^\r\n]*\s-c\b|--downgrade\b|\s-d\b|\bkill-server\b|\bstart-server\b|\breconnect\b|\btcpip\b|\bdisconnect\b)'
$unsafeCommands = @($ast.FindAll({
    param($node)
    $node -is [Management.Automation.Language.CommandAst] -and
        ($node.Extent.Text -match $forbiddenCommandPattern -or
         ($node.Extent.Text -match '(?i)\bforce-stop\b' -and
          $node.Extent.Text -cnotmatch '\$ExpectedPackageName'))
}, $true))
if ($unsafeCommands.Count -ne 0) {
    throw "Compatibility diagnostic contains a prohibited device command."
}
Invoke-CheckedChild $clientGate @("-RepoRoot", $repo) "client specialization static gate"

$validatorSourcePath = (Resolve-Path -LiteralPath $PSCommandPath).Path
$validatorSourceSha256 = Get-Sha256 $validatorSourcePath
$inputTupleCanonical = @(
    "schema=rusty.quest.manifold_broker.compatibility_static_gate_input_tuple.v1",
    "validator_source_sha256=$validatorSourceSha256",
    "build_manifest_path=$buildManifestPath",
    "build_manifest_sha256=$buildManifestSha256",
    "rollback_evidence_path=$rollbackEvidencePath",
    "rollback_evidence_sha256=$rollbackEvidenceSha256",
    "feature_lock_path=$featureLock",
    "feature_lock_sha256=$FeatureLockSha256",
    "feature_lock_revision=$FeatureLockRevision",
    "feature_lock_fingerprint=$FeatureLockFingerprint",
    "expected_version_code=$ExpectedVersionCode",
    "expected_version_name=$ExpectedVersionName",
    "expected_signer_certificate_sha256=$ExpectedSignerCertificateSha256",
    "expected_package_name=$ExpectedPackageName",
    "spatial_camera_panel_package_name=$SpatialCameraPanelPackageName",
    "file_manager_cli_path=$qfm",
    "file_manager_cli_sha256=$FileManagerSha256",
    "adb_path=$adb",
    "adb_sha256=$AdbSha256") -join "`n"
$inputTupleSha256 = Get-TextSha256 $inputTupleCanonical

$retainedGateRoot = [IO.Path]::GetFullPath(
    (Join-Path $repo "target\manifold-broker-compatibility-gates")).TrimEnd('\', '/')
if ([string]::IsNullOrWhiteSpace($OutPath)) {
    $OutPath = Join-Path (Join-Path $retainedGateRoot $inputTupleSha256) `
        "static-gate.json"
}
$resolvedOut = [IO.Path]::GetFullPath($OutPath).TrimEnd('\', '/')
$separator = [string][IO.Path]::DirectorySeparatorChar
if (-not $resolvedOut.StartsWith($retainedGateRoot + $separator,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw "Static gate receipt must be under the separate retained gate root."
}
$buildRootFull = [IO.Path]::GetFullPath($buildRoot).TrimEnd('\', '/')
if ($resolvedOut.StartsWith($buildRootFull + $separator,
        [StringComparison]::OrdinalIgnoreCase) -or
    $buildRootFull.StartsWith((Split-Path -Parent $resolvedOut) + $separator,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw "Static gate receipt must be disjoint from the reusable build output."
}
foreach ($retainedInput in @($rollbackEvidencePath, $featureLock, $qfm, $adb)) {
    $retainedFull = [IO.Path]::GetFullPath($retainedInput).TrimEnd('\', '/')
    if ($retainedFull.Equals($resolvedOut, [StringComparison]::OrdinalIgnoreCase) -or
        $retainedFull.StartsWith($resolvedOut + $separator,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Static gate output collides with a retained input."
    }
}
if (Test-Path -LiteralPath $resolvedOut) {
    throw "Static gate receipt already exists; preserve the prior content-addressed result."
}
$receipt = [ordered]@{
    '$schema' = "rusty.quest.manifold_broker.compatibility_static_gate_receipt.v1"
    status = "passed"
    generated_at_utc = [DateTime]::UtcNow.ToString("O")
    validator = [ordered]@{
        path=$validatorSourcePath; sha256=$validatorSourceSha256
    }
    input_tuple = [ordered]@{
        schema="rusty.quest.manifold_broker.compatibility_static_gate_input_tuple.v1"
        sha256=$inputTupleSha256
    }
    build_manifest = [ordered]@{ path=$buildManifestPath; sha256=$buildManifestSha256 }
    rollback_evidence = [ordered]@{ path=$rollbackEvidencePath; sha256=$rollbackEvidenceSha256 }
    feature_lock = [ordered]@{
        path=$featureLock; sha256=$FeatureLockSha256; revision=$FeatureLockRevision
        fingerprint=$FeatureLockFingerprint; feature_id=$ExpectedFeatureId
    }
    package = [ordered]@{
        broker=$ExpectedPackageName; spatial_camera_panel=$SpatialCameraPanelPackageName
        version_code=$ExpectedVersionCode; version_name=$ExpectedVersionName
        signer_certificate_sha256=$ExpectedSignerCertificateSha256
        candidate_apk_sha256=[string]$build.apk_sha256
        rollback_apk_sha256=[string]$rollback.rollback_apk.sha256
    }
    tools = [ordered]@{
        file_manager_cli_sha256=$FileManagerSha256; adb_sha256=$AdbSha256
    }
    safety = [ordered]@{
        inspected_before_effects=$true; same_version_restore=$true
        uninstall_permitted=$false; data_clear_permitted=$false
        global_log_clear_permitted=$false; blanket_force_stop_permitted=$false
        downgrade_permitted=$false; adb_lifecycle_permitted=$false
    }
    does_not_prove = @(
        "Does not install, launch, mutate a device, prove a final media graph, prove a hot Local consumer, publish, or mutate a remote.")
}
$parent = Split-Path -Parent $resolvedOut
$cursor = $parent
while (-not [string]::IsNullOrWhiteSpace($cursor)) {
    if (Test-Path -LiteralPath $cursor) {
        if (((Get-Item -LiteralPath $cursor -Force).Attributes -band
                [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Static gate output cannot traverse a reparse point: $cursor"
        }
    }
    $next = Split-Path -Parent $cursor
    if ($next -ceq $cursor) { break }
    $cursor = $next
}
if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
    [void](New-Item -ItemType Directory -Path $parent -Force)
}
[IO.File]::WriteAllText($resolvedOut, ($receipt | ConvertTo-Json -Depth 20),
    [Text.UTF8Encoding]::new($false))
Write-Output $resolvedOut
