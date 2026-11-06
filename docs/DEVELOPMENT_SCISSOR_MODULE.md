# Optional development scissor module

`SpatialScissorModule` is an optional app-owned adapter for the pure CPU table
defined in [scissor.h](../examples/development-feedback-native/scissor/scissor.h).
It owns no provider, worker, SDK object, Vulkan resource, session or authority.
Without explicit activation the renderer keeps its existing compiled scissor
calculation. An explicit `static` directive restores that calculation.

## Composition and staging

An existing developer addon transport may select `development_scissor_module`
with these closed fields. Omission leaves the current selection unchanged.
The transport must validate the whole profile before applying effects.

| Mode | generation | sha256 | policy |
| --- | --- | --- | --- |
| `activate` | Positive signed 64-bit integer | Lowercase SHA-256 | `product` or `inset_one_pixel` |
| `select_retained` | Positive signed 64-bit integer | Absent | Absent |
| `static`, `status` | Absent | Absent | Absent |

The Java facade accepts absent generation as zero. Its `apply(Context, Executor,
String profileSha256, String mode, long generation, String sha256, String policy)`
returns the exact `CompletableFuture<String>` that the addon owner must track
and cancel when closing. Supply the existing bounded owner executor. There is
no new executor, queue, caller-runs fallback, provider or synchronous admission
entrypoint. Static/status complete immediately. A rejected executor produces a
completed rejection receipt.

The existing upload owner calls `stageOnOwnerWorker(Context, long generation,
String sha256, byte[] bytes)` on its worker. The facade verifies the hash and
publishes a readonly, create-new fixed file below
`getNoBackupFilesDir()/development-scissor-v1/incoming/generation-<N>.so`.
Activation copies it into a unique process session below
`getCodeCacheDir()/development-scissor-v1/process-<UUID>/generation-<N>-<sha>.so`.
No caller path, class name or trust boolean is accepted. Files are at most
4 MiB. Existing generation bytes are never replaced; hashes are checked before
and after native admission. Facade writers serialize staging, with no native
or module callbacks while holding that staging monitor.

These are trusted same-UID developer images, not a native-code sandbox or a
defense against malicious filesystem writers. Loading can execute constructors
before header checks. Platform loading restrictions still require app-UID
device evidence. Process-session files remain inspectable; storage cleanup
belongs to the existing owner after process exit.

## Selection, cancellation and renderer use

The native client checks the actual image hash, version, table size, contract,
generation, function pointer and bounded scratch behavior before publication.
`product` matches the existing finite-input calculation; `inset_one_pixel` is
the explicit preview variant, reducing each axis by one pixel at either edge
when its computed size exceeds two. The ABI accepts scalars and borrowed POD
output only. Images must match the process ABI and must not unwind or retain
pointers. The [neutral proof](../examples/development-feedback-native/README.md)
documents the table, probes and fixtures.

Fresh activation is monotonic. A selected image can be selected again; an
older admitted image requires explicit `select_retained`. Rejected admissions
preserve the previous selection. Every successfully opened image stays resident
until process exit, including rejected or cancelled candidates. There is no
`dlclose`/`FreeLibrary`. Admission is bounded to 64 load attempts and 64 MiB of
charged image bytes per process. These limits apply only to this optional
adapter. Metadata publication retains only records announced in 64 fixed frame
hazard slots: at most 64 retired records and one current record survive a
publication, independent of the number of selections. A paused reader retains
its own record, not every intervening selection. Unprotected records retire on
the next publication. An in-flight snapshot owns its current record separately
and releases the control mutex before copying receipt fields. Snapshots never
occupy frame slots. If all 64 frame read sections are occupied, that call uses
the compiled fallback without waiting for a reader. Pointer revalidation may
retry under concurrent publication; this is not a hard wait-free guarantee.

The exact future's cancellation and native pointer publication share one
linearization point. Cancellation before publication prevents later publication,
including a discarded queued runnable or an already running probe. Publication
winning first makes cancellation return false. Explicit static mode also
cancels pending admissions. A native image already opened remains resident.
No authority/control mutex is held across file I/O, constructors, getters or
module calls.

`spatial_video_projection::target_rect_to_scissor` calls the selected function
immediately before constructing the Vulkan scissor used by the existing draw.
The frame path performs no allocation, file I/O, lock acquisition or Java
callback. Invalid output falls back to the original compiled calculation.
Code lifetime is independent of selection metadata and SDK lifecycle.

## Effective readback and validation limits

`status(profileSha256)` and control replies use
`rusty.quest.development.scissor.readback.v1`. They report the actual native PID,
selected generation, monotonically increasing selection revision, artifact
hash, selected raw-profile hash, policy, busy state and retained-image count.
`renderer_calls` counts successful renderer-helper calls, excluding admission
probes. `last_computed` reports one coherent successful call's generation,
selection revision, extent, input float bits and output pixels. It may refer to
the preceding selection until the new selection has actually been used; compare
both generation and revision. Concurrent sample writers may skip a telemetry
sample. A selection receipt alone proves no renderer use. A computed scissor
proves calculation, not GPU submission, visible pixels or SDK continuity.

Java staging/executor failures use `rusty.quest.development.scissor.failure.v1`
with a finite reason and nested actual selection readback. No arbitrary paths
or exception messages enter these receipts.

Run the focused tests with existing immutable scissor images and existing host
dependency suppliers. Each output must be a fresh directory under `target/`:

```powershell
python tools/checks/Test-SpatialScissorModuleHost.py --output target/scissor-core-proof --host-deps <deps> --module-manifest <manifest.tsv>
python tools/checks/Test-SpatialScissorFacadeHost.py --output target/scissor-facade-proof --host-deps <deps> --java-home <jdk17> --module-manifest <manifest.tsv>
```

The first executes actual core/image selection, cancellation, renderer-helper
dispatch, concurrent telemetry and metadata retirement. The second executes
the exact Java facade/JNI/core with real libraries and `-Xcheck:jni`; it enables
the Android JNI child on the host and models only Context directories and Vulkan
PODs. These focused checks perform no Cargo, APK, device or shared-cache writes.
Their passes are host evidence. Full compiled composition, Android linking,
app-UID A/B/retained-A renderer use and device lifecycle remain separate checks.

Packaging must include the Java facade and its `AdmissionFuture`/`StageFailure`
nested classes, the native module declaration, JNI child and renderer hook.
No Cargo dependency is added: the adapter uses existing `sha2`, Android `jni`
and the platform dynamic loader. Release/export and addon availability remain
with the application's existing component and feature closure.
