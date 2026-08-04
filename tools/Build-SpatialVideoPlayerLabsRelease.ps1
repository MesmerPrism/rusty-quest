[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateRange(1, 2100000000)]
    [int]$VersionCode,
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$')]
    [string]$VersionName,
    [Parameter(Mandatory=$true)]
    [string]$Keystore,
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$ExpectedSignerSha256,
    [Parameter(Mandatory=$true)]
    [string]$ManifoldSourceRoot,
    [Parameter(Mandatory=$true)]
    [string]$OutDir,
    [Parameter(Mandatory=$true)]
    [string]$GradlePath,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$appRoot = Join-Path $repoRoot 'apps\spatial-video-control-example-android'
$keystorePath = (Resolve-Path -LiteralPath $Keystore).Path
$manifoldRoot = (Resolve-Path -LiteralPath $ManifoldSourceRoot).Path
$gradlePath = (Resolve-Path -LiteralPath $GradlePath).Path
$out = [IO.Path]::GetFullPath($OutDir)

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-Json([string]$Path, $Value) {
    [IO.File]::WriteAllText(
        $Path,
        ($Value | ConvertTo-Json -Depth 20),
        [Text.UTF8Encoding]::new($false))
}

$dirty = @(& git -C $repoRoot status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $dirty.Count -ne 0) {
    throw 'Spatial Video Player Labs release requires a clean exact Rusty Quest worktree.'
}
$manifoldDirty = @(& git -C $manifoldRoot status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $manifoldDirty.Count -ne 0) {
    throw 'Spatial Video Player Labs release requires the exact clean pinned Manifold source.'
}
if (Test-Path -LiteralPath $out) {
    if (-not (Test-Path -LiteralPath $out -PathType Container) -or
        @(Get-ChildItem -LiteralPath $out -Force).Count -ne 0) {
        throw 'Release output directory must be absent or empty.'
    }
} else {
    New-Item -ItemType Directory -Path $out | Out-Null
}

$sourceRevision = (& git -C $repoRoot rev-parse HEAD).Trim()
$sourceTree = (& git -C $repoRoot rev-parse 'HEAD^{tree}').Trim()
$manifoldRevision = (& git -C $manifoldRoot rev-parse HEAD).Trim()
$manifoldTree = (& git -C $manifoldRoot rev-parse 'HEAD^{tree}').Trim()
$lockPath = Join-Path $appRoot 'native\manifold-source.lock.json'
$lock = Get-Content -Raw -LiteralPath $lockPath | ConvertFrom-Json
if ([string]$lock.revision -cne $manifoldRevision -or [string]$lock.tree -cne $manifoldTree) {
    throw 'Manifold source does not match the Spatial Video Player native lock.'
}

$env:RUSTY_MANIFOLD_SOURCE_ROOT = $manifoldRoot
$env:RUSTY_SPATIAL_VIDEO_RELEASE_KEYSTORE = $keystorePath
$env:RUSTY_SPATIAL_VIDEO_VERSION_CODE = [string]$VersionCode
$env:RUSTY_SPATIAL_VIDEO_VERSION_NAME = $VersionName
if (-not [string]::IsNullOrWhiteSpace($AndroidHome)) { $env:ANDROID_HOME = $AndroidHome }
if (-not [string]::IsNullOrWhiteSpace($JavaHome)) { $env:JAVA_HOME = $JavaHome }
$buildRoot = Join-Path $out 'build'
$env:RUSTY_QUEST_SPATIAL_VIDEO_CONTROL_BUILD_ROOT = $buildRoot

$gradleVersion = @(& $gradlePath --version 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0 -or $gradleVersion -notmatch 'Gradle 8\.13') {
    throw 'Spatial Video Player Labs release requires exact Gradle 8.13.'
}
Push-Location $appRoot
try {
    & $gradlePath '--no-daemon' '--console=plain' `
        ':app:testConnectionHubDebugSurfaceSource' ':app:lintRelease' ':app:assembleRelease'
    if ($LASTEXITCODE -ne 0) { throw 'Spatial Video Player release build failed.' }
} finally {
    Pop-Location
}

$builtApk = Join-Path $buildRoot 'app\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $builtApk -PathType Leaf)) {
    throw 'Release APK is missing.'
}
$buildTools = Get-ChildItem -LiteralPath (Join-Path $AndroidHome 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if ($null -eq $buildTools) { throw 'Android build tools are unavailable.' }
$aapt2 = Join-Path $buildTools.FullName 'aapt2.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$badging = @(& $aapt2 dump badging $builtApk 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'aapt2 rejected the release APK.' }
$manifest = @(& $aapt2 dump xmltree $builtApk --file AndroidManifest.xml 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'aapt2 could not inspect the release manifest.' }
$certs = @(& $apksigner verify --print-certs $builtApk 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'apksigner rejected the release APK.' }
$badgingText = $badging -join "`n"
$manifestText = $manifest -join "`n"
$certText = $certs -join "`n"
if ($badgingText -notmatch "package: name='io\.github\.mesmerprism\.rustyquest\.spatial_video_control_example' versionCode='$VersionCode' versionName='$([regex]::Escape($VersionName))'") {
    throw 'Built APK identity/version does not match the requested release.'
}
if ($manifestText -match 'DebugShellControlProvider|ConnectionHubDebugSurfaceService|debug-local-control|START_CONNECTION_HUB_DEBUG_SURFACE') {
    throw 'Release manifest contains a debug operator surface.'
}
$normalizedCert = ($certText -replace '[^0-9A-Fa-f]', '').ToLowerInvariant()
if (-not $normalizedCert.Contains($ExpectedSignerSha256)) {
    throw 'Release signer does not match ExpectedSignerSha256.'
}

$artifactName = "rusty-spatial-video-player-$VersionName.apk"
$artifactPath = Join-Path $out $artifactName
Copy-Item -LiteralPath $builtApk -Destination $artifactPath
$artifactSha = Get-Sha256 $artifactPath
$licensePath = Join-Path $out 'LICENSE'
$noticesPath = Join-Path $out 'THIRD_PARTY_NOTICES.md'
$onboardingPath = Join-Path $out 'SPATIAL_VIDEO_PLAYER_ONBOARDING.md'
$sourceNoticePath = Join-Path $out 'SOURCE-NOTICE.md'
Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination $licensePath
Copy-Item -LiteralPath (Join-Path $appRoot 'THIRD_PARTY_NOTICES.md') -Destination $noticesPath
Copy-Item -LiteralPath (Join-Path $repoRoot 'docs\SPATIAL_VIDEO_PLAYER.md') -Destination $onboardingPath
$sourceNotice = @"
# Rusty Spatial Video Player source notice

This binary was built from Rusty Quest commit $sourceRevision (tree $sourceTree).

Source: https://github.com/MesmerPrism/rusty-quest/tree/$sourceRevision/apps/spatial-video-control-example-android

The pinned Manifold native authority source is commit $manifoldRevision (tree $manifoldTree).

The APK is media-free. User videos are not part of this release and are never uploaded by the player.
"@
[IO.File]::WriteAllText($sourceNoticePath, $sourceNotice, [Text.UTF8Encoding]::new($false))
$manifestValue = [ordered]@{
    '$schema' = 'rusty.quest.spatial_video_player_labs_release.v1'
    product = 'Rusty Spatial Video Player'
    release_tag = "spatial-video-player-v$VersionName"
    channel = 'labs'
    maturity = 'alpha'
    package_name = 'io.github.mesmerprism.rustyquest.spatial_video_control_example'
    version_code = $VersionCode
    version_name = $VersionName
    source_revision = $sourceRevision
    source_tree = $sourceTree
    source_url = "https://github.com/MesmerPrism/rusty-quest/tree/$sourceRevision/apps/spatial-video-control-example-android"
    manifold_source_revision = $manifoldRevision
    manifold_source_tree = $manifoldTree
    signer_sha256 = $ExpectedSignerSha256
    artifact_name = $artifactName
    artifact_sha256 = $artifactSha
    artifact_size = (Get-Item -LiteralPath $artifactPath).Length
    onboarding_sha256 = Get-Sha256 $onboardingPath
    source_notice_sha256 = Get-Sha256 $sourceNoticePath
    third_party_notices_sha256 = Get-Sha256 $noticesPath
    license_sha256 = Get-Sha256 $licensePath
    debug_operator_absent = $true
    media_bundled = $false
    user_media_authority = 'persisted-saf-document-tree'
    user_media_taxonomy = 'RustySpatialMedia/plain-videos/<shape>/<stereo>'
    connection_hub_surface = 'surface.spatial_video_control.media'
    standalone_websocket_listener_default = 'stopped'
    connection_hub_required_for_remote_control = $true
    trusted_lan_plaintext_option_owned_by_connection_hub = $true
}
$manifestPath = Join-Path $out 'spatial-video-player-release-manifest.json'
Write-Json $manifestPath $manifestValue
Write-Output ($manifestValue | ConvertTo-Json -Depth 20 -Compress)
