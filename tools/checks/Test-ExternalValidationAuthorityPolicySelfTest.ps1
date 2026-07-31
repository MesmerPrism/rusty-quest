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
$policyPath = Join-Path $RepoRoot "config\external-validation-authority.json"
$policy = Get-Content -Raw -LiteralPath $policyPath | ConvertFrom-Json -Depth 40
$approvalFixturePath = Join-Path $RepoRoot `
    "fixtures\validation-authority\bootstrap-approval.valid.json"
if (-not (Test-Path -LiteralPath $approvalFixturePath -PathType Leaf)) {
    throw "Bootstrap approval fixture is missing."
}
$approvalFixture = Get-Content -Raw -LiteralPath $approvalFixturePath |
    ConvertFrom-Json -Depth 40

function Test-OrdinalEqual {
    param([string[]]$Left, [string[]]$Right)
    if ($Left.Count -ne $Right.Count) { return $false }
    for ($index = 0; $index -lt $Left.Count; $index++) {
        if (-not [string]::Equals(
            $Left[$index], $Right[$index], [StringComparison]::Ordinal
        )) {
            return $false
        }
    }
    return $true
}

function Assert-SortedUnique {
    param([string[]]$Values, [string]$Label)
    [string[]]$sorted = @($Values)
    [Array]::Sort($sorted, [StringComparer]::Ordinal)
    if (-not (Test-OrdinalEqual $Values $sorted)) {
        throw "$Label must be ordinally sorted."
    }
    for ($index = 1; $index -lt $Values.Count; $index++) {
        if ($Values[$index - 1] -ceq $Values[$index]) {
            throw "$Label contains a duplicate."
        }
    }
}

function Assert-ExactProperties {
    param([object]$Value, [string[]]$Expected, [string]$Label)
    [string[]]$actual = @($Value.PSObject.Properties.Name)
    [string[]]$sortedActual = @($actual)
    [string[]]$sortedExpected = @($Expected)
    [Array]::Sort($sortedActual, [StringComparer]::Ordinal)
    [Array]::Sort($sortedExpected, [StringComparer]::Ordinal)
    if (-not (Test-OrdinalEqual $sortedActual $sortedExpected)) {
        throw "$Label properties differ from the closed contract."
    }
}

function Assert-PortablePath {
    param([string]$Path, [string]$Label, [switch]$Prefix)
    $candidate = $Path
    if ($Prefix) {
        if (-not $candidate.EndsWith("/", [StringComparison]::Ordinal)) {
            throw "$Label prefix must end with '/'."
        }
        $candidate = $candidate.Substring(0, $candidate.Length - 1)
    }
    if (
        [string]::IsNullOrWhiteSpace($candidate) -or
        [IO.Path]::IsPathRooted($candidate) -or
        $candidate.Contains("\") -or
        $candidate.Contains(":") -or
        $candidate -match "[\x00-\x1f\x7f]" -or
        @($candidate.Split("/") | Where-Object {
            [string]::IsNullOrEmpty($_) -or $_ -in @(".", "..")
        }).Count -ne 0
    ) {
        throw "$Label is not a portable relative path."
    }
}

function Test-ProtectedPath {
    param([object]$CandidatePolicy, [string]$Path)
    if (@($CandidatePolicy.mandatory_protected_paths) -ccontains $Path) {
        return $true
    }
    foreach ($rule in @($CandidatePolicy.protected_rules)) {
        if (
            ($rule.match -ceq "exact" -and $rule.path -ceq $Path) -or
            (
                $rule.match -ceq "prefix" -and
                $Path.StartsWith(
                    [string]$rule.path, [StringComparison]::Ordinal
                )
            )
        ) {
            return $true
        }
    }
    return $false
}

$expectedMandatory = @(
    ".github/scripts/Invoke-RustyQuestExternalValidationAuthority.ps1",
    ".github/workflows/external-validation-authority.yml",
    ".github/workflows/package-updater-dynamic-validation.yml",
    "config/external-validation-authority-settings.json",
    "config/external-validation-authority.json",
    "fixtures/validation-authority/adversarial-probe-receipt.hold.json",
    "fixtures/validation-authority/bootstrap-approval.valid.json",
    "schemas/rusty.quest.external_validation_authority_assessment.v1.schema.json",
    "schemas/rusty.quest.external_validation_authority_probe_receipt.v1.schema.json",
    "schemas/rusty.quest.external_validation_authority_settings.v1.schema.json",
    "tools/checks/Test-ExternalValidationAuthorityPolicySelfTest.ps1",
    "tools/checks/Test-ExternalValidationAuthorityStatic.ps1"
)
$sensitivePaths = @(
    ".github/scripts/Invoke-RustyQuestExternalValidationAuthority.ps1",
    ".github/workflows/external-validation-authority.yml",
    ".github/workflows/package-updater-dynamic-validation.yml",
    ".github/workflows/package-updater-labs-release.yml",
    "Cargo.lock",
    "Cargo.toml",
    "apps/package-updater-android/app/build.gradle.kts",
    "config/external-validation-authority.json",
    "config/external-validation-authority-settings.json",
    "crates/rusty-quest-package-updater/src/lib.rs",
    "distribution/package-update-labs-target.json",
    "fixtures/damaged/package-updater-bad-signature.json",
    "fixtures/damaged/package-updater-expired.json",
    "fixtures/damaged/package-updater-origin-confusion.json",
    "fixtures/damaged/package-updater-padded-signature.json",
    "fixtures/damaged/package-updater-sequence-rollback.json",
    "fixtures/damaged/package-updater-unknown-field.json",
    "fixtures/package-updater/policy.valid.json",
    "fixtures/validation-authority/adversarial-probe-receipt.hold.json",
    "fixtures/validation-authority/bootstrap-approval.valid.json",
    "schemas/rusty.quest.external_validation_authority_assessment.v1.schema.json",
    "schemas/rusty.quest.external_validation_authority_probe_receipt.v1.schema.json",
    "schemas/rusty.quest.external_validation_authority_settings.v1.schema.json",
    "schemas/rusty.quest.package_update_manifest_envelope.v1.schema.json",
    "schemas/rusty.quest.package_update_receipt.v1.schema.json",
    "schemas/rusty.quest.package_update_rollback_state.v1.schema.json",
    "tools/Build-PackageUpdaterAndroid.ps1",
    "tools/Invoke-PackageUpdaterE2eCli.ps1",
    "tools/New-PackageUpdaterProductReleaseMetadata.ps1",
    "tools/Publish-PackageUpdateLabsPages.ps1",
    "tools/Publish-PackageUpdateManifest.ps1",
    "tools/Test-PackageUpdaterProductReleaseMetadata.ps1",
    "tools/check_all.ps1",
    "tools/checks/Test-ExternalValidationAuthorityPolicySelfTest.ps1",
    "tools/checks/Test-ExternalValidationAuthorityStatic.ps1",
    "tools/checks/Test-PackageUpdateLabsPagesWorkflow.ps1",
    "tools/checks/Test-PackageUpdatePublicationContract.ps1",
    "tools/checks/Test-PackageUpdaterAndroidStatic.ps1",
    "tools/checks/Test-PackageUpdaterBuildArtifactContract.ps1",
    "tools/checks/Test-PackageUpdaterLabsReleaseWorkflow.ps1",
    "tools/checks/Test-PackageUpdaterProductReleaseContract.ps1",
    "tools/package_updater/PublicationContract.ps1"
)

function Assert-BootstrapApproval {
    param(
        [object[]]$Approvals,
        [string]$ExpectedAncestor = ""
    )
    if (-not [string]::IsNullOrEmpty($ExpectedAncestor) -and
        $ExpectedAncestor -cnotmatch '^(?:[0-9a-f]{40}|[0-9a-f]{64})$') {
        throw "Expected bootstrap ancestor is not a canonical Git object ID."
    }
    if ($Approvals.Count -eq 0) {
        if (-not [string]::IsNullOrEmpty($ExpectedAncestor)) {
            throw "Sealed candidate I requires exactly one bootstrap approval."
        }
        return
    }
    if ($Approvals.Count -ne 1) {
        throw "Bootstrap may carry exactly one one-use approval."
    }
    if ([string]::IsNullOrEmpty($ExpectedAncestor)) {
        throw "A live bootstrap approval requires the expected sealed candidate I."
    }

    $approval = $Approvals[0]
    Assert-ExactProperties $approval @(
        "approval_id",
        "required_ancestor",
        "changed_paths",
        "artifacts",
        "status"
    ) "Bootstrap approval"
    if ($approval.approval_id -cne "bootstrap-sealed-candidate-i" -or
        $approval.status -cne "approved") {
        throw "Bootstrap approval identity or status differs."
    }
    $ancestor = [string]$approval.required_ancestor
    if ($ancestor -cnotmatch '^(?:[0-9a-f]{40}|[0-9a-f]{64})$') {
        throw "Bootstrap approval ancestor is not a canonical Git object ID."
    }
    if (-not [string]::IsNullOrEmpty($ExpectedAncestor) -and
        $ancestor -cne $ExpectedAncestor) {
        throw "Bootstrap approval does not bind sealed candidate I."
    }

    [string[]]$changedPaths = @(
        $approval.changed_paths | ForEach-Object { [string]$_ }
    )
    if ($changedPaths.Count -lt 1 -or $changedPaths.Count -gt 512) {
        throw "Bootstrap approval changed-path count is out of bounds."
    }
    Assert-SortedUnique $changedPaths "Bootstrap approval changed paths"
    foreach ($path in $changedPaths) {
        Assert-PortablePath $path "Bootstrap approval changed path"
    }

    $artifacts = @($approval.artifacts)
    if ($artifacts.Count -ne $changedPaths.Count) {
        throw "Bootstrap approval artifact count differs from changed paths."
    }
    [string[]]$artifactPaths = @(
        $artifacts | ForEach-Object { [string]$_.path }
    )
    Assert-SortedUnique $artifactPaths "Bootstrap approval artifact paths"
    if (-not (Test-OrdinalEqual $changedPaths $artifactPaths)) {
        throw "Bootstrap approval artifact paths differ from changed paths."
    }

    [int64]$totalPresentBytes = 0
    foreach ($artifact in $artifacts) {
        Assert-PortablePath ([string]$artifact.path) `
            "Bootstrap approval artifact path"
        if ($artifact.state -ceq "present") {
            Assert-ExactProperties $artifact @(
                "path", "state", "mode", "size_bytes", "sha256"
            ) "Present bootstrap artifact"
            if ($artifact.mode -cnotin @("100644", "100755")) {
                throw "Present bootstrap artifact mode differs."
            }
            if (-not (
                $artifact.size_bytes -is [int] -or
                $artifact.size_bytes -is [long]
            )) {
                throw "Present bootstrap artifact size must be an integer."
            }
            [int64]$size = $artifact.size_bytes
            if ($size -lt 0 -or $size -gt 16777216) {
                throw "Present bootstrap artifact size is out of bounds."
            }
            if ([string]$artifact.sha256 -cnotmatch '^[0-9a-f]{64}$') {
                throw "Present bootstrap artifact SHA-256 is malformed."
            }
            $totalPresentBytes += $size
        } elseif ($artifact.state -ceq "absent") {
            Assert-ExactProperties $artifact @("path", "state") `
                "Absent bootstrap artifact"
        } else {
            throw "Bootstrap artifact state is unsupported."
        }
    }
    if ($totalPresentBytes -gt 67108864) {
        throw "Bootstrap approval exceeds the 64 MiB present-content budget."
    }
}

function Assert-Policy {
    param(
        [object]$CandidatePolicy,
        [string]$ExpectedAncestor = ""
    )
    if (
        $CandidatePolicy.schema -cne `
            "rusty.morphospace.workflow.external_validation_authority_policy.v1" -or
        $CandidatePolicy.policy_id -cne `
            "rusty-quest-external-validation-authority-v1" -or
        $CandidatePolicy.repository -cne "MesmerPrism/rusty-quest" -or
        $CandidatePolicy.status -cne "active"
    ) {
        throw "Policy identity is not the active Rusty Quest authority."
    }
    Assert-BootstrapApproval @($CandidatePolicy.approved_change_sets) `
        -ExpectedAncestor $ExpectedAncestor
    [string[]]$mandatory = @(
        $CandidatePolicy.mandatory_protected_paths | ForEach-Object { [string]$_ }
    )
    Assert-SortedUnique $mandatory "Mandatory paths"
    if (-not (Test-OrdinalEqual $mandatory $expectedMandatory)) {
        throw "Mandatory authority paths differ from the closed bootstrap set."
    }
    [string[]]$ruleIds = @(
        $CandidatePolicy.protected_rules | ForEach-Object { [string]$_.rule_id }
    )
    Assert-SortedUnique $ruleIds "Rule IDs"
    foreach ($rule in @($CandidatePolicy.protected_rules)) {
        if ($rule.match -ceq "exact") {
            Assert-PortablePath ([string]$rule.path) "Exact protected rule"
        } elseif ($rule.match -ceq "prefix") {
            Assert-PortablePath ([string]$rule.path) "Prefix protected rule" -Prefix
        } else {
            throw "Protected rule match is unsupported."
        }
    }
    foreach ($path in $sensitivePaths) {
        if (-not (Test-ProtectedPath $CandidatePolicy $path)) {
            throw "Sensitive updater or validation authority path is unprotected: $path"
        }
    }
    foreach ($path in @(
        "README.md",
        "docs/ARCHITECTURE.md",
        "crates/rusty-quest-media-stream/src/lib.rs"
    )) {
        if (Test-ProtectedPath $CandidatePolicy $path) {
            throw "Ordinary Quest path is unexpectedly protected: $path"
        }
    }
}

Assert-Policy $policy -ExpectedAncestor $ExpectedBootstrapApprovalAncestor

$fixturePolicy = $policy | ConvertTo-Json -Depth 40 |
    ConvertFrom-Json -Depth 40
$fixturePolicy.approved_change_sets = @($approvalFixture)
Assert-Policy $fixturePolicy -ExpectedAncestor ("a" * 40)

$approvalDamage = $policy | ConvertTo-Json -Depth 40 | ConvertFrom-Json -Depth 40
$approvalDamage.approved_change_sets = @([pscustomobject]@{ approval_id = "damage" })
$missingExpectedDamage = $fixturePolicy | ConvertTo-Json -Depth 40 |
    ConvertFrom-Json -Depth 40
$missingApprovalDamage = $policy | ConvertTo-Json -Depth 40 |
    ConvertFrom-Json -Depth 40
$secondApprovalDamage = $fixturePolicy | ConvertTo-Json -Depth 40 |
    ConvertFrom-Json -Depth 40
$secondApprovalDamage.approved_change_sets = @(
    $secondApprovalDamage.approved_change_sets[0],
    $secondApprovalDamage.approved_change_sets[0]
)
$wrongIdDamage = $fixturePolicy | ConvertTo-Json -Depth 40 |
    ConvertFrom-Json -Depth 40
$wrongIdDamage.approved_change_sets[0].approval_id = "bootstrap-other"
$wrongAncestorDamage = $fixturePolicy | ConvertTo-Json -Depth 40 |
    ConvertFrom-Json -Depth 40
$wrongAncestorDamage.approved_change_sets[0].required_ancestor = "c" * 40
$pathMismatchDamage = $fixturePolicy | ConvertTo-Json -Depth 40 |
    ConvertFrom-Json -Depth 40
$pathMismatchDamage.approved_change_sets[0].artifacts[1].path = "tools/other.ps1"
$pathOrderDamage = $fixturePolicy | ConvertTo-Json -Depth 40 |
    ConvertFrom-Json -Depth 40
$pathOrderDamage.approved_change_sets[0].changed_paths = @(
    $pathOrderDamage.approved_change_sets[0].changed_paths[1],
    $pathOrderDamage.approved_change_sets[0].changed_paths[0]
)
$artifactOrderDamage = $fixturePolicy | ConvertTo-Json -Depth 40 |
    ConvertFrom-Json -Depth 40
$artifactOrderDamage.approved_change_sets[0].artifacts = @(
    $artifactOrderDamage.approved_change_sets[0].artifacts[1],
    $artifactOrderDamage.approved_change_sets[0].artifacts[0]
)
$modeDamage = $fixturePolicy | ConvertTo-Json -Depth 40 |
    ConvertFrom-Json -Depth 40
$modeDamage.approved_change_sets[0].artifacts[0].mode = "100600"
$hashDamage = $fixturePolicy | ConvertTo-Json -Depth 40 |
    ConvertFrom-Json -Depth 40
$hashDamage.approved_change_sets[0].artifacts[0].sha256 = "B" * 64
$sizeDamage = $fixturePolicy | ConvertTo-Json -Depth 40 |
    ConvertFrom-Json -Depth 40
$sizeDamage.approved_change_sets[0].artifacts[0].size_bytes = 16777217
$propertyDamage = $fixturePolicy | ConvertTo-Json -Depth 40 |
    ConvertFrom-Json -Depth 40
$propertyDamage.approved_change_sets[0] |
    Add-Member -NotePropertyName "reviewer" -NotePropertyValue "unbound"
$githubDamage = $policy | ConvertTo-Json -Depth 40 | ConvertFrom-Json -Depth 40
$githubDamage.protected_rules = @(
    $githubDamage.protected_rules | Where-Object { $_.rule_id -cne "github-authority" }
)
$mandatoryDamage = $policy | ConvertTo-Json -Depth 40 | ConvertFrom-Json -Depth 40
$mandatoryDamage.mandatory_protected_paths = @(
    $mandatoryDamage.mandatory_protected_paths | Where-Object {
        $_ -cne "config/external-validation-authority.json"
    }
)
$orderDamage = $policy | ConvertTo-Json -Depth 40 | ConvertFrom-Json -Depth 40
$orderDamage.protected_rules = @($orderDamage.protected_rules)[($orderDamage.protected_rules.Count - 1)..0]

foreach ($case in @(
    [pscustomobject]@{ name = "approval-shape"; policy = $approvalDamage; expected = "" },
    [pscustomobject]@{ name = "missing-expected-ancestor"; policy = $missingExpectedDamage; expected = "" },
    [pscustomobject]@{ name = "missing-sealed-approval"; policy = $missingApprovalDamage; expected = ("a" * 40) },
    [pscustomobject]@{ name = "second-approval"; policy = $secondApprovalDamage; expected = ("a" * 40) },
    [pscustomobject]@{ name = "approval-id"; policy = $wrongIdDamage; expected = ("a" * 40) },
    [pscustomobject]@{ name = "approval-ancestor"; policy = $wrongAncestorDamage; expected = ("a" * 40) },
    [pscustomobject]@{ name = "artifact-path"; policy = $pathMismatchDamage; expected = ("a" * 40) },
    [pscustomobject]@{ name = "changed-path-order"; policy = $pathOrderDamage; expected = ("a" * 40) },
    [pscustomobject]@{ name = "artifact-order"; policy = $artifactOrderDamage; expected = ("a" * 40) },
    [pscustomobject]@{ name = "artifact-mode"; policy = $modeDamage; expected = ("a" * 40) },
    [pscustomobject]@{ name = "artifact-hash"; policy = $hashDamage; expected = ("a" * 40) },
    [pscustomobject]@{ name = "artifact-size"; policy = $sizeDamage; expected = ("a" * 40) },
    [pscustomobject]@{ name = "approval-property"; policy = $propertyDamage; expected = ("a" * 40) },
    [pscustomobject]@{ name = "github-protection"; policy = $githubDamage; expected = "" },
    [pscustomobject]@{ name = "mandatory-path"; policy = $mandatoryDamage; expected = "" },
    [pscustomobject]@{ name = "rule-order"; policy = $orderDamage; expected = "" }
)) {
    $rejected = $false
    try {
        Assert-Policy $case.policy -ExpectedAncestor $case.expected
    } catch {
        $rejected = $true
    }
    if (-not $rejected) {
        throw "Damaged policy case was accepted: $($case.name)"
    }
}

Write-Output "External validation authority policy self-test passed."
