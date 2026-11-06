param(
    [string]$RepoRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [Parameter(Mandatory)][string]$FeatureLock,
    [string]$ResultJson
)
$ErrorActionPreference='Stop'
$toolsRoot=Join-Path $RepoRoot 'tools'
Import-Module (Join-Path $toolsRoot 'lib/NativeRendererPanelCompositionSelection.psm1') -Force
$appBuildLockObject=Get-Content -LiteralPath $FeatureLock -Raw | ConvertFrom-Json
$generatedEnv=Get-Content -LiteralPath $appBuildLockObject.generated_outputs.build_env -Raw | ConvertFrom-Json
$appBuildEnvByName=@{};foreach($entry in $generatedEnv.env){$appBuildEnvByName[[string]$entry.name]=[string]$entry.value}
$manifestInputPath=[string]$appBuildLockObject.generated_outputs.android_manifest
$EnableMetaXrOperatorDebugLayer=$false;$MetaXrOperatorLibrary='';$MetaXrOperatorLibrarySha256='';$MetaXrOperatorManifest='';$MetaXrOperatorManifestSha256=''
$PreparedHandRigSource='';$ExpectedPreparedHandRigSourceSha256='';$RecordedHandCaptureDir='';$RequireRecordedHandCapture=$false
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $toolsRoot 'Build-NativeRendererAndroid.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Builder AST invalid.'}
# Locate by the exact selected module and final selector variable, not line numbers.
$start=@($ast.EndBlock.Statements|Where-Object {$_.Extent.Text -match '^Import-Module .*lib/NativeRendererPanelCompositionSelection\.psm1'})
$finish=@($ast.EndBlock.Statements|Where-Object {$_ -is [Management.Automation.Language.AssignmentStatementAst] -and $_.Left.Extent.Text -ceq '$handGeometrySelection'})
if($start.Count -ne 1 -or $finish.Count -ne 1){throw 'Exact full pre-Cargo selection region missing.'}
$region=@($ast.EndBlock.Statements|Where-Object {$_.Extent.StartOffset -ge $start[0].Extent.StartOffset -and $_.Extent.EndOffset -le $finish[0].Extent.EndOffset})
$script:passed=[Collections.Generic.List[string]]::new()
$ambient=@{};foreach($name in @('RUSTY_QUEST_NATIVE_RENDERER_BACKGROUND_MODE','RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS','RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ORIGINAL_SCENE','RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_NATIVE_PAGE')){$ambient[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$null,'Process')}
function Deny([string]$Label,[scriptblock]$Action){$rejected=$false;try{&$Action|Out-Null}catch{$rejected=$true};if(-not $rejected){throw "Expected rejection: $Label"};$script:passed.Add($Label)}
try {
    # Executes the actual builder's panel, Meta XR and hand selectors with the
    # real generated lock/env/manifest. It stops before source composition,
    # output creation, signing, Java/Cargo compilation, caches or device work.
    . ([scriptblock]::Create((($region|ForEach-Object {$_.Extent.Text}) -join "`n").Replace('$PSScriptRoot','$toolsRoot')))
    if(-not $panelCompositionSelection.original_scene_mesh_selected -or -not $panelCompositionSelection.native_page_selected -or $panelCompositionSelection.neutral_mesh_controls_enabled -or $panelCompositionSelection.solid_black_worldspace_selected){throw 'Actual showcase selected the wrong presentation/render policy.'}
    $passed.Add('actual-full-builder-pre-Cargo-selection')
    $required=@('ui.native_mesh_panel.showcase','ui.visual_showcase','renderer.private_particles','renderer.background.solid_black','input.controllers_and_hands_optional')
    foreach($id in $required){$missing=@{selected_feature_ids=@($appBuildLockObject.selected_feature_ids|Where-Object {$_ -cne $id})};Deny ('missing-'+$id) {Get-NativeRendererPanelCompositionSelection -FeatureLock $missing -BuildEnvByName $appBuildEnvByName}}
    $flags=@('RUSTY_QUEST_NATIVE_RENDERER_SHOWCASE','RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ENABLED','RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ORIGINAL_SCENE','RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_HYBRID','RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_NATIVE_PAGE','RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS')
    foreach($name in $flags){$missing=$appBuildEnvByName.Clone();$missing.Remove($name);Deny ('missing-'+$name) {Get-NativeRendererPanelCompositionSelection -FeatureLock $appBuildLockObject -BuildEnvByName $missing};$wrong=$appBuildEnvByName.Clone();$wrong[$name]='invalid';Deny ('invalid-'+$name) {Get-NativeRendererPanelCompositionSelection -FeatureLock $appBuildLockObject -BuildEnvByName $wrong}}
    foreach($extra in @('ui.native_mesh_panel','ui.native_mesh_panel.original_product','ui.native_mesh_panel.hybrid','ui.native_mesh_panel.native_page','renderer.native_scene','renderer.background.solid_black.composable','renderer.private_particles.original_composable','ui.breath_composition_control_panel')){Deny ('mixed-'+$extra) {Get-NativeRendererPanelCompositionSelection -FeatureLock @{selected_feature_ids=@($appBuildLockObject.selected_feature_ids)+$extra} -BuildEnvByName $appBuildEnvByName}}
    $off=Get-NativeRendererPanelCompositionSelection -FeatureLock $null -BuildEnvByName @{}
    if($off.native_page_selected -or $off.original_scene_mesh_selected -or -not $off.neutral_mesh_controls_enabled){throw 'Default-off policy changed.'};$passed.Add('default-off-unchanged')
    foreach($name in @('RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_NATIVE_PAGE','RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ORIGINAL_SCENE','RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS')){[Environment]::SetEnvironmentVariable($name,'true','Process');Deny ('ambient-'+$name) {Get-NativeRendererPanelCompositionSelection -FeatureLock $null -BuildEnvByName @{}};[Environment]::SetEnvironmentVariable($name,$null,'Process')}
    $original=@{selected_feature_ids=@('ui.native_mesh_panel.original_product','renderer.private_particles','renderer.background.solid_black','ui.breath_composition_control_panel','input.simultaneous_hands_and_controllers','ui.native_mesh_panel.hybrid','ui.native_mesh_panel.native_page')}
    $envOriginal=@{};foreach($name in @('RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ENABLED','RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ORIGINAL_SCENE','RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_HYBRID','RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_NATIVE_PAGE')){$envOriginal[$name]='true'};$envOriginal['RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS']='false'
    $old=Get-NativeRendererPanelCompositionSelection -FeatureLock $original -BuildEnvByName $envOriginal
    if(-not $old.native_page_selected -or -not $old.original_scene_mesh_selected -or $old.neutral_mesh_controls_enabled){throw 'Existing original product policy changed.'};$passed.Add('existing-original-native-page-unchanged')
    $world=Get-NativeRendererPanelCompositionSelection -FeatureLock @{selected_feature_ids=@('renderer.native_scene','ui.native_mesh_panel','renderer.private_particles.original_composable','renderer.background.solid_black.composable')} -BuildEnvByName @{'RUSTY_QUEST_NATIVE_RENDERER_BACKGROUND_MODE'='solid-black';'RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS'='false'}
    if(-not $world.solid_black_worldspace_selected -or $world.native_page_selected -or $world.neutral_mesh_controls_enabled){throw 'Existing worldspace composition changed.'};$passed.Add('existing-worldspace-unchanged')
    if($ResultJson){[ordered]@{passed=@($passed);count=$passed.Count;actual_selection=$panelCompositionSelection;full_builder_region_statements=$region.Count;compilation_invoked=$false;apk_assembly_invoked=$false;device_operations=0}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $ResultJson -Encoding utf8}
    Write-Output "PASS $($passed.Count) production selection cases including actual full pre-Cargo builder selector region."
}finally{foreach($name in $ambient.Keys){[Environment]::SetEnvironmentVariable($name,$ambient[$name],'Process')}}
