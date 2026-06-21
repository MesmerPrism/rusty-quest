# Native Video Projection

The native renderer video path is a public MediaCodec/AHardwareBuffer/Vulkan
input family. Java owns `MediaExtractor` and `MediaCodec` control, decodes into
a Rust-created `AImageReader` `Surface`, and Rust imports the decoded
`AHardwareBuffer` as a Vulkan sampled image. Video frames do not cross Java or
Rust as CPU pixels and do not use high-rate JSON payloads.

## SBS Source Rects

Side-by-side video keeps exact per-eye source UV ownership:

- Left eye: `0.000000,0.000000,0.500000,1.000000`
- Right eye: `0.500000,0.000000,0.500000,1.000000`

Those source rects identify which half of the decoded raster is sampled. They
are not used to encode stereo alignment offsets.

## Per-Eye Positioning

Correct headset stereo overlap needs per-eye display-screen-UV positioning, not
a naive assumption that each SBS half is centered in each eye. The native
Camera2 projection already owns the public per-eye target footprint:

- Left camera target: `0.171875,0.218750,0.750000,0.656250`
- Right camera target: `0.078125,0.218750,0.750000,0.671875`

Video projection stays a full-eye background target, but the sampled local UV is
shifted by the camera target center offset for each eye. With the default camera
targets, the video metadata reports:

- Left source position offset: `0.046875,0.046875`
- Right source position offset: `-0.046875,0.054688`

The shader clamps the positioned local UV inside the selected SBS source half,
so this adjustment cannot bleed the left and right source halves into each
other. Camera2/HWB guide textures and downstream private layers still compose as
separate layers above or around the video plane.

## Video-Border Blend Diagnostics

`video-border-blend` draws the video first as the full-eye background, then draws
the guide texture as a premultiplied-alpha overlay. The inner-band blend changes
only the guide overlay alpha, so the video is revealed at the camera target
edge. The eye-colored edge tint is diagnostic-only and is enabled by
`debug.rustyquest.native_renderer.peripheral.stretch.debug`; when that property
is `off`, the video-border transition has no cyan/orange debug rim.

## Looping

Looping playback seeks the extractor before queueing codec EOS. After seeking,
the same dequeued input buffer is filled with the first sample of the next pass
and queued back to MediaCodec. This avoids leaking decoder input buffers across
loop restarts and keeps playback smooth over repeated loops.
