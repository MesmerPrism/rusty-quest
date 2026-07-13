param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(
        "route_loss", "slow_consumer", "queue_pressure", "codec_failure",
        "cleanup_failure", "provider_death", "native_app_death", "spatial_app_death"
    )]
    [string]$Criterion,
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [Parameter(Mandatory = $true)][string]$RunId,
    [string]$Serial = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$BrokerApk = "",
    [string]$NativeApk = "",
    [string]$SpatialApk = "",
    [switch]$ConfirmBoundedLogcatClear
)

$ErrorActionPreference = "Stop"
$utf8 = [Text.UTF8Encoding]::new($false)
$repo = [IO.Path]::GetFullPath((Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path)
$evidence = [IO.Path]::GetFullPath($EvidenceDir)
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
if ($RunId -cnotmatch '^[a-z0-9][a-z0-9-]{7,127}$') { throw "RunId is invalid." }

$revision = (& git -C $repo rev-parse --verify HEAD).Trim().ToLowerInvariant()
if ($LASTEXITCODE -ne 0 -or $revision -cnotmatch '^[0-9a-f]{40}$') {
    throw "Unable to bind the exact repository revision."
}

# The deterministic Rust transition test is a useful source preflight, but it
# is deliberately not release evidence. In particular it cannot prove Android
# route loss, a real bounded queue, codec/provider rejection, process death,
# fresh provider epochs, app cleanup, or a bounded package/system fatal window.
$testName = "failure_recovery::tests::corrected_release_$Criterion"
$rawTestPath = Join-Path $evidence "unit-preflight.raw.txt"
$prior = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    $testOutput = @(
        & cargo test -p rusty-quest-media-stream --lib $testName -- --exact --nocapture 2>&1
    )
    $testExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $prior
}
$testText = (($testOutput | ForEach-Object { [string]$_ }) -join [Environment]::NewLine)
[IO.File]::WriteAllText($rawTestPath, $testText + [Environment]::NewLine, $utf8)
if ($testExit -ne 0 -or $testText -notmatch [regex]::Escape("test $testName ... ok")) {
    throw "Current-source failure-model preflight failed: $testName"
}

function New-Binding([string]$Path) {
    [ordered]@{
        path = [IO.Path]::GetFullPath($Path)
        sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

function Write-Raw([string]$Name, [string]$Text) {
    $path = Join-Path $evidence ($Name + ".raw.txt")
    [IO.File]::WriteAllText($path, $Text + [Environment]::NewLine, $utf8)
    New-Binding $path
}

function Write-Artifact([string]$Name, [object]$Value) {
    $path = Join-Path $evidence ($Name + ".json")
    [IO.File]::WriteAllText(
        $path,
        (($Value | ConvertTo-Json -Depth 20) + [Environment]::NewLine),
        $utf8)
    New-Binding $path
}

$hostCriteria = @("route_loss", "slow_consumer", "queue_pressure", "codec_failure", "cleanup_failure")
if ($hostCriteria -contains $Criterion) {
    $buildLogPath = Join-Path $evidence "host-probe-build.raw.txt"
    $prior = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $buildOutput = @(& cargo build -p rusty-quest-media-stream --bin corrected_release_failure_host 2>&1)
        $buildExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prior
    }
    $buildText = (($buildOutput | ForEach-Object { [string]$_ }) -join [Environment]::NewLine)
    [IO.File]::WriteAllText($buildLogPath, $buildText + [Environment]::NewLine, $utf8)
    if ($buildExit -ne 0) { throw "Host-live failure probe failed to build." }
    $binaryName = if ($IsWindows -or $env:OS -eq "Windows_NT") {
        "corrected_release_failure_host.exe"
    } else {
        "corrected_release_failure_host"
    }
    $binaryPath = Join-Path $repo "target\debug\$binaryName"
    if (-not (Test-Path -LiteralPath $binaryPath -PathType Leaf)) {
        throw "Built host-live failure probe is missing: $binaryPath"
    }
    $prior = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $probeOutput = @(& $binaryPath $Criterion 2>&1)
        $probeExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prior
    }
    $probeText = (($probeOutput | ForEach-Object { [string]$_ }) -join [Environment]::NewLine).Trim()
    $probeRawPath = Join-Path $evidence "host-probe.raw.json"
    [IO.File]::WriteAllText($probeRawPath, $probeText + [Environment]::NewLine, $utf8)
    if ($probeExit -ne 0) { throw "Host-live failure probe failed for $Criterion." }
    try { $probe = $probeText | ConvertFrom-Json } catch { throw "Host-live failure probe emitted invalid JSON." }
    if ([string]$probe.schema -cne "rusty.quest.corrected_release_host_failure_probe.v1" -or
        [string]$probe.criterion_id -cne $Criterion -or
        [string]$probe.implementation -cne "live-host-runtime-apis" -or
        [string]$probe.before.observed_state -cne "ready" -or
        [long]$probe.before.authority_revision -ne 1 -or
        -not [bool]$probe.before.cleanup_complete -or
        [long]$probe.failure.authority_revision -le [long]$probe.before.authority_revision -or
        [bool]$probe.failure.cleanup_complete -or
        [long]$probe.recovery.authority_revision -le [long]$probe.failure.authority_revision -or
        -not [bool]$probe.recovery.cleanup_complete) {
        throw "Host-live failure probe did not close its typed transition evidence."
    }

    $testId = "rusty.quest.corrected_release.$Criterion"
    $testBinding = New-Binding $rawTestPath
    $buildBinding = New-Binding $buildLogPath
    $probeBinding = New-Binding $probeRawPath
    $binaryBinding = New-Binding $binaryPath
    $injectionRaw = Write-Raw "damaged-input" (
        "run=$RunId criterion=$Criterion binary_sha256=$($binaryBinding.sha256) probe_sha256=$($probeBinding.sha256)")
    $injection = Write-Artifact "damaged-input" ([ordered]@{
        schema = "rusty.morphospace.failure_test_injection.v1"
        criterion_id = $Criterion
        test_id = $testId
        injection_kind = $Criterion
        target = "host-live:$($binaryBinding.path);sha256:$($binaryBinding.sha256)"
        triggered = $true
        repository_revision = $revision
        run_id = $RunId
        observed_at = [DateTimeOffset]::UtcNow.ToString("o")
        raw_evidence = $injectionRaw
    })

    function New-Phase([string]$Name, [object]$Observed, [object]$Observations) {
        $raw = Write-Raw $Name (($Observed | ConvertTo-Json -Depth 12 -Compress) +
            "`nprobe_sha256=$($probeBinding.sha256)`nbinary_sha256=$($binaryBinding.sha256)")
        [ordered]@{
            schema = "rusty.morphospace.failure_test_phase_receipt.v1"
            criterion_id = $Criterion
            test_id = $testId
            phase = $Name
            repository_revision = $revision
            injection_sha256 = $injection.sha256
            observed_state = [string]$Observed.observed_state
            cleanup_complete = [bool]$Observed.cleanup_complete
            fatal_count = 0
            authority_revision = [long]$Observed.authority_revision
            provider_epoch = [long]$Observed.provider_epoch
            run_id = $RunId
            observed_at = [DateTimeOffset]::UtcNow.ToString("o")
            raw_evidence = $raw
            observations = $Observations
        }
    }

    $before = Write-Artifact "before" (New-Phase "before" $probe.before ([ordered]@{}))
    $failure = Write-Artifact "failure" (New-Phase "failure" $probe.failure ([ordered]@{}))
    $recovery = Write-Artifact "recovery" (New-Phase "recovery" $probe.recovery $probe.observations)
    $marker = [ordered]@{
        schema = "rusty.morphospace.failure_test_result.v2"
        criterion_id = $Criterion
        test_id = $testId
        run_id = $RunId
        artifacts = [ordered]@{
            damaged_input = $injection
            before = $before
            failure = $failure
            recovery = $recovery
        }
    }
    Write-Output ("MORPHOSPACE_FAILURE_TEST_V2 " + ($marker | ConvertTo-Json -Depth 20 -Compress))
    exit 0
}

function Invoke-SerialAdb([string[]]$Arguments, [switch]$AllowFailure) {
    $prior = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& $Adb -s $Serial @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prior
    }
    $text = (($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine).Trim()
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "adb -s $Serial $($Arguments -join ' ') failed ($code): $text"
    }
    [pscustomobject][ordered]@{ exit_code = $code; output = $text }
}

function Wait-PackagePid([string]$PackageName, [bool]$Present) {
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $result = Invoke-SerialAdb -Arguments @("shell", "pidof", $PackageName) -AllowFailure
        $pidText = $result.output.Trim()
        if ($Present -and $pidText -match '^\d+(?:\s+\d+)*$') {
            return [long](@($pidText -split '\s+')[0])
        }
        if (-not $Present -and [string]::IsNullOrWhiteSpace($pidText)) {
            return 0
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Package process state did not become present=$Present for $PackageName."
}

function Get-LatestProviderEpoch([string]$LogText) {
    $matches = [regex]::Matches($LogText, 'providerEpoch=(epoch\.provider\.[0-9a-f]+)')
    if ($matches.Count -eq 0) { return "" }
    return $matches[$matches.Count - 1].Groups[1].Value
}

$deviceCriteria = @("provider_death", "native_app_death", "spatial_app_death")
if ($deviceCriteria -contains $Criterion) {
    if (-not $ConfirmBoundedLogcatClear) {
        throw "Android death criteria require -ConfirmBoundedLogcatClear."
    }
    if ($Serial -cnotmatch '^[A-Za-z0-9._:-]+$') { throw "An explicit Quest serial is required." }
    if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) { throw "ADB is missing: $Adb" }
    $device = switch ($Criterion) {
        "provider_death" {
            [pscustomobject]@{
                package = "io.github.mesmerprism.rustymanifold.broker"
                component = "io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity"
                apk = $BrokerApk
            }
        }
        "native_app_death" {
            [pscustomobject]@{
                package = "io.github.mesmerprism.rustyquest.native_renderer"
                component = "io.github.mesmerprism.rustyquest.native_renderer/android.app.NativeActivity"
                apk = $NativeApk
            }
        }
        "spatial_app_death" {
            [pscustomobject]@{
                package = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
                component = "io.github.mesmerprism.rustyquest.spatial_camera_panel/.SpatialCameraPanelActivity"
                apk = $SpatialApk
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace([string]$device.apk) -or
        -not (Test-Path -LiteralPath ([string]$device.apk) -PathType Leaf)) {
        throw "Current APK is required for $Criterion."
    }
    $apk = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath ([string]$device.apk)).Path)
    $apkBinding = New-Binding $apk
    $state = Invoke-SerialAdb -Arguments @("get-state")
    if ($state.output -cne "device") { throw "Quest $Serial is not ready." }

    $install = $null
    $firstLaunch = $null
    $secondLaunch = $null
    $stop = $null
    $beforePid = 0
    $recoveryPid = 0
    $beforeEpoch = ""
    $recoveryEpoch = ""
    $beforeLog = ""
    $failurePidText = ""
    $finalLog = ""
    $cleanupLines = @()
    try {
        Invoke-SerialAdb -Arguments @("logcat", "-c") | Out-Null
        Invoke-SerialAdb -Arguments @("shell", "am", "force-stop", [string]$device.package) -AllowFailure | Out-Null
        Invoke-SerialAdb -Arguments @("uninstall", [string]$device.package) -AllowFailure | Out-Null
        $install = Invoke-SerialAdb -Arguments @("install", "-r", $apk)
        if ($install.output -notmatch '(?im)Success') { throw "APK install did not report Success." }
        $firstLaunch = Invoke-SerialAdb -Arguments @("shell", "am", "start", "-W", "-n", [string]$device.component)
        $beforePid = Wait-PackagePid -PackageName ([string]$device.package) -Present $true
        $beforeLog = (Invoke-SerialAdb -Arguments @("logcat", "-d", "-v", "epoch")).output
        if ($Criterion -eq "provider_death") {
            $beforeEpoch = Get-LatestProviderEpoch -LogText $beforeLog
            if ([string]::IsNullOrWhiteSpace($beforeEpoch)) {
                throw "Broker launch did not expose a provider epoch."
            }
        }

        $stop = Invoke-SerialAdb -Arguments @("shell", "am", "force-stop", [string]$device.package)
        Wait-PackagePid -PackageName ([string]$device.package) -Present $false | Out-Null
        $failurePidText = (Invoke-SerialAdb -Arguments @("shell", "pidof", [string]$device.package) -AllowFailure).output
        if (-not [string]::IsNullOrWhiteSpace($failurePidText)) {
            throw "Force-stopped process remained alive."
        }

        $secondLaunch = Invoke-SerialAdb -Arguments @("shell", "am", "start", "-W", "-n", [string]$device.component)
        $recoveryPid = Wait-PackagePid -PackageName ([string]$device.package) -Present $true
        if ($recoveryPid -eq $beforePid) { throw "Process recovery reused the terminated PID." }
        $recoveryLog = (Invoke-SerialAdb -Arguments @("logcat", "-d", "-v", "epoch")).output
        if ($Criterion -eq "provider_death") {
            $recoveryEpoch = Get-LatestProviderEpoch -LogText $recoveryLog
            if ([string]::IsNullOrWhiteSpace($recoveryEpoch) -or $recoveryEpoch -ceq $beforeEpoch) {
                throw "Provider recovery did not expose a fresh epoch."
            }
        }
    } finally {
        try {
            $cleanupStop = Invoke-SerialAdb -Arguments @("shell", "am", "force-stop", [string]$device.package) -AllowFailure
            $cleanupLines += "force_stop_exit=$($cleanupStop.exit_code)"
        } catch { $cleanupLines += "force_stop_error=$($_.Exception.Message)" }
        try {
            $cleanupUninstall = Invoke-SerialAdb -Arguments @("uninstall", [string]$device.package) -AllowFailure
            $cleanupLines += "uninstall_exit=$($cleanupUninstall.exit_code) output=$($cleanupUninstall.output)"
        } catch { $cleanupLines += "uninstall_error=$($_.Exception.Message)" }
        try { $finalLog = (Invoke-SerialAdb -Arguments @("logcat", "-d", "-v", "epoch") -AllowFailure).output } catch { $finalLog = "logcat_error=$($_.Exception.Message)" }
    }

    $finalPid = (Invoke-SerialAdb -Arguments @("shell", "pidof", [string]$device.package) -AllowFailure).output.Trim()
    $packageList = (Invoke-SerialAdb -Arguments @("shell", "pm", "list", "packages")).output
    $packageRemaining = $packageList -match "(?m)^package:$([regex]::Escape([string]$device.package))$"
    $fatalCount = [regex]::Matches(
        $finalLog,
        '(?im)FATAL EXCEPTION(?: IN SYSTEM PROCESS)?|Fatal signal\s+\d+|Watchdog[^\r\n]*system_server'
    ).Count
    if (-not [string]::IsNullOrWhiteSpace($finalPid) -or $packageRemaining -or $fatalCount -ne 0 -or
        @($cleanupLines | Where-Object { $_ -match '_error=' }).Count -ne 0) {
        throw "Android death criterion cleanup/fatal closure failed."
    }

    $testId = "rusty.quest.corrected_release.$Criterion"
    $beforeRaw = Write-Raw "before" (($firstLaunch | ConvertTo-Json -Depth 6 -Compress) +
        "`nserial=$Serial package=$($device.package) pid=$beforePid provider_epoch=$beforeEpoch apk_sha256=$($apkBinding.sha256)`n$beforeLog")
    $failureRaw = Write-Raw "failure" (($stop | ConvertTo-Json -Depth 6 -Compress) +
        "`nserial=$Serial terminated_pid=$beforePid pid_after=$failurePidText")
    $recoveryRaw = Write-Raw "recovery" (($secondLaunch | ConvertTo-Json -Depth 6 -Compress) +
        "`nserial=$Serial recovery_pid=$recoveryPid provider_epoch=$recoveryEpoch`ncleanup=$($cleanupLines -join ';')`npackages_after=$packageList`n$finalLog")
    $injectionRaw = Write-Raw "damaged-input" (
        "run=$RunId criterion=$Criterion serial=$Serial package=$($device.package) apk_sha256=$($apkBinding.sha256)")
    $injection = Write-Artifact "damaged-input" ([ordered]@{
        schema = "rusty.morphospace.failure_test_injection.v1"
        criterion_id = $Criterion
        test_id = $testId
        injection_kind = $Criterion
        target = "adb:${Serial}:$($device.package);apk-sha256:$($apkBinding.sha256)"
        triggered = $true
        repository_revision = $revision
        run_id = $RunId
        observed_at = [DateTimeOffset]::UtcNow.ToString("o")
        raw_evidence = $injectionRaw
    })
    $states = @{
        provider_death = @("provider_terminated", "recovered_fresh_epoch")
        native_app_death = @("native_app_terminated", "recovered")
        spatial_app_death = @("spatial_app_terminated", "recovered")
    }
    $observations = if ($Criterion -eq "provider_death") {
        [ordered]@{
            before_pid = $beforePid
            terminated_pid = $beforePid
            recovery_pid = $recoveryPid
            before_epoch = 1
            recovery_epoch = 2
        }
    } else {
        [ordered]@{
            package = [string]$device.package
            before_pid = $beforePid
            terminated_pid = $beforePid
            recovery_pid = $recoveryPid
            resources_remaining = 0
        }
    }
    function New-DevicePhase([string]$Name, [string]$StateName, [long]$AuthorityRevision, [long]$ProviderEpoch, [bool]$Cleanup, [object]$Raw, [object]$Observations) {
        [ordered]@{
            schema = "rusty.morphospace.failure_test_phase_receipt.v1"
            criterion_id = $Criterion
            test_id = $testId
            phase = $Name
            repository_revision = $revision
            injection_sha256 = $injection.sha256
            observed_state = $StateName
            cleanup_complete = $Cleanup
            fatal_count = 0
            authority_revision = $AuthorityRevision
            provider_epoch = $ProviderEpoch
            run_id = $RunId
            observed_at = [DateTimeOffset]::UtcNow.ToString("o")
            raw_evidence = $Raw
            observations = $Observations
        }
    }
    $before = Write-Artifact "before" (New-DevicePhase "before" "ready" 1 1 $true $beforeRaw ([ordered]@{}))
    $failure = Write-Artifact "failure" (New-DevicePhase "failure" $states[$Criterion][0] 2 1 $false $failureRaw ([ordered]@{}))
    $recoveryProviderEpoch = if ($Criterion -eq "provider_death") { 2 } else { 1 }
    $recovery = Write-Artifact "recovery" (New-DevicePhase "recovery" $states[$Criterion][1] 3 $recoveryProviderEpoch $true $recoveryRaw $observations)
    $marker = [ordered]@{
        schema = "rusty.morphospace.failure_test_result.v2"
        criterion_id = $Criterion
        test_id = $testId
        run_id = $RunId
        artifacts = [ordered]@{ damaged_input = $injection; before = $before; failure = $failure; recovery = $recovery }
    }
    Write-Output ("MORPHOSPACE_FAILURE_TEST_V2 " + ($marker | ConvertTo-Json -Depth 20 -Compress))
    exit 0
}

$blockedPath = Join-Path $evidence "non-promotional-unit-preflight.json"
$blocked = [ordered]@{
    schema = "rusty.quest.corrected_release_failure_adapter_blocked.v1"
    status = "blocked"
    criterion_id = $Criterion
    run_id = $RunId
    repository_revision = $revision
    unit_preflight = New-Binding $rawTestPath
    missing_authority = "explicit-serial APK process death/restart/epoch/cleanup/logcat evidence"
    promotional_marker_emitted = $false
    observed_at = [DateTimeOffset]::UtcNow.ToString("o")
}
[IO.File]::WriteAllText($blockedPath, (($blocked | ConvertTo-Json -Depth 8) + [Environment]::NewLine), $utf8)
throw "Criterion '$Criterion' requires the explicit-serial Android death adapter. No promotional marker was emitted."
