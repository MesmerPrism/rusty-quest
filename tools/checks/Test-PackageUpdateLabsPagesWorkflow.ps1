[CmdletBinding()]
param([string]$RepoRoot = "")

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}
$workflowPath = Join-Path $RepoRoot ".github\workflows\package-update-labs-pages.yml"
$publisherPath = Join-Path $RepoRoot "tools\Publish-PackageUpdateLabsPages.ps1"
$projectionPath = Join-Path $RepoRoot `
    "tools\package_updater\FeedRulesetProjection.ps1"
$secretCleanupPath = Join-Path $RepoRoot `
    "tools\package_updater\FeedWriterSecretCleanup.ps1"
foreach ($path in @(
    $workflowPath, $publisherPath, $projectionPath, $secretCleanupPath
)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Labs Pages publication surface is missing: $path"
    }
}
$workflow = Get-Content -Raw -LiteralPath $workflowPath
$publisher = Get-Content -Raw -LiteralPath $publisherPath
$projectionHelper = Get-Content -Raw -LiteralPath $projectionPath
$secretCleanupHelper = Get-Content -Raw -LiteralPath $secretCleanupPath
. $publisherPath -FeedRoot "unused" -TargetPath "unused" `
    -AndroidBuildToolsDirectory "unused" -TrustedKeyId "unused" `
    -TrustedPublicKeyBase64Url "unused" -LibraryOnly
. $projectionPath
. $secretCleanupPath

function Copy-JsonObject($Value) {
    $Value | ConvertTo-Json -Depth 16 -Compress | ConvertFrom-Json
}

function Assert-Rejected([scriptblock]$Action, [string]$Label) {
    try {
        & $Action
    } catch {
        return
    }
    throw "Damaged Labs Pages publisher contract was accepted: $Label"
}

function Get-WorkflowStepBlock([string]$WorkflowText, [string]$StepName) {
    $namePattern = '(?m)^(?<indent>[ ]*)- name: ' +
        [regex]::Escape($StepName) + '[ ]*\r?$'
    $matches = [regex]::Matches($WorkflowText, $namePattern)
    if ($matches.Count -ne 1) {
        throw "Workflow must contain exactly one step named: $StepName"
    }
    $match = $matches[0]
    $indent = [regex]::Escape($match.Groups['indent'].Value)
    $tail = $WorkflowText.Substring($match.Index + $match.Length)
    $next = [regex]::Match($tail, "(?m)^${indent}- name: ")
    $length = if ($next.Success) {
        $match.Length + $next.Index
    } else {
        $WorkflowText.Length - $match.Index
    }
    $WorkflowText.Substring($match.Index, $length).TrimEnd()
}

function Assert-PinnedDependencyCheckouts([string]$WorkflowText) {
    $dependencySpecs = @(
        [pscustomobject]@{
            Name = 'Manifold'
            Path = 'rusty-manifold'
            Repository = 'MesmerPrism/rusty-manifold'
            Ref = '947421a928889889e485006bcc0200e05c2394f9'
        }
        [pscustomobject]@{
            Name = 'Lattice'
            Path = 'rusty-lattice'
            Repository = 'MesmerPrism/rusty-lattice'
            Ref = '0aee7faa52fc965ff2255381781dd082ab639f4b'
        }
        [pscustomobject]@{
            Name = 'Matter'
            Path = 'rusty-matter'
            Repository = 'MesmerPrism/rusty-matter'
            Ref = 'eec8cddd9830f7ef0f90574ddcbde2daac0ec804'
        }
        [pscustomobject]@{
            Name = 'Optics'
            Path = 'rusty-optics'
            Repository = 'MesmerPrism/rusty-optics'
            Ref = 'fd01d84acffa1b0a3a192fe978af337d9fedd18a'
        }
    )
    foreach ($dependency in $dependencySpecs) {
        $actual = Get-WorkflowStepBlock $WorkflowText `
            "Checkout exact $($dependency.Name) workspace dependency"
        $expected = @"
      - name: Checkout exact $($dependency.Name) workspace dependency
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          fetch-depth: 1
          lfs: false
          path: $($dependency.Path)
          persist-credentials: false
          ref: $($dependency.Ref)
          repository: $($dependency.Repository)
          submodules: false
"@.TrimEnd()
        $actualNormalized = $actual -replace "\r\n?", "`n"
        $expectedNormalized = $expected -replace "\r\n?", "`n"
        if ($actualNormalized -cne $expectedNormalized) {
            throw "Labs Pages workflow has a damaged pinned $($dependency.Name) checkout."
        }
    }
}

function Assert-FeedWriterKeyLifecycle([string]$WorkflowText) {
    $writerStep = Get-WorkflowStepBlock $WorkflowText `
        'Push through the dedicated protected feed writer'
    $tokens = [ordered]@{
        key_write = '[IO.File]::WriteAllBytes($keyPath, $keyBytes)'
        key_acl = 'icacls.exe $keyPath /inheritance:r /grant:r "$currentIdentity`:(R,W)"'
        key_read = 'ssh-keygen.exe -y -P "" -f $keyPath'
        push = 'git -C feed push origin "HEAD:$env:FEED_BRANCH"'
        readback = 'git -C feed ls-remote --exit-code origin'
        post_push = 'Read-FeedRulesetProjection "post-push"'
        cleanup = 'Invoke-PackageUpdateLabsFeedWriterSecretCleanup'
    }
    $indexes = [ordered]@{}
    foreach ($entry in $tokens.GetEnumerator()) {
        if ([regex]::Matches(
                $writerStep, [regex]::Escape($entry.Value)
            ).Count -ne 1) {
            throw "Feed writer must contain one key lifecycle token: $($entry.Key)"
        }
        $indexes[$entry.Key] = $writerStep.IndexOf(
            $entry.Value, [StringComparison]::Ordinal
        )
    }
    if (-not ($indexes.key_write -lt $indexes.key_acl -and
        $indexes.key_acl -lt $indexes.key_read -and
        $indexes.key_read -lt $indexes.push -and
        $indexes.push -lt $indexes.readback -and
        $indexes.readback -lt $indexes.post_push -and
        $indexes.post_push -lt $indexes.cleanup)) {
        throw 'Feed writer key lifecycle no longer ends after the final SSH readback.'
    }
}

foreach ($token in @(
    'cron: "17 \*/6 \* \* \*"',
    'workflow_dispatch:',
    'cancel-in-progress: false',
    'timeout-minutes: 45',
    'timeout-minutes: 15',
    'environment: package-update-labs-publication',
    'environment:\s+name: github-pages',
    'contents: read',
    'pages: write',
    'id-token: write',
    'persist-credentials: false',
    'path: source',
    'working-directory: source',
    'Checkout exact Manifold workspace dependency',
    'Checkout exact Lattice workspace dependency',
    'Checkout exact Matter workspace dependency',
    'Checkout exact Optics workspace dependency',
    'refs/heads/main',
    'package-update-labs-feed',
    'Publish-PackageUpdateLabsPages\.ps1',
    'package-update-labs-target\.json',
    'Require protected continuous feed authority',
    'PACKAGE_UPDATE_LABS_FEED_RULESET_ID',
    'PACKAGE_UPDATE_LABS_FEED_RULESET_UPDATED_AT_UTC',
    'PACKAGE_UPDATE_LABS_FEED_RULESET_FULL_POLICY_SHA256',
    'PACKAGE_UPDATE_LABS_FEED_DEPLOY_KEY_FINGERPRINT',
    'FeedRulesetProjection\.ps1',
    'FeedWriterSecretCleanup\.ps1',
    'rulesets/',
    '-Phase "pre-publication"',
    'Read-FeedRulesetProjection "pre-key-use"',
    'Read-FeedRulesetProjection "pre-push"',
    'Read-FeedRulesetProjection "post-push"',
    'Visible feed-ruleset projection drifted before the protected push',
    'Visible feed-ruleset projection drifted across the protected push',
    'refs/heads/package-update-labs-feed',
    'git -C feed ls-remote --exit-code origin',
    'PACKAGE_UPDATE_LABS_FEED_DEPLOY_KEY_BASE64',
    'Push through the dedicated protected feed writer',
    'git@github\.com:MesmerPrism/rusty-quest\.git',
    'StrictHostKeyChecking=yes',
    'IdentitiesOnly=yes',
    'AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl',
    'ssh-keygen\.exe -y -P "" -f',
    'ssh-keygen\.exe -lf \$keyPath -E sha256',
    'icacls\.exe \$keyPath /inheritance:r /grant:r "\$currentIdentity`:\(R,W\)"',
    'exact configured deploy key',
    'Invoke-PackageUpdateLabsFeedWriterSecretCleanup',
    '-PrimaryError \$primaryError',
    'git -C feed push origin "HEAD:\$env:FEED_BRANCH"',
    'Protected feed push did not read back the exact committed generation',
    'needs\.publish\.outputs\.feed_commit',
    'Feed commit contains content outside the Kiosk Labs subtree',
    'Feed commit contains an unexpected path or Git mode',
    'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1',
    'actions/setup-java@03ad4de0992f5dab5e18fcb136590ce7c4a0ac95',
    'rustc 1\.96\.0 \(ac68faa20 2026-05-25\)',
    'actions/configure-pages@983d7736d9b0ae728b81ab479565c72886d7745b',
    'actions/upload-pages-artifact@7b1f4a764d45c48632c6b24a0339c27f5614fb0b',
    'actions/deploy-pages@d6db90164ac5ed86f2b6aed7e0febac5b3c0c03e',
    '\.nojekyll`npackage-updates'
)) {
    if ($workflow -notmatch $token) {
        throw "Labs Pages workflow is missing contract token: $token"
    }
}
foreach ($token in @(
    'Remove-Item Env:\\GIT_SSH_COMMAND',
    'Remove-Item Env:\\FEED_DEPLOY_KEY_BASE64',
    '\[Array\]::Clear\(\$KeyBytes, 0, \$KeyBytes\.Length\)',
    'key_wipe_failed',
    'key_delete_failed',
    'known_hosts_delete_failed',
    'Write-Warning',
    'throw \$PrimaryError',
    'WriteAllBytes\(\$Path, \[byte\[\]\]::new\(\$Length\)\)'
)) {
    if ($secretCleanupHelper -notmatch $token) {
        throw "Feed writer secret cleanup helper lacks contract token: $token"
    }
}
foreach ($forbidden in @(
    'pull_request', 'pull_request_target', 'force-with-lease', '--force',
    'cancel-in-progress: true', 'CNAME', 'repository_dispatch',
    'contents: write', 'persist-credentials: true',
    'git -C feed push https?://',
    '"\$currentIdentity`:\(R\)"',
    'actions/(?:checkout|setup-java)@v[0-9]',
    'actions/(?:configure-pages|upload-pages-artifact|deploy-pages)@v[0-9]'
)) {
    if ($workflow -match $forbidden) {
        throw "Labs Pages workflow contains forbidden route: $forbidden"
    }
}
foreach ($token in @(
    'bypass_actor_closure = "externally-audited-not-projected"',
    'publisher_bypass_report = \$publisherBypassReport',
    'unexpectedly exposes bypass actors',
    'Protect Package Update Labs feed',
    'MesmerPrism/rusty-quest',
    'refs/heads/package-update-labs-feed',
    'creation", "deletion", "non_fast_forward", "update'
)) {
    if ($projectionHelper -notmatch $token) {
        throw "Feed ruleset projection helper is missing contract token: $token"
    }
}
$workflowLines = Get-Content -LiteralPath $workflowPath
for ($lineIndex = 0; $lineIndex -lt $workflowLines.Count; $lineIndex++) {
    if ($workflowLines[$lineIndex] -notmatch
        '^(?<indent>\s*)run:\s*(?<value>.*)$') {
        continue
    }
    $runValue = $Matches['value']
    $runIndent = $Matches['indent'].Length
    if ($runValue.Contains('${{')) {
        throw 'Labs Pages workflow interpolates an expression in a run command.'
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
            throw 'Labs Pages workflow interpolates an expression in a run block.'
        }
    }
}
$protectionIndex = $workflow.IndexOf(
    '- name: Require protected continuous feed authority',
    [StringComparison]::Ordinal
)
$publicationIndex = $workflow.IndexOf(
    '.\tools\Publish-PackageUpdateLabsPages.ps1',
    [StringComparison]::Ordinal
)
$dependencyIndexes = @(
    'Manifold', 'Lattice', 'Matter', 'Optics' | ForEach-Object {
        $workflow.IndexOf(
            "- name: Checkout exact $_ workspace dependency",
            [StringComparison]::Ordinal
        )
    }
)
$commitIndex = $workflow.IndexOf(
    '- name: Commit feed state without rewriting history',
    [StringComparison]::Ordinal
)
$secretIndex = $workflow.IndexOf(
    'FEED_DEPLOY_KEY_BASE64: ${{ secrets.PACKAGE_UPDATE_LABS_FEED_DEPLOY_KEY_BASE64 }}',
    [StringComparison]::Ordinal
)
$pushIndex = $workflow.IndexOf(
    'git -C feed push origin "HEAD:$env:FEED_BRANCH"',
    [StringComparison]::Ordinal
)
$preKeyProjectionIndex = $workflow.IndexOf(
    'Read-FeedRulesetProjection "pre-key-use"',
    [StringComparison]::Ordinal
)
$prePushProjectionIndex = $workflow.IndexOf(
    'Read-FeedRulesetProjection "pre-push"',
    [StringComparison]::Ordinal
)
$postPushProjectionIndex = $workflow.IndexOf(
    'Read-FeedRulesetProjection "post-push"',
    [StringComparison]::Ordinal
)
if ($protectionIndex -lt 0 -or $publicationIndex -lt 0 -or
    $commitIndex -lt 0 -or $secretIndex -lt 0 -or $pushIndex -lt 0 -or
    @($dependencyIndexes | Where-Object { $_ -lt 0 }).Count -ne 0 -or
    $preKeyProjectionIndex -lt 0 -or $prePushProjectionIndex -lt 0 -or
    $postPushProjectionIndex -lt 0 -or
    -not ($protectionIndex -lt $publicationIndex -and
        @($dependencyIndexes | Where-Object { $_ -ge $publicationIndex }).Count -eq 0 -and
        $publicationIndex -lt $commitIndex -and
        $commitIndex -lt $secretIndex -and $secretIndex -lt $pushIndex)) {
    throw 'Labs feed protection, publication, commit, secret, and push ordering changed.'
}
if (-not ($preKeyProjectionIndex -lt $prePushProjectionIndex -and
    $prePushProjectionIndex -lt $pushIndex -and
    $pushIndex -lt $postPushProjectionIndex)) {
    throw 'Labs feed projection checks do not bracket the protected push.'
}
Assert-FeedWriterKeyLifecycle $workflow
if (-not ($dependencyIndexes[0] -lt $dependencyIndexes[1] -and
    $dependencyIndexes[1] -lt $dependencyIndexes[2] -and
    $dependencyIndexes[2] -lt $dependencyIndexes[3])) {
    throw 'Pinned workspace dependency checkout ordering changed.'
}
$persistFalseCount = @(
    [regex]::Matches($workflow, '(?m)^\s*persist-credentials:\s*false\s*$')
).Count
if ($persistFalseCount -ne 7) {
    throw 'Every Labs feed checkout must disable persisted token credentials.'
}
Assert-PinnedDependencyCheckouts $workflow
$manifoldStep = Get-WorkflowStepBlock $workflow `
    'Checkout exact Manifold workspace dependency'
foreach ($damage in @(
    [pscustomobject]@{
        Label = 'terminal field borrowed from following step'
        Apply = { param($block) $block -replace '(?m)^\s*submodules: false\s*$', '' }
    }
    [pscustomobject]@{
        Label = 'mutable checkout action substitution'
        Apply = { param($block) $block.Replace(
            'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1',
            'actions/checkout@v7'
        ) }
    }
    [pscustomobject]@{
        Label = 'nested dependency checkout path'
        Apply = { param($block) $block.Replace(
            'path: rusty-manifold', 'path: source/rusty-manifold'
        ) }
    }
    [pscustomobject]@{
        Label = 'missing LFS boundary'
        Apply = { param($block) $block -replace '(?m)^\s*lfs: false\s*$', '' }
    }
)) {
    $damagedStep = & $damage.Apply $manifoldStep
    $damagedWorkflow = $workflow.Replace($manifoldStep, $damagedStep)
    Assert-Rejected {
        Assert-PinnedDependencyCheckouts $damagedWorkflow
    } $damage.Label
}

$writerStep = Get-WorkflowStepBlock $workflow `
    'Push through the dedicated protected feed writer'
$cleanupCall = 'Invoke-PackageUpdateLabsFeedWriterSecretCleanup'
$pushCall = 'git -C feed push origin "HEAD:$env:FEED_BRANCH"'
$earlyCleanupStep = $writerStep.Replace($cleanupCall, '')
$earlyCleanupStep = $earlyCleanupStep.Replace(
    $pushCall, "$cleanupCall`n            $pushCall"
)
$earlyCleanupWorkflow = $workflow.Replace($writerStep, $earlyCleanupStep)
Assert-Rejected {
    Assert-FeedWriterKeyLifecycle $earlyCleanupWorkflow
} 'key cleanup moved before protected push and SSH readback'

$cleanupEvents = [Collections.Generic.List[string]]::new()
$existingCleanupPaths = [Collections.Generic.HashSet[string]]::new(
    [StringComparer]::Ordinal
)
$fixtureKeyPath = 'fixture-key'
$fixtureKnownHostsPath = 'fixture-known-hosts'
$null = $existingCleanupPaths.Add($fixtureKeyPath)
$null = $existingCleanupPaths.Add($fixtureKnownHostsPath)
$existsAction = {
    param($Path) $existingCleanupPaths.Contains($Path)
}.GetNewClosure()
$lengthAction = { param($Path) 32 }
$wipeFailureAction = {
    param($Path, $Length)
    $cleanupEvents.Add("wipe:$Path")
    throw 'synthetic wipe failure'
}.GetNewClosure()
$deleteAction = {
    param($Path)
    $cleanupEvents.Add("delete:$Path")
    if (-not $existingCleanupPaths.Remove($Path)) {
        throw "Synthetic cleanup path was already absent: $Path"
    }
}.GetNewClosure()
$primaryFixture = $null
try {
    throw 'primary-operation-sentinel'
} catch {
    $primaryFixture = $_
}
$fixtureKeyBytes = [byte[]](1, 2, 3, 4)
$caughtPrimary = $null
$oldWarningPreference = $WarningPreference
try {
    $WarningPreference = 'SilentlyContinue'
    Invoke-PackageUpdateLabsFeedWriterSecretCleanup `
        -KeyBytes $fixtureKeyBytes -KeyPath $fixtureKeyPath `
        -KnownHostsPath $fixtureKnownHostsPath -PrimaryError $primaryFixture `
        -FileExists $existsAction -GetFileLength $lengthAction `
        -WriteZeros $wipeFailureAction -DeleteFile $deleteAction
} catch {
    $caughtPrimary = $_
} finally {
    $WarningPreference = $oldWarningPreference
}
if ($null -eq $caughtPrimary -or
    $caughtPrimary.Exception.Message -notmatch 'primary-operation-sentinel') {
    throw 'Cleanup failure did not preserve the primary publication error.'
}
if (($cleanupEvents -join ',') -cne
        'wipe:fixture-key,delete:fixture-key,delete:fixture-known-hosts' -or
    $existingCleanupPaths.Count -ne 0) {
    throw 'Cleanup did not continue through independent deletion attempts.'
}
if (@($fixtureKeyBytes | Where-Object { $_ -ne 0 }).Count -ne 0) {
    throw 'Cleanup did not clear the decoded key byte array first.'
}

$validRulesetProjection = [pscustomobject][ordered]@{
    id = 20137239
    name = "Protect Package Update Labs feed"
    target = "branch"
    source_type = "Repository"
    source = "MesmerPrism/rusty-quest"
    enforcement = "active"
    conditions = [pscustomobject][ordered]@{
        ref_name = [pscustomobject][ordered]@{
            exclude = @()
            include = @("refs/heads/package-update-labs-feed")
        }
    }
    rules = @(
        [pscustomobject]@{ type = "creation" }
        [pscustomobject]@{ type = "update" }
        [pscustomobject]@{ type = "deletion" }
        [pscustomobject]@{ type = "non_fast_forward" }
    )
    node_id = "RRS_fixture"
    created_at = "2026-07-31T17:00:00.000Z"
    updated_at = "2026-07-31T17:15:58.739Z"
    _links = [pscustomobject]@{}
}
$projectionReceipt = Assert-PackageUpdateLabsFeedRulesetProjection `
    -Ruleset $validRulesetProjection -ExpectedRulesetId 20137239 `
    -ExpectedUpdatedAtUtc "2026-07-31T17:15:58.7390000Z" `
    -ExternallyAuditedFullPolicySha256 ("ab" * 32) `
    -Phase "pre-publication"
if ($projectionReceipt.bypass_actor_closure -cne
        "externally-audited-not-projected" -or
    $projectionReceipt.publisher_bypass_report -cne "not-projected" -or
    $projectionReceipt.visible_projection_sha256 -cnotmatch "^[0-9a-f]{64}$") {
    throw "Valid feed-ruleset projection did not return its bounded receipt."
}
foreach ($mutation in @(
    [pscustomobject]@{ Name = "source drift"; Apply = {
        param($value) $value.source = "example/other"
    } },
    [pscustomobject]@{ Name = "ruleset revision drift"; Apply = {
        param($value) $value.updated_at = "2026-07-31T17:15:59.739Z"
    } },
    [pscustomobject]@{ Name = "extra visible rule"; Apply = {
        param($value) $value.rules += [pscustomobject]@{ type = "required_signatures" }
    } },
    [pscustomobject]@{ Name = "exposed bypass actors"; Apply = {
        param($value) $value | Add-Member -NotePropertyName bypass_actors `
            -NotePropertyValue @([pscustomobject]@{
                actor_id = $null; actor_type = "DeployKey"; bypass_mode = "always"
            })
    } },
    [pscustomobject]@{ Name = "publisher bypass authority"; Apply = {
        param($value) $value | Add-Member `
            -NotePropertyName current_user_can_bypass -NotePropertyValue "always"
    } }
)) {
    $damaged = Copy-JsonObject $validRulesetProjection
    & $mutation.Apply $damaged
    Assert-Rejected {
        Assert-PackageUpdateLabsFeedRulesetProjection `
            -Ruleset $damaged -ExpectedRulesetId 20137239 `
            -ExpectedUpdatedAtUtc "2026-07-31T17:15:58.7390000Z" `
            -ExternallyAuditedFullPolicySha256 ("ab" * 32) `
            -Phase "pre-publication" | Out-Null
    } $mutation.Name
}
Assert-Rejected {
    Assert-PackageUpdateLabsFeedRulesetProjection `
        -Ruleset $validRulesetProjection -ExpectedRulesetId 20137239 `
        -ExpectedUpdatedAtUtc "2026-07-31T17:15:58.7390000Z" `
        -ExternallyAuditedFullPolicySha256 "ABCD" `
        -Phase "pre-publication" | Out-Null
} "noncanonical external audit hash"
foreach ($token in @(
    'rusty\.quest\.package_update_labs_target\.v2',
    'MesmerPrism/Rusty-Kiosk',
    'six-asset closure',
    'rusty\.kiosk\.labs_release_owner_metadata\.v2',
    'release_id',
    'release_api_url',
    'release_html_url',
    'safe Android integer bound',
    'browser_download_url',
    'target_commitish -cne \$Target\.source_revision',
    '\$Release\.immutable -ne \$true',
    'complete-product',
    'Assert-PinnedReleaseContract',
    'Assert-PinnedOwnerMetadata',
    'Assert-ExactCanonicalHttpsUrl',
    'Get-PinnedGitHubTagSource',
    'Assert-FeedWorktreePreflight',
    'Copy-FeedToTransaction',
    'Install-FeedTransaction',
    'OutputDirectory = \$transactionRoot',
    'Transactional publication rewrote immutable feed content',
    'Transactional feed install failed and exact rollback also failed',
    'Labs feed publisher source must be a clean exact Git checkout',
    'ls-files --stage',
    'git/ref/tags/\$Tag',
    'git/commits/\$revision',
    'Read-PackageUpdatePointer',
    'ExpectedPriorPointerSha256',
    'ExpectedPriorEnvelopeSha256',
    'Refresh',
    '86400000',
    'package_update_publication_receipt\.v3',
    'FileAttributes\]::ReparsePoint',
    'Kiosk Labs channel contains a file family outside its contract',
    '\.nojekyll`npackage-updates'
)) {
    if ($publisher -notmatch $token) {
        throw "Labs Pages publisher is missing contract token: $token"
    }
}
foreach ($forbidden in @(
    '--clobber', 'gh release upload', 'gh release edit', 'CNAME',
    '-Method Delete', 'Remove-Item.*FeedRoot', 'expected_site_base_path.*='
)) {
    if ($publisher -match $forbidden) {
        throw "Labs Pages publisher contains forbidden route: $forbidden"
    }
}
$repository = "MesmerPrism/Rusty-Kiosk"
$tag = "v0.6.6-alpha.6"
$sourceRevision = "132b45325ec082f6ade9c5824d2c510194eccb22"
$sourceTree = "0123456789abcdef0123456789abcdef01234567"
$releaseId = 363205415L
$releaseApiUrl = "https://api.github.com/repos/$repository/releases/$releaseId"
$releaseHtmlUrl = "https://github.com/$repository/releases/tag/$tag"
$downloadRoot = "https://github.com/$repository/releases/download/$tag"
$target = [pscustomobject][ordered]@{
    schema = "rusty.quest.package_update_labs_target.v2"
    repository = $repository
    release_id = $releaseId
    release_api_url = $releaseApiUrl
    release_html_url = $releaseHtmlUrl
    release_tag = $tag
    source_revision = $sourceRevision
    source_tree = $sourceTree
    package_name = "io.github.mesmerprism.rustykiosk.labs"
    version_code = 60606L
    version_name = "0.6.6-alpha.6"
    signer_sha256 = "sha256:" + ("d" * 64)
    apk = [pscustomobject][ordered]@{
        id = 496913097L
        name = "rusty-kiosk.apk"
        browser_download_url = "$downloadRoot/rusty-kiosk.apk"
        sha256 = "sha256:" + ("a" * 64)
        bytes = 87062353L
    }
    owner_metadata = [pscustomobject][ordered]@{
        id = 496913007L
        name = "rusty-kiosk-labs-owner-release.json"
        browser_download_url =
            "$downloadRoot/rusty-kiosk-labs-owner-release.json"
        sha256 = "sha256:" + ("b" * 64)
        bytes = 1319L
    }
    bundle_manifest = [pscustomobject][ordered]@{
        id = 496913009L
        name = "bundle-manifest.json"
        browser_download_url = "$downloadRoot/bundle-manifest.json"
        sha256 = "sha256:" + ("c" * 64)
        bytes = 1642L
    }
}

function New-RemoteAsset($Pinned) {
    [pscustomobject][ordered]@{
        id = [int64]$Pinned.id
        url = "https://api.github.com/repos/$repository/releases/assets/$($Pinned.id)"
        name = $Pinned.name
        state = "uploaded"
        size = [int64]$Pinned.bytes
        digest = $Pinned.sha256
        browser_download_url = $Pinned.browser_download_url
    }
}

$remoteAssets = @(
    New-RemoteAsset $target.bundle_manifest
    New-RemoteAsset $target.owner_metadata
    [pscustomobject]@{ id = 496913008L; name = "RUSTY-KIOSK-LICENSE.txt" }
    [pscustomobject]@{ id = 496913010L; name = "rusty-kiosk-setup-helper.apk" }
    [pscustomobject]@{ id = 496913011L; name = "RUSTY-KIOSK-SOURCE.txt" }
    New-RemoteAsset $target.apk
)
$release = [pscustomobject][ordered]@{
    id = $releaseId
    url = $releaseApiUrl
    html_url = $releaseHtmlUrl
    tag_name = $tag
    target_commitish = $sourceRevision
    draft = $false
    prerelease = $true
    immutable = $true
    assets = $remoteAssets
}
$owner = [pscustomobject][ordered]@{
    schema = "rusty.kiosk.labs_release_owner_metadata.v2"
    repository = $repository
    product = "rusty-kiosk-labs"
    product_channel = "labs"
    maturity = "alpha"
    distribution_track = "github-prerelease"
    prerelease = $true
    tag = $tag
    version = "0.6.6-alpha.6"
    source_revision = $sourceRevision
    source_tree = $sourceTree
    installation_identity = "io.github.mesmerprism.rustykiosk.labs"
    coinstallable_lineage = [pscustomobject][ordered]@{
        identity_mode = "separate-coinstallable"
        package_name = "io.github.mesmerprism.rustykiosk.labs"
        signer_sha256 = "d" * 64
        version_name = "0.6.6-alpha.6"
        version_code = 60606L
        exit_policy = "uninstall-labs-without-changing-stable"
    }
    bundle_manifest = [pscustomobject][ordered]@{
        schema = "meta.quest.file_manager.rusty_kiosk_bundle.v2"
        name = "bundle-manifest.json"
        sha256 = "c" * 64
        bytes = 1642L
    }
    primary_artifact = [pscustomobject][ordered]@{
        role = "complete-product"
        name = "rusty-kiosk.apk"
        sha256 = "a" * 64
        bytes = 87062353L
    }
}

$null = Assert-LabsTargetContract $target
Assert-PinnedReleaseContract $release $target
Assert-PinnedOwnerMetadata $owner $target

$bad = Copy-JsonObject $target
$bad.release_id = 0
Assert-Rejected { $null = Assert-LabsTargetContract $bad } "nonpositive target release id"
$bad = Copy-JsonObject $target
$bad.version_code = "60606"
Assert-Rejected { $null = Assert-LabsTargetContract $bad } "string target version code"
$bad = Copy-JsonObject $target
$bad.version_code = 0
Assert-Rejected { $null = Assert-LabsTargetContract $bad } "nonpositive target version code"
$bad = Copy-JsonObject $target
$bad.version_code = [double]60606
Assert-Rejected { $null = Assert-LabsTargetContract $bad } "floating target version code"
$bad = Copy-JsonObject $target
$bad.version_code = [int64]([int]::MaxValue) + 1L
Assert-Rejected { $null = Assert-LabsTargetContract $bad } "unsafe target version code"
$bad = Copy-JsonObject $target
$bad.release_api_url = "${releaseApiUrl}?unexpected=1"
Assert-Rejected { $null = Assert-LabsTargetContract $bad } "target release API query"
$bad = Copy-JsonObject $target
$bad.release_html_url = "$releaseHtmlUrl#unexpected"
Assert-Rejected { $null = Assert-LabsTargetContract $bad } "target release HTML fragment"
$bad = Copy-JsonObject $target
$bad.apk.id = 0
Assert-Rejected { $null = Assert-LabsTargetContract $bad } "nonpositive target asset id"
$bad = Copy-JsonObject $target
$bad.apk.id = $bad.owner_metadata.id
Assert-Rejected { $null = Assert-LabsTargetContract $bad } "duplicate target asset id"
$bad = Copy-JsonObject $target
$bad.apk.browser_download_url = "$downloadRoot/other.apk"
Assert-Rejected { $null = Assert-LabsTargetContract $bad } "target asset URL mismatch"
$bad = Copy-JsonObject $target
$bad.apk | Add-Member -NotePropertyName unexpected -NotePropertyValue $true
Assert-Rejected { $null = Assert-LabsTargetContract $bad } "expanded target asset"

$badRelease = Copy-JsonObject $release
$badRelease.id = $releaseId + 1
Assert-Rejected { Assert-PinnedReleaseContract $badRelease $target } "release id mismatch"
$badRelease = Copy-JsonObject $release
$badRelease.url = "${releaseApiUrl}?unexpected=1"
Assert-Rejected { Assert-PinnedReleaseContract $badRelease $target } "release API URL mismatch"
$badRelease = Copy-JsonObject $release
$badRelease.html_url = "$releaseHtmlUrl#unexpected"
Assert-Rejected { Assert-PinnedReleaseContract $badRelease $target } "release HTML URL mismatch"
$badRelease = Copy-JsonObject $release
$badRelease.target_commitish = "0" * 40
Assert-Rejected { Assert-PinnedReleaseContract $badRelease $target } "release target mismatch"
$badRelease = Copy-JsonObject $release
$badRelease.immutable = $false
Assert-Rejected { Assert-PinnedReleaseContract $badRelease $target } "mutable upstream release"
$badRelease = Copy-JsonObject $release
($badRelease.assets | Where-Object name -CEQ "rusty-kiosk.apk").id++
Assert-Rejected { Assert-PinnedReleaseContract $badRelease $target } "asset id mismatch"
$badRelease = Copy-JsonObject $release
($badRelease.assets | Where-Object name -CEQ "rusty-kiosk.apk").url += "?unexpected=1"
Assert-Rejected { Assert-PinnedReleaseContract $badRelease $target } "asset API URL mismatch"
$badRelease = Copy-JsonObject $release
($badRelease.assets | Where-Object name -CEQ "rusty-kiosk.apk").browser_download_url =
    "$downloadRoot/other.apk"
Assert-Rejected { Assert-PinnedReleaseContract $badRelease $target } "asset download URL mismatch"
$badRelease = Copy-JsonObject $release
$badRelease.assets += [pscustomobject]@{ id = 1; name = "unexpected.bin" }
Assert-Rejected { Assert-PinnedReleaseContract $badRelease $target } "expanded six-asset closure"

foreach ($path in @("coinstallable_lineage", "bundle_manifest", "primary_artifact")) {
    $badOwner = Copy-JsonObject $owner
    $badOwner.$path | Add-Member -NotePropertyName unexpected -NotePropertyValue $true
    Assert-Rejected { Assert-PinnedOwnerMetadata $badOwner $target } `
        "expanded owner $path"
}
$badOwner = Copy-JsonObject $owner
$badOwner.primary_artifact.role = "setup-helper"
Assert-Rejected { Assert-PinnedOwnerMetadata $badOwner $target } `
    "non-complete primary artifact"

$repositoryTargetPath =
    Join-Path $RepoRoot "distribution\package-update-labs-target.json"
if (Test-Path -LiteralPath $repositoryTargetPath -PathType Leaf) {
    $repositoryTarget = Read-StrictJson `
        $repositoryTargetPath "Repository Labs feed target"
    $null = Assert-LabsTargetContract $repositoryTarget
    if ($repositoryTarget.signer_sha256 -cne
        "sha256:423d20004c79dd140c692e31aa80369cd3677b1ae2688dbd75011a4c83a0f1fb") {
        throw "Repository Labs target signer differs from the exact Kiosk Labs signer."
    }
}

$systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$transactionTestRoot = Join-Path $systemTemp (
    "rusty-quest-feed-transaction-$([guid]::NewGuid().ToString('N'))"
)
try {
    $destinationRoot = Join-Path $transactionTestRoot "destination"
    $candidateRoot = Join-Path $transactionTestRoot "candidate"
    New-Item -ItemType Directory -Path $destinationRoot | Out-Null
    [IO.File]::WriteAllBytes(
        (Join-Path $destinationRoot ".nojekyll"), [byte[]]::new(0)
    )
    $channel = Join-Path $destinationRoot `
        "package-updates\rusty-kiosk\labs"
    $oldGeneration = "s1-v60606-aaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb"
    $newGeneration = "s2-v60606-aaaaaaaaaaaaaaaa-cccccccccccccccc"
    $artifactHash = "a" * 64
    $artifactDirectory = Join-Path $channel "artifacts\sha256\$artifactHash"
    $oldGenerationDirectory = Join-Path $channel "generations\$oldGeneration"
    New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $oldGenerationDirectory -Force | Out-Null
    [IO.File]::WriteAllBytes(
        (Join-Path $artifactDirectory "rusty-kiosk-0.6.6-alpha.6.apk"),
        [byte[]](1, 2, 3)
    )
    [IO.File]::WriteAllText(
        (Join-Path $oldGenerationDirectory "envelope.json"), "old-envelope"
    )
    [IO.File]::WriteAllText(
        (Join-Path $oldGenerationDirectory "publication-receipt.json"),
        "old-receipt"
    )
    [IO.File]::WriteAllText((Join-Path $channel "current.json"), "old-pointer")
    Copy-FeedToTransaction -SourceRoot $destinationRoot `
        -TransactionRoot $candidateRoot
    $newGenerationDirectory = Join-Path $candidateRoot `
        "package-updates\rusty-kiosk\labs\generations\$newGeneration"
    New-Item -ItemType Directory -Path $newGenerationDirectory | Out-Null
    [IO.File]::WriteAllText(
        (Join-Path $newGenerationDirectory "envelope.json"), "new-envelope"
    )
    [IO.File]::WriteAllText(
        (Join-Path $newGenerationDirectory "publication-receipt.json"),
        "new-receipt"
    )
    [IO.File]::WriteAllText(
        (Join-Path $candidateRoot "package-updates\rusty-kiosk\labs\current.json"),
        "new-pointer"
    )
    $transactionReceipt = [pscustomobject]@{
        generation = $newGeneration
        apk_sha256 = "sha256:$artifactHash"
        version_name = "0.6.6-alpha.6"
    }
    Install-FeedTransaction -CandidateRoot $candidateRoot `
        -DestinationRoot $destinationRoot -Receipt $transactionReceipt
    $installed = Get-FeedFileInventory $destinationRoot
    $candidateInventory = Get-FeedFileInventory $candidateRoot
    if ((($installed.Keys | Sort-Object) -join "`n") -cne
        (($candidateInventory.Keys | Sort-Object) -join "`n")) {
        throw "Synthetic transaction did not install its exact file inventory."
    }

    $rollbackCandidate = Join-Path $transactionTestRoot "rollback-candidate"
    Copy-FeedToTransaction -SourceRoot $destinationRoot `
        -TransactionRoot $rollbackCandidate
    $rollbackGeneration = "s3-v60606-aaaaaaaaaaaaaaaa-dddddddddddddddd"
    $rollbackGenerationDirectory = Join-Path $rollbackCandidate `
        "package-updates\rusty-kiosk\labs\generations\$rollbackGeneration"
    New-Item -ItemType Directory -Path $rollbackGenerationDirectory | Out-Null
    [IO.File]::WriteAllText(
        (Join-Path $rollbackGenerationDirectory "envelope.json"), "rollback-envelope"
    )
    [IO.File]::WriteAllText(
        (Join-Path $rollbackGenerationDirectory "publication-receipt.json"),
        "rollback-receipt"
    )
    [IO.File]::WriteAllText(
        (Join-Path $rollbackCandidate `
            "package-updates\rusty-kiosk\labs\current.json"),
        "rollback-pointer"
    )
    $beforeRollback = Get-FeedFileInventory $destinationRoot
    $pointerLock = [IO.File]::Open(
        (Join-Path $destinationRoot `
            "package-updates\rusty-kiosk\labs\current.json"),
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::Read
    )
    try {
        Assert-Rejected {
            Install-FeedTransaction -CandidateRoot $rollbackCandidate `
                -DestinationRoot $destinationRoot -Receipt ([pscustomobject]@{
                    generation = $rollbackGeneration
                    apk_sha256 = "sha256:$artifactHash"
                    version_name = "0.6.6-alpha.6"
                })
        } "locked-pointer transactional install"
    } finally {
        $pointerLock.Dispose()
    }
    $afterRollback = Get-FeedFileInventory $destinationRoot
    if ((($beforeRollback.Keys | Sort-Object) -join "`n") -cne
        (($afterRollback.Keys | Sort-Object) -join "`n")) {
        throw "Failed transaction rollback changed the feed inventory."
    }
    foreach ($path in $beforeRollback.Keys) {
        if ($beforeRollback[$path].sha256 -cne $afterRollback[$path].sha256 -or
            $beforeRollback[$path].bytes -ne $afterRollback[$path].bytes) {
            throw "Failed transaction rollback changed feed bytes."
        }
    }
} finally {
    $fullTransactionTestRoot = [IO.Path]::GetFullPath($transactionTestRoot)
    if (-not $fullTransactionTestRoot.StartsWith(
            $systemTemp, [StringComparison]::OrdinalIgnoreCase
        ) -or $fullTransactionTestRoot -ceq $systemTemp) {
        throw "Refusing to remove an unsafe transaction fixture path."
    }
    if ([IO.Directory]::Exists($fullTransactionTestRoot)) {
        [IO.Directory]::Delete($fullTransactionTestRoot, $true)
    }
}

$invalidUtf8Path = Join-Path $systemTemp (
    "rusty-quest-invalid-utf8-$([guid]::NewGuid().ToString('N')).json"
)
try {
    [IO.File]::WriteAllBytes(
        $invalidUtf8Path,
        [byte[]](0x7b, 0x22, 0x78, 0x22, 0x3a, 0x22, 0xc3, 0x28, 0x22, 0x7d)
    )
    Assert-Rejected { $null = Read-StrictJson $invalidUtf8Path "Invalid UTF-8" } `
        "invalid UTF-8 JSON bytes"
} finally {
    $fullInvalidUtf8Path = [IO.Path]::GetFullPath($invalidUtf8Path)
    if (-not $fullInvalidUtf8Path.StartsWith(
            $systemTemp, [StringComparison]::OrdinalIgnoreCase
        ) -or $fullInvalidUtf8Path -ceq $systemTemp) {
        throw "Refusing to remove an unsafe invalid UTF-8 fixture path."
    }
    if ([IO.File]::Exists($fullInvalidUtf8Path)) {
        [IO.File]::Delete($fullInvalidUtf8Path)
    }
}

Write-Output "Package Update Labs Pages workflow contract passed."
