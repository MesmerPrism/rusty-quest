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
    'android\.app\.NativeActivity',
    'android\.app\.lib_name',
    'rusty_quest_native_renderer',
    'android:hasCode="false"',
    'org\.khronos\.openxr\.runtime_broker'
) "Android manifest NativeActivity route"

Assert-ContainsTokens $nativeLib @(
    'RUSTY_QUEST_NATIVE_RENDERER',
    'android_main',
    'android_on_create',
    'android_activity::AndroidApp',
    'validate_native_renderer_plan',
    'rustNativeActivity=true',
    'javaPackaged=false',
    'requestPermissions',
    'rust-native-jni',
    'publicEffectLayers=blur-guide,peripheral-stretch-border',
    'privatePayloads=false',
    'minimal-projection-layer',
    'recordedHandReplayRequested=true',
    'finalExternalHwbSamples=0',
    'guideTextureSamples=1',
    'openxrProjectionLayer=runtime-submit',
    'openxrSubmitReady=false',
    'vulkanExternalImportReady=false'
) "Rust NativeActivity scaffold"

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
    'rusty\.quest\.native_renderer_android\.build_manifest\.v1'
) "native renderer build script"
foreach ($token in @('javac', 'd8\.bat', 'classes\.dex', 'clang\+\+')) {
    if ($buildScriptText -match $token) {
        throw "Native renderer build script still carries Java/C++ packaging token: $token"
    }
}

Assert-ContainsTokens $readme @(
    'Rust NativeActivity',
    'no app Java is packaged',
    'real submitted OpenXR',
    'input queue'
) "native renderer app README"

Write-Host "Rusty Quest native renderer Android scaffold static validation passed"
