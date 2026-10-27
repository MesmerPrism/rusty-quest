param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'))

$ErrorActionPreference='Stop'
$builder=Join-Path $RepoRoot 'tools/Build-SpatialCameraPanelAndroid.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($builder,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Cannot parse canonical builder.'}
$stageParameter=@($ast.ParamBlock.Parameters | Where-Object {$_.Name.VariablePath.UserPath -ceq 'BuildStage'})
if($stageParameter.Count -ne 1){throw 'Canonical builder has no fixed BuildStage selection.'}
$selectStage=[scriptblock]::Create('param('+$stageParameter[0].Extent.Text+') $BuildStage')
if((& $selectStage) -cne 'Full'){throw 'Default stage changed from Full.'}
foreach($stage in @('Full','JvmPreflight')){if((& $selectStage -BuildStage $stage) -cne $stage){throw 'Selected stage changed.'}}
foreach($stage in @('jvmpreflight','Jvm','Assemble','')){
    $denied=$false
    try{$null=& $selectStage -BuildStage $stage}catch{$denied=$true}
    if(-not $denied){throw 'Stage selection accepted an unlisted or inexact value.'}
}
$earlyGuard=$ast.EndBlock.Statements | Where-Object {
    $_ -is [Management.Automation.Language.IfStatementAst] -and
    $_.Extent.Text.StartsWith("if (`$BuildStage -ceq 'JvmPreflight' -and [string]::IsNullOrWhiteSpace(`$AndroidComponentContractPath))")
} | Select-Object -First 1
if($null -eq $earlyGuard){throw 'Missing early contract guard before prerequisite effects.'}
foreach($contract in @('', ' ')){
    $BuildStage='JvmPreflight';$AndroidComponentContractPath=$contract;$denied=$false
    try{Invoke-Expression $earlyGuard.Extent.Text}catch{$denied=$_.Exception.Message -like '*requires a selected Android component contract*'}
    if(-not $denied){throw 'Empty contract reached prerequisite setup.'}
}
foreach($name in @('Set-TextFileIfChanged','Get-FileSha256','Invoke-SpatialGradlePhase')){
    $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true)
    if($null -eq $definition){throw "Missing actual builder function $name"}
    Invoke-Expression $definition.Extent.Text
}
$statements=@($ast.EndBlock.Statements)
$start=@($statements | Where-Object {$_.Extent.Text.StartsWith('$androidJvmCompilation=')})
$native=@($statements | Where-Object {$_.Extent.Text.StartsWith('$cargoCommand =')})
if($start.Count -ne 1 -or $native.Count -ne 1){throw 'Cannot select actual JVM/native dispatch boundary.'}
$dispatch=$ast.Extent.Text.Substring($start[0].Extent.StartOffset,$native[0].Extent.StartOffset-$start[0].Extent.StartOffset)
$failureTrap=$ast.EndBlock.Traps[0].Extent.Text
$caller=[scriptblock]::Create($failureTrap+"`n"+$dispatch+"`n"+'Resolve-QuestSpatialApplication -Name cargo')
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('quest-jvm-stage-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($scratch)
try{
    # All compiler, composition and native leaves are modeled. The actual phase
    # environment/finally and top-level dispatch/trap bodies execute unchanged.
    $AndroidHome=$scratch;$NdkHome=Join-Path $scratch 'ndk';$JavaHome=$scratch
    $gradleUserHome=Join-Path $scratch 'gradle-home';$gradleBat='trapped-gradle';$selectedGradleSha256='a'*64
    $EmbeddedDuplexSetupHorizonMs=300000;$EmbeddedDuplexMediaPolicy='finite';$EmbeddedDuplexFreshSetupPolicy='none';$freshBuildSelection=$null
    $resolvedProductId='fixture';$appBuildDir=$scratch;$rootBuildDir=$scratch;$appRoot=$scratch
    $resolvedAppId='fixture.preflight';$resolvedAppLabel='Fixture';$resolvedDistortionSpeedScale=1.0
    $resolvedDepthAlignmentDefaultLeftX=0.0;$resolvedDepthAlignmentDefaultLeftY=0.0
    $resolvedDepthAlignmentDefaultRightX=0.0;$resolvedDepthAlignmentDefaultRightY=0.0
    $CameraProjectionDefaultEnabled=$false;$ImmersiveVideoDefaultEnabled=$false;$lockedFinalPresentationEnabled=$false
    $resolvedImmersiveVideoDefaultOfflinePackId='';$ZoneCompositorDefaultPreset='off';$productBuildRoot=$scratch
    $ParticleLayerCarrierDefault='fixture';$StartInParticleViewDefault='false';$PanelLauncherVisibleDefault='false'
    $HandAlignmentEnabledDefault='false';$HandAlignmentViewerMarkersEnabledDefault='false'
    $HandAlignmentMappingProfileDefault='fixture';$HandBillboardFlockEnabledDefault='false';$HandBillboardSourceDefault='fixture'
    $resolvedOfflineMediaPackAssetDir='';$resolvedOfflineMediaKeyHex='';$resolvedHandMeshRigAssetDir='';$Keystore=''
    $resolvedPrivateFeatureSourceDir='fixture-source';$resolvedPrivateFeatureAssetDir='fixture-assets';$resolvedPrivateFeatureResourceDir='fixture-resources';$embeddedDuplexAssetRoot=''
    $embeddedDuplexProductInputs=$null;$EnvironmentDepthOwner='disabled';$PublicationBuild=$false
    $gradleProjectCacheDir=$scratch;$kotlinProjectPersistentDir=$scratch;$shortAapt2='trapped-aapt2'
    $cmakeStaging=@{path=$scratch};$sourceBankBuildInputs=@{enabled=$false}
    $UseSourceCompositionVersionName=$false;$BuildType='Debug';$gradleTimingInitPath='trapped-init';$PythonPath='trapped-python'
    [void][IO.Directory]::CreateDirectory((Join-Path $scratch 'platforms/android-34'))
    [IO.File]::WriteAllText((Join-Path $scratch 'platforms/android-34/android.jar'),'modeled SDK leaf')
    $script:phaseCalls=0;$script:nativeCalls=0;$script:failCompiler=$false;$script:failComposition=$false
    function Assert-QuestBuildSourceCompositionCurrent {
        param($Composition,[switch]$AllowWorkingTreeChanges)
        if($script:failComposition -and $script:phaseCalls -gt 0){throw 'modeled-post-compile-source-drift'}
    }
    function Resolve-QuestSpatialApplication { param($Name) $script:nativeCalls++;throw 'trapped-native-setup' }
    function Invoke-AndroidComponentGradlePhase { throw 'Unexpected assembly after JVM stop.' }
    function Invoke-AndroidComponentJvmCompilation {
        param($GradlePath,$ExpectedGradleSha256,$ProjectDirectory,$GradleUserHome,$BuildType,$Arguments,$InventoryPath,$ExpectedInventorySha256,$EvidenceDirectory,$PythonPath)
        $script:phaseCalls++
        if($env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_SRC_DIR -cne 'fixture-source' -or
            $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_ASSET_DIR -cne 'fixture-assets' -or
            $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_RES_DIR -cne 'fixture-resources' -or
            -not [string]::IsNullOrEmpty($env:RUSTY_QUEST_SPATIAL_UNSELECTED_FIXTURE)) {throw 'Selected projection was lost.'}
        if($InventoryPath -cne $androidComponentInventoryPath -or $ExpectedInventorySha256 -cne $androidComponentSelection.sha256){throw 'Original inventory binding was lost.'}
        if($script:failCompiler){$error=[Exception]::new('modeled-compiler-exit-17');$error.Data['exit_code']=17;throw $error}
        [void][IO.Directory]::CreateDirectory($EvidenceDirectory)
        [IO.File]::WriteAllText((Join-Path $EvidenceDirectory 'compiled.json'),'modeled compiled receipt')
        [IO.File]::WriteAllText((Join-Path $EvidenceDirectory 'gradle-inputs.json'),'modeled compiler inputs')
        [pscustomobject]@{status='passed';scope='jvm-compilation-only';inventory_sha256=$ExpectedInventorySha256;
            compiled_sha256=(Get-FileSha256 (Join-Path $EvidenceDirectory 'compiled.json'));
            gradle_inputs_sha256=(Get-FileSha256 (Join-Path $EvidenceDirectory 'gradle-inputs.json'));
            execution=[pscustomobject]@{duration_ms=1}}
    }
    $savedAmbient=$env:RUSTY_QUEST_SPATIAL_UNSELECTED_FIXTURE
    try{
        $env:RUSTY_QUEST_SPATIAL_UNSELECTED_FIXTURE='ambient-fixture'
        foreach($case in @('success','success-candidate','compiler-failure','source-drift','receipt-write-failure','missing-contract','full','full-unverified')){
            $BuildStage=if($case -like 'full*'){'Full'}else{'JvmPreflight'}
            $BuildMode=if($case -ceq 'success-candidate'){'Candidate'}else{'DevFast'}
            $BuildType=if($case -ceq 'success-candidate'){'Release'}else{'Debug'}
            $OutDir=Join-Path $scratch $case;[void][IO.Directory]::CreateDirectory($OutDir)
            if($case -ceq 'receipt-write-failure'){[void][IO.Directory]::CreateDirectory((Join-Path $OutDir 'build-phase-receipts.json'))}
            $androidComponentEvidenceDir=Join-Path $OutDir 'android-components';[void][IO.Directory]::CreateDirectory($androidComponentEvidenceDir)
            $androidComponentInventoryPath=Join-Path $androidComponentEvidenceDir 'inventory.json'
            $sourceCompositionPath=Join-Path $OutDir 'source-composition.json'
            [IO.File]::WriteAllText($androidComponentInventoryPath,'original inventory')
            [IO.File]::WriteAllText($sourceCompositionPath,'original composition')
            $sourceComposition=@{fingerprint='b'*64}
            $androidComponentSelection=if($case -in @('missing-contract','full-unverified')){$null}else{@{sha256=(Get-FileSha256 $androidComponentInventoryPath)}}
            $phaseReceipts=[Collections.Generic.List[object]]::new();$buildStartedUtc=[DateTimeOffset]::UtcNow
            $script:phaseCalls=0;$script:nativeCalls=0;$script:failCompiler=$case -ceq 'compiler-failure';$script:failComposition=$case -ceq 'source-drift'
            $buildLaneMutexOwned=$true;$apkInspectionCopy=$null
            $buildLaneMutex=[pscustomobject]@{released=$false;disposed=$false}
            $observedMutex=$buildLaneMutex
            $buildLaneMutex | Add-Member ScriptMethod ReleaseMutex {$this.released=$true}
            $buildLaneMutex | Add-Member ScriptMethod Dispose {$this.disposed=$true}
            $before=@{};Get-ChildItem Env: | ForEach-Object {$before[$_.Name]=[string]$_.Value}
            $caught=$null;$output=$null
            try{$output=@(& $caller)}catch{$caught=$_}
            $after=@{};Get-ChildItem Env: | ForEach-Object {$after[$_.Name]=[string]$_.Value}
            if($before.Count -ne $after.Count){throw 'Caller changed environment shape.'}
            foreach($key in $before.Keys){if($after[$key] -cne $before[$key]){throw "Caller did not restore environment $key"}}
            if(-not $observedMutex.released -or -not $observedMutex.disposed){throw "Caller leaked build lane on $case"}
            $resultPath=Join-Path $OutDir 'jvm-preflight-result.json'
            if($case -like 'success*'){
                if($null -ne $caught){throw $caught}
                if($script:phaseCalls -ne 1 -or $script:nativeCalls -ne 0){throw 'JVM stop reached native setup or skipped compiler.'}
                if($output.Count -ne 1 -or $output[0] -cne $resultPath){throw 'JVM stop did not return only its result path.'}
                $result=Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
                if($result.schema -cne 'rusty.quest.spatial_camera_panel.jvm_preflight_result.v1' -or $result.status -cne 'phase-complete' -or
                    $result.build_stage -cne 'JvmPreflight' -or $result.apk_produced -ne $false -or $result.runtime_authority -ne $false){throw 'JVM result overclaimed.'}
                if($result.build_type -cne $BuildType -or $result.app_id -cne 'fixture.preflight' -or $result.product_id -cne 'fixture'){throw 'Result lost selected product or variant.'}
                foreach($reference in @($result.source_composition,$result.android_components.inventory,$result.android_components.jvm_inputs,$result.android_components.jvm_compilation,$result.build_phase_receipts)){
                    if($reference.sha256 -cne (Get-FileSha256 $reference.path)){throw 'Result lost original evidence reference.'}
                }
                if($result.android_components.inventory.path -cne $androidComponentInventoryPath -or
                    $result.android_components.jvm_compilation.path -cne (Join-Path $androidComponentEvidenceDir 'jvm-preflight/compiled.json')){throw 'Result replaced original evidence paths.'}
                $receipt=Get-Content $result.build_phase_receipts.path -Raw | ConvertFrom-Json
                if($receipt.schema -cne 'rusty.quest.spatial_camera_panel.build_phase_receipts.v1' -or @($receipt.phases).Count -ne 1 -or
                    $receipt.phases[0].phase -cne 'android-jvm-preflight'){throw 'Result did not retain existing phase receipt scope.'}
            }else{
                if($null -eq $caught){throw "Expected caller refusal on $case"}
                if(Test-Path -LiteralPath $resultPath){throw 'Failed or Full caller fabricated JVM result.'}
                if($case -ceq 'compiler-failure' -and ($caught.Exception.Message -cne 'modeled-compiler-exit-17' -or $caught.Exception.Data['exit_code'] -ne 17)){throw 'Caller replaced compiler failure.'}
                if($case -ceq 'source-drift' -and $caught.Exception.Message -cne 'modeled-post-compile-source-drift'){throw 'Caller lost final composition guard.'}
                if($case -ceq 'receipt-write-failure' -and ($script:phaseCalls -ne 1 -or $script:nativeCalls -ne 0)){throw 'Result write failure reached native setup or skipped compiler.'}
                if($case -ceq 'missing-contract' -and ($script:phaseCalls -ne 0 -or $script:nativeCalls -ne 0 -or $caught.Exception.Message -notlike '*requires a selected Android component contract*')){throw 'JVM stop accepted unverified selection.'}
                if($case -like 'full*' -and ($script:nativeCalls -ne 1 -or $caught.Exception.Message -cne 'trapped-native-setup' -or $script:phaseCalls -ne $(if($case -ceq 'full'){1}else{0}))){throw 'Full dispatch behavior changed.'}
            }
            foreach($forbidden in @('run-capsule.json','build-manifest.json','app.apk')){if(Test-Path (Join-Path $OutDir $forbidden)){throw 'Host stage created an APK handoff artifact.'}}
        }
    }finally{$env:RUSTY_QUEST_SPATIAL_UNSELECTED_FIXTURE=$savedAmbient}
    Write-Host 'Actual canonical JVM stage/default/dispatch/environment/trap controls passed; modeled compiler leaves only; no build, shared-cache or device operations.'
}finally{
    $resolved=[IO.Path]::GetFullPath($scratch)
    if($resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolved) -match '^quest-jvm-stage-[a-f0-9]{32}$'){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
