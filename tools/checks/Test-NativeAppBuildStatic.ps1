param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = Resolve-Path $RepoRoot
$featureDir = Join-Path $repoRootPath "fixtures\native-app-features"
$appBuildDir = Join-Path $repoRootPath "fixtures\native-app-builds"
$schemaDir = Join-Path $repoRootPath "schemas"
$profileGate = Join-Path $repoRootPath "tools\Test-NativeAppBuildProfile.ps1"
$resolver = Join-Path $repoRootPath "tools\Resolve-NativeAppBuild.ps1"
$permissionTool = Join-Path $repoRootPath "tools\Grant-NativeRendererPermissions.ps1"

function Read-Json {
    param([Parameter(Mandatory=$true)][string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Assert-RequiredProperty {
    param(
        [Parameter(Mandatory=$true)]$Object,
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Label
    )
    if ($null -eq $Object.PSObject.Properties[$Name]) {
        throw "$Label is missing required property: $Name"
    }
}

function Assert-FeatureDescriptorShape {
    param(
        [Parameter(Mandatory=$true)]$Feature,
        [Parameter(Mandatory=$true)][string]$Path
    )
    foreach ($field in @("schema", "feature_id", "module_path", "module_kind", "settings_surface", "owner_lane", "status", "description", "provides", "depends_on", "incompatible_with", "exclusive_groups", "android_manifest", "runtime_profile", "build_inputs", "markers", "validation", "public_private_boundary")) {
        Assert-RequiredProperty -Object $Feature -Name $field -Label $Path
    }
    if ([string]$Feature.schema -ne "rusty.quest.native_app_feature.v1") {
        throw "$Path has unsupported schema: $($Feature.schema)"
    }
    if ([string]::IsNullOrWhiteSpace([string]$Feature.feature_id)) {
        throw "$Path has empty feature_id"
    }
    if ([string]::IsNullOrWhiteSpace([string]$Feature.module_path)) {
        throw "$Path has empty module_path"
    }
    if ([string]$Feature.module_path -notmatch '^[a-z0-9_]+([-/][a-z0-9_]+)*$') {
        throw "$Path has invalid module_path: $($Feature.module_path)"
    }
    Assert-RequiredProperty -Object $Feature.settings_surface -Name "authority" -Label "$Path settings_surface"
    Assert-RequiredProperty -Object $Feature.settings_surface -Name "adapter" -Label "$Path settings_surface"
    if ([string]$Feature.settings_surface.authority -ne "rusty.quest.native_app_settings.v1") {
        throw "$Path has wrong settings authority: $($Feature.settings_surface.authority)"
    }
    foreach ($field in @("permissions", "uses_features", "activities", "services", "queries")) {
        Assert-RequiredProperty -Object $Feature.android_manifest -Name $field -Label "$Path android_manifest"
    }
    foreach ($field in @("set", "clear_families", "expected_render_modes")) {
        Assert-RequiredProperty -Object $Feature.runtime_profile -Name $field -Label "$Path runtime_profile"
    }
    foreach ($field in @("env", "assets", "shaders")) {
        Assert-RequiredProperty -Object $Feature.build_inputs -Name $field -Label "$Path build_inputs"
    }
    foreach ($field in @("required", "forbidden")) {
        Assert-RequiredProperty -Object $Feature.markers -Name $field -Label "$Path markers"
    }
}

foreach ($path in @(
    (Join-Path $schemaDir "rusty.quest.native_app_feature.v1.schema.json"),
    (Join-Path $schemaDir "rusty.quest.native_app_build.v1.schema.json"),
    (Join-Path $schemaDir "rusty.quest.native_app_feature_lock.v1.schema.json"),
    (Join-Path $schemaDir "rusty.quest.native_app_settings.v1.schema.json"),
    $resolver,
    $permissionTool,
    $profileGate
)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Native app-build workflow file is missing: $path"
    }
}

$settingsSchema = Read-Json -Path (Join-Path $schemaDir "rusty.quest.native_app_settings.v1.schema.json")
if (-not (@($settingsSchema.required | ForEach-Object { [string]$_ }) -contains "settings_hotload")) {
    throw "Native app settings schema must require settings_hotload"
}
$featureLockSchema = Read-Json -Path (Join-Path $schemaDir "rusty.quest.native_app_feature_lock.v1.schema.json")
foreach ($requiredLockField in @("app_spec_path", "settings_hotload", "permission_pregrant")) {
    if (-not (@($featureLockSchema.required | ForEach-Object { [string]$_ }) -contains $requiredLockField)) {
        throw "Native app feature lock schema must require $requiredLockField"
    }
}
$resolverText = Get-Content -Raw -LiteralPath $resolver
foreach ($requiredResolverNeedle in @(
    "hotloadable-low-rate-settings-with-explicit-restart-boundaries",
    "pregrant-declared-permissions-before-first-launch",
    "same-process-jni-live-queue",
    "app-private-revision-sidecar",
    "app_spec_sha256",
    "feature_descriptors",
    "PROJECT_MEDIA",
    "USE_SCENE_DATA",
    "environmentDepthProviderState=provider-running",
    "com.oculus.vr.focusaware",
    'android:resizeableActivity="false"',
    "ControlPanelActivity",
    'android:hardwareAccelerated="true"',
    'android:defaultHeight="720dp"',
    'android:defaultWidth="960dp"',
    "com.oculus.intent.category.2D"
)) {
    if ($resolverText -notmatch [regex]::Escape($requiredResolverNeedle)) {
        throw "Native app-build resolver is missing workflow guardrail: $requiredResolverNeedle"
    }
}
$permissionToolText = Get-Content -Raw -LiteralPath $permissionTool
if ($permissionToolText -notmatch '\[string\[\]\]\$Permissions') {
    throw "Permission pregrant helper must accept an explicit permission list"
}
foreach ($requiredPermissionToolNeedle in @("GrantUseSceneDataAppOp", "USE_SCENE_DATA")) {
    if ($permissionToolText -notmatch [regex]::Escape($requiredPermissionToolNeedle)) {
        throw "Permission pregrant helper is missing scene-data app-op guardrail: $requiredPermissionToolNeedle"
    }
}

$featureFiles = @(Get-ChildItem -LiteralPath $featureDir -Filter "*.feature.json" -File -Recurse |
    Where-Object {
        $_.FullName.Replace("\", "/") -notmatch '/damaged/'
    } |
    Sort-Object FullName)
if ($featureFiles.Count -lt 12) {
    throw "Native app-build feature library is unexpectedly small: $($featureFiles.Count)"
}
$featureIds = @{}
foreach ($file in $featureFiles) {
    $feature = Read-Json -Path $file.FullName
    Assert-FeatureDescriptorShape -Feature $feature -Path $file.FullName
    $featureId = [string]$feature.feature_id
    if ($featureIds.ContainsKey($featureId)) {
        throw "Duplicate native app-build feature id: $featureId"
    }
    $featureIds[$featureId] = $true
}

foreach ($requiredFeature in @(
    "quest.native.openxr_vulkan_base",
    "renderer.background.solid_black",
    "renderer.private_particles",
    "particles.private.payload_slot",
    "particles.private.placeholder_compute",
    "particles.private.ordering.gpu_index_remap",
    "particles.private.mask.r8_texture",
    "particles.tracers.snapshot_rows",
    "input.right_primary_private_particle_recenter",
    "camera.hwb",
    "display_composite",
    "video_projection",
    "renderer.stimulus_volume",
    "environment_depth",
    "environment_depth.projection_sampler",
    "hand_mesh_visual",
    "hand_anchor_particles",
    "particles.hand_anchor.ordering.gpu_index_remap",
    "sdf_visual",
    "projection_target.breathing_room",
    "manifold.bridge",
    "makepad_runtime"
)) {
    if (-not $featureIds.ContainsKey($requiredFeature)) {
        throw "Native app-build feature library is missing required seed feature: $requiredFeature"
    }
}

$privateParticleFeature = Read-Json -Path (Join-Path $featureDir "particles\private\renderer\renderer.private_particles.feature.json")
foreach ($marker in @(
    "RUSTY_QUEST_NATIVE_RENDERER channel=private-particle-anchor",
    "privateParticleWorldAnchorForwardAxis=",
    "privateParticleComputeFovTangentPayload=world-anchor-forward-axis",
    "privateParticleDiagnosticStorageBinding=9",
    "privateParticleDiagnosticWords=16",
    "privateParticleDiagnosticTracerSpawnedCount=",
    "privateParticleDiagnosticTracerDiscardedCount=",
    "privateParticleDiagnosticCpuFullBufferReadback=false"
)) {
    if (@($privateParticleFeature.markers.required) -notcontains $marker) {
        throw "Private particle feature must require generic diagnostic marker: $marker"
    }
}

$publicFixtureFiles = @()
$publicFixtureFiles += Get-ChildItem -LiteralPath $featureDir -Filter "*.feature.json" -File -Recurse |
    Where-Object { $_.FullName.Replace("\", "/") -notmatch '/damaged/' }
$publicFixtureFiles += Get-ChildItem -LiteralPath $appBuildDir -Filter "*.app.json" -File -Recurse |
    Where-Object { $_.FullName.Replace("\", "/") -notmatch '/damaged/' }
foreach ($file in $publicFixtureFiles) {
    $text = Get-Content -Raw -LiteralPath $file.FullName
    foreach ($forbidden in @("S:/", "S:\\", "rusty-gpu-viscereality", "Rusty-Viscereality", "viscereality")) {
        if ($text -match [regex]::Escape($forbidden)) {
            throw "Public native app-build fixture contains private/local term '$forbidden': $($file.FullName)"
        }
    }
}

$damagedFeatureFiles = @(Get-ChildItem -LiteralPath (Join-Path $featureDir "damaged") -Filter "*.feature.json" -File -Recurse | Sort-Object FullName)
if ($damagedFeatureFiles.Count -lt 2) {
    throw "Native app-build damaged feature descriptor fixtures are missing"
}
foreach ($file in $damagedFeatureFiles) {
    $failed = $false
    try {
        $feature = Read-Json -Path $file.FullName
        Assert-FeatureDescriptorShape -Feature $feature -Path $file.FullName
        foreach ($property in @($feature.runtime_profile.set.PSObject.Properties)) {
            if ([string]$property.Name -like "*.high_rate_json_payload" -and [string]$property.Value -ne "false") {
                throw "Damaged high-rate JSON descriptor rejected"
            }
        }
    } catch {
        $failed = $true
    }
    if (-not $failed) {
        throw "Damaged native app-build feature descriptor was accepted by static shape gate: $($file.FullName)"
    }
}

$trackedGenerated = & git -C $repoRootPath ls-files "local-artifacts/native-app-builds"
if ($LASTEXITCODE -ne 0) {
    throw "git ls-files failed while checking generated native app-build artifacts"
}
if (-not [string]::IsNullOrWhiteSpace(($trackedGenerated -join "`n"))) {
    throw "Generated native app-build artifacts must not be tracked under local-artifacts/native-app-builds"
}

& powershell -NoProfile -ExecutionPolicy Bypass -File $profileGate -RepoRoot $repoRootPath
if ($LASTEXITCODE -ne 0) {
    throw "Native app-build profile gate failed with exit code $LASTEXITCODE"
}

Write-Host "Rusty Quest native app-build static validation passed"
