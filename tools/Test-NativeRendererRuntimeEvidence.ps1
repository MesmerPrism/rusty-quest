param(
    [Parameter(Mandatory=$true)]
    [string]$LogcatPath,
    [string]$ScreenshotPath = "",
    [int]$MinimumScreenshotBytes = 1024,
    [int]$MinimumScreenshotWidth = 64,
    [int]$MinimumScreenshotHeight = 64,
    [int]$MinimumScreenshotUniqueColors = 8,
    [double]$MinimumScreenshotLumaRange = 0.02,
    [string[]]$ScreenshotTargetUvRects = @(),
    [int]$MinimumNonFlatScreenshotTargetRects = 1,
    [int]$MinimumNonFlatHandMeshVisualRects = 1,
    [int]$MinimumNonFlatSdfVisualRects = 1,
    [int]$MinimumOverlayColorFamilyPixels = 4,
    [double]$MinimumHandMeshVisualOverlayColorRatio = 0.005,
    [double]$MinimumSdfVisualOverlayColorRatio = 0.005,
    [switch]$RequireScreenshot,
    [switch]$RequireNonFlatScreenshot,
    [switch]$RequireTargetNonFlatScreenshot,
    [switch]$RequireHandMeshVisualScreenshot,
    [switch]$RequireSdfVisualScreenshot,
    [switch]$RequireCameraProjection,
    [switch]$RequireReplayVisualProof,
    [switch]$RequireLiveVisualDiagnosticCaveat,
    [switch]$RequireEnvironmentDepthParticles,
    [switch]$RequireGuideGraph,
    [switch]$RequireSdfVisual,
    [switch]$RequireGpuTimestampReady,
    [switch]$RequirePerformanceBudget,
    [int]$ExpectedEnvironmentDepthParticleCount = 0,
    [int]$MinimumEnvironmentDepthSourceDepthSamples = 0,
    [int]$MinimumEnvironmentDepthHashProbeExhaustedCount = 0,
    [double]$MinimumObservedOpenXrFps = 70.0,
    [int]$MaximumStaleFrames = 0,
    [double]$MaximumRecordCpuMs = 4.0,
    [double]$MaximumSubmitCpuMs = 1.0,
    [double]$MaximumCameraAcquireImportCpuMs = 1.5,
    [double]$MaximumGuideGraphCpuMs = 2.0,
    [double]$MaximumLiveHandLocateCpuMs = 1.0,
    [double]$MaximumHandSdfPrepareCpuMs = 2.0,
    [double]$MaximumHandMeshVisualCpuMs = 1.0,
    [double]$MaximumProjectionCompositeCpuMs = 2.0,
    [double]$MaximumCommandRecordCpuMs = 4.0,
    [double]$MaximumSwapchainWaitCpuMs = 2.0,
    [double]$MaximumQueueSubmitCpuMs = 1.0,
    [double]$MaximumOpenXrEndFrameCpuMs = 1.0,
    [double]$MaximumCameraProjectionGpuMs = 1.0,
    [double]$MaximumGuideGraphGpuMs = 2.0,
    [double]$MaximumHandSdfGpuMs = 2.0,
    [double]$MaximumHandMeshVisualGpuMs = 1.0,
    [double]$MaximumProjectionCompositeGpuMs = 2.0,
    [switch]$RequirePrivateSlotNoPayload,
    [switch]$RequirePrivateSlotPayload,
    [string]$ScreenshotCropOutDir = "",
    [string]$SummaryOut = ""
)

$ErrorActionPreference = "Stop"

function Assert-True {
    param(
        [Parameter(Mandatory=$true)]
        [bool]$Condition,
        [Parameter(Mandatory=$true)]
        [string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-Contains {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Text,
        [Parameter(Mandatory=$true)]
        [string]$Token,
        [Parameter(Mandatory=$true)]
        [string]$Context
    )
    if ($Text -notmatch [regex]::Escape($Token)) {
        throw "$Context missing token: $Token"
    }
}

function Assert-NotRegex {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Text,
        [Parameter(Mandatory=$true)]
        [string]$Pattern,
        [Parameter(Mandatory=$true)]
        [string]$Context
    )
    if ($Text -match $Pattern) {
        throw "$Context rejected by pattern: $Pattern"
    }
}

function Get-LatestMarkerLine {
    param(
        [Parameter(Mandatory=$true)]
        [string[]]$Lines,
        [Parameter(Mandatory=$true)]
        [string]$Channel
    )
    $needle = "RUSTY_QUEST_NATIVE_RENDERER channel=$Channel "
    for ($index = $Lines.Count - 1; $index -ge 0; $index--) {
        if ($Lines[$index].Contains($needle)) {
            return $Lines[$index]
        }
    }
    return ""
}

function Measure-ScreenshotContent {
    param(
        [Parameter(Mandatory=$true)]
        [System.Drawing.Bitmap]$Bitmap,
        [double[]]$UvRect = @()
    )

    $imageWidth = $Bitmap.Width
    $imageHeight = $Bitmap.Height
    $startX = 0
    $startY = 0
    $endX = $imageWidth - 1
    $endY = $imageHeight - 1
    $uvToken = "0.000000,0.000000,1.000000,1.000000"
    if ($UvRect.Count -eq 4) {
        $startX = [Math]::Max(0, [Math]::Min($imageWidth - 1, [int][Math]::Floor($UvRect[0] * $imageWidth)))
        $startY = [Math]::Max(0, [Math]::Min($imageHeight - 1, [int][Math]::Floor($UvRect[1] * $imageHeight)))
        $endX = [Math]::Max($startX, [Math]::Min($imageWidth - 1, [int][Math]::Ceiling(($UvRect[0] + $UvRect[2]) * $imageWidth) - 1))
        $endY = [Math]::Max($startY, [Math]::Min($imageHeight - 1, [int][Math]::Ceiling(($UvRect[1] + $UvRect[3]) * $imageHeight) - 1))
        $uvToken = "{0:F6},{1:F6},{2:F6},{3:F6}" -f $UvRect[0], $UvRect[1], $UvRect[2], $UvRect[3]
    }

    $regionWidth = ($endX - $startX) + 1
    $regionHeight = ($endY - $startY) + 1
    $strideX = [Math]::Max(1, [int][Math]::Floor($regionWidth / 64.0))
    $strideY = [Math]::Max(1, [int][Math]::Floor($regionHeight / 64.0))
    $uniqueColors = New-Object 'System.Collections.Generic.HashSet[string]'
    $lumaMin = [double]::PositiveInfinity
    $lumaMax = [double]::NegativeInfinity
    $samples = 0
    $chromaPixels = 0
    $overlayFamilyPixels = 0
    $cyanLikePixels = 0
    $yellowLikePixels = 0
    $magentaLikePixels = 0
    for ($y = $startY; $y -le $endY; $y += $strideY) {
        for ($x = $startX; $x -le $endX; $x += $strideX) {
            $color = $Bitmap.GetPixel($x, $y)
            $maxChannel = [Math]::Max([Math]::Max([int]$color.R, [int]$color.G), [int]$color.B)
            $minChannel = [Math]::Min([Math]::Min([int]$color.R, [int]$color.G), [int]$color.B)
            $channelDelta = $maxChannel - $minChannel
            $cyanLike = ([int]$color.G -ge 90 -and [int]$color.B -ge 90 -and ([int]$color.R + 32) -lt [Math]::Min([int]$color.G, [int]$color.B))
            $yellowLike = ([int]$color.R -ge 100 -and [int]$color.G -ge 90 -and ([int]$color.B + 32) -lt [Math]::Min([int]$color.R, [int]$color.G))
            $magentaLike = ([int]$color.R -ge 100 -and [int]$color.B -ge 90 -and ([int]$color.G + 32) -lt [Math]::Min([int]$color.R, [int]$color.B))
            $luma = ((0.2126 * [double]$color.R) + (0.7152 * [double]$color.G) + (0.0722 * [double]$color.B)) / 255.0
            if ($luma -lt $lumaMin) {
                $lumaMin = $luma
            }
            if ($luma -gt $lumaMax) {
                $lumaMax = $luma
            }
            [void]$uniqueColors.Add("$($color.R),$($color.G),$($color.B)")
            if ($channelDelta -ge 48 -and $maxChannel -ge 96) {
                $chromaPixels += 1
            }
            if ($cyanLike) {
                $cyanLikePixels += 1
            }
            if ($yellowLike) {
                $yellowLikePixels += 1
            }
            if ($magentaLike) {
                $magentaLikePixels += 1
            }
            if ($cyanLike -or $yellowLike -or $magentaLike) {
                $overlayFamilyPixels += 1
            }
            $samples += 1
        }
    }
    $lumaRange = if ($samples -gt 0) { $lumaMax - $lumaMin } else { 0.0 }
    return [ordered]@{
        uv_rect = $uvToken
        x = $startX
        y = $startY
        width = $regionWidth
        height = $regionHeight
        sampled_pixels = $samples
        sampled_unique_colors = $uniqueColors.Count
        sampled_chroma_pixels = $chromaPixels
        sampled_chroma_ratio = if ($samples -gt 0) { [Math]::Round($chromaPixels / [double]$samples, 6) } else { 0.0 }
        overlay_color_family_pixels = $overlayFamilyPixels
        overlay_color_family_ratio = if ($samples -gt 0) { [Math]::Round($overlayFamilyPixels / [double]$samples, 6) } else { 0.0 }
        cyan_like_pixels = $cyanLikePixels
        yellow_like_pixels = $yellowLikePixels
        magenta_like_pixels = $magentaLikePixels
        luma_min = [Math]::Round($lumaMin, 6)
        luma_max = [Math]::Round($lumaMax, 6)
        luma_range = [Math]::Round($lumaRange, 6)
    }
}

function ConvertTo-ScreenshotUvRect {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Text
    )

    $parts = @($Text -split "[,; `t]+" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($parts.Count -ne 4) {
        throw "Screenshot target UV rect must have four fields: $Text"
    }
    $values = @()
    foreach ($part in $parts) {
        $values += [double]::Parse($part, [System.Globalization.CultureInfo]::InvariantCulture)
    }
    if ($values[0] -lt 0.0 -or $values[1] -lt 0.0 -or $values[2] -le 0.0 -or $values[3] -le 0.0 -or ($values[0] + $values[2]) -gt 1.0 -or ($values[1] + $values[3]) -gt 1.0) {
        throw "Screenshot target UV rect is outside 0..1 bounds: $Text"
    }
    return [double[]]$values
}

function Expand-ScreenshotTargetUvRectTexts {
    param(
        [string[]]$Texts = @()
    )

    $expanded = @()
    foreach ($text in $Texts) {
        foreach ($part in ($text -split "\|")) {
            if (-not [string]::IsNullOrWhiteSpace($part)) {
                $expanded += $part.Trim()
            }
        }
    }
    return $expanded
}

function Get-MarkerNumber {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Line,
        [Parameter(Mandatory=$true)]
        [string]$Field
    )

    $pattern = [regex]::Escape($Field) + "=([0-9]+(\.[0-9]+)?)"
    if ($Line -notmatch $pattern) {
        throw "Marker missing numeric field ${Field}: $Line"
    }
    return [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture)
}

function Get-MarkerInteger {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Line,
        [Parameter(Mandatory=$true)]
        [string]$Field
    )

    $pattern = [regex]::Escape($Field) + "=(\d+)"
    if ($Line -notmatch $pattern) {
        throw "Marker missing integer field ${Field}: $Line"
    }
    return [int64]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture)
}

function Get-MarkerValue {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Line,
        [Parameter(Mandatory=$true)]
        [string]$Field
    )

    $pattern = [regex]::Escape($Field) + "=([^ ]+)"
    if ($Line -match $pattern) {
        return $Matches[1]
    }
    return ""
}

function Get-ScreenshotTargetUvRectTexts {
    param(
        [string[]]$ExplicitTexts = @(),
        [string]$TimingScorecard = ""
    )

    $expanded = @(Expand-ScreenshotTargetUvRectTexts $ExplicitTexts)
    if ($expanded.Count -gt 0) {
        return $expanded
    }
    if ([string]::IsNullOrWhiteSpace($TimingScorecard)) {
        return @()
    }
    $derived = @()
    foreach ($field in @("leftTargetScreenUvRect", "rightTargetScreenUvRect")) {
        $value = Get-MarkerValue -Line $TimingScorecard -Field $field
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $derived += $value
        }
    }
    return $derived
}

function Get-ScreenshotMarkerUvRectTexts {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Line,
        [Parameter(Mandatory=$true)]
        [string[]]$Fields
    )

    if ([string]::IsNullOrWhiteSpace($Line)) {
        return @()
    }
    $derived = @()
    foreach ($field in $Fields) {
        $value = Get-MarkerValue -Line $Line -Field $field
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $derived += $value
        }
    }
    return $derived
}

function Measure-ScreenshotUvRects {
    param(
        [Parameter(Mandatory=$true)]
        [System.Drawing.Bitmap]$Bitmap,
        [string[]]$Texts = @()
    )

    $stats = @()
    foreach ($rectText in $Texts) {
        $stats += Measure-ScreenshotContent -Bitmap $Bitmap -UvRect (ConvertTo-ScreenshotUvRect $rectText)
    }
    return $stats
}

function Count-NonFlatScreenshotRects {
    param(
        [object[]]$Stats = @()
    )

    return @($Stats | Where-Object {
        $_.sampled_unique_colors -ge $MinimumScreenshotUniqueColors -and $_.luma_range -ge $MinimumScreenshotLumaRange
    }).Count
}

function Count-OverlayColorScreenshotRects {
    param(
        [object[]]$Stats = @(),
        [Parameter(Mandatory=$true)]
        [double]$MinimumRatio
    )

    return @($Stats | Where-Object {
        $_.overlay_color_family_pixels -ge $MinimumOverlayColorFamilyPixels -and $_.overlay_color_family_ratio -ge $MinimumRatio
    }).Count
}

function Save-ScreenshotCropSet {
    param(
        [Parameter(Mandatory=$true)]
        [System.Drawing.Bitmap]$Bitmap,
        [object[]]$Stats = @(),
        [string]$OutputDir = "",
        [Parameter(Mandatory=$true)]
        [string]$Prefix
    )

    if ([string]::IsNullOrWhiteSpace($OutputDir) -or $Stats.Count -eq 0) {
        return @()
    }

    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    $artifacts = @()
    for ($index = 0; $index -lt $Stats.Count; $index++) {
        $stat = $Stats[$index]
        $cropPath = Join-Path $OutputDir ("{0}-{1}.png" -f $Prefix, $index)
        $crop = [System.Drawing.Bitmap]::new([int]$stat.width, [int]$stat.height)
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($crop)
            try {
                $destination = [System.Drawing.Rectangle]::new(
                    0,
                    0,
                    [int]$stat.width,
                    [int]$stat.height
                )
                $source = [System.Drawing.Rectangle]::new(
                    [int]$stat.x,
                    [int]$stat.y,
                    [int]$stat.width,
                    [int]$stat.height
                )
                $graphics.DrawImage(
                    $Bitmap,
                    $destination,
                    $source,
                    [System.Drawing.GraphicsUnit]::Pixel
                )
            } finally {
                $graphics.Dispose()
            }
            $crop.Save($cropPath, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $crop.Dispose()
        }
        $artifacts += [ordered]@{
            label = "$Prefix-$index"
            path = (Resolve-Path $cropPath).Path
            uv_rect = $stat.uv_rect
            x = $stat.x
            y = $stat.y
            width = $stat.width
            height = $stat.height
        }
    }
    return $artifacts
}

function Assert-MetricAtMost {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Line,
        [Parameter(Mandatory=$true)]
        [string]$Field,
        [Parameter(Mandatory=$true)]
        [double]$Maximum,
        [Parameter(Mandatory=$true)]
        [string]$Context
    )

    $value = Get-MarkerNumber -Line $Line -Field $Field
    Assert-True ($value -le $Maximum) "$Context $Field=$value exceeds max $Maximum"
    return [ordered]@{
        field = $Field
        value = $value
        maximum = $Maximum
        status = "within-budget"
    }
}

if (-not (Test-Path $LogcatPath)) {
    throw "Logcat artifact not found: $LogcatPath"
}

$resolvedLogcat = (Resolve-Path $LogcatPath).Path
$logText = Get-Content -Raw -Path $resolvedLogcat
$logLines = @(Get-Content -Path $resolvedLogcat)

Assert-Contains $logText "RUSTY_QUEST_NATIVE_RENDERER" "native renderer log"
Assert-NotRegex $logText "FATAL EXCEPTION|AndroidRuntime|\bANR\b|Application Not Responding" "native renderer log"
if ($RequirePrivateSlotNoPayload -and $RequirePrivateSlotPayload) {
    throw "Use either -RequirePrivateSlotNoPayload or -RequirePrivateSlotPayload, not both."
}
if (-not $RequirePrivateSlotPayload) {
    Assert-NotRegex $logText "privateLayerPayloadLinked=true|privateLayerImplementationPath=(?!none\b)\S+" "native renderer private boundary"
}

$summary = [ordered]@{
    schema = "rusty.quest.native_renderer_runtime_evidence.v1"
    logcat_path = $resolvedLogcat
    screenshot_path = $null
    camera_projection_checked = [bool]$RequireCameraProjection
    replay_visual_proof_checked = [bool]$RequireReplayVisualProof
    live_visual_diagnostic_caveat_checked = [bool]$RequireLiveVisualDiagnosticCaveat
    environment_depth_particles_checked = [bool]$RequireEnvironmentDepthParticles
    guide_graph_checked = [bool]$RequireGuideGraph
    sdf_visual_checked = [bool]$RequireSdfVisual
    gpu_timestamp_checked = [bool]$RequireGpuTimestampReady
    performance_budget_checked = [bool]$RequirePerformanceBudget
    private_slot_checked = ([bool]$RequirePrivateSlotNoPayload -or [bool]$RequirePrivateSlotPayload)
    private_slot_payload_checked = [bool]$RequirePrivateSlotPayload
}

$timingScorecard = Get-LatestMarkerLine $logLines "timing-scorecard"
$handMeshVisualLine = Get-LatestMarkerLine $logLines "hand-mesh-visual"
$sdfFieldLine = Get-LatestMarkerLine $logLines "gpu-sdf-field"
$gpuTimingLine = Get-LatestMarkerLine $logLines "gpu-timestamp-timing"
$nativePassthroughLine = Get-LatestMarkerLine $logLines "native-passthrough"
$environmentDepthLine = Get-LatestMarkerLine $logLines "environment-depth"
$environmentDepthParticlesLine = Get-LatestMarkerLine $logLines "environment-depth-particles"

if (($RequireNonFlatScreenshot -or $RequireTargetNonFlatScreenshot -or $RequireHandMeshVisualScreenshot -or $RequireSdfVisualScreenshot) -and -not $RequireScreenshot) {
    throw "Screenshot content requirements need -RequireScreenshot and -ScreenshotPath."
}

if ($RequireScreenshot) {
    Assert-True (-not [string]::IsNullOrWhiteSpace($ScreenshotPath)) "RequireScreenshot needs -ScreenshotPath."
    Assert-True (Test-Path $ScreenshotPath) "Screenshot artifact not found: $ScreenshotPath"
    $screenshot = Get-Item -LiteralPath $ScreenshotPath
    Assert-True ($screenshot.Length -ge $MinimumScreenshotBytes) "Screenshot artifact is too small: $($screenshot.Length) bytes"
    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new($screenshot.FullName)
    $targetCropArtifacts = @()
    $handMeshVisualCropArtifacts = @()
    $sdfVisualCropArtifacts = @()
    try {
        $screenshotStats = Measure-ScreenshotContent -Bitmap $bitmap
        Assert-True ($bitmap.Width -ge $MinimumScreenshotWidth) "Screenshot width is too small: $($bitmap.Width)"
        Assert-True ($bitmap.Height -ge $MinimumScreenshotHeight) "Screenshot height is too small: $($bitmap.Height)"
        $targetStats = Measure-ScreenshotUvRects `
            -Bitmap $bitmap `
            -Texts (Get-ScreenshotTargetUvRectTexts -ExplicitTexts $ScreenshotTargetUvRects -TimingScorecard $timingScorecard)
        $handMeshVisualStats = Measure-ScreenshotUvRects `
            -Bitmap $bitmap `
            -Texts (Get-ScreenshotMarkerUvRectTexts -Line $handMeshVisualLine -Fields @("leftHandMeshVisualScreenUvRect", "rightHandMeshVisualScreenUvRect"))
        $sdfVisualStats = Measure-ScreenshotUvRects `
            -Bitmap $bitmap `
            -Texts (Get-ScreenshotMarkerUvRectTexts -Line $sdfFieldLine -Fields @("leftSdfVisualScreenUvRect", "rightSdfVisualScreenUvRect"))
        if (-not [string]::IsNullOrWhiteSpace($ScreenshotCropOutDir)) {
            $targetCropArtifacts = Save-ScreenshotCropSet `
                -Bitmap $bitmap `
                -Stats $targetStats `
                -OutputDir $ScreenshotCropOutDir `
                -Prefix "target"
            $handMeshVisualCropArtifacts = Save-ScreenshotCropSet `
                -Bitmap $bitmap `
                -Stats $handMeshVisualStats `
                -OutputDir $ScreenshotCropOutDir `
                -Prefix "hand-mesh"
            $sdfVisualCropArtifacts = Save-ScreenshotCropSet `
                -Bitmap $bitmap `
                -Stats $sdfVisualStats `
                -OutputDir $ScreenshotCropOutDir `
                -Prefix "sdf"
        }
    } finally {
        $bitmap.Dispose()
    }
    $summary.screenshot_path = $screenshot.FullName
    $summary.screenshot_bytes = $screenshot.Length
    $summary.screenshot_width = $screenshotStats.width
    $summary.screenshot_height = $screenshotStats.height
    $summary.screenshot_sampled_pixels = $screenshotStats.sampled_pixels
    $summary.screenshot_sampled_unique_colors = $screenshotStats.sampled_unique_colors
    $summary.screenshot_sampled_chroma_pixels = $screenshotStats.sampled_chroma_pixels
    $summary.screenshot_sampled_chroma_ratio = $screenshotStats.sampled_chroma_ratio
    $summary.screenshot_overlay_color_family_pixels = $screenshotStats.overlay_color_family_pixels
    $summary.screenshot_overlay_color_family_ratio = $screenshotStats.overlay_color_family_ratio
    $summary.screenshot_luma_min = $screenshotStats.luma_min
    $summary.screenshot_luma_max = $screenshotStats.luma_max
    $summary.screenshot_luma_range = $screenshotStats.luma_range
    $summary.screenshot_non_flat_checked = [bool]$RequireNonFlatScreenshot
    $summary.screenshot_target_non_flat_checked = [bool]$RequireTargetNonFlatScreenshot
    $summary.screenshot_hand_mesh_visual_checked = [bool]$RequireHandMeshVisualScreenshot
    $summary.screenshot_sdf_visual_checked = [bool]$RequireSdfVisualScreenshot
    $summary.screenshot_target_rects = $targetStats
    $summary.screenshot_hand_mesh_visual_rects = $handMeshVisualStats
    $summary.screenshot_sdf_visual_rects = $sdfVisualStats
    if (-not [string]::IsNullOrWhiteSpace($ScreenshotCropOutDir)) {
        $summary.screenshot_crop_out_dir = (Resolve-Path $ScreenshotCropOutDir).Path
        $summary.screenshot_target_crop_artifacts = $targetCropArtifacts
        $summary.screenshot_hand_mesh_visual_crop_artifacts = $handMeshVisualCropArtifacts
        $summary.screenshot_sdf_visual_crop_artifacts = $sdfVisualCropArtifacts
    }
    if ($RequireNonFlatScreenshot) {
        Assert-True ($screenshotStats.sampled_unique_colors -ge $MinimumScreenshotUniqueColors) "Screenshot sampled color count is too low: $($screenshotStats.sampled_unique_colors)"
        Assert-True ($screenshotStats.luma_range -ge $MinimumScreenshotLumaRange) "Screenshot luma range is too low: $($screenshotStats.luma_range)"
    }
    if ($RequireTargetNonFlatScreenshot) {
        Assert-True ($targetStats.Count -gt 0) "RequireTargetNonFlatScreenshot needs explicit -ScreenshotTargetUvRects or runtime target UV markers."
        $passingTargetRects = Count-NonFlatScreenshotRects -Stats $targetStats
        Assert-True ($passingTargetRects -ge $MinimumNonFlatScreenshotTargetRects) "Only $passingTargetRects screenshot target rects were non-flat; required $MinimumNonFlatScreenshotTargetRects."
        $summary.screenshot_target_non_flat_rects = $passingTargetRects
    }
    if ($RequireHandMeshVisualScreenshot) {
        Assert-True ($handMeshVisualStats.Count -gt 0) "RequireHandMeshVisualScreenshot needs hand mesh visual UV rect markers."
        $passingHandMeshRects = Count-NonFlatScreenshotRects -Stats $handMeshVisualStats
        Assert-True ($passingHandMeshRects -ge $MinimumNonFlatHandMeshVisualRects) "Only $passingHandMeshRects hand mesh visual rects were non-flat; required $MinimumNonFlatHandMeshVisualRects."
        $summary.screenshot_hand_mesh_visual_non_flat_rects = $passingHandMeshRects
        $passingHandMeshColorRects = Count-OverlayColorScreenshotRects `
            -Stats $handMeshVisualStats `
            -MinimumRatio $MinimumHandMeshVisualOverlayColorRatio
        Assert-True ($passingHandMeshColorRects -ge $MinimumNonFlatHandMeshVisualRects) "Only $passingHandMeshColorRects hand mesh visual rects had expected overlay colors; required $MinimumNonFlatHandMeshVisualRects."
        $summary.screenshot_hand_mesh_visual_overlay_color_rects = $passingHandMeshColorRects
    }
    if ($RequireSdfVisualScreenshot) {
        Assert-True ($sdfVisualStats.Count -gt 0) "RequireSdfVisualScreenshot needs SDF visual UV rect markers."
        $passingSdfRects = Count-NonFlatScreenshotRects -Stats $sdfVisualStats
        Assert-True ($passingSdfRects -ge $MinimumNonFlatSdfVisualRects) "Only $passingSdfRects SDF visual rects were non-flat; required $MinimumNonFlatSdfVisualRects."
        $summary.screenshot_sdf_visual_non_flat_rects = $passingSdfRects
        $passingSdfColorRects = Count-OverlayColorScreenshotRects `
            -Stats $sdfVisualStats `
            -MinimumRatio $MinimumSdfVisualOverlayColorRatio
        Assert-True ($passingSdfColorRects -ge $MinimumNonFlatSdfVisualRects) "Only $passingSdfColorRects SDF visual rects had expected overlay colors; required $MinimumNonFlatSdfVisualRects."
        $summary.screenshot_sdf_visual_overlay_color_rects = $passingSdfColorRects
    }
}

if ($RequireCameraProjection) {
    Assert-True (-not [string]::IsNullOrWhiteSpace($timingScorecard)) "Missing timing-scorecard marker."
    foreach ($token in @(
        "openxrSubmitReady=true",
        "cameraProjectionReady=true",
        "projectionReady=true",
        "metadataDrivenTargetFootprint=true"
    )) {
        Assert-Contains $timingScorecard $token "latest timing-scorecard"
    }
    if (-not $RequirePrivateSlotPayload) {
        foreach ($token in @(
            "leftCameraId=50",
            "rightCameraId=51"
        )) {
            Assert-Contains $timingScorecard $token "latest timing-scorecard"
        }
    }
    $staleFrames = Get-MarkerInteger -Line $timingScorecard -Field "stale_frames"
    Assert-True ($staleFrames -le $MaximumStaleFrames) "latest timing-scorecard reports stale_frames=$staleFrames, above max $MaximumStaleFrames"
    $observedOpenXrFps = Get-MarkerNumber -Line $timingScorecard -Field "observedOpenXrFps"
    Assert-True ($observedOpenXrFps -ge $MinimumObservedOpenXrFps) "latest timing-scorecard reports observedOpenXrFps=$observedOpenXrFps, below min $MinimumObservedOpenXrFps"
    $summary.observed_openxr_fps = $observedOpenXrFps
    $summary.stale_frames = $staleFrames
}

if ($RequireReplayVisualProof) {
    Assert-True (-not [string]::IsNullOrWhiteSpace($handMeshVisualLine)) "Missing hand-mesh-visual marker."
    Assert-Contains $timingScorecard "recordedHandReplayVisible=true" "latest timing-scorecard"
    foreach ($token in @(
        "recordedReplayVisualProofEnabled=true",
        "compactHandInputSourceMode=recorded-replay",
        "animatedHandMeshVisualReady=true",
        "animatedHandMeshVisualVisible=true",
        "handMeshCompactInputSource=recorded-replay",
        "gpuTriangleDraw=true",
        "cpuProjection=false",
        "validationMeshUploadPerFrame=false",
        "skinnedPositionBufferResident=true"
    )) {
        Assert-Contains $handMeshVisualLine $token "latest hand-mesh-visual marker"
    }
}

if ($RequireLiveVisualDiagnosticCaveat) {
    Assert-True (-not [string]::IsNullOrWhiteSpace($handMeshVisualLine)) "Missing hand-mesh-visual marker."
    Assert-True (-not [string]::IsNullOrWhiteSpace($sdfFieldLine)) "Missing gpu-sdf-field marker."
    Assert-Contains $timingScorecard "recordedHandReplayVisible=false" "latest timing-scorecard"
    foreach ($token in @(
        "recordedReplayVisualProofEnabled=false",
        "compactHandInputSourceMode=live-meta-openxr-hand-tracking",
        "compactHandInputSelectsLiveFrame=true",
        "compactHandInputAllowsRecordedFallback=false",
        "handMeshCompactInputSource=live-meta-openxr-hand-tracking",
        "liveHandMeshVisualAcceptance=pending-repeat-headset-visual-proof"
    )) {
        Assert-Contains $handMeshVisualLine $token "latest hand-mesh-visual marker"
    }
    foreach ($token in @(
        "recordedReplayVisualProofEnabled=false",
        "compactHandInputSourceMode=live-meta-openxr-hand-tracking",
        "compactHandInputSelectsLiveFrame=true",
        "compactHandInputAllowsRecordedFallback=false",
        "sdfCompactInputSource=live-meta-openxr-hand-tracking",
        "compactJointPoseUploadPerFrame=true",
        "jointMatrixUploadPerFrame=false",
        "liveSdfVisualAcceptance=pending-repeat-headset-visual-proof"
    )) {
        Assert-Contains $sdfFieldLine $token "latest gpu-sdf-field marker"
    }
    Assert-NotRegex $handMeshVisualLine "liveHandMeshVisualAcceptance=(?!pending-repeat-headset-visual-proof\b)\S+" "latest hand-mesh-visual marker"
    Assert-NotRegex $sdfFieldLine "liveSdfVisualAcceptance=(?!pending-repeat-headset-visual-proof\b)\S+" "latest gpu-sdf-field marker"
}

if ($RequireEnvironmentDepthParticles) {
    Assert-True (-not [string]::IsNullOrWhiteSpace($nativePassthroughLine)) "Missing native-passthrough marker."
    foreach ($token in @(
        "nativePassthroughLayerActive=true",
        "passthroughCompositionLayer=CompositionLayerPassthroughFB"
    )) {
        Assert-Contains $nativePassthroughLine $token "latest native-passthrough marker"
    }

    Assert-True (-not [string]::IsNullOrWhiteSpace($environmentDepthLine)) "Missing environment-depth marker."
    foreach ($token in @(
        "environmentDepthSource=xr-meta-environment-depth",
        "environmentDepthProviderState=provider-running",
        "environmentDepthProviderAvailable=true",
        "environmentDepthRealProviderBound=true",
        "environmentDepthSupported=true",
        "environmentDepthAcquireStatus=acquired",
        "environmentDepthFormat=VK_FORMAT_D16_UNORM",
        "environmentDepthLayerCount=2",
        "environmentDepthSourceViewCount=1",
        "environmentDepthSampledLayerMask=0x1",
        "environmentDepthShaderLayerPolicy=mono-layer0",
        "environmentDepthDepthUnitsPolicy=projected-depth-from-near-far",
        "environmentDepthRawToMetersPolicy=projected-depth-from-near-far",
        "environmentDepthDebugView=raw-d16",
        "environmentDepthDepthViewPoseValidMask=0x1",
        "environmentDepthDepthViewFovValidMask=0x1",
        "environmentDepthRenderViewStateFlags=orientation-valid+position-valid",
        "environmentDepthTextureTransformLabel=rotate0+flipY",
        "environmentDepthRayUvPolicy=canonical-untransformed",
        "environmentDepthSampleUvPolicy=texture-transformed",
        "environmentDepthPoseValid=true"
    )) {
        Assert-Contains $environmentDepthLine $token "latest environment-depth marker"
    }
    $captureToDisplayMs = Get-MarkerNumber -Line $environmentDepthLine -Field "environmentDepthCaptureToDisplayMs"
    Assert-True ($captureToDisplayMs -ge 0.0) "environment-depth capture-to-display timing must be nonnegative."
    $frameAgeMs = Get-MarkerNumber -Line $environmentDepthLine -Field "environmentDepthFrameAgeMs"
    Assert-True ($frameAgeMs -ge 0.0) "environment-depth frame age must be nonnegative."
    $repeatedCaptureTimeCount = Get-MarkerInteger -Line $environmentDepthLine -Field "environmentDepthRepeatedCaptureTimeCount"
    Assert-True ($repeatedCaptureTimeCount -ge 0) "environment-depth repeated capture count must be nonnegative."
    $unavailableStreak = Get-MarkerInteger -Line $environmentDepthLine -Field "environmentDepthUnavailableStreak"
    Assert-True ($unavailableStreak -ge 0) "environment-depth unavailable streak must be nonnegative."

    Assert-True (-not [string]::IsNullOrWhiteSpace($environmentDepthParticlesLine)) "Missing environment-depth-particles marker."
    foreach ($token in @(
        "environmentDepthParticleReady=true",
        "environmentDepthParticleVisible=true",
        "environmentDepthMode=scene-particle-map",
        "environmentDepthParticleSource=xr-meta-environment-depth",
        "environmentDepthParticleCoordinateSpace=openxr-reference-space",
        "environmentDepthWorldSpaceReady=true",
        "environmentDepthParticleCpuUploadBytes=0",
        "environmentDepthGpuBuffersResident=true",
        "environmentDepthParticleBufferMemory=device-local",
        "environmentDepthGpuReconstructPath=native-vulkan-compute-depth-view-to-reference-space",
        "environmentDepthGpuDrawPath=native-vulkan-reference-space-billboard-overlay",
        "environmentDepthParticleRetention=scene-owned-spatial-particle-map",
        "environmentDepthParticleMapPolicy=spatial-hash-reference-space-cells",
        "environmentDepthMapWritePolicy=atomic-slot-claim",
        "environmentDepthSceneParticleMap=true",
        "environmentDepthSceneCellMeters=0.060",
        "environmentDepthSceneHashProbeCount=8",
        "environmentDepthInvalidSamplePolicy=preserve-existing-cells",
        "environmentDepthFreeSpaceCorrection=visible-free-space-ray-clear",
        "environmentDepthRealProviderBound=true",
        "environmentDepthSupported=true",
        "environmentDepthAcquireStatus=acquired",
        "environmentDepthPoseValid=true",
        "environmentDepthFormat=VK_FORMAT_D16_UNORM",
        "environmentDepthLayerCount=2",
        "environmentDepthSourceViewCount=1",
        "environmentDepthSampledLayerMask=0x1",
        "environmentDepthShaderLayerPolicy=mono-layer0",
        "environmentDepthDepthUnitsPolicy=projected-depth-from-near-far",
        "environmentDepthRawToMetersPolicy=projected-depth-from-near-far",
        "environmentDepthDebugView=raw-d16",
        "environmentDepthRenderViewStateFlags=orientation-valid+position-valid",
        "environmentDepthTextureTransformLabel=rotate0+flipY",
        "environmentDepthRayUvPolicy=canonical-untransformed",
        "environmentDepthSampleUvPolicy=texture-transformed",
        "environmentDepthRawStatsStatus=readback",
        "environmentDepthDepthViewPoseValidMask=0x1",
        "environmentDepthDepthViewFovValidMask=0x1"
    )) {
        Assert-Contains $environmentDepthParticlesLine $token "latest environment-depth-particles marker"
    }
    $particleCaptureToDisplayMs = Get-MarkerNumber -Line $environmentDepthParticlesLine -Field "environmentDepthCaptureToDisplayMs"
    Assert-True ($particleCaptureToDisplayMs -ge 0.0) "environment-depth-particles capture-to-display timing must be nonnegative."
    $particleAcquireToRenderMs = Get-MarkerNumber -Line $environmentDepthParticlesLine -Field "environmentDepthAcquireToRenderMs"
    Assert-True ($particleAcquireToRenderMs -ge 0.0) "environment-depth-particles acquire-to-render timing must be nonnegative."
    $particleFrameAgeMs = Get-MarkerNumber -Line $environmentDepthParticlesLine -Field "environmentDepthFrameAgeMs"
    Assert-True ($particleFrameAgeMs -ge 0.0) "environment-depth-particles frame age must be nonnegative."

    $particleCount = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthParticleCount"
    Assert-True ($particleCount -gt 0) "environment-depth-particles marker reports no particles."
    if ($ExpectedEnvironmentDepthParticleCount -gt 0) {
        Assert-True ($particleCount -eq $ExpectedEnvironmentDepthParticleCount) "environment-depth-particles marker reports $particleCount particles; expected $ExpectedEnvironmentDepthParticleCount."
    }
    $sourceDepthSamples = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthParticleSourceDepthSamples"
    Assert-True ($sourceDepthSamples -gt 0) "environment-depth-particles marker reports no source depth samples."
    if ($MinimumEnvironmentDepthSourceDepthSamples -gt 0) {
        Assert-True ($sourceDepthSamples -ge $MinimumEnvironmentDepthSourceDepthSamples) "environment-depth-particles marker reports $sourceDepthSamples source depth samples; expected at least $MinimumEnvironmentDepthSourceDepthSamples."
    }
    $rawCenterD16 = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthRawCenterD16"
    Assert-True ($rawCenterD16 -gt 0 -and $rawCenterD16 -le 65535) "environment-depth-particles raw center D16 is outside 1..65535: $rawCenterD16"
    $centerReconstructedMeters = Get-MarkerNumber -Line $environmentDepthParticlesLine -Field "environmentDepthCenterReconstructedMeters"
    Assert-True ($centerReconstructedMeters -gt 0.0) "environment-depth-particles center reconstructed meters must be positive."
    $centerConfidence = Get-MarkerNumber -Line $environmentDepthParticlesLine -Field "environmentDepthCenterConfidence"
    Assert-True ($centerConfidence -ge 0.0 -and $centerConfidence -le 1.0) "environment-depth-particles center confidence is outside 0..1: $centerConfidence"
    $rawMedianD16 = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthRawCenterWindowMedianD16"
    Assert-True ($rawMedianD16 -gt 0 -and $rawMedianD16 -le 65535) "environment-depth-particles center-window median D16 is outside 1..65535: $rawMedianD16"
    $rawCenterWindowValidCount = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthRawCenterWindowValidCount"
    Assert-True ($rawCenterWindowValidCount -gt 0) "environment-depth-particles center-window valid sample count must be positive."
    $minValidReconstructedMeters = Get-MarkerNumber -Line $environmentDepthParticlesLine -Field "environmentDepthMinValidReconstructedMeters"
    $maxValidReconstructedMeters = Get-MarkerNumber -Line $environmentDepthParticlesLine -Field "environmentDepthMaxValidReconstructedMeters"
    Assert-True ($minValidReconstructedMeters -gt 0.0 -and $maxValidReconstructedMeters -ge $minValidReconstructedMeters) "environment-depth-particles reconstructed meter min/max are invalid."
    $debugValidSampleCount = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthDebugValidSampleCount"
    Assert-True ($debugValidSampleCount -gt 0) "environment-depth-particles raw debug readback reports no valid samples."
    $hashInsertSuccessCount = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthHashInsertSuccessCount"
    $hashMergeCount = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthHashMergeCount"
    $hashStaleReplaceCount = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthHashStaleReplaceCount"
    $hashProbeExhaustedCount = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthHashProbeExhaustedCount"
    $freeSpaceRetireAttemptCount = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthFreeSpaceRetireAttemptCount"
    $freeSpaceRetireSuccessCount = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthFreeSpaceRetireSuccessCount"
    $hashOccupancyEstimate = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthHashOccupancyEstimate"
    $hashWriteConflictCount = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthHashWriteConflictCount"
    $hashClaimFailedCount = Get-MarkerInteger -Line $environmentDepthParticlesLine -Field "environmentDepthHashClaimFailedCount"
    $hashUpdateCount = $hashInsertSuccessCount + $hashMergeCount + $hashStaleReplaceCount
    Assert-True ($hashUpdateCount -gt 0) "environment-depth-particles scene-map readback reports no successful insert, merge, or stale replacement."
    if ($MinimumEnvironmentDepthHashProbeExhaustedCount -gt 0) {
        Assert-True ($hashProbeExhaustedCount -ge $MinimumEnvironmentDepthHashProbeExhaustedCount) "environment-depth-particles scene-map reports $hashProbeExhaustedCount exhausted hash probes; expected at least $MinimumEnvironmentDepthHashProbeExhaustedCount."
    }
    Assert-True ($freeSpaceRetireSuccessCount -le $freeSpaceRetireAttemptCount) "environment-depth-particles free-space retire successes exceed attempts."
    $summary.environment_depth_line = $environmentDepthLine
    $summary.environment_depth_particles_line = $environmentDepthParticlesLine
    $summary.environment_depth_capture_to_display_ms = $captureToDisplayMs
    $summary.environment_depth_frame_age_ms = $frameAgeMs
    $summary.environment_depth_repeated_capture_time_count = $repeatedCaptureTimeCount
    $summary.environment_depth_unavailable_streak = $unavailableStreak
    $summary.environment_depth_particle_capture_to_display_ms = $particleCaptureToDisplayMs
    $summary.environment_depth_particle_acquire_to_render_ms = $particleAcquireToRenderMs
    $summary.environment_depth_particle_frame_age_ms = $particleFrameAgeMs
    $summary.environment_depth_particle_count = $particleCount
    $summary.environment_depth_particle_source_depth_samples = $sourceDepthSamples
    $summary.environment_depth_raw_center_d16 = $rawCenterD16
    $summary.environment_depth_center_reconstructed_meters = $centerReconstructedMeters
    $summary.environment_depth_raw_center_window_median_d16 = $rawMedianD16
    $summary.environment_depth_debug_valid_sample_count = $debugValidSampleCount
    $summary.environment_depth_hash_insert_success_count = $hashInsertSuccessCount
    $summary.environment_depth_hash_merge_count = $hashMergeCount
    $summary.environment_depth_hash_stale_replace_count = $hashStaleReplaceCount
    $summary.environment_depth_hash_probe_exhausted_count = $hashProbeExhaustedCount
    $summary.environment_depth_free_space_retire_attempt_count = $freeSpaceRetireAttemptCount
    $summary.environment_depth_free_space_retire_success_count = $freeSpaceRetireSuccessCount
    $summary.environment_depth_hash_occupancy_estimate = $hashOccupancyEstimate
    $summary.environment_depth_hash_write_conflict_count = $hashWriteConflictCount
    $summary.environment_depth_hash_claim_failed_count = $hashClaimFailedCount
}

if ($RequireGuideGraph) {
    $guideLine = Get-LatestMarkerLine $logLines "guide-blur-graph"
    Assert-True (-not [string]::IsNullOrWhiteSpace($guideLine)) "Missing guide-blur-graph marker."
    foreach ($token in @(
        "guideGraphReady=true",
        "guideGraphPath=low-resolution-two-phase-5tap-blur",
        "guideGraphFinalProjectionSource=guide-texture",
        "guideGraphFinalExternalHwbSamples=0",
        "guideTextureSamples=1"
    )) {
        Assert-Contains $guideLine $token "latest guide-blur-graph marker"
    }
}

if ($RequireSdfVisual) {
    Assert-True (-not [string]::IsNullOrWhiteSpace($sdfFieldLine)) "Missing gpu-sdf-field marker."
    foreach ($token in @(
        "dynamicSdfReady=true",
        "sdfVisualEffectVisible=true",
        "gpuSdfFieldReady=true",
        "gpuSdfOverlayVisible=true",
        "cpuSdfPerFrame=false",
        "compactJointPoseUploadPerFrame=true",
        "jointMatrixUploadPerFrame=false",
        "sdfCompactInputSource=recorded-replay",
        "liveSdfVisualAcceptance=not-live-input"
    )) {
        Assert-Contains $sdfFieldLine $token "latest gpu-sdf-field marker"
    }
}

if ($RequireGpuTimestampReady) {
    Assert-True (-not [string]::IsNullOrWhiteSpace($gpuTimingLine)) "Missing gpu-timestamp-timing marker."
    foreach ($token in @(
        "gpuTimestampQuerySupported=true",
        "gpuTimestampQueryReady=true",
        "gpuTimingScope=vulkan-timestamp-query"
    )) {
        Assert-Contains $gpuTimingLine $token "latest gpu-timestamp-timing marker"
    }
    foreach ($field in @("cameraProjectionGpuMs", "guideGraphGpuMs", "handSdfGpuMs", "handMeshVisualGpuMs", "projectionCompositeGpuMs")) {
        if ($gpuTimingLine -notmatch "$field=([0-9]+(\.[0-9]+)?)") {
            throw "latest gpu-timestamp-timing marker missing non-negative $field"
        }
    }
}

if ($RequirePerformanceBudget) {
    Assert-True (-not [string]::IsNullOrWhiteSpace($timingScorecard)) "RequirePerformanceBudget needs a timing-scorecard marker."
    $staleFrames = Get-MarkerInteger -Line $timingScorecard -Field "stale_frames"
    Assert-True ($staleFrames -le $MaximumStaleFrames) "performance budget stale_frames=$staleFrames, above max $MaximumStaleFrames"
    $observedOpenXrFps = Get-MarkerNumber -Line $timingScorecard -Field "observedOpenXrFps"
    Assert-True ($observedOpenXrFps -ge $MinimumObservedOpenXrFps) "performance budget observedOpenXrFps=$observedOpenXrFps, below min $MinimumObservedOpenXrFps"
    $cpuMetrics = @()
    $cpuMetrics += Assert-MetricAtMost -Line $timingScorecard -Field "recordCpuMs" -Maximum $MaximumRecordCpuMs -Context "timing-scorecard"
    $cpuMetrics += Assert-MetricAtMost -Line $timingScorecard -Field "submitCpuMs" -Maximum $MaximumSubmitCpuMs -Context "timing-scorecard"
    $cpuMetrics += Assert-MetricAtMost -Line $timingScorecard -Field "cameraAcquireImportCpuMs" -Maximum $MaximumCameraAcquireImportCpuMs -Context "timing-scorecard"
    $cpuMetrics += Assert-MetricAtMost -Line $timingScorecard -Field "guideGraphCpuMs" -Maximum $MaximumGuideGraphCpuMs -Context "timing-scorecard"
    $cpuMetrics += Assert-MetricAtMost -Line $timingScorecard -Field "liveHandLocateCpuMs" -Maximum $MaximumLiveHandLocateCpuMs -Context "timing-scorecard"
    $cpuMetrics += Assert-MetricAtMost -Line $timingScorecard -Field "handSdfPrepareCpuMs" -Maximum $MaximumHandSdfPrepareCpuMs -Context "timing-scorecard"
    $cpuMetrics += Assert-MetricAtMost -Line $timingScorecard -Field "handMeshVisualCpuMs" -Maximum $MaximumHandMeshVisualCpuMs -Context "timing-scorecard"
    $cpuMetrics += Assert-MetricAtMost -Line $timingScorecard -Field "projectionCompositeCpuMs" -Maximum $MaximumProjectionCompositeCpuMs -Context "timing-scorecard"
    $cpuMetrics += Assert-MetricAtMost -Line $timingScorecard -Field "commandRecordCpuMs" -Maximum $MaximumCommandRecordCpuMs -Context "timing-scorecard"
    $cpuMetrics += Assert-MetricAtMost -Line $timingScorecard -Field "swapchainWaitCpuMs" -Maximum $MaximumSwapchainWaitCpuMs -Context "timing-scorecard"
    $cpuMetrics += Assert-MetricAtMost -Line $timingScorecard -Field "queueSubmitCpuMs" -Maximum $MaximumQueueSubmitCpuMs -Context "timing-scorecard"
    $cpuMetrics += Assert-MetricAtMost -Line $timingScorecard -Field "openxrEndFrameCpuMs" -Maximum $MaximumOpenXrEndFrameCpuMs -Context "timing-scorecard"
    $summary.performance_budget_observed_openxr_fps = $observedOpenXrFps
    $summary.performance_budget_minimum_openxr_fps = $MinimumObservedOpenXrFps
    $summary.performance_budget_stale_frames = $staleFrames
    $summary.performance_budget_maximum_stale_frames = $MaximumStaleFrames
    $summary.performance_budget_cpu_metrics = $cpuMetrics
    if (-not [string]::IsNullOrWhiteSpace($gpuTimingLine)) {
        $gpuMetrics = @()
        $gpuMetrics += Assert-MetricAtMost -Line $gpuTimingLine -Field "cameraProjectionGpuMs" -Maximum $MaximumCameraProjectionGpuMs -Context "gpu-timestamp-timing"
        $gpuMetrics += Assert-MetricAtMost -Line $gpuTimingLine -Field "guideGraphGpuMs" -Maximum $MaximumGuideGraphGpuMs -Context "gpu-timestamp-timing"
        $gpuMetrics += Assert-MetricAtMost -Line $gpuTimingLine -Field "handSdfGpuMs" -Maximum $MaximumHandSdfGpuMs -Context "gpu-timestamp-timing"
        $gpuMetrics += Assert-MetricAtMost -Line $gpuTimingLine -Field "handMeshVisualGpuMs" -Maximum $MaximumHandMeshVisualGpuMs -Context "gpu-timestamp-timing"
        $gpuMetrics += Assert-MetricAtMost -Line $gpuTimingLine -Field "projectionCompositeGpuMs" -Maximum $MaximumProjectionCompositeGpuMs -Context "gpu-timestamp-timing"
        $summary.performance_budget_gpu_metrics = $gpuMetrics
    }
}

if ($RequirePrivateSlotNoPayload) {
    $privateLine = Get-LatestMarkerLine $logLines "private-extension-slot"
    Assert-True (-not [string]::IsNullOrWhiteSpace($privateLine)) "Missing private-extension-slot marker."
    foreach ($token in @(
        "privateLayerSlotReady=true",
        "privateLayerPublicAbiOnly=true",
        "privateLayerPayloadLinked=false",
        "privateLayerImplementationPath=none",
        "privateLayerOutput=identity-public-abi-resource",
        "privateLayerColorEffectActive=false",
        "privateLayerVisualAcceptance=not-applicable-public-noop"
    )) {
        Assert-Contains $privateLine $token "latest private-extension-slot marker"
    }
}

if ($RequirePrivateSlotPayload) {
    $privateLine = Get-LatestMarkerLine $logLines "private-extension-slot"
    Assert-True (-not [string]::IsNullOrWhiteSpace($privateLine)) "Missing private-extension-slot marker."
    foreach ($token in @(
        "privateLayerSlotReady=true",
        "privateLayerPublicAbiOnly=false",
        "privateLayerPayloadLinked=true",
        "privateLayerImplementationPath=external-private-shader-dir",
        "privateLayerEnabled=true",
        "privateLayerReady=true",
        "privateLayerRendered=true",
        "privateLayerOutput=resident-private-guide-texture-final",
        "privateLayerColorEffectActive=true",
        "privateLayerGuideTargets=5",
        "privateLayerGuidePasses=6"
    )) {
        Assert-Contains $privateLine $token "latest private-extension-slot marker"
    }
    $summary.private_slot_payload_line = $privateLine
}

if (-not [string]::IsNullOrWhiteSpace($SummaryOut)) {
    $summaryDir = Split-Path -Parent $SummaryOut
    if (-not [string]::IsNullOrWhiteSpace($summaryDir)) {
        New-Item -ItemType Directory -Force -Path $summaryDir | Out-Null
    }
    $summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -Path $SummaryOut
}

Write-Output "Rusty Quest native renderer runtime evidence validation passed"
