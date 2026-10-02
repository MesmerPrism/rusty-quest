[CmdletBinding()]
param([string]$JavaHome,[string]$AndroidJar,[string]$OutputRoot)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if($PSVersionTable.PSVersion -lt [version]'7.6'){throw 'PowerShell7.6 required'}
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$source=Join-Path $root 'apps/direct-p2p-provider-android/src/main/java/io/github/mesmerprism/rustyquest/directp2p'
foreach($p in @((Join-Path $JavaHome 'bin/javac.exe'),(Join-Path $JavaHome 'bin/java.exe'),$AndroidJar)){if(-not(Test-Path -LiteralPath $p -PathType Leaf)){throw 'Explicit compiler/SDK input missing'}}
if([string]::IsNullOrWhiteSpace($OutputRoot)){throw 'Explicit new output required'}
if(Test-Path -LiteralPath $OutputRoot){throw 'Output already exists'}
New-Item -ItemType Directory -Path $OutputRoot|Out-Null
$files=@(Get-ChildItem -LiteralPath $source -Filter '*.java' -File|Sort-Object Name|ForEach-Object FullName)
$inputs=@($files+@((Join-Path $JavaHome 'bin/javac.exe'),(Join-Path $JavaHome 'bin/java.exe'),$AndroidJar)|ForEach-Object{@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -source 8 -target 8 -cp $AndroidJar -d $OutputRoot @files
if($LASTEXITCODE-ne0){throw 'Actual production Android Java compile failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp $OutputRoot io.github.mesmerprism.rustyquest.directp2p.DirectP2pLifecycleHostTest
if($LASTEXITCODE-ne0){throw 'Actual production lifecycle test failed'}
# Check integration of the production controller at the effect and receipt sites.
$activity=Get-Content -LiteralPath (Join-Path $source 'DirectP2pProviderActivity.java') -Raw
foreach($required in @('lifecycle.request(SystemClock.elapsedRealtime())','lifecycle.exchange(','lifecycle.mayRemove(','lifecycle.discoveryAcknowledged(false)','lifecycle.removalAcknowledged(false)','requestDiscoveryState','requestGroupInfo','lifecycle.nativeComplete()','beginOwnedCleanup();','lifecycle.failure() != null')){if(-not$activity.Contains($required)){throw "Controller integration missing: $required"}}
foreach($forbidden in @('removeStaleGroup','cleanup.put("discovery_stopped", true)','cleanup.put("group_removed", true)','cleanup.put("socket_closed", true)')){if($activity.Contains($forbidden)){throw "Unconditional cleanup/source behavior remains: $forbidden"}}
foreach($row in $inputs){if((Get-FileHash -LiteralPath $row.path -Algorithm SHA256).Hash.ToLowerInvariant()-cne$row.sha256){throw 'Compiler/source changed during qualification'}}
$receipt=@{schema='rusty.quest.direct_p2p_lifecycle_host.v1';status='pass';device_calls=0;apk_built=$false;native_built=$false;inputs=$inputs}
[IO.File]::WriteAllText((Join-Path $OutputRoot 'result.json'),($receipt|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
Write-Output "Android production compile and lifecycle integration=pass output=$OutputRoot"
