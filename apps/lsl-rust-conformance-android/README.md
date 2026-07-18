# Rusty LSL Rust-on-Quest Conformance Harness

This distinct public 2D test package loads an `aarch64-linux-android` library
built from an exact clean Rusty LSL revision. Android Java owns only lifecycle,
display, and app-private result persistence. The native entrypoint executes the
Rusty LSL core descriptor, Float32 sample, and descriptor/sample binding
contracts and emits the `RLSL005H_RUST EFFECTIVE` marker itself.

The proof is one bounded local contract execution on Quest. It is not Java LSL
behavior, a host-Rust run, wire transport, official compatibility, ambient
activation, device support breadth, or Manifold authority. APKs, keys, device
identities, and raw logs stay outside Git. The runner captures prior package
state and removes only this run-owned package on cleanup.
