param(
    [Parameter(Mandatory)][string]$RepoRoot,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$AndroidApi33Jar,
    [Parameter(Mandatory)][string]$AndroidApi34Jar,
    [Parameter(Mandatory)][string]$HostJsonJar,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'output directory must be absent' }
$null = New-Item -ItemType Directory -Path $output
$suffix = if ($IsWindows) { '.exe' } else { '' }
$javac = Join-Path $JavaHome "bin/javac$suffix"
$java = Join-Path $JavaHome "bin/java$suffix"
$sourceRoot = Join-Path $repo 'crates/rusty-quest-media-stream-android/android/library/src/main/java'
$sources = @(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -File -Recurse | Sort-Object FullName | ForEach-Object FullName)
$sourceHashes = @($sources | ForEach-Object { [ordered]@{ path=[IO.Path]::GetRelativePath($repo, $_).Replace('\','/'); sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() } })
foreach ($api in @(33, 34)) {
    $jar = if ($api -eq 33) { $AndroidApi33Jar } else { $AndroidApi34Jar }
    $classes = Join-Path $output "api$api-classes"
    & $javac --release 8 '-Xlint:all' -Werror -cp $jar -d $classes @sources 1> (Join-Path $output "api$api.stdout") 2> (Join-Path $output "api$api.stderr")
    if ($LASTEXITCODE -ne 0) { throw "actual media API$api compilation failed" }
}
$fixtures = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'fixtures/own-capture-stage-diagnostic') -Filter '*.java' -File -Recurse | ForEach-Object FullName)
$hostClasses = Join-Path $output 'host-classes'
$classPath = "$HostJsonJar$([IO.Path]::PathSeparator)$(Join-Path $output 'api34-classes')"
& $javac --release 8 '-Xlint:all' -Werror -cp $classPath -d $hostClasses @fixtures 1> (Join-Path $output 'host-compile.stdout') 2> (Join-Path $output 'host-compile.stderr')
if ($LASTEXITCODE -ne 0) { throw 'actual diagnostic host fixture compilation failed' }
foreach ($case in @('CaptureFrameTraceCase', 'OwnCaptureStageCadenceCase', 'SurfaceDispatchWitnessCase')) {
    & $java -cp "$hostClasses$([IO.Path]::PathSeparator)$classPath" "io.github.mesmerprism.rustyquest.media.$case" 1> (Join-Path $output "$case.stdout") 2> (Join-Path $output "$case.stderr")
    if ($LASTEXITCODE -ne 0) { throw "$case failed" }
}
[ordered]@{
    status='passed'; scope='actual media API33/API34 + bounded production trace/cadence/Surface fixtures'
    lint='all/Werror'; source_hashes=$sourceHashes
    api33_jar_sha256=(Get-FileHash -LiteralPath $AndroidApi33Jar -Algorithm SHA256).Hash.ToLowerInvariant()
    api34_jar_sha256=(Get-FileHash -LiteralPath $AndroidApi34Jar -Algorithm SHA256).Hash.ToLowerInvariant()
    host_json_jar_sha256=(Get-FileHash -LiteralPath $HostJsonJar -Algorithm SHA256).Hash.ToLowerInvariant()
    physical_claim='none; no JNI/device/GPU/camera service effects'
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
