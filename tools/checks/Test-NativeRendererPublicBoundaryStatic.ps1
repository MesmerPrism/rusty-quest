param(
    [string]$RepoRoot,
    [string[]]$SourcePaths = @()
)

$ErrorActionPreference = "Stop"

if ($SourcePaths.Count -eq 0) {
    if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
        throw "RepoRoot is required when SourcePaths are not provided."
    }

    $repoRootPath = Resolve-Path $RepoRoot
    $appRoot = Join-Path $repoRootPath "apps\native-renderer-android"
    $SourcePaths = @(
        (Join-Path $appRoot "AndroidManifest.xml"),
        (Join-Path $appRoot "native\Cargo.toml"),
        (Join-Path $appRoot "native\build.rs"),
        (Join-Path $appRoot "native\src\lib.rs"),
        (Join-Path $appRoot "native\src\android_events.rs"),
        (Join-Path $appRoot "native\src\native_camera.rs"),
        (Join-Path $appRoot "native\src\acamera_sys.rs"),
        (Join-Path $appRoot "native\src\camera_projection.rs"),
        (Join-Path $appRoot "native\src\camera_projection_metadata.rs"),
        (Join-Path $appRoot "native\src\environment_depth_geometry.rs"),
        (Join-Path $appRoot "native\src\guide_blur_graph.rs"),
        (Join-Path $appRoot "native\shaders\guide_blur_downsample.frag.glsl"),
        (Join-Path $appRoot "native\shaders\guide_blur_5tap.frag.glsl"),
        (Join-Path $appRoot "native\shaders\guide_projection.frag.glsl"),
        (Join-Path $appRoot "native\src\recorded_hand_replay.rs"),
        (Join-Path $appRoot "native\src\live_hand_compact.rs"),
        (Join-Path $appRoot "native\src\native_renderer_camera_options.rs"),
        (Join-Path $appRoot "native\src\native_renderer_properties.rs"),
        (Join-Path $appRoot "native\src\native_renderer_property_values.rs"),
        (Join-Path $appRoot "native\src\native_renderer_environment_depth_options.rs"),
        (Join-Path $appRoot "native\src\native_renderer_hand_anchor_particle_options.rs"),
        (Join-Path $appRoot "native\src\native_renderer_projection_border_stretch_options.rs"),
        (Join-Path $appRoot "native\src\native_renderer_stimulus_volume_options.rs"),
        (Join-Path $appRoot "native\src\native_renderer_visual_options.rs"),
        (Join-Path $appRoot "native\src\native_renderer_options.rs"),
        (Join-Path $appRoot "native\src\native_renderer_options_tests.rs"),
        (Join-Path $appRoot "native\src\hand_mesh_graft.rs"),
        (Join-Path $appRoot "native\src\gpu_hand_mesh_visual.rs"),
        (Join-Path $appRoot "native\shaders\hand_mesh_visual.vert.glsl"),
        (Join-Path $appRoot "native\shaders\hand_mesh_visual.frag.glsl"),
        (Join-Path $appRoot "native\src\gpu_mesh_replay.rs"),
        (Join-Path $appRoot "native\src\gpu_sdf_field.rs"),
        (Join-Path $appRoot "native\shaders\gpu_hand_skinning.comp.glsl"),
        (Join-Path $appRoot "native\shaders\gpu_sdf_field.comp.glsl"),
        (Join-Path $appRoot "native\shaders\gpu_sdf_tile_bins.comp.glsl"),
        (Join-Path $appRoot "native\shaders\gpu_sdf_overlay.frag.glsl"),
        (Join-Path $appRoot "native\src\xr_vulkan.rs"),
        (Join-Path $appRoot "native\src\xr_vulkan\replay_visual_stats.rs"),
        (Join-Path $appRoot "native\src\xr_vulkan\scorecard.rs"),
        (Join-Path $repoRootPath "tools\Build-NativeRendererAndroid.ps1")
    )
}

$sourceParts = New-Object System.Collections.Generic.List[string]
foreach ($path in $SourcePaths) {
    if (-not (Test-Path $path)) {
        throw "Missing native renderer public boundary source file: $path"
    }
    $sourceParts.Add((Get-Content -Raw -Path $path))
}
$sourceCombined = $sourceParts -join "`n"

$forbiddenRouteTokens = @(
    ("RUSTY" + "_XR_"),
    ("rusty" + ".xr."),
    ("/rusty" + "xr/v1"),
    ("com.example." + "rustyxr.broker"),
    "Makepad",
    "NativeRendererStartActivity",
    "HardwareBuffer\.fromHardwareBuffer"
)
foreach ($token in $forbiddenRouteTokens) {
    if ($sourceCombined -cmatch $token) {
        throw "Native renderer Android scaffold contains forbidden route token: $token"
    }
}

foreach ($token in @('colorama', 'rusty-vision-colorama')) {
    if ($sourceCombined -match $token) {
        throw "Native renderer Android public scaffold exposes private effect token: $token"
    }
}

Write-Host "Rusty Quest native renderer public boundary static validation passed"
