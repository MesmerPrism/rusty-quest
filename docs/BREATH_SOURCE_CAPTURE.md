# Breath Source Capture

`apps/native-renderer-android` can record a bounded, app-private synchronized
source session for host-only analysis. Capture is disabled until the operator
starts it from the same-APK Polar panel or its fixed operator command. It is an
observation sink: it does not own acquisition, composition selection,
calibration, or the driver bank.

## Contents and clocks

A capture always targets exactly 120 seconds. The same panel's fixed
`start_capture` operator command and its Start recording control are equivalent:
each begins one new two-minute session from Android's monotonic clock. The
native watchdog ends the matching capture generation even when the panel is
closed. An operator stop is an intentional early abort: it finalizes whatever
was written but marks the receipt incomplete, so it cannot be used for tuning.

A completed session contains a manifest, an append-only JSONL source timeline,
and a completion receipt. The timeline contains controller right-grip poses and
raw right-thumbstick-Y observations, Polar ACC and ECG PMD frames and samples,
HR/RR observations, normalized assessments, and generic driver applies. Each
row retains the appropriate Android-monotonic, source-native, or OpenXR clock.
RR is recorded as a separate observation and is explicitly marked as not
consumed by the breath path. The right thumbstick is an annotation input only:
positive, neutral, and negative values can later mark inhale, hold, and exhale
without changing the live assessment or driver.

While active, a directory contains `capture.active.json` and
`breath_source_samples.partial.jsonl`. The writer checkpoints at a bounded
record interval, syncs its final bytes, atomically promotes the JSONL, then
writes the final receipt and removes the active marker. Partial/active files
are never eligible recordings. The completion receipt binds the target and
observed duration, stop reason, per-stream row counts, queue/writer failures,
and finalization outcome.

The capture writer uses a bounded non-blocking queue. Queue drops, write
failures, missing atomic finalization, or an early stop mark the receipt
incomplete. Do not use an incomplete session for algorithm comparison unless
investigating capture transport itself. The panel only projects native status;
opening or closing it does not create a second capture or controller-input
owner.

The fixed operator command reports dispatch and effect separately. In
particular, `start_capture` first reports an accepted command generation and
then requires the same app-owned receipt to name a current capture session with
`effect_status=started`. A prior panel/status snapshot is never treated as a
failed start, and an operator must not reissue a relative start command merely
because a stale receipt predates the current operation.

## ACC presentation policies

Both policies retain the same decoded ACC rows for calibration and capture:

- `low-latency-smooth` uses the newest received sample as the target and eases
  toward it at OpenXR render cadence with a bounded 120 ms time constant.
- `timestamp-faithful` holds a 180 ms timestamp buffer and linearly
  interpolates bracketing samples. It is more faithful to the received sample
  path but deliberately less immediate.

The panel and fixed operator commands can select either policy. Effective
status reports the mode, fixed timing values, ingress drops, and latest source
sequence. A mode change clears its presentation interpolation state; it does
not alter raw acquisition or calibration ownership.

## Host analysis

After a completed session is copied out through an authorized app-private
diagnostic route, run:

```powershell
pwsh -NoProfile -File .\tools\Analyze-NativeRendererBreathCapture.ps1 `
  -CaptureDirectory <capture-directory> `
  -OutputDirectory <private-analysis-output> `
  -RightStickHoldDeadzone 0.10
```

The tool verifies the manifest and completion receipt, emits a JSON cadence
report, and writes a time-aligned CSV. The report distinguishes PMD sample and
frame cadence, sample-to-receipt delay, receipt-to-JNI delay, controller pose
and right-thumbstick cadence, and assessment-to-driver delay. The CSV retains
raw neutral source columns together with normalized assessment and driver rows
so tuning can be performed without the headset. The host-only CSV view adds a
derived manual annotation column using the declared deadzone; the recorded
right-stick value remains unchanged and is the source of truth.

Capture directories, raw sensor data, and analysis outputs are private local
artifacts. They are never source or public validation evidence.
