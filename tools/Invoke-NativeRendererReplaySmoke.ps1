param(
    [string]$ApkPath = "target\native-renderer-android\rusty-quest-native-renderer.apk",
    [string]$ProfilePath = "",
    [ValidateSet("ReplayVisualProof", "LiveVisualDiagnosticCaveat")]
    [string]$EvidenceMode = "ReplayVisualProof",
    [string]$OutDir = "",
    [int]$RunSeconds = 12,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.native_renderer",
    [string]$Activity = "io.github.mesmerprism.rustyquest.native_renderer/android.app.NativeActivity",
    [string[]]$ScreenshotTargetUvRects = @(),
    [int]$MinimumNonFlatScreenshotTargetRects = 1,
    [switch]$SkipInstall,
    [switch]$ClearLogcat,
    [switch]$RequireGpuTimestampReady,
    [switch]$AllowFlatScreenshot,
    [switch]$AllowPerformanceBudgetMiss,
    [switch]$StopAfterRun
)

$ErrorActionPreference = "Stop"

function Resolve-ToolPath {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [string]$Value,
        [string]$DefaultPath
    )

    if (-not [string]::IsNullOrWhiteSpace($Value)) {
        if (Test-Path $Value) {
            return (Resolve-Path $Value).Path
        }
        $command = Get-Command $Value -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
        throw "$Name not found: $Value"
    }

    if (-not [string]::IsNullOrWhiteSpace($DefaultPath) -and (Test-Path $DefaultPath)) {
        return (Resolve-Path $DefaultPath).Path
    }

    $fallback = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $fallback) {
        throw "$Name not found. Pass -$Name or set the matching environment variable."
    }
    return $fallback.Source
}

function Resolve-AdbServerPortArgument {
    param(
        [string]$Value
    )

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

    # Keep native stderr as captured evidence instead of PowerShell NativeCommandError.
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

function Invoke-CheckedPowershell {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string[]]$Arguments
    )

    # Keep child PowerShell stderr as captured evidence instead of PowerShell NativeCommandError.
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & powershell @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "$Name failed with exit code $exitCode`n$($output -join "`n")"
    }
    return ($output -join "`n")
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$defaultReplayProfilePath = "fixtures\runtime-profiles\quest-native-renderer-replay-visual-proof.profile.json"
$defaultLiveDiagnosticProfilePath = "fixtures\runtime-profiles\quest-native-renderer-live-hand-visual-diagnostic.profile.json"
if ([string]::IsNullOrWhiteSpace($ProfilePath)) {
    $ProfilePath = if ($EvidenceMode -eq "LiveVisualDiagnosticCaveat") {
        $defaultLiveDiagnosticProfilePath
    } else {
        $defaultReplayProfilePath
    }
}
$resolvedApk = if ([System.IO.Path]::IsPathRooted($ApkPath)) {
    $ApkPath
} else {
    Join-Path $repoRoot $ApkPath
}
$resolvedProfile = if ([System.IO.Path]::IsPathRooted($ProfilePath)) {
    $ProfilePath
} else {
    Join-Path $repoRoot $ProfilePath
}
if (-not (Test-Path $resolvedApk)) {
    throw "APK not found: $resolvedApk"
}
if (-not (Test-Path $resolvedProfile)) {
    throw "Runtime profile not found: $resolvedProfile"
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRoot "local-artifacts\native-renderer-replay-smoke-$stamp"
} elseif (-not [System.IO.Path]::IsPathRooted($OutDir)) {
    $OutDir = Join-Path $repoRoot $OutDir
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$script:ResolvedAdb = Resolve-ToolPath `
    -Name "adb" `
    -Value $Adb `
    -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
$script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument -Value $AdbServerPort

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "-Serial or RUSTY_QUEST_SERIAL is required; device-facing smoke tests must pass adb -s <serial> and must not use an implicit target."
}

$rawLogcatPath = Join-Path $OutDir "raw-logcat.txt"
$filteredLogcatPath = Join-Path $OutDir "filtered-native-renderer-logcat.txt"
$screenshotPath = Join-Path $OutDir "screenshot.png"
$propertyPlanPath = Join-Path $OutDir "property-write-plan.json"
$evidenceSummaryPath = Join-Path $OutDir "runtime-evidence-summary.json"
$screenshotCropOutDir = Join-Path $OutDir "screenshot-crops"
$summaryPath = Join-Path $OutDir "run-summary.json"
$remoteScreenshotPath = "/data/local/tmp/rusty_quest_native_renderer_replay_smoke.png"

$summary = [ordered]@{
    schema = "rusty.quest.native_renderer_replay_smoke_run.v1"
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    status = "started"
    adb_path = $script:ResolvedAdb
    adb_scope = "device-scoped-adb"
    adb_serial_required = $true
    adb_server_port = $script:ResolvedAdbServerPort
    serial = $Serial
    package_name = $PackageName
    activity = $Activity
    apk_path = (Resolve-Path $resolvedApk).Path
    profile_path = (Resolve-Path $resolvedProfile).Path
    evidence_mode = $EvidenceMode
    out_dir = (Resolve-Path $OutDir).Path
    run_seconds = $RunSeconds
    skipped_install = [bool]$SkipInstall
    clear_logcat_requested = [bool]$ClearLogcat
    logcat_scope = "pid-scoped-device-logcat"
    gpu_timestamp_required = [bool]$RequireGpuTimestampReady
    non_flat_screenshot_required = (-not [bool]$AllowFlatScreenshot)
    screenshot_target_uv_rects = $ScreenshotTargetUvRects
    minimum_non_flat_screenshot_target_rects = $MinimumNonFlatScreenshotTargetRects
    hand_mesh_visual_screenshot_required = (-not [bool]$AllowFlatScreenshot)
    sdf_visual_screenshot_required = (-not [bool]$AllowFlatScreenshot)
    replay_visual_proof_required = ($EvidenceMode -eq "ReplayVisualProof")
    live_visual_diagnostic_caveat_required = ($EvidenceMode -eq "LiveVisualDiagnosticCaveat")
    performance_budget_required = (-not [bool]$AllowPerformanceBudgetMiss)
    stop_after_run = [bool]$StopAfterRun
    property_plan_path = $propertyPlanPath
    raw_logcat_path = $rawLogcatPath
    filtered_logcat_path = $filteredLogcatPath
    screenshot_path = $screenshotPath
    screenshot_crop_out_dir = $screenshotCropOutDir
    runtime_evidence_summary_path = $evidenceSummaryPath
    validation_command = "Test-NativeRendererRuntimeEvidence.ps1"
}

try {
    $state = Invoke-AdbCommand -Name "adb get-state" -Arguments @("get-state")
    $deviceState = $state.output.Trim()
    if ($deviceState -ne "device") {
        throw "ADB target is not ready: $deviceState"
    }
    $summary.device_state = $deviceState
    $summary.device_model = (Invoke-AdbCommand -Name "device model" -Arguments @("shell", "getprop", "ro.product.model")).output.Trim()
    $summary.device_build = (Invoke-AdbCommand -Name "device build" -Arguments @("shell", "getprop", "ro.build.version.incremental")).output.Trim()

    if (-not $SkipInstall) {
        Invoke-AdbCommand -Name "install APK" -Arguments @("install", "-r", "-d", "-g", (Resolve-Path $resolvedApk).Path) | Out-Null
    }

    $permissionResults = @()
    foreach ($permission in @(
        "android.permission.CAMERA",
        "com.oculus.permission.HAND_TRACKING",
        "horizonos.permission.HEADSET_CAMERA",
        "horizonos.permission.SPATIAL_CAMERA"
    )) {
        $permissionResults += Invoke-AdbCommand `
            -Name "grant $permission" `
            -Arguments @("shell", "pm", "grant", $PackageName, $permission) `
            -AllowFailure
    }
    $summary.permission_grants = $permissionResults

    $profileArgs = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $PSScriptRoot "Apply-RuntimeProfile.ps1"),
        "-ProfilePath", (Resolve-Path $resolvedProfile).Path,
        "-Execute",
        "-Out", $propertyPlanPath,
        "-Adb", $script:ResolvedAdb,
        "-Serial", $Serial
    )
    if ($null -ne $script:ResolvedAdbServerPort) {
        $profileArgs += @("-AdbServerPort", $script:ResolvedAdbServerPort)
    }
    $summary.profile_apply_output = Invoke-CheckedPowershell -Name "runtime profile apply" -Arguments $profileArgs

    if ($ClearLogcat) {
        Invoke-AdbCommand -Name "clear logcat" -Arguments @("logcat", "-c") | Out-Null
    }
    $summary.launch_output = (Invoke-AdbCommand -Name "launch native renderer" -Arguments @("shell", "am", "start", "-W", "-n", $Activity)).output
    Start-Sleep -Seconds ([Math]::Max(1, $RunSeconds))

    $pidResult = Invoke-AdbCommand -Name "native renderer pid" -Arguments @("shell", "pidof", $PackageName) -AllowFailure
    $summary.target_pid = (($pidResult.output -split "\s+") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($summary.target_pid)) {
        throw "Native renderer process id was not available after launch; refusing unscoped logcat evidence."
    }

    $rawLogcat = (Invoke-AdbCommand -Name "dump pid-scoped logcat" -Arguments @("logcat", "-d", "-v", "time", "--pid", $summary.target_pid)).output
    Set-Content -Encoding UTF8 -Path $rawLogcatPath -Value $rawLogcat
    $filtered = @($rawLogcat -split "`r?`n" | Where-Object { $_ -match "RUSTY_QUEST_NATIVE_RENDERER" })
    [System.IO.File]::WriteAllLines($filteredLogcatPath, [string[]]$filtered)

    Invoke-AdbCommand -Name "capture screenshot" -Arguments @("shell", "screencap", "-p", $remoteScreenshotPath) | Out-Null
    Invoke-AdbCommand -Name "pull screenshot" -Arguments @("pull", $remoteScreenshotPath, $screenshotPath) | Out-Null
    Invoke-AdbCommand -Name "remove remote screenshot" -Arguments @("shell", "rm", $remoteScreenshotPath) -AllowFailure | Out-Null

    $evidenceArgs = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $PSScriptRoot "Test-NativeRendererRuntimeEvidence.ps1"),
        "-LogcatPath", $filteredLogcatPath,
        "-ScreenshotPath", $screenshotPath,
        "-ScreenshotCropOutDir", $screenshotCropOutDir,
        "-SummaryOut", $evidenceSummaryPath,
        "-RequireScreenshot",
        "-RequireCameraProjection",
        "-RequireGuideGraph",
        "-RequirePrivateSlotNoPayload"
    )
    if ($EvidenceMode -eq "LiveVisualDiagnosticCaveat") {
        $evidenceArgs += "-RequireLiveVisualDiagnosticCaveat"
    } else {
        $evidenceArgs += @(
            "-RequireReplayVisualProof",
            "-RequireSdfVisual"
        )
    }
    if (-not $AllowFlatScreenshot) {
        $evidenceArgs += @(
            "-RequireNonFlatScreenshot",
            "-RequireTargetNonFlatScreenshot",
            "-RequireHandMeshVisualScreenshot",
            "-RequireSdfVisualScreenshot",
            "-MinimumNonFlatScreenshotTargetRects", $MinimumNonFlatScreenshotTargetRects.ToString()
        )
        if ($ScreenshotTargetUvRects.Count -gt 0) {
            $evidenceArgs += @("-ScreenshotTargetUvRects", ($ScreenshotTargetUvRects -join "|"))
        }
    }
    if ($RequireGpuTimestampReady) {
        $evidenceArgs += "-RequireGpuTimestampReady"
    }
    if (-not $AllowPerformanceBudgetMiss) {
        $evidenceArgs += "-RequirePerformanceBudget"
    }
    $summary.runtime_evidence_output = Invoke-CheckedPowershell -Name "native renderer runtime evidence" -Arguments $evidenceArgs

    if ($StopAfterRun) {
        $summary.stop_output = (Invoke-AdbCommand -Name "stop native renderer" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure).output
    }

    $summary.status = "passed"
} catch {
    $summary.status = "failed"
    $summary.error = $_.Exception.Message
    throw
} finally {
    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $summaryPath
}

Write-Output "Native renderer no-real-hands replay smoke summary: $summaryPath"
