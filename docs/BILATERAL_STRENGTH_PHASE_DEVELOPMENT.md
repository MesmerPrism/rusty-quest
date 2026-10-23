# Bilateral strength phase development feedback

This optional app-owned seam replaces only the pure strength-cycle phase arithmetic. The normal path remains the resident calculation. Phase, normalized rate, elapsed-time rebasing and the phase mutex stay in the app. Camera, codec, network, enrollment, lifecycle and cleanup authority do not change.

## Activation and ownership

After its existing native library load, the Activity passes its application context to `nativeConfigureStrengthPhaseHotload`. Native code reads `ApplicationInfo.FLAG_DEBUGGABLE` and the application's no-backup directory itself, then retains only an immutable Boolean, canonical directory and random session identifier. Activity recreation does not replace this configuration, the render-thread host, its loaded images or the phase clock.

The separate property `debug.rustyquest.strength_phase_hotload.enabled` must equal `true`. Its default is disabled. A nondebuggable app remains disabled even when the property is selected. This does not use or bypass the JSON control-profile hotloader or private-panel input policy.

The first selected render thread owns the feedback host. Other threads retain the resident calculation. The host and raw library handles remain thread local without a `Send`/`Sync` implementation. Refresh, command reads and activation run before taking the phase mutex. Only the selected pure computation runs inside it. Disabling selection preserves resident images and state but stops module dispatch.

The six unchanged provider files come from `rusty-quest-development-feedback-native` and its `examples/development-feedback-native/src` owner at source commit `b0700cc06848f15a3a3d93919e488caf6e408e1b`. The canonical native identity includes both source roots. Dynamic generation images are session inputs, not APK build inputs.

## App-private command transport

The fixed root is `no_backup/development-feedback-strength-phase`. Its `current-session.json` contains exactly:

```json
{"schema":"rusty.quest.hotload_current_session.v1","host_kind":"bilateral-strength-phase","session_id":"<pid>-<random-32hex>","owner_token":"strength-phase-render-owner-<session>","pid":123,"directory":"<session>"}
```

Each process creates a fresh session directory. Old directories and commands are not adopted. Stage a unique readonly `generation-N.so` within the current session, then publish a complete `command-ID.json`. IDs contain 1–64 ASCII letters, digits, hyphens or underscores. Commands are at most 4096 bytes, reject duplicate decoded keys, and contain exactly these four fields with string argument values:

```json
{"schema":"rusty.quest.hotload_command.v1","request_id":"activate-1","method":"native_activate","arguments":{"generation":"1","sha256":"<lowercase-64hex>","probe_result":"1048576005"}}
```

Supported methods are `native_activate` with exactly `generation`, `sha256`, `probe_result`, and `native_status` with empty arguments. Generations are canonical positive decimal u64 strings; probe results are canonical decimal i64 strings. A uses probe result `1048576005`, B uses `1056964613`, and fresh A uses A's constant. The codec preserves all f32 delta bits with XOR `0x3e800000`, so the existing fixed loader probe does not depend on subnormal arithmetic.

The render owner polls at most once per 100 ms, inspects at most 2048 entries and consumes at most one command per poll. It consumes malformed and refused commands once as well. It admits at most 512 request IDs per process session; the unchanged provider separately limits activation attempts and retained image bytes. It never exposes an arbitrary compute or lifecycle command. `observation-ID.json` is published after the request; an existing observation is conserved and prevents execution for that ID.

## Effective phase evidence

An observation contains exactly `schema`, `request_id`, `session_id`, `owner_token`, `pid`, `status`, `error`, `host`, `phase`. The schema is `rusty.quest.bilateral_strength_phase_observation.v1`. Status is `ok` or `error`; error is null or the refusal reason.

`host` is null or the separate ABI snapshot: numeric `generation`, `completed`, `value`, `allocation_token`, `retained_images`, `attempts`, `staged_bytes`, the eight-byte `buffer` array, and `active_sha256`. These fields describe ABI execution, not effective visual adoption.

`phase` contains exactly:

- `source`: `module`, `no_host`, `invalid_input`, `host_error`, or `invalid_output`.
- `effective_phase`: the actual app phase returned by the selected step or original resident fallback.
- `adopted_module_generation`: the accepted module generation, or zero for resident fallback.
- `phase_call_count`: a monotonic count of actual step calls, including fallback calls. It is not a GPU frame or pixel counter. The original first-call/rebase branches do not dispatch or increment it.

An activation observation alone proves no phase adoption. Observe `source=module`, the requested adopted generation and a subsequently advancing phase-call count. A probe-passing module returning NaN remains visible as the ABI generation while the actual app uses `invalid_output`, adopted generation zero and the original resident step. An incompatible ABI preserves the prior selected generation. No output or pixel claim follows merely from a successful command or generic host counter.

## Focused validation

Run `tools/checks/Test-BilateralStrengthPhaseHost.py --output <fresh-directory> --android-check`. It compiles real A/B/fresh-A and refusal modules, extracts the actual app state/normalization/advance items without changing their bodies, and calls the real app thread-local step, adapter and provider. It checks retained phase, rebase, semantic fallback, default-off behavior, file queue ownership and command admission. Android checking typechecks the actual context/JNI and transport modules; it does not link an APK or prove Android execution or pixels.

Run `tools/checks/Test-StrengthPhaseNativeIdentity.ps1 -OutputRoot <fresh-directory>`. It evaluates only the actual builder's identity declaration and unchanged hash functions. It proves all six compiled provider inputs participate in the native key by changing private test copies of each byte source. It does not execute the canonical build or touch its cache.

The next necessary enabling APK still needs actual Android module activation and effective phase observations through the live guide push consumer. The selected module is trusted native developer code, not a sandbox. The supported module contract is a synchronous pure phase computation with no callbacks or authority effects.
## Existing iteration caller

`apps/spatial-camera-panel-android/tools/strength_phase_hotload.py` is the app-specific caller. It loads the exact published `tools/development_feedback/quest_hotload.py` and `Build-NativeGeneration.ps1` by their source hashes. Its `iterate` command compiles one fresh module, publishes immutable bytes, sends one activation and waits for actual effective phase progress in the same process/session/owner. It does not install or launch an APK. Supply the actual actor and tool identities; `--plugin-source tools/checks/fixtures/strength_cycle_phase_plugin.rs` selects this phase module. A subsequent B and fresh A use new generations, request IDs and output directories. Preserve any unknown dispatch and its journal; do not repeat it.

The new setup JNI is a private Kotlin `external` method on `io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraPanelActivity`, descriptor `(Landroid/content/Context;)V`. Its implementation lives in the dedicated `strength_cycle_hotload.rs` app module. Future source/DEX/JNI qualification must bind that actual Kotlin declaration, callsite and native export alongside inherited obligations; the implementation does not move to another module to fit a verifier.
