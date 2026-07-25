param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'

function Read-RequiredText {
    param([Parameter(Mandatory=$true)][string]$RelativePath)
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required isolation file missing: $RelativePath"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Require-Text {
    param([string]$Label, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) { throw "$Label is missing required pattern: $Pattern" }
}

function Reject-Text {
    param([string]$Label, [string]$Text, [string]$Pattern)
    if ($Text -match $Pattern) { throw "$Label contains forbidden cross-product pattern: $Pattern" }
}

$settings = Read-RequiredText 'apps\spatial-camera-panel-android\settings.gradle.kts'
$cameraGradle = Read-RequiredText 'apps\spatial-camera-panel-android\app\build.gradle.kts'
$cameraManifest = Read-RequiredText 'apps\spatial-camera-panel-android\app\src\main\AndroidManifest.xml'
$cameraActivity = Read-RequiredText 'apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt'
$sharedGradle = Read-RequiredText 'apps\spatial-camera-panel-android\spatial-sdk-shared\build.gradle.kts'
$sharedFacing = Read-RequiredText 'apps\spatial-camera-panel-android\spatial-sdk-shared\src\main\java\io\github\mesmerprism\rustyquest\spatial_sdk_shared\SpatialPanelFacing.kt'
$strobeGradle = Read-RequiredText 'apps\spatial-vr-strobe-android\app\build.gradle.kts'
$strobeManifest = Read-RequiredText 'apps\spatial-vr-strobe-android\app\src\main\AndroidManifest.xml'
$strobeActivity = Read-RequiredText 'apps\spatial-vr-strobe-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_vr_strobe\SpatialVrStrobeActivity.kt'
$cameraBuild = Read-RequiredText 'tools\Build-SpatialCameraPanelAndroid.ps1'
$strobeBuild = Read-RequiredText 'tools\Build-SpatialVrStrobeAndroid.ps1'

Require-Text 'settings' $settings 'include\(":app"\)'
Require-Text 'settings' $settings 'include\(":spatial-sdk-shared"\)'
Require-Text 'settings' $settings 'include\(":strobe-app"\)'
Require-Text 'settings' $settings 'spatial-vr-strobe-android/app'
Require-Text 'shared module' $sharedGradle 'com\.android\.library|android\.library'
Require-Text 'shared module' $sharedFacing 'object SpatialPanelFacing'
Require-Text 'Camera Gradle' $cameraGradle 'implementation\(project\(":spatial-sdk-shared"\)\)'
Require-Text 'Strobe Gradle' $strobeGradle 'implementation\(project\(":spatial-sdk-shared"\)\)'
Require-Text 'Strobe Gradle' $strobeGradle 'applicationId = "io\.github\.mesmerprism\.rustyquest\.spatial_vr_strobe"'
Require-Text 'Strobe Gradle' $strobeGradle 'RUSTY_QUEST_SPATIAL_STROBE_BUILD_DIR'
Require-Text 'Strobe Activity' $strobeActivity 'class SpatialVrStrobeActivity : AppSystemActivity'
Require-Text 'Camera manifest' $cameraManifest 'android\.permission\.CAMERA'
Reject-Text 'Strobe manifest' $strobeManifest 'android\.permission\.CAMERA|HEADSET_CAMERA|SPATIAL_CAMERA'
Reject-Text 'Camera Activity' $cameraActivity 'VrStrobe|vrStrobe|spatial-vr-strobe|spatial_vr_strobe'
Reject-Text 'Strobe Activity' $strobeActivity 'SpatialCameraPanel|cameraHwb|privateLayer|SPATIAL_CAMERA'
Reject-Text 'Camera Gradle' $cameraGradle 'RUSTY_QUEST_SPATIAL_PRODUCT_ID|spatial-vr-strobe'
Require-Text 'Camera build' $cameraBuild ':app:assembleDebug'
Reject-Text 'Camera build' $cameraBuild ':strobe-app:assembleDebug'
Require-Text 'Strobe build' $strobeBuild ':strobe-app:testDebugUnitTest'
Require-Text 'Strobe build' $strobeBuild ':strobe-app:assembleDebug'
Reject-Text 'Strobe build' $strobeBuild 'Build-SpatialCameraPanelAndroid\.ps1'

$cameraStrobeSource = Join-Path $RepoRoot 'apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\vrstrobe'
if (Test-Path -LiteralPath $cameraStrobeSource) {
    $files = @(Get-ChildItem -LiteralPath $cameraStrobeSource -Recurse -File)
    if ($files.Count -gt 0) { throw "Camera module still contains $($files.Count) Strobe source files." }
}
$strobeSource = Join-Path $RepoRoot 'apps\spatial-vr-strobe-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_vr_strobe'
if (@(Get-ChildItem -LiteralPath $strobeSource -Filter '*.kt' -File).Count -lt 19) {
    throw 'Standalone Strobe source set is incomplete.'
}

[pscustomobject]@{
    schema = 'rusty.quest.spatial_product_isolation.static.v2'
    status = 'pass'
    camera_module = ':app'
    strobe_module = ':strobe-app'
    shared_module = ':spatial-sdk-shared'
    runtime_switch_removed = $true
    camera_permissions_absent_from_strobe = $true
} | ConvertTo-Json -Depth 4
