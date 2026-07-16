# Spatial Room World-Space Iteration Log

This document tracks the `Add Spatial FBX asset support` Codex thread from the
last pre-room baseline through the current room, world-space projection, staged
asset, and opaque-slot UI work. It is intentionally public-safe: generic
Spatial SDK support is documented here, while exact private media/model file
paths stay in ignored local manifests.

## Baseline Before Room Work

Thread: `Add Spatial FBX asset support`

Pre-room pushed state:

- `rusty-quest` commit `15e715c`: `Add Spatial depth policy controls`.
- Parallel private downstream repo commit `6952147`: private depth-compare path.
- Static validation passed with `Test-SpatialCameraPanelAndroidStatic.ps1`.
- Both working trees were clean against `origin/main`.

Functional baseline at that point:

- Spatial Camera Panel could render video background, camera projection,
  private downstream shader stack, layer-control panel, projection scale,
  depth-source policy, and depth-alignment controls.
- Depth compare mode showed visually different Meta depth layers, but full
  depth-stack alignment was explicitly deferred.
- Private downstream effect details, local media, local model files, and
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
- Private downstream shader/profile details and effect semantics.

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
- Private downstream effect formulas.

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
  not private downstream effect logic.

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

Initial retry:

- Default the room projection carrier back to the older `SceneQuadLayer`
  render path, but anchor it to a generated Spatial SDK room object so the
  surface participates in scene placement more like the staged GLB object.
- The first retry marked the anchor as `projectionAnchorHittable=NoCollision`
  and set an explicit projection anchor material render order. A later
  diagnostic restored the commit `5033532` anchor shape more closely:
  `Transform + Scale + Visible`, no `Hittable`, and default passthrough material
  ordering.
- The first retry recorded
  `projectionRoomRenderOrder=scenequadlayer-room-object-depth-order-under-test`;
  the final first-room replay restores the older
  `projectionRoomRenderOrder=projection-layer-over-virtual-room` token.
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

- Static validation and APK build passed, but headset evidence rejected this
  carrier for authored-room foregrounding.

### 19. Headset Result: Room-Object Carrier Rejected

Observed on headset:

- Inputs and ordering around the private UI recovered: the right-primary panel
  could be opened, controller input reached the UI, and layer button transport
  markers were emitted.
- App-private staged video, staged GLB, packaged room load, and
  `scenequadlayer-room-object` projection creation all produced runtime
  evidence.
- The custom camera projection itself still rendered behind authored room
  geometry. The user could see it outside/through the room window, matching the
  earliest room clue but failing the foreground goal.

Evidence:

- `local-artifacts/spatial-camera-panel-headset/20260629-234712-camera-hwb-projection-smoke/`
  recorded `scene_quad_layer_room_object_carrier=true`,
  `spatial_video_projection_rendered=true`,
  `spatial_asset_model_entity_created=true`,
  `spatial_virtual_room_loaded=true`, and
  `camera_projection_room_render_order=true`.

Conclusion:

- The old `SceneQuadLayer` projection path is still valuable as a no-room and
  skybox isolation baseline.
- Anchoring that `SceneQuadLayer` to a generated room object with
  `NoCollision` does not make it participate in authored room depth/order the
  way the staged GLB object does.
- The accepted room foreground carrier should return to
  `video-surface-panel-scene-object`, while keeping the recovered
  `PanelRenderMode.Mesh` private UI input path.
- `scenequadlayer-room-object` remains only a reproducible comparison path via
  `debug.rustyquest.spatial.camera_hwb_projection_probe.carrier`.

### 20. Sample Skybox SceneQuadLayer Negative Result

Clarification:

- The desired product goal remains a custom camera projection surface in front
  of the authored room.
- Reproducing the observed ordering `skybox < projection < room` is only a
  diagnostic goal, useful for understanding how Spatial SDK composition and
  depth ordering are behaving.

Built for this diagnostic:

- Restored the direct `SceneQuadLayer` diagnostic anchor closer to commit
  `5033532`: no `Hittable(MeshCollision.NoCollision)` on the generated anchor
  entity, no forced passthrough material render order, 1.0 m viewer-locked
  target distance, and original sample-style `mesh://skybox`.
- A follow-up replay patch also restores the first-room skybox entity creation
  shape from commit `5033532`: `Entity.create(Mesh(...), Material(...),
  Transform(...))` using `SPATIAL_VIRTUAL_ROOM_SKYBOX_MESH_URI`, with the
  runtime marker `skyboxEntityCreateApi=toolkit-varargs-first-room-replay`.
- Kept the accepted `video-surface-panel-scene-object` carrier separate; its
  input-transparent `NoCollision` panel behavior is still the foreground-room
  product path.

Evidence:

- `local-artifacts/spatial-camera-panel-headset/20260630-002509-camera-hwb-projection-smoke/`
  ran the restored direct SceneQuadLayer anchor with room, sample `mesh://skybox`,
  staged video, and staged GLB. Runtime markers passed for layer creation,
  native start, video rendering, room load, and staged model creation, but the
  headset view still showed room plus skybox and no custom projection surface.
- `local-artifacts/spatial-camera-panel-headset/20260630-002624-camera-hwb-projection-smoke/`
  disabled the room and kept only the original sample `mesh://skybox`. Runtime
  markers again passed for direct SceneQuadLayer creation and video/camera
  frame production, but the headset view showed only the skybox.
- `local-artifacts/spatial-camera-panel-headset/20260630-004515-camera-hwb-projection-smoke/`
  restored the sample skybox `Entity.create(Mesh, Material, Transform)` call
  shape and emitted
  `skyboxEntityCreateApi=toolkit-varargs-first-room-replay`. The wrapper
  passed with room, app-private staged video, staged GLB, direct SceneQuadLayer
  creation, and native video/camera frame composition. The screenshot still
  showed the authored room and sample skybox without the custom projection
  visible.
- `local-artifacts/spatial-camera-panel-headset/20260630-005247-camera-hwb-projection-smoke/`
  added the remaining first-room replay clues: `projectionStartGate=virtual-room-loaded`
  and the old `projectionRoomRenderOrder=projection-layer-over-virtual-room`
  token. The wrapper passed with sample `mesh://skybox`, room, app-private
  staged video, staged GLB, direct SceneQuadLayer creation, and native
  video/camera frame composition. The screenshot again showed only the authored
  room and sample skybox, with no visible custom projection.

Conclusion:

- The original sample `mesh://skybox` path alone is enough to hide or outrank
  the direct SceneQuadLayer custom projection surface in the current app.
- This differs from the earlier explicitly backgrounded runtime skydome
  experiment, where the custom projection did render in front of the skydome.
- The final exact replay run did include
  `skyboxEntityCreateApi=toolkit-varargs-first-room-replay`,
  `projectionStartGate=virtual-room-loaded`, and the old
  `projectionRoomRenderOrder=projection-layer-over-virtual-room` token, but it
  still did not show the projection.
- The direct SceneQuadLayer plus original sample skybox/room path should now be
  treated as a negative comparison/diagnostic route, not the likely foreground
  product path. Use the `video-surface-panel-scene-object` carrier for
  foreground-room work.

## Current State Before Next Iteration

What is working or strongly evidenced:

- Generic staged GLB/GLTF runtime asset path exists.
- Local FBX-to-GLB conversion path exists outside repos.
- Packaged virtual room path exists and is explicitly not MRUK.
- App-private video staging is the reliable video route.
- Projection over a skybox was proven only for the explicitly backgrounded
  runtime skydome material/order path. The original sample `mesh://skybox`
  remains a negative case for direct SceneQuadLayer foregrounding.
- Authored room foregrounding was proven after switching full-FOV projection
  to a video-surface panel scene object carrier.
- The `scenequadlayer-room-object` retry preserved input and runtime creation
  evidence but was rejected because the projection stayed behind authored room
  geometry and was visible outside/through the room window.
- A restored first-room-style direct SceneQuadLayer anchor did not reproduce the
  diagnostic `skybox < projection < room` ordering; skybox-only testing showed
  the sample `mesh://skybox` path by itself can hide the direct projection.
- Restoring the first-room sample skybox entity creation call shape, old
  `projection-layer-over-virtual-room` marker token, and explicit
  `virtual-room-loaded` start gate still did not reproduce the projection.
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
- The next accepted-room build should combine the foreground-capable
  `video-surface-panel-scene-object` projection carrier with the recovered
  mesh private-layer UI input path.
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
- `local-artifacts/spatial-camera-panel-headset/20260629-234712-camera-hwb-projection-smoke/`
  Rejected `scenequadlayer-room-object` retry: inputs and runtime creation were
  good, but the projection still rendered behind authored room geometry.
- `local-artifacts/spatial-camera-panel-headset/20260630-002509-camera-hwb-projection-smoke/`
  Restored first-room-style direct SceneQuadLayer anchor with room and original
  sample `mesh://skybox`; markers passed, projection still not visible.
- `local-artifacts/spatial-camera-panel-headset/20260630-002624-camera-hwb-projection-smoke/`
  Skybox-only isolation for original sample `mesh://skybox`; markers passed,
  direct SceneQuadLayer projection still not visible, isolating the sample
  skybox path as a negative case.
- `local-artifacts/spatial-camera-panel-headset/20260630-004515-camera-hwb-projection-smoke/`
  Restored sample skybox entity creation API with room, video, and staged GLB;
  wrapper passed but screenshot still did not show the custom projection.
- `local-artifacts/spatial-camera-panel-headset/20260630-005247-camera-hwb-projection-smoke/`
  Final first-room replay with restored skybox API, virtual-room-loaded start
  gate, and old `projection-layer-over-virtual-room` token; wrapper passed but
  screenshot still did not show the custom projection.
- `local-artifacts/spatial-camera-panel-headset/20260630-101615-manual-actual-room-sample-skybox/`
  Actual, non-synthetic manual custom-mesh carrier launch with private profile,
  staged video, staged GLB, room, and sample skybox. Machine evidence passed,
  but later human headset inspection showed the UI panel did not render in
  front of the custom projection panel.
- `local-artifacts/spatial-camera-panel-headset/20260630-105118-manual-actual-room-sample-skybox-relaunch/`
  Relaunch of the same actual manual custom-mesh setup. Machine evidence
  again passed and the app was left running for inspection; human result was
  still that the UI panel was not visually in front of the projection.

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

Current actual app build and launch workflow, public-safe form:

```powershell
& '<Quest toolchain activation script>'
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-SpatialCameraPanelAndroid.ps1 `
  -PrivateLayerProfilePath <private downstream profile json> `
  -OutDir target\spatial-camera-panel-android-manual-actual

. .\local-artifacts\Set-SpatialCameraPanelPrivateInputs.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1 `
  -ApkPath target\spatial-camera-panel-android-manual-actual\rusty-quest-spatial-camera-panel.apk `
  -OutDir local-artifacts\spatial-camera-panel-headset\<timestamp>-manual-actual-room-sample-skybox `
  -RunSeconds 20 `
  -Serial <quest-serial> `
  -Adb <work-ssd-adb> `
  -ProjectionCarrier manual-panel-scene-object-custom-mesh `
  -EnableVirtualRoom `
  -EnableSkybox `
  -SkyboxMode sample `
  -RequireSpatialVirtualRoom `
  -RequireSpatialVideoProjection `
  -RequireSpatialAssetModel `
  -RequirePublicMultiStackProjection `
  -AssetScale 0.35 `
  -ClearLogcat
```

The current actual APK built by that workflow was:

- `target/spatial-camera-panel-android-manual-actual/rusty-quest-spatial-camera-panel.apk`
- SHA-256 `F2EA8D0CD80FA00F62A94ACFF10F58EBB8FC94E6088CE9FF5D9F43E5FE56EB4E`

Do not commit the private profile, private video, private GLB, or generated APK.
The workflow is tracked so the next agent can reproduce the current app shape
without re-discovering local assets after context compaction.

## Targeted Carrier Matrix

The current targeted strategy is tracked in
`docs/SPATIAL_LAYERING_CARRIER_PROBE_PLAN.md`.

Use the matrix wrapper when the next question is carrier behavior rather than
runtime implementation:

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

This wrapper delegates to the existing camera/video smoke and aggregates
per-case summaries for the currently implemented carriers:

- direct `scenequadlayer-room-object`;
- foreground-capable `video-surface-panel-scene-object`.

Strict screenshot evidence is now required before a screenshot counts:

- expected package/activity foreground proof;
- live PID;
- focused or resumed immersive client proof where Android exposes it;
- synthetic checkerboard/color target visible by pixel classification.

Current valid baseline:

- `local-artifacts/spatial-camera-panel-headset/20260630-015828-layering-matrix/layering-matrix-summary.json`
  is the latest strict evidence run. It replaces the older invalid
  `20260630-012504` screenshot set, which showed the wrong native volumetric
  raymarching path.
- `scenequadlayer-no-room-no-skybox` passed with the synthetic target visible.
- `scenequadlayer-sample-skybox-only`/the previous sample-skybox-only row
  failed strict screenshot validity because the synthetic target was not
  visible.
- `scenequadlayer-room-sample-skybox`/the previous room+sample-skybox row also
  failed strict screenshot validity because the synthetic target was not
  visible.
- `video-surface-panel-scene-object` with room plus sample skybox passed with
  the synthetic target visible.

The next strict matrix adds `debug.rustyquest.spatial.skybox.mode` so the
original sample `mesh://skybox` path and the custom backgrounded
`SceneMesh.skybox` path are separate evidence rows:

- no room/no skybox;
- sample `mesh://skybox` only;
- custom backgrounded skybox only;
- room plus sample skybox;
- room plus custom skybox.

The panel-focused research brief `spatialsdkpanels.txt` changes the carrier
ranking. The best long-term world-surface target is a hidden/noninteractive
readable media producer whose `SceneTexture` is fed into a normal
`SceneMaterial` scene quad. This follows the `MediaPlayerSample` panel-texture
reuse pattern and the readable media panel/shader route, but it must validate
the known readable mesh-mode workaround:

```kotlin
val panelConfig = readableSettings.toPanelConfigOptions()
panelConfig.layerConfig = null
```

The next smaller diagnostic carrier remains
`manual-panel-scene-object-custom-mesh`, derived from `SpatialVideoSample`'s
manual `PanelSceneObject`, custom `sceneMeshCreator`, and `getSurface()` path.
The first test should keep it non-interactive, omitting the sample's
`Hittable()` and ISDK grabbable setup so the UI panel owns input. Follow-up
controls are `forceSceneTexture = true`, UI `QuadLayerConfig(zIndex = 99)`, and
readable media panels as shader/texture sources. Any new carrier test must use
the synthetic checkerboard first, not private media.

### 25. Manual PanelSceneObject Custom Mesh Diagnostic

Implemented the `manual-panel-scene-object-custom-mesh` carrier as the next
focused diagnostic after the custom-skybox matrix. It constructs a manual
`PanelSceneObject`, sets a custom `sceneMeshCreator` using
`SceneMesh.singleSidedQuad`, obtains `panelSceneObject.getSurface()`, registers
the object through `SceneObjectSystem`, and feeds the same synthetic
checkerboard/native camera surface path used by the existing strict smoke. It
intentionally does not add the `SpatialVideoSample` `Hittable()`, ISDK panel
dimensions, grab handle, or grabbable setup; markers record
`manualPanelNoHittable=true`, `manualPanelNoIsdkGrabbable=true`, and
`panelInputOptionsClickButtons=0`.

Added the `manual-carrier` matrix preset for exactly five public-safe rows:
no room/no skybox, sample `mesh://skybox` only, custom skybox only, room plus
sample skybox, and room plus custom skybox. This is still a diagnostic carrier,
not the long-term readable-producer scene-material route.

Ran the focused headset matrix at
`local-artifacts/spatial-camera-panel-headset/20260630-025426-layering-matrix`.
The APK SHA-256 was
`ADE7B9CFCF020C91A8056A60F901D5B6972941999C74464C753D6193EF358F18`.
All five rows passed with correct package/activity foreground proof, live PID,
focus/resumed proof, valid screenshot, and visible synthetic checkerboard:

- `manual-panel-custom-mesh-no-room-no-skybox`: ratio `0.622669`.
- `manual-panel-custom-mesh-sample-skybox-only`: ratio `0.635947`.
- `manual-panel-custom-mesh-custom-skybox-only`: ratio `0.622657`.
- `manual-panel-custom-mesh-room-sample-skybox`: ratio `0.616667`.
- `manual-panel-custom-mesh-room-custom-skybox`: ratio `0.617758`.

The hard `room + sample skybox` screenshot visibly shows the checkerboard in
front of the authored room/sample skybox path. The evidence proves visibility
for this carrier under the strict screenshot rules; pointer transparency still
needs a separate controller-ray/UI hit-test slice.

Future carrier work or refactors should follow the `FeatureDevSample`
modularity pattern: reusable SpatialFeature-style modules with their own
component/system ownership, registered by the Activity. Do not keep growing
`SpatialCameraPanelActivity.kt` as the owner of every room, carrier, panel,
controller, and marker behavior.

### 26. Actual Manual Carrier App Launch And UI Ordering Correction

Built and launched the actual app setup, not the synthetic matrix probe, using
the manual custom-mesh carrier:

- private downstream profile compiled into the native receipt library;
- app-private staged video;
- app-private staged GLB at scale `0.35`;
- packaged virtual room enabled;
- sample `mesh://skybox` enabled;
- full-FOV camera/video projection active at launch;
- private layer-control UI still opened by right primary.

Machine evidence passed twice:

- `20260630-101615-manual-actual-room-sample-skybox`;
- `20260630-105118-manual-actual-room-sample-skybox-relaunch`.

Important human finding:

- The UI panel did not show in front of the custom projection panel.

This corrects the interpretation of the `Fix Spatial layering` result. That
thread proved the manual `PanelSceneObject` custom-mesh carrier can be visible
with the sample room and skybox, and that the carrier is configured as
non-hittable/non-grabbable. It did not prove the actual private UI panel can
render in front of that projection in the full app, and the headset inspection
now proves that the actual UI-over-projection ordering remains unsolved for
this carrier.

Current app contents to preserve while iterating:

- room and sample skybox integration are active and working;
- staged GLB model integration is active and working;
- app-private video projection is active and working;
- private layer native update path is active in machine markers;
- manual custom-mesh projection carrier is a valid composition diagnostic, but
  not an accepted UI-ordering solution.

Next targeted work should test UI-above-projection controls directly, such as
the UI panel `QuadLayerConfig(zIndex = 99)` control row and the
readable-producer/`SceneTexture`/`SceneMaterial` scene-quad route. Do not claim
the manual custom-mesh carrier as the final foreground UI solution unless a
new headset run proves the UI panel visibly renders in front of it and remains
clickable.

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

### 27. UI Foreground Geometry And Force Scene Texture Ordering Test

Ran two targeted actual-app tests after the manual carrier UI ordering failure.
Both used the same app-private staged video, staged GLB at scale `0.35`,
packaged virtual room, sample `mesh://skybox`, full-FOV manual custom-mesh
projection carrier, and private layer-control UI opened by the app's
`private-layer-panel-open` command.

First test:

- APK:
  `target\spatial-camera-panel-android-ui-layer-front-test\rusty-quest-spatial-camera-panel.apk`
- SHA-256:
  `0570B809BF800C87E49F5D4C6AEEB3D6CF23F957036887F41A065DB6BF19ED80`
- Evidence:
  `local-artifacts\spatial-camera-panel-headset\20260630-110818-ui-layer-front-manual-actual`
- Code delta: private UI panel switched from `PanelRenderMode.Mesh()` to
  `PanelRenderMode.Layer()`, `privateLayerPanelLayerConfig=enabled`, and
  `PRIVATE_LAYER_PANEL_LAYER_Z_INDEX = 99`.
- Result: failed visually. Machine markers showed the private panel was open
  and the layer z-index update succeeded, but the screenshot still showed only
  the custom projection. The important marker clue was
  `panelDistanceLessThanCameraProjection=false`: in the room viewer-locked path
  the projection target is at `0.25m`, while the UI opened at `0.72m`, so the
  UI was still physically behind the full-FOV projection.

Second test:

- APK:
  `target\spatial-camera-panel-android-ui-foreground-force-scene-texture-test\rusty-quest-spatial-camera-panel.apk`
- SHA-256:
  `A574EBA5AAF2D038F05488F881B80D0098B318807521D67ADA20FE3021D30272`
- Evidence:
  `local-artifacts\spatial-camera-panel-headset\20260630-111828-ui-foreground-force-scene-texture-manual-actual`
- Code delta: kept the high-z UI layer, opened the private UI at the existing
  foreground helper distance `0.22m` with scale adjusted to `0.1986`, and set
  `forceSceneTexture = true` on the manual custom-mesh projection
  `PanelConfigOptions` while keeping `enableLayer = false` and
  `layerConfig = null`.
- Result: visual screenshot success with a wrapper caveat. The smoke wrapper
  installed and launched the app, then failed its strict required-marker gate
  on `public_multistack_depth_real_descriptor_bound`; foreground proof remained
  valid and the app stayed live. `ui-open-screencap.png` shows the Layer
  Selection Panel rendered in front of the custom projection while the
  projection, room, and skybox remain active. Markers include
  `privateLayerPanelRenderMode=spatial-sdk-layer`,
  `privateLayerPanelLayerZIndex=99`,
  `manualPanelForceSceneTexture=true`,
  `privateLayerPanelInputForegroundDistanceMeters=0.2200`,
  `privateLayerPanelInputForegroundScale=0.1986`, and
  `panelDistanceLessThanCameraProjection=true`.

This proves a viable visual ordering path for UI over the full-FOV manual
custom-mesh projection in the authored room. It does not yet prove controller
ray usability, button hit testing, or layer-button effect propagation in this
configuration. The next acceptance slice should keep this exact launch shape
and manually verify:

- controller/pointer remains visible enough to target the UI;
- UI buttons can be clicked while the projection is open;
- layer buttons change the active custom projection layer;
- right primary toggles the UI without moving the projection;
- left-stick Y distance changes still behave acceptably from the foreground
  starting distance and persist across close/open.

### 28. Behind-Projection A/B Confirms Geometry Drives UI Visibility

Ran the requested A/B to isolate whether `forceSceneTexture=true` plus the
high-z UI layer can foreground the UI without placing it physically in front of
the projection. The run kept the successful projection-side settings:

- manual custom-mesh projection carrier;
- `forceSceneTexture = true`;
- `enableLayer = false`;
- `layerConfig = null`;
- UI `PanelRenderMode.Layer()`;
- UI layer z-index `99`;
- packaged room, sample skybox, staged video, and staged GLB.

Only the UI placement changed back to the old normal-distance path:

- `privateLayerPanelInputForegroundActive=false`;
- `privateLayerPanelInputForegroundDistanceMeters=0.7200`;
- `privateLayerPanelInputForegroundScale=0.6500`;
- `privateLayerPanelDefaultReachDistancePreserved=true`.

Evidence:

- APK:
  `target\spatial-camera-panel-android-ui-behind-force-scene-texture-ab\rusty-quest-spatial-camera-panel.apk`
- SHA-256:
  `BC217DEF94F543190C00F29A076831F5FFAA2C3F8777B90CDBB8E6FC4D78E476`
- Run:
  `local-artifacts\spatial-camera-panel-headset\20260630-114013-ui-behind-force-scene-texture-ab`

Result: negative. The app foreground proof and wrapper evidence passed, the
remote `private-layer-panel-open` command delivered, and the markers confirmed
`privateLayerPanelVisible=true`, `privateLayerPanelLayerZIndex=99`,
`projectionPanelInputTargetDistanceMeters=0.2500`, and
`panelDistanceLessThanCameraProjection=false`. The post-open screenshot
`ui-open-screencap.png` still shows only the custom projection; the UI panel is
not visible.

Conclusion: `forceSceneTexture=true` and UI layer z-index `99` are not enough
to render the UI in front while the UI remains physically behind the full-FOV
projection surface. The working visual composition depends on foregrounding the
UI geometry in front of the `0.25m` projection plane. Keep the foreground
`0.22m` UI placement as the current working path until a different carrier or
compositor route proves true layer-order foregrounding.

### 29. Accepted No-Room 2m Projection / 1m UI Default

Built and launched a targeted no-room default to remove the authored room and
skybox from the UI/projection ordering question. After headset inspection, the
user accepted this as the current default behavior.

Requested setup:

- room disabled;
- skybox disabled;
- manual custom-mesh projection carrier active;
- app-private video/custom camera projection surface at 2.0m;
- generic layer-control UI panel opened at 1.0m;
- left-stick Y remains the private UI distance control while the UI is open;
- right secondary/B is deliberately consumed as a no-op.

Source markers added for this diagnostic:

- `CAMERA_HWB_PROJECTION_TARGET_DISTANCE_METERS = 2.0f`;
- `CAMERA_HWB_PROJECTION_TARGET_DISTANCE_MARKER = "2.00"`;
- `PRIVATE_LAYER_PANEL_DISTANCE_METERS = 1.0f`;
- `PARTICLE_LAYER_TARGET_DISTANCE_MAX_METERS = 2.00f`;
- `cameraProjectionWallToggleInput=disabled-right-secondary-noop`;
- `cameraProjectionWallToggleEnabled=false`;
- `privateLayerPanelDefaultReachDistancePreserved=true`;
- `privateLayerPanelScaleAdjustedForForeground=false`.

Validation:

- Static gate passed:
  `tools\checks\Test-SpatialCameraPanelAndroidStatic.ps1`
- PowerShell parser check passed for
  `tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1`
- `git diff --check` passed
- APK:
  `target\spatial-camera-panel-android-no-room-projection2-ui1-persist\rusty-quest-spatial-camera-panel.apk`
- APK SHA-256:
  `92825004A295EBE63BB810607E8C83EF80C776F40A4E02D53B6F260428E8D56A`
- Boundary-sanitized final build, not a new headset run:
  `target\spatial-camera-panel-android-no-room-projection2-ui1-default-final\rusty-quest-spatial-camera-panel.apk`
- Boundary-sanitized final build SHA-256:
  `1FDCF5F1C4EE1BCEAE34E9FB31E95488BCAD14AEEF973856ACF834FE7BCF2D4B`

Headset run:

- Evidence:
  `local-artifacts\spatial-camera-panel-headset\20260630-120459-no-room-projection2-ui1-persist`
- Smoke summary:
  `local-artifacts\spatial-camera-panel-headset\20260630-120459-no-room-projection2-ui1-persist\evidence-summary.json`
- UI-open screenshot:
  `local-artifacts\spatial-camera-panel-headset\20260630-120459-no-room-projection2-ui1-persist\ui-open-screencap.png`

Result:

- Smoke passed with foreground proof, live PID, camera/video projection,
  public multi-stack projection, real depth descriptor binding, and screenshot
  capture.
- Wrapper summary records
  `enable_spatial_virtual_room=false`, `enable_spatial_skybox=false`,
  `spatial_asset_model_requested=false`, and
  `projection_target_default_distance_two_meters=true`.
- Runtime projection markers include
  `projectionStartGate=scene-ready`,
  `projectionRoomRenderOrder=no-room-scenequadlayer-baseline`,
  `targetDistanceMeters=2.0000`,
  `targetDistanceDefaultMeters=2.00`,
  `cameraProjectionWallToggleInput=disabled-right-secondary-noop`, and
  `cameraProjectionWallToggleEnabled=false`.
- UI-open markers include
  an initial right-primary open with `headlockedPanelDistanceMeters=1.0000`,
  `privateLayerPanelDistanceControl=left-stick-y-private-panel-free-transform-distance`,
  `panelDistanceLessThanCameraProjection=true`,
  `privateLayerPanelLayerZIndex=99`, and
  `rightStickSideFlickPanelMoveDisabled=true`.
- A later remote open in the same app session reported
  `headlockedPanelDistanceMeters=1.5000` after stored placement changes, which
  is the intended persistence behavior rather than a reset to the 1.0m default.
- The UI-open screenshot shows the layer-control panel visibly in
  front of the projection in the empty/no-room scene.

Boundary:

- The final run cleared the local asset-model environment before launch, so no
  GLB was staged and no room or skybox was requested.
- The user confirmed the launched build works as desired. Keep this no-room,
  2m projection / 1m layer-control UI setup as the public default until a later
  room/wall-placement build is explicitly requested.
- Public documentation and UI labels expose only generic layer slots. The
  private mapping from those slots to downstream effect names stays in the
  private repo/profile.
