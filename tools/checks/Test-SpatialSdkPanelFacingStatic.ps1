param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'

function Read-RequiredText([string]$RelativePath) {
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing panel-facing file: $RelativePath" }
    Get-Content -Raw -LiteralPath $path
}
function Require-Text([string]$Label, [string]$Text, [string]$Pattern) {
    if ($Text -notmatch $Pattern) { throw "$Label is missing: $Pattern" }
}

$shared = Read-RequiredText 'apps\spatial-camera-panel-android\spatial-sdk-shared\src\main\java\io\github\mesmerprism\rustyquest\spatial_sdk_shared\SpatialPanelFacing.kt'
$sharedTest = Read-RequiredText 'apps\spatial-camera-panel-android\spatial-sdk-shared\src\test\java\io\github\mesmerprism\rustyquest\spatial_sdk_shared\SpatialPanelFacingTest.kt'
$cameraPlacement = Read-RequiredText 'apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialPanelPlacementModule.kt'
$cameraPose = Read-RequiredText 'apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialPanelPoseCoordinator.kt'
$strobeActivity = Read-RequiredText 'apps\spatial-vr-strobe-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_vr_strobe\SpatialVrStrobeActivity.kt'
$strobePanel = Read-RequiredText 'apps\spatial-vr-strobe-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_vr_strobe\SpatialVrStrobePanelModule.kt'

Require-Text 'shared facing' $shared 'Quaternion\.lookRotationAroundY'
Require-Text 'shared facing' $shared 'FALLBACK_EYE_HEIGHT_METERS = 1\.20f'
Require-Text 'shared facing tests' $sharedTest 'viewerRelativePoseKeepsRequestedDistance'
Require-Text 'Camera placement' $cameraPlacement 'spatial_sdk_shared\.SpatialPanelFacing'
Require-Text 'Camera pose coordinator' $cameraPose 'spatial_sdk_shared\.SpatialPanelFacing'
Require-Text 'Strobe Activity' $strobeActivity 'spatial_sdk_shared\.SpatialPanelFacing'
Require-Text 'Strobe Activity' $strobeActivity 'VR_STROBE_PANEL_COMFORT_DISTANCE_METERS'
Require-Text 'Strobe Activity' $strobeActivity 'panelPoseSnapshot\(\)'
Require-Text 'Strobe Activity' $strobeActivity 'setPanelVisible\(!panelVisible'
Require-Text 'Strobe panel' $strobePanel 'VR_STROBE_PANEL_LAYER_Z_INDEX = 100'
Require-Text 'Strobe panel' $strobePanel 'VR_STROBE_PANEL_COMFORT_DISTANCE_METERS = 0\.82f'
Require-Text 'Strobe panel' $strobePanel 'PanelShapeLayerBlendType\.OPAQUE'
Require-Text 'Strobe panel' $strobePanel 'ButtonBits\.ButtonTriggerL or ButtonBits\.ButtonTriggerR'

[pscustomobject]@{
    schema = 'rusty.quest.spatial_sdk_panel_facing.static.v2'
    status = 'pass'
    authority_module = ':spatial-sdk-shared'
    consumers = @(':app', ':strobe-app')
    strobe_panel = 'opaque-layer-z100-at-0.82m'
} | ConvertTo-Json -Depth 4
