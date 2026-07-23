# Rusty Fleet Agent

## Decision

Rusty Quest provides one explicit, permission-minimal producer for Rusty Fleet
local monitoring. The source contract lives in
`crates/rusty-quest-fleet-agent`; Android packaging and platform observations
live in `apps/fleet-agent-android`.

The producer creates proposals. It does not accept Manifold peer state, Fleet
device state, enrollment, commands, or capabilities.

## Baseline boundary

The first profile reports only low-rate facts that Quest owns:

- enrolled device identity supplied by app-private configuration;
- Fleet Agent lifecycle supplied by the Fleet Agent itself;
- battery percentage and charging state supplied by Android;
- participating-application foreground, lifecycle, kiosk, and control
  readiness only when that application explicitly supplies the evidence.

Android does not offer a permission-free, authoritative view of arbitrary
foreground packages suitable for this baseline. When no participating
application supplies evidence, the application fact remains `unknown` with
`platform_limited` authority. The adapter must not infer foreground state from
network traffic, activity guesses, ADB, package lists, or stale values.

The baseline does not declare or request:

- ADB or File Manager access;
- package inventory or usage-stats access;
- accessibility services;
- broad storage access;
- camera, microphone, spatial, or media capture;
- BLE, Wi-Fi Direct, discovery, or ambient listeners;
- kiosk/device-owner privileges;
- a command listener.

Loss or absence of any future optional family must not remove monitoring.

## Activation

`rusty.quest.fleet_agent_profile.v1` is inert unless `enabled=true`. An active
profile names:

- the exact Fleet/Manifold device id and identity revision;
- the initial Manifold authority-revision hint, which the trusted Fleet
  ingress rebinds to current fleet-global state before review;
- the monotonic per-peer status revision;
- the producer epoch and monotonic per-epoch source revision;
- an app-private Ed25519 key id and enrolled public-key fingerprint;
- an explicit Hub check-in endpoint;
- bounded check-in interval and TTL;
- operator-safe display metadata and tags.

There is no discovery fallback. The profile is an adapter input, not enrollment
evidence. Fleet and Manifold still require the matching current enrollment and
trust records.

## Wire and authority contract

The Quest crate pins `fleet-contracts` to published Fleet commit
`8181683be4a3abbc5daa0c4497c7aeb9e76316a8`. It uses the exact Manifold
peer-status types from the sibling Manifold source.

Each envelope contains:

1. a Manifold low-rate peer-status proposal;
2. a provenance-bearing Fleet device observation;
3. a source issue time and bounded expiry;
4. a zero receive time, because the Hub owns receive time;
5. an Ed25519 signature over the Fleet v1 domain separator plus RFC 8785/JCS
   claims bytes.

The public-key fingerprint is derived from the signing key and must match the
profile before a check-in is produced. Private seed material remains
app-private and must never appear in a fixture, log, receipt, intent, command
line, or public repository.

## Epoch and revision behavior

Ordinary service restarts retain the app-private producer epoch and the next
source revision. An app update or change to the configured device identity,
identity revision, or key creates a new producer epoch and resets only the
per-epoch source revision. The independent per-peer Manifold status revision
continues monotonically across that change.

Devices do not serialize themselves against Manifold's fleet-global authority
revision. The signed proposal carries an initial revision hint; after signature
and enrollment verification, the trusted Fleet ingress binds that one
authority-owned optimistic-lock field to current state immediately before
Manifold review. Device-owned identity, proposal id, status revision,
timestamps, capabilities, and Fleet observation remain exactly signed.

Retry of the same logical check-in uses the same signed envelope and id; a new
observation uses new status and source revisions. Fleet and Manifold
independently reject replay, expired status, untrusted enrollment, identity
mismatch, or non-advancing revisions.

## Validation

The source-only edit loop is:

```powershell
cargo test -p rusty-quest-fleet-agent
cargo clippy -p rusty-quest-fleet-agent --all-targets -- -D warnings
```

The public golden claims fixture under `fixtures/fleet-agent/` must reproduce
Fleet's signing message and signature exactly. Android static, build, and
device checks are routed through `tools/Test-FleetAgentAndroid.ps1` and the
serial-scoped smoke wrapper once the package slice is active.

Host validation proves contract and packaging shape. A later device gate must
prove real battery/charging readback, opt-in activation, accepted Hub ingress,
stale/offline behavior after stop, clean service termination, zero package or
system fatals, and package cleanup. Device evidence remains outside the public
repository.
