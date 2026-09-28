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
$executableSuffix = if ($IsWindows) { '.exe' } else { '' }
$java = Join-Path $JavaHome "bin/java$executableSuffix"
$javac = Join-Path $JavaHome "bin/javac$executableSuffix"
$packagePath = 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
$resources = Join-Path $repo "$packagePath/EmbeddedDuplexResources.java"
$fence = Join-Path $repo "$packagePath/EmbeddedDuplexProcessFence.java"
$fixtures = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'fixtures/embedded-duplex-retained-install') -Filter '*.java' -File -Recurse | ForEach-Object FullName)
$classes = Join-Path $output 'retained-install-classes'
$null = New-Item -ItemType Directory -Path $classes
& $javac --release 8 '-Xlint:all,-auxiliaryclass,-try' -Werror -d $classes @fixtures $resources $fence 1> (Join-Path $output 'resources-compile.stdout') 2> (Join-Path $output 'resources-compile.stderr')
if ($LASTEXITCODE -ne 0) { throw 'retained installation fixture compilation failed' }
& $java -cp $classes 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.PartialInstallTest' (Join-Path $output 'app-fence-fixture') 1> (Join-Path $output 'resources-test.stdout') 2> (Join-Path $output 'resources-test.stderr')
if ($LASTEXITCODE -ne 0) { throw 'retained installation fixture failed' }
$getterClasses = Join-Path $output 'cleanup-getter-classes'
$null = New-Item -ItemType Directory -Path $getterClasses
& $javac --release 8 '-Xlint:all' -Werror -cp $CompiledOwnerClassPath -d $getterClasses (Join-Path $PSScriptRoot 'fixtures/own-cleanup-status/OwnCleanupStatusRegression.java') 1> (Join-Path $output 'getter-compile.stdout') 2> (Join-Path $output 'getter-compile.stderr')
if ($LASTEXITCODE -ne 0) { throw 'cleanup getter compilation failed' }
& $java -cp "$getterClasses$([IO.Path]::PathSeparator)$CompiledOwnerClassPath" 'io.github.mesmerprism.rustyquest.media.OwnCleanupStatusRegression' 1> (Join-Path $output 'getter-test.stdout') 2> (Join-Path $output 'getter-test.stderr')
if ($LASTEXITCODE -ne 0) { throw 'cleanup getter fixture failed' }
[ordered]@{
    status='passed'; scope='actual Resources and app Fence; injected platform/media owners; actual compiled cleanup getters'
    resources_source_sha256=(Get-FileHash -LiteralPath $resources -Algorithm SHA256).Hash.ToLowerInvariant()
    fence_source_sha256=(Get-FileHash -LiteralPath $fence -Algorithm SHA256).Hash.ToLowerInvariant()
    physical_claim='none; no JNI/device/GL effects; native receipts and dispatch are fixtures'
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
