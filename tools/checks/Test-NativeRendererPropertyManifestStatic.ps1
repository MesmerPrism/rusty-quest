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
if ($manifest.property_count -ne 102 -or $manifest.properties.Count -ne 102) {
    throw "Native renderer property manifest must cover the current 102-property runtime surface."
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
    'debug.rustyquest.native_renderer.environment_depth.surface_support.min_neighbors',
    'debug.rustyquest.native_renderer.projection.target.breath.high_rate_json_payload',
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
