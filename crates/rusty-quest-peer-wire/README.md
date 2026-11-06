# Quest peer renewal wire codec

A pure source-level library for lossless Quest peer renewal projection,
restoration and complete-receipt digest references. The
[standalone desktop lab](../../examples/duplex-control-lab/README.md) is the
consumer delivered with this public package. Application and connected-runtime
integration remain separate owner work.

`project_renewal`, `expand_renewal`, `checkpoint_reference` and `is_projected`
preserve the original wire representation. `receipt_sha256` exposes its bounded
canonical complete-receipt digest. `WireError` provides a payload-free code
and preserves the original String error messages through Display.

Only `continuation_checkpoint.original` and `.baseline` copies exactly equal
to the same receipt's `media_authority` become closed references. Expansion
checks the complete-receipt digest and exact reprojection. Legacy,
uncheckpointed and unequal-slot pass-through behavior is retained, including
paths that do not invoke the exclusive 1 MiB digest bound. Callers retain
their own signed-frame, sign-domain, status and authority checks. No operation
authenticates a sender or grants authority, signs, persists, transports or
executes an effect.

Digest compatibility depends on serde_json Value serialization with its
default sorted object maps. Keep `serde_json/preserve_order` disabled and
verify the exact vectors if a consumer changes serializer features. This is
a source API, without a stable binary ABI or hotloader.

## Focused checks

From the repository root:

```text
cargo test --locked --manifest-path crates/rusty-quest-peer-wire/Cargo.toml
cargo clippy --locked --manifest-path crates/rusty-quest-peer-wire/Cargo.toml --all-targets -- -D warnings
```

The seven pure contract test groups compare the library against a clearly
marked, test-only frozen original algorithm and an independent known digest.
They cover exact errors and validation order, mixed/full and legacy paths,
equal/unequal checkpoint slots, malformed/version/reference/digest mutations,
exact reprojection, Unicode and the exclusive size threshold. The oracle
does not provide a second production implementation. These tests require no
Android source, app facade, generated runtime, APK or private fixture archive.

The original app-facade and connected three-lifetime/20-renewal evidence is
retained separately for source `34a6dbcee8dcdbeb471ce77821811ae5711b3184`.
That private integration record is provenance for the extraction; it is not
included here and does not qualify the public application at another source
identity. Its app-facade integration test remains in that original composition,
outside this standalone package's test inventory. Platform/JNI/OS/camera and
resource-release behavior are not established by the pure checks above.

This independent workspace is excluded from the root application workspace,
so its focused commands do not resolve app/render/shader dependencies. Its
lockfile records its own dependency resolution. Use task-owned Cargo, target
and temporary directories when working alongside other owners. Rust1.82
covers the inherited is_none_or operation; execution with a newer compiler
does not by itself establish every dependency's MSRV. `publish=false` keeps
registry publication separate from this Git source delivery.
