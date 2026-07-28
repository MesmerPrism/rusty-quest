param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._:-]+$')]
    [string]$Serial,
    [ValidateRange(10, 60)]
    [int]$PanelRunSeconds = 13,
    [ValidateRange(8, 60)]
    [int]$ExternalRunSeconds = 10,
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
    "debug.rustyquest.spatial.fragment_probe.enabled" = "false"
    "debug.rustyquest.spatial.sdk_quad_surface_probe" = "false"
    "debug.rustyquest.spatial.sdk_quad_vulkan_probe" = "false"
    "debug.rustyquest.spatial.sdk_quad_stereo_alpha_probe" = "false"
    "debug.rustyquest.spatial.native_surface_particle_layer.enabled" = "false"
    "debug.rustyquest.spatial.viscereality_ecs.enabled" = "false"
    "debug.rustyquest.spatial.panel_shell.visible" = "false"
    "debug.rustyquest.spatial.panel_surface_matrix_probe" = "false"
    "debug.rustyquest.spatial.external_swapchain_probe" = "false"
    "debug.rustyquest.spatial.external_swapchain_probe.cycles" = "1"
    "debug.rustyquest.spatial.external_swapchain_probe.cycle_ms" = "6000"
}

if ($DryRun) {
    [ordered]@{
        schema_version = 1
        transport = $transport
        serial = $Serial
        package = $packageName
        phases = @("panel-surface-dual-consumer", "sdk-openxr-external-swapchain")
        property_manifest = @($properties.Keys)
        install = -not $SkipInstall
        restore_exact_prior_values = $true
        queue_submission_policy = "fail-closed"
    } | ConvertTo-Json -Depth 6
    return
}

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $repoRootPath "target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk"
}
$apkFile = Get-Item -LiteralPath $ApkPath -ErrorAction Stop
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $repoRootPath "local-artifacts\spatial-render-access\$(Get-Date -Format 'yyyyMMdd-HHmmss')"
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
    if ($null -eq $command) { throw "adb was not found. Set -AndroidHome or add adb to PATH." }
    return $command.Source
}

$adbPath = Resolve-AdbPath

function Invoke-SerialAdbText {
    param([Parameter(Mandatory = $true)][string[]]$Arguments, [switch]$AllowFailure)
    $output = & $adbPath @("-s", $Serial) @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String).TrimEnd()
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb -s $Serial $($Arguments -join ' ') failed with exit code ${exitCode}: $text"
    }
    return [ordered]@{ exit_code = $exitCode; text = $text }
}

function Get-DeviceProperty {
    param([string]$Name)
    return (Invoke-SerialAdbText -Arguments @("shell", "getprop", $Name)).text.Trim()
}

function Set-DeviceProperty {
    param([string]$Name, [AllowEmptyString()][string]$Value)
    if ($Name -notmatch '^[A-Za-z0-9._-]+$') { throw "Unsafe Android property name: $Name" }
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
    param([string]$Destination)
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $adbPath
    $startInfo.Arguments = "-s $Serial exec-out screencap -p"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw "Failed to start adb screenshot capture." }
    $fileStream = [System.IO.File]::Open($Destination, [System.IO.FileMode]::Create)
    try { $process.StandardOutput.BaseStream.CopyTo($fileStream) } finally { $fileStream.Dispose() }
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "adb screenshot capture failed with exit code $($process.ExitCode): $stderr"
    }
}

function Measure-PngVisualWitness {
    param([Parameter(Mandatory = $true)][string]$Path)

    Add-Type -AssemblyName System.Drawing.Common
    $bitmap = [System.Drawing.Bitmap]::new((Resolve-Path -LiteralPath $Path).Path)
    try {
        $sampleStride = 16
        $sampleCount = 0
        $brightSamples = 0
        $coloredSamples = 0
        for ($y = 0; $y -lt $bitmap.Height; $y += $sampleStride) {
            for ($x = 0; $x -lt $bitmap.Width; $x += $sampleStride) {
                $pixel = $bitmap.GetPixel($x, $y)
                $rgbSum = [int]$pixel.R + [int]$pixel.G + [int]$pixel.B
                $rgbMax = [Math]::Max($pixel.R, [Math]::Max($pixel.G, $pixel.B))
                $rgbMin = [Math]::Min($pixel.R, [Math]::Min($pixel.G, $pixel.B))
                $sampleCount++
                if ($rgbSum -gt 96) { $brightSamples++ }
                if (($rgbMax - $rgbMin) -gt 30 -and $rgbSum -gt 60) { $coloredSamples++ }
            }
        }
        $brightFraction = if ($sampleCount -eq 0) { 0.0 } else { $brightSamples / [double]$sampleCount }
        $coloredFraction = if ($sampleCount -eq 0) { 0.0 } else { $coloredSamples / [double]$sampleCount }
        return [ordered]@{
            provider = "adb_screencap_sampled"
            sample_stride_px = $sampleStride
            sample_count = $sampleCount
            bright_fraction = [Math]::Round($brightFraction, 6)
            colored_fraction = [Math]::Round($coloredFraction, 6)
            content_observed = $coloredFraction -ge 0.01
        }
    } finally {
        $bitmap.Dispose()
    }
}

function Assert-RuntimeEvidence {
    param([string]$Phase, [string]$Logcat, [string[]]$Required)
    foreach ($needle in $Required) {
        if (-not $Logcat.Contains($needle)) {
            throw "$Phase required runtime evidence is missing: $needle"
        }
    }
    if ($Logcat -match "FATAL EXCEPTION|Fatal signal|ANR in $([Regex]::Escape($packageName))|VK_ERROR_DEVICE_LOST|Shader compilation failed") {
        throw "$Phase emitted fatal, shader, ANR, or device-loss evidence."
    }
    foreach ($forbidden in @(
        "channel=camera-hwb-spatial-probe status=start rawCameraProjectionProbe=true",
        "status=raw-camera-projection-layer-created rawCameraProjectionProbe=true",
        "channel=spatial-video-projection status=decoded-frame-acquired",
        "channel=spatial-video-projection status=ahardware-buffer-import-ready"
    )) {
        if ($Logcat.Contains($forbidden)) {
            throw "$Phase isolation failed; an unrelated camera/video layer became active: $forbidden"
        }
    }
}

function Invoke-ProbePhase {
    param(
        [string]$Name,
        [hashtable]$Overrides,
        [int]$RunSeconds,
        [int[]]$ScreenshotAtSeconds,
        [string[]]$ScreenshotLabels,
        [string]$RequiredVisibleWitnessLabel,
        [string[]]$RequiredMarkers
    )
    if ($ScreenshotAtSeconds.Count -ne $ScreenshotLabels.Count -or $ScreenshotAtSeconds.Count -eq 0) {
        throw "$Name screenshot times and labels must be non-empty and have the same count."
    }
    foreach ($entry in $properties.GetEnumerator()) {
        $value = if ($Overrides.ContainsKey($entry.Key)) { [string]$Overrides[$entry.Key] } else { [string]$entry.Value }
        Set-DeviceProperty -Name $entry.Key -Value $value
    }
    [void](Invoke-SerialAdbText -Arguments @("shell", "am", "force-stop", $packageName))
    $launch = (Invoke-SerialAdbText -Arguments @("shell", "am", "start", "-W", "-n", $componentName)).text
    $appProcessId = ""
    $deadline = [DateTime]::UtcNow.AddSeconds(12)
    while ([DateTime]::UtcNow -lt $deadline -and [string]::IsNullOrWhiteSpace($appProcessId)) {
        Start-Sleep -Milliseconds 500
        $appProcessId = (Invoke-SerialAdbText -Arguments @("shell", "pidof", $packageName) -AllowFailure).text.Trim()
    }
    if ([string]::IsNullOrWhiteSpace($appProcessId)) { throw "$Name process did not start." }
    $screenshots = @()
    $previousCaptureAtSeconds = 0
    for ($index = 0; $index -lt $ScreenshotAtSeconds.Count; $index++) {
        $captureAtSeconds = $ScreenshotAtSeconds[$index]
        $captureLabel = $ScreenshotLabels[$index]
        if ($captureAtSeconds -lt $previousCaptureAtSeconds -or $captureAtSeconds -gt $RunSeconds) {
            throw "$Name screenshot times must be ascending and within the phase duration."
        }
        if ($captureLabel -notmatch '^[a-z0-9-]+$') {
            throw "$Name has an unsafe screenshot label: $captureLabel"
        }
        if ($captureAtSeconds -gt $previousCaptureAtSeconds) {
            Start-Sleep -Seconds ($captureAtSeconds - $previousCaptureAtSeconds)
        }
        $screenshotPath = Join-Path $artifactDir.FullName "$Name-$captureLabel.png"
        Capture-AdbPng -Destination $screenshotPath
        if ((Get-Item -LiteralPath $screenshotPath).Length -lt 4096) {
            throw "$Name screenshot '$captureLabel' is unexpectedly small."
        }
        $screenshots += [ordered]@{
            label = $captureLabel
            capture_at_seconds = $captureAtSeconds
            path = $screenshotPath
            sha256 = (Get-FileHash -LiteralPath $screenshotPath -Algorithm SHA256).Hash.ToLowerInvariant()
            visual_witness = Measure-PngVisualWitness -Path $screenshotPath
        }
        $previousCaptureAtSeconds = $captureAtSeconds
    }
    if ($RunSeconds -gt $previousCaptureAtSeconds) {
        Start-Sleep -Seconds ($RunSeconds - $previousCaptureAtSeconds)
    }
    $logcat = (Invoke-SerialAdbText -Arguments @("logcat", "-d", "-v", "threadtime", "--pid=$appProcessId")).text
    $logcatPath = Join-Path $artifactDir.FullName "$Name-logcat.txt"
    [System.IO.File]::WriteAllText($logcatPath, $logcat, [System.Text.UTF8Encoding]::new($false))
    Assert-RuntimeEvidence -Phase $Name -Logcat $logcat -Required $RequiredMarkers
    $primaryScreenshot = $screenshots[-1]
    $requiredVisualWitness =
        if ([string]::IsNullOrWhiteSpace($RequiredVisibleWitnessLabel)) {
            $null
        } else {
            @($screenshots | Where-Object { $_.label -eq $RequiredVisibleWitnessLabel }) | Select-Object -First 1
        }
    if (-not [string]::IsNullOrWhiteSpace($RequiredVisibleWitnessLabel) -and $null -eq $requiredVisualWitness) {
        throw "$Name did not capture its required visual witness label '$RequiredVisibleWitnessLabel'."
    }
    $phaseStatus =
        if ($null -ne $requiredVisualWitness -and -not $requiredVisualWitness.visual_witness.content_observed) {
            "structural-pass-visual-unconfirmed"
        } else {
            "passed"
        }
    return [ordered]@{
        name = $Name
        status = $phaseStatus
        process_id = $appProcessId
        launch_output = $launch
        logcat = $logcatPath
        screenshot = $primaryScreenshot.path
        screenshot_sha256 = $primaryScreenshot.sha256
        screenshots = $screenshots
        visual_witness_required = -not [string]::IsNullOrWhiteSpace($RequiredVisibleWitnessLabel)
        required_visual_witness_label = $RequiredVisibleWitnessLabel
        visual_witness = if ($null -eq $requiredVisualWitness) { $primaryScreenshot.visual_witness } else { $requiredVisualWitness.visual_witness }
        visual_acceptance =
            if ([string]::IsNullOrWhiteSpace($RequiredVisibleWitnessLabel)) {
                "not-applicable-no-image-write"
            } elseif ($requiredVisualWitness.visual_witness.content_observed) {
                "automated-color-content-observed"
            } else {
                "needs-headset-or-supported-capture-witness"
            }
    }
}

$priorProperties = [ordered]@{}
$restoredProperties = [ordered]@{}
$restoreVerified = $false
$runError = $null
$phases = @()
$deviceModel = ""
$buildIncremental = ""
$installOutput = "skipped"

try {
    $state = (Invoke-SerialAdbText -Arguments @("get-state")).text.Trim()
    if ($state -ne "device") { throw "Quest serial '$Serial' is not in device state: '$state'." }
    $deviceModel = (Invoke-SerialAdbText -Arguments @("shell", "getprop", "ro.product.model")).text.Trim()
    $buildIncremental = (Invoke-SerialAdbText -Arguments @("shell", "getprop", "ro.build.version.incremental")).text.Trim()
    foreach ($name in $properties.Keys) { $priorProperties[$name] = Get-DeviceProperty -Name $name }
    if (-not $SkipInstall) {
        $installOutput = (Invoke-SerialAdbText -Arguments @("install", "-r", "-d", "-g", $apkFile.FullName)).text
        if ($installOutput -notmatch "Success") { throw "APK install did not report success: $installOutput" }
    }

    $phases += Invoke-ProbePhase `
        -Name "panel-surface-dual-consumer" `
        -Overrides @{ "debug.rustyquest.spatial.panel_surface_matrix_probe" = "true" } `
        -RunSeconds $PanelRunSeconds `
        -ScreenshotAtSeconds @(3, 9) `
        -ScreenshotLabels @("swapchain-only", "dual-consumer") `
        -RequiredVisibleWitnessLabel "dual-consumer" `
        -RequiredMarkers @(
            "channel=panel-surface-matrix-probe status=start",
            "variant=useSwapchain-true-useTexture-true",
            "fragmentShader=spatial_panel_surface_probe.frag",
            "status=scene-texture-material-attempted",
            "textureBacksSceneMaterial=true",
            "status=scene-skip-render-toggle panelSurfaceMatrixProbe=true sceneSkipRenderEnabled=true",
            "status=scene-skip-render-toggle panelSurfaceMatrixProbe=true sceneSkipRenderEnabled=false",
            "channel=sdk-owned-quad-vulkan-probe status=first-frame-presented",
            "channel=panel-surface-matrix-probe status=complete",
            "variantsTested=3"
        )

    $phases += Invoke-ProbePhase `
        -Name "sdk-openxr-external-swapchain" `
        -Overrides @{ "debug.rustyquest.spatial.external_swapchain_probe" = "true" } `
        -RunSeconds $ExternalRunSeconds `
        -ScreenshotAtSeconds @(7) `
        -ScreenshotLabels @("expected-no-image-write") `
        -RequiredVisibleWitnessLabel "" `
        -RequiredMarkers @(
            "channel=external-xr-swapchain-wrap-probe status=start",
            "channel=sdk-openxr-compile-time-boundary status=observed",
            "accessSurface=typed-sdk-and-openxr-api",
            "sessionGraphicsBindingGetterAvailable=false",
            "channel=native-vulkan-object-probe status=observed",
            "vkDeviceCreated=true",
            "vkQueueObtained=true",
            "channel=external-xr-swapchain-wrap-probe status=native-create-result",
            "xrEnumerateSwapchainImagesResult=success",
            "sdkSceneObjectConversion=false",
            "queueSubmissionAttempted=false",
            "swapchainImageWriteAttempted=false",
            "channel=external-xr-swapchain-wrap-probe status=complete"
        )
} catch {
    $runError = $_
} finally {
    [void](Invoke-SerialAdbText -Arguments @("shell", "am", "force-stop", $packageName) -AllowFailure)
    $restoreFailures = @()
    foreach ($name in $priorProperties.Keys) {
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
    status =
        if ($null -ne $runError) {
            "failed"
        } elseif (@($phases | Where-Object { $_.status -eq "structural-pass-visual-unconfirmed" }).Count -gt 0) {
            "partial"
        } else {
            "passed"
        }
    transport = $transport
    serial = $Serial
    device_model = $deviceModel
    build_incremental = $buildIncremental
    package = $packageName
    component = $componentName
    apk_path = $apkFile.FullName
    apk_sha256 = (Get-FileHash -LiteralPath $apkFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    install_output = $installOutput
    camera_projection_disabled = $true
    video_layers_disabled = $true
    queue_submission_policy = "fail-closed"
    visibility_acceptance =
        if (@($phases | Where-Object { $_.status -eq "structural-pass-visual-unconfirmed" }).Count -gt 0) {
            "needs-headset-or-supported-capture-witness"
        } else {
            "complete-for-declared-phase-requirements"
        }
    phases = $phases
    prior_properties = $priorProperties
    restored_properties = $restoredProperties
    restore_verified = $restoreVerified
    error = if ($null -eq $runError) { $null } else { $runError.Exception.Message }
}
$summaryPath = Join-Path $artifactDir.FullName "summary.json"
[System.IO.File]::WriteAllText(
    $summaryPath,
    ($summary | ConvertTo-Json -Depth 10),
    [System.Text.UTF8Encoding]::new($false)
)

if ($null -ne $runError) { throw $runError }
Write-Output $summaryPath
