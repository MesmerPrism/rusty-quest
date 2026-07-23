# Rusty LSL LAN outlet qualification

This default-inert public test package runs one exact-source-locked Rusty LSL
outlet and short-info responder on one Quest. A separate Rust host explicitly
discovers the Quest over an existing IPv4 LAN, selects the one response,
connects, receives one one-channel Float32 record, and closes.

The package owns no Android properties or staging files. It does not select or
change a network, firewall, ADB transport, power policy, or default activation.
Raw device identity, addresses, endpoints, APKs, and logs remain private.
