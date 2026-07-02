param(
    [ValidateSet("none", "flip-x", "flip-y", "flip-z", "yaw-180", "flip-xz")]
    [string]$Parity = "flip-x",

    [ValidateSet("none", "local-x", "local-y", "local-z")]
    [string]$ReflectionOrientation = "local-y",

    [string]$Serial = $env:RUSTY_QUEST_SERIAL,

    [string]$AdbPath = "",

    [int]$MarkerWaitSeconds = 2
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "Pass -Serial or set RUSTY_QUEST_SERIAL."
}

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    if (-not [string]::IsNullOrWhiteSpace($env:RUSTY_XR_ADB)) {
        $AdbPath = $env:RUSTY_XR_ADB
    } elseif (-not [string]::IsNullOrWhiteSpace($env:RUSTY_QUEST_ADB)) {
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

$propertyName = "debug.rustyquest.kuramoto_spatial.live_hand_spatial_viewer_world_registration.parity"
$reflectionPropertyName = "debug.rustyquest.kuramoto_spatial.live_hand_spatial_viewer_world_registration.reflection_orientation"

& $AdbPath -s $Serial shell setprop $propertyName $Parity
if ($LASTEXITCODE -ne 0) {
    throw "adb setprop failed for $propertyName with exit code $LASTEXITCODE"
}
& $AdbPath -s $Serial shell setprop $reflectionPropertyName $ReflectionOrientation
if ($LASTEXITCODE -ne 0) {
    throw "adb setprop failed for $reflectionPropertyName with exit code $LASTEXITCODE"
}

$readback = (& $AdbPath -s $Serial shell getprop $propertyName).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "adb getprop failed for $propertyName with exit code $LASTEXITCODE"
}
if ($readback -ne $Parity) {
    throw "Readback mismatch for ${propertyName}: expected '$Parity', got '$readback'."
}
$reflectionReadback = (& $AdbPath -s $Serial shell getprop $reflectionPropertyName).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "adb getprop failed for $reflectionPropertyName with exit code $LASTEXITCODE"
}
if ($reflectionReadback -ne $ReflectionOrientation) {
    throw "Readback mismatch for ${reflectionPropertyName}: expected '$ReflectionOrientation', got '$reflectionReadback'."
}

$markers = @()
if ($MarkerWaitSeconds -gt 0) {
    Start-Sleep -Seconds $MarkerWaitSeconds
    $markers =
        & $AdbPath -s $Serial logcat -d -v time RQKuramotoSpatialNative:I '*:S' |
        Select-String -Pattern "status=live-hand-spatial-viewer-world-registration-(parity-updated|diagnostic|updated)" |
        Select-Object -Last 8 |
        ForEach-Object { $_.Line }
}

[pscustomobject]@{
    schema = "rusty.quest.kuramoto_spatial_live_hand_registration_parity_set.v1"
    serial = $Serial
    property = $propertyName
    parity = $Parity
    readback = $readback
    reflection_property = $reflectionPropertyName
    reflection_orientation = $ReflectionOrientation
    reflection_readback = $reflectionReadback
    expected_markers = @(
        "status=live-hand-spatial-viewer-world-registration-parity-updated",
        "status=live-hand-spatial-viewer-world-reflection-orientation-updated",
        "status=live-hand-spatial-viewer-world-registration-diagnostic",
        "liveHandSpatialWorldRegistrationParity",
        "liveHandSpatialWorldRegistrationOrientationAdjusted",
        "liveHandSpatialWorldRegistrationEffectivePositionDeterminant"
    )
    recent_markers = $markers
} | ConvertTo-Json
