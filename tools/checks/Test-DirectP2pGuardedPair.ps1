[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if(Test-Path -LiteralPath $OutputRoot){throw 'New output required'}
New-Item -ItemType Directory -Path $OutputRoot|Out-Null
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$builder=Join-Path $repo 'tools/diagnostics/quest-original-station-guard/Build.ps1'
$tokens=$null;$parseErrors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($builder,[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count-ne0){throw ($parseErrors|Out-String)}
foreach($function in $ast.FindAll({param($node)$node-is[Management.Automation.Language.FunctionDefinitionAst]},$false)){. ([scriptblock]::Create($function.Extent.Text))}
$fixture=Join-Path $OutputRoot 'source';New-Item -ItemType Directory -Path $fixture|Out-Null
&git -C $fixture init -b main|Out-Null;if($LASTEXITCODE-ne0){throw 'Git fixture init failed'}
[IO.File]::WriteAllText((Join-Path $fixture 'source space ü.txt'),'reviewed',[Text.UTF8Encoding]::new($false))
&git -C $fixture add -- .;&git -c user.name='Guard fixture' -c user.email='guard-fixture@example.invalid' -C $fixture commit -m fixture|Out-Null;if($LASTEXITCODE-ne0){throw 'Git fixture commit failed'}
$head=(&git -C $fixture rev-parse HEAD)-join'';$tree=(&git -C $fixture rev-parse 'HEAD^{tree}')-join''
$sourcePin=@{path=(Join-Path $fixture 'source space ü.txt');sha256=(Get-GuardHash (Join-Path $fixture 'source space ü.txt'))}
$toolPath=Join-Path $OutputRoot 'tool-model';[IO.File]::WriteAllText($toolPath,'modeled tool identity');$toolPin=@{path=$toolPath;sha256=(Get-GuardHash $toolPath)}
$tools=@{};foreach($name in @('javac','java','jar','android_jar','d8','d8_jar')){$tools[$name]=$toolPin.Clone()}
$plan=@{schema='rusty.quest.guarded_p2p_build_plan.v1';artifact_set='Guardian';sources=@(@{root=$fixture;commit=$head;tree=$tree;files=@($sourcePin.Clone())});tools=$tools;minimum_free_bytes=268435456}
$script:cases=0
function Check([bool]$Condition,[string]$Name){if(-not$Condition){throw $Name};$script:cases++}
function Denies([scriptblock]$Work,[string]$Name){$denied=$false;try{&$Work|Out-Null}catch{$denied=$true};Check $denied $Name}
Check (@(Get-GuardBuildIdentity $plan).Count-eq7) 'Actual production build closure with Unicode/space source path'
$copy=$plan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable
$copy.sources[0].files[0].sha256=('0'*64);Denies {Get-GuardBuildIdentity $copy} 'Wrong raw source hash before compiler'
$copy=$plan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.sources[0].files=@();Denies {Get-GuardBuildIdentity $copy} 'Missing tracked input before compiler'
$copy=$plan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.sources[0].commit=('0'*40);Denies {Get-GuardBuildIdentity $copy} 'Wrong HEAD before compiler'
$copy=$plan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.sources[0].tree=('0'*40);Denies {Get-GuardBuildIdentity $copy} 'Wrong tree before compiler'
$copy=$plan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.sources[0].root=Join-Path $OutputRoot 'missing';Denies {Get-GuardBuildIdentity $copy} 'Missing source before compiler'
$copy=$plan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.tools.d8_jar.sha256='';Denies {Get-GuardBuildIdentity $copy} 'Empty executable input pin'
$copy=$plan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.tools.java.path=Join-Path $OutputRoot 'missing';Denies {Get-GuardBuildIdentity $copy} 'Missing selected tool'
$copy=$plan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.artifact_set='Unknown';Denies {Get-GuardBuildIdentity $copy} 'Unknown artifact mode'
$copy=$plan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.minimum_free_bytes=0;Denies {Get-GuardBuildIdentity $copy} 'Missing peak budget'
[IO.File]::WriteAllText($sourcePin.path,'changed');Denies {Get-GuardBuildIdentity $plan} 'Dirty source denied'
[IO.File]::WriteAllText($sourcePin.path,'reviewed');Check (@(Get-GuardBuildIdentity $plan).Count-eq7) 'Restored same-byte clean source'
[IO.File]::WriteAllText((Join-Path $fixture 'unexpected.txt'),'new');Denies {Get-GuardBuildIdentity $plan} 'Untracked source denied'
Remove-Item -LiteralPath (Join-Path $fixture 'unexpected.txt')
$receipt=Join-Path $OutputRoot 'immutable.json';Write-GuardNew $receipt @{status='modeled'};Denies {Write-GuardNew $receipt @{status='changed'}} 'Receipt create-new protects first bytes'
Check ((Get-Content -LiteralPath $receipt -Raw|ConvertFrom-Json).status-ceq'modeled') 'Original receipt retained'
[IO.File]::WriteAllText((Join-Path $OutputRoot 'RESULT.json'),(@{schema='rusty.quest.guarded_p2p_host_test.v1';status='pass';cases=$script:cases;device_calls=0;compiler_calls=0;builder_sha256=(Get-GuardHash $builder)}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Output "guarded_p2p_build_guards=pass cases=$script:cases device_calls=0 compiler_calls=0"
