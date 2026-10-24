param([Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$appRoot=Join-Path $repoRoot 'apps/spatial-camera-panel-android'
$builder=Join-Path $repoRoot 'tools/Build-SpatialCameraPanelAndroid.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($builder,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Builder PowerShell parse failed.'}
foreach($name in @('Get-FileSha256','Get-StringSha256','Get-DirectorySha256','Get-PathSetSha256')){
    $definitions=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$false)|Where-Object Name -CEQ $name)
    if($definitions.Count -ne 1){throw "Unique actual hash owner missing: $name"}
    . ([scriptblock]::Create($definitions[0].Extent.Text))
}
$assignments=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left -is [Management.Automation.Language.VariableExpressionAst] -and $node.Left.VariablePath.UserPath -ceq 'nativeIdentityInputs'},$false))
if($assignments.Count -ne 1){throw 'Unique actual native identity declaration missing.'}
. ([scriptblock]::Create($assignments[0].Extent.Text))
$required=[ordered]@{
    development_feedback_native='crates/rusty-quest-development-feedback-native'
    development_feedback_native_runtime='examples/development-feedback-native/src'
}
foreach($name in $required.Keys){
    if(-not $nativeIdentityInputs.Contains($name) -or [IO.Path]::GetFullPath($nativeIdentityInputs[$name]) -cne [IO.Path]::GetFullPath((Join-Path $repoRoot $required[$name]))){throw "Compiled provider root missing from actual native identity: $name"}
}
if(Test-Path -LiteralPath $OutputRoot){throw 'Output must be create-new.'}
New-Item -ItemType Directory -Path $OutputRoot|Out-Null
$actualKey=Get-PathSetSha256 -Paths $nativeIdentityInputs
$projected=[ordered]@{};foreach($name in $nativeIdentityInputs.Keys){$projected[$name]=$nativeIdentityInputs[$name]}
$files=@();$controls=@()
foreach($name in $required.Keys){
    $destination=Join-Path $OutputRoot $name
    New-Item -ItemType Directory -Path $destination|Out-Null
    Copy-Item -Path (Join-Path $nativeIdentityInputs[$name] '*') -Destination $destination -Recurse
    $projected[$name]=$destination
    $files+=@(Get-ChildItem -LiteralPath $nativeIdentityInputs[$name] -Recurse -File|ForEach-Object {[ordered]@{path=$_.FullName;bytes=$_.Length;sha256=Get-FileSha256 -Path $_.FullName}})
}
if($files.Count -ne 6){throw 'Imported provider closure differs from six reviewed source files.'}
if((Get-PathSetSha256 -Paths $projected) -cne $actualKey){throw 'Copied exact provider bytes did not preserve actual key.'}
foreach($name in $required.Keys){
    foreach($file in @(Get-ChildItem -LiteralPath $projected[$name] -Recurse -File)){
        $before=[IO.File]::ReadAllBytes($file.FullName)
        try{
            [IO.File]::WriteAllBytes($file.FullName,($before+[byte[]]@(10)))
            if((Get-PathSetSha256 -Paths $projected) -ceq $actualKey){throw 'Actual key ignored a changed compiled provider file.'}
            $controls+=[ordered]@{owner=$name;file=$file.Name;raw_byte_change_invalidates_actual_key=$true}
        }finally{[IO.File]::WriteAllBytes($file.FullName,$before)}
    }
}
if((Get-PathSetSha256 -Paths $projected) -cne $actualKey){throw 'Private copied source restoration changed key.'}
$result=[ordered]@{schema='rusty.quest.strength_phase_native_identity_control.v1';passed=$true;scope='Actual builder identity declaration and unchanged hash owners; no build/cache/device effects';builder_sha256=Get-FileSha256 -Path $builder;actual_native_key_source_sha256=$actualKey;roots=$required;provider_files=$files;controls=$controls;builder_parse_errors=$errors.Count}
[IO.File]::WriteAllText((Join-Path $OutputRoot 'RESULT.json'),($result|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
Write-Output ('PASS actual native identity: six provider source mutations each invalidate key '+$actualKey)
