# Spatial SDK Kuramoto Port Implementation Plan

## Initial Hypothesis

The smallest useful Spatial SDK version should be a separate Quest app lane
under `apps/`, not a mutation of `apps/native-renderer-android`.

Expected requirements:

- Add a standalone Gradle/Kotlin Android project for a minimal Spatial SDK
  `AppSystemActivity`.
- Use a distinct package and label so the existing native renderer APK remains
  buildable, installable, and launchable as-is.
- Register and spawn a Spatial SDK panel entity from the immersive activity.
- Use panel shape, transform, scale, and display options as the experiment
  surface for panel placement/size/DPI testing.
- Reuse or mirror only the low-rate Kuramoto experiment workflow:
  participant setup, Polar setup/status placeholder, surface choice, randomized
  condition order, short timed blocks, questionnaire capture, and JSONL files
  joinable by participant/session/block/condition/profile/surface IDs.
- Keep native OpenXR/Vulkan hand mesh, particle buffers, and high-rate frames
  out of the Spatial SDK panel command/data path.
- Treat hand rendering in this first Spatial SDK lane as not expected unless a
  later slice embeds or coordinates with the native renderer.

## Resources Consulted

Checked on 2026-06-25 unless otherwise noted.

Local:

- `AGENTS.md`
- `README.md`
- `docs/ARCHITECTURE.md`
- `docs/VALIDATION.md`
- `docs/NATIVE_APP_BUILD_WORKFLOW.md`
- `fixtures/README.md`
- `tools/check_all.ps1`
- `tools/checks/Test-NativeRendererAndroidScaffoldStatic.ps1`
- `apps/native-renderer-android/AndroidManifest.xml`
- `apps/native-renderer-android/README.md`
- `apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/ControlPanelActivity.java`
- `apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/KuramotoExperimentSession.java`
- `apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/PolarSensorPanel.java`
- `apps/native-renderer-android/native/src/native_renderer_panel_bridge.rs`
- `apps/native-renderer-android/native/src/native_renderer_stimulus_panel.rs`
- `rusty-morphospace-context` skill and its Quest/Android routing reference
- `meta-quest-workflow` skill and its Agent Board/tooling routing reference
- `system-engineering` skill

Official Meta:

- Spatial SDK overview:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-explainer/
- Add Spatial SDK to an existing 2D app:
  https://developers.meta.com/horizon/documentation/spatial-sdk/add-spatial-sdk-to-app/
- Spatial SDK activity lifecycle:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-activity-lifecycle/
- Spatial SDK architecture:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-architecture/
- Hybrid apps overview:
  https://developers.meta.com/horizon/documentation/spatial-sdk/hybrid-apps-overview/
- Hybrid sample:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-sample-hybrid/
- 2D panels in Spatial SDK:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel/
- Register 2D panels:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-registration/
- Build and position your first panel:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-panel-tutorial/
- Jetpack Compose in panels:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-compose/
- Panel resolution and display options:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-resolution/
- Meta Spatial SDK packages:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-packages/
- Connecting Spatial Editor to your project:
  https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-editor/

Official Android:

- Support large screen resizability:
  https://developer.android.com/games/develop/multiplatform/support-large-screen-resizability
- ChromeOS window management, for Android manifest launch-size semantics:
  https://developer.android.com/develop/devices/chromeos/learn/window-management

## Architecture Decisions

- Lane: create `apps/kuramoto-spatial-sdk-android` as a separate app lane. Do
  not add Spatial SDK, AndroidX, Compose, `AppSystemActivity`, `VrActivity`, or
  GLXF tokens to the existing native renderer app source/build path.
- Package/activity: use a new package,
  `io.github.mesmerprism.rustyquest.kuramoto_spatial`, with an immersive
  Spatial SDK activity as the launcher. Keep
  `io.github.mesmerprism.rustyquest.native_renderer/android.app.NativeActivity`
  unchanged.
- Panel ownership: the Spatial SDK activity owns panel registration, entity
  spawn, transform, scale, meter size, and display options. The panel is a
  low-rate experiment workflow UI and logger, not render authority.
- UI implementation: prefer a single view-based Compose panel for the first
  lane because it avoids embedding the old NativeActivity-coupled Java panel
  and gives direct access to `QuadShapeOptions`, `DpPerMeterDisplayOptions`,
  `Transform`, `Pose`, and `Scale`.
- Experiment state: store app-private session JSON/JSONL under this package's
  files directory. Preserve the join keys used by the native experiment rows:
  `participant_id`, `session_id`, `block_index`, `block_number`,
  `condition_id`, `profile_id`, and `surface_target_id`.
- Native interop: no native renderer control in the first Spatial SDK lane.
  This keeps the native GPU hand/particle path preserved and avoids claiming
  that Spatial SDK panel placement validates the native app-owned renderer.

## Iteration Log

- 2026-06-25 initial read: repository starts clean on
  `codex/kuramoto-experiment-panel-workflow` at
  `252f753afb8168bc59cefed4e711484b130a2083`.
- 2026-06-25 branch: created `codex/spatial-sdk-kuramoto-lane` for the
  Spatial SDK lane so the pushed experiment-panel branch remains intact.
- 2026-06-25 official doc check: Meta docs support an `AppSystemActivity`
  activity, `registerPanels()`, `PanelRegistration`/`ComposeViewPanelRegistration`,
  `Entity.createPanelEntity(...)`, `Transform(Pose(...))`, `QuadShapeOptions`,
  and `DpPerMeterDisplayOptions` for real Spatial SDK panel placement/resolution.
- 2026-06-25 local architecture check: existing native renderer static gates
  deliberately reject Spatial SDK tokens in the same-APK 2D panel and report
  `spatial_sdk_packaged = $false`. Therefore the first implementation must be a
  separate lane and separate validation slot.
- 2026-06-25 implementation: added
  `apps/kuramoto-spatial-sdk-android` with Gradle 9.4.1, AGP 8.11.1, Kotlin
  2.1.0, Spatial SDK 0.13.1, and a Compose-backed `AppSystemActivity` panel.
- 2026-06-25 build iteration: the first Gradle bootstrap failed because
  PowerShell `Invoke-WebRequest` threw a null reference while fetching the
  Gradle `.sha256`. The build wrapper now uses a small .NET download helper
  and still verifies SHA-256 before extracting Gradle.
- 2026-06-25 build iteration: Kotlin compilation failed when the root build
  file put the Spatial SDK and Compose plugins on the top-level plugin
  classpath. Aligning with the official sample fixed the issue: root declares
  Android/Kotlin only, app module applies Meta Spatial and Compose.
- 2026-06-25 build iteration: enabled `org.gradle.configuration-cache=true`
  because the official Meta Spatial SDK sample uses it. The final rebuild
  reused the configuration cache.
- 2026-06-25 panel iteration: initial screenshots from `adb screencap` showed
  the VR compositor/performance overlay but not the Spatial SDK panel layer.
  The panel now uses the official sample view-origin convention
  `scene.setViewOrigin(0, 0, 2, 180)` with default panel pose
  `y=1.1, z=-1.7`, and an explicit high-contrast Compose surface. The saved
  ADB screenshot still does not include the Spatial SDK panel layer; logcat,
  foreground activity state, and SurfaceFlinger layer evidence are the headset
  proof for this run.
- 2026-06-25 headset validation: installed and launched the APK on Quest 3S
  serial `3487C10H3M017Q` with serial-scoped ADB under Agent Board leases. The
  validation action drove participant setup, Polar placeholder setup, surface
  selection, block start, automatic elapsed-block transition, questionnaire
  submission, and completion markers.

## Final Build/Run Recipe

Local static gate:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-KuramotoSpatialSdkAndroid.ps1 -RepoRoot .
```

Build gate:

```powershell
# Activate the repo-family Quest/Android tooling for this machine first.
& 'S:\Work\tools\Quest\Use-QuestTooling.ps1'
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-KuramotoSpatialSdkAndroid.ps1 -RepoRoot .
```

APK output:

```text
target/kuramoto-spatial-sdk-android/rusty-quest-kuramoto-spatial-sdk.apk
```

Final validated APK SHA-256:

```text
748a19362c6fc3ae5afcad3be61b629d57f4b902c7dae948040219d1911f8d6f
```

Headset validation command shape:

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$serial = "3487C10H3M017Q"
$pkg = "io.github.mesmerprism.rustyquest.kuramoto_spatial"
& $adb -s $serial install -r -d -g target\kuramoto-spatial-sdk-android\rusty-quest-kuramoto-spatial-sdk.apk
& $adb -s $serial shell am start -W -n "$pkg/.KuramotoSpatialActivity" `
  -a io.github.mesmerprism.rustyquest.kuramoto_spatial.action.RUN_WORKFLOW_SELF_TEST `
  --es participant_id codex-spatial-sdk-visible-20260625 `
  --es surface_target_id real-hands
```

Evidence directory:

```text
local-artifacts/kuramoto-spatial-sdk-headset/20260625-104401
```

Important files in that directory:

- `filtered-logcat.txt`: activity creation, participant setup, panel spawn,
  block elapsed, questionnaire submitted, and self-test completion markers.
- `evidence-summary.json`: Quest serial, APK hash, session id, expected marker
  set, panel pose, and `hand_rendering_expected=false`.
- `questionnaire_results.jsonl`: persisted joinable questionnaire row.
- `session_manifest.json`, `block_events.jsonl`, `foreground_events.jsonl`,
  `polar_events.jsonl`, `ecg_events.jsonl`: app-private session artifacts.
- `dumpsys-activity-rusty-kuramoto-spatial.txt`: activity foreground/visible
  state.
- `surfaceflinger-list.txt` and `surfaceflinger-panel-filter.txt`: app and
  panel-related SurfaceFlinger layer evidence.
- `screenshot.png`: ADB compositor screenshot. It shows the VR compositor and
  performance overlay, but not the Spatial SDK panel layer.

## Remaining Risks And Follow-Ups

- Spatial SDK artifacts and Gradle may need to be downloaded during the first
  build. Keep downloads under Gradle/user caches or ignored local artifacts,
  not committed binaries.
- Spatial SDK 0.13.1 docs expect Android Gradle Plugin 8.11 and Gradle 9.x.
  The build wrapper bootstraps Gradle 9.4.1 under ignored `local-artifacts`
  and writes the APK/build manifest under ignored `target`.
- The first lane is expected to validate Spatial SDK panel placement/options
  and workflow logging, not native hand/particle rendering.
- Current ADB screenshots do not show the Spatial SDK panel layer even though
  the foreground activity, panel spawn marker, and SurfaceFlinger app layer are
  present. Treat screenshots as compositor-only evidence until a headset-side
  capture method that includes Spatial SDK panels is selected.
- If questionnaire semantics change, bump the questionnaire schema and update
  UI plus persisted row writer together. Current plan is to preserve the
  existing minimal comfort/intensity/engagement/notes semantics.
- If a later slice coordinates this Spatial SDK app with the native renderer,
  define an explicit low-rate command/receipt boundary first. Do not route
  high-rate hands, meshes, particles, phase fields, or buffers through panel
  JSON.
