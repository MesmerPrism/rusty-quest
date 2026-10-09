param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'))
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $RepoRoot 'tools/lib/SourceComposition.psm1') -Force
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('quest-premerge-'+[guid]::NewGuid().ToString('N'))
$app=Join-Path $scratch 'app';$dep=Join-Path $scratch 'dependency'
New-Item -ItemType Directory -Path "$app/src","$dep/src"|Out-Null
function Invoke-FixtureGit([string]$Root,[string[]]$Arguments){$output=@(& git -C $Root @Arguments 2>&1);if($LASTEXITCODE){throw "Git fixture command failed: $output"};$output}
function Commit([string]$Root){Invoke-FixtureGit $Root @('add','--all')|Out-Null;Invoke-FixtureGit $Root @('-c','user.name=Local Fixture','-c','user.email=fixture@example.invalid','commit','-m','Local unpublished source')|Out-Null}
function Write-FixtureText([string]$Path,[string]$Value){[IO.File]::WriteAllText($Path,$Value,[Text.UTF8Encoding]::new($false))}
function Observe([bool]$Dirty=$false){Get-QuestBuildSourceComposition -RepoRoot $app -PackageName 'premerge-app' -AllowWorkingTreeChanges:$Dirty}
$controls=[Collections.Generic.List[string]]::new()
function Check([string]$Name,[bool]$Value){if(-not$Value){throw "Control failed: $Name"};$controls.Add($Name)}
function Reject([string]$Name){$rejected=$false;try{Observe|Out-Null}catch{if(-not$_.Exception.Message.Contains('working-tree changes')){throw};$rejected=$true};Check $Name $rejected}
try {
 foreach($root in @($app,$dep)){Invoke-FixtureGit $root @('init','--quiet')|Out-Null}
 Write-FixtureText "$dep/Cargo.toml" "[package]`nname='premerge-dependency'`nversion='0.1.0'`nedition='2021'`n"
 Write-FixtureText "$dep/src/lib.rs" 'pub fn value()->u32 {1}'
 Commit $dep
 Write-FixtureText "$app/Cargo.toml" "[package]`nname='premerge-app'`nversion='0.1.0'`nedition='2021'`n[dependencies]`npremerge-dependency={path='../dependency'}`n"
 Write-FixtureText "$app/src/lib.rs" 'pub fn value()->u32 {premerge_dependency::value()}'
 Push-Location $app
 try {& cargo generate-lockfile --offline 2>&1|Out-Null;if($LASTEXITCODE){throw 'Offline fixture lock failed'}}finally{Pop-Location}
 Commit $app
 Check 'no_remote_or_published_reference' (@(Invoke-FixtureGit $app @('remote')).Count-eq0-and@(Invoke-FixtureGit $dep @('remote')).Count-eq0)
 $clean=Observe
 Check 'clean_unpublished_candidate_accepted' ($clean.schema-ceq'rusty.quest.apk_source_composition.v1'-and$clean.repositories.Count-eq2-and@($clean.repositories|Where-Object{-not$_.tracked_worktree_clean}).Count-eq0)
 Write-FixtureText "$app/src/lib.rs" 'pub fn value()->u32 {premerge_dependency::value()+1}'
 Reject 'dirty_primary_publication_rejected'
 $dirty=Observe $true
 Check 'devfast_tracked_overlay_bound' ($dirty.schema-ceq'rusty.quest.apk_source_composition.v2'-and$dirty.fingerprint-cne$clean.fingerprint-and@($dirty.repositories|Where-Object{$_.role-ceq'primary'})[0].worktree_overlay_sha256-cmatch'^[0-9a-f]{64}$')
 Write-FixtureText "$app/untracked.txt" 'reviewed extra input'
 $extra=Observe $true
 Check 'untracked_input_changes_identity' ($extra.fingerprint-cne$dirty.fingerprint)
 Commit $app
 $next=Observe
 Check 'new_unpublished_commit_accepted' ($next.schema-ceq'rusty.quest.apk_source_composition.v1'-and$next.fingerprint-cne$clean.fingerprint)
 Write-FixtureText "$dep/src/lib.rs" 'pub fn value()->u32 {2}'
 Reject 'dirty_dependency_publication_rejected'
 $depDirty=Observe $true
 Check 'dependency_overlay_bound' ($depDirty.fingerprint-cne$next.fingerprint-and@($depDirty.repositories|Where-Object{$_.role-ceq'path-dependency'})[0].worktree_overlay_sha256-cmatch'^[0-9a-f]{64}$')
 Commit $dep
 $depCommit=Observe
 Check 'dependency_commit_drift_changes_identity' ($depCommit.fingerprint-cne$next.fingerprint)
 [ordered]@{passed=$true;controls=$controls.ToArray();remote_count=0;android_build_or_device_effects=$false}|ConvertTo-Json -Depth 5
} finally {
 # Only this freshly created, fixed-prefix temporary fixture is removed.
 if([IO.Path]::GetFullPath($scratch).StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase)-and(Split-Path -Leaf $scratch)-match'^quest-premerge-[a-f0-9]{32}$'){Remove-Item -LiteralPath $scratch -Recurse -Force}
}
