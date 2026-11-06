# Native particle orbit controls

The existing product panel particle owner accepts an optional `orbit_distance`
object in its field-patch request. The same object is used by the local panel
and shell-authorized `Invoke-ProductPanelControl.ps1` action `particle-patch`.
Existing Direct-mode admission, revision fencing and submitted-frame effective
readback apply. A dispatch receipt alone does not establish an applied change.

```json
{
  "orbit_distance": {
    "source": "synthetic-heartbeat",
    "scale_with_particle_size": false,
    "reference_radius_m": 0.03,
    "synthetic_bpm": 60
  }
}
```

Fields may be sent individually; omitted fields preserve their existing values.
Unknown fields, invalid source names and nonfinite/out-of-range values fail
before staging. Sources are `static-minimum`, `oscillator`, `polar-heartbeat`
and `synthetic-heartbeat`. Reference radius is bounded to0.001–0.25m and the
periodic test input to30–240 events/minute. Source selection is explicit: loss
of a measured source never selects the synthetic source. The event producer
resets timing on source/rate changes, emits at most one event in a frame and
does not catch up missed beats after a delayed frame. Old measured events are
not replayed when enabling the measured source.

The generic adapter transports a normalized event in the existing bank slot5.
The linked private payload owns its pulse envelope and spatial interpretation.
Control-bank slot5 auxiliary row19 is `[enabled, source_code, scaling, reference]`,
where source codes0–3 follow the source order above. Other auxiliary slots and
the phase buffer layout remain unchanged. Consumers that do not opt in retain
their previous routing. Packaged opt-in uses
`RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_DEFAULT_ORBIT_ENABLED=true`;
absent/false stays inert. An explicit orbit field patch also enables the route.

The panel's source selector, scaling checkbox, fixed reference and synthetic
rate hydrate from effective status without generating edits. `orbit_distance`
in the effective settings and the `privateParticleOrbit*` runtime markers
report selected values. Legacy Polar enable/disable requests map to measured
source/static minimum only for an opted-in orbit consumer; legacy consumers
retain their original behaviour.

Tracer draw requests are separate from resident output/sort capacity. Zero
startup draw therefore does not prevent later increasing the budget up to the
already allocated state slots per particle. A payload with no tracer state
still has zero capacity.

Focused checks are the native Rust `private_particle_orbit_settings`,
`private_particle_tracer_budget`, `private_particle_heartbeat_pulse_adapter` and
`native_renderer_stimulus_panel` tests. Actual Android Rust/Java compilation and
private shader conformance belong to the selected downstream composition;
source tests do not prove pixels, pulse timing on hardware or sensor readiness.
