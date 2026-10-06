param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'),[Parameter(Mandatory)][string]$OutputRoot,[Parameter(Mandatory)][string]$Rustc)
Set-StrictMode -Version Latest;$ErrorActionPreference='Stop'
$RepoRoot=[IO.Path]::GetFullPath($RepoRoot);$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
if(-not$OutputRoot.StartsWith((Join-Path $RepoRoot 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)-or(Test-Path $OutputRoot)){throw 'Create-new ignored target output required'}
$rustRoot=Join-Path $RepoRoot 'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex'
$production=[IO.File]::ReadAllText((Join-Path $rustRoot 'retained_cleanup_host.rs'))
$startToken='AndroidMediaOwnerPlacementTarget::Remote { peer_id } => {'
$endToken='failure_stage = OwnerFailureStage::RemotePrepareProof;'
$start=$production.IndexOf($startToken);$end=$production.IndexOf($endToken,$start)
if($start-lt0-or$end-lt$start-or$production.IndexOf($startToken,$start+$startToken.Length)-ge0-or$production.IndexOf($endToken,$end+$endToken.Length)-ge0){throw 'Unique exact production prepare boundaries required'}
$block=$production.Substring($start+$startToken.Length,$end-$start-$startToken.Length)
foreach($name in @('RemotePeerBind','RemoteRequesterAuthority','RemotePendingCapacity','RemoteSequence','RemoteEntropy','RemotePrepareEncode','RemotePrepareSign','RemotePrepareExchange')){if(-not$block.Contains('failure_stage = OwnerFailureStage::'+$name+';')){throw 'Required production substage absent'}}
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'fixtures/retained_prepare_diagnostic_host.rs'))
$source=$template.Replace('OWNER_FAILURE_SOURCE',(Join-Path $rustRoot 'owner_failure.rs').Replace('\','/')).Replace('PRODUCTION_PREPARE_BLOCK',$block)
$null=New-Item -ItemType Directory $OutputRoot
$sourcePath=Join-Path $OutputRoot 'prepare-host.rs';[IO.File]::WriteAllText($sourcePath,$source,[Text.UTF8Encoding]::new($false))
$exe=Join-Path $OutputRoot 'prepare-host.exe'
& $Rustc --edition 2021 --test $sourcePath -o $exe
if($LASTEXITCODE-ne0){throw 'Actual production-block host compilation failed'}
& $exe --test-threads=1
if($LASTEXITCODE-ne0){throw 'Actual production-block host controls failed'}
$result=@{schema='rusty.quest.retained_prepare_diagnostic_host.v1';status='passed';tests=6;production_block_executed=$true;source_path=$sourcePath;source_sha256=(Get-FileHash $sourcePath).Hash.ToLowerInvariant();limits='Exact outgoing production block and production first-failure latch; authority/callback/entropy/serialization seams explicitly modeled. No JNI/Android native typecheck, real peer exchange, lifecycle completion, APK or device effect.';device_calls=0}
[IO.File]::WriteAllText((Join-Path $OutputRoot 'RESULT.json'),($result|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
