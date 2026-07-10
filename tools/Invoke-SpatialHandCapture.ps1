param(
    [ValidateSet("Prepare", "Start", "Stop", "Status", "Pull", "Inspect", "PullAndInspect", "ClearControl")]
    [string]$Action = "Status",
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.spatial_hand_lab",
    [string]$SessionId = "",
    [int]$MaxFrames = 900,
    [int]$SamplePeriodFrames = 1,
    [string]$OutDir = "",
    [string]$CaptureDir = "",
    [bool]$ShowAvatarHands = $true,
    [switch]$EnableAvatarHandProbe,
    [switch]$DisableAvatarHandProbe,
    [int]$AvatarHandProbeSamplePeriodFrames = 30,
    [int]$AvatarHandProbeDetailLimit = 16,
    [switch]$EnableBillboardWireframe,
    [switch]$DisableBillboardWireframe,
    [switch]$DisableEcsParticleHands,
    [ValidateSet("spatial-sdk-anchor-flock", "openxr-live-custom-mesh")]
    [string]$BillboardParticleSource = "openxr-live-custom-mesh",
    [int]$BillboardCount = 2048,
    [double]$BillboardWireframeWidthMeters = 0.0035,
    [ValidateSet("spatial-sdk-joint-proxy", "openxr-fb-mesh", "custom-mesh", "avatar-system-public-mesh-probe")]
    [string]$BillboardWireframeSource = "spatial-sdk-joint-proxy",
    [switch]$EnableAlignmentDiagnostic,
    [switch]$DisableAlignmentDiagnostic,
    [ValidateSet("viewer-world-basis-registration", "mirror-x-origin-registration")]
    [string]$AlignmentMappingProfile = "mirror-x-origin-registration",
    [int]$AlignmentSamplePeriodFrames = 15,
    [double]$AlignmentJointMarkerMeters = 0.017,
    [double]$AlignmentLineWidthMeters = 0.0040,
    [switch]$EnableViewerMarkers,
    [switch]$DisableNativeSurfaceParticleLayer
)

$ErrorActionPreference = "Stop"
$AvatarHandProbeEnabled = [bool]$EnableAvatarHandProbe -or (-not [bool]$DisableAvatarHandProbe)
$BillboardWireframeEnabled = [bool]$EnableBillboardWireframe -and (-not [bool]$DisableBillboardWireframe)
$EcsParticleHandsEnabled = -not [bool]$DisableEcsParticleHands
$AlignmentDiagnosticEnabled = ([bool]$EnableAlignmentDiagnostic -or (-not [bool]$DisableAlignmentDiagnostic))

$ControlSchema = "rusty.quest.spatial.hand_capture_control.v1"
$RemoteFilesRoot = "/sdcard/Android/data/$PackageName/files"
$RemoteCaptureRoot = "$RemoteFilesRoot/spatial-hand-captures"
$RemoteControlPath = "$RemoteFilesRoot/spatial-hand-capture-control.json"

function Resolve-ToolPath {
    param([Parameter(Mandatory=$true)][string]$Name, [string]$Value, [string]$DefaultPath)
    if (-not [string]::IsNullOrWhiteSpace($Value)) {
        if (Test-Path -LiteralPath $Value) { return (Resolve-Path -LiteralPath $Value).Path }
        $command = Get-Command $Value -ErrorAction SilentlyContinue
        if ($null -ne $command) { return $command.Source }
        throw "$Name not found: $Value"
    }
    if (-not [string]::IsNullOrWhiteSpace($DefaultPath) -and (Test-Path -LiteralPath $DefaultPath)) {
        return (Resolve-Path -LiteralPath $DefaultPath).Path
    }
    $fallback = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $fallback) { throw "$Name not found. Pass -$Name or set the matching environment variable." }
    return $fallback.Source
}

function Resolve-AdbServerPortArgument {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $parsed = 0
    if (-not [int]::TryParse($Value, [ref]$parsed) -or $parsed -lt 1 -or $parsed -gt 65535) {
        throw "ADB server port must be an integer from 1 to 65535: $Value"
    }
    return $parsed.ToString()
}

function Invoke-AdbCommand {
    param([Parameter(Mandatory=$true)][string]$Name, [Parameter(Mandatory=$true)][string[]]$Arguments, [switch]$AllowFailure)
    $adbArgs = @()
    if ($null -ne $script:ResolvedAdbServerPort) { $adbArgs += @("-P", $script:ResolvedAdbServerPort) }
    $adbArgs += @("-s", $script:Serial)
    $adbArgs += $Arguments
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $script:ResolvedAdb @adbArgs 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $result = [ordered]@{ name = $Name; arguments = $Arguments; exit_code = $exitCode; output = ($output -join "`n") }
    if ($exitCode -ne 0 -and -not $AllowFailure) { throw "$Name failed with exit code $exitCode`n$($result.output)" }
    return $result
}

function Assert-AdbReady {
    if ([string]::IsNullOrWhiteSpace($script:Serial)) {
        throw "Serial is required for $Action. Pass -Serial or set RUSTY_QUEST_SERIAL."
    }
    $script:ResolvedAdb = Resolve-ToolPath -Name "adb" -Value $Adb -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
    $script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument $AdbServerPort
}

function New-DefaultSessionId {
    return "spatial-hands-{0}" -f (Get-Date -Format "yyyyMMdd-HHmmss")
}

function Set-SpatialProperty {
    param([Parameter(Mandatory=$true)][string]$Name, [Parameter(Mandatory=$true)][string]$Value)
    Invoke-AdbCommand -Name "setprop $Name" -Arguments @("shell", "setprop", $Name, $Value) | Out-Null
}

function Write-ControlFile {
    param([Parameter(Mandatory=$true)][bool]$Enabled, [string]$CaptureSessionId)
    if ([string]::IsNullOrWhiteSpace($CaptureSessionId)) { $CaptureSessionId = New-DefaultSessionId }
    $control = [ordered]@{
        schema = $ControlSchema
        enabled = $Enabled
        session_id = $CaptureSessionId
        max_frames = [Math]::Max(1, [Math]::Min(36000, $MaxFrames))
        sample_period_frames = [Math]::Max(1, [Math]::Min(600, $SamplePeriodFrames))
        source_kind = "spatial-sdk-avatarbody-transform-plus-openxr-joint-bridge"
        spatial_public_mesh_topology_available = $false
        built_in_visual_provider = "AvatarSystem"
        avatar_hand_investigation_probe_enabled = [bool]$AvatarHandProbeEnabled
        avatar_hand_investigation_schema = "rusty.quest.spatial.avatar_hand_investigation.v1"
        avatar_hand_investigation_public_api_probe = "ecs-avatarbody-controller-mesh-material"
        avatar_hand_investigation_wireframe_fallback_provider = "spatial-hand-billboard-flock-trianglemesh"
        ecs_particle_hands_enabled = [bool]$EcsParticleHandsEnabled
        ecs_particle_hands_source = $BillboardParticleSource
        ecs_particle_hands_skinning = "cpu-linear-blend-from-mapped-openxr-joints"
        ecs_particle_hands_coordinate_anchor = "triangle-index-plus-barycentric"
        ecs_particle_hands_row_order = "openxr-left-right"
        ecs_particle_hands_mesh_pairing = "asset-handedness"
        ecs_particle_hands_orientation_correction = "none"
        ecs_particle_hands_world_anchor_correction = $false
        app_owned_wireframe_visual_enabled = [bool]$BillboardWireframeEnabled
        app_owned_wireframe_visual_provider = "spatial-hand-billboard-flock-trianglemesh"
        app_owned_wireframe_requested_source = $BillboardWireframeSource
        app_owned_wireframe_resolved_source = $(if ($BillboardParticleSource -eq "openxr-live-custom-mesh") { "custom-mesh-surface-particles" } else { "spatial-sdk-joint-proxy" })
        openxr_fb_mesh_wireframe_supported = $false
        custom_hand_mesh_wireframe_supported = $false
        spatial_openxr_hand_alignment_enabled = [bool]$AlignmentDiagnosticEnabled
        spatial_openxr_hand_alignment_schema = "rusty.quest.spatial.openxr_hand_alignment.v1"
        spatial_openxr_hand_alignment_provider = "openxr-joint-bridge-plus-spatial-avatarbody-anchors"
        spatial_openxr_hand_alignment_mapping_profile = $AlignmentMappingProfile
        spatial_openxr_hand_alignment_rollback_profile = "viewer-world-basis-registration"
        spatial_openxr_hand_alignment_accepted_profile = "mirror-x-origin-registration"
        spatial_openxr_hand_alignment_viewer_markers_enabled = [bool]$EnableViewerMarkers
    }
    $tempPath = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-quest-spatial-hand-capture-{0}.json" -f ([Guid]::NewGuid().ToString("N")))
    [System.IO.File]::WriteAllText($tempPath, ($control | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
    try {
        Invoke-AdbCommand -Name "mkdir remote app files" -Arguments @("shell", "mkdir", "-p", $RemoteFilesRoot) | Out-Null
        Invoke-AdbCommand -Name "push spatial hand capture control" -Arguments @("push", $tempPath, $RemoteControlPath) | Out-Null
    } finally {
        Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
    }
    return $CaptureSessionId
}

function Resolve-LatestRemoteSession {
    $result = Invoke-AdbCommand -Name "list spatial hand captures" -Arguments @("shell", "ls", "-1t", $RemoteCaptureRoot) -AllowFailure
    if ($result.exit_code -ne 0) { throw "Could not list remote capture sessions at $RemoteCaptureRoot`n$($result.output)" }
    $names = $result.output -split "[`r`n]+" | ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_ -notmatch "No such file" }
    if ($names.Count -lt 1) { throw "No remote spatial hand capture sessions found at $RemoteCaptureRoot" }
    return $names[0]
}

function Pull-Capture {
    param([string]$CaptureSessionId)
    if ([string]::IsNullOrWhiteSpace($CaptureSessionId)) { $CaptureSessionId = Resolve-LatestRemoteSession }
    if ([string]::IsNullOrWhiteSpace($OutDir)) {
        $repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
        $script:OutDir = Join-Path $repoRoot "target\spatial-hand-captures"
    }
    New-Item -ItemType Directory -Force -Path $script:OutDir | Out-Null
    $localSessionDir = Join-Path $script:OutDir $CaptureSessionId
    Invoke-AdbCommand -Name "pull spatial hand capture" -Arguments @("pull", "$RemoteCaptureRoot/$CaptureSessionId", $localSessionDir) | Out-Null
    return (Resolve-Path -LiteralPath $localSessionDir).Path
}

function Count-JsonlRows {
    param([Parameter(Mandatory=$true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return 0 }
    return @((Get-Content -LiteralPath $Path) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
}

function Inspect-Capture {
    param([Parameter(Mandatory=$true)][string]$LocalCaptureDir)
    $resolved = Resolve-Path -LiteralPath $LocalCaptureDir
    $manifestPath = Join-Path $resolved.Path "capture.manifest.json"
    if (-not (Test-Path -LiteralPath $manifestPath)) { throw "Capture manifest missing: $manifestPath" }
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $summary = [ordered]@{
        schema = "rusty.quest.spatial.hand_capture_inspection.v1"
        capture_dir = $resolved.Path
        manifest_schema = [string]$manifest.schema
        capture_id = [string]$manifest.capture_id
        provider = [string]$manifest.provider
        source_kind = [string]$manifest.source_kind
        runtime_provider = [string]$manifest.runtime_provider
        mesh_provider = [string]$manifest.mesh_provider
        spatial_public_mesh_topology_available = [bool]$manifest.spatial_public_mesh_topology_available
        left_clip_frame_count = Count-JsonlRows -Path (Join-Path $resolved.Path "left.clip.jsonl")
        right_clip_frame_count = Count-JsonlRows -Path (Join-Path $resolved.Path "right.clip.jsonl")
        spatial_pose_frame_count = Count-JsonlRows -Path (Join-Path $resolved.Path "spatial_poses.jsonl")
        ok = ((Count-JsonlRows -Path (Join-Path $resolved.Path "spatial_poses.jsonl")) -gt 0)
    }
    $summaryPath = Join-Path $resolved.Path "spatial-hand-capture-inspection.json"
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
    Write-Output ($summary | ConvertTo-Json -Depth 8)
}

if ($Action -ne "Inspect" -or [string]::IsNullOrWhiteSpace($CaptureDir)) {
    Assert-AdbReady
}

switch ($Action) {
    "Prepare" {
        Set-SpatialProperty "debug.rustyquest.spatial.avatar_hands.visible" ($(if ($ShowAvatarHands) { "true" } else { "false" }))
        Set-SpatialProperty "debug.rustyquest.spatial.avatar_hand_probe.enabled" ($(if ($AvatarHandProbeEnabled) { "true" } else { "false" }))
        Set-SpatialProperty "debug.rustyquest.spatial.avatar_hand_probe.sample_period_frames" ([Math]::Max(1, [Math]::Min(600, $AvatarHandProbeSamplePeriodFrames)).ToString())
        Set-SpatialProperty "debug.rustyquest.spatial.avatar_hand_probe.detail_limit" ([Math]::Max(0, [Math]::Min(64, $AvatarHandProbeDetailLimit)).ToString())
        Set-SpatialProperty "debug.rustyquest.spatial.avatar_hand_probe.associated_files" "true"
        Set-SpatialProperty "debug.rustyquest.spatial.hand_billboard_flock.enabled" ($(if ($EcsParticleHandsEnabled) { "true" } else { "false" }))
        Set-SpatialProperty "debug.rustyquest.spatial.hand_billboard_flock.source" $BillboardParticleSource
        Set-SpatialProperty "debug.rustyquest.spatial.hand_billboard_flock.carrier" "batched-scene-mesh"
        Set-SpatialProperty "debug.rustyquest.spatial.hand_billboard_flock.visual_mode" ($(if ($BillboardWireframeEnabled) { "wireframe-edges" } else { "filled-billboards" }))
        Set-SpatialProperty "debug.rustyquest.spatial.hand_billboard_flock.wireframe.source" $BillboardWireframeSource
        Set-SpatialProperty "debug.rustyquest.spatial.hand_billboard_flock.count" ([Math]::Max(1, [Math]::Min(2048, $BillboardCount)).ToString())
        Set-SpatialProperty "debug.rustyquest.spatial.hand_billboard_flock.render.depth_test" "always"
        Set-SpatialProperty "debug.rustyquest.spatial.hand_billboard_flock.wireframe.width_m" ("{0:0.####}" -f ([Math]::Max(0.00075, [Math]::Min(0.020, $BillboardWireframeWidthMeters))))
        Set-SpatialProperty "debug.rustyquest.spatial.hand_alignment.enabled" ($(if ($AlignmentDiagnosticEnabled) { "true" } else { "false" }))
        Set-SpatialProperty "debug.rustyquest.spatial.hand_alignment.render" "true"
        Set-SpatialProperty "debug.rustyquest.spatial.hand_alignment.mapping_profile" $AlignmentMappingProfile
        Set-SpatialProperty "debug.rustyquest.spatial.hand_alignment.viewer_markers.enabled" ($(if ($EnableViewerMarkers) { "true" } else { "false" }))
        Set-SpatialProperty "debug.rustyquest.spatial.hand_alignment.sample_period_frames" ([Math]::Max(1, [Math]::Min(600, $AlignmentSamplePeriodFrames)).ToString())
        Set-SpatialProperty "debug.rustyquest.spatial.hand_alignment.joint_marker_m" ("{0:0.####}" -f ([Math]::Max(0.004, [Math]::Min(0.080, $AlignmentJointMarkerMeters))))
        Set-SpatialProperty "debug.rustyquest.spatial.hand_alignment.line_width_m" ("{0:0.####}" -f ([Math]::Max(0.00075, [Math]::Min(0.030, $AlignmentLineWidthMeters))))
        if ($DisableNativeSurfaceParticleLayer -or $AlignmentDiagnosticEnabled) {
            Set-SpatialProperty "debug.rustyquest.spatial.native_surface_particle_layer.enabled" "false"
        }
        $preparedSession = if ([string]::IsNullOrWhiteSpace($SessionId)) { New-DefaultSessionId } else { $SessionId }
        Write-ControlFile -Enabled $false -CaptureSessionId $preparedSession | Out-Null
        Write-Host "Prepared Spatial hand capture/probe for next app launch. session_hint=$preparedSession avatarHandProbe=$AvatarHandProbeEnabled ecsParticleHands=$EcsParticleHandsEnabled particleSource=$BillboardParticleSource wireframe=$BillboardWireframeEnabled alignmentDiagnostic=$AlignmentDiagnosticEnabled alignmentMappingProfile=$AlignmentMappingProfile viewerMarkers=$([bool]$EnableViewerMarkers) controlFile=$RemoteControlPath"
    }
    "Start" {
        $startedSession = Write-ControlFile -Enabled $true -CaptureSessionId $SessionId
        Write-Host "Started Spatial hand capture control. session=$startedSession remoteDir=$RemoteCaptureRoot/$startedSession"
    }
    "Stop" {
        $stoppedSession = Write-ControlFile -Enabled $false -CaptureSessionId $SessionId
        Write-Host "Stopped Spatial hand capture control. session_hint=$stoppedSession"
    }
    "Status" {
        $control = Invoke-AdbCommand -Name "read spatial hand capture control" -Arguments @("shell", "cat", $RemoteControlPath) -AllowFailure
        $sessions = Invoke-AdbCommand -Name "list spatial hand capture sessions" -Arguments @("shell", "ls", "-1t", $RemoteCaptureRoot) -AllowFailure
        [ordered]@{
            package_name = $PackageName
            control_path = $RemoteControlPath
            capture_root = $RemoteCaptureRoot
            control_exit_code = $control.exit_code
            control = $control.output
            sessions_exit_code = $sessions.exit_code
            sessions = $sessions.output
        } | ConvertTo-Json -Depth 4
    }
    "Pull" {
        $pulled = Pull-Capture -CaptureSessionId $SessionId
        Write-Host "Pulled Spatial hand capture to $pulled"
    }
    "Inspect" {
        if ([string]::IsNullOrWhiteSpace($CaptureDir)) { throw "Inspect requires -CaptureDir." }
        Inspect-Capture -LocalCaptureDir $CaptureDir
    }
    "PullAndInspect" {
        $pulled = Pull-Capture -CaptureSessionId $SessionId
        Inspect-Capture -LocalCaptureDir $pulled
    }
    "ClearControl" {
        Invoke-AdbCommand -Name "clear spatial hand capture control" -Arguments @("shell", "rm", "-f", $RemoteControlPath) | Out-Null
        Write-Host "Cleared Spatial hand capture control file $RemoteControlPath"
    }
}
