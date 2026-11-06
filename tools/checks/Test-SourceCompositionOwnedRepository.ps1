param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'))
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$sourceModule=Import-Module (Join-Path $RepoRoot 'tools/lib/SourceComposition.psm1') -Force -PassThru
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('quest-owned-source-'+[guid]::NewGuid().ToString('N'))
$app=Join-Path $scratch 'app';$dep=Join-Path $scratch 'dependency';$owned=Join-Path $scratch 'owned-input';$untouched=Join-Path $scratch 'untouched';$java=Join-Path $scratch 'java-app'
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
 foreach($root in @($app,$dep,$owned,$untouched,$java)){
  New-Item -ItemType Directory -Path "$root/src"|Out-Null;Invoke-FixtureGit $root @('init','--quiet')|Out-Null
  Write-Text "$root/src/input.txt" 'original input';Commit $root
 }
 # The private owner can be observed before a cold Cargo graph exists.
 $standalone=Get-QuestBuildOwnedRepositoryObservation -Root $owned -RepositoryId owned-input
 $standaloneAgain=Get-QuestBuildOwnedRepositoryObservation -Root $owned -RepositoryId owned-input
 Check 'standalone_before_cargo_graph_is_stable_flat_row' (($standalone|ConvertTo-Json -Compress)-ceq($standaloneAgain|ConvertTo-Json -Compress)-and
   (($standalone.PSObject.Properties.Name|Sort-Object)-join',')-ceq'commit,repository,repository_id,role,tracked_worktree_clean,tree,worktree_overlay_sha256')
 Reject 'standalone_rejects_non_root' {Get-QuestBuildOwnedRepositoryObservation -Root "$owned/src" -RepositoryId owned-input}
 Reject 'standalone_rejects_invalid_logical_id' {Get-QuestBuildOwnedRepositoryObservation -Root $owned -RepositoryId invalid.id}
 # A Git-only application must bind real source without consulting Cargo at all.
 # This refusal spy cannot provide successful/fabricated metadata and is removed
 # before the unchanged native fixture below resolves its actual Cargo graph.
 & $sourceModule { $script:applicationCargoCalls=0;function script:cargo {$script:applicationCargoCalls++;throw 'Git-only application fixture forbids Cargo.'} }
 try{
  Reject 'application_cargo_refusal_spy_is_live' {& $sourceModule {cargo}}
  Check 'application_cargo_refusal_was_observed' ((& $sourceModule {$script:applicationCargoCalls})-eq1)
  & $sourceModule {$script:applicationCargoCalls=0}
  $applicationId='io.github.rustyquest.feedback'
  $javaComposition=Get-QuestBuildSourceComposition -RepoRoot $java -ApplicationId $applicationId
  Check 'application_has_no_cargo_manifest' (-not(Test-Path -LiteralPath "$java/Cargo.toml"))
  $javaHead=(Invoke-FixtureGit $java @('rev-parse','HEAD'))-join''
  $javaTree=(Invoke-FixtureGit $java @('rev-parse','HEAD^{tree}'))-join''
  $javaRow=$javaComposition.repositories[0]
  Check 'application_selects_exact_primary_git_row_and_closed_document' ($javaComposition.schema-ceq'rusty.quest.apk_source_composition.v1'-and
   (($javaComposition.PSObject.Properties.Name|Sort-Object)-join',')-ceq'fingerprint,packages,repositories,schema'-and
   $javaComposition.repositories.Count-eq1-and$javaRow.repository_id-ceq'rusty-quest'-and$javaRow.role-ceq'primary'-and
   $javaRow.repository-ieq[IO.Path]::GetFullPath($java)-and$javaRow.commit-ceq$javaHead-and$javaRow.tree-ceq$javaTree-and
   $javaRow.tracked_worktree_clean-and$javaRow.worktree_overlay_sha256-ceq''-and$javaComposition.packages.Count-eq1-and$javaComposition.packages[0]-ceq$applicationId)
  $packageField=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($applicationId))
  $expectedCanonical="schema=rusty.quest.apk_source_composition_identity.v1`npackage=$packageField`nrepository=cnVzdHktcXVlc3Q=:cHJpbWFyeQ==:${javaHead}:$javaTree"
  $expectedFingerprint=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($expectedCanonical))).ToLowerInvariant()
  Check 'application_fingerprint_matches_independent_git_identity_encoding' ($javaComposition.fingerprint-ceq$expectedFingerprint)
  $javaAgain=Get-QuestBuildSourceComposition -RepoRoot $java -ApplicationId $applicationId
  Check 'application_clean_reobservation_is_exact' (($javaAgain|ConvertTo-Json -Depth 5 -Compress)-ceq($javaComposition|ConvertTo-Json -Depth 5 -Compress))
  Assert-QuestBuildSourceCompositionCurrent -Composition $javaComposition
  Reject 'application_rejects_nested_non_git_root' {Get-QuestBuildSourceComposition -RepoRoot "$java/src" -ApplicationId $applicationId}
  foreach($badId in @('undotted','9invalid.package','io.bad-name','io..empty','io.valid/escape')){
   Reject ('application_rejects_bad_identity_'+$badId) {Get-QuestBuildSourceComposition -RepoRoot $java -ApplicationId $badId}
  }
  Reject 'application_and_native_selection_are_mutually_exclusive' {Get-QuestBuildSourceComposition -RepoRoot $java -ApplicationId $applicationId -PackageName invented-cargo}
  $forged=Copy-Document $javaComposition;$forged.repositories[0].commit='a'*40;Seal-Document $forged
  Reject 'application_forged_commit_current_identity_rejected' {Assert-QuestBuildSourceCompositionCurrent -Composition $forged}
  $forged=Copy-Document $javaComposition;$forged.repositories[0].tree='b'*40;Seal-Document $forged
  Reject 'application_forged_tree_current_identity_rejected' {Assert-QuestBuildSourceCompositionCurrent -Composition $forged}
  Write-Text "$java/src/input.txt" 'changed Java-only source'
  Reject 'application_candidate_rejects_dirty_git_source' {Get-QuestBuildSourceComposition -RepoRoot $java -ApplicationId $applicationId}
  Reject 'application_old_identity_detects_tracked_drift' {Assert-QuestBuildSourceCompositionCurrent -Composition $javaComposition -AllowWorkingTreeChanges}
  $javaDirty=Get-QuestBuildSourceComposition -RepoRoot $java -ApplicationId $applicationId -AllowWorkingTreeChanges
  Check 'application_explicit_dirty_overlay_uses_existing_v2' ($javaDirty.schema-ceq'rusty.quest.apk_source_composition.v2'-and
   -not$javaDirty.repositories[0].tracked_worktree_clean-and$javaDirty.repositories[0].worktree_overlay_sha256-cmatch'^[a-f0-9]{64}$'-and$javaDirty.fingerprint-cne$javaComposition.fingerprint)
  Assert-QuestBuildSourceCompositionCurrent -Composition $javaDirty -AllowWorkingTreeChanges
  Reject 'application_dirty_identity_cannot_claim_clean_candidate' {Assert-QuestBuildSourceCompositionCurrent -Composition $javaDirty}
  $forged=Copy-Document $javaDirty;$forged.repositories[0].worktree_overlay_sha256='c'*64;Seal-Document $forged
  Reject 'application_forged_self_consistent_overlay_rejected' {Assert-QuestBuildSourceCompositionCurrent -Composition $forged -AllowWorkingTreeChanges}
  Write-Text "$java/src/NewView.java" 'final class NewView {}'
  $javaAdded=Get-QuestBuildSourceComposition -RepoRoot $java -ApplicationId $applicationId -AllowWorkingTreeChanges
  Check 'application_untracked_source_changes_existing_overlay' ($javaAdded.fingerprint-cne$javaDirty.fingerprint-and$javaAdded.repositories[0].worktree_overlay_sha256-cne$javaDirty.repositories[0].worktree_overlay_sha256)
  Commit $java
  Reject 'application_old_identity_detects_commit_and_tree_drift' {Assert-QuestBuildSourceCompositionCurrent -Composition $javaComposition}
  $javaNext=Get-QuestBuildSourceComposition -RepoRoot $java -ApplicationId $applicationId
  Check 'application_committed_source_uses_new_v1_git_identity' ($javaNext.schema-ceq'rusty.quest.apk_source_composition.v1'-and$javaNext.repositories[0].commit-cne$javaHead-and$javaNext.repositories[0].tree-cne$javaTree-and$javaNext.fingerprint-cne$javaComposition.fingerprint)
  Assert-QuestBuildSourceCompositionCurrent -Composition $javaNext
  $applicationCargoCalls=& $sourceModule {$script:applicationCargoCalls}
  Check 'application_path_never_invokes_cargo' ($applicationCargoCalls-eq0)
 }finally{
  # Reloading the actual module removes the test-only refusal spy and counter.
  $sourceModule=Import-Module (Join-Path $RepoRoot 'tools/lib/SourceComposition.psm1') -Force -PassThru
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
 Check 'standalone_row_equals_composed_raw_row' (($standalone|ConvertTo-Json -Compress)-ceq($row|ConvertTo-Json -Compress))
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
 Reject 'standalone_candidate_rejects_dirty_root' {Get-QuestBuildOwnedRepositoryObservation -Root $owned -RepositoryId owned-input}
 $standaloneDirty=Get-QuestBuildOwnedRepositoryObservation -Root $owned -RepositoryId owned-input -AllowWorkingTreeChanges
 Check 'standalone_tracked_drift_changes_same_overlay_algorithm' (-not$standaloneDirty.tracked_worktree_clean-and$standaloneDirty.worktree_overlay_sha256-cne$standalone.worktree_overlay_sha256)
 Reject 'candidate_rejects_dirty_owned_root' {Add-QuestBuildOwnedRepository -Composition $cargo -Root $owned -RepositoryId owned-input}
 Reject 'old_document_detects_owned_drift' {Assert-QuestBuildSourceCompositionCurrent -Composition $full -AllowWorkingTreeChanges}
 $dirty=Add-QuestBuildOwnedRepository -Composition $cargo -Root $owned -RepositoryId owned-input -AllowWorkingTreeChanges
 Check 'standalone_dirty_row_equals_composed_raw_row' (($standaloneDirty|ConvertTo-Json -Compress)-ceq(@($dirty.repositories|Where-Object repository_id -CEQ owned-input)[0]|ConvertTo-Json -Compress))
 Check 'tracked_owned_overlay_bound_in_v2' ($dirty.schema-ceq'rusty.quest.apk_source_composition.v2'-and$dirty.fingerprint-cne$full.fingerprint-and@($dirty.repositories|Where-Object repository_id -CEQ owned-input)[0].worktree_overlay_sha256-cmatch'^[a-f0-9]{64}$')
 Assert-QuestBuildSourceCompositionCurrent -Composition $dirty -AllowWorkingTreeChanges
 Reject 'candidate_reobservation_never_accepts_dirty' {Assert-QuestBuildSourceCompositionCurrent -Composition $dirty}
 Write-Text "$owned/new-shader.txt" 'new shader outside Cargo closure'
 $standaloneExtra=Get-QuestBuildOwnedRepositoryObservation -Root $owned -RepositoryId owned-input -AllowWorkingTreeChanges
 Check 'standalone_untracked_addition_changes_overlay' ($standaloneExtra.worktree_overlay_sha256-cne$standaloneDirty.worktree_overlay_sha256)
 Remove-Item -LiteralPath "$owned/src/input.txt"
 $standaloneDeleted=Get-QuestBuildOwnedRepositoryObservation -Root $owned -RepositoryId owned-input -AllowWorkingTreeChanges
 Check 'standalone_tracked_deletion_changes_overlay' ($standaloneDeleted.worktree_overlay_sha256-cne$standaloneExtra.worktree_overlay_sha256)
 Write-Text "$owned/src/input.txt" 'changed Kotlin-like owned input'
 $standaloneRestored=Get-QuestBuildOwnedRepositoryObservation -Root $owned -RepositoryId owned-input -AllowWorkingTreeChanges
 Check 'standalone_restored_bytes_restore_raw_observation' (($standaloneRestored|ConvertTo-Json -Compress)-ceq($standaloneExtra|ConvertTo-Json -Compress))
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
 [ordered]@{passed=$true;controls=$controls.ToArray();application_cargo_invocations=$applicationCargoCalls;android_build_or_device_effects=$false}|ConvertTo-Json -Depth 5
}finally{
 $absolute=[IO.Path]::GetFullPath($scratch)
 if($absolute.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase)-and(Split-Path -Leaf $absolute)-match'^quest-owned-source-[a-f0-9]{32}$'){
  Remove-Item -LiteralPath $absolute -Recurse -Force
 }
}
