# Static particle transform and culling calibration

Debug-only `particle-bounds` uses the existing immutable `matching` particle packet, white mask, four-vertex mesh and actual workload shader. It changes only that fixture's SceneObject transform/culling request and its own material's existing host-coordinate flag. It never changes the live producer, private data, normal workload material, shader source or topology. Hands and grafts are not part of this test.

Prepare the particle ladder, keep the live bounds probe/measurement inactive, and hide both cohort fixtures before starting. The shell/DUMP provider accepts only the following labels. Requests describe setters; they never accept their own visual outcomes.

| Command argument | Requested fixture state | Independently observed purpose |
|---|---|---|
| `packet-origin` | Immutable matching point, identity object transform, packet-position shader branch, culling off | Establish the known yellow positive in a clear region |
| `host-front` | Same packet/material/mesh; existing worldFromObject branch; translated host centre matches the packet's SDK point; culling off | Establish a visible host-transform positive on the same object |
| `host-behind` | Same branch, culling off; captured viewer-relative host translation eight metres behind | Confirm transform consumption through ordinary clip rejection, separately from culling |
| `packet-behind-off` | Retain that translation; return to immutable packet-position branch; culling off | Show decoded world placement is independent of carrier translation |
| `packet-behind-on` | Retain all geometry/material/texture state; request culling on | Observe whether this SDK path suppresses the known point |
| `packet-behind-off` | Same translation and packet; request culling off | Positive recovery bracket |
| `off` | Hide first, then request identity translation, packet branch and culling off | Explicit cleanup; available during pause, comparison or quarantine |

Each non-off step requires the preceding requested mode. This is sequencing, not visual approval. A missing host-front or packet-behind-off positive makes the subsequent negative uninterpretable; stop rather than infer a culling decision. The fixed point stays at raw floor (.65,1.35,-1); packet bytes are never rewritten. The host-front calculation uses SDK logical (.65,1.35,1), matching the unchanged workload shader's raw-to-SDK basis mapping. Actual placement equivalence still needs the two positive captures.

`particleCohortFixture.boundsCalibration` exposes the mode, requested branch/culling/translation, captured eye/forward, both conservative behind-depth calculations and the mesh-local SDK bound. Dedicated fixture material/mesh/object/entity handles remain in the ordinary fixture status. `effectiveWorldBoundsObserved`, `effectiveCullingObserved` and `transformPixelsObserved` remain false. The ordinary cohort's `expectedVisibleWhenSelected` is packet-case metadata and does not apply to these placement states; `ordinaryCohortVisibilityMetadataApplies` makes that explicit.

When status observes that viewer motion no longer keeps both the uploaded host envelope and the host-branch vertices behind, it hides the fixture and records invalidation. A setter/reset failure quarantines the fixture with its resources retained. `off` still attempts hide/reset but cannot clear quarantine. Ordinary non-off `particle-cohort` commands are blocked while this calibration is active; existing live bounds/measurement/prepare guards see the active fixture and remain closed. Lifecycle pause/teardown uses the existing fixture hide path.

Meta's 0.14 [SceneObject reference](https://developers.meta.com/vr/reference/spatial-sdk/v0.14.0/com_meta_spatial_runtime_sceneobject/) describes `shouldFrustumCull` as: “Whether this SceneObject can be frustum culled (default is true)”. Eligibility does not supply a cull-result or effective world-bound readback. A visible off/on/off sequence therefore supports only this concrete observation: whether requesting culling suppressed this calibrated shader-displaced object on the tested runtime. It cannot identify the closed renderer's internal bound-selection policy or establish performance, input/collision, consumer fence, physics completion or live packet correctness.

Focused host checks are included in `Test-WorkloadTopology.ps1`: closed ordering, actual matching-packet coordinates versus the host-front calculation, all 26 nonzero axis directions, and invalidation after a reversed view. These are arithmetic/contract tests; SDK execution and image assertions require a newly qualified APK and separate captured device observations. No change to the frozen predecessor APK is implied.
