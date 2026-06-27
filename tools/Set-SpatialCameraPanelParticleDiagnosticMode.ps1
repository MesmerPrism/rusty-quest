param(
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [Parameter(Mandatory = $true)][string]$Mode,
    [string]$Adb = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "Quest serial is required. Pass -Serial or set RUSTY_QUEST_SERIAL."
}

if ([string]::IsNullOrWhiteSpace($Adb)) {
    $Adb = if ($env:ANDROID_HOME) {
        Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    } else {
        "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
    }
}

if (-not (Test-Path -LiteralPath $Adb)) {
    throw "adb not found: $Adb"
}

$propertyName = "debug.rustyquest.spatial_camera_panel.particle_layer.diagnostic_mode"
$value = $Mode.Trim()
& $Adb -s $Serial shell setprop $propertyName $value
$readback = (& $Adb -s $Serial shell getprop $propertyName).Trim()

[ordered]@{
    schema = "rusty.quest.spatial_camera_panel_particle_diagnostic_mode_set.v1"
    serial = $Serial
    property = $propertyName
    requested_mode = $value
    applied_mode = $readback
} | ConvertTo-Json -Depth 3
