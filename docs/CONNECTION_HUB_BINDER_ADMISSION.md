# Connection Hub Binder Admission Sessions

Connection Hub provider clients use one serialized session supervisor for the
Android Binder/Messenger lifecycle. The supervisor is a deterministic reducer;
Android callbacks, replies, deaths, deadlines, and owner lifecycle events are
inputs, while bind, send, unlink, unregister, and unbind operations are explicit
effects. This keeps reentrant Binder callbacks and late messages from mutating
session state directly.

This contract covers admission-session transport and reconciliation only.
Android derives caller identity, Manifold decides grants and authorization, the
provider owns application effects, and the WebSocket layer remains a controller
transport. A Binder send, Messenger reply, WebSocket acknowledgement, or command
dispatch is not proof of admission or application effect.

## Identity and grant closure

The broker derives the immediate caller from `Message.sendingUid`, resolves one
Android package for that UID, and hashes exactly one APK signing certificate.
The request cannot supply a client id, package, signer, grant, or capability.
The packaged authority must contain exactly one grant for that OS-derived
package-and-signer subject. Zero matches follow the normal identity-mismatch
route; multiple matches reject as an ambiguous platform subject before token
issue or runtime-config publication.

## Generations and correlation

Every client process owns a process generation. Each bind attempt advances a
binding generation, and each connected broker relationship advances a session
generation. Broker initialization exposes an epoch that changes after broker
process replacement. The supervisor fences callbacks and replies with these
values so a late connection, death, reply, or timeout from an earlier lifecycle
cannot advance the current session.

Logical operations have stable operation ids. Each transmission has a distinct
attempt and correlation id. Provider registration additionally has a stable
registration id and a digest of the exact canonical registration JSON. Requests,
broker progress markers, and replies carry the correlation and session
generation; replies also carry the broker epoch.

The observable progress chain is:

```text
bind-requested -> connected -> death-linked -> request-sent
-> broker-dequeue -> identity-derived -> authority-returned
-> reply-enqueued -> client-applied
```

Markers contain only bounded identifiers, generations, stage, sanitized package
identity, UID, last-positive stage, and terminal reason. They never include
tokens, signing certificates, private state, playlist contents, or command
payloads.

## Admission and registration sequence

The client performs these steps in order:

1. Bind the explicit signature-protected admission service.
2. Link Binder death before sending authority work.
3. Submit runtime evidence using OS-derived identity.
4. Request a short-lived capability token.
5. Authorize one exact capability use.
6. Register one bounded surface using the retained authorized use.

Registration binds the current provider session, a stable registration id, and
the SHA-256 of the exact registration document. Repeating the same id, digest,
surface, and callback Binder is equivalent and returns the cached applied
result. Reusing the id with different bytes, surface, callback, or session
rejects. This makes a lost registration reply recoverable without duplicating
the provider or widening authority.

## Retry and ambiguity rules

- Read-only runtime evidence may retry within a small fixed attempt limit.
- Equivalent registration may retry with the same registration id and exact
  bytes within a small fixed attempt limit.
- Token issue and token use do not retry after an ambiguous timeout. The client
  closes the session and establishes a new generation instead of risking a
  duplicated authority operation.
- Desired-state commands such as pause or resume may reconcile against a
  revisioned effective state.
- Relative commands such as previous or next never replay blindly. When the
  owner-confirmed result is unavailable, report `outcome_unknown` and require
  state reconciliation.

Every generation records one terminal reason and performs cleanup exactly once.
Null binding, binding death, Binder death, disconnect, lifecycle stop, timeout,
and explicit close all converge through the reducer. Cleanup cancels deadlines,
unregisters when applicable, unlinks death, and unbinds without allowing a late
callback to reopen the retired generation.

## Host acceptance

Before emulator or device work, host tests must cover:

- bind false/exception, null binding, disconnect, binding death, Binder death,
  lifecycle stop, and stalled replies;
- stale callbacks, replies, broker epochs, and deadlines across generations;
- evidence retry limits, ambiguous token issue/use, equivalent registration,
  conflicting registration, and cleanup exactly once;
- OS-derived package/signer projection, zero/duplicate grant rejection, and
  capability-escalation rejection;
- desired-state reconciliation and `outcome_unknown` for ambiguous relative
  effects;
- correlated, bounded, payload-free progress markers.

`tools/checks/Test-ConnectionHubAndroid.ps1` compiles and runs the shared reducer
fixtures, validates the broker/client projections, and keeps the Connection Hub
protocol, transport, Java, Rust, and browser host gates together.
