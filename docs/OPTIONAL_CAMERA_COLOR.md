# Optional camera color provider

Optional camera color processing is explicitly selected by
`required_public_bridge.optional_camera_color_abi_version: 1` in the selected
provider profile. The default is `0`. ABI1 requires source-bank final ABI2 and
an explicit optional color shader. The builder binds this selection into its
existing source-bank input identity and transports
`RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPTIONAL_CAMERA_COLOR_ABI=1` to the native
build. Unsupported values and incomplete selections are rejected.

The native build compiles the final provider with
`PRIVATE_LAYER_CAMERA_COLOR_ABI=1` and requires its compiled SPIR-V to expose a
fragment sampler at set 1, binding 10. The descriptor budget includes that
sampler. ABI0 providers receive no new descriptor or draw modes; combined
optional color is unavailable for them. The existing source-color routes
remain separate.

ABI1 assigns `params0.y=-2` to camera-content capture and `params0.y=-3` to
final consumption. Capture renders each normalized camera eye into its own
half of a transparent packed target. Surface coordinates remain joined to the
original projection target for aligned processing. Capture excludes background,
region composition, global opacity, and tessellated presentation geometry. It
preserves source validity and intrinsic alpha in premultiplied RGBA.

The optional shader receives separate, eye-clamped textures and must preserve
the original pixel's alpha, including zero coverage during RGB warps. Its
processed packed result is consumed only by the final center camera projection.
The final compositor retains ownership of center/video mixing, corner coverage,
feathering, middle and outer content, stretch, opacity, and tessellated geometry.
The ordinary final render pass independently applies black or transparent backing.
Background pixels are never used as camera color input.

An unmixed Own or Peer camera needs only its selected available source.
Enabled blends retain the two-source requirement. Diagnostic masks, synthetic
fixtures, and unsupported alpha routes remain excluded. Source-color routes
accept full-stack and Raw8 camera content while preserving guide diagnostics.
Effective placement still requires retirement of the exact requested draw;
configuration or allocation alone supplies no effective readback.

Focused host controls run without an APK or headset:

```powershell
rustc --edition 2021 --test apps/spatial-camera-panel-android/native-receipt/host-tests/optional_camera_color.rs -o target/optional-camera-color-controls.exe
& target/optional-camera-color-controls.exe
```

These exercise real source admission, compiled-ABI checking, independent backing
configuration, and final-copy planning. They do not prove rendered pixels.
The existing one-shot final readback copies the selected swapchain after the
last compositor draw and retires with its actual fence. Its normal crossings,
tile centers, and eight `carrier-corner` samples can distinguish camera content
from distant backing. Join those coordinates with the actual region geometry;
a carrier-corner label alone does not establish that a region is uncovered.
Input-bank qualification does not replace this final-output proof.
