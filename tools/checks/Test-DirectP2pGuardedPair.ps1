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
# Execute the actual physical Cargo-route guard against a separate real Git supplier.
$composition=Join-Path $OutputRoot 'pair';$pairRepo=Join-Path $composition 'quest';$supplier=Join-Path $composition 'rusty-manifold'
New-Item -ItemType Directory -Path $pairRepo|Out-Null
&git clone --no-hardlinks --quiet $fixture $supplier;if($LASTEXITCODE-ne0){throw 'Supplier fixture clone'}
[IO.File]::WriteAllText((Join-Path $supplier '.gitattributes'),"*.txt text`n",[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $supplier 'source space ü.txt'),"reviewed`n",[Text.UTF8Encoding]::new($false))
&git -C $supplier add -- .;&git -c user.name='Guard fixture' -c user.email='guard-fixture@example.invalid' -C $supplier commit -m 'raw source baseline'|Out-Null;if($LASTEXITCODE-ne0){throw 'Supplier fixture commit'}
$supplierPin=@{root=$supplier;commit=((&git -C $supplier rev-parse HEAD)-join'');tree=((&git -C $supplier rev-parse 'HEAD^{tree}')-join'');files=@(Get-GuardTrackedPaths $supplier|ForEach-Object{@{path=$_;sha256=(Get-GuardHash $_)}})}
$routePlan=@{artifact_set='Pair';sources=@(@{root=$pairRepo},$supplierPin)}
$script:compilerCalls=0
function Invoke-ModeledPairCompiler($Selected){Assert-GuardSupplierRoute $Selected $pairRepo;$script:compilerCalls++}
Invoke-ModeledPairCompiler $routePlan;Check ($script:compilerCalls-eq1) 'Declared physical supplier and complete raw pins'
$script:compilerCalls=0
$copy=$routePlan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.sources[1].root=$fixture
Denies {Invoke-ModeledPairCompiler $copy} 'Same-head alternate source root cannot stand in for Cargo route'
$copy=$routePlan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.sources[1].files=@()
Denies {Invoke-ModeledPairCompiler $copy} 'Derived supplier missing raw inventory'
$copy=$routePlan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.sources[1].files[0].sha256=''
Denies {Invoke-ModeledPairCompiler $copy} 'Derived supplier empty raw pin'
$copy=$routePlan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.sources[1].files+=@($copy.sources[1].files[0])
Denies {Invoke-ModeledPairCompiler $copy} 'Derived supplier duplicate raw pin'
$copy=$routePlan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.sources[1].files[0].path=$toolPath
Denies {Invoke-ModeledPairCompiler $copy} 'Derived supplier outside-authority pin'
$copy=$routePlan|ConvertTo-Json -Depth 15|ConvertFrom-Json -AsHashtable;$copy.sources[1].tree='0'*40
Denies {Invoke-ModeledPairCompiler $copy} 'Derived supplier tree mismatch'
$rawFile=Join-Path $supplier 'source space ü.txt'
[IO.File]::WriteAllText($rawFile,"reviewed`r`n",[Text.UTF8Encoding]::new($false))
&git -C $supplier add -- 'source space ü.txt';if($LASTEXITCODE-ne0){throw 'Raw carrier refresh'}
Check (@(&git -C $supplier status --porcelain).Count-eq0) 'Git-clean raw CRLF drift fixture'
Denies {Invoke-ModeledPairCompiler $routePlan} 'Git-clean raw drift denied before compiler'
[IO.File]::WriteAllText($rawFile,"changed`n",[Text.UTF8Encoding]::new($false))
Denies {Invoke-ModeledPairCompiler $routePlan} 'Dirty actual derived supplier denied'
[IO.File]::WriteAllText($rawFile,"reviewed`n",[Text.UTF8Encoding]::new($false))
&git -C $supplier add -- 'source space ü.txt';if($LASTEXITCODE-ne0){throw 'Raw carrier refresh'}
[IO.File]::WriteAllText((Join-Path $supplier 'unexpected.txt'),'unknown')
Denies {Invoke-ModeledPairCompiler $routePlan} 'Unexpected derived supplier input denied'
Remove-Item -LiteralPath (Join-Path $supplier 'unexpected.txt')
Check ($script:compilerCalls-eq0) 'All rejected closures dispatch zero compiler calls'
Assert-GuardSupplierRoute $routePlan $pairRepo;Check $true 'Restored full raw closure accepted'
[IO.File]::WriteAllText($rawFile,"reviewed`r`n",[Text.UTF8Encoding]::new($false))
&git -C $supplier add -- 'source space ü.txt';if($LASTEXITCODE-ne0){throw 'Raw carrier refresh'}
Denies {Assert-GuardSupplierRoute $routePlan $pairRepo} 'Post-compiler same-Git raw drift denies final receipt'
[IO.File]::WriteAllText($rawFile,"reviewed`n",[Text.UTF8Encoding]::new($false))
&git -C $supplier add -- 'source space ü.txt';if($LASTEXITCODE-ne0){throw 'Raw carrier refresh'}
$registry=Join-Path $OutputRoot 'registry/model-1.0.0';New-Item -ItemType Directory -Path $registry|Out-Null
foreach($file in @('Cargo.toml','lib.rs')){[IO.File]::WriteAllText((Join-Path $registry $file),'closed modeled registry source')}
$archivePath=Join-Path $OutputRoot 'model-1.0.0.crate';[IO.File]::WriteAllText($archivePath,'modeled locked archive')
$metadataPath=Join-Path $OutputRoot 'metadata.json';Write-GuardNew $metadataPath @{packages=@(@{id='native';name='rusty-quest-direct-p2p-provider-native';source=$null;manifest_path=(Join-Path $pairRepo 'Cargo.toml')},@{id='registry';name='model';version='1.0.0';source='registry+https://github.com/rust-lang/crates.io-index';manifest_path=(Join-Path $registry 'Cargo.toml')});resolve=@{nodes=@(@{id='native';deps=@(@{pkg='registry';dep_kinds=@(@{kind=$null})})},@{id='registry';deps=@()})}}
$lockPath=Join-Path $pairRepo 'Cargo.lock';[IO.File]::WriteAllText($lockPath,'modeled exact lock')
$nativeTools=@{};$nativeToolPins=@();foreach($name in @('cargo','rustc','clang','clang_exe','lld')){$path=Join-Path $OutputRoot $name;[IO.File]::WriteAllText($path,'modeled '+$name);$pin=@{path=$path;sha256=(Get-GuardHash $path)};$nativeTools[$name]=$pin;$nativeToolPins+=$pin}
$nativeClosure=@{schema='local.quest.pair_native_dependency_closure.v1';metadata=@{path=$metadataPath;sha256=(Get-GuardHash $metadataPath)};lock=@{path=$lockPath;sha256=(Get-GuardHash $lockPath)};retained_android_inputs=$toolPin;tool_inputs=$nativeToolPins;registry_packages=@(@{name='model';version='1.0.0';root=$registry;archive=@{path=$archivePath;sha256=(Get-GuardHash $archivePath)};files=@(Get-ChildItem $registry -File|ForEach-Object{@{path=$_.FullName;sha256=(Get-GuardHash $_.FullName)}})})}
$closurePath=Join-Path $OutputRoot 'native-closure.json';Write-GuardNew $closurePath $nativeClosure
$nativePlan=@{artifact_set='Pair';sources=@(@{root=$pairRepo},$supplierPin);native_dependency_closure=@{path=$closurePath;sha256=(Get-GuardHash $closurePath)};tools=$nativeTools;host_setup=$toolPin;host_setup_inputs=$toolPin}
$script:nativeCompilerCalls=0
function Invoke-ModeledNativeCompiler($Selected){Get-GuardNativeDependencyInputs $Selected|Out-Null;$script:nativeCompilerCalls++}
Invoke-ModeledNativeCompiler $nativePlan;Check ($script:nativeCompilerCalls-eq1) 'Production native registry/tool dependency closure accepted'
$script:nativeCompilerCalls=0
$copy=$nativePlan|ConvertTo-Json -Depth 20|ConvertFrom-Json -AsHashtable;$copy.native_dependency_closure.sha256='0'*64
Denies {Invoke-ModeledNativeCompiler $copy} 'Dependency manifest hash drift before compiler'
$copy=$nativePlan|ConvertTo-Json -Depth 20|ConvertFrom-Json -AsHashtable;$copy.host_setup.sha256='0'*64
Denies {Invoke-ModeledNativeCompiler $copy} 'Host setup pin missing before compiler'
$copy=$nativePlan|ConvertTo-Json -Depth 20|ConvertFrom-Json -AsHashtable;$copy.tools.cargo.sha256='0'*64
Denies {Invoke-ModeledNativeCompiler $copy} 'Selected compiler outside declared native closure'
$changed=Join-Path $registry 'lib.rs';[IO.File]::WriteAllText($changed,'drift')
Denies {Invoke-ModeledNativeCompiler $nativePlan} 'Registry raw drift before compiler'
[IO.File]::WriteAllText($changed,'closed modeled registry source')
$unexpected=Join-Path $registry 'unexpected.rs';[IO.File]::WriteAllText($unexpected,'unknown')
Denies {Invoke-ModeledNativeCompiler $nativePlan} 'Registry complete inventory detects added build input'
Remove-Item -LiteralPath $unexpected
[IO.File]::WriteAllText($archivePath,'drift')
Denies {Invoke-ModeledNativeCompiler $nativePlan} 'Locked archive drift before compiler'
[IO.File]::WriteAllText($archivePath,'modeled locked archive')
[IO.File]::WriteAllText($metadataPath,'{}')
Denies {Invoke-ModeledNativeCompiler $nativePlan} 'Pinned metadata drift before compiler'
[IO.File]::WriteAllText($metadataPath,(@{packages=@(@{id='native';name='rusty-quest-direct-p2p-provider-native';source=$null;manifest_path=(Join-Path $pairRepo 'Cargo.toml')},@{id='registry';name='model';version='1.0.0';source='registry+https://github.com/rust-lang/crates.io-index';manifest_path=(Join-Path $registry 'Cargo.toml')});resolve=@{nodes=@(@{id='native';deps=@(@{pkg='registry';dep_kinds=@(@{kind=$null})})},@{id='registry';deps=@()})}}|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))
Check ($script:nativeCompilerCalls-eq0) 'Every native closure damage dispatches zero compilers'
Get-GuardNativeDependencyInputs $nativePlan|Out-Null;Check $true 'Restored native byte closure accepted'
[IO.File]::WriteAllText($changed,'postbuild drift')
Denies {Get-GuardNativeDependencyInputs $nativePlan} 'Native registry change during compile denies final receipt'
[IO.File]::WriteAllText($changed,'closed modeled registry source')
$extraRegistry=Join-Path $OutputRoot 'registry/extra-1.0.0';New-Item -ItemType Directory -Path $extraRegistry|Out-Null
[IO.File]::WriteAllText((Join-Path $extraRegistry 'Cargo.toml'),'unreviewed dependency')
$copy=$nativeClosure|ConvertTo-Json -Depth 20|ConvertFrom-Json -AsHashtable
$damagedMetadata=@{packages=@(@{id='native';name='rusty-quest-direct-p2p-provider-native';source=$null;manifest_path=(Join-Path $pairRepo 'Cargo.toml')},@{id='registry';name='model';version='1.0.0';source='registry+https://github.com/rust-lang/crates.io-index';manifest_path=(Join-Path $registry 'Cargo.toml')},@{id='extra';name='extra';version='1.0.0';source='registry+https://github.com/rust-lang/crates.io-index';manifest_path=(Join-Path $extraRegistry 'Cargo.toml')});resolve=@{nodes=@(@{id='native';deps=@(@{pkg='registry';dep_kinds=@(@{kind=$null})},@{pkg='extra';dep_kinds=@(@{kind=$null})})},@{id='registry';deps=@()},@{id='extra';deps=@()})}}
$damagedMetadataPath=Join-Path $OutputRoot 'damaged-metadata.json';Write-GuardNew $damagedMetadataPath $damagedMetadata
$copy.metadata=@{path=$damagedMetadataPath;sha256=(Get-GuardHash $damagedMetadataPath)}
$damagedClosurePath=Join-Path $OutputRoot 'damaged-closure.json';Write-GuardNew $damagedClosurePath $copy
$brokenPlan=$nativePlan|ConvertTo-Json -Depth 20|ConvertFrom-Json -AsHashtable;$brokenPlan.native_dependency_closure=@{path=$damagedClosurePath;sha256=(Get-GuardHash $damagedClosurePath)}
Denies {Invoke-ModeledNativeCompiler $brokenPlan} 'Selected transitive registry package cannot be omitted'
$damagedMetadata.packages[0].manifest_path=$toolPath
$outsideMetadataPath=Join-Path $OutputRoot 'outside-metadata.json';Write-GuardNew $outsideMetadataPath $damagedMetadata
$copy.metadata=@{path=$outsideMetadataPath;sha256=(Get-GuardHash $outsideMetadataPath)}
$outsideClosurePath=Join-Path $OutputRoot 'outside-closure.json';Write-GuardNew $outsideClosurePath $copy
$brokenPlan.native_dependency_closure=@{path=$outsideClosurePath;sha256=(Get-GuardHash $outsideClosurePath)}
Denies {Invoke-ModeledNativeCompiler $brokenPlan} 'Unknown path source package cannot escape supplier closure'
[IO.File]::WriteAllText((Join-Path $OutputRoot 'RESULT.json'),(@{schema='rusty.quest.guarded_p2p_host_test.v1';status='pass';cases=$script:cases;device_calls=0;compiler_calls=0;builder_sha256=(Get-GuardHash $builder)}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Output "guarded_p2p_build_guards=pass cases=$script:cases device_calls=0 compiler_calls=0"
