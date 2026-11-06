[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$NativeBridge,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$NativeBridgeSha256,
    [Parameter(Mandatory)][string]$NativeManifest,
    [string]$OutputRoot
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if(-not $OutputRoot){$OutputRoot=Join-Path $repo ('target/hotload-shared/'+[guid]::NewGuid().ToString('N'))}
if(Test-Path -LiteralPath $OutputRoot){throw 'Use a fresh retained output directory.'}
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$output=(Resolve-Path -LiteralPath $OutputRoot).Path
$commands=[Collections.Generic.List[object]]::new()
function Record([string]$Path){$f=Get-Item -LiteralPath $Path;[ordered]@{path=$f.FullName;sha256=(Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLowerInvariant();bytes=$f.Length}}
function Run([string]$Name,[string]$Executable,[string[]]$Arguments){
    $clock=[Diagnostics.Stopwatch]::StartNew();$lines=@(& $Executable @Arguments 2>&1|ForEach-Object ToString);$code=$LASTEXITCODE
    $log=Join-Path $output ($Name+'.txt');[IO.File]::WriteAllLines($log,[string[]]$lines)
    $commands.Add([ordered]@{name=$Name;executable=$Executable;arguments=$Arguments;exit_code=$code;seconds=$clock.Elapsed.TotalSeconds;log=(Record $log)})
    if($code -ne 0){throw "$Name exited $code; see $log"}
}
$roots=@('android/development-feedback','android/development-feedback-host','crates/rusty-quest-development-feedback-native','examples/development-feedback-native/src','tools/development_feedback')
$sourceFiles=@($roots|ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $repo $_) -File -Recurse | Where-Object {$_.Extension -in '.java','.rs','.toml','.py','.ps1' -and $_.FullName -notmatch '[\\/](target|\.local)[\\/]'} }|ForEach-Object FullName|Sort-Object -Unique)
$sourceFiles+=Join-Path $repo 'schemas/rusty.quest.hotload_scenario.v1.schema.json'
$sourceFiles+=$PSCommandPath
$before=@($sourceFiles|ForEach-Object {Record $_})
$nativeInputs=@(Record $NativeBridge;Record $NativeManifest)
if($nativeInputs[0].sha256 -cne $NativeBridgeSha256){throw 'Selected bridge bytes differ from trusted digest.'}
foreach($line in Get-Content -LiteralPath $NativeManifest){$row=$line.Split("`t",4);if($row.Count -ne 4){throw 'Invalid native manifest row'};$actual=Record $row[3];if($actual.sha256 -cne $row[2]){throw 'Native image digest differs'};$nativeInputs+=$actual}
$schema=Join-Path $repo 'schemas/rusty.quest.hotload_scenario.v1.schema.json'
$request=[ordered]@{schema='rusty.quest.hotload_command.v1';request_id='actual-test';method='native_activate';arguments=[ordered]@{generation='1';sha256=('a'*64);probe_result='13'}}
if(-not ($request|ConvertTo-Json -Depth 5|Test-Json -SchemaFile $schema -ErrorAction Stop)){throw 'Valid command schema rejected'}
$schemaChecks=1
foreach($damage in @('path','unknown','zero','negative-zero','uppercase')){
    $bad=$request|ConvertTo-Json -Depth 5|ConvertFrom-Json -AsHashtable
    switch($damage){path{$bad.arguments.path='/arbitrary'}unknown{$bad.method='arbitrary'}zero{$bad.arguments.generation='0'}negative-zero{$bad.arguments.probe_result='-0'}uppercase{$bad.arguments.sha256='A'*64}}
    if($bad|ConvertTo-Json -Depth 5|Test-Json -SchemaFile $schema -ErrorAction SilentlyContinue){throw "Damaged $damage command admitted"};$schemaChecks++
}
$shader=[ordered]@{schema='rusty.quest.hotload_command.v1';request_id='shader-test';method='native_shader_activate';arguments=[ordered]@{generation='2';vertex_sha256=('a'*64);fragment_sha256=('b'*64)}}
if(-not($shader|ConvertTo-Json -Depth 5|Test-Json -SchemaFile $schema -ErrorAction Stop)){throw 'Valid shader command rejected'};$schemaChecks++
$shader.arguments.Remove('fragment_sha256')
if($shader|ConvertTo-Json -Depth 5|Test-Json -SchemaFile $schema -ErrorAction SilentlyContinue){throw 'Incomplete shader pair admitted'};$schemaChecks++
$passed=$false
try{
    Run 'closed-operator' 'python' @((Join-Path $repo 'tools/development_feedback/test_quest_hotload.py'))
    $classes=Join-Path $output 'classes';New-Item -ItemType Directory -Path $classes | Out-Null
    $javaSources=@(Get-ChildItem -LiteralPath (Join-Path $repo 'android/development-feedback/src/main/java'),(Join-Path $repo 'android/development-feedback-host/src/main/java') -Filter '*.java' -Recurse -File|ForEach-Object FullName)
    $javaSources+=Join-Path $repo 'android/development-feedback/src/android/java/org/rusty/quest/feedback/NativeFeedbackBridge.java'
    $javaSources+=Join-Path $repo 'android/development-feedback/src/host/java/org/rusty/quest/feedback/HostUrlLoader.java'
    $javaSources+=Join-Path $repo 'android/development-feedback-host/tests/ResidentFeedbackHostTest.java'
    Run 'javac' (Join-Path $JavaHome 'bin/javac.exe') (@('--release','8','-d',$classes)+$javaSources)
    Run 'actual-java-jni' (Join-Path $JavaHome 'bin/java.exe') @('-cp',$classes,'ResidentFeedbackHostTest',$output,$NativeBridge,$NativeBridgeSha256,$NativeManifest)
    $oldManifest=$env:FEEDBACK_NATIVE_MANIFEST;$oldOutput=$env:FEEDBACK_TEST_OUTPUT
    try{$env:FEEDBACK_NATIVE_MANIFEST=(Resolve-Path -LiteralPath $NativeManifest).Path;$env:FEEDBACK_TEST_OUTPUT=Join-Path $output 'native-images';Run 'actual-native' 'cargo' @('test','--manifest-path',(Join-Path $repo 'crates/rusty-quest-development-feedback-native/Cargo.toml'),'--test','resident')}
    finally{$env:FEEDBACK_NATIVE_MANIFEST=$oldManifest;$env:FEEDBACK_TEST_OUTPUT=$oldOutput}
    $passed=$true
}finally{
    $after=@($sourceFiles|ForEach-Object {Record $_});$unchanged=($before|ConvertTo-Json -Depth 4 -Compress) -ceq ($after|ConvertTo-Json -Depth 4 -Compress)
    [ordered]@{schema='rusty.quest.hotload_shared_check.v1';passed=($passed -and $unchanged);schema_checks=$schemaChecks;sources_unchanged=$unchanged;sources=$before;native_inputs=$nativeInputs;commands=@($commands);scope='Shared actual CPU/JNI/Java code replacement and command shape; not Android app build, graphics adoption or device proof'}|ConvertTo-Json -Depth 9|Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8NoBOM
    if(-not $unchanged){throw 'Shared source changed during check'}
}
Write-Output (Join-Path $output 'result.json')
