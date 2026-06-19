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
   window with three oscillators, a two-octave value-noise modulation, high
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
scale, depth warp, and three phase offsets.
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
`volumeResolutionTier=limit-1024`,
`volumeCentralFovFraction=0.72`,
`volumeGradientSmoothing=0.78`,
`stimulusVolumeImageSize=1024x1024`,
`stimulusVolumeProjectionPath=central-fov-stereo-sampled-storage-image`,
`stimulusVolumeGpuBuffersResident=true`,
`stimulusVolumeExpandedVolumeUploadPerFrame=false`,
`projectionTargetControlsEnabled=false`, `breathHapticsConfigured=false`,
`rightPrimaryResetAction=false`, `rightBreathHapticAction=false`,
`rightControllerPrimaryButtonRandomize=true`, and
`projectionLayerAlphaBlend=false`.
