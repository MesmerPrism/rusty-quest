# Rusty LSL Float32 Two-Record Chunk Android Harness

This public test-only app builds the accepted Rusty LSL crate for
`aarch64-linux-android` and runs one finite, one-channel, two-record Float32
chunk outlet/inlet exchange over IPv4 loopback inside Rust. Java owns Android
lifecycle and result-file projection only. The package declares only Android's
normal `INTERNET` permission, which the platform requires even for loopback.

The Rust marker reports exact activation, timestamp/value-bit retention, and
immediate TCP port reuse. This is one Quest execution of the existing bounded
runtime. It is not official-liblsl, host-Rust, Android-Java LSL, non-loopback,
arbitrary-chunk, broad compatibility, production activation, or Manifold evidence.

Use `tools/Build-LslRustFloat32TwoRecordChunkAndroid.ps1` with an exact clean Rusty
LSL root, then `tools/Invoke-LslRustFloat32TwoRecordChunkQuest.ps1` with one explicit
serial. Generated APKs, keys, serials, and raw device evidence stay outside Git.
