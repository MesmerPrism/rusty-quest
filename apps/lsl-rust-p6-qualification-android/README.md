# Rusty LSL P6 Single-Quest Qualification

This development-only package runs one bounded Rust-owned qualification on one
Quest. It uses only IPv4 loopback and covers app-owned candidate observation and
selection, Rusty LSL outlet/inlet sample and two-record chunk exchanges, exact
Float32/timestamp bits, a monotonic elapsed-time bound, one rejected closed-port
candidate followed by the selected route, and immediate terminal port reuse.
Java owns Android lifecycle and result-file projection only.

The build requires a caller-selected exact clean Rusty LSL Git checkout and
binds its commit and tree beside the exact clean Rusty Quest commit and tree.
All generated sources, Cargo state, keys, APKs, and manifests remain under
`target`. The device wrapper requires one explicit serial and retains the
run-owned development package after force-stop because uninstall and app-data
clearing are forbidden for this milestone.

The evidence is not official liblsl oracle/runtime compatibility, generalized
LSL discovery, a clock-correction algorithm, host-to-Quest, non-loopback,
second-device, attended-input, production activation, or global device-health
evidence.
