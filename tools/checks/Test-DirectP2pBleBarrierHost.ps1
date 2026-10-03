[CmdletBinding()]
param([Parameter(Mandatory)][string]$JavaHome,[Parameter(Mandatory)][string]$AndroidJar,[Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
if($PSVersionTable.PSVersion-lt[version]'7.6'){throw 'PowerShell7.6 required'}
if(Test-Path -LiteralPath $OutputRoot){throw 'Explicit create-new output required'}
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$pair=Join-Path $root 'apps/direct-p2p-provider-android/src/main/java/io/github/mesmerprism/rustyquest/directp2p'
$ble=Join-Path $root 'apps/peer-rendezvous-android/src/main/java/io/github/mesmerprism/rustyquest/peer_rendezvous'
$test=Join-Path $root 'apps/peer-rendezvous-android/tests/java/io/github/mesmerprism/rustyquest/peer_rendezvous/BleLiveObservationStateTest.java'
$java=Join-Path $JavaHome 'bin/java.exe';$javac=Join-Path $JavaHome 'bin/javac.exe'
$productionFiles=@((Get-ChildItem -LiteralPath $pair -Filter '*.java' -File).FullName)+@((Get-ChildItem -LiteralPath $ble -Filter '*.java' -File).FullName)
$files=$productionFiles+@($test)
$pins=@($files+@($java,$javac,$AndroidJar)|ForEach-Object{@{path=$_;sha256=(Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant()}})
$null=New-Item -ItemType Directory -Path $OutputRoot
& $javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath $AndroidJar -d $OutputRoot @productionFiles
if($LASTEXITCODE-ne0){throw 'Actual production Android compile failed'}
& $javac -encoding UTF-8 -source 1.8 -target 1.8 -cp "$OutputRoot;$AndroidJar" -d $OutputRoot $test
if($LASTEXITCODE-ne0){throw 'Host-only regression fixture compile failed'}
& $java -cp "$OutputRoot;$AndroidJar" io.github.mesmerprism.rustyquest.peer_rendezvous.BleLiveObservationStateTest
if($LASTEXITCODE-ne0){throw 'Live state/barrier regression failed'}
foreach($pin in $pins){if((Get-FileHash -LiteralPath $pin.path).Hash.ToLowerInvariant()-cne$pin.sha256){throw 'Source/tool changed during qualification'}}
[IO.File]::WriteAllText((Join-Path $OutputRoot 'RESULT.json'),(@{schema='rusty.quest.live_ble_barrier_host.v1';native_exit=0;apk_built=$false;device_calls=0;pins=$pins}|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
