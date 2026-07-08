param(
    [ValidateSet("Prepare", "Start", "Stop", "Status", "Pull", "Inspect", "PullAndInspect", "ClearControl")]
    [string]$Action = "Status",
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.native_renderer",
    [string]$SessionId = "",
    [int]$MaxFrames = 900,
    [int]$SamplePeriodFrames = 1,
    [int]$ValidationSamplePeriodFrames = 6,
    [string]$OutDir = "",
    [string]$CaptureDir = "",
    [ValidateSet("live-meta-openxr-hand-tracking", "recorded-replay")]
    [string]$HandInputSource = "live-meta-openxr-hand-tracking",
    [ValidateSet("unity-basic-reference", "mint-rim", "flat-gray")]
    [string]$MaterialProfile = "unity-basic-reference",
    [double]$MaterialAlpha = 0.74,
    [double]$MaterialRimStrength = 0.20,
    [switch]$Wireframe,
    [switch]$DisableWireframe,
    [double]$WireframeWidthPx = 1.35,
    [ValidateSet("auto", "openxr-fb-mesh", "custom-mesh")]
    [string]$VisualMeshSource = "openxr-fb-mesh",
    [switch]$DisableSdfVisual
)

$ErrorActionPreference = "Stop"
$WireframeEnabled = [bool]$Wireframe -or (-not [bool]$DisableWireframe)

$ControlSchema = "rusty.quest.native_renderer.hand_mesh_capture_control.v1"
$RemoteFilesRoot = "/sdcard/Android/data/$PackageName/files"
$RemoteCaptureRoot = "$RemoteFilesRoot/hand-mesh-captures"
$RemoteControlPath = "$RemoteFilesRoot/hand-mesh-capture-control.json"

function Resolve-ToolPath {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [string]$Value,
        [string]$DefaultPath
    )
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
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [switch]$AllowFailure
    )
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
    $result = [ordered]@{
        name = $Name
        arguments = $Arguments
        exit_code = $exitCode
        output = ($output -join "`n")
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "$Name failed with exit code $exitCode`n$($result.output)"
    }
    return $result
}

function Assert-AdbReady {
    if ([string]::IsNullOrWhiteSpace($script:Serial)) {
        throw "Serial is required for $Action. Pass -Serial or set RUSTY_QUEST_SERIAL."
    }
    $script:ResolvedAdb = Resolve-ToolPath `
        -Name "adb" `
        -Value $Adb `
        -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
    $script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument $AdbServerPort
}

function New-DefaultSessionId {
    return "hand-mesh-{0}" -f (Get-Date -Format "yyyyMMdd-HHmmss")
}

function Write-ControlFile {
    param(
        [Parameter(Mandatory=$true)][bool]$Enabled,
        [string]$CaptureSessionId
    )
    if ([string]::IsNullOrWhiteSpace($CaptureSessionId)) { $CaptureSessionId = New-DefaultSessionId }
    $control = [ordered]@{
        schema = $ControlSchema
        enabled = $Enabled
        session_id = $CaptureSessionId
        max_frames = [Math]::Max(1, [Math]::Min(36000, $MaxFrames))
        sample_period_frames = [Math]::Max(1, [Math]::Min(600, $SamplePeriodFrames))
        validation_sample_period_frames = [Math]::Max(1, [Math]::Min(600, $ValidationSamplePeriodFrames))
        replay_modes = @("recorded-mesh-validation-frames", "recorded-joints-skin-live")
        required_extensions = @("XR_EXT_hand_tracking", "XR_FB_hand_tracking_mesh")
        hand_input_source = $HandInputSource
        hand_visual_mesh_source = $VisualMeshSource
        hand_material = [ordered]@{
            profile = $MaterialProfile
            alpha = $MaterialAlpha
            rim_strength = $MaterialRimStrength
            wireframe_enabled = [bool]$WireframeEnabled
            wireframe_width_px = [Math]::Max(0.50, [Math]::Min(4.00, $WireframeWidthPx))
        }
    }
    $tempPath = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-quest-hand-mesh-capture-{0}.json" -f ([Guid]::NewGuid().ToString("N")))
    [System.IO.File]::WriteAllText(
        $tempPath,
        ($control | ConvertTo-Json -Depth 8),
        (New-Object System.Text.UTF8Encoding($false))
    )
    try {
        Invoke-AdbCommand -Name "mkdir remote app files" -Arguments @("shell", "mkdir", "-p", $RemoteFilesRoot) | Out-Null
        Invoke-AdbCommand -Name "push hand mesh capture control" -Arguments @("push", $tempPath, $RemoteControlPath) | Out-Null
    } finally {
        Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
    }
    return $CaptureSessionId
}

function Set-NativeProperty {
    param([Parameter(Mandatory=$true)][string]$Name, [Parameter(Mandatory=$true)][string]$Value)
    Invoke-AdbCommand -Name "setprop $Name" -Arguments @("shell", "setprop", $Name, $Value) | Out-Null
}

function Prepare-MaterialLiveRun {
    Set-NativeProperty "debug.rustyquest.native_renderer.replay.visual_proof.enabled" "false"
    Set-NativeProperty "debug.rustyquest.native_renderer.hand_mesh.input.source" $HandInputSource
    Set-NativeProperty "debug.rustyquest.native_renderer.hand_mesh.real_hands.visible" "true"
    Set-NativeProperty "debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled" "false"
    Set-NativeProperty "debug.rustyquest.native_renderer.hand_anchor_particles.enabled" "false"
    Set-NativeProperty "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled" "true"
    Set-NativeProperty "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.alpha" "1.0"
    Set-NativeProperty "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.offset_uv" "0.0,-0.10"
    Set-NativeProperty "debug.rustyquest.native_renderer.hand_mesh.visual.material.profile" $MaterialProfile
    Set-NativeProperty "debug.rustyquest.native_renderer.hand_mesh.visual.material.alpha" ("{0:0.###}" -f $MaterialAlpha)
    Set-NativeProperty "debug.rustyquest.native_renderer.hand_mesh.visual.material.rim_strength" ("{0:0.###}" -f $MaterialRimStrength)
    Set-NativeProperty "debug.rustyquest.native_renderer.hand_mesh.visual.mesh_source" $VisualMeshSource
    Set-NativeProperty "debug.rustyquest.native_renderer.hand_mesh.visual.wireframe.enabled" ($(if ($WireframeEnabled) { "true" } else { "false" }))
    Set-NativeProperty "debug.rustyquest.native_renderer.hand_mesh.visual.wireframe.width_px" ("{0:0.###}" -f ([Math]::Max(0.50, [Math]::Min(4.00, $WireframeWidthPx))))
    Set-NativeProperty "debug.rustyquest.native_renderer.sdf.field_visual.enabled" ($(if ($DisableSdfVisual) { "false" } else { "true" }))
    Set-NativeProperty "debug.rustyquest.native_renderer.sdf.visual.enabled" ($(if ($DisableSdfVisual) { "false" } else { "true" }))
}

function Resolve-LatestRemoteSession {
    $result = Invoke-AdbCommand -Name "list hand mesh captures" -Arguments @("shell", "ls", "-1t", $RemoteCaptureRoot) -AllowFailure
    if ($result.exit_code -ne 0) { throw "Could not list remote capture sessions at $RemoteCaptureRoot`n$($result.output)" }
    $names = $result.output -split "[`r`n]+" |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_ -notmatch "No such file" }
    if ($names.Count -lt 1) { throw "No remote hand mesh capture sessions found at $RemoteCaptureRoot" }
    return $names[0]
}

function Pull-Capture {
    param([string]$CaptureSessionId)
    if ([string]::IsNullOrWhiteSpace($CaptureSessionId)) { $CaptureSessionId = Resolve-LatestRemoteSession }
    if ([string]::IsNullOrWhiteSpace($OutDir)) {
        $repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
        $script:OutDir = Join-Path $repoRoot "target\native-renderer-hand-mesh-captures"
    }
    New-Item -ItemType Directory -Force -Path $script:OutDir | Out-Null
    $localSessionDir = Join-Path $script:OutDir $CaptureSessionId
    Invoke-AdbCommand -Name "pull hand mesh capture" -Arguments @("pull", "$RemoteCaptureRoot/$CaptureSessionId", $localSessionDir) | Out-Null
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
    $handSummaries = @()
    foreach ($hand in @("left", "right")) {
        $rigPath = Join-Path $resolved.Path "$hand.rig.json"
        $rig = $null
        if (Test-Path -LiteralPath $rigPath) { $rig = Get-Content -Raw -LiteralPath $rigPath | ConvertFrom-Json }
        $clipPath = Join-Path $resolved.Path "$hand.clip.jsonl"
        $validationPath = Join-Path $resolved.Path "$hand.validation_mesh.jsonl"
        $handSummaries += [ordered]@{
            handedness = $hand
            rig_file = "$hand.rig.json"
            clip_file = "$hand.clip.jsonl"
            validation_mesh_file = "$hand.validation_mesh.jsonl"
            topology_key = $(if ($null -eq $rig) { "missing" } else { [string]$rig.topology_key })
            bind_joint_count = $(if ($null -eq $rig) { 0 } else { @($rig.joints).Count })
            runtime_joint_count = $(if ($null -eq $rig) { 0 } else { [int]$rig.runtime_joint_set.joint_count })
            tip_length_count = $(if ($null -eq $rig) { 0 } else { [int]$rig.runtime_joint_set.tip_length_count })
            vertex_count = $(if ($null -eq $rig) { 0 } else { @($rig.bind_vertices).Count })
            triangle_count = $(if ($null -eq $rig) { 0 } else { @($rig.triangle_indices).Count })
            clip_frame_count = Count-JsonlRows -Path $clipPath
            validation_frame_count = Count-JsonlRows -Path $validationPath
        }
    }
    $summary = [ordered]@{
        schema = "rusty.quest.native_renderer.hand_mesh_capture_inspection.v1"
        capture_dir = $resolved.Path
        manifest_schema = [string]$manifest.schema
        capture_id = [string]$manifest.capture_id
        provider = [string]$manifest.provider
        runtime_provider = [string]$manifest.runtime_provider
        mesh_provider = [string]$manifest.mesh_provider
        reference_space = [string]$manifest.reference_space
        material_profile = [string]$manifest.hand_material.profile
        material_alpha = [double]$manifest.hand_material.alpha
        material_rim_strength = [double]$manifest.hand_material.rim_strength
        material_wireframe_enabled = [bool]$manifest.hand_material.wireframe_enabled
        material_wireframe_width_px = [double]$manifest.hand_material.wireframe_width_px
        hands = $handSummaries
        ok = (($handSummaries | Where-Object {
            $_.bind_joint_count -ne 26 -or $_.runtime_joint_count -ne 21 -or $_.tip_length_count -ne 5 -or
            $_.vertex_count -le 0 -or $_.triangle_count -le 0 -or $_.clip_frame_count -le 0 -or
            $_.validation_frame_count -le 0
        }).Count -eq 0)
    }
    $summaryPath = Join-Path $resolved.Path "hand-mesh-capture-inspection.json"
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
    Write-Output ($summary | ConvertTo-Json -Depth 8)
}

if ($Action -ne "Inspect" -or [string]::IsNullOrWhiteSpace($CaptureDir)) {
    Assert-AdbReady
}

switch ($Action) {
    "Prepare" {
        Prepare-MaterialLiveRun
        $preparedSession = if ([string]::IsNullOrWhiteSpace($SessionId)) { New-DefaultSessionId } else { $SessionId }
        Write-ControlFile -Enabled $false -CaptureSessionId $preparedSession | Out-Null
        Write-Host "Prepared native full hand mesh capture properties for next NativeActivity launch. session_hint=$preparedSession controlFile=$RemoteControlPath"
    }
    "Start" {
        $startedSession = Write-ControlFile -Enabled $true -CaptureSessionId $SessionId
        Write-Host "Started hand mesh capture control. session=$startedSession remoteDir=$RemoteCaptureRoot/$startedSession"
    }
    "Stop" {
        $stoppedSession = Write-ControlFile -Enabled $false -CaptureSessionId $SessionId
        Write-Host "Stopped hand mesh capture control. session_hint=$stoppedSession"
    }
    "Status" {
        $control = Invoke-AdbCommand -Name "read hand mesh capture control" -Arguments @("shell", "cat", $RemoteControlPath) -AllowFailure
        $sessions = Invoke-AdbCommand -Name "list hand mesh capture sessions" -Arguments @("shell", "ls", "-1t", $RemoteCaptureRoot) -AllowFailure
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
        Write-Host "Pulled hand mesh capture to $pulled"
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
        Invoke-AdbCommand -Name "clear hand mesh capture control" -Arguments @("shell", "rm", "-f", $RemoteControlPath) | Out-Null
        Write-Host "Cleared hand mesh capture control file $RemoteControlPath"
    }
}
