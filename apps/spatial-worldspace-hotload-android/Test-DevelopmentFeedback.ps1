[CmdletBinding()]
param([Parameter(Mandatory)][string[]]$CompilerClasspath,[Parameter(Mandatory)][string]$Java,
    [Parameter(Mandatory)][string]$Python,[Parameter(Mandatory)][string]$JsonJar,
    [string]$OutputDirectory=(Join-Path $PSScriptRoot ('.local/development-'+[guid]::NewGuid().ToString('N'))))
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputDirectory){throw 'Fresh retained test output required.'}
[void][IO.Directory]::CreateDirectory($OutputDirectory)
$output=(Resolve-Path -LiteralPath $OutputDirectory).Path
$package='io/github/mesmerprism/rustyquest/spatial_worldspace_hotload'
$main=Join-Path $PSScriptRoot "app/src/main/java/$package"
$tests=Join-Path $PSScriptRoot "app/src/test/java/$package"
$sources=@('AtlasPacket.kt','AtlasDiagnosticProtocol.kt','WorkloadTopology.kt','DevelopmentSceneManifest.kt','CpuFeedbackProjection.kt','FeedbackObservationStore.kt','FrameworkFilesRoot.kt')|ForEach-Object{Join-Path $main $_}
$sources+=@('DevelopmentSceneHostTest.kt','WorkloadContractHostTest.kt','AtlasDiagnosticHostTest.kt','WorkloadTopologyHostTest.kt','FeedbackObservationHostTest.kt','FrameworkFilesHostTest.kt','OperatorGeometryHostTest.kt')|ForEach-Object{Join-Path $tests $_}
$sources+=Join-Path $PSScriptRoot "app/src/operatorDebug/java/$package/SdkOperatorGeometry.kt"
$repoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$sharedSources=@(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'android/development-feedback/src/main/java'),(Join-Path $repoRoot 'android/development-feedback-host/src/main/java') -Recurse -File -Filter '*.java'|ForEach-Object FullName)
$sharedSources+=Join-Path $repoRoot 'android/development-feedback/src/android/java/org/rusty/quest/feedback/NativeFeedbackBridge.java'
$sharedSources+=Join-Path $repoRoot 'android/development-feedback/src/host/java/org/rusty/quest/feedback/HostUrlLoader.java'
$javac=Join-Path (Split-Path $Java) 'javac.exe'
$jars=@($CompilerClasspath|ForEach-Object{(Resolve-Path -LiteralPath $_).Path})+@((Resolve-Path -LiteralPath $JsonJar).Path)
$tools=@((Resolve-Path -LiteralPath $Java).Path,(Resolve-Path -LiteralPath $Python).Path,(Resolve-Path -LiteralPath $javac).Path)+$jars
$inputs=$sources+$sharedSources+@($PSCommandPath,(Join-Path $PSScriptRoot 'Prepare-DevelopmentFeedbackInputs.py'),(Join-Path $PSScriptRoot 'Test-DevelopmentFeedbackInputs.py'),(Join-Path $PSScriptRoot 'Test-DevelopmentShader.py'),(Join-Path $PSScriptRoot 'app/src/shaders/workload.vert'))
function Bind([string[]]$Paths){@($Paths|Sort-Object -Unique|ForEach-Object{[ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant()}})}
$before=Bind ($inputs+$tools);$steps=[Collections.Generic.List[object]]::new();$status='failed'
function Run([string]$Name,[string]$Tool,[string[]]$Arguments){
    $clock=[Diagnostics.Stopwatch]::StartNew();$lines=@(& $Tool @Arguments 2>&1|ForEach-Object ToString);$code=$LASTEXITCODE
    $lines|Set-Content -LiteralPath (Join-Path $output ($Name+'.txt')) -Encoding utf8NoBOM
    $steps.Add([ordered]@{name=$Name;executable=$Tool;arguments=$Arguments;exit_code=$code;elapsed_ms=$clock.Elapsed.TotalMilliseconds})
    $lines|Write-Output;if($code-ne0){throw "Host step failed: $Name ($code)"}
}
try{
    Run 'producer-regression' $Python @((Join-Path $PSScriptRoot 'Test-DevelopmentFeedbackInputs.py'))
    $packet=Join-Path $output 'packet'
    Run 'public-inputs' $Python @((Join-Path $PSScriptRoot 'Prepare-DevelopmentFeedbackInputs.py'),'--output',$packet)
    $classes=Join-Path $output 'classes';[void][IO.Directory]::CreateDirectory($classes)
    $fixture=Join-Path $output 'framework-files-fixture';$physical=Join-Path $fixture 'physical'
    [void][IO.Directory]::CreateDirectory($physical)
    $alias=Join-Path $fixture 'framework-alias';$aliasKind='SymbolicLink'
    try{New-Item -ItemType SymbolicLink -Path $alias -Target $physical -ErrorAction Stop|Out-Null}
    catch{if(Test-Path -LiteralPath $alias){throw};$aliasKind='Junction';New-Item -ItemType Junction -Path $alias -Target $physical -ErrorAction Stop|Out-Null}
    [ordered]@{kind=$aliasKind;physical=$physical;alias=$alias}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $output 'actual-alias.json') -Encoding utf8NoBOM
    Run 'actual-shared-java' $javac (@('--release','8','-d',$classes)+$sharedSources)
    $cp=(@($classes)+$jars)-join[IO.Path]::PathSeparator
    Run 'kotlin-compile' $Java (@('-cp',$cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-jvm-target','17','-no-stdlib','-no-reflect','-classpath',$cp,'-d',$classes)+$sources)
    $runtime=$classes+[IO.Path]::PathSeparator+$cp
    foreach($test in @('WorkloadContractHostTest','AtlasDiagnosticHostTest','WorkloadTopologyHostTest','DevelopmentSceneHostTest','FeedbackObservationHostTest','FrameworkFilesHostTest','OperatorGeometryHostTest')){
        Run $test $Java @('-cp',$runtime,('io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.'+$test),$packet)
    }
    Run 'actual-atlas-shader-layout' $Python @((Join-Path $PSScriptRoot 'Test-DevelopmentShader.py'),'--header',(Join-Path $output 'development-atlas-header.json'))
    $status='passed'
}finally{
    $after=Bind ($inputs+$tools);$match=($before|ConvertTo-Json -Depth 4 -Compress)-ceq($after|ConvertTo-Json -Depth 4 -Compress)
    if(-not$match){$status='failed'}
    [ordered]@{schema='rusty.quest.sdk_development_feedback.host_check.v1';status=$status;steps=@($steps);inputs_before=$before;inputs_after=$after;inputs_match=$match;device_tested=$false;sdk_pixels_proven=$false}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8NoBOM
    if(-not$match){throw 'Host test inputs drifted.'}
}
Write-Output "Retained development feedback checks: $output"
