# Rusty Quest Agent Notes

This is the clean source repository for Rusty Quest. Keep committed content
self-contained and free of local-only planning paths, downstream app names, and
historical naming drift.

Rusty Morphospace is the top-level project/platform umbrella. This repo remains
the Quest lane inside that umbrella: Quest platform behavior, launch settings,
permissions, device/runtime profiles, Horizon tooling boundaries, and
Quest-hosted operator app validation. Do not introduce `rusty.morphospace.*`
schemas here; use `rusty.quest.*` for Quest platform contracts.

Project-owned source in this repo is licensed `AGPL-3.0-or-later`. Platform
SDKs, APKs, generated binaries, headset logs, and tool downloads need separate
provenance and notice handling.

## Purpose

Rusty Quest owns platform profile contracts and write/readback transports. It
does not own Makepad widget implementation, Matter simulation truth, Optics
appearance truth, Manifold command authority, or Lattice relation contracts.

## Runtime Surface Default

For new Quest runtime work, prefer native OpenXR/Vulkan and Meta Spatial SDK
apps in this repo. Keep reusable hand, space, mesh, visual, command, and report
contracts in Lattice, Matter, Optics, Manifold, GUI, and Hostess before adding
Quest adapters.

Do not add new Makepad compatibility shims, profile surfaces, or Quest-Makepad
parity work here unless the user explicitly asks for Makepad migration,
regression repair, or historical evidence replay. When old Makepad evidence is
useful, port the accepted contract, marker, fixture, or scorecard shape into a
native Quest path.

## Read Order

1. `README.md`
2. `docs/ARCHITECTURE.md`
3. `docs/VALIDATION.md`
4. `fixtures/README.md`

For work in `apps/spatial-camera-panel-android`, then read its
`morphospace/project.spec.json`, `feature.lock.json`, `workspace.state.json`,
and the current iteration unit before source. That app is the first downstream
adopter of the portable project/module workflow: the panel shell is the only
workflow-selected baseline, nearby particle/hand/camera/media/asset/room
families are explicit disabled entries, and unlisted features remain inert.

For the Spatial surface-particle candidate, reuse Matter's existing particle
and surface-runtime contracts. Matter owns state, simulation, force-source
selection, deterministic diagnostics, snapshots, and render-neutral payloads;
Lattice owns situated relation snapshots; Optics owns appearance/projection;
Quest owns Vulkan/Spatial/Android adapters and effective markers; the app owns
composition and private policy. Do not create a parallel app-derived particle
schema or move renderer/platform code into Matter.

## Agent Board

Read-only source inspection and dry-run profile validation do not require Agent
Board. Use Agent Board only when the user explicitly asks for shared-resource
coordination or when a task actually uses headset, ADB lifecycle, APK build,
logcat, screenshots, Perfetto, or shared bridge ports.

Routine device ADB commands must be serial-scoped with `adb -s <serial>` or the
wrapper `-Serial`/`RUSTY_QUEST_SERIAL` inputs. Reserve `quest:<serial>` for
same-headset install, launch, screenshot, headset-bound logcat, Perfetto, and
runtime validation. Reserve `adb-server:lifecycle` only for disruptive daemon
operations such as `adb kill-server`, `adb start-server`, reconnect/recovery,
Wi-Fi ADB setup, or ADB server path/port ownership changes; do not serialize
ordinary serial-scoped ADB work behind a global `adb-server` lease.

## Sustainable Design Guardrails

- Treat monolithic file pressure as an ownership problem, not a line-count
  problem. Split only by durable authority, schema, route, validation, adapter,
  or test-family boundaries; preserve facades, schema IDs, serde fields,
  fixture outputs, CLI behavior, validation outcomes, and dependency boundaries.
- Keep Quest runtime features explicit opt-in. Native OpenXR/Vulkan and Meta
  Spatial SDK modules may be present in the source tree, but they must not
  affect an app package, permissions, runtime profile, scene graph, input route,
  marker stream, media path, or private payload behavior unless a feature
  descriptor, app spec, runtime profile, Android property, or intent extra
  explicitly enables that feature.
- After a split, update the nearest distributed file map: this `AGENTS.md`,
  `README.md`, `docs/ARCHITECTURE.md`, fixture docs, validation docs, or the
  planning `agent-state\iteration-events.jsonl`.
- Keep `AGENTS.md`, README, and skill files as concise routing indexes. Move
  lane-specific recipes, device/build detail, compatibility ledgers, and long
  validation flows into named docs or runbooks.
- Keep legacy Rusty-XR names as explicit compatibility surfaces only. New
  schemas, routes, and types use the owning lane (`rusty.manifold.*`,
  `rusty.lattice.*`, `rusty.matter.*`, `rusty.optics.*`, `rusty.quest.*`, or
  repo-local names); do not introduce `rusty.morphospace.*` schemas or
  `Morphospace*` core types by default.
- Android property writes are transport generated from validated
  `rusty.quest.runtime_profile.v1` inputs. `getprop` readback proves only the
  transport layer; the consuming app must also emit the matching effective
  setting, marker, or command receipt before the value counts as accepted
  runtime behavior.

## Validation

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
```

The Spatial Camera Panel wrapper runs its focused workflow gate before the
large legacy static ledger:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-SpatialCameraPanelWorkflowStatic.ps1 -RepoRoot .
```
