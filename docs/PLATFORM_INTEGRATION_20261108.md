# Platform source integration, 8 November 2026

Continue platform development on `codex/stereo-body-swap-consolidated-20261108`.
This branch joins the previously separate source lines with ordinary merges.
It is a source integration checkpoint; it selects no new installed artifact,
runtime profile, feature lock, or downstream project lifecycle state.

The integrated platform surfaces include:

- USB development transport and existing native continuity observations.
- Receiver trace and pressure observations, with the actual packet queue body
  retained inside its trace scope.
- Scene Entity lifetime controls and optional background, interop allocation,
  Vulkan provenance, allocation residency, and queue diagnostics.
- Camera-content-only optional color processing, including unmixed Own and
  Peer eligibility. Background and projection-region composition remain outside
  the camera color input. See [optional camera color](OPTIONAL_CAMERA_COLOR.md).
- Optional final binocular display routing after camera processing and scene
  composition. Stereo remains the default; selected-eye mirror and vertical
  flip modes require explicit selection.

The integrated branches retain their original Git ancestry. The SDK 0.14
experiment is deliberately separate at
`archive/stereo-body-swap-sdk014-unqualified-20261108`; it is not an adopted SDK
upgrade or a proven graphics fix. Existing artifact receipts remain bound to
their original source and bytes.

Focused host checks cover the receiver queue and traces, background contract
and Android provider, cached Activity controls, Entity lifecycle, retired source
snapshots, present semaphore selection, primary-input continuation, projection
background controls, optional color policy, and binocular policy. Some harnesses
model Android or Vulkan leaves; they do not compile the complete application or
exercise a headset GPU.

Before merging or packaging this combined source, complete the full application
Kotlin compilation, Android-target native Vulkan compilation, and the applicable
owner and hosted PR checks. A historical downstream key-only build-proof path
also needs an explicit contract update: its old diagnostic source and export
inventory differs from the current identity provider. Do not rename unrelated
source files or weaken source/export checks to make that historical proof pass.

Keep source integration, new artifact construction, downstream adoption, and
device qualification as separate recorded steps. This checkpoint makes no claim
that background-video crashes, sustained streaming gaps, recovery time, or
non-default binocular output have passed device qualification.
