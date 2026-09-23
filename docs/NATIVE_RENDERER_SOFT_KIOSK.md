# Native Renderer same-APK soft kiosk

`ui.same_apk_soft_kiosk` explicitly selects `NativeRendererSelfKioskApplication`
and the private same-process `NativeRendererSelfKioskService`. NativeActivity remains
the only MAIN/VR/LAUNCHER surface. The existing frame-submission gate opens the
landscape ControlPanelActivity after the first current-session frame.

## Own-app observation and return

The Application observes only the two known own-package Activities. The foreground
service confirms the panel using resumed/window-focus readback, and immersive
presentation using resumed NativeActivity, its own Android window focus, and fresh current-generation
`renderer_focus_state.json` evidence: FOCUSED, submitted frame, valid schema/activity,
and a non-future timestamp no more than three seconds old.

The panel-to-VR handoff dispatches one reassertion after the panel actually pauses,
because Quest may reveal a previous 2D launcher during that task transition. It
then requires both advancing focused OpenXR frames and
the immersive Activity's Android window focus for 750 ms. On a Quest task transition,
the 2D panel can close while the previous 2D launcher becomes foreground even though
OpenXR still reports focused frames. OpenXR alone is therefore not a handoff receipt.
The panel reasserts its own VR launch every 500 ms while that mismatch persists,
for at most 6.5 seconds; the self-watchdog begins recovery after the panel-to-VR
transition grace period of two seconds and its normal 750 ms absence threshold.
Only the app-selected presentation counts as a confirmed return. The other
own-package Activity remains allowed, but it cannot satisfy or cancel recovery
of a different desired presentation.

The selected manifest contains no Accessibility service. It declares foreground
service, special-use foreground service, and `SYSTEM_ALERT_WINDOW` permissions.
The service starts only after the coordinator accepts an explicit launch epoch.
Process death loses that authority; START_NOT_STICKY prevents silent re-arming.
The service shares the app process so its coordinator is the same authority as the
panel and session code.

Android background-launch restrictions still apply. The UI offers this package's
display-over-other-apps settings and reports `Settings.canDrawOverlays`; it never
changes that setting itself. Dispatch is reported as requested/unconfirmed until
own lifecycle and native focus confirm the desired presentation. Same-app identity
and a successful `startActivity` call alone do not establish recovery.

The watchdog polls every 75 ms, waits 250 ms before requesting return, and permits
at most three attempts per unresolved departure/generation, spaced 1500 ms apart.
It targets only the known own-package panel or NativeActivity and preserves the
selected panel route. It never inspects arbitrary foreground apps. Sleep, keyguard,
bounded app transitions, and declared system prompts suppress recovery.
Permission callers must invoke `NativeRendererSelfKioskApplication.beginSystemPrompt`
before dispatch and `endSystemPrompt` when the result arrives. Prompt suppression
expires after 60 seconds if no callback arrives.

## Departure gesture and exit

The three-in-five-seconds escape is a **departure gesture**, not physical HOME
interception. On Quest 2, Meta Navigator can take focus without pausing NativeActivity;
the app therefore accepts a sustained loss of its own window focus or a qualified
lifecycle pause following confirmed presentation. An episode must remain absent for
250 ms; another episode requires confirmed recovery first. Short focus flickers,
prompt/transition/lock/sleep episodes are discarded
and cannot count retrospectively after suppression expires. Platform prompts not
covered by those signals may require another explicit app-owned suppression hook.

The third qualifying departure or the **Save and exit** button latches terminal
before dispatching the typed `TERMINAL_SAVE_AND_EXIT` intent, invalidates pending
launcher authority, and cancels panel-to-immersive reassertion. Every future tick
and recovery claim checks the terminal/generation state. The service neither
finishes Activities nor claims data was saved. The existing app-owned session/writer
path validates the process-local terminal binding and waits for its shutdown
acknowledgement before finishing its own tasks. The exact terminal request remains
pending and is retried by the service or the next resumed own Activity until the
control panel acknowledges admission; a silently denied background launch therefore
cannot strand the recording behind a terminal latch. Only a newly accepted explicit
launch epoch can install a fresh policy.

The NativeActivity launch authority still rejects recreation, mismatched component,
data/selectors, unexpected categories, and replay. A self-return cannot create a
new explicit launch epoch. Legacy Accessibility adapter source remains unselected
compatibility code; it is not declared by the current feature.

## Provenance and validation limits

The earlier generation/cancellation policy was informed by Rusty Kiosk commit
`10593c9750ba7d400f41951d9dad36c619da2219`. The self-app observation approach is
informed by Study 6 commit `994498c9299b3f5d5475047eb32022b629a83473`, whose own
background-launch spike required overlay authorization. This implementation does
not copy its study-specific session logic or process-kill protocol.

Run `tools/checks/Test-NativeRendererSoftKioskStatic.ps1` for real Java compilation,
pure departure/suppression/terminal traces, and selected/unselected manifest dry
resolution. Run `tools/checks/Test-NativeRendererExperimentPanel.ps1` for generated
panel-shell compilation. Neither test establishes headset acceptance.

Attended Quest validation must cover real Meta-button departures in both panel and
immersive presentation, permission prompts, sleep, overlay permission readback,
background-return acceptance, native focus, and writer-acknowledged exit. Synthetic
ADB Home is not physical controller parity. Do not claim exact Home counting or
unconditional return-to-foreground support from these host tests.
