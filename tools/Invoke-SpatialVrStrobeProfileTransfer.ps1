[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Validate', 'Import', 'Export', 'Reset')]
    [string]$Action,

    [string]$Serial,
    [string]$BundlePath,
    [string]$OutPath,
    [string]$Package = 'io.github.mesmerprism.rustyquest.spatial_vr_strobe_test',
    [string]$Activity = 'io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraPanelActivity',
    [string]$AdbPath = 'adb',
    [switch]$ConfirmReset
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$bundleSchema = 'rusty.quest.spatial_vr_strobe.profile_bundle.v1'
$importFile = 'files/vr_strobe_profile_import.json'
$exportFile = 'files/vr_strobe_profile_bundle.json'

function Read-AndValidateBundle {
    param([Parameter(Mandatory)][string]$Path)

    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $raw = [System.IO.File]::ReadAllText($resolved)
    try {
        $document = $raw | ConvertFrom-Json
    }
    catch {
        throw "Profile bundle is not valid JSON: $($_.Exception.Message)"
    }
    if ($document.schema -ne $bundleSchema -or [int]$document.format_version -ne 1) {
        throw "Unsupported bundle schema or format version."
    }
    $profiles = @($document.profiles)
    if ($profiles.Count -gt 512 -or [int]$document.profile_count -ne $profiles.Count) {
        throw "Profile count does not match the bundle payload."
    }
    [pscustomobject]@{
        Path = $resolved
        Raw = $raw
        Count = $profiles.Count
    }
}

function Resolve-Adb {
    $command = Get-Command -Name $AdbPath -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
            throw "adb was not found at '$AdbPath'."
        }
        return (Resolve-Path -LiteralPath $AdbPath).Path
    }
    return $command.Source
}

function Invoke-SerialAdb {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    $output = & $script:ResolvedAdb -s $Serial @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb -s $Serial $($Arguments -join ' ') failed ($exitCode): $($output -join [Environment]::NewLine)"
    }
    [pscustomobject]@{ ExitCode = $exitCode; Output = @($output) }
}

function Assert-DeviceTarget {
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        throw "-Serial is required for $Action. Device operations are always serial-scoped."
    }
    $script:ResolvedAdb = Resolve-Adb
    $state = Invoke-SerialAdb -Arguments @('get-state')
    if (($state.Output -join '').Trim() -ne 'device') {
        throw "Quest '$Serial' is not in adb device state."
    }
    $packagePath = Invoke-SerialAdb -Arguments @('shell', 'pm', 'path', $Package) -AllowFailure
    if ($packagePath.ExitCode -ne 0 -or ($packagePath.Output -join '') -notmatch '^package:') {
        throw "Target package '$Package' is not installed on Quest '$Serial'."
    }
}

function Read-EffectiveExport {
    $result = Invoke-SerialAdb -Arguments @('exec-out', 'run-as', $Package, 'cat', $exportFile) -AllowFailure
    if ($result.ExitCode -ne 0) { return $null }
    $raw = ($result.Output -join [Environment]::NewLine).Trim()
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    try {
        $document = $raw | ConvertFrom-Json
    }
    catch { return $null }
    if ($document.schema -ne $bundleSchema -or [int]$document.format_version -ne 1) { return $null }
    $profiles = @($document.profiles)
    if ([int]$document.profile_count -ne $profiles.Count) { return $null }
    [pscustomobject]@{ Raw = $raw; Count = $profiles.Count }
}

function Import-Bundle {
    param([Parameter(Mandatory)][string]$Path)

    $validated = Read-AndValidateBundle -Path $Path
    $remoteStage = "/data/local/tmp/rusty-vr-strobe-profiles-$([guid]::NewGuid().ToString('N')).json"
    try {
        Invoke-SerialAdb -Arguments @('push', $validated.Path, $remoteStage) | Out-Null
        Invoke-SerialAdb -Arguments @('shell', 'run-as', $Package, 'cp', $remoteStage, $importFile) | Out-Null
    }
    finally {
        Invoke-SerialAdb -Arguments @('shell', 'rm', '-f', $remoteStage) -AllowFailure | Out-Null
    }

    # The export is a derived mirror. Removing it makes the next observed file
    # unambiguously belong to this cold-start import transaction.
    Invoke-SerialAdb -Arguments @('shell', 'run-as', $Package, 'rm', '-f', $exportFile) | Out-Null
    Invoke-SerialAdb -Arguments @('shell', 'am', 'force-stop', $Package) | Out-Null
    $component = "$Package/$Activity"
    Invoke-SerialAdb -Arguments @('shell', 'am', 'start', '-W', '-n', $component) | Out-Null

    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    do {
        Start-Sleep -Milliseconds 500
        $effective = Read-EffectiveExport
        if ($null -ne $effective -and $effective.Count -eq $validated.Count) {
            return [pscustomobject]@{
                Action = $Action
                Serial = $Serial
                Package = $Package
                ProfileCount = $effective.Count
                Schema = $bundleSchema
                Status = 'effective-list-verified'
            }
        }
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "Quest did not publish a matching effective profile export within 25 seconds."
}

if ($Action -eq 'Validate') {
    if ([string]::IsNullOrWhiteSpace($BundlePath)) { throw '-BundlePath is required for Validate.' }
    $validated = Read-AndValidateBundle -Path $BundlePath
    [pscustomobject]@{
        Action = $Action
        Path = $validated.Path
        ProfileCount = $validated.Count
        Schema = $bundleSchema
        Status = 'host-envelope-valid'
    }
    exit 0
}

Assert-DeviceTarget

switch ($Action) {
    'Import' {
        if ([string]::IsNullOrWhiteSpace($BundlePath)) { throw '-BundlePath is required for Import.' }
        Import-Bundle -Path $BundlePath
    }
    'Export' {
        if ([string]::IsNullOrWhiteSpace($OutPath)) { throw '-OutPath is required for Export.' }
        $effective = Read-EffectiveExport
        if ($null -eq $effective) {
            throw "The app has not published an effective profile bundle. Launch it once, then retry Export."
        }
        $fullOutPath = [System.IO.Path]::GetFullPath($OutPath)
        $parent = [System.IO.Path]::GetDirectoryName($fullOutPath)
        if (-not [string]::IsNullOrWhiteSpace($parent)) {
            [System.IO.Directory]::CreateDirectory($parent) | Out-Null
        }
        [System.IO.File]::WriteAllText($fullOutPath, "$($effective.Raw)`n", [System.Text.UTF8Encoding]::new($false))
        $validated = Read-AndValidateBundle -Path $fullOutPath
        [pscustomobject]@{
            Action = $Action
            Serial = $Serial
            Package = $Package
            Path = $validated.Path
            ProfileCount = $validated.Count
            Schema = $bundleSchema
            Status = 'effective-list-exported'
        }
    }
    'Reset' {
        if (-not $ConfirmReset) {
            throw 'Reset replaces the complete Quest profile list. Repeat with -ConfirmReset.'
        }
        $temporaryPath = Join-Path ([System.IO.Path]::GetTempPath()) "rusty-vr-strobe-empty-$([guid]::NewGuid().ToString('N')).json"
        try {
            $emptyBundle = [ordered]@{
                schema = $bundleSchema
                format_version = 1
                profile_count = 0
                profiles = @()
            } | ConvertTo-Json -Depth 5
            [System.IO.File]::WriteAllText($temporaryPath, "$emptyBundle`n", [System.Text.UTF8Encoding]::new($false))
            Import-Bundle -Path $temporaryPath
        }
        finally {
            if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath -Force }
        }
    }
}
