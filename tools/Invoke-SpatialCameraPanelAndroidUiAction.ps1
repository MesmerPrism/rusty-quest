param(
    [ValidateSet(
        "panel-open",
        "panel-close",
        "private-layer-panel-open",
        "private-layer-panel-close",
        "private-layer-select",
        "private-layer-zone-off",
        "private-layer-zone-native-buffer",
        "private-layer-zone-linear-buffer",
        "private-layer-zone-organic-buffer",
        "private-layer-zone-full-stretch",
        "private-layer-zone-component-blend-test",
        "private-layer-zone-region-blend-test",
        "private-layer-zone-video-underlay-blend-test",
        "rgb-channel-bypass",
        "rgb-channel-linked",
        "rgb-channel-independent",
        "projection-surface-displacement-off",
        "projection-surface-displacement-gentle",
        "projection-surface-displacement-deep",
        "projection-panel-off",
        "projection-panel-on",
        "video-previous",
        "video-next",
        "video-recenter",
        "video-playback-off",
        "video-playback-on",
        "video-select",
        "video-world-anchored",
        "video-head-fixed-border",
        "background-black",
        "background-passthrough",
        "background-lut-passthrough",
        "profile-save-current",
        "choose-shared-media-folder",
        "environment-depth-recovery-bounded",
        "environment-depth-recovery-aggressive",
        "particle-controls",
        "particle-panel-distance",
        "particle-panel-view-yaw",
        "particle-recenter",
        "particle-alias-control",
        "surface-target-activate"
    )]
    [string]$Action = "panel-open",

    [ValidateSet("real-hands", "gpu-replay-hands", "icosphere")]
    [string]$SurfaceTargetId = "real-hands",

    [string]$VideoPackId = "",

    [string]$ProfileTitle = "",

    [double]$PrivateLayerOverride = 0.0,

    [double]$Driver0 = 1.0,

    [double]$Driver1 = 0.0,

    [double]$Driver2 = 0.0,

    [double]$Driver3 = 0.0,

    [double]$Driver4 = 0.0,

    [double]$Driver5 = 0.0,

    [double]$Driver6 = 0.0,

    [double]$Driver7 = 0.0,

    [double]$PointScale = 1.0,

    [double]$TracerDrawSlotsPerOscillator = 7.0,

    [double]$TracerLifetimeSeconds = 0.5,

    [double]$TracerCopiesPerSecond = 14.0,

    [double]$TransparencyOpacity = 0.36,

    [double]$ProjectionWorldScale = 1.0,

    [double]$ParticleLayerTargetDistanceMeters = 1.35,

    [double]$ParticleLayerViewYawDegrees = 0.0,

    [string]$ParticleAliasParameterId = "tracer_draw_slots_per_oscillator",

    [double]$ParticleAliasValue = 7.0,

    [ValidateSet("default", "particle-size-driver2", "all-visual-drivers")]
    [string]$VisualDriverActivationProfile = "default",

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
    "surface_target_id",
    $SurfaceTargetId,
    "--es",
    "profile_title",
    $ProfileTitle,
    "--ef",
    "private_layer_override",
    (Format-InvariantNumber ([Math]::Max(-1.0, [Math]::Min(8.0, $PrivateLayerOverride)))),
    "--ef",
    "driver0",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(1.0, $Driver0)))),
    "--ef",
    "driver1",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(1.0, $Driver1)))),
    "--ef",
    "driver2",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(1.0, $Driver2)))),
    "--ef",
    "driver3",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(1.0, $Driver3)))),
    "--ef",
    "driver4",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(1.0, $Driver4)))),
    "--ef",
    "driver5",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(1.0, $Driver5)))),
    "--ef",
    "driver6",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(1.0, $Driver6)))),
    "--ef",
    "driver7",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(1.0, $Driver7)))),
    "--ef",
    "point_scale",
    (Format-InvariantNumber ([Math]::Max(0.4, [Math]::Min(2.4, $PointScale)))),
    "--ef",
    "tracer_draw_slots_per_oscillator",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(7.0, $TracerDrawSlotsPerOscillator)))),
    "--ef",
    "tracer_lifetime_seconds",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(0.5, $TracerLifetimeSeconds)))),
    "--ef",
    "tracer_copies_per_second",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(14.0, $TracerCopiesPerSecond)))),
    "--ef",
    "transparency_opacity",
    (Format-InvariantNumber ([Math]::Max(0.0, [Math]::Min(1.0, $TransparencyOpacity)))),
    "--ef",
    "projection_world_scale",
    (Format-InvariantNumber ([Math]::Max(0.5, [Math]::Min(2.0, $ProjectionWorldScale)))),
    "--ef",
    "particle_layer_target_distance_meters",
    (Format-InvariantNumber ([Math]::Max(0.2, [Math]::Min(8.0, $ParticleLayerTargetDistanceMeters)))),
    "--ef",
    "particle_layer_view_yaw_degrees",
    (Format-InvariantNumber ([Math]::Max(-180.0, [Math]::Min(180.0, $ParticleLayerViewYawDegrees)))),
    "--es",
    "parameter_id",
    $ParticleAliasParameterId,
    "--ef",
    "value",
    (Format-InvariantNumber $ParticleAliasValue),
    "--es",
    "visual_driver_activation_profile",
    $VisualDriverActivationProfile
)

if ($Action -eq "video-select" -and [string]::IsNullOrWhiteSpace($VideoPackId)) {
    throw "-VideoPackId is required when -Action video-select is requested."
}
if ($Action -eq "profile-save-current" -and
    ([string]::IsNullOrWhiteSpace($ProfileTitle) -or $ProfileTitle.Trim() -cne $ProfileTitle -or $ProfileTitle.Length -gt 96)) {
    throw "-ProfileTitle must contain 1 to 96 trimmed characters when -Action profile-save-current is requested."
}
if (-not [string]::IsNullOrWhiteSpace($VideoPackId)) {
    $intentArguments += @("--es", "video_pack_id", $VideoPackId.Trim())
}

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
    video_pack_id = $VideoPackId
    profile_title = $ProfileTitle
    pid = $targetPid
    launch_exit_code = $launch.exit_code
    launch_output = $launch.output
    marker_tail = $markerTail
} | ConvertTo-Json -Depth 6
