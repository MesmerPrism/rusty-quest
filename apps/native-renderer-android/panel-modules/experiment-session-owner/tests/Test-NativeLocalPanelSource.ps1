[CmdletBinding()]
param([Parameter(Mandatory)][string]$RepoRoot)
$ErrorActionPreference='Stop'
$module=Join-Path $RepoRoot 'apps/native-renderer-android/panel-modules/experiment-session-owner/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$panel=Get-Content -Raw (Join-Path $module 'NativeLocalControlPanel.java')
$bridge=Get-Content -Raw (Join-Path $module 'NativeLocalPanelBridge.java')
$controller=Get-Content -Raw (Join-Path $module 'NativeLocalControlController.java')
if(($panel+$bridge) -match 'PolarSensor|ExperimentSessionBle|Gateway|Relay|SelfKiosk|Lsl|AlertDialog|Spinner|PopupWindow'){throw 'Excluded owner or unrouted popup in selected local panel.'}
foreach($topic in @('home','particles','breath','status','prepare','controls','condition','session')){if(-not $panel.Contains('"'+$topic+'"')){throw "Missing canonical topic/workflow page $topic"}}
if(-not $panel.Contains('if(user)changed.changed') -or -not $panel.Contains('pageEpoch==captured')){throw 'Hydration/navigation fence absent.'}
foreach($field in @('size.mode','size.world_meters','size.sphere_radius_percent','size.oscillation_percent','world_anchor_scale_m','tracer.draw_slots_per_oscillator','tracer.lifetime_seconds','tracer.copies_per_second','driver_values01.')){if(-not $panel.Contains($field)){throw "Missing canonical control $field"}}
if(-not $controller.Contains('"effective_revision")==revision') -or -not $controller.Contains('pendingRevision!=revision')){throw 'Effective receipt identity absent.'}
$owner=Get-Content -Raw (Join-Path $module 'ExperimentSessionOwnerPanelModule.java')
if(([regex]::Matches($owner,'new ExperimentSessionPanelCoordinator\(')).Count -ne 1){throw 'Session coordinator duplicated.'}
$producer=Get-Content -Raw (Join-Path $RepoRoot 'apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/NativeMeshUiProducer.java')
if(-not $producer.Contains('panelAttachment.close()') -or -not $producer.Contains('return; // Selected local product owns its full UI')){throw 'Selected product UI or attachment lifetime not isolated.'}
if(-not $producer.Contains('return; // A failed selected owner must not expose integration controls.')){throw 'Failed selected attachment falls back to integration controls.'}
$legacy=Get-Content -Raw (Join-Path $RepoRoot 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/BreathCompositionPanelModule.java')
if(-not $legacy.Contains('NativeLocalControlController.particlePatch(edits')){throw 'Canonical patch serializer was forked.'}
foreach($needle in @('ActivityLifecycleCallbacks','panel.suspend()','scope.current(EXPERIMENT_SESSION_SHELL.currentLaunchEpochForUi()','scope.transfer()','WeakReference<NativeMeshSessionControls>','processCommand.observe(nativeState())','catch(RuntimeException failure){controls.close();throw failure;}','layout.removeView(panel.view())')){if(-not $owner.Contains($needle)){throw "Missing attachment/handoff guard $needle"}}
if($panel.Contains('breathReadback.setText(bridge.breath().toString') -or $panel.Contains('particleReadback.setText(bridge.particles().toString')){throw 'Default control pages expose raw snapshot instead of summaries.'}
if(-not $panel.Contains('Live breath state unavailable; refresh pending.') -or -not $panel.Contains('Renderer state unavailable; displayed controls remain provisional.')){throw 'Failed readback retains stale effective labels.'}
Write-Output 'Native local panel SOURCE checks PASS; Java compilation, widgets, JNI and device effects unexecuted.'
