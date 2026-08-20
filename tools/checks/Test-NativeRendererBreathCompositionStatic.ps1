param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repo = [System.IO.Path]::GetFullPath([string](Resolve-Path $RepoRoot))

function Read-RequiredText {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing breath composition file ($Label): $Path"
    }
    return Get-Content -Raw -LiteralPath $Path
}

function Assert-Tokens {
    param([string]$Text, [string[]]$Tokens, [string]$Label)
    foreach ($token in $Tokens) {
        if ($Text -notmatch [regex]::Escape($token)) {
            throw "Breath composition static check failed for $($Label): missing exact token: $token"
        }
    }
}

$corePath = Join-Path $repo "crates\rusty-quest-breath-contract\src\composition.rs"
$runtimePath = Join-Path $repo "apps\native-renderer-android\native\src\breath_composition_runtime.rs"
$controllerPath = Join-Path $repo "apps\native-renderer-android\native\src\openxr_stimulus_actions.rs"
$polarPath = Join-Path $repo "apps\native-renderer-android\native\src\polar_acc_breath_adapter.rs"
$ingressPath = Join-Path $repo "apps\native-renderer-android\native\src\polar_composition_adapters.rs"
$panelPath = Join-Path $repo "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\ControlPanelActivity.java"
$resolverPath = Join-Path $repo "tools\Resolve-NativeAppBuild.ps1"
$appSpecPath = Join-Path $repo "fixtures\native-app-builds\native-breath-four-way-conformance.app.json"
$featurePaths = @(
    "fixtures\native-app-features\breath\composition\breath.composition.closed_world.feature.json",
    "fixtures\native-app-features\breath\controller-assessment\input.breath.controller_assessment.feature.json",
    "fixtures\native-app-features\breath\polar-acc-assessment\input.breath.polar_acc_assessment.feature.json",
    "fixtures\native-app-features\breath\state-mapping\mapping.breath.state.feature.json",
    "fixtures\native-app-features\breath\volume-mapping\mapping.breath.volume.feature.json",
    "fixtures\native-app-features\ui\breath-mapping-panel\ui.same_apk_breath_mapping_panel.feature.json"
) | ForEach-Object { Join-Path $repo $_ }

$core = Read-RequiredText $corePath "pure composition"
$runtime = Read-RequiredText $runtimePath "native runtime"
$controller = Read-RequiredText $controllerPath "OpenXR adapter composition"
$polar = Read-RequiredText $polarPath "Polar ACC adapter"
$ingress = Read-RequiredText $ingressPath "Polar ingress"
$panel = Read-RequiredText $panelPath "same-APK panel"
$resolver = Read-RequiredText $resolverPath "structured resolver"
$appSpec = Read-RequiredText $appSpecPath "conformance app"
$features = ($featurePaths | ForEach-Object { Read-RequiredText $_ "feature descriptor" }) -join [Environment]::NewLine

Assert-Tokens $core @(
    "BreathCompositionSource",
    "BreathCompositionMapping",
    "BreathCompositionCapabilities",
    "mapping_change_preserves_generation_but_source_change_hard_resets",
    "exact_capability_closure_represents_the_four_way_matrix",
    "stale_malformed_and_unselected_assessments_fail_closed"
) "pure closed-world composition"
Assert-Tokens $runtime @(
    "rusty.quest.breath_composition.command.v1",
    "rusty.quest.breath_composition.response.v1",
    "activation_binding_sha256",
    "take_adapter_action",
    "latest_polar_acc_after",
    "controller_adapter_available",
    "controller_selected",
    "bounded_polar_silence_emits_one_missing_observation_and_clears_output",
    "nativeApplyBreathCompositionCommand",
    "nativeReadBreathCompositionStatus",
    "source_change_queues_hard_resets_but_mapping_change_does_not"
) "native command/readback authority"
Assert-Tokens $controller @(
    "apply_composition_controller_actions",
    "BreathCompositionSource::Controller",
    "composition_controller_available",
    "composition_controller_enabled",
    "submit_assessment"
) "single OpenXR assessment owner"
Assert-Tokens ($polar + [Environment]::NewLine + $ingress) @(
    "TimedPolarAccFrame::from_pmd_measurement",
    "CommonPhaseClassifier",
    "latest_polar_acc_after",
    "polar_rr_after",
    "rr_measurements"
) "separate Polar ACC/RR owners"
Assert-Tokens $panel @(
    "breath-mapping",
    "Direct Breath Mapping",
    "Start calibration",
    "native-effective readback",
    "nativeApplyBreathCompositionCommand",
    "nativeReadBreathCompositionStatus"
) "same-APK panel mode"
Assert-Tokens $resolver @(
    "rusty.quest.native_app_build_resolution_result.v1",
    "breath_composition_activation_sha256",
    "Assert-CanonicalPathInsideRoot"
) "structured resolver"
Assert-Tokens ($features + [Environment]::NewLine + $appSpec) @(
    "breath.composition.closed_world",
    "input.breath.controller_assessment",
    "input.breath.polar_acc_assessment",
    "mapping.breath.volume",
    "mapping.breath.state",
    "ui.same_apk_breath_mapping_panel",
    "input.right_secondary_same_apk_panel_triple_press",
    "io.github.mesmerprism.rustyquest.native_renderer.breath_matrix"
) "closed-world feature and lane identities"

foreach ($text in @($core, $runtime, $features)) {
    foreach ($forbidden in @("viscereality", "orbit-radius", "sphere-radius", "icosphere", "shader tuning")) {
        if ($text.ToLowerInvariant().Contains($forbidden)) {
            throw "Generic breath composition source contains downstream term: $forbidden"
        }
    }
}
if ($runtime -match 'polar_rr_after\s*\(') {
    throw "Breath composition runtime must not consume the RR queue"
}

$runRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-quest-breath-i6-" + [guid]::NewGuid().ToString("N"))
$resultPath = Join-Path $runRoot "result.json"
try {
    New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
    & pwsh -NoProfile -File $resolverPath -AppSpec $appSpecPath -OutputRoot $runRoot -ResultJsonPath $resultPath -DryRun | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Breath composition structured resolution failed with exit $LASTEXITCODE"
    }
    $results = @(Get-ChildItem -LiteralPath $runRoot -Filter "result.json" -File -Recurse)
    if ($results.Count -ne 1) {
        throw "Expected exactly one structured resolver result, found $($results.Count)"
    }
    $result = Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json
    if ([string]$result.schema -ne "rusty.quest.native_app_build_resolution_result.v1") {
        throw "Unexpected structured resolver schema: $($result.schema)"
    }
    $rootCanonical = [System.IO.Path]::GetFullPath($runRoot).TrimEnd("\", "/") + [System.IO.Path]::DirectorySeparatorChar
    $lockCanonical = [System.IO.Path]::GetFullPath([string]$result.feature_lock_path)
    if (-not $lockCanonical.StartsWith($rootCanonical, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Feature lock escaped requested output root: $lockCanonical"
    }
    $lock = Get-Content -Raw -LiteralPath $lockCanonical | ConvertFrom-Json
    $expected = @(
        "breath.composition.closed_world",
        "input.breath.controller_assessment",
        "input.breath.polar_acc_assessment",
        "input.controllers_and_hands_optional",
        "input.right_secondary_same_apk_panel_triple_press",
        "mapping.breath.state",
        "mapping.breath.volume",
        "quest.native.openxr_vulkan_base",
        "renderer.background.solid_black",
        "sensor.polar_h10_ble",
        "ui.same_apk_breath_mapping_panel",
        "ui.same_apk_control_panel"
    )
    $actual = @($result.resolved_feature_ids | Sort-Object)
    if (($actual -join [Environment]::NewLine) -ne (($expected | Sort-Object) -join [Environment]::NewLine)) {
        throw "Resolved feature closure was not exact. Actual: $($actual -join ', ')"
    }
    if ([string]::IsNullOrWhiteSpace([string]$result.breath_composition_activation_sha256)) {
        throw "Structured resolver omitted breath composition activation binding"
    }
    if ([string]$lock.breath_composition_activation.sha256 -ne [string]$result.breath_composition_activation_sha256) {
        throw "Feature lock and structured result activation bindings disagree"
    }
    if ([string]$lock.android_manifest.package_name -ne "io.github.mesmerprism.rustyquest.native_renderer.breath_matrix") {
        throw "Breath composition package identity drifted"
    }
} finally {
    if (Test-Path -LiteralPath $runRoot) {
        Remove-Item -LiteralPath $runRoot -Recurse -Force
    }
}

Write-Host "Rusty Quest breath composition static validation passed"
