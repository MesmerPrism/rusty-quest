# Environment Depth Raw D16 Known-Distance Runbook

Purpose: prove whether Meta `VK_FORMAT_D16_UNORM` environment depth should keep
the current `projected-depth-from-near-far` reconstruction or move to a separate
metric/axial conversion policy.

## Profile

Use:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\Apply-RuntimeProfile.ps1 -ProfilePath fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json -DryRun
```

The profile sets:

- `environment_depth.mode=scene-particle-map`
- `environment_depth.source=xr-meta-environment-depth`
- `environment_depth.layer_policy=mono-layer0`
- `environment_depth.depth_units_policy=projected-depth-from-near-far`
- `environment_depth.debug_view=raw-d16`

Quest validation on 2026-06-22 found that `XR_META_environment_depth` can be
extension-supported, provider-running, and image-acquired while raw D16 sampling
still returns only the sentinel value `65535` when no native
`XR_FB_passthrough` layer is active. Treat provider/acquire status as API
liveness only. Known-distance and depth-visual runs must record
`nativePassthroughLayerActive=true` plus raw D16 or valid-sample counters before
using the data to judge depth units or shader behavior.

`metric-axial-meters` is intentionally rejected until a headset run proves that
policy is needed and the shader branch is implemented.

## Device Run

Use the wrapper so permission pregrant, profile application, log capture, and
raw-D16 evidence checks stay mechanical:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\Invoke-NativeRendererEnvironmentDepthKnownDistanceProof.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -Serial <quest-serial> -TargetDistanceMeters 1.0 -ToleranceMeters 0.15 -RunSeconds 8
```

Run one capture per measured target distance. Place a flat target centered in
the headset view at 0.5 m, 1 m, 2 m, and 4 m, then pass the matching
`-TargetDistanceMeters` value for each run. The wrapper delegates to
`Invoke-NativeRendererReplaySmoke.ps1 -EvidenceMode EnvironmentDepthParticles`,
pregrants the native renderer permission set, applies the profile, captures
pid-scoped logcat plus a screenshot, and calls
`Test-NativeRendererRuntimeEvidence.ps1 -RequireEnvironmentDepthKnownDistance`.
Adjust `-ToleranceMeters`, `-MinimumCenterConfidence`, and
`-MinimumCenterWindowValidCount` only when the artifact bundle records why the
target or room setup requires a different gate.

## Required Marker Fields

Use the latest `channel=environment-depth-particles` marker for each distance:

- `nativePassthroughLayerActive=true`
- `environmentDepthRawStatsStatus=readback`
- `environmentDepthDepthUnitsPolicy=projected-depth-from-near-far`
- `environmentDepthRawToMetersPolicy=projected-depth-from-near-far`
- `environmentDepthDebugView=raw-d16`
- `environmentDepthRawCenterD16`
- `environmentDepthCenterReconstructedMeters`
- `environmentDepthCenterConfidence`
- `environmentDepthRawCenterWindowMedianD16`
- `environmentDepthRawCenterWindowValidCount`
- `environmentDepthMinValidReconstructedMeters`
- `environmentDepthMaxValidReconstructedMeters`
- `environmentDepthDebugValidSampleCount`
- `environmentDepthDebugInvalidSampleCount`
- `environmentDepthDebugConfidenceRejectedCount`

Acceptance for this iteration is not visual polish. Acceptance is a table that
shows whether `environmentDepthCenterReconstructedMeters` tracks 0.5 m, 1 m,
2 m, and 4 m monotonically and within a defensible tolerance while raw D16 also
changes monotonically. If the projected formula is wrong, record the raw D16
pattern and add a separate metric/axial branch behind a new validated policy.
The wrapper enforces the per-distance tolerance, confidence, and center-window
valid-count checks; the monotonic cross-distance table is still assembled from
the per-run `runtime-evidence-summary.json` artifacts. Use the series checker
to validate that table mechanically:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\Test-NativeRendererEnvironmentDepthKnownDistanceSeries.ps1 -SummaryPath <0p5m-summary.json>,<1m-summary.json>,<2m-summary.json>,<4m-summary.json> -MinimumDistances 4
```

The checker requires every summary to come from the known-distance evidence
gate, verifies each per-run error remains within its tolerance, requires
reconstructed meters to increase with measured target distance, and requires raw
D16 to change monotonically in one direction across the series.

After the movement proof run also exists, tie the machine-readable artifacts
together with the evidence-bundle checker:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\Test-NativeRendererEnvironmentDepthEvidenceBundle.ps1 -MotionRunSummaryPath <motion-run-summary.json> -KnownDistanceSeriesPath <known-distance-series-result.json> -KnownDistanceRunSummaryPath <0p5m-run-summary.json>,<1m-run-summary.json>,<2m-run-summary.json>,<4m-run-summary.json>
```

The bundle checker validates wrapper provenance, pid-scoped runtime evidence,
motion thresholds, the known-distance series, and the four known-distance run
summaries. It still records that human headset visual acceptance is required.

For the final headset session, the acceptance-suite wrapper runs those steps in
order:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\Invoke-NativeRendererEnvironmentDepthAcceptanceSuite.ps1 -ApkPath target\native-renderer-android\rusty-quest-native-renderer.apk -Serial <quest-serial>
```

Use the lower-level commands above when a specific target distance or motion
threshold needs to be repeated.
