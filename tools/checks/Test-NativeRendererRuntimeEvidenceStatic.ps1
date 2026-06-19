param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = Resolve-Path $RepoRoot
$toolsRoot = Join-Path $repoRootPath "tools"
$fixturesRoot = Join-Path $repoRootPath "fixtures\native-renderer"

function Read-RequiredText {
    param(
        [string]$Path,
        [string]$Label
    )
    if (-not (Test-Path $Path)) {
        throw "Missing native renderer runtime-evidence static file ($Label): $Path"
    }
    return Get-Content -Raw -Path $Path
}

function Assert-ContainsTokens {
    param(
        [string]$Text,
        [string[]]$Tokens,
        [string]$Label
    )
    foreach ($token in $Tokens) {
        if ($Text -notmatch [regex]::Escape($token)) {
            throw "Native renderer runtime-evidence static check failed for ${Label}: missing token: $token"
        }
    }
}

function Assert-PowerShellParses {
    param(
        [string]$Path,
        [string]$Label
    )
    $parseTokens = $null
    $parseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$parseTokens, [ref]$parseErrors) | Out-Null
    if ($parseErrors.Count -gt 0) {
        throw "Native renderer runtime-evidence static check failed for ${Label}: $($parseErrors[0].Message)"
    }
}

$runtimeEvidenceToolPath = Join-Path $toolsRoot "Test-NativeRendererRuntimeEvidence.ps1"
$runtimeSmokeToolPath = Join-Path $toolsRoot "Invoke-NativeRendererReplaySmoke.ps1"
$permissionPregrantToolPath = Join-Path $toolsRoot "Grant-NativeRendererPermissions.ps1"

$runtimeEvidenceToolText = Read-RequiredText $runtimeEvidenceToolPath "runtime evidence checker"
$runtimeSmokeToolText = Read-RequiredText $runtimeSmokeToolPath "runtime smoke wrapper"
$permissionPregrantToolText = Read-RequiredText $permissionPregrantToolPath "permission pregrant helper"
$runtimeEvidenceFixtureText = Read-RequiredText (Join-Path $fixturesRoot "native-renderer-replay-visual-proof.logcat.txt") "accepted replay visual logcat fixture"
$liveHandDiagnosticPendingFixtureText = Read-RequiredText (Join-Path $fixturesRoot "native-renderer-live-hand-visual-diagnostic-pending.logcat.txt") "live-hand diagnostic pending logcat fixture"

Assert-PowerShellParses $runtimeEvidenceToolPath "runtime evidence checker"
Assert-PowerShellParses $runtimeSmokeToolPath "runtime smoke wrapper"
Assert-PowerShellParses $permissionPregrantToolPath "permission pregrant helper"

Assert-ContainsTokens $runtimeEvidenceToolText @(
    'rusty.quest.native_renderer_runtime_evidence.v1',
    'Measure-ScreenshotContent',
    'Save-ScreenshotCropSet',
    'ConvertTo-ScreenshotUvRect',
    'Expand-ScreenshotTargetUvRectTexts',
    'Get-ScreenshotTargetUvRectTexts',
    'leftTargetScreenUvRect',
    'rightTargetScreenUvRect',
    'RequireNonFlatScreenshot',
    'RequireTargetNonFlatScreenshot',
    'RequireHandMeshVisualScreenshot',
    'RequireSdfVisualScreenshot',
    'ScreenshotTargetUvRects',
    'MinimumNonFlatScreenshotTargetRects',
    'MinimumNonFlatHandMeshVisualRects',
    'MinimumNonFlatSdfVisualRects',
    'MinimumOverlayColorFamilyPixels',
    'MinimumHandMeshVisualOverlayColorRatio',
    'MinimumSdfVisualOverlayColorRatio',
    'MinimumScreenshotUniqueColors',
    'MinimumScreenshotLumaRange',
    'screenshot_sampled_unique_colors',
    'screenshot_sampled_chroma_pixels',
    'screenshot_sampled_chroma_ratio',
    'overlay_color_family_pixels',
    'overlay_color_family_ratio',
    'screenshot_luma_range',
    'screenshot_target_rects',
    'screenshot_crop_out_dir',
    'screenshot_target_crop_artifacts',
    'screenshot_hand_mesh_visual_crop_artifacts',
    'screenshot_sdf_visual_crop_artifacts',
    'screenshot_target_non_flat_rects',
    'screenshot_hand_mesh_visual_rects',
    'screenshot_sdf_visual_rects',
    'screenshot_hand_mesh_visual_non_flat_rects',
    'screenshot_sdf_visual_non_flat_rects',
    'screenshot_hand_mesh_visual_overlay_color_rects',
    'screenshot_sdf_visual_overlay_color_rects',
    'RequireLiveVisualDiagnosticCaveat',
    'live_visual_diagnostic_caveat_checked',
    'compactHandInputSourceMode=live-meta-openxr-hand-tracking',
    'handMeshCompactInputSource=live-meta-openxr-hand-tracking',
    'sdfCompactInputSource=live-meta-openxr-hand-tracking',
    'liveHandMeshVisualAcceptance=pending-repeat-headset-visual-proof',
    'liveSdfVisualAcceptance=pending-repeat-headset-visual-proof',
    'RequirePerformanceBudget',
    'MinimumObservedOpenXrFps',
    'MaximumStaleFrames',
    'MaximumCameraAcquireImportCpuMs',
    'MaximumGuideGraphCpuMs',
    'MaximumHandSdfPrepareCpuMs',
    'MaximumHandMeshVisualCpuMs',
    'MaximumProjectionCompositeCpuMs',
    'performance_budget_cpu_metrics',
    'performance_budget_gpu_metrics',
    'RequireReplayVisualProof',
    'RequireGuideGraph',
    'RequireSdfVisual',
    'RequireGpuTimestampReady',
    'RequireStimulusGpuTimestampStages',
    'stimulusVolumeComputeGpuMs',
    'stimulusVolumeProjectionGpuMs',
    'RequirePrivateSlotNoPayload',
    'animatedHandMeshVisualVisible=true',
    'gpuTimestampQueryReady=true',
    'privateLayerPayloadLinked=false'
) "runtime evidence checker"

Assert-ContainsTokens $runtimeEvidenceFixtureText @(
    'leftTargetScreenUvRect=0.171875,0.218750,0.750000,0.656250',
    'rightTargetScreenUvRect=0.078125,0.218750,0.750000,0.671875',
    'leftHandMeshVisualScreenUvRect=',
    'rightHandMeshVisualScreenUvRect=',
    'leftSdfVisualScreenUvRect=',
    'rightSdfVisualScreenUvRect=',
    'targetCoordinateSpace=display-eye-screen-uv',
    'targetFootprintMetadataSource=native-direct-camera-target-screen-uv-runtime'
) "accepted replay visual logcat fixture"

Assert-ContainsTokens $liveHandDiagnosticPendingFixtureText @(
    'compactHandInputSourceMode=live-meta-openxr-hand-tracking',
    'compactHandInputSelectsLiveFrame=true',
    'compactHandInputAllowsRecordedFallback=false',
    'handMeshCompactInputSource=live-meta-openxr-hand-tracking',
    'sdfCompactInputSource=live-meta-openxr-hand-tracking',
    'liveHandMeshVisualAcceptance=pending-repeat-headset-visual-proof',
    'liveSdfVisualAcceptance=pending-repeat-headset-visual-proof'
) "live-hand diagnostic pending fixture"

Assert-ContainsTokens "$runtimeSmokeToolText`n$permissionPregrantToolText" @(
    'rusty.quest.native_renderer_replay_smoke_run.v1',
    'Apply-RuntimeProfile.ps1',
    'Test-NativeRendererRuntimeEvidence.ps1',
    'quest-native-renderer-replay-visual-proof.profile.json',
    'quest-native-renderer-live-hand-visual-diagnostic.profile.json',
    'EvidenceMode',
    'ReplayVisualProof',
    'LiveVisualDiagnosticCaveat',
    'previousErrorActionPreference',
    'NativeCommandError',
    'RUSTY_QUEST_ADB_SERVER_PORT',
    'AdbServerPort',
    'Resolve-AdbServerPortArgument',
    'device-scoped-adb',
    'adb_serial_required',
    'adb_server_port',
    'clear_logcat_requested',
    'pid-scoped-device-logcat',
    'pidof',
    '--pid',
    'refusing unscoped logcat evidence',
    'must pass adb -s <serial>',
    'replay_visual_proof_required',
    'live_visual_diagnostic_caveat_required',
    'rusty-quest-native-renderer.apk',
    'RUSTY_QUEST_NATIVE_RENDERER',
    'android.permission.CAMERA',
    'com.oculus.permission.HAND_TRACKING',
    'horizonos.permission.HEADSET_CAMERA',
    'horizonos.permission.SPATIAL_CAMERA',
    'horizonos.permission.USE_SCENE',
    'pm',
    'grant',
    'logcat',
    'screencap',
    '/data/local/tmp/rusty_quest_native_renderer_replay_smoke.png',
    'filtered-native-renderer-logcat.txt',
    'runtime-evidence-summary.json',
    'screenshot-crops',
    'run-summary.json',
    'AllowFlatScreenshot',
    'AllowPerformanceBudgetMiss',
    'RequireNonFlatScreenshot',
    'RequireTargetNonFlatScreenshot',
    'RequireHandMeshVisualScreenshot',
    'RequireSdfVisualScreenshot',
    'ScreenshotTargetUvRects',
    'ScreenshotCropOutDir',
    '-join "|"',
    'RequireReplayVisualProof',
    'RequireLiveVisualDiagnosticCaveat',
    'RequireGuideGraph',
    'RequireSdfVisual',
    'RequirePrivateSlotNoPayload',
    'RequireGpuTimestampReady',
    'RequirePerformanceBudget',
    'StopAfterRun'
) "runtime smoke wrapper"

Assert-ContainsTokens "$runtimeSmokeToolPath`n$permissionPregrantToolPath`n$runtimeSmokeToolText`n$permissionPregrantToolText" @(
    'Grant-NativeRendererPermissions.ps1',
    'permission-pregrant.json',
    'android.permission.CAMERA',
    'com.oculus.permission.HAND_TRACKING',
    'horizonos.permission.HEADSET_CAMERA',
    'horizonos.permission.SPATIAL_CAMERA',
    'horizonos.permission.USE_SCENE',
    'org.khronos.openxr.permission.OPENXR',
    'org.khronos.openxr.permission.OPENXR_SYSTEM',
    'pm',
    'grant',
    '-s'
) "permission pregrant route"

if ($runtimeSmokeToolText -notmatch [regex]::Escape('-Execute') -or $runtimeSmokeToolText -notmatch [regex]::Escape('-SummaryOut')) {
    throw "Native renderer runtime-evidence static check failed for runtime smoke wrapper: missing execute/summary wiring"
}

Write-Host "Rusty Quest native renderer runtime-evidence static validation passed"
