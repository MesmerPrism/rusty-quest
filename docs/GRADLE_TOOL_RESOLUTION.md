# Gradle Tool Resolution

The Spatial Camera Panel build uses the repository-owned Gradle 9.4.1 identity
at `config/gradle-9.4.1-tool.json`. It pins the official distribution URL,
SHA-256, archive-derived tree digest, archive name, expected top-level
directory, required executable and launcher paths, redirect allow-list, and
download/extraction bounds.

Prepare only this local prerequisite before a build-capable aggregate gate:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Resolve-GradleTool.ps1
```

This is the only network-enabled acquisition command. Build and test wrappers
instead call `Resolve-GradleTool.ps1 -Mode VerifyCache`: they neither download
nor repair a cache, and fail before Gradle execution when the exact pinned
archive, verified tree manifest, or installation is absent or corrupt.

The resolver writes only ignored repository-local paths:

- `local-artifacts/downloads/gradle-9.4.1-bin.zip` and its non-secret download record;
- `local-artifacts/tools/gradle-9.4.1`.

It does not alter `PATH`, a system/user Gradle installation, SDKs, profiles,
devices, APK outputs, or Gradle user homes. It verifies the pinned archive hash
on every cache use, derives a complete regular-file SHA-256/size/path and
directory manifest from that archive, compares its digest to the repository
pin, and accepts an install only when the tree matches exactly (including no
`init.d` or other extra files). It rejects
unsafe Windows ZIP paths, reparse points, and hardlinks where link-count
evidence is available. Network resolve uses bounded TLS-only redirects and
temporary siblings; verified files and receipts are promoted atomically without
in-place record overwrites.

The recorded 9.4.1 pin was observed at the official Gradle endpoint on
2026-08-25. Immutable local receipts contain only scheme/host/path redirect
identities, response metadata, byte count, archive hash, and tree digest; they
never record response bodies, redirect queries, credentials, or signed URLs.

The resolver assumes a trusted-local-process boundary while its mutex is held. It
revalidates paths and available Windows file identities around deletion and
promotion, preventing path escape for validated inputs and non-adversarial
races; it does not claim to defend against an actively malicious local process
that races handle/path replacement after those observations.

Its mutex identity uses stable Windows volume/FileId evidence for the repository
cache parent plus the fixed Gradle tool identity. This makes ordinary path
spelling and case aliases share one lock. Where Windows cannot provide that
evidence, it falls back to an upper-cased canonical non-reparse repository path
on the local host; that fallback is documented rather than treated as a hostile
local-process defense.

Run deterministic offline coverage with:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Resolve-GradleTool.ps1 -SelfTest
```

The self-test covers a good cache, corrupt archive, wrong hash, partial install,
concurrent/idempotent resolution and case-alias locking, provider failure,
temporary-file cleanup, reparse/target escape, and executable Windows ZIP-path
fixtures (backslash/dot traversal, ADS, case collisions, reserved names, and
symlink entries). It creates only a temporary fixture tree and removes it.
