# Finite capture stall diagnostics

`PackedStereoCaptureOwner` has an observation-only probe for delays that the
older callback timestamps exclude. It changes no Camera2 request, frame rate,
surface, pairing, encoder, pool, route, enrollment or cleanup decision.

The existing explicit qualification ARM calls `armDiagnosticTrace` through
`EmbeddedDuplexResources.armOwnCaptureTrace`. That call starts a **600-second**
window in this capture instance. Before ARM the probe is off: its event hooks
read a nullable window and do not read a diagnostic clock or post a heartbeat.
There is no new provider verb, property, permission or background worker.

The window starts at ARM, including startup time, rather than at first Peer
presentation. An operator must verify `active: true`, matching process/app/arm
identities and a useful remaining deadline after both Peer sources are live.
Retain the startup brackets. An expired window is evidence of expiry; it does
not authorize a live qualification rearm or trigger one automatically.

## Readback

The existing `own_capture_diagnostic` response adds
`own_capture_stage.stall_probe`. Its unarmed shape is
`{"armed":false,"active":false}`. Once armed it retains:

- `process_epoch`, `app_generation`, `arm_generation`, `start_ns`, `deadline_ns`,
  `window_ns`, and the effective `active` state;
- fourteen fixed stage rows: left/right started/completed callbacks, their
  outer trace-epoch and trace-record calls, steady capture `pool.pollReady`,
  and the encoder offer including subscription selection and detached release;
- completed count, active entry/frame, longest call and its bracket/frame,
  last entry and largest adjacent entry gap with its bracket/frames, thread ID,
  separately sampled JVM thread state, and skipped overlapping observations;
- a 100 ms heartbeat posted to the existing Camera2 handler: count, last and
  next expected execution, maximum lateness, and rejected-post state.

Times use the local elapsed-realtime clock. Frame `-1` means unavailable at
entry (completed-callback entry precedes reading its result; pool/offer stages
do not claim a raw camera identity). Rows marked `coherent:false` were busy
during both bounded snapshot attempts and must not supply timing assertions.
The heartbeat fields and different stages are sequential, non-atomic samples.
JVM thread state is a current observation, not the state during an old maximum.

Outer spans include lock acquisition, method execution and scheduling delay.
A long trace-call span alone does not prove monitor contention. A delayed
heartbeat shows that the handler did not execute that task on time; an on-time
heartbeat during absent camera callbacks narrows the delivery question but
does not identify a HAL fault or prove a physical sensor shutdown.

The probe snapshot is collected **before** the existing synchronized frame
trace snapshots. The complete owner/provider read still waits on those legacy
trace monitors. It is not a nonblocking live stack sampler. A response can
therefore deliver an earlier in-flight probe sample only after a trace lock
becomes available; completed waits are retained in each stage's maximum.
The separate dropout diagnostic and qualification envelopes are unchanged.
The existing full-response 32 KiB bound remains enforced.

## Cost and lifetime

One arm allocates one window, fourteen fixed slots and one heartbeat callback.
Armed event hooks allocate no per-event objects, log nothing, and acquire no
Java monitors. They use local clock reads, volatile fields and a single CAS to
skip overlapping same-stage observation. Export allocates JSON only on its
existing caller. Instrumentation can perturb timing; host tests establish no
Quest overhead bound or performance improvement.

Expiry disables sampling and stops the heartbeat chain. Stop/close removes the
window's pending heartbeat. An explicit later ARM fences old callbacks with a
new window; late completions remain attached to the old window and cannot
populate its successor. A post racing Stop removes only its own callback.
Handler rejection is diagnostic evidence and does not change media state.
No timer extends a device, camera, lease or session lifetime.

## Focused host validation

```powershell
python tools/checks/Test-CaptureStallDiagnosticsHost.py --output target/capture-stall-host --json-jar <org-json.jar> --android-jar <android-36/android.jar>
pwsh -NoProfile -File tools/checks/Test-RustyQuestMediaStreamAndroid.ps1 -RepoRoot .
python tools/check_quest_boundaries.py
git diff --check
```

The first command uses a create-new repository `target` directory. It runs the
real pure diagnostic with deterministic clocks and a real JVM monitor block,
then extracts the production callback, offer, poll and snapshot method bodies
into a harness with modeled Android/native leaves. Controls cover off/expiry,
overlap, rearm isolation, handler rejection, preserved exceptions and detached
frame release, both eyes, and the complete accessor's inherited trace wait.
It also compiles all actual shared Java sources against the Android API.
Its receipt pins sources and dependencies. A populated large-value probe JSON
is retained for a downstream full-envelope size/reader compatibility check.
These checks build no APK and establish no live camera, codec, latency,
Bluetooth, network, performance or qualification result.
