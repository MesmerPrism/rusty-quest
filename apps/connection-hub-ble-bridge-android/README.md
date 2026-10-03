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

Its typed `rusty.quest.hub_ble_carrier_control_receipt.v1` separates accepted
requests from actual service/advertising callbacks. Service destruction and stale
callbacks are generation-bound. `carrier_ready_now` additionally obeys the
original monotonic deadline; it proves neither controller authority nor radio
cleanup. Stop request success does not prove effective service or GATT absence;
read Status plus independent platform readback. An unknown start/stop outcome
remains unknown. Nothing exposes pairing codes, opaque sessions, credentials,
caller-selected components or arbitrary provider operations.
