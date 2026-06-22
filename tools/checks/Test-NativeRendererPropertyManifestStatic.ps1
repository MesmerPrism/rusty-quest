param(
    [Parameter(Mandatory = $true)]
    [string]$ManifestPath,
    [Parameter(Mandatory = $true)]
    [string]$PropertyParityToolPath
)

$ErrorActionPreference = "Stop"

foreach ($path in @($ManifestPath, $PropertyParityToolPath)) {
    if (-not (Test-Path $path)) {
        throw "Missing native renderer property validation file: $path"
    }
}

$manifestText = Get-Content -Raw -Path $ManifestPath
$propertyParityTool = Get-Content -Raw -Path $PropertyParityToolPath
$manifest = $manifestText | ConvertFrom-Json

if ($manifest.schema -ne "rusty.quest.native_renderer_property_manifest.v2") {
    throw "Native renderer property manifest has an unexpected schema."
}
$expectedPropertyCount = 176
if ($manifest.property_count -ne $expectedPropertyCount -or $manifest.properties.Count -ne $expectedPropertyCount) {
    throw "Native renderer property manifest must cover the current $expectedPropertyCount-property runtime surface."
}
foreach ($entry in @($manifest.properties)) {
    if ([string]$entry.lifecycle -ne "startup-effective") {
        throw "Native renderer property manifest entry $($entry.name) has unexpected lifecycle."
    }
    if ([string]$entry.clear_behavior -ne "profile-owned-explicit-set") {
        throw "Native renderer property manifest entry $($entry.name) has unexpected clear_behavior."
    }
    if ([string]$entry.default_behavior -ne "runtime-owner-default-when-unset") {
        throw "Native renderer property manifest entry $($entry.name) has unexpected default_behavior."
    }
}

foreach ($token in @(
    'debug.rustyquest.native_renderer.stimulus_volume.central_fov_fraction',
    'debug.rustyquest.native_renderer.environment_depth.surface_support.component_mode',
    'debug.rustyquest.native_renderer.environment_depth.native_passthrough.required',
    'debug.rustyquest.native_renderer.environment_depth.alignment.controls',
    'debug.rustyquest.native_renderer.environment_depth.alignment.joystick.controls',
    'debug.rustyquest.native_renderer.environment_depth.alignment.joystick.rate_uv_per_second',
    'debug.rustyquest.native_renderer.environment_depth.alignment.max_offset_uv',
    'debug.rustyquest.native_renderer.environment_depth.alignment.scale',
    'debug.rustyquest.native_renderer.environment_depth.alignment.left.offset.x.uv',
    'debug.rustyquest.native_renderer.environment_depth.alignment.left.offset.y.uv',
    'debug.rustyquest.native_renderer.environment_depth.alignment.right.offset.x.uv',
    'debug.rustyquest.native_renderer.environment_depth.alignment.right.offset.y.uv',
    'debug.rustyquest.native_renderer.environment_depth.surface_support.normal_source',
    'debug.rustyquest.native_renderer.environment_depth.surface_support.small_component_policy',
    'debug.rustyquest.native_renderer.environment_depth.surface_support.min_neighbors',
    'debug.rustyquest.native_renderer.display_composite.high_rate_json_payload',
    'debug.rustyquest.native_renderer.control_panel.mode',
    'debug.rustyquest.native_renderer.video_border_blend.mode',
    'alpha-over',
    'crossfade',
    'linear-crossfade',
    'luma-match',
    'chroma-luma',
    'soft-light',
    'overlay',
    'screen',
    'multiply',
    'gradient-aware',
    'two-band',
    'temporal-stabilized',
    'debug.rustyquest.native_renderer.video_projection.high_rate_json_payload',
    'debug.rustyquest.native_renderer.passthrough.style.mode',
    'debug.rustyquest.native_renderer.passthrough.style.audio_reactive.enabled',
    'debug.rustyquest.native_renderer.passthrough.style.audio_reactive.source',
    'debug.rustyquest.native_renderer.passthrough.style.audio_reactive.update_hz',
    'debug.rustyquest.native_renderer.passthrough.style.color.phase',
    'debug.rustyquest.native_renderer.passthrough.style.edge_color.a',
    'debug.rustyquest.native_renderer.projection.target.breath.high_rate_json_payload',
    'debug.rustyquest.native_renderer.private_particles.visual.scale',
    'debug.rustyquest.native_renderer.private_particles.world_anchor.scale_m',
    'debug.rustyquest.native_renderer.private_particles.driver0.value01',
    'debug.rustyquest.native_renderer.private_particles.driver1.value01',
    'debug.rustyquest.native_renderer.private_particles.driver2.value01',
    'debug.rustyquest.native_renderer.private_particles.driver7.value01',
    'debug.rustyquest.native_renderer.private_particles.tracer.draw_slots_per_oscillator',
    'debug.rustyquest.native_renderer.private_particles.transparency.opacity',
    'debug.rustyquest.native_renderer.private_particles.color.facing_attenuation_strength',
    'gpu_private_particles',
    'xr_vulkan::PrivateParticleWorldAnchor',
    'native_renderer_passthrough_style_options',
    'native_renderer_visual_options',
    'projection_target_state',
    'runtime-parser',
    'startup-effective',
    'profile-owned-explicit-set',
    'runtime-owner-default-when-unset',
    'profile-matrix',
    'rusty-quest-profile',
    'Apply-RuntimeProfile.ps1'
)) {
    if ($manifestText -notmatch [regex]::Escape($token)) {
        throw "Native renderer property manifest missing token: $token"
    }
}

foreach ($token in @(
    'PROPERTY_MANIFEST_PATH',
    'MANIFEST_SCHEMA',
    'REQUIRED_MANIFEST_VALIDATORS',
    'VALID_MANIFEST_LIFECYCLES',
    'VALID_MANIFEST_CLEAR_BEHAVIORS',
    'VALID_MANIFEST_DEFAULT_BEHAVIORS',
    'check_manifest_consumer_wiring',
    'load_property_manifest',
    'validate_profile_values_against_manifest',
    'manifest_properties=',
    'manifest_lifecycle_counts',
    'manifest_clear_behavior_counts',
    'manifest_default_behavior_counts',
    'low_rate_validator_properties=',
    'manifest_missing_required_validators',
    'runtime_missing_manifest_properties'
)) {
    if ($propertyParityTool -notmatch [regex]::Escape($token)) {
        throw "Native renderer property parity checker is not wired to the manifest token: $token"
    }
}

Write-Host "Rusty Quest native renderer property manifest static validation passed"
