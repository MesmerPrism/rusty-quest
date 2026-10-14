# The caller first authenticates the feature lock and its generated build-env bytes.
# This admission projects that selected composition; it never parses a second lock.
function Get-NativeRendererHandGeometrySelection {
    [CmdletBinding()]
    param(
        [AllowNull()]$FeatureLock,
        [Parameter(Mandatory)][hashtable]$BuildEnvByName,
        [string]$PreparedHandRigSource = '',
        [string]$ExpectedPreparedHandRigSourceSha256 = '',
        [string]$RecordedHandCaptureDir = '',
        [switch]$RequireRecordedHandCapture
    )
    $featureId = 'renderer.native_scene.particles_only'
    $envName = 'RUSTY_QUEST_NATIVE_RENDERER_HAND_GEOMETRY_ENABLED'
    $selected = $null -ne $FeatureLock -and @($FeatureLock.selected_feature_ids) -ccontains $featureId
    if (-not $selected) {
        # The unlocked development route has no generated env to overwrite an
        # ambient value. Reject it here before cargo and packaging can disagree.
        if ($BuildEnvByName.ContainsKey($envName) -or
            -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($envName, 'Process'))) {
            throw 'Hand geometry exclusion requires the exact particles-only scene feature.'
        }
        return [pscustomobject]@{ particles_only_selected = $false; hand_geometry_enabled = $true }
    }
    if (@($FeatureLock.selected_feature_ids) -cnotcontains 'renderer.native_scene' -or
        -not $BuildEnvByName.ContainsKey($envName) -or $BuildEnvByName[$envName] -cne 'false') {
        throw 'Particles-only scene requires its native scene and exact hand-geometry exclusion.'
    }
    $conflicts = @($FeatureLock.selected_feature_ids | Where-Object {
        $_ -cin @('hand_mesh_visual','hand_mesh_live_input','hand_anchor_particles','sdf_visual')
    })
    if ($conflicts.Count -gt 0) {
        throw "Particles-only scene selects incompatible geometry: $($conflicts -join ', ')."
    }
    if (-not [string]::IsNullOrWhiteSpace($PreparedHandRigSource) -or
        -not [string]::IsNullOrWhiteSpace($ExpectedPreparedHandRigSourceSha256) -or
        -not [string]::IsNullOrWhiteSpace($RecordedHandCaptureDir) -or $RequireRecordedHandCapture) {
        throw 'Particles-only scene forbids prepared or captured hand geometry inputs.'
    }
    return [pscustomobject]@{ particles_only_selected = $true; hand_geometry_enabled = $false }
}
Export-ModuleMember -Function Get-NativeRendererHandGeometrySelection
