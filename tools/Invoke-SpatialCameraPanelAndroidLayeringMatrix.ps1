param(
    [string]$RepoRoot,
    [string]$ApkPath = "target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk",
    [string]$OutDir = "",
    [ValidateSet("minimal", "manual-carrier", "implemented-carriers", "full-implemented")]
    [string]$MatrixPreset = "implemented-carriers",
    [int]$RunSeconds = 12,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$VideoPath = $env:RUSTY_QUEST_SPATIAL_VIDEO_PATH,
    [string]$VideoSourcePath = $env:RUSTY_QUEST_SPATIAL_VIDEO_SOURCE_PATH,
    [string]$AssetMeshUri = $env:RUSTY_QUEST_SPATIAL_ASSET_MODEL_URI,
    [string]$AssetSourcePath = $env:RUSTY_QUEST_SPATIAL_ASSET_MODEL_SOURCE_PATH,
    [string]$AssetConvertedMeshPath = $env:RUSTY_QUEST_SPATIAL_ASSET_MODEL_CONVERTED_MESH_PATH,
    [string]$AssetPositionM = "-0.55;1.15;-1.35",
    [string]$AssetRotationDegrees = "0.0;180.0;0.0",
    [double]$AssetScale = 0.25,
    [bool]$AssetGrabbable = $true,
    [string]$PrivateInputsManifestPath = "local-artifacts\spatial-camera-panel-private-inputs.json",
    [switch]$UsePrivateInputsManifest,
    [switch]$IncludeAssetModel,
    [switch]$RequireSpatialVideoProjection,
    [bool]$SyntheticVisualProbe = $true,
    [int]$MinimumSyntheticRedPixels = 1000,
    [int]$MinimumSyntheticGreenPixels = 1000,
    [double]$MinimumSyntheticTargetPixelRatio = 0.01,
    [switch]$ClearLogcat,
    [switch]$AllowMissingMarkers,
    [switch]$ContinueOnCaseFailure,
    [switch]$SkipInstallAfterFirstRun,
    [switch]$SkipPermissionPregrantAfterFirstRun,
    [switch]$LeaveLastRunActive
)

$ErrorActionPreference = "Stop"

function Save-Json {
    param(
        [Parameter(Mandatory=$true)]$Value,
        [Parameter(Mandatory=$true)][string]$Path
    )
    $Value | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -Path $Path
}

function Resolve-RepoRoot {
    param([string]$Value)
    if (-not [string]::IsNullOrWhiteSpace($Value)) {
        return (Resolve-Path -LiteralPath $Value).Path
    }
    return (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

function Read-PrivateInputsManifest {
    param(
        [Parameter(Mandatory=$true)][string]$RepoRootPath,
        [Parameter(Mandatory=$true)][string]$Path
    )
    $manifestPath = if ([System.IO.Path]::IsPathRooted($Path)) {
        $Path
    } else {
        Join-Path $RepoRootPath $Path
    }
    if (-not (Test-Path -LiteralPath $manifestPath)) {
        throw "Private inputs manifest not found: $manifestPath"
    }
    return Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
}

function New-MatrixCase {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Carrier,
        [bool]$EnableVirtualRoom,
        [bool]$EnableSkybox,
        [ValidateSet("none", "sample", "custom")]
        [string]$SkyboxMode = "none",
        [string]$Goal
    )
    $effectiveSkyboxMode = if ($EnableSkybox -and $SkyboxMode -eq "none") { "sample" } else { $SkyboxMode }
    [pscustomobject]@{
        name = $Name
        projection_carrier = $Carrier
        enable_virtual_room = $EnableVirtualRoom
        enable_skybox = ($effectiveSkyboxMode -ne "none")
        skybox_mode = $effectiveSkyboxMode
        goal = $Goal
    }
}

$repoRootPath = Resolve-RepoRoot -Value $RepoRoot
$smokeScript = Join-Path $repoRootPath "tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1"
if (-not (Test-Path -LiteralPath $smokeScript)) {
    throw "Smoke wrapper not found: $smokeScript"
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRootPath "local-artifacts\spatial-camera-panel-headset\$stamp-layering-matrix"
} elseif (-not [System.IO.Path]::IsPathRooted($OutDir)) {
    $OutDir = Join-Path $repoRootPath $OutDir
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$outDirPath = (Resolve-Path -LiteralPath $OutDir).Path

if ($UsePrivateInputsManifest) {
    $manifest = Read-PrivateInputsManifest -RepoRootPath $repoRootPath -Path $PrivateInputsManifestPath
    if ([string]::IsNullOrWhiteSpace($VideoSourcePath)) {
        $VideoSourcePath = [string]$manifest.inputs.video.source_path
    }
    if ([string]::IsNullOrWhiteSpace($AssetSourcePath)) {
        $AssetSourcePath = [string]$manifest.inputs.model.glb_source_path
    }
}

$videoProjectionRequested =
    (-not [string]::IsNullOrWhiteSpace($VideoPath)) -or
    (-not [string]::IsNullOrWhiteSpace($VideoSourcePath))
if ($RequireSpatialVideoProjection -and -not $videoProjectionRequested) {
    throw "-RequireSpatialVideoProjection requires -VideoPath, -VideoSourcePath, or -UsePrivateInputsManifest with a video input."
}

$assetModelRequested =
    [bool]$IncludeAssetModel -and (
        (-not [string]::IsNullOrWhiteSpace($AssetMeshUri)) -or
        (-not [string]::IsNullOrWhiteSpace($AssetSourcePath))
    )
if ($IncludeAssetModel -and -not $assetModelRequested) {
    throw "-IncludeAssetModel requires -AssetMeshUri, -AssetSourcePath, or -UsePrivateInputsManifest with a model input."
}

$cases = @()
switch ($MatrixPreset) {
    "manual-carrier" {
        $cases += New-MatrixCase `
            -Name "manual-panel-custom-mesh-no-room-no-skybox" `
            -Carrier "manual-panel-scene-object-custom-mesh" `
            -EnableVirtualRoom $false `
            -EnableSkybox $false `
            -SkyboxMode "none" `
            -Goal "Baseline the manual PanelSceneObject custom mesh carrier without room or skybox."
        $cases += New-MatrixCase `
            -Name "manual-panel-custom-mesh-sample-skybox-only" `
            -Carrier "manual-panel-scene-object-custom-mesh" `
            -EnableVirtualRoom $false `
            -EnableSkybox $true `
            -SkyboxMode "sample" `
            -Goal "Check whether the manual custom mesh carrier survives the sample mesh://skybox without authored room geometry."
        $cases += New-MatrixCase `
            -Name "manual-panel-custom-mesh-custom-skybox-only" `
            -Carrier "manual-panel-scene-object-custom-mesh" `
            -EnableVirtualRoom $false `
            -EnableSkybox $true `
            -SkyboxMode "custom" `
            -Goal "Check whether the manual custom mesh carrier survives the custom background skybox without authored room geometry."
        $cases += New-MatrixCase `
            -Name "manual-panel-custom-mesh-room-sample-skybox" `
            -Carrier "manual-panel-scene-object-custom-mesh" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "sample" `
            -Goal "Test the manual custom mesh carrier in the authored room plus sample mesh://skybox case."
        $cases += New-MatrixCase `
            -Name "manual-panel-custom-mesh-room-custom-skybox" `
            -Carrier "manual-panel-scene-object-custom-mesh" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "custom" `
            -Goal "Test the manual custom mesh carrier in the authored room plus custom background skybox case."
    }
    "minimal" {
        $cases += New-MatrixCase `
            -Name "scenequadlayer-no-room-no-skybox" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $false `
            -EnableSkybox $false `
            -SkyboxMode "none" `
            -Goal "Baseline the direct SceneQuadLayer carrier with no room and no skybox."
        $cases += New-MatrixCase `
            -Name "scenequadlayer-sample-skybox-only" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $false `
            -EnableSkybox $true `
            -SkyboxMode "sample" `
            -Goal "Isolate the sample mesh://skybox interaction with the direct SceneQuadLayer carrier."
        $cases += New-MatrixCase `
            -Name "scenequadlayer-custom-skybox-only" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $false `
            -EnableSkybox $true `
            -SkyboxMode "custom" `
            -Goal "Isolate the custom backgrounded skybox interaction with the direct SceneQuadLayer carrier."
        $cases += New-MatrixCase `
            -Name "scenequadlayer-room-sample-skybox" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "sample" `
            -Goal "Reproduce the direct SceneQuadLayer negative room plus sample skybox comparison."
        $cases += New-MatrixCase `
            -Name "scenequadlayer-room-custom-skybox" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "custom" `
            -Goal "Check whether the custom backgrounded skybox changes direct SceneQuadLayer behavior with authored room geometry."
        $cases += New-MatrixCase `
            -Name "video-panel-room-sample-skybox" `
            -Carrier "video-surface-panel-scene-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "sample" `
            -Goal "Exercise the foreground-capable panel scene object carrier in the same room plus sample skybox environment."
        $cases += New-MatrixCase `
            -Name "video-panel-room-custom-skybox" `
            -Carrier "video-surface-panel-scene-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "custom" `
            -Goal "Exercise the foreground-capable panel scene object carrier in the same room plus custom skybox environment."
    }
    "implemented-carriers" {
        $cases += New-MatrixCase `
            -Name "scenequadlayer-no-room-no-skybox" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $false `
            -EnableSkybox $false `
            -SkyboxMode "none" `
            -Goal "Baseline the direct projection carrier without room or skybox."
        $cases += New-MatrixCase `
            -Name "scenequadlayer-sample-skybox-only" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $false `
            -EnableSkybox $true `
            -SkyboxMode "sample" `
            -Goal "Isolate the sample mesh://skybox interaction with direct SceneQuadLayer."
        $cases += New-MatrixCase `
            -Name "scenequadlayer-custom-skybox-only" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $false `
            -EnableSkybox $true `
            -SkyboxMode "custom" `
            -Goal "Isolate the custom backgrounded skybox interaction with direct SceneQuadLayer."
        $cases += New-MatrixCase `
            -Name "scenequadlayer-room-sample-skybox" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "sample" `
            -Goal "Reproduce the direct SceneQuadLayer negative room plus sample skybox comparison."
        $cases += New-MatrixCase `
            -Name "scenequadlayer-room-custom-skybox" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "custom" `
            -Goal "Check the direct SceneQuadLayer carrier with authored room plus custom backgrounded skybox."
        $cases += New-MatrixCase `
            -Name "video-panel-room-sample-skybox" `
            -Carrier "video-surface-panel-scene-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "sample" `
            -Goal "Exercise the foreground-capable panel scene object carrier in the same room plus sample skybox environment."
        $cases += New-MatrixCase `
            -Name "video-panel-room-custom-skybox" `
            -Carrier "video-surface-panel-scene-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "custom" `
            -Goal "Exercise the foreground-capable panel scene object carrier in the same room plus custom skybox environment."
    }
    "full-implemented" {
        $cases += New-MatrixCase `
            -Name "scenequadlayer-no-room-no-skybox" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $false `
            -EnableSkybox $false `
            -SkyboxMode "none" `
            -Goal "Baseline the direct projection carrier without room or skybox."
        $cases += New-MatrixCase `
            -Name "scenequadlayer-sample-skybox-only" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $false `
            -EnableSkybox $true `
            -SkyboxMode "sample" `
            -Goal "Isolate the sample mesh://skybox interaction with direct SceneQuadLayer."
        $cases += New-MatrixCase `
            -Name "scenequadlayer-custom-skybox-only" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $false `
            -EnableSkybox $true `
            -SkyboxMode "custom" `
            -Goal "Isolate the custom backgrounded skybox interaction with direct SceneQuadLayer."
        $cases += New-MatrixCase `
            -Name "scenequadlayer-room-only" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $false `
            -SkyboxMode "none" `
            -Goal "Isolate authored room geometry without the optional sample skybox toggle."
        $cases += New-MatrixCase `
            -Name "scenequadlayer-room-sample-skybox" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "sample" `
            -Goal "Reproduce the direct SceneQuadLayer negative room plus sample skybox comparison."
        $cases += New-MatrixCase `
            -Name "scenequadlayer-room-custom-skybox" `
            -Carrier "scenequadlayer-room-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "custom" `
            -Goal "Check the direct SceneQuadLayer carrier with authored room plus custom backgrounded skybox."
        $cases += New-MatrixCase `
            -Name "video-panel-room-only" `
            -Carrier "video-surface-panel-scene-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $false `
            -SkyboxMode "none" `
            -Goal "Check foreground panel carrier with authored room only."
        $cases += New-MatrixCase `
            -Name "video-panel-room-sample-skybox" `
            -Carrier "video-surface-panel-scene-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "sample" `
            -Goal "Exercise the foreground-capable panel scene object carrier in the same room plus sample skybox environment."
        $cases += New-MatrixCase `
            -Name "video-panel-room-custom-skybox" `
            -Carrier "video-surface-panel-scene-object" `
            -EnableVirtualRoom $true `
            -EnableSkybox $true `
            -SkyboxMode "custom" `
            -Goal "Exercise the foreground-capable panel scene object carrier in the same room plus custom skybox environment."
    }
}

$matrixSummary = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel.layering_matrix.v1"
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    status = "started"
    repo_root = $repoRootPath
    out_dir = $outDirPath
    matrix_preset = $MatrixPreset
    smoke_wrapper = $smokeScript
    serial = $Serial
    adb_path = $Adb
    adb_server_port = $AdbServerPort
    apk_path = $ApkPath
    run_seconds = $RunSeconds
    use_private_inputs_manifest = [bool]$UsePrivateInputsManifest
    private_inputs_manifest_path = $PrivateInputsManifestPath
    video_projection_requested = $videoProjectionRequested
    require_spatial_video_projection = [bool]$RequireSpatialVideoProjection
    synthetic_visual_probe = [bool]$SyntheticVisualProbe
    synthetic_visual_pixel_thresholds = [ordered]@{
        minimum_red_pixels = $MinimumSyntheticRedPixels
        minimum_green_pixels = $MinimumSyntheticGreenPixels
        minimum_target_pixel_ratio = $MinimumSyntheticTargetPixelRatio
    }
    skybox_modes = @("none", "sample", "custom")
    skybox_mode_property = "debug.rustyquest.spatial.skybox.mode"
    asset_model_requested = $assetModelRequested
    include_asset_model = [bool]$IncludeAssetModel
    asset_position_m = $AssetPositionM
    asset_rotation_degrees = $AssetRotationDegrees
    asset_scale = $AssetScale
    asset_grabbable = [bool]$AssetGrabbable
    continue_on_case_failure = [bool]$ContinueOnCaseFailure
    cases = @()
}
$matrixSummaryPath = Join-Path $outDirPath "layering-matrix-summary.json"
Save-Json -Value $matrixSummary -Path $matrixSummaryPath

for ($i = 0; $i -lt $cases.Count; $i++) {
    $case = $cases[$i]
    $caseOutDir = Join-Path $outDirPath $case.name
    New-Item -ItemType Directory -Force -Path $caseOutDir | Out-Null

    $smokeArgs = @(
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        $smokeScript,
        "-RepoRoot",
        $repoRootPath,
        "-ApkPath",
        $ApkPath,
        "-OutDir",
        $caseOutDir,
        "-RunSeconds",
        $RunSeconds.ToString(),
        "-AllowLegacyLooseInputs",
        "-ProjectionCarrier",
        $case.projection_carrier
    )
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        $smokeArgs += @("-Serial", $Serial)
    }
    if (-not [string]::IsNullOrWhiteSpace($Adb)) {
        $smokeArgs += @("-Adb", $Adb)
    }
    if (-not [string]::IsNullOrWhiteSpace($AdbServerPort)) {
        $smokeArgs += @("-AdbServerPort", $AdbServerPort)
    }
    if (-not [string]::IsNullOrWhiteSpace($VideoPath)) {
        $smokeArgs += @("-VideoPath", $VideoPath)
    }
    if (-not [string]::IsNullOrWhiteSpace($VideoSourcePath)) {
        $smokeArgs += @("-VideoSourcePath", $VideoSourcePath)
    }
    if ($videoProjectionRequested -and $RequireSpatialVideoProjection) {
        $smokeArgs += "-RequireSpatialVideoProjection"
    }
    if ($SyntheticVisualProbe) {
        $smokeArgs += @(
            "-SyntheticVisualProbe",
            "-MinimumSyntheticRedPixels",
            $MinimumSyntheticRedPixels.ToString(),
            "-MinimumSyntheticGreenPixels",
            $MinimumSyntheticGreenPixels.ToString(),
            "-MinimumSyntheticTargetPixelRatio",
            $MinimumSyntheticTargetPixelRatio.ToString([Globalization.CultureInfo]::InvariantCulture)
        )
    }
    if ($assetModelRequested) {
        if (-not [string]::IsNullOrWhiteSpace($AssetMeshUri)) {
            $smokeArgs += @("-AssetMeshUri", $AssetMeshUri)
        }
        if (-not [string]::IsNullOrWhiteSpace($AssetSourcePath)) {
            $smokeArgs += @("-AssetSourcePath", $AssetSourcePath)
        }
        if (-not [string]::IsNullOrWhiteSpace($AssetConvertedMeshPath)) {
            $smokeArgs += @("-AssetConvertedMeshPath", $AssetConvertedMeshPath)
        }
        $smokeArgs += @(
            "-AssetPositionM",
            $AssetPositionM,
            "-AssetRotationDegrees",
            $AssetRotationDegrees,
            "-AssetScale",
            $AssetScale.ToString([Globalization.CultureInfo]::InvariantCulture)
        )
        if (-not $AssetGrabbable) {
            $smokeArgs += "-AssetGrabbable:`$false"
        }
        $smokeArgs += "-RequireSpatialAssetModel"
    }
    if ([bool]$case.enable_virtual_room) {
        $smokeArgs += @("-EnableVirtualRoom", "-RequireSpatialVirtualRoom")
    }
    if ([bool]$case.enable_skybox) {
        $smokeArgs += "-EnableSkybox"
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$case.skybox_mode)) {
        $smokeArgs += @("-SkyboxMode", [string]$case.skybox_mode)
    }
    if ($ClearLogcat) {
        $smokeArgs += "-ClearLogcat"
    }
    if ($AllowMissingMarkers) {
        $smokeArgs += "-AllowMissingMarkers"
    }
    if ($i -gt 0 -and $SkipInstallAfterFirstRun) {
        $smokeArgs += "-SkipInstall"
    }
    if ($i -gt 0 -and $SkipPermissionPregrantAfterFirstRun) {
        $smokeArgs += "-SkipPermissionPregrant"
    }
    $isLast = $i -eq ($cases.Count - 1)
    if (-not ($LeaveLastRunActive -and $isLast)) {
        $smokeArgs += "-StopAfterRun"
    }

    $caseResult = [ordered]@{
        name = $case.name
        goal = $case.goal
        projection_carrier = $case.projection_carrier
        enable_virtual_room = [bool]$case.enable_virtual_room
        enable_skybox = [bool]$case.enable_skybox
        skybox_mode = [string]$case.skybox_mode
        out_dir = $caseOutDir
        status = "started"
        command = "powershell " + (($smokeArgs | ForEach-Object {
            if ($_ -match "\s") { "'" + $_.Replace("'", "''") + "'" } else { $_ }
        }) -join " ")
    }

    try {
        $output = & pwsh @smokeArgs 2>&1
        $exitCode = $LASTEXITCODE
        $caseResult.exit_code = $exitCode
        $caseResult.output = ($output -join "`n")
        $caseSummaryPath = Join-Path $caseOutDir "evidence-summary.json"
        $caseResult.evidence_summary_path = $caseSummaryPath
        if (Test-Path -LiteralPath $caseSummaryPath) {
            $caseSummary = Get-Content -Raw -LiteralPath $caseSummaryPath | ConvertFrom-Json
            $caseResult.smoke_status = [string]$caseSummary.status
            $caseResult.screenshot_path = [string]$caseSummary.screenshot_path
            $caseResult.screenshot_captured = [bool]$caseSummary.screenshot_captured
            $caseResult.detected_carrier = [string]$caseSummary.carrier
            $caseResult.foreground_validation_passed = [bool]$caseSummary.foreground_validation_passed
            $caseResult.foreground_pid_live = [bool]$caseSummary.foreground_pid_live
            $caseResult.foreground_window_focus_matches = [bool]$caseSummary.foreground_window_focus_matches
            $caseResult.foreground_activity_resumed_matches = [bool]$caseSummary.foreground_activity_resumed_matches
            $caseResult.screenshot_valid = [bool]$caseSummary.screenshot_valid
            $caseResult.screenshot_invalid_reason = [string]$caseSummary.screenshot_invalid_reason
            $caseResult.synthetic_visual_visible = [bool]$caseSummary.synthetic_visual_visible
            $caseResult.synthetic_visual_presented = [bool]$caseSummary.synthetic_visual_presented
            $caseResult.screenshot_pixel_classification_path = [string]$caseSummary.screenshot_pixel_classification_path
            $caseResult.spatial_video_projection_rendered = [bool]$caseSummary.spatial_video_projection_rendered
            $caseResult.spatial_asset_model_entity_created = [bool]$caseSummary.spatial_asset_model_entity_created
            $caseResult.spatial_virtual_room_loaded = [bool]$caseSummary.spatial_virtual_room_loaded
            $caseResult.spatial_skybox_scene_configured = [bool]$caseSummary.spatial_skybox_scene_configured
            $caseResult.spatial_skybox_mode = [string]$caseSummary.spatial_skybox_mode
            $caseResult.spatial_skybox_mode_marker = [bool]$caseSummary.spatial_skybox_mode_marker
            $caseResult.spatial_skybox_sample_mesh_uri = [bool]$caseSummary.spatial_skybox_sample_mesh_uri
            $caseResult.spatial_skybox_custom_scene_mesh = [bool]$caseSummary.spatial_skybox_custom_scene_mesh
            $caseResult.camera_projection_room_render_order = [bool]$caseSummary.camera_projection_room_render_order
            $caseResult.projection_panel_input_pass_through = [bool]$caseSummary.projection_panel_input_pass_through
            $caseResult.projection_panel_hittable_no_collision = [bool]$caseSummary.projection_panel_hittable_no_collision
            $caseResult.projection_panel_hittable_manual_noninteractive = [bool]$caseSummary.projection_panel_hittable_manual_noninteractive
            $caseResult.manual_panel_scene_object_custom_mesh_carrier = [bool]$caseSummary.manual_panel_scene_object_custom_mesh_carrier
            $caseResult.manual_panel_scene_object_custom_mesh_ready = [bool]$caseSummary.manual_panel_scene_object_custom_mesh_ready
            $caseResult.manual_panel_scene_mesh_creator = [bool]$caseSummary.manual_panel_scene_mesh_creator
            $caseResult.manual_panel_no_hittable = [bool]$caseSummary.manual_panel_no_hittable
            $caseResult.manual_panel_no_isdk_grabbable = [bool]$caseSummary.manual_panel_no_isdk_grabbable
            $caseResult.manual_panel_input_click_buttons_zero = [bool]$caseSummary.manual_panel_input_click_buttons_zero
            $caseResult.private_layer_panel_button_selected = [bool]$caseSummary.private_layer_panel_button_selected
            $caseResult.private_layer_panel_override_native_updated = [bool]$caseSummary.private_layer_panel_override_native_updated
        }
        if ($exitCode -eq 0) {
            $caseResult.status = "completed"
        } else {
            $caseResult.status = "failed"
        }
    } catch {
        $caseResult.status = "failed"
        $caseResult.error = $_.Exception.Message
    }

    $matrixSummary.cases += [pscustomobject]$caseResult
    Save-Json -Value $matrixSummary -Path $matrixSummaryPath

    if ($caseResult.status -eq "failed" -and -not $AllowMissingMarkers -and -not $ContinueOnCaseFailure) {
        $matrixSummary.status = "failed"
        $matrixSummary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
        Save-Json -Value $matrixSummary -Path $matrixSummaryPath
        throw "Layering matrix case failed: $($case.name). See $caseOutDir"
    }
}

$failedCaseCount = @($matrixSummary.cases | Where-Object { $_.status -eq "failed" }).Count
$matrixSummary.failed_case_count = $failedCaseCount
$matrixSummary.status = if ($failedCaseCount -gt 0) { "failed" } else { "completed" }
$matrixSummary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
Save-Json -Value $matrixSummary -Path $matrixSummaryPath

Write-Output "Spatial Camera Panel layering matrix evidence: $matrixSummaryPath"
Write-Output "OUT_DIR=$outDirPath"
