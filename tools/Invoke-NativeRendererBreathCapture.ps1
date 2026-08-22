[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._:-]+$')]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^io\.github\.mesmerprism\.rustyquest\.native_renderer\.[a-z0-9_.]+$')]
    [string]$PackageName,

    [Parameter(Mandatory = $true)]
    [ValidateSet('ConnectivityPreflight', 'ControllerPreflight', 'FullRecording')]
    [string]$Mode,

    [ValidateRange(6, 30)]
    [int]$ControllerObservationSeconds = 10,

    [ValidateRange(120, 180)]
    [int]$FullRecordingTimeoutSeconds = 145
)

$ErrorActionPreference = 'Stop'
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$operatorPath = Join-Path $PSScriptRoot 'Invoke-NativeRendererPolarOperator.ps1'
$adb = (Get-Command adb -ErrorAction Stop).Source

function Invoke-PolarOperator {
    param([Parameter(Mandatory = $true)][string]$Command)
    $raw = & $operatorPath -Serial $Serial -PackageName $PackageName -Command $Command
    return ($raw | ConvertFrom-Json -Depth 32)
}

function Get-FreshPolarStatus {
    $result = Invoke-PolarOperator -Command 'status'
    if ($null -eq $result.app_receipt.polar_status) {
        throw 'The app did not provide a current Polar status row.'
    }
    return $result.app_receipt.polar_status
}

function Wait-PolarStatus {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Condition,
        [Parameter(Mandatory = $true)][string]$Description,
        [ValidateRange(1, 60)][int]$TimeoutSeconds = 20
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $status = Get-FreshPolarStatus
        if (& $Condition $status) {
            return $status
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Description."
}

function Assert-ExactlyOneCandidate {
    param([Parameter(Mandatory = $true)]$Status)
    $count = [int]$Status.candidate_count
    if ($count -ne 1 -or [string]$Status.selected_device_instance_id -eq 'none') {
        throw "Headless connection requires exactly one compatible candidate; observed $count."
    }
}

function Start-HeadlessPolarStreams {
    $initial = Get-FreshPolarStatus
    if ([string]$initial.ble_runtime.bluetooth_adapter_state -ne 'on') {
        throw 'Bluetooth is not on; this tool will not change a system setting.'
    }
    if (-not [bool]$initial.ble_runtime.runtime_permission_ready) {
        throw 'BLE permissions are not granted. Attach the optional panel to answer the normal in-app prompt.'
    }

    Invoke-PolarOperator -Command 'scan' | Out-Null
    $scanned = Wait-PolarStatus -Description 'headless Polar scan completion' -TimeoutSeconds 20 -Condition {
        param($status)
        -not [bool]$status.scanning
    }
    Assert-ExactlyOneCandidate $scanned

    Invoke-PolarOperator -Command 'connect' | Out-Null
    Wait-PolarStatus -Description 'Polar GATT and PMD readiness' -TimeoutSeconds 25 -Condition {
        param($status)
        [bool]$status.connected -and [bool]$status.pmd_ready
    } | Out-Null

    Invoke-PolarOperator -Command 'start_all' | Out-Null
    $first = Wait-PolarStatus -Description 'parallel ACC and ECG PMD streams' -TimeoutSeconds 25 -Condition {
        param($status)
        [bool]$status.acc_pmd_running -and [bool]$status.ecg_pmd_running
    }
    Start-Sleep -Seconds 3
    $second = Get-FreshPolarStatus
    if ([int64]$second.acc_samples -le [int64]$first.acc_samples -or
        [int64]$second.ecg_samples -le [int64]$first.ecg_samples) {
        throw 'ACC and ECG PMD counters did not advance after accepted parallel start.'
    }
    if ([int64]$second.heart_rate_events_observed -lt 1 -or
        [int64]$second.rr_intervals_observed -lt 1) {
        throw 'HR/RR notifications did not produce current evidence during the bounded preflight.'
    }
    if ([bool]$second.rr_consumed_by_breath -or -not [bool]$second.acc_direct_same_process) {
        throw 'Polar status violates RR isolation or direct ACC ownership.'
    }
    return $second
}

function Read-ActiveCaptureRows {
    param([Parameter(Mandatory = $true)][string]$SessionId)
    if ($SessionId -notmatch '^breath_capture_[0-9]+$') {
        throw "Invalid app-owned capture session id: $SessionId"
    }
    $path = "files/breath_source_captures/$SessionId/breath_source_samples.partial.jsonl"
    $raw = (& $adb -s $Serial exec-out run-as $PackageName cat $path 2>$null | Out-String)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($raw)) {
        throw 'The active capture does not expose a readable checkpoint yet.'
    }
    $rows = @()
    foreach ($line in $raw -split "`r?`n") {
        if (-not [string]::IsNullOrWhiteSpace($line)) {
            $rows += ($line | ConvertFrom-Json -Depth 24)
        }
    }
    return $rows
}

function Assert-ControllerRows {
    param([Parameter(Mandatory = $true)]$Rows)
    $poseRows = @($Rows | Where-Object { $_.kind -eq 'controller_pose' })
    $stickRows = @($Rows | Where-Object { $_.kind -eq 'controller_right_thumbstick' })
    if ($poseRows.Count -lt 2 -or $stickRows.Count -lt 2) {
        throw 'Capture preflight lacks sufficient controller pose or right-thumbstick rows.'
    }
    if (@($poseRows | Where-Object { $_.fields.tracked -eq $true -and $_.fields.action_active -eq $true }).Count -lt 1) {
        throw 'Capture preflight has no current tracked, action-active controller pose row.'
    }
    if (@($stickRows | Where-Object { $_.fields.action_active -eq $true }).Count -lt 1) {
        throw 'Capture preflight has no action-active right-thumbstick row.'
    }
}

function Read-FinalCaptureReceipt {
    param([Parameter(Mandatory = $true)][string]$SessionId)
    if ($SessionId -notmatch '^breath_capture_[0-9]+$') {
        throw "Invalid app-owned capture session id: $SessionId"
    }
    $path = "files/breath_source_captures/$SessionId/capture_receipt.json"
    $raw = (& $adb -s $Serial exec-out run-as $PackageName cat $path 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($raw)) {
        throw 'The finalized capture receipt is not readable through the app-private diagnostic route.'
    }
    $receipt = $raw | ConvertFrom-Json -Depth 32
    if ([string]$receipt.schema -ne 'rusty.quest.breath_source_capture_receipt.v1' -or
        [string]$receipt.session_id -ne $SessionId -or -not [bool]$receipt.complete) {
        throw 'The finalized capture receipt is missing, mismatched, or incomplete.'
    }
    foreach ($kind in @(
        'controller_pose', 'controller_right_thumbstick', 'polar_hr', 'polar_rr',
        'polar_acc_frame', 'polar_acc_sample', 'polar_ecg_frame', 'polar_ecg_sample'
    )) {
        if ([int64]$receipt.record_counts.$kind -lt 1) {
            throw "Finalized capture is missing required stream rows: $kind"
        }
    }
    return $receipt
}

function Start-And-CheckControllerCapture {
    $start = Invoke-PolarOperator -Command 'start_capture'
    $sessionId = [string]$start.app_receipt.capture_session_id
    if ([string]$start.app_receipt.effect_status -ne 'started' -or $sessionId -eq 'none') {
        throw "Capture did not start: $([string]$start.app_receipt.effect_status)"
    }
    Start-Sleep -Seconds $ControllerObservationSeconds
    $active = Get-FreshPolarStatus
    if (-not [bool]$active.capture.active) {
        throw 'Capture ended before controller preflight could observe it.'
    }
    $rows = Read-ActiveCaptureRows -SessionId $sessionId
    Assert-ControllerRows $rows
    return [pscustomobject]@{
        session_id = $sessionId
        active_status = $active
        controller_pose_rows = @($rows | Where-Object { $_.kind -eq 'controller_pose' }).Count
        controller_right_thumbstick_rows = @($rows | Where-Object { $_.kind -eq 'controller_right_thumbstick' }).Count
    }
}

$connectivity = Start-HeadlessPolarStreams
if ($Mode -eq 'ConnectivityPreflight') {
    [pscustomobject]@{
        schema = 'rusty.quest.native_renderer.breath_capture_operator_result.v1'
        mode = $Mode
        panel_foregrounded = $false
        connectivity = $connectivity
    } | ConvertTo-Json -Depth 32
    return
}

$controller = Start-And-CheckControllerCapture
if ($Mode -eq 'ControllerPreflight') {
    # A short preflight is intentionally incomplete and is never eligible as a recording.
    Invoke-PolarOperator -Command 'stop_capture' | Out-Null
    [pscustomobject]@{
        schema = 'rusty.quest.native_renderer.breath_capture_operator_result.v1'
        mode = $Mode
        panel_foregrounded = $false
        connectivity = $connectivity
        controller = $controller
        capture_eligibility = 'diagnostic-incomplete-not-recording'
    } | ConvertTo-Json -Depth 32
    return
}

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($FullRecordingTimeoutSeconds)
do {
    $status = Get-FreshPolarStatus
    if (-not [bool]$status.capture.active) {
        break
    }
    Start-Sleep -Seconds 1
} while ([DateTimeOffset]::UtcNow -lt $deadline)
if ([bool]$status.capture.active) {
    throw 'The fixed full recording did not finalize within its bounded timeout.'
}
$finalReceipt = Read-FinalCaptureReceipt -SessionId $controller.session_id
[pscustomobject]@{
    schema = 'rusty.quest.native_renderer.breath_capture_operator_result.v1'
    mode = $Mode
    panel_foregrounded = $false
    connectivity = $connectivity
    controller = $controller
    completion = $status.capture
    finalized_capture_receipt = $finalReceipt
} | ConvertTo-Json -Depth 32
