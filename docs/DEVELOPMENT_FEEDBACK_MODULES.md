# Development feedback modules

These opt-in, CPU-only conformance examples prepare an implementation boundary
for developer iteration. They are independent of application rendering, media,
network, permissions, lifecycle and authority owners. They do not change an
existing APK or enable a production code-delivery channel.

The Java example lives in [android/development-feedback](../android/development-feedback/).
The native example lives in [examples/development-feedback-native](../examples/development-feedback-native/README.md).
Each has a focused host test and fresh A1, changed B2 and freshly compiled A3
generations. A3 restores A's behavior with a new artifact identity. Selecting an
already loaded A1 is a separate rollback test.

## Contract and activation

Keep the public Java interface in the resident parent loader. Compile each
implementation against it and exclude it from the plugin. A new child loader
defines each implementation generation. The native example uses a checked,
size/version-tagged C table from a system dynamic library; Rust collections,
trait objects and allocator ownership do not cross that boundary.

The host owns the counter and buffer. Candidate validation uses disposable
state and an independently expected result. Activation occurs between calls
and publishes the callable together with its lifetime owner. Validation or
execution failure must preserve the previously selected implementation and
authoritative state. Code rollback changes future computation; it does not
reverse changes already committed to the buffer or counter.

Artifacts have fresh generation identities and verified bytes. Android 14's
[target-34 dynamic-code requirement](https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading)
requires opening a writable descriptor, marking the file read-only before
writing through that descriptor, then closing it before loading. Use a
Context-derived private staging directory. Never overwrite a loaded generation.

Retain native handles during this first proof. Native loading can execute
initializers before the API table is checked, and ABI checks do not isolate
faulty code. Neither `dlclose` success nor Java garbage collection guarantees
immediate memory reclamation. Count loaded generations and staged bytes; select
a process or worker recycling limit from actual measurements before device use.

## Focused host checks

Use the configured JDK with the Java focused runner:

```powershell
pwsh -NoProfile -File tools/Test-DevelopmentFeedback.ps1
```

Use an explicitly selected standard Rust compiler with the native runner:

```powershell
pwsh -NoProfile -File examples/development-feedback-native/Run-Conformance.ps1 -Rustc <rustc>
```

The runners keep unique output/provenance directories and enclosing timing.
They do not install tools, access a device or assemble an APK. Actual Android
SDK compilation and D8 packaging, when selected through the Java runner's
explicit inputs, establish source/package compatibility only. Desktop JVM
classloaders and the desktop dynamic linker do not establish ART or Quest
loader behavior.

## Later Android integration

An application must deliberately adopt a development-only resident host through
its supported build/provider route. Keep release packaging independent: the
same contract can use a packaged static implementation with the loader absent.
JNI registration, buffer allocation, Activity and process ownership remain with
the resident host. The native example does not yet supply an Android JNI host.

On a coordinated device slot, record process-start and Activity identities,
actual executing generation, challenge-dependent result, counter history,
buffer identity/guards, repeated-generation memory/mappings/FDs/threads and
staged-file growth. Measure saved edit through compile, transfer, verification,
activation and correct feedback, including failures, retries and fallback.
Separate one-time enabling APK/setup from repeated iterations. Host timings
are not a prediction of device feedback latency.

Test Android Studio body edits, added helpers and added methods separately;
[Apply Changes](https://developer.android.com/studio/run#apply-changes) has a
different compatibility/lifetime contract from
[Compose Live Edit](https://developer.android.com/develop/ui/compose/tooling/iterative-development).
Disable automatic full-Run fallback for those experiments. Compare an ordinary
incremental APK/restart using the same edit and cache conditions. Meta's
[direct native deployment](https://developers.meta.com/vr/documentation/unreal/unreal-reduce-app-deploy-time/)
is an additional prepared development route with restart semantics, rather
than evidence of in-process Rust owner replacement.

Migration, worker replacement, unloading, GPU/OpenXR resource replacement and
application adoption require their own evidence after the minimal CPU seam.
