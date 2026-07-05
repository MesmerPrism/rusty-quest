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

The Java lifecycle is split by ownership. `Qcl041WifiDirectLifecycle.java`
keeps the Android callback/state-machine flow, `Qcl041WifiDirectNetworkBinder`
owns Wi-Fi Direct network/socket binding and app-side `ConnectivityManager`
snapshots, `Qcl041AppBoundSocketMatrix` owns the optional Quest-to-Quest
client-to-group-owner app-UID UDP/TCP binding matrix, `Qcl082MediaLanes` owns
relay and receive-proxy lane parsing/summaries, `Qcl082CopyProgress` owns relay
copy counters, and `ReceiverSocketCandidate` carries receiver socket
construction state.

The Quest-to-Quest app-bound socket matrix is a synthetic transport gate, not a
media pass. Its receiver side counts bytes observed by the group owner while
the client tries wildcard/source-bound UDP, `Network.bindSocket(DatagramSocket)`
UDP, source-plus-network-bound UDP, source-bound TCP, `Network.bindSocket(Socket)`
TCP, `Network.getSocketFactory()` TCP, native fd `android_setsocknetwork()`
UDP/TCP rows, and temporary QCL041-only
`ConnectivityManager.bindProcessToNetwork(...)` UDP/TCP rows. The native rows
load `libqcl041_socket_probe.so` and report `setsocknetwork` errno/status
without treating sender success as acceptance. The process-wide row restores
the previous binding before the probe exits.

When the matrix is enabled, it still runs after the older bounded TCP probe
fails or times out. That keeps the diagnostic focused on app-bound transport
reachability instead of requiring the legacy client-to-owner socket path to
pass before the matrix can test alternate binding recipes.

The matrix also opens an immediate `tcp_tunnel_control_socket` row on a
separate port before the UDP rows. The client creates the socket from the Wi-Fi
Direct `Network`, binds its local p2p address when available, connects to the
group owner, sends a bounded binary block, then receives a bounded binary block
back on the same socket. Both artifacts record byte counts and CRC32 matches;
this is an alternate-topology synthetic gate for a future local relay/tunnel,
not QCL100 media/render acceptance by itself.

The same wrapper can enable a separate `tcp_tunnel_stream_socket` row on its
own port. That row keeps the same app-bound socket topology but alternates
framed deterministic chunks in both directions for a configured duration and
byte budget. The default runner budget is 15 seconds and 4 MiB per direction,
and the summary reports `tcp_tunnel_stream_bidirectional_bytes_pass` only when
the group owner observed client bytes and the client observed owner bytes with
matching CRC32 values.

The Android matrix writes checkpointed partial artifacts before and after the
control tunnel, stream tunnel, immediate UDP rows, delayed UDP rows, and final
role completion. The host wrapper waits for `client_sender_completed` and
`group_owner_receiver_completed`; if a role stalls, the summary still keeps the
latest checkpoint and blocks as `client_matrix_incomplete`,
`owner_matrix_incomplete`, `client_matrix_missing`, or `owner_matrix_missing`
before falling through to stream-byte failure reasons.
The stream row also starts an app-side stall watchdog around the socket
transfer. If a Java socket read or write stops making progress, the watchdog
records the idle interval, writes a checkpoint, and closes the socket so the
role can finish with bounded diagnostics instead of leaving only a stale
pre-loop artifact.
Delayed UDP rows are also bounded by a client-side diagnostic timeout. A stuck
late UDP send records `delayed_udp_thread_timeout` and still lets the client
role finish, so a passing sustained TCP stream row is not hidden by a later UDP
lifetime probe.

For QCL-100 `mixed` media experiments, the QCL-082 reverse-TCP relay and
receive proxy recycle peer/source segments after ACK, progress, peer-idle, or
partial-close failures while the configured deadline and byte budget still
allow another segment. This keeps the immediately established TCP carrier in
the media path instead of treating the first partial reverse-TCP stall as a
terminal transport result.

The QCL-100 host runner waits for QCL041 artifacts instead of reading them once
immediately after the projection window. QCL041 can write a mid-hold artifact
while preserving the Wi-Fi Direct group for dependent media steps, and blocked
group-formation runs can finish after the host media window. If orchestration
still throws before the final summary, the runner writes a compact blocked
orchestration summary and performs the same package cleanup unless
`-SkipCleanup` was requested.

The runner also emits delayed UDP rows by default. After the immediate UDP
matrix, the client prepares early-bound Java `DatagramSocket` rows while the
selected P2P `Network` is still visible, then waits
`-DelayedUdpDelaySeconds` before sending through those already-bound sockets.
It also retries late `Network.bindSocket(DatagramSocket)`,
source-plus-network-bound UDP, native fd `android_setsocknetwork()`, and
process-bound UDP after the same wait. The group owner extends its UDP receive
window for that delay and records receiver-observed bytes as the only pass
signal; the client records selected-network `LinkProperties` and
`NetworkCapabilities` before and after the delayed send window.

The host-side Quest-to-Quest matrix runner is:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-Qcl041QuestToQuestAppBoundSocketMatrix.ps1 `
  -RequireInfrastructureWifiDisconnected `
  -RequireP2p0Ipv4Cleared `
  -RequireTcpTunnelStreamPass `
  -DelayedUdpDelaySeconds 45
```

That switch is a fail-closed airgap gate. It records `cmd wifi status` for both
headsets and refuses to install or launch if either Quest is still associated
with ordinary infrastructure Wi-Fi. The wrapper does not forget saved networks
or disable the Wi-Fi radio because Wi-Fi Direct still needs the radio and saved
network mutation should be operator-owned.

The same runner records shell route evidence for the direct-Wi-Fi parity
ladder. It writes `preflight-shell-routes.json`,
`active-group-shell-routes.json`, and `post-run-shell-routes.json` with
`ip route get 192.168.49.1`, `ip route get 192.168.49.1 from <p2p0-ip>`,
`ip rule show`, and `ip route show table all` for both Quests. It also writes
`app-network-visibility-summary.json`, which compares the app artifact's
selected Wi-Fi Direct `Network`/`LinkProperties`/`NetworkCapabilities` and its
filtered `NET_CAPABILITY_WIFI_P2P` `NetworkRequest` callback against the active
shell route snapshot. That makes the key decision explicit: QCL041 can see the
P2P `Network` while shell routing still avoids `p2p0`, QCL041 cannot see the
P2P `Network`, or both app and shell agree on `p2p0`.

`-RequireP2p0Ipv4Cleared` is the matching stale-group preflight gate. It
records `ip -4 addr show p2p0` for both headsets and refuses to install or
launch if either device still has a `p2p0` IPv4 assignment from an earlier
Wi-Fi Direct epoch. It does not toggle the Wi-Fi radio, forget networks, or run
ADB daemon lifecycle recovery.

`-RequireTcpTunnelStreamPass` keeps the wrapper blocked unless the sustained
`tcp_tunnel_stream_socket` row reports bidirectional bytes with matching CRC32
values. That prevents the older one-shot `tcp_tunnel_control_socket` pass from
masking a failed stream-row validation run.

QCL-030 LocalOnlyHotspot is a separate alternate-topology probe carried by the
same APK. It starts Android `WifiManager.startLocalOnlyHotspot()` on one Quest
and writes `rusty.quest.qcl030.local_only_hotspot_probe.v1` artifacts under the
app-private `qcl030` directory. The artifact may contain the generated SSID and
passphrase, is marked `credential_sensitive=true`, and is not a duplex/media
pass. It only answers whether Horizon OS will let the app host a local AP long
enough to close the reservation cleanly. In client-join matrix mode, the owner
also starts bounded UDP and TCP receivers while holding the hotspot, the client
joins with `WifiNetworkSpecifier`, sends synthetic bytes over sockets bound to
that requested Wi-Fi network, and the host summary passes only when the owner
artifact reports receiver-observed bytes.

Passive QCL-030 preflight does not install or launch the app:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-Qcl030QuestLocalOnlyHotspotProbe.ps1 `
  -Serial <quest-serial> `
  -PreflightOnly
```

Live QCL-030 probing is serial-scoped and does not take Agent Board leases
automatically:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-Qcl030QuestLocalOnlyHotspotProbe.ps1 `
  -Serial <quest-serial> `
  -HoldSeconds 60
```

The two-Quest client-join matrix launches the owner first, waits for the
credential-bearing live artifact, then launches the joining Quest. The
generated SSID and passphrase are redacted from `summary.json`, but the pulled
owner/client app artifacts remain credential-sensitive evidence files.
`-LaunchClientViaActivity` starts the joining side through the foreground
Activity so Android's UI-mediated `WifiNetworkSpecifier` request is owned by a
visible app surface; without it, the wrapper uses the foreground service route
as a regression path. The host summary records the selected launch surface.
`-ClientJoinMode ActiveWifi` is a separate follow-up gate for host-mediated or
operator-mediated joins: it does not call `WifiNetworkSpecifier`; instead, the
client app selects Android's active Wi-Fi `Network`, binds UDP/TCP sockets to
that network, and lets the owner artifact decide pass/fail from
receiver-observed bytes. Pair it with `-RequireActiveWifiSsidMatch` when the
active SSID must match the generated LocalOnlyHotspot SSID before socket
traffic is attempted.
`-HostJoinClientWithWifiSuggestion` is an opt-in host-mediated diagnostic for
that mode. It uses serial-scoped `cmd wifi add-suggestion`, polls redacted
`cmd wifi status`, launches the client in `ActiveWifi` mode, and removes the
suggestion during cleanup. This is not app-owned `WifiNetworkSpecifier`
evidence; it is a distinct topology candidate that still requires owner
receiver-observed bytes before media promotion.

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-Qcl030QuestLocalOnlyHotspotProbe.ps1 `
  -RunClientJoinMatrix `
  -OwnerSerial <owner-quest-serial> `
  -ClientSerial <client-quest-serial> `
  -HoldSeconds 90 `
  -LaunchClientViaActivity `
  -AutoApproveClientNetworkRequest `
  -SocketBytes 65536
```

`-AutoApproveClientNetworkRequest` is explicit opt-in because Android
`WifiNetworkSpecifier` opens a system network-request dialog on the client
headset. The wrapper records only redacted approval metadata in the host
summary. It applies only to the default `NetworkSpecifier` client mode.

For live runs, the wrapper installs the shared APK unless `-SkipInstall` is
provided, writes `qcl030-permission-preflight.json`, pregrants declared
runtime-grantable permissions such as `NEARBY_WIFI_DEVICES` and
`POST_NOTIFICATIONS`, and uses `dumpsys package` readback before launching the
foreground service. A passing owner probe proves only
`hotspot_started_and_reservation_closed_cleanly`; the generated credentials are
credential-sensitive and the next acceptance gate remains a separate client
join plus receiver-observed socket byte matrix. A passing client-join matrix is
still transport-topology evidence only; QCL-100 media stream/render parity
requires a later media-lane mapping and renderer scorecard.

The QCL-100 native stereo runner has the matching stale-state preflight:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1 `
  -PreflightOnly `
  -RequireInfrastructureWifiDisconnected `
  -RequireP2p0Ipv4Cleared
```

For a real media/render attempt, keep `-RequireP2p0Ipv4Cleared` enabled so the
runner refuses to start QCL041, broker, or native-renderer packages if either
headset still has a stale `p2p0` IPv4 assignment from an earlier Wi-Fi Direct
epoch.

For app-bound carrier diagnostics during a QCL-100 attempt, the runner can
enable a synthetic held control-TCP stream proof:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1 `
  -Qcl082ControlTcpMediaStreamBytesPerDirection 4194304
```

This uses the already-established QCL041 bounded control socket after the media
path starts, records bidirectional byte counts and CRCs under `control_tcp`, and
keeps the default zero-byte behavior unless explicitly enabled. It is carrier
topology evidence only; QCL-100 promotion still requires receiver-observed
QCL082 media bytes plus native-renderer freshness scorecards.

For a real QCL082 media-lane carrier over that same established control socket,
select the `control-tcp` transport:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1 `
  -Qcl082TransportProtocol control-tcp
```

In this mode the relay and receive-proxy worker surfaces record
`handled_by_control_tcp_media_carrier`, while the held control socket copies
lane-labelled media frames between local broker source sockets and local
receive-proxy target sockets. Promotion still depends on receiver-observed
QCL082 bytes and native-renderer freshness scorecards from the final run
window.

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

For segmented latency, the wrapper can run the bidirectional QCL-081 LSL echo
probe:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-Qcl041WifiDirectLifecycle.ps1 `
  -Serial <quest-serial> `
  -WindowsHelperProject S:\Work\repos\active\rusty-hostess\tools\connectivity_probe\qcl041_wifi_direct_broker\qcl041-wifi-direct-broker.csproj `
  -RunQcl081LslEcho `
  -Qcl081EchoSampleCount 300 `
  -Qcl081EchoIntervalMs 100
```

That mode creates a Windows `pylsl` command outlet, asks the Quest harness to
resolve it with a native `liblsl` inlet, then publishes a Quest-owned echo
outlet. The Hostess artifact `qcl081-wifi-direct-lsl-echo-roundtrip.json`
contains the dedicated clock-alignment formula and per-sample timings for
Windows send to Quest receive, Quest processing, Quest send to Windows receive,
and total RTT.
