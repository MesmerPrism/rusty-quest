# Rusty Spatial Video Player

This public Meta Spatial SDK player reads validated wearer-owned videos from a
dedicated `RustySpatialMedia` folder and registers a control surface with the
standalone Rusty Connection Hub. A browser that is already connected to the Hub
sees the player surface when the app starts and returns to the Hub unchanged
when the app exits. High-rate media stays on the headset and never crosses the
Hub WebSocket.

The older, app-local `trusted_local_http_v1` control surface remains available
as an explicitly enabled compatibility lab. Its controller is a packaged,
same-origin web page intended for Safari or another modern browser on the same
trusted LAN or private hotspot. The protocol authenticates a short local
session; plain HTTP/WebSocket provides **no confidentiality**.

The product remains deliberately inert by default:

- local control is disabled at process start;
- a visible wearer action may request a bounded paired or explicitly unsafe
  Open LAN grant;
- the debug APK additionally exposes a `DUMP`-protected, shell-UID-only
  operator provider; the release manifest contains no provider;
- the wearer must manually communicate the displayed IP/port and single-use
  pairing code;
- only one controller lease can exist;
- idle/session expiry, replay rejection, strict rates, and revoke are owned by
  the pinned process-local Rust Manifold composite;
- DNS-SD advertises only a fixed service type and non-secret mode/path metadata
  while the listener is active; it never advertises a code or bearer token;
- there is no relay, Fleet operation, arbitrary command, intent/shell bridge,
  upload surface, or runtime-loaded UI.

Open LAN is intentionally **not secure**: it has neither authentication nor
confidentiality. The first network peer to request control receives Manifold's
one bounded controller lease; other peers are rejected until expiry or visible
on-headset revoke. The browser and headset label this mode explicitly.

## User video folder

On first run, select the exact `Documents/RustySpatialMedia` folder in the
Android folder picker. The app supplies that exact directory as Android's
initial-location hint, persists only the wearer-confirmed narrow grant, and,
when write access is available, creates only this fixed taxonomy:

```text
RustySpatialMedia/
  plain-videos/
    flat/{mono,side-by-side-left-right,top-bottom}/
    equirect-180/{mono,side-by-side-left-right,top-bottom}/
    equirect-360/{mono,side-by-side-left-right,top-bottom}/
```

Copy `.mp4` files into the directory matching their actual projection and
packing, then press **Reload** or relaunch the player. Directory placement is
not sufficient by itself: the app verifies container dimensions, zero rotation,
duration, MIME type, and a decoded sample. Contradictory or unreadable files are
rejected. Top/bottom means top is left eye; side-by-side means left half is left
eye. The app requests no broad storage permission and never copies video bytes.

The complete friend/operator procedure is in
[`../../docs/SPATIAL_VIDEO_PLAYER.md`](../../docs/SPATIAL_VIDEO_PLAYER.md).

## Connection Hub surface

Install Rusty Connection Hub first and enable either paired mode or the
explicitly unsafe plaintext trusted-LAN option. Connect the browser once. When
this player launches it registers `surface.spatial_video_control.media`; the Hub
page immediately shows selected title, geometry, source kind, play state, and
Previous/Play/Pause/Next controls. Closing or switching away from the player
removes only its surface. The Hub listener and browser WebSocket stay alive and
another compatible app can replace the surface without reconnecting.

The provider client is a reducer-driven Binder session, not a callback-driven
state machine. Process/binding/session generations fence stale callbacks and
replies, broker epochs fence replacement, and registration retries reuse one
id plus the digest of the exact registration JSON. Read-only evidence may retry
within its fixed limit; token/use ambiguity closes the generation. Relative
Previous/Next effects report `outcome_unknown` instead of replaying. See
[`../../docs/CONNECTION_HUB_BINDER_ADMISSION.md`](../../docs/CONNECTION_HUB_BINDER_ADMISSION.md).

## Authority and effect boundary

The browser sends one canonical command envelope at a time. The authority port
returns `command_accepted` before Quest invokes the player. Acceptance is not
application effect. `command_applied` is emitted only after the fake player or
Media3 callback reports the effective state and advances the Quest-owned player
revision. Every receipt retains the request id, expected authority revision,
expected player revision, exact Manifold receipt id, and the resulting
local/admission/lease/host revision tuple. The browser uses `local_revision` as
the one authoritative concurrency token; the full tuple remains visible for
diagnostics and receipt verification.

`ManifoldAuthorityPort` is an adapter contract, not a second authority
implementation. `FakeManifoldAuthority` exists under `host/src/test` only for
offline tests. Android binds the typed JNI adapter under `native/` to exact
Manifold commit `3bf721eaa2f0551b01b33d8264fb7afa5f72749d`. Rust sources trusted
wall/monotonic clock observations and token entropy; Kotlin owns only the
six-digit code, its one-use verification, the opaque HTTP cookie, and the
remote-address transport binding.

The optional debug-shell actor is a separately recorded authority fact. It is
accepted only when the native policy was initialized from `BuildConfig.DEBUG`
and Android's exported debug provider independently verifies the platform shell
UID. It may open or revoke the fixed listener and read bounded app/player
status; media commands still travel through the same HTTP/WebSocket and
Manifold command path as the browser.

## Closed commands

The canonical build-time registry is
[`contracts/trusted_local_http_v1.commands.registry.json`](contracts/trusted_local_http_v1.commands.registry.json).
It contains exactly:

1. `describe`
2. `get_state`
3. `list_videos`
4. `select_video`
5. `play`
6. `pause`

Selection never implies playback.

## Projection and stereo catalog

Each closed catalog item declares its packed pixel dimensions, projection, and
stereo layout. The public synthetic catalog covers:

- flat mono;
- 180° mono, side-by-side left/right, and top-bottom;
- 360° mono, side-by-side left/right, and top-bottom.

The app maps those declarations directly to Meta Spatial SDK
`QuadShapeOptions`, `Equirect180ShapeOptions`, or
`Equirect360ShapeOptions`, and to `StereoMode.None`, `LeftRight`, or `UpDown`.
For SBS the left packed half is the left eye. For top-bottom the top packed
half is the left eye. Eye swap is deliberately not offered.

One immutable video-panel registration exists per closed descriptor. The
initial viewer pose becomes the retained world anchor and orientation reference
for 180°/360° media. Selecting another item replaces only the video entity and
decoder surface; it does not restart the Activity or control panel. A select
receipt is applied only after the matching surface is attached and a Media3
callback observes that item in `READY` state.

Debug builds also recognize four generic, fixed app-external slots for a
temporary 180-SBS, 360-top-bottom, 360-mono, or 4096x4096 HEVC60 360-top-bottom
device test. A slot appears in
`list_videos` only when its exact file exists when the process starts. The
browser cannot supply filenames, paths, URLs, projection settings, or uploads.
These slots are local test infrastructure: their media has unknown provenance,
must not be redistributed, packaged, or committed, and must be deleted from
the headset after the authorized run. Release builds ignore them.

The dormant TB ODS qualification slot is fixed as follows:

- video id: `device-test-360-top-bottom-4096x4096-hevc60`;
- app-external filename:
  `immersive-video-test/debug_test_360_top_bottom_4096x4096_hevc_60fps.mp4`;
- packed dimensions: 4096x4096, interpreted as a 4096x2048 left-eye image on
  top and a 4096x2048 right-eye image on the bottom;
- carrier: world-anchored equirectangular 360° with `StereoMode.UpDown`;
- expected qualification input: HEVC at 60 fps.

The filename and slot describe the intended test profile; they do not inspect
or certify the actual codec, frame rate, eye order, bitrate, bit depth, or
projection of a supplied file. Media3 readiness and device decoder evidence
remain required before claiming that a particular input plays correctly.

## Spatial panel placement and input

The wearer control panel is a one-sided Meta Spatial SDK UI surface. Its
preferred pose is centered one meter along the current wearer view and uses
the shared `lookRotationAroundY` front-face convention. If viewer pose is not
yet available during scene creation, the app uses the known-facing 180-degree
yaw fallback. An identity quaternion can expose the back face and make an
otherwise healthy panel appear black or absent.

The panel is a bounded `PIVOT_Y` grabbable surface. Either trigger selects its
buttons. Right-controller A is excluded from panel click input and is reserved
for one app-owned action: hide the panel, then show and recenter it on the next
press. Spatial controller-component and Android key/motion observations feed
one edge arbiter with bounded cross-route deduplication. The panel uses the
public graphite, high-contrast ink, cyan action, and amber revoke palette
without importing any external application or private effect behavior. The
application declares optional Horizon hand tracking so controller absence does
not make the app ineligible to launch; the controller-specific A-button
shortcut remains available only when a controller is present. The
facing convention is documented in
[`../../docs/SPATIAL_SDK_PANEL_FACING.md`](../../docs/SPATIAL_SDK_PANEL_FACING.md);
media-surface orientation remains a separate contract.

## Source layout

- `contracts/`: canonical command and bundled-video registries.
- `host/src/main/java/`: Android-compatible pure Java protocol, authority port,
  coordinator, and HTTP/WebSocket server.
- `host/src/test/java/`: fake authority/player and loopback conformance tests.
- `app/src/main/assets/control/`: packaged controller page; no external assets.
- `app/src/main/java/`: opt-in Android, Media3, and Spatial SDK adapter source.
- `app/src/debug/`: debug-only shell-UID/DUMP operator provider.
- `native/`: closed JNI operations and the exact Manifold source lock.
- `app/src/main/media-source/`: deterministic CC0 flat/180/360 30fps synthetic
  MP4 source blobs kept inside a bounded Quest hardware-decoder compatibility
  profile and decoded only at Android build time.

## Source-only validation

```powershell
pwsh -NoProfile -File .\tools\Test-TrustedLocalControlSource.ps1
git diff --check
```

The gate compiles and runs the pure Java tests, verifies the exact registries,
checks same-origin web constraints and native/Gradle lock wiring, and confirms
that the only exercised listener is loopback with port `0`. It performs no
dependency download, APK install, LAN bind, mDNS advertisement, ADB action, or
headset operation.

## Native and Android source gate

The Manifold commit is intentionally local and unpublished in this no-push
slice. Set `RUSTY_MANIFOLD_SOURCE_ROOT` to a clean checkout matching
`native/manifold-source.lock.json`; every native build rechecks both commit and
tree before Cargo runs. Also set `ANDROID_NDK_ROOT` to NDK 27.2 or a compatible
API-34 toolchain.

```powershell
$env:RUSTY_MANIFOLD_SOURCE_ROOT = 'X:\path\to\rusty-manifold'
$env:ANDROID_NDK_ROOT = 'X:\Android\Sdk\ndk\27.2.12479018'
pwsh -NoProfile -File .\tools\Build-NativeLocalControl.ps1
gradle :app:compileDebugKotlin :app:lintDebug :app:assembleDebug
```

Gradle 8.13 is the validated runner. These commands build source only; they do
not install, launch, bind a LAN socket, or use ADB.

## Debug device operator

Hostess owns the closed companion CLI at
`tools/trusted_local_control_cli.py`. Its ADB actions accept an exact serial and
are limited to `status`, `enable-paired`, `enable-open-lan`, `revoke`, and
`test-media`; its network-only `discover` action resolves the fixed DNS-SD
service without ADB. Pairing codes are redacted from CLI output by default and
are retained only long enough to establish the paired session. `test-media`
uses the real LAN route—no ADB
forward/reverse—and verifies describe, state, catalog, separate select, play,
pause, Media3-derived application effects, revision causality, and final revoke.

Plain Safari/Chrome pages cannot enumerate Bonjour services. DNS-SD lets a
native Apple client or host CLI discover and resolve the service; the resolved
same-origin URL then opens the packaged browser UI. Manual IP remains the
universal fallback.
