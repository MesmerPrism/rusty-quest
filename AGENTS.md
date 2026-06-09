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

## Read Order

1. `README.md`
2. `docs/ARCHITECTURE.md`
3. `docs/VALIDATION.md`
4. `fixtures/README.md`

## Agent Board

Read-only source inspection and dry-run profile validation do not require Agent
Board. Use Agent Board only when the user explicitly asks for shared-resource
coordination or when a task actually uses headset, ADB server, APK build,
logcat, screenshots, Perfetto, or shared bridge ports.

## Validation

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
```

