param(
    [string]$RepoRoot,
    [string]$ApkPath = "target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk",
    [string]$OutDir = "",
    [int]$RunSeconds = 48,
    [int]$ScanSeconds = 16,
    [int]$ConnectDelaySeconds = 10,
    [int]$EcgSeconds = 14,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.spatial_camera_panel",
    [string]$Activity = "io.github.mesmerprism.rustyquest.spatial_camera_panel/.SpatialCameraPanelActivity",
    [string]$ParticipantId = "",
    [ValidateSet("real-hands", "gpu-replay-hands", "icosphere")]
    [string]$SurfaceTargetId = "real-hands",
    [switch]$SkipInstall,
    [switch]$ClearLogcat,
    [switch]$StopAfterRun,
    [switch]$AllowMissingLivePolar
)

$ErrorActionPreference = "Stop"

$PolarLiveAction = "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_POLAR_LIVE_VALIDATION"
$MarkerPrefix = "RUSTY_QUEST_SPATIAL_CAMERA_PANEL"

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

function Save-AppPrivateFile {
    param(
        [Parameter(Mandatory=$true)][string]$RemotePath,
        [Parameter(Mandatory=$true)][string]$LocalPath
    )

    $result = Invoke-AdbCommand `
        -Name "app-private file $RemotePath" `
        -Arguments @("exec-out", "run-as", $PackageName, "cat", $RemotePath) `
        -AllowFailure
    if ($result.exit_code -eq 0) {
        Save-Text -Path $LocalPath -Text $result.output
        return $true
    }
    Save-Text -Path ($LocalPath + ".error.txt") -Text $result.output
    return $false
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
        throw "Spatial Camera Panel live Polar evidence missing required flag: $Name"
    }
}

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$repoRootPath = Resolve-Path -LiteralPath $RepoRoot

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "-Serial or RUSTY_QUEST_SERIAL is required; live Polar validation must use adb -s <serial>."
}

$minimumRunSeconds = $ScanSeconds + $ConnectDelaySeconds + $EcgSeconds + 4
if ($RunSeconds -lt $minimumRunSeconds) {
    throw "-RunSeconds must be at least ScanSeconds + ConnectDelaySeconds + EcgSeconds + 4 ($minimumRunSeconds)."
}

$resolvedApk = if ([System.IO.Path]::IsPathRooted($ApkPath)) {
    $ApkPath
} else {
    Join-Path $repoRootPath $ApkPath
}
if (-not (Test-Path -LiteralPath $resolvedApk)) {
    throw "APK not found: $resolvedApk"
}

if ([string]::IsNullOrWhiteSpace($ParticipantId)) {
    $ParticipantId = "codex-spatial-polar-live-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRootPath "local-artifacts\spatial-camera-panel-headset\$stamp-polar-live"
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

$apkSha256 = Get-FileSha256 -Path $resolvedApk
$summaryPath = Join-Path $OutDir "evidence-summary.json"
$pidLogcatPath = Join-Path $OutDir "pid-logcat.txt"
$tagLogcatPath = Join-Path $OutDir "tag-logcat.txt"
$allLogcatPath = Join-Path $OutDir "logcat-all.txt"
$appPrivateDir = Join-Path $OutDir "app-private"
New-Item -ItemType Directory -Force -Path $appPrivateDir | Out-Null

$summary = [ordered]@{
    '$schema' = "rusty.quest.spatial_camera_panel_polar_live_run.v1"
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    status = "started"
    adb_path = $script:ResolvedAdb
    adb_scope = "device-scoped-adb"
    adb_serial_required = $true
    adb_server_port = $script:ResolvedAdbServerPort
    serial = $Serial
    package = $PackageName
    activity = $Activity
    polar_live_action = $PolarLiveAction
    participant_id = $ParticipantId
    surface_target_id = $SurfaceTargetId
    apk_path = (Resolve-Path -LiteralPath $resolvedApk).Path
    apk_sha256 = $apkSha256
    out_dir = (Resolve-Path -LiteralPath $OutDir).Path
    run_seconds = $RunSeconds
    scan_seconds = $ScanSeconds
    connect_delay_seconds = $ConnectDelaySeconds
    ecg_seconds = $EcgSeconds
    skipped_install = [bool]$SkipInstall
    clear_logcat_requested = [bool]$ClearLogcat
    stop_after_run = [bool]$StopAfterRun
    allow_missing_live_polar = [bool]$AllowMissingLivePolar
    pid_logcat_path = $pidLogcatPath
    tag_logcat_path = $tagLogcatPath
    all_logcat_path = $allLogcatPath
    app_private_dir = $appPrivateDir
}

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

    $permissionLines = New-Object System.Collections.Generic.List[string]
    foreach ($permission in @(
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.ACCESS_FINE_LOCATION"
    )) {
        $grant = Invoke-AdbCommand -Name "grant $permission" -Arguments @("shell", "pm", "grant", $PackageName, $permission) -AllowFailure
        $permissionLines.Add("$permission exit=$($grant.exit_code) $($grant.output)")
    }
    Save-Text -Path (Join-Path $OutDir "permission-grants.txt") -Text ($permissionLines -join "`n")

    Invoke-AdbCommand -Name "force-stop Spatial SDK app" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
    $clearPrivateLines = New-Object System.Collections.Generic.List[string]
    foreach ($privateFile in @(
        "files/polar_sensor_status.json",
        "files/polar_stream_events.jsonl",
        "files/spatial_camera_panel_activity_markers.log"
    )) {
        $clearPrivate = Invoke-AdbCommand `
            -Name "clear app-private $privateFile" `
            -Arguments @("shell", "run-as", $PackageName, "rm", "-f", $privateFile) `
            -AllowFailure
        $clearPrivateLines.Add("$privateFile exit=$($clearPrivate.exit_code) $($clearPrivate.output)")
    }
    Save-Text -Path (Join-Path $OutDir "clear-app-private-live-polar.txt") -Text ($clearPrivateLines -join "`n")
    $launch = Invoke-AdbCommand -Name "launch Spatial SDK live Polar validation" -Arguments @(
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        $Activity,
        "-a",
        $PolarLiveAction,
        "--es",
        "participant_id",
        $ParticipantId,
        "--es",
        "surface_target_id",
        $SurfaceTargetId,
        "--ei",
        "polar_scan_seconds",
        $ScanSeconds.ToString(),
        "--ei",
        "polar_connect_delay_seconds",
        $ConnectDelaySeconds.ToString(),
        "--ei",
        "polar_ecg_seconds",
        $EcgSeconds.ToString()
    )
    Save-Text -Path (Join-Path $OutDir "launch.txt") -Text $launch.output
    Start-Sleep -Seconds ([Math]::Max(1, $RunSeconds))

    $pidResult = Invoke-AdbCommand -Name "Spatial SDK app pid" -Arguments @("shell", "pidof", $PackageName) -AllowFailure
    $targetPid = (($pidResult.output -split "\s+") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($targetPid)) {
        throw "Spatial SDK process id was not available after launch; refusing unscoped runtime evidence."
    }
    $summary.pid = $targetPid
    Save-Text -Path (Join-Path $OutDir "pid.txt") -Text $targetPid

    $pidLogcat = (Invoke-AdbCommand -Name "dump pid-scoped logcat" -Arguments @("logcat", "-d", "-v", "time", "--pid", $targetPid)).output
    Save-Text -Path $pidLogcatPath -Text $pidLogcat
    $allLogcat = (Invoke-AdbCommand -Name "dump full logcat" -Arguments @("logcat", "-d", "-v", "time")).output
    Save-Text -Path $allLogcatPath -Text $allLogcat
    $tagLines = @($pidLogcat -split "`r?`n" | Where-Object { $_ -match "$MarkerPrefix|RQSpatialCameraPanel|BluetoothGatt" })
    [System.IO.File]::WriteAllLines($tagLogcatPath, [string[]]$tagLines)

    $dumpsys = (Invoke-AdbCommand -Name "dumpsys activity" -Arguments @("shell", "dumpsys", "activity", "activities") -AllowFailure).output
    Save-Text -Path (Join-Path $OutDir "dumpsys-activity.txt") -Text $dumpsys
    $privateFiles = (Invoke-AdbCommand -Name "list app-private files" -Arguments @("shell", "run-as", $PackageName, "find", "files", "-maxdepth", "5", "-type", "f") -AllowFailure).output
    Save-Text -Path (Join-Path $OutDir "app-private-files.txt") -Text $privateFiles

    $summary.app_private_polar_sensor_status = Save-AppPrivateFile -RemotePath "files/polar_sensor_status.json" -LocalPath (Join-Path $appPrivateDir "polar_sensor_status.json")
    $summary.app_private_root_polar_stream_events = Save-AppPrivateFile -RemotePath "files/polar_stream_events.jsonl" -LocalPath (Join-Path $appPrivateDir "polar_stream_events.jsonl")
    $activityMarkersPath = Join-Path $appPrivateDir "spatial_camera_panel_activity_markers.log"
    $summary.app_private_activity_markers = Save-AppPrivateFile -RemotePath "files/spatial_camera_panel_activity_markers.log" -LocalPath $activityMarkersPath

    $sessionStatePath = Join-Path $appPrivateDir "spatial_camera_panel_session.json"
    $summary.app_private_session_state = Save-AppPrivateFile -RemotePath "files/spatial_camera_panel_session.json" -LocalPath $sessionStatePath
    $sessionId = ""
    if ($summary.app_private_session_state) {
        $sessionState = Get-Content -Raw -LiteralPath $sessionStatePath | ConvertFrom-Json
        $sessionId = [string]$sessionState.session_id
        $summary.session_id = $sessionId
    }
    if (-not [string]::IsNullOrWhiteSpace($sessionId)) {
        foreach ($fileName in @(
            "session_manifest.json",
            "block_events.jsonl",
            "foreground_events.jsonl",
            "polar_events.jsonl",
            "ecg_events.jsonl",
            "questionnaire_results.jsonl"
        )) {
            $localFile = Join-Path $appPrivateDir $fileName
            $remoteFile = "files/spatial_camera_panel_session/$sessionId/$fileName"
            $summary["app_private_$($fileName -replace '[^A-Za-z0-9]+', '_')"] = Save-AppPrivateFile -RemotePath $remoteFile -LocalPath $localFile
        }
    }

    $rootStreamPath = Join-Path $appPrivateDir "polar_stream_events.jsonl"
    $polarEventsPath = Join-Path $appPrivateDir "polar_events.jsonl"
    $ecgEventsPath = Join-Path $appPrivateDir "ecg_events.jsonl"
    $rootStreamText = if (Test-Path -LiteralPath $rootStreamPath) { Get-Content -Raw -LiteralPath $rootStreamPath } else { "" }
    $activityMarkersText = if (Test-Path -LiteralPath $activityMarkersPath) { Get-Content -Raw -LiteralPath $activityMarkersPath } else { "" }
    $markerText = $pidLogcat + "`n" + $activityMarkersText
    $polarEventsText = if (Test-Path -LiteralPath $polarEventsPath) { Get-Content -Raw -LiteralPath $polarEventsPath } else { "" }
    $ecgEventsText = if (Test-Path -LiteralPath $ecgEventsPath) { Get-Content -Raw -LiteralPath $ecgEventsPath } else { "" }

    $summary.live_validation_started = Test-TextContains $markerText "channel=polar-live-validation status=start"
    $summary.polar_panel_created = Test-TextContains $markerText "channel=polar-sensor-panel status=created"
    $summary.polar_panel_ready = Test-TextContains $markerText "channel=polar-sensor-panel status=ready"
    $summary.polar_panel_automation_ready = Test-TextContains $markerText "channel=polar-live-validation status=polar-panel-automation-ready"
    $summary.select_ecg_command = Test-TextContains $markerText "status=cli-command command=select_ecg"
    $summary.scan_started = Test-TextContains $markerText "status=scanning"
    $summary.scan_command_issued = Test-TextContains $markerText "channel=polar-live-validation status=scan-command-issued"
    $summary.device_found = Test-TextContains $markerText "status=device-found"
    $summary.connect_requested = Test-TextContains $markerText "channel=polar-live-validation status=connect-requested"
    $summary.auto_connect_selected = Test-TextContains $markerText "status=auto-connect-selected"
    $summary.connected = Test-TextContains $markerText "channel=polar-sensor-panel status=connected"
    $summary.pmd_ready = Test-TextContains $markerText "status=pmd-ready"
    $summary.start_ecg_requested = (Test-TextContains $markerText "channel=polar-live-validation status=start-ecg-requested") -and (Test-TextContains $markerText "command=start_ecg")
    $summary.pmd_started_ecg = Test-TextContains $markerText "status=pmd-started mode=ecg"
    $summary.ecg_frame_marker = Test-TextContains $markerText "status=ecg-frame"
    $summary.live_validation_complete = Test-TextContains $markerText "channel=polar-live-validation status=complete"
    $summary.live_validation_complete_receiving = Test-TextContains $markerText "status=complete ecgReceiving=true"
    $summary.store_ecg_mirror_marker = (Test-TextContains $markerText "status=polar-stream-event-recorded") -and (Test-TextContains $markerText "streamId=stream.polar_h10.ecg") -and (Test-TextContains $markerText "ecgMirrored=true")
    $summary.root_polar_stream_events_nonempty = ($rootStreamText.Length -gt 0)
    $summary.root_polar_stream_events_ecg = Test-TextContains $rootStreamText "stream.polar_h10.ecg"
    $summary.polar_events_ecg_row = Test-TextContains $polarEventsText "stream.polar_h10.ecg"
    $summary.ecg_events_stream_row = Test-TextContains $ecgEventsText "stream.polar_h10.ecg"
    $summary.app_private_ecg_events_nonempty = ($ecgEventsText.Length -gt 0)
    $summary.android_runtime_matches = ([regex]::Matches($pidLogcat, "AndroidRuntime")).Count
    $summary.fatal_matches = ([regex]::Matches($pidLogcat, "FATAL")).Count
    $summary.render_failed_matches = ([regex]::Matches($pidLogcat, "render-failed")).Count

    $requiredFlags = @(
        "live_validation_started",
        "polar_panel_automation_ready",
        "scan_command_issued",
        "connect_requested",
        "app_private_session_state"
    )
    $liveFlags = @(
        "auto_connect_selected",
        "connected",
        "pmd_ready",
        "start_ecg_requested",
        "pmd_started_ecg",
        "ecg_frame_marker",
        "live_validation_complete",
        "live_validation_complete_receiving",
        "store_ecg_mirror_marker",
        "root_polar_stream_events_nonempty",
        "root_polar_stream_events_ecg",
        "polar_events_ecg_row",
        "ecg_events_stream_row",
        "app_private_ecg_events_nonempty"
    )
    foreach ($flag in $requiredFlags) {
        Assert-SummaryFlag -Summary $summary -Name $flag
    }
    if (-not $AllowMissingLivePolar) {
        foreach ($flag in $liveFlags) {
            Assert-SummaryFlag -Summary $summary -Name $flag
        }
    }
    if ($summary.android_runtime_matches -ne 0 -or $summary.fatal_matches -ne 0 -or $summary.render_failed_matches -ne 0) {
        throw "Spatial SDK live Polar log contains failure markers: AndroidRuntime=$($summary.android_runtime_matches), FATAL=$($summary.fatal_matches), render-failed=$($summary.render_failed_matches)"
    }

    if ($StopAfterRun) {
        $stop = Invoke-AdbCommand -Name "stop Spatial SDK app" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure
        Save-Text -Path (Join-Path $OutDir "stop.txt") -Text $stop.output
    }

    $summary.status = if ($AllowMissingLivePolar) { "passed-allow-missing-live-polar" } else { "passed" }
} catch {
    $summary.status = "failed"
    $summary.error = $_.Exception.Message
    throw
} finally {
    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $summaryPath
}

Write-Output "Spatial Camera Panel live Polar evidence: $summaryPath"
