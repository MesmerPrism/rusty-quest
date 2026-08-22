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

function New-ExternalOwnerAuthorizationRequest {
    param([Parameter(Mandatory)][object]$Policy, [Parameter(Mandatory)][int]$PullRequestNumber, [Parameter(Mandatory)][object]$Base, [Parameter(Mandatory)][object]$Head, [Parameter(Mandatory)][object[]]$ChangedArtifacts, [Parameter(Mandatory)][object[]]$ProtectedArtifacts, [Parameter(Mandatory)][object]$Assessment)
    foreach ($set in @(@($ChangedArtifacts), @($ProtectedArtifacts))) {
        $paths = @($set | ForEach-Object { [string]$_.path })
        if (($paths -join "`n") -cne (($paths | Sort-Object -CaseSensitive) -join "`n") -or @($paths | Sort-Object -Unique -CaseSensitive).Count -ne $paths.Count) { throw "Authorization artifacts must be complete, unique, and ordinal sorted." }
    }
    $stableAssessment = $Assessment | ConvertTo-Json -Depth 30 -Compress | ConvertFrom-Json -Depth 30 -DateKind String
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
        assessment_sha256 = Get-ExternalOwnerSha256 (Get-CanonicalAuthorizationBytes $stableAssessment)
        limitations = @("candidate_code_executed=false","execution_attested=false","acceptance_authority=false","publication_authority=false")
    }
}

function New-ExternalOwnerAuthorizationPayload {
    param([Parameter(Mandatory)][object]$Request, [Parameter(Mandatory)][string]$AuditId, [Parameter(Mandatory)][string]$IssuedAt, [Parameter(Mandatory)][string]$ExpiresAt)
    if ((Get-ExternalOwnerSha256 (Get-CanonicalAuthorizationBytes $Request.assessment)) -cne [string]$Request.assessment_sha256) { throw "Authorization request assessment hash is inconsistent." }
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
        request_sha256 = Get-ExternalOwnerSha256 (Get-CanonicalAuthorizationBytes $Request)
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

Export-ModuleMember -Function Get-CanonicalAuthorizationBytes, Get-ExternalOwnerSha256, ConvertFrom-ExternalOwnerJsonStrict, Read-ExternalOwnerAuthorizationPolicy, New-ExternalOwnerAuthorizationRequest, New-ExternalOwnerAuthorizationPayload, Test-ExternalOwnerAuthorizationComments
