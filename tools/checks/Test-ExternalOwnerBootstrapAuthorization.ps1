[CmdletBinding()]
param([string]$RepoRoot = "")

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
} else {
    $RepoRoot = (Resolve-Path $RepoRoot).Path
}

Import-Module (Join-Path $RepoRoot ".github\scripts\lib\ExternalOwnerAuthorization.psm1") -Force
$requestSchema = Join-Path $RepoRoot "schemas\rusty.quest.external_owner_bootstrap_request.v1.schema.json"
$authorizationSchema = Join-Path $RepoRoot "schemas\rusty.quest.external_owner_bootstrap_authorization.v1.schema.json"
$policySchema = Join-Path $RepoRoot "schemas\rusty.quest.external_owner_authorization_policy.v1.schema.json"
$policy = Read-ExternalOwnerAuthorizationPolicy (Join-Path $RepoRoot "config\external-owner-authorization.json") $policySchema

function Copy-ExternalOwnerBootstrapValue {
    param([Parameter(Mandatory)][object]$Value)
    return $Value | ConvertTo-Json -Depth 40 | ConvertFrom-Json -Depth 40
}

function Assert-BootstrapDamageRejected {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][scriptblock]$Action)
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (-not $rejected) { throw "External-owner bootstrap damage was accepted: $Name" }
}

function Get-BootstrapArtifactWithState {
    param([Parameter(Mandatory)][object]$Artifact)
    return [ordered]@{
        path = [string]$Artifact.path
        state = "present"
        mode = [string]$Artifact.mode
        size_bytes = [int64]$Artifact.size_bytes
        sha256 = [string]$Artifact.sha256
    }
}

function Get-ExternalOwnerBootstrapInventoryDigest {
    param([Parameter(Mandatory)][object]$Request)
    $changed = @($Request.changed_artifacts)
    $protected = @($Request.protected_artifacts)
    if ($changed.Count -ne [int]$Request.changed_artifact_count -or
        $protected.Count -ne [int]$Request.protected_artifact_count) {
        throw "Bootstrap artifact counts differ from their complete inventories."
    }
    $changedPaths = @($changed | ForEach-Object { [string]$_.path })
    $protectedPaths = @($protected | ForEach-Object { [string]$_.path })
    Assert-ExternalOwnerArtifactInventory -ChangedPaths $changedPaths `
        -ChangedArtifacts @($changed | ForEach-Object { Get-BootstrapArtifactWithState $_ }) `
        -ProtectedPaths $protectedPaths `
        -ProtectedArtifacts @($protected | ForEach-Object { Get-BootstrapArtifactWithState $_ })

    $protectedByPath = @{}
    foreach ($artifact in $protected) {
        $protectedByPath[[string]$artifact.path] = $artifact
    }
    $lines = [Collections.Generic.List[string]]::new()
    foreach ($artifact in $changed) {
        $path = [string]$artifact.path
        $classification = if ($protectedByPath.ContainsKey($path)) { "protected" } else { "unprotected" }
        if ($classification -ceq "protected") {
            $protectedArtifact = $protectedByPath[$path]
            if ([string]$protectedArtifact.mode -cne [string]$artifact.mode -or
                [int64]$protectedArtifact.size_bytes -ne [int64]$artifact.size_bytes -or
                [string]$protectedArtifact.sha256 -cne [string]$artifact.sha256) {
                throw "Bootstrap protected artifact differs from its changed artifact."
            }
        }
        $lines.Add((@(
            $path,
            $classification,
            "present",
            [string]$artifact.mode,
            ([int64]$artifact.size_bytes).ToString([Globalization.CultureInfo]::InvariantCulture),
            [string]$artifact.sha256
        ) -join "`t"))
    }
    [byte[]]$bytes = [Text.UTF8Encoding]::new($false, $true).GetBytes(($lines -join "`n") + "`n")
    return Get-ExternalOwnerSha256 $bytes
}

function Assert-ExternalOwnerBootstrapRequest {
    param([Parameter(Mandatory)][object]$Request, [Parameter(Mandatory)][object]$ExpectedPolicy)
    $requestJson = $Request | ConvertTo-Json -Depth 40 -Compress
    if (-not (Test-Json -Json $requestJson -SchemaFile $requestSchema -ErrorAction Stop)) {
        throw "Bootstrap request failed its closed schema."
    }
    if ([string]$Request.issuer_id -cne [string]$ExpectedPolicy.issuer_id -or
        [string]$Request.key_id -cne [string]$ExpectedPolicy.key_id -or
        [string]$Request.comment_marker -cne [string]$ExpectedPolicy.bootstrap_comment_marker) {
        throw "Bootstrap request issuer, key, or marker is not pinned."
    }
    if ([string]$Request.generated_merge.tree -cne [string]$Request.head.tree -or
        @($Request.generated_merge.ordered_parents).Count -ne 2 -or
        [string]$Request.generated_merge.ordered_parents[0] -cne [string]$Request.base.commit -or
        [string]$Request.generated_merge.ordered_parents[1] -cne [string]$Request.head.commit -or
        [string]$Request.local_dynamic_evidence.candidate_head -cne [string]$Request.head.commit) {
        throw "Bootstrap request merge or supplied dynamic evidence is not bound to the exact candidate."
    }
    if ((Get-ExternalOwnerBootstrapInventoryDigest $Request) -cne [string]$Request.inventory_digest.value) {
        throw "Bootstrap inventory digest does not bind the complete artifact inventories."
    }
    foreach ($forbidden in @("runtime", "executable", "runner", "assessment", "assessment_sha256")) {
        if ($Request.PSObject.Properties.Name -ccontains $forbidden) {
            throw "Bootstrap request contains a forbidden runtime-assessment field."
        }
    }
}

function Assert-ExternalOwnerBootstrapGeneratedMergeEquivalence {
    param(
        [Parameter(Mandatory)][object]$Request,
        [Parameter(Mandatory)][object]$ObservedGeneratedMerge
    )
    $properties = if ($ObservedGeneratedMerge -is [Collections.IDictionary]) {
        @($ObservedGeneratedMerge.Keys | ForEach-Object { [string]$_ } |
            Sort-Object -CaseSensitive)
    } else {
        @($ObservedGeneratedMerge.PSObject.Properties.Name |
            Sort-Object -CaseSensitive)
    }
    if (($properties -join ",") -cne "commit,ordered_parents,tree" -or
        [string]$ObservedGeneratedMerge.commit -cnotmatch "^[0-9a-f]{40}$" -or
        [string]$ObservedGeneratedMerge.tree -cne [string]$Request.head.tree -or
        @($ObservedGeneratedMerge.ordered_parents).Count -ne 2 -or
        [string]$ObservedGeneratedMerge.ordered_parents[0] -cne [string]$Request.base.commit -or
        [string]$ObservedGeneratedMerge.ordered_parents[1] -cne [string]$Request.head.commit) {
        throw "Bootstrap generated-merge observation is not stably equivalent to the signed base/head topology."
    }
}

function Test-ExternalOwnerBootstrapAuthorizationComment {
    param(
        [Parameter(Mandatory)][object[]]$Comments,
        [Parameter(Mandatory)][object]$ExpectedRequest,
        [Parameter(Mandatory)][object]$ExpectedPolicy,
        [Parameter(Mandatory)][object]$ObservedGeneratedMerge,
        [Parameter(Mandatory)][datetimeoffset]$Now,
        [AllowEmptyCollection()][string[]]$ConsumedAuditIds = @()
    )
    if ($Comments.Count -gt [int]$ExpectedPolicy.maximum_comments) {
        throw "Bootstrap comment count exceeds the configured bound."
    }
    $markerPattern = "(?m)^$([regex]::Escape([string]$ExpectedPolicy.bootstrap_comment_marker))$"
    $marked = @($Comments | Where-Object {
        [string]$_.user.login -ceq [string]$ExpectedPolicy.owner_login -and
        [regex]::Matches([string]$_.body, $markerPattern).Count -gt 0
    })
    if ($marked.Count -ne 1) { throw "Exactly one pinned-owner bootstrap marker is required." }
    $comment = $marked[0]
    if ($null -eq $comment.id -or [string]$comment.created_at -cne [string]$comment.updated_at) {
        throw "Bootstrap authorization comment identity or edit state is invalid."
    }
    if ([Text.Encoding]::UTF8.GetByteCount([string]$comment.body) -gt [int]$ExpectedPolicy.maximum_comment_bytes) {
        throw "Bootstrap authorization comment exceeds the size bound."
    }
    $lines = ([string]$comment.body) -split "\r?\n", 2
    if ($lines.Count -ne 2 -or $lines[0] -cne [string]$ExpectedPolicy.bootstrap_comment_marker) {
        throw "Bootstrap marker framing is not canonical."
    }
    $document = ConvertFrom-ExternalOwnerJsonStrict $lines[1]
    $documentJson = $document | ConvertTo-Json -Depth 40 -Compress
    if (-not (Test-Json -Json $documentJson -SchemaFile $authorizationSchema -ErrorAction Stop)) {
        throw "Bootstrap authorization document failed its closed schema."
    }
    $payload = $document.payload
    Assert-ExternalOwnerBootstrapRequest -Request $payload.request -ExpectedPolicy $ExpectedPolicy
    Assert-ExternalOwnerBootstrapGeneratedMergeEquivalence -Request $payload.request `
        -ObservedGeneratedMerge $ObservedGeneratedMerge
    if ([string]$payload.issuer_id -cne [string]$ExpectedPolicy.issuer_id -or
        [string]$payload.key_id -cne [string]$ExpectedPolicy.key_id -or
        [string]$document.signature.algorithm -cne "RSA-PSS-SHA256" -or
        [string]$document.signature.public_key_spki_sha256 -cne [string]$ExpectedPolicy.public_key_spki_sha256 -or
        [string]$payload.issuer_id -cne [string]$payload.request.issuer_id -or
        [string]$payload.key_id -cne [string]$payload.request.key_id -or
        [string]$payload.request_sha256 -cne (Get-ExternalOwnerSha256 (Get-CanonicalAuthorizationBytes $payload.request)) -or
        (@((Get-CanonicalAuthorizationBytes $payload.request)) -join ",") -cne
            (@((Get-CanonicalAuthorizationBytes $ExpectedRequest)) -join ",")) {
        throw "Bootstrap authorization evidence differs from its exact pinned request."
    }
    if (@($ConsumedAuditIds | Where-Object {
        [string]$_ -ceq [string]$payload.audit_id
    }).Count -ne 0) {
        throw "Bootstrap authorization audit identity is already consumed."
    }
    try {
        $issued = [datetimeoffset]::ParseExact(
            [string]$payload.issued_at, "yyyy-MM-dd'T'HH:mm:ss'Z'",
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::AssumeUniversal
        )
        $expires = [datetimeoffset]::ParseExact(
            [string]$payload.expires_at, "yyyy-MM-dd'T'HH:mm:ss'Z'",
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::AssumeUniversal
        )
    } catch { throw "Bootstrap authorization timestamps are invalid." }
    if ($issued -gt $Now.AddSeconds([int]$ExpectedPolicy.max_future_skew_seconds) -or
        $issued -lt $Now.AddSeconds(-[int]$ExpectedPolicy.max_authorization_age_seconds) -or
        $expires -le $Now -or $expires -le $issued -or
        $expires -gt $issued.AddSeconds([int]$ExpectedPolicy.max_authorization_age_seconds)) {
        throw "Bootstrap authorization is stale, future-dated, or exceeds its freshness window."
    }
    [byte[]]$canonical = Get-CanonicalAuthorizationBytes $payload
    try { [byte[]]$signature = [Convert]::FromBase64String([string]$document.signature.value_base64) } catch { throw "Bootstrap signature is not canonical base64." }
    if ([Convert]::ToBase64String($signature) -cne [string]$document.signature.value_base64 -or
        -not [RustyQuest.ExternalOwnerCrypto]::Verify([string]$ExpectedPolicy.public_key_pem, $canonical, $signature)) {
        throw "Bootstrap authorization signature verification failed."
    }
    return $payload
}

$rsa = [Security.Cryptography.RSA]::Create(3072)
$wrongRsa = [Security.Cryptography.RSA]::Create(3072)
try {
    $now = [datetimeoffset]::Parse("2026-08-22T16:00:00Z")
    $testPolicy = Copy-ExternalOwnerBootstrapValue $policy
    $testPolicy.public_key_pem = $rsa.ExportSubjectPublicKeyInfoPem().Replace("`r", "")
    $testPolicy.public_key_spki_sha256 = [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($rsa.ExportSubjectPublicKeyInfo())
    ).ToLowerInvariant()
    $changedArtifacts = @(
        [ordered]@{ path = "config/external-validation-authority.json"; mode = "100644"; size_bytes = 3; sha256 = ("a" * 64) },
        [ordered]@{ path = "tools/checks/Test-ExternalOwnerBootstrapAuthorization.ps1"; mode = "100644"; size_bytes = 4; sha256 = ("b" * 64) }
    )
    $protectedArtifacts = @($changedArtifacts[0])
    $request = [ordered]@{
        schema = "rusty.quest.external_owner_bootstrap_request.v1"
        issuer_id = [string]$testPolicy.issuer_id
        key_id = [string]$testPolicy.key_id
        comment_marker = [string]$testPolicy.bootstrap_comment_marker
        repository = "MesmerPrism/rusty-quest"
        pull_request_number = 55
        base = [ordered]@{ commit = ("1" * 40); tree = ("2" * 40) }
        head = [ordered]@{ commit = ("3" * 40); tree = ("4" * 40) }
        generated_merge = [ordered]@{ observed_commit = ("5" * 40); tree = ("4" * 40); ordered_parents = @(("1" * 40), ("3" * 40)) }
        changed_artifact_count = 2
        protected_artifact_count = 1
        inventory_digest = [ordered]@{ algorithm = "SHA-256"; domain = "rusty.quest.external_owner_bootstrap_inventory.v1"; encoding = "UTF-8"; line_ending = "LF"; field_separator = "TAB"; field_order = @("path", "protected_classification", "state", "mode", "size_bytes", "sha256"); absent_field_sentinel = "-"; value = ("0" * 64) }
        changed_artifacts = $changedArtifacts
        protected_artifacts = $protectedArtifacts
        intent = [ordered]@{ kind = "independently-reviewed-trust-root-evolution"; independently_reviewed = $true; user_authorized_one_time_bootstrap = $true; authorization_scope = "single-exact-bootstrap-merge-review-decision"; consumption = "orchestrator-exact-head-admin-merge-only" }
        old_base_hold = [ordered]@{ event = "pull_request_target"; workflow_name = "External validation authority"; run_id = "32585330671"; job_id = "97060724080"; job_name = "Static admission"; exit_code = 1; verifier_message = "Protected changes do not match an exact base-approved change set." }
        ordinary_candidate_ci = [ordered]@{ event = "pull_request"; workflow_name = "Package updater dynamic validation (non-authoritative)"; run_id = "32585330645"; job_id = "97060723977"; job_name = "Credential-free candidate checks (non-authoritative)"; conclusion = "success"; non_authoritative = $true }
        local_dynamic_evidence = [ordered]@{ kind = "supplied-local-aggregate-receipt"; candidate_head = ("3" * 40); aggregate_receipt_sha256 = ("c" * 64); conclusion = "passed"; non_authoritative = $true }
        limitations = @("candidate_code_executed=false", "execution_attested=false", "static_admission_authority=false", "acceptance_authority=false", "publication_authority=false")
    }
    $request.inventory_digest.value = Get-ExternalOwnerBootstrapInventoryDigest $request
    Assert-ExternalOwnerBootstrapRequest -Request $request -ExpectedPolicy $testPolicy
    $payload = [ordered]@{
        schema = "rusty.quest.external_owner_bootstrap_authorization_payload.v1"
        issuer_id = [string]$testPolicy.issuer_id
        key_id = [string]$testPolicy.key_id
        audit_id = "bootstrap-pr55-00000000000000000000000000000000"
        request = $request
        request_sha256 = Get-ExternalOwnerSha256 (Get-CanonicalAuthorizationBytes $request)
        issued_at = "2026-08-22T15:59:00Z"
        expires_at = "2026-08-22T16:59:00Z"
        decision = "authorize-one-time-bootstrap-merge-review"
        limitations = @($request.limitations)
    }
    [byte[]]$signature = $rsa.SignData((Get-CanonicalAuthorizationBytes $payload), [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pss)
    $document = [ordered]@{
        schema = "rusty.quest.external_owner_bootstrap_authorization.v1"
        payload = $payload
        signature = [ordered]@{ algorithm = "RSA-PSS-SHA256"; public_key_spki_sha256 = [string]$testPolicy.public_key_spki_sha256; value_base64 = [Convert]::ToBase64String($signature) }
    }
    $comment = [ordered]@{ id = 55; created_at = "2026-08-22T15:59:00Z"; updated_at = "2026-08-22T15:59:00Z"; user = [ordered]@{ login = "MesmerPrism" }; body = [string]$testPolicy.bootstrap_comment_marker + "`n" + ($document | ConvertTo-Json -Depth 40 -Compress) }
    $observedMerge = [ordered]@{ commit = ("5" * 40); tree = ("4" * 40); ordered_parents = @(("1" * 40), ("3" * 40)) }
    $null = Test-ExternalOwnerBootstrapAuthorizationComment -Comments @($comment) -ExpectedRequest $request -ExpectedPolicy $testPolicy -ObservedGeneratedMerge $observedMerge -Now $now
    $laterSyntheticMerge = Copy-ExternalOwnerBootstrapValue $observedMerge
    $laterSyntheticMerge.commit = ("6" * 40)
    $null = Test-ExternalOwnerBootstrapAuthorizationComment -Comments @($comment) -ExpectedRequest $request -ExpectedPolicy $testPolicy -ObservedGeneratedMerge $laterSyntheticMerge -Now $now

    $changedRequest = Copy-ExternalOwnerBootstrapValue $request
    $changedRequest.changed_artifacts[0].sha256 = ("d" * 64)
    $changedRequest.protected_artifacts[0].sha256 = ("d" * 64)
    $changedRequest.inventory_digest.value = Get-ExternalOwnerBootstrapInventoryDigest $changedRequest
    $changedPayload = Copy-ExternalOwnerBootstrapValue $payload
    $changedPayload.request = $changedRequest
    $changedPayload.request_sha256 = Get-ExternalOwnerSha256 (Get-CanonicalAuthorizationBytes $changedRequest)
    $changedSignature = $rsa.SignData((Get-CanonicalAuthorizationBytes $changedPayload), [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pss)
    $changedDocument = Copy-ExternalOwnerBootstrapValue $document
    $changedDocument.payload = $changedPayload
    $changedDocument.signature.value_base64 = [Convert]::ToBase64String($changedSignature)
    $changedComment = Copy-ExternalOwnerBootstrapValue $comment
    $changedComment.body = [string]$testPolicy.bootstrap_comment_marker + "`n" + ($changedDocument | ConvertTo-Json -Depth 40 -Compress)

    $wrongKeySignature = $wrongRsa.SignData((Get-CanonicalAuthorizationBytes $payload), [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pss)
    $wrongKeyComment = Copy-ExternalOwnerBootstrapValue $comment
    $wrongKeyDocument = Copy-ExternalOwnerBootstrapValue $document
    $wrongKeyDocument.signature.value_base64 = [Convert]::ToBase64String($wrongKeySignature)
    $wrongKeyComment.body = [string]$testPolicy.bootstrap_comment_marker + "`n" + ($wrongKeyDocument | ConvertTo-Json -Depth 40 -Compress)

    $offsetPayload = Copy-ExternalOwnerBootstrapValue $payload
    $offsetPayload.issued_at = "2026-08-22T15:59:00+00:00"
    $offsetSignature = $rsa.SignData((Get-CanonicalAuthorizationBytes $offsetPayload), [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pss)
    $offsetDocument = Copy-ExternalOwnerBootstrapValue $document
    $offsetDocument.payload = $offsetPayload
    $offsetDocument.signature.value_base64 = [Convert]::ToBase64String($offsetSignature)
    $offsetComment = Copy-ExternalOwnerBootstrapValue $comment
    $offsetComment.body = [string]$testPolicy.bootstrap_comment_marker + "`n" + ($offsetDocument | ConvertTo-Json -Depth 40 -Compress)

    $expiryPayload = Copy-ExternalOwnerBootstrapValue $payload
    $expiryPayload.expires_at = "2026-08-22T16:00:00Z"
    $expirySignature = $rsa.SignData((Get-CanonicalAuthorizationBytes $expiryPayload), [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pss)
    $expiryDocument = Copy-ExternalOwnerBootstrapValue $document
    $expiryDocument.payload = $expiryPayload
    $expiryDocument.signature.value_base64 = [Convert]::ToBase64String($expirySignature)
    $expiryComment = Copy-ExternalOwnerBootstrapValue $comment
    $expiryComment.body = [string]$testPolicy.bootstrap_comment_marker + "`n" + ($expiryDocument | ConvertTo-Json -Depth 40 -Compress)

    foreach ($case in @(
        [pscustomobject]@{ name = "duplicate"; comments = @($comment, $comment); expected = $request; at = $now; consumed = @() },
        [pscustomobject]@{ name = "changed-artifact"; comments = @($changedComment); expected = $request; at = $now; consumed = @() },
        [pscustomobject]@{ name = "stale"; comments = @($comment); expected = $request; at = $now.AddDays(2); consumed = @() },
        [pscustomobject]@{ name = "future"; comments = @($comment); expected = $request; at = $now.AddDays(-1); consumed = @() },
        [pscustomobject]@{ name = "wrong-key"; comments = @($wrongKeyComment); expected = $request; at = $now; consumed = @() },
        [pscustomobject]@{ name = "offset-timestamp"; comments = @($offsetComment); expected = $request; at = $now; consumed = @() },
        [pscustomobject]@{ name = "expiry-at-read-time"; comments = @($expiryComment); expected = $request; at = $now; consumed = @() },
        [pscustomobject]@{ name = "consumed-audit"; comments = @($comment); expected = $request; at = $now; consumed = @([string]$payload.audit_id) }
    )) {
        Assert-BootstrapDamageRejected $case.name {
            $null = Test-ExternalOwnerBootstrapAuthorizationComment -Comments $case.comments `
                -ExpectedRequest $case.expected -ExpectedPolicy $testPolicy `
                -ObservedGeneratedMerge $observedMerge -Now $case.at `
                -ConsumedAuditIds $case.consumed
        }
    }
    $runtimeDamage = Copy-ExternalOwnerBootstrapValue $request
    $runtimeDamage | Add-Member -NotePropertyName runtime -NotePropertyValue ([ordered]@{ runner = "forbidden" })
    Assert-BootstrapDamageRejected "runtime-field" {
        Assert-ExternalOwnerBootstrapRequest -Request $runtimeDamage -ExpectedPolicy $testPolicy
    }
    $digestDamage = Copy-ExternalOwnerBootstrapValue $request
    $digestDamage.inventory_digest.value = ("0" * 64)
    Assert-BootstrapDamageRejected "inventory-digest" {
        Assert-ExternalOwnerBootstrapRequest -Request $digestDamage -ExpectedPolicy $testPolicy
    }
    $parentDamage = Copy-ExternalOwnerBootstrapValue $request
    $parentDamage.generated_merge.ordered_parents[1] = ("f" * 40)
    Assert-BootstrapDamageRejected "merge-parent" {
        Assert-ExternalOwnerBootstrapRequest -Request $parentDamage -ExpectedPolicy $testPolicy
    }
    $baseDamage = Copy-ExternalOwnerBootstrapValue $request
    $baseDamage.base.commit = ("f" * 40)
    Assert-BootstrapDamageRejected "request-base" {
        Assert-ExternalOwnerBootstrapRequest -Request $baseDamage -ExpectedPolicy $testPolicy
    }
    $headDamage = Copy-ExternalOwnerBootstrapValue $request
    $headDamage.head.commit = ("f" * 40)
    Assert-BootstrapDamageRejected "request-head" {
        Assert-ExternalOwnerBootstrapRequest -Request $headDamage -ExpectedPolicy $testPolicy
    }
    foreach ($case in @(
        [pscustomobject]@{ name = "observed-merge-tree"; value = [ordered]@{ commit = ("6" * 40); tree = ("f" * 40); ordered_parents = @(("1" * 40), ("3" * 40)) } },
        [pscustomobject]@{ name = "observed-merge-base"; value = [ordered]@{ commit = ("6" * 40); tree = ("4" * 40); ordered_parents = @(("f" * 40), ("3" * 40)) } },
        [pscustomobject]@{ name = "observed-merge-head"; value = [ordered]@{ commit = ("6" * 40); tree = ("4" * 40); ordered_parents = @(("1" * 40), ("f" * 40)) } },
        [pscustomobject]@{ name = "observed-merge-parent-order"; value = [ordered]@{ commit = ("6" * 40); tree = ("4" * 40); ordered_parents = @(("3" * 40), ("1" * 40)) } }
    )) {
        Assert-BootstrapDamageRejected $case.name {
            Assert-ExternalOwnerBootstrapGeneratedMergeEquivalence -Request $request `
                -ObservedGeneratedMerge $case.value
        }
    }
    $authorityFlagDamage = Copy-ExternalOwnerBootstrapValue $request
    $authorityFlagDamage.limitations[4] = "publication_authority=true"
    Assert-BootstrapDamageRejected "bootstrap-publication-authority" {
        Assert-ExternalOwnerBootstrapRequest -Request $authorityFlagDamage -ExpectedPolicy $testPolicy
    }
    $duplicatePathDamage = Copy-ExternalOwnerBootstrapValue $request
    $duplicatePathDamage.changed_artifacts = @($duplicatePathDamage.changed_artifacts[0], $duplicatePathDamage.changed_artifacts[0])
    $duplicatePathDamage.changed_artifact_count = 2
    Assert-BootstrapDamageRejected "duplicate-artifact-path" {
        Get-ExternalOwnerBootstrapInventoryDigest $duplicatePathDamage | Out-Null
    }
    Write-Output "External-owner bootstrap authorization schema, crypto, and damage tests passed."
} finally {
    $rsa.Dispose()
    $wrongRsa.Dispose()
}
