# Duplex control lab: standalone codec consumer

This independent desktop project calls the pure
[`rusty-quest-peer-wire`](../../crates/rusty-quest-peer-wire/README.md) library.
It contains no copied codec algorithm and requires no APK, Android runtime,
headset, camera, private runtime fixture or permanent PC service. Public
application/facade and connected-runtime adoption are separate integrations.

From the repository root:

```text
cargo test --locked --manifest-path examples/duplex-control-lab/Cargo.toml
cargo run --locked --manifest-path examples/duplex-control-lab/Cargo.toml -- codec --input <fixture.json>
```

One input file contains a full or supported projected renewal JSON value.
Success prints one `rusty.quest.peer_wire.codec_observation.v1` object with
the exact restored receipt, canonical projection, complete-receipt SHA256,
input bytes and canonical receipt/projection UTF-8 bytes and UTF-16 units.
Refusals use the same schema with `status=refused` and a payload-free code
and message, returning exit2. Output I/O failure returns1. There is no request
dispatch, authority, signing, journal or lifecycle command.

Input reads are bounded to 4 MiB plus one sentinel byte. This is a CLI admission
bound, not signed-frame or status eligibility. Producing the required digest
also enforces the original exclusive 1 MiB complete-receipt bound, including
legacy inputs whose core project/expand paths pass through unchanged. Actual
transport wrappers and current-owner validation belong to their consumers.

The three CLI integration tests run the actual desktop process. They exercise
full/projected Unicode values, exact digest and UTF-8/UTF-16 size observations,
and malformed, unknown-schema, oversize and usage refusals. The library's
separate seven pure contract groups exercise the codec itself. No app-facade,
two-JVM lifecycle or Android integration test is included in this package.

Separate retained private integration evidence for original source
`34a6dbcee8dcdbeb471ce77821811ae5711b3184` covers its actual app facade and
connected runtime. It does not validate public application integration in this
standalone composition, and is not needed to run the commands above.

The manifest has an independent workspace and lock, excluded from the root
APK workspace. Its path dependency uses the same library source exercised by
the pure contract tests. Keep default serde_json sorted object maps and the
documented no-preserve_order condition. Use task-owned Cargo/target/temp lanes
for concurrent work. Rebuild/restart/replay, broader hotloading, Android
storage/JNI and camera/media adapters remain separate work. `publish=false`
does not select a crates.io release.
