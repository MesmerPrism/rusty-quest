param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = Resolve-Path $RepoRoot

function Read-RequiredText {
    param([string]$RelativePath)
    $path = Join-Path $repoRootPath $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing simultaneous hands/controllers owner file: $RelativePath"
    }
    Get-Content -Raw -LiteralPath $path
}

function Assert-Tokens {
    param([string]$Text, [string[]]$Tokens, [string]$Label)
    foreach ($token in $Tokens) {
        if (-not $Text.Contains($token)) {
            throw "Simultaneous hands/controllers static check failed for ${Label}: missing $token"
        }
    }
}

$pure = Read-RequiredText "apps\native-renderer-android\native\src\simultaneous_hands_controllers.rs"
$platform = Read-RequiredText "apps\native-renderer-android\native\src\openxr_simultaneous_hands_controllers.rs"
$xrVulkan = Read-RequiredText "apps\native-renderer-android\native\src\xr_vulkan.rs"
$actions = Read-RequiredText "apps\native-renderer-android\native\src\openxr_stimulus_actions.rs"
$resolver = Read-RequiredText "tools\Resolve-NativeAppBuild.ps1"
$builder = Read-RequiredText "tools\Build-NativeRendererAndroid.ps1"
$buildRs = Read-RequiredText "apps\native-renderer-android\native\build.rs"
$descriptorText = Read-RequiredText "fixtures\native-app-features\input\simultaneous-hands-and-controllers\input.simultaneous_hands_and_controllers.feature.json"
$appText = Read-RequiredText "fixtures\native-app-builds\native-breath-four-way-conformance.app.json"

Assert-Tokens $pure @(
    "ActivationDecision",
    "packaged-binding-missing",
    "activation-binding-mismatch",
    "hand-adapter-not-applied",
    "LifecycleCommand::Resume",
    "LifecycleCommand::Pause",
    "stale-session-generation",
    "resume-session-loss-pending",
    "pause_failure_is_one_shot_and_clears_effective_readiness",
    "simultaneousHandsControllersReady=",
    "hands-only must not pass",
    "controller-only must not pass"
) "dependency-light lifecycle"

Assert-Tokens $platform @(
    "XR_META_simultaneous_hands_and_controllers",
    "SystemSimultaneousHandsAndControllersPropertiesMETA::out",
    "supports_simultaneous_hands_and_controllers",
    "resume_simultaneous_hands_and_controllers_tracking",
    "pause_simultaneous_hands_and_controllers_tracking",
    "SESSION_LOSS_PENDING",
    "NEXT_SESSION_GENERATION",
    "staleHandleCall=false"
) "native OpenXR adapter"

Assert-Tokens $xrVulkan @(
    "meta_simultaneous_hands_and_controllers",
    "select_extension",
    "OpenXrSimultaneousHandsControllers::new",
    "simultaneous_hands_controllers.resume",
    "simultaneous_hands_controllers.resume(xr_instance, session)",
    "session-stopping",
    "hard_reset_session_loss",
    "hand_tracker_ready: live_hand_stats.tracker_ready",
    "controller_action_set_ready: controller_readiness.action_set_ready"
) "existing native-renderer lifecycle integration"

Assert-Tokens $actions @(
    "ControllerActionReadiness",
    "current_interaction_profile",
    "INTERACTION_PROFILES",
    "controllerActionSetReady=",
    "handsCannotSubstituteController=true"
) "independent controller readiness"

foreach ($runtimeSource in @($platform, $xrVulkan, $actions)) {
    if ($runtimeSource.Contains("XR_META_detached_controllers")) {
        throw "Native renderer runtime source must not request XR_META_detached_controllers"
    }
}

$descriptor = $descriptorText | ConvertFrom-Json
if ([string]$descriptor.feature_id -ne "input.simultaneous_hands_and_controllers") {
    throw "Simultaneous hands/controllers feature descriptor has the wrong feature id"
}
$dependencies = @($descriptor.depends_on)
foreach ($dependency in @(
    "input.controllers_and_hands_optional",
    "hand_mesh_live_input",
    "hand_mesh_visual"
)) {
    if ($dependencies -cnotcontains $dependency) {
        throw "Combined input descriptor is missing dependency: $dependency"
    }
}
if ([string]$descriptor.runtime_profile.set.'debug.rustyquest.native_renderer.simultaneous_hands_controllers.enabled' -ne "true" -or
    [string]$descriptor.runtime_profile.set.'debug.rustyquest.native_renderer.hand_adapter.lock_sha256' -ne "A1391A7EF2C41F072032283E485F5A9EB58CAB3B74681F150CE24CD9262CF91D") {
    throw "Combined input descriptor does not select the exact simultaneous and hand-adapter closure"
}
if (@($descriptor.android_manifest.permissions).Count -ne 0 -or
    @($descriptor.android_manifest.uses_features).Count -ne 0) {
    throw "Combined input descriptor must not add Android permissions or features beyond dependencies"
}

$app = $appText | ConvertFrom-Json
if (@($app.requested_features) -cnotcontains "input.simultaneous_hands_and_controllers" -or
    @($app.denied_features) -ccontains "hand_mesh_live_input" -or
    @($app.denied_features) -ccontains "hand_mesh_visual") {
    throw "Four-way conformance app does not select the real combined hand/controller closure"
}
if (@($app.denied_features) -cnotcontains "particles.private.polar_rr_heartbeat_pulse") {
    throw "Four-way conformance app must retain explicit RR exclusion"
}

Assert-Tokens "$resolver`n$builder`n$buildRs" @(
    "RUSTY_QUEST_NATIVE_RENDERER_SIMULTANEOUS_HANDS_CONTROLLERS_EXPECTED_BINDING_SHA256",
    "simultaneous_hands_controllers_activation",
    "runtime_set_without_binding",
    "Hands-only or controller-only selection cannot claim simultaneous hands/controllers activation",
    "requires exact applied hand-adapter binding property"
) "resolver and packaged binding"

foreach ($fixture in @(
    "fixtures\native-app-builds\damaged\simultaneous-unapplied-hand-lock.app.json",
    "fixtures\native-app-builds\damaged\simultaneous-hands-only-claim.app.json",
    "fixtures\native-app-builds\damaged\simultaneous-controller-only-claim.app.json"
)) {
    $null = Read-RequiredText $fixture
}

Write-Output "Rusty Quest native renderer simultaneous hands/controllers static validation passed"
