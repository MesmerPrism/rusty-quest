# Native App Feature Library

This directory is the recursive feature library for `tools/Resolve-NativeAppBuild.ps1`.

App-building agents should request stable `feature_id` values in
`fixtures/native-app-builds/*.app.json`. They should not copy a broad runtime
profile or Android manifest. The resolver selects the transitive feature
closure, writes `native-app-settings.json` as the master settings surface, and
then emits runtime profile, property write plan, build env, and Android
manifest adapters from that settings artifact.

Module families:

- `core/`: Quest NativeActivity/OpenXR/Vulkan substrate.
- `background/`: mutually exclusive background/render route setup.
- `particles/private/`: downstream private-particle public ABI, placeholder,
  resident GPU ordering, mask texture, tracer, and renderer aggregate modules.
- `particles/hand-anchor/`: public hand-anchor particle renderer and ordering
  modules.
- `camera/`, `display/`, `environment/`, `hand/`, `input/`, `private-layer/`,
  `projection-target/`, `sdf/`, `stimulus/`, and `video/`: feature lanes that
  must be selected explicitly before their permissions, properties, or runtime
  markers enter an APK profile.
- `damaged/`: malformed descriptors used only by rejection tests.
