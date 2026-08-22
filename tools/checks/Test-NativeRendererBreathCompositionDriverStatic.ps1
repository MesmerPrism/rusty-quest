param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repo = [System.IO.Path]::GetFullPath([string](Resolve-Path $RepoRoot))

function Read-RequiredText {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing breath composition driver file ($Label): $Path"
    }
    return Get-Content -Raw -LiteralPath $Path
}

function Assert-Tokens {
    param([string]$Text, [string[]]$Tokens, [string]$Label)
    foreach ($token in $Tokens) {
        if (-not $Text.Contains($token, [System.StringComparison]::Ordinal)) {
            throw "Breath composition driver static check failed for $($Label): missing exact token: $token"
        }
    }
}

$driverPath = Join-Path $repo "apps\native-renderer-android\native\src\breath_composition_driver.rs"
$integratorPath = Join-Path $repo "apps\native-renderer-android\native\src\bounded_breath_phase_integrator.rs"
$gpuPath = Join-Path $repo "apps\native-renderer-android\native\src\gpu_private_particles.rs"
$buildRsPath = Join-Path $repo "apps\native-renderer-android\native\build.rs"
$resolverPath = Join-Path $repo "tools\Resolve-NativeAppBuild.ps1"
$featurePath = Join-Path $repo "fixtures\native-app-features\particles\private\breath-composition-driver\particles.private.breath_composition_driver.feature.json"
$appSpecPath = Join-Path $repo "fixtures\native-app-builds\native-breath-particle-driver-conformance.app.json"
$unlistedAppSpecPath = Join-Path $repo "fixtures\native-app-builds\native-breath-four-way-conformance.app.json"

$driver = Read-RequiredText $driverPath "pure adapter"
$integrator = Read-RequiredText $integratorPath "generic phase integrator"
$gpu = Read-RequiredText $gpuPath "generic driver bank consumer"
$buildRs = Read-RequiredText $buildRsPath "native packaged-binding build contract"
$resolver = Read-RequiredText $resolverPath "structured resolver"
$feature = Read-RequiredText $featurePath "feature descriptor"
$appSpec = Read-RequiredText $appSpecPath "conformance app"
$unlistedAppSpec = Read-RequiredText $unlistedAppSpecPath "unlisted conformance app"

Assert-Tokens $driver @(
    "BreathCompositionStatus::Running",
    "snapshot.feature_lock_active",
    "packaged_binding != runtime_binding",
    "snapshot.feature_lock_sha256 != packaged_binding",
    "snapshot.packaged_feature_lock_sha256 != packaged_binding",
    "unlisted-or-missing-packaged-binding",
    "accepted-after-boundary-reset",
    "stale-output",
    "out-of-order-output",
    "sample-time-discontinuity",
    "observed-time-discontinuity",
    "breathCompositionDriverRequestedSource=",
    "breathCompositionDriverEffectiveSource=",
    "breathCompositionDriverGeneration=",
    "breathCompositionDriverTargetSlot=",
    "breathCompositionDriverRrConsumed=false",
    "all_four_source_mapping_selections_reach_one_neutral_slot",
    "volume_endpoints_and_rapid_accepted_change_apply_immediately",
    "state_integration_has_cadence_parity_and_explicit_hold_behavior",
    "disabled_unlisted_and_stale_or_mismatched_bindings_are_inert",
    "unlisted_app_with_ambient_matching_composition_binding_is_inert"
) "fail-closed adapter"
Assert-Tokens $integrator @(
    "BoundedBreathPhaseIntegrator",
    "BreathHoldPolicy",
    "integration_is_bounded_and_cadence_independent",
    "hold_and_explicit_reset_policies_are_distinct"
) "source-neutral phase integration"
Assert-Tokens $gpu @(
    "update_breath_composition_driver",
    "breath-composition-closed-world",
    "breathCompositionDriverReceipt=render-thread-applied-neutral-slot"
) "render-thread driver-bank handoff"
Assert-Tokens $resolver @(
    '$BreathCompositionDriverFeatureId = "particles.private.breath_composition_driver"',
    '$BreathCompositionDriverActivationBindingProperty',
    '$BreathCompositionDriverExpectedBindingBuildEnv',
    "breath_composition_driver_activation_sha256",
    "resolver-derived and must not be supplied by a feature or app spec"
) "resolver-derived exact binding"
Assert-Tokens $buildRs @(
    "RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_DRIVER_EXPECTED_BINDING_SHA256"
) "driver-specific packaged binding"
Assert-Tokens ($feature + [Environment]::NewLine + $appSpec) @(
    "particles.private.breath_composition_driver",
    "breath.composition.closed_world",
    "renderer.private_particles",
    "pwsh -NoProfile -File tools/Apply-RuntimeProfile.ps1",
    "input.simultaneous_hands_and_controllers",
    "breathCompositionDriverRrConsumed=false",
    "privateParticleHeartbeatPulseMode=polar-rr-event"
) "closed feature and app composition"

$windowsPowerShellNoProfile = ("power" + "shell") + " -NoProfile"
if ($feature.Contains($windowsPowerShellNoProfile, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Breath composition driver feature must not add a Windows PowerShell child route"
}

if ($unlistedAppSpec.Contains('"particles.private.breath_composition_driver"', [System.StringComparison]::Ordinal)) {
    throw "The pre-existing four-way input shell must remain unlisted/inert for the particle driver adapter"
}
$forbiddenDownstreamTerms = @(
    "viscereality",
    ("kura" + "moto"),
    "icosphere",
    "orbit-radius",
    "sphere-radius",
    "shader tuning"
)
$syntheticJoinedForbiddenTerm = "synthetic-" + ("kura" + "moto")
$syntheticJoinedForbiddenRejected = $false
foreach ($forbidden in $forbiddenDownstreamTerms) {
    if ($syntheticJoinedForbiddenTerm.Contains($forbidden, [System.StringComparison]::OrdinalIgnoreCase)) {
        $syntheticJoinedForbiddenRejected = $true
        break
    }
}
if (-not $syntheticJoinedForbiddenRejected) {
    throw "Public breath composition driver forbidden-token assertion must reject a synthetic joined token"
}
foreach ($text in @($driver, $integrator, $feature)) {
    foreach ($forbidden in $forbiddenDownstreamTerms) {
        if ($text.Contains($forbidden, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Public breath composition driver source contains downstream term: $forbidden"
        }
    }
}
foreach ($forbiddenCall in @("polar_rr_after(", "rr_measurements(", "ManifoldScalarDriver")) {
    if ($driver.Contains($forbiddenCall, [System.StringComparison]::Ordinal)) {
        throw "Breath composition driver must not consume an unrelated transport: $forbiddenCall"
    }
}

$runRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-quest-breath-driver-" + [guid]::NewGuid().ToString("N"))
$resultPath = Join-Path $runRoot "result.json"
try {
    New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
    & pwsh -NoProfile -File $resolverPath -AppSpec $appSpecPath -OutputRoot $runRoot -ResultJsonPath $resultPath -DryRun | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Breath composition driver structured resolution failed with exit $LASTEXITCODE"
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
        "input.right_secondary_same_apk_panel_triple_press",
        "input.simultaneous_hands_and_controllers",
        "mapping.breath.state",
        "mapping.breath.volume",
        "particles.private.breath_composition_driver",
        "particles.private.mask.r8_texture",
        "particles.private.ordering.gpu_index_remap",
        "particles.private.payload_slot",
        "particles.private.placeholder_compute",
        "quest.native.openxr_vulkan_base",
        "renderer.background.solid_black",
        "renderer.private_particles",
        "sensor.polar_h10_ble",
        "ui.same_apk_breath_mapping_panel",
        "ui.same_apk_control_panel"
    )
    $actual = @($result.resolved_feature_ids | Sort-Object)
    if (($actual -join "`n") -ne (($expected | Sort-Object) -join "`n")) {
        throw "Resolved breath composition driver feature closure was not exact: $($actual -join ', ')"
    }
    $binding = [string]$result.breath_composition_activation_sha256
    if ([string]::IsNullOrWhiteSpace($binding) -or
        [string]$result.breath_composition_driver_activation_sha256 -cne $binding -or
        [string]$lock.breath_composition_driver_activation.sha256 -cne $binding) {
        throw "Driver activation did not bind the exact composition activation digest"
    }
    $profilePath = [System.IO.Path]::GetFullPath([string]$lock.generated_outputs.runtime_profile)
    $profile = Get-Content -Raw -LiteralPath $profilePath | ConvertFrom-Json
    $bindingProperty = "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.activation.binding_sha256"
    $bindingRows = @($profile.set_properties | Where-Object { [string]$_.name -ceq $bindingProperty })
    if ($bindingRows.Count -ne 1 -or [string]$bindingRows[0].value -cne $binding) {
        throw "Executable runtime profile omitted the exact driver activation binding"
    }
    $buildEnvPath = [System.IO.Path]::GetFullPath([string]$lock.generated_outputs.build_env)
    $buildEnv = Get-Content -Raw -LiteralPath $buildEnvPath | ConvertFrom-Json
    $packagedRows = @($buildEnv.env | Where-Object {
        [string]$_.name -ceq "RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_DRIVER_EXPECTED_BINDING_SHA256"
    })
    if ($packagedRows.Count -ne 1 -or [string]$packagedRows[0].value -cne $binding) {
        throw "Driver-specific packaged binding was not emitted exactly once"
    }

    $unlistedRoot = Join-Path $runRoot "unlisted"
    $unlistedResultPath = Join-Path $unlistedRoot "result.json"
    New-Item -ItemType Directory -Path $unlistedRoot -Force | Out-Null
    & pwsh -NoProfile -File $resolverPath -AppSpec $unlistedAppSpecPath -OutputRoot $unlistedRoot -ResultJsonPath $unlistedResultPath -DryRun | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Unlisted breath composition app resolution failed with exit $LASTEXITCODE"
    }
    $unlistedResult = Get-Content -Raw -LiteralPath $unlistedResultPath | ConvertFrom-Json
    if ($null -ne $unlistedResult.breath_composition_driver_activation_sha256) {
        throw "Unlisted app unexpectedly received a runtime driver activation binding"
    }
    $unlistedLock = Get-Content -Raw -LiteralPath ([string]$unlistedResult.feature_lock_path) | ConvertFrom-Json
    $unlistedBuildEnv = Get-Content -Raw -LiteralPath ([string]$unlistedLock.generated_outputs.build_env) | ConvertFrom-Json
    $unlistedPackagedRows = @($unlistedBuildEnv.env | Where-Object {
        [string]$_.name -ceq "RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_DRIVER_EXPECTED_BINDING_SHA256"
    })
    if ($unlistedPackagedRows.Count -ne 0) {
        throw "Unlisted app unexpectedly received the driver-specific packaged binding"
    }
} finally {
    if (Test-Path -LiteralPath $runRoot) {
        Remove-Item -LiteralPath $runRoot -Recurse -Force
    }
}

Write-Host "Rusty Quest breath composition driver static validation passed"
