# Broker Product Packaging

## Decision

The standalone Quest broker APK is built only from an explicit Manifold
`product_spec.v1` plus its exact accepted `product_lock.v1`. The build has no
ambient app manifest and does not re-resolve or union capabilities in
PowerShell or Java. PowerShell may only compute the deterministic intersection
of one accepted product lock and one exact client lock; Rust revalidates the
same closure at provider initialization.

## Product split

- `base-standalone`: broker control/status plus required standalone service
  lifecycle permissions; no media, camera, P2P, or BLE feature.
- `media-session-standalone`: generic media session/stream references with the
  same camera-free permissions as base.
- camera, direct-P2P, and BLE: separate explicit feature closures.
- `legacy-camera-p2p-standalone`: the broad historical validation package,
  available only through `-LegacyCameraP2pCompatibility`.

Direct-P2P and BLE platform mutation normally belongs in their dedicated
provider packages. The legacy product exists for compatibility evidence, not
as the default broker shape.

## Generated inputs

`prepare_android_broker_product` validates the lock against a fresh Manifold
resolution and writes deterministic package inputs:

- `product-spec.json`;
- `accepted-product-lock.json`;
- `manifest-projection.json`;
- `AndroidManifest.xml`;
- `command-registry.json`;
- `GeneratedBrokerProductConfig.java`;
- `product-package-inputs.json`.

The input receipt carries the lock id, product id, closure fingerprint,
canonical spec/lock SHA-256 values, generated manifest/registry hashes, runtime
mode, and exact feature closure. Stale, expanded, union, duplicate,
or embedded locks fail before Android compilation.

The APK packages the accepted lock, command registry, and manifest projection
under `assets/manifold/`. The final
`rusty.quest.manifold_broker_android.build_manifest.v2` repeats their hashes and
asset paths beside the APK hash and signing/admission evidence.
After the signing certificate is known, the build also generates and packages
`runtime-config.json`, embeds the same exact config in
`GeneratedBrokerRuntimeConfig.java`, and records its SHA-256 plus the
fresh-process/same-process-rebind epoch policy. The config embeds the exact raw
product-spec, accepted-lock, and client-lock JSON plus per-file SHA-256 values.
`runtime_config_digest` computes the canonical typed-config SHA-256 embedded as
`GeneratedBrokerRuntimeConfig.SHA256`; the tool also constructs a throwaway
Rust authority to reject lock/grant/config drift during the build, and JNI
rechecks the digest before creating the live provider.
`BrokerStartService` is
non-exported; only the launcher and signature-protected admission service are
exported.

Use `-ValidateRuntimeConfigOnly` before a full APK build. It performs the exact
product preparation, signing-identity projection, admission/client-lock
closure, and Rust authority digest, then returns before Java, native, or APK
packaging. `-PrepareOnly` and `-ValidateRuntimeConfigOnly` are mutually
exclusive. The final build receipt explicitly projects the selected Manifold
modules and permissions, compiled Android permissions/components, and the
product-lock, generated-manifest, runtime-config, APK-signature, and packaged-
asset validation outcomes.

## Commands

Camera-free preparation:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1 `
  -ProductSpecPath ..\rusty-manifold\fixtures\broker-product\media-session-standalone.json `
  -ProductLockPath ..\rusty-manifold\fixtures\broker-product\media-session-standalone.lock.json `
  -PrepareOnly
```

Fail-fast runtime-config authority preflight:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1 `
  -ProductSpecPath ..\rusty-manifold\fixtures\broker-product\base-standalone.json `
  -ProductLockPath ..\rusty-manifold\fixtures\broker-product\base-standalone.lock.json `
  -OutDir .\target\manifold-broker-runtime-preflight `
  -ValidateRuntimeConfigOnly
```

Focused validation:

```powershell
cargo test -p rusty-quest-broker-product
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-QuestBrokerProductStatic.ps1 -RepoRoot .
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-ManifoldBrokerProductBuildPreparation.ps1 -RepoRoot .
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-ManifoldBrokerAndroid.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-ManifoldBrokerBuildArtifact.ps1 `
  -RepoRoot . -BuildDir .\target\manifold-broker-android -ExpectedProductName base-standalone
```

## Spatial Camera Panel supplier specialization

The legacy camera/P2P compatibility package has one optional supplier binding:
`-SpatialCameraPanelPackageName`. It changes only the packaged Spatial Camera
Panel client lock, its media-lifecycle lock, and the derived signature-scoped
admission subject. The checked-in baseline fixtures stay reusable for the
ordinary public package. Specialization requires all of the following in the
same build:

- `-LegacyCameraP2pCompatibility` with an explicit tracked-clean
  `-ManifoldSourceRoot`;
- exactly
  `fixtures/media-runtime-products/camera2-surface.binding.json` and
  `fixtures/media-runtime-products/spatial-camera-panel-display.binding.json`;
- `-EnableRemoteCameraDebugOperator` and
  `-RequireSharedMorphovisionSigner` with the reviewed keystore;
- an explicit version and content-addressed output directory.

The build manifest records the exact Manifold commit/tree, both binding
digests, specialized client/lifecycle digests, package, signer, version, and
fixed diagnostic safety policy. It never records the local Manifold or
keystore path.

The B compatibility diagnostic is deliberately non-destructive. Before any
device effect, `Test-ManifoldBrokerCompatibilityDiagnosticStatic.ps1` checks
the build manifest, selected feature-lock raw hash/revision/fingerprint,
rollback evidence, both APKs through the pinned File Manager CLI, and the
pinned ADB bytes. Its receipt is then a mandatory input to
`Invoke-ManifoldBrokerCompatibilityDiagnostic.ps1`.
The default receipt is content-addressed beneath
`target/manifold-broker-compatibility-gates/`, separate from the replaceable
build directory; collisions, nesting, and reparse-point traversal fail closed.

Rollback evidence has this closed local-only shape:

```json
{
  "$schema": "rusty.quest.manifold_broker.compatibility_rollback_evidence.v1",
  "candidate_apk_sha256": "<candidate-apk-sha256>",
  "package_name": "io.github.mesmerprism.rustymanifold.broker",
  "version_code": 10103,
  "candidate_version_name": "0.1.0-unit020-lan-20260913",
  "signer_certificate_sha256": "722f1f3dcb921918d2e02f39f1b1bd8f9ff2812e07757c5fc665f6b8f7ee32a8",
  "rollback_apk": {
    "path": "<captured-installed-apk>",
    "sha256": "<sha256>",
    "actual_version_name": "0.1.10103"
  },
  "captured_from_installed_bytes": true,
  "same_version_restore": true,
  "preservation": {
    "uninstall_required": false,
    "data_clear_required": false,
    "global_log_clear_required": false,
    "blanket_force_stop_required": false,
    "downgrade_required": false,
    "adb_lifecycle_required": false
  }
}
```

The device runner requires the exact prior broker bytes to be installed,
performs a same-version/same-signer inspected replacement, verifies installed
candidate bytes, reads only the bounded remote-camera `authority-status`, and
reinstalls and verifies the exact prior bytes in `finally`. It never uninstalls,
clears data or logs, force-stops packages, requests downgrade, changes ADB
lifecycle, or touches the Spatial package. The evidence is local/target-only
and proves neither a final media graph nor a hot Local consumer handoff.

## Runtime gate

NET-014 binds the generated config to one process-local Rust provider. Binder,
JNI, WebSocket, and Java remain transport only; every mutation consumes a
current one-use admission and receives the exact Runtime Host receipt. The
Native Renderer build applies the same rule to the embedded camera product: it
packages the exact product/client inputs, derived grant, canonical config
digest, and generated Java constants before compilation. Runtime settings may
enable the server transport but cannot supply or expand authority config.
Remaining product work is generic media adoption: map accepted and leased
commands to the source-neutral Quest media runtime. Legacy remote-camera source
remains an effect adapter, not a second acceptance path.
