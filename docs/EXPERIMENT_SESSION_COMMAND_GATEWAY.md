# Optional own-app command gateway

The `ui.experiment_session_command_gateway` feature selects Java command gateway
code in the existing breath composition module. The packager must explicitly
emit application metadata `rusty.quest.experiment.command_gateway.enabled=true`
and `rusty.quest.experiment.command_gateway.origins` containing a comma-separated
closed allowlist of HTTPS browser origins. Unselected packages must omit both.
Internet permission is required. No separate broker APK, shell grant, or
read-only embedded WebSocket upgrade is involved.

Runtime enrollment remains off until a headset operator approves an encrypted
relay controller or trusted-LAN control. Approval replaces the one persistent controller with a random 32-hex
controller ID and 64-hex secret key stored in app-private preferences. Only the
headset approval dialog displays the key. Revocation removes it. Encrypted-only
enrollment creates no HTTP listener and cannot create a direct network; trusted
LAN has a separate explicit local consent bit. Unencrypted LAN
has no confidentiality; use only on an explicitly trusted network. Controller
membership persists across process restart; native epoch replay protection does
not. Each native lifetime or enrollment creates a fresh random 52-bit gateway epoch, independent of native epoch counters that may reset across process restart.

Only trusted-LAN enrollment starts the listener. It uses TCP 8766, accepts bounded HTTP/1.1 requests from private/local
addresses, and exposes only `GET /v1/gateway` and `POST /v1/gateway`. GET returns
unauthenticated discovery hints `{v:1,device:<32hex>,epoch:<decimal string>,enabled}`.
It is never command readiness. OPTIONS admits only exact configured origins,
GET/POST, Content-Type and explicitly responds to local-network preflight.
Supported browsers must grant Local Network Access for public HTTPS to private
HTTP; browsers that block this route need the separately selected HTTPS relay.

POST carries exactly these seven fields:

```json
{"v":1,"controller":"32 lowercase hex","epoch":"positive decimal string","nonce":"32 lowercase hex","action":"status","payload":"{}","mac":"64 lowercase hex"}
```

The MAC is full HMAC-SHA256, with the ASCII bytes of the 64-hex key as key:
`RQG1|controller|epoch|nonce|action|payload`. Payload is a JSON **string**, signed
byte-for-byte as its decoded UTF-8 string; JSON object property order is therefore
irrelevant to envelope decoding. Nonces are unique for the controller/epoch.
Only `status`, `command`, and `receipt` are supported. Status payload is `{}`;
receipt payload is `{"id":"original 16-hex command ID"}`. Command payload is the
existing closed BLE v1/v2/v3/v4 command without `mac`; its original ID, session
reference/generation, setup epoch and Polar capability fences are preserved.
Network membership never bypasses these app-owned capability checks.

Replies contain `{v:1,device,epoch,nonce,action,payload,mac}`. Payload is the exact
JSON string of the fresh compact BLE status or original command receipt. Reply
MAC is `RQG1-REPLY|controller|device|epoch|nonce|action|payload`; The reply authenticates device and gateway epoch. Native status `o.e` remains the separate native epoch; the gateway binds both epochs to its live native owner. Status observations retain their original freshness metadata.
Accepted/admitted means transport admission only. Confirmed effects come only
from existing native/session/Polar handlers and original receipt callbacks.

Within one epoch the gateway retains up to 256 original command IDs and their
payloads/receipts in a journal shared with BLE admission. Per-transport nonce and
MAC fields are excluded from semantic ID comparison. A BLE original can be
queried over the network, and a network original retried on BLE returns the same
receipt. Both routes consult the journal after authentication and before effect
dispatch. Identical retry returns its original outcome without dispatch;
a different payload with the same ID rejects. Capacity denies new operations
instead of evicting dedupe. A retired epoch rejects all old envelopes. Requests
are bounded to 8192 bytes and 65536 authenticated nonces per epoch.

The closed inventory remains BLE v1 `ping`, `arm`, `save-next`, `save-exit`,
`return-vr`, `open-polar`; v2 `start`, `pause`, `resume`; v3 `stop-acc`, `stop-ecg`;
and v4 `scan`, `select`, `connect`, `start-acc`, `start-ecg`. Each route retains its
existing native session, sensor, revision, identity and presentation checks.
BLE challenge optionally exposes public `d` (device ID) and `ge` (gateway epoch)
for receipt correlation; no enrollment key is exposed on unauthenticated reads.

Relay and direct-network adapters call the same `identity()` and `exchange()`
methods. They acquire no command authority of their own. Application preparation
creates no listener: initialized native-owner lifetime plus selected feature and
headset enrollment plus explicit trusted-LAN consent are required. An encrypted
relay uses the same enrolled controller without opening HTTP. Native owner
teardown closes every selected transport. Relay connection additionally requires
selected relay metadata, the fixed approved origin, and operator-provisioned host
session, device token and exact expiry timestamp; no expiry is minted by the UI.

Host protocol tests and Android source compilation are separate from physical
LAN, browser permission, native effect, durable recording, and packaging proof.
