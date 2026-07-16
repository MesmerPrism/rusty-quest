# Native Display-Composite AHardwareBuffer Plan

This document defines the long-term MediaProjection display-composite route for
the Rusty Quest native renderer. The goal is a reusable native media/image
ingress path that can serve display-composite feedback, future diagnostic video
sources, and other GPU-sampled Android producers without making `xr_vulkan.rs`
or the Camera2 path the owner of every `AHardwareBuffer` concept.

## Decision

Use Android MediaProjection only for consent and display-composite production,
then receive frames through a Rust/NDK `AImageReader` surface. Java owns the
`MediaProjectionManager`, foreground service, and `VirtualDisplay` lifecycle.
Rust owns the `AImageReader`, `ANativeWindow`, `AImage`, `AHardwareBuffer`
descriptor/lifetime evidence, Vulkan sampled-image import, and the optional
clean MediaProjection feedback texture.

The first implemented slice is a native `AImageReader` display-composite source
and `AHardwareBuffer` witness path. It replaces the temporary Java
`ImageReader.getHardwareBuffer()` bridge so the long-term high-rate path does
not allocate Java `Image`/`HardwareBuffer` objects per frame.
The second implemented slice extracts reusable Vulkan `AHardwareBuffer`
property query, sampled-image import, memory binding, image-view creation,
layout transition, and retained-handle ownership into
`ahardware_buffer_vulkan.rs`; Camera2 now uses that module without changing its
renderer policy.
The third implemented slice imports MediaProjection `AHardwareBuffer` frames
through that module, then offers two renderer-owned diagnostics:
`gpu-feedback-diagnostic` draws the imported frame directly, and
`gpu-recursive-feedback-diagnostic` first renders the current MediaProjection
frame into a bounded app-owned feedback texture without previous-feedback
blending or diagnostic borders before projecting it into the field of view with
fully opaque premultiplied alpha, luma-damped feedback, and an aggressively shrunken centered target footprint. The
feedback effect is then driven by MediaProjection recapturing the visible app
plane on later frames.

## Scope

- Android 14-compatible foreground `mediaProjection` service declaration.
- Control-panel action that calls `createScreenCaptureIntent` and receives
  fresh result data on every launch.
- Java service that creates a `VirtualDisplay` against a Rust-created
  `Surface`.
- Rust/NDK `AImageReader_newWithUsage` source for display-composite frames.
- Rust `AImageReader` callbacks that acquire latest `AImage` frames, call
  `AImage_getHardwareBuffer`, acquire/release `AHardwareBuffer` references, and
  emit descriptor markers.
- Shared Rust `AndroidHardwareBufferHandle` helper, so Camera2 and
  display-composite sources use the same reference-counting primitive.
- Shared Vulkan `AHardwareBuffer` import helper plus a display-composite
  renderer-owned clean feedback texture for MediaProjection recapture
  diagnostics.
- Runtime profile, property manifest, damaged-profile, docs, and static checks
  that keep this route native-image-reader based and ban high-rate JSON/CPU
  pixel-copy paths.

## Non-Scope

- No recursive visual feedback effect is accepted from descriptor markers alone;
  screenshot/headset evidence remains the acceptance gate.
- No raw camera, passthrough texture, environment-depth, or geometry truth is
  inferred from MediaProjection.
- No high-rate JSON frame payload, CPU plane readback, `ByteBuffer`, or
  `copyPixelsFromBuffer` transport is allowed.
- No refactor of the full Camera2 projection renderer in the first slice.

## Authority

- Android framework: MediaProjection consent/token and `VirtualDisplay`
  production.
- Java foreground service: projection lifecycle adapter only.
- Rust display-composite native stream: `AImageReader` source, frame callback,
  `AHardwareBuffer` descriptor evidence, queue/drop counters.
- Rust runtime profile parser: low-rate display-composite settings authority.
- Vulkan import module: generic GPU import/cache/fence-retirement authority for
  `AHardwareBuffer`.
- Display-composite feedback renderer: direct sampled-frame diagnostic and
  app-owned clean feedback texture diagnostic.
- `xr_vulkan.rs`: OpenXR/Vulkan session and frame submission authority only; it
  should call small module facades rather than own producer-specific details.

## Data-Plane Shape

```text
ControlPanelActivity
  -> createScreenCaptureIntent()
  -> DisplayCompositeProjectionService
  -> Rust nativeCreateDisplayCompositeSurface()
  -> AImageReader_newWithUsage()
  -> AImageReader_getWindow()
  -> ANativeWindow_toSurface()
  -> MediaProjection.createVirtualDisplay(surface)
  -> AImageReader callback
  -> AImage_getHardwareBuffer()
  -> AndroidHardwareBufferHandle::acquire()
  -> descriptor/witness markers
  -> Vulkan sampled-image import
  -> optional clean feedback texture
```

## Module Boundaries

### Java Adapter

`DisplayCompositeProjectionService` should not receive per-frame image objects.
Its long-term job is to:

- run as a foreground `mediaProjection` service;
- receive approved MediaProjection result data;
- ask Rust for a `Surface`;
- create and destroy the `VirtualDisplay`;
- call Rust lifecycle hooks for start/stop/error markers.

It must not call `ImageReader.newInstance`, `Image.getHardwareBuffer`,
`Image.getPlanes`, `ByteBuffer`, or `copyPixelsFromBuffer`.

### Shared Android Hardware Buffer Helper

`android_hardware_buffer.rs` owns the generic Rust handle:

- `AndroidHardwareBufferHandle::acquire(ptr)`;
- `Clone`/`Drop` reference counting through `AHardwareBuffer_acquire` and
  `AHardwareBuffer_release`;
- descriptor/id querying helpers.

Camera2 and display-composite code should use this shared helper rather than
embedding their own handle wrappers.

### Native Display-Composite Stream

`display_composite_native_stream.rs` owns:

- `AImageReader` allocation and deletion;
- `ANativeWindow` acquisition/release;
- Java `Surface` creation through `ANativeWindow_toSurface`;
- `AImageReader_ImageListener` and `AImageReader_BufferRemovedListener`;
- `AImageReader_acquireLatestImage`;
- `AImage_getTimestamp`;
- `AImage_getHardwareBuffer`;
- bounded frame/drop/error counters;
- descriptor markers.

It is a producer/source module. It should not own Vulkan render passes,
OpenXR session state, or projection composition policy.

### Future Generic Vulkan Import Module

The next GPU render slice should introduce an `ahardware_buffer_vulkan` module
with:

- Vulkan `AHardwareBuffer` property query;
- image/memory allocation and binding;
- image view/sampler/descriptor creation;
- import cache keyed by buffer id/format/size;
- image layout transition helpers;
- in-flight frame-slot protection and fence-retirement cleanup;
- feature markers for external format, sampler conversion, and fallback reason.

Camera2 and MediaProjection renderers should both use this module. Camera2 may
keep YCbCr-specific conversion policy above it; display-composite RGBA can use
a simpler sampled-image path.

## Format Policy

The initial native display-composite source uses `AIMAGE_FORMAT_RGBA_8888` with
`AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT`
because it is predictable for `VirtualDisplay` production and later Vulkan
sampling. The NDK notes that `AIMAGE_FORMAT_PRIVATE` can be more efficient when
the consumer only needs `AHardwareBuffer`; keep it as a future Quest-specific
fast-path profile after headset evidence proves that MediaProjection accepts the
format and the Vulkan import path handles its external format correctly.

## Implementation Steps

1. Replace the Java per-frame `ImageReader` service path with a Java
   `VirtualDisplay` adapter that receives a Rust-created `Surface`.
2. Extend the local NDK FFI with `AIMAGE_FORMAT_RGBA_8888`,
   `AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT`, and `ANativeWindow_toSurface`.
3. Move `AndroidHardwareBufferHandle` into a shared module and update Camera2
   to use it.
4. Add `display_composite_native_stream.rs` with:
   - global single-stream lifecycle;
   - `nativeCreateDisplayCompositeSurface(width, height, maxImages, fpsCap)`;
   - `nativeStopDisplayCompositeStream()`;
   - native image and buffer-removed callbacks;
   - descriptor markers that state `nativeImageReader=true` and
     `javaHardwareBufferBridge=false`.
5. Update display-composite settings markers to name the native image-reader
   transport, then distinguish witness-only startup from
   `displayCompositeGpuImportReady=true` render frames.
6. Extract `ahardware_buffer_vulkan.rs` and route Camera2 Vulkan import through
   it while leaving YCbCr conversion and descriptor policy camera-owned.
7. Add `Invoke-NativeRendererDisplayCompositeSmoke.ps1` for serial-scoped
   Quest validation of MediaProjection native stream markers.
8. Update profile fixtures, expected markers, docs, and static checks.
9. Validate with formatter, native crate tests, profile matrix, property parity,
   Android static checks, APK build, and full repo `check_all.ps1`.

## Validation Gates

- `cargo fmt --manifest-path apps/native-renderer-android/native/Cargo.toml`
- `cargo test --manifest-path apps/native-renderer-android/native/Cargo.toml display_composite`
- `python tools/check_native_renderer_property_parity.py --out local-artifacts/native-renderer-property-parity.json`
- `pwsh -NoProfile -ExecutionPolicy Bypass -File tools/Test-NativeRendererProfileMatrix.ps1`
- `pwsh -NoProfile -ExecutionPolicy Bypass -File tools/Test-NativeRendererAndroid.ps1 -SkipProfileMatrix`
- `pwsh -NoProfile -ExecutionPolicy Bypass -File tools/Build-NativeRendererAndroid.ps1`
- `pwsh -NoProfile -ExecutionPolicy Bypass -File tools/check_all.ps1`
- `pwsh -NoProfile -ExecutionPolicy Bypass -File tools/Invoke-NativeRendererDisplayCompositeSmoke.ps1 -ApkPath target/native-renderer-android/rusty-quest-native-renderer.apk -Serial <quest-serial> -RunSeconds 12`

Device validation is owned by `$meta-quest-workflow` and the smoke wrapper:

1. Apply the display-composite runtime profile.
2. Pregrant `PROJECT_MEDIA` for lab use.
3. Launch `ControlPanelActivity` with a fresh
   `display_composite_request_token`.
4. Collect PID-scoped logcat and a screenshot.
5. Verify `display-composite-service`,
   `display-composite-native-stream`, and
   `display-composite-ahardware-buffer` markers.
6. Reset `PROJECT_MEDIA` to default.

## Observability

Required markers:

- service lifecycle: start requested, started, stopped, error;
- native stream lifecycle: surface-created, stopped, reader creation errors;
- frame acquisition: frame index, timestamp, descriptor size/format/usage,
  hardware-buffer id/status, queue bounds, fps cap, dropped frame count;
- source boundary: `sourceAuthority=android-mediaprojection`,
  `displayCompositeStream=display_composite`, `rawCamera=false`,
  `passthroughTexture=false`, `environmentDepth=false`,
  `geometryWitness=false`, `highRateJsonPayload=false`;
- implementation boundary: `nativeImageReader=true`,
  `javaHardwareBufferBridge=false`, `cpuPixelCopy=false`,
  `displayCompositeGpuImportReady=true` once a frame is sampled, and
  `displayCompositeRecursiveFeedbackSource=media-projection-current-frame-clean` when the
  recursive diagnostic mode is active, plus
  `displayCompositeFinalAlphaMode=premultiplied-openxr-projection-layer`.
- target geometry: default 16:9 display-composite feedback uses a centered
  shrunken screen-UV footprint of roughly `0.42 x 0.236` so MediaProjection
  recapture has visible spatial contraction.

## Future Slices

1. Capture headset screenshot evidence for `gpu-recursive-feedback-diagnostic`
   and keep iterating until the visual effect is visible, not just imported.
2. Tighten performance evidence for the recursive texture pass with GPU timing
   markers or Perfetto once the visual target is accepted.
3. Add a Quest-only `AIMAGE_FORMAT_PRIVATE` fast path if runtime evidence shows
   lower latency or lower memory bandwidth than RGBA without breaking import.
4. Preserve the evidence distinction between descriptor witness,
   GPU-import-ready, sampled-into-target, clean MediaProjection feedback, and
   visible-headset feedback.
