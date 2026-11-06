# Experiment session network routes

These optional in-process adapters transport the same app-owned gateway
commands and original receipts. They do not install a Connection Hub, grant
permissions, start media, or introduce a second experiment authority.

## Composition and scope

`ExperimentSessionRelayClient` and `ExperimentSessionDirectNetwork` live in the
native renderer's selected breath-composition panel module. The common gateway
owns local attended enrollment, lifecycle wiring, the app command dispatcher,
controller revocation, and route closure. The trusted relay origin comes from
`rusty.quest.experiment.command_gateway.relay_origin` build metadata. Selecting
a relay requires private operator configuration and a headset-approved gateway
controller. An arbitrary browser URL cannot supply an approved origin.
The explicit feature IDs are `ui.experiment_session_relay` and
`ui.experiment_session_direct_network`, each depending on
`ui.experiment_session_command_gateway`.

Direct networking requires the explicit metadata feature
`rusty.quest.feature.experiment_direct_network`. Its permission closure is
`ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES` with
`neverForLocation`, and `ACCESS_FINE_LOCATION` for earlier Android versions.
For a direct-only permission owner, a newly added fine-location declaration is
limited to API 32. When `sensor.polar_h10_ble` is also selected, preserve its
existing full-range fine-location declaration: the current Polar runtime still
requires fine and coarse location on API 31 and later. Direct networking must
not narrow another selected feature's permission contract. The gateway/relay also requires `INTERNET`. Disabled features
must remain absent from the resolved manifest. Permission requests are local
attended UI actions; denied or unsupported platform operations remain blocked.

## HTTPS relay protocol

The host is `tools/experiment_control_relay.py`. One private JSON config declares
`session` (16–64 URL-safe characters), distinct random `device_token` and
`browser_token` (32–128 URL-safe characters), the gateway's 32-hex `device`, one
exact HTTPS `browser_origin`, and `expires_at_ms` no more than one hour ahead.
Enter that exact private host expiry into the headset enrollment; a client must
not manufacture another hour. Host expiry is authoritative even if a client is
misconfigured: it rejects subsequent polls, replies and browser requests. Client
snapshots project expired at their configured deadline while an HTTP call is
pending; connected is transport state, not authenticated app readiness.
Use independently generated cryptographic random tokens. Configs, TLS keys,
enrollment keys and join credentials never belong in public source or logs.

All routes are under `/v1/relay/<session>/`. Credentials are exclusively in
`Authorization: Bearer …`; redirects are disabled in the Android client.

| Role | Method and suffix | Body/result |
| --- | --- | --- |
| Device | POST `poll` | `{identity: gateway.identity()}` → `{}` or `{id,request}` |
| Device | POST `reply` | `{id,response: gateway.exchange(request)}` → `{}` |
| Device | POST `revoke` | `{}` retires the entire scope |
| Browser | GET `identity` | Last device identity; unavailable after 15 seconds without polling |
| Browser | POST `exchange` | Original signed gateway envelope → original signed gateway response |

Browser Origin must exactly match the configured origin. Device requests must
have no Origin. CORS permits only that origin and Authorization/Content-Type.
The first authenticated device poll binds the configured device and gateway
epoch; an epoch change retires the scope. Non-authoritative identity hints are
not forwarded. Identity is discovery, not readiness. The browser verifies the
gateway response MAC, device, epoch, nonce, action and status freshness.

Transport bounds are 64 KiB bodies, eight outstanding requests, sixteen HTTP
workers, a twelve-second command wait, and one-hour maximum scope life enforced
with wall and monotonic clocks. Timed-out queued requests are never delivered.
Once delivered, missing replies report HTTP 504 `outcome_unknown`, never command
failure or permission to replay. Original pending/rejected/confirmed/unknown
receipts retain their meaning. The shared gateway supplies replay protection.
Revocation wakes waiters, rejects subsequent traffic and clears pending work;
it cannot undo a command the app has already accepted.

The Android client uses platform TLS trust and hostname verification with no
trust-all path. It runs one bounded outbound polling thread and closes the live
connection when its gateway lifecycle ends. Relay transport is trusted TLS,
not end-to-end encryption: the relay can see payloads, but gateway MACs prevent
it from creating authorized commands or altering authenticated replies.

## Local rehearsal and hosting proposal

Run state tests without opening a port:

```text
python tools/test_experiment_control_relay.py
```

After reserving the selected local port, run the actual TLS test, optionally
including the production Java relay client and production gateway protocol:

```text
python tools/test_experiment_control_relay.py --tls-port 19443 --java-home <jdk> --json-jar <org-json-jar>
```

The runner generates a temporary certificate and private scope, explicitly trusts
that certificate only in its test Python context/JVM trust store, verifies an
untrusted certificate fails, exercises CORS/auth/revocation, and closes its
listener. The Java harness executes signed status, command and receipt messages
through the actual client/protocol with a modeled owner. It is not Android,
actual experiment execution, APK or device evidence. The standalone service is:

```text
python tools/experiment_control_relay.py --config <private-scope.json> --cert <certificate.pem> --key <private-key.pem> --bind 127.0.0.1 --port 19443
```

No public relay is deployed by these tests. The proposed service origin is
`https://control-relay.mesmerprism.com` and the exact browser origin is
`https://mesmerprism.com`; the proposal is not evidence that the relay DNS,
certificate, host or service exists. A deployable first slice needs an
operator-selected hostname, TLS certificate accepted by Android and browsers,
a host able to run this bounded Python process, firewall/reverse-proxy policy,
and an exact public-page Origin. A reverse proxy must preserve body/worker/time
bounds, forbid caching, and avoid logging Authorization or message bodies.
Do not expose this rehearsal process as a multi-tenant service without adding
operator admission, monitoring and service lifecycle controls. Select the
hostname into the APK's approved origin and distribute a fresh private scope to
the headset and its browser enrollment. Device and browser route tokens remain
distinct; neither belongs in the public website build. Remote TLS and expiry,
revocation, reconnection and receipt behavior require a separate deployment test.

For an operator-managed Linux host, a concrete deployment shape is a dedicated
unprivileged `rq-relay` systemd service, the Python source under
`/opt/rustyquest`, a unique private scope file under `/var/lib/rq-relay`, and a
TLS reverse proxy on port 443. These are proposed deployment paths, not local
machine locations or installed resources. Provision DNS to the selected host
and obtain a certificate for `control-relay.mesmerprism.com` using the operator's
existing ACME/certificate process. Protect the scope and relay TLS private key
with owner-only access; give the proxy its own certificate access. Example unit:

```ini
[Unit]
Description=One bounded Rusty Quest controller relay scope
After=network-online.target

[Service]
Type=simple
User=rq-relay
Group=rq-relay
UMask=0077
ExecStart=/usr/bin/python3 /opt/rustyquest/tools/experiment_control_relay.py --config /var/lib/rq-relay/scope-unique.json --cert /var/lib/rq-relay/tls/fullchain.pem --key /var/lib/rq-relay/tls/privkey.pem --bind 127.0.0.1 --port 19443
Restart=no
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/rq-relay
TasksMax=32
MemoryMax=256M
```

The CLI atomically creates and flushes a sibling `.used` marker before listening.
Never delete that marker to restart an old scope. A crash, stop, revoke or
startup failure requires a fresh config filename, session and both tokens;
automatic restart is deliberately disabled. This prevents a revoked in-memory
scope from being resurrected by reusing its configuration. The marker and scope
file must remain together. In-process imported test fixtures do not persist
markers because their lifetime is the bounded test itself.

An illustrative nginx virtual host can proxy the existing TLS relay without
disabling upstream verification:

```nginx
server {
    listen 443 ssl;
    server_name control-relay.mesmerprism.com;
    ssl_certificate /etc/nginx/tls/relay/fullchain.pem;
    ssl_certificate_key /etc/nginx/tls/relay/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    client_max_body_size 64k;
    client_body_timeout 15s;
    access_log off;
    location /v1/relay/ {
        proxy_pass https://127.0.0.1:19443;
        proxy_ssl_server_name on;
        proxy_ssl_name control-relay.mesmerprism.com;
        proxy_ssl_verify on;
        proxy_ssl_trusted_certificate /etc/ssl/certs/ca-certificates.crt;
        proxy_read_timeout 15s;
        proxy_send_timeout 15s;
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_cache off;
    }
    location / { return 404; }
}
```

Validate the proxy config before enabling it, expose only TLS port 443, and keep
19443 loopback-only. The relay itself enforces worker/body/queue limits and exact
Origin authentication. Configure sanitized service/proxy diagnostics without
Authorization, request bodies or private enrollment fields. A device-authenticated
`POST revoke` retires the running scope; stopping the service also closes it.
Certificate renewal and any service restart require an explicitly provisioned
fresh scope. This sample unit/proxy configuration is a hosting proposal, not a
tested deployment recipe or an executed system change.

## Direct/offline behavior and limits

Local headset controls request `hotspot` or `wifi_direct`. Hotspot creation uses
`WifiManager.startLocalOnlyHotspot` and retains the returned reservation. Wi-Fi
Direct checks for an existing group, refuses to adopt/remove somebody else's
group, calls `createGroup`, and waits for group and connection readback before
reporting active. It rereads the group while active. Cleanup closes its own
hotspot reservation or removes its verified owned group and checks absence.
Unresolved platform callbacks remain stopping/cleanup-unknown instead of success.

SSID and passphrase are exposed only through the headset-local join-details
method; generic status contains no credentials. Join the network in the phone's
OS Wi-Fi settings, then use the same LAN gateway and enrolled controller. A
browser cannot create or join Android Wi-Fi Direct on the headset by itself.
Network-active does not assert browser-control-ready. No gateway IP is guessed
when Android does not expose it or returns an anonymized address. A cached HTTPS
page also remains subject to browser local-network and mixed-content policy;
physical phone/browser verification is required for the selected LAN bearer.

Actual Quest 2 hotspot/Wi-Fi Direct capability, attended permission dialogs,
phone joining, offline browser access, immersive lifetime and radio cleanup are
not established by source compilation or the relay harness. Android documents
the relevant [local-only hotspot permissions and lifecycle](https://developer.android.com/develop/connectivity/wifi/localonlyhotspot)
and [Wi-Fi Direct creation/readback APIs](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager).

## Existing source assessed

Quest revision `fbbd3cb6` adds an inert, read-only local
`ExperimentSessionStatusPublisher`; it does not provide an enrolled Internet
endpoint or command transport. The existing `q2q_peer_stereo_tls_relay.py` is a
raw binary TLS media relay; browser HTTP commands cannot use its framing. The
existing direct-P2P product demonstrates Android topology APIs but is a separate
app and is not installed by this route. No compatibility/legacy camera authority
or laboratory shell-UID permission bypass is adopted here.
