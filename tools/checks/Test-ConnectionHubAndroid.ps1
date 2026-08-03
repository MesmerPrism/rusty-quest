param([string]$RepoRoot)
$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..") }
$RepoRoot = (Resolve-Path $RepoRoot).Path
$app = Join-Path $RepoRoot "apps\manifold-broker-android"
$javaRoot = Join-Path $app "src\main\java\io\github\mesmerprism\rustymanifold\broker"
$test = Join-Path $app "tests\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubCoreTest.java"
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
    (Join-Path $assets "index.html"),
    (Join-Path $assets "app.js"),
    (Join-Path $assets "styles.css")
)
foreach ($path in $required) { if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing Connection Hub path: $path" } }

$service = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubStartService.java")
$activity = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubStartActivity.java")
$binder = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubAdmissionService.java")
$stateStore = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "AndroidConnectionHubStateStore.java")
$server = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubHttpServer.java")
$protocol = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubProtocol.java")
$process = Get-Content -Raw -LiteralPath (Join-Path $javaRoot "ConnectionHubProcess.java")
$spatialManifest = Get-Content -Raw -LiteralPath (Join-Path $spatial "app\src\main\AndroidManifest.xml")
$spatialClient = Get-Content -Raw -LiteralPath (Join-Path $spatial "app\src\main\java\io\github\mesmerprism\rustyquest\spatial_video_control\ConnectionHubSurfaceClient.kt")
$spatialActivity = Get-Content -Raw -LiteralPath (Join-Path $spatial "app\src\main\java\io\github\mesmerprism\rustyquest\spatial_video_control\SpatialVideoControlActivity.kt")
$buildScript = Get-Content -Raw -LiteralPath (Join-Path $RepoRoot "tools\Build-ManifoldBrokerAndroid.ps1")
$productSource = Get-Content -Raw -LiteralPath (Join-Path $RepoRoot "crates\rusty-quest-broker-product\src\lib.rs")
$sampleProvider = Join-Path $spatial "hub-sample-provider\src\main\java\io\github\mesmerprism\rustyquest\connection_hub_sample\ConnectionHubSampleActivity.java"

if ($activity -match 'LocalManifoldBrokerServer\.get\(\)\.start' -or $activity -notmatch 'ACTION_START_HUB') { throw "Management Activity owns hidden server startup or lacks explicit Start." }
if ($service -notmatch 'START_STICKY' -or $service -notmatch 'ConnectionHubProcess' -or $service -notmatch 'ACTION_STOP_HUB') { throw "Foreground service does not own persistent Hub lifecycle." }
if ($binder -notmatch 'message\.sendingUid' -or $binder -notmatch 'GET_SIGNING_CERTIFICATES' -or $binder -notmatch 'MESSAGE_REGISTER_SURFACE') { throw "Binder provider API does not derive platform identity." }
if ($binder -match 'data\.getString\("admitted_client_evidence_json"') { throw "Binder accepts caller-supplied admission evidence." }
if ($stateStore -notmatch 'AndroidKeyStore' -or $stateStore -notmatch 'AES/GCM/NoPadding' -or $stateStore -match 'putString\(KEY_STATE, value\.toString') { throw "Durable session projections are not Keystore-encrypted." }
if ($protocol -notmatch '/v1/status' -or $protocol -notmatch '/v1/pair' -or $protocol -notmatch '/v1/socket' -or $server -notmatch 'X-Rusty-Confidentiality' -or $server -notmatch 'sec-websocket-version' -or $server -notmatch 'hasSameOrigin') { throw "Fixed HTTP/WebSocket protocol or posture headers missing." }
if ($server -match '/v1/socket\?session' -or $server -notmatch 'SOCKET_AUTHENTICATE_SCHEMA' -or $server -notmatch 'transport_epoch') { throw "WebSocket still uses a URL bearer or omits first-frame authentication." }
if ($server -match 'Access-Control-Allow-Origin' -or $server -match 'https?://[^\"]') { throw "Hub server enables CORS or ambient remote assets." }
if ($process -notmatch 'new ManifoldConnectionHubAuthority\(\)' -or $process -match 'UnavailableManifold') { throw "Connection Hub process is not wired to the real Manifold JNI authority." }
if (Test-Path -LiteralPath (Join-Path $javaRoot "UnavailableManifoldConnectionHubAuthority.java")) { throw "Fail-closed development authority stub remains in product source." }
if ($buildScript -notmatch '\$connectionHubSelected' -or $buildScript -notmatch 'connectionHubPackagedAssets' -or $buildScript -notmatch 'ConnectionHub\*\.java' -or $productSource -notmatch '\.ConnectionHubStartActivity' -or $productSource -notmatch '\.BrokerStartActivity') { throw "Product lock does not gate Hub classes/assets/components while preserving legacy components." }
if ($spatialManifest -notmatch 'BROKER_ADMISSION' -or $spatialManifest -notmatch 'io\.github\.mesmerprism\.rustymanifold\.broker') { throw "Spatial provider does not bind the exact signature-scoped Broker." }
if ($spatialClient -notmatch 'MESSAGE_REGISTER_SURFACE' -or $spatialClient -notmatch 'MESSAGE_UNREGISTER_SURFACE' -or $spatialClient -match 'admitted_client_evidence_json') { throw "Spatial provider lifecycle or admission boundary is incorrect." }
if ($spatialActivity -notmatch 'hubSurface.*\.start' -or $spatialActivity -notmatch 'hubSurface\?\.close') { throw "Spatial app does not register/unregister its Hub surface with lifecycle." }
if (-not (Test-Path -LiteralPath $sampleProvider -PathType Leaf) -or (Get-Content -Raw -LiteralPath $sampleProvider) -notmatch 'surface\.connection_hub_sample\.toggle') { throw "Distinct second-package Hub provider is missing." }

$node = (Get-Command node -ErrorAction Stop).Source
& $node --check (Join-Path $assets "app.js")
if ($LASTEXITCODE -ne 0) { throw "Connection Hub browser JS syntax failed." }
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
$sources = @(
    (Join-Path $javaRoot "ConnectionHubProtocol.java"),
    (Join-Path $javaRoot "ConnectionHubAuthorityPort.java"),
    (Join-Path $javaRoot "ConnectionHubStateStore.java"),
    (Join-Path $javaRoot "ConnectionHubRuntime.java"),
    (Join-Path $javaRoot "HubProviderIdentity.java"),
    (Join-Path $javaRoot "HubSurfaceDescriptor.java"),
    (Join-Path $javaRoot "HubSurfaceRegistry.java"),
    $test
)
$javac = (Get-Command javac -ErrorAction Stop).Source
$java = (Get-Command java -ErrorAction Stop).Source
& $javac -encoding UTF-8 -source 8 -target 8 -cp $jsonJar.FullName -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "Connection Hub host Java compile failed." }
& $java -cp "$out;$($jsonJar.FullName)" io.github.mesmerprism.rustymanifold.broker.ConnectionHubCoreTest
if ($LASTEXITCODE -ne 0) { throw "Connection Hub host tests failed." }

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
final class GeneratedConnectionHubConfig { static final String JSON="{}"; }
'@)
$androidOut = Join-Path $out "android-classes"
New-Item -ItemType Directory -Force -Path $androidOut | Out-Null
$androidSources = @(Get-ChildItem -Path $javaRoot -Filter *.java | ForEach-Object { $_.FullName }) +
    @(Get-ChildItem -Path $generatedDir -Filter *.java | ForEach-Object { $_.FullName })
& $javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar -d $androidOut $androidSources
if ($LASTEXITCODE -ne 0) { throw "Connection Hub complete Android Java source compile failed." }
Write-Output "Rusty Connection Hub Android source and host validation passed"
