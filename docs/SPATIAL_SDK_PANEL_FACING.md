# Meta Spatial SDK UI Panel Facing

Meta Spatial SDK UI panels are one-sided presentation surfaces. Creating the
entity, registering its Compose content, and observing a visible Android
presentation surface do not prove that the panel's front face points toward the
viewer. A back-facing panel commonly appears as an all-black or missing panel.
The same symptom can come from an unrelated opaque panel layer that remains
registered above the intended one, so pose and registration isolation are
separate acceptance conditions.

## Rusty Quest convention

Viewer-relative UI panels use the vector from the viewer to the intended panel
position and Meta's upright panel rotation:

```kotlin
val viewerToPanel = panelPosition - viewerPosition
val rotation = Quaternion.lookRotationAroundY(viewerToPanel)
```

The shared app-local authority is `SpatialPanelFacing`. UI panels call that
helper; scene meshes, custom-material quads, camera carriers, and quad layers do
not. Those surfaces have separate winding, culling, UV, and stereo-orientation
contracts. Do not repair a UI-panel facing problem by globally rotating scene
geometry.

When no viewer pose is available during initial scene construction, use a
known-facing static pose for the app's coordinate convention. The Spatial
Camera Panel fallback uses `Quaternion(0f, 180f, 0f)` and replaces it with the
viewer-relative convention whenever a valid viewer pose is available. Do not
use a zero or generic direction quaternion as an unverified UI-panel fallback.

`Quaternion.fromDirection(viewerForward, viewerUp)` is not the Rusty Quest
UI-panel-facing authority. It aligns a generic transform direction, but it does
not document which local face of a one-sided panel is visible. A read-only audit
found this pattern in multiple local Spatial SDK applications, which is why the
repository has a focused regression gate instead of an app-specific 180-degree
patch.

## Official Meta reference

The convention is derived from the official MIT-licensed
`Meta-Spatial-SDK-Samples` repository at commit
`fb7c4f27a45ec22922d689cb76053b92f0f10f18`:

- `Showcases/media_view/.../LookAtHeadSystem.kt` positions a panel with the
  head-local forward offset and applies
  `Quaternion.lookRotationAroundY(forward)`.
- `Showcases/meta_spatial_scanner/.../TipManager.kt` computes
  `position - headPosition` and applies
  `Quaternion.lookRotationAroundY(...)`.
- `Showcases/meta_spatial_scanner/.../LiveStreamingActivity.kt` demonstrates a
  static panel with `Quaternion(0f, 180f, 0f)`.

The pinned samples are reference evidence, not runtime dependencies. The local
helper owns the Rusty Quest convention and marker vocabulary.

## Required observability

Panel creation markers must include the effective facing convention and whether
the pose came from the viewer or a static fallback. These markers prove the
route that ran; they still do not prove visibility. Device acceptance requires
an operator to confirm that the readable front face is visible from the launch
position.

An exclusive panel feature must also register only the panels it can present.
`Visible(false)` on an entity is not a substitute for omitting an unrelated
opaque `PanelRenderMode.Layer` registration: the Android presentation and
compositor-layer lifecycle are separate from ECS visibility. Emit the active
registration set and use a dedicated `UIPanelSettings` contract for panels that
do not share layer-order policy.

Layer z-index and blend selection are not substitutes for an attended
foreground contract. The repository's first attended A/B kept the same
full-field custom projection and high-z UI layer: a panel at 0.72 m remained
hidden behind a 0.25 m projection, while a panel at 0.22 m rendered in front.
A later controller-attended VR Strobe run rejected that 0.22 m route because
the panel was uncomfortably close and no longer accepted trigger clicks.
Moving and proportionally scaling a panel is therefore valid only after both
comfort and controller-ray interaction pass on the headset. If either fails,
retain the comfortable panel pose and suppress the competing visual carrier
while the panel is visible, using both the native scene-object and ECS
visibility routes without pausing or stopping the underlying output state.
`PanelRenderMode.Layer()` defaults to `MASKED`; an opaque control surface may
select `PanelShapeLayerBlendType.OPAQUE`, but that choice is supplemental and
must not be claimed as foreground proof. Full-field scene materials should use
normal depth testing and depth writes unless a separately validated contract
requires otherwise.

ECS visibility is likewise not an input-lifecycle boundary. A hidden panel's
backing Compose view or native scene object can retain focus or hit state and
activate a control from controller keys. Features with global controller
shortcuts must exclude those buttons from panel input, consume the
corresponding Android key at Compose preview, block descendant focus where
appropriate, disable the backing root view, and hide the native panel scene
object while the entity is hidden. A reserved global shortcut must not have a
conflicting panel action such as Pause/Resume available to invoke.

Keep these claims separate:

- `panel registered`: Compose content has a registration.
- `panel entity created`: the ECS entity and components exist.
- `presentation surface shown`: Android reports the backing surface.
- `front-facing visible`: a headset observer can read the panel.

Only the last claim closes a black-panel facing regression.

## Black-panel diagnosis

Use this order for a Spatial SDK panel that is black or absent:

1. Stop other immersive or Spatial SDK applications on the selected test
   headset so another presentation surface cannot confuse the observation.
2. Confirm the intended app is foreground and the expected panel registration
   and entity-created markers exist.
3. Confirm the active registration set contains only the panels needed by the
   feature. Remove hidden opaque layer panels and unrelated video-surface
   registrations from an exclusive diagnostic route.
4. Confirm the panel is `Visible(true)`, has non-zero dimensions, and is not
   deliberately hidden by feature or safety state.
5. Read the effective pose source and facing-convention marker. Treat a missing
   or generic-direction convention as suspect.
6. Verify the viewer-to-panel direction passed to
   `Quaternion.lookRotationAroundY`; do not reverse it to panel-to-viewer.
7. Compare the panel's viewer distance with the nearest competing full-field
   surface. If a nearer panel is tested, require explicit comfort and
   controller-ray acceptance. If either fails, return to the comfortable pose
   and suppress the competing carrier through both native scene-object and ECS
   visibility while the panel is open. Keep the output lifecycle unchanged and
   make the scene material depth-respecting where possible.
8. Relaunch from a clean package stop and obtain an explicit headset visibility
   verdict. A screenshot, log marker, or Android surface dump alone is not
   sufficient.

For a temporal or otherwise safety-sensitive app, keep visual output inert
through this diagnosis. First prove the readable control panel, then request
separate operator authorization for output.

## Validation

Run the focused guard and the owning app suite:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-SpatialSdkPanelFacingStatic.ps1 -RepoRoot .
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1
```

The focused gate requires the shared `lookRotationAroundY` convention, the
known-facing fallback, exclusive strobe registration, dedicated compositor
layer settings with explicit z-index, recenter-on-open adoption, marker fields,
documentation routing, and the
absence of direct `Quaternion.fromDirection` placement in the protected UI
panel paths. A fresh APK plus serial-scoped headset confirmation remains
required after a panel-facing or panel-registration change.
