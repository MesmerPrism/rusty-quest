# Standalone experiment-session owner

`ui.experiment_session_owner` selects the `experiment-session-owner` panel
module. It packages the existing process shell, coordinator, shared JSON codec,
verified profile/audio closure, and generation-fenced command handoff. The
generated `ControlPanelActivity.applyExperimentSessionCommand(String)` remains
the same binding handler as the legacy breath panel. The native runtime remains
the only recording, control-state, and durability owner.

The independent entry presents the existing Arm, Start, Pause, Resume, guidance
bias, and Finish/save-next operations. `attachNativeMeshSessionControls` is a
public inherited method of the generated activity for the native mesh producer.
Commands stay within the existing schema. A queued operator reply is pending;
confirmation requires the exact generation, epoch, session, condition, profile,
activation binding, operator ID/origin, effective status, and control state.
Unknown outcomes do not replay commands. Handoff requires raw native
`phase=active`, not an adapter-created recording flag.

This route is controller-driven. Its descriptor excludes the legacy breath
panel, Polar, kiosk, boot, BLE operator, gateway, relay, and Hub routes. The
builder checks the selected closure and manifest, emits a presentation facade
without kiosk classes, and limits Java sources to the declared session/audio
modules and a fixed base family. Media and embedded transport Java sources are
outside that base family. The generated app-status bridge prepares the verified
closure without acquiring a remote transport or sensor owner.

The selected profile must explicitly declare the standalone schema and false
kiosk policy. Generated selection, packaged feature-lock selection, and profile
policy must agree. The legacy profile and feature route retain their existing
schema and behavior. Runtime properties cannot select a different panel module
or change the packaged owner policy.

Validation uses `tools/checks/Test-ExperimentSessionStandaloneOwner.ps1` with a
new output directory. It generates the real legacy and standalone facades,
compares their command handlers, checks excluded owners, and compiles the exact
standalone Java closure when an explicit Android platform jar is supplied.
Also run the existing experiment-panel, packaged-closure, codec/status, and
remote-durability fixtures after changing their shared sources. Source or host
checks do not establish device presentation, GPU output, audio timing, or saved
recording durability.
