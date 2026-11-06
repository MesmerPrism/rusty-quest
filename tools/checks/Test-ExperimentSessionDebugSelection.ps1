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

# Exercise the actual builder consumer, including PowerShell's one-item output
# shape. Resolver-only controls cannot catch scalar collapse in this block.
$builder=Join-Path $repo 'tools/Build-NativeRendererAndroid.ps1'
$tokens=$null;$parseErrors=$null
$builderAst=[Management.Automation.Language.Parser]::ParseFile($builder,[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count-ne0){throw 'Builder extraction parse failed'}
foreach($name in @('Resolve-RepoPath','Get-FileSha256','Assert-HashMatches')) {
 $functions=@($builderAst.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true))
 if($functions.Count-ne1){throw "Ambiguous production helper: $name"}
 . ([scriptblock]::Create($functions[0].Extent.Text))
}
$builderText=[IO.File]::ReadAllText($builder)
$begin=$builderText.IndexOf('$debugSources = @()', [StringComparison]::Ordinal)
$end=$builderText.IndexOf('$sourceFiles += $debugSources', $begin, [StringComparison]::Ordinal)
if($begin-lt0-or$end-le$begin){throw 'Production debug source block extraction failed'}
$consumer=[scriptblock]::Create($builderText.Substring($begin,$end-$begin))
function Test-BuilderConsumer([string]$Name,$Lock,[bool]$ShouldAccept,[int]$ExpectedSources=0) {
 $appBuildLockObject=$Lock;$repoRoot=$repo;$script:BuildUsesLock=$true
 $denied=$false;$debugSources=@();$reason=''
 try { . $consumer } catch { $denied=$true;$reason=$_.Exception.Message }
 if($denied-eq$ShouldAccept){throw "Unexpected actual builder consumer result: $Name; $reason"}
 if($ShouldAccept-and@($debugSources).Count-ne$ExpectedSources){throw "Wrong actual compiled source count: $Name"}
}
function Copy-ConsumerLock($Lock){return ($Lock|ConvertTo-Json -Depth 100|ConvertFrom-Json -Depth 100)}
Test-BuilderConsumer 'default-off' $off $true 0
Test-BuilderConsumer 'sole-provider-on' $on $true 2
$legacy=Copy-ConsumerLock $off
$legacy.PSObject.Properties.Remove('debug_experiment_operator_sources')
Test-BuilderConsumer 'legacy-no-provider-no-property' $legacy $true 0
foreach($case in @('missing-property','empty-sources','two-providers','wrong-provider','wrong-source-path','wrong-source-hash','missing-feature')) {
 $bad=Copy-ConsumerLock $on
 switch($case) {
  'missing-property' {$bad.PSObject.Properties.Remove('debug_experiment_operator_sources')}
  'empty-sources' {$bad.debug_experiment_operator_sources=@()}
  'two-providers' {$bad.android_manifest.providers=@('ExperimentSessionDebugControlProvider','OtherProvider')}
  'wrong-provider' {$bad.android_manifest.providers=@('OtherProvider')}
  'wrong-source-path' {$bad.debug_experiment_operator_sources[0].path='apps/native-renderer-android/src/debug/java/io/github/mesmerprism/rustyquest/native_renderer/OtherProvider.java'}
  'wrong-source-hash' {$bad.debug_experiment_operator_sources[0].sha256='0'*64}
  'missing-feature' {$bad.selected_feature_ids=@($bad.selected_feature_ids|Where-Object {$_-cne'operator.breath_composition_cli'})}
 }
 Test-BuilderConsumer $case $bad $false
}
'PASS actual builder debug source block: sole provider array, legacy/off and seven closed-shape/hash denials'
