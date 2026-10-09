[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputRoot,
      [string]$BrowserRepo,[string]$LegacyParser)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw 'Create-new output required'}
$null=New-Item -ItemType Directory $OutputRoot
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$package='io/github/mesmerprism/rustyquest/native_renderer'
$panel=Join-Path $repo "apps/native-renderer-android/panel-modules/breath-composition/src/main/java/$package"
$tests=Join-Path $repo "apps/native-renderer-android/tests/java/$package"
function RunHost([string]$Name,[string]$Exe,[string[]]$Arguments){
 & $Exe @Arguments > (Join-Path $OutputRoot "$Name.stdout") 2> (Join-Path $OutputRoot "$Name.stderr")
 $code=$LASTEXITCODE
 [IO.File]::WriteAllText((Join-Path $OutputRoot "$Name.exit"),[string]$code)
 if($code-ne0){throw "Host control failed: $Name exit $code"}
 Get-Content (Join-Path $OutputRoot "$Name.stdout")
}
$sources=@('ExperimentSessionPanelState','ExperimentSessionPanelCoordinator','ExperimentSessionPanelViewPolicy','ExperimentSessionStatusObservation')|ForEach-Object{Join-Path $panel "$_.java"}
$sources+=@('ExperimentSessionPanelCoordinatorTest','ExperimentSessionStatusObservationTest')|ForEach-Object{Join-Path $tests "$_.java"}
RunHost 'compile' 'javac' (@('--release','8','-d',$OutputRoot)+$sources)
RunHost 'coordinator' 'java' @('-cp',$OutputRoot,'io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionPanelCoordinatorTest')
$fixtures=Join-Path $OutputRoot 'producer.jsonl'
RunHost 'producer' 'java' @('-cp',$OutputRoot,'io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionStatusObservationTest',$fixtures)
$feature=Get-Content (Join-Path $repo 'fixtures/native-app-features/ui/breath-composition-panel/ui.breath_composition_control_panel.feature.json') -Raw|ConvertFrom-Json
$source='apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/ExperimentSessionStatusObservation.java'
if(@($feature.panel_composition.modules[0].source_files|Where-Object{$_-ceq$source}).Count-ne1){throw 'Producer missing from exact feature source closure'}
if($BrowserRepo){
 if(-not$LegacyParser){throw 'Exact prior parser required for compatibility proof'}
 $names=@('QUEST_OBSERVATION_FIXTURES','QUEST_OBSERVATION_LEGACY_PARSER');$old=@{}
 foreach($n in $names){$old[$n]=[Environment]::GetEnvironmentVariable($n,'Process')}
 try{
  $env:QUEST_OBSERVATION_FIXTURES=$fixtures;$env:QUEST_OBSERVATION_LEGACY_PARSER=$LegacyParser
  RunHost 'browser' 'node' @('--test',(Join-Path $BrowserRepo 'viscereality-control/status-relay-observation.test.cjs'))
 }finally{foreach($n in $names){[Environment]::SetEnvironmentVariable($n,$old[$n],'Process')}}
}
@{passed=$true;scope='Actual coordinator and producer; native receipts modeled; no transport/auth/device/physical proof';fixtures_sha256=(Get-FileHash $fixtures).Hash.ToLowerInvariant()}|ConvertTo-Json|Set-Content (Join-Path $OutputRoot 'RESULT.json')
