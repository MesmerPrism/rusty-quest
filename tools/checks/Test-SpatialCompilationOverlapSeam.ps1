param([Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw 'OutputRoot must be create-new.'}
[void][IO.Directory]::CreateDirectory($OutputRoot)
Import-Module (Join-Path $PSScriptRoot '../lib/SpatialCargoChild.psm1') -Force
$python=(Get-Command python -CommandType Application|Select-Object -First 1).Source
$builder=Join-Path $PSScriptRoot '../Build-SpatialCameraPanelAndroid.ps1'
$text=[IO.File]::ReadAllText($builder)
$start=$text.IndexOf('$androidJvmCompilation=[ordered]@')
$end=$text.IndexOf('$nativeStopwatch.Stop()',$start)+'$nativeStopwatch.Stop()'.Length
if($start -lt 0 -or $end -lt $start){throw 'Actual compile/join seam missing.'}
$seam=$text.Substring($start,$end-$start)
$native=@'
import json, os, pathlib, sys, time
r=pathlib.Path(sys.argv[sys.argv.index('--target-dir')+1]); mode=os.environ['RQ_SEAM_MODE']
(r/'native.pid').write_text(str(os.getpid()))
(r/'native.env').write_text(os.getenv('RUSTFLAGS',''))
(r/'native.ready').write_text('ready')
print('native-output',flush=True); print('native-error-output',file=sys.stderr,flush=True)
if mode=='jvm-failure': time.sleep(60)
if mode=='native-failure': sys.exit(23)
if mode=='both-fail': time.sleep(.1); sys.exit(23)
deadline=time.monotonic()+8
while not (r/'jvm.ready').exists():
    if time.monotonic()>deadline: sys.exit(93)
    time.sleep(.01)
if os.environ['RQ_SEAM_OVERLAP']=='false' and not (r/'jvm.done').exists(): sys.exit(94)
(r/'native.done').write_text('done')
'@
$jvm=@'
import os, pathlib, sys, time
r=pathlib.Path(sys.argv[1]); mode=os.environ['RQ_SEAM_MODE']
(r/'jvm.env').write_text(os.getenv('RUSTFLAGS',''))
(r/'jvm.ready').write_text('ready')
if os.environ['RQ_SEAM_OVERLAP']=='true':
    deadline=time.monotonic()+8
    while not (r/'native.ready').exists():
        if time.monotonic()>deadline: sys.exit(95)
        time.sleep(.01)
if mode=='both-fail': time.sleep(.2)
if mode in ('jvm-failure','both-fail'): sys.exit(29)
time.sleep(.05)
(r/'jvm.done').write_text('done')
'@
function Resolve-QuestSpatialApplication {param($Name,$Path,[switch]$Optional);if($Name -ceq 'cargo'){[pscustomobject]@{Source=$python}}}
function Invoke-SpatialGradlePhase {
 param($Phase,$ComponentEvidenceDirectory)
 & $python (Join-Path $OutDir 'jvm.py') $nativeReceiptTargetDir
 if($LASTEXITCODE -ne 0){throw [InvalidOperationException]::new("fixture JVM failure $LASTEXITCODE")}
 return [pscustomobject]@{compilation=[ordered]@{status='fixture-pass'}}
}
$controls=[Collections.Generic.List[string]]::new();$observations=[Collections.Generic.List[object]]::new()
function Check($Name,[bool]$Value){if(-not $Value){throw "Control failed: $Name"};$controls.Add($Name)}
$builderAst=[Management.Automation.Language.Parser]::ParseFile($builder,[ref]$null,[ref]$null)
$selectionGuard=$builderAst.Find({param($n)$n -is [Management.Automation.Language.IfStatementAst] -and $n.Clauses[0].Item1.Extent.Text -ceq '$OverlapJvmNativeCompilation -and [string]::IsNullOrWhiteSpace($AndroidComponentContractPath)'},$true)
$OverlapJvmNativeCompilation=$true;$AndroidComponentContractPath='';$denied=$false
try{Invoke-Expression $selectionGuard.Extent.Text}catch{$denied=$_.Exception.Message -like '*requires a selected Android component contract*'}
Check 'overlap_requires_actual_selected_jvm_route' $denied
$OverlapJvmNativeCompilation=$false;Invoke-Expression $selectionGuard.Extent.Text
Check 'serialized_unverified_compatibility_route_preserved' $true
$prior=@{};foreach($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()){$prior[[string]$entry.Key]=[string]$entry.Value}
try{
 foreach($case in @(
  @{Name='serialized';Overlap=$false;Mode='success'},
  @{Name='overlap';Overlap=$true;Mode='success'},
  @{Name='native-failure';Overlap=$true;Mode='native-failure'},
  @{Name='jvm-failure';Overlap=$true;Mode='jvm-failure'},
  @{Name='both-fail';Overlap=$true;Mode='both-fail'})){
  $OutDir=Join-Path $OutputRoot $case.Name;[void][IO.Directory]::CreateDirectory($OutDir)
  [IO.File]::WriteAllText((Join-Path $OutDir 'build'),$native);[IO.File]::WriteAllText((Join-Path $OutDir 'jvm.py'),$jvm)
  $nativeReceiptCargoManifest=Join-Path $OutDir 'Cargo.toml';[IO.File]::WriteAllText($nativeReceiptCargoManifest,'fixture')
  $nativeReceiptLinker=$nativeReceiptCargoManifest;$nativeReceiptTargetDir=$OutDir;$nativeReceiptJniAbiDir=Join-Path $OutDir 'jni'
  $OverlapJvmNativeCompilation=$case.Overlap;$env:RQ_SEAM_MODE=$case.Mode;$env:RQ_SEAM_OVERLAP=$case.Overlap.ToString().ToLowerInvariant();$env:RUSTFLAGS='ambient-parent'
  $androidComponentSelection=@{};$androidComponentEvidenceDir=Join-Path $OutDir 'components'
  $CargoPath=$python;$RustupPath='';$RequireInstalledRustTarget=$false
  $AndroidHome='fixture-sdk';$NdkHome='fixture-ndk';$RustStdLinkage='Static'
  $lockedFinalPresentationEnabled=$false;$resolvedDistortionSpeedScale=1.0;$resolvedProjectionSurfaceUniformAbiVersion=1
  $EmbeddedDuplexLifetimePolicy='finite';$EmbeddedDuplexMediaPolicy='finite';$EmbeddedDuplexFreshSetupPolicy='none';$EnvironmentDepthOwner='disabled';$freshBuildSelection=$null
  $resolvedRecordedHandCaptureDir='';$resolvedPrivateLayerProfilePath='';$privateLayerShaderInputsConfigured=$false;$privateSurfaceParticleInputsConfigured=$false
  $sourceBankBuildInputs=[pscustomobject]@{enabled=$false;final_abi_version=1;primary_input_shader=$null;own_capture_hold_limits=[pscustomobject]@{slots=0;bytes=0;gpu_uses=0}}
  $errorValue=$null
  Push-Location $OutDir
  try{Invoke-Expression $seam}catch{$errorValue=$_}finally{Pop-Location}
  $observations.Add([ordered]@{case=$case.Name;execution_mode=$(if($case.Overlap){'jvm-native-overlap'}else{'serialized'});error=$(if($errorValue){$errorValue.Exception.Message}else{$null})})
  Check ($case.Name+'_parent_environment_restored') ($env:RUSTFLAGS -ceq 'ambient-parent')
  Check ($case.Name+'_jvm_environment_excludes_native_projection') ((Get-Content (Join-Path $OutDir 'jvm.env') -Raw) -ceq 'ambient-parent')
  if($case.Mode -ceq 'success'){
   Check ($case.Name+'_real_results_joined') ($null -eq $errorValue -and $androidJvmCompilation.status -ceq 'fixture-pass' -and (Test-Path (Join-Path $OutDir 'native.done')) -and (Test-Path (Join-Path $OutDir 'jvm.done')))
   Check ($case.Name+'_cargo_output_retained') (@($cargoOutput|Where-Object {$_ -ceq 'native-output'}).Count -eq 1 -and @($cargoOutput|Where-Object {$_ -ceq 'native-error-output'}).Count -eq 1)
  }else{
   Check ($case.Name+'_refuses_join') ($null -ne $errorValue)
   $nativePid=[int](Get-Content (Join-Path $OutDir 'native.pid'))
   Check ($case.Name+'_native_reaped') (-not(Get-Process -Id $nativePid -ErrorAction SilentlyContinue))
   if($case.Mode -cin @('jvm-failure','both-fail')){Check ($case.Name+'_original_jvm_error_retained') ($errorValue.Exception.Message -like '*fixture JVM failure 29*')}
   if($case.Mode -ceq 'both-fail'){Check 'both_original_failures_retained' ($errorValue.Exception.Data['native_execution'].exit_code -eq 23)}
  }
 }
}finally{
 foreach($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()){if(-not $prior.ContainsKey([string]$entry.Key)){[Environment]::SetEnvironmentVariable([string]$entry.Key,$null,'Process')}}
 foreach($key in $prior.Keys){[Environment]::SetEnvironmentVariable($key,$prior[$key],'Process')}
}
[ordered]@{passed=$true;execution_mode='actual-builder-seam-host-fixture';controls=$controls.ToArray();observations=$observations.ToArray();scope='actual builder compile/join seam with real child processes; no compiler/APK/device';builder_sha256=(Get-FileHash $builder).Hash.ToLowerInvariant()}|ConvertTo-Json -Depth 8|Set-Content (Join-Path $OutputRoot 'RESULT.json')
Get-Content (Join-Path $OutputRoot 'RESULT.json') -Raw
