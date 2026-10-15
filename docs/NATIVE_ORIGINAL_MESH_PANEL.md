# Mesh presentation with the original particle renderer

`ui.native_mesh_panel.original_product` selects the original full product Activity
as a native mesh surface while retaining `solid-black-private-particles` and its
original renderer/background/particle/driver descriptors. It does not select the
scene-depth renderer or its composable substitutes.

The descriptor selects `RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ENABLED=true`,
`RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ORIGINAL_SCENE=true` and
`RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS=false`. The builder requires the
exact selected original descriptor closure and rejects unselected/ambient values.
The existing `ui.native_mesh_panel` remains the separate scene-depth route.

The original mesh uses a color-only panel/pointer pipeline, with depth test and
write disabled. Original hand blending, shader specializations, particle ordering
and half-resolution policy still come from their original owners. A tracers-only
request does not imply that path is effective: sorting and anchor echoes can
select the existing whole-billboard half-resolution path.

Panel pointer ownership does not replace simultaneous hand/controller tracking
or original hand visual/breath input. The mesh input requests XR API1.1.53 for
runtime aim behavior independently of the unchanged 3D render mode. The ordinary
2D product continues without mesh selection; the neutral mesh keeps scene depth.

Created markers report the actual selected mode and `depth=color-only-overlay`
or `depth=scene-zbuffer`. A created resource, setter or source test is not frame
submission or pixel evidence. Existing submitted generation/pose/visibility
receipts remain the observation boundary.

`tools/checks/Test-NativeRendererPanelComposition.ps1 -SourceOnly` checks finite
selection admission without a compiler. Its default also compiles the actual
pure owner modules for ordinary, old composable and original scene selections.
`tools/checks/Test-OriginalPanelHostManifest.ps1` checks the actual manifest owner
for both mesh selections, ordinary 2D and neutral routes.
