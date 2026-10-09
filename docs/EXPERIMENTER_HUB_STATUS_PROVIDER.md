# Opt-in Experimenter Hub status provider component

This dormant source component binds the fixed Connection Hub admission service
only after an explicit main-looper `start()`. `close()` retires its own surface,
cancels publication/deadlines, and unbinds. It never enables a listener, sends an
application command, creates an issuer/grant, or launches an Activity. Binding
uses Android `BIND_AUTO_CREATE` for the existing admission service; this can
initialize that service but cannot opt into the Hub LAN listener.

`ExperimentSessionHubSurfaceClient` is the real Android Messenger/ServiceConnection
adapter. Android PackageManager supplies the exact installed broker UID; shared
UIDs are rejected and callback sending UID must match. Each binding has a fresh
Messenger and generation, death link and timer cleanup. The provider subject is
never serialized by this client: ConnectionHubAdmissionService derives the
calling UID/package/single signer and consumes its real retained admission use.
No broker signing certificate is invented by this component; broker product
selection/provenance remains the owner's prerequisite.

`ExperimentSessionHubProvider` drives the existing public admission reducer in
the order runtime evidence, issue token, authorize use, register surface. It
retains only genuine correlated owner responses. Late replies are fenced by the
actual uptime deadline even before a queued timer runs. Broker epoch changes,
wrong message kinds, stale session/binding/correlation, rejected grants and
invalid responses cannot start publication. The reducer's one equivalent
registration retry uses identical cached registration bytes and identity;
ambiguous completion stays nonregistered. It does not retry arbitrary commands.

The fixed surface is `surface.experimenter.status`, label `Experimenter status`,
description `Read-only application status observation; no physical readiness claim`.
Its exact Hub canonical digest is computed over `v1`, those three strings, and
their newline delimiters with no command records. Host controls compare the
digest to the actual HubSurfaceDescriptor implementation. `commands` is exactly
`[]`. The only state key is `experiment_status_observation`, a scalar containing
the existing closed <=1024-byte observation. The application supplies the
same-lock NativeStatusSnapshot, selected channel and native runtime epoch to
the existing producer. Polling once per second does not synthesize native
readbacks or refresh sequence. Unavailable source publishes a null status
projection. Hub stores this data; it does not treat it as authority, native
freshness, application readiness or physical observation.

## Deliberate packaging and caller prerequisites

This increment adds no current feature selection or panel lifecycle hookup.
Existing V9/private source selection stays unchanged. Before a future candidate
calls it, its owner must deliberately select an optional status-provider feature
and declare the exact Java source closure: ExperimentSessionHubSurfaceClient,
ExperimentSessionHubProvider, existing ExperimentSessionStatusObservation,
ExperimentSessionPanelCoordinator, ExperimentSessionPanelState,
ExperimentSessionPanelViewPolicy, and the public
`crates/rusty-quest-broker-admission/android/io/github/mesmerprism/rustyquest/broker_admission/ConnectionHubAdmissionSessionReducer.java`.
Update the private exact Java allowlist and source binding together; these new
files are not implicitly compiled by the current panel feature.

The broker product must separately select reviewed empty-command support in
both Quest and Manifold, and a canonical owner-packaged provider grant/client
lock with the actual new APK package/signer and exact surface digest, allowed
commands `[]`, provider-register admission capability, and existing controller
policy. This document supplies no token, client/provider identity, grant or
authenticated subscription. Older owners reject empty-command registration.

The caller must start only in its expressly selected lifetime and close when
that eligibility ends; no automatic reopen or background lifecycle is added.
Browser consumption requires a genuine authenticated Hub snapshot/event route
and matching selected surface/channel/epoch before admitting observation bytes.
LAN listening, plaintext security posture, wearer opt-in, same-origin assets,
and remote Internet relay remain separate existing owner decisions.

## Host qualification

`tools/checks/Test-ExperimentSessionHubProvider.ps1` accepts explicit existing
Android and org.json jars and a create-new output. It compiles/runs the actual
pure driver with the public reducer and Hub descriptor, and separately compiles
the real Android adapter against android.jar. Synthetic callback replies prove
protocol/lifetime behavior, not actual Binder credentials or owner issuance.
No test starts a listener, contacts a broker, builds an APK or touches a device.
