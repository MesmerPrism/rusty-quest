Set-StrictMode -Version Latest

function Get-AndroidComponentOwnerRepositoryId {
    param([Parameter(Mandatory)][string]$Root)
    $identifier=(Split-Path -Leaf ([IO.Path]::GetFullPath($Root).TrimEnd([char[]]@('\','/')))).ToLowerInvariant() -replace '[^a-z0-9-]','-'
    if($identifier -cnotmatch '^[a-z]'){$identifier='android-owner-'+$identifier}
    return $identifier
}

function Assert-AndroidComponentArtifactRoot {
    param([Parameter(Mandatory)]$Composition,[Parameter(Mandatory)][string]$Parent,[Parameter(Mandatory)][string]$Output)
    foreach($owner in @($Composition.repositories | Where-Object role -CEQ 'app-owned-input')){
        $root=[IO.Path]::GetFullPath([string]$owner.repository).TrimEnd([char[]]@('\','/'))
        $inside=@(@($Parent,$Output) | ForEach-Object {
            $full=[IO.Path]::GetFullPath($_).TrimEnd([char[]]@('\','/'))
            $full.Equals($root,[StringComparison]::OrdinalIgnoreCase) -or $full.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)
        })
        if($inside -contains $true){
            if($inside -contains $false){throw 'Artifact output under an app-owned input repository must have a Git-ignored owner parent.'}
            foreach($path in @($Parent,$Output)){
                & git -C $root check-ignore --quiet --no-index -- $path
                if($LASTEXITCODE -ne 0){throw 'Artifact output under an app-owned input repository must be Git-ignored.'}
            }
        }
    }
}

function Invoke-AndroidComponentTool {
    param([Parameter(Mandatory)][string[]]$Arguments,[string]$PythonPath='python')
    $tool=Join-Path $PSScriptRoot 'android_component_closure.py'
    $lines=@(& $PythonPath $tool @Arguments 2>&1)
    if($LASTEXITCODE -ne 0){throw "Selected Android component assertion failed: $($lines -join ' ')"}
    return ($lines -join "`n")
}

function New-AndroidComponentInventory {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$ContractPath,[Parameter(Mandatory)]$ValidatedProductInputs,
        [Parameter(Mandatory)][string]$ApplicationSourceRoot,[Parameter(Mandatory)][string]$PrivateSourceRoot,
        [Parameter(Mandatory)][ValidateSet('debug','release')][string]$Variant,[string]$PythonPath='python')
    $rows=@($ValidatedProductInputs.artifacts | Where-Object path -CEQ 'planning-feature-lock.json')
    if($rows.Count -ne 1){throw 'Selected Android components require exactly one authenticated planning feature lock.'}
    $arguments=@('prepare','--contract',$ContractPath,'--feature-lock',(Join-Path $ValidatedProductInputs.root 'planning-feature-lock.json'),
        '--expected-feature-lock-sha256',[string]$rows[0].sha256,'--application-root',$ApplicationSourceRoot,
        '--private-root',$PrivateSourceRoot,'--variant',$Variant,'--output','-')
    $json=Invoke-AndroidComponentTool -Arguments $arguments -PythonPath $PythonPath
    $digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($json))).ToLowerInvariant()
    return [pscustomobject]@{json=$json;sha256=$digest;inventory=($json | ConvertFrom-Json -Depth 64)}
}

function Get-AndroidComponentInputSummary {
    param($Selection)
    if($null -eq $Selection){return [ordered]@{status='unverified';reason='no-selected-component-contract'}}
    return [ordered]@{status='required';contract_sha256=[string]$Selection.inventory.contract_sha256;
        feature_lock_sha256=[string]$Selection.inventory.feature_lock_sha256;
        source_fingerprint=[string]$Selection.inventory.fingerprint;
        inventory_sha256=[string]$Selection.sha256;
        selected_components=@($Selection.inventory.selected_components)}
}

function Assert-AndroidComponentClosure {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$InventoryPath,[Parameter(Mandatory)][string]$EvidenceDirectory,
        [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedInventorySha256,
        [Parameter(Mandatory)][string]$ApkPath,[string]$PythonPath='python')
    $output=Join-Path $EvidenceDirectory 'closure.json'
    $null=Invoke-AndroidComponentTool -PythonPath $PythonPath -Arguments @('verify','--inventory',$InventoryPath,
        '--expected-inventory-sha256',$ExpectedInventorySha256,
        '--gradle-receipt',(Join-Path $EvidenceDirectory 'gradle-inputs.json'),'--apk',$ApkPath,'--output',$output)
    $receipt=Get-Content -LiteralPath $output -Raw | ConvertFrom-Json -Depth 64
    if($receipt.status -cne 'passed' -or $receipt.inventory_sha256 -cne $ExpectedInventorySha256){throw 'Selected Android component closure did not pass its prepared inventory binding.'}
    return [ordered]@{status='passed';contract_sha256=[string]$receipt.contract_sha256;
        feature_lock_sha256=[string]$receipt.feature_lock_sha256;source_fingerprint=[string]$receipt.source_fingerprint;
        inventory_sha256=[string]$receipt.inventory_sha256;gradle_inputs_sha256=[string]$receipt.gradle_inputs_sha256;
        closure_sha256=(Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant();
        apk_sha256=[string]$receipt.apk_sha256;runtime_authority=$false}
}
Export-ModuleMember -Function Get-AndroidComponentOwnerRepositoryId, Assert-AndroidComponentArtifactRoot, New-AndroidComponentInventory, Get-AndroidComponentInputSummary, Assert-AndroidComponentClosure
