# Selected Android component closure

`tools/Build-SpatialCameraPanelAndroid.ps1 -AndroidComponentContractPath <owner-contract>`
verifies selected application components before APK handoff. Product owner
entrypoints should require their tracked contract automatically. Existing callers
without a contract explicitly report `unverified`; they acquire no completeness
claim. This assertion grants no lifecycle, signing or runtime authority.

The contract schema is `rusty.quest.android_component_contract.v1`. Each component
has `component_id`, `module_id`, `owner_lane` and nonempty `definitions`. The
`module_id`/`owner_lane` pair joins selected rows of the already authenticated
product `planning-feature-lock.json`. Every selected pair must be covered exactly
once. Disabled components introduce no requirement. Definition fields are:

```json
{
  "root": "private",
  "source": "example/Owner.kt",
  "class": "example.Owner",
  "methods": [
    {"name": "status", "descriptor": "()I", "public": true, "static": true}
  ]
}
```

`application` means the selected app's `app/src` root (`main` and the selected
`debug`/`release` source set); `private` means the explicitly selected private
feature source root. Requirements belong to the selected owner's contract,
including concrete classes used by its entrypoint factories. Public tools contain
no product-specific definition list. Relative paths cannot escape or traverse
links. The contract must reside in the explicit private source owner's repository.
`class` names the emitted JVM binary definition, including a Kotlin file facade
for a top-level function. It does not name an arbitrary source symbol. Bind that
definition and exact emitted method to the owning source with actual class/DEX
evidence; source-text class-name matching cannot establish this contract.

The existing Quest source-composition provider retains the original Cargo graph
for native supplier proof. An absent private Git root is added to the full app
document using `Add-QuestBuildOwnedRepository` with
`Get-AndroidComponentOwnerRepositoryId` (lowercase normalized basename, prefixed
when necessary to start with a letter) and
fixed `app-owned-input` role; a root already present in Cargo retains that row.
The builder writes `source-composition.json` before compile dispatch, reobserves
the same document before Gradle and handoff, and binds it in the run capsule.
Candidate/publication clean-source rules remain in effect.
Before a cold Cargo graph is resolvable,
`Get-QuestBuildOwnedRepositoryObservation -Root -RepositoryId` returns that same
flat `app-owned-input` repository row. Its explicit `-AllowWorkingTreeChanges`
uses the existing tracked/untracked overlay algorithm; the default rejects dirty
source. This observes an owned root without creating a second composition document
or supplying native reuse eligibility.
Artifact directories under an added app-owned repository must be Git-ignored;
the actual selected Android source/asset/resource directories remain excluded.
This permits an owner's ignored artifact namespace without writing into its source.

`tools/lib/android_component_closure.py` (Python 3.11+) inventories exact selected
Kotlin/Java source bytes. The applied
`tools/gradle/android-component-closure.gradle` observes the actual selected
KotlinCompile `sources`, JavaCompile `source` and their destination directories.
It rejects a missing, stale or unexpected owned compiler input before compilation.
The inventory is a content-sensitive compiler task input, so additions and changes
invalidate compilation through Gradle's existing task model. It does not clear
caches or disable incremental compilation. Explicit empty properties prevent an
ambient Gradle property from silently enabling the route for compatibility callers.
The canonical builder also pins the exact selected variant Kotlin/Java compiler
task names; the shared observer's explicit JVM fixture overrides cannot redirect
an Android build's observation.

After compilation, the checker joins current source hashes, the actual compiler
receipt, required JVM class definitions (including `SourceFile` and exact method
descriptors/access), and all final `classes*.dex` definitions. A reference to a
class or method does not satisfy a definition. Missing/duplicate definitions,
method mismatches, input drift or unavailable observations fail before the APK is
copied to the artifact directory. It reads headers and signatures without emitting
constant values. Python entrypoints suppress source-tree bytecode cache writes.
Full verification requires the raw prepared inventory SHA from the original
in-memory selection and validates its fingerprint. Rewriting the evidence file,
even with a matching rewritten compiler receipt, cannot change the selected
requirements while retaining the original build input binding.

With a selected component contract, the canonical builder first calls
`Invoke-AndroidComponentJvmCompilation` before Cargo setup or compilation. This
owner API invokes only `:app:verifySelectedAndroidJvmCompilation`, then joins
the original raw inventory to actual JVM class output with `verify-compiled`.
It needs no APK or DEX. Its receipt has scope `jvm-compilation-only` and grants
no runtime authority. Callers without a selected contract remain unverified.

The early aggregate depends on the selected variant's real Kotlin and Java
compiler tasks. Actual SDK bootclasspath, compiler classpaths, generated source
files and class directories are observed from Gradle. A typed dependency and
finalizer closure is checked while Gradle constructs the graph; an unexpected
producer is refused before task execution. This is a closed compiler/resource/
managed-library preparation route, not permission to select arbitrary tasks.
It does not sandbox configuration-time build code. Configuration-cache reuse
retains the checked graph; the observer still resamples selected source bytes.

Early compilation and later assembly share one environment projection and
`finally` restoration body. Fixed phase selection owns task/variant/inventory
arguments; caller common arguments cannot append another task or replace those
properties. Both phases hold the selected Gradle executable and original source
inventory. A failed child retains its exit/output metadata even if a subsequent
source check also fails. Assembly remains after native publication and retains
all final source, signer, native, APK and all-DEX closure checks.

Ignored output `android-components/` contains `inventory.json`,
`gradle-inputs.json` and `closure.json`. The input lock records the selected
contract/feature-lock/source fingerprints; APK inspection and build manifest
record the passing closure hash and APK binding. The run capsule hashes those
normal outputs. Local paths stay in ignored evidence.
Early evidence is separate: `android-components/jvm-preflight/` contains actual
pre/post-compile observations and `compiled.json`; assembly never overwrites it.
The normal phase receipt records early elapsed time, and the capsule binds the
original inventory, early compiler receipts and final assembled closure.

Android source/contract changes affect shell/package identities, never the native
identity through a full repository overlay. The existing native key remains bound
to its selected native files, profiles and shaders. A dirty public shell with a
separate frozen native repository remains subject to the existing clean/raw-equal
supplier guard; this change does not add a native-only projection of dirty public
source. Private owner source iteration uses its supported composition route.

For the selected Spatial SDK CMake layer, preflight checks actual selected C/C++
source filenames against Ninja's Windows 260-character limit before Cargo or
Gradle. It also projects each selected compilation source's object/dependency
filename into the existing `Get-SpatialCmakeStaging` variant/hash/ABI/target layout
and retains that owner's 250-character CMake object-path budget. These are separate
checks: a short cache does not make a long source filename usable. Product owner
commands should select the existing stable short native forest for cold and warm
materialization; artifact directory names do not select native work directories.

Focused checks:

```powershell
python tools/checks/test_android_component_closure.py
pwsh -NoProfile -File tools/checks/Test-AndroidComponentBuildSeam.ps1
pwsh -NoProfile -File tools/checks/Test-AndroidNativeSourcePaths.ps1
```

`Test-AndroidComponentWarmCompile.py` accepts explicit Gradle/Java/cache/D8/android.jar paths and
uses one fresh JVM fixture project for initial/add/change/unchanged builds,
configuration-cache reuse and missing-source rejection.
It verifies a top-level function's real Kotlin file facade and source/method/class/DEX
closure, and rejects the function name used incorrectly as a class definition.
`Test-AndroidComponentBinaryClosure.py`
accepts explicit Java/D8/android.jar paths and verifies real JVM/DEX output plus
descriptor/access/duplicate/missing/truncation controls. Both keep output under a
fresh `target/` directory and perform no device calls. Product owner tests supply
their real contract/source and exact artifact controls separately. Host fixtures
do not establish APK runtime, native rendering or media acceptance.

`Test-AndroidComponentEarlyCompile.py` accepts explicit Gradle/Java/SDK/cache
paths and uses a fresh real Android project with Compose, generated BuildConfig/
resources and a project AAR. Its full scenario tests unavailable Android Java
and Kotlin APIs, Java/Kotlin signature mismatch, source add/change, unchanged
configuration-cache reuse, unexpected dependency/finalizer refusal and stale
source rejection. The `graph` scenario isolates the latter affected controls.
No native task, APK packaging or device operation is part of this fixture.

`Test-AndroidComponentMediaAarGraph.py` uses the complete media library Gradle
script with a small host Java payload to exercise its actual Java/Jar/Zip AAR
chain through the unchanged task classifier. A custom AAR archive must declare
`archiveExtension = "aar"`; an explicit `.aar` filename alone leaves a `Zip`
task's extension metadata as `zip`. The fixture checks the unchanged output
filename and archive entry payloads, and refuses extra ZIP, APK and native
finalizer tasks before execution. It accepts explicit Gradle/Java/android.jar
paths and fresh output and optional reusable fixture Gradle-home paths under
`target/`. This checks managed task metadata and graph behavior, not an Android
app build or media runtime.

The shared Spatial Camera Panel builder accepts optional absolute `CargoPath`
and `RustupPath` selections. Unspecified applications must resolve uniquely;
invalid explicit paths and ambiguous applications fail closed. With
`RequireInstalledRustTarget`, a missing Android Rust target is refused before
target installation. Existing defaults remain available when that switch is absent.

`SpatialCameraPanelBuildPrerequisites.psm1` owns Android/Java/NDK/Gradle and
signer prerequisite observations. The builder consumes its result before compiler
staging. Evidence uses a fresh non-overlapping directory; signer secrets are not
recorded. `WaitPolicy` defaults to `Bounded`; explicit `Retained` keeps the same
mutex or asynchronous operation after an advisory estimate, including through
the actual pinned Gradle resolver. Hash, path, redirect, size, actual cancellation,
and cleanup checks remain mandatory. This platform adapter adds no workflow
context authority or application media/provider policy.

The focused `Test-SpatialCameraPanelBuildWorkflowStatic.ps1
-PrerequisiteCompatibilityOnly` controls use independent fixture mutex/task
owners, actual executable selection and an absent-cache resolver refusal.
Optional explicit prerequisite input/output arguments qualify existing platform
tools with a fresh fixture signer. These are host prerequisite controls; they
do not establish APK, application adoption or runtime behavior.
