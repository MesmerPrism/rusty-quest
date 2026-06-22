# Native App-Build Workflow

Use this workflow when creating a new Rusty Quest native APK profile.

1. Choose the smallest required feature IDs from `fixtures/native-app-features/`.
   Browse by module path; particle capabilities are nested below `particles/`.
2. Create or update a spec in `fixtures/native-app-builds/`. List requested
   features, denied features, expected manifest entries, expected markers, and
   `settings_assertions`.
3. Resolve the spec:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File tools/Resolve-NativeAppBuild.ps1 -AppSpec fixtures/native-app-builds/<app>.app.json -DryRun
   ```

4. Inspect `local-artifacts/native-app-builds/<app>/feature-lock.json` and
   `native-app-settings.json`. The settings file is the master app settings
   surface; runtime profile, property write plan, Android manifest, and build
   env are generated adapters. The generated lock also records:

   - `settings_hotload`: which low-rate settings can be changed live, which
     transports are allowed, and which changes require restart or rebuild.
   - `permission_pregrant`: the exact declared permission/app-op surface that
     must be prepared before first launch.
   - source hashes for the app spec, selected feature descriptors, and
     generated build artifacts. The APK build refuses stale locks; re-run the
     resolver after changing a spec, copied downstream feature descriptor,
     generated manifest, generated settings file, or build-env file.
5. Build from the lock:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File tools/Build-NativeRendererAndroid.ps1 -AppBuildLock local-artifacts/native-app-builds/<app>/feature-lock.json
   ```

   A successful package should also be checked against
   `target/native-renderer-android/build-manifest.json`. For downstream apps
   that package optional private shader/payload inputs through generated
   `build-env.json`, verify the manifest reports the expected packaged payload
   booleans before launch. Build-env values select APK contents; runtime
   profiles and `adb setprop` values select startup behavior on the headset.

6. If `feature-lock.json.permission_pregrant.required_before_first_launch` is
   true, run its generated command before the first headset launch. Do not
   pregrant permissions that are absent from the resolved manifest. Media
   projection still needs fresh `createScreenCaptureIntent` result data even
   when the lab `PROJECT_MEDIA` app-op is allowed.
7. For the private-particle solid-black canary, run serial-scoped headset smoke
   with the generated profile:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File tools/Invoke-NativeRendererReplaySmoke.ps1 -EvidenceMode PrivateParticleCanary -ProfilePath local-artifacts/native-app-builds/private_particle_solid_black_canary/runtime-profile.json -ApkPath target/native-renderer-android/rusty-quest-native-renderer.apk -Serial <quest-serial> -RunSeconds 8 -AllowFlatScreenshot -AllowPerformanceBudgetMiss -StopAfterRun
   ```

Never accept raw `adb getprop` readback as proof by itself. The runtime must
emit matching effective markers for the selected app profile.

## Runtime Profiles And Launch Overrides

The generated app settings and feature lock are the APK packaging contract.
They are not a substitute for applying the current runtime profile before a
validation launch. When a source profile has gained a new startup property but
the already generated lock still packages the right manifest, shaders, and
assets, it is acceptable to build from that lock and apply the updated runtime
profile directly with `tools/Apply-RuntimeProfile.ps1 -Execute` before launch.

Keep every stale visual switch explicitly owned by the profile used for the
run. A profile that validates one visible stack should also set unrelated
camera, video, display-composite, hand, particle, SDF, and private-layer
families to their intended enabled/disabled values so one subsystem cannot
masquerade as another in headset evidence.

Use serial-scoped launch overrides only for values that are intentionally
runtime-selected, such as a diagnostic layer index for a single run. Set those
properties after applying the base profile and before starting the activity,
then rely on runtime markers such as active layer, source authority, fallback
reason, and effective option fields as proof.

App-private media used for video projection is a local test artifact. Stage it
to the app-private device path with the staging helper or an explicit
device-local command, and keep the source video out of public fixtures, build
locks, commits, and release artifacts.

For `XR_META_environment_depth` validation, provider support and acquired frame
markers prove only API liveness. On current Quest validation, sampled D16 depth
payloads were sentinel-only unless a native `XR_FB_passthrough` layer was also
active. Depth-dependent apps should set
`debug.rustyquest.native_renderer.environment_depth.native_passthrough.required=true`
when they need usable sampled depth, then verify
`nativePassthroughRequested=true`, `nativePassthroughLayerActive=true`,
`environmentDepthAcquireStatus=acquired`, and a non-fallback depth consumer
marker in the same run.

When an app samples depth in a projection/composite shader rather than drawing
the public particle map, request the public
`environment_depth.projection_sampler` feature instead of relying on private
feature side effects. That feature owns the public manifest contract:
`horizonos.permission.USE_SCENE`, `com.oculus.feature.PASSTHROUGH`, and
`USE_SCENE_DATA` pregrant/app-op evidence. `HEADSET_CAMERA`, `SPATIAL_CAMERA`,
and `USE_SCENE` remain manifest/pregrant surfaces; the native activity should
not request them through `Activity.requestPermissions`.

## Iteration Speed

Launching can feel slow when agents use the full acceptance route for every
small change. That path may rebuild native code, repackage the APK, reinstall
with `adb install -r -d -g`, run permission pregrant/app-op setup, apply the
runtime profile, start the activity, wait through OpenXR/session and renderer
warmup, collect screenshot/logcat evidence, and then run marker checks.

Use the smallest route that matches the change:

- Settings-only changes: update the master settings surface and use a declared
  hotload transport from `settings_hotload`. The runtime must report an
  applied or rejected effective-settings revision.
- Private-particle scalar diagnostics: use the generated `settings_hotload`
  `accepted_scalar_properties` list and serial-scoped `adb setprop` only for
  runtime-polled `private_particles.*` values, including world-anchor scale,
  visual scale, bounded generic driver scalars, tracer scalars, transparency
  scalars, and color facing attenuation. The renderer must emit
  `privateParticleSettingsHotload=true` plus the matching effective value
  markers before the change is accepted.
- Same APK, same manifest: skip rebuild/reinstall and run launch/smoke with
  the existing APK. Use `-SkipInstall` only when the installed package and lock
  hash are known to match.
- Manifest, permission, service, activity, query, asset, shader, native code,
  package identity, or non-hotload render-target changes: rebuild/reinstall and
  rerun the pregrant plan before first launch.
- Full Android APK builds are the compile gate for native renderer integration
  paths that are not exercised by narrower Rust unit-test targets. If a change
  touches OpenXR/Vulkan frame-loop wiring, run the package build before headset
  validation even when crate-level tests pass.
- Acceptance evidence: keep using the full smoke route. It is intentionally
  slower because it proves install, profile transport, app-side adoption, and
  runtime markers together.
