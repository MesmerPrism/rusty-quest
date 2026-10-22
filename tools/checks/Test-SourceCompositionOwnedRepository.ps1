param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'))
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $RepoRoot 'tools/lib/SourceComposition.psm1') -Force
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('quest-owned-source-'+[guid]::NewGuid().ToString('N'))
$app=Join-Path $scratch 'app';$dep=Join-Path $scratch 'dependency';$owned=Join-Path $scratch 'owned-input';$untouched=Join-Path $scratch 'untouched'
$controls=[Collections.Generic.List[string]]::new()
function Check([string]$Name,[bool]$Value){if(-not $Value){throw "Control failed: $Name"};$controls.Add($Name)}
function Reject([string]$Name,[scriptblock]$Action){$rejected=$false;try{& $Action|Out-Null}catch{$rejected=$true};Check $Name $rejected}
function Invoke-FixtureGit([string]$Root,[string[]]$Arguments){$output=@(& git -C $Root @Arguments 2>&1);if($LASTEXITCODE){throw "Git fixture failed: $output"};$output}
function Write-Text([string]$Path,[string]$Value){[IO.File]::WriteAllText($Path,$Value,[Text.UTF8Encoding]::new($false))}
function Commit([string]$Root){Invoke-FixtureGit $Root @('add','--all')|Out-Null;Invoke-FixtureGit $Root @('-c','user.name=Local Fixture','-c','user.email=fixture@example.invalid','commit','--quiet','-m','Owned input fixture')|Out-Null}
function Copy-Document($Value){$Value|ConvertTo-Json -Depth 12|ConvertFrom-Json}
function Seal-Document($Value){
 $canonical=Get-QuestBuildSourceCompositionIdentityCanonicalText -PackageName $Value.packages -Repository $Value.repositories
 $Value.fingerprint=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($canonical))).ToLowerInvariant()
}
try{
 foreach($root in @($app,$dep,$owned,$untouched)){
  New-Item -ItemType Directory -Path "$root/src"|Out-Null;Invoke-FixtureGit $root @('init','--quiet')|Out-Null
  Write-Text "$root/src/input.txt" 'original input';Commit $root
 }
 Write-Text "$dep/Cargo.toml" "[package]`nname='owned-dependency'`nversion='0.1.0'`nedition='2021'`n"
 Write-Text "$dep/src/lib.rs" 'pub fn value()->u32 {1}';Commit $dep
 Write-Text "$app/Cargo.toml" "[package]`nname='owned-app'`nversion='0.1.0'`nedition='2021'`n[dependencies]`nowned-dependency={path='../dependency'}`n"
 Write-Text "$app/src/lib.rs" 'pub fn value()->u32 {owned_dependency::value()}'
 Push-Location $app
 try{& cargo generate-lockfile --offline 2>&1|Out-Null;if($LASTEXITCODE){throw 'Offline fixture lock failed'}}finally{Pop-Location}
 Commit $app
 $beforeUntouched=@(Invoke-FixtureGit $untouched @('rev-parse','HEAD')) -join "`n"
 $beforeOwned=(Get-FileHash -LiteralPath "$owned/src/input.txt").Hash
 $beforeDep=@(Invoke-FixtureGit $dep @('rev-parse','HEAD')) -join "`n"
 $cargo=Get-QuestBuildSourceComposition -RepoRoot $app -PackageName owned-app
 $originalFingerprint=$cargo.fingerprint
 $full=Add-QuestBuildOwnedRepository -Composition $cargo -Root $owned -RepositoryId owned-input
 Check 'outside_cargo_root_in_same_v1_document' ($full.schema-ceq'rusty.quest.apk_source_composition.v1'-and$full.repositories.Count-eq3-and$full.fingerprint-cne$cargo.fingerprint)
 $row=@($full.repositories|Where-Object repository_id -CEQ owned-input)[0]
 Check 'fixed_owned_role_actual_git_identity' ($row.role-ceq'app-owned-input'-and$row.commit-ceq((Invoke-FixtureGit $owned @('rev-parse','HEAD'))-join'')-and$row.tracked_worktree_clean)
 Check 'input_document_not_mutated' ($cargo.repositories.Count-eq2-and$cargo.fingerprint-ceq$originalFingerprint)
 Check 'observation_preserved_owned_source_bytes' ($beforeOwned-ceq(Get-FileHash -LiteralPath "$owned/src/input.txt").Hash)
 Assert-QuestBuildSourceCompositionCurrent -Composition $full
 Check 'clean_reobservation_accepted' $true
 Reject 'duplicate_repository_id_rejected' {Add-QuestBuildOwnedRepository -Composition $cargo -Root $owned -RepositoryId rusty-quest}
 Reject 'existing_cargo_root_not_reclassified' {Add-QuestBuildOwnedRepository -Composition $cargo -Root $dep -RepositoryId another-id}
 Check 'existing_cargo_record_role_preserved' (@($cargo.repositories|Where-Object repository -EQ $dep)[0].role-ceq'path-dependency')
 Reject 'non_root_path_rejected' {Add-QuestBuildOwnedRepository -Composition $cargo -Root "$owned/src" -RepositoryId owned-input}
 Reject 'duplicate_owned_root_rejected' {Add-QuestBuildOwnedRepository -Composition $full -Root $owned -RepositoryId another-id}
 Write-Text "$owned/src/input.txt" 'changed Kotlin-like owned input'
 Reject 'candidate_rejects_dirty_owned_root' {Add-QuestBuildOwnedRepository -Composition $cargo -Root $owned -RepositoryId owned-input}
 Reject 'old_document_detects_owned_drift' {Assert-QuestBuildSourceCompositionCurrent -Composition $full -AllowWorkingTreeChanges}
 $dirty=Add-QuestBuildOwnedRepository -Composition $cargo -Root $owned -RepositoryId owned-input -AllowWorkingTreeChanges
 Check 'tracked_owned_overlay_bound_in_v2' ($dirty.schema-ceq'rusty.quest.apk_source_composition.v2'-and$dirty.fingerprint-cne$full.fingerprint-and@($dirty.repositories|Where-Object repository_id -CEQ owned-input)[0].worktree_overlay_sha256-cmatch'^[a-f0-9]{64}$')
 Assert-QuestBuildSourceCompositionCurrent -Composition $dirty -AllowWorkingTreeChanges
 Reject 'candidate_reobservation_never_accepts_dirty' {Assert-QuestBuildSourceCompositionCurrent -Composition $dirty}
 Write-Text "$owned/new-shader.txt" 'new shader outside Cargo closure'
 $extra=Add-QuestBuildOwnedRepository -Composition $cargo -Root $owned -RepositoryId owned-input -AllowWorkingTreeChanges
 Check 'untracked_owned_bytes_change_identity' ($extra.fingerprint-cne$dirty.fingerprint)
 $forged=Copy-Document $extra
 @($forged.repositories|Where-Object repository_id -CEQ owned-input)[0].worktree_overlay_sha256='a'*64
 Seal-Document $forged
 Reject 'self_consistent_forged_overlay_rejected' {Assert-QuestBuildSourceCompositionCurrent -Composition $forged -AllowWorkingTreeChanges}
 Reject 'add_rejects_forged_base_document' {Add-QuestBuildOwnedRepository -Composition $forged -Root $untouched -RepositoryId other -AllowWorkingTreeChanges}
 $forged=Copy-Document $extra;$forged.fingerprint='a'*64
 Reject 'bad_document_fingerprint_rejected' {Assert-QuestBuildSourceCompositionCurrent -Composition $forged -AllowWorkingTreeChanges}
 $forged=Copy-Document $extra;$forged.schema='rusty.quest.apk_source_composition.v1'
 Reject 'schema_cannot_hide_overlay' {Assert-QuestBuildSourceCompositionCurrent -Composition $forged -AllowWorkingTreeChanges}
 $forged=Copy-Document $extra;@($forged.repositories|Where-Object role -CEQ primary)[0].repository_id='invented-primary';Seal-Document $forged
 Reject 'primary_identity_cannot_be_relabelled' {Assert-QuestBuildSourceCompositionCurrent -Composition $forged -AllowWorkingTreeChanges}
 $forged=Copy-Document $extra;@($forged.repositories|Where-Object repository_id -CEQ owned-input)[0].role='private';Seal-Document $forged
 Reject 'unknown_owner_role_rejected' {Assert-QuestBuildSourceCompositionCurrent -Composition $forged -AllowWorkingTreeChanges}
 $forged=Copy-Document $extra;$forged.repositories+=Copy-Document $forged.repositories[0];Seal-Document $forged
 Reject 'duplicate_document_repository_rejected' {Assert-QuestBuildSourceCompositionCurrent -Composition $forged -AllowWorkingTreeChanges}
 Commit $owned
 $next=Add-QuestBuildOwnedRepository -Composition $cargo -Root $owned -RepositoryId owned-input
 Check 'committed_owned_change_uses_v1' ($next.schema-ceq'rusty.quest.apk_source_composition.v1'-and$next.fingerprint-cne$full.fingerprint)
 Check 'observation_does_not_mutate_other_repositories' (($beforeUntouched-ceq((Invoke-FixtureGit $untouched @('rev-parse','HEAD'))-join"`n"))-and@(Invoke-FixtureGit $untouched @('status','--porcelain')).Count-eq0-and$beforeDep-ceq((Invoke-FixtureGit $dep @('rev-parse','HEAD'))-join"`n")-and@(Invoke-FixtureGit $dep @('status','--porcelain')).Count-eq0)
 [ordered]@{passed=$true;controls=$controls.ToArray();android_build_or_device_effects=$false}|ConvertTo-Json -Depth 5
}finally{
 $absolute=[IO.Path]::GetFullPath($scratch)
 if($absolute.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase)-and(Split-Path -Leaf $absolute)-match'^quest-owned-source-[a-f0-9]{32}$'){
  Remove-Item -LiteralPath $absolute -Recurse -Force
 }
}
