param(
    [double]$OffsetX = 0.0,

    [double]$OffsetY = 0.0,

    [double]$OffsetZ = 2.0,

    [double]$YawDegrees = 180.0,

    [double]$HorizontalSign = -1.0,

    [string]$Serial = $env:RUSTY_QUEST_SERIAL,

    [string]$AdbPath = ""
)

$ErrorActionPreference = "Stop"

$properties = [ordered]@{
    "debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_x_m" = [Math]::Max(-4.0, [Math]::Min(4.0, $OffsetX))
    "debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_y_m" = [Math]::Max(-4.0, [Math]::Min(4.0, $OffsetY))
    "debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_z_m" = [Math]::Max(-4.0, [Math]::Min(4.0, $OffsetZ))
    "debug.rustyquest.spatial_camera_panel.live_hand_scene.yaw_degrees" = [Math]::Max(-360.0, [Math]::Min(360.0, $YawDegrees))
    "debug.rustyquest.spatial_camera_panel.live_hand_scene.horizontal_sign" = $(if ($HorizontalSign -lt 0.0) { -1.0 } else { 1.0 })
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "Pass -Serial or set RUSTY_QUEST_SERIAL."
}

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    if (-not [string]::IsNullOrWhiteSpace($env:RUSTY_QUEST_ADB)) {
        $AdbPath = $env:RUSTY_QUEST_ADB
    } elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $AdbPath = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    } else {
        $AdbPath = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
    }
}

if (-not (Test-Path -LiteralPath $AdbPath)) {
    throw "ADB not found: $AdbPath"
}

$readbacks = [ordered]@{}
foreach ($property in $properties.GetEnumerator()) {
    $value = $property.Value.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
    & $AdbPath -s $Serial shell setprop $property.Key $value
    if ($LASTEXITCODE -ne 0) {
        throw "adb setprop failed for $($property.Key) with exit code $LASTEXITCODE"
    }
    $readback = (& $AdbPath -s $Serial shell getprop $property.Key).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "adb getprop failed for $($property.Key) with exit code $LASTEXITCODE"
    }
    $readbacks[$property.Key] = $readback
}

[pscustomobject]@{
    schema = "rusty.quest.spatial_camera_panel_live_hand_scene_transform_set.v1"
    serial = $Serial
    applied = [ordered]@{
        offset_x_m = $properties["debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_x_m"]
        offset_y_m = $properties["debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_y_m"]
        offset_z_m = $properties["debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_z_m"]
        yaw_degrees = $properties["debug.rustyquest.spatial_camera_panel.live_hand_scene.yaw_degrees"]
        horizontal_sign = $properties["debug.rustyquest.spatial_camera_panel.live_hand_scene.horizontal_sign"]
    }
    readbacks = $readbacks
} | ConvertTo-Json
