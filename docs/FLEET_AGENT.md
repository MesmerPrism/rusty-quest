# Rusty Fleet Agent

## Decision

Rusty Quest provides one explicit, permission-minimal producer for Rusty Fleet
local monitoring. The source contract lives in
`crates/rusty-quest-fleet-agent`; Android packaging and platform observations
live in `apps/fleet-agent-android`.

The producer creates proposals. It does not accept Manifold peer state, Fleet
device state, enrollment, commands, or capabilities.

## Key-record helper release capsule

`fleet-agent-key-record` derives one public enrollment record from an
operator-owned private seed file. The existing
`Build-FleetAgentKeyRecord.ps1` output remains machine-bound developer evidence
for source and device tests. Its manifest contains local paths and is never a
supported distribution input.

`Build-FleetAgentKeyRecordRelease.ps1` is the separate owner release route. It
requires an exact clean Rusty Quest source tree, resolves the closed dependency
composition, and clones each exact Quest, Fleet, and Manifold commit/tree into
an isolated no-hardlink clean room. Cargo uses only that materialized Quest and
Manifold source plus a fresh checkout of the materialized Fleet dependency,
locked dependencies, a dedicated Cargo home/target, an explicit Windows x64
target, cleared ambient Cargo/Rust profile overrides, stable path remapping, and
stripped symbols. The builder revalidates all
three Git identities, clean states, and the Cargo composition after compilation
before it emits exactly:

- `release-manifest.json` using
  `rusty.quest.fleet_agent_key_record_release_capsule.v1`;
- `provenance.json` with public repository URLs, exact commits/trees, the
  closed dependency set, exact source-file hashes, and build identity;
- `fleet-agent-key-record.exe`, `LICENSE`, `SOURCE-NOTICE.md`, and
  `checksums.sha256`.

The only supported capsule version is `1.0.0`; both builder and validator reject
every other value. Consumers must pin the owner identity, manifest
SHA-256, helper SHA-256, exact source commit/tree, version, target, and payload
set separately and preserve the owner bytes without augmentation. The owner
validator rejects artifact or provenance substitution, unknown fields, extra
repositories or files, stale/unsupported versions or targets, and detectable
private or machine-local material. It inspects both ASCII and both byte
alignments of little- and big-endian UTF-16 in the executable, so absolute host
paths are release-blocking even when ordinary manifest text is clean.

Rusty Quest has no release-signing or revocation authority for this helper
capsule. The current contract therefore makes no signature claim: ownership is
bound by the exact public repository identity, clean Git commit/tree, closed
provenance, and downstream-pinned SHA-256 values. A downstream signed bundle
may authenticate its own packaging, but must not relabel that signature as a
Rusty Quest enrollment or capsule-revocation decision.

Capsule validity proves packaging and helper provenance only. It does not
enroll or activate a device, prove reachability, issue a lease, accept a peer,
or authorize a Fleet/Manifold transition. Manifold remains the live enrollment
and peer authority. No capsule contains a seed, profile, Hub configuration, or
private inventory.

Build and validate after committing the source:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-FleetAgentKeyRecordRelease.ps1 -CapsuleVersion 1.0.0
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-FleetAgentKeyRecordRelease.ps1 -CapsuleRoot <owner-capsule>
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-FleetAgentKeyRecordReleaseSelfTest.ps1 -CapsuleRoot <owner-capsule>
```

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
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-FleetAgentAndroid.ps1 -Tier Host
```

The host gate also checks the release builder, validator, schema, and fixture
matrix statically. The release builder's clean-source gate and the exact
capsule validator run after the source commit exists; damaged-capsule self-tests
run against those generated owner bytes. The negative matrix repairs linked
hash/checksum fields around semantic mutations so version, repository closure,
and private-path rejection are tested rather than passing through an unrelated
digest mismatch.

The public golden claims fixture under `fixtures/fleet-agent/` must reproduce
Fleet's signing message and signature exactly. `-Tier Host` is the explicit
source/static owner gate and rejects unknown tier names; add `-Build` only
when the package build is required. Device checks remain separate and route
through the serial-scoped smoke wrapper once the package slice is active.

Host validation proves contract and packaging shape. A later device gate must
prove real battery/charging readback, opt-in activation, accepted Hub ingress,
stale/offline behavior after stop, clean service termination, zero package or
system fatals, and package cleanup. Device evidence remains outside the public
repository.

The active physical-device gate is
`tools/Invoke-FleetAgentTwoQuestSmoke.ps1`. It requires exactly two distinct
serials, the validated content-addressed run capsule, two distinct private
profiles and seeds, the source-bound key-record helper manifest, an
already-running Fleet-owned Hub, and a new evidence directory outside this
repository. Build the helper before device execution with
`tools/Build-FleetAgentKeyRecord.ps1`. The caller, not the script, owns the
exact Agent Board headset and listener reservations.

The gate first proves ordinary launch is inert. It then requires both enrolled
devices to become fresh through accepted signed check-ins, compares the
projected power fields with Android battery authority, and verifies that
participating-application foreground state remains `unknown` with
`platform_limited` authority. It stops the first producer and requires
fresh → stale → offline while the second producer remains fresh, then proves
the same aging and clean stop for the second producer. Bounded log and crash
buffers must contain zero Fleet Agent fatals.

The gate refuses to replace a pre-installed Fleet Agent package. In `finally`
it stops only the target package, removes the exact app-private test inputs,
uninstalls the package installed by the run, and verifies that package and
process absence match the observed pre-run state. It never restarts ADB,
changes device settings, creates a forward/reverse route, or accepts an
implicit device.
