param([Parameter(Mandatory)][string]$OutDir, [string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
$module=[IO.Path]::GetFullPath($PSScriptRoot)
$output=[IO.Path]::GetFullPath($OutDir)
if ($output.Equals($module,[StringComparison]::OrdinalIgnoreCase) -or
    $output.StartsWith($module.TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
    $module.StartsWith($output.TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Output must be disjoint from library source.' }
if (Test-Path -LiteralPath $output) { throw 'Output must be a new capsule; prior outputs are preserved.' }
$javac=if($JavaHome){Join-Path $JavaHome 'bin/javac.exe'}else{(Get-Command javac -ErrorAction Stop).Source}
$jar=if($JavaHome){Join-Path $JavaHome 'bin/jar.exe'}else{(Get-Command jar -ErrorAction Stop).Source}
$sources=@('HubBleFrames.java','GattPeer.java','GattInputQueue.java'|ForEach-Object {Join-Path $module ('src/main/java/io/github/mesmerprism/rustyquest/ble_control/'+$_)})
$pins=@($sources|ForEach-Object {[ordered]@{path=[IO.Path]::GetRelativePath($module,$_).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})
$classes=Join-Path $output 'classes'
[void][IO.Directory]::CreateDirectory($classes)
& $javac --release 8 -encoding UTF-8 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Carrier compilation failed.'}
$artifact=Join-Path $output 'quest-ble-control.jar'
& $jar --create --file $artifact -C $classes .
if($LASTEXITCODE -ne 0){throw 'Carrier packaging failed.'}
foreach($pin in $pins){if((Get-FileHash -LiteralPath (Join-Path $module $pin.path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $pin.sha256){throw 'Source changed during packaging.'}}
[ordered]@{schema='rusty.quest.ble_carrier.artifact.v1';version='0.2.0';inputs=$pins;artifact='quest-ble-control.jar';sha256=(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant();java_release=8;device_proof=$false} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'artifact.json') -Encoding utf8NoBOM
