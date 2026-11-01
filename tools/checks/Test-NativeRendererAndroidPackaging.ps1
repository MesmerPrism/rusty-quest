param([string]$RepoRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference='Stop'
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererAndroidPackaging.psm1') -Force
function Reject($action){$rejected=$false;try{& $action | Out-Null}catch{$rejected=$true};if(-not $rejected){throw 'Expected rejection'}}
foreach($bad in @($true,$false,0,-1,1.5,2147483648,'1.0','1e2','01','',@())){Reject {Get-NativeRendererAndroidVersion -VersionCode $bad -VersionName '0.1.61'}}
foreach($bad in @($true,'','../x',('x'*65),"1`n2")){Reject {Get-NativeRendererAndroidVersion -VersionCode 61 -VersionName $bad}}
foreach($valid in @(1,61,'61',2147483647)){if((Get-NativeRendererAndroidVersion -VersionCode $valid -VersionName '0.1.61').code -ne [long]$valid){throw 'integer failed'}}
$args=@{AppId='test';PackageName='io.example.test';FeatureLockSha256=('a'*64);SourceCommit=('b'*40);SourceTree=('c'*40);SourceCompositionFingerprint=('d'*64)}
$one=New-NativeRendererAndroidPackagingInput @args -VersionCode 1 -VersionName '0.1.0'
$next=New-NativeRendererAndroidPackagingInput @args -VersionCode 61 -VersionName '0.1.61'
$other=New-NativeRendererAndroidPackagingInput @args -VersionCode 62 -VersionName '0.1.62'
$base=Join-Path $RepoRoot 'target/test-explicit'
if((Get-NativeRendererAndroidPackagingOutputDirectory -BaseDirectory $base -Packaging $one) -cne $base){throw 'legacy OutDir changed'}
$path=Get-NativeRendererAndroidPackagingOutputDirectory -BaseDirectory $base -Packaging $next
if($path -eq $base -or $path -eq (Get-NativeRendererAndroidPackagingOutputDirectory -BaseDirectory $base -Packaging $other)){throw 'versions collide'}
if($path -cne (Get-NativeRendererAndroidPackagingOutputDirectory -BaseDirectory $base -Packaging $next)){throw 'not deterministic'}
if($next.sha256 -notmatch '^[0-9a-f]{64}$' -or $next.sha256 -eq $one.sha256){throw 'identity missing'}
$assetProvider=Import-Module (Join-Path $RepoRoot 'tools/lib/NativeAppPrivateAssetProvider.psm1') -Force -PassThru
Reject {& $assetProvider {Assert-NativeAppPrivateAssetDestinationAvailable -Destination 'android-build-identity.json' -BlockedRecords $script:ReservedAssetRecords -AcceptedDestinations @()}}
Write-Output 'Android packaging versions PASS'
# Capsule damage checks exercise the real CLI; legacy/Spatial data stays accepted.
$temp=Join-Path ([IO.Path]::GetTempPath()) ('quest-packaging-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $temp | Out-Null
try{
 $head=(& git -C $RepoRoot rev-parse HEAD).Trim();$tree=(& git -C $RepoRoot rev-parse 'HEAD^{tree}').Trim()
 $p=New-NativeRendererAndroidPackagingInput -AppId test -PackageName io.example.test -FeatureLockSha256 ('a'*64) -SourceCommit $head -SourceTree $tree -SourceCompositionFingerprint ('d'*64) -VersionCode 61 -VersionName '0.1.61'
 $write={param($name,$value)$path=Join-Path $temp $name;[IO.File]::WriteAllText($path,($value|ConvertTo-Json -Depth 20 -Compress));@{path=$path;sha256=(Get-FileHash $path).Hash.ToLowerInvariant()}}
 $lock=& $write 'feature.json' @{app_id='test';android_manifest=@{package_name='io.example.test'}}
 $p.record.feature_lock_sha256=$lock.sha256
 $pack=& $write 'packaging.json' $p.record
 $manifest=@{app_id='test';package_name='io.example.test';app_build_lock_sha256=$lock.sha256;source_commit=$head;source_tree=$tree;source_composition_fingerprint=('d'*64);android_version_code=61;android_version_name='0.1.61';packaging_fingerprint=$pack.sha256}
 $capsule=@{schema='rusty.quest.apk_run_capsule.v1';capsule_id='test';app_id='test';app_lane='native-renderer-android';source=@{repository=$RepoRoot;commit=$head;tree=$tree;tracked_worktree_clean=$true};build_lock=$lock;build_manifest=(& $write 'manifest.json' $manifest);apk=(& $write 'test.apk' 'fixture');runtime_profile=$null;property_manifest=$null;android=@{package_name='io.example.test';activity='io.example.test/android.app.NativeActivity';version_code=61;version_name='0.1.61'};cleanup=@{policy='always-force-stop-and-restore-exact-property-snapshot';serial_exclusive_mutex=$true;restore_on_failure=$true};packaging_input_lock=$pack;packaging_fingerprint=$pack.sha256}
 # Use the canonical source-composition identity from the existing module.
 Import-Module (Join-Path $RepoRoot 'tools/lib/SourceComposition.psm1') -Force
 $capsule.source.packages=@('test');$capsule.source.dependencies=@()
 $canonical=Get-QuestBuildSourceCompositionIdentityCanonicalText -PackageName @('test') -Repository @([pscustomobject]@{repository_id='rusty-quest';role='primary';commit=$head;tree=$tree})
 $h=[Security.Cryptography.SHA256]::Create();try{$fp=([BitConverter]::ToString($h.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)))).Replace('-','').ToLowerInvariant()}finally{$h.Dispose()}
 $capsule.source.composition_fingerprint=$fp;$p.record.source_composition_fingerprint=$fp;$pack=& $write 'packaging.json' $p.record;$capsule.packaging_input_lock=$pack;$capsule.packaging_fingerprint=$pack.sha256;$manifest.source_composition_fingerprint=$fp;$manifest.packaging_fingerprint=$pack.sha256;$capsule.build_manifest=& $write 'manifest.json' $manifest
 $invoke={param($expected)$path=Join-Path $temp 'capsule.json';$capsule|ConvertTo-Json -Depth 20|Set-Content $path;& pwsh -NoProfile -File (Join-Path $RepoRoot 'tools/Test-ApkRunCapsule.ps1') -Path $path *> $null;if(($LASTEXITCODE -eq 0) -ne $expected){throw "Capsule result expected=$expected"}}
 & $invoke $true
 foreach($location in @('capsule','manifest')){
  foreach($damage in @(@{label='singleton';value=@($pack.sha256)},@{label='object';value=@{digest=$pack.sha256}},@{label='null';value=$null},@{label='bool';value=$true},@{label='integer';value=61},@{label='upper';value=$pack.sha256.ToUpperInvariant()})){
   if($location -eq 'capsule'){$capsule.packaging_fingerprint=$damage.value}else{$manifest.packaging_fingerprint=$damage.value;$capsule.build_manifest=& $write 'manifest.json' $manifest}
   & $invoke $false
   $capsule.packaging_fingerprint=$pack.sha256;$manifest.packaging_fingerprint=$pack.sha256;$capsule.build_manifest=& $write 'manifest.json' $manifest
  }
 }
 $capsule.android.version_code=62;& $invoke $false;$capsule.android.version_code=61
 $capsule.android.version_code=61.5;& $invoke $false;$capsule.android.version_code=61
 $capsule.Remove('packaging_input_lock');& $invoke $false;$capsule.packaging_input_lock=$pack
 foreach($field in @('app_id','package_name','app_build_lock_sha256','source_commit','source_tree','source_composition_fingerprint','android_version_name','packaging_fingerprint')){
  $old=$manifest[$field];$manifest[$field]='damaged';$capsule.build_manifest=& $write 'manifest.json' $manifest;& $invoke $false;$manifest[$field]=$old
 }
 $capsule.build_manifest=& $write 'manifest.json' $manifest
 $p.record.extra='injected';$capsule.packaging_input_lock=& $write 'packaging.json' $p.record;$capsule.packaging_fingerprint=$capsule.packaging_input_lock.sha256;$manifest.packaging_fingerprint=$capsule.packaging_fingerprint;$capsule.build_manifest=& $write 'manifest.json' $manifest;& $invoke $false
 $p.record.Remove('extra');$capsule.packaging_input_lock=$pack
 $capsule.Remove('packaging_input_lock');$capsule.Remove('packaging_fingerprint');$capsule.android.Remove('version_code');$capsule.android.Remove('version_name')
 # Old manifests have no new contract and may contain unrelated Spatial fields.
 $capsule.build_manifest=& $write 'manifest.json' @{legacy='fixture'};& $invoke $true;$capsule.app_lane='spatial-camera-panel-android';$capsule.unrelated_legacy_field='allowed';& $invoke $true
 Write-Output 'Packaging capsule damage and legacy compatibility PASS'
}finally{Remove-Item -LiteralPath $temp -Recurse -Force}
