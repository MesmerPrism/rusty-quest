# Native worldspace integration probe

## Hands or controllers

Worldspace mode uses one input owner at a time. An active recognized controller
interaction profile takes priority; otherwise the mesh UI can use a tracked hand.
The right hand takes priority over a valid left-hand fallback. Its app-derived ray
runs from the wrist toward the index proximal joint; thumb/index tip separation
drives pinch with 25mm press and 40mm release thresholds. A newly tracked hand
must first open its pinch. Ownership changes, focus loss, missing tracking and
non-rendering frames cancel drag capture. This is a basic tracked-joint pointer,
not a claim of the platform's hand-aim extension or controller-input proof.

Joints use the existing `XR_EXT_hand_tracking` trackers, projection reference
space and current predicted display timestamp. No extra frame loop is created.
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

The public foundation additionally selects `input.tracked_hand_pointer`. Its
explicit packaged flag enables a four-joint UI sample even while compact mesh
input is disabled. It needs neither a hand-adapter lock nor a private or recorded
rig input: the pointer consumes positions directly in the projection reference
space. The existing hand tracker owns the raw acquisition, using position-valid
and position-tracked flags on all four joints (`0x2 | 0x8` in OpenXR). A private
live-skinning composition can select this feature as well; it reuses its existing
current compact sample when available and polls raw joints only as a fallback.
Live GPU rig mapping still requires that composition's independently applied
hand-adapter lock. The pointer feature never substitutes for that lock.

Pure host tests cover bounded ray construction, pinch hysteresis, controller
priority, hand switching and tracking loss. Resolver tests cover manifest
eligibility. Hand usability, launch without controllers and wearer-visible mode
switching still require validation of the exact replacement APK on a headset.

The `native-worldspace-integration` render mode composes camera effects, resident
GPU particles, live GPU hand meshes, hand graft copies and a world-positioned UI
mesh in one native OpenXR/Vulkan session. The mode is opt-in. Existing closed
particle and camera-layer modes keep their own composition rules.

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
