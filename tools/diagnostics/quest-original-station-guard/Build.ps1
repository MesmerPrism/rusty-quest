[CmdletBinding()]
param([Parameter(Mandatory)][string]$PlanPath,[Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$PlanSha256,[Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
function Get-GuardHash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Assert-GuardPin($Pin){if($Pin.sha256-cnotmatch'^[a-f0-9]{64}$'-or-not(Test-Path -LiteralPath $Pin.path -PathType Leaf)-or(Get-GuardHash $Pin.path)-cne$Pin.sha256){throw 'Build file pin differs'}}
function Get-GuardTrackedPaths([string]$Root){
 $start=[Diagnostics.ProcessStartInfo]::new('git');$start.UseShellExecute=$false;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
 foreach($argument in @('-C',$Root,'ls-files','-z')){$start.ArgumentList.Add($argument)}
 $process=[Diagnostics.Process]::Start($start);try{$errorRead=$process.StandardError.ReadToEndAsync();$raw=$process.StandardOutput.ReadToEnd();$process.WaitForExit();if($process.ExitCode-ne0-or$raw.Length-gt8388608){throw 'Bounded tracked inventory unavailable'};@($raw.Split([char]0,[StringSplitOptions]::RemoveEmptyEntries)|ForEach-Object{[IO.Path]::GetFullPath((Join-Path $Root $_))})}finally{$process.Dispose()}
}
function Get-GuardBuildIdentity($Plan){
 if($Plan.schema-cne'rusty.quest.guarded_p2p_build_plan.v1'-or$Plan.artifact_set-cnotin@('Guardian','Pair')-or@($Plan.sources).Count-ne$(if($Plan.artifact_set-ceq'Pair'){2}else{1})){throw 'Closed artifact/source set required'}
 $rows=@();foreach($source in $Plan.sources){
  $head=(&git -C $source.root rev-parse HEAD)-join'';if($LASTEXITCODE-ne0){throw 'Source HEAD unavailable'}
  $tree=(&git -C $source.root rev-parse 'HEAD^{tree}')-join'';if($LASTEXITCODE-ne0){throw 'Source tree unavailable'}
  $dirty=@(&git -C $source.root status --porcelain --untracked-files=normal);if($LASTEXITCODE-ne0-or$dirty.Count-ne0-or$head-cne$source.commit-or$tree-cne$source.tree){throw 'Build source is not exact and clean'}
  $tracked=@(Get-GuardTrackedPaths $source.root|Sort-Object);$declared=@($source.files|ForEach-Object{[IO.Path]::GetFullPath($_.path)}|Sort-Object)
  if(($tracked-join"`n")-cne($declared-join"`n")){throw 'Build complete raw tracked source inventory differs'}
  foreach($pin in $source.files){Assert-GuardPin $pin;$rows+=@{path=$pin.path;sha256=$pin.sha256}}
 }
 $toolNames=@('javac','java','jar','android_jar','d8','d8_jar');if($Plan.artifact_set-ceq'Pair'){$toolNames+=@('aapt2','zipalign','apksigner','apksigner_jar','cargo','rustc','clang','clang_exe','ar','lld','keystore')}
 foreach($name in $toolNames){Assert-GuardPin $Plan.tools.$name;$rows+=@{path=$Plan.tools.$name.path;sha256=$Plan.tools.$name.sha256}}
 if($Plan.minimum_free_bytes-lt$(if($Plan.artifact_set-ceq'Pair'){1073741824}else{268435456})){throw 'Free-space bound missing'}
 if($Plan.artifact_set-ceq'Guardian'){return @($rows)}
 if($Plan.signer_sha256-cnotmatch'^[a-f0-9]{64}$'){throw 'Signer missing'}
 Assert-GuardPin $Plan.task_override
 $override=Get-Content -LiteralPath $Plan.task_override.path -Raw|ConvertFrom-Json
 if($override.schema-cne'local.morphovision.task_coordination_override.v1'-or$override.direct_user_authorization-ne$true-or$override.agent_board_required-ne$false-or'apk-build'-cnotin$override.scope-or(@($override.serials|Sort-Object)-join'|')-cne'340YC10G7T0JBW|3487C10H3M017Q'){throw 'Exact task APK-build override required'}
 Assert-GuardPin $Plan.authorization_evidence
 if([IO.Path]::GetFullPath($override.authorization_evidence)-cne[IO.Path]::GetFullPath($Plan.authorization_evidence.path)){throw 'Override evidence join differs'}
 $rows+=@{path=$Plan.task_override.path;sha256=$Plan.task_override.sha256};$rows+=@{path=$Plan.authorization_evidence.path;sha256=$Plan.authorization_evidence.sha256}
 return @($rows)
}
function Write-GuardNew([string]$Path,$Body){$bytes=[Text.UTF8Encoding]::new($false).GetBytes(($Body|ConvertTo-Json -Depth 30));$stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew);try{$stream.Write($bytes)}finally{$stream.Dispose()}}
function Invoke-GuardBuildTool([string]$Path,[string[]]$Arguments,[string]$Log){$result=&$Path @Arguments 2>&1;$code=$LASTEXITCODE;[IO.File]::WriteAllLines($Log,@($result|ForEach-Object ToString),[Text.UTF8Encoding]::new($false));if($code-ne0){throw "Build step failed: $([IO.Path]::GetFileName($Log)) exit=$code"}}
if((Get-GuardHash $PlanPath)-cne$PlanSha256){throw 'Build plan hash differs'}
$plan=Get-Content -LiteralPath $PlanPath -Raw|ConvertFrom-Json -Depth 30
$identity=Get-GuardBuildIdentity $plan
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
if([IO.Path]::GetFullPath($plan.sources[0].root)-cne[IO.Path]::GetFullPath($repo)){throw 'Builder must execute from selected Quest source'}
$target=[IO.Path]::GetFullPath((Join-Path $repo 'target'))+[IO.Path]::DirectorySeparatorChar
$out=[IO.Path]::GetFullPath($OutputRoot)
if(-not$out.StartsWith($target,[StringComparison]::OrdinalIgnoreCase)-or(Test-Path -LiteralPath $out)){throw 'New task output under selected source target required'}
$drive=[IO.DriveInfo]::new([IO.Path]::GetPathRoot($out));if($drive.AvailableFreeSpace-lt$plan.minimum_free_bytes){throw 'Peak build free-space guard failed'}
$tools=$plan.tools
$guardSources=@('OriginalStationGuardContract.java','QuestOriginalStationGuard.java'|ForEach-Object{Join-Path $PSScriptRoot $_})
$appRoot=Join-Path $repo 'apps/direct-p2p-provider-android'
$appSources=@(Get-ChildItem -LiteralPath (Join-Path $appRoot 'src/main/java/io/github/mesmerprism/rustyquest/directp2p') -Filter '*.java' -File|Where-Object Name -CNotLike '*HostTest.java'|ForEach-Object FullName)
$compileInputs=@($guardSources);if($plan.artifact_set-ceq'Pair'){$compileInputs+=@($appSources)+@((Join-Path $appRoot 'AndroidManifest.xml'),(Join-Path $repo 'Cargo.lock'))}
foreach($path in $compileInputs){if(@($identity|Where-Object path -CEQ $path).Count-ne1){throw 'Actual compiler input missing from declared pin closure'}}
if($plan.artifact_set-ceq'Pair'){
 $derived=[IO.Path]::GetFullPath((Join-Path $appRoot 'native/../../../../rusty-manifold'))
 $actualHead=(&git -C $derived rev-parse HEAD)-join'';$actualTree=(&git -C $derived rev-parse 'HEAD^{tree}')-join''
 if($LASTEXITCODE-ne0-or$actualHead-cne$plan.sources[1].commit-or$actualTree-cne$plan.sources[1].tree){throw 'Cargo derived Manifold supplier differs'}
}
New-Item -ItemType Directory -Path $out|Out-Null
Write-GuardNew (Join-Path $out 'inputs.json') @{schema='rusty.quest.guarded_p2p_build_inputs.v1';plan_sha256=$PlanSha256;inputs=$identity;sources=$plan.sources;builder_sha256=(Get-GuardHash $PSCommandPath)}
foreach($dir in @('guardian-classes','guardian-dex','app-classes','app-dex','native/lib/arm64-v8a')){New-Item -ItemType Directory -Path (Join-Path $out $dir)|Out-Null}
$lanes=@('guardian');if($plan.artifact_set-ceq'Pair'){$lanes+='app'}
foreach($lane in $lanes){
 $sources=if($lane-ceq'guardian'){$guardSources}else{$appSources}
 $javacArguments=@('-encoding','UTF-8','-source','8','-target','8','-cp',$tools.android_jar.path,'-d',(Join-Path $out "$lane-classes"))+@($sources)
 Invoke-GuardBuildTool $tools.javac.path $javacArguments (Join-Path $out "$lane-javac.log")
 Invoke-GuardBuildTool $tools.jar.path @('cf',(Join-Path $out "$lane-classes.jar"),'-C',(Join-Path $out "$lane-classes"),'.') (Join-Path $out "$lane-jar.log")
 Invoke-GuardBuildTool $tools.java.path @('-cp',$tools.d8_jar.path,'com.android.tools.r8.D8','--min-api','29','--lib',$tools.android_jar.path,'--output',(Join-Path $out "$lane-dex"),(Join-Path $out "$lane-classes.jar")) (Join-Path $out "$lane-d8.log")
}
Copy-Item -LiteralPath (Join-Path $out 'guardian-dex/classes.dex') -Destination (Join-Path $out 'quest-original-station-guard.dex')
if($plan.artifact_set-ceq'Guardian'){
 $after=Get-GuardBuildIdentity $plan;if(($identity|ConvertTo-Json -Depth 8 -Compress)-cne($after|ConvertTo-Json -Depth 8 -Compress)){throw 'Source/tool input changed during build'}
 Write-GuardNew (Join-Path $out 'RESULT.json') @{schema='rusty.quest.guarded_p2p_build.v1';status='built_device_unverified';artifact_set='Guardian';plan_sha256=$PlanSha256;inputs_sha256=(Get-GuardHash (Join-Path $out 'inputs.json'));sources=$plan.sources;dex=@{path=(Join-Path $out 'quest-original-station-guard.dex');sha256=(Get-GuardHash (Join-Path $out 'quest-original-station-guard.dex'))};device_calls=0}
 Write-Output (Join-Path $out 'RESULT.json');exit 0
}
$saved=@{};foreach($name in @('CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER','CC_aarch64_linux_android','AR_aarch64_linux_android','RUSTC','CARGO_NET_OFFLINE')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
 $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$tools.clang.path;$env:CC_aarch64_linux_android=$tools.clang.path;$env:AR_aarch64_linux_android=$tools.ar.path;$env:RUSTC=$tools.rustc.path;$env:CARGO_NET_OFFLINE='true'
 Push-Location $repo;try{Invoke-GuardBuildTool $tools.cargo.path @('build','--offline','--locked','--target','aarch64-linux-android','--target-dir',(Join-Path $out 'cargo-target'),'-p','rusty-quest-direct-p2p-provider-native') (Join-Path $out 'cargo.log')}finally{Pop-Location}
}finally{foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
$native=Join-Path $out 'cargo-target/aarch64-linux-android/debug/librusty_quest_direct_p2p_provider.so'
Copy-Item -LiteralPath $native -Destination (Join-Path $out 'native/lib/arm64-v8a/librusty_quest_direct_p2p_provider.so')
$unsigned=Join-Path $out 'unsigned.apk';$unaligned=Join-Path $out 'unaligned.apk';$aligned=Join-Path $out 'aligned.apk';$apk=Join-Path $out 'rusty-quest-direct-p2p-provider.apk'
Invoke-GuardBuildTool $tools.aapt2.path @('link','-o',$unsigned,'--manifest',(Join-Path $appRoot 'AndroidManifest.xml'),'-I',$tools.android_jar.path,'--min-sdk-version','29','--target-sdk-version','34','--version-code','1','--version-name','0.1.0') (Join-Path $out 'aapt2.log')
Copy-Item -LiteralPath $unsigned -Destination $unaligned
Invoke-GuardBuildTool $tools.jar.path @('uf',$unaligned,'-C',(Join-Path $out 'app-dex'),'classes.dex','-C',(Join-Path $out 'native'),'lib') (Join-Path $out 'package.log')
Invoke-GuardBuildTool $tools.zipalign.path @('-f','4',$unaligned,$aligned) (Join-Path $out 'align.log')
Invoke-GuardBuildTool $tools.java.path @('-jar',$tools.apksigner_jar.path,'sign','--ks',$tools.keystore.path,'--ks-pass','pass:android','--key-pass','pass:android','--out',$apk,$aligned) (Join-Path $out 'sign.log')
$verify=&$tools.java.path -jar $tools.apksigner_jar.path verify --verbose --print-certs $apk 2>&1;$verifyExit=$LASTEXITCODE
[IO.File]::WriteAllLines((Join-Path $out 'signature.log'),@($verify|ForEach-Object ToString),[Text.UTF8Encoding]::new($false))
if($verifyExit-ne0-or(@($verify)-join"`n")-notmatch('Signer #1 certificate SHA-256 digest: '+[regex]::Escape($plan.signer_sha256))){throw 'Actual APK signer differs'}
$after=Get-GuardBuildIdentity $plan;if(($identity|ConvertTo-Json -Depth 8 -Compress)-cne($after|ConvertTo-Json -Depth 8 -Compress)){throw 'Source/tool input changed during build'}
Write-GuardNew (Join-Path $out 'RESULT.json') @{schema='rusty.quest.guarded_p2p_build.v1';status='built_device_unverified';artifact_set='Pair';plan_sha256=$PlanSha256;inputs_sha256=(Get-GuardHash (Join-Path $out 'inputs.json'));sources=$plan.sources;media_enabled=$false;package='io.github.mesmerprism.rustyquest.directp2p';apk=@{path=$apk;sha256=(Get-GuardHash $apk);signer_sha256=$plan.signer_sha256};dex=@{path=(Join-Path $out 'quest-original-station-guard.dex');sha256=(Get-GuardHash (Join-Path $out 'quest-original-station-guard.dex'))};native=@{path=$native;sha256=(Get-GuardHash $native)};device_calls=0}
Write-Output (Join-Path $out 'RESULT.json')
