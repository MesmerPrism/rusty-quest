# Native Stimulus Volume Implementation

## Decision

The bright volumetric interference stimulus is implemented in the native Rusty
Quest renderer, not in Makepad. The active route is a Rust/OpenXR/Vulkan path
inside `apps/native-renderer-android/native`: Android properties select the
profile, OpenXR supplies the session and right-controller input action, Vulkan
compute generates a stereo storage image, and a fullscreen projection pass draws
that image into each OpenXR projection-layer eye.

The route is intentionally volume-only. It does not sample the camera HWB path,
does not depend on Hostess hotload payloads, and emits markers with
`makepadRuntime=false`, `hostessRuntime=false`, `volumeOnly=true`, and
`renderPath=native-vulkan-stimulus-volume`.

## GitHub Audit Anchors

Use these public-repo anchors for review after the branch lands:

- Native renderer app: https://github.com/MesmerPrism/rusty-quest/tree/main/apps/native-renderer-android
- Render loop: https://github.com/MesmerPrism/rusty-quest/blob/main/apps/native-renderer-android/native/src/xr_vulkan.rs
- Runtime property names: https://github.com/MesmerPrism/rusty-quest/blob/main/apps/native-renderer-android/native/src/native_renderer_properties.rs
- Stimulus settings parser: https://github.com/MesmerPrism/rusty-quest/blob/main/apps/native-renderer-android/native/src/native_renderer_stimulus_volume_options.rs
- Runtime options facade: https://github.com/MesmerPrism/rusty-quest/blob/main/apps/native-renderer-android/native/src/native_renderer_options.rs
- Stimulus GPU module: https://github.com/MesmerPrism/rusty-quest/blob/main/apps/native-renderer-android/native/src/gpu_stimulus_volume.rs
- Stimulus actions: https://github.com/MesmerPrism/rusty-quest/blob/main/apps/native-renderer-android/native/src/openxr_stimulus_actions.rs
- Shader build: https://github.com/MesmerPrism/rusty-quest/blob/main/apps/native-renderer-android/native/build.rs
- Runtime profiles: https://github.com/MesmerPrism/rusty-quest/tree/main/fixtures/runtime-profiles
- Runtime profile validator: https://github.com/MesmerPrism/rusty-quest/blob/main/tools/Apply-RuntimeProfile.ps1

External references:

- OpenXR input/action model: https://registry.khronos.org/OpenXR/specs/1.1/html/xrspec.html#input
- Vulkan compute shaders: https://docs.vulkan.org/tutorial/latest/11_Compute_Shader.html
- Vulkan synchronization examples: https://docs.vulkan.org/guide/latest/synchronization_examples.html
- WCAG flash safety background: https://www.w3.org/WAI/WCAG22/Understanding/three-flashes-or-below-threshold.html

## Runtime Profiles

Four profiles select the route:

- `fixtures/runtime-profiles/quest-native-renderer-solid-black-stimulus-volume.profile.json`
- `fixtures/runtime-profiles/quest-native-renderer-solid-black-stimulus-volume-balanced.profile.json`
- `fixtures/runtime-profiles/quest-native-renderer-solid-black-stimulus-volume-performance.profile.json`
- `fixtures/runtime-profiles/quest-native-renderer-native-passthrough-stimulus-volume.profile.json`

The solid-black profile is the pure-volume profile. It sets
`debug.rustyquest.native_renderer.render.mode=solid-black-stimulus-volume`,
disables camera/SDF/hand/environment-depth visual layers, enables
`stimulus_volume.enabled=true`, acknowledges the photosensitive-risk gate with
`stimulus_volume.safety_ack=true`, and randomizes temporal and spatial
oscillator frequencies in the `3.0` to `40.0` Hz range. It uses the limit
central-FOV quality tier:
`stimulus_volume.render_target=1024x1024x2-rgba16f`,
`stimulus_volume.raymarch_samples=18`,
`stimulus_volume.central_fov_fraction=0.72`, and
`stimulus_volume.gradient_smoothing=0.78`. It also clears projection-target
controls, joystick controls, and the projection-target breath bridge so stale
Breathing Room Android properties cannot enable PMB scale controls or
right-controller haptics in a volume-only stimulus run.

The balanced solid-black profile keeps the same render mode, visual profile,
safety acknowledgement, randomization range, central-FOV fraction, smoothing,
and disabled Breathing Room controls, but lowers the canonical workload to
`stimulus_volume.render_target=768x768x2-rgba16f` and
`stimulus_volume.raymarch_samples=12`. Use it for live GPU-budget A/B runs when
the limit-tier fixture is GPU-bound at normal refresh rates.

The performance solid-black profile keeps the same render mode, visual profile,
safety acknowledgement, randomization range, central-FOV fraction, smoothing,
and disabled Breathing Room controls, but lowers the canonical workload to
`stimulus_volume.render_target=512x512x2-rgba16f` and
`stimulus_volume.raymarch_samples=12`. Use it as the first high-headroom native
stimulus route for 120 Hz/high-clock exploration. The 2026-06-19 Quest 3S sweep
showed this tier running at the 120 Hz target with substantially lower app/GPU
time than the `768x768x2` tier, while both `512x512x2` and `768x768x2` hit 72 Hz
at normal clocks.

The native-passthrough profile keeps the XR_FB_passthrough layer available under
an opaque projection, but the projection layer does not alpha blend, so the
visible output is still the generated colored volume over black. Its default
fixture is a balanced quality tier at `768x768x2`, 14 raymarch samples, and a
0.78 central-FOV fraction.

## Properties

The stimulus settings are owned by `NativeStimulusVolumeSettings` in
`native_renderer_stimulus_volume_options` and are re-exported through the
`native_renderer_options` facade:

- `debug.rustyquest.native_renderer.stimulus_volume.enabled`
- `debug.rustyquest.native_renderer.stimulus_volume.profile`
- `debug.rustyquest.native_renderer.stimulus_volume.composition`
- `debug.rustyquest.native_renderer.stimulus_volume.pattern_family`
- `debug.rustyquest.native_renderer.stimulus_volume.render_target`
- `debug.rustyquest.native_renderer.stimulus_volume.raymarch_samples`
- `debug.rustyquest.native_renderer.stimulus_volume.central_fov_fraction`
- `debug.rustyquest.native_renderer.stimulus_volume.gradient_smoothing`
- `debug.rustyquest.native_renderer.stimulus_volume.randomize.enabled`
- `debug.rustyquest.native_renderer.stimulus_volume.randomize.min_hz`
- `debug.rustyquest.native_renderer.stimulus_volume.randomize.max_hz`
- `debug.rustyquest.native_renderer.stimulus_volume.safety_ack`

The validator rejects enabled stimulus profiles unless `safety_ack=true`, rejects
unknown stimulus properties, rejects `randomize.min_hz < 3`,
rejects `randomize.max_hz > 40`, and rejects
`randomize.min_hz > randomize.max_hz`. It accepts explicit storage-image target
tiers `512x512x2`, `768x768x2`, and `1024x1024x2`, clamps runtime raymarch
samples to 48, and requires central-FOV and smoothing profile values to stay in
their bounded ranges.

Stimulus render modes are also guarded in runtime option parsing: when
`render.mode` selects a stimulus-volume route, parsed projection-target settings
are replaced with disabled defaults. This makes the route robust against stale
device properties from prior Breathing Room launches.

## Same-APK Panel Candidate

The native renderer APK now includes a plain Android 2D
`ControlPanelActivity`. It is deliberately not a Spatial SDK, WebView, Compose,
or Makepad surface. The panel writes a low-rate candidate into app-private
storage:

```text
stimulus_volume_candidate.json
```

The candidate schema is `rusty.quest.stimulus_volume.profile.v1`. It can select
the stimulus composition, active request state, safety acknowledgement, render
target tier, raymarch samples, central-FOV fraction, gradient smoothing, pattern
family, and randomize Hz range. The panel cannot write Android system
properties and does not directly mutate renderer state. On startup the Rust
`NativeActivity` reads the file through `AndroidApp::internal_data_path()`,
rejects damaged or unsafe candidates, maps accepted values into
`NativeStimulusVolumeSettings`, disables stale Breathing Room controls for the
volume-only route, emits a `stimulus-panel` marker, and writes:

```text
stimulus_volume_status.json
```

with schema `rusty.quest.stimulus_volume.apply_status.v1`.

This is a startup-effective proof slice. Future browser-like editing should
reuse the same candidate/status schema through a same-process JNI or command
queue adapter and apply only at a safe frame boundary. The renderer should not
poll panel files or WebView state inside the Vulkan command-recording hot path.

The first in-VR panel affordance is a right-controller trigger toggle. The
native OpenXR action set binds `/user/hand/right/input/trigger/value` for
Oculus/Meta Touch controllers and uses the simple-controller select click only
as a fallback. On a rising trigger edge, Rust sends a JNI intent to
`ControlPanelActivity` with the panel toggle action. The first trigger opens
the panel; a second trigger closes it if Horizon OS keeps the immersive
OpenXR action stream active while the panel is visible. The panel also has a
Close button for exclusive/focus-shift modes. A/right-primary remains the
stimulus randomize action and is not reused for the panel.

## Pattern Vocabulary

The current volume shader ports a Trevor Hewitt-inspired browser pattern
vocabulary into shader-native 3D fields. It does not import browser canvas code
or generate a 2D image plane. Instead, `pattern_family` selects a compact family
name that both a future browser editor and the Quest shader can share:
`randomized-trevor-vocabulary`, `trevor-mix`, `stripes`, `ripples`, `rays`,
`checker`, `spiral`, and `noise-field`.

The default `randomized-trevor-vocabulary` value lets the A/right-primary
randomizer choose among the concrete families. The shader also carries a small
browser-portable warp vocabulary: mirror mode, twist, pinch/bulge, scramble,
jumble, stretch, spatial oscillator frequencies, temporal frequency, phase
offsets, noise scale, and depth warp. These names intentionally match controls
that can be represented in a browser UI, while the native renderer evaluates
them as volumetric fields inside the central-FOV raymarch.

The startup dynamics default is a saved in-headset randomization labeled
`headset-randomize-count-28-2026-06-20`. It starts on the `spiral` family with
temporal frequency `3.084` Hz, spatial oscillators `6.041`, `35.362`, and
`37.531` Hz, no mirror fold, twist `-0.791`, pinch `-0.282`, scramble `0.128`,
jumble `0.165`, stretch `1.390,1.072`, source shift `-0.052,0.099`, noise
scale `6.633`, depth warp `0.103`, and phase offsets `0.965,1.613,3.836`.
The right-primary randomize action remains enabled and can move away from this
startup preset.

This means browser and VR outputs should not be expected to be pixel-identical:
the browser preview can be a fast 2D design surface, while the headset route
uses the same parameter vocabulary to drive colored volumetric interference.
Profile fixtures should use canonical `pattern_family` values from the manifest
when pinning a family; parser aliases are reserved for raw Android property
experiments.

## GPU Flow

`GpuStimulusVolumeRenderer` creates one device-local stereo storage image with
`STORAGE | SAMPLED` usage. The render target is selected by profile:
`512x512x2` for the performance/baseline tier, `768x768x2` for the balanced
tier, and `1024x1024x2` for the limit tier. The requested profile marker can say
`rgba16f`; the current portable storage format is
`VK_FORMAT_R8G8B8A8_UNORM`, reported as a format fallback in markers.

Per rendered frame:

1. The renderer transitions the stereo image to `GENERAL`.
2. `stimulus_volume_raymarch.comp.glsl` dispatches one compute grid over both
   array layers.
3. The compute shader raymarches a synthetic volume over the central-FOV ray
   window with Trevor-inspired pattern families, mirror/twist/pinch/scramble
   warps, three spatial oscillators, a two-octave value-noise modulation, high
   emission gain, black thresholding, and a depth-driven cyan/magenta/yellow
   ramp.
4. The image transitions to `SHADER_READ_ONLY_OPTIMAL`.
5. `stimulus_volume_projection.vert/frag.glsl` draws a fullscreen triangle into
   each eye framebuffer, sampling the matching array layer only inside the
   requested central-FOV fraction and leaving the periphery black.

No expanded volume texture is uploaded per frame; only a small uniform buffer and
push constants drive the shader.

The current shader uses weighted volumetric accumulation blended with the peak
interference color. This keeps strong black/color separation while making the
depth-ramp oscillator shapes smoother than the earlier max-only composite.

Runtime markers split the stimulus workload into `stimulusVolumeComputeGpuMs`
for the storage-image raymarch dispatch and `stimulusVolumeProjectionGpuMs`
for the stereo projection draw. The legacy `stimulusVolumeGpuMs` scorecard
field remains the parent projection-composite timestamp. Per-frame
`stimulus-volume status=frame` markers are emitted on startup, every 120
frames, and once per new randomization count.

## Input

`StimulusVolumeActions` creates the `stimulus_volume` action set and a boolean
`right_primary_randomize` action. It suggests bindings for:

- `/interaction_profiles/oculus/touch_controller` -> `/user/hand/right/input/a/click`
- `/interaction_profiles/meta/touch_controller_plus` -> `/user/hand/right/input/a/click`
- `/interaction_profiles/khr/simple_controller` -> `/user/hand/right/input/select/click`

Each frame the renderer calls `sync_actions`, reads the boolean action state, and
applies randomization on the rising edge. Randomization changes the temporal
envelope frequency and three spatial oscillator frequencies inside the validated
3-40 Hz range, then updates source offsets, spatial frequency scale, noise
scale, depth warp, three phase offsets, the pattern family when the active
family is randomized, and the browser-portable mirror/twist/pinch/scramble/
jumble/stretch warp parameters.
Projection-target reset, joystick scale, PMB pose publishing, and breath haptics
are not bound or polled in stimulus-volume modes, so the A/right-primary button
is reserved for stimulus randomization.

## Validation

Focused checks:

```powershell
cargo test -p rusty-quest-profile stimulus_volume
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Apply-RuntimeProfile.ps1 -ProfilePath fixtures\runtime-profiles\quest-native-renderer-solid-black-stimulus-volume.profile.json -DryRun -Out local-artifacts\native-renderer-solid-black-stimulus-volume-property-write-plan.json
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Apply-RuntimeProfile.ps1 -ProfilePath fixtures\runtime-profiles\quest-native-renderer-solid-black-stimulus-volume-balanced.profile.json -DryRun -Out local-artifacts\native-renderer-solid-black-stimulus-volume-balanced-property-write-plan.json
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Apply-RuntimeProfile.ps1 -ProfilePath fixtures\runtime-profiles\quest-native-renderer-solid-black-stimulus-volume-performance.profile.json -DryRun -Out local-artifacts\native-renderer-solid-black-stimulus-volume-performance-property-write-plan.json
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Apply-RuntimeProfile.ps1 -ProfilePath fixtures\runtime-profiles\quest-native-renderer-native-passthrough-stimulus-volume.profile.json -DryRun -Out local-artifacts\native-renderer-native-passthrough-stimulus-volume-property-write-plan.json
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Test-NativeRendererAndroid.ps1
```

Android target compile with shaders:

```powershell
cargo check --manifest-path apps\native-renderer-android\native\Cargo.toml --target aarch64-linux-android
```

Expected runtime evidence markers include:
`stimulusVolumeEnabled=true`, `stimulusVolumeActive=true`,
`volumePatternVocabulary=trevor-hewitt-inspired-browser-portable-v1`,
`volumePatternFamily=randomized-trevor-vocabulary`,
`volumeResolutionTier=limit-1024`,
`volumeCentralFovFraction=0.72`,
`volumeGradientSmoothing=0.78`,
`stimulusVolumeImageSize=1024x1024`,
`stimulusVolumePatternFamily=...`,
`stimulusVolumeMirrorMode=...`,
`stimulusVolumeStretch=...`,
`stimulusVolumeProjectionPath=central-fov-stereo-sampled-storage-image`,
`stimulusVolumeGpuBuffersResident=true`,
`stimulusVolumeExpandedVolumeUploadPerFrame=false`,
`projectionTargetControlsEnabled=false`, `breathHapticsConfigured=false`,
`rightPrimaryResetAction=false`, `rightBreathHapticAction=false`,
`rightControllerPrimaryButtonRandomize=true`, and
`projectionLayerAlphaBlend=false`.
