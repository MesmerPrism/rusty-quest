# Native hybrid panel

`ui.native_mesh_panel.hybrid` is an explicit addition to the original-product
mesh panel. It retains the original application Activity, forms, text editing,
keyboard, settings handlers and private particle renderer. It adds no Makepad,
Spatial SDK, second OpenXR session or Vulkan device.

The native toolbar draws rounded shapes and an independently authored compact
stroke alphabet directly in the existing eye passes. FORMS changes only the
presentation of the Android form texture; CENTER places the panel in front of
the current valid head pose; SIZE changes physical panel dimensions through the
existing presentation state. Original panel visibility shortcuts still hide
both forms and toolbar. Toolbar actions do not start recording or change
particle settings.

The renderer batches at most 1,024 primitives into one instanced draw per eye.
Its two fixed coherent instance buffers are updated only after their owning
frame-slot fence retires. Graphics resources remain on the existing device and
are destroyed only after caller-owned frame retirement. Geometry is rebuilt
when hover, press or form visibility changes. Translucent vector edges use
premultiplied alpha; the original color-only overlay and scene-depth policies
remain distinct.

## Logical size and raster density

Original layout, input and geometry metadata remain 1,024 by 768 logical units.
The hybrid selection initially renders the Android surface at 1,536 by 1,152
pixels. The producer supports bounded 1x, 1.5x and 2x startup tiers. Logical
wrappers scale the original content tree, modal content and keyboard without
changing the resource density or replacing application callbacks. Physical
panel size is controlled independently by its pose, not by raster dimensions.

`native_panel_resolution` also supplies a bounded recommendation policy with
hysteresis and a minimum dwell interval. This version does not replace a live
image reader or automatically apply those recommendations. Live resizing would
need explicit producer/image and GPU-fence retirement; a recommendation is not
an adopted resolution.

Higher raster density costs image memory and sampling/producer work. Direct
vector UI remains limited by the final eye-buffer resolution and foveation.
Neither architecture alone establishes faster rendering or better readability.

## Validation

Host tests cover pointer capture/cancellation, generation-bound presentation
actions, recentering, raster policy and geometry bounds. Both shaders must
compile and the Android producer must typecheck. Device qualification must join
the exact APK and current actor with both-eye pixels, native toolbar hover and
press/release, original form navigation, modal/keyboard operation, hidden-form
pointer behavior, original visibility shortcuts and advancing particle frames.
Compare performance with the original panel on the same target and workload.
Creation, action admission and queue-submission markers are separate from GPU
completion, XR presentation and visible-pixel proof.
