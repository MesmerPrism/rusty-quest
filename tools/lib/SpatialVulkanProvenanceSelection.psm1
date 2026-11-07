Set-StrictMode -Version Latest

function Resolve-SpatialVulkanProvenanceSelection {
    param(
        [switch]$Requested,
        [ValidateSet('Debug','Release')][string]$BuildType='Debug',
        [switch]$PublicationBuild,
        [ValidateSet('disabled','legacy-native-sidecar','spatial-sdk-api-layer')][string]$EnvironmentDepthOwner='legacy-native-sidecar'
    )
    if($Requested-and($BuildType-cne'Debug'-or$PublicationBuild-or$EnvironmentDepthOwner-cne'spatial-sdk-api-layer')){
        throw 'Vulkan provenance requires a non-publication Debug candidate with the selected Spatial SDK API-layer CMake closure.'
    }
    [ordered]@{
        schema='rusty.quest.vulkan_provenance.selection.v1'
        enabled=[bool]$Requested
        layer_name=$(if($Requested){'VK_LAYER_RUSTY_QUEST_provenance'}else{''})
        required_native_library=$(if($Requested){'libVkLayer_rusty_quest_provenance.so'}else{''})
        runtime_property='debug.rustyquest.vulkan_provenance.enabled'
        runtime_exact_value='1'
        process_package_match='exact-compiled-application-id'
        capture_max_events=262144
        capture_max_bytes=33554432
        capture_max_ns=120000000000
        capture_queue_rows=1024
        runtime_activation_proven=$false
        sdk_dispatch_coverage_proven=$false
    }
}

function Assert-SpatialVulkanProvenanceArtifact {
    param([Parameter(Mandatory)][string]$ApkPath,[Parameter(Mandatory)]$Selection,[string]$ReadelfPath='',[string]$ExpectedApplicationId='')
    $zip=[IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ApkPath).Path)
    try{
        $layerEntries=@($zip.Entries|Where-Object FullName -Match '(^|/)libVkLayer_rusty_quest_provenance\.so$')
        if(-not$Selection.enabled){
            if($layerEntries.Count){throw 'Unselected or release APK contains the Vulkan provenance library.'}
            return [ordered]@{selected=$false;present=$false;entry='';bytes=0;sha256='';runtime_coverage_proven=$false}
        }
        if($layerEntries.Count-ne1-or$layerEntries[0].FullName-cne'lib/arm64-v8a/libVkLayer_rusty_quest_provenance.so'-or$layerEntries[0].Length-le0){
            throw 'Selected APK must contain exactly the arm64 Vulkan provenance library.'
        }
        $stream=$layerEntries[0].Open()
        try{$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
        $elfObservation=$null
        if($ReadelfPath){
            $scratch=Join-Path ([IO.Path]::GetTempPath()) ('rq-vulkan-elf-'+[guid]::NewGuid().ToString('N'))
            New-Item -ItemType Directory -Path $scratch -ErrorAction Stop|Out-Null
            $elf=Join-Path $scratch 'libVkLayer_rusty_quest_provenance.so'
            try{
                [IO.Compression.ZipFileExtensions]::ExtractToFile($layerEntries[0],$elf,$false)
                $symbolText=(& $ReadelfPath '--dyn-syms' '--wide' $elf 2>&1|Out-String)
                if($LASTEXITCODE-ne0){throw 'Provenance native symbol inspection failed.'}
                $required=@('vkNegotiateLoaderLayerInterfaceVersion','vkGetInstanceProcAddr','vkGetDeviceProcAddr','vkEnumerateInstanceLayerProperties','vkEnumerateInstanceExtensionProperties','vkEnumerateDeviceLayerProperties','vkEnumerateDeviceExtensionProperties')
                foreach($symbol in $required){if($symbolText-cnotmatch('(?m)\bGLOBAL\s+DEFAULT\s+(?!UND\b)\S+\s+'+[regex]::Escape($symbol)+'\s*$')){throw "Required defined Vulkan loader export is absent: $symbol"}}
                $relocations=(& $ReadelfPath '--relocs' '--wide' $elf 2>&1|Out-String)
                if($LASTEXITCODE-ne0){throw 'Provenance native relocation inspection failed.'}
                if($relocations-match'(?m)\bvk[A-Za-z_][A-Za-z0-9_]*\b'){throw 'Provenance native references contain preemptible Vulkan entry relocations.'}
                $dynamic=(& $ReadelfPath '--dynamic' '--wide' $elf 2>&1|Out-String)
                if($LASTEXITCODE-ne0){throw 'Provenance native dynamic-tag inspection failed.'}
                $flags1Rows=[regex]::Matches($dynamic,'(?m)^[ \t]*0x0*6[fF]{6}[bB][ \t]+\(FLAGS_1\)[ \t]+([^\r\n]*)\r?$')
                $nodelete=$flags1Rows.Count-eq1-and@($flags1Rows[0].Groups[1].Value-split'[ \t]+')-ccontains'NODELETE'
                if(-not$nodelete){throw 'Provenance native FLAGS_1 NODELETE is required for diagnostic-module process residence.'}
                $segments=(& $ReadelfPath '-lW' $elf 2>&1|Out-String)
                if($LASTEXITCODE-ne0){throw 'Provenance native ELF inspection failed.'}
                $alignments=@([regex]::Matches($segments,'(?m)^\s*LOAD\s+.*?(0x[0-9a-fA-F]+)\s*$')|ForEach-Object{[Convert]::ToInt64($_.Groups[1].Value.Substring(2),16)})
                if(-not$alignments.Count-or@($alignments|Where-Object{$_-lt16384}).Count){throw 'Provenance native LOAD segments do not satisfy 16 KiB alignment.'}
                if($ExpectedApplicationId-and-not[Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($elf)).Contains($ExpectedApplicationId)){throw 'Provenance native library does not bind the selected application ID.'}
                $elfObservation=[ordered]@{defined_loader_exports=$required;vulkan_symbol_relocations_absent=$true;flags_1_nodelete=$true;diagnostic_module_residence='process-lifetime';residency_retains_gpu_objects=$false;sdk_dispatch_coverage_proven=$false;load_alignment_minimum=($alignments|Measure-Object -Minimum).Minimum;compiled_application_id_present=[bool]$ExpectedApplicationId;runtime_order_proven=$false}
            }finally{
                if(Test-Path -LiteralPath $elf){Remove-Item -LiteralPath $elf}
                if(Test-Path -LiteralPath $scratch){Remove-Item -LiteralPath $scratch}
            }
        }
        [ordered]@{selected=$true;present=$true;entry=$layerEntries[0].FullName;bytes=$layerEntries[0].Length;sha256=$hash;elf=$elfObservation;runtime_coverage_proven=$false}
    }finally{$zip.Dispose()}
}

Export-ModuleMember -Function Resolve-SpatialVulkanProvenanceSelection,Assert-SpatialVulkanProvenanceArtifact
