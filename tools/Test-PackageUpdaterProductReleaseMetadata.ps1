[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$MetadataPath,
    [Parameter(Mandatory = $true)][string]$BuildManifestPath,
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$ReleaseTag,
    [Parameter(Mandatory = $true)][string]$RepoRoot
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot "package_updater\ProductReleaseContract.ps1")
if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Package Updater release metadata validation requires PowerShell 7.6 Core or newer."
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$metadata = Get-Content -Raw -LiteralPath $MetadataPath | ConvertFrom-Json
$build = Read-PackageUpdaterBuildManifest `
    -Path $BuildManifestPath -ApkPath $ApkPath
$head = (& git -C $RepoRoot rev-parse HEAD).Trim()
$tree = (& git -C $RepoRoot rev-parse "$($build.source_revision)^{tree}").Trim()
if ($LASTEXITCODE -ne 0 -or $head -ne $build.source_revision -or
    $tree -notmatch "^[0-9a-f]{40}$") {
    throw "Release metadata source is not the exact checked-out revision and tree."
}
Assert-PackageUpdaterProductReleaseMetadata `
    -Metadata $metadata -BuildManifest $build -ExpectedTag $ReleaseTag `
    -ExpectedSourceTree $tree
Write-Output "Package Updater product release metadata validation passed."
