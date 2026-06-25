param(
    [Parameter(Mandatory = $true)]
    [double]$Meters,

    [string]$Serial = $env:RUSTY_QUEST_SERIAL,

    [string]$AdbPath = ""
)

$ErrorActionPreference = "Stop"

$propertyName = "debug.rustyquest.kuramoto_spatial.live_hand_depth_offset_meters"

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "Pass -Serial or set RUSTY_QUEST_SERIAL."
}

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    if (-not [string]::IsNullOrWhiteSpace($env:RUSTY_XR_ADB)) {
        $AdbPath = $env:RUSTY_XR_ADB
    } elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $AdbPath = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    } else {
        $AdbPath = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
    }
}

if (-not (Test-Path -LiteralPath $AdbPath)) {
    throw "ADB not found: $AdbPath"
}

$clampedMeters = [Math]::Max(-1.5, [Math]::Min(1.5, $Meters))
$value = $clampedMeters.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)

& $AdbPath -s $Serial shell setprop $propertyName $value
if ($LASTEXITCODE -ne 0) {
    throw "adb setprop failed with exit code $LASTEXITCODE"
}

$readback = (& $AdbPath -s $Serial shell getprop $propertyName).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "adb getprop failed with exit code $LASTEXITCODE"
}

[pscustomobject]@{
    schema = "rusty.quest.kuramoto_spatial_live_hand_depth_offset_set.v1"
    serial = $Serial
    property = $propertyName
    requested_meters = $Meters
    applied_meters = $clampedMeters
    readback = $readback
} | ConvertTo-Json
