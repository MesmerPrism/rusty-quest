# Spatial Camera Motion Iteration Report

## Decision

`SensorWarpCameraCalibrated` is the accepted best-current baseline for the
Spatial Camera Panel custom camera projection. It materially reduces the
motion-echo artifact in both horizontal and vertical head movement. The issue
is not completely eliminated, so the unwarped `Baseline` preset remains the
control condition and the calibrated mode remains hotloadable.

The accepted baseline keeps these independently useful invariants:

- non-blocking camera-frame adoption on a FIFO surface;
- Camera2's native capture cadence and every-available-frame adoption;
- strict timestamp-paired stereo presentation;
- acquired-image lifetime held through the serialized Vulkan frame fence;
- current-viewer placement and the normal video-plus-custom-layer composite;
- sensor-timestamp rotation reprojection calibrated from Camera2 static lens
  pose and intrinsic metadata.

This is a public renderer and Quest-platform baseline. Private effect formulas,
device identities, raw logs, APKs, and captures are not part of this report.

## Observed Symptom

During fast headset motion, high-contrast borders in the custom camera
projection appeared at an old and a current position in close succession. The
old image appeared to move with the headset and catch up after motion stopped.
The effect was visible with one eye closed, stayed inside the custom projection
region, and was present in the raw custom layer. The neighboring video layer
remained visually stable during the same motion.

A latched camera frame was individually clean. That result shifted the focus
from source-frame corruption toward frame hold, pose association, and the
camera-to-viewer coordinate transform used at presentation time.

## Iteration Results

| Iteration | Test | Result | Decision |
| --- | --- | --- | --- |
| 1 | Original current-viewer projection | Motion echo and border-position flicker were clearly visible. | Retain as the unwarped control only. |
| 2 | Non-blocking frame wait with FIFO | At most a small improvement; not a meaningful resolution. | Keep non-blocking adoption because it removes an avoidable wait. |
| 3 | Raw custom projection | The artifact remained without the private effect shader. | Focus on the public custom-projection path. |
| 4 | Strict timestamp stereo pair and mono-left controls | Alternating eye advancement existed, but the artifact was also monocular. | Retain strict atomic pairing as a correctness invariant, not the primary fix. |
| 5 | 45 Hz adoption and exact capture-rate controls | Rate limiting did not materially remove the artifact. | Keep native capture cadence and every-available adoption in the best baseline. |
| 6 | Early image deletion versus fence-held image lifetime | Unchanged or slightly better with fence-held lifetime. | Retain fence-held lifetime because producer/consumer ownership is explicit. |
| 7 | Camera2 noise reduction and edge enhancement requested off | No useful resolution. Unsupported controls fail closed rather than silently substituting. | Leave capture processing at the camera default. |
| 8 | Frozen complete stereo frame | The frozen frame was clean and stable. | Individual source images are not the main defect. |
| 9 | Fresh-frame-only pulse | Repeated 50 Hz image holds on the faster display became visible, but did not fully explain the moving double edge. | Treat cadence conversion as a contributor, not the sole cause. |
| 10 | Full rotation warp with forward/inverse direction and 70/90/110 degree FOV controls | Inverse direction helped, while large FOV changes did not solve the issue and could introduce an uncomfortable tilt. | Isolate rotation axes and inspect camera calibration. |
| 11 | Roll-free inverse yaw-plus-pitch | Horizontal motion became much better than vertical motion. | The common inverse sign was wrong for at least one axis. |
| 12 | Inverse yaw-only | Horizontal and vertical motion became similarly decent and the overall issue was reduced. | Use as the strongest diagnostic clue, not the final transform. |
| 13 | Camera2-calibrated full rotation | Best result so far in both directions, though not perfect. | Accept as the new best-current baseline. |

## Calibration Finding

The camera characteristics expose square active arrays, nearly symmetric focal
lengths, principal points close to image center, and an approximately 73-degree
field of view. Image aspect ratio was therefore not the explanation for the
axis-dependent result.

More importantly, the lens poses are referenced to the headset gyroscope and
the sensor-to-camera rotation is close to a half-turn around the X axis. That
coordinate change reverses the apparent yaw direction while preserving pitch.
It explains why an inverse transform improved yaw but made pitch worse.

The calibrated mode now computes the camera-space relative rotation as:

```text
camera_from_sensor * capture_from_current * sensor_from_camera
```

It also derives the ray projection from the reported focal lengths and
principal point instead of assuming a centered, symmetric operator-selected
FOV. Calibration is accepted only when the lens pose is gyroscope-referenced
and the selected stream dimensions match the pre-correction active array;
otherwise the calibrated reprojection fails closed.

## Runtime Evidence

The accepted headset run reported:

- calibration and intrinsics applied from Camera2 static characteristics;
- the calibrated reprojection mode observed by the native renderer;
- approximately 90 presented frames per second;
- approximately 50 acquired frames per second for each camera;
- zero single-eye presents under the strict-pair policy;
- timestamp-paired eyes recorded in one command buffer and submitted through
  one queue-present path;
- no bounded package fatal or ANR during the accepted run.

Source validation included the native receipt tests, focused Kotlin parser
tests, the camera-latency static gate, PowerShell parser checks, formatting,
diff hygiene, and an Android APK build. Generated binaries and device evidence
remain local artifacts.

## Remaining Work

The most useful next investigations are:

1. Carry distinct left/right lens rotations, focal lengths, and principal
   points through the shader instead of the current shared-left approximation.
2. Separate the unavoidable 50 Hz camera-to-90 Hz display hold from residual
   pose error with display-time prediction or a compositor-aligned timestamp.
3. Evaluate translation/parallax compensation after the rotation contract is
   stable; rotation-only reprojection cannot correct nearby-scene translation.
4. Revisit the custom-projection border blend separately. The current blend
   stops the effect before the raw projection and video boundary, leaving a
   visible hard transition that is not part of this latency decision.
