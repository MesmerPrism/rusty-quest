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

Source acquisition, source-specific units and policy, phase estimation, UI,
transport, rendering, and application interpretation are deliberately outside
this crate.
