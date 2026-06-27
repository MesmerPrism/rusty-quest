param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path $RepoRoot).Path

function Read-RequiredText {
    param([Parameter(Mandatory=$true)][string]$RelativePath)
    $path = Join-Path $repoRootPath $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing required Spatial Camera Panel file: $RelativePath"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$Text,
        [Parameter(Mandatory=$true)][string]$Needle
    )
    if (-not $Text.Contains($Needle)) {
        throw "$Label is missing required token: $Needle"
    }
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$Text,
        [Parameter(Mandatory=$true)][string]$Needle
    )
    if ($Text.Contains($Needle)) {
        throw "$Label contains forbidden private-boundary token: $Needle"
    }
}

$appGradle = Read-RequiredText "apps\spatial-camera-panel-android\app\build.gradle.kts"
$manifest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\AndroidManifest.xml"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$store = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelStore.kt"
$nativeLib = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\lib.rs"
$cameraProbe = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_probe.rs"
$cameraStream = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_stream.rs"
$cameraWsi = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_wsi.rs"
$surfaceLayer = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\surface_particle_layer.rs"
$replayHands = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\replay_hands.rs"
$buildScript = Read-RequiredText "tools\Build-SpatialCameraPanelAndroid.ps1"
$readme = Read-RequiredText "apps\spatial-camera-panel-android\README.md"
$notes = Read-RequiredText "docs\SPATIAL_SDK_PORT_IMPLEMENTATION_PLAN.md"

Assert-Contains "Gradle app" $appGradle 'namespace = "io.github.mesmerprism.rustyquest.spatial_camera_panel"'
Assert-Contains "Gradle app" $appGradle 'applicationId = "io.github.mesmerprism.rustyquest.spatial_camera_panel"'
Assert-Contains "Android manifest" $manifest 'android:name=".SpatialCameraPanelActivity"'
Assert-Contains "Activity" $activity "class SpatialCameraPanelActivity : AppSystemActivity()"
Assert-Contains "Activity" $activity "SceneSwapchain.createAsAndroid"
Assert-Contains "Activity" $activity "debug.rustyquest.spatial.camera_hwb_projection_probe"
Assert-Contains "Activity" $activity "outputMode=raw-color-target-rect"
Assert-Contains "Store" $store 'SESSION_SCHEMA = "rusty.quest.spatial_camera_panel.session.v1"'
Assert-Contains "Store" $store 'EVENT_SCHEMA = "rusty.quest.spatial_camera_panel.event.v1"'
Assert-Contains "Store" $store 'QUESTIONNAIRE_SCHEMA = "rusty.quest.spatial_camera_panel.questionnaire.v1"'
Assert-Contains "Store" $store "driver0_value01"
Assert-Contains "Store" $store "driver1_value01"
Assert-Contains "Store" $store "rusty.quest.spatial_camera_panel.driver_profile.profile-a.v1"
Assert-Contains "Store" $store "rusty.quest.spatial_camera_panel.driver_profile.profile-d.v1"
Assert-Contains "Native receipt" $nativeLib "Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRecordNoRenderInteropReceipt"
Assert-Contains "Camera HWB probe" $cameraProbe "outputMode=raw-color-target-rect"
Assert-Contains "Camera HWB probe" $cameraProbe "privateShaderStack=false"
Assert-Contains "Camera HWB stream" $cameraStream "AImageReader_newWithUsage"
Assert-Contains "Camera HWB stream" $cameraStream "AImage_getHardwareBuffer"
Assert-Contains "Camera HWB stream" $cameraStream "StereoCamera50_51"
Assert-Contains "Camera HWB WSI" $cameraWsi "create_ahb_sampler_ycbcr_conversion"
Assert-Contains "Camera HWB WSI" $cameraWsi "record_camera_hwb_probe_command_buffer"
Assert-Contains "Camera HWB WSI" $cameraWsi "select_camera_surface_device"
Assert-Contains "Surface particle layer" $surfaceLayer "Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartSurfaceParticleLayer"
Assert-Contains "Surface particle layer" $surfaceLayer "Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateSurfaceParticleParameters"
Assert-Contains "Replay hands" $replayHands "surfaceLayerMode=native-hand-anchor-particles"
Assert-Contains "Replay hands" $replayHands "rusty.quest.spatial_camera_panel.driver_profile.profile-b.v1"
Assert-Contains "Replay hands" $replayHands "driverProfileSchemaId={}"
Assert-Contains "Build script" $buildScript "libspatial_camera_panel_native_receipt.so"
Assert-Contains "Build script" $buildScript 'driver_profile_mapping = "driver0_value01-to-native-driver0;driver1_value01-to-native-driver1"'
Assert-Contains "Build script" $buildScript 'questionnaire_schema = "rusty.quest.spatial_camera_panel.questionnaire.v1"'
Assert-Contains "README" $readme "Raw Camera2/AHardwareBuffer projection probes"
Assert-Contains "Implementation notes" $notes "Private effect formulas"

$scanSuffixes = @(".kt", ".java", ".rs", ".glsl", ".kts", ".xml", ".md", ".ps1", ".toml")
$skipScanDirs = @(".gradle", ".kotlin", "build")
$scanRoots = @(
    "apps\spatial-camera-panel-android",
    "tools\Build-SpatialCameraPanelAndroid.ps1",
    "tools\Test-SpatialCameraPanelAndroid.ps1",
    "tools\Invoke-SpatialCameraPanelAndroidSelfTest.ps1",
    "tools\Invoke-SpatialCameraPanelAndroidPolarLive.ps1",
    "tools\Invoke-SpatialCameraPanelAndroidUiAction.ps1",
    "docs\SPATIAL_SDK_PORT_IMPLEMENTATION_PLAN.md"
)
$forbidden = @(
    ("Kura" + "moto"),
    ("kura" + "moto"),
    ("KURA" + "MOTO"),
    ("movement" + "_coupling"),
    ("movement" + "_base_frequency_hz"),
    ("movement" + "BaseFrequencyHz"),
    ("movement" + "Coupling"),
    ("private" + ("Kura" + "moto")),
    ("private" + "_" + ("kura" + "moto")),
    ("PRIVATE" + "_" + ("KURA" + "MOTO")),
    ("native" + "-" + ("kura" + "moto")),
    ("l" + "che"),
    ("h" + "che"),
    ("l" + "cle"),
    ("h" + "cle"),
    ("low" + "-energy"),
    ("high" + "-energy"),
    ("movement" + "-only"),
    ("Rusty" + "-Symmetric-" + "Morpho" + "vision"),
    ("Morpho" + "vision"),
    "privateShaderStack=true",
    "customProjectionStack=true"
)

foreach ($root in $scanRoots) {
    $path = Join-Path $repoRootPath $root
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Static scan root is missing: $root"
    }
    $item = Get-Item -LiteralPath $path
    $files = if ($item.PSIsContainer) {
        Get-ChildItem -LiteralPath $path -Recurse -File | Where-Object { $scanSuffixes -contains $_.Extension }
    } else {
        @($item)
    }
    foreach ($file in $files) {
        $relative = $file.FullName -replace ("^" + [regex]::Escape($repoRootPath) + "[\\/]*"), ""
        $parts = $relative -split "[\\/]"
        if ($parts | Where-Object { $skipScanDirs -contains $_ }) {
            continue
        }
        $text = Get-Content -Raw -LiteralPath $file.FullName
        foreach ($token in $forbidden) {
            Assert-NotContains $relative $text $token
        }
    }
}

Write-Host "Spatial Camera Panel Android static gate passed"
