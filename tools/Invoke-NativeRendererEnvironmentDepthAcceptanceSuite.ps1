param(
    [string]$ApkPath = "target\native-renderer-android\rusty-quest-native-renderer.apk",
    [string]$ProfilePath = "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json",
    [string]$OutDir = "",
    [int]$MotionRunSeconds = 12,
    [int]$KnownDistanceRunSeconds = 8,
    [double[]]$TargetDistancesMeters = @(0.5, 1.0, 2.0, 4.0),
    [double]$KnownDistanceToleranceMeters = 0.15,
    [double]$MinimumCenterConfidence = 0.5,
    [int]$MinimumCenterWindowValidCount = 5,
    [int]$MinimumHeadMotionSamples = 120,
    [double]$MinimumYawDeg = 25.0,
    [double]$MinimumTranslationM = 0.0,
    [switch]$RequireEnvironmentDepthSurfaceSupport,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [switch]$SkipInstall,
    [switch]$ClearLogcat,
    [switch]$StopAfterRun
)

$ErrorActionPreference = "Stop"

function Format-DistanceLabel {
    param(
        [Parameter(Mandatory=$true)]
        [double]$Value
    )

    return (($Value.ToString("0.###", [System.Globalization.CultureInfo]::InvariantCulture)) -replace "\.", "p")
}

function Invoke-CheckedPowershell {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string[]]$Arguments
    )

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

foreach ($requiredDistance in @(0.5, 1.0, 2.0, 4.0)) {
    $matchingDistances = @($TargetDistancesMeters | Where-Object {
        [Math]::Abs($_ - $requiredDistance) -le 0.02
    })
    if ($matchingDistances.Count -ne 1) {
        throw "TargetDistancesMeters must include exactly one target near $requiredDistance m for the final depth-scale gate."
    }
}
if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "-Serial or RUSTY_QUEST_SERIAL is required; the acceptance suite must not use an implicit ADB target."
}
if ($MotionRunSeconds -lt 1 -or $KnownDistanceRunSeconds -lt 1) {
    throw "Run durations must be positive."
}
if ($TargetDistancesMeters.Count -lt 4) {
    throw "TargetDistancesMeters must include at least the 0.5 m, 1 m, 2 m, and 4 m known-distance targets."
}
if ($KnownDistanceToleranceMeters -le 0.0) {
    throw "KnownDistanceToleranceMeters must be positive."
}
if ($MinimumCenterConfidence -lt 0.0 -or $MinimumCenterConfidence -gt 1.0) {
    throw "MinimumCenterConfidence must be in 0..1."
}
if ($MinimumCenterWindowValidCount -lt 0) {
    throw "MinimumCenterWindowValidCount must be nonnegative."
}
if ($MinimumHeadMotionSamples -lt 1) {
    throw "MinimumHeadMotionSamples must be positive."
}
if ($MinimumYawDeg -lt 0.0 -or $MinimumTranslationM -lt 0.0) {
    throw "Motion thresholds must be nonnegative."
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRoot "local-artifacts\native-renderer-envdepth-acceptance-suite-$stamp"
} elseif (-not [System.IO.Path]::IsPathRooted($OutDir)) {
    $OutDir = Join-Path $repoRoot $OutDir
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$suiteSummaryPath = Join-Path $OutDir "acceptance-suite-summary.json"
$motionOutDir = Join-Path $OutDir "motion-proof"
$seriesResultPath = Join-Path $OutDir "known-distance-series-result.json"
$bundleResultPath = Join-Path $OutDir "environment-depth-evidence-bundle-result.json"

$summary = [ordered]@{
    schema = "rusty.quest.environment_depth_acceptance_suite_run.v1"
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    status = "started"
    serial = $Serial
    adb_path = $Adb
    adb_server_port = $AdbServerPort
    apk_path = $ApkPath
    profile_path = $ProfilePath
    out_dir = (Resolve-Path $OutDir).Path
    motion_run_seconds = $MotionRunSeconds
    known_distance_run_seconds = $KnownDistanceRunSeconds
    target_distances_meters = $TargetDistancesMeters
    known_distance_tolerance_meters = $KnownDistanceToleranceMeters
    minimum_center_confidence = $MinimumCenterConfidence
    minimum_center_window_valid_count = $MinimumCenterWindowValidCount
    minimum_head_motion_samples = $MinimumHeadMotionSamples
    minimum_yaw_deg = $MinimumYawDeg
    minimum_translation_m = $MinimumTranslationM
    require_environment_depth_surface_support = [bool]$RequireEnvironmentDepthSurfaceSupport
    skip_install = [bool]$SkipInstall
    clear_logcat = [bool]$ClearLogcat
    stop_after_run = [bool]$StopAfterRun
    human_device_visual_acceptance_required = $true
}

try {
    $motionArgs = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $PSScriptRoot "Invoke-NativeRendererEnvironmentDepthMotionProof.ps1"),
        "-ApkPath", $ApkPath,
        "-ProfilePath", $ProfilePath,
        "-OutDir", $motionOutDir,
        "-RunSeconds", $MotionRunSeconds.ToString(),
        "-Serial", $Serial,
        "-MinimumHeadMotionSamples", $MinimumHeadMotionSamples.ToString()
    )
    if ($MinimumYawDeg -gt 0.0) {
        $motionArgs += @("-MinimumYawDeg", $MinimumYawDeg.ToString([System.Globalization.CultureInfo]::InvariantCulture))
    }
    if ($MinimumTranslationM -gt 0.0) {
        $motionArgs += @("-MinimumTranslationM", $MinimumTranslationM.ToString([System.Globalization.CultureInfo]::InvariantCulture))
    }
    if (-not [string]::IsNullOrWhiteSpace($Adb)) {
        $motionArgs += @("-Adb", $Adb)
    }
    if (-not [string]::IsNullOrWhiteSpace($AdbServerPort)) {
        $motionArgs += @("-AdbServerPort", $AdbServerPort)
    }
    if ($RequireEnvironmentDepthSurfaceSupport) {
        $motionArgs += "-RequireEnvironmentDepthSurfaceSupport"
    }
    if ($SkipInstall) {
        $motionArgs += "-SkipInstall"
    }
    if ($ClearLogcat) {
        $motionArgs += "-ClearLogcat"
    }
    if ($StopAfterRun) {
        $motionArgs += "-StopAfterRun"
    }
    $summary.motion_output = Invoke-CheckedPowershell -Name "environment-depth motion proof" -Arguments $motionArgs
    $summary.motion_run_summary_path = Join-Path $motionOutDir "run-summary.json"

    $knownDistanceRunSummaryPaths = @()
    $knownDistanceRuntimeEvidencePaths = @()
    foreach ($targetDistance in $TargetDistancesMeters) {
        if ($targetDistance -le 0.0) {
            throw "Target distance must be positive: $targetDistance"
        }
        $label = Format-DistanceLabel -Value $targetDistance
        $targetOutDir = Join-Path $OutDir "known-distance-${label}m"
        $knownArgs = @(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", (Join-Path $PSScriptRoot "Invoke-NativeRendererEnvironmentDepthKnownDistanceProof.ps1"),
            "-ApkPath", $ApkPath,
            "-ProfilePath", $ProfilePath,
            "-OutDir", $targetOutDir,
            "-RunSeconds", $KnownDistanceRunSeconds.ToString(),
            "-TargetDistanceMeters", $targetDistance.ToString([System.Globalization.CultureInfo]::InvariantCulture),
            "-ToleranceMeters", $KnownDistanceToleranceMeters.ToString([System.Globalization.CultureInfo]::InvariantCulture),
            "-MinimumCenterConfidence", $MinimumCenterConfidence.ToString([System.Globalization.CultureInfo]::InvariantCulture),
            "-MinimumCenterWindowValidCount", $MinimumCenterWindowValidCount.ToString(),
            "-Serial", $Serial,
            "-SkipInstall"
        )
        if (-not [string]::IsNullOrWhiteSpace($Adb)) {
            $knownArgs += @("-Adb", $Adb)
        }
        if (-not [string]::IsNullOrWhiteSpace($AdbServerPort)) {
            $knownArgs += @("-AdbServerPort", $AdbServerPort)
        }
        if ($ClearLogcat) {
            $knownArgs += "-ClearLogcat"
        }
        if ($StopAfterRun) {
            $knownArgs += "-StopAfterRun"
        }
        $summary["known_distance_${label}m_output"] = Invoke-CheckedPowershell -Name "environment-depth known-distance ${label}m proof" -Arguments $knownArgs
        $knownDistanceRunSummaryPaths += (Join-Path $targetOutDir "run-summary.json")
        $knownDistanceRuntimeEvidencePaths += (Join-Path $targetOutDir "runtime-evidence-summary.json")
    }
    $summary.known_distance_run_summary_paths = $knownDistanceRunSummaryPaths
    $summary.known_distance_runtime_evidence_summary_paths = $knownDistanceRuntimeEvidencePaths

    $seriesArgs = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $PSScriptRoot "Test-NativeRendererEnvironmentDepthKnownDistanceSeries.ps1"),
        "-SummaryPath"
    )
    $seriesArgs += $knownDistanceRuntimeEvidencePaths
    $seriesArgs += @(
        "-MinimumDistances", "4",
        "-Out", $seriesResultPath
    )
    $summary.known_distance_series_output = Invoke-CheckedPowershell -Name "environment-depth known-distance series" -Arguments $seriesArgs
    $summary.known_distance_series_path = $seriesResultPath

    $bundleArgs = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $PSScriptRoot "Test-NativeRendererEnvironmentDepthEvidenceBundle.ps1"),
        "-MotionRunSummaryPath", $summary.motion_run_summary_path,
        "-KnownDistanceSeriesPath", $seriesResultPath,
        "-KnownDistanceRunSummaryPath"
    )
    $bundleArgs += $knownDistanceRunSummaryPaths
    $bundleArgs += @("-RequiredTargetDistancesMeters")
    foreach ($targetDistance in $TargetDistancesMeters) {
        $bundleArgs += $targetDistance.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    }
    $bundleArgs += @(
        "-DistanceMatchToleranceMeters", "0.02",
        "-MinimumMotionSamples", $MinimumHeadMotionSamples.ToString(),
        "-MinimumYawDeg", $MinimumYawDeg.ToString([System.Globalization.CultureInfo]::InvariantCulture),
        "-MinimumKnownDistanceCount", "4",
        "-Out", $bundleResultPath
    )
    if ($MinimumTranslationM -gt 0.0) {
        $bundleArgs += @("-MinimumTranslationM", $MinimumTranslationM.ToString([System.Globalization.CultureInfo]::InvariantCulture))
    }
    if ($RequireEnvironmentDepthSurfaceSupport) {
        $bundleArgs += "-RequireSurfaceSupport"
    }
    $summary.evidence_bundle_output = Invoke-CheckedPowershell -Name "environment-depth evidence bundle" -Arguments $bundleArgs
    $summary.evidence_bundle_path = $bundleResultPath
    $summary.status = "passed"
} catch {
    $summary.status = "failed"
    $summary.error = $_.Exception.Message
    throw
} finally {
    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $suiteSummaryPath
}

Write-Output "Environment-depth acceptance suite passed: $suiteSummaryPath"
