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

function Assert-NotContains {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if ($Text.Contains($Needle)) {
        throw $Message
    }
}

$controls = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\PrivateLayerZoneCompositor.kt"
$panel = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\PrivateLayerControlPanel.kt"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$coordinator = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialPrivateLayerControlCoordinator.kt"
$rawCarrier = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraHwbProjectionRawCarrierCoordinator.kt"
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
Assert-Contains $controls "mappingGradedEdgeTrail = 0" "The original native graded edge trail must have a bounded public transport id."
Assert-Contains $controls '"graded-edge-trail-native"' "Markers must identify the original native edge-trail target."
Assert-Contains $controls "legacyMappingRequest" "Legacy lens mapping values must be detected and coerced to the edge-trail defaults."
Assert-NotContains $controls "mappingMirroredLens" "The public controls must not expose the rejected cross-center lens mapping."
Assert-Contains $controls "signalDifference = 4" "The seam signal catalog must include inter-source difference."
Assert-Contains $controls "outerTargetTransparentSpatialVideo = 1" "The compositor must expose an unsampled direct-video underlay target."
Assert-Contains $controls "transparentSpatialVideoSupported" "The public controls must report the bounded underlay support matrix."
Assert-Contains $controls "applicationMode = applicationRegion" "The underlay preset must use one scalar region alpha."
Assert-Contains $controls "sourceChoice = blendSourceOutgoing" "The underlay preset must derive alpha from the readable outgoing buffer."
Assert-Contains $controls "projectionZoneGeometryOrder=user-scale-then-dynamic-core" "Panel markers must state the scale-before-guard geometry order."
Assert-Contains $panel 'Section("Peripheral Stretch & Zone Blend")' "The layer panel must expose the zone compositor."
Assert-Contains $panel 'ChoiceButton("Native stretch"' "The panel must expose the original native stretch preset."
Assert-Contains $panel 'ChoiceButton("Organic stretch"' "The panel must expose the channel-responsive preset."
Assert-Contains $panel 'ChoiceButton("Full stretch"' "The panel must expose the video-replacement preset."
Assert-Contains $panel '"360 underlay blend"' "The panel must expose the direct Spatial-video alpha preset."
Assert-Contains $panel "Component, midpoint, incoming, difference, and synthetic-debug choices are disabled." "The panel must explain the unsampled-source restrictions."
Assert-Contains $panel "ProjectionSurfaceDisplacementControls.off" "Synthetic presets must explicitly disable live surface displacement."
Assert-Contains $activity "nativeUpdatePrivateLayerZoneCompositor" "The activity must bridge live compositor settings to native code."
Assert-Contains $coordinator "updateZoneCompositor" "The coordinator must own the live panel-to-native roundtrip."
Assert-Contains $rawCarrier "BlendFactor.ONE," "The custom Spatial panel must consume premultiplied color."
Assert-Contains $rawCarrier "BlendFactor.ONE_MINUS_SOURCE_ALPHA" "The custom Spatial panel must alpha-over the lower video layer."

Assert-Contains $projection "let (left_user, right_user)" "Each frame must snapshot the live user-scaled eye rectangles."
Assert-Contains $projection "let left_core = effective_rect(left_user, footprint_scale" "Dynamic guard contraction must follow user scale."
Assert-Contains $projection "2 => carrier_rects" "Replace-video mode must draw to the full stereo carrier."
Assert-Contains $projection "(settings.stretch_mapping" "The selected mapping must reach the packed per-frame zone UBO lane."
Assert-Contains $projection "if settings.projection_effect_edge_guard_enabled" "The packed zone UBO lane must retain the projection-effect edge-guard enable state."
Assert-Contains $projection "1 << 1" "The packed zone UBO lane must retain the projection-effect edge-guard disable flag."
Assert-Contains $projection "projection_zone_effect_edge_guard_disable_is_packed_without_expanding_the_uniform_abi" "The packed projection-effect edge-guard flag needs a dedicated native regression test."
Assert-Contains $projection 'fn stretch_mapping_token(_mapping: u32)' "The compatibility mapping field must not select a rendering family."
Assert-Contains $projection '"graded-edge-trail-native"' "Native transport must identify the original graded edge trail."
Assert-Contains $projection 'return (0, 0.015, 0.14, 1.6)' "Legacy lens requests must normalize to the original graded edge-trail defaults."
Assert-Contains $projection "size_of::<ProjectionZoneUniform>() == 368" "The projection-zone UBO ABI must remain fixed at twenty-three vec4 values."
Assert-Contains $projection "settings.outer_target_mode as f32" "The outer target must use the reserved uniform lane without expanding the ABI."
Assert-Contains $projection "transparent_underlay_supported" "Native settings must fail closed on unsupported unsampled-source combinations."
Assert-Contains $projection "projection_zone_applies_user_scale_before_dynamic_core_contraction" "The geometry order needs a native unit test."
Assert-Contains $projection "projection_zone_replace_video_uses_full_per_eye_carrier" "Replace-video coverage needs a native unit test."

Assert-Contains $wsi "camera_hwb_projection_zone_frame(" "Render recording must create one zone snapshot for the display frame."
Assert-Contains $wsi "projection_zone_frame.settings.replaces_video()" "Only a ready replacement compositor may suppress the separate video draw."
Assert-Contains $wsi "record_projection_zone_compositor_in_open_render_pass" "The ready path must record the composite in the final render pass."
Assert-Contains $wsi '"camera-fallback-unused"' "Replace-video coverage must not wait for a decoded video descriptor it never samples."
Assert-Contains $wsi '"transparent-underlay-fallback-unused"' "The direct Spatial-video underlay must not require a sampled video descriptor."
Assert-Contains $wsi "transparent_underlay_requested()" "The separate same-surface video draw must be suppressed for direct Spatial underlay."
Assert-Contains $wsi "vk::CompositeAlphaFlagsKHR::PRE_MULTIPLIED" "The Android swapchain must prefer premultiplied alpha."
Assert-Contains $wsi "projectionZoneRendered=" "The renderer must report effective compositor adoption rather than only requested controls."
Assert-Contains $runtime "prepare_projection_zone_compositor" "The renderer must prepare the live zone UBO and video-aware pipeline."
Assert-Contains $runtime "pipeline.video_descriptor_set_layout != video_descriptor_set_layout" "The video descriptor layout must participate in pipeline compatibility."
Assert-Contains $runtime "!zone_frame.settings.synthetic_diagnostic()" "Synthetic color diagnostics must bypass live guide displacement."
Assert-Contains $build "PRIVATE_LAYER_VIDEO_COMPOSITOR=0" "The build must preserve the exact legacy projection shader variant."
Assert-Contains $build "PRIVATE_LAYER_VIDEO_COMPOSITOR=1" "The build must compile the video-aware compositor variant."
Assert-Contains $readme "user scale -> dynamic core -> stretch/seams -> video carrier" "The public adapter README must document the geometry order."

Write-Output "Spatial Camera Panel projection-zone compositor static checks passed."
