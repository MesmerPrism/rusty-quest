# Optional Meta XR Operator debug layer

The inert `debug.meta_xr_operator` feature adds Internet permission and the
non-required `com.oculus.experimental.enabled` manifest feature. Select it in
the app spec, declare those exact manifest additions, and resolve the ordinary
app feature lock.

The layer does not select a rendering policy. Its packaging owner accepts the
existing `renderer.native_scene` route or the explicit
`ui.native_mesh_panel.original_product` closure with the original renderer,
background, full panel and simultaneous input. The latter retains
`solid-black-private-particles`; an ordinary 2D original renderer alone does
not admit this mesh input/API route.

Packaging additionally requires all five builder arguments:

```powershell
pwsh -NoProfile -File tools/Build-NativeRendererAndroid.ps1 `
  -AppBuildLock <resolved-feature-lock> `
  -EnableMetaXrOperatorDebugLayer `
  -MetaXrOperatorLibrary <external-android-arm64-library> `
  -MetaXrOperatorLibrarySha256 <exact-lowercase-sha256> `
  -MetaXrOperatorManifest <external-layer-json> `
  -MetaXrOperatorManifestSha256 <exact-lowercase-sha256>
```

Keep external proprietary files, machine paths and pins in private build
inputs. The public repository contains no layer binary. Default builds neither
package the layer nor set up an Operator server. Inputs supplied without the
selected feature, a selected feature without explicit opt-in, and a
non-debuggable Android manifest are rejected before compilation. Cargo's
optimization profile is independent of the Android debuggable contract.

The helper accepts only the closed Android Operator manifest identity,
API version 1.1 and its single external-tool extension. It verifies the exact
input hashes, bounded ELF64 little-endian AArch64 shared-library headers,
SONAME, file-backed string table, executable LOAD presence, non-executable
stack, valid LOAD alignment of at least 4 KiB, and the six Android platform
dependencies. No additional shared dependencies are admitted. LOAD alignment
values are recorded; this check alone does not establish compatibility with
every runtime page size or the app's negotiated OpenXR API version.

Exact bytes are staged at
`lib/arm64-v8a/libXrApiLayer_METAX_operator.so` and
`assets/openxr/1/api_layers/implicit.d/XrApiLayer_METAX_operator.json`.
Staging rechecks hashes and refuses existing destinations. The signed archive
must contain exactly that Operator pair with matching lengths and hashes;
unselected archives reject Operator-family entries. The owner manifest extracts
native libraries, so APK ZIP alignment is separate from ELF LOAD alignment.

The generated build manifest records the input closure and ELF facts with
`runtime_verified=false`. Its existing hash binds this record into the run
capsule without widening the capsule schema. The default output directory also
includes the external-pair fingerprint. This adds a layer to the app's existing
OpenXR instance; it introduces no second frame loop. Device connection,
runtime activation, agent tools and input simulation require separate checks.

Run `tools/checks/Test-NativeRendererMetaXrOperator.ps1` for synthetic ELF,
manifest, staging, archive rejection cases and the production descriptor
resolver join. Optional external inputs let the same host check inspect the
actual pinned pair without packaging or running it.

The packaging destinations follow Meta's official
[Quest setup](https://developers.meta.com/vr/documentation/unity/meta-xr-operator/quest/)
and [agent connection](https://developers.meta.com/vr/documentation/unity/meta-xr-operator/connecting-ai-agents/)
documentation. This remains an explicit experimental debug composition.
