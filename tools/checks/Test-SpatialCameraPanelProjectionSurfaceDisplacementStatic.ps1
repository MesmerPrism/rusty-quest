param([string]$RepoRoot)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path

function Read-RequiredText {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $path = Join-Path $repoRootPath $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing projection-surface displacement file: $path"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )

    if (-not $Text.Contains($Needle)) {
        throw "$Label is missing required token: $Needle"
    }
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )

    if ($Text.Contains($Needle)) {
        throw "$Label contains private/reference implementation token: $Needle"
    }
}

$kotlin = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\ProjectionSurfaceDisplacement.kt"
$kotlinTest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\ProjectionSurfaceDisplacementTest.kt"
$panel = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\PrivateLayerControlPanel.kt"
$coordinator = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialPrivateLayerControlCoordinator.kt"
$workflow = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialValidationWorkflowCoordinator.kt"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$native = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\projection_surface_displacement.rs"
$jni = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_probe.rs"
$runtime = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_public_multistack_runtime.rs"
$nativeBuild = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\build.rs"
$build = Read-RequiredText "tools\Build-SpatialCameraPanelAndroid.ps1"
$uiAction = Read-RequiredText "tools\Invoke-SpatialCameraPanelAndroidUiAction.ps1"
$docs = Read-RequiredText "docs\PROJECTION_SURFACE_DISPLACEMENT.md"

Assert-Contains "Kotlin contract" $kotlin 'CONTRACT_ID = "rusty.quest.projection-surface-displacement.v1"'
Assert-Contains "Kotlin contract" $kotlin "val off = ProjectionSurfaceDisplacement()"
Assert-Contains "Kotlin contract" $kotlin "maxDisplacementMeters = 0.06f"
Assert-Contains "Kotlin contract" $kotlin "maxDisplacementMeters = 0.18f"
Assert-Contains "Kotlin tests" $kotlinTest "offIsExactIdentity"
Assert-Contains "Kotlin tests" $kotlinTest "damagedValuesFailClosedAndStayFinite"
Assert-Contains "Layer panel" $panel 'Section("Projection Depth")'
Assert-Contains "Coordinator" $coordinator "updateProjectionSurfaceDisplacementNative"
Assert-Contains "Coordinator" $coordinator "status=projection-surface-displacement-submitted"
Assert-Contains "Validation workflow" $workflow '"projection-surface-displacement-off"'
Assert-Contains "Validation workflow" $workflow '"projection-surface-displacement-gentle"'
Assert-Contains "Validation workflow" $workflow '"projection-surface-displacement-deep"'
Assert-Contains "UI action wrapper" $uiAction '"projection-surface-displacement-off"'
Assert-Contains "UI action wrapper" $uiAction '"projection-surface-displacement-gentle"'
Assert-Contains "UI action wrapper" $uiAction '"projection-surface-displacement-deep"'
Assert-Contains "Activity JNI" $activity "nativeUpdateProjectionSurfaceDisplacement"
Assert-Contains "Native JNI" $jni "update_projection_surface_displacement_settings"

Assert-Contains "Native contract" $native "PROJECTION_SURFACE_DISPLACEMENT_CONTRACT_ID"
Assert-Contains "Native contract" $native "PROJECTION_SURFACE_GRID_VERTEX_COUNT"
Assert-Contains "Native contract" $native "size_of::<ProjectionSurfaceDisplacementUniform>() == 64"
Assert-Contains "Native contract" $native "projectionSurfaceDisplacementDisabledPath=original-fullscreen-triangle"

Assert-Contains "Vulkan runtime" $runtime "opaque_projection_displacement_pipeline"
Assert-Contains "Vulkan runtime" $runtime "create_projection_displacement_pipeline"
Assert-Contains "Vulkan runtime" $runtime "PROJECTION_SURFACE_GRID_VERTEX_COUNT"
Assert-Contains "Vulkan runtime" $runtime "if displacement_effective"
Assert-Contains "Vulkan runtime" $runtime "dst_binding(1)"
Assert-Contains "Vulkan runtime" $runtime "vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT"
Assert-Contains "Vulkan runtime" $runtime '"/spatial_opaque_projection.vert.spv"'
Assert-Contains "Vulkan runtime" $runtime '"/spatial_opaque_projection_video_compositor.vert.spv"'
Assert-Contains "Native build" $nativeBuild "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_VERTEX_SHADER"
Assert-Contains "Native build" $nativeBuild '"spatial_opaque_projection.vert.spv"'
Assert-Contains "Native build" $nativeBuild '"-DPRIVATE_LAYER_VIDEO_COMPOSITOR=0"'
Assert-Contains "Native build" $nativeBuild '"spatial_opaque_projection_video_compositor.vert.spv"'
Assert-Contains "Native build" $nativeBuild '"-DPRIVATE_LAYER_VIDEO_COMPOSITOR=1"'
Assert-Contains "Build wrapper" $build '[string]$OpaqueProjectionVertexShader = ""'
Assert-Contains "Build wrapper" $build "projection_vertex_shader"
Assert-Contains "Public documentation" $docs "original fullscreen triangle"
Assert-Contains "Public documentation" $docs "planar"
Assert-Contains "Public documentation" $docs "descriptor set 3, binding 1"

$publicOwner = $kotlin + $native + $runtime + $nativeBuild + $docs
foreach ($privateToken in @(
    "centered_legacy_strength",
    "morphovision_surface_signed_depth_m",
    "morphovision_projection.vert.glsl",
    "spatial-vr-strobe",
    "VrStrobeDepthDeformation"
)) {
    Assert-NotContains "Public projection-surface owner" $publicOwner $privateToken
}

Write-Output "Spatial Camera Panel projection-surface displacement static checks passed."
