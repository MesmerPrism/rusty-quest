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

## Asynchronous image fence ownership

Mesh-panel acquisition uses bounded `AImageReader_acquireNextImageAsync` calls,
keeping each acquired image paired with its own duplicated acquire fence. Dropped
queue entries transfer that exact fence to `AImage_deleteAsync`. The retained
entry is sampled only after a nonblocking fence probe reports readiness; its
acquire fence is then closed once. GPU imports and image leases still retire
through the owning frame fences and the explicit graphics-to-FOREIGN release.

This avoids the `acquireLatestImageAsync` loop in the examined
[Android 14 source](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-14.0.0_r1/media/ndk/NdkImageReader.cpp)
and [Android 15 security source](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-security-15.0.0_r8/media/ndk/NdkImageReader.cpp),
which overwrites the output acquire-fence FD while obtaining the next image,
then passes that FD when releasing the previous image. Under queued production,
the returned image can therefore arrive with an already consumed FD. The native
adapter drains at most eight acquisitions per frame instead of invoking that
loop. This source observation explains the invalid-fence/double-close failure
shape; it does not identify the headset vendor library's exact source revision.

Unexpected invalid/error fence events disable further acquisition and retain the
reader/image without closing or retransferring an untrusted FD. Writer shutdown
still runs. This rare quarantine deliberately preserves resources rather than
recycling a buffer with unproven synchronization. It is reported by an explicit
`acquire-fence-quarantined` marker and is not a recovery or frame-success claim.
Host tests exercise distinct image/FD pairs, exact-once retirement, unsignaled
retention, the drain bound and invalid-event quarantine. Device keyboard/dialog
stress remains required to verify the affected runtime.
