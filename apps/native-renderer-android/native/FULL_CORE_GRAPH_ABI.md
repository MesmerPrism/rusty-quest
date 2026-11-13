# Opt-in buffered stereo graph

`FullCore3` is mode 2 of the version 2 external Meta input control. Raw (0)
and Colorama (1) retain the existing direct pipeline. There is no fallback
from mode 2 to either direct mode.

The build requires `RUSTY_QUEST_NATIVE_RENDERER_META_INPUT_FULL3_DIAGNOSTIC=true`,
`RUSTY_QUEST_NATIVE_RENDERER_META_INPUT_FULL3_BUNDLE`, and
`RUSTY_QUEST_NATIVE_RENDERER_META_INPUT_FULL3_BUNDLE_SHA256`, together with the
existing presentation and Meta-input opt-ins. The bundle is external to this
public source tree. Its exact bytes and each fragment's exact bytes are
verified, then copied to the build output before compilation.

The closed JSON object has `schema="private.meta_input.full3_bundle.v1"`,
`abi="presentation128-v1"`, `atlas=[3072,1664]`, `eye=[1280,1280]`, and
`fragments` containing exactly three ordered objects `{path,bytes,sha256}`.
The order is pack, strength blur, final projection. Paths are relative
descendants of the bundle directory; SHA256 strings are lowercase hex.
The existing public fullscreen vertex is used for all stages.

All fragments use descriptor set 0, binding 0, a regular combined image
sampler; location 0 is the vertex's `vec2` UV. The fragment push constant
range is 128 bytes, eight consecutive float vec4 values:

| Offset | Meaning |
| --- | --- |
| 0 | destination target rectangle, x/y/width/height |
| 16 | packed source eye rectangle, x/y/width/height |
| 32 | source Y flip, opacity, mode (=2), eye index |
| 48 | runtime eye tangents, left/right/down/up |
| 64 | explicitly supplied source tangents, left/right/down/up |
| 80 | eye-to-source quaternion, XYZW |
| 96 | existing presentation geometry parameters |
| 112 | existing eye origin XYZ, predicted display time in seconds (float) W |

Positive Vulkan viewports map UV (0,0) to the top left. Atlas stages draw
two disjoint 1536 by 1664 viewports at x=0 and x=1536. Each has a 1280
square raw region and four 384-square guide tiles along y=1280. Stage
fragments own their internal coordinate convention and quantization;
the bundle must map input top-origin UV and final local eye UV consistently.
Packed-eye mapping applies after geometry projection, and filtering must
stay within the selected eye's texel-center bounds. Source tangents are
explicit assumptions, not a measured capture pose or an implicit FOV.
Time comes from the current OpenXR predicted-display timestamp, independently
of the retained input image's age. The selected fixed bundle profile may
leave temporal rates at zero; carrying time does not enable a new profile.

The two intermediate attachments are `R8G8B8A8_UNORM`, sampled linearly
without an sRGB transfer. The final target must be RGBA8 or BGRA8 UNORM;
an sRGB target, external-format/YCbCr sampler, or another input size is
refused. Actual image orientation and numeric equivalence still require
GPU evidence; successful compilation is not that evidence.

Each XR frame slot owns both atlases. Recording occurs only after that
slot's existing completion fence. Render-pass dependencies publish color
writes to subsequent fragment reads. The imported AHB lease remains with
the original feedback owner through both atlas passes, both eye draws,
and witness draws, then receives the existing FOREIGN release and fence
retirement. Mode-2 witnesses use the same final graph pipeline as the
submitted eyes. Witness status bit 4 requires both eye-sized exports to
finish successfully after their actual slot fence. Bit 4 now requires four
durable exports: both processed eye results and both raw-pack input eyes from
that same retained AImage, input timestamp, source frame and original GPU
submission. Separate raw/Colorama witnesses retain their existing meaning.
Existing OpenXR wait/acquire/release, EXCLUDE, and native
passthrough ownership remain unchanged.

The selected bundle's pack pass must preserve sampled input RGBA in atlas A's
two 1280-square raw rectangles at x=0 and x=1536, y=0, before guide/effect
processing. The generic carrier copies those rectangles with TRANSFER_SRC
barriers after graph draws, restores shader-read layout, and reads host bytes
only after the original frame-slot fence. Atlas images already include
TRANSFER_SRC usage. No extra queue submission, wait, AImage acquisition or CPU
pixel transform is introduced. This is sampled/UNORM-quantized raw input, not
the platform producer's storage bytes; the pack's canonical bottom-left row
orientation and sampled alpha (including zero alpha with nonzero RGB) remain
unchanged. A changed bundle must independently qualify that raw-region contract.

Raw files are `meta-input-witness/<generation>/full-core3-input-eye-{0,1}-seq-<sequence>.{rgba,json}`;
processed files remain `presentation-witness/<generation>/eye-{0,1}-mode-2-seq-<sequence>.{rgba,json}`.
Metadata explicitly joins source/render identity and names the raw-pack stage;
all four durable writes are required before publishing bit 4. An absent readback,
copy/write error, mixed identity or partial export cannot publish paired
completion. GPU readback and these files still do not prove HMD scanout,
camera physical coverage, temporal reprojection or empirical no-feedback.


## Imported-input normalization

`sampled_input_adapter.rs` validates the actual imported Vulkan sampler contract,
not the Android hardware-buffer descriptor format. A regular UNORM input can
enter the ordinary graph directly. Converted/external images enter
`sampled_input_normalize_vk.rs` using the import owner's **exact** descriptor
layout, immutable sampler and YCbCr conversion. Its neutral pixel-center copy
retains packed dimensions, orientation and sampled alpha and writes an owned
`R8G8B8A8_UNORM` image. This adds UNORM quantization after the platform conversion;
it is not a claim that external image storage itself is RGBA or color calibrated.
The output uses a regular LINEAR descriptor compatible with the unchanged graph.

The normalizer owns one output per XR frame slot. It runs only after that slot's
original fence retires. Its pipeline/layout are destroyed before the borrowed
import-layout/conversion owner. FOREIGN ownership and the AImage lease remain
with the parent across normalization, all effect passes and witness draws; only
the parent records release and retires the lease after original submission.
No conversion, nearest-filter, alpha or shader fallback is synthesized.

Both normalizer and graph finish fallible allocation/validation before beginning
a render pass. A rejected FullCore3 preparation is reported immediately and
submits the recorded ownership transitions with no effect draw or effect
completion. The acquired XR image then follows normal wait/submit/release/frame
completion instead of unwinding a running session. The slot fence remains
signalled during fallible recording and is reset only beside queue submission.
Successful device idle retires submitted work without waiting on fences that
were reset but never submitted. Device-loss/unknown-completion retention remains
separate; this repair does not establish safe recovery from arbitrary driver or
OpenXR failure and does not authorize destroying a running session on rejection.

The diagnostic bridge is not the Spatial Camera Panel input implementation.
That consumer's existing packed-SBS normalization and effect ownership remain
separate; this contract is a neutral input-adapter reference, not a replacement
for an application's effect graph or output authority.
