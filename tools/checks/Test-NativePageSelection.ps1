#requires -Version 7.6
[CmdletBinding()]
param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'))
$ErrorActionPreference='Stop'
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererPanelCompositionSelection.psm1') -Force
function Deny([scriptblock]$work){$denied=$false;try{& $work|Out-Null}catch{$denied=$true};if(-not $denied){throw 'Invalid native-page compile selection was accepted.'}}
$name='RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_NATIVE_PAGE'
$prior=[Environment]::GetEnvironmentVariable($name,'Process')
try {
 [Environment]::SetEnvironmentVariable($name,$null,'Process')
 foreach($value in @('true','false','invalid')){$map=@{};$map[$name]=$value;Deny {Get-NativeRendererPanelCompositionSelection -FeatureLock $null -BuildEnvByName $map}}
 [Environment]::SetEnvironmentVariable($name,'true','Process')
 Deny {Get-NativeRendererPanelCompositionSelection -FeatureLock $null -BuildEnvByName @{}}
 [Environment]::SetEnvironmentVariable($name,$null,'Process')
 $ids=@('ui.native_mesh_panel.original_product','renderer.private_particles','renderer.background.solid_black','ui.breath_composition_control_panel','input.simultaneous_hands_and_controllers','ui.native_mesh_panel.hybrid','ui.native_mesh_panel.native_page')
 $map=@{RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ENABLED='true';RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ORIGINAL_SCENE='true';RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS='false';RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_HYBRID='true';RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_NATIVE_PAGE='true'}
 $selection=Get-NativeRendererPanelCompositionSelection -FeatureLock @{selected_feature_ids=$ids} -BuildEnvByName $map
 if(-not $selection.native_page_selected -or -not $selection.original_scene_mesh_selected){throw 'Native-page selection replaced or omitted its original owner.'}
 foreach($value in @('false','invalid','TRUE')){$bad=$map.Clone();$bad[$name]=$value;Deny {Get-NativeRendererPanelCompositionSelection -FeatureLock @{selected_feature_ids=$ids} -BuildEnvByName $bad}}
 $bad=$map.Clone();$bad.Remove($name);Deny {Get-NativeRendererPanelCompositionSelection -FeatureLock @{selected_feature_ids=$ids} -BuildEnvByName $bad}
 $bad=$map.Clone();$bad.RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_HYBRID='false';Deny {Get-NativeRendererPanelCompositionSelection -FeatureLock @{selected_feature_ids=$ids} -BuildEnvByName $bad}
 foreach($required in @('ui.native_mesh_panel.hybrid','ui.native_mesh_panel.original_product')){$badIds=@($ids|Where-Object {$_ -cne $required});Deny {Get-NativeRendererPanelCompositionSelection -FeatureLock @{selected_feature_ids=$badIds} -BuildEnvByName $map}}
 $ordinary=@($ids|Where-Object {$_ -cne 'ui.native_mesh_panel.native_page'});$normal=$map.Clone();$normal.Remove($name)
 $selection=Get-NativeRendererPanelCompositionSelection -FeatureLock @{selected_feature_ids=$ordinary} -BuildEnvByName $normal
 if($selection.native_page_selected -or -not $selection.original_scene_mesh_selected){throw 'Existing hybrid route changed.'}
 'PASS native-page compile selection, ambient/value/prerequisite rejection, original hybrid preserved.'
} finally {[Environment]::SetEnvironmentVariable($name,$prior,'Process')}
