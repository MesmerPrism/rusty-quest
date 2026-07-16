param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..") }
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
Import-Module (Join-Path $repo "tools\lib\SourceComposition.psm1") -Force

function Assert-Contains { param([string]$Label,[string]$Text,[string]$Needle) if (-not $Text.Contains($Needle)) { throw "$Label is missing isolation guardrail: $Needle" } }
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
$sourceCompositionModule = Get-Content -LiteralPath (Join-Path $repo "tools\lib\SourceComposition.psm1") -Raw

foreach ($needle in @("-AppBuildLock is required", "Locked native APK build rejected undeclared ambient feature inputs", "content-addressed-app-lock-source-composition", "generated-native-renderer.broker-media-client.feature.lock.json", "embedded_manifold_app_feature_lock_sha256", "run-capsule.json")) { Assert-Contains "native build" $nativeBuild $needle }
foreach ($needle in @("-AppId is required so each Spatial project has a distinct Android identity", "RUSTY_QUEST_SPATIAL_BUILD_ROOT", "content-addressed-explicit-input-lock", "ambient_spatial_feature_environment_ignored", "`$propertyScanRoots", "spatial-property-manifest.json", "complete-source-consumer-surface", "run-capsule.json")) { Assert-Contains "Spatial build" $spatialBuild $needle }
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
foreach ($needle in @("target\apk-r", "Get-QuestRunCapsuleInstallApk", "Local\RustyMorphospaceQuestRun-", "property_snapshot", "Clear-QuestRunIsolationProperties", "complete_property_clear", "Exit-QuestRunIsolation", "force-stop", "property_restore")) { Assert-Contains "run isolation module" $isolationModule $needle }
foreach ($text in @($nativeSmoke, $spatialSmoke)) { Assert-Contains "smoke wrapper short APK staging" $text "Get-QuestRunCapsuleInstallApk" }
foreach ($needle in @("cargo metadata --format-version 1 --locked", "path-dependency", "tracked_worktree_clean", "rusty.quest.apk_source_composition_identity.v1")) { Assert-Contains "source composition module" $sourceCompositionModule $needle }
foreach ($text in @($nativeBuild, $spatialBuild)) {
    Assert-Contains "APK builder" $text "Get-QuestBuildSourceComposition"
    Assert-Contains "APK builder" $text "source_composition_fingerprint"
    Assert-Contains "APK builder" $text "source_dependencies"
    Assert-Contains "APK builder" $text '"--locked"'
    Assert-Contains "APK builder" $text "apk-i"
}

$temp = Join-Path ([IO.Path]::GetTempPath()) ("rusty-quest-run-capsule-test-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $temp | Out-Null
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
    $capsule.source.composition_fingerprint = "0" * 64
    $capsule | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $capsulePath -Encoding UTF8
    if ((Invoke-ExpectedCapsuleFailure -Path $capsulePath) -eq 0) { throw "Damaged source-composition fingerprint was accepted." }
    $capsule.source.composition_fingerprint = $compositionFingerprint
    $capsule | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $capsulePath -Encoding UTF8
    Add-Content -LiteralPath $files["test.apk"] -Value "damaged"
    if ((Invoke-ExpectedCapsuleFailure -Path $capsulePath) -eq 0) { throw "Damaged APK run capsule was accepted." }
} finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
}

Write-Host "Rusty Quest APK run isolation static validation passed"
