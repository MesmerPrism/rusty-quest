# Spatial immersive-video playback

The Spatial Camera Panel app has an explicit, default-off immersive-video
launch route for local media. It uses the Meta Spatial SDK's direct-to-surface
media panel path and AndroidX Media3 ExoPlayer. Source videos are staged as
shared video media and opened through a single MediaStore URI read grant; they
are not packaged into the ordinary APK, and the app requests no broad media
access.

For offline sideload bundles, the same route can instead read an authenticated
encrypted media pack. The preferred thin-APK route keeps packs in the shared
`Documents/RustySpatialMedia/offline-media-packs/` library and asks the user to
select `RustySpatialMedia` once with Android's Storage Access Framework. The
persisted read grant lets updates read ciphertext directly without copying it
into app storage. A prototype APK may still package encrypted chunks as assets
and import only that ciphertext into app-private storage on first launch.
Media3 receives a seekable virtual byte stream backed by independently
encrypted AES-256-GCM chunks. Each chunk is decrypted in memory on demand; the
app never writes a plaintext video file. The decoded surface and Spatial SDK
projection path are identical to the ordinary local-file route when the direct
media panel is active.

An authenticated side-by-side or top-bottom stereo pack may instead feed the
app's existing custom stereo projection compositor. Android `MediaExtractor`
reads the same random-access decrypted byte stream through `MediaDataSource`,
`MediaCodec` decodes directly to the Rust-owned surface, and the Vulkan
compositor maps the declared eye regions into its packed-SBS target before
combining the video with the camera and private projection/effect stack. The
encrypted video route and the custom stack are therefore no longer
activity-level exclusive features.

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
on the initial viewer pose and uses a 50 m default radius. Its entity remains
in the world reference space: head movement changes the view into the
hemisphere or sphere rather than moving the video with the viewer's head.

Direct-to-surface is intentional. Meta documents it as the high-performance
path for high-resolution and non-rectilinear media. The readable surface path
is lower performance, is only needed for custom shader sampling, and has a
current mesh/layer known issue. The app therefore does not request
`ReadableVideoSurfacePanelRegistration` for this route.

Projection inner-alpha controls do not change this boundary. They apply to the
custom processed-core projection and its optional stretch output, not to
individual pixels of the direct 180/360 video carrier. Whole-layer video
opacity is adapter-owned only for the bounded selection transition: the direct
carrier fades out, one decoder/panel source is swapped while hidden, and the
replacement fades in after its first rendered frame. This is not a persistent
opacity control and does not extend the custom projection or shader ABI.

Reference implementation and API guidance:

- [Meta Spatial SDK media playback](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-media-playback/)
- [Meta Spatial SDK known issues](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-known-issues/)
- [Meta Spatial SDK Premium Media sample](https://github.com/meta-quest/Meta-Spatial-SDK-Samples/tree/main/PremiumMediaSample)
- [Meta Spatial SDK Spatial Video sample](https://github.com/meta-quest/Meta-Spatial-SDK-Samples/tree/main/SpatialVideoSample)

The adapter tracks Spatial SDK `0.13.2` and Media3 `1.4.1`, matching the
current official media samples when this route was introduced.

## Switchable offline sessions

Packaged pack directories are discovered only under the fixed
`offline-media-packs/` asset namespace. Previously imported encrypted packs
may also be discovered under the fixed app-private
`files/offline-media-packs/` directory. The selected shared document tree is
also searched only beneath its fixed `offline-media-packs/` child. Discovery
admits at most 32 valid pack directory tokens with a manifest; the loader still
validates the complete authenticated manifest and chunks before exposing a
pack to the session.
Raw filenames and private playlist labels are not part of the public contract.

One custom-projection session may contain authenticated SBS and top-bottom
stereo packs with different declared shapes, per-eye aspect ratios, and encoded
resolutions. Mono packs remain outside that stereo session. Each decoder
surface is scaled proportionally to fit within 4096 pixels on either axis while
retaining an even packed width for SBS or even packed height for top-bottom.
The source manifest keeps its ideal direct Spatial SDK shape and stereo mode;
the private compositor always normalizes its result to packed SBS before the
Spatial SDK carrier presents it.

`World anchored` uses the source's declared flat, equirectangular 180°, or
equirectangular 360° Spatial SDK surface and retains its initial world-space
pose as the viewer turns. Supported `Head-fixed` stereo instead enters the
existing planar Vulkan camera/effect compositor. Center independently selects
Projection, Video, Projection + Video, or Transparent; Middle selects Continue
Outer, Stretch, or Transparent; Outer selects Video, Stretch, or Transparent.
Full head-fixed video is Center Video plus Buffer Off and Outer Video on that
same carrier. Camera-ray mapping, blend zones, displacement, and private tuning
therefore stay planar and video can blend with projection without a second
panel or decoder. The direct head-fixed Spatial panel remains a bounded
fallback/diagnostic route when the compositor cannot accept a source. The
control panel remains above the visual carrier.

The Media library shows a sanitized selectable row for every accepted encrypted
pack and shared plain video, plus Previous and Next actions. It exposes source
kind, shape, stereo layout, and dimensions but never storage paths or raw media
names. Refresh scans and validates on a background worker, then adopts the
bounded catalog through fixed registration slots without recreating the
Activity, control panel, current carrier, or current decoder. A horizontal
right-stick flick selects the previous item on the left or the next item on the
right. The stick must return near neutral before another selection, which
prevents a held stick from skipping multiple items. Both Spatial SDK
controller-component input and Android joystick fallback input feed the same
latch.

Meta system passthrough is an always-on scene substrate. Black and video panels
occlude it; Transparent removes the opaque black backing; LUT does the same and
styles only the system passthrough layer. Background selection never disables
or repeatedly re-enables system passthrough. Background has its own panel page;
the Media library is limited to video files, selection, presentation, cadence,
and playback. The Background page exposes LUT animation, color strength, color
cycle speed, and black cutoff. LUT construction runs off the main thread and
only the final Spatial SDK scene mutation returns to the main scope; static LUT
mode applies once and schedules no periodic LUT update.

Turning ordinary video playback off retains the registered carrier and its
Surface. It releases the zero-contribution decoder on a dedicated lifecycle
executor/Media3 looper, never on the Activity/XR thread. A rapid off/on change
generation-fences the stale stop and keeps the live decoder. Turning playback
back on reuses the carrier when its Surface remains valid; only explicit source
or presentation changes may rebuild the direct fallback carrier.

Validation clients may also send `video-previous`, `video-next`,
`video-select`, `video-recenter`, `video-world-anchored`, or
`video-head-fixed-border` through the existing `RUN_UI_COMMAND` action;
`video-select` carries an opaque `video_pack_id`. Right-controller secondary/B
invokes the same recenter operation and applies a fresh viewer-derived pose to
the existing direct-video entity without recreating its player, entity, the
Activity, or the custom projection carrier.

A selection fades only the direct Spatial SDK video layer to transparent,
rebuilds its one media-panel entity and ExoPlayer while hidden, waits for the
new first-frame callback, and fades that layer back to opaque. A second
selection during this transition is rejected deterministically. The custom
projection route replaces only its media decoder/source; its planar carrier,
camera runtime, private configuration, Activity, and control state remain
alive. Presentation-mode changes remain explicit carrier changes rather than
part of ordinary source selection.

Performance validation must use the non-debuggable release variant:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-SpatialCameraPanelAndroid.ps1 `
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
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Stage-SpatialCameraPanelImmersiveVideo.ps1 `
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
   `/sdcard/Movies/RustyQuestImmersiveVideo/v.<extension>` and indexes that exact
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

## Offline encrypted sideload bundles

Create a content-addressed pack outside source control with PowerShell 7:

```powershell
$env:RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX = "<64 hexadecimal characters>"
pwsh -NoProfile -File .\tools\New-SpatialCameraPanelOfflineMediaPack.ps1 `
  -SourcePath <local-video.mp4> `
  -OutDir <local-bundle-media-directory> `
  -PackId <content-addressed-pack-id> `
  -Shape equirect-180 `
  -Stereo side-by-side-left-right
```

For the preferred thin-APK workflow, build with the key but without
`RUSTY_QUEST_OFFLINE_MEDIA_PACK_ASSET_DIR`. The installer stages ciphertext in
the shared Documents library:

```powershell
pwsh -NoProfile -File .\tools\Install-SpatialCameraPanelOfflineMediaPack.ps1 `
  -Serial <quest-serial> `
  -ApkPath <thin-release.apk> `
  -PackDirectory <local-bundle-media-directory\pack-id>
```

In the app's Video page, choose `RustySpatialMedia` once. Android retains the
folder and its ciphertext across app uninstall/reinstall, but a full uninstall
removes the app's persisted URI grant, so the surviving folder must be selected
again. In-place APK updates retain the grant.

For a self-contained prototype instead, place the pack directory under
`offline-media-packs/<pack-id>/` in an ignored asset root, set
`RUSTY_QUEST_OFFLINE_MEDIA_PACK_ASSET_DIR` to that root, and use:

```powershell
pwsh -NoProfile -File .\tools\Install-SpatialCameraPanelOfflineMediaPack.ps1 `
  -Serial <quest-serial> `
  -ApkPath <release.apk> `
  -PackDirectory <local-bundle-media-directory\pack-id> `
  -PackagedInApk `
  -Launch
```

The pack manifest records classification, encoded dimensions, source length
and hash, chunk boundaries, random nonces, and ciphertext hashes. Stable
manifest fields are also bound into each chunk's AES-GCM associated data, so a
classification, ordering, or content change fails authentication. The
installer verifies every encrypted asset inside the exact APK before install.

`RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX` is deliberately a build-time input and must
never be committed, printed, copied into the media pack, or written to a build
receipt. When supplied, the value is compiled into `BuildConfig` and therefore
extractable from the APK by a determined recipient. This mode is suitable only
for proving offline distribution and discouraging casual raw-file access. It
is not DRM and it is not a durable confidentiality boundary. A production
hardening pass should replace it with user- or device-provisioned key material
and Android Keystore wrapping.

The shareable prototype bundle can consist of only the self-contained APK and
an installer helper. Packaging large libraries this way makes the APK
correspondingly large and requires an APK rebuild to replace content. The
shared document-tree route avoids both costs while preserving authenticated,
in-memory chunk decryption.

For ordinary development rebuilds, keep the same key input, omit the packaged
asset directory, and reinstall the thin APK with `adb install -r`. The bounded
catalog discovers and authenticates the already staged shared packs at runtime.
A clean installation without a selected folder or valid pack fails closed
instead of synthesizing a catalog.

## Fail-closed behavior

The immersive route is not lifecycle-exclusive. A valid direct-media request
adds its ideal Spatial SDK media panel without short-circuiting ordinary scene,
VR-ready, tick, or control-panel setup. When the custom stereo projection route
is active and the selected encrypted pack has a supported stereo packing, the
direct media panel is suppressed to prevent duplicate rendering and that pack
becomes the custom compositor's video source. SBS and top-bottom sources use
their declared per-eye UV regions; both feed the compositor's existing
packed-SBS full-eye target.

An invalid request registers no panel and emits
`channel=spatial-immersive-video status=route-rejected ... failClosed=true`.
It never falls back to a guessed projection or the normal camera presentation.
Only a readable, explicitly granted MediaStore video URI or a canonical file
inside the app-owned `immersive-video` directory is accepted. The offline
variant additionally accepts a validated pack ID rooted under app-private
`files/offline-media-packs` or the persisted shared document tree's fixed
`offline-media-packs` child. Packaged assets are imported only from the fixed
`offline-media-packs/<validated-pack-id>/` namespace; arbitrary manifest paths
are not accepted.

Useful runtime markers include:

- `status=route-ready`
- `status=panel-ready`
- `status=entity-spawned`
- `status=player-preparing`
- `status=decoded-video-size`
- `status=first-frame-rendered`
- `status=playback-error`
- `status=encrypted-chunk-decrypted`
- `status=encrypted-chunk-error`
- `status=catalog-ready`
- `status=controller-flick-selection`
- `status=selection-applied`
- `status=direct-layer-fade-out-started`
- `status=direct-layer-source-swap`
- `status=direct-layer-fade-in-started`
- `status=direct-layer-transition-complete`
- `status=recenter-applied`
- `status=source-switch-applied`
- `status=presentation-mode-applied`
- `status=custom-carrier-rebuilt`

Host checks live in
`tools/checks/Test-SpatialCameraPanelImmersiveVideoStatic.ps1`, and pure route
tests cover 180° SBS, 360° mono, flat top-bottom, default-off behavior,
single-URI media access, unknown classifications, path confinement, mixed
encrypted stereo catalogs, layout-aware resolution scaling and source
rectangles, and selection wraparound.
