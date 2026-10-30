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
Import-Module (Join-Path $RepoRoot 'tools/EmbeddedDuplexFreshSetupPolicy.psm1') -Force
$actualGradlePhaseCommand=(Get-Command Invoke-AndroidComponentGradlePhase).ScriptBlock
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
    $EmbeddedDuplexLifetimePolicy='finite';$EmbeddedDuplexMediaPolicy='finite';$EmbeddedDuplexFreshSetupPolicy='none'
    $developmentLifetime=Resolve-EmbeddedDuplexDevelopmentLifetime -Selection $EmbeddedDuplexLifetimePolicy -FiniteHorizonMs $EmbeddedDuplexSetupHorizonMs
    $freshBuildSelection=Resolve-EmbeddedDuplexFreshSetupBuildSelection -Selection $EmbeddedDuplexFreshSetupPolicy -MediaPolicy $EmbeddedDuplexMediaPolicy -DevelopmentLifetimePolicy $developmentLifetime.policy
    function Observe-NativeIdentity {
        Invoke-Expression $assignments.nativeSourceSha256
        Invoke-Expression $assignments.privateNativeIdentity
        Invoke-Expression $assignments.nativeIdentityDescriptor
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
    $jvmApi=Get-Command Invoke-AndroidComponentJvmCompilation -ErrorAction Stop
    foreach($parameter in @('InventoryPath','ExpectedInventorySha256','EvidenceDirectory','GradlePath','ExpectedGradleSha256','ProjectDirectory','GradleUserHome','BuildType','Arguments')){
        if(-not $jvmApi.Parameters.ContainsKey($parameter)){throw 'Early JVM API lost a selected input boundary.'}
    }
    # Modeled transport is used only to prove refusal before dispatch; real
    # Gradle/Android compiler behavior belongs to the separate early fixture.
    $fakeGradle=Join-Path $scratch 'gradle.bat';$sentinel=Join-Path $scratch 'unexpected-gradle'
    [IO.File]::WriteAllText($fakeGradle,"@echo off`necho unexpected> `"$sentinel`"`n")
    $invocation=@{Phase='Assemble';GradlePath=$fakeGradle;ExpectedGradleSha256=(Get-FileSha256 $fakeGradle);
        ProjectDirectory=$scratch;GradleUserHome=(Join-Path $scratch 'gradle-home');BuildType='Debug';InventoryPath='';EvidenceDirectory=''}
    $argumentDamages=@(
        @('classes'), @(':app:assembleDebug'), @('--init-script','relative.gradle'),
        @('-PrqAndroidComponentKotlinTask=other'), @('-PunknownProjection=value'),
        @('--no-daemon','--no-daemon'), @('--configuration-cache','--no-configuration-cache')
    )
    foreach($damage in $argumentDamages){
        try{$null=Invoke-AndroidComponentGradlePhase @invocation -Arguments $damage;throw 'Uncontrolled Gradle argument accepted.'}
        catch{if($_.Exception.Message -notlike '*component-gradle-arguments*'){throw}}
        if(Test-Path -LiteralPath $sentinel){throw 'Rejected argument still dispatched its child.'}
    }
    # Actual phase/task composition, modeled transport only: no Gradle or APK.
    $capturedArguments=Join-Path $scratch 'assemble-arguments.txt'
    [IO.File]::WriteAllText($fakeGradle,"@echo off`necho %*> `"$capturedArguments`"`nexit /b 0`n")
    $invocation.ExpectedGradleSha256=Get-FileSha256 $fakeGradle
    foreach($variant in @('Debug','Release')){
        $invocation.BuildType=$variant
        $execution=Invoke-AndroidComponentGradlePhase @invocation -Arguments @('--no-daemon')
        $expectedTask=":app:assemble$variant"
        $actualArguments=[IO.File]::ReadAllText($capturedArguments).Trim()
        $actualTasks=@([regex]::Matches($actualArguments,'(?:^|\s)"?(:[^"\s]+)"?(?=\s|$)') | ForEach-Object {$_.Groups[1].Value})
        if($execution.phase -cne 'Assemble' -or $execution.task -cne $expectedTask -or
            $actualTasks.Count -ne 1 -or $actualTasks[0] -cne $expectedTask){
            throw 'Actual component phase did not dispatch its one selected assemble task.'
        }
    }
    $invocation.BuildType='Debug'
    $invocation.ExpectedGradleSha256='0'*64
    try{$null=Invoke-AndroidComponentGradlePhase @invocation -Arguments @('--no-daemon');throw 'Changed Gradle executable accepted.'}
    catch{if($_.Exception.Message -notlike '*component-gradle-tool-changed*'){throw}}
    if(Test-Path -LiteralPath $sentinel){throw 'Changed tool still dispatched its child.'}
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
    # Real inventory/source guard plus modeled nonzero transport: late source
    # drift must not replace the original child failure or discard its metadata.
    $inventoryPath=Join-Path $scratch 'inventory.json'
    [IO.File]::WriteAllText($inventoryPath,$selected.json,[Text.UTF8Encoding]::new($false))
    $privateOwner=Join-Path $privateSources 'Owner.kt'
    [IO.File]::WriteAllText($fakeGradle,"@echo off`necho changed> `"$privateOwner`"`necho modeled-child-failure`nexit /b 17`n")
    $priorNativePreference=$global:PSNativeCommandUseErrorActionPreference
    $originalOwner=[IO.File]::ReadAllText($privateOwner)
    try{foreach($nativePreference in @($false,$true)){
        $global:PSNativeCommandUseErrorActionPreference=$nativePreference
        [IO.File]::WriteAllText($privateOwner,$originalOwner)
        $failedEvidence=Join-Path $scratch ('failed-jvm-'+$nativePreference)
        try{
            $null=Invoke-AndroidComponentJvmCompilation -InventoryPath $inventoryPath -ExpectedInventorySha256 $selected.sha256 `
                -EvidenceDirectory $failedEvidence -GradlePath $fakeGradle -ExpectedGradleSha256 (Get-FileSha256 $fakeGradle) `
                -ProjectDirectory $scratch -GradleUserHome (Join-Path $scratch 'gradle-home') -BuildType Debug
            throw 'Failed child unexpectedly passed.'
        }catch{
            if($_.Exception.Data['gradle_execution'].exit_code -ne 17){throw 'Original native exit17 metadata was lost.'}
            if([string]::IsNullOrEmpty($_.Exception.Data['post_source_check_error']) -or
                $_.Exception.Data['gradle_execution'].output -cnotcontains 'modeled-child-failure'){throw}
            if($nativePreference){
                if($_.Exception -isnot [Management.Automation.NativeCommandExitException] -or $_.Exception.ExitCode -ne 17){throw 'Native preference exception was replaced.'}
            }elseif($_.Exception.Message -notlike '*JvmPreflight failed with exit code 17*' -or
                $_.Exception.Data['post_source_check_error'] -notlike '*source-drift*'){throw}
            if([IO.File]::ReadAllText($privateOwner) -ceq $originalOwner){throw 'Late-drift control did not change the selected source.'}
        }
        if(Test-Path (Join-Path $failedEvidence 'compiled.json')){throw 'Failed child fabricated compiled receipt.'}
    }}finally{
        $global:PSNativeCommandUseErrorActionPreference=$priorNativePreference
        [IO.File]::WriteAllText($privateOwner,$originalOwner)
    }
    # Working paths never enter source/immutable evidence or reuse an old
    # observation. The modeled tool below exercises transport/publication only.
    $working=Join-Path $scratch 'working-observations'
    $immutable=Join-Path $scratch 'immutable-observations'
    [IO.File]::WriteAllText($fakeGradle,"@echo off`necho unexpected> `"$sentinel`"`nexit /b 0`n")
    $workingCall=@{Phase='Assemble';InventoryPath=$inventoryPath;ExpectedInventorySha256=$selected.sha256;
        EvidenceDirectory=$immutable;WorkingEvidenceDirectory=$working;GradlePath=$fakeGradle;
        ExpectedGradleSha256=(Get-FileSha256 $fakeGradle);ProjectDirectory=$scratch;
        GradleUserHome=(Join-Path $scratch 'gradle-home');BuildType='Debug'}
    foreach($bad in @([IO.Path]::GetPathRoot($scratch),$privateSources,$immutable)){
        $workingCall.WorkingEvidenceDirectory=$bad
        try{$null=Invoke-AndroidComponentGradlePhase @workingCall;throw 'Unsafe working path accepted.'}
        catch{if($_.Exception.Message -notmatch 'component-working-path-(required|overlap)'){throw}}
        if(Test-Path -LiteralPath $sentinel){throw 'Unsafe working path dispatched the tool.'}
    }
    $workingCall.WorkingEvidenceDirectory=$working
    New-Item -ItemType Directory -Path $working,$immutable | Out-Null
    $oldReceipt=Join-Path $working 'gradle-inputs.json'
    [IO.File]::WriteAllText($oldReceipt,'stale')
    [IO.File]::WriteAllText($fakeGradle,"@echo off`nexit /b 0`n")
    $workingCall.ExpectedGradleSha256=Get-FileSha256 $fakeGradle
    try{$null=Invoke-AndroidComponentGradlePhase @workingCall;throw 'Stale working receipt accepted.'}
    catch{if($_.Exception.Message -notlike '*component-working-receipt-missing*' -or $_.Exception.Data['gradle_execution'].exit_code -ne 0){throw}}
    if(Test-Path -LiteralPath $oldReceipt){throw 'Stale mutable receipt survived dispatch.'}
    $finalReceipt=Join-Path $immutable 'gradle-inputs.json'
    [IO.File]::WriteAllText($finalReceipt,'immutable-original')
    [IO.File]::WriteAllText($fakeGradle,"@echo off`necho current-observation> `"$oldReceipt`"`nexit /b 0`n")
    $workingCall.ExpectedGradleSha256=Get-FileSha256 $fakeGradle
    try{$null=Invoke-AndroidComponentGradlePhase @workingCall;throw 'Immutable evidence overwritten.'}
    catch{if($_.Exception.Data['gradle_execution'].exit_code -ne 0){throw}}
    if([IO.File]::ReadAllText($finalReceipt) -cne 'immutable-original'){throw 'Final observation changed on collision.'}
    try{$null=Assert-AndroidComponentClosure -InventoryPath $contractPath -ExpectedInventorySha256 $selected.sha256 -EvidenceDirectory $scratch -ApkPath (Join-Path $scratch 'absent.apk');throw 'Absent compile receipt accepted.'}
    catch{if($_.Exception.Message -notlike '*assertion failed*'){throw}}
    $code=$ast.Extent.Text
    foreach($parameter in @('CargoPath','RustupPath','RequireInstalledRustTarget','WaitPolicy')){
        if(@($ast.ParamBlock.Parameters|Where-Object {$_.Name.VariablePath.UserPath-ceq$parameter}).Count-ne1){throw 'Shared platform compatibility parameter was lost.'}
    }
    foreach($binding in @('$prerequisites = Get-QuestSpatialBuildPrerequisites', '$cargoCommand = Resolve-QuestSpatialApplication -Name cargo -Path $CargoPath', '$rustupCommand = Resolve-QuestSpatialApplication -Name rustup -Path $RustupPath', 'if (-not $rustTargetInstalled -and $RequireInstalledRustTarget)')){
        if(-not$code.Contains($binding)){throw ('Shared prerequisite contract was lost: '+$binding)}
    }
    # Run the actual builder selection/target block, stopping before staging or Cargo.
    $toolOwner=Import-Module (Join-Path $originalRepo 'tools/lib/SpatialCameraPanelBuildPrerequisites.psm1') -PassThru
    $nativeReceiptCargoManifest=Join-Path $scratch 'fixture-Cargo.toml'
    $nativeReceiptLinker=Join-Path $scratch 'fixture-linker.cmd'
    $RustupPath=Join-Path $scratch 'fixture-rustup.cmd';$CargoPath=Join-Path $PSHOME 'pwsh.exe'
    [IO.File]::WriteAllText($nativeReceiptCargoManifest,'fixture');[IO.File]::WriteAllText($nativeReceiptLinker,'@exit /b 0')
    $targetBlock=$code.Substring($code.IndexOf('$cargoCommand ='),$code.IndexOf('Write-Host ("BUILD_CACHE rust_target=')-$code.IndexOf('$cargoCommand ='))
    [IO.File]::WriteAllText($RustupPath,"@echo off`necho x86_64-pc-windows-msvc`nexit /b 0`n")
    $RequireInstalledRustTarget=$true;$denied=$false
    try{Invoke-Expression $targetBlock}catch{$denied=$_.Exception.Message-ceq'The selected build requires the Android Rust target already installed.'}
    if(-not$denied){throw 'Actual installed-target selection did not refuse before target installation.'}
    [IO.File]::WriteAllText($RustupPath,"@echo off`necho aarch64-linux-android`nexit /b 0`n")
    Invoke-Expression $targetBlock
    if(-not$rustTargetInstalled-or$cargoCommand.Source-cne$CargoPath){throw 'Actual selected tool/installed-target positive was lost.'}
    if($code.IndexOf('$androidComponentClosure = Assert-AndroidComponentClosure') -gt $code.IndexOf('$apkCopied = Copy-FileIfChanged')){throw 'APK handed off before closure.'}
    if($code.IndexOf('Assert-AndroidNativeSourcePaths -SourceDirectory') -gt $code.IndexOf('$nativeStopwatch =')){throw 'CMake source guard is not early.'}
    if(-not $code.Contains('-ShellComposition $cargoSourceComposition')){throw 'Native proof consumed full app composition.'}
    $phaseFunction=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Invoke-SpatialGradlePhase'},$true))
    if($phaseFunction.Count -ne 1){throw 'Canonical Gradle environment body is not shared.'}
    $phaseCalls=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -ceq 'Invoke-SpatialGradlePhase'},$true))
    if($phaseCalls.Count -ne 2 -or $phaseCalls[0].Extent.Text -notlike '*-Phase JvmPreflight*' -or
        $phaseCalls[1].Extent.Text -notlike '*-Phase Assemble*'){throw 'Canonical caller did not select exactly the two fixed phases.'}
    if($phaseCalls[0].Extent.StartOffset -gt $code.IndexOf('$cargoCommand =')){throw 'JVM preflight starts after native setup.'}
    if($phaseCalls[1].Extent.StartOffset -lt $code.IndexOf('$nativeJniUpdated =')){throw 'Final assembly precedes native publication.'}
    # Execute the actual one-body projection/finally with a modeled failing
    # transport. Compiler qualification is the separate real Android fixture.
    Invoke-Expression $phaseFunction[0].Extent.Text
    $AndroidHome=$scratch;$NdkHome=Join-Path $scratch 'ndk';$JavaHome=$scratch
    $gradleUserHome=Join-Path $scratch 'gradle-home';$gradleBat=$fakeGradle
    $selectedGradleSha256=Get-FileSha256 $gradleBat
    $EmbeddedDuplexSetupHorizonMs=300000;$resolvedProductId='fixture';$appBuildDir=$scratch;$rootBuildDir=$scratch
    $resolvedAppId='fixture.preflight';$resolvedAppLabel='Fixture';$resolvedDistortionSpeedScale=1.0
    $resolvedDepthAlignmentDefaultLeftX=0.0;$resolvedDepthAlignmentDefaultLeftY=0.0
    $resolvedDepthAlignmentDefaultRightX=0.0;$resolvedDepthAlignmentDefaultRightY=0.0
    $CameraProjectionDefaultEnabled=$false;$ImmersiveVideoDefaultEnabled=$false
    $resolvedImmersiveVideoDefaultOfflinePackId='';$ZoneCompositorDefaultPreset='';$productBuildRoot=$scratch
    $ParticleLayerCarrierDefault='';$StartInParticleViewDefault=$false;$PanelLauncherVisibleDefault=$false
    $HandAlignmentEnabledDefault=$false;$HandAlignmentViewerMarkersEnabledDefault=$false
    $HandAlignmentMappingProfileDefault='';$HandBillboardFlockEnabledDefault=$false;$HandBillboardSourceDefault=''
    $resolvedOfflineMediaPackAssetDir='';$resolvedOfflineMediaKeyHex='';$resolvedHandMeshRigAssetDir='';$Keystore=''
    $resolvedPrivateFeatureSourceDir='';$resolvedPrivateFeatureAssetDir='';$resolvedPrivateFeatureResourceDir='';$embeddedDuplexAssetRoot=''
    $PublicationBuild=$false;$gradleProjectCacheDir=$scratch;$kotlinProjectPersistentDir=$scratch;$shortAapt2=$fakeGradle
    $cmakeStaging=@{path=$scratch};$androidComponentInventoryPath='';$androidComponentSelection=$null
    $UseSourceCompositionVersionName=$false;$sourceRuntimeStampEnabled=$false;$BuildType='Debug';$gradleTimingInitPath=$fakeGradle;$PythonPath='python'
    New-Item -ItemType Directory -Path (Join-Path $scratch 'platforms/android-34') -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $scratch 'platforms/android-34/android.jar'),'modeled transport only')
    function Assert-QuestBuildSourceCompositionCurrent { param($Composition,[switch]$AllowWorkingTreeChanges) }
    function Assert-ModeledProjection {
        if($env:ANDROID_HOME -cne $scratch -or $env:RUSTY_QUEST_SPATIAL_PRODUCT_ID -cne 'fixture' -or
            -not [string]::IsNullOrEmpty($env:RUSTY_QUEST_SPATIAL_UNSELECTED_FIXTURE)){throw 'Selected Gradle projection was not applied.'}
        throw 'modeled-selected-gradle-failure'
    }
    function Invoke-AndroidComponentJvmCompilation { param($GradlePath,$ExpectedGradleSha256,$ProjectDirectory,$GradleUserHome,$BuildType,$Arguments,$InventoryPath,$ExpectedInventorySha256,$EvidenceDirectory,$PythonPath) Assert-ModeledProjection }
    function Invoke-AndroidComponentGradlePhase { param($GradlePath,$ExpectedGradleSha256,$ProjectDirectory,$GradleUserHome,$BuildType,$Arguments,$InventoryPath,$ExpectedInventorySha256,$EvidenceDirectory,$Phase,$PythonPath) Assert-ModeledProjection }
    $savedAmbient=$env:RUSTY_QUEST_SPATIAL_UNSELECTED_FIXTURE
    try{
        $env:RUSTY_QUEST_SPATIAL_UNSELECTED_FIXTURE='unselected-synthetic-value'
        foreach($BuildMode in @('DevFast','Candidate')){foreach($phase in @('JvmPreflight','Assemble')){
            $beforeEnvironment=@{};Get-ChildItem Env: | ForEach-Object {$beforeEnvironment[$_.Name]=[string]$_.Value}
            try{$null=Invoke-SpatialGradlePhase -Phase $phase -ComponentEvidenceDirectory '';throw 'Modeled failure was lost.'}
            catch{if($_.Exception.Message -cne 'modeled-selected-gradle-failure'){throw}}
            $afterEnvironment=@{};Get-ChildItem Env: | ForEach-Object {$afterEnvironment[$_.Name]=[string]$_.Value}
            if($beforeEnvironment.Count -ne $afterEnvironment.Count){
                $added=@($afterEnvironment.Keys|Where-Object {-not $beforeEnvironment.ContainsKey($_)})
                $removed=@($beforeEnvironment.Keys|Where-Object {-not $afterEnvironment.ContainsKey($_)})
                throw ('Gradle failure changed parent environment shape; added names='+($added -join ',')+'; removed names='+($removed -join ','))
            }
            foreach($key in $beforeEnvironment.Keys){
                if(-not $afterEnvironment.ContainsKey($key) -or $afterEnvironment[$key] -cne $beforeEnvironment[$key]){throw 'Gradle failure did not restore parent environment.'}
            }
        }}
    }finally{$env:RUSTY_QUEST_SPATIAL_UNSELECTED_FIXTURE=$savedAmbient}
    # Real shared executable pre/post guard, modeled compiler transport only:
    # changing the selected launcher between phases cannot become a fresh pin.
    function Invoke-AndroidComponentGradlePhase {
        param($GradlePath,$ExpectedGradleSha256,$ProjectDirectory,$GradleUserHome,$BuildType,$Arguments,$InventoryPath,$ExpectedInventorySha256,$EvidenceDirectory,$Phase,$PythonPath)
        & $actualGradlePhaseCommand @PSBoundParameters
    }
    function Invoke-AndroidComponentJvmCompilation {
        param($GradlePath,$ExpectedGradleSha256,$ProjectDirectory,$GradleUserHome,$BuildType,$Arguments,$InventoryPath,$ExpectedInventorySha256,$EvidenceDirectory,$PythonPath)
        $execution=& $actualGradlePhaseCommand @PSBoundParameters -Phase Assemble
        [pscustomobject]@{status='passed';scope='modeled-compiler-transport-only';inventory_sha256='';compiled_sha256='d'*64;gradle_inputs_sha256='e'*64;execution=$execution}
    }
    $firstChild=Join-Path $scratch 'first-phase-child';$secondChild=Join-Path $scratch 'second-phase-child'
    [IO.File]::WriteAllText($fakeGradle,"@echo off`necho first> `"$firstChild`"`nexit /b 0`n")
    $selectedGradleSha256=Get-FileSha256 $fakeGradle
    $BuildMode='DevFast';$phaseReceipts=[Collections.Generic.List[object]]::new()
    $null=Invoke-SpatialGradlePhase -Phase JvmPreflight -ComponentEvidenceDirectory ''
    if(-not(Test-Path $firstChild)){throw 'Launcher pin control did not execute its first modeled child.'}
    [IO.File]::WriteAllText($fakeGradle,"@echo off`necho second> `"$secondChild`"`nexit /b 0`n")
    $refused=$false
    try{$null=Invoke-SpatialGradlePhase -Phase Assemble -ComponentEvidenceDirectory ''}
    catch{
        if(Test-Path $secondChild){throw 'Changed launcher reached the second child.'}
        if($_.Exception.Message -notlike '*component-gradle-tool-changed*'){throw}
        $refused=$true
    }
    if(-not $refused -or (Test-Path $secondChild)){throw 'Changed launcher was repinned between phases.'}
    $pins=@($ast.FindAll({param($node)$node -is [Management.Automation.Language.AssignmentStatementAst] -and
        $node.Left -is [Management.Automation.Language.VariableExpressionAst] -and $node.Left.VariablePath.UserPath -ceq 'selectedGradleSha256'},$true))
    if($pins.Count -ne 1 -or $pins[0].Extent.Text -cne '$selectedGradleSha256 = Get-FileSha256 -Path $gradleBat' -or
        $pins[0].Extent.StartOffset -gt $phaseFunction[0].Extent.StartOffset -or
        -not $phaseFunction[0].Extent.Text.Contains('ExpectedGradleSha256=$selectedGradleSha256;')){throw 'Canonical phases did not hold one selected launcher identity.'}
    'Production native identity stable/rotating, actual adapter selection/authenticated-lock refusal, fail-before-handoff controls passed'
} finally {
    $resolved=[IO.Path]::GetFullPath($scratch)
    if($resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolved) -match '^quest-component-seam-[a-f0-9]{32}$'){
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
