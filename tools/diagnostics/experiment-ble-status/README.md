# Read-only experiment BLE status diagnostic

This optional Python client reads the existing RQEC1 status characteristic. It
does not write commands, pair, enable a carrier, change permissions, replay an
action, or establish protocol authority. Apps must independently select and
enable their carrier. The default UUIDs are the existing experiment profile;
explicit UUID parameters do not make another status protocol compatible.

Host controls use the standard library:

```text
python tools/diagnostics/experiment-ble-status/test_probe.py
```

Live use additionally requires the optional `requirements.txt` dependency
(Bleak 0.22.3). Tests can exercise its actual Windows connection Boolean proxy
when that backend is available. Dependency availability and host tests do not
qualify a radio connection. Record and qualify a different dependency version
separately before using it for live evidence.

```text
python -m pip install -r tools/diagnostics/experiment-ble-status/requirements.txt
python tools/diagnostics/experiment-ble-status/probe.py --address <independently-joined-peer-address> --name <optional-exact-name> --output <create-new-private-directory>
```

Keep addresses, names, device captures and all output outside public source.
Observe the owning task's device/radio coordination before live use. Every cycle
requires fresh discovery of the explicit address, optionally its exact name,
followed by uncached Windows service discovery and actual readable status
membership in the selected GATT service, including service UUID and attribute
handle joins. Duplicate status UUIDs in other services cannot supply membership. Windows' last system advertisement may
omit the app UUID; advertisement UUID absence does not substitute for the GATT
membership check. An address requires an independent peer identity join.

Scan, connect, read, poll, cycle gap and disconnect limits are configurable and
bounded. `--scan-only` performs discovery only. Defaults select three read-only
cycles and require two advancing native read completions per connection. A
disconnect timeout preserves an unresolved failure and prevents another cycle;
observation availability is retired before every disconnect attempt, including
timeouts. It is not proof the radio or system GATT handles closed. The Windows backend
can retain shared GATT for much longer than the poll interval, hence a separately
selected disconnect deadline up to 135 seconds.

For carriers exposing optional native observation `o`, integer v1, canonical
positive int64 epoch/read identifiers, safe-integer outer/native g/r joins,
closed observed/unknown shapes and native age bounds are enforced. Legacy
status without `o` remains native-observation unavailable. Unknown, malformed
or regressed status clears availability without clearing high waters; only a
strictly newer epoch/read may restore it. Repeated reads use the greater of
reported age and the previous lower bound plus host elapsed monotonic time;
polling cannot reset age. Delivery delay remains unknown. A new connection
starts an independent observation scope, not cross-connection continuity proof.

The create-new output snapshots the executed probe source and configuration,
records Python/Bleak versions and hashes, and retains each strictly valid UTF-8
status body of at most 480 bytes, including malformed JSON failure bodies.
Events and the final result retain failures and disconnect outcomes. Changed
probe source during execution invalidates success. A successful native status
read is neither command admission nor app effect completion. Outer f/p/bt
labels do not independently prove immersive focus, physical headset readiness,
Polar permissions or another bridge's GATT state. Browser reconnection, pairing,
physical readiness and other bridges require their own evidence.
