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

Any exported debug-only provider must preserve that package isolation. Its
authority is derived from the selected application id, for example
`${applicationId}.debug-host-receipt`; a fixed provider authority shared by
distinct candidate packages is rejected before build handoff because Android
cannot co-install both packages.

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

The Camera builder also supports DevFast and Candidate external artifacts through an
explicit `-ArtifactOutputParent` and absolute `-OutDir` strict child. The parent
must already exist with ordinary ancestors and must not overlap any source
composition repository, build cache or explicitly shared Gradle tool root.
OutDir is create-new and replacement is forbidden. This keeps fresh attempt
artifacts outside a reusable source tree while retaining the full build lock,
source/signer inspection and run capsule. The emitted lock/manifest record the
external-output policy without the parent machine path. See the
[Camera workflow](SPATIAL_CAMERA_PANEL_FAST_BUILD_WORKFLOW.md) for tool reuse.

## Run Capsule

An Android source owner outside the Cargo closure joins that same composition
through `Add-QuestBuildOwnedRepository -Composition <document> -Root <git-root>
-RepositoryId <id>`. It samples the exact commit/tree and, only with
`-AllowWorkingTreeChanges`, the existing tracked/untracked overlay hash. The
added role is `app-owned-input`; the document remains
`rusty.quest.apk_source_composition.v1` (clean) or `.v2` (overlay). If the root
already belongs to the Cargo composition, retain its existing record and role;
an explicit duplicate or overlapping root is rejected. No caller-supplied
commit or overlay can replace the observed source.

Generic consumers can load a provider's exact raw document and call
`Assert-QuestBuildSourceCompositionCurrent -Composition <document>` before and
after a child build. The same optional working-tree switch is required for
dirty development inputs. The assertion verifies document identity and
reobserves every explicit Git root, rejecting drift even if a caller recomputes
a self-consistent forged fingerprint. It does not discover additional Cargo
dependencies. Keep the full app source proof separate from each component's
cache inputs: an Android-only edit must not invalidate native compilation,
while a native input change must. The Cargo/native dependency assertion keeps
its existing closure and behavior.
Spatial depth-layer CMake staging uses the selected serialized product/type
cache lane, in a persistent `x` sibling of Gradle's temporary `a` and `r`
directories. It no longer inherits `.cxx` beneath the source checkout.
Before output writes or compilation the builder checks the known depth-layer
object path against CMake's Windows 250-character limit. Select a shorter
absolute `-BuildCacheRoot` if that check fails; do not toggle global long-path
policy or delete an existing output. The builder passes the staging selection
explicitly to Gradle, and records its geometry and selected-path SHA-256 in
path-free build-cache evidence.
Direct Gradle builds with the depth layer require an explicit absolute
`rqCmakeStagingDirectory`; missing staging fails instead of falling back to
the source tree. Host controls: `tools/checks/Test-SpatialCmakeStagingHost.ps1`.

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

`Apply-RuntimeProfile.ps1 -Execute` sends the ordered property-write plan as
bounded `setprop` batches, then performs one complete `getprop` readback. The
plan retains each operation in order and records matched, superseded, missing,
or mismatched final-property evidence. Every ADB call remains explicitly bound
to the requested serial and optional server port; a batch or readback failure
is terminal.

If a host process dies after `Enter-QuestRunIsolation` has published its active
receipt, use only `tools\Recover-QuestRunIsolation.ps1`. It requires that exact
entered receipt, an explicit existing ADB executable, and the expected
serial/package. It reuses the serial mutex, force-stops only the receipt-bound
package, restores only the receipt snapshot, verifies every property, and
atomically writes a distinct `cleaned` or `partial` terminal receipt. Damaged,
mismatched, terminal, or concurrently owned receipts are rejected; this is not
a generic shell, property, or package cleanup command.

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

pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\checks\Test-QuestRecordingHostReliability.ps1 `
  -RepoRoot .
```

The gate validates source guardrails plus valid and damaged capsule fixtures.
It does not contact a headset.

## Premerge iteration

A clean unmerged local commit is sufficient for Candidate source identity.
Publication is a later action; neither merge nor push is required to compile,
inspect, or test a selected candidate. DevFast may use an exact working-tree
overlay. Preserve its complete source fingerprint, build lock, signer, package
isolation and hashed run capsule, plus every selected live device/authority
guard. A candidate with an existing package ID is not a co-installable package
and must not replace a retained APK without explicit runtime authorization.
See [the fast build workflow](SPATIAL_CAMERA_PANEL_FAST_BUILD_WORKFLOW.md).
