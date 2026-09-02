# Projection Surface Displacement

Rusty Quest exposes an optional, renderer-neutral projection-surface
displacement transport for private downstream projection payloads. The public
contract is `rusty.quest.projection-surface-displacement.v1`.

The capability does not define an effect signal, a color or guide mapping, a
signed depth formula, or product tuning. A downstream payload supplies an
optional vertex shader at build time. Public builds without that payload, and
all runs with displacement disabled, keep the original fullscreen triangle
projection path exactly.

## Representation

The Spatial SDK projection carrier remains a planar `SceneQuadLayer` backed by
the existing Android/Vulkan surface. When enabled, the Vulkan projection draw
uses a bounded 32 by 32 expanded triangle grid and lets the optional vertex
payload move raster positions inside that carrier. This produces a
surface-like parallax or depth warp, but it is not compositor-space mesh
geometry and must not be reported as environment depth.

The neutral tiling suffix has three topology values. `continuous` retains the
connected grid, `tiled` uses one center for each of the 1,024 square cells, and
`triangle-tiles` gives the two already-present triangles in every cell separate
centers, yielding 2,048 independently centered triangle tiles. All three use
the same 32 by 32 grid, 6,144 vertices, one draw, and unchanged rest-space
content UVs. A downstream vertex payload owns how those centers affect private
depth sampling or geometry.

Keeping the carrier unchanged preserves:

- Camera2 and hardware-buffer sampling;
- stereo video decode and live video selection;
- the shared stereo timebase;
- RGB-channel transforms;
- the core, buffer, and outer-video compositor zones;
- the existing Spatial SDK panel lifecycle.

## Public controls

The normalized low-rate configuration contains:

- enabled state;
- maximum displacement in meters, clamped to `0.0..0.35`;
- reference surface distance, clamped to `1.0..4.0` meters;
- signed polarity, clamped to `-1.0..1.0`;
- edge taper, clamped to `0.02..0.45`;
- a monotonic revision.

`Off` is the identity preset. `Gentle` and `Deep` are public convenience
levels, not application-specific signal mappings.

## Shader and draw ABI

The existing RGB transform remains at descriptor set 3, binding 0. Projection
surface settings occupy descriptor set 3, binding 1 as a 64-byte `std140`
uniform:

1. mode, polarity, revision, and grid resolution;
2. maximum displacement, reference distance, and edge taper;
3. left draw rectangle;
4. right draw rectangle.

The optional private vertex input is configured with
`RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_VERTEX_SHADER`. Both the
ordinary private projection and the same-surface video-zone compositor build a
second pipeline when this payload is present.

At runtime, the renderer selects that pipeline only when the normalized
configuration is active. Otherwise it draws the original fullscreen triangle
with three vertices. A requested active setting without a compiled private
vertex pipeline fails closed to the original path.

## Authority and observability

The Compose panel and serial-scoped UI command adapter feed one Kotlin
normalizer, then one JNI update function, then one native revisioned owner.
Markers distinguish:

- requested active state;
- effective active state;
- optional vertex-pipeline availability;
- bounded values and revision;
- grid resolution and vertex count;
- planar carrier and tessellated parallax representation;
- disabled fullscreen-triangle fallback.

The remote validation actions are:

- `projection-surface-displacement-off`;
- `projection-surface-displacement-gentle`;
- `projection-surface-displacement-deep`.

## Validation

Run:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-SpatialCameraPanelProjectionSurfaceDisplacementStatic.ps1
cargo test -p spatial-camera-panel-native-receipt projection_surface_displacement
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-SpatialCameraPanelAndroid.ps1
```

An application-specific acceptance run must also prove live Off/active
switching, advancing stereo video, retained zone-compositor markers, zero
bounded fatals, and cleanup on an explicitly selected headset.
