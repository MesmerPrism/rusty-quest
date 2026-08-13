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
$consumer = Read-Required (Join-Path $productRoot 'SpatialPackedStereoBrokerPlayback.java')
$status = Read-Required (Join-Path $productRoot 'SpatialPeerStereoStatus.java')
$panel = Read-Required (Join-Path $productRoot 'PrivateLayerControlPanel.kt')
$runtime = Read-Required (Join-Path $brokerRoot 'RemoteCameraSessionRuntime.java')
$credential = Read-Required (Join-Path $brokerRoot 'RemoteCameraRelayCredential.java')
$relayTransport = Read-Required (Join-Path $brokerRoot 'RemoteCameraRelayTransport.java')
$relay = Read-Required (Join-Path $root 'tools\q2q_peer_stereo_tls_relay.py')
$doc = Read-Required (Join-Path $root 'docs\REMOTE_CAMERA_STREAMING.md')

Require 'Settings' $settings 'peer-packed-stereo'
Require 'Settings' $settings 'peerEndpointRedacted=true peerSecretSerialized=false'
Require 'Consumer' $consumer 'RMANVID v4 packed-stereo'
Require 'Consumer' $consumer 'nativeSetPackedStereoPairMetadata'
Require 'Consumer' $consumer 'SpatialPeerStereoStatus\.rendered'
Require 'Status' $status 'leftSensorTimestampNs'
Require 'Status' $status 'rightSensorTimestampNs'
Require 'Status' $status 'pairSequenceAdvancing'
Require 'Status' $status 'decoderOutputObserved'
Require 'Panel' $panel 'Section\("Peer stereo"\)'
Require 'Broker receiver' $runtime 'RemoteCameraRelayTransport\.connectReceiver'
Require 'Broker receiver' $runtime 'authenticated_tls_relay_connected_waiting_for_local_client'
Require 'Broker sender' $runtime 'RemoteCameraRelayTransport\.connect'
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
