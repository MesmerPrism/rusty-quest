param(
    [Parameter(Mandatory)][string]$RepoRoot,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$CompiledOwnerClassPath,
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
$camera = Join-Path $repo 'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media/PackedStereoCaptureOwner.java'
$app = Join-Path $repo 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
$receiver = Join-Path $app 'EmbeddedDuplexReceiver.java'
$platform = Join-Path $app 'EmbeddedDuplexPlatform.java'
$cameraFixtures = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'fixtures/camera-close') -Filter '*.java' -File -Recurse | ForEach-Object FullName)
$cameraClasses = Join-Path $output 'camera-close-classes'
$null = New-Item -ItemType Directory -Path $cameraClasses
& $javac --release 8 '-Xlint:all' -Werror -d $cameraClasses @cameraFixtures $camera 1> (Join-Path $output 'camera-compile.stdout') 2> (Join-Path $output 'camera-compile.stderr')
if ($LASTEXITCODE -ne 0) { throw 'camera callback fixture compilation failed' }
& $java -cp $cameraClasses 'io.github.mesmerprism.rustyquest.media.CaptureCloseTest' 1> (Join-Path $output 'camera-test.stdout') 2> (Join-Path $output 'camera-test.stderr')
if ($LASTEXITCODE -ne 0) { throw 'camera callback fixture failed' }
$ownerClasses = Join-Path $output 'actual-duplex-classes'
$null = New-Item -ItemType Directory -Path $ownerClasses
& $javac --release 8 '-Xlint:all' -Werror -cp $CompiledOwnerClassPath -d $ownerClasses $receiver $platform 1> (Join-Path $output 'owner-compile.stdout') 2> (Join-Path $output 'owner-compile.stderr')
if ($LASTEXITCODE -ne 0) { throw 'actual receiver/platform compilation failed' }
$stageFixtures = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'fixtures/embedded-duplex-closed-stages') -Filter '*.java' -File -Recurse | ForEach-Object FullName)
$stageClasses = Join-Path $output 'stage-fixture-classes'
$null = New-Item -ItemType Directory -Path $stageClasses
$ownerCp = "$ownerClasses$([IO.Path]::PathSeparator)$CompiledOwnerClassPath"
& $javac --release 8 '-Xlint:all' -Werror -cp $ownerCp -d $stageClasses @stageFixtures 1> (Join-Path $output 'stage-compile.stdout') 2> (Join-Path $output 'stage-compile.stderr')
if ($LASTEXITCODE -ne 0) { throw 'closed stage fixture compilation failed' }
& $java -cp "$stageClasses$([IO.Path]::PathSeparator)$ownerCp" 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.SinkArmStageRegression' 1> (Join-Path $output 'stage-test.stdout') 2> (Join-Path $output 'stage-test.stderr')
if ($LASTEXITCODE -ne 0) { throw 'closed stage fixture failed' }
[ordered]@{
 status='passed'; scope='actual selected Java camera owner + Receiver/Platform; synthetic platform/media dependencies'
 camera_source_sha256=(Get-FileHash -LiteralPath $camera -Algorithm SHA256).Hash.ToLowerInvariant()
 receiver_source_sha256=(Get-FileHash -LiteralPath $receiver -Algorithm SHA256).Hash.ToLowerInvariant()
 platform_source_sha256=(Get-FileHash -LiteralPath $platform -Algorithm SHA256).Hash.ToLowerInvariant()
 physical_claim='none; no JNI/device/GPU/camera service effects'
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
