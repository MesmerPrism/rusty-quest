$ErrorActionPreference = "Stop"

function Assert-PackageUpdateLabsEd25519PublicProjection {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [object[]]$Rows
    )

    if ($Rows.Count -ne 1) {
        throw "The Labs feed deploy key public projection is not one line."
    }
    $line = [string]$Rows[0]
    if ($line.Length -gt 512 -or $line -match "[\r\n]") {
        throw "The Labs feed deploy key public projection is malformed."
    }
    $match = [Regex]::Match(
        $line,
        "^ssh-ed25519 [A-Za-z0-9+/]+={0,2}" +
            "(?: (?<comment>[\x20-\x7e]{1,128}))?$"
    )
    if (-not $match.Success) {
        throw "The Labs feed deploy key public projection is not Ed25519."
    }

    [pscustomobject][ordered]@{
        algorithm = "ssh-ed25519"
        comment_present = $match.Groups["comment"].Success
    }
}

function Assert-PackageUpdateLabsEd25519FingerprintLine {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [object[]]$Rows,

        [Parameter(Mandatory)]
        [string]$ExpectedFingerprint
    )

    if ($ExpectedFingerprint -cnotmatch "^SHA256:[A-Za-z0-9+/]{43}$") {
        throw "The expected Labs feed deploy key fingerprint is noncanonical."
    }
    if ($Rows.Count -ne 1) {
        throw "The Labs feed deploy key fingerprint projection is not one line."
    }
    $line = [string]$Rows[0]
    if ($line.Length -gt 512 -or $line -match "[\r\n]") {
        throw "The Labs feed deploy key fingerprint projection is malformed."
    }
    $match = [Regex]::Match(
        $line,
        "^256 (?<fingerprint>SHA256:[A-Za-z0-9+/]{43})" +
            "(?: [\x20-\x7e]{1,256})? \(ED25519\)$"
    )
    if (-not $match.Success) {
        throw "The Labs feed deploy key fingerprint projection is not Ed25519."
    }
    if ($match.Groups["fingerprint"].Value -cne $ExpectedFingerprint) {
        throw "The Labs feed secret is not the exact configured deploy key."
    }

    [pscustomobject][ordered]@{
        algorithm = "ED25519"
        fingerprint = $match.Groups["fingerprint"].Value
    }
}

function Assert-PackageUpdateLabsFeedWriterDeployKey {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$KeyPath,

        [Parameter(Mandatory)]
        [string]$ExpectedFingerprint
    )

    $publicKeyRows = @(& ssh-keygen.exe -y -P "" -f $KeyPath 2>$null)
    $publicKeyExit = $LASTEXITCODE
    if ($publicKeyExit -ne 0) {
        throw "The dedicated Labs feed deploy key could not be read."
    }
    $publicProjection =
        Assert-PackageUpdateLabsEd25519PublicProjection -Rows $publicKeyRows

    $fingerprintRows = @(& ssh-keygen.exe -lf $KeyPath -E sha256 2>$null)
    $fingerprintExit = $LASTEXITCODE
    if ($fingerprintExit -ne 0) {
        throw "The dedicated Labs feed deploy key fingerprint could not be read."
    }
    $fingerprintProjection =
        Assert-PackageUpdateLabsEd25519FingerprintLine `
            -Rows $fingerprintRows -ExpectedFingerprint $ExpectedFingerprint

    [pscustomobject][ordered]@{
        schema = "rusty.quest.package_update_labs_feed_key_validation.v1"
        algorithm = $publicProjection.algorithm
        fingerprint = $fingerprintProjection.fingerprint
        comment_present = $publicProjection.comment_present
    }
}
