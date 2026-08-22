[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._:-]+$')]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^io\.github\.mesmerprism\.rustyquest\.native_renderer\.[a-z0-9_.]+$')]
    [string]$PackageName,

    [Parameter(Mandatory = $true)]
    [ValidateSet(
        'scan', 'connect', 'start_acc', 'start_ecg', 'start_all', 'stop_pmd',
        'stop_all', 'start_capture', 'stop_capture', 'presentation_low_latency',
        'presentation_timestamp_faithful', 'disconnect', 'reset', 'status'
    )]
    [string]$Command,

    [ValidateRange(1, 30)]
    [int]$TimeoutSeconds = 8
)

$ErrorActionPreference = 'Stop'
$adb = (Get-Command adb -ErrorAction Stop).Source
$action = 'io.github.mesmerprism.rustyquest.native_renderer.action.POLAR_SENSOR_RUNTIME_COMMAND'
$receiverClass = 'io.github.mesmerprism.rustyquest.native_renderer.PolarSensorCommandReceiver'
$component = "$PackageName/$receiverClass"
$token = [Guid]::NewGuid().ToString('N')
$receiptPath = 'files/polar_sensor_operator_status.json'

$deviceState = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $deviceState -ne 'device') {
    throw "Serial-scoped ADB target is not ready: $Serial ($deviceState)"
}

# This is an explicit component broadcast. It never starts ControlPanelActivity,
# NativeActivity, or another OpenXR/session owner.
$arguments = @(
    '-s', $Serial,
    'shell', 'am', 'broadcast',
    '-n', $component,
    '-a', $action,
    '--es', 'polar_sensor_runtime_command', $Command,
    '--es', 'polar_sensor_runtime_command_token', $token
)
$dispatchOutput = (& $adb @arguments 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Fixed headless Polar operator broadcast failed: $dispatchOutput"
}

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
$receipt = $null
do {
    $raw = (& $adb -s $Serial exec-out run-as $PackageName cat $receiptPath 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($raw)) {
        try {
            $candidate = $raw | ConvertFrom-Json -Depth 32
            if ([string]$candidate.schema -eq 'rusty.quest.native_renderer.polar_sensor_operator_status.v2' -and
                [string]$candidate.token -eq $token) {
                $receipt = $candidate
                break
            }
        } catch {
        }
    }
    Start-Sleep -Milliseconds 100
} while ([DateTimeOffset]::UtcNow -lt $deadline)

if ($null -eq $receipt) {
    throw "Timed out waiting for correlated headless Polar receipt token $token"
}
if ([string]$receipt.command_origin -ne 'cli-receiver') {
    throw "Polar command receipt did not prove the headless receiver route: $([string]$receipt.command_origin)"
}
if ([string]$receipt.dispatch_status -ne 'accepted') {
    throw "Polar command was not accepted: $([string]$receipt.reason_code)"
}

[pscustomobject]@{
    schema = 'rusty.quest.native_renderer.polar_sensor_operator_invocation.v2'
    serial = $Serial
    package = $PackageName
    component = $component
    token = $token
    command = $Command
    dispatch_transport = 'serial-scoped-fixed-adb-broadcast'
    foreground_activity_changed = $false
    screenshot_required = $false
    app_receipt = $receipt
    dispatch_output = $dispatchOutput
} | ConvertTo-Json -Depth 32
