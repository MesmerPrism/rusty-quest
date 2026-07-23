param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

$paths = [ordered]@{
    workspace = Join-Path $RepoRoot "Cargo.toml"
    crate = Join-Path $RepoRoot "crates\rusty-quest-fleet-agent\Cargo.toml"
    source = Join-Path $RepoRoot "crates\rusty-quest-fleet-agent\src\lib.rs"
    guide = Join-Path $RepoRoot "docs\FLEET_AGENT.md"
    default_profile = Join-Path $RepoRoot "fixtures\fleet-agent\fleet-agent.disabled.profile.json"
    golden_claims = Join-Path $RepoRoot "fixtures\fleet-agent\checkin-claims-golden.valid.json"
    golden_readme = Join-Path $RepoRoot "fixtures\fleet-agent\README.md"
    manifest = Join-Path $RepoRoot "apps\fleet-agent-android\AndroidManifest.xml"
    app_readme = Join-Path $RepoRoot "apps\fleet-agent-android\README.md"
    native_crate = Join-Path $RepoRoot "apps\fleet-agent-android\native\Cargo.toml"
    native_source = Join-Path $RepoRoot "apps\fleet-agent-android\native\src\lib.rs"
    activity = Join-Path $RepoRoot "apps\fleet-agent-android\src\main\java\io\github\mesmerprism\rustyquest\fleetagent\FleetAgentActivity.java"
    service = Join-Path $RepoRoot "apps\fleet-agent-android\src\main\java\io\github\mesmerprism\rustyquest\fleetagent\FleetAgentService.java"
    config = Join-Path $RepoRoot "apps\fleet-agent-android\src\main\java\io\github\mesmerprism\rustyquest\fleetagent\FleetAgentConfig.java"
    key = Join-Path $RepoRoot "apps\fleet-agent-android\src\main\java\io\github\mesmerprism\rustyquest\fleetagent\FleetAgentPrivateKey.java"
    observation = Join-Path $RepoRoot "apps\fleet-agent-android\src\main\java\io\github\mesmerprism\rustyquest\fleetagent\FleetAgentObservation.java"
    publisher = Join-Path $RepoRoot "apps\fleet-agent-android\src\main\java\io\github\mesmerprism\rustyquest\fleetagent\FleetAgentPublisher.java"
    receipt = Join-Path $RepoRoot "apps\fleet-agent-android\src\main\java\io\github\mesmerprism\rustyquest\fleetagent\FleetAgentReceipt.java"
    native_bridge = Join-Path $RepoRoot "apps\fleet-agent-android\src\main\java\io\github\mesmerprism\rustyquest\fleetagent\FleetAgentNativeBridge.java"
    build = Join-Path $RepoRoot "tools\Build-FleetAgentAndroid.ps1"
}
foreach ($entry in $paths.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) {
        throw "Missing Fleet Agent surface $($entry.Key): $($entry.Value)"
    }
}

function Assert-Match([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) {
        throw $Message
    }
}

$workspace = Get-Content -Raw -LiteralPath $paths.workspace
$crate = Get-Content -Raw -LiteralPath $paths.crate
$source = Get-Content -Raw -LiteralPath $paths.source
$guide = Get-Content -Raw -LiteralPath $paths.guide
$profile = Get-Content -Raw -LiteralPath $paths.default_profile | ConvertFrom-Json
$golden = Get-Content -Raw -LiteralPath $paths.golden_readme
$manifest = Get-Content -Raw -LiteralPath $paths.manifest
$activity = Get-Content -Raw -LiteralPath $paths.activity
$service = Get-Content -Raw -LiteralPath $paths.service
$config = Get-Content -Raw -LiteralPath $paths.config
$key = Get-Content -Raw -LiteralPath $paths.key
$observation = Get-Content -Raw -LiteralPath $paths.observation
$publisher = Get-Content -Raw -LiteralPath $paths.publisher
$receipt = Get-Content -Raw -LiteralPath $paths.receipt
$nativeBridge = Get-Content -Raw -LiteralPath $paths.native_bridge
$nativeSource = Get-Content -Raw -LiteralPath $paths.native_source
$build = Get-Content -Raw -LiteralPath $paths.build

Assert-Match $workspace '"crates/rusty-quest-fleet-agent"' "Workspace must include the Fleet Agent contract crate."
Assert-Match $crate 'rev = "8181683be4a3abbc5daa0c4497c7aeb9e76316a8"' "Fleet contract dependency must remain pinned to the accepted public revision."
Assert-Match $crate 'ed25519-dalek = \{ version = "=2\.1\.1"' "Fleet Agent must retain the qualified Ed25519 implementation."
if ($crate -match '(?m)^\s*(tokio|reqwest|hyper|axum|jni|ndk|windows)\s*=') {
    throw "Source-only Fleet Agent contract crate must not activate transport or platform dependencies."
}

foreach ($token in @(
    'CHECKIN_SIGNATURE_ALGORITHM',
    'claims.signing_bytes',
    'received_time_ms: 0',
    'ForegroundAuthority::PlatformLimited',
    'ForegroundAuthority::ParticipatingApp',
    'FLEET_AGENT_PACKAGE',
    'capability.monitoring',
    'signing_identity_mismatch',
    'agent_disabled')) {
    Assert-Match $source ([regex]::Escape($token)) "Fleet Agent contract source is missing boundary token: $token"
}
foreach ($forbidden in @(
    'Command::new\("adb"\)|process::Command.*adb',
    'MediaCodec',
    'CameraManager',
    'WifiP2pManager',
    'Bluetooth',
    'UsageStatsManager',
    'AccessibilityService',
    'QUERY_ALL_PACKAGES',
    'java\.net\.ServerSocket')) {
    if ($source -match $forbidden) {
        throw "Fleet Agent contract source crosses a forbidden baseline boundary: $forbidden"
    }
}

if ($profile.schema -ne "rusty.quest.fleet_agent_profile.v1" -or $profile.enabled -ne $false) {
    throw "Committed Fleet Agent profile must remain the v1 inert default."
}
if ($profile.hub_endpoint -notmatch '^http://192\.0\.2\.') {
    throw "Inert fixture endpoint must stay in the documentation-only address range."
}
Assert-Match $golden '8181683be4a3abbc5daa0c4497c7aeb9e76316a8' "Golden fixture provenance must name the accepted Fleet revision."
Assert-Match $golden 'a9dd28a3681ccd242fee648a7010b85a69df38147f487e8c4e7e2b08116b8432' "Golden signing-message digest is missing."
Assert-Match $guide 'The producer creates proposals' "Guide must preserve the proposal-only authority boundary."
Assert-Match $guide 'host-owned receive time|Hub owns receive time' "Guide must preserve host-owned receive time."

Assert-Match $manifest 'package="io\.github\.mesmerprism\.rustyquest\.fleetagent"' "Fleet Agent package identity is wrong."
$permissions = @([regex]::Matches($manifest, '<uses-permission android:name="([^"]+)"') |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique)
$expectedPermissions = @(
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.INTERNET",
    "android.permission.POST_NOTIFICATIONS"
) | Sort-Object
if (@(Compare-Object $permissions $expectedPermissions -SyncWindow 0).Count -ne 0) {
    throw "Fleet Agent manifest permission closure changed: $($permissions -join ', ')"
}
Assert-Match $manifest 'android:exported="false"\s+android:foregroundServiceType="dataSync"' "Fleet Agent service must remain non-exported dataSync."
Assert-Match $manifest 'android:allowBackup="false"' "Fleet Agent app-private enrollment data must not be backed up."
Assert-Match $manifest 'android:usesCleartextTraffic="true"' "Local M1 cleartext support must remain explicit and runtime-scoped."

Assert-Match $activity 'Inactive on ordinary launch' "Fleet Agent launcher must remain inert."
if ($activity -match 'startForegroundService|startService') {
    throw "Fleet Agent launcher must not activate the service."
}
foreach ($token in @(
    'ACTION_START',
    'ACTION_STOP',
    'START_NOT_STICKY',
    'FleetAgentConfig.load',
    'FleetAgentPrivateKey.load',
    'Executors.newSingleThreadScheduledExecutor',
    'RUSTY_QUEST_FLEET_AGENT_SERVICE_START',
    'RUSTY_QUEST_FLEET_AGENT_SERVICE_STOP')) {
    Assert-Match $service ([regex]::Escape($token)) "Fleet Agent service is missing lifecycle token: $token"
}
if ($service -match 'get.*Extra\(') {
    throw "Fleet Agent service must not accept profile, endpoint, or key overrides from intent extras."
}
Assert-Match $config 'filesDir|context\.getFilesDir' "Fleet Agent profile must be app-private."
Assert-Match $config 'cleartext_hub_must_be_local' "Cleartext endpoints must fail closed outside the local address set."
foreach ($localRange in @('127\.', '10\.', '192\.168\.', '169\.254\.', '172\.')) {
    Assert-Match $config $localRange "Fleet Agent local endpoint validation is missing $localRange"
}
Assert-Match $key 'signing-seed\.bin' "Fleet Agent signing seed must use the app-private file contract."
Assert-Match $key 'seed\.length != 32' "Fleet Agent signing seed length must fail closed."
Assert-Match $observation 'BATTERY_PROPERTY_CAPACITY' "Fleet Agent must use Android battery authority."
Assert-Match $observation '"participating_application", JSONObject\.NULL' "Baseline must not invent participating-app evidence."
Assert-Match $publisher 'MAX_REQUEST_BYTES = 256 \* 1024' "Fleet Agent request bound changed."
Assert-Match $publisher 'MAX_RESPONSE_BYTES = 64 \* 1024' "Fleet Agent response bound changed."
Assert-Match $publisher 'setInstanceFollowRedirects\(false\)' "Fleet Agent must not follow endpoint redirects."
Assert-Match $publisher 'TIMEOUT_MS = 5_000' "Fleet Agent network timeouts must remain bounded."
Assert-Match $receipt '"offline_queue_depth", 0' "Fleet Agent must record the no-offline-queue boundary."
Assert-Match $nativeBridge 'byte\[\] privateSeed' "JNI must carry the seed as mutable bytes rather than a String."
Assert-Match $nativeSource 'produce_signed_checkin' "JNI must invoke the shared Quest producer."
Assert-Match $nativeSource 'invalid_private_seed' "JNI must reject non-32-byte seeds."
Assert-Match $build 'rusty\.quest\.fleet_agent_android\.build\.v1' "Fleet Agent build manifest schema missing."
Assert-Match $build 'aarch64-linux-android' "Fleet Agent build must package the native arm64 bridge."
Assert-Match $build 'adb_runtime_dependency = \$false' "Fleet Agent build receipt must reject an ADB runtime dependency."
Assert-Match $build 'Get-QuestBuildSourceComposition' "Fleet Agent build must bind the exact clean source composition."
Assert-Match $build 'rusty\.quest\.apk_run_capsule\.v1' "Fleet Agent build must emit a run capsule."
Assert-Match $build 'Test-ApkRunCapsule\.ps1' "Fleet Agent build must validate its generated capsule."
Assert-Match $build 'fleet-agent-android\\builds\\fleet-agent' "Fleet Agent output must be content addressed."
Assert-Match $build 'PSVersionTable\.PSVersion -lt \[version\]"7\.6"' "Fleet Agent build must enforce the PowerShell 7.6 host contract."

$appSource = @(
    $manifest,
    $activity,
    $service,
    $config,
    $key,
    $observation,
    $publisher,
    $receipt,
    $nativeBridge,
    $nativeSource) -join "`n"
foreach ($forbidden in @(
    'QUERY_ALL_PACKAGES',
    'PACKAGE_USAGE_STATS',
    'AccessibilityService',
    'MediaProjection',
    'MediaCodec',
    'CameraManager',
    'WifiP2pManager',
    'BluetoothAdapter',
    'MANAGE_EXTERNAL_STORAGE',
    'READ_EXTERNAL_STORAGE',
    'WRITE_EXTERNAL_STORAGE',
    'ServerSocket',
    'command_listener')) {
    if ($appSource -match $forbidden) {
        throw "Fleet Agent Android baseline crosses a forbidden boundary: $forbidden"
    }
}

Write-Output "Rusty Quest Fleet Agent Android static validation passed"
