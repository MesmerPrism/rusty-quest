# Development feedback Android consumer

An opt-in **debug-only, plain Java 2D application** for the
[development feedback module](../../docs/DEVELOPMENT_FEEDBACK_MODULES.md).
It has no camera, network, native library, Spatial SDK or OpenXR integration.
There is no bundled plugin implementation: a fresh `feedbackplugin.Module`
must be received and explicitly activated before Compute can run.

One `FeedbackApplication` owns the controller, `ModuleHost`, counter and
16-byte buffer. Activity recreation changes its Activity token and increments
the creation count while retaining Application/process tokens, allocation,
counter, buffer and selected generation. A new process starts fresh. The
status shows the full buffer and its CRC32 for comparison; CRC32 is diagnostic,
not artifact authentication. Generation selection changes code only and never
rewinds host state. Failed activation preserves the prior active generation;
failed loader attempts remain charged against the resident 64-load cap.

The debug APK must package the owner's resolved v2 feature lock and the exact
neutral runtime profile. The Application reads those two bounded assets once
and projects their actual SHA256 values, project, lock revision/fingerprint and
closed selected features into immutable controller status. The workflow owner
resolves full feature policy; this reader reports the selected compiled inputs.
A DEX controller refuses an absent selection. Pure host fixtures are explicitly
marked `host-only-unbound` and establish no packaged/runtime activation.

This loads trusted development code. The loader provides contract identity,
digest/definition checks, readonly staging and scratch-state probing; it does
not sandbox Java code or remove Android platform trust boundaries.

## Source checks

Use JDK17 and explicit installed tool locations:

```powershell
pwsh -NoProfile -File apps/development-feedback-android/tests/Test-FeedbackApplication.ps1 `
  -JavaHome <jdk17> -AndroidJar <android-sdk/platforms/android-34/android.jar> `
  -D8 <android-sdk/build-tools/34.0.0/d8.bat>
```

This compiles and exercises the real pure Java controller with fresh compiled
host plugins, including tamper, failed probe, invalid field, Activity lifetime
and retained-code selection cases. It separately typechecks all Android source
against API34 and optionally runs actual D8 plus the existing owner class_def
parser to prove the plugin, host loader and tests are absent from that DEX.
It retains create-new ignored `.local/` logs, steps, source and selected tool
hashes before/after. It does not assemble, sign or execute an APK. Keep the
landed module's existing `tools/Test-DevelopmentFeedback.ps1` conformance checks
as the core validation; this consumer's tests do not replace them.

## Canonical APK build

The project pins AGP8.11.1, compile/target API34, min API26 and Build Tools36.0.0.
Use the repository's Gradle9.4.1 tool config and existing resolver's **VerifyCache**
mode against the explicitly selected shared tool owner. No Gradle wrapper,
toolchain installation or alternate APK assembler is included here.

Run the reviewed [debug profile](profiles/debug-build.profile.json) through the
existing WEF `Invoke-QuestBuildProfile.ps1`, selecting the exact external Gradle
launcher and SHA256, clean Quest revision/tree, profile SHA256, private
environment bindings and fresh retained receipt. The profile binds:

| Environment variable | Private binding ID | Kind |
| --- | --- | --- |
| `JAVA_HOME` | `jdk17` | directory |
| `ANDROID_HOME`, `ANDROID_SDK_ROOT` | `android-sdk` | directory |
| `GRADLE_USER_HOME` | `feedback-gradle-home` | directory |
| `RM_FEEDBACK_DEBUG_KEYSTORE` | `debug-keystore` | file |
| `RM_FEEDBACK_FEATURE_LOCK` | `feedback-feature-lock` | file |
| `RM_FEEDBACK_RUNTIME_PROFILE` | `feedback-runtime-profile` | file |

The existing owner resolver must select exactly `development-feedback-core`
and `development-feedback-debug-host` for project `quest-development-feedback`,
with default activation disabled and `selected-lock-and-runtime-input`.
The runtime input has exactly five keys: schema
`rusty.quest.development_feedback.runtime_profile.v1`, app lane
`development-feedback-android`, this exact `owner_package`, `property_policy`
`none`, and an empty `properties` array. The Gradle preparation task copies
these exact raw files into generated debug assets before AGP packaging; it
neither issues a feature lock nor assembles another APK. Compare the status's
`selection_*` identities against the capsule's actual selected inputs.

Prepare verified raw copies of the selected owner inputs at
`local-artifacts/development-feedback/selected/feature.lock.json` and
`runtime-profile.json` under the Quest root. Both file bindings must select
those exact paths. The portable profile hashes them as explicit lock inputs,
so changing selected bytes changes the retained output address even when
source and tool versions stay fixed. Keep immutable copies beside the final
APK/capsule for later verification and cleanup; the working selection paths
are preparation inputs, not long-term evidence storage.

The debug keystore must already exist and use the conventional development
`androiddebugkey` alias and `android` password. This project never generates
a key or uses a product release signer. Machine paths, signer identity,
bindings and resolved dependencies belong in private retained inputs.

Select a separate stable Gradle user home for this app. Project cache and
AGP build intermediates use the repository's ignored
`target/apk-i/development-feedback/warm/` namespace. They share no product
cache. First-use missing AGP dependencies are a real build prerequisite;
the source check does not establish an offline-ready Gradle cache.

AGP owns Java compilation, DEX, manifest/resources, alignment and debug signing.
`:app:retainDebugApk` only copies AGP's already signed `app-debug.apk` to the
canonical runner's create-new artifact destination. It refuses replacement,
aliases and other destinations. The content-address filename is the WEF
preflight binding digest; the runner separately hashes the actual APK bytes.
Preserve/reuse an unchanged existing output instead of rebuilding over it.
Only the debug variant is enabled; release cannot package these controls.

Source preparation and its tests prove neither successful AGP assembly nor
an enabling APK. Before packaging/adoption and effects, select the existing
owner's project spec/feature lock/runtime profile and exact app/source/tool/
signer/run capsule as required by [APK isolation](../../docs/APK_RUN_ISOLATION.md).
This neutral consumer cannot inherit another product's unit or activation.
Use the `Android2d` run shape and QFM's actual APK inspection/install/launch
route. Confirm the final APK manifest, signer, debuggability, no product
permissions/native payload, debug provider authority and actual class_defs.
No shell property, headset or Android process success follows from a host pass.

## Fixed debug operations

The programmatic Activity and provider call the same synchronized controller.
The provider exists only in `src/debug`; its authority is
`${applicationId}.feedback-operator`, and both Android `DUMP` and runtime shell
UID2000 are required. It accepts only `ContentProvider.call` with a null `arg`
and these exact String fields. No URI CRUD, Intent/component, arbitrary path,
class name, command, JSON request or network endpoint is accepted.

| Method | Exact fields | Effect |
| --- | --- | --- |
| `status` | none | Read resident tokens, count, allocation, buffer, generation and result |
| `compute` | `input` | Run active generation with signed64 input, commit successful host buffer and increment counter |
| `activate` | `generation`, `sha256`, `probe_input`, `probe_counter`, `probe_initial`, `probe_result`, `probe_expected` | Verify received artifact and independent scratch known-answer probe, then select |
| `select` | `generation` | Select a previously validated resident generation without rewinding host state |

Generation IDs are 1–64 ASCII alphanumeric/underscore/hyphen characters with
an alphanumeric first character. SHA256 is exactly64 lowercase hex characters.
Probe buffers are exactly16 bytes, supplied as32 lowercase hex characters.
Decimal fields are canonical signed64 strings. The response Bundle contains
one `status_json` string using `rusty.quest.development_feedback.status.v1`.

Transfer is separate from activation. An authorized developer route places
only the generation's JAR at the app-private `files/incoming/<generation>.jar`.
The controller derives that fixed basename, refuses aliases and stages a new
readonly verified copy under app-private `files/generations/`. The caller must
supply a trusted digest and independently calculated probe result/buffer.
The UI's initial values are placeholders, not evidence of a valid received
generation. No transfer command, generic execution service or plugin build
pipeline is added by this app. Use a fresh identity after a failed load; the
failed loader may already have initialized code and remains resident.

For example, a received A1 whose transform adds1 to `scratch[0]` and returns
`input + counter + 1` has probe input5/counter3/zero buffer/result9 and expected
buffer `01000000000000000000000000000000`. Its digest still comes from the
actual independently built JAR. A/B/A proof requires newly compiled artifacts
and receipts, rather than a prepackaged switch. Android/ART execution,
resident JNI/native integration, memory bounds and measured feedback speed
remain separate device experiments.
