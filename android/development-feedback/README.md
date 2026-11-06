# Neutral development feedback seam

An opt-in CPU-only Java implementation contract, shared host logic, desktop
classloader conformance adapter and API-26+ Android `DexClassLoader` adapter.
No application, manifest, Gradle project, APK builder, transport, device action
or default runtime activation is included. Android sources compile against a
real SDK; host tests execute on the JVM and do not establish ART or Quest
patching, Activity continuity, unloading, distribution suitability or speedup.

The parent owns `org.rusty.quest.feedback.api.FeedbackModule`. Plugins compile
against its contract JAR but never package the resident namespace. They supply
ABI version 1, an immutable generation identity and synchronous
`compute(input, counter, scratch)` behavior. Arithmetic in the examples wraps
as Java `long`/byte arithmetic. Implementations must not retain scratch or
create callbacks, threads, asynchronous tasks or persistent module state.
This is a trusted development contract, not an adversarial code sandbox.

`ArtifactStore.stage` requires an application-private directory and a trusted
SHA-256 digest. It creates a new file, opens its descriptor, marks the file
read-only before writing, closes, verifies the received digest and inspects
definitions. Activation rechecks read-only state and the digest before any
class loading. Android `AndroidDexLoader.stage` obtains that directory from the
supplied `Context`. Standard single-DEX JARs and ASCII class names are the
bounded Android format; compact/container DEX, multidex, resources and Android
components and JAR manifests are intentionally outside the seam. In
particular, a manifest `Class-Path` cannot add an unstaged dependency to the
desktop adapter. DEX inspection reads
`class_defs`; references to the parent interface are allowed, duplicate
resident definitions are rejected. A digest establishes identity relative to
the supplied trusted value, not publisher authenticity.

`ModuleHost` serializes activation, computation and retained selection and
rejects callback reentry into any mutation while one is in progress. Read-only
observations remain available. The child must use the exact selected parent
loader without an intermediate dependency loader. It
owns one fixed buffer allocation and counter. A module sees only a scratch
copy. Successful computation copies back into the existing allocation and
increments the wrapping Java `long` counter once; a thrown call commits neither. Activation checks
the artifact again, requires a fresh defining loader, verifies ABI/compiled
identity, and evaluates a caller-provided independent known-answer `Probe`
against scratch state before publishing the callable and its retained loader.
The host's authoritative state is unchanged by activation. A rejected candidate
leaves the active generation usable.

Each load attempt consumes the configured cap, including failed initialization
or validation attempts. Successfully created loaders are retained even when
validation fails. Reaching the cap requires recycling the host process; this
implementation neither closes loaders nor promises class/memory reclamation.
Repeated staging consumes disk separately; the caller owns a bounded staging
session and may remove only its known retired files after the associated
process exits. Selecting retained A1 changes future code; it does not restore
earlier counter or buffer contents.

Run the focused host gate from the repository root:

```powershell
pwsh -NoProfile -File tools/Test-DevelopmentFeedback.ps1 -JavaHome $env:JAVA_HOME
```

For real SDK compilation and D8 inspection, pass explicit installed paths:

```powershell
pwsh -NoProfile -File tools/Test-DevelopmentFeedback.ps1 -JavaHome $env:JAVA_HOME `
  -AndroidJar <installed-sdk>/platforms/android-34/android.jar `
  -D8 <installed-sdk>/build-tools/34.0.0/d8.bat
```

JDK 17 is required. With no `JavaHome`, the runner uses `JAVA_HOME`, then the
resolved `javac` executable. The runner installs nothing, builds no APK and
contacts no device. Every run writes to a new ignored `.local/<unique-id>`
inside this module, or a new caller-supplied `OutputRoot`. Generated sources,
fresh compiler commands/timings, artifact digests, tool/source hashes, step
logs and total enclosing time are preserved there, including failed runs.

The test compiles A1, then structurally changed B2 (helper/private field/new
method), then fresh A3 restoring A semantics. The original host classpath
contains none of their implementations. Held-out computations and literal
known answers check changed execution, defining-loader identity, stable host
allocation/state and separate retained-A1 rollback. Negative cases cover
hash/tamper/identity/archive/ABI/contract/self-test failures, scratch mutation
before a thrown compute, failed-load cap accounting, callback reentry,
intermediate parents and a manifest pointing to a real unstaged helper. Optional D8 runs
compile actual DEX and check both clean definitions and a deliberately
duplicated parent interface. These are host preparation/conformance results.

See [the owner programme guide](../../docs/DEVELOPMENT_FEEDBACK_MODULES.md)
for the native seam and the remaining runtime evidence boundary.

## Optional connection observation candidate

`ConnectionObservationController` is a passive resident consumer of the same
host and parent-loaded interface. It is exercised by an independent JVM
consumer; no application selects it automatically and no APK includes an
enabled product route through this change. It owns no sockets, keys, commands,
timers, Futures or lifecycle state.

Its fixed scalar meanings are:

| Input/output | Meaning |
| --- | --- |
| Input 1 / 2 / 3 | Poll due / observation failed / observation received |
| Input bit 256 | Resident reports unresolved work; read-only status remains possible |
| Input 0 | Independent activation probe, not a delivered event |
| Output 0 / 1 | WAIT / REQUEST_STATUS |

Only the event and unresolved flag reach replaceable code. Owner, operation and
sample tokens remain resident, along with current/closed/cancellation/in-flight
flags and monotonic polling times. The resident controller enforces a 500ms
polling floor before calling the module. Neither output can issue or replay a
command, complete a Future, assert cleanup, or resolve UNKNOWN. A module may
change which eligible events nominate a read-only refresh, using helper classes
without changing the parent interface.

Take a `Snapshot` under the product's existing owner gate, then call `evaluate`
outside that gate. On completion, under the same gate, take a fresh snapshot,
call `claimStatus` and atomically mark the query in flight/update last-poll time.
Dispatch the existing read-only observation after releasing the gate. The
caller must advance tokens whenever its owner/operation/sample changes. This
value projection does not establish authority or freshness by itself.

Acceptance performs no callbacks or lock acquisition. A private one-use
decision plus a fresh atomic identity for every selection attempt rejects
replay, replacement races and stale owner/operation/sample/timing observations.
Even rejected activation invalidates outstanding proposals. A replacement
after a successful claim cannot undo a query already accepted by the resident.
Activation and computation serialize outside product locks; a hung module can
still block those calls and retained selection. There is no timeout or sandbox.

Runtime exceptions, linkage failures and unrecognized outputs become visible
WAIT decisions. Other fatal errors are not contained. Failed computation
leaves ModuleHost state unchanged; an invalid returned value has already
completed a successful host computation. Neither case automatically rolls
back code. `selectRetained` explicitly changes future code and invalidates
proposals while preserving the host counter/buffer. Resident keys/resources,
pending operations and durable authority must never be stored in that buffer.

Run the additional focused consumer with an installed JDK17 and a fresh ignored
output directory:

```powershell
python tools/Test-ConnectionObservationFeedback.py --java-home <jdk17> --output <repository>/.local/<fresh-run>
```

The consumer compiles implementations against a JAR containing only
`FeedbackModule`, then stages fresh A/B/A generations through the existing
artifact store and loader. It checks changed helper behavior, explicit retained
rollback, rejected ABI, throwing/malformed calls, concurrent one-use acceptance,
acceptance while another computation is blocked,
stale/cancelled observations and stable resident fixture state. The fixture's
resource/key/Future/UNKNOWN values are not physical resources or a durable
product store. This proof establishes neither Android/ART execution nor product
integration, cleanup, lifetime or feedback performance.
