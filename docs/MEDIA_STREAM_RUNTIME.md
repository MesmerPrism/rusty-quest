# Generic Media Stream Runtime

`rusty-quest-media-stream` separates the source-neutral plan from seven exact
product owners:

1. the source-neutral `MediaStreamSessionPlan`;
2. source descriptors for Camera2, app-consent display composite, external
   H.264, diagnostic, and developer-only shell capture;
3. explicit passthrough, independent dual-lane, or packed-SBS processor
   descriptors;
4. accepted route references;
5. socket and codec providers;
6. selected sinks; and
7. terminal cleanup.

Manifold remains accepted session/stream authority. A runtime spec must carry
the accepted Manifold decision id and revision. Quest owns only platform
adoption phases: planned, receivers armed, sources started, sink-observed
streaming, and stopped. Each transition is revisioned and replay-protected;
source start before receiver readiness, streaming without sink-observed frames,
and stop without cleanup reject without advancing state.

When a product explicitly selects Direct P2P, it is referenced through the existing
`rusty.quest.direct_p2p_socket_route.v1` contract. The media runtime validates
the lane/peer endpoint and scoped Rust socket authority, but it never creates,
binds, or substitutes sockets. Generic LAN media products carry no implicit
`p2p0` route. Broker packaging rejects Camera2 or Direct-P2P providers that
exceed the exact product feature lock. Codec ownership likewise stays in existing
MediaCodec/H.264 adapters. Packed-SBS processors require left/right inputs,
stereo output, and no CPU pixel-copy path.

`rusty-quest-remote-camera` is retained as an explicit compatibility adapter.
It validates the legacy plan, maps it to the generic plan/runtime, preserves
lane and route counts, and emits separate dual-lane, packed, or passthrough
processor selection. Legacy properties and commands remain compatibility
surfaces rather than generic runtime authority.

Canonical cross-repo packaging, the Rust prepare/apply protocol, exact owner
completion receipts, and the Android boundary are documented in
[Generic Media Session Runtime](MEDIA_SESSION_RUNTIME.md).

Validation:

```powershell
cargo test -p rusty-quest-media-stream -p rusty-quest-remote-camera
```

## Android module and artifact validation

`crates/rusty-quest-media-stream-android` separates Android media execution from
host permissions and policy. The standalone broker and the neutral
`apps/media-stream-conformance-android` consumer use the same compiled AAR and
link the `rlib` into their respective native authority libraries. The neutral
host declares no camera, network, headset, or foreground-service permission;
its changing stereo data and transport are bounded in-memory inputs.

The host is a deployment choice: a Manifold module may run through Hostess or
inside another application APK. An embedded consumer supplies its own lifecycle,
permissions, display surfaces, and accepted product binding. It does not require
a separately installed broker APK or create another Manifold authority.

Packed capture, timestamp pairing, GLES composition, encoder draining, and
transport live in the shared Android package. The broker's legacy packed-source
class delegates to `PackedStereoMediaSourceRuntime`; it retains host commands
and defaults without carrying a second implementation.

The module's protocol must reject oversized or malformed packets, duplicate
submitted presentation timestamps, missing exact timestamp associations,
expired stereo pairs, and callbacks from a cancelled generation. Encoded
frames retain their exact source identity; a nearest-timestamp fallback is not
an identity match. Leases release frames exactly once. Network work must leave
encoder/render callbacks promptly, bound its queues, and gate reconnect on
configuration plus a keyframe. Camera disconnect/error and terminal cleanup
must appear in effective state rather than leaving a stale active label.

### Shared packed capture

An embedded host may own one `PackedStereoCaptureOwner` independently of a
Peer media subscription. `createSharedCapturePipeline` attaches a bounded
encoder consumer to an already started, configuration-matching owner; closing
that subscription does not stop app-owned capture. Capture and encoder EGL
contexts retain separate content leases and real consumer fences. Mailbox
pressure drops bounded complete inputs rather than creating an unbounded
queue or declaring a blocked fence ready.

A selected profile can request 60 Hz on supporting cameras. Measure actual
sensor, renderer and encoder cadence separately. Pair identity and exact
sensor timestamps survive encoding and decoding; an allocation handle alone
is not a frame identity. Whole-capture shutdown keeps the capture context
alive until actual camera closure and all retained consumers retire.

Run the bounded host and source checks before Android compilation:

```powershell
cargo test --locked -p rusty-quest-media-stream -p rusty-quest-broker-authority -p rusty-quest-manifold-broker-authority-native -p rusty-quest-media-stream-android
pwsh -NoProfile -File ./tools/checks/Test-RustyQuestMediaStreamAndroid.ps1 -RepoRoot .
pwsh -NoProfile -File ./tools/Build-MediaStreamConformanceAndroid.ps1 -HostOnly -JavaHome $env:JAVA_HOME -OutDir ./target/media-conformance-host
```

Build both consumers through the declared artifact route:

```powershell
pwsh -NoProfile -File ./tools/Build-MediaStreamConformanceAndroid.ps1 -BuildCompatibilityBroker -AndroidHome $env:ANDROID_HOME -JavaHome $env:JAVA_HOME -ManifoldSourceRoot $env:Q2Q_MANIFOLD_SOURCE_ROOT -OutDir ./target/media-conformance-android
```

The build records actual compiler identities and hashes of the AAR, native
libraries, APKs, and packaged bindings. `-BuildCompatibilityBroker` invokes the
existing explicit compatibility build using a PowerShell argument splat with
the two camera/display binding paths as a string array. `-HostOnly` cannot use
that switch. Neither path runs ADB, installs packages, or qualifies device
behavior. Host tests prove the exercised contracts; compilation proves artifact
composition. Sustained camera/codec/GLES/LAN execution and simultaneous duplex
require separate device evidence for the integrated host.

Builds use installed Gradle 8.7, Android platform/build-tools 36, NDK
27.2.12479018, and Java 17 with Java 8 output. Host JSON contract tests use the
hash-pinned `org.json` 20240303 jar, resolved from the local Gradle cache or
`-HostJsonJar`. Missing tools fail explicitly; these commands do not download
dependencies. Each invocation preserves prior evidence in its own output
capsule under the selected repository `target` directory.

## Embedded diagnostic producer/consumer regression

`tools/checks/Test-EmbeddedDuplexDiagnosticProducerConsumer.ps1` compiles the
selected actual Java Platform, Registry, ticket, binding and readback sources,
then feeds their full diagnostic getter payload into the extracted actual Rust
closed parser. It also checks that the JNI string consumer delegates to that
parser. Run this focused check when changing either side of this diagnostic
seam; it is separate from the build and device routes above.

Supply `-RepoRoot`, `-JavaHome`, `-CompiledOwnerClassPath`, `-HostJsonJar`,
`-RustCompiler`, `-RustDependencyDirectory`, `-SerdeJsonLibrary` and a fresh
`-OutputDirectory`. The classpath contains the selected app/SDK dependencies;
the host JSON jar and serde_json library are existing host artifacts. Run in
an environment with the selected Rust compiler's linker prerequisites. The
check downloads nothing and records the selected source and artifact hashes.

A parsed ticket reaches an actual Registry callback whose mock provider returns
foreign readback; the actual classifier feeds the actual getter. Every compiled
ProviderReason value follows the same getter, then the actual parser checks
legacy compatibility, closed fields/values and the 256-byte bound. Reflection
transfers diagnostic state into the getter to exclude constructor effects.
This proves the exercised producer/parser seam, not complete Platform dispatch,
JNI invocation, physical effects, Android compilation or streaming readiness.

`tools/checks/Test-EmbeddedDuplexConcurrentPeerCaller.ps1` compiles the current
Kotlin Display/Router and Java Receiver/Registry before exercising the complete
concurrent Own-to-Peer preparation caller. Its happy case must preserve Own;
non-live/stale Own, absent native actor proof, native local quiescence failure,
and fenced dispatch must reject with the retained Receiver failure stage. The
exclusive Local shutdown guard remains a separate rejection.

Pass explicit JDK, Kotlin compiler classpath, compiled owner dependencies, host
JSON jar, optional Kotlin friend paths, and a fresh output directory. The fixture
uses actual capture freshness/deadline methods with injected pool freshness and
mocked Own instance, Looper/clock and JNI observations. It proves no camera,
renderer, network, physical cleanup or JNI execution. Timeout coverage checks
the closed exception category, not a timed wait. This focused owner regression
is callable independently; it is not a new APK or lifecycle prerequisite.

`tools/checks/Test-EmbeddedDuplexAdmissionJniCaller.ps1` reuses the concurrent
caller fixture and compiles current Kotlin/Java callers against an actual host
JVM JNI library. The library extracts the owning admission/getter functions
and uses the actual source/input-set and peer-route modules. It checks coherent
clock/read interleaving, strict freshness/epoch/carrier rejection, and closed
Java pre-JNI versus native first-failure reasons. Supply the existing Java/Kotlin
dependencies, Python/Cargo executables, an absent run-owned native target path
(short enough for the host linker), and an absent output directory. Cargo uses
locked offline `jni` 0.21.1. Physical actors, clock and Looper remain fixtures;
this proves no Android JNI supplier composition, physical device cause or duplex
acceptance and adds no APK prerequisite. The host fixture explicitly exits after
assertions because actual supplier executors have no fixture shutdown route.

The same JNI caller check includes default Local initialization, stale polling
and the extracted actual Activity Local stop branch. Local intent generation
and its later native retirement generation remain separate: the concurrent
proof binds both counters and exact native carrier words, then rechecks both
before Peer reservation. A fresh JVM isolates that native source-owner case;
prior-native-counter and substituted-word negatives retain the guards. The
Activity carrier/Surface and physical actors are fixture suppliers; Pending
Local retirement does not become terminal cleanup evidence.
