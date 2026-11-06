# Native CPU development feedback conformance example

## Pure product scissor leaf

The optional app consumer and renderer-used generation/readback are documented
in [Development scissor module](../../docs/DEVELOPMENT_SCISSOR_MODULE.md).
Its existing addon owner supplies transport and the bounded worker.

The additional opt-in [scissor leaf](scissor/scissor.h) applies the existing
`normalized_interval_to_pixels` policy from the Camera Panel renderer to plain
normalized rectangles. It exchanges only fixed-width scalars and a caller-owned
16-byte pixel rectangle through a versioned C table. It owns no Vulkan object,
camera, codec, window, connection, lease or application authority. The actual
product renderer remains unchanged and uses its existing compiled policy.

```powershell
python examples/development-feedback-native/Run-ScissorConformance.py `
  --rustc <installed-rustc> --android-ndk <installed-ndk>
```

Omit the NDK argument for host-only execution. Android preparation currently uses
an installed Windows-host NDK and defaults to API 26. Every invocation retains a
fresh ignored `.local/scissor-<identity>` directory; no Cargo graph, dependency
installation, global cache, APK or device action is used. Host conformance runs
the actual source renderer's interval function against 12,000 deterministic
inputs, checks unsupported/no-write cases and executes dynamic modules in two
fresh host processes. The table admits extents 1 through 2^24, finite rectangle
components and finite endpoint sums. Inputs outside that explicitly bounded
domain are refused; it does not redefine arbitrary renderer input behavior.

A1 uses the product calculation. B2 is an explicit preview experiment that
insets nondegenerate axes by one pixel. Fresh A3 restores A's code and output.
The same native process retains its output allocation and completion count;
incompatible tables, bad scratch behavior, null functions, invalid calls, stale
generations and bad hashes cannot publish a replacement or advance that count.
All opened handles, including refused candidates, remain until process exit.
The finite proof opens nine handles; it is not a production staging-budget API.
Only the existing low-level verified image loader is shared with the earlier
JNI example. Neither Rust traits nor allocator ownership crosses the C ABI.

Android preparation separately compiles the modules and native proof driver,
checks independent C/Rust layouts, AArch64 ELF types, unique SONAMEs, getter-only
module exports and closed platform dependencies. Actual projection instructions
must differ for B2 and match for A1/A3. The immutable ZIP contains a relative
manifest, native proof driver, eight modules and LF-only checksums. These are
compilation/inspection results; `android_executed` remains false.

For a later coordinator-scheduled device slot, use a new shell-owned directory,
verify its exact package/individual hashes, mark modules readonly and the driver
executable, then run `./scissor-proof manifest.tsv`. Require `SCISSOR_PROOF_PASSED`
and the A/B/A pixel observations above, natural process exit and a second fresh
process beginning at zero. This case proves native shell execution only. App-UID
linker namespace, Activity continuity and selected product-feature adoption need
their own subsequent proof. No unload or product activation is implied.

## Arithmetic control example

This standalone, dependency-free Rust example exercises a synchronous native
module seam. It is opt-in and owns no Android application, package, runtime
profile, network endpoint or device. Its nested `[workspace]` keeps it outside
the repository's Cargo workspace. The focused runner invokes an explicitly
selected installed `rustc` directly; it does not install a toolchain or fetch
dependencies.

Run from the repository root with PowerShell 7.6 or newer:

```powershell
pwsh -NoProfile -File examples/development-feedback-native/Run-Conformance.ps1 `
  -Rustc <absolute-path-to-standard-toolchain-rustc>
```

The runner preserves every invocation in a new timestamp/GUID directory below
the example's ignored `.local/`. `-OutputRoot <directory>` selects another
parent; each invocation still creates a unique child and never deletes or
reuses prior output. Output outside the default ignored parent is the caller's
responsibility. No arbitrary timeout kills a compiler. Builds are sequential.
The compiler must already have its platform's linker/runtime available.

`receipt.json` records compiler identity/hash, source hashes, actual artifact
SHA-256 values from `Get-FileHash`, artifact byte lengths, each fresh compile
duration, and enclosing harness elapsed time through proof completion. This
enclosing time includes unit compilation/tests, host compilation, all seven
module compilations, hashing, loading and dispatch. It is a local sample, not
an APK build comparison or performance improvement claim. Generated wrappers,
compiler output, full compiler version, tests and host proof remain alongside
the receipt.

## Optional Android native preparation

The same runner can additionally prepare Android AArch64 CPU libraries with
the selected, already installed standard Rust compiler and NDK:

```powershell
pwsh -NoProfile -File examples/development-feedback-native/Run-Conformance.ps1 `
  -Rustc <absolute-path-to-standard-toolchain-rustc> `
  -AndroidNdk <installed-ndk-root> -AndroidApi 26
```

`AndroidApi` defaults to 26 and requires `AndroidNdk`. It must be at least 26,
within that NDK's advertised platform range, and have an actual target
sysroot library directory. Before any compilation the runner checks the real
NDK clang, linker, readelf, objdump, sysroot and metadata, then asks the selected
Rust compiler for its Android target library directory and requires installed
`std`/`core` libraries. It never installs a missing target or changes global
tooling. Invalid preflight stops before host compilation and retains a failed
receipt. Optional preparation still runs all existing host conformance checks.

After the host proof, the runner uses that NDK's C compiler to compile
[`tests/abi-layout.c`](tests/abi-layout.c). Its static assertions independently
match Rust's compile-time target layout assertions: header offsets 0/4/8/16,
header size/alignment 24/8, and table header/function offsets 0/24 with table
size/alignment 32/8. Both compilers use actual AArch64 target types. A real
negative shared object without the API getter must fail ELF admission.

Fresh A1/B2/A3 Android cdylibs use ordinary NDK clang linking and distinct
filenames/SONAMEs containing a fresh run identity. Real ELF inspection requires
ELF64 little-endian AArch64 shared objects, the exact SONAME, a defined global
API getter, and a closed set of Android platform CPU dependencies. Exact
dependencies are recorded; there are no supplied stub libraries. The runner
finds the actual local transform function symbol and disassembles only that
function. It compares instruction words after removing display addresses:
B2 must differ from A1, while fresh A3 must restore A1's transform instructions.
Generation-token or whole-file differences cannot satisfy that check.
Android compilation strips debug information and uses optimization to expose
the changed arithmetic while retaining the function symbol for inspection.
Preparation refuses modules larger than the resident 4 MiB image limit.
The C ABI table,
version, contract identity and generation semantics remain unchanged.

Receipts retain exact command arguments, target/API, NDK revision, tool
versions, artifact hashes, selected tool/target-library/API-stub hashes and
source/header/runner/test hashes before and after the run. Generated wrapper
sources also receive before/after hashes. A changed input fails the run.
Selected tool bindings identify the files used for this preparation; they are
not a byte inventory of the entire NDK installation. Failed commands and
preflights keep `passed=false`; successful Android preparation separately
records `executed=false`, `jni_host_tested=false` and device/Quest false.

Quick negative preflights exercise a missing NDK, API without an NDK, and
out-of-contract/out-of-NDK API levels without compiling:

```powershell
pwsh -NoProfile -File examples/development-feedback-native/tests/Test-AndroidPreparation.ps1 `
  -Rustc <absolute-path-to-standard-toolchain-rustc> -AndroidNdk <installed-ndk-root>
```

Android cross-compilation and ELF/layout/disassembly inspection establish
source preparation. The Android libraries are never loaded or executed by
this runner. Optional resident JNI preparation is described below. This creates
no APK packaging path, device transfer or current application activation.

## Resident JNI bridge and Android proof preparation

The opt-in resident bridge composes the same host, state and guarded buffer.
The Java facade owns its private native token for the process lifetime and
serializes activation, computation and observation, including callers on
different Java threads. The fixed JNI adapter uses real `jni.h`, registers
four private methods in `JNI_OnLoad` and copies bounded primitive arrays with
the JNI Region APIs. It exports only `JNI_OnLoad`. No native owner pointer
appears in status. The facade freezes one canonical resident bridge path and
SHA-256 per Java class after successful loading; another identity is refused
before it can replace the class's JNI registrations. Failed loading does not
freeze that identity. Retain the facade until process exit.

Prepare the bridge using the existing runner:

```powershell
pwsh -NoProfile -File examples/development-feedback-native/Run-Conformance.ps1 `
  -Rustc <installed-rustc> -AndroidNdk <installed-ndk> -AndroidApi 26 `
  -PrepareAndroidBridge -JavaHome <installed-jdk17>
```

This runs resident Rust tests and a real JDK17 `-Xcheck:jni` proof using the
same C/Rust sources, then links the Android Rust archive and C PIC object with
ordinary NDK clang. The Unix FFI uses target `c_char`, including Android targets
where C strings use unsigned characters. The final ELF must have the exact
SONAME, AArch64 layout, only the JNI entry export and closed platform CPU
dependencies. The receipt keeps host execution distinct from Android
preparation. Windows host JNI is not Android runtime evidence.

Add `-AndroidJar <android-sdk/platforms/android-34/android.jar>` and
`-D8Jar <android-sdk/build-tools/36.0.0/lib/d8.jar>` together to prepare the
standalone `AndroidBridgeProof` DEX JAR. The runner compiles the actual facade
and proof against Android's API declarations, then invokes D8 with the selected
JDK. It also compiles a real bad-behavior generation for semantic refusal.
These options build no APK and launch no device process.

The standalone proof takes a bridge pathname, its expected SHA-256, a module
directory and a fresh session directory. Its module `artifacts.tsv` has four
rows (`a1`, `b2`, `a3`, `bad-behavior`), each containing name, generation,
SHA-256 and relative basename separated by tabs. Generations are 1, 2, 3 and 7.
All inputs are trusted local development artifacts. Run it through the
selected Android runtime's `app_process` with only its exact DEX JAR on
`CLASSPATH`. It exercises actual JNI, readonly staging, A/B/A native dispatch,
refusal, retained state/buffer and Java thread serialization. A fifth argument
`interactive` pauses before B2 and A3; the host supplies the fixed phase and
fresh artifact SHA-256 after compilation and transfer. This exposes a complete
saved-edit-to-effective-feedback measurement in the same native process.

The shell-runtime harness is an independent neutral module consumer. It does
not enable native code in the debug Android application's feature lock, prove
its app UID/linker namespace or Activity lifetime, or replace its DEX controller.
Application adoption requires its own explicit feature and resident owner.
Keep exact device identity, source/artifact hashes, process birth, commands,
effective snapshots and timings in private evidence. Do not treat a prepared
proof JAR as executed evidence.

The example limits are 64 activation attempts, 4 MiB per image and 64 MiB of
charged staging. Failed attempts remain charged and staged files are never
overwritten; use a fresh generation after refusal. Every opened image remains
loaded, including a candidate rejected after loading. These limits are example
policy, not a measured memory bound or a product recycling policy.

## Contract and ownership

[`include/rm030_feedback.h`](include/rm030_feedback.h) and
[`src/abi.rs`](src/abi.rs) define the same C ABI: a frozen header with explicit
version, exact table size, fixed contract identity and nonzero generation,
followed by one nullable C function pointer. The host reads only the header
before validating the full table size. Size is native `sizeof(rm030_api)` for
the selected architecture; images and host must have the same target ABI.
The host also matches the expected generation and rejects a stale publication.
There is no Rust `Vec`, `String`, trait object or allocator ownership across
the ABI. The host may use ordinary Rust containers internally.

The host owns the authoritative value, completion counter and one fixed buffer
allocation. Calls borrow an output pointer and an eight-byte pointer/length
pair for their synchronous duration. Plugins neither retain pointers nor own
state or the completion counter. Arithmetic is checked before writes; only a
completed call commits the host value and increments its counter. The host
checks output bytes and surrounding guards. Guards detect this example's
bounded mistakes; they cannot contain arbitrary native memory corruption.

`A1` adds `input + 1` to the prior value. `B2` compiles a different function
body and helper that adds `2 * input + 2`. Fresh `A3` restores A's body with
generation 3. The runner compiles each to its own new cdylib path, and the host
loads the actual artifacts via Windows `LoadLibraryW`/`GetProcAddress` or Unix
`dlopen`/`dlsym`. There is one host thread; selection occurs between completed
calls. There is no concurrent dispatch, background plugin work, TLS, global
logging, GPU, network or OpenXR integration. Compile uses `panic=abort`, and
the ABI forbids unwinding.

Candidate admission checks artifact SHA-256 before loading, then header,
generation and scratch behavior before publishing the function pointer. A
second hash check catches accidental file replacement before publication.
Scratch calls use separate temporary storage and leave authoritative state
and buffer unchanged. Expected hashes and semantic probe results come from
the trusted local harness; hashes provide identity, not publisher trust. Only
trusted, task-owned immutable native images are supported. Loading executes
native code and may run platform constructors before the ABI check; this is
not a sandbox or an adversarial file-race defense.

Every successful load retains its image reference until process exit,
including rejected images. The example never calls `FreeLibrary` or
`dlclose`. It does not prove safe unload, repeated long-lived memory bounds,
exception recovery, arbitrary pointer validity, ABI migration or state
migration. A DLL symbol returning an invalid pointer can crash this process.

## Observed proof and limits

Four unit tests cover incompatible headers, independent SHA-256 vectors,
completion-only state mutation, and scratch behavior rejection. The dynamic
proof independently compiles and checks all seven real libraries:

| Action | Effective host state |
| --- | --- |
| Select A1 and dispatch input 4 | counter 1, value 5 |
| Reject wrong hash, version, short table, contract, bad behavior and generation; dispatch A1 after each | counter 7, value 35 |
| Select B2 and dispatch input 4 | counter 8, value 45 |
| Select fresh A3 and dispatch input 4 | counter 9, value 50 |
| Reject overflowing operation | counter 9, value 50 |

The short-table fixture returns an actual header-only allocation. The hash
rejection reaches no loader call. All candidate validations preserve host
state and buffer; every rejection continues prior dispatch. The final check
observes the same buffer address/guards and eight retained image references.
Code rollback restores A arithmetic while keeping the accumulated value 45
and counter 8 before A3's next call. It does not restore an earlier state
snapshot; state rollback requires a separately specified host transaction.

This proves host conformance only. Windows is the initially exercised path;
the Unix loader branch still requires a run on the selected Unix host.
Optional preparation checks the selected AArch64 C/Rust layout, link closure
and emitted ELF code. The resident JNI proof adds host execution and an
independently runnable Android harness. Application-specific loading
restrictions, Activity/process continuity, signed product staging, memory after
many replacements and product timings require their own evidence. Integration
keeps the resident JNI bridge as the sole platform entry and translates into
this private CPU C ABI. Nothing here makes a new module the current application
owner or enables it in an APK.
