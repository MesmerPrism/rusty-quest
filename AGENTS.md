# Rusty Quest Agent Notes

The P70 `lsl-rust-float32-lan-outlet-android` package is an opt-in,
same-LAN, one-channel Float32 Quest-outlet to host-inlet qualification only.
It does not establish the reverse direction or a default runtime feature.
Use its dedicated build/static/device scripts; the device runner retains the
installed package after target-only force-stop.

The `lsl-rust-float32-two-record-chunk-android` app is a distinct public test
package for LSLC-005S. It builds exact clean Rusty LSL for
`aarch64-linux-android` and executes only the accepted one-channel, two-record
Float32 chunk runtime over IPv4 loopback inside Rust. Java owns lifecycle only;
runs require ordered exact-bit evidence, immediate port reuse, zero bounded
fatals, one explicit serial, distinct identities, and exact run-owned cleanup.
It adds no arbitrary chunk, production activation, or compatibility breadth.

The `lsl-rust-float32-loopback-android` app is a distinct public test package
for LSLC-005L. It builds the exact clean Rusty LSL revision for
`aarch64-linux-android` and executes the accepted one-channel, one-record
Float32 handshake/sample runtime over IPv4 loopback inside Rust. Java owns
lifecycle only. Runs use one explicit serial, a distinct package/build/staging
identity, zero bounded fatals, immediate port-reuse evidence, and exact
run-owned cleanup. This adds no production activation or compatibility breadth.

The `lsl-rust-conformance-android` app is a distinct public test package for
LSLC-005H. Its generated native crate binds an exact clean Rusty LSL source
revision and builds only for `aarch64-linux-android`; Java owns lifecycle while
Rust owns the effective core-contract marker. Runs are serial-scoped and must
remove only the run-owned package with zero bounded fatals and complete
package/process/forward/reverse/property/staging cleanup.

This is the clean source repository for Rusty Quest. Keep committed content
self-contained and free of local-only planning paths, downstream app names, and
historical naming drift.

Rusty Morphospace is the top-level project/platform umbrella. This repo remains
the Quest lane inside that umbrella: Quest platform behavior, launch settings,
permissions, device/runtime profiles, Horizon tooling boundaries, and
Quest-hosted operator app validation. Do not introduce `rusty.morphospace.*`
schemas here; use `rusty.quest.*` for Quest platform contracts.

Project-owned source in this repo is licensed `AGPL-3.0-or-later`. Platform
SDKs, APKs, generated binaries, headset logs, and tool downloads need separate
provenance and notice handling.

Use PowerShell `7.6` LTS or newer through `pwsh` for all validation, build,
launch, and evidence wrappers. Windows PowerShell 5.1 is bootstrap detection
only. Do not add new Windows PowerShell child routes; child
processes must resolve the explicit `pwsh` host.

## Purpose

The package updater release product is the Labs-only
`io.github.mesmerprism.rustyquest.packageupdater.labs`. Its signed manifest,
rollback state, endpoint, storage, receipts, target package, APK signer,
Ed25519 key id/public key, and HTTPS origin form one closed tuple. Fleet may
select, authorize, and project only; Quest owns verification, download,
PackageInstaller lifecycle, readback, rollback, and the effective receipt.
Release variants must expose no debug or E2E components.
Publication uses immutable generation directories and one CAS-bound Labs
pointer updated last. The updater targets only the co-installable
`io.github.mesmerprism.rustykiosk.labs` core and must never retarget stable.
The updater product itself releases only from an exact pre-existing
`package-updater-v0.1.0-alpha.N` tag through the protected Labs environment.
Its owner metadata is derived from the closed build manifest plus actual APK
and exposes only tag/version, source revision/tree, Labs installation
identity, tag-derived monotonic APK version, updater signer SHA-256, and primary
asset name/hash/bytes. Public release assets are exactly that APK, metadata,
project license, and exact source notice; promotion is draft-first,
prerelease, non-latest, rechecks the remote tag immediately before promotion,
and never overwrites or deletes an existing release.

Changes to `.github/` or the protected package-updater validation and
publication surfaces use the two-PR trust-root route in
`docs/EXTERNAL_VALIDATION_AUTHORITY.md`. The base-owned workflow performs
static Git-object admission only; it never executes candidate code, attests
dynamic validation, or authorizes publication. Do not add approvals directly
to an implementation PR. Before sealed candidate `I` exists, the live bootstrap
policy must contain zero approvals. After independent audit of immutable `I`,
the final bootstrap commit may carry exactly one approval named
`bootstrap-sealed-candidate-i`; validate it with
`-ExpectedBootstrapApprovalAncestor I`. The fixture is inert test data, never
live authority.
The separate credential-free `pull_request` workflow executes the exact
candidate head only for formatting, package-updater Rust tests, and the Android
static gate. Its pass is test evidence, not effect, acceptance, or publication
authority.

Configure admission from
`config/external-validation-authority-settings.json`. Rusty Quest is user-owned;
organization required-workflow rules are future-only. The deployable repository
rule binds `Static admission` to GitHub Actions App ID `15368` but does not bind
workflow path or event and is never authoritative without the mandatory
same-name/same-App adversarial PR probe. Run both success-before-failure and
success-after-failure orderings, API-inventory both workflow and check runs,
require `mergeStateStatus=BLOCKED` both times, use no bypass or merge attempt,
close unmerged, and keep the observed receipt private. Any ambiguity,
mergeability, missing run, ordering inconsistency, or bypass holds candidate,
publication, release, and Pages environments. The ordinary dynamic workflow is
supplemental and non-authoritative.

Validation-authority history uses ordinary merge commits throughout. The first
bootstrap PR, every policy PR, and every candidate PR must land through an
ordinary two-parent merge commit. Seal the implementation commit `I`; merge
bootstrap/policy `main` into its branch without rewriting `I`; then merge the
candidate normally. Squash, rebase, merge queue, amend, replacement, and
force-update routes are forbidden. After merge, prove `I` is an ancestor of
`main` and the exact approval is consumed or retired.

Secret-bearing environments are closed independently: publication is
protected-`main` only; updater signing is limited to protected
`package-updater-v0.1.0-alpha.*` tags whose peeled commits are reachable from
`main`; and `github-pages` accepts only the protected same-run publisher to
exact-feed-commit deployment lineage. Pull-request workflows never reference
those environments. The floating `windows-2025` runner has no accepted image
allowlist; the static assessment records exact observed Git, PowerShell, and
runner identities with drift explicitly `observed-unpinned`.

The live feed is served from the Rusty Quest project Pages prefix
`/rusty-quest/package-updates/rusty-kiosk/labs`. The origin, exact project
prefix, feed path, and target tuple are build-fixed. A bounded refresh may
advance only sequence and signed issue/expiry times while retaining the exact
authenticated prior APK identity; installation rollback remains strictly
version-advancing.

Rusty Quest owns platform profile contracts and write/readback transports. It
does not own Makepad widget implementation, Matter simulation truth, Optics
appearance truth, Manifold command authority, or Lattice relation contracts.

## Runtime Surface Default

For new Quest runtime work, prefer native OpenXR/Vulkan and Meta Spatial SDK
apps in this repo. Keep reusable hand, space, mesh, visual, command, and report
contracts in Lattice, Matter, Optics, Manifold, GUI, and Hostess before adding
Quest adapters.

Do not add new Makepad compatibility shims, profile surfaces, or Quest-Makepad
parity work here unless the user explicitly asks for Makepad migration,
regression repair, or historical evidence replay. When old Makepad evidence is
useful, port the accepted contract, marker, fixture, or scorecard shape into a
native Quest path.

## Read Order

1. `README.md`
2. `docs/ARCHITECTURE.md`
3. `docs/VALIDATION.md`
4. `fixtures/README.md`

For a protected validation, workflow, policy, schema, publisher, or package
updater authority change, also read `docs/EXTERNAL_VALIDATION_AUTHORITY.md`
and `docs/PACKAGE_UPDATE_LABS_DISTRIBUTION.md` before editing.

For APK builds or repeated same-headset runs, also read
`docs/APK_RUN_ISOLATION.md`. Locked builds use app-specific package/client
identities, explicit inputs, clean exact source, content-addressed outputs, and
a hashed run capsule. Launch wrappers serialize per serial, apply complete
property closure, stop only the target package, and restore exact prior values.

For work in `apps/spatial-camera-panel-android`, then read its
`morphospace/project.spec.json`, `feature.lock.json`, `workspace.state.json`,
and the current iteration unit before source. This directory is shared public
adapter source, not a single consumer-project authority. Its live workflow
entrypoint is the inert v2 index: the panel shell remains its only selected
baseline, nearby particle/hand/camera/media/asset/room families are explicit
disabled entries, and unlisted features remain inert. Private downstream effect
projects resume from their own private project workspaces, while Spatial VR
Strobe resumes from `apps/spatial-vr-strobe-android/morphospace/`. The complete
mixed v1 camera ledger is integrity-bound under
`apps/spatial-camera-panel-android/legacy-workspaces/mixed-integration-v1/`;
neither that history nor Strobe state may gate another project.
An app-owned, content-addressed build may select explicit normal-launch camera
projection, encrypted immersive-video pack, and zone-compositor defaults.
Those selections must be recorded in the build lock and BuildConfig, while the
shared adapter defaults remain inert and runtime panel controls remain
independent.
The staged Spatial asset lane is also lock-bound: a GLB/GLTF URI or legacy
enable property alone must stay inert unless the app-owned
`spatial-asset-model.feature.lock.json` and its exact runtime identity tuple
apply first.

Spatial Camera Panel and Spatial VR Strobe are mutually exclusive public adapter products. A
build must select one product identity and therefore one package, client,
feature-lock, marker, property, intermediate-build, and output namespace.
Ambient Android properties cannot switch products. Run
`tools/checks/Test-SpatialProductIsolationStatic.ps1` when changing this
boundary.

For corrective WF-005 reconciliation, inspect the archived Camera integration
workspace and the independent Native Renderer workspace:
`apps/spatial-camera-panel-android/legacy-workspaces/mixed-integration-v1` and
`apps/native-renderer-android/morphospace`. Their historical default locks
remain inert; particle and hand families may appear only in explicit
conformance locks. Run
`tools/checks/Test-SpatialCameraPanelWorkflowStatic.ps1` and its self-test for
workspace changes. Local MOD-006 source validation is device-pending and must
not be presented as central promotion or device acceptance.

`crates/rusty-quest-feature-activation` is the sole generic closed-world parser,
exact-lock digest binder, dependency/conflict checker, and common rejection
engine for reusable Quest features. Module adapters expose nominal private-inner
decision types and own only selector/receipt/marker policy; applications own the
accepted project, feature, module, profile, lock digest, and resulting effects.
Do not copy the parser or use one adapter family's decision at another effect
gate. See `docs/FEATURE_ACTIVATION.md` and keep both adapter static gates current.

For the Spatial surface-particle candidate, reuse Matter's existing particle
and surface-runtime contracts. Matter owns state, simulation, force-source
selection, deterministic diagnostics, snapshots, and render-neutral payloads;
Lattice owns situated relation snapshots; Optics owns appearance/projection;
Quest owns Vulkan/Spatial/Android adapters and effective markers; the app owns
composition and private policy. Do not create a parallel app-derived particle
schema or move renderer/platform code into Matter.

`crates/rusty-quest-particle-adapter` is the accepted Quest-side handoff for
that family. It consumes Matter render payloads, Lattice situated anchors, and
Optics visual frames, then produces renderer-neutral instance rows and a
low-rate receipt. Spatial Camera Panel and native renderer are explicit
consumers; both remain disabled by default, and app policy, Vulkan resources,
private drivers, and high-rate control stay outside the adapter contract.

`crates/rusty-quest-hand-adapter` is the accepted Quest-side handoff for hand
substrates. It validates Lattice provider/frame identity, maps joints into the
Matter rig, checks prepared rows against the Matter CPU oracle, and preserves
Optics provider/frame/rig/hand identity. Native and Spatial acquisition and app
policy stay local; provider, basis, hand, rig, or joint substitution fails closed.
The native renderer's optional simultaneous hands/controllers adapter must use
its existing OpenXR lifecycle, the exact applied hand lock, and independent
live hand plus controller readiness; it must not request detached controllers.

`crates/rusty-quest-broker-product` is only the Android projection boundary for
accepted Manifold broker product locks. Manifold owns product feature resolution,
runtime mode, commands, streams, modules, and the exact permission closure. Quest
maps that accepted permission enum into an exact manifest projection; it must not
union permissions, silently add optional capabilities, or accept a stale lock.
Generic `media_session` remains camera-free. Camera, direct-P2P, and BLE
products remain separate explicit opt-ins, while the base broker stays
camera/P2P/BLE-free. `Build-ManifoldBrokerAndroid.ps1` must consume an exact
spec/lock pair, generate the actual app manifest and command registry, and
package their lock-stamped receipts; it must never fall back to an ambient app
manifest. The broad camera/P2P validation surface is legacy compatibility and
requires its explicit switch.

`crates/rusty-quest-broker-authority` is the trusted local process/JNI
projection over `ManifoldBrokerRuntime`. Real standalone and embedded JNI
surfaces retain one process-local provider, exact product lock, admission
  state, bounded-use permits, Runtime Host, and one non-cloneable
  `ManifoldBrokerControlLeaseAuthority`. Fresh v2 initialization must use the
  Android wall/monotonic clock and generic Manifold review/application for
  every product-requested initial lease. Reject expired, duplicate, or
  unreproducible requests; released v1 raw-lease configs require rebuild and
  must never be silently reinterpreted. Every server mutation carries
  the live provider epoch plus one signature-scoped use id, its opaque token id,
  and that use's creation revision; Rust binds it to the exact client/command
  capability, consumes it,
then performs the single Runtime Host review/apply attempt. Java/WebSocket/JNI
must not write `accepted`, invent Manifold authority labels, or execute a
platform effect before the Rust receipt applies. Same-provider rebind preserves
  state; provider process restart requires a fresh entropy-derived epoch.
  Unrelated admission revision advances must not invalidate another client's
  pending use; revocation/expiry invalidates only uses derived from the exact
  affected token.

`crates/rusty-quest-broker-transport` is the single Android-compatible
RFC6455 transport owner for standalone and embedded brokers. Keep upgrade and
frame validation, bounded per-client message/byte queues, one isolated writer
per socket, Ping/Pong/Close deadlines, cancellation, cleanup-exactly-once, and
sanitized transport telemetry there. Keep each app's HTTP/socket acceptor as a
placement adapter. Do not move JSON semantics, Binder identity/admission,
Manifold decisions, command outcomes or platform effects, or media payloads
into the transport core. Run the focused transport gate and both placement
gates on the host before any device work.

  Product runtime config is packaged authority, not a settings payload. Builds
  embed the exact accepted product spec/lock and exact client locks with their
  SHA-256 bindings, derive each grant from the product/client intersection, and
  embed the canonical runtime-config digest consumed by JNI. Base products must
  not gain media/sink/peer grants; camera-free media may gain only selected
  media/sink grants. Embedded Native Renderer rejects settings-supplied config
  and authenticates its own Android package plus single signing certificate
  before Rust issue/use/mutation.

  A product that selects `media_session` must package exact canonical Manifold
  descriptor and Quest runtime-spec bindings. The runtime spec must close over
  independently selected source, processor, route, socket, codec, sink, and
  cleanup owners; Camera2 and Direct-P2P providers also require those exact
  product features. Runtime Host command acceptance prepares a receipt-bound
  action with `platform_effect_completed=false`. Only an exact owner completion
  applied by Rust may advance receiver-first start or cleanup-last stop state.
  Media preparation and refresh must borrow the same synchronized live Broker
  runtime; do not clone it, fabricate lease lineage, or bypass the public owner
  and mutation APIs.
  Generic media must never route through `RemoteCameraSessionRuntime` or inherit
  its properties, defaults, permissions, or command aliases; that runtime is an
  explicit compatibility branch only. See `docs/MEDIA_SESSION_RUNTIME.md`.

Cross-app product admission uses the signature-scoped Binder service in
`apps/manifold-broker-android` and the thin
`crates/rusty-quest-broker-admission` projection. Android derives the immediate
caller UID, package, and signing-certificate SHA-256; Manifold owns the grant,
256-bit opaque token, capability subset, revision, replay, expiry, revocation,
and audit decision. The service must not contain capability/grant policy.
Require exactly one packaged grant for the OS-derived package-and-signer
subject; duplicate subjects fail before token issue or runtime-config
publication. Drive each client through one serialized reducer with fenced
process/binding/session generations, broker epoch, operation/attempt/correlation
ids, bounded monotonic deadlines, and cleanup exactly once. Retry only read-only
evidence or byte-equivalent registration; never blindly replay a relative
effect. See `docs/CONNECTION_HUB_BINDER_ADMISSION.md`.
Device validation requires a same-signer lifecycle, a differently signed
permission denial, zero package fatals, and uninstall cleanup on every serial.

Independent product apps consume that surface through
`crates/rusty-quest-broker-client`. Each app must declare a distinct client id,
package subject, feature lock, marker namespace, and app-local sink capability;
the shared SDK may carry only the exact peer/media contract families and the
signature permission. Capability lists are canonical sorted sets. Repeated
service binding must preserve the live Manifold admission and Runtime Host
revisions. Client commands must be built from the Binder use receipt with
  `build_broker_mutation_request`; ungranted commands, copied client ids, stale
  revisions, old epochs, and reused use ids fail closed. Validate
native renderer and Spatial Camera Panel together with
`tools/Invoke-MultiAppBrokerClientTwoQuest.ps1`; require both lifecycles,
distinct Android app ids, no cross-marker/default/property bleed, zero
  package/system fatals, and complete uninstall cleanup on both serials.
  Each client process/Activity launch creates a 128-bit `SecureRandom` request
  namespace; only an explicit replay probe may reuse one request id. Runtime
  Host requests bind canonical typed effect params (maximum 4096 bytes) through
  dispatch/application receipts, and Java consumes only Rust-returned
  `effect_params` after acceptance.

App-specific Connection Hub surfaces require an owner-packaged provider grant
bound to the app's real Android package. One Android package-and-signer subject
must map to exactly one canonical client lock and grant; a selected product may
add that app's provider-registration capability to the canonical grant, but
must not create a second indistinguishable subject or accept caller-supplied
client identity. Never copy an example client, provider, surface, or marker
identity. The locked-playlist compatibility surface registers only fixed
empty-argument commands and bounded scalar state. Provider-private lists,
arbitrary selectors, paths, profile ids, effect parameters, and downstream-
private names remain outside the Hub product contract. A richer parameter or
browser surface requires a separately reviewed Hub-owner contract evolution.

The normal Connection Hub product must retain a fixed shell-UID operator
provider for `start`, `stop`, `status`, `pair`, `revoke`, and `forget`. Route it
through the same controller and effective-state confirmation used by wearer
actions; accept no caller-selected component, identity, grant, capability, or
arbitrary command. Keep credentials outside receipts and markers, and report
mutations as sent, pending, then confirmed, rejected, or `outcome_unknown`.
The optional debug provider remains a separate test-only surface.

Product Wi-Fi Direct topology lives in `apps/direct-p2p-provider-android`.
Android Wi-Fi P2P owns credentialed temporary group formation,
`AndroidNetworkBindingProvider` reports whether the platform exposes a usable
`Network`, and the Rust native provider alone owns explicit `p2p0` bind,
bounded socket exchange, and close. A missing Android `Network` is a truthful
`network_available=false` receipt, not permission to fabricate a handle or
substitute Android socket ownership. The product app must not depend on the
connectivity-lab harness or enable media. Validate with
`tools/Invoke-DirectP2pProviderTwoQuest.ps1` and require both typed receipts,
inactive cleanup, and zero package/system fatals.

When peer-session gating is enabled, `rusty-quest-peer-session-adapter` only
projects authenticated BLE pair evidence into Manifold. The product must
validate Manifold's fresh topology authorization, exact current revision,
topology contract, and local peer role before initializing Wi-Fi P2P; rejected,
stale, expired, or revoked receipts must leave topology inactive. Validate the
decision matrix with `tools/Invoke-PeerSessionDecisionGateTwoQuest.ps1`.

The adapter's N-peer projection may combine a live authenticated Quest pair
with one sanitized configured-peer observation, but remains a proposer.
Manifold owns membership, coordinator, revision, route ranking, split-brain,
expiry, revocation, direct-lane eligibility, and audit. Termux and sidecar
inputs stay source/privacy/advisory only; they never authenticate a direct
route or carry media. Validate with
`tools/Invoke-NPeerMeshTwoQuestConfiguredPeer.ps1`.

Fleet check-in production lives in `crates/rusty-quest-fleet-agent` and the
permission-minimal `apps/fleet-agent-android` adapter. Keep the default profile
inert, require an explicit Hub endpoint and enrolled signing identity, preserve
host-owned receive time, and report arbitrary foreground state as unknown
unless an explicitly participating app supplies its own evidence. The baseline
must not add ADB, package-query, accessibility, storage, camera, microphone,
media, or command-listener authority. Manifold accepts peer status and Fleet
accepts the device projection; the Quest producer accepts neither.
The supported Windows key-record helper release is a separate clean-source,
portable, exact-file capsule. Keep its owner manifest, provenance, executable,
license, notice, and checksums closed and byte-bound. It contains no private
seed, profile, Hub configuration, enrollment, activation, reachability, lease,
or peer-acceptance evidence. The machine-bound developer-tool manifest remains
build/test evidence only and must never be consumed as a release capsule.
Build it only from isolated exact Git-object materializations, and reject ASCII
or UTF-16 machine-local paths in the executable before distribution.
Keep Cargo workspace parse-only sibling repositories in a separate closed
provenance set; they never widen the helper's dependency or runtime authority.

Generic media adoption lives in `rusty-quest-media-stream`. Manifold owns the
accepted session/stream descriptor; the Quest runtime owns only receiver-first
platform lifecycle after the accepted decision. Sources, processors, direct-
P2P route references, and sinks are explicit, independently validated, and
free of app policy. `rusty-quest-remote-camera` remains a compatibility adapter
that maps into this runtime; do not copy its properties or defaults into new
source, processor, or sink descriptors.

Spatial Camera Panel offline immersive media is a separate local adapter
boundary documented in `docs/SPATIAL_IMMERSIVE_VIDEO_PLAYBACK.md`. Keep its
encrypted pack catalog generic, bounded, opt-in, and free of private media
names. In direct video-only mode, each item owns its ideal Spatial SDK shape
and stereo mode; switching may rebuild only that world-centered media surface
and decoder, never head-lock an immersive surface, and must require neutral
rearm between right-stick selection flicks. When video and the custom camera
projection are both active, keep them on coordinated carriers: the video uses
its declared world-anchored flat/180/360 surface or an explicitly selected
legacy head-fixed background quad, while the custom camera/effect compositor
retains its planar stereo carrier and camera mapping. The video carrier uses a
strictly lower Spatial SDK z-index than the custom projection, and the control
panel remains above both. Video selection or
presentation changes may rebuild only the video surface and decoder; they must
retain the Activity, planar camera carrier, control state, and current private
configuration.

The reusable RGB-channel spatial transform is documented in
`docs/RGB_CHANNEL_TRANSFORM.md`. Rusty Quest owns only its bounded neutral
configuration, controls, JNI/Vulkan transport, ABI, and effective markers.
Consuming projects retain their signal derivation, color-to-strength mapping,
artistic tuning, and final distortion/compositor formulas. Keep those formulas
out of this public repository and run the dedicated RGB static check whenever
the ABI or controls change.

The reusable projection-surface displacement transport is documented in
`docs/PROJECTION_SURFACE_DISPLACEMENT.md`. Rusty Quest owns its bounded,
disabled-by-default controls, 32x32 tessellated draw, optional private vertex
slot, JNI/Vulkan transport, exact fullscreen fallback, and effective markers.
Downstream projects own the displacement field, signal-to-depth mapping, and
tuning. The Spatial SDK carrier remains planar; do not claim compositor-space
mesh or environment-depth geometry. Run the dedicated projection-surface
static check whenever this ABI or pipeline selection changes.
authority.

For release-candidate broker recovery, distinguish client death from authority
process death. A stopped client may rebind to the existing authority revision;
after an explicit broker process stop, clients must rebuild from their exact
product locks and grants at a fresh authority epoch. In both cases replay and
post-revocation use must remain rejected, client UIDs and marker namespaces
must stay distinct, and cleanup must remove all test packages. Validate both
connected devices with
`tools/Invoke-BrokerAdmissionDeathRecoveryTwoQuest.ps1`; its dedicated 2D
clients avoid an unrelated 6DoF launch dependency, and its provider restart is
a deliberate safe rebuild, not evidence of persisted in-memory authority.

For the final proportional two-Quest release matrix, use
`tools/Invoke-CorrectedReleaseTwoQuestMatrix.ps1` and the focused contract in
`docs/CORRECTED_RELEASE_TWO_QUEST_MATRIX.md`. Production requires exactly two
explicit serials, the exact clean Rusty Quest revision, current built broker,
Native, and Spatial APKs, and the mandatory live
`tools/Invoke-ManifoldPeerAuthorityTwoQuest.ps1` provider. Do not substitute
legacy BLE/session/QCL/Termux/sidecar evidence for on-device keys, reciprocal
signatures, current Manifold enrollment/rendezvous revisions, topology
authorization, a real direct-lane lease, rotation/revocation/replay negatives,
direct exchange, inactive cleanup, or zero bounded fatals. Run
`tools/checks/Test-CorrectedReleaseTwoQuestMatrixStatic.ps1` for source-only
contract and damaged-input checks; it must not contact a headset.

## Agent Board

Read-only source inspection and dry-run profile validation do not require Agent
Board. Use Agent Board only when the user explicitly asks for shared-resource
coordination or when a task actually uses headset, ADB lifecycle, APK build,
logcat, screenshots, Perfetto, or shared bridge ports.

Routine device ADB commands must be serial-scoped with `adb -s <serial>` or the
wrapper `-Serial`/`RUSTY_QUEST_SERIAL` inputs. Reserve `quest:<serial>` for
same-headset install, launch, screenshot, headset-bound logcat, Perfetto, and
runtime validation. Reserve `adb-server:lifecycle` only for disruptive daemon
operations such as `adb kill-server`, `adb start-server`, reconnect/recovery,
Wi-Fi ADB setup, or ADB server path/port ownership changes; do not serialize
ordinary serial-scoped ADB work behind a global `adb-server` lease.

## Sustainable Design Guardrails

- Treat monolithic file pressure as an ownership problem, not a line-count
  problem. Split only by durable authority, schema, route, validation, adapter,
  or test-family boundaries; preserve facades, schema IDs, serde fields,
  fixture outputs, CLI behavior, validation outcomes, and dependency boundaries.
- Keep Quest runtime features explicit opt-in. Native OpenXR/Vulkan and Meta
  Spatial SDK modules may be present in the source tree, but they must not
  affect an app package, permissions, runtime profile, scene graph, input route,
  marker stream, media path, or private payload behavior unless a feature
  descriptor, app spec, runtime profile, Android property, or intent extra
  explicitly enables that feature.
- Keep camera replay capture finite and explicit-opt-in. Store packed frame
  data only in the app-private external-files tree, emit bounded manifest and
  completion markers, and treat captures and pulled copies as local artifacts
  that never enter source control.
- Do not build or launch a new project from loose APK/profile inputs or shared
  package defaults. Resolve/build from an exact app lock, validate the run
  capsule, and keep package, marker, property, staging, and build identities
  distinct from other projects.
- After a split, update the nearest distributed file map: this `AGENTS.md`,
  `README.md`, `docs/ARCHITECTURE.md`, fixture docs, validation docs, or the
  planning `agent-state\iteration-events.jsonl`.
- Keep `AGENTS.md`, README, and skill files as concise routing indexes. Move
  lane-specific recipes, device/build detail, compatibility ledgers, and long
  validation flows into named docs or runbooks.
- Keep legacy Rusty-XR names as explicit compatibility surfaces only. New
  schemas, routes, and types use the owning lane (`rusty.manifold.*`,
  `rusty.lattice.*`, `rusty.matter.*`, `rusty.optics.*`, `rusty.quest.*`, or
  repo-local names); do not introduce `rusty.morphospace.*` schemas or
  `Morphospace*` core types by default.
- Android property writes are transport generated from validated
  `rusty.quest.runtime_profile.v1` inputs. `getprop` readback proves only the
  transport layer; the consuming app must also emit the matching effective
  setting, marker, or command receipt before the value counts as accepted
  runtime behavior.
- Route one-sided Meta Spatial SDK UI-panel placement through the shared
  front-face convention in `docs/SPATIAL_SDK_PANEL_FACING.md`. Keep UI-panel
  facing separate from scene-quad/material orientation, emit the effective
  facing marker, register only the panels active for an exclusive feature
  route, and require headset visibility confirmation after a change. Layer
  z-index is not a substitute for an attended foreground contract over a
  full-field custom material. A nearer, proportionally scaled panel requires
  separate comfort and controller-ray proof; if that proof fails, retain the
  comfortable panel pose and suppress the competing carrier while the panel is
  visible without changing its output lifecycle. Do not rely on ECS
  `Visible(false)` alone to isolate an unrelated compositor panel; hide its
  native scene object and remove app actions that a reserved global controller
  shortcut must never invoke. Give each reserved physical shortcut one
  authoritative action arbiter. Multiple platform observations may feed that
  arbiter when no single route is proven complete, but they must share physical
  edge state or a bounded cross-route deduplication window; never discard a
  working key/motion route merely to make a separate snapshot route exclusive.
  Emit action-to-render receipts; a host-side SceneObject assignment or a
  completed series of `SceneMaterial.setAttribute` calls proves submission,
  not visible renderer adoption. Keep interactive material deltas bounded,
  separate submission from a later frame-boundary observation, and retain
  attended visibility as the final proof. When a comfortable physical panel must overlay a
  view-locked carrier, scaling carrier distance and geometry by the same ratio
  may preserve angular coverage while restoring depth order; clamp its nearest
  distance behind the panel and require attended occlusion proof. Treat a
  reference UI's random editor bounds as outer compatibility limits, not proof
  of numerically valid or performant mobile shader profiles.

## Validation

Run:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-ApkRunIsolationStatic.ps1 -RepoRoot .
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-FleetAgentAndroid.ps1 -Tier Host
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-FleetAgentKeyRecordRelease.ps1 -CapsuleRoot <owner-capsule>
```

The Spatial Camera Panel wrapper runs its focused workflow gate before the
large legacy static ledger:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-SpatialCameraPanelWorkflowStatic.ps1 -RepoRoot .
```
