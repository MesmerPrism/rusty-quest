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

`control-profiles/active.profile.json`

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
