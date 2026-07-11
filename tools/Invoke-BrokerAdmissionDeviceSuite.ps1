param(
    [Parameter(Mandatory=$true)][string[]]$Serial,
    [string]$BrokerApk = "",
    [string]$ClientBuildManifest = "",
    [Parameter(Mandatory=$true)][string]$OutDir,
    [string]$AdbPath = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if ([string]::IsNullOrWhiteSpace($BrokerApk)) {
    $BrokerApk = Join-Path $repoRoot "target\manifold-broker-android\rusty-manifold-broker.apk"
}
if ([string]::IsNullOrWhiteSpace($ClientBuildManifest)) {
    $ClientBuildManifest = Join-Path $repoRoot "target\broker-admission-clients\build-manifest.json"
}
foreach ($path in @($AdbPath, $BrokerApk, $ClientBuildManifest)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing admission suite input: $path" }
}
$clientBuild = Get-Content -Raw -LiteralPath $ClientBuildManifest | ConvertFrom-Json
$authorizedApk = $clientBuild.authorized.apk_path
$unauthorizedApk = $clientBuild.unauthorized.apk_path
foreach ($path in @($authorizedApk, $unauthorizedApk)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing admission client APK: $path" }
}

$brokerPackage = "io.github.mesmerprism.rustymanifold.broker"
$authorizedPackage = "io.github.mesmerprism.rustymanifold.admission.client"
$unauthorizedPackage = "io.github.mesmerprism.rustymanifold.admission.untrusted"
$activity = "io.github.mesmerprism.rustymanifold.admission_client.AdmissionClientActivity"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$results = @()

function Invoke-Adb([string]$DeviceSerial, [string[]]$Arguments, [switch]$AllowFailure) {
    $output = & $AdbPath -s $DeviceSerial @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb -s $DeviceSerial $($Arguments -join ' ') failed ($exitCode): $($output -join ' ')"
    }
    @($output)
}

function Wait-ForMarker([string]$DeviceSerial, [string]$Pattern, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $log = (Invoke-Adb $DeviceSerial @("logcat", "-d", "-v", "threadtime")) -join "`n"
        if ($log -match [regex]::Escape($Pattern)) { return $true }
        Start-Sleep -Milliseconds 750
    } while ([DateTime]::UtcNow -lt $deadline)
    $false
}

foreach ($deviceSerial in $Serial) {
    $deviceOut = Join-Path $OutDir $deviceSerial
    New-Item -ItemType Directory -Force -Path $deviceOut | Out-Null
    $status = "fail"
    $issues = @()
    $cleanupComplete = $false
    try {
        $state = (Invoke-Adb $deviceSerial @("get-state")) -join ""
        if ($state.Trim() -ne "device") { throw "Device is not ready: $state" }
        Invoke-Adb $deviceSerial @("install", "-r", $BrokerApk) | Set-Content -LiteralPath (Join-Path $deviceOut "install-broker.txt")
        Invoke-Adb $deviceSerial @("install", "-r", $authorizedApk) | Set-Content -LiteralPath (Join-Path $deviceOut "install-authorized.txt")
        Invoke-Adb $deviceSerial @("install", "-r", $unauthorizedApk) | Set-Content -LiteralPath (Join-Path $deviceOut "install-unauthorized.txt")
        Invoke-Adb $deviceSerial @("logcat", "-c") | Out-Null

        Invoke-Adb $deviceSerial @(
            "shell", "am", "start", "-n",
            "$authorizedPackage/$activity"
        ) | Set-Content -LiteralPath (Join-Path $deviceOut "start-authorized.txt")
        $authorizedMarker = "RUSTY_QUEST_BROKER_ADMISSION_CLIENT status=accepted variant=authorized issueApplied=true useApplied=true replayReason=replayed_request revokeApplied=true postRevokeReason=token_revoked"
        if (-not (Wait-ForMarker $deviceSerial $authorizedMarker 15)) {
            $issues += "authorized_lifecycle_marker_missing"
        }

        Invoke-Adb $deviceSerial @(
            "shell", "am", "start", "-n",
            "$unauthorizedPackage/$activity"
        ) | Set-Content -LiteralPath (Join-Path $deviceOut "start-unauthorized.txt")
        $unauthorizedMarker = "RUSTY_QUEST_BROKER_ADMISSION_CLIENT status=unauthorized-rejected variant=unauthorized reason=signature-permission"
        if (-not (Wait-ForMarker $deviceSerial $unauthorizedMarker 10)) {
            $issues += "unauthorized_signature_rejection_marker_missing"
        }

        $logcat = (Invoke-Adb $deviceSerial @("logcat", "-d", "-v", "threadtime")) -join "`n"
        [System.IO.File]::WriteAllText(
            (Join-Path $deviceOut "logcat.txt"),
            $logcat,
            (New-Object System.Text.UTF8Encoding($false)))
        $permissionDump = (Invoke-Adb $deviceSerial @("shell", "dumpsys", "package", $unauthorizedPackage)) -join "`n"
        [System.IO.File]::WriteAllText(
            (Join-Path $deviceOut "unauthorized-package.txt"),
            $permissionDump,
            (New-Object System.Text.UTF8Encoding($false)))
        if ($logcat -match "FATAL EXCEPTION[\s\S]{0,500}(rustymanifold|admission_client)" -or
            $logcat -match "Process: (io\.github\.mesmerprism\.rustymanifold\.(broker|admission))") {
            $issues += "package_fatal_present"
        }
        if ($permissionDump -notmatch "io\.github\.mesmerprism\.rustymanifold\.permission\.BROKER_ADMISSION") {
            $issues += "unauthorized_permission_request_not_visible"
        }
        if ($issues.Count -eq 0) { $status = "pass" }
    } finally {
        foreach ($packageName in @($authorizedPackage, $unauthorizedPackage, $brokerPackage)) {
            Invoke-Adb $deviceSerial @("shell", "am", "force-stop", $packageName) -AllowFailure | Out-Null
        }
        foreach ($packageName in @($authorizedPackage, $unauthorizedPackage, $brokerPackage)) {
            Invoke-Adb $deviceSerial @("uninstall", $packageName) -AllowFailure | Out-Null
        }
        $packages = (Invoke-Adb $deviceSerial @("shell", "pm", "list", "packages")) -join "`n"
        $remainingPackages = @($authorizedPackage, $unauthorizedPackage, $brokerPackage) |
            Where-Object { $packages -match [regex]::Escape($_) }
        $cleanupComplete = @($remainingPackages).Count -eq 0
        if (-not $cleanupComplete) {
            $issues += "cleanup_packages_remain"
            $status = "fail"
        }
    }
    $deviceResult = [ordered]@{
        serial = $deviceSerial
        status = $status
        authorized_lifecycle = $issues -notcontains "authorized_lifecycle_marker_missing"
        unauthorized_signature_rejection = $issues -notcontains "unauthorized_signature_rejection_marker_missing"
        package_fatal_count = @($issues | Where-Object { $_ -eq "package_fatal_present" }).Count
        cleanup_complete = $cleanupComplete
        issues = @($issues)
        evidence_dir = $deviceOut
    }
    $deviceResult | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $deviceOut "summary.json")
    $results += [pscustomobject]$deviceResult
}

$summary = [ordered]@{
    '$schema' = "rusty.quest.broker.admission_device_suite.v1"
    status = if (@($results | Where-Object status -ne "pass").Count -eq 0) { "pass" } else { "fail" }
    device_count = $results.Count
    results = @($results)
}
$summaryPath = Join-Path $OutDir "summary.json"
$summary | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 -LiteralPath $summaryPath
Write-Output $summaryPath
if ($summary.status -ne "pass") { exit 1 }
