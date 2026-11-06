# Run the actual dependency-free Kotlin sources; no Gradle/Android/device build.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string[]] $CompilerClasspath,
    [string] $Java = 'java',
    [string] $OutputDirectory = (Join-Path ([IO.Path]::GetTempPath()) ('spatial-atlas-host-' + [Guid]::NewGuid().ToString('N')))
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
    (Join-Path $sourceRoot "main/java/$package/AtlasDiagnosticProtocol.kt"),
    (Join-Path $sourceRoot "test/java/$package/WorkloadContractHostTest.kt"),
    (Join-Path $sourceRoot "test/java/$package/AtlasDiagnosticHostTest.kt")
)
$log = Join-Path $output 'host-test-output.txt'
"Compiler: K2JVMCompiler; JVM target17; explicit classpath; actual four Kotlin source files" | Set-Content -LiteralPath $log
& $Java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 -no-stdlib -no-reflect -classpath $compilerCp -d $output @sources 2>&1 | Tee-Object -FilePath $log -Append
if ($LASTEXITCODE -ne 0) { throw "Kotlin host compilation failed ($LASTEXITCODE); see $log" }
& $Java -cp ($output + [IO.Path]::PathSeparator + $compilerCp) io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.WorkloadContractHostTest 2>&1 | Tee-Object -FilePath $log -Append
if ($LASTEXITCODE -ne 0) { throw "Atlas host assertions failed ($LASTEXITCODE); see $log" }
& $Java -cp ($output + [IO.Path]::PathSeparator + $compilerCp) io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.AtlasDiagnosticHostTest 2>&1 | Tee-Object -FilePath $log -Append
if ($LASTEXITCODE -ne 0) { throw "Atlas diagnostic assertions failed ($LASTEXITCODE); see $log" }
Write-Output "Retained host result: $log"
