# N-Peer Mesh Projection

`rusty-quest-peer-session-adapter` combines an authenticated live Quest pair,
the sanitized Termux source/privacy profile, and the sidecar configured-peer
plan into a proposal for Manifold's N-peer authority. Quest does not accept
membership or rank routes locally.

The live Quest pair contributes one authenticated direct-P2P candidate. The
configured third peer contributes low-rate advisory edges only. Manifold owns
the three-peer accepted state, coordinator, revision, direct-route ranking,
split-brain/replay rejection, expiry, revocation, and audit. A selected direct
lane remains subject to separate peer/media admission and socket ownership.

Run `tools\checks\Test-NPeerMesh.ps1` for static validation. The private
two-Quest rehearsal is `tools\Invoke-NPeerMeshTwoQuestConfiguredPeer.ps1`.
