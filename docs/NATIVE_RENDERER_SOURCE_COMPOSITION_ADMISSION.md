# Native Renderer source-composition admission

## Decision

This change reconstructs the public, pre-panel baseline required to apply the
later W-007A panel-module separation.  It is a snapshot port from public source
commit `784f9f3d79797a1cacb787c7f5ae72226d19724f`, onto
`origin/main` `a54f3c4b836dca59afed7b37ccc92182f14b682b`; it is not a
cherry-pick and it deliberately excludes W-007A's `784f9f3..cd185ca` panel
module moves.

The public default build remains the existing shell.  Every admitted optional
family is selected only through an exact native-app feature/build lock; an
unselected family is inert.  This admission adds no device route, no private
binding input, and no new renderer/material/coupling/performance setting.

## Per-path provenance classification

The 20 Rust paths missing from current main are classified individually below.
All are public blobs from the stated source commit; **feature-owned** means
that the path may only be reached through its explicit lock, not that its
contents came from a private repository.

| Path | Classification | Admission reason |
| --- | --- | --- |
| `native/src/bounded_breath_phase_integrator.rs` | neutral shared runtime substrate | bounded deterministic phase math used by the locked composition contract |
| `native/src/breath_calibration_controller_action.rs` | feature-owned behavior | locked calibration input adapter |
| `native/src/breath_capture.rs` | feature-owned behavior | locked low-rate capture state; no capture runner is admitted |
| `native/src/breath_composition_driver.rs` | feature-owned behavior | lock-gated driver bridge |
| `native/src/breath_composition_runtime.rs` | feature-owned behavior | JNI source closure for the later panel module |
| `native/src/breath_input_selection.rs` | feature-owned behavior | fail-closed source selection |
| `native/src/lsl_panel_runtime.rs` | already-public product adapter | JNI endpoint consumed by the later LSL panel module |
| `native/src/lsl_rusty_outlet.rs` | already-public product adapter | selected Rusty-LSL backend only |
| `native/src/native_renderer_display_refresh_options.rs` | feature-owned behavior | optional display-refresh parser; default remains unchanged |
| `native/src/native_renderer_private_particle_material_request.rs` | feature-owned behavior | lock-gated request schema only |
| `native/src/native_renderer_private_particle_render_experiment_request.rs` | feature-owned behavior | lock-gated request schema only |
| `native/src/native_renderer_private_particle_visual_scale_request.rs` | feature-owned behavior | lock-gated request schema only |
| `native/src/openxr_controller_breath_adapter.rs` | feature-owned behavior | selected controller assessment adapter |
| `native/src/openxr_simultaneous_hands_controllers.rs` | neutral shared runtime substrate | explicit simultaneous-input state, disabled unless selected |
| `native/src/polar_acc_breath_adapter.rs` | already-public product adapter | Polar input projection behind the feature lock |
| `native/src/polar_composition_adapters.rs` | already-public product adapter | Polar-to-composition projection behind the feature lock |
| `native/src/private_particle_heartbeat_pulse_adapter.rs` | feature-owned behavior | optional private-particle source adapter |
| `native/src/private_particle_world_basis.rs` | feature-owned behavior | optional private-particle coordinate adapter |
| `native/src/same_apk_panel_action.rs` | already-public product adapter | typed, lock-gated panel action |
| `native/src/simultaneous_hands_controllers.rs` | neutral shared runtime substrate | bounded input-mode coordinator |

The remaining admitted paths are supporting closure, classified by exact path
family:

- `Cargo.toml`, `Cargo.lock`, and every path in
  `crates/rusty-quest-breath-contract/` are neutral shared runtime substrate.
- The changed existing files under `apps/native-renderer-android/native/`, the
  app manifest, and the listed Java adapters are already-public product
  adapters when they bind JNI/Android lifecycle, and feature-owned behavior
  when they parse or route an optional family.  Their source blobs are copied
  exactly from the public baseline; no value is taken from the private binding
  candidate.
- `fixtures/breath-contract/`, `fixtures/native-app-builds/`,
  `fixtures/native-app-features/`, and
  `fixtures/native-renderer/native-renderer-property-manifest.json` are the
  closed feature/property proof.  The four-way conformance build is included;
  damaged fixtures remain rejection evidence.
- `schemas/rusty.quest.native_app_build_resolution_result.v1.schema.json` and
  the selected build/profile/static-check tools are neutral validation and
  packaging substrate.  Device/operator/capture scripts are excluded.
- W-007A `panel-modules/`, `PanelModule*`, `PanelImmersiveHandoff`, and the
  `ControlPanelActivity` move are **rejected in this change** as later panel
  modularization.  Private binding candidate files, private evidence, and
  unrelated external-validation history are also rejected.  No current-main
  behavior is classified as superseded: the selected new behavior remains
  locked and default-inert.

## Scope and validation

The property manifest is ported as the exact `619` additions and `2` deletions
from the public baseline because it is the parser/build-lock closure for the
admitted sources.  Static checks must prove that default locks remain inert,
that damaged inputs reject, and that the native source/build closure is
consistent.  This document is source-composition evidence only; it is neither
device evidence nor permission to activate any optional capability.

The source-level compatibility repairs are equivalent `checked_div` arithmetic
in `native/build.rs` (retaining the public baseline's zero-divisor result of
`0`) and a standard-library `FRAC_1_SQRT_2` constant in a host test.  They
satisfy the current repository Clippy gate without changing runtime behavior.
