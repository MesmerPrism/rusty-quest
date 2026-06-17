# Rusty Quest Fixtures

- `runtime-profiles/`: valid Quest runtime profiles, including separate native
  renderer profiles for no-real-hands recorded replay acceptance and the later
  live-hand visual diagnostic retest.
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
  not accepted as mesh/SDF proof. The live-hand diagnostic log fixture is a
  caveat fixture only: it keeps live mesh/SDF acceptance pending until a later
  screenshot proves visible overlay color.
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
