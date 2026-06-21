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
   env are generated adapters.
5. Build from the lock:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File tools/Build-NativeRendererAndroid.ps1 -AppBuildLock local-artifacts/native-app-builds/<app>/feature-lock.json
   ```

6. For the private-particle solid-black canary, run serial-scoped headset smoke
   with the generated profile:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File tools/Invoke-NativeRendererReplaySmoke.ps1 -EvidenceMode PrivateParticleCanary -ProfilePath local-artifacts/native-app-builds/private_particle_solid_black_canary/runtime-profile.json -ApkPath target/native-renderer-android/rusty-quest-native-renderer.apk -Serial <quest-serial> -RunSeconds 8 -AllowFlatScreenshot -AllowPerformanceBudgetMiss -StopAfterRun
   ```

Never accept raw `adb getprop` readback as proof by itself. The runtime must
emit matching effective markers for the selected app profile.
