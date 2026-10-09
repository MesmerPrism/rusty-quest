# Spatial Camera Panel fast Android build workflow

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

Testing precedes publication. Candidate accepts an exact clean local commit
that has never been pushed or merged; no remote, pull request, or merge is a
build prerequisite. Commit only when a reproducible clean Candidate pin is
useful. DevFast also accepts reviewed working-tree changes, binding the complete
tracked diff and nonignored untracked file hashes in its source composition.
Re-observe those inputs before and after the build. Source publication and
required PR checks follow testing; a build or inspection pass does not prove
device behavior or authorize installation.

Keep the task's stable compiler cache root across candidate revisions and choose
a fresh final output namespace separately. A new checkout still needs its
repository-local pinned Gradle cache. Prepare it through
`pwsh -NoProfile -File tools/Resolve-GradleTool.ps1 -RepoRoot . -Mode Resolve`,
then use `-Mode VerifyCache` for read-only verification. The resolver retains
archive/tree hashes and executable checks; do not replace them with an ambient
Gradle installation or repeatedly download an unchanged verified tool.

The focused regression `tools/checks/Test-SourceCompositionPremerge.ps1` creates
real local Git repositories with no remotes and verifies unpublished source,
working-tree overlays, dependency drift, and publication rejection.

The build does not provision signing secrets. Supply the keystore with the
parameter or local environment binding and supply alias/store/key passwords in
the local `RUSTY_QUEST_SPATIAL_SIGNING_*` environment variables. Receipts
record only the public certificate fingerprint. A shared client package
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
