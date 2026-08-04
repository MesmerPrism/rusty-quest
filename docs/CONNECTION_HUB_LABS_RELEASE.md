# Rusty Connection Hub Labs release

Rusty Connection Hub is the standalone on-device connection owner for bounded
Rusty Morphospace control surfaces. Start or stop it from its native 2D Quest
panel; supported apps can then publish their registered control surface without
owning the connection lifetime. Switching or closing a provider app does not
implicitly stop the Hub.

## Labs security posture

The initial Labs transport is deliberately retained as an explicit option:

- `transport_classification=trusted_lan_experimental`
- `confidentiality=none`
- `production_eligible=false`
- the listener is stopped by default and starts only from the visible wearer UI;
- a pairing code establishes controller admission, but it does **not** encrypt
  WebSocket traffic;
- enable it only on a private, trusted LAN. Do not use it on public, guest, or
  otherwise untrusted Wi-Fi.

The plaintext option is not a hidden fallback and must not be presented as a
secure Internet-facing mode. A later encrypted transport can coexist with it;
adding that transport must not silently broaden the plaintext route.

## Install and connect

1. Install the APK from the Connection Hub GitHub prerelease or the Rusty
   Morphospace guided installer entry. Confirm the package is
   `io.github.mesmerprism.rustymanifold.broker` and retain the published SHA-256.
2. Open **Rusty Connection Hub** on the Quest and choose **Start paired
   connection**. Keep the pairing code private.
3. On the controller computer, use the published Hostess CLI with both
   `--transport-classification trusted_lan_experimental` and
   `--allow-insecure-trusted-lan`. Supply the pairing code through stdin or a
   dedicated file descriptor, never as a command-line argument.
4. Launch a compatible provider app. Its bounded surface appears on the existing
   connection. Launching a second compatible app replaces or augments surfaces
   according to its registered leases; the Hub remains active.
5. Use **Stop connection** or the Hostess `revoke` command to end the session on
   purpose. Revocation must delete the controller's local session credentials.

The exact CLI verbs, receipt schemas, fixed surface registry, cleanup behavior,
and autonomous Quest validation are documented in
`docs/CONNECTION_HUB_OPERATOR.md`. The release build excludes the shell-only
debug operator used by the device evidence suite.

## Release build

`tools/Build-ConnectionHubLabsRelease.ps1` requires clean exact Rusty Quest and
Rusty Manifold source trees, one explicit keystore, the expected signer digest,
an Android version code/name, and a fresh output directory. It emits the signed
APK plus `connection-hub-release-manifest.json`, which binds source revisions,
signer, APK bytes, version identity, release-only manifest exclusions, and the
plaintext transport posture.
