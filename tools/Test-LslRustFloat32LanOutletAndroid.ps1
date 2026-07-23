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
$runScriptText = Get-Content -Raw (Join-Path $root "tools\Invoke-LslRustFloat32LanOutletQuest.ps1")
$p70Scripts = Get-ChildItem (Join-Path $root "tools") -File |
    Where-Object Name -Like "*LslRustFloat32LanOutlet*.ps1"
foreach ($script in $p70Scripts) {
    $tokens = $null
    $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        $script.FullName,
        [ref]$tokens,
        [ref]$parseErrors
    )
    if ($parseErrors.Count -ne 0) {
        throw "PowerShell parse failure in $($script.Name): $($parseErrors[0].Message)"
    }
    $reservedAutomaticVariables = @($ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.VariableExpressionAst] -and
            (
                $node.VariablePath.UserPath.Equals("Host", [StringComparison]::OrdinalIgnoreCase) -or
                $node.VariablePath.UserPath.Equals("Args", [StringComparison]::OrdinalIgnoreCase)
            )
    }, $true))
    if ($reservedAutomaticVariables.Count -ne 0) {
        throw "Reserved PowerShell automatic variable used in $($script.Name)"
    }
}
foreach ($needle in @("aarch64-linux-android", "rusty_lsl", "RLSLP70_RUST", "run_timestamped_float32_outlet", "run_typed_udp_discovery_float32_session_inlet", 'record_count\":1', "ACCEPTED_FEATURE_LOCK_FINGERPRINT", "socket_cleanup", "run-as", "install", "force-stop", "forward --list", "reverse --list")) {
    if (-not $all.Contains($needle)) { throw "Missing Rust-on-Quest guard: $needle" }
}
foreach ($needle in @("bounded_logcat_sha256", "bounded_fatal_count", "failure_path_evidence_preserved")) {
    if (-not $runScriptText.Contains($needle)) { throw "Missing failure-path evidence guard: $needle" }
}
$nativeText = Get-Content -Raw (Join-Path $app "native\src\lib.rs")
foreach ($needle in @(
    "ShortInfoResponderLimits::new(",
    "SELF_PROBE_QUERY_ID",
    "ShortInfoQueryWire::encode",
    "ParsedShortInfoResponseEnvelope::parse",
    "parsed.query_id() == SELF_PROBE_QUERY_ID",
    "parsed.body().source() == xml",
    "sent != wire.as_bytes().len()",
    "self_probe=true stage=responder-ready",
    "NOT_READY schema=rusty.lsl.p70.quest_outlet_ready.v2 self_probe=false stage=responder-self-probe",
    "RESPONDER schema=rusty.lsl.p70.quest_responder_result.v1",
    "run.requests() == 2",
    "ShortInfoResponderTermination::RequestLimit"
)) {
    if (-not $nativeText.Contains($needle)) { throw "Missing responder-readiness guard: $needle" }
}
if ($nativeText -notmatch 'ShortInfoResponderLimits::new\(\s*2048,\s*2,') {
    throw "Responder limit must be exactly two requests"
}
if ([regex]::Matches($nativeText, '\.send_to\(wire\.as_bytes\(\),').Count -ne 1) {
    throw "Responder readiness must send exactly one self-probe datagram"
}
if ($nativeText -match '(?<!NOT_)READY schema=rusty\.lsl\.p70\.quest_outlet_ready\.v2 self_probe=false') {
    throw "Self-probe failure must never emit READY"
}
$readyContract = "READY schema=rusty.lsl.p70.quest_outlet_ready.v2 self_probe=true stage=responder-ready"
if (-not $runScriptText.Contains($readyContract) -or -not $nativeText.Contains($readyContract)) {
    throw "Native and run-script readiness contracts must match exactly"
}
$selfProbeCall = $nativeText.IndexOf("let self_probe = prove_responder_ready", [StringComparison]::Ordinal)
$readyMarker = $nativeText.IndexOf("self_probe=true stage=responder-ready", [StringComparison]::Ordinal)
if ($selfProbeCall -lt 0 -or $readyMarker -lt 0 -or $selfProbeCall -gt $readyMarker) {
    throw "Responder self-probe must complete before READY"
}
$failureCapture = $runScriptText.IndexOf('$logcatPath=Join-Path $OutDir "logcat.txt"', [StringComparison]::Ordinal)
$finalForceStop = $runScriptText.LastIndexOf('& adb.exe -s $Serial shell am force-stop $package', [StringComparison]::Ordinal)
if ($failureCapture -lt 0 -or $finalForceStop -lt 0 -or $failureCapture -gt $finalForceStop) {
    throw "Failure-path bounded logcat must be captured before final target force-stop"
}
foreach ($forbidden in @("android.permission.CAMERA", "android.permission.RECORD_AUDIO", "MANAGE_EXTERNAL_STORAGE", "adb kill-server", "adb start-server")) {
    if ($all.Contains($forbidden)) { throw "Forbidden Rust-on-Quest token: $forbidden" }
}
"P70 Rust-on-Quest Float32 LAN outlet harness static validation passed."

