[CmdletBinding()]
param([Parameter(Mandatory)][string]$JavaHome,[string]$AndroidJar='',[string]$D8='',[string]$OutputRoot='',
    [string]$JsonJar='',[string]$FeatureLock='',[string]$RuntimeProfile='')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$appRoot=Split-Path $PSScriptRoot -Parent
$repoRoot=Split-Path (Split-Path $appRoot -Parent) -Parent
$module=Join-Path $repoRoot 'android/development-feedback'
$suffix=if($IsWindows){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix)
$java=Join-Path $JavaHome ('bin/java'+$suffix)
if(-not(Test-Path -LiteralPath $javac -PathType Leaf)){throw 'Explicit JavaHome must contain javac.'}
if((& $javac -version 2>&1|Out-String)-notmatch '^javac 17\.'){throw 'JDK17 required.'}
$activationArguments=@(@($JsonJar,$FeatureLock,$RuntimeProfile)|Where-Object {$_})
if($activationArguments.Count-ne0-and($activationArguments.Count-ne3-or-not$AndroidJar)){throw 'Parser check requires JsonJar, FeatureLock, RuntimeProfile and AndroidJar together.'}
if($JsonJar){
    $JsonJar=(Resolve-Path -LiteralPath $JsonJar).Path
    $FeatureLock=(Resolve-Path -LiteralPath $FeatureLock).Path
    $RuntimeProfile=(Resolve-Path -LiteralPath $RuntimeProfile).Path
}
if(-not$OutputRoot){$OutputRoot=Join-Path $appRoot ('.local/'+[guid]::NewGuid().ToString('N'))}
if(Test-Path -LiteralPath $OutputRoot){throw 'OutputRoot must be create-new.'}
[void][IO.Directory]::CreateDirectory($OutputRoot)
$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
$steps=[Collections.Generic.List[object]]::new()
function Get-SourceRecords {
    $paths=@((Join-Path $repoRoot 'config/gradle-9.4.1-tool.json'))+@(Get-ChildItem -LiteralPath $appRoot -Recurse -File|Where-Object {
        $relative=[IO.Path]::GetRelativePath($appRoot,$_.FullName).Replace('\','/')
        $relative-notmatch '(^|/)(\.local|\.gradle|build)/'
    }|ForEach-Object FullName)
    foreach($relative in @('src/main/java','src/android/java','src/host/java')) {
        $paths+=@(Get-ChildItem -LiteralPath (Join-Path $module $relative) -Recurse -Filter '*.java' -File|ForEach-Object FullName)
    }
    @($paths|Sort-Object -Unique|ForEach-Object{[ordered]@{path=[IO.Path]::GetRelativePath($repoRoot,$_).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})
}
$sourcesBefore=@(Get-SourceRecords)
$toolPaths=@($javac,$java)
if($AndroidJar){$toolPaths+=(Resolve-Path -LiteralPath $AndroidJar).Path}
if($JsonJar){$toolPaths+=$JsonJar}
if($D8){$toolPaths+=(Resolve-Path -LiteralPath $D8).Path;$toolPaths+=Join-Path (Split-Path (Resolve-Path -LiteralPath $D8).Path -Parent) 'lib/d8.jar'}
function Get-ToolRecords { @($toolPaths|Sort-Object -Unique|ForEach-Object{[ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}}) }
$toolsBefore=@(Get-ToolRecords)
function Get-ActivationRecords {
    if($JsonJar){foreach($path in @($FeatureLock,$RuntimeProfile)){[ordered]@{path=$path;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}}}
}
$activationBefore=@(Get-ActivationRecords)
$parserPassed=$false
function Invoke-TestStep([string]$Name,[string]$Executable,[string[]]$Arguments){
    $timer=[Diagnostics.Stopwatch]::StartNew()
    $lines=@(& $Executable @Arguments 2>&1|ForEach-Object ToString)
    $code=$LASTEXITCODE;$timer.Stop()
    [IO.File]::WriteAllLines((Join-Path $OutputRoot ($Name+'.txt')),[string[]]$lines,[Text.UTF8Encoding]::new($false))
    $steps.Add([ordered]@{name=$Name;exit_code=$code;elapsed_ms=$timer.Elapsed.TotalMilliseconds})
    $lines|Write-Output
    if($code-ne0){throw "$Name failed with $code; evidence: $OutputRoot"}
}
$status='failed'
try{
    [xml]$main=Get-Content -LiteralPath (Join-Path $appRoot 'app/src/main/AndroidManifest.xml') -Raw
    [xml]$debug=Get-Content -LiteralPath (Join-Path $appRoot 'app/src/debug/AndroidManifest.xml') -Raw
    if($main.SelectNodes('//provider|//uses-permission|//uses-feature').Count-ne0){throw 'Main manifest must remain free of debug controls and device permissions/features.'}
    $providers=@($debug.SelectNodes('//provider'))
    if($providers.Count-ne1-or$providers[0].GetAttribute('permission','http://schemas.android.com/apk/res/android')-cne'android.permission.DUMP'-or$providers[0].GetAttribute('authorities','http://schemas.android.com/apk/res/android')-cne'${applicationId}.feedback-operator'){throw 'Debug provider must have package-derived authority and DUMP permission.'}
    if(@(Get-ChildItem -LiteralPath (Join-Path $appRoot 'app/src/main') -Filter '*.java' -Recurse -File).Count-ne0){throw 'Debug controller/operator classes may not enter main sources.'}
    $steps.Add([ordered]@{name='source-manifest-boundary';exit_code=0;elapsed_ms=$null})
    $common=@(Get-ChildItem -LiteralPath (Join-Path $module 'src/main/java') -Filter '*.java' -Recurse -File|Sort-Object FullName|ForEach-Object FullName)
    $controller=Join-Path $appRoot 'app/src/debug/java/io/github/mesmerprism/rustyquest/development_feedback/FeedbackController.java'
    $hostClasses=Join-Path $OutputRoot 'host-classes';[void][IO.Directory]::CreateDirectory($hostClasses)
    $hostSources=$common+@((Join-Path $module 'src/host/java/org/rusty/quest/feedback/HostUrlLoader.java'),$controller,(Join-Path $PSScriptRoot 'FeedbackControllerTest.java'),(Join-Path $PSScriptRoot 'FeedbackDexInventory.java'))
    Invoke-TestStep 'host-compile' $javac (@('--release','8','-encoding','UTF-8','-Xlint:all','-Werror','-d',$hostClasses)+$hostSources)
    Invoke-TestStep 'controller-behavior' $java @('-cp',$hostClasses,'FeedbackControllerTest',$OutputRoot)
    if($JsonJar){
        $parserClasses=Join-Path $OutputRoot 'parser-classes';[void][IO.Directory]::CreateDirectory($parserClasses)
        $parserDependencies=$JsonJar+[IO.Path]::PathSeparator+$AndroidJar
        $parserSources=@((Join-Path $appRoot 'app/src/debug/java/io/github/mesmerprism/rustyquest/development_feedback/AssetActivation.java'),(Join-Path $PSScriptRoot 'AssetActivationTest.java'))
        Invoke-TestStep 'activation-parser-compile' $javac (@('--release','8','-encoding','UTF-8','-Xlint:all','-Werror','-classpath',$parserDependencies,'-d',$parserClasses)+$parserSources)
        Invoke-TestStep 'activation-parser-real-json' $java @('-cp',($parserClasses+[IO.Path]::PathSeparator+$parserDependencies),'io.github.mesmerprism.rustyquest.development_feedback.AssetActivationTest',$FeatureLock,$RuntimeProfile)
        $parserPassed=$true
    }
    if($AndroidJar){
        $AndroidJar=(Resolve-Path -LiteralPath $AndroidJar).Path
        $androidClasses=Join-Path $OutputRoot 'android-classes';[void][IO.Directory]::CreateDirectory($androidClasses)
        $androidSources=$common+@(Get-ChildItem -LiteralPath (Join-Path $module 'src/android/java') -Filter '*.java' -Recurse -File|Sort-Object FullName|ForEach-Object FullName)+@(Get-ChildItem -LiteralPath (Join-Path $appRoot 'app/src/debug/java') -Filter '*.java' -Recurse -File|Sort-Object FullName|ForEach-Object FullName)
        Invoke-TestStep 'android-sdk34-compile' $javac (@('--release','8','-encoding','UTF-8','-Xlint:all,-deprecation','-Werror','-classpath',$AndroidJar,'-d',$androidClasses)+$androidSources)
        if($D8){
            $d8Jar=Join-Path (Split-Path (Resolve-Path -LiteralPath $D8).Path -Parent) 'lib/d8.jar'
            $dex=Join-Path $OutputRoot 'dex';[void][IO.Directory]::CreateDirectory($dex)
            $classInputs=@(Get-ChildItem -LiteralPath $androidClasses -Filter '*.class' -Recurse -File|Sort-Object FullName|ForEach-Object FullName)
            Invoke-TestStep 'android-d8-source-check' $java (@('-cp',$d8Jar,'com.android.tools.r8.D8','--lib',$AndroidJar,'--min-api','26','--output',$dex)+$classInputs)
            Invoke-TestStep 'android-dex-definitions' $java @('-cp',$hostClasses,'org.rusty.quest.feedback.FeedbackDexInventory',(Join-Path $dex 'classes.dex'))
        }
    }elseif($D8){throw 'D8 source check requires AndroidJar.'}
    $status='passed'
}finally{
    $sourcesAfter=@(Get-SourceRecords);$toolsAfter=@(Get-ToolRecords);$activationAfter=@(Get-ActivationRecords)
    $inputsMatch=(ConvertTo-Json -InputObject $sourcesBefore -Compress -Depth 4)-ceq(ConvertTo-Json -InputObject $sourcesAfter -Compress -Depth 4)
    $toolsMatch=(ConvertTo-Json -InputObject $toolsBefore -Compress -Depth 4)-ceq(ConvertTo-Json -InputObject $toolsAfter -Compress -Depth 4)
    $activationMatch=(ConvertTo-Json -InputObject $activationBefore -Compress -Depth 4)-ceq(ConvertTo-Json -InputObject $activationAfter -Compress -Depth 4)
    if(-not$inputsMatch-or-not$toolsMatch-or-not$activationMatch){$status='failed'}
    [ordered]@{schema='rusty.quest.feedback_application.source_test.v1';result=$status;steps=@($steps);source_inputs_before=$sourcesBefore;source_inputs_after=$sourcesAfter;source_inputs_match=$inputsMatch;selected_tools_before=$toolsBefore;selected_tools_after=$toolsAfter;selected_tools_match=$toolsMatch;activation_parser_requested=[bool]$JsonJar;activation_parser_passed=$parserPassed;activation_inputs_before=$activationBefore;activation_inputs_after=$activationAfter;activation_inputs_match=$activationMatch;limitations=@('Host controller and Android source/DEX checks only','Optional parser check executes explicit upstream org.json, not Android JSONObject or AssetManager','No AGP assembly, APK signing, ART or Quest execution')}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $OutputRoot 'result.json') -Encoding utf8
    if(-not$inputsMatch-or-not$toolsMatch-or-not$activationMatch){throw 'Source, selected tool or activation input changed during source qualification.'}
}
