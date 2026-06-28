param(
    [string]$RepoRoot,
    [string]$ApkPath = "target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk",
    [string]$OutDir = "",
    [int]$RunSeconds = 12,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.spatial_camera_panel",
    [string]$Activity = "io.github.mesmerprism.rustyquest.spatial_camera_panel/.SpatialCameraPanelActivity",
    [int]$ReaderMaxImages = 4,
    [switch]$SkipInstall,
    [switch]$ClearLogcat,
    [switch]$StopAfterRun,
    [switch]$AllowMissingMarkers
)

$ErrorActionPreference = "Stop"

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
        [Parameter(Mandatory=$true)][string[]]$Arguments,
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

function Get-FileSha256 {
    param([Parameter(Mandatory=$true)][string]$Path)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToUpperInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Save-Text {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [AllowNull()][string]$Text
    )

    if ($null -eq $Text) {
        $Text = ""
    }
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.Encoding]::UTF8)
}

function Test-TextContains {
    param(
        [string]$Text,
        [string]$Needle
    )
    return $Text.Contains($Needle)
}

function Assert-SummaryFlag {
    param(
        [System.Collections.IDictionary]$Summary,
        [string]$Name
    )

    if (-not [bool]$Summary[$Name]) {
        throw "Spatial Camera Panel camera_hwb_projection_smoke evidence missing required flag: $Name"
    }
}

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$repoRootPath = Resolve-Path -LiteralPath $RepoRoot

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "-Serial or RUSTY_QUEST_SERIAL is required; Spatial SDK headset validation must use adb -s <serial>."
}

$resolvedApk = if ([System.IO.Path]::IsPathRooted($ApkPath)) {
    $ApkPath
} else {
    Join-Path $repoRootPath $ApkPath
}
if (-not (Test-Path -LiteralPath $resolvedApk)) {
    throw "APK not found: $resolvedApk"
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRootPath "local-artifacts\spatial-camera-panel-headset\$stamp-camera-hwb-projection-smoke"
} elseif (-not [System.IO.Path]::IsPathRooted($OutDir)) {
    $OutDir = Join-Path $repoRootPath $OutDir
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$script:ResolvedAdb = Resolve-ToolPath `
    -Name "adb" `
    -Value $Adb `
    -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
$script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument -Value $AdbServerPort
$script:Serial = $Serial

$readerMaxImagesClamped = [Math]::Max(3, [Math]::Min(12, $ReaderMaxImages))
$apkSha256 = Get-FileSha256 -Path $resolvedApk
$summaryPath = Join-Path $OutDir "evidence-summary.json"
$tagLogcatStreamPath = Join-Path $OutDir "tag-logcat-stream.txt"
$tagLogcatErrorPath = Join-Path $OutDir "tag-logcat-stream.stderr.txt"
$pidLogcatPath = Join-Path $OutDir "pid-logcat.txt"
$allLogcatPath = Join-Path $OutDir "logcat-all.txt"
$windowFocusPath = Join-Path $OutDir "window-focus.txt"
$screenshotPath = Join-Path $OutDir "screencap.png"
$remoteScreenshotPath = "/data/local/tmp/rusty-quest-spatial-camera-hwb-projection-smoke.png"

$summary = [ordered]@{
    '$schema' = "rusty.quest.spatial_camera_panel.camera_hwb_projection_smoke.v1"
    wrapper = "Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1"
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    status = "started"
    adb_path = $script:ResolvedAdb
    adb_scope = "device-scoped-adb"
    adb_serial_required = $true
    adb_server_port = $script:ResolvedAdbServerPort
    serial = $Serial
    package = $PackageName
    activity = $Activity
    apk_path = (Resolve-Path -LiteralPath $resolvedApk).Path
    apk_sha256 = $apkSha256
    out_dir = (Resolve-Path -LiteralPath $OutDir).Path
    run_seconds = [Math]::Max(1, $RunSeconds)
    skipped_install = [bool]$SkipInstall
    clear_logcat_requested = [bool]$ClearLogcat
    stop_after_run = [bool]$StopAfterRun
    allow_missing_markers = [bool]$AllowMissingMarkers
    reader_max_images = $readerMaxImagesClamped
    carrier = "scenequadlayer-createAsAndroid-vulkan-wsi"
    tag_logcat_stream_path = $tagLogcatStreamPath
    tag_logcat_error_path = $tagLogcatErrorPath
    pid_logcat_path = $pidLogcatPath
    all_logcat_path = $allLogcatPath
    window_focus_path = $windowFocusPath
    screenshot_path = $screenshotPath
}

$logcatProcess = $null

try {
    $state = Invoke-AdbCommand -Name "adb get-state" -Arguments @("get-state")
    $summary.device_state = $state.output.Trim()
    if ($summary.device_state -ne "device") {
        throw "ADB target is not ready: $($summary.device_state)"
    }
    Save-Text -Path (Join-Path $OutDir "adb-device-state.txt") -Text $summary.device_state
    $summary.device_model = (Invoke-AdbCommand -Name "device model" -Arguments @("shell", "getprop", "ro.product.model")).output.Trim()
    $summary.device_build = (Invoke-AdbCommand -Name "device build" -Arguments @("shell", "getprop", "ro.build.version.incremental")).output.Trim()

    if ($ClearLogcat) {
        Invoke-AdbCommand -Name "clear logcat" -Arguments @("logcat", "-c") | Out-Null
    }

    if (-not $SkipInstall) {
        $install = Invoke-AdbCommand -Name "install Spatial SDK APK" -Arguments @("install", "-r", "-d", "-g", (Resolve-Path -LiteralPath $resolvedApk).Path)
        Save-Text -Path (Join-Path $OutDir "install.txt") -Text $install.output
    }

    $setpropResults = @()
    $setpropResults += Invoke-AdbCommand -Name "disable luma camera HWB probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_probe", "0")
    $setpropResults += Invoke-AdbCommand -Name "enable raw camera projection probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe", "1")
    $setpropResults += Invoke-AdbCommand -Name "set raw camera projection reader max images" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe.reader_max_images", $readerMaxImagesClamped.ToString())
    Save-Text -Path (Join-Path $OutDir "setprops.json") -Text ($setpropResults | ConvertTo-Json -Depth 6)

    Invoke-AdbCommand -Name "force-stop Spatial SDK app" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null

    $logcatArgs = @()
    if ($null -ne $script:ResolvedAdbServerPort) {
        $logcatArgs += @("-P", $script:ResolvedAdbServerPort)
    }
    $logcatArgs += @(
        "-s", $Serial,
        "logcat", "-v", "time",
        "RQSpatialCameraPanel:D",
        "RQSpatialCameraPanelNative:D",
        "*:S"
    )
    $logcatProcess = Start-Process `
        -FilePath $script:ResolvedAdb `
        -ArgumentList $logcatArgs `
        -RedirectStandardOutput $tagLogcatStreamPath `
        -RedirectStandardError $tagLogcatErrorPath `
        -PassThru `
        -WindowStyle Hidden
    Start-Sleep -Milliseconds 300

    $launch = Invoke-AdbCommand -Name "launch raw camera projection probe" -Arguments @("shell", "am", "start", "-W", "-n", $Activity)
    Save-Text -Path (Join-Path $OutDir "launch.txt") -Text $launch.output
    Start-Sleep -Seconds ([Math]::Max(1, $RunSeconds))

    $pidResult = Invoke-AdbCommand -Name "Spatial SDK app pid" -Arguments @("shell", "pidof", $PackageName) -AllowFailure
    $targetPid = (($pidResult.output -split "\s+") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
    $summary.pid = $targetPid
    Save-Text -Path (Join-Path $OutDir "pid.txt") -Text $targetPid

    if (-not [string]::IsNullOrWhiteSpace($targetPid)) {
        $pidLogcat = (Invoke-AdbCommand -Name "dump pid-scoped logcat" -Arguments @("logcat", "-d", "-v", "time", "--pid", $targetPid) -AllowFailure).output
    } else {
        $pidLogcat = ""
    }
    Save-Text -Path $pidLogcatPath -Text $pidLogcat
    $allLogcat = (Invoke-AdbCommand -Name "dump full logcat" -Arguments @("logcat", "-d", "-v", "time") -AllowFailure).output
    Save-Text -Path $allLogcatPath -Text $allLogcat

    $windowFocus = (Invoke-AdbCommand -Name "dump window focus" -Arguments @("shell", "dumpsys", "window") -AllowFailure).output
    Save-Text -Path $windowFocusPath -Text $windowFocus

    Invoke-AdbCommand -Name "capture screenshot" -Arguments @("shell", "screencap", "-p", $remoteScreenshotPath) -AllowFailure | Out-Null
    Invoke-AdbCommand -Name "pull screenshot" -Arguments @("pull", $remoteScreenshotPath, $screenshotPath) -AllowFailure | Out-Null
    Invoke-AdbCommand -Name "remove remote screenshot" -Arguments @("shell", "rm", $remoteScreenshotPath) -AllowFailure | Out-Null

    if ($null -ne $logcatProcess -and -not $logcatProcess.HasExited) {
        Stop-Process -Id $logcatProcess.Id -Force
        $logcatProcess.WaitForExit()
    }
    Start-Sleep -Milliseconds 250

    $tagLogcat = if (Test-Path -LiteralPath $tagLogcatStreamPath) {
        Get-Content -Raw -LiteralPath $tagLogcatStreamPath
    } else {
        ""
    }
    $evidenceText = "$tagLogcat`n$pidLogcat`n$allLogcat"

    $summary.start = (Test-TextContains $evidenceText "status=start") -and (Test-TextContains $evidenceText "rawCameraProjectionProbe=true")
    $summary.layer_created = Test-TextContains $evidenceText "status=raw-camera-projection-layer-created"
    $summary.native_start_requested = Test-TextContains $evidenceText "status=native-start-requested"
    $summary.render_loop_ready = Test-TextContains $evidenceText "status=render-loop-ready"
    $summary.camera_runtime_started = Test-TextContains $evidenceText "status=camera-runtime-started"
    $summary.ahb_properties = Test-TextContains $evidenceText "status=ahb-properties"
    $summary.resources_created = Test-TextContains $evidenceText "status=probe-resources-created"
    $summary.ahb_imported = Test-TextContains $evidenceText "status=ahb-imported"
    $summary.first_frame_presented = Test-TextContains $evidenceText "status=first-camera-frame-presented"
    $summary.raw_frame_presented = Test-TextContains $evidenceText "status=raw-camera-frame-presented"
    $summary.camera_frame_acquired = Test-TextContains $evidenceText "status=camera-frame-acquired"
    $summary.stereo_source_camera_50_51 = Test-TextContains $evidenceText "stereoSource=camera50-51"
    $summary.left_camera_50 = Test-TextContains $evidenceText "leftCameraId=50"
    $summary.right_camera_51 = Test-TextContains $evidenceText "rightCameraId=51"
    $summary.output_raw_color_target_rect = Test-TextContains $evidenceText "outputMode=raw-color-target-rect"
    $summary.target_clip_policy = Test-TextContains $evidenceText "targetClipPolicy=clip-to-visible-eye"
    $summary.mapping_mode_target_local_raster = Test-TextContains $evidenceText "projectionContentMappingMode=target-local-raster"
    $summary.mono_duplicated_false = Test-TextContains $evidenceText "monoDuplicated=false"
    $summary.private_shader_stack_false = Test-TextContains $evidenceText "privateShaderStack=false"
    $summary.custom_projection_stack_false = Test-TextContains $evidenceText "customProjectionStack=false"
    $summary.runtime_crash_false = (Test-TextContains $evidenceText "runtimeCrash=false") -and -not ($evidenceText -match "AndroidRuntime|FATAL|render-failed")
    $summary.screenshot_captured = Test-Path -LiteralPath $screenshotPath

    $requiredFlags = @(
        "start",
        "layer_created",
        "native_start_requested",
        "render_loop_ready",
        "camera_runtime_started",
        "ahb_properties",
        "resources_created",
        "ahb_imported",
        "first_frame_presented",
        "raw_frame_presented",
        "camera_frame_acquired",
        "stereo_source_camera_50_51",
        "left_camera_50",
        "right_camera_51",
        "output_raw_color_target_rect",
        "target_clip_policy",
        "mapping_mode_target_local_raster",
        "mono_duplicated_false",
        "private_shader_stack_false",
        "custom_projection_stack_false",
        "runtime_crash_false"
    )
    if (-not $AllowMissingMarkers) {
        foreach ($flag in $requiredFlags) {
            Assert-SummaryFlag -Summary $summary -Name $flag
        }
    }

    if ($StopAfterRun) {
        $stop = Invoke-AdbCommand -Name "stop Spatial SDK app" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure
        Save-Text -Path (Join-Path $OutDir "stop.txt") -Text $stop.output
        $disable = Invoke-AdbCommand -Name "disable raw camera projection probe" -Arguments @("shell", "setprop", "debug.rustyquest.spatial.camera_hwb_projection_probe", "0") -AllowFailure
        Save-Text -Path (Join-Path $OutDir "disable-projection-probe.txt") -Text $disable.output
    }

    $summary.status = if ($AllowMissingMarkers) { "completed" } else { "passed" }
} catch {
    $summary.status = "failed"
    $summary.error = $_.Exception.Message
    throw
} finally {
    if ($null -ne $logcatProcess -and -not $logcatProcess.HasExited) {
        try {
            Stop-Process -Id $logcatProcess.Id -Force
            $logcatProcess.WaitForExit()
        } catch {
            $summary.logcat_stop_error = $_.Exception.Message
        }
    }
    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $summaryPath
}

Write-Output "Spatial Camera Panel camera_hwb_projection_smoke evidence: $summaryPath"
Write-Output "APK_SHA256=$apkSha256"
Write-Output "OUT_DIR=$((Resolve-Path -LiteralPath $OutDir).Path)"
