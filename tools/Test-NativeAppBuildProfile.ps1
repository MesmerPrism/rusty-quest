param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$repoRootPath = Resolve-Path $RepoRoot
$resolver = Join-Path $repoRootPath "tools\Resolve-NativeAppBuild.ps1"
$validSpecDir = Join-Path $repoRootPath "fixtures\native-app-builds"
$damagedSpecDir = Join-Path $validSpecDir "damaged"

function Read-Json {
    param([Parameter(Mandatory=$true)][string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Assert-SetEquals {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [string[]]$Expected,
        [string[]]$Actual
    )
    $expectedSorted = @($Expected | Sort-Object)
    $actualSorted = @($Actual | Sort-Object)
    if (($expectedSorted -join "`n") -ne ($actualSorted -join "`n")) {
        throw "$Label mismatch. Expected [$($expectedSorted -join ', ')] but found [$($actualSorted -join ', ')]."
    }
}

function Invoke-Resolver {
    param([Parameter(Mandatory=$true)][string]$SpecPath)
    & powershell -NoProfile -ExecutionPolicy Bypass -File $resolver -AppSpec $SpecPath -DryRun
    if ($LASTEXITCODE -ne 0) {
        throw "Resolve-NativeAppBuild.ps1 failed for $SpecPath with exit code $LASTEXITCODE"
    }
}

if (-not (Test-Path -LiteralPath $resolver)) {
    throw "Missing native app-build resolver: $resolver"
}

$validSpecs = @(Get-ChildItem -LiteralPath $validSpecDir -Filter "*.app.json" -File -Recurse |
    Where-Object {
        $_.FullName.Replace("\", "/") -notmatch '/damaged/'
    } |
    Sort-Object FullName)
if ($validSpecs.Count -eq 0) {
    throw "No valid native app-build specs found under $validSpecDir"
}

foreach ($spec in $validSpecs) {
    Invoke-Resolver -SpecPath $spec.FullName
}

$canaryDir = Join-Path $repoRootPath "local-artifacts\native-app-builds\private_particle_solid_black_canary"
$lockPath = Join-Path $canaryDir "feature-lock.json"
$profilePath = Join-Path $canaryDir "runtime-profile.json"
$settingsPath = Join-Path $canaryDir "native-app-settings.json"
$propertyPlanPath = Join-Path $canaryDir "property-write-plan.json"
$manifestPath = Join-Path $canaryDir "AndroidManifest.xml"
$buildEnvPath = Join-Path $canaryDir "build-env.json"
$buildManifestPath = Join-Path $canaryDir "build-manifest.json"
$auditPath = Join-Path $canaryDir "app-build-audit.json"
foreach ($path in @($lockPath, $profilePath, $settingsPath, $propertyPlanPath, $manifestPath, $buildEnvPath, $buildManifestPath, $auditPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Expected generated native app-build artifact is missing: $path"
    }
}

$lock = Read-Json -Path $lockPath
Assert-SetEquals -Label "canary selected feature closure" -Expected @(
    "input.controllers_and_hands_optional",
    "input.right_primary_private_particle_recenter",
    "particles.private.mask.r8_texture",
    "particles.private.ordering.gpu_index_remap",
    "particles.private.payload_slot",
    "particles.private.placeholder_compute",
    "particles.tracers.snapshot_rows",
    "quest.native.openxr_vulkan_base",
    "renderer.background.solid_black",
    "renderer.private_particles",
    "ui.same_apk_control_panel"
) -Actual @($lock.selected_feature_ids | ForEach-Object { [string]$_ })
Assert-SetEquals -Label "canary Android permission surface" -Expected @(
    "com.oculus.permission.HAND_TRACKING",
    "org.khronos.openxr.permission.OPENXR",
    "org.khronos.openxr.permission.OPENXR_SYSTEM"
) -Actual @($lock.android_manifest.permissions | ForEach-Object { [string]$_ })
if ($null -eq $lock.PSObject.Properties["settings_hotload"]) {
    throw "Generated canary feature lock is missing settings_hotload policy"
}
if ([string]$lock.settings_hotload.policy -ne "hotloadable-low-rate-settings-with-explicit-restart-boundaries") {
    throw "Generated canary feature lock has wrong settings hotload policy: $($lock.settings_hotload.policy)"
}
foreach ($transport in @("startup-runtime-profile", "same-process-jni-live-queue", "app-private-revision-sidecar")) {
    if (-not (@($lock.settings_hotload.allowed_transports | ForEach-Object { [string]$_ }) -contains $transport)) {
        throw "Generated canary feature lock hotload policy is missing transport: $transport"
    }
}
if (-not [bool]$lock.settings_hotload.high_rate_payloads_forbidden) {
    throw "Generated canary feature lock must forbid high-rate settings payloads"
}
if ($null -eq $lock.PSObject.Properties["permission_pregrant"]) {
    throw "Generated canary feature lock is missing permission_pregrant plan"
}
Assert-SetEquals -Label "canary permission pregrant declared surface" `
    -Expected @($lock.android_manifest.permissions | ForEach-Object { [string]$_ }) `
    -Actual @($lock.permission_pregrant.declared_permissions | ForEach-Object { [string]$_ })
if ([string]$lock.permission_pregrant.policy -ne "pregrant-declared-permissions-before-first-launch") {
    throw "Generated canary permission pregrant plan has wrong policy: $($lock.permission_pregrant.policy)"
}
if (-not [bool]$lock.permission_pregrant.required_before_first_launch) {
    throw "Generated canary must require permission pregrant before first launch for optional hand tracking"
}
if ([string]::IsNullOrWhiteSpace([string]$lock.permission_pregrant.command)) {
    throw "Generated canary permission pregrant plan must emit a device command for optional hand tracking"
}
if (-not (@($lock.permission_pregrant.runtime_dangerous_permissions | ForEach-Object { [string]$_ }) -contains "com.oculus.permission.HAND_TRACKING")) {
    throw "Generated canary permission pregrant plan must include HAND_TRACKING as a runtime dangerous permission"
}
Assert-SetEquals -Label "canary Android uses-feature surface" -Expected @(
    "android.hardware.vr.headtracking",
    "android.opengl.gles.3.1",
    "oculus.software.handtracking"
) -Actual @($lock.android_manifest.uses_features | ForEach-Object { [string]$_ })

$forbiddenFeatures = @(
    "camera.hwb",
    "display_composite",
    "environment_depth",
    "hand_anchor_particles",
    "hand_mesh_visual",
    "makepad_runtime",
    "private_layer",
    "projection_target.breathing_room",
    "renderer.background.native_passthrough",
    "renderer.stimulus_volume",
    "sdf_visual",
    "video_projection"
)
foreach ($featureId in $forbiddenFeatures) {
    if (@($lock.selected_feature_ids) -contains $featureId) {
        throw "Canary feature lock accidentally selected denied feature: $featureId"
    }
}

$profile = Read-Json -Path $profilePath
if ([string]$profile.schema -ne "rusty.quest.runtime_profile.v1") {
    throw "Generated canary runtime profile has wrong schema: $($profile.schema)"
}
$renderModeSet = @($profile.set_properties | Where-Object { [string]$_.name -eq "debug.rustyquest.native_renderer.render.mode" })
if ($renderModeSet.Count -ne 1 -or [string]$renderModeSet[0].value -ne "solid-black-private-particles") {
    throw "Generated canary runtime profile did not set solid-black-private-particles render mode"
}
foreach ($name in @(
    "debug.rustyquest.native_renderer.display_composite.high_rate_json_payload",
    "debug.rustyquest.native_renderer.environment_depth.high_rate_json_payload",
    "debug.rustyquest.native_renderer.video_projection.high_rate_json_payload"
)) {
    $entry = @($profile.set_properties | Where-Object { [string]$_.name -eq $name })
    if ($entry.Count -ne 1 -or [string]$entry[0].value -ne "false") {
        throw "Generated canary profile must explicitly set $name=false"
    }
}

$settings = Read-Json -Path $settingsPath
if ([string]$settings.schema -ne "rusty.quest.native_app_settings.v1") {
    throw "Generated canary native app settings has wrong schema: $($settings.schema)"
}
foreach ($requiredModule in @(
    "core/openxr-vulkan",
    "background/solid-black",
    "input/controllers-and-hands",
    "particles/private/payload-slot",
    "particles/private/placeholder",
    "particles/private/ordering/gpu-index-remap",
    "particles/private/mask/r8-texture",
    "particles/private/renderer",
    "particles/tracers/snapshot-rows",
    "input/private-particles/right-primary-recenter"
)) {
    if (-not (@($settings.modules | ForEach-Object { [string]$_.module_path }) -contains $requiredModule)) {
        throw "Generated canary native app settings is missing required module: $requiredModule"
    }
}
foreach ($forbiddenModule in @(
    "camera/hwb",
    "display/composite",
    "environment/depth",
    "hand/mesh",
    "particles/hand-anchor/renderer",
    "private-layer",
    "projection-target/breathing-room",
    "stimulus/volume",
    "video/projection"
)) {
    if (@($settings.modules | ForEach-Object { [string]$_.module_path }) -contains $forbiddenModule) {
        throw "Generated canary native app settings selected forbidden module: $forbiddenModule"
    }
}
foreach ($requiredDisabled in @(
    "camera",
    "display_composite",
    "environment_depth",
    "hand_anchor_particles",
    "hand_mesh",
    "private_layer",
    "projection_target",
    "sdf",
    "stimulus_volume",
    "video_projection"
)) {
    if (-not (@($settings.disabled_modules | ForEach-Object { [string]$_ }) -contains $requiredDisabled)) {
        throw "Generated canary native app settings does not disable module family: $requiredDisabled"
    }
}
if ([string]$settings.values.'native_renderer.render.mode'.value -ne "solid-black-private-particles") {
    throw "Generated canary native app settings did not set the master render mode"
}
if ($null -eq $settings.PSObject.Properties["settings_hotload"]) {
    throw "Generated canary native app settings is missing settings_hotload policy"
}
if ([string]$settings.settings_hotload.master_surface -ne "local-artifacts/native-app-builds/private_particle_solid_black_canary/native-app-settings.json") {
    throw "Generated canary native app settings hotload policy points at the wrong master surface: $($settings.settings_hotload.master_surface)"
}
foreach ($restartBoundary in @("Android manifest permissions", "build inputs", "OpenXR provider")) {
    if (-not (@($settings.settings_hotload.restart_required_scope | ForEach-Object { [string]$_ }) -match [regex]::Escape($restartBoundary))) {
        throw "Generated canary native app settings hotload policy is missing restart boundary containing: $restartBoundary"
    }
}

$manifestText = Get-Content -Raw -LiteralPath $manifestPath
foreach ($forbiddenPermission in @(
    "android.permission.CAMERA",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "horizonos.permission.HEADSET_CAMERA",
    "horizonos.permission.SPATIAL_CAMERA",
    "horizonos.permission.USE_SCENE"
)) {
    if ($manifestText -match [regex]::Escape($forbiddenPermission)) {
        throw "Generated canary manifest contains denied permission: $forbiddenPermission"
    }
}
if ($manifestText -notmatch 'com\.oculus\.permission\.HAND_TRACKING') {
    throw "Generated canary manifest must declare HAND_TRACKING for optional hands-and-controllers support"
}
if ($manifestText -notmatch 'android:name="oculus\.software\.handtracking"\s+android:required="false"') {
    throw "Generated canary manifest must declare optional oculus.software.handtracking"
}
if ($manifestText -notmatch 'android:name="\.ControlPanelActivity"') {
    throw "Generated canary manifest must include the same-APK control panel activity"
}

$stimulusDir = Join-Path $repoRootPath "local-artifacts\native-app-builds\native_stimulus_volume_panel"
$stimulusSettingsPath = Join-Path $stimulusDir "native-app-settings.json"
if (-not (Test-Path -LiteralPath $stimulusSettingsPath)) {
    throw "Expected generated stimulus native app settings artifact is missing: $stimulusSettingsPath"
}
$stimulusSettings = Read-Json -Path $stimulusSettingsPath
if (@($stimulusSettings.settings_hotload.runtime_polled_property_families | ForEach-Object { [string]$_ }) -contains "private_particles") {
    throw "Generated stimulus app settings must not advertise private-particle runtime-polled hotload families"
}
foreach ($propertyName in @($stimulusSettings.settings_hotload.accepted_scalar_properties | ForEach-Object { [string]$_ })) {
    if ($propertyName -like "*.private_particles.*") {
        throw "Generated stimulus app settings must not advertise private-particle hotload property: $propertyName"
    }
}

$handLabDir = Join-Path $repoRootPath "local-artifacts\native-app-builds\native_openxr_hand_lab"
$handLabLockPath = Join-Path $handLabDir "feature-lock.json"
$handLabProfilePath = Join-Path $handLabDir "runtime-profile.json"
$handLabSettingsPath = Join-Path $handLabDir "native-app-settings.json"
foreach ($path in @($handLabLockPath, $handLabProfilePath, $handLabSettingsPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Expected generated native OpenXR hand lab artifact is missing: $path"
    }
}
$handLabLock = Read-Json -Path $handLabLockPath
Assert-SetEquals -Label "native OpenXR hand lab selected feature closure" -Expected @(
    "hand_mesh_live_input",
    "hand_mesh_visual",
    "input.controllers_and_hands_optional",
    "quest.native.openxr_vulkan_base",
    "renderer.background.solid_black"
) -Actual @($handLabLock.selected_feature_ids | ForEach-Object { [string]$_ })
Assert-SetEquals -Label "native OpenXR hand lab Android permission surface" -Expected @(
    "com.oculus.permission.HAND_TRACKING",
    "org.khronos.openxr.permission.OPENXR",
    "org.khronos.openxr.permission.OPENXR_SYSTEM"
) -Actual @($handLabLock.android_manifest.permissions | ForEach-Object { [string]$_ })
foreach ($deniedFeature in @("hand_anchor_particles", "renderer.private_particles", "camera.hwb", "sdf_visual", "video_projection")) {
    if (@($handLabLock.selected_feature_ids | ForEach-Object { [string]$_ }) -contains $deniedFeature) {
        throw "Native OpenXR hand lab feature lock accidentally selected denied feature: $deniedFeature"
    }
}
foreach ($marker in @(
    "openxrDefaultHandVisualRequested=false",
    "customHandMeshVisualRequested=true",
    "handMeshRealHandsVisible=true",
    "handMeshVisualWireframeEnabled=true",
    "handMeshVisualWireframeMode=shader-barycentric-triangle-edges",
    "compactHandInputSourceMode=live-meta-openxr-hand-tracking",
    "handAnchorParticlesEnabled=false"
)) {
    if (@($handLabLock.expected_markers.required | ForEach-Object { [string]$_ }) -notcontains $marker) {
        throw "Native OpenXR hand lab feature lock is missing required marker: $marker"
    }
}
$handLabProfile = Read-Json -Path $handLabProfilePath
$handLabRenderModeSet = @($handLabProfile.set_properties | Where-Object { [string]$_.name -eq "debug.rustyquest.native_renderer.render.mode" })
if ($handLabRenderModeSet.Count -ne 1 -or [string]$handLabRenderModeSet[0].value -ne "solid-black-hands-and-grafts") {
    throw "Native OpenXR hand lab runtime profile did not set solid-black-hands-and-grafts render mode"
}
$handLabSettings = Read-Json -Path $handLabSettingsPath
foreach ($requiredModule in @(
    "background/solid-black",
    "core/openxr-vulkan",
    "hand/live-input",
    "hand/mesh",
    "input/controllers-and-hands"
)) {
    if (-not (@($handLabSettings.modules | ForEach-Object { [string]$_.module_path }) -contains $requiredModule)) {
        throw "Generated native OpenXR hand lab settings are missing required module: $requiredModule"
    }
}
foreach ($forbiddenModule in @("particles/hand-anchor/renderer", "particles/private/renderer", "camera/hwb", "sdf/visual", "video/projection")) {
    if (@($handLabSettings.modules | ForEach-Object { [string]$_.module_path }) -contains $forbiddenModule) {
        throw "Generated native OpenXR hand lab settings selected forbidden module: $forbiddenModule"
    }
}

$damagedSpecs = @(Get-ChildItem -LiteralPath $damagedSpecDir -Filter "*.app.json" -File | Sort-Object Name)
if ($damagedSpecs.Count -eq 0) {
    throw "No damaged native app-build specs found under $damagedSpecDir"
}
foreach ($spec in $damagedSpecs) {
    $failed = $false
    try {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $resolver -AppSpec $spec.FullName -DryRun *> $null
        if ($LASTEXITCODE -ne 0) {
            $failed = $true
        }
    } catch {
        $failed = $true
    }
    if (-not $failed) {
        throw "Damaged native app-build spec was accepted: $($spec.FullName)"
    }
}

Write-Host "Rusty Quest native app-build profile validation passed"
