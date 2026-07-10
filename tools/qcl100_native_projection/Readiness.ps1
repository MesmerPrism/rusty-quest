# Dot-sourced helper functions for Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1.
# Keep these functions side-effect free until called by the runner facade.

function Prepare-QuestForXrFocus {
    param(
        [string]$Serial,
        [string]$Label,
        [switch]$SkipWakePrep,
        [switch]$AllowWakePrepMutation
    )
    if ($SkipWakePrep) {
        return [ordered]@{
            skipped = $true
            serial = $Serial
            label = $Label
            policy = "external_keep_awake_managed"
            mutations_performed = $false
        }
    }
    if (-not $AllowWakePrepMutation) {
        throw "Prepare-QuestForXrFocus mutates Quest wake state. Pass -SkipWakePrep when an external keep-awake/watchdog thread owns headset state, or pass -AllowWakePrepMutation only for an explicitly approved runner-owned wake-prep mutation."
    }

    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "input", "keyevent", "224")
    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "svc", "power", "stayon", "true")
    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "settings", "put", "system", "screen_off_timeout", "2147483647")
    Invoke-AdbBestEffort -Serial $Serial -Arguments @("shell", "settings", "put", "secure", "sleep_timeout", "-1")

    Start-Sleep -Milliseconds 500
    $power = Read-AdbText -Serial $Serial -Arguments @("shell", "dumpsys", "power") -Path (Join-Path $MediaDir "$Label-power-after-wake-prep.txt")
    $display = Read-AdbText -Serial $Serial -Arguments @("shell", "dumpsys", "display") -Path (Join-Path $MediaDir "$Label-display-after-wake-prep.txt")
    $mounted = (Read-AdbText -Serial $Serial -Arguments @("shell", "getprop", "sys.hmt.mounted")).Trim()
    [ordered]@{
        skipped = $false
        serial = $Serial
        label = $Label
        sys_hmt_mounted = $mounted
        wakefulness_awake = [bool]($power -match "mWakefulness=Awake")
        display_on = [bool]($display -match "mScreenState=ON|state=ON|Display Power: state=ON")
        stay_on_applied = $true
        policy = "runner_wake_prep_mutation_explicitly_allowed"
        mutations_performed = $true
    }
}

function Get-QuestXrLaunchReadiness {
    param([string]$Serial, [string]$Label)

    $activityPath = Join-Path $MediaDir "$Label-xr-launch-readiness-activity.txt"
    $windowPath = Join-Path $MediaDir "$Label-xr-launch-readiness-window.txt"
    $activity = Read-AdbText -Serial $Serial -Arguments @("shell", "dumpsys", "activity", "activities") -Path $activityPath
    $window = Read-AdbText -Serial $Serial -Arguments @("shell", "dumpsys", "window") -Path $windowPath
    $mounted = (Read-AdbText -Serial $Serial -Arguments @("shell", "getprop", "sys.hmt.mounted")).Trim()

    $sensorLockActive = [bool]($activity -match "com\.oculus\.os\.vrlockscreen/.SensorLockActivity" -or $window -match "com\.oculus\.os\.vrlockscreen/.SensorLockActivity")
    $volumetricSystemDialogActive = [bool]($activity -match "VolumetricSystemDialog" -or $window -match "VolumetricSystemDialog")
    $focusPlaceholderActive = [bool]($activity -match "com\.oculus\.vrshell/.FocusPlaceholderActivity" -or $window -match "com\.oculus\.vrshell/.FocusPlaceholderActivity")
    $reprojectedDialogSeen = [bool]($activity -match "Reprojected OS dialog" -or $window -match "Reprojected OS dialog")
    $awake = [bool]($window -match "mAwake=true")
    $screenOn = [bool]($window -match "mScreenOnEarly=true|mScreenOnFully=true")

    $currentFocus = ""
    $focusMatch = [regex]::Match($window, "mCurrentFocus=([^\r\n]+)")
    if ($focusMatch.Success) {
        $currentFocus = $focusMatch.Groups[1].Value.Trim()
    }

    $issues = @()
    if ($sensorLockActive) {
        $issues += "sensor_lock_active"
    }
    if ($reprojectedDialogSeen) {
        $issues += "reprojected_os_dialog_seen"
    }
    if ($focusPlaceholderActive -and $sensorLockActive) {
        $issues += "focus_placeholder_under_sensor_lock"
    }
    if ($mounted -ne "1") {
        $issues += "headset_not_mounted"
    }

    $ready = [bool]($mounted -eq "1" -and -not $sensorLockActive -and -not $reprojectedDialogSeen)
    $receipt = [ordered]@{
        schema = "rusty.quest.qcl100_xr_launch_readiness.v1"
        serial = $Serial
        label = $Label
        xr_launch_ready = $ready
        sys_hmt_mounted = $mounted
        awake = $awake
        screen_on = $screenOn
        sensor_lock_active = $sensorLockActive
        volumetric_system_dialog_active = $volumetricSystemDialogActive
        focus_placeholder_active = $focusPlaceholderActive
        reprojected_os_dialog_seen = $reprojectedDialogSeen
        current_focus = $currentFocus
        issues = $issues
        activity_artifact = $activityPath
        window_artifact = $windowPath
    }
    $path = Join-Path $MediaDir "$Label-xr-launch-readiness.json"
    Write-JsonFile -Value $receipt -Path $path
    return $receipt
}

function Stop-Qcl100DeviceApps {
    param([string[]]$Serials)
    foreach ($serial in $Serials) {
        Invoke-AdbBestEffort -Serial $serial -Arguments @("forward", "--remove-all")
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", $Qcl041Package)
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", $BrokerPackage)
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", $NativeRendererPackage)
    }
}

function Grant-QclRuntimePermissions {
    param([string]$Serial, [string]$Label)
    $grantResults = @()
    foreach ($grant in @(
        [ordered]@{ package = $Qcl041Package; permission = "android.permission.NEARBY_WIFI_DEVICES" },
        [ordered]@{ package = $Qcl041Package; permission = "android.permission.ACCESS_FINE_LOCATION" },
        [ordered]@{ package = $BrokerPackage; permission = "android.permission.CAMERA" },
        [ordered]@{ package = $BrokerPackage; permission = "horizonos.permission.HEADSET_CAMERA" }
    )) {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $output = & $Adb -s $Serial shell pm grant $grant.package $grant.permission 2>&1 | Out-String
            $exitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        $grantResults += [ordered]@{
            package = $grant.package
            permission = $grant.permission
            exit_code = $exitCode
            output = $output.Trim()
        }
    }
    $receipt = [ordered]@{
        schema = "rusty.quest.qcl100_permission_pregrant.v1"
        serial = $Serial
        label = $Label
        adb_scope = "device-scoped-adb"
        grant_results = $grantResults
    }
    $path = Join-Path $MediaDir "$Label-qcl-broker-permission-pregrant.json"
    Write-JsonFile -Value $receipt -Path $path
    return $path
}

function Grant-NativeRendererPermissions {
    param([string]$Serial, [string]$Label)
    $outPath = Join-Path $MediaDir "$Label-native-renderer-permission-pregrant.json"
    Invoke-External `
        -Name "$Label native renderer permission pregrant" `
        -File "powershell" `
        -Arguments @(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", (Join-Path $Qcl100ToolRoot "Grant-NativeRendererPermissions.ps1"),
            "-Adb", $Adb,
            "-Serial", $Serial,
            "-PackageName", $NativeRendererPackage,
            "-Out", $outPath
        ) `
        -LogPath (Join-Path $MediaDir "$Label-native-renderer-permission-pregrant.stdout.txt") | Out-Null
    return $outPath
}

function Apply-NativeRendererProfile {
    param([string]$Serial, [string]$Label)
    $outPath = Join-Path $MediaDir "$Label-native-renderer-property-write-plan.json"
    Invoke-External `
        -Name "$Label native renderer runtime profile apply" `
        -File "powershell" `
        -Arguments @(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", (Join-Path $Qcl100ToolRoot "Apply-RuntimeProfile.ps1"),
            "-ProfilePath", (Resolve-Path $NativeRendererProfile).Path,
            "-Execute",
            "-Out", $outPath,
            "-Adb", $Adb,
            "-Serial", $Serial
        ) `
        -LogPath (Join-Path $MediaDir "$Label-native-renderer-profile-apply.stdout.txt") | Out-Null
    return $outPath
}

function Apply-NativeRendererLaneModeOverride {
    param([string]$Serial, [string]$Label)
    $leftPort = if ($packedMediaLayout -or $leftLaneActive) { $LeftReceiverPort } else { 0 }
    $rightPort = if ($packedMediaLayout) { 0 } elseif ($rightLaneActive) { $RightReceiverPort } else { 0 }
    Invoke-AdbChecked -Serial $Serial -Arguments @(
        "shell", "setprop",
        "debug.rustyquest.native_renderer.video_projection.broker.left_port",
        $leftPort.ToString()
    ) -Name "$Label native renderer left broker port override"
    Invoke-AdbChecked -Serial $Serial -Arguments @(
        "shell", "setprop",
        "debug.rustyquest.native_renderer.video_projection.broker.right_port",
        $rightPort.ToString()
    ) -Name "$Label native renderer right broker port override"
    Invoke-AdbChecked -Serial $Serial -Arguments @(
        "shell", "setprop",
        "debug.rustyquest.native_renderer.video_projection.broker.connect_timeout_ms",
        $NativeRendererBrokerConnectTimeoutMs.ToString()
    ) -Name "$Label native renderer broker socket timeout override"
    Invoke-AdbChecked -Serial $Serial -Arguments @(
        "shell", "setprop",
        "debug.rustyquest.native_renderer.video_projection.broker.media_layout",
        $MediaLayout
    ) -Name "$Label native renderer broker media layout override"
    if ($packedMediaLayout) {
        Invoke-AdbChecked -Serial $Serial -Arguments @(
            "shell", "setprop",
            "debug.rustyquest.native_renderer.video_projection.width",
            $packedWidth.ToString()
        ) -Name "$Label packed renderer width override"
        Invoke-AdbChecked -Serial $Serial -Arguments @(
            "shell", "setprop",
            "debug.rustyquest.native_renderer.video_projection.height",
            $packedHeight.ToString()
        ) -Name "$Label packed renderer height override"
    }
    $receipt = [ordered]@{
        schema = "rusty.quest.qcl100_native_renderer_lane_mode_override.v1"
        serial = $Serial
        label = $Label
        lane_mode = $LaneMode
        media_layout = $MediaLayout
        packed_stereo = [bool]$packedMediaLayout
        left_lane_active = $leftLaneActive
        right_lane_active = $rightLaneActive
        left_broker_port = $leftPort
        right_broker_port = $rightPort
        packed_width = if ($packedMediaLayout) { $packedWidth } else { 0 }
        packed_height = if ($packedMediaLayout) { $packedHeight } else { 0 }
        per_eye_width = if ($packedMediaLayout) { $PackedPerEyeWidth } else { 0 }
        per_eye_height = if ($packedMediaLayout) { $PackedPerEyeHeight } else { 0 }
        broker_connect_timeout_ms = $NativeRendererBrokerConnectTimeoutMs
        broker_stream_read_timeout_ms = $NativeRendererBrokerConnectTimeoutMs
    }
    $path = Join-Path $MediaDir "$Label-native-renderer-lane-mode-override.json"
    Write-JsonFile -Value $receipt -Path $path
    return $path
}

function Start-NativeRenderer {
    param([string]$Serial, [string]$Label)
    Invoke-External `
        -Name "$Label native renderer launch" `
        -File $Adb `
        -Arguments @(
            "-s", $Serial,
            "shell", "am", "start", "-W",
            "-a", "android.intent.action.MAIN",
            "-c", "com.oculus.intent.category.VR",
            "-n", $NativeRendererActivity
        ) `
        -LogPath (Join-Path $MediaDir "$Label-native-renderer-launch.txt") | Out-Null
}

function Get-NativeRendererFocusSnapshot {
    param([string]$Serial, [string]$Label, [string]$Suffix)
    $activityPath = Join-Path $MediaDir "$Label-native-focus-$Suffix-activity.txt"
    $windowPath = Join-Path $MediaDir "$Label-native-focus-$Suffix-window.txt"
    $activity = Read-AdbText -Serial $Serial -Arguments @("shell", "dumpsys", "activity", "activities") -Path $activityPath
    $window = Read-AdbText -Serial $Serial -Arguments @("shell", "dumpsys", "window", "windows") -Path $windowPath
    $focusActive = ($activity -like "*$NativeRendererPackage*" -and $activity -like "*NativeActivity*") -or
        ($window -like "*$NativeRendererPackage*" -and $window -like "*NativeActivity*")
    [ordered]@{
        label = $Label
        suffix = $Suffix
        focus_active = [bool]$focusActive
        activity_path = $activityPath
        window_path = $windowPath
    }
}
