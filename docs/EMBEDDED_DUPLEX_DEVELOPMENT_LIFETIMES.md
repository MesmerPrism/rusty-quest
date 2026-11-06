# Development lifetime selection

`Build-SpatialCameraPanelAndroid.ps1 -EmbeddedDuplexLifetimePolicy development-v1`
selects the public semantic contract from the actual Manifold source dependency:
`rusty-manifold-admission/contracts/development-lifetime-policy.v1.json`.
The builder, artifact descriptors, Gradle BuildConfig, Java configuration,
Native configuration, signing policy, admission and accepted Peer policy carry
the same selection. Milliseconds remain signed64/unsigned64 throughout; the
current thirty-day horizon exceeds signed32. Omission keeps finite behavior.

The legacy public Rust config/snapshot/context field shapes stay unchanged.
The selected Quest config uses
`rusty.quest.embedded_duplex.development_authority_config.v1` in its existing
schema field. Native separately requires the compiled source selection, then
passes the exact public policy to additive Runtime/provider constructors.
A marker alone cannot enable a finite build. Selected Admission/Peer restore
requires the same trusted policy; finite APIs refuse selected markers. Accepted
owners retain the policy privately and expose a semantic getter. Intentional
finite configurations remain finite, including the existing diagnostic fixture.

The policy bounds delegated **new effects**. Real expiry, revocation, actual
actor/identity, accepted revisions, signatures, source joins and once/replay
checks remain. Ordinary selected ceremony and review duration estimates warn;
they do not expire the held transaction. Preparation clock waiting can exceed
its estimate while retaining the same current phase. Its actual-time predicate,
clock-difference bound, backward-clock refusal and actual desired Stop remain.
Frame/pose freshness has its independent meaning and is not lengthened here.

The effective deadline is still the minimum of actual parents. Bootstrap grant
and outer lease expiry are issuance time plus the selected root horizon. They
are distinct from the enrolled maximum operation-token TTL. A Start permit is
bounded by that enrolled maximum, the selected command horizon, the grant and
outer lease. Media and route authority are further bounded by the accepted
permit, current Pair, credential/status and route parents. Signed context
backdating is retained, so context expiry is its actual issue time plus the
selected horizon. Renewal must advance real accepted parents through existing
current-authority transitions. After genuine expiry, use real same-key fresh
authorization under its existing no-live-graph/physical eligibility rules;
never label expired evidence current or replay an uncertain Original.

An existing enrollment's token TTL is **unchanged** until an explicit reviewed
metadata update is confirmed. First finish owned cleanup/recovery; changing the
enrollment record while old uncertain work owns that lineage is not a shortcut.
Then the debug operator may call `review_policy_update` with a fresh lowercase
hex32 challenge and no extras. It is a persistent identity operation: `run_id`,
`original_id`, caller TTLs, keys and proof JSON reject. The app reads the current
enrollment and changes only the proposed maximum token TTL to the source policy.
This route and confirmation read existing identity only; missing identity
refuses without creating replacement key material. Ordinary provisioning keeps
its separate identity-creation behavior.
It rejoins actual prior revision, record hash and installed facts. The reviewed
response includes `receipt` with schema
`rusty.quest.embedded_duplex.enrollment_policy_review.v1`, the selected public
policy, actual prior revision/hash, proposed TTL and actual review digest.

Confirmation uses the existing `confirm` method, the **same challenge**, and
only `review_sha256`. The same held review is consumed once. Existing process,
Native, recovery, installed-fact and private-store CAS guards remain; accepted
replacement must advance the revision exactly once. The optional private store
must support this explicit same-key metadata transaction. This is separate from
context rebind, key replacement, NewRun and automatic bootstrap.

Owned cleanup entitlement is independent of all these clocks and expiry values.
Its durable protocol and fresh remote authentication belong to the cleanup
owner. SDK/media wait continuation is a separate owner change. This policy
does not assert platform completion, device qualification or APK readiness.

Focused checks are `tools/checks/Test-DevelopmentLifetimeProducer.ps1` (actual
PowerShell binder and policy producer; optional exact Gradle source block) and
`tools/checks/Test-DevelopmentLifetimePolicy.py` (actual public Java callers and
optional Kotlin parser/output, with explicit Android/store/Native leaves).
Actual Android source/API compilation and shared Manifold authority controls
remain separate evidence; these checks do not build an APK or operate a device.
