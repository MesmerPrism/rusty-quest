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
    foreach ($field in @("schema", "feature_id", "owner_lane", "status", "description", "provides", "depends_on", "incompatible_with", "exclusive_groups", "android_manifest", "runtime_profile", "build_inputs", "markers", "validation", "public_private_boundary")) {
        Assert-RequiredProperty -Object $Feature -Name $field -Label $Path
    }
    if ([string]$Feature.schema -ne "rusty.quest.native_app_feature.v1") {
        throw "$Path has unsupported schema: $($Feature.schema)"
    }
    if ([string]::IsNullOrWhiteSpace([string]$Feature.feature_id)) {
        throw "$Path has empty feature_id"
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
    $resolver,
    $profileGate
)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Native app-build workflow file is missing: $path"
    }
}

$featureFiles = @(Get-ChildItem -LiteralPath $featureDir -Filter "*.feature.json" -File | Sort-Object Name)
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
    "input.right_primary_private_particle_recenter",
    "camera.hwb",
    "display_composite",
    "video_projection",
    "renderer.stimulus_volume",
    "environment_depth",
    "hand_mesh_visual",
    "hand_anchor_particles",
    "sdf_visual",
    "projection_target.breathing_room",
    "manifold.bridge",
    "makepad_runtime"
)) {
    if (-not $featureIds.ContainsKey($requiredFeature)) {
        throw "Native app-build feature library is missing required seed feature: $requiredFeature"
    }
}

$publicFixtureFiles = @()
$publicFixtureFiles += Get-ChildItem -LiteralPath $featureDir -Filter "*.feature.json" -File
$publicFixtureFiles += Get-ChildItem -LiteralPath $appBuildDir -Filter "*.app.json" -File
foreach ($file in $publicFixtureFiles) {
    $text = Get-Content -Raw -LiteralPath $file.FullName
    foreach ($forbidden in @("S:/", "S:\\", "rusty-gpu-viscereality", "Rusty-Viscereality", "viscereality")) {
        if ($text -match [regex]::Escape($forbidden)) {
            throw "Public native app-build fixture contains private/local term '$forbidden': $($file.FullName)"
        }
    }
}

$damagedFeatureFiles = @(Get-ChildItem -LiteralPath (Join-Path $featureDir "damaged") -Filter "*.feature.json" -File | Sort-Object Name)
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
