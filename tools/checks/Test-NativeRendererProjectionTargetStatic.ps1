param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = Resolve-Path $RepoRoot
$appRoot = Join-Path $repoRootPath "apps\native-renderer-android"
$nativeRoot = Join-Path $appRoot "native"
$srcRoot = Join-Path $nativeRoot "src"

function Read-RequiredText {
    param(
        [string]$Path,
        [string]$Label
    )
    if (-not (Test-Path $Path)) {
        throw "Missing native renderer projection-target static file ($Label): $Path"
    }
    return Get-Content -Raw -Path $Path
}

function Assert-ContainsTokens {
    param(
        [string]$Text,
        [string[]]$Tokens,
        [string]$Label
    )
    foreach ($token in $Tokens) {
        if ($Text -notmatch [regex]::Escape($token)) {
            throw "Native renderer projection-target static check failed for ${Label}: missing token: $token"
        }
    }
}

$nativeLib = Read-RequiredText (Join-Path $srcRoot "lib.rs") "native lib"
$projectionTargetState = Read-RequiredText (Join-Path $srcRoot "projection_target_state.rs") "projection target state"
$manifoldBreathBridge = Read-RequiredText (Join-Path $srcRoot "manifold_breath_bridge.rs") "Manifold breath bridge"
$manifoldPosePublisher = Read-RequiredText (Join-Path $srcRoot "manifold_pose_publisher.rs") "Manifold pose publisher"
$openxrStimulusActions = Read-RequiredText (Join-Path $srcRoot "openxr_stimulus_actions.rs") "OpenXR stimulus actions"
$nativeRendererOptionSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_camera_options.rs") "native renderer camera options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_properties.rs") "native renderer properties"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_property_values.rs") "native renderer property values"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_environment_depth_options.rs") "environment-depth options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_hand_anchor_particle_options.rs") "hand-anchor particle options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_projection_border_stretch_options.rs") "projection-border options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_stimulus_volume_options.rs") "stimulus-volume options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_visual_options.rs") "native renderer visual options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options.rs") "native renderer options facade"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options_tests.rs") "native renderer options tests")
) -join "`n"
$xrVulkanSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan.rs") "xr_vulkan facade"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\replay_visual_stats.rs") "xr_vulkan replay visual stats"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\scorecard.rs") "xr_vulkan scorecard")
) -join "`n"

Assert-ContainsTokens "$nativeLib`n$nativeRendererOptionSurface`n$projectionTargetState`n$manifoldBreathBridge`n$manifoldPosePublisher`n$openxrStimulusActions`n$xrVulkanSurface" @(
    'mod projection_target_state',
    'mod manifold_breath_bridge',
    'mod manifold_pose_publisher',
    'ProjectionTargetState',
    'ProjectionTargetSettings',
    'ProjectionTargetInput',
    'ProjectionTargetScaleDriver',
    'ToggleScaleDriver',
    'BreathBridgeMode',
    'ManifoldBreathBridge',
    'ManifoldPosePublisher',
    'ManifoldPosePublisherConfig',
    'ManifoldPoseSample',
    'stream.breath.state',
    'stream.breath.state.value',
    'stream.motion.object_pose',
    'provider.native_renderer.controller_pose',
    'controller_pose_provider',
    'rusty.manifold.motion.object_pose.sample.v1',
    'publish_stream_event',
    'source_agnostic',
    'controller_specific_estimator',
    'subscribe',
    'right_thumbstick_y',
    'right_primary_reset',
    'right_secondary_scale_driver_toggle',
    'right_grip_pose',
    'right_breath_haptic',
    'create_space',
    'space.locate',
    'HapticVibration',
    'apply_feedback',
    '/user/hand/right/input/thumbstick/y',
    '/user/hand/right/input/b/click',
    '/user/hand/right/input/grip/pose',
    '/user/hand/right',
    '/user/hand/right/output/haptic',
    'projectionTargetScaleDriver',
    'projectionTargetPmbAvailable',
    'rightControllerSecondaryScaleDriverToggle',
    'rightGripPoseTracked',
    'rightBreathHapticAction={}',
    'rightBreathHapticSubaction',
    'breathHapticsEnabled',
    'breathHapticRequiresScaleDriver=pmb',
    'breathHapticRequiresRightGripTracked',
    'breathHapticPulseHz',
    'breathHapticAmplitude',
    'breathHapticDurationMs',
    'nativeControllerPosePublisherEnabled',
    'nativeControllerPosePublishedCount',
    'highRatePoseViaManifold=true',
    'projectionTargetRuntimeAuthority=native-renderer',
    'startupDefaultsAuthority=runtime-profile',
    'pmbSourceAuthority=hostess-manifold',
    'highRateBreathViaAndroidProperties=false',
    'highRatePoseViaAndroidProperties=false',
    'debug.rustyquest.native_renderer.projection.target.breath.high_rate_json_payload'
) "Breathing Room projection-target route"

Write-Host "Rusty Quest native renderer projection-target static validation passed"
