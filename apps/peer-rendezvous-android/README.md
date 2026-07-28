# Rusty Quest Peer Rendezvous Android

This APK is an explicit opt-in BLE/GATT sidecar for low-rate Quest peer
rendezvous. It advertises or scans one scoped service, exchanges bounded
authenticated hints, and writes an app-private
`rusty.quest.ble_rendezvous_sidecar_receipt.v1` receipt.

The sidecar may propose a Wi-Fi Direct role and report an already-observed
`p2p0` address plus local Manifold broker port. It does not form or tear down a
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
