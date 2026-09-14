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
