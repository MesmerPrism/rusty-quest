# Hand Tracking Visualization APK Audit

This audit keeps the reusable hand-tracking work in the public Rusty Quest
lane. Private mesh-dynamics apps can consume these surfaces later, but they do
not own the base Quest hand providers, joint-space mapping contracts, or APK
build authority.

## Clean APK Targets

### Native OpenXR Hand Lab

The native target is `fixtures/native-app-builds/native-openxr-hand-lab.app.json`.
It resolves to a source-only OpenXR/Vulkan APK with:

- `quest.native.openxr_vulkan_base`
- `input.controllers_and_hands_optional`
- `renderer.background.solid_black`
- `hand_mesh_live_input`
- `hand_mesh_visual`

`hand_mesh_live_input` is the important split point. It enables live
Meta/OpenXR compact hand input and the resident GPU skinning substrate without
requiring per-frame expanded mesh vertices. `hand_mesh_visual` then draws the
resident selected mesh with the procedural hand material and optional
shader-barycentric wireframe overlay. The app spec keeps
camera, video, display-composite, SDF, private particles, private layers,
Makepad, and hand-anchor particles denied.

The accepted target to return to is:

- render mode `solid-black-hands-and-grafts`
- live OpenXR compact joint input selected
- resident XR_FB hand mesh visual selected by
  `debug.rustyquest.native_renderer.hand_mesh.visual.mesh_source=openxr-fb-mesh`
- shader-barycentric wireframe enabled for exact triangle inspection
- existing graft-copy markers remain separate from source-mesh selection
- SDF field visual disabled through
  `debug.rustyquest.native_renderer.sdf.field_visual.enabled=false`
- private hand-particle payload inactive

Use:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeOpenXrHandLabAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-NativeOpenXrHandLabAndroid.ps1 -Build
```

The first command is dry-run/static validation. The `-Build` form produces the
APK from the generated feature lock after the usual Quest build resources have
been reserved.

### Spatial SDK Hand Lab

The Spatial SDK target reuses `apps/spatial-camera-panel-android` under a
separate build identity provided by `tools\Test-SpatialHandLabAndroid.ps1`.
That wrapper sets:

- package id `io.github.mesmerprism.rustyquest.spatial_hand_lab`
- app label `Rusty Quest Spatial Hand Lab`
- APK name `rusty-quest-spatial-hand-lab.apk`
- particle view as the default start view
- launcher panel hidden by default
- OpenXR/Spatial joint diagnostics enabled with the headset-accepted
  `mirror-x-origin-registration` mapping
- viewer/headset marker spheres disabled while joint and hand-anchor markers
  remain visible
- ECS particle hands enabled with source `openxr-live-custom-mesh`

The built-in Meta avatar hand visual is controlled by
`debug.rustyquest.spatial.avatar_hands.visible`. The default is `false`, which
preserves the existing public/custom-only visual policy. Set it to `true` on a
headset when the run needs the SDK's `AvatarSystem` hand visual for comparison.

The public ECS hand billboard path remains opt-in in the general camera-panel
build and is enabled by default only in the dedicated hand-lab variant. Set
`debug.rustyquest.spatial.hand_billboard_flock.source=openxr-live-custom-mesh`
to drive the app-owned recorded rig from the validated mapped OpenXR rows. The
renderer performs CPU linear-blend skinning, then resolves stable surface
positions from triangle indices plus barycentric coordinates. It deliberately
does not apply the older `flip-x + local-y`, final world mirror, orientation
half-turn, or `AvatarBody` world-anchor correction. The rollback proxy remains
`spatial-sdk-anchor-flock`.

The rig implementation is public, but the recorded rig pack is supplied as an
explicit build input and is not committed to Rusty Quest:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialHandLabAndroid.ps1 `
  -Build `
  -HandMeshRigAssetDir <asset-root-containing-spatial-ecs-replay>
```

The runtime must report `liveCpuSkinnedMesh=true`,
`surfaceAnchors=triangle-barycentric`, `rowOrder=openxr-left-right`,
`meshPairing=asset-handedness`, `orientationCorrection=none`, and
`worldAnchorCorrection=false`. If the asset pack is absent, the particle
source stays hidden and reports `fallback=joint-visuals-only`; active joint
markers provide the intentional diagnostic fallback.

Its app-owned `TriangleMesh` carrier can render filled billboard quads or
wireframe edge quads:

- `debug.rustyquest.spatial.hand_billboard_flock.visual_mode=filled-billboards`
- `debug.rustyquest.spatial.hand_billboard_flock.visual_mode=wireframe-edges`
- `debug.rustyquest.spatial.hand_billboard_flock.wireframe.source=spatial-sdk-joint-proxy`
- `debug.rustyquest.spatial.hand_billboard_flock.wireframe.width_m=0.0035`

This Spatial wireframe mode applies only to Rusty Quest app-owned
`TriangleMesh` geometry. The built-in `AvatarSystem` hand mesh remains
SDK-owned; runtime markers report `spatialAvatarHandMeshWireframeSupported=false`
so the comparison path cannot be mistaken for custom hand topology access.
The edge-quad mode outlines each particle billboard. It is not the topology
wireframe of either the SDK-owned `AvatarSystem` hand or the recorded custom
mesh; those exact mesh-wireframe paths remain separate visual features.

For the Spatial SDK hand-mesh investigation APK, also enable the read-only
Avatar hand probe:

- `debug.rustyquest.spatial.avatar_hand_probe.enabled=true`
- `debug.rustyquest.spatial.avatar_hand_probe.sample_period_frames=30`
- `debug.rustyquest.spatial.avatar_hand_probe.detail_limit=16`
- `debug.rustyquest.spatial.hand_billboard_flock.wireframe.source=avatar-system-public-mesh-probe`

The probe emits `channel=spatial-avatar-hand-investigation` markers with
`sdkBuiltInHandMeshPubliclyObserved`, `handCandidateMeshCount`,
`meshExtractionStatus`, and `skinningExtractionStatus`. The expected 0.13.1
public-API result is transform/material metadata only, not vertex/index
readback; exact wireframe still comes from app-owned `TriangleMesh` proxy
geometry unless a public topology path is observed at runtime.

Use:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialHandLabAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialHandLabAndroid.ps1 -Build -HandMeshRigAssetDir <asset-root-containing-spatial-ecs-replay>
adb shell setprop debug.rustyquest.spatial.avatar_hands.visible true
```

The property command is runtime setup for a comparison launch, not build-time
authority.

## Unity Hand Material Reference

Meta's Unity hand prefab path is renderer-owned: `OVRHandPrefab` drives an
`OVRSkeleton`, `OVRMesh`, and a `SkinnedMeshRenderer` material list. The local
reference material inspected for this audit was `BasicHandMaterial`. Its
portable settings are simple: opaque surface, white base color, metallic `0`,
smoothness `0.5`, z-write enabled, and a hand UV-map texture. The texture is a
black UV/triangle wire reference for the hand mesh layout, not a skin albedo
that should be imported into native or Spatial SDK apps.

The native OpenXR/Vulkan equivalent is therefore a procedural material profile,
not a Unity asset copy. `hand_mesh_visual` now selects
`handMeshVisualMaterialProfile=unity-basic-reference` by default and exposes
these startup properties:

- `debug.rustyquest.native_renderer.hand_mesh.visual.material.profile`
- `debug.rustyquest.native_renderer.hand_mesh.visual.material.alpha`
- `debug.rustyquest.native_renderer.hand_mesh.visual.material.base_color.r`
- `debug.rustyquest.native_renderer.hand_mesh.visual.material.base_color.g`
- `debug.rustyquest.native_renderer.hand_mesh.visual.material.base_color.b`
- `debug.rustyquest.native_renderer.hand_mesh.visual.material.rim_strength`
- `debug.rustyquest.native_renderer.hand_mesh.visual.mesh_source`
- `debug.rustyquest.native_renderer.hand_mesh.visual.wireframe.enabled`
- `debug.rustyquest.native_renderer.hand_mesh.visual.wireframe.width_px`

The shader uses a base RGB color, alpha, subtle normal/depth tinting, and a
normal-facing rim approximation. When wireframe is enabled, the same triangle
draw emits shader-barycentric edge lines over the resident selected topology.
`hand_mesh.visual.mesh_source=auto` accepts whichever topology is packaged,
`openxr-fb-mesh` requires an XR_FB hand mesh topology, and `custom-mesh`
requires a non-XR_FB custom topology. The frame markers report both the
requested and resolved source through `handMeshVisualMeshSourceSelection` and
`handMeshVisualResolvedMeshSource`. Wireframe enabled/width and mesh source are
runtime-polled Android properties, so they can be changed with `adb setprop`
during a running session. The material deliberately emits
`handMeshVisualTextureImported=false`; the Unity UV texture remains only a
reference, not a runtime asset.

## Recording And Replay Modes

The hand substrate now keeps two recorded replay modes visible instead of
treating all captures as one artifact:

- `recorded-mesh-validation-frames`: replay already-skinned animated mesh rows
  from `left/right.validation_mesh.jsonl`. This is useful as a visual/reference
  capture of what the provider produced.
- `recorded-joints-skin-live`: replay compact runtime joint rows from
  `left/right.clip.jsonl`, then skin the rig through the same resident CPU/GPU
  shape used by live OpenXR hands.

The second mode is the autonomy target for custom hand meshes. A headset run
can record live OpenXR compact joint clips, pull them from app-scoped external
storage, inspect them locally, and later combine them with an existing
`left/right.rig.json` plus optional `validation_mesh_jsonl` frames for a full
`recorded_hand_replay_source.v1` bundle.

The live joint recorder is controlled by a low-rate app-scoped external control
file, not by high-rate Android properties:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererHandJointCapture.ps1 `
  -Serial <quest-serial> `
  -Action Prepare `
  -MaterialProfile unity-basic-reference `
  -VisualMeshSource openxr-fb-mesh `
  -Wireframe `
  -DisableSdfVisual

# Launch or relaunch the native hand lab after Prepare so startup material
# properties are consumed by NativeActivity.

powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererHandJointCapture.ps1 `
  -Serial <quest-serial> `
  -Action Start `
  -SessionId hand-joints-test-001 `
  -MaxFrames 900

powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererHandJointCapture.ps1 `
  -Serial <quest-serial> `
  -Action Stop

powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererHandJointCapture.ps1 `
  -Serial <quest-serial> `
  -Action PullAndInspect `
  -SessionId hand-joints-test-001
```

Pulled captures land under `target\native-renderer-hand-joint-captures` and
contain `capture.manifest.json`, `left.clip.jsonl`, `right.clip.jsonl`, and
`hand-joint-capture-inspection.json`. Runtime logcat should show
`channel=hand-joint-capture`, `replayMode=recorded-joints-skin-live`,
`leftFrames`, `rightFrames`, and the active
`handMeshVisualMaterialProfile`.

Spatial SDK's built-in `AvatarSystem` hand visual remains SDK-owned. The public
toggle can show or hide those hands, but there is no supported public
`AvatarSystem` material surface equivalent to Unity's `SkinnedMeshRenderer`
material list. For Spatial SDK comparisons, use the built-in hands as the Meta
reference visual and keep custom material work on separate Rusty Quest Spatial
ECS renderers.

## Space And Hand Mapping

### Native OpenXR

The native renderer treats OpenXR reference-space meters as the authority for
live hand particles and resident skinned hand buffers. The clean hand-lab APK
selects live Meta/OpenXR compact hand input with
`hand_mesh.input.source=live-meta-openxr-hand-tracking`. The native path is
compatible with the Meta sample pattern around `XR_EXT_hand_tracking` and the
Meta mesh/capsule extensions, while keeping the app's custom mesh draw as a
separate selectable visual.

### Spatial SDK

Spatial SDK hands are not a drop-in replacement for the custom OpenXR hand
mesh. Spatial SDK exposes controller/avatar entities and transforms in the
Spatial world, while `AvatarSystem` owns the built-in hand/controller
visibility policy. Rusty Quest therefore keeps three separate Spatial hand
paths:

- built-in `AvatarSystem` hands, toggled by
  `debug.rustyquest.spatial.avatar_hands.visible`
- public ECS billboard flock, sourced from Spatial hand anchors
- native receipt hand-joint mapping for panel-relative particle diagnostics

The accepted native-receipt mapping target remains
`viewer-relative-openxr-to-spatial-sdk-panel-basis`. Its default calibration is:

- scene offset x/y/z meters: `0.000`, `0.000`, `2.000`
- yaw degrees: `180.000`
- horizontal sign: `-1.000`

The runtime properties for that target are:

- `debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_x_m`
- `debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_y_m`
- `debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_z_m`
- `debug.rustyquest.spatial_camera_panel.live_hand_scene.yaw_degrees`
- `debug.rustyquest.spatial_camera_panel.live_hand_scene.horizontal_sign`

## Validation Notes

Static validation can prove that the feature graph, manifest surface, property
defaults, and mapping vocabulary are clean. It cannot prove whether live
headset hand poses line up visually between the runtime/default hand visual,
the resident OpenXR mesh basis, and Spatial SDK anchor transforms.

The headset acceptance run needs:

- OS hand tracking enabled
- declared hand-tracking permission granted before first launch
- real hands visible in the headset
- deliberate left/right hand movement, wrist roll, finger spread, and crossing
  motions
- headset translation and yaw movement to catch reference-space mistakes
- log markers for selected render mode, hand input source, live-frame
  selection, hand-anchor particle counts, and Spatial avatar-hand policy

The external documentation basis is:

- Meta Native OpenXR SDK sample docs, especially `XrHandsFB` and the
  `XR_EXT_hand_tracking` / `XR_FB_hand_tracking_mesh` / capsule extension path.
- Meta Spatial SDK input docs for controller/avatar entity access.
- Meta Spatial SDK `AvatarSystem` docs for built-in hand and controller
  visibility.
