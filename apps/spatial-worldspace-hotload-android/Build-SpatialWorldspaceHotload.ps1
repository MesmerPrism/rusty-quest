[CmdletBinding()]
param(
    [ValidateSet('Candidate')][string]$Mode='Candidate',
    [Parameter(Mandatory=$true)][string]$AndroidHome,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$DebugKeystore,
    [string]$GradleUserHome,
    [string]$AgentBoardScript,
    [string]$BuildLeaseId,
    [string]$IntermediateRoot,
    [string]$OutputRoot,
    [Parameter(Mandatory=$true)][string]$WorkloadInputsDirectory,
    [string]$ExternalSourceDirectory,
    [Parameter(Mandatory=$true)][string]$OpenXrIncludeDirectory,
    [Parameter(Mandatory=$true)][string]$HandShaderPath,
    [Parameter(Mandatory=$true)][string]$HotloadInputsDirectory,
    [switch]$SubmissionObserver,
    [switch]$SdkGraphicsHotReload,
    [switch]$DevelopmentFeedback,
    [switch]$ExternalWorkloadHotload,
    [string]$SubmissionObserverIncludeDirectory,
    [switch]$SessionCore,
    [string]$SessionCompositionManifest,
    [string]$PrivateAppIdentityManifest,
    [switch]$Offline,
    [switch]$VerifyOnly
)
$ErrorActionPreference='Stop'
if ($PSVersionTable.PSVersion -lt [version]'7.6') { throw 'Requires pwsh 7.6 or newer.' }
function Resolve-SpatialWorkloadBackend {
    param([string]$SourceDirectory,[bool]$SessionCore,[bool]$ExternalWorkloadHotload)
    if([string]::IsNullOrWhiteSpace($SourceDirectory)){
        if($SessionCore -or $ExternalWorkloadHotload){throw 'SessionCore and external workload hotload require an explicit external workload source.'}
        return [pscustomobject]@{selection='unavailable';source_directory=''}
    }
    $resolved=(Resolve-Path -LiteralPath $SourceDirectory -ErrorAction Stop).Path
    if(-not(Test-Path -LiteralPath $resolved -PathType Container) -or
       -not(Test-Path -LiteralPath (Join-Path $resolved 'CMakeLists.txt') -PathType Leaf)){
        throw 'Explicit external compute module must be a directory containing CMakeLists.txt.'
    }
    return [pscustomobject]@{selection='external';source_directory=$resolved}
}
$workloadBackend=Resolve-SpatialWorkloadBackend -SourceDirectory $ExternalSourceDirectory -SessionCore ([bool]$SessionCore) -ExternalWorkloadHotload ([bool]$ExternalWorkloadHotload)
if($DevelopmentFeedback -and ($SessionCore -or $ExternalWorkloadHotload -or $SubmissionObserver -or $workloadBackend.selection -ne 'unavailable')){throw 'Public development feedback excludes session, external backend and observer owners.'}
$appRoot=[IO.Path]::GetFullPath($PSScriptRoot)
$repoRoot=[IO.Path]::GetFullPath((Join-Path $appRoot '../..'))
$relativeRoot='apps/spatial-worldspace-hotload-android'
$observerRelativeRoot='android/openxr-submission-observer'
Import-Module (Join-Path $appRoot 'SpatialSessionComposition.psm1') -Force
Import-Module (Join-Path $appRoot 'SpatialWorkloadPermissions.psm1') -Force
Import-Module (Join-Path $appRoot 'SpatialNativeDependencies.psm1') -Force
if(-not $SessionCore -and (-not [string]::IsNullOrWhiteSpace($SessionCompositionManifest) -or -not [string]::IsNullOrWhiteSpace($PrivateAppIdentityManifest))){throw 'Session inputs require explicit SessionCore selection.'}
if($SessionCore -and (-not $SubmissionObserver -or [string]::IsNullOrWhiteSpace($SessionCompositionManifest) -or [string]::IsNullOrWhiteSpace($PrivateAppIdentityManifest))){throw 'SessionCore requires actual submission observer and exact composition/identity manifests.'}
$session=if($SessionCore){Read-SpatialSessionComposition -Manifest $SessionCompositionManifest -IdentityManifest $PrivateAppIdentityManifest}else{$null}
$packageName=if($SessionCore){[string]$session.identity.application_id}else{'io.github.mesmerprism.rustyquest.spatial_worldspace_hotload'}
$activityName='io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.SpatialWorldspaceHotloadActivity'
if(-not $SubmissionObserver -and -not [string]::IsNullOrWhiteSpace($SubmissionObserverIncludeDirectory)){throw 'Observer headers require explicit SubmissionObserver selection.'}
$contractPath=Join-Path $appRoot 'workload-build.lock.json'
$contract=Get-Content -LiteralPath $contractPath -Raw | ConvertFrom-Json
if($contract.schema -ne 'rusty.quest.spatial_worldspace_hotload.build_contract.v1' -or
   $contract.sdk -ne '0.14.0' -or $contract.sdk_plugin -ne '0.14.0' -or
   $contract.package_name -ne 'io.github.mesmerprism.rustyquest.spatial_worldspace_hotload') {
    throw 'Unexpected standalone probe build contract.'
}
$catalog=Get-Content -LiteralPath (Join-Path $appRoot 'gradle/libs.versions.toml') -Raw
foreach($pin in @{spatial=$contract.sdk;agp=$contract.agp;kotlin=$contract.kotlin}.GetEnumerator()) {
    $pattern='(?m)^'+[regex]::Escape($pin.Key)+'\s*=\s*"'+[regex]::Escape([string]$pin.Value)+'"\s*$'
    if($catalog -notmatch $pattern){throw "Dependency declaration differs from build contract: $($pin.Key)"}
}
$wrapper=Get-Content -LiteralPath (Join-Path $appRoot 'gradle/wrapper/gradle-wrapper.properties') -Raw
if($wrapper -notmatch ('gradle-'+[regex]::Escape([string]$contract.gradle)+'-bin\.zip') -or
   $wrapper -notmatch '(?m)^distributionSha256Sum=[a-f0-9]{64}\s*$'){throw 'Gradle wrapper pin/checksum missing or mismatched.'}
$gradleScript=Get-Content -LiteralPath (Join-Path $appRoot 'app/build.gradle.kts') -Raw
if($gradleScript -notmatch ('ndkVersion\s*=\s*"'+[regex]::Escape([string]$contract.ndk)+'"') -or
   $gradleScript -notmatch ('buildToolsVersion\s*=\s*"'+[regex]::Escape([string]$contract.build_tools)+'"')){throw 'Android toolchain declaration differs from build contract.'}
function FileRecord([string]$path) {
    $file=Get-Item -LiteralPath $path
    [ordered]@{path=$file.FullName;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
}
function TextHash([string]$text) {
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text))).ToLowerInvariant()
}
function WriteJson([string]$path,$value) {
    [IO.File]::WriteAllText($path,($value|ConvertTo-Json -Depth 30)+"`n",[Text.UTF8Encoding]::new($false))
}
function SourceInventory([string]$root=$relativeRoot) {
    $paths=@(& git -C $repoRoot -c core.quotePath=false ls-files --cached --others --exclude-standard -- $root)
    if($LASTEXITCODE -ne 0){throw 'Cannot inventory probe source.'}
    @($paths | Sort-Object -Unique | ForEach-Object {
        $record=FileRecord (Join-Path $repoRoot $_)
        [ordered]@{path=$_;bytes=$record.bytes;sha256=$record.sha256}
    })
}
# External payloads remain outside tracked public source. Bind every byte before staging.
$externalRoots=[ordered]@{}
if($workloadBackend.selection -eq 'external'){$externalRoots['private-source']=$workloadBackend.source_directory}
$sceneInputsKey=if($DevelopmentFeedback){'development-inputs'}else{'private-inputs'}
$externalRoots[$sceneInputsKey]=(Resolve-Path -LiteralPath $WorkloadInputsDirectory).Path
$externalRoots['openxr-include']=(Resolve-Path -LiteralPath $OpenXrIncludeDirectory).Path
$externalRoots['hotload-inputs']=(Resolve-Path -LiteralPath $HotloadInputsDirectory).Path
if($SubmissionObserver){
    if([string]::IsNullOrWhiteSpace($SubmissionObserverIncludeDirectory)){throw 'Selected observer requires explicit official header directory.'}
    $externalRoots['observer-openxr-include']=(Resolve-Path -LiteralPath $SubmissionObserverIncludeDirectory).Path
    foreach($header in @('openxr.h','openxr_platform_defines.h','openxr_loader_negotiation.h')){
        if(-not(Test-Path -LiteralPath (Join-Path $externalRoots['observer-openxr-include'] ('openxr/'+$header)))){throw "Observer header absent: $header"}
    }
}
$HandShaderPath=(Resolve-Path -LiteralPath $HandShaderPath).Path
if(-not(Test-Path -LiteralPath (Join-Path $externalRoots[$sceneInputsKey] 'assets/workload/manifest.json'))){throw 'Prepared workload asset manifest is required.'}
if(-not(Test-Path -LiteralPath (Join-Path $externalRoots['openxr-include'] 'openxr/openxr.h'))){throw 'Explicit OpenXR headers required.'}
function ExternalInventory {
    $result=@()
    foreach($name in $externalRoots.Keys){
        foreach($file in @(Get-ChildItem -LiteralPath $externalRoots[$name] -File -Recurse | Sort-Object FullName)){
            $relative=[IO.Path]::GetRelativePath($externalRoots[$name],$file.FullName).Replace('\','/')
            if($relative.StartsWith('../') -or $file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Unsafe external input path.'}
            $r=FileRecord $file.FullName
            $result+=[ordered]@{path=$name+'/'+$relative;source=$file.FullName;bytes=$r.bytes;sha256=$r.sha256}
        }
    }
    $h=FileRecord $HandShaderPath
    $result+=[ordered]@{path='public-hand/gpu_hand_skinning.comp';source=$HandShaderPath;bytes=$h.bytes;sha256=$h.sha256}
    $result
}
if(-not(Test-Path -LiteralPath (Join-Path $externalRoots['hotload-inputs'] 'assets/hotload/catalog.json')) -or
   -not(Test-Path -LiteralPath (Join-Path $externalRoots['hotload-inputs'] 'native-catalog/hotload_catalog.h'))){throw 'Explicit frozen SDK/native hotload catalogs required.'}
$hotloadCatalog=Get-Content -Raw -LiteralPath (Join-Path $externalRoots['hotload-inputs'] 'assets/hotload/catalog.json')|ConvertFrom-Json
if($hotloadCatalog.schema -cne 'rusty.quest.sdk_hotload.catalog.v1' -or $hotloadCatalog.sdkVersion -cne '0.14.0' -or $hotloadCatalog.batches.Count -ne 3){throw 'Closed SDK hotload catalog rejected'}
if($null -eq $hotloadCatalog.PSObject.Properties['materialUniformBytes'] -or $hotloadCatalog.materialUniformBytes -ne 80){throw 'Fresh reflected five-row material catalog required; old 32-byte catalogs are incompatible.'}
$batchNames=@($hotloadCatalog.batches|ForEach-Object{[string]$_.name})
if(($batchNames|Sort-Object)-join ',' -cne 'baseline,offset,tint'){throw 'Unexpected SDK hotload preset set'}
foreach($batch in $hotloadCatalog.batches){
 if($batch.files.Count -ne 12 -or $batch.vertexPaths.Count -ne 8 -or $batch.fragmentPaths.Count -ne 4){throw 'Required full shader variants missing'}
 foreach($shader in $batch.files){
  if($shader.path -notmatch '^workload\.(vert|frag)(\.skin|\.2uv|\.tangent)?(\.mv)?\.spv$'){throw 'Unlisted hotload shader path'}
  $record=FileRecord (Join-Path $externalRoots['hotload-inputs'] ('assets/hotload/'+$batch.name+'/'+$shader.path))
  if($record.sha256 -cne $shader.sha256 -or $record.bytes -ne $shader.bytes -or $shader.abiSha256 -notmatch '^[0-9a-f]{64}$'){throw 'Hotload shader byte/ABI receipt mismatch'}
  $stage=if($shader.path.StartsWith('workload.vert')){'vert'}else{'frag'}
  $currentSource=FileRecord (Join-Path $appRoot ('app/src/shaders/workload.'+$stage))
  if($shader.originalSourceSha256 -cne $currentSource.sha256){throw 'Hotload catalog was compiled from different current material source.'}
 }
}
$nativeHotloadAssets=@(Read-NativeHotloadAssetClosure -Root $externalRoots['hotload-inputs'])
$sessionInventory=@()
if($SessionCore){
    $sessionInventory+=@($session.assets)
    foreach($kind in @('core_source_files','inputs')){
        $index=0
        foreach($row in $session.inventories[$kind]){
            $sessionInventory+=[ordered]@{path=('session-inputs/'+$kind+'/'+$index+'/'+[IO.Path]::GetFileName($row.path));source=$row.path;bytes=$row.bytes;sha256=$row.sha256};$index++
        }
    }
    foreach($row in $session.inventories.java_source_files){$sessionInventory+=[ordered]@{path=('session-java/'+[IO.Path]::GetFileName($row.path));source=$row.path;bytes=$row.bytes;sha256=$row.sha256}}
    foreach($row in $session.inventories.core_libraries){$sessionInventory+=[ordered]@{path=('session-libraries/arm64-v8a/'+[IO.Path]::GetFileName($row.path));source=$row.path;bytes=$row.bytes;sha256=$row.sha256}}
    $row=$session.service_header;$sessionInventory+=[ordered]@{path='session-include/rq_experiment_services.h';source=$row.path;bytes=$row.bytes;sha256=$row.sha256}
    foreach($path in @($SessionCompositionManifest,$PrivateAppIdentityManifest)){$row=FileRecord $path;$sessionInventory+=[ordered]@{path=('session-contract/'+[IO.Path]::GetFileName($path));source=$row.path;bytes=$row.bytes;sha256=$row.sha256}}
}
$externalInventory=@(ExternalInventory)
$externalInventory+=@($sessionInventory)
$externalHash=TextHash ($externalInventory|ConvertTo-Json -Depth 10 -Compress)
$assetManifest=Get-Content -LiteralPath (Join-Path $externalRoots[$sceneInputsKey] 'assets/workload/manifest.json') -Raw|ConvertFrom-Json
if($DevelopmentFeedback){
    if($assetManifest.schema -cne 'rusty.quest.spatial_worldspace_hotload.development_scene.v1' -or $assetManifest.source -cne 'public-procedural' -or $assetManifest.backend -cne 'none' -or $assetManifest.handSource -cne 'none' -or $assetManifest.mainCapacity -ne 512 -or $assetManifest.tracerCapacity -ne 0 -or $assetManifest.handsCount -ne 0 -or $assetManifest.graftsCount -ne 0 -or $assetManifest.provenance.privateInputs -ne $false){throw 'Explicit public development scene required.'}
    if(@($assetManifest.assets).Count -ne 1 -or $assetManifest.assets[0].path -cne 'workload/mask.png'){throw 'Public development scene must contain only its procedural mask.'}
    foreach($pair in @{atlasPacketVersion=4;handRecordLayout=1;retainedParticleCapacity=0;particlesOffset=0;particlesCount=512;auxiliaryOffset=512;auxiliaryCount=512;handsOffset=1024;graftsOffset=1024;totalRecords=1028;initialVisibleCount=64}.GetEnumerator()){
        if($assetManifest.PSObject.Properties.Name -cnotcontains $pair.Key -or $assetManifest.($pair.Key) -ne $pair.Value){throw "Public development layout mismatch: $($pair.Key)"}
    }
    if($assetManifest.provenance.privateInputs -isnot [bool] -or $assetManifest.provenance.producer -cne 'Prepare-DevelopmentFeedbackInputs.py' -or $assetManifest.provenance.producerSha256 -cne (FileRecord (Join-Path $appRoot 'Prepare-DevelopmentFeedbackInputs.py')).sha256){throw 'Public scene producer provenance mismatch; regenerate with the selected source.'}
    $publicFiles=@($externalInventory|Where-Object{$_.path.StartsWith('development-inputs/')}|ForEach-Object{$_.path.Substring('development-inputs/'.Length)}|Sort-Object)
    if(($publicFiles -join '|') -cne 'assets/workload/manifest.json|assets/workload/mask.png'){throw 'Public input packet contains unlisted files.'}
}elseif($assetManifest.schema -ne 'rusty.quest.spatial_worldspace_hotload.asset_manifest.v1' -or $assetManifest.mainCapacity -ne 2562 -or $assetManifest.tracerCapacity -ne 17934){throw 'Unsupported genuine workload manifest.'}
if($SessionCore -and ($assetManifest.retainedParticleCapacity -ne 2 -or $assetManifest.instanceCapacity -ne 20498 -or $assetManifest.totalRecords -ne 124304)){throw 'Selected core workload retained-anchor topology mismatch.'}
if(@($assetManifest.assets).Count -eq 0){throw 'Asset inventory missing.'}
foreach($asset in $assetManifest.assets){
    if($asset.path -notmatch '^workload/[A-Za-z0-9_./-]+$' -or $asset.path.Contains('..')){throw 'Unsafe asset name.'}
    $r=FileRecord (Join-Path $externalRoots[$sceneInputsKey] ('assets/'+$asset.path))
    if($r.sha256 -cne $asset.sha256 -or $r.bytes -ne $asset.bytes){throw 'Declared workload asset bytes mismatch.'}
}
$head=([string](& git -C $repoRoot rev-parse HEAD)).Trim()
$tree=([string](& git -C $repoRoot rev-parse 'HEAD^{tree}')).Trim()
if($LASTEXITCODE -ne 0 -or $head -notmatch '^[0-9a-f]{40}$' -or $tree -notmatch '^[0-9a-f]{40}$'){throw 'Exact Git identity unavailable.'}
$trackedStatus=@(& git -C $repoRoot status --porcelain=v1 --untracked-files=no)
$untrackedSource=@(& git -C $repoRoot -c core.quotePath=false ls-files --others --exclude-standard -- $relativeRoot)
if($SubmissionObserver){$untrackedSource+=@(& git -C $repoRoot -c core.quotePath=false ls-files --others --exclude-standard -- $observerRelativeRoot)}
$clean=$trackedStatus.Count -eq 0 -and $untrackedSource.Count -eq 0
if($Mode -eq 'Candidate' -and -not $clean){throw 'Candidate requires clean tracked repository and no untracked probe inputs. Commit the source before candidate packaging.'}
$inventory=@(SourceInventory)
$feedbackRoots=@('android/development-feedback','android/development-feedback-host')
$feedbackInventory=if($DevelopmentFeedback){@($feedbackRoots|ForEach-Object{SourceInventory $_})}else{@()}
if($DevelopmentFeedback -and @($feedbackInventory|Where-Object{$_.path.EndsWith('/ResidentFeedbackHost.java')}).Count -ne 1){throw 'Selected shared resident feedback host source is absent.'}
$feedbackInventoryHash=TextHash ($feedbackInventory|ConvertTo-Json -Depth 8 -Compress)
$observerInventory=if($SubmissionObserver){@(SourceInventory $observerRelativeRoot)}else{@()}
$observerInventoryHash=TextHash ($observerInventory|ConvertTo-Json -Depth 8 -Compress)
if($SubmissionObserver){
    $observerContract=Get-Content -LiteralPath (Join-Path $repoRoot ($observerRelativeRoot+'/packaging-contract.json')) -Raw|ConvertFrom-Json
    if($observerContract.schema -cne 'rusty.quest.xr_submission_observer.packaging.v1' -or $observerContract.default_enabled -ne $false -or
       $observerContract.android_native_library -cne 'libXR_APILAYER_MESMERPRISM_submission_observer.so' -or
       $observerContract.requested_extensions.Count -ne 0 -or $observerContract.required_permissions.Count -ne 0){throw 'Observer packaging contract differs from selected closed route.'}
    if($observerInventory.Count -lt 8){throw 'Incomplete selected observer module inventory.'}
}
if($inventory.Count -lt 10){throw 'Incomplete standalone probe source inventory.'}
$inventoryHash=TextHash ($inventory|ConvertTo-Json -Depth 8 -Compress)
# An empty runtime property surface is intentional and verified against source.
foreach($entry in $inventory | Where-Object {$_.path -match '\.(kt|cpp|h|xml)$'}) {
    $text=[IO.File]::ReadAllText((Join-Path $repoRoot $entry.path))
    if($text -match 'debug\.rustyquest\.|android\.os\.SystemProperties|__system_property_get') {
        throw "Runtime property consumer added outside the closed empty contract: $($entry.path)"
    }
}
$AndroidHome=(Resolve-Path -LiteralPath $AndroidHome).Path
$JavaHome=(Resolve-Path -LiteralPath $JavaHome).Path
$DebugKeystore=(Resolve-Path -LiteralPath $DebugKeystore).Path
$java=Join-Path $JavaHome 'bin/java.exe'
$javaRelease=Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw
if($javaRelease -notmatch 'JAVA_VERSION="17[\.\"]'){throw 'JavaHome must be JDK 17.'}
$ndkRoot=Join-Path $AndroidHome ('ndk/'+$contract.ndk)
$cmakeRoot=Join-Path $AndroidHome ('cmake/'+$contract.cmake)
$toolsRoot=Join-Path $AndroidHome ('build-tools/'+$contract.build_tools)
$toolInputs=@(
    FileRecord $java
    FileRecord (Join-Path $JavaHome 'bin/keytool.exe')
    FileRecord (Join-Path $JavaHome 'release')
    FileRecord (Join-Path $AndroidHome ('platforms/android-'+$contract.android_api+'/android.jar'))
    FileRecord (Join-Path $ndkRoot 'source.properties')
    FileRecord (Join-Path $ndkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe')
    FileRecord (Join-Path $ndkRoot 'shader-tools/windows-x86_64/glslc.exe')
    FileRecord (Join-Path $cmakeRoot 'bin/cmake.exe')
    FileRecord (Join-Path $cmakeRoot 'bin/ninja.exe')
    FileRecord (Join-Path $toolsRoot 'apksigner.bat')
    FileRecord (Join-Path $toolsRoot 'lib/apksigner.jar')
    FileRecord (Join-Path $toolsRoot 'aapt2.exe')
)
$sessionCppRuntime=$null;$systemLibraryInputs=@()
if($SessionCore){
    $sysrootLibraries=Join-Path $ndkRoot 'toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android'
    $sessionCppRuntime=FileRecord (Join-Path $sysrootLibraries 'libc++_shared.so')
    $null=Get-SpatialElfDependencies -Bytes ([IO.File]::ReadAllBytes($sessionCppRuntime.path))
    # Only public NDK stubs for the selected Android API are system exemptions.
    $systemLibraryInputs=@(Get-ChildItem -LiteralPath (Join-Path $sysrootLibraries ([string]$contract.android_api)) -Filter '*.so' -File |
        Sort-Object Name | ForEach-Object {FileRecord $_.FullName})
    if($systemLibraryInputs.Count -eq 0){throw 'Selected NDK Android system library inventory missing.'}
    $toolInputs+=@($sessionCppRuntime)+$systemLibraryInputs
}
# Check signer admission before staging or running any expensive compiler.
$certificatePre=@(& (Join-Path $JavaHome 'bin/keytool.exe') -exportcert -rfc -alias androiddebugkey -keystore $DebugKeystore -storepass android 2>&1)
if($LASTEXITCODE -ne 0){throw 'Cannot inspect explicit debug signing certificate.'}
$pemPre=[regex]::Match(($certificatePre -join "`n"),'(?s)-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----')
if(-not $pemPre.Success){throw 'Explicit signing certificate missing.'}
$derPre=[Convert]::FromBase64String(($pemPre.Groups[1].Value -replace '\s',''))
$signerPre=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($derPre)).ToLowerInvariant()
if($signerPre -cne $contract.expected_signer_sha256){throw 'Explicit signer differs from workload signer contract before build.'}
$intermediateBase=if([string]::IsNullOrWhiteSpace($IntermediateRoot)){Join-Path $repoRoot 'target/apk-i/sww'}else{[IO.Path]::GetFullPath($IntermediateRoot)}
$outputBase=if([string]::IsNullOrWhiteSpace($OutputRoot)){Join-Path $repoRoot 'target/spatial-worldspace-hotload-android/builds'}else{[IO.Path]::GetFullPath($OutputRoot)}
$inputLock=[ordered]@{
    schema='rusty.quest.spatial_worldspace_hotload.build_inputs.v1';mode=$Mode
    contract_sha256=(FileRecord $contractPath).sha256
    source_commit=$head;source_tree=$tree;source_inventory_sha256=$inventoryHash
    toolchain=$toolInputs;signing_keystore=FileRecord $DebugKeystore;signer_sha256=$signerPre
    editor_export=$false;gradle_tasks=@(':app:assembleDebug',':app:writeProbeDependencyInventory')
    external_inputs=$externalInventory;external_inventory_sha256=$externalHash;
    submission_observer_selected=[bool]$SubmissionObserver
    sdk_graphics_hot_reload_selected=[bool]$SdkGraphicsHotReload
    development_feedback_selected=[bool]$DevelopmentFeedback
    development_feedback_source_inventory=$feedbackInventory
    development_feedback_source_inventory_sha256=$feedbackInventoryHash
    external_workload_hotload_selected=[bool]$ExternalWorkloadHotload
    external_workload_backend=$workloadBackend.selection
    session_core_selected=[bool]$SessionCore
    session_cpp_runtime=$sessionCppRuntime
    native_dependency_system_libraries=$systemLibraryInputs
    session_composition_sha256=if($SessionCore){(FileRecord $SessionCompositionManifest).sha256}else{''}
    session_feature_lock=if($SessionCore){$session.feature_lock}else{$null}
    private_app_identity_sha256=if($SessionCore){(FileRecord $PrivateAppIdentityManifest).sha256}else{''}
    android_package_name=$packageName
    local_source_dependencies=@(if($SubmissionObserver){[ordered]@{root=$observerRelativeRoot;inventory_sha256=$observerInventoryHash;files=$observerInventory;staged_root='local-source/submission-observer'}})
    dependency_resolution='pinned-declarations-plus-post-build-artifact-hashes'
    formal_lifecycle_adoption=$false
    mutable_intermediate_root=$intermediateBase;immutable_output_root=$outputBase
}
$fingerprint=TextHash ($inputLock|ConvertTo-Json -Depth 20 -Compress)
$output=Join-Path $outputBase ($Mode.ToLowerInvariant()+'/'+$fingerprint)
$cache=Join-Path $intermediateBase ($Mode.ToLowerInvariant()+'/'+$fingerprint.Substring(0,16))
if($VerifyOnly) {
    [ordered]@{status='inputs-verified-no-build';mode=$Mode;fingerprint=$fingerprint;source_clean=$clean;source_file_count=$inventory.Count;output=$output;cache=$cache}|ConvertTo-Json
    return
}
if([string]::IsNullOrWhiteSpace($AgentBoardScript) -or [string]::IsNullOrWhiteSpace($BuildLeaseId)) {
    throw 'Execution requires explicit AgentBoardScript and active apk-build:rusty-quest BuildLeaseId; VerifyOnly never builds.'
}
$boardJson=@(& pwsh -NoProfile -File $AgentBoardScript status --json)
if($LASTEXITCODE -ne 0){throw 'Agent Board status failed.'}
$board=($boardJson -join "`n")|ConvertFrom-Json
$lease=@($board.active_leases | Where-Object {$_.id -eq $BuildLeaseId -and $_.resource -eq 'apk-build:rusty-quest' -and $_.status -eq 'active'})
if($lease.Count -ne 1){throw 'Supplied build lease is not active for apk-build:rusty-quest.'}
if(Test-Path -LiteralPath $output){throw 'Content address already exists; validate/reuse it or change inputs. Output replacement is deliberately unsupported.'}
if(Test-Path -LiteralPath $cache){throw 'Existing staging address may contain incomplete build state; inspect it, or change inputs. No automatic deletion.'}
[IO.Directory]::CreateDirectory($output)|Out-Null
[IO.Directory]::CreateDirectory($cache)|Out-Null
WriteJson (Join-Path $output 'build-input-lock.json') $inputLock
WriteJson (Join-Path $output 'source-inventory.json') $inventory
WriteJson (Join-Path $output 'build-lease.json') $lease[0]
foreach($entry in $inventory) {
    $relative=$entry.path.Substring($relativeRoot.Length+1)
    $destination=Join-Path $cache $relative
    [IO.Directory]::CreateDirectory((Split-Path $destination))|Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot $entry.path) -Destination $destination
    if((FileRecord $destination).sha256 -ne $entry.sha256){throw "Staging drift: $relative"}
}
foreach($entry in $observerInventory){
    $relative=$entry.path.Substring($observerRelativeRoot.Length+1)
    $destination=Join-Path $cache ('local-source/submission-observer/'+$relative)
    [IO.Directory]::CreateDirectory((Split-Path $destination))|Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot $entry.path) -Destination $destination
    if((FileRecord $destination).sha256 -ne $entry.sha256){throw "Observer staging drift: $relative"}
}
foreach($entry in $feedbackInventory){
    $destination=Join-Path $cache ('local-source/'+$entry.path)
    [IO.Directory]::CreateDirectory((Split-Path $destination))|Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot $entry.path) -Destination $destination
    if((FileRecord $destination).sha256 -ne $entry.sha256){throw 'Shared feedback staging drift.'}
}
foreach($entry in $externalInventory){
    $destination=Join-Path $cache $entry.path
    [IO.Directory]::CreateDirectory((Split-Path $destination))|Out-Null
    Copy-Item -LiteralPath $entry.source -Destination $destination
    if((FileRecord $destination).sha256 -ne $entry.sha256){throw 'External staging drift.'}
}
WriteJson (Join-Path $output 'external-inputs.json') $externalInventory
$previousEnvironment=@{}
$names=@('ANDROID_HOME','ANDROID_SDK_ROOT','ANDROID_NDK_HOME','JAVA_HOME','GRADLE_USER_HOME','GRADLE_OPTS','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','ORG_GRADLE_PROJECT_probeDebugKeystore','PATH')
$names+=@(Get-ChildItem Env: | Where-Object {$_.Name -match '^RUSTY_QUEST_(SPATIAL|NATIVE)|^ORG_GRADLE_PROJECT_|^RQ_(WORKLOAD|HOTLOAD)'} | Select-Object -ExpandProperty Name)
foreach($name in $names | Sort-Object -Unique){$previousEnvironment[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    foreach($name in $previousEnvironment.Keys){if($name -ne 'PATH'){[Environment]::SetEnvironmentVariable($name,$null,'Process')}}
    $env:ANDROID_HOME=$AndroidHome;$env:ANDROID_SDK_ROOT=$AndroidHome;$env:ANDROID_NDK_HOME=$ndkRoot;$env:JAVA_HOME=$JavaHome
    $env:PATH=(Join-Path $JavaHome 'bin')+';'+$previousEnvironment['PATH']
    if([string]::IsNullOrWhiteSpace($GradleUserHome)){$GradleUserHome=Join-Path $repoRoot 'target/apk-i/sww/gradle-user-home'}
    $env:GRADLE_USER_HOME=[IO.Path]::GetFullPath($GradleUserHome)
    foreach($ambientFile in @('gradle.properties','init.gradle','init.gradle.kts')) {
        if(Test-Path -LiteralPath (Join-Path $env:GRADLE_USER_HOME $ambientFile)){throw "GradleUserHome contains ambient configuration: $ambientFile"}
    }
    if(@(Get-ChildItem -LiteralPath (Join-Path $env:GRADLE_USER_HOME 'init.d') -File -ErrorAction SilentlyContinue).Count -gt 0){throw 'GradleUserHome contains ambient init scripts.'}
    $gradleArguments=@('--no-daemon','--max-workers=1','--console=plain','--no-configuration-cache','-Pkotlin.compiler.execution.strategy=in-process','-PspatialEditorExport=false',('-PprobeDebugKeystore='+$DebugKeystore),':app:assembleDebug',':app:writeProbeDependencyInventory')
    $gradleArguments+=@(
        ('-PworkloadInputs='+ (Join-Path $cache $sceneInputsKey))
        ('-PworkloadOpenXrInclude='+ (Join-Path $cache 'openxr-include'))
        ('-PworkloadHandShader='+ (Join-Path $cache 'public-hand/gpu_hand_skinning.comp'))
        ('-PworkloadHotloadAssets='+ (Join-Path $cache 'hotload-inputs/assets'))
        ('-PworkloadHotloadNativeCatalog='+ (Join-Path $cache 'hotload-inputs/native-catalog'))
    )
    if($workloadBackend.selection -eq 'external'){$gradleArguments+=('-PworkloadExternalSource='+(Join-Path $cache 'private-source'))}
    $gradleArguments+=('-PworkloadSubmissionObserver='+([bool]$SubmissionObserver).ToString().ToLowerInvariant())
    $gradleArguments+=('-PworkloadSdkGraphicsHotReload='+([bool]$SdkGraphicsHotReload).ToString().ToLowerInvariant())
    $gradleArguments+=('-PworkloadDevelopmentFeedback='+([bool]$DevelopmentFeedback).ToString().ToLowerInvariant())
    if($DevelopmentFeedback){$gradleArguments+=@(('-PworkloadFeedbackCore='+(Join-Path $cache 'local-source/android/development-feedback')),('-PworkloadFeedbackHost='+(Join-Path $cache 'local-source/android/development-feedback-host')))}
    $gradleArguments+=('-PworkloadExternalWorkloadHotload='+([bool]$ExternalWorkloadHotload).ToString().ToLowerInvariant())
    if($SubmissionObserver){$gradleArguments+=@(
        ('-PworkloadSubmissionObserverModule='+(Join-Path $cache 'local-source/submission-observer'))
        ('-PworkloadSubmissionObserverOpenXrInclude='+(Join-Path $cache 'observer-openxr-include'))
    )}
    $gradleArguments+=('-PworkloadSessionCore='+([bool]$SessionCore).ToString().ToLowerInvariant())
    if($SessionCore){$gradleArguments+=@(
        ('-PworkloadSessionJava='+(Join-Path $cache 'session-java'))
        ('-PworkloadSessionLibraries='+(Join-Path $cache 'session-libraries'))
        ('-PworkloadSessionAssets='+(Join-Path $cache 'session-assets'))
        ('-PworkloadSessionInclude='+(Join-Path $cache 'session-include'))
        ('-PworkloadSessionBinding='+$session.composition.activation_binding_sha256)
        ('-PworkloadPrivateApplicationId='+$packageName)
        ('-PworkloadPrivateAppLabel='+$session.identity.app_label)
    )}
    if($Offline){$gradleArguments+='--offline'}
    Push-Location $cache
    try {
        & ./gradlew.bat @gradleArguments 2>&1 | Tee-Object -FilePath (Join-Path $output 'gradle.log') | Write-Host
        if($LASTEXITCODE -ne 0){throw 'Standalone Gradle probe build failed; retained incomplete output for diagnosis.'}
    } finally {Pop-Location}
    $postExternal=@(ExternalInventory)
    foreach($row in $sessionInventory){$record=FileRecord $row.source;if($record.bytes -ne $row.bytes -or $record.sha256 -cne $row.sha256){throw 'Selected session input drifted during compilation.'}}
    $postExternal+=@($sessionInventory)
    if((TextHash ($postExternal|ConvertTo-Json -Depth 10 -Compress)) -ne $externalHash){throw 'External inputs drifted during compilation.'}
    $postInventory=@(SourceInventory)
    $postFeedbackInventory=if($DevelopmentFeedback){@($feedbackRoots|ForEach-Object{SourceInventory $_})}else{@()}
    if((TextHash ($postFeedbackInventory|ConvertTo-Json -Depth 8 -Compress)) -cne $feedbackInventoryHash){throw 'Shared feedback source drift; no capsule issued.'}
    if((TextHash ($postInventory|ConvertTo-Json -Depth 8 -Compress)) -ne $inventoryHash){throw 'Live probe source drifted during compilation; no capsule issued.'}
    $postObserverInventory=if($SubmissionObserver){@(SourceInventory $observerRelativeRoot)}else{@()}
    if((TextHash ($postObserverInventory|ConvertTo-Json -Depth 8 -Compress)) -ne $observerInventoryHash){throw 'Selected observer module drifted during compilation; no capsule issued.'}
    if($Mode -eq 'Candidate' -and @(& git -C $repoRoot status --porcelain=v1 --untracked-files=no).Count -ne 0){throw 'Candidate tracked source drifted during compilation.'}
    foreach($tool in $toolInputs){if((FileRecord $tool.path).sha256 -ne $tool.sha256){throw 'Toolchain changed during compilation.'}}
    if((FileRecord $DebugKeystore).sha256 -ne $inputLock.signing_keystore.sha256){throw 'Signing keystore changed during compilation.'}
    $apk=Join-Path $output 'spatial-worldspace-hotload-debug.apk'
    Copy-Item -LiteralPath (Join-Path $cache 'app/build/outputs/apk/debug/app-debug.apk') -Destination $apk
    $zip=[IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $closedAssets=@($nativeHotloadAssets)
        $closedAssets+=@($externalInventory|Where-Object{$_.path.StartsWith($sceneInputsKey+'/assets/')}|ForEach-Object{[ordered]@{path=$_.path.Substring(($sceneInputsKey+'/').Length);bytes=$_.bytes;sha256=$_.sha256}})
        $closedAssets+=@($externalInventory|Where-Object{$_.path.StartsWith('hotload-inputs/assets/')}|ForEach-Object{[ordered]@{path=$_.path.Substring('hotload-inputs/'.Length);bytes=$_.bytes;sha256=$_.sha256}})
        $closedAssets+=@($sessionInventory|Where-Object{$_.path.StartsWith('session-assets/')}|ForEach-Object{[ordered]@{path=('assets/'+$_.path.Substring('session-assets/'.Length));bytes=$_.bytes;sha256=$_.sha256}})
        $closedAssets+=@($sessionInventory|Where-Object{$_.path.StartsWith('session-libraries/')}|ForEach-Object{[ordered]@{path=('lib/'+$_.path.Substring('session-libraries/'.Length));bytes=$_.bytes;sha256=$_.sha256}})
        if(($null -ne $zip.GetEntry('assets/feature-lock.json')) -ne [bool]$SessionCore){throw 'Canonical session feature-lock selection mismatch.'}
        $externalMarkers=@($zip.Entries|Where-Object FullName -CEQ 'assets/external-workload-hotload.json')
        if($externalMarkers.Count -gt 1){throw 'Duplicate external workload hotload marker.'}
        $externalMarker=if($externalMarkers.Count -eq 1){$externalMarkers[0]}else{$null}
        if(($null -ne $externalMarker) -ne [bool]$ExternalWorkloadHotload){throw 'External workload hotload selection marker mismatch.'}
        if($ExternalWorkloadHotload){
            if($externalMarker.Length -lt 1 -or $externalMarker.Length -gt 4096){throw 'External workload hotload marker size.'}
            $reader=[IO.StreamReader]::new($externalMarker.Open())
            try{$marker=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
            if($marker.schema -cne 'rusty.quest.hotload.external_workload_build.v1' -or $marker.selected -ne $true -or $marker.debugOnly -ne $true -or $marker.slot -cne 'external-workload-v1' -or $marker.maximumCodeBytes -ne 262144){throw 'External workload hotload marker contract mismatch.'}
        }
        foreach($row in $closedAssets){
            $entries=@($zip.Entries|Where-Object FullName -CEQ $row.path)
            if($entries.Count -ne 1 -or $entries[0].Length -ne $row.bytes){throw "Closed APK member missing/duplicate/size mismatch: $($row.path)"}
            $stream=$entries[0].Open();try{$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
            if($hash -cne $row.sha256){throw "Closed APK member bytes changed: $($row.path)"}
        }
        if(($null -ne $zip.GetEntry('lib/arm64-v8a/librusty_quest_sdk_session_core.so')) -ne [bool]$SessionCore){throw 'Session library selection mismatch.'}
        $observerEntries=@('assets/openxr/1/api_layers/implicit.d/XR_APILAYER_MESMERPRISM_submission_observer.json',
            'lib/arm64-v8a/libXR_APILAYER_MESMERPRISM_submission_observer.so','lib/arm64-v8a/librq_xr_submission_observer_probe.so')
        foreach($entry in $observerEntries){
            if(@($zip.Entries|Where-Object{$_.FullName -ceq $entry}).Count -gt 1){throw "Duplicate observer APK entry: $entry"}
            if(($null -ne $zip.GetEntry($entry)) -ne [bool]$SubmissionObserver){throw "Observer APK selection mismatch: $entry"}
        }
        if($SubmissionObserver){
            $asset=$zip.GetEntry($observerEntries[0])
            $expectedAsset=@($observerInventory|Where-Object{$_.path -ceq ($observerRelativeRoot+'/'+$observerEntries[0])})
            if($expectedAsset.Count -ne 1){throw 'Inventoried observer loader asset missing or ambiguous.'}
            $assetStream=$asset.Open()
            try{$assetHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($assetStream)).ToLowerInvariant()}finally{$assetStream.Dispose()}
            if($assetHash -cne $expectedAsset[0].sha256 -or $asset.Length -ne $expectedAsset[0].bytes){throw 'Packaged observer loader asset differs from selected source bytes.'}
            $reader=[IO.StreamReader]::new($asset.Open())
            try{$manifest=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
            if($manifest.api_layer.name -cne 'XR_APILAYER_MESMERPRISM_submission_observer' -or
               $manifest.api_layer.library_path -cne 'libXR_APILAYER_MESMERPRISM_submission_observer.so'){throw 'Packaged observer loader manifest mismatch.'}
        }
    }finally{$zip.Dispose()}
    $nativeDependencyPath=$null
    if($SessionCore){
        $nativeLibraries=Read-SpatialApkNativeLibraries -Apk $apk
        if(-not $nativeLibraries.ContainsKey('libc++_shared.so')){throw 'Selected session C++ runtime is missing from APK.'}
        $runtimeBytes=$nativeLibraries['libc++_shared.so']
        if($runtimeBytes.LongLength -ne $sessionCppRuntime.bytes -or
           [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($runtimeBytes)).ToLowerInvariant() -cne $sessionCppRuntime.sha256){throw 'Packaged C++ runtime differs from exact selected NDK input.'}
        $closure=@(Assert-SpatialNativeDependencyClosure -Libraries $nativeLibraries `
            -SystemLibraryNames @($systemLibraryInputs|ForEach-Object {[IO.Path]::GetFileName($_.path)}))
        $nativeDependencyPath=Join-Path $output 'native-dependencies.json'
        WriteJson $nativeDependencyPath ([ordered]@{schema='rusty.quest.spatial_worldspace_hotload.native_dependencies.v1';abi='arm64-v8a';runtime=$sessionCppRuntime;system_libraries=$systemLibraryInputs;libraries=$closure;device_evidence=$false})
    }
    $dependencyPath=Join-Path $output 'dependencies.json'
    Copy-Item -LiteralPath (Join-Path $cache 'app/build/probe/dependencies.json') -Destination $dependencyPath
    $dependencies=@(Get-Content -LiteralPath $dependencyPath -Raw|ConvertFrom-Json)
    foreach($dependency in $dependencies){if((FileRecord $dependency.path).sha256 -ne $dependency.sha256){throw 'Resolved dependency bytes drifted.'}}
    if(-not ($dependencies.coordinate -contains 'com.meta.spatial:meta-spatial-sdk:0.14.0')){throw 'Latest SDK base artifact missing from actual resolution.'}
    $inspectionPath=Join-Path $output 'apk-inspection.txt'
    $inspection=@(& (Join-Path $toolsRoot 'aapt2.exe') dump badging $apk 2>&1)
    $packagePattern=[regex]::Escape($packageName)
    $activityPattern=[regex]::Escape($activityName)
    if($LASTEXITCODE -ne 0 -or ($inspection -join "`n") -notmatch "package: name='$packagePattern'" -or
       ($inspection -join "`n") -notmatch "launchable-activity: name='$activityPattern'"){throw 'APK identity inspection mismatch.'}
    $observedPermissions=@([regex]::Matches(($inspection -join "`n"),"(?m)^uses-permission: name='([^']+)'") | ForEach-Object {$_.Groups[1].Value})
    Assert-SpatialWorkloadPermissions -ContractPackageName $contract.package_name -SelectedPackageName $packageName `
        -PermissionAllowlist $contract.permission_allowlist -ObservedPermissions $observedPermissions
    $signature=@(& (Join-Path $toolsRoot 'apksigner.bat') verify --verbose --print-certs $apk 2>&1)
    if($LASTEXITCODE -ne 0){throw 'APK signer verification failed.'}
    $certificate=@(& (Join-Path $JavaHome 'bin/keytool.exe') -exportcert -rfc -alias androiddebugkey -keystore $DebugKeystore -storepass android 2>&1)
    if($LASTEXITCODE -ne 0){throw 'Cannot inspect explicit debug signing certificate.'}
    $pem=[regex]::Match(($certificate -join "`n"),'(?s)-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----')
    if(-not $pem.Success){throw 'Signing certificate PEM is missing.'}
    $der=[Convert]::FromBase64String(($pem.Groups[1].Value -replace '\s',''))
    $expectedSigner=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($der)).ToLowerInvariant()
    if($expectedSigner -cne $contract.expected_signer_sha256){throw 'Explicit signer differs from workload signer contract.'}
    $observedSigner=[regex]::Match(($signature -join "`n"),'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})')
    if(-not $observedSigner.Success -or $observedSigner.Groups[1].Value.ToLowerInvariant() -ne $expectedSigner){throw 'APK signer differs from explicit debug keystore.'}
    [IO.File]::WriteAllText($inspectionPath,(($inspection+$signature)-join "`n"),[Text.UTF8Encoding]::new($false))
    $profilePath=Join-Path $output 'runtime-profile.json'
    $propertiesPath=Join-Path $output 'property-manifest.json'
    WriteJson $profilePath ([ordered]@{schema='rusty.quest.spatial_worldspace_hotload.runtime_profile.v1';properties=@();activation='explicit-isolated-package';formal_lifecycle_adoption=$false})
    WriteJson $propertiesPath ([ordered]@{schema='rusty.quest.spatial_worldspace_hotload.property_manifest.v1';scope='complete-manifest';properties=@();source_inventory_sha256=$inventoryHash})
    $manifestPath=Join-Path $output 'build-manifest.json'
    WriteJson $manifestPath ([ordered]@{schema='rusty.quest.spatial_worldspace_hotload.build_receipt.v1';status='compiled';mode=$Mode;candidate=($Mode -eq 'Candidate');fingerprint=$fingerprint;dependencies=FileRecord $dependencyPath;native_dependencies=if($SessionCore){FileRecord $nativeDependencyPath}else{$null};source_inventory=FileRecord (Join-Path $output 'source-inventory.json');toolchain=$toolInputs;apk=FileRecord $apk;inspection=FileRecord $inspectionPath;device_evidence=$false;formal_lifecycle_adoption=$false})
    $capsulePath=Join-Path $output 'run-capsule.json'
    WriteJson $capsulePath ([ordered]@{
        schema='rusty.quest.apk_run_capsule.v1';capsule_id='spatial-worldspace-hotload-'+$fingerprint
        app_id=$contract.app_id;app_lane='spatial-worldspace-hotload-android'
        source=[ordered]@{repository=$repoRoot;commit=$head;tree=$tree;tracked_worktree_clean=$clean;worktree_overlay_sha256=if($clean){''}else{$inventoryHash}}
        build_lock=FileRecord (Join-Path $output 'build-input-lock.json');build_manifest=FileRecord $manifestPath;apk=FileRecord $apk
        runtime_profile=FileRecord $profilePath;property_manifest=([ordered]@{path=$propertiesPath;sha256=(FileRecord $propertiesPath).sha256;scope='complete-manifest'})
        android=[ordered]@{package_name=$packageName;activity=$activityName}
        cleanup=[ordered]@{policy='always-force-stop-and-restore-exact-property-snapshot';serial_exclusive_mutex=$true;restore_on_failure=$true}
        device_validation_recorded=$false;formal_lifecycle_adoption=$false
    })
    & (Join-Path $repoRoot 'tools/Test-ApkRunCapsule.ps1') -Path $capsulePath
    if(-not $?){throw 'Generated capsule validation failed.'}
    Write-Output $capsulePath
} finally {
    foreach($name in $previousEnvironment.Keys){[Environment]::SetEnvironmentVariable($name,$previousEnvironment[$name],'Process')}
}
