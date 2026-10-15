# Native worldspace integration probe

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
`XR_FB_hand_tracking_aim` supplies the aim pose and index pinch. Worldspace alone
requests OpenXR API 1.1.53 for Meta's corrected aim flags and Shell-aligned pose;
an unsupported API fails explicitly without a silent downgrade. Computed + valid
runtime aim, an active hand and a tracked wrist are required; system gestures
cancel input. If the extension is unavailable, the ray runs from wrist toward
index proximal, with thumb/index tip separation providing 25mm press and 40mm
release thresholds. A newly tracked hand must first open its pinch. Ownership
changes, focus loss, missing tracking and non-rendering frames cancel drag capture
and remove its visual.

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

When the original product Activity is selected, starting NativeActivity first
does not imply that the original Activity/view already exists. The mesh
producer requests that real Activity once for its current generation. The
Activity notifies readiness after its original builders and lifecycle run;
the producer attaches its existing view when the actual native Activity
resumes. It reuses the original immersive presentation route, without creating
an unattached Activity or another session owner. Late readiness can repair the
initial missing-owner condition without restarting the app or producer.

Pending visibility intent stays with the exact real owner and producer
generation. Retirement or owner replacement cannot carry that intent forward.
A selected product whose owner has not attached cannot confirm hidden-panel
visibility; the separate ordinary 2D-only route retains its original foreground
proof. Product operator observation can be unavailable while the real Activity
is paused and has no active mesh attachment; that availability guard is not an
observation exception. The host rendezvous fixture checks lifecycle/identity
state only. Actual Activity bootstrap, focus transition and pixels still need
runtime verification.

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

Optional Camera2 capture metadata is read only when its tag is present in that
result's `ACameraMetadata_getAllTags` inventory. The list is borrowed within the
capture callback; snapshots retain only parsed values. Missing optional fields
remain unavailable, without repeatedly asking Android to log a failed lookup.
Discovery or entry errors do not fabricate values, and no metadata pointers are
cached across frames.
