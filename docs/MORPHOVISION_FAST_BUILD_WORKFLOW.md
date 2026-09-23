# Morphovision fast Android build workflow

`tools/Build-SpatialCameraPanelAndroid.ps1` has two independent identity
contracts:

- the final APK and its evidence remain content-addressed by the complete
  resolved build-input lock;
- compiler intermediates live in one stable, deliberately short local cache
  root so normal incremental compilation survives product-input changes.

The default cache root is `<workspace-drive>:\b\mv`. Override it with
`-BuildCacheRoot` or `RUSTY_QUEST_BUILD_CACHE_ROOT`. The wrapper rejects roots
longer than 64 characters and checks a representative AAPT2 path against a
220-character budget before compilation.

The local cache has separate persistent lanes for Cargo Android/host targets,
the Gradle user home, Gradle project cache, and product/host build directories.
It is local build state, not source or release evidence, and must not be
committed.

## Cache identities

Each run writes `build-cache-identities.json` beside the final APK. It records
hashes, prior verified output availability, observed Cargo/Gradle outcomes,
and field-level invalidation reasons without recording cache paths:

- `native`: Rust graph, private shader/profile hashes, NDK, Rust flags, and
  native compile-time settings;
- `android_shell`: Kotlin/Java/resources/manifest/Gradle inputs and Android
  build settings;
- `package`: native + shell identities, application ID, build type, packaged
  inputs, and the selected public signer certificate fingerprint.

A Rust/private-shader-only edit therefore retains Android/Kotlin/resource/dex
outputs. A Kotlin/resource-only edit retains the native library. Gradle and
Cargo still verify their own file-level inputs; the identity receipt explains
why a lane was expected to invalidate.

## Modes

`-BuildMode DevFast` uses the Gradle daemon, configuration cache, build cache,
and stable local intermediates. It still performs signer preflight and inspects
every APK.

`-BuildMode Candidate` requires a frozen clean source composition, uses an
explicit signer and expected certificate fingerprint, disables the Gradle
daemon/configuration cache for a detailed task-timing pass, and retains the
same complete content-addressed output/evidence contract.

The build does not provision signing secrets. Supply the keystore with the
parameter or local environment binding and supply alias/store/key passwords in
the local `RUSTY_QUEST_SPATIAL_SIGNING_*` environment variables. Receipts
record only the public certificate fingerprint. A shared Morphovision package
cannot compile with the ambient default debug signer, and a mismatched explicit
signer is rejected before Cargo or Gradle runs.

## Preflight and inspection

Before compilation the wrapper resolves the machine Android profile (SDK,
build-tools 36.0.0, NDK 27.2.12479018, Temurin JDK 17), verifies the exact
build-tools `source.properties` revision and AAPT2 hash, copies AAPT2 byte-conditionally to the short tool
lane, and executable-smoke-tests AAPT2, Clang, Java, zipalign, and apksigner.
Rust target installation is idempotent.
One named machine-local mutex serializes writers to each stable cache root.
Receipts retain the bounded wait and abandoned-owner readback, but not the
mutex name or cache path.

Every APK then passes:

- AAPT2 package/activity/min/target SDK readback;
- one-signer apksigner verification and expected fingerprint comparison;
- 4-byte and 16-KiB-aware zip alignment;
- ELF LOAD alignment of at least 16 KiB for the Rust library;
- exact native payload inventory;
- rejection of key/local-property material and plaintext video payloads.

Static Rust standard-library linkage is the shipping path for both DevFast and
Candidate APKs. The optional `-RustStdLinkage Dynamic
-AllowNonDeployableDynamicStdBenchmark` experiment uses a separate cache lane and fails
inspection when its required dynamic Rust standard-library payload is absent.

`build-phase-receipts.json` records preflight, native compile/link, Android
shell/resources/dex/APK, and inspection durations. Candidate mode additionally
records classified Gradle task counts, aggregate task durations, and cache
outcomes.
