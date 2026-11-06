[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(-not [IO.Path]::IsPathRooted($OutputDirectory) -or (Test-Path -LiteralPath $OutputDirectory)){throw 'Fresh absolute test output required.'}
$null=New-Item -ItemType Directory -Path $OutputDirectory
function Read-Json([string]$path){Get-Content -Raw -LiteralPath (Join-Path $repo $path)|ConvertFrom-Json -Depth 100}
function Check([bool]$condition,[string]$detail){if(-not $condition){throw $detail}}
$oldRenderer=Read-Json 'fixtures/native-app-features/particles/private/renderer/renderer.private_particles.feature.json'
$newRenderer=Read-Json 'fixtures/native-app-features/particles/private/original-composable-renderer/renderer.private_particles.original_composable.feature.json'
$oldBlack=Read-Json 'fixtures/native-app-features/background/solid-black/renderer.background.solid_black.feature.json'
$newBlack=Read-Json 'fixtures/native-app-features/background/solid-black/composable/renderer.background.solid_black.composable.feature.json'
foreach($pair in @(@($oldRenderer,$newRenderer),@($oldBlack,$newBlack))){
    foreach($p in $pair[0].runtime_profile.set.PSObject.Properties){
        $expected=if($p.Name -ceq 'debug.rustyquest.native_renderer.render.mode'){'native-worldspace-integration'}else{$p.Value}
        Check ($pair[1].runtime_profile.set.($p.Name) -ceq $expected) "Original runtime default changed: $($p.Name)"
    }
    Check (($pair[0].runtime_profile.clear_families -join '|') -ceq ($pair[1].runtime_profile.clear_families -join '|')) 'Original clear-family policy changed.'
    $markers=@($pair[0].markers.required|ForEach-Object{if($_ -ceq 'renderMode=solid-black-private-particles'){'renderMode=native-worldspace-integration'}elseif($_ -ceq 'customStereoProjectionEnabled=false'){'customStereoProjectionEnabled=true'}else{$_}})
    Check (($markers -join '|') -ceq ($pair[1].markers.required -join '|')) 'Original non-route markers changed.'
    Check (($pair[0].markers.forbidden -join '|') -ceq ($pair[1].markers.forbidden -join '|')) 'Original forbidden markers changed.'
}
Check ($newBlack.build_inputs.env.Count -eq 1 -and $newBlack.build_inputs.env[0].name -ceq 'RUSTY_QUEST_NATIVE_RENDERER_BACKGROUND_MODE' -and $newBlack.build_inputs.env[0].value -ceq 'solid-black') 'Explicit selected black build contract missing.'
$neutral=@($newRenderer.build_inputs.env|Where-Object name -CEQ 'RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS')
Check ($neutral.Count -eq 1 -and $neutral[0].value -ceq 'false') 'Original owner must suppress neutral demo controls.'
$oldLsl=Read-Json 'fixtures/native-app-features/lsl/lsl.panel_controlled_io.feature.json'
$newLsl=Read-Json 'fixtures/native-app-features/lsl/composable/lsl.panel_controlled_io.composable.feature.json'
foreach($field in @('runtime_profile','build_inputs','android_manifest','markers','provides')){
    Check (($oldLsl.$field|ConvertTo-Json -Depth 100 -Compress) -ceq ($newLsl.$field|ConvertTo-Json -Depth 100 -Compress)) "Original LSL $field changed."
}
$fixture=Read-Json 'fixtures/native-app-builds/native-breath-original-mesh-driver-conformance.app.json'
$cases=@(
    @{name='original-mesh';spec=$fixture;accept=$true},
    @{name='old-route';spec=(Read-Json 'fixtures/native-app-builds/native-breath-particle-driver-conformance.app.json');accept=$true}
)
foreach($mode in @('controller-only-hybrid','old-renderer-hybrid','unlisted-source','injected-binding')){
    $bad=$fixture|ConvertTo-Json -Depth 100|ConvertFrom-Json -Depth 100
    switch($mode){
        'controller-only-hybrid' {$bad.requested_features+='breath.composition.controller_only'}
        'old-renderer-hybrid' {$bad.requested_features+='renderer.private_particles'}
        'unlisted-source' {$bad.runtime_profile.set.'debug.rustyquest.native_renderer.breath_composition.source'='unlisted'}
        'injected-binding' {$bad.runtime_profile.set|Add-Member -NotePropertyName 'debug.rustyquest.native_renderer.private_particles.breath_composition_driver.activation.binding_sha256' -NotePropertyValue ('a'*64)}
    }
    $cases+=@{name=$mode;spec=$bad;accept=$false}
}
$results=@()
foreach($case in $cases){
    $spec=Join-Path $OutputDirectory ($case.name+'.app.json');$case.spec|ConvertTo-Json -Depth 100|Set-Content -LiteralPath $spec -Encoding utf8
    $result=Join-Path (Join-Path $OutputDirectory $case.name) 'result.json'
    & pwsh -NoProfile -File (Join-Path $repo 'tools/Resolve-NativeAppBuild.ps1') -AppSpec $spec -FeatureDir (Join-Path $repo 'fixtures/native-app-features') -OutputRoot (Join-Path $OutputDirectory $case.name) -ResultJsonPath $result -DryRun *> (Join-Path $OutputDirectory ($case.name+'.log'))
    $code=$LASTEXITCODE
    Check (($code -eq 0) -eq $case.accept) "Actual resolver case failed: $($case.name) exit=$code"
    if($case.name -ceq 'original-mesh'){
        $resolution=Get-Content -Raw -LiteralPath $result|ConvertFrom-Json
        $lock=Get-Content -Raw -LiteralPath $resolution.feature_lock_path|ConvertFrom-Json
        Check ($lock.selected_feature_ids -ccontains 'breath.composition.closed_world' -and $lock.selected_feature_ids -ccontains 'input.breath.polar_acc_assessment' -and $lock.selected_feature_ids -ccontains 'input.simultaneous_hands_and_controllers') 'Original input closure lost.'
        Check ($lock.selected_feature_ids -cnotcontains 'breath.composition.controller_only') 'Standalone input substituted.'
        Check ($lock.breath_composition_driver_activation.sha256 -cmatch '^[a-f0-9]{64}$' -and $lock.breath_composition_driver_activation.sha256 -ceq $lock.breath_composition_activation.sha256) 'Actual driver activation binding missing/mismatched.'
        $envRow=@($lock.build_inputs.env|Where-Object name -CEQ 'RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_DRIVER_EXPECTED_BINDING_SHA256')
        Check ($envRow.Count -eq 1 -and $envRow[0].value -ceq $lock.breath_composition_activation.sha256) 'Actual packaged driver binding missing.'
        foreach($selection in @(@('RUSTY_QUEST_NATIVE_RENDERER_BACKGROUND_MODE','solid-black'),@('RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS','false'))){
            $row=@($lock.build_inputs.env|Where-Object name -CEQ $selection[0])
            Check ($row.Count -eq 1 -and $row[0].value -ceq $selection[1]) "Actual presentation selector missing: $($selection[0])"
        }
    }
    if($case.name -ceq 'old-route'){
        $resolution=Get-Content -Raw -LiteralPath $result|ConvertFrom-Json
        $lock=Get-Content -Raw -LiteralPath $resolution.feature_lock_path|ConvertFrom-Json
        Check ($lock.runtime_profile.render_mode -ceq 'solid-black-private-particles') 'Old render route changed.'
        Check (@($lock.build_inputs.env|Where-Object {$_.name -cin @('RUSTY_QUEST_NATIVE_RENDERER_BACKGROUND_MODE','RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS')}).Count -eq 0) 'Old route acquired presentation selectors.'
    }
    if(-not $case.accept){
        $log=Get-Content -Raw -LiteralPath (Join-Path $OutputDirectory ($case.name+'.log'))
        $reason=switch($case.name){
            'controller-only-hybrid' {'incompatible'}
            'old-renderer-hybrid' {'incompatible'}
            'unlisted-source' {'not in manifest allowed_values'}
            'injected-binding' {'resolver-derived'}
        }
        Check ($log -match $reason) "Negative case failed for an unrelated reason: $($case.name)"
    }
    $results+=@{case=$case.name;accepted=($code -eq 0);expected=$case.accept;exit_code=$code}
    Write-Output "$($case.name) PASS (actual resolver)"
}
@{passed=$true;cases=$results;scope='Actual source resolver/default/negative fixtures only; no APK/device/runtime grant.'}|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $OutputDirectory 'RESULT.json') -Encoding utf8
