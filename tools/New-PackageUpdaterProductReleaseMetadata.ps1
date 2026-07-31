[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BuildManifestPath,
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$ReleaseTag,
    [Parameter(Mandatory = $true)][string]$RepoRoot,
    [Parameter(Mandatory = $true)][string]$OutPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot "package_updater\ProductReleaseContract.ps1")
if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Package Updater release metadata requires PowerShell 7.6 Core or newer."
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$ApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
$BuildManifestPath = (Resolve-Path -LiteralPath $BuildManifestPath).Path
$build = Read-PackageUpdaterBuildManifest `
    -Path $BuildManifestPath -ApkPath $ApkPath
$head = (& git -C $RepoRoot rev-parse HEAD).Trim()
$tree = (& git -C $RepoRoot rev-parse "$($build.source_revision)^{tree}").Trim()
if ($LASTEXITCODE -ne 0 -or $head -ne $build.source_revision -or
    $tree -notmatch "^[0-9a-f]{40}$") {
    throw "Build manifest source is not the exact checked-out revision and tree."
}
$metadata = New-PackageUpdaterProductReleaseMetadata `
    -BuildManifest $build -Tag $ReleaseTag -SourceTree $tree
Assert-PackageUpdaterProductReleaseMetadata `
    -Metadata ([pscustomobject]$metadata) -BuildManifest $build `
    -ExpectedTag $ReleaseTag -ExpectedSourceTree $tree
$parent = Split-Path -Parent ([System.IO.Path]::GetFullPath($OutPath))
New-Item -ItemType Directory -Path $parent -Force | Out-Null
$metadata | ConvertTo-Json -Depth 5 |
    Set-Content -LiteralPath $OutPath -Encoding utf8NoBOM
[pscustomobject]$metadata
