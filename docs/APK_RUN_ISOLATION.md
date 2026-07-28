# APK Build And Run Isolation

Use this contract whenever multiple Rusty Quest projects build or launch APKs
during the same session, especially when they share one headset.

Build and run wrappers execute under PowerShell `7.6` LTS or newer through
`pwsh`. An ambient Windows PowerShell 5.1 host is rejected before source,
package, property, or headset mutation begins.

## Build Contract

A locked build requires a clean tracked source composition and binds the exact
Quest commit/tree plus every Matter, Lattice, Optics, or Manifold Git root
reachable through the APK crates' local path dependencies. Any tracked drift
in that closure fails before compilation, and the composition fingerprint is
part of the output address and run capsule. Each app owns a distinct Android package, launch component,
Manifold client identity, feature lock, marker namespace, and lifecycle/grant
identity. Build inputs are explicit; ambient native-renderer or Spatial feature
environment variables are rejected or ignored rather than inherited.

Native app specs resolve under:

```text
local-artifacts/native-app-builds/<app-id>/<resolution-fingerprint>/
```

Build from that directory's `feature-lock.json`. The default output is content
addressed by app, lock, and source revision:

```text
target/native-renderer-android/builds/<app-id>/<lock-sha-prefix>/<source-commit-prefix>/
```

Spatial builds require an explicit `-AppId` unless the caller deliberately
uses `-AllowSharedDevelopmentPackage`. Their explicit inputs form a build-input
lock and fingerprint under:

```text
target/spatial-camera-panel-android/builds/<package>/<fingerprint-prefix>/
```

The shortened directory addresses are collision-resistant prefixes; the full
lock, commit, tree, and fingerprint remain in the manifest/capsule. Both
builders isolate Cargo/Gradle intermediates in short, input-addressed cache
roots under `target/apk-i/` (`n` for native and `s` for Spatial) and refuse
to replace an existing content address unless `-ReplaceExistingOutput` is
passed. Normal iteration should reuse the existing output or create a new
address; replacement is a repair/debug action.

## Run Capsule

Every locked build writes `run-capsule.json`. The capsule hashes the APK, build
manifest, selected lock or build-input lock, runtime profile, and complete
property manifest, and binds the package/activity plus exact source
commit/tree. Validate it before device mutation:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Test-ApkRunCapsule.ps1 `
  -Path <content-addressed-output>\run-capsule.json
```

Launch native and Spatial apps from the capsule:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Invoke-NativeRendererReplaySmoke.ps1 `
  -RunCapsule <content-addressed-output>\run-capsule.json `
  -Serial <quest-serial>

pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1 `
  -RunCapsule <content-addressed-output>\run-capsule.json `
  -Serial <quest-serial>
```

`-AllowLegacyLooseInputs` exists only for named historical compatibility
wrappers. New project workflows must not combine a loose APK path, ambient
profile, and default package identity.

The Spatial builder also inventories every app/private-source
`debug.rustyquest.spatial.*` and `debug.rustyquest.spatial_camera_panel.*`
consumer into `spatial-property-manifest.json`; its capsule hashes that complete
surface. This prevents an old projection-panel, hand-particle, room, video, or
asset setting from surviving into the next project run.

## Same-Headset Transaction

The launch wrappers take a named mutex derived from the exact serial and wait
up to 120 seconds by default (`-RunIsolationMutexTimeoutSeconds` can set a
bounded alternative). One headset therefore runs one mutation transaction at
a time while unrelated projects may continue building in parallel.

Before launch the wrapper snapshots the app's complete declared property set,
clears that set, and applies only the capsule profile. Cleanup runs in
`finally`: it force-stops only the capsule package, restores every property to
its exact prior value, verifies the restore, and writes a cleanup receipt.

Do not use blanket force-stop of neighboring XR packages as ordinary
preflight. The Spatial wrapper exposes `-ForceStopKnownXrPackages` only for an
explicit diagnostic that actually requires that disruption.

The transaction mutex protects callers using these wrappers. Cross-tool or
cross-agent scheduling should additionally claim the headset serial, Android
package, property namespace, and output through the project work-environment
resource-claim protocol.

For Windows path compatibility, a launcher verifies and stages the capsule APK
at `target/apk-r/<full-apk-sha256>.apk` before calling `adb install`. The short
copy is content addressed and never substitutes a loose or unhashed APK.

## Static Gate

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\checks\Test-ApkRunIsolationStatic.ps1 `
  -RepoRoot .
```

The gate validates source guardrails plus valid and damaged capsule fixtures.
It does not contact a headset.
