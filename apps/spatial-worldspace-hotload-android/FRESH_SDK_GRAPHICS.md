# Fresh debug SDK graphics loop

This extends the integrated workload's packaged graphics controls with the
same existing SDK0.14 `HotReloadFeature` used by the separate capability app.
It is graphics source iteration only: no native-library, private compute
kernel, persistent simulation state or general code replacement.

## One enabling developer build

Use the ordinary isolated owner builder with `-SdkGraphicsHotReload`. The
selection is recorded as `sdk_graphics_hot_reload_selected` in its input lock
and forwarded as `-PworkloadSdkGraphicsHotReload=true`. It defaults to false;
release tasks refuse true. Use the exact new public source/clean commit and
regenerate its ordinary input manifest; unchanged private inputs stay exact.
Do not claim an old packaged-only APK exposes this route. No new permission,
app-owned provider action or runtime pipeline is added.

## Host admission before any upload

Copy only the reviewed `workload.vert` and `workload.frag` sources into a
fresh edit directory, retaining a source-edit start time. Make an arithmetic
edit with the same interface. Run:

```text
python Prepare-FreshSdkHotload.py --source-directory <reviewed-edit-directory> --baseline-catalog <selected-assets/hotload/catalog.json> --glslc <pinned-glslc> --spirv-val <pinned-spirv-val> --spirv-dis <pinned-spirv-dis> --headers <selected-SDK-headers> --shader-config <selected-shader_config.json> --output <new-batch-directory>
```

The explicitly selected public CPU development scene also passes
`--development-feedback`; its source and manifest bind that compile define.
The original packaged catalog can remain an ABI/baseline reference for a fresh
compatible shader, but it is not a newly selected public catalog. The independent
atlas diagnostic grid belongs to a separate shader family: omitting that grid
from the public scene needs the coherent enabling APK, not a twelve-file workload
override. Keep raw record positions unchanged and qualify the packaged scene
with no stale external overrides before collecting code-generation comparisons.

The catalog and baseline binaries must be the exact inputs of the selected
enabling APK, not an unrelated earlier capability app. The preparer checks
their actual bytes, tool/header/config identities, compiles/validates all
eight vertex plus four fragment variants, and uses the existing reflector
to compare every ABI with baseline, including the80-byte material uniform.
Outputs have only fixed `workload.vert*.spv` / `workload.frag*.spv` names and
a source-hashed manifest. Compiler or resource/interface failure leaves no
published candidate directory and does not modify a prior batch. Existing
output directories are refused. These checks do not prove shader termination,
arithmetic correctness, SDK completion or device refusal of arbitrary bytes.

For an exact byte revert, use the same arguments with `--baseline-revert`
instead of `--source-directory`. This copies the hash-checked baseline twelve
files without recompiling them. The supplied catalog/manifest/source/tool
hashes must join the actual owner build and captured host invocation; an
untrusted manifest's claimed validation is not sufficient.

## Existing owner upload and SDK trigger

The separate capability helper's hardcoded package, `atlas_probe` filenames
and retired actor must not be reused unchanged. Use the current owner's
existing guarded capture/operator route with its selected package, APK,
serial, boot, actor and source span. The fixed remote root is:

```text
/sdcard/Android/data/<selected-package>/files/app_assets
```

Only upload the twelve exact names listed by `VERTEX` / `FRAGMENT` in the
preparer, with matching manifest byte counts/hashes. Preserve the exact
baseline batch for later revert. Verify each remote SHA256; after all twelve
match and fresh target guards still hold, send one package-scoped existing
SDK broadcast:

```text
adb -s <selected-serial> shell am broadcast -a com.meta.spatial.debugtools.HOT_RELOAD_PUSH_COMPLETE -p <selected-package>
```

No unqualified Gradle watcher, install or Activity launch is needed for a
compatible edit. This SDK debug receiver is **unauthenticated**; its presence
is an isolated developer choice, not an app-owned authenticated operator.
The twelve transfers are **non-atomic**. Partial/unknown writes or trigger
results retain uncertainty; no automatic replay or rollback is promised.
Host ABI refusal must stop before the first upload. Do not send a bad ABI to
the device merely to claim native rejection.

Read existing app-owned `status` before/after through the DUMP/shell provider.
Bind package/PID/process-start/activity-session and current scene/material/
mesh/entity identities. Keep the chosen CPU/GPU/texture/render settings
unchanged during one comparison; do not change them implicitly for this loop.
Capture and inspect visible vertex/fragment differences, then upload the exact
baseline files, hash-read back and trigger once to verify visible restoration.
SDK invocation has no completion callback: keep API invocation, observed
pixels, GPU retirement, eye scope and wearer acceptance distinct. Neither the
packaged controller's generation nor its receipt acknowledges an external
SDK broadcast. Do not manufacture that receipt from host activity.

Measure source-edit start through required compilation, upload/trigger,
observation and later inspection; report child costs separately from enclosing
elapsed time. First frame remains unknown unless independently timestamped.
Retain settings between runs per the current owner; wearer-flow validation is
separate. A new ABI/resource shape falls back to the normal rebuild route.

## Focused source checks

`python Test-FreshSdkHotload.py` exercises complete publication, exact revert,
compiler/ABI refusal, drift, fixed names and opt-in/release separation.
`python Test-SdkHotloadCatalog.py` retains the existing reflector/layout checks.
Run the affected Kotlin frontend/build checks at their normal owner boundary;
host controls alone establish no visible device result.
