param(
    [string]$RunCapsule = "",
    [string]$ApkPath = "target\native-renderer-android\rusty-quest-native-renderer.apk",
    [string]$ProfilePath = "",
    [ValidateSet("ReplayVisualProof", "LiveVisualDiagnosticCaveat", "EnvironmentDepthParticles", "PrivateParticleCanary", "ParticleAdapterConformance")]
    [string]$EvidenceMode = "ReplayVisualProof",
    [string]$OutDir = "",
    [int]$RunSeconds = 12,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.native_renderer",
    [string]$Activity = "io.github.mesmerprism.rustyquest.native_renderer/android.app.NativeActivity",
    [ValidateRange(1, 1800)][int]$RunIsolationMutexTimeoutSeconds = 120,
    [string[]]$ScreenshotTargetUvRects = @(),
    [int]$MinimumNonFlatScreenshotTargetRects = 1,
    [switch]$SkipInstall,
    [switch]$ClearLogcat,
    [switch]$RequireGpuTimestampReady,
    [switch]$AllowFlatScreenshot,
    [switch]$AllowPerformanceBudgetMiss,
    [switch]$AllowPrivateLayerPayload,
    [switch]$RequireEnvironmentDepthSurfaceSupport,
    [int]$ExpectedEnvironmentDepthParticleCount = 0,
    [int]$MinimumEnvironmentDepthSourceDepthSamples = 0,
    [int]$MinimumEnvironmentDepthHashProbeExhaustedCount = 0,
    [int]$MinimumEnvironmentDepthHeadMotionSamples = 0,
    [double]$MinimumEnvironmentDepthHeadMotionYawDeg = 0.0,
    [double]$MinimumEnvironmentDepthHeadMotionTranslationM = 0.0,
    [switch]$RequireEnvironmentDepthKnownDistance,
    [double]$ExpectedEnvironmentDepthCenterMeters = 0.0,
    [double]$EnvironmentDepthCenterToleranceMeters = 0.15,
    [double]$MinimumEnvironmentDepthCenterConfidence = 0.0,
    [int]$MinimumEnvironmentDepthCenterWindowValidCount = 0,
    [string]$PostProfileAndroidPropertyName = "",
    [string]$PostProfileAndroidPropertyValue = "",
    [switch]$StopAfterRun,
    [switch]$AllowLegacyLooseInputs
)

$ErrorActionPreference = "Stop"

if ($RequireEnvironmentDepthKnownDistance) {
    if ($EvidenceMode -ne "EnvironmentDepthParticles") {
        throw "RequireEnvironmentDepthKnownDistance requires -EvidenceMode EnvironmentDepthParticles."
    }
    if ($ExpectedEnvironmentDepthCenterMeters -le 0.0) {
        throw "ExpectedEnvironmentDepthCenterMeters must be positive when RequireEnvironmentDepthKnownDistance is set."
    }
    if ($EnvironmentDepthCenterToleranceMeters -le 0.0) {
        throw "EnvironmentDepthCenterToleranceMeters must be positive when RequireEnvironmentDepthKnownDistance is set."
    }
    if ($MinimumEnvironmentDepthCenterConfidence -lt 0.0 -or $MinimumEnvironmentDepthCenterConfidence -gt 1.0) {
        throw "MinimumEnvironmentDepthCenterConfidence must be in 0..1."
    }
    if ($MinimumEnvironmentDepthCenterWindowValidCount -lt 0) {
        throw "MinimumEnvironmentDepthCenterWindowValidCount must be nonnegative."
    }
}

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
        [switch]$AllowFailure,
        [int]$TimeoutSeconds = 120
    )

    $adbArgs = @()
    if ($null -ne $script:ResolvedAdbServerPort) {
        $adbArgs += @("-P", $script:ResolvedAdbServerPort)
    }
    $adbArgs += @("-s", $script:Serial)
    $adbArgs += $Arguments

    # Keep native stderr as captured evidence instead of PowerShell NativeCommandError.
    $stdoutPath = [IO.Path]::GetTempFileName()
    $stderrPath = [IO.Path]::GetTempFileName()
    $quotedArgs = @($adbArgs | ForEach-Object {
        $arg = [string]$_
        if ($arg -match '[\s"]') { '"' + $arg.Replace('"', '\"') + '"' } else { $arg }
    })
    $process = $null
    try {
        $process = Start-Process -FilePath $script:ResolvedAdb -ArgumentList $quotedArgs -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru -WindowStyle Hidden
        $process.Handle | Out-Null
        if (-not $process.WaitForExit([Math]::Max(1, $TimeoutSeconds) * 1000)) {
            try { $process.Kill($true) } catch {}
            $exitCode = 124
            $output = "adb command timed out after $TimeoutSeconds seconds."
        } else {
            $process.WaitForExit()
            $process.Refresh()
            $stdout = if (Test-Path -LiteralPath $stdoutPath) { Get-Content -Raw -LiteralPath $stdoutPath } else { "" }
            $stderr = if (Test-Path -LiteralPath $stderrPath) { Get-Content -Raw -LiteralPath $stderrPath } else { "" }
            $exitCode = $process.ExitCode
            $output = (@($stdout, $stderr) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "`n"
        }
    } finally {
        if ($null -ne $process) { $process.Dispose() }
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
    $result = [ordered]@{
        name = $Name
        arguments = $Arguments
        exit_code = $exitCode
        output = $output
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
        $output = & pwsh @Arguments 2>&1
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
$capsule = $null
$capsuleValidation = $null
if ([string]::IsNullOrWhiteSpace($RunCapsule)) {
    if (-not $AllowLegacyLooseInputs) {
        throw "-RunCapsule is required for an isolated APK launch. Use -AllowLegacyLooseInputs only for explicit historical compatibility."
    }
} else {
    $resolvedCapsulePath = if ([IO.Path]::IsPathRooted($RunCapsule)) { $RunCapsule } else { Join-Path $repoRoot $RunCapsule }
    $capsuleValidationText = & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "Test-ApkRunCapsule.ps1") -CapsulePath $resolvedCapsulePath -ExpectedLane native-renderer-android
    if ($LASTEXITCODE -ne 0) { throw "APK run capsule validation failed: $resolvedCapsulePath" }
    $capsuleValidation = ($capsuleValidationText -join "`n") | ConvertFrom-Json
    $capsule = Get-Content -LiteralPath $capsuleValidation.capsule_path -Raw | ConvertFrom-Json
    $ApkPath = [string]$capsule.apk.path
    $ProfilePath = [string]$capsule.runtime_profile.path
    $PackageName = [string]$capsule.android.package_name
    $Activity = [string]$capsule.android.activity
}
$defaultReplayProfilePath = "fixtures\runtime-profiles\quest-native-renderer-replay-visual-proof.profile.json"
$defaultLiveDiagnosticProfilePath = "fixtures\runtime-profiles\quest-native-renderer-live-hand-visual-diagnostic.profile.json"
$defaultEnvironmentDepthParticlesProfilePath = "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json"
if ([string]::IsNullOrWhiteSpace($ProfilePath)) {
    if ($EvidenceMode -eq "PrivateParticleCanary") {
        throw "PrivateParticleCanary evidence mode requires -ProfilePath from a generated native app-build lock."
    }
    $ProfilePath = switch ($EvidenceMode) {
        "LiveVisualDiagnosticCaveat" { $defaultLiveDiagnosticProfilePath }
        "EnvironmentDepthParticles" { $defaultEnvironmentDepthParticlesProfilePath }
        default { $defaultReplayProfilePath }
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
$capsuleApkPath = (Resolve-Path -LiteralPath $resolvedApk).Path
Import-Module (Join-Path $PSScriptRoot "lib\QuestRunIsolation.psm1") -Force
if ($null -ne $capsule) { $resolvedApk = Get-QuestRunCapsuleInstallApk -RepoRoot $repoRoot -Capsule $capsule }
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
$permissionPregrantPath = Join-Path $OutDir "permission-pregrant.json"
$evidenceSummaryPath = Join-Path $OutDir "runtime-evidence-summary.json"
$screenshotCropOutDir = Join-Path $OutDir "screenshot-crops"
$summaryPath = Join-Path $OutDir "run-summary.json"
$isolationReceiptPath = Join-Path $OutDir "run-isolation-receipt.json"
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
    capsule_apk_path = $capsuleApkPath
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
    environment_depth_particles_required = ($EvidenceMode -eq "EnvironmentDepthParticles")
    private_particle_canary_required = ($EvidenceMode -eq "PrivateParticleCanary")
    environment_depth_surface_support_required = [bool]$RequireEnvironmentDepthSurfaceSupport
    performance_budget_required = (-not [bool]$AllowPerformanceBudgetMiss)
    private_layer_payload_allowed = [bool]$AllowPrivateLayerPayload
    expected_environment_depth_particle_count = $ExpectedEnvironmentDepthParticleCount
    minimum_environment_depth_source_depth_samples = $MinimumEnvironmentDepthSourceDepthSamples
    minimum_environment_depth_hash_probe_exhausted_count = $MinimumEnvironmentDepthHashProbeExhaustedCount
    minimum_environment_depth_head_motion_samples = $MinimumEnvironmentDepthHeadMotionSamples
    minimum_environment_depth_head_motion_yaw_deg = $MinimumEnvironmentDepthHeadMotionYawDeg
    minimum_environment_depth_head_motion_translation_m = $MinimumEnvironmentDepthHeadMotionTranslationM
    environment_depth_known_distance_required = [bool]$RequireEnvironmentDepthKnownDistance
    expected_environment_depth_center_meters = $ExpectedEnvironmentDepthCenterMeters
    environment_depth_center_tolerance_meters = $EnvironmentDepthCenterToleranceMeters
    minimum_environment_depth_center_confidence = $MinimumEnvironmentDepthCenterConfidence
    minimum_environment_depth_center_window_valid_count = $MinimumEnvironmentDepthCenterWindowValidCount
    stop_after_run = [bool]$StopAfterRun
    cleanup_always = $true
    run_capsule_path = if ($null -eq $capsuleValidation) { "" } else { [string]$capsuleValidation.capsule_path }
    run_capsule_sha256 = if ($null -eq $capsuleValidation) { "" } else { [string]$capsuleValidation.capsule_sha256 }
    legacy_loose_inputs = [bool]$AllowLegacyLooseInputs
    isolation_receipt_path = $isolationReceiptPath
    property_plan_path = $propertyPlanPath
    permission_pregrant_path = $permissionPregrantPath
    raw_logcat_path = $rawLogcatPath
    filtered_logcat_path = $filteredLogcatPath
    screenshot_path = $screenshotPath
    screenshot_crop_out_dir = $screenshotCropOutDir
    runtime_evidence_summary_path = $evidenceSummaryPath
    validation_command = "Test-NativeRendererRuntimeEvidence.ps1"
    post_profile_android_property_name = $PostProfileAndroidPropertyName
    post_profile_android_property_override_requested = (-not [string]::IsNullOrWhiteSpace($PostProfileAndroidPropertyName))
}

$propertyManifestPath = if ($null -eq $capsule -or $null -eq $capsule.property_manifest) {
    Join-Path $repoRoot "fixtures\native-renderer\native-renderer-property-manifest.json"
} else {
    [string]$capsule.property_manifest.path
}
$propertyManifest = Get-Content -LiteralPath $propertyManifestPath -Raw | ConvertFrom-Json
$isolationPropertyNames = @($propertyManifest.properties | ForEach-Object { [string]$_.name })
if (-not [string]::IsNullOrWhiteSpace($PostProfileAndroidPropertyName)) { $isolationPropertyNames += $PostProfileAndroidPropertyName }
$isolationContext = Enter-QuestRunIsolation `
    -Adb $script:ResolvedAdb `
    -Serial $Serial `
    -AdbServerPort $script:ResolvedAdbServerPort `
    -PackageName $PackageName `
    -PropertyNames $isolationPropertyNames `
    -ReceiptPath $isolationReceiptPath `
    -MutexTimeoutSeconds $RunIsolationMutexTimeoutSeconds
$cleanupFailureMessage = ""

try {
    $state = Invoke-AdbCommand -Name "adb get-state" -Arguments @("get-state")
    $deviceState = $state.output.Trim()
    if ($deviceState -ne "device") {
        throw "ADB target is not ready: $deviceState"
    }
    Clear-QuestRunIsolationProperties -Context $isolationContext
    $summary.device_state = $deviceState
    $summary.device_model = (Invoke-AdbCommand -Name "device model" -Arguments @("shell", "getprop", "ro.product.model")).output.Trim()
    $summary.device_build = (Invoke-AdbCommand -Name "device build" -Arguments @("shell", "getprop", "ro.build.version.incremental")).output.Trim()

    if (-not $SkipInstall) {
        Invoke-AdbCommand -Name "install APK" -Arguments @("install", "-r", "-d", "-g", (Resolve-Path $resolvedApk).Path) | Out-Null
    }

    $pregrantArgs = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $PSScriptRoot "Grant-NativeRendererPermissions.ps1"),
        "-Adb", $script:ResolvedAdb,
        "-Serial", $Serial,
        "-PackageName", $PackageName,
        "-Out", $permissionPregrantPath
    )
    if ($null -ne $script:ResolvedAdbServerPort) {
        $pregrantArgs += @("-AdbServerPort", $script:ResolvedAdbServerPort)
    }
    $summary.permission_pregrant_output = Invoke-CheckedPowershell -Name "native renderer permission pregrant" -Arguments $pregrantArgs

    $profileArgs = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $PSScriptRoot "Apply-RuntimeProfile.ps1"),
        "-ProfilePath", (Resolve-Path $resolvedProfile).Path,
        "-Execute",
        "-PropertyScopeMode", "CompleteManifest",
        "-Out", $propertyPlanPath,
        "-Adb", $script:ResolvedAdb,
        "-Serial", $Serial
    )
    if ($null -ne $script:ResolvedAdbServerPort) {
        $profileArgs += @("-AdbServerPort", $script:ResolvedAdbServerPort)
    }
    $summary.profile_apply_output = Invoke-CheckedPowershell -Name "runtime profile apply" -Arguments $profileArgs
    if (-not [string]::IsNullOrWhiteSpace($PostProfileAndroidPropertyName)) {
        if ([string]::IsNullOrWhiteSpace($PostProfileAndroidPropertyValue)) {
            throw "-PostProfileAndroidPropertyValue is required when -PostProfileAndroidPropertyName is supplied."
        }
        $summary.post_profile_property_override_output = (Invoke-AdbCommand -Name "post-profile property override" -Arguments @("shell", "setprop", $PostProfileAndroidPropertyName, $PostProfileAndroidPropertyValue)).output
    }

    if ($ClearLogcat) {
        Invoke-AdbCommand -Name "clear logcat" -Arguments @("logcat", "-c") | Out-Null
    }
    $summary.launch_output = (Invoke-AdbCommand -Name "launch native renderer" -Arguments @("shell", "am", "start", "-W", "-n", $Activity)).output
    $summary.target_pid = ""
    foreach ($attempt in 1..10) {
        $pidResult = Invoke-AdbCommand -Name "native renderer pid" -Arguments @("shell", "pidof", $PackageName) -AllowFailure
        $summary.target_pid = (($pidResult.output -split "\s+") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
        if (-not [string]::IsNullOrWhiteSpace($summary.target_pid)) { break }
        Start-Sleep -Milliseconds 250
    }
    if ([string]::IsNullOrWhiteSpace($summary.target_pid)) {
        throw "Native renderer process id was not available after launch; refusing unscoped logcat evidence."
    }
    Start-Sleep -Seconds ([Math]::Max(1, $RunSeconds))

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
        "-RequireScreenshot"
    )
    if ($EvidenceMode -eq "EnvironmentDepthParticles") {
        $evidenceArgs += "-RequireEnvironmentDepthParticles"
        if ($RequireEnvironmentDepthSurfaceSupport) {
            $evidenceArgs += "-RequireEnvironmentDepthSurfaceSupport"
        }
        if ($ExpectedEnvironmentDepthParticleCount -gt 0) {
            $evidenceArgs += @("-ExpectedEnvironmentDepthParticleCount", $ExpectedEnvironmentDepthParticleCount.ToString())
        }
        if ($MinimumEnvironmentDepthSourceDepthSamples -gt 0) {
            $evidenceArgs += @("-MinimumEnvironmentDepthSourceDepthSamples", $MinimumEnvironmentDepthSourceDepthSamples.ToString())
        }
        if ($MinimumEnvironmentDepthHashProbeExhaustedCount -gt 0) {
            $evidenceArgs += @("-MinimumEnvironmentDepthHashProbeExhaustedCount", $MinimumEnvironmentDepthHashProbeExhaustedCount.ToString())
        }
        if ($MinimumEnvironmentDepthHeadMotionSamples -gt 0) {
            $evidenceArgs += @("-MinimumEnvironmentDepthHeadMotionSamples", $MinimumEnvironmentDepthHeadMotionSamples.ToString())
        }
        if ($MinimumEnvironmentDepthHeadMotionYawDeg -gt 0.0) {
            $evidenceArgs += @("-MinimumEnvironmentDepthHeadMotionYawDeg", $MinimumEnvironmentDepthHeadMotionYawDeg.ToString([System.Globalization.CultureInfo]::InvariantCulture))
        }
        if ($MinimumEnvironmentDepthHeadMotionTranslationM -gt 0.0) {
            $evidenceArgs += @("-MinimumEnvironmentDepthHeadMotionTranslationM", $MinimumEnvironmentDepthHeadMotionTranslationM.ToString([System.Globalization.CultureInfo]::InvariantCulture))
        }
        if ($RequireEnvironmentDepthKnownDistance) {
            $evidenceArgs += @(
                "-RequireEnvironmentDepthKnownDistance",
                "-ExpectedEnvironmentDepthCenterMeters",
                $ExpectedEnvironmentDepthCenterMeters.ToString([System.Globalization.CultureInfo]::InvariantCulture),
                "-EnvironmentDepthCenterToleranceMeters",
                $EnvironmentDepthCenterToleranceMeters.ToString([System.Globalization.CultureInfo]::InvariantCulture),
                "-MinimumEnvironmentDepthCenterConfidence",
                $MinimumEnvironmentDepthCenterConfidence.ToString([System.Globalization.CultureInfo]::InvariantCulture),
                "-MinimumEnvironmentDepthCenterWindowValidCount",
                $MinimumEnvironmentDepthCenterWindowValidCount.ToString()
            )
        }
    } elseif ($EvidenceMode -eq "PrivateParticleCanary") {
        $evidenceArgs += "-RequirePrivateParticleSlotNoPayload"
    } elseif ($EvidenceMode -eq "ParticleAdapterConformance") {
        # Particle adapter acceptance is reduced by Test-QuestParticleAdapterEvidence.ps1
        # from the native and Spatial marker logs. Do not require private extension
        # or private-particle payload markers for this reusable module-lock gate.
    } else {
        $evidenceArgs += @(
            "-RequireCameraProjection",
            "-RequireGuideGraph"
        )
    }
    if ($AllowPrivateLayerPayload) {
        $evidenceArgs += "-RequirePrivateSlotPayload"
    } elseif ($EvidenceMode -notin @("PrivateParticleCanary", "ParticleAdapterConformance")) {
        $evidenceArgs += "-RequirePrivateSlotNoPayload"
    }
    switch ($EvidenceMode) {
        "LiveVisualDiagnosticCaveat" {
            $evidenceArgs += "-RequireLiveVisualDiagnosticCaveat"
        }
        "ReplayVisualProof" {
            $evidenceArgs += @(
                "-RequireReplayVisualProof",
                "-RequireSdfVisual"
            )
        }
    }
    if (-not $AllowFlatScreenshot) {
        $evidenceArgs += "-RequireNonFlatScreenshot"
        if ($EvidenceMode -in @("ReplayVisualProof", "LiveVisualDiagnosticCaveat")) {
            $evidenceArgs += @(
                "-RequireTargetNonFlatScreenshot",
                "-RequireHandMeshVisualScreenshot",
                "-RequireSdfVisualScreenshot",
                "-MinimumNonFlatScreenshotTargetRects", $MinimumNonFlatScreenshotTargetRects.ToString()
            )
            if ($ScreenshotTargetUvRects.Count -gt 0) {
                $evidenceArgs += @("-ScreenshotTargetUvRects", ($ScreenshotTargetUvRects -join "|"))
            }
        }
    }
    if ($RequireGpuTimestampReady) {
        $evidenceArgs += "-RequireGpuTimestampReady"
    }
    if (-not $AllowPerformanceBudgetMiss) {
        $evidenceArgs += "-RequirePerformanceBudget"
    }
    $summary.runtime_evidence_output = Invoke-CheckedPowershell -Name "native renderer runtime evidence" -Arguments $evidenceArgs

    $summary.status = "passed"
} catch {
    $summary.status = "failed"
    $summary.error = $_.Exception.Message
    throw
} finally {
    try {
        $cleanupReceipt = Exit-QuestRunIsolation -Context $isolationContext
        $summary.cleanup_status = [string]$cleanupReceipt.status
        $summary.cleanup_property_restore_count = @($cleanupReceipt.property_restore).Count
        if ([string]$cleanupReceipt.status -ne "pass" -and [string]$summary.status -eq "passed") {
            $summary.status = "failed"
            $summary.error = "Quest run isolation cleanup was partial."
            $cleanupFailureMessage = $summary.error
        }
    } catch {
        $summary.cleanup_status = "failed"
        $summary.cleanup_error = $_.Exception.Message
        if ([string]$summary.status -eq "passed") { $summary.status = "failed" }
        $cleanupFailureMessage = "Quest run isolation cleanup failed: $($_.Exception.Message)"
    }
    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $summaryPath
}
if (-not [string]::IsNullOrWhiteSpace($cleanupFailureMessage)) { throw $cleanupFailureMessage }

Write-Output "Native renderer no-real-hands replay smoke summary: $summaryPath"
