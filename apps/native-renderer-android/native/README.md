# Native session bias decisions and live clocks

`operator-set-bias` uses the existing closed operator envelope and adds one
integer `bias_percent` in 0–100. The native worker checks the runtime/session,
generation, condition, source binding, and operator origin. A packaged guidance
timeline and an armed, running, or paused recording are required. The manifest
retains the initial Arm bias and exact asset identity.

The recording actor writes `breath-guidance-bias-changed` to `events.jsonl`, then
synchronizes streams and publishes a checkpoint before the worker applies the
new value or acknowledges it. Each event has the old/new percentages, generation,
operation ID, origin, monotonic/derived UTC timestamps, and a consecutive bias
revision. Its `decision_status` is `committed-before-application`: the event
proves a durable decision, while a breath row with that revision proves use.
A crash between the two does not fabricate an application receipt. A failed or
unknown durable operation terminates the recording with an error and never
automatically retries or applies the value.

Same-value decisions receive a new revision and event. Exact replays do not
apply again; conflicting reuse is rejected. Bias operation IDs remain retained
for the worker lifetime, with an explicit 4096-decision admission limit rather
than eviction. The separate current projection exposes `bias_percent`,
`bias_revision`, `arm_bias_percent`, and `last_operation_id` under
`readback.breath_guidance`. Queue acceptance is not an application receipt.

Breath rows preserve `unbiased_phase`, the applied `guidance_bias_percent`, and
`guidance_bias_revision`; classifier `settings_revision` remains independent.
Revision zero denotes the Arm configuration or an observation to which no
guidance target was applied.
Guidance is attributed only when the Tuned classifier evaluates the target for
a valid live observation after validation and priming. Common classification,
calibration, missing samples, rejection, and classifier reset/prime observations
carry no applied target, even if a nonzero global bias is configured.
Checkpoints and final records add
`breath_guidance_state` with the latest committed percentage/revision. Recovery
validates the decision chain, operation uniqueness, terminal state, and breath
revision references. Historical recordings without this additive extension
retain their existing validation rules.

Status computes `active_time_ms` from the native accumulated active time plus
the current monotonic interval only while running in immersive presentation.
The existing submitted-frame stall bound limits that observation and freezes
the reducer interval when frames stop. Pauses and unavailable presentation add
no active time. Completion is a durable threshold receipt; it does not stop the
clock or recording. `recording_elapsed_ms` measures acquisition since Arm,
including pre-roll and pauses. `preparation_elapsed_ms` stops at official Start.
These readbacks do not submit Tick commands, mutate operation receipts, or use
wall time as experiment-clock authority.

The owner reconciles submitted-frame expiry before queued mutations and after
blocking command work, even when the command lane remains busy. It records the
freeze at the last submitted frame plus the existing grace interval. A command
whose timestamp predates an already committed automatic freeze is rejected
with `MonotonicTimeRegression`; replay retains that disposition. Command
timestamps are never rewritten to hide queue delay.

Host validation includes the `experiment_session` and `session_recording`
tests, the live-bias tests included by `experiment_session_runtime`, and the
existing guided/unbiased classifier tests. Android target compilation and
attended recording evidence remain separate checks.

Guided experiment Arm selects the already-capable Polar ACC source and State
mapping through the app-owned authority, retaining projection and inversion.
It requests the existing tuned classifier settings: inhale/exhale entry .030,
hold band .025, smoothing/confirmation/dwell 400 ms, stale 500 ms, motion 2 mg,
leave-contraction/expansion .030, and late window 120 ms. The session worker
awaits the actual renderer's Configure/Start and successful parameter boundary
before preparing a recording. The wait is bounded at two seconds and does not
hold the composition mutex. Timeout cancels that pending request; superseding
configuration or failed/absent adapter rejects Arm with no durable Arm receipt.
A healthy adapter already acknowledging the exact settings retains its
calibration and tuning generation across Arm.

`breath_tracking_configuration` is an immutable initial snapshot in manifest,
Started, checkpoints and final metadata. It includes source/mapping, projections,
inversion, composition/tuning identities and revisions, exact settings, Arm
identity, and declared source clocks. `boundary_source_time_ns` is tagged as
OpenXR time from the actual consumer boundary; `prepared_monotonic_ns` is the
separate Android elapsed-time observation. Healthy reuse retains the original
boundary timestamp. Recovery checks the snapshot and exact metadata joins;
historical recordings without this extension remain valid without backfilling.

Configuration readiness permits pre-roll/calibration even without fresh sensor
samples. It never proves sensor freshness, completed calibration, or valid
tracking; the snapshot explicitly says `tracking_claim: not-established`.
New breath rows name `assessment_source`. Polar presentation samples and their
assessment observations use OpenXR time; `polar_source_provenance` separately
preserves the raw Java nano-time sample endpoints, sensor timestamps and source
sequence identities, the actual paired Java observation bracket and XR target,
and the presentation policy. This is a declared presentation mapping, not a
claim that Java and OpenXR share an epoch. Historical Controller assessments in
the audited archives remain distinguishable by their old OpenXR sample clock.
Raw Polar capture or a zero-bias label alone is not evidence that the derived
breath stream used Polar tracking.
