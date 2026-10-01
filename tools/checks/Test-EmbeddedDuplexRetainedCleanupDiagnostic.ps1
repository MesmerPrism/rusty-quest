param(
    [Parameter(Mandatory)][string]$RepoRoot,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$AndroidApi33Jar,
    [Parameter(Mandatory)][string]$AndroidApi34Jar,
    [Parameter(Mandatory)][string]$HostJsonJar,
    [Parameter(Mandatory)][string[]]$AppDependencyClasspath,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'output directory must be absent' }
$null = New-Item -ItemType Directory -Path $output
$platform = Join-Path $repo 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexPlatform.java'
$fixture = Join-Path $PSScriptRoot 'fixtures/retained-cleanup-diagnostic/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/RetainedCleanupDiagnosticCase.java'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$java = Join-Path $JavaHome 'bin/java.exe'
$paths = @($platform, $fixture, $PSCommandPath, $javac, $java, $AndroidApi33Jar, $AndroidApi34Jar, $HostJsonJar)
foreach ($entry in $AppDependencyClasspath) {
    if (Test-Path -LiteralPath $entry -PathType Container) {
        $paths += @(Get-ChildItem -LiteralPath $entry -File -Recurse | Sort-Object FullName | ForEach-Object FullName)
    } elseif (Test-Path -LiteralPath $entry -PathType Leaf) { $paths += $entry }
    else { throw 'missing actual compiled app dependency closure' }
}
function Inventory {
    @($paths | ForEach-Object { [ordered]@{ path=$_; size_bytes=(Get-Item -LiteralPath $_).Length; sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() } })
}
$before = Inventory
$dependencies = @($AppDependencyClasspath) + $HostJsonJar
foreach ($api in @(33,34)) {
    $jar = if ($api -eq 33) { $AndroidApi33Jar } else { $AndroidApi34Jar }
    $classpath = (@($jar) + $dependencies) -join [IO.Path]::PathSeparator
    & $javac --release 8 -encoding UTF-8 '-Xlint:all' -Werror -cp $classpath -d (Join-Path $output "api$api") $platform 1> (Join-Path $output "api$api.stdout") 2> (Join-Path $output "api$api.stderr")
    if ($LASTEXITCODE -ne 0) { throw "actual Platform API$api compilation failed" }
}
$classpath = (@((Join-Path $output 'api34'), $HostJsonJar) + $AppDependencyClasspath + $AndroidApi34Jar) -join [IO.Path]::PathSeparator
& $javac --release 8 -encoding UTF-8 '-Xlint:all' -Werror -cp $classpath -d (Join-Path $output 'host') $fixture 1> (Join-Path $output 'fixture.stdout') 2> (Join-Path $output 'fixture.stderr')
if ($LASTEXITCODE -ne 0) { throw 'production diagnostic fixture compilation failed' }
& $java -cp ((Join-Path $output 'host') + [IO.Path]::PathSeparator + $classpath) io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.RetainedCleanupDiagnosticCase 1> (Join-Path $output 'host.stdout') 2> (Join-Path $output 'host.stderr')
if ($LASTEXITCODE -ne 0) { throw 'production diagnostic fixture failed' }
$after = Inventory
if (($before | ConvertTo-Json -Depth 5 -Compress) -cne ($after | ConvertTo-Json -Depth 5 -Compress)) { throw 'source/tool/dependency closure changed during checks' }
$result = [ordered]@{
    status='passed'; scope='actual changed Platform API33/API34 compile plus production diagnostic holder/classifiers'; checks=18
    source_tool_dependency_bindings=$before
    limits='Catch routing is compiled and reviewed; this pure fixture does not execute JNI, provider callbacks, physical cleanup or signed authority.'
}
[IO.File]::WriteAllText((Join-Path $output 'result.json'), ($result | ConvertTo-Json -Depth 7), [Text.UTF8Encoding]::new($false))
Write-Output 'PASS retained cleanup diagnostic: API33/API34 Werror and 18 production diagnostic checks'
