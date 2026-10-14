[CmdletBinding()]
param([Parameter(Mandatory)][string]$RepoRoot)
$ErrorActionPreference='Stop'
$base=Join-Path $RepoRoot 'apps/native-renderer-android/panel-modules'
$package='src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$shell=Get-Content -Raw (Join-Path $base "breath-composition/$package/ExperimentSessionAndroidShell.java")
$owner=Get-Content -Raw (Join-Path $base "experiment-session-owner/$package/ExperimentSessionOwnerPanelModule.java")
$panel=Get-Content -Raw (Join-Path $base "experiment-session-owner/$package/NativeLocalControlPanel.java")
foreach($needle in @('attachedSink.get()!=sink||retiredSinks.containsKey(sink)','scope.launchEpoch!=launchEpoch','localTerminalDispatched=true','nativeBridge.applyLocalTerminal(terminalCommandJson,local)','localTerminalScope==null||saved','acceptsExperimentSessionTerminalCallbacks(epoch)','terminal-admission-unknown-observe-only-no-replay','isCurrentTerminalUiAttachment')){if(-not $shell.Contains($needle)){throw "Missing terminal guard: $needle"}}
$entry=$shell.Substring($shell.IndexOf('boolean beginLocalTerminal('),$shell.IndexOf('private void beginLocalTerminalOnOwner(')-$shell.IndexOf('boolean beginLocalTerminal('))
if($entry.Contains('attach(sink)')){throw 'Local terminal admission can steal current UI attachment'}
$apply=$shell.Substring($shell.IndexOf('private void continueTerminalAfterAudio('),$shell.IndexOf('private void scheduleStatusPoll(')-$shell.IndexOf('private void continueTerminalAfterAudio('))
if($apply.IndexOf('validateLocalTerminalScope(local)') -gt $apply.IndexOf('clock.elapsedRealtimeNanos()')){throw 'Cutoff sampled before blocking scope verification'}
foreach($needle in @('nativeReadExperimentSessionImmersiveOwnerIdentity','nativeApplyExperimentSessionTerminalWithFence','ControlPanelActivity.admitStandaloneTerminal(scope)','ControlPanelActivity.finishStandaloneTerminal(activity,','result.shouldFinish&&result.saved','isCurrentTerminalUiAttachment(this,epoch)')){if(-not $owner.Contains($needle)){throw "Missing actual owner API: $needle"}}
if(-not $panel.Contains('sessionButton("save-exit","Save and exit")') -or -not $panel.Contains('sessionButton("save-next","Save session & prepare next")')){throw 'Terminal or save-next control absent'}
if(-not $shell.Contains('readback.receipt!=null&&readback.runtimeEpoch>0L&&"ready".equals(readback.initializationStatus)')){throw 'Unavailable terminal observation can be mistaken for replacement'}
if(-not $shell.Contains('localTerminalScope.idleWithoutRecording')){throw 'Idle result gate missing'}
if(-not $owner.Contains('replayCurrentTerminal(NativeMeshSessionControls.this,epoch);EXPERIMENT_SESSION_SHELL.requestStatus')){throw 'Same-sink terminal completion not replayed before status on resume'}
$replay=$shell.Substring($shell.IndexOf('boolean replayCurrentTerminal('),$shell.IndexOf('boolean resumePendingTerminal(')-$shell.IndexOf('boolean replayCurrentTerminal('))
if($replay.Contains('dispatchTerminal(') -or -not $replay.Contains('postTerminal(expectedLaunchEpoch,result)')){throw 'Cached replay republishes owner receipt'}
if(-not $shell.Contains('latestTerminal!=result')){throw 'Superseded terminal deliveries remain actionable'}
Write-Output 'Local terminal SOURCE guard PASS; production JVM/native/Android/device execution pending.'
