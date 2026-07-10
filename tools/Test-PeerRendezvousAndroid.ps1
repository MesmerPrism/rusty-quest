param([string]$RepoRoot = "")

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$appRoot = Join-Path $RepoRoot "apps\peer-rendezvous-android"
$sourceRoot = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustyquest\peer_rendezvous"
$paths = [ordered]@{
    manifest = Join-Path $appRoot "AndroidManifest.xml"
    activity = Join-Path $sourceRoot "PeerRendezvousActivity.java"
    service = Join-Path $sourceRoot "PeerRendezvousService.java"
    config = Join-Path $sourceRoot "BleRendezvousConfig.java"
    permissions = Join-Path $sourceRoot "BleRendezvousPermissions.java"
    protocol = Join-Path $sourceRoot "BleRendezvousProtocol.java"
    evidence = Join-Path $sourceRoot "BleRendezvousEvidence.java"
    server = Join-Path $sourceRoot "BleRendezvousGattServer.java"
    client = Join-Path $sourceRoot "BleRendezvousGattClient.java"
    build = Join-Path $RepoRoot "tools\Build-PeerRendezvousAndroid.ps1"
    smoke = Join-Path $RepoRoot "tools\Invoke-PeerRendezvousAndroidSmoke.ps1"
    pair = Join-Path $RepoRoot "tools\Invoke-PeerRendezvousAndroidPair.ps1"
    contract = Join-Path $RepoRoot "crates\rusty-quest-device-link\src\ble_rendezvous.rs"
    direct_p2p_contract = Join-Path $RepoRoot "crates\rusty-quest-device-link\src\direct_p2p_socket_authority.rs"
    direct_p2p_validator = Join-Path $RepoRoot "crates\rusty-quest-device-link\src\bin\validate_direct_p2p_socket_route.rs"
}
foreach ($entry in $paths.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) {
        throw "Missing peer rendezvous surface $($entry.Key): $($entry.Value)"
    }
}

$manifest = Get-Content -Raw -LiteralPath $paths.manifest
$activity = Get-Content -Raw -LiteralPath $paths.activity
$service = Get-Content -Raw -LiteralPath $paths.service
$config = Get-Content -Raw -LiteralPath $paths.config
$permissions = Get-Content -Raw -LiteralPath $paths.permissions
$protocol = Get-Content -Raw -LiteralPath $paths.protocol
$evidence = Get-Content -Raw -LiteralPath $paths.evidence
$server = Get-Content -Raw -LiteralPath $paths.server
$client = Get-Content -Raw -LiteralPath $paths.client
$build = Get-Content -Raw -LiteralPath $paths.build
$smoke = Get-Content -Raw -LiteralPath $paths.smoke
$pair = Get-Content -Raw -LiteralPath $paths.pair
$contract = Get-Content -Raw -LiteralPath $paths.contract
$directP2pContract = Get-Content -Raw -LiteralPath $paths.direct_p2p_contract
$combined = @($manifest, $activity, $service, $config, $permissions, $protocol, $evidence, $server, $client) -join "`n"

function Assert-Match([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) { throw $Message }
}

Assert-Match $manifest 'package="io\.github\.mesmerprism\.rustyquest\.peer_rendezvous"' "Wrong peer rendezvous package."
foreach ($permission in @("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "FOREGROUND_SERVICE_CONNECTED_DEVICE")) {
    Assert-Match $manifest $permission "Manifest missing $permission."
}
Assert-Match $manifest 'android\.hardware\.bluetooth_le' "Manifest must require BLE hardware."
Assert-Match $manifest 'android:foregroundServiceType="connectedDevice"' "Sidecar service must use connectedDevice foreground type."
if ($manifest -match 'android\.permission\.(INTERNET|NEARBY_WIFI_DEVICES|CAMERA)') {
    throw "BLE sidecar must not declare network, Wi-Fi mutation, or camera permissions."
}

Assert-Match $service 'START_NOT_STICKY' "Sidecar must be bounded and non-sticky."
Assert-Match $service 'ACTION_START' "Sidecar must require an exact start action."
Assert-Match $config 'getBooleanExtra\("enabled", false\)' "Sidecar must require enabled=true explicit opt-in."
Assert-Match $activity 'statusView\.setText\("Idle"\)' "Normal launcher start must remain inert."
Assert-Match $permissions 'BLUETOOTH_ADVERTISE' "Server permission route missing."
Assert-Match $permissions 'BLUETOOTH_SCAN' "Client permission route missing."

Assert-Match $protocol 'HmacSHA256' "BLE rendezvous messages must use HMAC-SHA256."
Assert-Match $protocol 'MessageDigest\.isEqual' "BLE HMAC comparison must be constant-time."
Assert-Match $protocol 'MAX_WIRE_BYTES = 220' "Android wire ceiling must be 220 bytes."
Assert-Match $protocol 'REQUESTED_MTU = 247' "Android adapter must negotiate the 247-byte MTU tier."
Assert-Match $protocol 'wire_message_unknown_key' "Wire parser must reject unknown fields such as credentials."
Assert-Match $protocol 'RQRV1\|%s\|%s' "Android signing input must preserve the Rust contract prefix."
Assert-Match $contract 'BLE_RENDEZVOUS_MAX_WIRE_BYTES: usize = 220' "Rust wire ceiling must match Android."
Assert-Match $contract 'RQRV1\|\{\}' "Rust signing input must preserve the Android contract prefix."
Assert-Match $contract 'clean authenticated bidirectional exchange and reconnect' "Pass receipts must require reconnect evidence."
Assert-Match $contract 'rusty\.quest\.peer_rendezvous_android_pair\.v1' "Rust pair contract schema missing."
Assert-Match $contract 'validate_ble_rendezvous_pair_receipt' "Rust pair contract validator missing."
Assert-Match $contract 'is_supported_direct_p2p_ipv4' "BLE P2P hints must consume the shared direct-P2P address rule."
Assert-Match $directP2pContract 'rusty\.quest\.direct_p2p_socket_route\.v1' "Shared direct-P2P route schema missing."
Assert-Match $directP2pContract 'rusty_owned_sockets_only' "Shared direct-P2P authority must remain Rusty-socket scoped."
Assert-Match $directP2pContract 'Android Network substitution' "Shared contract must reject Android-Network substitution claims."

Assert-Match $server 'BluetoothGattServer' "GATT server adapter missing."
Assert-Match $server 'setIncludeDeviceName\(false\)' "BLE advertising must not publish the device name."
Assert-Match $server 'proposal_authentication_failed' "Server must fail closed on bad HMAC."
Assert-Match $server 'proposal_replay_detected' "Server must reject replayed proposals."
Assert-Match $server 'reconnectsCompleted = 1' "Server must record an authenticated reconnect."
Assert-Match $client 'BluetoothLeScanner' "GATT client scanner missing."
Assert-Match $client 'negotiated_mtu_too_small' "Client must fail closed below the message MTU."
Assert-Match $client 'peer_message_authentication_failed' "Client must fail closed on bad HMAC."
Assert-Match $client 'peer_message_replay_detected' "Client must reject replayed peer messages."
Assert-Match $client 'disconnectForReconnect' "Client must exercise a bounded reconnect."
Assert-Match $client 'postReconnectMessageAuthenticated = true' "Client must record authenticated post-reconnect evidence."
Assert-Match $evidence 'rusty\.quest\.ble_rendezvous_sidecar_receipt\.v1' "Sidecar receipt schema missing."
foreach ($boundary in @(
    'raw_bluetooth_addresses_redacted", true',
    'media_payload_bytes", 0',
    'wifi_direct_mutations_executed", 0',
    'manifold_commands_executed", 0',
    'cleanup_complete')) {
    Assert-Match $evidence $boundary "Receipt boundary missing: $boundary"
}
Assert-Match $build 'wifi_direct_mutation_allowed = \$false' "Build manifest must reject Wi-Fi mutation ownership."
Assert-Match $build '"--debug-mode"' "Local evidence APK must permit scoped run-as receipt retrieval."
Assert-Match $smoke '\[Parameter\(Mandatory=\$true\)\]\[string\]\$Serial' "Smoke wrapper must require an explicit serial."
Assert-Match $smoke '\[Parameter\(Mandatory=\$true\)\]\[string\]\$QuestLeaseId' "Smoke wrapper must require a Quest lease id."
Assert-Match $smoke 'wifi_mutation_requested = \$false' "Smoke wrapper must not mutate Wi-Fi."
Assert-Match $smoke 'shared_secret_recorded = \$false' "Smoke wrapper must not record the shared secret."
Assert-Match $smoke 'validate_ble_rendezvous' "Smoke wrapper must use the Rust contract validator."
Assert-Match $smoke 'UTF8Encoding\]::new\(\$false\)' "Smoke evidence must use BOM-free UTF-8 for Rust validator compatibility."
Assert-Match $smoke '4\.\.=32 character ephemeral safe token' "Smoke wrapper must enforce the shared safe-tag bound before launch."
Assert-Match $smoke '"ble-\$modeTag-"' "Generated smoke run ids must fit the 32-character rendezvous contract."
Assert-Match $pair 'rusty\.quest\.peer_rendezvous_android_pair\.v1' "Pair wrapper schema missing."
Assert-Match $pair 'role_swap_completed' "Pair wrapper must require BLE role swap."
Assert-Match $pair 'reconnects_completed' "Pair wrapper must require reconnect evidence."
Assert-Match $pair 'raw_bluetooth_address_pattern_found' "Pair wrapper must scan its summary for raw Bluetooth addresses."
Assert-Match $pair 'shared_secret_pattern_found' "Pair wrapper must scan all text artifacts for the shared secret."
Assert-Match $pair 'bluetooth_and_p2p0_state_stable' "Pair wrapper must compare Bluetooth and p2p0 state before and after."
Assert-Match $pair 'app_fatal_count' "Pair wrapper must require a clean bounded AndroidRuntime log window."
Assert-Match $pair 'wifi_direct_mutations_executed = 0' "Pair wrapper must preserve the no-Wi-Fi-mutation boundary."
Assert-Match $pair 'shared_secret_recorded = \$false' "Pair wrapper must not record the shared secret."
Assert-Match $pair 'validate_ble_rendezvous -- pair' "Pair wrapper must invoke the independent Rust pair validator."
Assert-Match $activity 'RUSTY_QUEST_BLE_RENDEZVOUS_ACTIVITY_BLOCKED' "Activity must emit a redacted launch rejection marker."
Assert-Match $service 'RUSTY_QUEST_BLE_RENDEZVOUS_SERVICE_START' "Service must emit redacted effective launch state."
Assert-Match $service 'RUSTY_QUEST_BLE_RENDEZVOUS_SERVICE_FINISH' "Service must emit a terminal lifecycle marker."

foreach ($forbidden in @(
    'getAddress\(',
    'WifiP2pManager',
    'WifiManager',
    'MediaCodec',
    'CameraManager',
    'java\.net\.Socket',
    'LocalManifoldBrokerServer',
    'command\.remote_camera',
    'shared_secret.*put\(',
    'setIncludeDeviceName\(true\)')) {
    if ($combined -match $forbidden) {
        throw "Peer rendezvous app crosses a forbidden boundary: $forbidden"
    }
}

Write-Output "Rusty Quest peer rendezvous Android static validation passed"
