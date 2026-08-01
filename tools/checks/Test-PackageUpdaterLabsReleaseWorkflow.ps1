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
    'working-directory: rusty-quest',
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
    'Require fresh independent release-immutability attestation',
    'PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_HMAC_BASE64',
    'PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_KEY_ID',
    'PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_RELEASE_TAG',
    'PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_SOURCE_SHA',
    'PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_OBSERVED_AT_MS',
    'PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_EXPIRES_AT_MS',
    'PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_HMAC_SHA256',
    'rusty\.quest\.release_immutability_attestation\.v1',
    'enabled=true',
    'TimeSpan\]::FromMinutes\(30\)',
    'TimeSpan\]::FromHours\(2\)',
    'SETTINGS_ATTESTATION_RELEASE_TAG -cne \$env:GITHUB_REF_NAME',
    'SETTINGS_ATTESTATION_SOURCE_SHA -cne \$env:GITHUB_SHA',
    'CryptographicOperations\]::FixedTimeEquals',
    'Remove-Item Env:\\SETTINGS_ATTESTATION_HMAC_BASE64',
    'PACKAGE_UPDATER_KEYSTORE_BASE64',
    'PACKAGE_UPDATER_LABS_MANIFEST_URL',
    'https://mesmerprism\.com/rusty-quest/package-updates/',
    'Package Updater Labs release origin is not the canonical direct endpoint',
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
    '\$Release\.immutable -ne \(-not \$ExpectedDraft\)',
    'X-GitHub-Api-Version" = "2026-03-10"',
    '\$Release\.url -cne \$expectedReleaseUrl',
    '\$Release\.assets_url -cne \$expectedAssetsUrl',
    '\$Release\.upload_url -cne \$expectedUploadUrl',
    'untagged-\[0-9a-f\]\{20\}',
    '\[int64\]\$remote\[0\]\.id -le 0',
    '\$remote\[0\]\.browser_download_url -cne \$expectedUrl',
    '\$draftReadbackIdentity\.ReleaseId -ne \$draftIdentity\.ReleaseId',
    '\$promotedIdentity\.ReleaseId -ne \$draftIdentity\.ReleaseId',
    '\$publishedIdentity\.ReleaseId -ne \$draftIdentity\.ReleaseId',
    '\[object\[\]\]\$remoteAssets = Invoke-RestMethod',
    '\[object\[\]\]\$publishedAssets = Invoke-RestMethod',
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
$typedAssetReads = @([regex]::Matches(
    $workflow,
    '(?m)^\s*\[object\[\]\]\$(?:remoteAssets|publishedAssets) = Invoke-RestMethod `\s*$'
))
if ($typedAssetReads.Count -ne 2 -or $workflow -match
    '(?ms)\$\w*Assets\s*=\s*@\(\s*Invoke-RestMethod\s+-Uri\s+"\$api/releases/') {
    throw 'Release asset reads must flatten the REST array exactly once.'
}
$checkoutUses = @([regex]::Matches(
    $workflow,
    '(?m)^\s*uses:\s*actions/checkout@[^\s]+(?:\s+#.*)?$'
))
$pinnedCheckoutUses = @([regex]::Matches(
    $workflow,
    '(?m)^\s*uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7\.0\.1\s*$'
))
if ($checkoutUses.Count -ne 5 -or $pinnedCheckoutUses.Count -ne 5) {
    throw 'Package Updater Labs workflow checkout closure is not exact.'
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
$workspaceDependencies = @(
    [pscustomobject]@{
        Name = 'Manifold'
        Path = 'rusty-manifold'
        Ref = '947421a928889889e485006bcc0200e05c2394f9'
        Repository = 'MesmerPrism/rusty-manifold'
    }
    [pscustomobject]@{
        Name = 'Lattice'
        Path = 'rusty-lattice'
        Ref = '0aee7faa52fc965ff2255381781dd082ab639f4b'
        Repository = 'MesmerPrism/rusty-lattice'
    }
    [pscustomobject]@{
        Name = 'Matter'
        Path = 'rusty-matter'
        Ref = 'eec8cddd9830f7ef0f90574ddcbde2daac0ec804'
        Repository = 'MesmerPrism/rusty-matter'
    }
    [pscustomobject]@{
        Name = 'Optics'
        Path = 'rusty-optics'
        Ref = 'fd01d84acffa1b0a3a192fe978af337d9fedd18a'
        Repository = 'MesmerPrism/rusty-optics'
    }
)
function Get-ExactWorkflowStep(
    [string]$Name,
    [string]$WorkflowText
) {
    $header = "      - name: $Name"
    $headers = @([regex]::Matches(
        $WorkflowText,
        '(?m)^' + [regex]::Escape($header) + '\r?$'
    ))
    if ($headers.Count -ne 1) {
        throw "Package Updater Labs workflow lacks one named step: $Name"
    }
    $start = $headers[0].Index
    $next = $WorkflowText.IndexOf(
        "`n      - name:",
        $start + $headers[0].Length,
        [StringComparison]::Ordinal
    )
    if ($next -lt 0) { $next = $WorkflowText.Length }
    [pscustomobject]@{
        Index = $start
        Block = $WorkflowText.Substring($start, $next - $start).
            Replace("`r`n", "`n").TrimEnd()
    }
}
function Assert-ExactWorkflowStep(
    [string]$Name,
    [string[]]$ExpectedLines,
    [string]$WorkflowText
) {
    $step = Get-ExactWorkflowStep -Name $Name -WorkflowText $WorkflowText
    $expected = ($ExpectedLines -join "`n").TrimEnd()
    if ($step.Block -cne $expected) {
        throw "Package Updater Labs workflow step differs: $Name"
    }
    $step
}
function Assert-WorkspaceTopology([string]$WorkflowText) {
    if (@([regex]::Matches(
        $WorkflowText,
        '(?m)^  release:\r?$\n' +
            '^    runs-on: windows-2025\r?$\n' +
            '^    timeout-minutes: 60\r?$\n' +
            '^    environment: package-updater-labs-release\r?$\n' +
            '^    defaults:\r?$\n' +
            '^      run:\r?$\n' +
            '^        working-directory: rusty-quest\r?$\n' +
            '^    env:\r?$'
        )).Count -ne 1) {
        throw 'Package Updater Labs release job lacks the exact nested workspace layout.'
    }
    $tagCheckout = Assert-ExactWorkflowStep `
        -Name 'Checkout exact tag target' `
        -WorkflowText $WorkflowText `
        -ExpectedLines @(
            '      - name: Checkout exact tag target'
            '        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1'
            '        with:'
            '          fetch-depth: 0'
            '          fetch-tags: true'
            '          lfs: false'
            '          path: rusty-quest'
            '          persist-credentials: false'
            '          ref: ${{ github.sha }}'
            '          submodules: false'
        )
    $javaSetupIndex = $WorkflowText.IndexOf(
        '- name: Set up exact Java',
        [StringComparison]::Ordinal
    )
    $lastDependencyIndex = $tagCheckout.Index
    foreach ($dependency in $workspaceDependencies) {
        $name = "Checkout exact $($dependency.Name) workspace dependency"
        $step = Assert-ExactWorkflowStep -Name $name `
            -WorkflowText $WorkflowText -ExpectedLines @(
                "      - name: $name"
                '        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1'
                '        with:'
                '          fetch-depth: 1'
                '          lfs: false'
                "          path: $($dependency.Path)"
                '          persist-credentials: false'
                "          ref: $($dependency.Ref)"
                "          repository: $($dependency.Repository)"
                '          submodules: false'
            )
        if ($step.Index -le $lastDependencyIndex -or
            $step.Index -ge $javaSetupIndex) {
            throw "Package Updater Labs workspace checkout ordering changed for $($dependency.Name)."
        }
        $lastDependencyIndex = $step.Index
    }
}
Assert-WorkspaceTopology -WorkflowText $workflow
foreach ($mutation in @(
    [pscustomobject]@{
        Name = 'missing nested source path'
        Pattern = '(?m)^          path: rusty-quest\r?$'
        Replacement = '          path: nested/rusty-quest'
    }
    [pscustomobject]@{
        Name = 'missing nested run root'
        Pattern = '(?m)^        working-directory: rusty-quest\r?$'
        Replacement = '        working-directory: .'
    }
    [pscustomobject]@{
        Name = 'nested dependency path'
        Pattern = '(?m)^          path: rusty-manifold\r?$'
        Replacement = '          path: rusty-quest/rusty-manifold'
    }
    [pscustomobject]@{
        Name = 'dependency field outside its step'
        Pattern = '(?m)^          repository: MesmerPrism/rusty-manifold\r?$'
        Replacement = '          repository-moved: MesmerPrism/rusty-manifold'
    }
)) {
    $mutated = [regex]::Replace(
        $workflow, $mutation.Pattern, $mutation.Replacement
    )
    if ($mutated -ceq $workflow) {
        throw "Workspace topology self-test did not mutate: $($mutation.Name)"
    }
    $rejected = $false
    try {
        Assert-WorkspaceTopology -WorkflowText $mutated
    } catch {
        $rejected = $true
    }
    if (-not $rejected) {
        throw "Workspace topology self-test accepted: $($mutation.Name)"
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
$settingsAttestationIndex = $workflow.IndexOf(
    '- name: Require fresh independent release-immutability attestation',
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
    $settingsAttestationIndex -lt 0 -or $draftIndex -lt 0 -or
    $assetVerificationIndex -lt 0 -or
    $promotionIndex -lt 0 -or $publishedReadbackIndex -lt 0 -or
    $publishedAssetVerificationIndex -lt 0 -or
    -not (
        $settingsAttestationIndex -lt $draftIndex -and
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
