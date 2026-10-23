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
$sources=@('AtlasPacket.kt','AtlasDiagnosticProtocol.kt','WorkloadTopology.kt','DevelopmentSceneManifest.kt','CpuFeedbackProjection.kt','FeedbackObservationStore.kt')|ForEach-Object{Join-Path $main $_}
$sources+=@('DevelopmentSceneHostTest.kt','WorkloadContractHostTest.kt','AtlasDiagnosticHostTest.kt','WorkloadTopologyHostTest.kt','FeedbackObservationHostTest.kt')|ForEach-Object{Join-Path $tests $_}
$jars=@($CompilerClasspath|ForEach-Object{(Resolve-Path -LiteralPath $_).Path})+@((Resolve-Path -LiteralPath $JsonJar).Path)
$tools=@((Resolve-Path -LiteralPath $Java).Path,(Resolve-Path -LiteralPath $Python).Path)+$jars
$inputs=$sources+@($PSCommandPath,(Join-Path $PSScriptRoot 'Prepare-DevelopmentFeedbackInputs.py'),(Join-Path $PSScriptRoot 'Test-DevelopmentFeedbackInputs.py'))
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
    $cp=$jars-join[IO.Path]::PathSeparator
    Run 'kotlin-compile' $Java (@('-cp',$cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-jvm-target','17','-no-stdlib','-no-reflect','-classpath',$cp,'-d',$classes)+$sources)
    $runtime=$classes+[IO.Path]::PathSeparator+$cp
    foreach($test in @('WorkloadContractHostTest','AtlasDiagnosticHostTest','WorkloadTopologyHostTest','DevelopmentSceneHostTest','FeedbackObservationHostTest')){
        Run $test $Java @('-cp',$runtime,('io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.'+$test),$packet)
    }
    $status='passed'
}finally{
    $after=Bind ($inputs+$tools);$match=($before|ConvertTo-Json -Depth 4 -Compress)-ceq($after|ConvertTo-Json -Depth 4 -Compress)
    if(-not$match){$status='failed'}
    [ordered]@{schema='rusty.quest.sdk_development_feedback.host_check.v1';status=$status;steps=@($steps);inputs_before=$before;inputs_after=$after;inputs_match=$match;device_tested=$false;sdk_pixels_proven=$false}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8NoBOM
    if(-not$match){throw 'Host test inputs drifted.'}
}
Write-Output "Retained development feedback checks: $output"
