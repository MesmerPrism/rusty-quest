# Rusty Quest Agent Notes

Rusty Quest owns Quest/Android/OpenXR/Spatial SDK platform adapters, packaging,
permissions, runtime profiles, launch transport and effective platform receipts.
Manifold owns accepted command/session/stream authority; Lattice, Matter and
Optics own their reusable contracts. Apps own composition and private policy.
Use `$rusty-morphospace` for routing. Adopted external planning workspaces own
current units and lifecycle; embedded predecessors remain historical.

This is clean public AGPL-3.0-or-later source. Keep machine paths, downstream
private names, SDK/APK binaries, signing material and raw headset evidence out
of commits. Platform assets need separate provenance/notices. New platform
schemas use `rusty.quest.*`, not `rusty.morphospace.*`. Rusty XR/Makepad names
remain explicit compatibility surfaces; new Makepad parity needs user scope.

## Select the relevant contract

Start with README and the nearest owner instructions. Read ARCHITECTURE,
VALIDATION and fixture docs when their surface is involved. Long product and
historical details are retained in [conditional owner contracts](docs/agent-instructions/owner-contracts.md);
read only the applicable section alongside its focused guide.

| Work | Required focused route |
| --- | --- |
| Protected validation, workflow, policy, schema, publisher or updater authority | `docs/EXTERNAL_VALIDATION_AUTHORITY.md`; `docs/PACKAGE_UPDATE_LABS_DISTRIBUTION.md` |
| APK builds or repeated headset runs | `docs/APK_RUN_ISOLATION.md`; project lock and hashed run capsule |
| Spatial Camera Panel workspace or feature selection | Its inert v2 index/spec/feature lock/workspace/current unit; `docs/FEATURE_ACTIVATION.md` |
| Generic media and Android AAR consumers | `docs/MEDIA_SESSION_RUNTIME.md`; `docs/MEDIA_STREAM_RUNTIME.md` |
| Embedded duplex native fences or debug operators | `docs/EMBEDDED_DUPLEX_R5_CONTINUITY.md`; conditional debug-host receipt boundary |
| Binder admission and app clients | `docs/CONNECTION_HUB_BINDER_ADMISSION.md` |
| Product-specific rendering, UI, particles, hand, Fleet or connectivity | Corresponding section of the conditional owner contracts and linked guide |
| Source-only publication | Adopted WEF `docs/SOURCE_ONLY_PUBLICATION.md`, each owner’s required PR checks |

For private downstream effects, resume their private project workspace.
Spatial VR Strobe uses its own `apps/spatial-vr-strobe-android/morphospace/`.
The mixed v1 Camera ledger is integrity-bound historical evidence and must
not gate another project. Unlisted/disabled features remain inert.

## Invariants for implementation and effects

Prefer native OpenXR/Vulkan and Meta Spatial SDK. Select explicit closed
feature, app, runtime and client locks before packaging or effects. Keep
package/client/marker/property/intermediate/output namespaces distinct.
Spatial Camera Panel and Spatial VR Strobe are mutually exclusive products;
ambient properties cannot switch them. Reuse the generic feature-activation
parser and nominal adapter decisions instead of copying authority.
The shared owner is `crates/rusty-quest-feature-activation`.

Keep Quest runtime features explicit opt-in: they must not
affect an app package, permissions, runtime profile, scene graph, input route,
marker stream, media path, or private payload behavior unless a feature
descriptor, app spec, runtime profile, Android property, or intent extra
explicitly enables that feature.

Keep reusable computational, relation and visual truth in its owner. Platform
adapters project accepted decisions and complete only their own effects.
Runtime property readback proves transport; app-owned effective markers prove
adoption. Generic media uses explicit source/processor/route/socket/codec/sink/
cleanup closure and receiver-first start, cleanup-last stop. Compatibility
remote-camera defaults do not become generic media authority. Do not hold
authority/registry locks across re-entrant callbacks or clone live Broker
authority to fabricate lineage.

Debug receipt/operator providers remain debug-only, DUMP plus shell-UID
gated and closed typed calls. Release source/artifacts contain neither debug
classes nor authority suffixes. Debug fixture, bootstrap or peer-session
receipts alone prove no media Start, stereo presentation or reciprocal duplex.
Follow the exact conditional contract before changing a provider.

Camera import caches must remain identity/generation/descriptor bound, fence
retired and buffer-removal aware; disable reuse fail-safely on bookkeeping
overflow. Apply the linked rendering contract when changing that path.

Protected authority and secret-bearing release routes retain their existing
trust-root/external-owner admission, exact Git-object inventories and ordinary
two-parent merge history. Static admission is inert and cannot attest dynamic
validation or authorize effects/publication. Test passes remain test evidence.
Do not rewrite sealed candidates or replace admission with agent approval.
Read the focused authority guide before touching those surfaces.

## Execution and validation

Continue within existing user/session authorization; owner boundaries decide
where results live. Confirm missing intent or scope expansion. Preserve exact
accepted checkpoints and mandatory live guards; unchanged reviewed inputs need
no extra aggregate before each lifecycle step.

Use PowerShell 7.6 LTS or newer via explicit `pwsh`; Windows PowerShell 5.1
is bootstrap detection only. Before exclusive headset, long APK/build, ADB
lifecycle or shared-port work, use applicable machine coordination. Serial-scope
device commands. Reserve `quest:<serial>` for exclusive headset work and
`adb-server:lifecycle` only for disruptive daemon/transport changes.
Read-only source inspection needs no reservation.

For documentation-only edits, run `git diff --check`, the public boundary scan
(`python tools/check_quest_boundaries.py`) and verify changed links against
owners. For implementation changes use `docs/VALIDATION.md` and the affected
focused gates; `pwsh -NoProfile -File tools/check_all.ps1` is the complete
host aggregate. APK isolation, Fleet host/release capsule, workflow and device
gates apply to their selected surfaces. All mandatory hosted PR checks still
apply. Host/typecheck/static passes do not prove device, camera, codec, LAN,
duplex, performance or publication readiness.

Keep entrypoints concise and update affected owner docs after changes.
Split by durable authority/interface/test boundaries, preserving schemas,
facades, fixtures and behavior. Do not load unrelated compatibility ledgers
or device recipes as a default startup checklist.
