param(
    [string]$RepoRoot
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
}
$root = (Resolve-Path $RepoRoot).Path

function Read-Required([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing Q2Q peer-stereo surface: $Path"
    }
    Get-Content -LiteralPath $Path -Raw
}

function Require([string]$Label, [string]$Text, [string]$Pattern) {
    if ($Text -cnotmatch $Pattern) {
        throw "$Label is missing: $Pattern"
    }
}

$productRoot = Join-Path $root 'apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel'
$brokerRoot = Join-Path $root 'apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker'
$settings = Read-Required (Join-Path $productRoot 'SpatialVideoProjectionSettings.kt')
$nativeSettings = Read-Required (Join-Path $root 'apps\spatial-camera-panel-android\native-receipt\src\spatial_video_projection_settings.rs')
$consumer = Read-Required (Join-Path $productRoot 'SpatialPackedStereoBrokerPlayback.java')
$playback = Read-Required (Join-Path $productRoot 'SpatialStereoVideoPlayback.java')
$startup = Read-Required (Join-Path $productRoot 'SpatialVideoProjectionProbeCoordinator.kt')
$status = Read-Required (Join-Path $productRoot 'SpatialPeerStereoStatus.java')
$panel = Read-Required (Join-Path $productRoot 'PrivateLayerControlPanel.kt')
$mediaActionProvider = Read-Required (Join-Path $productRoot 'MediaActionDebugControlProvider.java')
$spatialManifest = Read-Required (Join-Path $root 'apps\spatial-camera-panel-android\app\src\main\AndroidManifest.xml')
$runtime = Read-Required (Join-Path $brokerRoot 'RemoteCameraSessionRuntime.java')
$debugProvider = Read-Required (Join-Path $brokerRoot 'RemoteCameraDebugControlProvider.java')
$credential = Read-Required (Join-Path $brokerRoot 'RemoteCameraRelayCredential.java')
$relayTransport = Read-Required (Join-Path $brokerRoot 'RemoteCameraRelayTransport.java')
$brokerBuild = Read-Required (Join-Path $root 'tools\Build-ManifoldBrokerAndroid.ps1')
$sharedSignerGate = Read-Required (Join-Path $root 'tools\checks\Test-ManifoldBrokerSharedSignerGate.ps1')
$brokerClient = Read-Required (Join-Path $root 'crates\rusty-quest-broker-client\android\io\github\mesmerprism\rustyquest\broker_client\BrokerClientProbeActivity.java')
$relay = Read-Required (Join-Path $root 'tools\q2q_peer_stereo_tls_relay.py')
$doc = Read-Required (Join-Path $root 'docs\REMOTE_CAMERA_STREAMING.md')

Require 'Settings' $settings 'peer-packed-stereo'
Require 'Settings' $settings 'peerEndpointRedacted=true peerSecretSerialized=false'
Require 'Native settings' $nativeSettings 'SpatialVideoProjectionSource::from_token'
Require 'Native settings' $nativeSettings 'self\.source\.stream_backed\(\) \|\| !self\.path\.trim\(\)\.is_empty\(\)'
Require 'Native settings' $nativeSettings 'peer_stream_is_active_without_a_file_path'
Require 'Native settings' $nativeSettings 'unknown_source_without_a_file_path_stays_inactive'
Require 'Consumer' $consumer 'RMANVID v4 packed-stereo'
Require 'Consumer' $consumer 'nativeSetPackedStereoPairMetadata'
Require 'Consumer' $consumer 'SpatialPeerStereoStatus\.rendered'
Require 'Playback diagnostics' $playback 'status=playback-error failureType='
Require 'Playback diagnostics' $playback 'peerEndpointRedacted=true peerSecretSerialized=false'
Require 'Playback diagnostics' $playback 'safeBrokerPlaybackFailureDetail'
Require 'Stream decoder owner' $startup 'delegateStreamToCameraProjection'
Require 'Stream decoder owner' $startup 'decoderOwner=raw-projection decoderStartCount=0'
Require 'Status' $status 'leftSensorTimestampNs'
Require 'Status' $status 'rightSensorTimestampNs'
Require 'Status' $status 'pairSequenceAdvancing'
Require 'Status' $status 'decoderOutputObserved'
Require 'Panel' $panel 'Section\("Peer stereo"\)'
Require 'Media action operator' $mediaActionProvider 'Process\.SHELL_UID'
Require 'Media action operator' $mediaActionProvider '!BuildConfig\.DEBUG'
Require 'Media action operator' $mediaActionProvider 'ManifoldAdmissionService'
Require 'Media action operator' $mediaActionProvider 'capability\.command\.media\.session\.'
Require 'Media action operator' $mediaActionProvider 'platform_completion_separate'
Require 'Media action operator' $mediaActionProvider 'token_serialized", false'
Require 'Media action operator' $mediaActionProvider 'secret_serialized", false'
Require 'Spatial manifest' $spatialManifest 'debug-media-action-control'
Require 'Spatial manifest' $spatialManifest 'android:permission="android\.permission\.DUMP"'
Require 'Broker receiver' $runtime 'RemoteCameraRelayTransport\.connectReceiver'
Require 'Broker receiver' $runtime 'authenticated_tls_relay_connected_waiting_for_local_client'
Require 'Broker sender' $runtime 'RemoteCameraRelayTransport\.connect'
Require 'Debug operator' $debugProvider 'Process\.SHELL_UID'
Require 'Debug operator' $debugProvider 'requirePendingAction\(authority, operation\)'
Require 'Debug operator' $debugProvider 'platform_completion_separate'
Require 'Debug operator' $debugProvider 'diagnostic_without_media_acceptance'
Require 'Debug operator' $debugProvider 'packed_source_port'
Require 'Debug operator' $debugProvider 'sbs-lr\|'
Require 'Debug operator' $debugProvider 'transport_adapter'
Require 'Debug operator' $debugProvider 'infrastructure_lan'
Require 'Debug operator' $debugProvider 'wifi_direct'
Require 'Debug operator' $debugProvider 'authenticated_tls_relay'
Require 'Debug operator' $debugProvider 'remote_camera_debug_peer_host_not_private_ipv4'
Require 'Debug operator' $debugProvider 'remote_camera_debug_wifi_direct_local_bind_address_required'
Require 'Debug operator' $debugProvider 'sender_camera_ids", "left:50,right:51"'
Require 'Debug operator' $debugProvider 'stereoRoute\('
Require 'Debug duplex operator' $debugProvider '!"start-duplex"\.equals\(method\)'
Require 'Debug duplex operator' $debugProvider 'receiver_local_stream_port'
Require 'Debug duplex operator' $debugProvider 'sender_local_stream_port'
Require 'Debug duplex operator' $debugProvider 'runtimeCommand\("start-receiver", receiverExtras, false\)'
Require 'Debug duplex operator' $debugProvider 'runtimeCommand\("start-sender", senderExtras, false\)'
Require 'Debug duplex operator' $debugProvider 'rusty\.quest\.remote_camera\.duplex_start\.v1'
Require 'Debug duplex operator' $debugProvider 'duplex_sender_barrier_delay_ms", 500, 5000'
if ($debugProvider.IndexOf('runtimeCommand("start-receiver", receiverExtras, false)') -gt
        $debugProvider.IndexOf('runtimeCommand("start-sender", senderExtras, false)')) {
    throw 'Debug duplex operator no longer arms the receiver before the sender.'
}
Require 'Broker build' $brokerBuild 'EnableRemoteCameraDebugOperator'
Require 'Broker build' $brokerBuild 'debug-remote-camera-control'
Require 'Broker build' $brokerBuild 'android:permission="android\.permission\.DUMP"'
Require 'Broker build' $brokerBuild 'RequireSharedMorphovisionSigner'
Require 'Broker build' $brokerBuild 'artifact_signer_sha256'
Require 'Shared signer gate' $sharedSignerGate 'broker default signer can silently enter'
Require 'Shared signer gate' $sharedSignerGate 'Explicit shared Morphovision signer fingerprint mismatch'
Require 'Broker client' $brokerClient 'hold_media_action_before_completion'
Require 'Broker client' $brokerClient 'status=media_action_pending'
Require 'Relay transport' $relayTransport 'ROLE_SENDER = 1'
Require 'Relay transport' $relayTransport 'ROLE_RECEIVER = 2'
Require 'Relay transport' $relayTransport 'setEndpointIdentificationAlgorithm\("HTTPS"\)'
Require 'Relay transport' $relayTransport 'MessageDigest\.isEqual'
Require 'Relay transport' $relayTransport 'getDefaultHostnameVerifier'
Require 'Relay credential' $credential 'Process-memory-only'
if ($credential -cmatch 'SharedPreferences|System\.setProperty|SystemProperties') {
    throw 'Relay credential escaped the process-memory-only boundary.'
}
Require 'Opaque relay' $relay 'opaque_binary_media'
Require 'Opaque relay' $relay 'hmac\.compare_digest'
Require 'Product doc' $doc 'screenshot brightness is\s+not camera freshness evidence'

Write-Host 'Rusty Quest Q2Q peer-stereo static gate: PASS'
