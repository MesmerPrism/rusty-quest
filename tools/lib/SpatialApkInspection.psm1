Set-StrictMode -Version Latest

# Process-local ownership only. A caller cannot select an old cache entry for
# retirement. Failed attempts remain on disk, even when this process exits.
$script:inspectionCopies = @{}

function Assert-InspectionOrdinaryPath {
    param([string]$Path, [switch]$Directory)
    if (-not [IO.Path]::IsPathFullyQualified($Path)) { throw 'Inspection paths must be absolute.' }
    $current = [IO.Path]::GetFullPath($Path)
    $first = $true
    while ($current) {
        $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
            (($Directory -or -not $first) -and -not $item.PSIsContainer) -or
            ($first -and -not $Directory -and $item.PSIsContainer)) {
            throw "Inspection path must have ordinary file/directory ancestors: $Path"
        }
        $first = $false
        $parent = [IO.Path]::GetDirectoryName($current)
        if ($parent -eq $current) { break }
        $current = $parent
    }
}

function Get-InspectionSha256([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
}

function New-QuestApkInspectionCopy {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$ApkPath,
        [Parameter(Mandatory)][string]$InspectionRoot,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedApkSha256
    )
    if (-not [IO.Path]::IsPathFullyQualified($ApkPath) -or -not [IO.Path]::IsPathFullyQualified($InspectionRoot)) {
        throw 'Inspection paths must be absolute.'
    }
    $canonical = [IO.Path]::GetFullPath($ApkPath)
    $root = [IO.Path]::GetFullPath($InspectionRoot)
    if ((Split-Path $root -Leaf) -cne 'apk-v') { throw 'Inspection root must be the apk-v leaf.' }
    Assert-InspectionOrdinaryPath $canonical
    Assert-InspectionOrdinaryPath (Split-Path $root -Parent) -Directory
    if ($canonical.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Canonical APK must be outside the inspection-copy root.'
    }
    if (-not (Test-Path -LiteralPath $root)) { [void][IO.Directory]::CreateDirectory($root) }
    Assert-InspectionOrdinaryPath $root -Directory
    $id = [Guid]::NewGuid().ToString('N')
    $path = Join-Path $root ("attempt-$id.apk")
    # CreateNew never replaces a pre-existing hash, rollback, milestone or failure.
    $source = [IO.File]::Open($canonical, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $destination = $null
    try {
        $destination = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $source.CopyTo($destination)
        $destination.Flush($true)
    } finally {
        if ($null -ne $destination) { $destination.Dispose() }
        $source.Dispose()
    }
    if ((Get-InspectionSha256 $path) -cne $ExpectedApkSha256) {
        throw "Inspection copy differs from canonical APK identity; retained: $path"
    }
    $handle = [pscustomobject]@{ id=$id; path=$path }
    $script:inspectionCopies[$id] = @{ handle=$handle; path=$path; canonical=$canonical; root=$root; sha256=$ExpectedApkSha256 }
    return $handle
}

function Complete-QuestApkInspectionCopy {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Copy,
        [Parameter(Mandatory)][string]$InspectionReceiptPath,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedInspectionSha256
    )
    $record = $script:inspectionCopies[[string]$Copy.id]
    if ($null -eq $record -or -not [object]::ReferenceEquals($record.handle, $Copy) -or $Copy.path -cne $record.path) {
        throw 'Inspection copy is not owned by this invocation.'
    }
    # Only after the builder's synchronous tool/identity/ZIP checks and durable
    # compact JSON publication. Any failed guard retains the copy and canonical.
    Assert-InspectionOrdinaryPath $record.root -Directory
    Assert-InspectionOrdinaryPath $record.path
    Assert-InspectionOrdinaryPath $record.canonical
    Assert-InspectionOrdinaryPath $InspectionReceiptPath
    if ((Get-InspectionSha256 $record.canonical) -cne $record.sha256 -or
        (Get-InspectionSha256 $record.path) -cne $record.sha256) {
        throw "Inspection APK identity changed; retained: $($record.path)"
    }
    if ((Get-InspectionSha256 $InspectionReceiptPath) -cne $ExpectedInspectionSha256) {
        throw "Inspection receipt changed; retained: $($record.path)"
    }
    $receipt = Get-Content -LiteralPath $InspectionReceiptPath -Raw | ConvertFrom-Json -ErrorAction Stop
    if ($receipt.schema -cne 'rusty.quest.spatial_camera_panel.apk_inspection.v1' -or
        $receipt.apksigner_verified -cne $true -or $receipt.zipalign_4_and_16k_verified -cne $true -or
        $receipt.native_elf_16k_compatible -cne $true) {
        throw "Inspection receipt is not successful; retained: $($record.path)"
    }
    # Exact single file, never a recursive directory operation or cache census.
    Remove-Item -LiteralPath $record.path -ErrorAction Stop
    $script:inspectionCopies.Remove([string]$Copy.id)
}

Export-ModuleMember -Function New-QuestApkInspectionCopy, Complete-QuestApkInspectionCopy
