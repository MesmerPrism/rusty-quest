# Quest offline hotspot handoff

This is a source/build-only, UID-2000 `app_process` diagnostic for one attended
saved-network handoff: original A → temporary WPA2 hotspot B → original A. It
uses the proven framework bootstrap (`Looper`, `ActivityThread.systemMain`, and
`com.android.shell` UID-2000 attribution) and typed modes only: `snapshot`,
`connect`, `restore`, `guard`, and the read-only `watchdog-probe`. It is not a
generic shell, WifiManager RPC, or production feature.

Its private shell-readable configuration must be exactly
`/data/local/tmp/rqohh-<32-lowercase-hex-run-token>.properties` and contain:

```properties
run_token=<32 lowercase hex>
temporary_ssid=<hotspot SSID>
temporary_wpa2_password=<8..63-character WPA2 passphrase>
original_network_id=<captured A network ID>
deadline_elapsed_realtime_ms=<absolute Android elapsedRealtime deadline>
```

`snapshot` is read-only and requires A to be selected, Wi-Fi enabled, and its
selection metadata to equal the actual framework constants
`NETWORK_SELECTION_ENABLED` / `DISABLED_NONE`. `connect` starts and waits for a
separate deadline watchdog before adding B. It writes a private state record,
adds exactly one WPA2-PSK `WifiConfiguration` (with
`noInternetAccessExpected=true` when that field exists), selects it with
`enableNetwork(B, true)`, then waits up to the earlier of its deadline and 20
seconds for both B's current network ID and a non-loopback IPv4 address from
the active network's `LinkProperties`. It writes B's returned ID to the
private state record immediately after `addNetwork`, before any created-profile
readback, so the watchdog can clean it up after a later readback failure. It
does not fingerprint or print the password, and it does not toggle the radio.

`restore` and the deadline watchdog each lock the same run state. They select
A, wait for A's ID as the actual connection readback, remove only the recorded
B network after an exact created-profile fingerprint check, and require the
saved-profile baseline fingerprint and A profile fingerprint to return. A
successful restore writes the watchdog stop marker. Any exception reports the
closed mode/stage/error type and exits nonzero; it never prints SSIDs or keys.

`watchdog-probe` is a read-only, bounded (1–30 seconds) survival probe. It
launches its child through the same detached `app_process` path as `guard`,
writes private `.probe-ready` and `.probe-completed` markers, and does no
Wi-Fi operation. Use a fresh token/config to compare ordinary initiator exit
with a separately controlled ADB-daemon restart. Establish ADB transport
configuration before arming a real handoff: the bounded read-only probe showed
that restarting the ADB daemon can terminate the detached shell child. An
ordinary guardian restoration does not establish survival through that restart.

The Java source and builder were exercised in an attended Android 14 Quest
handoff. Publication excludes the private configuration, network identifiers,
credentials, device receipts, and built DEX. A new build remains
`built_device_unverified` until its own target run is captured. Supply a
printable ASCII WPA2 passphrase; the runtime itself enforces only the 8–63
character length bound before Android validates the configuration.

Build with the scoped Agent Board lease:

```powershell
pwsh -NoProfile -File .\Build.ps1 -AndroidHome <android-sdk> -JavaHome <jdk17> -BuildLeaseId <lease-id>
```

The build result is a device-unverified DEX. A later owner-runner may stage it
privately and invoke, for example,

```text
CLASSPATH=<private-dex> app_process /system/bin QuestOfflineHotspotHandoff snapshot <private-config>
CLASSPATH=<private-dex> app_process /system/bin QuestOfflineHotspotHandoff connect <private-config>
CLASSPATH=<private-dex> app_process /system/bin QuestOfflineHotspotHandoff restore <private-config>
CLASSPATH=<private-dex> app_process /system/bin QuestOfflineHotspotHandoff watchdog-probe <private-config>
```

The configuration's monotonic deadline must be based on an observed Quest
`elapsedRealtime` value; a host wall-clock deadline is intentionally rejected.
No device command is run by this lane.

Run the source-only contract check without an Android build or headset:

```powershell
pwsh -NoProfile -File .\Test-Source.ps1
```

The check verifies the corrected selection constants, UID-2000 attribution,
closed modes, exact configuration path, bounded deadlines, temporary-profile
fingerprint, restore lock, and fixed Wi-Fi effects. It does not claim runtime
behavior.
