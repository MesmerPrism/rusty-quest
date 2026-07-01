# QCL-041 Wi-Fi Direct Harness Android App

This app is the Quest-side live harness for QCL-041 Windows Wi-Fi Direct
lifecycle evidence; no Android phone is required for this route. It writes
`rusty.quest.connectivity_wifi_direct_lifecycle.v1` artifacts under the app
private/external `qcl041` directory.

The harness is intentionally live-only. Static validation checks the Android 13+
Wi-Fi Direct permission matrix, `WifiP2pManager` lifecycle calls, role
recording, bounded TCP exchange path, cleanup calls, Agent Board lease fields,
and the Hostess Windows helper wrapper. Passing topology evidence still requires
a Quest headset, a reserved `quest:<serial>` lease, the Windows QCL-041 helper,
peer discovery, group formation, a bounded TCP request/ack, cleanup, lease
release, and Hostess normalization.

Build:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-Qcl041WifiDirectHarnessAndroid.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-Qcl041WifiDirectHarnessAndroid.ps1
```

Live run, after confirming the Windows Mobile Hotspot is off:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-Qcl041WifiDirectLifecycle.ps1 `
  -Serial <quest-serial>
```

For the Windows-peer route, prefer the Hostess UI-thread broker:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-Qcl041WifiDirectLifecycle.ps1 `
  -Serial <quest-serial> `
  -WindowsHelperProject S:\Work\repos\active\rusty-hostess\tools\connectivity_probe\qcl041_wifi_direct_broker\qcl041-wifi-direct-broker.csproj
```

The first promoted 2026-07-01 run required both sides of the data-plane fix:
the Windows broker kept the `WiFiDirectConnectionRequest` alive through
`WiFiDirectDevice.FromIdAsync`, and the Quest harness created its Java socket
from Android's Wi-Fi Direct `Network` before connecting. The passing artifact
showed `p2p0`, a `192.168.137.x` Quest address, one bounded request/ack, and
Hostess `promotion.allowed=true`.

The same harness can also run the QCL-081 Quest-runtime LSL proof while the
Wi-Fi Direct group is formed:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-Qcl041WifiDirectLifecycle.ps1 `
  -Serial <quest-serial> `
  -WindowsHelperProject S:\Work\repos\active\rusty-hostess\tools\connectivity_probe\qcl041_wifi_direct_broker\qcl041-wifi-direct-broker.csproj `
  -RunQcl081Lsl
```

That mode starts a Windows `pylsl` inlet and asks the Quest app to bind the
Android process to the Wi-Fi Direct `Network` before loading `liblsl` and
publishing a one-channel float32 outlet. The acceptance evidence is the paired
QCL-041 lifecycle artifact plus `qcl081-wifi-direct-lsl-receiver.json`, with
`network_provider=wifi_direct`, `local_endpoint=192.168.137.1`, the Quest
`p2p0` endpoint, 16/16 monotonic samples, Quest `lsl_local_clock` source
timestamps, and Hostess QCL-081 `promotion.allowed=true`.

The promoted QCL-081 backend remains official `liblsl`. Alternative backend
experiments are recorded in the private planning notes only and are not part
of this published harness.
