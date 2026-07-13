# Rusty Quest Fixtures

- `particle-adapter/`: the closed two-consumer conformance fixture for the
  Matter/Lattice/Optics-to-Quest particle adapter. Both Spatial Camera Panel
  and native renderer default disabled; no high-rate JSON or backend payload is
  admitted.
- `hand-adapter/`: the closed Lattice/Matter/Optics hand conformance fixture
  for the native OpenXR hand lab and Spatial Camera Panel. It fixes both-hand,
  coordinate-basis, CPU/prepared parity, default-disabled, and backend-exclusion
  rules; damaged provider substitution must fail closed.
- `spatial-hand-alignment/`: sanitized OpenXR-to-Spatial SDK mapping fixtures.
  The accepted viewer-world basis registration remains the rollback default;
  mirror/reflection candidates stay explicit until a separate live headset
  run validates viewer, hand-anchor, and joint-marker placement.
- `native-app-features/`: source-only native APK feature descriptors used by
  `tools/Resolve-NativeAppBuild.ps1`. Each descriptor names dependencies,
  incompatibilities, manifest surface, runtime-profile property ownership,
  generated build inputs, and required/forbidden markers for one selectable
  capability. The directory is a recursive module library: `module_path`
  names the durable family, and particle capabilities are nested below
  `particles/` so aggregate app features can depend on payload-slot,
  placeholder, ordering, mask, and tracer submodules without hiding them in a
  broad profile.
- `native-app-builds/`: app-build specs for new native APK shapes. Agents
  should request feature ids here and run the resolver instead of copying a
  nearby runtime profile or broad Android manifest. Damaged app-build specs
  prove denied features, permission supersets, render-mode mismatches, and
  high-rate JSON misuse are rejected before any APK build. Resolved builds
  generate `native-app-settings.json` as the master settings surface; Android
  properties, generated manifests, build env, and headset launch profiles are
  adapters from that file rather than separate launch-mode authorities.
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
  full-eye target-edge stretch/blend border profile. The HWB video-border
  blend profile keeps the same custom Camera2 guide projection route but uses
  the stereo video stream as the full-eye background and fades the camera guide
  overlay into that video through the existing target inner-band blend controls.
  It explicitly owns `debug.rustyquest.native_renderer.video_border_blend.mode`
  so shader-composite modes cannot be confused with the fixed-function
  `alpha-over` baseline. `tools/Invoke-NativeRendererVideoBorderBlendSweep.ps1`
  generates transient per-mode profiles and captures visual/timing artifacts for
  all public modes except Poisson/gradient-domain blending.
  The Breathing Room PMB
  scale profile adds the same stretch route plus the source-agnostic Manifold
  controller-pose bridge and a right-controller haptic pulse when PMB drives
  scale and the right grip pose is tracked. The native passthrough
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
  Downstream private particle effects can use the generic private particle slot
  without adding private constants to Rusty Quest: public builds compile a
  no-op placeholder, while private builds provide payload data, shader, kind,
  marker prefix, and opaque marker fields through
  `RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_*`. The public slot owns the
  four-row billboard ABI, sampled R8 texture-array mask path, resident GPU
  index-remap ordering, and parameterized transparency controls; downstream
  payloads own only effect-specific compute behavior and proof markers.
  The display-composite feedback profile configures native Meta passthrough as
  the background and Android MediaProjection as the only app-rendered feedback
  plane. It owns stale visual switches so Camera2 output, guide blur, hand/SDF
  visuals, environment-depth particles, stimulus volume, and private visual
  layers are disabled before the Rust/NDK `AImageReader`/`AHardwareBuffer`
  stream is sampled through Vulkan. The selected
  `gpu-recursive-feedback-diagnostic` mode folds that sampled stream through an
  app-owned device-local feedback texture without diagnostic borders or
  previous-feedback blending before projecting it into the field of view with
  fully opaque premultiplied alpha, luma-damped feedback, and an aggressively
  shrunken centered target footprint.
  The stream has bounded resolution, queue depth, frame cap, explicit
  projection metadata, and `high_rate_json_payload=false`; it is not raw camera,
  passthrough texture, environment-depth, or geometry evidence. Device evidence
  for this fixture is owned by `tools/Invoke-NativeRendererDisplayCompositeSmoke.ps1`, which uses a
  fresh `display_composite_request_token`, marker-scoped logcat, screenshot
  evidence, and `PROJECT_MEDIA` reset after the run.
  The fullscreen stereo video profile is a video-only custom projection route:
  Camera2 output, display-composite capture, guide blur, hand/SDF visuals,
  environment-depth particles, stimulus volume, private layers, and projection
  target controls are disabled while Android `MediaCodec` decodes the
  app-private `video/noodletest-sbs.mp4` side-by-side source into a Rust-owned
  `AImageReader` surface. The profile records left-eye UV
  `0.0,0.0,0.5,1.0`, right-eye UV `0.5,0.0,0.5,1.0`, full-eye target metadata,
  queue depth, frame cap, looping, opacity, and
  `high_rate_json_payload=false` so downstream scale/overlay work can adapt the
  source without baking stereo layout assumptions into the renderer.
  `tools/Stage-NativeRendererVideo.ps1` stages the user-provided MP4 into the
  package-scoped external `files/v.mp4` path through serial-scoped ADB and
  records the compact absolute `video_projection_path` for the property
  override without adding broad storage permissions.
  The solid-black stimulus-volume profile is the current central-FOV limit
  fixture for bright volumetric interference: it requests a 1024x1024x2 stereo
  storage target, 18 raymarch samples, a 0.72 central-FOV fraction, and smooth
  weighted gradient accumulation while preserving A/right-primary
  randomization over a Trevor-inspired browser-portable pattern vocabulary and
  keeping Breathing Room haptics/reset controls disabled.
  The balanced solid-black stimulus-volume profile keeps the same route,
  central-FOV fraction, smoothing, safety acknowledgement, and randomization
  range while dropping to a 768x768x2 stereo target and 12 raymarch samples for
  72 Hz quality A/B checks after the limit tier is GPU-bound.
  The performance solid-black stimulus-volume profile keeps the same route,
  central-FOV fraction, smoothing, safety acknowledgement, and randomization
  range while dropping to a 512x512x2 stereo target and 12 raymarch samples for
  the first high-headroom 120 Hz/native-clock exploration tier measured on the
  2026-06-19 Quest 3S resolution sweep. Its runtime markers now also expect
  the saved startup dynamics `headset-randomize-count-28-2026-06-20`: a
  `spiral` pattern at 3.084 Hz with spatial oscillators 6.041, 35.362, and
  37.531 Hz before any new right-primary randomization.
  The native-passthrough stimulus-volume fixture is the balanced comparison
  tier at 768x768x2 and 14 raymarch samples. Damaged stimulus-volume fixtures
  reject invalid randomization, missing safety acknowledgement, and out-of-range
  central-FOV or gradient-smoothing quality controls.
  `quest-native-renderer-environment-depth-status.profile.json` is the
  first environment-depth status-only profile. It sets only scalar
  environment-depth properties such as mode, source, reference space, capacity,
  stride, range, and `high_rate_json_payload=false`; damaged fixtures reject
  high-rate JSON, invalid capacity, invalid near/far range, invalid
  source-layer requirements, and impossible radius/min-neighbor threshold
  attempts.
  `quest-native-renderer-native-passthrough-environment-depth-particles.profile.json`
  is the synthetic pure-GPU proof route: native passthrough is enabled, hand/SDF
  overlays are disabled, a compute shader writes reference-space particle rows
  into a resident Vulkan buffer, and the draw path reports zero CPU-expanded
  particle upload. It intentionally marks the depth source as
  `synthetic-gpu-proof` until a real environment-depth provider is bound.
  `quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json`
  is the real Meta provider proof route: it selects
  `scene-particle-map`, `xr-meta-environment-depth`, and
  `layer_policy=mono-layer0`, requires the `USE_SCENE` permission path and an
  active native `XR_FB_passthrough` layer for non-sentinel Quest depth payloads,
  and
  expects acquired D16 two-layer depth with explicit mono-layer source markers
  (`environmentDepthSourceViewCount=1`,
  `environmentDepthSampledLayerMask=0x1`,
  `environmentDepthShaderLayerPolicy=mono-layer0`), valid pose, nonzero source
  depth samples, OpenXR-local world-space scene cells,
  spatial-hash map policy, preserve-existing-cells invalid-sample behavior,
  confidence-gated visible-free-space correction, the
  `near-plus-cell-step-cap` free-space range policy, zero expanded CPU particle
  upload, resident GPU buffers, device-local particle memory, explicit render
  view-state flags, capture-to-display/frame-age timing, repeated-capture and
  unavailable-streak counters, texture-transform/ray-UV/sample-UV policy
  labels, and the free-space confidence-skip counter.
  The known-distance raw-D16 wrapper can run the same profile with
  `-RequireEnvironmentDepthKnownDistance`, checking center reconstructed
  meters, center confidence, and center-window valid counts against a measured
  target distance before the projected-depth formula is accepted or replaced.
  The series checker validates monotonic reconstructed meters and raw D16 across
  the 0.5 m, 1 m, 2 m, and 4 m artifact summaries.
  The evidence-bundle checker ties those known-distance run summaries to the
  movement run summary and keeps the human headset visual gate explicit.
  The acceptance-suite wrapper runs the motion, known-distance, series, and
  bundle gates in the intended final device order.
  `quest-native-renderer-native-passthrough-meta-environment-depth-particles-layer1.profile.json`
  is the matching mono-layer1 comparison route; it requires
  `environmentDepthSampledLayerMask=0x2` and
  `environmentDepthShaderLayerPolicy=mono-layer1` without enabling stereo
  fusion.
  `quest-native-renderer-native-passthrough-meta-environment-depth-particles-low-capacity.profile.json`
  is the bounded-map stress route for the same real Meta provider path. It
  keeps layer 0, OpenXR-local scene cells, raw D16 projected-depth sampling,
  and native passthrough fixed while lowering `particle_capacity` to 64 and
  `sample_stride_pixels` to 4 so a headset run can prove low-capacity hash
  pressure through the scene-map health counters.
  `quest-native-renderer-native-passthrough-meta-environment-depth-particles-debug-colors.profile.json`
  is the diagnostic-color route for the same real Meta scene-map path. It uses
  `environment_depth.debug_view=free-space-state` and expects
  `environmentDepthParticleDebugColorMode=free-space-state`; the default,
  layer-1, and low-capacity routes expect `depth-gradient`.
  The `quest-native-renderer-envdepth-*.profile.json` Iteration 8 matrix keeps
  that same real provider scene-map route fixed and varies one acceptance axis
  per fixture: layer 0, layer 1, raw-D16 debug, OpenXR local reference space,
  OpenXR stage reference space, 65536-particle capacity, 8-pixel sample stride,
  or the Meta environment-depth hand-removal request.
  The surface-support profiles also expect pending
  `environmentDepthSurfaceLifecycleStatus` markers plus component-mode,
  small-component policy, normal-source, and zero aggregate component/normal
  counters in dry-run output; runtime log fixtures carry active
  candidate/confirmed/local-patch component-hint lifecycle counters only after
  the GPU scene-map path has run. Source-only mirror tests cover
  reference-space reconstruction/reprojection, scene-map
  hash/probe/merge/stale/free-space-retire behavior, same-cell
  two-source-layer promotion, layer-offset single-layer candidate separation,
  impossible oracle-threshold rejection,
  retained-cell neighborhood normals, pose-shifted retained scene-cell samples,
  compact surface descriptor packing, and dynamic object appear/confirm/move
  retirement before headset movement evidence is available.
  Those mirror fixtures are host/test-only evidence. The Quest runtime CPU
  still only coordinates profiles, permissions, provider setup, command
  submission, low-rate pose/timing calculations, and aggregate markers; depth
  projection, retained scene-map writes, support/normal counters, particles,
  and drawing stay in the native GPU path.
  The
  source-layer-agreement profile adds a non-default
  `environmentDepthSourceLayerAgreementRequired=true` dry-run route so two-layer
  agreement can be tested later without making stereo fusion the default.
- `native-renderer/`: valid Quest-native renderer plans, timing scorecards, and
  public recorded-hand topology/shape fixtures for pure-HWB blur, GPU mesh
  boundary, resident compact-joint GPU-skinned visual examples,
  recorded-compatible live compact hand input evidence, target-space
  skinned-mesh GPU SDF cadence/cache examples, private extension ABI slots, and
  accepted no-real-hands replay visual proof logcat evidence. The
  `native-renderer-property-manifest.json` fixture is the typed low-rate
  Android property manifest for the native renderer: it records each property
  name, parser owner, startup-effective lifecycle, profile-owned explicit-set
  clear behavior, runtime-owner default behavior, value kind, accepted profile
  tokens/ranges, and expected validators. The manifest records where defaults
  live instead of duplicating default values out of runtime parser modules.
  Every entry names the runtime parser, profile matrix,
  `Apply-RuntimeProfile.ps1`, and the `rusty-quest-profile` Rust validator as
  low-rate validation surfaces. `check_native_renderer_property_parity.py`,
  `Apply-RuntimeProfile.ps1`, and the `rusty-quest-profile` Rust validator all
  consume or compare against this manifest before accepting native renderer
  runtime-profile values. `tools/Test-NativeRendererProfileMatrix.ps1` owns the
  exact native-renderer profile and damaged-profile inventories, dry-runs every
  valid profile, and rejects every damaged fixture through that same
  manifest-backed apply path.
  `tools/checks/Test-NativeRendererAndroidScaffoldStatic.ps1` owns Android
  manifest, Rust NativeActivity, input pump, Cargo manifest, build script, and
  app README static checks.
  `tools/checks/Test-NativeRendererStimulusVolumeStatic.ps1` owns the
  stimulus-volume renderer, shader, OpenXR action, timing, and route-marker
  static ledger, so stimulus-volume fixture acceptance stays separate from the
  Android harness orchestration layer.
  `tools/checks/Test-NativeRendererProjectionTargetStatic.ps1` owns the
  Breathing Room projection-target, Manifold breath/pose transport, right-hand
  OpenXR input/haptic, and runtime-authority marker static ledger.
  `tools/checks/Test-NativeRendererHandVisualStatic.ps1` owns recorded-hand
  replay, live compact hand input, GPU-skinned hand mesh visual, graft-copy,
  and GPU mesh replay boundary static checks.
  `tools/checks/Test-NativeRendererGpuSdfStatic.ps1` owns target-space GPU SDF
  field, tile-bin, overlay shader, compact-joint upload, cadence/cache, and SDF
  marker static checks.
  `tools/checks/Test-NativeRendererCameraGuideStatic.ps1` owns camera
  projection metadata, guide blur/projection, direct-HWB camera quality
  diagnostic, peripheral-stretch, source-route profile snippet, and native
  camera scaffold static checks.
  `tools/checks/Test-NativeRendererDisplayCompositeStatic.ps1` owns the
  MediaProjection foreground-service declaration, control-panel capture action,
  Rust-created `Surface`, native `AImageReader`/`AHardwareBuffer` descriptor
  bridge, reusable AHB Vulkan import helper, `PROJECT_MEDIA` lab pregrant/reset
  script, display-composite smoke wrapper, profile fixture, and no-CPU-copy
  guard.
  `tools/checks/Test-NativeRendererVideoProjectionStatic.ps1` owns fullscreen
  stereo video settings, Java `MediaCodec`/`MediaExtractor` control,
  Rust-created video `Surface`, native `AImageReader`/`AHardwareBuffer`
  descriptor stream, per-eye source-UV metadata, Vulkan import/sampling,
  profile fixture, staging wrapper, shader compilation, and the Java
  no-CPU-copy guard.
  `tools/checks/Test-NativeRendererOpenXrVulkanStatic.ps1` owns OpenXR/Vulkan
  prerequisite, timing marker, private-slot, render-mode, scorecard, and native
  timing counter static checks. Replay visual
  proof markers include camera target rectangles plus separate hand-mesh and
  SDF overlay evidence rectangles so screenshot checks do not confuse camera
  image variation with mesh/SDF visibility. Overlay evidence checks also track
  expected high-chroma diagnostic color families so grayscale camera detail is
  not accepted as mesh/SDF proof. The normal hand visual is a continuous
  single-surface material; component ranks remain metadata. The live-hand
  diagnostic log fixture is a caveat fixture only: it keeps live mesh/SDF
  acceptance pending until a later screenshot proves visible overlay color.
- `device-link/`: valid `rusty.quest.device_link.v1` reports for host-to-Quest
  connectivity. The first fixture models the Hostess USB broker-stream session
  as reusable data: serial-scoped ADB identity, ADB forward state, Manifold
  WebSocket endpoint readiness, runtime subscriber receipt health, command
  stage results, and stream capability descriptors for WebSocket command
  events, LSL samples, UDP telemetry, and binary media. The
  `wifi-direct-lifecycle-qcl041-windows.pass.json` and
  `wifi-direct-lifecycle-qcl040-android-phone.pass.json` fixtures model
  `rusty.quest.connectivity_wifi_direct_lifecycle.v1` source artifacts for
  QCL-041 Windows and QCL-040 Android-phone Wi-Fi Direct lifecycle evidence.
  `product-wifi-direct-run.pass.json` is the generic no-media product receipt:
  Android topology and network observation remain separate from Rust-owned
  direct sockets, bounded exchange, and cleanup.
  They require live evidence tier, source run and harness identity, matching
  Agent Board quest lease, peer discovery, group formation, bounded TCP socket
  exchange, and cleanup before Hostess may promote direct-Wi-Fi topology.
  `direct-p2p-socket-route.pass.json` is the protocol-neutral
  `rusty.quest.direct_p2p_socket_route.v1` fixture shared by camera and future
  binary stream adapters. `ble-rendezvous-offer.pass.json` and
  `ble-rendezvous-server-ready.receipt.json` cover the compact authenticated
  BLE proposal and one-headset adapter-readiness receipt without claiming peer
  exchange or Wi-Fi authority. `ble-rendezvous-pair.pass.json` covers the
  two-device role swap, authenticated reconnect in each phase, exact device
  correlation, redaction, boundary-state stability, and cleanup contract.
- `media-stream-sessions/`: valid `rusty.quest.media_stream_session.v1`
  source-neutral plans for app-consent MediaProjection display-to-PC H.264 and
  lab-only shell hidden-display H.264. They keep frame bytes on
  `binary-media` and declare packet bounds, capture authority, consent,
  protected-content policy, privacy indicators, and receiver-first bindings.
- `media-runtime-products/`: deterministic cross-repo product bindings for an
  app-consent display-composite source and an independently selected Camera2
  source. Each binds canonical Manifold and Quest hashes plus exact source,
  processor, LAN route/socket, codec, sink, and cleanup owners. Neither fixture
  selects Direct P2P or inherits remote-camera properties/defaults.
- `remote-camera-sessions/`: valid remote camera session plans for
  Quest-to-Quest and Quest-to-Android phone diagnostic streaming, including
  low-rate runtime endpoint bindings for sender source kind, sender media
  profiles, camera permission policy, local receiver ports, peer transport
  ingress, and outgoing transport route adapters. The
  `q2q-direct-p2p-mono.plan.json` fixture proves that direct-P2P route kind,
  scoped socket authority, explicit local bind, peer subnet, and binary media
  plane remain separate validated fields.
  `q2q-direct-p2p-packed-sbs.plan.json` is the explicit packed-stereo variant:
  each direction owns one `stereo` lane, Camera2 `50`/`51`, a bounded
  sensor-timestamp pairing policy, GPU-only left/right composition, one
  direct-P2P route, and no stale-eye reuse. The separate
  `rmanvid-v4-packed-stereo.pass.json` fixture covers the exact 48-byte pair
  extension, packed/per-eye dimensions, eye order, pair sequence, and source
  timestamps. The ordinary two-eye fixtures remain the default/fallback lane.
  Contract tests also map these fixtures into
  `rusty.quest.media_stream_session.v1` compatibility plans so generic media
  modules can reuse the camera descriptions without changing their schema.
- `broker-products/`: exact Android manifest projections for the accepted
  Manifold base, generic media-session, camera, direct-P2P, and BLE product
  locks. Standalone manifests are byte-stable package inputs generated from
  their locks; embedded manifests are projection fixtures. The base and generic
  media-session products contain only network plus notification/background
  data-sync lifecycle permissions. The broad camera-plus-P2P fixture is
  explicitly named legacy compatibility; no fixture is an implicit permission
  union or default enablement claim. Standalone launcher activities are
  exported, start services are non-exported, and admission services are
  exported only behind the signature permission.
- `broker-clients/`: exact per-app client locks for the admission probe, Native
  Renderer, and Spatial Camera Panel. Packaging hashes the raw files and derives
  grants only from each client/product intersection; runtime properties and app
  defaults remain empty so no application policy can bleed through the shared
  broker SDK.
- `broker-authority/`: trusted local standalone/embedded JNI invocations and
  their Quest response projections. Applied, unknown-command, and missing-lease
  pairs preserve identical Manifold dispatch/application receipts; only bridge
  placement, lock fingerprint, and adapter identity differ. Rejected pairs keep
  revision 1, and every response reports `local_acceptance_rules=false` with
  `module.runtime.host` as decision owner. Those files preserve the v1
  stateless projection contract; the real NET-014 server-entrypoint matrix now
  executes the process-local provider tests in `rusty-quest-broker-authority`
  and both JNI crates, covering bounded admission, parity, damage, rebind, and
  fresh provider epochs. Focused runtime tests additionally cover independent
  uses across unrelated revisions, token-scoped invalidation, canonical typed
  effect-parameter binding, packaged-lock/config hashes, and exact grant
  closure.
- `damaged/`: invalid runtime profile, remote-camera, and native-renderer
  examples that must be rejected, including runtime evidence logs where replay
  markers exist but the visual mesh was not actually reported visible, and a
  native renderer replay log whose FPS, stale-frame, CPU-stage, and GPU-stage
  timings exceed the performance-budget gate. Damaged live-hand visual evidence
  also rejects marker-only acceptance without screenshot proof. Native renderer
  damaged profiles include manifest-driven generic property failures such as an
  unsupported camera output token and an unsupported display-composite mode,
  plus environment-depth cross-field failures such as impossible local support
  thresholds, proving the apply path consumes the typed native renderer property
  manifest before ADB writes. Device-link damaged fixtures also reject
  high-rate JSON stream claims and applied command reports without runtime
  receipt stage evidence. Wi-Fi Direct lifecycle damaged fixtures reject
  template/source-identity gaps, lease serial mismatch, missing bounded TCP
  socket counters, and cleanup that did not complete.
  Direct-P2P socket-route damaged fixtures reject infrastructure-WLAN addresses
  and Android-`Network` substitution claims.
  Product Wi-Fi Direct damaged fixtures reject Android socket-authority claims
  and incomplete cleanup.
  Packed-stereo damage cases reject extra/missing lanes, incorrect camera or
  layout identity, CPU composition, stale-eye reuse, excessive pair skew, and
  malformed RMANVID v4 pair metadata.
