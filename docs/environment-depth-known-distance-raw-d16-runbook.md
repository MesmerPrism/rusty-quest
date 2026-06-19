# Environment Depth Raw D16 Known-Distance Runbook

Purpose: prove whether Meta `VK_FORMAT_D16_UNORM` environment depth should keep
the current `projected-depth-from-near-far` reconstruction or move to a separate
metric/axial conversion policy.

## Profile

Use:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Apply-RuntimeProfile.ps1 -ProfilePath fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json -DryRun
```

The profile sets:

- `environment_depth.mode=scene-particle-map`
- `environment_depth.source=xr-meta-environment-depth`
- `environment_depth.layer_policy=mono-layer0`
- `environment_depth.depth_units_policy=projected-depth-from-near-far`
- `environment_depth.debug_view=raw-d16`

`metric-axial-meters` is intentionally rejected until a headset run proves that
policy is needed and the shader branch is implemented.

## Device Run

1. Install the native renderer APK and pregrant the native renderer permission
   set, including `android.permission.CAMERA`, `horizonos.permission.USE_SCENE`,
   `horizonos.permission.HEADSET_CAMERA`, `horizonos.permission.SPATIAL_CAMERA`,
   and OpenXR permissions.
2. Apply the profile with `tools\Apply-RuntimeProfile.ps1 -Execute`.
3. Clear logcat, launch the native renderer, and let it run for at least 6
   seconds so the frame-120 or frame-240 aggregate readback marker is emitted.
4. Place a flat target centered in the headset view at 0.5 m, 1 m, 2 m, and
   4 m. Hold each position steady for at least 3 seconds.
5. Save logcat after each distance with a filename that includes the measured
   distance.

## Required Marker Fields

Use the latest `channel=environment-depth-particles` marker for each distance:

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
