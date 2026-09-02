# Spatial Camera Raw Projection Freshness

The Spatial Camera Panel owns a periodic app marker for Raw Projection camera
freshness. The marker proves only app-side source advancement, imported-buffer
advancement, command-buffer camera drawing, and the selected cadence owner. It
does not prove OpenXR compositor display or wearer visibility.

`tools/Reduce-SpatialCameraPanelRawProjectionFreshness.ps1` consumes one
bounded UTF-8 launch log and produces one CreateNew
`rusty.quest.camera_hwb_projection_freshness_reduction.v1` document. The caller
must supply the exact launch challenge, layer generation/switch count, camera
run generation, cadence session generation, and expected cadence authority.
The managed capture must append exactly one final canonical boundary,
`schema=rusty.quest.camera_hwb_projection_freshness_capture.v1 captureComplete=true logCount=1`,
after its one bounded log stream closes. Missing, duplicate, non-final, malformed,
or multi-log boundaries and any partial receipt-family marker reject before
reduction.
The reducer requires at least two contiguous receipts separated by 300 present
ordinals, checks the complete monotonic stereo frame/timestamp/HWB-import
chain, and rejects Raw deselection, authority drift, any live JNI layer-fence
removal or failed update regardless of token order, layer switching, app-owned
denial/failure/session-loop markers, Android fatal markers, non-canonical
integers, unknown fields, duplicate fields, or detached receipt chains.

The accepted visibility scope is exactly
`app-command-buffer-not-wearer-visible`. A later attended check remains the
only authority for wearer-visible acceptance. A host transaction must bind the
input bytes and reducer bytes, retain the reducer output, and adjudicate device
cleanup independently from semantic freshness.
