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
    "input.right_primary_private_particle_recenter",
    "particles.private.mask.r8_texture",
    "particles.private.ordering.gpu_index_remap",
    "particles.private.payload_slot",
    "particles.private.placeholder_compute",
    "particles.private.tracers",
    "quest.native.openxr_vulkan_base",
    "renderer.background.solid_black",
    "renderer.private_particles"
) -Actual @($lock.selected_feature_ids | ForEach-Object { [string]$_ })
Assert-SetEquals -Label "canary Android permission surface" -Expected @(
    "org.khronos.openxr.permission.OPENXR",
    "org.khronos.openxr.permission.OPENXR_SYSTEM"
) -Actual @($lock.android_manifest.permissions | ForEach-Object { [string]$_ })
Assert-SetEquals -Label "canary Android uses-feature surface" -Expected @(
    "android.hardware.vr.headtracking",
    "android.opengl.gles.3.1"
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
    "particles/private/payload-slot",
    "particles/private/placeholder",
    "particles/private/ordering/gpu-index-remap",
    "particles/private/mask/r8-texture",
    "particles/private/tracers",
    "particles/private/renderer",
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

$manifestText = Get-Content -Raw -LiteralPath $manifestPath
foreach ($forbiddenPermission in @(
    "android.permission.CAMERA",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "com.oculus.permission.HAND_TRACKING",
    "horizonos.permission.HEADSET_CAMERA",
    "horizonos.permission.SPATIAL_CAMERA",
    "horizonos.permission.USE_SCENE"
)) {
    if ($manifestText -match [regex]::Escape($forbiddenPermission)) {
        throw "Generated canary manifest contains denied permission: $forbiddenPermission"
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
