# Rusty Quest Peer Rendezvous Android

This APK is an explicit opt-in BLE/GATT sidecar for low-rate Quest peer
rendezvous. It advertises or scans one scoped service, exchanges bounded
authenticated hints, and writes an app-private
`rusty.quest.ble_rendezvous_sidecar_receipt.v1` receipt.

The v1 sidecar proposes a Wi-Fi Direct role and exchanges configured
address and broker-port hints. Authentication does not make these observations. It does not form or tear down a
Wi-Fi Direct group, execute Manifold commands, carry media, publish device
serials, or record Bluetooth addresses. A normal launcher start is inert;
automation must use the exact start action and `enabled=true`.

Build:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-PeerRendezvousAndroid.ps1
```

The output APK is
`target/peer-rendezvous-android/rusty-quest-peer-rendezvous.apk`.

Static and source validation:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-PeerRendezvousAndroid.ps1
cargo test -p rusty-quest-device-link
cargo run --quiet -p rusty-quest-device-link --bin validate_ble_rendezvous -- message fixtures\device-link\ble-rendezvous-offer.pass.json
```

The leased headset smoke wrapper writes a redacted summary plus the app-private
receipt under `target/peer-rendezvous-runs/<run-id>/`. `ready` means one-role
adapter readiness and complete cleanup without a peer. `pass` is reserved for
an authenticated bidirectional peer exchange followed by an authenticated
disconnect/reconnect cycle. Replayed nonces, wrong epochs/sequences, and peer
identity changes fail closed. The client proves the physical link transition;
the server proves the second fresh authenticated offer/proposal/accept cycle
because Quest's GATT-server callback does not reliably expose the intermediate
disconnect event.

The two-Quest acceptance wrapper runs both BLE role layouts and requires an
authenticated reconnect in each phase:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-PeerRendezvousAndroidPair.ps1 `
  -PrimarySerial <serial> -SecondarySerial <serial> `
  -PrimaryQuestLeaseId <lease-id> -SecondaryQuestLeaseId <lease-id>
```

It writes one redacted
`rusty.quest.peer_rendezvous_android_pair.v1` artifact under
`target/peer-rendezvous-pairs/<run-id>/`. The wrapper generates an ephemeral
test secret when none is supplied, never records it, and does not treat this
ADB orchestration as the future autonomous provisioning path. The final pair
artifact is independently validated by the data-only `rusty-quest-device-link`
contract through `validate_ble_rendezvous pair`.

The v1 wire and receipt schema remain unchanged. Both actual GATT callbacks now
reject GO/GO and client/client proposals. An `either` preference resolves to the
opposite explicit preference; two `either` preferences resolve by ordered peer
tags. A changed peer or configured role across reconnect is rejected. These
predicates coordinate proposals and confer no topology authority.

An explicit `observed_coordination_v2=true` start with `expected_peer_tag` uses
`rqrv` version 2 and `rusty.quest.ble_role_readiness_receipt.v2`. Session and peer
tags retain the 4-32 ASCII safe-character bound; an explicit positive long
`coordination_epoch` is independent of the unchanged v1 integer epoch. It requires an already formed group and
explicit GO/client preferences. It does not form a group. The read-only observer
requests connection and group information, joins their GO role with the current
group interface's actual IPv4 address, and binds a boot tag, salted group tag
including the normalized observed owner MAC,
request elapsed time, and maximum 5-second age. Missing permission, partial
callbacks, an unsupported address, changed role/group/peer, or stale observation
never falls back to intent hints. Missing, redacted, placeholder, or multicast
owner MACs are unavailable; a platform that redacts this needs a separately
reviewed shell-observation/nonce bridge. The raw MAC is not emitted. Requests are bounded by the original service
lifetime. The opt-in permission delta is ACCESS_WIFI_STATE and
NEARBY_WIFI_DEVICES (or fine location through API 32); the default v1 permission
request remains unchanged. No Wi-Fi formation/removal or socket API is added.

Version 2 signs the boot/group/role/address facts and echoes the offer nonce in
the proposal and proposal nonce in the accept. Both actual callbacks retain the
existing epoch/session/sequence and replay checks and enforce complementary
observed roles. Messages have a separate 244-byte ceiling and must fit the
actual negotiated MTU minus 3 bytes; oversized or fragmented v2 reads are
rejected. The closed 14-position JSON array uses compact kind codes, with a separately
domain-bound RQRV2 signing input. Maximum 32-character tags, a 13-digit epoch,
and longest supported IPv4 addresses fit 234 bytes; Long.MAX_VALUE fits 240.
Oversized serialized inputs are still rejected. The existing v1
220-byte ceiling is unchanged. The v2 receipt retains the last authenticated
peer message for independent joins. `broker_ready` and `wifi_ready_claimed` are
false: these local group facts are not broker, route, socket, or Manifold
acceptance. The old Rust v1 validator and pair runner remain v1-only and must
not be used to relabel v2 receipts.

Focused host validation accepts explicit `-JavaHome`, `-AndroidJar`, `-JsonJar`,
and create-new `-HostOutDir` parameters on `Test-PeerRendezvousAndroid.ps1`. It
compiles all production Java against the real Android API, then executes the
actual protocol and verbatim GATT guard bodies using modeled Intent/elapsed
clock and transport fixtures. These tests are source evidence, not device
readiness or authority evidence.
