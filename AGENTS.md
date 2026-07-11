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

`crates/rusty-quest-particle-adapter` is the accepted Quest-side handoff for
that family. It consumes Matter render payloads, Lattice situated anchors, and
Optics visual frames, then produces renderer-neutral instance rows and a
low-rate receipt. Spatial Camera Panel and native renderer are explicit
consumers; both remain disabled by default, and app policy, Vulkan resources,
private drivers, and high-rate control stay outside the adapter contract.

`crates/rusty-quest-hand-adapter` is the accepted Quest-side handoff for hand
substrates. It validates Lattice provider/frame identity, maps joints into the
Matter rig, checks prepared rows against the Matter CPU oracle, and preserves
Optics provider/frame/rig/hand identity. Native and Spatial acquisition and app
policy stay local; provider, basis, hand, rig, or joint substitution fails closed.

`crates/rusty-quest-broker-product` is only the Android projection boundary for
accepted Manifold broker product locks. Manifold owns product feature resolution,
runtime mode, commands, streams, modules, and the exact permission closure. Quest
maps that accepted permission enum into an exact manifest projection; it must not
union permissions, silently add optional capabilities, or accept a stale lock.
Camera, direct-P2P, and BLE products remain separate explicit opt-ins, while the
base broker stays camera/P2P/BLE-free.

`crates/rusty-quest-broker-authority` is the trusted local process/JNI
projection over `rusty-manifold-broker-adapter`. Standalone and embedded JNI
surfaces must pass the full typed invocation to the same Rust evaluator,
preserve its dispatch/application receipt and next snapshot, report
`local_acceptance_rules=false`, and name `module.runtime.host` as decision
owner. Java may validate bridge shape; it must not duplicate command, lease,
revision, replay, or rejection policy. These product-lock paths remain
non-default until a selected app package supplies their trusted local state.

Cross-app product admission uses the signature-scoped Binder service in
`apps/manifold-broker-android` and the thin
`crates/rusty-quest-broker-admission` projection. Android derives the immediate
caller UID, package, and signing-certificate SHA-256; Manifold owns the grant,
256-bit opaque token, capability subset, revision, replay, expiry, revocation,
and audit decision. The service must not contain capability/grant policy.
Device validation requires a same-signer lifecycle, a differently signed
permission denial, zero package fatals, and uninstall cleanup on every serial.

Independent product apps consume that surface through
`crates/rusty-quest-broker-client`. Each app must declare a distinct client id,
package subject, feature lock, marker namespace, and app-local sink capability;
the shared SDK may carry only the exact peer/media contract families and the
signature permission. Capability lists are canonical sorted sets. Repeated
service binding must preserve the live Manifold authority revision. Validate
native renderer and Spatial Camera Panel together with
`tools/Invoke-MultiAppBrokerClientTwoQuest.ps1`; require both lifecycles,
distinct Android app ids, no cross-marker/default/property bleed, zero
package/system fatals, and complete uninstall cleanup on both serials.

Product Wi-Fi Direct topology lives in `apps/direct-p2p-provider-android`.
Android Wi-Fi P2P owns credentialed temporary group formation,
`AndroidNetworkBindingProvider` reports whether the platform exposes a usable
`Network`, and the Rust native provider alone owns explicit `p2p0` bind,
bounded socket exchange, and close. A missing Android `Network` is a truthful
`network_available=false` receipt, not permission to fabricate a handle or
substitute Android socket ownership. The product app must not depend on the
connectivity-lab harness or enable media. Validate with
`tools/Invoke-DirectP2pProviderTwoQuest.ps1` and require both typed receipts,
inactive cleanup, and zero package/system fatals.

When peer-session gating is enabled, `rusty-quest-peer-session-adapter` only
projects authenticated BLE pair evidence into Manifold. The product must
validate Manifold's fresh topology authorization, exact current revision,
topology contract, and local peer role before initializing Wi-Fi P2P; rejected,
stale, expired, or revoked receipts must leave topology inactive. Validate the
decision matrix with `tools/Invoke-PeerSessionDecisionGateTwoQuest.ps1`.

The adapter's N-peer projection may combine a live authenticated Quest pair
with one sanitized configured-peer observation, but remains a proposer.
Manifold owns membership, coordinator, revision, route ranking, split-brain,
expiry, revocation, direct-lane eligibility, and audit. Termux and sidecar
inputs stay source/privacy/advisory only; they never authenticate a direct
route or carry media. Validate with
`tools/Invoke-NPeerMeshTwoQuestConfiguredPeer.ps1`.

Generic media adoption lives in `rusty-quest-media-stream`. Manifold owns the
accepted session/stream descriptor; the Quest runtime owns only receiver-first
platform lifecycle after the accepted decision. Sources, processors, direct-
P2P route references, and sinks are explicit, independently validated, and
free of app policy. `rusty-quest-remote-camera` remains a compatibility adapter
that maps into this runtime; do not copy its properties or defaults into new
source, processor, or sink descriptors.

For release-candidate broker recovery, distinguish client death from authority
process death. A stopped client may rebind to the existing authority revision;
after an explicit broker process stop, clients must rebuild from their exact
product locks and grants at a fresh authority epoch. In both cases replay and
post-revocation use must remain rejected, client UIDs and marker namespaces
must stay distinct, and cleanup must remove all test packages. Validate both
connected devices with
`tools/Invoke-BrokerAdmissionDeathRecoveryTwoQuest.ps1`; its dedicated 2D
clients avoid an unrelated 6DoF launch dependency, and its provider restart is
a deliberate safe rebuild, not evidence of persisted in-memory authority.

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
