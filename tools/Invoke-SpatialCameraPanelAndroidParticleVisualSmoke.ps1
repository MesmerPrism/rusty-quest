param(
    [string]$RepoRoot,
    [string]$ApkPath = "target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk",
    [string]$OutDir = "",
    [int]$RunSeconds = 10,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.spatial_camera_panel",
    [string]$Activity = "io.github.mesmerprism.rustyquest.spatial_camera_panel/.SpatialCameraPanelActivity",
    [string]$ParticipantId = "",
    [ValidateSet("real-hands", "gpu-replay-hands", "icosphere")]
    [string]$SurfaceTargetId = "icosphere",
    [switch]$UsePrivateEcsIcosphere,
    [switch]$SkipInstall,
    [switch]$ClearLogcat,
    [switch]$StopAfterRun,
    [switch]$AllowMissingMarkers,
    [switch]$SkipParticleControlBoost,
    [switch]$RequireWorldAnchorMotion,
    [switch]$ExercisePanelDistanceMotion,
    [switch]$ExercisePanelViewYawMotion,
    [switch]$ExerciseParticleRecenter,
    [switch]$RequireWorldAnchorStabilityDuringPanelMotion,
    [switch]$RequireWorldAnchorStabilityDuringPanelViewYawMotion,
    [double]$MinimumWorldAnchorPanelMotionMeters = 0.02,
    [double]$MinimumWorldAnchorPanelForwardMotion = 0.10,
    [double]$MinimumWorldAnchorMappedMotionMeters = 0.005,
    [double]$PanelDistanceMotionNearMeters = 1.35,
    [double]$PanelDistanceMotionFarMeters = 2.00,
    [int]$PanelDistanceMotionSettlingMilliseconds = 900,
    [double]$PanelViewYawLeftDegrees = -18.0,
    [double]$PanelViewYawRightDegrees = 18.0,
    [int]$PanelViewYawMotionSettlingMilliseconds = 900,
    [double]$MaximumWorldAnchorMappedDriftMeters = 0.015
)

$ErrorActionPreference = "Stop"

$SurfaceTargetAction = "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_SURFACE_TARGET"
$UiCommandAction = "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_UI_COMMAND"
$MarkerPrefix = "RUSTY_QUEST_SPATIAL_CAMERA_PANEL"
$ParticleLayerTargetDistanceProperty = "debug.rustyquest.spatial_camera_panel.particle_layer.target_distance_meters"
$ParticleLayerViewYawProperty = "debug.rustyquest.spatial_camera_panel.particle_layer.view_yaw_degrees"
$ParticleLayerSurfaceOverscanProperty = "debug.rustyquest.spatial_camera_panel.particle_layer.surface_overscan_scale"
$ParticleLayerCarrierProperty = "debug.rustyquest.spatial_camera_panel.particle_layer.carrier"
$ParticleLayerCarrierValue = "manual-panel-scene-object-custom-mesh"
$ParticleLayerRendererModeProperty = "debug.rustyquest.spatial_camera_panel.particle_layer.renderer_mode"
$ParticleLayerRendererModeValue = "private-main-draw-only"
$NativeSurfaceParticleLayerEnabledProperty = "debug.rustyquest.spatial.native_surface_particle_layer.enabled"
$PrivateEcsEnabledProperty = "debug.rustyquest.spatial.viscereality_ecs.enabled"
$PrivateEcsCarrierProperty = "debug.rustyquest.spatial.viscereality_ecs.carrier"
$PrivateEcsCountProperty = "debug.rustyquest.spatial.viscereality_ecs.count"
$PrivateEcsCarrierCountProperty = "debug.rustyquest.spatial.viscereality_ecs.carrier_count"
$PrivateEcsSphereRadiusProperty = "debug.rustyquest.spatial.viscereality_ecs.sphere_radius_meters"
$PrivateEcsBillboardMetersProperty = "debug.rustyquest.spatial.viscereality_ecs.billboard_meters"
$PrivateEcsAutoRecenterDistanceProperty = "debug.rustyquest.spatial.viscereality_ecs.auto_recenter_distance_meters"
$PrivateEcsCarrierValue = "batched-scene-mesh"
$ParticleLayerDefaultTargetDistanceMeters = 2.00
$ParticleLayerDefaultViewYawDegrees = 0.0
$ParticleLayerDefaultSurfaceOverscanScale = 1.0

if ($UsePrivateEcsIcosphere -and $SurfaceTargetId -ne "icosphere") {
    throw "-UsePrivateEcsIcosphere requires -SurfaceTargetId icosphere."
}
if ($UsePrivateEcsIcosphere -and (
        $ExerciseParticleRecenter `
        -or $RequireWorldAnchorMotion `
        -or $RequireWorldAnchorStabilityDuringPanelMotion `
        -or $RequireWorldAnchorStabilityDuringPanelViewYawMotion)) {
    throw "-UsePrivateEcsIcosphere is an exclusive renderer path; native surface-particle recenter and panel-motion assertions do not apply."
}
$script:EffectiveSkipParticleControlBoost = [bool]$SkipParticleControlBoost -or [bool]$UsePrivateEcsIcosphere

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

function Save-Text {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [AllowNull()][string]$Text
    )

    if ($null -eq $Text) {
        $Text = ""
    }
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.Encoding]::UTF8)
}

function Save-AppPrivateFile {
    param(
        [Parameter(Mandatory=$true)][string]$RemotePath,
        [Parameter(Mandatory=$true)][string]$LocalPath
    )

    $result = Invoke-AdbCommand `
        -Name "app-private file $RemotePath" `
        -Arguments @("exec-out", "run-as", $PackageName, "cat", $RemotePath) `
        -AllowFailure
    if ($result.exit_code -eq 0) {
        Save-Text -Path $LocalPath -Text $result.output
        return $true
    }
    Save-Text -Path ($LocalPath + ".error.txt") -Text $result.output
    return $false
}

function Format-InvariantNumber {
    param([double]$Value)
    return $Value.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
}

function Test-TextContains {
    param(
        [AllowNull()][string]$Text,
        [string]$Needle
    )
    return (-not [string]::IsNullOrWhiteSpace($Text)) -and $Text.Contains($Needle)
}

function Test-LineContainsAll {
    param(
        [AllowNull()][string]$Text,
        [Parameter(Mandatory=$true)][string[]]$Needles
    )

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }
    foreach ($line in [regex]::Split($Text, "`r?`n")) {
        $matched = $true
        foreach ($needle in $Needles) {
            if (-not $line.Contains($needle)) {
                $matched = $false
                break
            }
        }
        if ($matched) {
            return $true
        }
    }
    return $false
}

function Get-LineIndexContainingAll {
    param(
        [AllowNull()][string]$Text,
        [Parameter(Mandatory=$true)][string[]]$Needles
    )

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return -1
    }
    $lines = [regex]::Split($Text, "`r?`n")
    for ($index = 0; $index -lt $lines.Length; $index++) {
        $matched = $true
        foreach ($needle in $Needles) {
            if (-not $lines[$index].Contains($needle)) {
                $matched = $false
                break
            }
        }
        if ($matched) {
            return $index
        }
    }
    return -1
}

function Get-FirstRegexInt {
    param(
        [AllowNull()][string]$Text,
        [Parameter(Mandatory=$true)][string]$Pattern
    )

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return 0
    }
    $match = [regex]::Match($Text, $Pattern)
    if (-not $match.Success) {
        return 0
    }
    $value = 0
    if ([int]::TryParse($match.Groups[1].Value, [ref]$value)) {
        return $value
    }
    return 0
}

function Get-VectorDistance {
    param(
        [Parameter(Mandatory=$true)][double[]]$A,
        [Parameter(Mandatory=$true)][double[]]$B
    )
    if ($A.Length -lt 3 -or $B.Length -lt 3) {
        return 0.0
    }
    $dx = $A[0] - $B[0]
    $dy = $A[1] - $B[1]
    $dz = $A[2] - $B[2]
    return [Math]::Sqrt($dx * $dx + $dy * $dy + $dz * $dz)
}

function Get-MaxVectorSpan {
    param(
        [AllowNull()][object[]]$Samples,
        [Parameter(Mandatory=$true)][string]$PropertyName
    )
    if ($null -eq $Samples) {
        return 0.0
    }
    if ($Samples.Count -lt 2) {
        return 0.0
    }
    $maxDistance = 0.0
    for ($i = 0; $i -lt $Samples.Count; $i++) {
        for ($j = $i + 1; $j -lt $Samples.Count; $j++) {
            $distance = Get-VectorDistance -A $Samples[$i].$PropertyName -B $Samples[$j].$PropertyName
            if ($distance -gt $maxDistance) {
                $maxDistance = $distance
            }
        }
    }
    return $maxDistance
}

function Get-MaxScalarSpan {
    param(
        [AllowNull()][object[]]$Samples,
        [Parameter(Mandatory=$true)][string]$PropertyName
    )
    if ($null -eq $Samples) {
        return 0.0
    }
    if ($Samples.Count -lt 2) {
        return 0.0
    }
    $minValue = [double]::PositiveInfinity
    $maxValue = [double]::NegativeInfinity
    foreach ($sample in $Samples) {
        $value = [double]$sample.$PropertyName
        if ($value -lt $minValue) {
            $minValue = $value
        }
        if ($value -gt $maxValue) {
            $maxValue = $value
        }
    }
    if ([double]::IsInfinity($minValue) -or [double]::IsInfinity($maxValue)) {
        return 0.0
    }
    return $maxValue - $minValue
}

function Get-WorldAnchorMotionSamples {
    param([AllowNull()][string]$Text)

    $samples = New-Object System.Collections.Generic.List[object]
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return @()
    }
    $pattern = "status=private-openxr-world-anchor-mapped[^\r\n]*mappedWorldAnchorCenterM=([-0-9.]+);([-0-9.]+);([-0-9.]+)[^\r\n]*mappedWorldAnchorForward=([-0-9.]+);([-0-9.]+);([-0-9.]+)[^\r\n]*panelCenterM=([-0-9.]+);([-0-9.]+);([-0-9.]+)"
    foreach ($match in [regex]::Matches($Text, $pattern)) {
        $values = @()
        $valid = $true
        for ($index = 1; $index -le 9; $index++) {
            $parsed = 0.0
            if (-not [double]::TryParse(
                $match.Groups[$index].Value,
                [Globalization.NumberStyles]::Float,
                [Globalization.CultureInfo]::InvariantCulture,
                [ref]$parsed
            )) {
                $valid = $false
                break
            }
            $values += $parsed
        }
        if ($valid) {
            $samples.Add([pscustomobject]@{
                mapped_center = [double[]]@($values[0], $values[1], $values[2])
                mapped_forward = [double[]]@($values[3], $values[4], $values[5])
                panel_center = [double[]]@($values[6], $values[7], $values[8])
            })
        }
    }
    return @($samples.ToArray())
}

function Get-SceneFixedWorldAnchorMotionSamples {
    param([AllowNull()][string]$Text)

    $samples = New-Object System.Collections.Generic.List[object]
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return @()
    }
    $pattern = "status=private-scene-fixed-world-anchor-sampled[^\r\n]*worldAnchorCenterM=([-0-9.]+);([-0-9.]+);([-0-9.]+)[^\r\n]*worldAnchorForward=([-0-9.]+);([-0-9.]+);([-0-9.]+)[^\r\n]*panelCenterM=([-0-9.]+);([-0-9.]+);([-0-9.]+)[^\r\n]*panelForward=([-0-9.]+);([-0-9.]+);([-0-9.]+)[^\r\n]*panelTargetDistanceMeters=([-0-9.]+)"
    foreach ($match in [regex]::Matches($Text, $pattern)) {
        $values = @()
        $valid = $true
        for ($index = 1; $index -le 13; $index++) {
            $parsed = 0.0
            if (-not [double]::TryParse(
                $match.Groups[$index].Value,
                [Globalization.NumberStyles]::Float,
                [Globalization.CultureInfo]::InvariantCulture,
                [ref]$parsed
            )) {
                $valid = $false
                break
            }
            $values += $parsed
        }
        if ($valid) {
            $samples.Add([pscustomobject]@{
                text_index = [int]$match.Index
                world_anchor_center = [double[]]@($values[0], $values[1], $values[2])
                world_anchor_forward = [double[]]@($values[3], $values[4], $values[5])
                panel_center = [double[]]@($values[6], $values[7], $values[8])
                panel_forward = [double[]]@($values[9], $values[10], $values[11])
                panel_target_distance_meters = [double]$values[12]
            })
        }
    }
    return @($samples.ToArray())
}

function Get-LatestSceneFixedWorldAnchorEpochIndex {
    param([AllowNull()][string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return -1
    }
    $latestIndex = -1
    $pattern = "status=private-world-anchor-(captured|recentered|auto-recentered)"
    foreach ($match in [regex]::Matches($Text, $pattern)) {
        $latestIndex = [int]$match.Index
    }
    return $latestIndex
}

function Assert-SummaryFlag {
    param(
        [System.Collections.IDictionary]$Summary,
        [string]$Name
    )
    if (-not [bool]$Summary[$Name]) {
        throw "Spatial Camera Panel particle-visual smoke evidence missing required flag: $Name"
    }
}

function Measure-ScreenshotDimensions {
    param([Parameter(Mandatory=$true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return [ordered]@{
            exists = $false
            width = 0
            height = 0
        }
    }

    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new((Resolve-Path -LiteralPath $Path).Path)
    try {
        return [ordered]@{
            exists = $true
            path = (Resolve-Path -LiteralPath $Path).Path
            width = $bitmap.Width
            height = $bitmap.Height
        }
    } finally {
        $bitmap.Dispose()
    }
}

function Invoke-SurfaceTargetActivation {
    $result = Invoke-AdbCommand -Name "activate Spatial Camera Panel surface target" -Arguments @(
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        $Activity,
        "-a",
        $SurfaceTargetAction,
        "--es",
        "participant_id",
        $ParticipantId,
        "--es",
        "surface_target_id",
        $SurfaceTargetId,
        "--es",
        "run_label",
        "particle-visual-smoke",
        "--es",
        "operator_id",
        "codex",
        "--es",
        "notes",
        "no-controller-particle-visual-smoke"
    )
    Save-Text -Path (Join-Path $OutDir "surface-target-activation.txt") -Text $result.output
    return $result
}

function Invoke-ParticleControlBoost {
    $result = Invoke-AdbCommand -Name "boost particle controls for visual smoke" -Arguments @(
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
        "particle-controls",
        "--es",
        "participant_id",
        $ParticipantId,
        "--es",
        "surface_target_id",
        $SurfaceTargetId,
        "--es",
        "run_label",
        "particle-visual-smoke",
        "--es",
        "operator_id",
        "codex",
        "--es",
        "notes",
        "no-controller-particle-visual-smoke",
        "--ef",
        "driver0",
        "1",
        "--ef",
        "driver1",
        "0.35",
        "--ef",
        "driver2",
        "0.62",
        "--ef",
        "driver3",
        "0.2",
        "--ef",
        "point_scale",
        "0.70",
        "--ef",
        "tracer_draw_slots_per_oscillator",
        "7",
        "--ef",
        "tracer_lifetime_seconds",
        "0.5",
        "--ef",
        "tracer_copies_per_second",
        "14",
        "--ef",
        "transparency_opacity",
        "0.36",
        "--ef",
        "projection_world_scale",
        "1.0"
    )
    Save-Text -Path (Join-Path $OutDir "particle-control-boost.txt") -Text $result.output
    return $result
}

function Invoke-ParticleRecenter {
    $result = Invoke-AdbCommand -Name "recenter particle sphere on viewer" -Arguments @(
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
        "particle-recenter",
        "--es",
        "participant_id",
        $ParticipantId,
        "--es",
        "surface_target_id",
        $SurfaceTargetId,
        "--es",
        "run_label",
        "particle-visual-smoke",
        "--es",
        "operator_id",
        "codex",
        "--es",
        "notes",
        "no-controller-particle-recenter-smoke"
    )
    Save-Text -Path (Join-Path $OutDir "particle-recenter.txt") -Text $result.output
    return $result
}

function Set-ParticleLayerTargetDistance {
    param(
        [Parameter(Mandatory=$true)][double]$Value,
        [Parameter(Mandatory=$true)][string]$Label
    )

    $formattedValue = Format-InvariantNumber -Value $Value
    $result = Invoke-AdbCommand `
        -Name "set particle layer target distance $Label" `
        -Arguments @("shell", "setprop", $ParticleLayerTargetDistanceProperty, $formattedValue) `
        -AllowFailure
    Save-Text -Path (Join-Path $OutDir "set-particle-layer-target-distance-$Label.txt") -Text $result.output
    return $result
}

function Set-ParticleLayerViewYaw {
    param(
        [Parameter(Mandatory=$true)][double]$Value,
        [Parameter(Mandatory=$true)][string]$Label
    )

    $formattedValue = Format-InvariantNumber -Value $Value
    $result = Invoke-AdbCommand `
        -Name "set particle layer view yaw $Label" `
        -Arguments @("shell", "setprop", $ParticleLayerViewYawProperty, $formattedValue) `
        -AllowFailure
    Save-Text -Path (Join-Path $OutDir "set-particle-layer-view-yaw-$Label.txt") -Text $result.output
    return $result
}

function Invoke-ParticleLayerTargetDistanceCommand {
    param(
        [Parameter(Mandatory=$true)][double]$Value,
        [Parameter(Mandatory=$true)][string]$Label
    )

    $formattedValue = Format-InvariantNumber -Value $Value
    $result = Invoke-AdbCommand -Name "apply particle layer target distance command $Label" -Arguments @(
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
        "particle-panel-distance",
        "--es",
        "participant_id",
        $ParticipantId,
        "--es",
        "surface_target_id",
        $SurfaceTargetId,
        "--es",
        "run_label",
        "particle-visual-smoke",
        "--es",
        "operator_id",
        "codex",
        "--es",
        "notes",
        "no-controller-panel-distance-motion",
        "--ef",
        "particle_layer_target_distance_meters",
        $formattedValue
    ) -AllowFailure
    Save-Text -Path (Join-Path $OutDir "particle-layer-target-distance-command-$Label.txt") -Text $result.output
    return $result
}

function Invoke-ParticleLayerViewYawCommand {
    param(
        [Parameter(Mandatory=$true)][double]$Value,
        [Parameter(Mandatory=$true)][string]$Label
    )

    $formattedValue = Format-InvariantNumber -Value $Value
    $result = Invoke-AdbCommand -Name "apply particle layer view yaw command $Label" -Arguments @(
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
        "particle-panel-view-yaw",
        "--es",
        "participant_id",
        $ParticipantId,
        "--es",
        "surface_target_id",
        $SurfaceTargetId,
        "--es",
        "run_label",
        "particle-visual-smoke",
        "--es",
        "operator_id",
        "codex",
        "--es",
        "notes",
        "no-controller-panel-view-yaw-motion",
        "--ef",
        "particle_layer_view_yaw_degrees",
        $formattedValue
    ) -AllowFailure
    Save-Text -Path (Join-Path $OutDir "particle-layer-view-yaw-command-$Label.txt") -Text $result.output
    return $result
}

function Invoke-PanelDistanceMotionSequence {
    $settleMilliseconds = [Math]::Max(100, $PanelDistanceMotionSettlingMilliseconds)
    $results = @()
    $results += Set-ParticleLayerTargetDistance -Value $PanelDistanceMotionFarMeters -Label "far"
    $results += Invoke-ParticleLayerTargetDistanceCommand -Value $PanelDistanceMotionFarMeters -Label "far"
    Start-Sleep -Milliseconds $settleMilliseconds
    $results += Set-ParticleLayerTargetDistance -Value $PanelDistanceMotionNearMeters -Label "near"
    $results += Invoke-ParticleLayerTargetDistanceCommand -Value $PanelDistanceMotionNearMeters -Label "near"
    Start-Sleep -Milliseconds $settleMilliseconds
    $results += Set-ParticleLayerTargetDistance -Value $ParticleLayerDefaultTargetDistanceMeters -Label "restore-default"
    $results += Invoke-ParticleLayerTargetDistanceCommand -Value $ParticleLayerDefaultTargetDistanceMeters -Label "restore-default"
    Start-Sleep -Milliseconds $settleMilliseconds
    return @($results)
}

function Invoke-PanelViewYawMotionSequence {
    $settleMilliseconds = [Math]::Max(100, $PanelViewYawMotionSettlingMilliseconds)
    $results = @()
    $results += Set-ParticleLayerViewYaw -Value $PanelViewYawRightDegrees -Label "right"
    $results += Invoke-ParticleLayerViewYawCommand -Value $PanelViewYawRightDegrees -Label "right"
    Start-Sleep -Milliseconds $settleMilliseconds
    $results += Set-ParticleLayerViewYaw -Value $PanelViewYawLeftDegrees -Label "left"
    $results += Invoke-ParticleLayerViewYawCommand -Value $PanelViewYawLeftDegrees -Label "left"
    Start-Sleep -Milliseconds $settleMilliseconds
    $results += Set-ParticleLayerViewYaw -Value $ParticleLayerDefaultViewYawDegrees -Label "restore-default"
    $results += Invoke-ParticleLayerViewYawCommand -Value $ParticleLayerDefaultViewYawDegrees -Label "restore-default"
    Start-Sleep -Milliseconds $settleMilliseconds
    return @($results)
}

function Set-ParticleVisualBaselineProperties {
    $handBillboardFlockValue = "0"
    $nativeSurfaceParticleLayerValue = if ($UsePrivateEcsIcosphere) { "0" } else { "true" }
    $nativeSurfaceParticleLayerFile = if ($UsePrivateEcsIcosphere) { "disable-native-surface-particle-layer-for-private-ecs.txt" } else { "enable-native-surface-particle-layer.txt" }
    $privateEcsEnabledValue = if ($UsePrivateEcsIcosphere) { "1" } else { "0" }
    $properties = @(
        @{ Name = "debug.rustyquest.spatial.camera_hwb_projection_probe"; Value = "0"; File = "disable-projection-probe.txt" },
        @{ Name = "debug.rustyquest.spatial.camera_hwb_projection_probe.synthetic_visual"; Value = "0"; File = "disable-synthetic-visual-probe.txt" },
        @{ Name = "debug.rustyquest.spatial.camera_hwb_projection_probe.carrier"; Value = "none"; File = "clear-projection-carrier.txt" },
        @{ Name = "debug.rustyquest.spatial.camera_hwb_projection_probe.video.enabled"; Value = "0"; File = "disable-video-projection.txt" },
        @{ Name = "debug.rustyquest.spatial.video_projection_probe"; Value = "0"; File = "disable-video-only-projection-probe.txt" },
        @{ Name = $NativeSurfaceParticleLayerEnabledProperty; Value = $nativeSurfaceParticleLayerValue; File = $nativeSurfaceParticleLayerFile },
        @{ Name = $ParticleLayerCarrierProperty; Value = $ParticleLayerCarrierValue; File = "set-particle-layer-carrier-manual-scene-object.txt" },
        @{ Name = $ParticleLayerRendererModeProperty; Value = $ParticleLayerRendererModeValue; File = "set-particle-layer-renderer-private-main-draw.txt" },
        @{ Name = $PrivateEcsEnabledProperty; Value = $privateEcsEnabledValue; File = "set-private-ecs-icosphere-enabled.txt" },
        @{ Name = $PrivateEcsCarrierProperty; Value = $PrivateEcsCarrierValue; File = "set-private-ecs-icosphere-carrier.txt" },
        @{ Name = $PrivateEcsCountProperty; Value = "2562"; File = "set-private-ecs-icosphere-count.txt" },
        @{ Name = $PrivateEcsCarrierCountProperty; Value = "2"; File = "set-private-ecs-icosphere-carrier-count.txt" },
        @{ Name = $PrivateEcsSphereRadiusProperty; Value = "2.0"; File = "set-private-ecs-icosphere-radius.txt" },
        @{ Name = $PrivateEcsBillboardMetersProperty; Value = "0.055"; File = "set-private-ecs-icosphere-billboard.txt" },
        @{ Name = $PrivateEcsAutoRecenterDistanceProperty; Value = "0.5"; File = "set-private-ecs-icosphere-auto-recenter.txt" },
        @{ Name = "debug.rustyquest.spatial.panel_shell.visible"; Value = "false"; File = "hide-spatial-panel-shell.txt" },
        @{ Name = $ParticleLayerTargetDistanceProperty; Value = (Format-InvariantNumber -Value $ParticleLayerDefaultTargetDistanceMeters); File = "set-particle-layer-target-distance-default.txt" },
        @{ Name = $ParticleLayerViewYawProperty; Value = (Format-InvariantNumber -Value $ParticleLayerDefaultViewYawDegrees); File = "set-particle-layer-view-yaw-default.txt" },
        @{ Name = $ParticleLayerSurfaceOverscanProperty; Value = (Format-InvariantNumber -Value $ParticleLayerDefaultSurfaceOverscanScale); File = "set-particle-layer-surface-overscan-default.txt" },
        @{ Name = "debug.rustyquest.spatial.asset_model.enabled"; Value = "0"; File = "disable-spatial-asset-model.txt" },
        @{ Name = "debug.rustyquest.spatial.virtual_room.enabled"; Value = "0"; File = "disable-spatial-virtual-room.txt" },
        @{ Name = "debug.rustyquest.spatial.skybox.enabled"; Value = "0"; File = "disable-spatial-skybox.txt" },
        @{ Name = "debug.rustyquest.spatial.skybox.mode"; Value = "none"; File = "disable-spatial-skybox-mode.txt" },
        @{ Name = "debug.rustyquest.spatial.hand_billboard_flock.enabled"; Value = $handBillboardFlockValue; File = "set-hand-billboard-flock-property.txt" }
    )
    foreach ($property in $properties) {
        $result = Invoke-AdbCommand `
            -Name "set particle visual baseline $($property.Name)" `
            -Arguments @("shell", "setprop", $property.Name, $property.Value) `
            -AllowFailure
        Save-Text -Path (Join-Path $OutDir $property.File) -Text $result.output
    }
}

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$repoRootPath = Resolve-Path -LiteralPath $RepoRoot

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "-Serial or RUSTY_QUEST_SERIAL is required; Spatial SDK headset validation must use adb -s <serial>."
}

$resolvedApk = if ([System.IO.Path]::IsPathRooted($ApkPath)) {
    $ApkPath
} else {
    Join-Path $repoRootPath $ApkPath
}
if (-not (Test-Path -LiteralPath $resolvedApk)) {
    throw "APK not found: $resolvedApk"
}

if ([string]::IsNullOrWhiteSpace($ParticipantId)) {
    $ParticipantId = "codex-spatial-particle-visual-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRootPath "local-artifacts\spatial-camera-panel-headset\$stamp-particle-visual-smoke"
} elseif (-not [System.IO.Path]::IsPathRooted($OutDir)) {
    $OutDir = Join-Path $repoRootPath $OutDir
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$script:ResolvedAdb = Resolve-ToolPath `
    -Name "adb" `
    -Value $Adb `
    -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
$script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument -Value $AdbServerPort
$script:Serial = $Serial

$apkSha256 = Get-FileSha256 -Path $resolvedApk
$summaryPath = Join-Path $OutDir "evidence-summary.json"
$pidLogcatPath = Join-Path $OutDir "pid-logcat.txt"
$tagLogcatPath = Join-Path $OutDir "tag-logcat.txt"
$allLogcatPath = Join-Path $OutDir "logcat-all.txt"
$activityMarkersPath = Join-Path $OutDir "activity-markers.log"
$nativeMarkersPath = Join-Path $OutDir "native-markers.log"
$screenshotInitialPath = Join-Path $OutDir "screencap-t0.png"
$screenshotInitialInfoPath = Join-Path $OutDir "screenshot-info-t0.json"
$screenshotPath = Join-Path $OutDir "screencap.png"
$screenshotInfoPath = Join-Path $OutDir "screenshot-info.json"
$remoteScreenshotInitialPath = "/data/local/tmp/rusty-quest-spatial-camera-panel-particle-visual-smoke-t0.png"
$remoteScreenshotPath = "/data/local/tmp/rusty-quest-spatial-camera-panel-particle-visual-smoke.png"

$summary = [ordered]@{
    '$schema' = "rusty.quest.spatial_camera_panel_particle_visual_smoke.v1"
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    status = "started"
    adb_path = $script:ResolvedAdb
    adb_scope = "device-scoped-adb"
    adb_serial_required = $true
    adb_server_port = $script:ResolvedAdbServerPort
    serial = $Serial
    package = $PackageName
    activity = $Activity
    surface_target_action = $SurfaceTargetAction
    ui_command_action = $UiCommandAction
    participant_id = $ParticipantId
    surface_target_id = $SurfaceTargetId
    private_ecs_icosphere_requested = [bool]$UsePrivateEcsIcosphere
    renderer_ownership_mode = $(if ($UsePrivateEcsIcosphere) { "private-spatial-ecs-icosphere-only" } else { "native-surface-particle-layer" })
    native_surface_particle_layer_enabled_property = $NativeSurfaceParticleLayerEnabledProperty
    native_surface_particle_layer_requested_enabled = -not [bool]$UsePrivateEcsIcosphere
    private_ecs_enabled_property = $PrivateEcsEnabledProperty
    private_ecs_carrier_property = $PrivateEcsCarrierProperty
    private_ecs_carrier_value = $PrivateEcsCarrierValue
    apk_path = (Resolve-Path -LiteralPath $resolvedApk).Path
    apk_sha256 = $apkSha256
    out_dir = (Resolve-Path -LiteralPath $OutDir).Path
    run_seconds = $RunSeconds
    skipped_install = [bool]$SkipInstall
    clear_logcat_requested = [bool]$ClearLogcat
    stop_after_run = [bool]$StopAfterRun
    allow_missing_markers = [bool]$AllowMissingMarkers
    skip_particle_control_boost_effective = [bool]$script:EffectiveSkipParticleControlBoost
    controller_input_required = $false
    automation_input_policy = "adb-am-start-intent-commands-no-physical-controller"
    world_anchor_motion_requirement_requested = [bool]$RequireWorldAnchorMotion
    minimum_world_anchor_panel_motion_meters = $MinimumWorldAnchorPanelMotionMeters
    minimum_world_anchor_panel_forward_motion = $MinimumWorldAnchorPanelForwardMotion
    minimum_world_anchor_mapped_motion_meters = $MinimumWorldAnchorMappedMotionMeters
    world_anchor_panel_distance_stability_requirement_requested = [bool]$RequireWorldAnchorStabilityDuringPanelMotion
    world_anchor_panel_view_yaw_stability_requirement_requested = [bool]$RequireWorldAnchorStabilityDuringPanelViewYawMotion
    panel_distance_motion_requested = [bool]($ExercisePanelDistanceMotion -or $RequireWorldAnchorStabilityDuringPanelMotion)
    panel_view_yaw_motion_requested = [bool]($ExercisePanelViewYawMotion -or $RequireWorldAnchorStabilityDuringPanelViewYawMotion)
    particle_recenter_requested = [bool]$ExerciseParticleRecenter
    particle_layer_target_distance_property = $ParticleLayerTargetDistanceProperty
    particle_layer_view_yaw_property = $ParticleLayerViewYawProperty
    particle_layer_carrier_property = $ParticleLayerCarrierProperty
    particle_layer_carrier_value = $ParticleLayerCarrierValue
    particle_layer_renderer_mode_property = $ParticleLayerRendererModeProperty
    particle_layer_renderer_mode_value = $ParticleLayerRendererModeValue
    panel_distance_motion_near_meters = $PanelDistanceMotionNearMeters
    panel_distance_motion_far_meters = $PanelDistanceMotionFarMeters
    panel_distance_motion_settling_milliseconds = $PanelDistanceMotionSettlingMilliseconds
    panel_view_yaw_left_degrees = $PanelViewYawLeftDegrees
    panel_view_yaw_right_degrees = $PanelViewYawRightDegrees
    panel_view_yaw_motion_settling_milliseconds = $PanelViewYawMotionSettlingMilliseconds
    maximum_world_anchor_mapped_drift_meters = $MaximumWorldAnchorMappedDriftMeters
    pid_logcat_path = $pidLogcatPath
    tag_logcat_path = $tagLogcatPath
    all_logcat_path = $allLogcatPath
    activity_markers_path = $activityMarkersPath
    native_markers_path = $nativeMarkersPath
    screenshot_initial_path = $screenshotInitialPath
    screenshot_initial_info_path = $screenshotInitialInfoPath
    screenshot_path = $screenshotPath
    screenshot_info_path = $screenshotInfoPath
    screenshot_animation_interval_seconds = 2
    screenshot_hash_change_policy = "supporting-only-not-required-for-world-anchor-validation"
    screenshot_pre_capture_wait_seconds = [Math]::Max(1, [Math]::Min($RunSeconds, 4))
    panel_shell_property_forced_visible = $false
    panel_shell_property_forced_hidden = $true
    hand_billboard_flock_property_forced_enabled = $false
    hand_billboard_flock_property_disabled_for_icosphere = ($SurfaceTargetId -eq "icosphere")
}

try {
    $state = Invoke-AdbCommand -Name "adb get-state" -Arguments @("get-state")
    $summary.device_state = $state.output.Trim()
    if ($summary.device_state -ne "device") {
        throw "ADB target is not ready: $($summary.device_state)"
    }
    Save-Text -Path (Join-Path $OutDir "adb-device-state.txt") -Text $summary.device_state
    $summary.device_model = (Invoke-AdbCommand -Name "device model" -Arguments @("shell", "getprop", "ro.product.model")).output.Trim()
    $summary.device_build = (Invoke-AdbCommand -Name "device build" -Arguments @("shell", "getprop", "ro.build.version.incremental")).output.Trim()

    if (-not $SkipInstall) {
        $install = Invoke-AdbCommand -Name "install Spatial SDK APK" -Arguments @("install", "-r", "-d", "-g", (Resolve-Path -LiteralPath $resolvedApk).Path)
        Save-Text -Path (Join-Path $OutDir "install.txt") -Text $install.output
    }

    Invoke-AdbCommand -Name "force-stop Spatial SDK app" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
    Set-ParticleVisualBaselineProperties
    if ($ClearLogcat) {
        Invoke-AdbCommand -Name "clear logcat" -Arguments @("logcat", "-c") | Out-Null
    }
    $clearMarkers = Invoke-AdbCommand `
        -Name "clear app-private marker files" `
        -Arguments @(
            "shell",
            "run-as",
            $PackageName,
            "rm",
            "-f",
            "files/spatial_camera_panel_activity_markers.log",
            "files/spatial_camera_panel_native_markers.log"
        ) `
        -AllowFailure
    Save-Text -Path (Join-Path $OutDir "clear-app-private-markers.txt") -Text $clearMarkers.output

    $activation = Invoke-SurfaceTargetActivation
    $summary.surface_target_activation_exit_code = $activation.exit_code
    Start-Sleep -Milliseconds 700
    if (-not $script:EffectiveSkipParticleControlBoost) {
        $boost = Invoke-ParticleControlBoost
        $summary.particle_control_boost_exit_code = $boost.exit_code
    }
    if ($ExerciseParticleRecenter) {
        Start-Sleep -Milliseconds 350
        $recenter = Invoke-ParticleRecenter
        $summary.particle_recenter_exit_code = $recenter.exit_code
    }
    Start-Sleep -Seconds $summary.screenshot_pre_capture_wait_seconds

    $pidResult = Invoke-AdbCommand -Name "Spatial SDK app pid" -Arguments @("shell", "pidof", $PackageName) -AllowFailure
    $targetPid = (($pidResult.output -split "\s+") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($targetPid)) {
        throw "Spatial SDK process id was not available after visual smoke launch; refusing unscoped runtime evidence."
    }
    $summary.pid = $targetPid
    Save-Text -Path (Join-Path $OutDir "pid.txt") -Text $targetPid

    Invoke-AdbCommand -Name "capture initial screenshot" -Arguments @("shell", "screencap", "-p", $remoteScreenshotInitialPath) -AllowFailure | Out-Null
    Invoke-AdbCommand -Name "pull initial screenshot" -Arguments @("pull", $remoteScreenshotInitialPath, $screenshotInitialPath) -AllowFailure | Out-Null
    Invoke-AdbCommand -Name "remove remote initial screenshot" -Arguments @("shell", "rm", $remoteScreenshotInitialPath) -AllowFailure | Out-Null
    $screenshotInitialInfo = Measure-ScreenshotDimensions -Path $screenshotInitialPath
    Save-Text -Path $screenshotInitialInfoPath -Text ($screenshotInitialInfo | ConvertTo-Json -Depth 4)
    $summary.screenshot_initial_sha256 = if ($screenshotInitialInfo.exists) { Get-FileSha256 -Path $screenshotInitialPath } else { "" }
    if ([bool]$summary.panel_distance_motion_requested) {
        $panelDistanceMotionResults = @(Invoke-PanelDistanceMotionSequence)
        $summary.panel_distance_motion_exercised = $true
        $summary.panel_distance_motion_exit_codes = @($panelDistanceMotionResults | ForEach-Object { $_.exit_code })
        $summary.panel_distance_motion_all_setprops_succeeded =
            @($panelDistanceMotionResults | Where-Object { $_.name -like "set particle layer target distance*" -and $_.exit_code -ne 0 }).Count -eq 0
        $summary.panel_distance_motion_all_commands_succeeded =
            @($panelDistanceMotionResults | Where-Object { $_.exit_code -ne 0 }).Count -eq 0
    } else {
        $summary.panel_distance_motion_exercised = $false
        $summary.panel_distance_motion_exit_codes = @()
        $summary.panel_distance_motion_all_setprops_succeeded = $false
        $summary.panel_distance_motion_all_commands_succeeded = $false
    }
    if ([bool]$summary.panel_view_yaw_motion_requested) {
        $panelViewYawMotionResults = @(Invoke-PanelViewYawMotionSequence)
        $summary.panel_view_yaw_motion_exercised = $true
        $summary.panel_view_yaw_motion_exit_codes = @($panelViewYawMotionResults | ForEach-Object { $_.exit_code })
        $summary.panel_view_yaw_motion_all_setprops_succeeded =
            @($panelViewYawMotionResults | Where-Object { $_.name -like "set particle layer view yaw*" -and $_.exit_code -ne 0 }).Count -eq 0
        $summary.panel_view_yaw_motion_all_commands_succeeded =
            @($panelViewYawMotionResults | Where-Object { $_.exit_code -ne 0 }).Count -eq 0
    } else {
        $summary.panel_view_yaw_motion_exercised = $false
        $summary.panel_view_yaw_motion_exit_codes = @()
        $summary.panel_view_yaw_motion_all_setprops_succeeded = $false
        $summary.panel_view_yaw_motion_all_commands_succeeded = $false
    }
    if (-not [bool]$summary.panel_distance_motion_requested -and -not [bool]$summary.panel_view_yaw_motion_requested) {
        Start-Sleep -Seconds 2
    }

    Invoke-AdbCommand -Name "capture screenshot" -Arguments @("shell", "screencap", "-p", $remoteScreenshotPath) -AllowFailure | Out-Null
    Invoke-AdbCommand -Name "pull screenshot" -Arguments @("pull", $remoteScreenshotPath, $screenshotPath) -AllowFailure | Out-Null
    Invoke-AdbCommand -Name "remove remote screenshot" -Arguments @("shell", "rm", $remoteScreenshotPath) -AllowFailure | Out-Null
    $screenshotInfo = Measure-ScreenshotDimensions -Path $screenshotPath
    Save-Text -Path $screenshotInfoPath -Text ($screenshotInfo | ConvertTo-Json -Depth 4)
    $summary.screenshot_sha256 = if ($screenshotInfo.exists) { Get-FileSha256 -Path $screenshotPath } else { "" }

    $pidLogcat = (Invoke-AdbCommand -Name "dump pid-scoped logcat" -Arguments @("logcat", "-d", "-v", "time", "--pid", $targetPid)).output
    Save-Text -Path $pidLogcatPath -Text $pidLogcat
    $allLogcat = (Invoke-AdbCommand -Name "dump full logcat" -Arguments @("logcat", "-d", "-v", "time")).output
    Save-Text -Path $allLogcatPath -Text $allLogcat
    $summary.app_private_activity_markers = Save-AppPrivateFile -RemotePath "files/spatial_camera_panel_activity_markers.log" -LocalPath $activityMarkersPath
    $summary.app_private_native_markers = Save-AppPrivateFile -RemotePath "files/spatial_camera_panel_native_markers.log" -LocalPath $nativeMarkersPath
    $activityMarkersText = if (Test-Path -LiteralPath $activityMarkersPath) { Get-Content -Raw -LiteralPath $activityMarkersPath } else { "" }
    $nativeMarkersText = if (Test-Path -LiteralPath $nativeMarkersPath) { Get-Content -Raw -LiteralPath $nativeMarkersPath } else { "" }
    $markerText = $pidLogcat + "`n" + $activityMarkersText + "`n" + $nativeMarkersText
    $tagLines = @([regex]::Split($markerText, "`r?`n") | Where-Object { $_ -match "$MarkerPrefix|RQSpatialCameraPanel|RQSpatialCameraPanelNative|privateSurfaceParticle" })
    [System.IO.File]::WriteAllLines($tagLogcatPath, [string[]]$tagLines)

    $summary.surface_target_activation_started = Test-LineContainsAll $markerText @(
        "status=surface-target-activation-start",
        "surfaceTargetId=$SurfaceTargetId"
    )
    $summary.surface_target_activated = Test-LineContainsAll $markerText @(
        "status=surface-target-activated",
        "surfaceTargetId=$SurfaceTargetId",
        "leftInParticleView=true"
    )
    $summary.left_in_particle_view = Test-TextContains $markerText "leftInParticleView=true"
    $summary.render_loop_ready = Test-TextContains $markerText "status=render-loop-ready"
    $summary.first_frame_presented = Test-TextContains $markerText "status=first-frame-presented"
    $summary.private_ecs_feature_loaded = Test-LineContainsAll $markerText @(
        "channel=spatial-private-feature-loader",
        "status=loaded",
        "privateFeatureLoaded=true"
    )
    $summary.private_ecs_pool_created = Test-LineContainsAll $markerText @(
        "channel=spatial-viscereality-icosphere-ecs",
        "status=pool-created",
        "visualParticleCount=2562",
        "privateViscerealityEcsCarrier=true",
        "privateViscerealityEcsGeometryKind=icosphere",
        "privateViscerealityEcsIcosphereRecursionLevel=4",
        "privateViscerealityEcsGeometrySource=akd-Ico4-x-axis-rotation-30deg",
        "privateViscerealityEcsFibonacciFallback=false",
        "privateViscerealityEcsParticleTextureMode=static-reference-soft-disc",
        "privateViscerealityEcsParticleTextureResource=static_reference_soft_disc",
        "privateViscerealityEcsParticleTextureResourceFound=true",
        "privateViscerealityEcsRendererExclusivity=spatial-ecs-only",
        "nativeSurfaceParticleLayerExpectedEnabled=false"
    )
    $summary.private_ecs_world_space_updated = Test-LineContainsAll $markerText @(
        "channel=spatial-viscereality-icosphere-ecs",
        "status=world-space-updated",
        "privateViscerealityEcsRecenterChangesCoordinateMapping=false",
        "privateViscerealityEcsCameraRealignEachFrame=false",
        "directWorldSpace=true",
        "projectionPlane=false"
    )
    $summary.native_surface_particle_render_loop_absent = -not (Test-LineContainsAll $markerText @(
        "channel=native-surface-particle-layer",
        "status=render-loop-ready"
    ))
    $summary.private_ecs_native_surface_particle_layer_suppressed =
        (-not [bool]$UsePrivateEcsIcosphere) -or (
            [bool]$summary.native_surface_particle_layer_requested_enabled -eq $false -and
            [bool]$summary.native_surface_particle_render_loop_absent
        )
    $summary.private_main_tracer_ready_compact = Test-TextContains $markerText "status=private-main-tracer-ready-compact"
    $summary.private_main_tracer_presented_compact = Test-TextContains $markerText "status=private-main-tracer-presented-compact"
    $summary.private_main_tracer_ready_counts_compact = Test-TextContains $markerText "status=private-main-tracer-ready-counts-compact"
    $summary.private_main_tracer_presented_counts_compact = Test-TextContains $markerText "status=private-main-tracer-presented-counts-compact"
    $summary.private_profile_metadata_present = Test-TextContains $markerText "privateSurfaceParticleProfileIdHash="
    $summary.private_payload_staged = Test-LineContainsAll $markerText @(
        "status=private-main-tracer-ready-compact",
        "privateSurfaceParticleStagedPayloadReady=true"
    )
    $summary.private_renderer_main_draw = Test-LineContainsAll $markerText @(
        "status=private-main-tracer-ready-compact",
        "privateSurfaceParticleRendererMode=main-draw-only",
        "privateSurfaceParticleVisible=true"
    )
    $summary.private_renderer_no_public_fallback = Test-LineContainsAll $markerText @(
        "status=private-surface-particle-main-draw-only",
        "privateSurfaceParticleRendererSelection=private-main-draw-only-no-public-fallback",
        "privateSurfaceParticlePublicFallbackActive=false"
    )
    $summary.private_payload_visibility_private_main_draw_only = Test-LineContainsAll $markerText @(
        "status=first-frame-presented",
        "privatePayloadVisibility=private-main-draw-only",
        "privateSurfaceParticlePublicFallbackActive=false"
    )
    $summary.private_overlay_public_fallback_absent = -not (Test-TextContains $markerText "private-main-draw-overlay-public-fallback")
    $summary.native_hand_anchor_renderer_absent_for_icosphere =
        ($SurfaceTargetId -ne "icosphere") -or
        (-not (Test-TextContains $markerText "surfaceParticleRendererMode=public-hand-anchor-proof"))
    $summary.hand_billboard_flock_icosphere_suppressed =
        ($SurfaceTargetId -ne "icosphere") -or
        [bool]$summary.hand_billboard_flock_property_disabled_for_icosphere -or
        (Test-LineContainsAll $markerText @(
            "channel=spatial-hand-billboard-flock",
            "status=disabled",
            "reason=icosphere-surface-target",
            "surfaceTargetId=icosphere",
            "icosphereSuppressed=true"
        ))
    $summary.hand_billboard_flock_world_space_absent = -not (Test-TextContains $markerText "channel=spatial-hand-billboard-flock status=world-space-updated")
    $summary.private_world_anchor_fixed_sim_transform = Test-LineContainsAll $markerText @(
        "status=private-world-anchor-captured",
        "privateSurfaceParticleWorldAnchorMode=spatial-sdk-fixed-sim-world-transform",
        "privateSurfaceParticleWorldAnchorComputeSource=spatial-sdk-world-coordinates",
        "privateSurfaceParticleWorldAnchorCenterSource=configured-spatial-world-coordinate",
        "privateSurfaceParticleWorldAnchorBasis=spatial-world-canonical-axes",
        "privateSurfaceParticleWorldAnchorScaleSource=fixed-sim-meter-radius",
        "privateSurfaceParticleSimRegistration=sim-space-fixed-in-spatial-sdk-world-space",
        "privateSurfaceParticleSimTransform=spatial-world-from-sim-fixed-configured-origin-basis-meter-scale",
        "privateSurfaceParticleWorldAnchorStable=true"
    )
    $summary.particle_recenter_command_requested = Test-LineContainsAll $markerText @(
        "status=particle-recenter-requested",
        "inputSource=remote-ui-command-particle-recenter",
        "controllerInputRequired=false",
        "privateSurfaceParticleWorldAnchorCenterSource=current-spatial-sdk-viewer-world-coordinate",
        "privateSurfaceParticleRecenterChangesCoordinateMapping=false"
    )
    $summary.private_world_anchor_recentered_to_viewer = Test-LineContainsAll $markerText @(
        "status=private-world-anchor-recentered",
        "privateSurfaceParticleWorldAnchorRecenterAccepted=true",
        "privateSurfaceParticleWorldAnchorCenterSource=current-spatial-sdk-viewer-world-coordinate",
        "privateSurfaceParticleWorldAnchorBasis=spatial-world-canonical-axes",
        "privateSurfaceParticleRecenterChangesOnlySphereCenter=true"
    )
    $summary.private_world_anchor_capture_skipped = Test-LineContainsAll $markerText @(
        "status=private-world-anchor-capture-skipped",
        "privateSurfaceParticleWorldAnchorStable=false"
    )
    $summary.private_world_anchor_captured_floor_space = $summary.private_world_anchor_fixed_sim_transform
    $summary.private_openxr_world_anchor_mapper_ready = Test-LineContainsAll $markerText @(
        "status=private-openxr-world-anchor-mapper-ready",
        "privateSurfaceParticleWorldAnchorMapper=openxr-local-floor-view-panel-mapping",
        "privateSurfaceParticleOpenXrViewMapperReady=true",
        "privateSurfaceParticlePublicFallbackActive=false"
    )
    $summary.private_openxr_world_anchor_captured = Test-LineContainsAll $markerText @(
        "status=private-openxr-world-anchor-captured",
        "privateSurfaceParticleWorldAnchorMode=openxr-local-floor-fixed-anchor",
        "privateSurfaceParticleWorldAnchorEligibility=floor-space-panel-pose+openxr-view",
        "privateSurfaceParticleOpenXrWorldAnchorStable=true",
        "privateSurfaceParticlePublicFallbackActive=false"
    )
    $summary.private_openxr_world_anchor_mapped = Test-LineContainsAll $markerText @(
        "status=private-openxr-world-anchor-mapped",
        "privateSurfaceParticleWorldAnchorMode=openxr-local-floor-fixed-anchor",
        "privateSurfaceParticleWorldAnchorMapped=true",
        "privateSurfaceParticleOpenXrWorldAnchorStable=true",
        "privateSurfaceParticlePublicFallbackActive=false"
    )
    $anchorSkipIndex = Get-LineIndexContainingAll $markerText @(
        "status=private-world-anchor-capture-skipped",
        "privateSurfaceParticleWorldAnchorEligibility=requires-floor-space-panel-pose"
    )
    $anchorCaptureIndex = Get-LineIndexContainingAll $markerText @(
        "status=private-world-anchor-captured",
        "privateSurfaceParticleWorldAnchorMode=spatial-sdk-fixed-sim-world-transform"
    )
    $summary.private_world_anchor_skip_line_index = $anchorSkipIndex
    $summary.private_world_anchor_capture_line_index = $anchorCaptureIndex
    $summary.private_world_anchor_skipped_before_capture = ($anchorSkipIndex -ge 0) -and ($anchorCaptureIndex -gt $anchorSkipIndex)
    $summary.private_tracers_active = Test-LineContainsAll $markerText @(
        "status=private-main-tracer-ready-compact",
        "privateSurfaceParticleTracersActive=true"
    )
    $summary.private_descriptor_sets_ready = Test-LineContainsAll $markerText @(
        "status=private-main-tracer-ready-compact",
        "privateSurfaceParticlePrivateDescriptorSetsReady=true"
    )
    $summary.private_main_compute_recorded = Test-LineContainsAll $markerText @(
        "status=private-main-tracer-presented-counts-compact",
        "privateSurfaceParticleMainComputeDispatchRecorded=true"
    )
    $summary.private_main_draw_recorded = Test-LineContainsAll $markerText @(
        "status=private-main-tracer-presented-counts-compact",
        "privateSurfaceParticleMainDrawRecorded=true"
    )
    $summary.private_main_draw_spatial_world_direct_mapping =
        Test-TextContains $markerText "status=first-frame-presented"
    foreach ($requiredMarker in @(
        "privateSurfaceParticleOpenXrViewDrawAuthority=false",
        "privateSurfaceParticleMainDrawProjection=spatial-sdk-world-explicit-viewer-camera-to-panel-plane",
        "projectionPlaneRollAuthority=spatial-world-up",
        "projectionPlaneRollFollowsHeadset=false",
        "overscanMode=none",
        "projectionSurfaceScaleX=1.0000",
        "projectionSurfaceScaleY=1.0000",
        "panelDimensionsMatchProjection=true",
        "overscanCompensated=not-required",
        "horizontalProjectionMode=wide-fov",
        "projectionHorizontalScale=1.3500",
        "privateSurfaceParticleMainDrawCameraBasisSource=Scene.getViewerPose-position+forward-x-mirror-corrected-roll-stable",
        "privateSurfaceParticleCarrierPanelForwardSource=spatial-sdk-presentation-plane-only",
        "privateSurfaceParticleEyePoseSource=Scene.getViewerPose+Scene.getEyeOffsets",
        "privateSurfaceParticlePanelPoseSource=Scene.getViewerPose-derived-panel-plane",
        "privateSurfaceParticlePanelDefinesEye=false",
        "privateSurfaceParticleWorldAnchorCenterSource=configured-spatial-world-coordinate",
        "privateSurfaceParticleWorldAnchorBasis=spatial-world-canonical-axes",
        "privateSurfaceParticleWorldAnchorScaleSource=fixed-sim-meter-radius",
        "privateSurfaceParticleSimRegistration=sim-space-fixed-in-spatial-sdk-world-space",
        "privateSurfaceParticleSimTransform=spatial-world-from-sim-fixed-configured-origin-basis-meter-scale",
        "privateSurfaceParticleCameraRealignEachFrame=false",
        "privateSurfaceParticleOffAxisStereoProjection=true",
        "privateSurfaceParticleMainDrawTargetDistanceMaxMeters=2.00"
    )) {
        $summary.private_main_draw_spatial_world_direct_mapping =
            $summary.private_main_draw_spatial_world_direct_mapping -and
            (Test-TextContains $markerText $requiredMarker)
    }
    $summary.private_main_draw_explicit_eye_mapping =
        $summary.private_main_draw_spatial_world_direct_mapping -and
        (Test-LineContainsAll $markerText @(
            "status=viewer-eye-pose-updated",
            "privateSurfaceParticleEyePoseSource=Scene.getViewerPose+Scene.getEyeOffsets",
            "privateSurfaceParticlePanelDefinesEye=false",
            "privateSurfaceParticleOpenXrViewDrawAuthority=false",
            "privateSurfaceParticleSceneViewerFallbackAvailable=true",
            "explicitEyePoseValid=true"
        ))
    $summary.private_openxr_diagnostic_registration_captured = Test-LineContainsAll $markerText @(
        "status=private-openxr-diagnostic-registration-captured",
        "privateSurfaceParticleOpenXrDiagnosticProjection=registered-openxr-eye-through-start-basis",
        "privateSurfaceParticleOpenXrDiagnosticDrawAuthority=diagnostic-only"
    )
    $summary.private_openxr_diagnostic_projection_updated = Test-LineContainsAll $markerText @(
        "status=private-openxr-diagnostic-projection-updated",
        "privateSurfaceParticleOpenXrDiagnosticProjection=registered-openxr-eye-through-start-basis",
        "privateSurfaceParticleOpenXrDiagnosticDrawAuthority=diagnostic-only",
        "registeredPanelCenterM="
    )
    $worldAnchorMotionSamples = @(Get-WorldAnchorMotionSamples -Text $markerText)
    $summary.private_world_anchor_mapped_sample_count = $worldAnchorMotionSamples.Count
    $summary.private_world_anchor_panel_motion_meters = Get-MaxVectorSpan `
        -Samples $worldAnchorMotionSamples `
        -PropertyName "panel_center"
    $summary.private_world_anchor_mapped_motion_meters = Get-MaxVectorSpan `
        -Samples $worldAnchorMotionSamples `
        -PropertyName "mapped_center"
    $sceneFixedWorldAnchorMotionSamplesAll = @(Get-SceneFixedWorldAnchorMotionSamples -Text $markerText)
    $sceneFixedWorldAnchorEpochIndex = Get-LatestSceneFixedWorldAnchorEpochIndex -Text $markerText
    $sceneFixedWorldAnchorMotionSamples = @(
        $sceneFixedWorldAnchorMotionSamplesAll |
            Where-Object { [int]$_.text_index -gt $sceneFixedWorldAnchorEpochIndex }
    )
    if ($sceneFixedWorldAnchorMotionSamples.Count -lt 3) {
        $sceneFixedWorldAnchorMotionSamples = $sceneFixedWorldAnchorMotionSamplesAll
    }
    $summary.private_scene_fixed_world_anchor_total_sample_count = $sceneFixedWorldAnchorMotionSamplesAll.Count
    $summary.private_scene_fixed_world_anchor_epoch_start_index = $sceneFixedWorldAnchorEpochIndex
    $summary.private_scene_fixed_world_anchor_sample_count = $sceneFixedWorldAnchorMotionSamples.Count
    $summary.private_scene_fixed_world_anchor_panel_motion_meters = Get-MaxVectorSpan `
        -Samples $sceneFixedWorldAnchorMotionSamples `
        -PropertyName "panel_center"
    $summary.private_scene_fixed_world_anchor_panel_forward_motion = Get-MaxVectorSpan `
        -Samples $sceneFixedWorldAnchorMotionSamples `
        -PropertyName "panel_forward"
    $summary.private_scene_fixed_world_anchor_motion_meters = Get-MaxVectorSpan `
        -Samples $sceneFixedWorldAnchorMotionSamples `
        -PropertyName "world_anchor_center"
    $summary.private_scene_fixed_world_anchor_target_distance_span_meters = Get-MaxScalarSpan `
        -Samples $sceneFixedWorldAnchorMotionSamples `
        -PropertyName "panel_target_distance_meters"
    $summary.private_world_anchor_motion_supporting_evidence = `
        ([int]$summary.private_world_anchor_mapped_sample_count -ge 2)
    $summary.private_world_anchor_motion_requirement_met = `
        (-not $RequireWorldAnchorMotion) -or (
            [int]$summary.private_world_anchor_mapped_sample_count -ge 2 -and
            [double]$summary.private_world_anchor_panel_motion_meters -ge $MinimumWorldAnchorPanelMotionMeters -and
            [double]$summary.private_world_anchor_mapped_motion_meters -ge $MinimumWorldAnchorMappedMotionMeters
        )
    $summary.private_world_anchor_panel_distance_motion_requirement_met = `
        (-not $RequireWorldAnchorStabilityDuringPanelMotion) -or (
            [bool]$summary.panel_distance_motion_exercised -and
            [bool]$summary.panel_distance_motion_all_commands_succeeded -and
            [int]$summary.private_scene_fixed_world_anchor_sample_count -ge 3 -and
            [double]$summary.private_scene_fixed_world_anchor_panel_motion_meters -ge $MinimumWorldAnchorPanelMotionMeters -and
            [double]$summary.private_scene_fixed_world_anchor_target_distance_span_meters -ge ([Math]::Abs($PanelDistanceMotionFarMeters - $PanelDistanceMotionNearMeters) * 0.5)
        )
    $summary.private_world_anchor_panel_distance_stability_requirement_met = `
        (-not $RequireWorldAnchorStabilityDuringPanelMotion) -or (
            [bool]$summary.private_world_anchor_panel_distance_motion_requirement_met -and
            [double]$summary.private_scene_fixed_world_anchor_motion_meters -le $MaximumWorldAnchorMappedDriftMeters
        )
    $summary.private_world_anchor_panel_view_yaw_motion_requirement_met = `
        (-not $RequireWorldAnchorStabilityDuringPanelViewYawMotion) -or (
            [bool]$summary.panel_view_yaw_motion_exercised -and
            [bool]$summary.panel_view_yaw_motion_all_commands_succeeded -and
            [int]$summary.private_scene_fixed_world_anchor_sample_count -ge 3 -and
            [double]$summary.private_scene_fixed_world_anchor_panel_forward_motion -ge $MinimumWorldAnchorPanelForwardMotion
        )
    $summary.private_world_anchor_panel_view_yaw_stability_requirement_met = `
        (-not $RequireWorldAnchorStabilityDuringPanelViewYawMotion) -or (
            [bool]$summary.private_world_anchor_panel_view_yaw_motion_requirement_met -and
            [double]$summary.private_scene_fixed_world_anchor_motion_meters -le $MaximumWorldAnchorMappedDriftMeters
        )
    $summary.private_main_draw_particle_count = Get-FirstRegexInt `
        -Text $markerText `
        -Pattern "status=private-main-tracer-presented-counts-compact[^\r\n]*privateSurfaceParticleMainDrawParticleCount=(\d+)"
    $summary.private_main_draw_tracer_count = Get-FirstRegexInt `
        -Text $markerText `
        -Pattern "status=private-main-tracer-presented-counts-compact[^\r\n]*privateSurfaceParticleMainDrawTracerDrawCount=(\d+)"
    $summary.private_main_draw_particle_count_nonzero = [int]$summary.private_main_draw_particle_count -gt 0
    $summary.private_main_draw_tracer_count_nonzero = [int]$summary.private_main_draw_tracer_count -gt 0
    $summary.particle_controls_submitted = Test-LineContainsAll $markerText @(
        "status=parameters-submitted",
        "source=remote-ui-command-particle-controls"
    )
    $summary.parameters_updated = Test-TextContains $markerText "status=parameters-updated"
    $summary.screenshot_captured = [bool]$screenshotInfo.exists
    $summary.screenshot_initial_captured = [bool]$screenshotInitialInfo.exists
    $summary.screenshot_initial_dimensions_valid = ([int]$screenshotInitialInfo.width -gt 0) -and ([int]$screenshotInitialInfo.height -gt 0)
    $summary.screenshot_dimensions_valid = ([int]$screenshotInfo.width -gt 0) -and ([int]$screenshotInfo.height -gt 0)
    $summary.screenshot_initial_width = [int]$screenshotInitialInfo.width
    $summary.screenshot_initial_height = [int]$screenshotInitialInfo.height
    $summary.screenshot_width = [int]$screenshotInfo.width
    $summary.screenshot_height = [int]$screenshotInfo.height
    $summary.screenshot_changed_between_captures = (-not [string]::IsNullOrWhiteSpace($summary.screenshot_initial_sha256)) -and ($summary.screenshot_initial_sha256 -ne $summary.screenshot_sha256)
    $summary.android_runtime_matches = ([regex]::Matches($pidLogcat, "AndroidRuntime")).Count
    $summary.fatal_matches = ([regex]::Matches($pidLogcat, "FATAL")).Count
    $summary.render_failed_matches = ([regex]::Matches($pidLogcat, "render-failed")).Count

    if ($UsePrivateEcsIcosphere) {
        $requiredFlags = @(
            "surface_target_activation_started",
            "surface_target_activated",
            "left_in_particle_view",
            "private_ecs_feature_loaded",
            "private_ecs_pool_created",
            "private_ecs_world_space_updated",
            "native_surface_particle_render_loop_absent",
            "private_ecs_native_surface_particle_layer_suppressed",
            "hand_billboard_flock_icosphere_suppressed",
            "hand_billboard_flock_world_space_absent",
            "screenshot_initial_captured",
            "screenshot_initial_dimensions_valid",
            "screenshot_captured",
            "screenshot_dimensions_valid"
        )
    } else {
        $requiredFlags = @(
            "surface_target_activation_started",
            "surface_target_activated",
            "left_in_particle_view",
            "render_loop_ready",
            "first_frame_presented",
            "private_main_tracer_ready_compact",
            "private_main_tracer_presented_compact",
            "private_main_tracer_ready_counts_compact",
            "private_main_tracer_presented_counts_compact",
            "private_profile_metadata_present",
            "private_payload_staged",
            "private_renderer_main_draw",
            "private_renderer_no_public_fallback",
            "private_payload_visibility_private_main_draw_only",
            "private_overlay_public_fallback_absent",
            "native_hand_anchor_renderer_absent_for_icosphere",
            "hand_billboard_flock_icosphere_suppressed",
            "hand_billboard_flock_world_space_absent",
            "private_world_anchor_fixed_sim_transform",
            "private_openxr_world_anchor_mapper_ready",
            "private_openxr_world_anchor_captured",
            "private_openxr_world_anchor_mapped",
            "private_tracers_active",
            "private_descriptor_sets_ready",
            "private_main_compute_recorded",
            "private_main_draw_recorded",
            "private_main_draw_spatial_world_direct_mapping",
            "private_main_draw_explicit_eye_mapping",
            "private_main_draw_particle_count_nonzero",
            "private_main_draw_tracer_count_nonzero",
            "screenshot_initial_captured",
            "screenshot_initial_dimensions_valid",
            "screenshot_captured",
            "screenshot_dimensions_valid"
        )
    }
    if (-not $script:EffectiveSkipParticleControlBoost) {
        $requiredFlags += @("particle_controls_submitted", "parameters_updated")
    }
    if ($ExerciseParticleRecenter) {
        $requiredFlags += @(
            "particle_recenter_command_requested",
            "private_world_anchor_recentered_to_viewer"
        )
    }
    if ($RequireWorldAnchorMotion) {
        $requiredFlags += @("private_world_anchor_motion_requirement_met")
    }
    if ($RequireWorldAnchorStabilityDuringPanelMotion) {
        $requiredFlags += @(
            "panel_distance_motion_all_setprops_succeeded",
            "panel_distance_motion_all_commands_succeeded",
            "private_world_anchor_panel_distance_motion_requirement_met",
            "private_world_anchor_panel_distance_stability_requirement_met"
        )
    }
    if ($RequireWorldAnchorStabilityDuringPanelViewYawMotion) {
        $requiredFlags += @(
            "panel_view_yaw_motion_all_setprops_succeeded",
            "panel_view_yaw_motion_all_commands_succeeded",
            "private_world_anchor_panel_view_yaw_motion_requirement_met",
            "private_world_anchor_panel_view_yaw_stability_requirement_met"
        )
    }
    if (-not $AllowMissingMarkers) {
        foreach ($flag in $requiredFlags) {
            Assert-SummaryFlag -Summary $summary -Name $flag
        }
        if ([int]$summary.android_runtime_matches -ne 0 -or [int]$summary.fatal_matches -ne 0 -or [int]$summary.render_failed_matches -ne 0) {
            throw "Spatial Camera Panel particle-visual smoke saw failure markers: AndroidRuntime=$($summary.android_runtime_matches) FATAL=$($summary.fatal_matches) render-failed=$($summary.render_failed_matches)"
        }
    }

    $summary.status = if ($AllowMissingMarkers) { "completed" } else { "passed" }
} catch {
    $summary.status = "failed"
    $summary.error = $_.Exception.Message
    throw
} finally {
    if ($StopAfterRun) {
        try {
            $stop = Invoke-AdbCommand -Name "stop Spatial SDK app" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure
            Save-Text -Path (Join-Path $OutDir "stop.txt") -Text $stop.output
        } catch {
            $summary.stop_after_run_error = $_.Exception.Message
        }
    }
    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $summaryPath
}

Write-Output "Spatial Camera Panel particle-visual smoke evidence: $summaryPath"
Write-Output "APK_SHA256=$apkSha256"
Write-Output "OUT_DIR=$((Resolve-Path -LiteralPath $OutDir).Path)"
