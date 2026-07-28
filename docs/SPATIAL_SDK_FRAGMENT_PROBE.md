# Meta Spatial SDK fragment probe

## Decision

The Spatial Camera Panel contains a narrow, default-off custom-fragment probe
for establishing the real device boundary of the Meta Spatial SDK renderer.
It has two static presentation modes:

- `flat-2d`: a checker-and-ring control rendered by the custom fragment path.
- `raymarch`: a twelve-step analytic signed-distance-field renderer evaluated
  in the fragment shader with a per-eye origin and projection.

Each mode can select a shader that does or does not write `gl_FragDepth`.
Separate shader binaries make the depth experiment unambiguous even if a
driver optimizes uniform-controlled branches.

This is an app-owned diagnostic adapter, not a reusable Lattice, Matter,
Optics, or Manifold contract. It does not own the OpenXR session, Vulkan
device, swapchain, camera path, or frame loop. Meta Spatial SDK remains the
renderer authority and compiles the GLSL sources through its Gradle plugin.

## Activation and safety

The probe is inert unless the complete property manifest is valid:

| Property | Accepted value |
| --- | --- |
| `debug.rustyquest.spatial.fragment_probe.enabled` | explicit boolean |
| `debug.rustyquest.spatial.fragment_probe.mode` | `flat-2d` or `raymarch` |
| `debug.rustyquest.spatial.fragment_probe.fragment_depth` | explicit boolean |
| `debug.rustyquest.spatial.fragment_probe.hold_ms` | 2,000–60,000 ms |

Missing or invalid mode/depth input fails closed. The shaders never consume
the SDK time or modulo-time uniforms and implement no brightness alternation,
flashing, or strobing. The raymarch loop is statically bounded to twelve
steps. Runtime evidence declares `temporalModulation=false` and
`photosensitiveSafetyMode=static-only`.

Android properties are activation adapters only. The consuming app must emit
`effectiveMarker=rusty.quest.spatial_fragment_probe.effective` after the
custom material and all scene objects have been created.

## Depth experiment

The probe creates three independently owned scene objects:

1. A box proxy at 1.40 m carrying the custom material.
2. A magenta foreground occluder at 0.92 m, which should always win the depth
   test where it overlaps.
3. A cyan discriminator at 1.14 m, positioned behind the proxy's front face
   but in front of much of the analytic SDF surface.

The no-depth raymarch variant retains proxy-raster depth, so the cyan object
can be incorrectly hidden. The depth variant projects the analytic hit point
with the current eye matrix and writes that value to `gl_FragDepth`; the cyan
object should then remain visible wherever it is closer than the analytic
surface. This A/B is the primary proof that fragment-depth writes are honored,
not merely accepted by the offline shader compiler.

The foreground occluder must remain correctly in front in both variants. A
failure there indicates a broader scene depth-state problem rather than a
raymarch-specific limitation.

## Evidence contract

Required log markers are:

- `channel=spatial-fragment-probe status=start`
- `status=effective` with the app-authored effective marker
- `status=render-ready`
- `status=render-window` with a positive scene-tick count

These markers prove activation, material/object construction, and scene-loop
liveness. They deliberately report `gpuFragmentExecutionConfirmed=false`.
A captured headset image and a visual comparison are required to prove actual
fragment execution and the depth A/B.

Run the focused host checks and build:

```powershell
pwsh -NoProfile -File .\tools\checks\Test-SpatialFragmentProbeStatic.ps1
pwsh -NoProfile -File .\tools\Build-SpatialCameraPanelAndroid.ps1
```

Run the four-case device matrix with an explicit Quest serial:

```powershell
pwsh -NoProfile -File .\tools\Invoke-SpatialCameraPanelFragmentProbeSmoke.ps1 `
  -Serial <quest-serial> -Mode flat-2d -FragmentDepth:$false
pwsh -NoProfile -File .\tools\Invoke-SpatialCameraPanelFragmentProbeSmoke.ps1 `
  -Serial <quest-serial> -Mode flat-2d -FragmentDepth:$true -SkipInstall
pwsh -NoProfile -File .\tools\Invoke-SpatialCameraPanelFragmentProbeSmoke.ps1 `
  -Serial <quest-serial> -Mode raymarch -FragmentDepth:$false -SkipInstall
pwsh -NoProfile -File .\tools\Invoke-SpatialCameraPanelFragmentProbeSmoke.ps1 `
  -Serial <quest-serial> -Mode raymarch -FragmentDepth:$true -SkipInstall
```

The wrapper uses only `adb -s <serial>`, snapshots the complete property
manifest, stops only the target package, captures PID-scoped logcat and an
`exec-out` screenshot, restores the exact prior properties, and verifies the
readback. During each run it explicitly disables and rejects runtime evidence
from the unrelated camera-HWB projection, decoded-video projection,
video-only projection, camera-HWB diagnostic, and panel shell so the fragment
result is not visually or computationally confounded. Those prior property
values are restored only after the isolated app process has stopped. Raw
device evidence stays in ignored `local-artifacts`.

## Capability verdicts this can support

- Shader compile failure: the pinned Spatial SDK/plugin or its toolchain does
  not accept the source/API combination.
- Material/object creation failure: custom shaders compile but the runtime
  custom-material surface is unusable for this app/device combination.
- 2D visible, raymarch absent: investigate fragment control flow, discard,
  per-eye uniform access, or device shader budget.
- Raymarch visible without correct depth A/B: custom fragment execution works,
  but `gl_FragDepth` is ignored, transformed incorrectly, or incompatible with
  the effective scene depth pipeline.
- Raymarch and depth A/B correct: analytic fragment rendering is viable inside
  SDK-owned scene geometry. This still does not demonstrate compute shaders,
  storage buffers/images, arbitrary descriptor layouts, command-buffer access,
  custom render passes, or Vulkan/OpenXR swapchain ownership.

If the twelve-step probe passes, the next slice should profile bounded step
counts and proxy coverage on-device. Perfetto or broader performance capture
requires separate explicit approval and is not part of this smoke.

## Observed validation: 2026-07-17

The implementation was built and exercised against the app's pinned Meta
Spatial SDK `0.13.1` on one Quest 3S running Android 14 / API 34. This is an
empirical compatibility result for that combination, not a claim about every
Spatial SDK or Horizon OS release.

Host results:

- The Meta Gradle plugin compiled ordinary and multiview SPIR-V assets for
  both shader bases, including the fragment-depth variant.
- `SpatialFragmentProbeRouteTest` passed all five default-off, mode, depth,
  clamp, and fail-closed cases.
- `:app:assembleDebug` completed successfully and packaged the shader
  manifest plus the app's native receipt library.

Device results used a four-case isolated matrix. During each process lifetime
the wrapper disabled the pre-existing camera-HWB projection, decoded video,
video-only route, camera-HWB diagnostic, and panel shell. PID-scoped logs
contained zero camera-projection starts and zero decoded-video frames.

| Mode | `gl_FragDepth` | Runtime markers | Visual result |
| --- | --- | --- | --- |
| `flat-2d` | off | pass | static checker/ring and foreground control visible |
| `flat-2d` | on | pass | same control image; no depth regression |
| `raymarch` | off | pass | analytic sphere/box visible for both eyes; proxy-depth behavior visible |
| `raymarch` | on | pass | analytic shape visible; cyan discriminator correctly wins where it is closer than the analytic hit |

The magenta foreground control remained in front in all four captures. The
cyan A/B changed as designed between the raymarch variants, which is positive
device evidence that this SDK renderer/driver combination honors fragment
depth written from the custom material. Every run stopped the app and restored
the complete ten-property test/isolation manifest with verified readback.

The headset overlay stayed near 90 FPS during these brief captures, but that
is not a performance result: no controlled GPU timing, thermal stabilization,
step-count sweep, or Perfetto trace was taken. The validated conclusion is
functional viability for a small, static, twelve-step analytic raymarch—not a
production performance budget and not general Vulkan command/API access.
