# Debug Vulkan metadata provenance layer

This optional source candidate records image, view, allocation, binding and
descriptor metadata on participating Vulkan dispatch chains. It supplies no
renderer admission, GPU lifetime repair or SDK coverage claim. The existing
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
to Vulkan entry symbols. The selected library must also have the real
`DT_FLAGS_1` `NODELETE` bit, inspected with `llvm-readelf --dynamic`. Synthetic ZIP/tool tests exercise these checks
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

### Diagnostic module residence

The selected Debug layer links with `-Wl,-z,nodelete`, keeping its code,
one-time admission and bounded recorder state resident until process exit.
An early disposable Vulkan instance can load the layer and claim its fixed
PID file before the main instance exists. Android's Vulkan loader closes a
layer library when its reference count reaches zero. Without module residence,
a later load can construct fresh static admission state and fail to open the
already existing evidence file. `vkDestroyInstance` itself does not reset
admission or the recorder.

This changes the diagnostic module's unloading policy. Instance/device and
image destruction still forward normally and remove their dispatch records;
no Vulkan object, GPU allocation or application lifetime is extended by this
selection. The capture retains its original finite limits and first-instance
start time. It neither reopens nor overwrites an old file, and failed admission
still stays off.

See the [AOSP layer-library close path](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/vulkan/libvulkan/layers_extensions.cpp)
and [Bionic's supported NODELETE linker option](https://android.googlesource.com/platform/bionic/+/refs/heads/main/android-changes-for-ndk-developers.md#dlclose-interacts-badly-with-thread-local-variables-with-non-trivial-destructors).
Actual Quest module residence, later SDK capture and VVL ordering still require
device evidence. A `NODELETE` ELF flag alone establishes none of those results.

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

The recorder is limited to 262144 events, 32 MiB and 120 seconds, with
rows bounded to 2048 bytes, up to 32 numeric fields and a 1024-row metadata queue. It records explicit terminal
loss/limit states rather than silently truncating rows. Unknown descriptor
encodings, incomplete `pNext` interpretation, external images or missing create
history must remain coverage gaps. A finite window is not complete process
history; overlapping cross-thread operations are not ordered merely by their
log sequence. The recorder does not capture pixels, memory contents, shader
source or private media.

The metadata queue is unrelated to Vulkan queue objects. One caller drains it
to the file sink outside the recorder mutex; concurrent callers can enqueue
without waiting for that sink. The first selected start allocates the bounded
queue on the heap, with no allocation on event calls. An allocation failure
leaves recording inactive. Unselected recorders allocate no queue. No writer
thread or GPU wait is added.

The header advertises `max_queue_rows`. One slot is reserved for terminal
evidence. A queue overflow ends the capture with `queue-limit`, observed
`queue_capacity`, `queued_rows` and `drainer_active`, plus a count including the
known rejected record. `dropped_events` is a lower bound on known lost records
at termination; later unobserved calls are not counted. A drainer being active
does not distinguish file I/O from descheduling. More queue capacity can absorb
bursts; it supplies no guarantee of a loss-free device capture.

VVL may wrap handles, so layer order matters. The intended trace position is
above validation, but Meta's built-in injection may change effective ordering.
For a comparison with validation, select both names explicitly in the device
owner's ordered per-app list and verify the actual validation library mapping;
a separate vendor validation property alone is insufficient evidence.
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
python tools/checks/Test-VulkanProvenanceRecorderHost.py --root <quest> --output <quest>/target/provenance-recorder --cxx <host-cxx> --mutation-controls [--vcvars <existing-vcvars64.bat> --windows-sdk <existing-Windows-Kits-root>]
python tools/checks/Test-VulkanProvenanceLayerHost.py --output <quest>/target/provenance-layer --vulkan-include <ndk-sysroot>/usr/include --cxx <host-g++> --android-clang <ndk-clang++> --mutation-controls
pwsh -NoProfile -File tools/checks/Test-VulkanProvenanceSelectionHost.ps1
python tools/check_quest_boundaries.py
git diff --check
```

Recorder controls pause the real sink while four producers enqueue 256
paired-call records, then verify complete FIFO output. A separate full-ring
case checks the reserved terminal slot and pre-terminal depth. Allocation
failure, disabled selection, reentry, sink failures with pending rows and
worst-width terminal fields are exercised against the actual implementation.
Mutation controls reject the former 16-row capacity and incorrect loss or
allocation behavior. These host tests establish no device capture guarantee.

The residence regression control requires an existing Linux ELF toolchain
(an installed WSL environment also works):

```text
python3 tools/checks/Test-VulkanProvenanceResidencyHost.py --output <quest>/target/provenance-residency --vulkan-include <ndk-sysroot>/usr/include
```

It compiles the unchanged production layer and recorder with Android admission
enabled, modeling only Android log/property calls, two exact file-open paths
and downstream Vulkan functions. Real host `dlopen`/`dlclose`, `RTLD_NOLOAD`,
ELF flags and exclusive files distinguish an unloadable probe-only trace from
a resident trace continuing into a second instance/device/image. Both variants
destroy all fake Vulkan objects. An initially off resident module remains off
after the modeled property changes. Host GNU-unique symbol pinning is disabled
in both variants so it cannot conceal the negative control.

This exercises Linux loader behavior and actual production admission state;
it does not attest the Quest loader, SDK dispatch coverage or GPU behavior.

The build owner must independently inspect actual selected and release native
packaging, loader exports, build inputs, signer/alignment and exclusion. Device
qualification must demonstrate earliest relevant SDK objects passing through
the layer, correct actual VVL handle domain and loss-free required history.
Trace metadata alone does not prove continuous playback, physical pixels,
performance improvement, a driver/SDK defect or a graphics lifetime repair.
