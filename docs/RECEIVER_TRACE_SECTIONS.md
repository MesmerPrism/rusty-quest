# Receiver Android Trace sections

The shared packed-stereo receiver emits fixed Android APP-tag sections only
when Android tracing is enabled for the process. It adds no provider, property,
ARM, session, JSON schema, media queue, packet-drop or camera-request behavior.
There is no application timer, logging stream or tracing thread.

Enable the selected package through an explicitly bounded external Perfetto
`atrace_apps` session. This code neither starts that session nor extends it.
Before interpreting results, verify that the actual current app/TID tracks
contain the section names below; configured tracing alone is not adoption.
Keep capture loss, file caps, missing metadata and clock joins explicit.

## Section boundaries

| Fixed name | Measured work |
| --- | --- |
| `rusty.media.receiver.read_packet` | Complete framed-packet read, parsing and validation, including nested I/O and byte counting; ends before the packet-progress receiver lock. |
| `rusty.media.receiver.socket_read` | One underlying `CountingInputStream` read below `BufferedInputStream`; ends before byte-count monitor acquisition. Includes header and payload reads, partial reads and EOF. |
| `rusty.media.receiver.queue_packet` | Complete input dequeue/capacity/identity/queue method, including any nested drain or acquisition while waiting for capacity. |
| `rusty.media.receiver.drain` | Complete synchronous output drain loop, including nested pre-render and output release. A zero dequeue timeout does not bound the whole loop. |
| `rusty.media.receiver.acquire_probe` | Only the host/native acquisition callback; excludes subsequent receiver identity/counter locking. This may also run on startup/control threads. |
| `rusty.media.receiver.pre_render` | Synchronous host/native frame-registration callback before output release. |
| `rusty.media.receiver.release_output` | The actual codec output release, including configuration/rejected/overflow paths, with its original render argument. |

Names carry no package, peer, frame, PTS or other dynamic data. Use current
process/TID metadata and the existing diagnostic frame/connection identity to
join observations. Do not assume that a matching section name alone identifies
the active receiver connection, and do not reuse old trace track IDs.

The distinction between `read_packet` and its nested `socket_read` sections
separates actual underlying I/O from buffered parsing or byte-count locking.
Gaps between completed packet reads can also contain queueing, drain,
acquisition, monitor or scheduling work. Receiver/trace locks outside the listed
boundaries remain outside those sections. A long span includes scheduling and
execution; it does not by itself establish network loss or a decoder fault.

## Default cost and failure behavior

`ReceiverTraceSections` uses `Trace.isEnabled()` and fixed string constants.
The disabled path checks the platform flag and emits no sections. No temporary
section-name strings, scope objects, clocks or JSON are added on the media path.
Enabled tracing incurs Android marker costs and can perturb scheduling; no
Quest overhead bound or performance improvement has been established.

Each successful begin has a same-thread `finally` end using its captured begin
result, including early return and thrown media failures. A platform tracing
RuntimeException is ignored rather than replacing a media result or exception.
Trace sessions that start/end mid-call can contain partial/censored spans;
that is not a receiver lifecycle transition or cleanup assertion.

`Trace.isEnabled()` requires API 29, matching the shared library's existing
minimum. Section names are below the platform's 127-code-unit bound. See the
[Android Trace API](https://developer.android.com/reference/android/os/Trace).

## Focused validation

```powershell
python tools/checks/Test-ReceiverTraceSectionsHost.py --output target/receiver-trace-host --android-jar <android-36/android.jar>
pwsh -NoProfile -File tools/checks/Test-RustyQuestMediaStreamAndroid.ps1 -RepoRoot .
python tools/check_quest_boundaries.py
git diff --check
```

The first gate requires a create-new directory below this repository's `target`.
It extracts the actual receiver methods, packet-read fragment and counting
stream into host fixtures. Android Trace, codec, clock and native/protocol
leaves are explicitly modeled. A real blocked Java stream and receiver monitor
check that socket scope excludes the count-lock wait. Other controls exercise
disabled behavior, single/bulk/partial/EOF reads, exceptions, callback rejection,
balanced nesting and output-release counts. All actual shared Java sources are
also compiled against the Android API with Java 8 target.

These gates produce ignored host classes and source/tool receipts, not an APK
or packaged AAR. They establish no actual Android Trace enablement, socket,
decoder, acquisition, headset timing, pixels, performance or acceptance result.
