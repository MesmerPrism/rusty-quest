# Remote Polar setup and carrier recovery

The optional experiment BLE owner exposes setup only during idle preparation.
The application process owns Polar acquisition; scan, selection, connect and
ACC/ECG start use the same handlers as the local panel and existing operator.
Normal setup never launches an Activity. Missing Android grants or radio
readiness remain explicit platform prerequisites; maintenance may open the
visible permission surface.

Status `y` contains `s` (Polar owner reference), `g` (native runtime epoch),
`r` (setup revision), `p` (effective permission), `b` (radio ready), `c`
(selected sensor connected), `x` (selected twelve-hex sensor identity), `a`/`e`
(fresh actual ACC/ECG frames), `d` (up to four current candidate identities),
and `sc` (scan in progress). Candidate identities are opaque hashed identities,
not BLE addresses. Refresh before every mutation. `h` is fresh app-owned
presentation: `panel`, `vr-preparation`, `vr-session`, or `unknown`.

Commands are closed, at most 240 UTF-8 bytes:

```json
{"v":4,"id":"1111111111111111","op":"scan","s":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","g":1,"r":1,"d":"","n":"bbbbbbbbbbbbbbbb"}
```

Operations are `scan`, `select`, `connect`, `start-acc`, `start-ecg`. Only select
accepts a twelve-hex `d`; other operations require the empty string. Integers
must be JavaScript-safe, with a positive epoch. The gated carrier additionally
requires a sixteen-byte lowercase-hex HMAC-SHA256 prefix in `mac`, using
`RQPS1|challenge|id|op|s|g|r|d|n`. Existing session and stop capability versions
retain their own independent authority.

A receipt confirms scan completion, selected identity, connection, or fresh
actual requested stream frames, respectively. Transport acceptance does not
confirm acquisition. Retained exact command IDs return the original outcome;
a different body under the same ID is rejected. Generation/revision mismatch,
permission failure and another pending operation reject before dispatch.
A timeout publishes uncertainty and never dispatches again. Original setup
receipts carry admission, publication and effective-frame timing where available.

The existing DUMP-protected receiver accepts the identical v4 object in
`polar_sensor_setup_request_b64`. The read-only object `{"v":4,"op":"status"}`
returns current `y` and the original owner receipt. Use
`tools/Invoke-NativeRendererPolarSetup.ps1` without a request path to observe,
or with an exact JSON request path to dispatch once. The tool never automatically
retries; a subsequent status call observes the original pending outcome.

Screen-on, user-present, radio-on and actual own-app resume reconcile the
current native owner. Active wake reconciliation refreshes advertising and
status without changing the carrier generation or replaying commands. Radio
loss retires stale handles; recovery makes at most six bounded attempts for
the same current owner. Stale callbacks cannot adopt a replaced owner.

Host qualification: `python tools/checks/test_remote_setup.py` and
`pwsh -NoProfile -File tools/checks/Test-NativeRendererExperimentPanel.ps1`.
These cover production owner decisions, bounded wire controls and Android API
compilation; they do not establish device sleep/wake or sensor radio behavior.

## Open the existing system settings action

With the Polar page attached, the existing protected operator also accepts
`Invoke-NativeRendererPolarOperator.ps1 -Command open_location_settings`.
Supply the same explicit `-Serial` and `-PackageName` used by its other
commands. This calls the original page's Open location settings handler,
including its system-prompt lifecycle and general-settings fallback.

A detached page rejects before launch. An accepted request reports `pending`
or `unavailable`; it does not confirm the displayed system Activity or a
changed setting. For this action the host reports
`foreground_activity_changed: null` and `system_settings_requested: true`.
Observe actual foreground/platform state separately. The action does not
enable location services, grant a permission, connect a sensor or replay a
previous setup request.
