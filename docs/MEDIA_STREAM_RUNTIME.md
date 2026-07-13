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
