# Particle primary/AUX shader fixtures

These debug-only static inputs exercise the actual `workload` particle branch, separately from [hand/graft fixtures](COHORT_FIXTURES.md) and from live GPU publication. They use their own atlas textures, fully white1024x1024 mask, additive particle material and one billboard (four vertices/six indices). There is no custom hand/graft geometry or private simulation data in this fixture. The material selects the generic particle style lane; it does not reproduce an owner's private render style.

Through the [debug operator](DEBUG_OPERATOR.md), first call `prepare-scale` with `particles`. This locks live producer drawHands=false/drawGrafts=false for the particle ladder; it does not resize/reset simulation or delete separate hand/graft capability modes. Then call `particle-cohort` with `matching`, another closed case below, or `off`. Non-off selection requires that particle-only preparation and the other fixture controller off/certain. Changing the prepared ladder is blocked while either fixture is active or quarantined. `off` is always admitted before normal Activity readiness/comparison guards. Release builds contain only inert facade methods.

The point is warm yellow at raw-world (.65,1.35,-1.0) metres, radius approximately.045m: in front/right of the startup gray reference, panel and diagnostic planes. Use a suitable viewer pose and confirm both-eye visibility; the placement alone does not prove lack of occlusion. Record0 carries the real primary color/radius/flags encoding; record20496 carries AUX rotation/animation/full32 frame/generation. Header hand/graft counts and enable bits are zero. The normal valid frame/gen is65/65537; the primary six-bit tag is1. The positive point uses the actual nearest-mask particle fragment path with an all-white mask, not a special diagnostic shader.

| Case | Changed input | Required settled device result |
| --- | --- | --- |
| `matching` | Primary tag and AUX/header/material all match | Whole billboard visible |
| `auxFrame64Alias` | AUX frame1 vs header65; primary tag still matches | No billboard |
| `auxGenerationLow16Alias` | AUX generation1 vs header65537 | No billboard |
| `auxGenerationZero` | AUX generation0 | No billboard |
| `primaryTagMismatch` | Primary tag2 vs header low-six bits1; AUX matches | No billboard |
| `primaryZero` | Entire primary record zero; AUX matches | No billboard |
| `primaryInactive` | Active bit cleared; other primary/AUX values valid | No billboard |
| `primaryInvalid` | Invalid flag set while active | No billboard |
| `auxRotationNaN` | Rotation bits0x7fc00000 | No billboard |
| `auxAnimationInfinity` | Animation bits0x7f800000 | No billboard |
| `retiredExpectedGeneration` | Material expects0 | No billboard |
| `restartStaleGeneration` | Material expects65538 vs header65537 | No billboard |
| `expectedHigh16Mismatch` | Material expects1 vs header65537 | No billboard |
| `cpuGpuGenerationAlias` | All generation1; header GPU test bit/material CPU | No billboard |
| `gpuCpuGenerationAlias` | All generation1; header CPU/material GPU test bit | No billboard |
| `highBits` | All full32 tokens0xffffffff; primary tag63 | Whole billboard visible |
| `signBit` | All full32 tokens0x80000000; primary tag0 | Whole billboard visible |

Bracket every negative with matching and off observations using baseline shaders and stable fixed references. Observe a settled selection in both eyes. Texture binding, material expectation and visibility calls are separate requests; transition frames are inconclusive. A synthetic GPU source test bit is not GPU producer provenance. Status identifies `static_uploaded_synthetic`, requested tokens/case/result, real SDK handles and topology; shaderExecutionObserved/sdkSampledProof stay false. Host wire tests do not execute the shader. Live publication tokens, producer freeze/restart, private simulation behavior and source styling still require their own observations.

Each case is uploaded once to its own immutable atlas texture and reused without re-upload. The independent white mask is also immutable. At most18 textures and18 retained4MiB CPU buffers are allocated (17 cases plus mask:72MiB each before implementation overhead). One material/mesh/entity/object is retained. Any allocation, registration, upload, binding or hide failure quarantines without retrying unknown allocations. Pause/detach requests hiding, and resume does not reselect. Both controllers must be off and not quarantined before bounds or measurement starts; hiding does not clear quarantine. Retained resources from the separate hand/graft suite are additional if it was used in the same scene.

`Test-WorkloadTopology.ps1` includes the real debug packet builder and `ParticleCohortFixtureHostTest`: exact primary/AUX word fixtures, category/header addresses, sign/high tokens, material16-bit splitting, empty hand/graft sections and every white-mask byte. Compilation, test execution and device assertions must be recorded separately; source preparation supplies no acceptance result.
