# Spatial immersive-video playback

The Spatial Camera Panel app has an explicit, default-off immersive-video
launch route for local media. It uses the Meta Spatial SDK's direct-to-surface
media panel path and AndroidX Media3 ExoPlayer. Source videos are staged as
shared video media and opened through a single MediaStore URI read grant; they
are never packaged into the APK, and the app requests no broad media access.

This is generic public adapter infrastructure. Media libraries, private
filenames, classification evidence, artistic content, and effect-specific
playlists do not belong in this repository.

## Representation contract

The caller must supply the verified projection shape, stereo layout, and
encoded pixel dimensions. The app does not guess from a filename or embedded
spherical metadata.

| Content geometry | Spatial SDK shape | Stereo input | Spatial SDK stereo mode |
| --- | --- | --- | --- |
| Rectilinear/flat | `QuadShapeOptions` | mono | `StereoMode.None` |
| Rectilinear/flat | `QuadShapeOptions` | side-by-side | `StereoMode.LeftRight` |
| Rectilinear/flat | `QuadShapeOptions` | top-bottom | `StereoMode.UpDown` |
| Equirectangular 180° | `Equirect180ShapeOptions` | mono/SBS/top-bottom | matching mode above |
| Equirectangular 360° | `Equirect360ShapeOptions` | mono/SBS/top-bottom | matching mode above |

All routes use `VideoSurfacePanelRegistration`, `PixelDisplayOptions` at the
encoded source dimensions, and `MediaPanelRenderOptions`. Equirectangular
content is placed behind ordinary UI with `zIndex=-1`. The sphere is centered
on the initial viewer pose and uses a 50 m default radius.

Direct-to-surface is intentional. Meta documents it as the high-performance
path for high-resolution and non-rectilinear media. The readable surface path
is lower performance, is only needed for custom shader sampling, and has a
current mesh/layer known issue. The app therefore does not request
`ReadableVideoSurfacePanelRegistration` for this route.

Reference implementation and API guidance:

- [Meta Spatial SDK media playback](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-media-playback/)
- [Meta Spatial SDK known issues](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-known-issues/)
- [Meta Spatial SDK Premium Media sample](https://github.com/meta-quest/Meta-Spatial-SDK-Samples/tree/main/PremiumMediaSample)
- [Meta Spatial SDK Spatial Video sample](https://github.com/meta-quest/Meta-Spatial-SDK-Samples/tree/main/SpatialVideoSample)

The adapter tracks Spatial SDK `0.13.2` and Media3 `1.4.1`, matching the
current official media samples when this route was introduced.

Performance validation must use the non-debuggable release variant:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-SpatialCameraPanelAndroid.ps1 `
  -BuildType Release `
  -ApkFileName spatial-camera-panel-immersive-video-release.apk
```

The build wrapper still defaults to `Debug` for existing diagnostic workflows.
Release uses the same local development signing identity but disables Android
debuggable behavior and code shrinking, so marker text remains observable
without introducing minifier variance.

## Staging and launch

Use the serial-scoped wrapper with an explicit classification:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Stage-SpatialCameraPanelImmersiveVideo.ps1 `
  -Serial <quest-serial> `
  -SourcePath <local-video.mp4> `
  -Shape equirect-180 `
  -Stereo side-by-side-left-right `
  -Launch
```

Accepted shape tokens are `flat`, `equirect-180`, and `equirect-360`.
Accepted stereo tokens are `mono`, `side-by-side-left-right`, and
`top-bottom`.

The wrapper:

1. reads the encoded dimensions with `ffprobe`;
2. hashes the host file;
3. pushes it to
   `/sdcard/Movies/RustyMorphovision/v.<extension>` and indexes that exact
   file in Android MediaStore;
4. verifies byte length and SHA-256 on the exact device;
5. stops only the target package when `-Launch` is supplied;
6. launches the explicit immersive route with the exact MediaStore video URI
   and a single-URI read grant, without requesting broad shared-storage
   permission;
7. checks bounded logcat route, transport, decoded-size, first-frame,
   advancing-position, target-process liveness, playback-error, and fatal
   signals; and
8. stores its receipt and compositor screenshots under ignored
   `local-artifacts/`.

No shared-storage permission is needed. The script never starts, stops, or
reconfigures the ADB daemon.

## Fail-closed behavior

The immersive route is exclusive when explicitly requested. A valid request
registers only the immersive media panel and suppresses the camera/effect
presentation stack for that launch.

An invalid request registers no panel and emits
`channel=spatial-immersive-video status=route-rejected ... failClosed=true`.
It never falls back to a guessed projection or the normal camera presentation.
Only a readable, explicitly granted MediaStore video URI or a canonical file
inside the app-owned `immersive-video` directory is accepted.

Useful runtime markers include:

- `status=route-ready`
- `status=panel-ready`
- `status=entity-spawned`
- `status=player-preparing`
- `status=decoded-video-size`
- `status=first-frame-rendered`
- `status=playback-error`

Host checks live in
`tools/checks/Test-SpatialCameraPanelImmersiveVideoStatic.ps1`, and pure route
tests cover 180° SBS, 360° mono, flat top-bottom, default-off behavior,
single-URI media access, unknown classifications, path confinement, and
packed-layout geometry.
