# Rusty Quest Package Updater

This directory contains a separate native 2D, attended package-updater app:

```text
io.github.mesmerprism.rustyquest.packageupdater/.PackageUpdaterActivity
```

It is intentionally not part of the Store launcher. The Store launcher remains
unprivileged; this sideloaded package alone requests Android's
`REQUEST_INSTALL_PACKAGES` capability. On consumer headsets Android still
requires the wearer to enable this app as an unknown-app source and approve
each Package Installer confirmation UI.

## Authority boundary

The app has only `INTERNET` and `REQUEST_INSTALL_PACKAGES`. It has no
Accessibility, HOME, boot, device-owner, storage, camera, microphone, overlay,
notification, service, or package-enumeration authority. Its launcher Activity
is the only exported component. The Package Installer callback receiver is
non-exported and validates an app-private callback token and persisted session
id.

The manifest endpoint, trusted Ed25519 key, key id, and exact
package/ring/signer/origin policy are compile-time values. They are never taken
from an Intent, QR code, clipboard, editable text field, downloaded
configuration, or public storage:

```text
RUSTY_QUEST_PACKAGE_UPDATER_MANIFEST_URL
RUSTY_QUEST_PACKAGE_UPDATER_TRUSTED_KEY_ID
RUSTY_QUEST_PACKAGE_UPDATER_TRUSTED_PUBLIC_KEY_BASE64
RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_HTTPS_ORIGIN
RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_PACKAGE_NAME
RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_ROLLOUT_RING
RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_SIGNER_SHA256
```

The default public-key value is empty, so an ordinary source build fails closed
before accepting any update. A release build must inject the 32-byte raw
Ed25519 public key as standard Base64 and the exact matching key id.

## Signed envelope

The endpoint returns the exact Rust-owned
`rusty.quest.package_update_manifest_envelope.v1`. Its `signed` member is RFC
8785/JCS canonicalized and signed with Ed25519 over:

```text
rusty.quest.package_update_manifest.v1\0<JCS signed manifest bytes>
```

The signed member is the strict, one-APK
`rusty.quest.package_update_manifest.v1` object. The verifier rejects
unknown/missing keys, non-JCS-safe numbers, wrong key ids or algorithms,
invalid signatures, package/ring/signer/origin policy mismatches, rollback
sequences or versions, expired or excessively long-lived manifests,
non-HTTPS URLs, and invalid APK hashes/signers/sizes.

Verified APKs are downloaded with redirects disabled and bounded time/size into
the app's no-backup private directory. Before installation, Android's archive
parser must read back the exact signed package, version code, and sole signer
certificate. Existing installations, when present, must have that signer too.

Each installation uses `PackageInstaller.SessionParams.MODE_FULL_INSTALL` with
`USER_ACTION_REQUIRED`. The session, callback token, target identity, staged
hash, and state are persisted before commit. The non-exported callback receiver
launches only the Package Installer confirmation Intent associated with that
exact session. Success is not accepted until the installed package/version/
signer are read back. A wearer rejection is recorded as a terminal cancellation
without advancing rollback state. The visible cancel control can abandon a
stale pending session, and restart reconciliation either confirms an exact
installed readback or keeps the attended session visibly pending.

One package-specific signed channel is staged per check. A fleet can provision
several fixed updater channel builds, or a later contract version can define a
signed index without weakening this one-artifact v1. Every consumer-headset
package change remains individually visible and attended.

## Build

This is a dependency-free Android Gradle project using the repository's
compile/target SDK 34 and Java 17 baseline:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File `
  .\tools\checks\Test-PackageUpdaterAndroidStatic.ps1 -RepoRoot .

& <gradle-8.13-or-newer>\bin\gradle.bat `
  -p .\apps\package-updater-android :app:assembleDebug
```

Release signing uses only the `RUSTY_QUEST_PACKAGE_UPDATER_KEYSTORE_*`
environment variables. All four signing values must be supplied together, and
release assembly fails closed when they are absent. No secret is stored in this
repository.

For an exact clean source revision, use
`tools/Build-PackageUpdaterAndroid.ps1`; it runs the static gate, injects the
fixed trust/policy inputs and Android signing material through the child
environment, builds the signed APK, and writes a public build manifest.
`tools/Publish-PackageUpdateManifest.ps1` then copies one exact Kiosk APK into a
bounded channel directory, signs its manifest with the seed supplied only
through `RUSTY_QUEST_UPDATE_SIGNING_SEED_BASE64URL`, and writes public release
metadata. Neither wrapper copies signing material into an output directory.
