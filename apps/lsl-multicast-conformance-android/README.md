# Rusty LSL Multicast Conformance Android Harness

This native Java 2D test app packages one bounded Rusty LSL compatibility
probe for Quest. It accepts only explicit role, interface, and deadline intent
inputs. Both requester and responder join, drop, and rejoin exactly
239.255.172.215:16571; the requester then sends one fixed short-info query and
accepts one fixed unicast response from an independently running peer. The
cancel role proves owned-socket cancellation and cleanup without network
exchange.

The app emits one RLSL004G EFFECTIVE JSON marker and writes the same marker to
app-private storage. It owns Android packaging, lifecycle, the multicast lock,
and device evidence only. Rusty LSL owns compatibility behavior. Manifold
stream admission, routing, identity, and authority are not involved.

Generated APKs, keys, run capsules, serials, endpoints, and raw device logs
remain outside Git. The host runner must use serial-scoped ADB, distinct build
and staging identities, bounded target-package fatal windows, target-only
stop/uninstall, and cleanup readback.
