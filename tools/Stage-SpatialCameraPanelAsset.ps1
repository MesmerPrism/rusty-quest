param(
    [Parameter(Mandatory=$true)][string]$SourcePath,
    [string]$ConvertedMeshPath = "",
    [string]$RepoRoot = "",
    [string]$AssetConformanceLockPath = "",
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.spatial_camera_panel",
    [string]$DestinationRelativePath = "",
    [string]$Out = ""
)

$ErrorActionPreference = "Stop"

function Resolve-ToolPath {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [string]$Value
    )

    if (-not [string]::IsNullOrWhiteSpace($Value)) {
        if (Test-Path -LiteralPath $Value) {
            return (Resolve-Path -LiteralPath $Value).Path
        }
        $command = Get-Command $Value -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
        throw "$Name not found: $Value"
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
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string[]]$Arguments
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
    if ($exitCode -ne 0) {
        throw "$Name failed with exit code $exitCode`n$($result.output)"
    }
    return $result
}

function Get-FileSha256 {
    param([Parameter(Mandatory=$true)][string]$Path)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToUpperInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Save-Receipt {
    param([System.Collections.IDictionary]$Receipt)

    if ([string]::IsNullOrWhiteSpace($Out)) {
        return
    }
    $outParent = Split-Path -Parent $Out
    if (-not [string]::IsNullOrWhiteSpace($outParent)) {
        New-Item -ItemType Directory -Force -Path $outParent | Out-Null
    }
    [System.IO.File]::WriteAllText($Out, ($Receipt | ConvertTo-Json -Depth 8), [System.Text.Encoding]::UTF8)
}

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Join-Path $PSScriptRoot ".."
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path
$assetConformanceLockResolved = if ([string]::IsNullOrWhiteSpace($AssetConformanceLockPath)) {
    Join-Path $repoRootPath "apps\spatial-camera-panel-android\morphospace\conformance-locks\spatial-asset-model.feature.lock.json"
} elseif ([System.IO.Path]::IsPathRooted($AssetConformanceLockPath)) {
    $AssetConformanceLockPath
} else {
    Join-Path $repoRootPath $AssetConformanceLockPath
}
if (-not (Test-Path -LiteralPath $assetConformanceLockResolved -PathType Leaf)) {
    throw "Spatial asset conformance lock not found: $assetConformanceLockResolved"
}
$assetConformanceLockResolved = (Resolve-Path -LiteralPath $assetConformanceLockResolved).Path
$assetConformanceLock = Get-Content -Raw -LiteralPath $assetConformanceLockResolved | ConvertFrom-Json
$assetConformanceFeature = @($assetConformanceLock.features | Where-Object { [string]$_.feature_id -eq "spatial-asset-model" })
if ([string]$assetConformanceLock.schema -ne "rusty.morphospace.workflow.feature_lock.v1" -or
    [string]$assetConformanceLock.project_id -ne "spatial-camera-panel" -or
    [long]$assetConformanceLock.revision -lt 1 -or
    $assetConformanceFeature.Count -ne 1 -or
    $assetConformanceFeature[0].enabled -ne $true -or
    [string]$assetConformanceFeature[0].module_id -ne "spatial-asset-model" -or
    [string]$assetConformanceFeature[0].requested_by -ne "conformance-profile:spatial-asset-model") {
    throw "Spatial asset conformance lock does not select the accepted spatial-asset-model contract: $assetConformanceLockResolved"
}
$assetActivationProfileId = "profile.quest.spatial_camera_panel.spatial_asset_model_conformance"
$assetActivationLockSha256 = Get-FileSha256 -Path $assetConformanceLockResolved

if (-not (Test-Path -LiteralPath $SourcePath)) {
    throw "SourcePath not found: $SourcePath"
}

$source = Get-Item -LiteralPath (Resolve-Path -LiteralPath $SourcePath)
$sourceExtension = $source.Extension.ToLowerInvariant()
$sourceFormat =
    switch ($sourceExtension) {
        ".fbx" { "fbx" }
        ".glb" { "glb" }
        ".gltf" { "gltf" }
        default { throw "Unsupported source model extension for Spatial staged asset: $sourceExtension" }
    }

$meshSource = $source
$requiresConversion = $sourceFormat -eq "fbx"
if ($requiresConversion) {
    if ([string]::IsNullOrWhiteSpace($ConvertedMeshPath)) {
        $receipt = [ordered]@{
            '$schema' = "rusty.quest.spatial_camera_panel.staged_asset.v1"
            status = "conversion-required"
            source_format = $sourceFormat
            sdk_loadable_mesh_uri = $false
            fbx_conversion_required = $true
            source_sha256 = Get-FileSha256 -Path $source.FullName
            activation_required = $true
            activation_profile_id = $assetActivationProfileId
            activation_project_id = "spatial-camera-panel"
            activation_feature_id = "spatial-asset-model"
            activation_lock_path = $assetConformanceLockResolved
            activation_lock_revision = [long]$assetConformanceLock.revision
            activation_lock_sha256 = $assetActivationLockSha256
            note = "Provide -ConvertedMeshPath with a GLB or GLTF export before staging."
        }
        Save-Receipt -Receipt $receipt
        throw "Raw FBX is a local source format only. Convert it to GLB or GLTF and pass -ConvertedMeshPath."
    }
    if (-not (Test-Path -LiteralPath $ConvertedMeshPath)) {
        throw "ConvertedMeshPath not found: $ConvertedMeshPath"
    }
    $meshSource = Get-Item -LiteralPath (Resolve-Path -LiteralPath $ConvertedMeshPath)
}

$meshExtension = $meshSource.Extension.ToLowerInvariant()
if (@(".glb", ".gltf") -notcontains $meshExtension) {
    throw "Spatial staged mesh must be GLB or GLTF for the SDK Mesh URI path: $($meshSource.Extension)"
}
$meshFormat = $meshExtension.TrimStart(".")

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "Serial is required. Pass -Serial or set RUSTY_QUEST_SERIAL."
}

if ([string]::IsNullOrWhiteSpace($DestinationRelativePath)) {
    $safeName = ($meshSource.BaseName -replace "[^A-Za-z0-9._-]+", "-").Trim("-")
    if ([string]::IsNullOrWhiteSpace($safeName)) {
        $safeName = "model"
    }
    $DestinationRelativePath = "spatial-assets/$safeName$meshExtension"
}
$destinationRelative = $DestinationRelativePath.Replace("\", "/").TrimStart("/")
if ($destinationRelative.Contains("..")) {
    throw "DestinationRelativePath must not contain parent-directory segments."
}

$script:ResolvedAdb = Resolve-ToolPath -Name "adb" -Value $Adb
$script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument -Value $AdbServerPort
$script:Serial = $Serial

$destinationParts = $destinationRelative -split "/"
$remoteBaseDir = "/sdcard/Android/data/$PackageName/files"
$remoteDir = $remoteBaseDir
if ($destinationParts.Length -gt 1) {
    $remoteDir = "$remoteBaseDir/" + (($destinationParts[0..($destinationParts.Length - 2)]) -join "/")
}
$remotePath = "/sdcard/Android/data/$PackageName/files/$destinationRelative"
$meshUri = "file://$remotePath"

$commands = @()
$commands += Invoke-AdbCommand -Name "create Spatial asset directory" -Arguments @("shell", "mkdir", "-p", $remoteDir)
$commands += Invoke-AdbCommand -Name "push Spatial staged mesh" -Arguments @("push", $meshSource.FullName, $remotePath)

$receipt = [ordered]@{
    '$schema' = "rusty.quest.spatial_camera_panel.staged_asset.v1"
    status = "staged"
    source_format = $sourceFormat
    staged_format = $meshFormat
    source_sha256 = Get-FileSha256 -Path $source.FullName
    staged_mesh_sha256 = Get-FileSha256 -Path $meshSource.FullName
    fbx_conversion_required = $requiresConversion
    sdk_loadable_mesh_uri = $true
    package_name = $PackageName
    serial = $Serial
    destination_relative_path = $destinationRelative
    device_path = $remotePath
    mesh_uri = $meshUri
    runtime_property_enabled = "debug.rustyquest.spatial.asset_model.enabled"
    runtime_property_mesh_uri = "debug.rustyquest.spatial.asset_model.mesh_uri"
    runtime_property_source_format = "debug.rustyquest.spatial.asset_model.source_format"
    activation_required = $true
    activation_profile_id = $assetActivationProfileId
    activation_project_id = "spatial-camera-panel"
    activation_feature_id = "spatial-asset-model"
    activation_lock_path = $assetConformanceLockResolved
    activation_lock_revision = [long]$assetConformanceLock.revision
    activation_lock_sha256 = $assetActivationLockSha256
    activation_receipt_schema = "rusty.quest.spatial_asset_model.activation_receipt.v1"
    activation_effective_marker = "rusty.quest.spatial_asset_model.effective"
    runtime_property_activation_profile_id = "debug.rustyquest.spatial.asset_model.activation.profile_id"
    runtime_property_activation_project_id = "debug.rustyquest.spatial.asset_model.activation.project_id"
    runtime_property_activation_feature_id = "debug.rustyquest.spatial.asset_model.activation.feature_id"
    runtime_property_activation_lock_revision = "debug.rustyquest.spatial.asset_model.activation.lock_revision"
    runtime_property_activation_lock_sha256 = "debug.rustyquest.spatial.asset_model.activation.lock_sha256"
    adb_commands = $commands
}

Save-Receipt -Receipt $receipt
$receipt | ConvertTo-Json -Depth 8
