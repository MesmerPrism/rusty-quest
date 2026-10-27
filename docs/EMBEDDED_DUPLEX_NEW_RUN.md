# Same-package operational runs

NewRun separates an explicitly selected operational run from the app's persistent identity and settings. It is opt-in. The legacy no-run command routes remain available when no run is selected.

NewRun is a data/context selection action. Existing bootstrap, ARM, SDK preparation, Pair and ordinary Start remain separate commands. Selection does not install an APK, reset enrollment, create a signing identity, restore an authority snapshot or reconstruct a prepared SDK resource.

## Storage and ownership

The original application Context still owns `embedded-duplex-identity/ed25519-identity.v1`, private enrollment and ordinary profile/settings files. No Context wrapper redirects those stores.

An explicit run has an app-private root under `embedded-duplex-runs-v1/<run_id>`. Original rows, replay/recovery files, fresh/Pair/root/construction/prepared custody and run diagnostics use that root. Protocol archives are operational custody, not disposable diagnostic logs. Old rows and UNKNOWN outcomes remain unchanged and explicitly queryable; NewRun neither ACKs nor replays them.

One fixed package-wide Java writer inode and the distinct Native writer inode remain under the original `embedded-duplex-replay` directory. The Native permanent counter format stays unchanged. `app_process_fence.v2` adds the selected run ID to the global channel binding. Process-incarnation companions belong to their actual run. Switching a clear, eligible owner advances the app generation and makes old callbacks stale; it never rebinds an old companion to a new process.

The atomic selection names exactly one existing run and a monotonic selection generation. Repeating NewRun with the same ID observes the same selection. Reusing an older existing ID requires ResumeRun. A torn publication or selector/fence disagreement refuses rather than guessing another directory. ResumeRun selects the exact retained scope and keeps its recovery/expiry/UNKNOWN semantics; it supplies no writable authority from JSON.

## Typed app transport

The debug provider retains its existing shell-UID/DUMP boundary. All methods retain the existing 32-lowercase-hex challenge. Run IDs are app-generated or caller-selected opaque 32-lowercase-hex values, never filesystem paths.

| Method | Exact extra fields | Behavior |
| --- | --- | --- |
| `new_run` | `run_id`; optional `legacy_whole_original_id` | Create/select one new scope after current owner eligibility. The optional ID names one exact retained Whole Original in the outgoing scope. It is also usable for a current explicit-run release at a restart boundary. No receipt/path is accepted. |
| `resume_run` | `run_id` | Select that exact existing scope; no latest-run fallback. |
| `run_status` | none | Observe Java selection/app generation and actual Native binding/counter/eligibility. A Native run is null before actual admission, or still names its retained old owner. The Java label is never echoed as Native authority. |

In explicit mode every operational request, diagnostic, cleanup, Original query and ACK carries `run_id` in addition to its existing closed fields. The parser preserves the real operation, including Start's `prepare_preflight=true` choice. Run ID enters the canonical Original request digest. A stale A request cannot be admitted as a new B action, even if the Original ID is the same. Missing/wrong run refuses before operational dispatch. Enrollment status/review/confirm keep their persistent, unscoped contract.

Run-aware results use `rusty.quest.embedded_duplex.operator_transport.v3`; Original observations include the exact run ID. Existing nested lifecycle/Pair/runtime producer receipts retain their schemas. Read-only `operation_query` can name an inactive retained run without creating directories, rebinding an actor or making it writable. ACK still requires its actual existing current owner and joins; selection adds no ACK shortcut. Low-level qualification routes remain distinct.

The panel and Activity/Gate expose the same app-owned NewRun/ResumeRun actions. The run ID is retained across an uncertain result; changing it is explicit. Selection does not silently chain a cold bootstrap or Pair operation.

## Actual Native join and release

The strict Native bootstrap accepts `operational_run_id` only when it equals the live capability's captured run. It is checked before partial construction. Initialized/status projections read the admitted capability/Host, not the Java selection label.

Current pending effects, an entered request/callback, live or partial Native constructor, Host/route/root/SDK ownership, checkout/lease integrity failure, poisoned writer, or actual conflicting Own/camera/renderer/control resources refuse selection. Peer-only no-media closure retaining Own capture is not whole-camera release. A process exit, empty registry, clear bit, folder name or higher counter alone supplies no release evidence.

For the bounded restart adapter, Java reads exactly one canonical retained Whole Original and its `.joined.json`, validates request/outcome/actor/app/Native subjects and the final physical proof hash. It does not compare the final proof with the older checkpoint digest or inventory every old failure/ACK. A run marker may retain only that exact source locator and joined hash tuple for selection-crash retry. That metadata is not a serialized permit: the fixed held-channel callback re-reads and revalidates the retained source every time.

Native independently validates the closed source-owned handoff, full terminal Whole fields, target run, current channel digest and actual previous counter. Claim re-reads that counter under the existing writer exclusion. Counter advance, stale/missing/changed source, wrong run/channel or any current resource conflict refuses. A successful retry of the same actual pending binding returns its existing capability without allocating another counter. Completed old metadata is advanced only through this guarded transition; the singleton registries and permanent inodes are never reset or duplicated.

No named valid release/current closed owner means the existing cleanup/recovery owner is still required. In particular an ambiguous prior-process v2 owner is not erased. A crash after durable effects/claim can leave real pending responsibility; repeating a selection ID cannot authorize replay. This slice does not solve all dead-actor recovery or remote resource ownership.

## Source verification and adoption

`tools/Test-EmbeddedDuplexNewRunSource.ps1` compiles actual finite/default-none and ongoing/source-bound-fresh Android Java/Kotlin callers, runs focused parser/attachment/ordinary Start/Original controls, actual store/idempotency/fence/source-reader controls, exact Unix Native fence/transition controls and full Android Native/JNI source checks. The Unix fixture executes real flock/inode/counter operations; enclosing runtime/platform/physical facts and the digest-tool seam are explicitly declared models. Android source compilation is not Android execution.

Host fixtures check retained identity/enrollment-shaped file bytes and settings isolation. They do not attest the maintained private consumer's actual enrollment bytes/revision, camera/codec/GPU teardown, remote release, rendered media or an installed APK. The next immutable APK/consumer checkpoint must use run-aware requests, observe the same actual Java/Native run and verify actual identity/enrollment/settings bytes before/after useful bootstrap/Pair/Start. Current installed SDK/APK pins are unchanged by this source package.
