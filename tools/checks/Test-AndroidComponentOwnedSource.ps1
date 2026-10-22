param([string]$RepoRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
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
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('quest-component-owner-'+[guid]::NewGuid().ToString('N'))
$app=Join-Path $scratch 'app';$owner=Join-Path $scratch 'Owner.Repo'
New-Item -ItemType Directory -Path "$app/src","$owner/src" -Force | Out-Null
function Invoke-FixtureGit([string]$Root,[string[]]$Arguments){$output=@(& git -C $Root @Arguments 2>&1);if($LASTEXITCODE){throw 'Fixture Git operation failed.'};$output}
function Commit([string]$Root){$null=Invoke-FixtureGit $Root @('add','--all');$null=Invoke-FixtureGit $Root @('-c','user.name=Local Fixture','-c','user.email=fixture@example.invalid','commit','-qm','Local fixture')}
function Write-Text([string]$Path,[string]$Text){[IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false))}
function Freeze-App {
    Push-Location $app
    try{& cargo generate-lockfile --offline 2>&1 | Out-Null;if($LASTEXITCODE){throw 'Fixture lock failed.'}}finally{Pop-Location}
    Commit $app
}
try {
    foreach($root in @($app,$owner)){$null=Invoke-FixtureGit $root @('init','--quiet')}
    Write-Text "$app/src/lib.rs" 'pub fn value() {}'
    Write-Text "$owner/src/lib.rs" 'pub fn owner() {}'
    Write-Text "$owner/Cargo.toml" "[package]`nname='fixture-owner'`nversion='0.1.0'`nedition='2021'`n"
    Write-Text "$owner/contract.json" '{}'
    Write-Text "$owner/.gitignore" '.local/'
    Commit $owner
    $manifest="[package]`nname='fixture-app'`nversion='0.1.0'`nedition='2021'`n"
    Write-Text "$app/Cargo.toml" $manifest
    Freeze-App
    $sourceComposition=Get-QuestBuildSourceComposition -RepoRoot $app -PackageName fixture-app
    $cargoSourceComposition=$sourceComposition
    $resolvedPrivateFeatureSourceDir=Join-Path $owner 'src';$AndroidComponentContractPath=Join-Path $owner 'contract.json'
    $PublicationBuild=$false;$BuildMode='DevFast'
    Invoke-Expression $block.Extent.Text
    $added=@($sourceComposition.repositories | Where-Object role -CEQ 'app-owned-input')
    if($added.Count -ne 1 -or $added[0].repository_id -cne 'owner-repo' -or $cargoSourceComposition.repositories.Count -ne 1){throw 'Actual uppercase/dotted absent owner callsite failed or changed Cargo document.'}
    if((Get-AndroidComponentOwnerRepositoryId (Join-Path $scratch '123.Owner')) -cne 'android-owner-123-owner'){throw 'Nonletter owner identity is invalid.'}
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
    Write-Text "$app/Cargo.toml" ($manifest+"[dependencies]`nfixture-owner={path='../Owner.Repo'}`n")
    Freeze-App
    $sourceComposition=Get-QuestBuildSourceComposition -RepoRoot $app -PackageName fixture-app
    $cargoSourceComposition=$sourceComposition;$before=$sourceComposition.fingerprint
    Invoke-Expression $block.Extent.Text
    $existing=@($sourceComposition.repositories | Where-Object role -CEQ 'path-dependency')
    if($sourceComposition.fingerprint -cne $before -or $existing.Count -ne 1 -or $existing[0].repository_id -cne 'Owner.Repo'){
        throw 'Existing Cargo supplier was reclassified or relabelled.'
    }
    'Actual builder owned-source Git callsite: normalized ID, Cargo row preserved, ignored artifact allowed/nonignored owner and source overlap rejected passed'
} finally {
    $resolved=[IO.Path]::GetFullPath($scratch)
    if($resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolved) -match '^quest-component-owner-[a-f0-9]{32}$'){
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
