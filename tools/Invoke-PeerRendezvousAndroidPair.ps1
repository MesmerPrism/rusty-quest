param(
    [Parameter(Mandatory=$true)][string]$PrimarySerial,
    [Parameter(Mandatory=$true)][string]$SecondarySerial,
    [ValidateSet("agent_board_leased", "user_authorized_serial_scoped")][string]$CoordinationMode = "user_authorized_serial_scoped",
    [string]$PrimaryQuestLeaseId = "",
    [string]$SecondaryQuestLeaseId = "",
    [ValidateSet("group_owner", "client", "either")][string]$PrimaryRolePreference = "group_owner",
    [ValidateSet("group_owner", "client", "either")][string]$SecondaryRolePreference = "client",
    [string]$PrimaryPeerTag = "peer-primary",
    [string]$SecondaryPeerTag = "peer-secondary",
    [string]$SharedSecret = $env:RUSTY_QUEST_BLE_RENDEZVOUS_SECRET,
    [string]$RunId = "",
    [int]$ServerDurationSeconds = 35,
    [int]$ClientDurationSeconds = 30,
    [string]$ApkPath = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$OutDir = "",
    [switch]$SkipInstall,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$smokePath = Join-Path $PSScriptRoot "Invoke-PeerRendezvousAndroidSmoke.ps1"
$package = "io.github.mesmerprism.rustyquest.peer_rendezvous"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "ble-pair-" + (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
}
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $repoRoot "target\peer-rendezvous-android\rusty-quest-peer-rendezvous.apk"
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $repoRoot "target\peer-rendezvous-pairs\$RunId"
}

function Test-SafeTag([string]$Value, [int]$Maximum = 32) {
    return $Value.Length -ge 4 -and $Value.Length -le $Maximum -and $Value -match '^[A-Za-z0-9._-]+$'
}
if (-not (Test-SafeTag $RunId 28)) {
    throw "RunId must be a 4..=28 character safe token so phase run ids remain bounded."
}
foreach ($tag in @($PrimaryPeerTag, $SecondaryPeerTag)) {
    if (-not (Test-SafeTag $tag)) {
        throw "Peer tags must be 4..=32 character safe tokens."
    }
}
if ($PrimaryPeerTag -eq $SecondaryPeerTag) {
    throw "PrimaryPeerTag and SecondaryPeerTag must be distinct."
}
if ($PrimarySerial -eq $SecondarySerial) {
    throw "PrimarySerial and SecondarySerial must be distinct."
}
if ($CoordinationMode -eq "agent_board_leased" -and
        ([string]::IsNullOrWhiteSpace($PrimaryQuestLeaseId) -or
         [string]::IsNullOrWhiteSpace($SecondaryQuestLeaseId) -or
         $PrimaryQuestLeaseId -eq $SecondaryQuestLeaseId)) {
    throw "Agent Board coordination requires two distinct Quest lease ids."
}
if ($CoordinationMode -eq "user_authorized_serial_scoped" -and
        (-not [string]::IsNullOrWhiteSpace($PrimaryQuestLeaseId) -or
         -not [string]::IsNullOrWhiteSpace($SecondaryQuestLeaseId))) {
    throw "User-authorized serial-scoped coordination must not claim Agent Board leases."
}
if ($ServerDurationSeconds -lt 20 -or $ServerDurationSeconds -gt 120 -or
        $ClientDurationSeconds -lt 20 -or $ClientDurationSeconds -gt 120 -or
        $ServerDurationSeconds -le $ClientDurationSeconds) {
    throw "Server/client durations must be 20..=120 seconds and server must outlive client."
}
if (-not (Test-Path -LiteralPath $smokePath)) {
    throw "Peer rendezvous smoke wrapper not found: $smokePath"
}

$secretGenerated = $false
if ([string]::IsNullOrWhiteSpace($SharedSecret)) {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    $SharedSecret = ([System.BitConverter]::ToString($bytes)).Replace("-", "").ToLowerInvariant()
    $secretGenerated = $true
}
if ($SharedSecret.Length -lt 16 -or $SharedSecret.Length -gt 128) {
    throw "SharedSecret must contain 16..=128 characters."
}

$summary = [ordered]@{
    schema = "rusty.quest.peer_rendezvous_android_pair.v1"
    run_id = $RunId
    status = "planned"
    primary_serial = $PrimarySerial
    secondary_serial = $SecondarySerial
    coordination_mode = $CoordinationMode
    primary_quest_lease_id = if ($CoordinationMode -eq "agent_board_leased") { $PrimaryQuestLeaseId } else { $null }
    secondary_quest_lease_id = if ($CoordinationMode -eq "agent_board_leased") { $SecondaryQuestLeaseId } else { $null }
    dry_run = [bool]$DryRun
    role_swap_required = $true
    role_swap_completed = $false
    reconnect_required_each_phase = $true
    authenticated_phase_count = 0
    shared_secret_recorded = $false
    shared_secret_source = if ($secretGenerated) { "generated_ephemeral_test_secret" } else { "caller_or_environment" }
    raw_bluetooth_addresses_redacted = $true
    media_payload_bytes = 0
    wifi_direct_mutations_executed = 0
    manifold_commands_executed = 0
    phases = @()
    device_state_before = @()
    device_state_after = @()
    cleanup = $null
}
if ($DryRun) {
    $summary.status = "dry_run"
    $summary.phases = @(
        [ordered]@{ name = "primary_server"; server_serial = $PrimarySerial; client_serial = $SecondarySerial },
        [ordered]@{ name = "secondary_server"; server_serial = $SecondarySerial; client_serial = $PrimarySerial }
    )
    $summary | ConvertTo-Json -Depth 12
    return
}
if (-not (Test-Path -LiteralPath $Adb)) {
    throw "ADB not found: $Adb"
}
if (-not $SkipInstall -and -not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK not found: $ApkPath"
}
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target")).TrimEnd("\")
$outFull = [System.IO.Path]::GetFullPath($OutDir).TrimEnd("\")
if (-not $outFull.StartsWith($targetRoot + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must remain under the repo target directory: $outFull"
}
New-Item -ItemType Directory -Force -Path $outFull | Out-Null
$summaryPath = Join-Path $outFull "summary.json"

function Write-Text([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}

function Read-Json([string]$Path, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Label is missing: $Path"
    }
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Invoke-DeviceAdb([string]$Serial, [string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    return [pscustomobject]@{ exit_code = $LASTEXITCODE; output = @($output) }
}

function Get-DeviceBoundaryState([string]$Serial) {
    $state = Invoke-DeviceAdb $Serial @("get-state")
    if ($state.exit_code -ne 0 -or ($state.output -join "").Trim() -ne "device") {
        throw "ADB device state is not ready for $Serial."
    }
    $bluetooth = Invoke-DeviceAdb $Serial @("shell", "settings", "get", "global", "bluetooth_on")
    $p2p = Invoke-DeviceAdb $Serial @("shell", "ip", "-4", "-o", "addr", "show", "dev", "p2p0")
    return [ordered]@{
        serial = $Serial
        bluetooth_on = ($bluetooth.output -join "`n").Trim()
        p2p0_ipv4 = ($p2p.output -join "`n").Trim()
    }
}

function Assert-ReceiptPass($ChildSummary, [string]$ExpectedRole, [string]$Label) {
    if ([string]$ChildSummary.status -ne "pass" -or
            [string]$ChildSummary.receipt.status -ne "pass" -or
            [string]$ChildSummary.receipt.role -ne $ExpectedRole -or
            [string]$ChildSummary.receipt_validation.status -ne "pass") {
        throw "$Label did not produce a validated pass receipt."
    }
    $receipt = $ChildSummary.receipt
    if (-not [bool]$receipt.connected -or
            -not [bool]$receipt.disconnected -or
            [int]$receipt.reconnects_completed -lt 1 -or
            -not [bool]$receipt.post_reconnect_message_authenticated -or
            [int]$receipt.authentication_failures -ne 0 -or
            -not [bool]$receipt.raw_bluetooth_addresses_redacted -or
            [int64]$receipt.media_payload_bytes -ne 0 -or
            [int]$receipt.wifi_direct_mutations_executed -ne 0 -or
            [int]$receipt.manifold_commands_executed -ne 0 -or
            -not [bool]$receipt.cleanup_complete -or
            @($receipt.issue_codes).Count -ne 0) {
        throw "$Label receipt failed reconnect, redaction, boundary, or cleanup acceptance."
    }
}

$roleJob = {
    param(
        $RepoRoot,
        $SmokePath,
        $Serial,
        $LeaseId,
        $CoordinationMode,
        $Mode,
        $ChildRunId,
        $SessionTag,
        $PeerTag,
        $Secret,
        $DurationSeconds,
        $RolePreference,
        $Apk,
        $ChildOutDir,
        $SkipInstallValue
    )
    Set-Location $RepoRoot
    $params = @{
        Serial = $Serial
        CoordinationMode = $CoordinationMode
        Mode = $Mode
        RunId = $ChildRunId
        SessionTag = $SessionTag
        PeerTag = $PeerTag
        SharedSecret = $Secret
        DurationSeconds = $DurationSeconds
        RolePreference = $RolePreference
        ApkPath = $Apk
        OutDir = $ChildOutDir
    }
    if ($CoordinationMode -eq "agent_board_leased") {
        $params["QuestLeaseId"] = $LeaseId
    }
    if ($SkipInstallValue) {
        $params["SkipInstall"] = $true
    }
    & $SmokePath @params
}

function Invoke-PairPhase {
    param(
        [string]$Name,
        [string]$Suffix,
        [string]$ServerSerial,
        [string]$ServerLeaseId,
        [string]$ServerPeerTag,
        [string]$ServerRolePreference,
        [string]$ClientSerial,
        [string]$ClientLeaseId,
        [string]$ClientPeerTag,
        [string]$ClientRolePreference,
        [bool]$SkipPhaseInstall
    )
    $phaseDir = Join-Path $outFull $Name
    $serverDir = Join-Path $phaseDir "server"
    $clientDir = Join-Path $phaseDir "client"
    New-Item -ItemType Directory -Force -Path $serverDir, $clientDir | Out-Null
    $sessionTag = "$RunId-$Suffix"
    $serverRunId = "$RunId-$Suffix-s"
    $clientRunId = "$RunId-$Suffix-c"
    $serverJob = $null
    $clientJob = $null
    try {
        $serverJob = Start-Job -ScriptBlock $roleJob -ArgumentList @(
            $repoRoot, $smokePath, $ServerSerial, $ServerLeaseId, $CoordinationMode, "server",
            $serverRunId, $sessionTag, $ServerPeerTag, $SharedSecret,
            $ServerDurationSeconds, $ServerRolePreference, $ApkPath, $serverDir,
            $SkipPhaseInstall)
        Start-Sleep -Seconds 4
        $clientJob = Start-Job -ScriptBlock $roleJob -ArgumentList @(
            $repoRoot, $smokePath, $ClientSerial, $ClientLeaseId, $CoordinationMode, "client",
            $clientRunId, $sessionTag, $ClientPeerTag, $SharedSecret,
            $ClientDurationSeconds, $ClientRolePreference, $ApkPath, $clientDir,
            $SkipPhaseInstall)
        Wait-Job -Job @($serverJob, $clientJob) -Timeout ($ServerDurationSeconds + 70) | Out-Null
        $serverOutput = Receive-Job -Job $serverJob -Keep 2>&1 | Out-String
        $clientOutput = Receive-Job -Job $clientJob -Keep 2>&1 | Out-String
        Write-Text (Join-Path $phaseDir "server-wrapper-output.txt") $serverOutput
        Write-Text (Join-Path $phaseDir "client-wrapper-output.txt") $clientOutput
        if ($serverJob.State -ne "Completed" -or $clientJob.State -ne "Completed") {
            throw "$Name role jobs did not both complete: server=$($serverJob.State) client=$($clientJob.State)"
        }
        $serverSummaryPath = Join-Path $serverDir "summary.json"
        $clientSummaryPath = Join-Path $clientDir "summary.json"
        $serverSummary = Read-Json $serverSummaryPath "$Name server summary"
        $clientSummary = Read-Json $clientSummaryPath "$Name client summary"
        Assert-ReceiptPass $serverSummary "server" "$Name server"
        Assert-ReceiptPass $clientSummary "client" "$Name client"
        if ([string]$serverSummary.receipt.session_tag -ne $sessionTag -or
                [string]$clientSummary.receipt.session_tag -ne $sessionTag -or
                [string]$serverSummary.receipt.peer_tag -eq [string]$clientSummary.receipt.peer_tag) {
            throw "$Name session or peer identity correlation failed."
        }
        return [ordered]@{
            name = $Name
            status = "pass"
            session_tag = $sessionTag
            server_serial = $ServerSerial
            client_serial = $ClientSerial
            server_summary_path = $serverSummaryPath
            client_summary_path = $clientSummaryPath
            server_receipt = $serverSummary.receipt
            client_receipt = $clientSummary.receipt
        }
    } finally {
        foreach ($job in @($serverJob, $clientJob)) {
            if ($null -ne $job) {
                if ($job.State -notin @("Completed", "Failed", "Stopped")) {
                    Stop-Job -Job $job -ErrorAction SilentlyContinue
                }
                Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

$beforeRows = @()
foreach ($serial in @($PrimarySerial, $SecondarySerial)) {
    $logcatClear = Invoke-DeviceAdb $serial @("logcat", "-c")
    if ($logcatClear.exit_code -ne 0) {
        throw "Could not clear the bounded BLE logcat window for $serial."
    }
    $beforeRows += Get-DeviceBoundaryState $serial
}
$summary.device_state_before = $beforeRows

$failure = $null
try {
    $phaseA = Invoke-PairPhase `
        -Name "phase-a-primary-server" `
        -Suffix "a" `
        -ServerSerial $PrimarySerial `
        -ServerLeaseId $PrimaryQuestLeaseId `
        -ServerPeerTag $PrimaryPeerTag `
        -ServerRolePreference $PrimaryRolePreference `
        -ClientSerial $SecondarySerial `
        -ClientLeaseId $SecondaryQuestLeaseId `
        -ClientPeerTag $SecondaryPeerTag `
        -ClientRolePreference $SecondaryRolePreference `
        -SkipPhaseInstall ([bool]$SkipInstall)
    $phaseB = Invoke-PairPhase `
        -Name "phase-b-secondary-server" `
        -Suffix "b" `
        -ServerSerial $SecondarySerial `
        -ServerLeaseId $SecondaryQuestLeaseId `
        -ServerPeerTag $SecondaryPeerTag `
        -ServerRolePreference $SecondaryRolePreference `
        -ClientSerial $PrimarySerial `
        -ClientLeaseId $PrimaryQuestLeaseId `
        -ClientPeerTag $PrimaryPeerTag `
        -ClientRolePreference $PrimaryRolePreference `
        -SkipPhaseInstall $true
    $summary.phases = @($phaseA, $phaseB)
    $summary.authenticated_phase_count = 2
    $summary.role_swap_completed = $true
    $summary.status = "pass"
} catch {
    $failure = $_.Exception.Message.Replace($SharedSecret, "<redacted>")
    $summary.status = "fail"
    $summary.error = $failure
} finally {
    $cleanupRows = @()
    $afterRows = @()
    $appFatalCount = 0
    foreach ($serial in @($PrimarySerial, $SecondarySerial)) {
        $forceStop = Invoke-DeviceAdb $serial @("shell", "am", "force-stop", $package)
        $pidRead = Invoke-DeviceAdb $serial @("shell", "pidof", $package)
        $afterRows += Get-DeviceBoundaryState $serial
        $logcat = Invoke-DeviceAdb $serial @(
            "logcat", "-d", "-v", "threadtime", "-s",
            "RustyBleRendezvous:V", "AndroidRuntime:E", "*:S")
        $logcatText = $logcat.output -join "`n"
        Write-Text (Join-Path $outFull "$serial-logcat.txt") $logcatText
        $appFatalCount += @($logcat.output | Where-Object {
            [string]$_ -match 'FATAL EXCEPTION|Process:\s+io\.github\.mesmerprism\.rustyquest\.peer_rendezvous'
        }).Count
        $cleanupRows += [ordered]@{
            serial = $serial
            force_stop_exit_code = $forceStop.exit_code
            package_pid_absent = [string]::IsNullOrWhiteSpace(($pidRead.output -join "").Trim())
        }
    }
    $summary.device_state_after = $afterRows
    $boundaryStateStable = $true
    foreach ($before in @($summary.device_state_before)) {
        $after = @($afterRows | Where-Object { $_.serial -eq $before.serial } | Select-Object -First 1)[0]
        if ($null -eq $after -or
                [string]$after.bluetooth_on -ne [string]$before.bluetooth_on -or
                [string]$after.p2p0_ipv4 -ne [string]$before.p2p0_ipv4) {
            $boundaryStateStable = $false
        }
    }
    $summary.cleanup = [ordered]@{
        devices = $cleanupRows
        package_processes_absent = [bool](@($cleanupRows | Where-Object { -not $_.package_pid_absent }).Count -eq 0)
        bluetooth_and_p2p0_state_stable = $boundaryStateStable
        app_fatal_count = $appFatalCount
        complete = [bool](
            @($cleanupRows | Where-Object { -not $_.package_pid_absent }).Count -eq 0 -and
            $boundaryStateStable -and
            $appFatalCount -eq 0)
    }
    if (-not [bool]$summary.cleanup.complete) {
        $summary.status = "fail"
        if ([string]::IsNullOrWhiteSpace($failure)) {
            $failure = "Peer rendezvous package process remained after cleanup."
            $summary.error = $failure
        }
    }
    $summaryJson = $summary | ConvertTo-Json -Depth 24
    $artifactText = @(
        Get-ChildItem -LiteralPath $outFull -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Extension -in @(".json", ".txt") } |
            ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName }
    ) -join "`n"
    $scanText = $summaryJson + "`n" + $artifactText
    $addressPatternFound = [regex]::IsMatch(
        $scanText,
        '(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}')
    $secretPatternFound = $scanText.Contains($SharedSecret)
    $summary.raw_bluetooth_address_pattern_found = $addressPatternFound
    $summary.shared_secret_pattern_found = $secretPatternFound
    if ($addressPatternFound -or $secretPatternFound) {
        $summary.status = "fail"
        $failure = "Raw Bluetooth address or shared secret found in pair artifacts."
        $summary.error = $failure
    }
    Write-Text $summaryPath ($summary | ConvertTo-Json -Depth 24)
}

if ($null -eq $failure) {
    $pairValidationPath = Join-Path $outFull "pair-validation.json"
    $pairValidationOutput = & cargo run --quiet -p rusty-quest-device-link `
        --bin validate_ble_rendezvous -- pair $summaryPath 2>&1
    $pairValidationExit = $LASTEXITCODE
    $pairValidationText = ($pairValidationOutput -join "`n").Trim()
    Write-Text $pairValidationPath $pairValidationText
    if ($pairValidationExit -ne 0) {
        $failure = "BLE pair contract validation failed: $pairValidationText"
        $summary.status = "fail"
        $summary.error = $failure
    } else {
        $summary["pair_validation"] = $pairValidationText | ConvertFrom-Json
    }
    Write-Text $summaryPath ($summary | ConvertTo-Json -Depth 24)
}

if ($null -ne $failure) {
    throw $failure
}
$summary | ConvertTo-Json -Depth 24
