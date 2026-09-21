[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$panelRoot = Join-Path $repo 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$polarRoot = Join-Path $repo 'apps/native-renderer-android/panel-modules/polar/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$testRoot = Join-Path $repo 'apps/native-renderer-android/tests/java/io/github/mesmerprism/rustyquest/native_renderer'
$module = Join-Path $panelRoot 'BreathCompositionPanelModule.java'
$viewPolicy = Join-Path $panelRoot 'ExperimentSessionPanelViewPolicy.java'
$coordinator = Join-Path $panelRoot 'ExperimentSessionPanelCoordinator.java'
$polar = Join-Path $polarRoot 'PolarSensorPanel.java'
$polarRuntime = Join-Path $polarRoot 'PolarSensorRuntime.java'
$mainRoot = Join-Path $repo 'apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$lslRoot = Join-Path $repo 'apps/native-renderer-android/panel-modules/lsl/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$privateRoot = Join-Path $repo 'apps/native-renderer-android/panel-modules/private-particle/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$stimulusRoot = Join-Path $repo 'apps/native-renderer-android/panel-modules/stimulus-volume/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'

function Assert-Contains([string]$Path, [string]$Literal) {
    $text = [IO.File]::ReadAllText($Path)
    if (-not $text.Contains($Literal, [StringComparison]::Ordinal)) {
        throw "Missing required literal '$Literal' in $Path"
    }
}
function Assert-NotContains([string]$Path, [string]$Literal) {
    $text = [IO.File]::ReadAllText($Path)
    if ($text.Contains($Literal, [StringComparison]::Ordinal)) {
        throw "Forbidden literal '$Literal' in $Path"
    }
}

Assert-Contains $module 'Arm Condition 1'
Assert-Contains $module 'Arm Condition 2'
Assert-Contains $module 'Open Polar connection'
Assert-Contains $module 'Open developer settings'
Assert-Contains $module 'Read canonical effective snapshot'
Assert-Contains $module 'replay_supported'
Assert-Contains $module 'ephemeral=true'
Assert-NotContains $module 'LslPanelConfigStore.save'
Assert-NotContains $module 'LslPanelConfigStore.read'
Assert-Contains $coordinator 'restartToExperimenter'
Assert-Contains $module 'PROVENANCE_NATIVE_B_RESTART'
Assert-Contains $module 'EXTRA_PANEL_SESSION_GENERATION'
Assert-Contains $module 'EXTRA_PANEL_OPERATION_ID'
Assert-Contains $module 'PROVENANCE_EXPLICIT_USER_LAUNCH'
Assert-Contains $module 'EXTRA_LAUNCH_EPOCH'
Assert-Contains $coordinator 'admitTrustedColdLaunch'
Assert-Contains $coordinator 'awaitNativeRestartRoute'
Assert-Contains $module 'by_condition'
Assert-Contains $module 'condition-a'
Assert-Contains $module 'condition-b'
Assert-Contains $module 'storage_status'
Assert-Contains $module 'recovery_status'
Assert-Contains $module 'kiosk_enforcement'
Assert-Contains $module 'validNonNegativeLong'
Assert-Contains $viewPolicy 'Saving…'
Assert-Contains $viewPolicy 'Unclassified/recovered:'
Assert-Contains $viewPolicy 'invalid native readback'
Assert-Contains $viewPolicy 'Background return ready'
Assert-Contains $viewPolicy 'Recording ready'
Assert-Contains $module 'ControlPanelActivity.softKioskUiState(this)'
Assert-NotContains $module 'ControlPanelActivity.softKioskEffectiveStatus(this)'
Assert-Contains $module 'ControlPanelActivity.applyExperimentSessionCommand'
Assert-Contains $polar 'Side-effect-free experimenter projection'
Assert-Contains $polar 'automatic_connection_state'
Assert-Contains $polar 'ensureAutoConnection'
Assert-Contains $polar 'Multiple eligible sensors were found'
Assert-Contains $polar 'connectAdmittedCandidate(admittedCandidate)'
Assert-NotContains $polar 'connectSelected(true)'
Assert-Contains $polar 'automatic_connection_updated_at_unix_ms'
Assert-Contains $polar 'automatic_connection_evidence_generation'
Assert-Contains $polar 'markAutomaticConnectionEvidenceFromLiveCallback'
Assert-Contains $polar 'preferredScanName('
Assert-Contains $polar 'getServiceSolicitationUuids()'
Assert-Contains $polar 'scan_raw_callback_count'
Assert-Contains $polar 'scan_rejected_advertisement_count'
Assert-Contains $polar 'rawDeviceIdentifierLogged=false'
Assert-Contains $polar 'Open location settings'
Assert-Contains $polar 'Settings.ACTION_LOCATION_SOURCE_SETTINGS'
Assert-Contains $polar 'reason=location-services-disabled'
Assert-Contains $polar '"not-started"'
Assert-Contains $polar '"location-services-unavailable"'
Assert-Contains $polar 'BluetoothGatt admittedGatt'
Assert-Contains $polar 'long admittedGeneration'
Assert-Contains $polar 'connectionIdentityLock'
Assert-Contains $polarRuntime 'mayPublishLiveEvidence'
Assert-Contains $polar 'automatic_connection_deadline_elapsed_ms'
Assert-Contains $polar 'statusWriter.execute'
Assert-Contains $polar 'captureStatusSnapshotOnOwner'
Assert-Contains $polar 'StatusPersistenceSnapshot'
Assert-Contains $polar 'Collections.unmodifiableMap'
Assert-Contains $polar 'persistStatusOnBackgroundThread'
Assert-Contains $polarRuntime 'operatorReceiptWriter.execute'
Assert-Contains $polarRuntime 'persistReceiptOnBackgroundThread'
Assert-Contains $polarRuntime 'receipt_effect=pending'
Assert-Contains $polarRuntime 'requireBackgroundThread'
Assert-Contains $module 'boolean currentPlatformPrerequisite'

$polarText = [IO.File]::ReadAllText($polar)
$workerStart = $polarText.IndexOf('private void persistStatusOnBackgroundThread', [StringComparison]::Ordinal)
$workerEnd = $polarText.IndexOf('private static boolean pmdDataReceiving', $workerStart, [StringComparison]::Ordinal)
if ($workerStart -lt 0 -or $workerEnd -le $workerStart) {
    throw 'Could not isolate Polar background status persistence worker.'
}
$workerBody = $polarText.Substring($workerStart, $workerEnd - $workerStart)
foreach ($forbiddenWorkerToken in @('deviceSpinner', 'devices.', 'selectedDeviceInstanceId()')) {
    if ($workerBody.Contains($forbiddenWorkerToken, [StringComparison]::Ordinal)) {
        throw "Background persistence worker touches live UI/model token '$forbiddenWorkerToken'."
    }
}

$javacCommand = Get-Command javac -ErrorAction SilentlyContinue
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($null -eq $javacCommand -or $null -eq $javaCommand) {
    throw 'A JDK with javac and java is required.'
}
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$output = Join-Path $tempBase ("rq-experiment-panel-{0}" -f [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($output) | Out-Null
try {
    $sources = @(
        (Join-Path $panelRoot 'ExperimentSessionPanelState.java'),
        (Join-Path $panelRoot 'ExperimentSessionPanelCoordinator.java'),
        (Join-Path $panelRoot 'ExperimentSessionPanelViewPolicy.java'),
        (Join-Path $testRoot 'ExperimentSessionPanelCoordinatorTest.java'),
        (Join-Path $testRoot 'ExperimentSessionPanelViewPolicyTest.java')
    )
    & $javacCommand.Source '--release' '8' '-encoding' 'UTF-8' '-d' $output @sources
    if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
    & $javaCommand.Source '-cp' $output 'io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionPanelCoordinatorTest'
    if ($LASTEXITCODE -ne 0) { throw "Java test failed: $LASTEXITCODE" }
    & $javaCommand.Source '-cp' $output 'io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionPanelViewPolicyTest'
    if ($LASTEXITCODE -ne 0) { throw "Panel presentation Java test failed: $LASTEXITCODE" }

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
    $stubDir = Join-Path $output 'stub/io/github/mesmerprism/rustyquest/native_renderer'
    [IO.Directory]::CreateDirectory($stubDir) | Out-Null
    $stub = Join-Path $stubDir 'ControlPanelActivity.java'
    [IO.File]::WriteAllText($stub, @'
package io.github.mesmerprism.rustyquest.native_renderer;
final class ControlPanelActivity extends BreathCompositionPanelModule {
    static void closePanelAndReturnToImmersive(BreathCompositionPanelModule activity) {}
    static String initializeExperimentSessionRuntimeFromOwner(String root) { return "{}"; }
    static boolean consumeExplicitColdUserLaunch(android.app.Activity activity, boolean recreation, android.content.Intent intent) { return false; }
    static boolean admitExplicitColdExperimentShell(android.app.Activity activity, long epoch, String route) { return false; }
    static boolean beginPanelTransition(android.app.Activity activity, String route, long generation) { return true; }
    static String softKioskEffectiveStatus(android.app.Activity activity) { return "unavailable"; }
    static String softKioskUiState(android.app.Activity activity) { return "attention"; }
    static boolean openSelfKioskOverlaySettings(android.app.Activity activity) { return false; }
    static boolean admitTerminalSaveAndExit(android.app.Activity activity, android.content.Intent intent) { return false; }
    static void finishTerminalSaveAndExit(android.app.Activity activity, boolean saved, long generation, String operationId, long revision) {}
    static String conditionAudioReadiness(String condition) { return "audio-track-not-ready"; }
    static boolean startConditionAudio(long generation, String operationId, String condition) { return false; }
    static boolean requestConditionAudioStop(long generation, String operationId) { return true; }
    static boolean requestConditionAudioRestartStop(long generation, String operationId) { return true; }
    static String conditionAudioShutdownStatus() { return "complete"; }
    static boolean closeExperimentResourcesFromOwner() { return true; }
    static String nativeApplyBreathCompositionCommand(String value) { return "{}"; }
    static String applyExperimentSessionCommand(String value) { return "{}"; }
    static String nativeApplyLslTransportCommand(String value) { return "{}"; }
    static String nativeReadLslTransportStatus() { return "{}"; }
    static String nativeReadBreathCompositionStatus() { return "{}"; }
    static String applyLslTransportCommandFromOwner(String value) { return "{}"; }
    static String nativeSubmitLivePrivateParticleDynamics(String value) { return "{}"; }
}
'@, (New-Object Text.UTF8Encoding($false)))
    $androidSources = @(
        (Join-Path $mainRoot 'PanelModule.java'),
        (Join-Path $mainRoot 'DisplayCompositeProjectionService.java'),
        (Join-Path $mainRoot 'NativeAppSettingsReader.java'),
        (Join-Path $mainRoot 'NativeRendererForegroundGuardPolicy.java'),
        (Join-Path $mainRoot 'NativeRendererSoftKioskCoordinator.java'),
        (Join-Path $mainRoot 'NativeRendererWriterAcknowledgedExitPolicy.java'),
        (Join-Path $mainRoot 'NativeRendererSelfKioskApplication.java'),
        (Join-Path $mainRoot 'NativeRendererSelfKioskService.java'),
        (Join-Path $mainRoot 'NativeRendererSelfKioskDeparturePolicy.java'),
        (Join-Path $mainRoot 'NativeRendererExperimentLaunchAuthority.java'),
        (Join-Path $mainRoot 'NativeRendererExperimentLauncherPolicy.java'),
        (Join-Path $mainRoot 'PanelImmersiveHandoff.java'),
        (Join-Path $mainRoot 'PanelImmersiveHandoffLifecyclePolicy.java'),
        (Join-Path $panelRoot 'BreathCompositionCommandReceiver.java'),
        (Join-Path $panelRoot 'BreathCompositionPanelModule.java'),
        (Join-Path $panelRoot 'ExperimentSessionAndroidShell.java'),
        (Join-Path $panelRoot 'ExperimentSessionPanelState.java'),
        (Join-Path $panelRoot 'ExperimentSessionPanelCoordinator.java'),
        (Join-Path $panelRoot 'ExperimentSessionPanelViewPolicy.java'),
        (Join-Path $polarRoot 'PolarBleRuntimeSupport.java'),
        (Join-Path $polarRoot 'PolarSensorCommandReceiver.java'),
        (Join-Path $polarRoot 'PolarSensorPanel.java'),
        (Join-Path $polarRoot 'PolarSensorRuntime.java'),
        (Join-Path $lslRoot 'LslMulticastLockManager.java'),
        (Join-Path $lslRoot 'LslPanelCommandReceiver.java'),
        (Join-Path $lslRoot 'LslPanelConfigStore.java'),
        (Join-Path $privateRoot 'PrivateParticlePanelController.java'),
        (Join-Path $testRoot 'PolarAutoConnectionPolicyTest.java'),
        $stub
    )
    & $javacCommand.Source '--release' '8' '-encoding' 'UTF-8' '-cp' $androidJar '-d' $output @androidSources
    if ($LASTEXITCODE -ne 0) { throw "Android panel javac failed: $LASTEXITCODE" }
    & $javaCommand.Source '-cp' ("$output$([IO.Path]::PathSeparator)$androidJar") 'io.github.mesmerprism.rustyquest.native_renderer.PolarAutoConnectionPolicyTest'
    if ($LASTEXITCODE -ne 0) { throw "Polar auto-connection policy test failed: $LASTEXITCODE" }
    # Compile the real generated shell as well as the module stub; no APK/build invocation.
    $buildText = [IO.File]::ReadAllText((Join-Path $repo 'tools/Build-NativeRendererAndroid.ps1'))
    $shellMatch = [regex]::Match($buildText, '(?s)\$generatedControlPanelActivitySource = @"\r?\n(.*?)\r?\n"@')
    $nativeMatch = [regex]::Match($buildText, '(?s)elseif \(\$selectedPanelModuleId -ceq "breath-composition-controls"\) \{\s*@"\r?\n(.*?)\r?\n"@')
    if (-not $shellMatch.Success -or -not $nativeMatch.Success) { throw 'Generated shell source missing' }
    $generatedShell = $shellMatch.Groups[1].Value.Replace('$panelNativeMethods', $nativeMatch.Groups[1].Value)
    $generatedShell = $generatedShell.Replace('$selectedPanelEntrySimpleName', 'BreathCompositionPanelModule')
    $generatedShell = $generatedShell.Replace('$selectedPanelModuleId', 'breath-composition-controls')
    $generatedShell = $generatedShell.Replace('$experimentSessionTerminalAudioStop', '        stopCurrentConditionAudioForTerminal(true);')
    $generatedShell = $generatedShell.Replace('$experimentSessionOnCreate', '        ownerApplicationContext = getApplicationContext();')
    $generatedShell = $generatedShell.Replace('$polarRuntimeReopenFromExplicitLaunch', '            PolarSensorRuntime.reopenClosedFromExplicitLaunch();')
    $generatedShell = $generatedShell.Replace('    // EXPERIMENT_SESSION_SHELL_BEGIN' + [Environment]::NewLine, '')
    $generatedShell = $generatedShell.Replace('    // EXPERIMENT_SESSION_SHELL_END' + [Environment]::NewLine, '')
    foreach ($anchor in @('experimentSessionProfileSha256', 'experimentSessionProviderManifestSha256', 'experimentSessionInventorySha256')) {
        $generatedShell = $generatedShell.Replace('$' + $anchor, ('a' * 64))
    }
    [IO.File]::WriteAllText($stub, $generatedShell, (New-Object Text.UTF8Encoding($false)))
    $audioRoot = Join-Path $repo 'apps/native-renderer-android/panel-modules/condition-audio/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
    $androidSources += @(
        (Join-Path $mainRoot 'PanelModuleRegistry.java'),
        (Join-Path $mainRoot 'PanelImmersiveHandoff.java'),
        (Join-Path $mainRoot 'PanelImmersiveHandoffLifecyclePolicy.java'),
        (Join-Path $mainRoot 'NativeRendererExperimentLaunchAuthority.java'),
        (Join-Path $mainRoot 'NativeRendererExperimentLauncherPolicy.java'),
        (Join-Path $mainRoot 'NativeRendererSoftKioskAccessibilityService.java'),
        (Join-Path $mainRoot 'NativeRendererSelfKioskApplication.java'),
        (Join-Path $mainRoot 'NativeRendererSelfKioskService.java'),
        (Join-Path $mainRoot 'NativeRendererSelfKioskDeparturePolicy.java'),
        (Join-Path $mainRoot 'NativeRendererHomeEpisodePolicy.java'),
        (Join-Path $mainRoot 'NativeRendererSoftKioskRecoveryTimerGate.java'),
        (Join-Path $panelRoot 'ExperimentSessionPackagedClosure.java'),
        (Join-Path $audioRoot 'ConditionAudioRuntime.java'),
        (Join-Path $audioRoot 'ConditionAudioContract.java'),
        (Join-Path $audioRoot 'ConditionAudioAndroidMediaBackendFactory.java')
    )
    & $javacCommand.Source '--release' '8' '-encoding' 'UTF-8' '-cp' $androidJar '-d' $output @androidSources
    if ($LASTEXITCODE -ne 0) { throw "Generated Android shell javac failed: $LASTEXITCODE" }

    # A non-breath selected panel must compile from its own closure.  In
    # particular it must not need the breath-only experiment session, packaged
    # closure, condition-audio, or their JNI declarations merely because the
    # common shell is generated for it.
    $stimulusNativeMatch = [regex]::Match(
        $buildText,
        '(?s)if \(\$selectedPanelModuleId -ceq "stimulus-volume"\) \{\s*@"\r?\n(.*?)\r?\n"@')
    if (-not $stimulusNativeMatch.Success) { throw 'Stimulus generated JNI surface missing' }
    $unrelatedShell = $shellMatch.Groups[1].Value.Replace('$panelNativeMethods', $stimulusNativeMatch.Groups[1].Value)
    $unrelatedShell = $unrelatedShell.Replace('$selectedPanelEntrySimpleName', 'StimulusVolumePanelModule')
    $unrelatedShell = $unrelatedShell.Replace('$selectedPanelModuleId', 'stimulus-volume')
    $unrelatedShell = $unrelatedShell.Replace('$experimentSessionTerminalAudioStop', '')
    $unrelatedShell = $unrelatedShell.Replace('$experimentSessionOnCreate', '')
    $unrelatedShell = $unrelatedShell.Replace('$polarRuntimeReopenFromExplicitLaunch', '')
    foreach ($anchor in @('experimentSessionProfileSha256', 'experimentSessionProviderManifestSha256', 'experimentSessionInventorySha256')) {
        $unrelatedShell = $unrelatedShell.Replace('$' + $anchor, ('a' * 64))
    }
    $unrelatedShell = [regex]::Replace(
        $unrelatedShell,
        '(?s)    // EXPERIMENT_SESSION_SHELL_BEGIN\r?\n.*?    // EXPERIMENT_SESSION_SHELL_END\r?\n',
        '')
    foreach ($forbiddenUnrelatedToken in @(
        'ConditionAudioRuntime',
        'ExperimentSessionPackagedClosure',
        'nativeInitializeExperimentSessionRuntime',
        'conditionAudioReadiness',
        'stopCurrentConditionAudioForTerminal',
        'PolarSensorRuntime',
        'reopenClosedFromExplicitLaunch')) {
        if ($unrelatedShell.Contains($forbiddenUnrelatedToken, [StringComparison]::Ordinal)) {
            throw "Generated non-breath shell leaked experiment-session token: $forbiddenUnrelatedToken"
        }
    }
    $stimulusModule = Join-Path $stimulusRoot 'StimulusVolumePanelModule.java'
    if (-not (Test-Path -LiteralPath $stimulusModule)) {
        throw "Selected stimulus panel source is missing: $stimulusModule"
    }
    $stimulusText = [IO.File]::ReadAllText($stimulusModule)
    if ($stimulusText.Contains('PolarSensorRuntime', [StringComparison]::Ordinal) -or
            $stimulusText.Contains('ExperimentSession', [StringComparison]::Ordinal)) {
        throw 'Stimulus selected source closure leaked Polar or experiment-session ownership.'
    }
    [IO.File]::WriteAllText($stub, $unrelatedShell, (New-Object Text.UTF8Encoding($false)))
    $unrelatedSources = @(
        (Join-Path $mainRoot 'PanelModule.java'),
        (Join-Path $mainRoot 'PanelModuleRegistry.java'),
        (Join-Path $mainRoot 'NativeRendererForegroundGuardPolicy.java'),
        (Join-Path $mainRoot 'NativeRendererSoftKioskCoordinator.java'),
        (Join-Path $mainRoot 'NativeRendererExperimentLaunchAuthority.java'),
        (Join-Path $mainRoot 'NativeRendererExperimentLauncherPolicy.java'),
        (Join-Path $mainRoot 'NativeRendererSoftKioskAccessibilityService.java'),
        (Join-Path $mainRoot 'NativeRendererSelfKioskApplication.java'),
        (Join-Path $mainRoot 'NativeRendererSelfKioskService.java'),
        (Join-Path $mainRoot 'NativeRendererSelfKioskDeparturePolicy.java'),
        (Join-Path $mainRoot 'NativeRendererHomeEpisodePolicy.java'),
        (Join-Path $mainRoot 'NativeRendererSoftKioskRecoveryTimerGate.java'),
        (Join-Path $mainRoot 'PanelImmersiveHandoff.java'),
        (Join-Path $mainRoot 'PanelImmersiveHandoffLifecyclePolicy.java'),
        $stimulusModule,
        $stub
    )
    & $javacCommand.Source '--release' '8' '-encoding' 'UTF-8' '-cp' $androidJar '-d' $output @unrelatedSources
    if ($LASTEXITCODE -ne 0) { throw "Generated non-breath shell javac failed: $LASTEXITCODE" }
} finally {
    if (Test-Path -LiteralPath $output) {
        Remove-Item -LiteralPath $output -Recurse -Force
    }
}

Write-Host 'Test-NativeRendererExperimentPanel PASS'
