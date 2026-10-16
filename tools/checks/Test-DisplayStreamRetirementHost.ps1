param([string]$RepoRoot = "", [string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = "Stop"
if (!$RepoRoot) { $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
$app = Join-Path $RepoRoot 'apps/display-stream-android'
$out = Join-Path $RepoRoot ('target/display-retirement-host-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out | Out-Null
$source = Join-Path $app 'src/main/java/io/github/mesmerprism/rustyquest/displaystream'
$files = @(Get-ChildItem -LiteralPath (Join-Path $app 'host-tests') -Recurse -Filter '*.java' | ForEach-Object FullName)
$files += @('DisplayStreamAttempt.java','DisplayStreamAttempts.java','DisplayStreamControl.java','Rmanvid1Writer.java','H264SurfaceEncoder.java') | ForEach-Object { Join-Path $source $_ }
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 --release 8 -d $out $files
if ($LASTEXITCODE) { throw 'Display retirement harness compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -cp $out io.github.mesmerprism.rustyquest.displaystream.DisplayStreamAttemptHostMain
if ($LASTEXITCODE) { throw 'Display retirement harness failed' }
