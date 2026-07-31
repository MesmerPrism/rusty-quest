[CmdletBinding()]
param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Package Updater validation requires PowerShell 7.6 Core or newer."
}
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

$appRoot = Join-Path $RepoRoot "apps\package-updater-android"
$javaRoot = Join-Path $appRoot `
    "app\src\main\java\io\github\mesmerprism\rustyquest\packageupdater"
$e2eJavaRoot = Join-Path $appRoot `
    "app\src\e2e\java\io\github\mesmerprism\rustyquest\packageupdater"
$paths = [ordered]@{
    readme = Join-Path $appRoot "README.md"
    settings = Join-Path $appRoot "settings.gradle.kts"
    root_build = Join-Path $appRoot "build.gradle.kts"
    app_build = Join-Path $appRoot "app\build.gradle.kts"
    manifest = Join-Path $appRoot "app\src\main\AndroidManifest.xml"
    e2e_manifest = Join-Path $appRoot "app\src\e2e\AndroidManifest.xml"
    activity = Join-Path $javaRoot "PackageUpdaterActivity.java"
    pipeline = Join-Path $javaRoot "PackageUpdatePipeline.java"
    channel_pointer = Join-Path $javaRoot "UpdateChannelPointer.java"
    verified_plan = Join-Path $javaRoot "VerifiedUpdatePlan.java"
    post_install_policy = Join-Path $javaRoot "PostInstallCheckpointPolicy.java"
    native_verifier = Join-Path $javaRoot "NativeEd25519Verifier.java"
    verifier_boundary = Join-Path $javaRoot "UpdateEnvelopeVerifier.java"
    verifier = Join-Path $javaRoot "StrictUpdateEnvelopeVerifier.java"
    canonicalizer = Join-Path $javaRoot "UpdateManifestCanonicalizer.java"
    json_preflight = Join-Path $javaRoot "StrictJsonPreflight.java"
    state_store = Join-Path $javaRoot "UpdateStateStore.java"
    manifest_client = Join-Path $javaRoot "UpdateManifestClient.java"
    stager = Join-Path $javaRoot "ApkStager.java"
    inspection = Join-Path $javaRoot "PackageInspection.java"
    receipt = Join-Path $javaRoot "InstallReceiptStore.java"
    installer = Join-Path $javaRoot "PackageInstallController.java"
    callback = Join-Path $javaRoot "PackageInstallCallbackReceiver.java"
    e2e_provider = Join-Path $e2eJavaRoot "E2ePackageUpdaterCliProvider.java"
    e2e_service = Join-Path $e2eJavaRoot "E2ePackageUpdateService.java"
    e2e_store = Join-Path $e2eJavaRoot "E2eUpdateOperationStore.java"
    native_cargo = Join-Path $appRoot "native\Cargo.toml"
    native_lib = Join-Path $appRoot "native\src\lib.rs"
    host_vector = Join-Path $appRoot `
        "host-tests\io\github\mesmerprism\rustyquest\packageupdater\PackageUpdaterCanonicalVectorTest.java"
    host_post_install_policy = Join-Path $appRoot `
        "host-tests\io\github\mesmerprism\rustyquest\packageupdater\PostInstallCheckpointPolicyTest.java"
    build_wrapper = Join-Path $RepoRoot "tools\Build-PackageUpdaterAndroid.ps1"
    publish_wrapper = Join-Path $RepoRoot "tools\Publish-PackageUpdateManifest.ps1"
    e2e_cli_wrapper = Join-Path $RepoRoot "tools\Invoke-PackageUpdaterE2eCli.ps1"
    publication_contract_test = Join-Path $RepoRoot `
        "tools\checks\Test-PackageUpdatePublicationContract.ps1"
    build_artifact_contract_test = Join-Path $RepoRoot `
        "tools\checks\Test-PackageUpdaterBuildArtifactContract.ps1"
    product_release_contract_test = Join-Path $RepoRoot `
        "tools\checks\Test-PackageUpdaterProductReleaseContract.ps1"
    alpha_release_workflow_test = Join-Path $RepoRoot `
        "tools\checks\Test-PackageUpdaterLabsReleaseWorkflow.ps1"
    pages_workflow_test = Join-Path $RepoRoot `
        "tools\checks\Test-PackageUpdateLabsPagesWorkflow.ps1"
    product_release_contract = Join-Path $RepoRoot `
        "tools\package_updater\ProductReleaseContract.ps1"
    product_release_generator = Join-Path $RepoRoot `
        "tools\New-PackageUpdaterProductReleaseMetadata.ps1"
    product_release_validator = Join-Path $RepoRoot `
        "tools\Test-PackageUpdaterProductReleaseMetadata.ps1"
    alpha_release_workflow = Join-Path $RepoRoot `
        ".github\workflows\package-updater-labs-release.yml"
    pages_workflow = Join-Path $RepoRoot `
        ".github\workflows\package-update-labs-pages.yml"
    pages_publisher = Join-Path $RepoRoot `
        "tools\Publish-PackageUpdateLabsPages.ps1"
    pages_target = Join-Path $RepoRoot `
        "distribution\package-update-labs-target.json"
    manifest_authenticator = Join-Path $RepoRoot `
        "crates\rusty-quest-package-updater\src\bin\authenticate_package_update_manifest.rs"
}
foreach ($entry in $paths.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) {
        throw "Missing Package Updater surface $($entry.Key): $($entry.Value)"
    }
}

function Assert-Match(
    [string]$Text,
    [string]$Pattern,
    [string]$Message
) {
    if ($Text -notmatch $Pattern) {
        throw $Message
    }
}

$readme = Get-Content -Raw -LiteralPath $paths.readme
$settings = Get-Content -Raw -LiteralPath $paths.settings
$rootBuild = Get-Content -Raw -LiteralPath $paths.root_build
$appBuild = Get-Content -Raw -LiteralPath $paths.app_build
$manifestText = Get-Content -Raw -LiteralPath $paths.manifest
$e2eManifestText = Get-Content -Raw -LiteralPath $paths.e2e_manifest
$activity = Get-Content -Raw -LiteralPath $paths.activity
$pipeline = Get-Content -Raw -LiteralPath $paths.pipeline
$channelPointer = Get-Content -Raw -LiteralPath $paths.channel_pointer
$verifiedPlan = Get-Content -Raw -LiteralPath $paths.verified_plan
$postInstallPolicy = Get-Content -Raw -LiteralPath $paths.post_install_policy
$nativeVerifier = Get-Content -Raw -LiteralPath $paths.native_verifier
$verifierBoundary = Get-Content -Raw -LiteralPath $paths.verifier_boundary
$verifier = Get-Content -Raw -LiteralPath $paths.verifier
$canonicalizer = Get-Content -Raw -LiteralPath $paths.canonicalizer
$jsonPreflight = Get-Content -Raw -LiteralPath $paths.json_preflight
$stateStore = Get-Content -Raw -LiteralPath $paths.state_store
$manifestClient = Get-Content -Raw -LiteralPath $paths.manifest_client
$stager = Get-Content -Raw -LiteralPath $paths.stager
$inspection = Get-Content -Raw -LiteralPath $paths.inspection
$receipt = Get-Content -Raw -LiteralPath $paths.receipt
$installer = Get-Content -Raw -LiteralPath $paths.installer
$callback = Get-Content -Raw -LiteralPath $paths.callback
$e2eProvider = Get-Content -Raw -LiteralPath $paths.e2e_provider
$e2eService = Get-Content -Raw -LiteralPath $paths.e2e_service
$e2eStore = Get-Content -Raw -LiteralPath $paths.e2e_store
$nativeCargo = Get-Content -Raw -LiteralPath $paths.native_cargo
$nativeLib = Get-Content -Raw -LiteralPath $paths.native_lib
$buildWrapper = Get-Content -Raw -LiteralPath $paths.build_wrapper
$publishWrapper = Get-Content -Raw -LiteralPath $paths.publish_wrapper
$e2eCliWrapper = Get-Content -Raw -LiteralPath $paths.e2e_cli_wrapper
$productReleaseContract = Get-Content -Raw `
    -LiteralPath $paths.product_release_contract
$productReleaseGenerator = Get-Content -Raw `
    -LiteralPath $paths.product_release_generator
$productReleaseValidator = Get-Content -Raw `
    -LiteralPath $paths.product_release_validator
$alphaReleaseWorkflow = Get-Content -Raw `
    -LiteralPath $paths.alpha_release_workflow

[xml]$manifest = $manifestText
$androidNamespace = "http://schemas.android.com/apk/res/android"
if ($manifest.manifest.HasAttribute("package")) {
    throw "Package Updater source manifest must leave package identity to the Gradle applicationId."
}

$permissions = @(
    $manifest.manifest.'uses-permission' |
        ForEach-Object { $_.GetAttribute("name", $androidNamespace) } |
        Sort-Object -Unique
)
$expectedPermissions = @(
    "android.permission.INTERNET",
    "android.permission.REQUEST_INSTALL_PACKAGES"
) | Sort-Object
if (@(Compare-Object $permissions $expectedPermissions -SyncWindow 0).Count -ne 0) {
    throw "Package Updater permission closure changed: $($permissions -join ', ')"
}
$queries = @($manifest.manifest.queries.package)
if ($queries.Count -ne 1 -or
    $queries[0].GetAttribute("name", $androidNamespace) -ne
        '${expectedPackageName}') {
    throw "Package Updater may query only its exact build-fixed target package."
}

$application = $manifest.manifest.application
if ($application.GetAttribute("allowBackup", $androidNamespace) -ne "false") {
    throw "Package Updater app-private receipts must not be backed up."
}
if ($application.GetAttribute("usesCleartextTraffic", $androidNamespace) -ne "false") {
    throw "Package Updater must reject cleartext transport at the manifest boundary."
}
if ($null -ne $application.service -or $null -ne $application.provider) {
    throw "Package Updater must not declare services or providers."
}

$activities = @($application.activity)
if ($activities.Count -ne 1) {
    throw "Package Updater must expose exactly one Activity."
}
$launcherActivity = $activities[0]
if ($launcherActivity.GetAttribute("name", $androidNamespace) -ne
        ".PackageUpdaterActivity" -or
    $launcherActivity.GetAttribute("exported", $androidNamespace) -ne "true") {
    throw "Package Updater must expose only its launcher Activity."
}
$launcherActions = @(
    $launcherActivity.'intent-filter'.action |
        ForEach-Object { $_.GetAttribute("name", $androidNamespace) }
)
$launcherCategories = @(
    $launcherActivity.'intent-filter'.category |
        ForEach-Object { $_.GetAttribute("name", $androidNamespace) }
)
if ($launcherActions -notcontains "android.intent.action.MAIN" -or
    $launcherCategories -notcontains "android.intent.category.LAUNCHER") {
    throw "Package Updater Activity must remain a visible 2D launcher."
}
if ($launcherCategories -contains "android.intent.category.HOME") {
    throw "Package Updater must not claim HOME authority."
}

$receivers = @($application.receiver)
if ($receivers.Count -ne 1 -or
    $receivers[0].GetAttribute("name", $androidNamespace) -ne
        ".PackageInstallCallbackReceiver" -or
    $receivers[0].GetAttribute("exported", $androidNamespace) -ne "false") {
    throw "The sole Package Installer callback receiver must remain non-exported."
}

Assert-Match $settings 'rootProject\.name = "RustyQuestPackageUpdater"' `
    "Standalone Package Updater Gradle project identity missing."
Assert-Match $rootBuild 'com\.android\.application.*8\.11\.1' `
    "Package Updater must use the repository Android Gradle Plugin baseline."
foreach ($token in @(
    'namespace = "io.github.mesmerprism.rustyquest.packageupdater"',
    'applicationId = "io.github.mesmerprism.rustyquest.packageupdater.labs"',
    'compileSdk = 34',
    'minSdk = 34',
    'targetSdk = 34',
    'JavaVersion.VERSION_17',
    'RUSTY_QUEST_PACKAGE_UPDATER_MANIFEST_URL',
    'RUSTY_QUEST_PACKAGE_UPDATER_TRUSTED_KEY_ID',
    'RUSTY_QUEST_PACKAGE_UPDATER_TRUSTED_PUBLIC_KEY_BASE64',
    'RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_HTTPS_ORIGIN',
    'RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_SITE_BASE_PATH',
    'RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_PACKAGE_NAME',
    'RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_ROLLOUT_RING',
    'RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_SIGNER_SHA256',
    'expectedPackageName == "io\.github\.mesmerprism\.rustykiosk\.labs"',
    'expectedRolloutRing == "labs"',
    'package-updates/rusty-kiosk/labs/current\.json',
    'parsedManifestUri\.rawUserInfo == null',
    'parsedManifestUri\.rawQuery == null',
    'parsedManifestUri\.rawFragment == null',
    'buildConfigField\("String", "UPDATE_MANIFEST_URL"',
    'buildConfigField\(\s*"String",\s*"TRUSTED_PUBLIC_KEY_BASE64"',
    'buildConfigField\(\s*"String",\s*"EXPECTED_HTTPS_ORIGIN"',
    'buildConfigField\(\s*"String",\s*"EXPECTED_SITE_BASE_PATH"',
    'buildConfigField\(\s*"String",\s*"EXPECTED_PACKAGE_NAME"',
    'buildConfigField\(\s*"String",\s*"EXPECTED_ROLLOUT_RING"',
    'buildConfigField\(\s*"String",\s*"EXPECTED_SIGNER_SHA256"',
    'buildConfigField\("long", "MINIMUM_TARGET_VERSION_CODE", "1L"\)',
    '"MAXIMUM_TARGET_VERSION_CODE"')) {
    Assert-Match $appBuild $token "Package Updater build is missing fixed input token: $token"
}
foreach ($token in @(
    'Read-PackageUpdaterBuildManifest',
    'Assert-PackageUpdaterProductReleaseMetadata',
    '\$head -ne \$build\.source_revision',
    '\^\{tree\}')) {
    Assert-Match $productReleaseValidator $token `
        "Product release metadata validator is missing token: $token"
}
foreach ($token in @(
    'create\("e2e"\)',
    'initWith\(getByName\("debug"\)\)',
    'applicationIdSuffix = "\.e2ecli"',
    'versionNameSuffix = "-e2ecli"',
    'abiFilters \+= "arm64-v8a"',
    'generated/rustJniLibs',
    'buildRustNativeVerifier',
    'merge\[A-Z\]\.\*JniLibFolders',
    'CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER',
    'nativeCrate\.dir\("src"\)',
    'outputs\.file\(generatedNativeArtifact\)',
    '--target-dir')) {
    Assert-Match $appBuild $token `
        "Package Updater test-only E2E build closure is missing token: $token"
}
Assert-Match $appBuild `
    'manifestPlaceholders\["expectedPackageName"\] = expectedPackageName' `
    "Package Updater fixed package visibility placeholder is missing."
if ($appBuild -match '(?m)^\s*(implementation|api|runtimeOnly|compileOnly|kapt)\s*\(') {
    throw "Package Updater must remain dependency-light with no runtime libraries."
}

foreach ($token in @(
    'Attended sideloaded updater',
    'BuildConfig.UPDATE_MANIFEST_URL',
    'BuildConfig.TRUSTED_KEY_ID',
    'BuildConfig.EXPECTED_PACKAGE_NAME',
    'BuildConfig.EXPECTED_ROLLOUT_RING',
    'BuildConfig.EXPECTED_HTTPS_ORIGIN',
    'canRequestPackageInstalls',
    'Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES',
    'Uri\.parse\("package:" \+ getPackageName\(\)\)',
    'Executors.newSingleThreadExecutor',
    'PackageUpdatePipeline',
    'checkAndStage',
    'Thread\.currentThread\(\)\.isInterrupted',
    'describeProgress')) {
    Assert-Match $activity $token "Visible Package Updater flow is missing token: $token"
}
Assert-Match $activity 'cancelPersistedSession' `
    "Package Updater must expose a visible attended-session cancellation path."
foreach ($forbidden in @(
    'getIntent\(',
    'onNewIntent\(',
    'get[A-Za-z]*Extra\(',
    'ACTION_VIEW',
    'ACTION_SEND',
    'ACTION_INSTALL_PACKAGE',
    'ACTION_OPEN_DOCUMENT',
    'ACTION_GET_CONTENT',
    'ClipboardManager')) {
    if ($activity -match $forbidden) {
        throw "Package Updater Activity accepts a forbidden external input surface: $forbidden"
    }
}

foreach ($token in @(
    'UpdateManifestClient\.requireFixedHttpsUri',
    'BuildConfig\.UPDATE_MANIFEST_URL',
    'StrictUpdateEnvelopeVerifier',
    'PackageInspection\.installedVersionOrMissing',
    'PackageInspection\.verifyInstalledSigner',
    'downloadAndVerify',
    'stageAttendedInstall',
    'install_permission_required',
    'UpdateCancelledException')) {
    Assert-Match $pipeline $token `
        "Shared Package Updater pipeline is missing token: $token"
}

Assert-Match $verifierBoundary 'VerifiedUpdatePlan verify\(byte\[\] envelopeBytes, long nowMs\)' `
    "Package Updater must expose a strict verifier boundary."
foreach ($token in @(
    'rusty\.quest\.package_update_manifest_envelope\.v1',
    'rusty\.quest\.package_update_manifest\.v1',
    'SIGNATURE_DOMAIN',
    'NativeEd25519Verifier\.verify',
    'signature_verifier_failed_closed',
    'LinkageError',
    'trusted_key_not_configured',
    'trusted_signer_not_configured',
    'requireExactKeys',
    'StrictJsonPreflight\.requireNoDuplicateObjectKeys',
    'canonicalSignedManifest',
    'manifest_expired',
    'maximumManifestValidityMs',
    'minimumTargetVersionCode',
    'maximumTargetVersionCode',
    'MAX_JCS_SAFE_INTEGER',
    'requireEquals\(expectedPackageName',
    'requireEquals\(expectedRolloutRing',
    'requireEquals\(expectedSignerSha256',
    'canonicalOrigin\(uri\)\.equals\(expectedHttpsOrigin\)',
    'stateStore\.requireAdvances',
    'sequence_rollback',
    'version_rollback',
    'commitInstalled',
    'json_duplicate_object_key')) {
    Assert-Match (
        $verifier + "`n" + $canonicalizer + "`n" + $stateStore + "`n" + $jsonPreflight
    ) $token `
        "Fail-closed signed-envelope verifier is missing token: $token"
}
foreach ($token in @(
    'System\.loadLibrary\("rusty_quest_package_updater_android"\)',
    'nativeVerify\(')) {
    Assert-Match $nativeVerifier $token `
        "Package Updater native verifier Java boundary is missing token: $token"
}
foreach ($token in @(
    'crate-type = \["cdylib", "rlib"\]',
    'ed25519-dalek',
    'jni = "0\.22"')) {
    Assert-Match $nativeCargo $token `
        "Package Updater native verifier crate is missing token: $token"
}
foreach ($token in @(
    'verify_strict',
    'Java_io_github_mesmerprism_rustyquest_packageupdater_NativeEd25519Verifier_nativeVerify',
    'JNI_FALSE',
    'accepts_exact_signature_and_rejects_damage')) {
    Assert-Match $nativeLib $token `
        "Package Updater native verifier implementation is missing token: $token"
}
if ($verifier -match 'return\s+true\s*;|accepted\s*=\s*true') {
    throw "Package Updater verifier contains a permissive acceptance shortcut."
}

foreach ($networkSource in @($manifestClient, $stager)) {
    foreach ($token in @(
        'HttpsURLConnection',
        'setInstanceFollowRedirects\(false\)',
        'setConnectTimeout\(',
        'setReadTimeout\(',
        'Accept-Encoding", "identity"',
        'disconnect\(\)')) {
        Assert-Match $networkSource $token `
            "Package Updater HTTPS path is missing bounded transport token: $token"
    }
}
foreach ($token in @(
    'rusty\.quest\.package_update_channel_pointer\.v2',
    'BuildConfig\.UPDATE_CHANNEL',
    'BuildConfig\.TRUSTED_KEY_ID',
    'BuildConfig\.TRUSTED_PUBLIC_KEY_BASE64',
    'BuildConfig\.EXPECTED_SITE_BASE_PATH',
    'channel_pointer_envelope_hash_mismatch',
    'channel_pointer_plan_mismatch',
    '/package-updates/rusty-kiosk/labs/generations/'
)) {
    Assert-Match $channelPointer $token `
        "Immutable channel pointer verification is missing token: $token"
}
foreach ($token in @(
    'UpdateChannelPointer\.verify\(pointerBytes\)',
    'pointer\.verifyEnvelopeBytes\(envelopeBytes\)',
    'pointer\.verifyPlan\(plan\)'
)) {
    Assert-Match $pipeline $token `
        "Fresh-client pointer resolution is missing token: $token"
}
foreach ($token in @(
    'fromInstallReceipt',
    'install_receipt_tuple_mismatch',
    'BuildConfig\.EXPECTED_HTTPS_ORIGIN'
)) {
    Assert-Match $verifiedPlan $token `
        "Immutable rollback plan reconstruction is missing token: $token"
}
Assert-Match $stateStore 'requireAdvances\(VerifiedUpdatePlan plan\)' `
    "Rollback admission does not consume the immutable verified plan."
Assert-Match $stateStore 'commitInstalled\(VerifiedUpdatePlan plan\)' `
    "Rollback commit does not consume the immutable verified plan."
Assert-Match $manifestClient 'MAX_RESPONSE_BYTES = 256 \* 1024' `
    "Manifest response bound changed."
foreach ($token in @(
    'getNoBackupFilesDir',
    'package-updater/labs/staged',
    'apk_download_exceeded_signed_size',
    'apk_download_size_mismatch',
    'apk_sha256_mismatch',
    'PackageInspection.verifyArchive')) {
    Assert-Match $stager $token "Private APK staging is missing token: $token"
}
foreach ($token in @(
    'getPackageArchiveInfo',
    'GET_SIGNING_CERTIFICATES',
    'getLongVersionCode',
    'getApkContentsSigners',
    'signers.length != 1',
    'apk_package_mismatch',
    'apk_version_mismatch',
    'installed_version_readback_mismatch',
    '"_signer_mismatch"')) {
    Assert-Match $inspection $token "APK identity readback is missing token: $token"
}

foreach ($token in @(
    'PackageInstaller.SessionParams.MODE_FULL_INSTALL',
    'PackageInstaller.SessionParams.USER_ACTION_REQUIRED',
    'setAppPackageName\(artifact.packageName\)',
    'PACKAGE_SOURCE_DOWNLOADED_FILE',
    'session\.openWrite\(\s*"base\.apk"',
    'requireNotCancelled\(cancellation\)',
    'session.fsync\(output\)',
    'receiptStore.begin',
    'PendingIntent.FLAG_MUTABLE',
    'setPackage\(context.getPackageName\(\)\)',
    'session.commit',
    'compareAndSetState',
    'getSessionInfo',
    'sessionInfo\.isCommitted',
    'cancel_requested_awaiting_installer_callback',
    'cancel_requested_manifest_expired_awaiting_installer_callback',
    'isCancellationPending',
    'isInstalledCheckpointPending',
    'installed_readback_checkpoint_pending',
    'installed_but_checkpoint_rejected_expired',
    'PostInstallCheckpointPolicy\.mayAdvance',
    'commitInstalledCheckpoint',
    'manifest_expired_before_install_commit',
    '5_000L',
    'install_staging_failed_interrupted',
    'cancelPersistedSession',
    'cleanupTerminalArtifacts',
    'install_cancelled_by_wearer',
    'PackageInspection.verifyInstalled')) {
    Assert-Match $installer $token "Attended Package Installer path is missing token: $token"
}
foreach ($token in @(
    'AtomicFile',
    'getNoBackupFilesDir',
    'session_id',
    'callback_token',
    'package_name',
    'version_code',
    'version_name',
    'apk_url',
    'apk_size_bytes',
    'apk_sha256',
    'signer_sha256',
    'manifest_sequence',
    'manifest_expires_at_ms',
    'signed_manifest_sha256',
    'rollout_ring',
    'matchesCallback',
    'CALLBACK_SCHEME',
    'CALLBACK_AUTHORITY',
    'intent.getIntExtra',
    'compareAndSetState',
    'isTerminal\(currentState\)',
    'isCancellationPending\(currentState\)',
    'isInstalledCheckpointPending\(currentState\)',
    'install_staging_failed')) {
    Assert-Match $receipt $token "Persisted install receipt is missing token: $token"
}
foreach ($token in @(
    'receiptStore.matchesCallback\(intent\)',
    'PackageInstaller.STATUS_PENDING_USER_ACTION',
    'InstallReceiptStore\.isCancellationPending',
    'intent.getParcelableExtra\(Intent.EXTRA_INTENT, Intent.class\)',
    'pending_user_confirmation',
    'Intent.FLAG_ACTIVITY_NEW_TASK',
    'PackageInstaller.STATUS_SUCCESS',
    'verifyInstalledReadback',
    'commitInstalledCheckpoint',
    'installed_readback_checkpoint_pending',
    'installed_but_checkpoint_rejected_expired',
    'a fresh signed manifest is required',
    'installed_readback_ok',
    'readback_failed_after_installer_success')) {
    Assert-Match $callback $token "Package Installer callback is missing token: $token"
}
Assert-Match $callback 'PackageInstaller\.STATUS_FAILURE_ABORTED' `
    "Package Installer wearer cancellation must be a distinct terminal state."
Assert-Match $callback 'cleanupTerminalArtifacts' `
    "Package Installer terminal callbacks must remove private staged APKs."
Assert-Match $postInstallPolicy `
    'observedAtMs >= 0L && observedAtMs < manifestExpiresAtMs' `
    "Post-install expiry boundary must match Rust's exclusive expiry."
Assert-Match $stager 'failed_private_stage_not_removed' `
    "Failed downloads must fail closed when private partial-file cleanup fails."
foreach ($token in @(
    'PackageUpdatePipeline\.Cancellation',
    'requireNotCancelled\(cancellation\)',
    'progress\.update\(total, artifact\.apkSizeBytes\)')) {
    Assert-Match $stager $token `
        "Private APK staging cancellation/progress closure is missing token: $token"
}

[xml]$e2eManifest = $e2eManifestText
$e2ePermissions = @(
    $e2eManifest.manifest.'uses-permission' |
        ForEach-Object { $_.GetAttribute("name", $androidNamespace) } |
        Sort-Object -Unique
)
$expectedE2ePermissions = @(
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
) | Sort-Object
if (@(Compare-Object $e2ePermissions $expectedE2ePermissions -SyncWindow 0).
    Count -ne 0) {
    throw "Package Updater E2E permission closure changed: $($e2ePermissions -join ', ')"
}
$e2eApplication = $e2eManifest.manifest.application
$e2eProviders = @($e2eApplication.provider)
if ($e2eProviders.Count -ne 1 -or
    $e2eProviders[0].GetAttribute("name", $androidNamespace) -ne
        ".E2ePackageUpdaterCliProvider" -or
    $e2eProviders[0].GetAttribute("authorities", $androidNamespace) -ne
        '${applicationId}.cli' -or
    $e2eProviders[0].GetAttribute("exported", $androidNamespace) -ne "true" -or
    $e2eProviders[0].GetAttribute("grantUriPermissions", $androidNamespace) -ne
        "false" -or
    $e2eProviders[0].GetAttribute("permission", $androidNamespace) -ne
        "android.permission.DUMP") {
    throw "Package Updater E2E CLI provider boundary changed."
}
$e2eServices = @($e2eApplication.service)
if ($e2eServices.Count -ne 1 -or
    $e2eServices[0].GetAttribute("name", $androidNamespace) -ne
        ".E2ePackageUpdateService" -or
    $e2eServices[0].GetAttribute("exported", $androidNamespace) -ne "false" -or
    $e2eServices[0].GetAttribute("foregroundServiceType", $androidNamespace) -ne
        "dataSync") {
    throw "Package Updater E2E foreground worker boundary changed."
}
foreach ($token in @(
    'ANDROID_SHELL_UID = 2000',
    'Binder\.getCallingUid\(\) != ANDROID_SHELL_UID',
    '"check"\.equals\(command\)',
    '"status"\.equals\(command\)',
    '"cancel"\.equals\(command\)',
    'result_b64',
    'unsupported_cli_command')) {
    Assert-Match $e2eProvider $token `
        "Package Updater E2E CLI provider is missing token: $token"
}
foreach ($token in @(
    'startForeground\(',
    'FOREGROUND_SERVICE_TYPE_DATA_SYNC',
    'PackageUpdatePipeline',
    'requestCancel',
    'isRunning',
    'awaitingWearer',
    'stopSelf\(\)',
    'stopForeground\(STOP_FOREGROUND_REMOVE\)')) {
    Assert-Match $e2eService $token `
        "Package Updater E2E worker is missing token: $token"
}
foreach ($forbidden in @(
    'StrictUpdateEnvelopeVerifier',
    'UpdateManifestClient',
    'ApkStager',
    'PackageInstaller\.SessionParams',
    'USER_ACTION_REQUIRED')) {
    if ($e2eProvider -match $forbidden -or $e2eService -match $forbidden) {
        throw "Package Updater E2E CLI duplicates production authority: $forbidden"
    }
}
foreach ($token in @(
    'AtomicFile',
    'getNoBackupFilesDir',
    'rusty\.quest\.package_update\.e2e_operation_status\.v1',
    'cancel_requested',
    'installed_readback_ok',
    'correlateInstallReceipt',
    'if \(operation\.optBoolean\("terminal", false\)\)',
    'InstallReceiptStore\.isTerminal')) {
    Assert-Match $e2eStore $token `
        "Package Updater E2E operation store is missing token: $token"
}
foreach ($token in @(
    'ValidateSet\("Check", "Status", "Cancel"\)',
    '"io\.github\.mesmerprism\.rustyquest\.packageupdater\.labs\.e2ecli"',
    'adb',
    'content call',
    'result_b64',
    'ConvertFrom-Json',
    'rusty\.quest\.package_update\.e2e_cli_response\.v1')) {
    Assert-Match $e2eCliWrapper $token `
        "Package Updater E2E host CLI is missing token: $token"
}

foreach ($token in @(
    'status --porcelain',
    'Test-PackageUpdaterAndroidStatic.ps1',
    ':app:assembleRelease',
    'aarch64-linux-android34-clang\.cmd',
    'librusty_quest_package_updater_android\.so',
    'lib/arm64-v8a/librusty_quest_package_updater_android\.so',
    'OpenRead\(\$builtApk\)',
    'packagedNativeHash -ne \$nativeVerifierHash',
    'native_verifier_sha256',
    '--no-configuration-cache',
    'rusty.quest.package_updater_android.build_manifest.v1',
    'Assert-PackageUpdaterManifestUrl',
    'Assert-PackageUpdaterReleaseArtifact',
    'ExpectedVersionCode \$VersionCode',
    'ExpectedVersionName \$VersionName',
    'dump xmltree --file AndroidManifest\.xml',
    'dump permissions',
    'apksigner\.bat',
    'ConvertFrom-PackageUpdaterSignerCertificates',
    'ExpectedUpdaterSignerSha256',
    'release signer differs from the protected expected certificate',
    'updater_signer_sha256',
    'RUSTY_QUEST_PACKAGE_UPDATER_VERSION_CODE',
    'RUSTY_QUEST_PACKAGE_UPDATER_VERSION_NAME',
    'Get-PublicPackageUpdaterBuildTool',
    'InspectE2eApkPath',
    'Assert-PackageUpdaterE2eArtifact',
    'artifact_inspection',
    'RUSTY_QUEST_PACKAGE_UPDATER_KEYSTORE_PASSWORD')) {
    Assert-Match $buildWrapper $token `
        "Reproducible Package Updater build wrapper is missing token: $token"
}
foreach ($token in @(
    'ExpectedPackageName -cne',
    'io\.github\.mesmerprism\.rustykiosk\.labs',
    'ExpectedRolloutRing -cne "labs"',
    'may target only Kiosk Labs')) {
    Assert-Match $buildWrapper $token `
        "Release build wrapper lacks a hard Labs target boundary: $token"
}
foreach ($token in @(
    'rusty\.quest\.package_updater_product_release\.v2',
    'package-updater-v\(\?<version>0\\\.1\\\.0-alpha',
    'source_revision', 'source_tree', 'installation_identity',
    'expected_updater_signer_sha256', 'updater_signer_sha256',
    'APK version is not derived from its alpha tag',
    'primary_apk', 'Assert-ExactJsonFields',
    'Actual Package Updater APK differs from its build manifest')) {
    Assert-Match $productReleaseContract $token `
        "Product release metadata contract is missing token: $token"
}
foreach ($token in @(
    'rev-parse "\$\(\$build\.source_revision\)\^\{tree\}"',
    '\$head -ne \$build\.source_revision',
    'ConvertTo-Json -Depth 5')) {
    Assert-Match $productReleaseGenerator $token `
        "Product release metadata generator is missing token: $token"
}
Assert-Match $buildWrapper 'AndroidNdkDirectory' `
    "Release build wrapper lacks an exact NDK directory input."
Assert-Match $alphaReleaseWorkflow `
    'environment: package-updater-labs-release' `
    "Package Updater Labs release lacks its protected environment."
foreach ($token in @(
    'RUSTY_QUEST_UPDATE_SIGNING_SEED_BASE64URL',
    'TrustedPublicKeyBase64Url',
    'ValidatePattern\("\^\[A-Za-z0-9\._-\]\{1,64\}\$"\)',
    '--expected-public-key',
    'sign_package_update_manifest',
    '--locked',
    'Get-FileHash',
    '\.package-update-publish-',
    'Immutable generation already exists',
    'ExpectedPriorPointerSha256',
    'ExpectedPriorEnvelopeSha256',
    'ExpectPriorAbsent',
    'Refresh',
    'authenticate_package_update_manifest',
    'artifacts\\sha256',
    'Assert-PackageUpdatePointerUnchanged',
    'aapt2\.exe',
    'apksigner\.bat',
    '\[System\.IO\.File\]::Move\(',
    'current\.json',
    'rusty.quest.package_update_publication_receipt.v3',
    'generations')) {
    Assert-Match $publishWrapper $token `
        "Signed Package Updater publication wrapper is missing token: $token"
}

$allJava = (
    Get-ChildItem -LiteralPath $javaRoot -Filter "*.java" -File |
        Sort-Object FullName |
        ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName }
) -join "`n"
foreach ($forbidden in @(
    'AccessibilityService',
    'DevicePolicyManager',
    'MediaProjection',
    'CameraManager',
    'AudioRecord',
    'RECORD_AUDIO',
    'SYSTEM_ALERT_WINDOW',
    'MANAGE_EXTERNAL_STORAGE',
    'READ_EXTERNAL_STORAGE',
    'WRITE_EXTERNAL_STORAGE',
    'QUERY_ALL_PACKAGES',
    'Runtime\.getRuntime\(\)\.exec',
    'ProcessBuilder',
    '\badb\b',
    'ServerSocket',
    'setInstanceFollowRedirects\(true\)')) {
    if ($allJava -match $forbidden) {
        throw "Package Updater crosses a forbidden authority boundary: $forbidden"
    }
}

foreach ($token in @(
    'REQUEST_INSTALL_PACKAGES',
    'unknown-app source',
    'approve\s+each Package Installer confirmation',
    'default public-key value is empty',
    'fails closed',
    'never taken\s+from an Intent',
    'co-installable',
    'current\.json',
    'uninstall-labs-without-changing-stable')) {
    Assert-Match $readme $token "Package Updater guide is missing boundary token: $token"
}

$javac = (Get-Command "javac" -ErrorAction Stop).Source
$java = (Get-Command "java" -ErrorAction Stop).Source
$temporaryRoot = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine(
        [System.IO.Path]::GetTempPath(),
        "rusty-quest-package-updater-vector-$([guid]::NewGuid().ToString('N'))"
    )
)
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    & $javac -encoding UTF-8 -d $temporaryRoot `
        (Join-Path $javaRoot "UpdateArtifact.java") `
        $paths.canonicalizer `
        $paths.host_vector `
        $paths.post_install_policy `
        $paths.host_post_install_policy
    if ($LASTEXITCODE -ne 0) {
        throw "Package Updater Java/Rust canonical vector did not compile."
    }
    $vectorOutput = & $java -cp $temporaryRoot `
        "io.github.mesmerprism.rustyquest.packageupdater.PackageUpdaterCanonicalVectorTest"
    if ($LASTEXITCODE -ne 0 -or
        ($vectorOutput -join "`n") -notmatch
            "Package Updater Java/Rust canonical vector passed") {
        throw "Package Updater Java/Rust canonical vector failed."
    }
    $expiryOutput = & $java -cp $temporaryRoot `
        "io.github.mesmerprism.rustyquest.packageupdater.PostInstallCheckpointPolicyTest"
    if ($LASTEXITCODE -ne 0 -or
        ($expiryOutput -join "`n") -notmatch
            "Post-install checkpoint expiry policy passed") {
        throw "Package Updater post-install expiry boundary test failed."
    }
} finally {
    $systemTemp = [System.IO.Path]::GetFullPath(
        [System.IO.Path]::GetTempPath()
    )
    if (-not $temporaryRoot.StartsWith(
            $systemTemp,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove unexpected canonical-vector directory: $temporaryRoot"
    }
    if ([System.IO.Directory]::Exists($temporaryRoot)) {
        [System.IO.Directory]::Delete($temporaryRoot, $true)
    }
}

$rollbackSchemaPath = Join-Path $RepoRoot `
    "schemas\rusty.quest.package_update_rollback_state.v1.schema.json"
$isolationFixturePath = Join-Path $RepoRoot `
    "fixtures\package-updater\channel-isolation-cases.json"
foreach ($requiredPath in @($rollbackSchemaPath, $isolationFixturePath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Package Updater channel-isolation contract is missing: $requiredPath"
    }
}
$rollbackSchema = Get-Content -Raw -LiteralPath $rollbackSchemaPath
$isolationFixture = Get-Content -Raw -LiteralPath $isolationFixturePath
foreach ($token in @(
    '"channel"',
    '"signer_sha256"',
    '"key_id"',
    '"public_key"',
    '"https_origin"'
)) {
    Assert-Match $rollbackSchema ([regex]::Escape($token)) `
        "Normative rollback schema is missing full-tuple field: $token"
}
foreach ($code in @(
    "channel_mismatch",
    "package_policy_mismatch",
    "signer_policy_mismatch",
    "key_id_mismatch",
    "public_key_mismatch",
    "origin_mismatch"
)) {
    Assert-Match $isolationFixture ([regex]::Escape($code)) `
        "Channel-isolation fixture is missing rejection: $code"
}
Assert-Match $appBuild `
    'applicationId = "io\.github\.mesmerprism\.rustyquest\.packageupdater\.labs"' `
    "Release updater package is not Labs-isolated."
Assert-Match $publishWrapper 'package_update_publication_receipt\.v3' `
    "Publication output is not the deterministic receipt contract."
foreach ($leak in @('aapt2_path', 'apksigner_path')) {
    if ($publishWrapper -match [regex]::Escape($leak) -or
        $buildWrapper -match [regex]::Escape($leak)) {
        throw "Public Package Updater receipt retains local tool path field: $leak"
    }
}

& pwsh -NoProfile -ExecutionPolicy Bypass -File `
    $paths.publication_contract_test
if ($LASTEXITCODE -ne 0) {
    throw "Package update publication contract self-test failed."
}
& pwsh -NoProfile -ExecutionPolicy Bypass -File `
    $paths.build_artifact_contract_test
if ($LASTEXITCODE -ne 0) {
    throw "Package updater build artifact contract self-test failed."
}
& pwsh -NoProfile -ExecutionPolicy Bypass -File `
    $paths.product_release_contract_test
if ($LASTEXITCODE -ne 0) {
    throw "Package updater product release contract self-test failed."
}
& pwsh -NoProfile -ExecutionPolicy Bypass -File `
    $paths.alpha_release_workflow_test -RepoRoot $RepoRoot
if ($LASTEXITCODE -ne 0) {
    throw "Package updater Labs workflow contract failed."
}
& pwsh -NoProfile -ExecutionPolicy Bypass -File `
    $paths.pages_workflow_test -RepoRoot $RepoRoot
if ($LASTEXITCODE -ne 0) {
    throw "Package update Labs Pages workflow contract failed."
}

Write-Output "Rusty Quest Package Updater Android static validation passed"
