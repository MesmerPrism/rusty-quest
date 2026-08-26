[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._:-]+$')]
    [string]$Serial = '',
    [ValidatePattern('^io\.github\.mesmerprism\.rustyquest\.native_renderer\.[a-z0-9_.]+$')]
    [string]$PackageName = '',
    [ValidateSet('select', 'start_calibration', 'cancel', 'reset', 'disable', 'status')]
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

$command = New-Command
$json = $command | ConvertTo-Json -Compress -Depth 8
if ([Text.Encoding]::UTF8.GetByteCount($json) -gt 16384) {
    throw 'Breath command exceeds the fixed 16384-byte receiver limit.'
}
$encoded = ConvertTo-Base64Utf8 $json
$component = "$PackageName/$receiver"
$broadcast = (& $adb -s $Serial shell am broadcast --receiver-foreground -a $action -n $component --es command_b64 $encoded 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw "Headless breath broadcast failed: $broadcast" }
$receipt = ConvertFrom-BroadcastOutput $broadcast
if ([string]$receipt.command_status -notin @('accepted', 'status')) {
    throw "Breath command was rejected: $([string]$receipt.reason_code)"
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
    broadcast_output = $broadcast
} | ConvertTo-Json -Depth 32
