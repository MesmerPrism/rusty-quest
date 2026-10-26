[CmdletBinding()]
param(
    [string]$RepoRoot = (Join-Path $PSScriptRoot '../..'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$HostJsonJar = $env:RUSTY_QUEST_HOST_JSON_JAR,
    [string]$AndroidJar,
    [string]$OutputRoot
)
$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$package = 'io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
$app = Join-Path $RepoRoot 'apps/spatial-camera-panel-android/app'
$main = Join-Path $app "src/main/java/$package"
$tests = Join-Path (Split-Path $app -Parent) 'host-tests/embedded_duplex'
if (-not $HostJsonJar) {
    $cache = Join-Path $HOME '.gradle/caches/modules-2/files-2.1/org.json/json/20240303'
    $candidates = @(Get-ChildItem -LiteralPath $cache -Filter 'json-20240303.jar' -File -Recurse -ErrorAction SilentlyContinue)
    if ($candidates.Count) { $HostJsonJar = $candidates[0].FullName }
}
if (-not $HostJsonJar -or (Get-FileHash -LiteralPath $HostJsonJar).Hash.ToLowerInvariant() -cne '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed') {
    throw 'Select pinned org.json 20240303 with -HostJsonJar or RUSTY_QUEST_HOST_JSON_JAR'
}
if (-not $OutputRoot) { $OutputRoot = Join-Path $RepoRoot ('target/duplex-identity-' + [guid]::NewGuid().ToString('N')) }
$output = [IO.Path]::GetFullPath($OutputRoot)
$target = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'target'))
if (-not $output.StartsWith($target + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $output)) {
    throw 'Select a create-new output directory beneath repository target'
}
[void](New-Item -ItemType Directory -Path $output)
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { (Get-Command javac -ErrorAction Stop).Source }
$java = Join-Path (Split-Path $javac -Parent) 'java.exe'
$sources = @('EmbeddedDuplexProcessFence.java', 'EmbeddedDuplexProcessIncarnation.java', 'EmbeddedDuplexCanonicalJson.java') | ForEach-Object { Join-Path $main $_ }
$testSources = @('EmbeddedDuplexProcessFenceHostTest.java', 'EmbeddedDuplexProcessIncarnationHostTest.java') | ForEach-Object { Join-Path $tests $_ }
$classes = Join-Path $output 'host'
& $javac '--release' '17' '-encoding' 'UTF-8' '-cp' $HostJsonJar '-d' $classes @sources @testSources *> (Join-Path $output 'host-javac.log')
if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $output 'host-javac.log'); throw 'Process identity/fence host compilation failed' }
foreach ($test in @('EmbeddedDuplexProcessIncarnationHostTest', 'EmbeddedDuplexProcessFenceHostTest')) {
    & $java '-cp' ($classes + [IO.Path]::PathSeparator + $HostJsonJar) ("io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.$test") *> (Join-Path $output "$test.log")
    Get-Content (Join-Path $output "$test.log")
    if ($LASTEXITCODE -ne 0) { throw "Process identity/fence host regressions failed: $test" }
}
if ($AndroidJar) {
    & $javac '--release' '17' '-encoding' 'UTF-8' '-cp' $AndroidJar '-d' (Join-Path $output 'android-api') @sources *> (Join-Path $output 'android-javac.log')
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $output 'android-javac.log'); throw 'Process identity/fence Android API compilation failed' }
    Write-Output 'Actual Android API compilation passed; no Android execution or APK build.'
}
Write-Output "Identity/fence evidence: $output"
$hostSource = Get-Content -Raw -LiteralPath (Join-Path $main 'EmbeddedDuplexProcessHost.java')
$platformSource = Get-Content -Raw -LiteralPath (Join-Path $main 'EmbeddedDuplexPlatform.java')
foreach ($required in @('commands.execute(EmbeddedDuplexOriginals.carry(() ->', 'ensureProcessFence();', 'result.complete(action.run());', 'processFence.bindIncarnation(EmbeddedDuplexProcessIncarnation.current(', 'EmbeddedDuplexOriginals.bindActualActor(originalActor(), processFence.generation())', 'beforeRuntimeEffects(checkpointSnapshot(), evidenceSnapshot())', 'callbacks.bindProcessFence(processFence)', 'afterVerifiedNoMediaCleanup(checkpointSnapshot(), evidenceSnapshot())', 'platform.retireProcessCallbacks()')) {
    if (-not $hostSource.Contains($required)) { throw "App host fence integration missing: $required" }
}
if (-not $platformSource.Contains('guard.requireLive()') -or -not $platformSource.Contains('guard.retire()')) { throw 'App callback fence integration missing' }
Write-Output 'App startup/callback source gate passed. No native process-fence or physical-cleanup attestation.'
