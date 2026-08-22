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

function Resolve-GeneratedOutputPath {
    param([string]$Value)
    if ([System.IO.Path]::IsPathRooted($Value)) {
        return [System.IO.Path]::GetFullPath($Value)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $repo $Value))
}

function Get-ArtifactSha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$corePath = Join-Path $repo "crates\rusty-quest-breath-contract\src\composition.rs"
$runtimePath = Join-Path $repo "apps\native-renderer-android\native\src\breath_composition_runtime.rs"
$controllerPath = Join-Path $repo "apps\native-renderer-android\native\src\openxr_stimulus_actions.rs"
$polarPath = Join-Path $repo "apps\native-renderer-android\native\src\polar_acc_breath_adapter.rs"
$ingressPath = Join-Path $repo "apps\native-renderer-android\native\src\polar_composition_adapters.rs"
$capturePath = Join-Path $repo "apps\native-renderer-android\native\src\breath_capture.rs"
$captureAnalyzerPath = Join-Path $repo "tools\Analyze-NativeRendererBreathCapture.ps1"
$captureFixturePath = Join-Path $repo "fixtures\native-renderer-breath-capture\synthetic-parallel-session"
$panelPath = Join-Path $repo "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\ControlPanelActivity.java"
$polarPanelPath = Join-Path $repo "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\PolarSensorPanel.java"
$calibrationActionPath = Join-Path $repo "apps\native-renderer-android\native\src\breath_calibration_controller_action.rs"
$worldBasisPath = Join-Path $repo "apps\native-renderer-android\native\src\private_particle_world_basis.rs"
$xrVulkanPath = Join-Path $repo "apps\native-renderer-android\native\src\xr_vulkan.rs"
$gpuPrivateParticlesPath = Join-Path $repo "apps\native-renderer-android\native\src\gpu_private_particles.rs"
$operatorPath = Join-Path $repo "tools\Invoke-NativeRendererBreathOperator.ps1"
$nativeBuildScriptPath = Join-Path $repo "apps\native-renderer-android\native\build.rs"
$androidBuildPath = Join-Path $repo "tools\Build-NativeRendererAndroid.ps1"
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
$capture = Read-RequiredText $capturePath "synchronized source capture"
$captureAnalyzer = Read-RequiredText $captureAnalyzerPath "host capture analyzer"
$panel = Read-RequiredText $panelPath "same-APK panel"
$polarPanel = Read-RequiredText $polarPanelPath "sole Polar acquisition panel"
$calibrationAction = Read-RequiredText $calibrationActionPath "controller calibration action"
$worldBasis = Read-RequiredText $worldBasisPath "captured private-particle world basis"
$xrVulkan = Read-RequiredText $xrVulkanPath "native OpenXR/Vulkan composition"
$gpuPrivateParticles = Read-RequiredText $gpuPrivateParticlesPath "private particle compute/sort routing"
$operator = Read-RequiredText $operatorPath "fixed breath operator CLI"
$nativeBuildScript = Read-RequiredText $nativeBuildScriptPath "native build script"
$androidBuild = Read-RequiredText $androidBuildPath "Android build wrapper"
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
    "packaged_activation_binding_sha256",
    "activation_binding_matches",
    "activation-binding-mismatch",
    "action-queue-full",
    '"generation": value.generation.get()',
    "observation.generation != Some(active_generation)",
    "BreathCompositionStatus::Running",
    "take_adapter_action",
    "polar_acc_for_presentation",
    "controller_adapter_available",
    "controller_selected",
    "bounded_polar_silence_emits_one_missing_observation_and_clears_output",
    "nativeApplyBreathCompositionCommand",
    "nativeReadBreathCompositionStatus",
    "start_calibration_inner",
    '"start_calibration"',
    "AdapterAction::Reset",
    "start_calibration_restarts_running_ready_and_failed_generations_atomically",
    "running_calibration_restart_rejects_before_any_mutation_when_queue_is_full",
    "source_change_queues_hard_resets_but_mapping_change_does_not"
) "native command/readback authority"
Assert-Tokens $controller @(
    "apply_composition_controller_actions",
    "BreathCompositionSource::Controller",
    "controller_adapter_available",
    "composition_controller_enabled",
    "right_thumbstick_binding_enabled",
    "record_capture_right_thumbstick",
    "controller_right_thumbstick",
    "capture_annotation_binds_right_thumbstick_without_projection_controls",
    "observe_composition_controller_missing",
    "poll_polar(observed_at)",
    "submit_assessment"
) "single OpenXR assessment owner"
Assert-Tokens ($polar + [Environment]::NewLine + $ingress) @(
    "TimedPolarAccFrame::from_pmd_measurement",
    "CommonPhaseClassifier",
    "latest_polar_acc_after",
    "PolarAccPresentationMode",
    "LowLatencySmooth",
    "TimestampFaithful",
    "POLAR_ACC_PRESENTATION_DELAY_NS",
    "POLAR_ACC_SMOOTHING_TIME_CONSTANT_NS",
    "timestamped_acc_batches_resample_at_render_cadence_with_one_batch_delay",
    "low_latency_presentation_uses_the_freshest_target_and_eases_at_render_cadence",
    "polar_rr_after",
    "rr_measurements"
) "separate Polar ACC/RR owners"
Assert-Tokens $capture @(
    "rusty.quest.breath_source_capture.v1",
    "QUEUE_CAPACITY",
    "FIXED_CAPTURE_DURATION_MILLIS",
    "breath-capture-writer",
    "breath-capture-watchdog",
    "capture.active.json",
    "breath_source_samples.partial.jsonl",
    "duration-elapsed",
    "polar_acc_sample",
    "polar_ecg_sample",
    "controller_pose",
    "controller_right_thumbstick",
    "driver_apply",
    "rr_consumed_by_breath",
    "same-process-direct"
) "bounded synchronized source capture"
Assert-Tokens $captureAnalyzer @(
    "rusty.quest.breath_source_capture_analysis.v1",
    "capture_manifest.json",
    "capture_receipt.json",
    "polar_acc_sample_to_frame_receipt",
    "polar_acc_frame_to_jni_submit",
    "controller_right_thumbstick_interval",
    "RightStickHoldDeadzone",
    "manual_breath_annotation",
    "assessment_source_sample_to_driver_apply",
    "breath_capture_timeline.csv"
) "host-only capture analysis"
Assert-Tokens $panel @(
    "breath-mapping",
    "Direct Breath Mapping",
    "Start calibration",
    "Active status",
    "Mapping",
    "Calibration",
    "Diagnostics",
    "Polar connection",
    "breath_composition_operator_status.json",
    "native-effective readback",
    'calibration.opt("generation")',
    "buildEmbeddedAcquisitionView",
    "nativeApplyBreathCompositionCommand",
    "nativeReadBreathCompositionStatus"
) "same-APK panel mode"
$breathPanelStart = $panel.IndexOf("private View buildBreathMappingPanelView()")
$breathPanelEnd = $panel.IndexOf("private void applyBreathCompositionOperation(", $breathPanelStart)
if ($breathPanelStart -lt 0 -or $breathPanelEnd -le $breathPanelStart) {
    throw "Breath composition static check could not isolate the same-APK breath panel method"
}
$breathPanelMethod = $panel.Substring($breathPanelStart, $breathPanelEnd - $breathPanelStart)
$listenerCount = [regex]::Matches(
    $breathPanelMethod,
    [regex]::Escape("new View.OnClickListener()")
).Count
if ($listenerCount -ne 6) {
    throw "Same-APK breath panel must retain six explicit Android-compatible click listeners; found $listenerCount"
}
if ($breathPanelMethod -match 'setOnClickListener\s*\([^;]*?->') {
    throw "Same-APK breath panel must not use lambda click listeners with the Android boot classpath"
}
Assert-Tokens $breathPanelMethod @(
    "launchImmersiveRenderer();",
    'applyBreathCompositionOperation("start_calibration", readback, false);',
    'applyBreathCompositionOperation("cancel", readback, true);',
    'applyBreathCompositionOperation("reset", readback, false);',
    "refreshBreathCompositionReadback(readback);"
) "Android-compatible breath panel click behavior"
Assert-Tokens ($calibrationAction + [Environment]::NewLine + $controller) @(
    "right-secondary-hold-start",
    "hold_triggers_once_until_release_and_rearms",
    'start_calibration("right-secondary-hold")',
    "cancel_pending_sequence"
) "controller calibration action"
Assert-Tokens ($worldBasis + [Environment]::NewLine + $xrVulkan + [Environment]::NewLine + $gpuPrivateParticles) @(
    "captured_compute_basis_does_not_follow_later_head_motion",
    "recenter_recaptures_right_up_and_forward_axes_together",
    "compute_basis_transport()",
    "PrivateParticleFrameEyeProjections::new(",
    "particle_sort_eye_projection",
    "eye_projections.compute_basis",
    "eye_projections.sort_eye",
    "captured_compute_basis_stays_fixed_while_live_sort_eye_follows_head_motion",
    "privateParticleComputeBasisSource=captured-world-anchor",
    "privateParticleComputeBasisFollowCamera=false"
) "captured private-particle compute basis"
Assert-Tokens ($operator + [Environment]::NewLine + $panel) @(
    "BREATH_COMPOSITION_PANEL_COMMAND",
    "start_calibration",
    "breath_composition_operator_status.json",
    "screenshot_required",
    "structuredReadback=true",
    "run-as"
) "fixed operator command and structured readback"
Assert-Tokens $polarPanel @(
    "buildEmbeddedAcquisitionView",
    "buildView(false)",
    'Button scan = button("Scan")',
    'Button connect = button("Connect")',
    'Button startPmd = button("Start PMD")'
) "sole embedded Polar acquisition owner"
Assert-Tokens $resolver @(
    "rusty.quest.native_app_build_resolution_result.v1",
    "breath_composition_activation_sha256",
    "RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_EXPECTED_BINDING_SHA256",
    "Assert-CanonicalPathInsideRoot"
) "structured resolver"
Assert-Tokens ($nativeBuildScript + [Environment]::NewLine + $androidBuild) @(
    "RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_EXPECTED_BINDING_SHA256",
    '$breathActivation.Value.sha256',
    "does not exactly match feature-lock activation"
) "packaged exact-binding build route"
Assert-Tokens ($features + [Environment]::NewLine + $appSpec) @(
    "breath.composition.closed_world",
    "input.breath.controller_assessment",
    "input.breath.polar_acc_assessment",
    "mapping.breath.volume",
    "mapping.breath.state",
    "ui.same_apk_breath_mapping_panel",
    "input.right_secondary_same_apk_panel_triple_press",
    "input.right_secondary_breath_calibration_hold",
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

$syncErrorIndex = $controller.IndexOf("if let Err(error) = session.sync_actions")
if ($syncErrorIndex -lt 0) {
    throw "OpenXR composition adapter is missing the sync_actions error branch"
}
$syncErrorReturnIndex = $controller.IndexOf("return events;", $syncErrorIndex)
if ($syncErrorReturnIndex -lt 0) {
    throw "OpenXR sync_actions error branch has no bounded return"
}
$syncErrorBlock = $controller.Substring($syncErrorIndex, $syncErrorReturnIndex - $syncErrorIndex)
Assert-Tokens $syncErrorBlock @(
    "observe_composition_controller_missing(",
    "poll_polar(observed_at)",
    "record_controller_right_thumbstick(",
    "sync-unavailable"
) "sync_actions error clearing before early return"

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
        "hand_mesh_live_input",
        "hand_mesh_visual",
        "input.breath.controller_assessment",
        "input.breath.polar_acc_assessment",
        "input.controllers_and_hands_optional",
        "input.right_secondary_breath_calibration_hold",
        "input.right_secondary_same_apk_panel_triple_press",
        "input.simultaneous_hands_and_controllers",
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
    if (@($lock.android_manifest.permissions) -notcontains "android.permission.ACCESS_COARSE_LOCATION") {
        throw "Quest Polar composition closure omitted coarse location permission"
    }
    if ((@($lock.android_manifest.receivers | Sort-Object) -join [Environment]::NewLine) -cne "PolarSensorCommandReceiver") {
        throw "Quest Polar composition closure must select exactly the fixed headless Polar receiver"
    }
    $generatedManifestPath = Resolve-GeneratedOutputPath ([string]$lock.generated_outputs.android_manifest)
    $generatedManifest = Get-Content -Raw -LiteralPath $generatedManifestPath
    if ($generatedManifest -notmatch 'PolarSensorCommandReceiver' -or
        $generatedManifest -notmatch 'POLAR_SENSOR_RUNTIME_COMMAND' -or
        $generatedManifest -notmatch 'android:exported="true"') {
        throw "Generated Android manifest omitted the exact headless Polar command receiver"
    }
    if ([string]::IsNullOrWhiteSpace([string]$result.breath_composition_activation_sha256)) {
        throw "Structured resolver omitted breath composition activation binding"
    }
    if ([string]$lock.breath_composition_activation.sha256 -ne [string]$result.breath_composition_activation_sha256) {
        throw "Feature lock and structured result activation bindings disagree"
    }
    $bindingProperty = "debug.rustyquest.native_renderer.breath_composition.activation.binding_sha256"
    $bindingSetting = "native_renderer.breath_composition.activation.binding_sha256"
    $bindingBuildEnv = "RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_EXPECTED_BINDING_SHA256"
    $binding = [string]$result.breath_composition_activation_sha256
    $runtimeProfilePath = Resolve-GeneratedOutputPath ([string]$lock.generated_outputs.runtime_profile)
    $nativeAppSettingsPath = Resolve-GeneratedOutputPath ([string]$lock.generated_outputs.native_app_settings)
    $propertyWritePlanPath = Resolve-GeneratedOutputPath ([string]$lock.generated_outputs.property_write_plan)
    $buildEnvPath = Resolve-GeneratedOutputPath ([string]$lock.generated_outputs.build_env)
    $buildManifestPath = Resolve-GeneratedOutputPath ([string]$lock.generated_outputs.build_manifest)
    $runtimeProfile = Get-Content -Raw -LiteralPath $runtimeProfilePath | ConvertFrom-Json
    $nativeAppSettings = Get-Content -Raw -LiteralPath $nativeAppSettingsPath | ConvertFrom-Json
    $propertyWritePlan = Get-Content -Raw -LiteralPath $propertyWritePlanPath | ConvertFrom-Json
    $buildEnv = Get-Content -Raw -LiteralPath $buildEnvPath | ConvertFrom-Json
    $buildManifest = Get-Content -Raw -LiteralPath $buildManifestPath | ConvertFrom-Json

    $runtimeOwnedBinding = @($runtimeProfile.owned_android_properties | Where-Object { [string]$_ -eq $bindingProperty })
    $runtimeSetBinding = @($runtimeProfile.set_properties | Where-Object { [string]$_.name -eq $bindingProperty })
    if ($runtimeOwnedBinding.Count -ne 1 -or $runtimeSetBinding.Count -ne 1 -or [string]$runtimeSetBinding[0].value -ne $binding) {
        throw "Runtime profile did not retain exactly one executable activation binding"
    }
    $settingsBindingProperty = $nativeAppSettings.values.PSObject.Properties[$bindingSetting]
    $settingsAdapterBinding = @($nativeAppSettings.adapters.android_properties | Where-Object { [string]$_.name -eq $bindingProperty })
    if ($null -eq $settingsBindingProperty -or [string]$settingsBindingProperty.Value.value -ne $binding -or
        $settingsAdapterBinding.Count -ne 1 -or [string]$settingsAdapterBinding[0].value -ne $binding) {
        throw "Native settings authority/adapters did not retain the exact activation binding"
    }
    $planBinding = @($propertyWritePlan.operations | Where-Object { [string]$_.kind -eq "set" -and [string]$_.name -eq $bindingProperty })
    if ($planBinding.Count -ne 1 -or [string]$planBinding[0].value -ne $binding) {
        throw "Executable property write plan did not retain the exact activation binding"
    }
    $buildBinding = @($buildEnv.env | Where-Object { [string]$_.name -eq $bindingBuildEnv })
    if ($buildBinding.Count -ne 1 -or [string]$buildBinding[0].value -ne $binding) {
        throw "Resolver build environment did not package the exact activation binding"
    }
    foreach ($artifact in @(
        @{ Label = "runtime profile"; Path = $runtimeProfilePath; Expected = [string]$buildManifest.runtime_profile_sha256 },
        @{ Label = "native app settings"; Path = $nativeAppSettingsPath; Expected = [string]$buildManifest.native_app_settings_sha256 },
        @{ Label = "property write plan"; Path = $propertyWritePlanPath; Expected = [string]$buildManifest.property_write_plan_sha256 },
        @{ Label = "build env"; Path = $buildEnvPath; Expected = [string]$buildManifest.build_env_sha256 }
    )) {
        if ((Get-ArtifactSha256 $artifact.Path) -ne $artifact.Expected) {
            throw "Build manifest hash drifted for $($artifact.Label)"
        }
    }
    if ([string]$lock.android_manifest.package_name -ne "io.github.mesmerprism.rustyquest.native_renderer.breath_matrix") {
        throw "Breath composition package identity drifted"
    }
    $captureAnalysisRoot = Join-Path $runRoot "capture-analysis"
    & pwsh -NoProfile -File $captureAnalyzerPath -CaptureDirectory $captureFixturePath -OutputDirectory $captureAnalysisRoot | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Synthetic fixed-duration capture analysis failed with exit $LASTEXITCODE"
    }
    $captureAnalysisPath = Join-Path $captureAnalysisRoot "breath_capture_analysis.json"
    $captureAnalysis = Get-Content -Raw -LiteralPath $captureAnalysisPath | ConvertFrom-Json
    if ([Int64]$captureAnalysis.target_duration_millis -ne 120000 -or
        [string]$captureAnalysis.receipt.stop_reason -ne "duration-elapsed" -or
        [Int64]$captureAnalysis.record_counts.controller_right_thumbstick -ne 1) {
        throw "Synthetic capture analysis lost the fixed duration, automatic completion, or right-thumbstick channel"
    }
    $captureTimeline = Import-Csv -LiteralPath (Join-Path $captureAnalysisRoot "breath_capture_timeline.csv")
    $annotation = @($captureTimeline | Where-Object {
        [string]$_.kind -eq "controller_right_thumbstick"
    })
    if ($annotation.Count -ne 1 -or [string]$annotation[0].manual_breath_annotation -ne "inhaling") {
        throw "Synthetic capture analysis did not derive the expected host-only right-stick annotation"
    }
} finally {
    if (Test-Path -LiteralPath $runRoot) {
        Remove-Item -LiteralPath $runRoot -Recurse -Force
    }
}

Write-Host "Rusty Quest breath composition static validation passed"
