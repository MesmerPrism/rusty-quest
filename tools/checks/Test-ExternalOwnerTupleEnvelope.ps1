[CmdletBinding()]
param([string]$RepoRoot = "", [string]$RequestPath = "")
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root=if ([string]::IsNullOrWhiteSpace($RepoRoot)) {(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path} else {(Resolve-Path -LiteralPath $RepoRoot).Path}
Import-Module (Join-Path $root '.github/scripts/lib/ExternalOwnerAuthorization.psm1') -Force
$schema=Join-Path $root 'schemas/rusty.quest.external_owner_authorization.v1.schema.json'
$policy=Read-ExternalOwnerAuthorizationPolicy (Join-Path $root 'config/external-owner-authorization.json') (Join-Path $root 'schemas/rusty.quest.external_owner_authorization_policy.v1.schema.json')
# Portable deterministic NONAUTHORITY fixture. No source/runtime/PR observation is claimed.
# Its complete present+absent inventory deliberately crosses the v1 comment bound.
if ([string]::IsNullOrWhiteSpace($RequestPath)) {
    $artifacts = @(for ($i=0; $i -lt 269; $i++) {
        [ordered]@{path=('fixtures/validation-authority/non-authority/tuple-boundary-complete-descriptor-artifact-{0:D3}.json' -f $i);state='present';mode='100644';size_bytes=$i;sha256=('a'*64)}
    }) + @([ordered]@{path='fixtures/validation-authority/non-authority/tuple-boundary-complete-descriptor-artifact-999.json';state='absent'})
    $protected = @($artifacts[0..2])
    $baseIdentity = [ordered]@{ commit = ("1" * 40); tree = ("2" * 40) }
    $headIdentity = [ordered]@{ commit = ("3" * 40); tree = ("4" * 40) }
    $mergeIdentity = [ordered]@{ commit = ("5" * 40); tree = ("4" * 40) }
    $assessment = [ordered]@{
        schema = "rusty.quest.external_validation_authority_assessment.v1"
        policy_id = "rusty-quest-external-validation-authority-v1"
        policy_sha256 = ("b" * 64)
        repository = "MesmerPrism/rusty-quest"
        pull_request_number = 999
        event_identity = [ordered]@{
            base_repository = "MesmerPrism/rusty-quest"
            base_ref = "main"
            head_repository = "MesmerPrism/rusty-quest"
            merge_commit_observation = $null
            merge_commit_relation = "event-merge-observation-absent"
        }
        workflow = [ordered]@{
            event = "pull_request_target"
            run_id = "33151222801"
            run_attempt = 1
        }
        runtime = [ordered]@{
            powershell = [ordered]@{
                edition = "Core"
                version = "7.6.5"
                executable_bytes = 301368
                executable_sha256 = ("c" * 64)
            }
            git = [ordered]@{
                version = "git version 2.55.0.windows.5"
                executable_bytes = 43352
                executable_sha256 = ("d" * 64)
            }
            runner = [ordered]@{
                label = "windows-2025"
                os = "Windows"
                architecture = "X64"
                image_os = "win25-vs2026"
                image_version = "20260824.214.3"
                image_allowlist_enforced = $false
                drift_status = "observed-unpinned"
            }
        }
        base = $baseIdentity
        candidate = $headIdentity
        merge = $mergeIdentity
        changed_paths = @($artifacts | ForEach-Object path)
        protected_paths = @($protected | ForEach-Object path)
        decision = "protected-without-base-approval"
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
    $request=New-ExternalOwnerAuthorizationRequest -Policy $policy -PullRequestNumber 999 -Base $baseIdentity -Head $headIdentity -ChangedArtifacts $artifacts -ProtectedArtifacts $protected -Assessment $assessment
    $requestOrigin='generated-public-NONAUTHORITY-fixture'
} else {
    $request=ConvertFrom-ExternalOwnerJsonStrict ([IO.File]::ReadAllText($RequestPath,[Text.UTF8Encoding]::new($false,$true)))
    $requestOrigin='supplied-request-host-regression-only'
}
if (-not (Test-Json -Json ($request | ConvertTo-Json -Depth 30 -Compress) -SchemaFile (Join-Path $root 'schemas/rusty.quest.external_owner_authorization_request.v1.schema.json') -ErrorAction Stop)) {throw 'Regression request failed its closed schema'}
Assert-ExternalOwnerArtifactInventory -ChangedPaths @($request.changed_artifacts | ForEach-Object path) -ChangedArtifacts $request.changed_artifacts -ProtectedPaths @($request.protected_artifacts | ForEach-Object path) -ProtectedArtifacts $request.protected_artifacts
$count=0
function EqualBytes($a,$b) { if (-not [Security.Cryptography.CryptographicOperations]::FixedTimeEquals((Get-CanonicalAuthorizationBytes $a),(Get-CanonicalAuthorizationBytes $b))) {throw 'Canonical bytes differ'} }
function Reject([string]$Name,[scriptblock]$Action) { $caught=$false;try {& $Action | Out-Null} catch {$caught=$true};if(-not $caught){throw "Accepted damage: $Name"};$script:count++ }
function Copy-TupleFixture($a) {ConvertFrom-ExternalOwnerJsonStrict ($a|ConvertTo-Json -Depth 30 -Compress)}
# Ephemeral test key is NONAUTHORITY. No certificate store or approved private key.
$rsa=[Security.Cryptography.RSA]::Create(3072)
try {
 $now=[DateTimeOffset]::ParseExact('2026-10-06T21:20:00Z',"yyyy-MM-dd'T'HH:mm:ss'Z'",[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::AssumeUniversal)
 $payload=New-ExternalOwnerAuthorizationPayload $request 'aa' '2026-10-06T21:20:00Z' '2026-10-06T22:20:00Z'
 $testPolicy=Copy-TupleFixture $policy;$testPolicy.public_key_pem=$rsa.ExportSubjectPublicKeyInfoPem();$testPolicy.public_key_spki_sha256=[RustyQuest.ExternalOwnerCrypto]::SpkiSha256($testPolicy.public_key_pem)
 $sig=[Convert]::ToBase64String($rsa.SignData((Get-CanonicalAuthorizationBytes $payload),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pss))
 $doc=[pscustomobject]@{schema='rusty.quest.external_owner_authorization.v1';payload=$payload;signature=[pscustomobject]@{algorithm='RSA-PSS-SHA256';public_key_spki_sha256=$testPolicy.public_key_spki_sha256;value_base64=$sig}}
 $v1Bytes=[Text.Encoding]::UTF8.GetByteCount($policy.comment_marker+"`n"+($doc|ConvertTo-Json -Depth 30 -Compress))
 if ($requestOrigin-ceq'generated-public-NONAUTHORITY-fixture' -and $v1Bytes-le$policy.maximum_comment_bytes) {throw 'Generated fixture does not exercise the v1 boundary'};$count++
 $wire=ConvertTo-ExternalOwnerTupleEnvelope $doc $testPolicy $schema
 $wireJson=$wire|ConvertTo-Json -Depth 30 -Compress
 $expanded=ConvertFrom-ExternalOwnerAuthorizationEnvelope $wireJson $testPolicy $schema
 EqualBytes $payload $expanded.payload;EqualBytes $doc $expanded;$count+=2
 $bytes=[Text.Encoding]::UTF8.GetByteCount($policy.comment_marker+"`n"+$wireJson)
 if($bytes-gt$policy.maximum_comment_bytes){throw 'Tuple comment exceeds original bound'};$count++
 function Verify($w,$p=$testPolicy,$expected=$payload) {
   $comment=[pscustomobject]@{id=1;user=[pscustomobject]@{login=$p.owner_login};created_at='2026-10-06T21:20:00Z';updated_at='2026-10-06T21:20:00Z';body=$p.comment_marker+"`n"+($w|ConvertTo-Json -Depth 30 -Compress)}
   Test-ExternalOwnerAuthorizationComments -Comments @($comment) -ExpectedPayload $expected -Policy $p -Now $now -SchemaPath $schema
 }
 EqualBytes $payload (Verify $wire);$count++
 $noOpt=Copy-TupleFixture $testPolicy;$noOpt.PSObject.Properties.Remove('allowed_envelope_schemas')
 Reject 'policy-not-opted-in' {Verify $wire $noOpt}
 foreach($case in @('missing','extra','type','state','mode','size-type','size-negative','size-large','hash','duplicate','path','absent-extra','payload-field','signature','context','valid-mode-change','valid-size-change','valid-hash-change','missing-artifact','protected-change')) {
  $bad=Copy-TupleFixture $wire
  switch($case) {
   'missing' {$bad.payload.changed_artifacts[0]=@($bad.payload.changed_artifacts[0][0..3])}
   'extra' {$bad.payload.changed_artifacts[0]=@($bad.payload.changed_artifacts[0])+@('extra')}
   'type' {$bad.payload.changed_artifacts[0]='invalid'}
   'state' {$bad.payload.changed_artifacts[0][1]='unknown'}
   'mode' {$bad.payload.changed_artifacts[0][2]='120000'}
   'size-type' {$bad.payload.changed_artifacts[0][3]='1'}
   'size-negative' {$bad.payload.changed_artifacts[0][3]=-1}
   'size-large' {$bad.payload.changed_artifacts[0][3]=16777217}
   'hash' {$bad.payload.changed_artifacts[0][4]='bad'}
   'duplicate' {$bad.payload.changed_artifacts[1]=$bad.payload.changed_artifacts[0]}
   'path' {$bad.payload.changed_artifacts[0][0]='../escape'}
   'absent-extra' {$i=0;while($bad.payload.changed_artifacts[$i][1]-cne'absent'){$i++};$bad.payload.changed_artifacts[$i]=@($bad.payload.changed_artifacts[$i])+@('100644')}
   'payload-field' {$bad.payload|Add-Member -NotePropertyName extra -NotePropertyValue 'bad'}
   'signature' {$bad.signature.value_base64=[Convert]::ToBase64String([byte[]]::new(384))}
   'context' {$bad.payload.head.commit='0'*40}
   'valid-mode-change' {$bad.payload.changed_artifacts[0][2]=if($bad.payload.changed_artifacts[0][2]-ceq'100644'){'100755'}else{'100644'}}
   'valid-size-change' {$bad.payload.changed_artifacts[0][3]++}
   'valid-hash-change' {$bad.payload.changed_artifacts[0][4]='0'*64}
   'missing-artifact' {$bad.payload.changed_artifacts=@($bad.payload.changed_artifacts | Select-Object -Skip 1)}
   'protected-change' {$bad.payload.protected_artifacts[0][4]='0'*64}
  }
  Reject $case {Verify $bad}
 }
 Reject 'duplicate-json-property' {ConvertFrom-ExternalOwnerAuthorizationEnvelope ($wireJson.Replace('"schema":"rusty.quest.external_owner_authorization.v2"','"schema":"rusty.quest.external_owner_authorization.v2","schema":"rusty.quest.external_owner_authorization.v2"')) $testPolicy $schema}
 Reject 'fractional-size' {ConvertFrom-ExternalOwnerAuthorizationEnvelope ($wireJson -replace ',"present","100644",[0-9]+,',',"present","100644",1.5,') $testPolicy $schema}
 # v1 wire remains unchanged and works without the new optional policy field.
 $small=Copy-TupleFixture $doc;$small.payload.changed_artifacts=@($small.payload.changed_artifacts[0]);$small.payload.protected_artifacts=@($small.payload.changed_artifacts[0])
 $small.signature.value_base64=[Convert]::ToBase64String($rsa.SignData((Get-CanonicalAuthorizationBytes $small.payload),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pss))
 EqualBytes $small (ConvertFrom-ExternalOwnerAuthorizationEnvelope ($small|ConvertTo-Json -Depth 30 -Compress) $noOpt $schema);$count++
 EqualBytes $small.payload (Verify $small $noOpt $small.payload);$count++
 [pscustomobject]@{status='passed';controls=$count;changed_artifacts=$request.changed_artifacts.Count;protected_artifacts=$request.protected_artifacts.Count;wire_comment_bytes=$bytes;maximum_comment_bytes=$policy.maximum_comment_bytes;request_origin=$requestOrigin;request_canonical_sha256=Get-ExternalOwnerSha256 (Get-CanonicalAuthorizationBytes $request);v1_comment_bytes=$v1Bytes;canonical_payload_sha256=Get-ExternalOwnerSha256 (Get-CanonicalAuthorizationBytes $payload);authority='NONAUTHORITY ephemeral test key; no signing authorization, comment or runtime acceptance'}|ConvertTo-Json
} finally {$rsa.Dispose()}
