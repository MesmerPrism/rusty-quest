# Spatial Room World-Space Iteration Log

This document tracks the `Add Spatial FBX asset support` Codex thread from the
last pre-room baseline through the current room, world-space projection, staged
asset, and private-layer UI work. It is intentionally public-safe: generic
Spatial SDK support is documented here, while exact private media/model file
paths stay in ignored local manifests.

## Baseline Before Room Work

Thread: `Add Spatial FBX asset support`

Pre-room pushed state:

- `rusty-quest` commit `15e715c`: `Add Spatial depth policy controls`.
- Parallel private Morphovision repo commit `6952147`: `Add Spatial Morphovision depth compare path`.
- Static validation passed with `Test-SpatialCameraPanelAndroidStatic.ps1`.
- Both working trees were clean against `origin/main`.

Functional baseline at that point:

- Spatial Camera Panel could render video background, camera projection,
  private Morphovision shader stack, layer-control panel, projection scale,
  depth-source policy, and depth-alignment controls.
- Depth compare mode showed visually different Meta depth layers, but full
  depth-stack alignment was explicitly deferred.
- Private Morphovision effect details, local media, local model files, and
  local captures were outside public repos.

## Public/Private Boundary For This Lane

Public `rusty-quest` may own:

- Generic Spatial SDK staged GLB/GLTF runtime mesh support.
- Generic packaged virtual room loading from an app-compatible GLXF scene.
- Generic skybox/IBL setup and room/world-space panel placement markers.
- Generic camera/video projection carriers and control-panel ordering logic.
- Static/smoke/build validation wrappers and public marker vocabulary.

Private/local only:

- The particular test FBX, converted GLB, video source, headset screenshots,
  APKs, log dumps, and exact local paths.
- Private downstream Morphovision shader/profile details and effect semantics.

Current private launch inputs are stored in ignored manifests:

- `local-artifacts/spatial-camera-panel-private-inputs.json`
- `local-artifacts/Set-SpatialCameraPanelPrivateInputs.ps1`

Future agents should read those local manifests before trusting empty
environment variables or doing broad file searches.

## Timeline And Effects

### 1. Generic Staged 3D Asset Module

Built:

- `SpatialStagedAssetModule.kt`.
- `Stage-SpatialCameraPanelAsset.ps1`.
- Smoke-wrapper parameters for `-AssetMeshUri`, `-AssetSourcePath`,
  `-AssetConvertedMeshPath`, `-RequireSpatialAssetModel`, placement, rotation,
  scale, label, and grabbable state.
- Build-manifest and static-gate markers for the generic module.
- README and implementation-plan text distinguishing GLB/GLTF runtime assets
  from raw FBX source inputs.

Effects:

- Runtime can spawn a Spatial SDK `Mesh` entity from a staged GLB/GLTF URI with
  `Transform`, `Scale`, `Visible`, and optional `Grabbable`.
- Raw `.fbx` is treated as conversion-required host input. The app does not
  package raw FBX or claim the Spatial SDK can render FBX directly.
- The first module build passed. Initial headset validation was deferred until
  a GLB existed.

Important lesson:

- This belongs in public `rusty-quest` as a generic staged asset module. The
  specific model is only a local test case.

### 2. Local Conversion And Meta Tooling

Built/installed locally:

- Meta Spatial Editor 16.1 via per-user MSI extraction after `hzdb tools
  install spatial-editor` downloaded the MSI but normal install hit MSI exit
  `1602`.
- Verified `mse-agent.exe` from the extracted editor.
- Portable Blender under the local S-drive tools folder.
- `Convert-FbxToGlb.ps1`, `fbx_to_glb.py`, and `README-fbx-to-glb.md`.

Effects:

- Blender conversion produced the local private GLB under the local artifacts
  root.
- `mse-agent check-asset-info` accepted the generated GLB.
- Existing Rusty Matter hand-mesh GLB tools were confirmed useful for GLB
  inspection/extraction, but not for FBX-to-GLB conversion.

Important lesson:

- Meta Spatial Editor is useful for Spatial scene work and GLB/GLTF inspection,
  but raw FBX conversion still needed Blender in this lane.

### 3. First Packaged Virtual Room

Built:

- Optional packaged virtual room loader gated by
  `debug.rustyquest.spatial.virtual_room.enabled`.
- Generic GLXF scene URI markers such as `apk:///scenes/Composition.glxf`.
- Sample-style skybox/IBL/lighting setup when room assets are packaged.
- `spatial-sdk-packaged-virtual-room` lane boundary markers.
- Smoke-wrapper `-EnableVirtualRoom` and `-RequireSpatialVirtualRoom`.
- Right-secondary/B placement state for viewer-locked full-FOV projection
  versus a fixed virtual-wall placement.

Effects:

- The chosen sample room was an actual authored Spatial SDK scene, not MRUK,
  not passthrough-room placement, and not a procedural quad room.
- The first exported sample GLXF referenced a sample Android panel id that the
  Rusty Quest app did not own. The GLXF was sanitized so only the environment
  node remained.
- Sanitized-room APK included the room assets and no private model payload.
- First required room smoke passed and screenshot showed the simple VR room.

Important lesson:

- Sample-authored GLXF environments can be reused, but sample UI bindings must
  be removed or the composition will fail in this app.

### 4. Staged Asset Launch Transport

Built:

- Smoke wrapper switched long staged-asset configuration from Android
  `setprop` to `am start` intent extras.
- Vector extras were changed from semicolon-separated values to comma-separated
  values so the shell would not treat semicolons as command separators.

Effects:

- The staged GLB URI no longer hit Android property length limits.
- Asset model requirements could pass the wrapper and create a runtime entity.

Evidence:

- `local-artifacts/spatial-camera-panel-headset/20260629-193326-camera-hwb-projection-smoke/evidence-summary.json`
  recorded `spatial_asset_model_requested=true`,
  `spatial_asset_model_entity_created=true`, and an app-private staged mesh
  URI.

Important lesson:

- Short booleans and small scalar settings can stay in properties. Long URIs
  and staged asset details should use launch extras.

### 5. Launcher Suppression And Room UI Semantics

Built:

- Suppression of the legacy small launcher/visible-button panel on the
  camera-stack room route.
- Right primary opens only the private multi-layer control panel in that route.
- Right secondary/B toggles projection placement and does not replace the
  control panel.
- Layer-control markers say the active layer override applies in both wall and
  full-FOV placements.

Effects:

- The old yellow launcher panel still flashed in early builds, then was moved
  earlier in startup so it was hidden before room route entity creation.
- Control-panel and projection placement became separate concepts.

Important lesson:

- In room mode there are three separate surfaces that must not be conflated:
  legacy launcher/workflow panel, private layer-control panel, and
  camera/video projection carrier.

### 6. Room Load Ordering

Built:

- Deferral so staged GLB entity creation and projection startup could wait for
  `spatial-virtual-room status=loaded`.
- Markers to prove creation after room load.

Effects:

- Logs showed the GLB and projection layer being created after room load.
- Screenshot still showed only the sample room in some iterations, so creation
  order was necessary but not sufficient for accepted visual placement.

Important lesson:

- Marker-valid entity creation is not visual acceptance. Room screenshots and
  headset inspection remain necessary.

### 7. Controller Routing And B Toggle

Built/tested:

- Sample-style local controller component polling in addition to AvatarBody
  fallback.
- Less brittle handling of typed-but-inactive right-controller button state.
- Attempted native OpenXR B-button fallback.

Effects:

- Right primary/A panel open was restored through the Spatial SDK local
  controller component route.
- Native OpenXR fallback was rejected by runtime state because Spatial SDK had
  already attached OpenXR action sets; `xrAttachSessionActionSets` failed with
  the expected conflict shape.
- Synthetic Android `KEYCODE_BUTTON_B` did not prove real Quest controller B.

Important lesson:

- For this Spatial SDK app, controller input should follow the SDK controller
  component / Interaction SDK pointer path. Do not assume ADB key events or a
  second native OpenXR action set prove controller behavior.

### 8. Initial Room Projection Regressions

Observed:

- First room implementation showed the custom projection surface at least
  visible: behind the room window/geometry but in front of the skybox.
- Later iterations regressed to room-only screenshots, no visible projection,
  and at times missing controller/panel behavior.
- A tiny floating object was likely the staged GLB at the original small scale.

Built/tested:

- Larger asset scale for visual validation.
- Full-FOV startup mode instead of room-enabled wall-start mode.
- Reverted failed experiments such as skybox-removal-as-solution and
  near-plane room foregrounding when evidence showed they did not solve the
  issue.
- Startup guard to avoid held/stale B immediately toggling away from full-FOV.
- Avoided destroying/recreating the `SceneQuadLayer` on every placement toggle
  after the first visible room build suggested moving the same anchor was less
  risky.

Effects:

- Full-FOV startup markers and no-toggle-at-start markers were restored.
- Room screenshots still showed only the room until the carrier approach
  changed.

Important lesson:

- The pre-room projection renderer was not the core problem. Room integration
  exposed a carrier/composition/order problem.

### 9. Video Path Recovery

Observed:

- No-room/no-skybox isolation showed the fundamental custom projection quad.
- It did not show video when using the older device shared-storage path.

Built/tested:

- Switched smoke runs to stage video from a local source into the app-private
  files directory using `-VideoSourcePath`.

Effects:

- Staged-video smoke passed with decoded frame, AHB import, and rendered-video
  evidence.
- Skybox plus staged video also passed.

Important lesson:

- The reliable video transport for this Spatial app is app-private staging.
  Device shared-storage paths can configure MediaCodec but fail to produce
  decoded-frame evidence from the app process.

Evidence:

- `local-artifacts/spatial-camera-panel-headset/20260629-223147-camera-hwb-projection-smoke/evidence-summary.json`
  recorded `spatial_video_projection_rendered=true`.

### 10. Public Checkpoint Commit

Pushed:

- `rusty-quest` commit `5033532`: `Add Spatial room and staged asset path`.

Stored in that checkpoint:

- Generic staged GLB/GLTF module.
- Packaged room/skybox integration.
- B-button wall/full-FOV projection toggle plumbing.
- Layer-control continuity markers.
- Smoke/staging/build tooling.
- Docs noting depth/render ordering remained active work.

Not stored:

- Private FBX, MP4, or private converted GLB.
- Private Morphovision effect formulas.

Validation:

- `Test-SpatialCameraPanelAndroidStatic.ps1` passed.

### 11. Skybox Foreground Proof

Built/tested:

- Runtime skybox path switched from higher-level toolkit material to
  `SceneMesh.skybox(...)` with explicit `SceneMaterial` depth/write/order
  controls.
- `@OptIn` added for experimental `setRenderOrder`.

Effects:

- Before the fix, skybox-only showed only the skydome.
- After the runtime skybox material/order change, headset screenshot showed the
  stereo video/custom camera projection in front of the skybox.

Important lesson:

- The custom projection can foreground over a properly backgrounded skybox.
  Skybox proof does not automatically solve authored room geometry.

### 12. Failed Room-Ordering Attempts

Built/tested:

- Applied background-like material policy to loaded room mesh/materials:
  disabled depth writes, preprocess sort, low render order.
- Tried virtual-room-only near viewer-locked projection distance.
- Tried high `SceneQuadLayer` z-index and opaque blend/clip/color settings.
- Tried full-FOV quad facing/orientation variants.

Effects:

- These changes produced markers but did not make the full-FOV
  `SceneQuadLayer` visibly win over the authored room in screenshots.
- Several were reverted when evidence showed they were wrong or created worse
  behavior.

Important lesson:

- The authored room is not just a skybox. `SceneQuadLayer` depth/order knobs
  were not enough for reliable room foregrounding.

### 13. Projection Carrier Pivot: Video Surface Panel Scene Object

Built:

- A dedicated camera/video projection surface panel based on
  `VideoSurfacePanelRegistration` / `PanelSceneObject`.
- Existing native camera/video renderer was routed to the Android `Surface`
  from that panel carrier.
- The room path used the scene-panel carrier while keeping the existing
  renderer stack, rather than inventing a new video renderer.
- Native marker emission was fixed so video-composition evidence could be
  recorded after the first decoded video frame, not only in the first four
  startup frames.
- Smoke wrapper learned both carriers: old `SceneQuadLayer` and new
  `video-surface-panel-scene-object`.

Effects:

- Room/video smoke passed cleanly.
- Screenshot showed the custom stereo projection/video surface drawn in front
  of the authored room, with room visible around it.
- This answered the user's question "can the quad be the same sort of object
  as the GLB?" with a practical variant: the projection is not a static mesh,
  but a Spatial SDK panel scene object that participates more like room/panel
  objects while still accepting a live Android surface.

Evidence:

- `local-artifacts/spatial-camera-panel-headset/20260629-213245-camera-hwb-projection-smoke/evidence-summary.json`
  recorded `carrier=video-surface-panel-scene-object` and
  `spatial_video_projection_rendered=true`.

Important lesson:

- For live pixels in an authored room, SDK panel/surface carrier behavior is
  more useful than forcing the old `SceneQuadLayer` to behave like foreground
  room geometry.

### 14. Private Layer-Control Panel Visual Ordering

Observed:

- Projection foregrounded over the room, but right-primary private
  layer-control panel rendered behind the projection.

Built:

- Private layer-control panel switched from mesh-only to layer-backed Spatial
  SDK panel rendering.
- Captured the panel `PanelSceneObject` and set its layer z-index above the
  camera/video projection carrier.
- Build/static/docs markers updated to record this explicit compositor order.

Effects:

- Private layer-control panel rendered in front of the projection carrier.
- The old callback path still submitted through
  `updatePrivateLayerOverrideFromPanel` and `nativeUpdatePrivateLayerOverride`.

Evidence:

- `local-artifacts/spatial-camera-panel-headset/20260629-214905-camera-hwb-projection-smoke/private-layer-panel-open-markers.txt`
  captured `privateLayerPanelLayerZIndex=80`,
  `cameraVideoProjectionLayerZIndex=40`, and
  `privateLayerPanelAboveCameraProjectionLayer=true`.

Important lesson:

- Visual compositor order and world-space hit-test order are separate.

### 15. Input Hit Testing And Controller Rays

Observed:

- After visual ordering was fixed, controller pointer/ray hit the projection
  panel instead of the UI panel.
- UI panel could appear visually in front while still not being the first
  hit-test target.

Built/tested:

- First attempted an input-clearance policy: when the private panel opened,
  move the physical projection carrier behind the panel while keeping visual
  z-index. This produced markers but caused the projection to downsize or
  otherwise change in ways the user did not want.
- Replaced that with explicit input transparency:
  `Hittable(MeshCollision.NoCollision)` on the projection panel carrier.
- UI panel returned to normal reach distance.
- Smoke/static docs track `projectionPanelInputPassThrough=true` and
  `projectionPanelHittable=NoCollision`.

Effects:

- Controller rays can skip the projection carrier and resolve to the UI panel.
- Projection visual size no longer needs to change merely because the UI panel
  opens.

Evidence:

- `local-artifacts/spatial-camera-panel-headset/20260629-223147-camera-hwb-projection-smoke/evidence-summary.json`
  and `private-layer-panel-open-ui-action.txt` captured the input-transparent
  projection-panel markers.

Important lesson:

- Solve hit testing with collision/input policy, not by changing the visual
  projection distance.

### 16. Joystick And Private Panel Placement Policy

Observed:

- Right-stick side flick could move or teleport the UI panel.
- Later user feedback wanted left-stick up/down to control UI distance again
  and to persist across close/open.
- Projection panel could hide controller/pointer visuals.

Built in the latest local pass:

- Non-grabbed private panel transform is app-owned and re-applied from stored
  placement each scene tick.
- If actively grabbed, SDK transform is accepted and synced back.
- Left-stick Y controls private layer panel stored distance when the private
  panel is open.
- Private panel distance persists across close/open.
- Right-stick side-flick/default SDK movement is overwritten unless the panel
  is actively grabbed.
- Projection carrier z-index was lowered to reduce controller/pointer hiding.

Effects:

- Intended behavior is: right primary toggles UI panel, left-stick Y changes
  its distance, the distance persists, right-stick side flick no longer moves
  it, and squeeze/grab remains the explicit SDK movement path.
- This pass has static/native/build validation, but should still get headset
  user validation.

Validation:

- `Test-SpatialCameraPanelAndroidStatic.ps1` passed.
- Native receipt crate tests passed.
- APK build passed with private profile inputs in the local build.

Important lesson:

- Panel transform authority must be explicit: stored placement when not
  grabbed, SDK placement only during active grab.

### 17. Layer-Button Effect On Projection

Observed:

- The private panel buttons used to change the active layer before room work.
- During room iterations, button clicks sometimes reached native markers but
  did not visibly change the projection.

Findings:

- Kotlin callback path still calls the native override path.
- In APKs without private shader inputs, the public fallback/raw projection
  could ignore the active layer, making button presses look inert even if JNI
  state changed.

Built:

- Native raw fallback diagnostic now receives the current layer override in
  push constants.
- Fallback fragment shader applies public diagnostic layer variants for layer
  ids 0-6.
- Markers distinguish this as `fallbackProjectionLayerOverrideDiagnostic=true`,
  not private Morphovision effect logic.

Effects:

- In a public/fallback build, layer buttons should have a visible diagnostic
  effect instead of silently doing nothing.
- In a private shader-profile build, the real private stack remains the
  intended active visual path.

Validation:

- Static checks verify shader and push-constant wiring.
- Native tests passed.

Important lesson:

- UI callback success and visual layer effect are different gates. The active
  render path must consume the selected layer.

### 18. Checkpoint And Room-Object Carrier Retry

Checkpoint:

- `rusty-quest` commit `f06dd50`: `Checkpoint Spatial room world-space projection state`.

Stored in that checkpoint:

- The room-enabled `video-surface-panel-scene-object` projection carrier state.
- Generic staged GLB/GLTF and app-private staged video launch support.
- Packaged virtual room/skybox integration.
- Private layer-control panel ordering/input transparency work from the latest
  room pass.
- This public-safe iteration log.

New retry now under test:

- Default the room projection carrier back to the older `SceneQuadLayer`
  render path, but anchor it to a generated Spatial SDK room object so the
  surface participates in scene placement more like the staged GLB object.
- Mark the anchor as `projectionAnchorHittable=NoCollision` so controller rays
  should not stop on the projection surface before reaching the UI panel.
- Set the projection anchor material render order explicitly and record
  `projectionRoomRenderOrder=scenequadlayer-room-object-depth-order-under-test`.
- Restore the private layer-control panel to the older `PanelRenderMode.Mesh`
  path that had working input before room integration.
- Keep the saved panel carrier selectable with
  `debug.rustyquest.spatial.camera_hwb_projection_probe.carrier=video-surface-panel-scene-object`
  so headset tests can compare both carriers without code churn.

Expected validation:

- With the room enabled, app-private staged video and staged GLB should still
  launch from ignored local manifests.
- The projection should start in full-FOV viewer-locked mode.
- Right secondary/B should still toggle between full-FOV and wall placement.
- Right primary should toggle the private mesh UI panel without resizing the
  projection.
- Layer buttons should still call the native layer override path and visibly
  affect the active public fallback/private projection path.

Status:

- Static validation is the next gate. Headset evidence is still required before
  treating the `scenequadlayer-room-object` carrier as accepted.

## Current State Before Next Iteration

What is working or strongly evidenced:

- Generic staged GLB/GLTF runtime asset path exists.
- Local FBX-to-GLB conversion path exists outside repos.
- Packaged virtual room path exists and is explicitly not MRUK.
- App-private video staging is the reliable video route.
- Skybox foregrounding was proven after background material/order changes.
- Authored room foregrounding was proven after switching full-FOV projection
  to a video-surface panel scene object carrier.
- Private layer-control panel can render visually above the projection layer.
- Projection carrier can be made input-transparent so controller rays reach
  the UI.
- Private asset/video path memory is now in ignored local manifests.

Current open issues:

- User still needs headset validation of the latest joystick/distance/z-index
  pass.
- UI buttons must be validated in-headset against both private shader builds
  and public fallback diagnostic builds.
- Controller/pointer visibility in front of the projection needs a clean
  visual acceptance run after the latest projection z-index change.
- Full depth-stack organization across room, skybox, GLB, video panel,
  projection, and private UI remains active work.
- The code has accumulated pressure in `SpatialCameraPanelActivity.kt`; future
  feature work should consider splitting projection carrier, private panel
  placement, virtual room loading, staged asset handling, and controller input
  into owned modules after the current behavior stabilizes.

## Evidence Index

Use these relative local artifact folders when comparing behavior. They are
developer evidence, not public source assets.

- `local-artifacts/spatial-camera-panel-headset/20260629-193326-camera-hwb-projection-smoke/`
  First model smoke with staged GLB entity creation.
- `local-artifacts/spatial-camera-panel-headset/20260629-213245-camera-hwb-projection-smoke/`
  Room foreground proof with `video-surface-panel-scene-object` carrier and
  rendered video.
- `local-artifacts/spatial-camera-panel-headset/20260629-214905-camera-hwb-projection-smoke/`
  Private layer-control panel z-index above projection.
- `local-artifacts/spatial-camera-panel-headset/20260629-215951-camera-hwb-projection-smoke/`
  Earlier input-clearance experiment, useful mainly as a rejected approach.
- `local-artifacts/spatial-camera-panel-headset/20260629-223147-camera-hwb-projection-smoke/`
  Projection input pass-through via `NoCollision` and room/video smoke.
- `local-artifacts/spatial-camera-panel-headset/20260629-223147-camera-hwb-projection-smoke/private-layer-panel-open-ui-action.txt`
  Focused panel-open marker capture for input transparency and panel distance.

## Current Resume Path

Before running another APK or smoke after context compaction:

1. Read this document.
2. Read ignored local manifest `local-artifacts/spatial-camera-panel-private-inputs.json`.
3. Dot-source ignored helper when the shell needs launch env vars:

   ```powershell
   . .\local-artifacts\Set-SpatialCameraPanelPrivateInputs.ps1
   ```

4. Build with the private layer profile only when the run is intended to test
   private layer visuals; otherwise expect the public fallback diagnostic layer
   selector.
5. For a room/video/asset smoke, prefer app-private staging for both video and
   model. Avoid shared-storage video paths for rendered-video evidence.
6. Reserve Agent Board resources before APK build or headset validation.

## Next Validation Gates

Recommended next run:

- Build an APK from the latest local state.
- Use app-private staged video and staged GLB from the ignored manifest.
- Enable packaged virtual room.
- Start in full-FOV camera/video projection mode.
- Open/close the private layer-control panel with right primary.
- Confirm pointer/controller visibility and UI hit testing.
- Move private panel distance with left-stick Y, close/open it, and confirm the
  distance persists.
- Confirm right-stick side flick does not move/teleport the panel.
- Press layer buttons and confirm the active projection changes in the active
  render path.

Acceptance should require both:

- Machine markers for carrier, panel ordering, input transparency, stored
  placement, and layer override transport.
- Human headset confirmation that the projection, controller/pointer, and UI
  are visually usable together in the authored room.
