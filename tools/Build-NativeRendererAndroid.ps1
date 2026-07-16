param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NdkHome = $env:ANDROID_NDK_HOME,
    [string]$OpenXrLoader = "S:\Work\tools\Quest\openxr-loader\libopenxr_loader.so",
    [string]$OutDir = "",
    [string]$Keystore = "",
    [string]$AppBuildLock = "",
    [string]$RecordedHandCaptureDir = "",
    [int]$RecordedHandFrameLimit = 12,
    [switch]$RequireRecordedHandCapture,
    [switch]$AllowUnlockedDevelopmentBuild,
    [switch]$ReplaceExistingOutput
)

$ErrorActionPreference = "Stop"
$script:BuildUsesLock = $false

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

function Resolve-RepoPath {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$RepoRoot
    )
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $Path))
}

function Read-JsonFile {
    param([Parameter(Mandatory=$true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing JSON file: $Path"
    }
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Get-FileSha256 {
    param([Parameter(Mandatory=$true)][string]$Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
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

function Assert-HashMatches {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$ExpectedSha256,
        [Parameter(Mandatory=$true)][string]$Path
    )
    if ([string]::IsNullOrWhiteSpace($ExpectedSha256)) {
        throw "$Label has no expected SHA-256 in the native app-build feature lock."
    }
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Label is missing: $Path"
    }
    $actualSha256 = Get-FileSha256 -Path $Path
    if ($ExpectedSha256.ToLowerInvariant() -ne $actualSha256) {
        throw "$Label hash does not match the native app-build feature lock. Expected $ExpectedSha256 but found $actualSha256 at $Path. Re-run tools/Resolve-NativeAppBuild.ps1 for the app spec before building."
    }
}

function Get-EffectiveBuildEnvValue {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)]$AppBuildEnvByName
    )
    if ($AppBuildEnvByName.ContainsKey($Name)) {
        return [string]$AppBuildEnvByName[$Name]
    }
    if ($script:BuildUsesLock) {
        return $null
    }
    return [Environment]::GetEnvironmentVariable($Name)
}

function Test-TruthyBuildEnvValue {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $false
    }
    return @("1", "true", "yes", "on") -contains $Value.Trim().ToLowerInvariant()
}

function Copy-AssetInput {
    param(
        [Parameter(Mandatory=$true)][string]$Source,
        [Parameter(Mandatory=$true)][string]$DestinationRoot,
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [string]$DestinationName = ""
    )

    $sourcePath = Resolve-RepoPath -Path $Source -RepoRoot $RepoRoot
    if (-not (Test-Path -LiteralPath $sourcePath)) {
        throw "Declared APK asset input is missing: $sourcePath"
    }
    $leaf = if ([string]::IsNullOrWhiteSpace($DestinationName)) {
        Split-Path -Leaf $sourcePath
    } else {
        $DestinationName
    }
    if ($leaf -match '[\\/]' -or [string]::IsNullOrWhiteSpace($leaf)) {
        throw "APK asset destination name must be a single path component: $leaf"
    }
    $destinationPath = Join-Path $DestinationRoot $leaf
    if ((Get-Item -LiteralPath $sourcePath).PSIsContainer) {
        New-Item -ItemType Directory -Force -Path $destinationPath | Out-Null
        Get-ChildItem -LiteralPath $sourcePath -Force | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $destinationPath -Recurse -Force
        }
    } else {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destinationPath) | Out-Null
        Copy-Item -LiteralPath $sourcePath -Destination $destinationPath -Force
    }
    return $destinationPath
}

if ([string]::IsNullOrWhiteSpace($AndroidHome)) {
    throw "ANDROID_HOME or -AndroidHome is required."
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "JAVA_HOME or -JavaHome is required."
}
if ([string]::IsNullOrWhiteSpace($NdkHome)) {
    $ndkRoot = Join-Path $AndroidHome "ndk"
    if (Test-Path $ndkRoot) {
        $NdkHome = Get-LatestDirectory -Parent $ndkRoot -Pattern "*"
    }
}
if ([string]::IsNullOrWhiteSpace($NdkHome)) {
    throw "ANDROID_NDK_HOME, -NdkHome, or an Android SDK ndk directory is required."
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$appRoot = Resolve-Path (Join-Path $repoRoot "apps\native-renderer-android")
$targetRoot = Join-Path $repoRoot "target"
$requestedOutDir = $OutDir

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
$linker = Join-Path $NdkHome "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang.cmd"
$cargoCommand = Get-Command cargo -ErrorAction Stop

foreach ($tool in @($platformJar, $aapt2, $d8, $zipalign, $apksigner, $javac, $jar, $keytool, $linker)) {
    if (-not (Test-Path $tool)) {
        throw "Required tool not found: $tool"
    }
}

$appBuildLockObject = $null
$appBuildLockPath = ""
$appBuildEnvPath = ""
$nativeAppSettingsPath = ""
$generatedManifestPath = ""
$manifestInputPath = Join-Path $appRoot "AndroidManifest.xml"
$packageName = "io.github.mesmerprism.rustyquest.native_renderer"
$activityName = "io.github.mesmerprism.rustyquest.native_renderer/android.app.NativeActivity"
$appBuildEnvEntries = @()
$appBuildEnvByName = @{}
$runtimeProfilePath = ""
$generatedBuildManifestPath = ""
$appBuildLockSha256 = ""
if ([string]::IsNullOrWhiteSpace($AppBuildLock) -and -not $AllowUnlockedDevelopmentBuild) {
    throw "-AppBuildLock is required. Use -AllowUnlockedDevelopmentBuild only for an explicitly loose local compatibility build."
}
if (-not [string]::IsNullOrWhiteSpace($AppBuildLock)) {
    $script:BuildUsesLock = $true
    $appBuildLockPath = Resolve-RepoPath -Path $AppBuildLock -RepoRoot ([string]$repoRoot)
    $appBuildLockSha256 = Get-FileSha256 -Path $appBuildLockPath
    $appBuildLockObject = Read-JsonFile -Path $appBuildLockPath
    if ([string]$appBuildLockObject.schema -ne "rusty.quest.native_app_feature_lock.v1") {
        throw "Unsupported native app-build feature lock schema: $($appBuildLockObject.schema)"
    }
    foreach ($field in @("android_manifest", "generated_outputs", "app_settings", "build_inputs")) {
        if ($null -eq $appBuildLockObject.PSObject.Properties[$field]) {
            throw "Native app-build feature lock is missing required field for APK build: $field"
        }
    }
    foreach ($field in @("app_spec_path", "app_spec_sha256", "feature_descriptors", "resolution_fingerprint")) {
        if ($null -eq $appBuildLockObject.PSObject.Properties[$field]) {
            throw "Native app-build feature lock is missing freshness field for APK build: $field"
        }
    }
    $appSpecPath = Resolve-RepoPath -Path ([string]$appBuildLockObject.app_spec_path) -RepoRoot ([string]$repoRoot)
    Assert-HashMatches `
        -Label "Native app-build app spec" `
        -ExpectedSha256 ([string]$appBuildLockObject.app_spec_sha256) `
        -Path $appSpecPath
    foreach ($descriptor in @($appBuildLockObject.feature_descriptors)) {
        foreach ($field in @("feature_id", "path", "sha256")) {
            if ($null -eq $descriptor.PSObject.Properties[$field]) {
                throw "Native app-build feature descriptor record is missing freshness field: $field"
            }
        }
        $descriptorPath = Resolve-RepoPath -Path ([string]$descriptor.path) -RepoRoot ([string]$repoRoot)
        Assert-HashMatches `
            -Label "Native app-build feature descriptor $($descriptor.feature_id)" `
            -ExpectedSha256 ([string]$descriptor.sha256) `
            -Path $descriptorPath
    }
    $packageName = [string]$appBuildLockObject.android_manifest.package_name
    $activityName = "$packageName/android.app.NativeActivity"
    $generatedManifestPath = Resolve-RepoPath -Path ([string]$appBuildLockObject.generated_outputs.android_manifest) -RepoRoot ([string]$repoRoot)
    $nativeAppSettingsPath = Resolve-RepoPath -Path ([string]$appBuildLockObject.generated_outputs.native_app_settings) -RepoRoot ([string]$repoRoot)
    $runtimeProfilePath = Resolve-RepoPath -Path ([string]$appBuildLockObject.generated_outputs.runtime_profile) -RepoRoot ([string]$repoRoot)
    $appBuildEnvPath = Resolve-RepoPath -Path ([string]$appBuildLockObject.generated_outputs.build_env) -RepoRoot ([string]$repoRoot)
    $generatedBuildManifestPath = Resolve-RepoPath -Path ([string]$appBuildLockObject.generated_outputs.build_manifest) -RepoRoot ([string]$repoRoot)
    foreach ($path in @($generatedManifestPath, $nativeAppSettingsPath, $runtimeProfilePath, $appBuildEnvPath, $generatedBuildManifestPath)) {
        if (-not (Test-Path -LiteralPath $path)) {
            throw "Native app-build generated artifact is missing: $path"
        }
    }
    if ([string]$appBuildLockObject.app_settings.sha256 -ne (Get-FileSha256 -Path $nativeAppSettingsPath)) {
        throw "Native app-build settings hash does not match feature lock app_settings.sha256"
    }
    $generatedBuildManifest = Read-JsonFile -Path $generatedBuildManifestPath
    foreach ($field in @("feature_lock_sha256", "runtime_profile_sha256", "native_app_settings_sha256", "android_manifest_sha256", "build_env_sha256")) {
        if ($null -eq $generatedBuildManifest.PSObject.Properties[$field]) {
            throw "Native app-build generated build manifest is missing hash field: $field"
        }
    }
    Assert-HashMatches `
        -Label "Native app-build feature lock" `
        -ExpectedSha256 ([string]$generatedBuildManifest.feature_lock_sha256) `
        -Path $appBuildLockPath
    Assert-HashMatches `
        -Label "Native app-build generated runtime profile" `
        -ExpectedSha256 ([string]$generatedBuildManifest.runtime_profile_sha256) `
        -Path $runtimeProfilePath
    Assert-HashMatches `
        -Label "Native app-build generated settings" `
        -ExpectedSha256 ([string]$generatedBuildManifest.native_app_settings_sha256) `
        -Path $nativeAppSettingsPath
    Assert-HashMatches `
        -Label "Native app-build generated Android manifest" `
        -ExpectedSha256 ([string]$generatedBuildManifest.android_manifest_sha256) `
        -Path $generatedManifestPath
    Assert-HashMatches `
        -Label "Native app-build generated build-env" `
        -ExpectedSha256 ([string]$generatedBuildManifest.build_env_sha256) `
        -Path $appBuildEnvPath
    $manifestInputPath = $generatedManifestPath
    $appBuildEnv = Read-JsonFile -Path $appBuildEnvPath
    $appBuildEnvEntries = @($appBuildEnv.env)
    foreach ($entry in $appBuildEnvEntries) {
        if ($null -eq $entry.PSObject.Properties["name"]) {
            throw "Native app-build env entry is missing name"
        }
        $name = [string]$entry.name
        if ($name -notmatch '^[A-Z0-9_]+$') {
            throw "Native app-build env entry has invalid name: $name"
        }
        $appBuildEnvByName[$name] = if ($null -ne $entry.PSObject.Properties["value"]) { [string]$entry.value } else { "" }
    }

    $undeclaredAmbient = @(Get-ChildItem Env: | Where-Object {
        $_.Name -like "RUSTY_QUEST_NATIVE_RENDERER_*" -and
        -not $appBuildEnvByName.ContainsKey([string]$_.Name) -and
        -not [string]::IsNullOrWhiteSpace([string]$_.Value)
    } | Select-Object -ExpandProperty Name | Sort-Object -Unique)
    if ($undeclaredAmbient.Count -gt 0) {
        throw "Locked native APK build rejected undeclared ambient feature inputs: $($undeclaredAmbient -join ', '). Add them to the app feature lock or clear them."
    }
}

Import-Module (Join-Path $PSScriptRoot "lib\SourceComposition.psm1") -Force
$sourceComposition = Get-QuestBuildSourceComposition `
    -RepoRoot ([string]$repoRoot) `
    -PackageName @("rusty-quest-native-renderer-android-native", "rusty-quest-broker-authority")
$primarySource = @($sourceComposition.repositories | Where-Object { $_.role -eq "primary" })
if ($primarySource.Count -ne 1) { throw "Native APK source composition did not resolve exactly one primary Rusty Quest repository." }
$sourceHead = [string]$primarySource[0].commit
$sourceTree = [string]$primarySource[0].tree
$sourceDependencies = @($sourceComposition.repositories | Where-Object { $_.role -eq "path-dependency" })

if ([string]::IsNullOrWhiteSpace($requestedOutDir)) {
    if ($script:BuildUsesLock) {
        $OutDir = Join-Path $targetRoot ("native-renderer-android\builds\{0}\{1}\{2}" -f ([string]$appBuildLockObject.app_id), $appBuildLockSha256.Substring(0, 24), ([string]$sourceComposition.fingerprint).Substring(0, 16))
    } else {
        $OutDir = Join-Path $targetRoot ("native-renderer-android\unlocked-development\{0}" -f ([string]$sourceComposition.fingerprint).Substring(0, 16))
    }
} else {
    $OutDir = $requestedOutDir
}
$resolvedOutParent = Split-Path -Parent $OutDir
New-Item -ItemType Directory -Force -Path $targetRoot, $resolvedOutParent | Out-Null
$resolvedTargetRoot = (Resolve-Path $targetRoot).Path.TrimEnd("\")
$resolvedOutFull = [System.IO.Path]::GetFullPath($OutDir).TrimEnd("\")
if (-not $resolvedOutFull.StartsWith($resolvedTargetRoot + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must be under the repo target directory: $resolvedOutFull"
}
if (Test-Path $OutDir) {
    if (-not $ReplaceExistingOutput) {
        throw "Content-addressed native APK output already exists: $OutDir. Reuse its run capsule or pass -ReplaceExistingOutput explicitly."
    }
    $resolvedOutDir = (Resolve-Path $OutDir).Path
    Remove-Item -LiteralPath $resolvedOutDir -Recurse -Force
}

$assetsDir = Join-Path $OutDir "assets"
$classesDir = Join-Path $OutDir "classes"
$dexDir = Join-Path $OutDir "dex"
$classesJar = Join-Path $OutDir "classes.jar"
$nativeStageRoot = Join-Path $OutDir "native"
$nativeLibDir = Join-Path $nativeStageRoot "lib\arm64-v8a"
$intermediateIdentity = if ($script:BuildUsesLock) {
    "{0}-{1}" -f $appBuildLockSha256.Substring(0, 8), ([string]$sourceComposition.fingerprint).Substring(0, 8)
} else {
    "unlocked-{0}" -f ([string]$sourceComposition.fingerprint).Substring(0, 8)
}
$cargoTargetDir = Join-Path $targetRoot ("apk-i\n\{0}\cargo" -f $intermediateIdentity)
$apkUnsigned = Join-Path $OutDir "rusty-quest-native-renderer-unsigned.apk"
$apkUnaligned = Join-Path $OutDir "rusty-quest-native-renderer-unaligned.apk"
$apkAligned = Join-Path $OutDir "rusty-quest-native-renderer-aligned.apk"
$apkSigned = Join-Path $OutDir "rusty-quest-native-renderer.apk"
$nativeLib = Join-Path $nativeLibDir "librusty_quest_native_renderer.so"
if ([string]::IsNullOrWhiteSpace($Keystore)) {
    $Keystore = Join-Path $targetRoot "rusty-quest-native-renderer-debug.keystore"
}

New-Item -ItemType Directory -Force -Path $assetsDir, $classesDir, $dexDir, $nativeLibDir | Out-Null
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
        "-dname", "CN=Rusty Quest Native Renderer,O=Rusty Quest,C=US"
    )
}
$embeddedBrokerCertificatePath = Join-Path $OutDir "native-renderer-signing-certificate.der"
Invoke-Checked "keytool certificate export" $keytool @(
    "-exportcert",
    "-keystore", $Keystore,
    "-storepass", "android",
    "-alias", "androiddebugkey",
    "-file", $embeddedBrokerCertificatePath
)
$embeddedBrokerCertificateSha256 = Get-FileSha256 -Path $embeddedBrokerCertificatePath

$manifoldFixtureRoot = Resolve-Path (Join-Path $repoRoot "..\rusty-manifold\fixtures\broker-product")
$embeddedProductSpecPath = Join-Path $manifoldFixtureRoot "media-session-embedded.json"
$embeddedProductLockPath = Join-Path $manifoldFixtureRoot "media-session-embedded.lock.json"
$embeddedClientLockTemplatePath = Join-Path $repoRoot "fixtures\broker-clients\native-renderer.client.json"
$embeddedMediaBindingPath = Join-Path $repoRoot "fixtures\media-runtime-products\native-renderer-display.binding.json"
$embeddedMediaLifecycleTemplatePath = Join-Path $repoRoot "fixtures\broker-clients\native-renderer.media-lifecycle.json"
$embeddedAppFeatureLockTemplatePath = Join-Path $repoRoot "apps\native-renderer-android\morphospace\conformance-locks\broker-media-client.feature.lock.json"
foreach ($path in @($embeddedProductSpecPath, $embeddedProductLockPath, $embeddedClientLockTemplatePath, $embeddedMediaBindingPath, $embeddedMediaLifecycleTemplatePath, $embeddedAppFeatureLockTemplatePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Embedded Manifold packaged authority input is missing: $path"
    }
}
$embeddedProductSpecJson = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $embeddedProductSpecPath))
$embeddedProductLockJson = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $embeddedProductLockPath))
$embeddedClientLockJson = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $embeddedClientLockTemplatePath))
$embeddedMediaBindingJson = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $embeddedMediaBindingPath))
$embeddedMediaLifecycleJson = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $embeddedMediaLifecycleTemplatePath))
$embeddedAppFeatureLockJson = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $embeddedAppFeatureLockTemplatePath))
$embeddedProductLock = $embeddedProductLockJson | ConvertFrom-Json
$embeddedClientLock = $embeddedClientLockJson | ConvertFrom-Json
$embeddedMediaBinding = $embeddedMediaBindingJson | ConvertFrom-Json
if ([string]$embeddedClientLock.schema -ne "rusty.quest.broker_client_spec.v1" -or
    [string]$embeddedClientLock.client_id -ne "client.quest.native-renderer" -or
    [string]$embeddedClientLock.package_name -ne "io.github.mesmerprism.rustyquest.native_renderer" -or
    @($embeddedClientLock.adapter_permissions).Count -ne 1 -or
    [string]$embeddedClientLock.adapter_permissions[0] -ne "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION" -or
    @($embeddedClientLock.runtime_properties).Count -ne 0 -or
    @($embeddedClientLock.application_defaults).Count -ne 0) {
    throw "Native renderer broker client lock is not an exact closed signature-scoped binding."
}
$identitySuffix = if ($null -eq $appBuildLockObject) { "unlocked-development" } else { ([string]$appBuildLockObject.app_id).Replace("_", "-").Replace(".", "-") }
$markerSuffix = $identitySuffix.ToUpperInvariant().Replace("-", "_")
$embeddedClientLock.client_id = "client.quest.native-renderer.$identitySuffix"
$embeddedClientLock.package_name = $packageName
$embeddedClientLock.feature_lock_id = "lock.broker-client.native-renderer.$identitySuffix.v1"
$embeddedClientLock.marker_namespace = "RUSTY_QUEST_NATIVE_BROKER_CLIENT_$markerSuffix"
$embeddedClientLockJson = $embeddedClientLock | ConvertTo-Json -Depth 16 -Compress
$embeddedClientLockPath = Join-Path $OutDir "generated-native-renderer.client.json"
[System.IO.File]::WriteAllText($embeddedClientLockPath, $embeddedClientLockJson, (New-Object System.Text.UTF8Encoding($false)))

$embeddedProjectId = "native-renderer-$identitySuffix"
$embeddedAppFeatureLock = $embeddedAppFeatureLockJson | ConvertFrom-Json
$embeddedAppFeatureLock.project_id = $embeddedProjectId
foreach ($feature in @($embeddedAppFeatureLock.features)) {
    $feature.requested_by = "iteration-unit:apk-build-$identitySuffix"
    if ([string]$feature.feature_id -eq "broker-media-client") {
        $feature.activation_receipt.effective_marker = "rusty.quest.native_renderer.$identitySuffix.broker_media_client.effective"
    } elseif ([string]$feature.feature_id -eq "native-renderer-shell") {
        $feature.activation_receipt.effective_marker = "rusty.quest.native_renderer.$identitySuffix.shell.effective"
    }
}
$embeddedAppFeatureLockJson = $embeddedAppFeatureLock | ConvertTo-Json -Depth 16 -Compress
$embeddedAppFeatureLockPath = Join-Path $OutDir "generated-native-renderer.broker-media-client.feature.lock.json"
[System.IO.File]::WriteAllText($embeddedAppFeatureLockPath, $embeddedAppFeatureLockJson, (New-Object System.Text.UTF8Encoding($false)))
$embeddedAppFeatureLockSha256 = Get-FileSha256 -Path $embeddedAppFeatureLockPath

$embeddedMediaLifecycle = $embeddedMediaLifecycleJson | ConvertFrom-Json
$embeddedMediaLifecycle.client_id = [string]$embeddedClientLock.client_id
$embeddedMediaLifecycle.package_name = $packageName
$embeddedMediaLifecycle.broker_client_lock_id = [string]$embeddedClientLock.feature_lock_id
$embeddedMediaLifecycle.marker_namespace = [string]$embeddedClientLock.marker_namespace
$embeddedMediaLifecycle.project_id = $embeddedProjectId
$embeddedMediaLifecycle.app_feature_lock_id = "lock.app.native-renderer.$identitySuffix.broker-media-client.v1"
$embeddedMediaLifecycle.app_feature_lock_path = "generated-native-renderer.broker-media-client.feature.lock.json"
$embeddedMediaLifecycle.app_feature_lock_fingerprint = "sha256:$embeddedAppFeatureLockSha256"
$embeddedMediaLifecycle.app_feature_lock_sha256 = "sha256:$embeddedAppFeatureLockSha256"
$embeddedMediaLifecycle.activation_effective_marker = "rusty.quest.native_renderer.$identitySuffix.broker_media_client.effective"
$embeddedMediaLifecycle.broker_runtime_lease_id = "lease.broker.media-session.$([string]$embeddedClientLock.client_id)"
$embeddedMediaLifecycle.media_runtime_lease_id = "lease.media.session.$([string]$embeddedClientLock.client_id)"
$embeddedMediaLifecycleJson = $embeddedMediaLifecycle | ConvertTo-Json -Depth 16 -Compress
$embeddedMediaLifecyclePath = Join-Path $OutDir "generated-native-renderer.media-lifecycle.json"
[System.IO.File]::WriteAllText($embeddedMediaLifecyclePath, $embeddedMediaLifecycleJson, (New-Object System.Text.UTF8Encoding($false)))

$embeddedGrantId = "grant.quest.native-renderer.$identitySuffix"
$embeddedLeaseId = "lease.broker.media-session.$([string]$embeddedClientLock.client_id)"
$embeddedGrantCapabilities = @(Get-ExactClientGrantCapabilities -ClientLock $embeddedClientLock -ProductLock $embeddedProductLock)
$embeddedRuntimeConfig = [ordered]@{
    '$schema' = "rusty.quest.broker.runtime_config.v1"
    bridge_kind = "embedded_in_process_jni"
    adapter_config = [ordered]@{
        '$schema' = "rusty.manifold.broker.adapter_config.v2"
        adapter_id = "adapter.quest.native-renderer.$identitySuffix.embedded"
        mode = "embedded"
        product_lock_id = [string]$embeddedProductLock.lock_id
        product_lock_fingerprint = [string]$embeddedProductLock.spec_fingerprint
        product_lock_sha256 = "sha256:$(Get-FileSha256 -Path $embeddedProductLockPath)"
        authority_host_id = "host.quest.native-renderer.$identitySuffix"
        authority_owner_id = "module.runtime.host"
    }
    product_lock = $embeddedProductLock
    packaged_authority = [ordered]@{
        product_spec_json = $embeddedProductSpecJson
        product_spec_sha256 = Get-FileSha256 -Path $embeddedProductSpecPath
        product_lock_json = $embeddedProductLockJson
        product_lock_sha256 = Get-FileSha256 -Path $embeddedProductLockPath
        client_locks = @([ordered]@{
            grant_id = $embeddedGrantId
            client_lock_json = $embeddedClientLockJson
            client_lock_sha256 = Get-FileSha256 -Path $embeddedClientLockPath
            media_lifecycle_authority = [ordered]@{
                media_lifecycle_lock_json = $embeddedMediaLifecycleJson
                media_lifecycle_lock_sha256 = Get-FileSha256 -Path $embeddedMediaLifecyclePath
                app_feature_lock_json = $embeddedAppFeatureLockJson
                app_feature_lock_sha256 = $embeddedAppFeatureLockSha256
                media_binding_json = $embeddedMediaBindingJson
                media_binding_sha256 = Get-FileSha256 -Path $embeddedMediaBindingPath
            }
        })
    }
    initial_leases = @([ordered]@{
        lease_id = $embeddedLeaseId
        scope = "lease.media.session"
        holder_id = [string]$embeddedClientLock.client_id
        expires_at_ms = 4102444800000
    })
    admission = [ordered]@{
        '$schema' = "rusty.quest.broker.admission_config.v1"
        snapshot = [ordered]@{
            '$schema' = "rusty.manifold.admission.snapshot.v2"
            authority_id = "authority.admission.quest.native-renderer.$identitySuffix"
            authority_revision = 1
            grants = @([ordered]@{
                grant_id = $embeddedGrantId
                client_lock_id = [string]$embeddedClientLock.feature_lock_id
                client_lock_fingerprint = "sha256:$(Get-FileSha256 -Path $embeddedClientLockPath)"
                identity = [ordered]@{
                    client_id = [string]$embeddedClientLock.client_id
                    platform_subject = [string]$embeddedClientLock.package_name
                    signing_fingerprint = "sha256:$embeddedBrokerCertificateSha256"
                }
                capabilities = $embeddedGrantCapabilities
                expires_at_ms = 4102444800000
                revoked = $false
            })
            active_tokens = @()
            revoked_token_ids = @()
            consumed_request_ids = @()
            consumed_use_request_ids = @()
            reviewed_sweep_ids = @()
            audit_events = @()
            max_token_ttl_ms = 60000
        }
    }
    media_session = $embeddedMediaBinding
}
$embeddedRuntimeConfigPath = Join-Path $OutDir "embedded-manifold-runtime-config.json"
[System.IO.File]::WriteAllText(
    $embeddedRuntimeConfigPath,
    ($embeddedRuntimeConfig | ConvertTo-Json -Depth 30),
    (New-Object System.Text.UTF8Encoding($false)))
$embeddedRuntimeConfigSha256 = Get-RuntimeConfigDigest -RepoRoot $repoRoot -RuntimeConfigPath $embeddedRuntimeConfigPath
$embeddedRuntimeConfigJava = ($embeddedRuntimeConfig | ConvertTo-Json -Depth 30 -Compress).Replace('\', '\\').Replace('"', '\"')
$embeddedCapabilitiesJava = (@($embeddedGrantCapabilities | ForEach-Object { '"' + ([string]$_).Replace('"', '\"') + '"' }) -join ', ')
$generatedEmbeddedPackageDir = Join-Path $OutDir "generated\io\github\mesmerprism\rustyquest\native_renderer"
New-Item -ItemType Directory -Force -Path $generatedEmbeddedPackageDir | Out-Null
$generatedEmbeddedRuntimeConfigPath = Join-Path $generatedEmbeddedPackageDir "GeneratedEmbeddedManifoldRuntimeConfig.java"
$generatedEmbeddedRuntimeConfigSource = @"
package io.github.mesmerprism.rustyquest.native_renderer;

final class GeneratedEmbeddedManifoldRuntimeConfig {
    static final String JSON = "$embeddedRuntimeConfigJava";
    static final String SHA256 = "$embeddedRuntimeConfigSha256";
    static final String CLIENT_ID = "$($embeddedClientLock.client_id)";
    static final String PACKAGE_NAME = "$($embeddedClientLock.package_name)";
    static final String[] GRANTED_CAPABILITIES = new String[] {$embeddedCapabilitiesJava};
    private GeneratedEmbeddedManifoldRuntimeConfig() {}
}
"@
[System.IO.File]::WriteAllText(
    $generatedEmbeddedRuntimeConfigPath,
    $generatedEmbeddedRuntimeConfigSource,
    (New-Object System.Text.UTF8Encoding($false)))
$embeddedAuthorityAssetDir = Join-Path $assetsDir "manifold"
New-Item -ItemType Directory -Force -Path $embeddedAuthorityAssetDir | Out-Null
Copy-Item -LiteralPath $embeddedProductSpecPath -Destination (Join-Path $embeddedAuthorityAssetDir "product-spec.json") -Force
Copy-Item -LiteralPath $embeddedProductLockPath -Destination (Join-Path $embeddedAuthorityAssetDir "accepted-product-lock.json") -Force
Copy-Item -LiteralPath $embeddedClientLockPath -Destination (Join-Path $embeddedAuthorityAssetDir "native-renderer.client.json") -Force
Copy-Item -LiteralPath $embeddedRuntimeConfigPath -Destination (Join-Path $embeddedAuthorityAssetDir "runtime-config.json") -Force
Copy-Item -LiteralPath (Join-Path $repoRoot "fixtures\native-renderer\native-hwb-blur-sdf-public.plan.json") `
    -Destination (Join-Path $assetsDir "native-hwb-blur-sdf-public.plan.json") `
    -Force
Copy-Item -LiteralPath (Join-Path $repoRoot "fixtures\native-renderer\recorded-hand-replay-public-shape.json") `
    -Destination (Join-Path $assetsDir "recorded-hand-replay-public-shape.json") `
    -Force
if (-not [string]::IsNullOrWhiteSpace($nativeAppSettingsPath)) {
    Copy-Item -LiteralPath $nativeAppSettingsPath -Destination (Join-Path $assetsDir "native-app-settings.json") -Force
    Copy-Item -LiteralPath $appBuildLockPath -Destination (Join-Path $assetsDir "feature-lock.json") -Force
}

$declaredAssetInputsPackaged = @()
if ($null -ne $appBuildLockObject -and $null -ne $appBuildLockObject.build_inputs -and $null -ne $appBuildLockObject.build_inputs.assets) {
    foreach ($assetInput in @($appBuildLockObject.build_inputs.assets)) {
        $assetInputText = [string]$assetInput
        if ([string]::IsNullOrWhiteSpace($assetInputText)) {
            continue
        }
        $declaredAssetInputsPackaged += Copy-AssetInput -Source $assetInputText -DestinationRoot $assetsDir -RepoRoot ([string]$repoRoot)
    }
}

$questionnaireAssetDir = [Environment]::GetEnvironmentVariable("RUSTY_QUEST_NATIVE_RENDERER_QUESTIONNAIRE_ASSET_DIR")
$questionnaireAssetsPackaged = $false
$questionnaireAssetSource = ""
if (-not [string]::IsNullOrWhiteSpace($questionnaireAssetDir)) {
    $questionnaireAssetSource = Resolve-RepoPath -Path $questionnaireAssetDir -RepoRoot ([string]$repoRoot)
    if (-not (Test-Path -LiteralPath $questionnaireAssetSource)) {
        throw "RUSTY_QUEST_NATIVE_RENDERER_QUESTIONNAIRE_ASSET_DIR does not exist: $questionnaireAssetSource"
    }
    if (-not (Get-Item -LiteralPath $questionnaireAssetSource).PSIsContainer) {
        throw "RUSTY_QUEST_NATIVE_RENDERER_QUESTIONNAIRE_ASSET_DIR must be a directory: $questionnaireAssetSource"
    }
    [void](Copy-AssetInput -Source $questionnaireAssetSource -DestinationRoot $assetsDir -RepoRoot ([string]$repoRoot) -DestinationName "maia_spatial_questionnaire")
    $questionnaireAssetsPackaged = $true
}

if ($RequireRecordedHandCapture -and [string]::IsNullOrWhiteSpace($RecordedHandCaptureDir)) {
    throw "-RequireRecordedHandCapture needs -RecordedHandCaptureDir so the APK cannot silently fall back to the public metadata-only replay shape."
}
$resolvedRecordedHandCaptureDir = ""
if (-not [string]::IsNullOrWhiteSpace($RecordedHandCaptureDir)) {
    if (-not (Test-Path -LiteralPath $RecordedHandCaptureDir)) {
        throw "Recorded hand capture directory not found: $RecordedHandCaptureDir"
    }
    $resolvedRecordedHandCaptureDir = (Resolve-Path -LiteralPath $RecordedHandCaptureDir).Path
}

$sourceFiles = Get-ChildItem -Path (Join-Path $appRoot "src\main\java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
$sharedBrokerClientJavaRoot = Join-Path $repoRoot "crates\rusty-quest-broker-client\android"
$sharedBrokerClientJava = Get-ChildItem -LiteralPath $sharedBrokerClientJavaRoot -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
if ($sharedBrokerClientJava.Count -lt 2) {
    throw "Shared broker client Android adapter sources are incomplete: $sharedBrokerClientJavaRoot"
}
$sourceFiles = @($sourceFiles) + @($sharedBrokerClientJava) + @($generatedEmbeddedRuntimeConfigPath)
if ($sourceFiles.Count -eq 0) {
    throw "No Java sources found under $appRoot"
}
$sourceList = Join-Path $OutDir "sources.rsp"
$sourceFiles | Set-Content -Encoding ASCII -Path $sourceList

Invoke-Checked "javac" $javac @(
    "-encoding", "UTF-8",
    "-source", "1.8",
    "-target", "1.8",
    "-bootclasspath", $platformJar,
    "-d", $classesDir,
    "@$sourceList"
)
Invoke-Checked "jar class pack" $jar @("cf", $classesJar, "-C", $classesDir, ".")
Invoke-Checked "d8" $d8 @("--lib", $platformJar, "--output", $dexDir, $classesJar)

$previousAndroidHome = $env:ANDROID_HOME
$previousNdkHome = $env:ANDROID_NDK_HOME
$previousLinker = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
$previousRecordedHandCaptureDir = $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR
$previousRecordedHandFrameLimit = $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT
$previousAppBuildEnv = @{}
try {
    $env:ANDROID_HOME = $AndroidHome
    $env:ANDROID_NDK_HOME = $NdkHome
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $linker
    foreach ($entry in $appBuildEnvEntries) {
        $name = [string]$entry.name
        $previousAppBuildEnv[$name] = [Environment]::GetEnvironmentVariable($name)
        [Environment]::SetEnvironmentVariable($name, [string]$appBuildEnvByName[$name], "Process")
    }
    if (-not [string]::IsNullOrWhiteSpace($resolvedRecordedHandCaptureDir)) {
        $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR = $resolvedRecordedHandCaptureDir
        $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT = [Math]::Max(1, [Math]::Min(120, $RecordedHandFrameLimit)).ToString()
    } else {
        Remove-Item Env:\RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR -ErrorAction SilentlyContinue
        Remove-Item Env:\RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT -ErrorAction SilentlyContinue
    }
    Invoke-Checked "native renderer cargo build" $cargoCommand.Source @(
        "build",
        "--manifest-path", (Join-Path $appRoot "native\Cargo.toml"),
        "--locked",
        "--target", "aarch64-linux-android",
        "--release",
        "--target-dir", $cargoTargetDir
    )
} finally {
    $env:ANDROID_HOME = $previousAndroidHome
    $env:ANDROID_NDK_HOME = $previousNdkHome
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $previousLinker
    if ($null -eq $previousRecordedHandCaptureDir) {
        Remove-Item Env:\RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR = $previousRecordedHandCaptureDir
    }
    if ($null -eq $previousRecordedHandFrameLimit) {
        Remove-Item Env:\RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT = $previousRecordedHandFrameLimit
    }
    foreach ($name in $previousAppBuildEnv.Keys) {
        if ($null -eq $previousAppBuildEnv[$name]) {
            [Environment]::SetEnvironmentVariable([string]$name, $null, "Process")
        } else {
            [Environment]::SetEnvironmentVariable([string]$name, [string]$previousAppBuildEnv[$name], "Process")
        }
    }
}

$builtNativeLib = Join-Path $cargoTargetDir "aarch64-linux-android\release\librusty_quest_native_renderer.so"
if (-not (Test-Path $builtNativeLib)) {
    throw "Cargo build did not produce native renderer library: $builtNativeLib"
}
Copy-Item -LiteralPath $builtNativeLib -Destination $nativeLib -Force

$lslNativeLibraryPackaged = $false
$lslNativeLibraryPath = ""
$lslNativeLibrarySha256 = ""
if (Test-TruthyBuildEnvValue -Value (Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_LSL_ANDROID" -AppBuildEnvByName $appBuildEnvByName)) {
    $configuredLslLibDir = Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_LSL_LIB_DIR" -AppBuildEnvByName $appBuildEnvByName
    $lslLibCandidates = @()
    if (-not [string]::IsNullOrWhiteSpace($configuredLslLibDir)) {
        $lslLibCandidates += $configuredLslLibDir
    }
    $lslLibCandidates += (Join-Path $repoRoot "local-artifacts\liblsl-android\arm64-v8a")
    $lslLibCandidates += (Join-Path $repoRoot "third_party\liblsl-android\staged\arm64-v8a")
    $resolvedLslLibDir = ""
    foreach ($candidate in $lslLibCandidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        $candidatePath = [System.IO.Path]::GetFullPath($candidate)
        if (Test-Path -LiteralPath (Join-Path $candidatePath "liblsl.so")) {
            $resolvedLslLibDir = $candidatePath
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($resolvedLslLibDir)) {
        throw "RUSTY_QUEST_NATIVE_RENDERER_LSL_ANDROID is enabled, but liblsl.so was not found. Run tools/Stage-LibLslAndroid.ps1 or set RUSTY_QUEST_NATIVE_RENDERER_LSL_LIB_DIR."
    }
    $lslSource = Join-Path $resolvedLslLibDir "liblsl.so"
    $lslDestination = Join-Path $nativeLibDir "liblsl.so"
    Copy-Item -LiteralPath $lslSource -Destination $lslDestination -Force
    $lslNativeLibraryPackaged = $true
    $lslNativeLibraryPath = $lslSource
    $lslNativeLibrarySha256 = Get-FileSha256 -Path $lslSource
}

$privateLayerPayloadLinked =
    (-not [string]::IsNullOrWhiteSpace((Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_LAYER_GUIDE_SHADER" -AppBuildEnvByName $appBuildEnvByName))) -and
    (-not [string]::IsNullOrWhiteSpace((Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_LAYER_PROJECTION_SHADER" -AppBuildEnvByName $appBuildEnvByName))) -and
    (Test-Path (Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_LAYER_GUIDE_SHADER" -AppBuildEnvByName $appBuildEnvByName)) -and
    (Test-Path (Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_LAYER_PROJECTION_SHADER" -AppBuildEnvByName $appBuildEnvByName))

$privateParticlePayloadLinked =
    (-not [string]::IsNullOrWhiteSpace((Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_DATA_DIR" -AppBuildEnvByName $appBuildEnvByName))) -and
    (-not [string]::IsNullOrWhiteSpace((Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_SHADER" -AppBuildEnvByName $appBuildEnvByName))) -and
    (Test-Path (Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_DATA_DIR" -AppBuildEnvByName $appBuildEnvByName)) -and
    (Test-Path (Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_SHADER" -AppBuildEnvByName $appBuildEnvByName))

$openXrLoaderPackaged = $false
if (-not [string]::IsNullOrWhiteSpace($OpenXrLoader) -and (Test-Path $OpenXrLoader)) {
    Copy-Item -LiteralPath $OpenXrLoader -Destination (Join-Path $nativeLibDir "libopenxr_loader.so") -Force
    $openXrLoaderPackaged = $true
}

Invoke-Checked "aapt2 link" $aapt2 @(
    "link",
    "-o", $apkUnsigned,
    "--manifest", $manifestInputPath,
    "-A", $assetsDir,
    "-I", $platformJar,
    "--min-sdk-version", "29",
    "--target-sdk-version", "35",
    "--version-code", "1",
    "--version-name", "0.1.0"
)

Copy-Item $apkUnsigned $apkUnaligned
Invoke-Checked "jar native lib update" $jar @("uf", $apkUnaligned, "-C", $nativeStageRoot, "lib")
Invoke-Checked "jar dex update" $jar @("uf", $apkUnaligned, "-C", $dexDir, "classes.dex")
Invoke-Checked "zipalign" $zipalign @("-f", "4", $apkUnaligned, $apkAligned)

Invoke-Checked "apksigner" $apksigner @(
    "sign",
    "--ks", $Keystore,
    "--ks-pass", "pass:android",
    "--key-pass", "pass:android",
    "--out", $apkSigned,
    $apkAligned
)

$sha256 = Get-FileSha256 -Path $apkSigned
$manifest = [ordered]@{
    '$schema' = "rusty.quest.native_renderer_android.build_manifest.v1"
    app_id = if ($null -eq $appBuildLockObject) { "unlocked-development" } else { [string]$appBuildLockObject.app_id }
    package_name = $packageName
    activity = $activityName
    entrypoint = "android.app.NativeActivity"
    authority = "rusty.quest.native_renderer"
    target_runtime = "quest-native-openxr-vulkan"
    plan_asset = "native-hwb-blur-sdf-public.plan.json"
    recorded_hand_replay_asset = "recorded-hand-replay-public-shape.json"
    source_plan_fixture = "fixtures/native-renderer/native-hwb-blur-sdf-public.plan.json"
    source_recorded_hand_replay_fixture = if ([string]::IsNullOrWhiteSpace($resolvedRecordedHandCaptureDir)) { "fixtures/native-renderer/recorded-hand-replay-public-shape.json" } else { $resolvedRecordedHandCaptureDir }
    recorded_hand_replay_embedded_source = if ([string]::IsNullOrWhiteSpace($resolvedRecordedHandCaptureDir)) { "public-topology-shape-fixture" } else { "external-recorded-capture-build-env" }
    marker_prefix = "RUSTY_QUEST_NATIVE_RENDERER"
    rust_native_activity = $true
    java_classes_packaged = $true
    panel_activity = "$packageName/io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity"
    panel_transport = "app-private-file"
    panel_candidate_file = "stimulus_volume_candidate.json"
    panel_status_file = "stimulus_volume_status.json"
    spatial_sdk_packaged = $false
    rust_native_crate = "apps/native-renderer-android/native/Cargo.toml"
    runtime_permission_request = "rust-jni-framework-activity-requestPermissions"
    public_effect_layers = @("blur-guide", "recorded-hand-replay-visual", "gpu-mesh-boundary", "target-space-validation-mesh-sdf")
    private_extension_payloads_packaged = [bool]$privateLayerPayloadLinked
    private_particle_payloads_packaged = [bool]$privateParticlePayloadLinked
    private_particle_payload_kind = if ($privateParticlePayloadLinked) { Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_KIND" -AppBuildEnvByName $appBuildEnvByName } else { "none" }
    camera_ids = [ordered]@{
        left = "50"
        right = "51"
    }
    hwb_import_path = "ndk-acamera-aimagereader-ahardwarebuffer-vulkan-external"
    descriptor_shape = "combined-immutable-sampler-ycbcr-conversion"
    openxr_vulkan_prereq_probe = "rust-native-openxr-loader-vulkan-instance-device-extension-check"
    vulkan_external_import_prereqs_reported = $true
    native_library = "lib/arm64-v8a/librusty_quest_native_renderer.so"
    lsl_native_library_packaged = $lslNativeLibraryPackaged
    lsl_native_library = $lslNativeLibraryPath
    lsl_native_library_sha256 = $lslNativeLibrarySha256
    declared_asset_inputs_packaged = $declaredAssetInputsPackaged
    questionnaire_assets_packaged = $questionnaireAssetsPackaged
    questionnaire_asset_source = $questionnaireAssetSource
    questionnaire_asset_root = if ($questionnaireAssetsPackaged) { "assets/maia_spatial_questionnaire" } else { "" }
    openxr_loader_packaged = $openXrLoaderPackaged
    apk_path = $apkSigned
    apk_sha256 = $sha256
    projection_visual_acceptance = $false
    recorded_hand_capture_required = [bool]$RequireRecordedHandCapture
    recorded_hand_capture_embedded = (-not [string]::IsNullOrWhiteSpace($resolvedRecordedHandCaptureDir))
    recorded_hand_capture_source_dir = $resolvedRecordedHandCaptureDir
    recorded_hand_frame_limit = $RecordedHandFrameLimit
    app_build_lock_path = if ([string]::IsNullOrWhiteSpace($appBuildLockPath)) { "" } else { $appBuildLockPath }
    app_build_lock_sha256 = $appBuildLockSha256
    app_build_resolution_fingerprint = if ($null -eq $appBuildLockObject) { "" } else { [string]$appBuildLockObject.resolution_fingerprint }
    source_commit = $sourceHead
    source_tree = $sourceTree
    source_tracked_worktree_clean = $true
    source_composition_fingerprint = [string]$sourceComposition.fingerprint
    source_dependencies = $sourceDependencies
    output_policy = "content-addressed-app-lock-source-composition"
    isolated_cargo_target_dir = $cargoTargetDir
    native_app_settings_path = if ([string]::IsNullOrWhiteSpace($nativeAppSettingsPath)) { "" } else { $nativeAppSettingsPath }
    native_app_settings_sha256 = if ([string]::IsNullOrWhiteSpace($nativeAppSettingsPath)) { "" } else { Get-FileSha256 -Path $nativeAppSettingsPath }
    app_build_manifest_input = $manifestInputPath
    app_build_selected_feature_ids = if ($null -eq $appBuildLockObject) { @() } else { @($appBuildLockObject.selected_feature_ids) }
    settings_authority = if ($null -eq $appBuildLockObject) { "" } else { [string]$appBuildLockObject.app_settings.authority }
    embedded_manifold_runtime_config_sha256 = Get-FileSha256 -Path $embeddedRuntimeConfigPath
    embedded_manifold_runtime_config_canonical_sha256 = $embeddedRuntimeConfigSha256
    embedded_manifold_product_spec_sha256 = Get-FileSha256 -Path $embeddedProductSpecPath
    embedded_manifold_product_lock_sha256 = Get-FileSha256 -Path $embeddedProductLockPath
    embedded_manifold_client_lock_sha256 = Get-FileSha256 -Path $embeddedClientLockPath
    embedded_manifold_app_feature_lock_sha256 = $embeddedAppFeatureLockSha256
    embedded_manifold_media_lifecycle_sha256 = Get-FileSha256 -Path $embeddedMediaLifecyclePath
    embedded_manifold_client_id = [string]$embeddedClientLock.client_id
    embedded_manifold_marker_namespace = [string]$embeddedClientLock.marker_namespace
    embedded_manifold_granted_capabilities = $embeddedGrantCapabilities
    embedded_manifold_authority_config_source = "packaged-generated-lock-closure"
}
$manifestPath = Join-Path $OutDir "build-manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $manifestPath

if ($script:BuildUsesLock) {
    $propertyManifestPath = Join-Path $repoRoot "fixtures\native-renderer\native-renderer-property-manifest.json"
    $runCapsule = [ordered]@{
        schema = "rusty.quest.apk_run_capsule.v1"
        capsule_id = "native-renderer-$([string]$appBuildLockObject.app_id)-$($appBuildLockSha256.Substring(0, 12))-$(([string]$sourceComposition.fingerprint).Substring(0, 12))"
        app_id = [string]$appBuildLockObject.app_id
        app_lane = "native-renderer-android"
        source = [ordered]@{
            repository = [string]$repoRoot
            commit = $sourceHead
            tree = $sourceTree
            tracked_worktree_clean = $true
            composition_fingerprint = [string]$sourceComposition.fingerprint
            packages = @($sourceComposition.packages)
            dependencies = $sourceDependencies
        }
        build_lock = [ordered]@{
            path = $appBuildLockPath
            sha256 = $appBuildLockSha256
            resolution_fingerprint = [string]$appBuildLockObject.resolution_fingerprint
        }
        build_manifest = [ordered]@{
            path = $manifestPath
            sha256 = Get-FileSha256 -Path $manifestPath
        }
        apk = [ordered]@{
            path = $apkSigned
            sha256 = $sha256
        }
        runtime_profile = [ordered]@{
            path = $runtimeProfilePath
            sha256 = Get-FileSha256 -Path $runtimeProfilePath
        }
        property_manifest = [ordered]@{
            path = $propertyManifestPath
            sha256 = Get-FileSha256 -Path $propertyManifestPath
            scope = "complete-manifest"
        }
        android = [ordered]@{
            package_name = $packageName
            activity = $activityName
        }
        cleanup = [ordered]@{
            policy = "always-force-stop-and-restore-exact-property-snapshot"
            serial_exclusive_mutex = $true
            restore_on_failure = $true
        }
    }
    $runCapsulePath = Join-Path $OutDir "run-capsule.json"
    $runCapsule | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 -Path $runCapsulePath
    Write-Output $runCapsulePath
}

Write-Output $apkSigned
