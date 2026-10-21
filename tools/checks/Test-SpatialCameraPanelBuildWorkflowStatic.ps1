param([string]$RepoRoot,[string]$PrerequisiteInputSpecPath='', [string]$PrerequisiteOutRoot='')

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$buildPath = Join-Path $repo "tools\Build-SpatialCameraPanelAndroid.ps1"
$testPath = Join-Path $repo "tools\Test-SpatialCameraPanelAndroid.ps1"
$gradlePath = Join-Path $repo "apps\spatial-camera-panel-android\app\build.gradle.kts"
foreach ($path in @($buildPath, $testPath, $gradlePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing build workflow input: $path" }
}

$build = Get-Content -LiteralPath $buildPath -Raw
$prerequisiteModule=Join-Path $repo 'tools/lib/SpatialCameraPanelBuildPrerequisites.psm1'
if(-not(Test-Path -LiteralPath $prerequisiteModule -PathType Leaf)){throw 'Shared prerequisite module missing.'}
$prerequisiteSource=Get-Content -LiteralPath $prerequisiteModule -Raw
$buildAndPrerequisites=$build+"`n"+$prerequisiteSource
$test = Get-Content -LiteralPath $testPath -Raw
$gradle = Get-Content -LiteralPath $gradlePath -Raw

function Require([string]$Text, [string]$Token, [string]$Message) {
    if (-not $Text.Contains($Token, [StringComparison]::Ordinal)) { throw $Message }
}

foreach ($token in @(
    '[ValidateSet("DevFast", "Candidate")]',
    '[string]$BuildToolsVersion = "36.0.0"',
    '[switch]$AllowNonDeployableDynamicStdBenchmark',
    '[string]$BuildCacheRoot = $env:RUSTY_QUEST_BUILD_CACHE_ROOT',
    'Join-Path $workspaceDrive "b\mv"',
    'native_cache_identity.v1',
    'android_shell_cache_identity.v1',
    'package_cache_identity.v1',
    'build_cache_identities.v1',
    '$nativeReceiptTargetDir = Join-Path $BuildCacheRoot',
    '$gradleUserHome = Join-Path $BuildCacheRoot "gu"',
    '$gradleProjectCacheDir = Join-Path $BuildCacheRoot "gp"',
    '$appBuildDir = Join-Path $productBuildRoot "a"',
    '"--no-daemon"',
    'gradle_daemon = $false',
    'android.aapt2FromMavenOverride=$shortAapt2',
    "Observe 'aapt2'",
    "Observe 'android-clang'",
    "Observe 'java'",
    "Observe 'zipalign'",
    "Observe 'apksigner'",
    'Pkg\.Revision',
    'selected_aapt2_sha256',
    'pinned Temurin JDK17',
    'Dynamic Rust std is restricted to an explicitly labeled non-deployable benchmark.',
    'Candidate APKs require static Rust std linkage.',
    '[System.Threading.Mutex]::new',
    'serialized stable Spatial Camera Panel build cache lane',
    'target" "list" "--installed"',
    'Set-TextFileIfChanged',
    'Copy-FileIfChanged',
    'build_phase_receipts.v1',
    'BUILD_PHASE native-compile-link',
    'BUILD_PHASE android-shell-resources-dex-apk',
    'prior_cache_available',
    'cargo_observed_fresh',
    'cargo_compile_unit_count',
    'gradle_observed_outcomes',
    'TimeUnit.NANOSECONDS.toMillis',
    'from_cache',
    'BUILD_PHASE zipalign-sign-inspection',
    'zipalign "-c" "-P" "16"',
    'llvm-readelf',
    'plaintext video media',
    'private_path_recorded = $false'
)) {
    Require $buildAndPrerequisites $token "Spatial Camera Panel build workflow is missing: $token"
}

if ($build.Contains('$intermediateRoot = Join-Path $targetRoot ("apk-i\{0}" -f $buildInputFingerprint', [StringComparison]::Ordinal)) {
    throw "The complete APK fingerprint still selects compiler intermediates."
}
foreach ($forbidden in @('$nativeCacheHit', '$shellCacheHit', '$packageCacheHit', 'build_cache_hits')) {
    if ($build.Contains($forbidden, [StringComparison]::Ordinal)) {
        throw "Build receipts still overclaim a cache hit instead of prior-cache availability: $forbidden"
    }
}
if ($build.IndexOf('$prerequisites = Get-QuestSpatialBuildPrerequisites', [StringComparison]::Ordinal) -gt
    $build.IndexOf('Spatial Camera Panel native receipt cargo build', [StringComparison]::Ordinal)) {
    throw "The signer mismatch gate occurs after native compilation."
}
foreach ($token in @(
    'Shared-package and Candidate builds require explicit local signer input.',
    '$SharedSpatialSignerSha256 = "722f1f3dcb921918d2e02f39f1b1bd8f9ff2812e07757c5fc665f6b8f7ee32a8"',
    'Explicit Spatial Camera Panel signer fingerprint mismatch before compilation.',
    'signer_path_alias_password_recorded = $false'
)) {
    Require $buildAndPrerequisites $token "Shared-package signer fail-closed gate is missing: $token"
}

foreach ($token in @(
    'Join-Path $BuildCacheRoot "gu"',
    'Join-Path $BuildCacheRoot "gp"',
    'Join-Path $BuildCacheRoot "c\host"',
    '[System.Threading.Mutex]::new',
    'serialized stable Spatial Camera Panel build cache lane',
    'if ($BuildMode -eq "DevFast") { "--daemon" } else { "--no-daemon" }'
)) {
    Require $test $token "Host validation does not share the compatible stable cache contract: $token"
}

foreach ($token in @(
    'RUSTY_QUEST_SPATIAL_SIGNING_KEY_ALIAS',
    'RUSTY_QUEST_SPATIAL_SIGNING_STORE_PASSWORD',
    'RUSTY_QUEST_SPATIAL_SIGNING_KEY_PASSWORD',
    'spatialSigningStorePassword.get()',
    'spatialSigningKeyAlias.get()',
    'spatialSigningKeyPassword.get()'
)) {
    Require $gradle $token "Gradle signing does not consume the explicit local signer binding: $token"
}

$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($buildPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -gt 0) { throw "Build wrapper PowerShell parse failed: $($errors[0].Message)" }
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($testPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -gt 0) { throw "Test wrapper PowerShell parse failed: $($errors[0].Message)" }

& (Join-Path $repo "tools/checks/Test-SpatialCmakeStagingHost.ps1")
& (Join-Path $repo "tools/checks/Test-NativeReceiptArtifactMetadataHost.ps1") -RepoRoot $repo

Write-Host "Spatial Camera Panel fast build workflow static check: PASS"

# Shared prerequisite extraction and explicit scalar adapters are runtime-qualified separately.
foreach($name in @('CargoPath','RustupPath','RequireInstalledRustTarget')) {
    if(-not (Get-Command $buildPath).Parameters.ContainsKey($name)){throw "Missing selected tool parameter: $name"}
}
$prerequisiteModule=Join-Path $repo 'tools/lib/SpatialCameraPanelBuildPrerequisites.psm1'
if(-not(Test-Path -LiteralPath $prerequisiteModule -PathType Leaf)){throw 'Shared owning prerequisite module missing.'}
if([bool]$PrerequisiteInputSpecPath-ne[bool]$PrerequisiteOutRoot){throw 'Runtime prerequisite inputs and fresh output must be selected together.'}
if($PrerequisiteInputSpecPath){
    & (Join-Path $repo 'tools/checks/Test-SpatialCameraPanelBuildPrerequisites.ps1') -RepoRoot $repo -InputSpecPath $PrerequisiteInputSpecPath -OutRoot $PrerequisiteOutRoot
}


# Execute the actual packaged-input descriptor expression with synthetic keys.
# This proves metadata redaction only; real Gradle BuildConfig refresh is separately qualified.
$privacyTokens=$null;$privacyErrors=$null
$privacyAst=[Management.Automation.Language.Parser]::ParseFile($buildPath,[ref]$privacyTokens,[ref]$privacyErrors)
if(@($privacyErrors).Count){throw 'Build privacy production AST is invalid.'}
$packagedTables=@($privacyAst.FindAll({param($node)$node-is[Management.Automation.Language.HashtableAst]-and@($node.KeyValuePairs|Where-Object{$_.Item1-is[Management.Automation.Language.StringConstantExpressionAst]-and$_.Item1.Value-ceq'offline_media_key_configured'}).Count-eq1},$true))
if($packagedTables.Count-ne1){throw 'One production packaged-input descriptor required.'}
$resolvedOfflineMediaPackAssetDir='';$resolvedHandMeshRigAssetDir='';$resolvedPrivateFeatureSourceDir='';$resolvedPrivateFeatureAssetDir='';$resolvedPrivateFeatureResourceDir='';$embeddedDuplexProductInputs=$null
$observed=@()
foreach($synthetic in @(('1'*64),('2'*64))){
    $resolvedOfflineMediaKeyHex=$synthetic
    $descriptor=& ([scriptblock]::Create($packagedTables[0].Extent.Text))
    if($descriptor.Contains('offline_media_key_sha256')-or-not$descriptor.offline_media_key_configured-or$descriptor.offline_media_key_observation_policy-cne'presence-only-v1'){throw 'Production descriptor leaks a clear-key digest or loses configured policy.'}
    $encoded=$descriptor|ConvertTo-Json -Depth 20 -Compress
    $directDigest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($synthetic))).ToLowerInvariant()
    if($encoded.Contains($synthetic)-or$encoded.Contains($directDigest)){throw 'Synthetic key or key digest appeared in production descriptor.'}
    $observed+=,$encoded
}
if($observed[0]-cne$observed[1]){throw 'Sanitized packaged metadata incorrectly claims credential-value identity.'}
# The existing real provider must still feed the current environment into Gradle's actual BuildConfig field.
Require $build '$env:RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX = $resolvedOfflineMediaKeyHex' 'Current key delivery was removed.'
Require $gradle 'providers.environmentVariable("RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX")' 'Actual Gradle key input provider missing.'
Require $gradle '"OFFLINE_MEDIA_KEY_HEX"' 'Actual generated compiler input missing.'
Require $gradle 'buildConfigString(offlineMediaKeyHex.get())' 'Actual generated compiler input no longer consumes the current provider.'
Require $build 'credential-equivalence-not-claimed; compilation-still-observed-separately' 'Cache observation overclaims credential equivalence.'
Write-Host 'Media-key privacy descriptor projection: PASS (synthetic metadata only; real BuildConfig refresh pending actual Gradle qualification)'

Require $build '":app:assemble$BuildType"' 'Sanitized metadata must not remove the actual assembly task.'
Require $build '& $gradleBat @gradleArguments' 'Sanitized cache metadata must not replace actual Gradle dispatch.'
