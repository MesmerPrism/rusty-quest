# Native diagnostic compute hotload contract

This public harness owns a separate 128-byte storage buffer with two billboard
instances. Each instance has four std430 vec4 rows: position/radius,
color/alpha, normal/active, and rotation/mask-phase/kind/weight. Coordinates
are raw OpenXR right-handed floor-relative metres. A0 is cyan/red at
x ±0.45, y 1.65, z −1.2, radius 0.04. B1 is yellow/magenta at y 1.73.
This output is stateless and does not replace or roll back simulation state.

The only executable asset choices are packaged `hotload/native/a.spv` and
`hotload/native/b.spv`. `Compile-HotloadKernels.ps1` compiles both with the
selected NDK glslc, runs spirv-val, and follows the disassembled SPIR-V type and
decoration graph. It requires one storage buffer at set 0/binding 0 with
runtime vec4 array stride 16, one 32-byte push block (uvec4 then vec4), and
local workgroup size 2/1/1. It emits `assets/hotload/native/catalog.json` and
`native-catalog/hotload_catalog.h`. The latter seals exact binary byte lengths,
SHA-256 digests, and ABI version into the native library. Native loading
checks these bounds, magic, ABI, and SHA-256 before any driver creation call.

Actions are `a`, `b`, `revert`, `bad-abi`, `malformed`, and `stale`.
Revert selects baseline A0 and advances generation; it restores no private
state. The two negative presets reject before shader-module or pipeline
creation. Stale always rejects with a generation reason. Request identifiers
are positive canonical decimal integers, monotonically increasing. Exact
retained retries return their owned transaction receipt, conflicting fields
reject, and IDs older than bounded receipt retention reject rather than
running again. Thirty-two receipts are retained, preserving the pending and
last transaction. One pending transaction covers queueing through its first
completed submission; additional requests get a busy receipt.

The caller owns every command buffer, queue submit, fence and reset.
`afterRetirement(serial)` is legal only after successful fence retirement and
successful command buffer reset, before beginning its next recording. This
is the sole activation site; it prepares the candidate, swaps generation and
pipeline, and destroys the old pipeline after the caller's retirement proof.
The caller uses `record`, then calls `confirmSubmitted(serial)` only following
successful queue submission. `confirmCompleted(serial)` follows successful
fence observation and joins the transaction's first actual submission.
No module call submits, waits, or resets GPU work. Destroy is legal only with
all GPU uses retired and recorded command buffers reset or destroyed.
Unproven teardown retains native allocations until process exit.

Receipts separate queued, prepared, activated, submitted, completed, and
rejected phases. A pre-submit initial baseline is reported as activated with
zero submission/completion serials. Effective visual behavior and GPU/device
safety still require the headset tests in the harness test matrix.

Host validation builds `app/src/test/cpp/hotload_state_test.cpp` as C++17 with
warnings as errors. It covers preparation failure/abandonment, stale/busy,
receipt flood and eviction, idempotent retries, submission generation and
serial joins, stateless revert, shutdown, bounded blob validation, negative
presets, and SHA-256 empty/abc/multiple-block known vectors. Shader compiler
validation independently checks both real public compiled variants.
