# Native hybrid panel

`ui.native_mesh_panel.hybrid` is an explicit addition to the original-product
mesh panel. It retains the original application Activity, forms, text editing,
settings handlers and private particle renderer. It adds no Makepad,
Spatial SDK, second OpenXR session or Vulkan device.

The native toolbar draws analytic rounded shapes and platform-shaped SDF text
directly in the existing eye passes. A compact stroke alphabet remains a bounded
fallback when platform text is unavailable. FORMS changes only the
presentation of the Android form texture; CENTER places the panel in front of
the current valid head pose; SIZE changes physical panel dimensions through the
existing presentation state. DETAIL cycles Auto, 1x, 1.5x and 2x form density
without changing physical size or application settings. Original panel visibility shortcuts still hide
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
pixels. Auto selects bounded 1x, 1.5x and 2x tiers from both eyes' projected
footprint, holding invalid, near-plane-crossing or offscreen observations.
Automatic changes require twelve stable frame observations, twenty-percent downshift
hysteresis and a minimum 120-frame dwell after adoption. Logical
wrappers scale the original content tree and modal content without
changing the resource density or replacing application callbacks. Physical
panel size is controlled independently by its pose, not by raster dimensions.

The existing VirtualDisplay changes its backing Surface and dimensions while
retaining the Activity, display identity, Presentation and original widget tree.
Density DPI, logical layout and physical size stay independent. A handoff waits
while an editor, keyboard, modal, selection action, pointer gesture or paused
owner is active. Cached modal wrappers update their scale when shown again,
deriving dimensions and spacing from original logical values to avoid rounding
drift across repeated tier changes.

Raster epochs are separate from the application control generation. At most one
new reader is staged; another change waits for prior reader images to retire.
Every retained image owns its reader through the existing Vulkan import and
frame-fence lifetime. The old texture stays drawable until a valid new image is
imported. An uncertain handoff retains resources and disables further changes.
Request, writer acknowledgement, image adoption and queue submission are distinct
observations; none alone proves visible pixels.

Higher raster density costs image memory and sampling/producer work. Direct
vector UI remains limited by the final eye-buffer resolution and foveation.
Neither architecture alone establishes faster rendering or better readability.

## Cost observations

With the existing timestamp diagnostics enabled, native panel draw regions have
separate left/right GPU query spans and a paired marker. These command-region
spans can contain overlapping prior work and are already inside projection
composition; they must not be added to it as incremental shader cost. Periodic
CPU preparation markers include acquisition, import, policy and toolbar updates.
Compare fixed tiers in the same app, with equal scene and eye-buffer settings,
and keep captures outside the idle timing windows.

Raster handoffs retain the original widget tree, edit values and scroll offsets.
The root form keeps an exact logical 1024×768 viewport throughout physical
display resizing; modal wrappers retain their own logical dimensions.
The writer acknowledgement waits for stable scroll, completed layout, restored
offsets and a hardware frame commit. New pointer/editor actions are deferred
only during the mutation; pending modal and IME input remains usable. This
submission barrier still requires headset pixel qualification.

## Meta system keyboard

The hybrid APK declares optional `com.oculus.feature.VIRTUAL_KEYBOARD` and
`oculus.software.overlay_keyboard` features. The selected manifest is the
capability declaration; no additional runtime property enables this route.
A focusable proxy attached to the real Native Activity requests the Meta system
IME and forwards input to the original form's `EditText`/`InputConnection`.
The original editor retains text, filters, selection, composition and actions;
there is no duplicate edit buffer or selected custom keyboard UI.
Monitored extracted-text updates return through the served proxy so Meta's
mirrored text stays synchronized with the original field, including edits that
keep the same length. Editor focus changes and connection retirement invalidate
the bridge; ordinary window-focus loss to the keyboard does not.

The existing focus-aware Activity allows the system overlay to own input while
the native XR renderer continues submitting when the runtime permits it.
Keyboard dismissal and editor retirement must invalidate the old connection.
A show request or manifest capability is not proof that the system keyboard is
visible or that input reached the original editor; qualify both on the headset.

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

## Native text, presentation widgets and attachments

The selected hybrid toolbar shapes its static label inventory once through
Android TextShaper and per-glyph platform fallback fonts. Padded glyph coverage
is converted to a bounded monochrome signed-distance atlas at runtime. No font
files are bundled or redistributed. The native Vulkan text pipeline uses two
fixed fence-owned R8 atlas/upload/instance slots and bounded text ranges per pane in each eye;
unchanged generations never upload again. Layout retains shaped advances,
metrics and offsets. Color fonts, missing glyphs and malformed inputs fail
explicitly; this is not complete Unicode or color-emoji support.

Native glyph coverage uses the UV-derived pixel footprint rather than derivatives
of sampled distance values, which can collapse around thin stems. Four half-pixel
coverage samples are averaged within each glyph's half-texel atlas bounds. This
changes only text reconstruction; atlas allocation, shaping and form sampling
remain independent. The extra samples have a bounded cost per glyph fragment,
but require headset quality and frame-timing qualification. Very small projected
text still has a physical pixel limit; this is not analytic outline rendering
or temporal antialiasing.

SCALE changes only physical panel width, using absolute values from the existing
presentation owner. A drag retains its initial plane so resizing does not feed
back into its own coordinate mapping. The meter below DETAIL displays effective
raster budget and the FPS readout counts actual renderer preparation frames.
These are observations, not requested settings or incremental GPU-cost claims.

The WORLD/WRIST control attaches the panel to a currently tracked left wrist;
NO HAND marks unavailable tracking. The same predicted time and reference space
as the OpenXR input path supply valid and tracked position and orientation.
Loss holds the last pose and cancels input; explicit WORLD or CENTER can recall
the panel using a valid pointer. CENTER and the original reset atomically return
to world placement. Android forms and native rows use shared panel geometry and
capture primitives; logical coordinates remain independent of raster density.
Multi-panel hit selection is bounded, but this feature does not create multiple
independent Android form owners or replace the application's scene engine.

Tracking loss, focus loss, hidden owners and competing capture cancel gestures.
Recovery requires a valid release; an input arbiter may transfer only its own
observed-open/fresh-press proof when switching between hands and controllers.
A held gesture cannot become a new form press through pointer-owner changes.

Qualification must include platform shaping and mask comparison, both-eye text
pixels, native dragging and attachment changes, original forms/Meta input,
repeated tier changes and resource retirement. Host checks alone do not establish
headset readability, physical-input parity, endurance or performance.
INFO opens an optional native STATUS pane at an independent world pose. It shares
the immutable atlas and fence-owned instance buffers with the main toolbar; each
pane draws only its own vector and text ranges. The full nearest panel body
consumes input before controls are selected. Complete pane groups render in
per-eye order for parallel planes. Intersecting nonparallel transparent panes
remain unqualified. Closing INFO never creates or retires an Android form owner.
The auxiliary submission marker records its slot pose; pixels require separate
headset qualification.

The attachment button names its next action: TO WRIST from world placement and
TO WORLD from wrist placement, including tracking-loss recall. Original Android
forms retain their single-mip AHB path; their fragment shader integrates a bounded
2x2 derivative footprint up to two texels per pixel and 4x4 above that,
retaining one bilinear tap without minification.
This targets thin-border dropouts without changing original widget styling or
text input. Steep minification beyond four texels per pixel remains unqualified.
UI aim admission is independent of complete visual-rig validity: an unavailable
finger joint may suppress the hand mesh while a separately valid wrist/aim still
provides a pointer. Invalid wrist/aim input remains rejected.

On tiled GPUs, timestamp pairs inside a render pass can measure one tile or
approach zero. The panel draw-region timestamp is not evidence of total fragment
work or isolated incremental UI cost. Use frame timing and an appropriately
controlled workload for regression checks; overlapping scopes are not additive.
See https://github.khronos.org/Vulkan-Site/features/latest/features/proposals/VK_QCOM_elapsed_timer_query.html.
