# Native worldspace integration probe

## Dedicated development hotload consumer

`fixtures/native-app-builds/native-development-hotload.app.json` selects the
separate package
`io.github.mesmerprism.rustyquest.native_renderer.development_hotload`.
Its six-feature closure includes the native scene, Android mesh panel, optional
controller/hand input and explicit `native.development_feedback`. Camera,
private particles, scientific payloads, broker and bilateral streaming are
denied. Other app defaults do not enable the developer route.

The visible scene contains a public procedural cube with a checker material,
64 moving procedural particles, and the real Android panel texture sampled by
Vulkan. The panel starts visible, is placed once from a valid tracked head
pose, and offers text, scrolling, a text-input field, animation and particle
controls, panel distance/size/reset, plus a button to compute with the active
CPU generation. Controls requiring denied camera/hand/private features are
disabled. Actual keyboard, tracking-loss and headset behavior require device
qualification; the presence of an EditText is not proof of system IME support.

The application-owned Rust frame thread holds the shared resident CPU host.
The panel button and fixed app-private file transport enter the same typed
dispatch. A compute result changes cube/particle color and spacing from its
actual delta while the cumulative value, counter, allocation token and buffer
continue. With the public conformance producer and input `4`, A/B/fresh-A give
deltas `5/10/5`, cumulative values `5/15/20`, and blue/orange/blue material
selection. An activation alone does not count as visible adoption: the scene's
effective CPU generation changes only after successful computation.

Each native render owner creates a fresh session directory under
`files/development-feedback-native/<session>/`; atomic `current-session.json`
advertises its PID/session/owner. The app never scans older sessions. Stage
immutable, read-only `generation-<n>.so` with a fresh filename, positive ABI
generation and unique Android SONAME. Stage create-new
`command-<request_id>.json` there using `rusty.quest.hotload_command.v1`:

| Method | Exact string arguments | Boundary |
| --- | --- | --- |
| `native_status` | none | Observation |
| `native_activate` | `generation`, `sha256`, `probe_result` | Trusted CPU code replacement |
| `native_compute` | `input` | Stateful dispatch / visible scene update |
| `native_shader_activate` | `generation`, `vertex_sha256`, `fragment_sha256` | Shader pipeline replacement |

JNI initialization and DEX methods are unsupported by this native host and are
refused. Numbers have one canonical decimal spelling; hashes are lowercase
SHA-256. Duplicate JSON keys, unknown fields and non-string arguments are
rejected. The queue admits one command per frame and polls disk at most every
100 ms; it remembers at most 512 request IDs and never replays a known request.
The shared CPU host separately bounds attempts to 64, each image to 4 MiB and
staged bytes to 64 MiB. All opened CPU images remain resident until process
exit, including images whose ABI/semantic probe was refused. This trusted
developer interface is not a native-code sandbox.

Shader requests use fixed read-only `shader-<n>.vert.spv` and
`shader-<n>.frag.spv`, each at most 256 KiB. The scene admits exactly its existing
vertex/fragment stages, `main`, Shader capability, logical GLSL memory model,
no descriptor resources, exact stage I/O and seven vec4 vertex push members
at offsets 0–96 (112 bytes). This is a closed interface preflight, not a full
SPIR-V verifier or a sandbox. The candidate pipeline is created first. Only
an explicit reload waits, bounded to one second, for the other referencing
frame slots; the current slot has already retired. Failure preserves the old
pipeline. On success the old pipeline is destroyed after all referencing
fences retire. Shader attempts are limited to 32 and staged shader bytes to
16 MiB. Shader identity is reported independently of CPU module identity.

Atomic `observation-<request_id>.json` uses
`rusty.quest.hotload_observation.v1`. State is app-owned; effective scene and
shader generations are acknowledged after successful `xrEndFrame`. Physical
pixels remain separate evidence. CPU hotloads retain the current Activity,
scene and frame owner. Activity recreation creates fresh native host/session
state; process-wide continuity across recreation is not claimed.

Resolve the dedicated fixture with the existing resolver, then pass its exact
feature lock to the canonical builder:

```powershell
pwsh -NoProfile -File tools/Resolve-NativeAppBuild.ps1 `
  -AppSpec fixtures/native-app-builds/native-development-hotload.app.json -DryRun
pwsh -NoProfile -File tools/Build-NativeRendererAndroid.ps1 `
  -AppBuildLock <resolved-feature-lock> -AndroidHome <sdk> -JavaHome <jdk17> `
  -NdkHome <ndk> -OpenXrLoader <owned-arm64-loader> -Keystore <debug-keystore>
cargo test --manifest-path apps/native-renderer-android/tests/development-feedback/Cargo.toml --locked
```

The focused host harness imports the exact production transport and shared
resident host sources. Its ignored real-image test requires
`FEEDBACK_NATIVE_MANIFEST` and `FEEDBACK_TEST_OUTPUT`, then `cargo test` with
`-- --ignored --nocapture`. Those host results explicitly do not establish
device pixels, Vulkan pipeline replacement or physical tracking. The
`validate_shader` harness binary checks actual compiler-produced SPIR-V and
supports `--exercise-refusals` for stage/header/location/push/descriptor and
truncation mutations. Use the common hotload scenario/operator for full
saved-edit A/B/fresh-A timing and device observations.

## Hands or controllers

Worldspace mode uses one input owner at a time. A recognized controller
interaction profile with a current tracked pointer takes priority; otherwise
the mesh UI can use a tracked hand. Controller aim is scoped to the right-hand
subaction and located in the panel's reference space at predicted display time.
An active pose needs both position/orientation valid and tracked flags, finite
coordinates and a unit quaternion. An inactive or merely valid estimated pose
never substitutes a default origin. If aim is unavailable, the same frame's
tracked right grip pose supplies its local negative-Z ray; the marker identifies
`right-aim` or `right-grip`. Mesh UI selection binds grip even without a breath
or pose-publisher feature. Missing controller tracking releases UI ownership.
The right hand takes priority over a valid left-hand fallback. When available,
`XR_FB_hand_tracking_aim` supplies the aim pose and index pinch. Worldspace and
explicitly selected original mesh UI request OpenXR API 1.1.53 for Meta's corrected aim flags and Shell-aligned pose;
`XR_FB_hand_tracking_aim` supplies the aim pose and index pinch. Worldspace alone
requests OpenXR API 1.1.53 for Meta's corrected aim flags and Shell-aligned pose;
an unsupported API fails explicitly without a silent downgrade. Computed + valid
runtime aim, an active hand and a tracked wrist are required; system gestures
cancel input. If the extension is unavailable, the ray runs from wrist toward
index proximal, with thumb/index tip separation providing 25mm press and 40mm
release thresholds. A newly tracked hand must first open its pinch. Ownership
changes, focus loss, missing tracking and non-rendering frames cancel drag capture
and remove its visual.

The explicitly selected original-product panel uses interaction edges instead
of tracked-controller hover priority. An idle tracked controller cannot block
an open hand's next pinch. A fresh trigger or pinch takes an idle pointer;
simultaneous fresh edges choose the controller once. The pressed owner retains
capture through release, and competing edges during that press are discarded.
Tracking loss, a different hand, an aim/joint source change or focus reset
requires an open input before another press. The existing cancel-before-owner
handoff path still supplies one Android pointer. Neutral worldspace retains
its tracked-controller priority. This changes UI arbitration only, not original
hand visuals, scene input or simultaneous tracking selection.

Original-product UI also requests optional available `XR_FB_hand_tracking_aim`
through the existing same-session trackers. When absent, the existing tracked
joint ray remains; a tool's FB aim target does not establish where that different
joint ray hits. Current markers distinguish tracked controller rays from trigger
state, selected arbitration, actual hand source and optional aim enablement.

The app draws a solid depth-tested Vulkan ray and a hit reticle independently of
the Android UI texture. White indicates aiming, green a panel hit, and amber a
press. The runtime supplies input data, not these graphics; no Meta sample assets
or new hand model are included. Existing combined GPU hand meshes remain the hand
visuals. Both worldspace eyes use the same neutral background clear.

Panel visibility is a generation-bound presentation setting. Hiding it suppresses
the panel mesh, pointer graphics and pointer capture while the same native scene,
recording and audio owners continue. Existing panel/menu recalls show the same
mesh presentation through the same visibility handler. The Java facade requests
`nativeSetPanelVisible(generation, visible)` once and receives a positive revision;
`nativeReadPanelVisibility(generation, revision)` reports pending until a submitted
frame carries that exact revision, or stale after a newer request. Revision zero
reads the latest visibility snapshot without changing it. `applied` describes
native frame submission, not acoustic output, XR focus or visible compositor proof.

Joints use the existing `XR_EXT_hand_tracking` trackers, projection reference
space and current predicted display timestamp. No extra frame loop is created.
In the neutral worldspace composition, when controller input owns the frame, live hand visuals, diagnostic
hand overlays and graft copies are suppressed. Ordinary controller actions remain
available; events captured by the panel are consumed before world controls.

The neutral combined hand-geometry feature closure should retain
`input.controllers_and_hands_optional` and its selected live-hand/visual
features, but omit
`input.simultaneous_hands_and_controllers`. Remove its enabled property and
resolver-derived activation binding from the private runtime closure. Worldspace
policy rejects an accidentally selected simultaneous adapter. The manifest resolver
When controller input owns the worldspace frame, live hand visuals, diagnostic
hand overlays and graft copies are suppressed. Ordinary controller actions remain
available; events captured by the panel are consumed before world controls.

The app feature closure should retain `input.controllers_and_hands_optional`
and the selected live-hand/visual features, but omit
`input.simultaneous_hands_and_controllers`. Remove its enabled property and
resolver-derived activation binding from the private runtime closure. Worldspace
mode rejects an accidentally selected simultaneous adapter. The manifest resolver
emits `com.oculus.handtracking.support=HANDS_AND_CONTROLLERS` and hand version
`V2.0` only when both the optional hand feature and hand permission are selected.
This permits either input mode at launch without claiming simultaneous tracking.

The explicit original-composable product descriptor selects the separate
`original-product` input policy through its authenticated
`RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS=false` compile input. It retains the
original simultaneous-tracking feature and exact activation/hand-adapter binding,
extension availability, function resolution and session resume/pause guards.
Controller readiness does not suppress original hand visuals, grafts, compact
hand input or source-specific recorded visual fallback. A captured panel event
may consume discrete world actions, but cannot discard the original controller
breath assessment sample. The panel still arbitrates one pointer at a time;
this is independent of underlying hand/controller tracking. Markers separate
`exclusivePanelPointer` from `exclusiveHandsOrControllers`, identify the selected
`inputPolicy`, and report actual selected simultaneous tracking requirements.

The public foundation additionally selects `input.tracked_hand_pointer`. Its
explicit packaged flag enables runtime aim (or its four-joint fallback) even
while compact mesh input is disabled. It needs neither a hand-adapter lock nor a private or recorded
rig input: the pointer consumes positions directly in the projection reference
space. The existing hand tracker owns the raw acquisition, using position-valid
and position-tracked flags on all four joints (`0x2 | 0x8` in OpenXR). A private
live-skinning composition can select this feature as well; it reuses its existing
current compact sample when available and polls raw joints only as a fallback.
Live GPU rig mapping still requires that composition's independently applied
hand-adapter lock. The pointer feature never substitutes for that lock. Neutral worldspace
never uses recorded poses as a fallback: missing live compact input hides the
corresponding hand, while the static topology/bind rig remains available. Live
poses are selected by each resident rig's handedness: right-only tracking stays
on the right rig and never promotes right poses into the left bind mesh. Other
render modes keep their existing replay policies. The short `worldspace-hand-input`
marker reports per-hand active/ready/source/locate state, shape counts and reason
every 120 frames and on state changes; raw pointer input remains rig-independent.
hand-adapter lock. The pointer feature never substitutes for that lock.

Pure host tests cover bounded ray construction, pinch hysteresis, controller
priority, hand switching and tracking loss. Resolver tests cover manifest
eligibility. Hand usability, launch without controllers and wearer-visible mode
switching still require validation of the exact replacement APK on a headset.

The `native-worldspace-integration` render mode composes camera effects, resident
GPU particles, live GPU hand meshes, hand graft copies and a world-positioned UI
mesh in one native OpenXR/Vulkan session. The mode is opt-in. Existing closed
particle and camera-layer modes keep their own composition rules.

The public `native-worldspace-panel-test.app.json` selects the scene, UI and
neutral visible pointer foundation, with compact rig input disabled and no
private payload. A downstream composition can select
The public `native-worldspace-panel-test.app.json` selects only the scene and UI
foundation. A downstream composition can select
`renderer.private_particles.composable` and `render.private_layer.composable`
alongside the existing hand features. Private payloads and shader formulas remain
inputs supplied by their owners; the public renderer owns frame scheduling,
resource lifetime and draw ordering.

## Scene and panel contract

All participating world geometry uses the same eye poses, asymmetric OpenXR FOV
and perspective clip coordinates, with a 0.05 metre near plane and 20 metre far
plane. Each eye swapchain image has an app-owned depth attachment. Opaque hands
and the panel test and write depth; translucent particles test depth without
writing it. Camera projection remains the background. The combined route uses
the common full-resolution scene instead of a particle-only half-resolution
target that cannot share scene depth.

This is virtual scene depth. It does not itself submit projection depth to the
OpenXR compositor or add sensed environment-depth occlusion. Existing environment
depth support has separate permissions, resources and evidence requirements.
Grafts currently mean GPU-skinned hand copies; anatomical seam welding is not
implemented by this composition.

The UI producer runs Android widgets in a Presentation on an app-owned virtual
display, backed by an NDK ImageReader Surface. Vulkan imports each RGBA hardware
buffer on the existing XR device and samples it on a world quad. The app has one
NativeActivity and requires no separately launchable control-panel Activity.
The panel's pose and dimensions are scene values, not an Android window pose.
Its first valid head pose places it 1.4 metres ahead of the eye midpoint, with
the corresponding orientation, then leaves it anchored in the reference space.
Distance controls move along the panel normal; reset restores that initial pose.
The build selects it through `ui.native_mesh_panel` and
`RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ENABLED=true`.

Controller aim rays intersect the quad to produce UI pixel coordinates. Trigger
press, drag and release are captured by the panel before ordinary scene actions.
UI requests are generation-bound; stale callbacks cannot edit a replacement
session. Renderer-observed revisions indicate that the render loop applied the
control snapshot, not that a person saw the result.

The hardware-buffer handoff must preserve Android producer pixels, wait only for
ready producer frames, acquire foreign queue ownership before sampling, and
release ownership after the GPU consumer retires. Device support and actual
buffer compatibility are checked before enabling the path. Teardown must stop
the producer before deleting the ImageReader and retire submitted GPU resources.
Java shutdown uses a generation-specific asynchronous request and acknowledgment.
The native owner pumps its existing Android events during the bounded wait so
window callbacks can finish and queued UI closure can run. A timeout, failed
closure or Activity destruction retains the reader. Consumed Destroy events
remain terminal for later error-loop pumping on the same native Activity thread.
The Android writer starts only after the fallible frame-resource allocations.
Invalid head-view frames cancel pointer capture just like other skipped frames.
If GPU retirement fails, including the final ownership-release submission, teardown
quiesces the writer and retains the panel, device resources and Vulkan/OpenXR
loaders until process exit. It reports `retained-until-process-exit`; this is a
failed cleanup requiring process retirement, not a successful reusable shutdown.
The outer renderer owns projection swapchains, foveation profiles and eye depth
resources through every frame-loop return. It destroys them only after successful
GPU retirement, or retains them with the device on retirement failure.

An unselected simultaneous-tracking adapter remains inert across session creation
and READY callbacks. It owns no session generation or platform resume call; the
selected adapter retains its exact binding and generation checks.

## Build and validation

The shared launch authority owns physical actor admission and one-shot epochs.
`NativeRendererSelfKioskRemotePreparation` owns the selected remote preparation
and foreground/service effects. The native remote callback targets that adapter;
the standalone Java inventory keeps its existing exclusion of kiosk sources.
Run `Test-ExperimentSessionStandaloneOwner.ps1` with `-AndroidJar` to compile the
actual standalone closure, and `Test-PolarTrustedColdStart.ps1` for the selected
remote owner, JNI class binding, unselected applications and replay controls.
The soft-kiosk policy harness uses a fail-fast compile-only panel dependency;
its policy traces do not qualify Android panel dispatch.

Resolve an app spec with `tools/Resolve-NativeAppBuild.ps1 -DryRun`. Build the
resulting content-addressed feature lock with
`tools/Build-NativeRendererAndroid.ps1 -AppBuildLock <lock> -OutDir <new-output>`.
Use a compatible isolated sibling source closure for Quest's Rust dependencies;
do not retarget shared workspaces to make a local build pass. Downstream private
build adapters must hash their payloads, shader inputs and source identities.

Host checks establish projection/ray math, callback generations, feature
resolution and compiled Android/shader compatibility. They cannot establish
headset rendering or performance. The live probe should progress through:

1. Panel pixels, trigger capture/drag, pose/size controls and repeated app teardown.
2. Live hands crossing in front of and behind the panel, with grafts off then on.
3. Resident particles crossing hands and the panel, checking transparency order.
4. Camera effects alone, then camera plus hands, then the combined workload.
5. Recorded frame pacing, active particle/tracer counts and validation/log errors.

Keep source, feature lock, APK hash and exact headset tied to each capture. Report
UI command application, visible behavior and timing evidence separately. A built
APK is a candidate until its live checks pass.

## Particles-only scene and full local controls

`renderer.native_scene.particles_only` selects the existing native scene while
excluding hand geometry resources. Its exact resolved lock supplies
`RUSTY_QUEST_NATIVE_RENDERER_HAND_GEOMETRY_ENABLED=false`; the APK builder
rejects an unselected or ambient exclusion, contradictory geometry features,
and prepared/captured rig inputs. The selected binary has no materialized replay
source, hand skinning, mesh, graft, hand-anchor or capture-recorder resources.
Generic shared shader code and renderer types remain in the source graph.
Runtime visibility controls cannot re-enable the absent resources.

Optional `input.tracked_hand_pointer` and
`input.controllers_and_hands_optional` still use the current OpenXR tracker,
reference space and predicted display time. Pointer input requires no mesh rig.
Controller breath assessment, particle/tracer buffers and retained particle
anchors keep their existing owners. The particles-only route omits the probe's
diagnostic animation/scale tuple from particle compute; canonical accepted
settings remain the controls.

NativeActivity cold startup synchronously prepares the selected canonical Java
packaged closure before Rust inventory initialization and immersive-owner
capture. Verification failure stops startup. Repeated panel preparation
re-verifies the same application/files scope and preserves the condition and
phase audio owners; failed verification installs neither owner. Compact live
hand input requires compiled hand geometry, while raw UI pointer tracking
remains independent. Explicit visual, mesh, graft and anchor effects still
require the applied hand lock.

The selected `ui.experiment_session_owner` presents local Home, Particles,
Breath, Status and experiment workflow pages through its closed Java inventory.
The real standalone Activity and native Presentation share the local panel;
the legacy composite panel also uses the extracted sparse particle-patch
builder. Particle changes submit edited named fields through the existing JNI
queue and require a matching renderer-effective revision before reporting
application. Hydration, retired pages and detached producers issue no edits.
The session readback header is separate from local command outcomes. It reports
current only after an accepted, initialized read matches the coordinator's
runtime epoch and completion witness. After three missed 500 ms polls its label
becomes stale; this display threshold grants no command authority. Pause resets
the observation, and read failures remain unavailable until a newer accepted
read arrives. The header does not attest physical input, audio or saved output.
In-view choices stay within the panel's routed touch surface. Sensor, network,
kiosk and camera capabilities require their separate explicit selections.

The combined original product panel and native mesh selection uses the real
`NativeProductPanelActivity`, an Android `NativeActivity` subclass with the same
native library and main loop. `BreathCompositionPanelModule` is the shared
original controller. The ordinary 2D entry is a thin Activity adapter for that
same controller; its builders, listeners, Polar, network, LSL, session and audio
owners are not copied. The native host buffers the original content until its
Presentation carrier exists and never replaces the native window's content.
There is no Shell-managed 2D bootstrap or Activity launched onto a private
virtual display. Native callbacks retain their actual controller; UI route
Intents do not replace the physical MAIN/LAUNCHER Intent.

The selected native host and its Presentation share the actual Activity density
and the original inherited Material theme. Neutral mesh and standalone density
remain unchanged. Callback retirement prevents delayed permission/results or
old host lifecycle callbacks from entering a replacement controller. A duplicate
2D entry relays into the current native controller without creating another
shell or audio owner. Return-to-VR and terminal actions retain their original
coordinator/handler routes; native presentation requests still need effective
submitted-frame acknowledgement.

Pending visibility intent stays with the exact real owner and producer
generation. Retirement or owner replacement cannot carry that intent forward.
A selected product whose owner has not attached cannot confirm hidden-panel
visibility; the separate ordinary 2D-only route retains its original foreground
proof. Product operator observation can be unavailable while the real Activity
is paused and has no active mesh attachment; that availability guard is not an
observation exception. Native lifecycle resume alone does not make a hidden
mesh UI visible. Source fixtures check actual adapter callback routing, stale
owners, the native surface boundary, original method preservation and selected
manifest closure. Actual lifecycle, input, dialogs and pixels still need device
verification.

The focused admission check is
`pwsh -NoProfile -File tools/checks/Test-NativeRendererHandGeometrySelection.ps1`.
Java/controller fixtures, both Rust geometry selections and the exact generated
Android source closure must pass before packaging. On a headset, verify the
stereo panel, targeting/scroll/drag, hand/controller ownership changes, effective
settings, calibration, audio/recording and saving separately. Syntax and host
admission checks do not establish those live effects.

Local Save and exit uses the same session worker, durable writer and audio
shutdown barrier as the existing terminal command. Its captured native owner,
runtime epoch, actual generation, session identity and binding are rechecked on
that worker before cutoff. A preparation state after Save-next retains its real
generation; it does not invent a new recording or reuse an old active identity.
Paused hosts replay only the exact cached terminal result on their current
attachment. Temporary busy readback keeps observing the accepted command.

Renderer retirement requires the exact finalized operation/generation/shutdown
acknowledgement, a joined worker and a closed writer. The captured projection
loop requests OpenXR exit, completes its ordinary GPU/UI/XR cleanup, then
finishes its NativeActivity. Cleanup failure retains the resource error and
cannot report retired. The Java continuation validates every retirement scope
field; an uncertain request is never replayed, while an explicit request-side
not-ready response allows a bounded retry. A read response cannot reopen that
request. The selected facade has production host fixtures and typed JNI exports;
actual Android linking, lifecycle and headset shutdown require their own checks.

## Optional passthrough and environment sampler

The existing `environment_depth.projection_sampler` feature can also be selected
with `native-worldspace-integration`. It declares scene permission and the
passthrough manifest feature, requests the runtime provider, and requires native
passthrough. Its resolved permission/app-op pregrant remains mandatory before
first launch; the app also requests scene permission when this provider is
selected. Worldspace composition then uses source alpha and a transparent clear,
including when the Android mesh panel is present. A panel-only build without this
selection retains its opaque neutral background. Other render-mode alpha
policies remain unchanged.

The explicit `renderer.background.solid_black.composable` descriptor selects
`RUSTY_QUEST_NATIVE_RENDERER_BACKGROUND_MODE=solid-black` in the authenticated
build environment. Only the native worldspace mode adopts this compile-time
selection; ordinary scenes keep their existing backgrounds. It outranks the
mesh panel's neutral clear, and reports `solidBlackBackground=true`. Explicit
native passthrough overrides the selection, uses the transparent clear, and
reports `solidBlackBackground=false`.

The original product composition selects
`renderer.private_particles.original_composable`, whose exact build input is
`RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS=false`. The builder requires its
native scene, mesh panel and explicit black-background feature closure. It
rejects either composition input without its descriptor, including ambient
values on unlocked development builds. This disables the neutral mesh demo's
camera, diagnostics, graft, hand, replay, effect, animation and particle-scale
overrides. Existing app settings owners continue to supply those parameters.
Pointer dispatch, panel pose and submitted-frame visibility continue to use the
raw mesh frame. Ordinary neutral mesh fixtures retain their controls.

Visibility readback includes `neutral_controls_enabled` and
`settings_authority`; submitted mesh markers include `neutralControlsApplied`.
These distinguish input/visibility observation from demo-setting adoption.
They do not establish original owner setting effects or headset appearance.
Run `tools/checks/Test-NativeRendererPanelComposition.ps1` for admission checks
and actual owner-module host tests in ordinary and original compile modes.

The sampler feeds the camera/compositor path. The hands, particles, and panel
still use virtual-scene D32 depth; selecting the sampler does not provide those
draws with real-environment occlusion. Passthrough composition, virtual-scene
depth, and environment-depth sampling need separate effective markers and live
validation. Downstream camera selection must choose its intended normal
composition rather than a diagnostic shader output; passthrough cannot change
the meaning of that selection.

## Camera result metadata

The retained-Activity compatibility bootstrap keeps one return-to-immersive obligation even when
Android reports both the original panel Activity and NativeActivity resumed.
Resumed lifecycle alone does not establish immersive focus. Real owner readiness
uses the original full handoff once, including during reentrant attachment. A
current-producer, exact-owner, thread-scoped one-shot bypass makes that handoff's
automatic mesh-hide shortcut fall through without clearing earlier explicit
visibility intent. Later requests use the ordinary owner/generation route. A
dispatched or unknown handoff is not replayed because another resume arrives.
The production state fixture covers always-resumed startup and reentrant attach;
host passes do not establish Android focus, mesh pixels or headset acceptance.
The combined original/native controller route above does not use this 2D
bootstrap.

Optional Camera2 capture metadata is read only when its tag is present in that
result's `ACameraMetadata_getAllTags` inventory. The list is borrowed within the
capture callback; snapshots retain only parsed values. Missing optional fields
remain unavailable, without repeatedly asking Android to log a failed lookup.
Discovery or entry errors do not fabricate values, and no metadata pointers are
cached across frames.
