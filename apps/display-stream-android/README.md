# Rusty Quest Display Stream

This explicit opt-in Android utility exposes two display-capture adapters over
one H.264/RMANVID1 stream contract:

- `DisplayStreamActivity` requests Android MediaProjection consent and starts a
  foreground capture service.
- `ShellDisplayMirrorMain` is packaged as a DEX sidecar and is started by an
  authorized, serial-scoped ADB `app_process` command. It mirrors display 0
  through Android's shell-only display APIs and does not use MediaProjection.

The APK and shell sidecar reuse the same encoder and binary writer. The shell
adapter cannot run inside the APK process because it deliberately requires the
Android shell UID. Both connect to the same host listener, normally through an
ADB reverse rule, and both identify their source in RMANVID1 metadata.
See the [wire and process contract](../../docs/DISPLAY_CAPTURE_WIRE.md) for
packet flags, emission clocks and the distinct native square producer. Codec
EOS stays local to draining: an empty sentinel is omitted; final media retains
its key/config flags without a wire EOS extension.

The encoder requests the selected surface-input frame-rate cap, real-time codec
priority, and a one-frame latency target when the vendor codec supports those
hints. Encoded `MediaCodec` output remains in its `ByteBuffer` until a gathered
header-plus-payload socket write completes: there is no frame-sized Java-array
copy, per-field one-byte write loop, or per-packet stream flush. RMANVID1 layout
and timestamps remain byte-compatible with the Hostess receiver.

Build with:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-DisplayStreamAndroid.ps1
```

The build writes a signed debug APK, the shell DEX jar, and a hash receipt under
`target\display-stream-android`. Runtime orchestration and preview belong to
Rusty Hostess; this app does not own a second PC protocol or viewer.

Hostess normally launches the APK with the visible Android consent chooser.
For unattended development validation it may temporarily set the package's
`PROJECT_MEDIA` app-op to `allow`, but it must read the prior mode and restore
that exact mode after the run. This is lab convenience, not persistent capture
authority. The shell sidecar never needs or consumes that app-op.

Hostess stops an unbounded MediaProjection run through the explicit
`io.github.mesmerprism.rustyquest.displaystream.action.STOP` receiver. The
receiver only adapts the ADB command into the app-owned `stopService` call; the
private foreground service remains non-exported.

This implementation was informed by scrcpy's separation between shell capture,
device-side MediaCodec encoding, direct codec-buffer emission, and host-side
decoding. Those principles were implemented independently for RMANVID1; it does
not copy the scrcpy server, control protocol, or source code.

## Owned retirement and shell control

A run retains one `DisplayStreamAttempt`, its channel and acquired resources until
its worker has genuinely exited. Stop closes that exact channel before waiting,
including a blocked codec-buffer write. Android main-thread destruction never
joins or clears an unresolved worker. A process-retained attempt slot rejects a
replacement while cleanup is pending or uncertain. Old callbacks can affect only
their original attempt. Encoder/mirror release failures are reported, not hidden.
A constructed Java VirtualDisplay wrapper alone is not a capture allocation;
unsupported shell display APIs now fail rather than claiming that fallback works.

For an explicitly controlled shell invocation, supply `--invocation-id` and
`--stop-nonce` as fresh lowercase32-hex strings plus a bounded opaque
`--generation`. Low-rate control is one canonical ASCII JSON line on stdin:
`schema=rusty.quest.display_stream.control.v1`, action=stop, invocation_id,
generation and stop_nonce, in that order with compact JSON separators. The exact
incarnation must match. EOF or malformed control retires only that attempt but
does not certify the requested Stop. Stdout contains bounded hello/terminal JSON
including PID/UID/start ticks, never media. Media remains RMANVID1v1 over its
separate selected socket. A coordinator emits terminal only after actual worker
exit and release; the host must still prove the exact remote actor gone and
native exit0/full pipe EOF. No marker converts native nonzero into success.

Source clocks are explicit: MediaCodec PTS and device elapsed/unix packet-emission
samples. They are not sensor timestamps, GPU execution duration or calibrated
end-to-end latency. No Meta signed helper or scrcpy runtime is required by this
independently authored source. Current shell-API compatibility, useful VR content,
consent/coexistence for MediaProjection and physical retirement require device
qualification. This source check is not that qualification.

Focused host checks (no APK/DEX packaging, listener or device):

```
pwsh -NoProfile -File tools/checks/Test-DisplayStreamRetirementHost.ps1
pwsh -NoProfile -File tools/checks/Test-DisplayStreamAndroidStatic.ps1
```

The retirement harness compiles the production attempt/slot/control/writer/encoder
classes with clearly modeled Android codec stubs. It uses a real blocking NIO
Pipe to prove channel-close wakeup and covers pending/stale/foreign/repeated Stop,
release failures, observer interruption, codec-buffer finally and config/key/PTS
wire preservation. Android API34 Java typechecking is separate. The build helper
uses JDK `--release8` plus the selected Android classpath so lambda bootstrapping
does not depend on Android stub-only LambdaMetafactory.
