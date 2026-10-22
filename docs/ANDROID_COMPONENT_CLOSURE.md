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

The existing Quest source-composition provider retains the original Cargo graph
for native supplier proof. An absent private Git root is added to the full app
document using `Add-QuestBuildOwnedRepository` with
`Get-AndroidComponentOwnerRepositoryId` (lowercase normalized basename, prefixed
when necessary to start with a letter) and
fixed `app-owned-input` role; a root already present in Cargo retains that row.
The builder writes `source-composition.json` before compile dispatch, reobserves
the same document before Gradle and handoff, and binds it in the run capsule.
Candidate/publication clean-source rules remain in effect.
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

Ignored output `android-components/` contains `inventory.json`,
`gradle-inputs.json` and `closure.json`. The input lock records the selected
contract/feature-lock/source fingerprints; APK inspection and build manifest
record the passing closure hash and APK binding. The run capsule hashes those
normal outputs. Local paths stay in ignored evidence.

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

`Test-AndroidComponentWarmCompile.py` accepts explicit Gradle/Java/cache paths and
uses one fresh JVM fixture project for initial/add/change/unchanged builds,
configuration-cache reuse and missing-source rejection. `Test-AndroidComponentBinaryClosure.py`
accepts explicit Java/D8/android.jar paths and verifies real JVM/DEX output plus
descriptor/access/duplicate/missing/truncation controls. Both keep output under a
fresh `target/` directory and perform no device calls. Product owner tests supply
their real contract/source and exact artifact controls separately. Host fixtures
do not establish APK runtime, native rendering or media acceptance.
