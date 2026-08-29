Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Adapted from MesmerPrism/rusty-morphospace-work-environment at
# b8c05cca92f4c61ca6fab9fc500a905a0826c3c8, audited blob
# 1a56414777d0b71ac8149a93b0cbf24a4ee192ee.  This module is base-owned
# and is never loaded from a pull-request candidate.
function Initialize-ExternalOwnerAuthorizationTypes {
    if ("RustyQuest.ExternalOwnerCrypto" -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
namespace RustyQuest {
  public static class ExternalOwnerCrypto {
    static void WriteCanonical(Utf8JsonWriter w, JsonElement e) {
      switch (e.ValueKind) {
        case JsonValueKind.Object:
          w.WriteStartObject(); var properties = new List<JsonProperty>();
          foreach (var p in e.EnumerateObject()) properties.Add(p);
          properties.Sort((a,b) => StringComparer.Ordinal.Compare(a.Name,b.Name));
          string prior = null;
          foreach (var p in properties) {
            if (prior != null && StringComparer.OrdinalIgnoreCase.Equals(prior,p.Name))
              throw new InvalidDataException("Duplicate or case-colliding JSON property.");
            prior = p.Name; w.WritePropertyName(p.Name); WriteCanonical(w,p.Value);
          }
          w.WriteEndObject(); break;
        case JsonValueKind.Array:
          w.WriteStartArray(); foreach (var v in e.EnumerateArray()) WriteCanonical(w,v); w.WriteEndArray(); break;
        case JsonValueKind.String: w.WriteStringValue(e.GetString()); break;
        case JsonValueKind.Number:
          if (e.TryGetInt64(out long n)) w.WriteNumberValue(n); else throw new InvalidDataException("Non-integer JSON number.");
          break;
        case JsonValueKind.True: w.WriteBooleanValue(true); break;
        case JsonValueKind.False: w.WriteBooleanValue(false); break;
        case JsonValueKind.Null: w.WriteNullValue(); break;
        default: throw new InvalidDataException("Unsupported JSON token.");
      }
    }
    public static byte[] Canonicalize(string json) {
      var options = new JsonDocumentOptions { AllowTrailingCommas=false, CommentHandling=JsonCommentHandling.Disallow, MaxDepth=32 };
      using (var doc = JsonDocument.Parse(json, options))
      using (var stream = new MemoryStream()) {
        using (var writer = new Utf8JsonWriter(stream, new JsonWriterOptions { Indented=false })) WriteCanonical(writer,doc.RootElement);
        return stream.ToArray();
      }
    }
    public static string SpkiSha256(string pem) {
      using (RSA rsa = RSA.Create()) { rsa.ImportFromPem(pem); return Convert.ToHexString(SHA256.HashData(rsa.ExportSubjectPublicKeyInfo())).ToLowerInvariant(); }
    }
    public static bool Verify(string pem, byte[] data, byte[] signature) {
      using (RSA rsa = RSA.Create()) { rsa.ImportFromPem(pem); return rsa.VerifyData(data,signature,HashAlgorithmName.SHA256,RSASignaturePadding.Pss); }
    }
  }
}
'@
}

function Get-CanonicalAuthorizationBytes {
    param([Parameter(Mandatory)][object]$Payload)
    Initialize-ExternalOwnerAuthorizationTypes
    return ,[RustyQuest.ExternalOwnerCrypto]::Canonicalize(($Payload | ConvertTo-Json -Depth 30 -Compress))
}

function Get-ExternalOwnerSha256 {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant()
}

function ConvertFrom-ExternalOwnerJsonStrict {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Json)
    Initialize-ExternalOwnerAuthorizationTypes
    $null = [RustyQuest.ExternalOwnerCrypto]::Canonicalize($Json)
    if ($Json.Trim() -ceq "[]") { return }
    return $Json | ConvertFrom-Json -Depth 30 -DateKind String
}

function Read-ExternalOwnerAuthorizationPolicy {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$SchemaPath)
    [byte[]]$raw = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path).Path)
    if ($raw.Length -gt 16384) { throw "External-owner policy exceeds its size bound." }
    try { $text = [Text.UTF8Encoding]::new($false,$true).GetString($raw) } catch { throw "External-owner policy is not valid UTF-8." }
    $policy = ConvertFrom-ExternalOwnerJsonStrict $text
    $canonical = [Text.Encoding]::UTF8.GetString((Get-CanonicalAuthorizationBytes $policy))
    if (-not (Test-Json -Json $canonical -SchemaFile $SchemaPath -ErrorAction Stop)) { throw "External-owner policy failed its schema." }
    if ([RustyQuest.ExternalOwnerCrypto]::SpkiSha256([string]$policy.public_key_pem) -cne [string]$policy.public_key_spki_sha256) { throw "External-owner policy public-key fingerprint is inconsistent." }
    return $policy
}

function Assert-ExternalOwnerPortablePathSequence {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Paths,
        [Parameter(Mandatory)][string]$Label
    )
    $ordinal = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $folded = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($path in $Paths) {
        if (
            [string]::IsNullOrWhiteSpace($path) -or
            $path.Length -gt 512 -or
            $path -cne $path.Normalize([Text.NormalizationForm]::FormC) -or
            $path.Contains([char]92) -or
            $path.Contains(":") -or
            $path -match "[\x00-\x1f\x7f]" -or
            @($path.Split("/") | Where-Object {
                [string]::IsNullOrEmpty($_) -or $_ -in @(".", "..")
            }).Count -ne 0
        ) {
            throw "$Label contains a non-portable or non-canonical path."
        }
        if (-not $ordinal.Add($path)) {
            throw "$Label contains a duplicate path."
        }
        if (-not $folded.Add($path)) {
            throw "$Label contains a case-colliding path."
        }
    }
    [string[]]$sorted = @($Paths)
    [Array]::Sort($sorted, [StringComparer]::Ordinal)
    if (($Paths -join "`n") -cne ($sorted -join "`n")) {
        throw "$Label is not ordinally sorted."
    }
}

function Get-ExternalOwnerPropertyNames {
    param([Parameter(Mandatory)][object]$Value)
    if ($Value -is [Collections.IDictionary]) {
        return @($Value.Keys | ForEach-Object { [string]$_ })
    }
    return @($Value.PSObject.Properties.Name | ForEach-Object { [string]$_ })
}

function ConvertFrom-ExternalOwnerGitNameStatusBytes {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    if ($Bytes.Length -eq 0) { return @() }
    $tokens = [Collections.Generic.List[byte[]]]::new()
    $start = 0
    for ($index = 0; $index -lt $Bytes.Length; $index++) {
        if ($Bytes[$index] -ne 0) { continue }
        $length = $index - $start
        if ($length -eq 0) {
            throw "Git name-status output contains an empty NUL-delimited token."
        }
        [byte[]]$token = [byte[]]::new($length)
        [Array]::Copy($Bytes, $start, $token, 0, $length)
        $tokens.Add($token)
        $start = $index + 1
        if ($tokens.Count -gt 1024) {
            throw "Git name-status output exceeds the 512-path bound."
        }
    }
    if ($start -ne $Bytes.Length) {
        throw "Git name-status output lacks a terminal NUL delimiter."
    }
    if (($tokens.Count % 2) -ne 0) {
        throw "Git name-status output has an incomplete status/path pair."
    }
    $utf8 = [Text.UTF8Encoding]::new($false, $true)
    $records = [Collections.Generic.List[object]]::new()
    for ($index = 0; $index -lt $tokens.Count; $index += 2) {
        try {
            $status = $utf8.GetString($tokens[$index])
            $path = $utf8.GetString($tokens[$index + 1])
        } catch {
            throw "Git name-status output contains invalid UTF-8."
        }
        if ($status -cnotmatch "^[AMD]$") {
            throw "Git name-status output has an unsupported status."
        }
        $records.Add([pscustomobject][ordered]@{ status = $status; path = $path })
    }
    Assert-ExternalOwnerPortablePathSequence `
        -Paths @($records | ForEach-Object { [string]$_.path }) `
        -Label "Git name-status paths"
    return @($records)
}

function Assert-ExternalOwnerArtifactInventory {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$ChangedPaths,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$ChangedArtifacts,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$ProtectedPaths,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$ProtectedArtifacts
    )
    Assert-ExternalOwnerPortablePathSequence $ChangedPaths "Changed artifact paths"
    Assert-ExternalOwnerPortablePathSequence $ProtectedPaths "Protected artifact paths"
    $changedSet = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal
    )
    foreach ($path in $ChangedPaths) { [void]$changedSet.Add($path) }
    foreach ($path in $ProtectedPaths) {
        if (-not $changedSet.Contains($path)) {
            throw "Protected artifact path is absent from the changed-path inventory."
        }
    }
    foreach ($pair in @(
        [pscustomobject]@{ paths = $ChangedPaths; artifacts = $ChangedArtifacts; label = "Changed" },
        [pscustomobject]@{ paths = $ProtectedPaths; artifacts = $ProtectedArtifacts; label = "Protected" }
    )) {
        if ($pair.artifacts.Count -ne $pair.paths.Count) {
            throw "$($pair.label) artifact count differs from its path inventory."
        }
        for ($index = 0; $index -lt $pair.paths.Count; $index++) {
            $artifact = $pair.artifacts[$index]
            if ([string]$artifact.path -cne $pair.paths[$index]) {
                throw "$($pair.label) artifact paths differ from their path inventory."
            }
            $properties = @(Get-ExternalOwnerPropertyNames $artifact | Sort-Object -CaseSensitive)
            if ([string]$artifact.state -ceq "present") {
                if (($properties -join ",") -cne "mode,path,sha256,size_bytes,state") {
                    throw "$($pair.label) present artifact shape is not closed."
                }
                if ([string]$artifact.mode -cnotin @("100644", "100755") -or
                    [int64]$artifact.size_bytes -lt 0 -or
                    [int64]$artifact.size_bytes -gt 16777216 -or
                    [string]$artifact.sha256 -cnotmatch "^[0-9a-f]{64}$") {
                    throw "$($pair.label) present artifact is malformed."
                }
            } elseif ([string]$artifact.state -ceq "absent") {
                if (($properties -join ",") -cne "path,state") {
                    throw "$($pair.label) absent artifact shape is not closed."
                }
            } else {
                throw "$($pair.label) artifact state is unsupported."
            }
        }
    }
}

function Assert-ExternalOwnerProtectedWithoutBaseApprovalAssessment {
    param(
        [Parameter(Mandatory)][object]$Assessment,
        [Parameter(Mandatory)][object]$Policy,
        [Parameter(Mandatory)][byte[]]$PolicyBytes,
        [Parameter(Mandatory)][object]$Base,
        [Parameter(Mandatory)][object]$Candidate,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$ChangedArtifacts,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$ProtectedArtifacts
    )
    $properties = @($Assessment.PSObject.Properties.Name | Sort-Object -CaseSensitive)
    $expected = @(
        "approval_id", "base", "candidate", "candidate_code_executed", "changed_paths",
        "decision", "execution_attested", "limitations", "policy_id", "policy_sha256",
        "protected_paths", "publication_authority", "repository", "schema"
    )
    if (($properties -join ",") -cne (($expected | Sort-Object -CaseSensitive) -join ",")) {
        throw "Protected-without-base-approval assessment shape is not closed."
    }
    $changedPaths = @($ChangedArtifacts | ForEach-Object { [string]$_.path })
    $protectedPaths = @($ProtectedArtifacts | ForEach-Object { [string]$_.path })
    Assert-ExternalOwnerArtifactInventory $changedPaths $ChangedArtifacts $protectedPaths $ProtectedArtifacts
    if (
        [string]$Assessment.schema -cne "rusty.morphospace.workflow.external_validation_authority_assessment.v1" -or
        [string]$Assessment.policy_id -cne [string]$Policy.policy_id -or
        [string]$Assessment.policy_sha256 -cne (Get-ExternalOwnerSha256 $PolicyBytes) -or
        [string]$Assessment.repository -cne "MesmerPrism/rusty-quest" -or
        [string]$Assessment.base.commit -cne [string]$Base.commit -or
        [string]$Assessment.base.tree -cne [string]$Base.tree -or
        [string]$Assessment.candidate.commit -cne [string]$Candidate.commit -or
        [string]$Assessment.candidate.tree -cne [string]$Candidate.tree -or
        (@([string[]]$Assessment.changed_paths) -join "`n") -cne ($changedPaths -join "`n") -or
        (@([string[]]$Assessment.protected_paths) -join "`n") -cne ($protectedPaths -join "`n") -or
        [string]$Assessment.decision -cne "protected-without-base-approval" -or
        $null -ne $Assessment.approval_id -or
        $Assessment.candidate_code_executed -ne $false -or
        $Assessment.execution_attested -ne $false -or
        $Assessment.publication_authority -ne $false -or
        (@([string[]]$Assessment.limitations) -join "`n") -cne (@(
            "Static admission only; no candidate code was executed.",
            "Execution, tests, and owner-effect evidence require separate trusted validation.",
            "This assessment does not authorize publication."
        ) -join "`n")
    ) {
        throw "Protected-without-base-approval assessment differs from the exact verifier hold."
    }
}

function New-ExternalOwnerProtectedWithoutBaseApprovalAssessment {
    param(
        [Parameter(Mandatory)][object]$Policy,
        [Parameter(Mandatory)][byte[]]$PolicyBytes,
        [Parameter(Mandatory)][object]$Base,
        [Parameter(Mandatory)][object]$Candidate,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$ChangedArtifacts,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$ProtectedArtifacts
    )
    $assessment = [pscustomobject][ordered]@{
        schema = "rusty.morphospace.workflow.external_validation_authority_assessment.v1"
        policy_id = [string]$Policy.policy_id
        policy_sha256 = Get-ExternalOwnerSha256 $PolicyBytes
        repository = "MesmerPrism/rusty-quest"
        base = $Base
        candidate = $Candidate
        changed_paths = @($ChangedArtifacts | ForEach-Object { [string]$_.path })
        protected_paths = @($ProtectedArtifacts | ForEach-Object { [string]$_.path })
        decision = "protected-without-base-approval"
        approval_id = $null
        candidate_code_executed = $false
        execution_attested = $false
        publication_authority = $false
        limitations = @(
            "Static admission only; no candidate code was executed.",
            "Execution, tests, and owner-effect evidence require separate trusted validation.",
            "This assessment does not authorize publication."
        )
    }
    Assert-ExternalOwnerProtectedWithoutBaseApprovalAssessment `
        -Assessment $assessment -Policy $Policy -PolicyBytes $PolicyBytes `
        -Base $Base -Candidate $Candidate -ChangedArtifacts $ChangedArtifacts `
        -ProtectedArtifacts $ProtectedArtifacts
    return $assessment
}

function Test-ExternalOwnerExactUtf8TransportText {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Actual,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Expected
    )
    # Keep the ANSI renderer profile byte-for-byte: ordinal text equality
    # rejects lookalikes, while fixed-time UTF-8 comparison rejects a partial
    # rendering without normalizing terminal control bytes.
    $sameOrdinalText = [string]::Equals(
        $Actual, $Expected, [StringComparison]::Ordinal
    )
    $utf8 = [Text.UTF8Encoding]::new($false, $true)
    [byte[]]$actualBytes = $utf8.GetBytes($Actual)
    [byte[]]$expectedBytes = $utf8.GetBytes($Expected)
    $sameBytes = [Security.Cryptography.CryptographicOperations]::FixedTimeEquals(
        $actualBytes, $expectedBytes
    )
    return $sameOrdinalText -and $sameBytes
}

function Assert-ExternalOwnerFallbackVerifierFailure {
    param(
        [Parameter(Mandatory)][int]$ExitCode,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Output,
        [AllowEmptyString()][string]$VerifierScript = "",
        [int]$VerifierHoldLine = 0
    )
    $holdMessage = "Protected changes do not match an exact base-approved change set."
    # The authority workflow is pinned to windows-2025.  A terminating throw
    # from its child pwsh process is transported through native stderr as this
    # exact RemoteException message, including one terminal CRLF.
    $transportMessage = "Exception: $holdMessage`r`n"
    if (
        $ExitCode -eq 1 -and $Output.Count -eq 1 -and
        $Output[0] -is [Management.Automation.ErrorRecord] -and
        $null -ne $Output[0].Exception -and
        $Output[0].Exception.GetType() -eq [Management.Automation.RemoteException] -and
        [string]$Output[0].FullyQualifiedErrorId -ceq "NativeCommandError" -and
        [string]$Output[0].CategoryInfo.Category -ceq "NotSpecified" -and
        [string]$Output[0].TargetObject -ceq $transportMessage -and
        [string]$Output[0].Exception.Message -ceq $transportMessage
    ) {
        return
    }

    # PowerShell 7.6's -File native-error renderer emits the trusted throw as
    # five RemoteException records rather than the legacy one-record CRLF
    # envelope.  It is accepted only when every record is the exact rendering
    # of the hash-pinned verifier path, source line, and hold text.
    if ([string]::IsNullOrWhiteSpace($VerifierScript) -or $VerifierHoldLine -eq 0) {
        throw "Pinned verifier result is not the exact protected-without-base-approval hold."
    }
    $absoluteVerifierScript = [IO.Path]::GetFullPath($VerifierScript)
    $expectedMessages = @(
        "Exception: $absoluteVerifierScript`:$VerifierHoldLine",
        "Line |",
        (" {0,3} |          throw `"Protected changes do not match an exact base-approved  …" -f $VerifierHoldLine),
        "     |          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~",
        "     | $holdMessage"
    )
    $expectedIds = @(
        "NativeCommandError",
        "NativeCommandErrorMessage",
        "NativeCommandErrorMessage",
        "NativeCommandErrorMessage",
        "NativeCommandErrorMessage"
    )
    $currentFileTransport = $ExitCode -eq 1 -and $Output.Count -eq 5
    for ($index = 0; $currentFileTransport -and $index -lt 5; $index++) {
        $record = $Output[$index]
        $expectedTarget = if ($index -eq 0) { $expectedMessages[$index] } else { "" }
        $currentFileTransport =
            $record -is [Management.Automation.ErrorRecord] -and
            $null -ne $record.Exception -and
            $record.Exception.GetType() -eq [Management.Automation.RemoteException] -and
            [string]$record.FullyQualifiedErrorId -ceq $expectedIds[$index] -and
            [string]$record.CategoryInfo.Category -ceq "NotSpecified" -and
            [string]$record.TargetObject -ceq $expectedTarget -and
            [string]$record.Exception.Message -ceq $expectedMessages[$index]
    }
    if (-not $currentFileTransport) {
        $escape = [char]27
        $red = $escape + "[31;1m"
        $reset = $escape + "[0m"
        $cyan = $escape + "[36;1m"
        [string[]]$expectedAnsiMessages = @(
            ($red + "Exception: " + $reset + $absoluteVerifierScript + ":" + $VerifierHoldLine + $reset),
            ($red + $reset + $cyan + "Line |" + $reset),
            ($red + $reset + $cyan + $cyan + (" {0,3} | " -f $VerifierHoldLine) +
                $reset + "         " + $cyan +
                "throw `"Protected changes do not match an exact base-approved " +
                $reset + " …" + $reset),
            ($red + $reset + $cyan + $cyan + $reset + $cyan + $reset + $cyan +
                "     | " + $red +
                "         ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~" + $reset),
            ($red + $reset + $cyan + $cyan + $reset + $cyan + $reset + $cyan +
                $red + $red + $cyan + "     | " + $red + $holdMessage + $reset
            )
        )
        $currentAnsiTransport = $VerifierHoldLine -eq 969 -and
            $ExitCode -eq 1 -and $Output.Count -eq 5
        for ($index = 0; $currentAnsiTransport -and $index -lt 5; $index++) {
            $record = $Output[$index]
            $expectedTarget = if ($index -eq 0) {
                $expectedAnsiMessages[$index]
            } else { "" }
            $currentAnsiTransport =
                $record -is [Management.Automation.ErrorRecord] -and
                $record.GetType() -eq [Management.Automation.ErrorRecord] -and
                $null -ne $record.Exception -and
                $record.Exception.GetType() -eq [Management.Automation.RemoteException] -and
                [string]::Equals(
                    [string]$record.FullyQualifiedErrorId, $expectedIds[$index],
                    [StringComparison]::Ordinal
                ) -and
                [string]::Equals(
                    [string]$record.CategoryInfo.Category, "NotSpecified",
                    [StringComparison]::Ordinal
                ) -and
                (Test-ExternalOwnerExactUtf8TransportText `
                    -Actual ([string]$record.TargetObject) -Expected $expectedTarget) -and
                (Test-ExternalOwnerExactUtf8TransportText `
                    -Actual ([string]$record.Exception.Message) `
                    -Expected $expectedAnsiMessages[$index])
        }
        if (-not $currentAnsiTransport) {
            throw "Pinned verifier result is not the exact protected-without-base-approval hold."
        }
    }
}

function New-ExternalOwnerAuthorizationRequest {
    param([Parameter(Mandatory)][object]$Policy, [Parameter(Mandatory)][int]$PullRequestNumber, [Parameter(Mandatory)][object]$Base, [Parameter(Mandatory)][object]$Head, [Parameter(Mandatory)][object[]]$ChangedArtifacts, [Parameter(Mandatory)][object[]]$ProtectedArtifacts, [Parameter(Mandatory)][object]$Assessment)
    $changedPaths = @($ChangedArtifacts | ForEach-Object { [string]$_.path })
    $protectedPaths = @($ProtectedArtifacts | ForEach-Object { [string]$_.path })
    Assert-ExternalOwnerArtifactInventory `
        -ChangedPaths $changedPaths -ChangedArtifacts $ChangedArtifacts `
        -ProtectedPaths $protectedPaths -ProtectedArtifacts $ProtectedArtifacts
    $stableAssessment = $Assessment | ConvertTo-Json -Depth 30 -Compress | ConvertFrom-Json -Depth 30 -DateKind String
    $assessmentChallenge = New-ExternalOwnerAuthorizationAssessmentChallenge `
        -Assessment $stableAssessment
    return [ordered]@{
        schema = "rusty.quest.external_owner_authorization_request.v1"
        issuer_id = [string]$Policy.issuer_id
        key_id = [string]$Policy.key_id
        repository = "MesmerPrism/rusty-quest"
        pull_request_number = $PullRequestNumber
        base = $Base
        head = $Head
        changed_artifacts = $ChangedArtifacts
        protected_artifacts = $ProtectedArtifacts
        assessment = $stableAssessment
        assessment_sha256 = Get-ExternalOwnerSha256 `
            (Get-CanonicalAuthorizationBytes $assessmentChallenge)
        limitations = @("candidate_code_executed=false","execution_attested=false","acceptance_authority=false","publication_authority=false")
    }
}

function New-ExternalOwnerAuthorizationAssessmentChallenge {
    param([Parameter(Mandatory)][object]$Assessment)
    return [ordered]@{
        domain = "rusty.quest.external_owner_authorization_assessment_challenge.v1"
        assessment_schema = $Assessment.schema
        policy_id = $Assessment.policy_id
        policy_sha256 = $Assessment.policy_sha256
        repository = $Assessment.repository
        pull_request_number = $Assessment.pull_request_number
        event_identity = [ordered]@{
            base_repository = $Assessment.event_identity.base_repository
            base_ref = $Assessment.event_identity.base_ref
            head_repository = $Assessment.event_identity.head_repository
        }
        workflow_event = $Assessment.workflow.event
        base = $Assessment.base
        candidate = $Assessment.candidate
        merge = $Assessment.merge
        changed_paths = @($Assessment.changed_paths)
        protected_paths = @($Assessment.protected_paths)
        decision = $Assessment.decision
        approval_id = $Assessment.approval_id
        candidate_code_executed = $Assessment.candidate_code_executed
        execution_attested = $Assessment.execution_attested
        publication_authority = $Assessment.publication_authority
        limitations = @($Assessment.limitations)
    }
}

function New-ExternalOwnerAuthorizationRequestChallenge {
    param([Parameter(Mandatory)][object]$Request)
    $assessmentChallenge = New-ExternalOwnerAuthorizationAssessmentChallenge `
        -Assessment $Request.assessment
    $assessmentHash = Get-ExternalOwnerSha256 `
        (Get-CanonicalAuthorizationBytes $assessmentChallenge)
    if ($assessmentHash -cne [string]$Request.assessment_sha256) {
        throw "Authorization request assessment challenge hash is inconsistent."
    }
    return [ordered]@{
        domain = "rusty.quest.external_owner_authorization_request_challenge.v1"
        request_schema = [string]$Request.schema
        issuer_id = [string]$Request.issuer_id
        key_id = [string]$Request.key_id
        repository = [string]$Request.repository
        pull_request_number = [int]$Request.pull_request_number
        base = $Request.base
        head = $Request.head
        changed_artifacts = @($Request.changed_artifacts)
        protected_artifacts = @($Request.protected_artifacts)
        assessment = $assessmentChallenge
        assessment_sha256 = $assessmentHash
        limitations = @($Request.limitations)
    }
}

function New-ExternalOwnerAuthorizationPayload {
    param([Parameter(Mandatory)][object]$Request, [Parameter(Mandatory)][string]$AuditId, [Parameter(Mandatory)][string]$IssuedAt, [Parameter(Mandatory)][string]$ExpiresAt)
    $requestChallenge = New-ExternalOwnerAuthorizationRequestChallenge `
        -Request $Request
    return [ordered]@{
        schema = "rusty.quest.external_owner_authorization_payload.v1"
        issuer_id = [string]$Request.issuer_id
        key_id = [string]$Request.key_id
        audit_id = $AuditId
        repository = [string]$Request.repository
        pull_request_number = [int]$Request.pull_request_number
        base = $Request.base
        head = $Request.head
        changed_artifacts = @($Request.changed_artifacts)
        protected_artifacts = @($Request.protected_artifacts)
        assessment_sha256 = [string]$Request.assessment_sha256
        request_sha256 = Get-ExternalOwnerSha256 `
            (Get-CanonicalAuthorizationBytes $requestChallenge)
        issued_at = $IssuedAt
        expires_at = $ExpiresAt
        decision = "authorize-static-assessment"
        limitations = @($Request.limitations)
    }
}

function Test-ExternalOwnerAuthorizationComments {
    param([Parameter(Mandatory)][object[]]$Comments, [Parameter(Mandatory)][object]$ExpectedPayload, [Parameter(Mandatory)][object]$Policy, [datetimeoffset]$Now = [datetimeoffset]::UtcNow, [Parameter(Mandatory)][string]$SchemaPath)
    Initialize-ExternalOwnerAuthorizationTypes
    if ($Comments.Count -gt [int]$Policy.maximum_comments) { throw "Comment count exceeds the configured bound." }
    $bootstrapMarker = [string]$Policy.bootstrap_comment_marker
    if ([string]::IsNullOrWhiteSpace($bootstrapMarker)) {
        throw "External-owner policy lacks the bootstrap-marker rejection binding."
    }
    $bootstrapMarkerPattern = "(?m)^$([regex]::Escape($bootstrapMarker))$"
    $bootstrapMarked = @($Comments | Where-Object {
        [string]$_.user.login -ceq [string]$Policy.owner_login -and
        [regex]::Matches([string]$_.body, $bootstrapMarkerPattern).Count -gt 0
    })
    if ($bootstrapMarked.Count -ne 0) {
        throw "Bootstrap authorization markers are never accepted by the normal external-owner fallback."
    }
    $markerPattern = "(?m)^$([regex]::Escape([string]$Policy.comment_marker))$"
    $marked = @($Comments | Where-Object { [string]$_.user.login -ceq [string]$Policy.owner_login -and [regex]::Matches([string]$_.body,$markerPattern).Count -gt 0 })
    if ($marked.Count -ne 1) { throw "Exactly one pinned-owner authorization marker is required." }
    $comment = $marked[0]
    if ($null -eq $comment.id -or [string]$comment.created_at -cne [string]$comment.updated_at) { throw "Authorization comment identity or edit state is invalid." }
    if ([Text.Encoding]::UTF8.GetByteCount([string]$comment.body) -gt [int]$Policy.maximum_comment_bytes) { throw "Authorization comment exceeds the size bound." }
    $lines = ([string]$comment.body) -split "\r?\n", 2
    if ($lines.Count -ne 2 -or $lines[0] -cne [string]$Policy.comment_marker) { throw "Authorization marker framing is not canonical." }
    $document = ConvertFrom-ExternalOwnerJsonStrict $lines[1]
    $documentJson = $document | ConvertTo-Json -Depth 30 -Compress
    if (-not (Test-Json -Json $documentJson -SchemaFile $SchemaPath -ErrorAction Stop)) { throw "Authorization document failed its schema." }
    if ([string]$document.payload.issuer_id -cne [string]$Policy.issuer_id -or [string]$document.payload.key_id -cne [string]$Policy.key_id -or [string]$document.signature.algorithm -cne "RSA-PSS-SHA256" -or [string]$document.signature.public_key_spki_sha256 -cne [string]$Policy.public_key_spki_sha256) { throw "Authorization issuer, key, or algorithm is not pinned." }
    $actual = Get-CanonicalAuthorizationBytes $document.payload
    $expected = Get-CanonicalAuthorizationBytes $ExpectedPayload
    if (-not [Security.Cryptography.CryptographicOperations]::FixedTimeEquals($actual,$expected)) { throw "Authorization payload does not equal the exact expected evidence." }
    $issued = [datetimeoffset]::ParseExact([string]$document.payload.issued_at,"yyyy-MM-dd'T'HH:mm:ss'Z'",[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::AssumeUniversal)
    $expires = [datetimeoffset]::ParseExact([string]$document.payload.expires_at,"yyyy-MM-dd'T'HH:mm:ss'Z'",[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::AssumeUniversal)
    if ($issued -gt $Now.AddSeconds([int]$Policy.max_future_skew_seconds) -or $issued -lt $Now.AddSeconds(-[int]$Policy.max_authorization_age_seconds) -or $expires -le $Now -or $expires -le $issued -or $expires -gt $issued.AddSeconds([int]$Policy.max_authorization_age_seconds)) { throw "Authorization freshness is invalid." }
    try { [byte[]]$signature = [Convert]::FromBase64String([string]$document.signature.value_base64) } catch { throw "Authorization signature is not canonical base64." }
    if ([Convert]::ToBase64String($signature) -cne [string]$document.signature.value_base64 -or -not [RustyQuest.ExternalOwnerCrypto]::Verify([string]$Policy.public_key_pem,$actual,$signature)) { throw "Authorization signature verification failed." }
    return $document.payload
}

Export-ModuleMember -Function Get-CanonicalAuthorizationBytes, Get-ExternalOwnerSha256, ConvertFrom-ExternalOwnerJsonStrict, Read-ExternalOwnerAuthorizationPolicy, ConvertFrom-ExternalOwnerGitNameStatusBytes, Assert-ExternalOwnerArtifactInventory, Assert-ExternalOwnerProtectedWithoutBaseApprovalAssessment, New-ExternalOwnerProtectedWithoutBaseApprovalAssessment, Assert-ExternalOwnerFallbackVerifierFailure, New-ExternalOwnerAuthorizationRequest, New-ExternalOwnerAuthorizationPayload, Test-ExternalOwnerAuthorizationComments
