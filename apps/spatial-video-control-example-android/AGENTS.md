# Spatial Video Control Example Agent Notes

This subtree is the complete source boundary for the public
`trusted_local_http_v1` Spatial SDK example. Do not register it in another
Quest app, change repository-root Gradle files, or borrow source from Spatial
Camera Panel.

The listener is disabled by default. A wearer action in the visible foreground
app may request one short bounded enable grant from Manifold. Manifold remains
the sole owner of pairing, admission, the single controller lease, replay,
expiry, revocation, rate limits, and command acceptance. Quest owns only the
effective player state and its monotonically increasing player revision.

The closed command set is defined only by
`contracts/trusted_local_http_v1.commands.registry.json`. Keep the packaged web
assets same-origin and build-time fixed. Never add permissive CORS, remote
scripts, uploaded/runtime UI, arbitrary URLs or paths, shell/ADB/intent
dispatch, executable discovery, plugin discovery, or generic command/MCP
execution.

For this source-only slice run:

```powershell
pwsh -NoProfile -File .\tools\Test-TrustedLocalControlSource.ps1
git diff --check
```

The test server may bind only a loopback address and port `0`. Do not build an
APK, advertise mDNS, open a LAN listener, use a fixed bridge port, or touch a
device from this subtree.
