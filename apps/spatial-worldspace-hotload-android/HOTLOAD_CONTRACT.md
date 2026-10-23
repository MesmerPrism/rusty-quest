# Closed debug hotload harness

The debug app calls SDK 0.14 experimental `Scene.reloadShaders` for three
packaged, host-validated shader batches. `sdk-baseline` and `sdk-revert` replay
the same baseline SPIR-V bytes; `sdk-tint` changes fragment arithmetic and
`sdk-offset` changes vertex arithmetic. `sdk-stale` rejects an obsolete
generation before filesystem or SDK effects. The original workload app is
independent of this package.

`Prepare-SdkHotloadCatalog.py` requires explicit glslc, spirv-val, spirv-dis,
SDK header directory, plugin shader_config.json and output directory arguments.
It compiles eight vertex and four fragment variants per batch, including HSR
and multiview defines. Reflected descriptor, block/member, input/output and
push-constant ABI structures must equal baseline. Source/SPIR-V/ABI SHA256
identities are recorded in `hotload/catalog.json`. Compilation and validation
finish before publishing a fresh output directory. Existing output directories are refused and previous catalogs remain untouched. Generate the SDK catalog before
adding native diagnostic assets to the same output directory. Temporary cleanup verifies resolved paths and refuses link/reparse traversal.

Gradle `workloadHotloadAssets` points to the directory containing `hotload/`.
Inside each named batch the exact SDK relative names are `workload.vert.spv`,
`workload.vert.mv.spv`, `workload.vert.2uv.spv`,
`workload.vert.2uv.mv.spv`, `workload.vert.tangent.spv`,
`workload.vert.tangent.mv.spv`, `workload.vert.skin.spv`,
`workload.vert.skin.mv.spv`; fragments use no suffix, `.mv`, `.tangent`,
`.tangent.mv` before `.spv`. There is no `shaders/` prefix.

The controller validates all twelve named asset hashes and ABI identities
before copying to application-internal, per-session immutable batch directories.
Only packaged names are admitted. It exposes app/session/request/generation
identity and `api_invoked`. SDK offers no completion callback: these receipts
prove neither GPU completion/retirement nor sampled pixels. Scene material and
object identity counters remain observable; reload does not reconstruct them.
VR readiness and resumed activity are required by the shared command handler.

UI buttons and debug DUMP plus shell-UID provider calls share handlers. No
generic watcher or arbitrary app-owned file/path/code route is registered.
The separately selected debug SDK graphics workflow below registers the stock
SDK development broadcast; it is not the DUMP-gated operator provider.
Release excludes the provider and shader controller. `texture-rebind` separately
creates one fixed square-mask texture and invokes material texture setters;
`texture-revert` restores the original texture. At most two mask textures remain
retained for normal SDK teardown. Setter invocation is not shader reload or
pixel proof. Procedural geometry replacement is outside this slice.

The separately named debug [topology binding comparison](TOPOLOGY_SCALE.md#retained-setter-versus-fresh-construction)
uses one existing cached P512 mesh and compares retained-object setters with
fresh-object construction. It is an opt-in SDK topology diagnostic, not a
generic geometry/source loader or an SDK shader reload. App-owned hotload
controls are blocked while it is armed; the selected stock SDK graphics
broadcast remains an external unauthenticated development route.

## Opt-in fresh SDK graphics source

`Build-SpatialWorldspaceHotload.ps1 -SdkGraphicsHotReload` records the choice in
the build-input lock and supplies `workloadSdkGraphicsHotReload=true`. Only
that debug selection registers the existing SDK0.14 `HotReloadFeature`;
the default remains off, release selection is refused, and the release facade
stays inert. The normal owning build wrapper can forward this switch through
its existing builder-argument map; no private kernel/source edit is involved.

See [FRESH_SDK_GRAPHICS.md](FRESH_SDK_GRAPHICS.md) for the fixed twelve-file
fresh-source prepare/upload/trigger/observe/revert workflow. It reuses SDK
graphics reload rather than adding a loader or private state migration.
Stock SDK debug receiver admission is unauthenticated and uploads are
non-atomic; neither becomes a trusted product authority surface. Host ABI
refusal prevents that batch's upload, not arbitrary SDK/native input execution.

`compute-a`, `compute-b`, `compute-revert`, `compute-bad-abi`,
`compute-malformed`, `compute-stale` and `compute-rapid` are forwarded only with
an active GPU producer. Native receipts describe preparation, selection,
submission and fence completion separately. This public diagnostic slot never
replaces the private workload kernel. Rapid requests can be rejected while a
transaction is pending; a successful transport call is not native activation.
Compute controls require a current ready producer with initialized output and
actual submission/fence evidence. Missing or unreadable native status returns
a blocked receipt. Each UI/provider invocation allocates a fresh request ID;
live replay is not exposed. Replay and expired-ID rejection are host-tested.

Packet v4 keeps diagnostic headers 40–45 and two primary plus two auxiliary records
after graft geometry. Shader group 3 decodes these as diagnostic circle
billboards, independently of private particle offsets. CPU reference keeps
diagnostics inactive. Device proof still requires attended before/after views,
both eyes, depth occlusion, exact baseline restoration, identity preservation,
pause/resume and negative transactions after device ownership is available.

## Fresh public diagnostic compute

The debug-only external candidate route extends the native diagnostic slot;
it leaves the private workload and its state unchanged. See
[EXTERNAL_HOTLOAD_TRANSFER.md](EXTERNAL_HOTLOAD_TRANSFER.md) for the closed
operator commands and exact transfer sequence. The supplied example is
`app/src/debug/fixtures/hotload_external.comp`: new trajectory arithmetic and
a time-dependent color branch, compiled after packaging. It is not an APK
asset or a source for the SDK graphics catalog.

The fixed ABI is Vulkan 1.1 / SPIR-V 1.3, one `GLCompute main` entry,
`LocalSize 2 1 1`, one write-only set 0 binding 0 StorageBuffer containing a
runtime array of float32 vec4 with stride 16 and member offset 0, and a
32-byte push block (uvec4 offset 0, vec4 offset 16). The owner allocates 128
bytes and dispatches once. Each of two invocations must write four complete
vec4 rows: position/radius, color, normal/active, auxiliary/active. Input
builtins are limited to the ordinary compute invocation/workgroup indices.

`Prepare-ExternalHotloadKernel.py` takes explicit compiler, validator and
disassembler paths, hashes them before/after execution, runs `spirv-val`, and
reflects the actual candidate's types, variables and layout decorations. The
canonical ABI JSON and digest agree with `hotload_spirv_contract.h`. The
host receipt binds the actual source, SPIR-V, manifest and disassembly. The
device independently checks bounded bytes, SHA-256, header and instruction
extents, entrypoint, execution mode, resources and layout graph. It never
trusts the manifest ABI digest alone. The on-device parser is not a complete
SPIR-V semantic validator. Neither host validation nor the fixed descriptor
range proves termination, output bounds or meaningful values; trusted,
reviewed public diagnostic source remains required.

Staging owns an immutable copy without invoking Vulkan shader/pipeline
creation. Activation checks producer, expected kernel generation and active
digest, then prepares only after the owner's previous fence completion and
successful command-buffer reset. Failed validation/preparation preserves
the active pipeline, generation, digest and output allocation. One pending
transaction prevents overlapping activation. An external pipeline has tag
2 / `external`; active identity never indexes the two-entry packaged catalog.

Activation retains the immediately previous completed pipeline. Explicit
`compute-rollback` swaps active and retained pipelines, so repeated rollback
can return to the external candidate. `compute-revert` instead prepares the
packaged A bytes. A third successful activation destroys only the older
retained pipeline at the same retirement boundary. Stop discards staged bytes
and cancels queued activation; the original owner teardown/reset policy
governs Vulkan destruction. Unknown or rejected requests cannot cancel a
different pending activation.

The producer stop transition and retirement/activation transition share the
same owner mutex. Stop sets the producer's running flag and stops the kernel
while holding that mutex. If Stop acquires it first, a worker already past an
earlier loop check cannot prepare/activate queued work at the later boundary.
If the boundary acquires it first, that activation precedes Stop. Cleanup
still owns GPU waits and destruction; Stop does not promise cancellation of
an already acquired frame or private workload command recording. A kernel
finishing initialization after Stop is published already stopped.

Native status separates active digest/tag/origin, active first/completed
serials, staged digest, retained digest/tag/origin and last rejection from the
transaction receipt. `old_pipeline_retired` means prior command references
have retired; `previous_pipeline_retained` explicitly records retention for
rollback. `completed` is a producer fence receipt, not SDK sampling or pixel
proof. Pre-admission JNI rejection is recorded separately and never enters
or cancels the native transaction ledger; the app still consumes its request
identity. No private kernel/state migration is exposed.

Focused tests are `Test-ExternalHotloadKernel.py` (host reflection and actual
byte fixtures), `Test-ExternalHotloadTransfer.py` (mocked transfer),
`hotload_spirv_contract_test.cpp` (actual-byte runtime interface checks),
`hotload_state_test.cpp` (transaction state), and
`hotload_kernel_external_test.cpp` (the production implementation linked to
test-only Vulkan/asset functions). The last test exercises active/retained
handles, prepare failure, rollback twice, replacement retirement, stop and
negative requests. A deterministic threaded fixture pauses the worker before
the production owner boundary, invokes the same Stop helper used by JNI, then
resumes it; the complementary ordering allows activation before Stop. Fake
Vulkan calls do not establish real driver acceptance,
command-buffer reset, fence completion or visible output.
