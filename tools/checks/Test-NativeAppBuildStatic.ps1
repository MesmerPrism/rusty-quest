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
$agentsFile = Join-Path $repoRootPath "AGENTS.md"
$nativeAppWorkflowDoc = Join-Path $repoRootPath "docs\NATIVE_APP_BUILD_WORKFLOW.md"

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
    foreach ($envEntry in @($Feature.build_inputs.env)) {
        if ($envEntry -is [string]) {
            throw "$Path build_inputs.env entries must be objects with a name property, not raw strings: $envEntry"
        }
        Assert-RequiredProperty -Object $envEntry -Name "name" -Label "$Path build_inputs.env entry"
        if ([string]::IsNullOrWhiteSpace([string]$envEntry.name)) {
            throw "$Path build_inputs.env entry has empty name"
        }
    }
    foreach ($field in @("required", "forbidden")) {
        Assert-RequiredProperty -Object $Feature.markers -Name $field -Label "$Path markers"
    }
}

foreach ($path in @(
    (Join-Path $schemaDir "rusty.quest.native_app_feature.v1.schema.json"),
    (Join-Path $schemaDir "rusty.quest.native_app_build.v1.schema.json"),
    (Join-Path $schemaDir "rusty.quest.native_app_feature_lock.v1.schema.json"),
    (Join-Path $schemaDir "rusty.quest.native_app_build_resolution_result.v1.schema.json"),
    (Join-Path $schemaDir "rusty.quest.native_app_settings.v1.schema.json"),
    $resolver,
    $permissionTool,
    $profileGate,
    $agentsFile,
    $nativeAppWorkflowDoc
)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Native app-build workflow file is missing: $path"
    }
}

$agentsText = Get-Content -Raw -LiteralPath $agentsFile
foreach ($requiredAgentsNeedle in @(
    "Keep Quest runtime features explicit opt-in",
    "they must not",
    "affect an app package, permissions, runtime profile, scene graph, input route,",
    "marker stream, media path, or private payload behavior unless",
    "descriptor, app spec, runtime profile, Android property, or intent extra"
)) {
    if ($agentsText -notmatch [regex]::Escape($requiredAgentsNeedle)) {
        throw "AGENTS.md is missing native app feature opt-in guardrail: $requiredAgentsNeedle"
    }
}
$workflowText = Get-Content -Raw -LiteralPath $nativeAppWorkflowDoc
foreach ($requiredWorkflowNeedle in @(
    "Every native OpenXR/Vulkan feature is explicit opt-in",
    "must not change APK manifest",
    "or marker expectations until an app-build spec requests it",
    "Source modules may",
    "remain inert until",
    "feature descriptor, runtime profile, app spec, Android property, or intent",
    "deny known-nearby feature families",
    "build_inputs.private_particle_payload_linkage",
    "requires the linked marker; partial,",
    "ambiguous, or multiple payload inventories fail closed"
)) {
    if ($workflowText -notmatch [regex]::Escape($requiredWorkflowNeedle)) {
        throw "Native app-build workflow is missing explicit feature opt-in guardrail: $requiredWorkflowNeedle"
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
$appBuildSchema = Read-Json -Path (Join-Path $schemaDir "rusty.quest.native_app_build.v1.schema.json")
if ($null -eq $appBuildSchema.properties.runtime_profile.properties.set) {
    throw "Native app build schema must support optional app-owned runtime_profile.set"
}
$resolverText = Get-Content -Raw -LiteralPath $resolver
foreach ($requiredResolverNeedle in @(
    "hotloadable-low-rate-settings-with-explicit-restart-boundaries",
    "app-spec:",
    "Assert-NativeRendererPropertyValue -Name `$name -Value `$value -ManifestByName `$ManifestByName",
    "pregrant-declared-permissions-before-first-launch",
    "same-process-jni-live-queue",
    "app-private-revision-sidecar",
    "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_R8",
    "mask_texture is missing required",
    "build_env entries require explicit name and value fields",
    "outside the generic private-particle namespace",
    "rusty.quest.private_particle_payload_linkage.v1",
    "unlinked-placeholder",
    "linked-app-payload",
    "resolver-selected for zero complete private_particle payloads",
    "Resolved marker contract is contradictory",
    "app_spec_sha256",
    "feature_descriptors",
    "PROJECT_MEDIA",
    "USE_SCENE_DATA",
    "environmentDepthProviderState=provider-running",
    "com.oculus.vr.focusaware",
    'android:debuggable="true"',
    'android:resizeableActivity="false"',
    "ControlPanelActivity",
    "QuestionnairePanelActivity",
    'android:hardwareAccelerated="true"',
    'android:defaultHeight="720dp"',
    'android:defaultWidth="960dp"',
    'android:defaultWidth="1040dp"',
    "com.oculus.intent.category.2D",
    "ResultJsonPath",
    "rusty.quest.native_app_build_resolution_result.v1",
    "Assert-CanonicalPathInsideRoot"
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
    "particles.anchor_echo.rows",
    "particles.private.manifold_scalar_driver",
    "particles.private.breath_state_driver",
    "particles.private.polar_acc_breath_source",
    "particles.private.polar_rr_heartbeat_pulse",
    "input.right_secondary_same_apk_panel_triple_press",
    "input.right_primary_private_particle_recenter",
    "camera.hwb",
    "display_composite",
    "video_projection",
    "renderer.stimulus_volume",
    "sensor.polar_h10_ble",
    "environment_depth",
    "environment_depth.projection_sampler",
    "hand_mesh_live_input",
    "hand_mesh_visual",
    "hand_anchor_particles",
    "particles.hand_anchor.ordering.gpu_index_remap",
    "sdf_visual",
    "projection_target.breathing_room",
    "manifold.bridge",
    "manifold.embedded_broker",
    "ui.same_apk_questionnaire_panel",
    "lsl.outlet",
    "lsl.inlet",
    "makepad_runtime"
)) {
    if (-not $featureIds.ContainsKey($requiredFeature)) {
        throw "Native app-build feature library is missing required seed feature: $requiredFeature"
    }
}

$privateParticleFeature = Read-Json -Path (Join-Path $featureDir "particles\private\renderer\renderer.private_particles.feature.json")
if (@($privateParticleFeature.depends_on) -contains "particles.private.placeholder_compute") {
    throw "Private particle renderer must not unconditionally depend on the unlinked placeholder"
}
$privateParticlePayloadSlotFeature = Read-Json -Path (Join-Path $featureDir "particles\private\payload-slot\particles.private.payload_slot.feature.json")
if (@($privateParticlePayloadSlotFeature.markers.required) -contains "privateParticlePayloadLinked=false") {
    throw "Generic private-particle payload slot must remain linkage-state-neutral"
}
foreach ($marker in @(
    "RUSTY_QUEST_NATIVE_RENDERER channel=private-particle-anchor",
    "privateParticleWorldAnchorForwardAxis=",
    "privateParticleComputeFovTangentPayload=world-anchor-forward-axis",
    "privateParticleDiagnosticStorageBinding=9",
    "privateParticleDiagnosticWords=24",
    "privateParticleDiagnosticTracerSpawnedCount=",
    "privateParticleDiagnosticTracerDiscardedCount=",
    "privateParticleDiagnosticAnchorEchoActiveCount=",
    "privateParticleDiagnosticAnchorEchoSpawnedCount=",
    "privateParticleDiagnosticAnchorEchoDiscardedCount=",
    "privateParticleDiagnosticActiveEdgeCount=",
    "privateParticleDiagnosticPassHealthFlags=",
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

& pwsh -NoProfile -ExecutionPolicy Bypass -File $profileGate -RepoRoot $repoRootPath
if ($LASTEXITCODE -ne 0) {
    throw "Native app-build profile gate failed with exit code $LASTEXITCODE"
}

$structuredResultRoot = Join-Path $repoRootPath ("local-artifacts\native-app-builds\structured-result-static-" + [guid]::NewGuid().ToString("N"))
$structuredResultPath = Join-Path $structuredResultRoot "resolution-result.json"
try {
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $resolver `
        -AppSpec "fixtures\native-app-builds\private-particle-solid-black-canary.app.json" `
        -OutputRoot $structuredResultRoot `
        -ResultJsonPath $structuredResultPath `
        -DryRun | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Native app-build structured resolver probe failed with exit code $LASTEXITCODE"
    }
    $structuredResult = Read-Json -Path $structuredResultPath
    if ([string]$structuredResult.schema -ne "rusty.quest.native_app_build_resolution_result.v1") {
        throw "Native app-build structured resolver probe returned the wrong schema"
    }
    $canonicalOutputRoot = [System.IO.Path]::GetFullPath($structuredResultRoot).TrimEnd("\", "/")
    foreach ($artifactPath in @([string]$structuredResult.feature_lock_path, [string]$structuredResult.audit_path)) {
        $canonicalArtifactPath = [System.IO.Path]::GetFullPath($artifactPath)
        $relativeArtifactPath = [System.IO.Path]::GetRelativePath($canonicalOutputRoot, $canonicalArtifactPath)
        if ([System.IO.Path]::IsPathRooted($relativeArtifactPath) -or $relativeArtifactPath -eq ".." -or $relativeArtifactPath.StartsWith("..\") -or $relativeArtifactPath.StartsWith("../")) {
            throw "Structured resolver artifact escaped requested output root: $canonicalArtifactPath"
        }
        if (-not (Test-Path -LiteralPath $canonicalArtifactPath -PathType Leaf)) {
            throw "Structured resolver artifact is missing: $canonicalArtifactPath"
        }
    }
    $unlinkedFeatureLock = Read-Json -Path ([string]$structuredResult.feature_lock_path)
    $unlinkedLinkage = $unlinkedFeatureLock.build_inputs.private_particle_payload_linkage
    if ([string]$unlinkedLinkage.schema -cne "rusty.quest.private_particle_payload_linkage.v1" -or
        [string]$unlinkedLinkage.mode -cne "unlinked-placeholder" -or
        [int]$unlinkedLinkage.complete_payload_count -ne 0 -or
        [string]$unlinkedLinkage.inventory_sha256 -notmatch '^[a-f0-9]{64}$') {
        throw "Native app-build unlinked private-particle linkage receipt is incomplete or malformed"
    }
    if (@($unlinkedFeatureLock.selected_feature_ids) -cnotcontains "particles.private.placeholder_compute") {
        throw "Native app-build zero-payload private-particle closure must select the placeholder"
    }
    foreach ($marker in @(
        "privateParticlePayloadLinked=false",
        "privateParticlePublicAbiOnly=true",
        "privateParticleVisualAcceptance=not-applicable-public-noop"
    )) {
        if (@($unlinkedFeatureLock.expected_markers.required) -cnotcontains $marker) {
            throw "Native app-build unlinked private-particle closure is missing required marker: $marker"
        }
    }
    if (@($unlinkedFeatureLock.expected_markers.forbidden) -cnotcontains "privateParticlePayloadLinked=true") {
        throw "Native app-build unlinked private-particle closure must forbid the linked marker"
    }

    $payloadInputDir = Join-Path $structuredResultRoot "synthetic-private-particle-payload"
    $payloadDataDir = Join-Path $payloadInputDir "data"
    New-Item -ItemType Directory -Force -Path $payloadDataDir | Out-Null
    $payloadShaderPath = Join-Path $payloadInputDir "synthetic.comp.glsl"
    $payloadMaskPath = Join-Path $payloadInputDir "synthetic-mask.r8.bin"
    Set-Content -LiteralPath $payloadShaderPath -Value "#version 450`nvoid main() {}" -Encoding utf8
    [System.IO.File]::WriteAllBytes($payloadMaskPath, [byte[]]@(0))

    $payloadApp = Read-Json -Path (Join-Path $appBuildDir "private-particle-solid-black-canary.app.json")
    $payloadApp.app_id = "private_particle_build_env_static"
    $payloadApp.package_name = "io.github.example.rustyquest.private_particle_build_env_static"
    $payloadApp.settings_assertions.required_modules = @($payloadApp.settings_assertions.required_modules | Where-Object { [string]$_ -ne "particles/private/placeholder" })
    $payloadApp.settings_assertions.forbidden_modules = @($payloadApp.settings_assertions.forbidden_modules) + "particles/private/placeholder"
    $payloadApp.payloads = @([ordered]@{
        kind = "private_particle"
        payload_id = "synthetic-private-particle"
        data_dir = $payloadDataDir
        shader = $payloadShaderPath
        particle_kind = "synthetic-static"
        marker_prefix = "RUSTY_QUEST_SYNTHETIC"
        marker_fields = "syntheticPrivateParticleBuildEnv=true"
        mask_texture = [ordered]@{
            path = $payloadMaskPath
            width = 1
            height = 1
            layers = 1
        }
        build_env = @(
            [ordered]@{ name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_Z_STATIC_TEST"; value = "0.625" },
            [ordered]@{ name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_A_STATIC_TEST"; value = "synthetic" }
        )
    })
    $payloadAppPath = Join-Path $payloadInputDir "synthetic-private-particle.app.json"
    $payloadApp | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $payloadAppPath -Encoding utf8
    $payloadResultPath = Join-Path $structuredResultRoot "payload-resolution-result.json"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $resolver `
        -AppSpec $payloadAppPath `
        -OutputRoot $structuredResultRoot `
        -ResultJsonPath $payloadResultPath `
        -DryRun | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Native app-build private-particle build_env probe failed with exit code $LASTEXITCODE"
    }
    $payloadResult = Read-Json -Path $payloadResultPath
    $payloadFeatureLock = Read-Json -Path ([string]$payloadResult.feature_lock_path)
    $linkedLinkage = $payloadFeatureLock.build_inputs.private_particle_payload_linkage
    if ([string]$linkedLinkage.schema -cne "rusty.quest.private_particle_payload_linkage.v1" -or
        [string]$linkedLinkage.mode -cne "linked-app-payload" -or
        [int]$linkedLinkage.complete_payload_count -ne 1 -or
        [string]$linkedLinkage.inventory_sha256 -notmatch '^[a-f0-9]{64}$') {
        throw "Native app-build linked private-particle linkage receipt is incomplete or malformed"
    }
    if ([string]$linkedLinkage.inventory_sha256 -ceq [string]$unlinkedLinkage.inventory_sha256) {
        throw "Native app-build linked and unlinked private-particle inventories must have distinct identities"
    }
    if (@($payloadFeatureLock.selected_feature_ids) -ccontains "particles.private.placeholder_compute") {
        throw "Native app-build linked private-particle closure must exclude the placeholder"
    }
    if (@($payloadFeatureLock.build_inputs.shaders) -ccontains "apps/native-renderer-android/native/shaders/private_particles_placeholder.comp.glsl") {
        throw "Native app-build linked private-particle build inputs must exclude the placeholder shader"
    }
    if (@($payloadFeatureLock.expected_markers.required) -cnotcontains "privateParticlePayloadLinked=true") {
        throw "Native app-build linked private-particle closure must require the linked marker"
    }
    foreach ($marker in @(
        "privateParticlePayloadLinked=false",
        "privateParticlePublicAbiOnly=true",
        "privateParticleVisualAcceptance=not-applicable-public-noop"
    )) {
        if (@($payloadFeatureLock.expected_markers.forbidden) -cnotcontains $marker) {
            throw "Native app-build linked private-particle closure must forbid unlinked marker: $marker"
        }
    }
    $payloadBuildEnvPath = [string]$payloadFeatureLock.generated_outputs.build_env
    if (-not [System.IO.Path]::IsPathRooted($payloadBuildEnvPath)) {
        $payloadBuildEnvPath = Join-Path $repoRootPath $payloadBuildEnvPath
    }
    $payloadBuildEnv = Read-Json -Path $payloadBuildEnvPath
    $payloadEnvEntries = @($payloadBuildEnv.env)
    $payloadEnvNames = @($payloadEnvEntries | ForEach-Object { [string]$_.name })
    $sortedPayloadEnvNames = @($payloadEnvNames | Sort-Object)
    if (($payloadEnvNames -join "`n") -cne ($sortedPayloadEnvNames -join "`n")) {
        throw "Native app-build private-particle build_env output is not deterministic by name"
    }
    foreach ($expectedPayloadEnv in @(
        @{ name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_A_STATIC_TEST"; value = "synthetic" },
        @{ name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_Z_STATIC_TEST"; value = "0.625" }
    )) {
        $matches = @($payloadEnvEntries | Where-Object { [string]$_.name -eq [string]$expectedPayloadEnv.name })
        if ($matches.Count -ne 1 -or [string]$matches[0].value -cne [string]$expectedPayloadEnv.value -or [string]$matches[0].source -cne "app-payload:private_particle_build_env_static:synthetic-private-particle") {
            throw "Native app-build private-particle build_env did not preserve the declared value and source for $($expectedPayloadEnv.name)"
        }
    }

    $payloadRepeatResultPath = Join-Path $structuredResultRoot "payload-resolution-result-repeat.json"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $resolver `
        -AppSpec $payloadAppPath `
        -OutputRoot $structuredResultRoot `
        -ResultJsonPath $payloadRepeatResultPath `
        -DryRun | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Native app-build repeated private-particle build_env probe failed with exit code $LASTEXITCODE"
    }
    $payloadRepeatResult = Read-Json -Path $payloadRepeatResultPath
    if ([string]$payloadRepeatResult.resolution_fingerprint -cne [string]$payloadResult.resolution_fingerprint -or [string]$payloadRepeatResult.feature_lock_sha256 -cne [string]$payloadResult.feature_lock_sha256) {
        throw "Native app-build private-particle build_env resolution is not deterministic"
    }
    $payloadRepeatFeatureLock = Read-Json -Path ([string]$payloadRepeatResult.feature_lock_path)
    if ([string]$payloadRepeatFeatureLock.build_inputs.private_particle_payload_linkage.inventory_sha256 -cne [string]$linkedLinkage.inventory_sha256 -or
        (@($payloadRepeatFeatureLock.selected_feature_ids) -join "`n") -cne (@($payloadFeatureLock.selected_feature_ids) -join "`n")) {
        throw "Native app-build private-particle linkage identity or closure is not deterministic"
    }

    $changedInventoryApp = Read-Json -Path $payloadAppPath
    $changedInventoryApp.payloads[0].marker_fields = "syntheticPrivateParticleBuildEnv=true;inventoryVariant=2"
    $changedInventoryAppPath = Join-Path $payloadInputDir "changed-inventory.app.json"
    $changedInventoryApp | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $changedInventoryAppPath -Encoding utf8
    $changedInventoryResultPath = Join-Path $structuredResultRoot "changed-inventory-resolution-result.json"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $resolver `
        -AppSpec $changedInventoryAppPath `
        -OutputRoot $structuredResultRoot `
        -ResultJsonPath $changedInventoryResultPath `
        -DryRun | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Native app-build changed private-particle inventory probe failed with exit code $LASTEXITCODE"
    }
    $changedInventoryResult = Read-Json -Path $changedInventoryResultPath
    $changedInventoryFeatureLock = Read-Json -Path ([string]$changedInventoryResult.feature_lock_path)
    if ([string]$changedInventoryResult.resolution_fingerprint -ceq [string]$payloadResult.resolution_fingerprint -or
        [string]$changedInventoryFeatureLock.build_inputs.private_particle_payload_linkage.inventory_sha256 -ceq [string]$linkedLinkage.inventory_sha256) {
        throw "Native app-build private-particle inventory identity is not bound into the resolution fingerprint"
    }

    foreach ($inventoryDamage in @(
        @{ name = "partial"; expected = "is partial; missing required field: shader"; mutate = "partial" },
        @{ name = "multiple"; expected = "declares multiple private_particle payloads; exactly zero or one is allowed"; mutate = "multiple" },
        @{ name = "ambiguous-identity"; expected = "has ambiguous payload_id and id values"; mutate = "ambiguous-identity" },
        @{ name = "linked-placeholder"; expected = "links a private_particle payload but also selects the unlinked placeholder feature"; mutate = "linked-placeholder" },
        @{ name = "contradictory-marker"; expected = "Resolved marker contract is contradictory; marker is both required and forbidden: privateParticlePayloadLinked=false"; mutate = "contradictory-marker" }
    )) {
        $damagedInventoryApp = Read-Json -Path $payloadAppPath
        switch ([string]$inventoryDamage.mutate) {
            "partial" {
                $damagedInventoryApp.payloads[0].PSObject.Properties.Remove("shader")
            }
            "multiple" {
                $damagedInventoryApp.payloads = @($damagedInventoryApp.payloads[0], $damagedInventoryApp.payloads[0])
            }
            "ambiguous-identity" {
                $damagedInventoryApp.payloads[0] | Add-Member -NotePropertyName id -NotePropertyValue "different-synthetic-payload"
            }
            "linked-placeholder" {
                $damagedInventoryApp.requested_features = @($damagedInventoryApp.requested_features) + "particles.private.placeholder_compute"
            }
            "contradictory-marker" {
                $damagedInventoryApp.expected_markers.required = @($damagedInventoryApp.expected_markers.required) + "privateParticlePayloadLinked=false"
            }
        }
        $damagedInventoryApp.app_id = "private_particle_inventory_$($inventoryDamage.name.Replace('-', '_'))"
        $damagedInventoryAppPath = Join-Path $payloadInputDir "$($inventoryDamage.name).app.json"
        $damagedInventoryApp | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $damagedInventoryAppPath -Encoding utf8
        $damagedInventoryOutput = @(& pwsh -NoProfile -ExecutionPolicy Bypass -File $resolver `
            -AppSpec $damagedInventoryAppPath `
            -OutputRoot $structuredResultRoot `
            -DryRun 2>&1 | ForEach-Object { $_.ToString() })
        $damagedInventoryExitCode = $LASTEXITCODE
        if ($damagedInventoryExitCode -eq 0) {
            throw "Native app-build accepted damaged private-particle inventory case: $($inventoryDamage.name)"
        }
        $normalizedDamagedInventoryOutput = ((($damagedInventoryOutput -join " ") -replace '\s*\|\s*', ' ') -replace '\s+', ' ').Trim()
        if (-not $normalizedDamagedInventoryOutput.Contains([string]$inventoryDamage.expected, [System.StringComparison]::Ordinal)) {
            throw "Native app-build damaged private-particle inventory case returned the wrong error: $($inventoryDamage.name) output=$normalizedDamagedInventoryOutput"
        }
    }

    foreach ($malformedCase in @(
        @{ name = "missing-value"; entry = [ordered]@{ name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_STATIC_TEST" }; expected = "build_env entries require explicit name and value fields" },
        @{ name = "wrong-namespace"; entry = [ordered]@{ name = "RUSTY_QUEST_PRIVATE_STATIC_TEST"; value = "synthetic" }; expected = "build_env name is outside the generic private-particle namespace: RUSTY_QUEST_PRIVATE_STATIC_TEST" }
    )) {
        $malformedApp = Read-Json -Path $payloadAppPath
        $malformedApp.app_id = "private_particle_build_env_$($malformedCase.name.Replace('-', '_'))"
        $malformedApp.payloads[0].build_env = @($malformedCase.entry)
        $malformedAppPath = Join-Path $payloadInputDir "$($malformedCase.name).app.json"
        $malformedApp | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $malformedAppPath -Encoding utf8
        $malformedOutput = @(& pwsh -NoProfile -ExecutionPolicy Bypass -File $resolver `
            -AppSpec $malformedAppPath `
            -OutputRoot $structuredResultRoot `
            -DryRun 2>&1 | ForEach-Object { $_.ToString() })
        $malformedExitCode = $LASTEXITCODE
        if ($malformedExitCode -eq 0) {
            throw "Native app-build accepted malformed private-particle build_env case: $($malformedCase.name)"
        }
        $normalizedMalformedOutput = ((($malformedOutput -join " ") -replace '\s*\|\s*', ' ') -replace '\s+', ' ').Trim()
        if (-not $normalizedMalformedOutput.Contains([string]$malformedCase.expected, [System.StringComparison]::Ordinal)) {
            throw "Native app-build malformed private-particle build_env case returned the wrong error: $($malformedCase.name) output=$normalizedMalformedOutput"
        }
    }
} finally {
    if (Test-Path -LiteralPath $structuredResultRoot) {
        Remove-Item -LiteralPath $structuredResultRoot -Recurse -Force
    }
}

Write-Host "Rusty Quest native app-build static validation passed"
