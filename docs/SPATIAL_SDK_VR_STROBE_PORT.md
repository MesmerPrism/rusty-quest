# Spatial SDK VR Strobe Port

The Spatial Camera Panel contains an app-local, default-disabled Spatial SDK
port of Trevor Hewitt's `vr_strobe` portal. The source authority is pinned to
commit `52c71cc069f4102bc4148e05c5fd3fc4d5466479`. Creator permission to release
the port under `AGPL-3.0-or-later` is bound by the MOD-010 permission receipt
and the app's `THIRD_PARTY_NOTICES.md`.

## Activation and safety

The feature is absent from the scene by default. Registering its panel and
packaging its shader do not activate it. A run must explicitly set:

```text
debug.rustyquest.spatial.vr_strobe.enabled=true
```

That property only exposes the warning panel. It cannot start either visual
mode. Every focused app session requires all of the following:

1. The panel opens on the photosensitivity warning.
2. The user deliberately presses `I UNDERSTAND — CONTINUE`.
3. The panel transitions to stimulus selection.
4. Selecting a preset is the explicit Begin gesture. A custom designer remains
   inert until `START CUSTOM STIMULUS` is pressed.
5. The renderer shows a 500 ms black lead-in before starting the profile.

Restored UI state, properties, intent data, panel creation, and application
launch cannot satisfy either gesture. The acknowledgement remains valid while
the app stays focused, so Stop can return to selection without repeating the
warning. A selected stimulus has no automatic time limit: it continues until
explicit Stop, focus loss, session withdrawal, or activity destruction. Focus
loss, explicit session end, and activity destruction invalidate the warning
acknowledgement. Every stop route destroys the full-field carrier.

This feature can expose users to rapidly changing light and high-contrast
motion. It is experimental visual software, not a medical device or treatment.
Original preset names are displayed as source attribution labels only.

## Port surface

The portal includes the pinned source's five simulation presets, four temporal
strobe presets, interference designer, and temporal designer. The exact five
base64 simulation payloads are retained and decoded by a pure Kotlin codec.

The interference profile maps:

- two or three colors and color oscillator;
- scale, shear, offset, shake, rotation, and step shaping;
- trail, blur, glow, brightness, and contrast;
- noise and vignette controls;
- up to eight stripes, ripples, rays, and Perlin fields, including the source
  distortion, wave, pivot, extent, rotation, and movement parameters.

The temporal profile maps two colors, 0.1–120 Hz frequency, 1–99% duty,
white/Perlin phase noise, 1–50 px noise resolution and amplitudes, fixation
color, and 2–100 px fixation size. Source-compatible duration fields remain in the decoded data
model but do not control runtime completion.

Trevor's pinned `sim.html` editor bounds remain the outer compatibility
envelope, but the Quest Randomize action uses the narrower
`quest-reliable-v3` distribution. The raw browser distribution is not a mobile
viability guarantee: it makes vignette center and edge both `5` in most runs,
which gives GLSL `smoothstep` equal edges, and combines already-bright colors
with `-1…1` brightness and `0…3` contrast, which frequently clips to a flat
field. The Quest envelope uses separated bright palettes, bounded
brightness/contrast, at most one active distortion branch and one wave branch,
at most three active patterns, and valid positive-width or disabled vignettes. The shader also rejects an
equal-edge vignette from older stored profiles. Temporal randomization stays
inside the declared `strobe.html` controls but uses 1–30 Hz, 20–80% duty, and
bounded optional noise. Spatial detail uses a mixed distribution rather than a
low-frequency clamp: each spatial control gives the fine band 60% selection
weight, up to global scale 16, stripe/ripple and ray period 50, or Perlin scale
40. These changes do not add pattern evaluations or relax the
branch-count, color-separation, brightness, contrast, or vignette guards.

## Touch controller route

The VR Strobe feature consumes its controller shortcuts before the camera-panel
application's unrelated projection and private-panel controls:

- right A randomizes the currently selected interference or temporal stimulus;
- left X, the left controller primary button, stores an exact app-private copy of the currently active
  profile;
- right B hides or shows the UI panel without changing stimulus output;
- a left or right thumbstick horizontal flick selects the previous or next
  entry in the ordered nine-preset catalog and restarts it after the 500 ms
  black lead-in;
- in flat mode, dominant vertical movement on either thumbstick changes the
  view-relative stimulus distance from 1.05 m to 4.00 m;
- in curved mode, right-thumbstick vertical movement retains that distance
  authority while left-thumbstick vertical movement changes concavity. Up
  increases concavity and down decreases it.

Right A is deliberately excluded from the Compose panel's accepted input-button
mask and consumed by the Compose root's preview-key policy before a focused
control can receive it. More importantly, the active panel exposes no
Pause/Resume action or callback, and the safety state machine has no paused
state for a leaked controller click to invoke.
Hiding the panel clears focus, disables the backing root view, hides the native
`PanelSceneObject`, and hides the ECS entity; `Visible(false)` alone does not
isolate all panel input surfaces. Right A therefore remains a global strobe
shortcut. Right-trigger selection remains available for pointed panel
interaction and does not own a Store route.
Every successful randomize preserves the current safety state and output
lifecycle while publishing exactly one new stimulus revision.

Whenever right B shows the panel, the app first recomputes its pose from the
current viewer pose at the comfortable, trigger-tested 0.82 m distance. The
previous 0.22 m physical-foreground experiment rendered over the carrier but
was rejected in attended testing because it was uncomfortably close and lost
trigger clicks. The carrier now starts at the controller range maximum of
4.00 m and retains its 2.84 m radius, moving its central surface behind the
panel over the panel's field of view.
The selected carrier remains active when the panel opens, and joystick distance
is clamped to a 1.05 m minimum so it cannot return to the old in-front
placement. A successful preset, designer, or stored-profile Begin still closes
the panel immediately. The dedicated panel layer
retains z-index 100 and explicit `OPAQUE` blending, while the stimulus uses
`DepthTest.LESS_OR_EQUAL` with depth writes enabled.

One app-owned coordinator is the sole right-A **action authority**, but it can
observe the physical press through either the same Android key/motion path that
makes B reliable or the Spatial SDK controller snapshot fallback. The first
observed edge dispatches randomize and a 120 ms cross-route window suppresses a
duplicate observation; neither platform path owns a second randomize callback.
This distinction matters: making one unreliable observation source the sole
ingress previously discarded otherwise valid Android A events. A Spatial SDK
controller sample is valid when the
stable local component identifies a right `CONTROLLER` attachment or the local
avatar exposes a right `CONTROLLER`; Meta's transient `isActive=false` flag is
not treated as absence. This matters because current headset evidence showed
valid A/button state on the stable local component while that flag was false.
A and left X use independent snapshot-side physical press latches: only valid
right- or left-controller samples can re-arm their respective fallbacks, and
release must remain visible for 60 ms. Android key and motion routers independently emit rising
edges. Every accepted A edge receives a press ID and
emits dispatched, randomize-start, renderer-update-submitted,
randomize-complete, later-frame-boundary, and press-complete markers carrying
the same source/transaction lineage. The frame marker proves only that the app
crossed a later scene tick; attended observation remains the visible-adoption
proof.
Thumbsticks retain a 0.72 horizontal flick threshold, a 0.25 release threshold,
a 0.25 vertical dead zone, release-to-rearm, dominant-axis selection, and a
250 ms horizontal-action debounce.

Panel selection, horizontal preset cycling, and right-A randomization all use
one app-owned selection authority. It records the exact catalog index and a
monotonic stimulus revision. Randomization preserves that selected preset
identity, and the next horizontal flick continues from the same catalog index
rather than from stale panel state.

Stored profiles use a versioned app-local codec and Android app-private
preferences, so their exact interference or temporal parameters, spatial
distance, carrier mode, and concavity survive an app restart. Codec version 3
reads existing version-1 records as flat carriers and scales version-1/2
distances by the same factor of two used by the enlarged carrier, without
repeatedly scaling rewritten data. The panel exposes the stored list from both selection and active
screens. Loading is routed through the same coordinator as controller actions:
it still requires the current warning acknowledgement, destroys any current
carrier, and starts the saved profile with the normal 500 ms black lead-in.
Panel Store and left-X Store call the same persistence authority. Right trigger
remains reserved for pointed panel interaction.

The human-readable interchange schema is
`rusty.quest.spatial_vr_strobe.profile_bundle.v1`. The app validates a staged
JSON import before transactionally replacing its app-private list, then mirrors
the effective list to an app-private export file. The flat browser editor in
`apps/spatial-camera-panel-android/profile-editor-web` validates the same
profile vocabulary and bounds; it never reads or writes SharedPreferences
bytes. `tools/Invoke-SpatialVrStrobeProfileTransfer.ps1` is the serial-scoped
CLI-equivalent route for validate, import, export, and explicit list reset.

The editor deliberately retains Trevor's familiar portal and designer
structure. Its landing page contains the pinned source list in the original
order: five simulation cards, four temporal-strobe cards, then the simulated
and real design-page cards. Simulation editing keeps the full-canvas preview
with a 400-pixel right control rail and Trevor's Colors, Color Animation,
Global Transforms, Post Processing, In-Shader Effects, and pattern-family
sections. Temporal editing keeps the compact upper-left STROBE/CONTROLS panel.
Quest-only distance, curved-carrier, concavity, import, and download controls
are additive and do not replace those source navigation landmarks.

The default downloadable bundle contains all nine pinned profiles using the
same stable IDs as the Quest portal catalog. Browser migration inserts any
missing pinned profile, preserves edited pinned profiles and additional user
profiles, and removes only the untouched generic starter created by the first
editor revision. `Restore original set` replaces the nine pinned entries with
their exact Quest-sanitized source forms while retaining additional profiles.
The interference payloads remain tied to source commit
`52c71cc069f4102bc4148e05c5fd3fc4d5466479`; values outside the Quest shader's
declared bounds, such as an older ray period above 50, are clamped by the same
rules as the Kotlin source decoder before export.

Start the browser editor locally:

```powershell
pwsh -File tools/Start-SpatialVrStrobeProfileEditor.ps1
```

Move the effective Quest list into the browser, or replace it from a browser
download:

```powershell
pwsh -File tools/Invoke-SpatialVrStrobeProfileTransfer.ps1 -Action Export -Serial <quest-serial> -OutPath .\quest-profiles.json
pwsh -File tools/Invoke-SpatialVrStrobeProfileTransfer.ps1 -Action Import -Serial <quest-serial> -BundlePath .\rusty-vr-strobe-profiles.json
```

Import is whole-list replacement, not an append. The script removes only its
own staging file, cold-starts the named strobe package, and accepts the result
only after the app publishes a matching effective export. `-Action Reset`
requires `-ConfirmReset` and uses the same validated empty-bundle transaction;
it does not clear app data.

The renderer is a Meta Spatial SDK custom material compiled to the headset's
Vulkan shader path; it does not generate the stimulus on the Kotlin CPU. The
full-field carrier is one 2.84 m-radius radial disc with 16 rings and 48 angular
segments, not a box, and remains one material draw. Its 4.00 m default distance
is the right-stick range maximum. Flat mode bypasses bending. Curved
mode maps the disc in both X and Y onto a spherical bowl in the vertex shader,
preserving the original flat stimulus coordinates while left-stick changes
update one uniform rather than rebuilding geometry. A new session defaults to
curved mode at maximum concavity: the full 180-degree hemisphere whose rim
curves toward the viewer. At minimum concavity it is a flat circle. The
coordinator packs only active patterns and sends one count per pattern family,
allowing each bounded eight-slot shader loop to terminate before unused slots.
The counts are submitted after their active slot payloads and act as the
shader-side active-range commit; stale trailing slots are never evaluated.

Randomization updates the one material already bound to the visible carrier and
does not replace the scene object's mesh. This deliberately rejects the former
standby-mesh assumption: if Meta did not publish that mesh assignment while the
Kotlin references still swapped, per-frame time updates targeted an invisible
material and the visible stimulus appeared paused until a later press. The
bounded active-material delta retains one visible draw, keeps the time uniform
on the visible target, and increments a renderer revision. A full profile clear
would submit 175 attributes and the previous randomize path followed it with
another complete 160-slot rewrite. The interactive path now submits nine
attributes for a temporal profile or at most 27 for the guarded three-pattern
interference profile; the full clear runs once at material creation. Runtime
markers distinguish accepted input and selection revision, host update
submission, and a later scene-frame boundary. Neither a host-side mesh
assignment nor completed `setAttribute` calls are called visible renderer
publication.
Candidate markers also report the effective randomization envelope, its
acceptance result, costly branch counts, and the host transaction duration.

The browser renderer's feedback-buffer trail is represented without an
offscreen swapchain or persistent render target. The Quest path now evaluates
the complete procedural interference signal exactly once per fragment. Trail
and blur use bounded palette-domain softening, and glow reuses the resulting
color; the previous revisions evaluated the signal three and, before that,
seven times when post effects were active. Derivative-based band limiting
softens sub-pixel square-wave edges, while a decorrelated interleaved-gradient
hash replaces the mobile-visible post-noise lattice. This is an
intent-preserving mobile approximation, not pixel-identical browser feedback.
While a stimulus is selected, the app asks Spatial SDK for
`PerformanceLevel.BOOST_HINT`; Stop, focus loss, warning withdrawal, failure,
and activity destruction return it to `SUSTAINED_HIGH`. The request and SDK
result are emitted as runtime markers, rather than treating an Android debug
property readback as proof of the effective performance level.
Host shader compilation proves the contract, while headset GPU timing and
visual fidelity remain attended evidence.

## Authority and evidence

`rusty.quest.spatial_vr_strobe.adapter.v1` is app-local. Optics owns
stimulus-profile and safety-presentation policy; the Spatial app owns panel and
renderer adoption. No contract is promoted into clean Optics or Lattice core
code by this port.

Runtime markers use `channel=spatial-vr-strobe` and distinguish panel enable,
safety state, profile application, controller actions, distance changes,
carrier creation, failure, and cleanup. Host validation does not prove headset temporal accuracy, frame
pacing, comfort, or visual parity.

## Host validation

From the `rusty-quest` repository:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/checks/Test-SpatialVrStrobeStatic.ps1 -RepoRoot .
```

From `apps/spatial-camera-panel-android`, run the focused JVM tests and the
Spatial shader compiler. Attended MOD-012 validation names one Quest target,
uses serial-scoped ADB, verifies warning → selection → immediate preset start,
then checks single-edge A randomization, left-X store, stored-list load
after an app restart, B panel visibility, both-stick horizontal preset cycling,
flat/curved switching, left-stick concavity, right-stick distance movement,
explicit-stop persistence, and focus-loss
cleanup with real Touch controllers. It captures bounded GPU/frame and fatal
evidence and stops immediately on discomfort. Host checks alone do not
establish a performance improvement.

Browser contract tests run with:

```powershell
node --test apps/spatial-camera-panel-android/profile-editor-web/tests/*.test.mjs
```
