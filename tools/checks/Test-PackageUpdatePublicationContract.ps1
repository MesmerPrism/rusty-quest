[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "..\package_updater\PublicationContract.ps1")

function Assert-Rejected {
    param([scriptblock]$Action, [string]$Name)
    try {
        & $Action
        throw "Self-test case '$Name' unexpectedly passed."
    } catch {
        if ($_.Exception.Message -like "Self-test case * unexpectedly passed.") {
            throw
        }
    }
}

$tuple = Get-PackageUpdateTuple `
    -Channel labs `
    -PackageName io.github.mesmerprism.rustykiosk.labs `
    -RolloutRing labs `
    -SignerSha256 ("sha256:" + ("2" * 64)) `
    -KeyId release-test-a `
    -PublicKey ("A" * 43) `
    -HttpsOrigin https://updates.example.test `
    -SiteBasePath rusty-quest

Assert-PackageUpdateCanonicalInputs `
    -HttpsOrigin https://updates.example.test `
    -SiteBasePath rusty-quest `
    -ChannelPath package-updates/rusty-kiosk/labs `
    -Channel labs `
    -PackageName io.github.mesmerprism.rustykiosk.labs `
    -RolloutRing labs `
    -KeyId release-test-a `
    -PublicKey ("A" * 43) `
    -SignerSha256 ("sha256:" + ("2" * 64)) `
    -IssuedAtMs 1000 `
    -ExpiresAtMs 86401000
Assert-Rejected {
    Assert-PackageUpdateCanonicalInputs `
        -HttpsOrigin https://updates.example.test `
        -SiteBasePath rusty-quest `
        -ChannelPath package-updates/rusty-kiosk/labs `
        -Channel labs `
        -PackageName io.github.mesmerprism.rustykiosk.labs `
        -RolloutRing labs `
        -KeyId release-test-a `
        -PublicKey ("A" * 43) `
        -SignerSha256 ("sha256:" + ("2" * 64)) `
        -IssuedAtMs 1000 `
        -ExpiresAtMs 86401001
} "24h plus one"
Assert-PackageUpdateArtifactSize -SizeBytes 104857600
Assert-Rejected {
    Assert-PackageUpdateArtifactSize -SizeBytes 0
} "zero-byte APK"
Assert-Rejected {
    Assert-PackageUpdateArtifactSize -SizeBytes 104857601
} "APK above updater policy"

$pointerValue = [ordered]@{
    schema = "rusty.quest.package_update_channel_pointer.v2"
    generation = "s40-v100-aaaaaaaaaaaaaaaa-1111111111111111"
    envelope_sha256 = "sha256:" + ("1" * 64)
    sequence = 40
    version_code = 100
    channel = $tuple.channel
    package_name = $tuple.package_name
    rollout_ring = $tuple.rollout_ring
    signer_sha256 = $tuple.signer_sha256
    key_id = $tuple.key_id
    public_key = $tuple.public_key
    https_origin = $tuple.https_origin
    site_base_path = $tuple.site_base_path
}
$pointerBytes = [System.Text.Encoding]::UTF8.GetBytes(
    ($pointerValue | ConvertTo-Json -Compress)
)
$pointerValidationRoot = Join-Path (
    [System.IO.Path]::GetTempPath()
) ("package-update-pointer-validation-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $pointerValidationRoot | Out-Null
    foreach ($forbiddenGeneration in @(
        ".",
        "..",
        "../escape",
        "s41-v100-aaaaaaaaaaaaaaaa-1111111111111111",
        "s40-v101-aaaaaaaaaaaaaaaa-1111111111111111",
        "s40-v100-aaaaaaaaaaaaaaaa-2222222222222222"
    )) {
        $invalidPointer = [ordered]@{}
        foreach ($entry in $pointerValue.GetEnumerator()) {
            $invalidPointer[$entry.Key] = $entry.Value
        }
        $invalidPointer.generation = $forbiddenGeneration
        $invalidPath = Join-Path $pointerValidationRoot (
            "pointer-$([guid]::NewGuid().ToString('N')).json"
        )
        $invalidPointer | ConvertTo-Json -Compress |
            Set-Content -LiteralPath $invalidPath -Encoding utf8NoBOM
        Assert-Rejected {
            Read-PackageUpdatePointer -Path $invalidPath | Out-Null
        } "generation traversal '$forbiddenGeneration'"
    }
    $canonicalPointerJson = $pointerValue | ConvertTo-Json -Compress
    foreach ($damagedNumber in @(
        @{ field = "sequence"; value = "40.0" },
        @{ field = "sequence"; value = "4e1" },
        @{ field = "sequence"; value = '"40"' },
        @{ field = "sequence"; value = "0" },
        @{ field = "sequence"; value = "9007199254740992" },
        @{ field = "version_code"; value = "100.0" },
        @{ field = "version_code"; value = "1e2" },
        @{ field = "version_code"; value = '"100"' },
        @{ field = "version_code"; value = "0" },
        @{ field = "version_code"; value = "9007199254740992" }
    )) {
        $invalidPath = Join-Path $pointerValidationRoot (
            "pointer-$([guid]::NewGuid().ToString('N')).json"
        )
        $damagedJson = $canonicalPointerJson -replace (
            '"' + $damagedNumber.field + '":[0-9]+'
        ), ('"' + $damagedNumber.field + '":' + $damagedNumber.value)
        Set-Content -LiteralPath $invalidPath -Value $damagedJson -Encoding utf8NoBOM
        Assert-Rejected {
            Read-PackageUpdatePointer -Path $invalidPath | Out-Null
        } "noncanonical pointer number $($damagedNumber.field)=$($damagedNumber.value)"
    }
} finally {
    if (Test-Path -LiteralPath $pointerValidationRoot) {
        Remove-Item -LiteralPath $pointerValidationRoot -Recurse -Force
    }
}
$prior = [pscustomobject]@{
    value = $pointerValue
    bytes = $pointerBytes
    sha256 = "sha256:" + (
        [Convert]::ToHexString(
            [System.Security.Cryptography.SHA256]::HashData($pointerBytes)
        ).ToLowerInvariant()
    )
}
$priorArtifact = [ordered]@{
    package_name = "io.github.mesmerprism.rustykiosk.labs"
    version_code = 100
    version_name = "0.6.5"
    apk_url = "https://updates.example.test/rusty-quest/package-updates/rusty-kiosk/labs/artifacts/sha256/$('a' * 64)/rusty-kiosk-0.6.5.apk"
    apk_sha256 = "sha256:" + ("a" * 64)
    apk_size_bytes = 1234
    signer_sha256 = $tuple.signer_sha256
}
$candidateArtifact = [ordered]@{}
foreach ($entry in $priorArtifact.GetEnumerator()) {
    $candidateArtifact[$entry.Key] = $entry.Value
}
$candidateArtifact.version_code = 101
$candidateArtifact.version_name = "0.6.6"
$candidateArtifact.apk_sha256 = "sha256:" + ("b" * 64)
$candidateArtifact.apk_url = "https://updates.example.test/rusty-quest/package-updates/rusty-kiosk/labs/artifacts/sha256/$('b' * 64)/rusty-kiosk-0.6.6.apk"
$candidateArtifact.apk_size_bytes = 2345
$priorEnvelope = [ordered]@{
    key_id = $tuple.key_id
    signed = [ordered]@{
        sequence = 40
        channel = $tuple.channel
        rollout_ring = $tuple.rollout_ring
        artifact = $priorArtifact
    }
}
Assert-PackageUpdatePrior `
    -Prior $prior `
    -ExpectedPriorPointerSha256 $prior.sha256 `
    -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
    -Tuple $tuple `
    -Sequence 41 `
    -VersionCode 101 `
    -PriorEnvelope $priorEnvelope `
    -CandidateArtifact $candidateArtifact
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $prior `
        -ExpectedPriorPointerSha256 ("sha256:" + ("0" * 64)) `
        -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
        -Tuple $tuple -Sequence 41 -VersionCode 101 `
        -PriorEnvelope $priorEnvelope -CandidateArtifact $candidateArtifact
} "stale caller"
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $prior `
        -ExpectedPriorPointerSha256 $prior.sha256 `
        -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
        -Tuple $tuple -Sequence 40 -VersionCode 101 `
        -PriorEnvelope $priorEnvelope -CandidateArtifact $candidateArtifact
} "sequence downgrade"
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $prior `
        -ExpectedPriorPointerSha256 $prior.sha256 `
        -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
        -Tuple $tuple -Sequence 41 -VersionCode 100 `
        -PriorEnvelope $priorEnvelope -CandidateArtifact $priorArtifact
} "version downgrade"
$driftTuple = [ordered]@{}
foreach ($entry in $tuple.GetEnumerator()) {
    $driftTuple[$entry.Key] = $entry.Value
}
$driftTuple.signer_sha256 = "sha256:" + ("3" * 64)
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $prior `
        -ExpectedPriorPointerSha256 $prior.sha256 `
        -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
        -Tuple $driftTuple -Sequence 41 -VersionCode 101 `
        -PriorEnvelope $priorEnvelope -CandidateArtifact $candidateArtifact
} "tuple drift"
$wrongGenerationValue = [ordered]@{}
foreach ($entry in $pointerValue.GetEnumerator()) {
    $wrongGenerationValue[$entry.Key] = $entry.Value
}
$wrongGenerationValue.generation =
    "s40-v100-cccccccccccccccc-$($pointerValue.envelope_sha256.Substring(7,16))"
$wrongGenerationPrior = [pscustomobject]@{
    value = $wrongGenerationValue
    bytes = $pointerBytes
    sha256 = $prior.sha256
}
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $wrongGenerationPrior `
        -ExpectedPriorPointerSha256 $wrongGenerationPrior.sha256 `
        -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
        -Tuple $tuple -Sequence 41 -VersionCode 101 `
        -PriorEnvelope $priorEnvelope -CandidateArtifact $candidateArtifact
} "generation APK hash prefix drift"
foreach ($damage in @(
    @{ path = "key_id"; value = "other-key" },
    @{ path = "channel"; value = "stable" },
    @{ path = "rollout_ring"; value = "other-ring" },
    @{ path = "package_name"; value = "io.github.mesmerprism.other" },
    @{ path = "signer_sha256"; value = "sha256:" + ("3" * 64) },
    @{ path = "apk_url"; value = "https://evil.example.test/app.apk" }
)) {
    $damagedEnvelope = $priorEnvelope | ConvertTo-Json -Depth 8 |
        ConvertFrom-Json -AsHashtable
    if ($damage.path -eq "key_id") {
        $damagedEnvelope.key_id = $damage.value
    } elseif ($damage.path -in @("channel", "rollout_ring")) {
        $damagedEnvelope.signed[$damage.path] = $damage.value
    } else {
        $damagedEnvelope.signed.artifact[$damage.path] = $damage.value
    }
    Assert-Rejected {
        Assert-PackageUpdatePrior -Prior $prior `
            -ExpectedPriorPointerSha256 $prior.sha256 `
            -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
            -Tuple $tuple -Sequence 41 -VersionCode 101 `
            -PriorEnvelope $damagedEnvelope -CandidateArtifact $candidateArtifact
    } "prior envelope $($damage.path) drift"
}
Assert-PackageUpdatePrior -Prior $prior `
    -ExpectedPriorPointerSha256 $prior.sha256 `
    -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
    -Tuple $tuple -Sequence 41 -VersionCode 100 `
    -PriorEnvelope $priorEnvelope -CandidateArtifact $priorArtifact -Refresh
$driftArtifact = [ordered]@{}
foreach ($entry in $priorArtifact.GetEnumerator()) {
    $driftArtifact[$entry.Key] = $entry.Value
}
$driftArtifact.apk_sha256 = "sha256:" + ("c" * 64)
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $prior `
        -ExpectedPriorPointerSha256 $prior.sha256 `
        -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
        -Tuple $tuple -Sequence 41 -VersionCode 100 `
        -PriorEnvelope $priorEnvelope -CandidateArtifact $driftArtifact -Refresh
} "refresh artifact drift"

$legacyOrigin = "https://mesmerprism.github.io"
$canonicalOrigin = "https://mesmerprism.com"
$legacyTuple = Get-PackageUpdateTuple `
    -Channel $tuple.channel `
    -PackageName $tuple.package_name `
    -RolloutRing $tuple.rollout_ring `
    -SignerSha256 $tuple.signer_sha256 `
    -KeyId $tuple.key_id `
    -PublicKey $tuple.public_key `
    -HttpsOrigin $legacyOrigin `
    -SiteBasePath $tuple.site_base_path
$canonicalTuple = Get-PackageUpdateTuple `
    -Channel $tuple.channel `
    -PackageName $tuple.package_name `
    -RolloutRing $tuple.rollout_ring `
    -SignerSha256 $tuple.signer_sha256 `
    -KeyId $tuple.key_id `
    -PublicKey $tuple.public_key `
    -HttpsOrigin $canonicalOrigin `
    -SiteBasePath $tuple.site_base_path
$migrationPointerValue = [ordered]@{}
foreach ($entry in $pointerValue.GetEnumerator()) {
    $migrationPointerValue[$entry.Key] = $entry.Value
}
$migrationPointerValue.https_origin = $legacyOrigin
$migrationPointerBytes = [Text.Encoding]::UTF8.GetBytes(
    ($migrationPointerValue | ConvertTo-Json -Compress)
)
$migrationPrior = [pscustomobject]@{
    value = $migrationPointerValue
    bytes = $migrationPointerBytes
    sha256 = "sha256:" + (
        [Convert]::ToHexString(
            [Security.Cryptography.SHA256]::HashData($migrationPointerBytes)
        ).ToLowerInvariant()
    )
}
$migrationPriorArtifact = [ordered]@{}
foreach ($entry in $priorArtifact.GetEnumerator()) {
    $migrationPriorArtifact[$entry.Key] = $entry.Value
}
$migrationPriorArtifact.apk_url =
    "$legacyOrigin/rusty-quest/package-updates/rusty-kiosk/labs/" +
    "artifacts/sha256/$('a' * 64)/rusty-kiosk-0.6.5.apk"
$migrationCandidateArtifact = [ordered]@{}
foreach ($entry in $migrationPriorArtifact.GetEnumerator()) {
    $migrationCandidateArtifact[$entry.Key] = $entry.Value
}
$migrationCandidateArtifact.apk_url =
    "$canonicalOrigin/rusty-quest/package-updates/rusty-kiosk/labs/" +
    "artifacts/sha256/$('a' * 64)/rusty-kiosk-0.6.5.apk"
$migrationEnvelope = [ordered]@{
    key_id = $legacyTuple.key_id
    signed = [ordered]@{
        sequence = 40
        channel = $legacyTuple.channel
        rollout_ring = $legacyTuple.rollout_ring
        artifact = $migrationPriorArtifact
    }
}
Assert-PackageUpdatePrior -Prior $migrationPrior `
    -ExpectedPriorPointerSha256 $migrationPrior.sha256 `
    -ExpectedPriorEnvelopeSha256 $migrationPointerValue.envelope_sha256 `
    -Tuple $canonicalTuple -Sequence 41 -VersionCode 100 `
    -PriorEnvelope $migrationEnvelope `
    -CandidateArtifact $migrationCandidateArtifact -Refresh `
    -MigrateGitHubPagesProjectOriginToCustomDomain
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $migrationPrior `
        -ExpectedPriorPointerSha256 $migrationPrior.sha256 `
        -ExpectedPriorEnvelopeSha256 $migrationPointerValue.envelope_sha256 `
        -Tuple $canonicalTuple -Sequence 41 -VersionCode 100 `
        -PriorEnvelope $migrationEnvelope `
        -CandidateArtifact $migrationCandidateArtifact -Refresh
} "origin drift without sealed migration"
$alternatePublicKeyPrefix = if ($canonicalTuple.public_key.StartsWith("A")) {
    "B"
} else {
    "A"
}
$tupleMigrationDamages = @(
    [pscustomobject]@{ Field = "channel"; Value = "alternate-channel" },
    [pscustomobject]@{ Field = "package_name"; Value = "io.github.mesmerprism.other" },
    [pscustomobject]@{ Field = "rollout_ring"; Value = "canary" },
    [pscustomobject]@{ Field = "signer_sha256"; Value = "sha256:" + ("9" * 64) },
    [pscustomobject]@{ Field = "key_id"; Value = "release-manifest-2026-b" },
    [pscustomobject]@{ Field = "public_key"; Value = $alternatePublicKeyPrefix + $canonicalTuple.public_key.Substring(1) },
    [pscustomobject]@{ Field = "site_base_path"; Value = "rusty-quest-alt" }
)
foreach ($damage in $tupleMigrationDamages) {
    $damagedMigrationTuple = [ordered]@{}
    foreach ($entry in $canonicalTuple.GetEnumerator()) {
        $damagedMigrationTuple[$entry.Key] = $entry.Value
    }
    $damagedMigrationTuple[$damage.Field] = $damage.Value
    Assert-Rejected {
        Assert-PackageUpdatePrior -Prior $migrationPrior `
            -ExpectedPriorPointerSha256 $migrationPrior.sha256 `
            -ExpectedPriorEnvelopeSha256 $migrationPointerValue.envelope_sha256 `
            -Tuple $damagedMigrationTuple -Sequence 41 -VersionCode 100 `
            -PriorEnvelope $migrationEnvelope `
            -CandidateArtifact $migrationCandidateArtifact -Refresh `
            -MigrateGitHubPagesProjectOriginToCustomDomain
    } "origin migration tuple $($damage.Field) drift"
}
$artifactMigrationDamages = @(
    [pscustomobject]@{ Field = "package_name"; Value = "io.github.mesmerprism.other" },
    [pscustomobject]@{ Field = "version_code"; Value = 101L },
    [pscustomobject]@{ Field = "version_name"; Value = "0.6.5-alt" },
    [pscustomobject]@{ Field = "apk_sha256"; Value = "sha256:" + ("9" * 64) },
    [pscustomobject]@{ Field = "apk_size_bytes"; Value = 524289L },
    [pscustomobject]@{ Field = "signer_sha256"; Value = "sha256:" + ("8" * 64) }
)
foreach ($damage in $artifactMigrationDamages) {
    $damagedMigrationArtifact = [ordered]@{}
    foreach ($entry in $migrationCandidateArtifact.GetEnumerator()) {
        $damagedMigrationArtifact[$entry.Key] = $entry.Value
    }
    $damagedMigrationArtifact[$damage.Field] = $damage.Value
    Assert-Rejected {
        Assert-PackageUpdatePrior -Prior $migrationPrior `
            -ExpectedPriorPointerSha256 $migrationPrior.sha256 `
            -ExpectedPriorEnvelopeSha256 $migrationPointerValue.envelope_sha256 `
            -Tuple $canonicalTuple -Sequence 41 -VersionCode 100 `
            -PriorEnvelope $migrationEnvelope `
            -CandidateArtifact $damagedMigrationArtifact -Refresh `
            -MigrateGitHubPagesProjectOriginToCustomDomain
    } "origin migration artifact $($damage.Field) drift"
}
$damagedMigrationArtifact = [ordered]@{}
foreach ($entry in $migrationCandidateArtifact.GetEnumerator()) {
    $damagedMigrationArtifact[$entry.Key] = $entry.Value
}
$damagedMigrationArtifact.apk_url =
    "https://evil.example.test/rusty-kiosk-0.6.5.apk"
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $migrationPrior `
        -ExpectedPriorPointerSha256 $migrationPrior.sha256 `
        -ExpectedPriorEnvelopeSha256 $migrationPointerValue.envelope_sha256 `
        -Tuple $canonicalTuple -Sequence 41 -VersionCode 100 `
        -PriorEnvelope $migrationEnvelope `
        -CandidateArtifact $damagedMigrationArtifact -Refresh `
        -MigrateGitHubPagesProjectOriginToCustomDomain
} "origin migration route drift"
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $migrationPrior `
        -ExpectedPriorPointerSha256 $migrationPrior.sha256 `
        -ExpectedPriorEnvelopeSha256 $migrationPointerValue.envelope_sha256 `
        -Tuple $canonicalTuple -Sequence 41 -VersionCode 101 `
        -PriorEnvelope $migrationEnvelope `
        -CandidateArtifact $candidateArtifact `
        -MigrateGitHubPagesProjectOriginToCustomDomain
} "origin migration without authenticated refresh"
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $prior `
        -ExpectedPriorPointerSha256 $prior.sha256 `
        -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
        -Tuple $tuple -Sequence 41 -VersionCode 100 `
        -PriorEnvelope $priorEnvelope -CandidateArtifact $priorArtifact `
        -Refresh -MigrateGitHubPagesProjectOriginToCustomDomain
} "unnecessary origin migration assertion"
Assert-Rejected {
    Assert-PackageUpdatePointerUnchanged -Initial $prior -Current $null
} "interrupted pointer removal"
$interruptionRoot = Join-Path (
    [System.IO.Path]::GetTempPath()
) ("package-update-publication-interruption-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path (
        Join-Path $interruptionRoot "generations\unreferenced"
    ) -Force | Out-Null
    $pointerPath = Join-Path $interruptionRoot "current.json"
    [System.IO.File]::WriteAllBytes($pointerPath, $pointerBytes)
    [System.IO.File]::WriteAllText(
        (Join-Path $interruptionRoot "generations\unreferenced\envelope.json"),
        "{}"
    )
    $afterInterruptedGeneration = Read-PackageUpdatePointer -Path $pointerPath
    Assert-PackageUpdatePointerUnchanged `
        -Initial $prior `
        -Current $afterInterruptedGeneration
} finally {
    if (Test-Path -LiteralPath $interruptionRoot) {
        Remove-Item -LiteralPath $interruptionRoot -Recurse -Force
    }
}
$concurrent = [pscustomobject]@{
    value = $pointerValue
    bytes = [byte[]]($pointerBytes + 10)
    sha256 = "sha256:" + ("4" * 64)
}
Assert-Rejected {
    Assert-PackageUpdatePointerUnchanged -Initial $prior -Current $concurrent
} "concurrent pointer change"
Assert-PackageUpdatePrior -Prior $null -ExpectPriorAbsent `
    -Tuple $tuple -Sequence 1 -VersionCode 1 `
    -CandidateArtifact $candidateArtifact
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $prior -ExpectPriorAbsent `
        -Tuple $tuple -Sequence 1 -VersionCode 1 `
        -CandidateArtifact $candidateArtifact
} "fresh client against existing channel"
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $null -ExpectPriorAbsent `
        -Tuple $tuple -Sequence 1 -VersionCode 1 `
        -CandidateArtifact $candidateArtifact -Refresh
} "refresh without prior"

$observed = ConvertFrom-Aapt2Badging @(
    "package: name='io.github.mesmerprism.rustykiosk' versionCode='101' versionName='0.1.1'"
)
$observed.signer_sha256 = ConvertFrom-ApkSignerCertificates @(
    "Signer #1 certificate SHA-256 digest: " + ("23" * 32)
)
Assert-PackageUpdateObservedApk -Observed $observed `
    -ExpectedPackageName io.github.mesmerprism.rustykiosk `
    -ExpectedVersionCode 101 `
    -ExpectedVersionName 0.1.1 `
    -ExpectedSignerSha256 ("sha256:" + ("23" * 32))
Assert-Rejected {
    ConvertFrom-Aapt2Badging @(
        "package: name='io.github.mesmerprism.other' versionCode='101' versionName='0.1.1'",
        "package: name='io.github.mesmerprism.rustykiosk' versionCode='101' versionName='0.1.1'"
    )
} "multiple package identities"
Assert-Rejected {
    ConvertFrom-ApkSignerCertificates @(
        "Signer #1 certificate SHA-256 digest: " + ("23" * 32),
        "Signer #2 certificate SHA-256 digest: " + ("45" * 32)
    )
} "multiple APK signers"
$wrongObserved = [ordered]@{}
foreach ($entry in $observed.GetEnumerator()) {
    $wrongObserved[$entry.Key] = $entry.Value
}
$wrongObserved.package_name = "io.github.mesmerprism.other"
Assert-Rejected {
    Assert-PackageUpdateObservedApk -Observed $wrongObserved `
        -ExpectedPackageName io.github.mesmerprism.rustykiosk `
        -ExpectedVersionCode 101 `
        -ExpectedVersionName 0.1.1 `
        -ExpectedSignerSha256 ("sha256:" + ("23" * 32))
} "wrong observed package"

$publicTool = Get-PublicPackageUpdateInspectionTool `
    -BuildToolsVersion "35.0.1" `
    -Aapt2Path "C:\Users\alice\AppData\Android\35.0.1\aapt2.exe" `
    -Aapt2Sha256 ("sha256:" + ("a" * 64)) `
    -ApkSignerPath "D:\private\sdk\35.0.1\apksigner.bat" `
    -ApkSignerSha256 ("sha256:" + ("b" * 64))
$publicToolJson = $publicTool | ConvertTo-Json -Compress
foreach ($leak in @(
    "C:\Users\alice",
    "D:\private",
    "aapt2_path",
    "apksigner_path"
)) {
    if ($publicToolJson.Contains($leak)) {
        throw "Public inspection receipt leaked local path material: $leak"
    }
}
if ($publicTool.aapt2_name -ne "aapt2.exe" -or
    $publicTool.apksigner_name -ne "apksigner.bat") {
    throw "Public inspection receipt did not retain bounded tool names."
}

Write-Output "Package update publication contract self-test passed."
