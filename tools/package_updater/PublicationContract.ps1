Set-StrictMode -Version Latest

function Assert-PackageUpdateCanonicalInputs {
    param(
        [string]$HttpsOrigin,
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
        $ChannelPath -ne "package-updates/rusty-kiosk/alpha" -or
        $Channel -ne "alpha" -or
        $PackageName -ne "io.github.mesmerprism.rustykiosk" -or
        $RolloutRing -ne "alpha" -or
        $KeyId -notmatch "^[A-Za-z0-9._-]{1,96}$" -or
        $PublicKey -notmatch "^[A-Za-z0-9_-]{43}$" -or
        $SignerSha256 -notmatch "^sha256:[0-9a-f]{64}$" -or
        $ExpiresAtMs -le $IssuedAtMs -or
        ($ExpiresAtMs - $IssuedAtMs) -gt 86400000) {
        throw "Package update publication inputs are not the canonical alpha tuple."
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
        [string]$HttpsOrigin
    )
    [ordered]@{
        channel = $Channel
        package_name = $PackageName
        rollout_ring = $RolloutRing
        signer_sha256 = $SignerSha256
        key_id = $KeyId
        public_key = $PublicKey
        https_origin = $HttpsOrigin
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
        $Tuple.https_origin
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
        "public_key", "https_origin"
    )
    if ($value.schema -ne "rusty.quest.package_update_channel_pointer.v1" -or
        @($value.Keys).Count -ne $required.Count -or
        @($required | Where-Object { -not $value.ContainsKey($_) }).Count -ne 0 -or
        $value.generation -notmatch "^[A-Za-z0-9._-]{1,160}$" -or
        $value.envelope_sha256 -notmatch "^sha256:[0-9a-f]{64}$") {
        throw "Current channel pointer does not satisfy the exact schema."
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

function Assert-PackageUpdatePrior {
    param(
        $Prior,
        [switch]$ExpectPriorAbsent,
        [string]$ExpectedPriorPointerSha256,
        [string]$ExpectedPriorEnvelopeSha256,
        [Parameter(Mandatory = $true)]$Tuple,
        [uint64]$Sequence,
        [uint64]$VersionCode
    )
    if ($ExpectPriorAbsent -eq (-not [string]::IsNullOrWhiteSpace(
            $ExpectedPriorPointerSha256))) {
        throw "Assert exactly one prior state: absent or pinned pointer."
    }
    if ($ExpectPriorAbsent) {
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
    if ((ConvertTo-PackageUpdateTupleIdentity $Prior.value) -ne
        (ConvertTo-PackageUpdateTupleIdentity $Tuple)) {
        throw "The current pointer closed tuple differs from the candidate."
    }
    if ($Sequence -le [uint64]$Prior.value.sequence -or
        $VersionCode -le [uint64]$Prior.value.version_code) {
        throw "Sequence and version must both strictly increase."
    }
}

function Assert-PackageUpdatePointerUnchanged {
    param($Initial, $Current)
    if (($null -eq $Initial) -ne ($null -eq $Current)) {
        throw "Concurrent publication changed pointer presence."
    }
    if ($null -ne $Initial -and (
            $Initial.sha256 -ne $Current.sha256 -or
            -not $Initial.bytes.AsSpan().SequenceEqual($Current.bytes))) {
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

