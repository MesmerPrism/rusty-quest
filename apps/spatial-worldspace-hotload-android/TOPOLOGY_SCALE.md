# Submitted topology and bounds capability tests

This debug harness starts with P64 particle topology, hands and graft production disabled, and SDK frustum culling disabled. Particle behavior has no hand-mesh dependency. Hand, graft and combined capability tests are explicit modes. Simulation buffers, dispatch capacity, atlas dimensions and category offsets remain resident and fixed across each ladder. A producer mask is distinct from the number of vertices and indices submitted to the SDK.

The existing debug operator exposes the following closed commands through the same Activity handlers used by the panel. It retains DUMP permission, shell UID and debug-only admission. A completed setter receipt proves a requested SDK mutation, not pixels, draw count or timing.

| Command | Values / purpose |
| --- | --- |
| `prepare-scale` | `particles`, `hands`, `grafts`, `combined`, or `release` |
| `topology` | One profile in the currently prepared ladder |
| `bounds-probe` | `behind-off`, `behind-on`, `restore` |
| `publicationPaused` | `true` or `false`; freezes the producer pipeline at a publication boundary |
| `topology-binding` | `retained`, `fresh`, `off`; opt-in CPU/P512 construction comparison below |

`prepare-scale` configures full particle masks, enables only the selected ladder's hand/graft producers, and selects its first profile. It records the producer control revision. It does not reset, resize or replace the private simulation. Subsequent topology selections replace compact SceneMeshes on retained entity slots and leave producer configuration unchanged. Changing a producer mask, scale, simulation toggle or reset requires `release` first. Publication freeze is an explicit diagnostic exception that advances and reports the configuration revision; measurements reject a frozen producer. Releasing does not change the current producer configuration or active meshes.

| Ladder | Profiles | Submitted records |
| --- | --- | --- |
| Particle capability | `p64`, `p512`, `pfull` | Main prefixes of 64, 512, all |
| Particle capability | `t2562`, `t8967`, `tfull`, `retained` | All main plus tracer prefixes of 2562, 8967, all; final profile also includes retained particle records |
| Hand capability | `h32`, `h256`, `hleft`, `hright`, `hfull` | 32 or 256 complete triangles per rig; entire left, right, or both rigs |
| Graft capability | `hfull`, `g1`, `g2`, `g5` | Full base hands, then 1, 2 or 5 copies per rig alongside full base hands |
| Combined capability | `csmall`, `cfull` | P64 plus full base hands; then all particles, hands and grafts |

Hand triangle counts and ranges come from the SHA-verified 48-byte admitted metadata asset, checked against the manifest and per-rig asset lengths. Left and right counts need not match. Partial hand profiles select deterministic evenly spaced complete triangle triplets. Graft ranges use five left copies followed by five right copies, with each rig's own corner count. A request exceeding admitted topology is rejected; it is never silently clamped or routed to the other rig.

Each particle record submits four vertices/six indices. Hand and graft corner records each submit one vertex/index. Chunks contain at most 16,383 billboards or 65,532 triangle corners, retain complete triangles, and stay strictly below 65,535 vertices. Meshes are cached for reuse with fixed retention ceilings of 1,000,000 vertices and 1,500,000 indices; partial allocation failures consume that budget. Failed bindings attempt to restore the previous selection; uncertain restoration hides record objects and blocks further scaling until scene recreation. Cached allocation is reported separately from active topology. Normal scene teardown owns resource lifetime.

Status reports requested versus applied profile/counts, per-category counts, mesh revision, entity slots, cached allocations, simulation capacities, producer configuration revision and static host bounds. Applied counts mean accepted SDK calls; `gpuDrawCountObserved=false` remains explicit. The fixed barcode, gray reference and diagnostic quads are separately reported as 268 vertices/402 indices. Atlas diagnostics and UI geometry are excluded from both counts. Metadata topology checks are asset admission, not source-generation or wearer-pose evidence.

Runtime identity includes package, PID, process start elapsed time, Activity UUID/creation elapsed time, scene-ready count, owner epoch, SDK scene/material/object/mesh handles and fixed-reference entity IDs. Topology mesh handles may change intentionally; process, Activity, scene and fixed-reference identities can be compared across source/pause/bounds transitions. Handle strings preserve exact integers and are observational, not pixel or continuity proof by themselves.

## Retained setter versus fresh construction

`topology-binding` is a debug-only closed comparison, routed through the same Activity handler from the panel and DUMP plus shell-UID provider. It requires the CPU source with no owned or retiring GPU producer, a prepared particle ladder with its producer configuration revision unchanged, current P512, ready XR/resumed lifecycle, normal byte transport, and no bounds probe, measurement or cohort/isolation comparison. It does not start private computation, change the packet, create a new mesh, reload a shader or recreate the scene.

1. Inspect P64 with the fixed-grid shader oracle, then `prepare-scale particles` and `topology p512`. Inspect the retained-object result and pin the actual cached P512 mesh handle and input/shader bytes. The existing topology application invokes `setSceneMesh` on its retained slot.
2. `topology-binding retained` arms the comparison and keeps the retained slot visible **without invoking another mesh setter**. `topology-binding fresh` constructs at most one additional SceneObject and Entity using that exact cached P512 SceneMesh, registers it through the same SceneObjectSystem route, hides the retained slot and shows the fresh object. The fresh object is never passed to `setSceneMesh`, including on later toggles. Its current requested host transform and culling state match the original slot.
3. Compare retained/fresh repeatedly without allocation, reconfiguration or source/shader changes. `topology-binding off` hides the fresh object, restores the retained slot's visibility without rebinding, and releases the app control freeze. The extra entity/object remain strongly retained and hidden through normal scene teardown; the one-object budget cannot be reclaimed or bypassed by toggling off. Later P512 comparisons reuse that object and the same cached mesh.

While armed, app-owned source, configuration, topology, cohort, hotload, culling and transport controls are blocked. Status and Stop remain available; `off` can restore original visibility during pause. Stop, Activity pause/destruction and queued SDK XR-unavailable handling end the comparison and record invalidation rather than carrying a supposedly unchanged comparison across that lifecycle boundary. This is an app control freeze: the separately selected stock SDK HotReloadFeature broadcast is unauthenticated and cannot be frozen by this handler. Do not push or broadcast graphics changes between retained/fresh observations; pin the relevant files and capture actual lifecycle/identity state.

Status adds debug `topology.bindingComparison`: requested route, comparison revision, one-object allocation attempt, original/fresh entity/object identities, prepared/fresh-constructor mesh identity and zero fresh-object mesh-setter invocations. These describe actual app-owned calls and retained references. Visibility readback, effective GPU draw counts and pixel proof remain false; the existing `activeMeshHandles` still describes the selected topology plan, not a renderer binding getter. Distinct visible grid expansion must be established in the inspected capture ROI.

Any unknown partial construction, registration or visibility failure consumes the allocation attempt, quarantines the app path, attempts to hide every known returned object and blocks scaling/comparison until scene recreation. The created Entity remains retained. A constructor that throws after an internal native allocation may return no object handle; that native partial construction remains unknown and cannot be claimed hidden or reclaimed. There is no automatic allocation retry or guessed native rollback. Release rejects this command and has no operator provider or comparison status.

`python Test-TopologyBindingSource.py` checks source integration, closed admission and constructor/setter separation without invoking a compiler. `Test-TopologyBindingProbe.ps1 -CompilerClasspath <explicit JARs> -Java <JDK17 java> -OutputDirectory <fresh directory>` compiles the actual scene owner, packet and topology sources against test-only SDK call recorders and executes identity, zero-fresh-rebind, bounded reuse, transform/culling, release-refusal and partial-failure controls. Those host recorders do not emulate SDK rendering or prove the native defect; actual Android compilation and retained/fresh device observations remain separate evidence.

## Bounds comparison after packet validity

First establish current RWA4 generation, source tag, actual frame tokens and visible particle/hand validity. Use the known byte transport configuration (`restoreSrgb=true`, `flipRows=false`). The custom vertex shader decodes raw world-space geometry; entity translation is not part of that decoded position. Uploaded placeholder bounds therefore do not bound the displaced geometry. The inspected SDK 0.14 public API provides mesh-derived `computeCombinedBounds()` and per-object `shouldFrustumCull`, without an arbitrary bounds setter used by this harness. Culling stays disabled by default.

1. Prepare particles/P64 or hands/H32. Confirm visible valid geometry with identity carrier transforms and fixed references.
2. Invoke `behind-off`. The carrier translation is captured eye position minus eight metres along the captured unit forward vector. A conservative envelope of the uploaded placeholders (X/Y ±1, Z=0) must lie more than four metres behind that view. Only particle/hand/graft carrier transforms change; world decoded positions, fixed barcode, gray reference, diagnostic quads, panels and the opaque reference remain unchanged.
3. Invoke `behind-on`, then `behind-off` again without changing source, topology, producer configuration or transport. Record both eyes and fixed references. The same captured translation is retained throughout off/on/off. Observe whether the valid displaced geometry disappears only with SDK culling enabled. This tests actual scene culling behavior; a setter receipt alone cannot establish it.
4. Invoke `restore` and confirm identity placement with culling off. If the current viewer pose no longer leaves the conservative host envelope at least one metre behind, the poll cancels the test and restores identity. Stop/pause/source changes invalidate measurement intervals; pause/source changes also restore carrier placement.

For depth/input follow-up, keep packet validity fixed, test the opaque reference at known world depth, move the actual hand across it, and exercise left-only/right-only interaction independently of rendered GPU hands. The custom meshes do not redefine SDK hand tracking or colliders. Bounds, scene depth, transparency and interaction observations remain separate capability results.

`measurementStart` requires a prepared invariant configuration, live GPU source, no freeze or bounds probe, culling off and default transport. Topology/configuration changes are blocked during the interval. Its receipt measures only a user interval; collect independently attributed GPU/SDK observations before claiming scaling performance.

`Test-WorkloadTopology.ps1` compiles the actual dependency-free packet/topology sources and synthetic asymmetric-rig tests when compiler ownership is available. It covers category addresses, complete triplets, chunk boundaries, rejected malformed inputs, conservative behind-view bounds and exact generation/source-tag fields. It does not emulate SDK culling, render device pixels or prove private simulation behavior.
