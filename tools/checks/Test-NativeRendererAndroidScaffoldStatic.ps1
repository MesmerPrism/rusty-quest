param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = Resolve-Path $RepoRoot
$appRoot = Join-Path $repoRootPath "apps\native-renderer-android"
$nativeRoot = Join-Path $appRoot "native"
$srcRoot = Join-Path $nativeRoot "src"

function Read-RequiredText {
    param(
        [string]$Path,
        [string]$Label
    )
    if (-not (Test-Path $Path)) {
        throw "Missing native renderer Android scaffold static file ($Label): $Path"
    }
    return Get-Content -Raw -Path $Path
}

function Assert-ContainsTokens {
    param(
        [string]$Text,
        [string[]]$Tokens,
        [string]$Label
    )
    foreach ($token in $Tokens) {
        if ($Text -notmatch $token) {
            throw "Native renderer Android scaffold static check failed for ${Label}: missing token: $token"
        }
    }
}

$manifest = Read-RequiredText (Join-Path $appRoot "AndroidManifest.xml") "Android manifest"
$readme = Read-RequiredText (Join-Path $appRoot "README.md") "app README"
$nativeCargo = Read-RequiredText (Join-Path $nativeRoot "Cargo.toml") "native Cargo manifest"
$nativeLib = Read-RequiredText (Join-Path $srcRoot "lib.rs") "native lib"
$androidEvents = Read-RequiredText (Join-Path $srcRoot "android_events.rs") "Android event pump"
$panelBridge = Read-RequiredText (Join-Path $srcRoot "native_renderer_panel_bridge.rs") "stimulus panel JNI bridge"
$stimulusPanel = Read-RequiredText (Join-Path $srcRoot "native_renderer_stimulus_panel.rs") "stimulus panel candidate adapter"
$controlPanel = Read-RequiredText (Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustyquest\native_renderer\ControlPanelActivity.java") "control panel Activity"
$xrVulkan = Read-RequiredText (Join-Path $srcRoot "xr_vulkan.rs") "xr_vulkan facade"
$buildScriptText = Read-RequiredText (Join-Path $repoRootPath "tools\Build-NativeRendererAndroid.ps1") "native Android build script"

if ($manifest -notmatch 'package="io\.github\.mesmerprism\.rustyquest\.native_renderer"') {
    throw "Native renderer Android manifest has the wrong package."
}
Assert-ContainsTokens $manifest @(
    'android\.permission\.CAMERA',
    'android\.permission\.INTERNET',
    'com\.oculus\.permission\.HAND_TRACKING',
    'horizonos\.permission\.HEADSET_CAMERA',
    'horizonos\.permission\.SPATIAL_CAMERA',
    'horizonos\.permission\.USE_SCENE',
    'org\.khronos\.openxr\.permission\.OPENXR',
    'org\.khronos\.openxr\.permission\.OPENXR_SYSTEM'
) "Android manifest permissions"
Assert-ContainsTokens $manifest @(
    'android\.hardware\.vr\.headtracking',
    'com\.oculus\.feature\.PASSTHROUGH',
    'oculus\.software\.handtracking',
    'android\.hardware\.camera2\.full'
) "Android manifest features"
Assert-ContainsTokens $manifest @(
    'com\.oculus\.intent\.category\.VR',
    'com\.oculus\.intent\.category\.2D',
    'android\.app\.NativeActivity',
    'ControlPanelActivity',
    'android\.app\.lib_name',
    'rusty_quest_native_renderer',
    'android:hasCode="true"',
    'android:resizeableActivity="true"',
    '<layout',
    'org\.khronos\.openxr\.runtime_broker'
) "Android manifest NativeActivity and 2D panel routes"

Assert-ContainsTokens $controlPanel @(
    'final class ControlPanelActivity extends Activity',
    'stimulus_volume_candidate.json',
    'stimulus_volume_status.json',
    'rusty.quest.stimulus_volume.profile.v1',
    'rusty.quest.stimulus_volume.apply_status.v1',
    'same_apk_panel',
    'app_private_file',
    'photosensitive_risk_ack',
    'Request active stimulus after launch',
    'Enable right-primary randomize',
    'Live auto update',
    'Volumetric Pattern Panel',
    'GridLayout',
    'SeekBar',
    'buildChoiceGrid',
    'buildDynamicsJson',
    'spatial_oscillator_hz',
    'mirror_mode',
    'twist',
    'scramble',
    'jumble',
    'stretch',
    'Stage \+ Launch VR',
    'Apply Live',
    'apply-on-next-safe-frame',
    'scheduleLiveApplyFromControl',
    'nativeSubmitLiveStimulusCandidate',
    'System\.loadLibrary\("rusty_quest_native_renderer"\)',
    'ACTION_TOGGLE_PANEL',
    'ACTION_APPLY_LIVE_SELF_TEST',
    'onNewIntent',
    'onResume',
    'Close',
    'com.oculus.intent.category.VR',
    'android.app.NativeActivity',
    'readSystemProperty',
    'debug\.rustyquest\.native_renderer\.stimulus_volume\.render_target',
    'debug\.rustyquest\.native_renderer\.stimulus_volume\.enabled',
    'diagnostic_token',
    'handleDiagnosticIntent',
    'Diagnostic Apply Live self-test pending'
) "same-APK 2D control panel"
foreach ($token in @('WebView', 'addJavascriptInterface', 'androidx', 'AppSystemActivity', 'VrActivity', 'GLXF', 'Spatial SDK')) {
    if ($controlPanel -match $token) {
        throw "Native renderer control panel first slice should not carry WebView/Spatial SDK token: $token"
    }
}

Assert-ContainsTokens $nativeLib @(
    'RUSTY_QUEST_NATIVE_RENDERER',
    'android_main',
    'android_on_create',
    'android_activity::AndroidApp',
    'validate_native_renderer_plan',
    'rustNativeActivity=true',
    'javaPackaged=true',
    'panelActivity=ControlPanelActivity',
    'mod native_renderer_panel_bridge',
    'mod native_renderer_stimulus_panel',
    'apply_app_private_candidate',
    'requestPermissions',
    'rust-native-jni',
    'publicEffectLayers=blur-guide,peripheral-stretch-border,video-border-blend',
    'privatePayloads=false',
    'minimal-projection-layer',
    'recordedHandReplayRequested=true',
    'finalExternalHwbSamples=0',
    'guideTextureSamples=1',
    'openxrProjectionLayer=runtime-submit',
    'openxrSubmitReady=false',
    'vulkanExternalImportReady=false'
) "Rust NativeActivity scaffold"

Assert-ContainsTokens $panelBridge @(
    'toggle_control_panel',
    'ACTION_TOGGLE_PANEL',
    'ControlPanelActivity',
    'com.oculus.intent.category.2D',
    'FLAG_ACTIVITY_REORDER_TO_FRONT',
    'FLAG_ACTIVITY_SINGLE_TOP',
    'startActivity',
    'event=right-trigger-panel-toggle status=intent-sent'
) "Rust panel JNI toggle bridge"

Assert-ContainsTokens $stimulusPanel @(
    'stimulus_volume_candidate.json',
    'stimulus_volume_status.json',
    'rusty.quest.stimulus_volume.profile.v1',
    'rusty.quest.stimulus_volume.apply_status.v1',
    'apply_app_private_candidate',
    'status=applied transport=app-private-file',
    'status=rejected transport=app-private-file',
    'take_live_candidate',
    'write_live_status',
    'status=live-queued transport=jni-live-queue',
    'status=live-rejected transport=jni-live-queue',
    'Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeSubmitLiveStimulusCandidate',
    'NativeRendererRenderMode::SolidBlackStimulusVolume',
    'NativeRendererRenderMode::NativePassthroughStimulusVolume',
    'ProjectionTargetSettings::disabled_for_volume_only_route',
    'safety_ack_missing',
    'randomize_hz_out_of_range',
    'unsupported_pattern_family',
    'parse_startup_dynamics',
    'temporal_frequency_hz',
    'spatial_oscillator_hz',
    'source_shift',
    'unsupported_mirror_mode'
) "Rust panel candidate adapter"

Assert-ContainsTokens $xrVulkan @(
    'apply_live_stimulus_candidate',
    'render_mode_requires_restart',
    'render_target_requires_restart',
    'status=live-applied transport=jni-live-queue',
    'status=live-rejected transport=jni-live-queue',
    'update_stimulus_settings'
) "Rust live stimulus frame-loop adapter"

Assert-ContainsTokens $androidEvents @(
    'pump_activity_events',
    'MainEvent::InputAvailable',
    'input_events_iter',
    'InputStatus::Unhandled',
    'android-input',
    'event=drain'
) "Rust NativeActivity input pump"
if ($nativeLib -notmatch 'mod android_events' -or $xrVulkan -notmatch 'pump_activity_events') {
    throw "Rust NativeActivity input pump is not wired into both app and OpenXR loops."
}

Assert-ContainsTokens $nativeCargo @(
    'rusty-quest-native-renderer-contracts',
    'package = "rusty-quest-native-renderer"',
    'android-activity',
    'native-activity',
    'jni',
    'ndk-sys',
    'ash',
    'openxr',
    'crate-type = \["cdylib", "rlib"\]',
    'name = "rusty_quest_native_renderer"'
) "Rust native Cargo manifest"

Assert-ContainsTokens $buildScriptText @(
    'cargo build',
    'javac',
    'd8.bat',
    'classes.dex',
    'CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER',
    'aarch64-linux-android29-clang\.cmd',
    'librusty_quest_native_renderer\.so',
    'libopenxr_loader\.so',
    'native-hwb-blur-sdf-public\.plan\.json',
    'recorded-hand-replay-public-shape\.json',
    'RecordedHandCaptureDir',
    'RequireRecordedHandCapture',
    'recorded_hand_capture_required',
    'RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR',
    'gpu-mesh-boundary',
    'panel_candidate_file',
    'panel_status_file',
    'spatial_sdk_packaged = \$false',
    'rusty\.quest\.native_renderer_android\.build_manifest\.v1'
) "native renderer build script"
foreach ($token in @('clang\+\+', 'AppSystemActivity', 'VrActivity', 'GLXF')) {
    if ($buildScriptText -match $token) {
        throw "Native renderer build script carries unexpected Spatial SDK/C++ packaging token: $token"
    }
}

Assert-ContainsTokens $readme @(
    'Rust NativeActivity',
    'same-APK 2D control panel',
    'classes.dex',
    'stimulus_volume_candidate.json',
    'real submitted OpenXR',
    'input queue'
) "native renderer app README"

Write-Host "Rusty Quest native renderer Android scaffold static validation passed"
