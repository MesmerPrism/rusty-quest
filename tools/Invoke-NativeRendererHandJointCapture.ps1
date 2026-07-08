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

$ControlSchema = "rusty.quest.native_renderer.hand_joint_capture_control.v1"
$RemoteFilesRoot = "/sdcard/Android/data/$PackageName/files"
$RemoteCaptureRoot = "$RemoteFilesRoot/hand-joint-captures"
$RemoteControlPath = "$RemoteFilesRoot/hand-joint-capture-control.json"

function Resolve-ToolPath {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [string]$Value,
        [string]$DefaultPath
    )

    if (-not [string]::IsNullOrWhiteSpace($Value)) {
        if (Test-Path -LiteralPath $Value) {
            return (Resolve-Path -LiteralPath $Value).Path
        }
        $command = Get-Command $Value -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
        throw "$Name not found: $Value"
    }

    if (-not [string]::IsNullOrWhiteSpace($DefaultPath) -and (Test-Path -LiteralPath $DefaultPath)) {
        return (Resolve-Path -LiteralPath $DefaultPath).Path
    }

    $fallback = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $fallback) {
        throw "$Name not found. Pass -$Name or set the matching environment variable."
    }
    return $fallback.Source
}

function Resolve-AdbServerPortArgument {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }
    $parsed = 0
    if (-not [int]::TryParse($Value, [ref]$parsed) -or $parsed -lt 1 -or $parsed -gt 65535) {
        throw "ADB server port must be an integer from 1 to 65535: $Value"
    }
    return $parsed.ToString()
}

function Invoke-AdbCommand {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string[]]$Arguments,
        [switch]$AllowFailure
    )

    $adbArgs = @()
    if ($null -ne $script:ResolvedAdbServerPort) {
        $adbArgs += @("-P", $script:ResolvedAdbServerPort)
    }
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
    return "hand-joints-{0}" -f (Get-Date -Format "yyyyMMdd-HHmmss")
}

function Write-ControlFile {
    param(
        [Parameter(Mandatory=$true)]
        [bool]$Enabled,
        [string]$CaptureSessionId
    )
    if ([string]::IsNullOrWhiteSpace($CaptureSessionId)) {
        $CaptureSessionId = New-DefaultSessionId
    }
    $control = [ordered]@{
        schema = $ControlSchema
        enabled = $Enabled
        session_id = $CaptureSessionId
        max_frames = [Math]::Max(1, [Math]::Min(36000, $MaxFrames))
        sample_period_frames = [Math]::Max(1, [Math]::Min(600, $SamplePeriodFrames))
        replay_mode = "recorded-joints-skin-live"
        companion_mesh_replay = "validation_mesh_jsonl"
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
    $tempPath = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-quest-hand-joint-capture-{0}.json" -f ([Guid]::NewGuid().ToString("N")))
    $controlJson = $control | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText(
        $tempPath,
        $controlJson,
        (New-Object System.Text.UTF8Encoding($false))
    )
    try {
        Invoke-AdbCommand -Name "mkdir remote app files" -Arguments @("shell", "mkdir", "-p", $RemoteFilesRoot) | Out-Null
        Invoke-AdbCommand -Name "push hand joint capture control" -Arguments @("push", $tempPath, $RemoteControlPath) | Out-Null
    } finally {
        Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
    }
    return $CaptureSessionId
}

function Set-NativeProperty {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string]$Value
    )
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
    $result = Invoke-AdbCommand -Name "list hand joint captures" -Arguments @("shell", "ls", "-1t", $RemoteCaptureRoot) -AllowFailure
    if ($result.exit_code -ne 0) {
        throw "Could not list remote capture sessions at $RemoteCaptureRoot`n$($result.output)"
    }
    $names = $result.output -split "[`r`n]+" |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_ -notmatch "No such file" }
    if ($names.Count -lt 1) {
        throw "No remote hand joint capture sessions found at $RemoteCaptureRoot"
    }
    return $names[0]
}

function Pull-Capture {
    param([string]$CaptureSessionId)
    if ([string]::IsNullOrWhiteSpace($CaptureSessionId)) {
        $CaptureSessionId = Resolve-LatestRemoteSession
    }
    if ([string]::IsNullOrWhiteSpace($OutDir)) {
        $repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
        $script:OutDir = Join-Path $repoRoot "target\native-renderer-hand-joint-captures"
    }
    New-Item -ItemType Directory -Force -Path $script:OutDir | Out-Null
    $localSessionDir = Join-Path $script:OutDir $CaptureSessionId
    Invoke-AdbCommand -Name "pull hand joint capture" -Arguments @("pull", "$RemoteCaptureRoot/$CaptureSessionId", $localSessionDir) | Out-Null
    return (Resolve-Path -LiteralPath $localSessionDir).Path
}

function Inspect-Capture {
    param([Parameter(Mandatory=$true)][string]$LocalCaptureDir)
    $resolved = Resolve-Path -LiteralPath $LocalCaptureDir
    $manifestPath = Join-Path $resolved.Path "capture.manifest.json"
    if (-not (Test-Path -LiteralPath $manifestPath)) {
        throw "Capture manifest missing: $manifestPath"
    }
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $handSummaries = @()
    foreach ($hand in @("left", "right")) {
        $clipPath = Join-Path $resolved.Path "$hand.clip.jsonl"
        $lines = @()
        if (Test-Path -LiteralPath $clipPath) {
            $lines = Get-Content -LiteralPath $clipPath
        }
        $frameCount = 0
        $badRows = 0
        $minJointCount = $null
        $maxJointCount = 0
        $minTipCount = $null
        $maxTipCount = 0
        $firstTimestampNs = $null
        $lastTimestampNs = $null
        foreach ($line in $lines) {
            if ([string]::IsNullOrWhiteSpace($line)) {
                continue
            }
            try {
                $row = $line | ConvertFrom-Json
                $jointCount = @($row.joints).Count
                $tipCount = @($row.tip_lengths_m).Count
                if ($null -eq $minJointCount -or $jointCount -lt $minJointCount) { $minJointCount = $jointCount }
                if ($jointCount -gt $maxJointCount) { $maxJointCount = $jointCount }
                if ($null -eq $minTipCount -or $tipCount -lt $minTipCount) { $minTipCount = $tipCount }
                if ($tipCount -gt $maxTipCount) { $maxTipCount = $tipCount }
                if ($null -eq $firstTimestampNs) { $firstTimestampNs = [Int64]$row.timestamp_ns }
                $lastTimestampNs = [Int64]$row.timestamp_ns
                if ($jointCount -ne 21 -or $tipCount -ne 5) {
                    $badRows++
                }
                $frameCount++
            } catch {
                $badRows++
            }
        }
        $handSummaries += [ordered]@{
            handedness = $hand
            clip_file = "$hand.clip.jsonl"
            frame_count = $frameCount
            bad_row_count = $badRows
            min_joint_count = $(if ($null -eq $minJointCount) { 0 } else { $minJointCount })
            max_joint_count = $maxJointCount
            min_tip_length_count = $(if ($null -eq $minTipCount) { 0 } else { $minTipCount })
            max_tip_length_count = $maxTipCount
            first_timestamp_ns = $(if ($null -eq $firstTimestampNs) { 0 } else { $firstTimestampNs })
            last_timestamp_ns = $(if ($null -eq $lastTimestampNs) { 0 } else { $lastTimestampNs })
        }
    }
    $summary = [ordered]@{
        schema = "rusty.quest.native_renderer.hand_joint_capture_inspection.v1"
        capture_dir = $resolved.Path
        manifest_schema = [string]$manifest.schema
        capture_id = [string]$manifest.capture_id
        replay_mode = [string]$manifest.replay_mode
        runtime_provider = [string]$manifest.runtime_provider
        reference_space = [string]$manifest.reference_space
        material_profile = [string]$manifest.hand_material.profile
        material_alpha = [double]$manifest.hand_material.alpha
        material_rim_strength = [double]$manifest.hand_material.rim_strength
        material_wireframe_enabled = [bool]$manifest.hand_material.wireframe_enabled
        material_wireframe_width_px = [double]$manifest.hand_material.wireframe_width_px
        requires_hand_mesh_rig_for_skinning = [bool]$manifest.requires_hand_mesh_rig_for_skinning
        hands = $handSummaries
        ok = (($handSummaries | Where-Object { $_.bad_row_count -ne 0 }).Count -eq 0 -and ($handSummaries | Where-Object { $_.frame_count -gt 0 }).Count -gt 0)
    }
    $summaryPath = Join-Path $resolved.Path "hand-joint-capture-inspection.json"
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
        Write-Host "Prepared native hand material/live joint capture properties for next NativeActivity launch. session_hint=$preparedSession controlFile=$RemoteControlPath"
    }
    "Start" {
        $startedSession = Write-ControlFile -Enabled $true -CaptureSessionId $SessionId
        Write-Host "Started hand joint capture control. session=$startedSession remoteDir=$RemoteCaptureRoot/$startedSession"
    }
    "Stop" {
        $stoppedSession = Write-ControlFile -Enabled $false -CaptureSessionId $SessionId
        Write-Host "Stopped hand joint capture control. session_hint=$stoppedSession"
    }
    "Status" {
        $control = Invoke-AdbCommand -Name "read hand joint capture control" -Arguments @("shell", "cat", $RemoteControlPath) -AllowFailure
        $sessions = Invoke-AdbCommand -Name "list hand joint capture sessions" -Arguments @("shell", "ls", "-1t", $RemoteCaptureRoot) -AllowFailure
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
        Write-Host "Pulled hand joint capture to $pulled"
    }
    "Inspect" {
        if ([string]::IsNullOrWhiteSpace($CaptureDir)) {
            throw "Inspect requires -CaptureDir."
        }
        Inspect-Capture -LocalCaptureDir $CaptureDir
    }
    "PullAndInspect" {
        $pulled = Pull-Capture -CaptureSessionId $SessionId
        Inspect-Capture -LocalCaptureDir $pulled
    }
    "ClearControl" {
        Invoke-AdbCommand -Name "clear hand joint capture control" -Arguments @("shell", "rm", "-f", $RemoteControlPath) | Out-Null
        Write-Host "Cleared hand joint capture control: $RemoteControlPath"
    }
}
