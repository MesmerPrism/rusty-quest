param(
    [Parameter(Mandatory)][string]$RepoRoot,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$CompiledOwnerClassPath,
    [Parameter(Mandatory)][string]$HostJsonJar,
    [Parameter(Mandatory)][string]$KotlinCompilerClassPath,
    [string]$KotlinFriendPaths = '',
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'output directory must be absent' }
$null = New-Item -ItemType Directory -Path $output
$suffix = if ($IsWindows) { '.exe' } else { '' }
$java = Join-Path $JavaHome "bin/java$suffix"
$javac = Join-Path $JavaHome "bin/javac$suffix"
$fixture = Join-Path $PSScriptRoot 'fixtures/embedded-duplex-concurrent-caller'
$app = Join-Path $repo 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel'
$media = Join-Path $repo 'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media'
$sources = @()
foreach ($name in @('EmbeddedDuplexReceiver', 'EmbeddedDuplexPlatform')) {
    $sources += Join-Path $app "embedded_duplex/$name.java"
}
foreach ($name in @('PackagedAndroidMediaOwnerRegistry', 'MediaOwnerAction', 'MediaProductBinding', 'MediaProviderReadback', 'MediaOwnerProvider', 'CancellationHandle', 'MediaRuntimeSnapshot', 'AndroidMediaOwnerRegistry')) {
    $sources += Join-Path $media "$name.java"
}
$stubs = @('Looper.java', 'SystemClock.java', 'EmbeddedDuplexNative.java', 'OwnPackedPoolNative.java') | ForEach-Object { Join-Path $fixture $_ }
$classes = Join-Path $output 'java'
$kotlin = Join-Path $output 'kotlin'
$null = New-Item -ItemType Directory -Path $classes, $kotlin
$separator = [IO.Path]::PathSeparator
# The working host JSON implementation precedes Android API stubs.
$dependencies = "$HostJsonJar$separator$CompiledOwnerClassPath"
& $javac --release 8 '-Xlint:all' -Werror -cp $dependencies -d $classes @sources @stubs 1> (Join-Path $output 'owner-java.stdout') 2> (Join-Path $output 'owner-java.stderr')
if ($LASTEXITCODE -ne 0) { throw 'actual Receiver/Registry compilation failed' }
$kotlinSources = @((Join-Path $app 'SpatialVideoSourceRoutingCoordinator.kt'), (Join-Path $app 'embedded_duplex/EmbeddedDuplexDisplayCoordinator.kt'))
$compilerArguments = @('-no-stdlib', '-no-reflect', '-jvm-target', '1.8', '-classpath', "$classes$separator$dependencies")
if ($KotlinFriendPaths) { $compilerArguments += "-Xfriend-paths=$KotlinFriendPaths" }
& $java -cp $KotlinCompilerClassPath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @compilerArguments -d $kotlin @kotlinSources 1> (Join-Path $output 'owner-kotlin.stdout') 2> (Join-Path $output 'owner-kotlin.stderr')
if ($LASTEXITCODE -ne 0) { throw 'actual Display/Router compilation failed' }
$classPath = "$kotlin$separator$classes$separator$dependencies"
& $javac --release 8 '-Xlint:all' -Werror -cp $classPath -d $classes (Join-Path $fixture 'ConcurrentCallerRegression.java') 1> (Join-Path $output 'fixture-compile.stdout') 2> (Join-Path $output 'fixture-compile.stderr')
if ($LASTEXITCODE -ne 0) { throw 'complete caller fixture compilation failed' }
& $java -cp $classPath io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.ConcurrentCallerRegression 1> (Join-Path $output 'test.stdout') 2> (Join-Path $output 'test.stderr')
if ($LASTEXITCODE -ne 0) { throw 'complete concurrent Peer caller regression failed' }
$hashes = @($sources + $kotlinSources | ForEach-Object {
    [ordered]@{path=[IO.Path]::GetRelativePath($repo, $_).Replace('\', '/'); sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}
})
[ordered]@{
    status='passed'; scope='actual compiled Kotlin Display/Router -> Java Receiver/Registry; happy plus six rejection cases, exclusive guard and checked failure stage'
    sources=$hashes
    platform_scope='mock Looper/clock, native actor proof/quiescence, retained Own phase/identity and injected physical pool freshness; actual CaptureOwner and compositor deadline freshness methods'
    physical_claim='none; no camera, JNI, GL, decoder, network or device effects; timeout classification is a class case, not a timed wait'
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
