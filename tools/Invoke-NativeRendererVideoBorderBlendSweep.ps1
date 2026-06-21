# Invoke-NativeRendererVideoBorderBlendSweep.ps1
param(
    [string]$ApkPath = "target\native-renderer-android\rusty-quest-native-renderer.apk",
    [string]$ProfilePath = "fixtures\runtime-profiles\quest-native-renderer-hwb-video-border-blend.profile.json",
    [string[]]$Modes = @(
        "alpha-over",
        "crossfade",
        "linear-crossfade",
        "luma-match",
        "chroma-luma",
        "soft-light",
        "overlay",
        "screen",
        "multiply",
        "gradient-aware",
        "two-band",
        "temporal-stabilized"
    ),
    [string]$OutDir = "",
    [int]$RunSeconds = 8,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.native_renderer",
    [string]$Activity = "io.github.mesmerprism.rustyquest.native_renderer/android.app.NativeActivity",
    [switch]$SkipInstall,
    [switch]$ClearLogcat,
    [switch]$AllowFlatScreenshot,
    [switch]$AllowPerformanceBudgetMiss,
    [switch]$DryRunOnly,
    [switch]$StopAfterSweep
)

$ErrorActionPreference = "Stop"

$BlendModeProperty = "debug.rustyquest.native_renderer.video_border_blend.mode"
$AllowedModes = @(
    "alpha-over",
    "crossfade",
    "linear-crossfade",
    "luma-match",
    "chroma-luma",
    "soft-light",
    "overlay",
    "screen",
    "multiply",
    "gradient-aware",
    "two-band",
    "temporal-stabilized"
)

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
        $output = & powershell @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "$Name failed with exit code $exitCode`n$($output -join "`n")"
    }
    return ($output -join "`n")
}

function Get-ModeDescriptor {
    param([Parameter(Mandatory=$true)][string]$Mode)
    switch ($Mode) {
        "alpha-over" {
            return [ordered]@{
                option = "1-alpha-feather"
                formula = "fixed-function premultiplied camera alpha over video"
                cost_tier = "baseline-fixed-function"
                sample_pattern = "video background pass plus guide pass"
            }
        }
        "crossfade" {
            return [ordered]@{
                option = "2-shader-crossfade"
                formula = "mix(video, camera, mask)"
                cost_tier = "low"
                sample_pattern = "one guide sample plus one video sample"
            }
        }
        "linear-crossfade" {
            return [ordered]@{
                option = "3-linear-light-crossfade"
                formula = "linearize, mix, convert back"
                cost_tier = "low-medium"
                sample_pattern = "one guide sample plus one video sample plus pow"
            }
        }
        "luma-match" {
            return [ordered]@{
                option = "4-luma-matched-feather"
                formula = "camera luma gain matched toward video near edge"
                cost_tier = "medium"
                sample_pattern = "one guide sample plus one video sample plus luma gain"
            }
        }
        "chroma-luma" {
            return [ordered]@{
                option = "5-chroma-luma-split"
                formula = "camera luma/detail with faster video chroma blend near edge"
                cost_tier = "medium"
                sample_pattern = "one guide sample plus one video sample plus luma/chroma split"
            }
        }
        "soft-light" {
            return [ordered]@{
                option = "6-soft-light"
                formula = "band-limited soft-light blend"
                cost_tier = "medium"
                sample_pattern = "one guide sample plus one video sample plus artistic blend math"
            }
        }
        "overlay" {
            return [ordered]@{
                option = "6-overlay"
                formula = "band-limited overlay blend"
                cost_tier = "medium"
                sample_pattern = "one guide sample plus one video sample plus artistic blend math"
            }
        }
        "screen" {
            return [ordered]@{
                option = "6-screen"
                formula = "band-limited screen blend"
                cost_tier = "medium"
                sample_pattern = "one guide sample plus one video sample plus artistic blend math"
            }
        }
        "multiply" {
            return [ordered]@{
                option = "6-multiply"
                formula = "band-limited multiply blend"
                cost_tier = "medium"
                sample_pattern = "one guide sample plus one video sample plus artistic blend math"
            }
        }
        "gradient-aware" {
            return [ordered]@{
                option = "7-gradient-aware"
                formula = "bias mask toward sharper source using screen-space derivatives"
                cost_tier = "medium-high"
                sample_pattern = "one guide sample plus one video sample plus derivatives"
            }
        }
        "two-band" {
            return [ordered]@{
                option = "8-two-band"
                formula = "wide low-frequency blend plus narrower high-frequency blend"
                cost_tier = "high"
                sample_pattern = "five-tap guide and five-tap video low-pass"
            }
        }
        "temporal-stabilized" {
            return [ordered]@{
                option = "9-temporal-stabilized-mask"
                formula = "crossfade with per-eye target-mask EMA"
                cost_tier = "low-medium"
                sample_pattern = "single samples plus small per-frame CPU-side state"
            }
        }
        default { throw "Unsupported blend mode: $Mode" }
    }
}

function Get-ModeRuntimeMarkers {
    param([Parameter(Mandatory=$true)][string]$Mode)
    $shaderCompositeActive = if ($Mode -eq "alpha-over") { "false" } else { "true" }
    $compositor = if ($Mode -eq "alpha-over") {
        "fixed-function-premultiplied-alpha"
    } else {
        "guide-video-shader-composite"
    }
    $formula = switch ($Mode) {
        "alpha-over" { "premultiplied-alpha-over" }
        "crossfade" { "srgb-crossfade" }
        "linear-crossfade" { "linear-light-crossfade" }
        "luma-match" { "luma-matched-crossfade" }
        "chroma-luma" { "chroma-luma-split" }
        "soft-light" { "soft-light-band" }
        "overlay" { "overlay-band" }
        "screen" { "screen-band" }
        "multiply" { "multiply-band" }
        "gradient-aware" { "gradient-aware-weight-bias" }
        "two-band" { "two-band-low-high-split" }
        "temporal-stabilized" { "temporal-stabilized-mask-crossfade" }
        default { throw "Unsupported blend mode: $Mode" }
    }
    $costTier = switch ($Mode) {
        "alpha-over" { "baseline-fixed-function" }
        "crossfade" { "low" }
        "linear-crossfade" { "low-medium" }
        "luma-match" { "medium" }
        "chroma-luma" { "medium" }
        "soft-light" { "medium" }
        "overlay" { "medium" }
        "screen" { "medium" }
        "multiply" { "medium" }
        "gradient-aware" { "medium-high" }
        "two-band" { "high" }
        "temporal-stabilized" { "low-medium" }
        default { throw "Unsupported blend mode: $Mode" }
    }
    $samplePattern = switch ($Mode) {
        "alpha-over" { "fixed-function-alpha" }
        "gradient-aware" { "guide-and-video-single-sample-plus-derivatives" }
        "two-band" { "guide-and-video-five-tap-low-high-split" }
        default { "guide-and-video-single-sample" }
    }
    $temporalState = if ($Mode -eq "temporal-stabilized") {
        "per-eye-target-rect-ema"
    } else {
        "none"
    }
    return @(
        "videoBorderBlendMode=$Mode",
        "videoBorderBlendCompositor=$compositor",
        "videoBorderBlendShaderCompositeActive=$shaderCompositeActive",
        "videoBorderBlendFormula=$formula",
        "videoBorderBlendCostTier=$costTier",
        "videoBorderBlendSamplePattern=$samplePattern",
        "videoBorderBlendTemporalState=$temporalState"
    )
}

function Set-ProfileBlendMode {
    param(
        [Parameter(Mandatory=$true)][object]$Profile,
        [Parameter(Mandatory=$true)][string]$Mode,
        [Parameter(Mandatory=$true)][string]$ProfileId
    )
    $Profile.profile_id = $ProfileId
    if ($Profile.PSObject.Properties.Name -contains "name") {
        $Profile.name = "$($Profile.name) ($Mode sweep)"
    }
    if ($Profile.PSObject.Properties.Name -notcontains "set_properties") {
        throw "Base profile missing set_properties."
    }
    $setting = @($Profile.set_properties | Where-Object { $_.name -eq $BlendModeProperty } | Select-Object -First 1)
    if ($setting.Count -eq 0) {
        throw "Base profile missing required setting: $BlendModeProperty"
    }
    $setting[0].value = $Mode
    $setting[0].source_setting_id = "native_renderer.video_border_blend.sweep.mode"
    if ($Profile.PSObject.Properties.Name -notcontains "expected_markers") {
        throw "Base profile missing expected_markers."
    }
    $modeMarkers = Get-ModeRuntimeMarkers -Mode $Mode
    $Profile.expected_markers = @(
        @($Profile.expected_markers | Where-Object {
            $_ -notmatch "^videoBorderBlendMode=" -and
            $_ -notmatch "^videoBorderBlendCompositor=" -and
            $_ -notmatch "^videoBorderBlendShaderCompositeActive=" -and
            $_ -notmatch "^videoBorderBlendFormula=" -and
            $_ -notmatch "^videoBorderBlendCostTier=" -and
            $_ -notmatch "^videoBorderBlendSamplePattern=" -and
            $_ -notmatch "^videoBorderBlendTemporalState="
        }) +
        $modeMarkers
    )
}

function Get-LatestMarkerLine {
    param(
        [Parameter(Mandatory=$true)][string[]]$Lines,
        [Parameter(Mandatory=$true)][string]$Channel
    )
    $needle = "RUSTY_QUEST_NATIVE_RENDERER channel=$Channel "
    for ($index = $Lines.Count - 1; $index -ge 0; $index--) {
        if ($Lines[$index].Contains($needle)) {
            return $Lines[$index]
        }
    }
    return ""
}

function Get-MarkerValue {
    param(
        [string]$Line,
        [string]$Field
    )
    if ([string]::IsNullOrWhiteSpace($Line)) {
        return $null
    }
    $pattern = [regex]::Escape($Field) + "=([^ ]+)"
    if ($Line -match $pattern) {
        return $Matches[1]
    }
    return $null
}

function Get-MarkerNumberOrNull {
    param(
        [string]$Line,
        [string]$Field
    )
    $raw = Get-MarkerValue -Line $Line -Field $Field
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return $null
    }
    $value = 0.0
    if ([double]::TryParse($raw, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$value)) {
        return $value
    }
    return $null
}

function Write-SweepReport {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][object[]]$Results
    )
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# Native Video Border Blend Sweep")
    $lines.Add("")
    $lines.Add("Generated: $((Get-Date).ToUniversalTime().ToString("o"))")
    $lines.Add("")
    $lines.Add("| Mode | Option | Status | Cost | FPS | Stale | Projection CPU ms | Guide CPU ms | Screenshot |")
    $lines.Add("| --- | --- | --- | --- | ---: | ---: | ---: | ---: | --- |")
    foreach ($result in $Results) {
        $screenshot = if ([string]::IsNullOrWhiteSpace($result.screenshot_path)) { "" } else { "[png]($($result.screenshot_path))" }
        $lines.Add((
            "| `{0}` | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} |" -f
            $result.mode,
            $result.option,
            $result.status,
            $result.cost_tier,
            $result.observed_openxr_fps,
            $result.stale_frames,
            $result.projection_composite_cpu_ms,
            $result.guide_graph_cpu_ms,
            $screenshot
        ))
    }
    $lines.Add("")
    $lines.Add("Each row is produced from the same APK/profile route with only `$BlendModeProperty` changed. `alpha-over` is the fixed-function baseline; all other modes use the guide/video shader compositor. Poisson/gradient-domain blending is intentionally out of scope for this realtime Quest sweep.")
    [System.IO.File]::WriteAllLines($Path, [string[]]$lines)
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$resolvedProfile = if ([System.IO.Path]::IsPathRooted($ProfilePath)) {
    $ProfilePath
} else {
    Join-Path $repoRoot $ProfilePath
}
if (-not (Test-Path $resolvedProfile)) {
    throw "Base runtime profile not found: $resolvedProfile"
}

foreach ($mode in $Modes) {
    if ($AllowedModes -notcontains $mode) {
        throw "Unsupported mode '$mode'. Allowed modes: $($AllowedModes -join ', ')"
    }
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRoot "local-artifacts\native-renderer-video-border-blend-sweep\$stamp"
} elseif (-not [System.IO.Path]::IsPathRooted($OutDir)) {
    $OutDir = Join-Path $repoRoot $OutDir
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$profileOutDir = Join-Path $OutDir "profiles"
New-Item -ItemType Directory -Force -Path $profileOutDir | Out-Null

$script:ResolvedAdb = $null
$script:ResolvedAdbServerPort = $null
if (-not $DryRunOnly) {
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        throw "-Serial or RUSTY_QUEST_SERIAL is required unless -DryRunOnly is set."
    }
    $script:Serial = $Serial
    $script:ResolvedAdb = Resolve-ToolPath `
        -Name "adb" `
        -Value $Adb `
        -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
    $script:ResolvedAdbServerPort = Resolve-AdbServerPortArgument -Value $AdbServerPort
    $resolvedApk = if ([System.IO.Path]::IsPathRooted($ApkPath)) {
        $ApkPath
    } else {
        Join-Path $repoRoot $ApkPath
    }
    if (-not (Test-Path $resolvedApk)) {
        throw "APK not found: $resolvedApk"
    }
}

$summaryPath = Join-Path $OutDir "blend-sweep-summary.json"
$reportPath = Join-Path $OutDir "video-border-blend-sweep-report.md"
$results = @()

try {
    if (-not $DryRunOnly) {
        $state = Invoke-AdbCommand -Name "adb get-state" -Arguments @("get-state")
        if ($state.output.Trim() -ne "device") {
            throw "ADB target is not ready: $($state.output.Trim())"
        }
        if (-not $SkipInstall) {
            Invoke-AdbCommand -Name "install APK" -Arguments @("install", "-r", "-d", "-g", (Resolve-Path $resolvedApk).Path) | Out-Null
        }
        $pregrantArgs = @(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", (Join-Path $PSScriptRoot "Grant-NativeRendererPermissions.ps1"),
            "-Adb", $script:ResolvedAdb,
            "-Serial", $Serial,
            "-PackageName", $PackageName,
            "-Out", (Join-Path $OutDir "permission-pregrant.json")
        )
        if ($null -ne $script:ResolvedAdbServerPort) {
            $pregrantArgs += @("-AdbServerPort", $script:ResolvedAdbServerPort)
        }
        Invoke-CheckedPowershell -Name "native renderer permission pregrant" -Arguments $pregrantArgs | Out-Null
    }

    foreach ($mode in $Modes) {
        $descriptor = Get-ModeDescriptor -Mode $mode
        $modeDir = Join-Path $OutDir $mode
        New-Item -ItemType Directory -Force -Path $modeDir | Out-Null
        $modeProfilePath = Join-Path $profileOutDir ("quest-native-renderer-hwb-video-border-blend-{0}.profile.json" -f $mode)
        $propertyPlanPath = Join-Path $modeDir "property-write-plan.json"
        $rawLogcatPath = Join-Path $modeDir "raw-logcat.txt"
        $filteredLogcatPath = Join-Path $modeDir "filtered-native-renderer-logcat.txt"
        $screenshotPath = Join-Path $modeDir "screenshot.png"
        $evidenceSummaryPath = Join-Path $modeDir "runtime-evidence-summary.json"
        $cropDir = Join-Path $modeDir "screenshot-crops"
        $remoteScreenshotPath = "/data/local/tmp/rusty_quest_video_border_blend_$mode.png"

        $profile = Get-Content -Raw -LiteralPath $resolvedProfile | ConvertFrom-Json
        Set-ProfileBlendMode `
            -Profile $profile `
            -Mode $mode `
            -ProfileId ("profile.quest.native_renderer.hwb_video_border_blend.{0}.sweep" -f $mode)
        $profile | ConvertTo-Json -Depth 16 | Set-Content -Encoding UTF8 -Path $modeProfilePath

        $profileArgs = @(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", (Join-Path $PSScriptRoot "Apply-RuntimeProfile.ps1"),
            "-ProfilePath", $modeProfilePath,
            "-Out", $propertyPlanPath
        )
        if ($DryRunOnly) {
            $profileArgs += "-DryRun"
        } else {
            $profileArgs += @(
                "-Execute",
                "-Adb", $script:ResolvedAdb,
                "-Serial", $Serial
            )
            if ($null -ne $script:ResolvedAdbServerPort) {
                $profileArgs += @("-AdbServerPort", $script:ResolvedAdbServerPort)
            }
        }
        $profileOutput = Invoke-CheckedPowershell -Name "runtime profile apply for $mode" -Arguments $profileArgs

        $result = [ordered]@{
            mode = $mode
            option = $descriptor.option
            formula = $descriptor.formula
            cost_tier = $descriptor.cost_tier
            sample_pattern = $descriptor.sample_pattern
            status = "dry-run"
            profile_path = (Resolve-Path $modeProfilePath).Path
            property_plan_path = (Resolve-Path $propertyPlanPath).Path
            profile_apply_output = $profileOutput
            screenshot_path = $null
            filtered_logcat_path = $null
            runtime_evidence_summary_path = $null
            timing_scorecard_line = ""
            projection_border_line = ""
            guide_video_composite_line = ""
            observed_openxr_fps = $null
            stale_frames = $null
            projection_composite_cpu_ms = $null
            guide_graph_cpu_ms = $null
            record_cpu_ms = $null
            command_record_cpu_ms = $null
            projection_composite_gpu_ms = $null
        }

        if (-not $DryRunOnly) {
            if ($ClearLogcat) {
                Invoke-AdbCommand -Name "clear logcat" -Arguments @("logcat", "-c") | Out-Null
            }
            Invoke-AdbCommand -Name "force-stop before $mode" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
            $result.launch_output = (Invoke-AdbCommand -Name "launch native renderer for $mode" -Arguments @("shell", "am", "start", "-W", "-n", $Activity)).output
            Start-Sleep -Seconds ([Math]::Max(1, $RunSeconds))
            $pidResult = Invoke-AdbCommand -Name "native renderer pid for $mode" -Arguments @("shell", "pidof", $PackageName) -AllowFailure
            $targetPid = (($pidResult.output -split "\s+") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
            if ([string]::IsNullOrWhiteSpace($targetPid)) {
                throw "Native renderer process id was not available for $mode."
            }
            $rawLogcat = (Invoke-AdbCommand -Name "dump pid-scoped logcat for $mode" -Arguments @("logcat", "-d", "-v", "time", "--pid", $targetPid)).output
            Set-Content -Encoding UTF8 -Path $rawLogcatPath -Value $rawLogcat
            $filtered = @($rawLogcat -split "`r?`n" | Where-Object { $_ -match "RUSTY_QUEST_NATIVE_RENDERER" })
            [System.IO.File]::WriteAllLines($filteredLogcatPath, [string[]]$filtered)

            Invoke-AdbCommand -Name "capture screenshot for $mode" -Arguments @("shell", "screencap", "-p", $remoteScreenshotPath) | Out-Null
            Invoke-AdbCommand -Name "pull screenshot for $mode" -Arguments @("pull", $remoteScreenshotPath, $screenshotPath) | Out-Null
            Invoke-AdbCommand -Name "remove remote screenshot for $mode" -Arguments @("shell", "rm", $remoteScreenshotPath) -AllowFailure | Out-Null

            $evidenceArgs = @(
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-File", (Join-Path $PSScriptRoot "Test-NativeRendererRuntimeEvidence.ps1"),
                "-LogcatPath", $filteredLogcatPath,
                "-ScreenshotPath", $screenshotPath,
                "-ScreenshotCropOutDir", $cropDir,
                "-SummaryOut", $evidenceSummaryPath,
                "-RequireScreenshot",
                "-RequireCameraProjection",
                "-RequireGuideGraph",
                "-RequirePrivateSlotNoPayload"
            )
            if (-not $AllowFlatScreenshot) {
                $evidenceArgs += "-RequireNonFlatScreenshot"
            }
            if (-not $AllowPerformanceBudgetMiss) {
                $evidenceArgs += "-RequirePerformanceBudget"
            }
            $result.runtime_evidence_output = Invoke-CheckedPowershell -Name "runtime evidence for $mode" -Arguments $evidenceArgs

            $timingLine = Get-LatestMarkerLine -Lines $filtered -Channel "timing-scorecard"
            $projectionLine = Get-LatestMarkerLine -Lines $filtered -Channel "projection-border-stretch"
            $guideVideoLine = Get-LatestMarkerLine -Lines $filtered -Channel "guide-video-composite"
            $gpuTimingLine = Get-LatestMarkerLine -Lines $filtered -Channel "gpu-timestamp-timing"
            foreach ($marker in (Get-ModeRuntimeMarkers -Mode $mode)) {
                if ($projectionLine -notmatch [regex]::Escape($marker)) {
                    throw "Mode $mode missing projection-border-stretch marker: $marker"
                }
            }

            $result.status = "passed"
            $result.screenshot_path = (Resolve-Path $screenshotPath).Path
            $result.filtered_logcat_path = (Resolve-Path $filteredLogcatPath).Path
            $result.runtime_evidence_summary_path = (Resolve-Path $evidenceSummaryPath).Path
            $result.timing_scorecard_line = $timingLine
            $result.projection_border_line = $projectionLine
            $result.guide_video_composite_line = $guideVideoLine
            $result.observed_openxr_fps = Get-MarkerNumberOrNull -Line $timingLine -Field "observedOpenXrFps"
            $result.stale_frames = Get-MarkerNumberOrNull -Line $timingLine -Field "stale_frames"
            $result.projection_composite_cpu_ms = Get-MarkerNumberOrNull -Line $timingLine -Field "projectionCompositeCpuMs"
            $result.guide_graph_cpu_ms = Get-MarkerNumberOrNull -Line $timingLine -Field "guideGraphCpuMs"
            $result.record_cpu_ms = Get-MarkerNumberOrNull -Line $timingLine -Field "recordCpuMs"
            $result.command_record_cpu_ms = Get-MarkerNumberOrNull -Line $timingLine -Field "commandRecordCpuMs"
            $result.projection_composite_gpu_ms = Get-MarkerNumberOrNull -Line $gpuTimingLine -Field "projectionCompositeGpuMs"
        }

        $result | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path (Join-Path $modeDir "mode-summary.json")
        $results += [pscustomobject]$result
    }

    if (-not $DryRunOnly -and $StopAfterSweep) {
        Invoke-AdbCommand -Name "force-stop after sweep" -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
    }

    $summary = [ordered]@{
        schema = "rusty.quest.native_renderer.video_border_blend_sweep.v1"
        status = "completed"
        generated_at = (Get-Date).ToUniversalTime().ToString("o")
        base_profile_path = (Resolve-Path $resolvedProfile).Path
        out_dir = (Resolve-Path $OutDir).Path
        dry_run_only = [bool]$DryRunOnly
        package_name = $PackageName
        activity = $Activity
        run_seconds = $RunSeconds
        modes = $Modes
        results = $results
        report_path = $reportPath
    }
    $summary | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -Path $summaryPath
    Write-SweepReport -Path $reportPath -Results $results
} catch {
    $summary = [ordered]@{
        schema = "rusty.quest.native_renderer.video_border_blend_sweep.v1"
        status = "failed"
        generated_at = (Get-Date).ToUniversalTime().ToString("o")
        out_dir = (Resolve-Path $OutDir).Path
        modes = $Modes
        results = $results
        error = $_.Exception.Message
    }
    $summary | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -Path $summaryPath
    throw
}

Write-Output "Native renderer video-border blend sweep summary: $summaryPath"
Write-Output "Native renderer video-border blend sweep report: $reportPath"
