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
        [string]$DestinationName = "",
        [switch]$ExplicitExternalSource
    )

    $sourcePath = if ($ExplicitExternalSource) {
        [IO.Path]::GetFullPath($Source)
    } else {
        Resolve-NativeAppPublicAssetInput -AssetInput $Source -RepoRoot $RepoRoot
    }
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
Import-Module (Join-Path $PSScriptRoot 'lib\NativeAppPrivateAssetProvider.psm1') -Force
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
$panelSourceClosure = $null
$panelSourceFiles = @()
$selectedPanelModuleId = ""
$selectedPanelEntryClass = ""
$selectedPanelEntrySimpleName = ""
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
    foreach ($field in @("android_manifest", "generated_outputs", "app_settings", "build_inputs", "panel_source_closure")) {
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

    $panelSourceClosure = $appBuildLockObject.panel_source_closure
    if ([string]$panelSourceClosure.schema -cne "rusty.quest.native_renderer.panel_source_closure.v1") {
        throw "Unsupported native panel source-closure schema: $($panelSourceClosure.schema)"
    }
    if ($null -eq $panelSourceClosure.PSObject.Properties["runtime_widening_allowed"] -or
        [bool]$panelSourceClosure.runtime_widening_allowed) {
        throw "Native panel source closure must explicitly forbid runtime widening."
    }
    $panelActivitySelected = @($appBuildLockObject.android_manifest.activities) -ccontains "ControlPanelActivity"
    $selectedPanelModuleId = [string]$panelSourceClosure.selected_module_id
    $selectedPanelEntryClass = [string]$panelSourceClosure.entry_class
    if ($panelActivitySelected) {
        if ([string]::IsNullOrWhiteSpace($selectedPanelModuleId) -or
            [string]::IsNullOrWhiteSpace($selectedPanelEntryClass)) {
            throw "ControlPanelActivity requires exactly one baked native panel module."
        }
        if (@($panelSourceClosure.denied_module_ids) -ccontains $selectedPanelModuleId) {
            throw "Selected native panel module is denied by its own source closure: $selectedPanelModuleId"
        }
        if ($selectedPanelEntryClass -notmatch '^io\.github\.mesmerprism\.rustyquest\.native_renderer\.[A-Za-z][A-Za-z0-9_]*$') {
            throw "Native panel entry class is outside the fixed Android package: $selectedPanelEntryClass"
        }
        $selectedPanelEntrySimpleName = $selectedPanelEntryClass.Substring($selectedPanelEntryClass.LastIndexOf('.') + 1)
        $moduleIds = @{}
        foreach ($module in @($panelSourceClosure.modules)) {
            $moduleId = [string]$module.module_id
            if ([string]::IsNullOrWhiteSpace($moduleId) -or $moduleIds.ContainsKey($moduleId)) {
                throw "Native panel source closure contains an absent or duplicate module id: $moduleId"
            }
            $moduleIds[$moduleId] = $true
        }
        if (-not $moduleIds.ContainsKey($selectedPanelModuleId)) {
            throw "Native panel source closure omits selected module: $selectedPanelModuleId"
        }
        foreach ($module in @($panelSourceClosure.modules)) {
            foreach ($dependency in @($module.dependencies)) {
                if (-not $moduleIds.ContainsKey([string]$dependency)) {
                    throw "Native panel source closure omits dependency $dependency required by $($module.module_id)"
                }
            }
        }
        $selectedRecords = @($panelSourceClosure.modules | Where-Object {
            [string]$_.module_id -ceq $selectedPanelModuleId -and
            [string]$_.entry_class -ceq $selectedPanelEntryClass
        })
        if ($selectedRecords.Count -ne 1) {
            throw "Native panel source closure does not bind exactly one matching selected entry class."
        }
        $panelSourcePaths = @{}
        foreach ($sourceRecord in @($panelSourceClosure.source_files)) {
            $relativeSource = ([string]$sourceRecord.path).Replace('\', '/')
            if ([string]::IsNullOrWhiteSpace($relativeSource) -or
                -not $relativeSource.StartsWith('apps/native-renderer-android/panel-modules/', [System.StringComparison]::Ordinal) -or
                -not $relativeSource.EndsWith('.java', [System.StringComparison]::Ordinal)) {
                throw "Native panel source is outside the owned panel-module root: $relativeSource"
            }
            if ($panelSourcePaths.ContainsKey($relativeSource)) {
                throw "Native panel source closure contains a duplicate source: $relativeSource"
            }
            $panelSourcePaths[$relativeSource] = $true
            $resolvedPanelSource = Resolve-RepoPath -Path $relativeSource -RepoRoot ([string]$repoRoot)
            Assert-HashMatches `
                -Label "Native panel source $relativeSource" `
                -ExpectedSha256 ([string]$sourceRecord.sha256) `
                -Path $resolvedPanelSource
            $panelSourceFiles += $resolvedPanelSource
        }
        if ($panelSourceFiles.Count -eq 0) {
            throw "Selected native panel source closure is empty: $selectedPanelModuleId"
        }
    } elseif (-not [string]::IsNullOrWhiteSpace($selectedPanelModuleId) -or
        -not [string]::IsNullOrWhiteSpace($selectedPanelEntryClass) -or
        @($panelSourceClosure.modules).Count -ne 0 -or
        @($panelSourceClosure.source_files).Count -ne 0) {
        throw "Native app without ControlPanelActivity must carry an explicit empty panel source closure."
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
    $breathExpectedBindingEnvName = "RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_EXPECTED_BINDING_SHA256"
    $breathActivation = $appBuildLockObject.PSObject.Properties["breath_composition_activation"]
    if ($null -ne $breathActivation -and $null -ne $breathActivation.Value) {
        $expectedBreathBinding = [string]$breathActivation.Value.sha256
        if ([string]::IsNullOrWhiteSpace($expectedBreathBinding) -or
            -not $appBuildEnvByName.ContainsKey($breathExpectedBindingEnvName) -or
            [string]$appBuildEnvByName[$breathExpectedBindingEnvName] -cne $expectedBreathBinding) {
            throw "Native app-build packaged breath binding does not exactly match feature-lock activation"
        }
    } elseif ($appBuildEnvByName.ContainsKey($breathExpectedBindingEnvName)) {
        throw "Native app-build env carries a breath binding without a feature-lock activation"
    }
    $simultaneousExpectedBindingEnvName = "RUSTY_QUEST_NATIVE_RENDERER_SIMULTANEOUS_HANDS_CONTROLLERS_EXPECTED_BINDING_SHA256"
    $simultaneousActivation = $appBuildLockObject.PSObject.Properties["simultaneous_hands_controllers_activation"]
    if ($null -ne $simultaneousActivation -and $null -ne $simultaneousActivation.Value) {
        $expectedSimultaneousBinding = [string]$simultaneousActivation.Value.sha256
        if ([string]::IsNullOrWhiteSpace($expectedSimultaneousBinding) -or
            -not $appBuildEnvByName.ContainsKey($simultaneousExpectedBindingEnvName) -or
            [string]$appBuildEnvByName[$simultaneousExpectedBindingEnvName] -cne $expectedSimultaneousBinding) {
            throw "Native app-build packaged simultaneous hands/controllers binding does not exactly match feature-lock activation"
        }
    } elseif ($appBuildEnvByName.ContainsKey($simultaneousExpectedBindingEnvName)) {
        throw "Native app-build env carries a simultaneous hands/controllers binding without a feature-lock activation"
    }

    $undeclaredAmbient = @(Get-ChildItem Env: | Where-Object {
        $_.Name -like "RUSTY_QUEST_NATIVE_RENDERER_*" -and
        -not $appBuildEnvByName.ContainsKey([string]$_.Name) -and
        -not [string]::IsNullOrWhiteSpace([string]$_.Value)
    } | Select-Object -ExpandProperty Name | Sort-Object -Unique)
    if ($undeclaredAmbient.Count -gt 0) {
        throw "Locked native APK build rejected undeclared ambient feature inputs: $($undeclaredAmbient -join ', '). Add them to the app feature lock or clear them."
    }
} elseif ((Get-Content -LiteralPath $manifestInputPath -Raw) -match 'ControlPanelActivity') {
    throw "Unlocked development builds cannot package ControlPanelActivity; resolve an exact native-app feature lock first."
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
    '$schema' = "rusty.quest.broker.runtime_config.v2"
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
$generatedControlPanelActivityPath = ""
if (-not [string]::IsNullOrWhiteSpace($selectedPanelModuleId)) {
    # The experiment-session shell is an owning breath-composition concern.  Do
    # not let its audio/profile/native dependencies leak into another panel's
    # generated Java closure: those closures deliberately omit that source
    # family and its JNI declarations.
    $isExperimentSessionPanel = $selectedPanelModuleId -ceq "breath-composition-controls"
    $usesPolarSessionRuntime = $isExperimentSessionPanel -or
        $selectedPanelModuleId -ceq "polar-controls"
    $experimentSessionProfileSha256 = if ($appBuildEnvByName.ContainsKey("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROFILE_SHA256")) {
        [string]$appBuildEnvByName["RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROFILE_SHA256"]
    } else { "" }
    $experimentSessionProviderManifestSha256 = if ($appBuildEnvByName.ContainsKey("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROVIDER_MANIFEST_SHA256")) {
        [string]$appBuildEnvByName["RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROVIDER_MANIFEST_SHA256"]
    } else { "" }
    $experimentSessionInventorySha256 = if ($appBuildEnvByName.ContainsKey("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_INVENTORY_SHA256")) {
        [string]$appBuildEnvByName["RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_INVENTORY_SHA256"]
    } else { "" }
    $panelNativeMethods = if ($selectedPanelModuleId -ceq "stimulus-volume") {
@"
    static native String nativeSubmitLiveStimulusCandidate(String candidateJson);
"@
    } elseif ($selectedPanelModuleId -ceq "private-particle-controls" -or
              $selectedPanelModuleId -ceq "driver-profile-controls") {
@"
    static native String nativeSubmitLivePrivateParticleDynamics(String dynamicsJson);
"@
    } elseif ($selectedPanelModuleId -ceq "polar-controls") {
        ""
    } elseif ($selectedPanelModuleId -ceq "breath-composition-controls") {
@"
    static native String nativeSubmitLiveDepthAlignment(String alignmentJson);
    static native String nativeSubmitLivePrivateParticleDynamics(String dynamicsJson);
    static native String nativeStartDriverProfileSessionBlock(String blockJson);
    static native String nativeApplyBreathCompositionCommand(String commandJson);
    static native String nativeInitializeExperimentSessionRuntime(String appPrivateFilesRoot);
    static native String nativeApplyLslTransportCommand(String commandJson);
    static native String nativeReadLslTransportStatus();
    static native String nativeReadBreathCompositionStatus();

    static String applyLslTransportCommandFromOwner(String commandJson) {
        return nativeApplyLslTransportCommand(commandJson);
    }
"@
    } else {
        throw "Native panel module has no declared JNI adapter surface: $selectedPanelModuleId"
    }
    $experimentSessionTerminalAudioStop = if ($isExperimentSessionPanel) {
        "        stopCurrentConditionAudioForTerminal(true);"
    } else {
        ""
    }
    $experimentSessionOnCreate = if ($isExperimentSessionPanel) {
        "        ownerApplicationContext = getApplicationContext();"
    } else {
        ""
    }
    $polarRuntimeReopenFromExplicitLaunch = if ($usesPolarSessionRuntime) {
        "            PolarSensorRuntime.reopenClosedFromExplicitLaunch();"
    } else {
        ""
    }
    $generatedControlPanelActivityPath = Join-Path $generatedEmbeddedPackageDir "ControlPanelActivity.java"
    $generatedControlPanelActivitySource = @"
package io.github.mesmerprism.rustyquest.native_renderer;

/**
 * Build-generated Android shell for the one panel module admitted by the app lock.
 * Product pages and local presentation state live in the selected module; this class
 * remains the stable manifest/JNI/lifecycle boundary.
 */
public final class ControlPanelActivity extends $selectedPanelEntrySimpleName {
    static final PanelModuleRegistry PACKAGED_PANEL = PanelModuleRegistry.requireExact(
        "$selectedPanelModuleId",
        $selectedPanelEntrySimpleName.MODULE_ID,
        $selectedPanelEntrySimpleName.class
    );

    static {
        System.loadLibrary("rusty_quest_native_renderer");
    }

    private final PanelImmersiveHandoff immersiveHandoff = new PanelImmersiveHandoff(this);
    private static volatile java.lang.ref.WeakReference<ControlPanelActivity> visiblePanel =
        new java.lang.ref.WeakReference<>(null);
    private volatile boolean panelResumed;
    private static final Object PRESENTATION_LOCK = new Object();
    private static long presentationGeneration;
    private static boolean terminalIntentAdmitted;
    private static boolean terminalFinishConsumed;
    // EXPERIMENT_SESSION_SHELL_BEGIN
    private static volatile android.content.Context ownerApplicationContext;
    private static volatile ConditionAudioRuntime conditionAudioRuntime;
    private static volatile ExperimentSessionPackagedClosure.Result experimentSessionClosure;
    private static long pendingAudioGeneration;
    private static String pendingAudioPrepareOperation = "";
    private static boolean audioImmersiveAdmitted;
    private static boolean audioPrepared;
    private static long appliedAudioControlGeneration;
    private static long appliedAudioControlRevision;
    private static final ExperimentSessionPackagedClosure.Anchors EXPERIMENT_SESSION_ANCHORS =
        new ExperimentSessionPackagedClosure.Anchors(
            "$experimentSessionProfileSha256",
            "$experimentSessionProviderManifestSha256",
            "$experimentSessionInventorySha256");

    static String initializeExperimentSessionRuntimeFromOwner(String appPrivateFilesRoot) {
        if (appPrivateFilesRoot == null || appPrivateFilesRoot.trim().isEmpty()) {
            throw new IllegalArgumentException("exact app-private files root is required");
        }
        installConditionAudioFromPackagedClosure(appPrivateFilesRoot);
        PanelImmersiveHandoff.setCompletionListener(new PanelImmersiveHandoff.CompletionListener() {
            @Override public long currentSessionGeneration() {
                synchronized (PRESENTATION_LOCK) { return pendingAudioGeneration; }
            }
            @Override public void onCompletion(long generation, boolean stable) {
                synchronized (PRESENTATION_LOCK) {
                    if (generation <= 0L || generation != pendingAudioGeneration) return;
                    if (stable) admitStableImmersiveAudio(); else cancelPendingImmersiveAudio();
                }
            }
        });
        return nativeInitializeExperimentSessionRuntime(appPrivateFilesRoot);
    }

    private static void installConditionAudioFromPackagedClosure(String appPrivateFilesRoot) {
        android.content.Context context = ownerApplicationContext;
        ConditionAudioContract.TrustedPackagedInventory inventory =
            ConditionAudioContract.TrustedPackagedInventory.unavailable(
                "packaged-inventory-not-loaded");
        RuntimeException closureFailure = null;
        if (context == null) {
            closureFailure = new IllegalStateException("owner-application-context-unavailable");
        } else {
            try {
                final android.content.res.AssetManager assets = context.getAssets();
                java.nio.file.Path exactFilesRoot = context.getFilesDir().toPath();
                if (!exactFilesRoot.toString().equals(appPrivateFilesRoot)) {
                    throw new SecurityException("app-private-files-root-mismatch");
                }
                ExperimentSessionPackagedClosure.Result closure =
                    ExperimentSessionPackagedClosure.prepare(
                        readPackagedAsset(assets, "feature-lock.json"),
                        new ExperimentSessionPackagedClosure.AssetReader() {
                            @Override public byte[] read(String logicalDestination)
                                    throws Exception {
                                return readPackagedAsset(assets, logicalDestination);
                            }
                        },
                        exactFilesRoot,
                        EXPERIMENT_SESSION_ANCHORS);
                experimentSessionClosure = closure;
                if (closure.active) {
                    ConditionAudioContract.Provider[] providers =
                        new ConditionAudioContract.Provider[closure.audioEntries.length];
                    for (int index = 0; index < closure.audioEntries.length; index += 1) {
                        ExperimentSessionPackagedClosure.AudioEntry entry =
                            closure.audioEntries[index];
                        providers[index] = new ConditionAudioContract.Provider(
                            entry.conditionId,
                            entry.logicalDestination,
                            entry.logicalDestination,
                            entry.sourceSha256,
                            entry.sourceBytes,
                            entry.mediaType);
                    }
                    inventory =
                        ConditionAudioContract.TrustedPackagedInventory.fromValidatedProvider(
                            ConditionAudioContract.PACKAGED_PROVIDER_ORIGIN,
                            closure.inventorySha256,
                            providers);
                    if (!inventory.available) {
                        throw new SecurityException("condition-audio-inventory-invalid");
                    }
                } else {
                    inventory = ConditionAudioContract.TrustedPackagedInventory.unavailable(
                        "packaged-inventory-inactive");
                }
            } catch (Exception error) {
                experimentSessionClosure = null;
                inventory = ConditionAudioContract.TrustedPackagedInventory.unavailable(
                    "packaged-inventory-unavailable");
                closureFailure = new IllegalStateException(
                    "experiment-session-packaged-closure-rejected", error);
            }
        }
        android.content.res.AssetManager assets = context == null ? null : context.getAssets();
        conditionAudioRuntime = ConditionAudioRuntime.installAppLifetime(
            inventory,
            assets == null ? null : new ConditionAudioAndroidMediaBackendFactory(assets),
            new ConditionAudioContract.ReceiptSink() {
                @Override public void onReceipt(ConditionAudioContract.Receipt receipt) {
                    android.util.Log.i("RustyQuestNativeRenderer",
                        "channel=condition-audio event="
                            + receipt.event.name().toLowerCase(java.util.Locale.ROOT)
                            + " generation=" + receipt.sessionGeneration
                            + " revision=" + receipt.receiptRevision
                            + " reason=" + receipt.reason);
                    String eventOperation = receipt.event == ConditionAudioContract.Event.PREPARED
                        ? "audio-prepared" : receipt.event == ConditionAudioContract.Event.ACTUAL_START
                        ? "audio-started" : receipt.event == ConditionAudioContract.Event.NATURAL_END
                        ? "audio-ended" : receipt.event == ConditionAudioContract.Event.ERROR
                        ? "audio-error" : "";
                    if (!eventOperation.isEmpty()) {
                        synchronized (PRESENTATION_LOCK) {
                            if (terminalIntentAdmitted || receipt.sessionGeneration != pendingAudioGeneration)
                                return;
                        }
                        try {
                            org.json.JSONObject event = new org.json.JSONObject()
                                .put("schema", "rusty.quest.experiment_session.command.v1")
                                .put("operation", eventOperation)
                                .put("operation_id", "audio-event-" + receipt.sessionGeneration
                                    + "-" + receipt.receiptRevision)
                                .put("expected_generation", receipt.sessionGeneration)
                                .put("elapsed_realtime_ns", android.os.SystemClock.elapsedRealtimeNanos());
                            nativeApplyBreathCompositionCommand(event.toString());
                        } catch (Exception error) {
                            android.util.Log.e("RustyQuestNativeRenderer", "audio-event-record-failed", error);
                        }
                    }
                    if (receipt.event == ConditionAudioContract.Event.PREPARED) {
                        String expectedOperation;
                        long expectedGeneration;
                        synchronized (PRESENTATION_LOCK) {
                            expectedOperation = pendingAudioPrepareOperation;
                            expectedGeneration = pendingAudioGeneration;
                            if (receipt.sessionGeneration == expectedGeneration
                                    && expectedOperation.equals(receipt.operationId)) audioPrepared = true;
                        }
                    }
                }
            });
        if (closureFailure != null) throw closureFailure;
    }

    /** Binds a panel arm/start request to the verified packaged profile and audio asset. */
    static String applyExperimentSessionCommand(String commandJson) {
        try {
            org.json.JSONObject command = new org.json.JSONObject(commandJson == null ? "{}" : commandJson);
            String operation = command.optString("operation", "");
            if ("arm".equals(operation) || "start".equals(operation)) {
                ExperimentSessionPackagedClosure.Result closure = experimentSessionClosure;
                if (closure == null || !closure.active) {
                    throw new SecurityException("experiment-session-packaged-closure-unavailable");
                }
                String condition = command.optString("condition", "");
                ExperimentSessionPackagedClosure.AudioEntry selected = null;
                for (ExperimentSessionPackagedClosure.AudioEntry entry : closure.audioEntries) {
                    if (entry.conditionId.equals(condition)) selected = entry;
                }
                if (selected == null) {
                    throw new SecurityException("experiment-session-condition-not-packaged");
                }
                command.put("non_audio_profile_sha256", closure.nonAudioProfileSha256);
                command.put("audio", new org.json.JSONObject()
                    .put("logical_destination", selected.logicalDestination)
                    .put("source_sha256", selected.sourceSha256)
                    .put("source_bytes", selected.sourceBytes)
                    .put("media_type", selected.mediaType));
            }
            return nativeApplyBreathCompositionCommand(command.toString());
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("experiment-session-command-binding-failed", error);
        }
    }

    private static byte[] readPackagedAsset(
            android.content.res.AssetManager assets, String logicalDestination) throws Exception {
        java.io.InputStream stream = assets.open(
            logicalDestination, android.content.res.AssetManager.ACCESS_STREAMING);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read > 0) bytes.write(buffer, 0, read);
            }
        } finally {
            stream.close();
        }
        return bytes.toByteArray();
    }

    static String conditionAudioReadiness(String conditionId) {
        ConditionAudioRuntime runtime = conditionAudioRuntime;
        if (runtime == null) return "audio-inventory-unavailable";
        ConditionAudioContract.TrackReadiness readiness = runtime.trackReadiness(conditionId);
        return readiness.state == ConditionAudioContract.TrackState.READY
            ? "track-ready" : readiness.reason;
    }

    static boolean startConditionAudio(
            long sessionGeneration, String operationId, String conditionId) {
        ConditionAudioRuntime runtime = conditionAudioRuntime;
        if (runtime == null || sessionGeneration <= 0L || operationId == null
                || operationId.isEmpty()) return false;
        ConditionAudioContract.TrackReadiness readiness = runtime.trackReadiness(conditionId);
        if (readiness.state != ConditionAudioContract.TrackState.READY) return false;
        String prepareOperation = operationId + "-audio-prepare";
        synchronized (PRESENTATION_LOCK) {
            pendingAudioGeneration = sessionGeneration;
            pendingAudioPrepareOperation = prepareOperation;
            audioImmersiveAdmitted = false;
            audioPrepared = false;
            appliedAudioControlGeneration = sessionGeneration;
            appliedAudioControlRevision = 0L;
        }
        return runtime.submit(ConditionAudioContract.Command.prepare(
            sessionGeneration, prepareOperation, conditionId)).accepted;
    }

    static void admitStableImmersiveAudio() {
        synchronized (PRESENTATION_LOCK) {
            if (terminalIntentAdmitted || pendingAudioGeneration <= 0L) return;
            audioImmersiveAdmitted = true;
        }
    }

    /**
     * Called from the app-owned OpenXR loop only after the matching control event is durable.
     * The generation/revision fence makes controller and panel status projections idempotent.
     */
    public static void applyConditionAudioControlReceipt(
            long sessionGeneration, long receiptRevision, String event) {
        ConditionAudioRuntime runtime;
        String operationId;
        synchronized (PRESENTATION_LOCK) {
            if (terminalIntentAdmitted || sessionGeneration <= 0L || receiptRevision <= 0L
                    || sessionGeneration != pendingAudioGeneration
                    || sessionGeneration < appliedAudioControlGeneration
                    || (sessionGeneration == appliedAudioControlGeneration
                        && receiptRevision <= appliedAudioControlRevision)) {
                return;
            }
            appliedAudioControlGeneration = sessionGeneration;
            appliedAudioControlRevision = receiptRevision;
            if ("armed".equals(event)) return;
            if (!audioPrepared) {
                publishConditionAudioControlFailure(
                    sessionGeneration, receiptRevision, "audio-not-prepared");
                return;
            }
            runtime = conditionAudioRuntime;
            operationId = "controller-" + event + "-" + sessionGeneration + "-" + receiptRevision;
        }
        if (runtime == null) {
            publishConditionAudioControlFailure(
                sessionGeneration, receiptRevision, "audio-runtime-unavailable");
            return;
        }
        ConditionAudioContract.Command command;
        if ("official-start".equals(event)) {
            command = ConditionAudioContract.Command.start(sessionGeneration, operationId);
        } else if ("experiment-paused".equals(event)) {
            command = ConditionAudioContract.Command.pause(sessionGeneration, operationId);
        } else if ("experiment-resumed".equals(event)) {
            command = ConditionAudioContract.Command.resume(sessionGeneration, operationId);
        } else {
            return;
        }
        ConditionAudioContract.Submission submitted = runtime.submit(command);
        if (!submitted.accepted) {
            publishConditionAudioControlFailure(
                sessionGeneration, receiptRevision, submitted.reason);
        }
    }

    private static void publishConditionAudioControlFailure(
            long sessionGeneration, long receiptRevision, String reason) {
        android.util.Log.e("RustyQuestNativeRenderer",
            "channel=condition-audio event=controller-effect-rejected generation="
                + sessionGeneration + " revision=" + receiptRevision + " reason=" + reason);
        try {
            org.json.JSONObject event = new org.json.JSONObject()
                .put("schema", "rusty.quest.experiment_session.command.v1")
                .put("operation", "audio-error")
                .put("operation_id", "audio-control-error-" + sessionGeneration
                    + "-" + receiptRevision)
                .put("expected_generation", sessionGeneration)
                .put("elapsed_realtime_ns", android.os.SystemClock.elapsedRealtimeNanos());
            nativeApplyBreathCompositionCommand(event.toString());
        } catch (Exception error) {
            android.util.Log.e("RustyQuestNativeRenderer", "audio-control-error-record-failed", error);
        }
    }

    static void cancelPendingImmersiveAudio() {
        long generation;
        String operation;
        synchronized (PRESENTATION_LOCK) {
            generation = pendingAudioGeneration;
            operation = pendingAudioPrepareOperation;
        }
        if (generation > 0L) requestConditionAudioRestartStop(generation, operation + "-handoff-failed");
    }

    static boolean closeExperimentResourcesFromOwner() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("terminal cleanup must run off main");
        boolean clean = true;
        PanelImmersiveHandoff.setCompletionListener(null);
        try { clean = PolarSensorRuntime.closeExistingFromOwner(); }
        catch (Exception error) { clean = false; }
        ConditionAudioRuntime audio = conditionAudioRuntime;
        if (audio != null) {
            audio.close();
            try { clean = audio.awaitClosed(5000L) && clean; }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); clean = false; }
            if (ConditionAudioRuntime.appLifetimeReleasedForTest()) conditionAudioRuntime = null;
        }
        return clean;
    }

    static boolean requestConditionAudioStop(long sessionGeneration, String operationId) {
        return requestConditionAudioStop(
            sessionGeneration, operationId, ConditionAudioContract.StopReason.SAVE_AND_EXIT);
    }

    static boolean requestConditionAudioRestartStop(long sessionGeneration, String operationId) {
        return requestConditionAudioStop(
            sessionGeneration,
            operationId,
            ConditionAudioContract.StopReason.RESTART_TO_EXPERIMENTER);
    }

    // Called at the physical terminal latch, before native recording finalization completes.
    static void stopCurrentConditionAudioForTerminal(boolean fullExit) {
        ConditionAudioRuntime runtime = conditionAudioRuntime;
        if (runtime == null) return;
        long generation = runtime.snapshot().sessionGeneration;
        if (generation <= 0L) return;
        requestConditionAudioStop(generation, "audio-terminal-latch-" + generation,
            fullExit ? ConditionAudioContract.StopReason.SAVE_AND_EXIT
                : ConditionAudioContract.StopReason.RESTART_TO_EXPERIMENTER);
    }

    private static boolean requestConditionAudioStop(
            long sessionGeneration,
            String operationId,
            ConditionAudioContract.StopReason stopReason) {
        synchronized (PRESENTATION_LOCK) {
            if (sessionGeneration == pendingAudioGeneration) {
                pendingAudioGeneration = 0L;
                pendingAudioPrepareOperation = "";
                audioImmersiveAdmitted = false;
                audioPrepared = false;
                appliedAudioControlGeneration = 0L;
                appliedAudioControlRevision = 0L;
            }
        }
        ConditionAudioRuntime runtime = conditionAudioRuntime;
        if (runtime == null || sessionGeneration == 0L) return true;
        if (sessionGeneration < 0L || operationId == null || operationId.isEmpty()) return false;
        ConditionAudioContract.Snapshot snapshot = runtime.snapshot();
        if (snapshot.phase == ConditionAudioContract.Phase.UNAVAILABLE
                || snapshot.phase == ConditionAudioContract.Phase.IDLE
                || snapshot.phase == ConditionAudioContract.Phase.STOPPED
                || snapshot.phase == ConditionAudioContract.Phase.STOPPING) return true;
        return runtime.submit(ConditionAudioContract.Command.stop(
            sessionGeneration,
            operationId,
            stopReason)).accepted;
    }

    static String conditionAudioShutdownStatus() {
        ConditionAudioRuntime runtime = conditionAudioRuntime;
        if (runtime == null) return "complete";
        ConditionAudioContract.Phase phase = runtime.snapshot().phase;
        if (phase == ConditionAudioContract.Phase.UNAVAILABLE
                || phase == ConditionAudioContract.Phase.IDLE
                || phase == ConditionAudioContract.Phase.STOPPED) return "complete";
        if (phase == ConditionAudioContract.Phase.ERROR) return "error";
        return "pending";
    }
    // EXPERIMENT_SESSION_SHELL_END

    static boolean consumeExplicitColdUserLaunch(
            android.app.Activity activity,
            boolean recreation,
            android.content.Intent intent) {
        if (!(activity instanceof ControlPanelActivity) || recreation || intent == null
                || !android.content.Intent.ACTION_MAIN.equals(intent.getAction())
                || intent.getData() != null || intent.getSelector() != null
                || intent.getComponent() == null
                || !activity.getPackageName().equals(intent.getComponent().getPackageName())
                || !ControlPanelActivity.class.getName().equals(
                    intent.getComponent().getClassName())
                || intent.getCategories() == null
                || intent.getCategories().size() != 1
                || !intent.getCategories().contains("com.oculus.intent.category.2D")) {
            return false;
        }
        return NativeRendererExperimentLaunchAuthority.consume(
            intent.getStringExtra(NativeRendererExperimentLaunchAuthority.EXTRA_LAUNCH_PROVENANCE),
            intent.getLongExtra(NativeRendererExperimentLaunchAuthority.EXTRA_LAUNCH_EPOCH, 0L));
    }

    static boolean admitExplicitColdExperimentShell(
            android.app.Activity activity, long launchEpoch, String panelRoute) {
        if (!(activity instanceof ControlPanelActivity) || launchEpoch <= 0L
                || !NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER.equals(
                    panelRoute)) {
            return false;
        }
        ControlPanelActivity owner = (ControlPanelActivity) activity;
        NativeRendererSoftKioskCoordinator coordinator =
            NativeRendererSoftKioskCoordinator.process();
        NativeRendererSoftKioskCoordinator.Snapshot before = coordinator.snapshot();
        if (launchEpoch <= before.explicitLaunchEpoch
                || !owner.immersiveHandoff.admitExplicitColdUserLaunch()) {
            return false;
        }
        boolean armed = coordinator.armFromExplicitColdLaunch(
            launchEpoch,
            NativeRendererForegroundGuardPolicy.Presentation.PANEL,
        NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        if (armed) {
            NativeRendererSelfKioskApplication.armed(activity);
$polarRuntimeReopenFromExplicitLaunch
            synchronized (PRESENTATION_LOCK) {
                presentationGeneration = launchEpoch;
                terminalIntentAdmitted = false;
                terminalFinishConsumed = false;
            }
        }
        return armed;
    }

    private static long nextPresentationGeneration(long hint) {
        synchronized (PRESENTATION_LOCK) {
            NativeRendererSoftKioskCoordinator.Snapshot snapshot =
                NativeRendererSoftKioskCoordinator.process().snapshot();
            long next = Math.max(presentationGeneration, snapshot.generation);
            if (next == Long.MAX_VALUE) {
                throw new IllegalStateException("presentation generation exhausted");
            }
            next += 1L;
            if (hint > next) next = hint;
            presentationGeneration = next;
            return next;
        }
    }

    static boolean beginPanelTransition(
            android.app.Activity activity, String panelRoute, long routeGeneration) {
        if (!(activity instanceof ControlPanelActivity)) return false;
        String route = NativeRendererSoftKioskCoordinator.PANEL_ROUTE_DEVELOPER.equals(panelRoute)
            ? NativeRendererSoftKioskCoordinator.PANEL_ROUTE_DEVELOPER
            : NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER;
        return NativeRendererSoftKioskCoordinator.process().beginTransition(
            nextPresentationGeneration(routeGeneration),
            NativeRendererForegroundGuardPolicy.Presentation.PANEL,
            route,
            android.os.SystemClock.uptimeMillis(),
            5_000L);
    }

    static String softKioskEffectiveStatus(android.app.Activity activity) {
        NativeRendererSoftKioskCoordinator.Snapshot state =
            NativeRendererSoftKioskCoordinator.process().snapshot();
        return "Self guard requested=true; state="
            + state.effectiveness.name().toLowerCase(java.util.Locale.ROOT)
            + "; self_watchdog=" + state.serviceState.name().toLowerCase(java.util.Locale.ROOT)
            + "; " + NativeRendererSelfKioskService.readback()
            + "; armed=" + state.armed;
    }

    /** Stable, presentation-only status; verbose guard diagnostics stay out of operator UI. */
    static String softKioskUiState(android.app.Activity activity) {
        if (activity == null || !android.provider.Settings.canDrawOverlays(activity)) {
            return "permission-required";
        }
        NativeRendererSoftKioskCoordinator.Effectiveness effectiveness =
            NativeRendererSoftKioskCoordinator.process().snapshot().effectiveness;
        switch (effectiveness) {
            case READY_ARMED:
            case READY_DISARMED:
                return "ready";
            case WATCHDOG_STARTING:
                return "starting";
            case TERMINAL:
                return "ending";
            default:
                return "attention";
        }
    }

    static boolean openSelfKioskOverlaySettings(android.app.Activity activity) {
        if (!(activity instanceof ControlPanelActivity)) return false;
        android.content.Intent query = new android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:" + activity.getPackageName()));
        android.content.pm.ResolveInfo resolved = activity.getPackageManager().resolveActivity(
            query, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        android.content.pm.ActivityInfo target = resolved == null ? null : resolved.activityInfo;
        if (target == null || target.packageName == null || target.name == null) return false;
        android.content.ComponentName component = new android.content.ComponentName(
            target.packageName, target.name);
        NativeRendererSelfKioskApplication.beginSystemPrompt(activity);
        try {
            activity.startActivityForResult(query.setComponent(component), 60612);
        } catch (RuntimeException error) {
            NativeRendererSelfKioskApplication.endSystemPrompt(activity);
            return false;
        }
        return true;
    }

    static boolean admitTerminalSaveAndExit(
            android.app.Activity activity, android.content.Intent intent) {
        if (!(activity instanceof ControlPanelActivity) || intent == null) return false;
        long guardGeneration = intent.getLongExtra(
            NativeRendererSoftKioskCoordinator.EXTRA_GUARD_GENERATION, 0L);
        long homeEpisode = intent.getLongExtra(
            NativeRendererSoftKioskCoordinator.EXTRA_HOME_EPISODE, 0L);
        if (!NativeRendererSoftKioskCoordinator.process().admitsTerminalIntent(
                intent.getAction(),
                intent.getStringExtra(NativeRendererSoftKioskCoordinator.EXTRA_TERMINAL_ROUTE),
                guardGeneration,
                homeEpisode)) {
            return false;
        }
        synchronized (PRESENTATION_LOCK) {
            if (terminalIntentAdmitted) return false;
            terminalIntentAdmitted = true;
        }
        NativeRendererSelfKioskService.acknowledgeTerminalIntent(guardGeneration, homeEpisode);
        NativeRendererExperimentLaunchAuthority.invalidatePending();
        PanelImmersiveHandoff.cancelForTerminalExit(activity);
$experimentSessionTerminalAudioStop
        return true;
    }

    static void finishTerminalSaveAndExit(
            android.app.Activity activity,
            boolean saved,
            long sessionGeneration,
            String operationId,
            long shutdownAckRevision) {
        if (!(activity instanceof ControlPanelActivity) || sessionGeneration < 0L
                || operationId == null || !operationId.startsWith("panel-save-and-exit-")
                || shutdownAckRevision <= 0L) {
            return;
        }
        synchronized (PRESENTATION_LOCK) {
            if (!terminalIntentAdmitted || terminalFinishConsumed) return;
            terminalFinishConsumed = true;
        }
        android.util.Log.i("RustyQuestNativeRenderer",
            "status=terminal-finish saved=" + saved
                + " session_generation=" + sessionGeneration
                + " shutdown_ack_revision=" + shutdownAckRevision);
        android.app.ActivityManager manager = (android.app.ActivityManager)
            activity.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        if (manager != null) for (android.app.ActivityManager.AppTask task : manager.getAppTasks()) {
            android.content.Intent base = task.getTaskInfo().baseIntent;
            android.content.ComponentName component = base == null ? null : base.getComponent();
            if (component == null || activity.getPackageName().equals(component.getPackageName())) {
                android.util.Log.i("RustyQuestNativeRenderer",
                    "status=terminal-app-task-finish-dispatched task_id="
                        + task.getTaskInfo().id);
                task.finishAndRemoveTask();
            }
        }
        int ownedActivityFinishes =
            NativeRendererSelfKioskApplication.finishOwnedActivitiesForTerminalExit(activity);
        android.util.Log.i("RustyQuestNativeRenderer",
            "status=terminal-owned-activity-finishes count=" + ownedActivityFinishes);
        if (!activity.isFinishing()) activity.finishAndRemoveTask();
    }

    static int panelBackgroundColor() {
        return android.graphics.Color.rgb(17, 18, 22);
    }

    static int panelForegroundColor() {
        return android.graphics.Color.rgb(238, 240, 244);
    }

    static int panelMutedColor() {
        return android.graphics.Color.rgb(170, 176, 186);
    }

    static android.widget.Button panelButton(android.app.Activity activity, String label) {
        android.widget.Button button = new android.widget.Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    static android.widget.TextView panelText(
        android.app.Activity activity,
        String value,
        int sizeSp,
        int color,
        int verticalPaddingPx
    ) {
        android.widget.TextView view = new android.widget.TextView(activity);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setPadding(0, verticalPaddingPx, 0, verticalPaddingPx);
        return view;
    }

    static void closePanelAndReturnToImmersive(android.app.Activity activity) {
        if (!(activity instanceof ControlPanelActivity)) {
            throw new IllegalStateException("Panel handoff owner is not the packaged ControlPanelActivity");
        }
        NativeRendererSoftKioskCoordinator.process().beginTransition(
            nextPresentationGeneration(0L),
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER,
            android.os.SystemClock.uptimeMillis(),
            5_000L);
        ((ControlPanelActivity) activity).immersiveHandoff.request();
    }

    /**
     * Closes the foreground 2D panel without asking Quest's task organizer to resolve another
     * Activity launch. A repeated controller shortcut can otherwise be placed in a fresh 2D root
     * task, which creates another panel instead of delivering {@code onNewIntent} to this one.
     */
    static boolean requestCloseVisiblePanelFromNative() {
        final ControlPanelActivity panel = visiblePanel.get();
        if (panel == null || !panel.panelResumed || panel.isFinishing()
                || panel.isDestroyed()) {
            return false;
        }
        panel.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (panel.isFinishing() || panel.isDestroyed()) return;
                android.util.Log.i("RustyQuestNativeRenderer",
                    "channel=experiment-session-panel event=panel-toggle"
                        + " status=close-requested source=openxr-same-process");
                closePanelAndReturnToImmersive(panel);
            }
        });
        return true;
    }

    @Override
    protected void onCreate(android.os.Bundle state) {
        PACKAGED_PANEL.entryClass();
$experimentSessionOnCreate
        super.onCreate(state);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        panelResumed = true;
        visiblePanel = new java.lang.ref.WeakReference<>(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode == 60612) NativeRendererSelfKioskApplication.endSystemPrompt(this);
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onUserLeaveHint() {
        NativeRendererSelfKioskApplication.userLeaveHint(this);
        super.onUserLeaveHint();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
    }

    @Override
    protected void onPause() {
        panelResumed = false;
        java.lang.ref.WeakReference<ControlPanelActivity> current = visiblePanel;
        if (current.get() == this) {
            visiblePanel = new java.lang.ref.WeakReference<>(null);
        }
        immersiveHandoff.onPanelPaused();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        immersiveHandoff.onPanelDestroyed();
        super.onDestroy();
    }

$panelNativeMethods
}
"@
    if ($isExperimentSessionPanel) {
        $generatedControlPanelActivitySource = $generatedControlPanelActivitySource.Replace(
            '    // EXPERIMENT_SESSION_SHELL_BEGIN' + [Environment]::NewLine, '')
        $generatedControlPanelActivitySource = $generatedControlPanelActivitySource.Replace(
            '    // EXPERIMENT_SESSION_SHELL_END' + [Environment]::NewLine, '')
    } else {
        $generatedControlPanelActivitySource = [regex]::Replace(
            $generatedControlPanelActivitySource,
            '(?s)    // EXPERIMENT_SESSION_SHELL_BEGIN\r?\n.*?    // EXPERIMENT_SESSION_SHELL_END\r?\n',
            '')
    }
    [System.IO.File]::WriteAllText(
        $generatedControlPanelActivityPath,
        $generatedControlPanelActivitySource,
        (New-Object System.Text.UTF8Encoding($false)))
}
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
    $panelSourceClosure | ConvertTo-Json -Depth 20 |
        Set-Content -LiteralPath (Join-Path $assetsDir "panel-source-closure.json") -Encoding UTF8
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

$privateAssetsPackaged = @()
if ($null -ne $appBuildLockObject) {
    if ($null -eq $appBuildLockObject.build_inputs.PSObject.Properties['private_asset_closure']) {
        throw 'Native app-build feature lock is missing build_inputs.private_asset_closure.'
    }
    $privateAssetsPackaged = @(Copy-NativeAppPrivateAssetsFromClosure `
        -Closure $appBuildLockObject.build_inputs.private_asset_closure `
        -FeatureLockPath $appBuildLockPath `
        -DestinationRoot $assetsDir)
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
    [void](Copy-AssetInput -Source $questionnaireAssetSource -DestinationRoot $assetsDir -RepoRoot ([string]$repoRoot) -DestinationName "maia_spatial_questionnaire" -ExplicitExternalSource)
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
$sharedBrokerTransportJavaRoot = Join-Path $repoRoot "crates\rusty-quest-broker-transport\android"
$sharedBrokerTransportJava = Get-ChildItem -LiteralPath $sharedBrokerTransportJavaRoot -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
if ($sharedBrokerTransportJava.Count -lt 1) {
    throw "Shared broker transport Android sources are incomplete: $sharedBrokerTransportJavaRoot"
}
$sourceFiles = @($sourceFiles) +
    @($panelSourceFiles) +
    @($sharedBrokerClientJava) +
    @($sharedBrokerTransportJava) +
    @($generatedEmbeddedRuntimeConfigPath)
if (-not [string]::IsNullOrWhiteSpace($generatedControlPanelActivityPath)) {
    $sourceFiles += $generatedControlPanelActivityPath
}
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
$rustyLslBackendPackaged = Test-TruthyBuildEnvValue -Value (Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_RUSTY_LSL_ANDROID" -AppBuildEnvByName $appBuildEnvByName)
$cargoBuildArguments = @(
    "build",
    "--manifest-path", (Join-Path $appRoot "native\Cargo.toml"),
    "--locked",
    "--target", "aarch64-linux-android",
    "--release",
    "--target-dir", $cargoTargetDir
)
if ($rustyLslBackendPackaged) {
    $cargoBuildArguments += @("--features", "rusty-lsl-backend")
}
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
    Invoke-Checked "native renderer cargo build" $cargoCommand.Source $cargoBuildArguments
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

$privateLayerGuideShaderPath = Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_LAYER_GUIDE_SHADER" -AppBuildEnvByName $appBuildEnvByName
$privateLayerProjectionShaderPath = Get-EffectiveBuildEnvValue -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_LAYER_PROJECTION_SHADER" -AppBuildEnvByName $appBuildEnvByName
$privateLayerPayloadLinked =
    (-not [string]::IsNullOrWhiteSpace($privateLayerGuideShaderPath)) -and
    (-not [string]::IsNullOrWhiteSpace($privateLayerProjectionShaderPath)) -and
    (Test-Path $privateLayerGuideShaderPath) -and
    (Test-Path $privateLayerProjectionShaderPath)

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
    "-I", $platformJar,
    "--min-sdk-version", "29",
    "--target-sdk-version", "35",
    "--version-code", "1",
    "--version-name", "0.1.0"
)

Copy-Item $apkUnsigned $apkUnaligned
Invoke-Checked "jar portable asset update" $jar @("uf0", $apkUnaligned, "-C", $OutDir, "assets")
$portableApkAssetEntries = @(Assert-NativeAppApkAssetArchive `
    -ApkPath $apkUnaligned `
    -AssetRoot $assetsDir)
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
    panel_activity_packaged = (-not [string]::IsNullOrWhiteSpace($selectedPanelModuleId))
    panel_activity = if ([string]::IsNullOrWhiteSpace($selectedPanelModuleId)) { "" } else { "$packageName/io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity" }
    selected_android_activities = if ($null -eq $appBuildLockObject) {
        @(
            "android.app.NativeActivity"
            if (-not [string]::IsNullOrWhiteSpace($selectedPanelModuleId)) { "ControlPanelActivity" }
        )
    } else {
        @($appBuildLockObject.android_manifest.activities | ForEach-Object { [string]$_ })
    }
    selected_panel_module_id = $selectedPanelModuleId
    selected_panel_entry_class = $selectedPanelEntryClass
    panel_runtime_widening_allowed = $false
    panel_source_closure_asset = if ([string]::IsNullOrWhiteSpace($selectedPanelModuleId)) { "" } else { "panel-source-closure.json" }
    panel_source_closure = $panelSourceClosure
    panel_java_sources = if ($null -eq $panelSourceClosure) { @() } else { @($panelSourceClosure.source_files) }
    java_compile_source_count = @($sourceFiles).Count
    java_compile_sources = @($sourceFiles | ForEach-Object { [System.IO.Path]::GetFullPath([string]$_) })
    panel_transport = "app-private-file"
    panel_candidate_file = if ($selectedPanelModuleId -eq "stimulus-volume") { "stimulus_volume_candidate.json" } else { "" }
    panel_status_file = if ($selectedPanelModuleId -eq "stimulus-volume") { "stimulus_volume_status.json" } else { "" }
    spatial_sdk_packaged = $false
    rust_native_crate = "apps/native-renderer-android/native/Cargo.toml"
    runtime_permission_request = "rust-jni-framework-activity-requestPermissions"
    public_effect_layers = @("blur-guide", "recorded-hand-replay-visual", "gpu-mesh-boundary", "target-space-validation-mesh-sdf")
    private_extension_payloads_packaged = [bool]$privateLayerPayloadLinked
    private_layer_guide_push_constant_bytes = 112
    private_layer_guide_push_constant_portable_limit_bytes = 128
    private_layer_guide_phase_rate_hz = 0.5
    private_layer_guide_shader_sha256 = if ($privateLayerPayloadLinked) { Get-FileSha256 -Path $privateLayerGuideShaderPath } else { "" }
    private_layer_projection_shader_sha256 = if ($privateLayerPayloadLinked) { Get-FileSha256 -Path $privateLayerProjectionShaderPath } else { "" }
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
    rusty_lsl_backend_packaged = $rustyLslBackendPackaged
    rusty_lsl_source_repository = if ($rustyLslBackendPackaged) { "https://github.com/MesmerPrism/rusty-lsl.git" } else { "" }
    rusty_lsl_source_commit = if ($rustyLslBackendPackaged) { "8b6b2a6cd0c0e5147b7e1cc076a116ef226cddbd" } else { "" }
    rusty_lsl_source_tree = if ($rustyLslBackendPackaged) { "4bfd1b1b5621af6706aafa9477e7a4f5764dd688" } else { "" }
    declared_asset_inputs_packaged = $declaredAssetInputsPackaged
    private_asset_provider = if ($null -eq $appBuildLockObject) {
        [ordered]@{
            schema = 'rusty.quest.native_app_private_asset_closure.v1'
            mode = 'inactive'
            provider_id = ''
            provider_manifest_sha256 = ''
            inventory_sha256 = ''
            closure_sha256 = ''
            asset_count = 0
            assets = @()
        }
    } else {
        [ordered]@{
            schema = [string]$appBuildLockObject.build_inputs.private_asset_closure.schema
            mode = [string]$appBuildLockObject.build_inputs.private_asset_closure.mode
            provider_id = [string]$appBuildLockObject.build_inputs.private_asset_closure.provider_id
            provider_manifest_sha256 = [string]$appBuildLockObject.build_inputs.private_asset_closure.provider_manifest_sha256
            inventory_sha256 = [string]$appBuildLockObject.build_inputs.private_asset_closure.inventory_sha256
            closure_sha256 = Get-NativeAppPrivateAssetTextSha256 -Text ($appBuildLockObject.build_inputs.private_asset_closure | ConvertTo-Json -Depth 12 -Compress)
            asset_count = [int]$appBuildLockObject.build_inputs.private_asset_closure.asset_count
            assets = $privateAssetsPackaged
        }
    }
    portable_apk_asset_entries = $portableApkAssetEntries
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
