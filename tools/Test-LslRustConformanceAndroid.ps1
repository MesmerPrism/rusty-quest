$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$app = Join-Path $root "apps\lsl-rust-conformance-android"
$all = @(
    (Get-Content -Raw (Join-Path $app "AndroidManifest.xml"))
    (Get-Content -Raw (Join-Path $app "src\main\java\io\github\mesmerprism\rustyquest\lslrustconformance\RustConformanceActivity.java"))
    (Get-Content -Raw (Join-Path $app "native\src\lib.rs"))
    (Get-Content -Raw (Join-Path $root "tools\Build-LslRustConformanceAndroid.ps1"))
    (Get-Content -Raw (Join-Path $root "tools\Invoke-LslRustConformanceQuest.ps1"))
) -join "`n"
foreach ($needle in @("aarch64-linux-android", "rusty_lsl", "RLSL005H_RUST", "BoundDescriptorSample", "run-as", "uninstall", "forward --list", "reverse --list")) {
    if (-not $all.Contains($needle)) { throw "Missing Rust-on-Quest guard: $needle" }
}
foreach ($forbidden in @("android.permission.CAMERA", "android.permission.RECORD_AUDIO", "MANAGE_EXTERNAL_STORAGE", "adb kill-server", "adb start-server")) {
    if ($all.Contains($forbidden)) { throw "Forbidden Rust-on-Quest token: $forbidden" }
}
"LSLC-005H Rust-on-Quest harness static validation passed."
