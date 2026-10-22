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

function Assert-AndroidComponentGradleArguments {
    param([string[]]$Arguments)
    $flags=@('--no-daemon','--configuration-cache','--no-configuration-cache','--console=plain','--build-cache')
    $paired=@('--project-cache-dir','--init-script')
    $properties=@('kotlin.project.persistent.dir','kotlin.project.persistent.dir.gradle.disableWrite',
        'android.aapt2FromMavenOverride','rqCmakeStagingDirectory','androidJar','mediaBuildDir',
        'rqSourceBanksForeignOwnership','rqDeveloperSourceCompositionFingerprint','kotlin.compiler.execution.strategy')
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for($index=0;$index -lt $Arguments.Count;$index++){
        $argument=[string]$Arguments[$index]
        if($argument.IndexOfAny([char[]]@([char]0,[char]10,[char]13)) -ge 0){throw 'component-gradle-arguments'}
        if($flags -ccontains $argument){
            if(-not $seen.Add($argument)){throw 'component-gradle-arguments'}
        }elseif($paired -ccontains $argument){
            if(-not $seen.Add($argument) -or ++$index -ge $Arguments.Count){throw 'component-gradle-arguments'}
            $path=[string]$Arguments[$index]
            if(-not [IO.Path]::IsPathFullyQualified($path) -or
                ($argument -ceq '--init-script' -and -not [IO.File]::Exists($path))){throw 'component-gradle-arguments'}
        }elseif($argument -cmatch '^-P([^=]+)=(.*)$'){
            if($properties -cnotcontains $Matches[1] -or -not $seen.Add('property:'+$Matches[1])){throw 'component-gradle-arguments'}
        }else{throw 'component-gradle-arguments'}
    }
    if($seen.Contains('--configuration-cache') -and $seen.Contains('--no-configuration-cache')){throw 'component-gradle-arguments'}
}

function Assert-AndroidComponentSelectedSources {
    param([string]$InventoryPath,[string]$ExpectedInventorySha256,[string]$PythonPath)
    if($ExpectedInventorySha256 -cnotmatch '^[a-f0-9]{64}$' -or
        -not [IO.File]::Exists($InventoryPath) -or
        (Get-FileHash -LiteralPath $InventoryPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ExpectedInventorySha256){
        throw 'component-prepared-inventory-changed'
    }
    $null=Invoke-AndroidComponentTool -PythonPath $PythonPath -Arguments @('verify-sources','--inventory',$InventoryPath,
        '--expected-inventory-sha256',$ExpectedInventorySha256,'--output','-')
}

function Invoke-AndroidComponentGradlePhase {
    [CmdletBinding()]
    param([Parameter(Mandatory)][ValidateSet('JvmPreflight','Assemble')][string]$Phase,
        [Parameter(Mandatory)][string]$GradlePath,
        [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedGradleSha256,
        [Parameter(Mandatory)][string]$ProjectDirectory,[Parameter(Mandatory)][string]$GradleUserHome,
        [Parameter(Mandatory)][ValidateSet('Debug','Release')][string]$BuildType,
        [string[]]$Arguments=@(),[AllowEmptyString()][string]$InventoryPath='',
        [AllowEmptyString()][string]$ExpectedInventorySha256='',[AllowEmptyString()][string]$EvidenceDirectory='',
        [string]$PythonPath='python')
    $checkTool={
        if(-not [IO.File]::Exists($GradlePath) -or
            (Get-FileHash -LiteralPath $GradlePath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ExpectedGradleSha256){throw 'component-gradle-tool-changed'}
    }
    & $checkTool
    Assert-AndroidComponentGradleArguments -Arguments $Arguments
    if(-not [IO.Path]::IsPathFullyQualified($ProjectDirectory) -or -not [IO.Directory]::Exists($ProjectDirectory) -or
        -not [IO.Path]::IsPathFullyQualified($GradleUserHome)){throw 'component-gradle-selected-paths'}
    if([string]::IsNullOrEmpty($InventoryPath) -ne [string]::IsNullOrEmpty($EvidenceDirectory)){throw 'component-inventory-and-evidence-required-together'}
    if($Phase -ceq 'JvmPreflight' -and [string]::IsNullOrEmpty($InventoryPath)){throw 'component-jvm-selected-inventory-required'}
    if($InventoryPath){Assert-AndroidComponentSelectedSources -InventoryPath $InventoryPath -ExpectedInventorySha256 $ExpectedInventorySha256 -PythonPath $PythonPath}
    $task=if($Phase -ceq 'JvmPreflight'){':app:verifySelectedAndroidJvmCompilation'}else{":app:assemble$BuildType"}
    $selected=@("-PrqAndroidComponentInventory=$InventoryPath","-PrqAndroidComponentEvidenceDir=$EvidenceDirectory",
        "-PrqAndroidComponentKotlinTask=compile${BuildType}Kotlin","-PrqAndroidComponentJavaTask=compile${BuildType}JavaWithJavac")
    $effectiveArguments=@($selected)+@($Arguments)+@('--gradle-user-home',$GradleUserHome,'-p',$ProjectDirectory,$task)
    $clock=[Diagnostics.Stopwatch]::StartNew();$lines=[Collections.Generic.List[string]]::new()
    $invocationError=$null
    try{& $GradlePath @effectiveArguments 2>&1 | ForEach-Object {$line=[string]$_;$lines.Add($line);Write-Host $line}}
    catch{$invocationError=$_}
    $exitCode=if($null -eq $invocationError){$LASTEXITCODE}
        elseif($invocationError.Exception -is [Management.Automation.NativeCommandExitException]){$invocationError.Exception.ExitCode}
        else{$null}
    $clock.Stop()
    $execution=[pscustomobject]@{phase=$Phase;task=$task;exit_code=$exitCode;duration_ms=$clock.ElapsedMilliseconds;output=@($lines.ToArray());gradle_sha256=$ExpectedGradleSha256}
    $postError=$null
    try{
        & $checkTool
        if($InventoryPath){Assert-AndroidComponentSelectedSources -InventoryPath $InventoryPath -ExpectedInventorySha256 $ExpectedInventorySha256 -PythonPath $PythonPath}
    }catch{$postError=$_}
    if($null -ne $invocationError){
        $invocationError.Exception.Data['gradle_execution']=$execution
        if($null -ne $postError){$invocationError.Exception.Data['post_source_check_error']=$postError.Exception.Message}
        throw $invocationError
    }
    if($exitCode -ne 0){
        $failure=[InvalidOperationException]::new("Spatial Camera Panel Gradle $Phase failed with exit code $exitCode")
        $failure.Data['gradle_execution']=$execution
        if($null -ne $postError){$failure.Data['post_source_check_error']=$postError.Exception.Message}
        throw $failure
    }
    if($null -ne $postError){$postError.Exception.Data['gradle_execution']=$execution;throw $postError}
    return $execution
}

function Invoke-AndroidComponentJvmCompilation {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$InventoryPath,
        [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedInventorySha256,
        [Parameter(Mandatory)][string]$EvidenceDirectory,[Parameter(Mandatory)][string]$GradlePath,
        [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedGradleSha256,
        [Parameter(Mandatory)][string]$ProjectDirectory,[Parameter(Mandatory)][string]$GradleUserHome,
        [Parameter(Mandatory)][ValidateSet('Debug','Release')][string]$BuildType,
        [string[]]$Arguments=@(),[string]$PythonPath='python')
    if(-not [IO.Path]::IsPathFullyQualified($EvidenceDirectory) -or (Test-Path -LiteralPath $EvidenceDirectory)){throw 'component-fresh-jvm-evidence-required'}
    Assert-AndroidComponentGradleArguments -Arguments $Arguments
    Assert-AndroidComponentSelectedSources -InventoryPath $InventoryPath -ExpectedInventorySha256 $ExpectedInventorySha256 -PythonPath $PythonPath
    [void][IO.Directory]::CreateDirectory($EvidenceDirectory)
    $execution=Invoke-AndroidComponentGradlePhase -Phase JvmPreflight -GradlePath $GradlePath -ExpectedGradleSha256 $ExpectedGradleSha256 `
        -ProjectDirectory $ProjectDirectory -GradleUserHome $GradleUserHome -BuildType $BuildType -Arguments $Arguments `
        -InventoryPath $InventoryPath -ExpectedInventorySha256 $ExpectedInventorySha256 -EvidenceDirectory $EvidenceDirectory -PythonPath $PythonPath
    $actualPath=Join-Path $EvidenceDirectory 'gradle-inputs.json'
    $actual=Get-Content -LiteralPath $actualPath -Raw | ConvertFrom-Json -Depth 64
    if($actual.observation_phase -cne 'post-compile' -or @($actual.early_task_graph).Count -eq 0){throw 'component-jvm-compiler-observation-required'}
    $receiptPath=Join-Path $EvidenceDirectory 'compiled.json'
    $null=Invoke-AndroidComponentTool -PythonPath $PythonPath -Arguments @('verify-compiled','--inventory',$InventoryPath,
        '--expected-inventory-sha256',$ExpectedInventorySha256,'--gradle-receipt',$actualPath,'--output',$receiptPath)
    $receipt=Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json -Depth 64
    if($receipt.schema -cne 'rusty.quest.android_component_jvm_compilation.v1' -or $receipt.status -cne 'passed' -or
        $receipt.inventory_sha256 -cne $ExpectedInventorySha256 -or $receipt.runtime_authority -ne $false){throw 'component-jvm-compilation-did-not-pass'}
    return [pscustomobject]@{status='passed';scope='jvm-compilation-only';runtime_authority=$false;execution=$execution;
        inventory_sha256=$ExpectedInventorySha256;compiled_sha256=(Get-FileHash -LiteralPath $receiptPath -Algorithm SHA256).Hash.ToLowerInvariant();
        gradle_inputs_sha256=(Get-FileHash -LiteralPath $actualPath -Algorithm SHA256).Hash.ToLowerInvariant()}
}
Export-ModuleMember -Function Get-AndroidComponentOwnerRepositoryId, Assert-AndroidComponentArtifactRoot, New-AndroidComponentInventory, Get-AndroidComponentInputSummary, Assert-AndroidComponentClosure, Invoke-AndroidComponentGradlePhase, Invoke-AndroidComponentJvmCompilation
