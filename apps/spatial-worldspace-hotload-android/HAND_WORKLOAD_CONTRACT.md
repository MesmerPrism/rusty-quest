# Resident hand workload

This optional backend records into the atlas producer's Vulkan command buffer.
It owns buffers and pipelines, not the device, queue, submission, fence or XR
frame lifecycle. The caller retires the single buffer slot before every record
and calls `destroyAfterRetirement` only after all referencing work is complete.
Uncertain retirement must retain the whole owning device/resource closure.

The skinning SPIR-V comes from the existing native renderer's
`gpu_hand_skinning.comp.glsl`, compiled unchanged. Only 21 joint poses and five
tip lengths per active hand are uploaded each frame. The second compute pass
expands resident triangles, computes the existing face normals and applies the
existing opposite-fingertip graft transforms. Output is two vec4 rows per corner:
position/validity followed by normal. Base left/right corners come first, then
five left-source graft copies and five right-source copies. Invalid current
hands write invalid corners; grafts require both hands. There is no recorded-pose
fallback and right poses never skin a left rig.

`HandInput` borrows the SDK instance/session/proc-address function. It owns only
two hand trackers and a reference space of the exact requested type. The app
selects LOCAL_FLOOR_EXT explicitly; unsupported space fails without substituting
LOCAL/STAGE. Request `XR_EXT_hand_tracking`, `XR_EXT_local_floor` and
`XR_KHR_convert_timespec_time` before SDK initialization. Sampling converts
`CLOCK_MONOTONIC` using the runtime's conversion function and locates at that
**current** time, not an assumed SDK predicted-display timestamp. Raw right-handed
positions remain in the declared XR space; the atlas consumer applies its
explicit SDK basis conversion. Device alignment remains to be validated.

Producer generation and frontend SDK session generation are separate identities.
Focus loss immediately yields inactive input. `detach` is serialized against
locate and must run before the SDK invalidates its session; it permits later
reattachment. Terminal producer cleanup calls `close`, which also rejects any
late attach racing with shutdown. No code calls XR instance/session creation,
event polling, wait/begin/end frame or changes SDK frame ownership.

The neutral `tools/Export-SpatialWorkloadHandRig.py` reads explicit left/right
static rig JSON files and emits private build assets plus `hand-inputs.json`.
It validates compact provider/order/basis, hand identity, indices, float32 values
and ABI counts. It never reads clips or recorded animation. Generated assets
must stay outside tracked public source. `metadata.bin` is a 48-byte little-endian
record: magic `0x31484752`, version1, then two five-word rows containing hand ID,
vertex count, triangle count, bind count26, runtime count21. Bind tips use the
five current measured lengths; the zero-filled `tip_lengths.bin` is an ABI
placeholder and is not used for pose fallback. Host asset packaging verifies
the manifest's source and payload hashes.

Focused checks:

- `python tools/tests/test_spatial_hand_export.py` tests actual exporter ABI,
  asymmetric hands, bad indices/weights/poses/provider/order and missing rows.
- `gpu_hand_input_test.cpp` includes the real bridge behind a fake XR function
  table and exercises clocks, exact reference space, focus, invalid pose,
  right-only input, session changes, detachment and terminal close.
- `gpu_hand_math_test.cpp` checks the actual graft math and 192-byte ABI against
  asymmetric known palm/fingertip fixtures and a 90-degree quaternion rotation.
- Compile both compute shaders with `glslc --target-env=vulkan1.1 -Werror`;
  the native skinning `.glsl` path needs `-fshader-stage=compute`.
- Android C++ syntax/type checks use the selected NDK and external Khronos
  OpenXR headers, without linking another OpenXR loader.

Host and shader passes do not prove GPU output, SDK clock/reference-space
alignment, Surface sampling, wearer-visible hand shape or graft articulation.
