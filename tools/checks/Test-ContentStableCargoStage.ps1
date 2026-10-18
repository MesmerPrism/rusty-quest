[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if(Test-Path -LiteralPath $OutputRoot){throw 'OutputRoot must be create-new.'}
New-Item -ItemType Directory -Path $OutputRoot|Out-Null
Import-Module (Join-Path $PSScriptRoot '../lib/ContentStableCargoStage.psm1') -Force
$original=Join-Path $OutputRoot 'original';$cache=Join-Path $OutputRoot 'cache'
$app=Join-Path $original 'app';$dep=Join-Path $original 'dependency'
New-Item -ItemType Directory -Path "$app/src","$dep/src",$cache|Out-Null
$controls=[Collections.Generic.List[string]]::new();$observations=[Collections.Generic.List[object]]::new()
function Check([string]$Name,[bool]$Value){if(-not$Value){throw "Control failed: $Name"};$controls.Add($Name)}
function Git([string]$Root,[string[]]$Arguments){$value=(& git.exe -C $Root @Arguments 2>&1)-join "`n";if($LASTEXITCODE){throw "Fixture Git failed: $value"};$value}
function Text([string]$Path,[string]$Value){[IO.File]::WriteAllText($Path,$Value,[Text.UTF8Encoding]::new($false))}
function Commit([string]$Root){Git $Root @('add','--all')|Out-Null;Git $Root @('-c','user.name=Local Fixture','-c','user.email=fixture@example.invalid','commit','--quiet','-m','Local source')|Out-Null}
function Sync {
 foreach($pair in @(@($app,'app'),@($dep,'dependency'))){
  Sync-ContentStableCargoStage $pair[0] (Join-Path $forest $pair[1]) (Git $pair[0] @('rev-parse','HEAD')) (Git $pair[0] @('rev-parse','HEAD^{tree}')) $lock
  Assert-CleanNativeStage (Join-Path $forest $pair[1])
 }
}
function Build([string]$Name,[switch]$Feature){
 $args=@('build','--offline','--locked','--manifest-path',(Join-Path $forest 'app/Cargo.toml'),'--target-dir',(Join-Path $cache 'target'),'--message-format=json')
 if($Feature){$args+=@('--features','extra')}
 $stderr=Join-Path $OutputRoot ($Name+'.stderr.log')
 $lines=@(& cargo @args 2>$stderr);$exit=$LASTEXITCODE
 $lines|Set-Content -LiteralPath (Join-Path $OutputRoot ($Name+'.stdout.jsonl')) -Encoding utf8
 if($exit){throw "Actual Cargo fixture failed: $Name"}
 $artifacts=@($lines|ForEach-Object {$_|ConvertFrom-Json}|Where-Object reason -CEQ 'compiler-artifact')
 $compiled=@($artifacts|Where-Object {-not$_.fresh}).Count
 $row=[ordered]@{name=$Name;actual_cargo_invoked=$true;compiler_artifacts=$artifacts.Count;compiled_units=$compiled}
 $observations.Add($row)
 return $compiled
}
$lock=$null;$previousFlags=$env:RUSTFLAGS
try{
 foreach($root in @($app,$dep)){Git $root @('-c','core.autocrlf=false','init','--quiet')|Out-Null;Git $root @('config','core.autocrlf','false')|Out-Null}
 Text "$dep/Cargo.toml" "[package]`nname='warm-stage-dependency'`nversion='0.1.0'`nedition='2021'`n"
 Text "$dep/src/lib.rs" 'pub fn value()->u32 {1}'
 Text "$app/Cargo.toml" "[package]`nname='warm-stage-app'`nversion='0.1.0'`nedition='2021'`n[dependencies]`nwarm-stage-dependency={path='../dependency'}`n[features]`nextra=[]`n"
 Text "$app/src/lib.rs" 'pub fn value()->u32 {warm_stage_dependency::value()}'
 Text "$app/Panel.java" 'final class Panel { int value=1; }'
 Push-Location $app
 try{& cargo generate-lockfile --offline 2>&1|Out-Null;if($LASTEXITCODE){throw 'Fixture lock failed'}}finally{Pop-Location}
 Commit $dep;Commit $app
 $lock=Open-NativeForestLock $cache;$forest=Join-Path $cache 'n'
 New-Item -ItemType Directory -Path $forest|Out-Null
 Assert-NativeForestLock $forest $lock
 $rejected=$false;try{$second=Open-NativeForestLock $cache;$second.Dispose()}catch [IO.IOException]{$rejected=$true}
 Check 'concurrent_forest_owner_rejected' $rejected
 Sync
 Check 'fresh_producer_compiles' ((Build 'initial') -gt 0)
 $nativeFile=Join-Path $forest 'app/src/lib.rs';$depFile=Join-Path $forest 'dependency/src/lib.rs'
 $mtime=(Get-Item $nativeFile).LastWriteTimeUtc.Ticks;$depMtime=(Get-Item $depFile).LastWriteTimeUtc.Ticks
 $hash=(Get-FileHash $nativeFile).Hash
 Text "$app/Panel.java" 'final class Panel { int value=2; }';Commit $app;Sync
 Check 'java_only_preserves_native_bytes_and_mtimes' ((Get-FileHash $nativeFile).Hash-ceq$hash-and(Get-Item $nativeFile).LastWriteTimeUtc.Ticks-eq$mtime-and(Get-Item $depFile).LastWriteTimeUtc.Ticks-eq$depMtime)
 Check 'java_only_actual_cargo_zero_compile' ((Build 'java-only') -eq 0)
 Text "$app/src/lib.rs" 'pub fn value()->u32 {warm_stage_dependency::value()+1}';Commit $app;Sync
 Check 'native_source_recompiles' ((Build 'native-change') -gt 0)
 Text "$dep/src/lib.rs" 'pub fn value()->u32 {2}';Commit $dep;Sync
 Check 'path_dependency_recompiles' ((Build 'dependency-change') -gt 0)
 Check 'feature_selection_recompiles' ((Build 'feature-change' -Feature) -gt 0)
 $env:RUSTFLAGS='-C opt-level=1'
 Check 'effective_toolchain_flags_recompile' ((Build 'flags-change' -Feature) -gt 0)
 # Existing stage refuses unknown payload instead of deleting another owner's files.
 Text (Join-Path $forest 'app/unowned.txt') 'retain'
 $rejected=$false;try{Sync}catch{$rejected=$true}
 Check 'unexpected_stage_payload_refused_and_retained' ($rejected-and(Test-Path (Join-Path $forest 'app/unowned.txt')))
}finally{
 [Environment]::SetEnvironmentVariable('RUSTFLAGS',$previousFlags,'Process')
 if($lock){$lock.Dispose()}
}
$reopened=Open-NativeForestLock $cache;$reopened.Dispose()
Check 'forest_lock_released_after_terminal' $true
[ordered]@{stage_helper_sha256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot '../lib/ContentStableCargoStage.psm1')).Hash.ToLowerInvariant();test_sha256=(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant();passed=$true;controls=$controls.ToArray();observations=$observations.ToArray();scope='real host Cargo with actual shared stage helper; not Android APK/device qualification';outputs_retained=$true}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $OutputRoot 'RESULT.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $OutputRoot 'RESULT.json') -Raw
