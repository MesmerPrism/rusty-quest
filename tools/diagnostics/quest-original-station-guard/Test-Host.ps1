[CmdletBinding()]
param([Parameter(Mandatory)][string]$JavaHome,[Parameter(Mandatory)][string]$AndroidJar,[Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if(Test-Path -LiteralPath $OutputRoot){throw 'Create-new output required'}
$files=@('OriginalStationGuardContract.java','QuestOriginalStationGuard.java','OriginalStationGuardHostTest.java'|ForEach-Object{Join-Path $PSScriptRoot $_})
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$app=Join-Path $repo 'apps/direct-p2p-provider-android/src/main/java/io/github/mesmerprism/rustyquest/directp2p'
$files+=@(Get-ChildItem -LiteralPath $app -Filter '*.java' -File|ForEach-Object FullName)
$inputs=@($files+@((Join-Path $JavaHome 'bin/javac.exe'),(Join-Path $JavaHome 'bin/java.exe'),$AndroidJar)|ForEach-Object{@{path=$_;sha256=(Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant()}})
New-Item -ItemType Directory -Path $OutputRoot|Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -source 8 -target 8 -cp $AndroidJar -d $OutputRoot @files
if($LASTEXITCODE-ne0){throw 'Actual shell adapter Android compile failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$OutputRoot;$AndroidJar" OriginalStationGuardHostTest
if($LASTEXITCODE-ne0){throw 'Actual contract cases failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp $OutputRoot io.github.mesmerprism.rustyquest.directp2p.DirectP2pLifecycleHostTest
if($LASTEXITCODE-ne0){throw 'Actual app nonce/lifecycle cases failed'}
foreach($row in $inputs){if((Get-FileHash -LiteralPath $row.path).Hash.ToLowerInvariant()-cne$row.sha256){throw 'Input drift'}}
$adapter=Get-Content -LiteralPath $files[1] -Raw
foreach($forbidden in @('addNetwork(','removeNetwork(','preSharedKey','temporary_ssid')){if($adapter.Contains($forbidden)){throw "Forbidden effect $forbidden"}}
[IO.File]::WriteAllText((Join-Path $OutputRoot 'RESULT.json'),(@{schema='rusty.quest.original_station_guard_host.v1';status='pass';inputs=$inputs;device_calls=0;apk_built=$false;device_verified=$false}|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
