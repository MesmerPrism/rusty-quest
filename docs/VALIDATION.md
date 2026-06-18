# Rusty Quest Validation

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
```

The runtime profile validation path is dry-run only. It validates runtime profile
fixtures and generates a deterministic property write plan without touching a
headset or ADB server. The native renderer profiles are the public validation
matrix for the main native Quest XR stack: they select custom Camera2/HWB
projection, native Meta passthrough, or a solid black projection background
without changing APK identity or hiding route state in ad hoc launch scripts.
The current raw-camera quality hardening backlog is tracked in
`docs/NATIVE_CAMERA_QUALITY_ITERATION_PLAN.md`; this document lists the
validation commands and profile fixtures that prove each landed slice.
The native renderer has separate profile fixtures:
`quest-native-renderer-direct-hwb-camera-quality.profile.json` is the raw
Camera2/HWB baseline route; it forces
`debug.rustyquest.native_renderer.camera.output=direct-hwb`, bypasses
guide/private projection output, disables hand/SDF overlays, disables the
direct-camera border overlay, leaves the Vulkan YCbCr sampler on Android's
suggested model/range, and requests a UNORM OpenXR swapchain preference. It
also declares `camera.quality_profile=direct-baseline` and
`camera.sync_mode=early-delete-ahb-retained`; runtime markers report this as
the default public baseline. The
`quest-native-renderer-direct-hwb-camera-quality-bt601-unorm.profile.json`
fixture keeps the same raw route but forces the effective sampler conversion to
`YCBCR_601` plus `ITU_NARROW` and requests a UNORM OpenXR swapchain preference
for color-lift and dark-region grain diagnostics. The
`quest-native-renderer-direct-hwb-low-noise-30.profile.json` fixture keeps the
same Android-suggested/UNORM raw route but requests the support-gated public
Camera2 low-noise profile: 30 FPS AE range, high-quality noise reduction with a
fast fallback, and edge enhancement off when those keys/modes are exposed by
the device. `quest-native-renderer-direct-hwb-low-noise-record-30.profile.json`
uses the same public request controls from Camera2 `TEMPLATE_RECORD` so preview
and record templates can be compared without changing APK identity. AE FPS
selection is exact-first and then nearest-supported, with requested, selected,
and applied markers logged. `quest-native-renderer-direct-hwb-low-latency-60.profile.json`
requests 60 FPS AE, fast noise reduction, and edge enhancement off.
`quest-native-renderer-direct-hwb-1280x960.profile.json` requests the 1280x960
reader size, with Camera2 stream-configuration fallback ranked and logged when
needed. Ranking uses tested preferred PRIVATE sizes, target aspect, target-FPS
feasibility, and `ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS` when exposed.
`quest-native-renderer-direct-hwb-hold-sync.profile.json` activates the
conservative producer/consumer diagnostic: the camera callback retains the
sampled `AImage`, the renderer tracks that lease per Vulkan frame slot, and the
lease is retired only after that frame slot's GPU fence completes. The
`quest-native-renderer-direct-hwb-hold-sync-reader6.profile.json` and
`quest-native-renderer-direct-hwb-hold-sync-reader8.profile.json` fixtures raise
`camera.reader_max_images` for queue-headroom A/B checks. The native
app logs `camera-capabilities`, `camera-request-profile`,
`camera-capture-result`, selected reader size, buffer-removed listener,
cache-eviction processing, import/descriptor LRU eviction deferrals, sync lease
tracking, acquire errors, bounded capture-result correlation fields on acquired
HWB frames, left/right result-correlation fields in the timing scorecard, and
YCbCr format-feature markers so headset screenshots can be compared with the
actual Camera2 result metadata. The
lower-latency `AImage_deleteAsync`/sync-fd release path remains a future
validation gate. The
`quest-native-renderer-replay-visual-proof.profile.json` fixture is the
no-real-hands recorded replay acceptance route, while
`quest-native-renderer-live-hand-visual-diagnostic.profile.json` only stages the
future live-hand retest with live input, high-contrast mesh diagnostics, SDF
visuals, and explicit pending visual-acceptance markers. The
`quest-native-renderer-native-passthrough-graft-only.profile.json` route is the
live-hand native passthrough test: it disables custom stereo Camera2 projection
and the SDF visual, then draws only the post-skinning fingertip graft copies at
`0.85` scale over `XR_FB_passthrough` when the runtime exposes it. The
`quest-native-renderer-native-passthrough-hands-and-grafts.profile.json` route
uses the same native passthrough background and graft scale but also enables the
base live hand mesh draw path with
`debug.rustyquest.native_renderer.hand_mesh.real_hands.visible=true`. The
`quest-native-renderer-solid-black-hands-and-grafts.profile.json` route disables
both passthrough and custom Camera2 projection, clears the submitted projection
layer to black, and draws only the live base meshes plus graft copies.
The
`quest-native-renderer-solid-black-openxr-hands-anchor-particles.profile.json`
route also disables passthrough and custom Camera2 projection, clears to black,
keeps the app's custom hand mesh and graft visuals disabled, requests the
runtime/default OpenXR hand visual, and draws only resident-mesh anchor
particles for topology comparison.

Remote camera session plans are also source-only validation:

```powershell
cargo test -p rusty-quest-remote-camera
```

Native Quest renderer plans are source-only validation:

```powershell
cargo test -p rusty-quest-native-renderer
```

The tests validate the public HWB blur/SDF renderer plan, a timing scorecard,
and damaged examples that try to leak private extension implementation paths or
return final projection to multiplied external HWB samples.

The Quest-native Android renderer scaffold has static and APK build validation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeRendererAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-NativeRendererAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererReplaySmoke.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -Serial <quest-serial> -RunSeconds 12
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeRendererRuntimeEvidence.ps1 -LogcatPath <filtered-logcat.txt> -ScreenshotPath <screenshot.png> -RequireScreenshot -RequireNonFlatScreenshot -RequireTargetNonFlatScreenshot -RequireHandMeshVisualScreenshot -RequireSdfVisualScreenshot -RequireCameraProjection -RequireReplayVisualProof -RequireGuideGraph -RequireSdfVisual -RequireGpuTimestampReady -RequirePerformanceBudget -RequirePrivateSlotNoPayload
```

`Invoke-NativeRendererReplaySmoke.ps1` is the no-real-hands device wrapper for
the recorded replay path. It installs the APK unless `-SkipInstall` is passed,
best-effort grants camera/hand permissions, applies
`quest-native-renderer-replay-visual-proof.profile.json` through
`Apply-RuntimeProfile.ps1 -Execute`, launches the NativeActivity, captures
logcat plus a screenshot, and then calls
`Test-NativeRendererRuntimeEvidence.ps1` with screenshot content analysis
enabled. That checker records whole-screenshot dimensions, sampled unique
colors, luminance range, and per-target-rectangle stats derived from the
runtime-emitted `leftTargetScreenUvRect` and `rightTargetScreenUvRect` marker
fields. It also measures hand-mesh and SDF overlay evidence rectangles derived
from `leftHandMeshVisualScreenUvRect`/`rightHandMeshVisualScreenUvRect` and
`leftSdfVisualScreenUvRect`/`rightSdfVisualScreenUvRect`, so camera-target
variation cannot be mistaken for mesh/SDF visual proof. Those overlay
rectangles also record chroma and expected high-chroma overlay color-family
pixel counts, so grayscale camera detail inside the same region is rejected as visual
proof. The wrapper rejects flat screenshots, flat target regions, flat or
colorless hand-mesh evidence regions, and flat or colorless SDF evidence
regions unless `-AllowFlatScreenshot` is passed for a diagnostic run. The
wrapper also writes target, hand-mesh, and SDF crop PNGs under
`screenshot-crops/` beside `runtime-evidence-summary.json` so the runtime
visual evidence can be inspected directly after a headset run.
It also requires the performance-budget gate by default: stale frames must stay
at or below the configured maximum, observed OpenXR FPS must stay above the
configured minimum, and CPU/GPU stage timings must stay within explicit per
stage thresholds. Use `-AllowPerformanceBudgetMiss` only for exploratory runs
where collecting artifacts is more important than acceptance. GPU timestamp
readiness is optional for exploratory runs and becomes required only when
`-RequireGpuTimestampReady` is passed.
Device-facing runtime profile execution and smoke wrappers require
`-Serial <quest-serial>` or `RUSTY_QUEST_SERIAL`; they must not select an
implicit ADB target. `-AdbServerPort` or `RUSTY_QUEST_ADB_SERVER_PORT` can be
used for intentional non-default ADB server routing. The wrapper uses
PID-scoped logcat evidence by default and does not run `logcat -c` unless
`-ClearLogcat` is explicitly passed for a run that owns the headset lease.
For the later live-hand retest, the same wrapper should be run with
`-EvidenceMode LiveVisualDiagnosticCaveat`; that applies
`quest-native-renderer-live-hand-visual-diagnostic.profile.json` by default and
switches the marker gate from replay proof to the live visual caveat while
keeping screenshot overlay-color checks active.
ADB and child PowerShell calls are captured with `ErrorActionPreference`
temporarily set to `Continue`, so native stderr is recorded in the run summary
with the real exit code instead of surfacing as a PowerShell
`NativeCommandError`.

The static test verifies the package route, Quest/OpenXR/camera manifest
surface, Rust NativeActivity identity, public plan fixture consumption, camera
ids `50` and `51`, NDK `AImageReader` hardware-buffer acquisition, native
`AHardwareBuffer` description, Rust/JNI framework permission request, OpenXR
loader staging, OpenXR/Vulkan prerequisite probe tokens, Vulkan external-HWB
boundary tokens, NativeActivity input-queue draining for `InputAvailable`,
required `RUSTY_QUEST_NATIVE_RENDERER` counters, the runtime-submit projection
marker, the public recorded hand topology/shape fixture, the optional
`RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR` local capture generator, the
paired left/right replay-set parser, right-hand distinct-source markers, source
handedness markers for the primary and secondary GPU mesh visuals, the native
Vulkan recorded mesh storage-buffer boundary, the resident GPU-skinned-mesh
triangle visual path, browser-matched component-rank labels, continuous
single-surface hand material markers, the optional post-skinning graft-copy
visual route and property,
the public low-resolution native guide blur graph, the native Vulkan compute
SDF field over recorded-compatible compact-joint
input, the live `XR_EXT_hand_tracking` compact input adapter, the resident
source mesh buffers, the no per-frame joint-matrix upload boundary, GPU
skinning pass, resident skinned-position buffer, opt-in SDF overlay scorecard
markers, tile-bin shader compilation, triangle-bounds/tile-header/tile-index
storage buffers, tile-local SDF shader reads, SDF update cadence/cache markers,
the `native_renderer_options`
property parser boundary for replay visual proof and compact hand source
selection, host CPU per-stage timing markers for camera/import, guide graph,
live-hand locate, SDF preparation, hand visual, projection composite, swapchain
wait, queue submit, and OpenXR end-frame, the modular Vulkan timestamp-query
owner and marker fields for camera projection, guide graph, hand SDF,
hand-mesh visual, and projection composite GPU timings, the public no-op private
extension ABI slot marker with no linked payload, the runtime artifact evidence
checker plus accepted/damaged replay visual log fixtures, and absence of Makepad
or legacy compatibility route tokens in the app source/build path. It also has
a live-hand diagnostic caveat fixture that accepts live compact-input markers
only when `liveHandMeshVisualAcceptance` and `liveSdfVisualAcceptance` remain
`pending-repeat-headset-visual-proof`, and a damaged fixture rejects marker-only
live acceptance without screenshot proof. It also rejects
Java/C++ packaging tokens for this low-level route. The build command requires
Android SDK, JDK, and NDK paths in the current process and writes a debug APK plus
`rusty.quest.native_renderer_android.build_manifest.v1` under `target/`.

Current caveat: this validates the Android NativeActivity scaffold and native
HWB acquisition shape. The 2026-06-17 headset smokes visually validate the
native diagnostic projection, direct-HWB metadata target, guide-texture final
projection route, and no-real-hands recorded replay mesh/SDF overlay. The
current recorded replay slice removes the per-frame CPU screen-space SDF
diagnostic from the render loop and reports
`cpuSdfPerFrame=false`; local builds can embed the real recorded Meta/OpenXR
hand capture, stage its bind mesh into a native Vulkan storage buffer, and draw
the real animated hand mesh from the resident GPU-skinned position buffer in
the target projection area. The no-real-hands replay proof path is explicit:
`debug.rustyquest.native_renderer.replay.visual_proof.enabled=true` selects
the recorded replay by default, enables the high-contrast mesh diagnostic and
the SDF visual, and reports `compactHandInputSourceMode=recorded-replay`.
`debug.rustyquest.native_renderer.hand_mesh.input.source` can still force
`auto`, `recorded-replay`, or `live-meta-openxr-hand-tracking` for isolation
tests. Full replay visual APK builds should use `-RequireRecordedHandCapture`
with `-RecordedHandCaptureDir` so a metadata-only public fixture cannot be
mistaken for a mesh/SDF visual test. The runtime property bundle is captured in
`fixtures/runtime-profiles/quest-native-renderer-replay-visual-proof.profile.json`,
with a dry-run plan emitted by `tools/check_all.ps1`. The public HWB
peripheral stretch/blend route is captured in
`fixtures/runtime-profiles/quest-native-renderer-hwb-peripheral-stretch.profile.json`;
its dry-run evidence checks the Makepad-matched stretch controls and expected
markers, including `guideProjectionCoverage=full-eye-peripheral-stretch` and
`cameraProjectionPath=metadata-target-guide-texture-peripheral-stretch-final`.
The live-hand diagnostic
bundle is captured in
`fixtures/runtime-profiles/quest-native-renderer-live-hand-visual-diagnostic.profile.json`;
it forces `compactHandInputSourceMode=live-meta-openxr-hand-tracking` and
`allowsRecordedFallback=false`, but it remains pending until screenshot evidence
shows visible mesh/SDF color inside the target projection. Host-side unit tests
cover the parser defaults, replay-proof source selection, explicit live-source
override, SDF cadence clamp, diagnostic offset/alpha clamps, and graft-copy
toggle default/parse behavior. The compact-joint GPU SDF visual is
otherwise present but disabled by default behind
`debug.rustyquest.native_renderer.sdf.visual.enabled`. It now uses recorded rig
blend indices/weights plus compact joint frames, or live OpenXR hand tracking
when available, to upload only runtime joint poses plus packed tip lengths per
frame; GPU passes then skin the mesh and build or reuse the SDF field from
resident buffers. The SDF update cadence is controlled by
`debug.rustyquest.native_renderer.sdf.update_period_frames` and markers
separate `sdfFieldUpdateDispatched` from `sdfFieldReused`. The 2026-06-17
source/build validation exercised the real recorded capture parser with
`RecordedHandFrameLimit=8` and built a real-capture APK; the later replay smoke
ran a full recorded-capture APK and passed screenshot target, hand-mesh, SDF
overlay-color, and performance-budget gates. Live hand tracking marker
readiness is not live visual acceptance: during the 2026-06-17 live-hand
check the user had real hands in view, but did not see a mesh or SDF
representation in the headset. The live visual path now keeps the resident
skinned-position buffer in OpenXR reference-space meters and projects live
hands through each eye's OpenXR pose/FOV, with OpenXR eye-space `+Y` converted
for the positive-height Vulkan viewport. A later headset retest must confirm
that the mesh and SDF representation are visible in-headset, preferably with
`debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled` and the
diagnostic alpha property enabled. The guide graph has
source/static validation plus headset replay evidence for 384x384 downsample,
split horizontal/vertical 5-tap blur, guide cache markers, and final
guide-texture projection. Color-correct camera projection output remains a
separate pending validation gate; the direct-HWB YCbCr/swapchain A/B profiles
only expose suggested/effective sampler metadata and selected swapchain format
so headset review can isolate range/matrix/gamma behavior. Direct Meta
hand-mesh topology import,
Matter/Lattice SDF parity, and live-hand headset visual SDF acceptance also
remain separate pending validation gates. The
GPU timestamp query scaffold is source-validated, but its numeric values remain
pending runtime acceptance through `gpu-timestamp-timing` markers on a Quest
replay or live run.

The tests validate Quest-to-Quest and Quest-to-Android phone duplex fixtures and
reject a damaged fixture that tries to carry high-rate camera payloads through
control JSON. They also validate the low-rate runtime endpoint bindings that
name adapter kind, sender source kind, sender source ports, sender media
profiles, Quest stereo sender camera ids, camera permission policy, receiver
listen ports, peer transport ingress ports, and outgoing transport routes for
each media endpoint. Quest stereo endpoints are expected to bind outside left
eye camera id `50` and outside right eye camera id `51`.

The remote-camera profile fixture also runs through the existing dry-run
property planner:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath fixtures\runtime-profiles\quest-remote-camera-q2q-diagnostic.profile.json -DryRun -Out local-artifacts\remote-camera-property-write-plan.json
```

Runtime profile validation and `Apply-RuntimeProfile.ps1` both reject Android
property values above the on-device `setprop` byte limit. Remote-camera media
profile and direct-route properties therefore use compact strings instead of
full lane ids.

The Manifold broker Android scaffold has two validation levels:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-ManifoldBrokerAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1
```

The static test verifies package naming, `/manifold/v1/events`, Manifold
command-envelope acknowledgement support, remote-camera command lifecycle
hooks, receiver port and transport route property consumption, sender bridge
markers, sender-source runtime support for Camera2/MediaCodec and diagnostic
synthetic MediaCodec sources, the high-rate JSON payload ban, and absence of
legacy Rusty-XR tokens. The build command requires an Android SDK and JDK in
the current process and writes a debug APK plus build manifest under `target/`.
The camera-source broker APK is expected to declare `android.permission.CAMERA`,
`horizonos.permission.HEADSET_CAMERA`, and
`horizonos.permission.SPATIAL_CAMERA`; that expectation is specific to this
broker adapter and does not change the camera-free Makepad app validation lane.

## Live Quest Remote Camera Smoke

The first 2026-06-12 Quest smoke evidence is recorded in the local
developer evidence archive as `remote-camera-broker-20260612-stereo-ids`.

The run installed a locally built `rusty-manifold-broker.apk` with SHA-256
`4C5ED7DDEC5738A70DFB9B76DB5AD8609B60311B56A492B424D3F2AF1B5C2024`, granted
camera permissions, applied the diagnostic Q2Q runtime profile, and drove the
broker through `/manifold/v1/events`. The clean smoke summary reports
`receiver_armed`, `sender_transport_bridge_started`, a live status snapshot
with four active lanes and zero failed lanes, and stopped cleanly.

The captured receiver stream stats prove binary H.264 media on both local
receiver sockets for that build:

- left lane: `camera_id=50`, 1280x1280, `RMQVID01`, 1,517,104 bytes;
- right lane: `camera_id=51`, 1280x1280, `RMQVID01`, 1,512,879 bytes.

`RMQVID01` was an interim Quest-broker magic in the captured APK. Current source
emits `RMANVID1`, the repo-family Manifold stream magic consumed by the Makepad
H.264 reader. Rebuild and rerun live peer validation before treating the
Manifold-magic path as headset evidence.

The current Manifold-framing smoke evidence is recorded in the local developer
evidence archive as `remote-camera-broker-20260612-rmanvid1-smoke`.

That run installed rebuilt APK SHA-256
`44E9E907F4FC68ADD0912613760275460D2FC10D2C2798A0D8B7EC53C4A3C474`, applied
the Q2Q diagnostic runtime profile, used a command-level loopback route
override (`left:127.0.0.1:9079;right:127.0.0.1:9080`), and drove
`/manifold/v1/events` through hello, start receiver, start sender, live status,
and stop.

The compact status check reports:

- `active_count=4`, `failed_count=0`;
- two `source_streaming_camera2` source states;
- camera ids `50` and `51`;
- `high_rate_json_payload=false`;
- left receiver stream: `RMANVID1`, `camera_id=50`, 1,812,763 bytes;
- right receiver stream: `RMANVID1`, `camera_id=51`, 1,815,524 bytes.

This is direct TCP self-loop broker evidence on one Quest. It does not replace
future two-headset LAN validation, Quest-to-Android-phone validation, TLS relay
validation, or Quest Makepad projection validation.
