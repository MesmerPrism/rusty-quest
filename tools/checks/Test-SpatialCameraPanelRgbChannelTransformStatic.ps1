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
        throw "Missing RGB channel transform file: $path"
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
        throw "$Label contains private effect implementation token: $Needle"
    }
}

$kotlin = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\RgbChannelTransform.kt"
$kotlinTest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\RgbChannelTransformTest.kt"
$panel = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\PrivateLayerControlPanel.kt"
$coordinator = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialPrivateLayerControlCoordinator.kt"
$workflow = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialValidationWorkflowCoordinator.kt"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$native = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\rgb_channel_transform.rs"
$jni = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_probe.rs"
$runtime = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_public_multistack_runtime.rs"
$docs = Read-RequiredText "docs\RGB_CHANNEL_TRANSFORM.md"

Assert-Contains "Kotlin contract" $kotlin 'CONTRACT_ID = "rusty.quest.rgb-channel-transform.v1"'
Assert-Contains "Kotlin contract" $kotlin "modeIndependent = 1"
Assert-Contains "Kotlin contract" $kotlin "modeLinked = 2"
Assert-Contains "Kotlin contract" $kotlin "else -> RgbChannelTransformControls.modeBypass"
Assert-Contains "Kotlin contract" $kotlin "String.format(Locale.US"
Assert-Contains "Kotlin tests" $kotlinTest "defaultIsBypassIdentity"
Assert-Contains "Kotlin tests" $kotlinTest "linkedModeUsesRedAsSingleChannelAuthority"
Assert-Contains "Kotlin tests" $kotlinTest "independentModePreservesBoundedChannelDifferences"

Assert-Contains "Layer panel" $panel 'Section("RGB Channel Transform")'
Assert-Contains "Layer panel" $panel '"Direction speed"'
Assert-Contains "Layer panel" $panel '"Coverage scale"'
Assert-Contains "Coordinator" $coordinator "updateRgbChannelTransformNative"
Assert-Contains "Coordinator" $coordinator "status=rgb-channel-transform-submitted"
Assert-Contains "Validation workflow" $workflow '"rgb-channel-bypass"'
Assert-Contains "Validation workflow" $workflow '"rgb-channel-linked"'
Assert-Contains "Validation workflow" $workflow '"rgb-channel-independent"'
Assert-Contains "Activity JNI" $activity "nativeUpdateRgbChannelTransform"
Assert-Contains "Native JNI" $jni "update_rgb_channel_transform_settings"

Assert-Contains "Native contract" $native 'RGB_CHANNEL_TRANSFORM_CONTRACT_ID'
Assert-Contains "Native contract" $native "RgbChannelTransformMode::Linked"
Assert-Contains "Native contract" $native "size_of::<RgbChannelTransformUniform>() == 96"
Assert-Contains "Native contract" $native "direction_turns: [f32; RGB_CHANNEL_COUNT]"
Assert-Contains "Native contract" $native "direction_rate_hz: [f32; RGB_CHANNEL_COUNT]"
Assert-Contains "Native contract" $native "displacement_strength_uv: [f32; RGB_CHANNEL_COUNT]"
Assert-Contains "Native contract" $native "image_scale: [f32; RGB_CHANNEL_COUNT]"
Assert-Contains "Native contract" $native "coverage_scale: [f32; RGB_CHANNEL_COUNT]"

Assert-Contains "Vulkan runtime" $runtime "SpatialRgbChannelTransformUniformResources"
Assert-Contains "Vulkan runtime" $runtime "create_rgb_channel_transform_uniform_resources"
Assert-Contains "Vulkan runtime" $runtime "current_rgb_channel_transform_settings().uniform()"
Assert-Contains "Vulkan runtime" $runtime "self.rgb_channel_transform_uniform.descriptor_set"
Assert-Contains "Public documentation" $docs "does not define an effect signal"
Assert-Contains "Public documentation" $docs "descriptor set 3, binding 0"
Assert-Contains "Public documentation" $docs "video sampler at set 4 and zone uniform at set 5"

$publicRgbOwner = $kotlin + $native + $runtime
foreach ($privateToken in @(
    "morphovision_strength_from_channel",
    "legacy_displacement_strength",
    "normalized_gradient_strength",
    "MORPHOVISION_DEPTH_DISTORTION_INFLUENCE"
)) {
    Assert-NotContains "Public RGB owner" $publicRgbOwner $privateToken
}

Write-Output "Spatial Camera Panel RGB channel transform static checks passed."
