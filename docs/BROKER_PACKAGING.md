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
