# Generic Media Stream Runtime

`rusty-quest-media-stream` now separates five surfaces:

1. the source-neutral `MediaStreamSessionPlan`;
2. source descriptors for Camera2, app-consent display composite, external
   H.264, diagnostic, and developer-only shell capture;
3. explicit passthrough, independent dual-lane, or packed-SBS processor
   descriptors;
4. external route contracts and selected sinks; and
5. the receiver-first `MediaStreamSessionRuntime` lifecycle.

Manifold remains accepted session/stream authority. A runtime spec must carry
the accepted Manifold decision id and revision. Quest owns only platform
adoption phases: planned, receivers armed, sources started, sink-observed
streaming, and stopped. Each transition is revisioned and replay-protected;
source start before receiver readiness, streaming without sink-observed frames,
and stop without cleanup reject without advancing state.

Direct Wi-Fi P2P is referenced through the existing
`rusty.quest.direct_p2p_socket_route.v1` contract. The media runtime validates
the lane/peer endpoint and scoped Rust socket authority, but it never creates,
binds, or substitutes sockets. Codec ownership likewise stays in existing
MediaCodec/H.264 adapters. Packed-SBS processors require left/right inputs,
stereo output, and no CPU pixel-copy path.

`rusty-quest-remote-camera` is retained as an explicit compatibility adapter.
It validates the legacy plan, maps it to the generic plan/runtime, preserves
lane and route counts, and emits separate dual-lane, packed, or passthrough
processor selection. Legacy properties and commands remain compatibility
surfaces rather than generic runtime authority.

Validation:

```powershell
cargo test -p rusty-quest-media-stream -p rusty-quest-remote-camera
```
