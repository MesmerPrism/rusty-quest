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
  `ManifoldAuthorityPort`;
- QR and mDNS are optional future conveniences and are not implemented;
- there is no relay, Fleet operation, arbitrary headset discovery, ADB,
  intent/shell bridge, upload surface, or runtime-loaded UI.

## Authority and effect boundary

The browser sends one canonical command envelope at a time. The authority port
returns `command_accepted` before Quest invokes the player. Acceptance is not
application effect. `command_applied` is emitted only after the fake player or
Media3 callback reports the effective state and advances the Quest-owned player
revision. Every receipt retains the request id, expected authority revision,
expected player revision, and accepted authority revision.

`ManifoldAuthorityPort` is an adapter contract, not a second authority
implementation. `FakeManifoldAuthority` exists under `host/src/test` only for
offline tests. The Android example must remain disabled until a real
process-local Manifold provider is injected.

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
- `app/src/main/media-source/`: deterministic CC0 synthetic MP4 source blobs
  decoded only at Android build time.

## Source-only validation

```powershell
pwsh -NoProfile -File .\tools\Test-TrustedLocalControlSource.ps1
git diff --check
```

The gate compiles and runs the pure Java tests, verifies the exact registries,
checks same-origin web constraints, and confirms that the only exercised
listener is loopback with port `0`. It performs no dependency download, Gradle
assembly, APK install, LAN bind, mDNS advertisement, ADB action, or headset
operation.

## Later Android gate

Android packaging is intentionally not validated in this slice. Before an APK
build, bind a real `ManifoldAuthorityPort`, review the foreground lifecycle and
network-security configuration, then validate Media3 callback causality and
Spatial SDK panel behavior on a clean source revision. Manual address entry and
single-use pairing remain mandatory even if QR or mDNS is added later.
