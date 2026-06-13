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

foreach ($path in @($manifestPath, $activityPath, $serverPath, $remoteCameraRuntimePath, $remoteCameraSourceRuntimePath)) {
    if (-not (Test-Path $path)) {
        throw "Missing Manifold broker Android file: $path"
    }
}

$manifest = Get-Content -Raw -Path $manifestPath
$activity = Get-Content -Raw -Path $activityPath
$server = Get-Content -Raw -Path $serverPath
$remoteCameraRuntime = Get-Content -Raw -Path $remoteCameraRuntimePath
$remoteCameraSourceRuntime = Get-Content -Raw -Path $remoteCameraSourceRuntimePath

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
if ($remoteCameraRuntime -notmatch 'RUSTY_QUEST_REMOTE_CAMERA_RECEIVER_ARMED') {
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
if ($remoteCameraRuntime -notmatch 'RUSTY_QUEST_REMOTE_CAMERA_SENDER_TRANSPORT_BRIDGE_STARTED') {
    throw "RemoteCameraSessionRuntime does not emit the sender transport bridge marker token."
}
if ($remoteCameraRuntime -notmatch 'RUSTY_QUEST_REMOTE_CAMERA_SENDER_SOURCE_UNAVAILABLE') {
    throw "RemoteCameraSessionRuntime does not emit the sender source unavailable marker token."
}
if ($remoteCameraSourceRuntime -notmatch 'camera2_mediacodec_surface') {
    throw "RemoteCameraSourceRuntime does not model the Camera2 MediaCodec sender source."
}
if ($remoteCameraSourceRuntime -notmatch 'diagnostic_synthetic_mediacodec_surface') {
    throw "RemoteCameraSourceRuntime does not model the diagnostic synthetic MediaCodec sender source."
}
if ($remoteCameraSourceRuntime -notmatch 'STREAM_MAGIC = "RMANVID1"') {
    throw "RemoteCameraSourceRuntime must emit the Manifold H.264 stream magic RMANVID1."
}
if ($remoteCameraSourceRuntime -match 'RMQVID01') {
    throw "RemoteCameraSourceRuntime still contains the interim Quest stream magic RMQVID01."
}
if ($remoteCameraSourceRuntime -notmatch 'MediaCodec\.createEncoderByType') {
    throw "RemoteCameraSourceRuntime does not create a MediaCodec encoder."
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

$combined = "$manifest`n$activity`n$server`n$remoteCameraRuntime`n$remoteCameraSourceRuntime"
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
