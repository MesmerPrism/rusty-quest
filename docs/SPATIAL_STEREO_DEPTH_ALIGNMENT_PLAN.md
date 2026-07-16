# Spatial Stereo Depth Alignment Plan

Status: implemented source/build gates and unattended Quest Q receipts; human visual alignment pending.

Workflow note: this work is the proposed MOD-008 follow-on. MOD-007 remains the single active unit until its headset-motion visual gate is completed or explicitly superseded. This document does not advance `workspace.state.json`.

## Objective

Use both `XR_META_environment_depth` array layers and their per-eye metadata to establish a first-pass render-to-depth alignment. Keep live panel controls as a residual calibration layer for camera crop, device variation, and translation/parallax that a 2D affine cannot solve.

## Source audit

Before this change, the provider exported a two-layer `VK_FORMAT_D16_UNORM` image and the renderer supported `mono-layer0`, `mono-layer1`, `eye-index`, and `compare`. The default policy was already `eye-index`, but the provider discarded both `XrEnvironmentDepthImageMETA.views` records. The shader therefore sampled the selected layer with one uniform manual scale and per-eye X/Y offsets. Some validation profiles deliberately selected `mono-layer0`, which made those runs monoscopic even though the underlying image was stereo.

The Khronos OpenXR contract is more specific:

- `XrEnvironmentDepthImageMETA.views[0]` is the left view and `views[1]` is the right view; each view supplies its own FOV and pose.
- `XrEnvironmentDepthImageTimestampMETA.captureTime` identifies the depth capture time.
- acquisition should use the current frame's predicted display time and must occur at most once between `xrBeginFrame` and `xrEndFrame`.

Primary references:

- <https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrEnvironmentDepthImageMETA.html>
- <https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrEnvironmentDepthImageViewMETA.html>
- <https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrEnvironmentDepthImageAcquireInfoMETA.html>
- <https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrEnvironmentDepthImageTimestampMETA.html>
- <https://registry.khronos.org/OpenXR/specs/1.1/man/html/xrAcquireEnvironmentDepthImageMETA.html>

Meta's Passthrough Camera API overview separately confirms left and right RGB camera streams on Quest 3/3S and Camera2 calibration metadata. That metadata can support a later camera-intrinsics homography; it is not substituted for the environment-depth view contract here: <https://developers.meta.com/horizon/documentation/unity/unity-pca-overview/>.

## Authority and data flow

| Stage | Authority | Contract |
|---|---|---|
| Depth acquisition | native OpenXR provider | two D16 array layers, near/far, capture time, two depth FOV/poses |
| Render-view estimate | native OpenXR sidecar | `xrLocateViews` at the same estimated display time and LOCAL space |
| Automatic alignment | native renderer | per-eye FOV + orientation center-Jacobian affine; identity fallback |
| Residual calibration | panel to JNI to native atomics | per-eye X/Y, X scale, Y scale, roll, auto on/off |
| Shader sampling | private downstream shader | composed 2x3 affine followed by existing depth texture transform |
| Effective-state evidence | native renderer | requested/applied/fallback masks, affine rows, capture/display times, lifecycle warning |

The automatic map converts a render-eye UV into a render FOV ray, rotates that ray through the render and depth poses, projects it into the selected depth FOV, and approximates the projective map with a 2x3 affine around the image center. The panel transform is composed after that map. Pose translation is measured and reported but intentionally not applied: translating a ray requires a sampled depth and a full depth-aware reprojection, so manual residual calibration remains the safe bounded behavior.

## Delivered controls

- `Stereo (per eye)` is the first, default source choice.
- `Mono 0`, `Mono 1`, and `Compare` remain explicit diagnostics.
- `Auto metadata: On/Off` selects metadata-first alignment or manual-only identity.
- Left and right X/Y offsets are independent.
- X scale and Y scale are independent.
- Roll is bounded to +/-15 degrees.
- `Reset fine tune` restores offsets, scales, and roll while preserving the auto-metadata choice.

## Lifecycle limitation

Spatial SDK owns `xrWaitFrame`/`xrBeginFrame`/`xrEndFrame`, and its public API does not expose the SDK-owned environment-depth texture or a frame callback that can transfer this sidecar acquisition into the active OpenXR frame interval. The compatibility thread now uses monotonic OpenXR time plus an explicit 11 ms estimate instead of unconditional zero time. It waits 32 ms after a successful acquire and uses a bounded 1 ms retry after a call-order miss to phase-lock opportunistically to the observed depth cadence. Unique and repeated capture-time counters distinguish fresh depth from reacquisition. That is still not compositor-predicted time and does not make the call order conformant.

Evidence therefore always reports:

- `environmentDepthAcquireFrameLoopIntegration=spatial-sdk-sidecar-compatibility`
- `environmentDepthAcquireScheduling=phase-lock-32ms-success-1ms-call-order-retry`
- `environmentDepthAcquireCallOrderConformant=false`
- `environmentDepthAcquireCallOrderErrorCount=<n>`

If the runtime begins enforcing the current call-order rule, the route must fail back to the depth fallback descriptor. A later conformant implementation requires either a Spatial SDK frame-hook/export API or moving the whole projection owner to an app-owned OpenXR frame loop.

## Acceptance gates

Automated:

1. Rust affine tests cover identity, per-eye FOV scaling, source-view selection, and residual composition.
2. Kotlin tests cover stereo/auto defaults, control clamps, and JNI receipt fields.
3. The private shader contract verifies the packed affine ABI and compiles to SPIR-V.
4. Static gates require stereo metadata, residual controls, and lifecycle warning markers.
5. An exact private-profile Android APK must compile and package.

Unattended Quest Q receipt run:

1. Use only the user-selected Q-ending headset; omit its serial from committed evidence.
2. Confirm both depth and render valid masks are `3`, source view count is `2`, and capture/display times are nonzero.
3. Confirm stereo policy plus left/right metadata alignment applied markers.
4. Record call-order errors, crashes, and fallback transitions.

Human visual run after the operator returns:

1. Start on `Stereo (per eye)`, auto metadata on, fine tune reset.
2. View the depth-gradient diagnostic against a near hand/edge and a farther vertical edge.
3. Check each eye separately, then binocularly, while stationary.
4. Tune X/Y first, then X/Y scale, then roll; keep adjustments small and record the final values.
5. Toggle auto off without changing residual controls for an A/B.
6. Move the headset slowly and then at normal speed to distinguish spatial misalignment from the separate MOD-007 camera-presentation lag.

No 45 Hz versus 50 Hz camera decision is made by this depth unit. Depth alignment and Camera2 presentation cadence remain separate variables so the later motion A/B stays attributable.

## Unattended Quest Q result

The final 2026-07-15 run used only the user-selected Q-ending headset with APK
SHA-256 `A5C5CF4DCB41583BBD60A08D5AB762D53977C8A66F0D884511C3722A26F4B8E9`.
The device serial, raw log, and local evidence path remain intentionally
uncommitted.

Depth-specific gates passed: foreground proof, real D16 array descriptor, `eye-index`, source views `0/1`, depth/render masks `3/3`, nonzero capture/display times, and metadata alignment applied for both eyes. The zero-residual affine was close to identity, as expected for matched render/depth views. Over 14.754 seconds the provider reported 359 successful acquires, 316 unique capture times, 43 repeats, and no crash. This is about 21.4 fresh depth captures per second while the stationary headset was unattended.

The run also recorded 2,744 `XR_ERROR_CALL_ORDER_INVALID` results. This is not an accepted conformant lifecycle; it is direct evidence that the compatibility sidecar only catches some SDK-owned frame intervals. The run used the wrapper's missing-marker allowance because its then-current broader camera assertion required stale default target rectangles; that assertion now accepts valid live-adjusted rectangles. All depth receipts above were checked from the foreground PID log.
