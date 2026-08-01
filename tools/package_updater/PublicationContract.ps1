Set-StrictMode -Version Latest

function Assert-PackageUpdateArtifactSize {
    param([uint64]$SizeBytes)
    if ($SizeBytes -lt 1 -or $SizeBytes -gt 104857600) {
        throw "Package update APK size must be within the updater's 100 MiB policy."
    }
}

function Assert-PackageUpdateCanonicalInputs {
    param(
        [string]$HttpsOrigin,
        [string]$SiteBasePath,
        [string]$ChannelPath,
        [string]$Channel,
        [string]$PackageName,
        [string]$RolloutRing,
        [string]$KeyId,
        [string]$PublicKey,
        [string]$SignerSha256,
        [uint64]$IssuedAtMs,
        [uint64]$ExpiresAtMs
    )
    if ($HttpsOrigin -notmatch "^https://[a-z0-9.-]+(?::[1-9][0-9]{0,4})?$" -or
        $SiteBasePath -ne "rusty-quest" -or
        $ChannelPath -ne "package-updates/rusty-kiosk/labs" -or
        $Channel -ne "labs" -or
        $PackageName -ne "io.github.mesmerprism.rustykiosk.labs" -or
        $RolloutRing -ne "labs" -or
        $KeyId -notmatch "^[A-Za-z0-9._-]{1,96}$" -or
        $PublicKey -notmatch "^[A-Za-z0-9_-]{43}$" -or
        $SignerSha256 -notmatch "^sha256:[0-9a-f]{64}$" -or
        $ExpiresAtMs -le $IssuedAtMs -or
        ($ExpiresAtMs - $IssuedAtMs) -gt 86400000) {
        throw "Package update publication inputs are not the canonical Labs tuple."
    }
}

function Get-PackageUpdateTuple {
    param(
        [string]$Channel,
        [string]$PackageName,
        [string]$RolloutRing,
        [string]$SignerSha256,
        [string]$KeyId,
        [string]$PublicKey,
        [string]$HttpsOrigin,
        [string]$SiteBasePath
    )
    [ordered]@{
        channel = $Channel
        package_name = $PackageName
        rollout_ring = $RolloutRing
        signer_sha256 = $SignerSha256
        key_id = $KeyId
        public_key = $PublicKey
        https_origin = $HttpsOrigin
        site_base_path = $SiteBasePath
    }
}

function ConvertTo-PackageUpdateTupleIdentity {
    param([Parameter(Mandatory = $true)]$Tuple)
    @(
        $Tuple.channel,
        $Tuple.package_name,
        $Tuple.rollout_ring,
        $Tuple.signer_sha256,
        $Tuple.key_id,
        $Tuple.public_key,
        $Tuple.https_origin,
        $Tuple.site_base_path
    ) -join "`0"
}

function Read-PackageUpdatePointer {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $null
    }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -eq 0 -or $bytes.Length -gt 65536) {
        throw "Current channel pointer has an invalid size."
    }
    try {
        $value = [System.Text.Encoding]::UTF8.GetString($bytes) |
            ConvertFrom-Json -AsHashtable
    } catch {
        throw "Current channel pointer is malformed."
    }
    $required = @(
        "schema", "generation", "envelope_sha256", "sequence", "version_code",
        "channel", "package_name", "rollout_ring", "signer_sha256", "key_id",
        "public_key", "https_origin", "site_base_path"
    )
    if ($value.schema -ne "rusty.quest.package_update_channel_pointer.v2" -or
        @($value.Keys).Count -ne $required.Count -or
        @($required | Where-Object { -not $value.ContainsKey($_) }).Count -ne 0 -or
        $value.sequence -isnot [long] -or
        $value.version_code -isnot [long] -or
        [int64]$value.sequence -lt 1 -or
        [int64]$value.sequence -gt 9007199254740991 -or
        [int64]$value.version_code -lt 1 -or
        [int64]$value.version_code -gt 9007199254740991 -or
        $value.generation -notmatch
            "^s[1-9][0-9]{0,19}-v[1-9][0-9]{0,19}-[0-9a-f]{16}-[0-9a-f]{16}$" -or
        $value.envelope_sha256 -notmatch "^sha256:[0-9a-f]{64}$") {
        throw "Current channel pointer does not satisfy the exact schema."
    }
    if (-not ([string]$value.generation).StartsWith(
            "s$($value.sequence)-v$($value.version_code)-",
            [StringComparison]::Ordinal
        ) -or
        -not ([string]$value.generation).EndsWith(
            "-$(([string]$value.envelope_sha256).Substring(7,16))",
            [StringComparison]::Ordinal
        )) {
        throw "Current channel pointer generation identity is inconsistent."
    }
    [pscustomobject]@{
        value = $value
        bytes = $bytes
        sha256 = "sha256:" + (
            [Convert]::ToHexString(
                [System.Security.Cryptography.SHA256]::HashData($bytes)
            ).ToLowerInvariant()
        )
    }
}

function Read-PackageUpdatePriorEnvelope {
    param(
        [Parameter(Mandatory = $true)]$Prior,
        [Parameter(Mandatory = $true)][string]$ChannelDirectory
    )
    $path = Join-Path $ChannelDirectory (
        "generations\$($Prior.value.generation)\envelope.json"
    )
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Pinned prior generation envelope is absent."
    }
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if ($bytes.Length -eq 0 -or $bytes.Length -gt 131072) {
        throw "Pinned prior generation envelope has an invalid size."
    }
    $digest = "sha256:" + (
        [Convert]::ToHexString(
            [System.Security.Cryptography.SHA256]::HashData($bytes)
        ).ToLowerInvariant()
    )
    if ($digest -ne $Prior.value.envelope_sha256) {
        throw "Pinned prior generation envelope differs from the pointer."
    }
    try {
        $value = [System.Text.Encoding]::UTF8.GetString($bytes) |
            ConvertFrom-Json -AsHashtable
    } catch {
        throw "Pinned prior generation envelope is malformed."
    }
    [pscustomobject]@{ value = $value; bytes = $bytes; path = $path }
}

function Assert-PackageUpdatePrior {
    param(
        $Prior,
        [switch]$ExpectPriorAbsent,
        [string]$ExpectedPriorPointerSha256,
        [string]$ExpectedPriorEnvelopeSha256,
        [Parameter(Mandatory = $true)]$Tuple,
        [uint64]$Sequence,
        [uint64]$VersionCode,
        $PriorEnvelope,
        [Parameter(Mandatory = $true)]$CandidateArtifact,
        [switch]$Refresh,
        [switch]$MigrateGitHubPagesProjectOriginToCustomDomain
    )
    if ($ExpectPriorAbsent -eq (-not [string]::IsNullOrWhiteSpace(
            $ExpectedPriorPointerSha256))) {
        throw "Assert exactly one prior state: absent or pinned pointer."
    }
    if ($ExpectPriorAbsent) {
        if ($Refresh -or $MigrateGitHubPagesProjectOriginToCustomDomain) {
            throw "A refresh or origin migration requires one pinned prior generation."
        }
        if ($null -ne $Prior) {
            throw "Expected the channel pointer to be absent."
        }
        if (-not [string]::IsNullOrWhiteSpace($ExpectedPriorEnvelopeSha256)) {
            throw "An absent prior pointer cannot have an envelope assertion."
        }
        return
    }
    if ($null -eq $Prior -or
        $ExpectedPriorPointerSha256 -notmatch "^sha256:[0-9a-f]{64}$" -or
        $ExpectedPriorEnvelopeSha256 -notmatch "^sha256:[0-9a-f]{64}$" -or
        $Prior.sha256 -ne $ExpectedPriorPointerSha256 -or
        $Prior.value.envelope_sha256 -ne $ExpectedPriorEnvelopeSha256) {
        throw "The current pointer does not match the caller-pinned prior state."
    }
    $tupleMatches = (ConvertTo-PackageUpdateTupleIdentity $Prior.value) -eq
        (ConvertTo-PackageUpdateTupleIdentity $Tuple)
    $originMigration = -not $tupleMatches
    if ($originMigration) {
        if (-not $Refresh -or
            -not $MigrateGitHubPagesProjectOriginToCustomDomain -or
            [string]$Prior.value.https_origin -cne
                "https://mesmerprism.github.io" -or
            [string]$Tuple.https_origin -cne "https://mesmerprism.com") {
            throw "The current pointer closed tuple differs from the candidate."
        }
        foreach ($field in @(
            "channel", "package_name", "rollout_ring", "signer_sha256",
            "key_id", "public_key", "site_base_path"
        )) {
            if ([string]$Prior.value[$field] -cne [string]$Tuple[$field]) {
                throw "The origin migration changed closed tuple field '$field'."
            }
        }
    } elseif ($MigrateGitHubPagesProjectOriginToCustomDomain) {
        throw "An origin migration assertion requires exact origin drift."
    }
    if ($Sequence -le [uint64]$Prior.value.sequence) {
        throw "Sequence must strictly increase."
    }
    if ($null -eq $PriorEnvelope -or
        [uint64]$PriorEnvelope.signed.sequence -ne
            [uint64]$Prior.value.sequence -or
        [uint64]$PriorEnvelope.signed.artifact.version_code -ne
            [uint64]$Prior.value.version_code) {
        throw "Pinned prior envelope identity differs from the pointer."
    }
    $priorSigned = $PriorEnvelope.signed
    $priorArtifact = $priorSigned.artifact
    $expectedPriorGeneration = "s$($Prior.value.sequence)-" +
        "v$($Prior.value.version_code)-" +
        "$(([string]$priorArtifact.apk_sha256).Substring(7,16))-" +
        "$(([string]$Prior.value.envelope_sha256).Substring(7,16))"
    $expectedPriorApkUrl = "$($Prior.value.https_origin)/$($Tuple.site_base_path)/" +
        "package-updates/rusty-kiosk/labs/artifacts/sha256/" +
        "$(([string]$priorArtifact.apk_sha256).Substring(7))/" +
        "rusty-kiosk-$($priorArtifact.version_name).apk"
    if ([string]$Prior.value.generation -cne $expectedPriorGeneration -or
        [string]$PriorEnvelope.key_id -cne [string]$Tuple.key_id -or
        [string]$priorSigned.channel -cne [string]$Tuple.channel -or
        [string]$priorSigned.rollout_ring -cne [string]$Tuple.rollout_ring -or
        [string]$priorArtifact.package_name -cne [string]$Tuple.package_name -or
        [string]$priorArtifact.signer_sha256 -cne [string]$Tuple.signer_sha256 -or
        [string]$priorArtifact.apk_url -cne $expectedPriorApkUrl) {
        throw "Pinned prior envelope differs from the closed publication tuple."
    }
    if ($Refresh) {
        if ($VersionCode -ne [uint64]$Prior.value.version_code) {
            throw "A refresh must retain the exact prior version code."
        }
        foreach ($field in @(
            "package_name", "version_code", "version_name", "apk_sha256",
            "apk_size_bytes", "signer_sha256"
        )) {
            if ([string]$priorArtifact[$field] -cne
                [string]$CandidateArtifact[$field]) {
                throw "A refresh changed immutable artifact field '$field'."
            }
        }
        $expectedCandidateApkUrl =
            "$($Tuple.https_origin)/$($Tuple.site_base_path)/" +
            "package-updates/rusty-kiosk/labs/artifacts/sha256/" +
            "$(([string]$priorArtifact.apk_sha256).Substring(7))/" +
            "rusty-kiosk-$($priorArtifact.version_name).apk"
        if ([string]$CandidateArtifact.apk_url -cne $expectedCandidateApkUrl -or
            (-not $originMigration -and
                [string]$priorArtifact.apk_url -cne
                    [string]$CandidateArtifact.apk_url)) {
            throw "A refresh changed the APK route outside the exact origin migration."
        }
    } elseif ($VersionCode -le [uint64]$Prior.value.version_code) {
        throw "A new publication must strictly increase the version code."
    }
}

function Assert-PackageUpdatePointerUnchanged {
    param($Initial, $Current)
    if (($null -eq $Initial) -ne ($null -eq $Current)) {
        throw "Concurrent publication changed pointer presence."
    }
    if ($null -ne $Initial -and (
            $Initial.sha256 -ne $Current.sha256 -or
            [Convert]::ToBase64String([byte[]]$Initial.bytes) -ne
                [Convert]::ToBase64String([byte[]]$Current.bytes))) {
        throw "Concurrent publication changed the pinned pointer."
    }
}

function ConvertFrom-Aapt2Badging {
    param([Parameter(Mandatory = $true)][string[]]$Lines)
    $packageLines = @($Lines | Where-Object { $_ -match "^package: " })
    if ($packageLines.Count -ne 1 -or
        $packageLines[0] -notmatch
            "^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'") {
        throw "aapt2 did not report one canonical package identity."
    }
    [ordered]@{
        package_name = $Matches[1]
        version_code = [uint64]$Matches[2]
        version_name = $Matches[3]
    }
}

function ConvertFrom-ApkSignerCertificates {
    param([Parameter(Mandatory = $true)][string[]]$Lines)
    $digestLines = @($Lines | Where-Object {
        $_ -match "^Signer #[0-9]+ certificate SHA-256 digest: "
    })
    if ($digestLines.Count -ne 1 -or
        $digestLines[0] -notmatch
            "^Signer #1 certificate SHA-256 digest: ([0-9A-Fa-f]{64})$") {
        throw "apksigner did not report exactly one signer."
    }
    "sha256:" + $Matches[1].ToLowerInvariant()
}

function Assert-PackageUpdateObservedApk {
    param(
        [Parameter(Mandatory = $true)]$Observed,
        [string]$ExpectedPackageName,
        [uint64]$ExpectedVersionCode,
        [string]$ExpectedVersionName,
        [string]$ExpectedSignerSha256
    )
    if ($Observed.package_name -ne $ExpectedPackageName -or
        [uint64]$Observed.version_code -ne $ExpectedVersionCode -or
        $Observed.version_name -ne $ExpectedVersionName -or
        $Observed.signer_sha256 -ne $ExpectedSignerSha256) {
        throw "Observed APK identity differs from the exact publication inputs."
    }
}

function Get-PublicPackageUpdateInspectionTool {
    param(
        [string]$BuildToolsVersion,
        [string]$Aapt2Path,
        [string]$Aapt2Sha256,
        [string]$ApkSignerPath,
        [string]$ApkSignerSha256
    )
    if ($BuildToolsVersion -notmatch "^[A-Za-z0-9._-]{1,64}$" -or
        $Aapt2Sha256 -notmatch "^sha256:[0-9a-f]{64}$" -or
        $ApkSignerSha256 -notmatch "^sha256:[0-9a-f]{64}$") {
        throw "Inspection tool receipt fields are not bounded and canonical."
    }
    $aapt2Name = [System.IO.Path]::GetFileName($Aapt2Path)
    $apkSignerName = [System.IO.Path]::GetFileName($ApkSignerPath)
    if ($aapt2Name -notmatch "^aapt2(?:\.exe)?$" -or
        $apkSignerName -notmatch "^apksigner(?:\.bat)?$") {
        throw "Inspection tool names are not the exact Android build tools."
    }
    [ordered]@{
        build_tools_version = $BuildToolsVersion
        aapt2_name = $aapt2Name
        aapt2_sha256 = $Aapt2Sha256
        apksigner_name = $apkSignerName
        apksigner_sha256 = $ApkSignerSha256
    }
}
