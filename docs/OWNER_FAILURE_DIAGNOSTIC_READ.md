# Owner failure diagnostic read

The debug-only `owner_failure_diagnostic` operator call accepts only its shell challenge. It reads the retained Java owner diagnostic once through the live native host callback. Process/app/native generations remain joined before and after the read. It invokes no Peer lifecycle operation and changes no authority, owner effect, or cleanup state.

The optional report has schema `rusty.quest.embedded_duplex.owner_failure_diagnostic_read.v1` and `qualification_claimed=false`. Its native observation reports the existing strict parser outcome and a finite error category. `closed_java_fields` contains only the seven known diagnostic keys with individually declared enum values; a rejected combination may be shown for diagnosis. Unknown keys, unknown values, invalid JSON, and over-256-byte text produce no field projection. Callback failures produce an unavailable report without exporting exception text. These fields are diagnostic only and cannot replace strict lifecycle, cleanup, or readiness evidence.

Existing Peer receipts and strict diagnostic parsing remain unchanged. The route is absent from earlier installed builds. No current device cause, Android behavior, or streaming qualification is established by host controls.

The native observation also includes a separate `latest_attempt` report with
schema `rusty.quest.embedded_duplex.owner_latest_attempt_native_read.v1` and
`qualification_claimed=false`. It observes the most recently begun ordinary or
retained-cleanup Java owner callback, while the original first-failure fields
remain unchanged. Its eight fields are an integer `attempt_sequence`, closed
`outcome` (`NONE`, `IN_PROGRESS`, `SUCCEEDED`, `FAILED`, or `UNAVAILABLE`),
`compensate` boolean, and closed stage/action/owner/cause/provider-reason values.
The counter is process-local, monotonic and bounded at 2^53-1; exhaustion clears
the observation to `UNAVAILABLE` without denying effects. An older overlapping
attempt cannot overwrite a newer attempt. Reads retain the existing shell,
challenge, process, configuration and native-generation guards.

The latest parser rejects unknown or duplicate keys, wrong types, oversized
text, unknown values and inconsistent combinations without projecting payload
or exception text. `SUCCEEDED` means this Java callback returned verified
registry readback. It does not prove remote signed effect validation, Broker
acceptance, terminal lifecycle, or physical cleanup. A rejected remote effect
and invalid remote evidence remain indistinguishable through this Java-only
observation when the Java callback itself completed; no new authority or remote
response handling is introduced. Host controls exercise actual Platform
callbacks with modeled physical providers and both production Rust parsers;
candidate APK and device evidence remain separate requirements.

The Peer receipt's separate `native_owner_dispatch_failure` retains the first native failure. Ordinary dispatch recognizes a closed set of exact errors for remote uncertainty/rejection, verified-effect/readback mismatch, runtime-slot failure, and Java callback/attachment failure. Unknown errors keep `RETAINED_ORDINARY_CALLBACK`; no exception text or payload is exported. Later abort or cleanup failure cannot replace this first observation. These codes remain diagnostic, never proof of completion or physical cleanup.
