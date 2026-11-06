# Actual Kotlin topology and packet types, independent of Android/SDK. Run only after compiler ownership is available.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string[]] $CompilerClasspath,
    [string] $Java = 'java',
    [string] $OutputDirectory = (Join-Path ([IO.Path]::GetTempPath()) ('spatial-topology-host-' + [Guid]::NewGuid().ToString('N')))
)
$ErrorActionPreference = 'Stop'
$resolvedJars = @($CompilerClasspath | ForEach-Object { (Resolve-Path -LiteralPath $_).Path })
$compilerCp = $resolvedJars -join [IO.Path]::PathSeparator
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null
$package = 'io/github/mesmerprism/rustyquest/spatial_worldspace_hotload'
$sourceRoot = Join-Path $PSScriptRoot 'app/src'
$sources = @(
    (Join-Path $sourceRoot "main/java/$package/AtlasPacket.kt"),
    (Join-Path $sourceRoot "main/java/$package/WorkloadTopology.kt"),
    (Join-Path $sourceRoot "test/java/$package/WorkloadTopologyHostTest.kt"),
    (Join-Path $sourceRoot "debug/java/$package/CohortFixturePacket.kt"),
    (Join-Path $sourceRoot "testDebug/java/$package/CohortFixtureHostTest.kt"),
    (Join-Path $sourceRoot "debug/java/$package/ParticleCohortFixturePacket.kt"),
    (Join-Path $sourceRoot "testDebug/java/$package/ParticleCohortFixtureHostTest.kt"),
    (Join-Path $sourceRoot "debug/java/$package/ParticleBoundsCalibration.kt"),
    (Join-Path $sourceRoot "testDebug/java/$package/ParticleBoundsCalibrationHostTest.kt")
)
$log = Join-Path $output 'topology-test-output.txt'
& $Java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 -no-stdlib -no-reflect -classpath $compilerCp -d $output @sources 2>&1 | Tee-Object -FilePath $log
if ($LASTEXITCODE -ne 0) { throw "Kotlin topology compilation failed ($LASTEXITCODE); see $log" }
& $Java -cp ($output + [IO.Path]::PathSeparator + $compilerCp) io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.WorkloadTopologyHostTest 2>&1 | Tee-Object -FilePath $log -Append
if ($LASTEXITCODE -ne 0) { throw "Topology host assertions failed ($LASTEXITCODE); see $log" }
& $Java -cp ($output + [IO.Path]::PathSeparator + $compilerCp) io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.CohortFixtureHostTest 2>&1 | Tee-Object -FilePath $log -Append
if ($LASTEXITCODE -ne 0) { throw "Cohort fixture byte assertions failed ($LASTEXITCODE); see $log" }
& $Java -cp ($output + [IO.Path]::PathSeparator + $compilerCp) io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.ParticleCohortFixtureHostTest 2>&1 | Tee-Object -FilePath $log -Append
if ($LASTEXITCODE -ne 0) { throw "Particle cohort fixture byte assertions failed ($LASTEXITCODE); see $log" }
& $Java -cp ($output + [IO.Path]::PathSeparator + $compilerCp) io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.ParticleBoundsCalibrationHostTest 2>&1 | Tee-Object -FilePath $log -Append
if ($LASTEXITCODE -ne 0) { throw "Particle bounds calibration assertions failed ($LASTEXITCODE); see $log" }
Write-Output "Retained topology result: $log"
