# RGB channel transform

`rusty.quest.rgb-channel-transform.v1` is the public, renderer-neutral contract
for applying bounded spatial transforms to red, green, and blue independently.
It owns configuration normalization, Android controls, JNI transport, a Vulkan
uniform ABI, effective markers, and identity/reference tests.

The contract does not define an effect signal, a color-to-strength mapping, an
artistic preset, or a final sampling/compositing formula. Those decisions
belong to the consuming application. This keeps the reusable per-channel
capability public without publishing a downstream private effect.

## Modes and bounds

- `0` (`bypass`) leaves consumer behavior unchanged.
- `1` (`independent-rgb`) preserves distinct values for red, green, and blue.
- `2` (`linked-rgb`) uses the normalized red values for all three channels.

Unknown mode or edge codes fail closed to bypass and clamp. Edge handling is
`0` clamp, `1` mirror, or `2` fade. Public normalization applies these bounds:

| Parameter | Per-channel range |
| --- | --- |
| Direction | wrapped to `[0, 1)` turns |
| Direction rate | `[-2, 2]` Hz |
| Displacement strength | `[0, 0.08]` UV |
| Image scale | `[0.5, 2]` |
| Effect coverage scale | `[0.5, 1]` |

The time value is supplied once per display frame and shared by both eyes.
Consumers therefore receive stereo-coherent channel directions even when the
three channels use different rates.

## Vulkan ABI

The public Spatial Camera Panel renderer binds a 96-byte `std140` uniform at
descriptor set 3, binding 0:

```text
vec4 mode                       // mode, edge mode, revision, reserved
vec4 direction_turns            // r, g, b, reserved
vec4 direction_rate_hz          // r, g, b, reserved
vec4 displacement_strength_uv   // r, g, b, reserved
vec4 image_scale                // r, g, b, reserved
vec4 coverage_scale             // r, g, b, reserved
```

The non-video projection layout is camera, guides, depth, then RGB transform.
The video compositor adds its video sampler at set 4 and zone uniform at set 5.
The public runtime updates the RGB uniform before recording either projection
path.

## Controls and evidence

The layer panel exposes bypass, linked, and independent modes, three edge
policies, and per-channel direction, rate, strength, image scale, and coverage.
Validation clients may select the bounded presets with
`rgb-channel-bypass`, `rgb-channel-linked`, or
`rgb-channel-independent`.

Requested and native-effective markers include
`rgbChannelTransformContract`, `rgbChannelTransformMode`,
`rgbChannelTransformEdge`, `rgbDirectionTurns`, `rgbDirectionRateHz`,
`rgbDisplacementStrengthUv`, `rgbImageScale`, and `rgbCoverageScale`.

Run
`tools/checks/Test-SpatialCameraPanelRgbChannelTransformStatic.ps1` plus the
focused Rust and Android unit tests when changing this contract.
