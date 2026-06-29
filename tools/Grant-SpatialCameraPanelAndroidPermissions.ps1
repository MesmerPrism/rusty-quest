param(
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.spatial_camera_panel",
    [string[]]$Permissions = @(),
    [switch]$GrantUseSceneDataAppOp,
    [switch]$ResetUseSceneDataAppOp,
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
        if (Test-Path -LiteralPath $Value) {
            return (Resolve-Path -LiteralPath $Value).Path
        }
        $command = Get-Command $Value -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
        throw "$Name not found: $Value"
    }

    if (-not [string]::IsNullOrWhiteSpace($DefaultPath) -and (Test-Path -LiteralPath $DefaultPath)) {
        return (Resolve-Path -LiteralPath $DefaultPath).Path
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

function Test-PackageDeclaresPermission {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Dumpsys,
        [Parameter(Mandatory=$true)]
        [string]$Permission
    )
    return $Dumpsys.Contains($Permission)
}

function Get-PermissionGrantedState {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Dumpsys,
        [Parameter(Mandatory=$true)]
        [string]$Permission
    )
    $escaped = [regex]::Escape($Permission)
    $match = [regex]::Match($Dumpsys, "$escaped[^\r\n]*granted=(true|false)")
    if ($match.Success) {
        return $match.Groups[1].Value
    }
    return ""
}

function Get-AppOpMode {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Package,
        [Parameter(Mandatory=$true)]
        [string]$Op
    )
    $result = Invoke-AdbCommand `
        -Name "appops get $Op" `
        -Arguments @("shell", "cmd", "appops", "get", $Package, $Op) `
        -AllowFailure
    if ($result.exit_code -ne 0) {
        return ""
    }
    $match = [regex]::Match($result.output, "${Op}:\s+([a-zA-Z_]+)")
    if ($match.Success) {
        return $match.Groups[1].Value
    }
    return $result.output.Trim()
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$script:ResolvedAdb = Resolve-ToolPath `
    -Name "adb" `
    -Value $Adb `
    -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
$script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument -Value $AdbServerPort

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "-Serial or RUSTY_QUEST_SERIAL is required; permission pregrant must use adb -s <serial>."
}
$script:Serial = $Serial

if ([string]::IsNullOrWhiteSpace($Out)) {
    $Out = Join-Path $repoRoot "local-artifacts\spatial-camera-panel-permission-pregrant.json"
} elseif (-not [System.IO.Path]::IsPathRooted($Out)) {
    $Out = Join-Path $repoRoot $Out
}
$outDir = Split-Path -Parent $Out
if (-not [string]::IsNullOrWhiteSpace($outDir)) {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
}

$defaultPermissions = @(
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.BLUETOOTH",
    "android.permission.BLUETOOTH_ADMIN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.CAMERA",
    "android.permission.MODIFY_AUDIO_SETTINGS",
    "com.oculus.permission.HAND_TRACKING",
    "com.oculus.permission.RENDER_MODEL",
    "horizonos.permission.HEADSET_CAMERA",
    "horizonos.permission.SPATIAL_CAMERA",
    "horizonos.permission.USE_SCENE",
    "org.khronos.openxr.permission.OPENXR",
    "org.khronos.openxr.permission.OPENXR_SYSTEM"
)
$requestedPermissions = if ($Permissions.Count -gt 0) {
    @(
        $Permissions |
            ForEach-Object { ([string]$_).Split(",") } |
            ForEach-Object { ([string]$_).Trim() } |
            Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } |
            Sort-Object -Unique
    )
} else {
    $defaultPermissions
}

$summary = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel_permission_pregrant.v1"
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    adb_path = $script:ResolvedAdb
    adb_scope = "device-scoped-adb"
    serial = $Serial
    adb_server_port = $script:ResolvedAdbServerPort
    package_name = $PackageName
    requested_permissions = $requestedPermissions
    declared_permissions = @()
    skipped_undeclared_permissions = @()
    grant_results = @()
    scene_data_appop_results = @()
    use_scene_permission_declared = $false
    use_scene_permission_granted = ""
    use_scene_data_appop_requested = [bool]($GrantUseSceneDataAppOp -or $ResetUseSceneDataAppOp)
    use_scene_data_appop_mode = ""
    note = "Only package-declared permissions are requested. pm grant may fail for normal or signature permissions; the readback and USE_SCENE_DATA app-op evidence locate scene-depth setup gaps."
}

try {
    $state = Invoke-AdbCommand -Name "adb get-state" -Arguments @("get-state")
    $summary.device_state = $state.output.Trim()
    if ($summary.device_state -ne "device") {
        throw "ADB target is not ready: $($summary.device_state)"
    }
    $summary.device_model = (Invoke-AdbCommand -Name "device model" -Arguments @("shell", "getprop", "ro.product.model")).output.Trim()
    $summary.package_path = (Invoke-AdbCommand -Name "package path" -Arguments @("shell", "pm", "path", $PackageName)).output.Trim()
    if ([string]::IsNullOrWhiteSpace($summary.package_path)) {
        throw "Package is not installed: $PackageName"
    }

    $beforeDumpsys = (Invoke-AdbCommand `
        -Name "permission readback before" `
        -Arguments @("shell", "dumpsys", "package", $PackageName) `
        -AllowFailure).output
    $summary.dumpsys_permission_excerpt_before = $beforeDumpsys
    foreach ($permission in $requestedPermissions) {
        if (Test-PackageDeclaresPermission -Dumpsys $beforeDumpsys -Permission $permission) {
            $summary.declared_permissions += $permission
        } else {
            $summary.skipped_undeclared_permissions += $permission
        }
    }

    foreach ($permission in $summary.declared_permissions) {
        $grant = Invoke-AdbCommand `
            -Name "grant $permission" `
            -Arguments @("shell", "pm", "grant", $PackageName, $permission) `
            -AllowFailure
        $summary.grant_results += [ordered]@{
            permission = $permission
            declared = $true
            exit_code = $grant.exit_code
            output = $grant.output
        }
    }

    if ($GrantUseSceneDataAppOp) {
        $summary.scene_data_appop_results += Invoke-AdbCommand `
            -Name "appops USE_SCENE_DATA allow" `
            -Arguments @("shell", "cmd", "appops", "set", $PackageName, "USE_SCENE_DATA", "allow") `
            -AllowFailure
    }

    if ($ResetUseSceneDataAppOp) {
        $summary.scene_data_appop_results += Invoke-AdbCommand `
            -Name "appops USE_SCENE_DATA default" `
            -Arguments @("shell", "cmd", "appops", "set", $PackageName, "USE_SCENE_DATA", "default") `
            -AllowFailure
    }

    $afterDumpsys = (Invoke-AdbCommand `
        -Name "permission readback after" `
        -Arguments @("shell", "dumpsys", "package", $PackageName) `
        -AllowFailure).output
    $summary.dumpsys_permission_excerpt_after = $afterDumpsys
    $summary.use_scene_permission_declared = $summary.declared_permissions -contains "horizonos.permission.USE_SCENE"
    $summary.use_scene_permission_granted = Get-PermissionGrantedState `
        -Dumpsys $afterDumpsys `
        -Permission "horizonos.permission.USE_SCENE"
    $summary.use_scene_data_appop_mode = Get-AppOpMode -Package $PackageName -Op "USE_SCENE_DATA"
    $summary.status = "completed"
} catch {
    $summary.status = "failed"
    $summary.error = $_.Exception.Message
    throw
} finally {
    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $Out
    Write-Output "Spatial Camera Panel permission pregrant summary written: $Out"
}
