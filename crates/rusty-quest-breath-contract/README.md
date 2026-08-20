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

Source acquisition, calibration, phase estimation, UI, transport, rendering,
and application interpretation are deliberately outside this crate.
