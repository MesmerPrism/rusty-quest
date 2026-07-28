param(
    [Parameter(Mandatory=$true)]
    [string]$SourcePath,
    [Parameter(Mandatory=$true)]
    [ValidateSet("flat", "equirect-180", "equirect-360")]
    [string]$Shape,
    [Parameter(Mandatory=$true)]
    [ValidateSet("mono", "side-by-side-left-right", "top-bottom")]
    [string]$Stereo,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$Ffprobe = $env:RUSTY_QUEST_FFPROBE,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.spatial_camera_panel",
    [string]$ActivityName = "io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraPanelActivity",
    [int]$WidthPx = 0,
    [int]$HeightPx = 0,
    [bool]$Autoplay = $true,
    [bool]$Loop = $true,
    [ValidateRange(1.0, 100.0)]
    [double]$RadiusMeters = 50.0,
    [switch]$Launch,
    [ValidateRange(0, 60)]
    [int]$ObserveSeconds = 20,
    [string]$Out = ""
)

$ErrorActionPreference = "Stop"

function Resolve-ToolPath {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [string]$Value,
        [string]$DefaultPath
    )
    if (-not [string]::IsNullOrWhiteSpace($Value)) {
        if (Test-Path -LiteralPath $Value -PathType Leaf) {
            return (Resolve-Path -LiteralPath $Value).Path
        }
        $command = Get-Command $Value -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
        throw "$Name not found: $Value"
    }
    if (-not [string]::IsNullOrWhiteSpace($DefaultPath) -and
        (Test-Path -LiteralPath $DefaultPath -PathType Leaf)) {
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

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $script:ResolvedAdb @adbArgs 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
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

function Get-VideoDimensions {
    param([Parameter(Mandatory=$true)][string]$Path)
    $probeOutput = & $script:ResolvedFfprobe `
        -v error `
        -select_streams "v:0" `
        -show_entries "stream=width,height" `
        -of json `
        $Path
    if ($LASTEXITCODE -ne 0) {
        throw "ffprobe failed for $Path"
    }
    $probe = ($probeOutput -join "`n") | ConvertFrom-Json
    $stream = @($probe.streams) | Select-Object -First 1
    if ($null -eq $stream -or [int]$stream.width -lt 1 -or [int]$stream.height -lt 1) {
        throw "ffprobe did not return a valid video width and height for $Path"
    }
    return [pscustomobject]@{
        width = [int]$stream.width
        height = [int]$stream.height
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script:ResolvedAdb = Resolve-ToolPath `
    -Name "adb" `
    -Value $Adb `
    -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
$script:ResolvedFfprobe = Resolve-ToolPath `
    -Name "ffprobe" `
    -Value $Ffprobe `
    -DefaultPath "S:\Work\tools\ffmpeg\bin\ffprobe.exe"
$script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument -Value $AdbServerPort

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "-Serial or RUSTY_QUEST_SERIAL is required; media staging must use adb -s <serial>."
}
if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) {
    throw "SourcePath not found or not a file: $SourcePath"
}
$resolvedSource = (Resolve-Path -LiteralPath $SourcePath).Path
$sourceInfo = Get-Item -LiteralPath $resolvedSource
$extension = $sourceInfo.Extension.ToLowerInvariant()
if ($extension -notin @(".mp4", ".m4v", ".mkv", ".webm", ".mov")) {
    throw "Unsupported local video container: $extension"
}

$detectedDimensions = Get-VideoDimensions -Path $resolvedSource
if ($WidthPx -eq 0) {
    $WidthPx = $detectedDimensions.width
}
if ($HeightPx -eq 0) {
    $HeightPx = $detectedDimensions.height
}
if ($WidthPx -ne $detectedDimensions.width -or $HeightPx -ne $detectedDimensions.height) {
    throw "Declared dimensions ${WidthPx}x${HeightPx} do not match ffprobe dimensions $($detectedDimensions.width)x$($detectedDimensions.height)."
}
if ($WidthPx -gt 16384 -or $HeightPx -gt 16384) {
    throw "Video dimensions exceed the app routing limit: ${WidthPx}x${HeightPx}"
}
if ($Stereo -eq "side-by-side-left-right" -and ($WidthPx % 2) -ne 0) {
    throw "Side-by-side video width must be even: $WidthPx"
}
if ($Stereo -eq "top-bottom" -and ($HeightPx % 2) -ne 0) {
    throw "Top-bottom video height must be even: $HeightPx"
}

$remoteRoot = "/sdcard/Movies/RustyQuestImmersiveVideo"
$remotePath = "$remoteRoot/v$extension"
$remoteRelativePath = "Movies/RustyQuestImmersiveVideo/"
$validationRemoteRoot = "/sdcard/Android/data/$PackageName/files/immersive-video-validation"
if ([string]::IsNullOrWhiteSpace($Out)) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $Out = Join-Path $repoRoot "local-artifacts\immersive-video\$timestamp-$Shape-$Stereo-stage.json"
} elseif (-not [System.IO.Path]::IsPathRooted($Out)) {
    $Out = Join-Path $repoRoot $Out
}
$outPath = [System.IO.Path]::GetFullPath($Out)
$outDirectory = Split-Path -Parent $outPath
New-Item -ItemType Directory -Force -Path $outDirectory | Out-Null

$sourceSha256 = (Get-FileHash -LiteralPath $resolvedSource -Algorithm SHA256).Hash.ToLowerInvariant()
$commands = @()
$commands += Invoke-AdbCommand -Name "adb get-state" -Arguments @("get-state")
$commands += Invoke-AdbCommand `
    -Name "stop target package before replacing staged immersive video" `
    -Arguments @("shell", "am", "force-stop", $PackageName)
$commands += Invoke-AdbCommand `
    -Name "create shared immersive-video directory" `
    -Arguments @("shell", "mkdir", "-p", $remoteRoot)
$commands += Invoke-AdbCommand `
    -Name "create package-scoped validation-artifact directory" `
    -Arguments @("shell", "mkdir", "-p", $validationRemoteRoot)
$commands += Invoke-AdbCommand `
    -Name "push immersive video" `
    -Arguments @("push", $resolvedSource, $remotePath)
$commands += Invoke-AdbCommand `
    -Name "set staged video permissions" `
    -Arguments @("shell", "chmod", "0644", $remotePath)
$remoteStat = Invoke-AdbCommand `
    -Name "inspect staged video size" `
    -Arguments @("shell", "stat", "-c", "%s", $remotePath)
$commands += $remoteStat
$remoteSize = 0L
if (-not [long]::TryParse($remoteStat.output.Trim(), [ref]$remoteSize) -or
    $remoteSize -ne $sourceInfo.Length) {
    throw "Staged video size mismatch: host=$($sourceInfo.Length) device=$($remoteStat.output.Trim())"
}
$remoteHash = Invoke-AdbCommand `
    -Name "hash staged video" `
    -Arguments @("shell", "toybox", "sha256sum", $remotePath)
$commands += $remoteHash
$remoteSha256 = (($remoteHash.output.Trim() -split "\s+")[0]).ToLowerInvariant()
if ($remoteSha256 -ne $sourceSha256) {
    throw "Staged video SHA-256 mismatch: host=$sourceSha256 device=$remoteSha256"
}
$commands += Invoke-AdbCommand `
    -Name "index staged video in MediaStore" `
    -Arguments @(
        "shell", "am", "broadcast",
        "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "-d", "file://$remotePath"
    )
Start-Sleep -Seconds 2
$mediaQuery = Invoke-AdbCommand `
    -Name "resolve staged video MediaStore content URI" `
    -Arguments @(
        "shell", "content", "query",
        "--uri", "content://media/external/video/media",
        "--projection", "_id:_display_name:_size:relative_path"
    )
$commands += $mediaQuery
$escapedName = [regex]::Escape("v$extension")
$escapedRelativePath = [regex]::Escape($remoteRelativePath)
$mediaRowPattern =
    "_id=(\d+), _display_name=$escapedName, _size=$remoteSize, relative_path=$escapedRelativePath(?:\s|$)"
$matchingMediaRows = @(
    $mediaQuery.output -split "`r?`n" |
        Where-Object { $_ -match $mediaRowPattern }
)
if ($matchingMediaRows.Count -ne 1) {
    throw "Expected exactly one indexed MediaStore row for $remotePath, found $($matchingMediaRows.Count)."
}
$null = $matchingMediaRows[0] -match $mediaRowPattern
$mediaStoreId = $Matches[1]
$launchContentUri = "content://media/external/video/media/$mediaStoreId"

$markerOutput = ""
$targetFatalOutput = ""
$targetPid = ""
$targetProcessAliveAfterObservation = $false
$screenshotPath = ""
$freshnessScreenshots = @()
$freshnessUniqueHashCount = 0
if ($Launch) {
    $deviceEpochResult = Invoke-AdbCommand `
        -Name "read device epoch before launch" `
        -Arguments @("shell", "date", "+%s")
    $commands += $deviceEpochResult
    $deviceEpochSeconds = $deviceEpochResult.output.Trim()
    if ($deviceEpochSeconds -notmatch "^\d+$") {
        throw "Unable to read device epoch before launch: $deviceEpochSeconds"
    }
    $commands += Invoke-AdbCommand `
        -Name "stop target package before immersive launch" `
        -Arguments @("shell", "am", "force-stop", $PackageName)
    $launchArguments = @(
        "shell", "am", "start",
        "-a", "android.intent.action.VIEW",
        "-d", $launchContentUri,
        "-f", "0x1",
        "-n", "$PackageName/$ActivityName",
        "--ez", "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_ENABLED", "true",
        "--es", "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_PATH", $launchContentUri,
        "--es", "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_SHAPE", $Shape,
        "--es", "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_STEREO", $Stereo,
        "--ei", "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_WIDTH_PX", $WidthPx.ToString(),
        "--ei", "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_HEIGHT_PX", $HeightPx.ToString(),
        "--ez", "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_AUTOPLAY", $Autoplay.ToString().ToLowerInvariant(),
        "--ez", "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_LOOP", $Loop.ToString().ToLowerInvariant(),
        "--es", "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_RADIUS_METERS",
        $RadiusMeters.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    )
    $commands += Invoke-AdbCommand -Name "launch immersive video route" -Arguments $launchArguments
    if ($ObserveSeconds -gt 0) {
        Start-Sleep -Seconds $ObserveSeconds
    }
    $markerResult = Invoke-AdbCommand `
        -Name "read bounded immersive-video logcat markers" `
        -Arguments @(
            "logcat", "-d", "-v", "epoch", "-T", "$deviceEpochSeconds.000",
            "-s", "RQSpatialCameraPanel:I", "*:S"
        ) `
        -AllowFailure
    $commands += $markerResult
    $markerOutput = $markerResult.output
    $pidResult = Invoke-AdbCommand `
        -Name "resolve target package pid after playback observation" `
        -Arguments @("shell", "pidof", $PackageName) `
        -AllowFailure
    $commands += $pidResult
    $targetPid = $pidResult.output.Trim()
    $targetProcessAliveAfterObservation =
        ($pidResult.exit_code -eq 0 -and $targetPid -match "^\d+$")
    if ($targetProcessAliveAfterObservation) {
        $targetFatalResult = Invoke-AdbCommand `
            -Name "read bounded target-process error logcat" `
            -Arguments @(
                "logcat", "-d", "-v", "epoch", "-T", "$deviceEpochSeconds.000",
                "--pid=$targetPid", "*:E"
            ) `
            -AllowFailure
        $commands += $targetFatalResult
        $targetFatalOutput = $targetFatalResult.output
    }

    foreach ($frameIndex in 1..2) {
        $remoteScreenshot = "$validationRemoteRoot/validation-screenshot-$frameIndex.png"
        $commands += Invoke-AdbCommand `
            -Name "capture immersive-video validation screenshot $frameIndex" `
            -Arguments @("shell", "screencap", "-p", $remoteScreenshot) `
            -AllowFailure
        $localScreenshot = Join-Path $outDirectory "validation-screenshot-$frameIndex.png"
        $pullScreenshot = Invoke-AdbCommand `
            -Name "pull immersive-video validation screenshot $frameIndex" `
            -Arguments @("pull", $remoteScreenshot, $localScreenshot) `
            -AllowFailure
        $commands += $pullScreenshot
        if (Test-Path -LiteralPath $localScreenshot -PathType Leaf) {
            $freshnessScreenshots += [ordered]@{
                index = $frameIndex
                path = $localScreenshot
                sha256 = (Get-FileHash -LiteralPath $localScreenshot -Algorithm SHA256).Hash.ToLowerInvariant()
                size_bytes = (Get-Item -LiteralPath $localScreenshot).Length
            }
            if ([string]::IsNullOrWhiteSpace($screenshotPath)) {
                $screenshotPath = $localScreenshot
            }
        }
        if ($frameIndex -eq 1) {
            Start-Sleep -Seconds 1
        }
    }
    $freshnessUniqueHashCount =
        @($freshnessScreenshots.sha256 | Sort-Object -Unique).Count
}

$requiredMarkers = @(
    "channel=spatial-immersive-video status=route-ready",
    "projectionShape=$Shape",
    "stereoLayout=$Stereo",
    "source=granted-media-content-uri",
    "status=decoded-video-size",
    "status=first-frame-rendered",
    "status=playback-progress",
    "advancing=true"
)
$missingMarkers = @()
if ($Launch) {
    $missingMarkers = @($requiredMarkers | Where-Object { -not $markerOutput.Contains($_) })
}
$playbackErrorDetected =
    ($Launch -and $markerOutput.Contains("status=playback-error"))
$targetFatalDetected =
    ($Launch -and $targetFatalOutput.Contains("FATAL EXCEPTION"))
$boundedRuntimeValidationPassed =
    ([bool]$Launch -and
     $missingMarkers.Count -eq 0 -and
     $targetProcessAliveAfterObservation -and
     -not $playbackErrorDetected -and
     -not $targetFatalDetected)

$receipt = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel.immersive_video_stage_receipt.v2"
    generated_at = (Get-Date).ToUniversalTime().ToString("o")
    source_path = $resolvedSource
    source_size_bytes = $sourceInfo.Length
    source_sha256 = $sourceSha256
    detected_width_px = $detectedDimensions.width
    detected_height_px = $detectedDimensions.height
    explicit_projection_shape = $Shape
    explicit_stereo_layout = $Stereo
    metadata_autodetection_authority = $false
    package_name = $PackageName
    shared_media_destination = $remotePath
    media_store_content_uri = $launchContentUri
    single_uri_read_grant = $true
    broad_shared_storage_permission_required = $false
    videos_bundled_in_apk = $false
    adb_scope = "serial-scoped"
    adb_serial = $Serial
    adb_server_port = $script:ResolvedAdbServerPort
    remote_size_bytes = $remoteSize
    remote_sha256 = $remoteSha256
    transfer_verified = ($remoteSize -eq $sourceInfo.Length -and $remoteSha256 -eq $sourceSha256)
    launch_requested = [bool]$Launch
    autoplay = $Autoplay
    loop = $Loop
    radius_meters = $RadiusMeters
    observe_seconds = $ObserveSeconds
    required_marker_tokens = $requiredMarkers
    missing_marker_tokens = $missingMarkers
    target_pid_after_observation = $targetPid
    target_process_alive_after_observation = $targetProcessAliveAfterObservation
    playback_error_detected = $playbackErrorDetected
    target_fatal_detected = $targetFatalDetected
    target_fatal_output = $targetFatalOutput
    bounded_runtime_validation_passed = $boundedRuntimeValidationPassed
    runtime_marker_validation_passed = $boundedRuntimeValidationPassed
    screenshot_path = $screenshotPath
    freshness_screenshots = $freshnessScreenshots
    freshness_unique_hash_count = $freshnessUniqueHashCount
    compositor_screenshot_freshness_evidenced = ($freshnessUniqueHashCount -gt 1)
    playback_advance_evidenced =
        ($markerOutput.Contains("status=playback-progress") -and
         $markerOutput.Contains("advancing=true"))
    commands = $commands
}
$receipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $outPath -Encoding UTF8

Write-Output "immersive video staged: $remotePath"
Write-Output "transfer verified: $($receipt.transfer_verified)"
if ($Launch) {
    Write-Output "runtime marker validation passed: $($receipt.runtime_marker_validation_passed)"
}
Write-Output $outPath
if ($Launch -and -not $boundedRuntimeValidationPassed) {
    throw (
        "Immersive-video bounded runtime validation failed: " +
        "missingMarkers=$($missingMarkers -join ',') " +
        "targetAlive=$targetProcessAliveAfterObservation " +
        "playbackError=$playbackErrorDetected targetFatal=$targetFatalDetected"
    )
}
