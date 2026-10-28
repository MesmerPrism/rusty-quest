param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'),[Parameter(Mandatory)][string]$OutputRoot,[Parameter(Mandatory)][string]$Rustc)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$RepoRoot=[IO.Path]::GetFullPath($RepoRoot);$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
if(-not$OutputRoot.StartsWith((Join-Path $RepoRoot 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)-or(Test-Path $OutputRoot)){throw 'Fresh ignored owner target required'}
$p=Join-Path $RepoRoot 'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/retained_cleanup_host.rs'
$admission=Join-Path ([IO.Path]::GetDirectoryName($p)) 'cleanup_admission.rs'
$fixture=Join-Path $PSScriptRoot 'fixtures/retained_original_enrollment_host.rs'
$pins=@($p,$admission,$fixture,$PSCommandPath)|ForEach-Object{@{path=$_;sha256=(Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant()}}
$source=[IO.File]::ReadAllText($p)
function Body([string]$Marker){$start=$source.IndexOf($Marker);if($start-lt0-or$source.IndexOf($Marker,$start+$Marker.Length)-ge0){throw 'Unique production boundary required'};$opening=$source.IndexOf('{',$start);$depth=0;for($i=$opening;$i-lt$source.Length;$i++){if($source[$i]-eq'{'){$depth++}elseif($source[$i]-eq'}'){$depth--;if($depth-eq0){return $source.Substring($start,$i-$start+1)}}};throw 'Unclosed production boundary'}
$template=[IO.File]::ReadAllText($fixture)
$construction=(Body 'pub(super) fn construction_snapshot(').Replace('pub(super) fn ','fn ')
$fresh=(Body 'pub(super) fn fresh_setup_quiescence_observed(').Replace('pub(super) fn ','fn ')
$cut=$fresh.IndexOf('        trace.mark(NativeGuard::CleanupStateLock,')
if($cut-lt0){throw 'Exact observer admission prefix boundary required'}
$fresh=$fresh.Substring(0,$cut)+"        // Remaining post-admission cryptographic/quiescence review is a modeled leaf.`n        let _ = (replay, public_key);`n        Ok(())`n    }"
$generated=$template.Replace('PRODUCTION_KEY',(Body 'fn key(')).Replace('PRODUCTION_REGISTRY',(Body 'impl AuthenticatedOwnerRegistry for RetainingRegistry {')).Replace('PRODUCTION_REQUIRE_STATE',(Body 'fn require_state(')).Replace('PRODUCTION_PERSIST',(Body 'fn persist(')).Replace('PRODUCTION_CONSTRUCTION',$construction).Replace('PRODUCTION_FRESH_PREFIX',$fresh).Replace('ADMISSION_PATH',$admission.Replace('\','/'))
$null=New-Item -ItemType Directory $OutputRoot
$output=Join-Path $OutputRoot 'original-host.rs';[IO.File]::WriteAllText($output,$generated,[Text.UTF8Encoding]::new($false))
& $Rustc --edition 2021 --test $output -o (Join-Path $OutputRoot 'original-host.exe') 2>&1|Tee-Object -FilePath (Join-Path $OutputRoot 'compile.txt');$compileExit=$LASTEXITCODE
$testExit=$null
if($compileExit-eq0){& (Join-Path $OutputRoot 'original-host.exe') --test-threads=1 --nocapture 2>&1|Tee-Object -FilePath (Join-Path $OutputRoot 'controls.txt');$testExit=$LASTEXITCODE}
$unchanged=$true
foreach($pin in $pins){if((Get-FileHash -LiteralPath $pin.path).Hash.ToLowerInvariant()-ne$pin.sha256){$unchanged=$false}}
$passed=$compileExit-eq0-and$testExit-eq0-and$unchanged
$result=@{schema='rusty.quest.retained_original_enrollment_host.v1';status=$(if($passed){'passed'}else{'failed'});tests=17;compile_exit=$compileExit;controls_exit=$testExit;source_unchanged=$unchanged;source_pins=$pins;production_sha256=$pins[0].sha256;generated_sha256=(Get-FileHash $output).Hash.ToLowerInvariant();device_calls=0;limits='Exact native registry producer/key, Cleanup persist/require_state/construction_snapshot, fresh-setup observer admission prefix, actual cleanup Admission/Lease/Condvar and AtomicBool poison; thin test wrapper observes enter attempts or executes actual failed persist before real try_enter. JSON encoding, clock, current C1 projection source/authority, durable callback/provider effects and remaining fresh-setup cryptographic review modeled. Local post-wait source lookup and semantic projection joins execute in the extracted registry body; receiving remote-original enrollment remains its independent modeled guard. Channel handshakes establish overlap ordering; timeouts are test hang bounds only. Real credential/projection/witness checks are separately registered broker Cargo tests. No full JNI/Android compile or physical effect.'}
[IO.File]::WriteAllText((Join-Path $OutputRoot 'RESULT.json'),($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
if(-not$passed){throw 'Actual retaining method controls failed; evidence retained'}
