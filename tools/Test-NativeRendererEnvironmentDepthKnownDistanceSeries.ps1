param(
    [string[]]$SummaryPath = @(),
    [string]$SummaryGlob = "",
    [int]$MinimumDistances = 4,
    [string]$Out = ""
)

$ErrorActionPreference = "Stop"

function Resolve-SummaryPaths {
    param(
        [string[]]$PathValues,
        [string]$GlobValue
    )

    $paths = @()
    foreach ($path in $PathValues) {
        if ([string]::IsNullOrWhiteSpace($path)) {
            continue
        }
        if (-not (Test-Path $path)) {
            throw "Known-distance summary not found: $path"
        }
        $paths += (Resolve-Path $path).Path
    }
    if (-not [string]::IsNullOrWhiteSpace($GlobValue)) {
        $matches = Get-ChildItem -Path $GlobValue -File -ErrorAction SilentlyContinue
        foreach ($match in $matches) {
            $paths += $match.FullName
        }
    }
    return @($paths | Sort-Object -Unique)
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
        throw "Known-distance summary $Path is missing $Name."
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
        throw "Known-distance summary $Path has non-numeric ${Name}: $value"
    }
    return $number
}

$resolvedSummaries = Resolve-SummaryPaths -PathValues $SummaryPath -GlobValue $SummaryGlob
if ($resolvedSummaries.Count -lt $MinimumDistances) {
    throw "Known-distance series needs at least $MinimumDistances summaries; found $($resolvedSummaries.Count)."
}

$rows = @()
foreach ($path in $resolvedSummaries) {
    $summary = Get-Content -Raw -Path $path | ConvertFrom-Json
    if ($summary.environment_depth_known_distance_required -ne $true) {
        throw "Known-distance summary $path was not produced by the known-distance evidence gate."
    }
    $expected = Get-RequiredNumber -Object $summary -Name "environment_depth_expected_center_meters" -Path $path
    $reconstructed = Get-RequiredNumber -Object $summary -Name "environment_depth_center_reconstructed_meters" -Path $path
    $tolerance = Get-RequiredNumber -Object $summary -Name "environment_depth_center_tolerance_meters" -Path $path
    $errorMeters = Get-RequiredNumber -Object $summary -Name "environment_depth_center_error_meters" -Path $path
    $rawCenterD16 = Get-RequiredNumber -Object $summary -Name "environment_depth_raw_center_d16" -Path $path
    if ($expected -le 0.0 -or $reconstructed -le 0.0 -or $tolerance -le 0.0) {
        throw "Known-distance summary $path has non-positive expected/reconstructed/tolerance values."
    }
    if ($errorMeters -gt $tolerance) {
        throw "Known-distance summary $path reports center error $errorMeters m above tolerance $tolerance m."
    }
    if ($rawCenterD16 -le 0.0 -or $rawCenterD16 -gt 65535.0) {
        throw "Known-distance summary $path has raw center D16 outside 1..65535: $rawCenterD16"
    }
    $rows += [pscustomobject]@{
        path = $path
        expected_m = $expected
        reconstructed_m = $reconstructed
        tolerance_m = $tolerance
        error_m = $errorMeters
        raw_center_d16 = [int][Math]::Round($rawCenterD16)
    }
}

$rows = @($rows | Sort-Object expected_m)
for ($index = 1; $index -lt $rows.Count; $index++) {
    if ($rows[$index].expected_m -le $rows[$index - 1].expected_m) {
        throw "Known-distance expected target distances must be strictly increasing after sorting."
    }
    if ($rows[$index].reconstructed_m -le $rows[$index - 1].reconstructed_m) {
        throw "Known-distance reconstructed meters are not strictly increasing between $($rows[$index - 1].path) and $($rows[$index].path)."
    }
}

$rawDirection = 0
for ($index = 1; $index -lt $rows.Count; $index++) {
    $delta = $rows[$index].raw_center_d16 - $rows[$index - 1].raw_center_d16
    if ($delta -eq 0) {
        throw "Known-distance raw center D16 did not change between $($rows[$index - 1].path) and $($rows[$index].path)."
    }
    $direction = if ($delta -gt 0) { 1 } else { -1 }
    if ($rawDirection -eq 0) {
        $rawDirection = $direction
    } elseif ($direction -ne $rawDirection) {
        throw "Known-distance raw center D16 is not monotonic across the series."
    }
}

$maxError = ($rows | Measure-Object -Property error_m -Maximum).Maximum
$result = [ordered]@{
    schema = "rusty.quest.environment_depth_known_distance_series.v1"
    status = "passed"
    summary_count = $rows.Count
    minimum_distances = $MinimumDistances
    raw_center_d16_direction = if ($rawDirection -gt 0) { "increasing" } else { "decreasing" }
    max_center_error_meters = $maxError
    rows = $rows
}

if (-not [string]::IsNullOrWhiteSpace($Out)) {
    $outDir = Split-Path -Parent $Out
    if (-not [string]::IsNullOrWhiteSpace($outDir)) {
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    }
    $result | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $Out
}

Write-Output "Known-distance environment-depth series validation passed ($($rows.Count) summaries, raw D16 $($result.raw_center_d16_direction))."
