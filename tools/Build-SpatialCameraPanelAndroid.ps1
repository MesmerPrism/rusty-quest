param(
    [string]$RepoRoot,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NdkHome = $env:ANDROID_NDK_HOME,
    [string]$NdkVersion = "27.2.12479018",
    [string]$BuildToolsVersion = "36.0.0",
    [string]$GradleVersion = "9.4.1",
    [ValidateSet("DevFast", "Candidate")]
    [string]$BuildMode = "DevFast",
    [ValidateSet("Static", "Dynamic")]
    [string]$RustStdLinkage = "Static",
    [switch]$AllowNonDeployableDynamicStdBenchmark,
    [string]$BuildCacheRoot = $env:RUSTY_QUEST_BUILD_CACHE_ROOT,
    [string]$RecordedHandCaptureDir = "",
    [int]$RecordedHandFrameLimit = 24,
    [string]$PrivateLayerProfilePath = "",
    [ValidateRange(-0.25, 0.25)][double]$DepthAlignmentDefaultLeftX = 0.0,
    [ValidateRange(-0.25, 0.25)][double]$DepthAlignmentDefaultLeftY = 0.0,
    [ValidateRange(-0.25, 0.25)][double]$DepthAlignmentDefaultRightX = 0.0,
    [ValidateRange(-0.25, 0.25)][double]$DepthAlignmentDefaultRightY = 0.0,
    [string]$OpaqueGuideShader = "",
    [string]$OpaqueProjectionShader = "",
    [string]$OpaqueProjectionVertexShader = "",
    [string]$OpaqueProjectionEffect = "",
    [ValidateSet(1, 2)][int]$ProjectionSurfaceUniformAbiVersion = 1,
    [string]$PrivateSurfaceParticleProfilePath = "",
    [string]$PrivateSurfaceParticleShader = "",
    [string]$PrivateSurfaceParticlePayloadDir = "",
    [string]$PrivateSurfaceParticleMarkerPrefix = "",
    [string]$HandMeshRigAssetDir = "",
    [string]$PrivateFeatureSourceDir = "",
    [string]$PrivateFeatureAssetDir = "",
    [string]$PrivateFeatureResourceDir = "",
    [string]$ProductId = "",
    [string]$AppId = "",
    [string]$AppLabel = "",
    [string]$ApkFileName = "",
    [string]$ParticleLayerCarrierDefault = "manual-panel-scene-object-custom-mesh",
    [string]$StartInParticleViewDefault = "false",
    [string]$PanelLauncherVisibleDefault = "true",
    [switch]$CameraProjectionDefaultEnabled,
    [ValidateSet("disabled", "legacy-native-sidecar", "spatial-sdk-api-layer")]
    [string]$EnvironmentDepthOwner = "legacy-native-sidecar",
    [switch]$ImmersiveVideoDefaultEnabled,
    [string]$ImmersiveVideoDefaultOfflinePackId = "",
    [string]$OfflineMediaPackAssetDir = $env:RUSTY_QUEST_OFFLINE_MEDIA_PACK_ASSET_DIR,
    [string]$OfflineMediaKeyHex = $env:RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX,
    [ValidateSet(
        "off",
        "native-buffer",
        "organic-buffer",
        "full-stretch",
        "spatial-video-underlay"
    )]
    [string]$ZoneCompositorDefaultPreset = "off",
    [string]$HandAlignmentEnabledDefault = "false",
    [string]$HandAlignmentViewerMarkersEnabledDefault = "false",
    [string]$HandAlignmentMappingProfileDefault = "mirror-x-origin-registration",
    [string]$HandBillboardFlockEnabledDefault = "false",
    [string]$HandBillboardSourceDefault = "spatial-sdk-anchor-flock",
    [ValidateSet("Debug", "Release")]
    [string]$BuildType = "Debug",
    [switch]$LockedFinalPresentation,
    [ValidateRange(0.0, 4.0)][double]$DistortionSpeedScale = 1.0,
    [string]$Keystore = "",
    [ValidatePattern('^(?:[0-9a-fA-F]{64})?$')]
    [string]$ExpectedSignerSha256 = "",
    [string]$OutDir = "",
    [switch]$AllowSharedDevelopmentPackage,
    [switch]$PublicationBuild,
    [switch]$ReplaceExistingOutput
)

$ErrorActionPreference = "Stop"
$SharedSpatialSignerSha256 = "722f1f3dcb921918d2e02f39f1b1bd8f9ff2812e07757c5fc665f6b8f7ee32a8"
$SharedSpatialAppId = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
$buildLaneMutex = $null
$buildLaneMutexOwned = $false
$buildLaneMutexAbandoned = $false
$buildLaneMutexWaitMs = 0L

trap {
    $caughtBuildError = $_
    if ($buildLaneMutexOwned -and $null -ne $buildLaneMutex) {
        try { $buildLaneMutex.ReleaseMutex() } catch { }
        $buildLaneMutexOwned = $false
    }
    if ($null -ne $buildLaneMutex) {
        try { $buildLaneMutex.Dispose() } catch { }
        $buildLaneMutex = $null
    }
    throw $caughtBuildError
}

function Set-TextFileIfChanged {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][AllowEmptyString()][string]$Value
    )
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    $bytes = $utf8NoBom.GetBytes($Value)
    $existingMatches = $false
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        $existing = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path).Path)
        $existingMatches = $existing.Length -eq $bytes.Length
        if ($existingMatches) {
            for ($index = 0; $index -lt $bytes.Length; $index++) {
                if ($existing[$index] -ne $bytes[$index]) {
                    $existingMatches = $false
                    break
                }
            }
        }
    }
    if ($existingMatches) { return $false }
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [System.IO.File]::WriteAllBytes([System.IO.Path]::GetFullPath($Path), $bytes)
    return $true
}

function Copy-FileIfChanged {
    param(
        [Parameter(Mandatory=$true)][string]$Source,
        [Parameter(Mandatory=$true)][string]$Destination
    )
    $copyRequired = -not (Test-Path -LiteralPath $Destination -PathType Leaf)
    if (-not $copyRequired) {
        $copyRequired = (Get-FileSha256 -Path $Source) -cne (Get-FileSha256 -Path $Destination)
    }
    if (-not $copyRequired) { return $false }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
    return $true
}

function Get-PathSetSha256 {
    param([Parameter(Mandatory=$true)][Collections.IDictionary]$Paths)
    $entries = foreach ($name in @($Paths.Keys | Sort-Object)) {
        $path = [string]$Paths[$name]
        if (-not (Test-Path -LiteralPath $path)) {
            throw "Build identity input not found: $name ($path)"
        }
        $item = Get-Item -LiteralPath $path
        $hash = if ($item.PSIsContainer) {
            Get-DirectorySha256 -Path $item.FullName
        } else {
            Get-FileSha256 -Path $item.FullName
        }
        "$name=$hash"
    }
    return Get-StringSha256 -Value ($entries -join "`n")
}

function Get-IdentityInvalidation {
    param(
        [object]$Previous,
        [Parameter(Mandatory=$true)][Collections.IDictionary]$Current
    )
    if ($null -eq $Previous) { return @("cold-cache") }
    $changes = [Collections.Generic.List[string]]::new()
    foreach ($name in @($Current.Keys | Sort-Object)) {
        $priorProperty = $Previous.PSObject.Properties[[string]$name]
        $priorValue = if ($null -eq $priorProperty) { $null } else { $priorProperty.Value }
        $currentValue = $Current[$name]
        $priorJson = $priorValue | ConvertTo-Json -Depth 20 -Compress
        $currentJson = $currentValue | ConvertTo-Json -Depth 20 -Compress
        if ($priorJson -cne $currentJson) { $changes.Add([string]$name) }
    }
    if ($changes.Count -eq 0) { return @("none") }
    return @($changes)
}

function Invoke-SmokeChecked {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$File,
        [string[]]$Arguments = @(),
        [int[]]$AcceptedExitCodes = @(0)
    )
    Write-Host "BUILD_PHASE preflight tool=$Name status=start"
    & $File @Arguments 2>&1 | Select-Object -First 8 | ForEach-Object { Write-Host $_ }
    $exitCode = $LASTEXITCODE
    if ($exitCode -notin $AcceptedExitCodes) {
        throw "$Name executable smoke test failed with exit code $exitCode"
    }
    Write-Host "BUILD_PHASE preflight tool=$Name status=pass exitCode=$exitCode"
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$File,
        [string[]]$Arguments = @()
    )
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Get-FileSha256 {
    param([Parameter(Mandatory=$true)][string]$Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-StringSha256 {
    param([Parameter(Mandatory=$true)][AllowEmptyString()][string]$Value)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Value)))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-DirectorySha256 {
    param([Parameter(Mandatory=$true)][string]$Path)
    $root = (Resolve-Path -LiteralPath $Path).Path.TrimEnd([char[]]@('\', '/'))
    $entries = Get-ChildItem -LiteralPath $root -Recurse -File |
        Sort-Object FullName |
        ForEach-Object {
            $relative = $_.FullName.Substring($root.Length).TrimStart([char[]]@('\', '/')).Replace("\", "/")
            "$relative=$((Get-FileSha256 -Path $_.FullName))"
        }
    $manifest = ($entries -join "`n")
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($manifest)
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-SpatialPropertyNames {
    param([Parameter(Mandatory=$true)][string[]]$Roots)
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($root in @($Roots | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $root -PathType Container)) { continue }
        foreach ($file in @(Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object {
            $_.FullName -notmatch '[\\/](build|target|\.gradle)[\\/]' -and $_.Length -le 4MB
        })) {
            try { $text = [IO.File]::ReadAllText($file.FullName) } catch { continue }
            foreach ($match in [regex]::Matches($text, 'debug\.rustyquest\.(?:spatial|spatial_camera_panel)\.[A-Za-z0-9_.-]+')) {
                [void]$names.Add([string]$match.Value)
            }
        }
    }
    return @($names | Sort-Object)
}

function Test-ZipEntry {
    param(
        [Parameter(Mandatory=$true)][string]$ZipPath,
        [Parameter(Mandatory=$true)][string]$EntryName
    )
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ZipPath).Path)
    try {
        return [bool]($zip.Entries | Where-Object { $_.FullName -eq $EntryName } | Select-Object -First 1)
    } finally {
        $zip.Dispose()
    }
}

function Test-ApkDexDescriptor {
    param(
        [Parameter(Mandatory=$true)][string]$ApkPath,
        [Parameter(Mandatory=$true)][string]$Descriptor
    )
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead(
        (Resolve-Path -LiteralPath $ApkPath).Path
    )
    try {
        foreach ($entry in @($zip.Entries | Where-Object {
            $_.FullName -match '^classes[0-9]*\.dex$'
        })) {
            $stream = $entry.Open()
            $memory = [IO.MemoryStream]::new()
            try {
                $stream.CopyTo($memory)
                $dexText = [Text.Encoding]::ASCII.GetString($memory.ToArray())
                if ($dexText.Contains($Descriptor, [StringComparison]::Ordinal)) {
                    return $true
                }
            } finally {
                $memory.Dispose()
                $stream.Dispose()
            }
        }
        return $false
    } finally {
        $zip.Dispose()
    }
}

function Invoke-DownloadFile {
    param(
        [Parameter(Mandatory=$true)][string]$Uri,
        [Parameter(Mandatory=$true)][string]$OutFile
    )
    $client = [System.Net.WebClient]::new()
    try {
        $client.DownloadFile($Uri, $OutFile)
    } finally {
        $client.Dispose()
    }
}

function Invoke-DownloadText {
    param([Parameter(Mandatory=$true)][string]$Uri)
    $client = [System.Net.WebClient]::new()
    try {
        return $client.DownloadString($Uri)
    } finally {
        $client.Dispose()
    }
}

function Resolve-OptionalFilePath {
    param(
        [string]$Path,
        [Parameter(Mandatory=$true)][string]$Label
    )
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return ""
    }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label not found: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Resolve-OptionalDirectoryPath {
    param(
        [string]$Path,
        [Parameter(Mandatory=$true)][string]$Label
    )
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return ""
    }
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$Label not found: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Test-HandMeshRigAssetPack {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return [pscustomobject]@{ ready = $false; asset_id = ""; file_count = 0 }
    }
    $root = Join-Path $Path "spatial-ecs-replay"
    $manifestPath = Join-Path $root "spatial-ecs-replay-manifest.json"
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Hand mesh rig manifest not found: $manifestPath"
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace([string]$manifest.asset_id)) {
        throw "Hand mesh rig manifest must declare asset_id: $manifestPath"
    }
    if ([string]$manifest.coordinate_anchor -ne "triangle-index-plus-barycentric") {
        throw "Hand mesh rig coordinate_anchor must be triangle-index-plus-barycentric"
    }
    if ([int]$manifest.live_skinning.openxr_joint_row_count_per_hand -ne 26) {
        throw "Hand mesh rig must declare 26 OpenXR joint rows per hand"
    }
    $prefixes = @("recorded-meta-quest-hand", "recorded-meta-quest-right-hand")
    $suffixes = @(
        "mesh-triangles.u32.bin",
        "skinning-bind-vertices.f32.bin",
        "skinning-bind-normals.f32.bin",
        "skinning-vertex-joint-indices.u32.bin",
        "skinning-vertex-joint-weights.f32.bin",
        "skinning-bind-joint-poses.f32.bin",
        "skinning-bind-joint-sources.u32.bin",
        "samples-512-coordinate-triangles.u32.bin",
        "samples-512-coordinate-barycentric.f32.bin",
        "samples-1024-coordinate-triangles.u32.bin",
        "samples-1024-coordinate-barycentric.f32.bin"
    )
    foreach ($prefix in $prefixes) {
        foreach ($suffix in $suffixes) {
            $file = Join-Path $root "$prefix-$suffix"
            if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
                throw "Hand mesh rig file missing: $file"
            }
            if (((Get-Item -LiteralPath $file).Length % 4) -ne 0) {
                throw "Hand mesh rig binary length is not divisible by four: $file"
            }
        }
    }
    return [pscustomobject]@{
        ready = $true
        asset_id = [string]$manifest.asset_id
        file_count = @(Get-ChildItem -LiteralPath $root -File).Count
    }
}

function Test-PrivateSurfaceParticleProfile {
    param([Parameter(Mandatory=$true)][string]$Path)
    $profile = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    $schemaId = [string]$profile.schema_id
    if ([string]::IsNullOrWhiteSpace($schemaId)) {
        throw "Private surface-particle profile must declare schema_id: $Path"
    }
    if ($schemaId.StartsWith("rusty.morphospace.", [System.StringComparison]::Ordinal)) {
        throw "Private surface-particle profile must not use rusty.morphospace schema ids: $schemaId"
    }
    if ($null -ne $profile.target_runtime -and [string]$profile.target_runtime -ne "rusty-quest-spatial-camera-panel-android") {
        throw "Private surface-particle profile target_runtime must be rusty-quest-spatial-camera-panel-android"
    }
    if ($null -eq $profile.runtime_parameter_packet) {
        throw "Private surface-particle profile must declare runtime_parameter_packet"
    }
    if ($profile.runtime_parameter_packet.packet_is_not_data_plane -ne $true) {
        throw "Private surface-particle runtime packet must declare packet_is_not_data_plane=true"
    }
    $allowed = @($profile.runtime_parameter_packet.allowed_packet_fields | ForEach-Object { [string]$_ })
    $forbidden = @($profile.runtime_parameter_packet.forbidden_packet_fields | ForEach-Object { [string]$_ })
    foreach ($payload in @(
            "particle_output_rows",
            "phase_state_rows",
            "neighbor_graph_rows",
            "tracer_state_rows",
            "texture_arrays",
            "per-frame-expanded-particle-lists"
        )) {
        if ($forbidden -notcontains $payload) {
            throw "Private surface-particle profile forbidden packet fields missing: $payload"
        }
        if ($allowed -contains $payload) {
            throw "Private surface-particle profile allowed packet fields must not include: $payload"
        }
    }
    if ($null -ne $profile.public_build_inputs) {
        $expected = @{
            profile_env = "RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PROFILE"
            shader_env = "RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_SHADER"
            payload_dir_env = "RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PAYLOAD_DIR"
            marker_prefix_env = "RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_MARKER_PREFIX"
        }
        foreach ($entry in $expected.GetEnumerator()) {
            $actual = $profile.public_build_inputs.PSObject.Properties[$entry.Key].Value
            if ([string]$actual -ne $entry.Value) {
                throw "Private surface-particle profile public_build_inputs.$($entry.Key) must be $($entry.Value)"
            }
        }
    }
}

function Get-PrivateSurfaceParticlePayloadInfo {
    param([string]$Path)
    $result = [ordered]@{
        files_present = $false
        positions_bytes = 0
        normals_bytes = 0
        aux0_bytes = 0
        mask_texture_bytes = 0
    }
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return [pscustomobject]$result
    }
    $expected = @(
        @{ Key = "positions_bytes"; Name = "private_particle_positions.f32.bin" },
        @{ Key = "normals_bytes"; Name = "private_particle_normals.f32.bin" },
        @{ Key = "aux0_bytes"; Name = "private_particle_aux0.u32.bin" },
        @{ Key = "mask_texture_bytes"; Name = "private_particle_mask_texture.r8.bin" }
    )
    foreach ($entry in $expected) {
        $file = Join-Path $Path $entry.Name
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
            throw "Private surface-particle payload file missing: $file"
        }
        $result[$entry.Key] = (Get-Item -LiteralPath $file).Length
    }
    $result.files_present = $true
    return [pscustomobject]$result
}

function Test-MarkerPrefix {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return ""
    }
    if ($Value -notmatch "^[A-Z0-9_]{3,80}$") {
        throw "PrivateSurfaceParticleMarkerPrefix must be an uppercase marker token, got: $Value"
    }
    return $Value
}

function Test-ProjectionEffectValue {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $false
    }
    $parts = @($Value -split '[,;\s]+' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($parts.Length -ne 4) {
        throw "OpaqueProjectionEffect must contain four floats, found $($parts.Length): $Value"
    }
    foreach ($part in $parts) {
        $parsed = 0.0
        if (-not [double]::TryParse($part, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) {
            throw "OpaqueProjectionEffect contains an invalid float: $part"
        }
    }
    return $true
}

function Resolve-DepthAlignmentDefaultValue {
    param(
        [Parameter(Mandatory=$true)][object]$Value,
        [Parameter(Mandatory=$true)][string]$Label
    )
    $parsed = 0.0
    if ($Value -is [string]) {
        if (-not [double]::TryParse(
                [string]$Value,
                [System.Globalization.NumberStyles]::Float,
                [System.Globalization.CultureInfo]::InvariantCulture,
                [ref]$parsed)) {
            throw "$Label must be numeric."
        }
    } else {
        try {
            $parsed = [Convert]::ToDouble($Value, [System.Globalization.CultureInfo]::InvariantCulture)
        } catch {
            throw "$Label must be numeric."
        }
    }
    if ([double]::IsNaN($parsed) -or [double]::IsInfinity($parsed) -or $parsed -lt -0.25 -or $parsed -gt 0.25) {
        throw "$Label must be finite and between -0.25 and 0.25."
    }
    return $parsed
}

function Resolve-SpatialProductId {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return "spatial-camera-panel"
    }
    $trimmed = $Value.Trim()
    if ($trimmed -ne "spatial-camera-panel") {
        throw "Build-SpatialCameraPanelAndroid.ps1 only builds spatial-camera-panel. Use Build-SpatialVrStrobeAndroid.ps1 for Strobe."
    }
    return $trimmed
}

function Resolve-SpatialAppId {
    param(
        [string]$Value,
        [Parameter(Mandatory=$true)][string]$ResolvedProductId
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return "io.github.mesmerprism.rustyquest.spatial_camera_panel"
    }
    $trimmed = $Value.Trim()
    if ($trimmed -notmatch "^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$") {
        throw "AppId must be a valid Android application id, got: $Value"
    }
    return $trimmed
}

function Resolve-SpatialAppLabel {
    param(
        [string]$Value,
        [Parameter(Mandatory=$true)][string]$ResolvedProductId
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return "Rusty Quest Spatial Camera Panel"
    }
    $trimmed = $Value.Trim()
    if ($trimmed.Length -gt 80) {
        throw "AppLabel must be 80 characters or shorter."
    }
    return $trimmed
}

function Resolve-ApkFileName {
    param(
        [string]$Value,
        [Parameter(Mandatory=$true)][string]$ResolvedAppId,
        [Parameter(Mandatory=$true)][string]$ResolvedProductId
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        if ($ResolvedAppId -eq "io.github.mesmerprism.rustyquest.spatial_camera_panel") {
            return "rusty-quest-spatial-camera-panel.apk"
        }
        return (($ResolvedAppId -replace '[^A-Za-z0-9_.-]+', '-') + ".apk")
    }
    $trimmed = $Value.Trim()
    if ($trimmed -notmatch "^[A-Za-z0-9_.-]+\.apk$") {
        throw "ApkFileName must be a simple .apk file name, got: $Value"
    }
    return $trimmed
}

function Resolve-Gradle {
    param(
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [Parameter(Mandatory=$true)][string]$Version
    )
    $localRoot = Join-Path $RepoRoot "local-artifacts"
    $toolsRoot = Join-Path $localRoot "tools"
    $downloadsRoot = Join-Path $localRoot "downloads"
    $gradleHome = Join-Path $toolsRoot "gradle-$Version"
    $gradleBat = Join-Path $gradleHome "bin\gradle.bat"
    if (Test-Path -LiteralPath $gradleBat) {
        return $gradleBat
    }

    New-Item -ItemType Directory -Force -Path $toolsRoot, $downloadsRoot | Out-Null
    $zipPath = Join-Path $downloadsRoot "gradle-$Version-bin.zip"
    $distributionUrl = "https://services.gradle.org/distributions/gradle-$Version-bin.zip"
    if (-not (Test-Path -LiteralPath $zipPath)) {
        Invoke-DownloadFile -Uri $distributionUrl -OutFile $zipPath
    }

    $expectedSha = (Invoke-DownloadText -Uri "$distributionUrl.sha256").Trim().Split()[0].ToLowerInvariant()
    $actualSha = Get-FileSha256 -Path $zipPath
    if ($expectedSha -ne $actualSha) {
        throw "Gradle distribution SHA-256 mismatch for $zipPath. Expected $expectedSha but found $actualSha."
    }

    Expand-Archive -LiteralPath $zipPath -DestinationPath $toolsRoot -Force
    if (-not (Test-Path -LiteralPath $gradleBat)) {
        throw "Gradle distribution did not provide expected executable: $gradleBat"
    }
    return $gradleBat
}

$resolvedPrivateSurfaceParticleProfilePath = Resolve-OptionalFilePath -Path $PrivateSurfaceParticleProfilePath -Label "Private surface-particle profile"
$resolvedPrivateSurfaceParticleShader = Resolve-OptionalFilePath -Path $PrivateSurfaceParticleShader -Label "Private surface-particle shader"
$resolvedPrivateSurfaceParticlePayloadDir = Resolve-OptionalDirectoryPath -Path $PrivateSurfaceParticlePayloadDir -Label "Private surface-particle payload directory"
$resolvedPrivateSurfaceParticleMarkerPrefix = Test-MarkerPrefix -Value $PrivateSurfaceParticleMarkerPrefix
$resolvedHandMeshRigAssetDir = Resolve-OptionalDirectoryPath -Path $HandMeshRigAssetDir -Label "Hand mesh rig asset directory"
$resolvedPrivateFeatureSourceDir = Resolve-OptionalDirectoryPath -Path $PrivateFeatureSourceDir -Label "Private feature source directory"
$resolvedPrivateFeatureAssetDir = Resolve-OptionalDirectoryPath -Path $PrivateFeatureAssetDir -Label "Private feature asset directory"
$resolvedPrivateFeatureResourceDir = Resolve-OptionalDirectoryPath -Path $PrivateFeatureResourceDir -Label "Private feature resource directory"
$handMeshRigAssetInfo = Test-HandMeshRigAssetPack -Path $resolvedHandMeshRigAssetDir
if ([string]::IsNullOrWhiteSpace($AppId) -and -not $AllowSharedDevelopmentPackage) {
    throw "-AppId is required so each Spatial project has a distinct Android identity. Use -AllowSharedDevelopmentPackage only for explicit compatibility with the shared development package."
}
$resolvedProductId = Resolve-SpatialProductId -Value $ProductId
$resolvedAppId = Resolve-SpatialAppId -Value $AppId -ResolvedProductId $resolvedProductId
$resolvedAppLabel = Resolve-SpatialAppLabel -Value $AppLabel -ResolvedProductId $resolvedProductId
$resolvedApkFileName = Resolve-ApkFileName -Value $ApkFileName -ResolvedAppId $resolvedAppId -ResolvedProductId $resolvedProductId
$resolvedImmersiveVideoDefaultOfflinePackId =
    $ImmersiveVideoDefaultOfflinePackId.Trim().ToLowerInvariant()
$resolvedOfflineMediaPackAssetDir =
    Resolve-OptionalDirectoryPath -Path $OfflineMediaPackAssetDir -Label "Offline media pack asset root"
$resolvedOfflineMediaKeyHex = $OfflineMediaKeyHex.Trim().ToLowerInvariant()
if (-not [string]::IsNullOrWhiteSpace($resolvedOfflineMediaKeyHex) -and
    $resolvedOfflineMediaKeyHex -notmatch '^[a-f0-9]{64}$') {
    throw "OfflineMediaKeyHex must be empty or exactly 64 hexadecimal characters."
}
if (-not [string]::IsNullOrWhiteSpace($resolvedImmersiveVideoDefaultOfflinePackId) -and
    $resolvedImmersiveVideoDefaultOfflinePackId -notmatch '^[a-z0-9][a-z0-9._-]{1,63}$') {
    throw "ImmersiveVideoDefaultOfflinePackId must be empty or a valid offline media pack id."
}
if ([bool]$ImmersiveVideoDefaultEnabled -and
    [string]::IsNullOrWhiteSpace($resolvedImmersiveVideoDefaultOfflinePackId)) {
    throw "ImmersiveVideoDefaultOfflinePackId is required when ImmersiveVideoDefaultEnabled is selected."
}
if ([bool]$ImmersiveVideoDefaultEnabled) {
    if ([string]::IsNullOrWhiteSpace($resolvedOfflineMediaKeyHex)) {
        throw "An offline media key is required when ImmersiveVideoDefaultEnabled is selected."
    }
    if (-not [string]::IsNullOrWhiteSpace($resolvedOfflineMediaPackAssetDir)) {
        $defaultPackManifest = Join-Path $resolvedOfflineMediaPackAssetDir "offline-media-packs\$resolvedImmersiveVideoDefaultOfflinePackId\manifest.json"
        if (-not (Test-Path -LiteralPath $defaultPackManifest -PathType Leaf)) {
            throw "Default immersive video pack manifest not found: $defaultPackManifest"
        }
        $defaultPack = Get-Content -LiteralPath $defaultPackManifest -Raw | ConvertFrom-Json
        if ([string]$defaultPack.pack_id -ne $resolvedImmersiveVideoDefaultOfflinePackId) {
            throw "Default immersive video pack manifest id does not match ImmersiveVideoDefaultOfflinePackId."
        }
    }
}
$buildTypeLower = $BuildType.ToLowerInvariant()
if ($resolvedAppId -eq "io.github.mesmerprism.rustyquest.spatial_vr_strobe") {
    throw "The Camera build cannot use the standalone Spatial VR Strobe AppId."
}
$lockedFinalPresentationEnabled = [bool]$LockedFinalPresentation
$resolvedDistortionSpeedScale = [double]$DistortionSpeedScale
$privateSurfaceParticleInputsConfigured =
    (-not [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleProfilePath)) -or
    (-not [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleShader)) -or
    (-not [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticlePayloadDir)) -or
    (-not [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleMarkerPrefix))
if ($privateSurfaceParticleInputsConfigured -and [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleProfilePath)) {
    throw "Private surface-particle hook inputs require -PrivateSurfaceParticleProfilePath."
}
if (-not [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleProfilePath)) {
    Test-PrivateSurfaceParticleProfile -Path $resolvedPrivateSurfaceParticleProfilePath
}
$privateSurfaceParticlePayloadInfo = Get-PrivateSurfaceParticlePayloadInfo -Path $resolvedPrivateSurfaceParticlePayloadDir
$privateSurfaceParticleExecutableInputsConfigured =
    (-not [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleProfilePath)) -and
    (-not [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleShader)) -and
    (-not [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticlePayloadDir)) -and
    (-not [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleMarkerPrefix))
$privateSurfaceParticleStagedPayloadReady =
    $privateSurfaceParticleExecutableInputsConfigured -and
    $privateSurfaceParticlePayloadInfo.files_present

$provisionalRepoRoot = if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
} else {
    [System.IO.Path]::GetFullPath($RepoRoot)
}
$workspaceDrive = [System.IO.Path]::GetPathRoot($provisionalRepoRoot).TrimEnd([char[]]@('\', '/'))
$machineAndroidHome = Join-Path $workspaceDrive "Work\tools\Android\windows-sdk"
$machineJavaHome = Join-Path $workspaceDrive "Work\tools\Java\temurin-17"
if ([string]::IsNullOrWhiteSpace($AndroidHome)) { $AndroidHome = $machineAndroidHome }
if ([string]::IsNullOrWhiteSpace($JavaHome)) { $JavaHome = $machineJavaHome }
if ([string]::IsNullOrWhiteSpace($NdkHome)) {
    $NdkHome = Join-Path $AndroidHome "ndk\$NdkVersion"
}
foreach ($toolRoot in @(
    @{ Label = "Android SDK"; Path = $AndroidHome },
    @{ Label = "Android NDK $NdkVersion"; Path = $NdkHome },
    @{ Label = "JDK 17"; Path = $JavaHome }
)) {
    if (-not (Test-Path -LiteralPath ([string]$toolRoot.Path) -PathType Container)) {
        throw "$($toolRoot.Label) directory not found: $($toolRoot.Path)"
    }
}
$AndroidHome = (Resolve-Path -LiteralPath $AndroidHome).Path
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$NdkHome = (Resolve-Path -LiteralPath $NdkHome).Path
if ((Split-Path -Leaf $NdkHome) -cne $NdkVersion) {
    throw "Spatial Camera Panel requires pinned Android NDK $NdkVersion but resolved $NdkHome"
}
if ([string]::IsNullOrWhiteSpace($BuildCacheRoot)) {
    $BuildCacheRoot = Join-Path $workspaceDrive "b\mv"
}
$BuildCacheRoot = [System.IO.Path]::GetFullPath($BuildCacheRoot).TrimEnd([char[]]@('\', '/'))
if ($BuildCacheRoot.Length -gt 64) {
    throw "BuildCacheRoot must remain deliberately short (64 characters or fewer): $BuildCacheRoot"
}
New-Item -ItemType Directory -Force -Path $BuildCacheRoot | Out-Null
$buildLaneMutexHash = Get-StringSha256 -Value $BuildCacheRoot.ToLowerInvariant()
$buildLaneMutexName = "Local\RustyQuestSpatialBuild-$($buildLaneMutexHash.Substring(0, 16))"
$buildLaneMutex = [System.Threading.Mutex]::new($false, $buildLaneMutexName)
$buildLaneMutexStopwatch = [Diagnostics.Stopwatch]::StartNew()
try {
    $buildLaneMutexOwned = $buildLaneMutex.WaitOne([TimeSpan]::FromMinutes(30))
} catch [System.Threading.AbandonedMutexException] {
    $buildLaneMutexOwned = $true
    $buildLaneMutexAbandoned = $true
}
$buildLaneMutexStopwatch.Stop()
$buildLaneMutexWaitMs = $buildLaneMutexStopwatch.ElapsedMilliseconds
if (-not $buildLaneMutexOwned) {
    throw "Timed out waiting for the serialized stable Spatial Camera Panel build cache lane."
}
Write-Host ("BUILD_CACHE serialized=true waitMs={0} abandoned={1}" -f $buildLaneMutexWaitMs, $buildLaneMutexAbandoned)
$resolvedRecordedHandCaptureDir = ""
if (-not [string]::IsNullOrWhiteSpace($RecordedHandCaptureDir)) {
    if (-not (Test-Path -LiteralPath $RecordedHandCaptureDir -PathType Container)) {
        throw "Recorded hand capture directory not found: $RecordedHandCaptureDir"
    }
    $resolvedRecordedHandCaptureDir = (Resolve-Path -LiteralPath $RecordedHandCaptureDir).Path
}
$resolvedRecordedHandFrameLimit = [Math]::Max(1, [Math]::Min(120, $RecordedHandFrameLimit))
$resolvedPrivateLayerProfilePath = Resolve-OptionalFilePath -Path $PrivateLayerProfilePath -Label "Private layer profile"
$depthAlignmentDefaultLeftXExplicit = $PSBoundParameters.ContainsKey("DepthAlignmentDefaultLeftX")
$depthAlignmentDefaultLeftYExplicit = $PSBoundParameters.ContainsKey("DepthAlignmentDefaultLeftY")
$depthAlignmentDefaultRightXExplicit = $PSBoundParameters.ContainsKey("DepthAlignmentDefaultRightX")
$depthAlignmentDefaultRightYExplicit = $PSBoundParameters.ContainsKey("DepthAlignmentDefaultRightY")
$resolvedDepthAlignmentDefaultLeftX = Resolve-DepthAlignmentDefaultValue -Value $DepthAlignmentDefaultLeftX -Label "DepthAlignmentDefaultLeftX"
$resolvedDepthAlignmentDefaultLeftY = Resolve-DepthAlignmentDefaultValue -Value $DepthAlignmentDefaultLeftY -Label "DepthAlignmentDefaultLeftY"
$resolvedDepthAlignmentDefaultRightX = Resolve-DepthAlignmentDefaultValue -Value $DepthAlignmentDefaultRightX -Label "DepthAlignmentDefaultRightX"
$resolvedDepthAlignmentDefaultRightY = Resolve-DepthAlignmentDefaultValue -Value $DepthAlignmentDefaultRightY -Label "DepthAlignmentDefaultRightY"
$projectionSurfaceUniformAbiVersionExplicit =
    $PSBoundParameters.ContainsKey("ProjectionSurfaceUniformAbiVersion")
$resolvedProjectionSurfaceUniformAbiVersion = $ProjectionSurfaceUniformAbiVersion
if (-not [string]::IsNullOrWhiteSpace($resolvedPrivateLayerProfilePath)) {
    $privateLayerProfile = Get-Content -LiteralPath $resolvedPrivateLayerProfilePath -Raw | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace($OpaqueGuideShader) -and $null -ne $privateLayerProfile.private_shader_sources) {
        $OpaqueGuideShader = [string]$privateLayerProfile.private_shader_sources.guide_shader
    }
    if ([string]::IsNullOrWhiteSpace($OpaqueProjectionShader) -and $null -ne $privateLayerProfile.private_shader_sources) {
        $OpaqueProjectionShader = [string]$privateLayerProfile.private_shader_sources.projection_shader
    }
    if ([string]::IsNullOrWhiteSpace($OpaqueProjectionVertexShader) -and $null -ne $privateLayerProfile.private_shader_sources) {
        $OpaqueProjectionVertexShader = [string]$privateLayerProfile.private_shader_sources.projection_vertex_shader
    }
    if ([string]::IsNullOrWhiteSpace($OpaqueProjectionEffect) -and $null -ne $privateLayerProfile.required_public_bridge) {
        $OpaqueProjectionEffect = [string]$privateLayerProfile.required_public_bridge.opaque_projection_effect
    }
    if (-not $projectionSurfaceUniformAbiVersionExplicit -and
        $null -ne $privateLayerProfile.required_public_bridge -and
        $null -ne $privateLayerProfile.required_public_bridge.projection_surface_uniform_abi_version) {
        $resolvedProjectionSurfaceUniformAbiVersion =
            [int]$privateLayerProfile.required_public_bridge.projection_surface_uniform_abi_version
    }
    if ($null -ne $privateLayerProfile.depth_alignment_defaults) {
        $depthDefaults = $privateLayerProfile.depth_alignment_defaults
        foreach ($property in @("left_x", "left_y", "right_x", "right_y")) {
            if ($null -eq $depthDefaults.PSObject.Properties[$property]) {
                throw "Private layer profile depth_alignment_defaults is missing $property."
            }
        }
        if (-not $depthAlignmentDefaultLeftXExplicit) {
            $resolvedDepthAlignmentDefaultLeftX = Resolve-DepthAlignmentDefaultValue -Value $depthDefaults.left_x -Label "Private layer profile depth_alignment_defaults.left_x"
        }
        if (-not $depthAlignmentDefaultLeftYExplicit) {
            $resolvedDepthAlignmentDefaultLeftY = Resolve-DepthAlignmentDefaultValue -Value $depthDefaults.left_y -Label "Private layer profile depth_alignment_defaults.left_y"
        }
        if (-not $depthAlignmentDefaultRightXExplicit) {
            $resolvedDepthAlignmentDefaultRightX = Resolve-DepthAlignmentDefaultValue -Value $depthDefaults.right_x -Label "Private layer profile depth_alignment_defaults.right_x"
        }
        if (-not $depthAlignmentDefaultRightYExplicit) {
            $resolvedDepthAlignmentDefaultRightY = Resolve-DepthAlignmentDefaultValue -Value $depthDefaults.right_y -Label "Private layer profile depth_alignment_defaults.right_y"
        }
    }
}
if ($resolvedProjectionSurfaceUniformAbiVersion -notin @(1, 2)) {
    throw "Projection surface uniform ABI version must be 1 or 2."
}
$resolvedOpaqueGuideShader = Resolve-OptionalFilePath -Path $OpaqueGuideShader -Label "Opaque guide shader"
$resolvedOpaqueProjectionShader = Resolve-OptionalFilePath -Path $OpaqueProjectionShader -Label "Opaque projection shader"
$resolvedOpaqueProjectionVertexShader = Resolve-OptionalFilePath -Path $OpaqueProjectionVertexShader -Label "Opaque projection vertex shader"
$privateLayerShaderInputsConfigured =
    (-not [string]::IsNullOrWhiteSpace($resolvedOpaqueGuideShader)) -or
    (-not [string]::IsNullOrWhiteSpace($resolvedOpaqueProjectionShader))
if ($privateLayerShaderInputsConfigured -and (
        [string]::IsNullOrWhiteSpace($resolvedOpaqueGuideShader) -or
        [string]::IsNullOrWhiteSpace($resolvedOpaqueProjectionShader))) {
    throw "Both -OpaqueGuideShader and -OpaqueProjectionShader are required when enabling the private layer shader path."
}
$opaqueProjectionEffectConfigured = Test-ProjectionEffectValue -Value $OpaqueProjectionEffect
if ($privateLayerShaderInputsConfigured -and -not $opaqueProjectionEffectConfigured) {
    $OpaqueProjectionEffect = "1.0,1.0,0.0,1.0"
    $opaqueProjectionEffectConfigured = $true
}
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Join-Path $PSScriptRoot ".."
}
$repoRoot = Resolve-Path $RepoRoot
$appRoot = Resolve-Path (Join-Path $repoRoot "apps\spatial-camera-panel-android")
$targetRoot = Join-Path $repoRoot "target"
$buildStartedUtc = [DateTimeOffset]::UtcNow
$phaseReceipts = [Collections.Generic.List[object]]::new()
$preflightStopwatch = [Diagnostics.Stopwatch]::StartNew()
$buildTools = Join-Path $AndroidHome "build-tools\$BuildToolsVersion"
if (-not (Test-Path -LiteralPath $buildTools -PathType Container)) {
    throw "Pinned Android build-tools $BuildToolsVersion are not installed."
}
$buildToolsSourceProperties = Join-Path $buildTools "source.properties"
if (-not (Test-Path -LiteralPath $buildToolsSourceProperties -PathType Leaf)) {
    throw "Pinned Android build-tools source.properties is missing."
}
$buildToolsPropertiesText = Get-Content -LiteralPath $buildToolsSourceProperties -Raw
if ($buildToolsPropertiesText -notmatch ('(?m)^Pkg\.Revision\s*=\s*' + [regex]::Escape($BuildToolsVersion) + '\s*$')) {
    throw "Android build-tools source.properties revision differs from $BuildToolsVersion."
}
$sourceAapt2 = Join-Path $buildTools "aapt2.exe"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$keytool = Join-Path $JavaHome "bin\keytool.exe"
$java = Join-Path $JavaHome "bin\java.exe"
$nativeReceiptLinker = Join-Path $NdkHome "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang.cmd"
$llvmReadelf = Join-Path $NdkHome "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
foreach ($tool in @($sourceAapt2, $zipalign, $apksigner, $keytool, $java, $nativeReceiptLinker, $llvmReadelf)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Pinned Android build tool not found: $tool"
    }
}
$shortToolRoot = Join-Path $BuildCacheRoot "t"
$shortAapt2 = Join-Path $shortToolRoot "aapt2.exe"
$aapt2Updated = Copy-FileIfChanged -Source $sourceAapt2 -Destination $shortAapt2
$gradleTimingInitPath = Join-Path $shortToolRoot "task-timing.init.gradle"
$gradleTimingInit = @'
def rustyQuestTaskStarts = new java.util.concurrent.ConcurrentHashMap<String, Long>()
gradle.taskGraph.beforeTask { task ->
    rustyQuestTaskStarts.put(task.path, System.nanoTime())
}
gradle.taskGraph.afterTask { task, state ->
    def started = rustyQuestTaskStarts.remove(task.path)
    def durationMs = started == null ? 0L : java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    def outcome = state.failure != null ? "FAILED" :
        (state.noSource ? "NO_SOURCE" :
        (state.upToDate ? "UP_TO_DATE" :
        (state.skipped ? "SKIPPED" : "EXECUTED")))
    println("BUILD_TASK path=${task.path} durationMs=${durationMs} outcome=${outcome}")
}
'@
$gradleTimingInitUpdated = Set-TextFileIfChanged -Path $gradleTimingInitPath -Value $gradleTimingInit
$representativeAaptPath = Join-Path $BuildCacheRoot "g\p\app\intermediates\incremental\debug\mergeDebugResources\merged.dir\values\values.xml"
if ($representativeAaptPath.Length -gt 220) {
    throw "Stable Android cache path exceeds the safe AAPT2 path budget: $($representativeAaptPath.Length) > 220"
}
Invoke-SmokeChecked -Name "aapt2" -File $shortAapt2 -Arguments @("version")
Invoke-SmokeChecked -Name "android-clang" -File $nativeReceiptLinker -Arguments @("--version")
Invoke-SmokeChecked -Name "java" -File $java -Arguments @("-version")
Invoke-SmokeChecked -Name "zipalign" -File $zipalign -Arguments @() -AcceptedExitCodes @(0, 1)
Invoke-SmokeChecked -Name "apksigner" -File $apksigner -Arguments @("version")
$javaVersionOutput = @(& $java "-version" 2>&1)
if ($LASTEXITCODE -ne 0) { throw "Resolved Java executable version readback failed." }
$javaVersionText = $javaVersionOutput -join "`n"
if ($javaVersionText -notmatch '(?m)^(?:openjdk|java) version "17\.' -or
    $javaVersionText -notmatch '(?i)Temurin') {
    throw "Resolved Java executable is not the pinned Temurin JDK 17 contract."
}
$keytoolVersionOutput = @(& $keytool "-J-version" 2>&1)
if ($LASTEXITCODE -ne 0) { throw "Resolved keytool executable version readback failed." }
$keytoolVersionText = $keytoolVersionOutput -join "`n"
if ($keytoolVersionText -notmatch '(?m)^(?:openjdk|java) version "17\.' -or
    $keytoolVersionText -notmatch '(?i)Temurin') {
    throw "Resolved keytool is not backed by the pinned Temurin JDK 17 contract."
}
$selectedAapt2Sha256 = Get-FileSha256 -Path $sourceAapt2
$preflightStopwatch.Stop()
$phaseReceipts.Add([ordered]@{
    phase = "preflight"
    duration_ms = $preflightStopwatch.ElapsedMilliseconds
    aapt2_short_copy = $(if ($aapt2Updated) { "updated" } else { "unchanged" })
    gradle_timing_init = $(if ($gradleTimingInitUpdated) { "updated" } else { "unchanged" })
    serialized_cache_lane = $true
    serialized_cache_lane_wait_ms = $buildLaneMutexWaitMs
    serialized_cache_lane_abandoned_previous_owner = $buildLaneMutexAbandoned
    android_sdk_build_tools = $BuildToolsVersion
    android_sdk_build_tools_source_properties_sha256 = Get-FileSha256 -Path $buildToolsSourceProperties
    selected_aapt2_sha256 = $selectedAapt2Sha256
    android_ndk = $NdkVersion
    java_major = 17
    path_budget_max = 220
    representative_path_length = $representativeAaptPath.Length
    status = "pass"
})
if ($RustStdLinkage -eq "Dynamic" -and -not [bool]$AllowNonDeployableDynamicStdBenchmark) {
    throw "Dynamic Rust std is restricted to an explicitly labeled non-deployable benchmark."
}
if ($BuildMode -eq "Candidate" -and $RustStdLinkage -ne "Static") {
    throw "Candidate APKs require static Rust std linkage."
}
Import-Module (Join-Path $PSScriptRoot "lib\SourceComposition.psm1") -Force
$sourceComposition = Get-QuestBuildSourceComposition `
    -RepoRoot ([string]$repoRoot) `
    -PackageName @("spatial-camera-panel-native-receipt") `
    -AllowWorkingTreeChanges:(-not [bool]$PublicationBuild)
$primarySource = @($sourceComposition.repositories | Where-Object { $_.role -eq "primary" })
if ($primarySource.Count -ne 1) { throw "Spatial APK source composition did not resolve exactly one primary Rusty Quest repository." }
$sourceHead = [string]$primarySource[0].commit
$sourceTree = [string]$primarySource[0].tree
$sourceTrackedWorktreeClean = [bool]$primarySource[0].tracked_worktree_clean
$sourceWorktreeOverlaySha256 = [string]$primarySource[0].worktree_overlay_sha256
if (($BuildMode -eq "Candidate" -or [bool]$PublicationBuild) -and -not $sourceTrackedWorktreeClean) {
    throw "Candidate builds require a frozen clean tracked source composition."
}
$sourceDependencies = @($sourceComposition.repositories | Where-Object { $_.role -eq "path-dependency" })
$sourceDependencyIdentities = @($sourceDependencies | ForEach-Object {
    [ordered]@{
        repository_id = [string]$_.repository_id
        role = [string]$_.role
        commit = [string]$_.commit
        tree = [string]$_.tree
        tracked_worktree_clean = [bool]$_.tracked_worktree_clean
        worktree_overlay_sha256 = [string]$_.worktree_overlay_sha256
    }
})
$assetConformanceLockRelativePath = "legacy-workspaces/mixed-integration-v1/conformance-locks/spatial-asset-model.feature.lock.json"
$assetConformanceLockPath = Join-Path $appRoot $assetConformanceLockRelativePath
if (-not (Test-Path -LiteralPath $assetConformanceLockPath -PathType Leaf)) {
    throw "Spatial asset conformance lock not found: $assetConformanceLockPath"
}
$assetConformanceLock = Get-Content -Raw -LiteralPath $assetConformanceLockPath | ConvertFrom-Json
$assetConformanceFeature = @($assetConformanceLock.features | Where-Object { [string]$_.feature_id -eq "spatial-asset-model" })
if ([string]$assetConformanceLock.schema -ne "rusty.morphospace.workflow.feature_lock.v1" -or
    [string]$assetConformanceLock.project_id -ne "spatial-camera-panel" -or
    [long]$assetConformanceLock.revision -lt 1 -or
    $assetConformanceFeature.Count -ne 1 -or
    $assetConformanceFeature[0].enabled -ne $true -or
    [string]$assetConformanceFeature[0].module_id -ne "spatial-asset-model" -or
    [string]$assetConformanceFeature[0].requested_by -ne "conformance-profile:spatial-asset-model" -or
    [string]$assetConformanceFeature[0].activation_receipt.schema -ne "rusty.quest.spatial_asset_model.activation_receipt.v1" -or
    [string]$assetConformanceFeature[0].activation_receipt.effective_marker -ne "rusty.quest.spatial_asset_model.effective") {
    throw "Spatial asset conformance lock does not select the accepted spatial-asset-model contract: $assetConformanceLockPath"
}
$assetConformanceLockSha256 = Get-FileSha256 -Path $assetConformanceLockPath
$environmentKeystore = $env:RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE
$keystoreWasExplicit = -not [string]::IsNullOrWhiteSpace($Keystore)
if (-not $keystoreWasExplicit -and -not [string]::IsNullOrWhiteSpace($environmentKeystore)) {
    $Keystore = $environmentKeystore
    $keystoreWasExplicit = $true
}
$sharedPackageBuild = $resolvedAppId -ceq $SharedSpatialAppId
$signerRequired = $sharedPackageBuild -or $BuildMode -eq "Candidate" -or [bool]$PublicationBuild
if ($signerRequired -and -not $keystoreWasExplicit) {
    throw "Shared-package and candidate builds require an explicit local signer binding before compilation."
}
$signingAlias = $env:RUSTY_QUEST_SPATIAL_SIGNING_KEY_ALIAS
$signingStorePassword = $env:RUSTY_QUEST_SPATIAL_SIGNING_STORE_PASSWORD
$signingKeyPassword = $env:RUSTY_QUEST_SPATIAL_SIGNING_KEY_PASSWORD
$certificateSha256 = ""
if ($keystoreWasExplicit) {
    if (-not (Test-Path -LiteralPath $Keystore -PathType Leaf)) {
        throw "Explicit signing keystore not found."
    }
    $Keystore = (Resolve-Path -LiteralPath $Keystore).Path
    if ([string]::IsNullOrWhiteSpace($signingAlias) -or
        [string]::IsNullOrWhiteSpace($signingStorePassword) -or
        [string]::IsNullOrWhiteSpace($signingKeyPassword)) {
        throw "Explicit signer alias and passwords require local environment bindings."
    }
    $signerProbeRoot = Join-Path $BuildCacheRoot "s"
    New-Item -ItemType Directory -Force -Path $signerProbeRoot | Out-Null
    $certificatePath = Join-Path $signerProbeRoot "selected-signer.der"
    Invoke-Checked "selected signer certificate export" $keytool @(
        "-exportcert",
        "-keystore", $Keystore,
        "-storepass", $signingStorePassword,
        "-alias", $signingAlias,
        "-file", $certificatePath
    )
    $certificateSha256 = Get-FileSha256 -Path $certificatePath
}
$normalizedExpectedSignerSha256 = $ExpectedSignerSha256.Trim().ToLowerInvariant()
if ($sharedPackageBuild) {
    if (-not [string]::IsNullOrWhiteSpace($normalizedExpectedSignerSha256) -and
        $normalizedExpectedSignerSha256 -cne $SharedSpatialSignerSha256) {
        throw "Shared Spatial Camera Panel package expected signer differs from the pinned public fingerprint."
    }
    $normalizedExpectedSignerSha256 = $SharedSpatialSignerSha256
}
if ($signerRequired -and [string]::IsNullOrWhiteSpace($normalizedExpectedSignerSha256)) {
    throw "Candidate builds require an explicit expected signer fingerprint before compilation."
}
if (-not [string]::IsNullOrWhiteSpace($normalizedExpectedSignerSha256) -and
    $certificateSha256 -cne $normalizedExpectedSignerSha256) {
    throw "Explicit Spatial Camera Panel signer fingerprint mismatch before compilation."
}
$buildInputDescriptor = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel.build_input_lock.v1"
    source_commit = $sourceHead
    source_tree = $sourceTree
    source_tracked_worktree_clean = $sourceTrackedWorktreeClean
    source_worktree_overlay_sha256 = $sourceWorktreeOverlaySha256
    source_composition_fingerprint = [string]$sourceComposition.fingerprint
    build_mode = $(if ([bool]$PublicationBuild) { "publication" } else { "iteration" })
    build_workflow_mode = $BuildMode.ToLowerInvariant()
    source_dependencies = $sourceDependencyIdentities
    product_id = $resolvedProductId
    application_id = $resolvedAppId
    app_label = $resolvedAppLabel
    apk_file_name = $resolvedApkFileName
    gradle_version = $GradleVersion
    android_build_type = $buildTypeLower
    toolchain = [ordered]@{
        android_sdk_build_tools = Split-Path -Leaf $buildTools
        android_ndk = $NdkVersion
        java_major = 17
        gradle = $GradleVersion
        short_aapt2_sha256 = Get-FileSha256 -Path $shortAapt2
    }
    signer = [ordered]@{
        explicit_local_binding = $keystoreWasExplicit
        certificate_sha256 = $certificateSha256
        expected_certificate_sha256 = $normalizedExpectedSignerSha256
        local_path_recorded = $false
        alias_or_password_recorded = $false
    }
    locked_final_presentation = $lockedFinalPresentationEnabled
    camera_projection_default_enabled = [bool]$CameraProjectionDefaultEnabled
    environment_depth_owner = $EnvironmentDepthOwner
    immersive_video_default_enabled = [bool]$ImmersiveVideoDefaultEnabled
    immersive_video_default_offline_pack_id = $resolvedImmersiveVideoDefaultOfflinePackId
    zone_compositor_default_preset = $ZoneCompositorDefaultPreset
    distortion_speed_scale = $resolvedDistortionSpeedScale
    recorded_hand_capture = if ([string]::IsNullOrWhiteSpace($resolvedRecordedHandCaptureDir)) { $null } else { [ordered]@{ path = $resolvedRecordedHandCaptureDir; sha256 = Get-DirectorySha256 -Path $resolvedRecordedHandCaptureDir; frame_limit = $resolvedRecordedHandFrameLimit } }
    private_layer = [ordered]@{
        profile = if ([string]::IsNullOrWhiteSpace($resolvedPrivateLayerProfilePath)) { $null } else { [ordered]@{ path = $resolvedPrivateLayerProfilePath; sha256 = Get-FileSha256 -Path $resolvedPrivateLayerProfilePath } }
        guide_shader = if ([string]::IsNullOrWhiteSpace($resolvedOpaqueGuideShader)) { $null } else { [ordered]@{ path = $resolvedOpaqueGuideShader; sha256 = Get-FileSha256 -Path $resolvedOpaqueGuideShader } }
        projection_shader = if ([string]::IsNullOrWhiteSpace($resolvedOpaqueProjectionShader)) { $null } else { [ordered]@{ path = $resolvedOpaqueProjectionShader; sha256 = Get-FileSha256 -Path $resolvedOpaqueProjectionShader } }
        projection_vertex_shader = if ([string]::IsNullOrWhiteSpace($resolvedOpaqueProjectionVertexShader)) { $null } else { [ordered]@{ path = $resolvedOpaqueProjectionVertexShader; sha256 = Get-FileSha256 -Path $resolvedOpaqueProjectionVertexShader } }
        projection_effect = if ($opaqueProjectionEffectConfigured) { $OpaqueProjectionEffect } else { "" }
    }
    private_particles = [ordered]@{
        profile = if ([string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleProfilePath)) { $null } else { [ordered]@{ path = $resolvedPrivateSurfaceParticleProfilePath; sha256 = Get-FileSha256 -Path $resolvedPrivateSurfaceParticleProfilePath } }
        shader = if ([string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleShader)) { $null } else { [ordered]@{ path = $resolvedPrivateSurfaceParticleShader; sha256 = Get-FileSha256 -Path $resolvedPrivateSurfaceParticleShader } }
        payload = if ([string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticlePayloadDir)) { $null } else { [ordered]@{ path = $resolvedPrivateSurfaceParticlePayloadDir; sha256 = Get-DirectorySha256 -Path $resolvedPrivateSurfaceParticlePayloadDir } }
        marker_prefix = $resolvedPrivateSurfaceParticleMarkerPrefix
    }
    packaged_inputs = [ordered]@{
        offline_media_packs = if ([string]::IsNullOrWhiteSpace($resolvedOfflineMediaPackAssetDir)) { $null } else { [ordered]@{ path = $resolvedOfflineMediaPackAssetDir; sha256 = Get-DirectorySha256 -Path $resolvedOfflineMediaPackAssetDir } }
        offline_media_key_configured = (-not [string]::IsNullOrWhiteSpace($resolvedOfflineMediaKeyHex))
        offline_media_key_sha256 = if ([string]::IsNullOrWhiteSpace($resolvedOfflineMediaKeyHex)) { "" } else { Get-StringSha256 -Value $resolvedOfflineMediaKeyHex }
        hand_mesh_rig = if ([string]::IsNullOrWhiteSpace($resolvedHandMeshRigAssetDir)) { $null } else { [ordered]@{ path = $resolvedHandMeshRigAssetDir; sha256 = Get-DirectorySha256 -Path $resolvedHandMeshRigAssetDir } }
        private_source = if ([string]::IsNullOrWhiteSpace($resolvedPrivateFeatureSourceDir)) { $null } else { [ordered]@{ path = $resolvedPrivateFeatureSourceDir; sha256 = Get-DirectorySha256 -Path $resolvedPrivateFeatureSourceDir } }
        private_assets = if ([string]::IsNullOrWhiteSpace($resolvedPrivateFeatureAssetDir)) { $null } else { [ordered]@{ path = $resolvedPrivateFeatureAssetDir; sha256 = Get-DirectorySha256 -Path $resolvedPrivateFeatureAssetDir } }
        private_resources = if ([string]::IsNullOrWhiteSpace($resolvedPrivateFeatureResourceDir)) { $null } else { [ordered]@{ path = $resolvedPrivateFeatureResourceDir; sha256 = Get-DirectorySha256 -Path $resolvedPrivateFeatureResourceDir } }
    }
    defaults = [ordered]@{
        particle_layer_carrier = $ParticleLayerCarrierDefault; start_in_particle_view = $StartInParticleViewDefault
        panel_launcher_visible = $PanelLauncherVisibleDefault; hand_alignment_enabled = $HandAlignmentEnabledDefault
        hand_alignment_viewer_markers = $HandAlignmentViewerMarkersEnabledDefault; hand_alignment_mapping_profile = $HandAlignmentMappingProfileDefault
        hand_billboard_flock_enabled = $HandBillboardFlockEnabledDefault; hand_billboard_source = $HandBillboardSourceDefault
        depth_alignment = [ordered]@{
            left_x = $resolvedDepthAlignmentDefaultLeftX
            left_y = $resolvedDepthAlignmentDefaultLeftY
            right_x = $resolvedDepthAlignmentDefaultRightX
            right_y = $resolvedDepthAlignmentDefaultRightY
        }
    }
}

$nativeIdentityInputs = [ordered]@{
    cargo_lock = Join-Path $repoRoot "Cargo.lock"
    workspace_manifest = Join-Path $repoRoot "Cargo.toml"
    native_receipt = Join-Path $appRoot "native-receipt"
    particle_adapter = Join-Path $repoRoot "crates\rusty-quest-particle-adapter"
    hand_adapter = Join-Path $repoRoot "crates\rusty-quest-hand-adapter"
    feature_activation = Join-Path $repoRoot "crates\rusty-quest-feature-activation"
}
$nativeSourceSha256 = Get-PathSetSha256 -Paths $nativeIdentityInputs
$privateNativeIdentity = [ordered]@{
    profile_sha256 = if ($null -eq $buildInputDescriptor.private_layer.profile) { "" } else { [string]$buildInputDescriptor.private_layer.profile.sha256 }
    guide_shader_sha256 = if ($null -eq $buildInputDescriptor.private_layer.guide_shader) { "" } else { [string]$buildInputDescriptor.private_layer.guide_shader.sha256 }
    projection_shader_sha256 = if ($null -eq $buildInputDescriptor.private_layer.projection_shader) { "" } else { [string]$buildInputDescriptor.private_layer.projection_shader.sha256 }
    projection_vertex_shader_sha256 = if ($null -eq $buildInputDescriptor.private_layer.projection_vertex_shader) { "" } else { [string]$buildInputDescriptor.private_layer.projection_vertex_shader.sha256 }
    particle_profile_sha256 = if ($null -eq $buildInputDescriptor.private_particles.profile) { "" } else { [string]$buildInputDescriptor.private_particles.profile.sha256 }
    particle_shader_sha256 = if ($null -eq $buildInputDescriptor.private_particles.shader) { "" } else { [string]$buildInputDescriptor.private_particles.shader.sha256 }
    particle_payload_sha256 = if ($null -eq $buildInputDescriptor.private_particles.payload) { "" } else { [string]$buildInputDescriptor.private_particles.payload.sha256 }
}
$nativeIdentityDescriptor = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel.native_cache_identity.v1"
    source_sha256 = $nativeSourceSha256
    private_inputs = $privateNativeIdentity
    ndk_version = $NdkVersion
    target = "aarch64-linux-android"
    profile = "release"
    rust_std_linkage = $RustStdLinkage.ToLowerInvariant()
    elf_max_page_size_bytes = 16384
    elf_common_page_size_bytes = 16384
    projection_surface_uniform_abi_version = $resolvedProjectionSurfaceUniformAbiVersion
    locked_final_presentation = $lockedFinalPresentationEnabled
    distortion_speed_scale = $resolvedDistortionSpeedScale
    environment_depth_owner = $EnvironmentDepthOwner
}
$nativeFingerprint = Get-StringSha256 -Value ($nativeIdentityDescriptor | ConvertTo-Json -Depth 20 -Compress)

$shellIdentityInputs = [ordered]@{
    settings_gradle = Join-Path $appRoot "settings.gradle.kts"
    root_build_gradle = Join-Path $appRoot "build.gradle.kts"
    version_catalog = Join-Path $appRoot "gradle\libs.versions.toml"
    app_build_gradle = Join-Path $appRoot "app\build.gradle.kts"
    app_manifest = Join-Path $appRoot "app\src\main\AndroidManifest.xml"
    app_java = Join-Path $appRoot "app\src\main\java"
    app_resources = Join-Path $appRoot "app\src\main\res"
    spatial_sdk_shared = Join-Path $appRoot "spatial-sdk-shared"
    broker_client_android = Join-Path $repoRoot "crates\rusty-quest-broker-client\android"
    broker_admission_android = Join-Path $repoRoot "crates\rusty-quest-broker-admission\android"
}
if (-not [string]::IsNullOrWhiteSpace($resolvedPrivateFeatureSourceDir)) {
    $shellIdentityInputs["private_source"] = $resolvedPrivateFeatureSourceDir
}
if (-not [string]::IsNullOrWhiteSpace($resolvedPrivateFeatureResourceDir)) {
    $shellIdentityInputs["private_resources"] = $resolvedPrivateFeatureResourceDir
}
$shellSourceSha256 = Get-PathSetSha256 -Paths $shellIdentityInputs
$shellIdentityDescriptor = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel.android_shell_cache_identity.v1"
    source_sha256 = $shellSourceSha256
    product_id = $resolvedProductId
    application_id = $resolvedAppId
    app_label = $resolvedAppLabel
    android_build_type = $buildTypeLower
    gradle_version = $GradleVersion
    android_sdk_build_tools = Split-Path -Leaf $buildTools
    defaults = $buildInputDescriptor.defaults
    camera_projection_default_enabled = [bool]$CameraProjectionDefaultEnabled
    immersive_video_default_enabled = [bool]$ImmersiveVideoDefaultEnabled
    immersive_video_default_offline_pack_id = $resolvedImmersiveVideoDefaultOfflinePackId
    zone_compositor_default_preset = $ZoneCompositorDefaultPreset
    environment_depth_owner = $EnvironmentDepthOwner
    private_assets_sha256 = if ($null -eq $buildInputDescriptor.packaged_inputs.private_assets) { "" } else { [string]$buildInputDescriptor.packaged_inputs.private_assets.sha256 }
    private_resources_sha256 = if ($null -eq $buildInputDescriptor.packaged_inputs.private_resources) { "" } else { [string]$buildInputDescriptor.packaged_inputs.private_resources.sha256 }
}
$shellFingerprint = Get-StringSha256 -Value ($shellIdentityDescriptor | ConvertTo-Json -Depth 20 -Compress)
$packageIdentityDescriptor = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel.package_cache_identity.v1"
    native_fingerprint = $nativeFingerprint
    shell_fingerprint = $shellFingerprint
    application_id = $resolvedAppId
    android_build_type = $buildTypeLower
    signer_sha256 = $certificateSha256
    expected_signer_sha256 = $normalizedExpectedSignerSha256
    packaged_inputs = $buildInputDescriptor.packaged_inputs
}
$packageFingerprint = Get-StringSha256 -Value ($packageIdentityDescriptor | ConvertTo-Json -Depth 20 -Compress)
$buildInputDescriptor["cache_identities"] = [ordered]@{
    native = $nativeFingerprint
    android_shell = $shellFingerprint
    package = $packageFingerprint
}
$buildInputFingerprint = Get-StringSha256 -Value ($buildInputDescriptor | ConvertTo-Json -Depth 20 -Compress)
$buildInputDescriptor["fingerprint"] = $buildInputFingerprint
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $packageLeaf = ($resolvedAppId -replace '[^A-Za-z0-9_.-]+', '-')
    $OutDir = Join-Path $targetRoot "spatial-camera-panel-android\builds\$packageLeaf\$($buildInputFingerprint.Substring(0, 24))"
}

New-Item -ItemType Directory -Force -Path $targetRoot | Out-Null
$resolvedTargetRoot = (Resolve-Path $targetRoot).Path.TrimEnd([char[]]@('\'))
$resolvedOutFull = [System.IO.Path]::GetFullPath($OutDir).TrimEnd([char[]]@('\'))
if (-not $resolvedOutFull.StartsWith($resolvedTargetRoot + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must be under the repo target directory: $resolvedOutFull"
}
if (Test-Path -LiteralPath $OutDir) {
    if (-not $ReplaceExistingOutput) { throw "Content-addressed Spatial APK output already exists: $OutDir. Reuse its run capsule or pass -ReplaceExistingOutput explicitly." }
    Remove-Item -LiteralPath (Resolve-Path -LiteralPath $OutDir).Path -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$buildInputLockPath = Join-Path $OutDir "build-input-lock.json"
[void](Set-TextFileIfChanged -Path $buildInputLockPath -Value ($buildInputDescriptor | ConvertTo-Json -Depth 20))
$propertyManifestPath = Join-Path $OutDir "spatial-property-manifest.json"
$propertyScanRoots = @([string]$appRoot)
if (-not [string]::IsNullOrWhiteSpace($resolvedPrivateFeatureSourceDir)) { $propertyScanRoots += $resolvedPrivateFeatureSourceDir }
$propertyNames = Get-SpatialPropertyNames -Roots $propertyScanRoots
if ($propertyNames.Count -eq 0) { throw "Spatial APK build could not discover its app-scoped Android property surface." }
$propertyManifest = [ordered]@{
    schema = "rusty.quest.android_property_manifest.v1"
    owner_package = $resolvedAppId
    scope = "complete-source-consumer-surface"
    prefixes = @("debug.rustyquest.spatial.", "debug.rustyquest.spatial_camera_panel.")
    properties = @($propertyNames | ForEach-Object { [ordered]@{ name = [string]$_ } })
}
[void](Set-TextFileIfChanged -Path $propertyManifestPath -Value ($propertyManifest | ConvertTo-Json -Depth 8))

# Final artifacts remain content-addressed under target/. Compiler intermediates deliberately do
# not: stable, short lanes let Cargo and Gradle observe the actual file-level invalidation graph.
$cacheLaneId = (("{0}-{1}" -f $resolvedProductId, $buildTypeLower) -replace '[^A-Za-z0-9_.-]+', '-').ToLowerInvariant()
$cacheLaneRoot = Join-Path $BuildCacheRoot ("l\{0}" -f $cacheLaneId)
$cacheStateRoot = Join-Path $BuildCacheRoot "state"
$cacheStatePath = Join-Path $cacheStateRoot ("{0}.json" -f $cacheLaneId)
$productBuildRoot = Join-Path $BuildCacheRoot ("g\{0}" -f $cacheLaneId)
$appBuildDir = Join-Path $productBuildRoot "a"
$rootBuildDir = Join-Path $productBuildRoot "r"
$gradleProjectCacheDir = Join-Path $BuildCacheRoot "gp"
$gradleUserHome = Join-Path $BuildCacheRoot "gu"
$nativeReceiptTargetDir = Join-Path $BuildCacheRoot ("c\{0}" -f $RustStdLinkage.Substring(0, 1).ToLowerInvariant())
New-Item -ItemType Directory -Force -Path @(
    $cacheLaneRoot,
    $cacheStateRoot,
    $productBuildRoot,
    $appBuildDir,
    $rootBuildDir,
    $gradleProjectCacheDir,
    $gradleUserHome,
    $nativeReceiptTargetDir
) | Out-Null

$nativeReceiptRoot = Join-Path $appRoot "native-receipt"
$nativeReceiptCargoManifest = Join-Path $nativeReceiptRoot "Cargo.toml"
$nativeReceiptJniRoot = Join-Path $appBuildDir "generated\rustJniLibs"
$nativeReceiptJniAbiDir = Join-Path $nativeReceiptJniRoot "arm64-v8a"
$nativeReceiptJniLib = Join-Path $nativeReceiptJniAbiDir "libspatial_camera_panel_native_receipt.so"
$nativeReceiptApkEntry = "lib/arm64-v8a/libspatial_camera_panel_native_receipt.so"
$nativeReceiptBuiltLib = Join-Path $nativeReceiptTargetDir "aarch64-linux-android\release\libspatial_camera_panel_native_receipt.so"
$apkSource = Join-Path $appBuildDir "outputs\apk\$buildTypeLower\app-$buildTypeLower.apk"
$priorCacheState = if (Test-Path -LiteralPath $cacheStatePath -PathType Leaf) {
    Get-Content -LiteralPath $cacheStatePath -Raw | ConvertFrom-Json
} else {
    $null
}
$priorNativeDescriptor = if ($null -eq $priorCacheState) { $null } else { $priorCacheState.identity_descriptors.native }
$priorShellDescriptor = if ($null -eq $priorCacheState) { $null } else { $priorCacheState.identity_descriptors.android_shell }
$priorPackageDescriptor = if ($null -eq $priorCacheState) { $null } else { $priorCacheState.identity_descriptors.package }
$nativePriorCacheAvailable = $null -ne $priorCacheState -and
    [string]$priorCacheState.fingerprints.native -ceq $nativeFingerprint -and
    (Test-Path -LiteralPath $nativeReceiptBuiltLib -PathType Leaf) -and
    [string]$priorCacheState.outputs.native_sha256 -ceq (Get-FileSha256 -Path $nativeReceiptBuiltLib)
$shellPriorCacheAvailable = $null -ne $priorCacheState -and
    [string]$priorCacheState.fingerprints.android_shell -ceq $shellFingerprint -and
    (Test-Path -LiteralPath $appBuildDir -PathType Container)
$packagePriorCacheAvailable = $null -ne $priorCacheState -and
    [string]$priorCacheState.fingerprints.package -ceq $packageFingerprint -and
    (Test-Path -LiteralPath $apkSource -PathType Leaf) -and
    [string]$priorCacheState.outputs.apk_sha256 -ceq (Get-FileSha256 -Path $apkSource)
$nativeInvalidation = @(Get-IdentityInvalidation -Previous $priorNativeDescriptor -Current $nativeIdentityDescriptor)
$shellInvalidation = @(Get-IdentityInvalidation -Previous $priorShellDescriptor -Current $shellIdentityDescriptor)
$packageInvalidation = @(Get-IdentityInvalidation -Previous $priorPackageDescriptor -Current $packageIdentityDescriptor)
$cacheIdentityReceipt = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel.build_cache_identities.v1"
    workflow_mode = $BuildMode.ToLowerInvariant()
    stable_short_cache = $true
    paths_recorded = $false
    fingerprints = [ordered]@{
        native = $nativeFingerprint
        android_shell = $shellFingerprint
        package = $packageFingerprint
    }
    prior_cache = [ordered]@{
        semantics = "matching-prior-identity-and-verified-output-available; compilation-still-observed-separately"
        native = [ordered]@{ available = $nativePriorCacheAvailable; invalidation = $nativeInvalidation }
        android_shell = [ordered]@{ available = $shellPriorCacheAvailable; invalidation = $shellInvalidation }
        package = [ordered]@{ available = $packagePriorCacheAvailable; invalidation = $packageInvalidation }
    }
    serialized_lane = [ordered]@{
        enabled = $true
        wait_ms = $buildLaneMutexWaitMs
        abandoned_previous_owner = $buildLaneMutexAbandoned
        mutex_name_recorded = $false
    }
}
$cacheIdentityReceiptPath = Join-Path $OutDir "build-cache-identities.json"
[void](Set-TextFileIfChanged -Path $cacheIdentityReceiptPath -Value ($cacheIdentityReceipt | ConvertTo-Json -Depth 20))
Write-Host ("BUILD_CACHE nativePriorAvailable={0} shellPriorAvailable={1} packagePriorAvailable={2} nativeInvalidation={3} shellInvalidation={4} packageInvalidation={5}" -f `
    $nativePriorCacheAvailable,
    $shellPriorCacheAvailable,
    $packagePriorCacheAvailable,
    ($nativeInvalidation -join ","),
    ($shellInvalidation -join ","),
    ($packageInvalidation -join ","))
$cargoCommand = Get-Command cargo -ErrorAction Stop
$rustupCommand = Get-Command rustup -ErrorAction SilentlyContinue
if (-not (Test-Path -LiteralPath $nativeReceiptCargoManifest)) {
    throw "Missing Spatial Camera Panel native receipt Cargo manifest: $nativeReceiptCargoManifest"
}
if (-not (Test-Path -LiteralPath $nativeReceiptLinker)) {
    throw "Required Android NDK linker not found: $nativeReceiptLinker"
}
$rustTargetInstalled = $false
if ($null -ne $rustupCommand) {
    $installedTargets = @(& $rustupCommand.Source "target" "list" "--installed")
    if ($LASTEXITCODE -ne 0) { throw "rustup target list --installed failed." }
    $rustTargetInstalled = @($installedTargets | Where-Object { $_.Trim() -ceq "aarch64-linux-android" }).Count -eq 1
    if (-not $rustTargetInstalled) {
        Invoke-Checked "rustup target add aarch64-linux-android" $rustupCommand.Source @(
            "target",
            "add",
            "aarch64-linux-android"
        )
    }
}
Write-Host ("BUILD_CACHE rust_target={0}" -f $(if ($rustTargetInstalled) { "already-installed" } else { "installed" }))

New-Item -ItemType Directory -Force -Path $nativeReceiptJniAbiDir, $nativeReceiptTargetDir | Out-Null
$nativeStopwatch = [Diagnostics.Stopwatch]::StartNew()
$cargoOutput = [Collections.Generic.List[string]]::new()
$previousAndroidHomeForCargo = $env:ANDROID_HOME
$previousNdkHomeForCargo = $env:ANDROID_NDK_HOME
$previousLinkerForCargo = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
$previousCcForCargo = $env:CC_aarch64_linux_android
$previousRustFlagsForCargo = $env:RUSTFLAGS
$previousRecordedHandCaptureDir = $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR
$previousRecordedHandFrameLimit = $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT
$previousPrivateLayerProfile = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PRIVATE_LAYER_PROFILE
$previousOpaqueGuideShader = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER
$previousOpaqueProjectionShader = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER
$previousOpaqueProjectionVertexShader = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_VERTEX_SHADER
$previousOpaqueProjectionEffect = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_EFFECT
$previousProjectionSurfaceUniformAbiVersion = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PROJECTION_SURFACE_UNIFORM_ABI_VERSION
$previousLockedFinalPresentation = $env:RUSTY_QUEST_SPATIAL_LOCKED_FINAL_PRESENTATION
$previousDistortionSpeedScale = $env:RUSTY_QUEST_SPATIAL_DISTORTION_SPEED_SCALE
$previousPrivateSurfaceParticleProfile = $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PROFILE
$previousPrivateSurfaceParticleShader = $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_SHADER
$previousPrivateSurfaceParticlePayloadDir = $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PAYLOAD_DIR
$previousPrivateSurfaceParticleMarkerPrefix = $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_MARKER_PREFIX
$previousEnvironmentDepthOwnerForCargo = $env:RUSTY_QUEST_SPATIAL_ENVIRONMENT_DEPTH_OWNER
try {
    $env:ANDROID_HOME = $AndroidHome
    $env:ANDROID_NDK_HOME = $NdkHome
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $nativeReceiptLinker
    $env:CC_aarch64_linux_android = $nativeReceiptLinker
    $rustPageAlignmentFlags =
        "-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
    $env:RUSTFLAGS = if ($RustStdLinkage -eq "Dynamic") {
        "-C prefer-dynamic $rustPageAlignmentFlags"
    } else {
        $rustPageAlignmentFlags
    }
    $env:RUSTY_QUEST_SPATIAL_LOCKED_FINAL_PRESENTATION = $lockedFinalPresentationEnabled.ToString().ToLowerInvariant()
    $env:RUSTY_QUEST_SPATIAL_DISTORTION_SPEED_SCALE = $resolvedDistortionSpeedScale.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    $env:RUSTY_QUEST_SPATIAL_ENVIRONMENT_DEPTH_OWNER = $EnvironmentDepthOwner
    $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PROJECTION_SURFACE_UNIFORM_ABI_VERSION =
        $resolvedProjectionSurfaceUniformAbiVersion.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    if ([string]::IsNullOrWhiteSpace($resolvedRecordedHandCaptureDir)) {
        Remove-Item Env:\RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR -ErrorAction SilentlyContinue
        Remove-Item Env:\RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR = $resolvedRecordedHandCaptureDir
        $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT = $resolvedRecordedHandFrameLimit.ToString()
    }
    if ([string]::IsNullOrWhiteSpace($resolvedPrivateLayerProfilePath)) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PRIVATE_LAYER_PROFILE -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PRIVATE_LAYER_PROFILE = $resolvedPrivateLayerProfilePath
    }
    if ($privateLayerShaderInputsConfigured) {
        $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER = $resolvedOpaqueGuideShader
        $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER = $resolvedOpaqueProjectionShader
        if ([string]::IsNullOrWhiteSpace($resolvedOpaqueProjectionVertexShader)) {
            Remove-Item Env:\RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_VERTEX_SHADER -ErrorAction SilentlyContinue
        } else {
            $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_VERTEX_SHADER = $resolvedOpaqueProjectionVertexShader
        }
        $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_EFFECT = $OpaqueProjectionEffect
    } else {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER -ErrorAction SilentlyContinue
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER -ErrorAction SilentlyContinue
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_VERTEX_SHADER -ErrorAction SilentlyContinue
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_EFFECT -ErrorAction SilentlyContinue
    }
    if ($privateSurfaceParticleInputsConfigured) {
        $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PROFILE = $resolvedPrivateSurfaceParticleProfilePath
        if ([string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleShader)) {
            Remove-Item Env:\RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_SHADER -ErrorAction SilentlyContinue
        } else {
            $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_SHADER = $resolvedPrivateSurfaceParticleShader
        }
        if ([string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticlePayloadDir)) {
            Remove-Item Env:\RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PAYLOAD_DIR -ErrorAction SilentlyContinue
        } else {
            $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PAYLOAD_DIR = $resolvedPrivateSurfaceParticlePayloadDir
        }
        if ([string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleMarkerPrefix)) {
            Remove-Item Env:\RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_MARKER_PREFIX -ErrorAction SilentlyContinue
        } else {
            $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_MARKER_PREFIX = $resolvedPrivateSurfaceParticleMarkerPrefix
        }
    } else {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PROFILE -ErrorAction SilentlyContinue
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_SHADER -ErrorAction SilentlyContinue
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PAYLOAD_DIR -ErrorAction SilentlyContinue
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_MARKER_PREFIX -ErrorAction SilentlyContinue
    }
    $cargoArguments = @(
        "build",
        "--manifest-path", $nativeReceiptCargoManifest,
        "--locked",
        "--target", "aarch64-linux-android",
        "--release",
        "--target-dir", $nativeReceiptTargetDir
    )
    & $cargoCommand.Source @cargoArguments 2>&1 | ForEach-Object {
        $line = [string]$_
        $cargoOutput.Add($line)
        Write-Host $line
    }
    $cargoExitCode = $LASTEXITCODE
    if ($cargoExitCode -ne 0) {
        throw "Spatial Camera Panel native receipt cargo build failed with exit code $cargoExitCode"
    }
} finally {
    if ($null -eq $previousAndroidHomeForCargo) {
        Remove-Item Env:\ANDROID_HOME -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_HOME = $previousAndroidHomeForCargo
    }
    if ($null -eq $previousNdkHomeForCargo) {
        Remove-Item Env:\ANDROID_NDK_HOME -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_NDK_HOME = $previousNdkHomeForCargo
    }
    if ($null -eq $previousLinkerForCargo) {
        Remove-Item Env:\CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER -ErrorAction SilentlyContinue
    } else {
        $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $previousLinkerForCargo
    }
    if ($null -eq $previousCcForCargo) {
        Remove-Item Env:\CC_aarch64_linux_android -ErrorAction SilentlyContinue
    } else {
        $env:CC_aarch64_linux_android = $previousCcForCargo
    }
    if ($null -eq $previousRustFlagsForCargo) {
        Remove-Item Env:\RUSTFLAGS -ErrorAction SilentlyContinue
    } else {
        $env:RUSTFLAGS = $previousRustFlagsForCargo
    }
    if ($null -eq $previousEnvironmentDepthOwnerForCargo) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_ENVIRONMENT_DEPTH_OWNER -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_ENVIRONMENT_DEPTH_OWNER = $previousEnvironmentDepthOwnerForCargo
    }
    if ($null -eq $previousRecordedHandCaptureDir) {
        Remove-Item Env:\RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR = $previousRecordedHandCaptureDir
    }
    if ($null -eq $previousRecordedHandFrameLimit) {
        Remove-Item Env:\RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT = $previousRecordedHandFrameLimit
    }
    if ($null -eq $previousPrivateLayerProfile) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PRIVATE_LAYER_PROFILE -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PRIVATE_LAYER_PROFILE = $previousPrivateLayerProfile
    }
    if ($null -eq $previousOpaqueGuideShader) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER = $previousOpaqueGuideShader
    }
    if ($null -eq $previousOpaqueProjectionShader) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER = $previousOpaqueProjectionShader
    }
    if ($null -eq $previousOpaqueProjectionVertexShader) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_VERTEX_SHADER -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_VERTEX_SHADER = $previousOpaqueProjectionVertexShader
    }
    if ($null -eq $previousOpaqueProjectionEffect) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_EFFECT -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_EFFECT = $previousOpaqueProjectionEffect
    }
    if ($null -eq $previousProjectionSurfaceUniformAbiVersion) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PROJECTION_SURFACE_UNIFORM_ABI_VERSION -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PROJECTION_SURFACE_UNIFORM_ABI_VERSION =
            $previousProjectionSurfaceUniformAbiVersion
    }
    if ($null -eq $previousLockedFinalPresentation) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_LOCKED_FINAL_PRESENTATION -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_LOCKED_FINAL_PRESENTATION = $previousLockedFinalPresentation
    }
    if ($null -eq $previousDistortionSpeedScale) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_DISTORTION_SPEED_SCALE -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_DISTORTION_SPEED_SCALE = $previousDistortionSpeedScale
    }
    if ($null -eq $previousPrivateSurfaceParticleProfile) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PROFILE -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PROFILE = $previousPrivateSurfaceParticleProfile
    }
    if ($null -eq $previousPrivateSurfaceParticleShader) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_SHADER -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_SHADER = $previousPrivateSurfaceParticleShader
    }
    if ($null -eq $previousPrivateSurfaceParticlePayloadDir) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PAYLOAD_DIR -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PAYLOAD_DIR = $previousPrivateSurfaceParticlePayloadDir
    }
    if ($null -eq $previousPrivateSurfaceParticleMarkerPrefix) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_MARKER_PREFIX -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_MARKER_PREFIX = $previousPrivateSurfaceParticleMarkerPrefix
    }
}
$nativeStopwatch.Stop()
if (-not (Test-Path -LiteralPath $nativeReceiptBuiltLib)) {
    throw "Cargo build did not produce native receipt library: $nativeReceiptBuiltLib"
}
$nativeJniUpdated = Copy-FileIfChanged -Source $nativeReceiptBuiltLib -Destination $nativeReceiptJniLib
$nativeReceiptSha256 = Get-FileSha256 -Path $nativeReceiptJniLib
$cargoCompileUnitCount = @($cargoOutput | Where-Object { $_ -match '^\s*Compiling\s+' }).Count
$cargoObservedFresh = $cargoCompileUnitCount -eq 0 -and
    @($cargoOutput | Where-Object { $_ -match '^\s*Finished\s+`release`\s+profile' }).Count -gt 0
$phaseReceipts.Add([ordered]@{
    phase = "native-compile-link"
    duration_ms = $nativeStopwatch.ElapsedMilliseconds
    prior_cache_available = $nativePriorCacheAvailable
    prior_cache_semantics = "matching-identity-and-verified-output-only"
    invalidation = $nativeInvalidation
    cargo_observed_fresh = $cargoObservedFresh
    cargo_compile_unit_count = $cargoCompileUnitCount
    rust_target_setup = $(if ($rustTargetInstalled) { "already-installed" } else { "installed" })
    rust_std_linkage = $RustStdLinkage.ToLowerInvariant()
    jni_payload_changed = $nativeJniUpdated
    status = "pass"
})
Write-Host ("BUILD_PHASE native-compile-link status=pass durationMs={0} priorCacheAvailable={1} cargoFresh={2} cargoCompileUnits={3} jniChanged={4}" -f `
    $nativeStopwatch.ElapsedMilliseconds,
    $nativePriorCacheAvailable,
    $cargoObservedFresh,
    $cargoCompileUnitCount,
    $nativeJniUpdated)

$gradleBat = Resolve-Gradle -RepoRoot ([string]$repoRoot) -Version $GradleVersion
New-Item -ItemType Directory -Force -Path $gradleUserHome | Out-Null

$previousAndroidHome = $env:ANDROID_HOME
$previousGradleNdkHome = $env:ANDROID_NDK_HOME
$previousGradleNdkVersion = $env:RUSTY_QUEST_ANDROID_NDK_VERSION
$previousJavaHome = $env:JAVA_HOME
$previousGradleUserHome = $env:GRADLE_USER_HOME
$previousSpatialProductId = $env:RUSTY_QUEST_SPATIAL_PRODUCT_ID
$previousSpatialAppBuildDir = $env:RUSTY_QUEST_SPATIAL_APP_BUILD_DIR
$previousSpatialRootBuildDir = $env:RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR
$previousSpatialAppId = $env:RUSTY_QUEST_SPATIAL_APP_ID
$previousSpatialAppLabel = $env:RUSTY_QUEST_SPATIAL_APP_LABEL
$previousGradleLockedFinalPresentation = $env:RUSTY_QUEST_SPATIAL_LOCKED_FINAL_PRESENTATION
$previousGradleDistortionSpeedScale = $env:RUSTY_QUEST_SPATIAL_DISTORTION_SPEED_SCALE
$previousHandMeshRigAssetDir = $env:RUSTY_QUEST_SPATIAL_HAND_MESH_RIG_ASSET_DIR
$previousSigningKeystore = $env:RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE
$previousOfflineMediaPackAssetDir = $env:RUSTY_QUEST_OFFLINE_MEDIA_PACK_ASSET_DIR
$previousOfflineMediaKeyHex = $env:RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX
$previousSpatialScopedEnvironment = @{}
foreach ($entry in @(Get-ChildItem Env: | Where-Object { $_.Name -like "RUSTY_QUEST_SPATIAL_*" })) {
    $previousSpatialScopedEnvironment[[string]$entry.Name] = [string]$entry.Value
}
$ignoredAmbientSpatialFeatureVariables = @($previousSpatialScopedEnvironment.Keys | Sort-Object)
try {
    foreach ($name in @($previousSpatialScopedEnvironment.Keys)) {
        [Environment]::SetEnvironmentVariable([string]$name, $null, "Process")
    }
    $env:ANDROID_HOME = $AndroidHome
    $env:ANDROID_NDK_HOME = $NdkHome
    $env:RUSTY_QUEST_ANDROID_NDK_VERSION = Split-Path -Leaf $NdkHome
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = $gradleUserHome
    $env:RUSTY_QUEST_SPATIAL_PRODUCT_ID = $resolvedProductId
    $env:RUSTY_QUEST_SPATIAL_APP_BUILD_DIR = $appBuildDir
    $env:RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR = $rootBuildDir
    $env:RUSTY_QUEST_SPATIAL_APP_ID = $resolvedAppId
    $env:RUSTY_QUEST_SPATIAL_APP_LABEL = $resolvedAppLabel
    $env:RUSTY_QUEST_SPATIAL_LOCKED_FINAL_PRESENTATION = $lockedFinalPresentationEnabled.ToString().ToLowerInvariant()
    $env:RUSTY_QUEST_SPATIAL_DISTORTION_SPEED_SCALE = $resolvedDistortionSpeedScale.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    $env:RUSTY_QUEST_SPATIAL_DEPTH_ALIGNMENT_DEFAULT_LEFT_X = $resolvedDepthAlignmentDefaultLeftX.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    $env:RUSTY_QUEST_SPATIAL_DEPTH_ALIGNMENT_DEFAULT_LEFT_Y = $resolvedDepthAlignmentDefaultLeftY.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    $env:RUSTY_QUEST_SPATIAL_DEPTH_ALIGNMENT_DEFAULT_RIGHT_X = $resolvedDepthAlignmentDefaultRightX.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    $env:RUSTY_QUEST_SPATIAL_DEPTH_ALIGNMENT_DEFAULT_RIGHT_Y = $resolvedDepthAlignmentDefaultRightY.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    $env:RUSTY_QUEST_SPATIAL_CAMERA_PROJECTION_DEFAULT_ENABLED = ([bool]$CameraProjectionDefaultEnabled).ToString().ToLowerInvariant()
    $env:RUSTY_QUEST_SPATIAL_ENVIRONMENT_DEPTH_OWNER = $EnvironmentDepthOwner
    $env:RUSTY_QUEST_SPATIAL_IMMERSIVE_VIDEO_DEFAULT_ENABLED = ([bool]$ImmersiveVideoDefaultEnabled).ToString().ToLowerInvariant()
    $env:RUSTY_QUEST_SPATIAL_IMMERSIVE_VIDEO_DEFAULT_OFFLINE_PACK_ID = $resolvedImmersiveVideoDefaultOfflinePackId
    $env:RUSTY_QUEST_SPATIAL_ZONE_COMPOSITOR_DEFAULT_PRESET = $ZoneCompositorDefaultPreset
    $env:RUSTY_QUEST_SPATIAL_BUILD_ROOT = $productBuildRoot
    $env:RUSTY_QUEST_SPATIAL_PARTICLE_LAYER_CARRIER_DEFAULT = $ParticleLayerCarrierDefault
    $env:RUSTY_QUEST_SPATIAL_START_IN_PARTICLE_VIEW_DEFAULT = $StartInParticleViewDefault
    $env:RUSTY_QUEST_SPATIAL_PANEL_LAUNCHER_VISIBLE_DEFAULT = $PanelLauncherVisibleDefault
    $env:RUSTY_QUEST_SPATIAL_HAND_ALIGNMENT_ENABLED_DEFAULT = $HandAlignmentEnabledDefault
    $env:RUSTY_QUEST_SPATIAL_HAND_ALIGNMENT_VIEWER_MARKERS_ENABLED_DEFAULT = $HandAlignmentViewerMarkersEnabledDefault
    $env:RUSTY_QUEST_SPATIAL_HAND_ALIGNMENT_MAPPING_PROFILE_DEFAULT = $HandAlignmentMappingProfileDefault
    $env:RUSTY_QUEST_SPATIAL_HAND_BILLBOARD_FLOCK_ENABLED_DEFAULT = $HandBillboardFlockEnabledDefault
    $env:RUSTY_QUEST_SPATIAL_HAND_BILLBOARD_SOURCE_DEFAULT = $HandBillboardSourceDefault
    if ([string]::IsNullOrWhiteSpace($resolvedOfflineMediaPackAssetDir)) {
        Remove-Item Env:\RUSTY_QUEST_OFFLINE_MEDIA_PACK_ASSET_DIR -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_OFFLINE_MEDIA_PACK_ASSET_DIR = $resolvedOfflineMediaPackAssetDir
    }
    if ([string]::IsNullOrWhiteSpace($resolvedOfflineMediaKeyHex)) {
        Remove-Item Env:\RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX = $resolvedOfflineMediaKeyHex
    }
    if ([string]::IsNullOrWhiteSpace($resolvedHandMeshRigAssetDir)) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_HAND_MESH_RIG_ASSET_DIR -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_HAND_MESH_RIG_ASSET_DIR = $resolvedHandMeshRigAssetDir
    }
    if ([string]::IsNullOrWhiteSpace($Keystore)) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE -ErrorAction SilentlyContinue
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SIGNING_KEY_ALIAS -ErrorAction SilentlyContinue
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SIGNING_STORE_PASSWORD -ErrorAction SilentlyContinue
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SIGNING_KEY_PASSWORD -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE = $Keystore
        $env:RUSTY_QUEST_SPATIAL_SIGNING_KEY_ALIAS = $signingAlias
        $env:RUSTY_QUEST_SPATIAL_SIGNING_STORE_PASSWORD = $signingStorePassword
        $env:RUSTY_QUEST_SPATIAL_SIGNING_KEY_PASSWORD = $signingKeyPassword
    }
    foreach ($binding in @(
        @{ Name = "RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_SRC_DIR"; Value = $resolvedPrivateFeatureSourceDir },
        @{ Name = "RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_ASSET_DIR"; Value = $resolvedPrivateFeatureAssetDir },
        @{ Name = "RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_RES_DIR"; Value = $resolvedPrivateFeatureResourceDir }
    )) {
        if ([string]::IsNullOrWhiteSpace([string]$binding.Value)) {
            [Environment]::SetEnvironmentVariable([string]$binding.Name, $null, "Process")
        } else {
            [Environment]::SetEnvironmentVariable([string]$binding.Name, [string]$binding.Value, "Process")
        }
    }
    $gradleArguments = @(
        $(if ($BuildMode -eq "DevFast") { "--daemon" } else { "--no-daemon" }),
        $(if ($BuildMode -eq "DevFast") { "--configuration-cache" } else { "--no-configuration-cache" }),
        "--console=plain",
        "--build-cache",
        "--project-cache-dir", $gradleProjectCacheDir,
        "-Pandroid.aapt2FromMavenOverride=$shortAapt2",
        "-p", ([string]$appRoot),
        ":app:assemble$BuildType"
    )
    if ($BuildMode -eq "Candidate") {
        $gradleArguments = @("--init-script", $gradleTimingInitPath) + $gradleArguments
    }
    $gradleStopwatch = [Diagnostics.Stopwatch]::StartNew()
    $gradleOutput = [Collections.Generic.List[string]]::new()
    & $gradleBat @gradleArguments 2>&1 | ForEach-Object {
        $line = [string]$_
        $gradleOutput.Add($line)
        Write-Host $line
    }
    $gradleExitCode = $LASTEXITCODE
    if ($gradleExitCode -ne 0) {
        throw "Spatial Camera Panel Gradle build failed with exit code $gradleExitCode"
    }
    $gradleStopwatch.Stop()
    $gradleDetailedTaskReceipts = @($gradleOutput | ForEach-Object {
        $match = [regex]::Match([string]$_, '^BUILD_TASK path=(\S+) durationMs=(\d+) outcome=([A-Z_]+)$')
        if ($match.Success) {
            [pscustomobject]@{
                path = $match.Groups[1].Value
                duration_ms = [long]$match.Groups[2].Value
                outcome = $match.Groups[3].Value
            }
        }
    })
    $gradleStandardTaskReceipts = @($gradleOutput | ForEach-Object {
        $match = [regex]::Match([string]$_, '^> Task (\S+?)(?: (UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED|FAILED))?$')
        if ($match.Success) {
            [pscustomobject]@{
                path = $match.Groups[1].Value
                duration_ms = 0L
                outcome = $(if ($match.Groups[2].Success) { $match.Groups[2].Value.Replace('-', '_') } else { "EXECUTED" })
            }
        }
    })
    $gradleTaskReceipts = if ($gradleStandardTaskReceipts.Count -gt 0) {
        @($gradleStandardTaskReceipts | ForEach-Object {
            $standardTask = $_
            $detailedTask = $gradleDetailedTaskReceipts | Where-Object { $_.path -ceq $standardTask.path } | Select-Object -First 1
            [pscustomobject]@{
                path = $standardTask.path
                duration_ms = $(if ($null -eq $detailedTask) { 0L } else { [long]$detailedTask.duration_ms })
                outcome = $standardTask.outcome
            }
        })
    } else {
        @($gradleDetailedTaskReceipts)
    }
    $gradleOutcomeSummary = [ordered]@{
        task_count = $gradleTaskReceipts.Count
        executed = @($gradleTaskReceipts | Where-Object { $_.outcome -eq "EXECUTED" }).Count
        up_to_date = @($gradleTaskReceipts | Where-Object { $_.outcome -eq "UP_TO_DATE" }).Count
        from_cache = @($gradleTaskReceipts | Where-Object { $_.outcome -eq "FROM_CACHE" }).Count
        no_source = @($gradleTaskReceipts | Where-Object { $_.outcome -eq "NO_SOURCE" }).Count
        skipped = @($gradleTaskReceipts | Where-Object { $_.outcome -eq "SKIPPED" }).Count
    }
    $gradlePhaseClassifiers = [ordered]@{
        "kotlin-java" = '(?i)(compile.*(?:Kotlin|Java)|kapt)'
        "resources-aapt2-shaders" = '(?i)(Resource|Manifest|RFile|ResValue|Shader|Asset)'
        "dex" = '(?i)(Dex|GlobalSynthetic)'
        "apk-assembly-sign" = '(?i)(package|assemble|Signing|NativeLib|stripDebug)'
    }
    if ($gradleTaskReceipts.Count -gt 0) {
        foreach ($phaseName in $gradlePhaseClassifiers.Keys) {
            $tasks = @($gradleTaskReceipts | Where-Object { $_.path -match [string]$gradlePhaseClassifiers[$phaseName] })
            $phaseReceipts.Add([ordered]@{
                phase = $phaseName
                duration_ms = [long](($tasks | Measure-Object -Property duration_ms -Sum).Sum)
                duration_semantics = "aggregate-gradle-task-time-may-overlap"
                task_count = $tasks.Count
                executed_count = @($tasks | Where-Object { $_.outcome -eq "EXECUTED" }).Count
                up_to_date_count = @($tasks | Where-Object { $_.outcome -eq "UP_TO_DATE" }).Count
                from_cache_count = @($tasks | Where-Object { $_.outcome -eq "FROM_CACHE" }).Count
                no_source_count = @($tasks | Where-Object { $_.outcome -eq "NO_SOURCE" }).Count
                skipped_count = @($tasks | Where-Object { $_.outcome -eq "SKIPPED" }).Count
                status = "pass"
            })
        }
    }
    $phaseReceipts.Add([ordered]@{
        phase = "android-shell-resources-dex-apk"
        duration_ms = $gradleStopwatch.ElapsedMilliseconds
        shell_prior_cache_available = $shellPriorCacheAvailable
        shell_invalidation = $shellInvalidation
        package_prior_cache_available = $packagePriorCacheAvailable
        package_invalidation = $packageInvalidation
        gradle_observed_outcomes = $gradleOutcomeSummary
        gradle_daemon = ($BuildMode -eq "DevFast")
        aapt2_override = "verified-short-copy"
        status = "pass"
    })
    Write-Host ("BUILD_PHASE android-shell-resources-dex-apk status=pass durationMs={0} shellPriorAvailable={1} packagePriorAvailable={2} executed={3} upToDate={4} fromCache={5} noSource={6} skipped={7}" -f `
        $gradleStopwatch.ElapsedMilliseconds,
        $shellPriorCacheAvailable,
        $packagePriorCacheAvailable,
        $gradleOutcomeSummary.executed,
        $gradleOutcomeSummary.up_to_date,
        $gradleOutcomeSummary.from_cache,
        $gradleOutcomeSummary.no_source,
        $gradleOutcomeSummary.skipped)
} finally {
    $env:ANDROID_HOME = $previousAndroidHome
    if ($null -eq $previousGradleNdkHome) {
        Remove-Item Env:\ANDROID_NDK_HOME -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_NDK_HOME = $previousGradleNdkHome
    }
    if ($null -eq $previousGradleNdkVersion) {
        Remove-Item Env:\RUSTY_QUEST_ANDROID_NDK_VERSION -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_ANDROID_NDK_VERSION = $previousGradleNdkVersion
    }
    $env:JAVA_HOME = $previousJavaHome
    if ($null -eq $previousGradleUserHome) {
        Remove-Item Env:\GRADLE_USER_HOME -ErrorAction SilentlyContinue
    } else {
        $env:GRADLE_USER_HOME = $previousGradleUserHome
    }
    if ($null -eq $previousSpatialAppId) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_APP_ID -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_APP_ID = $previousSpatialAppId
    }
    if ($null -eq $previousSpatialProductId) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_PRODUCT_ID -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_PRODUCT_ID = $previousSpatialProductId
    }
    if ($null -eq $previousSpatialAppBuildDir) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_APP_BUILD_DIR -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_APP_BUILD_DIR = $previousSpatialAppBuildDir
    }
    if ($null -eq $previousSpatialRootBuildDir) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR = $previousSpatialRootBuildDir
    }
    if ($null -eq $previousSpatialAppLabel) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_APP_LABEL -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_APP_LABEL = $previousSpatialAppLabel
    }
    if ($null -eq $previousGradleLockedFinalPresentation) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_LOCKED_FINAL_PRESENTATION -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_LOCKED_FINAL_PRESENTATION = $previousGradleLockedFinalPresentation
    }
    if ($null -eq $previousGradleDistortionSpeedScale) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_DISTORTION_SPEED_SCALE -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_DISTORTION_SPEED_SCALE = $previousGradleDistortionSpeedScale
    }
    if ($null -eq $previousHandMeshRigAssetDir) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_HAND_MESH_RIG_ASSET_DIR -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_HAND_MESH_RIG_ASSET_DIR = $previousHandMeshRigAssetDir
    }
    if ($null -eq $previousSigningKeystore) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE = $previousSigningKeystore
    }
    if ($null -eq $previousOfflineMediaPackAssetDir) {
        Remove-Item Env:\RUSTY_QUEST_OFFLINE_MEDIA_PACK_ASSET_DIR -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_OFFLINE_MEDIA_PACK_ASSET_DIR = $previousOfflineMediaPackAssetDir
    }
    if ($null -eq $previousOfflineMediaKeyHex) {
        Remove-Item Env:\RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX = $previousOfflineMediaKeyHex
    }
    foreach ($name in @(Get-ChildItem Env: | Where-Object { $_.Name -like "RUSTY_QUEST_SPATIAL_*" } | Select-Object -ExpandProperty Name)) {
        [Environment]::SetEnvironmentVariable([string]$name, $null, "Process")
    }
    foreach ($name in @($previousSpatialScopedEnvironment.Keys)) {
        [Environment]::SetEnvironmentVariable([string]$name, [string]$previousSpatialScopedEnvironment[$name], "Process")
    }
}

$apkSource = Join-Path $appBuildDir "outputs\apk\$buildTypeLower\app-$buildTypeLower.apk"
if (-not (Test-Path -LiteralPath $apkSource)) {
    throw "Gradle build did not produce expected APK: $apkSource"
}

$apkOut = Join-Path $OutDir $resolvedApkFileName
$apkCopied = Copy-FileIfChanged -Source $apkSource -Destination $apkOut
$sha256 = Get-FileSha256 -Path $apkOut
$inspectionStopwatch = [Diagnostics.Stopwatch]::StartNew()
$signerInspection = @(& $apksigner "verify" "--verbose" "--print-certs" $apkOut 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Produced APK failed apksigner verification."
}
$signerMatches = @($signerInspection | Select-String -Pattern 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
if ($signerMatches.Count -ne 1) {
    throw "Produced APK did not expose exactly one signer certificate SHA-256 digest."
}
$artifactSignerSha256 = $signerMatches[0].Matches[0].Groups[1].Value.ToLowerInvariant()
if (-not [string]::IsNullOrWhiteSpace($normalizedExpectedSignerSha256) -and
    $artifactSignerSha256 -cne $normalizedExpectedSignerSha256) {
    throw "Produced APK signer differs from the preflight signer contract."
}
& $zipalign "-c" "-P" "16" "-v" "4" $apkOut | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Produced APK failed 16-KiB-aware zip alignment verification." }
$badging = @(& $shortAapt2 "dump" "badging" $apkOut 2>&1)
if ($LASTEXITCODE -ne 0) { throw "Produced APK failed AAPT2 badging inspection." }
$badgingText = $badging -join "`n"
if ($badgingText -notmatch ("package:\s+name='" + [regex]::Escape($resolvedAppId) + "'") -or
    $badgingText -notmatch "sdkVersion:'34'" -or
    $badgingText -notmatch "targetSdkVersion:'34'" -or
    $badgingText -notmatch "launchable-activity:") {
    throw "Produced APK package, SDK, or launcher identity differs from the Spatial Camera Panel contract."
}
$readelfOutput = @(& $llvmReadelf "-lW" $nativeReceiptJniLib 2>&1)
if ($LASTEXITCODE -ne 0) { throw "Produced native receipt failed llvm-readelf inspection." }
$loadSegments = @($readelfOutput | Where-Object { [string]$_ -match '^\s*LOAD\s' })
if ($loadSegments.Count -eq 0) { throw "Produced native receipt exposes no ELF LOAD segments." }
$loadAlignments = @($loadSegments | ForEach-Object {
    $match = [regex]::Match([string]$_, '(0x[0-9a-fA-F]+)\s*$')
    if (-not $match.Success) { throw "Could not parse ELF LOAD alignment." }
    [Convert]::ToInt64($match.Groups[1].Value.Substring(2), 16)
})
if (@($loadAlignments | Where-Object { $_ -lt 16384 }).Count -gt 0) {
    throw "Produced native receipt has an ELF LOAD alignment below 16 KiB."
}
$dynamicSection = @(& $llvmReadelf "-dW" $nativeReceiptJniLib 2>&1)
if ($LASTEXITCODE -ne 0) { throw "Produced native receipt failed dynamic dependency inspection." }
$nativeNeededLibraries = @($dynamicSection | ForEach-Object {
    $match = [regex]::Match([string]$_, '\(NEEDED\).*Shared library: \[([^\]]+)\]')
    if ($match.Success) { $match.Groups[1].Value }
} | Sort-Object -Unique)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkZip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $apkOut).Path)
try {
    $apkEntryNames = @($apkZip.Entries | ForEach-Object { [string]$_.FullName })
} finally {
    $apkZip.Dispose()
}
$nativePayload = @($apkEntryNames | Where-Object { $_ -match '^lib/[^/]+/[^/]+\.so$' } | Sort-Object)
$packagedNativeBasenames = @($nativePayload | ForEach-Object { Split-Path -Leaf $_ } | Sort-Object -Unique)
$missingRustDynamicStd = @($nativeNeededLibraries | Where-Object {
    $_ -like 'libstd-*.so' -and $_ -notin $packagedNativeBasenames
})
$sensitivePayload = @($apkEntryNames | Where-Object {
    $_ -match '(?i)(^|/)(?:[^/]+\.(?:jks|keystore|pem|key|p12|pfx)|local\.properties)$'
})
$plaintextVideoPayload = @($apkEntryNames | Where-Object { $_ -match '(?i)\.(?:mp4|mkv|webm|mov)$' })
if ($sensitivePayload.Count -gt 0) { throw "Produced APK contains key or local-property material." }
if ($plaintextVideoPayload.Count -gt 0) { throw "Produced APK contains plaintext video media instead of external or encrypted media." }
if ($missingRustDynamicStd.Count -gt 0) {
    throw "Dynamic Rust std experiment is not a deployable APK because its required libstd payload is absent."
}
$inspectionStopwatch.Stop()
$apkInspection = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel.apk_inspection.v1"
    package_name = $resolvedAppId
    min_sdk = 34
    target_sdk = 34
    launchable_activity_present = $true
    signer_sha256 = $artifactSignerSha256
    signer_count = 1
    apksigner_verified = $true
    zipalign_4_and_16k_verified = $true
    native_elf_load_alignment_minimum = ($loadAlignments | Measure-Object -Minimum).Minimum
    native_elf_16k_compatible = $true
    native_payload = $nativePayload
    native_needed_libraries = $nativeNeededLibraries
    missing_rust_dynamic_std_count = $missingRustDynamicStd.Count
    sensitive_payload_count = $sensitivePayload.Count
    plaintext_video_payload_count = $plaintextVideoPayload.Count
    private_path_recorded = $false
}
$apkInspectionPath = Join-Path $OutDir "apk-inspection.json"
[void](Set-TextFileIfChanged -Path $apkInspectionPath -Value ($apkInspection | ConvertTo-Json -Depth 12))
$phaseReceipts.Add([ordered]@{
    phase = "zipalign-sign-inspection"
    duration_ms = $inspectionStopwatch.ElapsedMilliseconds
    artifact_copy = $(if ($apkCopied) { "copied" } else { "unchanged" })
    status = "pass"
})
Write-Host ("BUILD_PHASE zipalign-sign-inspection status=pass durationMs={0} signer={1} nativePayloadCount={2}" -f `
    $inspectionStopwatch.ElapsedMilliseconds,
    $artifactSignerSha256,
    $nativePayload.Count)
$nativeReceiptLibraryPackaged = Test-ZipEntry -ZipPath $apkOut -EntryName $nativeReceiptApkEntry
if (-not $nativeReceiptLibraryPackaged) {
    throw "APK is missing native receipt library entry: $nativeReceiptApkEntry"
}
$launcherClassDescriptor =
    'Lio/github/mesmerprism/rustyquest/spatial_camera_panel/SpatialCameraPanelActivity;'
if (-not (Test-ApkDexDescriptor `
    -ApkPath $apkOut `
    -Descriptor $launcherClassDescriptor)) {
    throw "APK is missing launcher class DEX descriptor: $launcherClassDescriptor"
}

$cacheState = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel.local_build_cache_state.v1"
    updated_at_utc = [DateTimeOffset]::UtcNow.ToString("o")
    identity_descriptors = [ordered]@{
        native = $nativeIdentityDescriptor
        android_shell = $shellIdentityDescriptor
        package = $packageIdentityDescriptor
    }
    fingerprints = [ordered]@{
        native = $nativeFingerprint
        android_shell = $shellFingerprint
        package = $packageFingerprint
    }
    outputs = [ordered]@{
        native_sha256 = Get-FileSha256 -Path $nativeReceiptBuiltLib
        apk_sha256 = Get-FileSha256 -Path $apkSource
    }
}
[void](Set-TextFileIfChanged -Path $cacheStatePath -Value ($cacheState | ConvertTo-Json -Depth 30))
$totalBuildDurationMs = [long]([DateTimeOffset]::UtcNow - $buildStartedUtc).TotalMilliseconds
$phaseReceipts.Add([ordered]@{
    phase = "complete"
    duration_ms = $totalBuildDurationMs
    cache = "not-applicable"
    status = "pass"
})
$phaseReceipt = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel.build_phase_receipts.v1"
    build_workflow_mode = $BuildMode.ToLowerInvariant()
    started_at_utc = $buildStartedUtc.ToString("o")
    completed_at_utc = [DateTimeOffset]::UtcNow.ToString("o")
    total_duration_ms = $totalBuildDurationMs
    streamed = $true
    paths_recorded = $false
    phases = @($phaseReceipts)
}
$phaseReceiptPath = Join-Path $OutDir "build-phase-receipts.json"
[void](Set-TextFileIfChanged -Path $phaseReceiptPath -Value ($phaseReceipt | ConvertTo-Json -Depth 20))

$offlineMediaEmbeddedKeyEnabled =
    -not [string]::IsNullOrWhiteSpace($resolvedOfflineMediaKeyHex)
$offlineMediaPackagedAssets =
    -not [string]::IsNullOrWhiteSpace($resolvedOfflineMediaPackAssetDir)
$manifest = [ordered]@{
    '$schema' = "rusty.quest.spatial_camera_panel_sdk_android.build_manifest.v1"
    product_id = $resolvedProductId
    build_input_fingerprint = $buildInputFingerprint
    build_input_lock_path = $buildInputLockPath
    build_input_lock_sha256 = Get-FileSha256 -Path $buildInputLockPath
    source_commit = $sourceHead
    source_tree = $sourceTree
    source_tracked_worktree_clean = $sourceTrackedWorktreeClean
    source_worktree_overlay_sha256 = $sourceWorktreeOverlaySha256
    source_composition_fingerprint = [string]$sourceComposition.fingerprint
    source_dependencies = $sourceDependencies
    property_manifest_path = $propertyManifestPath
    property_manifest_sha256 = Get-FileSha256 -Path $propertyManifestPath
    property_manifest_count = $propertyNames.Count
    output_policy = $(if ([bool]$PublicationBuild) { "content-addressed-explicit-input-lock-clean-publication" } else { "content-addressed-explicit-input-lock-observed-worktree-iteration" })
    build_mode = $(if ([bool]$PublicationBuild) { "publication" } else { "iteration" })
    build_workflow_mode = $BuildMode.ToLowerInvariant()
    build_cache_root = "external-stable-short-local-cache"
    build_cache_paths_recorded = $false
    build_cache_identities = [ordered]@{
        native = $nativeFingerprint
        android_shell = $shellFingerprint
        package = $packageFingerprint
    }
    build_cache_prior_availability = [ordered]@{
        semantics = "matching-prior-identity-and-verified-output-only"
        native = $nativePriorCacheAvailable
        android_shell = $shellPriorCacheAvailable
        package = $packagePriorCacheAvailable
    }
    build_phase_receipt_sha256 = Get-FileSha256 -Path $phaseReceiptPath
    apk_inspection_sha256 = Get-FileSha256 -Path $apkInspectionPath
    ambient_spatial_feature_environment_ignored = $ignoredAmbientSpatialFeatureVariables
    package_name = $resolvedAppId
    application_id = $resolvedAppId
    app_label = $resolvedAppLabel
    activity = "$resolvedAppId/io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraPanelActivity"
    source_namespace = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
    app_lane = "spatial-camera-panel-android"
    android_build_type = $buildTypeLower
    android_debuggable = ($BuildType -eq "Debug")
    project_workspace = "private-project-workspace"
    client_id = "client.quest.spatial-camera-panel"
    feature_lock_id = "lock.broker-client.spatial-camera-panel.v1"
    marker_namespace = "RUSTY_QUEST_SPATIAL_BROKER_CLIENT"
    property_namespace = "debug.rustyquest.spatial_camera_panel"
    gradle_app_build_dir = "external-stable-cache/app"
    gradle_root_build_dir = "external-stable-cache/root"
    gradle_project_cache_dir = "external-stable-cache/project"
    authority = "rusty.quest.spatial_camera_panel_sdk_panel"
    target_runtime = "quest-spatial-sdk-appsystemactivity-panel"
    spatial_input_mode = $(if ($lockedFinalPresentationEnabled) { "disabled-presentation-output-only" } else { "interaction-sdk-input-only-no-locomotion" })
    spatial_vr_input_system_default = "interaction_sdk"
    spatial_should_consume_left_right_input_default = $false
    spatial_handtracking_manifest_declared = $true
    spatial_handtracking_permission_declared = $true
    spatial_render_model_manifest_declared = $false
    spatial_render_model_permission_declared = $false
    spatial_scene_permission_declared = $true
    spatial_openxr_permission_declared = $true
    spatial_environment_depth_permission_surface = "horizonos.permission.USE_SCENE+USE_SCENE_DATA"
    spatial_environment_depth_owner = $EnvironmentDepthOwner
    spatial_environment_depth_real_provider_bound = $false
    spatial_environment_depth_data_source = $(if ($EnvironmentDepthOwner -eq "legacy-native-sidecar") { "legacy-native-sidecar-last-valid-or-neutral" } elseif ($EnvironmentDepthOwner -eq "disabled") { "neutral-disabled" } else { "spatial-sdk-api-layer-device-local-d16-ring" })
    spatial_environment_depth_diagnostic_policy = "distinguish-permission-pregrant-provider-binding-acquire-valid-sample"
    spatial_multimodal_input_default_enabled = $false
    native_spatial_controller_actions_default_enabled = $false
    spatial_controller_launch_policy = $(if ($lockedFinalPresentationEnabled) { "disabled-by-locked-presentation-build" } else { "app-owned-readiness-prompt-if-no-active-avatarbody-controller" })
    locked_final_presentation = $lockedFinalPresentationEnabled
    locked_final_private_layer_override = $(if ($lockedFinalPresentationEnabled) { 0.0 } else { $null })
    locked_projection_scale = $(if ($lockedFinalPresentationEnabled) { 1.0 } else { $null })
    locked_app_control_inputs_enabled = (-not $lockedFinalPresentationEnabled)
    locked_video_projection_forced_enabled = $lockedFinalPresentationEnabled
    locked_video_border_forced_enabled = $lockedFinalPresentationEnabled
    distortion_speed_scale = $resolvedDistortionSpeedScale
    distortion_base_phase_rate_hz = 0.5
    distortion_effective_phase_rate_hz = (0.5 * $resolvedDistortionSpeedScale)
    spatial_sdk_version = "0.13.2"
    media3_version = "1.4.1"
    camera_projection_default_enabled = [bool]$CameraProjectionDefaultEnabled
    camera_projection_activation_policy = "explicit-intent-or-property-or-product-build-default"
    immersive_video_default_enabled = [bool]$ImmersiveVideoDefaultEnabled
    immersive_video_default_offline_pack_id = $resolvedImmersiveVideoDefaultOfflinePackId
    immersive_video_default_activation_policy = "explicit-intent-or-product-build-default"
    projection_zone_compositor_default_preset = $ZoneCompositorDefaultPreset
    immersive_video_source_policy = "explicit-single-grant-media-content-uri-app-owned-file-authenticated-packaged-pack-or-persisted-shared-document-tree"
    immersive_video_shape_tokens = @("flat", "equirect-180", "equirect-360")
    immersive_video_stereo_tokens = @("mono", "side-by-side-left-right", "top-bottom")
    immersive_video_render_path = "VideoSurfacePanelRegistration-direct-to-surface"
    immersive_video_media_packaged = $offlineMediaPackagedAssets
    offline_immersive_media_pack_supported = $true
    offline_immersive_media_pack_schema = "rusty.quest.offline_immersive_media_pack.v1"
    offline_immersive_media_encryption = "AES-256-GCM-independent-authenticated-chunks"
    offline_media_key_embedded_prototype = $offlineMediaEmbeddedKeyEnabled
    offline_media_key_value_recorded = $false
    offline_immersive_media_packaged_assets = $offlineMediaPackagedAssets
    offline_immersive_media_plaintext_file_written = $false
    spatial_hand_mesh_rig_packaged = $handMeshRigAssetInfo.ready
    spatial_hand_mesh_rig_asset_id = $handMeshRigAssetInfo.asset_id
    spatial_hand_mesh_rig_asset_file_count = $handMeshRigAssetInfo.file_count
    spatial_hand_mesh_rig_asset_root = "spatial-ecs-replay"
    spatial_hand_mesh_rig_asset_hash = $(if ([string]::IsNullOrWhiteSpace($resolvedHandMeshRigAssetDir)) { "" } else { Get-DirectorySha256 -Path $resolvedHandMeshRigAssetDir })
    spatial_hand_mesh_rig_build_env = "RUSTY_QUEST_SPATIAL_HAND_MESH_RIG_ASSET_DIR"
    spatial_hand_mesh_rig_runtime_source = "XR_EXT_hand_tracking-mapped-world-joints"
    spatial_hand_mesh_rig_skinning = "cpu-linear-blend-four-influences"
    spatial_hand_mesh_rig_surface_anchors = "triangle-index-plus-barycentric"
    spatial_hand_alignment_enabled_default = ($HandAlignmentEnabledDefault -eq "true")
    spatial_hand_alignment_viewer_markers_enabled_default = ($HandAlignmentViewerMarkersEnabledDefault -eq "true")
    spatial_hand_alignment_mapping_profile_default = $HandAlignmentMappingProfileDefault
    spatial_hand_billboard_flock_enabled_default = ($HandBillboardFlockEnabledDefault -eq "true")
    spatial_hand_billboard_source_default = $HandBillboardSourceDefault
    spatial_sdk_3d_asset_module = "spatial-sdk-staged-3d-asset"
    spatial_sdk_3d_asset_module_mesh_uri_transport = "runtime-property-or-intent-extra"
    spatial_sdk_3d_asset_module_source_policy = "no-source-model-packaged-or-committed"
    spatial_sdk_3d_asset_default_activation = "disabled-in-default-workflow-lock"
    spatial_sdk_3d_asset_activation_policy = "exact-conformance-lock-plus-explicit-runtime-input"
    spatial_sdk_3d_asset_activation_profile_id = "profile.quest.spatial_camera_panel.spatial_asset_model_conformance"
    spatial_sdk_3d_asset_activation_project_id = "spatial-camera-panel"
    spatial_sdk_3d_asset_activation_feature_id = "spatial-asset-model"
    spatial_sdk_3d_asset_activation_lock_path = $assetConformanceLockRelativePath
    spatial_sdk_3d_asset_activation_lock_revision = [long]$assetConformanceLock.revision
    spatial_sdk_3d_asset_activation_lock_sha256 = $assetConformanceLockSha256
    spatial_sdk_3d_asset_activation_receipt_schema = "rusty.quest.spatial_asset_model.activation_receipt.v1"
    spatial_sdk_3d_asset_activation_effective_marker = "rusty.quest.spatial_asset_model.effective"
    spatial_sdk_3d_asset_supported_runtime_mesh_formats = @("glb", "gltf")
    spatial_sdk_3d_asset_raw_fbx_policy = "local-source-only-convert-before-staging"
    spatial_sdk_3d_asset_runtime_properties = @(
        "debug.rustyquest.spatial.asset_model.enabled",
        "debug.rustyquest.spatial.asset_model.mesh_uri",
        "debug.rustyquest.spatial.asset_model.source_format",
        "debug.rustyquest.spatial.asset_model.label",
        "debug.rustyquest.spatial.asset_model.position_m",
        "debug.rustyquest.spatial.asset_model.rotation_degrees",
        "debug.rustyquest.spatial.asset_model.scale",
        "debug.rustyquest.spatial.asset_model.grabbable",
        "debug.rustyquest.spatial.asset_model.activation.profile_id",
        "debug.rustyquest.spatial.asset_model.activation.project_id",
        "debug.rustyquest.spatial.asset_model.activation.feature_id",
        "debug.rustyquest.spatial.asset_model.activation.lock_revision",
        "debug.rustyquest.spatial.asset_model.activation.lock_sha256"
    )
    spatial_sdk_virtual_room_module = "spatial-sdk-packaged-virtual-room"
    spatial_sdk_virtual_room_default_enabled = $false
    spatial_sdk_virtual_room_scene_uri = "apk:///scenes/Composition.glxf"
    spatial_sdk_virtual_room_runtime_property = "debug.rustyquest.spatial.virtual_room.enabled"
    spatial_sdk_virtual_room_asset_policy = "packaged-glxf-local-launch-input"
    spatial_sdk_virtual_room_mruk_policy = "disabled-not-real-room-placement"
    spatial_sdk_skybox_default_enabled = $false
    android_gradle_plugin_version = "8.11.1"
    kotlin_version = "2.1.0"
    gradle_version = $GradleVersion
    isolated_intermediate_root = $intermediateRoot
    native_renderer_package_preserved = "io.github.mesmerprism.rustyquest.native_renderer"
    native_renderer_spatial_sdk_packaged = $false
    native_interop_probe = "spatial-sdk-openxr-handles-and-panelsurface-capability"
    native_interop_probe_rendering = "no-render"
    native_interop_probe_runtime_handles = @(
        "Scene.getOpenXrInstanceHandle",
        "Scene.getOpenXrSessionHandle",
        "Scene.getOpenXrGetInstanceProcAddrHandle"
    )
    native_interop_probe_surface = "PanelSurface-create-destroy"
    native_receipt_probe = "rust-jni-openxr-handle-and-panelsurface-receipt"
    native_receipt_rendering = "no-render"
    native_receipt_openxr_probe = "xrGetInstanceProperties-vulkan-requirements-and-no-present-vulkan-objects-through-sdk-getInstanceProcAddr"
    native_receipt_vulkan_object_probe = "no-present-instance-device-queue-create-destroy"
    native_receipt_jni_bridge = "SpatialCameraPanelActivity.nativeRecordNoRenderInteropReceipt"
    native_receipt_mask_bits = @(
        "received",
        "openxr-instance-nonzero",
        "openxr-session-nonzero",
        "openxr-getInstanceProcAddr-nonzero",
        "panel-surface-valid",
        "openxr-getInstanceProcAddr-callable",
        "xrGetInstanceProperties-resolved",
        "xrGetInstanceProperties-succeeded",
        "xrGetSystem-resolved",
        "xrGetSystem-succeeded",
        "xrGetVulkanGraphicsRequirements2KHR-resolved",
        "xrGetVulkanGraphicsRequirements2KHR-succeeded",
        "xrCreateVulkanInstanceKHR-resolved",
        "xrGetVulkanGraphicsDevice2KHR-resolved",
        "xrCreateVulkanDeviceKHR-resolved",
        "vk-instance-created",
        "vk-graphics-device-obtained",
        "vk-graphics-compute-queue-found",
        "vk-device-created",
        "vk-queue-obtained",
        "vk-objects-destroyed"
    )
    native_receipt_library = $nativeReceiptApkEntry
    native_receipt_library_packaged = $nativeReceiptLibraryPackaged
    native_receipt_library_sha256 = $nativeReceiptSha256
    native_receipt_generated_jni_libs = "app/build/generated/rustJniLibs/arm64-v8a"
    spatial_public_guide_target_extent = "768x384-packed-stereo"
    spatial_public_guide_per_eye_extent = "384x384"
    spatial_public_guide_processing_default = "native-parity"
    spatial_public_guide_preblur_kernel_default = "native-box5"
    spatial_public_guide_preblur_input_default = "luma"
    spatial_public_guide_postblur_kernel_default = "native-box5"
    spatial_public_guide_kernel_alternatives = @("native-box5", "gaussian5")
    spatial_public_guide_input_alternatives = @("luma", "rgb-preserve")
    spatial_camera_sampling_default = "thin-line-tent5"
    spatial_camera_sampling_alternatives = @("linear", "thin-line-tent5")
    spatial_camera_sampling_footprint_aware = $true
    spatial_camera_sampling_radius_texels = "0.75..2.0"
    spatial_camera_projection_blend_policy = "premultiplied-alpha-over-same-surface-video"
    spatial_camera_projection_border_inner_blend_uv = 0.04
    spatial_camera_projection_border_blend_curve = 1.6
    spatial_camera_raw_projection_border_blend = $true
    spatial_camera_opaque_projection_border_blend = $true
    spatial_public_guide_processing_properties = @(
        "debug.rustyquest.spatial.camera_hwb_projection_probe.guide.preblur.kernel",
        "debug.rustyquest.spatial.camera_hwb_projection_probe.guide.preblur.input",
        "debug.rustyquest.spatial.camera_hwb_projection_probe.guide.postblur.kernel",
        "debug.rustyquest.spatial.camera_hwb_projection_probe.camera.sampling"
    )
    spatial_public_opaque_guide_native_phase_rate_hz = (0.5 * $resolvedDistortionSpeedScale)
    spatial_public_multistack_private_layer_profile_configured = (-not [string]::IsNullOrWhiteSpace($resolvedPrivateLayerProfilePath))
    spatial_public_multistack_private_shader_inputs = $(if ($privateLayerShaderInputsConfigured) { "external-build-inputs" } else { "not-configured-raw-camera-fallback" })
    spatial_public_multistack_opaque_guide_shader_configured = (-not [string]::IsNullOrWhiteSpace($resolvedOpaqueGuideShader))
    spatial_public_multistack_opaque_projection_shader_configured = (-not [string]::IsNullOrWhiteSpace($resolvedOpaqueProjectionShader))
    spatial_public_multistack_opaque_projection_vertex_shader_configured = (-not [string]::IsNullOrWhiteSpace($resolvedOpaqueProjectionVertexShader))
    spatial_public_multistack_opaque_projection_effect_configured = $opaqueProjectionEffectConfigured
    spatial_public_multistack_opaque_projection_effect = $(if ($opaqueProjectionEffectConfigured) { $OpaqueProjectionEffect } else { "" })
    projection_surface_uniform_abi_version = $resolvedProjectionSurfaceUniformAbiVersion
    projection_surface_uniform_prefix_bytes = 64
    projection_surface_uniform_suffix_bytes = $(if ($resolvedProjectionSurfaceUniformAbiVersion -ge 2) { 64 } else { 0 })
    spatial_public_multistack_private_layer_build_env = @(
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PRIVATE_LAYER_PROFILE",
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER",
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER",
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_VERTEX_SHADER",
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_EFFECT",
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PROJECTION_SURFACE_UNIFORM_ABI_VERSION",
        "RUSTY_QUEST_SPATIAL_DEPTH_ALIGNMENT_DEFAULT_LEFT_X",
        "RUSTY_QUEST_SPATIAL_DEPTH_ALIGNMENT_DEFAULT_LEFT_Y",
        "RUSTY_QUEST_SPATIAL_DEPTH_ALIGNMENT_DEFAULT_RIGHT_X",
        "RUSTY_QUEST_SPATIAL_DEPTH_ALIGNMENT_DEFAULT_RIGHT_Y"
    )
    spatial_public_depth_alignment_defaults = [ordered]@{
        left_x = $resolvedDepthAlignmentDefaultLeftX
        left_y = $resolvedDepthAlignmentDefaultLeftY
        right_x = $resolvedDepthAlignmentDefaultRightX
        right_y = $resolvedDepthAlignmentDefaultRightY
        other_alignment_fields = "unchanged-runtime-defaults"
        named_profile_default = $false
    }
    spatial_public_multistack_private_layer_profile_sha256 = $(if ([string]::IsNullOrWhiteSpace($resolvedPrivateLayerProfilePath)) { "" } else { Get-FileSha256 -Path $resolvedPrivateLayerProfilePath })
    spatial_public_multistack_opaque_guide_shader_sha256 = $(if ([string]::IsNullOrWhiteSpace($resolvedOpaqueGuideShader)) { "" } else { Get-FileSha256 -Path $resolvedOpaqueGuideShader })
    spatial_public_multistack_opaque_projection_shader_sha256 = $(if ([string]::IsNullOrWhiteSpace($resolvedOpaqueProjectionShader)) { "" } else { Get-FileSha256 -Path $resolvedOpaqueProjectionShader })
    spatial_public_multistack_opaque_projection_vertex_shader_sha256 = $(if ([string]::IsNullOrWhiteSpace($resolvedOpaqueProjectionVertexShader)) { "" } else { Get-FileSha256 -Path $resolvedOpaqueProjectionVertexShader })
    spatial_surface_private_particle_hook = "generic-build-time-private-surface-particle-hook"
    spatial_surface_private_particle_public_default = "no-op-private-surface-particle-hook"
    spatial_surface_private_particle_renderer_status = $(if ($privateSurfaceParticleStagedPayloadReady) { "main-draw-overlay-public-hand-anchor-fallback" } elseif ($privateSurfaceParticleInputsConfigured) { "metadata-only-private-renderer-public-hand-anchor-fallback" } else { "public-default-no-private-surface-particle-inputs" })
    spatial_surface_private_particle_profile_configured = (-not [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleProfilePath))
    spatial_surface_private_particle_shader_configured = (-not [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleShader))
    spatial_surface_private_particle_payload_dir_configured = (-not [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticlePayloadDir))
    spatial_surface_private_particle_shader_compiled = (-not [string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleShader))
    spatial_surface_private_particle_payload_files_present = $privateSurfaceParticlePayloadInfo.files_present
    spatial_surface_private_particle_positions_bytes = $privateSurfaceParticlePayloadInfo.positions_bytes
    spatial_surface_private_particle_normals_bytes = $privateSurfaceParticlePayloadInfo.normals_bytes
    spatial_surface_private_particle_aux0_bytes = $privateSurfaceParticlePayloadInfo.aux0_bytes
    spatial_surface_private_particle_mask_texture_bytes = $privateSurfaceParticlePayloadInfo.mask_texture_bytes
    spatial_surface_private_particle_staged_payload_ready = $privateSurfaceParticleStagedPayloadReady
    spatial_surface_private_particle_metadata_mode = "build-inputs-only"
    spatial_surface_private_particle_metadata_validation_scope = "public-build-hook"
    spatial_surface_private_particle_metadata_active = $privateSurfaceParticleInputsConfigured
    spatial_surface_private_particle_executable_inputs_configured = $privateSurfaceParticleExecutableInputsConfigured
    spatial_surface_private_particle_marker_prefix = $resolvedPrivateSurfaceParticleMarkerPrefix
    spatial_surface_private_particle_build_env = @(
        "RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PROFILE",
        "RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_SHADER",
        "RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PAYLOAD_DIR",
        "RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_MARKER_PREFIX"
    )
    spatial_surface_private_particle_profile_sha256 = $(if ([string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleProfilePath)) { "" } else { Get-FileSha256 -Path $resolvedPrivateSurfaceParticleProfilePath })
    spatial_surface_private_particle_shader_sha256 = $(if ([string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticleShader)) { "" } else { Get-FileSha256 -Path $resolvedPrivateSurfaceParticleShader })
    spatial_surface_private_particle_payload_hash = $(if ([string]::IsNullOrWhiteSpace($resolvedPrivateSurfaceParticlePayloadDir)) { "" } else { Get-DirectorySha256 -Path $resolvedPrivateSurfaceParticlePayloadDir })
    spatial_surface_private_particle_high_rate_policy = "no-particle-phase-graph-tracer-texture-rows-in-control-plane"
    native_surface_particle_layer = "PanelSceneObject-custom-mesh-forceSceneTexture-native-vulkan-wsi-surface-panel"
    native_surface_particle_layer_rendering = "native-vulkan-wsi-surface-panel-live-openxr-gpu-skinned-resident-rig-hand-anchor-particles-packed-stereo-left-right"
    native_surface_particle_layer_renderer_mode = "public-hand-anchor-proof"
    native_surface_particle_layer_private_renderer_mode = $(if ($privateSurfaceParticleStagedPayloadReady) { "main-draw-overlay-when-staged-payload-ready" } elseif ($privateSurfaceParticleInputsConfigured) { "metadata-only-when-private-inputs-configured" } else { "public-default" })
    native_surface_particle_layer_private_metadata_mode = "build-inputs-only"
    native_surface_particle_layer_private_metadata_active = $privateSurfaceParticleInputsConfigured
    native_surface_particle_layer_private_staged_payload_ready = $privateSurfaceParticleStagedPayloadReady
    native_surface_particle_layer_private_payload_active = $privateSurfaceParticleStagedPayloadReady
    native_surface_particle_layer_private_execution_ready = $privateSurfaceParticleStagedPayloadReady
    native_surface_particle_layer_private_draw_visible = $privateSurfaceParticleStagedPayloadReady
    native_surface_particle_layer_private_tracers_active = $false
    native_surface_particle_layer_jni_bridge = "SpatialCameraPanelActivity.nativeStartSurfaceParticleLayer"
    native_surface_particle_layer_stop_bridge = "SpatialCameraPanelActivity.nativeStopSurfaceParticleLayer"
    native_surface_particle_layer_parameter_bridge = "SpatialCameraPanelActivity.nativeUpdateSurfaceParticleParameters"
    native_surface_particle_layer_parameter_transport = "jni-live-queue"
    effect_control_parameter_bridge = "SpatialCameraPanelActivity.updateSurfaceParticleControls-to-nativeUpdateSurfaceParticleParameters"
    layer_control_panel = "spatial-private-layer-panel"
    surface_modes = @("real-hands", "gpu-replay-hands", "icosphere")
    driver_profile_high_rate_policy = "profile-metadata-and-bounded-scalars-only"
    native_surface_particle_layer_hotload_property = "debug.rustyquest.spatial_camera_panel.live_hand_depth_offset_meters"
    native_surface_particle_layer_live_hand_depth_offset_default_meters = 0.0
    native_surface_particle_layer_live_hand_scene_transform = "viewer-relative-openxr-to-spatial-sdk-panel-basis"
    native_surface_particle_layer_live_hand_scene_fallback_transform = "raw-openxr-local-floor-to-spatial-sdk-scene"
    native_surface_particle_layer_live_hand_scene_transform_source = "runtime-hotload-android-property"
    native_surface_particle_layer_live_hand_scene_transform_properties = @(
        "debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_x_m",
        "debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_y_m",
        "debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_z_m",
        "debug.rustyquest.spatial_camera_panel.live_hand_scene.yaw_degrees",
        "debug.rustyquest.spatial_camera_panel.live_hand_scene.horizontal_sign"
    )
    native_surface_particle_layer_live_hand_scene_offset_default_meters = "0.0;0.0;2.0"
    native_surface_particle_layer_live_hand_scene_yaw_default_degrees = 180.0
    native_surface_particle_layer_live_hand_scene_horizontal_sign_default = -1.0
    native_surface_particle_layer_target_distance_hotload_property = "debug.rustyquest.spatial_camera_panel.particle_layer.target_distance_meters"
    native_surface_particle_layer_target_distance_default_meters = 0.72
    native_surface_particle_layer_target_distance_range_meters = "0.20..1.50"
    camera_hwb_projection_quad_default_target_distance_meters = 2.0
    camera_hwb_projection_accepted_no_room_default = $true
    camera_hwb_projection_default_placement_mode = "viewer-pose-projection-locked-quad"
    camera_hwb_projection_right_secondary_behavior = "direct-video-recenter-existing-entity"
    camera_hwb_projection_right_primary_behavior = "open-generic-layer-control-panel"
    camera_hwb_projection_layer_control_panel_default_distance_meters = 1.0
    camera_hwb_projection_staged_asset_default_requested = $false
    camera_hwb_projection_quad_target_distance_control = "fixed-default"
    camera_hwb_projection_stereo_horizontal_offset_control = "left-controller-joystick-y"
    camera_hwb_projection_stereo_horizontal_offset_joystick_rate_property = "debug.rustyquest.spatial.camera_hwb_projection_probe.stereo_horizontal_offset.joystick.rate_uvps"
    camera_hwb_projection_stereo_horizontal_offset_default_rate_uv_per_second = 0.08
    camera_hwb_projection_stereo_horizontal_offset_default_uv = 0.046320
    camera_hwb_projection_stereo_horizontal_offset_default_source = "quest-live-headset-readback-20260628"
    camera_hwb_projection_stereo_horizontal_offset_range_uv = "-0.12..0.12"
    camera_hwb_projection_stereo_horizontal_offset_sign = "positive-increases-separation"
    camera_hwb_projection_quad_angular_coverage_policy = "preserve-current-plane-fov-by-scaling-width-and-height-with-distance"
    camera_hwb_projection_eye_space_target_rect_policy = "preserve-packed-eye-uv-target-rects-plus-live-opposed-horizontal-offset"
    camera_hwb_projection_native_panel_pose_authority = "camera-hwb-projection-plane"
    camera_hwb_projection_suppresses_particle_panel_pose_authority = $true
    camera_latency_diagnostic_module = "spatial-camera-latency-diagnostic-module"
    camera_latency_diagnostic_transport = "android-system-property-revision-last"
    camera_latency_diagnostic_tool = "tools/Set-SpatialCameraPanelCameraLatencyDiagnostic.ps1"
    camera_latency_diagnostic_properties = @(
        "debug.rustyquest.spatial.camera_latency.enabled",
        "debug.rustyquest.spatial.camera_latency.pose_mode",
        "debug.rustyquest.spatial.camera_latency.frame_wait_ms",
        "debug.rustyquest.spatial.camera_latency.summary_ms",
        "debug.rustyquest.spatial.camera_latency.frame_log",
        "debug.rustyquest.spatial.camera_latency.present_mode",
        "debug.rustyquest.spatial.camera_latency.image_count",
        "debug.rustyquest.spatial.camera_latency.capture_fps",
        "debug.rustyquest.spatial.camera_latency.camera_sync_mode",
        "debug.rustyquest.spatial.camera_latency.capture_processing",
        "debug.rustyquest.spatial.camera_latency.adoption_cadence",
        "debug.rustyquest.spatial.camera_latency.stereo_policy",
        "debug.rustyquest.spatial.camera_latency.isolation_mode",
        "debug.rustyquest.spatial.camera_latency.freeze_frame",
        "debug.rustyquest.spatial.camera_latency.reprojection_mode",
        "debug.rustyquest.spatial.camera_latency.assumed_capture_age_ms",
        "debug.rustyquest.spatial.camera_latency.reprojection_fov_degrees",
        "debug.rustyquest.spatial.camera_latency.reprojection_source_overscan_percent",
        "debug.rustyquest.spatial.camera_latency.reprojection_guard_band_mode",
        "debug.rustyquest.spatial.camera_latency.presentation_pose_mode",
        "debug.rustyquest.spatial.camera_latency.presentation_lead_ms",
        "debug.rustyquest.spatial.camera_latency.revision"
    )
    camera_latency_diagnostic_live_safe_fields = @(
        "pose-mode",
        "frame-wait-ms",
        "summary-ms",
        "frame-log",
        "camera-sync-mode",
        "adoption-cadence",
        "stereo-policy",
        "isolation-mode",
        "freeze-frame",
        "reprojection-mode",
        "assumed-capture-age-ms",
        "reprojection-fov-degrees",
        "reprojection-source-overscan-percent",
        "reprojection-guard-band-mode",
        "presentation-pose-mode",
        "presentation-lead-ms"
    )
    camera_latency_diagnostic_restart_required_fields = @("present-mode", "image-count", "capture-fps", "capture-processing")
    camera_latency_diagnostic_pose_modes = @("current-viewer", "frozen-world")
    camera_latency_diagnostic_camera_sync_modes = @("early-delete-ahb-retained", "hold-image-until-gpu-fence")
    camera_latency_diagnostic_capture_processing_modes = @("template-default", "noise-edge-off")
    camera_latency_diagnostic_isolation_modes = @("normal-composite", "opaque-camera-only", "fresh-frame-only-pulse")
    camera_latency_diagnostic_stereo_policies = @("independent-latest", "strict-timestamp-pair", "mono-duplicate-left")
    camera_latency_diagnostic_reprojection_modes = @(
        "off",
        "rotation-only-raw-layer",
        "rotation-only-sensor-timestamp",
        "rotation-only-sensor-timestamp-inverse",
        "rotation-only-sensor-timestamp-inverse-roll-free",
        "rotation-only-sensor-timestamp-inverse-yaw-only",
        "rotation-only-sensor-timestamp-camera-calibrated"
    )
    camera_latency_camera_calibration_source = "android-camera2-static-lens-pose-intrinsics"
    camera_latency_camera_calibration_transform = "camera_from_sensor-times-capture_from_current-times-sensor_from_camera"
    camera_latency_camera_calibration_scope = "independent-left-right-camera"
    camera_latency_projection_draw_scope = "one-draw-per-eye"
    camera_latency_projection_push_constant_bytes_per_eye = 96
    camera_latency_projection_invalid_uv_policy = "discard-to-underlying-carrier"
    camera_latency_projection_footprint_policies = @("fixed-target-rect-zoom-to-fill", "reduced-target-rect-preserve-angular-scale")
    camera_latency_projection_source_overscan_percent_range = "0..20"
    camera_latency_projection_source_overscan_policy = "central-source-crop-retains-real-camera-pixels"
    camera_latency_projection_guard_band_modes = @("zoom-to-fill", "reduced-footprint")
    camera_latency_projection_reduced_footprint_scale = "1-minus-two-times-source-overscan-uv"
    camera_latency_projection_source_coverage_exhaustion_policy = "discard-to-underlying-carrier"
    camera_latency_effect_stack_reprojection_ingress = "private-guide-pass0-prewarped-camera-color"
    camera_latency_effect_stack_guide_push_constant_bytes = 112
    camera_latency_diagnostic_capture_fps_requests = @("camera-default", "30", "45", "50", "60")
    camera_latency_diagnostic_adoption_cadences = @("every-available", "display-aligned-45")
    camera_latency_display_aligned_45_semantics = "adopt-latest-camera-image-every-two-presented-frames-at-90hz-camera-producer-remains-unchanged"
    camera_latency_diagnostic_timing_summary = "bounded-per-window-no-high-rate-payload"
    camera_latency_diagnostic_cadence_summary = "source-and-callback-intervals-display-hold-histograms-skipped-source-frames"
    camera_latency_diagnostic_present_age_semantics = "queue-present-call-not-photons"
    camera_latency_dynamic_camera_pose_metadata_used = $false
    camera_latency_image_timestamp_pose_association = "mode-selected-target-with-exact-interpolated-bracket-or-explicit-fallback"
    camera_latency_presentation_pose_modes = @("scene-tick-latest", "scene-extrapolated", "openxr-locate-views")
    camera_latency_presentation_lead_ms_range = "0..30"
    camera_latency_presentation_target_authority = "sidecar-estimate-not-compositor-predicted-display-time"
    camera_latency_openxr_frame_loop_authority = "spatial-sdk-only"
    camera_latency_sidecar_openxr_calls = @("xrLocateViews", "xrConvertTimespecTimeToTimeKHR")
    camera_latency_sidecar_openxr_frame_loop_calls = @()
    camera_latency_capture_result_metadata_callbacks = $false
    forced_replay_hand_source_mode = $(if ([string]::IsNullOrWhiteSpace($resolvedRecordedHandCaptureDir)) { "public-shape-fallback" } else { "external-recorded-capture-build-env" })
    forced_replay_hand_frame_limit = $resolvedRecordedHandFrameLimit
    native_surface_particle_layer_markers = @(
        "panel-entity-spawned",
        "surface-panel-ready",
        "started",
        "render-loop-ready",
        "surfaceLayerMode=native-hand-anchor-particles",
        "native-hand-anchor-mesh-components",
        "native-hand-anchor-left-hand-mesh-components",
        "native-hand-anchor-right-hand-mesh-components",
        "forcedReplayHands=true",
        "forcedReplayMeshVisible=false",
        "diagnosticParticlesVisible=false",
        "publicHandAnchorParticlesVisible=true",
        "handAnchorParticlesVisible=true",
        "gpuReplayHandsResident=true",
        "handAnchorParticlePath=resident-recorded-rig-gpu-skinned-mesh-coordinate-anchor-billboards",
        "handAnchorParticleCoordinateSource=live-openxr-world-joints-gpu-skinned-resident-mesh-with-forced-replay-fallback",
        "liveHandJointFrameSource=XR_EXT_hand_tracking",
        "liveHandJointGpuInputPath=recorded-compatible-compact-joint-pose-gpu-skinning",
        "liveHandCompactUploadEquivalent=true",
        "liveHandCompactFrameGate=native-equivalent-21-runtime-5-tip",
        "liveHandRuntimeJointPoseCount=",
        "liveHandTipLengthCount=",
        "liveHandJointPlacementMode=viewer-relative-openxr-to-spatial-sdk-panel-plane",
        "liveHandCoordinateTransform=viewer-relative-openxr-to-spatial-sdk-panel-basis",
        "liveHandViewPoseSource=xrLocateViews",
        "liveHandPanelBasisSource=Scene.getViewerPose-panel-plane",
        "liveHandSceneTransformSource=runtime-hotload-android-property",
        "liveHandSceneOffsetDefaultM=0.000;0.000;2.000",
        "liveHandSceneYawDefaultDegrees=180.000",
        "liveHandSceneHorizontalSignDefault=-1.000",
        "liveMeshSkinningPolicy=native-compact-frame-gated-full-weight-skinning",
        "liveMeshSurfacePolicy=keep_two_largest_components_drop_wrist_bridge_boundaries_v1",
        "liveMeshComponentRank0=hand-inside",
        "liveMeshComponentRank1=hand-back",
        "liveMeshComponentRank2=wrist-cap",
        "liveMeshWristCapPolicy=drop-component-rank-2",
        "liveMeshNormalFallbackPolicy=skinned-bind-normal-for-small-triangle-area",
        "liveMeshTriangleValidationAttempts=6",
        "liveHandCorrectPositionSizeProof=spatial-sdk-panel-plane-projection",
        "liveHandJointStatusY=pose-valid",
        "liveHandSkinningValidityPolicy=native-compact-frame-gate-trust-all-weights",
        "liveHandDepthOffsetParameterSource=runtime-hotload-android-property",
        "liveHandDepthOffsetProperty=debug.rustyquest.spatial_camera_panel.live_hand_depth_offset_meters",
        "particleDiagnosticModeProperty=debug.rustyquest.spatial_camera_panel.particle_layer.diagnostic_mode",
        "particleDiagnosticModeName=",
        "particleLayerTargetDistanceParameterSource=runtime-hotload-android-property",
        "particleLayerTargetDistanceProperty=debug.rustyquest.spatial_camera_panel.particle_layer.target_distance_meters",
        "privatePayloadActive=false",
        "driverProfileDynamicsActive=true",
        "driverProfileId=profile-b",
        "driverProfileSchemaId=rusty.quest.spatial_camera_panel.driver_profile.profile-b.v1",
        "driverBaseHz=0.88",
        "driverMix01=0.0",
        "properStereoHandAnchorParticles=true",
        "replayStereoProjection=per-eye-spatial-sdk-panel-plane-ray-intersection",
        "computeParticleStateBuffer=true",
        "computeShaderDispatchReady=true",
        "computeParameterBridge=true",
        "native-surface-compute-stereo-proof=true",
        "sideBySideStereoProof=true",
        "stereoMode=LeftRight",
        "cameraFacingParticleSurface=true",
        "projectionLockedParticleSurface=true",
        "placementMode=viewer-pose-projection-locked-quad",
        "targetProjectionSpace=spatial-sdk-panel-plane-perspective-projection",
        "projectionContentMappingMode=spatial-world-to-panel-plane-left-right",
        "first-frame-presented"
    )
    native_surface_particle_layer_shape = [ordered]@{
        width_px = 2048
        per_eye_width_px = 1024
        height_px = 1024
        stereo_mode = "StereoMode.LeftRight"
        packed_stereo_layout = "left-right"
        particles = 2048
        width_meters = 1.44
        height_meters = 1.44
        target_distance_meters = 0.72
        x_meters = 0.0
        y_meters = 1.22
        z_meters = -0.72
        placement_mode = "viewer-pose-projection-locked-quad"
        placement_authority = "spatial-sdk-viewer-pose-scene-tick"
        target_coordinate_space = "spatial-sdk-surface-panel-eye-uv"
        target_projection_space = "spatial-sdk-panel-plane-perspective-projection"
        target_fov_tangents = "panel-plane-derived"
        projection_content_mapping_mode = "world-to-spatial-sdk-panel-plane-left-right"
        left_target_surface_uv_rect = "0.0;0.0;1.0;1.0"
        right_target_surface_uv_rect = "0.0;0.0;1.0;1.0"
        view_origin_meters = "0.0;0.0;2.0"
        view_origin_yaw_degrees = 180.0
    }
    panel_registration_id = "spatial_private_layer_panel"
    particle_surface_panel_registration_id = "spatial_camera_surface_panel"
    spatial_panel_mode = "private-layer-controls-open-or-render-view"
    spatial_panel_mode_transition = "right-controller-primary-toggles-private-layer-panel"
    spatial_panel_mode_renderer_continuity = "native-vulkan-surface-particle-layer-kept-running"
    spatial_panel_focus_pose_meters = "0.0;1.1;0.475"
    spatial_panel_surface_target_activation_action = "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_SURFACE_TARGET"
    spatial_panel_ui_action = "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_UI_COMMAND"
    spatial_panel_ui_action_wrapper = "tools/Invoke-SpatialCameraPanelAndroidUiAction.ps1"
    spatial_panel_ui_actions = @(
        "panel-open",
        "panel-close",
        "private-layer-panel-open",
        "private-layer-panel-close",
        "private-layer-select",
        "private-layer-zone-off",
        "private-layer-zone-native-buffer",
        "private-layer-zone-linear-buffer",
        "private-layer-zone-organic-buffer",
        "private-layer-zone-full-stretch",
        "private-layer-zone-component-blend-test",
        "private-layer-zone-region-blend-test",
        "private-layer-zone-video-underlay-blend-test",
        "projection-panel-on",
        "projection-panel-off",
        "video-previous",
        "video-next",
        "video-select",
        "video-recenter",
        "video-world-anchored",
        "video-head-fixed-border",
        "video-playback-off",
        "video-playback-on",
        "background-black",
        "background-passthrough",
        "background-lut-passthrough",
        "choose-shared-media-folder",
        "refresh-shared-media-library",
        "particle-controls",
        "particle-panel-distance",
        "particle-panel-view-yaw",
        "particle-recenter",
        "particle-alias-control",
        "surface-target-activate"
    )
    spatial_panel_debug_controller_reopen = $(if ($lockedFinalPresentationEnabled) { "disabled-by-locked-presentation-build" } else { "right-controller-primary-button-SpatialSDK-Controller-ButtonA-plus-Android-KeyEvent-and-motion-fallback-toggles-panel-open-close" })
    spatial_panel_headlock_mode = "viewer-relative-private-layer-controls"
    spatial_panel_headlock_default_pose_meters = "0.0;0.0;1.40"
    spatial_panel_headlock_default_scale = 0.65
    spatial_private_layer_panel_render_mode = "spatial-sdk-layer-world-space-high-z"
    spatial_private_layer_panel_pose_mode = "initial-headset-facing-world-space-then-stored-placement-unless-grabbed"
    spatial_private_layer_panel_movement_authority = "app-stored-placement-with-spatial-sdk-grabbable-pivot-y-and-left-stick-y-distance"
    spatial_private_layer_panel_input_buttons = "trigger-l+trigger-r-select; controller-squeeze-grab; right-primary-select-disabled"
    spatial_private_layer_panel_compose_drag_movement = $false
    spatial_private_layer_panel_default_pose_meters = "0.0;0.0;1.00"
    spatial_private_layer_panel_projection_input_order = "manual-custom-mesh-projection-noninteractive-private-layer-panel-layer-input"
    spatial_panel_headlock_hotload_tool = "tools/Set-SpatialCameraPanelHeadlock.ps1"
    spatial_panel_headlock_hotload_properties = @(
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.enabled",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.offset_x_m",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.offset_y_m",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.distance_meters",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.width_meters",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.height_meters",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.scale",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.enabled",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.translate_rate_mps",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.distance_rate_mps",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.scale_rate_per_second"
    )
    spatial_panel_headlock_joystick_controls = "android-generic-motion-left-stick-y-workflow-panel-distance-private-layer-panel-distance-right-stick-y-projection-scale-disabled-while-private-panel-open-right-stick-x-ignored-right-stick-side-flick-panel-move-disabled"
    spatial_camera_projection_distance_controls = "fixed-2m-default; no joystick distance control"
    spatial_camera_projection_scale_controls = $(if ($lockedFinalPresentationEnabled) { "disabled-forced-scale-1.0" } else { "android-right-stick-y; spatial-sdk-avatar-body-right-thumb-up-down; native-openxr-right-thumbstick-y diagnostic; panel-control" })
    spatial_camera_projection_stereo_offset_controls = "disabled-default-locked; left-stick-y-controls-panel-distance-private-free-transform"
    spatial_camera_projection_distance_vr_input_system_property = "debug.rustyquest.spatial_camera_panel.vr_input_system"
    spatial_panel_headlock_tuning_file = "files/spatial_camera_panel_headlock_tuning.json"
    panel_shape_meters = [ordered]@{
        width = 1.20
        height = 1.254
    }
    panel_display = [ordered]@{
        option = "DpPerMeterDisplayOptions"
        dp_per_meter = 720
    }
    panel_transform_runtime_controls = @("Transform(Pose(Vector3, Quaternion))", "Scale(Vector3)", "PanelDimensions(Vector2)", "Visible(privateLayerPanelPlacement.visible)")
    diagnostic_backdrop = "disabled-vulkan-carrier-is-user-facing-surface"
    panel_content_probe = "sample-quaternion-opaque-yellow-background-teal-banner-orange-button"
    high_rate_json_payload = $false
    hand_rendering_expected = $false
    controller_rendering_expected = $false
    spatial_pointer_input_expected = (-not $lockedFinalPresentationEnabled)
    apk_path = $apkOut
    apk_sha256 = $sha256
    signing_keystore = $(if ($keystoreWasExplicit) { "explicit-local-binding" } else { "gradle-debug-default-nonshared-dev" })
    artifact_signer_sha256 = $artifactSignerSha256
    expected_signer_sha256 = $normalizedExpectedSignerSha256
    signer_path_alias_password_recorded = $false
}
$manifestPath = Join-Path $OutDir "build-manifest.json"
[void](Set-TextFileIfChanged -Path $manifestPath -Value ($manifest | ConvertTo-Json -Depth 12))

$runCapsule = [ordered]@{
    schema = "rusty.quest.apk_run_capsule.v1"
    capsule_id = "spatial-$($resolvedAppId.Replace('.', '-'))-$($buildInputFingerprint.Substring(0, 12))"
    app_id = $resolvedAppId
    app_lane = "spatial-camera-panel-android"
    source = [ordered]@{
        repository = [string]$repoRoot; commit = $sourceHead; tree = $sourceTree; tracked_worktree_clean = $sourceTrackedWorktreeClean; worktree_overlay_sha256 = $sourceWorktreeOverlaySha256
        composition_fingerprint = [string]$sourceComposition.fingerprint; packages = @($sourceComposition.packages); dependencies = $sourceDependencies
    }
    build_lock = [ordered]@{
        path = $buildInputLockPath; sha256 = Get-FileSha256 -Path $buildInputLockPath; resolution_fingerprint = $buildInputFingerprint
    }
    build_cache = [ordered]@{
        identities = [ordered]@{ path = $cacheIdentityReceiptPath; sha256 = Get-FileSha256 -Path $cacheIdentityReceiptPath }
        phases = [ordered]@{ path = $phaseReceiptPath; sha256 = Get-FileSha256 -Path $phaseReceiptPath }
        paths_recorded = $false
    }
    apk_inspection = [ordered]@{ path = $apkInspectionPath; sha256 = Get-FileSha256 -Path $apkInspectionPath }
    build_manifest = [ordered]@{ path = $manifestPath; sha256 = Get-FileSha256 -Path $manifestPath }
    apk = [ordered]@{ path = $apkOut; sha256 = $sha256 }
    runtime_profile = $null
    property_manifest = [ordered]@{ path = $propertyManifestPath; sha256 = Get-FileSha256 -Path $propertyManifestPath; scope = "complete-manifest" }
    android = [ordered]@{
        package_name = $resolvedAppId
        activity = "$resolvedAppId/io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraPanelActivity"
    }
    cleanup = [ordered]@{
        policy = "always-force-stop-and-restore-exact-property-snapshot"
        serial_exclusive_mutex = $true
        restore_on_failure = $true
    }
}
$runCapsulePath = Join-Path $OutDir "run-capsule.json"
[void](Set-TextFileIfChanged -Path $runCapsulePath -Value ($runCapsule | ConvertTo-Json -Depth 16))

if ($buildLaneMutexOwned -and $null -ne $buildLaneMutex) {
    $buildLaneMutex.ReleaseMutex()
    $buildLaneMutexOwned = $false
}
if ($null -ne $buildLaneMutex) {
    $buildLaneMutex.Dispose()
    $buildLaneMutex = $null
}
Write-Host "BUILD_CACHE serialized_lane_released=true"
Write-Output $runCapsulePath
Write-Output $apkOut
