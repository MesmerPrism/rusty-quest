# Native stopped-source output review

`renderer.diagnostic.held_output_review` is a disabled-by-default diagnostic
feature that displays already completed eye output in a nonexported 2D
Activity. It requires the existing Meta input, presentation and buffered stereo
three-pass features. It adds no camera/capture permission or source restart.
Live OpenXR projection exclusion is unchanged.

Resolve a new app spec/feature lock rather than changing an existing capsule.
Add `renderer.diagnostic.held_output_review` to requested features and
`NativeHeldReviewActivity` to declared activities. Use the consuming diagnostic app's exact package, external payload inputs and
permissions, with the presentation package suffix and required feature closure.
The selected diagnostic is a separate product from Showcase/session ownership. The lock supplies
`RUSTY_QUEST_NATIVE_RENDERER_HELD_OUTPUT_REVIEW=true`. The builder checks its
feature/manifest/input/presentation closure; the Android entry additionally
requires the actual debuggable app. Showcase and an active experiment session
owner cannot be combined with these diagnostics. Unselected diagnostics do
not restrict ordinary render modes or camera input and do not draw a grid.
Build to a new output address. Native code
may be optimized; Android debuggability is separate from Rust debug assertions.

The unchanged v2 Surface publisher and producer remain compatible. The
existing DUMP-protected service also accepts a separate closed token
`io.github.mesmerprism.rustyquest.NativeHeldReview.v1`, only from actual
Shell UID 2000 with synchronous replies and an actual per-call DUMP permission check:

| Transaction | Payload | Reply |
|---|---|---|
| FIRST_CALL_TRANSACTION+80 | token, one bounded JSON string, no trailing parcel data | exception status, accepted int |
| FIRST_CALL_TRANSACTION+81 | token only, no trailing parcel data | exception status, JSON state |

No action string, component, path, URI, service start/bind, capture Start/Stop,
permission change or generic command is accepted by this endpoint. Use the
already app-bound service. There is one accepted retirement attempt, no rearm.

The source-retirement JSON is closed (`deny_unknown_fields`) and at most4096
UTF-8 bytes. Every field is required:

```json
{
  "schema": "rusty.quest.held_review.source_retirement.v1",
  "generation": 7,
  "app_pid": 100,
  "app_uid": 10100,
  "app_start_ticks": 1234,
  "app_apk_sha256": "<actual 64 lowercase hex>",
  "canonical": "<selected serial>",
  "boot_id": "<current boot UUID>",
  "lease_id": "<current operator lease UUID>",
  "capture_session_id": "<original 32 lowercase hex>",
  "capture_nonce": "<original 32 lowercase hex>",
  "helper_pid": 101,
  "helper_start_ticks": 1235,
  "controller_pid": 102,
  "controller_start_ticks": 1236,
  "external_receipt_sha256": "<pinned original source-retirement receipt>",
  "accepted_stop": true,
  "no_pending_rpc": true,
  "helper_eof_reaped_absent": true,
  "controller_eof_reaped_absent": true
}
```

Example identifiers are inert. The controller start value is the host owner's
original process birth identity (for example UTC process-start ticks), not a
device PID observation. The actual Shell caller attests accepted own Stop,
original RPC resolution, both original EOF/reap completions, exact helper and
host-controller process absence, session/nonce, canonical/boot/lease and the
external receipt digest. The app cannot independently observe a host process.
The controller is the finite capture-producer controller; the app's ordinary
run-isolation launcher remains owned until held review and app cleanup finish.
Java independently checks its actual PID/UID and installed APK SHA-256; native
code checks its own PID/birth and current generation. An invalid request never
opens review; a failed request after Java gate closure remains closed.
The review identity is the immutable historical reader generation, also after
normal producer buffer removal has already made legacy generation admission0.
It is never returned as an admissible live Surface generation by the legacy API.

After acceptance, all legacy calls (including cached Binder references) are
closed. The native generation becomes inadmissible, latest input is dropped,
and a serialized acquisition gate joins resource-touching callbacks. Late
callbacks can only return through a retained tombstone; listener removal is
not used as a callback-join proof. The reader is retained until process exit.
Buffer-removal callbacks only set an atomic tombstone; they never acquire/drop
another image or reenter the acquisition mutex during AImage_delete. All source
admission checks honor that tombstone, and latest input is released by the
renderer/retirement owner.

At the XR frame boundary, the app requests normal XR exit. The outer renderer
owner serializes one admission decision with request handling before teardown.
A request arriving after that decision is rejected; teardown never reopens
review admission. Main's successful device idle and checked retirement of the
mesh UI owner (including any additional FOREIGN release) precede swapchain
destruction. An admitted review then resets every original command recording,
retires every source slot, releases acquired AImages and destroys source
imports, descriptor sets, normalizer, graph and readback owners. It does not
wait on reset but unsubmitted fences. Failed/unknown retirement or command
reset holds review; owners with unresolved GPU work remain retained. Queued
source buffers inside the retained reader are not claimed to be vendor-retired.

The original Java publisher cleanup has a single sticky result. Ready, the
immutable app receipt and UI launch require that the original main-queue
unbind action actually returned successfully. Rejected dispatch, an exception,
pending or unknown cleanup holds review; a later action cannot launder a known
failure. The native render owner joins that action without holding the policy
or callback locks. A main-thread caller never blocks its own queue. A timeout
is a pending result, not cleanup acknowledgement.

The output must be the same-generation, sequence2/mode2 completed pair, joined
to both same-AImage raw-pack witnesses. Fixed own paths are:

```text
files/presentation-witness/G/eye-{0,1}-mode-2-seq-2.{json,rgba}
files/meta-input-witness/G/full-core3-input-eye-{0,1}-seq-2.{json,rgba}
files/held-review/G/retirement.json
```

The final app-owned receipt separates external Shell attestation from actual
app observations. It records GPU idle, recording reset, callback acquisition
seal, successful original Java publisher cleanup, zero acquired
AImages/source refs/imports/pending exports/releases, fixed
output hashes, `reader_tombstone_retained=true`,
`future_acquisition_allowed=false`, `native_retirement_acknowledged=false`, and
`live_freshness=false`. It is a debug app quiescence receipt. It is not the
neutral sink reducer's `readerDestroyed` or drained result.

Only after native source/render cleanup and receipt completion can the same
process launch its nonexported 2D Activity. The UI rehashes actual loaded output
bytes, converts RGBA/BGRA while preserving row order and alpha, labels both
eyes and source timestamps, and states `SOURCE STOPPED / HELD OUTPUT` with fit
scaling. It reruns no effect and initializes no source/OpenXR decoder. Its own
review window is capturable; actual screenshot visibility still requires
observation. This does not establish wearer/physical scanout acceptance.

An old force-stopped process cannot retrospectively supply this receipt. Old
screenshots/witnesses remain their original evidence tier. A future source
restart requires independent owner admission and a new process/generation.

Focused host checks include real late-admission, Java cleanup and authorization
regressions. They can compile `held_review_policy.rs` directly with
`rustc --edition=2021 --test`, then run the executable. Java host tests cover
the real legacy-call gate and bounded pixel converter without Android calls.
Compile all diagnostic Java against the selected Android SDK jar, and type
check the Android Rust target in an isolated target directory. These checks
do not qualify the selected private payload APK or runtime drain.

The portable host wrapper is `tools/checks/Test-NativeHeldOutputReviewHost.ps1`.
Supply the selected SDK `-AndroidJar`, selected `-JavaHome` and a fresh `-Out`
directory. It performs no build of an APK and makes no device/network calls.
