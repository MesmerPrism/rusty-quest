# Spatial Camera Control Profiles

The Spatial Camera Panel accepts one bounded, versioned JSON control profile
while its custom projection route is running. The profile is a low-rate
control file, not a replay capsule, camera recording, media library, or shader
payload.

The public schema is:

`rusty.quest.spatial_camera_panel.control_profile.v1`

It carries the selected output-layer override, projection scale, generic zone
compositor controls, RGB-channel transform transport, and neutral
projection-surface displacement controls. Effect formulas, guide packing,
private shader assets, captures, media, and product tuning stay with the
private provider.

Rusty Quest is the sole owner of profile conversion, validation,
serialization, hotload application, and effective receipts. Hostess-authored
desktop states use
`rusty.hostess.projection_replay_control_state.v2` and contain capsule-native
24/16/92-float ABI blocks rather than Quest-shaped controls. Convert one with
the Quest-owned adapter:

```powershell
pwsh -NoProfile -File tools\Convert-HostessReplayControlState.ps1 `
  -InputPath <state.replay-control-state.json> `
  -OutPath <profile.profile.json> `
  -GradleHome <gradle-9.4.1>
```

The converter rejects unknown fields, wrong block lengths, non-finite values,
unsupported tokens and unsupported effective combinations, then runs the
result through the authoritative Quest profile parser. It maps only the
public, effect-neutral transport values; provider-private meanings and tuning
do not enter the output. The PowerShell route invokes the production
`convertHostessReplayControlState` JVM task with explicit input/output
arguments. `-GradleHome` (or `GRADLE_HOME`) permits a clean detached source
materialization to use an externally provisioned exact `gradle-9.4.1`
distribution; the repo-local ignored tool cache remains the fallback. The
production main writes a temporary sibling and requires an
atomic rename before the final profile becomes visible.

State v2 is preferred and carries an opaque descriptor-keyed
`control_transport` value map bound to its transport identifier and capsule
SHA-256. Quest validates that envelope but does not interpret provider labels
or destination mappings, and the exported Quest profile remains byte-for-byte
equivalent for the same capsule-native blocks. State v1 remains read-only
compatibility input; a v1 document containing the v2 `control_transport`
envelope is rejected.

## Runtime Behavior

The app watches this app-specific external-files location:

`control-profiles/active.profile.json`

The absolute device path is normally:

`/sdcard/Android/data/<package>/files/control-profiles/active.profile.json`

On launch, the app arms the watcher and snapshots any file already present.
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
controls. The app polls at 250 ms and the file is limited to 64 KiB; no
high-rate data crosses this path.

## Manual Hotload

Keep the app running, then use the serial-scoped helper:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File tools\Install-SpatialCameraPanelControlProfile.ps1 `
  -Serial <exact-adb-serial> `
  -ProfilePath <profile.profile.json> `
  -OutPath <local-receipt.json>
```

The helper validates the host file, stages it under a temporary device name,
atomically replaces `active.profile.json`, and waits for a fresh app-owned
receipt with the same SHA-256. A successful file transfer without a matching
application receipt is a failure.

The current helper is an ADB fallback for manual development. File Manager or
Manifold can later publish the same Quest-owned file contract without changing
the app-side parser.

## Compatibility Rules

- `schema` must match exactly; unknown control fields fail closed.
- `profile_id` uses 2–64 lowercase letters, digits, dots, underscores, or
  hyphens and must begin with a letter or digit.
- `desktop_preview` is optional, must be an object when present, and is
  ignored by Quest.
- Direct Spatial video is unsampled by the custom compositor. A transparent
  video underlay therefore accepts only the documented outgoing,
  region-driven outer-alpha route.
- The app receipt reports normalized effective values, not merely requested
  values.

Profiles and receipts are local artifacts. They are not packaged into the APK
or source control by this workflow.
