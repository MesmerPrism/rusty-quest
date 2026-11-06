param([string]$RepoRoot, [string]$EvidenceRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..") }
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
Import-Module (Join-Path $repo "tools\lib\SourceComposition.psm1") -Force
Import-Module (Join-Path $repo "tools\lib\QuestRunIsolation.psm1") -Force

function Assert-Contains { param([string]$Label,[string]$Text,[string]$Needle) if (-not $Text.Contains($Needle)) { throw "$Label is missing isolation guardrail: $Needle" } }
function Assert-NotContains { param([string]$Label,[string]$Text,[string]$Needle) if ($Text.Contains($Needle)) { throw "$Label contains forbidden isolation token: $Needle" } }
function Get-Sha { param([string]$Path) (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Get-StringSha { param([string]$Value) $sha=[Security.Cryptography.SHA256]::Create();try{([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace("-","").ToLowerInvariant()}finally{$sha.Dispose()} }
function Invoke-ExpectedCapsuleFailure {
    param([Parameter(Mandatory=$true)][string]$Path)
    $previousPreference = $ErrorActionPreference
    try {
        # Windows PowerShell promotes a child native process's stderr to an
        # ErrorRecord when Stop is active, even when the failure is expected.
        $ErrorActionPreference = "Continue"
        & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo "tools\Test-ApkRunCapsule.ps1") -CapsulePath $Path -ExpectedLane native-renderer-android *> $null
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

$nativeBuild = Get-Content -LiteralPath (Join-Path $repo "tools\Build-NativeRendererAndroid.ps1") -Raw
$spatialBuild = Get-Content -LiteralPath (Join-Path $repo "tools\Build-SpatialCameraPanelAndroid.ps1") -Raw
$nativeSmoke = Get-Content -LiteralPath (Join-Path $repo "tools\Invoke-NativeRendererReplaySmoke.ps1") -Raw
$spatialSmoke = Get-Content -LiteralPath (Join-Path $repo "tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1") -Raw
$profileTool = Get-Content -LiteralPath (Join-Path $repo "tools\Apply-RuntimeProfile.ps1") -Raw
$isolationModule = Get-Content -LiteralPath (Join-Path $repo "tools\lib\QuestRunIsolation.psm1") -Raw
$propertyTransportModule = Get-Content -LiteralPath (Join-Path $repo "tools\lib\QuestPropertyTransport.psm1") -Raw
$recoveryTool = Get-Content -LiteralPath (Join-Path $repo "tools\Recover-QuestRunIsolation.ps1") -Raw
$sourceCompositionModule = Get-Content -LiteralPath (Join-Path $repo "tools\lib\SourceComposition.psm1") -Raw
$spatialDebugManifest = Get-Content -LiteralPath (Join-Path $repo "apps\spatial-camera-panel-android\app\src\debug\AndroidManifest.xml") -Raw

foreach ($needle in @("-AppBuildLock is required", "Locked native APK build rejected undeclared ambient feature inputs", "content-addressed-app-lock-source-composition", "generated-native-renderer.broker-media-client.feature.lock.json", "embedded_manifold_app_feature_lock_sha256", "run-capsule.json")) { Assert-Contains "native build" $nativeBuild $needle }
foreach ($needle in @("-AppId is required so each Spatial project has a distinct Android identity", "RUSTY_QUEST_SPATIAL_BUILD_ROOT", "content-addressed-explicit-input-lock", "ambient_spatial_feature_environment_ignored", "`$propertyScanRoots", "spatial-property-manifest.json", "complete-source-consumer-surface", "run-capsule.json")) { Assert-Contains "Spatial build" $spatialBuild $needle }
Assert-Contains "Spatial debug provider" $spatialDebugManifest '${applicationId}.debug-host-receipt'
Assert-NotContains "Spatial debug provider" $spatialDebugManifest 'io.github.mesmerprism.rustyquest.spatial_camera_panel.debug-host-receipt'
foreach ($text in @($nativeSmoke, $spatialSmoke)) {
    Assert-Contains "smoke wrapper" $text "-RunCapsule is required"
    Assert-Contains "smoke wrapper" $text "Enter-QuestRunIsolation"
    Assert-Contains "smoke wrapper" $text "Clear-QuestRunIsolationProperties"
    Assert-Contains "smoke wrapper" $text "Exit-QuestRunIsolation"
    Assert-Contains "smoke wrapper" $text "cleanup_always = `$true"
}
Assert-Contains "native smoke" $nativeSmoke '"-PropertyScopeMode", "CompleteManifest"'
foreach ($needle in @('$process.Handle | Out-Null', '$process.WaitForExit()', '$process.Refresh()')) { Assert-Contains "native smoke process wait" $nativeSmoke $needle }
Assert-Contains "Spatial smoke" $spatialSmoke 'if ($ForceStopKnownXrPackages)'
Assert-Contains "Spatial smoke" $spatialSmoke '$capsule.property_manifest.path'
Assert-Contains "runtime profile" $profileTool '[ValidateSet("ProfileOwned", "CompleteManifest")]'
Assert-Contains "runtime profile" $profileTool 'property_scope_mode = $PropertyScopeMode'
foreach ($needle in @("target\apk-r", "Get-QuestRunCapsuleInstallApk", "Local\RustyMorphospaceQuestRun-", "property_snapshot", "Clear-QuestRunIsolationProperties", "complete_property_clear", "Exit-QuestRunIsolation", "Recover-QuestRunIsolation", "force-stop", "property_restore", "receipt-bound-cross-process")) { Assert-Contains "run isolation module" $isolationModule $needle }
Assert-Contains "runtime profile" $profileTool "ordered-batched-setprop"
foreach ($needle in @("New-QuestPropertySetpropBatches", "Get-QuestPropertyGlobalReadback", "Test-QuestPropertyExactReadback", "MaxCommandUtf8Bytes", "QuestPropertySetpropCommandSeparator", " && ", "duplicate property")) { Assert-Contains "property transport module" $propertyTransportModule $needle }
foreach ($needle in @("-EnteredReceiptPath", "-TerminalReceiptPath", "-ExpectedSerial", "-ExpectedPackageName", "-Adb must be an explicit existing executable path")) { Assert-Contains "recovery entrypoint" $recoveryTool $needle }
foreach ($text in @($nativeSmoke, $spatialSmoke)) { Assert-Contains "smoke wrapper short APK staging" $text "Get-QuestRunCapsuleInstallApk" }
foreach ($needle in @("cargo metadata --format-version 1 --locked", "path-dependency", "tracked_worktree_clean", "worktree_overlay_sha256", "AllowWorkingTreeChanges", "apk_source_composition_identity.v")) { Assert-Contains "source composition module" $sourceCompositionModule $needle }
foreach ($text in @($nativeBuild, $spatialBuild)) {
    Assert-Contains "APK builder" $text "Get-QuestBuildSourceComposition"
    Assert-Contains "APK builder" $text "source_composition_fingerprint"
    Assert-Contains "APK builder" $text "source_dependencies"
    Assert-Contains "APK builder" $text '"--locked"'
    Assert-Contains "APK builder" $text "apk-i"
}
Assert-Contains "Spatial iteration build" $spatialBuild "-AllowWorkingTreeChanges:(-not [bool]`$PublicationBuild)"
Assert-Contains "Spatial publication build" $spatialBuild '[switch]$PublicationBuild'
foreach ($needle in @(
    '$BuildCacheRoot.Length -gt 64',
    'BuildCacheRoot must remain deliberately short (64 characters or fewer)',
    '$cacheLaneId = (("{0}-{1}" -f $resolvedProductId, $buildTypeLower)',
    'Join-Path $BuildCacheRoot ("l\{0}" -f $cacheLaneId)',
    'Join-Path $BuildCacheRoot ("g\{0}" -f $cacheLaneId)',
    'Join-Path $BuildCacheRoot "gp"',
    'Join-Path $BuildCacheRoot "gu"',
    'Join-Path $BuildCacheRoot ("c\{0}" -f $RustStdLinkage.Substring(0, 1).ToLowerInvariant())',
    'native = $nativeFingerprint',
    'android_shell = $shellFingerprint',
    'package = $packageFingerprint',
    'stable_short_cache = $true',
    'paths_recorded = $false',
    'build_cache_paths_recorded = $false'
)) {
    Assert-Contains "Spatial stable cache identity" $spatialBuild $needle
}
if ($spatialBuild.Contains('buildInputFingerprint.Substring(0, 16)', [StringComparison]::Ordinal)) {
    throw 'Spatial compiler intermediates must not derive from the complete APK buildInputFingerprint.'
}

$temp = Join-Path ([IO.Path]::GetTempPath()) ("rusty-quest-run-capsule-test-" + [guid]::NewGuid().ToString("N"))
if ($EvidenceRoot) { $temp = Join-Path ([IO.Path]::GetFullPath($EvidenceRoot)) ('capsule-' + [guid]::NewGuid().ToString('N')) }
try {
    New-Item -ItemType Directory -Force -Path $temp | Out-Null
    if ($null -eq (Get-Command New-QuestRunCapsule -ErrorAction SilentlyContinue)) { throw 'Neutral capsule creation/reading owner API is missing.' }
    $files = @{}
    foreach ($name in @("build-lock.json", "build-manifest.json", "test.apk", "runtime-profile.json", "property-manifest.json")) {
        $path = Join-Path $temp $name
        [IO.File]::WriteAllText($path, "fixture:$name", [Text.UTF8Encoding]::new($false))
        $files[$name] = $path
    }
    $head = ([string](& git -C $repo rev-parse HEAD)).Trim().ToLowerInvariant()
    $tree = ([string](& git -C $repo rev-parse 'HEAD^{tree}')).Trim().ToLowerInvariant()
    $packages = @("static-test-package")
    $identityRepositories = @([pscustomobject][ordered]@{ repository_id = "rusty-quest"; role = "primary"; commit = $head; tree = $tree })
    $canonicalIdentity = Get-QuestBuildSourceCompositionIdentityCanonicalText -PackageName $packages -Repository $identityRepositories
    $compositionFingerprint = Get-StringSha $canonicalIdentity
    $record = { param($name) [ordered]@{ path = $files[$name]; sha256 = Get-Sha $files[$name] } }
    $capsule = [ordered]@{
        schema = "rusty.quest.apk_run_capsule.v1"; capsule_id = "static-test"; app_id = "static-test"; app_lane = "native-renderer-android"
        source = [ordered]@{ repository = $repo; commit = $head; tree = $tree; tracked_worktree_clean = $true; composition_fingerprint = $compositionFingerprint; packages = $packages; dependencies = @() }
        build_lock = & $record "build-lock.json"; build_manifest = & $record "build-manifest.json"; apk = & $record "test.apk"
        runtime_profile = & $record "runtime-profile.json"
        property_manifest = [ordered]@{ path = $files["property-manifest.json"]; sha256 = Get-Sha $files["property-manifest.json"]; scope = "complete-manifest" }
        android = [ordered]@{ package_name = "io.github.mesmerprism.rustyquest.test_isolated"; activity = "io.github.mesmerprism.rustyquest.test_isolated/android.app.NativeActivity" }
        cleanup = [ordered]@{ policy = "always-force-stop-and-restore-exact-property-snapshot"; serial_exclusive_mutex = $true; restore_on_failure = $true }
    }
    $capsulePath = Join-Path $temp "run-capsule.json"
    $capsule | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $capsulePath -Encoding UTF8
    $valid = & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo "tools\Test-ApkRunCapsule.ps1") -CapsulePath $capsulePath -ExpectedLane native-renderer-android
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($valid -join "`n"))) { throw "Valid APK run capsule was rejected." }
    # The historical manual validator admits exact dirty overlays even though
    # the old clean-only JSON schema does not. Preserve that caller contract.
    $cleanSource = $capsule.source
    $dirtyIdentity = @([pscustomobject]@{repository_id='rusty-quest';role='primary';commit=$head;tree=$tree;tracked_worktree_clean=$false;worktree_overlay_sha256=('a'*64)})
    $capsule.source = [ordered]@{repository=$repo;commit=$head;tree=$tree;tracked_worktree_clean=$false;worktree_overlay_sha256=('a'*64);composition_fingerprint=(Get-StringSha (Get-QuestBuildSourceCompositionIdentityCanonicalText -PackageName $packages -Repository $dirtyIdentity));packages=$packages;dependencies=@()}
    $capsule | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $capsulePath -Encoding UTF8
    $null = & pwsh -NoProfile -File (Join-Path $repo 'tools/Test-ApkRunCapsule.ps1') -CapsulePath $capsulePath
    if ($LASTEXITCODE -ne 0) { throw 'Legacy DirtyDevFast capsule was hardened incompatibly.' }
    $null = Read-QuestRunCapsule -Path $capsulePath
    $capsule.source = $cleanSource
    $capsule.source.composition_fingerprint = "0" * 64
    $capsule | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $capsulePath -Encoding UTF8
    if ((Invoke-ExpectedCapsuleFailure -Path $capsulePath) -eq 0) { throw "Damaged source-composition fingerprint was accepted." }
    $capsule.source.composition_fingerprint = $compositionFingerprint
    $capsule | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $capsulePath -Encoding UTF8
    Add-Content -LiteralPath $files["test.apk"] -Value "damaged"
    if ((Invoke-ExpectedCapsuleFailure -Path $capsulePath) -eq 0) { throw "Damaged APK run capsule was accepted." }

    $gitRoot = Join-Path $temp 'neutral-source'; New-Item -ItemType Directory -Path $gitRoot | Out-Null
    $null = & git -C $gitRoot init -q
    [IO.File]::WriteAllText((Join-Path $gitRoot 'source.txt'),'neutral source')
    $null = & git -C $gitRoot add source.txt
    $null = & git -C $gitRoot -c user.name=Fixture -c user.email=fixture@example.invalid commit -q -m fixture
    if ($LASTEXITCODE -ne 0) { throw 'Neutral source fixture Git commit failed.' }
    $profilePath = $files['runtime-profile.json']
    [ordered]@{schema='rusty.quest.development_feedback.runtime_profile.v1';app_lane='development-feedback-android';owner_package='io.github.mesmerprism.rustyquest.development_feedback';property_policy='none';properties=@()} | ConvertTo-Json | Set-Content -LiteralPath $profilePath
    $neutralPath = Join-Path $temp 'neutral-capsule.json'
    $newArguments = @{CapsulePath=$neutralPath;RepoRoot=$gitRoot;CapsuleId='neutral-fixture';BuildLockPath=$files['build-lock.json'];BuildManifestPath=$files['build-manifest.json'];ApkPath=$files['test.apk'];RuntimeProfilePath=$profilePath}
    $neutral = New-QuestRunCapsule @newArguments
    if ($neutral.property_manifest -ne $null -or $neutral.source.packages[0] -cne $neutral.android.package_name) { throw 'Neutral source/property identity was fabricated.' }
    $null = Read-QuestRunCapsule -Path $neutralPath -ExpectedLane development-feedback-android
    $null = & pwsh -NoProfile -File (Join-Path $repo 'tools/Test-ApkRunCapsule.ps1') -Path $neutralPath -ExpectedLane development-feedback-android
    if ($LASTEXITCODE -ne 0) { throw 'Neutral capsule facade rejected real observed Git/file fixture.' }
    function Refuses-Neutral([scriptblock]$Action) { $denied=$false; try { & $Action | Out-Null } catch { $denied=$true }; if (-not $denied) { throw 'Neutral capsule accepted a damaged input.' } }
    $originalCapsule = [IO.File]::ReadAllBytes($neutralPath)
    Refuses-Neutral { New-QuestRunCapsule @newArguments }
    if ((Get-Sha $neutralPath) -cne (Get-StringSha ([Text.Encoding]::UTF8.GetString($originalCapsule)))) { throw 'Create-new capsule was overwritten.' }
    foreach ($field in @('build_lock','build_manifest','apk','runtime_profile')) {
        $path = $neutral.$field.path; $original = [IO.File]::ReadAllBytes($path)
        try { Add-Content -LiteralPath $path -Value damaged; Refuses-Neutral { Read-QuestRunCapsule -Path $neutralPath } }
        finally { [IO.File]::WriteAllBytes($path,$original) }
    }
    try { Add-Content -LiteralPath (Join-Path $gitRoot 'source.txt') -Value drift; Refuses-Neutral { Read-QuestRunCapsule -Path $neutralPath } }
    finally { [IO.File]::WriteAllText((Join-Path $gitRoot 'source.txt'),'neutral source') }
    foreach ($damage in @('lane','package','unknown','source')) {
        $changed = Get-Content -LiteralPath $neutralPath -Raw | ConvertFrom-Json
        switch ($damage) {
            lane { $changed.app_lane='native-renderer-android' }
            package { $changed.android.package_name='io.github.mesmerprism.rustyquest.other' }
            unknown { $changed | Add-Member -NotePropertyName unknown -NotePropertyValue $true }
            source { $changed.source.tree='0'*40 }
        }
        try { $changed | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $neutralPath; Refuses-Neutral { Read-QuestRunCapsule -Path $neutralPath -ExpectedLane development-feedback-android } }
        finally { [IO.File]::WriteAllBytes($neutralPath,$originalCapsule) }
        if ($damage -eq 'lane') {
            try {
                $changed | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $neutralPath
                $null = & pwsh -NoProfile -File (Join-Path $repo 'tools/Test-ApkRunCapsule.ps1') -Path $neutralPath *> $null
                if ($LASTEXITCODE -eq 0) { throw 'Neutral identity escaped strict admission through a legacy lane.' }
            } finally { [IO.File]::WriteAllBytes($neutralPath,$originalCapsule) }
        }
    }
    $savedProfile = [IO.File]::ReadAllBytes($profilePath)
    try {
        $changedProfile = Get-Content -LiteralPath $profilePath -Raw | ConvertFrom-Json
        $changedProfile.owner_package='io.github.mesmerprism.rustyquest.other'
        $changedProfile | ConvertTo-Json | Set-Content -LiteralPath $profilePath
        $newArguments.CapsulePath=Join-Path $temp 'wrong-owner-capsule.json'
        Refuses-Neutral { New-QuestRunCapsule @newArguments }
        if (Test-Path -LiteralPath $newArguments.CapsulePath) { throw 'Invalid none declaration was published.' }
    } finally { [IO.File]::WriteAllBytes($profilePath,$savedProfile) }
} finally {
    if (-not $EvidenceRoot -and (Test-Path -LiteralPath $temp)) { Remove-Item -LiteralPath $temp -Recurse -Force }
    if ($EvidenceRoot) { Write-Host "Capsule fixture evidence: $temp" }
}

Write-Host "Rusty Quest APK run isolation static validation passed"
