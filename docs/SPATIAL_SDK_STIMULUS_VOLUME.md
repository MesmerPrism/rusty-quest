# Spatial SDK fixed-phase stimulus volume

Status: source implementation under MOD-009; host validation is required and
device launch is forbidden in this unit.

## Scope

This adapter brings the public native OpenXR/Vulkan stimulus-volume visual
kernel to the Meta Spatial SDK custom-material path. It is intentionally not a
literal renderer port: Spatial SDK owns the frame loop, scene, and material
submission, so the app uses a fragment material on an oversized viewer-relative
opaque carrier rather than the native compute image and fullscreen projection
pass.

The first slice is fixed phase and nonflashing. It preserves the recognizable
dual-source interference field, two-octave value noise, fixed density samples,
pinch/domain warp, black threshold, and near/mid/far depth ramp. It does not
copy the native frame-count timing assumption, randomized frequency range, or
unenforced autostart/duration fields.

## Authority

- Rusty Optics owns profile, temporal, safety, volume, and presentation
  semantics. The canonical source profile is
  `stimulus.profile.volume_only_bright_interference`.
- The Spatial Camera Panel owns only property adaptation, closed-world
  activation, custom-material parameters, scene resources, cleanup, and
  effective-state markers.
- Meta Spatial SDK owns rendering and the application frame loop. This route
  does not call or emulate `xrWaitFrame`, `xrBeginFrame`, or `xrEndFrame`.

The Optics profile requests 32 volume steps. This mobile fragment adaptation
uses 16 statically bounded samples and reports both values; it does not claim
compute or pixel parity.

## Closed-world activation

The feature lock remains `enabled=false`. Source presence is inert. An
activation request is accepted only when all of these app-scoped properties
are present:

- `debug.rustyquest.spatial.stimulus_volume.enabled=true`
- `debug.rustyquest.spatial.stimulus_volume.profile_id=stimulus.profile.volume_only_bright_interference`
- `debug.rustyquest.spatial.stimulus_volume.phase_mode=fixed-phase`
- `debug.rustyquest.spatial.stimulus_volume.temporal_enabled=false`
- `debug.rustyquest.spatial.stimulus_volume.autostart=false`
- `debug.rustyquest.spatial.stimulus_volume.safety_acknowledged=true`

The optional `debug.rustyquest.spatial.stimulus_volume.hold_ms` is clamped to
2,000–30,000 ms and defaults to 20,000 ms. Missing or invalid safety inputs,
another profile, temporal/strobe modes, temporal enablement, and autostart all
fail closed and emit an inert marker.

This property route is an adapter, not profile authority. A future attended
wrapper must snapshot and restore the complete property manifest and must not
reuse these fixed-phase properties as authorization for temporal content.

## Presentation adaptation

The canonical presentation is `StereoEyeField`, `FullViewport`,
`SharedFieldBothEyes`, and `ViewLocked`, with an opaque background. The
Spatial route creates a large opaque double-sided quad 0.62 m ahead of the
viewer, updates its pose from the viewer each scene tick, uses the actual
per-eye center for ray construction, bypasses scene depth testing, and does
not write depth.

This is a carrier candidate, not device proof. A later fixed-phase attended
gate must confirm both-eye coverage, stability during rotation and
translation, absence of visible quad edges, comfort, cleanup at the bounded
hold, and acceptable GPU behavior before the carrier is called equivalent to
the native fullscreen projection.

## Evidence markers

The consuming runtime emits `channel=spatial-stimulus-volume` markers for
`inert`, `start`, `effective`, `render-ready`, `complete`, `failed`, and
`cleanup`. The effective marker names Optics authority, fixed zero phase,
disabled temporal modulation/autostart, 16 implemented versus 32 requested
samples, viewer-relative carrier adaptation, and `deviceVisualProof=false`.

Markers prove CPU-side route and resource adoption only. They do not prove
fragment execution, visible coverage, comfort, timing, display scanout, or
visual parity.

## Safety and later work

MOD-009 forbids live-device operations. It also forbids adding a clock,
advancing phase, randomized frequency, temporal mode, or autostart. A later
temporal unit, if explicitly requested, must consume a validated Optics run
plan, use monotonic elapsed time instead of a nominal frame rate, enforce black
lead-in/duration/start-gate/acknowledgement limits, provide an immediate stop
route, and undergo separate safety and attended validation.
