# Derived primary input and final sampler ABI2

The optional derived Primary bank is a local renderer input. It changes neither
the input-set/media ABI1 nor the original Own/Peer identities and authority.
Private providers own the input blend formula. The platform owner records that
blend after both normalized/reprojected original raw targets are complete, then
runs the existing guide schedule from pass1 through pass5 on Primary. Raw pass0
must not overwrite the blend. Downstream private guide stages must sample these
Primary targets, without camera sampling or another reprojection. Explicit Own
and Peer guide choices remain separate; FollowRegion can select Primary under
the provider's enabled-mask policy.

Selection requires all of the following in the authenticated profile bridge:

- `stereo_input_set_abi_version: 1` (unchanged original source inputs);
- `source_bank_final_abi_version: 2`;
- `source_bank_final_policy_uniform_abi_version: 2` and
  `source_bank_final_policy_uniform_bytes: 64`;
- `source_bank_final_descriptor_shape` equal to
  `final-set1-bindings4,6,7,8-array3-plus-final-set5-binding1-std140-64bytes`;
- `primary_input_shader`, a profile-relative regular file contained by the
  explicitly supplied `-PrivateLayerProviderRoot`. No file or ancestor links.

The resolver includes the exact primary shader SHA256, final ABI and provider
root in `source_bank_inputs`, which enters the build-input lock/fingerprint and
build manifest. Cargo selects the final shader define
`PRIVATE_LAYER_SOURCE_BANK_ABI=2`, the explicit primary fragment provider and
generated ABI metadata together. Missing/inconsistent providers fail instead
of falling back. Original profiles retain final ABI1 and no primary provider.

Final set1 bindings4/6/7/8 select targets0/2/3/4 (raw, preblur brightness,
raw strength, blurred strength), each as Own/Peer/Primary arrays of length3.
Internal target1 remains the blur temporary and is not a final binding. The
final policy block consists of four std140 uvec4 rows: region origins, guide
origins, completed prefixes Own/Peer/Primary plus ABI2, and neutral mask words
plus reserved zero. With depth and one/two video samplers the final pipeline
uses14/15 image samplers. Physical-device checks conservatively reserve19/20
stage resources, four uniform buffers, six bound sets and128 push bytes.
The primary input draw uses two raw samplers and the existing112-byte opaque
push shape; it samples packed per-eye targets without a second reprojection.

Each frame clears all three banks, including unavailable attachments. Original
raw passes finish in shader-read layout with color-write to fragment-read
visibility before Primary samples them. The Primary raw draw similarly becomes
visible before its continuation; final visibility includes vertex and fragment
stages for all banks. Missing/partial originals leave Primary prefix0 and legal
cleared bindings. The provider suppresses all Primary paths, including geometry,
until prefix6 is available. No previous Primary content is reused.

All three resource banks belong to the same source-bank owner. Original byte
leases and content-hold tokens are installed before recording; the same actual
VkFence/SDK submission identity owns the derived commands. Unknown retirement
retains resources and source contents. Only genuine fence retirement or the
existing typed unsubmitted proof permits reuse/release. Creation failure cleans
every allocated pipeline/layout/pool/bank; continuation failure restores swapped
views before returning. No new timer, replay, media route or permission is added.

Readback adds the actual final ABI, derived completed prefix and two full
original source identities, control/mask/processing/geometry provenance.
These become retired-frame observations only through existing exact retirement.
The five-pixel diagnostic strip remains an input-layer diagnostic; neither it
nor recorded guide completion proves final-stack pixels or wearer output.

Primary requires an additional bank allocation and one raw draw plus five guide
passes when enabled. No performance or memory equivalence is asserted without
measurement. Host controls model Vulkan calls; native Android compilation and
shader validation establish source/type/layout closure, not device rendering.

The ordinary camera host gate registers `Test-StereoPrimaryInputHost.ps1` for
the production continuation and resolver. Rust unit tests cover ABI2 shape,
missing/disabled banks and exact derived retirement provenance. Run the native
crate tests/check in the selected supplier graph; do not silently replace its
accepted dependencies with a nearby checkout.
