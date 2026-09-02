[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._:-]+$')]
    [string]$Serial = '',
    [ValidatePattern('^io\.github\.mesmerprism\.rustyquest\.native_renderer\.[a-z0-9_.]+$')]
    [string]$PackageName = '',
    [ValidateSet('select', 'configure-polar-state', 'start_calibration', 'cancel', 'reset', 'disable', 'status')]
    [string]$Operation = 'status',
    [ValidateSet('controller', 'polar-acc')]
    [string]$Source = 'controller',
    [ValidateSet('volume', 'state')]
    [string]$Mapping = 'volume',
    [ValidateSet('dynamic-axis', 'fixed-orientation')]
    [string]$ControllerProjection = 'dynamic-axis',
    [ValidateSet('xz', '3d')]
    [string]$PolarProjection = 'xz',
    [switch]$Inverted,
    [ValidateRange(0.000001, 1000.0)]
    [double]$PolarInhaleEntryPerSecond = 0.030,
    [ValidateRange(0.000001, 1000.0)]
    [double]$PolarExhaleEntryPerSecond = 0.030,
    [ValidateRange(0.0, 999.999)]
    [double]$PolarHoldBandPerSecond = 0.025,
    [ValidateRange(0, 10000)]
    [int]$PolarSmoothingMillis = 400,
    [ValidateRange(1, 10000)]
    [int]$PolarConfirmationMillis = 400,
    [ValidateRange(0, 10000)]
    [int]$PolarMinimumDwellMillis = 400,
    [ValidateRange(1, 20000)]
    [int]$PolarStaleMillis = 500,
    [ValidateRange(0.0, 1000.0)]
    [double]$PolarMotionAdmissionMg = 2.0,
    [ValidateRange(0.000001, 1000.0)]
    [double]$PolarLeaveFullContractionPerSecond = 0.030,
    [ValidateRange(0.000001, 1000.0)]
    [double]$PolarLeaveFullExpansionPerSecond = 0.030,
    [ValidateRange(0, 10000)]
    [int]$PolarLateSampleWindowMillis = 120,
    [switch]$NoAwaitEffective,
    [switch]$ReturnToImmersive,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$action = 'io.github.mesmerprism.rustyquest.native_renderer.action.BREATH_COMPOSITION_COMMAND'
$receiver = 'io.github.mesmerprism.rustyquest.native_renderer.BreathCompositionCommandReceiver'

function ConvertTo-Base64Utf8([string]$Text) {
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Text)).TrimEnd('=')
}

function ConvertFrom-BroadcastOutput([string]$Output) {
    $match = [regex]::Match($Output, 'data="(?<data>[A-Za-z0-9+/=]+)"')
    if (-not $match.Success) { throw "Breath receiver returned no typed Base64 receipt.`n$Output" }
    $encoded = $match.Groups['data'].Value
    switch ($encoded.Length % 4) { 2 { $encoded += '==' }; 3 { $encoded += '=' } }
    $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
    return $json | ConvertFrom-Json -Depth 32
}

function New-Command {
    $command = [ordered]@{
        schema = 'rusty.quest.breath_composition.command.v1'
        operation = $Operation
    }
    if ($Operation -eq 'select') {
        $command.source = $Source
        $command.mapping = $Mapping
        $command.controller_projection = $ControllerProjection
        $command.polar_projection = $PolarProjection
        $command.inverted = $Inverted.IsPresent
    } elseif ($Operation -eq 'configure-polar-state') {
        $command.operation = 'configure_polar_state'
        $command.session_id = $script:PolarSessionId
        $command.generation = $script:PolarGeneration
        $command.request_id = $script:PolarRequestId
        $command.settings = [ordered]@{
            inhale_entry_per_second = $PolarInhaleEntryPerSecond
            exhale_entry_per_second = $PolarExhaleEntryPerSecond
            hold_band_per_second = $PolarHoldBandPerSecond
            smoothing_millis = $PolarSmoothingMillis
            confirmation_millis = $PolarConfirmationMillis
            minimum_dwell_millis = $PolarMinimumDwellMillis
            stale_millis = $PolarStaleMillis
            motion_admission_mg = $PolarMotionAdmissionMg
            leave_full_contraction_per_second = $PolarLeaveFullContractionPerSecond
            leave_full_expansion_per_second = $PolarLeaveFullExpansionPerSecond
            late_sample_window_millis = $PolarLateSampleWindowMillis
        }
    }
    return $command
}

function Invoke-SelfTest {
    $response = '{"schema":"rusty.quest.breath_composition.response.v1","command_status":"accepted","reason_code":"none"}'
    $synthetic = 'Broadcast completed: result=-1, data="' + (ConvertTo-Base64Utf8 $response) + '"'
    $parsed = ConvertFrom-BroadcastOutput $synthetic
    if ($parsed.command_status -cne 'accepted') { throw 'Breath receiver receipt parsing failed.' }
    $script:Operation = 'select'
    $command = New-Command
    if ($command.source -cne 'controller' -or $command.mapping -cne 'volume' -or
        $command.controller_projection -cne 'dynamic-axis' -or $command.polar_projection -cne 'xz') {
        throw 'Breath receiver typed command construction failed.'
    }
    $script:Operation = 'configure-polar-state'
    $script:PolarSessionId = '0123456789abcdef0123456789abcdef'
    $script:PolarGeneration = 7L
    $script:PolarRequestId = '11111111111111111111111111111111'
    $polarCommand = New-Command
    if ($polarCommand.operation -cne 'configure_polar_state' -or
        $polarCommand.settings.inhale_entry_per_second -ne 0.030 -or
        $polarCommand.settings.motion_admission_mg -ne 2.0) {
        throw 'Polar state tuning command construction failed.'
    }
    Write-Host 'Native renderer headless breath operator self-test passed'
}

if ($SelfTest) { Invoke-SelfTest; exit 0 }
if ([string]::IsNullOrWhiteSpace($Serial) -or [string]::IsNullOrWhiteSpace($PackageName)) {
    throw '-Serial and -PackageName are required.'
}
if ($ReturnToImmersive) {
    throw '-ReturnToImmersive is intentionally unavailable on the headless receiver route; launch/foreground the immersive activity separately.'
}

$adbCommand = Get-Command adb -ErrorAction Stop
$adb = $adbCommand.Source
$deviceState = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $deviceState -ne 'device') {
    throw "Serial-scoped ADB target is not ready: $Serial ($deviceState)"
}

function Invoke-BreathBroadcast([System.Collections.IDictionary]$Command) {
    $json = $Command | ConvertTo-Json -Compress -Depth 8
    if ([Text.Encoding]::UTF8.GetByteCount($json) -gt 16384) {
        throw 'Breath command exceeds the fixed 16384-byte receiver limit.'
    }
    $encoded = ConvertTo-Base64Utf8 $json
    $component = "$PackageName/$receiver"
    $broadcast = (& $adb -s $Serial shell am broadcast --receiver-foreground -a $action -n $component --es command_b64 $encoded 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Headless breath broadcast failed: $broadcast" }
    return [ordered]@{
        component = $component
        output = $broadcast
        receipt = ConvertFrom-BroadcastOutput $broadcast
    }
}

if ($Operation -eq 'configure-polar-state') {
    $statusCall = Invoke-BreathBroadcast ([ordered]@{
        schema = 'rusty.quest.breath_composition.command.v1'
        operation = 'status'
    })
    $tuning = $statusCall.receipt.snapshot.polar_state_tuning
    if ($null -eq $tuning -or [string]::IsNullOrWhiteSpace([string]$tuning.session_id)) {
        throw 'Native runtime returned no Polar state tuning session.'
    }
    $script:PolarSessionId = [string]$tuning.session_id
    $script:PolarGeneration = [long]$tuning.generation + 1L
    $script:PolarRequestId = [Guid]::NewGuid().ToString('N').ToLowerInvariant()
}

$command = New-Command
$call = Invoke-BreathBroadcast $command
$component = $call.component
$broadcast = $call.output
$receipt = $call.receipt
if ([string]$receipt.command_status -notin @('accepted', 'status')) {
    throw "Breath command was rejected: $([string]$receipt.reason_code)"
}

$effectiveReceipt = $receipt
if ($Operation -eq 'configure-polar-state' -and -not $NoAwaitEffective) {
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        Start-Sleep -Milliseconds 100
        $statusCall = Invoke-BreathBroadcast ([ordered]@{
            schema = 'rusty.quest.breath_composition.command.v1'
            operation = 'status'
        })
        $effectiveReceipt = $statusCall.receipt
        $effective = $effectiveReceipt.snapshot.polar_state_tuning.effective
        if ($null -ne $effective -and
            [string]$effective.session_id -ceq $script:PolarSessionId -and
            [long]$effective.generation -eq $script:PolarGeneration -and
            [string]$effective.request_id -ceq $script:PolarRequestId) {
            break
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($null -eq $effective -or [string]$effective.request_id -cne $script:PolarRequestId) {
        throw 'Timed out waiting for this Polar tuning request to become effective at the assessment boundary.'
    }
}

[ordered]@{
    schema = 'rusty.quest.native_renderer.breath_operator_invocation.v2'
    serial = $Serial
    package = $PackageName
    component = $component
    operation = $Operation
    launch_transport = 'serial-scoped-fixed-adb-ordered-broadcast'
    activity_launched = $false
    screenshot_required = $false
    app_receipt = $receipt
    effective_receipt = $effectiveReceipt
    broadcast_output = $broadcast
} | ConvertTo-Json -Depth 32
