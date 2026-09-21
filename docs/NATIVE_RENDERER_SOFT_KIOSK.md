# Native Renderer same-APK soft kiosk

## Decision

`ui.same_apk_soft_kiosk` is an explicit native-app feature. When selected, it:

- replaces NativeActivity's launcher filter with one UI-free 2D launcher trampoline;
- keeps NativeActivity and ControlPanelActivity available as explicit same-package surfaces;
- declares `NativeRendererSoftKioskAccessibilityService` behind Android's
  `BIND_ACCESSIBILITY_SERVICE` binding permission; and
- adds no requested runtime permission, hidden enablement route, device-owner authority, input
  interception, UI-tree access, global action, process kill, or host watchdog.

The feature being packaged does not make it effective. The wearer must explicitly enable the
service in Android Accessibility settings. App UI must project the coordinator's typed effective
state (`needs setup`, `home resolving/degraded`, `home unavailable`, `connected/armed`,
`interrupted`, `revoked`, or `terminal`) rather than treating a packaged/default request as a
grant. Recovery remains unavailable until the current HOME component resolves exactly.

## Authority

`NativeRendererExperimentLauncherActivity` is the sole production caller that mints a monotonic,
process-local launch epoch. It forwards the epoch and literal `explicit-user-launch-v1` provenance
to ControlPanelActivity. These extras do not authenticate a human. They are launcher-path
provenance whose trust is bounded to Android starting the sole exported launcher component with a
fresh exact MAIN/LAUNCHER intent. The trampoline rejects saved-state recreation, non-MAIN or
non-LAUNCHER routes, extra categories, data/selectors, and observable component mismatches.
`NativeRendererExperimentLaunchAuthority` still consumes the pair once, so bare extras, internal
MAIN intents, Activity recreation, soft-kiosk recovery, replay, and terminal intents cannot arm a
cold-launch epoch.

After the app's presentation owner accepts that one-shot launch, it arms
`NativeRendererSoftKioskCoordinator` with the desired same-APK surface and transition generation.
The Accessibility service only adapts focus/window observations into that pure policy. It resolves
the current Android HOME component off the main thread and counts only exact, distinct Home
episodes. Duplicate shell tails inside the debounce do not advance the escape count, while a
spaced press opens a new episode even if recovery never succeeded. Unrelated external applications
recover the current desired app surface after a bounded delay, with three attempts maximum per
departure episode/generation and typed exhaustion. Alternating external activities remain one
departure budget until the desired app returns, presentation generation changes, or a distinct
exact Home episode begins. Repeated observations for the same generation/episode preserve the
original recovery deadline rather than postponing it. Exact, generation-bound system-prompt leases and
app-transition grace immediately invalidate older recovery callbacks. Generation-bound deadline
timers evaluate expiry even without another window event. Claim suppression is bound to the exact
leased surface; unrelated applications and exact Home episodes are not hidden by a Settings lease,
and exact Home remains authoritative during a presentation transition.

The third Home episode disarms the guard first, invalidates the pending launcher epoch, cancels its
own recovery and the shared panel-to-immersive reassertion, then sends one explicit typed
`TERMINAL_SAVE_AND_EXIT` intent to ControlPanelActivity. The service neither finishes activities
nor reports saved data. The app-owned session/writer path must validate the process-local terminal
binding, durably finalize, and only then finish its known tasks. Terminal state forbids recovery;
only a later accepted explicit-launch epoch can install a fresh policy.

## Reference provenance and non-scope

The event/debounce and generation/cancellation design was informed by Rusty Kiosk at commit
`10593c9750ba7d400f41951d9dad36c619da2219` and the Study 6 transition-exclusion report at commit
`994498c9299b3f5d5475047eb32022b629a83473`. This implementation is new Java code under Rusty
Quest's license; it does not copy the Rusty Kiosk catalogue/setup/network surfaces or the legacy
Study 6 task removal/process-kill protocol.

Host validation cannot prove that a given Horizon version emits the resolved HOME component for
physical button episodes. Quest 2 and Quest 3-family attended tests remain required after the
feature is selected by an exact product, wired to its writer-acknowledged exit, built, installed,
and explicitly enabled.

## Host validation

Run:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\checks\Test-NativeRendererSoftKioskStatic.ps1 `
  -RepoRoot .
```

The test compiles the real Java adapter, executes pure one/two/three-Home and damage traces, and
dry-resolves both selected and unselected app manifests. The unselected route must retain the
existing NativeActivity launcher and omit the service/trampoline.
