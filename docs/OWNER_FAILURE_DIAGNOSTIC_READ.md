# Owner failure diagnostic read

The debug-only `owner_failure_diagnostic` operator call accepts only its shell challenge. It reads the retained Java owner diagnostic once through the live native host callback. Process/app/native generations remain joined before and after the read. It invokes no Peer lifecycle operation and changes no authority, owner effect, or cleanup state.

The optional report has schema `rusty.quest.embedded_duplex.owner_failure_diagnostic_read.v1` and `qualification_claimed=false`. Its native observation reports the existing strict parser outcome and a finite error category. `closed_java_fields` contains only the seven known diagnostic keys with individually declared enum values; a rejected combination may be shown for diagnosis. Unknown keys, unknown values, invalid JSON, and over-256-byte text produce no field projection. Callback failures produce an unavailable report without exporting exception text. These fields are diagnostic only and cannot replace strict lifecycle, cleanup, or readiness evidence.

Existing Peer receipts and strict diagnostic parsing remain unchanged. The route is absent from earlier installed builds. No current device cause, Android behavior, or streaming qualification is established by host controls.

The Peer receipt's separate `native_owner_dispatch_failure` retains the first native failure. Ordinary dispatch recognizes a closed set of exact errors for remote uncertainty/rejection, verified-effect/readback mismatch, runtime-slot failure, and Java callback/attachment failure. Unknown errors keep `RETAINED_ORDINARY_CALLBACK`; no exception text or payload is exported. Later abort or cleanup failure cannot replace this first observation. These codes remain diagnostic, never proof of completion or physical cleanup.
