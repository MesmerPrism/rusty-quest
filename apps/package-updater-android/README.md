# Rusty Quest Package Updater

This directory contains a separate native 2D, attended package-updater app:

```text
io.github.mesmerprism.rustyquest.packageupdater.alpha/.PackageUpdaterActivity
```

It is intentionally not part of the Store launcher. The Store launcher remains
unprivileged; this sideloaded package alone requests Android's
`REQUEST_INSTALL_PACKAGES` capability. On consumer headsets Android still
requires the wearer to enable this app as an unknown-app source and approve
each Package Installer confirmation UI.

## Authority boundary

The production app has only `INTERNET` and `REQUEST_INSTALL_PACKAGES`. It has no
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

Android 14 exposes no public Ed25519 `KeyFactory` for importing that raw
build-fixed release key. Signature verification therefore crosses a narrow JNI
boundary into the repository's pinned `ed25519-dalek` Rust implementation.
The bridge accepts only raw public-key, message, and signature bytes; it has no
network, storage, package, or installer authority.

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

## Test-only adb CLI

The `e2e` build type adds a separate
`io.github.mesmerprism.rustyquest.packageupdater.e2ecli` test package. It
contains an exported `ContentProvider` protected by the platform
`android.permission.DUMP` permission and an exact Binder shell-UID check. The
provider accepts only `check`, `status`, and `cancel`; callers cannot supply a
URL, key, package, signer, ring, APK, or installer flag.

`check` queues the same `PackageUpdatePipeline` used by the visible Activity.
A non-exported foreground data-sync service owns the potentially long
download. `status` returns a Base64url-encoded JSON operation snapshot, and
`cancel` cancels a run-owned download or exact persisted Package Installer
session. The CLI cannot click, approve, or bypass Android Package Installer,
and the final confirmation remains wearer-attended.

The provider, service, foreground-service permissions, and their Java classes
exist only under `src/e2e`; they are absent from `debug` and `release`. The
host wrapper uses only serial-scoped adb:

```powershell
pwsh -NoProfile -File .\tools\Invoke-PackageUpdaterE2eCli.ps1 `
  -Serial <quest-serial> -Command Check
pwsh -NoProfile -File .\tools\Invoke-PackageUpdaterE2eCli.ps1 `
  -Serial <quest-serial> -Command Status
pwsh -NoProfile -File .\tools\Invoke-PackageUpdaterE2eCli.ps1 `
  -Serial <quest-serial> -Command Cancel
```

## Build

This is a dependency-light Android Gradle project using the repository's
compile/target SDK 34 and Java 17 baseline plus the pinned Rust Ed25519
verifier:

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
`tools/Publish-PackageUpdateManifest.ps1` stages one exact Kiosk APK, envelope,
and release receipt under the bounded output root, signs before touching the
channel, refuses to replace a same-name APK with different bytes, and publishes
the envelope last. The signing seed is supplied only through
`RUSTY_QUEST_UPDATE_SIGNING_SEED_BASE64URL`. Neither wrapper copies signing
material into an output directory.
