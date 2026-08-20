# Rusty Quest Breath Contract

This crate owns a pure, source-neutral lifecycle for bounded normalized breath
observations. It has no Android, JNI, OpenXR, renderer, broker, or device
dependency.

The lifecycle is explicit: `Reset`, `Configure`, `Start`, `Cancel`, and
`Observe`. Callers inject every timestamp. Each successful start issues a new
generation, and observations from older generations cannot change current
state. Missing, stale, malformed, out-of-order, regressed-time, and
discontinuous-time inputs produce distinct fail-closed observations and
neutral counters.

The bounded replay harness is a conformance surface, not runtime activation.
It executes only caller-supplied actions up to an explicit hard limit. The
fixture corpus verifies deterministic outcomes and damaged over-limit
rejection.

The `calibration` module accepts source-neutral timed three-vector frames. It
admits fitting frames at no more than 10 Hz, counts motion frames that cross a
configured cumulative deadband, and expires only through injected virtual
time. A ready model contains a deterministically directed PCA axis, optional XZ
projection, fifth/ninety-fifth-percentile limits, and typed failure evidence.
Median-of-five plus EMA filtering runs for every valid ready-state frame;
bounded adaptive-limit maintenance runs only on analysis ticks. A synthetic
fixture proves a rapid live change is visible before the next analysis tick.

The `assessment` module provides a minimal source-neutral observation boundary
for concurrent optional volume and normalized phase plus calibration lifecycle,
tracking state, and bounded quality. It deliberately defines no classifier,
hysteresis, dwell, endpoint, source, or application policy.

The `composition` module independently selects Controller or Polar ACC and
Volume or State through an exact capability closure. It owns requested versus
effective readback, ordered calibration actions, generation fencing, mapping
changes that retain a running calibration, source/projection changes that hard
reset it, and fail-closed admission of only the selected assessment. It still
defines no acquisition, transport, UI, or consuming effect.

Source acquisition, source-specific units and policy, platform adapters, UI,
transport, rendering, and application interpretation are deliberately outside
this crate.
