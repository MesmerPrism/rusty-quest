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
$browserTest = Join-Path $app "tests\js\connection-hub-browser-protocol.test.js"
$vectors = Join-Path $app "contracts\connection-hub-protocol-v1.json"
$vectorsV2 = Join-Path $app "contracts\connection-hub-protocol-v2.json"
$assets = Join-Path $app "src\main\assets\connection-hub"
$spatial = Join-Path $RepoRoot "apps\spatial-video-control-example-android"
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
    $sharedAdmissionReducer,
    $sharedAdmissionTest,
    $browserTest,
    $vectors,
    $vectorsV2,
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
$debugControl = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubDebugControlProvider.java")
$spatialManifest = Get-Content -Raw -LiteralPath (Join-Path $spatial "app\src\main\AndroidManifest.xml")
$spatialDebugManifestPath = Join-Path $spatial "app\src\debug\AndroidManifest.xml"
$spatialDebugServicePath = Join-Path $spatial "app\src\debug\java\io\github\mesmerprism\rustyquest\spatial_video_control\ConnectionHubDebugSurfaceService.kt"
$spatialClient = Get-Content -Raw -LiteralPath (Join-Path $spatial "app\src\main\java\io\github\mesmerprism\rustyquest\spatial_video_control\ConnectionHubSurfaceClient.kt")
$spatialTarget = Get-Content -Raw -LiteralPath (Join-Path $spatial "app\src\main\java\io\github\mesmerprism\rustyquest\spatial_video_control\ConnectionHubSurfaceTarget.kt")
$spatialActivity = Get-Content -Raw -LiteralPath (Join-Path $spatial "app\src\main\java\io\github\mesmerprism\rustyquest\spatial_video_control\SpatialVideoControlActivity.kt")
$buildScript = Get-Content -Raw -LiteralPath (Join-Path $RepoRoot "tools\Build-ManifoldBrokerAndroid.ps1")
$releaseScript = Get-Content -Raw -LiteralPath (Join-Path $RepoRoot "tools\Build-ConnectionHubLabsRelease.ps1")
$spatialContractPath = Join-Path $spatial "contracts\connection-hub-media-surface.v1.json"
$spatialContract = Get-Content -Raw -LiteralPath $spatialContractPath | ConvertFrom-Json
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
if ($server -match 'Access-Control-Allow-Origin' -or $server -match 'https?://[^\"]') { throw "Hub server enables CORS or ambient remote assets." }
if ($process -notmatch 'new ManifoldConnectionHubAuthority\(\)' -or $process -match 'UnavailableManifold') { throw "Connection Hub process is not wired to the real Manifold JNI authority." }
if ($buildScript -notmatch 'releaseHubService' -or
    $buildScript -notmatch 'debugHubService' -or
    $buildScript -notmatch 'android:permission="android\.permission\.DUMP"' -or
    $buildScript -notmatch '\$manifestText\.Replace\(\$releaseHubService, \$debugHubService\)') {
    throw "Connection Hub debug build does not expose only its exact service lifecycle behind DUMP."
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
    $test,
    $vectorTest,
    $vectorV2Test,
    $transportTest,
    $providerReplyTest
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
