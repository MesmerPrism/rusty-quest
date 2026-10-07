# Quest Bluetooth carrier library

This dependency-free Java library owns the existing Hub BLE fragmentation
profile. It packages as a JAR, usable by Android hosts without an Android
manifest, service, permission, command registry, or Rust authority library.
Pure Java needs no additional rlib; an Android host keeps its existing one.

`HubBleFrames` retains the Hub UUIDs, eight-byte frame header, ordered IDs,
16 KiB body limit, 244-byte chunk cap, ten-second assembly deadline, strict
UTF-8 decoder, and terminal invalidation. These are carrier bounds, not
authentication or app-effect receipts. The RQEC1 experiment profile remains
separate; its operation list and arm conditions belong to that app.

Build a fresh external output capsule with `Build-Carrier.ps1 -OutDir ...`.
Hosts consume its exact `quest-ble-control.jar` artifact. The receipt records
input hashes and artifact hash. `Test-Carrier.ps1` compiles conformance tests
against the packaged JAR, rather than including production source in the host.

Build and check the packaged API in a fresh capsule:

```powershell
pwsh -NoProfile -File android/ble-control/Build-Carrier.ps1 -OutDir $capsule
pwsh -NoProfile -File android/ble-control/Test-Carrier.ps1 -Capsule $capsule
```

Both commands accept `-JavaHome`; otherwise they use `JAVA_HOME` or the Java
tools on `PATH`. The build emits `artifact.json` beside the JAR. Before using
the package, hosts must verify that the JAR SHA-256 equals the receipt's
`sha256`, then include that same JAR in their compiler, test runtime
and Android D8 inputs. They import
`io.github.mesmerprism.rustyquest.ble_control.HubBleFrames`; they do not compile
another copy of the production class. A fresh build capsule preserves previous
artifacts and avoids mixing outputs with source.

`Test-SupplierConformance.ps1` additionally checks an explicitly selected
supplier's original framing tests against the JAR. It adds the canonical import
to an isolated test projection and verifies that the original test bytes did
not change. This proves compatibility with that supplier's tests; actual host
source compilation and JAR packaging are separate consumer evidence.

This extraction preserves the original supplier framing bytes except its
package declaration. The host still owns Bluetooth permissions, advertising,
GATT service lifetime, connection generations, challenges, admission, command
dispatch and completion receipts. Neither successful frame reassembly nor a
successful GATT write confirms an app effect. Library conformance does not
establish Android lifecycle, physical Bluetooth behavior or owner acceptance.
