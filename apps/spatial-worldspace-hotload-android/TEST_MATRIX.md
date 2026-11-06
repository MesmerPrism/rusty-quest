# Workload qualification matrix

All hardware checks are pending for this new app. Existing two-point probe receipts remain distinct.

| Lane | Conditions and observations | Limit |
|---|---|---|
| Transport reference | CPU64 fixed markers; sRGB restoration; negative row flip and host positions; red invalid barcode | Tests codec/path, not private compute |
| GPU particles | Main64/512/2562, tracers0/2562/8967/17934; actual frame/generation barcode progression and stable color/mask/radius | SIM stays2562+17934 slots; visible record masking keeps81984 particle vertices submitted; no invented4096-real profile |
| Submitted topology | Record fixed81984 particle/13884 hand/69420 graft vertices plus260 diagnostics; verify whole hand/graft visibility requests separately | Visible record budgets are not a true submitted-vertex-count ladder; index-subset rebuild pending |
| Fidelity | Rotated billboard, mask-frame transitions, radius scaling, transported RGBA, additive overlap, tracer snapshots | Compare captured pixels and wearer observations with selected native reference; no formula reconstruction |
| Hands | Cyan GPU mesh alone, left/right/both, bind pose, wrist/finger motion, current-time pose freshness, loss/reacquisition | SDK interaction hands may coexist; toggles must establish which geometry was observed |
| Grafts | Orange expanded triangles, graft scale, anchor/barycentric attachment, tracking loss, hand-only/graft-only separation | Actual borrowed hand compute evidence, no CPU rig substitution |
| Virtual depth | Gray occluder on/off, particles behind/in front, hand/graft intersections, stereo parallax | No physical-room environment occlusion |
| Bounds | Default culling disabled; negative static culling while moving head/object | No dynamically updated CPU bounds claimed |
| Timing | Producer computeGpuNs/atlasAndColorWaitGpuNs with query availability/submission; independently record SDK FPS/capture cadence/thermal/memory | Producer timestamp does not include SDK renderer and atlas interval may include WSI wait |
| Latency | Actual captured barcode frame/gen, submitted/fence receipt and hand pose monotonic/XrTime; capture-clock relation retained | No automatic predicted-display-time or photons latency estimate |
| Lifecycle | GPU stop/restart, CPU/GPU switch, pause/resume, focus loss, SDK shutdown; typed retirement acknowledgment before restart | No success from request-only or process absence |
| Human controls | Hand pinch OR controller trigger on Compose; workload budgets, scale toggles, reset and unrestricted-duration user measurement interval | Debug provider is not human input proof |
| Editor/hot reload | Supported SDK debug hot-reload shader route; verify dynamic mesh/material reload and frame barcode continuity | Pending: Editor not installed; no claim of Editor validation or candidate replacement |
| Release boundary | Release provider/classes absent; selected external payload hashes; source/public-private boundary scan | Packaging proof separate from hardware |

## Direct Vulkan access inventory

Audited SDK0.14 public `Scene`, `SceneMesh`, `SceneObject` and `SceneMaterial` APIs expose scene meshes/materials/textures and borrowed OpenXR handles. No public VkDevice/VkQueue/VkCommandBuffer or native draw callback was found in those inspected Java API surfaces. This bounded inventory does not prove that no private/internal native hook exists. The implemented route therefore uses an independently owned Vulkan compute/atlas producer and SDK vertex decoding; it makes no claim of sharing the SDK renderer's Vulkan device. Any direct native draw integration requires a separately supported/public contract before implementation.


## Hotload harness live sequence

In this isolated debug app, use CPU reference for SDK-only tests first, then GPU source for native compute. Capture app/process/session and object identities before each phase. Human tests have no automatic closing timer.

1. SDK baseline -> tint -> vertex offset -> revert; keep the same material/mesh identities. Observe both eyes, depth/parallax and baseline restoration. API-invoked receipts are not pixel proof.
2. Texture checker -> texture revert; distinguish explicit texture rebind from shader reload. Both textures remain retained, no unsafe manual SDK object destruction.
3. GPU source -> native B -> native A/revert; observe the two public diagnostic billboards and independent real private simulation. Join active kernel generation/digest to first submitted and completed serial, then captured pixels.
4. Wrong ABI, malformed, stale generation and rapid/busy requests: expected typed rejection, advancing old code and no driver submission of incompatible blobs. Replay/conflicting-ID/expired-ID coverage is host-only; current UI/provider calls always allocate a fresh native request ID.
5. Repeat swaps during normal frames, pause/resume, focus loss and stop/restart; no pending pipeline may be destroyed before its fence and command references retire. Pause stops the GPU producer; after resume and acknowledged retirement, manually start it again. Private simulation state restarts although kernel bytes remain unchanged. Native stateful migration/reset is outside this stateless test.

Host checks validate the compiled presets, failure preservation, ABI and source identities. Dynamic SDK cache/rebinding and GPU retirement claims require the exact artifact's headset evidence. These presets intentionally test known compatible code. The separate fresh-source diagnostic route below admits trusted public compute through a bounded fixed ABI.

## Fresh native diagnostic source sequence

This is the prospective successor test, separate from earlier SDK shader and
packaged native A/B qualification. Run outside a measurement interval or
active bounds comparison. Leave the wearer test open until the wearer says
done; it has no automatic deadline. Never reset the private workload merely
to show a diagnostic shader change.

1. Start the GPU producer and retain ready status, actual active digest/tag,
   kernel and producer generations, private frame
   progression, active first/completed serials and shader-module/pipeline
   create-call counters. Obtain a fresh `compute-info` context; compile the
   public `hotload_external.comp` example with the exact selected tools and
   preserve source, disassembly, validator and reflection receipts.
2. Transfer exact bytes and read them back before the single stage request.
   Stage must report the actual candidate digest while the active digest,
   generation and driver-create counters stay unchanged. Explicitly activate
   that digest once. Observe queued/activated/submitted/completed separately;
   join its new generation and active first/completed serials to diagnostic
   motion/color in captured pixels and wearer observations. A received
   provider result alone proves none of those later effects.
3. Explicit rollback must restore the retained A pipeline/digest; a second
   rollback must restore the same external pipeline/digest, each with a new
   kernel generation. Output allocation preservation is separately checked by
   the production-code host test; observe continued private workload frames.
   Packaged `compute-revert` is a separate fresh preparation of A. Check
   the native retained digest/tag and create-call counters against that
   distinction; confirm visible baseline restoration.
4. From the actual host-validated compiled candidate, prepare the closed
   `malformed-instruction` and `wrong-binding` fixtures, each with a fresh
   context and actual invalid-byte digest. Their receipts explicitly expect
   validation rejection and claim no validator pass for the mutated bytes.
   Transfer and stage only. Require native `external_spirv_instruction` or
   `external_spirv_output_layout` rejection, joined to the exact bad digest.
   Active digest/generation and driver-create counters must remain
   unchanged while producer submissions/completions continue. Do not activate
   a negative candidate if it is unexpectedly staged; preserve evidence and
   discard it explicitly.
5. Retain a previously prepared context, then activate another valid candidate
   and submit the stale context: it must reject before preparing a new
   pipeline. An unknown digest must reject; distinguish app-local rejection
   from native rejection in receipts. Pending/busy and exact replay are
   covered by production-code host tests; provider calls allocate fresh
   request identities. Unknown transport results never trigger replay.
6. Stop with staged/queued work, acknowledge the existing retirement path,
   then resume via the ordinary manual producer restart. Expect the public
   staged candidate to be invalidated and baseline A to initialize. This
   restart also resets private simulation state; it is lifecycle evidence,
   not migration. No private kernel replacement, hand/graft change or state
   continuity across restart is claimed.

The production-code host tests use fake Vulkan calls to check rejection and
resource lifetime. Real compiler acceptance, Android/JNI integration, actual
driver acceptance/fences and SDK sampled pixels require their own receipts.
