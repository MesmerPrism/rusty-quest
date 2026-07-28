$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$app = Join-Path $root "apps\lsl-rust-float32-two-record-chunk-android"
$all = @(
    (Get-Content -Raw (Join-Path $app "AndroidManifest.xml"))
    (Get-Content -Raw (Join-Path $app "src\main\java\io\github\mesmerprism\rustyquest\lslrustfloat32tworecordchunk\Float32TwoRecordChunkActivity.java"))
    (Get-Content -Raw (Join-Path $app "native\src\lib.rs"))
    (Get-Content -Raw (Join-Path $root "tools\Build-LslRustFloat32TwoRecordChunkAndroid.ps1"))
    (Get-Content -Raw (Join-Path $root "tools\Invoke-LslRustFloat32TwoRecordChunkQuest.ps1"))
) -join "`n"
foreach ($needle in @("aarch64-linux-android", "rusty_lsl", "RLSL005S_RUST", "run_timestamped_float32_two_record_chunk_outlet", "run_timestamped_float32_two_record_chunk_inlet", 'record_count\":2', "ACCEPTED_FEATURE_LOCK_FINGERPRINT", "port_reuse", "run-as", "uninstall", "forward --list", "reverse --list")) {
    if (-not $all.Contains($needle)) { throw "Missing Rust-on-Quest guard: $needle" }
}
foreach ($forbidden in @("android.permission.CAMERA", "android.permission.RECORD_AUDIO", "MANAGE_EXTERNAL_STORAGE", "adb kill-server", "adb start-server")) {
    if ($all.Contains($forbidden)) { throw "Forbidden Rust-on-Quest token: $forbidden" }
}
"LSLC-005S Rust-on-Quest Float32 two-record chunk harness static validation passed."
