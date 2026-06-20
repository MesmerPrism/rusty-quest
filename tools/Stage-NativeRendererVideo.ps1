param(
    [Parameter(Mandatory=$true)]
    [string]$SourcePath,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.native_renderer",
    [string]$DestinationRelativePath = "v.mp4",
    [string]$Out = ""
)

$ErrorActionPreference = "Stop"

function Resolve-ToolPath {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [string]$Value,
        [string]$DefaultPath
    )

    if (-not [string]::IsNullOrWhiteSpace($Value)) {
        if (Test-Path $Value) {
            return (Resolve-Path $Value).Path
        }
        $command = Get-Command $Value -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
        throw "$Name not found: $Value"
    }

    if (-not [string]::IsNullOrWhiteSpace($DefaultPath) -and (Test-Path $DefaultPath)) {
        return (Resolve-Path $DefaultPath).Path
    }

    $fallback = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $fallback) {
        throw "$Name not found. Pass -$Name or set the matching environment variable."
    }
    return $fallback.Source
}

function Resolve-AdbServerPortArgument {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }
    $parsed = 0
    if (-not [int]::TryParse($Value, [ref]$parsed) -or $parsed -lt 1 -or $parsed -gt 65535) {
        throw "ADB server port must be an integer from 1 to 65535: $Value"
    }
    return $parsed.ToString()
}

function Invoke-AdbCommand {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string[]]$Arguments,
        [switch]$AllowFailure
    )

    $adbArgs = @()
    if ($null -ne $script:ResolvedAdbServerPort) {
        $adbArgs += @("-P", $script:ResolvedAdbServerPort)
    }
    $adbArgs += @("-s", $script:Serial)
    $adbArgs += $Arguments

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $script:ResolvedAdb @adbArgs 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $result = [ordered]@{
        name = $Name
        arguments = $Arguments
        exit_code = $exitCode
        output = ($output -join "`n")
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "$Name failed with exit code $exitCode`n$($result.output)"
    }
    return $result
}

function Normalize-AppRelativePath {
    param([Parameter(Mandatory=$true)][string]$Value)

    $normalized = $Value.Replace("\", "/").Trim()
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        throw "DestinationRelativePath must not be empty."
    }
    if ($normalized.StartsWith("/")) {
        throw "DestinationRelativePath must be relative to the package-scoped external files directory: $Value"
    }
    if ($normalized -eq "." -or $normalized -eq ".." -or $normalized.Contains("../") -or $normalized.Contains("/..")) {
        throw "DestinationRelativePath must not contain parent traversal: $Value"
    }
    if ($normalized -notmatch '^[A-Za-z0-9._/-]+$') {
        throw "DestinationRelativePath may contain only letters, digits, dot, underscore, dash, and slash: $Value"
    }
    return $normalized
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$script:ResolvedAdb = Resolve-ToolPath `
    -Name "adb" `
    -Value $Adb `
    -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
$script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument -Value $AdbServerPort

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "-Serial or RUSTY_QUEST_SERIAL is required; video staging must use adb -s <serial>."
}
if (-not (Test-Path -LiteralPath $SourcePath)) {
    throw "SourcePath not found: $SourcePath"
}

$resolvedSource = (Resolve-Path -LiteralPath $SourcePath).Path
$sourceInfo = Get-Item -LiteralPath $resolvedSource
if ($sourceInfo.PSIsContainer) {
    throw "SourcePath must be a file: $resolvedSource"
}

$destination = Normalize-AppRelativePath -Value $DestinationRelativePath
$destinationDir = Split-Path -Parent $destination
if ([string]::IsNullOrWhiteSpace($destinationDir)) {
    $destinationDir = "."
} else {
    $destinationDir = $destinationDir.Replace("\", "/")
}
$externalFilesRoot = "/sdcard/Android/data/$PackageName/files"
$remoteDestination = "$externalFilesRoot/$destination"
$maxAndroidPropertyValueLength = 91
if ($remoteDestination.Length -gt $maxAndroidPropertyValueLength) {
    throw "Staged video path is too long for Android system-property transport ($($remoteDestination.Length) > $maxAndroidPropertyValueLength): $remoteDestination. Use a shorter -DestinationRelativePath such as v.mp4."
}

if ([string]::IsNullOrWhiteSpace($Out)) {
    $Out = Join-Path $repoRoot "local-artifacts\native-renderer-video-stage.json"
} elseif (-not [System.IO.Path]::IsPathRooted($Out)) {
    $Out = Join-Path $repoRoot $Out
}
$outDir = Split-Path -Parent $Out
if (-not [string]::IsNullOrWhiteSpace($outDir)) {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
}

$commands = @()
$commands += Invoke-AdbCommand -Name "adb get-state" -Arguments @("get-state")
$commands += Invoke-AdbCommand -Name "app external mkdir video dir" -Arguments @("shell", "mkdir", "-p", "$externalFilesRoot/$destinationDir")
$commands += Invoke-AdbCommand -Name "adb push video" -Arguments @("push", $resolvedSource, $remoteDestination)
$commands += Invoke-AdbCommand -Name "adb chmod staged video" -Arguments @("shell", "chmod", "0644", $remoteDestination)
$commands += Invoke-AdbCommand -Name "app external list staged video" -Arguments @("shell", "ls", "-l", $remoteDestination)

$receipt = [ordered]@{
    schema = "rusty.quest.native_renderer.video_stage_receipt.v1"
    source_path = $resolvedSource
    source_size_bytes = $sourceInfo.Length
    package_name = $PackageName
    app_scoped_external_destination = $remoteDestination
    video_projection_path = $remoteDestination
    video_projection_path_transport = "android-system-property"
    path_resolution = "absolute-device-path"
    app_external_files_authority = "package-scoped-external-files"
    broad_shared_storage_required = $false
    adb_path = $script:ResolvedAdb
    adb_scope = "device-scoped-adb"
    adb_serial_required = $true
    adb_serial = $Serial
    adb_server_port = $script:ResolvedAdbServerPort
    max_android_property_value_length = $maxAndroidPropertyValueLength
    commands = $commands
}

$receipt | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $Out
Write-Output "native renderer video staged: $($receipt.app_scoped_external_destination)"
Write-Output "video projection property path: $($receipt.video_projection_path)"
Write-Output $Out
