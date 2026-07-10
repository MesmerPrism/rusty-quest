# Dot-sourced helper functions for Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1.
# Keep these functions side-effect free until called by the runner facade.

function Count-LinesContaining {
    param([string[]]$Lines, [string]$Needle)
    $count = 0
    foreach ($line in $Lines) {
        if ($line -like "*$Needle*") {
            $count += 1
        }
    }
    return $count
}

function Select-LastLineContaining {
    param([string[]]$Lines, [string]$Needle)
    for ($i = $Lines.Count - 1; $i -ge 0; $i--) {
        if ($Lines[$i] -like "*$Needle*") {
            return [string]$Lines[$i]
        }
    }
    return $null
}

function Test-Qcl100RemoteBrokerProjectionScorecardLine {
    param([string]$Line)
    if ([string]::IsNullOrWhiteSpace($Line)) {
        return $false
    }
    return [bool](
        $Line -like "*remoteBrokerCameraProjectionActive=true*" -and
        $Line -like "*sourceAuthority=manifold-broker-rmanvid1-camera2-h264*" -and
        $Line -like "*cameraProjectionReady=true*" -and
        $Line -like "*openxrSubmitReady=true*" -and
        $Line -like "*vulkanExternalImportReady=true*" -and
        $Line -like "*projectionReady=true*" -and
        $Line -like "*cameraProjectionPath=*remote-broker*" -and
        $Line -like "*leftCameraId=remote-broker-*" -and
        $Line -like "*rightCameraId=remote-broker-*"
    )
}

function Get-MarkerValue {
    param([string]$Line, [string]$Key)
    if ([string]::IsNullOrWhiteSpace($Line)) {
        return $null
    }
    $match = [regex]::Match($Line, "(^|\s)$([regex]::Escape($Key))=([^\s]+)")
    if (-not $match.Success) {
        return $null
    }
    return $match.Groups[2].Value
}

function ConvertTo-IntSafe {
    param($Value)
    try {
        return [int]$Value
    } catch {
        return 0
    }
}

function ConvertTo-LongSafe {
    param($Value)
    try {
        if ($null -eq $Value) {
            return 0L
        }
        return [long]$Value
    } catch {
        return 0L
    }
}

function Get-LogcatSecondOfDay {
    param([string]$Line)
    if ([string]::IsNullOrWhiteSpace($Line)) {
        return $null
    }
    $match = [regex]::Match($Line, "^\d\d-\d\d\s+(\d\d):(\d\d):(\d\d)\.(\d\d\d)")
    if (-not $match.Success) {
        return $null
    }
    return ([int]$match.Groups[1].Value * 3600.0) +
        ([int]$match.Groups[2].Value * 60.0) +
        [int]$match.Groups[3].Value +
        ([int]$match.Groups[4].Value / 1000.0)
}

function Get-ElapsedSeconds {
    param($EarlierSecondOfDay, $LaterSecondOfDay)
    if ($null -eq $EarlierSecondOfDay -or $null -eq $LaterSecondOfDay) {
        return $null
    }
    $delta = [double]$LaterSecondOfDay - [double]$EarlierSecondOfDay
    if ($delta -lt -43200.0) {
        $delta += 86400.0
    }
    if ($delta -lt 0.0) {
        return $null
    }
    return $delta
}

function Get-RemoteFrameFreshness {
    param(
        [string[]]$Lines,
        [string]$Needle,
        [string]$FinalScorecardLine,
        [double]$MaxFinalFrameAgeSeconds = 5.0,
        [double]$MinFrameSpanSeconds = $MinFreshFrameSpanSeconds,
        [int]$MinimumFrameLines = $MinFreshFrameLines
    )
    $frameLines = @($Lines | Where-Object { $_ -like "*$Needle*" })
    $sourceFrames = @($frameLines | ForEach-Object {
        ConvertTo-IntSafe (Get-MarkerValue -Line $_ -Key "sourceFrame")
    } | Where-Object { $_ -gt 0 })
    $importSequences = @($frameLines | ForEach-Object {
        ConvertTo-IntSafe (Get-MarkerValue -Line $_ -Key "importSequence")
    } | Where-Object { $_ -gt 0 })
    $hardwareBufferIds = @($frameLines | ForEach-Object {
        Get-MarkerValue -Line $_ -Key "hardwareBufferId"
    } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
    $firstLine = if ($frameLines.Count -gt 0) { [string]$frameLines[0] } else { $null }
    $lastLine = if ($frameLines.Count -gt 0) { [string]$frameLines[-1] } else { $null }
    $firstSourceFrame = if ($sourceFrames.Count -gt 0) { [int]$sourceFrames[0] } else { 0 }
    $lastSourceFrame = if ($sourceFrames.Count -gt 0) { [int]$sourceFrames[-1] } else { 0 }
    $firstImportSequence = if ($importSequences.Count -gt 0) { [int]$importSequences[0] } else { 0 }
    $lastImportSequence = if ($importSequences.Count -gt 0) { [int]$importSequences[-1] } else { 0 }
    $lastFrameSecond = Get-LogcatSecondOfDay -Line $lastLine
    $firstFrameSecond = Get-LogcatSecondOfDay -Line $firstLine
    $scorecardSecond = Get-LogcatSecondOfDay -Line $FinalScorecardLine
    $ageSeconds = Get-ElapsedSeconds -EarlierSecondOfDay $lastFrameSecond -LaterSecondOfDay $scorecardSecond
    if ($null -eq $ageSeconds -and $null -ne $lastFrameSecond -and $null -ne $scorecardSecond) {
        $frameAfterScorecardSeconds = Get-ElapsedSeconds -EarlierSecondOfDay $scorecardSecond -LaterSecondOfDay $lastFrameSecond
        if ($null -ne $frameAfterScorecardSeconds -and $frameAfterScorecardSeconds -le $MaxFinalFrameAgeSeconds) {
            $ageSeconds = 0.0
        }
    }
    $spanSeconds = Get-ElapsedSeconds -EarlierSecondOfDay $firstFrameSecond -LaterSecondOfDay $lastFrameSecond
    $recent = $null -ne $ageSeconds -and $ageSeconds -le $MaxFinalFrameAgeSeconds
    $sustained = $null -ne $spanSeconds -and $spanSeconds -ge $MinFrameSpanSeconds
    $enoughFrames = $frameLines.Count -ge $MinimumFrameLines
    $advancing = $sourceFrames.Count -ge 2 -and $lastSourceFrame -gt $firstSourceFrame
    [ordered]@{
        line_count = $frameLines.Count
        minimum_frame_lines = $MinimumFrameLines
        first_source_frame = $firstSourceFrame
        last_source_frame = $lastSourceFrame
        source_frame_delta = $lastSourceFrame - $firstSourceFrame
        first_import_sequence = $firstImportSequence
        last_import_sequence = $lastImportSequence
        import_sequence_delta = $lastImportSequence - $firstImportSequence
        distinct_hardware_buffer_ids = $hardwareBufferIds.Count
        frame_span_seconds = $spanSeconds
        minimum_frame_span_seconds = $MinFrameSpanSeconds
        final_frame_age_seconds = $ageSeconds
        enough_frame_lines = [bool]$enoughFrames
        sustained_frame_span = [bool]$sustained
        advancing_source_frames = [bool]$advancing
        recent_at_final_scorecard = [bool]$recent
        fresh = [bool]($advancing -and $recent -and $sustained -and $enoughFrames)
        first_line = $firstLine
        last_line = $lastLine
    }
}

function Get-ScorecardFrameFreshness {
    param(
        [string[]]$Lines,
        [string]$SourceFrameKey,
        [string]$FinalScorecardLine,
        [double]$MaxFinalFrameAgeSeconds = 5.0,
        [double]$MinFrameSpanSeconds = $MinFreshFrameSpanSeconds,
        [int]$MinimumFrameLines = $MinFreshFrameLines
    )
    $candidateScorecardLines = @($Lines | Where-Object {
        $_ -like "*channel=camera-projection-scorecard*"
    })
    $scorecardLines = @($candidateScorecardLines | Where-Object {
        Test-Qcl100RemoteBrokerProjectionScorecardLine -Line $_
    })
    $samples = @($scorecardLines | ForEach-Object {
        $sourceFrame = ConvertTo-IntSafe (Get-MarkerValue -Line $_ -Key $SourceFrameKey)
        $second = Get-LogcatSecondOfDay -Line $_
        if ($sourceFrame -gt 0 -and $null -ne $second) {
            [pscustomobject]@{
                line = [string]$_
                second = [double]$second
                source_frame = [int]$sourceFrame
            }
        }
    })
    $first = if ($samples.Count -gt 0) { $samples[0] } else { $null }
    $last = if ($samples.Count -gt 0) { $samples[-1] } else { $null }
    $firstSourceFrame = if ($null -ne $first) { [int]$first.source_frame } else { 0 }
    $lastSourceFrame = if ($null -ne $last) { [int]$last.source_frame } else { 0 }
    $scorecardSecond = Get-LogcatSecondOfDay -Line $FinalScorecardLine
    $spanSeconds = if ($null -ne $first -and $null -ne $last) {
        Get-ElapsedSeconds -EarlierSecondOfDay $first.second -LaterSecondOfDay $last.second
    } else {
        $null
    }
    $ageSeconds = if ($null -ne $last) {
        Get-ElapsedSeconds -EarlierSecondOfDay $last.second -LaterSecondOfDay $scorecardSecond
    } else {
        $null
    }
    $finalWindowSamples = @($samples | Where-Object {
        $age = Get-ElapsedSeconds -EarlierSecondOfDay $_.second -LaterSecondOfDay $scorecardSecond
        $null -ne $age -and $age -le $MaxFinalFrameAgeSeconds
    })
    $firstFinalWindow = if ($finalWindowSamples.Count -gt 0) { $finalWindowSamples[0] } else { $null }
    $finalWindowDelta = if ($null -ne $firstFinalWindow -and $null -ne $last) {
        [int]$last.source_frame - [int]$firstFinalWindow.source_frame
    } else {
        0
    }
    $recent = $null -ne $ageSeconds -and $ageSeconds -le $MaxFinalFrameAgeSeconds
    $sustained = $null -ne $spanSeconds -and $spanSeconds -ge $MinFrameSpanSeconds
    $enoughScorecards = $samples.Count -ge $MinimumFrameLines
    $advancing = $samples.Count -ge 2 -and $lastSourceFrame -gt $firstSourceFrame
    $advancedNearFinal = $finalWindowSamples.Count -ge 2 -and $finalWindowDelta -gt 0
    [ordered]@{
        required = "final-window native renderer camera-projection scorecards must be remote-broker RMANVID1 scorecards with remote-broker camera ids, ready markers, sustained span, and source-frame advancement inside the final window"
        candidate_scorecard_line_count = $candidateScorecardLines.Count
        line_count = $samples.Count
        minimum_frame_lines = $MinimumFrameLines
        source_authority_required = "manifold-broker-rmanvid1-camera2-h264"
        camera_id_required_prefix = "remote-broker-"
        first_source_frame = $firstSourceFrame
        last_source_frame = $lastSourceFrame
        source_frame_delta = $lastSourceFrame - $firstSourceFrame
        frame_span_seconds = $spanSeconds
        minimum_frame_span_seconds = $MinFrameSpanSeconds
        final_scorecard_age_seconds = $ageSeconds
        final_window_seconds = $MaxFinalFrameAgeSeconds
        final_window_line_count = $finalWindowSamples.Count
        final_window_source_frame_delta = $finalWindowDelta
        enough_scorecards = [bool]$enoughScorecards
        sustained_scorecard_span = [bool]$sustained
        advancing_scorecard_source_frames = [bool]$advancing
        advanced_in_final_window = [bool]$advancedNearFinal
        recent_at_final_scorecard = [bool]$recent
        fresh = [bool]($enoughScorecards -and $sustained -and $advancing -and $advancedNearFinal -and $recent)
        first_line = if ($null -ne $first) { $first.line } else { $null }
        last_line = if ($null -ne $last) { $last.line } else { $null }
    }
}

function New-Qcl100SyntheticNativeRendererLog {
    param(
        [int]$LeftFirstSourceFrame,
        [int]$LeftLastSourceFrame,
        [int]$RightFirstSourceFrame,
        [int]$RightLastSourceFrame,
        [string]$FirstFrameTime = "20:00:01.000",
        [string]$LastFrameTime = "20:00:29.000",
        [string]$ScorecardTime = "20:00:30.000"
    )
    $leftSecondSourceFrame = $LeftFirstSourceFrame + [Math]::Min(1, [Math]::Max(0, $LeftLastSourceFrame - $LeftFirstSourceFrame))
    $rightSecondSourceFrame = $RightFirstSourceFrame + [Math]::Min(1, [Math]::Max(0, $RightLastSourceFrame - $RightFirstSourceFrame))
    $leftThirdSourceFrame = $LeftFirstSourceFrame + [Math]::Min(2, [Math]::Max(0, $LeftLastSourceFrame - $LeftFirstSourceFrame))
    $rightThirdSourceFrame = $RightFirstSourceFrame + [Math]::Min(2, [Math]::Max(0, $RightLastSourceFrame - $RightFirstSourceFrame))
    $leftPenultimateSourceFrame = [Math]::Max($LeftFirstSourceFrame, $LeftLastSourceFrame - 1)
    $rightPenultimateSourceFrame = [Math]::Max($RightFirstSourceFrame, $RightLastSourceFrame - 1)
    return @(
        "07-02 $FirstFrameTime  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=video-projection videoProjectionSource=broker-rmanvid1"
        "07-02 $FirstFrameTime  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=stream-header side=left magic=RMANVID1"
        "07-02 $FirstFrameTime  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=stream-header side=right magic=RMANVID1"
        "07-02 $FirstFrameTime  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-ahardware-buffer status=frame side=left sourceFrame=$LeftFirstSourceFrame importSequence=1 hardwareBufferId=left-1"
        "07-02 $FirstFrameTime  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-ahardware-buffer status=frame side=right sourceFrame=$RightFirstSourceFrame importSequence=1 hardwareBufferId=right-1"
        "07-02 $FirstFrameTime  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=camera-projection-scorecard remoteBrokerCameraProjectionActive=true cameraProjectionReady=true openxrSubmitReady=true vulkanExternalImportReady=true projectionReady=true cameraProjectionPath=metadata-target-remote-broker-camera2-h264 leftCameraId=remote-broker-left rightCameraId=remote-broker-right leftSourceFrame=$LeftFirstSourceFrame rightSourceFrame=$RightFirstSourceFrame leftHardwareBufferId=left-1 rightHardwareBufferId=right-1 leftImportSequence=1 rightImportSequence=1 sourceAuthority=manifold-broker-rmanvid1-camera2-h264"
        "07-02 20:00:08.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-ahardware-buffer status=frame side=left sourceFrame=$leftSecondSourceFrame importSequence=2 hardwareBufferId=left-2"
        "07-02 20:00:08.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-ahardware-buffer status=frame side=right sourceFrame=$rightSecondSourceFrame importSequence=2 hardwareBufferId=right-2"
        "07-02 20:00:08.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=camera-projection-scorecard remoteBrokerCameraProjectionActive=true cameraProjectionReady=true openxrSubmitReady=true vulkanExternalImportReady=true projectionReady=true cameraProjectionPath=metadata-target-remote-broker-camera2-h264 leftCameraId=remote-broker-left rightCameraId=remote-broker-right leftSourceFrame=$leftSecondSourceFrame rightSourceFrame=$rightSecondSourceFrame leftHardwareBufferId=left-2 rightHardwareBufferId=right-2 leftImportSequence=2 rightImportSequence=2 sourceAuthority=manifold-broker-rmanvid1-camera2-h264"
        "07-02 20:00:15.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-ahardware-buffer status=frame side=left sourceFrame=$leftThirdSourceFrame importSequence=3 hardwareBufferId=left-3"
        "07-02 20:00:15.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-ahardware-buffer status=frame side=right sourceFrame=$rightThirdSourceFrame importSequence=3 hardwareBufferId=right-3"
        "07-02 20:00:15.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=camera-projection-scorecard remoteBrokerCameraProjectionActive=true cameraProjectionReady=true openxrSubmitReady=true vulkanExternalImportReady=true projectionReady=true cameraProjectionPath=metadata-target-remote-broker-camera2-h264 leftCameraId=remote-broker-left rightCameraId=remote-broker-right leftSourceFrame=$leftThirdSourceFrame rightSourceFrame=$rightThirdSourceFrame leftHardwareBufferId=left-3 rightHardwareBufferId=right-3 leftImportSequence=3 rightImportSequence=3 sourceAuthority=manifold-broker-rmanvid1-camera2-h264"
        "07-02 20:00:22.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-ahardware-buffer status=frame side=left sourceFrame=$leftPenultimateSourceFrame importSequence=4 hardwareBufferId=left-4"
        "07-02 20:00:22.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-ahardware-buffer status=frame side=right sourceFrame=$rightPenultimateSourceFrame importSequence=4 hardwareBufferId=right-4"
        "07-02 20:00:22.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=camera-projection-scorecard remoteBrokerCameraProjectionActive=true cameraProjectionReady=true openxrSubmitReady=true vulkanExternalImportReady=true projectionReady=true cameraProjectionPath=metadata-target-remote-broker-camera2-h264 leftCameraId=remote-broker-left rightCameraId=remote-broker-right leftSourceFrame=$leftPenultimateSourceFrame rightSourceFrame=$rightPenultimateSourceFrame leftHardwareBufferId=left-4 rightHardwareBufferId=right-4 leftImportSequence=4 rightImportSequence=4 sourceAuthority=manifold-broker-rmanvid1-camera2-h264"
        "07-02 $LastFrameTime  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-ahardware-buffer status=frame side=left sourceFrame=$LeftLastSourceFrame importSequence=5 hardwareBufferId=left-5"
        "07-02 $LastFrameTime  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-ahardware-buffer status=frame side=right sourceFrame=$RightLastSourceFrame importSequence=5 hardwareBufferId=right-5"
        "07-02 $LastFrameTime  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=camera-projection-import status=ok side=left"
        "07-02 $LastFrameTime  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=camera-projection-import status=ok side=right"
        "07-02 $LastFrameTime  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=camera-projection-scorecard remoteBrokerCameraProjectionActive=true cameraProjectionReady=true openxrSubmitReady=true vulkanExternalImportReady=true projectionReady=true cameraProjectionPath=metadata-target-remote-broker-camera2-h264 leftCameraId=remote-broker-left rightCameraId=remote-broker-right leftSourceFrame=$leftPenultimateSourceFrame rightSourceFrame=$rightPenultimateSourceFrame leftHardwareBufferId=left-4 rightHardwareBufferId=right-4 leftImportSequence=4 rightImportSequence=4 sourceAuthority=manifold-broker-rmanvid1-camera2-h264"
        "07-02 $ScorecardTime  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=camera-projection-scorecard remoteBrokerCameraProjectionActive=true cameraProjectionReady=true openxrSubmitReady=true vulkanExternalImportReady=true projectionReady=true cameraProjectionPath=metadata-target-remote-broker-camera2-h264 leftCameraId=remote-broker-left rightCameraId=remote-broker-right leftSourceFrame=$LeftLastSourceFrame rightSourceFrame=$RightLastSourceFrame leftHardwareBufferId=left-2 rightHardwareBufferId=right-2 leftImportSequence=2 rightImportSequence=2 sourceAuthority=manifold-broker-rmanvid1-camera2-h264"
    )
}

function Assert-Qcl100FreshnessCase {
    param(
        [string]$Name,
        [string[]]$Lines,
        [bool]$ExpectedFresh,
        [Nullable[bool]]$ExpectedStreamFresh = $null,
        [Nullable[bool]]$ExpectedProjectionReady = $null,
        [Nullable[bool]]$ExpectedScorecardFresh = $null
    )
    $path = Join-Path $OutDir "$Name-native-renderer.logcat.txt"
    $Lines | Set-Content -Encoding UTF8 -LiteralPath $path
    $summary = Summarize-NativeRendererLog -LogPath $path
    $expectedStream = if ($null -ne $ExpectedStreamFresh) { [bool]$ExpectedStreamFresh } else { $ExpectedFresh }
    $expectedProjection = if ($null -ne $ExpectedProjectionReady) { [bool]$ExpectedProjectionReady } else { $ExpectedFresh }
    $expectedScorecard = if ($null -ne $ExpectedScorecardFresh) { [bool]$ExpectedScorecardFresh } else { $ExpectedFresh }
    if ([bool]$summary.stream_fresh_frames -ne $expectedStream) {
        throw "QCL100 freshness self-test '$Name' expected stream_fresh_frames=$expectedStream but got $($summary.stream_fresh_frames)."
    }
    if ([bool]$summary.scorecard_fresh_frames -ne $expectedScorecard) {
        throw "QCL100 freshness self-test '$Name' expected scorecard_fresh_frames=$expectedScorecard but got $($summary.scorecard_fresh_frames)."
    }
    if ([bool]$summary.projection_ready -ne $expectedProjection) {
        throw "QCL100 freshness self-test '$Name' expected projection_ready=$expectedProjection but got $($summary.projection_ready)."
    }
    return [ordered]@{
        name = $Name
        expected_fresh = $ExpectedFresh
        expected_stream_fresh = $expectedStream
        expected_scorecard_fresh = $expectedScorecard
        expected_projection_ready = $expectedProjection
        stream_fresh_frames = [bool]$summary.stream_fresh_frames
        scorecard_fresh_frames = [bool]$summary.scorecard_fresh_frames
        projection_ready = [bool]$summary.projection_ready
        left = $summary.left_frame_freshness
        right = $summary.right_frame_freshness
        left_scorecard = $summary.left_scorecard_freshness
        right_scorecard = $summary.right_scorecard_freshness
    }
}

function Invoke-Qcl100FreshnessSelfTest {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $results = @(
        Assert-Qcl100FreshnessCase `
            -Name "fresh-advancing" `
            -Lines (New-Qcl100SyntheticNativeRendererLog -LeftFirstSourceFrame 1 -LeftLastSourceFrame 6 -RightFirstSourceFrame 2 -RightLastSourceFrame 7) `
            -ExpectedFresh $true
        Assert-Qcl100FreshnessCase `
            -Name "frozen-source-frame" `
            -Lines (New-Qcl100SyntheticNativeRendererLog -LeftFirstSourceFrame 4 -LeftLastSourceFrame 4 -RightFirstSourceFrame 8 -RightLastSourceFrame 8) `
            -ExpectedFresh $false
        Assert-Qcl100FreshnessCase `
            -Name "one-frame-at-start" `
            -Lines @(
                "07-02 20:00:01.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=video-projection videoProjectionSource=broker-rmanvid1",
                "07-02 20:00:01.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=stream-header side=left magic=RMANVID1",
                "07-02 20:00:01.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=stream-header side=right magic=RMANVID1",
                "07-02 20:00:01.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-ahardware-buffer status=frame side=left sourceFrame=4 importSequence=1 hardwareBufferId=left-1",
                "07-02 20:00:01.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-ahardware-buffer status=frame side=right sourceFrame=8 importSequence=1 hardwareBufferId=right-1",
                "07-02 20:00:30.000  100  200 I RustyQuest: RUSTY_QUEST_NATIVE_RENDERER channel=camera-projection-scorecard remoteBrokerCameraProjectionActive=true cameraProjectionReady=true openxrSubmitReady=true vulkanExternalImportReady=true projectionReady=true cameraProjectionPath=metadata-target-remote-broker-camera2-h264 leftCameraId=remote-broker-left rightCameraId=remote-broker-right leftSourceFrame=4 rightSourceFrame=8 leftHardwareBufferId=left-1 rightHardwareBufferId=right-1 leftImportSequence=1 rightImportSequence=1 sourceAuthority=manifold-broker-rmanvid1-camera2-h264"
            ) `
            -ExpectedFresh $false
        Assert-Qcl100FreshnessCase `
            -Name "short-frame-burst" `
            -Lines (New-Qcl100SyntheticNativeRendererLog -LeftFirstSourceFrame 1 -LeftLastSourceFrame 6 -RightFirstSourceFrame 2 -RightLastSourceFrame 7 -FirstFrameTime "20:00:01.000" -LastFrameTime "20:00:03.000" -ScorecardTime "20:00:04.000") `
            -ExpectedFresh $false
        Assert-Qcl100FreshnessCase `
            -Name "stale-before-scorecard" `
            -Lines (New-Qcl100SyntheticNativeRendererLog -LeftFirstSourceFrame 1 -LeftLastSourceFrame 9 -RightFirstSourceFrame 2 -RightLastSourceFrame 10 -LastFrameTime "20:00:20.000" -ScorecardTime "20:00:30.000") `
            -ExpectedFresh $false
        Assert-Qcl100FreshnessCase `
            -Name "fresh-frames-system-fatal-blocks-projection" `
            -Lines @((New-Qcl100SyntheticNativeRendererLog -LeftFirstSourceFrame 1 -LeftLastSourceFrame 6 -RightFirstSourceFrame 2 -RightLastSourceFrame 7) + "07-02 20:00:29.500  100  200 E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: main") `
            -ExpectedFresh $false `
            -ExpectedStreamFresh $true `
            -ExpectedScorecardFresh $true `
            -ExpectedProjectionReady $false
        Assert-Qcl100FreshnessCase `
            -Name "fresh-frames-missing-rmanvid1-scorecard-authority" `
            -Lines @((New-Qcl100SyntheticNativeRendererLog -LeftFirstSourceFrame 1 -LeftLastSourceFrame 6 -RightFirstSourceFrame 2 -RightLastSourceFrame 7) | ForEach-Object {
                $_ -replace "\s+sourceAuthority=manifold-broker-rmanvid1-camera2-h264", ""
            }) `
            -ExpectedFresh $false `
            -ExpectedStreamFresh $true `
            -ExpectedScorecardFresh $false `
            -ExpectedProjectionReady $false
    )
    Write-JsonFile -Value ([ordered]@{
        schema = "rusty.quest.qcl100_freshness_self_test.v1"
        required = "both_eyes_have_at_least_minimum_frame_lines_advancing_source_frames_minimum_span_and_last_remote_frame_within_5s_of_final_remote_broker_RMANVID1_native_renderer_scorecard"
        cases = $results
    }) -Path (Join-Path $OutDir "qcl100-freshness-self-test.json")
    Write-Output "QCL100 freshness self-test passed."
}

function Summarize-NativeRendererLog {
    param([string]$LogPath)
    $lines = @(Get-Content -LiteralPath $LogPath -ErrorAction SilentlyContinue)
    $nativePackagePattern = [regex]::Escape($NativeRendererPackage)
    $fatal = @($lines | Where-Object {
        $_ -match "Process:\s+$nativePackagePattern|Fatal signal.*$nativePackagePattern|SIGSEGV.*$nativePackagePattern|SIGABRT.*$nativePackagePattern|GPU page fault.*$nativePackagePattern|ANR in $nativePackagePattern"
    })
    $systemFatal = @($lines | Where-Object {
        $_ -match "FATAL EXCEPTION|AndroidRuntime|Fatal signal|SIGSEGV|SIGABRT|GPU page fault|ANR in"
    })
    $videoConfig = Select-LastLineContaining -Lines $lines -Needle "channel=video-projection"
    $sourceAuthority = Select-LastLineContaining -Lines $lines -Needle "sourceAuthority=manifold-broker-rmanvid1-camera2-h264"
    if (-not $sourceAuthority) {
        $sourceAuthority = Select-LastLineContaining -Lines $lines -Needle "videoProjectionSourceAuthority=manifold-broker-rmanvid1-camera2-h264"
    }
    $remoteBrokerActiveMarker = Select-LastLineContaining -Lines $lines -Needle "remoteBrokerCameraProjectionActive=true"
    $leftHeader = Select-LastLineContaining -Lines $lines -Needle "channel=remote-camera-broker-inlet status=stream-header side=left"
    $rightHeader = Select-LastLineContaining -Lines $lines -Needle "channel=remote-camera-broker-inlet status=stream-header side=right"
    $packedHeader = Select-LastLineContaining -Lines $lines -Needle "channel=remote-camera-broker-inlet status=stream-header side=stereo"
    $leftInletFrame = Select-LastLineContaining -Lines $lines -Needle "channel=remote-camera-broker-inlet status=frame side=left"
    $rightInletFrame = Select-LastLineContaining -Lines $lines -Needle "channel=remote-camera-broker-inlet status=frame side=right"
    $leftAhbFrame = Select-LastLineContaining -Lines $lines -Needle "channel=remote-camera-broker-ahardware-buffer status=frame side=left"
    $rightAhbFrame = Select-LastLineContaining -Lines $lines -Needle "channel=remote-camera-broker-ahardware-buffer status=frame side=right"
    $packedAhbFrame = Select-LastLineContaining -Lines $lines -Needle "channel=remote-camera-broker-ahardware-buffer status=frame side=stereo"
    $leftProjectionImport = Select-LastLineContaining -Lines $lines -Needle "channel=camera-projection-import status=ok side=left"
    $rightProjectionImport = Select-LastLineContaining -Lines $lines -Needle "channel=camera-projection-import status=ok side=right"
    $projectionScorecards = @($lines | Where-Object { $_ -like "*channel=camera-projection-scorecard*" })
    $remoteProjectionScorecards = @($projectionScorecards | Where-Object {
        Test-Qcl100RemoteBrokerProjectionScorecardLine -Line $_
    })
    $scorecards = @($lines | Where-Object { $_ -like "*channel=timing-scorecard*" })
    $remoteScorecards = @($scorecards | Where-Object {
        $_ -like "*leftCameraId=remote-broker-*" -and $_ -like "*rightCameraId=remote-broker-*"
    })
    $lastProjectionScorecard = if ($projectionScorecards.Count -gt 0) { [string]$projectionScorecards[-1] } else { $null }
    $lastRemoteProjectionScorecard = if ($remoteProjectionScorecards.Count -gt 0) { [string]$remoteProjectionScorecards[-1] } else { $null }
    $lastScorecard = if ($scorecards.Count -gt 0) { [string]$scorecards[-1] } else { $null }
    $lastRemoteScorecard = if ($remoteScorecards.Count -gt 0) { [string]$remoteScorecards[-1] } else { $null }
    $scorecardForReadiness = if ($lastRemoteProjectionScorecard) {
        $lastRemoteProjectionScorecard
    } elseif ($lastProjectionScorecard) {
        $lastProjectionScorecard
    } elseif ($lastRemoteScorecard) {
        $lastRemoteScorecard
    } else {
        $lastScorecard
    }
    $cameraProjectionReady = (Get-MarkerValue -Line $scorecardForReadiness -Key "cameraProjectionReady") -eq "true"
    $openxrReady = (Get-MarkerValue -Line $scorecardForReadiness -Key "openxrSubmitReady") -eq "true"
    $vulkanReady = (Get-MarkerValue -Line $scorecardForReadiness -Key "vulkanExternalImportReady") -eq "true"
    $projectionReady = (Get-MarkerValue -Line $scorecardForReadiness -Key "projectionReady") -eq "true"
    $leftFrameFreshness = Get-RemoteFrameFreshness -Lines $lines -Needle "channel=remote-camera-broker-ahardware-buffer status=frame side=left" -FinalScorecardLine $scorecardForReadiness
    $rightFrameFreshness = Get-RemoteFrameFreshness -Lines $lines -Needle "channel=remote-camera-broker-ahardware-buffer status=frame side=right" -FinalScorecardLine $scorecardForReadiness
    $packedFrameFreshness = Get-RemoteFrameFreshness -Lines $lines -Needle "channel=remote-camera-broker-ahardware-buffer status=frame side=stereo" -FinalScorecardLine $scorecardForReadiness
    if ($packedMediaLayout) {
        $leftFrameFreshness = $packedFrameFreshness
        $rightFrameFreshness = $packedFrameFreshness
    }
    $leftScorecardFreshness = Get-ScorecardFrameFreshness -Lines $lines -SourceFrameKey "leftSourceFrame" -FinalScorecardLine $scorecardForReadiness
    $rightScorecardFreshness = Get-ScorecardFrameFreshness -Lines $lines -SourceFrameKey "rightSourceFrame" -FinalScorecardLine $scorecardForReadiness
    if ($LaneMode -eq "left-only") {
        $streamFreshFrames = [bool]$leftFrameFreshness.fresh
        $scorecardFreshFrames = [bool]$leftScorecardFreshness.fresh
    } elseif ($LaneMode -eq "right-only") {
        $streamFreshFrames = [bool]$rightFrameFreshness.fresh
        $scorecardFreshFrames = [bool]$rightScorecardFreshness.fresh
    } else {
        $streamFreshFrames = [bool]($leftFrameFreshness.fresh -and $rightFrameFreshness.fresh)
        $scorecardFreshFrames = [bool]($leftScorecardFreshness.fresh -and $rightScorecardFreshness.fresh)
    }
    $brokerSourceActive = [bool](
        $videoConfig -like "*videoProjectionSource=broker-rmanvid1*" -or
        $sourceAuthority -or
        $leftAhbFrame -or
        $rightAhbFrame -or
        $packedAhbFrame
    )
    $remoteBrokerProjectionActive = [bool](
        $remoteBrokerActiveMarker -or
        $leftAhbFrame -or
        $rightAhbFrame -or
        $packedAhbFrame -or
        $lastRemoteProjectionScorecard -or
        $lastRemoteScorecard
    )
    $leftLaneRequired = [bool]($LaneMode -ne "right-only")
    $rightLaneRequired = [bool]($LaneMode -ne "left-only")
    $requiredAhbFramesReady = if ($packedMediaLayout) {
        [bool]$packedAhbFrame
    } else {
        [bool](
            ((-not $leftLaneRequired) -or $leftAhbFrame) -and
            ((-not $rightLaneRequired) -or $rightAhbFrame)
        )
    }
    $requiredProjectionImportsReady = if ($packedMediaLayout) {
        [bool]($packedAhbFrame -and $vulkanReady)
    } else {
        [bool](
            ((-not $leftLaneRequired) -or $leftProjectionImport) -and
            ((-not $rightLaneRequired) -or $rightProjectionImport)
        )
    }
    [ordered]@{
        log_path = $LogPath
        marker_counts = [ordered]@{
            video_projection = Count-LinesContaining -Lines $lines -Needle "channel=video-projection"
            broker_inlet = Count-LinesContaining -Lines $lines -Needle "channel=remote-camera-broker-inlet"
            broker_inlet_frame = Count-LinesContaining -Lines $lines -Needle "channel=remote-camera-broker-inlet status=frame"
            broker_ahardware_buffer = Count-LinesContaining -Lines $lines -Needle "channel=remote-camera-broker-ahardware-buffer status=frame"
            camera_projection_import = Count-LinesContaining -Lines $lines -Needle "channel=camera-projection-import"
            camera_projection_import_left_ok = Count-LinesContaining -Lines $lines -Needle "channel=camera-projection-import status=ok side=left"
            camera_projection_import_right_ok = Count-LinesContaining -Lines $lines -Needle "channel=camera-projection-import status=ok side=right"
            camera_projection_scorecard = $projectionScorecards.Count
            remote_camera_projection_scorecard = $remoteProjectionScorecards.Count
            timing_scorecard = $scorecards.Count
            remote_camera_scorecard = $remoteScorecards.Count
        }
        video_projection_broker_source = $brokerSourceActive
        remote_broker_camera_projection_active = $remoteBrokerProjectionActive
        left_stream_header_ok = [bool]($null -ne $leftHeader)
        right_stream_header_ok = [bool]($null -ne $rightHeader)
        packed_stream_header_ok = [bool]($null -ne $packedHeader)
        packed_media_layout = [bool]$packedMediaLayout
        left_inlet_frame_count = Count-LinesContaining -Lines $lines -Needle "channel=remote-camera-broker-inlet status=frame side=left"
        right_inlet_frame_count = Count-LinesContaining -Lines $lines -Needle "channel=remote-camera-broker-inlet status=frame side=right"
        left_ahb_frame_count = Count-LinesContaining -Lines $lines -Needle "channel=remote-camera-broker-ahardware-buffer status=frame side=left"
        right_ahb_frame_count = Count-LinesContaining -Lines $lines -Needle "channel=remote-camera-broker-ahardware-buffer status=frame side=right"
        packed_ahb_frame_count = Count-LinesContaining -Lines $lines -Needle "channel=remote-camera-broker-ahardware-buffer status=frame side=stereo"
        left_frame_freshness = $leftFrameFreshness
        right_frame_freshness = $rightFrameFreshness
        stream_fresh_frames = $streamFreshFrames
        left_scorecard_freshness = $leftScorecardFreshness
        right_scorecard_freshness = $rightScorecardFreshness
        scorecard_fresh_frames = $scorecardFreshFrames
        left_projection_import_ok_count = Count-LinesContaining -Lines $lines -Needle "channel=camera-projection-import status=ok side=left"
        right_projection_import_ok_count = Count-LinesContaining -Lines $lines -Needle "channel=camera-projection-import status=ok side=right"
        scorecard_left_camera_id = Get-MarkerValue -Line $scorecardForReadiness -Key "leftCameraId"
        scorecard_right_camera_id = Get-MarkerValue -Line $scorecardForReadiness -Key "rightCameraId"
        scorecard_left_source_frame = ConvertTo-IntSafe (Get-MarkerValue -Line $scorecardForReadiness -Key "leftSourceFrame")
        scorecard_right_source_frame = ConvertTo-IntSafe (Get-MarkerValue -Line $scorecardForReadiness -Key "rightSourceFrame")
        observed_openxr_fps = Get-MarkerValue -Line $scorecardForReadiness -Key "observedOpenXrFps"
        camera_projection_ready = $cameraProjectionReady
        openxr_submit_ready = $openxrReady
        vulkan_external_import_ready = $vulkanReady
        projection_ready_marker = $projectionReady
        fatal_count = $fatal.Count
        fatal_lines = @($fatal | Select-Object -First 20)
        system_fatal_count = $systemFatal.Count
        system_fatal_lines = @($systemFatal | Select-Object -First 20)
        last_video_projection = $videoConfig
        last_source_authority = $sourceAuthority
        last_remote_broker_active_marker = $remoteBrokerActiveMarker
        left_header = $leftHeader
        right_header = $rightHeader
        left_inlet_frame = $leftInletFrame
        right_inlet_frame = $rightInletFrame
        left_ahb_frame = $leftAhbFrame
        right_ahb_frame = $rightAhbFrame
        packed_ahb_frame = $packedAhbFrame
        left_projection_import = $leftProjectionImport
        right_projection_import = $rightProjectionImport
        active_lane_ahb_frames_ready = $requiredAhbFramesReady
        active_lane_projection_imports_ready = $requiredProjectionImportsReady
        last_camera_projection_scorecard = $lastProjectionScorecard
        last_scorecard = $scorecardForReadiness
        projection_ready = [bool](
            $brokerSourceActive -and
            $remoteBrokerProjectionActive -and
            $requiredAhbFramesReady -and
            $streamFreshFrames -and
            $requiredProjectionImportsReady -and
            ($lastRemoteProjectionScorecard -or $lastRemoteScorecard) -and
            $cameraProjectionReady -and
            $openxrReady -and
            $vulkanReady -and
            $projectionReady -and
            $fatal.Count -eq 0 -and
            $systemFatal.Count -eq 0
        )
    }
}
