param([string]$RepoRoot = (Join-Path $PSScriptRoot '../..'))

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $RepoRoot 'tools/Build-SpatialCameraPanelAndroid.ps1'),[ref]$tokens,[ref]$errors)
if($errors){throw 'Cannot parse artifact preflight caller.'}
$definition=$ast.Find({param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'New-ExternalArtifactDirectory'
},$true)
if($null -eq $definition){throw 'Artifact directory helper is missing.'}
Invoke-Expression $definition.Extent.Text
$preflight=$ast.EndBlock.Statements | Where-Object {
    $_ -is [Management.Automation.Language.IfStatementAst] -and
    $_.Extent.Text.StartsWith('if (-not [string]::IsNullOrWhiteSpace($ArtifactOutputParent))')
} | Select-Object -First 1
if($null -eq $preflight){throw 'Actual artifact preflight caller is missing.'}

$scratch=Join-Path ([IO.Path]::GetTempPath()) ('quest-artifact-preflight-'+[Guid]::NewGuid().ToString('N'))
try {
    foreach($part in @('attempt','source','native','cache','shared')){[void][IO.Directory]::CreateDirectory((Join-Path $scratch $part))}
    $parent=Join-Path $scratch 'attempt'
    $provisionalRepoRoot=Join-Path $scratch 'source';$BuildCacheRoot=Join-Path $scratch 'cache'
    $ReplaceExistingOutput=$false
    foreach($BuildMode in @('DevFast','Candidate')){
        $output=Join-Path $parent ("public-build-$BuildMode")
        foreach($selection in @(
            @{Native='';Shared=''},
            @{Native='';Shared=(Join-Path $scratch 'shared')},
            @{Native=(Join-Path $scratch 'native');Shared=''},
            @{Native=' ';Shared=' '},
            @{Native=(Join-Path $scratch 'native');Shared=(Join-Path $scratch 'shared')}
        )){
            $NativeRepoRoot=$selection.Native;$SharedGradleToolRoot=$selection.Shared
            $ArtifactOutputParent=$parent;$OutDir=$output
            Invoke-Expression $preflight.Extent.Text
            if(Test-Path -LiteralPath $output){throw 'Actual artifact preflight created output early.'}
            foreach($path in @($provisionalRepoRoot,$NativeRepoRoot,$BuildCacheRoot,$SharedGradleToolRoot) | Where-Object {-not [string]::IsNullOrWhiteSpace($_)}){
                foreach($overlap in @($path,(Join-Path $path 'nested'),$scratch)){
                    $ArtifactOutputParent=$overlap;$OutDir=Join-Path $overlap 'build'
                    $denied=$false
                    try{Invoke-Expression $preflight.Extent.Text}catch{$denied=$_.Exception.Message -like '*must not overlap*'}
                    if(-not $denied){throw "Actual artifact preflight failed to reject $BuildMode selected-root overlap: $overlap"}
                }
            }
        }
        $ArtifactOutputParent=$parent
        foreach($invalidOutput in @($parent,(Join-Path $scratch 'escape'),'relative-build',(Join-Path $parent 'CON.txt'),(Join-Path $parent 'build:stream'))){
            $OutDir=$invalidOutput;$denied=$false
            try{Invoke-Expression $preflight.Extent.Text}catch{$denied=$true}
            if(-not $denied){throw "Actual artifact preflight accepted unsafe output: $invalidOutput"}
        }
        $OutDir=$output;$ReplaceExistingOutput=$true;$denied=$false
        try{Invoke-Expression $preflight.Extent.Text}catch{$denied=$_.Exception.Message -like '*forbids ReplaceExistingOutput*'}
        if(-not $denied){throw 'Actual artifact preflight accepted replacement.'}
        $ReplaceExistingOutput=$false
        [IO.File]::WriteAllText($output,'collision');$denied=$false
        try{Invoke-Expression $preflight.Extent.Text}catch{$denied=$_.Exception.Message -like '*create-new and absent*'}
        if(-not $denied){throw 'Actual artifact preflight accepted existing output.'}
    }
    Write-Host 'Actual Spatial artifact preflight: blank optional roots accepted; selected-root overlaps and unsafe outputs rejected; no build/cache/device operations.'
} finally {
    $resolved=[IO.Path]::GetFullPath($scratch)
    if($resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolved) -match '^quest-artifact-preflight-[a-f0-9]{32}$'){
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
