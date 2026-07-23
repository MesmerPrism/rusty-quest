$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$app = Join-Path $root "apps\lsl-rust-float32-lan-outlet-android"
$all = @(
    (Get-Content -Raw (Join-Path $app "AndroidManifest.xml"))
    (Get-Content -Raw (Join-Path $app "src\main\java\io\github\mesmerprism\rustyquest\lslrustfloat32lanoutlet\Float32LanOutletActivity.java"))
    (Get-Content -Raw (Join-Path $app "native\src\lib.rs"))
    (Get-Content -Raw (Join-Path $root "tools\Build-LslRustFloat32LanOutletAndroid.ps1"))
    (Get-Content -Raw (Join-Path $root "tools\Invoke-LslRustFloat32LanOutletQuest.ps1"))
) -join "`n"
foreach ($needle in @("aarch64-linux-android", "rusty_lsl", "RLSLP70_RUST", "run_timestamped_float32_outlet", "run_typed_udp_discovery_float32_session_inlet", 'record_count\":1', "ACCEPTED_FEATURE_LOCK_FINGERPRINT", "socket_cleanup", "run-as", "install", "force-stop", "forward --list", "reverse --list")) {
    if (-not $all.Contains($needle)) { throw "Missing Rust-on-Quest guard: $needle" }
}
foreach ($forbidden in @("android.permission.CAMERA", "android.permission.RECORD_AUDIO", "MANAGE_EXTERNAL_STORAGE", "adb kill-server", "adb start-server")) {
    if ($all.Contains($forbidden)) { throw "Forbidden Rust-on-Quest token: $forbidden" }
}
"P70 Rust-on-Quest Float32 LAN outlet harness static validation passed."

