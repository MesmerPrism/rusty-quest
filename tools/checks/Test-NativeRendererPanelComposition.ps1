param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'),[string]$OutputDirectory='')
$ErrorActionPreference='Stop'
$RepoRoot=(Resolve-Path -LiteralPath $RepoRoot).Path
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory=Join-Path $RepoRoot ('local-artifacts/panel-composition/'+[guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Fresh test output required.' }
[void][IO.Directory]::CreateDirectory($OutputDirectory)
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererPanelCompositionSelection.psm1') -Force
$black='RUSTY_QUEST_NATIVE_RENDERER_BACKGROUND_MODE';$neutral='RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS'
$previousBlack=[Environment]::GetEnvironmentVariable($black,'Process');$previousNeutral=[Environment]::GetEnvironmentVariable($neutral,'Process')
$passed=[Collections.Generic.List[string]]::new()
function Deny([string]$Label,[scriptblock]$Action) { $failed=$false;try { &$Action|Out-Null } catch { $failed=$true };if(-not $failed){throw "Expected rejection: $Label"};$passed.Add($Label) }
try {
    [Environment]::SetEnvironmentVariable($black,$null,'Process');[Environment]::SetEnvironmentVariable($neutral,$null,'Process')
    $off=Get-NativeRendererPanelCompositionSelection -FeatureLock $null -BuildEnvByName @{}
    if($off.solid_black_worldspace_selected -or -not $off.neutral_mesh_controls_enabled){throw 'Ordinary mesh defaults changed.'};$passed.Add('ordinary-mesh-defaults')
    $ids=@('renderer.native_scene','ui.native_mesh_panel','renderer.private_particles.original_composable','renderer.background.solid_black.composable')
    $lock=@{selected_feature_ids=$ids};$envValues=@{};$envValues[$black]='solid-black';$envValues[$neutral]='false'
    $selected=Get-NativeRendererPanelCompositionSelection -FeatureLock $lock -BuildEnvByName $envValues
    if(-not $selected.solid_black_worldspace_selected -or $selected.neutral_mesh_controls_enabled){throw 'Original composition not selected.'};$passed.Add('original-composition')
    Deny 'unselected-build-env' { Get-NativeRendererPanelCompositionSelection -FeatureLock $null -BuildEnvByName $envValues }
    Deny 'missing-closed-input' { Get-NativeRendererPanelCompositionSelection -FeatureLock $lock -BuildEnvByName @{} }
    $wrong=$envValues.Clone();$wrong[$neutral]='true'
    Deny 'wrong-neutral-selection' { Get-NativeRendererPanelCompositionSelection -FeatureLock $lock -BuildEnvByName $wrong }
    $missing=@{selected_feature_ids=@($ids|Where-Object {$_ -cne 'ui.native_mesh_panel'})}
    Deny 'missing-mesh-feature' { Get-NativeRendererPanelCompositionSelection -FeatureLock $missing -BuildEnvByName $envValues }
    [Environment]::SetEnvironmentVariable($neutral,'false','Process')
    Deny 'unlocked-ambient-bypass' { Get-NativeRendererPanelCompositionSelection -FeatureLock $null -BuildEnvByName @{} }
    [Environment]::SetEnvironmentVariable($neutral,$null,'Process')
    # Compile the real dependency-free owner modules, not copied policy logic.
    # This bounded host gate does not compile/link Android or prove headset effects.
    $src=Join-Path $RepoRoot 'apps/native-renderer-android/native/src'
    $harness=Join-Path $OutputDirectory 'owner-modules.rs'
    $modules=@('native_renderer_properties','native_renderer_property_values','native_renderer_visual_options','native_mesh_ui','native_scene_composition')
    ($modules|ForEach-Object {'#[path = "'+(Join-Path $src ($_+'.rs')).Replace('\','/')+'"] mod '+$_+';'})|Set-Content -LiteralPath $harness
    foreach($mode in @('ordinary','original')) {
        [Environment]::SetEnvironmentVariable($black,$(if($mode -eq 'original'){'solid-black'}else{$null}),'Process')
        [Environment]::SetEnvironmentVariable($neutral,$(if($mode -eq 'original'){'false'}else{$null}),'Process')
        $exe=Join-Path $OutputDirectory ($mode+'.exe');$started=[DateTime]::UtcNow
        & rustc --edition=2021 --test -Awarnings $harness -o $exe *> (Join-Path $OutputDirectory ($mode+'.compile.log'))
        if($LASTEXITCODE -ne 0){throw "Owner module compile failed: $mode"}
        & $exe *> (Join-Path $OutputDirectory ($mode+'.tests.log'))
        if($LASTEXITCODE -ne 0){throw "Owner module tests failed: $mode"}
        @{mode=$mode;started_utc=$started.ToString('o');finished_utc=[DateTime]::UtcNow.ToString('o');outer_seconds=([DateTime]::UtcNow-$started).TotalSeconds}|ConvertTo-Json|Set-Content (Join-Path $OutputDirectory ($mode+'.process.json'))
        $passed.Add('actual-owner-modules-'+$mode)
    }
} finally {
    [Environment]::SetEnvironmentVariable($black,$previousBlack,'Process');[Environment]::SetEnvironmentVariable($neutral,$previousNeutral,'Process')
}
@{passed=@($passed);count=$passed.Count;apk_builds=0;device_operations=0}|ConvertTo-Json|Set-Content (Join-Path $OutputDirectory 'result.json')
Write-Output "Native panel composition host checks passed $($passed.Count) cases."
