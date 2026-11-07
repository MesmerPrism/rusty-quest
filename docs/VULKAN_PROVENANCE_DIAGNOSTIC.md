# Debug Vulkan metadata provenance layer

This optional source candidate records image, view, allocation, binding and
descriptor metadata on participating Vulkan dispatch chains. It supplies no
renderer admission, lifetime repair or SDK coverage claim. The existing
OpenXR API layer does not intercept all SDK Vulkan calls; replacing only its
forwarded GIPA parameter would not cover retained SDK dispatch tables or linked
Vulkan entry points.

## Explicit build selection

The public producer accepts `-VulkanProvenanceDiagnostic` only for a
non-publication Debug build with `-EnvironmentDepthOwner spatial-sdk-api-layer`.
The default is unselected. The producer passes the explicit Gradle property
`rqVulkanProvenanceDiagnostic=true|false`, binds the selection into its input
lock, shell cache identity, manifest and run capsule, and inventories the
additional native source directory.

The selected debug variant builds `VkLayer_rusty_quest_provenance` from its
separate source directory. Release retains the CMake OFF default and excludes
the target. The selected CMake directory refuses a non-Debug build. The native
binary contains the exact selected application ID; no downstream product ID is
hard-coded in public source. No app permission, SDK version, shader, frame-rate,
enrollment, real renderer binding or cleanup path is selected by this flag.

The artifact observer requires exactly
`lib/arm64-v8a/libVkLayer_rusty_quest_provenance.so` when selected and denies
that basename in any ABI when unselected. Actual builder inspection also
requires its defined loader/introspection exports, 16 KiB LOAD alignment and
compiled application-ID string. Local function binding prevents ELF symbol
preemption by adjacent Vulkan libraries; inspection rejects dynamic relocations
to Vulkan entry symbols. Synthetic ZIP/tool tests exercise these checks
but establish no Android ELF or APK result.

## Per-app loader and runtime admission

Android documents per-app Vulkan layer activation through
`enable_gpu_debug_layers`, exact `gpu_debug_app`, ordered `gpu_debug_layers`
and `gpu_debug_layer_app` supplier packages. The layer is named
`VK_LAYER_RUSTY_QUEST_provenance`. The debug APK supplies the native library;
Android queries its exported introspection functions instead of consuming a
desktop Vulkan JSON manifest. See the official [Android activation
contract](https://developer.android.com/ndk/guides/graphics/validation-layer).

The device owner must snapshot the existing settings, select the exact app,
verify effective per-app activation before first Vulkan creation and restore
the exact prior settings afterward. Do not use the all-application
`debug.vulkan.layers` property. Layer settings are outside this source-only
candidate's effects; no host check changes them. Packaging or a requested
layer-name array alone proves no activation on Quest.

Recording additionally requires the exact raw property
`debug.rustyquest.vulkan_provenance.enabled=1` and an exact `/proc/self/cmdline`
match to the compiled application ID. A secondary process suffix is refused.
Admission freezes on the first intercepted `vkCreateInstance`, before its
downstream call. Later property changes cannot enable capture. Off or failed
capture still preserves ordinary dispatch forwarding.

The only sink is the current application's private file
`files/rusty-quest-vulkan-provenance-<pid>.jsonl`. It is opened exclusively with
mode 0600; an existing file or an unavailable early private directory fails
capture rather than overwriting evidence or enabling late. There is no
arbitrary filename, network sink or generic command transport.

## Dispatch, evidence and boundedness

The layer uses standard instance/device call chaining, with GIPA/GDPA and the
physical-device endpoint. Unknown functions forward; supported aliases remain
separate; downstream null availability is preserved. Recorder limits cannot
discard the next-dispatch custody needed for live Vulkan objects. No registry
lock spans a downstream call. Application feature/extension chains, allocators
and results are forwarded without diagnostic changes; standard loader-link
bookkeeping advances to the next layer. No handles are wrapped and no GPU wait
is inserted.
See [Khronos loader/layer interface](https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderLayerInterface.md).

Compact numeric JSONL records include process, sequence, monotonic time, thread,
call entry/return and instance/device generations. Object creation/destruction,
successful image bindings, memory frees and descriptor writes can be joined
offline. `pDescriptorWrites[3]` is write-array index 3, not binding 3: the
recorded `dstBinding`, destination set/array element/type and actual image view
are required. Debug-name hashes are supporting labels, never owner authority.

The initial recorder is limited to 262144 events, 32 MiB and 120 seconds, with
rows bounded to 2048 bytes, up to 32 numeric fields and a 16-row queue. It records explicit terminal
loss/limit states rather than silently truncating rows. Unknown descriptor
encodings, incomplete `pNext` interpretation, external images or missing create
history must remain coverage gaps. A finite window is not complete process
history; overlapping cross-thread operations are not ordered merely by their
log sequence. The recorder does not capture pixels, memory contents, shader
source or private media.

VVL may wrap handles, so layer order matters. The intended trace position is
above validation, but Meta's built-in injection may change effective ordering.
Record actual next-dispatch function/module identity, mapped VVL file/hash and
an observed synthetic image/view/memory/descriptor sequence before joining a
VVL handle to a trace handle. A loader trampoline address or a requested layer
order alone is insufficient. Do not disable VVL unique handles implicitly.

## Validation and later qualification

Focused controls compile the actual recorder and layer wrappers against host
leaves without a GPU. They cover forwarding and null/alias dispatch, multiple
instance/device generations, exact runtime selection, bounded loss, reentry and
destruction ordering. Android NDK header syntax checks are separate from those
host leaves. Run the source selection/artifact-observer controls with:

```text
python tools/checks/Test-VulkanProvenanceRecorderHost.py --root <quest> --output <quest>/target/provenance-recorder --cxx <host-cxx> [--vcvars <existing-vcvars64.bat> --windows-sdk <existing-Windows-Kits-root>]
python tools/checks/Test-VulkanProvenanceLayerHost.py --output <quest>/target/provenance-layer --vulkan-include <ndk-sysroot>/usr/include --cxx <host-g++> --android-clang <ndk-clang++> --mutation-controls
pwsh -NoProfile -File tools/checks/Test-VulkanProvenanceSelectionHost.ps1
python tools/check_quest_boundaries.py
git diff --check
```

The build owner must independently inspect actual selected and release native
packaging, loader exports, build inputs, signer/alignment and exclusion. Device
qualification must demonstrate earliest relevant SDK objects passing through
the layer, correct actual VVL handle domain and loss-free required history.
Trace metadata alone does not prove continuous playback, physical pixels,
performance improvement, a driver/SDK defect or a graphics lifetime repair.
