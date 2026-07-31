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

The configured endpoint returns a strict
`rusty.quest.package_update_channel_pointer.v1` binding the closed tuple and
SHA-256 of an envelope in an immutable `generations/<id>/` directory. The
client rejects pointer drift and fetches only that exact generation. The
generation contains the Rust-owned
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
If Package Installer succeeds but the manifest has reached its exclusive
expiry boundary before checkpoint observation, the receipt truthfully records
`installed_but_checkpoint_rejected_expired`; rollback state is unchanged and
the next update decision requires a fresh signed manifest.

Alpha is an explicitly same-package policy that updates the existing
`io.github.mesmerprism.rustykiosk` package in place under continuous APK
signer identity. Alpha and the installed Kiosk cannot coexist, and there is no
immediate downgrade. Exit to stable requires a separately accepted stable
release with a strictly higher Android version code under the same signer.
Every consumer-headset package change remains individually visible and
attended.

## Test-only adb CLI

The `e2e` build type adds a separate
`io.github.mesmerprism.rustyquest.packageupdater.alpha.e2ecli` test package. It
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
repository. Product release also requires the protected expected updater
certificate SHA-256; a valid APK signed by any other sole certificate is
rejected.

For an exact clean source revision, use
`tools/Build-PackageUpdaterAndroid.ps1`; it runs the static gate, injects the
fixed trust/policy inputs and Android signing material through the child
environment, builds the signed APK, verifies its sole signing certificate, and
writes a public build manifest containing only bounded build-tool names,
versions, and hashes rather than local SDK paths.
`tools/New-PackageUpdaterProductReleaseMetadata.ps1` then revalidates that
closed build manifest against the actual APK and emits the sanitized
`rusty.quest.package_updater_product_release.v1` owner artifact for an exact
`package-updater-v0.1.0-alpha.N` tag. It contains no SDK, aapt2, NDK, Gradle,
or keystore path. The protected tag workflow creates a draft alpha prerelease,
uploads only `rusty-quest-package-updater.apk`,
`rusty-quest-package-updater.release.json`, the project license, and the exact
source notice, verifies all four remote names, SHA-256 digests, and byte counts
before and after promotion, rechecks the authoritative tag immediately before
promotion, and keeps the release non-latest. Tag sequence `N` is emitted as
Android version code `N` and version name `0.1.0-alpha.N`.
`tools/Publish-PackageUpdateManifest.ps1` first inspects the exact Kiosk APK
with caller-pinned Android build tools, then stages an immutable generation
containing the APK, envelope, and receipt. It requires an explicit absent
assertion or exact prior pointer/envelope hashes, rejects tuple drift and
downgrade, and atomically updates `current.json` last. The signing seed is
supplied only through
`RUSTY_QUEST_UPDATE_SIGNING_SEED_BASE64URL`. Neither wrapper copies signing
material into an output directory. Public receipts retain only bounded Android
build-tool names, version, and hashes—never absolute SDK paths.
