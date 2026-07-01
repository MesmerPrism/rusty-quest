param(
    [switch]$Build,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$appRoot = Join-Path $repoRoot "apps\qcl041-wifi-direct-harness-android"
$manifestPath = Join-Path $appRoot "AndroidManifest.xml"
$activityPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustyquest\qcl041\Qcl041WifiDirectHarnessActivity.java"
$servicePath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustyquest\qcl041\Qcl041WifiDirectHarnessService.java"
$lifecyclePath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustyquest\qcl041\Qcl041WifiDirectLifecycle.java"
$artifactPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustyquest\qcl041\Qcl041LifecycleArtifact.java"
$configPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustyquest\qcl041\Qcl041ProbeConfig.java"
$lslBridgePath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustyquest\qcl041\Qcl081LslNativeBridge.java"
$nativeBridgePath = Join-Path $appRoot "src\main\cpp\qcl081_lsl_outlet_bridge.cpp"
$nativeHeaderPath = Join-Path $appRoot "src\main\cpp\liblsl_qcl081_min.h"
$buildPath = Join-Path $PSScriptRoot "Build-Qcl041WifiDirectHarnessAndroid.ps1"
$invokePath = Join-Path $PSScriptRoot "Invoke-Qcl041WifiDirectLifecycle.ps1"
$readmePath = Join-Path $appRoot "README.md"

foreach ($path in @($manifestPath, $activityPath, $servicePath, $lifecyclePath, $artifactPath, $configPath, $lslBridgePath, $nativeBridgePath, $nativeHeaderPath, $buildPath, $invokePath, $readmePath)) {
    if (-not (Test-Path $path)) {
        throw "Missing QCL-041 Wi-Fi Direct harness file: $path"
    }
}

$manifest = Get-Content -Raw -Path $manifestPath
$activity = Get-Content -Raw -Path $activityPath
$service = Get-Content -Raw -Path $servicePath
$lifecycle = Get-Content -Raw -Path $lifecyclePath
$artifact = Get-Content -Raw -Path $artifactPath
$config = Get-Content -Raw -Path $configPath
$lslBridge = Get-Content -Raw -Path $lslBridgePath
$nativeBridge = Get-Content -Raw -Path $nativeBridgePath
$nativeHeader = Get-Content -Raw -Path $nativeHeaderPath
$buildText = Get-Content -Raw -Path $buildPath
$invoke = Get-Content -Raw -Path $invokePath
$readme = Get-Content -Raw -Path $readmePath
$combined = "$manifest`n$activity`n$service`n$lifecycle`n$artifact`n$config`n$lslBridge`n$nativeBridge`n$nativeHeader`n$buildText`n$invoke`n$readme"

function Assert-ContainsTokens {
    param(
        [string]$Text,
        [string[]]$Tokens,
        [string]$Label
    )
    foreach ($token in $Tokens) {
        if ($Text -notmatch $token) {
            throw "QCL-041 Wi-Fi Direct harness static check failed for ${Label}: missing token: $token"
        }
    }
}

function Assert-TextOrder {
    param(
        [string]$Text,
        [string]$First,
        [string]$Second,
        [string]$Label
    )
    $firstIndex = $Text.IndexOf($First, [StringComparison]::Ordinal)
    $secondIndex = $Text.IndexOf($Second, [StringComparison]::Ordinal)
    if ($firstIndex -lt 0 -or $secondIndex -lt 0 -or $firstIndex -ge $secondIndex) {
        throw "QCL-041 Wi-Fi Direct harness static check failed for ${Label}: expected '$First' before '$Second'."
    }
}

Assert-ContainsTokens $manifest @(
    'package="io\.github\.mesmerprism\.rustyquest\.qcl041"',
    'android\.hardware\.wifi\.direct',
    'android\.permission\.ACCESS_WIFI_STATE',
    'android\.permission\.CHANGE_WIFI_STATE',
    'android\.permission\.ACCESS_NETWORK_STATE',
    'android\.permission\.INTERNET',
    'android\.permission\.POST_NOTIFICATIONS',
    'android\.permission\.FOREGROUND_SERVICE',
    'android\.permission\.FOREGROUND_SERVICE_DATA_SYNC',
    'com\.oculus\.permission\.HAND_TRACKING',
    'oculus\.software\.handtracking',
    'android\.permission\.NEARBY_WIFI_DEVICES',
    'android:usesPermissionFlags="neverForLocation"',
    'android\.permission\.ACCESS_FINE_LOCATION',
    'android:maxSdkVersion="32"',
    'com\.oculus\.supportedDevices',
    'com\.oculus\.vr\.focusaware',
    'com\.oculus\.handtracking\.version',
    'com\.oculus\.intent\.category\.VR',
    'Qcl041WifiDirectHarnessActivity',
    'Qcl041WifiDirectHarnessService',
    'android:foregroundServiceType="dataSync"',
    'android:stopWithTask="false"',
    'android:debuggable="true"'
) "Android manifest"

Assert-ContainsTokens $activity @(
    'requestPermissions',
    'Manifest\.permission\.NEARBY_WIFI_DEVICES',
    'Manifest\.permission\.ACCESS_FINE_LOCATION',
    'Build\.VERSION_CODES\.TIRAMISU',
    'onRequestPermissionsResult',
    'Qcl041WifiDirectLifecycle'
) "runtime permission Activity"

Assert-ContainsTokens $service @(
    'extends Service',
    'startForeground',
    'NotificationChannel',
    'START_STICKY',
    'Manifest\.permission\.NEARBY_WIFI_DEVICES',
    'Manifest\.permission\.ACCESS_FINE_LOCATION',
    'Qcl041WifiDirectLifecycle',
    'QCL-041 lifecycle complete',
    'foreground service cannot request UI'
) "foreground service harness"

Assert-ContainsTokens $invoke @(
    'Invoke-AndroidPermissionPreflight',
    'dumpsys",\s*"package"',
    'grant_state_found',
    'pm",\s*"grant"',
    'uiautomator',
    'transport_bind_local_address',
    'transport_owner',
    'wifi_direct_local_address',
    'qcl041\.qcl082_relay_enabled',
    'qcl041\.qcl082_relay_start_delay_ms',
    'android\.permission\.POST_NOTIFICATIONS',
    'qcl041-wifi-direct-permission-pregrant\.json',
    'qcl041-wifi-direct-permission-uiautomator\.json',
    'qcl041-harness-launch\.json',
    'rusty\.quest\.qcl041\.harness_launch\.v1',
    'Qcl041WifiDirectHarnessService',
    'qcl041LaunchSurface',
    'foreground_service',
    'start-foreground-service',
    'qcl082-broker-permission-preflight\.json',
    'qcl082-broker-prestart\.json',
    'Start-Qcl082ProductMediaSourceBeforeHarness',
    'qcl041_wifi_direct_relay_prestarted_source',
    'QCL-082 pre-harness start_source live Android command',
    'Qcl082SenderSourceKind',
    'camera2_mediacodec_surface',
    'camera_permission_required',
    'Qcl082SenderCameraIds',
    'qcl082-broker-camera-permission-uiautomator\.json',
    'Invoke-BrokerCameraPermissionUiFallbackIfNeeded',
    'Get-Qcl082RelaySourcePort',
    'RunQcl082LivePreview',
    '--preview-ffplay',
    '--preview-window-title',
    'QCL-082 WPF product receiver with ffplay preview',
    'Qcl082Ffplay',
    'Prestarted QCL-082 WPF receiver already exited',
    'rusty-manifold-broker\.apk',
    'Build-ManifoldBrokerAndroid\.ps1',
    'QCL-082 broker APK install',
    'Start-Qcl082BrokerBeforeHarness',
    'Wait-AndroidPackagePid',
    'QuestLeaseWaitSeconds',
    '--wait',
    '--timeout',
    'BrokerStartService',
    'start-foreground-service',
    'foreground_service',
    'avoid_horizon_reprojected_os_dialog_before_qcl041_launch',
    'broker_foreground_service_start_failed',
    'service_start_exit_code',
    'activity_start_exit_code',
    'brokerPid',
    'packagePid',
    '--no-launch-broker',
    'PrestartedBroker',
    'broker_prestart_artifact',
    'force_stop_exit_code',
    'am force-stop io\.github\.mesmerprism\.rustyquest\.qcl041',
    'am_start_exit_code',
    'launch_evidence_path',
    'broker_pid_missing_after_prestart'
) "live permission pregrant and UIAutomator fallback"

Assert-ContainsTokens $lifecycle @(
    'PackageManager\.FEATURE_WIFI_DIRECT',
    'isP2pSupported',
    'WifiP2pManager',
    'initialize',
    'WIFI_P2P_STATE_CHANGED_ACTION',
    'WIFI_P2P_PEERS_CHANGED_ACTION',
    'WIFI_P2P_CONNECTION_CHANGED_ACTION',
    'WIFI_P2P_THIS_DEVICE_CHANGED_ACTION',
    'discoverPeers',
    'requestPeers',
    'connect',
    'groupOwnerIntent',
    'requestConnectionInfo',
    'requestGroupInfo',
    'groupOwnerAddress',
    'isGroupOwner',
    'Quest became group owner',
    'ConnectivityManager',
    'getAllNetworks',
    'NetworkCapabilities',
    'NetworkInterface',
    'bindSocket',
    'socket_bound_to_wifi_direct_local_address',
    'socket_bound_to_wifi_direct_network',
    'bindProcessToNetwork',
    'Qcl081LslNativeBridge',
    'qcl082_relay',
    'copyMediaBytes',
    'if \(total > 0L\)',
    'createQcl082ReceiverSocket',
    'receiver_socket_created_from_wifi_direct_network", true',
    'receiver_socket_bound_to_wifi_direct_network", true',
    'ReceiverSocketCandidate',
    'receiver_socket_network_factory_fallback',
    'local_address_bind',
    'receiver_socket_local_address_bind_skipped',
    'source_connected_before_receiver',
    'findWifiDirectNetworkOnce',
    'wifi_direct_network_wait_attempts',
    'wifi_direct_network_wait_elapsed_ms',
    'receiver_connected_local_address',
    'receiver connected from non Wi-Fi Direct local address',
    'qcl041_wifi_direct_harness',
    'qcl081_lsl',
    'Socket',
    'bounded TCP',
    'stopPeerDiscovery',
    'removeGroup'
) "WifiP2pManager lifecycle"

Assert-TextOrder `
    -Text $lifecycle `
    -First "source = connectSourceWithRetry(deadline);" `
    -Second "receiver = connectReceiverWithRetry(" `
    -Label "QCL-082 relay source-first sequencing"

Assert-ContainsTokens $artifact @(
    '"QCL-041"',
    '"windows"',
    '"live_wifi_direct_lifecycle"',
    '"wifi_direct"',
    '"peer_to_peer_group"',
    '"windows_wifi_direct_api"',
    '"permission_state"',
    '"peer_discovery"',
    '"group_formation"',
    '"socket_exchange"',
    '"bounded_tcp_probe"',
    '"cleanup"',
    '"Agent Board"',
    'released_after_live_steps',
    'diagnostics'
) "lifecycle source artifact"

Assert-ContainsTokens $config @(
    'rusty\.quest\.connectivity_wifi_direct_lifecycle\.v1',
    'rusty-quest-wifi-direct-qcl041-android-harness',
    'quest_windows_wifi_direct_lifecycle',
    'qcl041\.lease_id',
    'qcl041\.lease_resource',
    'qcl041\.windows_api_observed',
    'qcl041\.windows_peer_name_contains',
    'qcl041\.group_owner_intent',
    'qcl041\.allow_quest_group_owner',
    'qcl041\.listen_port',
    'qcl041\.qcl081_lsl_enabled',
    'qcl041\.qcl081_lsl_stream_name',
    'qcl041\.qcl081_lsl_source_id',
    'qcl041\.qcl082_relay_enabled',
    'qcl041\.qcl082_relay_source_host',
    'qcl041\.qcl082_relay_receiver_host',
    'qcl041\.qcl082_relay_start_delay_ms',
    'product_harness',
    'RMANVID1'
) "intent config"

Assert-ContainsTokens $lslBridge @(
    'System\.loadLibrary\("lsl"\)',
    'System\.loadLibrary\("qcl081_lsl_outlet_bridge"\)',
    'nativePublishSamples',
    'source_timestamps_monotonic'
) "QCL-081 Java native bridge"

Assert-ContainsTokens $nativeBridge @(
    'lsl_create_streaminfo',
    'lsl_create_outlet',
    'lsl_local_clock',
    'lsl_push_sample_ftp',
    'Quest-owned liblsl outlet'
) "QCL-081 native liblsl outlet bridge"

Assert-ContainsTokens $nativeHeader @(
    'lsl_streaminfo',
    'lsl_outlet',
    'cft_float32',
    'lsl_push_sample_ftp'
) "QCL-081 liblsl C API header"

Assert-ContainsTokens $buildText @(
    'qcl041-wifi-direct-harness-android',
    'javac',
    'd8\.bat',
    'aapt2',
    'aarch64-linux-android29-clang\+\+\.cmd',
    'libqcl081_lsl_outlet_bridge\.so',
    'qcl081_lsl_native_packaged',
    '--target-sdk-version", "34"',
    'rusty\.quest\.qcl041_wifi_direct_harness_android\.build_manifest\.v1',
    'live_evidence_synthesized = \$false'
) "manual Android build"

Assert-ContainsTokens $invoke @(
    'agent-board\.ps1',
    'reserve',
    'quest:\$Serial',
    'release',
    'adb',
    '-s',
    'dotnet',
    'qcl041_wifi_direct_peer_helper',
    'Windows Mobile Hotspot',
    'RunQcl081Lsl',
    'Qcl081LslBackend',
    'Qcl081ReceiverBackend',
    'qcl081_wifi_direct_lsl_receiver\.py',
    'receiver_backend',
    'qcl041\.qcl081_lsl_enabled',
    'qcl041\.qcl081_lsl_backend',
    'released_after_live_steps',
    'connectivity-probe run --mode fixture --probe-id QCL-041'
) "live wrapper"

Assert-ContainsTokens $readme @(
    'no Android phone',
    'Quest headset',
    'Windows QCL-041 helper',
    'Hostess normalization',
    'official `liblsl`',
    'private planning notes'
) "app README"

$legacyTokens = @(
    ("RUSTY" + "_XR_"),
    ("rusty" + ".xr."),
    ("/rusty" + "xr/v1"),
    ("com.example." + "rustyxr"),
    ("Rusty" + "XR")
)
foreach ($token in $legacyTokens) {
    if ($combined.Contains($token)) {
        throw "QCL-041 Wi-Fi Direct harness contains legacy token: $token"
    }
}

if ($Build) {
    & (Join-Path $PSScriptRoot "Build-Qcl041WifiDirectHarnessAndroid.ps1") -AndroidHome $AndroidHome -JavaHome $JavaHome | Out-Host
}

Write-Output "Rusty Quest QCL-041 Wi-Fi Direct Android harness validation passed"
