[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._:-]+$')]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^io\.github\.mesmerprism\.rustyquest\.native_renderer\.[a-z0-9_.]+$')]
    [string]$PackageName,

    [Parameter(Mandatory = $true)]
    [ValidateSet('select', 'start_calibration', 'cancel', 'reset', 'disable', 'status')]
    [string]$Operation,

    [ValidateSet('controller', 'polar-acc')]
    [string]$Source = 'controller',

    [ValidateSet('volume', 'state')]
    [string]$Mapping = 'volume',

    [ValidateSet('dynamic-axis', 'fixed-orientation')]
    [string]$ControllerProjection = 'dynamic-axis',

    [ValidateSet('xz', '3d')]
    [string]$PolarProjection = 'xz',

    [switch]$Inverted,
    [switch]$ReturnToImmersive,

    [ValidateRange(1, 30)]
    [int]$TimeoutSeconds = 8
)

$ErrorActionPreference = 'Stop'
$adbCommand = Get-Command adb -ErrorAction Stop
$adb = $adbCommand.Source
$action = 'io.github.mesmerprism.rustyquest.native_renderer.action.BREATH_COMPOSITION_PANEL_COMMAND'
$activityClass = 'io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity'
$component = "$PackageName/$activityClass"
$token = [Guid]::NewGuid().ToString('N')
$receiptPath = 'files/breath_composition_operator_status.json'

$deviceState = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $deviceState -ne 'device') {
    throw "Serial-scoped ADB target is not ready: $Serial ($deviceState)"
}

$arguments = @(
    '-s', $Serial,
    'shell', 'am', 'start', '-W',
    '-n', $component,
    '-a', $action,
    '--es', 'breath_composition_operation', $Operation,
    '--es', 'breath_composition_command_token', $token
)
if ($Operation -eq 'select') {
    $arguments += @(
        '--es', 'breath_composition_source', $Source,
        '--es', 'breath_composition_mapping', $Mapping,
        '--es', 'breath_composition_controller_projection', $ControllerProjection,
        '--es', 'breath_composition_polar_projection', $PolarProjection,
        '--ez', 'breath_composition_inverted', $Inverted.IsPresent.ToString().ToLowerInvariant()
    )
}
if ($ReturnToImmersive) {
    $arguments += @('--ez', 'breath_composition_return_to_immersive', 'true')
}

$launchOutput = (& $adb @arguments 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Fixed breath operator action failed to launch: $launchOutput"
}

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
$receipt = $null
do {
    $raw = (& $adb -s $Serial exec-out run-as $PackageName cat $receiptPath 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($raw)) {
        try {
            $candidate = $raw | ConvertFrom-Json -Depth 32
            if ([string]$candidate.token -eq $token) {
                $receipt = $candidate
                break
            }
        } catch {
        }
    }
    Start-Sleep -Milliseconds 100
} while ([DateTimeOffset]::UtcNow -lt $deadline)

if ($null -eq $receipt) {
    throw "Timed out waiting for correlated app-owned breath receipt token $token"
}

[pscustomobject]@{
    schema = 'rusty.quest.native_renderer.breath_operator_invocation.v1'
    serial = $Serial
    package = $PackageName
    component = $component
    token = $token
    operation = $Operation
    launch_transport = 'serial-scoped-fixed-adb-activity-action'
    screenshot_required = $false
    app_receipt = $receipt
    launch_output = $launchOutput
} | ConvertTo-Json -Depth 32
