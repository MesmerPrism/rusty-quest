[CmdletBinding()]
param(
    [string]$RepoRoot = "",
    [string]$ExpectedBootstrapApprovalAncestor = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
} else {
    $RepoRoot = (Resolve-Path $RepoRoot).Path
}

$workflowPath = Join-Path $RepoRoot `
    ".github\workflows\external-validation-authority.yml"
$dynamicWorkflowPath = Join-Path $RepoRoot `
    ".github\workflows\package-updater-dynamic-validation.yml"
$adapterPath = Join-Path $RepoRoot `
    ".github\scripts\Invoke-RustyQuestExternalValidationAuthority.ps1"
$externalOwnerModulePath = Join-Path $RepoRoot `
    ".github\scripts\lib\ExternalOwnerAuthorization.psm1"
$schemaPath = Join-Path $RepoRoot `
    "schemas\rusty.quest.external_validation_authority_assessment.v1.schema.json"
$settingsPath = Join-Path $RepoRoot `
    "config\external-validation-authority-settings.json"
$settingsSchemaPath = Join-Path $RepoRoot `
    "schemas\rusty.quest.external_validation_authority_settings.v1.schema.json"
$probeReceiptSchemaPath = Join-Path $RepoRoot `
    "schemas\rusty.quest.external_validation_authority_probe_receipt.v1.schema.json"
$probeReceiptFixturePath = Join-Path $RepoRoot `
    "fixtures\validation-authority\adversarial-probe-receipt.hold.json"
$approvalFixturePath = Join-Path $RepoRoot `
    "fixtures\validation-authority\bootstrap-approval.valid.json"
$policySelfTest = Join-Path $RepoRoot `
    "tools\checks\Test-ExternalValidationAuthorityPolicySelfTest.ps1"
$externalOwnerSelfTest = Join-Path $RepoRoot `
    "tools\checks\Test-ExternalOwnerAuthorization.ps1"
$externalOwnerBootstrapSelfTest = Join-Path $RepoRoot `
    "tools\checks\Test-ExternalOwnerBootstrapAuthorization.ps1"
$bootstrapRequestSchemaPath = Join-Path $RepoRoot `
    "schemas\rusty.quest.external_owner_bootstrap_request.v1.schema.json"
$bootstrapAuthorizationSchemaPath = Join-Path $RepoRoot `
    "schemas\rusty.quest.external_owner_bootstrap_authorization.v1.schema.json"

foreach ($path in @(
    $workflowPath,
    $dynamicWorkflowPath,
    $adapterPath,
    $externalOwnerModulePath,
    $schemaPath,
    $settingsPath,
    $settingsSchemaPath,
    $probeReceiptSchemaPath,
    $probeReceiptFixturePath,
    $approvalFixturePath,
    $policySelfTest,
    $externalOwnerSelfTest,
    $externalOwnerBootstrapSelfTest,
    $bootstrapRequestSchemaPath,
    $bootstrapAuthorizationSchemaPath
)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "External validation authority surface is missing: $path"
    }
}

$settingsJson = Get-Content -Raw -LiteralPath $settingsPath
if (-not (Test-Json -Json $settingsJson -SchemaFile $settingsSchemaPath `
    -ErrorAction Stop)) {
    throw "External validation authority settings rejected their exact contract."
}
$settings = $settingsJson | ConvertFrom-Json -Depth 30
if ($settings.target.repository_id -ne 1264057043 -or
    $settings.target.owner_kind -cne "user" -or
    $settings.selected_mode -cne "repository-status-check-probe-gated" -or
    $settings.authoritative_workflow_identity.repository_id -ne 1264057043 -or
    $settings.authoritative_workflow_identity.path -cne
        ".github/workflows/external-validation-authority.yml" -or
    $settings.authoritative_workflow_identity.ref -cne "refs/heads/main" -or
    $settings.authoritative_workflow_identity.event -cne "pull_request_target" -or
    $settings.authoritative_workflow_identity.settings_bind_workflow_path -ne $false -or
    $settings.authoritative_workflow_identity.cryptographic_path_provenance -ne $false) {
    throw "Honest base-owned workflow identity differs."
}
if ($settings.required_status_check.ruleset_scope -cne "repository" -or
    $settings.required_status_check.context -cne "Static admission" -or
    $settings.required_status_check.app_id -ne 15368 -or
    $settings.required_status_check.strict -ne $true -or
    $settings.required_status_check.binds_workflow_path -ne $false -or
    $settings.required_status_check.authoritative_without_probe -ne $false -or
    $settings.adversarial_probe.required_before_candidate_or_release -ne $true -or
    $settings.adversarial_probe.merge_state_required -cne "BLOCKED" -or
    $settings.adversarial_probe.admin_bypass_allowed -ne $false -or
    $settings.adversarial_probe.merge_attempt_allowed -ne $false -or
    $settings.adversarial_probe.receipt_storage -cne "private-local" -or
    $settings.future_stronger_mode.deployable_for_current_owner -ne $false) {
    throw "Repository status-check and mandatory probe boundary differs."
}

$probeReceiptJson = Get-Content -Raw -LiteralPath $probeReceiptFixturePath
if (-not (Test-Json -Json $probeReceiptJson `
    -SchemaFile $probeReceiptSchemaPath -ErrorAction Stop)) {
    throw "Adversarial probe hold fixture rejected its exact schema."
}
$probeReceipt = $probeReceiptJson | ConvertFrom-Json -Depth 40
if ($probeReceipt.evidence_origin -cne "synthetic-schema-fixture" -or
    $probeReceipt.decision -cne "hold" -or
    @($probeReceipt.hold_reasons) -cnotcontains "synthetic-not-authority") {
    throw "Committed probe fixture must remain an explicit synthetic hold."
}

$workflow = Get-Content -Raw -LiteralPath $workflowPath
foreach ($token in @(
    '(?m)^name: External validation authority\s*$',
    '(?m)^\s*pull_request_target:\s*$',
    '(?m)^permissions:\s*\r?\n\s+contents: read\r?\n\s+pull-requests: read\s*$',
    'runs-on: windows-2025',
    '(?m)^\s{4}name: Static admission\s*$',
    'timeout-minutes: 10',
    'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7\.0\.1',
    'persist-credentials: false',
    'ref: \$\{\{ github\.event\.pull_request\.base\.sha \}\}',
    'repository: MesmerPrism/rusty-morphospace-work-environment',
    'ref: 50a4c5222c9d6c4567bac09405e43049c61b126f',
    'EVENT_BASE_REF: \$\{\{ github\.event\.pull_request\.base\.ref \}\}',
    'EVENT_BASE_REPOSITORY: \$\{\{ github\.event\.pull_request\.base\.repo\.full_name \}\}',
    'EVENT_HEAD_REPOSITORY: \$\{\{ github\.event\.pull_request\.head\.repo\.full_name \}\}',
    'EVENT_HEAD_SHA: \$\{\{ github\.event\.pull_request\.head\.sha \}\}',
    'EVENT_MERGE_SHA: \$\{\{ github\.event\.pull_request\.merge_commit_sha \}\}',
    'GITHUB_TOKEN: \$\{\{ github\.token \}\}',
    'Invoke-RustyQuestExternalValidationAuthority\.ps1',
    '-BaseRepository \$env:EVENT_BASE_REPOSITORY',
    '-BaseRef \$env:EVENT_BASE_REF',
    '-HeadRepository \$env:EVENT_HEAD_REPOSITORY',
    '-CandidateCommit \$env:EVENT_HEAD_SHA',
    '-EventMergeCommit \$env:EVENT_MERGE_SHA',
    '-RunnerLabel "windows-2025"',
    '-RunnerOs \$env:RUNNER_OS',
    '-RunnerArchitecture \$env:RUNNER_ARCH',
    '-RunnerImageOs \$env:ImageOS',
    '-RunnerImageVersion \$env:ImageVersion'
)) {
    if ($workflow -notmatch $token) {
        throw "External validation workflow is missing contract token: $token"
    }
}
function Assert-AuthorityTokenProjection {
    param([Parameter(Mandatory)][string]$Content)
    $permissions = @([regex]::Matches(
        $Content,
        '(?m)^permissions:\r?\n(?<body>(?:  [^\r\n]+\r?\n){2})\r?\n(?=^jobs:)'
    ))
    $normalizedPermissions = if ($permissions.Count -eq 1) {
        (($permissions[0].Groups['body'].Value -split '\r?\n' |
                Where-Object { $_.Length -gt 0 } |
                ForEach-Object { $_.Trim() }) -join "`n")
    }
    if ($permissions.Count -ne 1 -or
        $normalizedPermissions -cne "contents: read`npull-requests: read") {
        throw "External validation workflow permissions must remain exactly contents:read and pull-requests:read."
    }
    $authoritySteps = @([regex]::Matches(
        $Content,
        '(?ms)^      - name: Assess candidate Git objects without checkout or execution\s*\r?\n(?<body>(?:(?!^      - name:)[\s\S])*)'
    ))
    if ($authoritySteps.Count -ne 1) {
        throw "External validation workflow must contain exactly one named authority step."
    }
    $authorityStep = $authoritySteps[0].Value
    $matches = @([regex]::Matches(
        $Content,
        '(?m)^          GITHUB_TOKEN:\s*\$\{\{ github\.token \}\}\s*$'
    ))
    if ($matches.Count -ne 1 -or $authorityStep -notmatch
        '(?m)^        env:\s*\r?\n          GITHUB_TOKEN:\s*\$\{\{ github\.token \}\}\s*$' -or
        ($Content.Replace($authorityStep, "") -match
            '(?m)^\s*GITHUB_TOKEN:\s*')) {
        throw "External validation workflow must project the built-in token only to the authority step."
    }
}
Assert-AuthorityTokenProjection $workflow
$tokenProjectionDamage = $workflow -replace
    'GITHUB_TOKEN:\s*\$\{\{ github\.token \}\}',
    'GITHUB_TOKEN: ${{ secrets.UNRELATED_TOKEN }}'
$tokenProjectionRejected = $false
try { Assert-AuthorityTokenProjection $tokenProjectionDamage } catch { $tokenProjectionRejected = $true }
if (-not $tokenProjectionRejected) {
    throw "Authority token projection damage was accepted."
}
$tokenRelocationDamage = ($workflow -replace
    '(?m)^          GITHUB_TOKEN:\s*\$\{\{ github\.token \}\}\s*\r?\n',
    '') + @'

      - name: Candidate-executing token damage
        env:
          GITHUB_TOKEN: ${{ github.token }}
        run: .\candidate.ps1
'@
$tokenRelocationRejected = $false
try { Assert-AuthorityTokenProjection $tokenRelocationDamage } catch { $tokenRelocationRejected = $true }
if (-not $tokenRelocationRejected) {
    throw "Authority token relocation to a candidate-executing step was accepted."
}
$permissionDamage = $workflow -replace
    '(?m)^  pull-requests: read\s*$',
    "  pull-requests: read`n  issues: write"
$permissionDamageRejected = $false
try { Assert-AuthorityTokenProjection $permissionDamage } catch { $permissionDamageRejected = $true }
if (-not $permissionDamageRejected) {
    throw "Additional workflow write permission was accepted."
}
$checkoutUses = @([regex]::Matches(
    $workflow,
    'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1'
))
if ($checkoutUses.Count -ne 2) {
    throw "External validation workflow must use exactly two pinned checkouts."
}
foreach ($forbidden in @(
    '(?m)^\s*pull_request:\s*$',
    '(?m)^\s+contents:\s*write\s*$',
    '(?m)^\s+environment:\s*',
    'id-token:',
    'secrets\.',
    'actions/upload-artifact',
    'actions/cache',
    'submodules:\s*true',
    'lfs:\s*true',
    'persist-credentials:\s*true',
    'ref:\s*\$\{\{\s*github\.event\.pull_request\.head\.sha'
)) {
    if ($workflow -match $forbidden) {
        throw "External validation workflow contains forbidden authority: $forbidden"
    }
}

$dynamicWorkflow = Get-Content -Raw -LiteralPath $dynamicWorkflowPath
if ($dynamicWorkflow -match '(?m)^\s*GITHUB_TOKEN:\s*') {
    throw "Candidate-executing dynamic validation must not receive an authority token."
}
foreach ($token in @(
    '(?m)^name: Package updater dynamic validation \(non-authoritative\)\s*$',
    '(?m)^\s*pull_request:\s*$',
    '(?m)^\s+branches:\s*\r?\n\s+- main\s*$',
    '(?m)^permissions:\s*\r?\n\s+contents: read\s*$',
    'runs-on: windows-2025',
    '(?m)^\s{4}name: Credential-free candidate checks \(non-authoritative\)\s*$',
    'timeout-minutes: 30',
    'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7\.0\.1',
    'persist-credentials: false',
    'fetch-depth: 0',
    'ref: \$\{\{ github\.event\.pull_request\.head\.sha \}\}',
    'path: rusty-quest',
    'path: rusty-manifold',
    'repository: MesmerPrism/rusty-manifold',
    'ref: 947421a928889889e485006bcc0200e05c2394f9',
    'path: rusty-lattice',
    'repository: MesmerPrism/rusty-lattice',
    'ref: 0aee7faa52fc965ff2255381781dd082ab639f4b',
    'path: rusty-matter',
    'repository: MesmerPrism/rusty-matter',
    'ref: eec8cddd9830f7ef0f90574ddcbde2daac0ec804',
    'path: rusty-optics',
    'repository: MesmerPrism/rusty-optics',
    'ref: fd01d84acffa1b0a3a192fe978af337d9fedd18a',
    'working-directory: rusty-quest',
    'EVENT_BASE_REF: \$\{\{ github\.event\.pull_request\.base\.ref \}\}',
    'EVENT_BASE_REPOSITORY: \$\{\{ github\.event\.pull_request\.base\.repo\.full_name \}\}',
    'EVENT_HEAD_REPOSITORY: \$\{\{ github\.event\.pull_request\.head\.repo\.full_name \}\}',
    'EVENT_MERGE_SHA: \$\{\{ github\.event\.pull_request\.merge_commit_sha \}\}',
    '\[string\]::IsNullOrEmpty\(\$env:EVENT_MERGE_SHA\)',
    'event-merge-observation-stale-fetched-ref-authoritative',
    'Dynamic validation base repository differs',
    'Dynamic validation base ref differs from main',
    'refs/pull/\$\(\$env:PR_NUMBER_EXACT\)/merge',
    '\$parents\.Count -ne 3',
    '\$parents\[1\] -cne \$env:EVENT_BASE_SHA',
    '\$parents\[2\] -cne \$env:EVENT_HEAD_SHA',
    'head_repository=',
    'actions/setup-java@03ad4de0992f5dab5e18fcb136590ce7c4a0ac95 # v5\.6\.0',
    'distribution: temurin',
    'java-version: 17\.0\.14\+7',
    'rustup toolchain install 1\.96\.0 --component rustfmt --no-self-update',
    'rustup override set 1\.96\.0',
    '\^rustc 1\\\.96\\\.0',
    'cargo \+1\.96\.0 fmt --all --check',
    'cargo \+1\.96\.0 test -p rusty-quest-package-updater --locked',
    'Test-PackageUpdaterAndroidStatic\.ps1',
    'does not validate owner effects, accept work, or authorize publication'
)) {
    if ($dynamicWorkflow -notmatch $token) {
        throw "Dynamic validation workflow is missing contract token: $token"
    }
}
function Assert-DynamicContextBoundary {
    param([string]$Content)
    if ($Content -match '(?m)^\s+name:\s+Static admission\s*$') {
        throw "Candidate-executing workflow duplicates the authority check context."
    }
}
Assert-DynamicContextBoundary $dynamicWorkflow
$duplicateContextRejected = $false
try {
    Assert-DynamicContextBoundary ($dynamicWorkflow + "`n    name: Static admission`n")
} catch {
    if ($_.Exception.Message -cne
        "Candidate-executing workflow duplicates the authority check context.") {
        throw "Duplicate-context damage rejected for the wrong reason."
    }
    $duplicateContextRejected = $true
}
if (-not $duplicateContextRejected) {
    throw "Candidate-created duplicate authority context was accepted."
}
$dynamicCheckoutUses = @([regex]::Matches(
    $dynamicWorkflow,
    'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1'
))
if ($dynamicCheckoutUses.Count -ne 7) {
    throw "Dynamic validation must use seven exact pinned source checkouts."
}
$dynamicUses = @([regex]::Matches($dynamicWorkflow, '(?m)^\s+uses:\s+'))
if ($dynamicUses.Count -ne 8) {
    throw "Dynamic validation may use only seven checkouts and exact Java setup."
}
foreach ($binding in @(
    [pscustomobject]@{ pattern = 'fetch-depth: 0'; count = 2 },
    [pscustomobject]@{ pattern = 'fetch-depth: 1'; count = 5 },
    [pscustomobject]@{ pattern = 'lfs: false'; count = 7 },
    [pscustomobject]@{ pattern = 'persist-credentials: false'; count = 7 },
    [pscustomobject]@{ pattern = 'submodules: false'; count = 7 },
    [pscustomobject]@{
        pattern = 'actions/setup-java@03ad4de0992f5dab5e18fcb136590ce7c4a0ac95'
        count = 1
    }
)) {
    if (@([regex]::Matches($dynamicWorkflow, $binding.pattern)).Count -ne
        $binding.count) {
        throw "Dynamic validation binding count differs: $($binding.pattern)"
    }
}
$dynamicPermissions = [regex]::Match(
    $dynamicWorkflow,
    '(?ms)^permissions:\s*\r?\n(?<body>(?:  [^\r\n]+\r?\n)+)'
)
if (-not $dynamicPermissions.Success -or
    $dynamicPermissions.Groups['body'].Value.Trim() -cne 'contents: read') {
    throw "Dynamic validation permissions must be exactly contents: read."
}
foreach ($forbidden in @(
    '(?m)^\s*pull_request_target:\s*$',
    '(?m)^\s*push:\s*$',
    '(?m)^\s*workflow_dispatch:\s*$',
    '(?m)^\s*schedule:\s*$',
    '(?m)^\s*release:\s*$',
    '(?m)^\s*merge_group:\s*$',
    '(?m)^\s+[a-z-]+:\s*write\s*$',
    '(?m)^\s+environment:\s*',
    'id-token:',
    'secrets\.',
    'actions/upload-artifact',
    'actions/cache',
    'submodules:\s*true',
    'lfs:\s*true',
    'persist-credentials:\s*true',
    'continue-on-error:',
    'gh\s+release',
    'Invoke-Expression',
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Start-Process'
)) {
    if ($dynamicWorkflow -match $forbidden) {
        throw "Dynamic validation workflow contains forbidden authority: $forbidden"
    }
}
function Assert-TransportEnvelopeDiagnosticBoundary {
    param([Parameter(Mandatory)][string]$Content)

    $diagnosticMatch = [regex]::Match(
        $Content,
        '(?ms)^  transport-envelope-diagnostic:\s*\r?\n(?<body>.*)\z'
    )
    if (-not $diagnosticMatch.Success) {
        throw "Transport diagnostic job is missing or is not the final bounded job."
    }
    $diagnostic = $diagnosticMatch.Value
    foreach ($token in @(
        '(?m)^    name: Child PowerShell transport fingerprint \(non-authoritative\)\s*$',
        '(?m)^    runs-on: windows-2025\s*$',
        '(?m)^    timeout-minutes: 10\s*$',
        '(?m)^          path: trusted-base\s*$',
        '(?m)^          ref: \$\{\{ github\.event\.pull_request\.base\.sha \}\}\s*$',
        '(?m)^          path: pinned-verifier\s*$',
        '(?m)^          ref: 50a4c5222c9d6c4567bac09405e43049c61b126f\s*$',
        '(?m)^          repository: MesmerPrism/rusty-morphospace-work-environment\s*$',
        'EVENT_BASE_REF: \$\{\{ github\.event\.pull_request\.base\.ref \}\}',
        'EVENT_BASE_REPOSITORY: \$\{\{ github\.event\.pull_request\.base\.repo\.full_name \}\}',
        'EVENT_BASE_SHA: \$\{\{ github\.event\.pull_request\.base\.sha \}\}',
        'EVENT_HEAD_REPOSITORY: \$\{\{ github\.event\.pull_request\.head\.repo\.full_name \}\}',
        'EVENT_HEAD_SHA: \$\{\{ github\.event\.pull_request\.head\.sha \}\}',
        'PR_NUMBER_EXACT: \$\{\{ github\.event\.pull_request\.number \}\}',
        'REPOSITORY_EXACT: \$\{\{ github\.repository \}\}',
        'candidate_code_executed = \$false',
        'non_authoritative = \$true',
        'rusty\.quest\.external_validation_transport_fingerprint\.v1',
        'pull_request_number = \[int\]\$env:PR_NUMBER_EXACT',
        'base = \[ordered\]@\{ repository = \$env:EVENT_BASE_REPOSITORY; ref = \$env:EVENT_BASE_REF; commit = \$env:EVENT_BASE_SHA \}',
        'head = \[ordered\]@\{ repository = \$env:EVENT_HEAD_REPOSITORY; commit = \$env:EVENT_HEAD_SHA \}',
        'policyBlob = \(git -C \$baseRoot rev-parse --verify',
        'git_blob = \$policyBlob',
        'Get-TransportText',
        '-gt 512',
        '\[Diagnostics\.ProcessStartInfo\]::new\(\)',
        'RedirectStandardOutput = \$true',
        'RedirectStandardError = \$true',
        '"-File", \$verifierScript',
        '\$records = @\(& \$pwsh @arguments 2>&1\)',
        'record_count = \$recordRows\.Count',
        'fully_qualified_error_id',
        'message = \[ordered\]@\{ bytes = \$messageBytes\.Length; sha256 = Get-Sha256 \$messageBytes; escaped_text = Get-TransportText \$message \}'
    )) {
        if ($diagnostic -notmatch $token) {
            throw "Transport diagnostic is missing required bounded token: $token"
        }
    }
    foreach ($forbidden in @(
        'GITHUB_TOKEN',
        'github\.token',
        'secrets\.',
        'pull_request_target',
        'workflow_dispatch',
        'actions/upload-artifact',
        'actions/cache',
        'path:\s*rusty-quest',
        'working-directory:',
        'ref:\s*\$\{\{\s*github\.event\.pull_request\.head\.sha',
        'Invoke-RustyQuestExternalValidationAuthority',
        'Test-PackageUpdater',
        'cargo\s',
        'gh\s+',
        'Invoke-RestMethod',
        'Invoke-WebRequest',
        'Start-Process'
    )) {
        if ($diagnostic -match $forbidden) {
            throw "Transport diagnostic contains forbidden authority or candidate execution: $forbidden"
        }
    }
    $scriptMatch = [regex]::Match(
        $diagnostic,
        '(?ms)- name: Fingerprint pinned child PowerShell transport \(non-authoritative\).*?' +
            'run: \|\r?\n(?<body>(?: {10}[^\r\n]*\r?\n)+)'
    )
    if (-not $scriptMatch.Success) {
        throw "Transport diagnostic PowerShell block is not bounded."
    }
    $script = [regex]::Replace(
        $scriptMatch.Groups['body'].Value,
        '(?m)^ {10}',
        ''
    )
    $tokens = $null
    $errors = $null
    $diagnosticAst = [Management.Automation.Language.Parser]::ParseInput(
        $script, [ref]$tokens, [ref]$errors
    )
    if ($errors.Count -ne 0) {
        throw "Transport diagnostic PowerShell parse failed: $($errors[0].Message)"
    }
    $successExitCommands = @([regex]::Matches($script, '(?m)^exit 0\s*$'))
    if ($successExitCommands.Count -ne 1 -or $script -notmatch
        '(?ms)^\} \| ConvertTo-Json -Compress -Depth 12 \| Write-Output\r?\nexit 0\s*\z') {
        throw "Transport diagnostic success exit must be unique and immediately follow verified JSON output."
    }
    foreach ($command in @($diagnosticAst.FindAll({
                param($node)
                $node -is [Management.Automation.Language.CommandAst]
            }, $true))) {
        $executable = $command.GetCommandName()
        if ($executable -cnotmatch '^(?:git|git\.exe)$') {
            continue
        }
        $elements = @($command.CommandElements)
        $index = 1
        while ($index -lt $elements.Count) {
            $token = [string]$elements[$index].Extent.Text
            if ($token -cmatch '^(?:-C|-c|--config-env|--exec-path|--git-dir|--work-tree|--namespace|--super-prefix)$') {
                $index += 2
                continue
            }
            if ($token -cmatch '^(?:-C|-c|--config-env|--exec-path|--git-dir|--work-tree|--namespace|--super-prefix)=') {
                $index++
                continue
            }
            if ($token -cmatch '^-' -and $token -cnotin @('--')) {
                $index++
                continue
            }
            if ($token -cin @('checkout', 'switch', 'worktree')) {
                throw "Transport diagnostic contains candidate materialization command '$token' through Git executable '$executable'."
            }
            break
        }
    }
}
Assert-TransportEnvelopeDiagnosticBoundary $dynamicWorkflow
$transportCandidateRefDamage = $dynamicWorkflow -replace
    '(?ms)(transport-envelope-diagnostic:.*?path: trusted-base\r?\n\s+persist-credentials: false\r?\n\s+ref: )\$\{\{ github\.event\.pull_request\.base\.sha \}\}',
    '$1${{ github.event.pull_request.head.sha }}'
$transportCandidateRefRejected = $false
try { Assert-TransportEnvelopeDiagnosticBoundary $transportCandidateRefDamage } catch { $transportCandidateRefRejected = $true }
if (-not $transportCandidateRefRejected) {
    throw "Transport diagnostic accepted a candidate checkout ref."
}
$transportExecutionDamage = $dynamicWorkflow -replace
    'candidate_code_executed = \$false',
    'candidate_code_executed = $true'
$transportExecutionRejected = $false
try { Assert-TransportEnvelopeDiagnosticBoundary $transportExecutionDamage } catch { $transportExecutionRejected = $true }
if (-not $transportExecutionRejected) {
    throw "Transport diagnostic accepted a candidate-execution assertion."
}
$transportPreambleLine = '          $ErrorActionPreference = "Stop"'
$transportSuccessExitDamages = @(
    [pscustomobject]@{
        label = 'early success exit'
        content = $dynamicWorkflow.Replace(
            $transportPreambleLine,
            "$transportPreambleLine`n          exit 0"
        )
    },
    [pscustomobject]@{
        label = 'missing success exit'
        content = $dynamicWorkflow -replace '(?m)^          exit 0\s*\r?\n?', ''
    }
)
foreach ($damage in $transportSuccessExitDamages) {
    $rejected = $false
    try {
        Assert-TransportEnvelopeDiagnosticBoundary $damage.content
    } catch {
        if ($_.Exception.Message -cnotmatch
            'success exit must be unique and immediately follow verified JSON output') {
            throw "Transport success-exit damage '$($damage.label)' rejected for the wrong reason: $($_.Exception.Message)"
        }
        $rejected = $true
    }
    if (-not $rejected) {
        throw "Transport diagnostic accepted success-exit damage: $($damage.label)"
    }
}
$transportMaterializationDamages = @(
    [pscustomobject]@{ label = 'bare checkout'; command = 'git checkout --detach $env:EVENT_HEAD_SHA' },
    [pscustomobject]@{ label = 'base-root checkout'; command = '& git -C $baseRoot checkout --detach $env:EVENT_HEAD_SHA' },
    [pscustomobject]@{ label = 'path-qualified switch'; command = 'git -C "$env:RUNNER_TEMP\trusted-base" switch --detach $env:EVENT_HEAD_SHA' },
    [pscustomobject]@{ label = 'base-root worktree'; command = 'git -C $baseRoot worktree add "$env:RUNNER_TEMP\candidate" $env:EVENT_HEAD_SHA' },
    [pscustomobject]@{ label = 'bare worktree'; command = 'git worktree add candidate $env:EVENT_HEAD_SHA' },
    [pscustomobject]@{ label = 'git executable checkout'; command = 'git.exe checkout --detach $env:EVENT_HEAD_SHA' },
    [pscustomobject]@{ label = 'global-option checkout'; command = 'git --no-pager checkout --detach $env:EVENT_HEAD_SHA' },
    [pscustomobject]@{ label = 'equals-C switch'; command = 'git -C=$baseRoot switch --detach $env:EVENT_HEAD_SHA' },
    [pscustomobject]@{ label = 'combined global worktree'; command = 'git -C $baseRoot --no-pager worktree add "$env:RUNNER_TEMP\candidate" $env:EVENT_HEAD_SHA' }
)
$transportMaterializationAnchor = '          $verifierScript = Join-Path $verifierRoot "scripts/Test-ExternalValidationAuthority.ps1"'
foreach ($damage in $transportMaterializationDamages) {
    $damagedDiagnostic = $dynamicWorkflow.Replace(
        $transportMaterializationAnchor,
        "          $($damage.command)`n$transportMaterializationAnchor"
    )
    $rejected = $false
    try {
        Assert-TransportEnvelopeDiagnosticBoundary $damagedDiagnostic
    } catch {
        if ($_.Exception.Message -cnotmatch 'candidate materialization command') {
            throw "Transport materialization damage '$($damage.label)' rejected for the wrong reason: $($_.Exception.Message)"
        }
        $rejected = $true
    }
    if (-not $rejected) {
        throw "Transport diagnostic accepted materialization damage: $($damage.label)"
    }
}
$topologyScriptMatch = [regex]::Match(
    $dynamicWorkflow,
    '(?ms)- name: Verify exact PR event topology \(non-authoritative\).*?' +
        'run: \|\r?\n(?<body>(?: {10}[^\r\n]*\r?\n)+)'
)
if (-not $topologyScriptMatch.Success) {
    throw "Dynamic validation topology script block is not bounded."
}
$topologyScript = [regex]::Replace(
    $topologyScriptMatch.Groups['body'].Value,
    '(?m)^ {10}',
    ''
)
$topologyTokens = $null
$topologyErrors = $null
[void][Management.Automation.Language.Parser]::ParseInput(
    $topologyScript, [ref]$topologyTokens, [ref]$topologyErrors
)
if ($topologyErrors.Count -ne 0) {
    throw "Dynamic topology PowerShell parse failed: $($topologyErrors[0].Message)"
}

$adapter = Get-Content -Raw -LiteralPath $adapterPath
foreach ($token in @(
    'MesmerPrism/rusty-quest',
    '50a4c5222c9d6c4567bac09405e43049c61b126f',
    'ead3855a2ecc5e1240e271d81a938985457f10e8',
    'ExpectedVerifierBytes = 35452',
    'fcab9717b53bee594949d3d7ffc6126d91db0a4b7592241efab9f9cefcd5a5be',
    'refs/pull/\$PullRequestNumber/head',
    'refs/pull/\$PullRequestNumber/merge',
    '\[AllowEmptyString\(\)\]\[string\]\$EventMergeCommit = ""',
    '\$effectiveMergeCommit = \$fetchedMerge',
    'event-merge-observation-stale-fetched-ref-authoritative',
    'parents\.Count -ne 3',
    'WaitForExit\(30000\)',
    '\[void\]\$copy\.GetAwaiter\(\)\.GetResult\(\)',
    'Git process output exceeded 1 MiB',
    'Trusted base must be a standalone initialized checkout',
    'Trusted base contains replacement refs',
    'Event base repository differs from MesmerPrism/rusty-quest',
    'Event base ref differs from main',
    'Event head repository',
    'Test-ExternalValidationAuthority\.ps1',
    'event_identity =',
    'powershell =',
    'git =',
    'image_allowlist_enforced = \$false',
    'drift_status = "observed-unpinned"',
    'candidate_code_executed = \$false',
    'execution_attested = \$false',
    'publication_authority = \$false',
    'ExternalOwnerAuthorization\.psm1',
    'external-owner-authorization\.json',
    'external_owner_authorization_request',
    'https://api\.github\.com/repos/MesmerPrism/rusty-quest/issues/',
    'GITHUB_TOKEN',
    'Assert-ExternalOwnerFallbackVerifierFailure',
    'Pinned verifier emitted an assessment while failing',
    'ConvertFrom-ExternalOwnerGitNameStatusBytes',
    'Authorization artifact inventory is incomplete relative to Git name-status output',
    'Resolve-ExternalOwnerAdapterExitCode',
    'Rusty Quest assessment did not resolve to an accepted adapter exit\.',
    'exit \$adapterExitCode',
    '"--no-ext-diff"',
    'External-owner authorization is required; the canonical request was emitted\.',
    '\[IO\.FileMode\]::CreateNew'
)) {
    if ($adapter -notmatch $token) {
        throw "Base-owned adapter is missing contract token: $token"
    }
}
foreach ($forbidden in @(
    'Invoke-Expression',
    'Start-Process',
    'git\s+checkout',
    'git\s+switch',
    'gh\s+',
    'Invoke-WebRequest'
)) {
    if ($adapter -match $forbidden) {
        throw "Base-owned adapter contains forbidden execution route: $forbidden"
    }
}
if ($adapter -match '-match\s*\[regex\]::Escape\("Protected changes do not match an exact base-approved change set\."\)') {
    throw "Base-owned adapter retains substring-based protected-hold detection."
}
if ($adapter -match '(?m)^\s*exit\s+0\s*$') {
    throw "Base-owned adapter contains an unconditional success exit."
}
if ($adapter -notmatch 'Get-Command\s+pwsh' -or $adapter -notmatch '2>&1') {
    throw "Base-owned adapter must retain the bounded child-PowerShell verifier transport."
}
$externalOwnerModule = Get-Content -Raw -LiteralPath $externalOwnerModulePath
foreach ($token in @(
    'Assert-ExternalOwnerFallbackVerifierFailure',
    'Management\.Automation\.RemoteException',
    'NativeCommandError',
    'CategoryInfo\.Category',
    'transportMessage = "Exception: \$holdMessage`r`n"',
    'Test-ExternalOwnerExactUtf8TransportText',
    'StringComparison\]::Ordinal',
    'CryptographicOperations\]::FixedTimeEquals',
    'record\.GetType\(\) -eq \[Management\.Automation\.ErrorRecord\]',
    '\$VerifierHoldLine -eq 969',
    '\$escape = \[char\]27',
    'ConvertFrom-ExternalOwnerGitNameStatusBytes',
    'Git name-status output contains invalid UTF-8',
    'Git name-status output lacks a terminal NUL delimiter',
    'case-colliding path',
    'Assert-ExternalOwnerArtifactInventory',
    'artifact count differs from its path inventory',
    'bootstrap_comment_marker',
    'Bootstrap authorization markers are never accepted by the normal external-owner fallback\.'
)) {
    if ($externalOwnerModule -notmatch $token) {
        throw "External-owner module is missing fail-closed contract token: $token"
    }
}
$externalOwnerSelfTestText = Get-Content -Raw -LiteralPath $externalOwnerSelfTest
foreach ($token in @(
    'Invoke-ExternalOwnerChildFailureFixture',
    'Direct verifier hold behavior changed\.',
    'Exact Windows child verifier hold transport changed\.',
    'hold-lf',
    'hold-double-crlf',
    'hold-stdout-contamination',
    'hostedAnsiExpected',
    'ansiDamageCases',
    'Assert-ExternalOwnerClosedProfileDamageRejected',
    'name = "esc"',
    'name = "fqid-first"',
    'name = "fqid-later"',
    'name = "category"',
    'name = "target-first"',
    'name = "target-later"',
    'name = "mixed-plain"',
    'name = "partial"'
)) {
    if ($externalOwnerSelfTestText -notmatch $token) {
        throw "External-owner self-test is missing transport-bound regression coverage: $token"
    }
}

$portableTrimExpression = @'
$script:TrustedBase.TrimEnd([char[]]@('\', '/'))
'@
if (-not $adapter.Contains($portableTrimExpression.Trim())) {
    throw "Base-owned adapter must pass an explicit char array to TrimEnd."
}
if ($adapter.Contains('$script:TrustedBase.TrimEnd("\\", "/")')) {
    throw "Base-owned adapter retains the PowerShell 7.6-incompatible TrimEnd overload."
}
foreach ($trimProbe in @('C:\trusted\', 'C:\trusted/')) {
    if ($trimProbe.TrimEnd([char[]]@('\', '/')) -cne 'C:\trusted') {
        throw "PowerShell path-trimming regression probe failed."
    }
}

foreach ($scriptPath in @($adapterPath, $externalOwnerModulePath, $policySelfTest, $externalOwnerSelfTest, $externalOwnerBootstrapSelfTest, $PSCommandPath)) {
    $tokens = $null
    $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile(
        $scriptPath, [ref]$tokens, [ref]$errors
    )
    if ($errors.Count -ne 0) {
        throw "PowerShell parse failure in $scriptPath`: $($errors[0].Message)"
    }
}

$adapterAstErrors = $null
$adapterAstTokens = $null
$adapterAst = [Management.Automation.Language.Parser]::ParseFile(
    $adapterPath, [ref]$adapterAstTokens, [ref]$adapterAstErrors
)
if ($adapterAstErrors.Count -ne 0) {
    throw "Base-owned adapter AST parse failed: $($adapterAstErrors[0].Message)"
}
$byteFunctions = @($adapterAst.Find({
    param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -ceq "Invoke-BaseGitBytes"
}, $true))
if ($byteFunctions.Count -ne 1) {
    throw "Base-owned adapter must contain exactly one Git byte-stream function."
}
$byteFunctionText = [string]$byteFunctions[0].Extent.Text
if ($byteFunctionText -notmatch
    '^function\s+Invoke-BaseGitBytes\s*\{' -or
    $byteFunctionText -notmatch
    '\[void\]\$copy\.GetAwaiter\(\)\.GetResult\(\)') {
    throw "Base-owned Git byte-stream function does not suppress its non-generic task result."
}

function New-AdapterByteStreamProbeModule {
    param([Parameter(Mandatory = $true)][string]$FunctionDefinition)
    return New-Module -ScriptBlock {
        param([string]$TrustedBase, [string]$Definition)
        $script:TrustedBase = $TrustedBase
        . ([scriptblock]::Create($Definition))
    } -ArgumentList $RepoRoot, $FunctionDefinition
}

function Assert-AdapterGitByteStreamResult {
    param(
        [Parameter(Mandatory = $true)][object[]]$Values,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $types = @($Values | ForEach-Object { $_.GetType().FullName })
    if ($Values.Count -ne 1 -or $types[0] -cne "System.Byte[]") {
        throw "$Label must yield exactly one System.Byte[] pipeline result; observed $($types -join ', ')."
    }
    [byte[]]$bytes = $Values[0]
    if ($bytes.Length -eq 0 -or
        [Text.UTF8Encoding]::new($false, $true).GetString($bytes) -cnotmatch
        '^git version [0-9]') {
        throw "$Label did not preserve exact Git byte output."
    }
}

$probeFunctionText = $byteFunctionText.Replace(
    'function Invoke-BaseGitBytes', 'function Invoke-AdapterByteStreamProbeTarget'
)
$probeModule = New-AdapterByteStreamProbeModule $probeFunctionText
try {
    $probeValues = @(& $probeModule {
        Invoke-AdapterByteStreamProbeTarget -Arguments @("--version")
    })
    Assert-AdapterGitByteStreamResult -Values $probeValues `
        -Label "Base-owned Git byte stream"
} finally {
    Remove-Module -ModuleInfo $probeModule -Force -ErrorAction SilentlyContinue
}

$damagedFunctionText = $probeFunctionText.Replace(
    '[void]$copy.GetAwaiter().GetResult()', '$copy.GetAwaiter().GetResult()'
)
if ($damagedFunctionText -ceq $probeFunctionText) {
    throw "Git byte-stream damage fixture did not remove task-result suppression."
}
$damageModule = New-AdapterByteStreamProbeModule $damagedFunctionText
$damageRejected = $false
try {
    $damageValues = @(& $damageModule {
        Invoke-AdapterByteStreamProbeTarget -Arguments @("--version")
    })
    try {
        Assert-AdapterGitByteStreamResult -Values $damageValues `
            -Label "Damaged base-owned Git byte stream"
    } catch {
        if ($_.Exception.Message -cne
            "Damaged base-owned Git byte stream must yield exactly one System.Byte[] pipeline result; observed System.Threading.Tasks.VoidTaskResult, System.Byte[].") {
            throw "Git byte-stream damage rejected for the wrong reason: $($_.Exception.Message)"
        }
        $damageRejected = $true
    }
} finally {
    Remove-Module -ModuleInfo $damageModule -Force -ErrorAction SilentlyContinue
}
if (-not $damageRejected) {
    throw "Git byte-stream task-result damage was accepted."
}

function Assert-AdapterInputDamageRejected {
    param(
        [Parameter(Mandatory = $true)][string]$ParameterName,
        [Parameter(Mandatory = $true)][string]$DamagedValue,
        [Parameter(Mandatory = $true)][string]$ExpectedMessage
    )
    $invokeArguments = @{
        BaseRoot = $RepoRoot
        VerifierRoot = $RepoRoot
        Repository = "MesmerPrism/rusty-quest"
        BaseRepository = "MesmerPrism/rusty-quest"
        BaseRef = "main"
        HeadRepository = "example/fork"
        EventName = "pull_request_target"
        BaseCommit = "0" * 40
        CandidateCommit = "1" * 40
        EventMergeCommit = "2" * 40
        PullRequestNumber = "1"
        RunId = "1"
        RunAttempt = "1"
        RunnerLabel = "windows-2025"
        RunnerOs = "Windows"
        RunnerArchitecture = "X64"
        RunnerImageOs = "win25"
        RunnerImageVersion = "20260731.1.0"
        OutPath = Join-Path $env:TEMP "unused-external-assessment.json"
    }
    $invokeArguments[$ParameterName] = $DamagedValue
    $rejected = $false
    try {
        & $adapterPath @invokeArguments | Out-Null
    } catch {
        if ($_.Exception.Message -notmatch $ExpectedMessage) {
            throw "Adapter damage case rejected for the wrong reason: $($_.Exception.Message)"
        }
        $rejected = $true
    }
    if (-not $rejected) {
        throw "Adapter accepted damaged event input: $ParameterName"
    }
}

Assert-AdapterInputDamageRejected `
    -ParameterName "BaseRepository" `
    -DamagedValue "example/rusty-quest" `
    -ExpectedMessage "Event base repository differs"
Assert-AdapterInputDamageRejected `
    -ParameterName "BaseRef" `
    -DamagedValue "develop" `
    -ExpectedMessage "Event base ref differs"
Assert-AdapterInputDamageRejected `
    -ParameterName "HeadRepository" `
    -DamagedValue "malformed head" `
    -ExpectedMessage "head repository.*canonical repository identity"
Assert-AdapterInputDamageRejected `
    -ParameterName "RunnerLabel" `
    -DamagedValue "windows-latest" `
    -ExpectedMessage "Runner label differs"

$zeros = "0" * 40
$hashes = "0" * 64
$schemaFixture = [pscustomobject][ordered]@{
    schema = "rusty.quest.external_validation_authority_assessment.v1"
    policy_id = "rusty-quest-external-validation-authority-v1"
    policy_sha256 = $hashes
    repository = "MesmerPrism/rusty-quest"
    pull_request_number = 1
    event_identity = [pscustomobject][ordered]@{
        base_repository = "MesmerPrism/rusty-quest"
        base_ref = "main"
        head_repository = "example/fork"
        merge_commit_observation = $null
        merge_commit_relation = "event-merge-observation-absent"
    }
    workflow = [pscustomobject][ordered]@{
        event = "pull_request_target"
        run_id = "1"
        run_attempt = 1
    }
    runtime = [pscustomobject][ordered]@{
        powershell = [pscustomobject][ordered]@{
            edition = "Core"
            version = "7.6.0"
            executable_bytes = 1
            executable_sha256 = $hashes
        }
        git = [pscustomobject][ordered]@{
            version = "git version 2.52.0.windows.1"
            executable_bytes = 1
            executable_sha256 = $hashes
        }
        runner = [pscustomobject][ordered]@{
            label = "windows-2025"
            os = "Windows"
            architecture = "X64"
            image_os = "win25"
            image_version = "20260731.1.0"
            image_allowlist_enforced = $false
            drift_status = "observed-unpinned"
        }
    }
    base = [pscustomobject][ordered]@{ commit = $zeros; tree = $zeros }
    candidate = [pscustomobject][ordered]@{ commit = $zeros; tree = $zeros }
    merge = [pscustomobject][ordered]@{ commit = $zeros; tree = $zeros }
    changed_paths = @()
    protected_paths = @()
    decision = "unprotected"
    approval_id = $null
    candidate_code_executed = $false
    execution_attested = $false
    publication_authority = $false
    limitations = @(
        "Static admission only; no candidate code was executed.",
        "Execution, tests, and owner-effect evidence require separate trusted validation.",
        "This assessment does not authorize publication.",
        "Runner image and tool identities are observed exactly but not allowlisted."
    )
}
$schemaJson = $schemaFixture | ConvertTo-Json -Depth 20
if (-not (Test-Json -Json $schemaJson -SchemaFile $schemaPath -ErrorAction Stop)) {
    throw "Repository-specific external validation schema rejected its fixture."
}
$baseIdentityDamage = $schemaJson | ConvertFrom-Json -Depth 30
$baseIdentityDamage.event_identity.base_ref = "develop"
$mergeRelationDamage = $schemaJson | ConvertFrom-Json -Depth 30
$mergeRelationDamage.event_identity.merge_commit_relation = "event-merge-authoritative"
$runnerAllowlistDamage = $schemaJson | ConvertFrom-Json -Depth 30
$runnerAllowlistDamage.runtime.runner.image_allowlist_enforced = $true
$authorityDamage = $schemaJson | ConvertFrom-Json -Depth 30
$authorityDamage.publication_authority = $true
foreach ($damage in @(
    [pscustomobject]@{ name = "base-ref"; value = $baseIdentityDamage },
    [pscustomobject]@{ name = "merge-relation"; value = $mergeRelationDamage },
    [pscustomobject]@{ name = "runner-allowlist"; value = $runnerAllowlistDamage },
    [pscustomobject]@{ name = "publication-authority"; value = $authorityDamage }
)) {
    $damagedJson = $damage.value | ConvertTo-Json -Depth 30
    if (Test-Json -Json $damagedJson -SchemaFile $schemaPath `
        -ErrorAction SilentlyContinue) {
        throw "External validation schema accepted damaged case: $($damage.name)"
    }
}

$settingsPathDamage = $settingsJson | ConvertFrom-Json -Depth 30
$settingsPathDamage.authoritative_workflow_identity.path = `
    ".github/workflows/candidate.yml"
$pathClaimDamage = $settingsJson | ConvertFrom-Json -Depth 30
$pathClaimDamage.authoritative_workflow_identity.settings_bind_workflow_path = $true
$probeOptionalDamage = $settingsJson | ConvertFrom-Json -Depth 30
$probeOptionalDamage.adversarial_probe.required_before_candidate_or_release = $false
$fallbackAuthorityDamage = $settingsJson | ConvertFrom-Json -Depth 30
$fallbackAuthorityDamage.required_status_check.authoritative_without_probe = $true
$appDamage = $settingsJson | ConvertFrom-Json -Depth 30
$appDamage.required_status_check.app_id = 1
$futureModeDamage = $settingsJson | ConvertFrom-Json -Depth 30
$futureModeDamage.future_stronger_mode.deployable_for_current_owner = $true
foreach ($damage in @(
    [pscustomobject]@{ name = "workflow-path"; value = $settingsPathDamage },
    [pscustomobject]@{ name = "path-provenance-claim"; value = $pathClaimDamage },
    [pscustomobject]@{ name = "probe-optional"; value = $probeOptionalDamage },
    [pscustomobject]@{ name = "fallback-authority"; value = $fallbackAuthorityDamage },
    [pscustomobject]@{ name = "app-integration"; value = $appDamage },
    [pscustomobject]@{ name = "future-mode-deployable"; value = $futureModeDamage }
)) {
    $damagedJson = $damage.value | ConvertTo-Json -Depth 30
    if (Test-Json -Json $damagedJson -SchemaFile $settingsSchemaPath `
        -ErrorAction SilentlyContinue) {
        throw "Authority settings schema accepted damaged case: $($damage.name)"
    }
}

$inventedPassDamage = $probeReceiptJson | ConvertFrom-Json -Depth 40
$inventedPassDamage.decision = "pass"
$inventedPassDamage.hold_reasons = @()
$mergeablePassDamage = $probeReceiptJson | ConvertFrom-Json -Depth 40
$mergeablePassDamage.evidence_origin = "observed"
$mergeablePassDamage.decision = "pass"
$mergeablePassDamage.hold_reasons = @()
$mergeablePassDamage.inventory.base_and_candidate_runs_proven = $true
$mergeablePassDamage.inventory.mappings_distinguishable = $true
$mergeablePassDamage.merge_gate.merge_state_status = "CLEAN"
foreach ($damage in @(
    [pscustomobject]@{ name = "synthetic-pass"; value = $inventedPassDamage },
    [pscustomobject]@{ name = "mergeable-pass"; value = $mergeablePassDamage }
)) {
    $damagedJson = $damage.value | ConvertTo-Json -Depth 40
    if (Test-Json -Json $damagedJson -SchemaFile $probeReceiptSchemaPath `
        -ErrorAction SilentlyContinue) {
        throw "Probe receipt schema accepted damaged case: $($damage.name)"
    }
}

& $policySelfTest `
    -RepoRoot $RepoRoot `
    -ExpectedBootstrapApprovalAncestor $ExpectedBootstrapApprovalAncestor
& $externalOwnerSelfTest -RepoRoot $RepoRoot
& $externalOwnerBootstrapSelfTest -RepoRoot $RepoRoot
Write-Output "External validation authority static contract passed."
