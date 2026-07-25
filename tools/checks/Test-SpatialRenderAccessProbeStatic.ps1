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
        throw "Missing Spatial render-access probe file: $path"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param([string]$Text, [string]$Needle, [string]$Message)
    if (-not $Text.Contains($Needle)) { throw $Message }
}

$route = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialDiagnosticProbeRouteModule.kt"
$panel = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialPanelSurfaceMatrixProbeCoordinator.kt"
$quad = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialSdkQuadSurfaceProbeCoordinator.kt"
$external = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialExternalSwapchainProbeCoordinator.kt"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$shader = Read-RequiredText "apps\spatial-camera-panel-android\app\src\shaders\spatial_panel_surface_probe.frag"
$smoke = Read-RequiredText "tools\Invoke-SpatialCameraPanelRenderAccessSmoke.ps1"

Assert-Contains $route "dualConsumerVariantTested=true" "The panel matrix must report its dual-consumer variant."
Assert-Contains $route "useSwapchain-true-useTexture-true" "The panel matrix must declare a simultaneous swapchain and texture producer."
Assert-Contains $panel "spatial_panel_surface_probe.frag" "The dual-consumer variant must exercise the panel effect shader."
Assert-Contains $panel "setSkipRender(true)" "The panel matrix must discriminate scene rendering from compositor-layer rendering."
Assert-Contains $panel "setSkipRender(false)" "The panel matrix must restore scene rendering."
Assert-Contains $quad "SceneMaterial(texture" "The readable PanelSurface texture must be bound to a world-space material."
Assert-Contains $quad "panel_surface_texture_probe" "The readable texture must back an independently named scene object."
Assert-Contains $shader "texture(sampler2D(tex, samp), otc)" "The panel effect shader must sample the produced panel image."

Assert-Contains $external "does not convert the returned XrSwapchain into an SDK scene object" "The OpenXR capability probe must remain separate from SDK scene objects."
Assert-Contains $route "probeMode=standard-openxr-functions" "The access receipt must use ordinary typed OpenXR calls."
Assert-Contains $route "sessionGraphicsBindingGetterAvailable=false" "The access boundary must identify the missing session graphics-binding getter."
$privateProbePath = Join-Path $repoRootPath "apps\spatial-camera-panel-android\native-receipt\src\spatial_sdk_vulkan_access.rs"
if (Test-Path -LiteralPath $privateProbePath) {
    throw "Unexpected obsolete Vulkan access file: $privateProbePath"
}

Assert-Contains $smoke "adb-explicit-serial" "The device smoke must be serial scoped."
Assert-Contains $smoke "prior_properties" "The smoke must snapshot all mutated properties."
Assert-Contains $smoke "restore_verified" "The smoke must verify property restoration."
Assert-Contains $smoke '"debug.rustyquest.spatial.camera_hwb_projection_probe" = "false"' "The smoke must disable custom camera projection."
Assert-Contains $smoke '"debug.rustyquest.spatial.camera_hwb_projection_probe.video.enabled" = "false"' "The smoke must disable projection video."
Assert-Contains $smoke '"debug.rustyquest.spatial.video_projection_probe" = "false"' "The smoke must disable the video projection route."
Assert-Contains $smoke "Measure-PngVisualWitness" "The smoke must inspect captured pixels instead of accepting a non-empty PNG as visual proof."
Assert-Contains $smoke 'ScreenshotLabels @("swapchain-only", "dual-consumer")' "The smoke must capture both the paced compositor-only and dual-consumer windows."
Assert-Contains $smoke 'RequiredVisibleWitnessLabel "dual-consumer"' "The readable scene-texture branch must own the required visual witness."
Assert-Contains $smoke "structural-pass-visual-unconfirmed" "The smoke must keep structural success separate from visible-renderer acceptance."
Assert-Contains $smoke "needs-headset-or-supported-capture-witness" "The smoke must preserve an explicit visual follow-up when capture does not show the produced image."

Write-Host "Spatial render-access probe static validation passed"
