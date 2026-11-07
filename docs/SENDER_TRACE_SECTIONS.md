# Sender Android Trace sections

The packed-stereo source can emit six fixed Android APP-tag sections. Both the
host application's `ApplicationInfo.FLAG_DEBUGGABLE` and an externally enabled
Android Trace session are required. The runtime samples the application flag
once at construction; a missing context, missing application info or failed
lookup leaves tracing disabled. Existing standalone pump and LAN constructors
also leave it disabled.

The shared AAR has no generated application `BuildConfig`. The sampled host
flag is carried explicitly to its packet pump and accepted LAN transport,
including a transport recreated after verified cleanup. It grants no media,
session or socket authority.

## Fixed boundaries

| Fixed name | Measured work |
| --- | --- |
| `rusty.media.sender.capacity_wait` | One existing `offerAwaitCapacity` monitor wait, including reacquisition. The loop's cancellation checks, packet cloning and enqueue are outside it. |
| `rusty.media.sender.pump_write` | One packet pump `OutputStream.write(packet)` call. |
| `rusty.media.sender.pump_flush` | The following pump `OutputStream.flush()` call. |
| `rusty.media.sender.source_read` | One LAN forwarder's source `InputStream.read(buffer)` call, including EOF or an exception. |
| `rusty.media.sender.sink_write` | One LAN forwarder's actual `OutputStream.write(buffer, 0, count)` call. |
| `rusty.media.sender.sink_flush` | The following LAN sink `OutputStream.flush()` call. |

The pump usually writes the local producer connection; the accepted LAN
transport subsequently writes its sink connection. These are distinct call
boundaries, not two measures of final peer receipt. Direct configuration replay
and bootstrap writes are outside this bounded instrumentation.

There are no dynamic labels, packet parsing, frame/PTS counters, new properties,
timers or tracing threads. Existing queue limits, byte order, retries, socket
deadlines and cleanup remain in force. The LAN write deadline continues to
cover both write and flush. The packet pump's idle wait for an empty queue is
not a capacity wait and is not instrumented.

Each successful begin is paired with a same-thread `finally` end, including
media failures and interrupted capacity waits. Runtime or linkage failures in
the optional Trace calls leave the original media result or exception intact.
Release hosts skip Trace API calls entirely. Debug hosts with external tracing
disabled emit no sections.

## Capture and interpretation

Use an explicitly bounded external Perfetto `atrace_apps` session for the
selected package. Check actual current process/TID tracks and section adoption;
configuration alone is no proof. Preserve capture loss, missing metadata and
spans censored by session start/end. This code starts no trace session and
extends no capture lifetime.

Spans measure synchronous wall time, including descheduling. A long socket
call alone cannot distinguish transport blocking from scheduler delay. A
capacity wait includes monitor reacquisition and can repeat after a spurious
wake. No section proves packet delivery, decoder readiness, a camera stall or
a performance improvement. Compare with the existing
[receiver sections](RECEIVER_TRACE_SECTIONS.md) and frame diagnostics; do not
subtract elapsed timestamps from separate headsets without a verified clock
join.

## Focused validation

```powershell
python tools/checks/Test-SenderTraceSectionsHost.py --output target/sender-trace-host --java-home <jdk17>
python tools/checks/Test-SenderPhaseTraceHost.py --output target/sender-phase-host --java-home <jdk17>
python tools/checks/Test-ReceiverTraceSectionsHost.py --output target/receiver-trace-host --android-jar <android-platform/android.jar>
pwsh -NoProfile -File tools/checks/Test-RustyQuestMediaStreamAndroid.ps1 -RepoRoot .
python tools/check_quest_boundaries.py
git diff --check
```

Use create-new output directories. The sender fixtures compile the actual
helper, application-flag sampler, constructor/binding fragments, packet pump
and forwarding body with explicitly modeled Android and socket leaves. They
check gate failures, balanced sections and preserved operations. The receiver
gate also compiles all actual shared media Java against the Android API.
These host controls create no APK and establish no Quest timing, external
Trace enablement, network behavior or device qualification.

The existing source-retirement host fixture uses a Trace tripwire whose
methods throw assertions if called, checking that compatibility pump
constructors never reach the Android API. This explicitly modeled leaf is
only part of that host fixture, outside the AAR's production source set.
