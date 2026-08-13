[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
)

$ErrorActionPreference = "Stop"

function Read-Required([string]$RelativePath) {
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required source is missing: $RelativePath"
    }
    [System.IO.File]::ReadAllText($path)
}

function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

$panel = Read-Required "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\PrivateLayerControlPanel.kt"
$zone = Read-Required "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\PrivateLayerZoneCompositor.kt"
$guard = Read-Required "apps\spatial-camera-panel-android\native-receipt\src\camera_reprojection_guard_band.rs"
$probe = Read-Required "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_probe.rs"
$appClient = Read-Required "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\ConnectionHubWearerControlClient.kt"
$surfaceClient = Read-Required "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\ConnectionHubSurfaceClient.kt"
$surfaceTarget = Read-Required "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\ConnectionHubSurfaceTarget.kt"
$brokerProvider = Read-Required "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\ConnectionHubWearerControlProvider.java"
$brokerBuild = Read-Required "tools\Build-ManifoldBrokerAndroid.ps1"
$activity = Read-Required "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"

Require ($panel.Contains('DepthSlider("Guard size", configuration.bufferStaticWidthUv, 0.0f..0.2f)')) "Panel must expose one bounded guard-size control."
Require (-not $panel.Contains('Static buffer width')) "The removed independent Static buffer-width label must not return."
Require ($panel.Contains('configuration.bufferGeometryMode != controls.bufferGeometryOff')) "Static and Dynamic must share the guard-size control."
Require ($zone.Contains('coerceIn(0.0f, 0.2f)')) "Stored guard size must obey the real source limit."
Require ($zone.Contains('projectionZoneGuardSizeSingleAuthority=true')) "Markers must declare the single guard authority."
Require ($guard.Contains('update_for_projection_buffer')) "Render ingress must expose the product Buffer guard policy."
Require ($guard.Contains('0 => (0.0, CameraLatencyReprojectionGuardBandMode::ZoomToFill)')) "Buffer Off must restore a full footprint."
Require ($guard.Contains('CameraLatencyReprojectionGuardBandMode::ReducedFootprint')) "Static must contract the footprint."
Require ($guard.Contains('CameraLatencyReprojectionGuardBandMode::DynamicReducedFootprint')) "Dynamic must grow from the same minimum guard."
Require ($probe.Contains('camera_reprojection_guard_band.update_for_projection_buffer(')) "The live camera path must consume the product Buffer guard policy."

foreach ($method in @('ACTION_START', 'ACTION_STOP', 'ACTION_STATUS')) {
    Require ($brokerProvider.Contains("ConnectionHubOperatorController.$method")) "Wearer provider is missing $method."
}
foreach ($forbidden in @('ACTION_PAIR', 'ACTION_REVOKE', 'ACTION_FORGET', 'pairing_code', 'session_b64')) {
    Require (-not $brokerProvider.Contains($forbidden)) "Wearer provider must not expose $forbidden."
}
Require ($brokerBuild.Contains('android:name=".ConnectionHubWearerControlProvider"')) "Broker artifact must package the wearer-control provider."
Require ($brokerBuild.Contains('android:permission="io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION"')) "Wearer control must remain signature-scoped."
Require ($appClient.Contains('secrets_in_snapshot')) "Client must enforce the secret-free snapshot boundary."
Require ($appClient.Contains('caller_selected_authority')) "Client must enforce the fixed-authority boundary."
Require ($appClient.Contains('HandlerThread(WORKER_THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND)')) "Wearer provider calls must have a background worker."
Require ($appClient.Contains('fun status(): ConnectionHubWearerControlSnapshot = latestSnapshot')) "Panel status reads must be local-only."
Require ($appClient.Contains('workerHandler.post {') -and $appClient.Contains('invokeBlocking(method)')) "Provider calls must be dispatched to the wearer worker."
Require ($panel.Contains('Text("Start Hub")') -and $panel.Contains('Text("Stop Hub")')) "Panel must expose Start and Stop controls."
Require ($panel.Contains('Headset controller input, panel reopening')) "Panel must state controller independence."
Require (-not $panel.Contains('val latestConnectionHub = connectionHubStatus()')) "Compose refresh cadence must not poll the broker."
Require ($panel.Contains('DisposableEffect(Unit)') -and $panel.Contains('observeConnectionHub')) "Compose must observe local Hub snapshots."
Require ($activity.Contains('dispatchGenericMotionEvent') -and $activity.Contains('connectionHubWearerControlClient')) "Controller routing and Hub lifecycle must remain independently owned."
Require (-not $activity.Contains('connectionHubSurfaceClient?.refresh()')) "The XR scene tick must not reconcile Hub work."
Require ($activity.Contains('connectionHubShouldOwnSurfaceClient(connectionHubActivityStarted, snapshot)')) "Only the explicit Hub lifecycle policy may own the surface client."
Require ($surfaceClient.Contains('private val handler = ProviderHandler(workerThread.looper)')) "Binder replies and reducer effects must use the Hub worker looper."
Require ($surfaceClient.Contains('handler.post {') -and $surfaceClient.Contains('override fun onServiceConnected')) "Service callbacks must hand off to the Hub worker."
Require (-not $surfaceClient.Contains('AVAILABILITY_RECONCILE_INTERVAL_MS')) "Inactive playlists must not retain an availability poll timer."
Require ($surfaceClient.Contains('setHubSurfaceChangeObserver(surfaceChangeObserver)')) "Surface registration must be owner-change driven."
Require ($surfaceClient.Contains('connectionHubSurfaceStatePublishDelayMs(surfaceAvailable = true, state)')) "Only the explicit active-state policy may retain progress publication cadence."
Require ($surfaceTarget.Contains('setHubSurfaceChangeObserver(observer: (() -> Unit)?) = Unit')) "Optional targets must default to an inert observer boundary."

Write-Output "Spatial Camera Panel Buffer/Hub controls static checks passed."
