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
function Resolve-GuardRoot([string]$Root){
 $directory=Get-Item -LiteralPath $Root -ErrorAction Stop
 if(-not$directory.PSIsContainer){throw 'Source root is not a directory'}
 if($directory.LinkType){$directory=$directory.ResolveLinkTarget($true);if($null-eq$directory){throw 'Source link target unavailable'}}
 [IO.Path]::GetFullPath($directory.FullName).TrimEnd([IO.Path]::DirectorySeparatorChar)
}
function Assert-GuardSupplierRoute($Plan,[string]$Repo){
 if($Plan.artifact_set-cne'Pair'){return}
 $derived=[IO.Path]::GetFullPath((Join-Path $Repo 'apps/direct-p2p-provider-android/native/../../../../rusty-manifold'))
 if((Resolve-GuardRoot $derived)-cne(Resolve-GuardRoot $Plan.sources[1].root)){throw 'Cargo supplier path differs from declared source closure'}
 $head=(&git -C $derived rev-parse HEAD)-join'';$tree=(&git -C $derived rev-parse 'HEAD^{tree}')-join'';$dirty=@(&git -C $derived status --porcelain --untracked-files=normal)
 if($LASTEXITCODE-ne0-or$dirty.Count-ne0-or$head-cne$Plan.sources[1].commit-or$tree-cne$Plan.sources[1].tree){throw 'Actual Cargo supplier is not exact and clean'}
 $declaredRoot=[IO.Path]::GetFullPath($Plan.sources[1].root).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
 $declaredRelative=@();$seen=@{}
 foreach($pin in $Plan.sources[1].files){
  $absolute=[IO.Path]::GetFullPath($pin.path)
  if(-not$absolute.StartsWith($declaredRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Cargo supplier pin outside declared root'}
  $relative=$absolute.Substring($declaredRoot.Length)
  if($seen.ContainsKey($relative)){throw 'Duplicate Cargo supplier pin'};$seen[$relative]=$true;$declaredRelative+=$relative
  Assert-GuardPin @{path=(Join-Path $derived $relative);sha256=$pin.sha256}
 }
 $actualRelative=@(Get-GuardTrackedPaths $derived|ForEach-Object{$_.Substring(([IO.Path]::GetFullPath($derived).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar).Length)}|Sort-Object)
 if(($actualRelative-join"`n")-cne(@($declaredRelative|Sort-Object)-join"`n")){throw 'Actual Cargo supplier raw inventory differs'}
}
function Get-GuardNativeDependencyInputs($Plan){
 if($Plan.artifact_set-cne'Pair'){return @()}
 Assert-GuardPin $Plan.native_dependency_closure
 if((Get-Item -LiteralPath $Plan.native_dependency_closure.path).Length-gt1048576){throw 'Native dependency closure bound'}
 $closure=Get-Content -LiteralPath $Plan.native_dependency_closure.path -Raw|ConvertFrom-Json -Depth 20
 if($closure.schema-cne'local.quest.pair_native_dependency_closure.v1'-or@($closure.registry_packages).Count-lt1-or@($closure.registry_packages).Count-gt128-or@($closure.tool_inputs).Count-lt1-or@($closure.tool_inputs).Count-gt256){throw 'Closed native dependency input set required'}
 if([IO.Path]::GetFullPath($closure.lock.path)-cne[IO.Path]::GetFullPath((Join-Path $Plan.sources[0].root 'Cargo.lock'))){throw 'Native graph lock must be selected Quest source'}
 $rows=@();foreach($pin in @($Plan.native_dependency_closure,$closure.metadata,$closure.lock,$closure.retained_android_inputs,$Plan.host_setup,$Plan.host_setup_inputs)+@($closure.tool_inputs)){Assert-GuardPin $pin;$rows+=@{path=$pin.path;sha256=$pin.sha256}}
 foreach($name in @('cargo','rustc','clang','clang_exe','lld')){if(@($closure.tool_inputs|Where-Object{[IO.Path]::GetFullPath($_.path)-ceq[IO.Path]::GetFullPath($Plan.tools.$name.path)-and$_.sha256-ceq$Plan.tools.$name.sha256}).Count-ne1){throw 'Selected native tool is outside reviewed dependency closure'}}
 $metadata=Get-Content -LiteralPath $closure.metadata.path -Raw|ConvertFrom-Json -Depth 100
 $selected=[Collections.Generic.HashSet[string]]::new();$pending=[Collections.Generic.Stack[string]]::new()
 $entries=@($metadata.packages|Where-Object{$_.name-ceq'rusty-quest-direct-p2p-provider-native'});if($entries.Count-ne1){throw 'Selected native package graph unavailable'};$pending.Push($entries[0].id)
 while($pending.Count){$id=$pending.Pop();if(-not$selected.Add($id)){continue};if($selected.Count-gt512){throw 'Native graph bound'};$nodes=@($metadata.resolve.nodes|Where-Object{$_.id-ceq$id});if($nodes.Count-ne1){throw 'Native graph dependency unknown'};foreach($dep in $nodes[0].deps){if(@($dep.dep_kinds|Where-Object{$_.kind-cne'dev'}).Count){$pending.Push($dep.pkg)}}}
 $pathRoots=@([IO.Path]::GetFullPath($Plan.sources[0].root),[IO.Path]::GetFullPath((Join-Path $Plan.sources[0].root '../rusty-manifold')))
 foreach($package in @($metadata.packages|Where-Object{$selected.Contains($_.id)-and$null-eq$_.source})){
  $manifest=[IO.Path]::GetFullPath($package.manifest_path)
  if(@($pathRoots|Where-Object{$manifest.StartsWith(($_.TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar),[StringComparison]::OrdinalIgnoreCase)}).Count-ne1){throw 'Selected native path package outside exact source supplier closure'}
 }
 $required=@($metadata.packages|Where-Object{$selected.Contains($_.id)-and$null-ne$_.source}|ForEach-Object{[IO.Path]::GetFullPath((Split-Path $_.manifest_path))}|Sort-Object)
 $declared=@($closure.registry_packages|ForEach-Object{[IO.Path]::GetFullPath($_.root)}|Sort-Object)
 if(($required-join"`n")-cne($declared-join"`n")){throw 'Selected native graph registry closure incomplete'}
 $seen=@{};foreach($package in $closure.registry_packages){
  if($package.name-cnotmatch'^[a-zA-Z0-9_-]+$'-or$package.version-cnotmatch'^[0-9]+\.[0-9]+\.[0-9]+([+-][a-zA-Z0-9.]+)?$'-or$seen.ContainsKey($package.root)){throw 'Native registry package identity'};$seen[$package.root]=$true
  $root=[IO.Path]::GetFullPath($package.root).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
  $matched=@($metadata.packages|Where-Object{$_.name-ceq$package.name-and$_.version-ceq$package.version-and$_.source-ceq'registry+https://github.com/rust-lang/crates.io-index'-and[IO.Path]::GetFullPath((Split-Path $_.manifest_path)).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar-ceq$root})
  if($matched.Count-ne1){throw 'Native registry package missing from pinned locked graph'}
  Assert-GuardPin $package.archive;$rows+=@{path=$package.archive.path;sha256=$package.archive.sha256}
  $actual=@(Get-ChildItem -LiteralPath $package.root -Recurse -File -Force|ForEach-Object{[IO.Path]::GetFullPath($_.FullName)}|Sort-Object)
  $declared=@($package.files|ForEach-Object{[IO.Path]::GetFullPath($_.path)}|Sort-Object)
  if($actual.Count-gt10000-or($actual-join"`n")-cne($declared-join"`n")){throw 'Native registry complete raw inventory differs'}
  foreach($pin in $package.files){if(-not[IO.Path]::GetFullPath($pin.path).StartsWith($root,[StringComparison]::OrdinalIgnoreCase)){throw 'Native registry pin outside package'};Assert-GuardPin $pin;$rows+=@{path=$pin.path;sha256=$pin.sha256}}
 }
 return @($rows)
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
  $rows+=@(Get-GuardNativeDependencyInputs $Plan)
 if($Plan.signer_sha256-cnotmatch'^[a-f0-9]{64}$'){throw 'Signer missing'}
 Assert-GuardPin $Plan.task_override
 $override=Get-Content -LiteralPath $Plan.task_override.path -Raw|ConvertFrom-Json
 if($override.schema-cne'local.morphovision.task_coordination_override.v1'-or$override.direct_user_authorization-ne$true-or$override.agent_board_required-ne$false-or'apk-build'-cnotin$override.scope-or(@($override.serials|Sort-Object)-join'|')-cne'340YC10G7T0JBW|3487C10H3M017Q'){throw 'Exact task APK-build override required'}
 Assert-GuardPin $Plan.authorization_evidence
 if([IO.Path]::GetFullPath($override.authorization_evidence)-cne[IO.Path]::GetFullPath($Plan.authorization_evidence.path)){throw 'Override evidence join differs'}
 $rows+=@{path=$Plan.task_override.path;sha256=$Plan.task_override.sha256};$rows+=@{path=$Plan.authorization_evidence.path;sha256=$Plan.authorization_evidence.sha256}
 return @($rows)
}
function Assert-GuardCompilerInputClosure([string[]]$Paths,$Identity){
 foreach($path in $Paths){
  $normalized=[IO.Path]::GetFullPath($path)
  $pins=@($Identity|Where-Object{[IO.Path]::GetFullPath($_.path).Equals($normalized,[StringComparison]::OrdinalIgnoreCase)})
  if($pins.Count-lt1){throw 'Actual compiler input missing from declared pin closure'}
  $observed=Get-GuardHash $path
  foreach($pin in $pins){if($pin.sha256-cnotmatch'^[0-9a-f]{64}$'-or$pin.sha256-cne$observed){throw 'Actual compiler input has conflicting or stale declared provenance'}}
 }
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
Assert-GuardCompilerInputClosure $compileInputs $identity
if($plan.artifact_set-ceq'Pair'){
 $derived=[IO.Path]::GetFullPath((Join-Path $appRoot 'native/../../../../rusty-manifold'))
 $actualHead=(&git -C $derived rev-parse HEAD)-join'';$actualTree=(&git -C $derived rev-parse 'HEAD^{tree}')-join''
 if($LASTEXITCODE-ne0-or$actualHead-cne$plan.sources[1].commit-or$actualTree-cne$plan.sources[1].tree){throw 'Cargo derived Manifold supplier differs'}
}
Assert-GuardSupplierRoute $plan $repo
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
 Assert-GuardSupplierRoute $plan $repo
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
Assert-GuardSupplierRoute $plan $repo
$after=Get-GuardBuildIdentity $plan;if(($identity|ConvertTo-Json -Depth 8 -Compress)-cne($after|ConvertTo-Json -Depth 8 -Compress)){throw 'Source/tool input changed during build'}
Write-GuardNew (Join-Path $out 'RESULT.json') @{schema='rusty.quest.guarded_p2p_build.v1';status='built_device_unverified';artifact_set='Pair';plan_sha256=$PlanSha256;inputs_sha256=(Get-GuardHash (Join-Path $out 'inputs.json'));sources=$plan.sources;media_enabled=$false;package='io.github.mesmerprism.rustyquest.directp2p';apk=@{path=$apk;sha256=(Get-GuardHash $apk);signer_sha256=$plan.signer_sha256};dex=@{path=(Join-Path $out 'quest-original-station-guard.dex');sha256=(Get-GuardHash (Join-Path $out 'quest-original-station-guard.dex'))};native=@{path=$native;sha256=(Get-GuardHash $native)};device_calls=0}
Write-Output (Join-Path $out 'RESULT.json')
