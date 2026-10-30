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
    $contract=Get-Content -LiteralPath $ContractPath -Raw|ConvertFrom-Json -Depth 64
    if(@($contract.components.definitions|Where-Object root -CEQ 'development_feedback').Count){
        $questRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
        $arguments+=@('--development-feedback-root',(Join-Path $questRoot 'android/development-feedback/src'))
    }
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

function Initialize-AndroidComponentWorkingEvidence {
    param([string]$WorkingDirectory,[string]$EvidenceDirectory,[string]$InventoryPath,[string]$InventorySha256)
    if(-not [IO.Path]::IsPathFullyQualified($WorkingDirectory)){throw 'component-working-path-required'}
    $working=[IO.Path]::GetFullPath($WorkingDirectory).TrimEnd([char[]]@('\','/'))
    if($working -ceq [IO.Path]::GetPathRoot($working+[IO.Path]::DirectorySeparatorChar).TrimEnd([char[]]@('\','/'))){throw 'component-working-path-required'}
    $evidence=[IO.Path]::GetFullPath($EvidenceDirectory).TrimEnd([char[]]@('\','/'))
    $inventory=Get-Content -LiteralPath $InventoryPath -Raw | ConvertFrom-Json -Depth 64
    # Mutable producer files must never overlap immutable attempt evidence or
    # selected compiler sources. The caller serializes this existing cache lane.
    foreach($other in @($evidence,$InventoryPath)+@($inventory.roots.PSObject.Properties.Value)){
        $other=[IO.Path]::GetFullPath([string]$other).TrimEnd([char[]]@('\','/'))
        if($working.Equals($other,[StringComparison]::OrdinalIgnoreCase) -or
            $working.StartsWith($other+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
            $other.StartsWith($working+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){
            throw 'component-working-path-overlap'
        }
    }
    $cursor=$working
    while($cursor){
        if(Test-Path -LiteralPath $cursor){
            $item=Get-Item -LiteralPath $cursor -Force
            if(-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'component-working-path-linked'}
        }
        $cursor=Split-Path -Parent $cursor
    }
    [void][IO.Directory]::CreateDirectory($working)
    $staged=Join-Path $working 'inventory.json'
    $names=@('gradle-inputs.json','gradle-precompile-inputs.json')
    foreach($path in @($staged)+@($names|ForEach-Object{Join-Path $working $_})){
        if(Test-Path -LiteralPath $path){
            $item=Get-Item -LiteralPath $path -Force
            if($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'component-working-file-linked'}
        }
    }
    $bytes=[IO.File]::ReadAllBytes($InventoryPath)
    $sha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    if($sha -cne $InventorySha256){throw 'component-working-inventory-changed'}
    if(-not [IO.File]::Exists($staged) -or (Get-FileHash -LiteralPath $staged).Hash.ToLowerInvariant() -cne $sha){[IO.File]::WriteAllBytes($staged,$bytes)}
    # An issued child must produce current observations, even if an earlier
    # child failed. Clear only these owned mutable outputs before dispatch.
    foreach($name in $names){[IO.File]::Delete((Join-Path $working $name))}
    return [pscustomobject]@{Inventory=$staged;Directory=$working;Names=$names}
}

function Publish-AndroidComponentWorkingEvidence {
    param($Working,[string]$EvidenceDirectory,[string]$Phase,[bool]$Required)
    [void][IO.Directory]::CreateDirectory($EvidenceDirectory)
    foreach($name in $Working.Names){
        $source=Join-Path $Working.Directory $name
        if(-not [IO.File]::Exists($source)){
            if($Required -and ($name -ceq 'gradle-inputs.json' -or $Phase -ceq 'JvmPreflight')){throw 'component-working-receipt-missing'}
            continue
        }
        if((Get-Item -LiteralPath $source -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'component-working-file-linked'}
        $destination=Join-Path $EvidenceDirectory $name
        $inputStream=[IO.File]::Open($source,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
        try{
            $outputStream=[IO.File]::Open($destination,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
            try{$inputStream.CopyTo($outputStream);$outputStream.Flush($true)}finally{$outputStream.Dispose()}
        }finally{$inputStream.Dispose()}
        if((Get-FileHash -LiteralPath $source).Hash -cne (Get-FileHash -LiteralPath $destination).Hash){throw 'component-working-publication-changed'}
    }
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
        [string]$WorkingEvidenceDirectory='',
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
    if($WorkingEvidenceDirectory -and -not $InventoryPath){throw 'component-working-inventory-required'}
    $working=$null;$effectiveInventory=$InventoryPath;$effectiveEvidence=$EvidenceDirectory
    if($WorkingEvidenceDirectory){
        $working=Initialize-AndroidComponentWorkingEvidence -WorkingDirectory $WorkingEvidenceDirectory -EvidenceDirectory $EvidenceDirectory -InventoryPath $InventoryPath -InventorySha256 $ExpectedInventorySha256
        $effectiveInventory=$working.Inventory;$effectiveEvidence=$working.Directory
    }
    $task=if($Phase -ceq 'JvmPreflight'){':app:verifySelectedAndroidJvmCompilation'}else{":app:assemble$BuildType"}
    $selected=@("-PrqAndroidComponentInventory=$effectiveInventory","-PrqAndroidComponentEvidenceDir=$effectiveEvidence",
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
    $publicationError=$null
    if($null -ne $working){
        try{Publish-AndroidComponentWorkingEvidence -Working $working -EvidenceDirectory $EvidenceDirectory -Phase $Phase -Required:($null -eq $invocationError -and $exitCode -eq 0)}
        catch{$publicationError=$_}
    }
    if($null -ne $invocationError){
        $invocationError.Exception.Data['gradle_execution']=$execution
        if($null -ne $postError){$invocationError.Exception.Data['post_source_check_error']=$postError.Exception.Message}
        if($null -ne $publicationError){$invocationError.Exception.Data['publication_error']=$publicationError.Exception.Message}
        throw $invocationError
    }
    if($exitCode -ne 0){
        $failure=[InvalidOperationException]::new("Spatial Camera Panel Gradle $Phase failed with exit code $exitCode")
        $failure.Data['gradle_execution']=$execution
        if($null -ne $postError){$failure.Data['post_source_check_error']=$postError.Exception.Message}
        if($null -ne $publicationError){$failure.Data['publication_error']=$publicationError.Exception.Message}
        throw $failure
    }
    if($null -ne $postError){$postError.Exception.Data['gradle_execution']=$execution;throw $postError}
    if($null -ne $publicationError){$publicationError.Exception.Data['gradle_execution']=$execution;throw $publicationError}
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
        [string[]]$Arguments=@(),[string]$PythonPath='python',[string]$WorkingEvidenceDirectory='')
    if(-not [IO.Path]::IsPathFullyQualified($EvidenceDirectory) -or (Test-Path -LiteralPath $EvidenceDirectory)){throw 'component-fresh-jvm-evidence-required'}
    Assert-AndroidComponentGradleArguments -Arguments $Arguments
    Assert-AndroidComponentSelectedSources -InventoryPath $InventoryPath -ExpectedInventorySha256 $ExpectedInventorySha256 -PythonPath $PythonPath
    [void][IO.Directory]::CreateDirectory($EvidenceDirectory)
    $execution=Invoke-AndroidComponentGradlePhase -Phase JvmPreflight -GradlePath $GradlePath -ExpectedGradleSha256 $ExpectedGradleSha256 `
        -ProjectDirectory $ProjectDirectory -GradleUserHome $GradleUserHome -BuildType $BuildType -Arguments $Arguments `
        -InventoryPath $InventoryPath -ExpectedInventorySha256 $ExpectedInventorySha256 -EvidenceDirectory $EvidenceDirectory -PythonPath $PythonPath -WorkingEvidenceDirectory $WorkingEvidenceDirectory
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
