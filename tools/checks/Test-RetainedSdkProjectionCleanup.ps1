param(
    [Parameter(Mandatory)][string]$RepoRoot,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$KotlinCompilerClassPath,
    [Parameter(Mandatory)][string]$KotlinStandardLibraryClassPath,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'output directory must be absent' }
$null = New-Item -ItemType Directory -Path $output
$suffix = if ($IsWindows) { '.exe' } else { '' }
$java = Join-Path $JavaHome "bin/java$suffix"
$fixture = Join-Path $PSScriptRoot 'fixtures/retained-sdk-projection'
$app = Join-Path $repo 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel'
$resources = Join-Path $app 'SpatialSdkQuadResourceCoordinator.kt'
$placement = Join-Path $app 'SpatialCameraHwbProjectionPlacementUpdateCoordinator.kt'
$activity = Join-Path $app 'SpatialCameraPanelActivity.kt'
$method = [regex]::Matches([IO.File]::ReadAllText($activity), '(?ms)^  private fun cleanupSdkProjectionResourcesOnUiThread\(reason: String\): String \{.*?^  \}')
if ($method.Count -ne 1) { throw 'expected one actual bounded Activity SDK cleanup method' }
$launch = [regex]::Matches([IO.File]::ReadAllText($activity), '(?ms)^  private fun runCameraHwbProjectionProbe\(.*?^  \}')
if ($launch.Count -ne 1) { throw 'expected one actual Activity camera projection launch method' }
$boundary = Join-Path $output 'ActivityBoundaryFixture.kt'
[IO.File]::WriteAllText($boundary, [IO.File]::ReadAllText((Join-Path $fixture 'ActivityBoundaryFixture.kt')) + "`n" + $method[0].Value + "`n" + $launch[0].Value + "`n}`n", [Text.UTF8Encoding]::new($false))
$sources = @($resources, $placement, $boundary) + @(Get-ChildItem -LiteralPath $fixture -Filter '*.kt' -File | Where-Object Name -ne 'ActivityBoundaryFixture.kt' | ForEach-Object FullName)
$classes = Join-Path $output 'classes'
$null = New-Item -ItemType Directory -Path $classes
& $java -cp $KotlinCompilerClassPath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -classpath $KotlinStandardLibraryClassPath -d $classes @sources 1> (Join-Path $output 'compile.stdout') 2> (Join-Path $output 'compile.stderr')
if ($LASTEXITCODE -ne 0) { throw 'actual retained SDK cleanup owner compilation failed' }
& $java -cp "$classes$([IO.Path]::PathSeparator)$KotlinStandardLibraryClassPath" io.github.mesmerprism.rustyquest.spatial_camera_panel.MainKt 1> (Join-Path $output 'test.stdout') 2> (Join-Path $output 'test.stderr')
if ($LASTEXITCODE -ne 0) { throw 'actual retained SDK cleanup owner regression failed' }
[ordered]@{
    status='passed'; scope='actual full resource and placement owners plus exact extracted Activity UI cleanup method'
    source_sha256=@($resources, $placement, $activity | ForEach-Object { [ordered]@{ path=[IO.Path]::GetRelativePath($repo, $_).Replace('\', '/'); sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() } })
    physical_claim='none; SDK/Android observations mocked including sticky destroy semantics; deadline cancellation is an actual ten-second wait; no native re-removal or JNI/device proof'
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
