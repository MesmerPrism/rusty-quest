# Hosting the production panel in the native mesh

The native mesh can host the retained `BreathCompositionPanelModule` Activity's
actual view hierarchy. Its existing page builders, listeners, browser/network
adapters, Polar and LSL runtime, permissions, and session shell remain the product
owners. This route selects `breath-composition-controls` with the product's full
feature lock. The separate `experiment-session-owner` panel is a different source
selection and does not provide production-panel feature parity.

The normal product launch must first create the genuine generated
`ControlPanelActivity`. The existing immersive handoff retains that Activity and
task. `attachNativeMeshSessionControls` then moves its content into the native
producer's carrier without creating another Activity, coordinator, clock,
recording writer, or native session. A missing, destroyed, or finishing retained
owner fails explicitly; no replacement panel is synthesized.

All content rebuilds continue through the original Activity's `setContentView`.
While attached, the presentation adapter routes the new hierarchy into its
current carrier. Returning to the real Activity routes it back into that
Activity's window. Destroying either host retires the attachment. Existing
permission and system-prompt callbacks retain the real Activity identity.

Return to VR requests hidden mesh visibility through the current native producer
generation. The request is admission only. The adapter accepts visibility only
from an exact `rusty.quest.native_mesh_ui.panel_visibility.v1` submitted-frame
receipt. The existing session Start/resume handoff retains all native session,
condition, generation, epoch, profile, binding, and immersive-presentation guards,
and additionally waits for the mesh's hidden-frame acknowledgement. It never
turns visibility into a fabricated immersive-focus or wearer-confirmation receipt.
Native menu recall uses the same visibility owner; effective visibility resumes
the original panel's presentation refresh loops.

The actual Activity also registers its `ProductPanelOperator.Owner` on creation
and retires that registration on destruction. Its typed adapter shares the
existing navigation, scrolling, particle submission/effective readback, BLE
settings and network owner APIs. A retained Activity is eligible while its
current immersive host is resumed; a stored Activity reference alone is
insufficient. General status excludes controller keys, relay tokens and temporary
network passwords. Enrollment and join details return sensitive values only as
the explicitly requested operation result. The particle patch envelope and
submission path are shared with the original widgets, and a queued reply remains
distinct from the renderer's matching effective revision.

Host compilation and pure readback/handoff fixtures do not establish Android
Activity transfer, system-prompt appearance, Bluetooth/network effects, input
reach, or headset acceptance. Those require the complete selected product and
attended validation.
