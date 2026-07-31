[CmdletBinding()]
param([string]$RepoRoot = "")

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}
$path = Join-Path $RepoRoot `
    ".github\workflows\package-updater-labs-release.yml"
$workflow = Get-Content -Raw -LiteralPath $path
foreach ($token in @(
    'package-updater-v0\.1\.0-alpha\.\*',
    'runs-on: windows-2025',
    'timeout-minutes: 60',
    'environment: package-updater-labs-release',
    'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7\.0\.1',
    'actions/setup-java@03ad4de0992f5dab5e18fcb136590ce7c4a0ac95 # v5\.6\.0',
    'gradle/actions/setup-gradle@748248ddd2a24f49513d8f472f81c3a07d4d50e1 # v4\.4\.4',
    'rustc 1\.96\.0 \(ac68faa20 2026-05-25\)',
    'java-version: 17\.0\.14\+7',
    'ANDROID_BUILD_TOOLS_VERSION: 35\.0\.1',
    'ANDROID_NDK_VERSION: 27\.2\.12479018',
    'platforms;android-34',
    'GRADLE_VERSION: 8\.13',
    'git show-ref --verify --quiet "refs/tags/\$tag"',
    'git rev-parse "refs/tags/\$tag\^\{\}"',
    '\$peeled -ne "\$env:GITHUB_SHA"',
    '\$tag = \$env:GITHUB_REF_NAME',
    'git fetch --no-tags origin',
    '\+refs/heads/main:refs/remotes/origin/main',
    'git merge-base --is-ancestor',
    '\$env:GITHUB_SHA refs/remotes/origin/main',
    'Release tag source is not reachable from protected main',
    '\$env:GITHUB_REPOSITORY -cne "MesmerPrism/rusty-quest"',
    'Release workflow repository identity differs',
    'PACKAGE_UPDATER_KEYSTORE_BASE64',
    'PACKAGE_UPDATER_LABS_MANIFEST_URL',
    'PACKAGE_UPDATER_LABS_EXPECTED_SITE_BASE_PATH',
    'PACKAGE_UPDATER_LABS_EXPECTED_UPDATER_SIGNER_SHA256',
    '-ExpectedUpdaterSignerSha256 \$env:UPDATE_UPDATER_SIGNER',
    '-ExpectedSiteBasePath \$env:UPDATE_SITE_BASE_PATH',
    '-VersionCode \$alphaSequence',
    '-VersionName \$releaseVersion',
    'New-PackageUpdaterProductReleaseMetadata\.ps1',
    'Test-PackageUpdaterProductReleaseMetadata\.ps1',
    'StatusCode -ne 404',
    'GitHub release lookup failed with HTTP',
    'Authoritative remote tag does not peel to GITHUB_SHA',
    'draft = \$true',
    'prerelease = \$true',
    'make_latest = "false"',
    'RELEASE_APK_NAME: rusty-quest-package-updater\.apk',
    'RELEASE_METADATA_NAME: rusty-quest-package-updater\.release\.json',
    'RELEASE_LICENSE_NAME: RUSTY-QUEST-PACKAGE-UPDATER-LICENSE\.txt',
    'RELEASE_SOURCE_NAME: RUSTY-QUEST-PACKAGE-UPDATER-SOURCE\.txt',
    '\$Release\.target_commitish -cne \$env:GITHUB_SHA',
    '\$Release\.url -cne \$expectedReleaseUrl',
    '\$Release\.assets_url -cne \$expectedAssetsUrl',
    '\$Release\.upload_url -cne \$expectedUploadUrl',
    'untagged-\[0-9a-f\]\{20\}',
    '\[int64\]\$remote\[0\]\.id -le 0',
    '\$remote\[0\]\.browser_download_url -cne \$expectedUrl',
    '\$draftReadbackIdentity\.ReleaseId -ne \$draftIdentity\.ReleaseId',
    '\$promotedIdentity\.ReleaseId -ne \$draftIdentity\.ReleaseId',
    '\$publishedIdentity\.ReleaseId -ne \$draftIdentity\.ReleaseId',
    'Release asset IDs or content drifted across promotion',
    'draft = \$false',
    'Package Updater Labs became the latest release'
)) {
    if ($workflow -notmatch $token) {
        throw "Package Updater Labs workflow is missing contract token: $token"
    }
}
foreach ($forbidden in @(
    'workflow_dispatch', 'pull_request', 'release:\s+types',
    'actions/(?:checkout|setup-java)@v[0-9]',
    'gradle/actions/setup-gradle@v[0-9]',
    'gh release upload', '--clobber', '-Method Delete',
    'deleteAsset', 'make_latest = "true"', 'latest = \$true',
    'actions/upload-artifact', 'InspectE2eApkPath'
)) {
    if ($workflow -match $forbidden) {
        throw "Package Updater Labs workflow contains forbidden route: $forbidden"
    }
}
$workflowLines = Get-Content -LiteralPath $path
for ($lineIndex = 0; $lineIndex -lt $workflowLines.Count; $lineIndex++) {
    if ($workflowLines[$lineIndex] -notmatch
        '^(?<indent>\s*)run:\s*(?<value>.*)$') {
        continue
    }
    $runValue = $Matches['value']
    $runIndent = $Matches['indent'].Length
    if ($runValue.Contains('${{')) {
        throw 'Package Updater Labs workflow interpolates an expression in a run command.'
    }
    if ($runValue -notmatch '^[|>](?:[+-]?[1-9]?|[1-9]?[+-]?)?\s*(?:#.*)?$') {
        continue
    }
    for ($bodyIndex = $lineIndex + 1; $bodyIndex -lt $workflowLines.Count; $bodyIndex++) {
        $bodyLine = $workflowLines[$bodyIndex]
        if (-not [string]::IsNullOrWhiteSpace($bodyLine)) {
            $bodyIndent = $bodyLine.Length - $bodyLine.TrimStart().Length
            if ($bodyIndent -le $runIndent) { break }
        }
        if ($bodyLine.Contains('${{')) {
            throw 'Package Updater Labs workflow interpolates an expression in a run block.'
        }
    }
}
$tagChecks = @(
    [regex]::Matches($workflow, '(?m)^\s*Assert-RemoteTag\s*$')
)
$nonLatestChecks = @(
    [regex]::Matches($workflow, '(?m)^\s*Assert-LabsIsNotLatest\s*$')
)
$draftIndex = $workflow.IndexOf(
    '$release = Invoke-RestMethod -Method Post',
    [StringComparison]::Ordinal
)
$assetVerificationIndex = $workflow.IndexOf(
    '$draftAssets = @(Assert-ReleaseAssets',
    [StringComparison]::Ordinal
)
$promotionIndex = $workflow.IndexOf(
    '$promoted = Invoke-RestMethod -Method Patch',
    [StringComparison]::Ordinal
)
$publishedReadbackIndex = $workflow.IndexOf(
    '$published = Invoke-RestMethod -Uri "$api/releases/tags/$tag"',
    [StringComparison]::Ordinal
)
$publishedAssetVerificationIndex = $workflow.IndexOf(
    '$liveAssets = @(Assert-ReleaseAssets',
    [StringComparison]::Ordinal
)
if ($tagChecks.Count -ne 3 -or $nonLatestChecks.Count -ne 1 -or
    $draftIndex -lt 0 -or $assetVerificationIndex -lt 0 -or
    $promotionIndex -lt 0 -or $publishedReadbackIndex -lt 0 -or
    $publishedAssetVerificationIndex -lt 0 -or
    -not (
        $tagChecks[0].Index -lt $draftIndex -and
        $draftIndex -lt $assetVerificationIndex -and
        $assetVerificationIndex -lt $tagChecks[1].Index -and
        $tagChecks[1].Index -lt $promotionIndex -and
        $promotionIndex -lt $publishedReadbackIndex -and
        $publishedReadbackIndex -lt $publishedAssetVerificationIndex -and
        $publishedAssetVerificationIndex -lt $tagChecks[2].Index -and
        $tagChecks[2].Index -lt $nonLatestChecks[0].Index
    )) {
    throw "Package Updater release evidence and promotion ordering changed."
}
Write-Output "Package Updater Labs release workflow contract passed."
