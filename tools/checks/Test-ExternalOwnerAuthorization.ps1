[CmdletBinding()]
param([string]$RepoRoot = "")
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path } else { $RepoRoot = (Resolve-Path $RepoRoot).Path }
Import-Module (Join-Path $RepoRoot ".github\scripts\lib\ExternalOwnerAuthorization.psm1") -Force
$authorizationSchema = Join-Path $RepoRoot "schemas\rusty.quest.external_owner_authorization.v1.schema.json"
$policySchema = Join-Path $RepoRoot "schemas\rusty.quest.external_owner_authorization_policy.v1.schema.json"
$policy = Read-ExternalOwnerAuthorizationPolicy (Join-Path $RepoRoot "config\external-owner-authorization.json") $policySchema

function Assert-DamageRejected {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][scriptblock]$Action)
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (-not $rejected) { throw "External-owner damage was accepted: $Name" }
}

$rsa = [Security.Cryptography.RSA]::Create(3072)
try {
    $now = [datetimeoffset]::Parse("2026-08-22T16:00:00Z")
    $artifacts = @([ordered]@{ path = "config/external-validation-authority.json"; state = "present"; mode = "100644"; size_bytes = 3; sha256 = ("a" * 64) })
    $assessment = [ordered]@{ schema = "rusty.quest.external_validation_authority_assessment.v1"; decision = "protected-without-base-approval"; candidate_code_executed = $false; execution_attested = $false; publication_authority = $false; limitations = @("Static admission only; no candidate code was executed.", "Execution, tests, and owner-effect evidence require separate trusted validation.", "This assessment does not authorize publication.", "Runner image and tool identities are observed exactly but not allowlisted.") }
    $policyPath = Join-Path $RepoRoot "config\external-validation-authority.json"
    [byte[]]$policyBytes = [IO.File]::ReadAllBytes($policyPath)
    $authorityPolicy = [Text.UTF8Encoding]::new($false, $true).GetString($policyBytes) |
        ConvertFrom-Json -Depth 30
    $baseIdentity = [ordered]@{ commit = ("1" * 40); tree = ("2" * 40) }
    $headIdentity = [ordered]@{ commit = ("3" * 40); tree = ("4" * 40) }
    $hold = New-ExternalOwnerProtectedWithoutBaseApprovalAssessment `
        -Policy $authorityPolicy -PolicyBytes $policyBytes -Base $baseIdentity `
        -Candidate $headIdentity -ChangedArtifacts $artifacts `
        -ProtectedArtifacts $artifacts
    $holdDecisionDamage = $hold | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $holdDecisionDamage.decision = "unprotected"
    $holdApprovalDamage = $hold | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $holdApprovalDamage.approval_id = "unexpected-approval"
    $holdInventoryDamage = $hold | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $holdInventoryDamage.changed_paths = @()
    foreach ($damage in @(
        [pscustomobject]@{ name = "hold-decision"; value = $holdDecisionDamage },
        [pscustomobject]@{ name = "hold-approval"; value = $holdApprovalDamage },
        [pscustomobject]@{ name = "hold-inventory"; value = $holdInventoryDamage }
    )) {
        Assert-DamageRejected $damage.name {
            Assert-ExternalOwnerProtectedWithoutBaseApprovalAssessment `
                -Assessment $damage.value -Policy $authorityPolicy -PolicyBytes $policyBytes `
                -Base $baseIdentity -Candidate $headIdentity `
                -ChangedArtifacts $artifacts -ProtectedArtifacts $artifacts
        }
    }
    $holdMessage = "Protected changes do not match an exact base-approved change set."
    $validHoldError = [Management.Automation.ErrorRecord]::new(
        [Management.Automation.RuntimeException]::new($holdMessage),
        "PinnedVerifier", [Management.Automation.ErrorCategory]::NotSpecified, $null
    )
    Assert-ExternalOwnerFallbackVerifierFailure -ErrorRecord $validHoldError -Output @() `
        -AssessmentOutputExists $false
    $exactHoldResult = Invoke-ExternalOwnerFallbackVerifier -Invocation ({
        throw [Management.Automation.RuntimeException]::new($holdMessage)
    }.GetNewClosure())
    if ($exactHoldResult.succeeded -or -not $exactHoldResult.external_owner_hold -or
        @($exactHoldResult.output).Count -ne 0) {
        throw "Exact in-process protected hold was not accepted."
    }
    $successResult = Invoke-ExternalOwnerFallbackVerifier -Invocation ({
        Write-Output "pinned-verifier-success"
    })
    if (-not $successResult.succeeded -or $successResult.external_owner_hold -or
        @($successResult.output).Count -ne 1 -or
        [string]$successResult.output[0] -cne "pinned-verifier-success") {
        throw "In-process pinned verifier success behavior changed."
    }
    foreach ($damage in @(
        [pscustomobject]@{ name = "hold-prefix"; action = ({ throw [Management.Automation.RuntimeException]::new("prefix $holdMessage") }.GetNewClosure()) },
        [pscustomobject]@{ name = "hold-suffix"; action = ({ throw [Management.Automation.RuntimeException]::new("$holdMessage suffix") }.GetNewClosure()) },
        [pscustomobject]@{ name = "hold-wrong-message"; action = ({ throw [Management.Automation.RuntimeException]::new("Unexpected verifier failure.") }) },
        [pscustomobject]@{ name = "hold-unexpected-exception"; action = ({ throw [Exception]::new($holdMessage) }.GetNewClosure()) },
        [pscustomobject]@{ name = "hold-runtime-subclass"; action = ({ throw [Management.Automation.CommandNotFoundException]::new($holdMessage) }.GetNewClosure()) },
        [pscustomobject]@{ name = "hold-partial-output"; action = ({ Write-Output "partial-output"; throw [Management.Automation.RuntimeException]::new($holdMessage) }.GetNewClosure()) }
    )) {
        Assert-DamageRejected $damage.name {
            $null = Invoke-ExternalOwnerFallbackVerifier -Invocation $damage.action
        }
    }
    $emittedAssessment = Join-Path ([IO.Path]::GetTempPath()) (
        "rusty-quest-partial-assessment-" + [Guid]::NewGuid().ToString("N") + ".json"
    )
    try {
        $emittingInvocation = ({
            [IO.File]::WriteAllText(
                $emittedAssessment, "{}", [Text.UTF8Encoding]::new($false)
            )
            throw [Management.Automation.RuntimeException]::new($holdMessage)
        }.GetNewClosure())
        Assert-DamageRejected "hold-emitted-assessment" {
            $null = Invoke-ExternalOwnerFallbackVerifier `
                -Invocation $emittingInvocation -AssessmentOutputPath $emittedAssessment
        }
        if (-not (Test-Path -LiteralPath $emittedAssessment -PathType Leaf)) {
            throw "Partial-assessment damage fixture did not materialize its assessment."
        }
    } finally {
        if (Test-Path -LiteralPath $emittedAssessment -PathType Leaf) {
            Remove-Item -LiteralPath $emittedAssessment -Force
        }
    }
    $null = ConvertFrom-ExternalOwnerGitNameStatusBytes ([Text.Encoding]::UTF8.GetBytes(
        "A" + [char]0 + "config/external-validation-authority.json" + [char]0
    ))
    foreach ($damage in @(
        [pscustomobject]@{ name = "invalid-utf8"; bytes = [byte[]]@(65, 0, 255, 0) },
        [pscustomobject]@{ name = "missing-terminal-delimiter"; bytes = [Text.Encoding]::UTF8.GetBytes("A" + [char]0 + "config/external-validation-authority.json") },
        [pscustomobject]@{ name = "backslash-path"; bytes = [Text.Encoding]::UTF8.GetBytes("A" + [char]0 + "dir" + [char]92 + "file.ps1" + [char]0) },
        [pscustomobject]@{ name = "duplicate-path"; bytes = [Text.Encoding]::UTF8.GetBytes("A" + [char]0 + "config/external-validation-authority.json" + [char]0 + "M" + [char]0 + "config/external-validation-authority.json" + [char]0) },
        [pscustomobject]@{ name = "case-colliding-path"; bytes = [Text.Encoding]::UTF8.GetBytes("A" + [char]0 + "Config/external-validation-authority.json" + [char]0 + "M" + [char]0 + "config/external-validation-authority.json" + [char]0) }
    )) {
        Assert-DamageRejected $damage.name {
            $null = ConvertFrom-ExternalOwnerGitNameStatusBytes $damage.bytes
        }
    }
    $secondArtifact = [ordered]@{ path = "tools/other.ps1"; state = "absent" }
    Assert-DamageRejected "incomplete-changed-inventory" {
        Assert-ExternalOwnerArtifactInventory `
            -ChangedPaths @($artifacts[0].path, $secondArtifact.path) `
            -ChangedArtifacts $artifacts -ProtectedPaths @($artifacts[0].path) `
            -ProtectedArtifacts $artifacts
    }
    Assert-DamageRejected "incomplete-protected-inventory" {
        Assert-ExternalOwnerArtifactInventory `
            -ChangedPaths @($artifacts[0].path, $secondArtifact.path) `
            -ChangedArtifacts @($artifacts[0], $secondArtifact) `
            -ProtectedPaths @($secondArtifact.path) -ProtectedArtifacts $artifacts
    }
    $request = New-ExternalOwnerAuthorizationRequest -Policy $policy -PullRequestNumber 53 -Base ([ordered]@{commit=("1"*40);tree=("2"*40)}) -Head ([ordered]@{commit=("3"*40);tree=("4"*40)}) -ChangedArtifacts $artifacts -ProtectedArtifacts $artifacts -Assessment $assessment
    $payload = New-ExternalOwnerAuthorizationPayload -Request $request -AuditId "external-owner-pr53-00000000000000000000000000000000" -IssuedAt "2026-08-22T15:59:00Z" -ExpiresAt "2026-08-22T16:59:00Z"
    [byte[]]$canonical = Get-CanonicalAuthorizationBytes $payload
    $signature = $rsa.SignData($canonical, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pss)
    $fingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($rsa.ExportSubjectPublicKeyInfo())).ToLowerInvariant()
    $testPolicy = $policy | ConvertTo-Json -Depth 20 | ConvertFrom-Json -Depth 20
    $testPolicy.public_key_pem = $rsa.ExportSubjectPublicKeyInfoPem().Replace("`r", "")
    $testPolicy.public_key_spki_sha256 = $fingerprint
    $document = [ordered]@{ schema = "rusty.quest.external_owner_authorization.v1"; payload = $payload; signature = [ordered]@{ algorithm = "RSA-PSS-SHA256"; public_key_spki_sha256 = $fingerprint; value_base64 = [Convert]::ToBase64String($signature) } }
    $comment = [ordered]@{ id = 1; created_at = "2026-08-22T15:59:00Z"; updated_at = "2026-08-22T15:59:00Z"; user = [ordered]@{ login = "MesmerPrism" }; body = $testPolicy.comment_marker + "`n" + ($document | ConvertTo-Json -Depth 30 -Compress) }
    $null = Test-ExternalOwnerAuthorizationComments -Comments @($comment) -ExpectedPayload $payload -Policy $testPolicy -Now $now -SchemaPath $authorizationSchema
    $null = Test-ExternalOwnerAuthorizationComments -Comments @($comment) -ExpectedPayload $payload -Policy $testPolicy -Now $now -SchemaPath $authorizationSchema
    $bootstrapComment = $comment | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $bootstrapComment.body = $testPolicy.bootstrap_comment_marker + "`n{}"
    Assert-DamageRejected "bootstrap-marker-normal-fallback" {
        $null = Test-ExternalOwnerAuthorizationComments -Comments @($bootstrapComment) `
            -ExpectedPayload $payload -Policy $testPolicy -Now $now `
            -SchemaPath $authorizationSchema
    }
    $changed = $payload | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $changed.head.commit = ("0" * 40)
    $duplicate = @($comment, $comment)
    [byte[]]$originalSignatureBytes = [Convert]::FromBase64String(
        [string]$document.signature.value_base64
    )
    [byte[]]$wrongSignatureBytes = [byte[]]$originalSignatureBytes.Clone()
    $wrongSignatureBytes[0] = $wrongSignatureBytes[0] -bxor 1
    if ([Security.Cryptography.CryptographicOperations]::FixedTimeEquals(
        $originalSignatureBytes, $wrongSignatureBytes
    )) {
        throw "Wrong-signature damage did not mutate the signature bytes."
    }
    $wrongSignatureDocument = $document | ConvertTo-Json -Depth 30 |
        ConvertFrom-Json -Depth 30
    $wrongSignatureDocument.signature.value_base64 =
        [Convert]::ToBase64String($wrongSignatureBytes)
    $wrongSignature = $comment | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $wrongSignature.body = $testPolicy.comment_marker + "`n" +
        ($wrongSignatureDocument | ConvertTo-Json -Depth 30 -Compress)
    foreach ($case in @(
        [pscustomobject]@{ name = "duplicate"; comments = $duplicate; expected = $payload; at = $now },
        [pscustomobject]@{ name = "changed-evidence"; comments = @($comment); expected = $changed; at = $now },
        [pscustomobject]@{ name = "stale"; comments = @($comment); expected = $payload; at = $now.AddDays(2) },
        [pscustomobject]@{ name = "future"; comments = @($comment); expected = $payload; at = $now.AddDays(-1) },
        [pscustomobject]@{ name = "wrong-signature"; comments = @($wrongSignature); expected = $payload; at = $now }
    )) {
        $rejected = $false
        try { $null = Test-ExternalOwnerAuthorizationComments -Comments $case.comments -ExpectedPayload $case.expected -Policy $testPolicy -Now $case.at -SchemaPath $authorizationSchema } catch { $rejected = $true }
        if (-not $rejected) { throw "External-owner authorization damage was accepted: $($case.name)" }
    }
    Write-Output "External-owner authorization crypto and damage tests passed."
} finally { $rsa.Dispose() }
