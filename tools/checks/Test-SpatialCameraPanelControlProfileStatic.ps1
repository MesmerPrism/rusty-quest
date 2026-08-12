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
        throw "Missing Spatial Camera control-profile file: $path"
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

$contract = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraControlProfile.kt"
$strictIngress = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\StrictJsonByteIngress.kt"
$strictIngressTests = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\StrictJsonByteIngressTest.kt"
$hotloader = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraControlProfileHotloader.kt"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$tests = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraControlProfileTest.kt"
$converter = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\HostessReplayControlStateConverter.kt"
$converterCli = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\HostessReplayControlStateCli.kt"
$converterTests = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\HostessReplayControlStateConverterTest.kt"
$converterTool = Read-RequiredText "tools\Convert-HostessReplayControlState.ps1"
$installer = Read-RequiredText "tools\Install-SpatialCameraPanelControlProfile.ps1"
$docs = Read-RequiredText "docs\SPATIAL_CAMERA_CONTROL_PROFILES.md"
$surfaceTiling = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\ProjectionSurfaceTiling.kt"
$innerAlpha = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\ProjectionInnerAlpha.kt"

Assert-Contains "Profile contract" $contract 'SCHEMA = "rusty.quest.spatial_camera_panel.control_profile.v1"'
Assert-Contains "Profile contract" $contract "MAX_PROFILE_BYTES = 64 * 1024"
Assert-Contains "Profile contract" $contract "StrictJsonByteIngress.parseObject(bytes)"
Assert-Contains "Profile contract" $contract "is BigInteger -> runCatching { value.longValueExact() }"
Assert-Contains "Profile contract" $contract "is BigDecimal -> runCatching { value.longValueExact() }"
Assert-Contains "Profile contract" $contract "unsupported_transparent_spatial_video_blend"
Assert-Contains "Profile contract" $contract 'APPLY_RECEIPT_FILE = "last-apply-receipt.json"'
Assert-Contains "Profile contract" $contract '"projection_surface_tiling"'
Assert-Contains "Profile contract" $contract '"projection_inner_alpha"'
Assert-Contains "Surface tiling contract" $surfaceTiling 'CONTRACT_ID = "rusty.quest.projection-surface-tiling.v1"'
Assert-Contains "Surface tiling contract" $surfaceTiling "ProjectionSurfaceTilingControls.off"
Assert-Contains "Inner alpha contract" $innerAlpha 'CONTRACT_ID = "rusty.quest.projection-inner-alpha.v1"'
Assert-Contains "Inner alpha contract" $innerAlpha 'INPUT_TOKEN = "processed-core"'
Assert-Contains "Inner alpha contract" $innerAlpha "ProjectionInnerAlphaControls.off"
Assert-Contains "Strict JSON ingress" $strictIngress "CodingErrorAction.REPORT"
Assert-Contains "Strict JSON ingress" $strictIngress "duplicate_object_key"
Assert-Contains "Strict JSON ingress" $strictIngress "trailing_content"
Assert-Contains "Strict JSON ingress" $strictIngress "MAX_NESTING_DEPTH = 64"
Assert-Contains "Strict JSON ingress tests" $strictIngressTests "malformedUtf8VariantsFailBeforeJsonConstruction"
Assert-Contains "Strict JSON ingress tests" $strictIngressTests "damagedStringsRootsNumbersAndTrailingValuesFailClosed"
Assert-Contains "Strict JSON ingress tests" $strictIngressTests "decodedDuplicateKeysFailAtEveryObjectLocation"
Assert-Contains "Profile hotloader" $hotloader "staleProfileApplied=false"
Assert-Contains "Profile hotloader" $hotloader "status=pending-route"
Assert-Contains "Profile hotloader" $hotloader "previousEffectiveControlsRetained=true"
Assert-Contains "Activity integration" $activity "controlProfileHotloader.arm()"
Assert-Contains "Activity integration" $activity "controlProfileHotloader.poll()"
Assert-Contains "Activity integration" $activity "applyControlProfile"
Assert-Contains "Profile tests" $tests "validProfileCarriesStructuredDesktopControlsIntoQuestTypes"
Assert-Contains "Profile tests" $tests "damagedAndExpandedProfilesFailClosed"
Assert-Contains "Profile tests" $tests "independentOuterRegionAcceptsIncomingColorForSpatialVideoUnderlay"
Assert-Contains "Profile tests" $tests "desktopPreviewMustBeAnObjectWhenPresent"
Assert-Contains "Profile tests" $tests "desktopPreviewRejectsUnknownMalformedAndOutOfRangeFields"
Assert-Contains "Profile tests" $tests "malformedUtf8InOtherwiseValidStringFailsClosed"
Assert-Contains "Profile tests" $tests "duplicateRootSchemaIncludingEscapeEquivalentFailsClosed"
Assert-Contains "Profile tests" $tests "duplicateNumericControlFailsClosed"
Assert-Contains "Profile tests" $tests "duplicateNestedObjectKeyIncludingEscapeEquivalentFailsClosed"
Assert-Contains "Profile tests" $tests "trailingContentFailsClosed"
Assert-Contains "Profile tests" $tests "excessiveNestingFailsAtStrictByteIngress"
Assert-Contains "Profile tests" $tests "integralMetadataUsesExactLongConversionAndRangeChecks"
Assert-Contains "Profile tests" $tests "authoritativeByteBoundsAcceptExactlyLimitAndRejectEmptyOrLimitPlusOne"
Assert-Contains "Profile tests" $tests "profilesWithoutAdditiveSurfaceFeaturesRetainDisabledCompatibilityDefaults"
Assert-Contains "Hostess state converter" $converter 'INPUT_SCHEMA = "rusty.hostess.projection_replay_control_state.v2"'
Assert-Contains "Hostess state converter" $converter 'INPUT_V1_SCHEMA = "rusty.hostess.projection_replay_control_state.v1"'
Assert-Contains "Hostess state converter" $converter '"control_transport"'
Assert-Contains "Hostess state converter" $converter "StrictJsonByteIngress.parseObject(bytes)"
Assert-Contains "Hostess state converter" $converter "SpatialCameraControlProfileContract.parse(encoded)"
Assert-Contains "Hostess state converter" $converter '"surface_feature_uniform_f32"'
Assert-Contains "Hostess state converter" $converter "surface_feature_uniform_prefix_mismatch"
Assert-Contains "Hostess state converter CLI" $converterCli "StandardCopyOption.ATOMIC_MOVE"
Assert-Contains "Hostess state converter tests" $converterTests "goldenHostessStateExportsSameEffectiveQuestControls"
Assert-Contains "Hostess state converter tests" $converterTests "damagedExpandedNonFiniteAndUnsupportedStatesFailClosed"
Assert-Contains "Hostess state converter tests" $converterTests "malformedUtf8InOtherwiseValidStringFailsClosed"
Assert-Contains "Hostess state converter tests" $converterTests "duplicateRootSchemaIncludingEscapeEquivalentFailsClosed"
Assert-Contains "Hostess state converter tests" $converterTests "duplicateNumericControlFailsClosed"
Assert-Contains "Hostess state converter tests" $converterTests "duplicateNestedObjectKeyIncludingEscapeEquivalentFailsClosed"
Assert-Contains "Hostess state converter tests" $converterTests "trailingContentFailsClosed"
Assert-Contains "Hostess state converter tests" $converterTests "excessiveNestingFailsAtStrictByteIngress"
Assert-Contains "Hostess state converter tests" $converterTests "integralMetadataUsesExactLongConversionAndRangeChecks"
Assert-Contains "Hostess state converter tests" $converterTests "authoritativeByteBoundsAcceptExactlyLimitAndRejectEmptyOrLimitPlusOne"
Assert-Contains "Hostess state converter tests" $converterTests "additiveSurfaceFeatureUniformExportsTilingAndInnerAlphaControls"
Assert-Contains "Hostess state converter tests" $converterTests "additiveSurfaceFeatureUniformRejectsPrefixDriftAndUnsupportedAbi"
Assert-Contains "Hostess state converter tool" $converterTool ":app:convertHostessReplayControlState"
Assert-Contains "Hostess state converter tool" $converterTool '[string]$GradleHome = $env:GRADLE_HOME'
Assert-Contains "Hostess state converter tool" $converterTool 'GradleHome must name the exact gradle-$GradleVersion distribution directory.'
Assert-Contains "Manual installer" $installer "serial-scoped-adb-fallback"
Assert-Contains "Manual installer" $installer "profile_sha256"
Assert-Contains "Manual installer" $installer "last-apply-receipt.json"
Assert-Contains "Manual installer" $installer 'shell touch -m -d "@$publishModifiedUnixSeconds"'
Assert-Contains "Manual installer" $installer "did not advance the device-observed signature"
Assert-Contains "Manual installer" $installer "publication_signature"
Assert-Contains "Manual installer" $installer "atomic_replace = `$true"
Assert-Contains "Public documentation" $docs "Only an atomic replacement"
Assert-Contains "Public documentation" $docs "A successful file transfer without a"
Assert-Contains "Public documentation" $docs "strictly newer staged modification time"
Assert-Contains "Public documentation" $docs "not packaged into the APK"

Write-Output "Spatial Camera Panel control-profile static checks passed."
