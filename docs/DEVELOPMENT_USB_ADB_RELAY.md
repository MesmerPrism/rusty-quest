# Development USB ADB relay

This optional development transport carries the existing reciprocal control and
packed stereo TCP protocols through an operator-owned PC ADB bridge. The app
does not configure ADB, discover devices, change radios, or create a relay. The
ordinary LAN profile and its address rules remain the default.

## Explicit selection

The selected embedded product must be a non-publication Debug build. Its
explicit private resource directory declares one default integer named
`rusty_quest_embedded_duplex_usb_adb_relay_enabled` with value `1`. This selects
the native Cargo feature `development-usb-adb-relay`, forwarding Manifold's
default-off `development-loopback-relay` capability. The selection joins the
build lock, native and shell cache identities, APK inspection, manifest and run
capsule. Missing capability or zero retains ordinary build behavior; enabled
Release, publication and non-embedded selections are refused.

The capability grants no route or lifecycle authority. A control profile must
also explicitly select `peer_network_transport: "development-usb-adb-relay-v1"`.
Each enrolled peer entry then declares the existing media/control endpoints and
one integer `local_source_port`. All external hosts must be literal
`127.0.0.1`; all four external and both internal ports must be positive and
distinct. Other loopback addresses, aliases, mixed LAN/USB selections and
collisions are refused.

For example, two peers may use external media/control ports `46061/46071` and
`46062/46072`, with internal camera producers at `47061` and `47062`. Distinct
internal producers are essential: an incoming loopback receiver cannot share
the local producer's address and port. Ordinary LAN retains its existing local
producer-port equality.

## Frozen inputs and signatures

Selection passes through the existing strict profile ingress and immutable
effective-input owner. The native derivation binds the new transport contract
and producer ports into the effective route, media bindings and lifecycle
digests. The signed transport ID is
`rusty.manifold.peer.development_loopback_relay_tcp_transport.v1`.
The existing signature format binds that ID, network scope, exact route hash,
peer identities and endpoints. The ordinary transport ID and signing bytes
are unchanged. No socket address is substituted after signing.

Enrollment, receiver-first startup, resource preparation, replay fences,
signed renewals, Stop and Whole retain their existing owners. Profile selection
readback describes the requested next generation; only current bootstrap,
signed route/lifecycle evidence and actual effects can establish adoption.
Neither an installed resource nor successful ADB forwarding proves media or
stereo presentation.

## Validation and history

`tools/checks/Test-SpatialUsbAdbRelayBuildHost.ps1` exercises actual build
selection and is registered in the camera host gate. Profile, frozen-input,
native derivation/signing and Manifold tests cover their respective boundaries.
Host tests do not establish USB throughput or live rendering.

Feature-off readers reject developer-relay receipts and retained history.
Consequently, downgrading an existing package after a USB journey is not a
transparent rollback. Preserve exact originals and use the owning supported
lifecycle/recovery route. This feature adds no history clearing or migration.
