param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = Resolve-Path $RepoRoot
$appRoot = Join-Path $repoRootPath "apps\kuramoto-spatial-sdk-android"
$sourceRoot = Join-Path $appRoot "app\src\main\java\io\github\mesmerprism\rustyquest\kuramoto_spatial"

function Read-RequiredText {
    param(
        [string]$Path,
        [string]$Label
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing Kuramoto Spatial SDK static file ($Label): $Path"
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
            throw "Kuramoto Spatial SDK static check failed for ${Label}: missing token: $token"
        }
    }
}

$settings = Read-RequiredText (Join-Path $appRoot "settings.gradle.kts") "settings"
$rootBuild = Read-RequiredText (Join-Path $appRoot "build.gradle.kts") "root build"
$gradleProperties = Read-RequiredText (Join-Path $appRoot "gradle.properties") "Gradle properties"
$versions = Read-RequiredText (Join-Path $appRoot "gradle\libs.versions.toml") "version catalog"
$appBuild = Read-RequiredText (Join-Path $appRoot "app\build.gradle.kts") "app build"
$manifest = Read-RequiredText (Join-Path $appRoot "app\src\main\AndroidManifest.xml") "manifest"
$ids = Read-RequiredText (Join-Path $appRoot "app\src\main\res\values\ids.xml") "ids"
$activity = Read-RequiredText (Join-Path $sourceRoot "KuramotoSpatialActivity.kt") "Spatial activity"
$store = Read-RequiredText (Join-Path $sourceRoot "KuramotoExperimentStore.kt") "experiment store"
$buildScript = Read-RequiredText (Join-Path $repoRootPath "tools\Build-KuramotoSpatialSdkAndroid.ps1") "build wrapper"
$readme = Read-RequiredText (Join-Path $appRoot "README.md") "app README"
$plan = Read-RequiredText (Join-Path $repoRootPath "docs\SPATIAL_SDK_PORT_IMPLEMENTATION_PLAN.md") "implementation plan"

Assert-ContainsTokens $settings @(
    'rootProject\.name = "RustyQuestKuramotoSpatialSdk"',
    'include\(":app"\)',
    'mavenCentral\(\)',
    'google\(\)'
) "Gradle settings"

Assert-ContainsTokens $rootBuild @(
    'libs\.plugins\.android\.application',
    'libs\.plugins\.jetbrains\.kotlin\.android'
) "root Gradle plugins"

Assert-ContainsTokens $gradleProperties @(
    'android\.useAndroidX=true',
    'android\.nonTransitiveRClass=true',
    'org\.gradle\.configuration-cache=true'
) "Gradle properties"

Assert-ContainsTokens $versions @(
    'spatialsdk = "0\.13\.1"',
    'agp = "8\.11\.1"',
    'kotlin = "2\.1\.0"',
    'meta-spatial-sdk-base',
    'meta-spatial-sdk-compose',
    'meta-spatial-sdk-toolkit',
    'meta-spatial-sdk-vr'
) "version catalog"

Assert-ContainsTokens $appBuild @(
    'libs\.plugins\.meta\.spatial\.plugin',
    'libs\.plugins\.compose\.compiler',
    'namespace = "io\.github\.mesmerprism\.rustyquest\.kuramoto_spatial"',
    'applicationId = "io\.github\.mesmerprism\.rustyquest\.kuramoto_spatial"',
    'compileSdk = 34',
    'minSdk = 34',
    'targetSdk = 34',
    'compose = true',
    'kotlinCompilerExtensionVersion = "1\.5\.15"',
    'allowUsageDataCollection\.set\(false\)',
    'implementation\(libs\.meta\.spatial\.sdk\.base\)',
    'implementation\(libs\.meta\.spatial\.sdk\.compose\)',
    'implementation\(libs\.meta\.spatial\.sdk\.toolkit\)',
    'implementation\(libs\.meta\.spatial\.sdk\.vr\)'
) "app Gradle build"

Assert-ContainsTokens $manifest @(
    'horizonos:uses-horizonos-sdk',
    'android\.hardware\.vr\.headtracking',
    'com\.oculus\.intent\.category\.VR',
    'android\.intent\.category\.LAUNCHER',
    'com\.oculus\.supportedDevices',
    'libossdk\.oculus\.so',
    'KuramotoSpatialActivity',
    'android:configChanges="screenSize\|screenLayout\|orientation\|keyboardHidden\|keyboard\|navigation\|uiMode"'
) "Android manifest"

Assert-ContainsTokens $ids @(
    'kuramoto_experiment_panel'
) "panel id"

Assert-ContainsTokens $activity @(
    'class KuramotoSpatialActivity : AppSystemActivity',
    'VRFeature\(this\)',
    'ComposeFeature\(\)',
    'ComposeViewPanelRegistration',
    'UIPanelSettings',
    'QuadShapeOptions\(width = PANEL_WIDTH_METERS, height = PANEL_HEIGHT_METERS\)',
    'DpPerMeterDisplayOptions\(dpPerMeter = PANEL_DP_PER_METER\)',
    'Entity\.createPanelEntity',
    'scene\.setViewOrigin\(0\.0f, 0\.0f, 2\.0f, 180\.0f\)',
    'Transform\(Pose\(Vector3',
    'Scale\(Vector3',
    'Color\(0xFFF8FAFC\)',
    'RUN_WORKFLOW_SELF_TEST',
    'self-test-complete',
    'RUSTY_QUEST_KURAMOTO_SPATIAL',
    'hand_rendering_expected'
) "Spatial activity and panel"

Assert-ContainsTokens $store @(
    'QUESTIONNAIRE_SCHEMA = "rusty\.kuramoto\.mesh\.experiment_questionnaire\.v1"',
    'DEFAULT_BLOCK_DURATION_MS = 10_000L',
    'participant_id',
    'session_id',
    'block_index',
    'block_number',
    'condition_id',
    'profile_id',
    'surface_target_id',
    'questionnaire_results\.jsonl',
    'polar_events\.jsonl',
    'ecg_events\.jsonl',
    'highLow',
    'lowHigh',
    'highHigh',
    'real-hands',
    'gpu-replay-hands',
    'icosphere'
) "experiment store"

Assert-ContainsTokens $buildScript @(
    'gradle-\$Version-bin\.zip',
    'services\.gradle\.org',
    'GRADLE_USER_HOME',
    ':app:assembleDebug',
    'rusty-quest-kuramoto-spatial-sdk\.apk',
    'rusty\.quest\.kuramoto_spatial_sdk_android\.build_manifest\.v1',
    'high_rate_json_payload = \$false',
    'hand_rendering_expected = \$false'
) "build wrapper"

Assert-ContainsTokens $readme @(
    'separate Meta Spatial SDK lane',
    'does not replace the native renderer APK',
    'io\.github\.mesmerprism\.rustyquest\.kuramoto_spatial/\.KuramotoSpatialActivity',
    'target/kuramoto-spatial-sdk-android/rusty-quest-kuramoto-spatial-sdk\.apk'
) "app README"

Assert-ContainsTokens $plan @(
    'Initial Hypothesis',
    'Resources Consulted',
    'Architecture Decisions',
    'Iteration Log',
    'Final Build/Run Recipe',
    'Remaining Risks And Follow-Ups',
    'apps/kuramoto-spatial-sdk-android',
    'io\.github\.mesmerprism\.rustyquest\.kuramoto_spatial'
) "implementation plan"

Write-Host "Rusty Quest Kuramoto Spatial SDK Android static validation passed"
