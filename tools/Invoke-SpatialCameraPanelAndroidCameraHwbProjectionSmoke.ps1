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
    [int]$VideoWidth = 3840,
    [int]$VideoHeight = 1920,
    [int]$VideoMaxImages = 3,
    [int]$VideoFpsCap = 30,
    [string]$VideoStereoLayout = "side-by-side-left-right",
    [double]$VideoOpacity = 1.0,
    [bool]$VideoLooping = $true,
    [switch]$VideoOnly,
    [switch]$SkipInstall,
    [switch]$ClearLogcat,
    [switch]$StopAfterRun,
    [switch]$AllowMissingMarkers,
    [switch]$RequirePublicMultiStackProjection,
    [switch]$RequireSpatialVideoProjection
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
$videoProjectionRequested = -not [string]::IsNullOrWhiteSpace($videoPathTrimmed)
if ($RequireSpatialVideoProjection -and -not $videoProjectionRequested) {
    throw "-RequireSpatialVideoProjection requires -VideoPath or RUSTY_QUEST_SPATIAL_VIDEO_PATH."
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
$apkSha256 = Get-FileSha256 -Path $resolvedApk
$summaryPath = Join-Path $OutDir "evidence-summary.json"
$tagLogcatStreamPath = Join-Path $OutDir "tag-logcat-stream.txt"
$tagLogcatErrorPath = Join-Path $OutDir "tag-logcat-stream.stderr.txt"
$pidLogcatPath = Join-Path $OutDir "pid-logcat.txt"
$allLogcatPath = Join-Path $OutDir "logcat-all.txt"
$windowFocusPath = Join-Path $OutDir "window-focus.txt"
$screenshotPath = Join-Path $OutDir "screencap.png"
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
    apk_path = (Resolve-Path -LiteralPath $resolvedApk).Path
    apk_sha256 = $apkSha256
    out_dir = (Resolve-Path -LiteralPath $OutDir).Path
    run_seconds = [Math]::Max(1, $RunSeconds)
    skipped_install = [bool]$SkipInstall
    clear_logcat_requested = [bool]$ClearLogcat
    stop_after_run = [bool]$StopAfterRun
    allow_missing_markers = [bool]$AllowMissingMarkers
    require_public_multistack_projection = [bool]$RequirePublicMultiStackProjection
    require_spatial_video_projection = [bool]$RequireSpatialVideoProjection
    spatial_video_only_requested = [bool]$VideoOnly
    reader_max_images = $readerMaxImagesClamped
    spatial_video_projection_requested = $videoProjectionRequested
    spatial_video_projection_path = $videoPathTrimmed
    spatial_video_projection_width = $videoWidthClamped
    spatial_video_projection_height = $videoHeightClamped
    spatial_video_projection_max_images = $videoMaxImagesClamped
    spatial_video_projection_fps_cap = $videoFpsCapClamped
    spatial_video_projection_looping = [bool]$VideoLooping
    spatial_video_projection_stereo_layout = $videoStereoLayoutToken
    spatial_video_projection_opacity = $videoOpacityClamped
    carrier = "scenequadlayer-createAsAndroid-vulkan-wsi"
    tag_logcat_stream_path = $tagLogcatStreamPath
    tag_logcat_error_path = $tagLogcatErrorPath
    pid_logcat_path = $pidLogcatPath
    all_logcat_path = $allLogcatPath
    window_focus_path = $windowFocusPath
    screenshot_path = $screenshotPath
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

    $setpropResults = @()
    $setpropResults += Invoke-AdbCommand -Name "disable luma camera HWB probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_probe", "0")
    $setpropResults += Invoke-AdbCommand -Name "configure raw camera projection probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe", $(if ($VideoOnly) { "0" } else { "1" }))
    $setpropResults += Invoke-AdbCommand -Name "configure Spatial video-only projection probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.video_projection_probe", $(if ($VideoOnly) { "1" } else { "0" }))
    $setpropResults += Invoke-AdbCommand -Name "set raw camera projection reader max images" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.reader_max_images", $readerMaxImagesClamped.ToString())
    $setpropResults += Invoke-AdbCommand -Name "select Spatial SDK interaction pointer input" -Arguments @("shell", "setprop", "debug.rustyquest.spatial_camera_panel.vr_input_system", "interaction_sdk")
    $setpropResults += Invoke-AdbCommand -Name "allow app controller probe to observe left/right input" -Arguments @("shell", "setprop", "debug.rustyquest.spatial_camera_panel.consume_left_right_input", "0")
    $setpropResults += Invoke-AdbCommand -Name "disable Spatial SDK multimodal input" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.multimodal_input.enabled", "0")
    $setpropResults += Invoke-AdbCommand -Name "disable native OpenXR controller action diagnostic" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.native_controller_actions.enabled", "0")
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
    Save-Text -Path (Join-Path $OutDir "setprops.json") -Text ($setpropResults | ConvertTo-Json -Depth 6)

    Invoke-AdbCommand -Name "force-stop Spatial SDK app" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null

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

    $launch = Invoke-AdbCommand -Name "launch raw camera projection probe" -Arguments @("shell", "am", "start", "-W", "-n", $Activity)
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

    Invoke-AdbCommand -Name "capture screenshot" -Arguments @("shell", "screencap", "-p", $remoteScreenshotPath) -AllowFailure | Out-Null
    Invoke-AdbCommand -Name "pull screenshot" -Arguments @("pull", $remoteScreenshotPath, $screenshotPath) -AllowFailure | Out-Null
    Invoke-AdbCommand -Name "remove remote screenshot" -Arguments @("shell", "rm", $remoteScreenshotPath) -AllowFailure | Out-Null

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

    $summary.start = if ($VideoOnly) {
        (Test-TextContains $evidenceText "status=start") -and (Test-TextContains $evidenceText "videoOnlySpatialProjection=true")
    } else {
        (Test-TextContains $evidenceText "status=start") -and (Test-TextContains $evidenceText "rawCameraProjectionProbe=true")
    }
    $summary.layer_created = Test-TextContains $evidenceText "status=raw-camera-projection-layer-created"
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
    $summary.projection_target_default_distance_one_meter = Test-TextContains $evidenceText "targetDistanceDefaultMeters=1.00"
    $summary.projection_target_stereo_horizontal_offset_readback = Test-TextContains $evidenceText "projectionTargetStereoHorizontalOffsetUv="
    $summary.projection_target_stereo_horizontal_offset_default = Test-TextContains $evidenceText "projectionTargetStereoHorizontalOffsetDefaultUv=0.046320"
    $summary.projection_target_left_right_offset_readback = (Test-TextContains $evidenceText "projectionTargetLeftOffsetUv=") -and (Test-TextContains $evidenceText "projectionTargetRightOffsetUv=")
    $summary.projection_target_stereo_horizontal_offset_control_disabled = Test-TextContains $evidenceText "stereoHorizontalOffsetJoystickInput=disabled-default-locked-left-stick-y-reserved-for-panel-scroll"
    $summary.mono_duplicated_false = Test-TextContains $evidenceText "monoDuplicated=false"
    $summary.private_shader_stack_false = Test-TextContains $evidenceText "privateShaderStack=false"
    $summary.custom_projection_stack_false = Test-TextContains $evidenceText "customProjectionStack=false"
    $summary.spatial_hands_and_controllers_manifest = Test-TextContains $evidenceText "spatialHandsAndControllersManifest=true"
    $summary.spatial_interaction_sdk_backend = Test-TextContains $evidenceText "spatialVrInputSystem=interaction_sdk"
    $summary.spatial_pointer_input_expected = Test-TextContains $evidenceText "spatialPointerInputExpected=true"
    $summary.spatial_controller_actions_disabled = Test-TextContains $evidenceText "nativeControllerActionBridge=false"
    $summary.spatial_multimodal_disabled = (Test-TextContains $evidenceText "spatialRequiredOpenXrExtensions=none") -and (Test-TextContains $evidenceText "spatialMultimodalInputRequest=false")
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
    $summary.public_multistack_frame_projected = Test-TextContains $evidenceText "status=public-multistack-frame-projected"
    $summary.public_multistack_projection_evidence = Test-TextContains $evidenceText "status=public-multistack-projection-evidence"
    $summary.public_multistack_projection_applied = Test-TextContains $evidenceText "publicMultiStackProjectionApplied=true"
    $summary.public_multistack_layer_cycle_enabled = Test-TextContains $evidenceText "publicMultiStackLayerCycleEnabled=true"
    $summary.public_multistack_layer_cycle_elapsed = Test-TextContains $evidenceText "publicMultiStackLayerCycleElapsedSeconds="
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
    $summary.runtime_crash_false = (Test-TextContains $evidenceText "runtimeCrash=false") -and -not ($evidenceText -match "AndroidRuntime|FATAL|render-failed")
    $summary.screenshot_captured = Test-Path -LiteralPath $screenshotPath

    $requiredFlags = if ($VideoOnly) {
        @(
            "start",
            "layer_created",
            "native_start_requested",
            "runtime_crash_false",
            "screenshot_captured"
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
            "spatial_controller_actions_disabled",
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
            "public_multistack_depth_fallback_ready",
            "public_multistack_frame_projected",
            "public_multistack_projection_evidence",
            "public_multistack_projection_applied",
            "public_multistack_layer_cycle_enabled",
            "public_multistack_layer_cycle_elapsed",
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
        $disableVideo = Invoke-AdbCommand -Name "disable Spatial video projection" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.video.enabled", "0") -AllowFailure
        Save-Text -Path (Join-Path $OutDir "disable-video-projection.txt") -Text $disableVideo.output
        $disableVideoOnly = Invoke-AdbCommand -Name "disable Spatial video-only projection probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.video_projection_probe", "0") -AllowFailure
        Save-Text -Path (Join-Path $OutDir "disable-video-only-projection-probe.txt") -Text $disableVideoOnly.output
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
