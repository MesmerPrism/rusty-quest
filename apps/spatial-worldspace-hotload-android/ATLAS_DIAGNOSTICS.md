# Fixed consumer atlas diagnostic

This successor adds an independent debug material and fixed SDK host geometry.
Its position, opacity and fragment output do not depend on packet validity,
record positions, radii, sourceReady or frame tags. Default restoration/row settings and private physics kernels are unchanged.
The workload consumer now checks the RWA4 generation and hand record layout.
No workload geometry or high-rate GPU data is read back to the CPU.

`atlas-diagnostic-grid.json` is the capture decoder contract. The grid has 56
rows and 32 columns. Columns run left to right, least significant bit first;
rows run top to bottom. SDK cell centers are `(-0.465 + column*0.030,
2.105 - row*0.022, 1.6)` with half extents `(0.013,0.009)` and identity object
transform. Screen projection is not a uniform rectangular sampling guarantee.
Each eye/capture must fit an observed planar homography using the four unique
colored-center fiducials and validate their black/white guard rings. Coordinates,
colors and guard extents are declared in the JSON calibration contract.
Reject clipping, border sampling, low contrast and uncertain cell assignment.
Payload x spans -0.465..0.465 at z1.6; marker centers are x±0.60,
y2.24/0.76 with outer halfextent0.074. Actual pose/FOV or other geometry can
still clip/occlude the plane. Source positions do not prove visibility. Reject
missing markers, ambiguous correspondences, low contrast, clipped guards or
uncertain cell interiors; never substitute guessed screenshot coordinates.

| Rows | Meaning |
| --- | --- |
| 0 | Columns 0–7 fixed white, 8–15 fixed black, 16–18 page low3, 19–20 profile low2, 21–28 revision low8, 29–31 white/black/white guards. |
| 1, 2 | Actual sampled texture width and height, 32 bits each. |
| 3 | 31 header predicates, indexed in the JSON; green pass, red fail, magenta unavailable, column31 blue/unused. These observations are independent of workload admission. |
| 4–11 | Pages0–5: eight header words `page*8 + row-4`. Page6: public AUX0 XYZW then AUX1 XYZW. Page7: public primary0 XYZ, primary1 XYZ, h44 kernel generation, h45 kernel tag. All 32 bits. |
| 12–16 | Header words 4, 5, 35, 17, 47: producer generation, submission, sourceReady, recorded source steps, source-state generation. Kernel generation/tag remain on header page5. |
| 17–19 | Raw sampled R/G/B of the first texel touched by this page's first word, using the selected lookup row profile. |
| 20–31 | Raw physical corners `(0,0)`, `(W-1,0)`, `(0,H-1)`, `(W-1,H-1)`, three R/G/B rows per corner. |
| 32–43 | Raw physical header texels `(0,0)` through `(3,0)`, three R/G/B rows per texel. |

All U8 rows use columns 0–7; remaining columns are black. Raw channel values
are `round(clamp(sampledLogicalRGB,0,1)*255)`, not guaranteed original encoded
bytes. Physical corners and header texel rows apply neither restoration nor
flip. Corners may all be background; they are inconclusive without unique
producer anchors. All fetches check actual texture bounds first. Magenta means
unavailable lookup, not a header value. Flipped packet lookup remains
`1023-y` using expected 1024-wide addressing; it is not `actualHeight-1-y`.

The debug UI and DUMP/shell-UID provider share `diag-page` (exact strings 0–7)
and `diag-profile` (`raw-normal`, `restored-normal`, `raw-flipped`,
`restored-flipped`). Profile codes are 0–3 respectively, with restoration in
bit0 and flip in bit1. These only update the independent diagnostic material.
Its page/profile/revision bits allow capture-to-receipt joining; revision is
modulo256 on the grid, with the full revision retained in status. These calls
do not change workload controls or infer a repaired transport. Release has no
diagnostic controller/provider. Effective app status now includes workload
`restoreSrgb`, `flipRows`, `hostGeometry` and the independent diagnostic state.

## Fixed public record pages

Pages0–5 retain their previous byte addresses, profiles and geometry. Pages6/7
read only the two public diagnostic records. Their payload rows and raw RGB
rows17–19 require a ready GPU RWA4 packet, all31 header checks, the active
producer generation, and the public primary offset from the admitted manifest.
Counts must be2, the AUX offset must follow the two primary records, the four
records must fit the atlas, and native kernel generation/tag must be valid.
CPU, stopped, stale, inaccessible or malformed inputs render these selected
rows magenta. The independent anchors, dimensions and existing header rows
remain visible. These checks cannot authenticate arbitrary malicious shader
writes; this is bounded diagnostic addressing, not shader semantic safety.

For primary offset D=h40, AUX offset A=h42 and record i=0/1, the byte addresses
are `192+24*(D+i)+4*word` and `192+24*(A+i)+4*word`. New-page raw RGB rows sample
the first texel touched by the selected page's first public word, applying flip
but no restoration. The grid's `pageOverrides` gives exact labels; old header
labels must not be reused as h48..63 for these pages. Unobserved base offsets
must remain symbolic in a capture decoder.

`app/src/debug/fixtures/hotload_words_finite.comp` defines stable finite XYZ
and unused AUX.z/w sign/mantissa patterns. `hotload_word_patterns.json` records
the source-defined expectations. The separate `hotload_words_qnan.comp` changes
only unused AUX.z/w to selected quiet-NaN payloads; preservation is unproven.
Prepare either source through `Prepare-ExternalHotloadKernel.py` with actual
compiler, app, session and active-producer context before using the existing
closed staging/activation route. Neither source is automatically selected.

Primary XYZ is preserved only when all XYZ/radius validity checks pass.
NaN/Inf/out-of-range primary data is sanitized. Radius is UNORM16 plus flags,
normal is octahedral SNORM16 and color is UNORM8; none is a raw float word.
AUX.z/w bypass those packer sanitizers and are unused by the workload geometry.
The finite candidate is the first probe. A quiet-NaN mismatch may originate in
compiler/float handling rather than the Surface. A selected matching32-bit
word proves neither every possible word nor full-packet synchronization.

Join observed page/profile/revision and complete per-eye words to the native
active digest, effective generation and completed serial. Keep candidate values
stable during capture; do not inject expected words into pixel calibration.
Source tests and synthetic captures are separate from shader compilation,
producer execution, SDK consumption and wearer acceptance.

Native `wsi` metadata reports selected format/colorSpace/currentTransform and
extent after successful swapchain creation. `presentCalls` counts actual
`vkQueuePresentKHR` calls, `presentAccepted` counts VK_SUCCESS or
VK_SUBOPTIMAL_KHR returns, and `lastPresentResult` is the actual VkResult.
Submission/fence counters remain separate. No counter proves presentation
completion, SDK acquisition, wearer visibility or photon timing. Before
`selectionAvailable`, zero-valued selection fields are unavailable placeholders.

## Next controlled Q or Quest 2 run

Acquire a fresh exact loan/custody binding after the build lane is available;
deploy a new source-locked inspected APK/capsule. Preserve the frozen previous
APK, its receipts and the current headset settings/keepers. Start CPU reference,
capture the anchor row and all six pages in restored-normal, then capture the
four diagnostic profiles on page0 with status/identity joins. Confirm the
workload flags remain true/false/false. Wrong diagnostic page/profile requests
must reject without a revision change.

Start the existing genuine GPU producer, require advancing submission, fence
and accepted-present counters. Capture the same page/profile inventory while
workload controls stay unchanged. Join h4/h5/h35/h17/h47 and visible metadata
to native generations/transactions, allowing capture time to differ from host
status time. Do not require a captured submission to equal a later host counter.

For expected RWA4 bytes, texel0 logical RGB is `(82,87,65)`, texel1 is
`(52,4,0)`, texel2 is `(0,0,0)`, and texel3 is `(4,0,0)` before any import
transfer. These are expected encoded bytes, not a promise that raw sampled
values equal them. A working restoration profile can differ from raw samples.

Use evidence to narrow the next change: missing anchors indicate geometry,
view or shader/material availability; wrong dimensions indicate an extent or
binding/import question; magenta marks bounded inaccessible coordinates;
individual failed predicates identify sampled-field disagreement; changing
h4/h5 supports freshness while fixed words can expose stale/background data.
Raw/restored differences illuminate transfer behavior; normal/flipped
differences illuminate lookup orientation. None alone proves a gamma, channel
or row repair. B8G8R8A8 storage does not require swapping logical R/B.

Stop the independent producer with app retirement acknowledgment before typed
package stop/quiescence and loan return. Restore only this run's explicit
temporary deltas. Protected wearer prompts are never dismissed automatically.

## Host validation boundary

`AtlasDiagnosticHostTest` executes the actual Kotlin protocol for every header
predicate failure, page/word mapping, all32 bit positions and anchor metadata.
Source checks join the protocol and fixed GLSL cell map. After build-lane
release, compile the actual debug/release frontends and all SDK diagnostic
shader variants, validate SPIR-V and inspect their ABI. Static and host tests
do not execute GLSL on a headset or prove sampler conversion. The private physics kernels remain unchanged; the adapter now exposes exact recorded source-state metadata.

For synchronization qualification, hold publication after its producer fence, capture a stable h4/h5 and payload token set in the SDK grid, then resume and observe fresh matching pairs. Allow queued presentation to settle after pause becomes effective. Freeze simulation separately: source header17 must stay fixed while publication and pack tokens advance. Header47 identifies the source generation; neither it nor AUX tokens proves completion inside the private kernel. A stale generation or a frame differing by64 must reject workload records, and one stale triangle corner must reject its entire triplet. The diagnostic grid deliberately remains visible to inspect rejected packets. Match captures to status; do not infer consumer sampling from accepted-present or fence counters.

The fiducials are packet-independent shader output with no texture fetch: TL cyan, TR yellow, BL orange, BR violet. Each has a solid0.10m square center, black guard to0.124m and white guard to0.148m, implemented as nonoverlapping planar quads. Fit each eye independently from observed marker centers and cross-check projected guard corners and cell interiors. Calibration success is not packet coherence: active captures can be torn or temporally mixed and must be reported as such. Stable publication-paused captures and active progress captures are separate observations.
