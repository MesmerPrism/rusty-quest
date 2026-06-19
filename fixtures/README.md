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
  edge enhancement off; the low-noise record 30 profile applies the same public
  controls through Camera2 `TEMPLATE_RECORD`; the low-latency 60 profile
  requests 60 FPS AE with fast noise reduction; the 1280x960 profile tests
  reader-size ranking with target-FPS and min-frame-duration markers; the
  direct-HWB route also exposes optional image-dataspace and Vulkan luma/range
  diagnostic markers through
  `debug.rustyquest.native_renderer.camera.luma_diagnostic.enabled`; and the
  hold-sync profile retains sampled `AImage` objects until the submitted Vulkan
  frame-slot fence retires. Hold-sync reader6 and reader8 variants raise
  `camera.reader_max_images` for queue-headroom A/B checks. The
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
  draws only resident-mesh anchor particles for topology comparison. Anchor
  particles also expose standard transparency modes (`legacy-additive-multiply`,
  `true-additive`, `fade`, `premultiplied`), composition modes
  (`true-additive`, `approximate-depth-suppressed`), and ordering modes for
  fixed/eye-depth hand draw order plus resident GPU index-remap sorting.
  CPU-sorted render-buffer ordering is not used by the native Quest path
  because steady state must not upload expanded particle arrays.
  `quest-native-renderer-environment-depth-status.profile.json` is the
  first environment-depth status-only profile. It sets only scalar
  environment-depth properties such as mode, source, reference space, capacity,
  stride, range, and `high_rate_json_payload=false`; damaged fixtures reject
  high-rate JSON, invalid capacity, and invalid near/far range attempts.
  `quest-native-renderer-native-passthrough-environment-depth-particles.profile.json`
  is the synthetic pure-GPU proof route: native passthrough is enabled, hand/SDF
  overlays are disabled, a compute shader writes reference-space particle rows
  into a resident Vulkan buffer, and the draw path reports zero CPU-expanded
  particle upload. It intentionally marks the depth source as
  `synthetic-gpu-proof` until a real environment-depth provider is bound.
  `quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json`
  is the real Meta provider proof route: it selects
  `scene-particle-map` plus `xr-meta-environment-depth`, requires the
  `USE_SCENE` permission path, and expects acquired D16 two-layer depth, valid
  pose, nonzero source depth samples, OpenXR-local world-space scene cells,
  spatial-hash map policy, preserve-existing-cells invalid-sample behavior,
  visible-free-space correction, zero expanded CPU particle upload, resident
  GPU buffers, and device-local particle memory.
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
