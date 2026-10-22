param([string]$RepoRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$builder=Join-Path $RepoRoot 'tools/Build-SpatialCameraPanelAndroid.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($builder,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Builder parsing failed.'}
foreach($name in @('Get-FileSha256','Get-StringSha256','Get-DirectorySha256','Get-PathSetSha256')){
    $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true)
    Invoke-Expression $definition.Extent.Text
}
Import-Module (Join-Path $RepoRoot 'tools/lib/AndroidComponentClosure.psm1') -Force
Import-Module (Join-Path $RepoRoot 'tools/EmbeddedDuplexMediaPolicy.psm1') -Force
Import-Module (Join-Path $RepoRoot 'tools/EmbeddedDuplexFreshSetupPolicy.psm1') -Force
$assignments=@{}
foreach($node in $ast.FindAll({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst]},$true)){
    if($node.Left -is [Management.Automation.Language.VariableExpressionAst]){$assignments[$node.Left.VariablePath.UserPath]=$node.Extent.Text}
}
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('quest-component-seam-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
try {
    $originalRepo=$RepoRoot;$repoRoot=$scratch;$appRoot=Join-Path $repoRoot 'app'
    Invoke-Expression $assignments.nativeIdentityInputs
    foreach($name in $nativeIdentityInputs.Keys){
        $path=$nativeIdentityInputs[$name]
        if([IO.Path]::GetExtension($path)){
            New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
            [IO.File]::WriteAllText($path,'fixture')
        }else{
            New-Item -ItemType Directory -Path $path -Force | Out-Null
            [IO.File]::WriteAllText((Join-Path $path 'input.rs'),'pub fn input() {}')
        }
    }
    $buildInputDescriptor=@{private_layer=@{profile=$null;guide_shader=$null;projection_shader=$null;projection_vertex_shader=$null};
        private_particles=@{profile=$null;shader=$null;payload=$null}}
    $sourceBankBuildInputs=@{enabled=$false};$NdkVersion='fixture';$RustStdLinkage='Static'
    $resolvedProjectionSurfaceUniformAbiVersion=1;$lockedFinalPresentationEnabled=$false;$resolvedDistortionSpeedScale=1.0
    $EnvironmentDepthOwner='disabled';$embeddedDuplexProductInputs=$null;$EmbeddedDuplexSetupHorizonMs=300000
    $EmbeddedDuplexMediaPolicy='finite';$freshBuildSelection=$null
    function Observe-NativeIdentity {
        Invoke-Expression $assignments.nativeSourceSha256
        Invoke-Expression $assignments.privateNativeIdentity
        Invoke-Expression $assignments.nativeIdentityDescriptor
        Set-EmbeddedDuplexMediaPolicyDescriptor -Descriptor $nativeIdentityDescriptor -Selection $EmbeddedDuplexMediaPolicy
        Set-EmbeddedDuplexFreshSetupPolicyDescriptor -Descriptor $nativeIdentityDescriptor -ResolvedSelection $freshBuildSelection
        Invoke-Expression $assignments.nativeFingerprint
        return $nativeFingerprint
    }
    $before=Observe-NativeIdentity
    [IO.File]::WriteAllText((Join-Path $scratch 'Owner.kt'),'object Owner')
    [IO.File]::WriteAllText((Join-Path $scratch 'component-contract.json'),'selected contract changed')
    $androidComponentInputs=@{contract_sha256='a'*64;source_fingerprint='b'*64}
    $sourceComposition=@{fingerprint='c'*64}
    if((Observe-NativeIdentity) -cne $before){throw 'Kotlin/contract/full app composition invalidated native key.'}
    [IO.File]::WriteAllText((Join-Path $nativeIdentityInputs.native_receipt 'input.rs'),'pub fn changed() {}')
    if((Observe-NativeIdentity) -ceq $before){throw 'Actual selected native Rust change did not invalidate native key.'}
    $compat=Get-AndroidComponentInputSummary $null
    if($compat.status -cne 'unverified'){throw 'Compatibility caller fabricated verified closure.'}
    $selection=[pscustomobject]@{sha256='d'*64;inventory=[pscustomobject]@{contract_sha256='a'*64;feature_lock_sha256='b'*64;fingerprint='c'*64;selected_components=@('owner')}}
    $summary=Get-AndroidComponentInputSummary $selection
    if($summary.status -cne 'required' -or $summary.contract_sha256 -cne ('a'*64)){throw 'Contract source binding missing.'}
    $appSources=Join-Path $scratch 'app-sources';$privateSources=Join-Path $scratch 'private-sources'
    New-Item -ItemType Directory -Path $appSources,$privateSources | Out-Null
    [IO.File]::WriteAllText((Join-Path $privateSources 'Owner.kt'),'package fixture; object Owner')
    $lockPath=Join-Path $scratch 'planning-feature-lock.json'
    [IO.File]::WriteAllText($lockPath,'{"selected_features":["owner"],"features":[{"feature_id":"owner","module_id":"owner","owner_lane":"fixture","selected":true}]}')
    $contractPath=Join-Path $scratch 'contract.json'
    [IO.File]::WriteAllText($contractPath,'{"schema":"rusty.quest.android_component_contract.v1","components":[{"component_id":"owner","module_id":"owner","owner_lane":"fixture","definitions":[{"root":"private","source":"Owner.kt","class":"fixture.Owner","methods":[]}]}]}')
    $validated=[pscustomobject]@{root=$scratch;artifacts=@([pscustomobject]@{path='planning-feature-lock.json';sha256=(Get-FileSha256 $lockPath)})}
    $selected=New-AndroidComponentInventory -ContractPath $contractPath -ValidatedProductInputs $validated -ApplicationSourceRoot $appSources -PrivateSourceRoot $privateSources -Variant debug
    if($selected.inventory.selected_components[0] -cne 'owner' -or $selected.inventory.sources.Count -ne 1){throw 'Real adapter lost selected compiler source.'}
    $validated.artifacts[0].sha256='0'*64
    try{$null=New-AndroidComponentInventory -ContractPath $contractPath -ValidatedProductInputs $validated -ApplicationSourceRoot $appSources -PrivateSourceRoot $privateSources -Variant debug;throw 'Changed authenticated lock accepted.'}
    catch{if($_.Exception.Message -notlike '*feature-lock-changed*'){throw}}
    try{$null=Assert-AndroidComponentClosure -InventoryPath $contractPath -ExpectedInventorySha256 $selected.sha256 -EvidenceDirectory $scratch -ApkPath (Join-Path $scratch 'absent.apk');throw 'Absent compile receipt accepted.'}
    catch{if($_.Exception.Message -notlike '*assertion failed*'){throw}}
    $code=$ast.Extent.Text
    if($code.IndexOf('$androidComponentClosure = Assert-AndroidComponentClosure') -gt $code.IndexOf('$apkCopied = Copy-FileIfChanged')){throw 'APK handed off before closure.'}
    if($code.IndexOf('Assert-AndroidNativeSourcePaths -SourceDirectory') -gt $code.IndexOf('$nativeStopwatch =')){throw 'CMake source guard is not early.'}
    if(-not $code.Contains('-ShellComposition $cargoSourceComposition')){throw 'Native proof consumed full app composition.'}
    'Production native identity stable/rotating, actual adapter selection/authenticated-lock refusal, fail-before-handoff controls passed'
} finally {
    $resolved=[IO.Path]::GetFullPath($scratch)
    if($resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolved) -match '^quest-component-seam-[a-f0-9]{32}$'){
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
