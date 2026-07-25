param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._:-]+$')]
    [string]$Serial,
    [ValidateSet("flat-2d", "raymarch")]
    [string]$Mode = "raymarch",
    [bool]$FragmentDepth = $true,
    [ValidateRange(3, 60)]
    [int]$RunSeconds = 10,
    [string]$RepoRoot,
    [string]$ApkPath,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$OutDir,
    [switch]$SkipInstall,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path
$packageName = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
$componentName = "$packageName/.SpatialCameraPanelActivity"
$transport = "adb-explicit-serial"
$properties = [ordered]@{
    "debug.rustyquest.spatial.camera_hwb_projection_probe" = "false"
    "debug.rustyquest.spatial.camera_hwb_projection_probe.synthetic_visual" = "false"
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.enabled" = "false"
    "debug.rustyquest.spatial.video_projection_probe" = "false"
    "debug.rustyquest.spatial.camera_hwb_probe" = "false"
    "debug.rustyquest.spatial.panel_shell.visible" = "false"
    "debug.rustyquest.spatial.fragment_probe.enabled" = "true"
    "debug.rustyquest.spatial.fragment_probe.mode" = $Mode
    "debug.rustyquest.spatial.fragment_probe.fragment_depth" = $FragmentDepth.ToString().ToLowerInvariant()
    "debug.rustyquest.spatial.fragment_probe.hold_ms" = (($RunSeconds + 4) * 1000).ToString()
}

if ($DryRun) {
    [ordered]@{
        schema_version = 1
        transport = $transport
        serial = $Serial
        package = $packageName
        mode = $Mode
        fragment_depth = $FragmentDepth
        property_manifest = @($properties.Keys)
        write_plan = @($properties.GetEnumerator() | ForEach-Object {
            [ordered]@{ property = $_.Key; value = $_.Value }
        })
        install = -not $SkipInstall
        screenshot_required = $true
        restore_exact_prior_values = $true
    } | ConvertTo-Json -Depth 6
    return
}

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $repoRootPath "apps\spatial-camera-panel-android\app\build\outputs\apk\debug\app-debug.apk"
}
$apkFile = Get-Item -LiteralPath $ApkPath -ErrorAction Stop

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $depthToken = if ($FragmentDepth) { "depth" } else { "nodepth" }
    $OutDir = Join-Path $repoRootPath "local-artifacts\spatial-fragment-probe\$stamp-$Mode-$depthToken"
}
$artifactDir = New-Item -ItemType Directory -Path $OutDir -Force

function Resolve-AdbPath {
    if (-not [string]::IsNullOrWhiteSpace($AndroidHome)) {
        $candidate = Join-Path $AndroidHome "platform-tools\adb.exe"
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "adb was not found. Set -AndroidHome or add adb to PATH."
    }
    return $command.Source
}

$adbPath = Resolve-AdbPath

function Invoke-AdbText {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )

    $output = & $adbPath @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String).TrimEnd()
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') failed with exit code ${exitCode}: $text"
    }
    return [ordered]@{ exit_code = $exitCode; text = $text }
}

function Invoke-SerialAdbText {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )

    return Invoke-AdbText -Arguments (@("-s", $Serial) + $Arguments) -AllowFailure:$AllowFailure
}

function Get-DeviceProperty {
    param([Parameter(Mandatory = $true)][string]$Name)
    return (Invoke-SerialAdbText -Arguments @("shell", "getprop", $Name)).text.Trim()
}

function Set-DeviceProperty {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Value
    )
    if ($Name -notmatch '^[A-Za-z0-9._-]+$') {
        throw "Unsafe Android property name: $Name"
    }
    $singleQuote = [string][char]39
    $doubleQuote = [string][char]34
    $embeddedQuote = $singleQuote + $doubleQuote + $singleQuote + $doubleQuote + $singleQuote
    $quotedValue = $singleQuote + $Value.Replace($singleQuote, $embeddedQuote) + $singleQuote
    [void](Invoke-SerialAdbText -Arguments @("shell", "setprop $Name $quotedValue"))
    $readback = Get-DeviceProperty -Name $Name
    if ($readback -cne $Value) {
        throw "Property readback mismatch for $Name. Expected '$Value', found '$readback'."
    }
}

function Capture-AdbPng {
    param([Parameter(Mandatory = $true)][string]$Destination)

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $adbPath
    $startInfo.Arguments = "-s $Serial exec-out screencap -p"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "Failed to start adb screenshot capture."
    }
    $fileStream = [System.IO.File]::Open($Destination, [System.IO.FileMode]::Create)
    try {
        $process.StandardOutput.BaseStream.CopyTo($fileStream)
    } finally {
        $fileStream.Dispose()
    }
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "adb screenshot capture failed with exit code $($process.ExitCode): $stderr"
    }
}

$priorProperties = [ordered]@{}
$restoredProperties = [ordered]@{}
$restoreVerified = $false
$runError = $null
$markers = ""
$windowState = ""
$appProcessId = ""
$screenshotPath = Join-Path $artifactDir.FullName "spatial-fragment-probe.png"
$logcatPath = Join-Path $artifactDir.FullName "logcat.txt"
$windowPath = Join-Path $artifactDir.FullName "window.txt"
$summaryPath = Join-Path $artifactDir.FullName "summary.json"
$deviceModel = ""
$buildIncremental = ""
$installOutput = "skipped"
$launchOutput = ""
$screenshotSha256 = ""

try {
    $deviceState = (Invoke-SerialAdbText -Arguments @("get-state")).text.Trim()
    if ($deviceState -ne "device") {
        throw "Quest serial '$Serial' is not in the device state: '$deviceState'."
    }
    $deviceModel = (Invoke-SerialAdbText -Arguments @("shell", "getprop", "ro.product.model")).text.Trim()
    $buildIncremental = (Invoke-SerialAdbText -Arguments @("shell", "getprop", "ro.build.version.incremental")).text.Trim()

    foreach ($name in $properties.Keys) {
        $priorProperties[$name] = Get-DeviceProperty -Name $name
    }

    if (-not $SkipInstall) {
        $installOutput = (Invoke-SerialAdbText -Arguments @("install", "-r", "-d", "-g", $apkFile.FullName)).text
        if ($installOutput -notmatch "Success") {
            throw "APK install did not report success: $installOutput"
        }
    }

    [void](Invoke-SerialAdbText -Arguments @("shell", "am", "force-stop", $packageName))
    foreach ($entry in $properties.GetEnumerator()) {
        Set-DeviceProperty -Name $entry.Key -Value $entry.Value
    }

    $launchOutput = (Invoke-SerialAdbText -Arguments @("shell", "am", "start", "-W", "-n", $componentName)).text
    $deadline = [DateTime]::UtcNow.AddSeconds(12)
    while ([DateTime]::UtcNow -lt $deadline -and [string]::IsNullOrWhiteSpace($appProcessId)) {
        Start-Sleep -Milliseconds 500
        $appProcessId = (Invoke-SerialAdbText -Arguments @("shell", "pidof", $packageName) -AllowFailure).text.Trim()
    }
    if ([string]::IsNullOrWhiteSpace($appProcessId)) {
        throw "The Spatial Camera Panel process did not start."
    }

    Start-Sleep -Seconds $RunSeconds
    $markers = (Invoke-SerialAdbText -Arguments @("logcat", "-d", "-v", "threadtime", "--pid=$appProcessId")).text
    $windowState = (Invoke-SerialAdbText -Arguments @("shell", "dumpsys", "window", "windows")).text
    [System.IO.File]::WriteAllText($logcatPath, $markers, [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($windowPath, $windowState, [System.Text.UTF8Encoding]::new($false))
    Capture-AdbPng -Destination $screenshotPath
    $screenshot = Get-Item -LiteralPath $screenshotPath
    if ($screenshot.Length -lt 4096) {
        throw "Screenshot is unexpectedly small ($($screenshot.Length) bytes)."
    }
    $screenshotSha256 = (Get-FileHash -LiteralPath $screenshotPath -Algorithm SHA256).Hash.ToLowerInvariant()

    $requiredMarkers = @(
        "channel=spatial-fragment-probe status=start",
        "channel=spatial-fragment-probe status=effective",
        "effectiveMarker=rusty.quest.spatial_fragment_probe.effective",
        "channel=spatial-fragment-probe status=render-ready",
        "channel=spatial-fragment-probe status=render-window",
        "mode=$Mode",
        "fragmentDepth=$($FragmentDepth.ToString().ToLowerInvariant())",
        "temporalModulation=false",
        "photosensitiveSafetyMode=static-only"
    )
    foreach ($requiredMarker in $requiredMarkers) {
        if (-not $markers.Contains($requiredMarker)) {
            throw "Required runtime evidence is missing: $requiredMarker"
        }
    }
    if ($markers -match "FATAL EXCEPTION|Fatal signal|ANR in $([Regex]::Escape($packageName))|channel=spatial-fragment-probe status=failed|Shader compilation failed|Failed to create.*shader|VK_ERROR_DEVICE_LOST") {
        throw "Fatal, shader, or device-loss evidence was found in logcat."
    }
    $forbiddenLayerEvidence = @(
        "channel=camera-hwb-spatial-probe status=start rawCameraProjectionProbe=true",
        "status=raw-camera-projection-layer-created rawCameraProjectionProbe=true",
        "channel=spatial-video-projection status=decoded-frame-acquired",
        "channel=spatial-video-projection status=ahardware-buffer-import-ready"
    )
    foreach ($forbiddenEvidence in $forbiddenLayerEvidence) {
        if ($markers.Contains($forbiddenEvidence)) {
            throw "Isolation failed; an unrelated camera/video layer became active: $forbiddenEvidence"
        }
    }
} catch {
    $runError = $_
} finally {
    [void](Invoke-SerialAdbText -Arguments @("shell", "am", "force-stop", $packageName) -AllowFailure)
    $restoreFailures = @()
    foreach ($name in $properties.Keys) {
        try {
            Set-DeviceProperty -Name $name -Value ([string]$priorProperties[$name])
            $restoredProperties[$name] = Get-DeviceProperty -Name $name
        } catch {
            $restoreFailures += "$name`: $($_.Exception.Message)"
        }
    }
    $restoreVerified = $restoreFailures.Count -eq 0
    if (-not $restoreVerified -and $null -eq $runError) {
        $runError = [System.Exception]::new("Property restoration failed: $($restoreFailures -join '; ')")
    }
}

$summary = [ordered]@{
    schema_version = 1
    status = if ($null -eq $runError) { "passed" } else { "failed" }
    transport = $transport
    serial = $Serial
    device_model = $deviceModel
    build_incremental = $buildIncremental
    package = $packageName
    component = $componentName
    mode = $Mode
    fragment_depth = $FragmentDepth
    raymarch_steps = 12
    temporal_modulation = $false
    photosensitive_safety_mode = "static-only"
    unrelated_camera_projection_disabled = $true
    unrelated_video_layers_disabled = $true
    unrelated_panel_shell_disabled = $true
    apk_path = $apkFile.FullName
    apk_sha256 = (Get-FileHash -LiteralPath $apkFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    install_output = $installOutput
    launch_output = $launchOutput
    process_id = $appProcessId
    prior_properties = $priorProperties
    restored_properties = $restoredProperties
    restore_verified = $restoreVerified
    screenshot = $screenshotPath
    screenshot_sha256 = $screenshotSha256
    screenshot_required = $true
    gpu_fragment_execution_marker_claim = $false
    visual_verdict = "pending-external-inspection"
    error = if ($null -eq $runError) {
        $null
    } elseif ($runError -is [System.Exception]) {
        $runError.Message
    } else {
        $runError.Exception.Message
    }
}
[System.IO.File]::WriteAllText(
    $summaryPath,
    ($summary | ConvertTo-Json -Depth 8),
    [System.Text.UTF8Encoding]::new($false)
)

if ($null -ne $runError) {
    $runErrorMessage = if ($runError -is [System.Exception]) { $runError.Message } else { $runError.Exception.Message }
    throw "Spatial fragment probe smoke failed. Evidence: $summaryPath. $runErrorMessage"
}

Write-Host "Spatial fragment probe smoke passed"
Write-Host "Evidence: $summaryPath"
Write-Output $summaryPath
