# Rusty Quest Spatial Video Control Example

This additive public example shows the narrow `trusted_local_http_v1` control
surface for a Meta Spatial SDK video player. The controller is a packaged,
same-origin web page intended for Safari or another modern browser on the same
trusted LAN or private hotspot. The protocol authenticates a short local
session; plain HTTP/WebSocket provides **no confidentiality**.

The source slice is deliberately inert:

- local control is disabled at process start;
- only a visible wearer action may request a bounded enable grant;
- the wearer must manually communicate the displayed IP/port and single-use
  pairing code;
- only one controller lease can exist;
- idle/session expiry, replay rejection, strict rates, and revoke are owned by
  the pinned process-local Rust Manifold composite;
- QR and mDNS are optional future conveniences and are not implemented;
- there is no relay, Fleet operation, arbitrary headset discovery, ADB,
  intent/shell bridge, upload surface, or runtime-loaded UI.

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
Manifold commit `1fe0f786a10371334c00296a5b4deff493796af1`. Rust sources trusted
wall/monotonic clock observations and token entropy; Kotlin owns only the
six-digit code, its one-use verification, the opaque HTTP cookie, and the
remote-address transport binding.

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

## Source layout

- `contracts/`: canonical command and bundled-video registries.
- `host/src/main/java/`: Android-compatible pure Java protocol, authority port,
  coordinator, and HTTP/WebSocket server.
- `host/src/test/java/`: fake authority/player and loopback conformance tests.
- `app/src/main/assets/control/`: packaged controller page; no external assets.
- `app/src/main/java/`: opt-in Android, Media3, and Spatial SDK adapter source.
- `native/`: closed JNI operations and the exact Manifold source lock.
- `app/src/main/media-source/`: deterministic CC0 320x180/30fps synthetic MP4
  source blobs kept inside the Quest hardware-decoder compatibility profile
  decoded only at Android build time.

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
not install, launch, bind a LAN socket, or use ADB. Manual address entry and the
single-use code remain mandatory even if QR or mDNS is added later.
