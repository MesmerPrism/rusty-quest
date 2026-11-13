# Native Meta input stereo diagnostic

This opt-in native-renderer diagnostic consumes an app-owned GPU Surface. It
does not grant capture authority: an independently owned producer must obtain
and use the Surface through the Shell-only, DUMP-protected bridge. Existing
private payload and feature-lock requirements remain mandatory.

## Closed reader layouts

The presentation startup configuration retains `input_layout: "mono-left"`
when the field is absent. Mono supports only 1280×672 or 1280×1280 and repeats
that source into both output eyes. Explicit `input_layout: "stereo-both"`
supports only 2560×1280, interpreted as two 1280-square capture halves.
Arbitrary widths and non-square stereo heights are rejected.

The JNI snapshot reports the configured width/height and immutable generation.
Incoming AHB dimensions must match the selected layout; format, usage, stride,
allocation identity and generation checks remain in force. Selecting a new
layout requires a fresh application/reader generation.

Capture halves are mapped in source order to output eyes. This is an explicit
diagnostic convention, not verification of physical left/right labels, IPD or
camera calibration. Source sampling bounds stop at each half's first/last
texel centers so filtering cannot cross the central seam. Angular mapping must
produce an eye-local UV before applying the half rectangle; uncovered rays
must be rejected before clamping.

## Projection bounds are separate

Raster size does not determine capture FOV. Stereo angular mode requires
`source_tangents_per_eye` with two explicit `[left,right,down,up]` tangent
arrays, `source_bounds_authority: "manual-assumed"`, and the explicit
`reference: "current-per-eye-view"` diagnostic assumption. Shared mono bounds
cannot compete with stereo bounds. No numeric capture FOV is supplied by
default. The assumption uses output eye references; it does not recover the
producer's capture pose or timestamp registration.

Frame metadata records consumer/per-eye sizes, capture-half UV rectangles,
filter bounds, runtime XR FOVs and the chosen reference. It keeps source
capture pose, angular-bound qualification, source orientation and physical
eye-label verification explicitly unqualified. Stretch/contain modes remain
uncalibrated controls, not full-FOV acceptance.

## Closed control version 2

`NativeMetaInputPublisher.v2` retains synchronous Shell UID checking,
generation/sequence guards and request-versus-fence-completed status. Modes are
Raw=0, Colorama=1 and FullCore3=2. The raw and Colorama meanings are unchanged;
the full graph is never reported as Colorama. The completion witness mask now
has three bits (0 through 7).

Mode 2 is refused unless the separately selected private bundle is available
and explicit stereo source bounds/reference are present. That capability is
captured in the reader's control generation and checked again at the JNI mode
entrypoint. This bridge does not implement the private graph or claim that a
request was rendered. Full-graph completion still belongs to the actual
renderer and its successful retirement fence.

Mask bit 2 (numeric value 4) specifically means that both eye-sized FullCore3
presentation witnesses were copied after the completed frame fence and saved.
It does not mean a legacy source-sized fragment was the full graph. Mode 2 also requires both raw-pack input witnesses from the same retained
AImage and GPU submission as both processed eye witnesses. The mode-1 pair
remains separate. Eye witnesses are not physical display proof.

The version-1 artifacts remain separate. A new producer must use the matching
version-2 descriptor, exact APK, reader dimensions, generation and closed
capture request. Client cleanup is not native producer retirement
acknowledgement, and a session string does not become a global capture lock.

## Focused validation

The pure presentation math tests cover closed layouts, all source-center
addresses through separable axes, independent eye-boundary clamps, unequal
asymmetric frustums, uncovered rays and legacy mono geometry. Control tests
cover unavailable full-graph capability, stale generations/sequences and
fence-witness timing. Config tests exercise the actual strict JSON parser and
explicit stereo bounds/reference requirements.

`apps/native-renderer-android/tests/NativeMetaInputContractHostTest.java` checks
the actual transport value validation using Android API classes for type
resolution without calling Android services. These host checks establish no
GPU pixels, producer pose, optical calibration, physical stability or device
admission. Those require a separately owned runtime test.
