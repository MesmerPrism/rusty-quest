# Project authenticated descriptor selections into compile-time panel policy.
function Get-NativeRendererPanelCompositionSelection {
    [CmdletBinding()]
    param([AllowNull()]$FeatureLock, [Parameter(Mandatory)][hashtable]$BuildEnvByName)
    $ids = if ($null -eq $FeatureLock) { @() } else { @($FeatureLock.selected_feature_ids) }
    $blackFeature = 'renderer.background.solid_black.composable'
    $originalFeature = 'renderer.private_particles.original_composable'
    $originalSceneFeature = 'ui.native_mesh_panel.original_product'
    $originalSceneEnv = 'RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ORIGINAL_SCENE'
    $originalScene = $ids -ccontains $originalSceneFeature
    $blackEnv = 'RUSTY_QUEST_NATIVE_RENDERER_BACKGROUND_MODE'
    $neutralEnv = 'RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS'
    $nativePageEnv = 'RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_NATIVE_PAGE'
    $nativePage = $ids -ccontains 'ui.native_mesh_panel.native_page'
    if ($nativePage) {
        if (-not $originalScene -or $ids -cnotcontains 'ui.native_mesh_panel.hybrid' -or
            -not $BuildEnvByName.ContainsKey($nativePageEnv) -or $BuildEnvByName[$nativePageEnv] -cne 'true' -or
            -not $BuildEnvByName.ContainsKey('RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_HYBRID') -or
            $BuildEnvByName['RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_HYBRID'] -cne 'true') {
            throw 'Native form presentation requires its exact selected descriptor, hybrid substrate and original owner.'
        }
    } elseif ($BuildEnvByName.ContainsKey($nativePageEnv) -or
        -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($nativePageEnv,'Process'))) {
        throw 'Native form compile selection requires its exact selected descriptor.'
    }
    foreach ($rule in @(@($blackFeature,$blackEnv,'solid-black'),@($originalFeature,$neutralEnv,'false'))) {
        $selected = $ids -ccontains $rule[0] -or ($originalScene -and $rule[1] -ceq $neutralEnv)
        if ($selected) {
            if ((-not $originalScene -and $ids -cnotcontains 'renderer.native_scene') -or
                -not $BuildEnvByName.ContainsKey($rule[1]) -or $BuildEnvByName[$rule[1]] -cne $rule[2]) {
                throw 'Panel composition needs its exact selected native scene and descriptor build value.'
            }
        } elseif ($BuildEnvByName.ContainsKey($rule[1]) -or
            -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($rule[1], 'Process'))) {
            throw 'Panel composition build input requires its exact selected descriptor.'
        }
    }
    if ($originalScene) {
        foreach ($required in @('renderer.private_particles','renderer.background.solid_black','ui.breath_composition_control_panel','input.simultaneous_hands_and_controllers')) {
            if ($ids -cnotcontains $required) { throw 'Original mesh UI requires the exact original renderer, background, panel and simultaneous input.' }
        }
        foreach ($excluded in @('ui.native_mesh_panel','renderer.native_scene',$blackFeature,$originalFeature)) {
            if ($ids -ccontains $excluded) { throw 'Original mesh UI cannot select the worldspace/composable render route.' }
        }
        foreach ($required in @(@($originalSceneEnv,'true'),@('RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ENABLED','true'))) {
            if (-not $BuildEnvByName.ContainsKey($required[0]) -or $BuildEnvByName[$required[0]] -cne $required[1]) { throw 'Original mesh UI requires its exact descriptor build selections.' }
        }
    } elseif ($BuildEnvByName.ContainsKey($originalSceneEnv) -or -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($originalSceneEnv,'Process'))) {
        throw 'Original mesh UI compile selection requires its exact selected descriptor.'
    }
    if ($ids -ccontains $originalFeature -and ($ids -cnotcontains 'ui.native_mesh_panel' -or $ids -cnotcontains $blackFeature)) {
        throw 'Original panel composition requires the mesh panel and explicit composable background.'
    }
    [pscustomobject]@{solid_black_worldspace_selected=($ids -ccontains $blackFeature);neutral_mesh_controls_enabled=($ids -cnotcontains $originalFeature -and -not $originalScene);original_scene_mesh_selected=$originalScene;native_page_selected=$nativePage}
}
Export-ModuleMember -Function Get-NativeRendererPanelCompositionSelection
