param([string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $RepoRoot 'tools/lib/SpatialVulkanProvenanceSelection.psm1') -Force
$off=Resolve-SpatialVulkanProvenanceSelection -BuildType Debug -EnvironmentDepthOwner disabled
if($off.enabled-or$off.required_native_library){throw 'Default selects provenance payload.'}
$on=Resolve-SpatialVulkanProvenanceSelection -Requested -BuildType Debug -EnvironmentDepthOwner spatial-sdk-api-layer
if(-not$on.enabled-or$on.layer_name-cne'VK_LAYER_RUSTY_QUEST_provenance'-or$on.required_native_library-cne'libVkLayer_rusty_quest_provenance.so'){throw 'Selected closure differs.'}
foreach($selection in @(
    @{Requested=$true;BuildType='Release';EnvironmentDepthOwner='spatial-sdk-api-layer'},
    @{Requested=$true;BuildType='Debug';EnvironmentDepthOwner='spatial-sdk-api-layer';PublicationBuild=$true},
    @{Requested=$true;BuildType='Debug';EnvironmentDepthOwner='disabled'}
)){
    $rejected=$false
    try{Resolve-SpatialVulkanProvenanceSelection @selection|Out-Null}catch{$rejected=$true}
    if(-not$rejected){throw 'Unsupported selection was admitted.'}
}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('rq-vulkan-selection-'+[guid]::NewGuid().ToString('N')+'.apk')
$fakeReadelf=$temp+'.readelf.ps1'
try{
    $zip=[IO.Compression.ZipFile]::Open($temp,[IO.Compression.ZipArchiveMode]::Create)
    try{$entry=$zip.CreateEntry('lib/arm64-v8a/libVkLayer_rusty_quest_provenance.so');$stream=$entry.Open();try{$stream.Write([byte[]](1,2,3))}finally{$stream.Dispose()}}finally{$zip.Dispose()}
    $observed=Assert-SpatialVulkanProvenanceArtifact -ApkPath $temp -Selection $on
    if(-not$observed.present-or$observed.bytes-ne3){throw 'Artifact closure observation differs.'}
    $reader=@'
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Tail)
if($Tail-contains'-lW'){' LOAD 0x000000 0x000000 0x000000 0x000000 0x000000 R E 0x4000';exit 0}
if($Tail-contains'--relocs'){exit 0}
if($Tail-contains'--dynamic'){' 0x000000006ffffffb (FLAGS_1) Flags: NODELETE';exit 0}
foreach($symbol in @('vkNegotiateLoaderLayerInterfaceVersion','vkGetInstanceProcAddr','vkGetDeviceProcAddr','vkEnumerateInstanceLayerProperties','vkEnumerateInstanceExtensionProperties','vkEnumerateDeviceLayerProperties','vkEnumerateDeviceExtensionProperties')){" 1: 0000 1 FUNC GLOBAL DEFAULT 1 $symbol"}
exit 0
'@
    [IO.File]::WriteAllText($fakeReadelf,$reader)
    $elf=Assert-SpatialVulkanProvenanceArtifact -ApkPath $temp -Selection $on -ReadelfPath $fakeReadelf
    if($elf.elf.defined_loader_exports.Count-ne7-or$elf.elf.load_alignment_minimum-ne16384){throw 'ELF observer did not bind the required exports/alignment.'}
    foreach($dynamicLine in @(
        '',
        ' 0x000000000000001e (FLAGS) Flags: NODELETE',
        ' 0x000000000000001e (FLAGS_1) Flags: NODELETE',
        ' 0x000000006ffffffb (FLAGS) Flags: NODELETE',
        ' 0x000000006ffffffb (FLAGS_1) Flags: NOW',
        ' 0x000000006ffffffb (FLAGS_1) Flags: NOT_NODELETE',
        ' 0x000000006ffffffb (FLAGS_1) Flags: NODELETE_EXTRA'
    )){
        [IO.File]::WriteAllText($fakeReadelf,$reader.Replace(' 0x000000006ffffffb (FLAGS_1) Flags: NODELETE',$dynamicLine))
        $rejected=$false
        try{Assert-SpatialVulkanProvenanceArtifact -ApkPath $temp -Selection $on -ReadelfPath $fakeReadelf|Out-Null}
        catch{if($_.Exception.Message-notlike'*FLAGS_1 NODELETE*'){throw};$rejected=$true}
        if(-not$rejected){throw "Missing or wrong-tag NODELETE flag was accepted: $dynamicLine"}
    }
    foreach($dynamicLine in @(
        ' 0x000000006ffffffb (FLAGS_1) NODELETE',
        ' 0x000000006ffffffb (FLAGS_1) Flags: NOW NODELETE',
        ' 0x000000006ffffffb (FLAGS_1) Flags: NODELETE NOW'
    )){
        [IO.File]::WriteAllText($fakeReadelf,$reader.Replace(' 0x000000006ffffffb (FLAGS_1) Flags: NODELETE',$dynamicLine))
        $observedFlags=Assert-SpatialVulkanProvenanceArtifact -ApkPath $temp -Selection $on -ReadelfPath $fakeReadelf
        if(-not$observedFlags.elf.flags_1_nodelete-or$observedFlags.elf.diagnostic_module_residence-cne'process-lifetime'-or
                $observedFlags.elf.residency_retains_gpu_objects-or$observedFlags.elf.sdk_dispatch_coverage_proven){
            throw 'NODELETE observation does not declare its diagnostic-module-only scope.'
        }
    }
    [IO.File]::WriteAllText($fakeReadelf,$reader.Replace("if(`$Tail-contains'--dynamic'){","if(`$Tail-contains'--dynamic'){exit 1;"))
    $rejected=$false
    try{Assert-SpatialVulkanProvenanceArtifact -ApkPath $temp -Selection $on -ReadelfPath $fakeReadelf|Out-Null}
    catch{if($_.Exception.Message-notlike'*dynamic-tag inspection failed*'){throw};$rejected=$true}
    if(-not$rejected){throw 'Failed dynamic-tag inspection was accepted.'}
    [IO.File]::WriteAllText($fakeReadelf,$reader.Replace('R E 0x4000','R E 0x1000'))
    $rejected=$false
    try{Assert-SpatialVulkanProvenanceArtifact -ApkPath $temp -Selection $on -ReadelfPath $fakeReadelf|Out-Null}
    catch{if($_.Exception.Message-notlike'*16 KiB alignment*'){throw};$rejected=$true}
    if(-not$rejected){throw 'NODELETE bypassed the existing native LOAD alignment gate.'}
    [IO.File]::WriteAllText($fakeReadelf,$reader.Replace("if(`$Tail-contains'--relocs'){exit 0}","if(`$Tail-contains'--relocs'){'R_AARCH64_JUMP_SLOT vkGetDeviceProcAddr + 0';exit 0}"))
    $rejected=$false;try{Assert-SpatialVulkanProvenanceArtifact -ApkPath $temp -Selection $on -ReadelfPath $fakeReadelf|Out-Null}catch{$rejected=$true}
    if(-not$rejected){throw 'Preemptible Vulkan entry relocation was accepted.'}
    [IO.File]::WriteAllText($fakeReadelf,$reader.Replace('GLOBAL DEFAULT 1','GLOBAL DEFAULT UND'))
    $rejected=$false;try{Assert-SpatialVulkanProvenanceArtifact -ApkPath $temp -Selection $on -ReadelfPath $fakeReadelf|Out-Null}catch{$rejected=$true}
    if(-not$rejected){throw 'Undefined imports were accepted as defined layer exports.'}
    $rejected=$false;try{Assert-SpatialVulkanProvenanceArtifact -ApkPath $temp -Selection $off|Out-Null}catch{$rejected=$true}
    if(-not$rejected){throw 'Unselected native diagnostic payload was accepted.'}
}finally{
    if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp}
    if(Test-Path -LiteralPath $fakeReadelf){Remove-Item -LiteralPath $fakeReadelf}
}
Write-Output 'Vulkan provenance selection and synthetic ZIP closure controls passed, including FLAGS_1 NODELETE positives and missing/wrong-tag/other-flag negatives; no APK/native build or device proof.'
