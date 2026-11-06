# SDK graphics preparation provider

`tools/sdk_graphics` is a host-only provider for an explicitly selected
SDK0.14 optimized, ten-vec4/160-byte material consumer. It contains no app,
shader source, private effect, device identity, upload, broadcast or APK build.
Other material layouts remain valid independent consumers but are unavailable
in this provider. Quest owns compilation/reflection; Hostess owns orchestration.

The independently authored compiler/reflection controls were extracted from a
qualified developer consumer. The extraction removes its packaged tint/offset
presets, app paths, runtime controls and legacy compilation selection. Source
directories, SDK tools, headers and variant configuration are explicit inputs.
The provider always uses `glslc -O`, SDK default Vulkan1.0 and `GRAPHICS_HSR`.
It validates eight fixed vertex variants and four fragment aliases, descriptor,
stage, block/member and push-constant ABI, including the selected material size.

```powershell
python tools/sdk_graphics/Prepare-SdkHotloadCatalog.py `
  --source-directory <reviewed-source> --output <new-catalog-directory> `
  --glslc <glslc> --spirv-val <spirv-val> --spirv-dis <spirv-dis> `
  --headers <sdk-headers> --shader-config <shader_config.json>
python tools/sdk_graphics/Prepare-FreshSdkHotload.py `
  --baseline-catalog <new-catalog-directory/catalog.json> `
  --source-directory <reviewed-edited-source> --output <new-batch-directory> `
  --glslc <glslc> --spirv-val <spirv-val> --spirv-dis <spirv-dis> `
  --headers <sdk-headers> --shader-config <shader_config.json> --require-sdk-primary
```

Use `--baseline-revert` instead of `--source-directory` for exact baseline bytes
without compilation. Each output is CreateNew and published only after all
members validate; failed preparation preserves prior catalogs. Keep compiler,
header, configuration, source, baseline and script provenance with the actual
invocation. Input drift checks are diagnostics, not an atomic source lock.

A catalog alone is not an enabling APK. Before live use, join all twelve names
and aliases to the actual installed APK's normal primary shader bytes and exact
build selection. Host ABI acceptance proves neither shader termination nor GPU
completion or visible adoption. This provider does not enable the stock SDK
debug receiver, authenticate it, dispatch reload, or claim a runtime receipt.
`Qualify-SdkGraphicsApk.py --apk <apk> --catalog <catalog.json> --out <new-report>`
performs the actual ZIP manifest/primary/alias/packaged-baseline byte join, with
bounded decompression and duplicate/traversal refusal. Receiver enablement is
deliberately false in that report; retain the app owner's separate actual-APK
build-selection evidence. A matching build-lock label alone is insufficient.

Run the three `Test-*.py` files in this directory. They exercise actual provider
functions with controlled compiler edges, exact revert, ABI/profile/variant
refusal and failed-publication preservation. Real SDK compilation and headset
observations remain separately retained evidence.
