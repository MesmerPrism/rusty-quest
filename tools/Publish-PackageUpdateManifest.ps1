[CmdletBinding(DefaultParameterSetName = "PinnedPrior")]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,
    [Parameter(Mandatory = $true)]
    [string]$AndroidBuildToolsDirectory,
    [Parameter(Mandatory = $true)]
    [string]$HttpsOrigin,
    [string]$ChannelPath = "package-updates/rusty-kiosk/alpha",
    [ValidateSet("alpha")]
    [string]$Channel = "alpha",
    [string]$KeyId = "release-manifest-2026-a",
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[A-Za-z0-9_-]{43}$")]
    [string]$TrustedPublicKeyBase64Url,
    [string]$PackageName = "io.github.mesmerprism.rustykiosk",
    [string]$RolloutRing = "alpha",
    [Parameter(Mandatory = $true)]
    [string]$SignerSha256,
    [Parameter(Mandatory = $true)]
    [uint64]$VersionCode,
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[A-Za-z0-9._-]{1,64}$")]
    [string]$VersionName,
    [Parameter(Mandatory = $true)]
    [uint64]$Sequence,
    [Parameter(Mandatory = $true)]
    [uint64]$IssuedAtMs,
    [Parameter(Mandatory = $true)]
    [uint64]$ExpiresAtMs,
    [Parameter(Mandatory = $true)]
    [string]$ManifestId,
    [Parameter(Mandatory = $true, ParameterSetName = "PinnedPrior")]
    [ValidatePattern("^sha256:[0-9a-f]{64}$")]
    [string]$ExpectedPriorPointerSha256,
    [Parameter(Mandatory = $true, ParameterSetName = "PinnedPrior")]
    [ValidatePattern("^sha256:[0-9a-f]{64}$")]
    [string]$ExpectedPriorEnvelopeSha256,
    [Parameter(Mandatory = $true, ParameterSetName = "AbsentPrior")]
    [switch]$ExpectPriorAbsent
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot "package_updater\PublicationContract.ps1")

if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Package update publication requires PowerShell 7.6 Core or newer."
}
if ([string]::IsNullOrWhiteSpace(
        $env:RUSTY_QUEST_UPDATE_SIGNING_SEED_BASE64URL)) {
    throw "RUSTY_QUEST_UPDATE_SIGNING_SEED_BASE64URL is required."
}
Assert-PackageUpdateCanonicalInputs `
    -HttpsOrigin $HttpsOrigin `
    -ChannelPath $ChannelPath `
    -Channel $Channel `
    -PackageName $PackageName `
    -RolloutRing $RolloutRing `
    -KeyId $KeyId `
    -PublicKey $TrustedPublicKeyBase64Url `
    -SignerSha256 $SignerSha256 `
    -IssuedAtMs $IssuedAtMs `
    -ExpiresAtMs $ExpiresAtMs
if ($ManifestId -notmatch "^[A-Za-z0-9._-]{1,128}$") {
    throw "Manifest id is not canonical."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$AndroidBuildToolsDirectory = (
    Resolve-Path -LiteralPath $AndroidBuildToolsDirectory
).Path
$aapt2 = Join-Path $AndroidBuildToolsDirectory "aapt2.exe"
$apkSigner = Join-Path $AndroidBuildToolsDirectory "apksigner.bat"
foreach ($tool in @($aapt2, $apkSigner)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Pinned Android build tool is missing: $tool"
    }
}
$aapt2Hash = "sha256:" + (
    Get-FileHash -LiteralPath $aapt2 -Algorithm SHA256
).Hash.ToLowerInvariant()
$apkSignerHash = "sha256:" + (
    Get-FileHash -LiteralPath $apkSigner -Algorithm SHA256
).Hash.ToLowerInvariant()

$badging = @(& $aapt2 dump badging $ApkPath 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Pinned aapt2 could not inspect the candidate APK."
}
$observed = ConvertFrom-Aapt2Badging -Lines $badging
$signerOutput = @(& $apkSigner verify --verbose --print-certs $ApkPath 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Pinned apksigner rejected the candidate APK."
}
$observed.signer_sha256 = ConvertFrom-ApkSignerCertificates -Lines $signerOutput
Assert-PackageUpdateObservedApk `
    -Observed $observed `
    -ExpectedPackageName $PackageName `
    -ExpectedVersionCode $VersionCode `
    -ExpectedVersionName $VersionName `
    -ExpectedSignerSha256 $SignerSha256

$tuple = Get-PackageUpdateTuple `
    -Channel $Channel `
    -PackageName $PackageName `
    -RolloutRing $RolloutRing `
    -SignerSha256 $SignerSha256 `
    -KeyId $KeyId `
    -PublicKey $TrustedPublicKeyBase64Url `
    -HttpsOrigin $HttpsOrigin
$channelDirectory = Join-Path $OutputDirectory (
    $ChannelPath.Replace("/", [System.IO.Path]::DirectorySeparatorChar)
)
$generationRoot = Join-Path $channelDirectory "generations"
$pointerPath = Join-Path $channelDirectory "current.json"
$lockPath = Join-Path $channelDirectory "publication.lock"
New-Item -ItemType Directory -Path $channelDirectory -Force | Out-Null

$lock = $null
$stagingRoot = $null
try {
    try {
        $lock = [System.IO.File]::Open(
            $lockPath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
    } catch {
        throw "Another publication owns the channel lock."
    }
    $prior = Read-PackageUpdatePointer -Path $pointerPath
    Assert-PackageUpdatePrior `
        -Prior $prior `
        -ExpectPriorAbsent:$ExpectPriorAbsent `
        -ExpectedPriorPointerSha256 $ExpectedPriorPointerSha256 `
        -ExpectedPriorEnvelopeSha256 $ExpectedPriorEnvelopeSha256 `
        -Tuple $tuple `
        -Sequence $Sequence `
        -VersionCode $VersionCode

    $stagingRoot = Join-Path $OutputDirectory (
        ".package-update-publish-" + [guid]::NewGuid().ToString("N")
    )
    New-Item -ItemType Directory -Path $stagingRoot | Out-Null
    $stagedApk = Join-Path $stagingRoot "artifact.apk"
    $stagedEnvelope = Join-Path $stagingRoot "envelope.json"
    $stagedReceipt = Join-Path $stagingRoot "publication-receipt.json"
    Copy-Item -LiteralPath $ApkPath -Destination $stagedApk
    $apkHash = (
        Get-FileHash -LiteralPath $stagedApk -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    $apkSize = (Get-Item -LiteralPath $stagedApk).Length
    $provisionalGeneration = "s$Sequence-v$VersionCode-$($apkHash.Substring(0,16))"
    $apkFileName = "rusty-kiosk-$VersionName.apk"
    $apkUrl = "$HttpsOrigin/$ChannelPath/generations/$provisionalGeneration/$apkFileName"

    & cargo run `
        --quiet `
        --locked `
        --manifest-path (Join-Path $repoRoot "Cargo.toml") `
        -p rusty-quest-package-updater `
        --bin sign_package_update_manifest `
        -- `
        --key-id $KeyId `
        --channel $Channel `
        --expected-public-key $TrustedPublicKeyBase64Url `
        --manifest-id $ManifestId `
        --package $PackageName `
        --ring $RolloutRing `
        --origin $HttpsOrigin `
        --apk-url $apkUrl `
        --signer-sha256 $SignerSha256 `
        --apk-sha256 "sha256:$apkHash" `
        --apk-size $apkSize `
        --version-code $VersionCode `
        --version-name $VersionName `
        --sequence $Sequence `
        --issued-at-ms $IssuedAtMs `
        --expires-at-ms $ExpiresAtMs `
        --out $stagedEnvelope
    if ($LASTEXITCODE -ne 0) {
        throw "Package update envelope signing failed."
    }
    $envelopeHash = (
        Get-FileHash -LiteralPath $stagedEnvelope -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    $generation = "$provisionalGeneration-$($envelopeHash.Substring(0,16))"
    # The signed URLs bind the immutable directory name. Re-sign once with the
    # final content-addressed name derived from the provisional envelope.
    $apkUrl = "$HttpsOrigin/$ChannelPath/generations/$generation/$apkFileName"
    & cargo run `
        --quiet --locked --manifest-path (Join-Path $repoRoot "Cargo.toml") `
        -p rusty-quest-package-updater --bin sign_package_update_manifest -- `
        --key-id $KeyId --channel $Channel `
        --expected-public-key $TrustedPublicKeyBase64Url `
        --manifest-id $ManifestId --package $PackageName --ring $RolloutRing `
        --origin $HttpsOrigin --apk-url $apkUrl `
        --signer-sha256 $SignerSha256 --apk-sha256 "sha256:$apkHash" `
        --apk-size $apkSize --version-code $VersionCode `
        --version-name $VersionName --sequence $Sequence `
        --issued-at-ms $IssuedAtMs --expires-at-ms $ExpiresAtMs `
        --out $stagedEnvelope
    if ($LASTEXITCODE -ne 0) {
        throw "Final package update envelope signing failed."
    }
    $envelopeIdentity = "sha256:" + (
        Get-FileHash -LiteralPath $stagedEnvelope -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    $sourceRevision = (& git -C $repoRoot rev-parse HEAD).Trim()
    $receipt = [ordered]@{
        schema = "rusty.quest.package_update_publication_receipt.v2"
        source_revision = $sourceRevision
        generation = $generation
        prior_pointer_sha256 = if ($null -eq $prior) { $null } else { $prior.sha256 }
        prior_envelope_sha256 = if ($null -eq $prior) {
            $null
        } else {
            $prior.value.envelope_sha256
        }
        channel = $Channel
        package_name = $PackageName
        rollout_ring = $RolloutRing
        signer_sha256 = $SignerSha256
        key_id = $KeyId
        public_key = $TrustedPublicKeyBase64Url
        https_origin = $HttpsOrigin
        manifest_id = $ManifestId
        manifest_url = "$HttpsOrigin/$ChannelPath/generations/$generation/envelope.json"
        apk_url = $apkUrl
        apk_sha256 = "sha256:$apkHash"
        apk_size_bytes = $apkSize
        envelope_sha256 = $envelopeIdentity
        version_code = $VersionCode
        version_name = $VersionName
        sequence = $Sequence
        issued_at_ms = $IssuedAtMs
        expires_at_ms = $ExpiresAtMs
        observed_apk = [ordered]@{
            package_name = $observed.package_name
            version_code = $observed.version_code
            version_name = $observed.version_name
            signer_sha256 = $observed.signer_sha256
        }
        inspection_tool = [ordered]@{
            build_tools_version = (
                Split-Path -Leaf $AndroidBuildToolsDirectory
            )
            aapt2_path = $aapt2
            aapt2_sha256 = $aapt2Hash
            apksigner_path = $apkSigner
            apksigner_sha256 = $apkSignerHash
        }
    }
    $receipt | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath $stagedReceipt -Encoding utf8NoBOM

    $generationDirectory = Join-Path $generationRoot $generation
    if (Test-Path -LiteralPath $generationDirectory) {
        throw "Immutable generation already exists."
    }
    New-Item -ItemType Directory -Path $generationRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $generationDirectory | Out-Null
    [System.IO.File]::Move(
        $stagedApk,
        (Join-Path $generationDirectory $apkFileName)
    )
    [System.IO.File]::Move(
        $stagedEnvelope,
        (Join-Path $generationDirectory "envelope.json")
    )
    [System.IO.File]::Move(
        $stagedReceipt,
        (Join-Path $generationDirectory "publication-receipt.json")
    )

    $currentBeforeCommit = Read-PackageUpdatePointer -Path $pointerPath
    Assert-PackageUpdatePointerUnchanged -Initial $prior -Current $currentBeforeCommit
    $pointer = [ordered]@{
        schema = "rusty.quest.package_update_channel_pointer.v1"
        generation = $generation
        envelope_sha256 = $envelopeIdentity
        sequence = $Sequence
        version_code = $VersionCode
        channel = $Channel
        package_name = $PackageName
        rollout_ring = $RolloutRing
        signer_sha256 = $SignerSha256
        key_id = $KeyId
        public_key = $TrustedPublicKeyBase64Url
        https_origin = $HttpsOrigin
    }
    $pointerTemporary = Join-Path $channelDirectory (
        ".current-$([guid]::NewGuid().ToString("N")).json"
    )
    $pointer | ConvertTo-Json -Compress |
        Set-Content -LiteralPath $pointerTemporary -Encoding utf8NoBOM
    [System.IO.File]::Move($pointerTemporary, $pointerPath, $true)
    $committed = Read-PackageUpdatePointer -Path $pointerPath
    if ($committed.value.generation -ne $generation -or
        $committed.value.envelope_sha256 -ne $envelopeIdentity) {
        throw "Channel pointer readback differs from the committed generation."
    }
    [pscustomobject]$receipt
} finally {
    if ($null -ne $lock) {
        $lock.Dispose()
    }
    if ($null -ne $stagingRoot -and
        (Test-Path -LiteralPath $stagingRoot)) {
        $resolvedStaging = [System.IO.Path]::GetFullPath($stagingRoot)
        $outputPrefix = $OutputDirectory.TrimEnd(
            [System.IO.Path]::DirectorySeparatorChar
        ) + [System.IO.Path]::DirectorySeparatorChar
        if (-not $resolvedStaging.StartsWith(
                $outputPrefix,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove staging outside the output root."
        }
        Remove-Item -LiteralPath $resolvedStaging -Recurse -Force
    }
}
