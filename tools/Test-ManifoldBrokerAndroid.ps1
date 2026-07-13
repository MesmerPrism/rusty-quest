param(
    [switch]$Build,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$appRoot = Join-Path $repoRoot "apps\manifold-broker-android"
$manifestPath = Join-Path $repoRoot "fixtures\broker-products\legacy-camera-p2p-standalone.AndroidManifest.xml"
$activityPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\BrokerStartActivity.java"
$servicePath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\BrokerStartService.java"
$admissionServicePath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\ManifoldAdmissionService.java"
$admissionBridgePath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\ManifoldAdmissionNativeBridge.java"
$authorityBridgePath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\ManifoldRuntimeAuthorityBridge.java"
$launchEvidencePath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\BrokerLaunchEvidence.java"
$serverPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\LocalManifoldBrokerServer.java"
$remoteCameraRuntimePath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\RemoteCameraSessionRuntime.java"
$remoteCameraDirectP2pSocketAuthorityPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\RemoteCameraDirectP2pSocketAuthority.java"
$remoteCameraSourceRuntimePath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\RemoteCameraSourceRuntime.java"
$h264MediaStreamWriterPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\H264MediaStreamWriter.java"
$mediaCodecSurfaceEncoderPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\MediaCodecSurfaceEncoder.java"

foreach ($path in @($manifestPath, $activityPath, $servicePath, $admissionServicePath, $admissionBridgePath, $authorityBridgePath, $launchEvidencePath, $serverPath, $remoteCameraRuntimePath, $remoteCameraDirectP2pSocketAuthorityPath, $remoteCameraSourceRuntimePath, $h264MediaStreamWriterPath, $mediaCodecSurfaceEncoderPath)) {
    if (-not (Test-Path $path)) {
        throw "Missing Manifold broker Android file: $path"
    }
}

$manifest = Get-Content -Raw -Path $manifestPath
$activity = Get-Content -Raw -Path $activityPath
$service = Get-Content -Raw -Path $servicePath
$admissionService = Get-Content -Raw -Path $admissionServicePath
$admissionBridge = Get-Content -Raw -Path $admissionBridgePath
$authorityBridge = Get-Content -Raw -Path $authorityBridgePath
$launchEvidence = Get-Content -Raw -Path $launchEvidencePath
$server = Get-Content -Raw -Path $serverPath
$remoteCameraRuntime = Get-Content -Raw -Path $remoteCameraRuntimePath
$remoteCameraDirectP2pSocketAuthority = Get-Content -Raw -Path $remoteCameraDirectP2pSocketAuthorityPath
$remoteCameraSourceRuntime = Get-Content -Raw -Path $remoteCameraSourceRuntimePath
$h264MediaStreamWriter = Get-Content -Raw -Path $h264MediaStreamWriterPath
$mediaCodecSurfaceEncoder = Get-Content -Raw -Path $mediaCodecSurfaceEncoderPath

if ($manifest -notmatch 'package="io\.github\.mesmerprism\.rustymanifold\.broker"') {
    throw "Manifold broker Android manifest has the wrong package."
}
if ($manifest -notmatch 'android\.permission\.CAMERA') {
    throw "Manifold broker Android manifest must declare Android camera permission for camera-source mode."
}
if ($manifest -notmatch 'android\.permission\.INTERNET') {
    throw "Manifold broker Android manifest must declare INTERNET for broker control and media sockets."
}
if ($manifest -notmatch 'android\.permission\.ACCESS_NETWORK_STATE') {
    throw "Manifold broker Android manifest must declare ACCESS_NETWORK_STATE for Wi-Fi Direct network selection."
}
if ($manifest -notmatch 'android\.permission\.POST_NOTIFICATIONS') {
    throw "Manifold broker Android manifest must declare POST_NOTIFICATIONS for foreground broker service evidence."
}
if ($manifest -notmatch 'android\.permission\.FOREGROUND_SERVICE') {
    throw "Manifold broker Android manifest must declare FOREGROUND_SERVICE for the QCL-082 broker service."
}
if ($manifest -notmatch 'android\.permission\.FOREGROUND_SERVICE_DATA_SYNC') {
    throw "Manifold broker Android manifest must declare FOREGROUND_SERVICE_DATA_SYNC for target-SDK 34 broker service startup."
}
if ($manifest -notmatch 'android\.permission\.FOREGROUND_SERVICE_CAMERA') {
    throw "Manifold broker Android manifest must declare FOREGROUND_SERVICE_CAMERA for target-SDK 34 camera-source service startup."
}
if ($manifest -notmatch 'android\.permission\.NEARBY_WIFI_DEVICES') {
    throw "Manifold broker Android manifest must declare NEARBY_WIFI_DEVICES for Android 13+ Wi-Fi Direct peer socket binding."
}
if ($manifest -notmatch 'android:usesPermissionFlags="neverForLocation"') {
    throw "Manifold broker Android manifest must mark NEARBY_WIFI_DEVICES neverForLocation."
}
if ($manifest -notmatch 'android\.permission\.ACCESS_FINE_LOCATION') {
    throw "Manifold broker Android manifest must retain ACCESS_FINE_LOCATION for pre-Android-13 Wi-Fi Direct fallback."
}
if ($manifest -notmatch 'horizonos\.permission\.HEADSET_CAMERA') {
    throw "Manifold broker Android manifest must declare Quest headset camera permission for camera-source mode."
}
if ($manifest -notmatch 'BROKER_ADMISSION' -or $manifest -notmatch 'protectionLevel="signature"' -or $manifest -notmatch 'ManifoldAdmissionService') {
    throw "Manifold broker Android manifest does not expose signature-scoped admission."
}
if ($admissionService -notmatch 'message\.sendingUid' -or $admissionService -notmatch 'GET_SIGNING_CERTIFICATES' -or $admissionService -notmatch 'SecureRandom') {
    throw "ManifoldAdmissionService does not project Binder UID/package/signing identity and entropy."
}
if ($admissionBridge -notmatch 'nativeExecute' -or $admissionBridge -notmatch 'rusty\.manifold\.admission') {
    throw "ManifoldAdmissionNativeBridge does not delegate to Manifold admission."
}
if ($authorityBridge -notmatch 'nativeInitialize' -or $authorityBridge -notmatch 'nativeMutate' -or $authorityBridge -notmatch 'rusty\.quest\.broker\.server_mutation_response\.v1') {
    throw "ManifoldRuntimeAuthorityBridge does not delegate initialization and mutation to the stateful Rust runtime."
}
if ($manifest -notmatch 'android:name="\.BrokerStartService"\s+android:exported="false"' -or $manifest -notmatch 'android:foregroundServiceType="dataSync\|camera"' -or $manifest -notmatch 'android:stopWithTask="false"') {
    throw "Manifold broker Android manifest must keep BrokerStartService non-exported with dataSync/camera foreground types."
}
if ($manifest -notmatch 'android:name="\.ManifoldAdmissionService"\s+android:exported="true"\s+android:permission="io\.github\.mesmerprism\.rustymanifold\.permission\.BROKER_ADMISSION"') {
    throw "Manifold admission service must be exported only behind the signature permission."
}
if ($launchEvidence -notmatch 'rusty\.quest\.manifold_broker_android\.launch_evidence\.v1') {
    throw "BrokerLaunchEvidence does not emit launch evidence schema."
}
if ($launchEvidence -notmatch 'ManifoldRuntimeAuthorityBridge\.evidence' -or $launchEvidence -match 'put\("authority"') {
    throw "BrokerLaunchEvidence must project Rust runtime evidence instead of manufacturing an authority label."
}
if ($activity -notmatch 'requestPermissions') {
    throw "BrokerStartActivity does not request runtime camera permission."
}
if ($activity -notmatch 'GeneratedBrokerProductConfig\.CAMERA_MEDIA_ENABLED') {
    throw "BrokerStartActivity does not gate camera permission requests by the accepted product lock."
}
if ($activity -notmatch 'ManifoldRuntimeAuthorityBridge\.initialize\(\)' -or
    $service -notmatch 'ManifoldRuntimeAuthorityBridge\.initialize\(\)' -or
    $authorityBridge -notmatch 'GeneratedBrokerRuntimeConfig\.JSON' -or
    $authorityBridge -notmatch 'GeneratedBrokerRuntimeConfig\.SHA256') {
    throw "Broker launch surfaces do not initialize the exact generated stateful runtime config."
}
if ($service -notmatch 'extends Service' -or $service -notmatch 'START_STICKY') {
    throw "BrokerStartService must be a sticky Android Service."
}
if ($service -notmatch 'startForeground' -or $service -notmatch 'NotificationChannel') {
    throw "BrokerStartService must promote itself to a notification-backed foreground service."
}
if ($service -notmatch 'FOREGROUND_SERVICE_TYPE_CAMERA' -or $service -notmatch 'FOREGROUND_SERVICE_TYPE_DATA_SYNC') {
    throw "BrokerStartService must start foreground with both camera and dataSync service types."
}
if ($service -notmatch 'GeneratedBrokerProductConfig\.CAMERA_MEDIA_ENABLED') {
    throw "BrokerStartService does not gate the camera foreground-service type by the accepted product lock."
}
if ($service -notmatch 'LocalManifoldBrokerServer\.get\(\)\.start') {
    throw "BrokerStartService does not start the local Manifold broker server."
}
if ($server -notmatch '/manifold/v1/events') {
    throw "Local broker server does not expose /manifold/v1/events."
}
if ($server -notmatch 'rusty\.manifold\.command\.envelope\.v1') {
    throw "Local broker server does not recognize Manifold command envelopes."
}
if ($server -notmatch 'ManifoldRuntimeAuthorityBridge\.evaluateMutation' -or
    $server -match 'put\("accepted"' -or
    $server -match 'put\("authority"') {
    throw "Local broker server bypasses Rust mutation authority or manufactures acceptance."
}
if ($server -notmatch 'getJSONObject\("effect_params"\)' -or
    $server -match 'message\.optJSONObject\("params"\)') {
    throw "Local broker server does not consume only Rust receipt-bound effect params."
}
if ($server -notmatch 'live_stream_events_synthesized') {
    throw "Local broker server must explicitly report that it does not synthesize live stream events."
}
if ($server -notmatch 'remote_camera_runtime') {
    throw "Local broker server does not attach remote_camera_runtime command ack status."
}
if ($server -notmatch 'media_stream_runtime') {
    throw "Local broker server does not attach media_stream_runtime command ack status for QCL-082."
}
if ($server -notmatch 'hostess\.makepad\.bridge_probe\.set_marker') {
    throw "Local broker server does not retain the Hostess Makepad compatibility effect adapter."
}
if ($server -notmatch 'rusty\.hostess\.bridge_command\.request\.v1') {
    throw "Local broker server does not dispatch Hostess bridge command request payloads."
}
if ($server -notmatch 'stream\.hostess\.makepad\.bridge_command') {
    throw "Local broker server does not expose the Hostess bridge command dispatch stream."
}
if ($server -notmatch 'stream\.hostess\.makepad\.bridge_command\.receipt') {
    throw "Local broker server does not expose the Hostess bridge command runtime receipt stream."
}
if ($server -notmatch 'runtime_receipt_required') {
    throw "Local broker server must report that runtime receipts are required for applied command evidence."
}
if ($server -notmatch 'command_transport", "manifold-broker-stream"') {
    throw "Local broker server must mark broker-dispatched Hostess bridge commands as manifold-broker-stream transport."
}
if ($server -notmatch 'high_rate_json_payload", false') {
    throw "Local broker server must prove bridge command JSON is low-rate control, not high-rate payload transport."
}
if ($remoteCameraRuntime -notmatch 'command\.remote_camera\.start_receiver') {
    throw "RemoteCameraSessionRuntime does not handle remote camera start_receiver."
}
if ($remoteCameraRuntime -notmatch 'command\.remote_camera\.start_sender') {
    throw "RemoteCameraSessionRuntime does not handle remote camera start_sender."
}
if ($remoteCameraRuntime -notmatch 'command\.media_stream\.start_source') {
    throw "RemoteCameraSessionRuntime does not handle media_stream start_source."
}
if ($remoteCameraRuntime -notmatch 'rusty\.quest\.media_stream\.android_runtime_status\.v1') {
    throw "RemoteCameraSessionRuntime does not emit the media-stream runtime status schema."
}
if ($remoteCameraRuntime -notmatch 'runtime_family", "media_stream"') {
    throw "RemoteCameraSessionRuntime does not mark QCL-082 runtime_family=media_stream."
}
if ($remoteCameraRuntime -notmatch 'compatibility_runtime", "remote_camera"') {
    throw "RemoteCameraSessionRuntime must expose the remote_camera compatibility runtime for media-stream aliases."
}
if ($remoteCameraRuntime -notmatch 'command\.remote_camera\.get_status') {
    throw "RemoteCameraSessionRuntime does not handle remote camera get_status."
}
if ($remoteCameraRuntime -notmatch 'command\.remote_camera\.stop') {
    throw "RemoteCameraSessionRuntime does not handle remote camera stop."
}
if ($remoteCameraRuntime -notmatch 'command\.media_stream\.start_source') {
    throw "RemoteCameraSessionRuntime does not handle media-stream start_source aliases."
}
if ($remoteCameraRuntime -notmatch 'command\.media_stream\.get_status') {
    throw "RemoteCameraSessionRuntime does not handle media-stream get_status aliases."
}
if ($remoteCameraRuntime -notmatch 'rusty\.quest\.media_stream\.android_runtime_status\.v1') {
    throw "RemoteCameraSessionRuntime does not expose the media-stream runtime status schema."
}
if ($remoteCameraRuntime -notmatch 'RUSTY_QUEST_REMOTE_CAMERA_' -or $remoteCameraRuntime -notmatch 'RECEIVER_ARMED') {
    throw "RemoteCameraSessionRuntime does not emit the receiver armed marker token."
}
if ($remoteCameraRuntime -notmatch 'debug\.rustyquest\.remote_camera\.receiver_ports') {
    throw "RemoteCameraSessionRuntime does not read the receiver port runtime property."
}
if ($remoteCameraRuntime -notmatch 'debug\.rustyquest\.remote_camera\.transport_receive_ports') {
    throw "RemoteCameraSessionRuntime does not read the transport receiver port runtime property."
}
if ($remoteCameraRuntime -notmatch 'RECEIVER_START_READY_WAIT_MS') {
    throw "RemoteCameraSessionRuntime must bound receiver start readiness waits."
}
if ($remoteCameraRuntime -notmatch 'rusty\.quest\.remote_camera\.receiver_start_readiness\.v1') {
    throw "RemoteCameraSessionRuntime does not emit receiver start readiness evidence."
}
if ($remoteCameraRuntime -notmatch 'receiver_ready') {
    throw "RemoteCameraSessionRuntime does not report receiver readiness before sender startup."
}
if ($remoteCameraRuntime -notmatch 'transport_server_bound_address') {
    throw "RemoteCameraSessionRuntime does not report transport receiver bound address diagnostics."
}
if ($remoteCameraRuntime -notmatch 'local_receiver_bound_address') {
    throw "RemoteCameraSessionRuntime does not report local receiver bound address diagnostics."
}
if ($remoteCameraRuntime -notmatch 'waiting_for_transport_peer_with_local_listener') {
    throw "RemoteCameraSessionRuntime must bind the local renderer listener while waiting for transport-backed receiver peers."
}
if ($remoteCameraRuntime -notmatch 'transport_peer_connected_waiting_for_local_client') {
    throw "RemoteCameraSessionRuntime must preserve transport-backed receiver peer acceptance before consuming a local renderer client."
}
if ($remoteCameraRuntime -notmatch 'transport_stream_copy_error_after_bytes') {
    throw "RemoteCameraSessionRuntime must recycle accepted transport receiver segments after post-byte copy errors."
}
if ($remoteCameraRuntime -notmatch 'debug\.rustyquest\.remote_camera\.transport_routes') {
    throw "RemoteCameraSessionRuntime does not read the peer transport route runtime property."
}
if ($remoteCameraRuntime -notmatch 'parts\.length\s*==\s*5\s*\|\|\s*parts\.length\s*==\s*6') {
    throw "RemoteCameraSessionRuntime must accept canonical six-field authority routes while retaining five-field compatibility."
}
if ($remoteCameraRuntime -notmatch 'socket_authority') {
    throw "RemoteCameraSessionRuntime must preserve socket authority separately from route kind."
}
if ($remoteCameraRuntime -notmatch 'debug\.rustyquest\.remote_camera\.transport_bind_local_address') {
    throw "RemoteCameraSessionRuntime does not read the explicit Wi-Fi Direct local bind address property."
}
if ($remoteCameraRuntime -notmatch 'debug\.rustyquest\.remote_camera\.transport_socket_authority') {
    throw "RemoteCameraSessionRuntime must read socket authority independently from the route kind."
}
if ($remoteCameraRuntime -notmatch 'optJSONObject\("params"\)') {
    throw "RemoteCameraSessionRuntime does not read Hostess bridge-command params."
}
if ($remoteCameraRuntime -notmatch 'sender_source_kind",\s*"source_kind"') {
    throw "RemoteCameraSessionRuntime does not accept sender source-kind params aliases."
}
if ($remoteCameraRuntime -notmatch 'peer_socket_bound_local_address') {
    throw "RemoteCameraSessionRuntime does not report Wi-Fi Direct peer socket local binding diagnostics."
}
if ($remoteCameraDirectP2pSocketAuthority -notmatch 'rusty_direct_p2p_explicit_local_bind_address') {
    throw "RemoteCameraSessionRuntime does not attempt explicit QCL-041-proven local address binding."
}
if ($remoteCameraRuntime -notmatch 'local_bind_host') {
    throw "RemoteCameraSessionRuntime does not report the explicit local bind host in peer routes."
}
if ($remoteCameraRuntime -notmatch 'WIFI_DIRECT_PEER_BIND_WAIT_MS') {
    throw "RemoteCameraSessionRuntime does not wait for Wi-Fi Direct peer socket binding before QCL-082 connect."
}
if ($remoteCameraDirectP2pSocketAuthority -notmatch 'isLikelyWifiDirectPeerAddress') {
    throw "RemoteCameraSessionRuntime does not identify direct-Wi-Fi peer address ranges before binding."
}
if ($remoteCameraRuntime -notmatch 'peer_socket_wifi_direct_bind_required') {
    throw "RemoteCameraSessionRuntime does not report when Wi-Fi Direct peer binding is required."
}
if ($remoteCameraRuntime -notmatch 'getSocketFactory\(\)\.createSocket') {
    throw "RemoteCameraSessionRuntime does not create peer sockets from the selected Android Network."
}
if ($remoteCameraDirectP2pSocketAuthority -notmatch 'NetworkInterface\.getNetworkInterfaces') {
    throw "RemoteCameraSessionRuntime does not fall back to Wi-Fi Direct local address binding."
}
if ($remoteCameraDirectP2pSocketAuthority -notmatch 'p2pInterface\s*&&\s*sameSubnet') {
    throw "RemoteCameraDirectP2pSocketAuthority must reject WLAN and off-subnet fallback addresses."
}
if ($remoteCameraDirectP2pSocketAuthority -notmatch 'isP2pInterfaceName') {
    throw "RemoteCameraDirectP2pSocketAuthority must centralize P2P interface classification."
}
if ($remoteCameraDirectP2pSocketAuthority -notmatch 'findInterfaceNameForAddress') {
    throw "RemoteCameraDirectP2pSocketAuthority does not expose interface-name diagnostics for bound receiver sockets."
}
if ($remoteCameraDirectP2pSocketAuthority -notmatch 'rusty_direct_p2p_socket_authority') {
    throw "RemoteCameraDirectP2pSocketAuthority does not name the Rusty direct p2p socket authority."
}
if ($remoteCameraDirectP2pSocketAuthority -notmatch 'direct_p2p_tcp') {
    throw "RemoteCameraDirectP2pSocketAuthority does not name the canonical direct P2P TCP route kind."
}
if ($remoteCameraDirectP2pSocketAuthority -notmatch 'isValidRouteAuthorityContract') {
    throw "RemoteCameraDirectP2pSocketAuthority must reject a canonical direct P2P route with the wrong socket authority."
}
if ($remoteCameraRuntime -notmatch 'peer_socket_authority') {
    throw "RemoteCameraSessionRuntime does not report the peer socket authority in lane evidence."
}
if ($remoteCameraRuntime -notmatch 'RemoteCameraDirectP2pSocketAuthority\.requiresDirectP2pSocket') {
    throw "RemoteCameraSessionRuntime does not delegate direct p2p socket selection to the shared authority."
}
if ($remoteCameraRuntime -notmatch 'hasDirectP2pLocalAddressBinding') {
    throw "RemoteCameraSessionRuntime must fail closed unless the local source is on a P2P interface and peer subnet."
}
if ($remoteCameraRuntime -notmatch 'hasDirectP2pNetworkBinding') {
    throw "RemoteCameraSessionRuntime must require a route-matched Android P2P Network."
}
if ($remoteCameraRuntime -notmatch 'explicit_local_bind_rejected_not_p2p_peer_subnet') {
    throw "RemoteCameraSessionRuntime must reject explicit WLAN or off-subnet direct-P2P binds."
}
if ($remoteCameraRuntime -notmatch 'p2p_interface_route_match') {
    throw "RemoteCameraSessionRuntime must select Android P2P Networks only when their route matches the peer."
}
if ($remoteCameraRuntime -notmatch 'notePeerSocketConnected') {
    throw "RemoteCameraSessionRuntime must record the actual connected peer socket source address and interface."
}
if ($remoteCameraRuntime -notmatch 'peer_socket_direct_p2p_ready') {
    throw "RemoteCameraSessionRuntime must report fail-closed direct-P2P socket readiness."
}
if ($remoteCameraRuntime -notmatch 'debug\.rustyquest\.remote_camera\.sender_source_kind') {
    throw "RemoteCameraSessionRuntime does not read the sender source kind runtime property."
}
if ($remoteCameraRuntime -notmatch 'debug\.rustyquest\.remote_camera\.sender_camera_ids') {
    throw "RemoteCameraSessionRuntime does not read the per-eye sender camera id runtime property."
}
if ($remoteCameraRuntime -notmatch 'SENDER_TRANSPORT_BRIDGE_STARTED') {
    throw "RemoteCameraSessionRuntime does not emit the sender transport bridge marker token."
}
if ($remoteCameraRuntime -notmatch 'SENDER_SOURCE_UNAVAILABLE') {
    throw "RemoteCameraSessionRuntime does not emit the sender source unavailable marker token."
}
if ($remoteCameraSourceRuntime -notmatch 'camera2_mediacodec_surface') {
    throw "RemoteCameraSourceRuntime does not model the Camera2 MediaCodec sender source."
}
if ($remoteCameraSourceRuntime -notmatch 'diagnostic_synthetic_mediacodec_surface') {
    throw "RemoteCameraSourceRuntime does not model the diagnostic synthetic MediaCodec sender source."
}
if ($h264MediaStreamWriter -notmatch 'STREAM_MAGIC = "RMANVID1"') {
    throw "H264MediaStreamWriter must emit the Manifold H.264 stream magic RMANVID1."
}
if ($h264MediaStreamWriter -match 'RMQVID01') {
    throw "H264MediaStreamWriter still contains the interim Quest stream magic RMQVID01."
}
if ($mediaCodecSurfaceEncoder -notmatch 'MediaCodec\.createEncoderByType') {
    throw "MediaCodecSurfaceEncoder does not create a MediaCodec encoder."
}
if ($remoteCameraSourceRuntime -notmatch 'CameraManager') {
    throw "RemoteCameraSourceRuntime does not inspect Camera2 devices."
}
if ($remoteCameraSourceRuntime -notmatch 'parseCameraIds') {
    throw "RemoteCameraSourceRuntime does not parse per-eye Camera2 id bindings."
}
if ($remoteCameraSourceRuntime -notmatch 'cachedCodecConfigPacket') {
    throw "RemoteCameraSourceRuntime does not cache codec config for source-consumer reconnect epochs."
}
if ($remoteCameraSourceRuntime -notmatch 'replayCachedCodecConfig') {
    throw "RemoteCameraSourceRuntime does not replay codec config after writing a fresh stream header."
}
if ($remoteCameraSourceRuntime -notmatch 'projectionMetadataReady", true') {
    throw "RemoteCameraSourceRuntime must emit projection-ready RMANVID1 stream header metadata."
}
if ($remoteCameraSourceRuntime -notmatch 'projectionGeometryProfile", "full-frame-diagnostic"') {
    throw "RemoteCameraSourceRuntime must declare the full-frame projection profile for RMANVID1 stream headers."
}
if ($remoteCameraSourceRuntime -notmatch 'contentMappingIntent", "map-full-frame-content-to-projection-area"') {
    throw "RemoteCameraSourceRuntime must declare full-frame content mapping for RMANVID1 stream headers."
}
if ($remoteCameraSourceRuntime -notmatch 'consumer_sync_frame_request_count') {
    throw "RemoteCameraSourceRuntime does not report consumer-triggered sync-frame requests."
}
if ($remoteCameraSourceRuntime -notmatch 'streamReady') {
    throw "RemoteCameraSourceRuntime must not expose a source socket until its stream header epoch is ready."
}
if ($remoteCameraRuntime -notmatch 'high_rate_json_payload", false') {
    throw "RemoteCameraSessionRuntime must prove high-rate media is not carried through JSON."
}
if ($remoteCameraSourceRuntime -notmatch 'high_rate_json_payload", false') {
    throw "RemoteCameraSourceRuntime must prove high-rate media is not carried through JSON."
}

$combined = "$manifest`n$activity`n$service`n$launchEvidence`n$server`n$remoteCameraRuntime`n$remoteCameraSourceRuntime`n$h264MediaStreamWriter`n$mediaCodecSurfaceEncoder"
$legacyTokens = @(
    ("RUSTY" + "_XR_"),
    ("rusty" + ".xr."),
    ("/rusty" + "xr/v1"),
    ("com.example." + "rustyxr.broker"),
    ("Rusty" + "XR")
)
foreach ($token in $legacyTokens) {
    if ($combined.Contains($token)) {
        throw "Manifold broker Android scaffold contains legacy token: $token"
    }
}

if ($Build) {
    & (Join-Path $PSScriptRoot "Build-ManifoldBrokerAndroid.ps1") -AndroidHome $AndroidHome -JavaHome $JavaHome -LegacyCameraP2pCompatibility | Out-Host
}

Write-Output "Rusty Quest Manifold broker Android validation passed"
