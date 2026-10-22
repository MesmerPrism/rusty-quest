# Native CPU development feedback conformance example

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
Android/Quest native loading restrictions, CPU/NDK ABI, resident JNI bridge,
ART/Activity/process continuity, signed staging, device feedback, memory after
many replacements and device timings remain unvalidated. Future integration
must keep a resident JNI bridge as the sole platform entry and translate into
this private CPU C ABI. Nothing here makes a new module the current runtime
owner or enables it in an APK.
