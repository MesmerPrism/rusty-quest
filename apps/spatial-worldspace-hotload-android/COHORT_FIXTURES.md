# Isolated workload shader cohort fixtures

This suite covers hand/graft triplets. [Particle primary/AUX fixtures](PARTICLE_COHORT_FIXTURES.md) are a separate particle-only test route; passing this suite does not establish particle AUX admission. The two fixture controllers cannot be selected simultaneously, and both must be off/certain before live bounds or measurement comparisons.

Debug-only `cohort-fixture` uses the [existing debug operator](DEBUG_OPERATOR.md), with `off`, `hand:<case>` or `graft:<case>` as its argument. The fixture starts hidden and allocates on the first explicit selection. It binds the actual `workload` shader to dedicated opaque, double-sided three-vertex triangle meshes. Hand addresses are40992–40994; graft addresses40995–40997. Only the selected group is requested visible. Each uses the synthetic raw-world triangle (-0.05,1.3,-1.5), (0.05,1.3,-1.5), (0,1.4,-1.5) metres.

Its1024-square RGBA texture contains a valid sparse RWA4/version4 header, hand layout1, source-generation join and three hand/graft records. Particle capacities remain the required2562/17934; particle data is inactive. The fixture texture and materials never bind or mutate live producer data, private state, live material expectations or core lifecycle. The synthetic header's GPU flag is a test input, not evidence of a GPU-produced packet. Expected generation is split into exact16-bit material fields using the same helper as live rendering. Hand/graft paths do not sample the particle mask; their separate sampler is bound to the fixture texture to keep all shader bindings defined.

| Case | Deliberate difference | Required settled device result |
| --- | --- | --- |
| `matching` | Header frame65/gen65537; all three corners match | Whole triangle visible |
| `stale64alias` | All corner frames1 while header frame65 | No triangle |
| `mixedFrameCorner0`, `mixedFramePeer`, `mixedFrameCorner2` | Corner0,1,2 respectively has frame1 | No triangle, including either provoking-vertex convention |
| `mixedGenerationCorner0`, `mixedGenerationPeer`, `mixedGenerationCorner2` | Corner0,1,2 respectively has gen1, same low16 as65537 | No triangle |
| `inactiveCorner0`, `inactivePeer`, `inactiveCorner2` | Corner0,1,2 respectively has generation0 | No triangle |
| `highBits` | All frame/gen/header/material tokens0xffffffff | Whole triangle visible |
| `signBit` | All frame/gen/header/material tokens0x80000000 | Whole triangle visible |
| `retiredExpectedGeneration` | Material expects generation0 | No triangle |
| `restartStaleGeneration` | Material expects65538, header/corners65537 | No triangle |
| `expectedHigh16Mismatch` | Material expects1, header/corners65537 | No triangle |
| `cpuGpuGenerationAlias` | Generation1 everywhere, header GPU test bit on/material CPU | No triangle |
| `gpuCpuGenerationAlias` | Generation1 everywhere, header CPU/material GPU test bit on | No triangle |

Run each case for both `hand` and `graft`. Start and end each negative with its group's `matching` case and capture an `off` comparison. Use baseline SDK shaders, default byte restoration and a stable view with fixed references visible. Exclude overlapping live geometry from the observation using explicit existing controls; fixture selection itself never changes that geometry. Observe both eyes and confirm that disappearance is whole-triangle rejection, not clipping, occlusion, an absent object or a stale capture. The positive bracketing is mandatory for interpreting a negative. Source high-bit fixtures test consumer byte admission; they do not test the native producer's float-bit storage path.

Texture upload, material changes and visibility calls are separate SDK requests. The harness does not invent an atomic consumer update or completion fence: transient frames during a switch are inconclusive. Observe a settled case and its matching status revision. Status names `static_uploaded_synthetic`, expected result, exact requested header/material tokens, real SDK handles, selected topology and allocation/quarantine state. `shaderExecutionObserved` and `sdkSampledProof` remain false. Neither uploaded bytes nor a setter receipt is executed shader evidence. Genuine advancing GPU-source provenance and the live producer's pause/restart behavior must be tested separately.

Resources are retained: one texture, two materials/meshes/object slots, and at most18 immutable4MiB CPU packet buffers (72MiB when all cases have been visited). No allocation failure is retried; uncertain initialization/update/hide quarantines the fixture. `off` and Activity pause/detach request hidden objects; resume does not automatically reselect. Bounds/measurement intervals block fixture changes through the existing Activity guard. The source-only Kotlin tests in `Test-WorkloadTopology.ps1` check fixture byte construction and corner coverage, not shader execution. Actual SDK compilation and wearer/capture assertions remain pending.

Bounds and measurement starts require the fixture to be `off` and not quarantined. `off` remains available during an interval or pause, before the general Activity guards. A hidden request does not clear quarantine; a failed hide therefore cannot make a comparison eligible. These are application-state checks, not visibility readback from the SDK.
