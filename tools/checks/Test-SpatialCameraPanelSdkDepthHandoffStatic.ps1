param([string]$RepoRoot)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path

function Read-RequiredText {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $path = Join-Path $repoRootPath $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing Spatial SDK depth-handoff file: $path"
    }
    Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )

    if (-not $Text.Contains($Needle)) {
        throw "$Label is missing required token: $Needle"
    }
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )

    if ($Text.Contains($Needle)) {
        throw "$Label contains forbidden overlap token: $Needle"
    }
}

function Normalize-ExactExtensionSet {
    param([Parameter(Mandatory = $true)][string[]]$Names)

    $normalized = [System.Collections.Generic.List[string]]::new()
    foreach ($name in $Names) {
        if ([string]::IsNullOrEmpty($name)) {
            throw "Malformed extension name"
        }
        $seen = $false
        foreach ($existing in $normalized) {
            if ([string]::Equals($existing, $name, [System.StringComparison]::Ordinal)) {
                $seen = $true
                break
            }
        }
        if (-not $seen) {
            $normalized.Add($name)
        }
    }
    return @($normalized)
}

function Assert-Sequence {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string[]]$Actual,
        [Parameter(Mandatory = $true)][string[]]$Expected
    )

    if ($Actual.Count -ne $Expected.Count) {
        throw "$Label count mismatch: actual=$($Actual.Count) expected=$($Expected.Count)"
    }
    for ($index = 0; $index -lt $Expected.Count; ++$index) {
        if (-not [string]::Equals($Actual[$index], $Expected[$index], [System.StringComparison]::Ordinal)) {
            throw "$Label mismatch at ${index}: actual=$($Actual[$index]) expected=$($Expected[$index])"
        }
    }
}

$gradle = Read-RequiredText "apps\spatial-camera-panel-android\app\build.gradle.kts"
$nativeBuild = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\build.rs"
$nativeRoot = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\lib.rs"
$renderer = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_probe.rs"
$handoff = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_sdk_depth_handoff.rs"
$settings = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_video_projection_settings.rs"
$coordinator = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraHwbProjectionDepthPrerequisiteCoordinator.kt"
$reasons = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialPassthroughReasonAggregator.kt"
$reasonTests = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialPassthroughReasonAggregatorTest.kt"
$layer = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\cpp\spatial_depth_layer\spatial_depth_api_layer.cpp"
$abi = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\cpp\spatial_depth_layer\spatial_depth_handoff_abi.h"
$ring = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\cpp\spatial_depth_layer\spatial_depth_ring_vulkan.cpp"
$layerManifest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\spatial-sdk-api-layer-assets\openxr\1\api_layers\implicit.d\XR_APILAYER_MESMERPRISM_spatial_sdk_depth_handoff.json"

foreach ($owner in @("disabled", "legacy-native-sidecar", "spatial-sdk-api-layer")) {
    Assert-Contains "Gradle closed owner enum" $gradle ('"' + $owner + '"')
    Assert-Contains "Rust closed owner enum" $nativeBuild ('"' + $owner + '"')
}
Assert-Contains "SDK-only layer packaging" $gradle 'assets.srcDir(file("src/main/spatial-sdk-api-layer-assets"))'
Assert-Contains "SDK-only layer build" $gradle 'XR_APILAYER_MESMERPRISM_spatial_sdk_depth_handoff'
Assert-Contains "Layer manifest" $layerManifest '"disable_environment"'
Assert-Contains "Layer manifest" $layerManifest 'libXR_APILAYER_MESMERPRISM_spatial_sdk_depth_handoff.so'

Assert-Contains "Legacy cfg" $nativeBuild 'rq_environment_depth_legacy_native_sidecar'
Assert-Contains "SDK cfg" $nativeBuild 'rq_environment_depth_spatial_sdk_api_layer'
Assert-Contains "Legacy module exclusion" $nativeRoot 'rq_environment_depth_legacy_native_sidecar'
Assert-Contains "Legacy module exclusion" $nativeRoot 'mod spatial_environment_depth;'
Assert-Contains "SDK handoff module" $nativeRoot 'rq_environment_depth_spatial_sdk_api_layer'
Assert-Contains "SDK handoff module" $nativeRoot 'mod spatial_sdk_depth_handoff;'

Assert-Contains "Depth-demand coordinator" $coordinator 'bindings.updateSdkEnvironmentDepthDemand(required, source)'
Assert-Contains "Depth-demand coordinator" $coordinator 'SpatialEnvironmentDepthOwner.SpatialSdkApiLayer'
Assert-Contains "Passthrough reason aggregator" $reasons 'EnvironmentDepthMode.TEXTURE_ONLY'
Assert-Contains "Passthrough reason aggregator" $reasons 'EnvironmentDepthMode.OFF'
Assert-Contains "Passthrough reason aggregator" $reasons 'Keeps visible Background selection independent from the internal depth prerequisite.'
Assert-Contains "Passthrough reason tests" $reasonTests 'blackRetainsOpaqueSelectionWhileDepthKeepsPassthroughInternally'
Assert-Contains "Passthrough reason tests" $reasonTests 'visiblePassthroughSurvivesDepthDisableAndAllReasonsClearInOrder'

Assert-Contains "Extension augmentation" $layer 'constexpr char kDepthExtension[] = "XR_META_environment_depth"'
Assert-Contains "Exact-time view metadata" $layer 'downstreamLocateViews('
Assert-Contains "Matching frame window" $layer 'reason=outside-matching-begin-end'
Assert-Contains "SDK-owned application acquire" $layer 'ownership=application-call'
Assert-Contains "No layer-owned provider" $layer 'layerOwnedAcquireEnabled=false'
Assert-Contains "Depth copy plane" $layer 'dataPlane=device-local-d16'
Assert-Contains "No CPU depth plane" $layer 'cpuDepthReadback=false'
Assert-Contains "No per-frame host wait" $layer 'perFrameHostFenceWait=false'
Assert-Contains "Three-slot ring" $ring 'kRingSize'
Assert-Contains "Pinned lease blocks ring rebuild" $ring 'pinned-lease-await-release'
Assert-Contains "Depth swapchain source invalidation retains copied lease" $layer 'gDepthGpuHandoff.invalidateSourceSwapchain()'
Assert-Contains "Accepted submit transitions lease to release pending" $layer 'markLeaseReleasePending(queued.request.lease_id)'
Assert-Contains "Lease release resumes deferred ring configuration" $layer 'configureApplicationDepthCopyLocked("consumer-lease-release")'
Assert-Contains "Lifecycle-only queue drain" $ring 'This is a lifecycle-only drain, never a'

Assert-Contains "Sampler-YCbCr duplicate gate" $layer 'duplicateChainRejected'
Assert-Contains "Sampler-YCbCr feature forwarding" $layer 'forwardedStorageLifetime=through-downstream-xrCreateVulkanDeviceKHR-call'
Assert-Contains "External semaphore gate" $layer 'VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME'
Assert-Contains "Extension set normalization" $layer 'upstreamDuplicateObserved'
Assert-Contains "Extension set normalization" $layer 'duplicateElidedCount'
Assert-Contains "Extension set normalization" $layer 'finalExtensionSetUnique'
Assert-Contains "Extension set normalization" $layer 'std::strcmp(forwardedName, extensionName) == 0'
Assert-Contains "Queue broker submit" $layer 'vkQueueSubmit(gState.sdkQueue'
Assert-Contains "Queue broker present" $layer 'vkQueuePresentKHR(gState.sdkQueue'
Assert-Contains "Terminal request cleanup" $layer 'gSpatialSubmitRequests.erase(iterator)'

Assert-Contains "ABI v2 export" $abi 'rq_spatial_depth_get_api_v2'
Assert-Contains "ABI v2 device token" $abi 'device_token'
Assert-Contains "ABI v2 session generation" $abi 'session_generation'
Assert-Contains "ABI v2 surface generation" $abi 'surface_generation'
Assert-Contains "ABI v2 media generation" $abi 'media_source_generation'
Assert-Contains "Rust ABI symbol" $handoff 'rq_spatial_depth_get_api_v2'
Assert-Contains "Rust fail-closed owner markers" $handoff 'legacySidecar=false layerOwnedProvider=false cpuDepthFallback=false'
Assert-Contains "Media generation remains independent" $settings 'spatial_video_media_source_generation'

Assert-Contains "Renderer exact SDK binding" $renderer 'spatial_depth_device_binding()'
Assert-Contains "Renderer brokered submit/present" $renderer 'enqueue_spatial_submit_present('
Assert-Contains "Renderer pinned release" $renderer 'release_spatial_depth_render_lease('
Assert-Contains "Renderer typed shutdown" $renderer 'request_spatial_depth_shutdown('
Assert-Contains "Steady broker success markers are rate limited" $renderer 'markerPolicy=first4-periodic300-failure-immediate'
Assert-Contains "Broker terminal aggregate" $renderer 'terminalConsumedTotal='
Assert-Contains "Fence retirement aggregate" $renderer 'submitRetiredTotal='
Assert-NotContains "Production layer" $layer 'OpenXrApiLayerDepthProbeActivity'
Assert-NotContains "Production handoff" ($handoff + $renderer) 'cpuDepthFallback=true'

Assert-Sequence "adjacent duplicate normalization" `
    (Normalize-ExactExtensionSet @("A", "A", "B")) @("A", "B")
Assert-Sequence "separated duplicate normalization" `
    (Normalize-ExactExtensionSet @("A", "B", "A", "C", "B")) @("A", "B", "C")
Assert-Sequence "upstream required extension retained" `
    (Normalize-ExactExtensionSet @("VK_KHR_swapchain", "VK_ANDROID_external_memory_android_hardware_buffer")) `
    @("VK_KHR_swapchain", "VK_ANDROID_external_memory_android_hardware_buffer")
Assert-Sequence "case-sensitive names remain distinct" `
    (Normalize-ExactExtensionSet @("A", "a", "A")) @("A", "a")

Write-Output "Spatial Camera Panel Spatial SDK depth-handoff static checks passed."
