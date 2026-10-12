param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'),[Parameter(Mandatory)][string]$OutputRoot,[Parameter(Mandatory)][string]$Rustc)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$RepoRoot=[IO.Path]::GetFullPath($RepoRoot);$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
if(-not$OutputRoot.StartsWith((Join-Path $RepoRoot 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)-or(Test-Path $OutputRoot)){throw 'Fresh ignored owner target required'}
$p=Join-Path $RepoRoot 'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/retained_cleanup_host.rs'
$source=[IO.File]::ReadAllText($p)
function Body([string]$Marker){$start=$source.IndexOf($Marker);if($start-lt0-or$source.IndexOf($Marker,$start+$Marker.Length)-ge0){throw 'Unique production boundary required'};$opening=$source.IndexOf('{',$start);$depth=0;for($i=$opening;$i-lt$source.Length;$i++){if($source[$i]-eq'{'){$depth++}elseif($source[$i]-eq'}'){$depth--;if($depth-eq0){return $source.Substring($start,$i-$start+1)}}};throw 'Unclosed production boundary'}
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'fixtures/retained_original_enrollment_host.rs'))
$generated=$template.Replace('PRODUCTION_KEY',(Body 'fn key(')).Replace('PRODUCTION_REGISTRY',(Body 'impl AuthenticatedOwnerRegistry for RetainingRegistry {'))
$null=New-Item -ItemType Directory $OutputRoot
$output=Join-Path $OutputRoot 'original-host.rs';[IO.File]::WriteAllText($output,$generated,[Text.UTF8Encoding]::new($false))
& $Rustc --edition 2021 --test $output -o (Join-Path $OutputRoot 'original-host.exe');if($LASTEXITCODE-ne0){throw 'Actual retaining method host compilation failed'}
& (Join-Path $OutputRoot 'original-host.exe') --test-threads=1;if($LASTEXITCODE-ne0){throw 'Actual retaining method controls failed'}
$result=@{schema='rusty.quest.retained_original_enrollment_host.v1';status='passed';tests=9;production_sha256=(Get-FileHash $p).Hash.ToLowerInvariant();generated_sha256=(Get-FileHash $output).Hash.ToLowerInvariant();device_calls=0;limits='Exact native registry producer method/key; clock/authority/durability/provider/physical checkout modeled. Real credential/projection/witness checks are separately registered broker Cargo tests. No full JNI/Android compile or physical effect.'}
[IO.File]::WriteAllText((Join-Path $OutputRoot 'RESULT.json'),($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
