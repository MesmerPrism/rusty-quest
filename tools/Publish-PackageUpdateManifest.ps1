[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,
    [Parameter(Mandatory = $true)]
    [string]$HttpsOrigin,
    [string]$ChannelPath = "package-updates/rusty-kiosk/alpha",
    [string]$KeyId = "release-manifest-2026-a",
    [string]$PackageName = "io.github.mesmerprism.rustykiosk",
    [string]$RolloutRing = "alpha",
    [Parameter(Mandatory = $true)]
    [string]$SignerSha256,
    [Parameter(Mandatory = $true)]
    [uint64]$VersionCode,
    [Parameter(Mandatory = $true)]
    [string]$VersionName,
    [Parameter(Mandatory = $true)]
    [uint64]$Sequence,
    [Parameter(Mandatory = $true)]
    [uint64]$IssuedAtMs,
    [Parameter(Mandatory = $true)]
    [uint64]$ExpiresAtMs,
    [Parameter(Mandatory = $true)]
    [string]$ManifestId
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Package update publication requires PowerShell 7.6 Core or newer."
}
if ([string]::IsNullOrWhiteSpace(
        $env:RUSTY_QUEST_UPDATE_SIGNING_SEED_BASE64URL)) {
    throw "RUSTY_QUEST_UPDATE_SIGNING_SEED_BASE64URL is required."
}
if ($HttpsOrigin -notmatch "^https://[a-z0-9.-]+(?::[1-9][0-9]{0,4})?$" -or
    $ChannelPath -notmatch "^[A-Za-z0-9._/-]+$" -or
    $ChannelPath.StartsWith("/") -or
    $ChannelPath.EndsWith("/") -or
    $ChannelPath.Contains("..") -or
    $SignerSha256 -notmatch "^sha256:[0-9a-f]{64}$" -or
    $ExpiresAtMs -le $IssuedAtMs -or
    ($ExpiresAtMs - $IssuedAtMs) -gt 2678400000) {
    throw "Package update publication inputs are not canonical or bounded."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$channelDirectory = Join-Path $OutputDirectory (
    $ChannelPath.Replace("/", [System.IO.Path]::DirectorySeparatorChar)
)
New-Item -ItemType Directory -Path $channelDirectory -Force | Out-Null

$apkFileName = "rusty-kiosk-$VersionName.apk"
$publishedApk = Join-Path $channelDirectory $apkFileName
Copy-Item -LiteralPath $ApkPath -Destination $publishedApk -Force
$apkHash = (Get-FileHash -LiteralPath $publishedApk -Algorithm SHA256).
    Hash.ToLowerInvariant()
$apkSize = (Get-Item -LiteralPath $publishedApk).Length
$apkUrl = "$HttpsOrigin/$ChannelPath/$apkFileName"
$envelopePath = Join-Path $channelDirectory "envelope.json"

& cargo run `
    --quiet `
    --locked `
    -p rusty-quest-package-updater `
    --bin sign_package_update_manifest `
    -- `
    --key-id $KeyId `
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
    --out $envelopePath
if ($LASTEXITCODE -ne 0) {
    throw "Package update envelope signing failed."
}

$sourceRevision = (& git -C $repoRoot rev-parse HEAD).Trim()
$release = [ordered]@{
    schema = "rusty.quest.package_update_publication.v1"
    source_revision = $sourceRevision
    manifest_id = $ManifestId
    manifest_url = "$HttpsOrigin/$ChannelPath/envelope.json"
    apk_url = $apkUrl
    apk_sha256 = "sha256:$apkHash"
    apk_size_bytes = $apkSize
    version_code = $VersionCode
    version_name = $VersionName
    sequence = $Sequence
    issued_at_ms = $IssuedAtMs
    expires_at_ms = $ExpiresAtMs
}
$release | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (
        Join-Path $channelDirectory "release.json"
    ) -Encoding utf8NoBOM

$release
