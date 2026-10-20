# Gradle Tool Resolution

The Spatial Camera Panel build uses the repository-owned Gradle 9.4.1 identity
at `config/gradle-9.4.1-tool.json`. It pins the official distribution URL,
SHA-256, archive-derived tree digest, archive name, expected top-level
directory, required executable and launcher paths, redirect allow-list, and
download/extraction bounds.

With explicit/default `-WaitPolicy Bounded`, one cancellation deadline (at most
55 seconds) covers the HTTP request,
redirects, and asynchronous response-body reads. A stalled body fails without
promoting its partial archive; this is not a total cache-validation/build budget.

Prepare this cache explicitly when a selected Spatial Camera Panel build or
JVM test needs Gradle:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Resolve-GradleTool.ps1
```

This is the only network-enabled acquisition command. Build and test wrappers
instead call `Resolve-GradleTool.ps1 -Mode VerifyCache`: they neither download
nor repair a cache, and fail before Gradle execution when the exact pinned
archive, verified tree manifest, or installation is absent or corrupt.

By default the resolver writes only ignored repository-local paths:

- `local-artifacts/downloads/gradle-9.4.1-bin.zip` and its non-secret download record;
- `local-artifacts/tools/gradle-9.4.1`.

For repeated staged checkouts, explicitly select an existing, dedicated ordinary
local-drive directory outside the source repositories with `-SharedGradleToolRoot
<absolute-tool-root>`. Run `-Mode Resolve` once with that same option; the
Camera builder accepts `-SharedGradleToolRoot` and passes it to `-Mode
VerifyCache`. No environment variable selects this tool root. Staged repositories
retain their pinned config and resolver; they need no archive or installed-tree
copy. The shared root contains only `tool-identity.json`, `downloads/` (the
archive and exact tree/download records), and `tools/gradle-9.4.1/`.

The immutable identity marker binds archive/tree pins and the exact resolver and
config file SHA-256 hashes. Changing either source file requires a new empty
shared root or explicit operator-managed retirement of the old one. The resolver
rejects unknown payload, file/directory collisions, root/repository overlap,
linked ancestors, reparse points and available hardlink evidence. Verification
still hashes the whole archive and installed tree on every use; it neither
creates the marker nor repairs state. Shared acquisition and verification use
the existing mutex, keyed by the shared root's stable Windows file identity.
The shared tool distribution stays separate from Gradle user homes, dependency
caches, task outputs, and the builder's `BuildCacheRoot`.

Shared builds add a `gradle_tool_distribution` identity to their build-input
lock and manifest. This records resolver/config/archive/tree/marker hashes and
the explicit storage kind without machine paths; existing local-mode receipt
shapes and previously emitted artifacts remain valid.

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

Its mutex identity uses stable Windows volume/FileId evidence for the selected
repository or explicit shared cache parent plus the fixed Gradle tool identity. This makes ordinary path
spelling and case aliases share one lock. Where Windows cannot provide that
evidence, it falls back to an upper-cased canonical non-reparse repository path
on the local host; that fallback is documented rather than treated as a hostile
local-process defense.

Run deterministic offline coverage with:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Resolve-GradleTool.ps1 -SelfTest
```

The self-test covers a good cache, corrupt archive, wrong hash, partial install,
missing verified-tree marker, concurrent/idempotent resolution and case-alias locking, provider failure,
temporary-file cleanup, reparse/target escape, and executable Windows ZIP-path
fixtures (backslash/dot traversal, ADS, case collisions, reserved names, and
symlink entries). It creates only a temporary fixture tree and removes it.
Shared fixtures additionally cover empty-root verification without writes,
concurrent provisioning, changed resolver/config/marker/archive/tree bytes,
unknown payload, file/directory collisions and linked root/ancestor/install files.

Existing `-GradleVersion 9.4.1` callers remain compatible. Other versions fail
parameter validation before acquisition or build work; changing the pinned tool
identity is a separate source change. Focused resolver checks do not run an APK
build or execute the returned Gradle launcher.


## Retained ongoing build waits

`-WaitPolicy Retained` is an explicit choice on the resolver and Spatial Camera
Panel builder. The selected source-bound private builder passes that choice;
other/default callers retain `Bounded`. A resolver/build mutex estimate then
publishes a warning and waits on the same mutex. Abandonment means the caller
actually acquired that mutex; all pinned cache/path/tree checks still run.
Warning publication failure cannot release or replace the pending owner.

The same choice covers `Resolve` acquisition: the HttpClient deadline and
response-body cancellation estimate are disabled for that original request,
and a warning observes slow header/stream/read/write tasks without cancelling,
retrying, or substituting them. Actual HTTP errors, HTTPS redirect/host limits,
byte/layout bounds and pinned archive/tree identities remain strict. Explicit
Stop/process termination remains a separate caller decision, never an elapsed
inference. `VerifyCache` still performs no download or repair.

Changing resolver bytes changes the shared identity marker. Prepare a NEW,
empty, dedicated ordinary shared root with `Resolve -WaitPolicy Retained`, then
use that exact root for verify/build. Do not rewrite an old marker or relabel an
existing cache. The old root remains historical input. The focused controls in
`tools/checks/Test-GradleToolResolverStatic.ps1 -WaitPolicyOnly` extract the actual
two owner helper definitions, use real held/abandoned mutexes and same Task
completion/failure, and never invoke Gradle, HTTP, or an APK build. They do not
replace the resolver's immutable cache self-tests or actual build qualification.
