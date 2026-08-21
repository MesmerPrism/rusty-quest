[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$CaptureDirectory,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [switch]$AllowIncomplete
)

$ErrorActionPreference = "Stop"

function Read-JsonObject {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing ${Label}: $Path"
    }
    try {
        return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json -AsHashtable -Depth 32
    } catch {
        throw "Malformed ${Label}: $Path"
    }
}

function Get-Int64Field {
    param([System.Collections.IDictionary]$Fields, [string]$Name)
    if ($null -eq $Fields -or -not $Fields.Contains($Name) -or $null -eq $Fields[$Name]) {
        return $null
    }
    try {
        return [Int64]$Fields[$Name]
    } catch {
        return $null
    }
}

function Get-Distribution {
    param([System.Collections.Generic.List[long]]$Values)
    if ($Values.Count -eq 0) {
        return [ordered]@{ count = 0; minimum = $null; median = $null; p95 = $null; maximum = $null; mean = $null }
    }
    $orderedValues = [long[]]@($Values | Sort-Object)
    $count = $orderedValues.Length
    $medianIndex = [Math]::Floor(($count - 1) * 0.5)
    $p95Index = [Math]::Floor(($count - 1) * 0.95)
    $sum = [double]0
    foreach ($value in $orderedValues) {
        $sum += $value
    }
    return [ordered]@{
        count = $count
        minimum = $orderedValues[0]
        median = $orderedValues[[int]$medianIndex]
        p95 = $orderedValues[[int]$p95Index]
        maximum = $orderedValues[$count - 1]
        mean = [Math]::Round($sum / $count, 3)
    }
}

function Add-Interval {
    param(
        [System.Collections.Generic.List[long]]$Values,
        [object]$Previous,
        [object]$Current
    )
    if ($null -ne $Previous -and $null -ne $Current -and [Int64]$Current -gt [Int64]$Previous) {
        $Values.Add(([Int64]$Current) - ([Int64]$Previous))
    }
}

$capturePath = [System.IO.Path]::GetFullPath($CaptureDirectory)
$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
if (-not (Test-Path -LiteralPath $capturePath -PathType Container)) {
    throw "Capture directory does not exist: $capturePath"
}
if ($outputPath -eq $capturePath) {
    throw "Output directory must differ from the capture directory"
}

$manifest = Read-JsonObject (Join-Path $capturePath "capture_manifest.json") "capture manifest"
$receipt = Read-JsonObject (Join-Path $capturePath "capture_receipt.json") "capture receipt"
if ($manifest["schema"] -ne "rusty.quest.breath_source_capture_manifest.v1") {
    throw "Unexpected capture manifest schema"
}
if ($receipt["schema"] -ne "rusty.quest.breath_source_capture_receipt.v1") {
    throw "Unexpected capture receipt schema"
}
if (-not $AllowIncomplete -and -not [bool]$receipt["complete"]) {
    throw "Capture receipt is incomplete; rerun with -AllowIncomplete only for diagnosis"
}

$samplesPath = Join-Path $capturePath "breath_source_samples.jsonl"
if (-not (Test-Path -LiteralPath $samplesPath -PathType Leaf)) {
    throw "Missing capture samples: $samplesPath"
}

$recordCounts = [ordered]@{}
$accSampleIntervals = [System.Collections.Generic.List[long]]::new()
$accFrameIntervals = [System.Collections.Generic.List[long]]::new()
$accSampleToFrameReceipt = [System.Collections.Generic.List[long]]::new()
$accFrameToJni = [System.Collections.Generic.List[long]]::new()
$ecgSampleIntervals = [System.Collections.Generic.List[long]]::new()
$controllerPoseIntervals = [System.Collections.Generic.List[long]]::new()
$driverLatency = [System.Collections.Generic.List[long]]::new()
$timeline = [System.Collections.Generic.List[object]]::new()
$lastAccSampleTime = $null
$lastAccFrameTime = $null
$lastEcgSampleTime = $null
$lastControllerPoseTime = $null
$frameReceiptById = @{}

$lineNumber = 0
foreach ($line in Get-Content -LiteralPath $samplesPath) {
    $lineNumber += 1
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }
    try {
        $record = $line | ConvertFrom-Json -AsHashtable -Depth 32
    } catch {
        throw "Malformed capture row $lineNumber"
    }
    if ($record["schema"] -ne "rusty.quest.breath_source_capture.v1") {
        throw "Unexpected capture row schema at line $lineNumber"
    }
    $kind = [string]$record["kind"]
    if ([string]::IsNullOrWhiteSpace($kind) -or $record["fields"] -isnot [System.Collections.IDictionary]) {
        throw "Malformed capture row shape at line $lineNumber"
    }
    if ($recordCounts.Contains($kind)) {
        $recordCounts[$kind] = [int64]$recordCounts[$kind] + 1
    } else {
        $recordCounts[$kind] = [int64]1
    }
    $fields = [System.Collections.IDictionary]$record["fields"]
    $timelineTime = $null

    switch ($kind) {
        "polar_acc_frame" {
            $frameId = Get-Int64Field $fields "frame_sequence_id"
            $frameTime = Get-Int64Field $fields "host_receipt_time_ns"
            Add-Interval $accFrameIntervals $lastAccFrameTime $frameTime
            if ($frameTime -ne $null) { $lastAccFrameTime = $frameTime }
            if ($frameId -ne $null -and $frameTime -ne $null) { $frameReceiptById[$frameId] = $frameTime }
            $timelineTime = $frameTime
        }
        "polar_acc_sample" {
            $sampleTime = Get-Int64Field $fields "sample_host_time_ns"
            $frameTime = Get-Int64Field $fields "frame_host_receipt_time_ns"
            $jniTime = Get-Int64Field $fields "jni_submit_time_ns"
            Add-Interval $accSampleIntervals $lastAccSampleTime $sampleTime
            if ($sampleTime -ne $null) { $lastAccSampleTime = $sampleTime }
            if ($sampleTime -ne $null -and $frameTime -ne $null -and $frameTime -ge $sampleTime) {
                $accSampleToFrameReceipt.Add($frameTime - $sampleTime)
            }
            if ($frameTime -ne $null -and $jniTime -ne $null -and $jniTime -ge $frameTime) {
                $accFrameToJni.Add($jniTime - $frameTime)
            }
            $timelineTime = $sampleTime
        }
        "polar_ecg_sample" {
            $sampleTime = Get-Int64Field $fields "sample_host_time_ns"
            Add-Interval $ecgSampleIntervals $lastEcgSampleTime $sampleTime
            if ($sampleTime -ne $null) { $lastEcgSampleTime = $sampleTime }
            $timelineTime = $sampleTime
        }
        "controller_pose" {
            $poseTimeMicros = Get-Int64Field $fields "observed_at_micros"
            if ($poseTimeMicros -ne $null) {
                $poseTime = $poseTimeMicros * 1000
                Add-Interval $controllerPoseIntervals $lastControllerPoseTime $poseTime
                $lastControllerPoseTime = $poseTime
                $timelineTime = $poseTime
            }
        }
        "breath_assessment" {
            $assessmentTimeMicros = Get-Int64Field $fields "observed_at_micros"
            if ($assessmentTimeMicros -ne $null) { $timelineTime = $assessmentTimeMicros * 1000 }
        }
        "driver_apply" {
            $driverTimeMicros = Get-Int64Field $fields "observed_at_micros"
            $sampleTimeMicros = Get-Int64Field $fields "source_sampled_at_micros"
            if ($driverTimeMicros -ne $null) {
                $timelineTime = $driverTimeMicros * 1000
                if ($sampleTimeMicros -ne $null -and $driverTimeMicros -ge $sampleTimeMicros) {
                    $driverLatency.Add(($driverTimeMicros - $sampleTimeMicros) * 1000)
                }
            }
        }
        default {
            $timelineTime = Get-Int64Field $fields "host_time_ns"
        }
    }

    if ($timelineTime -ne $null) {
        $timeline.Add([pscustomobject]@{
            time_ns = $timelineTime
            kind = $kind
            frame_sequence_id = Get-Int64Field $fields "frame_sequence_id"
            source = $fields["source"]
            mapping = $fields["mapping"]
            value01 = $fields["value01"]
            phase = $fields["phase"]
            x_mg = if ($fields["xyz_mg"] -and $fields["xyz_mg"].Count -ge 1) { $fields["xyz_mg"][0] } else { $null }
            y_mg = if ($fields["xyz_mg"] -and $fields["xyz_mg"].Count -ge 2) { $fields["xyz_mg"][1] } else { $null }
            z_mg = if ($fields["xyz_mg"] -and $fields["xyz_mg"].Count -ge 3) { $fields["xyz_mg"][2] } else { $null }
            microvolts = $fields["microvolts"]
        })
    }
}

New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
$report = [ordered]@{
    schema = "rusty.quest.breath_source_capture_analysis.v1"
    capture_directory = $capturePath
    source_capture_schema = $manifest["capture_schema"]
    session_id = $manifest["session_id"]
    capture_generation = $manifest["generation"]
    receipt_complete = [bool]$receipt["complete"]
    receipt = [ordered]@{
        enqueued_records = $receipt["enqueued_records"]
        written_records = $receipt["written_records"]
        dropped_records = $receipt["dropped_records"]
        write_failures = $receipt["write_failures"]
    }
    record_counts = $recordCounts
    cadence_ns = [ordered]@{
        polar_acc_sample_interval = Get-Distribution $accSampleIntervals
        polar_acc_frame_interval = Get-Distribution $accFrameIntervals
        polar_acc_sample_to_frame_receipt = Get-Distribution $accSampleToFrameReceipt
        polar_acc_frame_to_jni_submit = Get-Distribution $accFrameToJni
        polar_ecg_sample_interval = Get-Distribution $ecgSampleIntervals
        controller_pose_interval = Get-Distribution $controllerPoseIntervals
        assessment_source_sample_to_driver_apply = Get-Distribution $driverLatency
    }
    interpretation = [ordered]@{
        low_latency_smooth = "Uses newest received ACC target and frame-time smoothing; it cannot reconstruct samples not yet received."
        timestamp_faithful = "Uses a short timestamp buffer and real-sample interpolation; it trades latency for sample-path fidelity."
        raw_replay = "The timeline retains raw source records and driver/assessment rows for host-only algorithm comparison."
    }
}

$reportPath = Join-Path $outputPath "breath_capture_analysis.json"
$timelinePath = Join-Path $outputPath "breath_capture_timeline.csv"
$report | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $reportPath -Encoding utf8NoBOM
$timeline | Sort-Object time_ns, kind | Export-Csv -LiteralPath $timelinePath -NoTypeInformation -Encoding utf8NoBOM

[pscustomobject]@{
    status = "analyzed"
    report = $reportPath
    timeline = $timelinePath
    record_count = ($recordCounts.Values | Measure-Object -Sum).Sum
    receipt_complete = [bool]$receipt["complete"]
} | ConvertTo-Json -Depth 8
