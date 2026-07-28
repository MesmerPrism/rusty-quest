# Spatial Layering Carrier Probe Plan

This note defines the targeted Spatial SDK carrier matrix for the room,
skybox, video/camera projection, and UI-panel layering problem. It is
public-safe: it names generic SDK carriers, wrapper controls, and evidence
fields, but not private media, model files, captures, or downstream effect
formulas.

## Problem

The Spatial Camera Panel now mixes three different composition paths:

- authored room and skybox scene geometry;
- a dynamic video plus camera projection surface;
- a normal interactive UI panel with layer buttons and depth controls.

The unresolved question is not only visual z-order. A carrier can render in
front of the room and still be wrong if it swallows controller rays, resizes
unexpectedly, hides pointer affordances, or prevents the UI buttons from
changing the active projection layer.

The desired contract is:

1. Sample room and skybox render behind the projection.
2. GLB/custom scene objects render normally in the room.
3. The custom video plus camera projection can run full-FOV or wall-mounted.
4. The UI panel remains visually in front of the projection.
5. Controller rays target the UI rather than the projection surface.
6. UI layer buttons change the active projection layer.
7. Right primary toggles the UI panel open and closed.
8. Default joystick behavior does not teleport or resize the UI panel unless
   that behavior is explicitly enabled.

## Sample Inventory

| Sample | Relevant pattern | Use in this lane |
| --- | --- | --- |
| `StarterSample` | GLXF scene load, `mesh://skybox`, Compose panel, baseline `VRFeature` and `ComposeFeature` registration | Baseline room/skybox/panel setup. It also matches the sample skybox shape that is a negative direct-quad case in current evidence. |
| `FeatureDevSample` | Multi-module `:app`, `:nativefeature`, and `:kotlinfeature`; app registers `NativeBobbingFeature()` and `PulsingFeature()` beside `VRFeature` and `ComposeFeature`; pure Kotlin and JNI-backed `SpatialFeature` modules register their own components/systems | Default modularity model for future Spatial lane work. New room, projection carrier, controller input, and panel-placement capabilities should be feature/module-shaped when they grow beyond a narrow facade method. |
| `MediaPlayerSample` | `VideoSurfacePanelRegistration`, `MediaPanelSettings`, `MediaPanelRenderOptions(zIndex = -1)`, ExoPlayer direct-to-surface | Reference for high-performance media surfaces and explicit media z-order. |
| `SpatialVideoSample` | Manual `PanelSceneObject`, custom mesh/material, `getSurface()`, `PanelInputOptions`, manual ISDK panel dimensions/grab setup | Reference when normal media panel registration is not enough and the carrier needs scene-object style control. |
| `PremiumMediaSample` | Direct `VideoSurfacePanelRegistration`, `ReadableVideoSurfacePanelRegistration`, readable media settings, `PanelInputOptions`, `PanelSceneObject.getLayer()` alpha handling, control panels around media | Reference for choosing direct surface versus readable surface and for managing media/control panel interactions. |
| `UISetSample` | Central panel registry and many Compose panel registrations | Reference for keeping UI panels registered and routed predictably. |
| `Object3DSample` / `Object3DSampleIsdk` | GLB scene objects, `Grabbable`, Interaction SDK hover/grab/select, resizeable panels | Reference for normal scene-object behavior and for the GLB control object in the matrix. |
| `HybridSample` / `CustomComponentsSample` | GLXF environment, sample `mesh://skybox`, custom components/systems | Reference for environment setup and componentized scene behavior. |

The local sample set does not appear to use `SceneQuadLayer`; that carrier is
therefore a repo-owned diagnostic path, not a sample-derived pattern.

## Carrier Inventory

This inventory is based on the local Spatial SDK digest, the panel-focused
`spatialsdkpanels.txt` research brief, and the local Meta Spatial SDK sample
checkout. It is an implementation filter, not a commitment to add all carriers.

| API / family | Sample or doc source | Surface or dynamic frames | Likely composition path | Input / hit-test behavior | Custom camera-stack fit |
| --- | --- | --- | --- | --- | --- |
| Hidden/readable producer panel -> `SceneTexture` -> `SceneMaterial` scene quad | `spatialsdkpanels.txt`; `MediaPlayerSample` panel texture reuse; `PremiumMediaSample` readable media path; Meta custom shader docs | Yes via a readable panel/media producer, then texture reuse through `SceneTexture` on a normal scene material. No direct Android `Surface` -> `SceneTexture` route is assumed. | Visible projection is normal scene geometry; the producer panel should be hidden/offscreen/noninteractive if the SDK keeps its texture updating. | Visible projection mesh can be no-collision/non-hittable; producer must be tested for ray interception when hidden, alpha-zero, tiny, or `layerConfig = null`. | Highest-confidence long-term target. It directly targets the desired room/skybox behavior: video/camera pixels appear on a world-space scene surface while the UI panel owns controls. Needs headset validation before implementation. |
| `VideoSurfacePanelRegistration` | `spatialsdk.txt` sections G and J; `MediaPlayerSample/.../MediaPlayerSampleActivity.kt`; `PremiumMediaSample/.../ExoVideoEntity.kt` | Yes. The registration exposes an Android `Surface` to a consumer; samples feed ExoPlayer directly. | Panel-composited media surface with `MediaPanelSettings` and `MediaPanelRenderOptions`; z-order can be influenced by `zIndex`. | Panel input is normal unless configured otherwise. For this lane, the projection panel must remain non-hittable / input-pass-through so UI panels win rays. | Already implemented as `video-surface-panel-scene-object`. It is the current lowest-risk product carrier because the synthetic target survived room plus sample skybox evidence. |
| `ReadableVideoSurfacePanelRegistration` | `spatialsdk.txt` section H; `PremiumMediaSample/.../ExoVideoEntity.kt` | Yes. It accepts a video `Surface` and also supports readable panel image use for custom shaders. | Panel-composited readable media surface; comments call it less performant than direct media panels. | Same panel-input caveat as direct video; readable use may add shader/lighting interactions. | Plausible only if a later shader/readback/reflection need is proven. Do not replace the direct surface panel just for layering. |
| `MediaPanelSettings` / `MediaPanelRenderOptions(zIndex)` | `spatialsdk.txt` section G; `MediaPlayerSample` uses `zIndex = -1`; `PremiumMediaSample` varies z-index by shape. | Settings only; pairs with media panel registrations or manual panel objects. | Panel layer ordering hint, not a separate carrier. | Does not by itself define hit-test behavior; must pair with `PanelInputOptions` / entity components. | Useful tuning axis for existing video-panel carrier. Low-risk after the custom skybox matrix if z-index A/B is needed. |
| Manual `PanelSceneObject` plus `sceneMeshCreator` and `getSurface()` | `spatialsdk.txt` section I; `SpatialVideoSample/.../SpatialVideoSampleActivity.kt` around manual media panel construction and `panelSceneObject.getSurface()` | Yes. The sample manually constructs a `PanelSceneObject`, obtains `getSurface()`, and sends ExoPlayer frames into it. | Panel scene object with custom `sceneMeshCreator`, mesh/material control, and explicit `SceneObjectSystem` registration. | The sample adds input listeners, `Hittable()`, and ISDK grab dimensions. This lane's first test should omit those and make the projection intentionally non-interactive so UI panels own input. | Smallest intermediate diagnostic carrier. It keeps the supported media `Surface` path while letting this repo define mesh/material shape directly, but it may still behave like a panel. Test as `manual-panel-scene-object-custom-mesh`, not as a broad rewrite. |
| `ActivityPanelRegistration` / `IntentPanelRegistration` | `spatialsdk.txt` sections E/F; `MediaPlayerSample`, `PremiumMediaSample`, `SpatialVideoSample` | Not a natural high-rate video surface source; they host Android Activity UI panels. | Panel-composited Android Activity content. | Designed for interactive panels, so hit-test focus is expected rather than pass-through. | Poor fit for the custom camera stack. Keep for controls/selector panels, not projection video. |
| Android `View` / `SurfaceView` / `TextureView` inside panels | `spatialsdk.txt` Activity/View panel notes; `meta_spatial_scanner` Android `SurfaceView` previews | Can accept dynamic frames inside an Android UI view, but then frames travel through the Android view/panel path. | Activity/View panel composition, not normal room scene geometry. | Interactive Android view focus and panel hit-testing are expected. | Possible diagnostic preview route, but not the preferred full-FOV camera-stack carrier. It risks UI/input coupling and extra copies/composition. |
| Panel texture projected into scene material | `MediaPlayerSample/.../MediaPlayerSampleActivity.kt` uses a panel texture from a scene object and assigns it to room material texture channels. | Indirect. It reuses panel output texture as material input rather than handing a raw producer `Surface` directly to scene geometry. | Scene-material projection route once the panel texture exists. | Normal scene-object hit testing depends on the target mesh/entity, not the source panel. | This is the concrete sample pattern behind the highest-confidence route above. Test with a simple synthetic quad before private media. |
| `forceSceneTexture = true` / `LayerConfig` panel mode | `Showcases/geo_voyage/.../MainActivity.kt` uses forced scene texture with layer config and hole-punch shader. | It forces panel content into a scene texture path. | Potentially changes panel participation in scene rendering, but still panel-config driven. | UI panel input remains a concern; should be tested as a toggle, not a carrier rewrite. | Useful control if panel-vs-scene composition remains ambiguous. Keep separate from the manual carrier. |
| `SceneTexture` + `SceneMaterial` + `SceneMesh.singleSidedQuad` | `Showcases/media_view/.../MediaModelExtension.kt` wraps a `SceneTexture` in a material and renders it on a single-sided quad. | Dynamic if the `SceneTexture` is sourced from panel/media content. | Scene mesh/material surface. | Normal scene object behavior; hit-test depends on added components. | Good shape for the panel-texture-to-scene-material route after a texture-source proof. |
| UI panel `QuadLayerConfig(zIndex = 99)` | `Showcases/media_view/.../PanelManager.kt` uses a high-z-index quad layer for an immersive menu. | UI panel route, not a video producer. | High-z-index quad layer control surface. | Intentionally interactive UI; should own input above projection surfaces. | Useful as a separate UI-above-projection control, not a main projection carrier. |
| Readable video panel as texture source | `PremiumMediaSample/.../ExoVideoEntity.kt` `ReadableVideoSurfacePanelRegistration` | Yes, and readable panel images can drive shaders/reflections. | Panel-composited readable media surface feeding shader/material use. | Same panel-input caveat as other media panels unless the source is hidden/non-interactive. | Pair with panel-texture-to-scene-material only after direct/manual panel evidence shows a texture-source need. |

## Current Evidence

Confirmed:

- The direct `SceneQuadLayer` path remains useful in no-room/no-skybox style
  isolation.
- The original sample-style `mesh://skybox` path can hide or outrank the direct
  `SceneQuadLayer` projection even without the authored room.
- Anchoring the direct `SceneQuadLayer` to a generated room object did not make
  it behave like normal authored room geometry.
- `VideoSurfacePanelRegistration` / `PanelSceneObject` style behavior has
  foregrounded the projection in front of the authored room.
- The custom backgrounded runtime skybox path is distinct from the original
  sample `mesh://skybox` path and must be tested separately.
- Making the projection carrier input-transparent with
  `Hittable(MeshCollision.NoCollision)` is the right class of fix for UI ray
  targeting; moving or shrinking the projection to clear input caused worse
  behavior.
- `manual-panel-scene-object-custom-mesh` is now implemented as a focused
  diagnostic carrier. It manually constructs a `PanelSceneObject`, supplies a
  `sceneMeshCreator` using `SceneMesh.singleSidedQuad`, obtains
  `panelSceneObject.getSurface()`, and registers through `SceneObjectSystem`.
  It deliberately omits `Hittable()`, ISDK grab handles, and ISDK grabbable
  setup; the static and smoke checks require those noninteractive markers.
- The `20260630-025426-layering-matrix` focused `manual-carrier` headset run
  passed all five synthetic rows with strict foreground/PID/focus validation:
  no room/no skybox, sample skybox only, custom skybox only, room+sample skybox,
  and room+custom skybox. This proves the manual custom-mesh panel carrier is
  visible in the current problem cases; it does not by itself prove pointer ray
  transparency or promote the carrier above the planned readable-producer
  scene-material route.
- The actual, non-synthetic manual-carrier app launches
  `20260630-101615-manual-actual-room-sample-skybox` and
  `20260630-105118-manual-actual-room-sample-skybox-relaunch` passed machine
  evidence for room, sample skybox, staged GLB, rendered video projection, and
  non-hittable/non-grabbable carrier markers. Human headset inspection still
  found that the UI panel did not render in front of the custom projection
  panel. Therefore the manual carrier is not accepted as the final
  UI-over-projection solution.
- The targeted `20260630-110818-ui-layer-front-manual-actual` run showed that
  UI `PanelRenderMode.Layer()` plus `zIndex = 99` is not sufficient by itself
  when the private UI remains at `0.72m` and the room foreground projection
  target is at `0.25m`.
- The targeted
  `20260630-111828-ui-foreground-force-scene-texture-manual-actual` run showed
  a successful visual UI-over-projection composition when the private UI uses
  `PanelRenderMode.Layer()`, `zIndex = 99`, foreground distance `0.22m`, and
  scale `0.1986`, while the manual custom-mesh projection uses
  `forceSceneTexture = true`, `enableLayer = false`, and `layerConfig = null`.
  This promotes the manual carrier plus foreground UI controls to the next
  input-test candidate, but it is not accepted until controller ray targeting
  and layer-button behavior are verified.
- The targeted `20260630-114013-ui-behind-force-scene-texture-ab` run kept
  `forceSceneTexture = true` and UI `zIndex = 99`, but moved the UI back to
  `0.72m` behind the full-FOV projection plane at `0.25m`. The UI command
  opened the panel and markers confirmed
  `panelDistanceLessThanCameraProjection=false`, but the screenshot did not
  show the UI. This proves the successful visual ordering depends on the UI
  being physically in front of the projection, not on layer z-index alone.

Hypotheses:

- `manual-panel-scene-object-custom-mesh` remains a low-risk intermediate
  diagnostic after the custom-skybox matrix because it keeps a supported
  `Surface` producer path while moving mesh/material shape under repo control.
  Its initial actual full-app UI ordering failed, but the later foreground
  UI plus `forceSceneTexture = true` control row produced a visual ordering
  success. Keep it as the current input-test candidate, not as a final carrier.
- A hidden/readable producer panel feeding a `SceneTexture` into a normal
  `SceneMaterial` scene quad is now the best long-term world-space target. It
  should be implemented only after the custom-skybox matrix because it has more
  unknowns than the existing carrier rows.
- `forceSceneTexture = true`, UI `QuadLayerConfig(zIndex = 99)`, and readable
  video panel variants are useful small toggles/control rows, not broad rewrites.
  The behind-projection A/B shows `forceSceneTexture = true` plus high UI
  z-index does not by itself solve compositor ordering.

## Implemented Matrix Wrapper

Use:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialCameraPanelAndroidLayeringMatrix.ps1 `
  -Serial <quest-serial> `
  -MatrixPreset implemented-carriers `
  -UsePrivateInputsManifest `
  -RequireSpatialVideoProjection `
  -IncludeAssetModel `
  -ClearLogcat `
  -SkipInstallAfterFirstRun `
  -SkipPermissionPregrantAfterFirstRun
```

Before running it on a headset, reserve the target Quest through Agent Board.
The wrapper performs serial-scoped ADB install/launch/logcat/screenshot work by
delegating to `Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1`.

The wrapper currently exercises only carriers already implemented in
`rusty-quest`:

| Case | Carrier | Environment | Purpose |
| --- | --- | --- | --- |
| `scenequadlayer-no-room-no-skybox` | `scenequadlayer-room-object` | no room, no skybox | Direct carrier isolation baseline. |
| `scenequadlayer-sample-skybox-only` | `scenequadlayer-room-object` | sample `mesh://skybox` only | Reproduce the sample skybox-only negative case. |
| `scenequadlayer-custom-skybox-only` | `scenequadlayer-room-object` | custom backgrounded skybox only | Distinguish the custom runtime skydome from the sample `mesh://skybox` carrier. |
| `scenequadlayer-room-sample-skybox` | `scenequadlayer-room-object` | sample room plus sample skybox | Reproduce the rejected direct room comparison. |
| `scenequadlayer-room-custom-skybox` | `scenequadlayer-room-object` | sample room plus custom backgrounded skybox | Check whether the custom skybox fixes only skybox ordering or also affects authored room composition. |
| `video-panel-room-sample-skybox` | `video-surface-panel-scene-object` | sample room plus sample skybox | Check the current foreground-capable product carrier against the sample skybox. |
| `video-panel-room-custom-skybox` | `video-surface-panel-scene-object` | sample room plus custom backgrounded skybox | Check the current foreground-capable product carrier against the custom skybox. |

Use `-MatrixPreset full-implemented` when the room-only variants are useful.
Use `-MatrixPreset minimal` when a quick A/B between the rejected direct carrier
and the accepted panel carrier is enough.

The aggregate output is:

```text
local-artifacts/spatial-camera-panel-headset/<timestamp>-layering-matrix/layering-matrix-summary.json
```

Each case keeps its normal smoke `evidence-summary.json`, PID/tag logcat, and
screenshot under a case subdirectory.

## Acceptance Rules

Machine evidence should record:

- carrier token and detected carrier;
- room and skybox markers, including `spatial_skybox_mode`;
- render-order token;
- strict foreground proof: expected package/activity, live PID, and focused or
  resumed immersive client;
- synthetic target pixel classification, with screenshots invalid when the
  target is not visible;
- video/camera projection rendered markers;
- GLB/object entity creation markers when `-IncludeAssetModel` is used;
- projection `NoCollision` / input pass-through markers for panel carriers;
- right primary UI open/close markers from a focused UI-action pass;
- layer button selected, override submitted, native updated, and forced refresh
  markers from a focused UI-action pass.

Human headset acceptance is still required for:

- projection actually visible in front of the room;
- UI visually in front of the projection;
- pointer/controller affordances visible and usable;
- layer button presses visibly changing the active projection output;
- no unexpected joystick panel repositioning.

## Next Runtime Slice

Do not add another broad Activity pass. The current matrix uses a synthetic
checkerboard/color projection mode so screenshots can be classified by simple
color regions before private media/effects are used. The manual-carrier
visibility matrix has now been followed by an actual private-profile launch,
and that launch failed human UI foreground inspection. The next slice should
target UI-over-projection specifically rather than repeating the same
visibility proof.

The strongest world-surface candidate remains:

- `readable-producer-scene-material-quad`: a hidden or noninteractive readable
  media producer supplies the dynamic panel texture, and the visible projection
  is a normal scene quad using `SceneMaterial` texture input. This path must
  test producer visibility states and the readable-media mesh-mode workaround
  before it can be treated as reliable. The producer settings should include
  `PanelInputOptions(clickButtons = 0)` for the first input-suppression test,
  but that must be treated as click suppression only until headset ray evidence
  proves it does not intercept pointer targeting.

The implemented intermediate diagnostic carrier is:

- `manual-panel-scene-object-custom-mesh`: a manual `PanelSceneObject` with
  custom `sceneMeshCreator`, `getSurface()`, no initial `Hittable()`/ISDK grab
  setup, and the same synthetic checkerboard surface producer used by the
  current strict matrix. The focused matrix preset is `manual-carrier`, with
  no-room/no-skybox, sample-skybox-only, custom-skybox-only, room+sample-skybox,
  and room+custom-skybox rows. It is a visibility/composition diagnostic, not
  an accepted full-app UI layering solution.

Make these the immediate UI-order control rows:

- `forceSceneTexture = true` / `LayerConfig` panel mode;
- UI panel `QuadLayerConfig(zIndex = 99)` as a control for UI-above-projection;

Keep these as follow-up carrier controls:

- readable media panel as a texture/shader source;
- real mesh/object dynamic texture carrier, only after a supported
  surface/material binding path is proven.

When testing readable mesh mode, include Meta's known-issue workaround in the
implementation and evidence:

```kotlin
val panelConfig = readableSettings.toPanelConfigOptions()
panelConfig.layerConfig = null
```

The acceptance question is not just "does it render?" The run must prove that
the producer panel keeps updating the texture while hidden/offscreen/tiny or
alpha-zero, does not intercept pointer rays, and does not leave a compositor
layer that changes the intended scene-geometry ordering.

## Modularity Rule

Use `FeatureDevSample` as the default pattern for new Spatial lane capabilities
or refactors. When a capability grows beyond a small orchestration method,
shape it as a reusable Spatial feature/module with its own component/system
registration and, when needed, native loading boundary. The Activity should
register and orchestrate features; it should not keep absorbing room loading,
projection carriers, panel placement, controller input, and validation marker
logic into one file.

Preferred future split candidates:

- packaged virtual room and skybox feature;
- staged asset feature;
- projection carrier feature;
- private layer panel placement/input feature;
- controller shortcut/input feature;
- layering matrix marker/evidence helper.
