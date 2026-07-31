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
    -HttpsOrigin https://updates.example.test

Assert-PackageUpdateCanonicalInputs `
    -HttpsOrigin https://updates.example.test `
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

$pointerValue = [ordered]@{
    schema = "rusty.quest.package_update_channel_pointer.v1"
    generation = "s40-v100-test"
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
}
$pointerBytes = [System.Text.Encoding]::UTF8.GetBytes(
    ($pointerValue | ConvertTo-Json -Compress)
)
$prior = [pscustomobject]@{
    value = $pointerValue
    bytes = $pointerBytes
    sha256 = "sha256:" + (
        [Convert]::ToHexString(
            [System.Security.Cryptography.SHA256]::HashData($pointerBytes)
        ).ToLowerInvariant()
    )
}
Assert-PackageUpdatePrior `
    -Prior $prior `
    -ExpectedPriorPointerSha256 $prior.sha256 `
    -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
    -Tuple $tuple `
    -Sequence 41 `
    -VersionCode 101
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $prior `
        -ExpectedPriorPointerSha256 ("sha256:" + ("0" * 64)) `
        -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
        -Tuple $tuple -Sequence 41 -VersionCode 101
} "stale caller"
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $prior `
        -ExpectedPriorPointerSha256 $prior.sha256 `
        -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
        -Tuple $tuple -Sequence 40 -VersionCode 101
} "sequence downgrade"
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $prior `
        -ExpectedPriorPointerSha256 $prior.sha256 `
        -ExpectedPriorEnvelopeSha256 $pointerValue.envelope_sha256 `
        -Tuple $tuple -Sequence 41 -VersionCode 100
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
        -Tuple $driftTuple -Sequence 41 -VersionCode 101
} "tuple drift"
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
    -Tuple $tuple -Sequence 1 -VersionCode 1
Assert-Rejected {
    Assert-PackageUpdatePrior -Prior $prior -ExpectPriorAbsent `
        -Tuple $tuple -Sequence 1 -VersionCode 1
} "fresh client against existing channel"

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
