[CmdletBinding()]
param([string]$RepoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=(Resolve-Path -LiteralPath $RepoRoot).Path
$root=Join-Path ([IO.Path]::GetTempPath()) ('rq-debug-selection-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($root)
$spec=Get-Content (Join-Path $repo 'fixtures/native-app-builds/native-breath-four-way-conformance.app.json') -Raw|ConvertFrom-Json -Depth 100
$resolver=Join-Path $repo 'tools/Resolve-NativeAppBuild.ps1'
$runtime=Join-Path $PSHOME 'pwsh.exe'
function Resolve-Selected([string]$Name,$App,[bool]$Enabled,[bool]$Expected) {
 $path=Join-Path $root ($Name+'.app.json');$App|ConvertTo-Json -Depth 100|Set-Content -LiteralPath $path -Encoding utf8
 $out=Join-Path $root $Name
 $resolverArguments=@('-NoProfile','-File',$resolver,'-DryRun','-AppSpec',$path,'-FeatureDir',(Join-Path $repo 'fixtures/native-app-features'),'-OutputRoot',$out,'-ResultJsonPath',(Join-Path $out 'result.json'))
 if($Enabled){$resolverArguments+='-EnableExperimentSessionDebugOperator'}
 & $runtime @resolverArguments *> (Join-Path $root ($Name+'.log'))
 if(($LASTEXITCODE-eq0)-ne$Expected){throw "Unexpected selection result: $Name"}
 if(-not$Expected){return $null}
 $result=Get-Content (Join-Path $out 'result.json') -Raw|ConvertFrom-Json -Depth 100
 return Get-Content $result.feature_lock_path -Raw|ConvertFrom-Json -Depth 100
}
$off=Resolve-Selected 'off' $spec $false $true
if($null-ne$off.android_manifest.PSObject.Properties['providers'] -or @($off.debug_experiment_operator_sources).Count-ne0){throw 'Default debug source/manifest shape widened'}
$spec.requested_features+=@('operator.breath_composition_cli')
$spec.declared_manifest.receivers+=@('BreathCompositionCommandReceiver')
$spec.declared_manifest|Add-Member -NotePropertyName providers -NotePropertyValue @('ExperimentSessionDebugControlProvider')
$on=Resolve-Selected 'on' $spec $true $true
if(@($on.debug_experiment_operator_sources).Count-ne2 -or @($on.android_manifest.providers).Count-ne1){throw 'Selected debug closure missing'}
$spec.declared_manifest.providers=@('OtherProvider')
Resolve-Selected 'wrong-provider' $spec $true $false|Out-Null
$spec.declared_manifest.providers=@('ExperimentSessionDebugControlProvider')
Resolve-Selected 'provider-without-optin' $spec $false $false|Out-Null
'PASS production resolver default eightfield/off closure, explicit two-source provider, wrong-provider/no-optin denials'
