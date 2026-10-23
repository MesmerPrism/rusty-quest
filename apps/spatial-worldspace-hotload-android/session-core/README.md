# SDK experiment session core

This source-shared library owns the existing experiment runtime once inside an SDK app. It compiles the canonical native app reducer, recording writer/recovery/contract, clocks and guidance by path. It does not link the native renderer crate, instantiate NativeActivity, or run an OpenXR loop. The native app retains its platform implementation of the same narrow seam.

The SDK platform implementation fails closed without all of: exact compiled materialized profile/provider/inventory hashes; exact selected controller/settings hashes; one process-lifetime real workload service table; captured SDK owner/runtime/session identity; current exact-context observer evidence; and real controller assessment plus submit-confirmed effective settings. Host tests and observer UI status are not presentation or recording evidence.

## Inputs and artifacts

`tools/Build-SessionCoreAndroid.ps1` owns the Android library build. It requires a preinstalled Rust Android target, exact Cargo.lock hash, explicit Cargo/NDK paths and a fresh output root. The caller owns machine build reservations. It does not install tools, access a headset, or choose profiles. Its exact-hash input document is:

```json
{
  "schema": "rusty.quest.sdk_session_core.inputs.v1",
  "files": {
    "feature_lock": { "path": "<absolute selected file>", "sha256": "<64 lowercase hex>" },
    "profile": { "path": "<absolute selected file>", "sha256": "<64 lowercase hex>" },
    "provider_manifest": { "path": "<absolute selected file>", "sha256": "<64 lowercase hex>" },
    "provider_inventory": { "path": "<absolute exact canonical inventory bytes>", "sha256": "<64 lowercase hex>" },
    "settings": { "path": "<absolute selected file>", "sha256": "<64 lowercase hex>" }
  },
  "composition_binding_sha256": "<exact admitted source binding>",
  "driver_binding_sha256": "<exact admitted source binding>"
}
```

Inventory bytes must be the actual canonical inventory used by packaged closure verification; an invented JSON file with a claimed digest is not an input substitute. SDK adaptation provenance must bind the original selected source feature/settings hashes and the exact derived settings hash in the enclosing composition manifest.

Settings use `rusty.quest.sdk_session_settings.v1`, with `properties` containing the resolved native string property map, `controls` (eight finite unit values), `visual_scale`, `center` (four floats), `radius_slot: 0` and `deformation_slot: 0`. The core currently supports only the selected controller source and this explicit radius/deformation layout. It compiles exact settings bytes and verifies their digest at runtime. No ambient Android properties select a source.

Build output includes `librusty_quest_sdk_session_core.so`, the selected SDK base and adapter, the generated canonical ControlPanelActivity facade, 14 canonical owner/audio helper sources, a build log, and `session-core-build.json` with exact source/input/tool/output hashes. Both builders use the one `tools/lib/NativeRendererExperimentSessionFacade.psm1` generator. The SDK helper wraps its SessionMembersOnly output in the existing JNI package without NativeActivity inheritance. Root's APK composition must bind every emitted source/library and compile the complete closure. The receipt keeps Java compilation and device proof false until their actual checks pass.

## Actual interfaces

`include/rq_experiment_services.h` is the one C ABI for the selected real workload. The core bind bridge borrows the exact already loaded workload DSO using RTLD_NOLOAD, verifies getter/table/callback defining module identity, and calls `rq_session_core_install_services_v1(rq_get_experiment_services_v1())` in this core while retaining that DSO. The callbacks must be bounded, thread safe, non-reentrant to the core, and backed by the actual GPU workload. Installation cannot replace a table in-process. No callback code/storage may unload. Status `submitted_*` fields describe the exact command snapshot whose owner submission succeeded, never the newest pending settings. This is submission evidence, not GPU completion or visible pixels.

`SdkExperimentSessionCore` reuses existing native initialization/command symbols. Call `bindScene` only after the canonical facade verifies/materializes packaged closure and initializes the native owner, and after the actual SDK scene supplies its handles. SDK epochs and pose sample sequences must be positive, process-monotonic and never reused. Poll and pose delivery use one app-owned background executor. `revokeScene` must invalidate synchronously at actual pause/destruction; terminal retirement runs against the captured native owner, not a global replacement.

`controllerPose` accepts actual right-controller pose and tracking, stamped at acquisition in Android elapsed realtime nanoseconds. It explicitly does not accept or label an SDK timestamp as OpenXR time. The shared controller assessment, composition authority and composition driver determine output. Missing/unusable/calibrating samples do not grant Start. The private service must accept the exact sample and subsequently return its captured submission receipt. Runtime/session identity accompanies that effective sample.

The observer bridge uses RTLD_NOLOAD on the actual loaded API layer and its locked exact-context API. It does not insert the layer or accept a caller-provided proof boolean. Admission requires two advancing eligible observations and expires at 250 ms. Every critical worker command rereads observer/service evidence; an admission guard linearizes its pure reducer mutation with SDK revocation and is released before I/O/callbacks. Existing 500 ms writer/runtime presentation-stall handling remains in place. A changed observer focus/lifetime context requires fresh SDK binding; replay never refreshes proof.

## Prepared checks

Run only inside the allocated compiler lane and use a separate target directory:

```powershell
$env:CARGO_TARGET_DIR = '<new isolated host target>'
cargo check --manifest-path apps/spatial-worldspace-hotload-android/session-core/Cargo.toml
cargo test --manifest-path apps/spatial-worldspace-hotload-android/session-core/Cargo.toml proof::tests
```

The first resolution creates the separate core Cargo.lock for review; subsequent qualified Android builds require its exact hash and use `--locked --offline`. Existing canonical module tests also compile as part of the test binary. SDK production services remain unavailable on a host; canonical lifecycle tests expecting the native platform must be run in their native owner harness, not enabled via a successful SDK backend stub.

Required further checks: core Rust/JNI and C++ compile; header size/offset agreement on arm64; native platform regression tests and static source gates affected by the narrow module extraction; Android Java closure compile; backend sample/order/reset/submit tests; overlapping lifecycle/revocation/queue tests; real SDK loader interception; and actual clock/input/recording/audio/durable receipt/device qualification. None is implied by this source implementation.

## Revocation and queued callbacks

Lock order is Java audio delivery monitor, then Rust platform effect gate, then brief proof/controller state locks. Rust never calls Java while holding these gates. Pure reducer admission holds only the proof state guard; it is released before foreign callbacks, recording I/O or waiting for writer receipts. A service apply already inside the effect gate completes before synchronous revoke returns. An old blocked observer read must reacquire its captured effect lease before any side effect, including error/unfocused paths. Recording offers target the captured runtime and verify session generation inside its producer gate.

Queued audio commands, backend callbacks and phase samples capture a platform owner token when scheduled. Native mode has the previous permissive scope. SDK mode checks exact SDK/runtime/session identity at actual delivery and serializes playback with revoke. Active delivery also rereads real proof; cleanup may use the retired token only while that token remains the current owner, so an old stop cannot stop replacement audio. Load `SdkExperimentSessionCore` before canonical audio initialization to install its scope.

`controllerInputSource(epoch, entityId)` accepts an opaque nonzero 64-bit entity identity (signed Java Long bits preserved). Invoke it before poses and whenever the actual selected entity changes. It clears core/private readiness and classification without adding a recording command or resetting process-monotonic sample ordering. Counter/time JNI arguments are separately positive and below Long.MAX_VALUE. Invalid samples immediately clear admission and invalidate private readiness; delayed submit receipts cannot restore it.

Owner/session fences use the canonical projection generation, which is retained through durable restart/Idle. The active recording producer atomic clears to zero on finalization but is not the session identity. Continuous save-next can prepare the next session under the same genuine SDK scene; no fake SDK epoch or private tuple regression is needed. A changed SDK pose entity is an input reset, not a fabricated new scene epoch. SDK pose acquisition and its +Z-forward coordinate conversion must remain explicit in composition provenance; native right-grip semantic parity requires device evidence.

Prepared additional suites: `scope::tests`, `scope::counter_tests`, `proof::tests`; `ConditionAudioOwnerScopeTest` runs as its own Java process beside the canonical condition-audio host fixtures. No source fixture grants a production proof.

## Selected SDK Activity wrapper

The generated closure includes `io.github.mesmerprism.rustyquest.native_renderer.SessionOwnerActivity`, extending the actual Meta SDK 0.14 `AppSystemActivity`. `New-SessionOwnerActivity.ps1` wraps the one canonical generated session-member block in its existing `ControlPanelActivity` JNI class; that class has no Android/native-renderer Activity inheritance. The helper's Java inventory includes both wrapper and generated facade, plus canonical Shell/Coordinator/codec/audio sources. The native panel Activity entry is excluded from SDK Java closure. OFF builds use only `app/src/sessionDisabled/java/.../SessionOwnerActivity.java`, with identical API and no native load or owner/audio dependency.

The app Activity extends SessionOwnerActivity and uses `ownerSelected`, `ownerBind(epoch,instance,session)`, synchronous `ownerRevoke(epoch,terminal)`, `ownerPoll()` every 100ms, cached `ownerStatus()`, and `ownerAction(action,biasPct)`. Accepted actions are arm-a/arm-b/start/pause/resume/save-next. Bind and actions queue work and return current status, not a synchronous success receipt. `ownerController(epoch,entityId,sequence,requestedElapsedNs,acquiredElapsedNs,flags,active,tracked,rightSecondaryPressed,x,y,z,qx,qy,qz,qw)` takes copied actual SDK values in the canonical coordinate basis. All integers except biasPct are Java longs; entity identity is opaque, not a signed-positive counter. Input delivery coalesces queued frames and retains known loss so a later good snapshot cannot hide a classifier reset.

Override `onOwnerPresentationRequested(route)` to change only the real SDK presentation. The handoff then waits for actual native proof/readback. It never launches NativeActivity or manufactures immersive-active status. Root owns SDK panel visibility, actual producer startup and scene handles. Bind waits for real initialization and the real workload constructor; the bridge only borrows an already loaded libspatial_gpu_workload.so with RTLD_NOLOAD, verifies getter/table/callback defining module, and installs its table in this same core while retaining the DSO.

Nonterminal lifecycle revoke queues a real fenced canonical pause when authoritative control state is running, then synchronously retires the owner. Retired-epoch polling is cleanup-only and can deliver the actual paused receipt; it cannot deliver a start/phase to a replacement. Terminal cleanup captures the exact existing audio objects, closes them and awaits outside the audio delivery lock.

Calibration uses the same canonical BreathCalibrationControllerAction settings and 1.25s held-right-secondary action. Binding configures the classifier; it does not automatically start calibration. The SDK copies genuine right-controller ButtonBits.ButtonB from its borrowed component. Source/tracking/lifecycle loss resets held gesture and classifier history. No SDK pose timestamp is represented as an OpenXR prediction timestamp.


## Nonterminal owner transition

The SDK background bind executor drains old audio work and requires a quiescent PAUSED/PREPARED or empty terminal state. It closes the exact old backend before publishing the new SDK owner. A new MediaPlayer is then prepared from the same verified provider and seeks to the acknowledged paused position. Only its actual prepared/seek-complete callback and current-position readback settle restoration; only its actual start callback settles Resume. Old queued platform events remain attached to the retired player identity. No listener is retargeted to the new player, and commands are rejected while restoration is pending. Awaiting happens outside the audio delivery monitor. A bind attempted before the real paused receipt is available rejects and can be retried; it does not invent a pause.

Right-secondary observations take a gesture-only JNI path at acquisition before pose coalescing. The canonical hold action uses those ordered real elapsed times. Releases reset only the held gesture, preserving completed calibration; gaps at or above the proof expiry reset hold accumulation. A trigger is consumed only by a fresh pose at or after its actual acquisition. Source/tracking/lifecycle resets still reset classification. Explicit handoff cancellation retires waiting old ticks without replay and clears only the matching old handoff.

Prepared successor fixtures: `ConditionAudioRebindTest` uses the real asynchronous audio runtime and distinct fake platform player identities, including native-message listener lookup after rebind; `SessionHandoffRetirementTest` exercises the actual canonical handoff; `controller_button::tests` uses the actual canonical hold action for release/press between pose drains, rearming and acquisition gaps. These source fixtures are not executed qualification. Terminal same-process reopening remains outside the implemented capability route; use an actual cold process relaunch after terminal shutdown.


## Binding waits and correlation retirement

SdkOwnerLifecycle is the production attempt/publication gate. The background binding executor waits for old-audio draining and new-player seek outside its monitor. Native binding and recording its actual epoch are one synchronous gated operation. A lifecycle callback therefore revokes a genuinely bound owner even while it has no published SDK UI epoch. Preparation/restoration resource work carries the exact attempt guard, so a cancelled queued waiter cannot create or retire resources for a replacement. Publication and failure cleanup recheck the same attempt; no stale waiter can publish or revoke the newer owner.

The wrapper retries the same captured scene request, coalesced at 100ms and at most 100 retries, only while initialization is not ready or the exact pre-nativeBind audio-not-quiescent result is returned. Pause/replacement cancels this request. Timeouts, uncertain preparation failures, native failures, restoration failures and consumed native epochs are not retried. ownerStatus exposes pending_requested_epoch, native_bound_epoch, currently_bound_epoch and bind_result_phase. No extra Activity call or fabricated scene epoch is needed.

SdkPendingCommand binds each UI receipt to its exact token and receipt object. Owner revoke/rebind retires only that token with an unknown-outcome/no-replay result. Canonical owner-changed-outcome-unknown also settles correlation, allowing later actions without claiming the previous native command succeeded. Late observations and outcomes cannot clear or publish for a replacement slot.

Prepared successor05 fixtures use these exact production helpers: SdkOwnerWaiterTest runs the actual asynchronous ConditionAudioRuntime with a held platform-ready callback and checks main-callback revoke progress, unpublished native-owner retirement, stale-waiter suppression, replacement bind, pre-bind cancellation and retry bounds. Compile it alongside ConditionAudioRebindTest (its platform fixture helpers), and run in a fresh JVM. SdkPendingCommandTest uses the canonical receipt parser and exact correlation slot to test retirement, old/new token separation and owner-change settlement. No compiler or test execution is implied by these source fixtures.


Checkpoint06 adds exact audio transfer retirement. Preparation timeout marks its attempt retired before any worker/scope wait and cancels the queued future; an entered release reconciles on the serial audio worker. Native adoption rejection/cancellation retires the prepared transfer in a finally block. Restoration error/timeout also retires it, and only the matching transfer/generation/epoch can release its resource. Pending admission remains closed until that resource cleanup returns. Failure is an ERROR state, never successful playback or an invented resume receipt; ordinary stop/cleanup remains available afterward. Normal successful rebind still restores the actual acknowledged position with a distinct MediaPlayer.

`tests/ConditionAudioTransferFailureTest.java` is a prepared production-runtime fixture for queued timeout, timeout inside release, abandoned preparation, late old retirement versus a newer transfer, and restore error/timeout followed by exact adopted-epoch terminal cleanup. Compile alongside `ConditionAudioRebindTest.java`, `SdkOwnerLifecycle.java`, and canonical ConditionAudioContract/ConditionAudioRuntime in the reserved JVM fixture lane; run `io.github.mesmerprism.rustyquest.native_renderer.ConditionAudioTransferFailureTest` in its own JVM. The three timeout cases deliberately use production five-second waits (allow at least 30 seconds overall). No Android native library is loaded by this fixture. It does not establish real MediaPlayer/Looper, audibility, JNI shutdown, or device behavior. It and prior JVM/Rust fixtures remain unexecuted pending the coordinator's compiler reservation.
