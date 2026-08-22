[CmdletBinding()]
param([string]$RepoRoot = "")
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path } else { $RepoRoot = (Resolve-Path $RepoRoot).Path }
Import-Module (Join-Path $RepoRoot ".github\scripts\lib\ExternalOwnerAuthorization.psm1") -Force
$authorizationSchema = Join-Path $RepoRoot "schemas\rusty.quest.external_owner_authorization.v1.schema.json"
$policySchema = Join-Path $RepoRoot "schemas\rusty.quest.external_owner_authorization_policy.v1.schema.json"
$policy = Read-ExternalOwnerAuthorizationPolicy (Join-Path $RepoRoot "config\external-owner-authorization.json") $policySchema
$rsa = [Security.Cryptography.RSA]::Create(3072)
try {
    $now = [datetimeoffset]::Parse("2026-08-22T16:00:00Z")
    $artifacts = @([ordered]@{ path = "config/external-validation-authority.json"; state = "present"; mode = "100644"; size_bytes = 3; sha256 = ("a" * 64) })
    $assessment = [ordered]@{ schema = "rusty.quest.external_validation_authority_assessment.v1"; decision = "protected-without-base-approval"; candidate_code_executed = $false; execution_attested = $false; publication_authority = $false; limitations = @("Static admission only; no candidate code was executed.", "Execution, tests, and owner-effect evidence require separate trusted validation.", "This assessment does not authorize publication.", "Runner image and tool identities are observed exactly but not allowlisted.") }
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
    $changed = $payload | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $changed.head.commit = ("0" * 40)
    $duplicate = @($comment, $comment)
    $wrongSignature = $comment | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $wrongSignature.body = $testPolicy.comment_marker + "`n" + (($document | ConvertTo-Json -Depth 30 -Compress) -replace '"value_base64":".', '"value_base64":"A')
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
