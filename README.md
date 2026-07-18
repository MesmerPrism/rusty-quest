# Rusty Quest

`apps/lsl-rust-float32-loopback-android` is the public LSLC-005L platform
harness for one exact-source-locked Rusty LSL Float32 outlet/inlet execution on
Quest. The exchange, exact-bit checks, activation admission, and immediate port
reuse run in Rust over `127.0.0.1`; Android Java owns lifecycle only. This is
bounded device conformance, not official or non-loopback compatibility.

`apps/lsl-rust-conformance-android` is the public LSLC-005H platform harness
for one exact-source-locked Rusty LSL core-contract execution on Quest. It
keeps Android lifecycle glue separate from the Rust-owned effective marker and
does not broaden Rusty LSL transport or compatibility claims.

Rusty Quest is the Morphospace lane for Quest platform behavior: runtime
profiles, Android property hygiene, permissions, launch planning, and platform
validation evidence.

PowerShell `7.6` LTS or newer, invoked explicitly as `pwsh`, is the supported
host for repository validation, builds, launch wrappers, and child scripts.
Windows PowerShell 5.1 may run the bootstrap detector in
`tools/Test-PowerShellHost.ps1`, but it is not a supported workflow host.

This repo treats ADB and Android properties as transports. They are generated
from validated profiles and produce dry-run/readback evidence rather than
becoming hand-written launch authority.

For concurrent project builds and repeated launches on one headset, use
[APK Build And Run Isolation](docs/APK_RUN_ISOLATION.md): distinct app/package
identity, explicit locked inputs, content-addressed outputs, a hashed run
capsule, and serial-scoped cleanup that restores exact prior properties.

## Device Link Contracts

`crates/rusty-quest-device-link` defines
`rusty.quest.device_link.v1`, the reusable report contract for host-to-Quest
connectivity. A report records device identity, ADB forward/tunnel state,
Manifold broker endpoint readiness, runtime subscriber health, command-result
stages, and stream capability descriptors.

The same crate also defines
`rusty.quest.connectivity_wifi_direct_lifecycle.v1`, the source artifact shape
for QCL-040/QCL-041 Wi-Fi Direct lifecycle evidence. That artifact requires a
live evidence tier, concrete source run and harness identity, an Agent Board
`quest:<serial>` lease that matches the device serial, peer discovery, group
formation, bounded TCP socket exchange, and cleanup before Hostess can promote
direct-Wi-Fi topology.

The device-link crate also owns the reusable
`rusty.quest.direct_p2p_socket_route.v1` contract. It validates the canonical
`direct_p2p_tcp` route kind, `rusty_direct_p2p_socket_authority`, Rusty-owned
socket scope, `p2p0`, an explicit local bind, a supported peer address on the
same `/24`, and the rule that a bindable Android `Network` is optional rather
than required. Camera, telemetry, and later binary stream adapters consume
this lower contract instead of cloning P2P address or authority rules.

`rusty.quest.ble_rendezvous_sidecar_receipt.v1` and the compact `rqrv` wire
contract provide an explicit opt-in BLE/GATT bootstrap lane. BLE may exchange
authenticated role proposals, capabilities, and already-observed P2P/broker
hints; it does not form Wi-Fi Direct groups, execute Manifold commands, carry
media, or become connection authority. A one-headset advertiser run may report
`ready`; pair acceptance requires two authenticated peer phases with reversed
GATT roles and an authenticated reconnect in each phase. The pair artifact is
validated independently through the data-only device-link crate.
The current live baseline is `ble-pair-20260711T025453Z`; it passed both role
layouts, artifact redaction, boundary-state stability, and package cleanup.
ADB-based launch and ephemeral test-secret injection remain evidence
orchestration, not an autonomous provisioning claim.

`apps/direct-p2p-provider-android` is the clean product Wi-Fi Direct provider.
It separates Android group topology, honest Android `Network` availability,
and Rust-owned direct sockets. On current Quest builds the valid `p2p0` route
may have no public Android `Network`; the product receipts that as unavailable
and continues only through an explicit Rust local bind. Run the no-media
two-headset gate with `tools/Invoke-DirectP2pProviderTwoQuest.ps1`.

`crates/rusty-quest-peer-session-adapter` projects a validated BLE pair into a
Manifold peer-session proposal; it does not accept the session itself. In
decision-gated mode the product provider validates a fresh, current-revision
Manifold topology authorization before initializing `WifiP2pManager`.
`tools/Invoke-PeerSessionDecisionGateTwoQuest.ps1` proves unauthenticated,
stale, and revoked decisions cannot reach topology mutation, then proves an
accepted decision can complete the same bounded no-media product exchange.

The same adapter now has an [N-peer projection](docs/N_PEER_MESH.md) that folds
one authenticated live Quest pair plus a sanitized configured third peer into
Manifold's bounded mesh authority. Only the live pair contributes a direct
candidate; Termux/sidecar edges remain advisory. Replay, split brain,
advisory-to-media substitution, expiry, and revocation are explicit gates.

`crates/rusty-quest-media-stream` provides the generic receiver-first platform
runtime described in [Generic Media Stream Runtime](docs/MEDIA_STREAM_RUNTIME.md).
It composes explicit sources, dual-lane/packed/passthrough processors, validated
route references, and sinks after an accepted Manifold decision. Camera2 and
display-composite conformance remain independent; remote-camera plans enter
only through the compatibility adapter.

`apps/qcl041-wifi-direct-harness-android` is the Quest-side producer for the
Windows peer route. It does not need an Android phone: the live path pairs the
Quest APK with the Hostess Windows Wi-Fi Direct helper, records actual
`WifiP2pManager` feature/permission/discovery/group/socket/cleanup state, and
lets the host wrapper finalize Agent Board lease release before Hostess
promotion.

`tools/Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1` is the
QCL-100 facade for Quest-to-Quest native stereo projection over Wi-Fi Direct.
Its helpers live under `tools/qcl100_native_projection/`: readiness checks,
bridge request generation, QCL-041 relay/session setup, freshness parsing, and
runtime summary writing stay separate so new recycle/datagram/RTP recovery work
does not accumulate in the runner root.

This crate is source-only and does not open ADB, WebSocket, UDP, LSL, or
app-private transports. Hostess, WPF, Makepad tools, CLI routes, or future
frontends should execute their own adapters and then emit this report shape for
inspection. Applied command feedback still requires runtime receipt evidence;
raw ADB state or broker ACKs alone are transport/authority evidence only.

## Native Quest Rendering

Rusty Quest now treats `apps/native-renderer-android` as the main public
Quest-native XR stack for low-level Morphospace rendering experiments. The
stack is Rust-first and NativeActivity-based: it uses Android/OpenXR/Vulkan
directly, keeps Makepad out of the runtime path, and reports acceptance through
`RUSTY_QUEST_NATIVE_RENDERER` markers and runtime-profile fixtures.

The currently documented public routes are:

| Route | Background | Hand visual | Camera/HWB path | Primary use |
| --- | --- | --- | --- | --- |
| Direct HWB camera quality | Camera2 `50`/`51` sampled directly in the final projection | Disabled by profile | Forced direct `AHardwareBuffer` sample | Raw camera acquisition/projection baseline before guide/private processing |
| Fullscreen stereo video projection | Full-eye custom projection layer from app-private side-by-side MP4 | Disabled by profile | Android `MediaCodec` decodes into a Rust-owned `AImageReader` `Surface`, then Vulkan imports the decoded `AHardwareBuffer`; Camera2 and display-composite disabled | Stereo video background route for later camera/composite overlays without high-rate JSON or CPU pixel copies |
| Video-border blend | Full-eye stereo video background | Disabled by profile | Camera2 `50`/`51` via guide texture, optionally composited with the video texture in the public guide/video shader | Compare public camera/video border blend modes and costs |
| Display-composite feedback witness | Native Meta passthrough via `XR_FB_passthrough` | MediaProjection feedback plane only | Native `AImage`/`AHardwareBuffer` descriptor bridge sampled by the shared Vulkan AHB import module; Camera2 and guide blur disabled | Lab route for screen-composite visual feedback without high-rate JSON or CPU pixel copies |
| Custom stereo projection | Camera2 `50`/`51` via Vulkan HWB guide textures | Recorded/live GPU-skinned hand mesh, optional SDF visual, optional peripheral stretch border | Enabled | Camera projection, blur, stretch/blend border, SDF, and replay evidence |
| Live hand anchor particles | Camera2 `50`/`51` via Vulkan HWB guide textures | Live base hand meshes plus resident GPU anchor particles | Enabled | Inspect live hand topology anchors over the camera projection route |
| Native passthrough hands and grafts | `XR_FB_passthrough` | Live GPU-skinned base hands plus fingertip graft copies | Disabled | World-space hand mesh/graft visuals over Meta passthrough |
| Native passthrough graft only | `XR_FB_passthrough` | Fingertip graft copies only | Disabled | Graft-fit isolation |
| Solid black hands and grafts | Opaque black OpenXR projection layer | Live GPU-skinned base hands plus fingertip graft copies | Disabled | Non-passthrough world-space control view |
| Solid black OpenXR hands anchor particles | Opaque black OpenXR projection layer | Runtime/default OpenXR hands requested; app custom mesh visual disabled; resident-mesh anchor particles visible | Disabled | Compare resident custom mesh anchor placement against runtime hand visuals |

These routes are public AGPL examples. Private downstream effects can attach
later through the public extension-slot boundary, but private visual-layer
names, formulas, and tuning are not part of this package.

## Spatial Camera Panel Lane

`apps/spatial-camera-panel-android` is a separate Meta Spatial SDK experiment
lane for public Quest panel and camera-stack validation. It packages a
Compose-backed Spatial SDK 2D panel under
`io.github.mesmerprism.rustyquest.spatial_camera_panel` so participant setup,
surface selection, block timing, questionnaire capture, raw Camera2/HWB
projection probes, and public blur/projection receipts can be tested with
Spatial SDK panel placement, sizing, and scaling controls. It does not replace
`apps/native-renderer-android`, does not carry high-rate hand mesh or private
particle payloads through Java/Kotlin JSON, and keeps hand visuals explicit:
the Spatial SDK avatar hand visual and the public ECS hand-billboard flock are
both opt-in comparison surfaces.

The app also carries a public `morphospace/` composition workspace. It selects
only the panel shell as its workflow baseline, records nearby optional feature
families as disabled, keeps remote peer media absent, and tracks particle and
hand extraction candidates without changing runtime behavior. Run
`tools/checks/Test-SpatialCameraPanelWorkflowStatic.ps1` before the broader
Spatial static ledger when changing that app's composition or module map.

The paired Native Renderer workspace at
`apps/native-renderer-android/morphospace/` is independent rather than a
projection that can promote Spatial. Both workspaces keep particle and hand
families disabled by default and use separate conformance locks for bounded
source checks. Their shared static workflow gate proves source consistency only;
it does not prove a device run, central acceptance, or stable promotion.

The same lane now has two generic Spatial SDK asset/environment hooks. The
Spatial SDK staged 3D asset path accepts only explicit GLB/GLTF `Mesh` URIs at
runtime, usually staged by `Stage-SpatialCameraPanelAsset.ps1` or by
`Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1
-RequireSpatialAssetModel`; raw FBX files are local conversion inputs and are
not packaged. The app's default feature lock keeps this lane inert; the smoke
wrapper binds the exact `spatial-asset-model` conformance lock plus an explicit
runtime request before the entity consumer emits its effective receipt. The
packaged virtual room path is opt-in through
`debug.rustyquest.spatial.virtual_room.enabled` and loads a generic
`assets/scenes/Composition.glxf` room, such as an exported official Spatial SDK
panel sample, as a VR environment. That room path is explicitly not MRUK and
does not place objects in the user's real passthrough room. When that room is
enabled, prior room diagnostics placed the video plus custom camera projection
surface either on a fixed virtual wall or in a full-field viewer-locked pose.
The accepted default disables the room and skybox, starts the projection
surface at 2m, opens the generic layer-control UI panel at 1m, and consumes
right secondary/B as a no-op. The legacy launcher panel is suppressed on this
camera-stack route, and the right primary button opens only the generic
multi-layer control panel. Its layer buttons keep submitting the active layer
override. The default-off `Meta poster LUT` button keeps stereo video decode
active while clearing the custom projection footprint to alpha zero and applies
an animated, hard-posterized color LUT to the Spatial SDK-owned system
passthrough. The video carrier keeps its wide refactored dimensions; only the
custom camera target rectangle applies the square-baseline aspect compensation.
Passthrough and its LUT are activated before the native cutout is submitted so
the first selection can perform the required one-shot carrier rebind
automatically. The adjacent default-off `Raw custom projection` button draws
the direct Camera2/AHardwareBuffer stereo sample over the still-active video
without the private effect shader or its inner edge fade.
Right-stick Y still controls projection target scale, Left-stick Y
controls workflow/layer-control panel distance, and Right-stick X is
intentionally ignored.

The Spatial camera route also includes a public, revisioned latency-diagnostic
surface. `Set-SpatialCameraPanelCameraLatencyDiagnostic.ps1` hotloads bounded
pose, camera-wait, stereo-policy, raw-layer rotation-warp, summary, and logging
A/B presets. Swapchain present mode, image count, and exact Camera2 capture-FPS
requests use the same transaction but remain restart-required. Native summaries
keep frame payloads out of Kotlin, report relative source/callback cadence and
display holds, and label the selected callback-age or direct plausible sensor-
timestamp viewer-pose association. Direct use of an `UNKNOWN` Camera2 timebase
is an empirical headset A/B guarded by a bounded callback delta, not a portable
timestamp guarantee.
The Camera2-calibrated sensor-timestamp warp is now the prediction-off rollback
baseline. MOD-007 adds bracketed capture-pose interpolation, independent
per-eye calibration, bounded Scene/OpenXR estimated-presentation poses, and
reprojection on both the raw control and private guide ingress. Native
every-available camera adoption remains the candidate default; display-aligned
45 Hz is a controlled comparison. The unattended Quest pass retained about
50.4 fresh stereo imports/s at 90 FPS with every-available adoption, while the
45 Hz control increased mean holds from 1.785 to 2.005 display frames; an
actual fixed-45 Camera2 request was unsupported. The implementation and
physical-motion gate included a zero-versus-10-percent real-source-margin A/B
prompted by the observation that the custom projection border lagged relative
to the otherwise head-stable video carrier. The user confirmed that the
10-percent zoom-to-fill control removed the echo/lingering artifact, establishing
retained source coverage as causal, but rejected its constant 1.25x
magnification as the final presentation. The current candidate couples that
central 80-percent source crop to an 80-percent target footprint around each
existing eye center, preserving the original angular scale and exposing the
underlying video honestly. The user accepted that reduced-footprint mode as the
best baseline so far and confirmed that the general echo is gone. Rare
individual-frame inconsistencies remain a separate timing-outlier and 50-to-90
Hz cadence investigation. Exhausted source coverage remains transparent; no
replacement edge visual is used. These decisions
are recorded in the
[Spatial Camera Presentation-Time Remediation Plan](docs/SPATIAL_CAMERA_PRESENTATION_TIME_PLAN.md).
The earlier test sequence and calibration findings remain in the
[Spatial Camera Motion Iteration Report](docs/SPATIAL_CAMERA_MOTION_ITERATION_REPORT.md).

The lane records low-rate session, Polar placeholder, ECG placeholder, block,
and questionnaire JSONL files in app-private storage. Questionnaire rows remain
joinable by participant, session, block, condition/profile, and surface target.
Driver-profile fields are generic bounded scalars only; private downstream
effect formulas, coupling kernels, and tuned study parameters are not part of
this public package.
The implementation and validation plan is tracked in
`docs/SPATIAL_SDK_PORT_IMPLEMENTATION_PLAN.md`.

The native renderer also exposes a generic private particle payload slot for
downstream GPU-resident particle proofs. The public side owns only generic
build-time env-var discovery, a no-payload placeholder, static position/normal
payload staging, four-vec4 billboard output rows, sampled R8 texture-array
particle masks, resident GPU index-remap sorting, parameterized
transparency/coverage controls, generic tracer budget/draw capacity, and
public `private-particle-slot` markers. The public slot reports main count,
tracer budget, and merged draw count while downstream shaders own the semantics
of any appended tracer rows. Downstream repos supply their own shader, payload
data, kind string, marker prefix, and opaque marker fields through
`RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_*`; effect-specific constants and
profile bodies remain outside Rusty Quest.
The slot also exposes a small runtime-polled scalar Android-property family
under `debug.rustyquest.native_renderer.private_particles.*` for world-anchor
scale, visual scale, bounded generic driver scalars, tracer draw
slots/lifetime/cadence, and transparency opacity/alpha/depth/RGB coupling.
Runtime markers must report the accepted effective values; raw
`getprop` readback is only transport evidence. Shader payloads, texture
dimensions, buffer capacities, render mode, and fixed-function graphics
pipeline blend factors remain rebuild/relaunch scope.
The private-particle slot also exposes a generic within-app right-controller
breath-state adapter that can write a normalized value into a selected
driver-bank slot. Rusty Quest owns only the controller classifier,
driver-slot transport, and effective markers; downstream private payloads own
the meaning of the selected slot.

New native APK variants must start from the source-only native app-build
workflow instead of hand-editing runtime profiles, Android manifest
permissions, or build wrapper environment variables. App-build specs under
`fixtures/native-app-builds/` resolve against feature descriptors under
`fixtures/native-app-features/` through
`tools/Resolve-NativeAppBuild.ps1 -DryRun`, producing a feature lock, generated
runtime profile, generated manifest surface, hotload policy, permission
pregrant plan, build env, build manifest, and audit report under ignored
`local-artifacts/native-app-builds/<app-id>/<resolution-fingerprint>/`. Locked
builds require a clean exact source commit/tree and write a run capsule beside
the APK in a content-addressed output. The committed
public-safe canary proves that camera, video, display-composite, stimulus,
hand-anchor, depth, SDF, Makepad, native passthrough, and private-layer
features cannot enter a solid-black private-particle app unless explicitly
selected.

`crates/rusty-quest-native-renderer` defines the first clean contract for a
pure Quest-native OpenXR/Vulkan camera renderer. It models the public AGPL
HWB import, offscreen guide blur, SDF input hook, private extension ABI slot,
and detailed timing scorecard required before building a native app scaffold.

Reusable opt-in modules share the closed engine in
`crates/rusty-quest-feature-activation`; the ownership and validation contract
is in [Closed Feature Activation](docs/FEATURE_ACTIVATION.md). The engine binds
the exact application-accepted lock, while hand and particle facades retain
different nominal decisions and applications retain their own project,
feature, module, profile, digest, and effects. This prevents a tool extracted
while building one app from carrying that app's route or policy into another.

`crates/rusty-quest-particle-adapter` is the shared, platform-facing particle
handoff used by the Spatial Camera Panel and native renderer. It validates the
accepted Matter render payload, Lattice situated anchor, and Optics visual
frame together, preserves particle identity/count/bounds, applies only the
anchor pose, and emits renderer-neutral rows plus a bounded receipt. It owns no
simulation, appearance policy, backend handles, private driver fields, or
high-rate JSON. Both consumers are inert until their explicit app route selects
the adapter; `fixtures/particle-adapter/two-consumer-conformance.json` records
the closed composition and rollback profile.

`crates/rusty-quest-hand-adapter` consumes the accepted Lattice/Matter/Optics
hand contracts, maps both hands into a neutral Matter rig, and emits GPU-ready
rows with Matter CPU-oracle positions. Native OpenXR and Spatial Camera Panel
remain separate thin consumers with explicit activation and fail-closed
provider/basis/hand/rig substitution.

See `docs/NATIVE_QUEST_RENDERING.md`.
The raw native camera quality hardening backlog is tracked as sliced public
work in `docs/NATIVE_CAMERA_QUALITY_ITERATION_PLAN.md`.

`apps/native-renderer-android` is the first Quest-native Android package
scaffold for that contract. It stages the public renderer plan as an APK asset
and uses a Rust NativeActivity library to open NDK `ACameraManager` camera ids
`50` and `51` into GPU-sampled `AImageReader` hardware buffers and emit
`RUSTY_QUEST_NATIVE_RENDERER` timing markers. The package also includes a small
same-APK 2D control panel Activity packaged as `classes.dex`; that panel is a
low-rate requester only. It stages `stimulus_volume_candidate.json` in
app-private storage, while the Rust NativeActivity reads, validates, and applies
the candidate as the effective startup authority for the native stimulus-volume
route. The panel does not add Spatial SDK, WebView, Compose, or Makepad to the
immersive OpenXR/Vulkan renderer. It is not a Makepad route and it is not a
legacy compatibility route. The current scaffold has headset evidence for a real
submitted OpenXR diagnostic projection layer with the public recorded-hand
replay overlay visible. The current native
camera proof imports retained Camera2 HWB frames
into Vulkan external images and renders them only inside metadata-owned per-eye
target rectangles, with source raster Y flip controlled by metadata rather than
a hard-coded shader flip. The direct camera-quality profiles can force
`debug.rustyquest.native_renderer.camera.output=direct-hwb` so guide/private
projection outputs are bypassed while inspecting raw acquisition quality; the
default baseline now uses Android-suggested YCbCr plus the OpenXR swapchain
preference `unorm`, the BT.601/UNORM variant only changes the effective Vulkan
sampler conversion for color-lift A/B diagnostics, the low-noise 30 and
low-latency 60 profiles add support-gated public Camera2 request controls, the
1280x960 profile A/B tests reader resolution, and the hold-sync profile retains
sampled `AImage` objects until the submitted Vulkan frame-slot fence retires.
Local builds can embed the real recorded
Meta/OpenXR hand capture and stage its bind mesh into a native Vulkan storage
buffer while reporting `cpuSdfPerFrame=false`. The current render loop also
draws the real recorded hand mesh from the resident GPU-skinned position
buffer, preserving the browser-discovered component split: hand-inside,
hand-back, and wrist cap. The resident hand mesh buffer now stores OpenXR
reference-space meter positions; live hand visuals project those positions
through each eye's OpenXR pose/FOV instead of fixed target-local UVs, with the
OpenXR `+Y`-up eye-space value converted for this positive-height Vulkan
viewport. The live two-hand route now follows the browser "hand job" preview's
source split: the primary visual uses the left recorded topology, while the
secondary visual allocates, skins, and draws from the distinct right recorded
topology when a full local capture is embedded. Scorecards expose both visual
hand labels and source handedness so a right-hand draw cannot silently reuse
the left bind mesh. The normal hand visual now uses one continuous surface
material instead of visible component-color chunks; component ids remain
metadata for validation and future effects. An opt-in graft-copy property can
reuse the already-skinned left/right meshes as fingertip instances on the
opposite hand when both live hands are visible. The separate
`quest-native-renderer-native-passthrough-graft-only.profile.json` route uses
native passthrough as the background, skips Camera2/custom stereo projection,
disables the SDF visual, and scales those graft copies by `0.85` for a tighter
finger fit. The separate
`quest-native-renderer-native-passthrough-hands-and-grafts.profile.json` route
keeps the same native passthrough background and graft scale but also draws the
real live hand meshes from the already GPU-skinned resident buffers. The
`quest-native-renderer-solid-black-hands-and-grafts.profile.json` route is the
same live base-mesh plus graft visual test without passthrough or Camera2
projection: it submits an opaque black projection layer and draws only the
hand visuals. The
`quest-native-renderer-solid-black-openxr-hands-anchor-particles.profile.json`
route is the resident-topology comparison view against the runtime hand visual:
it skips Camera2/custom stereo projection, clears to black, keeps the app's
custom hand mesh visual and graft copies disabled, requests the OpenXR/default
hand visual as the reference, and draws only GPU anchor particles generated from
the resident skinned mesh buffers. The
`quest-native-renderer-display-composite-feedback.profile.json` route configures
native Meta passthrough plus an Android MediaProjection display-composite
feedback plane. The profile explicitly selects
`native-passthrough-media-only`, disables Camera2 output, guide blur, hand
visuals, SDF, environment-depth particles, stimulus volume, and private visual
layers, then uses the Rust/NDK `AImageReader`/`AHardwareBuffer` handoff with
foreground-service media-projection permissions. The selected mode is
`gpu-recursive-feedback-diagnostic`: MediaProjection remains the live input
stream, while an app-owned device-local feedback texture stages the current
captured frame without diagnostic borders or previous-feedback blending before
projection into an aggressively shrunken centered field-of-view footprint with
fully opaque premultiplied alpha and a luma-damped feedback pass. The visible
recursive effect comes from later
MediaProjection frames recapturing that app-rendered plane. The stream remains
display-composite evidence, not raw camera, passthrough texture,
environment-depth, or geometry evidence. Lab validation can pregrant
`PROJECT_MEDIA` with `tools/Grant-NativeRendererPermissions.ps1
-GrantMediaProjectionAppOp`, then launch `ControlPanelActivity` with
`io.github.mesmerprism.rustyquest.native_renderer.action.REQUEST_DISPLAY_COMPOSITE_CAPTURE`
so Android still generates fresh `createScreenCaptureIntent` result data on
each launch. `ahardware_buffer_vulkan.rs` is now the reusable Vulkan import
module used by Camera2 and display-composite sampling, while the recursive
feedback texture stays inside the display-composite renderer rather than the
large OpenXR frame loop.
`tools/Invoke-NativeRendererDisplayCompositeSmoke.ps1` owns the serial-scoped
device smoke for this MediaProjection route.
The `quest-native-renderer-fullscreen-stereo-video.profile.json` route uses the
same custom stereo projection shell without Camera2 acquisition or
MediaProjection capture. Java controls `MediaExtractor`/`MediaCodec` only at
the stream-control layer and decodes an app-accessible video file into a
Rust-created `AImageReader`
`Surface`; Rust acquires the decoded `AImage`/`AHardwareBuffer`, publishes
source metadata for side-by-side left/right UV halves, and Vulkan samples it as
a full-eye background before later overlay paths. The route is a video input
stream, not raw camera, passthrough texture, display-composite feedback,
environment-depth, or geometry evidence. It keeps
`high_rate_json_payload=false`, avoids Java `HardwareBuffer` frame bridges, and
does not use CPU pixel copies. Stage a user-provided MP4 with
`tools/Stage-NativeRendererVideo.ps1`, passing `-SourcePath <mp4>` and
`-Serial <quest-serial>`, before launching the fullscreen stereo video profile.
The staging helper defaults to the package-scoped external
`/sdcard/Android/data/.../files/v.mp4` path so release-style APKs do not depend
on `run-as`; use the receipt's `video_projection_path` as the runtime property
override.
The `quest-native-renderer-hwb-video-border-blend.profile.json` route uses that
same video stream as the full-eye background, keeps Camera2/HWB guide output
public, and selects a generic `video_border_blend.mode` so the transition band
can be tested as fixed-function alpha, shader crossfade, luma/chroma variants,
artistic blend modes, gradient-aware blend, two-band blend, or
temporal-stabilized mask smoothing. `tools/Invoke-NativeRendererVideoBorderBlendSweep.ps1`
generates per-mode visual and timing artifacts without baking private effect
semantics into Rusty Quest.
A compact-joint GPU
path now parses the real rig blend indices/weights, bind-joint sources, compact
runtime joint frames, and tip lengths; keeps source mesh and bind metadata
buffers resident; uploads only runtime poses plus tip-length rows per frame;
dispatches GPU skinning into a resident skinned-position buffer; and optionally
builds the target SDF field by projecting that GPU-owned mesh into the metadata
target for the current visual SDF slice. The same compact input
shape can now be fed by live OpenXR hand tracking when `XR_EXT_hand_tracking`
is available: live frames upload only the 21 runtime joint poses plus packed tip
lengths and then reuse the resident topology/bind/SDF graph. The SDF visual
path now also separates kernel dispatch from cached field reuse through
cadence/cache scorecard markers. The native camera path now includes a public
guide graph: imported Camera2 HWB frames are rendered into per-eye guide
textures, optionally blurred with split horizontal/vertical 5-tap passes, and
final projection samples the guide texture when the graph is ready instead of
sampling external HWB again. The default guide graph remains a 384x384
diagnostic blur path. The Breathing Room profile uses the same guide projection
without blur at camera-sized 1280x1280 resolution, and pins the documented
forced BT.601 narrow YCbCr plus UNORM swapchain settings for raw-camera color
parity. In Manifold PMB mode it also publishes the native OpenXR right-grip
controller pose to `stream.motion.object_pose` as
`provider.native_renderer.controller_pose`, matching the Makepad
source-agnostic controller-pose provider contract; right controller B toggles
the scale driver between PMB and joystick, while A resets the target scale.
When PMB is the active scale driver and the right grip pose is tracked, the
native OpenXR action layer also emits a regular right-controller haptic pulse
through the right-hand subaction path as the Breathing Room breathing-mode cue.
Stimulus-volume render modes are volume-only routes: they sanitize
projection-target settings to disabled defaults, do not bind Breathing Room
reset/scale/haptic actions, and reserve right-controller A for stimulus
randomization. The startup dynamics default to the saved headset randomization
`headset-randomize-count-28-2026-06-20`: a spiral family at 3.084 Hz with
spatial oscillators 6.041, 35.362, and 37.531 Hz, source shift
`-0.052,0.099`, and the captured twist/pinch/scramble/jumble/stretch values.
The randomization vocabulary remains Trevor Hewitt-inspired but shader-native:
button randomization selects browser-portable pattern families
(`trevor-mix`, stripes, ripples, rays, checker, spiral, and noise-field) plus
mirror/twist/pinch/scramble/jumble/stretch parameters that a later browser
designer can serialize into the Quest profile surface. The solid-black stimulus
fixture now uses the central-FOV limit tier (`1024x1024x2`, 18 raymarch samples,
0.72 central-FOV fraction) so
the native GPU path spends its resolution budget on the main field of view
instead of the periphery; the companion balanced solid-black profile keeps the
same route and safety settings at `768x768x2` with 12 raymarch samples for
72 Hz quality A/B runs. The performance solid-black profile keeps the same
authority surface at `512x512x2` with 12 raymarch samples; the 2026-06-19
Quest 3S resolution sweep made that the first native tier with enough headroom
for 120 Hz/high-clock stimulus exploration.
The same native guide projection pass can optionally expand to a
full-eye peripheral stretch border using the Makepad HWB stack's target-local
raster model as a reference: the profile
`quest-native-renderer-hwb-peripheral-stretch.profile.json` sets
`debug.rustyquest.native_renderer.processing.layer=peripheral-stretch`,
keeps the metadata-owned camera target as the core region, stretches exterior
pixels from the target edge, and blends through the inner target band while
reporting `guideProjectionCoverage=full-eye-peripheral-stretch`.
The companion profile
`quest-native-renderer-hwb-video-border-blend.profile.json` keeps the same
custom Camera2/HWB guide projection but sets
`debug.rustyquest.native_renderer.processing.layer=video-border-blend` and
enables the stereo video input path. Video renders first as a full-eye
background, then the camera guide projection draws over the metadata-owned
target and fades through the same inner-band blend controls to the video
outside the target instead of stretching camera pixels into the border.
Runtime markers report `guideProjectionCoverage=full-eye-video-border-blend`
and `cameraProjectionPath=metadata-target-guide-texture-video-border-blend-final`.
Live headset visual acceptance,
Matter/Lattice SDF parity, color conformance, projection parity,
and higher-order SDF acceleration remain separate validation gates. The
2026-06-17 no-real-hands recorded replay smoke visually verified the
target-local mesh/SDF overlay in headset screenshots and passed the stage
performance budget at 90.1 FPS with zero stale frames. During the separate
2026-06-17 live-hand check the user had real hands in view, but did not see a
mesh or SDF representation in the headset, so live mesh/SDF visual acceptance
still needs an explicit hand-mesh diagnostic offset/tint retest. The replay
profile and the future live-hand diagnostic profile are separate
runtime-profile fixtures so recorded replay acceptance cannot be confused with
live visual acceptance. Native renderer property names are centralized in
`native_renderer_properties`; generic value parsing lives in
`native_renderer_property_values`; camera/output option parsing lives in
`native_renderer_camera_options`; environment-depth parsing lives in
`native_renderer_environment_depth_options`; hand-anchor particle parsing lives
in `native_renderer_hand_anchor_particle_options`; projection-border and
peripheral-stretch parsing lives in
`native_renderer_projection_border_stretch_options`; native Meta passthrough
compositor style parsing lives in `native_renderer_passthrough_style_options`
and the raw XR_FB_passthrough style call lives in `openxr_passthrough_style`.
The same style owner now includes an opt-in oscillator-backed audio-reactive
controller that reuses the approved mono-to-RGBA gradient and shifts color
phase/edge tint through bounded `xrPassthroughLayerSetStyleFB` updates; real
microphone capture remains a later source adapter, not a parallel parameter
authority;
stimulus-volume parsing lives in `native_renderer_stimulus_volume_options`;
render-route, compact hand source, hand-visual diagnostic, and private-layer
parsing lives in
`native_renderer_visual_options`; and
`native_renderer_options` remains the aggregate facade consumed by the Vulkan
frame loop. Aggregate parser regression tests live in
`native_renderer_options_tests`. The frame loop keeps projection submission
authority in `xr_vulkan.rs`, while marker scorecard emission lives in the
child module `xr_vulkan/scorecard.rs` and replay/live visual evidence rectangle
math lives in `xr_vulkan/replay_visual_stats.rs` so timing/acceptance reporting
can evolve without making the integration file another settings or evidence
schema owner.
Environment-depth particle Vulkan resource and command recording stays in
`gpu_environment_depth_particles`, while readback statistics, marker-policy
strings, surface-support depth flags, normal-source/counter markers, and grid
sizing live in `gpu_environment_depth_particle_stats`. Source-only
scene-map hash/probe/free-space policy lives in `environment_depth_scene_map`;
normal/coherence regression math lives in `environment_depth_surface_support`;
it reconstructs bounded depth neighborhoods, retained scene-cell
neighborhoods, and pose-shifted scene-cell samples into reference-space
meters, builds compact surface descriptor fixtures for host tests, and mirrors
hash insert/merge/stale-replace/probe-exhaustion/free-space-retire behavior
plus retained-map source-layer agreement and offset separation without becoming
runtime authority.
The typed low-rate property manifest at
`fixtures/native-renderer/native-renderer-property-manifest.json` records the
current Android property surface, value kinds, ranges, parser owners,
startup-effective lifecycle, profile-owned explicit-set clear behavior, and
runtime-owner default behavior; `check_native_renderer_property_parity.py`
rejects runtime/profile drift and requires every manifest entry to name the
generic low-rate validators. The manifest does not duplicate default values:
defaults remain owned by the runtime parser module named by each entry.
`tools/checks/Test-NativeRendererPropertyManifestStatic.ps1`
owns the static manifest/parity-tool wiring assertions used by the Android
scaffold harness. Android manifest, Rust NativeActivity, input pump, Cargo
manifest, build script, and app README assertions live in
`tools/checks/Test-NativeRendererAndroidScaffoldStatic.ps1`. Both
`Apply-RuntimeProfile.ps1` and the
`rusty-quest-profile` Rust validator load the same manifest before ADB writes
or dry-run write-plan generation, so generic native renderer tokens and ranges
are not a second hand-maintained validator layer. The app-source public/private
boundary scan for the native renderer source/build path lives in
`tools/checks/Test-NativeRendererPublicBoundaryStatic.ps1`. Environment-depth
source, profile, fixture, and smoke-wrapper static assertions live in
`tools/checks/Test-NativeRendererEnvironmentDepthStatic.ps1`. General
runtime-evidence checker, replay-smoke wrapper, and permission-pregrant static
assertions live in
`tools/checks/Test-NativeRendererRuntimeEvidenceStatic.ps1`. Runtime-profile
apply-tool serial scoping and Rust validator manifest-hook assertions live in
`tools/checks/Test-NativeRendererRuntimeProfileStatic.ps1`. Stimulus-volume
renderer, shader, OpenXR action, timing, and route-marker assertions live in
`tools/checks/Test-NativeRendererStimulusVolumeStatic.ps1`. Breathing Room
projection-target route assertions, including Manifold breath/pose transport
and right-hand OpenXR input/haptic markers, live in
`tools/checks/Test-NativeRendererProjectionTargetStatic.ps1`. Recorded-hand
replay, live compact hand input, GPU-skinned hand mesh visual, graft-copy, and
GPU mesh replay boundary assertions live in
`tools/checks/Test-NativeRendererHandVisualStatic.ps1`. Target-space GPU SDF
field, tile-bin, overlay shader, compact-joint upload, cadence/cache, and SDF
marker assertions live in `tools/checks/Test-NativeRendererGpuSdfStatic.ps1`.
Camera projection metadata, guide blur/projection, direct-HWB camera quality
diagnostic, peripheral-stretch, source-route profile snippet, and native camera
scaffold assertions live in
`tools/checks/Test-NativeRendererCameraGuideStatic.ps1`. OpenXR/Vulkan
prerequisite, timing marker, private-slot, render-mode, scorecard, and native
timing counter assertions live in
`tools/checks/Test-NativeRendererOpenXrVulkanStatic.ps1`; the main Android
harness keeps the executable runtime-evidence logcat checks. The full
native-renderer profile and damaged-profile inventories are owned by
`tools/Test-NativeRendererProfileMatrix.ps1`, which dry-runs every valid
profile and rejects every damaged fixture through the manifest-backed runtime
profile tool.

## Remote Camera Streaming

`crates/rusty-quest-remote-camera` validates the first Morphospace remote
camera session plans for Quest-to-Quest two-way streaming and Quest-to-Android
phone duplex streaming. It is a contract crate only: high-rate camera frames
stay on a binary media plane, while session plans, safety requirements, queue
policy, local runtime endpoint bindings, peer transport routes, and
observability gates remain low-rate data.

The same contract models direct Wi-Fi as two independent decisions:
`direct_p2p_tcp` is the route kind, while
`rusty_direct_p2p_socket_authority` names the scoped Rusty-owned socket
authority. A direct-P2P plan must also provide the source `local_bind_host`;
the Android adapter then proves the actual bound address, P2P interface, peer
subnet, receiver-observed bytes, and cleanup instead of inferring authority
from a destination address alone.
The route-level authority and P2P address checks are delegated to the shared
`rusty-quest-device-link` contract; camera-specific lane/source/sink and runtime
profile checks remain in `rusty-quest-remote-camera`.

QCL100 is live-promoted for native OpenXR same-group full-stereo duplex by run
`qcl100-native-stereo-promotion-candidate-20260710T1236Z`. The accepted topology
uses two end-to-end direction paths, each with two validated sender and receiver
stereo lanes bound through `p2p0`; no app-visible Android Wi-Fi Direct `Network`
was required or claimed. QCL099/Makepad remains an explicit legacy compatibility
lane and is not part of this promotion.

Packed side-by-side stereo is separately promoted by
`qcl100-packed-sbs-duplex45-20260710T155638Z`. It preserves the two duplex
direction paths but reduces each direction to one Camera2 `50`/`51`
source-timestamp pairer, one GPU SBS compositor, one H.264/RMANVID v4 stream,
one Rusty-owned `p2p0` socket, one hardware decoder, and one packed
`AHardwareBuffer` sampled through the existing left/right UV halves. Packed SBS
is the recommended explicit QCL100 native OpenXR profile. The runtime default
remains `separate-eye-streams` for compatibility, so adopting packed SBS still
requires `media_layout=side-by-side-left-right`; the earlier two-lane promotion
remains the rollback and differential-diagnosis authority.

See `docs/REMOTE_CAMERA_STREAMING.md`.

## Android Broker Package

Broker packaging starts from an immutable Manifold product lock, not from a
hand-maintained permission union. `crates/rusty-quest-broker-product` validates
the exact spec/lock pair, projects its permission closure, renders the actual
Android manifest, emits the exact command registry, and stamps SHA-256 plus the
Manifold lock id/fingerprint into a package-input receipt. The standalone base
contains only network plus notification/background data-sync lifecycle
permissions. Generic `media_session` adds no camera permission; camera,
direct-P2P, and BLE remain independent opt-ins. The build packages the accepted
lock, registry, and projection as APK assets and emits
`rusty.quest.manifold_broker_android.build_manifest.v2`. There is no ambient
  app-local `AndroidManifest.xml` fallback. See
  [Broker Packaging](docs/BROKER_PACKAGING.md).

The same build binds exact packaged product-spec, accepted-lock, and client-lock
bytes plus their hashes into the runtime config. Grants are generated only from
the product/client intersection: the base product remains media/sink/peer-free,
and camera-free media adds media observe plus the app's selected sink without
adding peer authority. JNI checks the embedded canonical config digest before
creating the provider.

The product-only authority path is implemented in
`crates/rusty-quest-broker-authority`. It projects a trusted app-local
standalone-process or embedded-in-process provider into one stateful
`ManifoldBrokerRuntime`. Signature-scoped Binder admission creates an
opaque-token, client/capability/revision-bound one-use permit; the real
  WebSocket or embedded server
entrypoint must consume that permit before the shared Runtime Host review/apply
path can authorize any platform effect. Both JNI surfaces preserve same-process
  rebind state and create a fresh entropy-derived epoch after provider restart.
  Each use is bound to its own creation revision, so unrelated client admission
  advances do not invalidate it; exact-token revocation/expiry removes only
  derived pending uses.
Their Java classes project Rust-authored receipts and contain no local
`accepted=true`, authority label, command table, lease, revision, or replay
policy. See [Broker Runtime Authority](docs/BROKER_RUNTIME_AUTHORITY.md).

Secure cross-app product admission is implemented as a signature-protected
Binder service with a packaged arm64 Rust JNI library. Android projects the
Binder sending UID, resolved package, and signing-certificate SHA-256;
`rusty-quest-broker-admission` passes that evidence to
`rusty-manifold-admission`, which alone owns grants, random short-lived tokens,
one-time capability uses, revocation, expiry, revisions, and audit. The
authorized device-test client is signed with the broker key; the unauthorized
variant uses a different key and must fail at the Android permission boundary.
See `docs/BROKER_ADMISSION.md`.

`crates/rusty-quest-broker-client` is the shared, policy-free consumer SDK for
real product apps. Native Renderer and Spatial Camera Panel have different
signature-scoped grants, client identities, feature locks, marker namespaces,
and sink capabilities while selecting the same accepted Manifold peer-session
and generic media-session contracts. Their manifests contain no shared app
defaults or broker-client properties. The two-app device gate is
`tools/Invoke-MultiAppBrokerClientTwoQuest.ps1`; it also folds the promoted
QCL100 evidence shape into a QCL-neutral generic media-session receipt.
The packaged generic runtime binds exact Manifold and Quest descriptor hashes,
seven independently selected owners, and a receiver-first prepare/apply
completion protocol; see `docs/MEDIA_SESSION_RUNTIME.md`.
After Binder `authorize_use`, the SDK's `build_broker_mutation_request` binds
the exact app client id, provider epoch, current admission/host revisions,
  one-time use id, command capability, optional lease, and bounded time window.
  It also wraps low-rate values in `rusty.quest.broker.effect_params.v1`,
  canonicalizes object keys, enforces the 4096-byte limit, and binds the digest
  through both Runtime Host receipts. Java reads only the accepted response's
  exact `effect_params`. Android request ids use a fresh 128-bit launch
  namespace; only the deliberate replay probe reuses one id.

The embedded Native Renderer build packages the camera product spec/lock,
native-renderer client lock, derived grant, and canonical runtime-config digest
in generated code and inspectable APK assets. Settings cannot replace or widen
that authority. The embedded server derives its package and sole APK signer
from Android, asks Rust to issue and authorize each exact command use, then
overwrites all caller-supplied authority fields before mutation.

`apps/manifold-broker-android` is the Quest-owned Android package scaffold for
the Morphospace Manifold broker identity used by Hostess:

```text
io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity
```

It exposes `/manifold/v1/events` on local TCP port `8765`, accepts
`rusty.manifold.command.envelope.v1` WebSocket command envelopes, and returns
acknowledgements. It does not synthesize live provider stream events, so Polar,
controller, and Makepad evidence still requires real providers.
For the Hostess Makepad safe probe, it accepts
`hostess.makepad.bridge_probe.set_marker`, dispatches
`rusty.hostess.bridge_command.request.v1` on
`stream.hostess.makepad.bridge_command`, and reports the expected
`stream.hostess.makepad.bridge_command.receipt` runtime receipt stream in the
ACK. Runtime adoption still belongs to the Makepad app receipt, not to the
broker ACK alone.

For remote-camera commands, the broker package now has the first Quest-owned
runtime adapter slice. It recognizes `command.remote_camera.start_receiver`,
`command.remote_camera.start_sender`, `command.remote_camera.get_status`, and
`command.remote_camera.stop`; receiver start arms local TCP receiver sockets
from the validated `debug.rustyquest.remote_camera.receiver_ports` and
`debug.rustyquest.remote_camera.transport_receive_ports` properties, then
reports `remote_camera_runtime` status in the command ack. Sender start can now
use validated `debug.rustyquest.remote_camera.sender_source_kind`,
`debug.rustyquest.remote_camera.sender_media_profiles`, and
`debug.rustyquest.remote_camera.transport_routes` properties to arm a local
H.264 sender source and bridge it to a peer transport ingress. The broker
supports an external H.264 socket source, a diagnostic synthetic MediaCodec
surface source, and a Camera2-to-MediaCodec source gated by camera permission
evidence. Android-phone adapter execution and paired headset/phone live-stream
evidence remain later validation work.

The final proportional two-Quest release gate is routed through
[Corrected Release Two-Quest Matrix](docs/CORRECTED_RELEASE_TWO_QUEST_MATRIX.md).
`tools/Invoke-CorrectedReleaseTwoQuestMatrix.ps1` composes the existing MOD-006,
real Native/Spatial broker-client, peer-authority, Camera2, display-composite,
cleanup, and bounded-fatal suites into the exact 20-row corrected-release
matrix. It requires two explicit serials, a clean exact revision, and the new
live `Invoke-ManifoldPeerAuthorityTwoQuest.ps1` provider; legacy or fixture
evidence cannot fill that provider contract. The source-only damaged/static
gate is `tools/checks/Test-CorrectedReleaseTwoQuestMatrixStatic.ps1`.

## Validation

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeAppBuildProfile.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -Command "cargo test -p rusty-quest-device-link"
pwsh -NoProfile -ExecutionPolicy Bypass -Command "cargo run --quiet -p rusty-quest-device-link --bin validate_direct_p2p_socket_route -- fixtures\device-link\direct-p2p-socket-route.pass.json"
pwsh -NoProfile -ExecutionPolicy Bypass -Command "cargo test -p rusty-quest-native-renderer"
pwsh -NoProfile -ExecutionPolicy Bypass -Command "cargo test -p rusty-quest-remote-camera"
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-PeerRendezvousAndroid.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeRendererAndroid.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Resolve-NativeAppBuild.ps1 -AppSpec .\fixtures\native-app-builds\native-openxr-hand-lab.app.json -DryRun
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-NativeRendererAndroid.ps1 -AppBuildLock .\local-artifacts\native-app-builds\native_openxr_hand_lab\<resolution-fingerprint>\feature-lock.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-ApkRunCapsule.ps1 -Path <content-addressed-output>\run-capsule.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererReplaySmoke.ps1 -RunCapsule <content-addressed-output>\run-capsule.json -Serial <quest-serial> -RunSeconds 12
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererDisplayCompositeSmoke.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -Serial <quest-serial> -RunSeconds 12
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererEnvironmentDepthMotionProof.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -Serial <quest-serial> -RunSeconds 12
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererEnvironmentDepthAcceptanceSuite.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -Serial <quest-serial>
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1 `
  -ProductSpecPath ..\rusty-manifold\fixtures\broker-product\media-session-standalone.json `
  -ProductLockPath ..\rusty-manifold\fixtures\broker-product\media-session-standalone.lock.json `
  -MediaSessionBindingPath .\fixtures\media-runtime-products\display-composite.binding.json
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-QuestBrokerProductStatic.ps1 -RepoRoot .
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-QuestBrokerAuthorityStatic.ps1 -RepoRoot .
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-QuestBrokerAdmissionStatic.ps1 -RepoRoot .
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-CorrectedReleaseTwoQuestMatrixStatic.ps1 -RepoRoot .
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-BrokerAdmissionDeathRecoveryTwoQuest.ps1 -Serial <quest-serial-a>,<quest-serial-b>
```

The two-Quest broker-death suite first proves client death/rebind against the
live authority, then force-stops the broker and requires an explicit fresh-
epoch rebuild from each app's exact lock and grant. It rechecks replay,
revocation, identity separation, marker isolation, package/system fatals, and
complete uninstall cleanup. Evidence remains outside this public repository.

The default `check_all.ps1` lane excludes legacy Makepad and QCL099 checks and
focuses on native OpenXR/Vulkan plus Meta Spatial SDK surfaces. Pass
`-IncludeLegacyMakepad` only for explicit compatibility or historical replay.

`check_all.ps1` also dry-runs the environment-depth surface-support profiles:
local surfels, global surfaces, and hybrid surfaces. These validate the
properties and low-rate `environmentDepthSurfaceSupport*` markers without
touching a headset. The native renderer now has a bounded GPU
local-depth-neighborhood support gate for requested surface modes; runtime
particle evidence may report
`environmentDepthSurfaceSupportEnforced=true` with
`environmentDepthSurfaceSupportStatus=enforced-local-depth-neighborhood-component-local-hint`
and nonzero supported/rejected-cell counters. It also tracks a bounded
candidate/confirmed lifecycle with `environmentDepthSurfaceLifecycleStatus`
and candidate, confirmed, promoted, candidate-retired, component-mode,
small-component, confirmed-component, and nonzero local-patch max counters.
These are aggregate GPU local-hint counters, not accepted connected-labels.
Connected-component/global surface acceptance and world-space motion proof still
require a headset run.
When the headset is ready, the environment-depth acceptance-suite wrapper runs
the deliberate motion proof, the 0.5 m, 1 m, 2 m, and 4 m known-distance runs,
the known-distance series checker, and the evidence-bundle checker in one
serial-scoped route. It still leaves human headset visual acceptance explicit.

Device-facing smoke wrappers require `-Serial <quest-serial>` or
`RUSTY_QUEST_SERIAL`; normal ADB work must not rely on an implicit default
device. Use `RUSTY_QUEST_ADB_SERVER_PORT` or `-AdbServerPort` only when
intentionally routing through a non-default ADB server port.
