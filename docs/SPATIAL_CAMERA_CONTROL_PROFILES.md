# Spatial Camera Control Profiles

The Spatial Camera Panel accepts one bounded, versioned JSON control profile
while its custom projection route is running. The profile is a low-rate
control file, not a replay capsule, camera recording, media library, or shader
payload.

The public schema is:

`rusty.quest.spatial_camera_panel.control_profile.v1`

The panel also has a separate operator-owned named profile library. Its
human-readable interchange schema is:

`rusty.quest.spatial_camera_panel.profile_bundle.v1`

It carries the selected output-layer override, projection scale, generic zone
compositor controls, RGB-channel transform transport, and neutral
projection-surface displacement, tiling, and inner-alpha controls. Effect
formulas, guide packing, shader assets, captures, media, and product tuning
stay with the provider.

Rusty Quest is the sole owner of profile conversion, validation,
serialization, hotload application, and effective receipts. Hostess-authored
desktop states use `rusty.hostess.projection_replay_control_state.v2` and
contain capsule-native 24/16/92-float ABI blocks rather than Quest-shaped
controls. A state may also carry the additive 32-float projection-surface
uniform: its first 16 floats must exactly repeat the displacement block and
its final 16 floats are the public v2 suffix. Convert one with the Quest-owned
adapter:

```powershell
pwsh -NoProfile -File tools\Convert-HostessReplayControlState.ps1 `
  -InputPath <state.replay-control-state.json> `
  -OutPath <profile.profile.json> `
  -GradleHome <gradle-9.4.1>
```

Both byte ingress routes decode UTF-8 with malformed and unmappable input
reported as errors, validate the complete strict JSON grammar, and reject
duplicate object keys at every nesting level before constructing an
`org.json` object. Escaped and literal spellings of the same decoded key count
as duplicates, trailing non-whitespace content is rejected, and nesting is
bounded to 64 containers including the root object. Integral metadata uses
exact conversion and range checks; fractional, rounded, overflowing, and
wrapping `revision` or `created_unix_ms` values fail closed. The converter then
rejects unknown fields, wrong block lengths, non-finite values, unsupported
tokens and unsupported effective combinations before running the
result through the authoritative Quest profile parser. It maps only the
public, effect-neutral transport values; provider-private meanings and tuning
do not enter the output. The PowerShell route invokes the production
`convertHostessReplayControlState` JVM task with explicit input/output
arguments. `-GradleHome` (or `GRADLE_HOME`) permits a clean detached source
materialization to use an externally provisioned exact `gradle-9.4.1`
distribution; the repo-local ignored tool cache remains the fallback. The
production main writes a temporary sibling and requires an
atomic rename before the final profile becomes visible.

The generic `zone_compositor` object may carry an optional integer
`stretch_option_flags`. Rusty Quest treats the bounded value as an opaque,
default-zero provider extension and preserves it through profile conversion,
hotload, JNI, and the existing zone uniform. Bit 1 remains reserved for the
separate public `projection_effect_edge_guard_enabled` control and is masked
out of the opaque field. Public code and documentation do not assign effect
semantics to the remaining bits; the provider owns those meanings. Omission
is the exact compatibility identity.

The same object carries `center_corner_radius_uv` in the normalized `0.0..0.49`
range. It rounds only the compositor-owned Center boundary; it does not reshape
the Middle-to-Outer boundary or legacy attachment geometry. The native
416-byte zone uniform stores Center content, projection mix, this radius, and
the effective shader output-alpha convention in its appended `center_content`
vec4. Fixed-function same-surface blending always consumes premultiplied shader
output. A compositor-owned POST_MULTIPLIED route may emit straight output only
when that additional blend is absent. Transparent underlay is effective only
for explicit PRE_MULTIPLIED or POST_MULTIPLIED swapchain support; ambiguous
INHERIT and OPAQUE modes retain each requested Center Transparent, Middle
transparent-reveal, or Outer Transparent setting in receipts but render a
readable video-underlay fallback for every affected lane.
Legacy v1/v2 Outer transparency keeps its requested profile fields and receipt
identity, while an unsupported effective route selects video through the
legacy outer-target lane and clears the v2 stretch-extent flag. Existing
`projectionZoneOuterUnderlaySupported` and `projectionZoneUnsampledOuterData`
markers remain strictly Outer-specific; the render receipt's broader
transparent-underlay fields cover all v4 lanes.

State v2 is preferred and carries an opaque descriptor-keyed
`control_transport` value map bound to its transport identifier and capsule
SHA-256. Quest validates that envelope but does not interpret provider labels
or destination mappings, and the exported Quest profile remains byte-for-byte
equivalent for the same capsule-native blocks. State v1 remains read-only
compatibility input; a v1 document containing the v2 `control_transport`
envelope or the additive surface-feature uniform is rejected.

## Additive Surface Controls

`projection_surface_tiling` and `projection_inner_alpha` are optional
`quest_controls` objects. Omitting either object produces its exact disabled
compatibility identity.

The tiling object contains:

- `enabled`;
- `topology`: `continuous`, `tiled`, or `triangle-tiles`; `tiled` uses one
  center per square grid cell, while `triangle-tiles` uses separate centers
  for the two existing triangles without changing the 32 by 32 grid or the
  6,144-vertex draw;
- `gap_normalized`: `0.0..0.45`;
- `depth_flexibility`: `0.0..1.0`, where zero requests one depth value per
  tile and one requests the per-vertex depth path; and
- `scope`: `inner-and-buffer` or `core-only`; the legacy
  `core-and-stretch` spelling remains accepted as an input alias.

The inner-alpha object contains:

- `enabled`;
- `driver`: `red`, `green`, `blue`, `luma`, or `max`;
- bounded `threshold`, `softness`, and `amount`;
- `invert`;
- `stretch_policy`: `follow-projection` or `opaque-independent`; and
- `stretch_obeys_exact_projection_mask`.

The inner-alpha input is the processed core. The consuming shader must emit
premultiplied alpha and multiply this alpha with the existing outer-underlay
alpha. The contract does not apply per-pixel transparency to the direct
Spatial 180/360 video carrier.

Native transport uses
`rusty.quest.projection-surface-tiling.v1` and
`rusty.quest.projection-inner-alpha.v1`. Descriptor set 3, binding 1 retains
the original 64-byte displacement prefix. Uniform ABI v2 appends a 64-byte
suffix for these controls, for a total of 128 bytes. Builds declare support
with
`RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PROJECTION_SURFACE_UNIFORM_ABI_VERSION=2`;
requested, supported, and effective markers remain separate. ABI v1 and
missing optional payloads keep both features ineffective.

## Shared endpoint input selection

`SpatialPeerNetworkProfilePilot` validates the additive `peer_network_endpoints`
selection and delegates all ordinary control fields to the existing profile
parser. `FrozenEffectiveInputs.Selector` supplies the pre-bootstrap input:
it starts from the installed signer/manifest-verified base, asks native code to
derive endpoint-only route/binding documents, and publishes one immutable
snapshot only after complete validation. The base manifest is never regenerated
or represented as a different installed artifact. Separate versioned lineage
records original manifest/route, submitted profile and effective route hashes.

The new pure native operation opens no sockets and stages no runtime authority.
It checks peer coverage, supported unicast IPv4/protocol/port data and endpoint
collisions, preserves nonendpoint documents, recomputes the owner runtime-spec
digests and invokes the existing packaged-route validator. Identity keys, trust,
policy and compiled ceilings are not profile parameters.

The existing runtime generation owner must freeze the selector before enrollment
or bootstrap. Those consumers require the exact current frozen object; an older
selected object cannot be substituted even if its bytes happen to match. Invalid
input/native results retain the prior accepted object. Once frozen, further
selection is refused. A later endpoint edit or revert needs a new eligible
generation under the existing cleanup/admission contract, rather than mutating
live sockets, routes or authority. The default packaged-input path remains
available without an override.

ProcessHost reads the bounded `active.profile.json` once before enrollment and
retains one frozen snapshot for its role, process generation and operational run.
Enrollment resolution/review/confirmation and native bootstrap consume that same
object. An endpoint edit is selected only after the existing owner has earned a
new eligible selection boundary; ordinary peer Stop does not mutate the frozen
route. An explicit new enrollment review at the existing native-idle terminal
boundary also retires the prior data selection. Cleanup invalidates old objects.

The Activity parser accepts the endpoint field alongside visual controls. Visual
application keeps its existing route-active/main-thread boundary and does not
change a running route. `last-apply-receipt.json` reports `applied_scope` as
`visual_controls`, the requested endpoints, and cached `effective_input_owner`
observation. That observation distinguishes frozen selection from actual native
configuration identity; it does not claim socket liveness. The hotloader's
existing explicit property is still required. Debug builds permit that opt-in
with private locked input. File publication, native data validation, runtime
adoption and physical endpoint readback remain separate evidence.

The explicit pilot consumer accepts a complete existing control profile with
one additional root field, `peer_network_endpoints`. This is an array of exactly
two objects, each containing `peer_id`, `media_endpoint` and `control_endpoint`.
Each endpoint contains only `protocol`, `host` and `port`; media protocol is
`rmanvid-v4-packed-stereo`, control protocol is `rqod1`, host is canonical
unicast IPv4 (no DNS, loopback, unspecified or multicast address), and port is
an exact integer in `1..65535`. IPv6 is outside this pilot. Both peer IDs must
exactly cover the caller's current enrolled peer set; the caller, not profile
data, supplies that set. Identity, device roles, keys, trust, camera and policy
fields are not accepted endpoint inputs. Media/control collisions within a
peer, and equal media or control endpoints across the peers, are rejected.

The same existing strict byte ingress validates the whole bounded document
before its endpoint field is extracted. The existing profile parser validates
all remaining fields. A successful selection atomically replaces the resident
pilot's in-memory `current` value, exposing the typed original visual profile,
exact submitted-byte SHA-256 and immutable endpoint readback. Exceptions leave
the preceding selection intact. There is no additional file watcher, receipt
format, persistence path or new resource owner.

## Optional development module directives

The same strict profile envelope accepts `development_observation_module` and
`development_scissor_module`. Omission issues no command. Each directive has a
closed `mode`: `static` and `status` take no other fields; `select_retained`
requires only a positive integer `generation`; `activate` requires that generation
and lowercase 64-hex `sha256`. Scissor activation additionally requires `policy`
as `product` or `inset_one_pixel`; observation never accepts policy. No path,
class or trust setting is accepted. Invalid envelope/addon/context validation
prevents both visual and module dispatch.

After valid visual application, the existing private extension may dispatch each
present directive to its real optional consumer. It borrows the existing debug
module owner's bounded worker. A missing consumer is reported as unavailable.
The fixed `last-development-observation-module-receipt.json` and
`last-development-scissor-module-receipt.json` contain profile hash, requested
mode, dispatch outcome and the actual nested consumer receipt. They do not infer
adoption from successful file publication or visual application. Receipts use
AtomicFile; completion tickets prevent an old A/B/A request from overwriting a
later identical A, and extension shutdown cancels exact pending Native admission
futures before retiring the shared debug owner. No code image unloading is claimed.

Source/JVM checks cover the integrated parser, data holder, actual public source
closure and hotloader with modeled Android leaves. Installed APK/Android event
delivery, private Android key storage and physical sockets require their own
runtime evidence.

## Earlier host prototype evidence

The following describes the preceding probes, whose limitations remain part of
their evidence; it does not describe the shared production path above.

One acceptance scenario selects peer A at `10.20.0.11:41000` (media) and
`:41001` (control), then changes that same peer to `10.21.0.11:43000` and
`:43001` at the next profile revision on the same resident selector, retaining
peer B and all visual controls. A malformed update, a foreign peer or an
unsupported protocol must retain the second accepted object. The focused host
runner is `tools/checks/Test-SpatialPeerNetworkProfileHost.py`; its explicit
JVM spec selects existing JDK/compiler/runtime jars and a stable snapshot of
compiled app value types. It compiles the actual strict parser, profile parser,
pilot and tests. It performs no Gradle/Cargo/Android build or socket effects.

The smallest future adoption point is the product-owned input generator,
before it finalizes route JSON, reciprocal media bindings, lifecycle digests
and the packaged manifest. Today the complete route and manifest hashes also
enter enrollment facts; media endpoints must match accepted binding transports.
Runtime selection without repackaging therefore requires a separately reviewed
effective-route contract, frozen before enrollment snapshot and bootstrap, and
carried coherently through native staging, ceremonies and owner projections.
This pilot adopts none of those changes. It changes no JNI ABI or compiled
policy ceiling and removes no APK rebuild from the current product workflow.

An optional host-only continuation now exercises that boundary end to end:
`tools/checks/Test-EndpointRouteDerivationHost.py`. It uses sibling atomic
replacement of the same `control-profiles/active.profile.json` file, the existing
strict/profile parser and an unadopted `EndpointRouteCandidate` under
`host-tests/embedded_duplex/`. It derives reciprocal endpoint leaves and their
canonical runtime/lifecycle/manifest digests, then invokes the actual native
`packaged_route.rs` reader and Java packaged-input reader. The Java bootstrap
projection checker is also executed; its native runtime-authority assembler
body is explicitly modeled, so this proves neither runtime-authority admission
nor physical bootstrap. Existing compiled JVM/Rust dependencies are reused;
the probe does not run Gradle or Cargo or build an APK.

One compiled host probe selects endpoint A then B from file replacements,
checks production reader readback, verifies non-endpoint identity/policy fields
remain equal, and irreversibly freezes its candidate snapshot for bootstrap.
Incompatible data and edits after freeze retain the accepted snapshot. Crucially,
the changed assets fail against the original manifest hash, the original
bootstrap projection refuses the changed route, and the existing enrollment
equality check refuses changed route facts with the same key identity. Admitting
the regenerated manifest in the fixture is a separate candidate-input test,
not evidence that the installed APK accepts it. No Android FileObserver,
installed enrollment store, media runtime or headset effect is exercised.

`tools/checks/Test-EndpointAuthorityHost.py` closes the subsequent native
authority model boundary. It imports the unchanged production packaged-config,
route and source-policy modules, uses owner libraries compiled directly from
the matching selected source, and executes real broker construction, token
issuance and capability-use authorization. Endpoint A and B keep separate
effective-route digests under the same immutable base hashes and compiled
development policy. A foreign caller is refused without changing accepted
admission state. The synthetic candidate manifest is excluded from this
authority request; base artifact and endpoint-containment checks precede
assembly. The full derived snapshot validates before its `current` changes.

This remains a host-only API prototype. Binder identity, clock, entropy and
trust context are fixed host fixtures; each validation creates fresh temporary
broker authority. It does not exercise installed package authentication,
private enrollment persistence, production shared-input consumer wiring,
native process-fence/peer-session admission, socket binding or media effects.
No live runtime is replaced and no enabling APK is selected by these tests.

## Named Panel Profile Library

The fixed **Profiles** page saves and restores complete low-rate panel tuning
setups. Each entry includes projection visibility, output layer and scale,
depth source/alignment, guide processing, region compositor values, RGB
transform, surface displacement, tiling, inner transparency, and immersive
video playback/presentation mode. The selected media/catalog item is excluded
on purpose: loading visual tuning must not switch or reload the current video.

The library is stored atomically in app-internal storage. A human-readable
derived export mirror is kept under the release-compatible app-specific
external-files directory:

`/sdcard/Android/data/<package>/files/profile-library/export.profile-bundle.json`

An import is a complete-list replacement, not a merge. The app accepts at
most 128 profiles and 1 MiB, rejects mismatched counts, duplicate IDs,
unsupported schema/version values, invalid names, and out-of-range control
snapshots, then writes the accepted list atomically. To avoid restarting the
Activity and its active layers, a PC stages the bundle and the operator taps
**Import staged bundle** on the Profiles page.

Validate a bundle without a headset:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File tools\Invoke-SpatialCameraPanelProfileTransfer.ps1 `
  -Action Validate `
  -BundlePath <profiles.json>
```

Stage an import or pull the current export with an explicit ADB serial:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File tools\Invoke-SpatialCameraPanelProfileTransfer.ps1 `
  -Action StageImport `
  -Serial <exact-adb-serial> `
  -BundlePath <profiles.json>

pwsh -NoProfile -ExecutionPolicy Bypass `
  -File tools\Invoke-SpatialCameraPanelProfileTransfer.ps1 `
  -Action Export `
  -Serial <exact-adb-serial> `
  -OutPath <profiles.json>
```

The transfer path does not use `run-as`, so it works with release packages.
Device operations remain serial-scoped and do not restart the ADB server.
Profile bundles are local artifacts and must not contain private media names,
catalog IDs, keys, captures, or device identifiers.

## Runtime Behavior

The hotloader is disabled on a normal launch. Explicit diagnostic runs enable
`debug.rustyquest.spatial_camera_panel.control_profile_hotload.enabled=true`
before launching the app; only then does the app watch this app-specific
external-files location:

`control-profiles/active.profile.json` and the optional module-only
`control-profiles/active.modules.json` described below.

The absolute device path is normally:

`/sdcard/Android/data/<package>/files/control-profiles/active.profile.json`

On an explicitly enabled launch, the app arms the watcher and snapshots any
file already present.
That stale file is not applied. Only an atomic replacement made after the
watcher is armed can change the running controls. This keeps the APK's normal
startup configuration authoritative and prevents an old test profile from
silently changing a later run.

When a new file arrives:

1. the app waits until the custom projection route is active;
2. the complete file is parsed and range-checked;
3. unsupported direct-video blend combinations fail closed;
4. all controls are submitted through the existing Activity-owned
   coordinators; and
5. the app writes `control-profiles/last-apply-receipt.json`.

Malformed or unsupported profiles are rejected without resetting the previous
effective controls. Removing the active file also retains the current
controls. Change detection uses the Android file observer and performs no
scene-tick polling. The file is limited to 64 KiB; no high-rate data crosses
this path.

## Manual Hotload

Enable the diagnostic property, launch the app, then use the serial-scoped
helper:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File tools\Install-SpatialCameraPanelControlProfile.ps1 `
  -Serial <exact-adb-serial> `
  -ProfilePath <profile.profile.json> `
  -OutPath <local-receipt.json>
```

The helper validates the host file, stages it under a temporary device name,
assigns a strictly newer staged modification time than the active profile,
atomically replaces `active.profile.json`, verifies the resulting device file
signature, and waits for a fresh app-owned receipt with the same SHA-256. The
generation step makes publishing identical bytes observable to the app without
changing those bytes or their digest. A successful file transfer without a
matching application receipt is a failure.

The current helper is an ADB fallback for manual development. File Manager or
Manifold can later publish the same Quest-owned file contract without changing
the app-side parser.

## Module-only development delivery

The same opt-in watcher accepts a separate, closed envelope at the fixed
`control-profiles/active.modules.json` path. This route requires the existing
hotload property before a fresh Activity `onCreate` arms that watcher. Enabling
the property on an already running Activity does not arm it. Existing files
are snapshotted without replay on arm. This adds no provider, worker, SDK
generation or runtime readiness transition.

```json
{
  "schema": "rusty.quest.spatial_camera_panel.development_modules.v1",
  "profile_id": "module-status",
  "revision": 1,
  "development_observation_module": {"mode": "status"},
  "development_scissor_module": {"mode": "status"}
}
```

Only these five root fields are allowed. The id uses the control profile id
syntax; revision is a nonnegative signed JSON integer. At least one directive
is required. Both directives reuse the existing closed mode/generation/hash/
policy parser. Duplicate keys, null directives, unsupported fields and files
over 64 KiB fail before dispatch. Visual controls, desktop preview and network
endpoints cannot be included, applied or deferred through this envelope.
Module delivery calls the existing optional private extension and consumer
owners even when the visual native lifecycle is unavailable. Omitted consumers
receive no command. Existing owner queue bounds, pending work, retained images,
rollback and cancellation remain authoritative.

Arbitration is per consumer and independent of file event order. If the legacy
full profile contains that consumer's directive, a new module-only directive
for it is rejected. A new legacy directive is likewise retired if the
module-only file contains a directive for that consumer. A malformed or
unreadable competing file blocks module dispatch conservatively. All affected
requests retain the prior selection; no source wins by mtime or event order.
Removing a competing file does not replay a rejected or retired publication.
Resolve the competing content and publish a fresh file generation explicitly.
Consumers without a competing directive can still dispatch independently.
Legacy visual controls keep their full parsing/context validation and native
visual readiness gate. A retired legacy module directive never replays when
its pending visual controls later become eligible. Startup endpoint capture
still reads only the full profile before enrollment; this file is not an
endpoint input.

`module-watch-receipt.json` identifies the armed watcher UUID, process PID and
process start ticks. Activity shutdown marks it retired. Each existing fixed
`last-development-{observation,scissor}-module-receipt.json` also binds that
identity, consumer, raw SHA-256, id, revision, mode, source file and observed
publication length/mtime. `watched` means dispatched with a pending future;
`observed` / `consumer_readback` carries the actual nested consumer receipt.
Only that nested receipt proves acceptance or rejection. Scissor use requires
the consumer's actual computed generation **and** selection revision; a
successful transport, accepted selection, status request or zero renderer
calls proves no use. Shutdown writes `retired` for pending receipt futures and
suppresses their late completions. Receipts never manufacture acceptance or
renderer activity. `last-modules-apply-receipt.json` is transport diagnostics,
not consumer adoption evidence.

The installer selects this fixed route only with `-ReceiptMode ModulesOnly`:

```powershell
pwsh -NoProfile -File tools/Install-SpatialCameraPanelControlProfile.ps1 `
  -Serial <exact-adb-serial> -Package <exact-package> `
  -ProfilePath <modules.json> -ReceiptMode ModulesOnly `
  -ExpectedWatcherId <current-watcher-uuid> -ExpectedOwnerPid <current-pid> `
  -ExpectedOwnerStartTicks <current-process-start-ticks> -OutPath <receipt.json>
```

Supply identity from the current actor/Activity observation and its armed
watcher receipt. The helper requires that identity before publication, uses
the existing atomic fixed-file transfer, and joins every requested consumer's
readback to the exact publication signature and identity. It reports
`observed`, leaving acceptance/use in the nested consumer receipt. Watched-only,
stale, unavailable, failed or retired readback cannot satisfy it. Unknown or
timed-out ingress remains published; there is no automatic retry, removal or
fallback to the full-profile path. Default `VisualControls` mode continues to
wait for the existing visual application receipt.

Focused checks are `tools/checks/Test-ControlProfileHotloaderHost.py` (explicit
retained JVM recipe, actual parser/hotloader with modeled Android leaves) and
`tools/checks/Test-InstallSpatialCameraPanelControlProfileHost.ps1` (actual
installer with modeled serial-scoped ADB). They do not prove Android file
observer delivery, installed consumer admission, ART/native loading or use.

## Compatibility Rules

- `schema` must match exactly; unknown control fields fail closed.
- `profile_id` uses 2–64 lowercase letters, digits, dots, underscores, or
  hyphens and must begin with a letter or digit.
- `desktop_preview` is optional, must be an object when present, and is
  ignored by Quest.
- Omitted additive surface controls normalize to disabled identities; they do
  not select the tessellated pipeline or change alpha.
- Direct Spatial video is unsampled by the custom compositor. The independent
  region contract may still select outgoing, midpoint, or incoming color for
  the custom layer's outer-alpha transition while the Spatial video remains a
  separate transparent underlay.
- The app receipt reports normalized effective values, not merely requested
  values.

Profiles and receipts are local artifacts. They are not packaged into the APK
or source control by this workflow.
