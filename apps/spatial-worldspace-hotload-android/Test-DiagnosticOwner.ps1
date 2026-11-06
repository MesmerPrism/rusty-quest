# Compile actual debug/release facades with isolated SDK call recorders; coordinate compiler ownership first.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string[]]$CompilerClasspath,
    [Parameter(Mandatory)][string]$Java,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$output=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $output){throw 'Fresh host test output directory required'}
$compilerCp=(@($CompilerClasspath|ForEach-Object{(Resolve-Path -LiteralPath $_).Path}) -join [IO.Path]::PathSeparator)
$package='io/github/mesmerprism/rustyquest/spatial_worldspace_hotload'
$common=@(Join-Path $PSScriptRoot "app/src/main/java/$package/ProbeControlTarget.kt")
$common+=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'app/src/hostTest/diagnostic-owner') -Filter '*.kt' -File|ForEach-Object{$_.FullName})
New-Item -ItemType Directory -Path $output|Out-Null
foreach($variant in @('debug','release')){
    $destination=Join-Path $output $variant;New-Item -ItemType Directory -Path $destination|Out-Null
    $sources=$common+@(Join-Path $PSScriptRoot "app/src/$variant/java/$package/ProbeDebugFeatures.kt")
    $log=Join-Path $output ($variant+'-output.txt');$start=[DateTimeOffset]::UtcNow
    & $Java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 -no-stdlib -no-reflect -nowarn -classpath $compilerCp -d $destination @sources 2>&1|Tee-Object -FilePath $log
    $compileExit=$LASTEXITCODE
    if($compileExit -ne 0){
        @{variant=$variant;compile_exit=$compileExit;behavior_exit=$null}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $output ($variant+'-result.json'))
        throw "Diagnostic owner $variant compilation failed ($compileExit)"
    }
    & $Java -cp ($destination+[IO.Path]::PathSeparator+$compilerCp) io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.DiagnosticOwnerHostTest $variant 2>&1|Tee-Object -FilePath $log -Append
    $behaviorExit=$LASTEXITCODE
    [ordered]@{variant=$variant;compile_exit=$compileExit;behavior_exit=$behaviorExit;started_at_utc=$start.ToString('o');finished_at_utc=[DateTimeOffset]::UtcNow.ToString('o');sources=@($sources|ForEach-Object{@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $output ($variant+'-result.json'))
    if($behaviorExit -ne 0){throw "Diagnostic owner $variant behavior failed ($behaviorExit)"}
}
Write-Output "Actual debug/release diagnostic ownership host results: $output"
