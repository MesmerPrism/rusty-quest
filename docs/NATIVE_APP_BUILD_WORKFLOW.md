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
5. Build from the lock:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File tools/Build-NativeRendererAndroid.ps1 -AppBuildLock local-artifacts/native-app-builds/<app>/feature-lock.json
   ```

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
  runtime-polled `private_particles.*` values, including visual scale, tracer
  scalars, transparency scalars, and color facing attenuation. The renderer
  must emit `privateParticleSettingsHotload=true` plus the matching effective
  value markers before the change is accepted.
- Same APK, same manifest: skip rebuild/reinstall and run launch/smoke with
  the existing APK. Use `-SkipInstall` only when the installed package and lock
  hash are known to match.
- Manifest, permission, service, activity, query, asset, shader, native code,
  package identity, or non-hotload render-target changes: rebuild/reinstall and
  rerun the pregrant plan before first launch.
- Acceptance evidence: keep using the full smoke route. It is intentionally
  slower because it proves install, profile transport, app-side adoption, and
  runtime markers together.
