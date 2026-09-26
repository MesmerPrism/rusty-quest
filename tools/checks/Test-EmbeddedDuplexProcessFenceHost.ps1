[CmdletBinding()]
param([string]$RepoRoot = (Join-Path $PSScriptRoot '../..'))
$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$package = 'io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
$app = Join-Path $RepoRoot 'apps/spatial-camera-panel-android/app'
$main = Join-Path $app "src/main/java/$package"
$test = Join-Path $app "src/test/java/$package/EmbeddedDuplexProcessFenceHostTest.java"
$output = Join-Path ([IO.Path]::GetTempPath()) ('duplex-fence-classes-' + [guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $output)
$javac = (Get-Command javac -ErrorAction Stop).Source
$java = Join-Path (Split-Path $javac -Parent) 'java.exe'
& $javac '--release' '17' '-encoding' 'UTF-8' '-d' $output (Join-Path $main 'EmbeddedDuplexProcessFence.java') $test
if ($LASTEXITCODE -ne 0) { throw 'Process fence host compilation failed' }
& $java '-cp' $output 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexProcessFenceHostTest'
if ($LASTEXITCODE -ne 0) { throw 'Process fence host regressions failed' }
$hostSource = Get-Content -Raw -LiteralPath (Join-Path $main 'EmbeddedDuplexProcessHost.java')
$platformSource = Get-Content -Raw -LiteralPath (Join-Path $main 'EmbeddedDuplexPlatform.java')
foreach ($required in @('ensureProcessFence(); result.complete(action.run())', 'beforeRuntimeEffects(checkpointSnapshot(), evidenceSnapshot())', 'callbacks.bindProcessFence(processFence)', 'afterVerifiedNoMediaCleanup(checkpointSnapshot(), evidenceSnapshot())', 'platform.retireProcessCallbacks()')) {
    if (-not $hostSource.Contains($required)) { throw "App host fence integration missing: $required" }
}
if (-not $platformSource.Contains('guard.requireLive()') -or -not $platformSource.Contains('guard.retire()')) { throw 'App callback fence integration missing' }
Write-Output 'App startup/callback source gate passed. No native process-fence or physical-cleanup attestation.'
