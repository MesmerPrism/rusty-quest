[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$javaRoot = Join-Path $repo 'apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer'
$testRoot = Join-Path $repo 'apps\native-renderer-android\tests\java\io\github\mesmerprism\rustyquest\native_renderer'
$featurePath = Join-Path $repo 'fixtures\native-app-features\ui\same-apk-soft-kiosk\ui.same_apk_soft_kiosk.feature.json'
$resolver = Join-Path $repo 'tools\Resolve-NativeAppBuild.ps1'
$templateSpec = Join-Path $repo 'fixtures\native-app-builds\native-stimulus-volume-panel.app.json'

function Assert-Contains {
    param([string]$Path, [string]$Literal)
    $body = [IO.File]::ReadAllText($Path)
    if (-not $body.Contains($Literal, [StringComparison]::Ordinal)) {
        throw "Missing required literal '$Literal' in $Path"
    }
}

function Assert-NotContains {
    param([string]$Path, [string]$Literal)
    $body = [IO.File]::ReadAllText($Path)
    if ($body.Contains($Literal, [StringComparison]::Ordinal)) {
        throw "Forbidden literal '$Literal' remains in $Path"
    }
}

$policyPath = Join-Path $javaRoot 'NativeRendererForegroundGuardPolicy.java'
$homePolicyPath = Join-Path $javaRoot 'NativeRendererHomeEpisodePolicy.java'
$coordinatorPath = Join-Path $javaRoot 'NativeRendererSoftKioskCoordinator.java'
$recoveryTimerGatePath = Join-Path $javaRoot 'NativeRendererSoftKioskRecoveryTimerGate.java'
$servicePath = Join-Path $javaRoot 'NativeRendererSelfKioskService.java'
$applicationPath = Join-Path $javaRoot 'NativeRendererSelfKioskApplication.java'
$departurePath = Join-Path $javaRoot 'NativeRendererSelfKioskDeparturePolicy.java'
$launchAuthorityPath = Join-Path $javaRoot 'NativeRendererExperimentLaunchAuthority.java'
$launcherPolicyPath = Join-Path $javaRoot 'NativeRendererExperimentLauncherPolicy.java'
$handoffLifecyclePath = Join-Path $javaRoot 'PanelImmersiveHandoffLifecyclePolicy.java'
$handoffPath = Join-Path $javaRoot 'PanelImmersiveHandoff.java'
$nativeEntryPath = Join-Path $repo 'apps\native-renderer-android\native\src\lib.rs'
$panelBridgePath = Join-Path $repo 'apps\native-renderer-android\native\src\native_renderer_panel_bridge.rs'
$openXrPath = Join-Path $repo 'apps\native-renderer-android\native\src\xr_vulkan.rs'
$testPath = Join-Path $testRoot 'NativeRendererSoftKioskPolicyTest.java'
foreach ($path in @(
    $policyPath,
    $homePolicyPath,
    $coordinatorPath,
    $recoveryTimerGatePath,
    $servicePath,
    $applicationPath,
    $departurePath,
    $launchAuthorityPath,
    $launcherPolicyPath,
    $handoffLifecyclePath,
    $handoffPath,
    $nativeEntryPath,
    $panelBridgePath,
    $openXrPath,
    $testPath,
    $featurePath
)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Soft-kiosk source is missing: $path"
    }
}

$feature = Get-Content -Raw -LiteralPath $featurePath | ConvertFrom-Json
if ([string]$feature.schema -cne 'rusty.quest.native_app_feature.v1' -or
    [string]$feature.feature_id -cne 'ui.same_apk_soft_kiosk' -or
    @($feature.depends_on) -cnotcontains 'ui.same_apk_control_panel' -or
    @($feature.android_manifest.activities).Count -ne 0 -or
    @($feature.android_manifest.services) -cnotcontains 'NativeRendererSelfKioskService' -or
    @($feature.android_manifest.permissions).Count -ne 3 -or
    @($feature.android_manifest.permissions) -cnotcontains 'android.permission.SYSTEM_ALERT_WINDOW') {
    throw 'Same-APK soft-kiosk feature descriptor is not the closed opt-in surface.'
}

Assert-Contains $applicationPath 'registerActivityLifecycleCallbacks'
Assert-Contains $applicationPath 'beginSystemPrompt'
Assert-Contains $servicePath 'renderer_focus_state.json'
Assert-Contains $servicePath 'physical_home=false'
Assert-Contains $servicePath 'Settings.canDrawOverlays(this)'
Assert-Contains $servicePath 'status=terminal-save-exit-requested admitted=false saved=false'
Assert-Contains $servicePath 'pendingTerminal'
Assert-Contains $servicePath 'resumePendingTerminal(Context context)'
Assert-Contains $servicePath 'acknowledgeTerminalIntent(long generation, long departureId)'
Assert-Contains $applicationPath 'NativeRendererSelfKioskService.resumePendingTerminal(a)'
Assert-Contains (Join-Path $repo 'tools\Build-NativeRendererAndroid.ps1') 'NativeRendererSelfKioskService.acknowledgeTerminalIntent(guardGeneration, homeEpisode)'
Assert-Contains $servicePath 'handler.removeCallbacksAndMessages(null)'
Assert-Contains $coordinatorPath 'BEGIN_TERMINAL_EXIT'
Assert-Contains $coordinatorPath 'TERMINAL_ROUTE_SAVE_AND_EXIT'
Assert-Contains $launchAuthorityPath 'explicit-user-launch-v1'
Assert-Contains $launchAuthorityPath 'issueFromNativeActivity(Activity activity, boolean recreation)'
Assert-Contains $launchAuthorityPath 'Intent.CATEGORY_LAUNCHER'
Assert-Contains $launchAuthorityPath '"android.app.NativeActivity"'
Assert-Contains $launchAuthorityPath 'incoming.getData() != null || incoming.getSelector() != null'
Assert-Contains $nativeEntryPath 'admit_explicit_native_activity_launch(state)'
Assert-Contains $panelBridgePath 'pending_experiment_launch_epoch(app)'
Assert-Contains $panelBridgePath 'open_experimenter_panel_from_explicit_launch(app, epoch)'
Assert-Contains $panelBridgePath 'FLAG_ACTIVITY_REORDER_TO_FRONT | FLAG_ACTIVITY_SINGLE_TOP'
Assert-Contains $panelBridgePath 'after_current_session_frame_submitted('
Assert-NotContains $panelBridgePath 'fn control_panel_mode_is_breath_mapping()'
Assert-Contains $panelBridgePath 'packaged_control_panel_mode_is_breath_mapping_installed()'
Assert-Contains $openXrPath 'control_panel_command_poller.after_current_session_frame_submitted('
$openXrText = [IO.File]::ReadAllText($openXrPath)
$endFrameIndex = $openXrText.IndexOf('trace_startup_frame(frame_count, "after-xr-end-frame");', [StringComparison]::Ordinal)
$startupDispatchIndex = $openXrText.IndexOf(
    'control_panel_command_poller.after_current_session_frame_submitted(',
    [StringComparison]::Ordinal)
if ($endFrameIndex -lt 0 -or $startupDispatchIndex -le $endFrameIndex) {
    throw 'Experimenter cold-panel dispatch must remain after successful xrEndFrame return.'
}
Assert-Contains $coordinatorPath 'RECOVERY_EXHAUSTED'
Assert-Contains $coordinatorPath 'UNAVAILABLE_HOME_SURFACE'
Assert-Contains $servicePath 'coordinator.claimRecovery('
Assert-Contains $servicePath 'coordinator.observeOwnSurface('
foreach ($literal in @(
    'performGlobalAction(',
    'dispatchGesture(',
    'getRootInActiveWindow(',
    'killProcess(',
    'finishAndRemoveTask(',
    'Settings.Secure.put',
    'SharedPreferences',
    'FileOutputStream',
    'Thread.sleep('
)) {
    Assert-NotContains $servicePath $literal
}

if (Test-Path -LiteralPath (Join-Path $javaRoot 'NativeRendererExperimentLauncherActivity.java')) {
    throw 'The experiment profile must not package a competing launcher trampoline.'
}

$panelPath = Join-Path $repo 'apps\native-renderer-android\panel-modules\breath-composition\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\BreathCompositionPanelModule.java'
Assert-Contains $panelPath 'native_renderer_launch_provenance'
Assert-Contains $panelPath 'native_renderer_launch_epoch'
Assert-Contains $panelPath 'explicit-user-launch-v1'

$javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME')
$javac = if (-not [string]::IsNullOrWhiteSpace($javaHome)) { Join-Path $javaHome 'bin\javac.exe' } else { $null }
$java = if (-not [string]::IsNullOrWhiteSpace($javaHome)) { Join-Path $javaHome 'bin\java.exe' } else { $null }
if ($null -eq $javac -or -not (Test-Path -LiteralPath $javac)) {
    $javac = (Get-Command javac -ErrorAction Stop).Source
    $java = (Get-Command java -ErrorAction Stop).Source
}
$androidSdk = @(
    [Environment]::GetEnvironmentVariable('ANDROID_HOME'),
    [Environment]::GetEnvironmentVariable('ANDROID_SDK_ROOT')
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_) } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($androidSdk)) {
    throw 'ANDROID_HOME or ANDROID_SDK_ROOT is required.'
}
$androidJar = Get-ChildItem -LiteralPath (Join-Path $androidSdk 'platforms') -Filter android.jar -Recurse |
    Sort-Object { [int]([regex]::Match($_.Directory.Name, '\d+').Value) } -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if ([string]::IsNullOrWhiteSpace($androidJar)) {
    throw "No Android platform android.jar found under $androidSdk"
}

$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$tempRoot = Join-Path $tempBase ("rq-native-soft-kiosk-{0}" -f [Guid]::NewGuid().ToString('N'))
$resolvedTempRoot = [IO.Path]::GetFullPath($tempRoot)
if (-not $resolvedTempRoot.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing temporary output outside $tempBase"
}
[IO.Directory]::CreateDirectory($resolvedTempRoot) | Out-Null
try {
    $classes = Join-Path $resolvedTempRoot 'classes'
    [IO.Directory]::CreateDirectory($classes) | Out-Null
    $sources = @(
        $policyPath,
        $homePolicyPath,
        $coordinatorPath,
        $recoveryTimerGatePath,
        $servicePath,
        $applicationPath,
        $departurePath,
        $launchAuthorityPath,
        $launcherPolicyPath,
        $handoffLifecyclePath,
        $handoffPath,
        $testPath
    )
    & $javac '--release' '8' '-encoding' 'UTF-8' '-cp' $androidJar '-d' $classes @sources
    if ($LASTEXITCODE -ne 0) {
        throw "Soft-kiosk javac failed with exit code $LASTEXITCODE"
    }
    $runtimeClassPath = "$classes$([IO.Path]::PathSeparator)$androidJar"
    & $java '-cp' $runtimeClassPath 'io.github.mesmerprism.rustyquest.native_renderer.NativeRendererSoftKioskPolicyTest'
    if ($LASTEXITCODE -ne 0) {
        throw "Soft-kiosk Java traces failed with exit code $LASTEXITCODE"
    }

    $selectedSpecPath = Join-Path $resolvedTempRoot 'selected.app.json'
    $selectedSpec = Get-Content -Raw -LiteralPath $templateSpec | ConvertFrom-Json
    $selectedSpec.app_id = 'native_soft_kiosk_static_probe'
    $selectedSpec.package_name = 'io.github.mesmerprism.rustyquest.native_renderer.soft_kiosk_probe'
    $selectedSpec.requested_features = @($selectedSpec.requested_features) + 'ui.same_apk_soft_kiosk'
    $selectedSpec.declared_manifest.services = @('NativeRendererSelfKioskService')
    $selectedSpec.declared_manifest.permissions = @($selectedSpec.declared_manifest.permissions) + @($feature.android_manifest.permissions)
    [IO.File]::WriteAllText(
        $selectedSpecPath,
        ($selectedSpec | ConvertTo-Json -Depth 32),
        (New-Object Text.UTF8Encoding($false)))

    $selectedOutput = Join-Path $resolvedTempRoot 'selected-output'
    $selectedResultPath = Join-Path $selectedOutput 'result.json'
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $resolver `
        -AppSpec $selectedSpecPath `
        -OutputRoot $selectedOutput `
        -ResultJsonPath $selectedResultPath `
        -DryRun | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Selected soft-kiosk resolver probe failed with exit code $LASTEXITCODE"
    }
    $selectedResult = Get-Content -Raw -LiteralPath $selectedResultPath | ConvertFrom-Json
    $selectedLock = Get-Content -Raw -LiteralPath ([string]$selectedResult.feature_lock_path) | ConvertFrom-Json
    $selectedManifestPath = [string]$selectedLock.generated_outputs.android_manifest
    $selectedManifest = [IO.File]::ReadAllText($selectedManifestPath)
    if (@($selectedLock.selected_feature_ids) -cnotcontains 'ui.same_apk_soft_kiosk') {
        throw 'Selected resolver probe omitted the soft-kiosk feature.'
    }
    foreach ($literal in @(
        'android:name="io.github.mesmerprism.rustyquest.native_renderer.NativeRendererSelfKioskService"',
        'android:name="io.github.mesmerprism.rustyquest.native_renderer.NativeRendererSelfKioskApplication"',
        'android:foregroundServiceType="specialUse"',
        'android.permission.SYSTEM_ALERT_WINDOW',
        'android:name="android.app.NativeActivity"'
    )) {
        if (-not $selectedManifest.Contains($literal, [StringComparison]::Ordinal)) {
            throw "Selected generated manifest omitted '$literal'."
        }
    }
    if ([regex]::Matches($selectedManifest, 'android.intent.category.LAUNCHER').Count -ne 1) {
        throw 'Selected experiment profile must expose exactly one LAUNCHER category.'
    }
    if ($selectedManifest.Contains('NativeRendererExperimentLauncherActivity', [StringComparison]::Ordinal)) {
        throw 'Selected experiment profile must keep NativeActivity as the sole launcher.'
    }
    $nativeActivityBlock = [regex]::Match(
        $selectedManifest,
        '(?s)<activity\s+[^>]*android:name="android\.app\.NativeActivity".*?</activity>').Value
    foreach ($literal in @(
        'android:screenOrientation="landscape"',
        'android.intent.action.MAIN',
        'com.oculus.intent.category.VR',
        'android.intent.category.LAUNCHER'
    )) {
        if (-not $nativeActivityBlock.Contains($literal, [StringComparison]::Ordinal)) {
            throw "NativeActivity launcher block omitted '$literal'."
        }
    }
    $panelActivityBlock = [regex]::Match(
        $selectedManifest,
        '(?s)<activity\s+[^>]*android:name="io\.github\.mesmerprism\.rustyquest\.native_renderer\.ControlPanelActivity".*?</activity>').Value
    foreach ($literal in @(
        'android:screenOrientation="landscape"',
        'android:defaultHeight="720dp"',
        'android:defaultWidth="960dp"',
        'com.oculus.intent.category.2D'
    )) {
        if (-not $panelActivityBlock.Contains($literal, [StringComparison]::Ordinal)) {
            throw "ControlPanelActivity landscape handoff block omitted '$literal'."
        }
    }
    if ($selectedManifest.Contains('Accessibility', [StringComparison]::Ordinal) -or
        $selectedManifest.Contains('BIND_ACCESSIBILITY_SERVICE', [StringComparison]::Ordinal)) {
        throw 'Self-watchdog selected manifest must not contain Accessibility authority.'
    }

    $baselineOutput = Join-Path $resolvedTempRoot 'baseline-output'
    $baselineResultPath = Join-Path $baselineOutput 'result.json'
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $resolver `
        -AppSpec $templateSpec `
        -OutputRoot $baselineOutput `
        -ResultJsonPath $baselineResultPath `
        -DryRun | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Unselected soft-kiosk resolver probe failed with exit code $LASTEXITCODE"
    }
    $baselineResult = Get-Content -Raw -LiteralPath $baselineResultPath | ConvertFrom-Json
    $baselineLock = Get-Content -Raw -LiteralPath ([string]$baselineResult.feature_lock_path) | ConvertFrom-Json
    $baselineManifest = [IO.File]::ReadAllText([string]$baselineLock.generated_outputs.android_manifest)
    if ($baselineManifest.Contains('NativeRendererSelfKiosk', [StringComparison]::Ordinal) -or
        [regex]::Matches($baselineManifest, 'android.intent.category.LAUNCHER').Count -ne 1) {
        throw 'Unselected apps must retain the NativeActivity launcher and omit the soft-kiosk service.'
    }
} finally {
    if (Test-Path -LiteralPath $resolvedTempRoot) {
        Remove-Item -LiteralPath $resolvedTempRoot -Recurse -Force
    }
}

Write-Host 'Test-NativeRendererSoftKioskStatic PASS'
