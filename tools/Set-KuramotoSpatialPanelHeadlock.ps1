param(
    [string]$Enabled = "",

    [string]$OffsetX = "",

    [string]$OffsetY = "",

    [string]$Distance = "",

    [string]$Width = "",

    [string]$Height = "",

    [string]$Scale = "",

    [string]$JoystickEnabled = "",

    [string]$JoystickTranslateRate = "",

    [string]$JoystickDistanceRate = "",

    [string]$JoystickScaleRate = "",

    [string]$Serial = $env:RUSTY_QUEST_SERIAL,

    [string]$AdbPath = $env:RUSTY_QUEST_ADB,

    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,

    [string]$PackageName = "io.github.mesmerprism.rustyquest.kuramoto_spatial",

    [switch]$Clear
)

$ErrorActionPreference = "Stop"

$properties = [ordered]@{
    enabled = "debug.rustyquest.kuramoto_spatial.panel.headlocked.enabled"
    offset_x_m = "debug.rustyquest.kuramoto_spatial.panel.headlocked.offset_x_m"
    offset_y_m = "debug.rustyquest.kuramoto_spatial.panel.headlocked.offset_y_m"
    distance_meters = "debug.rustyquest.kuramoto_spatial.panel.headlocked.distance_meters"
    width_meters = "debug.rustyquest.kuramoto_spatial.panel.headlocked.width_meters"
    height_meters = "debug.rustyquest.kuramoto_spatial.panel.headlocked.height_meters"
    scale = "debug.rustyquest.kuramoto_spatial.panel.headlocked.scale"
    joystick_enabled = "debug.rustyquest.kuramoto_spatial.panel.headlocked.joystick.enabled"
    joystick_translate_rate_mps = "debug.rustyquest.kuramoto_spatial.panel.headlocked.joystick.translate_rate_mps"
    joystick_distance_rate_mps = "debug.rustyquest.kuramoto_spatial.panel.headlocked.joystick.distance_rate_mps"
    joystick_scale_rate_per_second = "debug.rustyquest.kuramoto_spatial.panel.headlocked.joystick.scale_rate_per_second"
}

function Resolve-ToolPath {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [string]$Value,
        [string]$DefaultPath
    )

    if (-not [string]::IsNullOrWhiteSpace($Value)) {
        if (Test-Path -LiteralPath $Value) {
            return (Resolve-Path -LiteralPath $Value).Path
        }
        $command = Get-Command $Value -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
        throw "$Name not found: $Value"
    }

    if (-not [string]::IsNullOrWhiteSpace($DefaultPath) -and (Test-Path -LiteralPath $DefaultPath)) {
        return (Resolve-Path -LiteralPath $DefaultPath).Path
    }

    $fallback = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $fallback) {
        throw "$Name not found. Pass -$Name or set the matching environment variable."
    }
    return $fallback.Source
}

function Resolve-AdbServerPortArgument {
    param([string]$Value)

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
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][AllowEmptyString()][string[]]$Arguments,
        [switch]$AllowFailure
    )

    $adbArgs = @()
    if ($null -ne $script:ResolvedAdbServerPort) {
        $adbArgs += @("-P", $script:ResolvedAdbServerPort)
    }
    $adbArgs += @("-s", $script:Serial)
    $adbArgs += $Arguments

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $script:ResolvedAdb @adbArgs 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $result = [ordered]@{
        name = $Name
        arguments = $Arguments
        exit_code = $exitCode
        output = ($output -join "`n")
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "$Name failed with exit code $exitCode`n$($result.output)"
    }
    return $result
}

function Format-InvariantNumber {
    param([double]$Value)
    return $Value.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
}

function Parse-ClampedDouble {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value,
        [Parameter(Mandatory=$true)][double]$Minimum,
        [Parameter(Mandatory=$true)][double]$Maximum
    )

    $parsed = 0.0
    if (-not [double]::TryParse($Value, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) {
        throw "$Name must be a number: $Value"
    }
    return [Math]::Max($Minimum, [Math]::Min($Maximum, $parsed))
}

function Parse-BoolString {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value
    )

    switch -Regex ($Value.Trim().ToLowerInvariant()) {
        "^(1|true|yes|on)$" { return "true" }
        "^(0|false|no|off)$" { return "false" }
        default { throw "$Name must be true or false: $Value" }
    }
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "Pass -Serial or set RUSTY_QUEST_SERIAL."
}

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $AdbPath = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    } else {
        $AdbPath = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
    }
}

$script:ResolvedAdb = Resolve-ToolPath -Name "adb" -Value $AdbPath -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
$script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument -Value $AdbServerPort

$requested = [ordered]@{}
if (-not [string]::IsNullOrWhiteSpace($Enabled)) {
    $requested[$properties.enabled] = Parse-BoolString -Name "Enabled" -Value $Enabled
}
if (-not [string]::IsNullOrWhiteSpace($OffsetX)) {
    $requested[$properties.offset_x_m] = Format-InvariantNumber (Parse-ClampedDouble -Name "OffsetX" -Value $OffsetX -Minimum -0.85 -Maximum 0.85)
}
if (-not [string]::IsNullOrWhiteSpace($OffsetY)) {
    $requested[$properties.offset_y_m] = Format-InvariantNumber (Parse-ClampedDouble -Name "OffsetY" -Value $OffsetY -Minimum -0.65 -Maximum 0.65)
}
if (-not [string]::IsNullOrWhiteSpace($Distance)) {
    $requested[$properties.distance_meters] = Format-InvariantNumber (Parse-ClampedDouble -Name "Distance" -Value $Distance -Minimum 0.35 -Maximum 1.5)
}
if (-not [string]::IsNullOrWhiteSpace($Width)) {
    $requested[$properties.width_meters] = Format-InvariantNumber (Parse-ClampedDouble -Name "Width" -Value $Width -Minimum 1.2 -Maximum 2.6)
}
if (-not [string]::IsNullOrWhiteSpace($Height)) {
    $requested[$properties.height_meters] = Format-InvariantNumber (Parse-ClampedDouble -Name "Height" -Value $Height -Minimum 0.75 -Maximum 1.65)
}
if (-not [string]::IsNullOrWhiteSpace($Scale)) {
    $requested[$properties.scale] = Format-InvariantNumber (Parse-ClampedDouble -Name "Scale" -Value $Scale -Minimum 0.65 -Maximum 1.6)
}
if (-not [string]::IsNullOrWhiteSpace($JoystickEnabled)) {
    $requested[$properties.joystick_enabled] = Parse-BoolString -Name "JoystickEnabled" -Value $JoystickEnabled
}
if (-not [string]::IsNullOrWhiteSpace($JoystickTranslateRate)) {
    $requested[$properties.joystick_translate_rate_mps] = Format-InvariantNumber (Parse-ClampedDouble -Name "JoystickTranslateRate" -Value $JoystickTranslateRate -Minimum 0.01 -Maximum 1.0)
}
if (-not [string]::IsNullOrWhiteSpace($JoystickDistanceRate)) {
    $requested[$properties.joystick_distance_rate_mps] = Format-InvariantNumber (Parse-ClampedDouble -Name "JoystickDistanceRate" -Value $JoystickDistanceRate -Minimum 0.01 -Maximum 1.0)
}
if (-not [string]::IsNullOrWhiteSpace($JoystickScaleRate)) {
    $requested[$properties.joystick_scale_rate_per_second] = Format-InvariantNumber (Parse-ClampedDouble -Name "JoystickScaleRate" -Value $JoystickScaleRate -Minimum 0.01 -Maximum 2.0)
}

$applied = [ordered]@{}
if ($Clear) {
    $defaultValues = [ordered]@{
        $properties.enabled = "true"
        $properties.offset_x_m = "0"
        $properties.offset_y_m = "0"
        $properties.distance_meters = "1.4"
        $properties.width_meters = "1.2"
        $properties.height_meters = "1.254"
        $properties.scale = "0.65"
        $properties.joystick_enabled = "true"
        $properties.joystick_translate_rate_mps = "0.18"
        $properties.joystick_distance_rate_mps = "0.16"
        $properties.joystick_scale_rate_per_second = "0.3"
    }
    foreach ($property in $defaultValues.GetEnumerator()) {
        Invoke-AdbCommand -Name "reset $($property.Key)" -Arguments @("shell", "setprop", $property.Key, [string]$property.Value) | Out-Null
        $applied[$property.Key] = $property.Value
    }
} else {
    foreach ($property in $requested.GetEnumerator()) {
        Invoke-AdbCommand -Name "set $($property.Key)" -Arguments @("shell", "setprop", $property.Key, [string]$property.Value) | Out-Null
        $applied[$property.Key] = $property.Value
    }
}

$readbacks = [ordered]@{}
foreach ($propertyName in $properties.Values) {
    $readback = Invoke-AdbCommand -Name "read $propertyName" -Arguments @("shell", "getprop", $propertyName)
    $readbacks[$propertyName] = $readback.output.Trim()
}

$tuningText = ""
$tuningJson = $null
$tuningPresent = $false
$tuningResult = Invoke-AdbCommand `
    -Name "read app-private panel headlock tuning" `
    -Arguments @("exec-out", "run-as", $PackageName, "cat", "files/kuramoto_spatial_panel_headlock_tuning.json") `
    -AllowFailure
if ($tuningResult.exit_code -eq 0) {
    $tuningText = $tuningResult.output.Trim()
    $tuningPresent =
        (-not [string]::IsNullOrWhiteSpace($tuningText)) -and
        $tuningText.TrimStart().StartsWith("{") -and
        ($tuningText -notmatch "No such file")
    if ($tuningPresent) {
        try {
            $tuningJson = $tuningText | ConvertFrom-Json
        } catch {
            $tuningJson = $null
        }
    }
}

[pscustomobject]@{
    schema = "rusty.quest.kuramoto_spatial_panel_headlock_set.v1"
    serial = $Serial
    package_name = $PackageName
    cleared = [bool]$Clear
    applied = $applied
    readbacks = $readbacks
    tuning_file = "files/kuramoto_spatial_panel_headlock_tuning.json"
    app_private_tuning_present = $tuningPresent
    app_private_tuning = $tuningJson
    app_private_tuning_text = $tuningText
} | ConvertTo-Json -Depth 8
