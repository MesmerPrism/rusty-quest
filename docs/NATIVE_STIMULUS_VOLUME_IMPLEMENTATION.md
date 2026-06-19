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
- Runtime options: https://github.com/MesmerPrism/rusty-quest/blob/main/apps/native-renderer-android/native/src/native_renderer_options.rs
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

Two profiles select the new route:

- `fixtures/runtime-profiles/quest-native-renderer-solid-black-stimulus-volume.profile.json`
- `fixtures/runtime-profiles/quest-native-renderer-native-passthrough-stimulus-volume.profile.json`

The solid-black profile is the pure-volume profile. It sets
`debug.rustyquest.native_renderer.render.mode=solid-black-stimulus-volume`,
disables camera/SDF/hand/environment-depth visual layers, enables
`stimulus_volume.enabled=true`, acknowledges the photosensitive-risk gate with
`stimulus_volume.safety_ack=true`, and randomizes in the `8.0` to `15.0` Hz
range.

The native-passthrough profile keeps the XR_FB_passthrough layer available under
an opaque projection, but the projection layer does not alpha blend, so the
visible output is still the generated colored volume over black.

## Properties

The stimulus settings are owned by `NativeStimulusVolumeSettings`:

- `debug.rustyquest.native_renderer.stimulus_volume.enabled`
- `debug.rustyquest.native_renderer.stimulus_volume.profile`
- `debug.rustyquest.native_renderer.stimulus_volume.composition`
- `debug.rustyquest.native_renderer.stimulus_volume.render_target`
- `debug.rustyquest.native_renderer.stimulus_volume.raymarch_samples`
- `debug.rustyquest.native_renderer.stimulus_volume.randomize.enabled`
- `debug.rustyquest.native_renderer.stimulus_volume.randomize.min_hz`
- `debug.rustyquest.native_renderer.stimulus_volume.randomize.max_hz`
- `debug.rustyquest.native_renderer.stimulus_volume.safety_ack`

The validator rejects enabled stimulus profiles unless `safety_ack=true`, rejects
unknown stimulus properties, rejects `randomize.max_hz > 15`, and rejects
`randomize.min_hz > randomize.max_hz`.

## GPU Flow

`GpuStimulusVolumeRenderer` creates one device-local 512x512x2 stereo image with
`STORAGE | SAMPLED` usage. The requested profile marker is `512x512x2-rgba16f`;
the current portable storage format is `VK_FORMAT_R8G8B8A8_UNORM`, reported as a
format fallback in markers.

Per rendered frame:

1. The renderer transitions the stereo image to `GENERAL`.
2. `stimulus_volume_raymarch.comp.glsl` dispatches one compute grid over both
   array layers.
3. The compute shader raymarches a synthetic volume with three oscillators, a
   two-octave value-noise modulation, high emission gain, black thresholding, and
   a depth-driven cyan/magenta/yellow ramp.
4. The image transitions to `SHADER_READ_ONLY_OPTIMAL`.
5. `stimulus_volume_projection.vert/frag.glsl` draws a fullscreen triangle into
   each eye framebuffer, sampling the matching array layer.

No expanded volume texture is uploaded per frame; only a small uniform buffer and
push constants drive the shader.

## Input

`StimulusVolumeActions` creates the `stimulus_volume` action set and a boolean
`right_primary_randomize` action. It suggests bindings for:

- `/interaction_profiles/oculus/touch_controller` -> `/user/hand/right/input/a/click`
- `/interaction_profiles/meta/touch_controller_plus` -> `/user/hand/right/input/a/click`
- `/interaction_profiles/khr/simple_controller` -> `/user/hand/right/input/select/click`

Each frame the renderer calls `sync_actions`, reads the boolean action state, and
applies randomization on the rising edge. Randomization changes the temporal
frequency inside the validated 8-15 Hz range and updates three phase offsets.

## Validation

Focused checks:

```powershell
cargo test -p rusty-quest-profile stimulus_volume
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Apply-RuntimeProfile.ps1 -ProfilePath fixtures\runtime-profiles\quest-native-renderer-solid-black-stimulus-volume.profile.json -DryRun -Out local-artifacts\native-renderer-solid-black-stimulus-volume-property-write-plan.json
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Apply-RuntimeProfile.ps1 -ProfilePath fixtures\runtime-profiles\quest-native-renderer-native-passthrough-stimulus-volume.profile.json -DryRun -Out local-artifacts\native-renderer-native-passthrough-stimulus-volume-property-write-plan.json
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Test-NativeRendererAndroid.ps1
```

Android target compile with shaders:

```powershell
cargo check --manifest-path apps\native-renderer-android\native\Cargo.toml --target aarch64-linux-android
```

Expected runtime evidence markers include:
`stimulusVolumeEnabled=true`, `stimulusVolumeActive=true`,
`stimulusVolumeGpuBuffersResident=true`,
`stimulusVolumeExpandedVolumeUploadPerFrame=false`,
`rightControllerPrimaryButtonRandomize=true`, and
`projectionLayerAlphaBlend=false`.
