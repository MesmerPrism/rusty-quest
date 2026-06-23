# liblsl Android staging

Rusty Quest does not vendor liblsl source or binaries in this repository. Native
renderer APK variants that select `lsl.outlet` or `lsl.inlet` expect an Android
ARM64 `liblsl.so` staged under:

```text
local-artifacts/liblsl-android/arm64-v8a/liblsl.so
```

Use `tools/Stage-LibLslAndroid.ps1` with an explicit liblsl source checkout.
The staging script verifies the requested upstream commit, builds with the
Android NDK CMake toolchain, copies `liblsl.so`, and writes
`local-artifacts/liblsl-android/liblsl-android-provenance.json`.

The default target is upstream liblsl `v1.17.7`
(`64988c6a14b8dc3b3f270ece58eab4f480bfab43`).
