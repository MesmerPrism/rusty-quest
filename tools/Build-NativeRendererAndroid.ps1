param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NdkHome = $env:ANDROID_NDK_HOME,
    [string]$OpenXrLoader = "S:\Work\tools\Quest\openxr-loader\libopenxr_loader.so",
    [string]$OutDir = "",
    [string]$Keystore = "",
    [string]$AppBuildLock = "",
    [string]$RecordedHandCaptureDir = $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR,
    [int]$RecordedHandFrameLimit = 12,
    [switch]$RequireRecordedHandCapture
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
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $targetRoot "native-renderer-android"
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
$linker = Join-Path $NdkHome "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang.cmd"
$cargoCommand = Get-Command cargo -ErrorAction Stop

foreach ($tool in @($platformJar, $aapt2, $d8, $zipalign, $apksigner, $javac, $jar, $keytool, $linker)) {
    if (-not (Test-Path $tool)) {
        throw "Required tool not found: $tool"
    }
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

$assetsDir = Join-Path $OutDir "assets"
$classesDir = Join-Path $OutDir "classes"
$dexDir = Join-Path $OutDir "dex"
$classesJar = Join-Path $OutDir "classes.jar"
$nativeStageRoot = Join-Path $OutDir "native"
$nativeLibDir = Join-Path $nativeStageRoot "lib\arm64-v8a"
$cargoTargetDir = Join-Path $OutDir "cargo-target"
$apkUnsigned = Join-Path $OutDir "rusty-quest-native-renderer-unsigned.apk"
$apkUnaligned = Join-Path $OutDir "rusty-quest-native-renderer-unaligned.apk"
$apkAligned = Join-Path $OutDir "rusty-quest-native-renderer-aligned.apk"
$apkSigned = Join-Path $OutDir "rusty-quest-native-renderer.apk"
$nativeLib = Join-Path $nativeLibDir "librusty_quest_native_renderer.so"
if ([string]::IsNullOrWhiteSpace($Keystore)) {
    $Keystore = Join-Path $targetRoot "rusty-quest-native-renderer-debug.keystore"
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
if (-not [string]::IsNullOrWhiteSpace($AppBuildLock)) {
    $appBuildLockPath = Resolve-RepoPath -Path $AppBuildLock -RepoRoot ([string]$repoRoot)
    $appBuildLockObject = Read-JsonFile -Path $appBuildLockPath
    if ([string]$appBuildLockObject.schema -ne "rusty.quest.native_app_feature_lock.v1") {
        throw "Unsupported native app-build feature lock schema: $($appBuildLockObject.schema)"
    }
    foreach ($field in @("android_manifest", "generated_outputs", "app_settings", "build_inputs")) {
        if ($null -eq $appBuildLockObject.PSObject.Properties[$field]) {
            throw "Native app-build feature lock is missing required field for APK build: $field"
        }
    }
    foreach ($field in @("app_spec_path", "app_spec_sha256", "feature_descriptors")) {
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
    $appBuildEnvPath = Resolve-RepoPath -Path ([string]$appBuildLockObject.generated_outputs.build_env) -RepoRoot ([string]$repoRoot)
    $generatedBuildManifestPath = Resolve-RepoPath -Path ([string]$appBuildLockObject.generated_outputs.build_manifest) -RepoRoot ([string]$repoRoot)
    foreach ($path in @($generatedManifestPath, $nativeAppSettingsPath, $appBuildEnvPath, $generatedBuildManifestPath)) {
        if (-not (Test-Path -LiteralPath $path)) {
            throw "Native app-build generated artifact is missing: $path"
        }
    }
    if ([string]$appBuildLockObject.app_settings.sha256 -ne (Get-FileSha256 -Path $nativeAppSettingsPath)) {
        throw "Native app-build settings hash does not match feature lock app_settings.sha256"
    }
    $generatedBuildManifest = Read-JsonFile -Path $generatedBuildManifestPath
    foreach ($field in @("feature_lock_sha256", "native_app_settings_sha256", "android_manifest_sha256", "build_env_sha256")) {
        if ($null -eq $generatedBuildManifest.PSObject.Properties[$field]) {
            throw "Native app-build generated build manifest is missing hash field: $field"
        }
    }
    Assert-HashMatches `
        -Label "Native app-build feature lock" `
        -ExpectedSha256 ([string]$generatedBuildManifest.feature_lock_sha256) `
        -Path $appBuildLockPath
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
}

New-Item -ItemType Directory -Force -Path $assetsDir, $classesDir, $dexDir, $nativeLibDir | Out-Null
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
    panel_activity = "io.github.mesmerprism.rustyquest.native_renderer/.ControlPanelActivity"
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
    app_build_lock_sha256 = if ([string]::IsNullOrWhiteSpace($appBuildLockPath)) { "" } else { Get-FileSha256 -Path $appBuildLockPath }
    native_app_settings_path = if ([string]::IsNullOrWhiteSpace($nativeAppSettingsPath)) { "" } else { $nativeAppSettingsPath }
    native_app_settings_sha256 = if ([string]::IsNullOrWhiteSpace($nativeAppSettingsPath)) { "" } else { Get-FileSha256 -Path $nativeAppSettingsPath }
    app_build_manifest_input = $manifestInputPath
    app_build_selected_feature_ids = if ($null -eq $appBuildLockObject) { @() } else { @($appBuildLockObject.selected_feature_ids) }
    settings_authority = if ($null -eq $appBuildLockObject) { "" } else { [string]$appBuildLockObject.app_settings.authority }
}
$manifestPath = Join-Path $OutDir "build-manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $manifestPath

Write-Output $apkSigned
