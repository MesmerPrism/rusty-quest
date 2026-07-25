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
        throw "Missing projection-zone compositor file: $path"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not $Text.Contains($Needle)) {
        throw $Message
    }
}

$controls = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\PrivateLayerZoneCompositor.kt"
$panel = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\PrivateLayerControlPanel.kt"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$coordinator = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialPrivateLayerControlCoordinator.kt"
$projection = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_projection_target.rs"
$wsi = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_wsi.rs"
$runtime = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_public_multistack_runtime.rs"
$build = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\build.rs"
$readme = Read-RequiredText "apps\spatial-camera-panel-android\README.md"

Assert-Contains $controls "coverageOff = 0" "The compositor must retain an explicit off/legacy mode."
Assert-Contains $controls "coverageDynamicBuffer = 1" "The compositor must expose dynamic-buffer coverage."
Assert-Contains $controls "coverageReplaceVideo = 2" "The compositor must expose replace-video coverage."
Assert-Contains $controls "sourceRaw = 0" "The stretch source must keep a raw-camera A/B path."
Assert-Contains $controls "sourceProcessed = 1" "The stretch source must keep the verified processed-layer target."
Assert-Contains $controls "mappingRectangularLinear = 0" "The earlier rectangular mapping must remain an A/B rollback."
Assert-Contains $controls "mappingMirroredLens = 1" "The corrected mirrored lens mapping must have a bounded public transport id."
Assert-Contains $controls 'mappingMirroredLens -> "mirrored-lens-native"' "Markers must identify the native-reference lens target."
Assert-Contains $controls "signalDifference = 4" "The seam signal catalog must include inter-source difference."
Assert-Contains $controls "projectionZoneGeometryOrder=user-scale-then-dynamic-core" "Panel markers must state the scale-before-guard geometry order."
Assert-Contains $panel 'Section("Peripheral Stretch & Zone Blend")' "The layer panel must expose the zone compositor."
Assert-Contains $panel 'ChoiceButton("Lens buffer"' "The panel must expose the native-reference lens buffer preset."
Assert-Contains $panel 'ChoiceButton("Native lens"' "The panel must expose the corrected lens mapping."
Assert-Contains $panel 'ChoiceButton("Linear"' "The panel must expose the prior mapping for direct A/B."
Assert-Contains $panel 'ChoiceButton("Organic lens"' "The panel must expose the channel-responsive preset."
Assert-Contains $panel 'ChoiceButton("Full lens"' "The panel must expose the video-replacement preset."
Assert-Contains $activity "nativeUpdatePrivateLayerZoneCompositor" "The activity must bridge live compositor settings to native code."
Assert-Contains $coordinator "updateZoneCompositor" "The coordinator must own the live panel-to-native roundtrip."

Assert-Contains $projection "let (left_user, right_user)" "Each frame must snapshot the live user-scaled eye rectangles."
Assert-Contains $projection "let left_core = effective_rect(left_user, footprint_scale" "Dynamic guard contraction must follow user scale."
Assert-Contains $projection "2 => carrier_rects" "Replace-video mode must draw to the full stereo carrier."
Assert-Contains $projection "settings.stretch_mapping as f32" "The selected mapping must reach the per-frame zone UBO."
Assert-Contains $projection '1 => "mirrored-lens-native"' "Native markers must identify the selected lens mapping."
Assert-Contains $projection "size_of::<ProjectionZoneUniform>() == 240" "The projection-zone UBO ABI must remain fixed at fifteen vec4 values."
Assert-Contains $projection "projection_zone_applies_user_scale_before_dynamic_core_contraction" "The geometry order needs a native unit test."
Assert-Contains $projection "projection_zone_replace_video_uses_full_per_eye_carrier" "Replace-video coverage needs a native unit test."

Assert-Contains $wsi "camera_hwb_projection_zone_frame(" "Render recording must create one zone snapshot for the display frame."
Assert-Contains $wsi "projection_zone_frame.settings.replaces_video()" "Only a ready replacement compositor may suppress the separate video draw."
Assert-Contains $wsi "record_projection_zone_compositor_in_open_render_pass" "The ready path must record the composite in the final render pass."
Assert-Contains $runtime "prepare_projection_zone_compositor" "The renderer must prepare the live zone UBO and video-aware pipeline."
Assert-Contains $runtime "pipeline.video_descriptor_set_layout != video_descriptor_set_layout" "The video descriptor layout must participate in pipeline compatibility."
Assert-Contains $build "PRIVATE_LAYER_VIDEO_COMPOSITOR=0" "The build must preserve the exact legacy projection shader variant."
Assert-Contains $build "PRIVATE_LAYER_VIDEO_COMPOSITOR=1" "The build must compile the video-aware compositor variant."
Assert-Contains $readme "user scale -> dynamic core -> stretch/seams -> video carrier" "The public adapter README must document the geometry order."

Write-Output "Spatial Camera Panel projection-zone compositor static checks passed."
