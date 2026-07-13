param([string]$RepoRoot = ".")
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path $RepoRoot).Path
$nativeSpec = Get-Content -Raw -LiteralPath (Join-Path $repo "fixtures\broker-clients\native-renderer.client.json") | ConvertFrom-Json
$spatialSpec = Get-Content -Raw -LiteralPath (Join-Path $repo "fixtures\broker-clients\spatial-camera-panel.client.json") | ConvertFrom-Json
$nativeManifest = Get-Content -Raw -LiteralPath (Join-Path $repo "apps\native-renderer-android\AndroidManifest.xml")
$spatialManifest = Get-Content -Raw -LiteralPath (Join-Path $repo "apps\spatial-camera-panel-android\app\src\main\AndroidManifest.xml")
$sharedClient = Get-Content -Raw -LiteralPath (Join-Path $repo "crates\rusty-quest-broker-client\android\io\github\mesmerprism\rustyquest\broker_client\BrokerClientProbeActivity.java")
$requestNamespacePath = Join-Path $repo "crates\rusty-quest-broker-client\android\io\github\mesmerprism\rustyquest\broker_client\BrokerRequestNamespace.java"
$requestNamespaceTestPath = Join-Path $repo "crates\rusty-quest-broker-client\tests\java\io\github\mesmerprism\rustyquest\broker_client\BrokerRequestNamespaceTest.java"
$brokerBuild = Get-Content -Raw -LiteralPath (Join-Path $repo "tools\Build-ManifoldBrokerAndroid.ps1")
$nativeBuild = Get-Content -Raw -LiteralPath (Join-Path $repo "tools\Build-NativeRendererAndroid.ps1")
$spatialBuildScript = Get-Content -Raw -LiteralPath (Join-Path $repo "tools\Build-SpatialCameraPanelAndroid.ps1")
$spatialBuild = Get-Content -Raw -LiteralPath (Join-Path $repo "apps\spatial-camera-panel-android\app\build.gradle.kts")
$deviceSuite = Get-Content -Raw -LiteralPath (Join-Path $repo "tools\Invoke-MultiAppBrokerClientTwoQuest.ps1")
$brokerAdmissionService = Get-Content -Raw -LiteralPath (Join-Path $repo "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\ManifoldAdmissionService.java")

foreach ($spec in @($nativeSpec, $spatialSpec)) {
    if ($spec.schema -ne "rusty.quest.broker_client_spec.v1") { throw "Broker client spec schema drifted." }
    if (@($spec.contract_families).Count -ne 2 -or
        @($spec.contract_families) -notcontains "rusty.manifold.peer.session_descriptor.v1" -or
        @($spec.contract_families) -notcontains "rusty.manifold.media.session_descriptor.v1") {
        throw "Broker client shared contract family closure drifted."
    }
    if (@($spec.runtime_properties).Count -ne 0 -or @($spec.application_defaults).Count -ne 0) {
        throw "Broker client spec contains property/default bleed."
    }
}
if ($nativeSpec.client_id -eq $spatialSpec.client_id -or
    $nativeSpec.package_name -eq $spatialSpec.package_name -or
    $nativeSpec.feature_lock_id -eq $spatialSpec.feature_lock_id -or
    $nativeSpec.marker_namespace -eq $spatialSpec.marker_namespace) {
    throw "Native and Spatial broker client identity/lock/marker must be distinct."
}
foreach ($token in @($nativeSpec.client_id,$nativeSpec.feature_lock_id,$nativeSpec.marker_namespace,"BrokerClientProbeActivity","BROKER_ADMISSION")) {
    if ($nativeManifest -notmatch [regex]::Escape([string]$token)) { throw "Native broker client manifest missing $token" }
}
foreach ($token in @($spatialSpec.client_id,$spatialSpec.feature_lock_id,$spatialSpec.marker_namespace,"BrokerClientProbeActivity","BROKER_ADMISSION")) {
    if ($spatialManifest -notmatch [regex]::Escape([string]$token)) { throw "Spatial broker client manifest missing $token" }
}
if ($nativeManifest -match [regex]::Escape([string]$spatialSpec.marker_namespace) -or
    $spatialManifest -match [regex]::Escape([string]$nativeSpec.marker_namespace)) {
    throw "Broker client marker namespace crossed app manifests."
}
foreach ($token in @("expected_authority_revision","capability.media.session.observe","replayed_request","token_revoked","localPolicy=false","cleanupRequested=true","SecureRandom","deliberateReplayUseRequestId","BrokerRequestNamespace")) {
    if ($sharedClient -notmatch [regex]::Escape($token)) { throw "Shared broker client adapter missing $token" }
}
foreach ($token in @("command.media.session.start","command.media.session.stop","MUTATE_RUNTIME","COMPLETE_MEDIA_ACTION","RUNTIME_EVIDENCE","start-completion.json","stop-completion.json","broker-media-lifecycle","provider_epoch_id","admission_authority_revision","runtime_authority_revision","admissionRevision=","providerEpoch=","recovery_complete","does_not_prove","lifecycleArtifacts=true")) {
    if ($sharedClient -notmatch [regex]::Escape($token)) { throw "Shared broker client lifecycle emission route missing $token" }
}
foreach ($token in @("MESSAGE_MUTATE_RUNTIME","MESSAGE_COMPLETE_MEDIA_ACTION","MESSAGE_RUNTIME_EVIDENCE","ManifoldRuntimeAuthorityBridge.evaluateMutation","ManifoldRuntimeAuthorityBridge.completeMediaAction","ManifoldRuntimeAuthorityBridge.evidence")) {
    if ($brokerAdmissionService -notmatch [regex]::Escape($token)) { throw "Broker admission service runtime bridge projection missing $token" }
}
if ($sharedClient -match 'app_death_observed"\s*,\s*true' -or
    $sharedClient -match 'cleanup_complete"\s*,\s*true') {
    throw "Shared broker client must not synthesize recovery/cleanup acceptance evidence."
}
foreach ($token in @("old_epoch_rejection","rusty.quest.broker_client.old_epoch_rejection_probe.v1","override_provider_epoch_id","old_epoch_rejected","expected_rejection","local_acceptance_rules")) {
    if ($sharedClient -notmatch [regex]::Escape($token)) { throw "Shared broker client old-epoch rejection probe missing $token" }
}
foreach ($token in @("native-renderer.client.json","spatial-camera-panel.client.json","Get-ExactClientGrantCapabilities","packaged_authority","client_lock_sha256")) {
    if ($brokerBuild -notmatch [regex]::Escape([string]$token)) { throw "Broker product grant generation missing $token" }
}
foreach ($token in @('"command.peer.status.get"', '"stream.peer.status"', '"capability.peer.session.observe"')) {
    if ($brokerBuild -notmatch [regex]::Escape([string]$token)) { throw "Broker product grant generation missing peer observation closure token $token" }
}
if ($brokerBuild -match 'capabilities\s*=\s*@\("capability\.command\.session\.list","capability\.media') {
    throw "Broker build still hardcodes an ambient product-independent capability union."
}
if ($nativeBuild -notmatch "rusty-quest-broker-client" -or $spatialBuild -notmatch "rusty-quest-broker-client") {
    throw "Both app builds must compile the shared broker client adapter."
}
foreach ($token in @("[string]`$Keystore", "RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE", "signing_keystore")) {
    if ($spatialBuildScript -notmatch [regex]::Escape($token)) { throw "Spatial build wrapper missing shared signing control token $token" }
}
foreach ($token in @("RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE", "signingConfigs.getByName(`"debug`")", "storePassword = `"android`"", "keyAlias = `"androiddebugkey`"")) {
    if ($spatialBuild -notmatch [regex]::Escape($token)) { throw "Spatial Gradle build missing shared signing override token $token" }
}
foreach ($token in @("expected_authority_revision","client_uid_not_distinct","marker_bleed","qcl100_generic_fold","cleanup_complete","system_fatal_count","NativeLifecycleEvidence","SpatialLifecycleEvidence","validate_media_lifecycle_evidence","media_lifecycle_pair_receipt")) {
    if ($deviceSuite -notmatch [regex]::Escape($token)) { throw "Multi-app device suite missing $token" }
}
if ($deviceSuite -notmatch "NET-016 media lifecycle evidence is required") {
    throw "Multi-app device suite must fail closed before ADB when lifecycle evidence is absent."
}
if ($deviceSuite -notmatch "status=rejected" -or $deviceSuite -notmatch "Client rejected on") {
    throw "Multi-app device suite must fail fast with explicit client rejection evidence."
}
foreach ($token in @("Get-FinalAdmissionRevision","spatialExpectedRevision","CollectLifecycleArtifactsFromApps","GenerateLifecycleRecoveryEvidence","NativeLifecycleRecoveryEvidence","SpatialLifecycleRecoveryEvidence","rusty.quest.broker_client.lifecycle_recovery_evidence.v1","Resolve-TemplatePath","{serial}","{client}","exec-out","run-as","RedirectStandardOutput","BaseStream.CopyTo","files/broker-media-lifecycle/manifest.json","start-completion.json","stop-completion.json","partial-lifecycle-artifacts","partial_lifecycle_artifacts","generated-lifecycle-recovery","wrapper_observed_device_sequence","Wait-OldEpochMarker","Pull-OldEpochProbe","Test-AppDeathObserved","Invoke-ObservedLifecycleRecoveryPair","old-epoch-rejection.json","recovery_probe_mode","override_provider_epoch_id","app_death_observed","restarted_provider_epoch_id","cleanup_complete","assembly-evidence.json","assemble_media_lifecycle_evidence","assembled_from_app_artifacts_and_explicit_recovery","media_lifecycle_pair_receipts","collected_partial_not_promotional","client_lifecycle_artifact_collection_enabled","does_not_prove_without_full_lifecycle_evidence")) {
    if ($deviceSuite -notmatch [regex]::Escape($token)) { throw "Multi-app device suite missing partial lifecycle collection boundary token $token" }
}
if ($deviceSuite -match '"--el","expected_authority_revision","4"') {
    throw "Multi-app device suite must not hard-code Spatial expected revision after Native media lifecycle advances admission state."
}
if ($deviceSuite -notmatch "non-promotional" -or $deviceSuite -notmatch "do not prove app death") {
    throw "Multi-app device suite must document partial artifact collection as non-promotional."
}
if ($deviceSuite -match 'app_death_observed\s*=\s*\$true' -or
    $deviceSuite -match 'old_epoch_rejected\s*=\s*\$true' -or
    $deviceSuite -match 'cleanup_complete\s*=\s*\$true[^;]') {
    throw "Multi-app device suite must not synthesize lifecycle recovery/cleanup booleans from partial artifacts."
}
foreach ($token in @("admission_authority_revision","runtime_authority_revision","current_provider_epoch_id")) {
    if ($sharedClient -notmatch [regex]::Escape($token)) { throw "Shared broker client old-epoch recovery probe missing $token" }
}
if (-not (Test-Path -LiteralPath (Join-Path $repo "crates\rusty-quest-broker-client\src\bin\validate_media_lifecycle_evidence.rs") -PathType Leaf)) {
    throw "Missing lifecycle evidence reducer CLI."
}
if (-not (Test-Path -LiteralPath (Join-Path $repo "crates\rusty-quest-broker-client\src\bin\assemble_media_lifecycle_evidence.rs") -PathType Leaf)) {
    throw "Missing lifecycle evidence assembler CLI."
}
foreach ($token in @("BrokerMediaLifecycleCompletionResponse","BrokerMediaLifecycleAssemblyEvidence","assemble_media_lifecycle_evidence","module.runtime.host")) {
    $lifecycleSource = Get-Content -Raw -LiteralPath (Join-Path $repo "crates\rusty-quest-broker-client\src\media_lifecycle.rs")
    if ($lifecycleSource -notmatch [regex]::Escape($token)) { throw "Lifecycle evidence assembler missing $token" }
}
foreach ($path in @($requestNamespacePath, $requestNamespaceTestPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing per-launch request namespace test surface: $path"
    }
}
$javaHome = @($env:JAVA_HOME, "S:\Work\tools\Java\temurin-17") |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_ -PathType Container) } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    throw "JDK 17 is required for broker request namespace host tests."
}
$javac = Join-Path $javaHome "bin\javac.exe"
$java = Join-Path $javaHome "bin\java.exe"
$javaTestOut = Join-Path $repo "local-artifacts\broker-client-java-test"
New-Item -ItemType Directory -Force -Path $javaTestOut | Out-Null
& $javac -d $javaTestOut $requestNamespacePath $requestNamespaceTestPath
if ($LASTEXITCODE -ne 0) { throw "Broker request namespace Java test compilation failed." }
& $java -cp $javaTestOut io.github.mesmerprism.rustyquest.broker_client.BrokerRequestNamespaceTest
if ($LASTEXITCODE -ne 0) { throw "Broker request namespace relaunch/restart test failed." }
& cargo test -p rusty-quest-broker-client
if ($LASTEXITCODE -ne 0) { throw "Broker client Rust contract tests failed." }
Write-Host "Rusty Quest multi-app broker client static gate passed"
