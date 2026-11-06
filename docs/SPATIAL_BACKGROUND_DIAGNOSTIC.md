# Closed background control diagnostic

This optional development adapter separates a Background UI action from the
Activity launch transport. It is not a graphics fix. The existing fixed UI
helper uses `am start`, which can pause and resume an existing singleTask
Activity and restart its XR session. A same-process result or
`activityRestarted=false` marker does not establish continuous XR operation.

## Selection and boundary

The provider, closed parser and request registry exist only in `app/src/debug`.
Its authority is `${applicationId}.spatial-background-diagnostic`, protected by
Android `DUMP` and an exact runtime shell UID check (2000, excluding root).
Only private integer resource
`rusty_quest_spatial_background_diagnostic_enabled=1` selects it. Missing,
malformed, zero or other values deny calls and register no lifecycle observer.
An ordinary debug manifest contains the inert provider; release source sets
contain neither its classes nor authority. A selected consumer must require
the actual debug definitions/methods in its owned Android component contract
and bind its resource tree through the existing build input lock/capsule.

The provider exposes only `observe` and `select` through ContentProvider.call.
All URI/file/query/insert/update/delete routes reject. No request can carry an
Intent, component, path, media key, JSON payload, Native command, run, session,
Peer, enrollment or Manifold authority. It never launches an Activity. The
Activity's existing background handler still performs its ordinary background
JNI configuration/readback; the adapter does not bypass or duplicate that path.

## Request and receipt

Both methods require a null call argument and exactly these string extras:

| Extra | Meaning |
| --- | --- |
| `nonce` | Caller request challenge, 64 lowercase hex characters |
| `expected_package` | Exact selected installed package |
| `expected_pid`, `expected_uid`, `expected_start_ticks` | Positive canonical decimal process birth fields |
| `expected_boot_id` | Canonical boot UUID |
| `expected_apk_sha256` | Exact installed base APK SHA-256 |

`select` additionally requires `ticket`, `observation_sha256`,
`expected_epoch` (all 64 lowercase hex) and `mode`, exactly `black` or `video`.
The server-issued ticket and observation hash come from a fresh `observe`;
the epoch is that observation's `diagnostic_process_epoch`. The app hashes its
installed base APK and observes its own boot/PID/UID/start ticks, rather than
accepting those caller fields as facts. The process-local diagnostic epoch is
not a Native or Manifold process epoch and grants no authority.

Observe reads cached app scalars on the main thread. It does not initialize a
lazy media coordinator, open a media catalog or request Native capture. A
ticket is issued only for the current resumed Activity, complete scene setup
and first scene tick (including packaged baseline), and VR readiness. It binds
the exact Activity/resume, scene, VR and background revision. Tickets expire
after 10 seconds and are consumed once. Pausing, retirement or an intervening
background handler invalidates the binding. An observe while a command is
pending returns its retained state without granting another command ticket.

Select posts at most one main-thread dispatch, checks the captured binding
again immediately before effect, and invokes the same
`setSpatialBackgroundMode` used by UI/profile control. It holds no registry
monitor across Activity callbacks. Every handler entry advances its revision,
including unchanged selections and throwing entries. Queue eligibility is
bounded to two seconds. An identical retained request returns the same state;
the same nonce with changed fields is rejected. A consumed ticket cannot become
valid again when a newer terminal record replaces the bounded retained slot.

A 1.5-second waiter timeout or interruption cancels only queued work. If the
handler already entered, the operation remains `executing` and must be observed
to its eventual result; it is not a failed no-effect request or permission to
retry. Observe may return `observation_pending` if the UI thread is unavailable.
Handler exceptions are retained as `handler_failed`, with any available current
snapshot; partial effects remain possible. Process death loses this diagnostic
registry and requires the owner's actual recovery, never a fabricated terminal.

Receipts are bounded to 16 KiB and identify the request hash, before/after
fences, exact app mode/revision and terminal phase. `handler_returned` with
`effective_mode_observed=true` proves only that the existing app handler
returned and its current mode/revision matched. It proves no player frame,
physical display, XR continuity, GPU/image lifetime or decoder cleanup.
An intervening lifecycle or additional handler revision yields `superseded`.
Preserve the independent lifecycle/validation/decoder evidence for those claims.

## Validation and next artifact

Use fresh output directories under `target`:

```text
python tools/checks/Test-SpatialBackgroundDiagnosticHost.py --repo-root <quest> --output-root <quest>/target/background-core --java-home <jdk17>
python tools/checks/Test-SpatialBackgroundDiagnosticAndroidHost.py --repo-root <quest> --output-root <quest>/target/background-android --java-home <jdk17> --kotlin-lib <compiler-jars> --android-jar <sdk>/platforms/android-36/android.jar
python tools/checks/Test-SpatialBackgroundControlActivityHost.py --repo-root <quest> --output-root <quest>/target/background-activity --java-home <jdk17> --kotlin-lib <compiler-jars>
```

The first compiles/executes the production parser and custody registry with
deterministic scheduling controls. The second typechecks the production
provider against Android API with a modeled Activity leaf, and checks source
manifest/release-root isolation. The third compiles actual Activity/coordinator
binding methods with modeled SDK leaves, including observing without lazy
initialization. Existing Entity lifecycle/backing retention checks remain
applicable. None produces an APK or proves runtime behavior.

This resident Activity/manifest/provider addition needs a new APK; hotloading
an effect or changing an existing profile cannot add it. The candidate owner
must bind clean source commits/trees, combined selected resources, actual
compiler/class/all-DEX closure and unchanged intended product/enrollment inputs
before handoff. Inspect the merged debug manifest, exact provider/callsite and
resource, signer and installed bytes. Release qualification separately requires
a merged release manifest and every DEX to exclude the debug component/classes
and authority, even with private opt-in resources supplied. Source checks do
not substitute for that artifact inspection.

After owner-controlled readiness/cleanup, a fresh-process SDK-only comparison
can use Black -> Video -> Black without a second Activity launch. Preserve
actual Activity/XR epochs, player first frame, decoder release, and complete
finite Vulkan validation logs. Validation message limits can suppress later
errors; a smaller count is not an improvement. Native Own/Peer and camera
request-template experiments remain separate from this transport diagnostic.

See [Android component closure](ANDROID_COMPONENT_CLOSURE.md),
[immersive media lifetime](SPATIAL_IMMERSIVE_VIDEO_PLAYBACK.md), and
[debug provider boundaries](agent-instructions/owner-contracts.md#debug-host-receipt-boundary).
