[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string[]]$Serial,
    [Parameter(Mandatory=$true)][string]$RunCapsule,
    [Parameter(Mandatory=$true)][string]$KeyRecordManifest,
    [Parameter(Mandatory=$true)][string[]]$ProfilePath,
    [Parameter(Mandatory=$true)][string[]]$SigningSeedPath,
    [Parameter(Mandatory=$true)][uri]$HubBaseUri,
    [Parameter(Mandatory=$true)][string]$EvidenceDir,
    [Parameter(Mandatory=$true)][ValidateRange(1000, 300000)][int]$StaleAfterMs,
    [Parameter(Mandatory=$true)][ValidateRange(2000, 600000)][int]$OfflineAfterMs,
    [ValidateRange(1000, 30000)][int]$TransitionToleranceMs = 5000,
    [ValidateRange(10, 120)][int]$AdbTimeoutSeconds = 30,
    [ValidateRange(30, 300)][int]$PackageTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Fleet Agent device smoke requires PowerShell 7.6 Core or newer."
}
if ($Serial.Count -ne 2 -or @($Serial | Select-Object -Unique).Count -ne 2) {
    throw "Exactly two distinct Quest serials are required."
}
if ($ProfilePath.Count -ne 2 -or $SigningSeedPath.Count -ne 2) {
    throw "Exactly two profiles and two signing-seed files are required."
}
if ($OfflineAfterMs -le $StaleAfterMs) {
    throw "OfflineAfterMs must be greater than StaleAfterMs."
}
if ($HubBaseUri.Scheme -notin @("http", "https") -or
    -not [string]::IsNullOrEmpty($HubBaseUri.Query) -or
    -not [string]::IsNullOrEmpty($HubBaseUri.Fragment) -or
    $HubBaseUri.AbsolutePath -ne "/") {
    throw "HubBaseUri must be an explicit HTTP(S) origin without a path, query, or fragment."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$repoPrefix = $repoRoot.TrimEnd("\") + "\"
$evidenceFull = [System.IO.Path]::GetFullPath($EvidenceDir).TrimEnd("\")
if ($evidenceFull.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Device evidence must stay outside the public Rusty Quest checkout."
}
if (Test-Path -LiteralPath $evidenceFull) {
    throw "EvidenceDir must not already exist: $evidenceFull"
}
$adb = (Get-Command adb.exe -ErrorAction Stop).Source
$capsulePath = (Resolve-Path -LiteralPath $RunCapsule).Path

& pwsh -NoProfile -ExecutionPolicy Bypass -File `
    (Join-Path $repoRoot "tools\Test-ApkRunCapsule.ps1") `
    -Path $capsulePath
if ($LASTEXITCODE -ne 0) {
    throw "Fleet Agent run capsule validation failed."
}
$capsule = Get-Content -Raw -LiteralPath $capsulePath | ConvertFrom-Json
if ([string]$capsule.app_id -ne "fleet_agent" -or
    [string]$capsule.app_lane -ne "fleet-agent-android") {
    throw "Run capsule does not describe the Fleet Agent Android lane."
}
$package = [string]$capsule.android.package_name
$activity = [string]$capsule.android.activity
$service = [string]$capsule.android.service
if ($package -ne "io.github.mesmerprism.rustyquest.fleetagent" -or
    $activity -ne "io.github.mesmerprism.rustyquest.fleetagent/.FleetAgentActivity" -or
    $service -ne "io.github.mesmerprism.rustyquest.fleetagent/.FleetAgentService") {
    throw "Run capsule Android identity does not match the Fleet Agent contract."
}
$apkPath = (Resolve-Path -LiteralPath ([string]$capsule.apk.path)).Path
$expectedApkHash = [string]$capsule.apk.sha256
$observedApkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($observedApkHash -ne $expectedApkHash) {
    throw "Fleet Agent APK hash does not match the run capsule."
}
$keyManifestPath = (Resolve-Path -LiteralPath $KeyRecordManifest).Path
$keyManifest = Get-Content -Raw -LiteralPath $keyManifestPath | ConvertFrom-Json
if ([string]$keyManifest.schema -ne "rusty.quest.fleet_agent_key_record_tool.v1" -or
    [string]$keyManifest.source_commit -ne [string]$capsule.source.commit -or
    [string]$keyManifest.source_tree -ne [string]$capsule.source.tree) {
    throw "Fleet Agent key-record tool is not bound to the run-capsule source."
}
$keyRecordTool = (Resolve-Path -LiteralPath ([string]$keyManifest.executable_path)).Path
$keyRecordToolHash = (Get-FileHash -LiteralPath $keyRecordTool -Algorithm SHA256).
    Hash.ToLowerInvariant()
if ($keyRecordToolHash -ne [string]$keyManifest.executable_sha256) {
    throw "Fleet Agent key-record tool hash does not match its manifest."
}
$capsuleRepositories = @($capsule.source) + @($capsule.source.dependencies)
foreach ($repository in @($keyManifest.source_repositories)) {
    $match = @($capsuleRepositories | Where-Object {
        [string]$_.repository_id -eq [string]$repository.repository_id -and
        [string]$_.commit -eq [string]$repository.commit -and
        [string]$_.tree -eq [string]$repository.tree
    })
    if ($match.Count -ne 1) {
        throw "Fleet Agent key-record tool dependency source does not match the run capsule."
    }
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory=$true)][string]$Device,
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [int]$TimeoutSeconds = $AdbTimeoutSeconds,
        [byte[]]$InputBytes = $null,
        [switch]$AllowFailure
    )
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adb
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $null -ne $InputBytes
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.ArgumentList.Add("-s")
    $start.ArgumentList.Add($Device)
    foreach ($argument in $Arguments) {
        $start.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) {
        throw "Unable to start exact-serial adb process."
    }
    try {
        $stopwatch = [Diagnostics.Stopwatch]::StartNew()
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $inputTimedOut = $false
        $ioFailure = $null
        if ($null -ne $InputBytes) {
            try {
                $writeTask = $process.StandardInput.BaseStream.WriteAsync(
                    $InputBytes,
                    0,
                    $InputBytes.Length)
                $remainingMs = [Math]::Max(
                    0,
                    ($TimeoutSeconds * 1000) - [int]$stopwatch.ElapsedMilliseconds)
                $inputTimedOut = -not $writeTask.Wait($remainingMs)
                if (-not $inputTimedOut -and
                    -not $writeTask.IsCompletedSuccessfully) {
                    $ioFailure = "adb standard-input write did not complete successfully"
                }
                if (-not $inputTimedOut -and $null -eq $ioFailure) {
                    $disposeTask =
                        $process.StandardInput.BaseStream.DisposeAsync().AsTask()
                    $remainingMs = [Math]::Max(
                        0,
                        ($TimeoutSeconds * 1000) - [int]$stopwatch.ElapsedMilliseconds)
                    $inputTimedOut = -not $disposeTask.Wait($remainingMs)
                    if (-not $inputTimedOut -and
                        -not $disposeTask.IsCompletedSuccessfully) {
                        $ioFailure =
                            "adb standard-input close did not complete successfully"
                    }
                }
            } catch {
                $ioFailure = "adb standard-input I/O failed"
            }
        }
        $processTimedOut = $false
        if (-not $inputTimedOut -and $null -eq $ioFailure) {
            try {
                $remainingMs = [Math]::Max(
                    0,
                    ($TimeoutSeconds * 1000) - [int]$stopwatch.ElapsedMilliseconds)
                $processTimedOut = -not $process.WaitForExit($remainingMs)
            } catch {
                $ioFailure = "adb process wait failed"
            }
        }
        $timedOut = $inputTimedOut -or $processTimedOut
        $terminationConfirmed = $process.HasExited
        if (($timedOut -or $null -ne $ioFailure) -and
            -not $terminationConfirmed) {
            try {
                $process.Kill($true)
                $terminationConfirmed = $process.WaitForExit(5000)
            } catch {
                $terminationConfirmed = $process.HasExited
            }
        }
        if (-not $terminationConfirmed) {
            $ioFailure = "adb process termination could not be confirmed"
        }
        $streamsDrained = $false
        if ($terminationConfirmed -and -not $timedOut -and
            $null -eq $ioFailure) {
            try {
                $remainingMs = [Math]::Max(
                    0,
                    ($TimeoutSeconds * 1000) - [int]$stopwatch.ElapsedMilliseconds)
                $streamsDrained = [Threading.Tasks.Task]::WaitAll(
                    [Threading.Tasks.Task[]]@($stdoutTask, $stderrTask),
                    $remainingMs)
            } catch {
                $ioFailure = "adb output I/O failed"
            }
        }
        if (-not $streamsDrained -and $null -eq $ioFailure) {
            $timedOut = $true
        }
        $stdout = if ($streamsDrained) {
            $stdoutTask.GetAwaiter().GetResult()
        } else {
            ""
        }
        $stderr = if ($streamsDrained) {
            $stderrTask.GetAwaiter().GetResult()
        } else {
            if ($null -ne $ioFailure) {
                $ioFailure
            } else {
                "adb process output did not close within the absolute deadline"
            }
        }
        $exitCode = if ($timedOut -or $null -ne $ioFailure) {
            $null
        } else {
            $process.ExitCode
        }
        $result = [pscustomobject]@{
            timed_out = $timedOut
            io_failed = $null -ne $ioFailure
            termination_confirmed = $terminationConfirmed
            streams_drained = $streamsDrained
            exit_code = $exitCode
            stdout = $stdout
            stderr = $stderr
            output = @($stdout -split "\r?\n" | Where-Object { $_ -ne "" })
        }
        if (($timedOut -or $null -ne $ioFailure -or $exitCode -ne 0) -and
            -not $AllowFailure) {
            $reason = if ($null -ne $ioFailure) {
                $ioFailure
            } elseif ($timedOut) {
                "timed out after $TimeoutSeconds seconds"
            } else {
                "failed with exit code $exitCode"
            }
            throw "adb -s $Device $($Arguments -join ' ') $reason`: $stderr"
        }
        return $result
    } finally {
        if (-not $process.HasExited) {
            try {
                $process.Kill($true)
                if (-not $process.WaitForExit(5000)) {
                    throw "bounded termination wait expired"
                }
            } catch {
                if (-not $process.HasExited) {
                    $process.Dispose()
                    throw "Exact-serial adb process termination could not be confirmed."
                }
            }
        }
        $process.Dispose()
    }
}

function Test-RemoteProcessPresent {
    param(
        [Parameter(Mandatory=$true)][string]$Device,
        [Parameter(Mandatory=$true)][string]$ProcessName
    )
    $probe = Invoke-Adb -Device $Device -Arguments @(
        "shell", "pidof", $ProcessName) -AllowFailure
    $probeOutput = ($probe.output -join " ").Trim()
    if ($probe.exit_code -eq 0 -and
        $probeOutput -match '^\d+(?:\s+\d+)*$') {
        return $true
    }
    if ($probe.exit_code -eq 1 -and
        [string]::IsNullOrWhiteSpace($probeOutput)) {
        return $false
    }
    throw "Unable to determine process state for $ProcessName on $Device."
}

function Test-RemotePackagePresent {
    param(
        [Parameter(Mandatory=$true)][string]$Device,
        [Parameter(Mandatory=$true)][string]$PackageName
    )
    $probe = Invoke-Adb -Device $Device -Arguments @(
        "shell", "pm", "path", $PackageName) -AllowFailure
    $probeOutput = ($probe.output -join "`n").Trim()
    if ($probe.exit_code -eq 0 -and
        $probeOutput -match '^(?:package:\S+)(?:\r?\npackage:\S+)*$') {
        return $true
    }
    if ($probe.exit_code -eq 1 -and
        [string]::IsNullOrWhiteSpace($probeOutput)) {
        return $false
    }
    throw "Unable to determine package state for $PackageName on $Device."
}

function Invoke-KeyRecordTool {
    param(
        [Parameter(Mandatory=$true)][string]$KeyId,
        [Parameter(Mandatory=$true)][string]$SeedFile
    )
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $keyRecordTool
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @("--key-id", $KeyId, "--seed-file", $SeedFile)) {
        $start.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) {
        throw "Unable to start the source-bound Fleet Agent key-record tool."
    }
    try {
        $stopwatch = [Diagnostics.Stopwatch]::StartNew()
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $timedOut = -not $process.WaitForExit(30000)
        $terminationConfirmed = $process.HasExited
        if ($timedOut) {
            try {
                $process.Kill($true)
                $terminationConfirmed = $process.WaitForExit(5000)
            } catch {
                $terminationConfirmed = $process.HasExited
            }
        }
        if (-not $terminationConfirmed) {
            throw "Fleet Agent key-record tool termination could not be confirmed."
        }
        $streamsDrained = $false
        if (-not $timedOut) {
            $remainingMs = [Math]::Max(
                0,
                30000 - [int]$stopwatch.ElapsedMilliseconds)
            $streamsDrained = [Threading.Tasks.Task]::WaitAll(
                [Threading.Tasks.Task[]]@($stdoutTask, $stderrTask),
                $remainingMs)
        }
        if ($timedOut -or -not $streamsDrained) {
            throw "Fleet Agent key-record tool exceeded its absolute deadline."
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw "Fleet Agent key-record tool failed without exposing private material: $stderr"
        }
        return ($stdout | ConvertFrom-Json)
    } finally {
        $process.Dispose()
    }
}

function Get-HubInspect {
    param(
        [Parameter(Mandatory=$true)][string]$DeviceId,
        [ValidateRange(1, 5)][int]$TimeoutSeconds = 5
    )
    $escaped = [Uri]::EscapeDataString($DeviceId)
    $uri = [Uri]::new($HubBaseUri, "/fleet/v1/devices/$escaped/inspect")
    $response = Invoke-WebRequest -Uri $uri -Method Get -SkipHttpErrorCheck `
        -TimeoutSec $TimeoutSeconds
    if ([int]$response.StatusCode -eq 404) {
        return $null
    }
    if ([int]$response.StatusCode -ne 200) {
        throw "Fleet Hub inspect returned HTTP $([int]$response.StatusCode)."
    }
    return ($response.Content | ConvertFrom-Json)
}

function Assert-HubAcceptedEvent {
    param(
        [Parameter(Mandatory=$true)][string]$DeviceId,
        [Parameter(Mandatory=$true)][long]$SourceRevision,
        [Parameter(Mandatory=$true)][long]$AcceptedRevision,
        [Parameter(Mandatory=$true)][long]$AcceptedAtMs
    )
    $uri = [Uri]::new($HubBaseUri, "/fleet/v1/watch?after_sequence=0&limit=10000")
    $response = Invoke-WebRequest -Uri $uri -Method Get -SkipHttpErrorCheck -TimeoutSec 5
    if ([int]$response.StatusCode -ne 200) {
        throw "Fleet Hub watch returned HTTP $([int]$response.StatusCode)."
    }
    $events = @($response.Content | ConvertFrom-Json)
    $matches = @($events | Where-Object {
        [string]$_.decision.decision -eq "accepted" -and
        [string]$_.decision.device_id -eq $DeviceId -and
        [long]$_.decision.source_revision -eq $SourceRevision -and
        [long]$_.decision.result_revision -eq $AcceptedRevision -and
        [long]$_.observed_at_ms -eq $AcceptedAtMs
    })
    if ($matches.Count -ne 1) {
        throw "Fleet Hub watch does not contain one exact accepted source revision event."
    }
}

function Wait-HubFreshness {
    param(
        [Parameter(Mandatory=$true)][string]$DeviceId,
        [Parameter(Mandatory=$true)][ValidateSet("fresh", "stale", "offline")][string]$Expected,
        [Parameter(Mandatory=$true)][int]$TimeoutSeconds,
        [object]$StoppedBaseline = $null,
        [long]$MinimumAgeMs = 0,
        [long]$MaximumAgeMs = [long]::MaxValue,
        [string]$ContinuityDeviceId = "",
        [long]$ContinuityAcceptedAtMs = 0
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $remainingSeconds = ($deadline - (Get-Date)).TotalSeconds
        if ($remainingSeconds -le 0) {
            break
        }
        $requestTimeout = [Math]::Max(
            1,
            [Math]::Min(5, [Math]::Ceiling($remainingSeconds)))
        $projection = Get-HubInspect -DeviceId $DeviceId `
            -TimeoutSeconds $requestTimeout
        if ($null -ne $projection -and
            [string]$projection.row.identity.device_id -eq $DeviceId -and
            [string]$projection.row.freshness -eq $Expected -and
            [long]$projection.row.age_ms -ge $MinimumAgeMs -and
            [long]$projection.row.age_ms -le $MaximumAgeMs) {
            if ($null -ne $StoppedBaseline -and (
                [long]$projection.row.accepted_at_ms -ne
                    [long]$StoppedBaseline.row.accepted_at_ms -or
                [long]$projection.row.accepted_revision -ne
                    [long]$StoppedBaseline.row.accepted_revision -or
                [string]$projection.row.source_epoch -ne
                    [string]$StoppedBaseline.row.source_epoch)) {
                throw "Stopped device advanced after its stop baseline."
            }
            if (-not [string]::IsNullOrWhiteSpace($ContinuityDeviceId)) {
                $continuity = Get-HubInspect -DeviceId $ContinuityDeviceId
                if ($null -eq $continuity -or
                    [string]$continuity.row.freshness -ne "fresh" -or
                    [long]$continuity.row.accepted_at_ms -le $ContinuityAcceptedAtMs) {
                    throw "Continuity device was not fresh while $DeviceId became $Expected."
                }
            }
            return $projection
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    $last = if ($null -eq $projection) { "absent" } else { [string]$projection.row.freshness }
    throw "Timed out waiting for $DeviceId freshness=$Expected; last=$last."
}

function Get-DeviceBattery {
    param([Parameter(Mandatory=$true)][string]$Device)
    $text = (Invoke-Adb -Device $Device -Arguments @("shell", "dumpsys", "battery")).output -join "`n"
    $levelMatch = [regex]::Match($text, '(?m)^\s*level:\s*(\d+)\s*$')
    $statusMatch = [regex]::Match($text, '(?m)^\s*status:\s*(\d+)\s*$')
    if (-not $levelMatch.Success -or -not $statusMatch.Success) {
        throw "Unable to parse Android battery authority for $Device."
    }
    $status = [int]$statusMatch.Groups[1].Value
    [pscustomobject]@{
        battery_percent = [int]$levelMatch.Groups[1].Value
        charging = $status -in @(2, 5)
        status_code = $status
    }
}

function Get-AppPrivateText {
    param(
        [Parameter(Mandatory=$true)][string]$Device,
        [Parameter(Mandatory=$true)][string]$RelativePath
    )
    $result = Invoke-Adb -Device $Device -Arguments @(
        "exec-out", "run-as", $package, "cat", $RelativePath)
    return ($result.output -join "`n")
}

function Assert-ServiceStopped {
    param([Parameter(Mandatory=$true)][string]$Device)
    $dump = (Invoke-Adb -Device $Device -Arguments @(
        "shell", "dumpsys", "activity", "services", $service)).output -join "`n"
    if ($dump -match [regex]::Escape("FleetAgentService")) {
        throw "Fleet Agent service remains active on $Device."
    }
}

function Stop-Agent {
    param(
        [Parameter(Mandatory=$true)][string]$Device,
        [switch]$AllowFailure
    )
    $result = Invoke-Adb -Device $Device -Arguments @(
        "shell", "am", "start",
        "-a", "io.github.mesmerprism.rustyquest.fleetagent.DEBUG_STOP",
        "-n", $activity) -AllowFailure:$AllowFailure
    Start-Sleep -Seconds 2
    if (-not $AllowFailure -and $result.exit_code -eq 0) {
        Assert-ServiceStopped -Device $Device
    }
}

function Get-BoundedLogs {
    param(
        [Parameter(Mandatory=$true)][string]$Device,
        [Parameter(Mandatory=$true)][string]$StartedAt
    )
    $main = (Invoke-Adb -Device $Device -Arguments @(
        "logcat", "-d", "-v", "threadtime", "-T", $StartedAt,
        "RustyFleetAgent:V", "AndroidRuntime:E", "ActivityManager:E", "*:S")
    ).output -join "`n"
    $crash = (Invoke-Adb -Device $Device -Arguments @(
        "logcat", "-b", "crash", "-d", "-v", "threadtime", "-T", $StartedAt
    )).output -join "`n"
    [pscustomobject]@{
        main = $main
        crash = $crash
        fatal_count = @(
            [regex]::Matches(
                "$main`n$crash",
                "(?im)FATAL EXCEPTION|Process:\s*$([regex]::Escape($package))")
        ).Count
    }
}

$profiles = @()
$seedFiles = @()
$publicKeyRecords = @()
$expectedEndpoint = [Uri]::new($HubBaseUri, "/fleet/v1/checkins").AbsoluteUri
for ($index = 0; $index -lt 2; $index++) {
    $profileResolved = (Resolve-Path -LiteralPath $ProfilePath[$index]).Path
    $seedResolved = (Resolve-Path -LiteralPath $SigningSeedPath[$index]).Path
    $profile = Get-Content -Raw -LiteralPath $profileResolved | ConvertFrom-Json
    if ([string]$profile.schema -ne "rusty.quest.fleet_agent_profile.v1" -or
        $profile.enabled -ne $true) {
        throw "Profile $index is not an explicitly enabled Fleet Agent v1 profile."
    }
    if ([string]$profile.hub_endpoint -ne $expectedEndpoint) {
        throw "Profile $index Hub endpoint does not match HubBaseUri."
    }
    if ([long]$profile.checkin_ttl_ms -lt 10000 -or
        [long]$profile.checkin_ttl_ms -gt 300000 -or
        [long]$profile.checkin_interval_ms -lt 5000 -or
        [long]$profile.checkin_interval_ms -ge [long]$profile.checkin_ttl_ms) {
        throw "Profile $index check-in interval and TTL are invalid."
    }
    if ((Get-Item -LiteralPath $seedResolved).Length -ne 32) {
        throw "Signing seed $index must contain exactly 32 bytes."
    }
    $keyRecord = Invoke-KeyRecordTool `
        -KeyId ([string]$profile.key_id) `
        -SeedFile $seedResolved
    if ([string]$keyRecord.key_id -ne [string]$profile.key_id -or
        [string]$keyRecord.key_fingerprint -ne [string]$profile.key_fingerprint) {
        throw "Profile $index signing identity does not match its private seed."
    }
    $profiles += [pscustomobject]@{
        path = $profileResolved
        value = $profile
        bytes = [IO.File]::ReadAllBytes($profileResolved)
    }
    $seedFiles += [pscustomobject]@{
        path = $seedResolved
        bytes = [IO.File]::ReadAllBytes($seedResolved)
    }
    $publicKeyRecords += $keyRecord
}
if (@($profiles.value.device_id | Select-Object -Unique).Count -ne 2 -or
    @($profiles.value.key_id | Select-Object -Unique).Count -ne 2 -or
    @($profiles.value.key_fingerprint | Select-Object -Unique).Count -ne 2 -or
    @($publicKeyRecords.public_key_hex | Select-Object -Unique).Count -ne 2) {
    throw "The two Quest profiles must use distinct device and signing identities."
}
$maximumIntervalMs = [long](($profiles.value.checkin_interval_ms |
    Measure-Object -Maximum).Maximum)
if ($maximumIntervalMs + $TransitionToleranceMs -ge $StaleAfterMs) {
    throw "Hub stale threshold must leave one check-in interval plus tolerance for continuity proof."
}
$freshTimeoutSeconds = [Math]::Ceiling(
    ($maximumIntervalMs + $TransitionToleranceMs + 10000) / 1000.0)
$staleTimeoutSeconds = [Math]::Ceiling(
    ($StaleAfterMs + $TransitionToleranceMs) / 1000.0)
$offlineTimeoutSeconds = [Math]::Ceiling(
    ($OfflineAfterMs + $TransitionToleranceMs) / 1000.0)

New-Item -ItemType Directory -Path $evidenceFull | Out-Null
$logStartedAt = @($null, $null)
$preflightComplete = @($false, $false)
$packagePresentBefore = @($null, $null)
$processPresentBefore = @($null, $null)
$mutationAttempted = @($false, $false)
$primaryFailure = $null
$summary = [ordered]@{
    schema = "rusty.quest.fleet_agent_two_quest_smoke.v1"
    status = "running"
    source_commit = [string]$capsule.source.commit
    source_tree = [string]$capsule.source.tree
    source_composition_fingerprint = [string]$capsule.source.composition_fingerprint
    apk_sha256 = $observedApkHash
    started_at = (Get-Date).ToUniversalTime().ToString("o")
    hub_origin = $HubBaseUri.AbsoluteUri
    devices = @()
    cleanup = @()
}

try {
    for ($index = 0; $index -lt 2; $index++) {
        $device = $Serial[$index]
        $state = (Invoke-Adb -Device $device -Arguments @("get-state")).output -join ""
        if ($state.Trim() -ne "device") {
            throw "Quest $device is not in adb device state."
        }
        $logStartedAt[$index] = (
            (Invoke-Adb -Device $device -Arguments @(
                "shell", "date '+%m-%d %H:%M:%S.000'")).output -join ""
        ).Trim()
        if ($logStartedAt[$index] -notmatch '^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$') {
            throw "Unable to establish the device-owned log boundary for $device."
        }
        $packagePresentBefore[$index] =
            Test-RemotePackagePresent -Device $device -PackageName $package
        $processPresentBefore[$index] =
            Test-RemoteProcessPresent -Device $device -ProcessName $package
        $preflightComplete[$index] = $true
        if ($packagePresentBefore[$index]) {
            throw "Fleet Agent is already installed on $device; refusing an irreversible replacement."
        }
        $priorProjection = Get-HubInspect -DeviceId ([string]$profiles[$index].value.device_id)
        if ($null -ne $priorProjection) {
            throw "Fleet Hub already contains device identity $($profiles[$index].value.device_id)."
        }
    }

    for ($index = 0; $index -lt 2; $index++) {
        $device = $Serial[$index]
        $mutationAttempted[$index] = $true
        $install = Invoke-Adb -Device $device -Arguments @("install", $apkPath) `
            -TimeoutSeconds $PackageTimeoutSeconds
        if (($install.output -join "`n") -notmatch '(?m)^Success\s*$') {
            throw "Fleet Agent install did not report Success on $device."
        }
        Invoke-Adb -Device $device -Arguments @(
            "shell", "run-as", $package,
            "mkdir", "-p", "files/fleet-agent") | Out-Null
        Invoke-Adb -Device $device -Arguments @(
            "shell", "run-as", $package,
            "chmod", "700", "files/fleet-agent") | Out-Null
        Invoke-Adb -Device $device -Arguments @(
            "exec-in", "run-as", $package,
            "dd", "of=files/fleet-agent/profile.json"
        ) -InputBytes $profiles[$index].bytes | Out-Null
        Invoke-Adb -Device $device -Arguments @(
            "exec-in", "run-as", $package,
            "dd", "of=files/fleet-agent/signing-seed.bin"
        ) -InputBytes $seedFiles[$index].bytes | Out-Null
        Invoke-Adb -Device $device -Arguments @(
            "shell", "run-as", $package,
            "chmod", "600",
            "files/fleet-agent/profile.json",
            "files/fleet-agent/signing-seed.bin") | Out-Null

        $ordinaryLaunch = Invoke-Adb -Device $device -Arguments @(
            "shell", "am", "start", "-W", "-n", $activity)
        $ordinaryLaunchText = $ordinaryLaunch.output -join "`n"
        if ($ordinaryLaunchText -notmatch '(?m)^Status:\s*ok\s*$' -or
            $ordinaryLaunchText -notmatch (
                '(?m)^Activity:\s*' + [regex]::Escape($activity) + '\s*$') -or
            $ordinaryLaunchText -match '(?im)^\s*(Error|Exception):') {
            throw "Fleet Agent ordinary launch did not confirm the exact Activity."
        }
        Start-Sleep -Seconds 2
        Assert-ServiceStopped -Device $device
        if ($null -ne (Get-HubInspect -DeviceId ([string]$profiles[$index].value.device_id))) {
            throw "Ordinary Fleet Agent launch was not inert on $device."
        }
    }

    for ($index = 0; $index -lt 2; $index++) {
        Invoke-Adb -Device $Serial[$index] -Arguments @(
            "shell", "am", "start",
            "-a", "io.github.mesmerprism.rustyquest.fleetagent.DEBUG_START",
            "-n", $activity) | Out-Null
    }

    $fresh = @()
    for ($index = 0; $index -lt 2; $index++) {
        $deviceId = [string]$profiles[$index].value.device_id
        $projection = Wait-HubFreshness -DeviceId $deviceId `
            -Expected fresh -TimeoutSeconds $freshTimeoutSeconds
        $identity = $projection.row.identity
        if ([string]$identity.device_id -ne $deviceId -or
            [string]$identity.display_name -ne [string]$profiles[$index].value.display_name -or
            [string]$identity.model -ne [string]$profiles[$index].value.model -or
            [string]$identity.hardware_class -ne [string]$profiles[$index].value.hardware_class -or
            [long]$identity.identity_revision -ne
                [long]$profiles[$index].value.identity_revision -or
            [long]$projection.row.accepted_revision -le 0 -or
            [long]$projection.row.accepted_at_ms -le 0 -or
            [string]$projection.row.source_epoch -notmatch '^agent\.[0-9a-f]{24}$') {
            throw "Fleet Hub projection is not bound to profile $index identity and producer generation."
        }
        $battery = Get-DeviceBattery -Device $Serial[$index]
        if ($null -eq $projection.row.battery_percent -or
            [Math]::Abs([int]$projection.row.battery_percent - $battery.battery_percent) -gt 1 -or
            [bool]$projection.row.charging -ne [bool]$battery.charging) {
            throw "Fleet Hub power projection does not match Android authority on $($Serial[$index])."
        }
        if ($null -ne $projection.row.foreground_app -or
            [string]$projection.row.application.foreground_state -ne "unknown" -or
            [string]$projection.row.application.foreground_authority -ne "platform_limited") {
            throw "Fleet Agent invented participating-app foreground authority on $($Serial[$index])."
        }
        $receipt = Get-AppPrivateText -Device $Serial[$index] `
            -RelativePath "files/fleet-agent/last-receipt.json" | ConvertFrom-Json
        if ([string]$receipt.status -ne "accepted_by_hub" -or
            [int]$receipt.http_status -ne 200 -or
            [string]$receipt.detail -ne "accepted" -or
            [long]$receipt.source_revision -le 0 -or
            [string]$receipt.source_epoch -ne [string]$projection.row.source_epoch -or
            [long]$projection.row.accepted_at_ms -lt ([long]$receipt.attempted_at_ms - 1000) -or
            [long]$projection.row.accepted_at_ms -gt
                ([long]$receipt.attempted_at_ms + 10000)) {
            throw "Fleet Agent did not retain an accepted Hub receipt on $($Serial[$index])."
        }
        Assert-HubAcceptedEvent `
            -DeviceId $deviceId `
            -SourceRevision ([long]$receipt.source_revision) `
            -AcceptedRevision ([long]$projection.row.accepted_revision) `
            -AcceptedAtMs ([long]$projection.row.accepted_at_ms)
        $deviceDir = Join-Path $evidenceFull "device-$($index + 1)"
        New-Item -ItemType Directory -Path $deviceDir | Out-Null
        $projection | ConvertTo-Json -Depth 20 |
            Set-Content -Encoding UTF8 -LiteralPath (Join-Path $deviceDir "fresh-inspect.json")
        $receipt | ConvertTo-Json -Depth 12 |
            Set-Content -Encoding UTF8 -LiteralPath (Join-Path $deviceDir "accepted-receipt.json")
        $fresh += $projection
        $summary.devices += [ordered]@{
            serial = $Serial[$index]
            device_id = $deviceId
            fresh = $true
            battery_percent = [int]$projection.row.battery_percent
            charging = [bool]$projection.row.charging
            foreground_state = [string]$projection.row.application.foreground_state
            foreground_authority = [string]$projection.row.application.foreground_authority
            accepted_revision = [long]$projection.row.accepted_revision
            accepted_at_ms = [long]$projection.row.accepted_at_ms
            source_revision = [long]$receipt.source_revision
            source_epoch = [string]$projection.row.source_epoch
            stale = $false
            offline = $false
            bounded_fatal_count = $null
        }
    }

    Stop-Agent -Device $Serial[0]
    $firstStoppedBaseline = Get-HubInspect -DeviceId (
        [string]$profiles[0].value.device_id)
    if ($null -eq $firstStoppedBaseline -or
        [string]$firstStoppedBaseline.row.freshness -ne "fresh") {
        throw "First stopped device did not retain a fresh stop baseline."
    }
    $firstStale = Wait-HubFreshness `
        -DeviceId ([string]$profiles[0].value.device_id) `
        -Expected stale `
        -TimeoutSeconds $staleTimeoutSeconds `
        -StoppedBaseline $firstStoppedBaseline `
        -MinimumAgeMs ($StaleAfterMs + 1) `
        -MaximumAgeMs $OfflineAfterMs `
        -ContinuityDeviceId ([string]$profiles[1].value.device_id) `
        -ContinuityAcceptedAtMs ([long]$fresh[1].row.accepted_at_ms)
    $firstOffline = Wait-HubFreshness `
        -DeviceId ([string]$profiles[0].value.device_id) `
        -Expected offline `
        -TimeoutSeconds $offlineTimeoutSeconds `
        -StoppedBaseline $firstStoppedBaseline `
        -MinimumAgeMs ($OfflineAfterMs + 1) `
        -ContinuityDeviceId ([string]$profiles[1].value.device_id) `
        -ContinuityAcceptedAtMs ([long]$fresh[1].row.accepted_at_ms)
    $summary.devices[0].stale = $true
    $summary.devices[0].offline = $true
    $firstStale | ConvertTo-Json -Depth 20 |
        Set-Content -Encoding UTF8 -LiteralPath (
            Join-Path $evidenceFull "device-1\stale-inspect.json")
    $firstOffline | ConvertTo-Json -Depth 20 |
        Set-Content -Encoding UTF8 -LiteralPath (
            Join-Path $evidenceFull "device-1\offline-inspect.json")

    Stop-Agent -Device $Serial[1]
    $secondStoppedBaseline = Get-HubInspect -DeviceId (
        [string]$profiles[1].value.device_id)
    if ($null -eq $secondStoppedBaseline -or
        [string]$secondStoppedBaseline.row.freshness -ne "fresh") {
        throw "Second stopped device did not retain a fresh stop baseline."
    }
    $secondStale = Wait-HubFreshness `
        -DeviceId ([string]$profiles[1].value.device_id) `
        -Expected stale `
        -TimeoutSeconds $staleTimeoutSeconds `
        -StoppedBaseline $secondStoppedBaseline `
        -MinimumAgeMs ($StaleAfterMs + 1) `
        -MaximumAgeMs $OfflineAfterMs
    $secondOffline = Wait-HubFreshness `
        -DeviceId ([string]$profiles[1].value.device_id) `
        -Expected offline `
        -TimeoutSeconds $offlineTimeoutSeconds `
        -StoppedBaseline $secondStoppedBaseline `
        -MinimumAgeMs ($OfflineAfterMs + 1)
    $summary.devices[1].stale = $true
    $summary.devices[1].offline = $true
    $secondStale | ConvertTo-Json -Depth 20 |
        Set-Content -Encoding UTF8 -LiteralPath (
            Join-Path $evidenceFull "device-2\stale-inspect.json")
    $secondOffline | ConvertTo-Json -Depth 20 |
        Set-Content -Encoding UTF8 -LiteralPath (
            Join-Path $evidenceFull "device-2\offline-inspect.json")

    for ($index = 0; $index -lt 2; $index++) {
        $logs = Get-BoundedLogs -Device $Serial[$index] -StartedAt $logStartedAt[$index]
        $deviceDir = Join-Path $evidenceFull "device-$($index + 1)"
        $logs.main | Set-Content -Encoding UTF8 -LiteralPath (
            Join-Path $deviceDir "bounded-logcat.txt")
        $logs.crash | Set-Content -Encoding UTF8 -LiteralPath (
            Join-Path $deviceDir "crash-buffer.txt")
        $summary.devices[$index].bounded_fatal_count = $logs.fatal_count
        if ($logs.fatal_count -ne 0) {
            throw "Fleet Agent fatal evidence was observed on $($Serial[$index])."
        }
    }
    $summary.status = "pass"
} catch {
    $summary.status = "fail"
    $summary.failure = $_.Exception.Message
    $primaryFailure = $_.Exception.Message
} finally {
    for ($index = 0; $index -lt 2; $index++) {
        $device = $Serial[$index]
        $cleanupErrors = [Collections.Generic.List[string]]::new()
        $cleanup = [ordered]@{
            serial = $device
            mutation_attempted = [bool]$mutationAttempted[$index]
            preflight_complete = [bool]$preflightComplete[$index]
            stop_requested = $false
            target_force_stopped = $false
            private_inputs_removed = $false
            package_absence_restored = $false
            process_absent = $false
            package_state_restored = $false
            process_state_restored = $false
            errors = @()
        }
        try {
            if ($mutationAttempted[$index]) {
                $cleanupState = Invoke-Adb -Device $device -Arguments @("get-state") `
                    -AllowFailure
                $transportAvailable =
                    -not $cleanupState.timed_out -and
                    $cleanupState.exit_code -eq 0 -and
                    (($cleanupState.output -join "").Trim()) -eq "device"
                if (-not $transportAvailable) {
                    $cleanupErrors.Add("Quest transport is unavailable during cleanup.")
                } else {
                    $packagePresent = $null
                    try {
                        $packagePresent =
                            Test-RemotePackagePresent -Device $device -PackageName $package
                    } catch {
                        $cleanupErrors.Add(
                            "Unable to inspect Fleet Agent package during cleanup: " +
                            $_.Exception.Message)
                    }
                    if ($null -ne $packagePresent) {
                        if ($packagePresent) {
                            try {
                                Stop-Agent -Device $device
                                $cleanup.stop_requested = $true
                            } catch {
                                $cleanupErrors.Add("Explicit stop failed: $($_.Exception.Message)")
                            }
                            try {
                                Invoke-Adb -Device $device -Arguments @(
                                    "shell", "am", "force-stop", $package) | Out-Null
                                $cleanup.target_force_stopped = $true
                            } catch {
                                $cleanupErrors.Add("Target force-stop failed: $($_.Exception.Message)")
                            }
                            try {
                                $privateFiles = @(
                                    "files/fleet-agent/profile.json",
                                    "files/fleet-agent/signing-seed.bin",
                                    "files/fleet-agent/last-receipt.json",
                                    "files/fleet-agent/last-receipt.json.tmp")
                                foreach ($privateFile in $privateFiles) {
                                    Invoke-Adb -Device $device -Arguments @(
                                        "shell", "run-as", $package,
                                        "rm", "-f", $privateFile) | Out-Null
                                }
                                Invoke-Adb -Device $device -Arguments @(
                                    "shell", "run-as", $package,
                                    "rmdir", "files/fleet-agent"
                                ) -AllowFailure | Out-Null
                                Invoke-Adb -Device $device -Arguments @(
                                    "shell", "run-as", $package,
                                    "ls", "files") | Out-Null
                                foreach ($privateFile in $privateFiles) {
                                    $privateProbe = Invoke-Adb -Device $device -Arguments @(
                                        "shell", "run-as", $package,
                                        "test", "!", "-e", $privateFile) -AllowFailure
                                    if ($privateProbe.exit_code -ne 0) {
                                        throw "App-private Fleet Agent input was not proven absent: $privateFile"
                                    }
                                }
                                $cleanup.private_inputs_removed = $true
                            } catch {
                                $cleanupErrors.Add("Private-input removal failed: $($_.Exception.Message)")
                            }
                            try {
                                $uninstall = Invoke-Adb -Device $device -Arguments @(
                                    "uninstall", $package) -TimeoutSeconds $PackageTimeoutSeconds
                                if (($uninstall.output -join "`n") -notmatch '(?m)^Success\s*$') {
                                    throw "Fleet Agent uninstall did not report Success."
                                }
                            } catch {
                                $cleanupErrors.Add("Run-owned uninstall failed: $($_.Exception.Message)")
                            }
                        } else {
                            $cleanup.private_inputs_removed = $true
                        }
                    }
                }
            } else {
                $cleanup.private_inputs_removed = $true
            }

            if ($preflightComplete[$index]) {
                try {
                    $verifiedState = Invoke-Adb -Device $device -Arguments @("get-state")
                    if ((($verifiedState.output -join "").Trim()) -ne "device") {
                        throw "Quest transport did not remain available for cleanup verification."
                    }
                    $packagePresentAfter =
                        Test-RemotePackagePresent -Device $device -PackageName $package
                    $processPresentAfter =
                        Test-RemoteProcessPresent -Device $device -ProcessName $package
                    $cleanup.package_absence_restored = -not $packagePresentAfter
                    $cleanup.process_absent = -not $processPresentAfter
                    $cleanup.package_state_restored =
                        $packagePresentAfter -eq [bool]$packagePresentBefore[$index]
                    $cleanup.process_state_restored =
                        $processPresentAfter -eq [bool]$processPresentBefore[$index]
                } catch {
                    $cleanupErrors.Add("Final cleanup verification failed: $($_.Exception.Message)")
                }
            } else {
                $cleanup.package_absence_restored = $true
                $cleanup.process_absent = $true
                $cleanup.package_state_restored = $true
                $cleanup.process_state_restored = $true
            }
        } catch {
            $cleanupErrors.Add("Unexpected per-device cleanup failure: $($_.Exception.Message)")
        } finally {
            [Array]::Clear(
                $seedFiles[$index].bytes,
                0,
                $seedFiles[$index].bytes.Length)
        }
        $cleanup.errors = @($cleanupErrors)
        $summary.cleanup += $cleanup
    }
    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $cleanupFailures = @($summary.cleanup | Where-Object {
        -not $_.private_inputs_removed -or
        -not $_.package_state_restored -or
        -not $_.process_state_restored -or
        @($_.errors).Count -ne 0
    })
    if ($cleanupFailures.Count -ne 0) {
        $summary.status = "cleanup-failed"
    }
    $summary |
        ConvertTo-Json -Depth 20 |
        Set-Content -Encoding UTF8 -LiteralPath (Join-Path $evidenceFull "private-summary.json")
}

if ($cleanupFailures.Count -ne 0 -and $null -ne $primaryFailure) {
    throw "Fleet Agent acceptance failed ($primaryFailure) and cleanup was not verified; inspect the private summary."
}
if ($cleanupFailures.Count -ne 0) {
    throw "Fleet Agent device cleanup did not restore the required package-absent state; inspect the private summary."
}
if ($null -ne $primaryFailure) {
    throw "Fleet Agent acceptance failed: $primaryFailure"
}

Write-Output (Join-Path $evidenceFull "private-summary.json")
