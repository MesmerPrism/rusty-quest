[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Validate', 'StageImport', 'Export')]
    [string]$Action,

    [string]$Serial,
    [string]$BundlePath,
    [string]$OutPath,
    [string]$Package = 'io.github.mesmerprism.rustyquest.spatial_camera_panel',
    [string]$AdbPath = 'adb'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$bundleSchema = 'rusty.quest.spatial_camera_panel.profile_bundle.v1'
$maxProfiles = 128
$maxPayloadBytes = 1MB
$remoteDirectory = "/sdcard/Android/data/$Package/files/profile-library"
$remoteImport = "$remoteDirectory/import.profile-bundle.json"
$remoteExport = "$remoteDirectory/export.profile-bundle.json"

function Read-AndValidateBundle {
    param([Parameter(Mandatory)][string]$Path)

    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $bytes = [System.IO.File]::ReadAllBytes($resolved)
    if ($bytes.Length -gt $maxPayloadBytes) {
        throw "Profile bundle exceeds the $maxPayloadBytes byte limit."
    }
    try {
        $document = [System.Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
    }
    catch {
        throw "Profile bundle is not valid JSON: $($_.Exception.Message)"
    }
    if ($document.schema -ne $bundleSchema -or [int]$document.format_version -ne 1) {
        throw 'Unsupported Spatial Camera Panel profile-bundle schema or format version.'
    }
    $profiles = @($document.profiles)
    if ($profiles.Count -gt $maxProfiles -or [int]$document.profile_count -ne $profiles.Count) {
        throw 'Profile count does not match the bounded bundle payload.'
    }
    $ids = @($profiles | ForEach-Object { [string]$_.id })
    if (@($ids | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -gt 0 -or
        @($ids | Sort-Object -Unique).Count -ne $ids.Count) {
        throw 'Profile IDs must be present and unique.'
    }
    foreach ($profile in $profiles) {
        $title = [string]$profile.title
        if ([string]::IsNullOrWhiteSpace($title) -or $title.Length -gt 96) {
            throw 'Every profile must have a title between 1 and 96 characters.'
        }
        if ($null -eq $profile.controls) {
            throw 'Every profile must contain a complete controls object.'
        }
    }
    [pscustomobject]@{
        Path = $resolved
        Count = $profiles.Count
        Schema = $bundleSchema
    }
}

function Resolve-Adb {
    $command = Get-Command -Name $AdbPath -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    if (Test-Path -LiteralPath $AdbPath -PathType Leaf) {
        return (Resolve-Path -LiteralPath $AdbPath).Path
    }
    throw "adb was not found at '$AdbPath'."
}

function Invoke-SerialAdb {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = & $script:ResolvedAdb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb -s $Serial $($Arguments -join ' ') failed ($LASTEXITCODE): $($output -join [Environment]::NewLine)"
    }
    @($output)
}

function Assert-DeviceTarget {
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        throw "-Serial is required for $Action. Device operations are always serial-scoped."
    }
    $script:ResolvedAdb = Resolve-Adb
    $state = ((Invoke-SerialAdb -Arguments @('get-state')) -join '').Trim()
    if ($state -ne 'device') { throw "Quest '$Serial' is not in adb device state." }
    $packagePath = Invoke-SerialAdb -Arguments @('shell', 'pm', 'path', $Package)
    if (($packagePath -join '') -notmatch '^package:') {
        throw "Target package '$Package' is not installed on Quest '$Serial'."
    }
}

if ($Action -eq 'Validate') {
    if ([string]::IsNullOrWhiteSpace($BundlePath)) { throw '-BundlePath is required for Validate.' }
    $validated = Read-AndValidateBundle -Path $BundlePath
    [pscustomobject]@{
        Action = $Action
        Path = $validated.Path
        ProfileCount = $validated.Count
        Schema = $validated.Schema
        Status = 'host-envelope-valid'
    }
    exit 0
}

Assert-DeviceTarget

switch ($Action) {
    'StageImport' {
        if ([string]::IsNullOrWhiteSpace($BundlePath)) { throw '-BundlePath is required for StageImport.' }
        $validated = Read-AndValidateBundle -Path $BundlePath
        Invoke-SerialAdb -Arguments @('shell', 'mkdir', '-p', $remoteDirectory) | Out-Null
        Invoke-SerialAdb -Arguments @('push', $validated.Path, $remoteImport) | Out-Null
        [pscustomobject]@{
            Action = $Action
            Serial = $Serial
            Package = $Package
            ProfileCount = $validated.Count
            RemotePath = $remoteImport
            Status = 'staged-open-profiles-and-select-import'
        }
    }
    'Export' {
        if ([string]::IsNullOrWhiteSpace($OutPath)) { throw '-OutPath is required for Export.' }
        $fullOutPath = [System.IO.Path]::GetFullPath($OutPath)
        $parent = [System.IO.Path]::GetDirectoryName($fullOutPath)
        if (-not [string]::IsNullOrWhiteSpace($parent)) {
            [System.IO.Directory]::CreateDirectory($parent) | Out-Null
        }
        Invoke-SerialAdb -Arguments @('pull', $remoteExport, $fullOutPath) | Out-Null
        $validated = Read-AndValidateBundle -Path $fullOutPath
        [pscustomobject]@{
            Action = $Action
            Serial = $Serial
            Package = $Package
            Path = $validated.Path
            ProfileCount = $validated.Count
            Schema = $validated.Schema
            Status = 'exported-and-host-validated'
        }
    }
}
