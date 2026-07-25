param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'

function Require-File([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Required Strobe file missing: $Path" }
}
function Require-Text([string]$Path, [string]$Pattern) {
    $content = Get-Content -Raw -LiteralPath $Path
    if ($content -notmatch $Pattern) { throw "Required Strobe pattern '$Pattern' missing from $Path" }
}
function Reject-Text([string]$Path, [string]$Pattern) {
    $content = Get-Content -Raw -LiteralPath $Path
    if ($content -match $Pattern) { throw "Forbidden Strobe pattern '$Pattern' found in $Path" }
}

$appRoot = Join-Path $RepoRoot 'apps\spatial-vr-strobe-android'
$legacyRoot = Join-Path $RepoRoot 'apps\spatial-camera-panel-android'
$sourceRoot = Join-Path $appRoot 'app\src\main\java\io\github\mesmerprism\rustyquest\spatial_vr_strobe'
$testRoot = Join-Path $appRoot 'app\src\test\java\io\github\mesmerprism\rustyquest\spatial_vr_strobe'
$activity = Join-Path $sourceRoot 'SpatialVrStrobeActivity.kt'
$coordinator = Join-Path $sourceRoot 'SpatialVrStrobeCoordinator.kt'
$controller = Join-Path $sourceRoot 'VrStrobeControllerInputCoordinator.kt'
$controllerAdapter = Join-Path $sourceRoot 'SpatialVrStrobeControllerAdapter.kt'
$panel = Join-Path $sourceRoot 'SpatialVrStrobePanelModule.kt'
$controlPanel = Join-Path $sourceRoot 'VrStrobeControlPanel.kt'
$featureRoute = Join-Path $sourceRoot 'VrStrobeFeatureRoute.kt'
$safety = Join-Path $sourceRoot 'VrStrobeSafetyController.kt'
$geometry = Join-Path $sourceRoot 'VrStrobeCarrierGeometry.kt'
$depth = Join-Path $sourceRoot 'VrStrobeDepthDeformation.kt'
$stored = Join-Path $sourceRoot 'VrStrobeStoredProfiles.kt'
$gpu = Join-Path $sourceRoot 'VrStrobeGpuPlan.kt'
$shader = Join-Path $appRoot 'app\src\shaders\vr_strobe_interference.frag'
$vertexShader = Join-Path $appRoot 'app\src\shaders\vr_strobe_interference.vert'
$manifest = Join-Path $appRoot 'app\src\main\AndroidManifest.xml'
$gradle = Join-Path $appRoot 'app\build.gradle.kts'
$build = Join-Path $RepoRoot 'tools\Build-SpatialVrStrobeAndroid.ps1'
$editor = Join-Path $appRoot 'profile-editor-web'
$permission = Join-Path $legacyRoot 'legacy-workspaces\mixed-integration-v1\receipts\mod-010-vr-strobe-source-permission-20260717.json'
$notice = Join-Path $appRoot 'THIRD_PARTY_NOTICES.md'
$projectSpec = Join-Path $appRoot 'morphospace\project.spec.json'

$sourceNames = @(
    'SpatialVrStrobeActivity.kt', 'SpatialVrStrobeControllerAdapter.kt', 'SpatialVrStrobeCoordinator.kt',
    'SpatialVrStrobePanelModule.kt', 'VrStrobeCarrierGeometry.kt', 'VrStrobeControllerInputCoordinator.kt',
    'VrStrobeControlPanel.kt', 'VrStrobeDepthDeformation.kt', 'VrStrobeFeatureRoute.kt',
    'VrStrobeGpuPlan.kt', 'VrStrobePanelFlow.kt', 'VrStrobePresetCatalog.kt',
    'VrStrobeProfileBundleCodec.kt', 'VrStrobeProfileCodec.kt', 'VrStrobeProfiles.kt',
    'VrStrobeRandomization.kt', 'VrStrobeSafetyController.kt', 'VrStrobeStimulusSelectionAuthority.kt',
    'VrStrobeStoredProfiles.kt'
)
$testNames = @(
    'SpatialVrStrobePanelModuleTest.kt', 'VrStrobeCarrierGeometryTest.kt',
    'VrStrobeControllerInputCoordinatorTest.kt', 'VrStrobeDepthDeformationTest.kt',
    'VrStrobeFeatureRouteTest.kt', 'VrStrobeGpuPlanTest.kt', 'VrStrobePanelFlowTest.kt',
    'VrStrobeProfileBundleCodecTest.kt', 'VrStrobeProfileCatalogTest.kt',
    'VrStrobeRandomizationTest.kt', 'VrStrobeSafetyControllerTest.kt',
    'VrStrobeStimulusSelectionAuthorityTest.kt', 'VrStrobeStoredProfileAuthorityTest.kt'
)
@($sourceNames | ForEach-Object { Join-Path $sourceRoot $_ }) +
    @($testNames | ForEach-Object { Join-Path $testRoot $_ }) +
    @($shader, $vertexShader, $manifest, $gradle, $build, $permission, $notice, $projectSpec) |
    ForEach-Object { Require-File $_ }

$permissionReceipt = Get-Content -Raw -LiteralPath $permission | ConvertFrom-Json
if ([string]$permissionReceipt.permission.target_spdx_license -ne 'AGPL-3.0-or-later') {
    throw 'Strobe source permission must bind AGPL-3.0-or-later.'
}
if ([string]$permissionReceipt.reference.commit -ne '52c71cc069f4102bc4148e05c5fd3fc4d5466479') {
    throw 'Strobe source permission must pin the accepted upstream commit.'
}

Require-Text $gradle 'com\.android\.application|android\.application'
Require-Text $gradle 'applicationId = "io\.github\.mesmerprism\.rustyquest\.spatial_vr_strobe"'
Require-Text $gradle 'implementation\(project\(":spatial-sdk-shared"\)\)'
Require-Text $gradle 'RUSTY_QUEST_SPATIAL_STROBE_BUILD_DIR'
Require-Text $manifest 'SpatialVrStrobeActivity'
Reject-Text $manifest 'android\.permission\.CAMERA|HEADSET_CAMERA|SPATIAL_CAMERA'
Require-Text $build ':strobe-app:testDebugUnitTest'
Require-Text $build ':strobe-app:assembleDebug'
Require-Text $build 'camera_permissions_declared = \$false'
Reject-Text $build 'Build-SpatialCameraPanelAndroid\.ps1'

Require-Text $featureRoute 'standalone-application-module'
Require-Text $featureRoute 'activationAuthority=application-module'
Require-Text $featureRoute 'restoredStateMayStart=false'
Require-Text $featureRoute 'warningScreenFirst=true'
Require-Text $featureRoute 'presetSelectionIsBeginGesture=true'
Require-Text $featureRoute 'automaticTimeLimit=false'
Reject-Text $featureRoute 'SpatialProductBuildPolicy|build-product-denied'
Require-Text $safety 'VR_STROBE_BLACK_LEAD_IN_MS = 500L'
Require-Text $safety 'focusLost'
Require-Text $safety 'warningAcknowledged'
Reject-Text $safety 'PAUSED|fun pause\(|fun resume\(|outputEndsAtMs|COMPLETED'
Require-Text $controlPanel 'PHOTOSENSITIVITY WARNING'
Require-Text $controlPanel 'I UNDERSTAND — CONTINUE'
Require-Text $controlPanel 'RANDOMIZE — RIGHT A'
Require-Text $controlPanel 'STORE CURRENT — LEFT X'
Require-Text $controlPanel 'no automatic time limit'
Reject-Text $controlPanel 'pauseOrResume|Text\("PAUSE"\)|"RESUME" else "PAUSE"'

Require-Text $activity 'class SpatialVrStrobeActivity : AppSystemActivity'
Require-Text $activity 'SpatialVrStrobeControllerPollingFeature\(::pollControllers\)'
Require-Text $activity 'coordinator\.onFocusLost\(\)'
Require-Text $activity 'coordinator\.destroy\("activity-destroy"\)'
Require-Text $activity 'VrStrobeRightControllerSamplePolicy\.isValid'
Require-Text $activity 'VrStrobeLeftControllerSamplePolicy\.isValid'
Require-Text $activity 'controllerInput\.handlePrimary'
Require-Text $activity 'controllerInput\.handleSnapshot'
Require-Text $activity 'setPanelVisible\(!panelVisible'
Require-Text $activity 'panelSceneObject\?\.setIsVisible\(visible\)'
Require-Text $activity 'PerformanceLevel\.BOOST_HINT'
Require-Text $activity 'spatial_sdk_shared\.SpatialPanelFacing'
Reject-Text $activity 'SpatialCameraPanel|cameraHwb|privateLayer|SPATIAL_CAMERA'

Require-Text $panel 'VR_STROBE_PANEL_LAYER_Z_INDEX = 100'
Require-Text $panel 'VR_STROBE_PANEL_COMFORT_DISTANCE_METERS = 0\.82f'
Require-Text $panel 'PanelShapeLayerBlendType\.OPAQUE'
Require-Text $panel 'ButtonBits\.ButtonTriggerL or ButtonBits\.ButtonTriggerR'
Require-Text $panel 'ViewGroup\.FOCUS_BLOCK_DESCENDANTS'
Require-Text $panel 'Modifier\.fillMaxSize\(\)\.onPreviewKeyEvent'
Require-Text $panel 'KEYCODE_BUTTON_A'
Require-Text $controllerAdapter 'ButtonBits\.ButtonThumbLL'
Require-Text $controllerAdapter 'ButtonBits\.ButtonThumbRR'
Require-Text $controllerAdapter 'ButtonBits\.ButtonX'
Require-Text $controller 'HORIZONTAL_FLICK_THRESHOLD = 0\.72f'
Require-Text $controller 'VERTICAL_DEADZONE = 0\.25f'
Require-Text $controller 'DEFAULT_METERS = 4\.00f'
Require-Text $controller 'MIN_METERS = 1\.05f'
Require-Text $controller 'MAX_METERS = 4\.00f'
Require-Text $controller 'RELEASE_CONFIRM_MS = 60L'

Require-Text $coordinator 'setDepthTest\(DepthTest\.LESS_OR_EQUAL\)'
Require-Text $coordinator 'setDepthWrite\(DepthWrite\.ENABLE\)'
Require-Text $coordinator 'setPerformanceBoost\(true, "stimulus-begin"\)'
Require-Text $coordinator 'setPerformanceBoost\(false, "focus-lost"\)'
Require-Text $coordinator 'VrStrobeStimulusSelectionAuthority'
Require-Text $coordinator 'storage=app-private'
Require-Text $coordinator 'visibleDrawCount=1'
Require-Text $coordinator 'rendererVisibleProof=attended-required'
Reject-Text $coordinator 'fun pauseOrResume\(|standbyMaterial|swapCarrierRenderSlots'
Require-Text $geometry 'RADIAL_RINGS = 32'
Require-Text $geometry 'ANGULAR_SEGMENTS = 96'
Require-Text $geometry 'RADIUS_METERS = 2\.84f'
Require-Text $depth 'MAX_MAX_METERS = VrStrobeCarrierGeometry\.RADIUS_METERS \* 0\.5f'
Require-Text $stored 'VERSION = 3'
Require-Text $gpu 'VR_STROBE_PROFILE_WRITES_PER_SCENE_TICK = 6'

Require-Text $shader 'float interferenceSignal'
Require-Text $shader 'vec3 temporalColor'
Require-Text $shader 'MAX_INTERFERENCE_SIGNAL_EVALUATIONS = 1'
Require-Text $shader 'const float CARRIER_RADIUS = 2\.84;'
Require-Text $vertexShader 'vec4 depthDeformation;'
Require-Text $vertexShader 'carrierPosition \+= viewerFacingNormal \* signedDisplacement'
Reject-Text $shader 'gl_FragDepth'

$editorFiles = @(
    'index.html', 'styles.css', 'app.mjs', 'renderer.mjs', 'profile-contract.mjs',
    'trevor-catalog.mjs', 'exploration-session.mjs', 'tests\profile-contract.test.mjs',
    'tests\trevor-catalog.test.mjs', 'tests\exploration-session.test.mjs'
)
$editorFiles | ForEach-Object { Require-File (Join-Path $editor $_) }
Require-Text (Join-Path $editor 'profile-contract.mjs') 'rusty\.quest\.spatial_vr_strobe\.profile_bundle\.v1'
Require-Text (Join-Path $editor 'trevor-catalog.mjs') '52c71cc069f4102bc4148e05c5fd3fc4d5466479'
Require-Text $notice 'AGPL-3\.0-or-later'

[pscustomobject]@{
    schema = 'rusty.quest.spatial_vr_strobe.static_validation.v2'
    status = 'pass'
    gradle_module = ':strobe-app'
    source_files = $sourceNames.Count
    unit_test_files = $testNames.Count
    warning_first = $true
    camera_permissions_declared = $false
    shared_camera_compile_graph = $false
    controller_shortcuts = @('right-a-randomize', 'left-x-store', 'right-b-panel-toggle', 'stick-adjustments')
} | ConvertTo-Json -Depth 5
