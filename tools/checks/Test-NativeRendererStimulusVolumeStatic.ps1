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
$shaderRoot = Join-Path $nativeRoot "shaders"

function Read-RequiredText {
    param(
        [string]$Path,
        [string]$Label
    )
    if (-not (Test-Path $Path)) {
        throw "Missing native renderer stimulus-volume static file ($Label): $Path"
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
            throw "Native renderer stimulus-volume static check failed for ${Label}: missing token: $token"
        }
    }
}

$nativeBuildRs = Read-RequiredText (Join-Path $nativeRoot "build.rs") "native build script"
$nativeLib = Read-RequiredText (Join-Path $srcRoot "lib.rs") "native lib"
$gpuStimulusVolume = Read-RequiredText (Join-Path $srcRoot "gpu_stimulus_volume.rs") "stimulus-volume renderer"
$openxrStimulusActions = Read-RequiredText (Join-Path $srcRoot "openxr_stimulus_actions.rs") "OpenXR stimulus actions"
$nativeRendererTiming = Read-RequiredText (Join-Path $srcRoot "native_renderer_timing.rs") "native renderer timing"
$nativeRendererOptionSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_properties.rs") "native renderer properties"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_property_values.rs") "native renderer property values"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_stimulus_volume_options.rs") "stimulus-volume options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options.rs") "native renderer options facade"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options_tests.rs") "native renderer options tests")
) -join "`n"
$stimulusVolumeComputeShader = Read-RequiredText (Join-Path $shaderRoot "stimulus_volume_raymarch.comp.glsl") "stimulus-volume compute shader"
$stimulusVolumeVertexShader = Read-RequiredText (Join-Path $shaderRoot "stimulus_volume_projection.vert.glsl") "stimulus-volume projection vertex shader"
$stimulusVolumeFragmentShader = Read-RequiredText (Join-Path $shaderRoot "stimulus_volume_projection.frag.glsl") "stimulus-volume projection fragment shader"
$xrVulkanSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan.rs") "xr_vulkan facade"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\scorecard.rs") "xr_vulkan scorecard")
) -join "`n"

Assert-ContainsTokens "$nativeBuildRs`n$nativeLib`n$nativeRendererOptionSurface`n$gpuStimulusVolume`n$openxrStimulusActions`n$nativeRendererTiming`n$stimulusVolumeComputeShader`n$stimulusVolumeVertexShader`n$stimulusVolumeFragmentShader`n$xrVulkanSurface" @(
    'mod gpu_stimulus_volume',
    'mod openxr_stimulus_actions',
    'GpuStimulusVolumeRenderer',
    'GpuStimulusVolumeFrameStats',
    'StimulusVolumeActions',
    'stimulus_volume_raymarch.comp.glsl',
    'stimulus_volume_projection.vert.glsl',
    'stimulus_volume_projection.frag.glsl',
    'image2DArray',
    'sampler2DArray',
    'PipelineBindPoint::COMPUTE',
    'cmd_dispatch',
    'PipelineBindPoint::GRAPHICS',
    'cmd_draw',
    'renderPath=native-vulkan-stimulus-volume',
    'makepadRuntime=false',
    'hostessRuntime=false',
    'volumeOnly=true',
    'volumeColorMode=DepthRamp',
    'volumeCompositing=opaque-black-projection',
    'volumeResolutionTier=',
    'volumeCentralFovFraction=',
    'volumeGradientSmoothing=',
    'volumePatternVocabulary=',
    'volumePatternFamily=',
    'randomizeHzRange={:.3}-{:.3}',
    'stimulusVolumeRandomizeMode=trevor-vocabulary-temporal-spatial',
    'status=startup-dynamics',
    'headset-randomize-count-28-2026-06-20',
    '3.083_864',
    '35.362_293',
    '37.530_54',
    '0.103_063',
    '0.964_848',
    '3.835_902',
    'stimulusVolumePatternFamily=',
    'stimulusVolumeMirrorMode=',
    'stimulusVolumeTwist=',
    'stimulusVolumePinch=',
    'stimulusVolumeScramble=',
    'stimulusVolumeJumble=',
    'stimulusVolumeStretch=',
    'stimulusVolumeSpatialOscillatorHz=',
    'stimulusVolumeSpatialFrequencyScale=',
    'stimulusVolumeSpatialSourceShift=',
    'stimulusVolumeSpatialNoiseScale=',
    'stimulusVolumeDepthWarp=',
    'stimulusSafetyClass=PhotosensitiveRisk',
    'stimulusVolumeGpuBuffersResident=true',
    'stimulusVolumeExpandedVolumeUploadPerFrame=false',
    'stimulusVolumeProjectionPath=central-fov-stereo-sampled-storage-image',
    'StimulusVolumeCompute',
    'StimulusVolumeProjection',
    'stimulusVolumeComputeGpuMs',
    'stimulusVolumeProjectionGpuMs',
    'last_reported_randomize_count',
    'randomize_count_changed',
    'familyPattern',
    'applyTrevorWarp',
    'randomized-trevor-vocabulary',
    'trevor-mix',
    'stripes',
    'ripples',
    'rays',
    'checker',
    'spiral',
    'noise-field',
    'centralFov',
    'qualityParams',
    'clamp(profile.depthParams.z, 1.0, 48.0)',
    'right_primary_randomize',
    'right_trigger_panel_toggle',
    'rightControllerTriggerPanelToggle=true',
    'event=right-trigger-panel-toggle status=triggered',
    'status=polled frame={} rightControllerPrimaryButtonRandomize=true',
    'rightPrimaryResetAction=false',
    'rightBreathHapticAction={}',
    'rightControllerPrimaryButtonRandomize={}',
    '/user/hand/right/input/a/click',
    'sync_actions',
    'projection_layer_alpha_blend',
    'record_compute_frame',
    'record_projection_eye'
) "stimulus-volume GPU route"

Write-Host "Rusty Quest native renderer stimulus-volume static validation passed"
