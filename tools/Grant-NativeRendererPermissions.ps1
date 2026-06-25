param(
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.native_renderer",
    [string[]]$Permissions = @(),
    [switch]$GrantMediaProjectionAppOp,
    [switch]$ResetMediaProjectionAppOp,
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

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$script:ResolvedAdb = Resolve-ToolPath `
    -Name "adb" `
    -Value $Adb `
    -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
$script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument -Value $AdbServerPort

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "-Serial or RUSTY_QUEST_SERIAL is required; permission pregrant must use adb -s <serial>."
}

if ([string]::IsNullOrWhiteSpace($Out)) {
    $Out = Join-Path $repoRoot "local-artifacts\native-renderer-permission-pregrant.json"
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
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "com.oculus.permission.HAND_TRACKING",
    "horizonos.permission.HEADSET_CAMERA",
    "horizonos.permission.SPATIAL_CAMERA",
    "horizonos.permission.USE_SCENE",
    "org.khronos.openxr.permission.OPENXR",
    "org.khronos.openxr.permission.OPENXR_SYSTEM"
)
$permissions = if ($Permissions.Count -gt 0) {
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
    schema = "rusty.quest.native_renderer_permission_pregrant.v1"
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    adb_path = $script:ResolvedAdb
    adb_scope = "device-scoped-adb"
    serial = $Serial
    adb_server_port = $script:ResolvedAdbServerPort
    package_name = $PackageName
    permissions = $permissions
    grant_results = @()
    media_projection_appop_results = @()
    scene_data_appop_results = @()
    note = "pm grant can legitimately fail for normal or signature permissions; runtime-dangerous grants and required app-ops are the acceptance-critical path. PROJECT_MEDIA app-op is an ADB lab pregrant that still requires the app to call createScreenCaptureIntent and receive fresh resultData. USE_SCENE_DATA is prepared for manifest-declared Meta environment-depth routes."
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

    foreach ($permission in $permissions) {
        $summary.grant_results += Invoke-AdbCommand `
            -Name "grant $permission" `
            -Arguments @("shell", "pm", "grant", $PackageName, $permission) `
            -AllowFailure
    }

    if ($GrantMediaProjectionAppOp) {
        $summary.media_projection_appop_results += Invoke-AdbCommand `
            -Name "appops PROJECT_MEDIA allow" `
            -Arguments @("shell", "cmd", "appops", "set", $PackageName, "PROJECT_MEDIA", "allow")
    }

    if ($ResetMediaProjectionAppOp) {
        $summary.media_projection_appop_results += Invoke-AdbCommand `
            -Name "appops PROJECT_MEDIA default" `
            -Arguments @("shell", "cmd", "appops", "set", $PackageName, "PROJECT_MEDIA", "default")
    }

    if ($GrantUseSceneDataAppOp) {
        $summary.scene_data_appop_results += Invoke-AdbCommand `
            -Name "appops USE_SCENE_DATA allow" `
            -Arguments @("shell", "cmd", "appops", "set", $PackageName, "USE_SCENE_DATA", "allow")
    }

    if ($ResetUseSceneDataAppOp) {
        $summary.scene_data_appop_results += Invoke-AdbCommand `
            -Name "appops USE_SCENE_DATA default" `
            -Arguments @("shell", "cmd", "appops", "set", $PackageName, "USE_SCENE_DATA", "default")
    }

    $summary.dumpsys_permission_excerpt = (Invoke-AdbCommand `
        -Name "permission readback" `
        -Arguments @("shell", "dumpsys", "package", $PackageName) `
        -AllowFailure).output
    $summary.status = "completed"
} catch {
    $summary.status = "failed"
    $summary.error = $_.Exception.Message
    throw
} finally {
    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $Out
    Write-Output "native renderer permission pregrant summary written: $Out"
}
