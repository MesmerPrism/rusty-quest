# Rusty Connection Hub

The closed Quest operator and autonomous debug E2E contract are documented in
[`CONNECTION_HUB_OPERATOR.md`](CONNECTION_HUB_OPERATOR.md).

## Decision

The standalone `apps/manifold-broker-android` product is the native 2D Rusty
Connection Hub when its exact product lock selects `connection_hub`. Its
visible Activity manages one explicit user-started Android foreground service.
That service, rather than a provider app or control page, owns desired listener
state, DNS-SD, browser transport, reconnect epochs, and notification.
Provider Activities may appear and vanish without stopping the connection.

This is an additive surface over the existing Broker product, not a second
broker or decision authority. Non-Hub product locks retain the existing Broker
label, assets, Activity behavior, and loopback `/manifold/v1/events` route.
Manifold retains trust, logical-session, provider admission, surface,
derivative-lease, transport-epoch, replay, expiry, command, revoke, and cleanup
decisions. Android owns OS identity evidence, Keystore persistence, foreground
service/network APIs, low-rate provider dispatch, and platform receipts.

## Security posture

The listener is disabled by default and starts only from the visible headset
Activity. The current remote transport is paired plain HTTP/WebSocket for an
explicit trusted-LAN experiment:

- `transport_classification=trusted_lan_experimental`
- `confidentiality=none`
- `production_eligible=false`

Pairing authenticates a controller; it does not encrypt traffic. These facts
are visible in the Activity, notification, DNS-SD, HTTP headers, status, and
WebSocket receipts. WSS with device-certificate pinning is a later production
gate. Loopback or ADB forwarding can prove protocol behavior, never LAN
confidentiality.

Pairing codes are process-memory wearer evidence and are never returned by the
status API or persisted. Controller cookies are 256-bit random transport
credentials. Durable state is AES-GCM encrypted with a non-exportable Android
Keystore key. Android persists the Manifold state envelope byte-for-byte and
does not interpret it. Logical Manifold session identity is distinct from a
replaceable numeric transport epoch and the random listener-instance ID.

## Provider boundary

`ManifoldAdmissionService` is the single exported signature-protected Binder
service. Android derives the immediate Binder UID, unambiguous package, and
single APK signing-certificate SHA-256. A provider cannot supply or substitute
those fields. A provider first consumes an admission use accepted by the
retained Manifold admission authority; registration refers only to the
Broker-retained use and never accepts caller-supplied admission evidence.

One `rusty.quest.connection_hub.surface_registration.v1` registration has one
token `surface_id`, label, description, at most 32 closed command descriptors,
and a scalar-only state object of at most 4096 UTF-8 bytes and 16 keys. It has
no component, action, URI, flags, raw path, URL, shell command, arbitrary
Intent extras, uploaded UI, or high-rate payload.

At most 32 surfaces are live. Provider Binder death or explicit Activity stop
removes only that provider's surfaces, never the Hub. Spatial Video Control
registers `surface.spatial_video_control.media` on `onStart` and unregisters on
`onStop`; its existing `trusted_local_http_v1` foreground route is unchanged.

## Browser protocol

Fixed same-origin assets are packaged under `assets/connection-hub`; there are
no remote scripts, uploads, CORS, arbitrary URL fetches, or runtime HTML.

- `GET /`, `/assets/app.js`, `/assets/styles.css`
- `GET /v1/status`
- `POST /v1/pair`
- `POST /v1/revoke`
- WebSocket `GET /v1/socket?session=<opaque>`

Server messages are exactly `surface_snapshot`, `surface_available`,
`surface_removed`, `surface_state`, and `command_receipt`. Clients send only
`rusty.quest.connection_hub.surface_command.v1` `surface.command` messages
with request, surface, registered command, and bounded scalar arguments. A
command receipt distinguishes Manifold authorization from a provider's later
effective state. `proves_application_effect=false` is preserved until the
provider publishes a separately observed state.

## Lifecycle

1. Installing or launching a provider does not start the listener.
2. The wearer opens Connection Hub and selects **Start paired connection**.
3. The foreground service starts the fixed listener and DNS-SD advertisement.
4. A controller submits the one-time code and public controller identity hash;
   Manifold decides trust and opens a durable logical session.
5. Physical reconnect advances the numeric Manifold transport epoch while the
   logical session and valid derivative surface leases remain.
6. Provider start/register and stop/unregister add/remove surfaces while the
   connection remains active.
7. **Stop** closes the listener but preserves trust. **Forget** delegates
   revocation/cleanup to Manifold and removes encrypted projections.

## Validation

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-ConnectionHubAndroid.ps1 -RepoRoot .
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-ManifoldBrokerAndroid.ps1
```

The JVM suite covers provider lifecycle independence, restart transport
replacement, identity substitution, replay, revoke, and bounds. The source
gate checks Keystore encryption, signature Binder routing, fixed assets,
security labelling, and Spatial lifecycle. Product builds must package the
real Rust authority adapter; tests may use an explicitly named fake.

Device acceptance separately requires an exact Hub product lock, shared signer
for Broker/providers, wearer pairing, app-to-app surface add/remove, reconnect
epoch replacement, replay/revoke negatives, effective app state, zero bounded
fatals, and exact cleanup. Host tests claim no device acceptance.
