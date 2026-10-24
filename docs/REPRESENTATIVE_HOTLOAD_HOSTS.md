# Dedicated development hosts

The native OpenXR/Vulkan and Spatial SDK hosts are public procedural test apps.
They exercise scene adoption alongside retained CPU code replacement. Their
content does not require a downstream product, a scientific packet producer, a
camera stream, or a peer headset. The bilateral streaming path has its own
packages, sources, devices and build caches.

The first scenario is a fresh A build, a compiled B edit and a freshly compiled
A restoration. The result changes mesh/particle spacing and color in the real
host scene while the counter and scratch allocation continue. An incompatible
candidate must be refused and the prior generation must still compute. Selecting
an already retained A is a useful rollback check, but does not count as the
freshly compiled A restoration.

## Content and change boundaries

| Content | Useful change | Evidence needed |
| --- | --- | --- |
| UI and panels | Text, layout, click state, panel geometry/pose | New visible content, working input, retained state and correct owning Activity/scene |
| Meshes | Vertex/index changes and replacement topology | Visible silhouette/count change, valid bounds and safe old-resource retirement |
| Particles | Count, size, spacing, motion and color | Scene change plus continuing simulation state; explicitly record workload size |
| Shaders and materials | Tint/position shader edit and restoration | Actual newly compiled shader identity, compatible interface, successful replacement and visible effect |
| Textures and fonts | Pattern/glyph change and restoration | Exact input identity, new sampled content, correct dimensions and resource retirement |
| Tracking | Controller/hand enablement, recenter and tracking loss/recovery | Requested setting versus effective tracking/input state; preserve the runtime's coordinate-space owner |
| Settings and permissions | Allowed, denied and restored paths | Current manifest/grant, useful denied-state UI, restart requirement and effective app behavior |
| Resource lifetime | Repeated reload, pause/resume and scene recreation | No use of retired objects, bounded retained resources and explicit owner identity |

Not every row uses the same reload boundary. Scalar parameters can update live;
code, assets and shaders use their respective replacement routes. Texture size,
mesh topology or a pipeline layout can require resource recreation. Manifest,
native ABI or packaged dependency changes require a new APK. Android grants and
system tracking settings remain OS/runtime operations. Record the actual
boundary in each scenario instead of calling every change a hotload.

The matrix defines intended coverage. A host test or successful setter is not
pixel evidence, and an implemented row is not a qualified device scenario.
Retain per-run observations and captures for the exact app and build.

## Existing build and reload owners

The optional agent-input builds use separate packages. Resolve
`fixtures/native-app-builds/native-development-hotload-operator.app.json` with
the existing resolver and pass its lock to the native builder with
`-EnableMetaXrOperator`, `-MetaXrOperatorLibrary`, its SHA256,
`-MetaXrOperatorManifest` and its SHA256. The explicit selected feature is
`debug.meta_xr_operator`; selection and pinned external layer bytes participate
in the canonical output identity. The original six-feature fixture remains the
ordinary no-INTERNET host.

The SDK builder selects its fixed `.operator` package only with
`-DevelopmentFeedback -MetaXrOperator`, exact `-MetaXrOperatorFeatureLockPath`
and SHA256, library path/SHA256 and manifest path/SHA256. Its SDK app contract
still owns composition, shader/workload selection, package and Activity.
Operator artifacts, INTERNET, optional experimental declaration and geometry
observers remain absent from ordinary/unselected/release builds. SDK selected
packaging also requires actual native-library extraction.

Both operator variants expose current original Compute bounds and completion
traces through status readback. See [the finite hand-target
adapter](META_XR_INPUT_AUTOMATION.md#original-compute-targets-in-the-development-hosts)
for geometry, live session, original-handler and exact actor-retirement checks.
This route needs an enabling APK; a sidecar cannot attach the layer to an
already installed no-INTERNET app. Simulated input does not qualify the broader
tracking, font/topology replacement, DEX scene or stress matrix above.

Resolve `fixtures/native-app-builds/native-development-hotload.app.json` with
`tools/Resolve-NativeAppBuild.ps1`, then pass its exact feature lock to the
existing `tools/Build-NativeRendererAndroid.ps1`. The development capability is
explicitly selected; the normal native app remains inert. See
[native integration](NATIVE_WORLDSPACE_INTEGRATION.md).

For the SDK host, prepare a fresh public workload directory with
`apps/spatial-worldspace-hotload-android/Prepare-DevelopmentFeedbackInputs.py`.
Use the existing `Build-SpatialWorldspaceHotload.ps1 -DevelopmentFeedback` and
that exact directory. The public `development_scene.v1` packet is separate from
the original scientific workload contract. Select no session core, external
compute backend or submission observer. The canonical builder owns dependency,
signer, source and permission inspection; the operator below does not build or
install APKs. Existing SDK shader/asset controllers retain their own contracts.

Use distinct package, intermediate, output and cache addresses. Reuse verified
tool distributions and dependency caches. Retain the canonical build source
composition and assert its inputs remain current after the build.

## Resident code and observations

`crates/rusty-quest-development-feedback-native` wraps the existing tested native
ABI, loader, guarded buffer and state owner. It is also source-included by the
native renderer without changing the parent workspace dependency graph.
`android/development-feedback-host` composes the existing DEX host and JNI bridge
without retaining an Activity, SceneObject or texture. App bindings own effective
scene projection and debug admission.

Native generations are trusted in-process development code. Validation checks
the ABI, table size, contract and probe before activation. It is not a sandbox:
a faulty implementation can crash or corrupt the app. Loaded images are retained
until the process exits. The native session allows 64 activation attempts,
4 MiB per image and 64 MiB total received image bytes. The Java staging wrapper
charges failed candidates as well as accepted ones, with 64 attempts, 16 MiB per
archive and 64 MiB cumulative staging bytes. A used generation ID cannot create
another retained artifact. Restart explicitly when a budget is exhausted.

Native graphics resources belong to the render owner. The first CPU scenario
retains that owner during reload; Activity/render-owner recreation starts a new
native host. The SDK resident CPU host is process-owned, while each Activity
owns its SDK objects. Rebinding a new SDK scene does not make old GPU objects
process-owned. Finite lifecycle checks do not establish leak freedom.

The closed command/observation contract is
`schemas/rusty.quest.hotload_scenario.v1.schema.json`. Native effective observations
are released after a successful OpenXR frame submission. SDK texture update
observations prove the app called the selected texture update route; captures
are required to prove the resulting pixels.

## Finite local operator

`tools/development_feedback/quest_hotload.py` uses the existing private-file
native transport and debug-only, shell-UID/DUMP-gated SDK provider. Supply the
exact ADB binary, serial, host, observed process ID and current session ID.
It never discovers or changes the shared ADB daemon, installs an app or selects
another headset.

Read `files/development-feedback-native/current-session.json` for native, or
`files/development-feedback/current-session.json` for SDK using package-scoped
`run-as`. These identify the current process and fresh host session. The SDK
descriptor also identifies its native bridge session. Stage candidates with
`stage <file> --kind native|dex|bridge|vertex|fragment --generation <id>` as
appropriate. The operator computes the digest and publishes a completed readonly
file into a fixed target under a create-new identity lock. Android app-data
SELinux prohibits the hard-link publication used by some host filesystems, so
the operator uses a no-clobber rename. The retained per-target lock serializes
operator writers. A failed publication consumes that identity; choose a fresh
generation/request instead of replaying it. Candidate bytes are never an arbitrary
command path or provider extra.
Binary input uses ADB `exec-in`; its exit alone does not establish remote
completion. The operator reads back a nonce-bound completion marker and the
target's SHA-256, size and readonly mode before reporting successful staging.

Write one closed JSON command, then call `send <command.json> --journal <dir>`.
For example, a native compute command is:

```json
{"schema":"rusty.quest.hotload_command.v1","request_id":"scene-a-1","method":"native_compute","arguments":{"input":"4"}}
```

For a single fresh Android CPU generation, use
`tools/development_feedback/Build-NativeGeneration.ps1 -Rustc <selected-rustc>
-AndroidNdk <selected-ndk> -Generation <fresh-number> -Variant A|B|WrongAbi
-OutputRoot <fresh-directory>`. This uses the same ABI, source and rustc/NDK
route as the existing native conformance build. An optional `-PluginSource`
selects a reviewed edited implementation. The receipt binds actual source,
tools, arguments, ELF identity, output hash and elapsed time. Compile B after
observing A, then compile the restoration into another fresh generation after
observing B. For a saved source edit, pass the same edited `-PluginSource` file
and retain its changed and restored hashes; selecting the bundled B compile
configuration alone does not prove a saved source edit. With the bundled source
and input4, A/B/fresh-A produce deltas
5/10/5 while retained cumulative state becomes 5/15/20. Probe expectations are
13 for A and 19 for B; the native loader uses input5 and prior7 for its probe.
The SDK JNI lane uses the same modules and its process-owned bridge; DEX
replacement remains a separate Java/DEX compilation and staging route.

Use fresh request IDs. A create-new local dispatch journal is retained before
the mutation. After interruption or timeout, use `observe <request-id>` to read
the original result. Do not replay the operation: a timeout can occur after
execution began. A new status request is read-only observation, not proof that
an earlier command did or did not execute.

The operator performs no automatic deletion. App-private candidates remain
until explicit app cleanup or process/session retirement; retain run evidence
outside the APK source. Inspect exact helper and headset custody before device
work and leave an explicitly adopted renewing helper running as requested.

## Host verification

`tools/Test-RepresentativeHotload.ps1` exercises real compiled Java generations,
the actual selected JNI bridge and native images, and the shared Rust host.
Supply the trusted bridge digest and artifact manifest from the canonical native
feedback build. It records exact input hashes, commands, exits and source
currentness. `python tools/development_feedback/test_quest_hotload.py` checks
closed commands, duplicate decoded keys and create-new dispatch journals.
These checks do not substitute for Android packaging, graphics or device proof.
