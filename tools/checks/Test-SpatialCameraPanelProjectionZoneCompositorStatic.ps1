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
$validationCoordinator = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialValidationWorkflowCoordinator.kt"
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
Assert-Contains $controls "fun applyStretchStyle(" "Stretch presets must preserve the independently selected outer target."
Assert-Contains $controls "current.copy(" "Stretch-style application must edit the current independent state rather than replace it."
Assert-Contains $controls "bufferFillMode = bufferFillStretch" "Stretch-style application must select Stretch without changing buffer geometry or Outer target."
Assert-Contains $controls "fun disableStretch(" "Stretch Off must preserve the independently selected outer target."
Assert-Contains $controls "bufferFillMode = bufferFillOuterContinuation" "Stretch Off must change only buffer content rather than remove buffer geometry."
Assert-Contains $controls "regionContractIndependent = 2" "The independent region contract must have an explicit version."
Assert-Contains $controls "regionContractRegionOwned = 3" "The region-owned contract must have an explicit version."
Assert-Contains $controls "regionContractCompositorOwned = 4" "The unified compositor-owned Center contract must have an explicit version."
Assert-Contains $controls "centerContentBlend = 2" "Center must expose a projection/video blend mode."
Assert-Contains $controls "bufferGeometryStatic = 1" "The buffer geometry contract must expose a fixed-width mode."
Assert-Contains $controls "bufferGeometryDynamic = 2" "The buffer geometry contract must expose a dynamic mode."
Assert-Contains $controls "bufferFillTransparentReveal = 1" "The buffer content contract must expose transparent reveal."
Assert-Contains $controls "bufferFillVideo = 3" "The middle region must expose video independently of Outer."
Assert-Contains $controls "outerContentStretch = 1" "Outer Stretch must be a region-owned content choice."
Assert-Contains $controls "bufferMaximumSpeedMetersPerSecond" "Dynamic Buffer must retain the tracked speed threshold."
Assert-Contains $controls "stretchExtentReplaceOuter = 1" "Stretch extent must independently select Outer replacement."
Assert-Contains $controls "applicationMode = applicationRegion" "The underlay preset must use one scalar region alpha."
Assert-Contains $controls "sourceChoice = blendSourceOutgoing" "The underlay preset must derive alpha from the readable outgoing buffer."
Assert-Contains $controls "projectionZoneGeometryOrder=user-scale-then-guard-contraction" "Panel markers must state the scale-before-guard geometry order."
Assert-Contains $panel "private fun RegionSettingsPage(" "The panel must expose a dedicated navigable Regions page."
Assert-Contains $panel 'Middle("Middle buffer"' "The panel must expose Middle buffer as a top-level page."
Assert-Contains $panel 'Outer("Outer region"' "The panel must expose Outer as a top-level page."
Assert-Contains $panel 'Transitions("Transitions"' "The panel must expose Transitions as a top-level page."
Assert-Contains $panel '"Minimum buffer"' "Dynamic Buffer must expose a minimum guard."
Assert-Contains $panel '"Maximum buffer"' "Dynamic Buffer must expose a maximum guard."
Assert-Contains $panel '"Headset speed for maximum buffer (m/s)"' "Dynamic Buffer must expose the speed at which Maximum is reached."
Assert-Contains $panel "private fun OuterRegionPage(" "Outer content must have its own page and conditional controls."
Assert-Contains $panel '"Projection + video"' "Center must expose projection/video blending on the unified carrier."
Assert-Contains $panel '"Projection share"' "Center blending must expose its projection share."
Assert-Contains $panel '"Disable compositor carrier (diagnostic)"' "The whole-carrier switch must be labeled as a diagnostic fallback."
Assert-Contains $panel "private fun RegionStretchSettings(" "Middle and Outer must share a region-parameterized stretch editor."
Assert-Contains $panel "outer = true" "Outer Stretch must use its independent parameter lane."
Assert-Contains $panel "localVideoSession.items.forEach" "The media library must render selectable catalog items."
Assert-Contains $panel 'Buffer("Buffer")' "Regions navigation must include Buffer geometry and content."
Assert-Contains $panel 'Effects("Effects")' "Regions navigation must separate general region effects."
Assert-Contains $panel 'Stretch("Stretch")' "Regions navigation must separate Stretch-only settings."
Assert-Contains $panel 'Transitions("Transitions")' "Regions navigation must separate adjacency-derived transitions."
Assert-Contains $panel 'Outer("Outer")' "Regions navigation must keep Outer composition independent."
Assert-Contains $panel '"Buffer geometry"' "The panel must expose buffer Off, Static, and Dynamic choices."
Assert-Contains $panel '"Buffer content"' "The panel must expose content independently of geometry."
Assert-Contains $panel '"Native"' "The panel must expose the original native Stretch style."
Assert-Contains $panel '"Organic"' "The panel must expose the channel-responsive Stretch style."
Assert-Contains $panel "applyStretchStyle(" "The panel must not replace the outer target when selecting Native or Organic stretch."
Assert-Contains $panel '"Replace Outer"' "The panel must expose full-carrier Stretch as an extent, not a combined mode."
Assert-Contains $panel '"180/360 underlay"' "The panel must expose the direct Spatial-video Outer target."
Assert-Contains $panel "The Outer target does not choose Buffer content." "The panel must explain that Outer composition and buffer content are independent."
Assert-Contains $panel "ProjectionSurfaceDisplacementControls.off" "Synthetic presets must explicitly disable live surface displacement."
Assert-Contains $activity "nativeUpdatePrivateLayerZoneCompositor" "The activity must bridge live compositor settings to native code."
Assert-Contains $activity "nativeUpdatePrivateLayerRegionLayout" "The activity must bridge region-owned content and dynamic Buffer controls."
Assert-Contains $validationCoordinator "currentPrivateLayerZoneCompositor" "Controller-free Native/Organic actions must read the current outer target."
Assert-Contains $validationCoordinator "applyStretchStyle(" "Controller-free Native/Organic actions must preserve layer composition."
Assert-Contains $validationCoordinator "disableStretch(" "The controller-free Off action must preserve layer composition."
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
Assert-Contains $projection "size_of::<ProjectionZoneUniform>() == 416" "The projection-zone UBO ABI must preserve the v3 prefix and append one Center vec4."
Assert-Contains $projection "outer_stretch_options" "The projection-zone UBO must carry independent Outer Stretch source/content controls."
Assert-Contains $projection "center_content" "The projection-zone UBO must carry Center content and blend controls."
Assert-Contains $projection "compositor_owned_center_video_and_blend_share_the_full_carrier" "Unified Center video/blend coverage needs a native regression test."
Assert-Contains $projection "region_owned_contract_keeps_outer_stretch_independent_of_buffer_geometry" "Outer Stretch independence needs a native regression test."
Assert-Contains $projection "settings.outer_target_mode as f32" "The outer target must use the reserved uniform lane without expanding the ABI."
Assert-Contains $projection "transparent_underlay_supported" "Native settings must fail closed on unsupported unsampled-source combinations."
Assert-Contains $projection "projection_zone_applies_user_scale_before_dynamic_core_contraction" "The geometry order needs a native unit test."
Assert-Contains $projection "projection_zone_replace_video_uses_full_per_eye_carrier" "Replace-video coverage needs a native unit test."

Assert-Contains $wsi "camera_hwb_projection_zone_frame(" "Render recording must create one zone snapshot for the display frame."
Assert-Contains $wsi "suppresses_same_surface_video(projection_zone_ready)" "The same-surface video draw must follow the target-aware suppression policy."
Assert-Contains $wsi "record_projection_zone_compositor_in_open_render_pass" "The ready path must record the composite in the final render pass."
Assert-Contains $wsi '"camera-fallback-unused"' "Replace-video coverage must not wait for a decoded video descriptor it never samples."
Assert-Contains $wsi '"transparent-underlay-fallback-unused"' "The direct Spatial-video underlay must not require a sampled video descriptor."
Assert-Contains $wsi "transparent_underlay_requested()" "The separate same-surface video draw must be suppressed for direct Spatial underlay."
Assert-Contains $projection "suppresses_same_surface_video" "Transparent underlay suppression must remain active when stretch coverage is Off."
Assert-Contains $projection "assert!(settings.suppresses_same_surface_video(false))" "Stretch Off must have a native regression test for same-surface video suppression."
Assert-Contains $wsi "vk::CompositeAlphaFlagsKHR::PRE_MULTIPLIED" "The Android swapchain must prefer premultiplied alpha."
Assert-Contains $wsi "projectionZoneRendered=" "The renderer must report effective compositor adoption rather than only requested controls."
Assert-Contains $runtime "prepare_projection_zone_compositor" "The renderer must prepare the live zone UBO and video-aware pipeline."
Assert-Contains $runtime "pipeline.video_descriptor_set_layout != video_descriptor_set_layout" "The video descriptor layout must participate in pipeline compatibility."
Assert-Contains $runtime "!zone_frame.settings.synthetic_diagnostic()" "Synthetic color diagnostics must bypass live guide displacement."
Assert-Contains $build "PRIVATE_LAYER_VIDEO_COMPOSITOR=0" "The build must preserve the exact legacy projection shader variant."
Assert-Contains $build "PRIVATE_LAYER_VIDEO_COMPOSITOR=1" "The build must compile the video-aware compositor variant."
Assert-Contains $readme "user scale -> effective guard contraction -> region content/transitions -> video carrier" "The public adapter README must document the geometry order."
Assert-Contains $readme "Outer Stretch works even when Buffer is Off" "The public adapter README must document Outer Stretch independence."

Write-Output "Spatial Camera Panel projection-zone compositor static checks passed."
