$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$app = Join-Path $root "apps\lsl-rust-float32-loopback-android"
$all = @(
    (Get-Content -Raw (Join-Path $app "AndroidManifest.xml"))
    (Get-Content -Raw (Join-Path $app "src\main\java\io\github\mesmerprism\rustyquest\lslrustfloat32loopback\Float32LoopbackActivity.java"))
    (Get-Content -Raw (Join-Path $app "native\src\lib.rs"))
    (Get-Content -Raw (Join-Path $root "tools\Build-LslRustFloat32LoopbackAndroid.ps1"))
    (Get-Content -Raw (Join-Path $root "tools\Invoke-LslRustFloat32LoopbackQuest.ps1"))
) -join "`n"
foreach ($needle in @("aarch64-linux-android", "rusty_lsl", "RLSL005L_RUST", "run_timestamped_float32_outlet", "run_timestamped_float32_inlet", "ACCEPTED_FEATURE_LOCK_FINGERPRINT", "port_reuse", "run-as", "uninstall", "forward --list", "reverse --list")) {
    if (-not $all.Contains($needle)) { throw "Missing Rust-on-Quest guard: $needle" }
}
foreach ($forbidden in @("android.permission.CAMERA", "android.permission.RECORD_AUDIO", "MANAGE_EXTERNAL_STORAGE", "adb kill-server", "adb start-server")) {
    if ($all.Contains($forbidden)) { throw "Forbidden Rust-on-Quest token: $forbidden" }
}
"LSLC-005L Rust-on-Quest Float32 loopback harness static validation passed."
