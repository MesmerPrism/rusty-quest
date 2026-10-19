[CmdletBinding()]
param([Parameter(Mandatory)] [string]$AndroidJar, [string]$JdkBin = '', [string]$OutputRoot = '')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $OutputRoot) { $OutputRoot = Join-Path $repoRoot ('local-artifacts/shell-recenter-test/' + [guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $OutputRoot) { throw 'OutputRoot must be fresh.' }
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path
$build = Join-Path $OutputRoot 'build'
& (Join-Path $PSScriptRoot 'Build-ShellRecenter.ps1') -AndroidJar $AndroidJar -JdkBin $JdkBin -OutputRoot $build | Out-Host
$javac = if ($JdkBin) { Join-Path $JdkBin $(if ($IsWindows) { 'javac.exe' } else { 'javac' }) } else { (Get-Command javac -CommandType Application | Select-Object -First 1).Source }
$java = if ($JdkBin) { Join-Path $JdkBin $(if ($IsWindows) { 'java.exe' } else { 'java' }) } else { (Get-Command java -CommandType Application | Select-Object -First 1).Source }
$testClasses = Join-Path $OutputRoot 'tests'
New-Item -ItemType Directory -Path $testClasses | Out-Null
$classpath = (Join-Path $build 'classes.jar') + [IO.Path]::PathSeparator + $testClasses
& $javac --release 8 -encoding UTF-8 -Xlint:all -Werror -classpath $classpath -d $testClasses (Join-Path $repoRoot 'apps/shell-recenter-android/host-tests/ShellRecenterConformanceMain.java')
if ($LASTEXITCODE -ne 0) { throw 'Host test compilation failed.' }
& $java -cp $classpath ShellRecenterConformanceMain
if ($LASTEXITCODE -ne 0) { throw 'Production consumer conformance failed.' }
$doubles = Join-Path $OutputRoot 'android-doubles'
New-Item -ItemType Directory -Path $doubles | Out-Null
$doubleSources = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'apps/shell-recenter-android/host-tests/android') -Recurse -File -Filter '*.java' | ForEach-Object FullName)
& $javac --release 8 -encoding UTF-8 -Xlint:all -Werror -d $doubles @doubleSources
if ($LASTEXITCODE -ne 0) { throw 'Framework double compilation failed.' }
$androidClasspath = $doubles + [IO.Path]::PathSeparator + $classpath
$consumerSources = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'apps/shell-recenter-android/host-tests/consumer') -Recurse -File -Filter '*.java' | ForEach-Object FullName)
& $javac --release 8 -encoding UTF-8 -Xlint:all -Werror -classpath $androidClasspath -d $testClasses @consumerSources
if ($LASTEXITCODE -ne 0) { throw 'Android binding consumer compilation failed.' }
& $java -cp $androidClasspath io.github.mesmerprism.rustyquest.shellrecenter.AndroidBindingConformanceMain
if ($LASTEXITCODE -ne 0) { throw 'Android production binding conformance failed.' }
$entries = @(& $(if ($JdkBin) { Join-Path $JdkBin $(if ($IsWindows) { 'jar.exe' } else { 'jar' }) } else { (Get-Command jar -CommandType Application | Select-Object -First 1).Source }) tf (Join-Path $build 'classes.jar'))
if ($LASTEXITCODE -ne 0 -or @($entries | Where-Object { $_ -match '^(android/|org/)|Conformance|host-tests' }).Count -ne 0) { throw 'Production package contains fixture payloads.' }
[ordered]@{schema='rusty.quest.shell_recenter.host_conformance.v1'; passed=$true; production_android_sources_compiled=$true; production_controller_exercised=$true; production_android_binding_exercised=$true; fixtures_excluded=$true; device_tested=$false; apk_built=$false} | ConvertTo-Json | Tee-Object -FilePath (Join-Path $OutputRoot 'result.json')
