[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [string]$RealFeatureLockPath = '',
    [string]$RealProfilePath = '',
    [string]$RealProviderManifestPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$javaRoot = Join-Path $repo 'apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$testRoot = Join-Path $repo 'apps/native-renderer-android/tests/java/io/github/mesmerprism/rustyquest/native_renderer'
$nativeRoot = Join-Path $repo 'apps/native-renderer-android/native/src'
$panelPath = Join-Path $repo 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/BreathCompositionPanelModule.java'
$sessionShellPath = Join-Path $repo 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/ExperimentSessionAndroidShell.java'
$packagedClosurePath = Join-Path $repo 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/ExperimentSessionPackagedClosure.java'
$buildPath = Join-Path $repo 'tools/Build-NativeRendererAndroid.ps1'
$breathFeaturePath = Join-Path $repo 'fixtures/native-app-features/ui/breath-composition-panel/ui.breath_composition_control_panel.feature.json'
$propertyManifestPath = Join-Path $repo 'fixtures/native-renderer/native-renderer-property-manifest.json'

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

$actionPath = Join-Path $nativeRoot 'same_apk_panel_action.rs'
$stimulusPath = Join-Path $nativeRoot 'openxr_stimulus_actions.rs'
$bridgePath = Join-Path $nativeRoot 'native_renderer_panel_bridge.rs'
$vulkanPath = Join-Path $nativeRoot 'xr_vulkan.rs'
$handoffPath = Join-Path $javaRoot 'PanelImmersiveHandoff.java'
$selfKioskApplicationPath = Join-Path $javaRoot 'NativeRendererSelfKioskApplication.java'

Assert-Contains $actionPath 'right-secondary-triple-press-experimenter-restart'
Assert-Contains $actionPath 'right-secondary-triple-press-experimenter-toggle'
Assert-Contains $actionPath 'experimenter_toggle_profile_enables_controls_without_terminal_b_route'
Assert-Contains $propertyManifestPath 'right-secondary-triple-press-experimenter-toggle'
Assert-Contains $propertyManifestPath 'right-secondary-triple-press-experimenter-restart'
Assert-Contains $actionPath 'SameApkDeveloperAction'
Assert-Contains $actionPath 'RIGHT_TRIGGER_PRESS_THRESHOLD: f32 = 0.82'
Assert-Contains $actionPath 'RIGHT_TRIGGER_RELEASE_THRESHOLD: f32 = 0.35'
Assert-Contains $stimulusPath '!same_apk_panel_action_settings.experimenter_profile_enabled'
Assert-Contains $stimulusPath 'event=right-primary-recenter status=triggered'
Assert-Contains $bridgePath 'OPEN_EXPERIMENTER_PANEL'
Assert-Contains $bridgePath 'OPEN_DEVELOPER_PANEL'
Assert-Contains $bridgePath 'native_renderer_panel_route'
Assert-Contains $vulkanPath 'open_experimenter_panel'
Assert-Contains $vulkanPath 'open_developer_panel'
Assert-Contains $panelPath 'ControlPanelActivity.closePanelAndReturnToImmersive(this)'
Assert-NotContains $panelPath 'rendererReturnPending'
Assert-NotContains $panelPath 'pollRendererReturnReadiness'
Assert-Contains $handoffPath 'STABLE_MS = 750L'
Assert-Contains $handoffPath 'state.frameCount > stableFrame'
Assert-Contains $handoffPath 'panelPaused'
Assert-Contains $handoffPath 'cancelForTerminalExit'
Assert-Contains $handoffPath 'relaunchAllowed=false'
Assert-Contains $handoffPath 'PanelImmersiveHandoffLifecyclePolicy'
Assert-Contains $handoffPath 'APPLICATION_LIFECYCLE.canLaunch(ownerToken, expectedGeneration)'
Assert-Contains $handoffPath 'new WeakReference<PanelImmersiveHandoff>(null)'
Assert-Contains $handoffPath 'cancelForReplacement'
Assert-Contains $handoffPath 'admitExplicitColdUserLaunch'
Assert-Contains $handoffPath 'APPLICATION_LIFECYCLE.admitExplicitLaunchEpoch(ownerToken)'
Assert-NotContains $handoffPath 'WeakHashMap'
Assert-Contains $sessionShellPath 'Executors.newSingleThreadScheduledExecutor'
Assert-Contains $sessionShellPath 'new WeakReference<UiSink>(sink)'
Assert-Contains $sessionShellPath 'readback.finalizedSessionGeneration != command.expectedGeneration'
Assert-Contains $sessionShellPath '!command.operationId.equals(readback.finalizedOperationId)'
Assert-Contains $buildPath 'nativeInitializeExperimentSessionRuntime(String appPrivateFilesRoot)'
Assert-Contains $buildPath 'installConditionAudioFromPackagedClosure(appPrivateFilesRoot)'
Assert-Contains $buildPath 'ExperimentSessionPackagedClosure.prepare('
Assert-Contains $buildPath 'context.getFilesDir().toPath()'
Assert-Contains $buildPath 'RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROFILE_SHA256'
Assert-Contains $buildPath 'RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROVIDER_MANIFEST_SHA256'
Assert-Contains $buildPath 'RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_INVENTORY_SHA256'
Assert-NotContains $buildPath 'entries.length() != 2'
Assert-Contains $buildPath 'ConditionAudioAndroidMediaBackendFactory(assets)'
Assert-Contains $buildPath 'ConditionAudioContract.Command.prepare('
Assert-Contains $buildPath 'pendingAudioCondition'
Assert-Contains $buildPath 'sessionGeneration == pendingAudioGeneration'
Assert-Contains $buildPath 'conditionId.equals(pendingAudioCondition)'
Assert-Contains $buildPath 'ConditionAudioContract.Command.start('
Assert-Contains $buildPath 'ConditionAudioContract.StopReason.SAVE_AND_EXIT'
Assert-Contains $buildPath 'ConditionAudioContract.StopReason.RESTART_TO_EXPERIMENTER'
Assert-Contains $buildPath 'NativeRendererExperimentLaunchAuthority.consume('
Assert-Contains $buildPath 'armFromExplicitColdLaunch('
Assert-Contains $buildPath 'selected_android_activities ='
Assert-Contains $buildPath '@($appBuildLockObject.android_manifest.activities | ForEach-Object { [string]$_ })'
Assert-NotContains $buildPath 'allowExactSystemPrompt('
Assert-NotContains $buildPath 'Settings.ACTION_ACCESSIBILITY_SETTINGS'
Assert-Contains $buildPath 'NativeRendererSelfKioskApplication.beginSystemPrompt(activity)'
Assert-Contains $buildPath 'Settings.ACTION_MANAGE_OVERLAY_PERMISSION'
Assert-Contains $buildPath 'NativeRendererSelfKioskApplication.userLeaveHint(this)'
Assert-Contains $buildPath 'activity.finishAndRemoveTask()'
Assert-Contains $buildPath 'status=terminal-app-task-finish-dispatched'
Assert-Contains $buildPath 'NativeRendererSelfKioskApplication.finishOwnedActivitiesForTerminalExit(activity)'
Assert-NotContains $buildPath 'if (task.getTaskInfo().id == activity.getTaskId()) continue;'
Assert-Contains $selfKioskApplicationPath 'finishOwnedActivitiesForTerminalExit(Activity terminalActivity)'
Assert-Contains $selfKioskApplicationPath 'candidate.finishAndRemoveTask()'
Assert-NotContains $buildPath 'android.os.Process.killProcess'
Assert-Contains $breathFeaturePath 'ExperimentSessionAndroidShell.java'
Assert-Contains $breathFeaturePath 'ExperimentSessionPackagedClosure.java'
Assert-Contains $breathFeaturePath 'condition-audio-runtime'
Assert-Contains $breathFeaturePath 'ConditionAudioAndroidMediaBackendFactory.java'
Assert-Contains $packagedClosurePath 'string(profile, "schema_id")'
Assert-NotContains $packagedClosurePath 'string(profile, "schema")'
Assert-Contains $packagedClosurePath 'FileChannel.open(target,'
Assert-Contains $packagedClosurePath 'StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE'
Assert-NotContains $packagedClosurePath 'Files.createLink('

$javac = $null
$java = $null
$javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME')
if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
    $candidateJavac = Join-Path $javaHome 'bin/javac.exe'
    $candidateJava = Join-Path $javaHome 'bin/java.exe'
    if ((Test-Path -LiteralPath $candidateJavac) -and (Test-Path -LiteralPath $candidateJava)) {
        $javac = $candidateJavac
        $java = $candidateJava
    }
}
if ($null -eq $javac) {
    $javacCommand = Get-Command javac -ErrorAction SilentlyContinue
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($null -ne $javacCommand -and $null -ne $javaCommand) {
        $javac = $javacCommand.Source
        $java = $javaCommand.Source
    }
}
if ($null -eq $javac) {
    throw 'A JDK with javac and java is required.'
}

$androidSdk = @(
    [Environment]::GetEnvironmentVariable('ANDROID_HOME'),
    [Environment]::GetEnvironmentVariable('ANDROID_SDK_ROOT')
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_) } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($androidSdk)) {
    throw 'ANDROID_HOME or ANDROID_SDK_ROOT is required to compile the real handoff adapter.'
}
$androidJar = Get-ChildItem -LiteralPath (Join-Path $androidSdk 'platforms') -Filter android.jar -Recurse |
    Sort-Object { [int]([regex]::Match($_.Directory.Name, '\d+').Value) } -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if ([string]::IsNullOrWhiteSpace($androidJar)) {
    throw "No Android platform android.jar found under $androidSdk"
}

$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testOutput = Join-Path $tempBase ("rq-native-renderer-experiment-shell-{0}" -f [Guid]::NewGuid().ToString('N'))
$resolvedOutput = [IO.Path]::GetFullPath($testOutput)
if (-not $resolvedOutput.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing temporary output outside $tempBase"
}
[IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null
try {
    $sources = @(
        (Join-Path $javaRoot 'NativeRendererForegroundGuardPolicy.java'),
        (Join-Path $javaRoot 'NativeRendererWriterAcknowledgedExitPolicy.java'),
        $sessionShellPath,
        $packagedClosurePath,
        (Join-Path $repo 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/ExperimentSessionPanelCoordinator.java'),
        (Join-Path $repo 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/ExperimentSessionPanelState.java'),
        (Join-Path $javaRoot 'PanelImmersiveHandoffLifecyclePolicy.java'),
        (Join-Path $javaRoot 'PanelImmersiveHandoff.java'),
        (Join-Path $testRoot 'NativeRendererExperimentShellPolicyTest.java'),
        (Join-Path $testRoot 'ExperimentSessionAndroidShellTest.java'),
        (Join-Path $testRoot 'ExperimentSessionPackagedClosureTest.java')
    )
    & $javac '--release' '8' '-encoding' 'UTF-8' '-cp' $androidJar '-d' $resolvedOutput @sources
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed with exit code $LASTEXITCODE"
    }
    & $java '-cp' $resolvedOutput 'io.github.mesmerprism.rustyquest.native_renderer.NativeRendererExperimentShellPolicyTest'
    if ($LASTEXITCODE -ne 0) {
        throw "Java policy tests failed with exit code $LASTEXITCODE"
    }
    & $java '-cp' $resolvedOutput 'io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionAndroidShellTest'
    if ($LASTEXITCODE -ne 0) {
        throw "Experiment session Android shell tests failed with exit code $LASTEXITCODE"
    }
    & $java '-cp' $resolvedOutput 'io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionPackagedClosureTest'
    if ($LASTEXITCODE -ne 0) {
        throw "Experiment session packaged-closure tests failed with exit code $LASTEXITCODE"
    }
    $realInputs = @($RealFeatureLockPath, $RealProfilePath, $RealProviderManifestPath)
    $realInputCount = @($realInputs | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
    if ($realInputCount -ne 0) {
        if ($realInputCount -ne 3) {
            throw 'RealFeatureLockPath, RealProfilePath, and RealProviderManifestPath must be supplied together.'
        }
        $realLock = (Resolve-Path -LiteralPath $RealFeatureLockPath).Path
        $realProfile = (Resolve-Path -LiteralPath $RealProfilePath).Path
        $realManifest = (Resolve-Path -LiteralPath $RealProviderManifestPath).Path
        $manifest = Get-Content -Raw -LiteralPath $realManifest | ConvertFrom-Json
        $request = Get-Content -Raw -LiteralPath (Join-Path (Split-Path -Parent $realManifest) 'request.json') | ConvertFrom-Json
        $byAsset = @{}; foreach ($asset in @($manifest.assets)) { $byAsset[[string]$asset.asset_id] = $asset }
        $audioA = (Resolve-Path -LiteralPath (Join-Path (Split-Path -Parent $realManifest) ([string]$byAsset['condition-audio-a'].source_relative_path))).Path
        $audioB = (Resolve-Path -LiteralPath (Join-Path (Split-Path -Parent $realManifest) ([string]$byAsset['condition-audio-b'].source_relative_path))).Path
        & $java '-cp' $resolvedOutput 'io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionPackagedClosureTest' `
            '--real-closure' $realLock $realProfile ([string]$request.provider_manifest_sha256) `
            ([string]$request.inventory_sha256) $audioA $audioB
        if ($LASTEXITCODE -ne 0) {
            throw "Real experiment-session packaged-closure integration failed with exit code $LASTEXITCODE"
        }
    }
} finally {
    if (Test-Path -LiteralPath $resolvedOutput) {
        Remove-Item -LiteralPath $resolvedOutput -Recurse -Force
    }
}

Write-Host 'Test-NativeRendererExperimentShellPolicy PASS'
