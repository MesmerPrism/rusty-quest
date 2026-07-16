param(
    [string]$ApkPath = "target\native-renderer-android\rusty-quest-native-renderer.apk",
    [string]$ProfilePath = "fixtures\runtime-profiles\quest-native-renderer-display-composite-feedback.profile.json",
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.native_renderer",
    [string]$Activity = "io.github.mesmerprism.rustyquest.native_renderer/.ControlPanelActivity",
    [string]$NativeActivity = "io.github.mesmerprism.rustyquest.native_renderer/android.app.NativeActivity",
    [int]$NativeLaunchDelaySeconds = 2,
    [int]$RunSeconds = 12,
    [string]$OutDir = "",
    [switch]$SkipInstall,
    [switch]$ClearLogcat,
    [switch]$KeepMediaProjectionAppOp,
    [switch]$StopAfterRun
)

$ErrorActionPreference = "Stop"

function Resolve-ToolPath {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [string]$Value,
        [string]$DefaultPath
    )

    if (-not [string]::IsNullOrWhiteSpace($Value)) {
        if (Test-Path $Value) {
            return (Resolve-Path $Value).Path
        }
        $command = Get-Command $Value -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
        throw "$Name not found: $Value"
    }

    if (-not [string]::IsNullOrWhiteSpace($DefaultPath) -and (Test-Path $DefaultPath)) {
        return (Resolve-Path $DefaultPath).Path
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
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string[]]$Arguments,
        [switch]$AllowFailure,
        [int]$TimeoutSeconds = 120
    )

    $adbArgs = @()
    if ($null -ne $script:ResolvedAdbServerPort) {
        $adbArgs += @("-P", $script:ResolvedAdbServerPort)
    }
    $adbArgs += @("-s", $script:Serial)
    $adbArgs += $Arguments

    $stdoutPath = [IO.Path]::GetTempFileName()
    $stderrPath = [IO.Path]::GetTempFileName()
    $quotedArgs = @($adbArgs | ForEach-Object {
        $arg = [string]$_
        if ($arg -match '[\s"]') { '"' + $arg.Replace('"', '\"') + '"' } else { $arg }
    })
    $process = $null
    try {
        $process = Start-Process -FilePath $script:ResolvedAdb -ArgumentList $quotedArgs -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru -WindowStyle Hidden
        if (-not $process.WaitForExit([Math]::Max(1, $TimeoutSeconds) * 1000)) {
            try { $process.Kill($true) } catch {}
            $exitCode = 124
            $output = "adb command timed out after $TimeoutSeconds seconds."
        } else {
            $stdout = if (Test-Path -LiteralPath $stdoutPath) { Get-Content -Raw -LiteralPath $stdoutPath } else { "" }
            $stderr = if (Test-Path -LiteralPath $stderrPath) { Get-Content -Raw -LiteralPath $stderrPath } else { "" }
            $exitCode = $process.ExitCode
            $output = (@($stdout, $stderr) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "`n"
        }
    } finally {
        if ($null -ne $process) { $process.Dispose() }
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
    $result = [ordered]@{
        name = $Name
        arguments = $Arguments
        exit_code = $exitCode
        output = $output
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "$Name failed with exit code $exitCode`n$($result.output)"
    }
    return $result
}

function Invoke-CheckedPowershell {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string[]]$Arguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & pwsh @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "$Name failed with exit code $exitCode`n$($output -join "`n")"
    }
    return ($output -join "`n")
}

function Assert-LogcatContains {
    param(
        [string[]]$Lines,
        [string]$Pattern,
        [string]$Label
    )
    if (-not ($Lines | Where-Object { $_ -match $Pattern } | Select-Object -First 1)) {
        throw "Display-composite smoke missing $Label marker: $Pattern"
    }
}

function Assert-LogcatNotContains {
    param(
        [string[]]$Lines,
        [string]$Pattern,
        [string]$Label
    )
    if ($Lines | Where-Object { $_ -match $Pattern } | Select-Object -First 1) {
        throw "Display-composite smoke found forbidden $Label marker: $Pattern"
    }
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$script:ResolvedAdb = Resolve-ToolPath `
    -Name "adb" `
    -Value $Adb `
    -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
$script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument -Value $AdbServerPort

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "-Serial or RUSTY_QUEST_SERIAL is required; display-composite smoke must use adb -s <serial>."
}
$script:Serial = $Serial

$resolvedApk = if ([System.IO.Path]::IsPathRooted($ApkPath)) {
    $ApkPath
} else {
    Join-Path $repoRoot $ApkPath
}
if (-not (Test-Path $resolvedApk)) {
    throw "APK not found: $resolvedApk"
}

$resolvedProfile = if ([System.IO.Path]::IsPathRooted($ProfilePath)) {
    $ProfilePath
} else {
    Join-Path $repoRoot $ProfilePath
}
if (-not (Test-Path $resolvedProfile)) {
    throw "Runtime profile not found: $resolvedProfile"
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRoot "local-artifacts\native-renderer-display-composite-smoke\$stamp"
} elseif (-not [System.IO.Path]::IsPathRooted($OutDir)) {
    $OutDir = Join-Path $repoRoot $OutDir
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$permissionPregrantPath = Join-Path $OutDir "permission-pregrant.json"
$permissionResetPath = Join-Path $OutDir "permission-reset.json"
$propertyPlanPath = Join-Path $OutDir "property-write-plan.json"
$rawLogcatPath = Join-Path $OutDir "raw-logcat.txt"
$filteredLogcatPath = Join-Path $OutDir "filtered-display-composite-logcat.txt"
$serviceStatePath = Join-Path $OutDir "dumpsys-services.txt"
$screenshotPath = Join-Path $OutDir "display-composite-smoke.png"
$summaryPath = Join-Path $OutDir "run-summary.json"
$remoteScreenshotPath = "/data/local/tmp/rusty-quest-display-composite-smoke.png"
$requestToken = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

$summary = [ordered]@{
    schema = "rusty.quest.native_renderer_display_composite_smoke.v1"
    wrapper = "Invoke-NativeRendererDisplayCompositeSmoke.ps1"
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    adb_path = $script:ResolvedAdb
    adb_scope = "device-scoped-adb"
    serial = $Serial
    adb_server_port = $script:ResolvedAdbServerPort
    package_name = $PackageName
    capture_activity = $Activity
    native_activity = $NativeActivity
    native_launch_delay_seconds = [Math]::Max(0, $NativeLaunchDelaySeconds)
    force_stop_before_launch = $true
    apk_path = (Resolve-Path $resolvedApk).Path
    profile_path = (Resolve-Path $resolvedProfile).Path
    run_seconds = [Math]::Max(1, $RunSeconds)
    skip_install = [bool]$SkipInstall
    clear_logcat_requested = [bool]$ClearLogcat
    logcat_scope = $(if ($ClearLogcat) { "cleared-marker-device-logcat" } else { "pid-scoped-device-logcat" })
    display_composite_request_token = $requestToken
    media_projection_appop_reset_after_run = -not [bool]$KeepMediaProjectionAppOp
    property_plan_path = $propertyPlanPath
    permission_pregrant_path = $permissionPregrantPath
    permission_reset_path = $permissionResetPath
    raw_logcat_path = $rawLogcatPath
    filtered_logcat_path = $filteredLogcatPath
    service_state_path = $serviceStatePath
    screenshot_path = $screenshotPath
}

try {
    $state = Invoke-AdbCommand -Name "adb get-state" -Arguments @("get-state")
    $summary.device_state = $state.output.Trim()
    if ($summary.device_state -ne "device") {
        throw "ADB target is not ready: $($summary.device_state)"
    }
    $summary.device_model = (Invoke-AdbCommand -Name "device model" -Arguments @("shell", "getprop", "ro.product.model")).output.Trim()
    $summary.device_build = (Invoke-AdbCommand -Name "device build" -Arguments @("shell", "getprop", "ro.build.version.incremental")).output.Trim()

    if (-not $SkipInstall) {
        $summary.install_output = (Invoke-AdbCommand -Name "install APK" -Arguments @("install", "-r", "-d", "-g", (Resolve-Path $resolvedApk).Path)).output
    }

    $pregrantArgs = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $PSScriptRoot "Grant-NativeRendererPermissions.ps1"),
        "-Adb", $script:ResolvedAdb,
        "-Serial", $Serial,
        "-PackageName", $PackageName,
        "-GrantMediaProjectionAppOp",
        "-Out", $permissionPregrantPath
    )
    if ($null -ne $script:ResolvedAdbServerPort) {
        $pregrantArgs += @("-AdbServerPort", $script:ResolvedAdbServerPort)
    }
    $summary.permission_pregrant_output = Invoke-CheckedPowershell -Name "native renderer permission pregrant" -Arguments $pregrantArgs

    $profileArgs = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $PSScriptRoot "Apply-RuntimeProfile.ps1"),
        "-ProfilePath", (Resolve-Path $resolvedProfile).Path,
        "-Execute",
        "-Out", $propertyPlanPath,
        "-Adb", $script:ResolvedAdb,
        "-Serial", $Serial
    )
    if ($null -ne $script:ResolvedAdbServerPort) {
        $profileArgs += @("-AdbServerPort", $script:ResolvedAdbServerPort)
    }
    $summary.profile_apply_output = Invoke-CheckedPowershell -Name "runtime profile apply" -Arguments $profileArgs

    $summary.force_stop_before_launch_output = (Invoke-AdbCommand -Name "force-stop before native launch" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure).output

    if ($ClearLogcat) {
        Invoke-AdbCommand -Name "clear logcat" -Arguments @("logcat", "-c") | Out-Null
    }

    $summary.native_launch_output = (Invoke-AdbCommand -Name "launch native renderer XR consumer" -Arguments @(
        "shell",
        "am",
        "start",
        "-W",
        "-n", $NativeActivity
    )).output

    if ($summary.native_launch_delay_seconds -gt 0) {
        Start-Sleep -Seconds $summary.native_launch_delay_seconds
    }

    $summary.capture_launch_output = (Invoke-AdbCommand -Name "launch display-composite capture request" -Arguments @(
        "shell",
        "am",
        "start",
        "-W",
        "-a", "io.github.mesmerprism.rustyquest.native_renderer.action.REQUEST_DISPLAY_COMPOSITE_CAPTURE",
        "-n", $Activity,
        "--el", "display_composite_request_token", $requestToken.ToString()
    )).output

    $summary.target_pid = ""
    foreach ($attempt in 1..10) {
        $pidResult = Invoke-AdbCommand -Name "native renderer pid" -Arguments @("shell", "pidof", $PackageName) -AllowFailure
        $summary.target_pid = (($pidResult.output -split "\s+") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
        if (-not [string]::IsNullOrWhiteSpace($summary.target_pid)) { break }
        Start-Sleep -Milliseconds 250
    }
    if ([string]::IsNullOrWhiteSpace($summary.target_pid)) {
        throw "Native renderer process id was not available after launch; refusing unscoped logcat evidence."
    }
    Start-Sleep -Seconds ([Math]::Max(1, $RunSeconds))

    if ($ClearLogcat) {
        $rawLogcat = (Invoke-AdbCommand -Name "dump cleared marker logcat" -Arguments @("logcat", "-d", "-v", "time")).output
    } else {
        $rawLogcat = (Invoke-AdbCommand -Name "dump pid-scoped logcat" -Arguments @("logcat", "-d", "-v", "time", "--pid", $summary.target_pid)).output
    }
    Set-Content -Encoding UTF8 -Path $rawLogcatPath -Value $rawLogcat
    $filtered = @($rawLogcat -split "`r?`n" | Where-Object {
        $_ -match "RUSTY_QUEST_NATIVE_RENDERER" -and
        ($_ -match "display-composite" -or
         $_ -match "display_composite" -or
         $_ -match "render-mode" -or
         $_ -match "camera-settings" -or
         $_ -match "camera-runtime" -or
         $_ -match "camera-projection" -or
         $_ -match "native-passthrough" -or
         $_ -match "projection-target" -or
         $_ -match "timing-scorecard" -or
         $_ -match "render-loop" -or
         $_ -match "guide-blur-graph" -or
         $_ -match "hand-mesh-visual" -or
         $_ -match "hand-mesh-visual-diagnostic" -or
         $_ -match "environment-depth" -or
         $_ -match "stimulus-volume" -or
         $_ -match "private-extension-slot")
    })
    [System.IO.File]::WriteAllLines($filteredLogcatPath, [string[]]$filtered)
    $serviceState = (Invoke-AdbCommand -Name "dump display-composite service state" -Arguments @("shell", "dumpsys", "activity", "services", $PackageName)).output
    Set-Content -Encoding UTF8 -Path $serviceStatePath -Value $serviceState

    Assert-LogcatContains -Lines $filtered -Pattern "renderMode=native-passthrough-media-only" -Label "native passthrough media-only render mode"
    Assert-LogcatContains -Lines $filtered -Pattern "customStereoProjectionEnabled=false" -Label "custom camera projection disabled"
    Assert-LogcatContains -Lines $filtered -Pattern "handMeshGraftCopiesEnabled=false" -Label "graft copies disabled"
    Assert-LogcatContains -Lines $filtered -Pattern "cameraOutputMode=disabled" -Label "camera output disabled"
    Assert-LogcatContains -Lines $filtered -Pattern "guideGraphBlurEnabled=false" -Label "guide blur disabled"
    Assert-LogcatContains -Lines $filtered -Pattern "cameraRuntimeMode=skipped-native-passthrough-media-only" -Label "camera runtime skipped for native passthrough"
    Assert-LogcatContains -Lines $filtered -Pattern "camera_frames_acquired=0" -Label "camera acquisition disabled"
    Assert-LogcatContains -Lines $filtered -Pattern "hardware_buffer_imports=0" -Label "camera hardware-buffer import disabled"
    Assert-LogcatContains -Lines $filtered -Pattern "nativePassthroughLayerActive=true" -Label "native passthrough active"
    Assert-LogcatContains -Lines $filtered -Pattern "projection-target.*projectionTargetBaseScale=1\.0000" -Label "projection target default scale"
    Assert-LogcatContains -Lines $filtered -Pattern "projection-target.*projectionTargetOffsetUv=0\.000000,0\.000000" -Label "projection target zero offset"
    Assert-LogcatContains -Lines $filtered -Pattern "environmentDepthMode=disabled" -Label "environment depth disabled"
    Assert-LogcatContains -Lines $filtered -Pattern "stimulusVolumeEnabled=false" -Label "stimulus volume disabled"
    Assert-LogcatContains -Lines $filtered -Pattern "privateLayerEnabled=false" -Label "private layer disabled"
    Assert-LogcatNotContains -Lines $filtered -Pattern "cameraProjectionReady=true" -Label "ready custom camera projection"
    $serviceStartMarker = $filtered | Where-Object { $_ -match "display-composite-service.*status=start-requested" } | Select-Object -First 1
    if (-not $serviceStartMarker) {
        if ($serviceState -notmatch "DisplayCompositeProjectionService" -or
            $serviceState -notmatch "isForeground=true" -or
            $serviceState -notmatch "startRequested=true") {
            throw "Display-composite smoke missing service lifecycle evidence: no service marker and dumpsys did not show foreground startRequested DisplayCompositeProjectionService."
        }
    }
    $nativeStreamMarker = $filtered | Where-Object { $_ -match "display-composite-native-stream.*status=surface-created" } | Select-Object -First 1
    if (-not $nativeStreamMarker) {
        Assert-LogcatContains -Lines $filtered -Pattern "display-composite-ahardware-buffer.*status=frame" -Label "native stream frame fallback"
    }
    Assert-LogcatContains -Lines $filtered -Pattern "display-composite-ahardware-buffer.*status=frame" -Label "AHardwareBuffer frame"
    $projectionMetadataMarker = $filtered | Where-Object { $_ -match "display-composite-projection-metadata.*status=loaded" } | Select-Object -First 1
    if (-not $projectionMetadataMarker) {
        Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeFeedbackProjection=metadata-target-screen-uv" -Label "display-composite projection metadata render fallback"
    }
    Assert-LogcatContains -Lines $filtered -Pattern "display-composite-feedback.*status=rendered" -Label "display-composite rendered feedback"
    Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeFeedbackRendered=true" -Label "display-composite rendered marker"
    Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeGpuImportReady=true" -Label "display-composite GPU import"
    Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeExternalFormatSampling=true" -Label "display-composite external-format sampling"
    Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeSamplerYcbcrConversion=true" -Label "display-composite sampler conversion"
    Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeRecursiveFeedbackEnabled=true" -Label "display-composite recursive feedback enabled"
    Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeRecursiveFeedbackReady=true" -Label "display-composite recursive feedback ready"
    Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeRecursiveFeedbackSource=media-projection-current-frame-clean" -Label "display-composite clean feedback source"
    Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeRecursiveFeedbackPreviousBlend=false" -Label "display-composite previous feedback blend disabled"
    Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeRecursiveFeedbackBorderOpacity=0.000" -Label "display-composite recursive border disabled"
    Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeFinalBorderOpacityLeft=0.000" -Label "display-composite final left border disabled"
    Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeFinalBorderOpacityRight=0.000" -Label "display-composite final right border disabled"
    Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeFinalPlaneOpacity=1.000" -Label "display-composite final plane opacity"
    Assert-LogcatContains -Lines $filtered -Pattern "displayCompositeFinalAlphaMode=premultiplied-openxr-projection-layer" -Label "display-composite premultiplied alpha mode"
    Assert-LogcatContains -Lines $filtered -Pattern "nativeImageReader=true" -Label "native ImageReader"
    Assert-LogcatContains -Lines $filtered -Pattern "javaHardwareBufferBridge=false" -Label "Java hardware-buffer bridge disabled"
    Assert-LogcatContains -Lines $filtered -Pattern "cpuPixelCopy=false" -Label "CPU pixel copy disabled"
    $summary.marker_validation_status = "passed"

    Invoke-AdbCommand -Name "capture screenshot" -Arguments @("shell", "screencap", "-p", $remoteScreenshotPath) | Out-Null
    Invoke-AdbCommand -Name "pull screenshot" -Arguments @("pull", $remoteScreenshotPath, $screenshotPath) | Out-Null
    Invoke-AdbCommand -Name "remove remote screenshot" -Arguments @("shell", "rm", $remoteScreenshotPath) -AllowFailure | Out-Null

    $summary.status = "completed"
} catch {
    $summary.status = "failed"
    $summary.error = $_.Exception.Message
    throw
} finally {
    if (-not $KeepMediaProjectionAppOp) {
        try {
            $resetArgs = @(
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-File", (Join-Path $PSScriptRoot "Grant-NativeRendererPermissions.ps1"),
                "-Adb", $script:ResolvedAdb,
                "-Serial", $Serial,
                "-PackageName", $PackageName,
                "-ResetMediaProjectionAppOp",
                "-Out", $permissionResetPath
            )
            if ($null -ne $script:ResolvedAdbServerPort) {
                $resetArgs += @("-AdbServerPort", $script:ResolvedAdbServerPort)
            }
            $summary.permission_reset_output = Invoke-CheckedPowershell -Name "native renderer permission reset" -Arguments $resetArgs
        } catch {
            $summary.permission_reset_error = $_.Exception.Message
        }
    }
    if ($StopAfterRun) {
        try {
            $summary.force_stop_output = (Invoke-AdbCommand -Name "force-stop native renderer" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure).output
        } catch {
            $summary.force_stop_error = $_.Exception.Message
        }
    }
    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $summaryPath
    Write-Output "native renderer display-composite smoke summary written: $summaryPath"
}
