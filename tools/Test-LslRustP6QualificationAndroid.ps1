$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$app = Join-Path $root "apps\lsl-rust-p6-qualification-android"
$owned = @(
    (Join-Path $app "AndroidManifest.xml"),
    (Join-Path $app "README.md"),
    (Join-Path $app "native\src\lib.rs"),
    (Join-Path $app "src\main\java\io\github\mesmerprism\rustyquest\lslrustp6qualification\P6QualificationActivity.java"),
    (Join-Path $root "tools\Build-LslRustP6QualificationAndroid.ps1"),
    (Join-Path $root "tools\Invoke-LslRustP6QualificationQuest.ps1")
)
foreach ($path in $owned) { if (-not (Test-Path -LiteralPath $path)) { throw "Missing qualification harness file: $path" } }
$all = ($owned | ForEach-Object { Get-Content -Raw -LiteralPath $_ }) -join "`n"
foreach ($needle in @(
    "rusty.quest.lsl_rust_p6_qualification_build.v1", "rusty.quest.lsl_rust_p6_qualification_capsule.v1",
    "rusty.quest.lsl_rust_p6_qualification_result.v1", "rusty.lsl.p6_single_quest_qualification.v1",
    "aarch64-linux-android", "run_timestamped_float32_outlet", "run_timestamped_float32_inlet",
    "run_timestamped_float32_two_record_chunk_outlet", "run_timestamped_float32_two_record_chunk_inlet",
    "candidate_count", "monotonic-elapsed-bound", "rejected-closed-loopback-candidate-then-selected-route",
    "sample_port_reuse", "chunk_port_reuse", "quest_source", "rusty_lsl_source", "android_manifest_sha256",
    "build_manifest_sha256", "apk_sha256", "install", "-r", "force-stop", "pidof", "forward", "reverse",
    "relevant_properties", "staging_entries", "intentional-run-owned-development-package-retained-because-uninstall-is-forbidden"
)) { if (-not $all.Contains($needle)) { throw "Missing P6 qualification guard: $needle" } }
foreach ($forbidden in @(
    "android.permission.CAMERA", "android.permission.RECORD_AUDIO", "MANAGE_EXTERNAL_STORAGE",
    "adb kill-server", "adb start-server", " tcpip ", " connect ", " shell input ", '@("uninstall"',
    " pm clear ", " forward --remove", "reverse --remove", " setprop ", " svc power", " stay_on"
)) { if ($all.Contains($forbidden, [StringComparison]::OrdinalIgnoreCase)) { throw "Forbidden P6 qualification token: $forbidden" } }
$invoke = Get-Content -Raw -LiteralPath (Join-Path $root "tools\Invoke-LslRustP6QualificationQuest.ps1")
$adbInvocations = [regex]::Matches($invoke, '&\s+\$AdbPath[^\r\n]*')
if ($adbInvocations.Count -ne 1 -or -not $adbInvocations[0].Value.Contains('-s $Serial')) { throw "All ADB execution must flow through the one serial-scoped helper." }
$manifest = [xml](Get-Content -Raw -LiteralPath (Join-Path $app "AndroidManifest.xml"))
if ($manifest.manifest.package -ne "io.github.mesmerprism.rustyquest.lslrustp6qualification") { throw "Unexpected package identity." }
$androidNamespace = "http://schemas.android.com/apk/res/android"
$permissionNames = @($manifest.manifest.'uses-permission' | ForEach-Object { $_.GetAttribute("name", $androidNamespace) })
if ($permissionNames.Count -ne 1 -or $permissionNames[0] -ne "android.permission.INTERNET") { throw "The harness must declare exactly INTERNET permission." }
foreach ($script in @((Join-Path $root "tools\Build-LslRustP6QualificationAndroid.ps1"), (Join-Path $root "tools\Invoke-LslRustP6QualificationQuest.ps1"), $PSCommandPath)) {
    $tokens = $null; $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($script, [ref]$tokens, [ref]$errors)
    if ($errors.Count -ne 0) { throw "PowerShell parse failure in $script`: $($errors[0].Message)" }
}
"Rusty LSL P6 single-Quest qualification harness focused static validation passed."
