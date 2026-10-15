# Project authenticated descriptor selections into compile-time panel policy.
function Get-NativeRendererPanelCompositionSelection {
    [CmdletBinding()]
    param([AllowNull()]$FeatureLock, [Parameter(Mandatory)][hashtable]$BuildEnvByName)
    $ids = if ($null -eq $FeatureLock) { @() } else { @($FeatureLock.selected_feature_ids) }
    $blackFeature = 'renderer.background.solid_black.composable'
    $originalFeature = 'renderer.private_particles.original_composable'
    $blackEnv = 'RUSTY_QUEST_NATIVE_RENDERER_BACKGROUND_MODE'
    $neutralEnv = 'RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS'
    foreach ($rule in @(@($blackFeature,$blackEnv,'solid-black'),@($originalFeature,$neutralEnv,'false'))) {
        $selected = $ids -ccontains $rule[0]
        if ($selected) {
            if ($ids -cnotcontains 'renderer.native_scene' -or
                -not $BuildEnvByName.ContainsKey($rule[1]) -or $BuildEnvByName[$rule[1]] -cne $rule[2]) {
                throw 'Panel composition needs its exact selected native scene and descriptor build value.'
            }
        } elseif ($BuildEnvByName.ContainsKey($rule[1]) -or
            -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($rule[1], 'Process'))) {
            throw 'Panel composition build input requires its exact selected descriptor.'
        }
    }
    if ($ids -ccontains $originalFeature -and ($ids -cnotcontains 'ui.native_mesh_panel' -or $ids -cnotcontains $blackFeature)) {
        throw 'Original panel composition requires the mesh panel and explicit composable background.'
    }
    [pscustomobject]@{solid_black_worldspace_selected=($ids -ccontains $blackFeature);neutral_mesh_controls_enabled=($ids -cnotcontains $originalFeature)}
}
Export-ModuleMember -Function Get-NativeRendererPanelCompositionSelection
