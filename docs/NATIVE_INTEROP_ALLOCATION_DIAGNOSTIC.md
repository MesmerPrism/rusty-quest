# No-render interop allocation diagnostic

The Spatial Camera Panel can select which disposable allocations its existing
no-render interop probe attempts. This is a debug-only, construction-time
selection. It helps distinguish the disposable SDK `PanelSurface` from the
temporary Vulkan instance/device probe without changing real renderer admission.

The fixed Android property is
`debug.rustyquest.spatial.native_interop_probe.mode`. Only the exact strings
below select a mode; whitespace, other values, an absent property or a read
failure select Full. Release always selects Full without reading the property.

| Value | Token | Disposable PanelSurface | Temporary Vulkan objects |
| --- | --- | --- | --- |
| `0` | `full` | Existing phase policy | Existing query prerequisites |
| `1` | `borrowed-only` | Skipped | Skipped |
| `2` | `surface-only` | Existing phase policy | Skipped |
| `3` | `vulkan-only` | Skipped | Existing query prerequisites |

The Activity selects once when constructing its interop coordinator. Changing
the property later does not change that coordinator, including later scene/VR
callbacks. A comparison needs a fresh process with its mode selected before
construction. There is no live toggle. This does not disable the interop probe:
Scene XR handle capture, receipt library loading, lightweight OpenXR queries,
function resolution, input bootstrap and existing callback sequencing remain.
When Surface allocation is allowed, scene-ready still defers it until the
existing VR-ready phase. Temporary Vulkan allocation remains independent of
that phase, subject to its existing successful query/resolution prerequisites.

## Receipt and admission boundaries

`channel=native-interop-allocation-mode` reports the selected numeric mode/token,
requested Surface/Vulkan allocation, phase request, Surface attempt/skip and
result. A skipped Surface has `surfaceValid=false`. The existing Surface
constructor/destroy behavior is unchanged; a throwing destroy still interrupts
the callback rather than publishing successful destruction.

The selected native entry is
`nativeRecordSelectedNoRenderInteropReceipt(JJJZZ)J`: three borrowed XR handles,
the observed Surface validity and an explicit Vulkan-allocation Boolean. The
legacy `nativeRecordNoRenderInteropReceipt(JJJZ)J` remains exported and retains
Full Vulkan behavior. The Activity calls only the new entry. Missing linkage
produces the existing `call-failed` receipt; it never falls back to legacy
allocation. Bind Kotlin and native library source together in an APK.

Both native markers report `receiptEntry`,
`allowVulkanObjectConstruction`, `vulkanObjectConstructionAttempted` and
`vulkanObjectConstructionSkipped`. Attempted becomes true only on entry to the
temporary Vulkan allocation helper. Allowed but failed prerequisites can leave
both attempted and skipped false. Native does not infer the four-mode selection
from `surfaceValid`; join it to the app mode marker.

The no-render mask preserves its meanings: bits 0-3 describe receipt/borrowed
handle presence, bit 4 observed disposable Surface validity, bits 5-14 actual
OpenXR query/resolution facts, and bits 15-20 temporary Vulkan object facts.
Skipped construction leaves all created/destroyed bits clear. With all retained
queries succeeding and nonzero handles, BorrowedOnly yields 32751; SurfaceOnly
with a valid Surface yields 32767. Full with all facts true yields 2097151;
VulkanOnly with all its facts true yields 2097135. These are fixture examples,
not required runtime masks or an admission threshold.

The real SDK session binding, V2 device-binding checks and renderer capability
mask `0x0f` are separate. No partial no-render receipt can admit a renderer.
Actual projection `runIfRequested`, SDK session capture, Own/Peer camera and
duplex paths, input, retirement and cleanup remain outside this selection.
This diagnostic does not repair the SDK/layer object cache, prove a Vulkan
lifetime cause, or remove all native work from an SDK-only journey.

## Focused source checks

Use fresh output directories. These commands do not assemble an APK or contact
a headset:

```text
python tools/checks/Test-SpatialNativeInteropLifecycleHost.py --repo-root <quest> --output-root <quest>/target/interop-kotlin --java-home <jdk17> --kotlin-lib <compiler-jars>
python tools/checks/Test-SpatialNativeInteropAllocationHost.py --root <quest> --output <quest>/target/interop-native --negative-controls
pwsh -NoProfile -File tools/checks/Test-SpatialCameraPanelAndroidStatic.ps1
pwsh -NoProfile -File tools/checks/Test-SpatialCameraPanelSdkDepthHandoffStatic.ps1
python tools/check_quest_boundaries.py
git diff --check
```

The Kotlin fixture compiles the actual policy/coordinator and extracted actual
Activity initializer/JNI declarations with modeled SDK/JNI leaves. It exercises
all modes, defaults, release, frozen selection, allocation failures and missing
selected linkage, and inspects actual compiled JVM descriptors. The standalone
Rust fixture compiles actual production JNI/query/mask/marker bodies with fake
OpenXR PFNs and an allocation leaf. It checks query failures, truthful masks,
legacy compatibility, mutation sensitivity and actual host DLL export names.
It does not compile the entire Android native crate or validate the real
OpenXR/Vulkan ABI, driver, SDK or device lifetime.

The build owner must independently inspect the actual selected source/class
closure, both JNI exports in the packaged Android library and the matching
Activity declarations across DEX. Release must retain Full behavior regardless
of property selection. Real allocation, effective markers, renderer registration
and playback still need separately owned artifact and fresh-process device
qualification. Preserve the comparison artifact and its exact source/resource
inputs. See [component closure](ANDROID_COMPONENT_CLOSURE.md) and
[immersive media lifetime](SPATIAL_IMMERSIVE_VIDEO_PLAYBACK.md).
