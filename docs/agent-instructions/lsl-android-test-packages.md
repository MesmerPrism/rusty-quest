# Rusty LSL Android test package notes

The P70 `lsl-rust-float32-lan-outlet-android` package is an opt-in,
same-LAN, one-channel Float32 Quest-outlet to host-inlet qualification only.
It does not establish the reverse direction or a default runtime feature.
Use its dedicated build/static/device scripts; the device runner retains the
installed package after target-only force-stop.

The `lsl-rust-float32-two-record-chunk-android` app is a distinct public test
package for LSLC-005S. It builds exact clean Rusty LSL for
`aarch64-linux-android` and executes only the accepted one-channel, two-record
Float32 chunk runtime over IPv4 loopback inside Rust. Java owns lifecycle only;
runs require ordered exact-bit evidence, immediate port reuse, zero bounded
fatals, one explicit serial, distinct identities, and exact run-owned cleanup.
It adds no arbitrary chunk, production activation, or compatibility breadth.

The `lsl-rust-float32-loopback-android` app is a distinct public test package
for LSLC-005L. It builds the exact clean Rusty LSL revision for
`aarch64-linux-android` and executes the accepted one-channel, one-record
Float32 handshake/sample runtime over IPv4 loopback inside Rust. Java owns
lifecycle only. Runs use one explicit serial, a distinct package/build/staging
identity, zero bounded fatals, immediate port-reuse evidence, and exact
run-owned cleanup. This adds no production activation or compatibility breadth.

The `lsl-rust-conformance-android` app is a distinct public test package for
LSLC-005H. Its generated native crate binds an exact clean Rusty LSL source
revision and builds only for `aarch64-linux-android`; Java owns lifecycle while
Rust owns the effective core-contract marker. Runs are serial-scoped and must
remove only the run-owned package with zero bounded fatals and complete
package/process/forward/reverse/property/staging cleanup.
