[CmdletBinding()]
param([string]$RepoRoot = ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))))
$ErrorActionPreference='Stop'
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererExperimentSessionFacade.psm1') -Force
$arguments=@{SessionMembersOnly=$true;standaloneExperimentOwner=$true;isExperimentSessionPanel=$true;standaloneExperimentOwnerJava='true';experimentSessionProfileSha256=('a'*64);experimentSessionProviderManifestSha256=('b'*64);experimentSessionInventorySha256=('c'*64)}
$members=New-NativeRendererExperimentSessionFacadeSource @arguments
foreach($required in @('applyExperimentSessionCommand(String commandJson)','ExperimentSessionPackagedClosure.prepare(','nativeApplyBreathCompositionCommand(command.toString())','applyConditionAudioControlReceipt','applyBreathPhaseAudioSample')) {
    if(-not $members.Contains($required)){throw "Canonical facade member missing: $required"}
}
if($members -match 'PanelImmersiveHandoff|NativeActivity|SelfKiosk|SoftKiosk|PolarSensorRuntime|ExperimentSessionBle|Gateway|Relay|Lsl'){throw 'SDK session members contain an excluded native presentation owner.'}
$builder=Get-Content -LiteralPath (Join-Path $RepoRoot 'tools/Build-NativeRendererAndroid.ps1') -Raw
if($builder.Contains('public static String applyExperimentSessionCommand')){throw 'Builder contains a fork of the canonical handler.'}
$core=Join-Path $RepoRoot 'apps/spatial-worldspace-hotload-android/session-core'
$android=Get-Content -LiteralPath (Join-Path $core 'src/android.rs') -Raw
if($android -match 'runtime::current_|runtime::note_preparation|controller::pose\(epoch as u64'){throw 'Callback re-resolves global owner or casts unchecked signed sequence.'}
$jni=Get-Content -LiteralPath (Join-Path $core 'java/io/github/mesmerprism/rustyquest/native_renderer/SdkExperimentSessionCore.java') -Raw
foreach($required in @('nativeValidateAudio','synchronized (AUDIO_LOCK)','captureAudioOwnerToken','audioOwner.runtimeEpoch != value.optLong("runtime_epoch")')){if(-not $jni.Contains($required)){throw "Audio delivery fence missing: $required"}}
Write-Output 'Session core source guard PASS (source structure only; no compile/device proof)'

$off=Get-Content -LiteralPath (Join-Path $RepoRoot 'apps/spatial-worldspace-hotload-android/app/src/sessionDisabled/java/io/github/mesmerprism/rustyquest/native_renderer/SessionOwnerActivity.java') -Raw
if($off -match 'System.loadLibrary|SdkExperimentSessionCore|ConditionAudio|ExperimentSessionAndroidShell'){throw 'OFF source links selected ownership.'}
$activity=Get-Content -LiteralPath (Join-Path $core 'java/io/github/mesmerprism/rustyquest/native_renderer/SessionOwnerActivity.java') -Raw
foreach($api in @('ownerBind(','ownerRevoke(','ownerPoll(','ownerStatus(','ownerAction(','ownerController(')) {if(-not $off.Contains($api) -or -not $activity.Contains($api)){throw "Selected/OFF API missing: $api"}}
$bridge=Get-Content -LiteralPath (Join-Path $core 'src/service_bridge.cpp') -Raw
if(-not $bridge.Contains('RTLD_NOW|RTLD_NOLOAD') -or $bridge.Contains('RTLD_DEFAULT') -or -not $bridge.Contains('rq_session_core_install_services_v1(table)')){throw 'Actual loaded service installer is not closed.'}
$controller=Get-Content -LiteralPath (Join-Path $core 'src/controller.rs') -Raw
if($controller.Contains('adapter.configure(at); adapter.start') -or -not $controller.Contains('calibration_button.take_trigger(sequence,elapsed)')){throw 'Calibration bypasses selected actual-button hold.'}
if(-not $activity.Contains('controllerButtonSample(epoch,sequence,acquiredElapsedNs,rightSecondaryPressed)')){throw 'Lossless button observations are absent.'}
Write-Output 'SDK selected/OFF wrapper and installer source guard PASS'

$coreBuilder=Get-Content -LiteralPath (Join-Path $core 'tools/Build-SessionCoreAndroid.ps1') -Raw
$patternMatch=[regex]::Match($coreBuilder,"(?m)^\`$excludedFeatureToken = '([^']+)'\r?$")
if(-not $patternMatch.Success){throw 'Feature exclusion pattern declaration missing.'}
$excluded=$patternMatch.Groups[1].Value
$selected=@(Get-Content -LiteralPath (Join-Path $core 'tests/standalone-selected-features.json') -Raw | ConvertFrom-Json)
if($selected.Count -ne 22 -or $selected -notcontains 'ui.experiment_session_owner'){throw 'Standalone feature regression fixture changed.'}
foreach($id in $selected){if($id -match $excluded){throw "Admitted standalone feature rejected: $id"}}
foreach($id in @('sensor.polar_h10_ble','ui.same_apk_soft_kiosk','ui.experiment_session_ble_control','ui.experiment_boot_autostart','ui.experiment_session_command_gateway','ui.experiment_session_streaming','ui.experiment_session_relay','ble','polar')){
    if($id -notmatch $excluded){throw "Excluded feature escaped selection guard: $id"}
}
$runtime=Get-Content -LiteralPath (Join-Path $RepoRoot 'apps/native-renderer-android/native/src/experiment_session_runtime.rs') -Raw
foreach($name in @('nativeInitializeExperimentSessionRuntime','nativeApplyBreathCompositionCommand','nativeReadBreathCompositionStatus')){
    if(-not ($android+$runtime).Contains('ControlPanelActivity_'+$name)){throw "Generated JNI entry has no real implementation: $name"}
}
if((Get-Content -LiteralPath (Join-Path $core 'src/lib.rs') -Raw) -match 'android_main|native_activity|openxr_stimulus_actions'){throw 'SDK core selects the native XR application loop.'}
Write-Output 'Standalone 22-feature regression and real generated JNI entry source guard PASS'
$audio=Get-Content -LiteralPath (Join-Path $RepoRoot 'apps/native-renderer-android/panel-modules/condition-audio/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/ConditionAudioRuntime.java') -Raw
$media=Get-Content -LiteralPath (Join-Path $RepoRoot 'apps/native-renderer-android/panel-modules/condition-audio/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/ConditionAudioAndroidMediaBackendFactory.java') -Raw
foreach($required in @('prepareOwnerTransfer()','attachOwnerTransfer(','createRestored(transfer.provider','ownerTransferPending')){if(-not $audio.Contains($required)){throw "Audio transition source missing: $required"}}
if($media.Contains('replaceListener(') -or -not $media.Contains('createRestored(') -or -not $media.Contains('setOnSeekCompleteListener')){throw 'Audio transition does not use a newly prepared player identity.'}
if(-not $activity.Contains('retiredHandoff.cancel();if(handoff==retiredHandoff)handoff=null;')){throw 'Matching handoff retirement missing.'}
Write-Output 'Distinct audio backend, lossless button and matching handoff retirement source guard PASS'
$bindingBody=$activity.Substring($activity.IndexOf('private void runBinding('),$activity.IndexOf('public final String ownerRevoke(')-$activity.IndexOf('private void runBinding('))
if($bindingBody.Contains('synchronized(lifecycle)')){throw 'Binding futures are awaited under lifecycle monitor.'}
foreach($required in @('lifecycle.publish(attempt','pendingCommands.retireToken(token)','pending_requested_epoch','native_bound_epoch','currently_bound_epoch','bind_result_phase','retryBinding(attempt)')){
    if(-not $activity.Contains($required)){throw "Lifecycle publication/correlation guard missing: $required"}
}
if(-not $jni.Contains('lifecycle.nativeBound(attempt)') -or -not $jni.Contains('audio.prepareOwnerTransfer(guard)') -or -not $jni.Contains('audio.attachOwnerTransfer(transfer,guard)')){throw 'Pending native binding or audio waiter lacks exact attempt gate.'}
Write-Output 'Unpublished native binding, retired correlation and bounded retry source guard PASS'

foreach($required in @('private static final class TransferAttempt','attempt.retired=true','future.cancel(false)','activeTransfer!=transfer','settleRetiredTransfer(attempt)','retireOwnerTransfer(transfer)')){
    if(-not $audio.Contains($required)){throw "Exact audio transfer retirement missing: $required"}
}
if(-not $jni.Contains('finally{if(transfer!=null&&!attached)audio.retireOwnerTransfer(transfer);}')){throw 'Prepared audio transfer can escape failed native adoption.'}
Write-Output 'Exact transfer timeout, failed adoption and restoration retirement source guard PASS'

if(-not $coreBuilder.Contains('--release --locked --offline --jobs 1')){throw 'Owning Cargo invocation is not explicitly serial.'}
Write-Output 'Owning core compiler parallelism source guard PASS'
