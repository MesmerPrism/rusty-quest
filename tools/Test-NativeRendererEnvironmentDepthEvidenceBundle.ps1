param(
    [string]$MotionRunSummaryPath = "",
    [string]$KnownDistanceSeriesPath = "",
    [string[]]$KnownDistanceRunSummaryPath = @(),
    [double[]]$RequiredTargetDistancesMeters = @(0.5, 1.0, 2.0, 4.0),
    [double]$DistanceMatchToleranceMeters = 0.02,
    [int]$MinimumMotionSamples = 120,
    [double]$MinimumYawDeg = 25.0,
    [double]$MinimumTranslationM = 0.0,
    [int]$MinimumKnownDistanceCount = 4,
    [switch]$RequireSurfaceSupport,
    [string]$Out = ""
)

$ErrorActionPreference = "Stop"

function Resolve-RequiredPath {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Path,
        [Parameter(Mandatory=$true)]
        [string]$Label
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "$Label path is required."
    }
    if (-not (Test-Path $Path)) {
        throw "$Label not found: $Path"
    }
    return (Resolve-Path $Path).Path
}

function Resolve-LinkedPath {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Path,
        [Parameter(Mandatory=$true)]
        [string]$BaseDir,
        [Parameter(Mandatory=$true)]
        [string]$Label
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "$Label path is empty."
    }
    $candidate = if ([System.IO.Path]::IsPathRooted($Path)) {
        $Path
    } else {
        Join-Path $BaseDir $Path
    }
    if (-not (Test-Path $candidate)) {
        throw "$Label not found: $candidate"
    }
    return (Resolve-Path $candidate).Path
}

function Read-JsonFile {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Path
    )

    return Get-Content -Raw -Path $Path | ConvertFrom-Json
}

function Get-RequiredNumber {
    param(
        [Parameter(Mandatory=$true)]
        [object]$Object,
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string]$Path
    )

    $value = $Object.$Name
    if ($null -eq $value) {
        throw "$Path is missing $Name."
    }
    if ($value -is [System.IConvertible] -and -not ($value -is [string])) {
        try {
            return [Convert]::ToDouble($value, [System.Globalization.CultureInfo]::InvariantCulture)
        } catch {
        }
    }
    $number = 0.0
    $text = [Convert]::ToString($value, [System.Globalization.CultureInfo]::InvariantCulture)
    if (-not [double]::TryParse($text, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$number)) {
        throw "$Path has non-numeric ${Name}: $value"
    }
    return $number
}

function Get-RequiredInt {
    param(
        [Parameter(Mandatory=$true)]
        [object]$Object,
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string]$Path
    )

    $number = Get-RequiredNumber -Object $Object -Name $Name -Path $Path
    return [int][Math]::Round($number)
}

function Assert-True {
    param(
        [Parameter(Mandatory=$true)]
        [bool]$Condition,
        [Parameter(Mandatory=$true)]
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Test-RequiredDistances {
    param(
        [Parameter(Mandatory=$true)]
        [object[]]$Rows,
        [Parameter(Mandatory=$true)]
        [double[]]$RequiredDistances,
        [Parameter(Mandatory=$true)]
        [double]$Tolerance,
        [Parameter(Mandatory=$true)]
        [string]$Context
    )

    foreach ($requiredDistance in $RequiredDistances) {
        $matching = @($Rows | Where-Object {
            [Math]::Abs(([double]$_.expected_m) - $requiredDistance) -le $Tolerance
        })
        if ($matching.Count -ne 1) {
            throw "$Context expected exactly one target near $requiredDistance m within $Tolerance m; found $($matching.Count)."
        }
    }
}

function Test-SmokeRunSummary {
    param(
        [Parameter(Mandatory=$true)]
        [object]$Run,
        [Parameter(Mandatory=$true)]
        [string]$Path,
        [switch]$KnownDistance
    )

    Assert-True ($Run.schema -eq "rusty.quest.native_renderer_replay_smoke_run.v1") "$Path is not a native renderer replay smoke run summary."
    Assert-True ($Run.status -eq "passed") "$Path did not pass."
    Assert-True ($Run.evidence_mode -eq "EnvironmentDepthParticles") "$Path was not captured in EnvironmentDepthParticles evidence mode."
    Assert-True ($Run.adb_scope -eq "device-scoped-adb") "$Path did not use device-scoped ADB."
    Assert-True ($Run.adb_serial_required -eq $true) "$Path did not require an explicit serial."
    Assert-True ($Run.logcat_scope -eq "pid-scoped-device-logcat") "$Path did not use pid-scoped logcat evidence."
    Assert-True ($Run.environment_depth_particles_required -eq $true) "$Path did not require environment-depth particles."
    Assert-True (-not [string]::IsNullOrWhiteSpace($Run.permission_pregrant_path)) "$Path is missing permission_pregrant_path."
    Assert-True (-not [string]::IsNullOrWhiteSpace($Run.runtime_evidence_summary_path)) "$Path is missing runtime_evidence_summary_path."

    if ($KnownDistance) {
        Assert-True ($Run.environment_depth_known_distance_required -eq $true) "$Path was not a known-distance environment-depth run."
        Assert-True ((Get-RequiredNumber -Object $Run -Name "expected_environment_depth_center_meters" -Path $Path) -gt 0.0) "$Path has no positive expected known-distance target."
    } else {
        Assert-True ((Get-RequiredInt -Object $Run -Name "minimum_environment_depth_head_motion_samples" -Path $Path) -gt 0) "$Path was not configured as a movement-required run."
    }
}

$motionRunPath = Resolve-RequiredPath -Path $MotionRunSummaryPath -Label "Motion run summary"
$seriesPath = Resolve-RequiredPath -Path $KnownDistanceSeriesPath -Label "Known-distance series result"

if ($KnownDistanceRunSummaryPath.Count -lt $MinimumKnownDistanceCount) {
    throw "Evidence bundle needs at least $MinimumKnownDistanceCount known-distance run summaries; found $($KnownDistanceRunSummaryPath.Count)."
}
if ($RequiredTargetDistancesMeters.Count -lt $MinimumKnownDistanceCount) {
    throw "RequiredTargetDistancesMeters must name at least $MinimumKnownDistanceCount target distances."
}
if ($DistanceMatchToleranceMeters -le 0.0) {
    throw "DistanceMatchToleranceMeters must be positive."
}

$motionRun = Read-JsonFile -Path $motionRunPath
Test-SmokeRunSummary -Run $motionRun -Path $motionRunPath
$motionRunDir = Split-Path -Parent $motionRunPath
$motionEvidencePath = Resolve-LinkedPath -Path $motionRun.runtime_evidence_summary_path -BaseDir $motionRunDir -Label "Motion runtime evidence summary"
$motionEvidence = Read-JsonFile -Path $motionEvidencePath

Assert-True ($motionEvidence.schema -eq "rusty.quest.native_renderer_runtime_evidence.v1") "$motionEvidencePath is not a runtime evidence summary."
Assert-True ($motionEvidence.environment_depth_particles_checked -eq $true) "$motionEvidencePath did not check environment-depth particles."
Assert-True ((Get-RequiredInt -Object $motionEvidence -Name "environment_depth_particle_count" -Path $motionEvidencePath) -gt 0) "$motionEvidencePath reports no particles."
Assert-True ((Get-RequiredInt -Object $motionEvidence -Name "environment_depth_particle_source_depth_samples" -Path $motionEvidencePath) -gt 0) "$motionEvidencePath reports no source depth samples."
$motionSamples = Get-RequiredInt -Object $motionEvidence -Name "environment_depth_head_motion_samples" -Path $motionEvidencePath
$maxYawDeg = Get-RequiredNumber -Object $motionEvidence -Name "environment_depth_head_motion_max_yaw_delta_deg" -Path $motionEvidencePath
$maxTranslationM = Get-RequiredNumber -Object $motionEvidence -Name "environment_depth_head_motion_max_translation_delta_m" -Path $motionEvidencePath
Assert-True ($motionSamples -ge $MinimumMotionSamples) "$motionEvidencePath has $motionSamples motion samples; expected at least $MinimumMotionSamples."
Assert-True ($maxYawDeg -ge $MinimumYawDeg) "$motionEvidencePath has max yaw $maxYawDeg deg; expected at least $MinimumYawDeg."
if ($MinimumTranslationM -gt 0.0) {
    Assert-True ($maxTranslationM -ge $MinimumTranslationM) "$motionEvidencePath has max translation $maxTranslationM m; expected at least $MinimumTranslationM."
}
if ($RequireSurfaceSupport) {
    Assert-True ($motionEvidence.environment_depth_surface_support_checked -eq $true) "$motionEvidencePath did not check surface support."
    Assert-True ($motionEvidence.environment_depth_surface_support_status -eq "enforced-local-depth-neighborhood-component-local-hint") "$motionEvidencePath did not report enforced local surface support."
    Assert-True ((Get-RequiredInt -Object $motionEvidence -Name "environment_depth_surface_supported_cells" -Path $motionEvidencePath) -gt 0) "$motionEvidencePath reports no supported surface cells."
    Assert-True ((Get-RequiredInt -Object $motionEvidence -Name "environment_depth_surface_confirmed_component_cells" -Path $motionEvidencePath) -gt 0) "$motionEvidencePath reports no confirmed component cells."
}

$series = Read-JsonFile -Path $seriesPath
Assert-True ($series.schema -eq "rusty.quest.environment_depth_known_distance_series.v1") "$seriesPath is not a known-distance series result."
Assert-True ($series.status -eq "passed") "$seriesPath did not pass."
Assert-True ((Get-RequiredInt -Object $series -Name "summary_count" -Path $seriesPath) -ge $MinimumKnownDistanceCount) "$seriesPath has too few summaries."
Assert-True (@("increasing", "decreasing") -contains $series.raw_center_d16_direction) "$seriesPath has invalid raw_center_d16_direction."
$seriesRows = @($series.rows)
Assert-True ($seriesRows.Count -ge $MinimumKnownDistanceCount) "$seriesPath has too few rows."
Test-RequiredDistances -Rows $seriesRows -RequiredDistances $RequiredTargetDistancesMeters -Tolerance $DistanceMatchToleranceMeters -Context "Known-distance series"

$knownDistanceRuns = @()
foreach ($runSummary in $KnownDistanceRunSummaryPath) {
    $runPath = Resolve-RequiredPath -Path $runSummary -Label "Known-distance run summary"
    $run = Read-JsonFile -Path $runPath
    Test-SmokeRunSummary -Run $run -Path $runPath -KnownDistance
    $runDir = Split-Path -Parent $runPath
    $evidencePath = Resolve-LinkedPath -Path $run.runtime_evidence_summary_path -BaseDir $runDir -Label "Known-distance runtime evidence summary"
    $evidence = Read-JsonFile -Path $evidencePath
    Assert-True ($evidence.schema -eq "rusty.quest.native_renderer_runtime_evidence.v1") "$evidencePath is not a runtime evidence summary."
    Assert-True ($evidence.environment_depth_known_distance_required -eq $true) "$evidencePath was not produced by the known-distance gate."
    $knownDistanceRuns += [pscustomobject]@{
        run_summary_path = $runPath
        runtime_evidence_summary_path = $evidencePath
        expected_m = Get-RequiredNumber -Object $evidence -Name "environment_depth_expected_center_meters" -Path $evidencePath
        reconstructed_m = Get-RequiredNumber -Object $evidence -Name "environment_depth_center_reconstructed_meters" -Path $evidencePath
        raw_center_d16 = Get-RequiredInt -Object $evidence -Name "environment_depth_raw_center_d16" -Path $evidencePath
        center_error_m = Get-RequiredNumber -Object $evidence -Name "environment_depth_center_error_meters" -Path $evidencePath
    }
}
Test-RequiredDistances -Rows $knownDistanceRuns -RequiredDistances $RequiredTargetDistancesMeters -Tolerance $DistanceMatchToleranceMeters -Context "Known-distance run summaries"

$result = [ordered]@{
    schema = "rusty.quest.environment_depth_evidence_bundle.v1"
    status = "passed"
    motion_run_summary_path = $motionRunPath
    motion_runtime_evidence_summary_path = $motionEvidencePath
    motion_samples = $motionSamples
    motion_max_yaw_delta_deg = $maxYawDeg
    motion_max_translation_delta_m = $maxTranslationM
    known_distance_series_path = $seriesPath
    known_distance_count = $knownDistanceRuns.Count
    known_distance_raw_center_d16_direction = $series.raw_center_d16_direction
    required_target_distances_meters = $RequiredTargetDistancesMeters
    require_surface_support = [bool]$RequireSurfaceSupport
    human_device_visual_acceptance_required = $true
    known_distance_runs = $knownDistanceRuns
}

if (-not [string]::IsNullOrWhiteSpace($Out)) {
    $outDir = Split-Path -Parent $Out
    if (-not [string]::IsNullOrWhiteSpace($outDir)) {
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    }
    $result | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $Out
}

Write-Output "Environment-depth evidence bundle validation passed ($motionSamples motion samples, $($knownDistanceRuns.Count) known-distance runs)."
