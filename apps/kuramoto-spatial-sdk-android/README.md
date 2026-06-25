# Rusty Quest Kuramoto Spatial SDK Android

This app is a separate Meta Spatial SDK lane for the Kuramoto experiment panel
workflow. It does not replace the native renderer APK and does not package the
Rust NativeActivity, OpenXR/Vulkan renderer, or native hand/particle payloads.

Package:

```text
io.github.mesmerprism.rustyquest.kuramoto_spatial/.KuramotoSpatialActivity
```

Purpose:

- prove a real Spatial SDK `AppSystemActivity` can own a world-space panel;
- experiment with panel pose, scale, meter size, and dp-per-meter display
  settings through Spatial SDK mechanisms;
- preserve the low-rate participant, surface, block, questionnaire, and JSONL
  logging shape from the native Kuramoto workflow.

Non-scope for the first lane:

- no native OpenXR/Vulkan hand rendering;
- no GPU particle or phase-field data through panel JSON;
- no BLE Polar stream intake inside this app. The app creates the same
  participant file skeleton so ECG/Polar files remain part of the session
  bundle, but live Polar intake stays in the native lane until a low-rate
  bridge is designed.

Build:

```powershell
& 'S:\Work\tools\Quest\Use-QuestTooling.ps1'
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-KuramotoSpatialSdkAndroid.ps1 -RepoRoot .
```

Expected APK:

```text
target/kuramoto-spatial-sdk-android/rusty-quest-kuramoto-spatial-sdk.apk
```

Static validation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-KuramotoSpatialSdkAndroid.ps1 -RepoRoot .
```

Headset workflow smoke, after taking Agent Board leases and choosing an
explicit Quest serial:

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

The validation action drives the same low-rate store path as the panel:
participant setup, Polar placeholder, surface selection, block timing,
automatic questionnaire due state, and questionnaire submission. ADB
`screencap` currently captures the VR compositor/performance overlay but not
the Spatial SDK panel layer, so headset evidence should include logcat,
SurfaceFlinger, activity dumpsys, and app-private JSONL artifacts.
