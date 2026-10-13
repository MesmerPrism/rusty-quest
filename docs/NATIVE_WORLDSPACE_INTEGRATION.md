# Native worldspace integration probe

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
