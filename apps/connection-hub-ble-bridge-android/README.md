# Foreground Connection Hub BLE carrier

This separate opt-in helper carries existing Hub v2 messages over Web Bluetooth
GATT to the already wearer-started Hub's fixed loopback WebSocket listener.
The launcher is inert. Enable from its visible Activity; an explicit package-private
foreground connected-device service then holds the carrier while the streaming
Activity resumes. Stop from that Activity or its notification. Service destruction
or expiry closes it; repeated start does not renew its scope. One controller and a
fifteen-minute foreground bound apply. There is no boot receiver or automatic restart.
It performs no Wi-Fi topology, media, pairing, lifecycle or private policy action.

The helper must have the Hub's actual signing certificate and existing
signature permission to read the exact Hub status provider. That status call
proves no controller/provider grant. A genuine existing controller session,
registered provider package/signer and native command grant remain mandatory.
It never starts the Hub or creates those grants. Only empty Own/Peer commands
and existing auth/keepalive frames pass the negative carrier filter.

The outer carrier is Bluetooth GATT; native receipts still describe the existing
internal experimental WebSocket hop. Neither link confidentiality nor production
eligibility is established. BLE fragment acknowledgement means bytes only;
app effects require the unchanged current provider/GPU receipt. This is not an
RQEC1 experiment controller and does not reuse its wearer code as Hub authority.

Version1 carrier header is eight bytes: version1, reserved0, unsigned big-endian
message id, byte offset, total byte length. IDs start1 and never wrap. Exact UTF8
messages are bounded16KiB, negotiated-MTU chunks at most244 bytes, ten-second
assembly and one peer. Read returns an empty payload when no message is queued.
Partial, replayed, oversized, stale and out-of-order messages close the carrier.
Bounded queues and process-held credentials are cleared on retirement.

Compile-only host checks require explicit pinned Android jar, Java runtime and
JSON runtime. The builder requires a create-new output and existing keystore;
it never creates a key or alters a shared SDK. Passwords are passed only through
named process environment entries. A separate root-owned APK build and actual
Android/BLE/browser/native grant qualification remain required.

The same app-owned controller handles visible Enable/Stop, notification Stop,
and the diagnostic-only local shell calls `enable`, `disable`, `status` at
`content://io.github.mesmerprism.rustyquest.connection_hub_ble_bridge.ble-carrier-control`.
The provider requires Android DUMP and runtime shell UID2000, with no argument
or extras. It cannot grant Bluetooth permissions, start the Hub, pair a controller,
change Wi-Fi, or send media commands. Missing current Hub readiness or permissions
denies Enable. Repeated Enable never renews an existing or unknown dispatch scope.

Its typed `rusty.quest.hub_ble_carrier_control_receipt.v2` separates accepted
requests from actual service/advertising callbacks. Service destruction and stale
callbacks are generation-bound. `carrier_ready_now` additionally obeys the
original monotonic deadline; it proves neither controller authority nor radio
cleanup. Stop request success does not prove effective service or GATT absence;
read Status plus independent platform readback. An unknown start/stop outcome
remains unknown. Nothing exposes pairing codes, opaque sessions, credentials,
caller-selected components or arbitrary provider operations.


### Shell foreground dispatch

The DUMP-protected provider additionally verifies the actual Binder caller is UID 2000.
Typed `enable` runs the same controller permission, Hub readiness and generation checks
as the human Enable handler, then returns `shell_start_prepared`. It does not start
advertising. The shell can dispatch the fixed `.BridgeService`
`SHELL_START` action with exactly `process_instance_id` (string) and `generation`
(long) from that receipt. The service consumes the process-held shell admission once
before foreground or GATT work. Admission remains until consumed, explicitly cancelled,
or its owning process ends; host review or scheduling delays do not expire it.
Wrong process/generation, replay, internal UI
scope and extra fields are denied. No new receipt grants Hub or pairing authority.
The UI uses an internal dispatch adapter with a separate private one-use token;
validation and the foreground/GATT implementation remain shared. Notification Stop
has a private service-instance token. Rejected intents cannot stop another scope.
`status` continues to distinguish prepared dispatch from service observation and
actual advertising callback. Fixed exception categories disclose no platform messages.

Receipt v2 adds `admission_policy`, `admission_state`, `admission_scope`,
`admission_issued_elapsed_ms` and `admission_age_ms`. State is `none`, `prepared`,
`consumed` or `cancelled`; scope is `none`, `shell` or `internal`. Elapsed age is
diagnostic only. No private token is emitted. Pending admission is cancelled
before Stop dispatch, on matching-generation failure, or on matching-generation
destruction. A lost or rejected platform dispatch does not become service closure
or permission to retry. A new process owns a new UUID and cannot consume the old
admission. The foreground service's actual fifteen-minute bound remains unchanged.

This is a versioned producer contract: v1 closed parsers must reject v2 until
their own explicitly reviewed source and artifact bindings are updated. Source
tests do not qualify the installed APK or any current carrier. The focused
`BridgeAdmissionLifetimeTest` drives the actual production State with a modeled
Android executor and reports progress; delayed and concurrent cases contain no
wall-clock kill guard. The broader host script also retains Service rejection
tests, which now use explicit cancellation rather than guessed admission expiry.

### Compile-only validation

`tools/Test-ConnectionHubBleBridgeAndroid.ps1` requires explicit Android, Java
and JSON runtimes, the JSON JAR SHA-256, a create-new output directory and
`-CarrierCapsule` pointing to the accepted carrier package. It compiles the actual
helper against the capsule's verified JAR and runs framing, loopback, controller
and admission/lifetime host cases. `tools/Test-ConnectionHubBleServiceReject.ps1`
adds rejection controls against explicitly modeled Android classes.

These checks prove source compilation and modeled contract behavior. They do
not prove an installed APK, Android Service lifetime, BLE discovery, advertising,
Hub authority or effective cleanup. A separate coordinated APK/device capsule
must qualify those effects. The build script's `-CompileOnly` route performs no
APK packaging, signing, installation or device calls.

The helper consumes the exact canonical Quest carrier JAR via -CarrierCapsule. Its Android manifest, service, controller admission and app effects remain helper-owned; the packaged framing library contains no permissions or activation. Build/Test scripts verify and pin the artifact receipt and put the JAR on the compiler/runtime/D8 dependency path. The app contains no private source copy of HubBleFrames.

### Selected helper package

`feature.json` declares this separate app-local helper, its dependency on
`quest-ble-carrier-v1`, and the existing manifest permissions. The library's
permission-free selection does not select this app. The helper is independently
packaged as `io.github.mesmerprism.rustyquest.connection_hub_ble_bridge`; it does
not replace or modify the streaming application. Launcher presence is inert.
Explicit visible Enable or the closed shell admission is required for each
process/generation scope. Descriptor selection alone proves no runtime activation.

The helper must use the current Hub signing certificate for its existing
signature-scoped admission/status read. It additionally requires an already
wearer-started Hub listener, actual Bluetooth runtime permissions, and the
existing Manifold controller/provider grants for native effects. Its receipt's
`carrier_ready_now` field requires the matching Service and advertising callbacks
within the original deadline. `FOREGROUND_SERVICE_CONNECTED_DEVICE` and the
Bluetooth permissions belong to this helper manifest, never the framing JAR.
The exact manifest remains the permission authority; host compilation requests
none of these Android permissions and performs no device effects.
