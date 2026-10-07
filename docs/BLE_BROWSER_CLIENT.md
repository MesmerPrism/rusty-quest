# Shared browser Bluetooth client

`web/ble-control` is the canonical producer for a dependency-free static
browser/CommonJS lifetime package. Its first consumers are the Viscereality
RQEC1 browser and the Connection Hub BLE carrier browser. Both use one
serialized, generation-guarded GATT operation queue. Protocol compatibility
profiles remain separate: experiment command/HMAC/UUID bytes are preserved;
the Hub carries unchanged owner authentication and command frames.

The producer owns the helper source and package version. A website projection
must pin the producer commit, path and SHA-256 and include a byte-integrity
check. A published website is a consumer, not a new protocol authority.

Transport write acknowledgement, command admission and app effect completion
remain separate. The RQEC1 consumer joins only exact current request receipts;
late results from a retired connection cannot change the current UI, and lost
readback after dispatch remains outcome unknown. Host app actions and the
final-confirmation behavior remain app-owned.

Manifold local-control/admission and connection-hub remain the respective
acceptance/replay/lease/revocation authorities. This slice changes neither
their schemas nor Android permission, service, package or foreground policy.
Further Android carrier extraction must preserve one authority per host,
use the existing Quest adapter packaging pattern and keep app commands outside
the shared library. The intended Lite consumer should select an existing
profile/authority composition with its own bounded app command registry.

Validation is the package's Node test plus both consuming website production
adapters' target-free tests, including stale readbacks, serialized calls,
receipt identity, outcome unknown and Hub framing/MTU/deadline cases. Device
qualification, package publication and app effects require separate evidence.
