# Native Video Border Blend Modes

`video-border-blend` is the public guide/video composition layer for the native
renderer. MediaCodec video stays a prepared Vulkan sampled image, Camera2/HWB
stays a guide texture, and private downstream layers remain separate from both.

The mode is selected with:

```text
debug.rustyquest.native_renderer.video_border_blend.mode
```

## Modes

| Mode | Formula family | Cost tier | Runtime path | Affordance | Main risk |
| --- | --- | --- | --- | --- | --- |
| `alpha-over` | Premultiplied guide alpha over video | baseline-fixed-function | Video background pass plus guide overlay | Cheapest baseline and alpha-feather reference | Looks like transparency, not content-aware image blending |
| `crossfade` | `mix(video, guide, mask)` in the guide/video shader | low | One guide sample plus one video sample | First true two-image compositor | sRGB mix can look slightly muddy |
| `linear-crossfade` | Approximate linear-light mix | low-medium | One guide sample plus one video sample plus RGB power ops | Better brightness continuity through the band | Color spaces are still approximated |
| `luma-match` | Camera gain nudged toward video luma near the edge | medium | Single samples plus luma gain | Reduces exposure mismatch at the transition | Can flatten contrast if pushed too hard |
| `chroma-luma` | Camera luma/detail with faster video chroma blend | medium | Single samples plus luma/chroma split | Keeps guide structure while easing color mismatch | Can look artificial on saturated edges |
| `soft-light` | Band-limited soft-light blend | medium | Single samples plus artistic blend math | Perceptual experiment without private semantics | Not physically neutral |
| `overlay` | Band-limited overlay blend | medium | Single samples plus artistic blend math | Stronger contrast experiment | Can exaggerate edge contrast |
| `screen` | Band-limited screen blend | medium | Single samples plus artistic blend math | Bright transition experiment | Can wash out bright video/camera pairs |
| `multiply` | Band-limited multiply blend | medium | Single samples plus artistic blend math | Darkening transition experiment | Can make the band visibly dim |
| `gradient-aware` | Derivative-biased crossfade toward sharper source | medium-high | Single samples plus screen-space derivatives | Reduces double-edge ghosts | Can shimmer under rapid motion |
| `two-band` | Wide low-frequency blend plus narrow high-frequency blend | high | Five-tap guide and video low-pass | Classic seam-hiding approximation | Extra texture taps; needs headset perf evidence |
| `temporal-stabilized` | Crossfade with per-eye target-rect EMA | low-medium | Single samples plus small CPU-side state | Reduces mask motion flicker | Adds response lag to target movement |

Poisson or gradient-domain blending is intentionally excluded from the real-time
Quest path.

## Sweep

Use the sweep wrapper to generate one profile, one runtime artifact directory,
and one summary row per mode:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererVideoBorderBlendSweep.ps1 `
  -Serial <quest-serial> `
  -ApkPath .\target\native-renderer-android\rusty-quest-native-renderer.apk `
  -OutDir .\local-artifacts\native-renderer-video-border-blend-sweep\headset
```

Dry-run profile generation without touching a device:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-NativeRendererVideoBorderBlendSweep.ps1 `
  -DryRunOnly `
  -OutDir .\local-artifacts\native-renderer-video-border-blend-sweep\dryrun
```

The report is written as `video-border-blend-sweep-report.md`, with per-mode
`mode-summary.json`, profile JSON, filtered logcat, screenshot, and parsed CPU
and GPU timing fields when the sweep is run on a headset.
