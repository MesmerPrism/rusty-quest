param(
    [string]$RepoRoot,
    [string]$ApkPath = "target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk",
    [string]$OutDir = "",
    [int]$RunSeconds = 12,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.spatial_camera_panel",
    [string]$Activity = "io.github.mesmerprism.rustyquest.spatial_camera_panel/.SpatialCameraPanelActivity",
    [int]$ReaderMaxImages = 4,
    [string]$VideoPath = $env:RUSTY_QUEST_SPATIAL_VIDEO_PATH,
    [string]$VideoSourcePath = $env:RUSTY_QUEST_SPATIAL_VIDEO_SOURCE_PATH,
    [string]$VideoDestinationRelativePath = "v.mp4",
    [int]$VideoWidth = 3840,
    [int]$VideoHeight = 1920,
    [int]$VideoMaxImages = 3,
    [int]$VideoFpsCap = 30,
    [string]$VideoStereoLayout = "side-by-side-left-right",
    [double]$VideoOpacity = 1.0,
    [bool]$VideoLooping = $true,
    [string]$DepthLayerPolicy = $env:RUSTY_QUEST_SPATIAL_DEPTH_LAYER_POLICY,
    [string]$ProjectionCarrier = $env:RUSTY_QUEST_SPATIAL_PROJECTION_CARRIER,
    [string]$AssetMeshUri = $env:RUSTY_QUEST_SPATIAL_ASSET_MODEL_URI,
    [string]$AssetSourcePath = $env:RUSTY_QUEST_SPATIAL_ASSET_MODEL_SOURCE_PATH,
    [string]$AssetConvertedMeshPath = $env:RUSTY_QUEST_SPATIAL_ASSET_MODEL_CONVERTED_MESH_PATH,
    [string]$AssetDestinationRelativePath = "spatial-assets/model.glb",
    [string]$AssetSourceFormat = $env:RUSTY_QUEST_SPATIAL_ASSET_MODEL_SOURCE_FORMAT,
    [string]$AssetLabel = "staged-asset",
    [string]$AssetPositionM = "-0.55;1.15;-1.35",
    [string]$AssetRotationDegrees = "0.0;180.0;0.0",
    [double]$AssetScale = 0.25,
    [bool]$AssetGrabbable = $true,
    [switch]$EnableVirtualRoom,
    [switch]$EnableSkybox,
    [ValidateSet("", "none", "sample", "custom")]
    [string]$SkyboxMode = "",
    [switch]$VideoOnly,
    [switch]$SkipInstall,
    [switch]$SkipPermissionPregrant,
    [switch]$ClearLogcat,
    [switch]$StopAfterRun,
    [switch]$AllowMissingMarkers,
    [switch]$RequirePublicMultiStackProjection,
    [switch]$RequireSpatialVideoProjection,
    [switch]$RequireSpatialAssetModel,
    [switch]$RequireSpatialVirtualRoom,
    [switch]$SyntheticVisualProbe,
    [int]$MinimumSyntheticRedPixels = 1000,
    [int]$MinimumSyntheticGreenPixels = 1000,
    [double]$MinimumSyntheticTargetPixelRatio = 0.01,
    [switch]$SkipForceStopKnownXrPackages
)

$ErrorActionPreference = "Stop"

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
        [Parameter(Mandatory=$true)][AllowEmptyString()][string[]]$Arguments,
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

function Invoke-CheckedPowershell {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string[]]$Arguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & powershell @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output -join "`n")
    if ($exitCode -ne 0) {
        throw "$Name failed with exit code $exitCode`n$text"
    }
    return $text
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

function Test-TextContains {
    param(
        [string]$Text,
        [string]$Needle
    )
    return $Text.Contains($Needle)
}

function Assert-SummaryFlag {
    param(
        [System.Collections.IDictionary]$Summary,
        [string]$Name
    )

    if (-not [bool]$Summary[$Name]) {
        throw "Spatial Camera Panel camera_hwb_projection_smoke evidence missing required flag: $Name"
    }
}

function Test-RegexAny {
    param(
        [AllowNull()][string]$Text,
        [Parameter(Mandatory=$true)][string[]]$Patterns
    )
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }
    foreach ($pattern in $Patterns) {
        if ($Text -match $pattern) {
            return $true
        }
    }
    return $false
}

function Get-SpatialForegroundProof {
    param(
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][string]$Activity,
        [AllowNull()][string]$TargetPid,
        [AllowNull()][string]$LaunchText,
        [AllowNull()][string]$WindowText,
        [AllowNull()][string]$ActivityText
    )

    $packagePattern = [regex]::Escape($PackageName)
    $activityClass = $Activity
    if ($Activity.Contains("/")) {
        $activityClass = $Activity.Substring($Activity.IndexOf("/") + 1)
    }
    $activityClassPattern = [regex]::Escape($activityClass)
    $componentPattern = [regex]::Escape($Activity)
    $activityShortPattern = [regex]::Escape("$PackageName/$activityClass")
    $pidText = if ($null -eq $TargetPid) { "" } else { $TargetPid.Trim() }

    $launchComponentMatches = Test-RegexAny `
        -Text $LaunchText `
        -Patterns @($componentPattern, $activityShortPattern)
    $windowFocusMatches = Test-RegexAny `
        -Text $WindowText `
        -Patterns @(
            "mCurrentFocus=.*$packagePattern.*$activityClassPattern",
            "mFocusedApp=.*$packagePattern.*$activityClassPattern",
            "topFocusedDisplay.*$packagePattern.*$activityClassPattern"
        )
    $activityResumedMatches = Test-RegexAny `
        -Text $ActivityText `
        -Patterns @(
            "topResumedActivity=.*$packagePattern.*$activityClassPattern",
            "mResumedActivity=.*$packagePattern.*$activityClassPattern",
            "ResumedActivity:.*$packagePattern.*$activityClassPattern",
            "Hist\s+#0: ActivityRecord\{.*$packagePattern.*$activityClassPattern"
        )
    $processRecordMatches = $false
    if (-not [string]::IsNullOrWhiteSpace($pidText) -and -not [string]::IsNullOrWhiteSpace($ActivityText)) {
        $processRecordMatches =
            $ActivityText.Contains("${pidText}:$PackageName") -or
            ($ActivityText -match "ProcessRecord\{.*\s$pidText`:$packagePattern\b")
    }

    $focusedOrResumed = $windowFocusMatches -or $activityResumedMatches
    $valid =
        (-not [string]::IsNullOrWhiteSpace($pidText)) -and
        $launchComponentMatches -and
        $focusedOrResumed

    return [ordered]@{
        expected_package = $PackageName
        expected_activity = $Activity
        pid = $pidText
        pid_live = -not [string]::IsNullOrWhiteSpace($pidText)
        launch_component_matches = [bool]$launchComponentMatches
        window_focus_matches = [bool]$windowFocusMatches
        activity_resumed_matches = [bool]$activityResumedMatches
        focused_or_resumed = [bool]$focusedOrResumed
        process_record_matches_pid = [bool]$processRecordMatches
        valid = [bool]$valid
    }
}

function Measure-SyntheticSurfacePixels {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [int]$Stride = 4
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return [ordered]@{
            path = $Path
            exists = $false
            sampled_pixels = 0
            red_pixels = 0
            green_pixels = 0
            blue_pixels = 0
            yellow_pixels = 0
            white_pixels = 0
            black_pixels = 0
            synthetic_target_pixels = 0
            synthetic_target_ratio = 0.0
        }
    }

    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new((Resolve-Path -LiteralPath $Path).Path)
    try {
        $strideClamped = [Math]::Max(1, $Stride)
        $sampled = 0
        $red = 0
        $green = 0
        $blue = 0
        $yellow = 0
        $white = 0
        $black = 0
        for ($y = 0; $y -lt $bitmap.Height; $y += $strideClamped) {
            for ($x = 0; $x -lt $bitmap.Width; $x += $strideClamped) {
                $sampled++
                $pixel = $bitmap.GetPixel($x, $y)
                if ($pixel.R -gt 150 -and $pixel.G -lt 95 -and $pixel.B -lt 95) {
                    $red++
                } elseif ($pixel.G -gt 130 -and $pixel.R -lt 110 -and $pixel.B -lt 130) {
                    $green++
                } elseif ($pixel.B -gt 150 -and $pixel.R -lt 110 -and $pixel.G -lt 140) {
                    $blue++
                } elseif ($pixel.R -gt 150 -and $pixel.G -gt 130 -and $pixel.B -lt 110) {
                    $yellow++
                } elseif ($pixel.R -gt 210 -and $pixel.G -gt 210 -and $pixel.B -gt 210) {
                    $white++
                } elseif ($pixel.R -lt 45 -and $pixel.G -lt 45 -and $pixel.B -lt 45) {
                    $black++
                }
            }
        }
        $target = $red + $green + $blue + $yellow
        $ratio = if ($sampled -gt 0) { [Math]::Round($target / [double]$sampled, 6) } else { 0.0 }
        return [ordered]@{
            path = (Resolve-Path -LiteralPath $Path).Path
            exists = $true
            width = $bitmap.Width
            height = $bitmap.Height
            sample_stride = $strideClamped
            sampled_pixels = $sampled
            red_pixels = $red
            green_pixels = $green
            blue_pixels = $blue
            yellow_pixels = $yellow
            white_pixels = $white
            black_pixels = $black
            synthetic_target_pixels = $target
            synthetic_target_ratio = $ratio
        }
    } finally {
        $bitmap.Dispose()
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

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRootPath "local-artifacts\spatial-camera-panel-headset\$stamp-camera-hwb-projection-smoke"
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

$readerMaxImagesClamped = [Math]::Max(3, [Math]::Min(12, $ReaderMaxImages))
$videoPathTrimmed = if ($null -eq $VideoPath) { "" } else { $VideoPath.Trim() }
$videoSourcePathTrimmed = if ($null -eq $VideoSourcePath) { "" } else { $VideoSourcePath.Trim() }
$videoProjectionRequested =
    (-not [string]::IsNullOrWhiteSpace($videoPathTrimmed)) -or
    (-not [string]::IsNullOrWhiteSpace($videoSourcePathTrimmed))
if ($RequireSpatialVideoProjection -and -not $videoProjectionRequested) {
    throw "-RequireSpatialVideoProjection requires -VideoPath, -VideoSourcePath, RUSTY_QUEST_SPATIAL_VIDEO_PATH, or RUSTY_QUEST_SPATIAL_VIDEO_SOURCE_PATH."
}
$videoWidthClamped = [Math]::Max(320, [Math]::Min(4096, $VideoWidth))
$videoHeightClamped = [Math]::Max(240, [Math]::Min(4096, $VideoHeight))
$videoMaxImagesClamped = [Math]::Max(2, [Math]::Min(6, $VideoMaxImages))
$videoFpsCapClamped = [Math]::Max(1, [Math]::Min(90, $VideoFpsCap))
$videoOpacityClamped = [Math]::Max(0.0, [Math]::Min(1.0, $VideoOpacity))
$videoStereoLayoutRaw = $VideoStereoLayout.Trim().ToLowerInvariant().Replace("_", "-")
$videoStereoLayoutToken = if (@("top-bottom", "top-bottom-left-right", "tb", "over-under") -contains $videoStereoLayoutRaw) {
    "top-bottom-left-right"
} else {
    "side-by-side-left-right"
}
$depthLayerPolicyRaw = if ($null -eq $DepthLayerPolicy) { "" } else { $DepthLayerPolicy.Trim().ToLowerInvariant().Replace("_", "-") }
$depthLayerPolicyToken = switch -Regex ($depthLayerPolicyRaw) {
    "^(mono-layer0|mono-left|layer0|left|0)$" { "mono-layer0"; break }
    "^(mono-layer1|mono-right|layer1|right|1)$" { "mono-layer1"; break }
    "^(compare|layer-compare|compare-layers|depth-compare|l0-l1-compare|3)$" { "compare"; break }
    "^(eye-index|per-eye|stereo|stereo-indexed|2)?$" { "eye-index"; break }
    default { throw "Unknown -DepthLayerPolicy '$DepthLayerPolicy'. Use mono-layer0, mono-layer1, eye-index, or compare." }
}
$projectionCarrierRaw = if ($null -eq $ProjectionCarrier) { "" } else { $ProjectionCarrier.Trim().ToLowerInvariant().Replace("_", "-") }
$projectionCarrierToken = switch -Regex ($projectionCarrierRaw) {
    "^(video-surface-panel-scene-object|video-surface-panel|panel-scene-object)$" { "video-surface-panel-scene-object"; break }
    "^(manual-panel-scene-object-custom-mesh|manual-panel-scene-object|custom-mesh-panel|manual-custom-mesh)$" { "manual-panel-scene-object-custom-mesh"; break }
    "^(scenequadlayer-room-object|scenequadlayer|scene-quad-layer|room-object|)$" { "scenequadlayer-room-object"; break }
    default { throw "Unknown -ProjectionCarrier '$ProjectionCarrier'. Use scenequadlayer-room-object, video-surface-panel-scene-object, or manual-panel-scene-object-custom-mesh." }
}
$skyboxModeRaw = if ($null -eq $SkyboxMode) { "" } else { $SkyboxMode.Trim().ToLowerInvariant().Replace("_", "-") }
$effectiveSkyboxMode = switch ($skyboxModeRaw) {
    "" { if ($EnableSkybox) { "sample" } else { "none" } }
    "sample" { "sample" }
    "custom" { "custom" }
    "none" { "none" }
    default { throw "Unknown -SkyboxMode '$SkyboxMode'. Use none, sample, or custom." }
}
$effectiveSkyboxEnabled = $effectiveSkyboxMode -ne "none"
$expectedSkyboxModeMarker = switch ($effectiveSkyboxMode) {
    "sample" { "sample-mesh-uri" }
    "custom" { "custom-scene-mesh" }
    default { "none" }
}
$expectedRoomRenderOrderToken = switch ($projectionCarrierToken) {
    "video-surface-panel-scene-object" { "video-surface-panel-over-virtual-room" }
    "manual-panel-scene-object-custom-mesh" { "manual-custom-mesh-panel-over-virtual-room" }
    default { "projection-layer-over-virtual-room" }
}
$assetMeshUriTrimmed = if ($null -eq $AssetMeshUri) { "" } else { $AssetMeshUri.Trim() }
$assetSourcePathTrimmed = if ($null -eq $AssetSourcePath) { "" } else { $AssetSourcePath.Trim() }
$assetConvertedMeshPathTrimmed = if ($null -eq $AssetConvertedMeshPath) { "" } else { $AssetConvertedMeshPath.Trim() }
$assetSourceFormatTrimmed = if ($null -eq $AssetSourceFormat) { "" } else { $AssetSourceFormat.Trim().ToLowerInvariant() }
$assetLabelTrimmed = if ($null -eq $AssetLabel) { "staged-asset" } else { $AssetLabel.Trim() }
if ([string]::IsNullOrWhiteSpace($assetLabelTrimmed)) {
    $assetLabelTrimmed = "staged-asset"
}
$assetPositionTrimmed = if ($null -eq $AssetPositionM) { "-0.55;1.15;-1.35" } else { $AssetPositionM.Trim() }
$assetRotationTrimmed = if ($null -eq $AssetRotationDegrees) { "0.0;180.0;0.0" } else { $AssetRotationDegrees.Trim() }
$assetScaleClamped = [Math]::Max(0.001, [Math]::Min(10.0, $AssetScale))
$assetModelRequested =
    (-not [string]::IsNullOrWhiteSpace($assetMeshUriTrimmed)) -or
    (-not [string]::IsNullOrWhiteSpace($assetSourcePathTrimmed))
if ($RequireSpatialAssetModel -and -not $assetModelRequested) {
    throw "-RequireSpatialAssetModel requires -AssetMeshUri, -AssetSourcePath, RUSTY_QUEST_SPATIAL_ASSET_MODEL_URI, or RUSTY_QUEST_SPATIAL_ASSET_MODEL_SOURCE_PATH."
}
$apkSha256 = Get-FileSha256 -Path $resolvedApk
$summaryPath = Join-Path $OutDir "evidence-summary.json"
$permissionPregrantPath = Join-Path $OutDir "permission-pregrant.json"
$tagLogcatStreamPath = Join-Path $OutDir "tag-logcat-stream.txt"
$tagLogcatErrorPath = Join-Path $OutDir "tag-logcat-stream.stderr.txt"
$pidLogcatPath = Join-Path $OutDir "pid-logcat.txt"
$allLogcatPath = Join-Path $OutDir "logcat-all.txt"
$windowFocusPath = Join-Path $OutDir "window-focus.txt"
$activityDumpPath = Join-Path $OutDir "activity-activities.txt"
$foregroundProofPath = Join-Path $OutDir "foreground-proof.json"
$screenshotPath = Join-Path $OutDir "screencap.png"
$pixelSummaryPath = Join-Path $OutDir "screenshot-pixel-classification.json"
$remoteScreenshotPath = "/data/local/tmp/rusty-quest-spatial-camera-hwb-projection-smoke.png"

$summary = [ordered]@{
    '$schema' = "rusty.quest.spatial_camera_panel.camera_hwb_projection_smoke.v1"
    wrapper = "Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1"
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    status = "started"
    adb_path = $script:ResolvedAdb
    adb_scope = "device-scoped-adb"
    adb_serial_required = $true
    adb_server_port = $script:ResolvedAdbServerPort
    serial = $Serial
    package = $PackageName
    activity = $Activity
    foreground_validation_required = $true
    foreground_expected_package = $PackageName
    foreground_expected_activity = $Activity
    foreground_proof_path = $foregroundProofPath
    foreground_validation_passed = $false
    apk_path = (Resolve-Path -LiteralPath $resolvedApk).Path
    apk_sha256 = $apkSha256
    out_dir = (Resolve-Path -LiteralPath $OutDir).Path
    run_seconds = [Math]::Max(1, $RunSeconds)
    skipped_install = [bool]$SkipInstall
    skip_permission_pregrant = [bool]$SkipPermissionPregrant
    permission_pregrant_path = $permissionPregrantPath
    spatial_scene_permission_pregrant_requested = (-not [bool]$SkipPermissionPregrant)
    spatial_scene_permission_declared = $false
    spatial_scene_permission_granted = ""
    spatial_scene_data_appop_requested = (-not [bool]$SkipPermissionPregrant)
    spatial_scene_data_appop_mode = ""
    clear_logcat_requested = [bool]$ClearLogcat
    stop_after_run = [bool]$StopAfterRun
    allow_missing_markers = [bool]$AllowMissingMarkers
    require_public_multistack_projection = [bool]$RequirePublicMultiStackProjection
    require_spatial_video_projection = [bool]$RequireSpatialVideoProjection
    spatial_video_only_requested = [bool]$VideoOnly
    reader_max_images = $readerMaxImagesClamped
    spatial_video_projection_requested = $videoProjectionRequested
    spatial_video_projection_path = $videoPathTrimmed
    spatial_video_projection_source_path = $videoSourcePathTrimmed
    spatial_video_projection_stage_path = ""
    spatial_video_projection_path_transport = $(if ([string]::IsNullOrWhiteSpace($videoSourcePathTrimmed)) { "caller-provided-device-path" } else { "app-private-staged-source" })
    spatial_video_projection_destination_relative_path = $VideoDestinationRelativePath
    spatial_video_projection_width = $videoWidthClamped
    spatial_video_projection_height = $videoHeightClamped
    spatial_video_projection_max_images = $videoMaxImagesClamped
    spatial_video_projection_fps_cap = $videoFpsCapClamped
    spatial_video_projection_looping = [bool]$VideoLooping
    spatial_video_projection_stereo_layout = $videoStereoLayoutToken
    spatial_video_projection_opacity = $videoOpacityClamped
    projection_carrier = $projectionCarrierToken
    projection_carrier_property = "debug.rustyquest.spatial.camera_hwb_projection_probe.carrier"
    projection_carrier_launch_extra = "rusty.quest.spatial.camera_hwb_projection_probe.carrier"
    projection_carrier_launch_transport = "android-property-and-intent-extra"
    projection_room_render_order_expected = $expectedRoomRenderOrderToken
    require_spatial_asset_model = [bool]$RequireSpatialAssetModel
    enable_spatial_virtual_room = [bool]$EnableVirtualRoom
    enable_spatial_skybox = [bool]$effectiveSkyboxEnabled
    spatial_skybox_mode = $effectiveSkyboxMode
    require_spatial_virtual_room = [bool]$RequireSpatialVirtualRoom
    spatial_virtual_room_module = "spatial-sdk-packaged-virtual-room"
    spatial_virtual_room_runtime_property_enabled = "debug.rustyquest.spatial.virtual_room.enabled"
    spatial_skybox_module = "spatial-sdk-skybox-only"
    spatial_skybox_runtime_property_enabled = "debug.rustyquest.spatial.skybox.enabled"
    spatial_skybox_runtime_property_mode = "debug.rustyquest.spatial.skybox.mode"
    spatial_virtual_room_scene_uri = "apk:///scenes/Composition.glxf"
    spatial_virtual_room_asset_policy = "packaged-glxf-local-launch-input"
    spatial_asset_model_requested = $assetModelRequested
    spatial_asset_model_mesh_uri = $assetMeshUriTrimmed
    spatial_asset_model_source_path = $assetSourcePathTrimmed
    spatial_asset_model_converted_mesh_path = $assetConvertedMeshPathTrimmed
    spatial_asset_model_stage_path = ""
    spatial_asset_model_path_transport = $(if ([string]::IsNullOrWhiteSpace($assetSourcePathTrimmed)) { "caller-provided-mesh-uri" } else { "app-private-staged-source" })
    spatial_asset_model_destination_relative_path = $AssetDestinationRelativePath
    spatial_asset_model_source_format = $assetSourceFormatTrimmed
    spatial_asset_model_label = $assetLabelTrimmed
    spatial_asset_model_position_m = $assetPositionTrimmed
    spatial_asset_model_rotation_degrees = $assetRotationTrimmed
    spatial_asset_model_scale = $assetScaleClamped
    spatial_asset_model_grabbable = [bool]$AssetGrabbable
    spatial_asset_model_module = "spatial-sdk-staged-3d-asset"
    spatial_asset_model_runtime_property_enabled = "debug.rustyquest.spatial.asset_model.enabled"
    spatial_asset_model_runtime_property_mesh_uri = "debug.rustyquest.spatial.asset_model.mesh_uri"
    spatial_asset_model_launch_transport = "none"
    public_multistack_depth_layer_policy = $depthLayerPolicyToken
    synthetic_visual_probe = [bool]$SyntheticVisualProbe
    synthetic_visual_property = "debug.rustyquest.spatial.camera_hwb_projection_probe.synthetic_visual"
    synthetic_visual_pixel_thresholds = [ordered]@{
        minimum_red_pixels = $MinimumSyntheticRedPixels
        minimum_green_pixels = $MinimumSyntheticGreenPixels
        minimum_target_pixel_ratio = $MinimumSyntheticTargetPixelRatio
    }
    synthetic_visual_visible = $false
    carrier = "runtime-detected"
    tag_logcat_stream_path = $tagLogcatStreamPath
    tag_logcat_error_path = $tagLogcatErrorPath
    pid_logcat_path = $pidLogcatPath
    all_logcat_path = $allLogcatPath
    window_focus_path = $windowFocusPath
    activity_dump_path = $activityDumpPath
    screenshot_path = $screenshotPath
    screenshot_pixel_classification_path = $pixelSummaryPath
    screenshot_valid = $false
    screenshot_invalid_reason = ""
}

$logcatProcess = $null

try {
    $state = Invoke-AdbCommand -Name "adb get-state" -Arguments @("get-state")
    $summary.device_state = $state.output.Trim()
    if ($summary.device_state -ne "device") {
        throw "ADB target is not ready: $($summary.device_state)"
    }
    Save-Text -Path (Join-Path $OutDir "adb-device-state.txt") -Text $summary.device_state
    $summary.device_model = (Invoke-AdbCommand -Name "device model" -Arguments @("shell", "getprop", "ro.product.model")).output.Trim()
    $summary.device_build = (Invoke-AdbCommand -Name "device build" -Arguments @("shell", "getprop", "ro.build.version.incremental")).output.Trim()

    if ($ClearLogcat) {
        Invoke-AdbCommand -Name "clear logcat" -Arguments @("logcat", "-c") | Out-Null
    }

    if (-not $SkipInstall) {
        $install = Invoke-AdbCommand -Name "install Spatial SDK APK" -Arguments @("install", "-r", "-d", "-g", (Resolve-Path -LiteralPath $resolvedApk).Path)
        Save-Text -Path (Join-Path $OutDir "install.txt") -Text $install.output
    }

    if (-not $SkipPermissionPregrant) {
        $pregrantArgs = @(
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            (Join-Path $PSScriptRoot "Grant-SpatialCameraPanelAndroidPermissions.ps1"),
            "-Adb",
            $script:ResolvedAdb,
            "-Serial",
            $script:Serial,
            "-PackageName",
            $PackageName,
            "-GrantUseSceneDataAppOp",
            "-Out",
            $permissionPregrantPath
        )
        if ($null -ne $script:ResolvedAdbServerPort) {
            $pregrantArgs += @("-AdbServerPort", $script:ResolvedAdbServerPort)
        }
        $summary.permission_pregrant_output = Invoke-CheckedPowershell -Name "Spatial Camera Panel permission pregrant" -Arguments $pregrantArgs
        if (Test-Path -LiteralPath $permissionPregrantPath) {
            $permissionReceipt = Get-Content -LiteralPath $permissionPregrantPath -Raw | ConvertFrom-Json
            $summary.spatial_scene_permission_declared = [bool]$permissionReceipt.use_scene_permission_declared
            $summary.spatial_scene_permission_granted = [string]$permissionReceipt.use_scene_permission_granted
            $summary.spatial_scene_data_appop_mode = [string]$permissionReceipt.use_scene_data_appop_mode
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($videoSourcePathTrimmed)) {
        if (-not (Test-Path -LiteralPath $videoSourcePathTrimmed)) {
            throw "VideoSourcePath not found: $videoSourcePathTrimmed"
        }
        $stageReceiptPath = Join-Path $OutDir "spatial-video-stage.json"
        $stageOutputPath = Join-Path $OutDir "spatial-video-stage-output.txt"
        $stageScriptPath = Join-Path $PSScriptRoot "Stage-NativeRendererVideo.ps1"
        $stageArgs = @(
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            $stageScriptPath,
            "-SourcePath",
            (Resolve-Path -LiteralPath $videoSourcePathTrimmed).Path,
            "-Adb",
            $script:ResolvedAdb,
            "-Serial",
            $script:Serial,
            "-PackageName",
            $PackageName,
            "-DestinationRelativePath",
            $VideoDestinationRelativePath,
            "-Out",
            $stageReceiptPath
        )
        if ($null -ne $script:ResolvedAdbServerPort) {
            $stageArgs += @("-AdbServerPort", $script:ResolvedAdbServerPort)
        }
        $stageOutput = & powershell @stageArgs 2>&1
        $stageExitCode = $LASTEXITCODE
        Save-Text -Path $stageOutputPath -Text ($stageOutput -join "`n")
        if ($stageExitCode -ne 0) {
            throw "stage Spatial video source failed with exit code $stageExitCode`n$($stageOutput -join "`n")"
        }
        $stageReceipt = Get-Content -LiteralPath $stageReceiptPath -Raw | ConvertFrom-Json
        $videoPathTrimmed = [string]$stageReceipt.video_projection_path
        $videoProjectionRequested = -not [string]::IsNullOrWhiteSpace($videoPathTrimmed)
        $summary.spatial_video_projection_requested = $videoProjectionRequested
        $summary.spatial_video_projection_path = $videoPathTrimmed
        $summary.spatial_video_projection_stage_path = $stageReceiptPath
        $summary.spatial_video_projection_path_transport = "app-private-staged-source"
    }

    if (-not [string]::IsNullOrWhiteSpace($assetSourcePathTrimmed)) {
        if (-not (Test-Path -LiteralPath $assetSourcePathTrimmed)) {
            throw "AssetSourcePath not found: $assetSourcePathTrimmed"
        }
        $assetStageReceiptPath = Join-Path $OutDir "spatial-asset-stage.json"
        $assetStageOutputPath = Join-Path $OutDir "spatial-asset-stage-output.txt"
        $assetStageScriptPath = Join-Path $PSScriptRoot "Stage-SpatialCameraPanelAsset.ps1"
        $assetStageArgs = @(
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            $assetStageScriptPath,
            "-SourcePath",
            (Resolve-Path -LiteralPath $assetSourcePathTrimmed).Path,
            "-Adb",
            $script:ResolvedAdb,
            "-Serial",
            $script:Serial,
            "-PackageName",
            $PackageName,
            "-DestinationRelativePath",
            $AssetDestinationRelativePath,
            "-Out",
            $assetStageReceiptPath
        )
        if (-not [string]::IsNullOrWhiteSpace($assetConvertedMeshPathTrimmed)) {
            $assetStageArgs += @("-ConvertedMeshPath", (Resolve-Path -LiteralPath $assetConvertedMeshPathTrimmed).Path)
        }
        if ($null -ne $script:ResolvedAdbServerPort) {
            $assetStageArgs += @("-AdbServerPort", $script:ResolvedAdbServerPort)
        }
        $assetStageOutput = & powershell @assetStageArgs 2>&1
        $assetStageExitCode = $LASTEXITCODE
        Save-Text -Path $assetStageOutputPath -Text ($assetStageOutput -join "`n")
        if ($assetStageExitCode -ne 0) {
            throw "stage Spatial asset source failed with exit code $assetStageExitCode`n$($assetStageOutput -join "`n")"
        }
        $assetStageReceipt = Get-Content -LiteralPath $assetStageReceiptPath -Raw | ConvertFrom-Json
        $assetMeshUriTrimmed = [string]$assetStageReceipt.mesh_uri
        $assetSourceFormatTrimmed = [string]$assetStageReceipt.source_format
        $assetModelRequested = -not [string]::IsNullOrWhiteSpace($assetMeshUriTrimmed)
        $summary.spatial_asset_model_requested = $assetModelRequested
        $summary.spatial_asset_model_mesh_uri = $assetMeshUriTrimmed
        $summary.spatial_asset_model_stage_path = $assetStageReceiptPath
        $summary.spatial_asset_model_path_transport = "app-private-staged-source"
        $summary.spatial_asset_model_source_format = $assetSourceFormatTrimmed
        $summary.spatial_asset_model_staged_format = [string]$assetStageReceipt.staged_format
        $summary.spatial_asset_model_sdk_loadable_mesh_uri = [bool]$assetStageReceipt.sdk_loadable_mesh_uri
        $summary.spatial_asset_model_fbx_conversion_required = [bool]$assetStageReceipt.fbx_conversion_required
    }

    $setpropResults = @()
    $setpropResults += Invoke-AdbCommand -Name "disable SDK quad surface probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.sdk_quad_surface_probe", "0")
    $setpropResults += Invoke-AdbCommand -Name "disable SDK quad Vulkan probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.sdk_quad_vulkan_probe", "0")
    $setpropResults += Invoke-AdbCommand -Name "disable SDK quad stereo alpha probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.sdk_quad_stereo_alpha_probe", "0")
    $setpropResults += Invoke-AdbCommand -Name "disable panel surface matrix probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.panel_surface_matrix_probe", "0")
    $setpropResults += Invoke-AdbCommand -Name "disable luma camera HWB probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_probe", "0")
    $setpropResults += Invoke-AdbCommand -Name "clear Spatial skybox mode" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.skybox.mode", "none")
    $setpropResults += Invoke-AdbCommand -Name "configure raw camera projection probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe", $(if ($VideoOnly) { "0" } else { "1" }))
    $setpropResults += Invoke-AdbCommand -Name "configure synthetic carrier visual probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.synthetic_visual", $(if ($SyntheticVisualProbe) { "1" } else { "0" }))
    $setpropResults += Invoke-AdbCommand -Name "configure Spatial video-only projection probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.video_projection_probe", $(if ($VideoOnly) { "1" } else { "0" }))
    $setpropResults += Invoke-AdbCommand -Name "set raw camera projection reader max images" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.reader_max_images", $readerMaxImagesClamped.ToString())
    $setpropResults += Invoke-AdbCommand -Name "set Spatial depth layer policy" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.depth.layer_policy", $depthLayerPolicyToken)
    $setpropResults += Invoke-AdbCommand -Name "set Spatial projection carrier" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.carrier", $projectionCarrierToken)
    $setpropResults += Invoke-AdbCommand -Name "select Spatial SDK interaction pointer input" -Arguments @("shell", "setprop", "debug.rustyquest.spatial_camera_panel.vr_input_system", "interaction_sdk")
    $setpropResults += Invoke-AdbCommand -Name "allow app controller probe to observe left/right input" -Arguments @("shell", "setprop", "debug.rustyquest.spatial_camera_panel.consume_left_right_input", "0")
    $setpropResults += Invoke-AdbCommand -Name "disable Spatial SDK multimodal input" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.multimodal_input.enabled", "0")
    $setpropResults += Invoke-AdbCommand -Name "disable native OpenXR controller action fallback" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.native_controller_actions.enabled", "0")
    $setpropResults += Invoke-AdbCommand -Name "configure Spatial packaged virtual room" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.virtual_room.enabled", $(if ($EnableVirtualRoom) { "1" } else { "0" }))
    $setpropResults += Invoke-AdbCommand -Name "configure Spatial skybox" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.skybox.enabled", $(if ($effectiveSkyboxEnabled) { "1" } else { "0" }))
    $setpropResults += Invoke-AdbCommand -Name "configure Spatial skybox mode" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.skybox.mode", $effectiveSkyboxMode)
    $setpropResults += Invoke-AdbCommand -Name "configure Spatial video projection enabled" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.video.enabled", $(if ($videoProjectionRequested) { "1" } else { "0" }))
    $setpropResults += Invoke-AdbCommand -Name "disable Spatial video high-rate JSON payload" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.video.high_rate_json_payload", "0")
    if ($videoProjectionRequested) {
        $videoOpacityText = $videoOpacityClamped.ToString("0.###", [System.Globalization.CultureInfo]::InvariantCulture)
        $setpropResults += Invoke-AdbCommand -Name "set Spatial video path" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.video.path", $videoPathTrimmed)
        $setpropResults += Invoke-AdbCommand -Name "set Spatial video stereo layout" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.video.stereo_layout", $videoStereoLayoutToken)
        $setpropResults += Invoke-AdbCommand -Name "set Spatial video width" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.video.width", $videoWidthClamped.ToString())
        $setpropResults += Invoke-AdbCommand -Name "set Spatial video height" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.video.height", $videoHeightClamped.ToString())
        $setpropResults += Invoke-AdbCommand -Name "set Spatial video max images" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.video.max_images", $videoMaxImagesClamped.ToString())
        $setpropResults += Invoke-AdbCommand -Name "set Spatial video FPS cap" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.video.fps_cap", $videoFpsCapClamped.ToString())
        $setpropResults += Invoke-AdbCommand -Name "set Spatial video looping" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.video.looping", $(if ($VideoLooping) { "1" } else { "0" }))
        $setpropResults += Invoke-AdbCommand -Name "set Spatial video opacity" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.video.opacity", $videoOpacityText)
    }
    $setpropResults += Invoke-AdbCommand -Name "configure Spatial staged asset model" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.asset_model.enabled", $(if ($assetModelRequested) { "1" } else { "0" }))
    if ($assetModelRequested) {
        $assetScaleText = $assetScaleClamped.ToString("0.###", [System.Globalization.CultureInfo]::InvariantCulture)
        $assetPositionLaunchExtra = $assetPositionTrimmed.Replace(";", ",")
        $assetRotationLaunchExtra = $assetRotationTrimmed.Replace(";", ",")
        $summary.spatial_asset_model_launch_transport = "intent-extras"
    }
    Save-Text -Path (Join-Path $OutDir "setprops.json") -Text ($setpropResults | ConvertTo-Json -Depth 6)

    $forceStoppedPackages = @()
    if (-not $SkipForceStopKnownXrPackages) {
        $knownXrPackages = @(
            $PackageName,
            "io.github.mesmerprism.rustyhostess.t",
            "io.github.mesmerprism.rustyhostess.makepad",
            "io.github.mesmerprism.rustymanifold.broker",
            "io.github.mesmerprism.rustyquest.native_renderer"
        ) | Select-Object -Unique
        foreach ($knownPackage in $knownXrPackages) {
            $stopKnown = Invoke-AdbCommand -Name "force-stop $knownPackage" -Arguments @("shell", "am", "force-stop", $knownPackage) -AllowFailure
            $forceStoppedPackages += [pscustomobject]@{
                package = $knownPackage
                exit_code = $stopKnown.exit_code
                output = $stopKnown.output
            }
        }
    } else {
        Invoke-AdbCommand -Name "force-stop Spatial SDK app" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
        $forceStoppedPackages += [pscustomobject]@{
            package = $PackageName
            exit_code = 0
            output = "only target package stopped; known XR package cleanup skipped"
        }
    }
    $summary.force_stopped_known_xr_packages = $forceStoppedPackages
    Save-Text -Path (Join-Path $OutDir "force-stopped-packages.json") -Text ($forceStoppedPackages | ConvertTo-Json -Depth 5)

    $logcatArgs = @()
    if ($null -ne $script:ResolvedAdbServerPort) {
        $logcatArgs += @("-P", $script:ResolvedAdbServerPort)
    }
    $logcatArgs += @(
        "-s", $Serial,
        "logcat", "-v", "time",
        "RQSpatialCameraPanel:D",
        "RQSpatialCameraPanelNative:D",
        "*:S"
    )
    $logcatProcess = Start-Process `
        -FilePath $script:ResolvedAdb `
        -ArgumentList $logcatArgs `
        -RedirectStandardOutput $tagLogcatStreamPath `
        -RedirectStandardError $tagLogcatErrorPath `
        -PassThru `
        -WindowStyle Hidden
    Start-Sleep -Milliseconds 300

    $launchArgs = @(
        "shell", "am", "start", "-W", "-n", $Activity,
        "--es", "rusty.quest.spatial.camera_hwb_projection_probe.carrier", $projectionCarrierToken
    )
    if ($assetModelRequested) {
        $launchArgs += @(
            "--ez", "rusty.quest.spatial.asset_model.enabled", "true",
            "--es", "rusty.quest.spatial.asset_model.mesh_uri", $assetMeshUriTrimmed,
            "--es", "rusty.quest.spatial.asset_model.source_format", $assetSourceFormatTrimmed,
            "--es", "rusty.quest.spatial.asset_model.label", $assetLabelTrimmed,
            "--es", "rusty.quest.spatial.asset_model.position_m", $assetPositionLaunchExtra,
            "--es", "rusty.quest.spatial.asset_model.rotation_degrees", $assetRotationLaunchExtra,
            "--ef", "rusty.quest.spatial.asset_model.scale", $assetScaleText,
            "--ez", "rusty.quest.spatial.asset_model.grabbable", $(if ($AssetGrabbable) { "true" } else { "false" })
        )
    }
    $launch = Invoke-AdbCommand -Name "launch raw camera projection probe" -Arguments $launchArgs
    Save-Text -Path (Join-Path $OutDir "launch.txt") -Text $launch.output
    Start-Sleep -Seconds ([Math]::Max(1, $RunSeconds))

    $pidResult = Invoke-AdbCommand -Name "Spatial SDK app pid" -Arguments @("shell", "pidof", $PackageName) -AllowFailure
    $targetPid = (($pidResult.output -split "\s+") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
    $summary.pid = $targetPid
    Save-Text -Path (Join-Path $OutDir "pid.txt") -Text $targetPid

    if (-not [string]::IsNullOrWhiteSpace($targetPid)) {
        $pidLogcat = (Invoke-AdbCommand -Name "dump pid-scoped logcat" -Arguments @("logcat", "-d", "-v", "time", "--pid", $targetPid) -AllowFailure).output
    } else {
        $pidLogcat = ""
    }
    Save-Text -Path $pidLogcatPath -Text $pidLogcat
    $allLogcat = (Invoke-AdbCommand -Name "dump full logcat" -Arguments @("logcat", "-d", "-v", "time") -AllowFailure).output
    Save-Text -Path $allLogcatPath -Text $allLogcat

    $windowFocus = (Invoke-AdbCommand -Name "dump window focus" -Arguments @("shell", "dumpsys", "window") -AllowFailure).output
    Save-Text -Path $windowFocusPath -Text $windowFocus

    $activityDump = (Invoke-AdbCommand -Name "dump activity activities" -Arguments @("shell", "dumpsys", "activity", "activities") -AllowFailure).output
    Save-Text -Path $activityDumpPath -Text $activityDump
    $foregroundProof = Get-SpatialForegroundProof `
        -PackageName $PackageName `
        -Activity $Activity `
        -TargetPid $targetPid `
        -LaunchText $launch.output `
        -WindowText $windowFocus `
        -ActivityText $activityDump
    $summary.foreground_proof = $foregroundProof
    $summary.foreground_pid_live = [bool]$foregroundProof.pid_live
    $summary.foreground_launch_component_matches = [bool]$foregroundProof.launch_component_matches
    $summary.foreground_window_focus_matches = [bool]$foregroundProof.window_focus_matches
    $summary.foreground_activity_resumed_matches = [bool]$foregroundProof.activity_resumed_matches
    $summary.foreground_focused_or_resumed = [bool]$foregroundProof.focused_or_resumed
    $summary.foreground_process_record_matches_pid = [bool]$foregroundProof.process_record_matches_pid
    $summary.foreground_validation_passed = [bool]$foregroundProof.valid
    Save-Text -Path $foregroundProofPath -Text ($foregroundProof | ConvertTo-Json -Depth 5)

    Invoke-AdbCommand -Name "capture screenshot" -Arguments @("shell", "screencap", "-p", $remoteScreenshotPath) -AllowFailure | Out-Null
    Invoke-AdbCommand -Name "pull screenshot" -Arguments @("pull", $remoteScreenshotPath, $screenshotPath) -AllowFailure | Out-Null
    Invoke-AdbCommand -Name "remove remote screenshot" -Arguments @("shell", "rm", $remoteScreenshotPath) -AllowFailure | Out-Null
    if (Test-Path -LiteralPath $screenshotPath) {
        $pixelSummary = Measure-SyntheticSurfacePixels -Path $screenshotPath -Stride 4
        $summary.screenshot_pixel_classification = $pixelSummary
        Save-Text -Path $pixelSummaryPath -Text ($pixelSummary | ConvertTo-Json -Depth 5)
    }

    if ($null -ne $logcatProcess -and -not $logcatProcess.HasExited) {
        Stop-Process -Id $logcatProcess.Id -Force
        $logcatProcess.WaitForExit()
    }
    Start-Sleep -Milliseconds 250

    $tagLogcat = if (Test-Path -LiteralPath $tagLogcatStreamPath) {
        Get-Content -Raw -LiteralPath $tagLogcatStreamPath
    } else {
        ""
    }
    $evidenceText = "$tagLogcat`n$pidLogcat`n$allLogcat"
    $appScopedEvidenceText = "$tagLogcat`n$pidLogcat"

    $summary.start = if ($VideoOnly) {
        (Test-TextContains $evidenceText "status=start") -and (Test-TextContains $evidenceText "videoOnlySpatialProjection=true")
    } else {
        (Test-TextContains $evidenceText "status=start") -and (Test-TextContains $evidenceText "rawCameraProjectionProbe=true")
    }
    $summary.scene_quad_layer_carrier = Test-TextContains $evidenceText "carrier=scenequadlayer-createAsAndroid-vulkan-wsi"
    $summary.scene_quad_layer_room_object_carrier = Test-TextContains $evidenceText "projectionCarrier=scenequadlayer-room-object"
    $summary.scene_quad_layer_created = Test-TextContains $evidenceText "status=raw-camera-projection-layer-created"
    $summary.video_surface_panel_scene_object_carrier = (Test-TextContains $evidenceText "scenePanelCarrier=true") -and (Test-TextContains $evidenceText "carrier=video-surface-panel-scene-object")
    $summary.manual_panel_scene_object_custom_mesh_carrier = (Test-TextContains $evidenceText "scenePanelCarrier=true") -and (Test-TextContains $evidenceText "carrier=manual-panel-scene-object-custom-mesh")
    $summary.manual_panel_scene_object_custom_mesh_ready = $evidenceText -match "status=manual-panel-carrier-ready[^\r\n]*surfaceValid=true"
    $summary.manual_panel_scene_mesh_creator = Test-TextContains $evidenceText "sceneMeshCreator=single-sided-quad"
    $summary.manual_panel_no_hittable = Test-TextContains $evidenceText "manualPanelNoHittable=true"
    $summary.manual_panel_no_isdk_grabbable = Test-TextContains $evidenceText "manualPanelNoIsdkGrabbable=true"
    $summary.manual_panel_input_click_buttons_zero = Test-TextContains $evidenceText "panelInputOptionsClickButtons=0"
    $summary.scene_panel_carrier = [bool]$summary.video_surface_panel_scene_object_carrier -or [bool]$summary.manual_panel_scene_object_custom_mesh_carrier
    $summary.scene_panel_carrier_entity_created = (
        ($evidenceText -match "status=scene-panel-carrier-entity-spawned[^\r\n]*entityCreated=true") -or
        [bool]$summary.manual_panel_scene_object_custom_mesh_ready
    )
    $summary.scene_panel_carrier_ready = (
        ($evidenceText -match "status=scene-panel-ready[^\r\n]*surfaceValid=true") -or
        [bool]$summary.manual_panel_scene_object_custom_mesh_ready
    )
    $summary.scene_panel_carrier_native_start_requested = $evidenceText -match "status=native-start-requested[^\r\n]*scenePanelCarrier=true"
    $summary.projection_panel_input_pass_through = Test-TextContains $evidenceText "projectionPanelInputPassThrough=true"
    $summary.projection_panel_hittable_no_collision = Test-TextContains $evidenceText "projectionPanelHittable=NoCollision"
    $summary.projection_panel_hittable_manual_noninteractive = Test-TextContains $evidenceText "projectionPanelHittable=none-manual-custom-mesh-noninteractive"
    $summary.private_layer_panel_default_reach_distance_preserved = Test-TextContains $evidenceText "privateLayerPanelDefaultReachDistancePreserved=true"
    $summary.private_layer_panel_left_stick_distance_enabled = Test-TextContains $evidenceText "privateLayerPanelDistanceControl=left-stick-y-private-panel-free-transform-distance"
    $summary.private_layer_panel_distance_persists_across_toggle = Test-TextContains $evidenceText "privateLayerPanelDistancePersistsAcrossToggle=true"
    $summary.right_stick_side_flick_panel_move_disabled = Test-TextContains $evidenceText "rightStickSideFlickPanelMoveDisabled=true"
    $summary.private_layer_panel_button_selected = $evidenceText -match "status=layer-button-selected[^\r\n]*source=private-layer-control-panel"
    $summary.private_layer_panel_override_submitted = $evidenceText -match "status=layer-override-submitted[^\r\n]*source=private-layer-control-panel"
    $summary.private_layer_panel_override_native_updated = Test-TextContains $evidenceText "status=private-layer-override-updated"
    $summary.private_layer_panel_projection_refresh_forced = Test-TextContains $evidenceText "layerOverrideForcedProjectionRefresh=true"
    $summary.layer_created = [bool]$summary.scene_quad_layer_created -or [bool]$summary.scene_panel_carrier_entity_created
    $summary.carrier = if ([bool]$summary.manual_panel_scene_object_custom_mesh_carrier) {
        "manual-panel-scene-object-custom-mesh"
    } elseif ([bool]$summary.video_surface_panel_scene_object_carrier) {
        "video-surface-panel-scene-object"
    } elseif ([bool]$summary.scene_quad_layer_carrier) {
        "scenequadlayer-createAsAndroid-vulkan-wsi"
    } else {
        "unknown"
    }
    $summary.native_start_requested = Test-TextContains $evidenceText "status=native-start-requested"
    $summary.render_loop_ready = Test-TextContains $evidenceText "status=render-loop-ready"
    $summary.camera_runtime_started = Test-TextContains $evidenceText "status=camera-runtime-started"
    $summary.ahb_properties = Test-TextContains $evidenceText "status=ahb-properties"
    $summary.resources_created = Test-TextContains $evidenceText "status=probe-resources-created"
    $summary.ahb_imported = Test-TextContains $evidenceText "status=ahb-imported"
    $summary.first_frame_presented = Test-TextContains $evidenceText "status=first-camera-frame-presented"
    $summary.raw_frame_presented = Test-TextContains $evidenceText "status=raw-camera-frame-presented"
    $summary.camera_frame_acquired = Test-TextContains $evidenceText "status=camera-frame-acquired"
    $summary.stereo_source_camera_50_51 = Test-TextContains $evidenceText "stereoSource=camera50-51"
    $summary.left_camera_50 = Test-TextContains $evidenceText "leftCameraId=50"
    $summary.right_camera_51 = Test-TextContains $evidenceText "rightCameraId=51"
    $summary.output_raw_color_target_rect = Test-TextContains $evidenceText "outputMode=raw-color-target-rect"
    $summary.target_clip_policy = Test-TextContains $evidenceText "targetClipPolicy=clip-to-visible-eye"
    $summary.mapping_mode_target_local_raster = Test-TextContains $evidenceText "projectionContentMappingMode=target-local-raster"
    $summary.native_packed_left_target_rect = Test-TextContains $evidenceText "leftPackedEffectiveTargetScreenUvRect=0.062777;0.218750;0.375000;0.656250"
    $summary.native_packed_right_target_rect = Test-TextContains $evidenceText "rightPackedEffectiveTargetScreenUvRect=0.562222;0.218750;0.375000;0.671875"
    $summary.projection_target_default_distance_two_meters = Test-TextContains $evidenceText "targetDistanceDefaultMeters=2.00"
    $summary.projection_target_stereo_horizontal_offset_readback = Test-TextContains $evidenceText "projectionTargetStereoHorizontalOffsetUv="
    $summary.projection_target_stereo_horizontal_offset_default = Test-TextContains $evidenceText "projectionTargetStereoHorizontalOffsetDefaultUv=0.046320"
    $summary.projection_target_left_right_offset_readback = (Test-TextContains $evidenceText "projectionTargetLeftOffsetUv=") -and (Test-TextContains $evidenceText "projectionTargetRightOffsetUv=")
    $summary.projection_target_stereo_horizontal_offset_control_disabled = Test-TextContains $evidenceText "stereoHorizontalOffsetJoystickInput=disabled-default-locked-left-stick-y-controls-workflow-or-private-panel-distance-only"
    $summary.mono_duplicated_false = Test-TextContains $evidenceText "monoDuplicated=false"
    $summary.private_shader_stack_false = Test-TextContains $evidenceText "privateShaderStack=false"
    $summary.custom_projection_stack_false = Test-TextContains $evidenceText "customProjectionStack=false"
    $summary.spatial_hands_and_controllers_manifest = Test-TextContains $evidenceText "spatialHandsAndControllersManifest=true"
    $summary.spatial_interaction_sdk_backend = Test-TextContains $evidenceText "spatialVrInputSystem=interaction_sdk"
    $summary.spatial_pointer_input_expected = Test-TextContains $evidenceText "spatialPointerInputExpected=true"
    $summary.spatial_controller_actions_disabled = Test-TextContains $evidenceText "nativeControllerActionBridge=false"
    $summary.spatial_controller_actions_native_fallback_declared =
        Test-TextContains $evidenceText "nativeSpatialControllerActionsProperty=debug.rustyquest.spatial.native_controller_actions.enabled"
    $summary.spatial_multimodal_disabled = (Test-TextContains $evidenceText "spatialMultimodalRequiredOpenXrExtensions=none") -and (Test-TextContains $evidenceText "spatialMultimodalInputRequest=false")
    $summary.spatial_required_openxr_includes_passthrough = Test-TextContains $evidenceText "XR_FB_passthrough"
    $summary.spatial_required_openxr_includes_environment_depth = Test-TextContains $evidenceText "XR_META_environment_depth"
    $summary.camera_stack_particle_layer_suppressed = $evidenceText -match "status=particle-layer-suppressed"
    $summary.camera_stack_particles_suppression_enabled = $evidenceText -match "status=particle-layer-suppressed[^\r\n]*cameraStackSuppressesParticles=true"
    $summary.camera_stack_particle_layer_visible_false = $evidenceText -match "status=particle-layer-suppressed[^\r\n]*particleLayerVisible=false"
    $summary.camera_stack_particle_layer_started_false = $evidenceText -match "status=particle-layer-suppressed[^\r\n]*particleLayerStarted=false"
    $summary.camera_stack_particle_suppress_failed_false = -not ($evidenceText -match "status=particle-layer-suppress-failed")
    $summary.camera_stack_particle_renderer_ready_false = -not ($evidenceText -match "status=native-hand-anchor-particles-ready")
    $summary.camera_stack_surface_layer_mode_absent = -not ($evidenceText -match "surfaceLayerMode=native-hand-anchor-particles")
    $summary.public_multistack_contract_ready = Test-TextContains $evidenceText "status=public-multistack-contract-ready"
    $summary.public_multistack_guide_targets_ready = Test-TextContains $evidenceText "status=public-multistack-guide-targets-ready"
    $summary.public_multistack_guide_targets_allocated = Test-TextContains $evidenceText "publicMultiStackGuideTargetsAllocated=true"
    $summary.public_multistack_guide_resources_ready = Test-TextContains $evidenceText "publicMultiStackGuidePassResourcesReady=true"
    $summary.public_multistack_pass_execution_ready = Test-TextContains $evidenceText "publicMultiStackPassExecutionReady=true"
    $summary.public_guide_blur_runtime_ready = Test-TextContains $evidenceText "publicGuideBlurRuntimeReady=true"
    $summary.public_multistack_opaque_guide_pipelines_ready = Test-TextContains $evidenceText "publicMultiStackOpaqueGuidePipelinesReady=true"
    $summary.public_multistack_opaque_projection_pipeline_ready = Test-TextContains $evidenceText "publicMultiStackOpaqueProjectionPipelineReady=true"
    $summary.public_multistack_opaque_projection_payload_execution_ready = Test-TextContains $evidenceText "publicMultiStackOpaqueProjectionPayloadExecutionReady=true"
    $summary.public_multistack_opaque_payload_execution_ready = Test-TextContains $evidenceText "publicMultiStackOpaquePayloadExecutionReady=true"
    $summary.public_multistack_depth_fallback_ready = Test-TextContains $evidenceText "publicMultiStackDepthFallbackReady=true"
    $summary.public_multistack_depth_fallback_source = Test-TextContains $evidenceText "publicMultiStackDepthSource=spatial-fallback-depth-descriptor"
    $summary.public_multistack_depth_real_provider_bound_false = Test-TextContains $evidenceText "publicMultiStackDepthRealProviderBound=false"
    $summary.public_multistack_depth_descriptor_shape_real = Test-TextContains $evidenceText "publicMultiStackDepthDescriptorShape=single-combined-d16-array-sampler"
    $summary.public_multistack_depth_real_descriptor_bound = Test-TextContains $evidenceText "publicMultiStackDepthRealDescriptorBound=true"
    $summary.public_multistack_depth_current_source_real = Test-TextContains $evidenceText "publicMultiStackDepthCurrentDescriptorSource=xr-meta-environment-depth"
    $summary.public_multistack_depth_descriptor_frame_count_nonzero = $evidenceText -match "publicMultiStackDepthDescriptorAcquiredFrameCount=([1-9][0-9]*)"
    $summary.spatial_environment_depth_start_requested = Test-TextContains $evidenceText "channel=spatial-environment-depth status=start-requested"
    $summary.spatial_environment_depth_provider_created = Test-TextContains $evidenceText "channel=spatial-environment-depth status=provider-created"
    $summary.spatial_environment_depth_provider_bound = Test-TextContains $evidenceText "environmentDepthRealProviderBound=true"
    $summary.spatial_environment_depth_acquire_thread_started = Test-TextContains $evidenceText "environmentDepthAcquireThreadStarted=true"
    $summary.spatial_environment_depth_acquire_loop_running = Test-TextContains $evidenceText "channel=spatial-environment-depth status=runtime"
    $summary.spatial_environment_depth_first_frame = Test-TextContains $evidenceText "channel=spatial-environment-depth status=first-frame"
    $summary.spatial_environment_depth_acquired = Test-TextContains $evidenceText "environmentDepthAcquireStatus=acquired"
    $summary.spatial_environment_depth_valid_data = Test-TextContains $evidenceText "environmentDepthValidData=true"
    $summary.spatial_environment_depth_valid_sample_count_nonzero = $evidenceText -match "environmentDepthDebugValidSampleCount=([1-9][0-9]*)"
    $summary.spatial_native_passthrough_layer_active = Test-TextContains $evidenceText "nativePassthroughLayerActive=true"
    $summary.spatial_native_passthrough_layer_inactive = Test-TextContains $evidenceText "nativePassthroughLayerActive=false"
    $summary.spatial_native_passthrough_prerequisite_active = Test-TextContains $evidenceText "environmentDepthPassthroughPrerequisite=active"
    $summary.public_multistack_frame_projected = Test-TextContains $evidenceText "status=public-multistack-frame-projected"
    $summary.public_multistack_projection_evidence = Test-TextContains $evidenceText "status=public-multistack-projection-evidence"
    $summary.public_multistack_projection_applied = Test-TextContains $evidenceText "publicMultiStackProjectionApplied=true"
    $summary.public_multistack_layer_cycle_enabled = Test-TextContains $evidenceText "publicMultiStackLayerCycleEnabled=true"
    $summary.public_multistack_layer_cycle_elapsed = Test-TextContains $evidenceText "publicMultiStackLayerCycleElapsedSeconds="
    $summary.public_multistack_depth_layer_policy_marker =
        Test-TextContains $evidenceText "publicMultiStackDepthLayerPolicy=$depthLayerPolicyToken"
    $summary.public_multistack_depth_layer_compare_visual_shader =
        if ($depthLayerPolicyToken -eq "compare") {
            Test-TextContains $evidenceText "publicMultiStackDepthLayerCompareMode=visual-shader"
        } else {
            Test-TextContains $evidenceText "publicMultiStackDepthLayerCompareMode=off"
        }
    $summary.public_multistack_opaque_projection_target_space = Test-TextContains $evidenceText "publicMultiStackOpaqueProjectionTargetSpace=packed-stereo-surface-uv"
    $summary.public_multistack_opaque_projection_left_target_rect = Test-TextContains $evidenceText "publicMultiStackOpaqueProjectionLeftTargetRect=0.062777;0.218750;0.375000;0.656250"
    $summary.public_multistack_opaque_projection_right_target_rect = Test-TextContains $evidenceText "publicMultiStackOpaqueProjectionRightTargetRect=0.562222;0.218750;0.375000;0.671875"
    $summary.spatial_video_projection_native_configured = Test-TextContains $evidenceText "channel=spatial-video-projection status=native-configured"
    $summary.spatial_video_projection_start_requested = Test-TextContains $evidenceText "channel=spatial-video-projection status=start-requested"
    $summary.spatial_video_projection_surface_created = Test-TextContains $evidenceText "status=surface-created"
    $summary.spatial_video_projection_mediacodec_started = Test-TextContains $evidenceText "mediaCodecStarted=true"
    $summary.spatial_video_projection_decoded_frame_acquired = Test-TextContains $evidenceText "status=decoded-frame-acquired"
    $summary.spatial_video_projection_ahb_import_ready = Test-TextContains $evidenceText "status=ahardware-buffer-import-ready"
    $summary.spatial_video_projection_rendered = (Test-TextContains $evidenceText "spatialVideoProjectionRendered=true") -and (Test-TextContains $evidenceText "videoProjectionRendered=true")
    $summary.spatial_video_projection_same_surface =
        (Test-TextContains $evidenceText "spatialVideoProjectionSameSurfaceComposition=true") -or
        (Test-TextContains $evidenceText "sameSurfaceComposition=true")
    $summary.spatial_video_projection_before_camera =
        (Test-TextContains $evidenceText "videoProjectionComposedBeforeCamera=true") -or
        (Test-TextContains $evidenceText "composedBeforeCamera=true")
    $summary.spatial_video_projection_camera_alignment_preserved = Test-TextContains $evidenceText "cameraProjectionAlignmentPreserved=true"
    $summary.spatial_video_projection_no_cpu_copy = Test-TextContains $evidenceText "nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false"
    $summary.spatial_video_projection_video_only_render_loop_ready = (Test-TextContains $evidenceText "videoOnlySpatialProjection=true") -and (Test-TextContains $evidenceText "status=render-loop-ready")
    $summary.spatial_video_projection_video_only_presented = (Test-TextContains $evidenceText "videoOnlySpatialProjection=true") -and (Test-TextContains $evidenceText "status=video-frame-presented")
    $summary.camera_runtime_absent_when_video_only = if ($VideoOnly) { -not (Test-TextContains $evidenceText "status=camera-runtime-started") } else { $true }
    $summary.spatial_asset_model_module_declared =
        Test-TextContains $evidenceText "spatialSdk3dAssetModule=spatial-sdk-staged-3d-asset"
    $summary.spatial_asset_model_entity_created =
        Test-TextContains $evidenceText "channel=spatial-sdk-asset-model status=entity-created"
    $summary.spatial_asset_model_sdk_mesh_uri =
        Test-TextContains $evidenceText "sdkLoadableMeshUri=true"
    $summary.spatial_asset_model_private_source_not_packaged =
        Test-TextContains $evidenceText "privateSourceAssetPackaged=false"
    $summary.spatial_asset_model_raw_fbx_rejected =
        Test-TextContains $evidenceText "status=rejected reason=raw-fbx-uri"
    $summary.spatial_virtual_room_module_declared =
        Test-TextContains $evidenceText "spatialVirtualRoomModule=spatial-sdk-packaged-virtual-room"
    $summary.spatial_virtual_room_load_requested =
        Test-TextContains $evidenceText "channel=spatial-virtual-room status=load-requested"
    $summary.spatial_virtual_room_loaded =
        Test-TextContains $evidenceText "channel=spatial-virtual-room status=loaded"
    $summary.spatial_virtual_room_scene_configured =
        Test-TextContains $evidenceText "channel=spatial-virtual-room status=scene-configured"
    $summary.spatial_virtual_room_generic_module =
        Test-TextContains $evidenceText "genericModuleSupport=true"
    $summary.spatial_virtual_room_not_mruk =
        Test-TextContains $evidenceText "mrukPlacement=false passthroughRoomPlacement=false"
    $summary.spatial_skybox_module_declared =
        Test-TextContains $evidenceText "spatialSkyboxModule=spatial-sdk-skybox-only"
    $summary.spatial_skybox_scene_configured =
        (Test-TextContains $evidenceText "status=scene-configured") -and
        (Test-TextContains $evidenceText "skyboxMode=$expectedSkyboxModeMarker")
    $summary.spatial_skybox_mode_marker =
        Test-TextContains $evidenceText "skyboxMode=$expectedSkyboxModeMarker"
    $summary.spatial_skybox_sample_mesh_uri =
        Test-TextContains $evidenceText "skyboxRenderer=sample-toolkit-mesh-uri"
    $summary.spatial_skybox_custom_scene_mesh =
        Test-TextContains $evidenceText "skyboxRenderer=custom-runtime-scene-mesh-skybox"
    $summary.spatial_skybox_custom_background_order =
        (Test-TextContains $evidenceText "skyboxProjectionForegroundPolicy=scene-layer-over-background-skybox") -and
        (Test-TextContains $evidenceText "skyboxDepthWrite=disabled") -and
        (Test-TextContains $evidenceText "skyboxSortOrder=preprocess")
    $summary.spatial_skybox_only =
        Test-TextContains $evidenceText "skyboxOnly=true"
    $summary.camera_projection_wall_toggle_disabled =
        Test-TextContains $evidenceText "cameraProjectionWallToggleInput=disabled-right-secondary-noop"
    $summary.camera_projection_wall_mode_declared =
        Test-TextContains $evidenceText "virtualRoomWallPlacementMode=virtual-room-wall-fixed-quad"
    $summary.legacy_launcher_panel_suppressed =
        Test-TextContains $evidenceText "legacyLauncherPanelSuppressed=true"
    $summary.camera_projection_initial_full_fov_mode =
        Test-TextContains $evidenceText "projectionDefaultPlacementMode=viewer-pose-projection-locked-quad"
    $summary.camera_projection_start_gate_virtual_room_loaded =
        Test-TextContains $evidenceText "projectionStartGate=virtual-room-loaded"
    $summary.camera_projection_room_render_order =
        Test-TextContains $evidenceText "projectionRoomRenderOrder=$expectedRoomRenderOrderToken"
    $summary.private_layer_controls_apply_to_wall_and_full_fov =
        Test-TextContains $evidenceText "layerOverrideAppliesToWallAndFullFov=true"
    $summary.runtime_crash_false = (Test-TextContains $appScopedEvidenceText "runtimeCrash=false") -and -not ($appScopedEvidenceText -match "AndroidRuntime|FATAL|render-failed")
    $summary.screenshot_captured = Test-Path -LiteralPath $screenshotPath
    $summary.synthetic_visual_presented =
        Test-TextContains $evidenceText "status=synthetic-visual-presented"
    $summary.synthetic_visual_canvas_drawn =
        $evidenceText -match "status=synthetic-visual-presented[^\r\n]*canvasDrawn=true"
    $summary.synthetic_visual_camera_runtime_disabled =
        ($evidenceText -match "status=synthetic-visual-presented[^\r\n]*cameraRuntimeStarted=false") -and
        ($evidenceText -match "status=synthetic-visual-presented[^\r\n]*sampledCameraTexture=false")
    if ($SyntheticVisualProbe -and $summary.screenshot_captured -and $summary.Contains("screenshot_pixel_classification")) {
        $pixelSummary = $summary.screenshot_pixel_classification
        $summary.synthetic_visual_visible =
            ([int]$pixelSummary.red_pixels -ge $MinimumSyntheticRedPixels) -and
            ([int]$pixelSummary.green_pixels -ge $MinimumSyntheticGreenPixels) -and
            ([double]$pixelSummary.synthetic_target_ratio -ge $MinimumSyntheticTargetPixelRatio)
    } elseif (-not $SyntheticVisualProbe) {
        $summary.synthetic_visual_visible = $false
    }
    $summary.screenshot_valid =
        [bool]$summary.screenshot_captured -and
        [bool]$summary.foreground_validation_passed -and
        ((-not [bool]$SyntheticVisualProbe) -or [bool]$summary.synthetic_visual_visible)
    if (-not [bool]$summary.screenshot_captured) {
        $summary.screenshot_invalid_reason = "screencap-missing"
    } elseif (-not [bool]$summary.foreground_validation_passed) {
        $summary.screenshot_invalid_reason = "foreground-validation-failed"
    } elseif ($SyntheticVisualProbe -and -not [bool]$summary.synthetic_visual_visible) {
        $summary.screenshot_invalid_reason = "synthetic-target-not-visible"
    } else {
        $summary.screenshot_invalid_reason = ""
    }

    $requiredFlags = if ($VideoOnly) {
        @(
            "start",
            "layer_created",
            "native_start_requested",
            "runtime_crash_false",
            "screenshot_captured"
        )
    } elseif ($SyntheticVisualProbe) {
        @(
            "start",
            "layer_created",
            "synthetic_visual_presented",
            "synthetic_visual_canvas_drawn",
            "synthetic_visual_camera_runtime_disabled",
            "camera_stack_particle_layer_suppressed",
            "camera_stack_particles_suppression_enabled",
            "camera_stack_particle_layer_visible_false",
            "camera_stack_particle_layer_started_false",
            "camera_stack_particle_suppress_failed_false",
            "camera_stack_particle_renderer_ready_false",
            "camera_stack_surface_layer_mode_absent",
            "runtime_crash_false"
        )
    } else {
        @(
            "start",
            "layer_created",
            "native_start_requested",
            "render_loop_ready",
            "camera_runtime_started",
            "ahb_properties",
            "resources_created",
            "ahb_imported",
            "first_frame_presented",
            "raw_frame_presented",
            "camera_frame_acquired",
            "stereo_source_camera_50_51",
            "left_camera_50",
            "right_camera_51",
            "output_raw_color_target_rect",
            "target_clip_policy",
            "mapping_mode_target_local_raster",
            "native_packed_left_target_rect",
            "native_packed_right_target_rect",
            "mono_duplicated_false",
            "private_shader_stack_false",
            "custom_projection_stack_false",
            "spatial_hands_and_controllers_manifest",
            "spatial_interaction_sdk_backend",
            "spatial_pointer_input_expected",
            "spatial_multimodal_disabled",
            "camera_stack_particle_layer_suppressed",
            "camera_stack_particles_suppression_enabled",
            "camera_stack_particle_layer_visible_false",
            "camera_stack_particle_layer_started_false",
            "camera_stack_particle_suppress_failed_false",
            "camera_stack_particle_renderer_ready_false",
            "camera_stack_surface_layer_mode_absent",
            "runtime_crash_false"
        )
    }
    if (-not $VideoOnly) {
        $requiredFlags += "spatial_controller_actions_disabled"
    }
    $requiredFlags += @(
        "foreground_validation_passed",
        "screenshot_captured",
        "screenshot_valid"
    )
    if ($RequirePublicMultiStackProjection -and -not $VideoOnly) {
        $requiredFlags += @(
            "public_multistack_contract_ready",
            "public_multistack_guide_targets_ready",
            "public_multistack_guide_targets_allocated",
            "public_multistack_guide_resources_ready",
            "public_multistack_pass_execution_ready",
            "public_guide_blur_runtime_ready",
            "public_multistack_opaque_guide_pipelines_ready",
            "public_multistack_opaque_projection_pipeline_ready",
            "public_multistack_opaque_projection_payload_execution_ready",
            "public_multistack_opaque_payload_execution_ready",
            "spatial_required_openxr_includes_passthrough",
            "spatial_required_openxr_includes_environment_depth",
            "spatial_native_passthrough_layer_active",
            "spatial_native_passthrough_prerequisite_active",
            "spatial_environment_depth_start_requested",
            "spatial_environment_depth_provider_created",
            "spatial_environment_depth_provider_bound",
            "spatial_environment_depth_acquire_thread_started",
            "spatial_environment_depth_first_frame",
            "spatial_environment_depth_valid_data",
            "spatial_environment_depth_valid_sample_count_nonzero",
            "public_multistack_depth_descriptor_shape_real",
            "public_multistack_depth_real_descriptor_bound",
            "public_multistack_depth_current_source_real",
            "public_multistack_depth_descriptor_frame_count_nonzero",
            "public_multistack_frame_projected",
            "public_multistack_projection_evidence",
            "public_multistack_projection_applied",
            "public_multistack_layer_cycle_enabled",
            "public_multistack_layer_cycle_elapsed",
            "public_multistack_depth_layer_policy_marker",
            "public_multistack_depth_layer_compare_visual_shader",
            "public_multistack_opaque_projection_target_space",
            "public_multistack_opaque_projection_left_target_rect",
            "public_multistack_opaque_projection_right_target_rect"
        )
    }
    if ($RequireSpatialVideoProjection) {
        if ($VideoOnly) {
            $requiredFlags += @(
                "spatial_video_projection_native_configured",
                "spatial_video_projection_start_requested",
                "spatial_video_projection_surface_created",
                "spatial_video_projection_mediacodec_started",
                "spatial_video_projection_decoded_frame_acquired",
                "spatial_video_projection_ahb_import_ready",
                "spatial_video_projection_rendered",
                "spatial_video_projection_same_surface",
                "spatial_video_projection_no_cpu_copy",
                "spatial_video_projection_video_only_render_loop_ready",
                "spatial_video_projection_video_only_presented",
                "camera_runtime_absent_when_video_only"
            )
        } else {
            $requiredFlags += @(
                "spatial_video_projection_native_configured",
                "spatial_video_projection_start_requested",
                "spatial_video_projection_surface_created",
                "spatial_video_projection_mediacodec_started",
                "spatial_video_projection_decoded_frame_acquired",
                "spatial_video_projection_ahb_import_ready",
                "spatial_video_projection_rendered",
                "spatial_video_projection_same_surface",
                "spatial_video_projection_before_camera",
                "spatial_video_projection_camera_alignment_preserved",
                "spatial_video_projection_no_cpu_copy"
            )
        }
    }
    if ($RequireSpatialAssetModel) {
        $requiredFlags += @(
            "spatial_asset_model_module_declared",
            "spatial_asset_model_entity_created",
            "spatial_asset_model_sdk_mesh_uri",
            "spatial_asset_model_private_source_not_packaged"
        )
    }
    if ($effectiveSkyboxEnabled) {
        $requiredFlags += @(
            "spatial_skybox_module_declared",
            "spatial_skybox_scene_configured",
            "spatial_skybox_mode_marker"
        )
        if ($effectiveSkyboxMode -eq "sample") {
            $requiredFlags += "spatial_skybox_sample_mesh_uri"
        } elseif ($effectiveSkyboxMode -eq "custom") {
            $requiredFlags += @(
                "spatial_skybox_custom_scene_mesh",
                "spatial_skybox_custom_background_order"
            )
        }
    }
    if ($RequireSpatialVirtualRoom) {
        $requiredFlags += @(
            "spatial_virtual_room_module_declared",
            "spatial_virtual_room_load_requested",
            "spatial_virtual_room_loaded",
            "spatial_virtual_room_scene_configured",
            "spatial_virtual_room_generic_module",
            "spatial_virtual_room_not_mruk",
            "camera_projection_wall_toggle_disabled",
            "camera_projection_wall_mode_declared",
            "legacy_launcher_panel_suppressed",
            "camera_projection_initial_full_fov_mode",
            "camera_projection_start_gate_virtual_room_loaded",
            "camera_projection_room_render_order"
        )
        if (-not $VideoOnly) {
            if ($projectionCarrierToken -eq "video-surface-panel-scene-object") {
                $requiredFlags += @(
                    "scene_panel_carrier",
                    "scene_panel_carrier_entity_created",
                    "scene_panel_carrier_ready"
                )
                if (-not $SyntheticVisualProbe) {
                    $requiredFlags += "scene_panel_carrier_native_start_requested"
                }
            } elseif ($projectionCarrierToken -eq "manual-panel-scene-object-custom-mesh") {
                $requiredFlags += @(
                    "scene_panel_carrier",
                    "scene_panel_carrier_entity_created",
                    "scene_panel_carrier_ready",
                    "manual_panel_scene_object_custom_mesh_carrier",
                    "manual_panel_scene_object_custom_mesh_ready",
                    "manual_panel_scene_mesh_creator",
                    "manual_panel_no_hittable",
                    "manual_panel_no_isdk_grabbable",
                    "manual_panel_input_click_buttons_zero",
                    "projection_panel_hittable_manual_noninteractive"
                )
                if (-not $SyntheticVisualProbe) {
                    $requiredFlags += "scene_panel_carrier_native_start_requested"
                }
            } else {
                $requiredFlags += @(
                    "scene_quad_layer_room_object_carrier",
                    "scene_quad_layer_created"
                )
            }
            if (-not $SyntheticVisualProbe) {
                $requiredFlags += "private_layer_controls_apply_to_wall_and_full_fov"
            }
        }
    }
    if (-not $AllowMissingMarkers) {
        foreach ($flag in $requiredFlags) {
            Assert-SummaryFlag -Summary $summary -Name $flag
        }
    }

    if ($StopAfterRun) {
        $stop = Invoke-AdbCommand -Name "stop Spatial SDK app" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure
        Save-Text -Path (Join-Path $OutDir "stop.txt") -Text $stop.output
        $disable = Invoke-AdbCommand -Name "disable raw camera projection probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe", "0") -AllowFailure
        Save-Text -Path (Join-Path $OutDir "disable-projection-probe.txt") -Text $disable.output
        $disableSynthetic = Invoke-AdbCommand -Name "disable synthetic carrier visual probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.synthetic_visual", "0") -AllowFailure
        Save-Text -Path (Join-Path $OutDir "disable-synthetic-visual-probe.txt") -Text $disableSynthetic.output
        $clearCarrier = Invoke-AdbCommand -Name "clear Spatial projection carrier" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.carrier", "none") -AllowFailure
        Save-Text -Path (Join-Path $OutDir "clear-projection-carrier.txt") -Text $clearCarrier.output
        $disableVideo = Invoke-AdbCommand -Name "disable Spatial video projection" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.video.enabled", "0") -AllowFailure
        Save-Text -Path (Join-Path $OutDir "disable-video-projection.txt") -Text $disableVideo.output
        $disableVideoOnly = Invoke-AdbCommand -Name "disable Spatial video-only projection probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.video_projection_probe", "0") -AllowFailure
        Save-Text -Path (Join-Path $OutDir "disable-video-only-projection-probe.txt") -Text $disableVideoOnly.output
        $disableAssetModel = Invoke-AdbCommand -Name "disable Spatial staged asset model" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.asset_model.enabled", "0") -AllowFailure
        Save-Text -Path (Join-Path $OutDir "disable-spatial-asset-model.txt") -Text $disableAssetModel.output
        $disableVirtualRoom = Invoke-AdbCommand -Name "disable Spatial packaged virtual room" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.virtual_room.enabled", "0") -AllowFailure
        Save-Text -Path (Join-Path $OutDir "disable-spatial-virtual-room.txt") -Text $disableVirtualRoom.output
        $disableSkybox = Invoke-AdbCommand -Name "disable Spatial skybox" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.skybox.enabled", "0") -AllowFailure
        Save-Text -Path (Join-Path $OutDir "disable-spatial-skybox.txt") -Text $disableSkybox.output
        $disableSkyboxMode = Invoke-AdbCommand -Name "disable Spatial skybox mode" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.skybox.mode", "none") -AllowFailure
        Save-Text -Path (Join-Path $OutDir "disable-spatial-skybox-mode.txt") -Text $disableSkyboxMode.output
    }

    $summary.status = if ($AllowMissingMarkers) { "completed" } else { "passed" }
} catch {
    $summary.status = "failed"
    $summary.error = $_.Exception.Message
    throw
} finally {
    if ($null -ne $logcatProcess -and -not $logcatProcess.HasExited) {
        try {
            Stop-Process -Id $logcatProcess.Id -Force
            $logcatProcess.WaitForExit()
        } catch {
            $summary.logcat_stop_error = $_.Exception.Message
        }
    }
    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $summaryPath
}

Write-Output "Spatial Camera Panel camera_hwb_projection_smoke evidence: $summaryPath"
Write-Output "APK_SHA256=$apkSha256"
Write-Output "OUT_DIR=$((Resolve-Path -LiteralPath $OutDir).Path)"
