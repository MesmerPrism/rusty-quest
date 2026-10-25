param([string]$RepoRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),[string]$CapsuleReaderPath='',[string]$HarnessRoot='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $RepoRoot 'tools/lib/SourceComposition.psm1') -Force
Import-Module (Join-Path $RepoRoot 'tools/lib/AndroidComponentClosure.psm1') -Force
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $RepoRoot 'tools/Build-SpatialCameraPanelAndroid.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Builder parsing failed.'}
$block=$ast.Find({param($node) $node -is [Management.Automation.Language.IfStatementAst] -and
    $node.Extent.Text.StartsWith('if (-not [string]::IsNullOrWhiteSpace($AndroidComponentContractPath))')},$true)
if($null -eq $block){throw 'Actual owned Android source callsite missing.'}
if(-not$CapsuleReaderPath){$CapsuleReaderPath=Join-Path $RepoRoot 'tools/Test-ApkRunCapsule.ps1'}
$CapsuleReaderPath=(Resolve-Path -LiteralPath $CapsuleReaderPath).Path
$retainHarness=[bool]$HarnessRoot
$scratch=if($HarnessRoot){[IO.Path]::GetFullPath($HarnessRoot)}else{Join-Path ([IO.Path]::GetTempPath()) ('quest-component-owner-'+[guid]::NewGuid().ToString('N'))}
if(Test-Path -LiteralPath $scratch){throw 'Fresh owned-source fixture required.'}
$completed=$false;$cases=[Collections.Generic.List[object]]::new()
$app=Join-Path $scratch 'app';$owner=Join-Path $scratch 'Owner.Repo'
New-Item -ItemType Directory -Path "$app/src","$owner/src" -Force | Out-Null
function Invoke-FixtureGit([string]$Root,[string[]]$Arguments){$output=@(& git -C $Root @Arguments 2>&1);if($LASTEXITCODE){throw 'Fixture Git operation failed.'};$output}
function Commit([string]$Root){$null=Invoke-FixtureGit $Root @('add','--all');$null=Invoke-FixtureGit $Root @('-c','user.name=Local Fixture','-c','user.email=fixture@example.invalid','commit','-qm','Local fixture')}
function Write-Text([string]$Path,[string]$Text){[IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false))}
function Write-Json([string]$Path,$Value){Write-Text $Path ($Value|ConvertTo-Json -Depth 24)}
function File-Record([string]$Path){[ordered]@{path=$Path;sha256=(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}}
function Copy-Document($Value){$Value|ConvertTo-Json -Depth 24|ConvertFrom-Json}
function Invoke-CapsuleCase([string]$CaseName,$Value,[string]$Expected=''){
    $path=Join-Path $scratch ($CaseName+'.capsule.json');Write-Json $path $Value
    $stdoutPath=Join-Path $scratch ($CaseName+'.stdout');$stderrPath=Join-Path $scratch ($CaseName+'.stderr')
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=@(Get-Command pwsh -CommandType Application)[0].Source
    $info.UseShellExecute=$false;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($arg in @('-NoProfile','-NonInteractive','-File',$CapsuleReaderPath,'-CapsulePath',$path,'-ExpectedLane','spatial-camera-panel-android')){$info.ArgumentList.Add($arg)}
    $child=[Diagnostics.Process]::new();$child.StartInfo=$info
    $stdout=[IO.File]::Open($stdoutPath,'CreateNew','Write','None');$stderr=[IO.File]::Open($stderrPath,'CreateNew','Write','None')
    try{
        if(-not$child.Start()){throw 'Capsule reader did not start.'}
        $outTask=$child.StandardOutput.BaseStream.CopyToAsync($stdout);$errTask=$child.StandardError.BaseStream.CopyToAsync($stderr)
        $child.WaitForExit();[void]$outTask.GetAwaiter().GetResult();[void]$errTask.GetAwaiter().GetResult();$code=$child.ExitCode
        $stdout.Flush($true);$stderr.Flush($true)
    }finally{$stdout.Dispose();$stderr.Dispose();$child.Dispose()}
    $errorText=[IO.File]::ReadAllText($stderrPath);$display=([regex]::Replace($errorText,'\x1b\[[0-9;]*m','')-replace'\s+',' ').Trim()
    $cases.Add([ordered]@{name=$CaseName;exit_code=$code;stdout=(File-Record $stdoutPath);stderr=(File-Record $stderrPath);process_reaped=$true;stdout_eof=$true;stderr_eof=$true})
    if($Expected){if($code-eq0-or$display-notmatch$Expected){throw "Wrong capsule refusal for ${CaseName}: $display"}}
    elseif($code-ne0){throw "Producer-to-capsule reader refused ${CaseName}: $display"}
    else{
        $result=[IO.File]::ReadAllText($stdoutPath)|ConvertFrom-Json
        if($result.status-cne'pass'-or$result.capsule_sha256-cne(File-Record $path).sha256){throw 'Capsule CLI pass did not bind the actual fixture.'}
    }
}
function Freeze-App {
    Push-Location $app
    try{& cargo generate-lockfile --offline 2>&1 | Out-Null;if($LASTEXITCODE){throw 'Fixture lock failed.'}}finally{Pop-Location}
    Commit $app
}
try {
    foreach($root in @($app,$owner)){$null=Invoke-FixtureGit $root @('init','--quiet');$null=Invoke-FixtureGit $root @('config','core.autocrlf','false')}
    Write-Text "$app/src/lib.rs" 'pub fn value() {}'
    Write-Text "$owner/src/lib.rs" 'pub fn owner() {}'
    Write-Text "$owner/Cargo.toml" "[package]`nname='fixture-owner'`nversion='0.1.0'`nedition='2021'`n"
    Write-Text "$owner/contract.json" '{}'
    Write-Text "$owner/.gitignore" '.local/'
    Commit $owner
    $dependencies="[dependencies]`n"
    foreach($number in 1..4){
        $dependency=Join-Path $scratch ('dependency-'+$number);[void](New-Item -ItemType Directory -Path (Join-Path $dependency 'src'))
        $null=Invoke-FixtureGit $dependency @('init','--quiet');$null=Invoke-FixtureGit $dependency @('config','core.autocrlf','false')
        Write-Text (Join-Path $dependency 'Cargo.toml') "[package]`nname='fixture-dependency-$number'`nversion='0.1.0'`nedition='2021'`n"
        Write-Text (Join-Path $dependency 'src/lib.rs') 'pub fn value() {}';Commit $dependency
        $dependencies+="fixture-dependency-$number={path='../dependency-$number'}`n"
    }
    $manifest="[package]`nname='fixture-app'`nversion='0.1.0'`nedition='2021'`n"+$dependencies
    Write-Text "$app/Cargo.toml" $manifest
    Freeze-App
    $sourceComposition=Get-QuestBuildSourceComposition -RepoRoot $app -PackageName fixture-app
    $cargoSourceComposition=$sourceComposition
    $resolvedPrivateFeatureSourceDir=Join-Path $owner 'src';$AndroidComponentContractPath=Join-Path $owner 'contract.json'
    $PublicationBuild=$false;$BuildMode='DevFast'
    Invoke-Expression $block.Extent.Text
    $added=@($sourceComposition.repositories | Where-Object role -CEQ 'app-owned-input')
    if($added.Count -ne 1 -or $added[0].repository_id -cne 'owner-repo' -or $cargoSourceComposition.repositories.Count -ne 5 -or $sourceComposition.repositories.Count-ne6){throw 'Actual six-owner callsite failed or changed the five-row Cargo document.'}
    if((Get-AndroidComponentOwnerRepositoryId (Join-Path $scratch '123.Owner')) -cne 'android-owner-123-owner'){throw 'Nonletter owner identity is invalid.'}
    # Evaluate the producer's actual primary/dependency assignments and source
    # table, not a mirrored primary-plus-dependencies fingerprint algorithm.
    foreach($variable in @('primarySource','sourceHead','sourceTree','sourceTrackedWorktreeClean','sourceWorktreeOverlaySha256','sourceDependencies','sourceDependencyIdentities')){
        $assignment=@($ast.EndBlock.Statements|Where-Object{$_-is[Management.Automation.Language.AssignmentStatementAst]-and$_.Left.Extent.Text-ceq('$'+$variable)})
        if($assignment.Count-ne1){throw "Actual source projection assignment missing: $variable"}
        if($variable-ceq'primarySource'){$projectionOffset=$assignment[0].Extent.StartOffset}
        . ([scriptblock]::Create($assignment[0].Extent.Text))
    }
    if($sourceDependencies.Count-ne4-or$sourceDependencyIdentities.Count-ne4){throw 'App-owned input leaked into Cargo dependency/cache projection.'}
    $sourceCompositionPath=Join-Path $scratch 'source-composition.json';Write-Json $sourceCompositionPath $sourceComposition
    $sourceTables=@($ast.FindAll({param($n)$n-is[Management.Automation.Language.HashtableAst]-and@($n.KeyValuePairs|Where-Object{$_.Item1-is[Management.Automation.Language.StringConstantExpressionAst]-and$_.Item1.Value-ceq'provider_document'}).Count-eq1},$true))
    if($sourceTables.Count-ne1){throw 'Actual capsule source provider expression missing.'}
    $shaFunction=$ast.Find({param($n)$n-is[Management.Automation.Language.FunctionDefinitionAst]-and$n.Name-ceq'Get-FileSha256'},$true)
    . ([scriptblock]::Create($shaFunction.Extent.Text))
    $producerRepoRoot=$RepoRoot
    try{$repoRoot=$app;$capsuleSource=& ([scriptblock]::Create($sourceTables[0].Extent.Text))}finally{$RepoRoot=$producerRepoRoot}
    # Explicit neutral artifact leaves: no APK was compiled, signed or qualified.
    $apk=Join-Path $scratch 'neutral.apk';$buildInputLockPath=Join-Path $scratch 'neutral-lock.json'
    Write-Text $apk 'neutral file-only artifact fixture';Write-Json $buildInputLockPath @{fixture='neutral'}
    $sha256=(File-Record $apk).sha256;$resolvedAppId='org.example.composition.fixture'
    $manifestAssignments=@($ast.EndBlock.Statements|Where-Object{$_-is[Management.Automation.Language.AssignmentStatementAst]-and$_.Left.Extent.Text-ceq'$manifest'})
    if($manifestAssignments.Count-ne1){throw 'Actual build manifest expression missing.'}
    $manifestTable=@($manifestAssignments[0].Right.FindAll({param($n)$n-is[Management.Automation.Language.HashtableAst]},$true))[0]
    $neutralManifest=[ordered]@{fixture='neutral artifact metadata only; no build or qualification'}
    foreach($field in @('source_commit','source_tree','source_tracked_worktree_clean','source_worktree_overlay_sha256','source_composition_fingerprint','source_dependencies','package_name','application_id','apk_sha256','build_input_lock_sha256')){
        $pair=@($manifestTable.KeyValuePairs|Where-Object{$_.Item1.Value-ceq$field})
        if($pair.Count-ne1){throw "Actual manifest source join missing: $field"}
        $neutralManifest[$field]=& ([scriptblock]::Create($pair[0].Item2.Extent.Text))
    }
    $manifestPath=Join-Path $scratch 'neutral-manifest.json';Write-Json $manifestPath $neutralManifest
    $capsule=[ordered]@{schema='rusty.quest.apk_run_capsule.v1';capsule_id='producer-composition-fixture';app_id=$resolvedAppId;app_lane='spatial-camera-panel-android';source=$capsuleSource;build_lock=(File-Record $buildInputLockPath);build_manifest=(File-Record $manifestPath);apk=(File-Record $apk);runtime_profile=$null;property_manifest=$null;android=@{package_name=$resolvedAppId;activity=($resolvedAppId+'/.Main')};cleanup=@{policy='always-force-stop-and-restore-exact-property-snapshot';serial_exclusive_mutex=$true;restore_on_failure=$true}}
    Invoke-CapsuleCase 'six-provider-five-projection' $capsule
    $bad=Copy-Document $capsule;$bad.source.provider_document.sha256='0'*64
    Invoke-CapsuleCase 'provider-raw-drift' $bad 'source composition provider hash mismatch:'
    $bad=Copy-Document $capsule;$bad.source.dependencies=@($bad.source.dependencies|Select-Object -Skip 1)
    Invoke-CapsuleCase 'dependency-omission' $bad 'provider dependency projection differs'
    $bad=Copy-Document $capsule;$bad.source.packages=@('wrong-package')
    Invoke-CapsuleCase 'package-drift' $bad 'provider package selection differs'
    $bad=Copy-Document $capsule;$bad.source.tree='0'*40
    Invoke-CapsuleCase 'primary-tree-drift' $bad 'primary source commit/tree binding is invalid'
    $precheck=@($ast.EndBlock.Statements|Where-Object{$_-is[Management.Automation.Language.PipelineAst]-and$_.Extent.Text-ceq'Assert-QuestBuildSourceCompositionIdentity -Composition $sourceComposition'})
    $providerWrite=@($ast.FindAll({param($n)$n-is[Management.Automation.Language.CommandAst]-and$n.GetCommandName()-ceq'Set-TextFileIfChanged'-and$n.Extent.Text.Contains('-Path $sourceCompositionPath',[StringComparison]::Ordinal)},$true))
    $nativeStart=@($ast.EndBlock.Statements|Where-Object{$_-is[Management.Automation.Language.AssignmentStatementAst]-and$_.Left.Extent.Text-ceq'$nativeStopwatch'})
    if($precheck.Count-ne1-or$providerWrite.Count-ne1-or$nativeStart.Count-ne1-or$precheck[0].Extent.StartOffset-le$block.Extent.EndOffset-or$precheck[0].Extent.StartOffset-ge$projectionOffset-or$precheck[0].Extent.StartOffset-ge$providerWrite[0].Extent.StartOffset-or$precheck[0].Extent.StartOffset-ge$nativeStart[0].Extent.StartOffset){throw 'Full-source identity precheck must follow owned-source composition before projection/materialization/dispatch.'}
    . ([scriptblock]::Create($precheck[0].Extent.Text))
    $saved=$sourceComposition.fingerprint;$sourceComposition.fingerprint='0'*64;$refused=$false
    try{. ([scriptblock]::Create($precheck[0].Extent.Text))}catch{if($_.Exception.Message-cne'APK source composition fingerprint differs from its document.'){throw};$refused=$true}finally{$sourceComposition.fingerprint=$saved}
    if(-not$refused){throw 'Actual early structural precheck accepted a damaged full document.'}
    $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'New-ExternalArtifactDirectory'},$true)
    Invoke-Expression $definition.Extent.Text
    $exclusions=$ast.Find({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and
        $node.Left -is [Management.Automation.Language.VariableExpressionAst] -and $node.Left.VariablePath.UserPath -ceq 'excludedArtifactRoots'},$true)
    $resolvedPrivateFeatureAssetDir='';$resolvedPrivateFeatureResourceDir='';$BuildCacheRoot=Join-Path $scratch 'cache';$SharedGradleToolRoot='';$NativeRepoRoot=''
    Invoke-Expression $exclusions.Extent.Text
    $artifactParent=Join-Path $owner '.local/artifacts'
    New-Item -ItemType Directory -Path $artifactParent -Force | Out-Null
    $artifactOut=Join-Path $artifactParent 'build'
    $ArtifactOutputParent=$artifactParent;$OutDir=$artifactOut
    $artifactGuard=$ast.Find({param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -ceq 'Assert-AndroidComponentArtifactRoot'},$true)
    Invoke-Expression $artifactGuard.Extent.Text
    $null=New-ExternalArtifactDirectory -Parent $artifactParent -Output $artifactOut -ExcludedRoots $excludedArtifactRoots -Mode DevFast
    if(Test-Path -LiteralPath $artifactOut){throw 'Artifact preflight mutated output.'}
    $ArtifactOutputParent=Join-Path $owner 'docs';$OutDir=Join-Path $ArtifactOutputParent 'fresh-output'
    New-Item -ItemType Directory -Path $ArtifactOutputParent | Out-Null
    try{Invoke-Expression $artifactGuard.Extent.Text;throw 'Nonignored owner output accepted.'}
    catch{if($_.Exception.Message -notlike '*must be Git-ignored*'){throw}}
    $ArtifactOutputParent=$scratch
    try{Invoke-Expression $artifactGuard.Extent.Text;throw 'Outside parent bypassed owned output guard.'}
    catch{if($_.Exception.Message -notlike '*must have a Git-ignored owner parent*'){throw}}
    try{$null=New-ExternalArtifactDirectory -Parent $resolvedPrivateFeatureSourceDir -Output (Join-Path $resolvedPrivateFeatureSourceDir 'build') -ExcludedRoots $excludedArtifactRoots -Mode DevFast;throw 'Actual private source overlap accepted.'}
    catch{if($_.Exception.Message -notlike '*must not overlap*'){throw}}
    Write-Text "$app/Cargo.toml" ($manifest+"fixture-owner={path='../Owner.Repo'}`n")
    Freeze-App
    $sourceComposition=Get-QuestBuildSourceComposition -RepoRoot $app -PackageName fixture-app
    $cargoSourceComposition=$sourceComposition;$before=$sourceComposition.fingerprint
    Invoke-Expression $block.Extent.Text
    $existing=@($sourceComposition.repositories | Where-Object role -CEQ 'path-dependency')
    if($sourceComposition.fingerprint -cne $before -or $existing.Count -ne 5 -or @($existing|Where-Object repository_id -CEQ 'Owner.Repo').Count-ne1){
        throw 'Existing Cargo supplier was reclassified or relabelled.'
    }
    Write-Json (Join-Path $scratch 'RESULT.json') @{passed=$true;provider_repository_count=6;capsule_primary_dependency_count=5;reader=(File-Record $CapsuleReaderPath);cases=@($cases.ToArray());actual_producer_ast=$true;structural_precheck_before_projection_materialization_dispatch=$true;neutral_artifact_leaves=$true;APK_build=$false;runtime_authority=$false;device_effects=0}
    $completed=$true
    'Actual builder owned-source/early identity and six-provider/five-projection capsule CLI controls passed; neutral file leaves only; no APK build/runtime credit'
} finally {
    $resolved=[IO.Path]::GetFullPath($scratch)
    if($completed-and-not$retainHarness-and$resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolved) -match '^quest-component-owner-[a-f0-9]{32}$'){
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }else{Write-Host ('Owned-source fixture retained: '+$resolved)}
}
