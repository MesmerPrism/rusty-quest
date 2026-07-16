# Spatial Camera Presentation-Time Remediation Plan

Status: MOD-007 active implementation plan (2026-07-15)

## Decision

Keep the outside cameras on their native every-available cadence (about 50 Hz
on the tested headset) and keep the app surface at 90 Hz. A held camera image
must be reprojected on every display submission from its capture-associated
head pose to a bounded presentation-target pose. Reducing adoption to 45 Hz is
retained as a controlled A/B only; it is not the default or the timing
authority.

The post-automation visual observation narrows the current defect further: on
a fast pitch, the video carrier stays attached to the headset while the custom
projection briefly exposes more carrier at the leading edge and then catches
up. The next candidate therefore keeps the target rectangle fixed within the
unchanged full-surface scissor,
maps that footprint to a central crop of the camera image, and reserves the
real captured pixels around the crop for rotation reprojection. It does not
fill exhausted coverage with an unwarped copy or a synthetic edge visual.

The Spatial SDK remains the sole owner of the OpenXR application frame loop.
The camera sidecar must not call `xrWaitFrame`, `xrBeginFrame`, or `xrEndFrame`.
When OpenXR handles are available, it may call `xrLocateViews` at an explicitly
estimated future target time. That target is a bounded latency estimate, not
the compositor's authoritative predicted display time.

## Problem contract

The camera source and display are asynchronous:

| Stage | Nominal rate | Required behavior |
| --- | ---: | --- |
| Camera2 outside-eye capture | about 50 Hz | Publish latest GPU image; never queue old images for sequential display. |
| Spatial scene pose sampling | app/scene tick | Record monotonic, ordered viewer samples with position and orientation. |
| Vulkan WSI render | about 90 Hz | Recompute camera reprojection for every submission, including held-image submissions. |
| Spatial/OpenXR compositor | SDK-owned | Remains the only application frame-loop authority. |

The defect is accepted as fixed only when repeated display holds do not remain
camera-locked during ordinary yaw and pitch and the custom projection border
does not move relative to the head-stable video carrier while retained source
coverage remains available. Camera rate matching or cosmetically filling an
invalid region cannot satisfy this contract.

## Authority and timestamp contract

| Value | Authority | Clock / semantics | Portable? |
| --- | --- | --- | --- |
| Camera image timestamp | Camera2/AImage | Camera sensor timebase | Only directly comparable when Camera2 declares `REALTIME`; `UNKNOWN` is an explicitly empirical association. |
| Camera callback timestamp | native callback | Android `CLOCK_BOOTTIME` | Yes within the process/device run. |
| Spatial viewer sample | `Scene.getViewerPose` sampled by the app | `SystemClock.elapsedRealtimeNanos` / `CLOCK_BOOTTIME` | Yes within the process/device run. |
| OpenXR locate time | OpenXR conversion extension | `XR_KHR_convert_timespec_time` from `CLOCK_MONOTONIC` | Preferred when available; no direct-`XrTime` fallback may be called authoritative. |
| Presentation target | camera sidecar policy | current monotonic time plus bounded configured lead | An estimate only; markers must say `estimated`, not compositor-predicted. |
| Present completion | Vulkan WSI | return from `vkQueuePresentKHR` | Queue-present call only, not compositor scanout or photons. |

Every runtime summary must expose the selected capture association, pose
bracket/interpolation state, presentation-pose source and fallback, requested
and effective lead, latest pose age, per-eye calibration validity, and the
queue-present-only evidence boundary.

## Implementation slices

### 1. Continuous capture-pose association

- Extend viewer samples with position and sequence metadata.
- Select exact or bracketed samples for the camera target timestamp.
- Use quaternion slerp for orientation and linear interpolation for position.
- Keep nearest-before and earliest-sample fallbacks explicit; never label them
  interpolated.
- Record bracket timestamps, interpolation fraction, sample age, and association
  confidence in bounded markers.

### 2. Presentation-target pose

Add hotloadable modes:

- `scene-tick-latest`: exact rollback to the prior latest-scene-pose behavior.
- `scene-extrapolated`: bounded quaternion extrapolation from the two newest
  scene samples.
- `openxr-locate-views`: locate the current and future OpenXR views at the
  configured target lead, register the current OpenXR basis to the current
  Spatial basis, and map the future relative orientation into Spatial space.
  Fall back to bounded scene extrapolation if handles, conversion, reference
  space, view flags, or locate calls are unavailable.

The lead is live-safe and clamped to 0-30 ms. Initial device sweeps use 0, 8,
11, 16, and 22 ms. A final default is selected from measured and visual
evidence rather than inferred from 90 Hz alone.

### 3. Per-eye projection

- Store independent left/right Camera2 static lens-pose and intrinsic
  calibration.
- Associate each acquired eye image with its own capture pose.
- Compute independent left/right calibrated rotation matrices.
- Submit one bounded raw-projection draw per eye so both calibrations fit under
  the portable 128-byte Vulkan push-constant floor.
- Feed the same eye-specific reprojection into the first private guide pass;
  store the prewarped camera color in the resident guide texture so the final
  effect projection does not need a larger-than-portable push block or a second
  raw external-camera sample.
- Add a live-safe 0-20 percent per-edge source margin. A 10 percent setting
  displays the central 80 percent of the real camera image and retains the
  surrounding captured pixels for held-frame rotation reprojection.
- Keep two explicit footprint policies. `zoom-to-fill` leaves the target fixed,
  so a 10 percent per-edge crop produces 1.25x magnification. The accepted
  `reduced-footprint` candidate scales each current per-eye target around its
  existing center by `1 - 2 * sourceMargin`; at 10 percent this is 0.8. Because
  both displayed source span and target span are 0.8, the original angular scale
  is preserved. The full-surface scissor and underlying video carrier remain
  unchanged.
- Reject out-of-range reprojected UVs to the underlying carrier only after the
  retained real-camera margin is exhausted. Do not clamp, blend to an
  unwarped stale sample, or draw a replacement visual in that region.

The non-calibrated and prediction-off paths remain available as rollback.

### 4. Evidence and controls

Add or retain hotload presets for:

- presentation prediction off (prior calibrated behavior);
- scene extrapolation at bounded leads;
- OpenXR locate-views at bounded leads;
- explicit `PresentationOpenXr11Overscan0` and
  `PresentationOpenXr11Overscan10` controls, identical except for retained
  real-source margin and both using the historical zoom-to-fill footprint;
- `PresentationOpenXr11GuardBand10`, which uses the same 10 percent real-source
  margin while reducing each current projection footprint to 80 percent;
- every-available native adoption (production candidate);
- 45 Hz display-aligned adoption (diagnostic control);
- verbose per-frame marker capture. The reproducible motion presets are
  `PresentationOpenXr11Verbose` and
  `PresentationOpenXr11Adoption45Verbose`; they change only frame logging and
  the 500 ms summary window relative to their non-verbose candidates.

Strict-pair rejection discards both latest candidates before the next 45 Hz
adoption opportunity. Retaining the newer eye while waiting for the other can
make two 50 Hz latest-image streams alternate one source period apart forever,
which holds an old stereo pair instead of measuring a 45 Hz cadence.

The device wrapper must collect PID-scoped logcat, preset/readback receipts,
bounded summary rows, and cleanup state. Raw logs, screenshots, APKs, and
device identifiers stay local.

## Validation ladder

1. Host/domain:
   - exact/interpolated/fallback capture-pose selection;
   - quaternion slerp and bounded extrapolation;
   - OpenXR-to-Spatial relative-basis mapping math;
   - per-eye calibration separation and invalid-eye fallback;
   - eye-specific projection push layout at or below 128 bytes;
   - central-source-crop mapping, bounded overscan parsing, and honest invalid
     discard after real coverage is exhausted;
   - settings parser and damaged-value rejection.
2. Static/boundary:
   - no camera frame payloads in JSON;
   - no sidecar `xrWaitFrame`/`xrBeginFrame`/`xrEndFrame`;
   - explicit queue-present-not-photons markers;
   - serial-scoped device tools and revision-last hotload.
3. Build:
   - Spatial Camera Panel debug APK with compiled shader and JNI parity.
4. Automated headset:
   - about 90 render submissions per second;
   - native camera callbacks remain near the observed source cadence;
   - every-available adoption and held-image reprojection both active;
   - nonzero interpolated capture associations during movement;
   - requested/effective presentation source and lead reported;
   - left and right calibration valid and independently selected;
   - zero fatal/process-crash markers.
5. Human headset acceptance:
   - user performs controlled yaw, pitch, and lateral movement;
   - user compares OpenXR +11 ms with 0 and 10 percent source overscan before
     comparing the winning overscan setting to the 45 Hz control;
   - yaw/pitch camera drag must be materially removed at normal and reasonably
     fast motion without objectionable overshoot or wobble;
   - lateral residual is recorded separately because rotation-only
     reprojection cannot synthesize correct depth parallax.

## Manual motion protocol

Use a distant, high-contrast vertical edge for rotation and a nearer object
for translation. Keep the camera target active and compare presets without
changing the scene.

1. Slow yaw: about 30 degrees left/right over two seconds.
2. Normal yaw: about 45 degrees left/right over one second.
3. Faster yaw: a comfortable, non-violent 45-degree reversal.
4. Pitch: about 20 degrees up/down at normal speed.
5. Lateral: translate the head 10-15 cm left/right without deliberate yaw.

For each preset, watch both a scene edge and the custom-projection border
against the video carrier, then report `clean`, `drag`, `border reveal`,
`overshoot/wobble`, or `uncertain`. If 10 percent overscan delays or removes the
border reveal and the visual echo by the same amount, exhausted source coverage
is part of the tracked mechanism. If the border stabilizes but scene content
still echoes, a separate pose/timestamp defect remains. Yaw/pitch decides the
rotation fix. Lateral movement characterizes the known no-depth limit and must
not be disguised as a camera-cadence problem.

## 45 Hz decision rule

Do not promote 45 Hz merely because 45 divides 90. Promote it only if the same
run shows all of the following against every-available adoption:

- lower capture-to-presentation pose error;
- no increase in source-frame age or skipped fresh frames;
- no worse hold histogram;
- better user yaw/pitch judgment;
- no new shimmer or cadence judder.

Otherwise retain native every-available adoption. The expected outcome is
that presentation-pose correction fixes rotational drag while 45 Hz adds age
and therefore remains a diagnostic control.

## Automated headset result (2026-07-15)

The unattended Quest 3S-class pass used APK SHA-256
`090DF82557EA576DE9EB3F78C0FAD50CAB6DCFF456100CBA339D418F9B128AD3`.
Device identifiers and raw logs remain local.

| Mode | Render FPS | Camera callbacks/s | Stereo imports/s | Mean display holds |
| --- | ---: | ---: | ---: | ---: |
| every-available, OpenXR +11 ms | 90.021 | 50.41 | 50.36 | 1.785 |
| display-aligned 45 control, OpenXR +11 ms | 90.022 | 50.41 | 44.77 | 2.005 |

Both modes passed the automated smoke contract, used the SDK-owned OpenXR
pose locator without a sidecar frame loop, exercised the private guide ingress,
and remained crash-free. The corrected 45 Hz control recovered from four
split-pair rejections and two one-eye publication windows instead of retaining
an old stereo pair indefinitely.

The verbose every-available run recorded 2,050 interpolated capture-pose
associations. All 1,963 presentation records used OpenXR with no fallback,
an effective 11.000 ms lead, independent left/right calibration, and an
applied warp for both eyes. After launch transients, the last 100 scene-pose
samples averaged 7.851 ms old.

After this automated pass, the user observed that the full video layer was
head-stable but the custom projection boundary lagged during fast pitch. This
invalidates a cosmetic edge-fallback as a remediation: such a fallback could
hide the boundary while retaining the stale-frame echo. The pending physical
gate became the zero-versus-10-percent real-source overscan comparison above.

The user then confirmed that `PresentationOpenXr11Overscan10` removed the
tracked echo/lingering artifact during physical headset motion. That result
supports source-coverage exhaustion as the cause, rather than camera cadence or
a cosmetic invalid-region problem. The unchanged target made this control
visibly 1.25x magnified, so it is retained only as the accepted causal A/B.
`PresentationOpenXr11GuardBand10` is the new candidate: it retains the same real
camera margin but shrinks the existing target footprint to 80 percent, exposing
more of the underlying head-stable video while preserving the original camera
angular scale. Physical confirmation that it keeps the echo fix without the
constant zoom subsequently passed. The user described this as the best state
reached so far. This closes the general echo/processing-stack defect for the
accepted baseline.

An actual Camera2 fixed-45 request was rejected on both eye cameras as
`exact-fixed-range-unsupported`; the source remained 49.995 Hz. Therefore the
automated decision is to retain native every-available adoption. Human
yaw/pitch acceptance remains the final gate and may still change the chosen
prediction lead.

## Rollback and evidence limits

Rollback is property-only: select `PresentationOpenXr11Overscan0`, select the
zoomed causal control `PresentationOpenXr11Overscan10`, select
`scene-tick-latest` and the prior calibrated sensor-warp preset, or turn the
latency diagnostic off. No APK rebuild is
required.

Automated evidence does not prove scanout time, photons, depth-correct
translation, or the absence of rare individual-frame outliers. Human
physical-motion acceptance of the general echo fix is complete.

## Accepted baseline and next investigation (2026-07-15)

The accepted baseline is `PresentationOpenXr11GuardBand10` with native
every-available camera adoption, OpenXR +11 ms presentation-pose location,
10-percent real-source margin per edge, and an 0.8 footprint multiplier. The
user confirmed that the general echo is gone and that the view no longer has
the rejected constant zoom.

Minor inconsistent motion remains, but it now appears as isolated individual
frames rather than a persistent projection-stack catch-up. Treat that as a new
outlier investigation. Preserve this baseline and correlate opt-in per-frame
evidence against:

- the normal 50 Hz camera-image hold pattern on the 90 Hz display loop;
- isolated source-age, capture-to-presentation, Scene-pose-age, or effective
  presentation-lead spikes;
- strict stereo-pair rejection/recovery and one-eye publication windows;
- fence retirement, camera-image adoption, Vulkan submission, or queue-present
  outliers;
- the display-aligned 45 Hz adoption control, without treating it as the
  default or claiming that an unsupported fixed-45 Camera2 request ran.

The next task should first produce a bounded outlier scorecard and only then
change cadence or synchronization policy. The accepted guard-band geometry is
the rollback anchor and must remain unchanged during that diagnosis.
