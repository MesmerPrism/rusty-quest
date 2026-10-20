# Native form presentation

`ui.native_mesh_panel.native_page` explicitly extends the hybrid panel with
native vector and platform-shaped text drawing for the form area. It depends
on `ui.native_mesh_panel.hybrid`; selecting the hybrid toolbar alone does not
select this mode. The original 2D app and sampled-form presentation remain
independent alternatives.

The retained Android view tree remains attached and owns layout, settings,
listeners, enabled state, scroll and the served editor. A main-thread adapter
publishes bounded immutable drawing snapshots. The Vulkan consumer draws the
snapshot's rounded shapes and shaped glyphs directly in the existing eye
passes. A valid native page suppresses the form's sampled color draw; it does
not create another application state, XR session, graphics device or keyboard.

Snapshots carry producer, content, raster, revision and atlas identities.
Coordinates remain in the original 1024 by 768 logical viewport. Ordered
shape/text batches retain separate logical clipping regions, including scroll
clipping. Page text uses its own bounded atlas and fence-owned GPU slots,
independent of the toolbar. Geometry-only changes can reuse shaped text.
Transient editor content is not part of diagnostic markers.

Pointer events continue through the original controls. Meta system keyboard
input continues through the original editor's InputConnection, including its
filters, selection and composition. Native presentation must retain disabled
controls and scientific readiness rules. Unsupported or incomplete content
uses an explicit coherent original-texture fallback; a selected feature is
not proof that native form pixels were drawn.

Qualification requires actual both-eye form and particle pixels, scroll
clipping, original button/slider readback, Meta keyboard edits and cancellation
on lifecycle or content changes. Compare frame timing using actual workload,
eye extent and Android raster extent. Reusing buffers or reporting a small
inside-pass timestamp does not establish negligible fragment cost.

Focused compile-selection checks are available through
`pwsh -NoProfile -File tools/checks/Test-NativePageSelection.ps1`.
They reject ambient or mismatched native-page compile inputs while retaining
the original and hybrid selections. Host checks do not establish headset pixels.
