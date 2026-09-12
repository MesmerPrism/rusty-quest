# Quest APK ↔ shell capability diagnostic

This is a bounded diagnostic package, separate from the saved-source relay. It
tests typed echo, 50 small request/response samples, and a fixed 4 MiB patterned
SHA-256 transfer over loopback TCP and abstract Unix sockets in both directions.
The Binder probe sends a shell-created Binder through the exported provider and
records both Binder calling UIDs. There is no command or filesystem RPC.

Every run uses one 32-lowercase-hex token. The app accepts an absolute elapsed
realtime expiry no more than ten minutes ahead. Endpoint names are exactly
`rqsc.shell.<token>` and `rqsc.app.<token>`.

Build:

```powershell
pwsh -NoProfile -File .\Build.ps1 -AndroidHome <sdk> -JavaHome <jdk17> -BuildLeaseId <lease-id>
```

The runner should push the exact built APK to a private shell-readable path and
start the shell-owned endpoints first:

```text
CLASSPATH=<device-apk> app_process / io.github.mesmerprism.rustyquest.shellcapabilities.ShellRole serve <token>
```

The first stdout line is `rusty.quest.shell_caps_shell_ready.v1` and contains
`tcp_port` and `uds_name`. Start the ordinary APK with:

```text
am start -W -n io.github.mesmerprism.rustyquest.shellcapabilities/.CapabilityActivity -a io.github.mesmerprism.rustyquest.shellcapabilities.START --es run_token <token> --el expires_elapsed_ms <absolute_elapsed_ms> --ei shell_tcp_port <port> --es shell_uds_name rqsc.shell.<token>
```

Read app readiness/result with `run-as io.github.mesmerprism.rustyquest.shellcapabilities cat files/capability-<token>.json`.
Use its `app_tcp_port` and `app_uds_name` for:

```text
CLASSPATH=<device-apk> app_process / io.github.mesmerprism.rustyquest.shellcapabilities.ShellRole probe-app <token> <app_tcp_port> <app_uds_name>
CLASSPATH=<device-apk> app_process / io.github.mesmerprism.rustyquest.shellcapabilities.ShellRole binder <token> <installed_app_uid>
```

Stop only the exact run:

```text
am start -W -n io.github.mesmerprism.rustyquest.shellcapabilities/.CapabilityActivity -a io.github.mesmerprism.rustyquest.shellcapabilities.STOP --es run_token <token>
```

This source has three deliberately separate lanes:

- `ShellRole` compares loopback TCP, abstract Unix sockets, and one-shot Binder
  handoff. It does not mutate Wi-Fi or Bluetooth.
- `LeaseShellRole` retains a typed Binder and transfers fixed 4 KiB and 4 MiB
  patterns through Binder-carried pipes in both directions.
- `WifiShellRole` accepts only authenticated BLE `NOOP`, `WIFI_OFF`, and
  `WIFI_RESUME` frames and owns bounded Wi-Fi restoration.

The Android sources were exercised on an Android 14 Quest. Publication does
not include device receipts, tokens, BLE addresses, network identities, or
credentials. Rebuilds still report `built_device_unverified`; a successful
build is not a substitute for a fresh target receipt.

After a shell-only change, the installed APK can be retained. Build and push
the bounded shell DEX instead:

```powershell
pwsh -NoProfile -File .\Build-Shell.ps1 -AndroidHome <sdk> -JavaHome <jdk17> -BuildLeaseId <lease-id>
```

For its `binder` command, the shell role acquires the provider through
`IActivityManager.getContentProviderExternal`, calls `IContentProvider.call`
with an explicit UID-2000 `AttributionSource` for `com.android.shell`, and
releases the external-provider lease in `finally`.

The retained Binder/pipe phase uses `LeaseShellRole` from the same APK:

```text
CLASSPATH=<device-apk> app_process / io.github.mesmerprism.rustyquest.shellcapabilities.LeaseShellRole server <token> <app_uid> <ttl_ms>
CLASSPATH=<device-apk> app_process / io.github.mesmerprism.rustyquest.shellcapabilities.LeaseShellRole status <token> <app_uid>
CLASSPATH=<device-apk> app_process / io.github.mesmerprism.rustyquest.shellcapabilities.LeaseShellRole negative-callback <token> <app_uid>
CLASSPATH=<device-apk> app_process / io.github.mesmerprism.rustyquest.shellcapabilities.LeaseShellRole stop <token> <app_uid>
```

For the bounded retained-shell post-drop diagnostic, start the lease server
with a TTL no greater than 120 seconds, then schedule the ordinary APK's fixed
post-drop probe before the intentional Wi-Fi/TLS loss. It waits only the given
app-local delay (0–30,000 ms), uses the already retained Binder, executes its
fixed STATUS operation and the existing 4 KiB / 4 MiB pipes in both directions,
and writes app-private `files/post-drop-<token>.json`. It accepts no method,
shell command, network, or filesystem path input:

```text
am start -W -n io.github.mesmerprism.rustyquest.shellcapabilities/.CapabilityActivity -a io.github.mesmerprism.rustyquest.shellcapabilities.POST_DROP_PROBE --es run_token <token> --el post_drop_delay_ms <0..30000>
```

The result records scheduled/start/completed `elapsedRealtime` values, app UID
and PID, the shell PID supplied at `lease_open`, retained-Binder liveness/death
state, shell callback caller UID, app-read `adb_wifi_enabled`, app-read Wi-Fi
enabled state, and fixed pipe hashes or error types. It requires the exact
retained lease token before attempting any pipe. It does not prove
that TLS, Wi-Fi, a shell process, or Binder survives until the result is
actually read after the drop. Stop the exact lease through its existing typed
`LeaseShellRole stop` command after recovery. Keep the total delay and transfer
time inside the shell's 120-second watchdog window.

TTL is 1–120 seconds and its watchdog starts before provider acquisition. The
server tests 4 KiB and 4 MiB patterned pipes in both directions before READY.
The exported provider requires Android permission `DUMP`, then checks that the
Binder caller is UID 2000 and that `expected_app_uid` equals its own app UID.
Every callback checks the 32-hex token and the app Binder caller UID. The shell
bootstrap uses Android 14 hidden signatures exactly: external provider
acquisition, `IContentProvider.call` with an explicit UID-2000
`AttributionSource` for `com.android.shell`, and lease release in `finally`.
This is a diagnostic identity fence, not a reusable product authorization
protocol.

The bounded BLE/WiFi diagnostic uses the same retained Binder lease. Start its
shell role before the app:

```text
CLASSPATH=<device-apk> app_process / io.github.mesmerprism.rustyquest.shellcapabilities.WifiShellRole server <token> <app_uid> <ttl_ms> <secret_hex64>
CLASSPATH=<device-apk> app_process / io.github.mesmerprism.rustyquest.shellcapabilities.WifiShellRole status <token> <app_uid>
CLASSPATH=<device-apk> app_process / io.github.mesmerprism.rustyquest.shellcapabilities.WifiShellRole stop <token> <app_uid>
```

Then start `BLE_START` with the same token and an absolute elapsed-realtime
expiry no more than 120 seconds ahead. Read readiness from
`files/ble-<token>.json`; it is written only after the GATT service-add callback
and advertising-start callback succeed. `BLE_STOP` requires the exact token.
The service/RX/TX UUIDs are respectively `b11c0001`, `b11c0002`, and
`b11c0003` with suffix `-7a2b-4c3d-9e0f-112233445566`.

Frames are exactly 20 bytes: version 1, operation/outcome, big-endian sequence,
and the first 16 bytes of HMAC-SHA256 over direction (`C` or `R`), raw 16-byte
token, and the four-byte header. Requests are NOOP=1, WIFI_OFF=2, and
WIFI_RESUME=3. The TX characteristic starts with signed READY=0x10 at sequence
zero. WiFi commands are fixed to `cmd wifi set-wifi-enabled disabled|enabled`;
the server requires an enabled baseline and arms a token-owned 30-second
restore guardian before OFF. The guardian runs for 30 seconds and enables the
radio if the server cannot cancel it; configure ADB before starting this role,
because an ADB daemon restart can terminate a detached shell child. TX
notifications remain outside this diagnostic; the host reads the characteristic
after each acknowledged write.

`ble_wifi_client.py` is a portable Bleak client for the fixed authenticated
sequence. It performs wrong-MAC and replay negatives, a valid no-op, Wi-Fi off,
ten seconds of bounded TCP reachability probes while BLE remains connected, and
Wi-Fi resume. It accepts no device command or Wi-Fi profile input:

```text
python ble_wifi_client.py --token <32-lowercase-hex> --secret <64-lowercase-hex> --endpoint <host>:<port> --output <new-directory>
```

Install `bleak` in the host Python environment first. Keep the token and secret
out of command transcripts intended for publication. A client failure after
`WIFI_OFF` relies on the shell role's bounded restore guardian; preserve a
separate recovery path for any live run.

The BLE secret is supplied as a shell-process argument and can be observed by
actors with sufficient local process visibility. The HMAC proves that the
bounded test client and server share that ephemeral input; it does not provide
product credential storage, enrollment, revocation, or unrelated-app isolation.

## Static check

Run the source-only check without an Android build or a headset:

```powershell
pwsh -NoProfile -File .\Test-Source.ps1
```

It runs the pure-Java BLE protocol test, Python syntax validation, and closed
surface checks for package, provider permission, UID, token, Binder, pipe,
Wi-Fi command, and guardian invariants. `Build.ps1` requires PowerShell 7.6,
JDK 17, Android platform 35, build-tools 35.0.0, and a caller-supplied build
lease identifier. Its output is content-addressed under
`target/quest-shell-capabilities`; `Build-Shell.ps1` emits only the retained
Binder shell DEX when the installed APK is unchanged.
