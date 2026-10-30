param([Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw 'OutputRoot must be create-new.'}
[void][IO.Directory]::CreateDirectory($OutputRoot)
$module=Join-Path $PSScriptRoot '../lib/SpatialCargoChild.psm1'
Import-Module $module -Force
$python=(Get-Command python -CommandType Application | Select-Object -First 1).Source
$fixture=Join-Path $OutputRoot 'child.py'
[IO.File]::WriteAllText($fixture,@'
import json, os, pathlib, subprocess, sys, time
root=pathlib.Path(sys.argv[1]); mode=sys.argv[2]
(root/'pid').write_text(str(os.getpid()))
(root/'env.json').write_text(json.dumps({'flags':os.getenv('RUSTFLAGS'),'private':os.getenv('RQ_OVERLAP_TEST')}))
if mode in ('tree','holdpipe'):
    p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'])
    (root/'descendant').write_text(str(p.pid))
sys.stdout.write('o'*131072+'stdout-tail'); sys.stdout.flush()
sys.stderr.write('e'*131072+'stderr-tail'); sys.stderr.flush()
(root/'ready').write_text('ready')
if mode=='tree': time.sleep(60)
elif mode=='gate':
    deadline=time.monotonic()+10
    while not (root/'release').exists():
        if time.monotonic()>deadline: sys.exit(91)
        time.sleep(.01)
sys.exit(17 if mode=='fail' else 0)
'@)
$controls=[Collections.Generic.List[string]]::new()
function Check([string]$Name,[bool]$Value){if(-not $Value){throw "Control failed: $Name"};$controls.Add($Name)}
function Ready($Root){$clock=[Diagnostics.Stopwatch]::StartNew();while(-not(Test-Path (Join-Path $Root 'ready'))){if($clock.ElapsedMilliseconds -gt 10000){throw 'Fixture readiness timeout'};Start-Sleep -Milliseconds 20}}
function StartFixture([string]$Name,[string]$Mode){
 $root=Join-Path $OutputRoot $Name;[void][IO.Directory]::CreateDirectory($root)
 $child=Start-SpatialCargoChild -FilePath $python -Arguments @($fixture,$root,$Mode) -WorkingDirectory $root -CaptureDirectory (Join-Path $root 'capture')
 return [pscustomobject]@{Root=$root;Child=$child}
}
$oldFlags=$env:RUSTFLAGS;$oldPrivate=$env:RQ_OVERLAP_TEST
try{
 $env:RUSTFLAGS='native-selected';$env:RQ_OVERLAP_TEST='native-private'
 $f=StartFixture 'overlap' 'gate'
 try{
  $env:RUSTFLAGS='jvm-parent';Remove-Item Env:/RQ_OVERLAP_TEST
  Ready $f.Root
  Check 'native_started_and_draining_before_jvm_finishes' (-not $f.Child.Process.HasExited)
  $observed=Get-Content (Join-Path $f.Root 'env.json') -Raw|ConvertFrom-Json
  Check 'child_environment_isolated_from_parent_changes' ($observed.flags -ceq 'native-selected' -and $observed.private -ceq 'native-private' -and $env:RUSTFLAGS -ceq 'jvm-parent' -and -not(Test-Path Env:/RQ_OVERLAP_TEST))
  [IO.File]::WriteAllText((Join-Path $f.Root 'release'),'jvm-complete')
  $result=Complete-SpatialCargoChild -Child $f.Child
  Check 'join_requires_real_exit_and_both_eof' ($result.exit_code -eq 0 -and $result.stdout_eof -and $result.stderr_eof -and $result.reaped)
  Check 'captures_drain_both_large_streams_and_unterminated_tails' ($result.stdout.Length -eq 131083 -and $result.stderr.Length -eq 131083 -and $result.stdout.EndsWith('stdout-tail') -and $result.stderr.EndsWith('stderr-tail'))
 }finally{if(-not $f.Child.Completed){$null=Complete-SpatialCargoChild -Child $f.Child -Cancel}}
 $f=StartFixture 'native-failure' 'fail'
 $result=Complete-SpatialCargoChild -Child $f.Child
 Check 'nonzero_exit_retains_both_captures_and_reaps' ($result.exit_code -eq 17 -and $result.stdout_eof -and $result.stderr_eof -and $result.reaped -and $result.stderr.EndsWith('stderr-tail'))
 $f=StartFixture 'sibling-failure' 'tree'
 try{
  Ready $f.Root;$pidValue=[int](Get-Content (Join-Path $f.Root 'pid'));$descendant=[int](Get-Content (Join-Path $f.Root 'descendant'))
  $result=Complete-SpatialCargoChild -Child $f.Child -Cancel
  Check 'sibling_failure_cancels_drains_and_reaps_owned_tree' ($result.cancelled -and $result.reaped -and $result.stdout_eof -and $result.stderr_eof -and -not(Get-Process -Id $pidValue,$descendant -ErrorAction SilentlyContinue))
 }finally{if(-not $f.Child.Completed){$null=Complete-SpatialCargoChild -Child $f.Child -Cancel}}
 $f=StartFixture 'inherited-pipe' 'holdpipe'
 $eofError=$null;$descendant=$null
 try{
  Ready $f.Root;$descendant=[int](Get-Content (Join-Path $f.Root 'descendant'))
  try{$null=Complete-SpatialCargoChild -Child $f.Child -DrainTimeoutMilliseconds 100}catch{$eofError=$_}
  Check 'exited_child_with_open_inherited_pipe_never_reports_eof' ($null -ne $eofError -and $eofError.Exception.Data['native_execution'].reaped -and -not $eofError.Exception.Data['native_execution'].stdout_eof -and -not $eofError.Exception.Data['native_execution'].stderr_eof)
 }finally{if($null -ne $descendant){Stop-Process -Id $descendant -Force -ErrorAction SilentlyContinue}}
 # Execute the builder's actual terminal trap for an error between native launch
 # and the ordinary join, so environment-restoration failures cannot orphan it.
 $builderAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot '../Build-SpatialCameraPanelAndroid.ps1'),[ref]$null,[ref]$null)
 $trapAst=$builderAst.Find({param($n)$n -is [Management.Automation.Language.TrapStatementAst]},$true)
 $trapBody=[scriptblock]::Create(($trapAst.Body.Statements.Extent.Text -join "`n"))
 $f=StartFixture 'pre-join-failure' 'tree';$nativeCargoChild=$f.Child;$apkInspectionCopy=$null;$buildLaneMutexOwned=$false;$buildLaneMutex=$null
 try{
  Ready $f.Root;$nativePid=[int](Get-Content (Join-Path $f.Root 'pid'));$descendant=[int](Get-Content (Join-Path $f.Root 'descendant'))
  $original=$null
  try{throw 'fixture pre-join failure'}catch{try{& $trapBody}catch{$original=$_}}
  Check 'terminal_trap_retains_original_error_and_reaps_before_lane_release' ($original.Exception.Message -ceq 'fixture pre-join failure' -and $original.Exception.Data['native_execution'].reaped -and $nativeCargoChild.Completed -and -not(Get-Process -Id $nativePid,$descendant -ErrorAction SilentlyContinue))
 }finally{if(-not $f.Child.Completed){$null=Complete-SpatialCargoChild -Child $f.Child -Cancel}}
}finally{
 [Environment]::SetEnvironmentVariable('RUSTFLAGS',$oldFlags,'Process');[Environment]::SetEnvironmentVariable('RQ_OVERLAP_TEST',$oldPrivate,'Process')
}
[ordered]@{passed=$true;execution_mode='host-process-fixture';controls=$controls.ToArray();scope='owned native child only; no compiler, APK, Gradle cache or device';module_sha256=(Get-FileHash $module).Hash.ToLowerInvariant()}|ConvertTo-Json -Depth 6|Set-Content (Join-Path $OutputRoot 'RESULT.json')
Get-Content (Join-Path $OutputRoot 'RESULT.json') -Raw
