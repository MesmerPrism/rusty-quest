param([string]$RepoRoot = "", [string]$JavaHome="", [string]$AndroidJar="", [string]$JsonJar="", [string]$HostOutDir="",[switch]$ProductionCompileOnly)

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
if ($manifest -match 'android\.permission\.(INTERNET|CAMERA)') {
    throw "BLE sidecar must not declare sockets or camera permissions."
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
Assert-Match $smoke 'user_authorized_serial_scoped' "Smoke wrapper must support explicit user-authorized serial-scoped coordination."
Assert-Match $smoke 'Agent Board coordination requires QuestLeaseId' "Smoke wrapper must require a Quest lease only in the leased mode."
Assert-Match $smoke 'wifi_mutation_requested = \$false' "Smoke wrapper must not mutate Wi-Fi."
Assert-Match $smoke 'shared_secret_recorded = \$false' "Smoke wrapper must not record the shared secret."
Assert-Match $smoke 'validate_ble_rendezvous' "Smoke wrapper must use the Rust contract validator."
Assert-Match $smoke 'UTF8Encoding\]::new\(\$false\)' "Smoke evidence must use BOM-free UTF-8 for Rust validator compatibility."
Assert-Match $smoke '4\.\.=32 character ephemeral safe token' "Smoke wrapper must enforce the shared safe-tag bound before launch."
Assert-Match $smoke '"ble-\$modeTag-"' "Generated smoke run ids must fit the 32-character rendezvous contract."
Assert-Match $pair 'rusty\.quest\.peer_rendezvous_android_pair\.v1' "Pair wrapper schema missing."
Assert-Match $pair 'coordination_mode = \$CoordinationMode' "Pair wrapper must record its coordination authority."
Assert-Match $contract 'UserAuthorizedSerialScoped' "Pair contract must distinguish serial-scoped user authorization from Agent Board leases."
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

# v1 has no Wi-Fi request call; the sole separate v2 sampler is read-only.
$observer=Get-Content -Raw -LiteralPath (Join-Path $sourceRoot 'BleWifiObservation.java')
Assert-Match $config 'observed_coordination_v2",false' 'Observed coordination must be explicit and default off.'
foreach($permission in @('ACCESS_WIFI_STATE','NEARBY_WIFI_DEVICES')) {
    Assert-Match $manifest $permission "Missing opted observation permission $permission."
}
foreach($method in @('requestConnectionInfo','requestGroupInfo')) {
    Assert-Match $observer $method "Missing real read-only observation $method."
}
if($observer -match '\.(createGroup|connect|discoverPeers|removeGroup|setWifiEnabled|addLocalService)\s*\(') {
    throw 'Read-only observer contains a Wi-Fi mutation.'
}
Assert-Match $client 'requireObservedPeer\(config,message\)' 'Actual client must enforce observed peer guard.'
Assert-Match $server 'requireObservedPeer\(config,proposal\)' 'Actual server must enforce observed peer guard.'
if($ProductionCompileOnly-or@($JavaHome,$AndroidJar,$JsonJar,$HostOutDir|Where-Object {-not [string]::IsNullOrWhiteSpace($_)}).Count -gt 0) {
    foreach($required in @($JavaHome,$AndroidJar,$JsonJar,$HostOutDir)) {
        if([string]::IsNullOrWhiteSpace($required)){throw 'All four focused host inputs are required.'}
    }
    if(Test-Path -LiteralPath $HostOutDir){throw 'HostOutDir must be create-new.'}
    $hostRoot=[IO.Path]::GetFullPath($HostOutDir)
    $androidClasses=Join-Path $hostRoot 'android-classes'
    $hostClasses=Join-Path $hostRoot 'host-classes'
    $stubs=Join-Path $hostRoot 'stubs'
    New-Item -ItemType Directory -Path $androidClasses,$hostClasses,(Join-Path $stubs 'android/os'),(Join-Path $stubs 'android/content')|Out-Null
    $utf8=[Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText((Join-Path $stubs 'android/os/SystemClock.java'), 'package android.os; public final class SystemClock { public static long elapsedRealtime(){return 101;} }',$utf8)
    [IO.File]::WriteAllText((Join-Path $stubs 'android/content/Intent.java'), 'package android.content; public final class Intent { private final String action;private final java.util.Map<String,Object> data=new java.util.HashMap<>(); public Intent(String action){this.action=action;}public String getAction(){return action;} public Intent putExtra(String k,String v){data.put(k,v);return this;} public Intent putExtra(String k,boolean v){data.put(k,v);return this;} public Intent putExtra(String k,long v){data.put(k,v);return this;} public long getLongExtra(String k,long fallback){return data.containsKey(k)?((Number)data.get(k)).longValue():fallback;} public String getStringExtra(String k){return (String)data.get(k);} public boolean getBooleanExtra(String k,boolean fallback){return data.containsKey(k)?(Boolean)data.get(k):fallback;} public int getIntExtra(String k,int fallback){return data.containsKey(k)?(Integer)data.get(k):fallback;} }',$utf8)
    $javac=Join-Path $JavaHome 'bin/javac.exe';$java=Join-Path $JavaHome 'bin/java.exe'
    foreach($required in @($javac,$java,$AndroidJar,$JsonJar)){if(-not(Test-Path -LiteralPath $required)){throw 'Missing pinned host input.'}}
    $allSources=@(Get-ChildItem -LiteralPath (Join-Path $appRoot 'src/main/java') -Recurse -Filter '*.java'|ForEach-Object FullName)
    & $javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath $AndroidJar -d $androidClasses @allSources
    if($LASTEXITCODE -ne 0){throw "Real Android Java compilation failed: $LASTEXITCODE"}
    if($ProductionCompileOnly){
        # Execute the actual converted callback guards with a modeled callback queue.
        # Full production sources above compile with the exact APK-builder flags.
        $callbacks=[regex]::Match($observer,'(?s)    private boolean current.*?(?=    private static boolean sameGroup)').Value
        $disconnect=[regex]::Match($observer,'public void onChannelDisconnected\(\)\{([^}]+)\}').Groups[1].Value
        if(-not$callbacks-or-not$disconnect){throw 'Actual callback guards absent'}
        $fixture=@'
public class BleCallbackHost {
 static class WifiP2pInfo {} static class WifiP2pGroup {}
 static class WifiP2pManager {
  interface ConnectionInfoListener {void onConnectionInfoAvailable(WifiP2pInfo v);}
  interface GroupInfoListener {void onGroupInfoAvailable(WifiP2pGroup v);}
  ConnectionInfoListener info;GroupInfoListener group;boolean denied;
  void requestConnectionInfo(Object c,ConnectionInfoListener n){if(denied)throw new SecurityException();info=n;}
  void requestGroupInfo(Object c,GroupInfoListener n){if(denied)throw new SecurityException();group=n;}
 }
 static class Config {Object observation=new Object();}
 final WifiP2pManager manager=new WifiP2pManager();final Object channel=new Object();final Config config=new Config();
 boolean closed;int generation=1;int accepted;
 CALLBACKS
 void disconnect(){DISCONNECT}
 void info(){requestInfo(1,new InfoNext(){public void accept(WifiP2pInfo v){accepted++;}});}
 void group(){requestGroup(1,new GroupNext(){public void accept(WifiP2pGroup v){accepted++;}});}
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] unused){
  BleCallbackHost a=new BleCallbackHost();a.info();a.manager.info.onConnectionInfoAvailable(new WifiP2pInfo());check(a.accepted==1);
  a=new BleCallbackHost();a.info();a.disconnect();a.manager.info.onConnectionInfoAvailable(new WifiP2pInfo());check(a.accepted==0&&a.closed&&a.generation==2&&a.config.observation==null);
  a=new BleCallbackHost();a.group();a.disconnect();a.manager.group.onGroupInfoAvailable(new WifiP2pGroup());check(a.accepted==0&&a.config.observation==null);
  a=new BleCallbackHost();a.info();a.generation++;a.manager.info.onConnectionInfoAvailable(new WifiP2pInfo());check(a.accepted==0);
  a=new BleCallbackHost();a.manager.denied=true;a.info();check(a.config.observation==null);
  a=new BleCallbackHost();a.requestGroup(1,new GroupNext(){public void accept(WifiP2pGroup v){throw new IllegalStateException();}});a.manager.group.onGroupInfoAvailable(new WifiP2pGroup());check(a.config.observation==null);
  System.out.println("PASS actual callback guard fixtures=6; modeled queue, no device qualification");
 }
}
'@
        $callbackPath=Join-Path $hostRoot 'BleCallbackHost.java'
        [IO.File]::WriteAllText($callbackPath,$fixture.Replace('CALLBACKS',$callbacks).Replace('DISCONNECT',$disconnect),$utf8)
        & $javac -source 1.8 -target 1.8 -d $hostClasses $callbackPath
        if($LASTEXITCODE-ne0){throw 'Actual callback fixture compilation failed'}
        & $java -cp $hostClasses BleCallbackHost
        if($LASTEXITCODE-ne0){throw 'Actual callback fixture failed'}
        Write-Output 'PASS actual production Java8 Android bootclasspath compile';return
    }
    # Compile verbatim actual validation bodies, with only Android transport/evidence fixtures.
    $clientBody=[regex]::Match($client,'(?s)    private JSONObject verifyPeerMessage.*?(?=    private boolean disconnectForReconnect)').Value
    if(-not $clientBody){throw 'Actual client validation body not located.'}
    $clientBody=$clientBody.Replace('private JSONObject verifyPeerMessage','public JSONObject verifyPeerMessage')
    $serverBody=[regex]::Match($server,'(?s)                    JSONObject proposal = BleRendezvousProtocol.verify\(.*?statusCharacteristic.setValue\(statusMessage\);').Value
    if(-not $serverBody){throw 'Actual server validation body not located.'}
    $harness='package io.github.mesmerprism.rustyquest.peer_rendezvous; import org.json.JSONObject; import java.util.*; final class BleCallsiteHarness { static class E { int messagesReceived,authenticatedMessages,authenticationFailures,reconnectsCompleted; boolean postReconnectMessageAuthenticated; void issue(String code){} } static class Client { final BleRendezvousConfig config; final E evidence=new E();final Set<String> peerNonces=new HashSet<>();String remotePeerTag; Client(BleRendezvousConfig c){config=c;} '+$clientBody+' } static class Server { final BleRendezvousConfig config; final E evidence=new E();final Set<String> acceptedProposalNonces=new HashSet<>();int authenticatedProposalCount;byte[] offerMessage,statusMessage; final Object device=new Object(); final Map<Object,Integer> peerMtus=new HashMap<>(); final C statusCharacteristic=new C();static class C{void setValue(byte[] value){}} Server(BleRendezvousConfig c)throws Exception{config=c;offerMessage=BleRendezvousProtocol.buildMessage(c,"offer",1);peerMtus.put(device,247);} boolean proposal(byte[] value){try{'+$serverBody+' return true;}catch(Exception denied){return false;}} } }'
    $harnessPath=Join-Path $stubs 'BleCallsiteHarness.java'
    [IO.File]::WriteAllText($harnessPath,$harness,$utf8)
    $hostSources=@($harnessPath,(Join-Path $stubs 'android/os/SystemClock.java'),(Join-Path $stubs 'android/content/Intent.java'),
        (Join-Path $sourceRoot 'BleRoleReadiness.java'),(Join-Path $sourceRoot 'BleRendezvousConfig.java'),
        (Join-Path $sourceRoot 'BleRendezvousProtocol.java'),
        (Join-Path $appRoot 'tests/java/io/github/mesmerprism/rustyquest/peer_rendezvous/BleRoleReadinessTest.java'))
    & $javac -source 17 -target 17 -classpath "$JsonJar;$AndroidJar;$androidClasses" -d $hostClasses @hostSources
    if($LASTEXITCODE -ne 0){throw "Focused host compilation failed: $LASTEXITCODE"}
    & $java -classpath "$hostClasses;$JsonJar;$AndroidJar;$androidClasses" io.github.mesmerprism.rustyquest.peer_rendezvous.BleRoleReadinessTest
    if($LASTEXITCODE -ne 0){throw "Focused host production predicates failed: $LASTEXITCODE"}
}
