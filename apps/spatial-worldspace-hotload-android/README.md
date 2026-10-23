# Spatial hotload test app

This separate debug app lets a wearer select prepared SDK shader and stateless native compute replacements without reinstalling. Original workload APK and application remain preserved. Use the catalogued presets first; source changes are prepared on the host into a new immutable input directory.

- SDK baseline/tint/vertex-offset/revert use actual Scene.reloadShaders with all compatible vertex/fragment variants. Typed receipts say API invoked; pixels and stable scene identities must establish effective reload.
- Native A/B/revert use an independent two-point stateless compute slot, while the real private simulation stays unchanged. Pipeline activation occurs after the prior fence succeeds and recorded commands reset; submitted and completed receipts name actual queue serials.
- Wrong-ABI/malformed/stale/busy/rapid/replay probes test bounded rejection and preservation of active code. Malformed or incompatible code never reaches Vulkan.
- Texture checker/revert explicitly rebind retained SDK textures, a separate API test. Procedural scene reconstruction and stateful private-kernel migration are not implemented.

[Native contract](NATIVE_HOTLOAD_CONTRACT.md), [SDK controls](HOTLOAD_CONTRACT.md), and the test matrix define observations. Debug-only catalog/controls are absent in release; its native diagnostic producer reports unavailable rather than substituting a fake model. No device results are implied by host compilation.

The locked builder Build-SpatialWorldspaceHotload.ps1 additionally requires HotloadInputsDirectory with assets/hotload/catalog.json, fixed native SPIR-V assets and native-catalog/hotload_catalog.h. Every input is hashed before/after. Optional IntermediateRoot and OutputRoot explicitly select short host directories and are bound in the input fingerprint; use a volume with sufficient free space. Prepare-SdkHotloadCatalog.py and Compile-HotloadKernels.ps1 generate these host artifacts; existing catalogs are immutable. Neither tool installs, launches or contacts a headset. The stock Gradle hotReload task is not used.

## Data and materials

`app/src/main/cpp/atlas_packet.h` defines RGB little-endian packet version4 (RWA4):1024x1024 RGBA8,64 header texels,8 texels/24 bytes per record, alpha255. The consumer restores sRGB byte values and uses nearest `texelFetch`. Coordinates are raw OpenXR right-handed floor metres; the vertex shader negates Z into SDK logical coordinates, then applies the SDK0.14 `adjustPosition` renderer convention exactly once. The host-geometry negative follows the default SDK-uploaded vertex path instead.

The startup manifest declares genuine main2562/tracer17934 capacity, optional retained particle records, matching primary/AUX record counts, and expanded hand/graft triangle corners. The manifest and admitted hand metadata determine exact ranges within131064 available records. Runtime geometry chunks have fewer than65535 vertices; expanded triangle chunks remain divisible by3. Startup asset `assets` entries bind path, byte length and SHA256. Private computation, data and masks stay in their owning external input packet.

Particles preserve transported RGB/alpha, radius, rotation and mask animation. Tracers are snapshot billboards, not segments. The64-layer mask uses8x8 tiles of128x128 pixels. The session-core composition uses its admitted render style, linear mask sampling and premultiplied particle material; the generic diagnostic composition uses nearest mask sampling and additive particles. Particle depth test is LESS_OR_EQUAL with disabled depth writes. Hand and graft triangles use opaque depth-writing materials and diagnostic cyan/orange tints so they can be distinguished from SDK interaction visuals. A switchable gray opaque rectangle provides a virtual depth reference. These settings require device visual validation; no room/environment-depth occlusion is implemented.

The default source is a fixed CPU64-marker transport reference. P64 topology submits256 vertices/384 indices; hand and graft production starts disabled. Selecting GPU explicitly starts the genuine external compute backend. [Topology capability controls](TOPOLOGY_SCALE.md) prepare separate particle, hand, graft or combined ladders, hold the producer configuration fixed, and replace actual compact mesh/index subsets. Particle rendering has no hand-mesh dependency. Status separates resident simulation capacity, producer masks, requested/applied topology and cached allocations. Culling is disabled by default because shader displacement does not update host bounds; a deterministic behind-view carrier comparison isolates culling behavior while fixed references remain stationary. Setter receipts do not prove rendered pixels, work savings or performance.

The closed debug `topology-binding retained|fresh|off` comparison reuses one cached P512 mesh on the CPU source to distinguish retained-object mesh replacement from fresh-object construction. It allocates at most one additional object/entity, never calls `setSceneMesh` on that fresh object, and restores the retained slot by visibility only. See [the comparison contract](TOPOLOGY_SCALE.md#retained-setter-versus-fresh-construction) for guards, lifecycle invalidation, resource retention and the separate pixel oracle.

The debug atlas diagnostic is scoped to its current Activity owner and the main looper. A replacement Activity clears the old diagnostic before creating scene materials; late callbacks from the retired owner cannot create, mutate, inspect or detach the replacement diagnostic. Current-owner native failures still propagate. The release facade remains inert. `Test-DiagnosticOwner.ps1 -CompilerClasspath <explicit JARs> -Java <JDK17 java> -OutputDirectory <fresh directory>` compiles the actual debug and release facades against test-only call recorders, covering replacement startup before diagnostic creation, stale owner callbacks/detach, thread refusal and reentrant constructor publication. Host results do not prove native resource retirement or successful device recreation.

See [diagnostic presentation isolation](DIAGNOSTIC_ISOLATION.md) for the debug-only reversible visibility control used to clear public-word capture cells without changing the resident producer.

## What receipts prove

[Isolated cohort fixtures](COHORT_FIXTURES.md) exercise the actual workload shader with dedicated synthetic hand/graft triangles. Their static uploaded inputs and expected outcomes remain separate from genuine GPU publication evidence.

[Particle cohort fixtures](PARTICLE_COHORT_FIXTURES.md) independently exercise primary six-bit tags and AUX full32 frame/generation admission with one particle and a white mask. Select `prepare-scale particles` first; hands/grafts stay disabled in that ladder. Static fixture acceptance remains separate from live particle-source evidence.

Native submission/fence/timing status proves producer work only. `sdkSampledProof` and `controllerInputProof` remain false in machine status. The64 fixed barcode cells read the header in the SDK vertex stage: first32 cells are submitted-frame bits and next32 generation bits, low bits leftmost. Their flat vertex outputs are rendered as white/dark cells; invalid headers render red. Actual capture decoding or wearer observation can establish sampled frame tokens separately. Hand/graft records preserve XYZ and octahedral normals, with full32-bit pack frame and generation replacing their unused color/radius words. Generation0 marks inactive output; all three corners must match the packet before a triangle is admitted. Particle AUX frame/generation identify atlas publication, not completion inside the private kernel. Header17 is the exact recorded source-step candidate, header47 its source generation, header46 hand layout1, and flags bit4 records whether the adapter scheduled a source dispatch. These values describe submitted work; they are not GPU-completion or SDK-consumption receipts. Materials bind the expected generation and CPU/GPU source tag, rejecting stale packets after stop/restart. No automatic pixel-reading proof is claimed.

Producer timestamp queries, when actually available, separate compute time from atlas/color-wait time. The latter can include WSI waits. Neither measures SDK render cost. SDK FPS, capture cadence, thermal state and memory need independent observations. UI measurement intervals impose no automatic human test window.

`VRFeature(inputSystemType=INTERACTION_SDK)` registers SDK interaction systems (verified in the0.14 artifact), and Compose registers the control panel. This selects SDK hand/controller panel input without a simultaneous-hands/controllers requirement. Actual click, pinch, drag and tracking-loss behavior still require a wearer test.

Stop is asynchronous. The activity holds the retiring handle and carrier until a typed JSON `teardownCompleted:true` acknowledgment; pause/destruction continue retirement polling while forbidding restart. Focus loss and SDK VR shutdown detach live input before borrowed SDK context invalidation. Unknown native status retains the restart block.

## Reproducible source and build checks

`Test-WorkloadContract.ps1 -CompilerClasspath <explicit Kotlin compiler/runtime JARs> -Java <JDK17 java> -OutputDirectory <retained directory>` compiles and executes the actual platform-free `AtlasPacket.kt` and `WorkloadContractHostTest.kt`; no Gradle or headset is required. It checks RGB header/reference bytes, alpha, raw positions, AUX frame/generation, record addresses and static chunk budgets.

`Build-SpatialWorldspaceHotload.ps1` requires explicit Android SDK, JDK17, signing keystore, workload-input directory, OpenXR include directory and public hand shader path. `ExternalSourceDirectory` selects an external compute module; an explicit missing/invalid module is rejected. SessionCore or external workload hotload requires that source. With both selections off, omitting it selects the existing public `unavailable_workload.cpp` backend: CPU reference and SDK topology diagnostics work, while real GPU workload creation throws unavailable. This does not substitute a fake producer or enable private session services. The hashed input lock records `external_workload_backend=external|unavailable`; only selected external source is inventoried/staged and forwarded to CMake. `Test-WorkloadBackendSelection.ps1 -OutputDirectory <fresh directory>` executes the actual builder selector and refusal controls without invoking Gradle/CMake/device tools.

`-VerifyOnly` performs no build. The builder accepts Candidate only and requires committed clean source. It hashes all new app and declared external source/SPV/assets/header bytes before copying them into isolated staging. Candidate output is content-addressed and never overwritten. The accepted signer is pinned in `workload-build.lock.json`. Long build execution requires an active `apk-build:rusty-quest` lease. SDK/plugin0.14.0, Kotlin2.2.0, JDK17 and Gradle9.4.1 remain pinned.

Selecting SessionCore also selects CMake `ANDROID_STL=c++_shared`. The builder pins the arm64 runtime from the selected NDK sysroot, preserves its exact bytes during packaging, and checks the APK against that input. Its native dependency gate reads every packaged arm64 ELF's `DT_NEEDED` entries and requires each non-system dependency in the APK; system exemptions come only from the inventoried public NDK stubs for the selected Android API. The gate also checks ELF ABI and 16KiB LOAD alignment and retains `native-dependencies.json`. This is packaging evidence, not Android loader or device acceptance. Neutral builds retain their existing STL selection.

`Test-WorkloadNativeDependencies.ps1 -RuntimeMissingApk <original failing APK> -SessionCoreLibrary <canonical core library> -NdkRoot <selected NDK>` reproduces a missing C++ runtime rejection using actual ELF bytes. It tests dependency closure with the runtime added only to an in-memory map, plus malformed/incorrect ABI, alignment, and dependency cases. It never modifies an APK or runs a compiler/device command.

## Canonical session assets

SessionCore requires a prepared SDK session closure. Run `Prepare-SpatialSessionClosure.ps1 -SessionCompositionManifest <original selected composition> -PrivateAppIdentityManifest <selected identity> -OutputDirectory <fresh absolute directory>`, then pass the returned `session-composition.json` to the private input preparer and APK builder. Previously prepared input packets must not be retargeted: the new composition digest and clean public source selection require a fresh packet.

The preparer validates the actual core build anchors, provider, inventory and asset bytes. It produces a SDK-specific `assets/feature-lock.json` selecting the canonical session owner and binding the exact provider inventory, profile, activation, settings and source byte inventories. It contains no host paths. The unchanged canonical Android closure verifier consumes this asset. A native application's reference feature lock is not a substitute for the selected SDK closure.

The builder recomputes this lock from the admitted composition, binds its bytes in the build input lock, stages it with the provider assets, and verifies the final APK member against the selected bytes. Missing or substituted locks fail before building. Neutral builds do not select or package this session lock.

`Test-SpatialSessionClosure.ps1 -SessionCompositionManifest <prepared composition> -PrivateAppIdentityManifest <selected identity> -AssetsDirectory <prepared assets> -JavaHome <JDK17> -OutputDirectory <fresh absolute directory>` checks the actual build reader's positive case and missing, rehashed wrong-anchor and wrong-source-binding negatives. It compiles the exact selected, unchanged platform-free canonical closure verifier and audio contract with a neutral host main. The JVM checks all eight asset identities and materialization, plus exact missing-lock, anchor, mutated-profile and owner-selection rejection reasons. Retained receipts bind tools, source files, actual child exits and complete output. This is host closure evidence, not Android compilation, successful session initialization or headset acceptance.

## Debug-only operator

Authority `${applicationId}.probe-control` requires Android DUMP permission and Binder shell UID2000. Only closed commands are admitted: cpu/gpu/stop/status, boolean restoreSrgb/flipRows/hostGeometry/hands/grafts/simulate/publicationPaused/culling/occluder, enumerated mainCount/tracerCount, bounded visualScale/graftScale reset and measurementStart/measurementStop. Culling, occluder and interval UI buttons use the same app-owned handlers. Main-looper execution has a bounded wait and reports admission separately from observation. Release has no provider or debug operator class. The operator does not establish physical controller input or sampled pixels.

Example shape: `content call --uri content://<package>.probe-control --method mainCount --arg 512`. This is documentation, not authorization for any headset effects.

See [TEST_MATRIX.md](TEST_MATRIX.md) for pending device qualification. Host compilation does not establish appearance, latency, hand fidelity, performance, lifecycle behavior on hardware or general SDK scalability.

Finite clip positions use normal homogeneous clipping, including triangles crossing the eye or near plane. Packet/category/visibility validity still controls the flat drawable flag. Culling remains disabled by default and live vertices retain their world-coordinate placement; this clipping policy does not establish sampled atlas validity.

Publication pause is a closed debug control: after the producer fence retires, the worker stops before image acquisition, pose sampling, compute and packing. It retains the last Surface packet and the expected consumer generation; resume continues that generation. Requested/effective status and held submission/generation are distinct. Stop progresses while paused. This differs from source simulation pause, which can republish the same source step with fresh atlas and hand tokens. Neither producer fence retirement nor device idle proves SDK consumer release or final presentation retirement.

## Public CPU/JNI/DEX development scene

The explicit `Build-SpatialWorldspaceHotload.ps1 -DevelopmentFeedback` selection
uses a public procedural packet and the shared resident feedback host. It excludes
SessionCore, external workload/backend and submission observer selection. The old
genuine-workload parser remains separate; neither packet schema can activate the
other composition. No private rig, mask, simulation or scientific Java is needed.

Prepare immutable public inputs with the canonical app-local producer:

```text
python Prepare-DevelopmentFeedbackInputs.py --output <fresh-physical-directory>
```

Pass that directory as `-WorkloadInputsDirectory`, with the existing selected
SDK/native hotload catalogs, public hand shader, OpenXR headers, signer and
installed tool paths. The public packet is `development_scene.v1`: capacity512,
64 initially visible markers, no tracers/hands/grafts, and one procedural1024²
circle-mask texture. Source hashes and explicit public provenance bind the packet.
There are no fabricated hand buffers or private backend substitutes.

Only an explicit debug command-line selection compiles the shared core and
`android/development-feedback-host` Java sources into this app. The builder
inventories, stages and rechecks them. Release and unselected debug builds use an
inert adapter without shared loader linkage. The process-owned host retains code,
counter and buffer; each Activity owns its scene/texture resources independently.
The selected SDK adapter resolves its trusted framework application-files path
to its physical directory once per process. The shared host, session descriptor
and observation store receive that same directory; strict owner containment
checks remain in force for all derived paths and staged artifacts.
The selected shader build defines `RQ_DEVELOPMENT_FEEDBACK=1`, admitting the
public512/0 CPU packet with0..512 visible markers and no rig/tracers. The default
shader retains its genuine2562/17934 validation. Development selection suppresses
legacy barcode/occluder draws and omits the independent opaque atlas-byte grid;
public positions retain their actual RH floor coordinates at2m. Shader catalogs
must be freshly prepared with `Prepare-SdkHotloadCatalog.py --development-feedback`;
the builder rejects a catalog whose selection or original source hashes differ.
Preserve and remove stale external `app_assets` overrides before qualifying a new
packaged baseline: installation can retain those earlier runtime inputs.
Successful native/DEX computations update the existing CPU texture's marker
spacing and color. The projection uses the actual native value difference;
trusted DEX result projection subtracts input and the pre-dispatch host counter.
Differences use signed64 wraparound modulo2^64, including min/max inputs; the
visual mapper then bounds spacing and palette indices with positive remainders.
Accepted host computation cannot fail afterward from projection overflow.
With input4, implementation deltas5/10/fresh5 restore blue/orange/blue and the
original spacing while the native cumulative state remains5/15/20. Both state
and visual revision remain observable. Native result changes never replace SDK meshes, materials,
the OpenXR session or Vulkan owner. Scene recreation reprojects the retained
value; it is a separate lifecycle test, not part of each edit.

The existing shell-UID/DUMP provider admits these additional names only in the
selected build, with null `arg` and bounded String extras. Every provider feedback
call requires a fresh `request_id` String matching `[a-z0-9][a-z0-9-]{0,63}`;
the adapter removes it before shared method validation. UI and provider use the
same app handler and the shared controller's exact field validation:

| Provider method | Shared method / fields |
| --- | --- |
| `feedback_status`, `feedback_native_status` | status, no fields |
| `feedback_native_init` | `native_init`: `sha256` |
| `feedback_native_activate` | `native_activate`: `generation`, `sha256`, `probe_result` |
| `feedback_native_compute` | `native_compute`: `input` |
| `feedback_activate` | DEX `activate`: existing generation/hash/independent probe fields |
| `feedback_compute` | DEX `compute`: `input` |
| `feedback_select` | DEX `select`: `generation` |

Transfers remain separate. Use the shared host's reported logical session and
fixed app-private incoming paths; never overwrite resident code. A/B/fresh-A
must be independently compiled images, with trusted hashes and known answers.
Rejected candidates preserve the prior callable and state. Existing SDK graphics
and texture reload routes remain independent. GLB mapped-object replacement was
qualified in the separate `spatial-worldspace-probe-android` app and is not
silently claimed by this host.

The app atomically publishes `files/development-feedback/current-session.json`
with its PID, host session and native session. Each request claims its ID before
mutation and writes a create-once `hotload_observation.v1` at
`files/development-feedback-observations/<request_id>.json`. Its state is the
shared host snapshot; scene fields identify the Activity and completed texture
update revision. A duplicate or pending ID cannot repeat a mutation, including
after Activity recreation. After an operator timeout, read the same receipt via
`run-as` instead of retrying the mutation. An absent final receipt establishes no
completed outcome; a pending file can remain after a process exits mid-request.

`developmentFeedback` status joins shared state to an Activity-local visual
revision, requested value and the last texture update that returned. These are
not visible-pixel proof. Fresh captures/inspection must establish actual scene
adoption and timings. Keep enabling APK/setup cost separate from repeat compile,
transfer, verification, activation and effective feedback.

No INTERNET permission is requested. Examined SDK0.14 hotreload artifacts use
local file observation and the existing broadcast route. The package's generated
signature receiver permission remains bound to its own identity; inspect the
actual merged APK before device use.

`Test-DevelopmentFeedback.ps1` takes explicit Kotlin compiler classpath, Java,
Python and real JSON JAR paths. It executes the actual public producer/parser,
transport projection and existing packet/topology tests with source/tool hashes
before/after. Those checks establish no Android SDK rendering, device continuity,
safe GPU replacement or reclamation.


## Optional Operator development package

`Build-SpatialWorldspaceHotload.ps1 -DevelopmentFeedback -MetaXrOperator` selects the fixed coinstalled package `io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.operator`, retaining the original fully qualified Activity and provider handler. Supply exact `MetaXrOperatorFeatureLockPath`/`MetaXrOperatorFeatureLockSha256`, `MetaXrOperatorLibraryPath`/`MetaXrOperatorLibrarySha256`, and `MetaXrOperatorManifestPath`/`MetaXrOperatorManifestSha256` inputs. The unchanged shared `tools/lib/MetaXrOperatorPackaging.psm1` admits the layer and stages exact bytes. Its native feature-lock input is an Operator capability selection (`debug.meta_xr_operator`), **not** the SDK app composition or package provenance. The SDK build independently binds its fixed package, selected source and `OperatorDebugManifest.xml`.

Only selected debug packaging requests INTERNET and optional `com.oculus.experimental.enabled`, extracts native libraries and packages the API-layer JSON plus AArch64 library. The SDK route requires 16 KiB ELF LOAD alignment. Gradle rejects selected release tasks and unselected layer inputs; ordinary/release source selection uses an inert observer facade and preserves the original UI ordering. The existing default package and permission closure remain available without this switch. No capture AAR is required.

The original native Compute control moves above the digest fields only for the Operator package. It still calls the normal `feedbackUi`/`applyFeedbackRequest` handler with input 4. Provider/status calls cannot update `original_ui_queued_request_id`, `original_ui_completed_request_id` or `original_ui_completed_count`; these fields join a successful original Compute callback with the retained native state. Completion is observed when the handler returns; it is not submitted-frame proof.

Existing `feedback_native_status` observations add `scene.operator_layer_loaded` from a bounded 2 MiB read of this process's maps, requiring the exact library basename. `scene.input_geometry` reports the current original Compose widget, measured/clipped bounds, view dimensions, actual centered-quad dimensions and the ECS transform. Geometry is unavailable for a missing/nonvisible/retired panel, invalid pose, changed root dimensions or unsupported shape/pivot. It is labeled `evidence=sdk-ecs-panel-transform`, `submitted_frame=null`, `source_coordinate_system=sdk-left-handed-z-forward`, `coordinate_conversion=reflect-z`. Metric position reflects Z; orientation uses R*M*R (`[-qx,-qy,qz,qw]`). The raw SDK pose is retained separately. Common center/right/up/normal use OpenXR right-handed coordinates in LOCAL_FLOOR. Fresh live Operator reference-space conversion is required when choosing LOCAL or STAGE; the observer does not equate those spaces or prove rendered pixels.

Canonical builds accept the legacy `apk-build:rusty-quest` lease or the fixed isolated `apk-build:rusty-quest-sdk-hotload` resource. The latter additionally requires `-BuildCacheLeaseId` for the exact active Gradle-user-home cache, the same lease owner, and separate non-aliased cache/staging/output/source roots. Wrong, expired or foreign cache/build leases refuse before output staging. This does not reserve or relax any other build's resources.

Focused checks: `Test-SpatialOperator.ps1` exercises package/manifest and lease admission. `Test-DevelopmentFeedback.ps1` includes the host geometry tests for reflection, clipped targets, malformed poses and exact mapped-library matching. These checks do not establish headset input adoption. A later finite original-button pinch must separately prove one handler completion, preserved owner/allocation/state and actual pixels, followed by original-actor retirement and verified physical input baseline recovery.
