param(
    [string]$RepoRoot,
    [string]$ApkPath = "target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk",
    [string]$OutDir = "",
    [int]$PostCommandDelayMilliseconds = 600,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.spatial_camera_panel",
    [string]$Activity = "io.github.mesmerprism.rustyquest.spatial_camera_panel/.SpatialCameraPanelActivity",
    [string]$ParticipantId = "",
    [ValidateSet("real-hands", "gpu-replay-hands", "icosphere")]
    [string]$SurfaceTargetId = "icosphere",
    [switch]$SkipInstall,
    [switch]$ClearLogcat,
    [switch]$StopAfterRun,
    [switch]$AllowMissingMarkers
)

$ErrorActionPreference = "Stop"

$UiCommandAction = "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_UI_COMMAND"
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

function Format-InvariantNumber {
    param([double]$Value)
    return $Value.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
}

function Get-SafeFileToken {
    param([Parameter(Mandatory=$true)][string]$Value)
    return ($Value -replace "[^A-Za-z0-9._-]+", "_").Trim("_")
}

function Test-TextContains {
    param(
        [string]$Text,
        [string]$Needle
    )
    return $Text.Contains($Needle)
}

function Test-LineContainsAll {
    param(
        [AllowNull()][string]$Text,
        [Parameter(Mandatory=$true)][string[]]$Needles
    )

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }
    foreach ($line in [regex]::Split($Text, "`r?`n")) {
        $matched = $true
        foreach ($needle in $Needles) {
            if (-not $line.Contains($needle)) {
                $matched = $false
                break
            }
        }
        if ($matched) {
            return $true
        }
    }
    return $false
}

function Assert-SummaryFlag {
    param(
        [System.Collections.IDictionary]$Summary,
        [string]$Name
    )
    if (-not [bool]$Summary[$Name]) {
        throw "Spatial Camera Panel particle-alias smoke evidence missing required flag: $Name"
    }
}

function Invoke-ParticleAliasCommand {
    param(
        [Parameter(Mandatory=$true)][string]$CaseId,
        [Parameter(Mandatory=$true)][string]$ParameterId,
        [Parameter(Mandatory=$true)][double]$Value,
        [Parameter(Mandatory=$true)][string]$ActivationProfile,
        [Parameter(Mandatory=$true)][int]$Index
    )

    $arguments = @(
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        $Activity,
        "-a",
        $UiCommandAction,
        "--es",
        "ui_action",
        "particle-alias-control",
        "--es",
        "participant_id",
        $ParticipantId,
        "--es",
        "surface_target_id",
        $SurfaceTargetId,
        "--es",
        "run_label",
        "particle-alias-smoke",
        "--es",
        "operator_id",
        "codex",
        "--es",
        "notes",
        "no-controller-particle-alias-smoke",
        "--es",
        "parameter_id",
        $ParameterId,
        "--ef",
        "value",
        (Format-InvariantNumber $Value),
        "--es",
        "visual_driver_activation_profile",
        $ActivationProfile
    )
    $result = Invoke-AdbCommand -Name "particle alias command $CaseId" -Arguments $arguments
    $commandPath = Join-Path $OutDir ("alias-command-{0:D2}-{1}.txt" -f $Index, (Get-SafeFileToken -Value $CaseId))
    Save-Text -Path $commandPath -Text $result.output
    Start-Sleep -Milliseconds ([Math]::Max(100, $PostCommandDelayMilliseconds))
    return [ordered]@{
        case_id = $CaseId
        parameter_id = $ParameterId
        value = $Value
        visual_driver_activation_profile = $ActivationProfile
        launch_exit_code = $result.exit_code
        launch_output_path = $commandPath
    }
}

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$repoRootPath = Resolve-Path -LiteralPath $RepoRoot

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "-Serial or RUSTY_QUEST_SERIAL is required; Spatial SDK particle-alias validation must use adb -s <serial>."
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
    $ParticipantId = "codex-spatial-particle-alias-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRootPath "local-artifacts\spatial-camera-panel-headset\$stamp-particle-alias-smoke"
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
$activityMarkersPath = Join-Path $OutDir "activity-markers.log"

$summary = [ordered]@{
    '$schema' = "rusty.quest.spatial_camera_panel_particle_alias_smoke.v1"
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    status = "started"
    adb_path = $script:ResolvedAdb
    adb_scope = "device-scoped-adb"
    adb_serial_required = $true
    adb_server_port = $script:ResolvedAdbServerPort
    serial = $Serial
    package = $PackageName
    activity = $Activity
    ui_command_action = $UiCommandAction
    participant_id = $ParticipantId
    surface_target_id = $SurfaceTargetId
    apk_path = (Resolve-Path -LiteralPath $resolvedApk).Path
    apk_sha256 = $apkSha256
    out_dir = (Resolve-Path -LiteralPath $OutDir).Path
    skipped_install = [bool]$SkipInstall
    clear_logcat_requested = [bool]$ClearLogcat
    stop_after_run = [bool]$StopAfterRun
    allow_missing_markers = [bool]$AllowMissingMarkers
    controller_input_required = $false
    automation_input_policy = "adb-am-start-intent-commands-no-physical-controller"
    pid_logcat_path = $pidLogcatPath
    tag_logcat_path = $tagLogcatPath
    all_logcat_path = $allLogcatPath
    activity_markers_path = $activityMarkersPath
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

    if (-not $SkipInstall) {
        $install = Invoke-AdbCommand -Name "install Spatial SDK APK" -Arguments @("install", "-r", "-d", "-g", (Resolve-Path -LiteralPath $resolvedApk).Path)
        Save-Text -Path (Join-Path $OutDir "install.txt") -Text $install.output
    }

    Invoke-AdbCommand -Name "force-stop Spatial SDK app" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
    if ($ClearLogcat) {
        Invoke-AdbCommand -Name "clear logcat" -Arguments @("logcat", "-c") | Out-Null
    }
    $clearMarkers = Invoke-AdbCommand `
        -Name "clear app-private activity markers" `
        -Arguments @("shell", "run-as", $PackageName, "rm", "-f", "files/spatial_camera_panel_activity_markers.log") `
        -AllowFailure
    Save-Text -Path (Join-Path $OutDir "clear-activity-markers.txt") -Text $clearMarkers.output

    $commands = New-Object System.Collections.Generic.List[object]
    $commands.Add((Invoke-ParticleAliasCommand -Index 1 -CaseId "active-alias-accept" -ParameterId "tracer_draw_slots_per_oscillator" -Value 3.0 -ActivationProfile "default"))
    $commands.Add((Invoke-ParticleAliasCommand -Index 2 -CaseId "inactive-visual-driver-alias-reject" -ParameterId "particle_size" -Value 0.6 -ActivationProfile "default"))
    $commands.Add((Invoke-ParticleAliasCommand -Index 3 -CaseId "activated-visual-driver-alias-accept" -ParameterId "particle_size" -Value 0.6 -ActivationProfile "particle-size-driver2"))
    $commands.Add((Invoke-ParticleAliasCommand -Index 4 -CaseId "forbidden-high-rate-payload-reject" -ParameterId "particle_output_rows" -Value 1.0 -ActivationProfile "default"))
    $commands.Add((Invoke-ParticleAliasCommand -Index 5 -CaseId "profile-derived-sphere-radius-accept" -ParameterId "sphere_radius_meters" -Value 1.5 -ActivationProfile "default"))
    $summary["commands"] = @($commands.ToArray())

    $pidResult = Invoke-AdbCommand -Name "Spatial SDK app pid" -Arguments @("shell", "pidof", $PackageName) -AllowFailure
    $targetPid = (($pidResult.output -split "\s+") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($targetPid)) {
        throw "Spatial SDK process id was not available after alias commands; refusing unscoped runtime evidence."
    }
    $summary.pid = $targetPid
    Save-Text -Path (Join-Path $OutDir "pid.txt") -Text $targetPid

    $pidLogcat = (Invoke-AdbCommand -Name "dump pid-scoped logcat" -Arguments @("logcat", "-d", "-v", "time", "--pid", $targetPid)).output
    Save-Text -Path $pidLogcatPath -Text $pidLogcat
    $allLogcat = (Invoke-AdbCommand -Name "dump full logcat" -Arguments @("logcat", "-d", "-v", "time")).output
    Save-Text -Path $allLogcatPath -Text $allLogcat
    $summary.app_private_activity_markers = Save-AppPrivateFile -RemotePath "files/spatial_camera_panel_activity_markers.log" -LocalPath $activityMarkersPath
    $activityMarkersText = if (Test-Path -LiteralPath $activityMarkersPath) { Get-Content -Raw -LiteralPath $activityMarkersPath } else { "" }
    $markerText = $pidLogcat + "`n" + $activityMarkersText
    $tagLines = @([regex]::Split($markerText, "`r?`n") | Where-Object { $_ -match "$MarkerPrefix|RQSpatialCameraPanel|RQSpatialCameraPanelNative|privateSurfaceParticleUiParameter" })
    [System.IO.File]::WriteAllLines($tagLogcatPath, [string[]]$tagLines)

    $summary.active_alias_submitted = Test-LineContainsAll $markerText @(
        "status=alias-parameter-submitted",
        "parameterId=tracer_draw_slots_per_oscillator",
        "visualDriverActivationProfile=default",
        "parameterMask=23"
    )
    $summary.active_alias_accepted = Test-LineContainsAll $markerText @(
        "status=alias-parameter-updated-compact",
        "privateSurfaceParticleUiParameterAcceptedAlias=tracer_draw_slots_per_oscillator",
        "privateSurfaceParticleUiParameterAliasPublicField=tracer_draw_slots_per_oscillator"
    )
    $summary.inactive_alias_submitted = Test-LineContainsAll $markerText @(
        "status=alias-parameter-submitted",
        "parameterId=particle_size",
        "visualDriverActivationProfile=default",
        "parameterMask=11"
    )
    $summary.inactive_alias_rejected = Test-LineContainsAll $markerText @(
        "status=alias-parameter-rejected-compact",
        "privateSurfaceParticleUiParameterRejectedAlias=particle_size",
        "privateSurfaceParticleUiParameterRejectReason=inactive-private-alias"
    )
    $summary.activated_alias_submitted = Test-LineContainsAll $markerText @(
        "status=alias-parameter-submitted",
        "parameterId=particle_size",
        "visualDriverActivationProfile=particle-size-driver2",
        "parameterMask=23"
    )
    $summary.activated_alias_accepted = Test-LineContainsAll $markerText @(
        "status=alias-parameter-updated-compact",
        "privateSurfaceParticleUiParameterAcceptedAlias=particle_size",
        "privateSurfaceParticleUiParameterAliasPublicField=driver2_value01"
    )
    $summary.high_rate_alias_submitted = Test-LineContainsAll $markerText @(
        "status=alias-parameter-submitted",
        "parameterId=particle_output_rows",
        "visualDriverActivationProfile=default",
        "parameterMask=11"
    )
    $summary.high_rate_alias_rejected = Test-LineContainsAll $markerText @(
        "status=alias-parameter-rejected-compact",
        "privateSurfaceParticleUiParameterRejectedAlias=particle_output_rows",
        "privateSurfaceParticleUiParameterRejectReason=high-rate-payload-forbidden"
    )
    $summary.derived_alias_submitted = Test-LineContainsAll $markerText @(
        "status=alias-parameter-submitted",
        "parameterId=sphere_radius_meters",
        "visualDriverActivationProfile=default",
        "parameterMask=55"
    )
    $summary.derived_alias_accepted = Test-LineContainsAll $markerText @(
        "status=alias-parameter-updated-compact",
        "privateSurfaceParticleUiParameterAcceptedAlias=sphere_radius_meters",
        "privateSurfaceParticleUiParameterAliasPublicField=projection_world_scale"
    )
    $summary.profile_metadata_present = Test-TextContains $markerText "privateSurfaceParticleProfileIdHash="
    $summary.packet_transport_ready = Test-TextContains $markerText "privateSurfaceParticleUiParameterTransport=jni-live-queue"
    $summary.high_rate_payload_forbidden_marker = Test-TextContains $markerText "privateSurfaceParticleUiParameterHighRatePayloadAllowed=false"
    $summary.submit_failed_matches = ([regex]::Matches($markerText, "status=alias-parameter-submit-failed")).Count
    $summary.android_runtime_matches = ([regex]::Matches($pidLogcat, "AndroidRuntime")).Count
    $summary.fatal_matches = ([regex]::Matches($pidLogcat, "FATAL")).Count

    $requiredFlags = @(
        "active_alias_submitted",
        "active_alias_accepted",
        "inactive_alias_submitted",
        "inactive_alias_rejected",
        "activated_alias_submitted",
        "activated_alias_accepted",
        "high_rate_alias_submitted",
        "high_rate_alias_rejected",
        "derived_alias_submitted",
        "derived_alias_accepted",
        "profile_metadata_present",
        "packet_transport_ready",
        "high_rate_payload_forbidden_marker"
    )
    if (-not $AllowMissingMarkers) {
        foreach ($flag in $requiredFlags) {
            Assert-SummaryFlag -Summary $summary -Name $flag
        }
        if ([int]$summary.submit_failed_matches -ne 0) {
            throw "Spatial Camera Panel particle-alias smoke saw alias submit failures: $($summary.submit_failed_matches)"
        }
        if ([int]$summary.android_runtime_matches -ne 0 -or [int]$summary.fatal_matches -ne 0) {
            throw "Spatial Camera Panel particle-alias smoke saw crash markers: AndroidRuntime=$($summary.android_runtime_matches) FATAL=$($summary.fatal_matches)"
        }
    }

    if ($StopAfterRun) {
        $stop = Invoke-AdbCommand -Name "stop Spatial SDK app" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure
        Save-Text -Path (Join-Path $OutDir "stop.txt") -Text $stop.output
    }

    $summary.status = if ($AllowMissingMarkers) { "completed" } else { "passed" }
} catch {
    $summary.status = "failed"
    $summary.error = $_.Exception.Message
    throw
} finally {
    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $summaryPath
}

Write-Output "Spatial Camera Panel particle-alias smoke evidence: $summaryPath"
Write-Output "APK_SHA256=$apkSha256"
Write-Output "OUT_DIR=$((Resolve-Path -LiteralPath $OutDir).Path)"
