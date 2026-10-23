# Embedded duplex operation diagnostics

Diagnostics follow the actual operation through the operator, debug provider,
Java owner, JNI bridge, media runtime and signed owner-dispatch verifier. They
are observations only. They never grant authority, attest an effect, authorize
a retry or acknowledge an UNKNOWN Original.

## Public observations

The additive `rusty.quest.failure_observation.v1` envelope names a precise
source-controlled boundary even when its cause is unfamiliar. Its correlation
contains validated identities or nulls. The safe cause, effect knowledge and
cleanup knowledge remain separate. Arbitrary exception messages, signed frames,
client identities, paths, signatures, tickets and keys do not enter public
observations.

`rusty.quest.operation_diagnostics.v1` retains the first failure and at most four
later observations for that operation. Later compensation, cleanup, recovery and
transport failures cannot overwrite the primary. A bounded collection retains
at most 32 operation summaries and reports dropped observations. Success does
not manufacture a failure. Collection errors do not replace the original result.

Native owners are long lived. A thread-local diagnostic scope is entered at the
existing Java Original callback boundary and restored when that boundary exits.
Nested callbacks for the same retained Original share its diagnostic handle.
An unrelated incoming thread cannot inherit another thread's Original. Missing
correlation remains null; an explicitly unattributed callback suspends and then
restores the outer scope. Snapshot storage owns plain data and does not retain
an Activity, HostLease, live capability or authority object.

Original/outcome hashes, signed owner frames and lifecycle acceptance schemas
remain unchanged. The diagnostic collection is a separate read.

## Private failure context

Failure producers can retain exact signed source request and target response
frames, tickets, returned projections, enrolled **public** verification keys and
bounded exception detail. Private signing keys and seed material are excluded.
The source selects these chunks; this is not a general memory or filesystem dump.

The native private envelope uses
`rusty.quest.private_failure_context.v1`. Its chunks declare their name, hex
encoding, original byte count and truncation. Untruncated hex decodes to the exact
production input. Truncated data is explicitly incomplete and cannot be used as
an exact verifier replay. Native chunks retain at most 128 KiB each, with at most
1 MiB encoded context per operation. The app-private store retains at most 4 MiB.

The Java application owns the fixed no-backup directory
`embedded-duplex-diagnostics`. Context file names are opaque 64-character lowercase
hex IDs, followed by `.json`. There is no caller-selected path. Public receipts
contain only the context availability, opaque ID, actual stored byte count and
SHA-256. Retention rolls by confirmed fixed-file eviction; it does not silently
exhaust a process-lifetime byte counter. Private storage is best effort. Disabled, unavailable, truncated or
evicted data must remain distinguishable from available complete evidence.

## Retrieval

The debug-only `operation_diagnostics` provider read accepts its normal challenge
and an optional validated Original ID. It returns the standard outer receipt and
`rusty.quest.embedded_duplex.operation_diagnostics.v1`, containing separate Java
and native collections. It does not construct a runtime, acquire a capability,
invoke Start/Stop, repeat an Original or require an unretired host. Release
provider closure remains unchanged.

The merged response is limited to 64 Ki characters and 128 KiB UTF-8, below the
Android transport limit. Read projection can omit older operations and counts
them in the collection's dropped count. It preserves primary and latest cleanup
observations within each retained operation and does not modify stored evidence.

An Original selector is applied before this transport bound, so its available
observation is preferred over unrelated history.

For an authorized private investigation, retrieve a referenced fixed app-private
context file with the existing bounded `run-as` capture owner. Validate the
opaque ID, byte count and SHA-256 against its descriptor. Do not publish raw
context or treat it as signed lifecycle evidence. Capture useful context before
process replacement or app-data cleanup; an unavailable context is a replay
limitation, not permission to reconstruct missing signed bytes.

The authenticated host `GET /operator/diagnostics` serves cached reports. GET
performs no device I/O. Explicit collection belongs to the existing worker and
capture owner, and its helper is an explicit held source role. Host error output
links the validated Original, boundary, safe cause and bounded evidence refs.

## Failure boundaries and verification

| Boundary | Preserved observation |
| --- | --- |
| Provider request validation and transport | Actual rejection/timeout/cancel boundary and honest effect uncertainty |
| Original dispatch, native Pending and publication | Original correlation, first cause, and separate persistence/join failure |
| JNI callback and attachment | Exact source-controlled call boundary, private error/Throwable detail, unchanged JNI result semantics |
| Media Start, uncertain compensation and Stop | Provider cause before generic conversion, separate compensation/cleanup phase |
| Current projection and route configuration | Actual selected authority inputs and returned projection where available |
| Remote encode/exchange/decode/binding/signature/proof | Exact bounded failed request/response and public verifier key for offline replay |
| Target registry readback and one-shot consumption | Distinct rejection substep while retaining existing null/success behavior |
| Retained cleanup and whole-app Close | Failure context separate from physical terminal evidence |
| Recovery, capture, child runner and build | Original first failure plus child completion/stream references; all issued peer failures retained |

Focused host controls execute production collector, verifier, registry and
Original propagation functions. They cover first-primary retention, separate
cleanup failures, unfamiliar causes, bounds, privacy, disabled/failed collection,
unchanged success/uncertainty, and retrieval after owner retirement. The native
diagnostic module also has a direct Android JNI typecheck. These checks do not
claim actual device callback execution, camera streaming or terminal cleanup.
Hardware acceptance requires the ordinary producer evidence for the adopted
actor and artifact.
