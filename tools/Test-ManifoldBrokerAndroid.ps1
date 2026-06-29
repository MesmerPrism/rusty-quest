param(
    [switch]$Build,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$appRoot = Join-Path $repoRoot "apps\manifold-broker-android"
$manifestPath = Join-Path $appRoot "AndroidManifest.xml"
$activityPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\BrokerStartActivity.java"
$serverPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\LocalManifoldBrokerServer.java"
$remoteCameraRuntimePath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\RemoteCameraSessionRuntime.java"
$remoteCameraSourceRuntimePath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\RemoteCameraSourceRuntime.java"
$h264MediaStreamWriterPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\H264MediaStreamWriter.java"
$mediaCodecSurfaceEncoderPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\MediaCodecSurfaceEncoder.java"

foreach ($path in @($manifestPath, $activityPath, $serverPath, $remoteCameraRuntimePath, $remoteCameraSourceRuntimePath, $h264MediaStreamWriterPath, $mediaCodecSurfaceEncoderPath)) {
    if (-not (Test-Path $path)) {
        throw "Missing Manifold broker Android file: $path"
    }
}

$manifest = Get-Content -Raw -Path $manifestPath
$activity = Get-Content -Raw -Path $activityPath
$server = Get-Content -Raw -Path $serverPath
$remoteCameraRuntime = Get-Content -Raw -Path $remoteCameraRuntimePath
$remoteCameraSourceRuntime = Get-Content -Raw -Path $remoteCameraSourceRuntimePath
$h264MediaStreamWriter = Get-Content -Raw -Path $h264MediaStreamWriterPath
$mediaCodecSurfaceEncoder = Get-Content -Raw -Path $mediaCodecSurfaceEncoderPath

if ($manifest -notmatch 'package="io\.github\.mesmerprism\.rustymanifold\.broker"') {
    throw "Manifold broker Android manifest has the wrong package."
}
if ($manifest -notmatch 'android\.permission\.CAMERA') {
    throw "Manifold broker Android manifest must declare Android camera permission for camera-source mode."
}
if ($manifest -notmatch 'horizonos\.permission\.HEADSET_CAMERA') {
    throw "Manifold broker Android manifest must declare Quest headset camera permission for camera-source mode."
}
if ($activity -notmatch 'rusty\.quest\.manifold_broker_android\.launch_evidence\.v1') {
    throw "BrokerStartActivity does not emit launch evidence schema."
}
if ($activity -notmatch 'requestPermissions') {
    throw "BrokerStartActivity does not request runtime camera permission."
}
if ($server -notmatch '/manifold/v1/events') {
    throw "Local broker server does not expose /manifold/v1/events."
}
if ($server -notmatch 'rusty\.manifold\.command\.envelope\.v1') {
    throw "Local broker server does not recognize Manifold command envelopes."
}
if ($server -notmatch 'live_stream_events_synthesized') {
    throw "Local broker server must explicitly report that it does not synthesize live stream events."
}
if ($server -notmatch 'remote_camera_runtime') {
    throw "Local broker server does not attach remote_camera_runtime command ack status."
}
if ($server -notmatch 'media_stream_runtime') {
    throw "Local broker server does not attach media_stream_runtime command ack status."
}
if ($server -notmatch 'hostess\.makepad\.bridge_probe\.set_marker') {
    throw "Local broker server does not authorize the Hostess Makepad safe bridge probe command."
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
if ($remoteCameraRuntime -notmatch 'command\.remote_camera\.get_status') {
    throw "RemoteCameraSessionRuntime does not handle remote camera get_status."
}
if ($remoteCameraRuntime -notmatch 'command\.remote_camera\.stop') {
    throw "RemoteCameraSessionRuntime does not handle remote camera stop."
}
if ($remoteCameraRuntime -notmatch 'command\.media_stream\.start_receiver') {
    throw "RemoteCameraSessionRuntime does not handle media-stream start_receiver aliases."
}
if ($remoteCameraRuntime -notmatch 'command\.media_stream\.start_source') {
    throw "RemoteCameraSessionRuntime does not handle media-stream start_source aliases."
}
if ($remoteCameraRuntime -notmatch 'command\.media_stream\.start_transport') {
    throw "RemoteCameraSessionRuntime does not handle media-stream start_transport aliases."
}
if ($remoteCameraRuntime -notmatch 'command\.media_stream\.request_keyframe') {
    throw "RemoteCameraSessionRuntime does not expose media-stream request_keyframe control status."
}
if ($remoteCameraRuntime -notmatch 'command\.media_stream\.set_bitrate') {
    throw "RemoteCameraSessionRuntime does not expose media-stream set_bitrate control status."
}
if ($remoteCameraRuntime -notmatch 'encoder_control_not_implemented') {
    throw "RemoteCameraSessionRuntime must explicitly report unsupported generic encoder controls."
}
if ($remoteCameraRuntime -notmatch 'command\.media_stream\.get_status') {
    throw "RemoteCameraSessionRuntime does not handle media-stream get_status aliases."
}
if ($remoteCameraRuntime -notmatch 'rusty\.quest\.media_stream\.android_runtime_status\.v1') {
    throw "RemoteCameraSessionRuntime does not expose the media-stream runtime status schema."
}
if ($remoteCameraRuntime -notmatch 'debug\.rustyquest\.media_stream\.') {
    throw "RemoteCameraSessionRuntime does not read generic media-stream runtime properties."
}
if ($remoteCameraRuntime -notmatch 'RUSTY_QUEST_MEDIA_STREAM_') {
    throw "RemoteCameraSessionRuntime does not emit generic media-stream marker tokens."
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
if ($remoteCameraRuntime -notmatch 'debug\.rustyquest\.remote_camera\.transport_routes') {
    throw "RemoteCameraSessionRuntime does not read the peer transport route runtime property."
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
if ($remoteCameraSourceRuntime -notmatch 'display_composite_mediaprojection_mediacodec_surface') {
    throw "RemoteCameraSourceRuntime does not model the MediaProjection display source adapter surface."
}
if ($remoteCameraSourceRuntime -notmatch 'shell_display_mirror_mediacodec_surface') {
    throw "RemoteCameraSourceRuntime does not model the lab-only shell display source adapter surface."
}
if ($remoteCameraSourceRuntime -notmatch 'rusty\.quest\.media_stream\.android_display_source_adapter\.v1') {
    throw "RemoteCameraSourceRuntime does not expose the media-stream display source adapter schema."
}
if ($remoteCameraSourceRuntime -notmatch 'mediaprojection_consent_route_not_implemented_in_broker_apk') {
    throw "RemoteCameraSourceRuntime must explicitly gate MediaProjection display capture behind consent route work."
}
if ($remoteCameraSourceRuntime -notmatch 'shell_hidden_display_sidecar_required') {
    throw "RemoteCameraSourceRuntime must explicitly gate shell hidden-display capture behind the lab sidecar."
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
if ($remoteCameraSourceRuntime -notmatch 'MediaCodecSurfaceEncoder\.create') {
    throw "RemoteCameraSourceRuntime does not use the shared MediaCodec surface encoder helper."
}
if ($remoteCameraSourceRuntime -notmatch 'H264MediaStreamWriter\.writeStreamHeader') {
    throw "RemoteCameraSourceRuntime does not use the shared H.264 stream header writer."
}
if ($remoteCameraSourceRuntime -notmatch 'H264MediaStreamWriter\.writeEncodedPacket') {
    throw "RemoteCameraSourceRuntime does not use the shared H.264 encoded packet writer."
}
if ($remoteCameraSourceRuntime -notmatch 'CameraManager') {
    throw "RemoteCameraSourceRuntime does not inspect Camera2 devices."
}
if ($remoteCameraSourceRuntime -notmatch 'parseCameraIds') {
    throw "RemoteCameraSourceRuntime does not parse per-eye Camera2 id bindings."
}
if ($remoteCameraRuntime -notmatch 'high_rate_json_payload", false') {
    throw "RemoteCameraSessionRuntime must prove high-rate media is not carried through JSON."
}
if ($remoteCameraSourceRuntime -notmatch 'high_rate_json_payload", false') {
    throw "RemoteCameraSourceRuntime must prove high-rate media is not carried through JSON."
}

$combined = "$manifest`n$activity`n$server`n$remoteCameraRuntime`n$remoteCameraSourceRuntime`n$h264MediaStreamWriter`n$mediaCodecSurfaceEncoder"
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
    & (Join-Path $PSScriptRoot "Build-ManifoldBrokerAndroid.ps1") -AndroidHome $AndroidHome -JavaHome $JavaHome | Out-Host
}

Write-Output "Rusty Quest Manifold broker Android validation passed"
