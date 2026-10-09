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

## Optional experiment Android carrier

The native renderer exposes the RQEC1 carrier only when an app explicitly
selects `ui.experiment_session_ble_control`, together with its panel and
same-APK kiosk dependencies. Its descriptor adds the required Bluetooth
permissions; unselected apps retain their existing permission closure.
The packaged experiment-session profile may configure `open_control_default`.
The default is false, and a stored operator choice takes precedence.

The carrier fences callbacks by the current server and connection generation.
It uses a bounded chooser-name policy and replaces a failed status snapshot
with `UNAVAILABLE` instead of returning retained status bytes. A successful
snapshot alone does not establish the age of the native session observation.
An optional status member `o` describes the app-owned native status-read
observation, without changing the v1 protocol, UUIDs or command receipts.
Its closed unknown form is `{"v":1,"s":"unknown"}`. The observed form adds
`e` (runtime epoch) and `i` (read completion identifier) as canonical positive
int64 decimal strings, `g`/`r` joined to the outer safe-integer state, and `a`
as elapsed whole milliseconds rounded down. State and observation are captured
under one coordinator lock. Polling does not allocate a new read identifier.
The existing 480-byte full-status limit still applies; an oversized projection
returns the existing `UNAVAILABLE` status rather than truncating its fields.
This measures a local native readback, not physical media/display observation,
transport delay, command completion or authority.

An age consumer may add monotonic time elapsed since delivery to this lower
bound, with delivery delay explicitly unknown. Within one connection it must
retain epoch/read high-water marks across unknown or malformed status; an old
epoch or replayed read cannot restore a cleared observation. A new connection
starts a new fenced observation scope. Legacy v1 consumers may ignore `o`.
The panel draw receipt identifies app-owned content and its view generation;
it does not establish immersive focus, Bluetooth readiness or session effects.

Focused host gates are `tools/checks/test_experiment_ble_generation.py`,
`tools/checks/Test-NativeRendererExperimentBleName.ps1`,
`tools/checks/Test-ExperimentSessionPanelRenderReceipt.ps1` and
`tools/checks/Test-NativeRendererExperimentPanel.ps1`. These exercise the
production Java policies and generated Android panel compilation. Physical
pairing, browser reconnection and app effects still need device evidence.
`tools/checks/test_experiment_native_readback_age.py` additionally exercises
the complete production snapshot method and BLE server with modeled Android
surfaces, validates its JSON bytes and checks the actual full-status limit.
