# Browser BLE lifetime helpers

This Quest-owned static browser/CommonJS package contains no dependencies.
Load `gatt-lifetime.js` before the host script, then use
`QuestBleLifetime.OperationQueue` with the host's generation/lifetime guard.
`run(generation, task)` serializes GATT work and checks the guard before
dispatch and after completion. Retirement rejects queued operations and
suppresses late readbacks. A new connection should create its own queue;
an unresolved old platform call must not block the next connection.

The caller supplies its actual connection generation, monotonic expiry where
applicable, and retirement handler. This module opens no chooser, radio,
listener or service and imposes no app foreground policy.

`commandReceipt(receipt, requestId)` is the RQEC1 v1 compatibility-profile
join: unmatched revisions/request IDs are ignored, unknown states fail,
and only the app's matching `confirmed` receipt has `applied=true`.
An accepted GATT write or an `accepted`/`pending` receipt proves no app effect.
The Hub profile retains its existing Manifold/Quest joined wire receipts;
it uses the operation queue, not the RQEC1 receipt vocabulary.

Use `npm pack` for the dependency-free static artifact. Website consumers may
vendor the exact file with an immutable producer commit and byte hash;
record that copy in their source manifest and validate it before deployment.
Never fork transport/authority behavior in a website copy. Protocol UUIDs,
authentication inputs, command registries and app action semantics are unchanged.

Run `npm test`. These target-free checks exercise queue retirement and receipt
joins; they establish no radio, pairing, authority grant or application effect.
