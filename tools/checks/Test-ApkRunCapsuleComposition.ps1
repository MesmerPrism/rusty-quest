param(
 [string]$RepoRoot=(Join-Path $PSScriptRoot '../..'),
 [string]$OutputDirectory='',
 [string]$ActualCapsulePath='',
 [switch]$ExpectRed
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $RepoRoot 'tools/lib/SourceComposition.psm1') -Force
$root=if($OutputDirectory){[IO.Path]::GetFullPath($OutputDirectory)}else{Join-Path ([IO.Path]::GetTempPath()) ('quest-capsule-composition-'+[guid]::NewGuid().ToString('N'))}
if(Test-Path -LiteralPath $root){throw 'New test output required.'}
New-Item -ItemType Directory -Path $root|Out-Null
$controls=[Collections.Generic.List[string]]::new()
function WriteJson([string]$Name,$Value){$p=Join-Path $root $Name;[IO.File]::WriteAllText($p,($Value|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false));return $p}
function Ref([string]$Path){[pscustomobject]@{path=$Path;sha256=(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}}
function CopyDoc($Value){$Value|ConvertTo-Json -Depth 20|ConvertFrom-Json -DateKind String}
function Check([string]$Name,[bool]$Value){if(-not $Value){throw "Control failed: $Name"};$controls.Add($Name)}
function Reject([string]$Name,[scriptblock]$Action){$denied=$false;try{& $Action|Out-Null}catch{$denied=$true};Check $Name $denied}
function Seal($Composition){$text=Get-QuestBuildSourceCompositionIdentityCanonicalText -PackageName @($Composition.packages) -Repository @($Composition.repositories);$Composition.fingerprint=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text))).ToLowerInvariant()}
function ReadCapsule([string]$Path){& (Join-Path $RepoRoot 'tools/Test-ApkRunCapsule.ps1') -CapsulePath $Path -ExpectedLane spatial-camera-panel-android|ConvertFrom-Json -DateKind String}
$rows=@()
foreach($name in @('quest','dependency','owned')){
 $repo=Join-Path $root $name;New-Item -ItemType Directory -Path $repo|Out-Null
 & git -C $repo init --quiet;if($LASTEXITCODE){throw 'Fixture Git init failed'}
 [IO.File]::WriteAllText((Join-Path $repo 'input.txt'),'source')
 & git -C $repo add --all;& git -C $repo -c user.name=Fixture -c user.email=fixture@example.invalid commit --quiet -m initial;if($LASTEXITCODE){throw 'Fixture Git commit failed'}
 $row=Get-QuestBuildOwnedRepositoryObservation -Root $repo -RepositoryId $name
 if($name-ceq'quest'){$row.repository_id='rusty-quest';$row.role='primary'}elseif($name-ceq'dependency'){$row.role='path-dependency'}
 $rows+=,$row
}
$composition=[pscustomobject]@{schema='rusty.quest.apk_source_composition.v1';fingerprint=('0'*64);packages=@('fixture-native');repositories=$rows};Seal $composition
$provider=WriteJson 'source-composition.json' $composition
$apk=Join-Path $root 'fixture.apk';[IO.File]::WriteAllText($apk,'modeled APK bytes; no Android build')
$lock=WriteJson 'build-input-lock.json' @{fixture=$true}
$manifest=[pscustomobject]@{application_id='org.example.fixture';package_name='org.example.fixture';apk_path=$apk;apk_sha256=(Ref $apk).sha256;build_input_lock_sha256=(Ref $lock).sha256;source_commit=$rows[0].commit;source_tree=$rows[0].tree;source_tracked_worktree_clean=$true;source_worktree_overlay_sha256='';source_composition_fingerprint=$composition.fingerprint;source_dependencies=@($rows|Where-Object role -CEQ 'path-dependency')}
$mp=WriteJson 'build-manifest.json' $manifest
$capsule=[pscustomobject]@{schema='rusty.quest.apk_run_capsule.v1';capsule_id='fixture';app_id='org.example.fixture';app_lane='spatial-camera-panel-android';source=[pscustomobject]@{repository=$rows[0].repository;commit=$rows[0].commit;tree=$rows[0].tree;tracked_worktree_clean=$true;worktree_overlay_sha256='';composition_fingerprint=$composition.fingerprint;packages=@('fixture-native');dependencies=@($rows|Where-Object role -CEQ 'path-dependency');provider_document=(Ref $provider)};build_lock=(Ref $lock);build_manifest=(Ref $mp);apk=(Ref $apk);runtime_profile=$null;property_manifest=$null;android=@{package_name='org.example.fixture';activity='org.example.fixture/.Main'};cleanup=@{policy='always-force-stop-and-restore-exact-property-snapshot';serial_exclusive_mutex=$true;restore_on_failure=$true}}
$cp=WriteJson 'run-capsule.json' $capsule
if($ExpectRed){
 $message='';try{ReadCapsule $cp|Out-Null}catch{$message=$_.Exception.Message}
 Check 'base_reader_rejects_full_provider_fingerprint' ($message-like'*source composition fingerprint mismatch*')
 [pscustomobject]@{status='expected-red';controls=$controls;error=$message}|ConvertTo-Json;exit 0
}
Check 'full_owned_provider_document_passes' ((ReadCapsule $cp).status-ceq'pass')

function TestBadCapsule([string]$Name,[scriptblock]$Change){
 $bad=CopyDoc $capsule;& $Change $bad;Reject $Name {ReadCapsule (WriteJson ($Name+'.capsule.json') $bad)}
}
TestBadCapsule 'wrong_provider_hash_refused' {param($v)$v.source.provider_document.sha256='0'*64}
TestBadCapsule 'missing_provider_refused' {param($v)$v.source.provider_document.path=Join-Path $root 'missing.json'}
TestBadCapsule 'wrong_capsule_fingerprint_refused' {param($v)$v.source.composition_fingerprint='0'*64}
TestBadCapsule 'wrong_source_packages_refused' {param($v)$v.source.packages=@('other-native')}
TestBadCapsule 'duplicate_source_packages_refused' {param($v)$v.source.packages=@('fixture-native','fixture-native')}
TestBadCapsule 'wrong_android_package_refused' {param($v)$v.android.package_name='org.example.other'}
TestBadCapsule 'wrong_app_id_refused' {param($v)$v.app_id='org.example.other'}
TestBadCapsule 'primary_root_projection_refused' {param($v)$v.source.repository=$rows[1].repository}
TestBadCapsule 'primary_clean_type_refused' {param($v)$v.source.tracked_worktree_clean='true'}
TestBadCapsule 'missing_primary_overlay_field_refused' {param($v)$v.source.PSObject.Properties.Remove('worktree_overlay_sha256')}
TestBadCapsule 'dependency_root_projection_refused' {param($v)$v.source.dependencies[0].repository=$rows[2].repository}
TestBadCapsule 'dependency_role_projection_refused' {param($v)$v.source.dependencies[0].role='app-owned-input'}
TestBadCapsule 'missing_dependency_refused' {param($v)$v.source.dependencies=@()}
TestBadCapsule 'duplicate_dependency_refused' {param($v)$v.source.dependencies=@($v.source.dependencies[0],$v.source.dependencies[0])}
foreach($name in @('missing_owned','changed_owned','invalid_owned_role','duplicate_owned_id','duplicate_owned_root')){
 $badProvider=CopyDoc $composition
 switch($name){
  missing_owned {$badProvider.repositories=@($badProvider.repositories|Where-Object role -CNE 'app-owned-input')}
  changed_owned {($badProvider.repositories|Where-Object role -CEQ 'app-owned-input').commit='0'*40}
  invalid_owned_role {($badProvider.repositories|Where-Object role -CEQ 'app-owned-input').role='unrecognized'}
  duplicate_owned_id {($badProvider.repositories|Where-Object role -CEQ 'app-owned-input').repository_id='rusty-quest'}
  duplicate_owned_root {($badProvider.repositories|Where-Object role -CEQ 'app-owned-input').repository=$rows[0].repository}
 }
 $providerRef=Ref (WriteJson ($name+'.provider.json') $badProvider);$bad=CopyDoc $capsule;$bad.source.provider_document=$providerRef
 Reject ($name+'_refused') {ReadCapsule (WriteJson ($name+'.capsule.json') $bad)}
}
# Even a caller-recomputed document cannot invent a repository commit/tree.
$badProvider=CopyDoc $composition;($badProvider.repositories|Where-Object role -CEQ 'app-owned-input').commit='0'*40;Seal $badProvider
$bad=CopyDoc $capsule;$bad.source.provider_document=Ref (WriteJson 'invented-owned.provider.json' $badProvider);$bad.source.composition_fingerprint=$badProvider.fingerprint
$badManifest=CopyDoc $manifest;$badManifest.source_composition_fingerprint=$badProvider.fingerprint;$bad.build_manifest=Ref (WriteJson 'invented-owned.manifest.json' $badManifest)
Reject 'recomputed_owned_without_actual_commit_refused' {ReadCapsule (WriteJson 'invented-owned.capsule.json' $bad)}
foreach($field in @('source_commit','source_tree','source_composition_fingerprint','application_id','package_name','apk_sha256','build_input_lock_sha256')){
 $badManifest=CopyDoc $manifest;$badManifest.$field='wrong';$bad=CopyDoc $capsule;$bad.build_manifest=Ref (WriteJson ('bad-'+$field+'.manifest.json') $badManifest)
 Reject ('manifest_'+$field+'_refused') {ReadCapsule (WriteJson ('bad-'+$field+'.capsule.json') $bad)}
}
$badManifest=CopyDoc $manifest;$badManifest.source_dependencies[0].repository=$rows[2].repository;$bad=CopyDoc $capsule;$bad.build_manifest=Ref (WriteJson 'bad-manifest-dep.json' $badManifest)
Reject 'manifest_dependency_projection_refused' {ReadCapsule (WriteJson 'bad-manifest-dep.capsule.json' $bad)}
$legacy=CopyDoc $capsule
foreach($key in @('provider_document','composition_fingerprint','packages','dependencies')){$legacy.source.PSObject.Properties.Remove($key)}
Check 'legacy_without_composition_is_conserved' ((ReadCapsule (WriteJson 'legacy-no-composition.json' $legacy)).status-ceq'pass')
$legacy=CopyDoc $capsule;$legacy.source.PSObject.Properties.Remove('provider_document')
$projection=CopyDoc $composition;$projection.repositories=@($projection.repositories|Where-Object role -CNE 'app-owned-input');Seal $projection;$legacy.source.composition_fingerprint=$projection.fingerprint
Check 'legacy_projected_composition_without_provider_is_conserved' ((ReadCapsule (WriteJson 'legacy-composition.json' $legacy)).status-ceq'pass')
if($ActualCapsulePath){
 $before=Ref $ActualCapsulePath;$actual=ReadCapsule $ActualCapsulePath;Check 'actual_selected_capsule_passes_without_rewriting' ($actual.status-ceq'pass'-and (Ref $ActualCapsulePath).sha256-ceq$before.sha256)
 WriteJson 'ACTUAL_VALIDATION.json' $actual|Out-Null
}
$result=[pscustomobject]@{schema='rusty.quest.capsule_composition_host_test.v1';status='passed';control_count=$controls.Count;controls=$controls;actual_capsule=if($ActualCapsulePath){Ref $ActualCapsulePath}else{$null};models=@('small APK/build-lock/manifest files in fixture','fixture repository/package identities');device_calls=0;apk_built=$false}
WriteJson 'RESULT.json' $result|Out-Null
$result|ConvertTo-Json -Depth 6
