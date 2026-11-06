# Optional Navigator visibility observer

`apps/navigator-visibility-android` is a source-level Android Java library and
neutral JVM conformance consumer. It observes the firmware CRCS signal
`signal://shell/navigator/visibility` through the application's own context.
It declares no Android permission, exported component, startup provider,
background service, shell operation or menu actuator. Creating a candidate is
inert; `start()` explicitly attempts its single registration.

The component is an unlisted, opt-in implementation candidate. It adds no
feature descriptor, app selection, runtime profile or module-composition entry.
Integrating it into a governed application still requires that owner's explicit
feature selection, lifecycle and validation. Host tests are not module promotion.

## Availability and firmware boundary

`AndroidNavigatorVisibility.create(applicationContext, listener)` returns a
`Candidate`. `available` means the current process found the `crcs` service and
could construct its vendor binding. It does not prove caller permission,
successful registration, fresh callbacks, useful visible effect or distribution
eligibility. A missing application context/service or incompatible vendor
linkage returns an unavailable candidate with no observer. There is no fallback.

The `horizonos.os.resource` binding is firmware-specific and outside the public
Android SDK. Minimal `compile-only/` declarations describe the observed ABI;
the OS supplies every implementation. Delivery mode `2` and interval `0` are
the narrow demonstrated subscription arguments, not a claim about other vendor
delivery modes. No vendor binary or declaration class ships in the AAR.
Missing or changed vendor APIs can fail availability, registration or callback
processing. Store policy and an actual consumer's eligibility require separate
current official verification. This library makes no eligibility claim.

The library targets Java 8 and Android API 24 or newer. Its application context
is used only during construction and never retained. `consumer-rules.pro` keeps
the optional linkage boundary intact when the consumer uses R8; the generated
AAR includes those rules. Host linkage tests do not exercise Android ART/R8.

## Samples and time

Every observer receives a process-local monotonically increasing `generation`;
each admitted sample carries that generation and an ordered `sequence`.
Controller URIs contain a fresh UUID and use the library's own namespace.
The backend handles every value in a callback batch in vendor-delivered order.

| Observation kind | Meaning |
| --- | --- |
| `INITIAL_TRUE` | First sample is true; the pre-subscription state is unknown. |
| `FALSE_BASELINE` | First sample is false. |
| `OPENED` | An observed false-to-true edge. |
| `HIDDEN` | An observed true-to-false edge. This also establishes a false baseline. |
| `UNCHANGED` | Same value as the preceding observed sample. |

`Status.falseBaselineObserved` records receipt of any false sample. An initial
true is retained as evidence and never converted into a synthetic opening edge.
No callback, successful registration or resumed Activity implies a false
baseline. Consumers must not infer current visibility from silence.

`receivedUptimeMs` is local `SystemClock.uptimeMillis()` at callback admission.
The vendor's raw `getTimeSinceBoot()` value is retained as `vendorSampleTime`
without asserting its units, epoch or relation to Android uptime. A backwards
or invalid local receipt clock holds the observer. These are signal receipts,
not pixel visibility, menu-close latency or an actuation-effect receipt.
Freshness/watchdog policy belongs to a consumer and is not fabricated here.

## One original and cleanup

The portable `NavigatorVisibilityObserver` owns one backend handle. Its state is
`NEW`, `REGISTERING`, `ACTIVE`, `UNREGISTERING`, `CLOSED` or `HELD`.
Calling `start()` again never issues another original registration.

Callbacks received during registration are queued, in order, until the original
returns true. A false return rejects registration and closes the observer. An
exception or linkage failure leaves an unknown original in `HELD`; it must not
be retried as though nothing happened. Callback, listener, clock or bounded
32-sample queue failure also holds observation. The status retains the cause
without logging vendor exception contents.

`close()` disarms callback admission and clears queued samples immediately. It
attempts at most one unregister using the same controller object. If registration
is still in flight, cleanup waits for that original's return, including when a
callback fault has already changed the visible phase to `HELD`. That late result
cannot re-arm the observer. A rejected or throwing unregister remains `HELD`;
repeated close/start calls do not replay uncertain cleanup. Retain that exact
observer/status as evidence. Creating another generation does not reconcile it.

No platform call or listener runs under the observer lock. An already admitted
listener call may finish after close; callers must fence their own queued work
by current generation and lifecycle/escape state. Observations are serially
delivered on the submitting callback/start thread; faults can be concurrent or
re-entrant. Listeners must be brief and thread-safe. Dispatch UI work to the UI
owner, check its generation and armed state again there, and never hold an app
authority lock while calling start/close. Platform register/unregister calls
are synchronous and may block; schedule lifecycle calls on the consumer's
serialized worker, rather than blocking the UI or calling close from inside a
vendor callback. The adapter invents no timeout success and spawns no service.

## Consumer split

A diagnostic consumer can create a candidate, attach an event journal, explicitly
call start on its lifecycle worker, display status and samples, and disarm its
own queued work before calling close at stop/destroy or explicit escape.
Each new run retains its own generation; previous-run callbacks must fail the
consumer's generation check. `host-tests/consumer/VisibilityConformanceMain.java`
is the neutral, non-actuating journal consumer and deterministic lifecycle
harness. It records initial true, false baseline, transitions and cleanup.

Application policy owns arming, freshness, target selection, one actuator original
at a time, completion/unknown reconciliation, explicit escape and lifecycle
disarm. A visibility sample grants none of those actions. Closing this observer
does not close Navigator, cancel another app's operation or attest the effects
of an exported broadcast. This boundary keeps any application-specific menu
policy and privileged alternatives out of the platform library.

## Build and focused validation

```powershell
pwsh -NoProfile -File tools/Test-PowerShellHost.ps1 -SelfTest
pwsh -NoProfile -File tools/Test-NavigatorVisibility.ps1 -AndroidJar <installed-sdk/platforms/android-N/android.jar>
python tools/check_quest_boundaries.py
git diff --check
```

The focused script uses installed JDK tools (optionally `-JdkBin`) and an explicit
Android SDK jar. Each run creates a fresh ignored output directory; it does not
download dependencies, invoke Gradle, build an APK, contact a device or change a
shared cache. It compiles production code against the SDK and separate vendor
signature classes, compiles host doubles separately, runs the journal/race
harness, and runs another JVM with all vendor classes absent. It packages only
production classes into a minimal AAR and checks that host doubles/vendor
declarations did not enter `classes.jar`. The JSON result records the AAR and SDK
hashes and explicitly reports no device/APK validation.

Host assertions cover initial true/false, ordered batches, duplicate samples,
generation isolation, repeated lifecycle calls, close before start, uncertain
registration and cleanup, callback overflow, local clock regression, listener
failure, a callback fault plus close racing an in-flight registration, late
callbacks, use of the application context and absence of actuator/resource
claims. They prove behavior against test doubles, not current firmware support,
real Binder permissions, runtime packaging, pixel effects or release readiness.
An attended ordinary-app device validation remains a separate next step under
the actual app owner's build/device coordination and source locks.
