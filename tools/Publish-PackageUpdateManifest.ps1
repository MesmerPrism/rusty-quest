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
    [string]$SiteBasePath = "rusty-quest",
    [string]$ChannelPath = "package-updates/rusty-kiosk/labs",
    [ValidateSet("labs")]
    [string]$Channel = "labs",
    [string]$KeyId = "release-manifest-2026-a",
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[A-Za-z0-9_-]{43}$")]
    [string]$TrustedPublicKeyBase64Url,
    [string]$PackageName = "io.github.mesmerprism.rustykiosk.labs",
    [string]$RolloutRing = "labs",
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
    [switch]$ExpectPriorAbsent,
    [switch]$Refresh,
    [switch]$MigrateGitHubPagesProjectOriginToCustomDomain
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
    -SiteBasePath $SiteBasePath `
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

$apkHash = (
    Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256
).Hash.ToLowerInvariant()
$apkSize = (Get-Item -LiteralPath $ApkPath).Length
Assert-PackageUpdateArtifactSize -SizeBytes $apkSize
$apkFileName = "rusty-kiosk-$VersionName.apk"
$publicChannelPath = "$SiteBasePath/$ChannelPath"
$apkUrl = "$HttpsOrigin/$publicChannelPath/artifacts/sha256/$apkHash/$apkFileName"
$candidateArtifact = [ordered]@{
    package_name = $PackageName
    version_code = $VersionCode
    version_name = $VersionName
    apk_url = $apkUrl
    apk_sha256 = "sha256:$apkHash"
    apk_size_bytes = $apkSize
    signer_sha256 = $SignerSha256
}

$tuple = Get-PackageUpdateTuple `
    -Channel $Channel `
    -PackageName $PackageName `
    -RolloutRing $RolloutRing `
    -SignerSha256 $SignerSha256 `
    -KeyId $KeyId `
    -PublicKey $TrustedPublicKeyBase64Url `
    -HttpsOrigin $HttpsOrigin `
    -SiteBasePath $SiteBasePath
$channelDirectory = Join-Path $OutputDirectory (
    $ChannelPath.Replace("/", [System.IO.Path]::DirectorySeparatorChar)
)
$generationRoot = Join-Path $channelDirectory "generations"
$pointerPath = Join-Path $channelDirectory "current.json"
$lockIdentity = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData(
        [System.Text.Encoding]::UTF8.GetBytes(
            [System.IO.Path]::GetFullPath($channelDirectory).ToLowerInvariant()
        )
    )
).ToLowerInvariant()
$lockPath = Join-Path ([System.IO.Path]::GetTempPath()) (
    "rusty-quest-package-update-$lockIdentity.lock"
)
New-Item -ItemType Directory -Path $channelDirectory -Force | Out-Null
$noJekyll = Join-Path $OutputDirectory ".nojekyll"
if (-not (Test-Path -LiteralPath $noJekyll -PathType Leaf)) {
    [System.IO.File]::WriteAllBytes($noJekyll, [byte[]]::new(0))
}

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
    $priorEnvelope = $null
    if ($null -ne $prior) {
        $priorEnvelopeRead = Read-PackageUpdatePriorEnvelope `
            -Prior $prior -ChannelDirectory $channelDirectory
        & cargo run `
            --quiet --locked --manifest-path (Join-Path $repoRoot "Cargo.toml") `
            -p rusty-quest-package-updater `
            --bin authenticate_package_update_manifest -- `
            --envelope $priorEnvelopeRead.path `
            --key-id $KeyId `
            --public-key $TrustedPublicKeyBase64Url | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Pinned prior generation signature authentication failed."
        }
        $priorEnvelope = $priorEnvelopeRead.value
    }
    Assert-PackageUpdatePrior `
        -Prior $prior `
        -ExpectPriorAbsent:$ExpectPriorAbsent `
        -ExpectedPriorPointerSha256 $ExpectedPriorPointerSha256 `
        -ExpectedPriorEnvelopeSha256 $ExpectedPriorEnvelopeSha256 `
        -Tuple $tuple `
        -Sequence $Sequence `
        -VersionCode $VersionCode `
        -PriorEnvelope $priorEnvelope `
        -CandidateArtifact $candidateArtifact `
        -Refresh:$Refresh `
        -MigrateGitHubPagesProjectOriginToCustomDomain:$MigrateGitHubPagesProjectOriginToCustomDomain

    $stagingRoot = Join-Path $OutputDirectory (
        ".package-update-publish-" + [guid]::NewGuid().ToString("N")
    )
    New-Item -ItemType Directory -Path $stagingRoot | Out-Null
    $stagedApk = Join-Path $stagingRoot "artifact.apk"
    $stagedEnvelope = Join-Path $stagingRoot "envelope.json"
    $stagedReceipt = Join-Path $stagingRoot "publication-receipt.json"
    Copy-Item -LiteralPath $ApkPath -Destination $stagedApk
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
    $envelopeIdentity = "sha256:" + (
        Get-FileHash -LiteralPath $stagedEnvelope -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    $generation = "s$Sequence-v$VersionCode-$($apkHash.Substring(0,16))-$($envelopeIdentity.Substring(7,16))"
    $sourceRevision = (& git -C $repoRoot rev-parse HEAD).Trim()
    $receipt = [ordered]@{
        schema = "rusty.quest.package_update_publication_receipt.v3"
        source_revision = $sourceRevision
        publication_mode = if ($MigrateGitHubPagesProjectOriginToCustomDomain) {
            "origin-migration-refresh"
        } elseif ($Refresh) {
            "refresh"
        } else {
            "version-advance"
        }
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
        site_base_path = $SiteBasePath
        manifest_id = $ManifestId
        manifest_url = "$HttpsOrigin/$publicChannelPath/generations/$generation/envelope.json"
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
        inspection_tool = Get-PublicPackageUpdateInspectionTool `
            -BuildToolsVersion (Split-Path -Leaf $AndroidBuildToolsDirectory) `
            -Aapt2Path $aapt2 `
            -Aapt2Sha256 $aapt2Hash `
            -ApkSignerPath $apkSigner `
            -ApkSignerSha256 $apkSignerHash
    }
    $receipt | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath $stagedReceipt -Encoding utf8NoBOM

    $artifactDirectory = Join-Path $channelDirectory "artifacts\sha256\$apkHash"
    $artifactPath = Join-Path $artifactDirectory $apkFileName
    if (Test-Path -LiteralPath $artifactDirectory) {
        $existingArtifactHash = if (Test-Path -LiteralPath $artifactPath -PathType Leaf) {
            (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
        } else {
            ""
        }
        if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf) -or
            (Get-Item -LiteralPath $artifactPath).Length -ne $apkSize -or
            $existingArtifactHash -ne $apkHash -or
            @(Get-ChildItem -LiteralPath $artifactDirectory -Force).Count -ne 1) {
            throw "Existing content-addressed APK artifact differs from the candidate."
        }
        Remove-Item -LiteralPath $stagedApk -Force
    } else {
        $artifactParent = Split-Path -Parent $artifactDirectory
        New-Item -ItemType Directory -Path $artifactParent -Force | Out-Null
        $stagedArtifactDirectory = Join-Path $stagingRoot "artifact-directory"
        New-Item -ItemType Directory -Path $stagedArtifactDirectory | Out-Null
        [System.IO.File]::Move(
            $stagedApk,
            (Join-Path $stagedArtifactDirectory $apkFileName)
        )
        [System.IO.Directory]::Move(
            $stagedArtifactDirectory,
            $artifactDirectory
        )
    }

    $generationDirectory = Join-Path $generationRoot $generation
    if (Test-Path -LiteralPath $generationDirectory) {
        throw "Immutable generation already exists."
    }
    New-Item -ItemType Directory -Path $generationRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $generationDirectory | Out-Null
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
        schema = "rusty.quest.package_update_channel_pointer.v2"
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
        site_base_path = $SiteBasePath
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
