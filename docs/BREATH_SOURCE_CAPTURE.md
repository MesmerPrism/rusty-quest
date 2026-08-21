# Breath Source Capture

`apps/native-renderer-android` can record a bounded, app-private synchronized
source session for host-only analysis. Capture is disabled until the operator
starts it from the same-APK Polar panel or its fixed operator command. It is an
observation sink: it does not own acquisition, composition selection,
calibration, or the driver bank.

## Contents and clocks

A completed session contains a manifest, an append-only JSONL source timeline,
and a completion receipt. The timeline may contain controller poses, Polar ACC
and ECG PMD frames and samples, HR/RR observations, normalized assessments,
and generic driver applies. Each row retains the appropriate Android-monotonic,
source-native, or OpenXR clock. RR is recorded as a separate observation and
is explicitly marked as not consumed by the breath path.

The capture writer uses a bounded non-blocking queue. Queue drops or write
failures mark the receipt incomplete. Do not use an incomplete session for
algorithm comparison unless investigating capture transport itself.

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
  -OutputDirectory <private-analysis-output>
```

The tool verifies the manifest and completion receipt, emits a JSON cadence
report, and writes a time-aligned CSV. The report distinguishes PMD sample and
frame cadence, sample-to-receipt delay, receipt-to-JNI delay, controller pose
cadence, and assessment-to-driver delay. The CSV retains raw neutral source
columns together with normalized assessment and driver rows so tuning can be
performed without the headset.

Capture directories, raw sensor data, and analysis outputs are private local
artifacts. They are never source or public validation evidence.
