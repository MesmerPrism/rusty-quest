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
`[]`. State contains thirteen typed scalars: schema, channel, epoch, sequence,
generation, revision, source_state, source_age_ms, phase, foreground, recording,
active_ms and completion. The existing closed observation producer supplies
these exact values; an unavailable status has null age and status fields. No
Boolean or numeric value is converted to a string. This representation fits
the Hub's scalar-only update contract (sixteen keys, 256 characters per string,
4096 UTF-8 bytes), unlike wrapping the observation JSON in one oversized string.
The application supplies the
same-lock NativeStatusSnapshot, selected channel and native runtime epoch to
the existing producer. Polling once per second does not synthesize native
readbacks or refresh sequence. Unavailable source publishes a null status
projection. Hub stores this data; it does not treat it as authority, native
freshness, application readiness or physical observation.

## Deliberate packaging and caller prerequisites

The public optional feature ui.experiment_session_hub_status_provider depends
on the breath composition panel. Feature off retains the established client
JSON specialization, has no Hub Java module or package query, and generates no
Hub caller. Feature on adds the exact three panel drivers and public admission
reducer to the hashed optional module. The compile list deduplicates the reducer
already present in the shared admission supplier. The exact broker package
query and signature admission permission are explicitly selected.

The generated breath panel shell creates a status lifetime only while resumed.
It waits for an actual same-lock coordinator native observation with positive
epoch, then starts one real Binder client. Pause/destroy, unavailable witness,
clock regression, local state invalidation, or epoch replacement closes that
lifetime. Neither a later poll nor a recovered witness reopens it. A subsequent
resumed panel gets a fresh lifetime and must again supply an eligible native
readback. No listener or application command is dispatched. Existing BLE status,
commands and navigation remain separate.

tools/Prepare-NativeRendererBrokerClient.ps1 -AppSpec <spec> -OutputRoot <new>
runs the real source-only resolver and writes one generated client lock using
the same specialization function as the APK builder. The optional feature adds
only provider-register capability and surface-registration contract family to
the existing media client. Its actual app/package/client/marker identity is
preserved; it creates no second subject. Output is packaged input, not a grant,
signer, token or runtime admission. Caller must select the private exact Java
allowlist and source binding separately; current installed candidates are not
changed by this source implementation.

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

## Bounded lifecycle diagnostics

The explicitly selected provider emits observation-only `hub-status` markers
under the existing `RQNativeRenderer` tag. A resumed panel reports at most eight
native callbacks (accepted boolean, numeric runtime epoch, and whether a real
status-read timestamp exists). Its Hub lifetime reports at most sixteen creation,
witness eligibility, factory/start, and closure markers. The provider and Android
adapter each cap their markers at thirty-two. Repeated unknown witnesses are
deduplicated. Ordinary packages without the selected lifetime emit none of these.

Reply markers follow existing generation/session/correlation/deadline fences.
`registration_applied` requires the reducer's actual positive registration reply;
bind accepted and start returned describe only those local operations. Arbitrary
reducer reasons map to a closed `reducer_marker_other` code. Raw JSON, subjects,
UIDs, tokens, correlations, secrets and exception messages are excluded. Logging
failures cannot alter admission, cleanup or retry behavior. Marker caps can leave
later events unreported; an empty log is not proof that a stage never occurred.

Initialization alone has no status-read timestamp. Idle generation zero is valid,
but eligibility still requires a genuine accepted status read and the coordinator's
exact epoch/state/monotonic witness. No marker substitutes for that witness, physical
XR readiness, live Binder evidence or authenticated browser status.
