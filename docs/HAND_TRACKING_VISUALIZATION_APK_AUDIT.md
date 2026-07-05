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
- `hand_anchor_particles`
- `particles.hand_anchor.ordering.gpu_index_remap`

`hand_mesh_live_input` is the important split point. It enables live
Meta/OpenXR compact hand input and the resident GPU skinning substrate without
requesting the app's custom hand mesh draw. `hand_anchor_particles` then uses
those resident skinned buffers for topology particles. The app spec keeps
camera, video, display-composite, SDF, private particles, private layers,
Makepad, and the custom mesh visual denied.

The accepted target to return to is:

- render mode `solid-black-openxr-hands-anchor-particles`
- runtime/default OpenXR hand visual requested
- app custom hand mesh visual disabled
- graft copies disabled
- hand-anchor particles enabled in OpenXR reference-space meters
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

The built-in Meta avatar hand visual is controlled by
`debug.rustyquest.spatial.avatar_hands.visible`. The default is `false`, which
preserves the existing public/custom-only visual policy. Set it to `true` on a
headset when the run needs the SDK's `AvatarSystem` hand visual for comparison.

The public ECS hand billboard path remains separate and opt-in through
`debug.rustyquest.spatial.hand_billboard_flock.enabled=true`.

Use:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialHandLabAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialHandLabAndroid.ps1 -Build
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

The shader uses a base RGB color, alpha, subtle normal/depth tinting, and a
normal-facing rim approximation. It deliberately emits
`handMeshVisualTextureImported=false`, because exact wire/triangle inspection
belongs in the separate mesh/wire debug visual path.

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
