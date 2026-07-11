# Rusty Quest Direct P2P Provider

This product app owns a minimal, no-media Wi-Fi Direct lifecycle for Quest.
Android Wi-Fi P2P owns group topology, `AndroidNetworkBindingProvider` reports
the matching `p2p0` `Network`, and the Rust native provider independently owns
local socket bind, bounded TCP exchange, and close.

The package has no dependency on connectivity-lab applications. Launch roles
through explicit intent extras: `role`, `run_id`, `port`, and for the client
`target_device_address`.
