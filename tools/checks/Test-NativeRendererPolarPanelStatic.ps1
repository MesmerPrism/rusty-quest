param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = Resolve-Path $RepoRoot

function Read-RequiredText {
    param(
        [string]$Path,
        [string]$Label
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing Polar panel static file ($Label): $Path"
    }
    return Get-Content -Raw -LiteralPath $Path
}

function Assert-ContainsTokens {
    param(
        [string]$Text,
        [string[]]$Tokens,
        [string]$Label
    )
    foreach ($token in $Tokens) {
        if ($Text -notmatch $token) {
            throw "Polar panel static check failed for ${Label}: missing token: $token"
        }
    }
}

$appRoot = Join-Path $repoRootPath "apps\native-renderer-android"
$javaRoot = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustyquest\native_renderer"
$manifest = Read-RequiredText (Join-Path $appRoot "AndroidManifest.xml") "Android manifest"
$controlPanel = Read-RequiredText (Join-Path $javaRoot "ControlPanelActivity.java") "control panel"
$polarPanel = Read-RequiredText (Join-Path $javaRoot "PolarSensorPanel.java") "Polar panel"
$polarRuntimeSupport = Read-RequiredText (Join-Path $javaRoot "PolarBleRuntimeSupport.java") "Polar runtime support"
$polarAdapters = Read-RequiredText (Join-Path $appRoot "native\src\polar_composition_adapters.rs") "Polar composition adapters"
$polarAccBreathAdapter = Read-RequiredText (Join-Path $appRoot "native\src\polar_acc_breath_adapter.rs") "calibrated Polar ACC adapter"
$breathAdapter = Read-RequiredText (Join-Path $appRoot "native\src\private_particle_breath_state_driver.rs") "breath-state adapter"
$heartbeatAdapter = Read-RequiredText (Join-Path $appRoot "native\src\private_particle_heartbeat_pulse_adapter.rs") "heartbeat-pulse adapter"
$panelAction = Read-RequiredText (Join-Path $appRoot "native\src\same_apk_panel_action.rs") "same-APK panel action"
$panelBridge = Read-RequiredText (Join-Path $appRoot "native\src\native_renderer_panel_bridge.rs") "same-APK panel bridge"
$feature = Read-RequiredText (Join-Path $repoRootPath "fixtures\native-app-features\sensors\polar-h10-ble\sensor.polar_h10_ble.feature.json") "Polar feature"
$propertyManifest = Read-RequiredText (Join-Path $repoRootPath "fixtures\native-renderer\native-renderer-property-manifest.json") "property manifest"
$resolver = Read-RequiredText (Join-Path $repoRootPath "tools\Resolve-NativeAppBuild.ps1") "native app-build resolver"
$pregrant = Read-RequiredText (Join-Path $repoRootPath "tools\Grant-NativeRendererPermissions.ps1") "permission pregrant"

Assert-ContainsTokens $manifest @(
    'android\.hardware\.bluetooth_le',
    'android\.permission\.ACCESS_COARSE_LOCATION',
    'android\.permission\.ACCESS_FINE_LOCATION',
    'android\.permission\.BLUETOOTH',
    'android\.permission\.BLUETOOTH_ADMIN',
    'android\.permission\.BLUETOOTH_CONNECT',
    'android\.permission\.BLUETOOTH_SCAN'
) "development manifest BLE surface"

Assert-ContainsTokens $controlPanel @(
    'polar-sensor',
    'PolarSensorPanel',
    'onRequestPermissionsResult',
    'closePanelAndReturnToImmersive'
) "ControlPanelActivity Polar mode"

Assert-ContainsTokens "$polarPanel`n$polarRuntimeSupport" @(
    'final class PolarSensorPanel',
    'BluetoothLeScanner',
    'ScanCallback',
    'connectGatt',
    'requestPermissions',
    'BLUETOOTH_SCAN',
    'BLUETOOTH_CONNECT',
    'ACCESS_COARSE_LOCATION',
    'ACCESS_FINE_LOCATION',
    'HEART_RATE_MEASUREMENT',
    'PMD_CONTROL_POINT',
    'PMD_DATA',
    'stream\.polar_h10\.hr_rr',
    'stream\.polar_h10\.acc',
    'stream\.polar_h10\.ecg',
    'stream\.polar_h10\.device_status',
    'polar_stream_events\.jsonl',
    'rusty\.manifold\.stream\.event\.v1',
    'decodeHeartRateMeasurement',
    'decodeAcc',
    'decodeEcg',
    'buildStartCommand',
    'nativeSubmitPolarRrMeasurement',
    'nativeSubmitPolarAccMeasurement',
    'RUSTY_QUEST_NATIVE_RENDERER',
    'polar-sensor-panel'
) "Polar BLE panel implementation"

Assert-ContainsTokens "$polarPanel`n$polarRuntimeSupport" @(
    'SCAN_MODE_LOW_LATENCY',
    'platformFilter=empty',
    'PENDING_BLE_SCAN',
    'resumePendingBleAction',
    'quest-nearby-devices-plus-location',
    'polar_sensor_status\.v2',
    'rr_consumed_by_breath',
    'rawDeviceIdentifierLogged=false'
) "Quest Polar discovery and structured readback"

Assert-ContainsTokens "$polarAdapters`n$breathAdapter`n$heartbeatAdapter`n$panelAction`n$panelBridge" @(
    'PolarAccBreathSource',
    'PolarRrPulseSource',
    'RR_QUEUE_CAPACITY',
    'SourceState::Disabled',
    'SourceState::Missing',
    'SourceState::Malformed',
    'SourceState::Stale',
    'polar_acc_synthetic_conformance_maps_configured_axis',
    'polar_acc_output_is_bounded',
    'rr_synthetic_conformance_emits_one_normalized_pulse',
    'valid_event_emits_one_bounded_frame_then_returns_to_zero',
    'right-secondary-triple-press-toggle',
    'exactly_three_press_edges_trigger_one_deterministic_action',
    'timeout_discards_incomplete_sequence',
    'ControlPanelActivity'
) "disabled-by-default Polar composition adapters"

Assert-ContainsTokens $polarAccBreathAdapter @(
    'PolarAccBreathAdapter',
    'TimedPolarAccFrame',
    'from_pmd_measurement',
    'PolarAccProjection',
    'CalibrationProjectionSpace::Xz',
    'CalibrationProjectionSpace::Full3d',
    'PolarAccelerationUnit',
    'SensorTimestampOutOfOrder',
    'CommonPhaseClassifier',
    'CommonPhaseResetReason',
    'polarAccPhaseCandidate',
    'polarAccPhaseFilteredDerivativePerSecond',
    'calibrated_projection_drives_confirmed_exhale_hold_inhale_and_resets_on_missing',
    'ready_volume_is_bounded_and_responds_before_the_next_analysis_tick',
    'calibration_failure_can_retry_with_a_fresh_generation',
    'deterministic_action_replay_is_value_equal_in_memory'
) "calibrated Polar ACC assessment adapter"

foreach ($forbidden in @('WebView', 'addJavascriptInterface', 'androidx', 'AppSystemActivity', 'VrActivity', 'GLXF')) {
    if ($polarPanel -match $forbidden) {
        throw "Polar panel should not carry WebView/Spatial SDK token: $forbidden"
    }
}

Assert-ContainsTokens $feature @(
    '"feature_id": "sensor\.polar_h10_ble"',
    '"module_path": "sensor/polar-h10-ble"',
    '"ui\.same_apk_control_panel"',
    '"android\.permission\.ACCESS_COARSE_LOCATION"',
    '"android\.permission\.BLUETOOTH_SCAN"',
    '"android\.permission\.BLUETOOTH_CONNECT"',
    '"android\.hardware\.bluetooth_le"',
    '"stream\.polar_h10\.hr_rr"',
    '"stream\.polar_h10\.acc"',
    '"stream\.polar_h10\.ecg"',
    'Test-NativeRendererPolarPanelStatic\.ps1'
) "Polar native app feature"

Assert-ContainsTokens "$resolver`n$pregrant" @(
    'android\.permission\.ACCESS_COARSE_LOCATION',
    'android\.permission\.ACCESS_FINE_LOCATION',
    'android\.permission\.BLUETOOTH_CONNECT',
    'android\.permission\.BLUETOOTH_SCAN'
) "BLE permission pregrant workflow"

Assert-ContainsTokens $propertyManifest @(
    'debug\.rustyquest\.native_renderer\.control_panel\.mode',
    'polar-sensor'
) "control-panel property allowlist"

Write-Host "Rusty Quest Polar panel static validation passed"
