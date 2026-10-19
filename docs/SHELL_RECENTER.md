# Shell recenter adapter

Quest owns the only AppFunction implementation in
`apps/shell-recenter-android`. Hostess operator actions and Kiosk developer
utilities consume that adapter through its source-pinned shell entrypoint.
The [host provider contract](SHELL_RECENTER_HOST.md) defines requests and custody-preserving retirement.
It is an explicit privileged developer/operator feature. An ordinary APK,
Store/Lite distribution or missing executor is unavailable; no privilege
fallback, permission grant or Launcher manifest change is supplied.

## Closed platform operation

The target is always `com.oculus.vrshell` and the function is always
`com.oculus.vrshell.appfunctions.ShellAppFunctions#recenterView`. The builder's
default empty parameter document and extras are retained. Neither the Java API
nor the standalone arguments accept a target, arbitrary method, Binder payload,
generic JSON call, persistent Home remap, menu close or Guardian policy change.

`ShellApplicationContext` constructs the real shell Application using framework
`ActivityThread.systemMain`, the actual installed shell LoadedApk and
`makeApplication(true, null)`. It checks process UID/eUID, installed package UID,
Application identity, op package, attribution UID/PID/package, AppOps package
ownership and signing metadata. Reflection is restricted to these fixed
framework declarations and the public typed AppFunction API. Denial remains
denial: no hidden-API exemptions, `setAccessible`, identity field writes,
permission widening, package-code load flags or identity impersonation.

Other explicit shell consumers can call the same typed, target-free
`ShellApplicationContext.create(JSONObject evidence)` factory, returning an
Android `Application` after those unchanged identity checks. The class and
this factory are public; its verification internals remain package-private.
The factory accepts no target, operation, package or context override and
dispatches no AppFunction. It grants no permission and does not establish
operation availability, callback retirement or visible effects. Each caller
must separately check its fixed API/permission and own its original lifetime.
The recenter operation and standalone vocabulary remain closed to recenter.

`AndroidShellRecenter.inspect()` checks the build-generated source identity,
current exact boot and fingerprint, SDK 34 or newer, actual shell identity,
normal execute permission, and the required API/service signatures. An async
enabled query completes readiness. Symbol availability is checked because the
API may be firmware-specific or backported; a numeric SDK alone proves nothing.
The caller must select fresh current boot/firmware observations and bind them
to its exact target/operator custody. No device serial or historical firmware
is baked into the public adapter.

## Java API and original lifetime

The package is `io.github.mesmerprism.rustyquest.shellrecenter`.
`ShellRecenterOperation(Mode, Backend, Journal, Listener)` is the shared
controller. Call it and its callbacks on one serialized executor; reentrant
callbacks are supported. `AndroidShellRecenter(Handler, expectedBootId,
expectedFingerprint, expectedSourceId)` supplies the production backend on that
Handler. The core also accepts an injected monotonic `Clock` for host tests.

`Mode.READINESS` reads identity/API/enabled state only. `Mode.RECENTER` first
requires readiness and a create-new synced `Journal.intent`, then records the
one attempted dispatch. The journal must retain that original identity and
final bytes. An absolute 30-second observation deadline is rechecked after
potentially slow intent storage. An expired readiness callback never dispatches.
Dispatch exceptions are uncertain and retain the original. A deadline sends at
most one cancellation request; cancellation does not prove reversal.

Only the genuine original callback can resolve a pending execution. Late
callbacks and platform exception objects are preserved. Duplicate enabled and
original callbacks are ignored, and calling `start()` twice is rejected.
`ORIGINAL_RESULT` means only a logical AppFunction response. Actual pose,
reference-space and wearer-visible effects require separately attributed
observations. Service callbacks preserve their exception chain/code;
`callback_error_origin=response_decoding` and `ORIGINAL_CALLBACK_UNREADABLE`
distinguish local result decoding failures. Failed final publication retains
ownership even after the callback. There is no automatic retry or cleanup of an
unknown original.

## Standalone protocol

The main class is
`io.github.mesmerprism.rustyquest.shellrecenter.ShellRecenterMain`. Its exact
seven key/value arguments are:

```text
--mode readiness|recenter
--session <fresh lower-case 32-hex session>
--nonce <fresh lower-case 32-hex original nonce>
--output-dir <fresh canonical absolute directory beneath owned stage>
--source-id <build source-manifest source_id>
--expected-boot-id <current lower-case boot UUID>
--expected-fingerprint <current exact firmware fingerprint>
```

It emits JSON lines with schema `rusty.quest.shell_recenter.event.v1`, fixed
target/function, source/boot/context evidence, session/nonce/PID/UID, monotonic
time, `execute_attempted`, `intent_published`, `original_callback_received`,
`deadline_reached`, `cleanup_held`, cancellation facts, `readiness_reason` and
explicit `pose_or_visibility_acknowledged=false`. The host retains all events,
including earlier unknown/pending observations.

The original directory is create-new. `EXECUTE_INTENT.json` precedes the sole
dispatch. `FINAL.json` is create-new, fsynced and bounded to 128 KiB;
`FINAL_PUBLISHED.final` supplies `{path,bytes,sha256}`. Supported outcomes include
`READINESS_READY`, `READINESS_UNAVAILABLE`, `ORIGINAL_RESULT`, `ORIGINAL_ERROR`,
`ORIGINAL_CALLBACK_UNREADABLE` and explicit pre-execute/deadline outcomes.
Success/readiness-ready exits 0; other known final outcomes exit 1. An uncertain
original keeps the main looper and process alive. A lost observer or 30-second
deadline does not authorize killing, deleting, reaping or dispatching again.
The host owns staging and retains the original until it joins the exact final,
full stdout/stderr EOF and actual process exit/reap. It closes only its own
resources. This library does not install packages or stop another provider.

## Build and focused conformance

```powershell
pwsh -NoProfile -File tools/Test-ShellRecenter.ps1 -AndroidJar <sdk/android.jar>
pwsh -NoProfile -File tools/Build-ShellRecenter.ps1 -AndroidJar <sdk/android.jar> -D8Jar <build-tools/lib/d8.jar>
```

The short host build creates a new ignored output directory containing
`classes.jar`, an empty-manifest AAR, and optionally
`rusty-quest-shell-recenter.dex.jar` for `app_process`. It builds no APK and
contacts no device. `source-manifest.json` binds source inventory, generated
source identity, actual artifacts and Android/JDK/D8/script input hashes. Inputs
are rehashed after compilation. The source ID hashes sorted relative Java
source paths plus their SHA-256 values, excluding the generated identity class.
Observed Git revision/tree and dirty state are diagnostic context; a dirty
build does not claim that baseline Git tree owns its modified source bytes.

Production controller tests cover readiness rejection, no-effect readiness,
durable intent, slow storage/deadline, late callbacks/errors, duplicate/replay,
dispatch uncertainty, pending ownership, cancellation and final-storage failure.
The independent Android doubles invoke the real fixed production reflection
binding, genuine-context validation paths, permission/firmware/source/boot
rejection, async original decoding/error and final serialization/durable-byte
join. Doubles and consumer classes are excluded from production artifacts.
An external-package consumer also compiles against and invokes the public
factory, checks independent process/real/effective UID and normal AppOps denial,
and verifies unchanged identity evidence without an AppFunction dispatch.
These host results do not prove firmware behavior, custody or physical effects.
