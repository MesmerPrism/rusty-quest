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
        throw "Missing native renderer GPU SDF static file ($Label): $Path"
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
        if ($Text -notmatch $token) {
            throw "Native renderer GPU SDF static check failed for ${Label}: missing token: $token"
        }
    }
}

$nativeBuildRs = Read-RequiredText (Join-Path $nativeRoot "build.rs") "native build script"
$nativeLib = Read-RequiredText (Join-Path $srcRoot "lib.rs") "native lib"
$nativeCamera = Read-RequiredText (Join-Path $srcRoot "native_camera.rs") "native camera"
$gpuSdfField = Read-RequiredText (Join-Path $srcRoot "gpu_sdf_field.rs") "GPU SDF field"
$nativeRendererOptionSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_properties.rs") "native renderer properties"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_property_values.rs") "native renderer property values"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_visual_options.rs") "native renderer visual options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options.rs") "native renderer options facade"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options_tests.rs") "native renderer options tests")
) -join "`n"
$gpuHandSkinningShader = Read-RequiredText `
    (Join-Path $shaderRoot "gpu_hand_skinning.comp.glsl") `
    "GPU hand skinning shader"
$gpuSdfFieldShader = Read-RequiredText `
    (Join-Path $shaderRoot "gpu_sdf_field.comp.glsl") `
    "GPU SDF field shader"
$gpuSdfTileBinsShader = Read-RequiredText `
    (Join-Path $shaderRoot "gpu_sdf_tile_bins.comp.glsl") `
    "GPU SDF tile-bin shader"
$gpuSdfOverlayShader = Read-RequiredText `
    (Join-Path $shaderRoot "gpu_sdf_overlay.frag.glsl") `
    "GPU SDF overlay shader"
$xrVulkanSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan.rs") "xr_vulkan facade"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\replay_visual_stats.rs") "xr_vulkan replay visual stats"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\scorecard.rs") "xr_vulkan scorecard")
) -join "`n"

Assert-ContainsTokens "$nativeBuildRs`n$nativeLib`n$nativeCamera`n$nativeRendererOptionSurface`n$gpuSdfField`n$gpuHandSkinningShader`n$gpuSdfFieldShader`n$gpuSdfTileBinsShader`n$gpuSdfOverlayShader`n$xrVulkanSurface" @(
    'mod gpu_sdf_field',
    'GpuSdfFieldRenderer',
    'GpuSdfFieldFrameStats',
    'gpu_hand_skinning.comp.glsl',
    'gpu_sdf_field.comp.glsl',
    'gpu_sdf_tile_bins.comp.glsl',
    'gpu_sdf_overlay.frag.glsl',
    'create_compute_pipelines',
    'PipelineBindPoint::COMPUTE',
    'cmd_dispatch',
    'sdfFieldSource=recorded-compact-joint-skinned-mesh-gpu-field',
    'legacySdfFieldSource=recorded-validation-mesh-target-space-gpu-field',
    'sdfComputePath=native-vulkan-compute-recorded-skinned-mesh-sdf-field',
    'legacySdfComputePath=native-vulkan-compute-recorded-validation-mesh-sdf-field',
    'dynamicSdfReady=\{\}',
    'sdfVisualEffectVisible=\{\}',
    'skinning_ready: true',
    'field_update_dispatched',
    'field_reused',
    'gpuSdfFieldReady',
    'gpuSdfOverlayVisible',
    'gpuSdfMeshVertexCount',
    'gpuSdfMeshTriangleCount',
    'sdfUpdateCadenceFrames',
    'sdfFieldUpdateDispatched',
    'sdfFieldReused',
    'sdfFieldCacheHits',
    'sdfCompactInputSource',
    'liveSdfVisualAcceptance',
    'sdfTriangleBinsReady=false',
    'sdfNarrowBandReady=false',
    'sdfNarrowBandMode=tile-local-triangle-bin-band-cull',
    'debug.rustyquest.native_renderer.sdf.update_period_frames',
    'gpuSdfRuntimeJointPoseBufferBytes',
    'gpuSdfTipLengthBufferBytes',
    'gpuSdfCompactJointFrameUploadBytes',
    'gpuSdfJointMatrixBufferBytes',
    'gpuSdfSkinnedPositionBufferBytes',
    'cpuSdfPerFrame=false',
    'meshToSdfKernel=\{\}',
    'targetSpaceMeshToSdfKernelAvailable=true',
    'SkinnedWorldPositions',
    'target_space_uv',
    'sdfProjectionInputCoordinateSpace=openxr-reference-space',
    'sdfProjectionOutputCoordinateSpace=metadata-target-screen-uv',
    'fullSkinnedMeshSdfReady=\{\}',
    'compactJointSkinningKernel=\{\}',
    'jointMatrixSkinningKernel=false',
    'jointMatrixUploadPerFrame=false',
    'compactJointPoseUploadPerFrame=true',
    'sourceMeshToSdfKernel=\{\}',
    'record_sdf_field_update'
) "GPU SDF field route"

Assert-ContainsTokens $gpuSdfTileBinsShader @(
    'SDF_TILE_GRID_WIDTH',
    'SDF_MAX_TRIANGLES_PER_TILE',
    'coherent buffer SdfTileHeaders',
    'writeonly buffer SdfTileIndices',
    'writeonly buffer SdfTriangleBounds',
    'atomicAdd',
    'triangle_bounds\.bounds',
    'tile_indices\.indices',
    'band_radius',
    'min_tile',
    'max_tile'
) "GPU SDF tile-bin shader"

Assert-ContainsTokens $gpuSdfFieldShader @(
    'readonly buffer SdfTileHeaders',
    'readonly buffer SdfTileIndices',
    'readonly buffer SdfTriangleBounds',
    'tile_headers\.headers',
    'tile_indices\.indices',
    'triangle_bounds\.bounds',
    'tile_triangle_count',
    'for \(uint slot = 0u; slot < tile_triangle_count; slot\+\+\)'
) "GPU SDF field shader"

Write-Host "Rusty Quest native renderer GPU SDF static validation passed"
