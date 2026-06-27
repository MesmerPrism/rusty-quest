param(
    [string]$RepoRoot,
    [string]$ApkPath = "target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk",
    [string]$OutDir = "",
    [int]$RunSeconds = 19,
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
    [switch]$AllowMissingPolarPanelCreated
)

$ErrorActionPreference = "Stop"

$SelfTestAction = "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_WORKFLOW_SELF_TEST"
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
        throw "Spatial Camera Panel self-test evidence missing required flag: $Name"
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

if ([string]::IsNullOrWhiteSpace($ParticipantId)) {
    $ParticipantId = "codex-spatial-selftest-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRootPath "local-artifacts\spatial-camera-panel-headset\$stamp-selftest"
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
    '$schema' = "rusty.quest.spatial_camera_panel_selftest_run.v1"
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    status = "started"
    adb_path = $script:ResolvedAdb
    adb_scope = "device-scoped-adb"
    adb_serial_required = $true
    adb_server_port = $script:ResolvedAdbServerPort
    serial = $Serial
    package = $PackageName
    activity = $Activity
    self_test_action = $SelfTestAction
    participant_id = $ParticipantId
    surface_target_id = $SurfaceTargetId
    apk_path = (Resolve-Path -LiteralPath $resolvedApk).Path
    apk_sha256 = $apkSha256
    out_dir = (Resolve-Path -LiteralPath $OutDir).Path
    run_seconds = $RunSeconds
    skipped_install = [bool]$SkipInstall
    clear_logcat_requested = [bool]$ClearLogcat
    stop_after_run = [bool]$StopAfterRun
    allow_missing_polar_panel_created = [bool]$AllowMissingPolarPanelCreated
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

    Invoke-AdbCommand -Name "force-stop Spatial SDK app" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
    $launch = Invoke-AdbCommand -Name "launch Spatial SDK self-test" -Arguments @(
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        $Activity,
        "-a",
        $SelfTestAction,
        "--es",
        "participant_id",
        $ParticipantId,
        "--es",
        "surface_target_id",
        $SurfaceTargetId
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
    $tagLines = @($pidLogcat -split "`r?`n" | Where-Object { $_ -match "$MarkerPrefix|RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE|RQSpatialCameraPanel" })
    [System.IO.File]::WriteAllLines($tagLogcatPath, [string[]]$tagLines)

    $dumpsys = (Invoke-AdbCommand -Name "dumpsys activity" -Arguments @("shell", "dumpsys", "activity", "activities") -AllowFailure).output
    Save-Text -Path (Join-Path $OutDir "dumpsys-activity.txt") -Text $dumpsys
    $privateFiles = (Invoke-AdbCommand -Name "list app-private files" -Arguments @("shell", "run-as", $PackageName, "find", "files", "-maxdepth", "5", "-type", "f") -AllowFailure).output
    Save-Text -Path (Join-Path $OutDir "app-private-files.txt") -Text $privateFiles

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

    $blockEventsPath = Join-Path $appPrivateDir "block_events.jsonl"
    $foregroundEventsPath = Join-Path $appPrivateDir "foreground_events.jsonl"
    $polarEventsPath = Join-Path $appPrivateDir "polar_events.jsonl"
    $questionnairePath = Join-Path $appPrivateDir "questionnaire_results.jsonl"
    $blockEventsText = if (Test-Path -LiteralPath $blockEventsPath) { Get-Content -Raw -LiteralPath $blockEventsPath } else { "" }
    $foregroundEventsText = if (Test-Path -LiteralPath $foregroundEventsPath) { Get-Content -Raw -LiteralPath $foregroundEventsPath } else { "" }
    $polarEventsText = if (Test-Path -LiteralPath $polarEventsPath) { Get-Content -Raw -LiteralPath $polarEventsPath } else { "" }
    $questionnaireText = if (Test-Path -LiteralPath $questionnairePath) { Get-Content -Raw -LiteralPath $questionnairePath } else { "" }

    $summary.condition_handoff_marker = Test-TextContains $pidLogcat "status=driver-profile-parameter-handoff"
    $summary.self_test_condition_handoff_marker = Test-TextContains $pidLogcat "source=self-test-driver-profile-start"
    $summary.parameter_submit_self_test_marker = (Test-TextContains $pidLogcat "status=parameters-submitted") -and (Test-TextContains $pidLogcat "source=self-test-driver-profile-start")
    $summary.panel_closed_for_self_test = (Test-TextContains $pidLogcat "source=self-test-particle-view") -and (Test-TextContains $pidLogcat "spatial-sdk-particle-view-panel-closed")
    $summary.workflow_panel_reopen_marker = (Test-TextContains $pidLogcat "source=self-test-workflow-panel") -and (Test-TextContains $pidLogcat "spatial-sdk-workflow-panel-open")
    $summary.panel_registration_count_3 = Test-TextContains $pidLogcat "panelRegistrationCount=3"
    $summary.polar_setup_recorded = Test-TextContains $pidLogcat "status=polar-setup-recorded"
    $summary.polar_panel_created = Test-TextContains $pidLogcat "channel=polar-sensor-panel status=created"
    $summary.polar_stream_mirror_registered = Test-TextContains $pidLogcat "streamMirror=spatial-camera-panel-store"
    $summary.particle_surface_start_requested = Test-TextContains $pidLogcat "status=start-requested"
    $summary.particle_surface_panel_ready = Test-TextContains $pidLogcat "status=surface-panel-ready"
    $summary.lifecycle_particle_layer_started = Test-TextContains $pidLogcat "particleLayerStarted=true"
    $summary.render_loop_ready = Test-TextContains $pidLogcat "status=render-loop-ready"
    $summary.first_frame_presented = Test-TextContains $pidLogcat "status=first-frame-presented"
    $summary.live_hand_markers = Test-TextContains $pidLogcat "liveHand"
    $summary.self_test_complete = Test-TextContains $pidLogcat "status=self-test-complete"
    $summary.block_events_block_started = Test-TextContains $blockEventsText '"event_type":"block_started"'
    $summary.block_events_block_elapsed = Test-TextContains $blockEventsText '"event_type":"block_elapsed"'
    $summary.block_events_questionnaire_submitted = Test-TextContains $blockEventsText '"event_type":"questionnaire_submitted"'
    $summary.foreground_events_panel_closed = Test-TextContains $foregroundEventsText "spatial-sdk-particle-view-panel-closed"
    $summary.foreground_events_panel_reopened = Test-TextContains $foregroundEventsText "spatial-sdk-workflow-panel-open"
    $summary.polar_events_setup_row = Test-TextContains $polarEventsText "spatial_polar_setup"
    $summary.questionnaire_result_row = Test-TextContains $questionnaireText '"schema_id":"rusty.quest.spatial_camera_panel.questionnaire.v1"'
    $summary.android_runtime_matches = ([regex]::Matches($pidLogcat, "AndroidRuntime")).Count
    $summary.fatal_matches = ([regex]::Matches($pidLogcat, "FATAL")).Count
    $summary.render_failed_matches = ([regex]::Matches($pidLogcat, "render-failed")).Count

    $requiredFlags = @(
        "condition_handoff_marker",
        "self_test_condition_handoff_marker",
        "parameter_submit_self_test_marker",
        "panel_closed_for_self_test",
        "workflow_panel_reopen_marker",
        "panel_registration_count_3",
        "polar_setup_recorded",
        "polar_stream_mirror_registered",
        "particle_surface_start_requested",
        "particle_surface_panel_ready",
        "lifecycle_particle_layer_started",
        "render_loop_ready",
        "first_frame_presented",
        "live_hand_markers",
        "self_test_complete",
        "app_private_session_state",
        "block_events_block_started",
        "block_events_block_elapsed",
        "block_events_questionnaire_submitted",
        "foreground_events_panel_closed",
        "foreground_events_panel_reopened",
        "polar_events_setup_row",
        "questionnaire_result_row"
    )
    if (-not $AllowMissingPolarPanelCreated) {
        $requiredFlags += "polar_panel_created"
    }
    foreach ($flag in $requiredFlags) {
        Assert-SummaryFlag -Summary $summary -Name $flag
    }
    if ($summary.android_runtime_matches -ne 0 -or $summary.fatal_matches -ne 0 -or $summary.render_failed_matches -ne 0) {
        throw "Spatial SDK self-test log contains failure markers: AndroidRuntime=$($summary.android_runtime_matches), FATAL=$($summary.fatal_matches), render-failed=$($summary.render_failed_matches)"
    }

    if ($StopAfterRun) {
        $stop = Invoke-AdbCommand -Name "stop Spatial SDK app" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure
        Save-Text -Path (Join-Path $OutDir "stop.txt") -Text $stop.output
    }

    $summary.status = "passed"
} catch {
    $summary.status = "failed"
    $summary.error = $_.Exception.Message
    throw
} finally {
    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $summaryPath
}

Write-Output "Spatial Camera Panel self-test evidence: $summaryPath"
