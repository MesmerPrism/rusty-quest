param(
    [ValidateSet(
        "panel-open",
        "panel-close",
        "private-layer-panel-open",
        "private-layer-panel-close",
        "panel-reset",
        "panel-headlock-on",
        "panel-headlock-off",
        "panel-headlock-toggle",
        "panel-adjust",
        "panel-resize",
        "particle-controls",
        "participant-reset",
        "participant-begin",
        "polar-setup-save",
        "surface-select",
        "start-block",
        "surface-target-activate",
        "questionnaire-submit"
    )]
    [string]$Action = "panel-open",

    [string]$ParticipantId = "codex-spatial-ui-command",

    [ValidateSet("real-hands", "gpu-replay-hands", "icosphere")]
    [string]$SurfaceTargetId = "real-hands",

    [double]$DeltaX = 0.0,

    [double]$DeltaY = 0.0,

    [double]$DeltaZ = 0.0,

    [double]$DeltaScale = 0.0,

    [double]$DeltaWidth = 0.0,

    [double]$DeltaHeight = 0.0,

    [double]$Driver0 = 1.0,

    [double]$Driver1 = 0.0,

    [double]$PointScale = 1.0,

    [string]$RunLabel = "remote-ui-command",

    [string]$OperatorId = "codex",

    [string]$Notes = "Remote UI command",

    [int]$ComfortRating = 4,

    [int]$IntensityRating = 4,

    [int]$EngagementRating = 4,

    [string]$Serial = $env:RUSTY_QUEST_SERIAL,

    [string]$AdbPath = $env:RUSTY_QUEST_ADB,

    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,

    [string]$PackageName = "io.github.mesmerprism.rustyquest.spatial_camera_panel",

    [string]$Activity = "io.github.mesmerprism.rustyquest.spatial_camera_panel/.SpatialCameraPanelActivity",

    [switch]$ReadMarkers
)

$ErrorActionPreference = "Stop"

$UiCommandAction = "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_UI_COMMAND"

function Resolve-ToolPath {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
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

function Format-InvariantNumber {
    param([double]$Value)
    return $Value.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
}

function Invoke-AdbCommand {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string[]]$Arguments,
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

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "Pass -Serial or set RUSTY_QUEST_SERIAL."
}

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    if (-not [string]::IsNullOrWhiteSpace($env:RUSTY_QUEST_ADB)) {
        $AdbPath = $env:RUSTY_QUEST_ADB
    } elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $AdbPath = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    } else {
        $AdbPath = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
    }
}

$script:ResolvedAdb = Resolve-ToolPath -Name "adb" -Value $AdbPath -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
$script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument -Value $AdbServerPort

$intentArguments = @(
    "shell",
    "am",
    "start",
    "-W",
    "-n",
    $Activity,
    "-a",
    $UiCommandAction,
    "--es",
    "ui_action",
    $Action,
    "--es",
    "participant_id",
    $ParticipantId,
    "--es",
    "surface_target_id",
    $SurfaceTargetId,
    "--es",
    "run_label",
    $RunLabel,
    "--es",
    "operator_id",
    $OperatorId,
    "--es",
    "notes",
    $Notes,
    "--ef",
    "delta_x",
    (Format-InvariantNumber $DeltaX),
    "--ef",
    "delta_y",
    (Format-InvariantNumber $DeltaY),
    "--ef",
    "delta_z",
    (Format-InvariantNumber $DeltaZ),
    "--ef",
    "delta_scale",
    (Format-InvariantNumber $DeltaScale),
    "--ef",
    "delta_width",
    (Format-InvariantNumber $DeltaWidth),
    "--ef",
    "delta_height",
    (Format-InvariantNumber $DeltaHeight),
    "--ef",
    "driver0",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(1.0, $Driver0)))),
    "--ef",
    "driver1",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(1.0, $Driver1)))),
    "--ef",
    "point_scale",
    (Format-InvariantNumber ([Math]::Max(0.4, [Math]::Min(2.4, $PointScale)))),
    "--ei",
    "comfort_rating",
    ([Math]::Max(1, [Math]::Min(7, $ComfortRating))).ToString(),
    "--ei",
    "intensity_rating",
    ([Math]::Max(1, [Math]::Min(7, $IntensityRating))).ToString(),
    "--ei",
    "engagement_rating",
    ([Math]::Max(1, [Math]::Min(7, $EngagementRating))).ToString()
)

$launch = Invoke-AdbCommand -Name "run Spatial Camera Panel UI action $Action" -Arguments $intentArguments
Start-Sleep -Milliseconds 350
$pidResult = Invoke-AdbCommand -Name "read app pid" -Arguments @("shell", "pidof", $PackageName) -AllowFailure
$targetPid = (($pidResult.output -split "\s+") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)

$markerTail = ""
if ($ReadMarkers) {
    $markerResult = Invoke-AdbCommand `
        -Name "read activity marker tail" `
        -Arguments @("exec-out", "run-as", $PackageName, "tail", "-n", "40", "files/spatial_camera_panel_activity_markers.log") `
        -AllowFailure
    $markerTail = $markerResult.output
}

[pscustomobject]@{
    schema = "rusty.quest.spatial_camera_panel_ui_action_invoked.v1"
    serial = $Serial
    package_name = $PackageName
    activity = $Activity
    action = $Action
    surface_target_id = $SurfaceTargetId
    participant_id = $ParticipantId
    pid = $targetPid
    launch_exit_code = $launch.exit_code
    launch_output = $launch.output
    marker_tail = $markerTail
} | ConvertTo-Json -Depth 6
