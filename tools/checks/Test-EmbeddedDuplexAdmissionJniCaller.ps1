param(
    [Parameter(Mandatory)][string]$RepoRoot,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$CompiledOwnerClassPath,
    [Parameter(Mandatory)][string]$HostJsonJar,
    [Parameter(Mandatory)][string]$KotlinCompilerClassPath,
    [string]$KotlinFriendPaths = '',
    [Parameter(Mandatory)][string]$PythonExecutable,
    [Parameter(Mandatory)][string]$CargoExecutable,
    [Parameter(Mandatory)][string]$NativeTargetDirectory,
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
$jniFixture = Join-Path $PSScriptRoot 'fixtures/embedded-duplex-admission-jni'
$target = [IO.Path]::GetFullPath($NativeTargetDirectory)
if (Test-Path -LiteralPath $target) { throw 'native fixture target directory must be absent and run-owned' }
$nativeOutput = Join-Path $output 'native'
& $PythonExecutable (Join-Path $jniFixture 'build_actual_admission_jni.py') --repo $repo --output $nativeOutput --cargo $CargoExecutable --target $target 1> (Join-Path $output 'native-build.stdout') 2> (Join-Path $output 'native-build.stderr')
if ($LASTEXITCODE -ne 0) { throw 'actual native/JNI fixture compilation failed' }
$library = [IO.File]::ReadAllText((Join-Path $nativeOutput 'library-path.txt'))

$app = Join-Path $repo 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel'
$media = Join-Path $repo 'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media'
$sources = @()
foreach ($name in @('EmbeddedDuplexReceiver', 'EmbeddedDuplexPlatform')) {
    $sources += Join-Path $app "embedded_duplex/$name.java"
}
foreach ($name in @('PackagedAndroidMediaOwnerRegistry', 'MediaOwnerAction', 'MediaProductBinding', 'MediaProviderReadback', 'MediaOwnerProvider', 'CancellationHandle', 'MediaRuntimeSnapshot', 'AndroidMediaOwnerRegistry')) {
    $sources += Join-Path $media "$name.java"
}
$stubs = @('Looper.java', 'SystemClock.java', 'EmbeddedDuplexNative.java') | ForEach-Object { Join-Path $fixture $_ }
$stubs += Join-Path $jniFixture 'OwnPackedPoolNative.java'
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
& $java -cp $KotlinCompilerClassPath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @compilerArguments -d $kotlin @kotlinSources (Join-Path $nativeOutput 'DefaultLocalRetirementFixture.kt') 1> (Join-Path $output 'owner-kotlin.stdout') 2> (Join-Path $output 'owner-kotlin.stderr')
if ($LASTEXITCODE -ne 0) { throw 'actual Display/Router compilation failed' }
$classPath = "$kotlin$separator$classes$separator$dependencies"
& $javac --release 8 '-Xlint:all' -Werror -cp $classPath -d $classes (Join-Path $fixture 'ConcurrentCallerRegression.java') (Join-Path $jniFixture 'AdmissionJniRegression.java') (Join-Path $jniFixture 'RetirementJniCaller.java') 1> (Join-Path $output 'fixture-compile.stdout') 2> (Join-Path $output 'fixture-compile.stderr')
if ($LASTEXITCODE -ne 0) { throw 'complete caller fixture compilation failed' }
& $java "-Drusty.quest.admission.fixture.library=$library" -cp $classPath io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.AdmissionJniRegression 1> (Join-Path $output 'test.stdout') 2> (Join-Path $output 'test.stderr')
if ($LASTEXITCODE -ne 0) { throw 'complete concurrent Peer caller regression failed' }
# A fresh JVM gives the actual native source owner its bootstrap-empty state.
& $java "-Drusty.quest.admission.fixture.library=$library" -cp $classPath io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.RetirementJniCaller 1> (Join-Path $output 'retirement-test.stdout') 2> (Join-Path $output 'retirement-test.stderr')
if ($LASTEXITCODE -ne 0) { throw 'complete default Local retirement/native-generation caller regression failed' }
$activitySource = Join-Path $app 'SpatialCameraPanelActivity.kt'

$nativeSources = @('own_stereo_capture_runtime.rs', 'own_packed_pool_jni.rs', 'stereo_source_payload.rs', 'stereo_input_set.rs', 'peer_projection_runtime.rs') | ForEach-Object { Join-Path $repo ('apps/spatial-camera-panel-android/native-receipt/src/' + $_) }
$hashes = @($sources + $kotlinSources + $nativeSources + $activitySource | ForEach-Object {
    [ordered]@{path=[IO.Path]::GetRelativePath($repo, $_).Replace('\', '/'); sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}
})
[ordered]@{
    status='passed'; scope='actual compiled Kotlin Display/Router -> Java Receiver/Registry -> host JVM actual JNI/native admission/source-set; coherent clock interleaving and closed first-failure cases'
    sources=$hashes
    platform_scope='mock Looper, physical actor flags and deterministic injected clock; actual selected owner functions extracted into host JNI library and actual source-set/input/peer-route modules; retained Java Own phase/identity with injected pool freshness'
    physical_claim='host JVM JNI only; no camera, Android JNI supplier composition, GL, decoder, network or device effects; reproduced interleaving is not a physical device-cause claim'
    native_library_sha256=(Get-FileHash -LiteralPath $library).Hash.ToLowerInvariant()
    native_fixture_source_sha256=(Get-FileHash -LiteralPath (Join-Path $nativeOutput 'src/lib.rs')).Hash.ToLowerInvariant()
    fixture_process_exit='explicit exit 0 after assertions; actual supplier executors have no fixture shutdown route'
    retirement_scope='actual default Local1, stale poll, extracted Activity stop branch -> native Disabled2; intent1/native2 join reserves Peer3; stale native counter and substituted proof word rejected'
    activity_local_stop_sha256=(Get-FileHash -LiteralPath (Join-Path $nativeOutput 'activity-local-stop-extracted.txt')).Hash.ToLowerInvariant()
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
