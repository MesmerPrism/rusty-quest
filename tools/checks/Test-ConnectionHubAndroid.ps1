param([string]$RepoRoot)
$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..") }
$RepoRoot = (Resolve-Path $RepoRoot).Path
$app = Join-Path $RepoRoot "apps\manifold-broker-android"
$javaRoot = Join-Path $app "src\main\java\io\github\mesmerprism\rustymanifold\broker"
$sharedTransportRoot = Join-Path $RepoRoot "crates\rusty-quest-broker-transport\android"
$sharedCodec = Join-Path $sharedTransportRoot "io\github\mesmerprism\rustyquest\broker_transport\Rfc6455Codec.java"
$sharedAdmissionRoot = Join-Path $RepoRoot "crates\rusty-quest-broker-admission\android"
$sharedAdmissionReducer = Join-Path $sharedAdmissionRoot "io\github\mesmerprism\rustyquest\broker_admission\ConnectionHubAdmissionSessionReducer.java"
$sharedAdmissionTest = Join-Path $RepoRoot "crates\rusty-quest-broker-admission\tests\java\io\github\mesmerprism\rustyquest\broker_admission\ConnectionHubAdmissionSessionReducerTest.java"
$test = Join-Path $app "tests\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubCoreTest.java"
$vectorTest = Join-Path $app "tests\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubProtocolVectorsTest.java"
$vectorV2Test = Join-Path $app "tests\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubProtocolV2VectorsTest.java"
$transportTest = Join-Path $app "tests\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubTransportBoundsTest.java"
$providerReplyTest = Join-Path $app "tests\java\io\github\mesmerprism\rustymanifold\broker\ProviderEffectReplyRouterTest.java"
$operatorControllerTest = Join-Path $app "tests\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubOperatorControllerTest.java"
$spatialCompositionTest = Join-Path $app "tests\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubSpatialCompositionTest.java"
$runtimeEvidenceProjectionTest = Join-Path $app "tests\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubRuntimeEvidenceProjectionTest.java"
$browserTest = Join-Path $app "tests\js\connection-hub-browser-protocol.test.js"
$vectors = Join-Path $app "contracts\connection-hub-protocol-v1.json"
$vectorsV2 = Join-Path $app "contracts\connection-hub-protocol-v2.json"
$assets = Join-Path $app "src\main\assets\connection-hub"
$spatial = Join-Path $RepoRoot "apps\spatial-video-control-example-android"
$spatialCameraPanel = Join-Path $RepoRoot "apps\spatial-camera-panel-android"
$lockedPlaylistContractPath = Join-Path $app "contracts\spatial-camera-panel-locked-playlist-surface.v1.json"
$spatialCameraPanelClientPath = Join-Path $RepoRoot "fixtures\broker-clients\spatial-camera-panel.client.json"
$retiredLockedPlaylistClientPath = Join-Path $RepoRoot "fixtures\broker-clients\spatial-camera-panel-locked-playlist.client.json"
$required = @(
    (Join-Path $javaRoot "ConnectionHubProtocol.java"),
    (Join-Path $javaRoot "ConnectionHubAuthorityPort.java"),
    (Join-Path $javaRoot "ConnectionHubRuntime.java"),
    (Join-Path $javaRoot "ConnectionHubHttpServer.java"),
    (Join-Path $javaRoot "HubProviderIdentity.java"),
    (Join-Path $javaRoot "HubSurfaceDescriptor.java"),
    (Join-Path $javaRoot "HubSurfaceRegistry.java"),
    $test,
    $vectorTest,
    $vectorV2Test,
    $transportTest,
    $providerReplyTest,
    $operatorControllerTest,
    $spatialCompositionTest,
    $runtimeEvidenceProjectionTest,
    $sharedAdmissionReducer,
    $sharedAdmissionTest,
    $browserTest,
    $vectors,
    $vectorsV2,
    $lockedPlaylistContractPath,
    $spatialCameraPanelClientPath,
    (Join-Path $assets "index.html"),
    (Join-Path $assets "app.js"),
    (Join-Path $assets "protocol.js"),
    (Join-Path $assets "styles.css")
)
foreach ($path in $required) { if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing Connection Hub path: $path" } }

$service = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubStartService.java")
$activity = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubStartActivity.java")
if ($activity -notmatch 'new ScrollView\(this\)' -or
    $activity -notmatch 'scroll\.setFillViewport\(true\)' -or
    $activity -notmatch 'scroll\.addView\(layout\)') {
    throw 'Connection Hub management controls must remain inside a scrollable viewport.'
}
$binder = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubAdmissionService.java")
$stateStore = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "AndroidConnectionHubStateStore.java")
$server = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubHttpServer.java")
$runtime = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubRuntime.java")
$coreTest = Get-Content -Raw -LiteralPath $test
$protocol = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubProtocol.java")
$process = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubProcess.java")
$localBroker = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "LocalManifoldBrokerServer.java")
$debugControl = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubDebugControlProvider.java")
$operatorController = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubOperatorController.java")
$operatorProvider = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubOperatorProvider.java")
$runtimeEvidenceProjection = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubRuntimeEvidenceProjection.java")
$spatialManifest = Get-Content -Raw -LiteralPath (Join-Path $spatial "app\src\main\AndroidManifest.xml")
$spatialDebugManifestPath = Join-Path $spatial "app\src\debug\AndroidManifest.xml"
$spatialDebugServicePath = Join-Path $spatial "app\src\debug\java\io\github\mesmerprism\rustyquest\spatial_video_control\ConnectionHubDebugSurfaceService.kt"
$spatialClient = Get-Content -Raw -LiteralPath (Join-Path $spatial "app\src\main\java\io\github\mesmerprism\rustyquest\spatial_video_control\ConnectionHubSurfaceClient.kt")
$spatialTarget = Get-Content -Raw -LiteralPath (Join-Path $spatial "app\src\main\java\io\github\mesmerprism\rustyquest\spatial_video_control\ConnectionHubSurfaceTarget.kt")
$spatialActivity = Get-Content -Raw -LiteralPath (Join-Path $spatial "app\src\main\java\io\github\mesmerprism\rustyquest\spatial_video_control\SpatialVideoControlActivity.kt")
$spatialCameraPanelManifest = Get-Content -Raw -LiteralPath (Join-Path $spatialCameraPanel "app\src\main\AndroidManifest.xml")
$spatialCameraPanelClientSource = Get-Content -Raw -LiteralPath (Join-Path $spatialCameraPanel "app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\ConnectionHubSurfaceClient.kt")
$releaseManifest = Get-Content -Raw -LiteralPath (Join-Path $RepoRoot "fixtures\broker-products\connection-hub-standalone.AndroidManifest.xml")
$buildScript = Get-Content -Raw -LiteralPath (Join-Path $RepoRoot "tools\Build-ManifoldBrokerAndroid.ps1")
$releaseScript = Get-Content -Raw -LiteralPath (Join-Path $RepoRoot "tools\Build-ConnectionHubLabsRelease.ps1")
$spatialContractPath = Join-Path $spatial "contracts\connection-hub-media-surface.v1.json"
$spatialContract = Get-Content -Raw -LiteralPath $spatialContractPath | ConvertFrom-Json
$lockedPlaylistContract = Get-Content -Raw -LiteralPath $lockedPlaylistContractPath | ConvertFrom-Json
$spatialCameraPanelClient = Get-Content -Raw -LiteralPath $spatialCameraPanelClientPath | ConvertFrom-Json
$productSource = Get-Content -Raw -LiteralPath (Join-Path $RepoRoot "crates\rusty-quest-broker-product\src\lib.rs")
$nativeLock = Get-Content -Raw -LiteralPath (Join-Path $app "native\manifold-source.lock.json")
$nativeHubRoot = Join-Path $app "connection-hub-native"
$nativeHubManifest = Get-Content -Raw -LiteralPath (Join-Path $nativeHubRoot "Cargo.toml")
$nativeHub = Get-Content -Raw -LiteralPath (Join-Path $nativeHubRoot "src\connection_hub_jni.rs")
$sampleManifest = Join-Path $spatial "hub-sample-provider\src\main\AndroidManifest.xml"
$sampleProvider = Join-Path $spatial "hub-sample-provider\src\main\java\io\github\mesmerprism\rustyquest\connection_hub_sample\ConnectionHubSampleProvider.java"
$sampleProviderText = Get-Content -Raw -LiteralPath $sampleProvider
$sampleDebugManifestPath = Join-Path $spatial "hub-sample-provider\src\debug\AndroidManifest.xml"
$sampleDebugServicePath = Join-Path $spatial "hub-sample-provider\src\debug\java\io\github\mesmerprism\rustyquest\connection_hub_sample\ConnectionHubDebugSurfaceService.java"

if ($activity -match 'LocalManifoldBrokerServer\.get\(\)\.start' -or $activity -notmatch 'ACTION_START_HUB') { throw "Management Activity owns hidden server startup or lacks explicit Start." }
if ($service -notmatch 'START_STICKY' -or $service -notmatch 'ConnectionHubProcess' -or $service -notmatch 'ACTION_STOP_HUB') { throw "Foreground service does not own persistent Hub lifecycle." }
if ($binder -notmatch 'message\.sendingUid' -or $binder -notmatch 'GET_SIGNING_CERTIFICATES' -or $binder -notmatch 'MESSAGE_REGISTER_SURFACE') { throw "Binder provider API does not derive platform identity." }
if ($binder -match 'data\.getString\("admitted_client_evidence_json"') { throw "Binder accepts caller-supplied admission evidence." }
if ($binder -notmatch 'ConnectionHubRuntimeEvidenceProjection\.project\(' -or
    $binder -match 'response\s*=\s*ManifoldRuntimeAuthorityBridge\.evidence\(\)') {
    throw "Connection Hub Binder runtime evidence bypasses the bounded projection."
}
if ($runtimeEvidenceProjection -notmatch 'rusty\.quest\.broker\.runtime_evidence\.transport_projection\.v1' -or
    $runtimeEvidenceProjection -notmatch 'MAX_UTF8_BYTES\s*=\s*32\s*\*\s*1024' -or
    $runtimeEvidenceProjection -notmatch 'authority_history_included' -or
    $runtimeEvidenceProjection -notmatch 'full_evidence_retained_by_authority') {
    throw "Connection Hub runtime-evidence projection lost its schema, bound, or retention declaration."
}
foreach ($field in @(
    'registration_id',
    'registration_fingerprint_sha256',
    'session_generation',
    'authorization_correlation_id',
    'surface_registration_json')) {
    if ($spatialCameraPanelClientSource -notmatch [regex]::Escape($field)) {
        throw "Spatial Camera Panel registration envelope is missing $field."
    }
}
if ($spatialCameraPanelClientSource -notmatch 'ConnectionHubAdmissionSessionReducer' -or
    $spatialCameraPanelClientSource -match 'sendingUid|signer_sha256|caller_package') {
    throw "Spatial Camera Panel does not use the shared generation reducer or self-asserts Android identity."
}
if ($spatialCameraPanelManifest -notmatch 'BROKER_ADMISSION' -or
    $spatialCameraPanelManifest -notmatch '<queries>' -or
    $spatialCameraPanelManifest -notmatch 'io\.github\.mesmerprism\.rustymanifold\.broker') {
    throw "Spatial Camera Panel manifest lacks the exact Binder admission permission/query."
}
if ($binder -notmatch 'surface registration fingerprint mismatch' -or
    $binder -notmatch 'provider authorization correlation mismatch' -or
    $binder -notmatch 'session_generation') {
    throw "Hub Binder registration admission lost fingerprint, correlation, or generation rejection."
}
if ($operatorProvider -notmatch 'Binder\.getCallingUid\(\) != Process\.SHELL_UID' -or
    $operatorProvider -match 'force-rollover|restart-process' -or
    $operatorController -notmatch 'sent' -or
    $operatorController -notmatch 'pending' -or
    $operatorController -notmatch 'outcome_unknown') {
    throw "Published typed operator lost shell gating, bounded method scope, or receipt transitions."
}
$binderRuntimeIndex = $binder.IndexOf('ManifoldRuntimeAuthorityBridge.initialize();', [StringComparison]::Ordinal)
$binderHubIndex = $binder.IndexOf('ConnectionHubProcess.get(this);', [StringComparison]::Ordinal)
if ($binderRuntimeIndex -lt 0 -or $binderHubIndex -lt 0 -or $binderRuntimeIndex -ge $binderHubIndex) {
    throw "Binder service does not establish the Hub admission floor before provider token mutations."
}
if ($binder -notmatch 'activeHubProviders' -or
    $binder -notmatch 'registrationCommitted\.compareAndSet\(true, false\)' -or
    $binder -notmatch 'unregisterProvider\(' -or
    $binder -match 'existing == null\s*\?\s*"provider\.instance') {
    throw "Provider lifecycle does not retire the exact instance before re-registration."
}
if ($stateStore -notmatch 'AndroidKeyStore' -or $stateStore -notmatch 'AES/GCM/NoPadding' -or $stateStore -match 'putString\(KEY_STATE, value\.toString') { throw "Durable session projections are not Keystore-encrypted." }
$sharedCodecText = Get-Content -Raw -LiteralPath $sharedCodec
if ($protocol -notmatch '/v1/status' -or $protocol -notmatch '/v1/pair' -or $protocol -notmatch '/v1/socket' -or $server -notmatch 'X-Rusty-Confidentiality' -or $sharedCodecText -notmatch 'sec-websocket-version' -or $server -notmatch 'hasSameOrigin') { throw "Fixed HTTP/WebSocket protocol or posture headers missing." }
if ($server -match '/v1/socket\?session' -or $server -notmatch 'SOCKET_AUTHENTICATE_SCHEMA' -or $server -notmatch 'transport_epoch') { throw "WebSocket still uses a URL bearer or omits first-frame authentication." }
if ($server -notmatch 'SOCKET_AUTHENTICATE_SCHEMA_V2' -or
    $server -notmatch 'validateV2CommandFrame' -or
    $server -notmatch 'handleKeepalive' -or
    $protocol -notmatch 'next_external_request_sequence') {
    throw "WebSocket v2 canonical sequencing, resynchronization, or JSON keepalive is missing."
}
if ($server -notmatch 'synchronized \(runtime\)[\s\S]*synchronized \(socketSessions\)[\s\S]*runtime\.replaceTransport\(cookie\)' -or
    $server -notmatch 'isNewerTransportEpoch') {
    throw "Authority transport replacement and socket installation are not atomic."
}
if ($server -notmatch 'installBaselineAndSubscribe\(' -or
    $server -notmatch 'synchronized \(registryLock\)[\s\S]*subscription\.enqueueBaseline\(\);\s*subscription\.subscribe\(\);' -or
    $server -notmatch 'CopyOnWriteArrayList' -or
    $server -notmatch 'bindOutboundSurfaceRevision') {
    throw "Authenticated sockets lack atomic baseline subscription or a serialized surface-revision watermark."
}
if ($runtime -notmatch 'CopyOnWriteArrayList<EventSink>' -or
    $runtime -match 'synchronized \(this\) \{ sinks = new ArrayList<>\(eventSinks\); \}' -or
    $coreTest -notmatch 'testRegistryRuntimeLockOrder\(\)') {
    throw "Hub runtime event delivery can reintroduce the registry/runtime lock inversion."
}
if ($server -notmatch 'next\.bind\(new InetSocketAddress\(lanAndLoopbackBindAddress\(\), requestedPort\)\)' -or
    $server -notmatch 'InetAddress\.getByAddress\(new byte\[\] \{0, 0, 0, 0\}\)' -or
    $process -notmatch 'public static final int PORT = 8876' -or
    $process -notmatch 'int port = next\.start\(PORT\);' -or
    $process -notmatch 'listenerAddress = bindAddress' -or
    $process -notmatch '"http://" \+ host \+ ":" \+ PORT' -or
    $coreTest -notmatch 'testLanAndLoopbackListener\(\)' -or
    $coreTest -notmatch 'firstNonLoopbackIpv4Address\(\)' -or
    $localBroker -notmatch 'public static final int PORT = 8765' -or
    $localBroker -notmatch 'InetAddress\.getByName\("127\.0\.0\.1"\), PORT') {
    throw "Hub 8876 LAN-plus-loopback listener, Wi-Fi display origin, or local 8765 separation contract is missing."
}
if ($server -match 'Access-Control-Allow-Origin' -or $server -match 'https?://[^\"]') { throw "Hub server enables CORS or ambient remote assets." }
if ($process -notmatch 'new ManifoldConnectionHubAuthority\(\)' -or $process -match 'UnavailableManifold') { throw "Connection Hub process is not wired to the real Manifold JNI authority." }
if ($releaseManifest -notmatch 'android:name="\.ConnectionHubStartService"[\s\S]*android:exported="true"[\s\S]*android:permission="android\.permission\.DUMP"' -or
    $buildScript -notmatch '\$publishedHubService' -or
    $buildScript -notmatch 'Connection Hub build requires the exact DUMP-gated published foreground service' -or
    $buildScript -match 'releaseHubService|debugHubService') {
    throw "Connection Hub normal product does not expose exactly its fixed DUMP-gated foreground service."
}
if ($buildScript -notmatch 'if \(\$EnableConnectionHubDebugOperator\)[\s\S]*\$debugProvider' -or
    $buildScript -notmatch 'android:name="\.ConnectionHubDebugControlProvider"') {
    throw "Connection Hub debug build does not limit its manifest delta to the debug provider."
}
if ($buildScript.IndexOf('$generatedAndroidManifest.SelectNodes("/manifest/application/$kind")',
        [StringComparison]::Ordinal) -lt 0 -or
    $buildScript -match '\$generatedAndroidManifest\.manifest\.application\.\$kind') {
    throw "Android build-manifest projection does not safely enumerate a zero-provider release manifest."
}
if ($releaseScript.IndexOf('$aapt2 dump xmltree $builtApk --file AndroidManifest.xml',
        [StringComparison]::Ordinal) -lt 0 -or
    $releaseScript -match 'dump xmltree \$builtApk AndroidManifest\.xml') {
    throw "Connection Hub release inspection does not use the pinned aapt2 xmltree file contract."
}
$runtimeOwnerIndex = $process.IndexOf('ManifoldRuntimeAuthorityBridge.initialize();', [StringComparison]::Ordinal)
$hubOwnerIndex = $process.IndexOf('new ManifoldConnectionHubAuthority()', [StringComparison]::Ordinal)
if ($runtimeOwnerIndex -lt 0 -or $hubOwnerIndex -lt 0 -or $runtimeOwnerIndex -ge $hubOwnerIndex) {
    throw "Connection Hub process does not initialize the shared Manifold admission owner before the Hub authority."
}
if ($process -notmatch 'provider_effect_binding\.v1' -or
    $process -notmatch 'PROVIDER_EFFECT_RECEIPT_DEADLINE_MS' -or
    $process -notmatch 'providerEffectResponseMessenger' -or
    $process -notmatch 'cancelProvider\(' -or
    $server -notmatch 'command_receipt_enqueued' -or
    $server -notmatch 'command_receipt_written' -or
    $server -notmatch 'command_receipt_rejected' -or
    $server -notmatch 'command_receipt_enqueue_failed') {
    throw "Provider effect receipts are not process-owned, exact, lifecycle bounded, and observable."
}
if ($nativeLock -notmatch 'd9d060f8c67199135a4c3e0a699ca408f6c64095' -or
    $nativeLock -notmatch '23126eb8b6d0127dfbfa7b968c95ea8b8c7174be' -or
    $nativeHubManifest -notmatch '(?m)^\[workspace\]$' -or
    $nativeHubManifest -notmatch 'name = "rusty_quest_manifold_broker_authority"' -or
    $buildScript -notmatch 'isolated Connection Hub native' -or
    $buildScript -notmatch 'Connection Hub native dependency path does not equal the validated Manifold source root' -or
    $nativeHub -notmatch '\.owner\(\)' -or
    $nativeHub -notmatch 'EMPTY_TYPED_PARAMS_SCHEMA_SHA256' -or
    $nativeHub -notmatch 'authority_epoch' -or
    $nativeHub -notmatch '"policy": retained\.config\.policy' -or
    $nativeHub -notmatch 'restart product or policy substitution rejected' -or
    $nativeHub -notmatch 'provider_admission_diagnostic' -or
    $nativeHub -notmatch 'authorize_use_not_newer_than_floor') {
    throw "Connection Hub JNI is not bound to the sealed v3 Manifold owner/epoch/typed-schema authority."
}
if (Test-Path -LiteralPath (Join-Path $javaRoot "UnavailableManifoldConnectionHubAuthority.java")) { throw "Fail-closed development authority stub remains in product source." }
if ($buildScript -notmatch '\$connectionHubSelected' -or $buildScript -notmatch 'connectionHubPackagedAssets' -or $buildScript -notmatch 'ConnectionHub\*\.java' -or $productSource -notmatch '\.ConnectionHubStartActivity' -or $productSource -notmatch '\.BrokerStartActivity') { throw "Product lock does not gate Hub classes/assets/components while preserving legacy components." }
if ($buildScript -notmatch 'connection-hub-typed-params-empty\.schema\.json' -or
    $buildScript -notmatch '7eedc1ccca80b83dbd121d1e4bae4f6a6c9c1561e1a08d6d5919c668d5406a51') {
    throw "Build does not package and bind the exact empty typed-parameter schema bytes."
}
if ([string]$spatialContract.canonical_contract_sha256 -cne 'sha256:6cc91c34f46b4da96de9a5f817cdb7ee371e5ebbc7789b39bc53700e211725b1' -or
    $buildScript -notmatch 'Read-ValidatedSpatialVideoHubContract' -or
    $buildScript -match '099dab2723521655df0617b22a14f3a8021ecf75fc952587d619b944e8019e60' -or
    $buildScript -notmatch '\$spatialVideoHubContract\.canonical_sha256') {
    throw "Hub packaging does not derive the exact reviewed spatial-player surface grant from its source contract."
}
$expectedLockedCommands = @(
    [ordered]@{ command = 'command.spatial_camera_panel.locked_playlist.next'; display_label = 'Next'; required_controller_capability = 'capability.spatial_camera_panel.locked_playlist.next' },
    [ordered]@{ command = 'command.spatial_camera_panel.locked_playlist.pause'; display_label = 'Pause'; required_controller_capability = 'capability.spatial_camera_panel.locked_playlist.pause' },
    [ordered]@{ command = 'command.spatial_camera_panel.locked_playlist.previous'; display_label = 'Previous'; required_controller_capability = 'capability.spatial_camera_panel.locked_playlist.previous' },
    [ordered]@{ command = 'command.spatial_camera_panel.locked_playlist.resume'; display_label = 'Resume'; required_controller_capability = 'capability.spatial_camera_panel.locked_playlist.resume' }
)
$expectedLockedStateKeys = @(
    'active_index', 'active_label', 'item_count', 'paused', 'phase',
    'playlist_title', 'progress', 'revision', 'running'
)
if ([string]$lockedPlaylistContract.'$schema' -cne 'rusty.quest.connection_hub.locked_playlist_surface_contract.v1' -or
    [string]$lockedPlaylistContract.canonical_version -cne 'locked-playlist-v1' -or
    [string]$lockedPlaylistContract.canonical_contract_sha256 -cne 'sha256:9e4c3794d8edbe123cd30cd3fc6abf0e14e71356c97fca2020334c5581b15e26' -or
    [string]$lockedPlaylistContract.runtime_surface_contract_sha256 -cne 'sha256:3eafe0fb1ff859a7848dfba8cf64a6eb532f98a39d0953fd628594792ca18d6e' -or
    [string]$lockedPlaylistContract.provider_id -cne 'provider.quest.spatial-camera-panel-locked-playlist' -or
    [string]$lockedPlaylistContract.surface_id -cne 'surface.spatial_camera_panel.locked_playlist' -or
    [string]$lockedPlaylistContract.typed_params_schema -cne 'rusty.manifold.connection_hub.typed_params.empty.v1' -or
    [string]$lockedPlaylistContract.availability -cne 'effective_locked_playlist_only' -or
    [string]$lockedPlaylistContract.lifecycle -cne 'unregister_when_unavailable' -or
    [string]$lockedPlaylistContract.direct_item_activation -cne 'unsupported-alpha4-empty-args' -or
    [string]$lockedPlaylistContract.ordered_item_list -cne 'unsupported-alpha4-scalar-state' -or
    [int]$lockedPlaylistContract.max_state_keys -ne 16 -or
    [int]$lockedPlaylistContract.max_state_bytes -ne 4096 -or
    [int]$lockedPlaylistContract.max_string_bytes -ne 256 -or
    ((@($lockedPlaylistContract.commands | ForEach-Object { [string]$_.command })) -join "`n") -cne ((@($expectedLockedCommands | ForEach-Object { [string]$_.command })) -join "`n") -or
    ((@($lockedPlaylistContract.state_keys | ForEach-Object { [string]$_ })) -join "`n") -cne ($expectedLockedStateKeys -join "`n")) {
    throw "Spatial Camera Panel locked-playlist contract is outside the exact empty-args scalar boundary."
}
$lockedCanonical = "locked-playlist-v1`nprovider|$($lockedPlaylistContract.provider_id)`nsurface|$($lockedPlaylistContract.surface_id)`nlabel|$($lockedPlaylistContract.display_label)`ndescription|$($lockedPlaylistContract.description)`ntyped_params|$($lockedPlaylistContract.typed_params_schema)`navailability|$($lockedPlaylistContract.availability)`nlifecycle|$($lockedPlaylistContract.lifecycle)`ndirect_item_activation|$($lockedPlaylistContract.direct_item_activation)`nordered_item_list|$($lockedPlaylistContract.ordered_item_list)`nmax_state_keys|$($lockedPlaylistContract.max_state_keys)`nmax_state_bytes|$($lockedPlaylistContract.max_state_bytes)`nmax_string_bytes|$($lockedPlaylistContract.max_string_bytes)`n"
$lockedRuntimeCanonical = "v1`n$($lockedPlaylistContract.surface_id)`n$($lockedPlaylistContract.display_label)`n$($lockedPlaylistContract.description)`n"
for ($index = 0; $index -lt $expectedLockedCommands.Count; $index += 1) {
    $actual = @($lockedPlaylistContract.commands)[$index]
    $expected = $expectedLockedCommands[$index]
    if ([string]$actual.display_label -cne [string]$expected.display_label -or
        [string]$actual.required_controller_capability -cne [string]$expected.required_controller_capability) {
        throw "Spatial Camera Panel locked-playlist command descriptor $index changed."
    }
    $lockedCanonical += "command|$($actual.command)|$($actual.display_label)|$($actual.required_controller_capability)`n"
    $lockedRuntimeCanonical += "$($actual.command)|$($actual.display_label)|$($actual.required_controller_capability)`n"
}
foreach ($stateKey in $expectedLockedStateKeys) { $lockedCanonical += "state|$stateKey`n" }
$lockedCanonicalSha256 = "sha256:" + [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($lockedCanonical))
).ToLowerInvariant()
if ($lockedCanonicalSha256 -cne [string]$lockedPlaylistContract.canonical_contract_sha256) {
    throw "Spatial Camera Panel locked-playlist canonical contract hash is invalid."
}
$lockedRuntimeSha256 = "sha256:" + [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($lockedRuntimeCanonical))
).ToLowerInvariant()
if ($lockedRuntimeSha256 -cne [string]$lockedPlaylistContract.runtime_surface_contract_sha256) {
    throw "Spatial Camera Panel locked-playlist runtime surface hash is invalid."
}
$lockedPlaylistContractJson = $lockedPlaylistContract | ConvertTo-Json -Depth 16 -Compress
foreach ($forbidden in @('select_index', 'profile_id', 'profile_path', 'ordered_items')) {
    if ($lockedPlaylistContractJson -match [regex]::Escape($forbidden)) {
        throw "Spatial Camera Panel locked-playlist contract exposes forbidden compatibility field: $forbidden"
    }
}
if ([string]$spatialCameraPanelClient.schema -cne 'rusty.quest.broker_client_spec.v1' -or
    [string]$spatialCameraPanelClient.client_id -cne 'client.quest.spatial-camera-panel' -or
    [string]$spatialCameraPanelClient.package_name -cne 'io.github.mesmerprism.rustyquest.spatial_camera_panel' -or
    [string]$spatialCameraPanelClient.feature_lock_id -cne 'lock.broker-client.spatial-camera-panel.v1' -or
    [string]$spatialCameraPanelClient.marker_namespace -cne 'RUSTY_QUEST_SPATIAL_BROKER_CLIENT' -or
    ((@($spatialCameraPanelClient.contract_families | ForEach-Object { [string]$_ })) -join "`n") -cne
        "rusty.manifold.media.session_descriptor.v1`nrusty.manifold.peer.session_descriptor.v1" -or
    ((@($spatialCameraPanelClient.capabilities | ForEach-Object { [string]$_ })) -join "`n") -cne
        "capability.command.media.session.start`ncapability.command.media.session.stop`ncapability.command.session.list`ncapability.connection_hub.provider.register`ncapability.media.session.observe`ncapability.peer.session.observe`ncapability.sink.spatial-sdk" -or
    @($spatialCameraPanelClient.adapter_permissions).Count -ne 1 -or
    [string]@($spatialCameraPanelClient.adapter_permissions)[0] -cne 'io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION' -or
    @($spatialCameraPanelClient.runtime_properties).Count -ne 0 -or
    @($spatialCameraPanelClient.application_defaults).Count -ne 0) {
    throw "Spatial Camera Panel canonical client lock does not close over its selected-product Hub provider capability."
}
if (Test-Path -LiteralPath $retiredLockedPlaylistClientPath) {
    throw "The duplicate same-package locked-playlist client lock must remain retired."
}
foreach ($token in @(
    'Read-ValidatedSpatialCameraPanelLockedPlaylistHubContract',
    'Assert-UniqueAndroidAdmissionSubjects',
    'grant.quest.spatial-camera-panel',
    'provider.quest.spatial-camera-panel-locked-playlist',
    '$lockedPlaylistHubContract.runtime_sha256',
    '$lockedPlaylistCommands'
)) {
    if ($buildScript -notmatch [regex]::Escape($token)) {
        throw "Hub packaging is missing the locked-playlist enrollment token: $token"
    }
}
if ($buildScript -match 'grant\.quest\.spatial-camera-panel-locked-playlist' -or
    $buildScript -match 'spatial-camera-panel-locked-playlist\.client\.json') {
    throw "Hub packaging still admits the ambiguous second grant for the Spatial Camera Panel Android subject."
}
$parseTokens = $null
$parseErrors = $null
$buildAst = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $RepoRoot 'tools\Build-ManifoldBrokerAndroid.ps1'),
    [ref]$parseTokens,
    [ref]$parseErrors
)
if (@($parseErrors).Count -ne 0) { throw "Hub build script does not parse for duplicate-subject negative testing." }
$guardAst = $buildAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -ceq 'Assert-UniqueAndroidAdmissionSubjects'
}, $true)
if ($null -eq $guardAst) { throw "Hub build script duplicate-subject guard is unavailable." }
. ([scriptblock]::Create($guardAst.Extent.Text))
$duplicateBindings = @(
    [pscustomobject]@{ input = [pscustomobject]@{ lock = [pscustomobject]@{ package_name = 'io.example.same' } } },
    [pscustomobject]@{ input = [pscustomobject]@{ lock = [pscustomobject]@{ package_name = 'io.example.same' } } }
)
$duplicateRejected = $false
try {
    Assert-UniqueAndroidAdmissionSubjects -ClientLockInputs $duplicateBindings -SigningFingerprint 'sha256:test'
} catch {
    $duplicateRejected = $_.Exception.Message -match 'Duplicate Android package\+signer admission subject is ambiguous'
}
if (-not $duplicateRejected) { throw "Hub build guard did not fail closed on a duplicate package+signer subject." }
if ((Get-FileHash -LiteralPath $vectors -Algorithm SHA256).Hash.ToLowerInvariant() -ne
        'fa00d34511b2ee5576eebdd815e58ae032e37b10c209e41289cfd876c78c9c78' -or
    $buildScript -notmatch 'connection-hub-protocol-v1\.json' -or
    $buildScript -notmatch 'connection-hub-protocol-v2\.json' -or
    $buildScript -notmatch 'connection_hub_protocol_v2_sha256') {
    throw "Build does not preserve and package both exact Connection Hub protocol vectors."
}
if ($buildScript -notmatch 'rusty\.manifold\.connection_hub\.policy\.v3' -or
    $buildScript -notmatch 'authenticated_activity_controller_ttl_ms' -or
    $buildScript -notmatch 'authenticated_activity_session_ttl_ms' -or
    $buildScript -notmatch 'debug_test_hooks_enabled' -or
    $debugControl -notmatch 'force-rollover' -or
    $debugControl -notmatch 'forceHistoryRolloverForDebug') {
    throw "Manifold v3 activity deadlines or debug-only rollover validation hook is missing."
}
if ($spatialManifest -notmatch 'BROKER_ADMISSION' -or $spatialManifest -notmatch 'io\.github\.mesmerprism\.rustymanifold\.broker') { throw "Spatial provider does not bind the exact signature-scoped Broker." }
if ($spatialClient -notmatch 'MESSAGE_REGISTER_SURFACE' -or $spatialClient -notmatch 'MESSAGE_UNREGISTER_SURFACE' -or $spatialClient -match 'admitted_client_evidence_json') { throw "Spatial provider lifecycle or admission boundary is incorrect." }
if ($spatialClient -notmatch 'ConnectionHubAdmissionSessionReducer' -or
    $spatialClient -notmatch 'onBindingDied' -or
    $spatialClient -notmatch 'onNullBinding' -or
    $spatialClient -notmatch 'linkToDeath' -or
    $spatialClient -notmatch 'correlation_id' -or
    $spatialClient -notmatch 'session_generation') {
    throw "Spatial provider does not use the generation-fenced shared Binder admission reducer."
}
if ($binder -notmatch 'correlation_id' -or
    $binder -notmatch 'broker_epoch_id' -or
    $binder -notmatch 'registration_id' -or
    $binder -notmatch 'registration_fingerprint_sha256' -or
    $binder -notmatch 'authorization_correlation_id' -or
    $spatialClient -notmatch 'authorization_correlation_id' -or
    $binder -notmatch 'surface_registration_equivalent') {
    throw "Binder broker does not correlate admission stages or implement equivalent registration."
}
if ($buildScript -notmatch 'sharedBrokerAdmissionJavaRoot' -or
    (Get-Content -Raw -LiteralPath (Join-Path $spatial 'app\build.gradle.kts')) -notmatch 'rusty-quest-broker-admission/android') {
    throw "Both Android placements do not compile the shared Binder admission source set."
}
if ($spatialClient -notmatch 'command_received_' -or
    $spatialClient -notmatch 'effect_response_sent_' -or
    $spatialClient -notmatch 'val effectReplyTo = message\.replyTo' -or
    $spatialClient -match 'sendEffectResponse\(message' -or
    $spatialClient -match 'request\.replyTo\?\.send') {
    throw "Spatial provider command/effect response is not positively observed or retains a recyclable Message/missing reply channel across its asynchronous probe."
}
foreach ($providerTarget in @($spatialTarget, $sampleProviderText)) {
    if ($providerTarget -notmatch 'rusty\.manifold\.connection_hub\.receipt\.v3' -or
        $providerTarget -notmatch 'surface-instance' -or
        $providerTarget -notmatch 'external_request_sha256' -or
        $providerTarget -notmatch 'deriveAuthorityRequestId' -or
        $providerTarget -notmatch 'typed_params_schema_sha256') {
        throw "Provider effect boundary does not bind the v3 authority receipt, internal surface incarnation, typed schema, and derived request id."
    }
}
if ($spatialActivity -notmatch 'hubSurface.*\.start' -or $spatialActivity -notmatch 'hubSurface\?\.close') { throw "Spatial app does not register/unregister its Hub surface with lifecycle." }
if (-not (Test-Path -LiteralPath $sampleProvider -PathType Leaf) -or $sampleProviderText -notmatch 'surface\.connection_hub_sample\.toggle') { throw "Distinct second-package Hub provider is missing." }
foreach ($debugPath in @($spatialDebugManifestPath, $spatialDebugServicePath, $sampleDebugManifestPath, $sampleDebugServicePath)) {
    if (-not (Test-Path -LiteralPath $debugPath -PathType Leaf)) { throw "Off-head debug provider seam is missing: $debugPath" }
}
$spatialDebugManifest = Get-Content -Raw -LiteralPath $spatialDebugManifestPath
$spatialDebugService = Get-Content -Raw -LiteralPath $spatialDebugServicePath
$sampleDebugManifest = Get-Content -Raw -LiteralPath $sampleDebugManifestPath
$sampleDebugService = Get-Content -Raw -LiteralPath $sampleDebugServicePath
foreach ($manifest in @($spatialDebugManifest, $sampleDebugManifest)) {
    if ($manifest -notmatch 'ConnectionHubDebugSurfaceService' -or
        $manifest -notmatch 'android:exported="true"' -or
        $manifest -notmatch 'android:permission="android\.permission\.DUMP"' -or
        $manifest -notmatch 'android:foregroundServiceType="dataSync"' -or
        $manifest -notmatch 'android:stopWithTask="false"') {
        throw "Off-head provider service is not exact, DUMP-gated, and foreground-only."
    }
}
foreach ($serviceSource in @($spatialDebugService, $sampleDebugService)) {
    if ($serviceSource -notmatch 'START_CONNECTION_HUB_DEBUG_SURFACE' -or
        $serviceSource -notmatch 'STOP_CONNECTION_HUB_DEBUG_SURFACE' -or
        $serviceSource -notmatch 'startForeground' -or $serviceSource -match 'startActivity') {
        throw "Off-head provider service does not expose only the fixed surface lifecycle."
    }
}
if ($spatialManifest -match 'ConnectionHubDebugSurfaceService' -or
    (Get-Content -Raw -LiteralPath $sampleManifest) -match 'ConnectionHubDebugSurfaceService') {
    throw "Debug provider service escaped into a release-source manifest."
}

$node = (Get-Command node -ErrorAction Stop).Source
& $node --check (Join-Path $assets "app.js")
if ($LASTEXITCODE -ne 0) { throw "Connection Hub browser JS syntax failed." }
& $node --check (Join-Path $assets "protocol.js")
if ($LASTEXITCODE -ne 0) { throw "Connection Hub browser protocol JS syntax failed." }
& $node $browserTest $vectors $vectorsV2 (Join-Path $assets "protocol.js") (Join-Path $assets "app.js")
if ($LASTEXITCODE -ne 0) { throw "Connection Hub browser protocol conformance failed." }
$html = Get-Content -Raw -LiteralPath (Join-Path $assets "index.html")
if ($html.IndexOf('<link rel="icon" href="#">', [StringComparison]::Ordinal) -lt 0) {
    throw "Browser page does not declare its local fragment favicon."
}
if ($html -match '<script[^>]+src="https?://' -or $html -match '<link[^>]+href="https?://') { throw "Browser page includes remote assets." }
foreach ($token in @('surface_snapshot','surface_available','surface_removed','surface_state','command_receipt','surface.command','confidentiality')) {
    $combined = "$html`n$(Get-Content -Raw -LiteralPath (Join-Path $assets 'app.js'))`n$server"
    if ($combined -notmatch [regex]::Escape($token)) { throw "Browser/server contract missing token: $token" }
}

$jsonJar = Get-ChildItem -Path (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\org.json\json") -Recurse -Filter "json-*.jar" | Sort-Object FullName -Descending | Select-Object -First 1
if ($null -eq $jsonJar) { throw "Host org.json test dependency is unavailable." }
$out = Join-Path $RepoRoot "target\connection-hub-host-tests"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @(Get-ChildItem -Path $sharedTransportRoot -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }) + @(
    (Join-Path $javaRoot "ConnectionHubProtocol.java"),
    (Join-Path $javaRoot "ConnectionHubAuthorityPort.java"),
    (Join-Path $javaRoot "ConnectionHubStateStore.java"),
    (Join-Path $javaRoot "ConnectionHubRuntime.java"),
    (Join-Path $javaRoot "ConnectionHubHttpServer.java"),
    (Join-Path $javaRoot "HubProviderIdentity.java"),
    (Join-Path $javaRoot "HubSurfaceDescriptor.java"),
    (Join-Path $javaRoot "HubSurfaceRegistry.java"),
    (Join-Path $javaRoot "ProviderEffectReplyRouter.java"),
    (Join-Path $javaRoot "ConnectionHubOperatorController.java"),
    (Join-Path $javaRoot "ConnectionHubRuntimeEvidenceProjection.java"),
    $test,
    $vectorTest,
    $vectorV2Test,
    $transportTest,
    $providerReplyTest,
    $operatorControllerTest,
    $spatialCompositionTest,
    $runtimeEvidenceProjectionTest
) + @(Get-ChildItem -Path $sharedAdmissionRoot -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }) + @($sharedAdmissionTest)
$javac = (Get-Command javac -ErrorAction Stop).Source
$java = (Get-Command java -ErrorAction Stop).Source
& $javac -encoding UTF-8 -source 8 -target 8 -cp $jsonJar.FullName -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "Connection Hub host Java compile failed." }
& $java -cp "$out;$($jsonJar.FullName)" io.github.mesmerprism.rustymanifold.broker.ConnectionHubProtocolVectorsTest $vectors
if ($LASTEXITCODE -ne 0) { throw "Connection Hub protocol-vector tests failed." }
& $java -cp "$out;$($jsonJar.FullName)" io.github.mesmerprism.rustymanifold.broker.ConnectionHubProtocolV2VectorsTest $vectors $vectorsV2
if ($LASTEXITCODE -ne 0) { throw "Connection Hub v2 protocol-vector tests failed." }
& $java -cp "$out;$($jsonJar.FullName)" io.github.mesmerprism.rustymanifold.broker.ConnectionHubTransportBoundsTest
if ($LASTEXITCODE -ne 0) { throw "Connection Hub transport-bound tests failed." }
& $java -cp "$out;$($jsonJar.FullName)" io.github.mesmerprism.rustymanifold.broker.ProviderEffectReplyRouterTest
if ($LASTEXITCODE -ne 0) { throw "Connection Hub provider-effect reply-router tests failed." }
& $java -cp "$out;$($jsonJar.FullName)" io.github.mesmerprism.rustymanifold.broker.ConnectionHubOperatorControllerTest
if ($LASTEXITCODE -ne 0) { throw "Connection Hub operator controller tests failed." }
& $java -cp "$out;$($jsonJar.FullName)" io.github.mesmerprism.rustymanifold.broker.ConnectionHubSpatialCompositionTest
if ($LASTEXITCODE -ne 0) { throw "Connection Hub Spatial composition tests failed." }
& $java -cp "$out;$($jsonJar.FullName)" io.github.mesmerprism.rustymanifold.broker.ConnectionHubRuntimeEvidenceProjectionTest
if ($LASTEXITCODE -ne 0) { throw "Connection Hub runtime-evidence projection tests failed." }
& $java -cp "$out;$($jsonJar.FullName)" io.github.mesmerprism.rustymanifold.broker.ConnectionHubCoreTest $vectors
if ($LASTEXITCODE -ne 0) { throw "Connection Hub host tests failed." }
& $java -cp "$out;$($jsonJar.FullName)" io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducerTest
if ($LASTEXITCODE -ne 0) { throw "Connection Hub admission-session reducer tests failed." }

& cargo test --locked --manifest-path (Join-Path $nativeHubRoot "Cargo.toml")
if ($LASTEXITCODE -ne 0) { throw "Isolated Connection Hub native tests failed." }

$androidJar = Join-Path $env:ANDROID_HOME "platforms\android-35\android.jar"
if (-not (Test-Path -LiteralPath $androidJar -PathType Leaf)) {
    $androidJar = Get-ChildItem -Path (Join-Path $env:ANDROID_HOME "platforms") -Recurse -Filter android.jar |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if ([string]::IsNullOrWhiteSpace($androidJar)) { throw "Android platform jar unavailable." }
$generatedDir = Join-Path $out "generated\io\github\mesmerprism\rustymanifold\broker"
New-Item -ItemType Directory -Force -Path $generatedDir | Out-Null
[IO.File]::WriteAllText((Join-Path $generatedDir "GeneratedBrokerProductConfig.java"), @'
package io.github.mesmerprism.rustymanifold.broker;
final class GeneratedBrokerProductConfig {
 static final boolean CAMERA_MEDIA_ENABLED=false;
 static final boolean CONNECTION_HUB_ENABLED=true;
}
'@)
[IO.File]::WriteAllText((Join-Path $generatedDir "GeneratedBrokerRuntimeConfig.java"), @'
package io.github.mesmerprism.rustymanifold.broker;
final class GeneratedBrokerRuntimeConfig { static final String JSON="{}"; static final String SHA256="test"; }
'@)
[IO.File]::WriteAllText((Join-Path $generatedDir "GeneratedConnectionHubConfig.java"), @'
package io.github.mesmerprism.rustymanifold.broker;
final class GeneratedConnectionHubConfig { static final String JSON="{}"; static final boolean DEBUG_OPERATOR_ENABLED=true; }
'@)
$androidOut = Join-Path $out "android-classes"
New-Item -ItemType Directory -Force -Path $androidOut | Out-Null
$androidSources = @(Get-ChildItem -Path $sharedTransportRoot -Recurse -Filter *.java | ForEach-Object { $_.FullName }) +
    @(Get-ChildItem -Path $sharedAdmissionRoot -Recurse -Filter *.java | ForEach-Object { $_.FullName }) +
    @(Get-ChildItem -Path $javaRoot -Filter *.java | ForEach-Object { $_.FullName }) +
    @(Get-ChildItem -Path $generatedDir -Filter *.java | ForEach-Object { $_.FullName })
& $javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar -d $androidOut $androidSources
if ($LASTEXITCODE -ne 0) { throw "Connection Hub complete Android Java source compile failed." }
& pwsh -NoProfile -File (Join-Path $RepoRoot "tools\checks\Test-ConnectionHubOperator.ps1") -RepoRoot $RepoRoot
if ($LASTEXITCODE -ne 0) { throw "Connection Hub operator CLI validation failed." }
Write-Output "Rusty Connection Hub Android source and host validation passed"
