# Rusty Quest Fixtures

- `runtime-profiles/`: valid Quest runtime profiles, including separate native
  renderer profiles for no-real-hands recorded replay acceptance and the later
  live-hand visual diagnostic retest. The direct-HWB camera quality profiles
  force `debug.rustyquest.native_renderer.camera.output=direct-hwb` and
  disable hand/SDF/private overlays plus the direct-camera border overlay so
  camera acquisition and projection can be inspected before guide or private
  processing. The default direct-HWB quality profile keeps Android-suggested
  sampler YCbCr model/range and requests UNORM swapchain selection, while the
  BT.601/UNORM variant forces limited BT.601 for color-lift A/B checks. The
  low-noise 30 profile keeps the Android-suggested/UNORM baseline and requests
  support-gated public Camera2 controls for 30 FPS AE, noise reduction, and
  edge enhancement off. The
  HWB peripheral stretch
  profile keeps the custom Camera2 projection route active while enabling the public
  full-eye target-edge stretch/blend border profile. The native passthrough
  graft-only profile keeps the normal projection profiles available while
  testing only fingertip graft models over `XR_FB_passthrough`, with custom
  Camera2 projection and SDF visuals disabled. The native passthrough
  hands-and-grafts profile uses the same route but also enables the base real
  hand meshes from the GPU-skinned resident buffers. The solid black
  hands-and-grafts profile disables both passthrough and custom Camera2
  projection so only an opaque black background and hand visuals are submitted.
  The solid black OpenXR-hands anchor-particles profile keeps the black
  background and live compact hand input but disables the app's custom hand
  mesh and graft visuals, requests the runtime/default OpenXR hand visual, and
  draws only resident-mesh anchor particles for topology comparison.
- `native-renderer/`: valid Quest-native renderer plans, timing scorecards, and
  public recorded-hand topology/shape fixtures for pure-HWB blur, GPU mesh
  boundary, resident compact-joint GPU-skinned visual examples,
  recorded-compatible live compact hand input evidence, target-space
  skinned-mesh GPU SDF cadence/cache examples, private extension ABI slots, and
  accepted no-real-hands replay visual proof logcat evidence. Replay visual
  proof markers include camera target rectangles plus separate hand-mesh and
  SDF overlay evidence rectangles so screenshot checks do not confuse camera
  image variation with mesh/SDF visibility. Overlay evidence checks also track
  expected high-chroma diagnostic color families so grayscale camera detail is
  not accepted as mesh/SDF proof. The normal hand visual is a continuous
  single-surface material; component ranks remain metadata. The live-hand
  diagnostic log fixture is a caveat fixture only: it keeps live mesh/SDF
  acceptance pending until a later screenshot proves visible overlay color.
- `remote-camera-sessions/`: valid remote camera session plans for
  Quest-to-Quest and Quest-to-Android phone diagnostic streaming, including
  low-rate runtime endpoint bindings for sender source kind, sender media
  profiles, camera permission policy, local receiver ports, peer transport
  ingress, and outgoing transport route adapters.
- `damaged/`: invalid runtime profile, remote-camera, and native-renderer
  examples that must be rejected, including runtime evidence logs where replay
  markers exist but the visual mesh was not actually reported visible, and a
  native renderer replay log whose FPS, stale-frame, CPU-stage, and GPU-stage
  timings exceed the performance-budget gate. Damaged live-hand visual evidence
  also rejects marker-only acceptance without screenshot proof.
