param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..") }
$root = (Resolve-Path -LiteralPath $RepoRoot).Path
$fixtureRoot = Join-Path $root "fixtures\broker-authority"

function Read-Json([string]$Path) {
    Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

foreach ($suffix in @("applied", "unknown-rejected", "unleased-rejected")) {
    $standalone = Read-Json (Join-Path $fixtureRoot "standalone-$suffix.response.json")
    $embedded = Read-Json (Join-Path $fixtureRoot "embedded-$suffix.response.json")
    foreach ($response in @($standalone, $embedded)) {
        if ($response.'$schema' -ne "rusty.quest.broker.authority_response.v1") { throw "Authority response schema drifted for '$suffix'." }
        if ($response.local_acceptance_rules -ne $false) { throw "Quest bridge gained local acceptance rules for '$suffix'." }
        if ($response.decision_owner_id -ne "module.runtime.host") { throw "Quest bridge authority owner drifted for '$suffix'." }
        if ($response.adapter_receipt.authority_owner_id -ne "module.runtime.host") { throw "Adapter receipt authority owner drifted for '$suffix'." }
    }
    $standaloneDispatch = $standalone.adapter_receipt.dispatch | ConvertTo-Json -Depth 20 -Compress
    $embeddedDispatch = $embedded.adapter_receipt.dispatch | ConvertTo-Json -Depth 20 -Compress
    $standaloneApplication = $standalone.adapter_receipt.application | ConvertTo-Json -Depth 20 -Compress
    $embeddedApplication = $embedded.adapter_receipt.application | ConvertTo-Json -Depth 20 -Compress
    if ($standaloneDispatch -ne $embeddedDispatch -or $standaloneApplication -ne $embeddedApplication) {
        throw "Standalone/embedded Runtime Host decision parity drifted for '$suffix'."
    }
}

$standaloneJavaPath = Join-Path $root "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\ManifoldRuntimeAuthorityBridge.java"
$embeddedJavaPath = Join-Path $root "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\EmbeddedManifoldRuntimeAuthorityBridge.java"
$standaloneRustPath = Join-Path $root "apps\manifold-broker-android\native\src\admission_jni.rs"
$embeddedRustPath = Join-Path $root "apps\native-renderer-android\native\src\embedded_manifold_runtime_authority_jni.rs"
$authorityRustPath = Join-Path $root "crates\rusty-quest-broker-authority\src\runtime.rs"
$clientRustPath = Join-Path $root "crates\rusty-quest-broker-client\src\lib.rs"
$standaloneServerPath = Join-Path $root "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\LocalManifoldBrokerServer.java"
$genericMediaPath = Join-Path $root "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\GenericMediaSessionPlatformAdapter.java"
$embeddedServerPath = Join-Path $root "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\EmbeddedManifoldBrokerServer.java"
$embeddedLifecyclePath = Join-Path $root "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\EmbeddedManifoldAdmissionLifecycle.java"
$embeddedIdentityPath = Join-Path $root "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\EmbeddedCallerIdentityResolver.java"
$embeddedIdentityTestPath = Join-Path $root "apps\native-renderer-android\tests\java\io\github\mesmerprism\rustyquest\native_renderer\EmbeddedCallerIdentityResolverTest.java"
$embeddedWebSocketPolicyPath = Join-Path $root "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\EmbeddedWebSocketAuthorityPolicy.java"
$embeddedWebSocketPolicyTestPath = Join-Path $root "apps\native-renderer-android\tests\java\io\github\mesmerprism\rustyquest\native_renderer\EmbeddedWebSocketAuthorityPolicyTest.java"
$buildPath = Join-Path $root "tools\Build-ManifoldBrokerAndroid.ps1"
$embeddedBuildPath = Join-Path $root "tools\Build-NativeRendererAndroid.ps1"
foreach ($path in @($standaloneJavaPath, $embeddedJavaPath, $standaloneRustPath, $embeddedRustPath, $authorityRustPath, $clientRustPath, $standaloneServerPath, $genericMediaPath, $embeddedServerPath, $embeddedLifecyclePath, $embeddedIdentityPath, $embeddedIdentityTestPath, $embeddedWebSocketPolicyPath, $embeddedWebSocketPolicyTestPath, $buildPath, $embeddedBuildPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing broker authority bridge: $path" }
}

$javaSources = @(
    (Get-Content -Raw -LiteralPath $standaloneJavaPath)
    (Get-Content -Raw -LiteralPath $embeddedJavaPath)
)
foreach ($java in $javaSources) {
    foreach ($token in @("nativeInitialize", "nativeMutate", "nativeCompleteMediaAction", "expectedConfigSha256", "rusty.quest.broker.runtime_initialize_status.v1", "rusty.quest.broker.server_mutation_response.v1", "rusty.quest.broker.media_completion_response.v1", "module.runtime.host", "local_acceptance_rules")) {
        if ($java -notmatch [regex]::Escape($token)) { throw "Java authority bridge is missing '$token'." }
    }
    if ($java -match 'command\.[a-z]' -or $java -match 'rejection_reason' -or $java -match 'nativeEvaluate') {
        throw "Java authority bridge contains command or rejection policy."
    }
}

$standaloneRust = Get-Content -Raw -LiteralPath $standaloneRustPath
$embeddedRust = Get-Content -Raw -LiteralPath $embeddedRustPath
foreach ($rust in @($standaloneRust, $embeddedRust)) {
    foreach ($token in @("QuestBrokerRuntimeProvider", "execute_admission_json", "handle_server_mutation_json", "complete_media_action_json", '"token_id"')) {
        if ($rust -notmatch [regex]::Escape($token)) {
            throw "JNI authority entrypoint is missing stateful runtime token '$token'."
        }
    }
    if ($rust -match 'evaluate_authority_json') {
        throw "A real JNI entrypoint still exposes the stateless authority evaluator."
    }
}
foreach ($token in @("apply_media_platform_completion_json", "nativeApplyMediaCompletion")) {
    if ($standaloneRust -match [regex]::Escape($token) -or $embeddedRust -match [regex]::Escape($token)) {
        throw "A generic JNI route can forge app-local media owner completion: '$token'."
    }
}

$authorityRust = Get-Content -Raw -LiteralPath $authorityRustPath
foreach ($token in @(
    "ManifoldBrokerRuntime",
    "QuestBrokerRuntimeProvider",
    "existing_authority_preserved",
    "RebindConfigMismatch",
    "ProviderEpochMismatch",
    "AdmissionTokenMismatch",
    "QuestBrokerPackagedAuthorityBinding",
    "PackagedConfigDigestMismatch",
    "GrantClosureMismatch",
    "EffectParamsDigestMismatch",
    "QuestBrokerMediaSessionProductBinding",
    "QuestBrokerMediaCompletionResponse",
    "complete_media_action_json",
    "MediaStreamSessionProductRuntime",
    "MediaProductFeatureMismatch",
    "platform_effect_completed",
    "platform_prepare_error",
    "effect_params",
    "params_digest",
    "token_id",
    "handle_server_mutation")) {
    if ($authorityRust -notmatch [regex]::Escape($token)) {
        throw "Stateful Quest broker authority is missing '$token'."
    }
}

$clientRust = Get-Content -Raw -LiteralPath $clientRustPath
foreach ($token in @("BrokerMutationAdmissionBinding", '"token_id"', '"admission_use_request_id"', '"params_digest"', "MAX_TYPED_PARAMS_CANONICAL_BYTES")) {
    if ($clientRust -notmatch [regex]::Escape($token)) {
        throw "Broker client mutation builder is missing '$token'."
    }
}

$standaloneServer = Get-Content -Raw -LiteralPath $standaloneServerPath
$genericMedia = Get-Content -Raw -LiteralPath $genericMediaPath
$embeddedServer = Get-Content -Raw -LiteralPath $embeddedServerPath
$embeddedWebSocketPolicy = Get-Content -Raw -LiteralPath $embeddedWebSocketPolicyPath
if ($standaloneServer -notmatch 'ManifoldRuntimeAuthorityBridge\.evaluateMutation') {
    throw "Standalone WebSocket entrypoint does not call the Rust mutation gate."
}
if ($embeddedServer -match 'lifecycle\.mutate' -or
    $embeddedServer -match 'admissionLifecycle\s*=' -or
    ($embeddedServer + $embeddedWebSocketPolicy) -notmatch 'embedded_websocket_read_only' -or
    $embeddedServer -notmatch 'signature_scoped_binder_or_direct_in_process') {
    throw "Embedded diagnostic WebSocket regained network-to-self mutation authority."
}
foreach ($token in @("reportPreparedAction", "awaiting_product_owner_completions", "completion_synthesized", "remote_camera_compatibility")) {
    if ($genericMedia -notmatch [regex]::Escape($token)) {
        throw "Generic media platform adapter is missing '$token'."
    }
}
if ($genericMedia -match 'RemoteCameraSessionRuntime' -or
    $standaloneServer -match 'remote_camera_compatibility"\.equals\(platformEffect\)\s*\|\|\s*"media_session') {
    throw "Generic media still routes through the remote-camera compatibility runtime."
}
foreach ($server in @($standaloneServer, $embeddedServer)) {
    if ($server -match 'put\("accepted"' -or
        $server -match 'put\("authority"' -or
        $server -match 'accepted\s*=\s*true') {
        throw "A Java/WebSocket entrypoint locally manufactures acceptance or authority."
    }
}
if ($standaloneServer -notmatch 'getJSONObject\("effect_params"\)' -or
    $standaloneServer -match 'message\.optJSONObject\("params"\)') {
    throw "Standalone Java/WebSocket entrypoint does not consume only receipt-bound effect_params."
}
if ($embeddedServer -notmatch 'authority_runtime_config_json' -or
    $embeddedServer -notmatch 'authorityConfigSource=packaged' -or
    $embeddedServer -match 'sessionTokenAccepted') {
    throw "Embedded server does not reject settings-supplied authority expansion or still treats a local token as mutation admission."
}

$embeddedLifecycle = Get-Content -Raw -LiteralPath $embeddedLifecyclePath
foreach ($token in @(
    "GET_SIGNING_CERTIFICATES",
    "Process.myUid()",
    "EmbeddedCallerIdentityResolver.requireExact",
    "EmbeddedManifoldRuntimeAuthorityBridge.admit",
    '"issue_token"',
    '"authorize_use"',
    '"expected_admission_authority_revision"',
    "GeneratedEmbeddedManifoldRuntimeConfig.CLIENT_ID")) {
    if ($embeddedLifecycle -notmatch [regex]::Escape($token)) {
        throw "Embedded platform-authenticated admission lifecycle is missing '$token'."
    }
}
if ($embeddedLifecycle -match 'GRANTED_CAPABILITIES') {
    throw "Embedded Java lifecycle gained local grant policy instead of asking Manifold."
}
$javaHome = @($env:JAVA_HOME, "S:\Work\tools\Java\temurin-17") |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_ -PathType Container) } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    throw "JDK 17 is required for the embedded caller identity damaged test."
}
$javaTestOut = Join-Path $root "local-artifacts\embedded-caller-identity-java-test"
New-Item -ItemType Directory -Force -Path $javaTestOut | Out-Null
& (Join-Path $javaHome "bin\javac.exe") -d $javaTestOut $embeddedIdentityPath $embeddedIdentityTestPath $embeddedWebSocketPolicyPath $embeddedWebSocketPolicyTestPath
if ($LASTEXITCODE -ne 0) { throw "Embedded caller identity Java test compilation failed." }
& (Join-Path $javaHome "bin\java.exe") -cp $javaTestOut io.github.mesmerprism.rustyquest.native_renderer.EmbeddedCallerIdentityResolverTest
if ($LASTEXITCODE -ne 0) { throw "Embedded caller identity damaged test failed." }
& (Join-Path $javaHome "bin\java.exe") -cp $javaTestOut io.github.mesmerprism.rustyquest.native_renderer.EmbeddedWebSocketAuthorityPolicyTest
if ($LASTEXITCODE -ne 0) { throw "Embedded WebSocket authority damaged test failed." }

$build = Get-Content -Raw -LiteralPath $buildPath
foreach ($token in @(
    "rusty.quest.broker.runtime_config.v1",
    "GeneratedBrokerRuntimeConfig.java",
    "packaged_authority",
    "Get-ExactClientGrantCapabilities",
    "broker_runtime_config_canonical_sha256",
    "module.runtime.host",
    "broker_runtime_config_sha256",
    "MediaSessionBindingPath",
    "native-renderer.media-lifecycle.json",
    "spatial-camera-panel.media-lifecycle.json",
    "mediaLifecycleLock.broker_runtime_lease_id",
    "media_sessions",
    "fresh-process-entropy_same-process-rebind-continuity")) {
    if ($build -notmatch [regex]::Escape($token)) {
        throw "Standalone build is missing stateful runtime config token '$token'."
    }
}

$embeddedBuild = Get-Content -Raw -LiteralPath $embeddedBuildPath
foreach ($token in @(
    "GeneratedEmbeddedManifoldRuntimeConfig.java",
    "media-session-embedded.json",
    "native-renderer-display.binding.json",
    "native-renderer.media-lifecycle.json",
    "native-renderer.client.json",
    "packaged_authority",
    "Get-ExactClientGrantCapabilities",
    "embedded_manifold_runtime_config_canonical_sha256")) {
    if ($embeddedBuild -notmatch [regex]::Escape($token)) {
        throw "Embedded build is missing packaged authority token '$token'."
    }
}

$regeneratedBindings = Join-Path $root "target\media-runtime-product-binding-regeneration"
& cargo run --quiet -p rusty-quest-broker-authority --bin export_media_product_bindings -- $regeneratedBindings
if ($LASTEXITCODE -ne 0) { throw "Media product binding regeneration failed." }
foreach ($name in @("display-composite.binding.json", "camera2-surface.binding.json", "native-renderer-display.binding.json", "spatial-camera-panel-display.binding.json")) {
    $expected = Get-Content -Raw -LiteralPath (Join-Path $root "fixtures\media-runtime-products\$name")
    $actual = Get-Content -Raw -LiteralPath (Join-Path $regeneratedBindings $name)
    if ($expected -cne $actual) {
        throw "Committed media product binding drifted: $name"
    }
}

Write-Host "Quest broker authority static gate passed"
