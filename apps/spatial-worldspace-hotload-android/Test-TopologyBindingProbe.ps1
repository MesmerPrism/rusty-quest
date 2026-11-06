# Compile the actual scene owner with isolated SDK call recorders. Requires a coordinated compiler window.
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
$sources=@('AtlasPacket.kt','WorkloadTopology.kt','WorkloadSceneTopology.kt'|ForEach-Object{Join-Path $PSScriptRoot "app/src/main/java/$package/$_"})
$sources+=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'app/src/hostTest/topology-binding') -Filter '*.kt' -File|ForEach-Object{$_.FullName})
New-Item -ItemType Directory -Path $output|Out-Null
$log=Join-Path $output 'host-test-output.txt'
& $Java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 -no-stdlib -no-reflect -classpath $compilerCp -d $output @sources 2>&1|Tee-Object -FilePath $log
if($LASTEXITCODE -ne 0){throw "Topology binding host compilation failed ($LASTEXITCODE)"}
& $Java -cp ($output+[IO.Path]::PathSeparator+$compilerCp) io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.WorkloadSceneTopologyHostTest 2>&1|Tee-Object -FilePath $log -Append
if($LASTEXITCODE -ne 0){throw "Topology binding host behavior failed ($LASTEXITCODE)"}
Write-Output "Retained scene-owner host results: $log"
