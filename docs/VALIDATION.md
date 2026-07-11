# Rusty Quest Validation

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
```

The default gate targets native OpenXR/Vulkan and Meta Spatial SDK surfaces.
It skips the legacy Makepad runtime profile and QCL099 runner; use
`-IncludeLegacyMakepad` only for an explicitly requested compatibility or
historical replay pass. QCL100 validation includes the hardware-free one-command
native stereo promotion-candidate gate. The current accepted live baseline is
`qcl100-native-stereo-promotion-candidate-20260710T1236Z`, promoted through the
monitored reducer only after full-stereo duplex, both-device lifecycle, and final
route-clear acceptance passed.

The packed SBS profile has an independent promoted baseline:
`qcl100-packed-sbs-duplex45-20260710T155638Z`. Its acceptance requires two
simultaneous directions and, per device, exactly one `stereo` sender lane, one
Rusty-owned `p2p0` receiver lane, one GPU compositor/encoder, bounded unique
Camera2 sensor-timestamp pairs, one hardware decoder, fresh packed
`AHardwareBuffer` projection, and the same AHB sampled as both eye views. Local
synthetic and real-Camera2 loopbacks plus one-way direct-P2P are prerequisites,
not promotion substitutes. Run the focused static gate and reducer with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-Qcl100PackedStereoStatic.ps1 -RepoRoot .
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-Qcl100PackedStereoRun.ps1 `
  -RunDir .\target\qcl100-packed-sbs-duplex45-20260710T155638Z -Direction duplex `
  -SyntheticLocalSummaryPath .\target\qcl100-packed-sbs-local-synthetic-20260710T153420Z\packed-stereo-local-summary.json `
  -Camera2LocalSummaryPath .\target\qcl100-packed-sbs-local-camera2-20260710T153456Z\packed-stereo-local-summary.json `
  -Qcl041SummaryPath .\target\qcl100-packed-sbs-qcl041-gate-20260710T153634Z\summary.json `
  -DualLaneFallbackPromotionPath .\target\qcl100-native-stereo-promotion-candidate-20260710T1236Z\qcl100-promotion-candidate-plan.json
```

The compatibility default remains `separate-eye-streams`; packed validation is
activated only by `media_layout=side-by-side-left-right`. The previously
promoted two-lane state remains valid fallback evidence.

The runtime profile validation path is dry-run only. It validates runtime profile
fixtures and generates a deterministic property write plan without touching a
headset or ADB server. The native renderer profiles are the public validation
matrix for the main native Quest XR stack: they select custom Camera2/HWB
projection, native Meta passthrough, or a solid black projection background
without changing APK identity or hiding route state in ad hoc launch scripts.
The Spatial Camera Panel panel lane adds a separate static/build/headset gate
for `apps/spatial-camera-panel-android`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1 -Build
```

The hand-tracking lab lanes have dedicated wrappers:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeOpenXrHandLabAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialHandLabAndroid.ps1
```

Pass `-Build` only when the Quest build/ADB resources have been reserved. The
native wrapper resolves `native-openxr-hand-lab.app.json` and builds from its
feature lock; the Spatial wrapper builds the same Spatial app under the
`io.github.mesmerprism.rustyquest.spatial_hand_lab` identity. To compare
Spatial SDK built-in avatar hands, set
`debug.rustyquest.spatial.avatar_hands.visible=true` before launch.
For app-owned native hand mesh edge inspection, set
`debug.rustyquest.native_renderer.hand_mesh.visual.wireframe.enabled=true`
and choose `debug.rustyquest.native_renderer.hand_mesh.visual.wireframe.width_px`
between `0.50` and `4.00`. Existing hand diagnostic profiles enable this
overlay for recorded-joint replay, live visual diagnostic, passthrough
hands/grafts, and solid-black hands/grafts.

For native hand material plus live joint recording, use the CLI control wrapper
around the native hand lab:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererHandJointCapture.ps1 -Serial <quest-serial> -Action Prepare -MaterialProfile unity-basic-reference -Wireframe -DisableSdfVisual
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererHandJointCapture.ps1 -Serial <quest-serial> -Action Start -SessionId hand-joints-test-001
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererHandJointCapture.ps1 -Serial <quest-serial> -Action Stop
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererHandJointCapture.ps1 -Serial <quest-serial> -Action PullAndInspect -SessionId hand-joints-test-001
```

`Prepare` sets the startup material and live-hand properties; relaunch the
native `NativeActivity` after that step. `Start` and `Stop` update
`hand-joint-capture-control.json` in app-scoped external storage while the app
is running. `PullAndInspect` retrieves `left/right.clip.jsonl` and verifies
that recorded joint rows have the 21 runtime poses plus 5 tip lengths expected
by the compact joint skinning replay path.

For a fresh native OpenXR capture with the runtime-provided FB mesh topology,
use the full mesh capture wrapper instead:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererHandMeshCapture.ps1 -Serial <quest-serial> -Action Prepare -MaterialProfile unity-basic-reference -Wireframe -DisableSdfVisual
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererHandMeshCapture.ps1 -Serial <quest-serial> -Action Start -SessionId hand-mesh-native-001
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererHandMeshCapture.ps1 -Serial <quest-serial> -Action Stop
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererHandMeshCapture.ps1 -Serial <quest-serial> -Action PullAndInspect -SessionId hand-mesh-native-001
```

This route requires `XR_EXT_hand_tracking` and `XR_FB_hand_tracking_mesh`. It
writes `left/right.rig.json`, `left/right.clip.jsonl`,
`left/right.validation_mesh.jsonl`, `status.jsonl`, and
`capture.manifest.json` under app-scoped external storage. The clip rows are the
same 21 runtime joints plus 5 tip lengths used by replay skinning, while the
validation mesh rows are CPU-skinned from the OpenXR FB bind mesh.

For the Spatial hand lab comparison capture, use:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialHandCapture.ps1 -Serial <quest-serial> -Action Prepare -ShowAvatarHands $true -EnableBillboardWireframe
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialHandCapture.ps1 -Serial <quest-serial> -Action Start -SessionId hand-spatial-001
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialHandCapture.ps1 -Serial <quest-serial> -Action Stop
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialHandCapture.ps1 -Serial <quest-serial> -Action PullAndInspect -SessionId hand-spatial-001
```

Spatial SDK public APIs expose `AvatarBody` hand/head transforms and built-in
`AvatarSystem` hand visuals, not the SDK-owned hand mesh topology. The Spatial
capture manifest therefore marks `spatial_public_mesh_topology_available=false`
and records `spatial_poses.jsonl` plus OpenXR joint-bridge clips when the
Spatial app has OpenXR handles.

For OpenXR-to-Spatial world alignment, prepare a clean diagnostic view instead
of the billboard fallback:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialHandCapture.ps1 -Serial <quest-serial> -Action Prepare -ShowAvatarHands $true -DisableBillboardWireframe -DisableNativeSurfaceParticleLayer
```

The clean rollback profile is `viewer-world-basis-registration`. The
headset-accepted Spatial hand-lab mapping is selected explicitly:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialHandCapture.ps1 -Serial <quest-serial> -Action Prepare -ShowAvatarHands $true -DisableBillboardWireframe -BillboardParticleSource openxr-live-custom-mesh -AlignmentMappingProfile mirror-x-origin-registration -DisableNativeSurfaceParticleLayer
```

The mirror-X profile captures translation from the current OpenXR and
Spatial viewer origins, maps position as `(-x, y, z)`, and converts OpenXR
`xyzw` orientation to Spatial `xyzw` as `(z, w, x, y)`. If startup supplies an
all-zero Spatial viewer pose, the bridge recaptures the registration once a
live origin arrives. The 2026-07-10 headset run reduced viewer error from a
reproduced 1.108 m floor offset to 0.0000 m / 0.0000 degrees and visually
confirmed that the OpenXR joint markers overlay the built-in Meta hands.
Viewer marker spheres default to disabled through
`debug.rustyquest.spatial.hand_alignment.viewer_markers.enabled=false`; set it
to `true` only when re-running viewer-pose registration diagnostics.

This renders one app-owned marker per OpenXR hand joint, larger Spatial SDK
hand-anchor markers, bone lines, anchor-to-palm/wrist delta lines, and logs
`spatial-openxr-hand-alignment` samples comparing `AvatarBody` hand anchors,
OpenXR `palm_ext`/`wrist_ext`, `Scene.getViewerPose`, and the bridge-mapped
OpenXR view pose.

For the public Spatial world-hand billboard flock, the high-density carrier
property is `debug.rustyquest.spatial.hand_billboard_flock.carrier`. The
default `batched-scene-mesh` mode should report
`carrier=batched-scene-mesh`, `carrierEntityCount=2`, and
`transformWrites=0`; `ecs-entities` is retained only as the old per-particle
entity baseline.
The dedicated hand lab defaults to 2048 app-owned billboards (1024 per hand)
at the established 8 mm live/replay particle size, sampled from the
recorded custom mesh and CPU-skinned from the mapped OpenXR joints. Hotload
`debug.rustyquest.spatial.hand_billboard_flock.source=spatial-sdk-anchor-flock`
to return to the loose `AvatarBody` hand-anchor proxy, or
`openxr-live-custom-mesh` to return to the accepted mesh-surface path.
Set `debug.rustyquest.spatial.hand_billboard_flock.visual_mode=wireframe-edges`
to replace each app-owned billboard mesh item with edge quads. This is not a
wireframe of the Spatial SDK built-in avatar hands; those remain
`AvatarSystem`-owned and should report
`spatialAvatarHandMeshWireframeSupported=false`.

For a Spatial Camera Panel APK whose private-layer buttons visibly select the
active projection layer, pass the downstream opaque shader profile at build
time:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1 `
  -Build `
  -PrivateLayerProfilePath <path-to-private-layer-profile.json>
```

The static gate checks the Spatial SDK package identity, `AppSystemActivity`
activity, `VRFeature`/`ComposeFeature` registration, panel shape/resolution
settings, app-private experiment record schemas, joinable questionnaire fields,
direct BLE Polar panel wiring, ECG event mirroring, the experiment
condition-to-native-parameter handoff, and the high-rate payload ban. The build
gate writes
`target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk` plus
`rusty.quest.spatial_camera_panel_sdk_android.build_manifest.v1`. Live validation
must install and launch that APK with serial-scoped ADB, then confirm the
participant setup, surface selection, block start/completion, workflow-panel
transition back to particle view, condition parameter handoff markers,
automatic questionnaire, submit path, and `RUSTY_QUEST_SPATIAL_CAMERA_PANEL` log
markers.
Use `tools\Invoke-SpatialCameraPanelAndroidSelfTest.ps1 -Serial <quest-serial>`
for the repeatable headset lane; it captures PID-scoped logcat, app-private
session JSONL artifacts, and `evidence-summary.json`, and rejects missing
panel/particle/condition/Polar-panel markers. Reserve `quest:<serial>` before
running it.
Polar live validation additionally requires headset-side BLE permission
acceptance plus a nearby Polar H10 scan/connect/start-ECG run, with
`polar-sensor-panel` markers and app-private `polar_events.jsonl` /
`ecg_events.jsonl` evidence. Use
`tools\Invoke-SpatialCameraPanelAndroidPolarLive.ps1 -Serial <quest-serial>`
for that gate; it launches `RUN_POLAR_LIVE_VALIDATION`, pregrants the declared
BLE runtime permissions where Android allows it, captures PID-scoped logcat
plus root Polar status/stream files and participant JSONL files, and fails
unless the scan/connect/start-ECG path yields decoded ECG frames mirrored into
`ecg_events.jsonl`. Reserve `quest:<serial>` before running it.
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
HWB frame markers also report `imageDataspace`/`imageDataspaceStatus` when the
runtime exports `AImage_getDataSpace`. Setting
`debug.rustyquest.native_renderer.camera.luma_diagnostic.enabled=true` enables
the opt-in Vulkan compute luma diagnostic and adds per-eye luma mean/min/max
and high-frequency ratio fields to `timing-scorecard`; the default profile
leaves that diagnostic off.
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
lower-latency `delete-async-release-fence` sync mode now activates the async
ImageReader acquire/release APIs and reports acquire fence fd presence, while
its markers explicitly keep Vulkan external-semaphore ownership transfer as
pending. Stereo pairing defaults to `latest-latest`; setting
`debug.rustyquest.native_renderer.camera.stereo_pairing=nearest-timestamp`
uses a bounded recent-frame ring and reports `stereoPairingPolicy` with
`stereoPairDeltaNs` in `timing-scorecard`. The
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
`quest-native-renderer-display-composite-feedback.profile.json` validates the
MediaProjection display-composite settings surface over native Meta
passthrough. It selects `native-passthrough-media-only`, disables Camera2
output, guide blur, hand/SDF/graft/stimulus/depth/private visual routes,
normalizes projection target scale/offset, configures the native
`AImageReader` size, queue depth, frame cap, feedback projection label, and
`high_rate_json_payload=false`; the selected display-composite mode is
`gpu-recursive-feedback-diagnostic`, which samples the current MediaProjection
`AHardwareBuffer` into an app-owned device-local feedback texture with
diagnostic borders and previous-feedback blending disabled before the eye
projection draw, applies luma damping to avoid recursive brightness ratcheting,
then submits it through fully opaque premultiplied-alpha projection-layer
composition into an aggressively shrunken centered target footprint. The
Android path still must call `createScreenCaptureIntent`
so every launch receives fresh result data. The
device-facing lab pregrant is
`tools/Grant-NativeRendererPermissions.ps1 -GrantMediaProjectionAppOp`, with
`-ResetMediaProjectionAppOp` after the smoke. Static checks require the Android
14 foreground-service media-projection declaration, the `PROJECT_MEDIA` app-op
route, Rust-created `Surface`, native `AImageReader`/`AHardwareBuffer`
descriptor handoff, the reusable `ahardware_buffer_vulkan.rs` import helper,
the recursive feedback texture markers, the serial-scoped display-composite
smoke wrapper, `XR_FB_passthrough` active, camera projection disabled, and no
service `ByteBuffer`/plane-copy path.
`quest-native-renderer-environment-depth-status.profile.json` is the first
environment-depth source-only profile. It validates the low-rate status
surface, explicit capacity/stride/range properties, requested OpenXR reference
space label, and `environmentDepthHighRateJsonPayload=false`; damaged profiles
reject high-rate JSON payloads, invalid capacities, invalid near/far ranges,
invalid source-layer requirements, and impossible radius/min-neighbor support
thresholds before any ADB write.
`quest-native-renderer-native-passthrough-environment-depth-particles.profile.json`
validates the next native GPU proof profile. It selects native passthrough,
sets `environment_depth.mode=retained-particles` and
`environment_depth.source=synthetic-gpu-proof`, and requires markers for a
resident Vulkan particle buffer, zero CPU-expanded particle upload, and
OpenXR reference-space particle coordinates. This is a proof of the native
passthrough particle mapping stack; it is not acceptance evidence for a real
runtime environment-depth provider.
`quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json`
is the real Meta provider proof profile. It selects
`environment_depth.mode=scene-particle-map` and
`environment_depth.source=xr-meta-environment-depth`, sets
`environment_depth.layer_policy=mono-layer0`,
`environment_depth.depth_units_policy=projected-depth-from-near-far`, and
`environment_depth.debug_view=raw-d16`, requires
`horizonos.permission.USE_SCENE` in the APK manifest and permission pregrant
summary, and validates runtime markers for acquired D16 two-layer
`XR_META_environment_depth` frames, explicit mono-layer source policy
(`environmentDepthSourceViewCount=1`, `environmentDepthSampledLayerMask=0x1`,
`environmentDepthShaderLayerPolicy=mono-layer0`), the projected-depth raw-to-meter
policy marker, raw D16 aggregate readback fields, valid depth pose, nonzero
source depth samples, OpenXR-local scene cells, the spatial-hash map policy,
the `atomic-slot-claim` map-write policy, preserve-existing-cells
invalid-sample behavior, confidence-gated visible-free-space correction,
the `near-plus-cell-step-cap` free-space range policy, scene-map health counters
for hash insert/merge/stale replace, probe exhaustion, approximate occupancy,
hash conflicts, failed claims, free-space retire attempt/success counts, and
valid-but-too-low-confidence free-space skip counts, explicit render view-state
flags, capture-to-display/frame-age timing, repeated-capture and
unavailable-streak counters, texture-transform/ray-UV/sample-UV policy labels,
zero expanded CPU particle upload, resident GPU buffers, and
`environmentDepthParticleBufferMemory=device-local`.

The Spatial Camera Panel path now declares the same scene-depth permission
surface for diagnostics and its camera/video smoke wrapper writes
`permission-pregrant.json` with `USE_SCENE_DATA` app-op state. That does not
claim real depth data by itself. The Spatial path also requests
`XR_FB_passthrough` and records `nativePassthroughLayerActive` /
`environmentDepthPassthroughPrerequisite=active` when the native passthrough
activation call succeeds, because current headset evidence indicates depth may
not provide real samples until passthrough is running. Current Spatial public
multi-stack markers must separate fallback readiness from the active descriptor
source: unbound/default runs still report the fallback descriptor, while real
depth runs require `publicMultiStackDepthRealDescriptorBound=true`,
`publicMultiStackDepthCurrentDescriptorSource=xr-meta-environment-depth`,
`environmentDepthValidData=true`, and nonzero valid sample counters.
The Spatial private-layer panel exposes depth source policy choices for
`mono-layer0`, `mono-layer1`, `eye-index`, and `compare`. The 2026-06-29
`-DepthLayerPolicy compare` smoke rendered layer 0 and layer 1 at the same
shader UV and showed structured per-eye differences. Treat that as visual
evidence that the two layers are not trivially identical, but not as literal
byte-for-byte readback proof. General Spatial depth-stack alignment is deferred
to manual panel calibration and later alignment work.
`quest-native-renderer-native-passthrough-meta-environment-depth-particles-layer1.profile.json`
is the matching layer-1 comparison profile. It switches only
`environment_depth.layer_policy` to `mono-layer1` and requires
`environmentDepthSampledLayerMask=0x2` plus
`environmentDepthShaderLayerPolicy=mono-layer1`; this validates a second
mono-source sample path before any stereo-two-layer policy is accepted.
`quest-native-renderer-native-passthrough-meta-environment-depth-particles-low-capacity.profile.json`
is the stress profile for the same layer-0 real Meta provider route. It keeps
the scene-map, native passthrough, raw-D16, projected-depth, and OpenXR-local
reference-space policy fixed, but sets `particle_capacity=64` and
`sample_stride_pixels=4` so a headset smoke run can assert the bounded
spatial-hash path under collision pressure. Acceptance for this profile should
pass the environment-depth particle marker gate with
`-ExpectedEnvironmentDepthParticleCount 64` and
`-MinimumEnvironmentDepthHashProbeExhaustedCount 1`; it is a stress/evidence
fixture, not the default quality profile.
`quest-native-renderer-native-passthrough-meta-environment-depth-particles-debug-colors.profile.json`
is the diagnostic color profile for the same real Meta scene-map route. It
sets `environment_depth.debug_view=free-space-state`, which the renderer
reports as `environmentDepthDebugView=free-space-state` and
`environmentDepthParticleDebugColorMode=free-space-state` on the particle
marker. The default raw-D16 profiles still draw particles with
`environmentDepthParticleDebugColorMode=depth-gradient`; other accepted
diagnostic debug-view values are confidence, age, source-layer, and hash-probe.
The Iteration 8 environment-depth matrix is dry-run validated through
`quest-native-renderer-envdepth-layer0.profile.json`,
`quest-native-renderer-envdepth-layer1.profile.json`,
`quest-native-renderer-envdepth-raw-depth-debug.profile.json`,
`quest-native-renderer-envdepth-local-space.profile.json`,
`quest-native-renderer-envdepth-stage-space.profile.json`,
`quest-native-renderer-envdepth-capacity-65536.profile.json`,
`quest-native-renderer-envdepth-stride-8.profile.json`, and
`quest-native-renderer-envdepth-hand-removal.profile.json`. These fixtures keep
the real Meta provider scene-map path fixed and vary one acceptance axis at a
time: sampled depth layer, raw-D16 debug view, OpenXR local/stage reference
space, particle capacity, sample stride, or the
`xrSetEnvironmentDepthHandRemovalMETA` request path.
The surface-support quality-control matrix is dry-run validated through
`quest-native-renderer-envdepth-local-surfels.profile.json`,
`quest-native-renderer-envdepth-global-surfaces.profile.json`,
`quest-native-renderer-envdepth-hybrid-surfaces.profile.json`, and
`quest-native-renderer-envdepth-source-layer-agreement.profile.json`. These profiles
validate `environment_depth.surface_model`,
`environment_depth.surface_support.radius_cells`,
`environment_depth.surface_support.min_neighbors`,
`environment_depth.surface_support.min_observations`,
`environment_depth.surface_support.min_source_layers`,
`environment_depth.surface_support.component_min_cells`,
`environment_depth.surface_support.component_mode`,
`environment_depth.surface_support.normal_source`,
`environment_depth.surface_support.normal_coherence`,
`environment_depth.surface_support.small_component_policy`, and
`environment_depth.surface_support.free_space_decay`. Runtime evidence now
requires the matching `environmentDepthSurfaceSupport*` marker fields. Dry-run
profile evidence remains low-rate and must not claim filtering by itself. On a
real runtime frame, requested surface modes can now report
`environmentDepthSurfaceSupportEnforced=true`,
`environmentDepthSurfaceSupportStatus=enforced-local-depth-neighborhood-component-local-hint`,
and nonzero supported/rejected-cell counters from the GPU local-depth
neighborhood gate. The same runtime marker now includes
`environmentDepthSurfaceLifecycleStatus` plus candidate, confirmed, promoted,
candidate-retired, component-mode, small-component, local-patch max,
component-candidate, confirmed-component, normal-source, valid-normal,
invalid-normal, and normal-rejected counters for the scene-cell lifecycle.
The component counters are aggregate local-hint evidence unless a later
connected-labels pass is accepted. Source-layer
agreement profiles additionally require
`environmentDepthSourceLayerAgreementRequired`,
`environmentDepthSourceLayerAgreementCells`, and
`environmentDepthSingleLayerOnlyCells` markers; they do not make stereo fusion
or two-layer agreement accepted by themselves. Host-side
`environment_depth_geometry` tests provide the synthetic CPU mirror for
reference-space reconstruction, render-eye reprojection, retained-point
projection through current view pose/FOV, and pose-delta evidence. Host-side
`environment_depth_scene_map` tests provide the synthetic CPU mirror for
spatial hash keys, same-cell merge/promotion, same-cell two-source-layer
promotion, layer-offset single-layer candidate separation, fresh collision
probe exhaustion, stale replacement, free-space retire behavior, and rejection
of impossible oracle thresholds. Host-side
`environment_depth_surface_support` tests provide the synthetic CPU mirror for
flat-plane coherent normals, invalid hole neighborhoods, depth-step rejection,
connected-component small-cluster policy, candidate promotion, candidate
retirement, source-layer agreement, and dynamic-object ghost retirement across
appear/confirm/move sequence fixtures. They also cover retained-cell
neighborhood normals for flat retained planes, missing retained neighbors,
discontinuous retained cell positions, pose-shifted retained scene-cell
samples, plus compact surface descriptor packing for confirmed cells, hidden
small components, and mismatched grids. Runtime
surface-support evidence now requires
`environmentDepthSurfaceNormalStatus=depth-neighborhood-gpu-readback` plus
nonzero depth-neighborhood normal counters before the GPU normal pass is
accepted. This is not yet
connected-component or global-surface acceptance; those remain pending alongside
the movement-required world-space proof.
The static environment-depth gate also checks that the scene-map and
surface-support CPU mirror modules are host/test-only and are not imported by
the live GPU/OpenXR runtime path. The headset CPU may orchestrate profiles,
permissions, provider setup, command submission, low-rate pose/timing
calculations, and aggregate evidence, but the high-rate depth map, support
counters, retained particles, and draw path remain GPU-owned.
`check_all.ps1` delegates the native renderer runtime-profile matrix to
`tools\Test-NativeRendererProfileMatrix.ps1`. That helper owns the exact
native-renderer profile and damaged-profile inventories, runs each valid
profile's declared dry-run command, and rejects every damaged native-renderer
profile under `fixtures\damaged`.
`check_all.ps1` also runs `tools\check_native_renderer_property_parity.py` and
writes `local-artifacts\native-renderer-property-parity.json`. This gate
loads `fixtures\native-renderer\native-renderer-property-manifest.json`,
compares all `quest-native-renderer*.profile.json` property names against the
manifest and native runtime parser constants, validates fixture values against
manifest value kinds, allowed tokens, and numeric ranges, requires every owned
profile property to be explicitly set, requires every manifest entry to name
startup-effective lifecycle, profile-owned explicit-set clear behavior,
runtime-owner default behavior, the runtime parser, profile matrix,
`Apply-RuntimeProfile.ps1`, and the `rusty-quest-profile` Rust validator,
checks that the apply tool and Rust validator are actually wired to the
manifest, checks specialized profile families against literal cross-field
validator surfaces, and protects the Breathing Room profile's explicit
camera/guide/environment-depth/stimulus/private-layer clears. The profile
matrix also runs every native renderer damaged profile through
`Apply-RuntimeProfile.ps1`; that apply tool loads the same manifest, so generic
camera, guide, hand, render, private-layer, and projection-border token/range
mistakes are rejected before any ADB write; it also rejects
environment-depth cross-field mistakes such as a local support neighbor
threshold that exceeds the selected radius. The `rusty-quest-profile` Rust
validator embeds that manifest as well, so dry-run write-plan generation rejects
the same generic native renderer token/range/type mistakes, environment-depth
cross-field mistakes, and manifest authority-metadata drift before specialized
checks run. The
manifest records runtime-owner default behavior instead of duplicating default
values. The Android scaffold static harness calls
`tools\checks\Test-NativeRendererPropertyManifestStatic.ps1` for the smaller
manifest schema, cardinality, and parity-tool wiring assertions that used to
live inline in `Test-NativeRendererAndroid.ps1`. Android manifest, Rust
NativeActivity, input pump, Cargo manifest, build script, and app README
assertions live in
`tools\checks\Test-NativeRendererAndroidScaffoldStatic.ps1`. It also delegates the
native-renderer public/private app-source scan to
`tools\checks\Test-NativeRendererPublicBoundaryStatic.ps1` and the
environment-depth source/profile/fixture/smoke-wrapper token ledger to
`tools\checks\Test-NativeRendererEnvironmentDepthStatic.ps1`. General
runtime-evidence checker, replay-smoke wrapper, and permission-pregrant static
assertions live in
`tools\checks\Test-NativeRendererRuntimeEvidenceStatic.ps1`. Runtime-profile
apply-tool serial scoping and Rust validator manifest-hook assertions live in
`tools\checks\Test-NativeRendererRuntimeProfileStatic.ps1`. Stimulus-volume
renderer, shader, OpenXR action, timing, and route-marker assertions live in
`tools\checks\Test-NativeRendererStimulusVolumeStatic.ps1`. Breathing Room
projection-target route assertions, including Manifold breath/pose transport
and right-hand OpenXR input/haptic markers, live in
`tools\checks\Test-NativeRendererProjectionTargetStatic.ps1`.
Manifold scalar private-particle driver changes should run the host parser and
loopback websocket-adoption tests, property parity, and native app-build static
gate:

```powershell
cargo test -p rusty-quest-native-renderer-android-native manifold_scalar_driver_bridge
python .\tools\check_native_renderer_property_parity.py
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-NativeAppBuildStatic.ps1 -RepoRoot .
```

Those checks prove the public adapter parses Manifold scalar samples, can send
a Manifold websocket `subscribe` command to a local loopback broker, can adopt
a `stream_event` sample into the generic private-particle driver bank, the
runtime property manifest covers its low-rate settings, and the opt-in
`particles.private.manifold_scalar_driver` feature can be selected without
folding Manifold stream input into every private-particle build.
Recorded-hand replay, live compact hand input, GPU-skinned hand mesh visual,
graft-copy, and GPU mesh replay boundary assertions live in
`tools\checks\Test-NativeRendererHandVisualStatic.ps1`.
Target-space GPU SDF field, tile-bin, overlay shader, compact-joint upload,
cadence/cache, and SDF marker assertions live in
`tools\checks\Test-NativeRendererGpuSdfStatic.ps1`. Camera projection metadata,
guide blur/projection, direct-HWB camera quality diagnostic, peripheral-stretch,
source-route profile snippet, and native camera scaffold assertions live in
`tools\checks\Test-NativeRendererCameraGuideStatic.ps1`. MediaProjection
foreground-service declaration, display-composite capture action,
Rust-created `Surface`, native `AImageReader`/`AHardwareBuffer` descriptor
bridge, reusable AHB Vulkan import helper, profile fixture, display-composite
smoke wrapper, and `PROJECT_MEDIA` lab pregrant/reset assertions live in
`tools\checks\Test-NativeRendererDisplayCompositeStatic.ps1`. OpenXR/Vulkan
prerequisite, timing marker, private-slot, render-mode, scorecard, and native
timing counter assertions live in
`tools\checks\Test-NativeRendererOpenXrVulkanStatic.ps1`.
`Test-NativeRendererAndroid.ps1` no longer mirrors native-renderer profile
fixture contents as literal token checks; profile acceptance is owned by the
profile matrix plus manifest-backed parity and validator gates.

The Android scaffold static check also owns the same-APK 2D stimulus panel
ledger. It requires `android:hasCode="true"`, the existing
`android.app.NativeActivity` VR route, the added `ControlPanelActivity`
`com.oculus.intent.category.2D` route, panel layout metadata, Java/d8
`classes.dex` packaging, and build-manifest fields that report
`java_classes_packaged=true`, `panel_activity`, `panel_candidate_file`, and
`panel_status_file`. The same check rejects WebView, `addJavascriptInterface`,
AndroidX, Spatial SDK, `AppSystemActivity`, `VrActivity`, and GLXF tokens in
the first panel slice. The Rust-side panel adapter is validated as a
startup-effective file transport: it consumes
`stimulus_volume_candidate.json`, emits `stimulus-panel` accepted/rejected
markers, rejects missing safety acknowledgement and invalid Hz ranges, maps
accepted candidates onto the existing `NativeStimulusVolumeSettings` owner, and
disables projection-target controls for volume-only routes. The same scaffold
and stimulus-volume checks also require the right-controller trigger panel
toggle bridge: the OpenXR action set binds
`/user/hand/right/input/trigger/value`, emits `rightControllerTriggerPanelToggle`
markers, and the JNI bridge starts `ControlPanelActivity` with a toggle intent.

Use `docs/environment-depth-known-distance-raw-d16-runbook.md` for the
headset known-distance run that compares `environmentDepthRawCenterD16`,
`environmentDepthCenterReconstructedMeters`, and
`environmentDepthRawCenterWindowMedianD16` against 0.5 m, 1 m, 2 m, and 4 m
targets before accepting or replacing the projected-depth formula. The
source-testable wrapper is
`tools\Invoke-NativeRendererEnvironmentDepthKnownDistanceProof.ps1`; it passes
`-RequireEnvironmentDepthKnownDistance`,
`-ExpectedEnvironmentDepthCenterMeters`,
`-EnvironmentDepthCenterToleranceMeters`,
`-MinimumEnvironmentDepthCenterConfidence`, and
`-MinimumEnvironmentDepthCenterWindowValidCount` into the runtime-evidence
checker.

Remote camera session plans are also source-only validation:

```powershell
cargo test -p rusty-quest-remote-camera
```

Device-link reports are source-only validation:

```powershell
cargo test -p rusty-quest-device-link
```

The fixture
`fixtures/device-link/hostess-usb-broker-session.device-link.json` records the
reusable shape expected from a Hostess USB broker-stream session: authorized
ADB identity, ADB forward `18765 -> 8765`, Manifold WebSocket broker endpoint,
runtime subscriber receipt stream, applied command stages, and stream
capability descriptors. Damaged fixtures reject high-rate JSON stream payloads
and applied command claims that do not include runtime receipt evidence.

The same test command validates the QCL-040/QCL-041 Wi-Fi Direct lifecycle
source contract in
`fixtures/device-link/wifi-direct-lifecycle-qcl041-windows.pass.json` and
`fixtures/device-link/wifi-direct-lifecycle-qcl040-android-phone.pass.json`.
Damaged lifecycle fixtures reject template/source-identity gaps, mismatched
quest lease serials, missing bounded TCP socket counters, and cleanup that did
not complete. These are source-contract checks only; a live Hostess promotion
still needs the corresponding leased peer-harness run and QCL-082 product
media receiver evidence.

The device-link test also validates the protocol-neutral direct-P2P socket
route and BLE rendezvous contracts. Their agent-addressable validators are:

```powershell
cargo run --quiet -p rusty-quest-device-link --bin validate_direct_p2p_socket_route -- fixtures\device-link\direct-p2p-socket-route.pass.json
cargo run --quiet -p rusty-quest-device-link --bin validate_product_wifi_direct_run -- fixtures\device-link\product-wifi-direct-run.pass.json
cargo run --quiet -p rusty-quest-device-link --bin validate_ble_rendezvous -- message fixtures\device-link\ble-rendezvous-offer.pass.json
cargo run --quiet -p rusty-quest-device-link --bin validate_ble_rendezvous -- receipt fixtures\device-link\ble-rendezvous-server-ready.receipt.json
cargo run --quiet -p rusty-quest-device-link --bin validate_ble_rendezvous -- pair fixtures\device-link\ble-rendezvous-pair.pass.json
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-PeerRendezvousAndroid.ps1
```

The clean two-Quest no-media product gate is:

```powershell
& .\tools\Build-DirectP2pProviderAndroid.ps1
& .\tools\checks\Test-DirectP2pProviderStatic.ps1
& .\tools\Invoke-DirectP2pProviderTwoQuest.ps1 -GroupOwnerSerial <serial> -ClientSerial <serial>
```

Acceptance requires two typed product receipts, explicit Rust `p2p0` binds,
one bounded request/echo per role, honest Android `Network` availability,
inactive Wi-Fi P2P state after cleanup, zero package/system fatals, and APK
removal. Raw device evidence stays private.

Damaged direct-P2P fixtures reject WLAN fallback and claims that a
`ConnectivityManager.Network` substitutes for the scoped Rusty socket
authority. The Android BLE gate enforces explicit opt-in, bounded GATT,
credential/address redaction, zero media bytes, zero Wi-Fi mutations, zero
Manifold command execution, and cleanup. A one-device advertiser receipt with
`status=ready` proves adapter readiness only; `status=pass` requires an actual
authenticated bidirectional peer exchange. Full pair acceptance uses
`Invoke-PeerRendezvousAndroidPair.ps1`, reverses the GATT roles, requires an
authenticated reconnect in both phases, scans artifacts for raw Bluetooth
addresses and the ephemeral secret, verifies stable Bluetooth/`p2p0` state,
force-stops both packages, and runs the Rust pair validator over the summary.
ADB still launches the evidence run and supplies the ephemeral test secret;
autonomous enrollment and launch are outside this scoped promotion.
The accepted two-Quest baseline is `ble-pair-20260711T025453Z`, whose summary
also embeds a passing independent `pair` validation result.

The Manifold decision-gated product sequence is:

```powershell
cargo run --quiet -p rusty-quest-peer-session-adapter --bin evaluate_ble_pair -- <pair-summary.json> <decision-bundle.json> <now-ms>
& .\tools\Invoke-PeerSessionDecisionGateTwoQuest.ps1 -GroupOwnerSerial <serial> -ClientSerial <serial> -DecisionBundlePath <decision-bundle.json>
```

Acceptance requires topology to remain non-grouped for unauthenticated,
stale-after-revocation, and revoked receipts. A fresh accepted revision must
emit `phase=topology_gate status=accepted` on both peers before the bounded
product exchange, followed by inactive cleanup, zero package/system fatals,
and APK removal. Device logs and decision bundles remain private evidence.

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
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeRendererProfileMatrix.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-NativeRendererAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererReplaySmoke.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -Serial <quest-serial> -RunSeconds 12
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererDisplayCompositeSmoke.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -Serial <quest-serial> -RunSeconds 12
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererReplaySmoke.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -EvidenceMode EnvironmentDepthParticles -Serial <quest-serial> -RunSeconds 12 -AllowFlatScreenshot -AllowPerformanceBudgetMiss
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererEnvironmentDepthKnownDistanceProof.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -Serial <quest-serial> -TargetDistanceMeters 1.0 -ToleranceMeters 0.15 -RunSeconds 8
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererEnvironmentDepthMotionProof.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -Serial <quest-serial> -RunSeconds 12
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererEnvironmentDepthAcceptanceSuite.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -Serial <quest-serial>
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererReplaySmoke.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -EvidenceMode EnvironmentDepthParticles -ProfilePath fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles-low-capacity.profile.json -ExpectedEnvironmentDepthParticleCount 64 -MinimumEnvironmentDepthHashProbeExhaustedCount 1 -Serial <quest-serial> -RunSeconds 12 -AllowFlatScreenshot -AllowPerformanceBudgetMiss
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererEnvironmentDepthMotionProof.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -ProfilePath fixtures\runtime-profiles\quest-native-renderer-envdepth-local-surfels.profile.json -RequireEnvironmentDepthSurfaceSupport -Serial <quest-serial> -RunSeconds 12
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeRendererRuntimeEvidence.ps1 -LogcatPath <filtered-logcat.txt> -ScreenshotPath <screenshot.png> -RequireScreenshot -RequireNonFlatScreenshot -RequireTargetNonFlatScreenshot -RequireHandMeshVisualScreenshot -RequireSdfVisualScreenshot -RequireCameraProjection -RequireReplayVisualProof -RequireGuideGraph -RequireSdfVisual -RequireGpuTimestampReady -RequirePerformanceBudget -RequirePrivateSlotNoPayload
```

`Invoke-NativeRendererReplaySmoke.ps1` is the no-real-hands device wrapper for
the recorded replay path. It installs the APK unless `-SkipInstall` is passed,
pregrants the APK-declared camera/hand/scene permissions through
`tools/Grant-NativeRendererPermissions.ps1`, applies
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
For the real environment-depth particle proof, run the same wrapper with
`-EvidenceMode EnvironmentDepthParticles`; that applies the Meta depth profile
by default and switches the marker gate to
`Test-NativeRendererRuntimeEvidence.ps1 -RequireEnvironmentDepthParticles`.
For a known-distance raw-D16 proof, prefer
`Invoke-NativeRendererEnvironmentDepthKnownDistanceProof.ps1`; it is a thin
single-distance wrapper around the same smoke path with
`-EvidenceMode EnvironmentDepthParticles`,
`-RequireEnvironmentDepthKnownDistance`, raw-D16 debug-view requirements,
center reconstructed-meter tolerance, center confidence, and center-window
valid-count checks. Run it once per measured target distance and compare the
resulting summaries with
`Test-NativeRendererEnvironmentDepthKnownDistanceSeries.ps1` for monotonicity
across 0.5 m, 1 m, 2 m, and 4 m.
For a world-space head-motion proof, prefer
`Invoke-NativeRendererEnvironmentDepthMotionProof.ps1`; it is a thin wrapper
around the same smoke path with `-EvidenceMode EnvironmentDepthParticles`,
`-AllowFlatScreenshot`, `-AllowPerformanceBudgetMiss`,
`-MinimumEnvironmentDepthHeadMotionSamples 120`, and
`-MinimumEnvironmentDepthHeadMotionYawDeg 25` by default. While it runs, perform
the deliberate slow-yaw and optional lateral-translation acceptance motion.
For custom thresholds, the lower-level smoke wrapper also accepts
`-MinimumEnvironmentDepthHeadMotionSamples`,
`-MinimumEnvironmentDepthHeadMotionYawDeg`, or
`-MinimumEnvironmentDepthHeadMotionTranslationM`; those thresholds are checked
against the particle marker's render-view pose-delta evidence while the same
marker still requires `environmentDepthWorldSpaceReady=true`.
After a movement run and the four known-distance runs are captured, use
`Test-NativeRendererEnvironmentDepthEvidenceBundle.ps1` against the
wrapper-produced `run-summary.json` files plus the known-distance series result.
It validates the serial-scoped pregrant/profile/pid-logcat route, the motion
runtime-evidence summary, the known-distance run summaries, and the monotonic
series result as one machine-readable bundle. The bundle checker still records
`human_device_visual_acceptance_required=true`; it does not replace headset
inspection or the visual movement artifact.
For the planned final device pass, prefer
`Invoke-NativeRendererEnvironmentDepthAcceptanceSuite.ps1`. It runs the motion
wrapper first, then the four known-distance wrappers with install skipped after
the first run, then the known-distance series checker, then the evidence-bundle
checker. It requires an explicit serial and preserves the lower-level artifact
directories so individual failures can still be inspected.
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
aggregate facade and `native_renderer_visual_options` parser boundary for
replay visual proof and compact hand source selection, host CPU per-stage
timing markers for camera/import, guide graph,
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

`Test-NativeRendererAndroid.ps1` delegates twelve focused static families to
`tools\checks`: `Test-NativeRendererAndroidScaffoldStatic.ps1` owns the
Android manifest, Rust NativeActivity, input pump, Cargo manifest, build
script, and app README ledger. `Test-NativeRendererPropertyManifestStatic.ps1` owns the
manifest schema/cardinality and parity-tool wiring assertions,
`Test-NativeRendererPublicBoundaryStatic.ps1` owns the source/build boundary
scan that rejects legacy route names and private visual-layer tokens, and
`Test-NativeRendererEnvironmentDepthStatic.ps1` owns the environment-depth
source, profile, fixture, and smoke-wrapper token ledger.
`Test-NativeRendererRuntimeEvidenceStatic.ps1` owns the general
runtime-evidence checker, replay-smoke wrapper, and permission-pregrant static
ledger. `Test-NativeRendererRuntimeProfileStatic.ps1` owns the runtime-profile
apply-tool serial scoping and Rust validator manifest-hook ledger.
`Test-NativeRendererStimulusVolumeStatic.ps1` owns the stimulus-volume
renderer, shader, OpenXR action, timing, and route-marker ledger.
`Test-NativeRendererProjectionTargetStatic.ps1` owns the Breathing Room
projection-target, Manifold breath/pose transport, right-hand OpenXR
input/haptic, and runtime-authority marker ledger.
`Test-NativeRendererHandVisualStatic.ps1` owns the recorded-hand replay, live
compact hand input, GPU-skinned hand mesh visual, graft-copy, and GPU mesh
replay boundary ledger. `Test-NativeRendererGpuSdfStatic.ps1` owns the
target-space GPU SDF field, tile-bin, overlay shader, compact-joint upload,
cadence/cache, and SDF marker ledger.
`Test-NativeRendererCameraGuideStatic.ps1` owns the camera projection metadata,
guide blur/projection, direct-HWB camera quality diagnostic,
peripheral-stretch, source-route profile snippet, and native camera scaffold
ledger. `Test-NativeRendererOpenXrVulkanStatic.ps1` owns the OpenXR/Vulkan
prerequisite, timing marker, private-slot, render-mode, scorecard, and native
timing counter ledger. The main harness still executes the runtime-evidence
logcat gates for the accepted and damaged fixtures.

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
the profile matrix dry-run checks the Makepad-matched stretch controls and expected
markers, including `guideProjectionCoverage=full-eye-peripheral-stretch` and
`cameraProjectionPath=metadata-target-guide-texture-peripheral-stretch-final`.
The related video-border blend route is captured in
`fixtures/runtime-profiles/quest-native-renderer-hwb-video-border-blend.profile.json`;
it dry-runs the same camera guide projection plus the stereo video input path
and expects `guideProjectionCoverage=full-eye-video-border-blend`,
`videoBorderBlendActive=true`, and
`cameraProjectionPath=metadata-target-guide-texture-video-border-blend-final`.
The Breathing Room PMB scale profile extends that route with Manifold
controller-pose publishing, PMB/joystick scale-driver switching, and expected
OpenXR haptic markers for a gentle right-controller pulse while PMB mode has a
tracked grip pose.
The solid-black stimulus-volume profile is the current native GPU headroom
stress fixture for smooth central-FOV interference: it requests the
1024x1024x2 limit tier, 18 raymarch samples, `central_fov_fraction=0.72`, and
`gradient_smoothing=0.78` while preserving the 3-40 Hz randomization range,
randomized Trevor-inspired pattern vocabulary, and disabled Breathing Room
haptic/reset actions. The balanced solid-black stimulus-volume profile keeps
the same visual/safety route at 768x768x2 and 12 raymarch samples for 72 Hz
quality A/B checks. The performance solid-black stimulus-volume profile keeps
the same visual/safety route at 512x512x2 and 12 raymarch samples; the
2026-06-19 Quest 3S resolution sweep made it the first native tier with enough
headroom for 120 Hz/high-clock exploration. Its runtime marker gate also
expects the saved startup dynamics
`headset-randomize-count-28-2026-06-20`, which starts on the `spiral` family at
3.084 Hz with spatial oscillators 6.041, 35.362, and 37.531 Hz before any new
right-primary randomization. The native-passthrough
stimulus-volume fixture is the balanced 768x768x2 comparison route.
The live-hand diagnostic
bundle is captured in
`fixtures/runtime-profiles/quest-native-renderer-live-hand-visual-diagnostic.profile.json`;
it forces `compactHandInputSourceMode=live-meta-openxr-hand-tracking` and
`allowsRecordedFallback=true`, but it remains pending until screenshot evidence
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

The reusable media-stream contract has a separate source-only test gate:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "cargo test -p rusty-quest-media-stream"
```

That gate validates app-consent MediaProjection display-to-PC and lab-only
shell hidden-display fixtures, independent display-source conformance,
dual-lane and packed-SBS processor conformance, a direct-P2P route reference,
and the receiver-first revisioned runtime through terminal cleanup. It rejects
high-rate JSON, shell routes marked as production, Android socket-authority
substitution, premature source start, streaming without sink-observed frames,
and stop without cleanup. These tests do not touch a headset, ADB server,
Android encoder, MediaProjection service, or hidden Android API.

The remote-camera crate also validates the compatibility mapping into the
generic media-stream contract:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "cargo test -p rusty-quest-remote-camera"
```

Those tests keep the existing remote-camera JSON contract and runtime-profile
fixture stable while proving Camera2 as an independent conforming source and
mapping direct-P2P mono, independent dual-lane, and packed-SBS plans into the
generic runtime without changing legacy lane/route counts or taking codec and
socket authority.

The Manifold broker Android scaffold has two validation levels:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-ManifoldBrokerAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1
```

The static test verifies package naming, `/manifold/v1/events`, Manifold
command-envelope acknowledgement support, remote-camera command lifecycle
hooks, receiver port and transport route property consumption, sender bridge
markers, generic `command.media_stream.*` aliases, `media_stream_runtime`
acknowledgement payloads, generic `debug.rustyquest.media_stream.*` property
fallbacks, sender-source runtime support for Camera2/MediaCodec and diagnostic
synthetic MediaCodec sources, shared `MediaCodecSurfaceEncoder` and
`H264MediaStreamWriter` helper usage, adapter-only MediaProjection and
lab-only shell display source statuses, the Hostess Makepad safe-probe dispatch
stream and required runtime receipt stream, the high-rate JSON payload ban, and
absence of legacy Rusty-XR tokens. The build command requires an Android SDK
and JDK in the current process and writes a debug APK plus build manifest under
`target/`.
The camera-source broker APK is expected to declare `android.permission.CAMERA`,
`horizonos.permission.HEADSET_CAMERA`, and
`horizonos.permission.SPATIAL_CAMERA`; that expectation is specific to this
broker adapter and does not change the camera-free Makepad app validation lane.

The QCL-041 Wi-Fi Direct harness is the no-Android-phone live route for
Quest-to-Windows Wi-Fi Direct lifecycle evidence:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-Qcl041WifiDirectHarnessAndroid.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-Qcl041WifiDirectHarnessAndroid.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-Qcl041WifiDirectLifecycle.ps1 -Serial <quest-serial>
```

The live wrapper reserves `quest:<serial>` with Agent Board, starts the Hostess
Windows QCL-041 Wi-Fi Direct helper, installs/launches the Quest harness with
serial-scoped `adb -s`, pulls the Quest artifact, releases the lease, and writes
the final `rusty.quest.connectivity_wifi_direct_lifecycle.v1` artifact with
`released_after_live_steps=true`. Windows Mobile Hotspot must be disabled before
the run because it conflicts with Windows Wi-Fi Direct peer advertising. The
first harness expects Windows to become group owner and serve the bounded TCP
probe; if the Quest becomes group owner, the artifact fails precisely instead
of promoting topology. Promote only the final wrapper artifact through Hostess:

```powershell
python S:\Work\repos\active\rusty-hostess\tools\hostessctl\hostessctl.py connectivity-probe run --mode fixture --probe-id QCL-041 --wifi-direct-lifecycle-report <final-artifact.json> --out target\connectivity-probe\qcl041-live-wifi-direct-lifecycle.json --fail-on-error
```

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
