# Spatial VR Strobe Android

This directory owns the standalone Spatial VR Strobe application. Its Android
application module is `:strobe-app`; it contains its own Activity, manifest,
resources, controller adapter, panel UI, renderer, shaders, profiles, tests,
and browser profile editor.

The shared Gradle root remains
`apps/spatial-camera-panel-android` so both applications reuse one dependency
cache and can be compiled together. The only source dependency between them is
the neutral `:spatial-sdk-shared` Android library. Strobe does not compile the
Camera product and declares no camera permissions.

Build and test it with:

```powershell
pwsh -File tools/Build-SpatialVrStrobeAndroid.ps1
```

The output APK is
`target/spatial-vr-strobe-android/rusty-quest-spatial-vr-strobe.apk` with
package `io.github.mesmerprism.rustyquest.spatial_vr_strobe`.

The accepted MOD-010 through MOD-012 records remain in the legacy integration
workspace as immutable provenance. New Strobe units belong in this directory's
`morphospace/` workspace.
