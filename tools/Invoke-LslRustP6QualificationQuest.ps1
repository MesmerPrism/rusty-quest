[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$Serial,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$RunCapsule,
    [string]$OutDir = "",
    [string]$AdbPath = "adb.exe",
    [ValidateRange(5, 60)][int]$TimeoutSeconds = 20
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$package = "io.github.mesmerprism.rustyquest.lslrustp6qualification"
$activity = ".P6QualificationActivity"

function Get-Sha256([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Invoke-ScopedAdb([string[]]$Arguments, [switch]$AllowFailure) {
    $output = (& $AdbPath -s $Serial @Arguments 2>&1 | Out-String)
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) { throw "serial-scoped adb failed ($exitCode): $($Arguments -join ' ')`n$output" }
    [pscustomobject]@{ ExitCode = $exitCode; Output = $output.TrimEnd() }
}
function Get-StateSnapshot {
    $forwards = (Invoke-ScopedAdb @("forward", "--list")).Output -split "`r?`n" | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s" }
    $reverses = (Invoke-ScopedAdb @("reverse", "--list")).Output -split "`r?`n" | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s" }
    $properties = (Invoke-ScopedAdb @("shell", "getprop")).Output -split "`r?`n" | Where-Object { $_ -match 'debug\.rustyquest|lsl_rust_p6|lslrustp6' }
    $staging = (Invoke-ScopedAdb @("shell", "sh", "-c", "ls -1A /data/local/tmp 2>/dev/null || true")).Output -split "`r?`n"
    [ordered]@{ forwards = @($forwards); reverses = @($reverses); relevant_properties = @($properties); staging_entries = @($staging) }
}
function Test-EqualJson($Left, $Right) { ($Left | ConvertTo-Json -Depth 8 -Compress) -ceq ($Right | ConvertTo-Json -Depth 8 -Compress) }

if ($PSVersionTable.PSVersion -lt [version]'7.6') { throw "PowerShell 7.6 or newer is required." }
if ($Serial -match '\s') { throw "Serial must be one explicit non-whitespace token." }
$capsulePath = (Resolve-Path -LiteralPath $RunCapsule).Path
$capsuleDir = Split-Path -Parent $capsulePath
$capsule = Get-Content -Raw -LiteralPath $capsulePath | ConvertFrom-Json
if ($capsule.schema -ne "rusty.quest.lsl_rust_p6_qualification_capsule.v1" -or $capsule.package -ne $package -or $capsule.activity -ne $activity) { throw "Run capsule identity mismatch." }
$manifestPath = Join-Path $capsuleDir $capsule.build_manifest_file
$apk = Join-Path $capsuleDir $capsule.apk_file
if ((Get-Sha256 $manifestPath) -cne $capsule.build_manifest_sha256) { throw "Build-manifest hash mismatch." }
if ((Get-Sha256 $apk) -cne $capsule.apk_sha256) { throw "APK hash mismatch." }
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
if ($manifest.schema -ne "rusty.quest.lsl_rust_p6_qualification_build.v1" -or $manifest.package -ne $package -or $manifest.activity -ne $activity -or $manifest.apk_sha256 -cne $capsule.apk_sha256) { throw "Build manifest identity mismatch." }
$sourceManifest = Join-Path (Split-Path $PSScriptRoot -Parent) "apps\lsl-rust-p6-qualification-android\AndroidManifest.xml"
if ((Get-Sha256 $sourceManifest) -cne $manifest.android_manifest_sha256) { throw "Android manifest hash mismatch." }
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $capsuleDir ("device-" + (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")) }
$fullOut = [IO.Path]::GetFullPath($OutDir)
New-Item -ItemType Directory -Path $fullOut -ErrorAction Stop | Out-Null
$startedUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd HH:mm:ss.fff")
$before = Get-StateSnapshot
$before | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $fullOut "state-before.json") -Encoding utf8
$receiptPath = Join-Path $fullOut "private-device-receipt.json"
$primaryError = $null
try {
    Invoke-ScopedAdb @("install", "-r", "-d", $apk) | Out-Null
    $packageDump = (Invoke-ScopedAdb @("shell", "dumpsys", "package", $package)).Output
    $packageDump | Set-Content -LiteralPath (Join-Path $fullOut "installed-package.txt") -Encoding utf8
    if ($packageDump -notmatch 'versionCode=1\b' -or $packageDump -notmatch [regex]::Escape($package)) { throw "Installed package identity/version readback failed." }
    Invoke-ScopedAdb @("shell", "am", "force-stop", $package) | Out-Null
    Invoke-ScopedAdb @("shell", "am", "start", "-W", "-n", "$package/$activity") | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $resultText = ""
    do {
        $read = Invoke-ScopedAdb @("exec-out", "run-as", $package, "cat", "files/result.json") -AllowFailure
        if ($read.ExitCode -eq 0 -and $read.Output.TrimStart().StartsWith('{')) { $resultText = $read.Output; break }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    if (-not $resultText) { throw "Timed out waiting for the bounded app result." }
    $resultText | Set-Content -LiteralPath (Join-Path $fullOut "result.json") -Encoding utf8
    $result = $resultText | ConvertFrom-Json
    $logs = (Invoke-ScopedAdb @("logcat", "-d", "-v", "threadtime", "-T", $startedUtc)).Output
    $logs | Set-Content -LiteralPath (Join-Path $fullOut "bounded-logcat.txt") -Encoding utf8
    $relevantLines = $logs -split "`r?`n" | Where-Object { $_ -match 'RLSLP6_|lslrustp6qualification|io\.github\.mesmerprism\.rustyquest\.lslrustp6qualification|ActivityManager.*(crash|fatal)' }
    $relevantText = $relevantLines -join "`n"
    $relevantText | Set-Content -LiteralPath (Join-Path $fullOut "bounded-relevant-logcat.txt") -Encoding utf8
    $fatalCount = [regex]::Matches($relevantText, 'FATAL EXCEPTION|Fatal signal|AndroidRuntime.*FATAL|am_crash').Count
    if ($result.result -ne "pass" -or $result.native_result -ne 1) { throw "Native qualification result failed." }
    foreach ($needle in @('rusty.lsl.p6_single_quest_qualification.v1', '"transport":"ipv4-loopback"', '"candidate_count":2', '"value_bits":"0x7fc01234"', '"record_count":2', '"monotonic-elapsed-bound"', '"bounded":true', '"sample_port_reuse":true', '"chunk_port_reuse":true')) {
        if (-not $relevantText.Contains($needle)) { throw "Missing direct Rust evidence: $needle" }
    }
    if ($fatalCount -ne 0) { throw "Bounded package/system-relevant fatal evidence is nonzero: $fatalCount" }
    [ordered]@{
        schema = "rusty.quest.lsl_rust_p6_qualification_device.v1"; result = "pass"; serial = $Serial
        evidence_scope = "single-device-ipv4-loopback-package-and-system-relevant-bounded-window"
        run_capsule_sha256 = Get-Sha256 $capsulePath; build_manifest_sha256 = Get-Sha256 $manifestPath; apk_sha256 = Get-Sha256 $apk
        installed_package_identity_readback = $true; rust_effective_marker = $true; java_result = $true
        sample_exact_bits = $true; chunk_exact_bits = $true; monotonic_clock_bound = $true; bounded_recovery = $true
        terminal_port_reuse = $true; relevant_fatal_count = 0; cleanup = "pending"
        package_retention = "intentional-run-owned-development-package-retained-because-uninstall-is-forbidden"
        limitations = @("no-host-to-quest", "no-second-device", "no-non-loopback", "no-official-runtime-or-oracle", "no-attended-input", "no-global-device-health")
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $receiptPath -Encoding utf8
} catch { $primaryError = $_ } finally {
    $cleanupErrors = [System.Collections.Generic.List[string]]::new()
    $stop = Invoke-ScopedAdb @("shell", "am", "force-stop", $package) -AllowFailure
    if ($stop.ExitCode -ne 0) { $cleanupErrors.Add("force-stop failed: $($stop.Output)") }
    $processIdText = (Invoke-ScopedAdb @("shell", "pidof", $package) -AllowFailure).Output.Trim()
    if ($processIdText) { $cleanupErrors.Add("live target PID remains: $processIdText") }
    $after = Get-StateSnapshot
    $after | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $fullOut "state-after.json") -Encoding utf8
    if (-not (Test-EqualJson $before.forwards $after.forwards)) { $cleanupErrors.Add("forward drift detected") }
    if (-not (Test-EqualJson $before.reverses $after.reverses)) { $cleanupErrors.Add("reverse drift detected") }
    if (-not (Test-EqualJson $before.relevant_properties $after.relevant_properties)) { $cleanupErrors.Add("relevant property drift detected") }
    if (-not (Test-EqualJson $before.staging_entries $after.staging_entries)) { $cleanupErrors.Add("/data/local/tmp staging drift detected") }
    if (Test-Path -LiteralPath $receiptPath) {
        $receipt = Get-Content -Raw -LiteralPath $receiptPath | ConvertFrom-Json
        $receipt.cleanup = if ($cleanupErrors.Count -eq 0) { "complete-force-stopped-no-pid-no-forward-reverse-property-staging-drift-package-retained" } else { "failed" }
        $receipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $receiptPath -Encoding utf8
    }
    if ($cleanupErrors.Count -gt 0) {
        $cleanupMessage = $cleanupErrors -join "; "
        if ($null -ne $primaryError) { throw "$($primaryError.Exception.Message); cleanup: $cleanupMessage" }
        throw "Cleanup verification failed: $cleanupMessage"
    }
}
if ($null -ne $primaryError) { throw $primaryError }
Get-Content -Raw -LiteralPath $receiptPath
